package com.tinnitusrelief.ripple.therapy

import com.tinnitusrelief.ripple.HearingSlope
import com.tinnitusrelief.ripple.RippleBand
import com.tinnitusrelief.ripple.RippleBandTable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One tone of a check sequence: frequency and level relative to the sequence reference. */
data class CheckTone(val hz: Double, val gainDb: Double)

/**
 * Hearing-slope check of [P]: the 17 match tones (1–16 kHz in ¼-octave steps) are presented with each of the four
 * correction profiles; the participant chooses the version in which all tones sound most similar in loudness.
 * Tone length and gap: ENGINEERING (1 s tones, 1 s gaps).
 */
object HearingCheck {
    const val TONE_MS = 1000
    const val GAP_MS = 1000

    fun sequence(maxCorrectionDb: Double): List<CheckTone> =
        RippleBandTable.MATCH_HZ.map { CheckTone(it, HearingSlope.gainDb(it, maxCorrectionDb)) }
}

/**
 * Audibility check of the active band [P]: A2 replaces A1 when one or more frequencies within A1 are inaudible.
 * The frequencies tested are the match tones that lie inside the band (edges included).
 */
object AudibilityCheck {
    const val TONE_MS = 1000
    const val GAP_MS = 1000

    fun tonesFor(band: RippleBand, hearingProfileDb: Double): List<CheckTone> =
        RippleBandTable.MATCH_HZ.filter { it >= band.lowHz * 0.999 && it <= band.highHz * 1.001 }
            .map { CheckTone(it, HearingSlope.gainDb(it, hearingProfileDb)) }

    /** null until every tone has been answered; true only if all were heard. */
    fun result(tones: List<CheckTone>, heard: Map<Double, Boolean>): Boolean? =
        if (tones.any { it.hz !in heard }) null else tones.all { heard[it.hz] == true }
}

/**
 * Renders a check sequence as mono float samples: sine tones with 10 ms raised-cosine ramps, separated by
 * silence. Each tone's RMS = [referenceRmsDbfs] + its gain. ENGINEERING: the reference is 10 dB below the
 * stimulus RMS (−30 dBFS), so a tone heard at the session level implies the band components are audible.
 * Pure computation; whether it may be played is decided by the output gate.
 */
object ToneSequenceRenderer {
    const val RAMP_MS = 10.0
    const val REFERENCE_RMS_DBFS = -40.0

    fun render(tones: List<CheckTone>, sampleRate: Int, toneMs: Int, gapMs: Int, referenceRmsDbfs: Double = REFERENCE_RMS_DBFS): FloatArray {
        val toneN = (toneMs * sampleRate / 1000.0).roundToInt()
        val gapN = (gapMs * sampleRate / 1000.0).roundToInt()
        val out = FloatArray(tones.size * (toneN + gapN))
        val rampN = (RAMP_MS * sampleRate / 1000).roundToInt()
        tones.forEachIndexed { k, t ->
            val amp = sqrt(2.0) * 10.0.pow((referenceRmsDbfs + t.gainDb) / 20)
            val off = k * (toneN + gapN)
            for (i in 0 until toneN) {
                val r = when { i < rampN -> 0.5 * (1 - cos(PI * i / rampN)); i >= toneN - rampN -> 0.5 * (1 - cos(PI * (toneN - 1 - i) / rampN)); else -> 1.0 }
                out[off + i] = (amp * r * sin(2 * PI * t.hz * i / sampleRate)).toFloat()
            }
        }
        return out
    }
}
