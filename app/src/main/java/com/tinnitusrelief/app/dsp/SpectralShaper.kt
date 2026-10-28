package com.tinnitusrelief.app.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Builds a per-FFT-bin linear gain curve from a list of bands.
 *
 * Inside a band the full gain applies. Outside, the gain falls to 0 dB along a raised-cosine slope that
 * spans [transitionOctaves] in log frequency. Gains of overlapping bands add in dB.
 *
 * ENGINEERING ASSUMPTION: none of the DFCRS publications specify the filter type, order or edge slope,
 * so the slope width is a documented parameter (default 1/12 octave), not a published value.
 */
object GainCurve {
    const val DEFAULT_TRANSITION_OCTAVES = 1.0 / 12.0

    fun build(
        bands: List<Band>,
        fftSize: Int,
        sampleRate: Int,
        transitionOctaves: Double = DEFAULT_TRANSITION_OCTAVES,
    ): FloatArray {
        val bins = fftSize / 2 + 1
        val out = FloatArray(bins)
        for (k in 0 until bins) {
            val f = k.toDouble() * sampleRate / fftSize
            out[k] = 10.0.pow(dbAt(f, bands, transitionOctaves) / 20.0).toFloat()
        }
        return out
    }

    /** Gain in dB at [f] (also used by tests and by the UI to draw the response). */
    fun dbAt(f: Double, bands: List<Band>, transitionOctaves: Double = DEFAULT_TRANSITION_OCTAVES): Double {
        if (f <= 0.0) return 0.0
        var db = 0.0
        for (b in bands) db += b.gainDb * weight(f, b, transitionOctaves)
        return db
    }

    private fun weight(f: Double, b: Band, t: Double): Double {
        if (f >= b.lowHz && f <= b.highHz) return 1.0
        if (t <= 0.0) return 0.0
        val d = if (f < b.lowHz) log2(b.lowHz / f) else log2(f / b.highHz)
        if (d >= t) return 0.0
        return 0.5 + 0.5 * cos(PI * d / t)
    }
}

/**
 * Zero-phase spectral gain processor (STFT, weighted overlap-add).
 *
 * - FFT size 4096 with hop 1024 (75 % overlap) and a square-root periodic Hann window on both analysis and
 *   synthesis, which reconstructs the input exactly when all gains are 1.
 * - Because it scales magnitudes without changing phase, band gains are exact (no ripple from summing a
 *   phase-shifted filtered copy with the original), which is what the tests verify.
 * - Gain-curve changes are smoothed frame by frame, so moving a band never clicks.
 * - Latency: fftSize samples (85 ms at 48 kHz). Stereo, allocation-free on the audio thread.
 */
class SpectralShaper(val sampleRate: Int, val fftSize: Int = 4096, val hop: Int = fftSize / 4) {

    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) { sqrt(0.5 - 0.5 * cos(2 * PI * it / fftSize)).toFloat() }
    // sum over overlapping frames of window^2 = fftSize/hop/2 for periodic Hann
    private val olaScale = 1f / (fftSize.toFloat() / hop / 2f)
    private val bins = fftSize / 2 + 1

    @Volatile private var target = FloatArray(bins) { 1f }
    private val current = FloatArray(bins) { 1f }

    private val latencyStart = fftSize - hop
    private val inFifo = Array(2) { FloatArray(fftSize) }
    private val outFifo = Array(2) { FloatArray(hop) }
    private val accum = Array(2) { FloatArray(fftSize * 2) }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private var fill = latencyStart

    /** Total input→output delay in frames (measured by the transparency test). */
    val latencyFrames: Int get() = fftSize

    /** Replace the gain curve (from any thread). The audio thread glides to it over ~4 frames. */
    fun setGains(gains: FloatArray) {
        require(gains.size == bins)
        target = gains.copyOf()
    }

    fun setBands(bands: List<Band>, transitionOctaves: Double = GainCurve.DEFAULT_TRANSITION_OCTAVES) =
        setGains(GainCurve.build(bands, fftSize, sampleRate, transitionOctaves))

    /** Processes interleaved stereo [buf] in place. */
    fun process(buf: FloatArray, frames: Int) {
        var o = 0
        for (n in 0 until frames) {
            val readIdx = fill - latencyStart
            for (c in 0..1) {
                inFifo[c][fill] = buf[o + c]
                buf[o + c] = outFifo[c][readIdx]
            }
            fill++
            if (fill >= fftSize) {
                fill = latencyStart
                processFrame()
            }
            o += 2
        }
    }

    private fun processFrame() {
        val t = target
        for (k in 0 until bins) current[k] += (t[k] - current[k]) * 0.35f
        for (c in 0..1) {
            val inp = inFifo[c]
            for (i in 0 until fftSize) { re[i] = inp[i] * window[i]; im[i] = 0f }
            fft.forward(re, im)
            for (k in 0 until bins) {
                val g = current[k]
                re[k] *= g; im[k] *= g
                if (k in 1 until fftSize / 2) {
                    val m = fftSize - k
                    re[m] *= g; im[m] *= g
                }
            }
            fft.inverse(re, im)
            val acc = accum[c]
            for (i in 0 until fftSize) acc[i] += re[i] * window[i] * olaScale
            val of = outFifo[c]
            System.arraycopy(acc, 0, of, 0, hop)
            System.arraycopy(acc, hop, acc, 0, fftSize)
            java.util.Arrays.fill(acc, fftSize, fftSize + hop, 0f)
            System.arraycopy(inp, hop, inp, 0, fftSize - hop)
        }
    }

    fun reset() {
        for (c in 0..1) {
            java.util.Arrays.fill(inFifo[c], 0f)
            java.util.Arrays.fill(outFifo[c], 0f)
            java.util.Arrays.fill(accum[c], 0f)
        }
        fill = latencyStart
    }
}
