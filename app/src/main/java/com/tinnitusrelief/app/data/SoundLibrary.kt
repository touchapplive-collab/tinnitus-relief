package com.tinnitusrelief.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.audio.NoiseColor
import com.tinnitusrelief.app.audio.NoiseVoice
import com.tinnitusrelief.app.audio.Pcm
import com.tinnitusrelief.app.audio.Seamless
import com.tinnitusrelief.app.audio.PcmDecoder
import com.tinnitusrelief.app.audio.PcmLoopVoice
import com.tinnitusrelief.app.audio.Voice
import com.tinnitusrelief.app.dsp.EqProfile
import com.tinnitusrelief.app.dsp.EqVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException

enum class SoundKind { BUILT_IN, GENERATED, IMPORTED, PERSONALIZED }

data class Sound(
    val id: String,
    val name: String,
    val kind: SoundKind,
    /** For the shower: true when the user replaced the bundled recording with their own. */
    val customized: Boolean = false,
    /** For a personalised sound: the original it is based on, and its stored balance. */
    val baseId: String? = null,
    val profile: EqProfile? = null,
) {
    /** The original this sound comes from (itself, for an original). */
    val rootId: String get() = baseId ?: id
}

/**
 * The offline sound library. Everything lives inside the app's private storage:
 *  - the bundled shower recording (res/raw/shower.ogg), optionally replaced by the user's own file
 *  - six bundled nature loops (rain, heavy rain, ocean, river, waterfall, fan)
 *  - noises generated in real time (no files)
 *  - files the user imported, copied to filesDir/sounds/imported
 */
class SoundLibrary(private val context: Context) {

    private val dir = File(context.filesDir, "sounds").apply { mkdirs() }
    private val importDir = File(dir, "imported").apply { mkdirs() }
    private val customShower = File(dir, "shower_custom")
    /** Personalised sounds: one line per sound (id, base id, name, balance). No audio is duplicated. */
    private val personalizedFile = File(dir, "personalized.tsv")

    private data class Personal(val id: String, val baseId: String, val name: String, val profile: EqProfile)

    private fun readPersonal(): List<Personal> = runCatching {
        if (!personalizedFile.exists()) emptyList() else personalizedFile.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 4) null else EqProfile.decode(p[3])?.let { Personal(p[0], p[1], p[2], it) }
        }
    }.getOrDefault(emptyList())

    private fun writePersonal(list: List<Personal>) {
        val tmp = File(dir, "personalized.tmp")
        tmp.writeText(list.joinToString("\n") { "${it.id}\t${it.baseId}\t${it.name.replace('\t', ' ').replace('\n', ' ')}\t${it.profile.encode()}" })
        if (!tmp.renameTo(personalizedFile)) { personalizedFile.delete(); tmp.renameTo(personalizedFile) }
    }

    private val _sounds = MutableStateFlow<List<Sound>>(emptyList())
    val sounds: StateFlow<List<Sound>> = _sounds

    fun refresh() {
        val list = mutableListOf(
            Sound(SHOWER, context.getString(R.string.sound_shower), SoundKind.BUILT_IN, customShower.exists()),
        )
        for ((id, name) in NATURE) list += Sound(id, context.getString(name), SoundKind.BUILT_IN)
        list += Sound(WHITE, context.getString(R.string.sound_white), SoundKind.GENERATED)
        list += Sound(PINK, context.getString(R.string.sound_pink), SoundKind.GENERATED)
        list += Sound(BROWN, context.getString(R.string.sound_brown), SoundKind.GENERATED)
        importDir.listFiles()?.sortedBy { it.lastModified() }?.forEach {
            list += Sound(FILE_PREFIX + it.name, it.nameWithoutExtension, SoundKind.IMPORTED)
        }
        // personalised versions are listed right after their original; orphans (deleted original) are dropped
        val personal = readPersonal()
        val out = ArrayList<Sound>()
        for (s in list) {
            out += s
            for (p in personal.filter { it.baseId == s.id }) out += Sound(p.id, p.name, SoundKind.PERSONALIZED, baseId = s.id, profile = p.profile)
        }
        _sounds.value = out
    }

    fun find(id: String?): Sound? = _sounds.value.firstOrNull { it.id == id }

    val default: Sound get() = _sounds.value.first()

    /** Small cache of decoded loops; the PCM is read-only, so several voices can share one buffer. */
    private val pcmCache = object : LinkedHashMap<String, Pcm>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pcm>?) = size > 4
    }

    private fun loop(id: String, decode: () -> Pcm): Pcm {
        synchronized(pcmCache) { pcmCache[id]?.let { return it } }
        val pcm = Seamless.makeSeamless(decode())
        synchronized(pcmCache) { pcmCache[id] = pcm }
        return pcm
    }

    private fun invalidate(id: String) = synchronized(pcmCache) { pcmCache.remove(id) }

    /** Builds a fresh real-time voice for a sound. Call off the main thread: decoding can take a moment. */
    fun createVoice(sound: Sound, outputRate: Int): Voice {
        if (sound.kind == SoundKind.PERSONALIZED) {
            val base = find(sound.baseId) ?: throw IOException("Original missing")
            return EqVoice(createVoice(base, outputRate), outputRate, sound.profile ?: EqProfile.FLAT)
        }
        val pcm = when (sound.id) {
            WHITE -> return NoiseVoice(NoiseColor.WHITE)
            PINK -> return NoiseVoice(NoiseColor.PINK)
            BROWN -> return NoiseVoice(NoiseColor.BROWN)
            SHOWER -> loop(SHOWER) {
                if (customShower.exists()) PcmDecoder.decodeFile(customShower) else PcmDecoder.decodeRaw(context, R.raw.shower)
            }
            in RAW -> loop(sound.id) { PcmDecoder.decodeRaw(context, RAW.getValue(sound.id)) }
            else -> {
                val f = File(importDir, sound.id.removePrefix(FILE_PREFIX))
                if (!f.exists()) throw IOException("File missing")
                loop(sound.id) { PcmDecoder.decodeFile(f) }
            }
        }
        return PcmLoopVoice(pcm.data, pcm.sampleRate, outputRate)
    }

    /** Copies a picked file into private storage and returns the new sound. Validates by decoding. */
    fun import(uri: Uri): Sound {
        val name = uniqueName(displayName(uri) ?: "Sound")
        val target = File(importDir, name)
        copy(uri, target)
        try {
            PcmDecoder.decodeFile(target)
        } catch (e: Exception) {
            target.delete()
            throw IOException("Unsupported audio file", e)
        }
        refresh()
        return find(FILE_PREFIX + name)!!
    }

    /** Replaces the shower recording with the user's own file (validated before it is kept). */
    fun replaceShower(uri: Uri) {
        val tmp = File(dir, "shower_custom.tmp")
        copy(uri, tmp)
        try {
            PcmDecoder.decodeFile(tmp)
        } catch (e: Exception) {
            tmp.delete()
            throw IOException("Unsupported audio file", e)
        }
        customShower.delete()
        tmp.renameTo(customShower)
        invalidate(SHOWER)
        refresh()
    }

    fun restoreShower() {
        customShower.delete()
        invalidate(SHOWER)
        refresh()
    }

    fun delete(sound: Sound) {
        when (sound.kind) {
            SoundKind.PERSONALIZED -> writePersonal(readPersonal().filterNot { it.id == sound.id })
            SoundKind.IMPORTED -> {
                File(importDir, sound.id.removePrefix(FILE_PREFIX)).delete()
                invalidate(sound.id)
                writePersonal(readPersonal().filterNot { it.baseId == sound.id })
            }
            else -> return
        }
        refresh()
    }

    /** Saves a personalised version of [baseId] and returns it. Only the balance is stored. */
    fun savePersonalized(baseId: String, name: String, profile: EqProfile): Sound {
        val id = "personal:" + java.util.UUID.randomUUID().toString().take(8)
        writePersonal(readPersonal() + Personal(id, baseId, name, profile))
        refresh()
        return find(id)!!
    }

    /** Personalised versions of [baseId], oldest first. */
    fun variantsOf(baseId: String): List<Sound> = _sounds.value.filter { it.baseId == baseId }

    private fun copy(uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
        input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun uniqueName(raw: String): String {
        val clean = raw.replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_").take(80).ifBlank { "Sound" }
        var name = clean
        var i = 2
        while (File(importDir, name).exists()) {
            val dot = clean.lastIndexOf('.')
            name = if (dot > 0) "${clean.substring(0, dot)} ($i)${clean.substring(dot)}" else "$clean ($i)"
            i++
        }
        return name
    }

    companion object {
        const val SHOWER = "builtin:shower"
        const val WHITE = "noise:white"
        const val PINK = "noise:pink"
        const val BROWN = "noise:brown"
        const val FILE_PREFIX = "file:"

        /** Bundled nature loops (synthesised offline, see tools/generate_nature.py). */
        private val NATURE = listOf(
            "builtin:rain_gentle" to R.string.sound_rain_gentle,
            "builtin:rain_heavy" to R.string.sound_rain_heavy,
            "builtin:ocean" to R.string.sound_ocean,
            "builtin:river" to R.string.sound_river,
            "builtin:waterfall" to R.string.sound_waterfall,
            "builtin:fan" to R.string.sound_fan,
        )
        private val RAW = mapOf(
            "builtin:rain_gentle" to R.raw.rain_gentle,
            "builtin:rain_heavy" to R.raw.rain_heavy,
            "builtin:ocean" to R.raw.ocean,
            "builtin:river" to R.raw.river,
            "builtin:waterfall" to R.raw.waterfall,
            "builtin:fan" to R.raw.fan,
        )
    }
}
