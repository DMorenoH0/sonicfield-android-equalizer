package com.sonicfield.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/** Short, digitally attenuated comparison tones for subjective adjustment; this is not a hearing test. */
class ListeningTestPlayer {
    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var stopTask: Runnable? = null
    private val dsp = PcmDsp()

    fun play(frequencyHz: Int, gainsDb: List<Float>, processingEnabled: Boolean, preampDb: Float) {
        stop()
        val sampleRate = 44_100
        val frames = sampleRate / 2
        val pcmFloat = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            // Quiet 4% full-scale signal, attenuated again at output for safe subjective comparison.
            val sample = (sin(2.0 * PI * frequencyHz * frame / sampleRate) * 0.04).toFloat()
            pcmFloat[frame * 2] = sample
            pcmFloat[frame * 2 + 1] = sample
        }
        try {
            dsp.update(gainsDb, preampDb, processingEnabled, megaBass = false, limiter = true)
            dsp.process(pcmFloat, pcmFloat.size, 2, sampleRate)
            val pcm = ShortArray(pcmFloat.size) { index -> (pcmFloat[index].coerceIn(-1f, 0.9999695f) * 32768f).toInt().toShort() }
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track = audioTrack
            audioTrack.setVolume(0.12f)
            audioTrack.write(pcm, 0, pcm.size)
            audioTrack.play()
            stopTask = Runnable { stop() }.also { handler.postDelayed(it, 650) }
        } catch (_: Exception) { stop() }
    }

    fun stop() {
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
        try { track?.stop() } catch (_: Exception) { }
        try { track?.release() } catch (_: Exception) { }
        track = null
    }
}

