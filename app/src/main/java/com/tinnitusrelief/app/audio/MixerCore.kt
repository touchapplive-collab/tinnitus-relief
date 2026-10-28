package com.tinnitusrelief.app.audio

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/** A gain value shared between the UI thread (writes [target]) and the audio thread (smooths towards it). */
class GainParam(initial: Float) {
    @Volatile var target: Float = initial
}

/**
 * Pure-Kotlin mixing core (no Android dependencies, unit-testable on the JVM).
 *
 *   Layer A  MASKER   up to [MAX_MASKER_CHANNELS] sounds, each with its own volume and crossfade ─┐
 *                     × masker volume × masker-enabled                                           ├─ sum
 *   Layer B  STIMULUS one experimental voice (Acoustic Lab), its own volume, capped at −6 dB ─────┘
 *        → transport fade (play/pause) → duck (audio focus) → peak limiter (−1 dBFS) → hard clip guard
 *
 * Every gain is smoothed per sample, so no parameter change produces a click.
 * Thread safety: gain targets are volatile; structural changes (adding/removing voices, transport) are
 * queued and applied by the audio thread at the start of the next block.
 */
class MixerCore(val sampleRate: Int) {

    private class Channel(val id: String, val voice: Voice, val volume: GainParam) {
        var vol = volume.target
        var xf = 0f
        var xfTarget = 1f
    }

    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val masker = ArrayList<Channel>(8)
    private val stimulus = ArrayList<Channel>(2)
    private var scratch = FloatArray(0)
    private var busB = FloatArray(0)

    private val maskerVolume = GainParam(0f)
    private val maskerEnabled = GainParam(1f)
    private val stimulusVolume = GainParam(0f)
    private val duckParam = GainParam(1f)
    private var mA = 0f
    private var mEn = 1f
    private var mB = 0f
    private var duck = 1f

    // transport
    private var fade = 0f
    private var fadeTarget = 0f
    private var fadeStep = 1f

    // limiter
    private var limGain = 1f
    private val limRelease = 1f - exp(-1.0 / (0.25 * sampleRate)).toFloat()
    private val smooth = 1f - exp(-1.0 / (0.05 * sampleRate)).toFloat()
    private val crossfadeStep = 1f / (1.2f * sampleRate)

    @Volatile var peak = 0f
        private set
    @Volatile var limiterActive = false
        private set

    /** True when paused and the fade-out has finished (the engine may then sleep). */
    val isSilent: Boolean get() = fade == 0f && fadeTarget == 0f

    fun resetPeak() { peak = 0f }

    // ---------------- Layer A: masker ----------------

    /** Adds a masker sound (or crossfades to a new voice if [id] is already playing). */
    fun setMaskerChannel(id: String, voice: Voice, volume: GainParam) = post {
        for (c in masker) if (c.id == id) c.xfTarget = 0f
        val ch = Channel(id, voice, volume)
        // when nothing is audible yet, start at full level instead of crossfading
        if (fade == 0f) ch.xf = 1f
        masker.add(ch)
        trimMasker()
    }

    fun removeMaskerChannel(id: String) = post { for (c in masker) if (c.id == id) c.xfTarget = 0f }

    fun clearMasker() = post { for (c in masker) c.xfTarget = 0f }

    /** Convenience for a single-sound masker. */
    fun setMaskerVoice(voice: Voice?) {
        clearMasker()
        if (voice != null) setMaskerChannel("single", voice, GainParam(1f))
    }

    private fun trimMasker() {
        // never more than MAX_MASKER_CHANNELS audible channels: fade out the oldest extras
        val alive = masker.filter { it.xfTarget > 0f }
        if (alive.size > MAX_MASKER_CHANNELS) {
            for (c in alive.take(alive.size - MAX_MASKER_CHANNELS)) c.xfTarget = 0f
        }
    }

    /** Masker (layer A) volume, linear 0..1. */
    fun setLayerAGain(gain: Float) { maskerVolume.target = gain.coerceIn(0f, 1f) }

    /** Mutes/unmutes the masker without touching its settings (used while the Lab plays alone). */
    fun setMaskerEnabled(enabled: Boolean) { maskerEnabled.target = if (enabled) 1f else 0f }

    // ---------------- Layer B: stimulation ----------------

    /**
     * Sets the experimental stimulus voice (null = off) with a crossfade. Its gain is capped at
     * [LAYER_B_MAX] (−6 dB) so the experimental layer can never be driven to full scale by the UI.
     */
    fun setLayerB(voice: Voice?, gain: Float) {
        if (voice != null) stimulusVolume.target = gain.coerceIn(0f, LAYER_B_MAX)
        post {
            for (c in stimulus) c.xfTarget = 0f
            if (voice != null) stimulus.add(Channel("b", voice, GainParam(1f)).also { if (fade == 0f) it.xf = 1f })
        }
    }

    fun setLayerBGain(gain: Float) { stimulusVolume.target = gain.coerceIn(0f, LAYER_B_MAX) }

    // ---------------- transport & focus ----------------

    fun setDuck(ducked: Boolean) { duckParam.target = if (ducked) DUCK_GAIN else 1f }

    fun play(fadeInMs: Int = 1500) = post { startFade(1f, fadeInMs) }

    fun pause(fadeOutMs: Int = 800) = post { startFade(0f, fadeOutMs) }

    private fun startFade(target: Float, ms: Int) {
        fadeTarget = target
        fadeStep = 1f / ((ms.coerceAtLeast(10) / 1000f) * sampleRate)
    }

    private fun post(cmd: () -> Unit) { commands.add(cmd) }

    // ---------------- rendering ----------------

    private fun renderChannels(list: ArrayList<Channel>, dest: FloatArray, frames: Int) {
        val n = frames * 2
        val it = list.iterator()
        while (it.hasNext()) {
            val c = it.next()
            java.util.Arrays.fill(scratch, 0, n, 0f)
            c.voice.render(scratch, frames)
            var xf = c.xf
            var vol = c.vol
            val vt = c.volume.target
            var o = 0
            for (k in 0 until frames) {
                if (xf < c.xfTarget) xf = minOf(c.xfTarget, xf + crossfadeStep)
                else if (xf > c.xfTarget) xf = maxOf(c.xfTarget, xf - crossfadeStep)
                vol += (vt - vol) * smooth
                val w = xf * xf * (3f - 2f * xf) * vol
                dest[o] += scratch[o] * w
                dest[o + 1] += scratch[o + 1] * w
                o += 2
            }
            c.xf = xf
            c.vol = vol
            if (xf == 0f && c.xfTarget == 0f) it.remove()
        }
    }

    /** Renders [frames] stereo frames into [out] (overwrites). Audio thread only. */
    fun render(out: FloatArray, frames: Int) {
        while (true) (commands.poll() ?: break).invoke()
        val n = frames * 2
        if (scratch.size < n) { scratch = FloatArray(n); busB = FloatArray(n) }
        java.util.Arrays.fill(out, 0, n, 0f)
        java.util.Arrays.fill(busB, 0, n, 0f)

        renderChannels(masker, out, frames)
        if (stimulus.isNotEmpty()) renderChannels(stimulus, busB, frames)

        val aT = maskerVolume.target
        val eT = maskerEnabled.target
        val bT = stimulusVolume.target
        val dT = duckParam.target
        var a = mA; var e = mEn; var b = mB; var d = duck
        var f = fade
        var lg = limGain
        var pk = peak
        var limited = false
        var o = 0
        for (k in 0 until frames) {
            a += (aT - a) * smooth
            e += (eT - e) * smooth
            b += (bT - b) * smooth
            d += (dT - d) * smooth
            if (f < fadeTarget) f = minOf(fadeTarget, f + fadeStep)
            else if (f > fadeTarget) f = maxOf(fadeTarget, f - fadeStep)
            val master = f * f * (3f - 2f * f) * d
            val ga = a * e
            var l = (out[o] * ga + busB[o] * b) * master
            var r = (out[o + 1] * ga + busB[o + 1] * b) * master
            if (l.isNaN() || r.isNaN()) { l = 0f; r = 0f }
            // peak limiter: instant attack (never exceeds the ceiling), smooth release
            val p = maxOf(abs(l), abs(r))
            if (p * lg > CEILING) { lg = CEILING / p; limited = true } else lg += (1f - lg) * limRelease
            l = (l * lg).coerceIn(-HARD_CLIP, HARD_CLIP)
            r = (r * lg).coerceIn(-HARD_CLIP, HARD_CLIP)
            out[o] = l
            out[o + 1] = r
            val ap = maxOf(abs(l), abs(r))
            if (ap > pk) pk = ap
            o += 2
        }
        mA = a; mEn = e; mB = b; duck = d; fade = f; limGain = lg; peak = pk
        limiterActive = limited
    }

    companion object {
        const val MAX_MASKER_CHANNELS = 3
        /** Limiter ceiling, linear (−1 dBFS). */
        const val CEILING = 0.891f
        const val HARD_CLIP = 0.98f
        const val DUCK_GAIN = 0.3f
        /** Cap for the experimental layer: −6 dB. */
        const val LAYER_B_MAX = 0.5f
        const val MIN_DB = -48f

        /**
         * Slider position 0..1 → linear gain with a dB taper (48 dB range). 0 is true silence and 1.0 is
         * 0 dB of the (loudness-normalised) source, never above it.
         */
        fun sliderToGain(v: Float): Float {
            if (v <= 0.005f) return 0f
            val db = MIN_DB * (1f - v.coerceIn(0f, 1f))
            return 10f.pow(db / 20f)
        }
    }
}
