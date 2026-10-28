package com.tinnitusrelief.app.dsp

import com.tinnitusrelief.app.audio.FastRandom
import com.tinnitusrelief.app.audio.Voice
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Continuous sine generator with optional amplitude or frequency modulation.
 * Every parameter glides (frequency in log space, amplitude linearly), so changes never click.
 * Parameters may be set from any thread.
 */
class ToneVoice(private val sampleRate: Int, freqHz: Double, amplitude: Float) : Voice {
    @Volatile var targetFreq = freqHz
    @Volatile var targetAmp = amplitude
    /** AM: modulation rate in Hz and depth 0..1 (0 = off). */
    @Volatile var amRate = 0.0
    @Volatile var amDepth = 0f
    /** FM: modulation rate in Hz and peak deviation in cents (0 = off). */
    @Volatile var fmRate = 0.0
    @Volatile var fmCents = 0.0

    private var freq = freqHz
    private var amp = 0f
    private var phase = 0.0
    private var modPhase = 0.0
    private val glide = 1.0 - exp(-1.0 / (0.03 * sampleRate))
    private val ampStep = 1f / (0.02f * sampleRate)

    override fun render(out: FloatArray, frames: Int) {
        val tf = targetFreq; val ta = targetAmp
        val amR = amRate; val amD = amDepth; val fmR = fmRate; val fmC = fmCents
        var o = 0
        for (n in 0 until frames) {
            freq *= (tf / freq).pow(glide)
            if (amp < ta) amp = minOf(ta, amp + ampStep) else if (amp > ta) amp = maxOf(ta, amp - ampStep)
            val modRate = if (fmC != 0.0) fmR else amR
            modPhase += 2 * PI * modRate / sampleRate
            if (modPhase > 2 * PI) modPhase -= 2 * PI
            var f = freq
            if (fmC != 0.0 && fmR > 0.0) f *= 2.0.pow(fmC / 1200.0 * sin(modPhase))
            phase += 2 * PI * f / sampleRate
            if (phase > 2 * PI) phase -= 2 * PI
            var g = amp
            // AM keeps the peak at `amp`: envelope runs between (1 - depth) and 1
            if (amD > 0f && amR > 0.0) g *= (1f - amD * 0.5f * (1f - cos(modPhase).toFloat()))
            val s = (sin(phase) * g).toFloat()
            out[o] += s; out[o + 1] += s
            o += 2
        }
    }
}

/**
 * Plays a short list of tones once (used by 3AFC pitch/loudness matching): each tone [toneMs] long with
 * [rampMs] raised-cosine on/off ramps, separated by [gapMs] of silence. Timings from the 3AFC paper:
 * 500 ms tones, 1 s gaps. The 10 ms ramps are an engineering choice to avoid clicks.
 */
class ToneSequenceVoice(
    private val sampleRate: Int,
    private val freqs: DoubleArray,
    private val amps: FloatArray,
    toneMs: Int = 500,
    gapMs: Int = 1000,
    rampMs: Int = 10,
) : Voice {
    private val toneLen = toneMs * sampleRate / 1000
    private val gapLen = gapMs * sampleRate / 1000
    private val rampLen = (rampMs * sampleRate / 1000).coerceAtLeast(1)
    private var pos = 0L
    private var phase = 0.0

    /** Index of the tone currently sounding, or -1. Readable from the UI thread. */
    @Volatile var currentIndex = -1
        private set
    @Volatile var finished = false
        private set

    val totalMs: Int get() = freqs.size * (toneLen + gapLen) * 1000 / sampleRate

    override fun render(out: FloatArray, frames: Int) {
        val period = toneLen + gapLen
        var o = 0
        for (n in 0 until frames) {
            val idx = (pos / period).toInt()
            if (idx >= freqs.size) { currentIndex = -1; finished = true; return }
            val t = (pos % period).toInt()
            if (t < toneLen) {
                currentIndex = idx
                val env = when {
                    t < rampLen -> 0.5f - 0.5f * cos(PI * t / rampLen).toFloat()
                    t > toneLen - rampLen -> 0.5f - 0.5f * cos(PI * (toneLen - t) / rampLen).toFloat()
                    else -> 1f
                }
                phase += 2 * PI * freqs[idx] / sampleRate
                if (phase > 2 * PI) phase -= 2 * PI
                val s = (sin(phase) * amps[idx] * env).toFloat()
                out[o] += s; out[o + 1] += s
            } else {
                currentIndex = -1
                phase = 0.0
            }
            pos++
            o += 2
        }
    }
}

/**
 * Narrow-band noise: white noise through two cascaded band-pass biquads centred on [centerHz], with a
 * level normalised so the output RMS is about [rms] regardless of centre frequency or bandwidth.
 */
class BandNoiseVoice(
    private val sampleRate: Int,
    centerHz: Double,
    private val widthOctaves: Double,
    private val rms: Float = 0.1f,
) : Voice {
    private val rl = FastRandom(0x2468ACE)
    private val rr = FastRandom(0x13579BD)
    private val fl = arrayOf(Biquad(), Biquad())
    private val fr = arrayOf(Biquad(), Biquad())
    private var norm = 1f
    @Volatile private var pendingCenter = centerHz
    private var center = -1.0

    fun setCenter(hz: Double) { pendingCenter = hz }

    private fun configure(hz: Double) {
        center = hz
        // two identical sections: each set to the requested width, so the cascade is a little narrower
        val q = Biquad.qForBandwidth(hz, sampleRate, widthOctaves)
        for (b in fl + fr) b.set(Biquad.Type.BANDPASS, sampleRate, hz, q)
        // noise-equivalent bandwidth of the cascade ≈ 0.78·(f_hi − f_lo); white noise (uniform) RMS = 1/√3
        val bwHz = hz * (2.0.pow(widthOctaves / 2) - 2.0.pow(-widthOctaves / 2)) * 0.78
        val passFraction = (2 * bwHz / sampleRate).coerceIn(1e-5, 1.0)
        norm = (rms / (sqrt(passFraction) / sqrt(3.0))).toFloat()
    }

    override fun render(out: FloatArray, frames: Int) {
        val p = pendingCenter
        if (p != center) configure(p)
        var o = 0
        for (n in 0 until frames) {
            out[o] += fl[1].process(fl[0].process(rl.next())) * norm
            out[o + 1] += fr[1].process(fr[0].process(rr.next())) * norm
            o += 2
        }
    }
}

/** Feeds a source [Voice] through a [SpectralShaper] (frequency-dependent processing of real audio). */
class ShapedVoice(private val source: Voice, val shaper: SpectralShaper) : Voice {
    private var buf = FloatArray(0)
    override fun render(out: FloatArray, frames: Int) {
        val n = frames * 2
        if (buf.size < n) buf = FloatArray(n)
        java.util.Arrays.fill(buf, 0, n, 0f)
        source.render(buf, frames)
        shaper.process(buf, frames)
        for (i in 0 until n) out[i] += buf[i]
    }
}
