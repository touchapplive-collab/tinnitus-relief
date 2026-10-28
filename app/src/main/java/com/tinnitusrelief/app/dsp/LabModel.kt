package com.tinnitusrelief.app.dsp

import kotlin.math.pow

/** How well a mode's parameters are backed by published sources. Shown in the UI next to every mode. */
enum class Evidence {
    /** Band placement and gain taken from the publications; filter shape/source still assumptions. */
    PUBLISHED_PARAMETERS,
    /** Generator with user-chosen parameters. No published therapeutic parameters are implied. */
    SIGNAL_GENERATOR,
}

/**
 * Acoustic Lab modes. All are EXPERIMENTAL, off by default and time-limited. None is presented as a
 * proven treatment. See docs/RESEARCH.md for what each is based on.
 */
enum class AcousticMode(val usesSource: Boolean, val evidence: Evidence) {
    /** Two 1/3-octave bands at Ft/√2 and √2·Ft raised by 10 dB (DFCRS publications). */
    DFCRS_INSPIRED(true, Evidence.PUBLISHED_PARAMETERS),
    /** One octave around the pitch removed (Okamoto et al. 2010, tailor-made notched music). */
    NOTCHED(true, Evidence.PUBLISHED_PARAMETERS),
    /** 6–9 kHz raised by 10 dB (MOST trial comparator; performed worse than unmodified music there). */
    HFEM(true, Evidence.PUBLISHED_PARAMETERS),
    /** User-defined band boost or cut around the pitch. */
    CUSTOM_FILTER(true, Evidence.SIGNAL_GENERATOR),
    /** 1/3-octave narrow-band noise centred on the pitch (MOST UM+NBN arm; level not published). */
    NARROWBAND_NOISE(false, Evidence.PUBLISHED_PARAMETERS),
    PURE_TONE(false, Evidence.SIGNAL_GENERATOR),
    AM_TONE(false, Evidence.SIGNAL_GENERATOR),
    FM_TONE(false, Evidence.SIGNAL_GENERATOR),
}

/**
 * Lab settings. Defaults that are NOT from publications are labelled "engineering default" and exist only
 * so the controls start somewhere quiet and neutral.
 */
data class LabSettings(
    val mode: AcousticMode = AcousticMode.DFCRS_INSPIRED,
    val sourceId: String = "noise:pink",
    /** Layer B volume slider 0..1 (dB taper; additionally capped at −6 dB by the mixer). Engineering default. */
    val volume: Float = 0.35f,
    val minutes: Int = SessionLimits.DEFAULT_MINUTES,
    val mixWithMasker: Boolean = false,
    /** Published default 10 dB (range 0–10 offered because one paper mentions 5–10 dB). */
    val bandGainDb: Double = 10.0,
    /** Edge slope of spectral bands. Engineering assumption (filters are not specified in the papers). */
    val transitionOctaves: Double = GainCurve.DEFAULT_TRANSITION_OCTAVES,
    val customOffsetOctaves: Double = 0.0,
    val customWidthOctaves: Double = 0.5,
    val customGainDb: Double = -20.0,
    /** Engineering defaults for the generators; not therapeutic values. */
    val amRateHz: Double = 4.0,
    val amDepth: Float = 0.5f,
    val fmRateHz: Double = 4.0,
    val fmCents: Double = 50.0,
) {
    fun bands(pitchHz: Double): List<Band> = when (mode) {
        AcousticMode.DFCRS_INSPIRED -> PublishedBands.dfcrs(pitchHz, 3, bandGainDb.coerceIn(0.0, 10.0))
        AcousticMode.NOTCHED -> listOf(PublishedBands.okamotoNotch(pitchHz))
        AcousticMode.HFEM -> PublishedBands.hfem()
        AcousticMode.CUSTOM_FILTER -> listOf(
            Octaves.bandAround(pitchHz * 2.0.pow(customOffsetOctaves), customWidthOctaves.coerceIn(1.0 / 12, 2.0), customGainDb.coerceIn(-40.0, 10.0))
        )
        else -> emptyList()
    }

    fun encode(): String = listOf(
        "mode" to mode.name, "src" to sourceId, "vol" to volume, "min" to minutes, "mix" to mixWithMasker,
        "gain" to bandGainDb, "tr" to transitionOctaves, "co" to customOffsetOctaves, "cw" to customWidthOctaves,
        "cg" to customGainDb, "amr" to amRateHz, "amd" to amDepth, "fmr" to fmRateHz, "fmc" to fmCents,
    ).joinToString(";") { "${it.first}=${it.second}" }

    companion object {
        fun decode(s: String?): LabSettings {
            val d = LabSettings()
            if (s.isNullOrBlank()) return d
            val m = s.split(';').mapNotNull { val i = it.indexOf('='); if (i < 0) null else it.substring(0, i) to it.substring(i + 1) }.toMap()
            fun dbl(k: String, def: Double) = m[k]?.toDoubleOrNull() ?: def
            return LabSettings(
                mode = runCatching { AcousticMode.valueOf(m["mode"] ?: "") }.getOrDefault(d.mode),
                sourceId = m["src"] ?: d.sourceId,
                volume = (m["vol"]?.toFloatOrNull() ?: d.volume).coerceIn(0f, 1f),
                minutes = (m["min"]?.toIntOrNull() ?: d.minutes).coerceIn(1, SessionLimits.MAX_MINUTES),
                mixWithMasker = m["mix"]?.toBooleanStrictOrNull() ?: d.mixWithMasker,
                bandGainDb = dbl("gain", d.bandGainDb).coerceIn(0.0, 10.0),
                transitionOctaves = dbl("tr", d.transitionOctaves).coerceIn(1.0 / 48, 1.0 / 3),
                customOffsetOctaves = dbl("co", d.customOffsetOctaves).coerceIn(-2.0, 2.0),
                customWidthOctaves = dbl("cw", d.customWidthOctaves).coerceIn(1.0 / 12, 2.0),
                customGainDb = dbl("cg", d.customGainDb).coerceIn(-40.0, 10.0),
                amRateHz = dbl("amr", d.amRateHz).coerceIn(0.1, 40.0),
                amDepth = (m["amd"]?.toFloatOrNull() ?: d.amDepth).coerceIn(0f, 1f),
                fmRateHz = dbl("fmr", d.fmRateHz).coerceIn(0.1, 40.0),
                fmCents = dbl("fmc", d.fmCents).coerceIn(0.0, 600.0),
            )
        }
    }
}

/** One sound in the Relief mix. [volume] is a 0..1 slider value. */
data class MixEntry(val soundId: String, val volume: Float)

data class Preset(val name: String, val mix: List<MixEntry>)

/** Plain-text codecs for the mix and presets (stored in DataStore). */
object MixCodec {
    fun encode(mix: List<MixEntry>): String = mix.joinToString(";") { "${it.soundId}=${it.volume}" }

    fun decode(s: String?): List<MixEntry> = s.orEmpty().split(';').mapNotNull {
        val i = it.lastIndexOf('=')
        if (i <= 0) null else it.substring(0, i).let { id -> it.substring(i + 1).toFloatOrNull()?.let { v -> MixEntry(id, v.coerceIn(0f, 1f)) } }
    }.distinctBy { it.soundId }.take(3)

    fun encodePresets(p: List<Preset>): String =
        p.joinToString("\n") { it.name.replace("\n", " ").replace("|", "/") + "|" + encode(it.mix) }

    fun decodePresets(s: String?): List<Preset> = s.orEmpty().lines().mapNotNull {
        val i = it.indexOf('|')
        if (i <= 0) null else Preset(it.substring(0, i), decode(it.substring(i + 1))).takeIf { p -> p.mix.isNotEmpty() }
    }
}
