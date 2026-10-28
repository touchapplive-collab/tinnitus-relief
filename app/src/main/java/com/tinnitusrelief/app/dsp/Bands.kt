package com.tinnitusrelief.app.dsp

import kotlin.math.pow
import kotlin.math.sqrt

/** A frequency band with a gain, in Hz and dB. */
data class Band(val lowHz: Double, val highHz: Double, val gainDb: Double) {
    val centerHz: Double get() = sqrt(lowHz * highHz)
    val widthOctaves: Double get() = log2(highHz / lowHz)
}

fun log2(x: Double): Double = kotlin.math.ln(x) / kotlin.math.ln(2.0)

/** Octave arithmetic shared by every module. */
object Octaves {
    fun shift(freq: Double, octaves: Double): Double = freq * 2.0.pow(octaves)

    /** Band of [widthOctaves] centred (geometrically) on [center]. */
    fun bandAround(center: Double, widthOctaves: Double, gainDb: Double = 0.0) =
        Band(shift(center, -widthOctaves / 2), shift(center, widthOctaves / 2), gainDb)
}

/**
 * Band calculations taken from the publications. Every function names its source.
 */
object PublishedBands {

    /**
     * DFCRS band placement.
     *
     * Source: Tang, Wang & Sun (2025), "Three-alternative forced-choice method (3AFC) for tinnitus matching
     * and digital frequency customized relieving sound (DFCRS) generation strategy", Sound & Vibration 59(1),
     * doi:10.59400/sv1928, section 2.4, equations (1)–(6):
     *
     *   Fu = 2·Fl,  Fu·Fl = Ft²   ⇒  Fu = √2·Ft,  Fl = Ft/√2
     *   Fuu = (2^(1/N))^(1/2)·Fu,  Ful = (2^(1/N))^(-1/2)·Fu
     *   Flu = (2^(1/N))^(1/2)·Fl,  Fll = (2^(1/N))^(-1/2)·Fl
     *   bands [Ful, Fuu] and [Fll, Flu], with N = 3 (1/3-octave bands, also stated in the JMIR 2025 and
     *   eClinicalMedicine 2025 papers).
     *
     * Gain: +10 dB in both bands (JMIR 2025 e60150; eClinicalMedicine 2025 103671, label of the published
     * example "DFCRS-4000hz-10.00dB"). The Sound & Vibration paper also mentions 5–10 dB, so the gain is
     * exposed as a parameter with 10 dB as the documented default.
     */
    fun dfcrs(tinnitusHz: Double, n: Int = 3, gainDb: Double = 10.0): List<Band> {
        val fu = sqrt(2.0) * tinnitusHz
        val fl = tinnitusHz / sqrt(2.0)
        val r = 2.0.pow(1.0 / n).pow(0.5)
        return listOf(
            Band(fl / r, fl * r, gainDb), // [Fll, Flu]
            Band(fu / r, fu * r, gainDb), // [Ful, Fuu]
        )
    }

    /**
     * HFEM comparator arm of the MOST trial (eClinicalMedicine 2025, 103671): 6000–9000 Hz raised by 10 dB
     * regardless of tinnitus pitch. In that trial it performed slightly WORSE than unmodified music.
     */
    fun hfem(): List<Band> = listOf(Band(6000.0, 9000.0, 10.0))

    /**
     * Narrow-band noise of the UM+NBN arm (MOST trial): 1/3 octave centred on the dominant tinnitus pitch.
     * Its level relative to the music is NOT reported.
     */
    fun mostNarrowband(tinnitusHz: Double): Band = Octaves.bandAround(tinnitusHz, 1.0 / 3.0)

    /**
     * Notch of tailor-made notched music (Okamoto et al., PNAS 2010): one octave centred on the tinnitus
     * frequency removed from the music. The notch depth and edge shape used here are engineering choices.
     */
    fun okamotoNotch(tinnitusHz: Double, depthDb: Double = -60.0): Band =
        Octaves.bandAround(tinnitusHz, 1.0, depthDb)
}
