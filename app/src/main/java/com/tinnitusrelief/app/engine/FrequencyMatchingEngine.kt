package com.tinnitusrelief.app.engine

import com.tinnitusrelief.app.audio.MixerCore
import com.tinnitusrelief.app.dsp.ThreeAfcLoudness
import com.tinnitusrelief.app.dsp.ThreeAfcPitch
import com.tinnitusrelief.app.dsp.ToneSequenceVoice
import com.tinnitusrelief.app.dsp.ToneVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.pow

/**
 * Personalised tinnitus pitch (and optional loudness) estimation.
 *
 * Two methods share one tone path on Layer B (masker muted while matching):
 *  - Guided 3AFC (published procedure, see [ThreeAfcPitch]) followed by an octave check;
 *  - Manual tuner with a logarithmic slider and 1/24-octave fine steps.
 *
 * Tone levels are in dBFS of the phone output. They are NOT calibrated hearing levels; results are
 * subjective estimates for personal use, never a clinical measurement.
 */
class FrequencyMatchingEngine(private val sessions: SessionManager, private val sampleRate: Int) {

    val maxHz: Double = minOf(16000.0, sampleRate * 0.45)

    private val _toneDb = MutableStateFlow(DEFAULT_TONE_DB)
    /** Comfortable tone level chosen by the user (dBFS, uncalibrated). */
    val toneDb: StateFlow<Double> = _toneDb

    var afc: ThreeAfcPitch = ThreeAfcPitch(maxHz = maxHz)
        private set
    var loudness: ThreeAfcLoudness? = null
        private set

    private var steady: ToneVoice? = null
    private var sequence: ToneSequenceVoice? = null

    suspend fun begin(label: String): Boolean = sessions.acquire(LabActivity.MATCHING, label, muteMasker = true)

    fun restartAfc() { afc = ThreeAfcPitch(maxHz = maxHz) }

    fun setToneDb(db: Double) {
        _toneDb.value = db.coerceIn(MIN_TONE_DB, MAX_TONE_DB)
        steady?.targetAmp = amp(_toneDb.value)
    }

    /** Plays the three tones (500 ms, 1 s gaps). Returns the sequence so the UI can follow it. */
    fun playTriple(freqs: DoubleArray, levelsDb: DoubleArray? = null): ToneSequenceVoice {
        steady = null
        val amps = FloatArray(3) { amp(levelsDb?.get(it) ?: _toneDb.value) }
        val seq = ToneSequenceVoice(sampleRate, freqs, amps, ThreeAfcPitch.TONE_MS, ThreeAfcPitch.GAP_MS)
        sequence = seq
        sessions.setStimulus(seq, MixerCore.LAYER_B_MAX)
        return seq
    }

    /** A continuous tone for manual tuning or threshold finding; frequency and level glide smoothly. */
    fun playSteady(freq: Double, db: Double = _toneDb.value) {
        val s = steady
        if (s != null) { s.targetFreq = freq; s.targetAmp = amp(db); return }
        sequence = null
        val t = ToneVoice(sampleRate, freq, amp(db))
        steady = t
        sessions.setStimulus(t, MixerCore.LAYER_B_MAX)
    }

    fun setSteady(freq: Double, db: Double) { steady?.let { it.targetFreq = freq; it.targetAmp = amp(db) } }

    fun silence() {
        steady = null; sequence = null
        sessions.setStimulus(null, 0f)
    }

    fun startLoudness(thresholdDb: Double) { loudness = ThreeAfcLoudness(thresholdDb, maxDbFs = MAX_TONE_DB) }

    fun end() {
        silence()
        sessions.stop(300)
    }

    /** Linear voice amplitude for a target output level in dBFS (Layer B gain is fixed at −6 dB here). */
    private fun amp(db: Double): Float =
        (10.0.pow(db.coerceIn(MIN_TONE_DB, MAX_TONE_DB) / 20.0) / MixerCore.LAYER_B_MAX).toFloat()

    companion object {
        /** Quiet default; the user raises it only to "clearly but comfortably audible". */
        const val DEFAULT_TONE_DB = -40.0
        const val MIN_TONE_DB = -80.0
        /** Hard safety cap for any matching tone. */
        const val MAX_TONE_DB = -12.0
    }
}
