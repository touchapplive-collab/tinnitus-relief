package com.tinnitusrelief.ripple

import java.util.SplittableRandom
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class RippleModulation {
    /** Plain harmonic complex (An ≡ 1); used by validation as the unmodulated reference. */
    NONE,
    /** [P] eq. 2 amplitude modulation inside the chosen band. */
    AMPLITUDE,
}

data class RippleConfig(
    val band: RippleBand,
    val modulation: RippleModulation = RippleModulation.AMPLITUDE,
    /** Maximum hearing correction in dB (0, 15, 30 or 45; [P]). */
    val hearingMaxDb: Double = 0.0,
    val sampleRate: Int = 48_000,
    val targetRmsDbfs: Double = RippleSpec.TARGET_RMS_DBFS,
    /**
     * [P] eq. 1: random starting phase per harmonic. `false` (all phases 0) exists ONLY so that validation can
     * report the crest factor of the alternative discussed in spec uncertainty 1; it is not a supported mode.
     */
    val randomStartPhase: Boolean = true,
) {
    init {
        require(hearingMaxDb in 0.0..45.0) { "hearing correction out of range" }
        require(sampleRate >= 2 * RippleSpec.HARMONIC_HIGH_HZ + 2_000) { "sample rate too low for 16 kHz harmonics" }
    }
}

/** The random draws of one segment ([P]: f0, per-harmonic phases φn, offsets q and p). Recorded for validation. */
data class SegmentParams(
    val f0: Double,
    val q: Double,
    val p: Double,
    val firstHarmonic: Int,
    val startPhases: DoubleArray,
) {
    val lastHarmonic: Int get() = firstHarmonic + startPhases.size - 1
}

class RippleSegment(val params: SegmentParams, val samples: FloatArray, val sampleRate: Int, /** linear gain applied by normalisation */ val gain: Double)

/**
 * Renders [P] eq. 1–5 one 4 s segment at a time (mono, float). Pure computation; no audio output.
 *
 *   s(t)  = Σn An(t)·g(fn)·sin(2π n f0 t + φn)           (g = hearing correction, ramps applied afterwards)
 *   An(t) = 1 + d·sin(2π[ω t + Fn·S(t)] + q)  inside the band, 1 outside
 *   Fn    = log2(n f0 / c),  S(t) = μ + r·sin(p + 2π ν t)
 */
object RippleGenerator {

    /** Envelope control-rate step (samples); the slow envelope is linearly interpolated in between. */
    private const val BLOCK = 32

    fun harmonicRange(f0: Double): IntRange =
        ceil(RippleSpec.HARMONIC_LOW_HZ / f0 - 1e-9).toInt()..floor(RippleSpec.HARMONIC_HIGH_HZ / f0 + 1e-9).toInt()

    fun drawParams(rng: SplittableRandom, randomStartPhase: Boolean = true): SegmentParams {
        val f0 = RippleSpec.F0_MIN_HZ + rng.nextDouble() * (RippleSpec.F0_MAX_HZ - RippleSpec.F0_MIN_HZ)
        val q = rng.nextDouble() * 2 * PI
        val p = rng.nextDouble() * 2 * PI
        val range = harmonicRange(f0)
        val phases = DoubleArray(range.last - range.first + 1) { if (randomStartPhase) rng.nextDouble() * 2 * PI else 0.0 }
        return SegmentParams(f0, q, p, range.first, phases)
    }

    /** Spectral modulation rate S(t), [P] eq. 5. */
    fun smr(t: Double, p: Double): Double =
        RippleSpec.SMR_MEAN + RippleSpec.SMR_EXTENT * sin(p + 2 * PI * RippleSpec.SMR_RATE_HZ * t)

    /** Raised-cosine onset/offset ramp value at time t within the segment. */
    fun ramp(t: Double): Double {
        val r = RippleSpec.RAMP_SECONDS
        val d = RippleSpec.SEGMENT_SECONDS
        return when {
            t < r -> 0.5 * (1 - cos(PI * t / r))
            t > d - r -> 0.5 * (1 - cos(PI * (d - t) / r))
            else -> 1.0
        }
    }

    fun render(config: RippleConfig, params: SegmentParams): RippleSegment {
        val sr = config.sampleRate
        val n = (RippleSpec.SEGMENT_SECONDS * sr).roundToInt()
        val acc = DoubleArray(n)
        val blocks = (n + BLOCK - 1) / BLOCK
        // shared modulation terms at control points: ωt and S(t)
        val omegaT = DoubleArray(blocks + 1) { RippleSpec.TEMPORAL_RATE_HZ * (it * BLOCK).toDouble() / sr }
        val sOfT = DoubleArray(blocks + 1) { smr((it * BLOCK).toDouble() / sr, params.p) }
        val env = DoubleArray(blocks + 1)
        val band = config.band
        val modulate = config.modulation == RippleModulation.AMPLITUDE

        for (idx in params.startPhases.indices) {
            val h = params.firstHarmonic + idx
            val f = h * params.f0
            val amp = 10.0.pow(HearingSlope.gainDb(f, config.hearingMaxDb) / 20)
            val inBand = modulate && band.contains(f)
            if (inBand) {
                val fn = log2(f / band.centreHz)
                for (b in 0..blocks) env[b] = 1 + RippleSpec.DEPTH * sin(2 * PI * (omegaT[b] + fn * sOfT[b]) + params.q)
            }
            val w = 2 * PI * f / sr
            val cw = cos(w); val sw = sin(w)
            val phi = params.startPhases[idx]
            var i = 0
            for (b in 0 until blocks) {
                // exact phase at each block start (no drift), recursive rotation inside the block
                val theta = w * i + phi
                var c = cos(theta); var s = sin(theta)
                val e0 = if (inBand) env[b] else 1.0
                val de = if (inBand) (env[b + 1] - env[b]) / BLOCK else 0.0
                var e = e0
                val end = minOf(i + BLOCK, n)
                while (i < end) {
                    acc[i] += amp * e * s
                    val c2 = c * cw - s * sw
                    s = s * cw + c * sw
                    c = c2
                    e += de
                    i++
                }
            }
        }
        var sum = 0.0
        for (i in 0 until n) {
            acc[i] *= ramp(i.toDouble() / sr)
            sum += acc[i] * acc[i]
        }
        val rms = sqrt(sum / n)
        val gain = if (rms > 0) 10.0.pow(config.targetRmsDbfs / 20) / rms else 0.0
        val out = FloatArray(n) { (acc[it] * gain).toFloat() }
        return RippleSegment(params, out, sr, gain)
    }
}

/** A deterministic stream of segments (the hour-long track of [P] is 900 of them). */
class RippleSequence(private val config: RippleConfig, seed: Long) {
    private val rng = SplittableRandom(seed)
    fun next(): RippleSegment = RippleGenerator.render(config, RippleGenerator.drawParams(rng, config.randomStartPhase))
}
