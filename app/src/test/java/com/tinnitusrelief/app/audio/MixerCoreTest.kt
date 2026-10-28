package com.tinnitusrelief.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** JVM tests for gain staging, peak protection, click-free transitions and loop continuity. */
class MixerCoreTest {

    private val sr = 48000
    private val block = 1024

    /** A constant-amplitude test signal that would clip hard without the limiter. */
    private class Dc(private val v: Float) : Voice {
        override fun render(out: FloatArray, frames: Int) {
            for (i in 0 until frames * 2) out[i] += v
        }
    }

    private class Sine(private val freq: Double, private val amp: Float, private val sr: Int) : Voice {
        private var ph = 0.0
        override fun render(out: FloatArray, frames: Int) {
            for (i in 0 until frames) {
                val s = (amp * kotlin.math.sin(ph)).toFloat()
                out[2 * i] += s; out[2 * i + 1] += s
                ph += 2 * Math.PI * freq / sr
            }
        }
    }

    private fun run(m: MixerCore, seconds: Double, onBlock: (FloatArray) -> Unit = {}) {
        val buf = FloatArray(block * 2)
        val blocks = (seconds * sr / block).toInt()
        repeat(blocks) { m.render(buf, block); onBlock(buf.copyOf()) }
    }

    @Test fun sliderTaperIsMonotonicAndBounded() {
        assertEquals(0f, MixerCore.sliderToGain(0f), 0f)
        assertEquals(1f, MixerCore.sliderToGain(1f), 1e-6f)
        var last = 0f
        for (i in 1..100) {
            val g = MixerCore.sliderToGain(i / 100f)
            assertTrue("gain must rise", g >= last)
            assertTrue("never above unity", g <= 1f)
            last = g
        }
        // -24 dB at the midpoint (48 dB range)
        assertEquals(-24.0, 20 * log10(MixerCore.sliderToGain(0.5f).toDouble()), 0.01)
    }

    @Test fun gainIsAccurateAfterSmoothing() {
        val m = MixerCore(sr)
        m.setMaskerVoice(Dc(0.1f)); m.setLayerAGain(0.5f); m.play(10)
        var last = 0f
        run(m, 1.0) { last = it[it.size - 2] }
        assertEquals(0.05f, last, 1e-4f)
    }

    @Test fun outputNeverClipsEvenWithExtremeInput() {
        val m = MixerCore(sr)
        m.setMaskerVoice(Dc(8f)); m.setLayerAGain(1f)
        m.setLayer(Sine(1000.0, 5f, sr), 1f)
        m.play(10)
        run(m, 2.0) { b -> b.forEach { assertTrue(abs(it) <= MixerCore.HARD_CLIP) } }
        assertTrue(m.peak <= MixerCore.CEILING + 1e-4f)
    }

    @Test fun limiterDoesNotTouchNormalLevels() {
        val m = MixerCore(sr)
        m.setMaskerVoice(Sine(440.0, 0.3f, sr)); m.setLayerAGain(1f); m.play(10)
        run(m, 1.0)
        m.resetPeak(); run(m, 0.5)
        assertEquals(0.3f, m.peak, 0.005f)
        assertFalse(m.limiterActive)
    }

    @Test fun fadeInAndOutAreClickFree() {
        val m = MixerCore(sr)
        m.setMaskerVoice(Dc(0.5f)); m.setLayerAGain(1f)
        m.play(1500)
        var prev = 0f
        var maxStep = 0f
        val check: (FloatArray) -> Unit = { b ->
            for (i in b.indices step 2) { maxStep = maxOf(maxStep, abs(b[i] - prev)); prev = b[i] }
        }
        run(m, 2.0, check)
        m.pause(800)
        run(m, 1.5, check)
        // a 0.5 step spread over >= 800 ms can never move more than a tiny amount per sample
        assertTrue("max per-sample step $maxStep", maxStep < 0.002f)
        assertTrue(m.isSilent)
        assertEquals(0f, prev, 0f)
    }

    @Test fun switchingSoundsCrossfadesWithoutJumps() {
        val m = MixerCore(sr)
        m.setMaskerVoice(Dc(0.4f)); m.setLayerAGain(1f); m.play(10)
        run(m, 0.5)
        m.setMaskerVoice(Dc(-0.4f))
        var prev = 0.4f
        var maxStep = 0f
        run(m, 2.0) { b -> for (i in b.indices step 2) { maxStep = maxOf(maxStep, abs(b[i] - prev)); prev = b[i] } }
        assertTrue("max per-sample step $maxStep", maxStep < 0.002f)
        assertEquals(-0.4f, prev, 1e-4f)
    }

    @Test fun duckingReducesLevelSmoothly() {
        val m = MixerCore(sr)
        m.setMaskerVoice(Dc(0.5f)); m.setLayerAGain(1f); m.play(10)
        run(m, 0.5)
        m.setDuck(true)
        var last = 0f
        run(m, 1.0) { last = it[0] }
        assertEquals(0.5f * MixerCore.DUCK_GAIN, last, 1e-3f)
    }

    @Test fun layerBIsSilentUnlessExplicitlyEnabled() {
        val m = MixerCore(sr)
        m.setMaskerVoice(null); m.setLayerAGain(1f); m.play(10)
        run(m, 0.5)
        m.resetPeak(); run(m, 0.5)
        assertEquals(0f, m.peak, 0f)
    }

    @Test fun pcmLoopIsContinuousAcrossTheWrapPoint() {
        // a ramp buffer whose end meets its start: the loop must not jump at the seam
        val frames = 4800
        val data = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (kotlin.math.sin(2 * Math.PI * i / frames) * 16000).toInt().toShort()
            data[2 * i] = v; data[2 * i + 1] = v
        }
        val voice = PcmLoopVoice(data, 44100, 48000)
        val out = FloatArray(20000 * 2)
        voice.render(out, 20000)
        var maxStep = 0f
        for (i in 2 until out.size step 2) maxStep = maxOf(maxStep, abs(out[i] - out[i - 2]))
        assertTrue("max step $maxStep", maxStep < 0.03f)
    }

    @Test fun noiseIsStableAndCalibrated() {
        for (c in NoiseColor.values()) {
            val v = NoiseVoice(c)
            val out = FloatArray(sr * 20 * 2)
            v.render(out, sr * 20)
            var sum = 0.0
            for (x in out) {
                assertFalse(x.isNaN())
                sum += x * x
            }
            val db = 20 * log10(sqrt(sum / out.size))
            assertTrue("$c level $db dBFS", db in -24.0..-17.0)
        }
    }

    @Test fun seamlessLoopRemovesTheClickAtTheWrapPoint() {
        // a smooth random walk whose end is far from its start: looping it raw would click
        val sr = 44100
        val frames = sr * 4
        val data = ShortArray(frames * 2)
        val rnd = java.util.Random(9)
        var v = 0.0
        for (i in 0 until frames) {
            v = (v + rnd.nextGaussian() * 60).coerceIn(-20000.0, 20000.0)
            val s = (v + i * 2.0).coerceIn(-32000.0, 32000.0).toInt().toShort() // drift → big end/start gap
            data[2 * i] = s; data[2 * i + 1] = s
        }
        val rawJump = abs(data[2 * (frames - 1)] - data[0])
        val looped = Seamless.makeSeamless(Pcm(data, sr), crossfadeSeconds = 1f, targetRmsDb = -20f)
        val n = looped.frames
        val seam = abs(looped.data[2 * (n - 1)] - looped.data[0]).toDouble()
        var typical = 0.0
        for (i in 1 until n) typical = maxOf(typical, abs(looped.data[2 * i] - looped.data[2 * (i - 1)]).toDouble())
        assertTrue("raw loop would jump $rawJump", rawJump > 5000)
        assertTrue("seam $seam vs largest normal step $typical", seam <= typical)
        // loudness normalised to about -20 dBFS (measured on the source, so the crossfade shifts it slightly)
        var sum = 0.0
        for (x in looped.data) sum += x.toDouble() * x
        val db = 20 * log10(sqrt(sum / looped.data.size) / 32768.0)
        assertEquals(-20.0, db, 1.5)
    }

    private fun MixerCore.setLayer(v: Voice, g: Float) = setLayerB(v, g)
}
