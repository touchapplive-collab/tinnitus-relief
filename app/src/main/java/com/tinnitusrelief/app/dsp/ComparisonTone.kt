package com.tinnitusrelief.app.dsp

import com.tinnitusrelief.app.audio.Voice
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Tone player for side-by-side comparison (Guided Match A / B / C and fine-tuning).
 *
 * - Each tone plays for at most [maxMs] (default 5 s) and then fades out by itself.
 * - Raised-cosine fade-in and fade-out of [fadeMs] (default 150 ms): no clicks.
 * - Starting another tone stops the previous one at once: the old tone begins its 150 ms fade-out in
 *   the same sample the new one begins its fade-in (equal-power style crossfade, peak never above the
 *   tone amplitude).
 * - Replaying the same tone restarts it from the beginning.
 * - Frequency and level changes while playing glide smoothly (used by the fine-tuning slider).
 * - Never changes its own level: the amplitude is exactly what the caller asks for.
 *
 * All control methods are safe to call from any thread; rendering happens on the audio thread.
 */
class ComparisonToneVoice(
    private val sampleRate: Int,
    maxMs: Int = 5000,
    fadeMs: Int = 150,
) : Voice {

    private class Request(val id: Int, val freq: Double, val amp: Float)

    private enum class Phase { IDLE, IN, HOLD, OUT }

    private class Slot {
        var phase = Phase.IDLE
        var id = -1
        var env = 0.0          // 0..1, shaped with a raised cosine
        var pos = 0L           // samples since start
        var osc = 0.0
        var freq = 1000.0
        var amp = 0f
    }

    private val maxLen = (maxMs.toLong() * sampleRate / 1000).coerceAtLeast(2)
    private val fadeLen = (fadeMs.toLong() * sampleRate / 1000).coerceAtLeast(1)
    private val envStep = 1.0 / fadeLen
    private val glide = 1.0 - exp(-1.0 / (0.03 * sampleRate))
    private val ampStep = 1f / (0.03f * sampleRate)

    private val slots = arrayOf(Slot(), Slot())
    private var active = -1    // index of the slot that is (or was last) the current tone

    private val pending = AtomicReference<Request?>(null)
    @Volatile private var stopRequested = false
    @Volatile private var targetFreq = 1000.0
    @Volatile private var targetAmp = 0f

    /** Id of the tone currently heard (−1 when silent or fading out after a stop). */
    @Volatile var playingId: Int = -1
        private set

    /** 0..1 position of the current tone within its [maxMs] window. */
    @Volatile var progress: Float = 0f
        private set

    /** Starts tone [id] (restarts it if it is already playing). */
    fun play(id: Int, freqHz: Double, amp: Float) {
        stopRequested = false
        targetFreq = freqHz
        targetAmp = amp
        pending.set(Request(id, freqHz, amp))
    }

    /** Fades the current tone out (150 ms). */
    fun stop() {
        pending.set(null)
        stopRequested = true
    }

    /** Glides the playing tone to a new frequency (and level) without restarting it. */
    fun retune(freqHz: Double, amp: Float) {
        targetFreq = freqHz
        targetAmp = amp
    }

    override fun render(out: FloatArray, frames: Int) {
        pending.getAndSet(null)?.let { start(it) }
        if (stopRequested) {
            stopRequested = false
            for (s in slots) if (s.phase != Phase.IDLE) s.phase = Phase.OUT
        }
        val tf = targetFreq
        val ta = targetAmp
        var o = 0
        for (n in 0 until frames) {
            var mix = 0.0
            for ((i, s) in slots.withIndex()) {
                if (s.phase == Phase.IDLE) continue
                when (s.phase) {
                    Phase.IN -> { s.env += envStep; if (s.env >= 1.0) { s.env = 1.0; s.phase = Phase.HOLD } }
                    Phase.HOLD -> if (s.pos >= maxLen - fadeLen) s.phase = Phase.OUT
                    Phase.OUT -> { s.env -= envStep; if (s.env <= 0.0) { s.env = 0.0; s.phase = Phase.IDLE } }
                    Phase.IDLE -> Unit
                }
                if (i == active && s.phase != Phase.OUT && s.phase != Phase.IDLE) {
                    s.freq *= (tf / s.freq).pow(glide)
                    if (s.amp < ta) s.amp = minOf(ta, s.amp + ampStep) else if (s.amp > ta) s.amp = maxOf(ta, s.amp - ampStep)
                }
                s.osc += 2 * PI * s.freq / sampleRate
                if (s.osc > 2 * PI) s.osc -= 2 * PI
                val shaped = 0.5 - 0.5 * cos(PI * s.env)
                mix += sin(s.osc) * shaped * s.amp
                s.pos++
            }
            val v = mix.toFloat()
            out[o] += v
            out[o + 1] += v
            o += 2
        }
        val cur = if (active >= 0) slots[active] else null
        if (cur != null && cur.phase != Phase.IDLE && cur.phase != Phase.OUT) {
            playingId = cur.id
            progress = (cur.pos.toFloat() / maxLen).coerceIn(0f, 1f)
        } else {
            playingId = -1
            progress = 0f
        }
    }

    private fun start(r: Request) {
        // the previous tone starts fading out now; the new one takes the other slot
        for (s in slots) if (s.phase != Phase.IDLE) s.phase = Phase.OUT
        val idx = if (active == 0) 1 else 0
        val s = slots[idx]
        // if that slot is still fading out (very fast tapping), take it over from its current envelope
        s.phase = Phase.IN
        s.id = r.id
        s.pos = 0
        s.freq = r.freq
        s.amp = r.amp
        if (s.env <= 0.0) s.osc = 0.0
        active = idx
    }
}

/**
 * Attenuation-only loudness compensation for matching tones, so tones at different frequencies sound
 * roughly equally loud without ever being made louder than the level the user chose.
 *
 * Based on the ISO 226:2003 equal-loudness contour at 40 phon, relative to 1 kHz. Where the ear is
 * more sensitive than at 1 kHz (about 1.6–5 kHz) the tone is turned DOWN by that difference
 * (at most about 4.4 dB at 3.15 kHz). Where the ear is less sensitive the tone is NOT boosted.
 * ENGINEERING CHOICE for listening comfort; headphones are uncalibrated, so this is approximate.
 */
object LoudnessCompensation {
    private val freqs = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0,
        630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0,
        10000.0, 12500.0,
    )
    private val phon40 = doubleArrayOf(
        99.85, 93.94, 88.17, 82.63, 77.78, 73.08, 68.48, 64.37, 60.59, 56.70, 53.41, 50.40, 47.58, 44.98, 43.05,
        41.34, 40.06, 40.01, 41.82, 42.51, 39.23, 36.51, 35.61, 36.65, 40.01, 45.83, 51.80,
        54.28, 51.49,
    )
    private const val REF = 40.01

    /** Gain change in dB for a tone at [hz]; always ≤ 0. */
    fun attenuationDb(hz: Double): Double {
        val f = hz.coerceIn(freqs.first(), freqs.last())
        var i = 0
        while (i < freqs.size - 2 && f > freqs[i + 1]) i++
        val t = (ln(f) - ln(freqs[i])) / (ln(freqs[i + 1]) - ln(freqs[i]))
        val spl = phon40[i] + (phon40[i + 1] - phon40[i]) * t.coerceIn(0.0, 1.0)
        return minOf(0.0, spl - REF)
    }
}
