package com.sonicfield.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Decodes an app-selected file to PCM, runs SonicField DSP, and writes the processed PCM to its own AudioTrack. */
class PcmAudioPlayer(
    context: Context,
    private val uri: Uri,
    private val dsp: PcmDsp,
    private val onSessionReady: (Int) -> Unit,
    private val onRouteChanged: (AudioDeviceInfo?) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    @Volatile private var outputTrack: AudioTrack? = null
    @Volatile private var worker: Thread? = null
    val audioSessionId: Int get() = outputTrack?.audioSessionId ?: 0
    val routedDevice: AudioDeviceInfo? get() = outputTrack?.routedDevice

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread(::decodeAndPlay, "SonicField-PCM").also { it.start() }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        // stop() also interrupts a blocking AudioTrack.write on supported Android implementations.
        try { outputTrack?.pause(); outputTrack?.flush(); outputTrack?.stop() } catch (_: IllegalStateException) { }
    }

    private fun decodeAndPlay() {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(appContext, uri, null) }
            val selectedTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("El archivo no contiene una pista de audio compatible")
            extractor.selectTrack(selectedTrack)
            val inputFormat = extractor.getTrackFormat(selectedTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Formato de audio no reconocido")
            decoder = MediaCodec.createDecoderByType(mime).apply { configure(inputFormat, null, null, 0); start() }
            val codec = decoder ?: error("No se pudo crear el decodificador de audio")
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var queuedFrames = 0L
            var sampleRate = inputFormat.integerOr(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var channelCount = inputFormat.integerOr(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceIn(1, 8)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var converted = ShortArray(0)
            var outputFloats = FloatArray(0)

            while (running.get() && !outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("El decodificador no entregó un búfer de entrada")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.integerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate).coerceIn(8_000, 192_000)
                        channelCount = format.integerOr(MediaFormat.KEY_CHANNEL_COUNT, channelCount).coerceIn(1, 8)
                        pcmEncoding = format.integerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        ensureTrack(sampleRate)
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val format = codec.outputFormat
                            sampleRate = format.integerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate).coerceIn(8_000, 192_000)
                            channelCount = format.integerOr(MediaFormat.KEY_CHANNEL_COUNT, channelCount).coerceIn(1, 8)
                            pcmEncoding = format.integerOr(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                            val track = ensureTrack(sampleRate)
                            val bytesPerSample = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> 4
                                AudioFormat.ENCODING_PCM_8BIT -> 1
                                else -> 2
                            }
                            val output = codec.getOutputBuffer(outputIndex) ?: error("El decodificador no entregó PCM de salida")
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val pcm = output.slice().order(ByteOrder.nativeOrder())
                            val inputFrames = info.size / (bytesPerSample * channelCount)
                            val outSamples = inputFrames * 2
                            if (outputFloats.size < outSamples) outputFloats = FloatArray(outSamples)
                            if (converted.size < outSamples) converted = ShortArray(outSamples)
                            downmixToStereo(pcm, inputFrames, channelCount, pcmEncoding, outputFloats)
                            dsp.process(outputFloats, outSamples, 2, sampleRate)
                            for (index in 0 until outSamples) {
                                converted[index] = (outputFloats[index].coerceIn(-1f, 0.9999695f) * 32768f).toInt().toShort()
                            }
                            var written = 0
                            while (running.get() && written < outSamples) {
                                val count = track.write(converted, written, outSamples - written, AudioTrack.WRITE_BLOCKING)
                                if (count < 0) error("AudioTrack rechazó el PCM procesado (código $count)")
                                if (count == 0) Thread.yield() else {
                                    written += count
                                    queuedFrames += count / 2L
                                }
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (outputDone && running.get()) drainOutput(queuedFrames)
        } catch (error: Exception) {
            if (running.get()) onError(error.message?.takeIf(String::isNotBlank) ?: "No se pudo decodificar el archivo de audio")
        } finally {
            running.set(false)
            try { decoder?.stop() } catch (_: Exception) { }
            try { decoder?.release() } catch (_: Exception) { }
            try { extractor?.release() } catch (_: Exception) { }
            val track = outputTrack
            outputTrack = null
            try { track?.release() } catch (_: Exception) { }
        }
    }

    private fun drainOutput(framesWritten: Long) {
        val track = outputTrack ?: return
        try { track.stop() } catch (_: IllegalStateException) { return }
        val deadline = SystemClock.elapsedRealtime() + 1_500
        while (running.get() && SystemClock.elapsedRealtime() < deadline) {
            val played = track.playbackHeadPosition.toLong() and 0xffff_ffffL
            if (played >= framesWritten) break
            try { Thread.sleep(4) } catch (_: InterruptedException) { return }
        }
    }

    @Synchronized
    private fun ensureTrack(sampleRate: Int): AudioTrack {
        outputTrack?.let { current -> if (current.sampleRate == sampleRate) return current }
        outputTrack?.let { current ->
            try { current.stop() } catch (_: Exception) { }
            try { current.release() } catch (_: Exception) { }
        }
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Android no ofrece una salida PCM estéreo a $sampleRate Hz (código $minimum)" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minimum, sampleRate * 2 * 2 / 10))
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "Android no pudo abrir la salida de audio PCM" }
        outputTrack = track
        val handler = Handler(Looper.getMainLooper())
        track.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { route -> onRouteChanged(route.routedDevice) }, handler)
        onSessionReady(track.audioSessionId)
        onRouteChanged(track.routedDevice)
        track.play()
        return track
    }

    private fun downmixToStereo(input: ByteBuffer, frames: Int, channels: Int, encoding: Int, out: FloatArray) {
        val sampleBytes = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        fun sample(frame: Int, channel: Int): Float {
            val offset = (frame * channels + channel) * sampleBytes
            return when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> input.getFloat(offset).takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
                AudioFormat.ENCODING_PCM_8BIT -> ((input.get(offset).toInt() and 0xff) - 128) / 128f
                else -> input.getShort(offset) / 32768f
            }
        }
        for (frame in 0 until frames) {
            val left: Float
            val right: Float
            if (channels == 1) {
                left = sample(frame, 0); right = left
            } else {
                var l = sample(frame, 0)
                var r = sample(frame, 1)
                var lWeight = 1f
                var rWeight = 1f
                if (channels == 3 || channels >= 5) {
                    val center = sample(frame, 2) * 0.70710677f
                    l += center; r += center
                    lWeight += 0.70710677f; rWeight += 0.70710677f
                }
                if (channels == 4) {
                    l += sample(frame, 2) * 0.5f; r += sample(frame, 3) * 0.5f
                    lWeight += 0.5f; rWeight += 0.5f
                } else if (channels >= 5) {
                    if (channels >= 6) {
                        val lfe = sample(frame, 3) * 0.25f
                        l += lfe; r += lfe
                        lWeight += 0.25f; rWeight += 0.25f
                        l += sample(frame, 4) * 0.70710677f
                        r += sample(frame, 5) * 0.70710677f
                        lWeight += 0.70710677f; rWeight += 0.70710677f
                    } else {
                        l += sample(frame, 3) * 0.5f; r += sample(frame, 4) * 0.5f
                        lWeight += 0.5f; rWeight += 0.5f
                    }
                    for (channel in 6 until channels) {
                        val extra = sample(frame, channel) * 0.35f
                        if (channel % 2 == 0) { l += extra; lWeight += 0.35f } else { r += extra; rWeight += 0.35f }
                    }
                }
                left = (l / lWeight.coerceAtLeast(1f)).coerceIn(-1f, 1f)
                right = (r / rWeight.coerceAtLeast(1f)).coerceIn(-1f, 1f)
            }
            out[frame * 2] = left
            out[frame * 2 + 1] = right
        }
    }

    private fun MediaFormat.integerOr(key: String, fallback: Int): Int =
        try { if (containsKey(key)) getInteger(key) else fallback } catch (_: ClassCastException) { fallback }
}
