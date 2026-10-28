package com.tinnitusrelief.app.dsp

import com.tinnitusrelief.app.audio.GainParam
import com.tinnitusrelief.app.audio.MixerCore
import com.tinnitusrelief.app.audio.NoiseColor
import com.tinnitusrelief.app.audio.NoiseVoice
import com.tinnitusrelief.app.audio.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Tests for the FFT, filters, band math, spectral processing, generators, 3AFC and policies. */
class DspTest {

    private val sr = 48000

    private class Sine(val freq: Double, val amp: Float, val sr: Int) : Voice {
        var ph = 0.0
        override fun render(out: FloatArray, frames: Int) {
            for (i in 0 until frames) {
                val s = (amp * sin(ph)).toFloat()
                out[2 * i] += s; out[2 * i + 1] += s
                ph += 2 * PI * freq / sr
            }
        }
    }

    private fun db(x: Double) = 20 * log10(x)

    // ---------------- FFT ----------------

    @Test fun fftMatchesDirectDftAndRoundTrips() {
        val n = 64
        val fft = Fft(n)
        val rnd = java.util.Random(3)
        val x = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        val re = x.copyOf(); val im = FloatArray(n)
        fft.forward(re, im)
        for (k in 0 until n) {
            var sr0 = 0.0; var si0 = 0.0
            for (t in 0 until n) { sr0 += x[t] * kotlin.math.cos(-2 * PI * k * t / n); si0 += x[t] * sin(-2 * PI * k * t / n) }
            assertEquals(sr0, re[k].toDouble(), 1e-4)
            assertEquals(si0, im[k].toDouble(), 1e-4)
        }
        fft.inverse(re, im)
        for (t in 0 until n) assertEquals(x[t], re[t], 1e-5f)
    }

    // ---------------- band math (published formulas) ----------------

    @Test fun dfcrsBandsMatchPublishedEquations() {
        val ft = 4000.0
        val (lowBand, highBand) = PublishedBands.dfcrs(ft)
        // Fl = Ft/√2, Fu = √2·Ft, bands 1/3 octave around them (Sound & Vibration 2025, eq. 1–6)
        assertEquals(ft / sqrt(2.0), lowBand.centerHz, 1e-6)
        assertEquals(ft * sqrt(2.0), highBand.centerHz, 1e-6)
        assertEquals(1.0 / 3.0, lowBand.widthOctaves, 1e-9)
        assertEquals(1.0 / 3.0, highBand.widthOctaves, 1e-9)
        // the paper's rounded figures: [0.63·Ft, 0.79·Ft] and [1.26·Ft, 1.59·Ft]
        assertEquals(0.63, lowBand.lowHz / ft, 0.005)
        assertEquals(0.79, lowBand.highHz / ft, 0.005)
        assertEquals(1.26, highBand.lowHz / ft, 0.005)
        assertEquals(1.59, highBand.highHz / ft, 0.005)
        assertEquals(10.0, lowBand.gainDb, 0.0)
    }

    @Test fun comparatorBandsMatchPublications() {
        val nbn = PublishedBands.mostNarrowband(4000.0)
        assertEquals(4000.0, nbn.centerHz, 1e-6)
        assertEquals(1.0 / 3.0, nbn.widthOctaves, 1e-9)
        val notch = PublishedBands.okamotoNotch(4000.0)
        assertEquals(1.0, notch.widthOctaves, 1e-9)
        val h = PublishedBands.hfem().single()
        assertEquals(6000.0, h.lowHz, 0.0); assertEquals(9000.0, h.highHz, 0.0); assertEquals(10.0, h.gainDb, 0.0)
    }

    @Test fun gainCurveIsFlatInsideAndZeroOutside() {
        val bands = PublishedBands.dfcrs(4000.0)
        val t = GainCurve.DEFAULT_TRANSITION_OCTAVES
        for (b in bands) {
            assertEquals(10.0, GainCurve.dbAt(b.lowHz, bands), 1e-9)
            assertEquals(10.0, GainCurve.dbAt(b.centerHz, bands), 1e-9)
            assertEquals(10.0, GainCurve.dbAt(b.highHz, bands), 1e-9)
            assertEquals(5.0, GainCurve.dbAt(b.highHz * 2.0.pow(t / 2), bands), 1e-9)
            assertEquals(0.0, GainCurve.dbAt(b.highHz * 2.0.pow(t), bands), 1e-9)
        }
        assertEquals(0.0, GainCurve.dbAt(4000.0, bands), 1e-9) // the tinnitus pitch itself is not boosted
        assertEquals(0.0, GainCurve.dbAt(1000.0, bands), 1e-9)
    }

    // ---------------- spectral shaper ----------------

    /** Steady-state RMS gain of the shaper for a sine at [freq]. */
    private fun shaperGainDb(bands: List<Band>, freq: Double): Double {
        val sh = SpectralShaper(sr)
        sh.setBands(bands)
        val src = Sine(freq, 0.25f, sr)
        val frames = 1024
        val buf = FloatArray(frames * 2)
        var sumIn = 0.0; var sumOut = 0.0
        for (blk in 0 until 120) {
            java.util.Arrays.fill(buf, 0f)
            src.render(buf, frames)
            val inCopy = buf.copyOf()
            sh.process(buf, frames)
            if (blk >= 40) for (i in buf.indices step 2) { sumIn += inCopy[i] * inCopy[i]; sumOut += buf[i] * buf[i] }
        }
        return 10 * log10(sumOut / sumIn)
    }

    @Test fun shaperIsTransparentWithUnityGain() {
        val sh = SpectralShaper(sr)
        val rnd = java.util.Random(5)
        val total = 48000
        val input = FloatArray(total * 2) { rnd.nextFloat() - 0.5f }
        val buf = input.copyOf()
        var pos = 0
        while (pos < total) {
            val n = minOf(777, total - pos) // odd block sizes on purpose
            val chunk = buf.copyOfRange(pos * 2, (pos + n) * 2)
            sh.process(chunk, n)
            System.arraycopy(chunk, 0, buf, pos * 2, n * 2)
            pos += n
        }
        val lat = sh.latencyFrames
        var maxErr = 0f
        for (i in sh.fftSize until total) {
            maxErr = maxOf(maxErr, abs(buf[2 * i] - input[2 * (i - lat)]), abs(buf[2 * i + 1] - input[2 * (i - lat) + 1]))
        }
        assertTrue("reconstruction error $maxErr", maxErr < 1e-4f)
    }

    @Test fun dfcrsProcessingAppliesExactlyTenDbInBand() {
        val ft = 4000.0
        val bands = PublishedBands.dfcrs(ft)
        assertEquals(10.0, shaperGainDb(bands, ft * sqrt(2.0)), 0.15)   // centre of upper band
        assertEquals(10.0, shaperGainDb(bands, ft / sqrt(2.0)), 0.15)   // centre of lower band
        assertEquals(0.0, shaperGainDb(bands, ft), 0.15)                // pitch itself unchanged
        assertEquals(0.0, shaperGainDb(bands, 1000.0), 0.15)
        assertEquals(0.0, shaperGainDb(bands, 9000.0), 0.15)
    }

    @Test fun bandwidthIsAccurateAtTheEdges() {
        val bands = PublishedBands.dfcrs(4000.0)
        val upper = bands[1]
        // just inside the edges: full gain; one transition width + leakage margin outside: no gain
        assertEquals(10.0, shaperGainDb(bands, upper.lowHz * 1.03), 0.4)
        assertEquals(10.0, shaperGainDb(bands, upper.highHz / 1.03), 0.4)
        assertEquals(0.0, shaperGainDb(bands, upper.highHz * 2.0.pow(1.0 / 12) * 1.04), 0.3)
        assertEquals(0.0, shaperGainDb(bands, upper.lowHz / 2.0.pow(1.0 / 12) / 1.04), 0.3)
    }

    @Test fun notchRemovesTheTinnitusFrequency() {
        val g = shaperGainDb(listOf(PublishedBands.okamotoNotch(4000.0)), 4000.0)
        assertTrue("notch depth $g dB", g < -40)
        assertEquals(0.0, shaperGainDb(listOf(PublishedBands.okamotoNotch(4000.0)), 1000.0), 0.15)
    }

    @Test fun changingBandsWhilePlayingDoesNotClick() {
        val sh = SpectralShaper(sr)
        val src = Sine(5000.0, 0.2f, sr)
        val frames = 512
        val buf = FloatArray(frames * 2)
        var prev = 0f; var maxStep = 0f
        for (blk in 0 until 400) {
            if (blk == 150) sh.setBands(PublishedBands.dfcrs(4000.0))
            if (blk == 250) sh.setBands(emptyList())
            java.util.Arrays.fill(buf, 0f)
            src.render(buf, frames); sh.process(buf, frames)
            for (i in buf.indices step 2) { if (blk > 20) maxStep = maxOf(maxStep, abs(buf[i] - prev)); prev = buf[i] }
        }
        // a 5 kHz sine at amplitude 0.63 (after +10 dB) moves at most 2π·5000/48000·0.63 ≈ 0.41 per sample
        assertTrue("max step $maxStep", maxStep < 0.45f)
    }

    // ---------------- biquads ----------------

    @Test fun biquadResponsesAreCorrect() {
        val bp = Biquad().set(Biquad.Type.BANDPASS, sr, 4000.0, Biquad.qForBandwidth(4000.0, sr, 1.0 / 3))
        assertEquals(0.0, db(bp.magnitude(4000.0, sr)), 0.01)
        assertTrue(db(bp.magnitude(1000.0, sr)) < -18)
        val pk = Biquad().set(Biquad.Type.PEAK, sr, 3000.0, 2.0, gainDb = 10.0)
        assertEquals(10.0, db(pk.magnitude(3000.0, sr)), 0.01)
        assertEquals(0.0, db(pk.magnitude(100.0, sr)), 0.1)
        val lp = Biquad().set(Biquad.Type.LOWPASS, sr, 2000.0, 1 / sqrt(2.0))
        assertEquals(-3.01, db(lp.magnitude(2000.0, sr)), 0.05)
        val notch = Biquad().set(Biquad.Type.NOTCH, sr, 4000.0, 2.0)
        assertTrue(db(notch.magnitude(4000.0, sr)) < -60)
    }

    // ---------------- generators ----------------

    @Test fun narrowbandNoiseIsStableCalibratedAndNarrow() {
        for (fc in listOf(250.0, 1000.0, 4000.0, 12000.0)) {
            val v = BandNoiseVoice(sr, fc, 1.0 / 3)
            val n = sr * 10
            val out = FloatArray(n * 2)
            v.render(out, n)
            var sum = 0.0
            for (x in out) { assertFalse(x.isNaN()); sum += x * x }
            val level = db(sqrt(sum / out.size))
            assertTrue("NBN $fc Hz level $level", level in -23.0..-17.0)
            // energy one octave away is at least 20 dB down
            val g1 = shaperLikeEnergyRatio(out, fc, fc * 2)
            assertTrue("NBN $fc selectivity $g1", g1 > 20)
        }
    }

    /** Ratio (dB) of energy near f1 vs near f2, using a long FFT. */
    private fun shaperLikeEnergyRatio(st: FloatArray, f1: Double, f2: Double): Double {
        val n = 65536
        val re = FloatArray(n) { st[2 * it] }
        val im = FloatArray(n)
        Fft(n).forward(re, im)
        fun e(f: Double): Double {
            val k = (f * n / sr).toInt()
            var s = 0.0
            for (i in (k - 50).coerceAtLeast(1)..(k + 50).coerceAtMost(n / 2 - 1)) s += re[i] * re[i] + im[i] * im[i]
            return s
        }
        return 10 * log10(e(f1) / e(f2).coerceAtLeast(1e-20))
    }

    @Test fun toneFrequencyAndModulationAreCorrect() {
        val t = ToneVoice(sr, 1000.0, 0.3f)
        val n = sr * 2
        val out = FloatArray(n * 2)
        t.render(out, n)
        var crossings = 0
        for (i in sr until n) if (out[2 * (i - 1)] < 0 && out[2 * i] >= 0) crossings++
        assertTrue("crossings $crossings", abs(crossings - 1000) <= 2)
        // AM never exceeds the set amplitude; FM stays finite
        t.amRate = 10.0; t.amDepth = 1f
        val am = FloatArray(n * 2); t.render(am, n)
        assertTrue(am.all { abs(it) <= 0.3001f })
        t.amDepth = 0f; t.fmRate = 5.0; t.fmCents = 100.0
        val fm = FloatArray(n * 2); t.render(fm, n)
        assertTrue(fm.all { !it.isNaN() && abs(it) <= 0.3001f })
    }

    @Test fun toneSequenceHasPublishedTimingAndNoClicks() {
        val seq = ToneSequenceVoice(sr, doubleArrayOf(2000.0, 4000.0, 8000.0), floatArrayOf(0.1f, 0.1f, 0.1f))
        assertEquals(3 * 1500, seq.totalMs)
        val n = sr * 5
        val out = FloatArray(n * 2)
        seq.render(out, n / 2)
        assertTrue(seq.currentIndex == -1 || seq.currentIndex in 0..2)
        seq.render(FloatArray(n), n / 2)
        assertTrue(seq.finished)
        assertTrue("first sample ramps from silence", abs(out[0]) < 1e-3f)
        // during the first 500 ms there is sound, during the 500..1500 ms gap there is none
        assertTrue((0 until sr / 2).any { abs(out[2 * it]) > 0.05f })
        assertTrue((sr / 2 + 10 until sr + sr / 2).all { out[2 * it] == 0f })
    }

    // ---------------- 3AFC ----------------

    private fun simulatePitch(truth: Double): ThreeAfcPitch {
        val t = ThreeAfcPitch()
        var guard = 0
        while (t.phase != ThreeAfcPitch.Phase.DONE && guard++ < 50) {
            val i = (0..2).minBy { abs(log2(t.triple[it] / truth)) }
            t.choose(i)
        }
        return t
    }

    @Test fun threeAfcFollowsThePublishedNarrowingRule() {
        val t = ThreeAfcPitch()
        assertEquals(listOf(2000.0, 4000.0, 8000.0), t.triple.toList())
        t.choose(1) // Fb chosen → [Fa + 1/3 oct, Fc − 1/3 oct] = [2520, 6350]
        assertEquals(2520.0, t.low, 1.0)
        assertEquals(6350.0, t.high, 1.0)
        assertEquals(ThreeAfcPitch.Phase.FINE, t.phase) // range ≤ 2 octaves → 1/3-octave tones
        assertEquals(1.0 / 3, log2(t.triple[1] / t.triple[0]), 1e-9)
    }

    @Test fun threeAfcConvergesForAnIdealListener() {
        for (truth in listOf(300.0, 1000.0, 3000.0, 5500.0, 7000.0, 11000.0)) {
            val t = simulatePitch(truth)
            val r = t.result!!
            assertTrue("truth $truth result $r", abs(log2(r / truth)) <= 1.0 / 6 + 1e-9)
            assertTrue("trials ${t.trials}", t.trials <= 12)
        }
    }

    @Test fun loudnessMatchConvergesToOneDb() {
        val truth = -42.0
        val l = ThreeAfcLoudness(thresholdDbFs = -60.0)
        var guard = 0
        while (!l.done && guard++ < 20) l.choose((0..2).minBy { abs(l.levels[it] - truth) })
        assertTrue(l.done)
        assertEquals(truth, l.middle, 1.0)
        assertEquals(18.0, l.sensationLevelDb, 1.0)
        assertTrue(l.levels.all { it <= l.maxDbFs })
    }

    // ---------------- policies ----------------

    @Test fun sessionTimerEndsAtTheLimitAndCapsAtSixtyMinutes() {
        val t = SessionTimer(15, startedAtMs = 1000)
        assertFalse(t.expired(1000 + 14 * 60_000L))
        assertTrue(t.expired(1000 + 15 * 60_000L))
        assertEquals(60 * 60_000L, SessionTimer(500, 0).limitMs)
    }

    @Test fun audioFocusInterruptionsFollowAndroidGuidance() {
        val p = FocusPolicy()
        p.onUserPlay()
        // phone call: pause, keep focus, then resume automatically
        assertEquals(listOf(FocusAction.Pause(300, false)), p.onFocus(FocusEvent.LOSS_TRANSIENT))
        assertTrue(p.onFocus(FocusEvent.GAIN).contains(FocusAction.Resume))
        assertTrue(p.playing)
        // navigation prompt: duck and restore, never pause
        assertEquals(listOf(FocusAction.Duck(true)), p.onFocus(FocusEvent.LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(listOf(FocusAction.Duck(false)), p.onFocus(FocusEvent.GAIN))
        // another music app: pause for good, no auto-resume
        assertEquals(listOf(FocusAction.Pause(300, true)), p.onFocus(FocusEvent.LOSS))
        assertFalse(p.onFocus(FocusEvent.GAIN).contains(FocusAction.Resume))
        // user paused during a call → must not resume when the call ends
        p.onUserPlay(); p.onFocus(FocusEvent.LOSS_TRANSIENT); p.onUserPause()
        assertFalse(p.onFocus(FocusEvent.GAIN).contains(FocusAction.Resume))
    }

    @Test fun responsesRoundTripAndBadLinesAreSkipped() {
        val recs = listOf(
            ResponseRecord.Pitch(1, 4123.5, "3afc", 12.0),
            ResponseRecord.Relief(2, "Shower + Rain\twith tab", 15, 7, 4, 5, 5),
            ResponseRecord.Ri(3, 4000.0, 60, 10, 6, 2, "quieter", 45),
            ResponseRecord.Daily(4, "2026-10-28", 5),
            ResponseRecord.Relief(5, "x", 10, 6, null, null, null),
        )
        val text = recs.joinToString("\n") { ResponseCodec.encode(it) } + "\ngarbage line\nRI\tat=x\n"
        assertEquals(recs, ResponseCodec.decodeAll(text))
    }

    // ---------------- mixer with all layers ----------------

    @Test fun maskerKeepsAtMostThreeChannels() {
        val m = MixerCore(sr)
        m.setLayerAGain(1f); m.play(10)
        val ids = listOf("a", "b", "c", "d")
        for (id in ids) m.setMaskerChannel(id, DcVoice(0.1f), GainParam(1f))
        val buf = FloatArray(2048)
        repeat(200) { m.render(buf, 1024) } // > 1.2 s crossfade
        assertEquals(0.3f, buf[0], 1e-3f)   // three channels of 0.1, the oldest one faded out
    }

    @Test fun perChannelVolumeAndLayerBCapAreAccurate() {
        val m = MixerCore(sr)
        m.setLayerAGain(1f); m.play(10)
        val vol = GainParam(0.5f)
        m.setMaskerChannel("a", DcVoice(0.2f), vol)
        m.setLayerB(DcVoice(0.2f), 1f) // asks for 0 dB, must be capped to −6 dB
        val buf = FloatArray(2048)
        repeat(100) { m.render(buf, 1024) }
        assertEquals(0.2f * 0.5f + 0.2f * MixerCore.LAYER_B_MAX, buf[0], 1e-3f)
        vol.target = 0f
        m.setMaskerEnabled(true)
        repeat(100) { m.render(buf, 1024) }
        assertEquals(0.2f * MixerCore.LAYER_B_MAX, buf[0], 1e-3f)
    }

    @Test fun maskerAndStimulusMixedForMinutesStayStableAndBelowCeiling() {
        val m = MixerCore(sr)
        m.setLayerAGain(1f)
        m.setMaskerChannel("pink", NoiseVoice(NoiseColor.PINK), GainParam(1f))
        m.setMaskerChannel("brown", NoiseVoice(NoiseColor.BROWN), GainParam(1f))
        val sh = SpectralShaper(sr).apply { setBands(PublishedBands.dfcrs(4000.0)) }
        m.setLayerB(ShapedVoice(NoiseVoice(NoiseColor.WHITE), sh), 0.5f)
        m.play(10)
        val buf = FloatArray(2048)
        repeat((sr * 180) / 1024) {
            m.render(buf, 1024)
            for (x in buf) assertFalse(x.isNaN())
        }
        assertTrue(m.peak <= MixerCore.CEILING + 1e-4f)
    }

    private class DcVoice(val v: Float) : Voice {
        override fun render(out: FloatArray, frames: Int) { for (i in 0 until frames * 2) out[i] += v }
    }
}
