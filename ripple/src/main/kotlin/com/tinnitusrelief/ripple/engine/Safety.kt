package com.tinnitusrelief.ripple.engine

import com.tinnitusrelief.ripple.RippleBand
import com.tinnitusrelief.ripple.RippleBandTable

// =====================================================================================================
//  Safety rules for the EXPERIMENTAL spectral-ripple engine (spec section 4, Gate G3).
//  Sources: [P] = Yukhnovich et al. 2025 (listening rules); everything else is marked ENGINEERING.
// =====================================================================================================

/** G3: audible playback is NOT authorised. Changing this requires explicit approval (G4) and a calibration record. */
object OutputAuthorization {
    const val AUDIBLE_PLAYBACK_AUTHORIZED = false

    /** Spec 4.1 acceptance: LAeq at app maximum gain and 100% device volume must not exceed this. */
    const val MAX_LAEQ_DBA = 75.0
}

data class RippleSafetyLimits(
    /** [P]: no more than 60 min of listening at a time. */
    val maxSessionMs: Long = 60 * 60_000L,
    /** [P]: leave a 60 min break between listening sessions. */
    val minBreakMs: Long = 60 * 60_000L,
    /** ENGINEERING (S6): at most 120 min in any rolling 24 h. */
    val dailyCapMs: Long = 120 * 60_000L,
    /** ENGINEERING (S3): sessions start silent and fade in over 5 s. */
    val fadeInMs: Long = 5_000,
    val userStopFadeMs: Long = 1_000,
    /** ENGINEERING (S7): any interruption fades out quickly and stops; no automatic resume. */
    val interruptFadeMs: Long = 150,
    val limitFadeMs: Long = 3_000,
    /** ENGINEERING: an interrupted session may be continued by explicit user action within this window. */
    val resumeWindowMs: Long = 10 * 60_000L,
    /** ENGINEERING (S9): lock after a ≥ 2-point rise, lifted no sooner than 24 h and only with acknowledgement. */
    val worseningPoints: Int = 2,
    val worseningLockMs: Long = 24 * 3_600_000L,
    /** G3 requirement 7: 30 and 45 dB corrections stay locked pending audiologist review. */
    val allowedHearingProfilesDb: Set<Double> = setOf(0.0, 15.0),
    /** Level is relative to the calibrated maximum gain (0 dB = calibrated cap). ENGINEERING: like Layer B, ≤ −6 dB. */
    val maxLevelDb: Double = -6.0,
    /** ENGINEERING: first session starts at this level; later sessions at most at the last confirmed level. */
    val firstSessionLevelDb: Double = -40.0,
    val gainSmoothingMs: Long = 50,
)

// ------------------------------------------------------------------ screening (S8)

/** Answers before first use; null = not answered. `true` on any exclusion question means "do not use". */
data class ScreeningAnswers(
    val adult: Boolean? = null,
    /** [P] exclusion: tinnitus previously made worse by moderately loud sounds. */
    val worseWithModerateSounds: Boolean? = null,
    /** Everyday sounds uncomfortably loud or painful (hyperacusis / pain). */
    val soundSensitivityOrPain: Boolean? = null,
    /** Pulsatile (heartbeat-like) tinnitus. */
    val pulsatile: Boolean? = null,
    /** Sudden hearing loss or sudden change in hearing in the last 3 months. */
    val suddenHearingChange: Boolean? = null,
    /** New tinnitus in one ear only. */
    val newOneSidedTinnitus: Boolean? = null,
    /** Ear pain, discharge, or dizziness/vertigo. */
    val earPainDischargeOrDizziness: Boolean? = null,
)

enum class ScreeningReason { NOT_ADULT, WORSE_WITH_MODERATE_SOUNDS, SOUND_SENSITIVITY_OR_PAIN, PULSATILE, SUDDEN_HEARING_CHANGE, NEW_ONE_SIDED, EAR_PAIN_DISCHARGE_DIZZINESS }

sealed class ScreeningResult {
    data class Incomplete(val unanswered: Int) : ScreeningResult()
    data class Excluded(val reasons: List<ScreeningReason>) : ScreeningResult()
    data object Eligible : ScreeningResult()
}

object Screening {
    fun evaluate(a: ScreeningAnswers): ScreeningResult {
        val all = listOf(a.adult, a.worseWithModerateSounds, a.soundSensitivityOrPain, a.pulsatile, a.suddenHearingChange, a.newOneSidedTinnitus, a.earPainDischargeOrDizziness)
        val reasons = buildList {
            if (a.adult == false) add(ScreeningReason.NOT_ADULT)
            if (a.worseWithModerateSounds == true) add(ScreeningReason.WORSE_WITH_MODERATE_SOUNDS)
            if (a.soundSensitivityOrPain == true) add(ScreeningReason.SOUND_SENSITIVITY_OR_PAIN)
            if (a.pulsatile == true) add(ScreeningReason.PULSATILE)
            if (a.suddenHearingChange == true) add(ScreeningReason.SUDDEN_HEARING_CHANGE)
            if (a.newOneSidedTinnitus == true) add(ScreeningReason.NEW_ONE_SIDED)
            if (a.earPainDischargeOrDizziness == true) add(ScreeningReason.EAR_PAIN_DISCHARGE_DIZZINESS)
        }
        // any exclusion is final even if other questions are unanswered
        if (reasons.isNotEmpty()) return ScreeningResult.Excluded(reasons)
        val missing = all.count { it == null }
        return if (missing > 0) ScreeningResult.Incomplete(missing) else ScreeningResult.Eligible
    }
}

// ------------------------------------------------------------------ pitch and band (S9 gate, [P] Table 1)

enum class PitchConfidence { LOW, MEDIUM, HIGH }

data class PitchEstimate(val hz: Double, val confidence: PitchConfidence)

enum class Arm { ACTIVE, SHAM }

/** The band to modulate for one arm, from [P] Table 1. */
data class Prescription(val matchHz: Double, val arm: Arm, val band: RippleBand, val alternativeUsed: Boolean)

sealed class PrescriptionResult {
    data class Ok(val prescription: Prescription) : PrescriptionResult()
    data object AudibilityUnknown : PrescriptionResult()
    data object NoAudibleBand : PrescriptionResult()
}

object Prescriber {
    /**
     * [P]: A1 is the preferred active band; A2 is used only if one or more frequencies within A1 are inaudible;
     * C1 is the preferred sham band; C2 is used only if A2 is used (when the table has no C2, C1 stays).
     * [a1Audible] = the user's audibility check for A1 (null = not done).
     */
    fun prescribe(pitch: PitchEstimate, arm: Arm, a1Audible: Boolean?): PrescriptionResult {
        val row = RippleBandTable.forPitch(pitch.hz)
        if (a1Audible == null) return PrescriptionResult.AudibilityUnknown
        if (a1Audible) {
            return PrescriptionResult.Ok(Prescription(row.matchHz, arm, if (arm == Arm.ACTIVE) row.active else row.sham, false))
        }
        val a2 = row.activeAlt ?: return PrescriptionResult.NoAudibleBand
        val band = if (arm == Arm.ACTIVE) a2 else (row.shamAlt ?: row.sham)
        return PrescriptionResult.Ok(Prescription(row.matchHz, arm, band, true))
    }
}

// ------------------------------------------------------------------ stop rules (S9)

enum class AdverseSymptom { EAR_FULLNESS, EAR_PAIN, SOUND_DISTORTION, TINNITUS_LOUDER_OR_CHANGED, DIZZINESS, HEARING_DECREASE }

/** Ratings 0–10 ("How noticeable is your tinnitus right now?") before and after a session, and any symptoms. */
data class SessionReport(val before: Int?, val after: Int?, val symptoms: Set<AdverseSymptom> = emptySet())

enum class LockReason {
    /** Any adverse symptom: lifted only after a clinician has cleared continued use. */
    SYMPTOMS,
    /** Rating rose by ≥ 2 points: lifted no sooner than 24 h later, with acknowledgement of the advice. */
    WORSENING,
}

object StopRule {
    fun evaluate(r: SessionReport, limits: RippleSafetyLimits): LockReason? = when {
        r.symptoms.isNotEmpty() -> LockReason.SYMPTOMS
        r.before != null && r.after != null && r.after - r.before >= limits.worseningPoints -> LockReason.WORSENING
        else -> null
    }
}

// ------------------------------------------------------------------ output calibration (spec 4.1)

/**
 * Result of the acoustic measurement for one device + headphone + connection. Without a valid record no audible
 * output is possible. No record exists in G3.
 */
data class OutputCalibration(
    val deviceModel: String,
    val headphoneModel: String,
    val route: String,
    val measuredAtMs: Long,
    val measuredBy: String,
    /** Highest app gain (dB re full scale of the generator) at which LAeq ≤ the limit at 100% device volume. */
    val maxGainDb: Double,
    val laeqAtMaxGainDbA: Double,
)
