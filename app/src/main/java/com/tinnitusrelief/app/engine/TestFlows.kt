package com.tinnitusrelief.app.engine

import android.os.SystemClock
import com.tinnitusrelief.app.audio.MixerCore
import com.tinnitusrelief.app.dsp.BandNoiseVoice
import com.tinnitusrelief.app.dsp.PublishedBands
import com.tinnitusrelief.app.dsp.ResponseRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * RELIEF TEST: rate tinnitus before a session, right after it, and once more a few minutes later.
 * Comparing "after" with "later" separates immediate masking from a possible lingering effect.
 * The 5-minute "later" delay is an engineering choice. Ratings are optional; no reminders are sent.
 */
class ReliefTestFlow(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val tracker: LocalResponseTracker,
    private val startLab: suspend (activity: LabActivity, minutes: Int) -> Boolean,
    private val labLabel: () -> String,
    private val mixLabel: () -> String,
) {
    enum class Stimulus { MIX, LAB }
    enum class Step { SETUP, BEFORE, PLAYING, AFTER, WAITING, LATER, DONE, FAILED }

    data class State(
        val step: Step = Step.SETUP,
        val stimulus: Stimulus = Stimulus.MIX,
        val minutes: Int = 10,
        val before: Int? = null,
        val after: Int? = null,
        val playedMinutes: Int = 0,
        val waitEndsAt: Long? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var job: Job? = null
    private var startedAt = 0L

    fun setStimulus(s: Stimulus) { _state.value = _state.value.copy(stimulus = s) }
    fun setMinutes(m: Int) { _state.value = _state.value.copy(minutes = m) }
    fun begin() { _state.value = _state.value.copy(step = Step.BEFORE) }

    fun rateBefore(r: Int) {
        val s = _state.value.copy(before = r, step = Step.PLAYING)
        _state.value = s
        job?.cancel()
        job = scope.launch {
            val ok = if (s.stimulus == Stimulus.MIX) {
                sessions.acquire(LabActivity.RELIEF_TEST, mixLabel(), muteMasker = false).also { if (it) sessions.startTimer(s.minutes) }
            } else startLab(LabActivity.RELIEF_TEST, s.minutes)
            if (!ok) { _state.value = _state.value.copy(step = Step.FAILED); return@launch }
            startedAt = SystemClock.elapsedRealtime()
            // the session ends by its timer, by "Stop", or when playback is stopped from anywhere
            while (sessions.state.value != null) delay(300)
            val played = ((SystemClock.elapsedRealtime() - startedAt) / 60_000L).toInt()
            _state.value = _state.value.copy(step = Step.AFTER, playedMinutes = played)
        }
    }

    fun stopEarly() = sessions.stop()

    fun rateAfter(r: Int) {
        val ends = SystemClock.elapsedRealtime() + LATER_DELAY_MS
        _state.value = _state.value.copy(after = r, step = Step.WAITING, waitEndsAt = ends)
        job?.cancel()
        job = scope.launch {
            while (SystemClock.elapsedRealtime() < ends) delay(500)
            _state.value = _state.value.copy(step = Step.LATER)
        }
    }

    fun rateLater(r: Int?) {
        job?.cancel()
        val s = _state.value
        val label = if (s.stimulus == Stimulus.MIX) mixLabel() else labLabel()
        scope.launch {
            tracker.add(
                ResponseRecord.Relief(
                    System.currentTimeMillis(), label, s.playedMinutes, s.before ?: 0, s.after, r,
                    if (r != null) (LATER_DELAY_MS / 60_000L).toInt() else null,
                )
            )
        }
        _state.value = s.copy(step = Step.DONE)
    }

    fun reset() {
        job?.cancel()
        if (_state.value.step == Step.PLAYING) sessions.stop()
        _state.value = State(stimulus = _state.value.stimulus, minutes = _state.value.minutes)
    }

    companion object {
        const val LATER_DELAY_MS = 5 * 60_000L
        val MINUTE_OPTIONS = listOf(5, 10, 15)
    }
}

/**
 * RESIDUAL INHIBITION test.
 *
 * Literature (Feldmann 1971 and later work): a masker – here 1/3-octave narrow-band noise at the
 * estimated pitch – is played for 60 s at about 10 dB above the minimum masking level (MML); after it
 * stops, the listener reports whether the tinnitus is suppressed and for how long.
 *
 * LIMITATIONS: the MML is set by ear on uncalibrated headphones; the +10 dB step is capped by the −6 dB
 * Layer B limit (the UI says so when the cap applies). A 10-minute cap ends the wait.
 */
class RiTestFlow(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val tracker: LocalResponseTracker,
    private val sampleRate: Int,
) {
    enum class Step { INTRO, LEVEL, BEFORE, STIMULUS, AFTER, WAITING, DONE, FAILED }
    enum class Outcome { GONE, QUIETER, SAME, LOUDER }

    data class State(
        val step: Step = Step.INTRO,
        val level: Float = 0.25f,
        val extraDb: Int = 10,
        val capped: Boolean = false,
        val before: Int? = null,
        val after: Int? = null,
        val outcome: Outcome? = null,
        val stimulusEndsAt: Long? = null,
        val offsetAt: Long? = null,
        val riSeconds: Int? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var pitch = 4000.0
    private var job: Job? = null

    fun setExtraDb(db: Int) { _state.value = _state.value.copy(extraDb = db) }

    fun begin(pitchHz: Double, label: String) {
        pitch = pitchHz
        job?.cancel()
        job = scope.launch {
            if (!sessions.acquire(LabActivity.RI_TEST, label, muteMasker = true)) {
                _state.value = _state.value.copy(step = Step.FAILED); return@launch
            }
            val b = PublishedBands.mostNarrowband(pitch)
            sessions.fadeIn(BandNoiseVoice(sampleRate, b.centerHz, b.widthOctaves), SessionManager.gainFor(_state.value.level))
            _state.value = _state.value.copy(step = Step.LEVEL)
        }
    }

    /** While setting the level: adjust until the tinnitus is just covered (≈ MML). */
    fun setLevel(v: Float) {
        _state.value = _state.value.copy(level = v)
        sessions.setGain(SessionManager.gainFor(v))
    }

    fun confirmLevel() {
        sessions.setGain(0f)
        _state.value = _state.value.copy(step = Step.BEFORE)
    }

    fun rateBefore(r: Int) {
        val s = _state.value
        val wanted = SessionManager.gainFor(s.level) * 10f.pow(s.extraDb / 20f)
        val capped = wanted > MixerCore.LAYER_B_MAX
        val ends = SystemClock.elapsedRealtime() + STIMULUS_MS
        _state.value = s.copy(before = r, step = Step.STIMULUS, capped = capped, stimulusEndsAt = ends)
        sessions.setGain(wanted.coerceAtMost(MixerCore.LAYER_B_MAX))
        job?.cancel()
        job = scope.launch {
            while (SystemClock.elapsedRealtime() < ends) {
                if (sessions.state.value == null) { _state.value = _state.value.copy(step = Step.FAILED); return@launch }
                delay(200)
            }
            // abrupt-but-click-free offset (the mixer smooths it over ~50 ms): RI is timed from here
            sessions.setGain(0f)
            _state.value = _state.value.copy(step = Step.AFTER, offsetAt = SystemClock.elapsedRealtime())
        }
    }

    fun rateAfter(r: Int, outcome: Outcome) {
        val s = _state.value.copy(after = r, outcome = outcome)
        if (outcome == Outcome.GONE || outcome == Outcome.QUIETER) {
            _state.value = s.copy(step = Step.WAITING)
            job?.cancel()
            job = scope.launch {
                delay(MAX_WAIT_MS)
                if (_state.value.step == Step.WAITING) save(_state.value, null)
            }
        } else save(s, 0)
    }

    /** "My tinnitus is back to its usual level." */
    fun returned() {
        val s = _state.value
        val off = s.offsetAt ?: return
        job?.cancel()
        save(s, ((SystemClock.elapsedRealtime() - off) / 1000).toInt())
    }

    private fun save(s: State, riSeconds: Int?) {
        sessions.stop(300)
        _state.value = s.copy(step = Step.DONE, riSeconds = riSeconds)
        scope.launch {
            tracker.add(
                ResponseRecord.Ri(
                    System.currentTimeMillis(), pitch, (STIMULUS_MS / 1000).toInt(), s.extraDb,
                    s.before ?: 0, s.after, s.outcome?.name?.lowercase() ?: "", riSeconds,
                )
            )
        }
    }

    fun reset() {
        job?.cancel()
        if (sessions.state.value?.activity == LabActivity.RI_TEST) sessions.stop(300)
        _state.value = State(level = _state.value.level, extraDb = _state.value.extraDb)
    }

    companion object {
        const val STIMULUS_MS = 60_000L
        const val MAX_WAIT_MS = 10 * 60_000L
        val EXTRA_DB_OPTIONS = listOf(0, 5, 10)
    }
}
