package com.tinnitusrelief.ripple.engine

import kotlin.math.abs

/** Everything needed to decide whether a session may start. */
data class StartRequest(
    val screening: ScreeningAnswers,
    val pitch: PitchEstimate?,
    val a1Audible: Boolean?,
    val arm: Arm,
    val hearingProfileDb: Double,
    /** True if the output would reach the user's ears. Always false in G3. */
    val audibleOutput: Boolean,
    val calibration: OutputCalibration? = null,
    val currentRoute: String? = null,
    /** Continue the last session if it was interrupted (explicit user action). */
    val resume: Boolean = false,
)

enum class Blocker {
    AUDIBLE_PLAYBACK_NOT_AUTHORIZED, NO_CALIBRATION, ROUTE_NOT_CALIBRATED, CALIBRATION_ABOVE_LIMIT,
    SCREENING_INCOMPLETE, SCREENING_EXCLUDED,
    NO_PITCH, PITCH_LOW_CONFIDENCE, AUDIBILITY_UNKNOWN, NO_AUDIBLE_BAND,
    HEARING_PROFILE_LOCKED, HEARING_PROFILE_INVALID,
    LOCKED_SYMPTOMS, LOCKED_WORSENING, POST_SESSION_REPORT_MISSING,
    BREAK_TOO_SHORT, DAILY_CAP_REACHED, NOTHING_TO_RESUME,
    // study plan (therapy layer)
    NO_PLAN, PLAN_NOT_STARTED, PLAN_WASHOUT, PLAN_FINISHED,
}

/** Only [RippleGate] can create an allowed result; the engine refuses to start without one. */
class GateResult internal constructor(
    /** True for a silent rehearsal: never audible, never counted as therapy. */
    val rehearsal: Boolean = false,
    val blockers: List<Blocker>,
    val prescription: Prescription?,
    /** Longest this session may run (audio time). */
    val allowedMs: Long,
    val resumedFromStartMs: Long?,
    val request: StartRequest,
    val startLevelCapDb: Double,
) {
    val allowed: Boolean get() = blockers.isEmpty()
}

object RippleGate {

    private val PROFILES = setOf(0.0, 15.0, 30.0, 45.0)

    fun evaluate(req: StartRequest, ledger: SafetyLedger, limits: RippleSafetyLimits, nowMs: Long): GateResult {
        val b = mutableListOf<Blocker>()

        // output: G3 never authorises audible playback; afterwards a matching calibration record is required
        if (req.audibleOutput) {
            if (!OutputAuthorization.AUDIBLE_PLAYBACK_AUTHORIZED) b += Blocker.AUDIBLE_PLAYBACK_NOT_AUTHORIZED
            val cal = req.calibration
            when {
                cal == null -> b += Blocker.NO_CALIBRATION
                cal.route != req.currentRoute -> b += Blocker.ROUTE_NOT_CALIBRATED
                cal.laeqAtMaxGainDbA > OutputAuthorization.MAX_LAEQ_DBA -> b += Blocker.CALIBRATION_ABOVE_LIMIT
            }
        }

        when (val s = Screening.evaluate(req.screening)) {
            is ScreeningResult.Incomplete -> b += Blocker.SCREENING_INCOMPLETE
            is ScreeningResult.Excluded -> b += Blocker.SCREENING_EXCLUDED
            ScreeningResult.Eligible -> Unit
        }

        var prescription: Prescription? = null
        val pitch = req.pitch
        if (pitch == null) b += Blocker.NO_PITCH
        else if (pitch.confidence == PitchConfidence.LOW) b += Blocker.PITCH_LOW_CONFIDENCE
        else when (val p = Prescriber.prescribe(pitch, req.arm, req.a1Audible)) {
            is PrescriptionResult.Ok -> prescription = p.prescription
            PrescriptionResult.AudibilityUnknown -> b += Blocker.AUDIBILITY_UNKNOWN
            PrescriptionResult.NoAudibleBand -> b += Blocker.NO_AUDIBLE_BAND
        }

        if (PROFILES.none { abs(it - req.hearingProfileDb) < 1e-9 }) b += Blocker.HEARING_PROFILE_INVALID
        else if (limits.allowedHearingProfilesDb.none { abs(it - req.hearingProfileDb) < 1e-9 }) b += Blocker.HEARING_PROFILE_LOCKED

        when (ledger.lock?.reason) {
            LockReason.SYMPTOMS -> b += Blocker.LOCKED_SYMPTOMS
            LockReason.WORSENING -> b += Blocker.LOCKED_WORSENING
            null -> Unit
        }

        val last = ledger.sessions.lastOrNull()
        var resumedFrom: Long? = null
        var sessionBudget = limits.maxSessionMs
        if (req.resume) {
            val canResume = last != null && last.reason == EndReason.INTERRUPTED && nowMs - last.endMs <= limits.resumeWindowMs
            if (!canResume) b += Blocker.NOTHING_TO_RESUME
            else {
                val origin = last!!.resumedFromStartMs ?: last.startMs
                val used = ledger.sessions.filter { (it.resumedFromStartMs ?: it.startMs) == origin }.sumOf { it.audioMs }
                sessionBudget = limits.maxSessionMs - used
                resumedFrom = origin
            }
        } else {
            if (last != null && last.report == null) b += Blocker.POST_SESSION_REPORT_MISSING
            if (last != null && nowMs - last.endMs < limits.minBreakMs) b += Blocker.BREAK_TOO_SHORT
        }
        if (req.resume && last != null && last.report != null) {
            // a report closes the session; a resume after that is a new session
            b += Blocker.NOTHING_TO_RESUME
        }

        val dailyLeft = limits.dailyCapMs - ledger.audioInWindow(nowMs, 24 * 3_600_000L)
        val allowedMs = minOf(sessionBudget, dailyLeft)
        if (allowedMs < 60_000) {
            if (dailyLeft < 60_000) b += Blocker.DAILY_CAP_REACHED else if (req.resume) b += Blocker.NOTHING_TO_RESUME
        }

        val startCap = minOf(limits.maxLevelDb, ledger.lastConfirmedLevelDb ?: limits.firstSessionLevelDb)
        return GateResult(false, b.distinct(), prescription, allowedMs.coerceAtLeast(0), resumedFrom, req, startCap)
    }
}
