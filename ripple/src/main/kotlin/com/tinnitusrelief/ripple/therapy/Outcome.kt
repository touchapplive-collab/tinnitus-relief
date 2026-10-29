package com.tinnitusrelief.ripple.therapy

import com.tinnitusrelief.ripple.engine.Arm
import com.tinnitusrelief.ripple.engine.SafetyLedger

/**
 * Per-period outcome from the daily ratings taken with NO sound playing (0–10, Phase 0 tracking).
 * Mirrors the trial's measurement points ([P]: loudness at the start and end of each listening and washout
 * period) with 7-day windows of daily ratings (ENGINEERING); a window needs ≥ 3 ratings.
 */
data class PeriodSummary(
    val period: Int,
    /** null while the plan is blinded. */
    val arm: Arm?,
    val startMean: Double?,
    val endMean: Double?,
    val washoutEndMean: Double?,
    val sessions: Int,
    val listenedMinutes: Long,
) {
    /** End of listening minus start (negative = quieter). */
    val change: Double? get() = if (startMean != null && endMean != null) endMean - startMean else null
    /** End of washout minus start: what remained 3 weeks after listening stopped. */
    val persisting: Double? get() = if (startMean != null && washoutEndMean != null) washoutEndMean - startMean else null
}

object PlanOutcome {
    const val WINDOW_DAYS = 7
    const val MIN_RATINGS = 3

    /** [ratings] = (epochDay, rating) for unmasked daily ratings; [dayOf] converts a timestamp to its epoch day. */
    fun summarize(plan: StudyPlan, ratings: List<Pair<Long, Int>>, ledger: SafetyLedger, today: Long, dayOf: (Long) -> Long): List<PeriodSummary> {
        val visible = plan.armsVisible(today)
        fun mean(from: Long, to: Long): Double? {
            if (to > today) return null
            val v = ratings.filter { it.first in from..to }.map { it.second }
            return if (v.size >= MIN_RATINGS) v.average() else null
        }
        return (1..2).map { p ->
            val s = plan.listenStart(p); val e = plan.listenEnd(p); val w = plan.washoutEnd(p)
            val inPeriod = ledger.sessions.filter { dayOf(it.startMs) in s..e }
            PeriodSummary(
                period = p,
                arm = if (visible) plan.armOf(p) else null,
                startMean = mean(s - WINDOW_DAYS + 1, s),
                endMean = mean(e - WINDOW_DAYS + 1, e),
                washoutEndMean = mean(w - WINDOW_DAYS + 1, w),
                sessions = inPeriod.size,
                listenedMinutes = inPeriod.sumOf { it.audioMs } / 60_000,
            )
        }
    }
}
