package com.tinnitusrelief.app.audio

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Pure-Kotlin mixing core (no Android dependencies, unit-testable on the JVM).
 *
 * Signal flow per stereo frame:
 *
 *   Layer A (masker slots, each with its own crossfade gain) ─┐
 *                                                            ├─ sum → play/pause fade → duck → peak limiter → out
 *   Layer B (acoustic stimulation bus, disabled by default) ──┘
 *
 * Every gain change is smoothed per sample, so no parameter change can produce a click or jump.
 * All public mutators are thread-safe: plain gain targets are volatile fields; structural changes
 * (voices, transport) are queued and applied by the audio thread at the start of the next block.
 */
class MixerCore(val sampleRate: Int) {

    private class Slot(val voice: Voice, var gain: Float, var target: Float)

    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val slots = ArrayList<Slot>(4)
    private var scratch = FloatArray(0)

    // Layer gains (linear) and their smoothed values
    @Volatile private var layerATarget = 0f
    private var layerA = 0f
    private var layerBTarget = 0f
    private var layerB = 0f
    private var layerBVoice: Voice? = null
    @Volatile private var duckTarget = 1f
    private var duck = 1f

    // Transport fade (0 = silent, 1 = full)
    private var fade = 0f
    private var fadeTarget = 0f
    private var fadeStep = 0f

    // Limiter
    private var limGain = 1f
    private val limRelease = 1f - exp(-1.0 / (0.25 * sampleRate)).toFloat()
    private val smooth = 1f - exp(-1.0 / (0.05 * sampleRate)).toFloat()
    private val crossfadeStep = 1f / (1.2f * sampleRate)

    /** Highest absolute sample value produced since the last [resetPeak] (for tests/diagnostics). */
    @Volatile var peak = 0f
        private set

    @Volatile var limiterActive = false
        private set

    /** True when the transport is paused and the fade-out has fully finished. */
    val isSilent: Boolean get() = fade == 0f && fadeTarget == 0f

    fun resetPeak() { peak = 0f }

    /** Replaces the masker sound with a smooth crossfade. Passing null fades the masker out. */
    fun setMaskerVoice(voice: Voice?) = post {
        for (s in slots) s.target = 0f
        if (voice != null) {
            // Start audible immediately when nothing is playing, otherwise crossfade.
            val start = if (fade == 0f) 1f else 0f
            slots.add(Slot(voice, start, 1f))
        }
    }

    /** Masker layer gain, linear. */
    fun setLayerAGain(gain: Float) { layerATarget = gain.coerceIn(0f, 1f) }

    /** Acoustic-stimulation layer. Disabled (null, gain 0) unless explicitly set. */
    fun setLayerB(voice: Voice?, gain: Float) = post {
        layerBVoice = voice
        layerBTarget = if (voice == null) 0f else gain.coerceIn(0f, 1f)
    }

    fun setDuck(ducked: Boolean) { duckTarget = if (ducked) DUCK_GAIN else 1f }

    fun play(fadeInMs: Int = 1500) = post { startFade(1f, fadeInMs) }

    fun pause(fadeOutMs: Int = 800) = post { startFade(0f, fadeOutMs) }

    private fun startFade(target: Float, ms: Int) {
        fadeTarget = target
        val samples = (ms.coerceAtLeast(10) / 1000f) * sampleRate
        fadeStep = 1f / samples
    }

    private fun post(cmd: () -> Unit) { commands.add(cmd) }

    /** Renders [frames] stereo frames into [out] (overwrites). Called only from the audio thread. */
    fun render(out: FloatArray, frames: Int) {
        while (true) (commands.poll() ?: break).invoke()
        val n = frames * 2
        if (scratch.size < n) scratch = FloatArray(n)
        java.util.Arrays.fill(out, 0, n, 0f)

        // Layer A: each slot rendered into scratch, then added with its own ramp
        val it = slots.iterator()
        while (it.hasNext()) {
            val s = it.next()
            java.util.Arrays.fill(scratch, 0, n, 0f)
            s.voice.render(scratch, frames)
            var g = s.gain
            var o = 0
            for (k in 0 until frames) {
                if (g < s.target) g = minOf(s.target, g + crossfadeStep)
                else if (g > s.target) g = maxOf(s.target, g - crossfadeStep)
                // equal-power style curve for the crossfade
                val w = g * g * (3f - 2f * g)
                out[o] += scratch[o] * w
                out[o + 1] += scratch[o + 1] * w
                o += 2
            }
            s.gain = g
            if (g == 0f && s.target == 0f) it.remove()
        }

        // Layer B (stimulation bus) rendered separately so it gets its own gain
        val bVoice = layerBVoice
        val renderB = bVoice != null || layerB > 1e-6f
        if (renderB) {
            java.util.Arrays.fill(scratch, 0, n, 0f)
            bVoice?.render(scratch, frames)
        }

        val aT = layerATarget
        val dT = duckTarget
        var a = layerA
        var b = layerB
        var d = duck
        var f = fade
        var lg = limGain
        var pk = peak
        var limited = false
        var o = 0
        for (k in 0 until frames) {
            a += (aT - a) * smooth
            b += (layerBTarget - b) * smooth
            d += (dT - d) * smooth
            if (f < fadeTarget) f = minOf(fadeTarget, f + fadeStep)
            else if (f > fadeTarget) f = maxOf(fadeTarget, f - fadeStep)
            // perceptually smooth S-curve for the transport fade
            val fc = f * f * (3f - 2f * f)
            val master = fc * d
            var l = out[o] * a
            var r = out[o + 1] * a
            if (renderB) {
                l += scratch[o] * b
                r += scratch[o + 1] * b
            }
            l *= master
            r *= master
            // Peak limiter: instant attack (never overshoots the ceiling), smooth release
            val p = maxOf(abs(l), abs(r))
            if (p * lg > CEILING) { lg = CEILING / p; limited = true }
            else lg += (1f - lg) * limRelease
            l = (l * lg).coerceIn(-HARD_CLIP, HARD_CLIP)
            r = (r * lg).coerceIn(-HARD_CLIP, HARD_CLIP)
            out[o] = l
            out[o + 1] = r
            val ap = maxOf(abs(l), abs(r))
            if (ap > pk) pk = ap
            o += 2
        }
        layerA = a; layerB = b; duck = d; fade = f; limGain = lg; peak = pk
        limiterActive = limited
    }

    companion object {
        /** Limiter ceiling, linear (-1 dBFS). */
        const val CEILING = 0.891f
        const val HARD_CLIP = 0.98f
        const val DUCK_GAIN = 0.3f
        /** Lowest point of the user volume range. */
        const val MIN_DB = -48f

        /**
         * Maps a 0..1 slider position to linear gain. Logarithmic (dB) taper so the slider feels even;
         * 0 is true silence. 1.0 maps to 0 dB of the source material, never above it.
         */
        fun sliderToGain(v: Float): Float {
            if (v <= 0.005f) return 0f
            val db = MIN_DB * (1f - v.coerceIn(0f, 1f))
            return 10f.pow(db / 20f)
        }
    }
}
