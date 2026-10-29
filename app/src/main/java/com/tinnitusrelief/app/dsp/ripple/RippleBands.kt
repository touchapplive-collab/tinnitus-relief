package com.tinnitusrelief.app.dsp.ripple

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The seven octave-wide candidate modulation bands of [P] Table 1: B1 = 1–2 kHz … B7 = 8–16 kHz, in ½-octave steps.
 * Exact edges are 1000·2^(k/2) Hz (the table prints them rounded, e.g. 2.8 and 5.7 kHz).
 */
enum class RippleBand(val number: Int) {
    B1(1), B2(2), B3(3), B4(4), B5(5), B6(6), B7(7);

    val lowHz: Double get() = 1000.0 * 2.0.pow((number - 1) / 2.0)
    val highHz: Double get() = 2.0 * lowHz
    /** [P] eq. 4: c = logarithmic centre of the band (geometric mean of the edges; see spec uncertainty 4). */
    val centreHz: Double get() = sqrt(lowHz * highHz)

    /** Band membership. ENGINEERING: lower edge inclusive, upper edge exclusive (not stated in [P]). */
    fun contains(hz: Double): Boolean = hz >= lowHz && hz < highHz
}

/** One row of [P] Table 1: preferred active (A1) and sham (C1) bands, and contingency bands A2/C2. */
data class BandAssignment(
    val matchHz: Double,
    val active: RippleBand,
    val activeAlt: RippleBand?,
    val sham: RippleBand,
    val shamAlt: RippleBand?,
)

object RippleBandTable {
    /** [P]: 17 match tones, 1–16 kHz in ¼-octave steps. */
    val MATCH_HZ: List<Double> = (0..16).map { 1000.0 * 2.0.pow(it / 4.0) }

    private val B = RippleBand.entries

    /** Transcribed from [P] Table 1 (rows = match tones 1.0, 1.2, 1.4, 1.7, 2.0, 2.4, 2.8, 3.4, 4.0, 4.8, 5.7, 6.7, 8.0, 9.5, 11, 13, 16 kHz). */
    val ROWS: List<BandAssignment> = listOf(
        row(0, 1, null, 3, null),
        row(1, 1, null, 3, null),
        row(2, 1, null, 3, null),
        row(3, 2, 1, 4, null),
        row(4, 2, null, 4, null),
        row(5, 3, 2, 5, null),
        row(6, 3, null, 5, null),
        row(7, 4, 3, 6, null),
        row(8, 4, null, 6, null),
        row(9, 5, 4, 3, 2),
        row(10, 5, null, 3, null),
        row(11, 6, 5, 4, 3),
        row(12, 6, null, 4, null),
        row(13, 6, null, 4, null),
        row(14, 7, null, 5, null),
        row(15, 7, null, 5, null),
        row(16, 7, null, 5, null),
    )

    private fun row(i: Int, a1: Int, a2: Int?, c1: Int, c2: Int?) =
        BandAssignment(MATCH_HZ[i], B[a1 - 1], a2?.let { B[it - 1] }, B[c1 - 1], c2?.let { B[it - 1] })

    /** Index of the nearest match tone (in octaves) for a continuous pitch estimate. */
    fun nearestIndex(pitchHz: Double): Int = MATCH_HZ.indices.minBy { abs(log2(pitchHz / MATCH_HZ[it])) }

    fun forPitch(pitchHz: Double): BandAssignment = ROWS[nearestIndex(pitchHz)]
}
