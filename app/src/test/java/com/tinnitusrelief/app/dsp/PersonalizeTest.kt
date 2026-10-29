package com.tinnitusrelief.app.dsp

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

/** Sound personalization: analysis, filters, loudness matching, adaptive search, playback. */
class PersonalizeTest {

    private val sr = 48000

    private class Sine(val f: Double, val amp: Float, val sr: Int) : Voice {
        var ph = 0.0
        override fun render(out: FloatArray, frames: Int) {
            for (i in 0 until frames) {
                val s = (amp * sin(ph)).toFloat(); out[2 * i] += s; out[2 * i + 1] += s
                ph += 2 * PI * f / sr
            }
        }
    }

    private fun noise(c: NoiseColor, seconds: Double = 8.0) = renderMono(NoiseVoice(c), sr, seconds)

    // ---------------- FFT analysis with known signals ----------------

    @Test fun analysisFindsTheFrequencyOfKnownSines() {
        for (f in listOf(250.0, 1000.0, 5000.0, 12000.0)) {
            val s = SpectrumAnalyzer.analyze(renderMono(Sine(f, 0.3f, sr), sr, 2.0), sr)
            assertTrue("$f → ${s.dominantHz}", abs(log2(s.dominantHz / f)) <= 1.0 / 12 + 1e-9)
        }
    }

    @Test fun analysisDoesNotAssumeEnergyEverywhere() {
        // a pure 1 kHz tone only has energy in the "body" region: nothing else is offered for adjustment
        val tone = SpectrumAnalyzer.analyze(renderMono(Sine(1000.0, 0.3f, sr), sr, 2.0), sr)
        assertEquals(listOf(false, true, false, false), tone.adjustable().toList())
        // broadband noise: every region is adjustable
        assertTrue(SpectrumAnalyzer.analyze(noise(NoiseColor.PINK), sr).adjustable().all { it })
    }

    @Test fun analysisMeasuresNoiseColoursCorrectly() {
        // slope in dB per octave between 200 Hz and 8 kHz (1/6-octave bands)
        fun slope(s: Spectrum): Double {
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (i in s.centersHz.indices) if (s.centersHz[i] in 200.0..8000.0) {
                xs += log2(s.centersHz[i]); ys += 10 * log10(s.power[i])
            }
            val mx = xs.average(); val my = ys.average()
            return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.indices.sumOf { (xs[it] - mx).pow(2) }
        }
        assertEquals(3.0, slope(SpectrumAnalyzer.analyze(noise(NoiseColor.WHITE), sr)), 0.5)   // white: +3 dB/oct per band
        assertEquals(0.0, slope(SpectrumAnalyzer.analyze(noise(NoiseColor.PINK), sr)), 0.5)    // pink: flat per band
        assertTrue(slope(SpectrumAnalyzer.analyze(noise(NoiseColor.BROWN), sr)) < -2.0)        // brown: falling
    }

    // ---------------- filters ----------------

    @Test fun regionFiltersHaveTheIntendedBroadResponse() {
        val air = doubleArrayOf(0.0, 0.0, 0.0, 6.0)
        assertEquals(6.0, EqMath.responseDb(air, 16000.0), 0.4)
        assertEquals(0.0, EqMath.responseDb(air, 300.0), 0.2)
        val presence = doubleArrayOf(0.0, 0.0, 6.0, 0.0)
        assertEquals(6.0, EqMath.responseDb(presence, 2500.0), 0.01)
        assertEquals(3.0, EqMath.responseDb(presence, 1000.0), 1.5)  // broad: still about half way an octave+ away
        val depth = doubleArrayOf(-8.0, 0.0, 0.0, 0.0)
        assertEquals(-8.0, EqMath.responseDb(depth, 40.0), 0.5)
        assertEquals(0.0, EqMath.responseDb(depth, 5000.0), 0.2)
        // never a narrow spike: with every region at the maximum the curve stays below 12 dB
        val all = DoubleArray(4) { EqBands.MAX_DB }
        var f = 30.0
        while (f < 20000) { assertTrue(EqMath.responseDb(all, f) < 12.5); f *= 1.1 }
    }

    // ---------------- loudness matching ----------------

    @Test fun makeupGainMatchesAWeightedLoudness() {
        val spec = SpectrumAnalyzer.analyze(noise(NoiseColor.PINK), sr)
        fun aLevel(gains: DoubleArray, makeup: Double): Double {
            var p = 0.0
            for (i in spec.centersHz.indices) {
                val fr = spec.centersHz[i]
                p += spec.power[i] * 10.0.pow((EqMath.aWeightDb(fr) + 2 * (EqMath.responseDb(gains, fr) + makeup) / 2) / 10)
            }
            return 10 * log10(p)
        }
        val ref = aLevel(DoubleArray(4), 0.0)
        for (g in listOf(doubleArrayOf(0.0, 0.0, 0.0, 6.0), doubleArrayOf(-8.0, -4.0, 4.0, 6.0), doubleArrayOf(6.0, 3.0, -3.0, -8.0))) {
            val p = EqMath.profile(spec, g)
            assertEquals(ref, aLevel(g, p.makeupDb), 0.05)
        }
        assertTrue(EqMath.matchMakeupDb(spec, doubleArrayOf(0.0, 0.0, 0.0, 6.0)) < 0) // a boost is turned down
    }

    @Test fun equaliserAppliesTheComputedGainInRealTime() {
        val p = EqProfile(listOf(0.0, 0.0, 0.0, 6.0), -2.0)
        val chain = EqChain(sr, p)
        val src = Sine(14000.0, 0.1f, sr)
        val buf = FloatArray(2048)
        var inP = 0.0; var outP = 0.0
        repeat(200) { k ->
            java.util.Arrays.fill(buf, 0f); src.render(buf, 1024)
            val inp = buf.copyOf()
            chain.process(buf, 1024)
            if (k > 20) for (i in buf.indices step 2) { inP += inp[i] * inp[i]; outP += buf[i] * buf[i] }
        }
        val measured = 10 * log10(outP / inP)
        assertEquals(EqMath.responseDb(doubleArrayOf(0.0, 0.0, 0.0, 6.0), 14000.0) - 2.0, measured, 0.15)
    }

    @Test fun changingTheBalanceWhilePlayingDoesNotClick() {
        val chain = EqChain(sr, EqProfile.FLAT)
        val src = Sine(3000.0, 0.2f, sr)
        val buf = FloatArray(1024)
        var prev = 0f; var maxStep = 0f; var peak = 0f
        repeat(300) { k ->
            if (k == 50) chain.target = EqProfile(listOf(-8.0, 0.0, 6.0, 6.0), -4.0)
            if (k == 150) chain.target = EqProfile.FLAT
            java.util.Arrays.fill(buf, 0f); src.render(buf, 512); chain.process(buf, 512)
            for (i in buf.indices step 2) { maxStep = maxOf(maxStep, abs(buf[i] - prev)); prev = buf[i]; peak = maxOf(peak, abs(buf[i])) }
        }
        // a sine of amplitude P moves at most 2π·f/sr·P per sample; a click would exceed that clearly
        val bound = (2 * PI * 3000 / sr * peak * 1.1).toFloat()
        assertTrue("max step $maxStep, bound $bound", maxStep < bound)
    }

    // ---------------- adaptive search ----------------

    private val allRegions = booleanArrayOf(true, true, true, true)

    /** Ideal listener: prefers the version closer to [target]; says "no difference" below [jnd] dB. */
    private fun run(p: Personalizer, target: DoubleArray, jnd: Double = 0.0): Personalizer {
        var guard = 0
        while (!p.done && guard++ < 50) {
            val c = p.comparison()!!
            val da = dist(c.a, target); val db = dist(c.b, target)
            p.answer(
                when {
                    abs(da - db) < jnd -> Personalizer.Answer.NO_DIFFERENCE
                    da < db -> Personalizer.Answer.A_BETTER
                    else -> Personalizer.Answer.B_BETTER
                }
            )
        }
        return p
    }

    private fun dist(a: DoubleArray, b: DoubleArray) = sqrt(a.indices.sumOf { (a[it] - b[it]).pow(2) })

    @Test fun planHasFiveToEightComparisons() {
        assertEquals(8, Personalizer.create(allRegions, 1).total)
        assertEquals(5, Personalizer.create(booleanArrayOf(false, true, false, false), 1).total)
        assertEquals(6, Personalizer.create(booleanArrayOf(true, true, true, false), 1).total)
        assertTrue(Personalizer.create(BooleanArray(4), 1).done)
    }

    @Test fun searchMovesTowardsTheListenersPreference() {
        for (target in listOf(doubleArrayOf(4.0, 2.0, -2.0, -4.0), doubleArrayOf(0.0, 0.0, 3.0, 5.0), doubleArrayOf(-5.0, 0.0, 0.0, 2.0))) {
            val p = run(Personalizer.create(allRegions, 7), target)
            val before = dist(DoubleArray(4), target)
            val after = dist(p.gainsDb, target)
            assertTrue("target ${target.toList()} result ${p.gainsDb.toList()}", after < before * 0.75)
            assertFalse(p.noClearPreference())
        }
    }

    @Test fun gainsNeverLeaveTheSafeRange() {
        val p = run(Personalizer.create(allRegions, 3), DoubleArray(4) { 40.0 })
        assertTrue(p.gainsDb.all { it in EqBands.MIN_DB..EqBands.MAX_DB })
        val q = run(Personalizer.create(allRegions, 3), DoubleArray(4) { -40.0 })
        assertTrue(q.gainsDb.all { it in EqBands.MIN_DB..EqBands.MAX_DB })
    }

    @Test fun noDifferenceEndsEarlyAndRecommendsTheOriginal() {
        val p = Personalizer.create(allRegions, 5)
        var n = 0
        while (!p.done) { p.answer(Personalizer.Answer.NO_DIFFERENCE); n++ }
        assertTrue("comparisons $n", n <= 4) // each direction retired after its first "no difference"
        assertTrue(p.noClearPreference())
        assertEquals(DoubleArray(4).toList(), p.gainsDb.toList())
    }

    @Test fun backSkipAndDeterminism() {
        val a = Personalizer.create(allRegions, 11)
        a.answer(Personalizer.Answer.A_BETTER)
        val afterOne = a.gainsDb.toList()
        a.answer(Personalizer.Answer.B_BETTER)
        a.back()
        assertEquals(afterOne, a.gainsDb.toList())
        val step = a.comparison()!!.step
        a.answer(Personalizer.Answer.SKIP)
        assertEquals(afterOne, a.gainsDb.toList())
        assertEquals(step + 1, a.comparison()!!.step)
        // same seed and answers → same result; the A/B order is not always the same
        val target = doubleArrayOf(3.0, 1.0, -1.0, -3.0)
        assertEquals(run(Personalizer.create(allRegions, 42), target).gainsDb.toList(), run(Personalizer.create(allRegions, 42), target).gainsDb.toList())
        val p = Personalizer.create(allRegions, 42)
        val orders = HashSet<Boolean>()
        while (!p.done) { val c = p.comparison()!!; orders += c.a.sum() > c.b.sum(); p.answer(Personalizer.Answer.SKIP) }
        assertEquals(2, orders.size)
    }

    @Test fun sessionCanBeStoppedAndResumed() {
        val target = doubleArrayOf(2.0, 0.0, 2.0, 4.0)
        val q = Personalizer.create(allRegions, 9)
        repeat(3) {
            val c = q.comparison()!!
            q.answer(if (dist(c.a, target) < dist(c.b, target)) Personalizer.Answer.A_BETTER else Personalizer.Answer.B_BETTER)
        }
        val resumed = Personalizer.decode(q.encode())!!
        assertEquals(q.comparison()!!.step, resumed.comparison()!!.step)
        assertEquals(run(q, target).gainsDb.toList(), run(resumed, target).gainsDb.toList())
        val profile = EqProfile(listOf(1.0, -2.5, 3.0, 6.0), -1.25)
        assertEquals(profile, EqProfile.decode(profile.encode()))
        assertEquals(EqBands.MAX_DB, EqProfile.decode("99,0,0,0,0")!!.gainsDb[0], 0.0)
        assertEquals(null, EqProfile.decode("garbage"))
    }

    // ---------------- A/B playback ----------------

    @Test fun abSwitchingIsSeamlessAndStopsByItself() {
        val spec = SpectrumAnalyzer.analyze(noise(NoiseColor.PINK), sr)
        val a = EqMath.profile(spec, doubleArrayOf(0.0, 0.0, 0.0, 0.0))
        val b = EqMath.profile(spec, doubleArrayOf(-4.0, -2.0, 2.0, 4.0))
        val v = AbCompareVoice(Sine(1500.0, 0.2f, sr), sr, a, b, maxMs = 2000)
        val buf = FloatArray(1024)
        var prev = 0f; var maxStep = 0f; var peak = 0f
        v.select(0)
        repeat(320) { k ->
            if (k == 40) v.select(1)
            if (k == 80) v.select(0)
            java.util.Arrays.fill(buf, 0f); v.render(buf, 512)
            for (i in buf.indices step 2) { maxStep = maxOf(maxStep, abs(buf[i] - prev)); prev = buf[i]; peak = maxOf(peak, abs(buf[i])) }
        }
        assertTrue("max step $maxStep", maxStep < 0.1f)
        assertTrue("peak $peak", peak < 0.4f)
        assertEquals(-1, v.selected) // stopped by itself after 2 s
        java.util.Arrays.fill(buf, 0f); repeat(30) { v.render(buf, 512) }
        assertTrue(buf.all { abs(it) < 1e-6f })
    }
}
