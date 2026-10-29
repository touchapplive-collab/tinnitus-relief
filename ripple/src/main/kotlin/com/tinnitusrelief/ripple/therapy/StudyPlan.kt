package com.tinnitusrelief.ripple.therapy

import com.tinnitusrelief.ripple.engine.Arm

/**
 * Personal crossover plan following the published trial design [P]: two 6-week listening periods (active and
 * sham, in random order), each followed by a 3-week washout. The order is drawn by the app and hidden while the
 * plan is blinded.
 */
data class StudyPlan(
    val startEpochDay: Long,
    val firstArm: Arm,
    val createdAtMs: Long,
    /** Set when the user chose to see the order before the plan ended (recorded, cannot be undone). */
    val unblindedAtMs: Long? = null,
    /** [P]: 6 weeks of listening. */
    val listenDays: Int = 42,
    /** [P]: 3-week washout after each listening period. */
    val washoutDays: Int = 21,
) {
    val totalDays: Int get() = 2 * (listenDays + washoutDays)
    val secondArm: Arm get() = if (firstArm == Arm.ACTIVE) Arm.SHAM else Arm.ACTIVE
    fun armOf(period: Int): Arm = if (period == 1) firstArm else secondArm
    fun listenStart(period: Int): Long = startEpochDay + if (period == 1) 0 else (listenDays + washoutDays).toLong()
    fun listenEnd(period: Int): Long = listenStart(period) + listenDays - 1
    fun washoutEnd(period: Int): Long = listenEnd(period) + washoutDays

    fun phase(epochDay: Long): PlanPhase {
        val d = epochDay - startEpochDay
        val cycle = (listenDays + washoutDays).toLong()
        return when {
            d < 0 -> PlanPhase(PhaseKind.NOT_STARTED, 0, null, d.toInt(), 0)
            d >= 2 * cycle -> PlanPhase(PhaseKind.FINISHED, 2, null, (d - 2 * cycle).toInt(), 0)
            else -> {
                val period = if (d < cycle) 1 else 2
                val inCycle = d - (period - 1) * cycle
                if (inCycle < listenDays) PlanPhase(PhaseKind.LISTENING, period, armOf(period), inCycle.toInt(), listenDays)
                else PlanPhase(PhaseKind.WASHOUT, period, null, (inCycle - listenDays).toInt(), washoutDays)
            }
        }
    }

    /** Whether the arm order may be shown: after the plan has ended, or after an explicit unblinding. */
    fun armsVisible(epochDay: Long): Boolean = unblindedAtMs != null || phase(epochDay).kind == PhaseKind.FINISHED

    companion object {
        fun create(startEpochDay: Long, random: java.util.Random, nowMs: Long): StudyPlan =
            StudyPlan(startEpochDay, if (random.nextBoolean()) Arm.ACTIVE else Arm.SHAM, nowMs)
    }
}

enum class PhaseKind { NOT_STARTED, LISTENING, WASHOUT, FINISHED }

/** Where the plan stands on a given day. [arm] is internal: the UI must not show it while blinded. */
data class PlanPhase(val kind: PhaseKind, val period: Int, val arm: Arm?, val dayIndex: Int, val lengthDays: Int)
