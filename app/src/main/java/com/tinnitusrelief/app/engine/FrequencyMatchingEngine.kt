package com.tinnitusrelief.app.engine

import com.tinnitusrelief.app.audio.MixerCore
import com.tinnitusrelief.app.dsp.ComparisonToneVoice
import com.tinnitusrelief.app.dsp.LoudnessCompensation
import com.tinnitusrelief.app.dsp.ThreeAfcLoudness
import com.tinnitusrelief.app.dsp.ThreeAfcPitch
import com.tinnitusrelief.app.dsp.ToneVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.pow

/**
 * Personalised tinnitus pitch (and optional loudness) estimation.
 *
 * Two methods share one tone path on Layer B (masker muted while matching):
 *  - Guided 3AFC (published procedure, see [ThreeAfcPitch]) with independent A / B / C comparison tones
 *    (up to 5 s each, 150 ms fades), an octave check, and log-slider fine-tuning;
 *  - Manual tuner with a logarithmic slider and 1/24-octave fine steps.
 *
 * Tone levels are in dBFS of the phone output. They are NOT calibrated hearing levels; results are
 * subjective estimates for personal use, never a clinical measurement. Levels only change when the user
 * moves a level control; nothing here raises the volume on its own.
 */
class FrequencyMatchingEngine(private val sessions: SessionManager, private val sampleRate: Int) {

    val maxHz: Double = minOf(16000.0, sampleRate * 0.45)
    val minHz: Double = 125.0

    private val _toneDb = MutableStateFlow(DEFAULT_TONE_DB)
    /** Comfortable tone level chosen by the user (dBFS, uncalibrated). */
    val toneDb: StateFlow<Double> = _toneDb

    var afc: ThreeAfcPitch = ThreeAfcPitch(maxHz = maxHz)
        private set
    var loudness: ThreeAfcLoudness? = null
        private set

    private var steady: ToneVoice? = null
    private var steadyCompensated = false
    private var comparison: ComparisonToneVoice? = null
    private var comparisonFreq = 1000.0
    private var comparisonDb = DEFAULT_TONE_DB

    suspend fun begin(label: String): Boolean = sessions.acquire(LabActivity.MATCHING, label, muteMasker = true)

    fun restartAfc() {
        afc = ThreeAfcPitch(maxHz = maxHz)
        loudness = null
    }

    fun setToneDb(db: Double) {
        _toneDb.value = db.coerceIn(MIN_TONE_DB, MAX_TONE_DB)
        steady?.let { it.targetAmp = amp(_toneDb.value, it.targetFreq, steadyCompensated) }
    }

    // ---------------- comparison tones (A / B / C, fine-tuning) ----------------

    /**
     * Plays comparison tone [id] at [freq] for up to 5 s. Starting a tone stops the previous one at once.
     * [db] defaults to the user's tone level; loudness matching passes its own (capped) levels.
     */
    fun compare(id: Int, freq: Double, db: Double = _toneDb.value) {
        val v = comparisonVoice()
        comparisonFreq = freq
        comparisonDb = db
        v.play(id, freq, amp(db, freq, compensated = true))
    }

    /** Moves the playing comparison tone to [freq] (smooth glide); starts it if nothing is playing. */
    fun retune(id: Int, freq: Double) {
        val v = comparisonVoice()
        comparisonFreq = freq
        comparisonDb = _toneDb.value
        if (v.playingId == id) v.retune(freq, amp(comparisonDb, freq, compensated = true))
        else v.play(id, freq, amp(comparisonDb, freq, compensated = true))
    }

    fun stopCompare() { comparison?.stop() }

    /**
     * Installs the (silent) comparison player before the first tap, so the first tone gets its exact
     * 150 ms fade-in instead of the mixer's slower layer crossfade.
     */
    fun prepareCompare() { comparisonVoice() }

    /** Id of the comparison tone being heard, or −1. */
    val playingId: Int get() = comparison?.playingId ?: -1

    /** 0..1 progress of the comparison tone being heard. */
    val playingProgress: Float get() = comparison?.progress ?: 0f

    private fun comparisonVoice(): ComparisonToneVoice {
        comparison?.let { return it }
        steady = null
        val v = ComparisonToneVoice(sampleRate, COMPARE_MS, FADE_MS)
        comparison = v
        sessions.setStimulus(v, MixerCore.LAYER_B_MAX)
        return v
    }

    // ---------------- continuous tones (level setup, threshold, manual tuner) ----------------

    /**
     * A continuous tone for manual tuning or threshold finding; frequency and level glide smoothly.
     * [compensated] applies the same loudness compensation as the comparison tones (Guided Match only),
     * so levels set here and levels heard there are consistent.
     */
    fun playSteady(freq: Double, db: Double = _toneDb.value, compensated: Boolean = false) {
        val s = steady
        if (s != null) {
            steadyCompensated = compensated
            s.targetFreq = freq; s.targetAmp = amp(db, freq, compensated); return
        }
        comparison = null
        steadyCompensated = compensated
        val t = ToneVoice(sampleRate, freq, amp(db, freq, compensated))
        steady = t
        sessions.setStimulus(t, MixerCore.LAYER_B_MAX)
    }

    fun setSteady(freq: Double, db: Double) {
        steady?.let { it.targetFreq = freq; it.targetAmp = amp(db, freq, steadyCompensated) }
    }

    fun silence() {
        comparison?.stop()
        steady = null; comparison = null
        sessions.setStimulus(null, 0f)
    }

    fun startLoudness(thresholdDb: Double) { loudness = ThreeAfcLoudness(thresholdDb, maxDbFs = MAX_TONE_DB) }

    fun end() {
        silence()
        sessions.stop(300)
    }

    /** Linear voice amplitude for a target output level in dBFS (Layer B gain is fixed at −6 dB here). */
    private fun amp(db: Double, freq: Double, compensated: Boolean): Float {
        val level = db.coerceIn(MIN_TONE_DB, MAX_TONE_DB) + if (compensated) LoudnessCompensation.attenuationDb(freq) else 0.0
        return (10.0.pow(level / 20.0) / MixerCore.LAYER_B_MAX).toFloat()
    }

    companion object {
        /** Quiet default; the user raises it only to "clearly but comfortably audible". */
        const val DEFAULT_TONE_DB = -40.0
        const val MIN_TONE_DB = -80.0
        /** Hard safety cap for any matching tone. */
        const val MAX_TONE_DB = -12.0
        /** Comparison tone length and fades (Guided Match). */
        const val COMPARE_MS = 5000
        const val FADE_MS = 150
    }
}
