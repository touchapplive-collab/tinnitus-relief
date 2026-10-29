package com.tinnitusrelief.app.engine

import android.os.SystemClock
import com.tinnitusrelief.app.audio.AudioEngine
import com.tinnitusrelief.app.audio.MixerCore
import com.tinnitusrelief.app.audio.Voice
import com.tinnitusrelief.app.dsp.SessionLimits
import com.tinnitusrelief.app.dsp.SessionTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Start/stop of the shared transport, provided by the UI's MediaController. */
interface Transport {
    fun play()
    fun pause()
}

enum class LabActivity { SESSION, MATCHING, RI_TEST, RELIEF_TEST, PERSONALIZE, EFFECT, RIPPLE }

data class LabState(val activity: LabActivity, val label: String, val totalMs: Long, val endsAtElapsed: Long?)

/**
 * Owns Layer B (the experimental layer) and its safety rules:
 *  - only one Lab activity at a time;
 *  - timed sessions always end (limit 1–60 min, see [SessionLimits]) with a fade-out;
 *  - Layer B fades in over 3 s and out over 3 s; it is capped at −6 dB by the mixer;
 *  - if playback stops for any reason (user, call, unplugged headphones, sleep timer) Layer B is cleared,
 *    so an experimental sound never resumes on its own.
 */
class SessionManager(
    private val scope: CoroutineScope,
    private val engine: AudioEngine,
    private val isPlaying: StateFlow<Boolean>,
    private val transport: () -> Transport?,
    private val requestPause: (fadeMs: Int) -> Unit,
) {
    private val _state = MutableStateFlow<LabState?>(null)
    val state: StateFlow<LabState?> = _state

    private var startedTransport = false
    private var timerJob: Job? = null
    private var rampJob: Job? = null
    private var stopJob: Job? = null
    private var currentGain = 0f

    /**
     * Claims the audio path for a Lab activity: optionally mutes the masker and starts playback if needed.
     * Returns false when playback could not start (e.g. audio focus refused or app not connected).
     */
    suspend fun acquire(activity: LabActivity, label: String, muteMasker: Boolean): Boolean {
        if (_state.value != null) stopNow()
        engine.mixer.setMaskerEnabled(!muteMasker)
        if (!isPlaying.value) {
            val t = transport() ?: run { engine.mixer.setMaskerEnabled(true); return false }
            startedTransport = true
            t.play()
            val ok = withTimeoutOrNull(2500) { isPlaying.first { it } } != null
            if (!ok) { startedTransport = false; engine.mixer.setMaskerEnabled(true); return false }
            delay(TRANSPORT_FADE_MS) // let the transport fade-in finish before precise stimuli
        } else startedTransport = false
        _state.value = LabState(activity, label, 0, null)
        return true
    }

    /** Sets the Layer B voice immediately at [gain] (used for short test tones whose level is in the voice). */
    fun setStimulus(voice: Voice?, gain: Float, instant: Boolean = false) {
        rampJob?.cancel()
        currentGain = gain
        engine.mixer.setLayerB(voice, gain, instant)
    }

    /** Smoothly changes Layer B gain (user moving a slider). */
    fun setGain(gain: Float) {
        rampJob?.cancel()
        currentGain = gain
        engine.mixer.setLayerBGain(gain)
    }

    /** Fades a new stimulus in over [SessionLimits.FADE_IN_MS]. */
    fun fadeIn(voice: Voice, gain: Float) {
        engine.mixer.setLayerB(voice, 0f)
        ramp(0f, gain, SessionLimits.FADE_IN_MS)
    }

    /** Starts a timed session that always ends by itself. */
    fun startTimer(minutes: Int) {
        val t = SessionTimer(minutes, SystemClock.elapsedRealtime())
        _state.value = _state.value?.copy(totalMs = t.limitMs, endsAtElapsed = SystemClock.elapsedRealtime() + t.limitMs)
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive && !t.expired(SystemClock.elapsedRealtime())) delay(500)
            if (isActive) stop()
        }
    }

    /** Ends the current Lab activity with a fade-out. */
    fun stop(fadeMs: Int = SessionLimits.FADE_OUT_MS) {
        if (_state.value == null || stopJob?.isActive == true) return
        timerJob?.cancel()
        rampJob?.cancel()
        val from = currentGain
        stopJob = scope.launch {
            val steps = (fadeMs / 50).coerceAtLeast(1)
            for (i in 1..steps) {
                currentGain = from * (1f - i.toFloat() / steps)
                engine.mixer.setLayerBGain(currentGain)
                delay(50)
            }
            finish()
        }
    }

    /** Immediate stop (no fade) – used when switching activities. */
    fun stopNow() {
        timerJob?.cancel(); rampJob?.cancel(); stopJob?.cancel()
        engine.mixer.setLayerB(null, 0f)
        currentGain = 0f
        engine.mixer.setMaskerEnabled(true)
        _state.value = null
    }

    /** Playback stopped from anywhere: clear the experimental layer so it can never auto-resume. */
    fun onTransportStopped() {
        if (_state.value == null) return
        startedTransport = false
        stopNow()
    }

    private fun finish() {
        engine.mixer.setLayerB(null, 0f)
        currentGain = 0f
        engine.mixer.setMaskerEnabled(true)
        _state.value = null
        if (startedTransport) {
            startedTransport = false
            requestPause(400)
        }
    }

    private fun ramp(from: Float, to: Float, ms: Int) {
        rampJob?.cancel()
        rampJob = scope.launch {
            val steps = (ms / 50).coerceAtLeast(1)
            for (i in 1..steps) {
                currentGain = from + (to - from) * i / steps
                engine.mixer.setLayerBGain(currentGain)
                delay(50)
            }
        }
    }

    companion object {
        private const val TRANSPORT_FADE_MS = 1600L
        /** Converts the Lab volume slider into the Layer B gain (dB taper; mixer caps at −6 dB). */
        fun gainFor(slider: Float): Float = MixerCore.sliderToGain(slider).coerceAtMost(MixerCore.LAYER_B_MAX)
    }
}
