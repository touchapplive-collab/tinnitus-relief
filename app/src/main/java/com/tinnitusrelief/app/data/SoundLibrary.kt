package com.tinnitusrelief.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.audio.NoiseColor
import com.tinnitusrelief.app.audio.NoiseVoice
import com.tinnitusrelief.app.audio.PcmDecoder
import com.tinnitusrelief.app.audio.PcmLoopVoice
import com.tinnitusrelief.app.audio.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException

enum class SoundKind { BUILT_IN, GENERATED, IMPORTED }

data class Sound(
    val id: String,
    val name: String,
    val kind: SoundKind,
    /** For the shower: true when the user replaced the bundled recording with their own. */
    val customized: Boolean = false,
)

/**
 * The offline sound library. Everything lives inside the app's private storage:
 *  - the bundled shower recording (res/raw/shower.ogg), optionally replaced by the user's own file
 *  - noises generated in real time (no files)
 *  - files the user imported, copied to filesDir/sounds/imported
 */
class SoundLibrary(private val context: Context) {

    private val dir = File(context.filesDir, "sounds").apply { mkdirs() }
    private val importDir = File(dir, "imported").apply { mkdirs() }
    private val customShower = File(dir, "shower_custom")

    private val _sounds = MutableStateFlow<List<Sound>>(emptyList())
    val sounds: StateFlow<List<Sound>> = _sounds

    fun refresh() {
        val list = mutableListOf(
            Sound(SHOWER, context.getString(R.string.sound_shower), SoundKind.BUILT_IN, customShower.exists()),
            Sound(WHITE, context.getString(R.string.sound_white), SoundKind.GENERATED),
            Sound(PINK, context.getString(R.string.sound_pink), SoundKind.GENERATED),
            Sound(BROWN, context.getString(R.string.sound_brown), SoundKind.GENERATED),
        )
        importDir.listFiles()?.sortedBy { it.lastModified() }?.forEach {
            list += Sound(FILE_PREFIX + it.name, it.nameWithoutExtension, SoundKind.IMPORTED)
        }
        _sounds.value = list
    }

    fun find(id: String?): Sound? = _sounds.value.firstOrNull { it.id == id }

    val default: Sound get() = _sounds.value.first()

    /** Builds the real-time voice for a sound. Call off the main thread: decoding can take a moment. */
    fun createVoice(sound: Sound, outputRate: Int): Voice = when (sound.id) {
        WHITE -> NoiseVoice(NoiseColor.WHITE)
        PINK -> NoiseVoice(NoiseColor.PINK)
        BROWN -> NoiseVoice(NoiseColor.BROWN)
        SHOWER -> {
            val pcm = if (customShower.exists()) PcmDecoder.decodeFile(customShower)
            else PcmDecoder.decodeRaw(context, R.raw.shower)
            val seamless = PcmDecoder.makeSeamless(pcm)
            PcmLoopVoice(seamless.data, seamless.sampleRate, outputRate)
        }
        else -> {
            val f = File(importDir, sound.id.removePrefix(FILE_PREFIX))
            if (!f.exists()) throw IOException("File missing")
            val seamless = PcmDecoder.makeSeamless(PcmDecoder.decodeFile(f))
            PcmLoopVoice(seamless.data, seamless.sampleRate, outputRate)
        }
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
        refresh()
    }

    fun restoreShower() {
        customShower.delete()
        refresh()
    }

    fun delete(sound: Sound) {
        if (sound.kind != SoundKind.IMPORTED) return
        File(importDir, sound.id.removePrefix(FILE_PREFIX)).delete()
        refresh()
    }

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
    }
}
