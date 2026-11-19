package com.sonicfield.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetCatalogTest {
    @Test fun allCurvesHaveTenFiniteBoundedBands() {
        PresetCatalog.names.forEach { name ->
            val gains = PresetCatalog.gains(name)
            assertEquals(name, 10, gains.size)
            assertTrue("$name exceeds the +4 dB app cap", gains.all { it.isFinite() && it in -6f..4f })
        }
    }

    @Test fun frequencyLabelsAreAscending() {
        assertEquals(10, PresetCatalog.centerFrequenciesHz.size)
        assertTrue(PresetCatalog.centerFrequenciesHz.toList().zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun bassAndVoiceCurvesEmphasizeTheirNamedRanges() {
        val bass = PresetCatalog.gains("Mega bajos")
        val voice = PresetCatalog.gains("Voz clara")
        assertTrue(bass.take(3).average() > bass.drop(4).take(3).average())
        assertTrue(voice.drop(4).take(3).average() > voice.take(3).average())
    }

    @Test fun safetyPreampNeverBoostsAndOffsetsPositiveEqGain() {
        assertEquals(-4f, PresetCatalog.safePreampDb(listOf(0f, 4f), 0f))
        assertEquals(-8f, PresetCatalog.safePreampDb(listOf(0f, 2f), -8f))
        assertEquals(0f, PresetCatalog.safePreampDb(listOf(-2f, 0f), 0f), 0.0001f)
    }

    @Test fun unknownPresetFallsBackToFlat() {
        assertEquals(List(10) { 0f }, PresetCatalog.gains("Perfil no disponible"))
    }
}



