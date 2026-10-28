package com.tinnitusrelief.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.tinnitusrelief.app.audio.AudioEngine
import com.tinnitusrelief.app.data.SettingsStore
import com.tinnitusrelief.app.data.Sound
import com.tinnitusrelief.app.data.SoundLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide state shared by the UI and the playback service.
 * The service owns the transport (play/pause, audio focus, notification);
 * everything else (sound choice, volume, timer, library) lives here.
 */
class AppGraph(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val engine = AudioEngine()
    val library = SoundLibrary(context)
    private val settings = SettingsStore(context)

    val sounds: StateFlow<List<Sound>> = library.sounds

    private val _selected = MutableStateFlow<Sound?>(null)
    val selected: StateFlow<Sound?> = _selected

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _volume = MutableStateFlow(SettingsStore.DEFAULT_VOLUME)
    val volume: StateFlow<Float> = _volume

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _timerMinutes = MutableStateFlow(0)
    val timerMinutes: StateFlow<Int> = _timerMinutes

    /** elapsedRealtime at which the sleep timer fires, or null. */
    private val _timerEndsAt = MutableStateFlow<Long?>(null)
    val timerEndsAt: StateFlow<Long?> = _timerEndsAt

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    /** Short user-facing messages (shown as a snackbar). */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Requests for the service to pause, carrying the fade-out length in ms. */
    private val _pauseRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val pauseRequests: SharedFlow<Int> = _pauseRequests

    private var loadJob: Job? = null
    private var timerJob: Job? = null

    init {
        scope.launch {
            val saved = settings.load()
            _volume.value = saved.volume
            engine.setMasterVolume(saved.volume)
            _timerMinutes.value = saved.timerMinutes
            withContext(Dispatchers.IO) { library.refresh() }
            select(library.find(saved.soundId) ?: library.default, persist = false)
            _ready.value = true
        }
    }

    fun select(sound: Sound, persist: Boolean = true) {
        val previous = _selected.value
        _selected.value = sound
        loadJob?.cancel()
        loadJob = scope.launch {
            _loading.value = true
            try {
                val voice = withContext(Dispatchers.Default) { library.createVoice(sound, engine.sampleRate) }
                engine.setMaskerVoice(voice)
                if (persist) settings.saveSound(sound.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AppGraph", "Could not load ${sound.id}", e)
                messages.tryEmit(context.getString(R.string.msg_load_failed))
                if (previous != null && previous.id != sound.id) _selected.value = previous
            } finally {
                _loading.value = false
            }
        }
    }

    fun setVolume(v: Float) {
        _volume.value = v
        engine.setMasterVolume(v)
    }

    fun commitVolume() { scope.launch { settings.saveVolume(_volume.value) } }

    fun setTimer(minutes: Int) {
        _timerMinutes.value = minutes
        scope.launch { settings.saveTimer(minutes) }
        if (_isPlaying.value) startTimer() else cancelTimer()
    }

    /** Called by the playback service whenever the transport changes. */
    fun onPlaybackChanged(playing: Boolean) {
        if (_isPlaying.value == playing) return
        _isPlaying.value = playing
        if (playing) startTimer() else cancelTimer()
    }

    private fun startTimer() {
        cancelTimer()
        val minutes = _timerMinutes.value
        if (minutes <= 0) return
        val endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        _timerEndsAt.value = endsAt
        timerJob = scope.launch {
            delay(minutes * 60_000L)
            _timerEndsAt.value = null
            _pauseRequests.tryEmit(SLEEP_FADE_MS)
        }
    }

    private fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _timerEndsAt.value = null
    }

    fun importSound(uri: Uri) = scope.launch {
        try {
            val sound = withContext(Dispatchers.IO) { library.import(uri) }
            select(sound)
        } catch (e: Exception) {
            messages.tryEmit(context.getString(R.string.msg_import_failed))
        }
    }

    fun replaceShower(uri: Uri) = scope.launch {
        try {
            withContext(Dispatchers.IO) { library.replaceShower(uri) }
            library.find(SoundLibrary.SHOWER)?.let { select(it) }
            messages.tryEmit(context.getString(R.string.msg_shower_replaced))
        } catch (e: Exception) {
            messages.tryEmit(context.getString(R.string.msg_import_failed))
        }
    }

    fun restoreShower() = scope.launch {
        withContext(Dispatchers.IO) { library.restoreShower() }
        if (_selected.value?.id == SoundLibrary.SHOWER) library.find(SoundLibrary.SHOWER)?.let { select(it) }
    }

    fun delete(sound: Sound) = scope.launch {
        withContext(Dispatchers.IO) { library.delete(sound) }
        if (_selected.value?.id == sound.id) select(library.default)
    }

    companion object {
        /** The sleep timer ends with a long, gentle fade instead of an abrupt stop. */
        const val SLEEP_FADE_MS = 8000
    }
}
