package com.tinnitusrelief.ripple.engine

import com.tinnitusrelief.ripple.RippleBand

enum class EndReason { USER_STOP, LIMIT_REACHED, INTERRUPTED, ERROR }

enum class Interruption { FOCUS_LOSS, FOCUS_LOSS_TRANSIENT, FOCUS_DUCK, BECOMING_NOISY, OUTPUT_ROUTE_CHANGED, PHONE_CALL, SERVICE_STOPPED, THERMAL }

data class SessionRecord(
    val startMs: Long,
    val endMs: Long,
    /** Audio actually rendered (fades included). */
    val audioMs: Long,
    val reason: EndReason,
    val interruption: Interruption?,
    val band: RippleBand,
    val arm: Arm,
    val hearingProfileDb: Double,
    val maxLevelDb: Double,
    /** Continues an interrupted session (counts towards the same 60 min). */
    val resumedFromStartMs: Long? = null,
    val report: SessionReport? = null,
)

data class Lock(val reason: LockReason, val sinceMs: Long)

/** Persistent safety state. Pure data; storage is the caller's job (text codec below). */
data class SafetyLedger(
    val sessions: List<SessionRecord> = emptyList(),
    val lock: Lock? = null,
    /** Level the user last confirmed (dB, relative); later sessions never start above it. */
    val lastConfirmedLevelDb: Double? = null,
) {
    fun withSession(r: SessionRecord) = copy(sessions = sessions + r)

    /** Attach the post-session ratings to the most recent session and apply the stop rule. */
    fun withReport(report: SessionReport, nowMs: Long, limits: RippleSafetyLimits): SafetyLedger {
        val last = sessions.lastOrNull() ?: return this
        val updated = copy(sessions = sessions.dropLast(1) + last.copy(report = report))
        val reason = StopRule.evaluate(report, limits) ?: return updated
        // a symptom lock is never downgraded by a later worsening lock
        val keep = updated.lock?.takeIf { it.reason == LockReason.SYMPTOMS }
        return updated.copy(lock = keep ?: Lock(reason, nowMs))
    }

    /** Lifts a lock if the rule allows it. Returns the unchanged ledger when it does not. */
    fun clearLock(nowMs: Long, adviceAcknowledged: Boolean, clinicianCleared: Boolean, limits: RippleSafetyLimits): SafetyLedger {
        val l = lock ?: return this
        val ok = when (l.reason) {
            LockReason.SYMPTOMS -> clinicianCleared && adviceAcknowledged
            LockReason.WORSENING -> adviceAcknowledged && nowMs - l.sinceMs >= limits.worseningLockMs
        }
        return if (ok) copy(lock = null) else this
    }

    fun confirmLevel(db: Double) = copy(lastConfirmedLevelDb = db)

    /** Audio listened in the rolling window ending at [nowMs]. */
    fun audioInWindow(nowMs: Long, windowMs: Long): Long =
        sessions.filter { it.endMs > nowMs - windowMs }.sumOf { s ->
            val overlapStart = maxOf(s.startMs, nowMs - windowMs)
            // proportional share of the session inside the window
            val wall = (s.endMs - s.startMs).coerceAtLeast(1)
            (s.audioMs * (s.endMs - overlapStart).coerceAtMost(wall) / wall)
        }

    // ------------------------------------------------------------- codec (one record per line)
    fun encode(): String = buildString {
        lock?.let { append("LOCK\t${it.reason}\t${it.sinceMs}\n") }
        lastConfirmedLevelDb?.let { append("LEVEL\t$it\n") }
        for (s in sessions) {
            append("S\t${s.startMs}\t${s.endMs}\t${s.audioMs}\t${s.reason}\t${s.interruption ?: "-"}\t${s.band}\t${s.arm}\t${s.hearingProfileDb}\t${s.maxLevelDb}\t${s.resumedFromStartMs ?: "-"}")
            val r = s.report
            if (r != null) append("\t${r.before ?: "-"}\t${r.after ?: "-"}\t${r.symptoms.joinToString(",").ifEmpty { "-" }}")
            append("\n")
        }
    }

    companion object {
        fun decode(text: String): SafetyLedger {
            var l = SafetyLedger()
            for (line in text.lines()) {
                val f = line.split("\t")
                runCatching {
                    when (f[0]) {
                        "LOCK" -> l = l.copy(lock = Lock(LockReason.valueOf(f[1]), f[2].toLong()))
                        "LEVEL" -> l = l.copy(lastConfirmedLevelDb = f[1].toDouble())
                        "S" -> {
                            val report = if (f.size >= 14) SessionReport(f[11].toIntOrNull(), f[12].toIntOrNull(),
                                if (f[13] == "-") emptySet() else f[13].split(",").map { AdverseSymptom.valueOf(it) }.toSet()) else null
                            l = l.withSession(SessionRecord(f[1].toLong(), f[2].toLong(), f[3].toLong(), EndReason.valueOf(f[4]),
                                f[5].takeIf { it != "-" }?.let { Interruption.valueOf(it) }, RippleBand.valueOf(f[6]), Arm.valueOf(f[7]),
                                f[8].toDouble(), f[9].toDouble(), f[10].toLongOrNull(), report))
                        }
                    }
                }
            }
            return l
        }
    }
}
