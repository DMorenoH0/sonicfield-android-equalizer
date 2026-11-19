package com.sonicfield.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Virtualizer
import android.os.Build
import androidx.annotation.RequiresApi

/** All effects are attached only to SonicField's owned playback session. */
class SessionEffects {
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null

    @Suppress("DEPRECATION")
    fun attach(sessionId: Int, bass: Boolean, spatial: Boolean, limiter: Boolean, strength: Int): String {
        release()
        val unavailable = mutableListOf<String>()
        val safeStrength = strength.coerceIn(0, 1000).toShort()
        if (bass) try {
            BassBoost(0, sessionId).also { effect ->
                if (effect.strengthSupported) {
                    effect.setStrength(safeStrength)
                    effect.enabled = true
                    bassBoost = effect
                } else { effect.release(); unavailable += "graves" }
            }
        } catch (_: Exception) { unavailable += "graves" }
        if (spatial) try {
            Virtualizer(0, sessionId).also { effect ->
                if (effect.strengthSupported) {
                    effect.setStrength(safeStrength)
                    effect.enabled = true
                    virtualizer = effect
                } else { effect.release(); unavailable += "espacio" }
            }
        } catch (_: Exception) { unavailable += "espacio" }
        if (limiter) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) try {
                dynamicsProcessing = createLimiter(sessionId)
            } catch (_: Exception) { unavailable += "limitador" }
            else unavailable += "limitador (Android 9+)"
        }
        val requested = listOf(bass, spatial, limiter).any { it }
        return when {
            !requested -> "Sin efectos adicionales activados"
            unavailable.isEmpty() -> "Efectos compatibles activos en esta sesión"
            else -> "No compatibles en este dispositivo: ${unavailable.distinct().joinToString()}"
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createLimiter(sessionId: Int): DynamicsProcessing {
        val limiter = DynamicsProcessing.Limiter(
            true, true, 0, 5f, 100f, 10f, -2f, 0f,
        )
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
            2,
            false, 0,
            false, 0,
            false, 0,
            true,
        ).apply { setLimiterAllChannelsTo(limiter) }.build()
        return DynamicsProcessing(0, sessionId, config).apply { enabled = true }
    }

    fun release() {
        try { bassBoost?.release() } catch (_: Exception) { }
        try { virtualizer?.release() } catch (_: Exception) { }
        try { dynamicsProcessing?.release() } catch (_: Exception) { }
        bassBoost = null
        virtualizer = null
        dynamicsProcessing = null
    }
}
