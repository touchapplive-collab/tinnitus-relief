package com.tinnitusrelief.ripple.therapy

import com.tinnitusrelief.ripple.engine.Arm
import com.tinnitusrelief.ripple.engine.Blocker
import com.tinnitusrelief.ripple.engine.GateResult
import com.tinnitusrelief.ripple.engine.PitchEstimate
import com.tinnitusrelief.ripple.engine.RippleGate
import com.tinnitusrelief.ripple.engine.RippleSafetyLimits
import com.tinnitusrelief.ripple.engine.SafetyLedger
import com.tinnitusrelief.ripple.engine.StartRequest

/**
 * Combines the safety gate with the study plan.
 *  - Therapy session: the arm comes from the plan (hidden from the UI); washout days and days outside the
 *    plan are blocked; the output is audible and therefore subject to authorisation and calibration.
 *  - Silent rehearsal: never audible, uses its own ledger, ignores the plan; the band audibility answer is
 *    assumed for rehearsal only, because nothing is heard.
 */
object TherapyGate {

    fun evaluate(
        state: TherapyState,
        pitch: PitchEstimate?,
        ledger: SafetyLedger,
        limits: RippleSafetyLimits,
        nowMs: Long,
        today: Long,
        rehearsal: Boolean,
        currentRoute: String? = null,
        resume: Boolean = false,
    ): GateResult {
        val extra = mutableListOf<Blocker>()
        var arm = Arm.ACTIVE
        if (!rehearsal) {
            val plan = state.plan
            if (plan == null) extra += Blocker.NO_PLAN
            else {
                val ph = plan.phase(today)
                when (ph.kind) {
                    PhaseKind.NOT_STARTED -> extra += Blocker.PLAN_NOT_STARTED
                    PhaseKind.WASHOUT -> extra += Blocker.PLAN_WASHOUT
                    PhaseKind.FINISHED -> extra += Blocker.PLAN_FINISHED
                    PhaseKind.LISTENING -> arm = ph.arm!!
                }
            }
        }
        val a1 = state.a1AudibleFor(pitch?.hz) ?: if (rehearsal) true else null
        val req = StartRequest(
            screening = state.screening, pitch = pitch, a1Audible = a1, arm = arm,
            hearingProfileDb = state.hearingProfileDb, audibleOutput = !rehearsal,
            calibration = if (rehearsal) null else state.calibrationFor(currentRoute), currentRoute = currentRoute, resume = resume,
        )
        val base = RippleGate.evaluate(req, ledger, limits, nowMs)
        return GateResult(rehearsal, (base.blockers + extra).distinct(), base.prescription, base.allowedMs, base.resumedFromStartMs, base.request, base.startLevelCapDb)
    }
}
