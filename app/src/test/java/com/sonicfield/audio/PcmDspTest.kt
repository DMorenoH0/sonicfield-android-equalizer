package com.sonicfield.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.sqrt

class PcmDspTest {
    @Test fun equalizerChangesMeasuredBandResponse() {
        val gains = List(10) { 0f }.toMutableList().also { it[5] = 6f }
        val atOneKhz = processedRms(1_000, gains = gains)
        val at250Hz = processedRms(250, gains = gains)
        assertTrue("EQ should create a measurable 1 kHz lift relative to 250 Hz", atOneKhz / at250Hz > 1.6)
    }

    @Test fun megaBassAddsLowShelfWithoutReplacingTheEqualizer() {
        val gains = List(10) { 0f }.toMutableList().also { it[5] = 4f }
        val bass60 = processedRms(60, gains = gains, bass = true)
        val mid1k = processedRms(1_000, gains = gains, bass = true)
        val eqOnly1k = processedRms(1_000, gains = gains, bass = false)
        assertTrue("Low shelf must change the same bass frequency against EQ-only", bass60 / processedRms(60, gains = gains, bass = false) > 1.25)
        assertTrue("The 1 kHz EQ band must remain active with bass enabled: ${mid1k / processedRms(1_000, bass = true)}", mid1k > processedRms(1_000, bass = true) * 1.4)
        assertTrue("Turning bass off must remove its low-frequency shelf", bass60 / processedRms(60, gains = gains, bass = false) > 1.25)
        assertTrue("EQ-only path remains measurably different", eqOnly1k > processedRms(1_000) * 1.4)
    }

    @Test fun neutralCurveAndBypassReturnTheOriginalSignal() {
        val input = tone(997, 0.2f)
        val neutral = input.copyOf()
        PcmDsp().apply { update(List(10) { 0f }, 0f, true, false, true); process(neutral, neutral.size, 2, SAMPLE_RATE) }
        assertTrue(neutral.indices.all { kotlin.math.abs(neutral[it] - input[it]) < 0.00001f })

        val bypassed = input.copyOf()
        PcmDsp().apply {
            update(List(10) { 0f }.toMutableList().also { it[5] = 8f }, 0f, false, true, true)
            process(bypassed, bypassed.size, 2, SAMPLE_RATE)
        }
        assertTrue(bypassed.indices.all { kotlin.math.abs(bypassed[it] - input[it]) < 0.00001f })
    }

    @Test fun preampAndLimiterChangeAndBoundTheOutput() {
        val input = tone(1_000, 0.7f)
        val attenuated = input.copyOf()
        PcmDsp().apply { update(List(10) { 0f }, -6f, true, false, false); process(attenuated, attenuated.size, 2, SAMPLE_RATE) }
        val ratio = rms(attenuated, 2_000, attenuated.size) / rms(input, 2_000, input.size)
        assertEquals(10.0.pow(-6.0 / 20.0), ratio, 0.015)

        val overloaded = tone(1_000, 0.98f)
        val dsp = PcmDsp()
        dsp.update(List(10) { 0f }, 0f, true, false, true)
        dsp.process(overloaded, overloaded.size, 2, SAMPLE_RATE)
        assertTrue("The active limiter must keep output below -1 dBFS", dsp.lastStats.outputPeak <= 0.892f)
    }

    @Test fun presetChangesProduceDifferentMeasuredResponsesWhileBassStaysOn() {
        val jazz = processedRms(8_000, gains = PresetCatalog.gains("Jazz"), bass = true)
        val voice = processedRms(8_000, gains = PresetCatalog.gains("Voz clara"), bass = true)
        assertTrue("Different presets must change the 8 kHz output", kotlin.math.abs(jazz - voice) / maxOf(jazz, voice) > 0.08)
        val voiceCurve = PresetCatalog.gains("Voz clara")
        val bassOn = processedRms(60, gains = voiceCurve, bass = true)
        val bassOff = processedRms(60, gains = voiceCurve, bass = false)
        assertTrue("Megabass adds measurable 60 Hz output with the new preset loaded: ${bassOn / bassOff}", bassOn / bassOff > 1.25)
    }

    @Test fun liveParameterUpdatesReachTheSameDspInstance() {
        val dsp = PcmDsp()
        val cityPreset = List(10) { 0f }.toMutableList().also { it[5] = 5f }
        dsp.update(cityPreset, 0f, true, false, true)
        val presetOne = tone(1_000, 0.2f).also { dsp.process(it, it.size, 2, SAMPLE_RATE) }
        val firstLevel = rms(presetOne, SAMPLE_RATE / 5 * 2, presetOne.size)

        dsp.update(PresetCatalog.gains("Plano"), 0f, true, false, true)
        val presetTwo = tone(1_000, 0.2f).also { dsp.process(it, it.size, 2, SAMPLE_RATE) }
        val secondLevel = rms(presetTwo, SAMPLE_RATE / 5 * 2, presetTwo.size)
        assertTrue("Selecting another preset must update the active DSP instance", kotlin.math.abs(firstLevel - secondLevel) / firstLevel > 0.1)

        val activePreset = PresetCatalog.gains("Plano")
        dsp.update(activePreset, 0f, true, true, true)
        val bassOn = tone(60, 0.2f).also { dsp.process(it, it.size, 2, SAMPLE_RATE) }
        dsp.update(activePreset, 0f, true, false, true)
        val bassOff = tone(60, 0.2f).also { dsp.process(it, it.size, 2, SAMPLE_RATE) }
        val liveBassRatio = rms(bassOn, SAMPLE_RATE / 5 * 2, bassOn.size) / rms(bassOff, SAMPLE_RATE / 5 * 2, bassOff.size)
        assertTrue("Megabajos must measurably change the live 60 Hz signal while the same preset remains selected: $liveBassRatio", liveBassRatio > 1.15)
    }

    private fun processedRms(frequency: Int, gains: List<Float> = List(10) { 0f }, bass: Boolean = false): Double {
        val samples = tone(frequency, 0.25f)
        PcmDsp().apply { update(gains, 0f, true, bass, true); process(samples, samples.size, 2, SAMPLE_RATE) }
        return rms(samples, SAMPLE_RATE / 5 * 2, samples.size)
    }

    private fun tone(frequency: Int, amplitude: Float): FloatArray {
        val result = FloatArray(SAMPLE_RATE * 2)
        for (frame in 0 until SAMPLE_RATE) {
            val sample = amplitude * sin(2.0 * PI * frequency * frame / SAMPLE_RATE).toFloat()
            result[frame * 2] = sample
            result[frame * 2 + 1] = sample
        }
        return result
    }

    private fun rms(values: FloatArray, fromSample: Int, untilSample: Int): Double {
        var energy = 0.0
        for (index in fromSample until untilSample) energy += values[index] * values[index]
        return sqrt(energy / (untilSample - fromSample))
    }

    companion object { private const val SAMPLE_RATE = 48_000 }
}
