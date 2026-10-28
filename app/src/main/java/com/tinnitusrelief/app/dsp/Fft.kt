package com.tinnitusrelief.app.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT with precomputed twiddles and bit-reversal table.
 * Allocation-free after construction, so it is safe on the audio thread.
 */
class Fft(val size: Int) {
    init { require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" } }

    private val cosT = FloatArray(size / 2) { cos(-2.0 * PI * it / size).toFloat() }
    private val sinT = FloatArray(size / 2) { sin(-2.0 * PI * it / size).toFloat() }
    private val rev = IntArray(size).also { r ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Forward transform (sign -1). [re] and [im] are overwritten with the spectrum. */
    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, false)

    /** Inverse transform, including the 1/N scaling. */
    fun inverse(re: FloatArray, im: FloatArray) {
        transform(re, im, true)
        val s = 1f / size
        for (i in 0 until size) { re[i] *= s; im[i] *= s }
    }

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = size
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = if (inverse) -sinT[k] else sinT[k]
                    val l = j + half
                    val xr = re[l] * wr - im[l] * wi
                    val xi = re[l] * wi + im[l] * wr
                    re[l] = re[j] - xr
                    im[l] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += len
            }
            len = len shl 1
        }
    }
}
