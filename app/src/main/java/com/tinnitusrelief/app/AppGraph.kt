package com.tinnitusrelief.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.tinnitusrelief.app.audio.AudioEngine
import com.tinnitusrelief.app.data.SettingsStore
import com.tinnitusrelief.app.data.Sound
import com.tinnitusrelief.app.data.SoundLibrary
import com.tinnitusrelief.app.dsp.AcousticMode
import com.tinnitusrelief.app.dsp.LabSettings
import com.tinnitusrelief.app.dsp.MixCodec
import com.tinnitusrelief.app.dsp.MixEntry
import com.tinnitusrelief.app.dsp.Preset
import com.tinnitusrelief.app.dsp.ResponseRecord
import com.tinnitusrelief.app.engine.AcousticProcessingEngine
import com.tinnitusrelief.app.engine.FrequencyMatchingEngine
import com.tinnitusrelief.app.engine.LabActivity
import com.tinnitusrelief.app.engine.LocalResponseTracker
import com.tinnitusrelief.app.engine.EffectFlow
import com.tinnitusrelief.app.engine.MaskerEngine
import com.tinnitusrelief.app.engine.PersonalizeFlow
import com.tinnitusrelief.app.engine.ReliefTestFlow
import com.tinnitusrelief.app.engine.RiTestFlow
import com.tinnitusrelief.app.engine.SessionManager
import com.tinnitusrelief.app.engine.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.tinnitusrelief.app.dsp.HistoryExport
import java.util.TimeZone
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide wiring of the five modules (MaskerEngine, FrequencyMatchingEngine,
 * AcousticProcessingEngine, SessionManager, LocalResponseTracker) plus settings.
 * The playback service owns the transport; this class owns everything else.
 */
class AppGraph(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = SettingsStore(context)

    val engine = AudioEngine()
    val library = SoundLibrary(context)
    val sounds: StateFlow<List<Sound>> = library.sounds

    /** Set by the activity while its MediaController is connected. */
    @Volatile var transport: Transport? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    /** Short user-facing messages (snackbar). */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val _pauseRequests = MutableSharedFlow<Int>(extraBufferCapacity = 2)
    /** The service collects these and pauses playback with the given fade (ms). */
    val pauseRequests: SharedFlow<Int> = _pauseRequests

    // ---------------- modules ----------------

    val masker = MaskerEngine(scope, engine, library) {
        messages.tryEmit(context.getString(R.string.msg_load_failed))
        persistMix()
    }
    val sessions = SessionManager(scope, engine, isPlaying, { transport }, { _pauseRequests.tryEmit(it) })
    val processing = AcousticProcessingEngine(library, engine.sampleRate)
    val matching = FrequencyMatchingEngine(sessions, engine.sampleRate)
    val tracker = LocalResponseTracker(context)
    val reliefTest = ReliefTestFlow(scope, sessions, tracker, ::startLabFor, { modeLabel(_lab.value.mode) }, ::mixTitle)
    val riTest = RiTestFlow(scope, sessions, tracker, engine.sampleRate)
    val personalize = PersonalizeFlow(
        scope, engine, library, sessions,
        reliefVolume = { _volume.value },
        loadSaved = { settings.snapshot()[SettingsStore.PERSONALIZE_SESSION] },
        storeSaved = { v -> if (v == null) settings.remove(SettingsStore.PERSONALIZE_SESSION) else settings.putString(SettingsStore.PERSONALIZE_SESSION, v) },
    )
    val effect = EffectFlow(scope, engine, library, sessions, tracker, { _volume.value }, { context.getString(R.string.effect_test_label) })

    // ---------------- relief state ----------------

    val mix: StateFlow<List<MixEntry>> = masker.mix
    val loadingSounds: StateFlow<Set<String>> = masker.loading

    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets

    private val _volume = MutableStateFlow(SettingsStore.DEFAULT_VOLUME)
    val volume: StateFlow<Float> = _volume

    private val _timerMinutes = MutableStateFlow(0)
    val timerMinutes: StateFlow<Int> = _timerMinutes

    private val _timerEndsAt = MutableStateFlow<Long?>(null)
    val timerEndsAt: StateFlow<Long?> = _timerEndsAt
    private var timerJob: Job? = null

    // ---------------- lab state ----------------

    private val _lab = MutableStateFlow(LabSettings())
    val lab: StateFlow<LabSettings> = _lab

    private val _pitch = MutableStateFlow<Double?>(null)
    /** The user's saved tinnitus pitch estimate (Hz), or null when none was saved yet. */
    val pitch: StateFlow<Double?> = _pitch

    private val _labAcknowledged = MutableStateFlow(false)
    val labAcknowledged: StateFlow<Boolean> = _labAcknowledged

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    init {
        scope.launch {
            val p = settings.snapshot()
            _volume.value = p[SettingsStore.VOLUME] ?: SettingsStore.DEFAULT_VOLUME
            engine.setMasterVolume(_volume.value)
            _timerMinutes.value = p[SettingsStore.TIMER] ?: 0
            _presets.value = MixCodec.decodePresets(p[SettingsStore.PRESETS])
            _lab.value = LabSettings.decode(p[SettingsStore.LAB])
            _pitch.value = p[SettingsStore.PITCH]?.toDouble()
            _labAcknowledged.value = p[SettingsStore.LAB_ACK] ?: false
            withContext(Dispatchers.IO) { library.refresh() }
            val saved = MixCodec.decode(p[SettingsStore.MIX]).filter { library.find(it.soundId) != null }
            masker.applyMix(saved.ifEmpty { listOf(MixEntry(SoundLibrary.SHOWER, 0.8f)) })
            tracker.load()
            _ready.value = true
        }
    }

    // ---------------- relief: mix ----------------

    fun mixTitle(): String {
        val names = mix.value.mapNotNull { library.find(it.soundId)?.name }
        return if (names.isEmpty()) context.getString(R.string.mix_empty) else names.joinToString(" + ")
    }

    fun toggleSound(id: String) {
        if (!masker.toggle(id)) messages.tryEmit(context.getString(R.string.msg_mix_full))
        persistMix()
    }

    fun soloSound(id: String) { masker.solo(id); persistMix() }

    fun setSoundVolume(id: String, v: Float) = masker.setVolume(id, v)

    fun commitMix() = persistMix()

    private fun persistMix() { scope.launch { settings.putString(SettingsStore.MIX, MixCodec.encode(mix.value)) } }

    fun savePreset(name: String) {
        val clean = name.trim().ifEmpty { context.getString(R.string.preset_default_name, _presets.value.size + 1) }
        val next = _presets.value.filterNot { it.name == clean } + Preset(clean, mix.value)
        _presets.value = next
        scope.launch { settings.putString(SettingsStore.PRESETS, MixCodec.encodePresets(next)) }
    }

    fun applyPreset(p: Preset) {
        masker.applyMix(p.mix.filter { library.find(it.soundId) != null })
        persistMix()
    }

    fun deletePreset(p: Preset) {
        val next = _presets.value - p
        _presets.value = next
        scope.launch { settings.putString(SettingsStore.PRESETS, MixCodec.encodePresets(next)) }
    }

    // ---------------- relief: personalised sounds ----------------

    /** The original and its personalised versions, when [soundId] has any (else empty). */
    fun variantsOf(soundId: String): List<Sound> {
        val root = library.find(soundId)?.rootId ?: return emptyList()
        val variants = library.variantsOf(root)
        if (variants.isEmpty()) return emptyList()
        return listOfNotNull(library.find(root)) + variants
    }

    /** Switches a sound in the mix to another version of it (Original ↔ personalised), instantly and smoothly. */
    fun switchVariant(fromId: String, toId: String) {
        masker.replace(fromId, toId)
        persistMix()
    }

    /** Default name for a new personalised version: "My Shower", "My Shower 2", … */
    fun newPersonalName(base: Sound): String {
        val first = context.getString(R.string.personal_name, base.name)
        val taken = library.variantsOf(base.id).map { it.name }.toSet()
        if (first !in taken) return first
        var i = 2
        while ("$first $i" in taken) i++
        return "$first $i"
    }

    /** Saves the personalisation result and puts it in the mix in place of the version that was there. */
    fun savePersonalized(name: String) = scope.launch {
        val base = personalize.state.value.base ?: return@launch
        val sound = personalize.saveResult(name.trim().ifEmpty { newPersonalName(base) }) ?: return@launch
        val inMix = mix.value.firstOrNull { library.find(it.soundId)?.rootId == base.id }
        if (inMix != null) masker.replace(inMix.soundId, sound.id) else if (!masker.toggle(sound.id)) masker.solo(sound.id)
        persistMix()
        messages.tryEmit(context.getString(R.string.msg_personal_saved, sound.name))
    }

    // ---------------- relief: volume & timer ----------------

    fun setVolume(v: Float) { _volume.value = v; engine.setMasterVolume(v) }

    fun commitVolume() { scope.launch { settings.putFloat(SettingsStore.VOLUME, _volume.value) } }

    fun setTimer(minutes: Int) {
        _timerMinutes.value = minutes
        scope.launch { settings.putInt(SettingsStore.TIMER, minutes) }
        if (_isPlaying.value) startTimer() else cancelTimer()
    }

    /** Called by the playback service whenever the transport changes. */
    fun onPlaybackChanged(playing: Boolean) {
        if (_isPlaying.value == playing) return
        _isPlaying.value = playing
        if (playing) startTimer() else {
            cancelTimer()
            sessions.onTransportStopped()
        }
    }

    private fun startTimer() {
        cancelTimer()
        val minutes = _timerMinutes.value
        if (minutes <= 0) return
        _timerEndsAt.value = SystemClock.elapsedRealtime() + minutes * 60_000L
        timerJob = scope.launch {
            delay(minutes * 60_000L)
            _timerEndsAt.value = null
            _pauseRequests.tryEmit(SLEEP_FADE_MS)
        }
    }

    private fun cancelTimer() { timerJob?.cancel(); timerJob = null; _timerEndsAt.value = null }

    // ---------------- library ----------------

    fun importSound(uri: Uri) = scope.launch {
        try {
            val sound = withContext(Dispatchers.IO) { library.import(uri) }
            if (!masker.toggle(sound.id)) masker.solo(sound.id)
            persistMix()
        } catch (e: Exception) {
            messages.tryEmit(context.getString(R.string.msg_import_failed))
        }
    }

    fun replaceShower(uri: Uri) = scope.launch {
        try {
            withContext(Dispatchers.IO) { library.replaceShower(uri) }
            masker.reload(SoundLibrary.SHOWER)
            messages.tryEmit(context.getString(R.string.msg_shower_replaced))
        } catch (e: Exception) {
            messages.tryEmit(context.getString(R.string.msg_import_failed))
        }
    }

    fun restoreShower() = scope.launch {
        withContext(Dispatchers.IO) { library.restoreShower() }
        masker.reload(SoundLibrary.SHOWER)
    }

    fun delete(sound: Sound) = scope.launch {
        val inMix = mix.value.any { it.soundId == sound.id }
        withContext(Dispatchers.IO) { library.delete(sound) }
        // a deleted personalised version in the mix is replaced by its original
        val base = sound.baseId
        if (inMix && base != null && library.find(base) != null) masker.replace(sound.id, base) else masker.prune()
        if (mix.value.isEmpty()) masker.applyMix(listOf(MixEntry(SoundLibrary.SHOWER, 0.8f)))
        persistMix()
    }

    // ---------------- lab ----------------

    fun acknowledgeLab() {
        _labAcknowledged.value = true
        scope.launch { settings.putBoolean(SettingsStore.LAB_ACK, true) }
    }

    fun updateLab(transform: (LabSettings) -> LabSettings) {
        val next = transform(_lab.value)
        _lab.value = next
        scope.launch { settings.putString(SettingsStore.LAB, next.encode()) }
        val st = sessions.state.value
        if (st != null && (st.activity == LabActivity.SESSION || st.activity == LabActivity.RELIEF_TEST) && processing.canUpdateLive(next)) {
            processing.updateLive(next, _pitch.value ?: return)
            sessions.setGain(SessionManager.gainFor(next.volume))
            engine.mixer.setMaskerEnabled(next.mixWithMasker)
        }
    }

    fun savePitch(hz: Double, method: String, loudnessSl: Double? = null) {
        _pitch.value = hz
        scope.launch {
            settings.putFloat(SettingsStore.PITCH, hz.toFloat())
            tracker.add(ResponseRecord.Pitch(System.currentTimeMillis(), hz, method, loudnessSl))
        }
    }

    /** Starts an experimental session with the current Lab settings. Requires a saved pitch. */
    fun startLabSession() = scope.launch {
        if (!startLabFor(LabActivity.SESSION, _lab.value.minutes)) messages.tryEmit(context.getString(R.string.msg_lab_failed))
    }

    private suspend fun startLabFor(activity: LabActivity, minutes: Int): Boolean {
        val s = _lab.value
        val pitchHz = _pitch.value ?: return false
        if (!_labAcknowledged.value) return false
        if (!sessions.acquire(activity, modeLabel(s.mode), muteMasker = !s.mixWithMasker)) return false
        return try {
            val voice = processing.build(s, pitchHz)
            sessions.fadeIn(voice, SessionManager.gainFor(s.volume))
            sessions.startTimer(minutes)
            true
        } catch (e: Exception) {
            sessions.stopNow()
            false
        }
    }

    fun stopLabSession() = sessions.stop()

    fun modeLabel(mode: AcousticMode): String = context.getString(
        when (mode) {
            AcousticMode.DFCRS_INSPIRED -> R.string.mode_dfcrs
            AcousticMode.NOTCHED -> R.string.mode_notched
            AcousticMode.HFEM -> R.string.mode_hfem
            AcousticMode.CUSTOM_FILTER -> R.string.mode_custom
            AcousticMode.NARROWBAND_NOISE -> R.string.mode_nbn
            AcousticMode.PURE_TONE -> R.string.mode_tone
            AcousticMode.AM_TONE -> R.string.mode_am
            AcousticMode.FM_TONE -> R.string.mode_fm
        }
    )

    // ---------------- tracking ----------------

    /**
     * Daily check-in (outcome D). [unmasked] = no sound was playing when the rating was given. A later
     * rating on the same day replaces the earlier one; the optional bother rating is kept if not given again.
     */
    fun addDaily(rating: Int, bother: Int? = null) = scope.launch {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val previous = tracker.records.value.lastOrNull { it is ResponseRecord.Daily && it.day == day } as ResponseRecord.Daily?
        tracker.add(ResponseRecord.Daily(System.currentTimeMillis(), day, rating, !_isPlaying.value, bother ?: previous?.bother))
    }

    /** Adds the optional "how much does it bother you" rating to today's check-in. */
    fun setDailyBother(bother: Int) = scope.launch {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val today = tracker.records.value.lastOrNull { it is ResponseRecord.Daily && it.day == day } as ResponseRecord.Daily? ?: return@launch
        tracker.replace(today.atMs, today.copy(bother = bother))
    }

    /** Stores how sure the user is about the most recent pitch estimate (1 not sure … 3 very sure). */
    fun setPitchConfidence(sure: Int) = scope.launch {
        val last = tracker.records.value.filterIsInstance<ResponseRecord.Pitch>().maxByOrNull { it.atMs } ?: return@launch
        tracker.replace(last.atMs, last.copy(sure = sure))
    }

    /** Pauses whatever is playing (used before an unmasked rating). */
    fun pausePlayback() { transport?.pause() }

    /** Writes the whole history as CSV to a document the user picked. */
    fun exportCsv(uri: Uri, onDone: (Boolean) -> Unit) = scope.launch {
        val ok = runCatching {
            val csv = HistoryExport.csv(tracker.records.value, TimeZone.getDefault())
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } ?: error("no stream")
            }
        }.isSuccess
        onDone(ok)
    }

    fun deleteRecord(atMs: Long) = scope.launch { tracker.delete(atMs) }

    fun clearRecords() = scope.launch { tracker.clear() }

    companion object {
        /** The sleep timer ends with a long, gentle fade instead of an abrupt stop. */
        const val SLEEP_FADE_MS = 8000
    }
}
