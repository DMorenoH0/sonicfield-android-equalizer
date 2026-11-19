package com.sonicfield.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Sample processor used between MediaCodec and AudioTrack; it never relies on optional OEM EQ effects. */
class PcmDsp {
    private data class Settings(
        val gainsDb: List<Float> = List(10) { 0f },
        val preampDb: Float = 0f,
        val enabled: Boolean = true,
        val megaBass: Boolean = false,
        val limiter: Boolean = true,
    )

    @Volatile private var settings = Settings()
    private var configuredGains = List(10) { Float.NaN }
    private var configuredBass = false
    private var configuredRate = 0
    private var configuredRequestedPreamp = Float.NaN
    private var configuredLimiter = false
    private val bands = Array(10) { Biquad() }
    private val lowShelf = Biquad()
    private var shelfCoefficients = Coefficients.IDENTITY
    private var effectivePreamp = 0f
    private var wetMix = 1f
    private var wetTarget = 1f
    private var wetFramesRemaining = 0
    private var limiterGain = 1f
    private var currentChannels = 0
    private var wetScratch = FloatArray(0)

    data class Stats(val inputRms: Double, val outputRms: Double, val inputPeak: Float, val outputPeak: Float, val frames: Int)
    @Volatile var lastStats = Stats(0.0, 0.0, 0f, 0f, 0)
        private set

    /** Atomically replaces the complete control state; the audio thread consumes it at its next PCM block. */
    fun update(gainsDb: List<Float>, preampDb: Float, enabled: Boolean, megaBass: Boolean, limiter: Boolean) {
        require(gainsDb.size == PresetCatalog.centerFrequenciesHz.size)
        settings = Settings(
            gainsDb = gainsDb.map { it.coerceIn(-12f, 12f) },
            preampDb = preampDb.coerceIn(-12f, 0f),
            enabled = enabled,
            megaBass = megaBass,
            limiter = limiter,
        )
    }

    /** Processes interleaved normalized float PCM in place. */
    fun process(samples: FloatArray, sampleCount: Int, channels: Int, sampleRate: Int) {
        require(channels > 0 && sampleCount in 0..samples.size && sampleCount % channels == 0 && sampleRate > 0)
        if (sampleCount == 0) return
        val state = settings
        configure(state, sampleRate)
        if (channels != currentChannels) {
            currentChannels = channels
            wetScratch = FloatArray(channels)
            wetMix = if (state.enabled) 1f else 0f
            wetTarget = wetMix
        }
        val frames = sampleCount / channels
        val target = if (state.enabled) 1f else 0f
        if (target != wetTarget) {
            wetTarget = target
            wetFramesRemaining = max(1, sampleRate / 40) // 25 ms click-reduced bypass transition.
        }
        var inputEnergy = 0.0
        var outputEnergy = 0.0
        var inputPeak = 0f
        var outputPeak = 0f
        val releasePerSample = (1.0 - exp(-1.0 / (sampleRate * 0.080))).toFloat()
        val ceiling = 10.0.pow(-1.0 / 20.0).toFloat()

        for (frame in 0 until frames) {
            if (wetFramesRemaining > 0) {
                wetMix += (wetTarget - wetMix) / wetFramesRemaining
                wetFramesRemaining--
            } else wetMix = wetTarget
            val offset = frame * channels
            var wetPeak = 0f
            for (channel in 0 until channels) {
                val input = samples[offset + channel].takeIf(Float::isFinite) ?: 0f
                inputEnergy += input.toDouble() * input
                inputPeak = max(inputPeak, abs(input))
                var processed = input * dbToLinear(effectivePreamp)
                for (band in bands) processed = band.process(processed, channel)
                if (state.megaBass) processed = lowShelf.process(processed, channel)
                wetScratch[channel] = processed
                wetPeak = max(wetPeak, abs(processed))
            }

            if (state.limiter && state.enabled && wetPeak > ceiling) {
                limiterGain = min(limiterGain, ceiling / wetPeak)
            } else {
                limiterGain += (1f - limiterGain) * releasePerSample
            }
            val appliedLimiter = if (state.limiter && state.enabled) limiterGain else 1f
            for (channel in 0 until channels) {
                val dry = samples[offset + channel].takeIf(Float::isFinite) ?: 0f
                val wet = wetScratch[channel] * appliedLimiter
                val output = (dry + (wet - dry) * wetMix).coerceIn(-1f, 1f)
                samples[offset + channel] = output
                outputEnergy += output.toDouble() * output
                outputPeak = max(outputPeak, abs(output))
            }
        }
        lastStats = Stats(
            sqrt(inputEnergy / sampleCount), sqrt(outputEnergy / sampleCount),
            inputPeak, outputPeak, frames,
        )
    }

    private fun configure(state: Settings, sampleRate: Int) {
        if (configuredRate == sampleRate && configuredBass == state.megaBass && configuredGains == state.gainsDb &&
            configuredRequestedPreamp == state.preampDb && configuredLimiter == state.limiter) return
        configuredRate = sampleRate
        configuredBass = state.megaBass
        configuredRequestedPreamp = state.preampDb
        configuredLimiter = state.limiter
        configuredGains = state.gainsDb.toList()
        val nyquist = sampleRate * 0.5
        bands.forEachIndexed { index, filter ->
            val frequency = PresetCatalog.centerFrequenciesHz[index].toDouble()
            filter.set(if (frequency < nyquist * 0.96) peaking(frequency, sampleRate.toDouble(), 1.15, state.gainsDb[index].toDouble()) else Coefficients.IDENTITY)
        }
        shelfCoefficients = if (state.megaBass) lowShelf(88.0, sampleRate.toDouble(), 0.707, 6.0) else Coefficients.IDENTITY
        lowShelf.set(shelfCoefficients)
        effectivePreamp = safePreamp(state.gainsDb, state.megaBass, state.limiter, sampleRate).coerceAtMost(state.preampDb)
    }

    private fun safePreamp(gains: List<Float>, bass: Boolean, limiter: Boolean, sampleRate: Int): Float {
        val nyquist = sampleRate * 0.5
        val filters = gains.mapIndexedNotNull { index, gain ->
            val frequency = PresetCatalog.centerFrequenciesHz[index].toDouble()
            if (frequency < nyquist * 0.96) peaking(frequency, sampleRate.toDouble(), 1.15, gain.toDouble()) else null
        } + if (bass) listOf(lowShelf(88.0, sampleRate.toDouble(), 0.707, 6.0)) else emptyList()
        var maxBoost = 0.0
        // Dense logarithmic scan estimates the combined response of the cascaded filters.
        for (step in 0..512) {
            val frequency = 20.0 * (nyquist.coerceAtMost(20_000.0) / 20.0).pow(step / 512.0)
            val w = 2.0 * PI * frequency / sampleRate
            val totalDb = filters.sumOf { it.responseDb(w) }
            maxBoost = max(maxBoost, totalDb)
        }
        if (maxBoost <= 0.05) return 0f
        // Let the linked limiter handle up to 3 dB of transient headroom; without it, attenuate fully with 1 dB reserve.
        val reserve = if (limiter) 3.0 else 1.0
        return min(0.0, reserve - maxBoost).toFloat()
    }

    private class Biquad {
        private var c = Coefficients.IDENTITY
        private var z1 = DoubleArray(8)
        private var z2 = DoubleArray(8)

        fun set(coefficients: Coefficients) { c = coefficients }

        fun process(input: Float, channel: Int): Float {
            if (channel >= z1.size) {
                z1 = z1.copyOf(channel + 1)
                z2 = z2.copyOf(channel + 1)
            }
            val output = c.b0 * input + z1[channel]
            z1[channel] = c.b1 * input - c.a1 * output + z2[channel]
            z2[channel] = c.b2 * input - c.a2 * output
            return output.toFloat().takeIf(Float::isFinite) ?: 0f
        }
    }

    private data class Coefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun responseDb(w: Double): Double {
            val nr = b0 + b1 * cos(w) + b2 * cos(2.0 * w)
            val ni = -b1 * sin(w) - b2 * sin(2.0 * w)
            val dr = 1.0 + a1 * cos(w) + a2 * cos(2.0 * w)
            val di = -a1 * sin(w) - a2 * sin(2.0 * w)
            val magnitude = sqrt((nr * nr + ni * ni) / max(1e-20, dr * dr + di * di))
            return 20.0 * log10(max(1e-10, magnitude))
        }

        companion object { val IDENTITY = Coefficients(1.0, 0.0, 0.0, 0.0, 0.0) }
    }

    companion object {
        private fun dbToLinear(db: Float) = 10.0.pow(db / 20.0).toFloat()

        private fun peaking(frequency: Double, sampleRate: Double, q: Double, gainDb: Double): Coefficients {
            val a = 10.0.pow(gainDb / 40.0)
            val w = 2.0 * PI * frequency / sampleRate
            val alpha = sin(w) / (2.0 * q)
            val cosW = cos(w)
            val a0 = 1.0 + alpha / a
            return Coefficients(
                (1.0 + alpha * a) / a0,
                (-2.0 * cosW) / a0,
                (1.0 - alpha * a) / a0,
                (-2.0 * cosW) / a0,
                (1.0 - alpha / a) / a0,
            )
        }

        private fun lowShelf(frequency: Double, sampleRate: Double, slope: Double, gainDb: Double): Coefficients {
            val a = 10.0.pow(gainDb / 40.0)
            val w = 2.0 * PI * frequency / sampleRate
            val cosW = cos(w)
            val sinW = sin(w)
            val alpha = sinW / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
            val twoRootAAlpha = 2.0 * sqrt(a) * alpha
            val a0 = (a + 1.0) + (a - 1.0) * cosW + twoRootAAlpha
            return Coefficients(
                a * ((a + 1.0) - (a - 1.0) * cosW + twoRootAAlpha) / a0,
                2.0 * a * ((a - 1.0) - (a + 1.0) * cosW) / a0,
                a * ((a + 1.0) - (a - 1.0) * cosW - twoRootAAlpha) / a0,
                -2.0 * ((a - 1.0) + (a + 1.0) * cosW) / a0,
                ((a + 1.0) + (a - 1.0) * cosW - twoRootAAlpha) / a0,
            )
        }
    }
}
