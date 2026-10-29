package com.tinnitusrelief.app.engine

import android.util.Log
import com.tinnitusrelief.app.audio.AudioEngine
import com.tinnitusrelief.app.audio.MixerCore
import com.tinnitusrelief.app.data.Sound
import com.tinnitusrelief.app.data.SoundLibrary
import com.tinnitusrelief.app.dsp.AbCompareVoice
import com.tinnitusrelief.app.dsp.EqMath
import com.tinnitusrelief.app.dsp.EqProfile
import com.tinnitusrelief.app.dsp.Personalizer
import com.tinnitusrelief.app.dsp.Spectrum
import com.tinnitusrelief.app.dsp.SpectrumAnalyzer
import com.tinnitusrelief.app.dsp.renderMono
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Find your perfect sound": personalises the balance of any Relief sound through simple A/B listening.
 *
 * Choose sound → analyse (FFT, on the device) → 5–8 comparisons → result → save.
 * Comparisons play on Layer B (masker muted) at the user's Relief volume, capped like every Lab sound.
 * Every version is loudness-matched, so the listener compares balance, not level.
 * The original is never changed: a saved result is only a stored balance applied while playing.
 */
class PersonalizeFlow(
    private val scope: CoroutineScope,
    private val engine: AudioEngine,
    private val library: SoundLibrary,
    private val sessions: SessionManager,
    private val reliefVolume: () -> Float,
    private val loadSaved: suspend () -> String?,
    private val storeSaved: suspend (String?) -> Unit,
) {
    enum class Step { ANALYZING, COMPARE, RESULT, FAILED }

    /** What the two big buttons play. */
    enum class Listening { CANDIDATES, ORIGINAL_VS_PERSONAL }

    data class State(
        val step: Step = Step.ANALYZING,
        val base: Sound? = null,
        val comparison: Personalizer.Comparison? = null,
        val canBack: Boolean = false,
        val heardA: Boolean = false,
        val heardB: Boolean = false,
        val result: EqProfile? = null,
        val noClearPreference: Boolean = false,
        val pair: Listening = Listening.CANDIDATES,
        val resumed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** −1 silent, 0 = A (or Original), 1 = B (or Personalised); polled by the UI with [progress]. */
    val playing: Int get() = if (installed()) voice?.selected ?: -1 else -1
    val progress: Float get() = if (installed()) voice?.progress ?: 0f else 0f

    private var spectrum: Spectrum? = null
    private var personalizer: Personalizer? = null
    private var start = DoubleArray(4)
    private var voice: AbCompareVoice? = null
    private var job: Job? = null

    /** Opens personalisation for [soundId] (an original or an earlier personalised version to refine). */
    fun open(soundId: String) {
        job?.cancel()
        closeAudio()
        val sound = library.find(soundId) ?: run { _state.value = State(Step.FAILED); return }
        val base = library.find(sound.rootId) ?: run { _state.value = State(Step.FAILED); return }
        _state.value = State(Step.ANALYZING, base)
        job = scope.launch {
            try {
                val spec = withContext(Dispatchers.Default) {
                    SpectrumAnalyzer.analyze(renderMono(library.createVoice(base, ANALYSIS_RATE), ANALYSIS_RATE, ANALYSIS_SECONDS), ANALYSIS_RATE)
                }
                spectrum = spec
                start = sound.profile?.gainsDb?.toDoubleArray() ?: DoubleArray(4)
                // resume an unfinished session for the same original (only when starting from the original)
                val saved = if (sound.profile == null) loadSaved()?.let { parseSaved(it, base.id) } else null
                personalizer = saved ?: Personalizer.create(
                    spec.adjustable(), System.nanoTime(), start, refine = sound.profile != null,
                )
                showComparison(resumed = saved != null)
            } catch (e: Exception) {
                Log.e("Personalize", "analysis failed", e)
                _state.value = State(Step.FAILED, base)
            }
        }
    }

    // ---------------- listening ----------------

    /** Plays A (0) or B (1); tapping the one that is playing stops it. */
    fun listen(which: Int) {
        if (playing == which) { voice?.stop(); return }
        scope.launch {
            if (!ensureAudio()) return@launch
            voice?.select(which)
            _state.value = _state.value.let { if (which == 0) it.copy(heardA = true) else it.copy(heardB = true) }
        }
    }

    fun stopListening() { voice?.stop() }

    // ---------------- answers ----------------

    fun answer(a: Personalizer.Answer) {
        val p = personalizer ?: return
        voice?.stop()
        p.answer(a)
        save()
        showComparison()
    }

    fun back() {
        val p = personalizer ?: return
        voice?.stop()
        p.back()
        showComparison()
    }

    /** Leaves the session; it can be resumed later from where it stopped. */
    fun finishLater() {
        save()
        close()
    }

    // ---------------- result ----------------

    fun comparePair(pair: Listening) {
        voice?.stop()
        _state.value = _state.value.copy(pair = pair, heardA = false, heardB = false)
        applyProfiles()
    }

    /** Continues from the current result with smaller steps. */
    fun refine() {
        val spec = spectrum ?: return
        val from = _state.value.result?.gainsDb?.toDoubleArray() ?: return
        voice?.stop()
        personalizer = Personalizer.create(spec.adjustable(), System.nanoTime(), from, refine = true)
        start = from
        showComparison()
    }

    /** Saves the result as a new personalised sound (original untouched) and returns it. */
    suspend fun saveResult(name: String): Sound? {
        val base = _state.value.base ?: return null
        val profile = _state.value.result ?: return null
        val sound = withContext(Dispatchers.IO) { library.savePersonalized(base.id, name, profile) }
        storeSaved(null)
        close()
        return sound
    }

    fun discard() {
        scope.launch { storeSaved(null) }
        close()
    }

    fun close() {
        job?.cancel()
        closeAudio()
    }

    // ---------------- internals ----------------

    private fun showComparison(resumed: Boolean = false) {
        val p = personalizer ?: return
        val spec = spectrum ?: return
        if (p.done) {
            val gains = p.gainsDb
            _state.value = _state.value.copy(
                step = Step.RESULT,
                comparison = null,
                result = EqMath.profile(spec, gains),
                noClearPreference = p.noClearPreference(start),
                canBack = p.canGoBack,
                pair = Listening.ORIGINAL_VS_PERSONAL,
                heardA = false, heardB = false,
            )
            scope.launch { storeSaved(null) }
        } else {
            _state.value = _state.value.copy(
                step = Step.COMPARE,
                comparison = p.comparison(),
                canBack = p.canGoBack,
                pair = Listening.CANDIDATES,
                heardA = false, heardB = false,
                resumed = resumed,
            )
        }
        applyProfiles()
    }

    private fun profiles(): Pair<EqProfile, EqProfile>? {
        val spec = spectrum ?: return null
        val s = _state.value
        return when (s.step) {
            Step.COMPARE -> s.comparison?.let { EqMath.profile(spec, it.a) to EqMath.profile(spec, it.b) }
            Step.RESULT -> s.result?.let { EqProfile.FLAT to it }
            else -> null
        }
    }

    private fun applyProfiles() {
        val (a, b) = profiles() ?: return
        voice?.setProfiles(a, b)
    }

    private fun installed(): Boolean = sessions.state.value?.activity == LabActivity.PERSONALIZE

    /** Claims the audio path on first use (and again after an interruption cleared it). */
    private suspend fun ensureAudio(): Boolean {
        if (installed() && voice != null) return true
        val base = _state.value.base ?: return false
        val (a, b) = profiles() ?: return false
        if (!sessions.acquire(LabActivity.PERSONALIZE, base.name, muteMasker = true)) return false
        val v = withContext(Dispatchers.Default) { AbCompareVoice(library.createVoice(base, engine.sampleRate), engine.sampleRate, a, b) }
        voice = v
        val gain = MixerCore.sliderToGain(reliefVolume()).coerceAtMost(MixerCore.LAYER_B_MAX)
        sessions.setStimulus(v, gain, instant = true)
        delay(30)
        return true
    }

    private fun closeAudio() {
        voice?.stop()
        if (installed()) sessions.stop(300)
        voice = null
    }

    private fun save() {
        val p = personalizer ?: return
        val base = _state.value.base ?: return
        if (p.done || start.any { it != 0.0 }) return // only fresh sessions from an original are resumable
        val text = base.id + "\t" + p.encode()
        scope.launch { storeSaved(text) }
    }

    private fun parseSaved(text: String, baseId: String): Personalizer? {
        val i = text.indexOf('\t')
        if (i <= 0 || text.substring(0, i) != baseId) return null
        return Personalizer.decode(text.substring(i + 1))?.takeIf { !it.done }
    }

    companion object {
        /** Analysis renders this much of the sound at a fixed rate (independent of the output device). */
        private const val ANALYSIS_RATE = 48000
        private const val ANALYSIS_SECONDS = 8.0
    }
}
