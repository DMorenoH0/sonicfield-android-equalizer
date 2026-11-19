package com.sonicfield.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/** Short, digitally attenuated comparison tones for subjective adjustment; this is not a hearing test. */
class ListeningTestPlayer {
    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var equalizer: Equalizer? = null
    private var stopTask: Runnable? = null

    fun play(frequencyHz: Int, gainsDb: List<Float>, processingEnabled: Boolean, preampDb: Float) {
        stop()
        val sampleRate = 44_100
        val frames = sampleRate / 2
        val pcm = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            // 4% full-scale, then a further 0.12 output multiplier.
            val sample = (sin(2.0 * PI * frequencyHz * frame / sampleRate) * 1_311).toInt().toShort()
            pcm[frame * 2] = sample
            pcm[frame * 2 + 1] = sample
        }
        try {
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
            val safeDb = PresetCatalog.safePreampDb(gainsDb, preampDb)
            audioTrack.setVolume(0.12f * Math.pow(10.0, safeDb / 20.0).toFloat())
            try {
                equalizer = Equalizer(0, audioTrack.audioSessionId).also { eq ->
                    val range = eq.bandLevelRange
                    val used = mutableSetOf<Short>()
                    gainsDb.forEachIndexed { index, gain ->
                        val band = eq.getBand(PresetCatalog.centerFrequenciesHz[index] * 1000)
                        if (used.add(band)) eq.setBandLevel(band, (gain * 100).toInt()
                            .coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                    }
                    eq.enabled = processingEnabled
                }
            } catch (_: Exception) { equalizer?.release(); equalizer = null }
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
        try { equalizer?.release() } catch (_: Exception) { }
        track = null
        equalizer = null
    }
}

