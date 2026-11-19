package com.sonicfield.audio

import android.media.audiofx.Virtualizer

/** Only the optional spatial effect is delegated to Android; EQ, bass and peak safety are processed in PCM by SonicField. */
class SessionEffects {
    private var virtualizer: Virtualizer? = null
    private var virtualizerSessionId = 0
    private var spatialEnabled = false

    @Suppress("DEPRECATION")
    fun attach(sessionId: Int, spatial: Boolean, strength: Int): String {
        if (!spatial) {
            releaseSpatial()
            return "DSP propio activo en la sesión: ecualizador, megabajos y limitador"
        }
        if (spatialEnabled && virtualizerSessionId == sessionId && virtualizer != null) {
            virtualizer?.let { effect ->
                if (effect.strengthSupported && effect.hasControl()) effect.setStrength(strength.coerceIn(0, 1000).toShort())
            }
            return "DSP propio activo; espacialización Android activa"
        }
        releaseSpatial()
        if (sessionId <= 0) return "DSP propio activo; falta una sesión de salida válida para el efecto espacial"
        return try {
            val effect = Virtualizer(0, sessionId)
            if (!effect.strengthSupported || !effect.hasControl()) {
                effect.release()
                "DSP propio activo; espacialización no controlable en este dispositivo"
            } else {
                effect.setStrength(strength.coerceIn(0, 1000).toShort())
                effect.enabled = true
                virtualizer = effect
                virtualizerSessionId = sessionId
                spatialEnabled = true
                "DSP propio activo; espacialización Android activa"
            }
        } catch (error: Exception) {
            "DSP propio activo; espacialización no disponible: ${error.javaClass.simpleName}"
        }
    }

    private fun releaseSpatial() {
        try { virtualizer?.release() } catch (_: Exception) { }
        virtualizer = null
        virtualizerSessionId = 0
        spatialEnabled = false
    }

    fun release() = releaseSpatial()
}
