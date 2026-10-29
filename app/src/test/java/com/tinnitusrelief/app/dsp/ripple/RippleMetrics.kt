package com.tinnitusrelief.app.dsp.ripple

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measurement tools for the validation tests (spec section 6). They look only at the rendered audio plus the
 * recorded segment parameters where a test explicitly allows it. Nothing here is used by the app.
 */
object RippleMetrics {

    /** Quadratic B-spline (three cascaded unit boxcars), u in units of the boxcar width. */
    private fun bspline2(u: Double): Double {
        val a = abs(u)
        return when {
            a <= 0.5 -> 0.75 - a * a
            a <= 1.5 -> 0.5 * (1.5 - a) * (1.5 - a)
            else -> 0.0
        }
    }

    /**
     * Envelope of the component at [freq] at the given [times] (s): complex demodulation, then three cascaded
     * moving averages one period 1/f0 long (a B-spline kernel). Every other harmonic of f0 falls on a null.
     * Returns 2·|baseband| (the instantaneous amplitude).
     */
    fun envelope(x: FloatArray, sr: Int, freq: Double, f0: Double, times: DoubleArray): DoubleArray {
        val period = sr / f0
        val half = 1.5 * period
        val w = 2 * PI * freq / sr
        return DoubleArray(times.size) { k ->
            val centre = times[k] * sr
            val j0 = max(0, ceil(centre - half).toInt())
            val j1 = min(x.size - 1, floor(centre + half).toInt())
            var re = 0.0; var im = 0.0; var norm = 0.0
            var c = cos(w * j0); var s = sin(w * j0)
            val cw = cos(w); val sw = sin(w)
            for (j in j0..j1) {
                val kv = bspline2((j - centre) / period)
                re += x[j] * c * kv; im -= x[j] * s * kv; norm += kv
                val c2 = c * cw - s * sw; s = s * cw + c * sw; c = c2
            }
            2 * sqrt(re * re + im * im) / norm
        }
    }

    fun times(from: Double, to: Double, step: Double): DoubleArray {
        val n = floor((to - from) / step + 1e-9).toInt() + 1
        return DoubleArray(n) { from + it * step }
    }

    /** Envelope relative to the unmodulated amplitude of that harmonic (gain · hearing correction), ramps removed. */
    fun relativeEnvelope(seg: RippleSegment, config: RippleConfig, harmonic: Int, t: DoubleArray): DoubleArray {
        val f = harmonic * seg.params.f0
        val g = Math.pow(10.0, HearingSlope.gainDb(f, config.hearingMaxDb) / 20) * seg.gain
        val e = envelope(seg.samples, seg.sampleRate, f, seg.params.f0, t)
        return DoubleArray(t.size) { e[it] / (g * RippleGenerator.ramp(t[it])) }
    }

    // ---------------- ripple fit across log-frequency (V6b) ----------------

    data class RippleFit(val density: Double, val phase: Double, val explained: Double)

    /** Fits y(F) ≈ sin(2π·S·F + θ)·A over a density grid; returns the best S, θ and explained variance. */
    fun fitRipple(fs: DoubleArray, ys: DoubleArray, sMin: Double = 0.5, sMax: Double = 10.0, step: Double = 0.01): RippleFit {
        var best = RippleFit(0.0, 0.0, -1.0)
        val total = ys.sumOf { it * it }
        fun eval(sv: Double): RippleFit {
            var scc = 0.0; var sss = 0.0; var scs = 0.0; var syc = 0.0; var sys = 0.0
            for (i in fs.indices) {
                val c = cos(2 * PI * sv * fs[i]); val s = sin(2 * PI * sv * fs[i])
                scc += c * c; sss += s * s; scs += c * s; syc += ys[i] * c; sys += ys[i] * s
            }
            val det = scc * sss - scs * scs
            if (abs(det) < 1e-12) return RippleFit(sv, 0.0, -1.0)
            val a = (syc * sss - sys * scs) / det // coefficient of cos
            val b = (sys * scc - syc * scs) / det // coefficient of sin
            var res = 0.0
            for (i in fs.indices) {
                val r = ys[i] - a * cos(2 * PI * sv * fs[i]) - b * sin(2 * PI * sv * fs[i]); res += r * r
            }
            // sin(2πSF + θ) = sin(2πSF)cosθ + cos(2πSF)sinθ  →  θ = atan2(a, b)
            return RippleFit(sv, atan2(a, b), 1 - res / total)
        }
        var sv = sMin
        while (sv <= sMax + 1e-9) { val f = eval(sv); if (f.explained > best.explained) best = f; sv += step }
        // refine
        var lo = best.density - step; var hi = best.density + step
        repeat(30) {
            val m1 = lo + (hi - lo) / 3; val m2 = hi - (hi - lo) / 3
            if (eval(m1).explained > eval(m2).explained) hi = m2 else lo = m1
        }
        return eval((lo + hi) / 2)
    }

    // ---------------- spectra ----------------

    /** In-place radix-2 FFT on doubles. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Power spectrum (|X|², bins 0…n/2) of a zero-padded signal. */
    fun powerSpectrum(x: FloatArray, n: Int): DoubleArray {
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in 0 until min(n, x.size)) re[i] = x[i].toDouble()
        fft(re, im)
        return DoubleArray(n / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
    }

    fun bandPower(ps: DoubleArray, n: Int, sr: Int, lo: Double, hi: Double): Double {
        var s = 0.0
        val b0 = ceil(lo * n / sr).toInt(); val b1 = min(ps.size - 1, floor(hi * n / sr).toInt())
        for (b in b0..b1) s += ps[b]
        return s
    }

    fun rmsDb(x: FloatArray): Double { var s = 0.0; for (v in x) s += v.toDouble() * v; return 10 * kotlin.math.log10(s / x.size) }
    fun peakDb(x: FloatArray): Double { var p = 0.0; for (v in x) p = max(p, abs(v.toDouble())); return 20 * kotlin.math.log10(p) }

    // ---------------- statistics ----------------

    /** Kolmogorov–Smirnov test against U(lo, hi). Returns (D, asymptotic p-value). */
    fun ksUniform(values: DoubleArray, lo: Double, hi: Double): Pair<Double, Double> {
        val v = values.map { (it - lo) / (hi - lo) }.sorted()
        val n = v.size
        var d = 0.0
        for (i in v.indices) d = max(d, max((i + 1).toDouble() / n - v[i], v[i] - i.toDouble() / n))
        val lambda = (sqrt(n.toDouble()) + 0.12 + 0.11 / sqrt(n.toDouble())) * d
        var p = 0.0
        for (k in 1..100) p += 2 * (if (k % 2 == 1) 1 else -1) * exp(-2.0 * k * k * lambda * lambda)
        return d to p.coerceIn(0.0, 1.0)
    }

    /** Linear least squares y ≈ a + b·sinφ + c·cosφ. Returns (a, amplitude, r²). */
    fun fitSinusoid(phi: DoubleArray, y: DoubleArray): Triple<Double, Double, Double> {
        // normal equations for [1, sin, cos]
        val m = Array(3) { DoubleArray(3) }; val r = DoubleArray(3)
        for (i in phi.indices) {
            val b = doubleArrayOf(1.0, sin(phi[i]), cos(phi[i]))
            for (a in 0..2) { r[a] += b[a] * y[i]; for (c in 0..2) m[a][c] += b[a] * b[c] }
        }
        val x = solve3(m, r)
        val mean = y.average()
        var res = 0.0; var tot = 0.0
        for (i in phi.indices) {
            val f = x[0] + x[1] * sin(phi[i]) + x[2] * cos(phi[i])
            res += (y[i] - f) * (y[i] - f); tot += (y[i] - mean) * (y[i] - mean)
        }
        return Triple(x[0], sqrt(x[1] * x[1] + x[2] * x[2]), 1 - res / tot)
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val m = Array(3) { i -> a[i].copyOf() + b[i] }
        for (c in 0..2) {
            val p = (c..2).maxBy { abs(m[it][c]) }; val t = m[c]; m[c] = m[p]; m[p] = t
            for (rr in 0..2) if (rr != c) { val f = m[rr][c] / m[c][c]; for (k in c..3) m[rr][k] -= f * m[c][k] }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }

    fun unwrap(ph: DoubleArray): DoubleArray {
        val out = ph.copyOf()
        for (i in 1 until out.size) {
            var d = out[i] - out[i - 1]
            while (d > PI) { out[i] -= 2 * PI; d -= 2 * PI }
            while (d < -PI) { out[i] += 2 * PI; d += 2 * PI }
        }
        return out
    }

    fun slope(x: DoubleArray, y: DoubleArray): Double {
        val mx = x.average(); val my = y.average()
        var num = 0.0; var den = 0.0
        for (i in x.indices) { num += (x[i] - mx) * (y[i] - my); den += (x[i] - mx) * (x[i] - mx) }
        return num / den
    }

    fun log2f(x: Double) = log2(x)
    fun lnf(x: Double) = ln(x)
}
