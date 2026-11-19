package com.sonicfield.audio

/**
 * Curves are conservative listening starting points; they are not per-model
 * corrections and are never presented as measurements. Gains use dB at the
 * standardized graphic band labels, then map to the bands the device exposes.
 */
object PresetCatalog {
    val names = listOf("Plano", "Equilibrado", "Mega bajos", "Voz clara", "Clásica", "Rock", "Jazz", "Electrónica", "Podcast", "Noche")
    val centerFrequenciesHz = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    private val curves = mapOf(
        "Equilibrado" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Mega bajos" to listOf(3f, 4f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Voz clara" to listOf(-2f, -1f, 0f, 1f, 2f, 2f, 2f, 1f, 0f, -1f),
        "Clásica" to listOf(1f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f),
        "Rock" to listOf(2f, 2f, 0f, -1f, 0f, 1f, 2f, 2f, 1f, 1f),
        "Jazz" to listOf(2f, 1f, 0f, 1f, 0f, 0f, 0f, 1f, 2f, 1f),
        "Electrónica" to listOf(3f, 3f, 2f, 0f, -1f, 0f, 0f, 1f, 2f, 2f),
        "Podcast" to listOf(-3f, -2f, -1f, 1f, 2f, 3f, 3f, 1f, 0f, -1f),
        "Noche" to listOf(2f, 1f, 0f, -1f, 0f, 1f, 1f, 0f, -1f, -2f),
    )

    fun gains(name: String): List<Float> = curves[name]?.toList() ?: List(centerFrequenciesHz.size) { 0f }

    /** Conservative input attenuation: requested preamp and the inverse of any positive EQ gain. */
    fun safePreampDb(gains: List<Float>, requestedDb: Float): Float {
        val peakBoost = (gains.maxOrNull() ?: 0f).coerceAtLeast(0f)
        return minOf(requestedDb.coerceIn(-12f, 0f), -peakBoost)
    }
}

