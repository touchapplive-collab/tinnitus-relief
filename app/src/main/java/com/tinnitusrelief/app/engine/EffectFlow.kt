package com.tinnitusrelief.app.engine

import android.os.SystemClock
import com.tinnitusrelief.app.audio.AudioEngine
import com.tinnitusrelief.app.audio.MixerCore
import com.tinnitusrelief.app.data.SoundLibrary
import com.tinnitusrelief.app.dsp.EffectSummary
import com.tinnitusrelief.app.dsp.ResponseRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Original vs personalised, over several sessions.
 *
 * Each session plays either the original or the personalised version for a set time. The app chooses
 * which (balanced, random order) and does not say which while it plays, to reduce preference bias.
 * Optional ratings: before, while playing (masking), right after it stops, and comfort. A summary per
 * version is shown in History. Temporary masking is reported as such, never as lasting improvement.
 */
class EffectFlow(
    private val scope: CoroutineScope,
    private val engine: AudioEngine,
    private val library: SoundLibrary,
    private val sessions: SessionManager,
    private val tracker: LocalResponseTracker,
    private val reliefVolume: () -> Float,
    private val testLabel: () -> String,
) {
    enum class Step { SETUP, BEFORE, PLAYING, AFTER, COMFORT, DONE, FAILED }

    data class State(
        val step: Step = Step.SETUP,
        val presetId: String? = null,
        val minutes: Int = 10,
        val before: Int? = null,
        val during: Int? = null,
        val after: Int? = null,
        val variant: String? = null,
        val playedMinutes: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun setPreset(id: String) { _state.value = _state.value.copy(presetId = id) }
    fun setMinutes(m: Int) { _state.value = _state.value.copy(minutes = m) }

    fun begin() {
        if (_state.value.presetId == null) return
        _state.value = _state.value.copy(step = Step.BEFORE)
    }

    /** [r] = null when the user skips the rating. */
    fun rateBefore(r: Int?) {
        val s = _state.value.copy(before = r, step = Step.PLAYING)
        _state.value = s
        val preset = library.find(s.presetId) ?: run { _state.value = s.copy(step = Step.FAILED); return }
        val original = library.find(preset.rootId) ?: run { _state.value = s.copy(step = Step.FAILED); return }
        val variant = EffectSummary.nextVariant(tracker.records.value, preset.name, java.util.Random())
        _state.value = s.copy(variant = variant)
        job?.cancel()
        job = scope.launch {
            val ok = sessions.acquire(LabActivity.EFFECT, testLabel(), muteMasker = true)
            if (!ok) { _state.value = _state.value.copy(step = Step.FAILED); return@launch }
            val sound = if (variant == EffectSummary.PERSONALIZED) preset else original
            val voice = withContext(Dispatchers.Default) { library.createVoice(sound, engine.sampleRate) }
            sessions.fadeIn(voice, MixerCore.sliderToGain(reliefVolume()).coerceAtMost(MixerCore.LAYER_B_MAX))
            sessions.startTimer(s.minutes)
            val started = SystemClock.elapsedRealtime()
            while (sessions.state.value?.activity == LabActivity.EFFECT) delay(300)
            val played = ((SystemClock.elapsedRealtime() - started + 30_000) / 60_000L).toInt()
            _state.value = _state.value.copy(step = Step.AFTER, playedMinutes = played)
        }
    }

    fun rateDuring(r: Int) { _state.value = _state.value.copy(during = r) }

    fun stopEarly() = sessions.stop()

    fun rateAfter(r: Int?) { _state.value = _state.value.copy(after = r, step = Step.COMFORT) }

    fun rateComfort(r: Int?) {
        val s = _state.value
        val preset = library.find(s.presetId)
        scope.launch {
            tracker.add(
                ResponseRecord.Effect(
                    System.currentTimeMillis(), preset?.name ?: "", s.variant ?: "", s.playedMinutes,
                    s.before, s.during, s.after, r,
                )
            )
        }
        _state.value = s.copy(step = Step.DONE)
    }

    fun reset() {
        job?.cancel()
        if (sessions.state.value?.activity == LabActivity.EFFECT) sessions.stop()
        _state.value = State(presetId = _state.value.presetId, minutes = _state.value.minutes)
    }

    companion object {
        val MINUTE_OPTIONS = listOf(5, 10, 15, 20)
    }
}
