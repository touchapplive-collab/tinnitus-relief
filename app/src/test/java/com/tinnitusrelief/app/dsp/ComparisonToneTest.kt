package com.tinnitusrelief.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Guided Match comparison tones: duration, fades, instant switching, replay, glide, loudness. */
class ComparisonToneTest {

    private val sr = 48000
    private val amp = 0.1f

    /** Renders [ms] of mono output (left channel) in 256-frame blocks, like the audio thread. */
    private fun render(v: ComparisonToneVoice, ms: Int, onBlock: () -> Unit = {}): FloatArray {
        val frames = sr * ms / 1000
        val outMono = FloatArray(frames)
        val buf = FloatArray(512)
        var pos = 0
        while (pos < frames) {
            val n = minOf(256, frames - pos)
            java.util.Arrays.fill(buf, 0f)
            v.render(buf, n)
            for (i in 0 until n) outMono[pos + i] = buf[2 * i]
            pos += n
            onBlock()
        }
        return outMono
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return kotlin.math.sqrt(s / (to - from))
    }

    private fun ms(m: Int) = sr * m / 1000

    @Test fun eachTonePlaysAtMostFiveSecondsWithFades() {
        val v = ComparisonToneVoice(sr)
        v.play(0, 1000.0, amp)
        val x = render(v, 6000)
        assertTrue("starts from silence", abs(x[0]) < 1e-4f)
        // fade-in: quieter in the first 50 ms than at full level
        assertTrue(rms(x, 0, ms(50)) < rms(x, ms(1000), ms(2000)) * 0.3)
        // full level (sine RMS = amp/√2) in the middle
        assertEquals(amp / kotlin.math.sqrt(2.0), rms(x, ms(1000), ms(4000)), 0.002)
        // silent after 5 s (+ a block of scheduling slack)
        var lastSound = 0
        for (i in x.indices) if (abs(x[i]) > 1e-6f) lastSound = i
        assertTrue("ends at ${lastSound * 1000 / sr} ms", lastSound in ms(4990)..ms(5010))
        assertEquals(-1, v.playingId)
    }

    @Test fun selectingAnotherToneStopsThePreviousImmediatelyWithoutClicks() {
        val v = ComparisonToneVoice(sr)
        v.play(0, 1000.0, amp)
        render(v, 1000)
        assertEquals(0, v.playingId)
        v.play(1, 3000.0, amp)
        var maxStep = 0f
        var prev = 0f
        val x = render(v, 1000)
        for (s in x) { maxStep = maxOf(maxStep, abs(s - prev)); prev = s }
        assertEquals(1, v.playingId)
        // a 3 kHz sine at amp 0.1 moves at most 2π·3000/48000·0.1 ≈ 0.04 per sample; a click would be far more
        assertTrue("max step $maxStep", maxStep < 0.05f)
        assertTrue("peak never above the tone level", x.all { abs(it) <= amp * 1.0001f })
        // after the 150 ms crossfade only the new 3 kHz tone remains: count zero crossings over 0.5 s
        var crossings = 0
        for (i in ms(400) until ms(900)) if (x[i - 1] < 0 && x[i] >= 0) crossings++
        assertTrue("crossings $crossings", abs(crossings - 1500) <= 3)
    }

    @Test fun replayRestartsAndStopFadesOutIn150ms() {
        val v = ComparisonToneVoice(sr)
        v.play(2, 2000.0, amp)
        render(v, 4000)
        v.play(2, 2000.0, amp) // replay: another full 5 s from now
        render(v, 4500)
        assertEquals(2, v.playingId)
        v.stop()
        val x = render(v, 400)
        assertEquals(-1, v.playingId)
        var lastSound = 0
        for (i in x.indices) if (abs(x[i]) > 1e-6f) lastSound = i
        assertTrue("silent ${lastSound * 1000 / sr} ms after stop", lastSound <= ms(160))
    }

    @Test fun rapidTappingStaysBoundedAndStable() {
        val v = ComparisonToneVoice(sr)
        val freqs = doubleArrayOf(500.0, 4000.0, 9000.0)
        var peak = 0f
        repeat(60) { k ->
            v.play(k % 3, freqs[k % 3], amp)
            val x = render(v, 37)
            for (s in x) { assertTrue(!s.isNaN()); peak = maxOf(peak, abs(s)) }
        }
        assertTrue("peak $peak", peak <= amp * 1.0001f)
    }

    @Test fun retuneGlidesWithoutRestarting() {
        val v = ComparisonToneVoice(sr)
        v.play(10, 1000.0, amp)
        render(v, 500)
        v.retune(1100.0, amp)
        var prev = 0f; var maxStep = 0f
        val x = render(v, 1000)
        for (s in x) { maxStep = maxOf(maxStep, abs(s - prev)); prev = s }
        assertEquals(10, v.playingId)
        assertTrue("max step $maxStep", maxStep < 0.02f)
        var crossings = 0
        for (i in ms(500) until ms(1000)) if (x[i - 1] < 0 && x[i] >= 0) crossings++
        assertTrue("crossings $crossings", abs(crossings - 550) <= 3)
    }

    @Test fun loudnessCompensationOnlyEverTurnsTonesDown() {
        var f = 20.0
        while (f < 20000) {
            assertTrue("$f Hz", LoudnessCompensation.attenuationDb(f) <= 0.0)
            f *= 1.05
        }
        assertEquals(0.0, LoudnessCompensation.attenuationDb(1000.0), 1e-9)
        assertEquals(0.0, LoudnessCompensation.attenuationDb(125.0), 1e-9)
        assertEquals(0.0, LoudnessCompensation.attenuationDb(8000.0), 1e-9)
        assertEquals(-4.4, LoudnessCompensation.attenuationDb(3150.0), 0.01)
        assertTrue(LoudnessCompensation.attenuationDb(4000.0) in -4.0..-3.0)
    }
}
