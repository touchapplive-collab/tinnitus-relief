package com.tinnitusrelief.app.engine

import android.util.Log
import com.tinnitusrelief.app.audio.AudioEngine
import com.tinnitusrelief.app.audio.GainParam
import com.tinnitusrelief.app.audio.MixerCore
import com.tinnitusrelief.app.data.SoundLibrary
import com.tinnitusrelief.app.dsp.MixEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layer A: the everyday relief masker. Up to three looping sounds, each with its own volume,
 * mixed under one master volume. Sounds are loaded off the main thread and crossfaded in.
 */
class MaskerEngine(
    private val scope: CoroutineScope,
    private val engine: AudioEngine,
    private val library: SoundLibrary,
    private val onLoadError: (soundId: String) -> Unit,
) {
    private val _mix = MutableStateFlow<List<MixEntry>>(emptyList())
    val mix: StateFlow<List<MixEntry>> = _mix

    private val _loading = MutableStateFlow<Set<String>>(emptySet())
    val loading: StateFlow<Set<String>> = _loading

    private val params = HashMap<String, GainParam>()

    /** Replaces the whole mix (used on start-up and when loading a preset). */
    fun applyMix(entries: List<MixEntry>) {
        val next = entries.distinctBy { it.soundId }.take(MixerCore.MAX_MASKER_CHANNELS)
        val old = _mix.value.associateBy { it.soundId }
        _mix.value = next
        for (id in old.keys - next.map { it.soundId }.toSet()) {
            engine.mixer.removeMaskerChannel(id)
            params.remove(id)
        }
        for (e in next) {
            if (e.soundId in old) params[e.soundId]?.target = MixerCore.sliderToGain(e.volume)
            else load(e)
        }
    }

    /** Adds or removes a sound. Returns false when the mix is already full. */
    fun toggle(soundId: String, defaultVolume: Float = 0.8f): Boolean {
        val cur = _mix.value
        return if (cur.any { it.soundId == soundId }) {
            applyMix(cur.filterNot { it.soundId == soundId }); true
        } else if (cur.size >= MixerCore.MAX_MASKER_CHANNELS) {
            false
        } else {
            applyMix(cur + MixEntry(soundId, defaultVolume)); true
        }
    }

    /** Makes [soundId] the only sound (a quick tap on a sound). */
    fun solo(soundId: String) {
        val keep = _mix.value.firstOrNull { it.soundId == soundId } ?: MixEntry(soundId, 0.8f)
        applyMix(listOf(keep))
    }

    /** Swaps one sound for another (e.g. Original ↔ personalised) keeping its volume; crossfades smoothly. */
    fun replace(fromId: String, toId: String) {
        if (fromId == toId) return
        val cur = _mix.value
        if (cur.none { it.soundId == fromId }) return
        applyMix(cur.filterNot { it.soundId == toId }.map { if (it.soundId == fromId) it.copy(soundId = toId) else it })
    }

    fun setVolume(soundId: String, v: Float) {
        _mix.value = _mix.value.map { if (it.soundId == soundId) it.copy(volume = v) else it }
        params[soundId]?.target = MixerCore.sliderToGain(v)
    }

    /** Re-decodes a sound that changed on disk (e.g. the user replaced the shower recording). */
    fun reload(soundId: String) {
        _mix.value.firstOrNull { it.soundId == soundId }?.let { load(it) }
    }

    /** Drops sounds that no longer exist in the library (e.g. a deleted import). */
    fun prune() {
        val ids = library.sounds.value.map { it.id }.toSet()
        if (_mix.value.any { it.soundId !in ids }) applyMix(_mix.value.filter { it.soundId in ids })
    }

    private fun load(entry: MixEntry) {
        val id = entry.soundId
        val param = params.getOrPut(id) { GainParam(MixerCore.sliderToGain(entry.volume)) }
        val sound = library.find(id) ?: run { drop(id); return }
        _loading.value = _loading.value + id
        scope.launch {
            try {
                val voice = withContext(Dispatchers.Default) { library.createVoice(sound, engine.sampleRate) }
                if (_mix.value.any { it.soundId == id }) engine.mixer.setMaskerChannel(id, voice, param)
            } catch (e: Exception) {
                Log.e("MaskerEngine", "Could not load $id", e)
                drop(id)
                onLoadError(id)
            } finally {
                _loading.value = _loading.value - id
            }
        }
    }

    private fun drop(id: String) {
        _mix.value = _mix.value.filterNot { it.soundId == id }
        params.remove(id)
    }
}
