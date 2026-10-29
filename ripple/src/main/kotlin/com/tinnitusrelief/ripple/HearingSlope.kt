package com.tinnitusrelief.ripple

import kotlin.math.log2
import kotlin.math.sqrt

/**
 * High-frequency hearing correction of [P] (Methods / Fig. 1): four profiles with maximum correction
 * 0, 15, 30 or 45 dB. Correction is 0 up to 2 kHz, 1/9 of the maximum at 2.8 kHz, rising linearly to 8/9 at
 * 8 kHz, and the maximum above that.
 *
 * Interpolation is linear in log-frequency (ASSUMPTION: [P] says "increased linearly").
 * Above 8 kHz the full maximum applies, as [P] states literally ("maximum above 8 kHz") and as the deposited
 * trial generator behaves (approved change after G2, decision T1). Exactly at 8 kHz the value is 8/9.
 * Applied per harmonic, as a smooth function of frequency.
 */
object HearingSlope {
    val PROFILES_DB = listOf(0.0, 15.0, 30.0, 45.0)

    private val POINTS = listOf(
        2000.0 to 0.0,
        2000.0 * sqrt(2.0) to 1.0 / 9,
        8000.0 to 8.0 / 9,
    )

    /** Fraction (0…1) of the maximum correction at [hz]. */
    fun weight(hz: Double): Double {
        if (hz <= POINTS.first().first) return 0.0
        if (hz > POINTS.last().first) return 1.0
        for (i in 0 until POINTS.size - 1) {
            val (f1, w1) = POINTS[i]; val (f2, w2) = POINTS[i + 1]
            if (hz <= f2) return w1 + (w2 - w1) * log2(hz / f1) / log2(f2 / f1)
        }
        return 1.0
    }

    fun gainDb(hz: Double, maxDb: Double): Double = maxDb * weight(hz)
}
