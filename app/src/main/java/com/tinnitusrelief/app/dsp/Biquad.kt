package com.tinnitusrelief.app.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Second-order IIR section (RBJ "Audio EQ Cookbook" formulas), transposed direct form II.
 * Coefficients are computed in double precision; state is float for speed.
 */
class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var z1 = 0.0; private var z2 = 0.0

    enum class Type { LOWPASS, HIGHPASS, BANDPASS, NOTCH, PEAK }

    fun set(type: Type, sampleRate: Int, freq: Double, q: Double, gainDb: Double = 0.0): Biquad {
        val f = freq.coerceIn(1.0, sampleRate * 0.49)
        val w0 = 2 * PI * f / sampleRate
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val a = 10.0.pow(gainDb / 40)
        var nb0: Double; var nb1: Double; var nb2: Double
        val na0: Double; var na1: Double; var na2: Double
        when (type) {
            Type.LOWPASS -> { nb0 = (1 - cw) / 2; nb1 = 1 - cw; nb2 = (1 - cw) / 2; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            Type.HIGHPASS -> { nb0 = (1 + cw) / 2; nb1 = -(1 + cw); nb2 = (1 + cw) / 2; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            Type.BANDPASS -> { nb0 = alpha; nb1 = 0.0; nb2 = -alpha; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            Type.NOTCH -> { nb0 = 1.0; nb1 = -2 * cw; nb2 = 1.0; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            Type.PEAK -> { nb0 = 1 + alpha * a; nb1 = -2 * cw; nb2 = 1 - alpha * a; na0 = 1 + alpha / a; na1 = -2 * cw; na2 = 1 - alpha / a }
        }
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
        return this
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y.toFloat()
    }

    /** Analytic magnitude response |H(e^jw)| at [freq]. */
    fun magnitude(freq: Double, sampleRate: Int): Double {
        val w = 2 * PI * freq / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        return sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    companion object {
        /** Q of a band-pass section whose -3 dB bandwidth is [octaves] wide at [freq] (RBJ BW definition). */
        fun qForBandwidth(freq: Double, sampleRate: Int, octaves: Double): Double {
            val w0 = 2 * PI * freq / sampleRate
            return 1.0 / (2 * sinh(ln(2.0) / 2 * octaves * w0 / sin(w0)))
        }
    }
}
