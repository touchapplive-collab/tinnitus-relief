package com.tinnitusrelief.app.dsp

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// =====================================================================================================
//  Long-term tracking (pure, unit-tested).
//
//  Four outcomes are kept apart:
//   A. masking            – noticeability WHILE a sound plays            (Effect.during)
//   B. comfort            – how pleasant the sound is                    (Effect.comfort)
//   C. residual inhibition – a temporary drop AFTER a sound stops         (Ri.riSeconds)
//   D. long-term change   – noticeability with NO sound playing, over weeks (Daily with unmasked = true)
//  D is the main objective. Nothing here attributes a change to any sound or algorithm.
// =====================================================================================================

/** The user's tinnitus pitch estimate with how much it can be trusted. */
data class PitchProfile(
    val latestHz: Double,
    val latestAtMs: Long,
    val method: String,
    val measurements: Int,
    /** Spread (max − min) of the recent estimates in octaves; null with a single estimate. */
    val spreadOctaves: Double?,
    val confidence: Confidence,
) {
    enum class Confidence { LOW, MEDIUM, HIGH }

    companion object {
        /** Estimates older than this are not used for the confidence rating. */
        const val WINDOW_DAYS = 180L
        const val RECENT = 5

        /**
         * ENGINEERING RULE (not from a publication):
         *  HIGH   – ≥ 3 recent estimates within 1/3 octave of each other and the latest not marked "not sure";
         *  MEDIUM – ≥ 2 recent estimates within 1/2 octave, or a single estimate marked "very sure";
         *  LOW    – otherwise (one estimate, or estimates far apart, e.g. octave confusion).
         */
        fun of(records: List<ResponseRecord>, nowMs: Long): PitchProfile? {
            val all = records.filterIsInstance<ResponseRecord.Pitch>().sortedBy { it.atMs }
            val latest = all.lastOrNull() ?: return null
            val recent = all.filter { nowMs - it.atMs <= WINDOW_DAYS * 86_400_000L }.takeLast(RECENT)
            val logs = recent.map { log2(it.freqHz) }
            val spread = if (logs.size >= 2) logs.max() - logs.min() else null
            val conf = when {
                recent.size >= 3 && spread != null && spread <= 1.0 / 3 + 1e-9 && latest.sure != 1 -> Confidence.HIGH
                recent.size >= 2 && spread != null && spread <= 0.5 + 1e-9 -> Confidence.MEDIUM
                recent.size == 1 && latest.sure == 3 -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
            return PitchProfile(latest.freqHz, latest.atMs, latest.method, all.size, spread, conf)
        }
    }
}

/** One calendar week (Monday–Sunday, local time) of tracking. */
data class WeekSummary(
    val startDay: String,
    /** Mean noticeability with no sound playing (outcome D) and the number of such ratings. */
    val unmaskedMean: Double?,
    val unmaskedCount: Int,
    val botherMean: Double?,
    /** Days rated 0 with no sound playing. */
    val tinnitusFreeDays: Int,
    /** Mean noticeability while a sound played (outcome A), from comparison sessions. */
    val maskingMean: Double?,
    val comfortMean: Double?,
    /** Residual inhibition tests this week (outcome C) and mean duration in seconds. */
    val riTests: Int,
    val riMeanSeconds: Double?,
)

/** Direction of long-term change in unmasked noticeability. */
data class LongTermTrend(
    val baseline: Double,
    val recent: Double,
    val change: Double,
    val direction: Direction,
    /** Most recent consecutive weeks in which every unmasked rating (≥ 4 per week) was 0. */
    val tinnitusFreeWeeks: Int,
) {
    enum class Direction { LOWER, STABLE, HIGHER }
}

object Progress {

    /** A week counts for the trend only with at least this many unmasked ratings. */
    const val MIN_RATINGS_PER_WEEK = 3
    /** ENGINEERING THRESHOLD: a change smaller than 1 point (0–10 scale) is shown as "about the same". */
    const val CHANGE_THRESHOLD = 1.0

    fun weekStart(atMs: Long, zone: TimeZone): String {
        val c = Calendar.getInstance(zone, Locale.US)
        c.timeInMillis = atMs
        c.firstDayOfWeek = Calendar.MONDAY
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val back = (dow - Calendar.MONDAY + 7) % 7
        c.add(Calendar.DAY_OF_MONTH, -back)
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Weekly summaries, oldest first, only for weeks that contain any tracking data. */
    fun weeks(records: List<ResponseRecord>, zone: TimeZone): List<WeekSummary> {
        val byWeek = records.groupBy { weekStart(it.atMs, zone) }
        return byWeek.keys.sorted().map { wk ->
            val rs = byWeek.getValue(wk)
            val unmasked = rs.filterIsInstance<ResponseRecord.Daily>().filter { it.unmasked == true }
            val effects = rs.filterIsInstance<ResponseRecord.Effect>()
            val ri = rs.filterIsInstance<ResponseRecord.Ri>()
            WeekSummary(
                startDay = wk,
                unmaskedMean = unmasked.map { it.rating }.averageOrNull(),
                unmaskedCount = unmasked.size,
                botherMean = unmasked.mapNotNull { it.bother }.averageOrNull(),
                tinnitusFreeDays = unmasked.count { it.rating == 0 },
                maskingMean = effects.mapNotNull { it.during }.averageOrNull(),
                comfortMean = effects.mapNotNull { it.comfort }.averageOrNull(),
                riTests = ri.size,
                riMeanSeconds = ri.mapNotNull { it.riSeconds }.averageOrNull(),
            )
        }.filter { it.unmaskedCount > 0 || it.maskingMean != null || it.riTests > 0 || it.comfortMean != null }
    }

    /**
     * Baseline = mean of the first two qualifying weeks, recent = mean of the last two; needs at least four
     * qualifying weeks so the two pairs never overlap. Returns null when there is not enough data.
     */
    fun trend(weeks: List<WeekSummary>): LongTermTrend? {
        val q = weeks.filter { it.unmaskedCount >= MIN_RATINGS_PER_WEEK && it.unmaskedMean != null }
        if (q.size < 4) return null
        val base = q.take(2).map { it.unmaskedMean!! }.average()
        val recent = q.takeLast(2).map { it.unmaskedMean!! }.average()
        val change = recent - base
        val dir = when {
            change <= -CHANGE_THRESHOLD -> LongTermTrend.Direction.LOWER
            change >= CHANGE_THRESHOLD -> LongTermTrend.Direction.HIGHER
            else -> LongTermTrend.Direction.STABLE
        }
        var free = 0
        for (w in weeks.reversed()) {
            if (w.unmaskedCount >= 4 && w.tinnitusFreeDays == w.unmaskedCount) free++ else if (w.unmaskedCount > 0) break
        }
        return LongTermTrend(base, recent, change, dir, free)
    }

    private fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()
}

/** CSV export of the whole history (one row per entry, one column per field; empty when not applicable). */
object HistoryExport {
    val COLUMNS = listOf(
        "type", "date", "time", "rating", "no_sound_playing", "bother", "before", "during", "after", "later",
        "comfort", "minutes", "pitch_hz", "pitch_method", "pitch_sure", "loudness_db_sl", "ri_seconds",
        "ri_outcome", "ri_extra_db", "stimulus", "preset", "variant",
    )

    fun csv(records: List<ResponseRecord>, zone: TimeZone): String {
        val sb = StringBuilder(COLUMNS.joinToString(",")).append('\n')
        val c = Calendar.getInstance(zone, Locale.US)
        for (r in records.sortedBy { it.atMs }) {
            c.timeInMillis = r.atMs
            val m = HashMap<String, Any?>()
            m["date"] = "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
            m["time"] = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            when (r) {
                is ResponseRecord.Pitch -> {
                    m["type"] = "pitch"; m["pitch_hz"] = "%.1f".format(Locale.US, r.freqHz); m["pitch_method"] = r.method
                    m["pitch_sure"] = r.sure; m["loudness_db_sl"] = r.loudnessDbSl?.let { "%.1f".format(Locale.US, it) }
                }
                is ResponseRecord.Daily -> {
                    m["type"] = "daily"; m["date"] = r.day; m["rating"] = r.rating
                    m["no_sound_playing"] = r.unmasked?.let { if (it) "yes" else "no" } ?: "unknown"; m["bother"] = r.bother
                }
                is ResponseRecord.Relief -> {
                    m["type"] = "relief_test"; m["stimulus"] = r.stimulus; m["minutes"] = r.minutes
                    m["before"] = r.before; m["after"] = r.after; m["later"] = r.later
                }
                is ResponseRecord.Ri -> {
                    m["type"] = "residual_inhibition"; m["pitch_hz"] = "%.1f".format(Locale.US, r.freqHz); m["before"] = r.before
                    m["after"] = r.after; m["ri_seconds"] = r.riSeconds; m["ri_outcome"] = r.outcome; m["ri_extra_db"] = r.extraDb
                }
                is ResponseRecord.Effect -> {
                    m["type"] = "original_vs_personalized"; m["preset"] = r.preset; m["variant"] = r.variant
                    m["minutes"] = r.minutes; m["before"] = r.before; m["during"] = r.during; m["after"] = r.after; m["comfort"] = r.comfort
                }
            }
            sb.append(COLUMNS.joinToString(",") { field(m[it]) }).append('\n')
        }
        return sb.toString()
    }

    private fun field(v: Any?): String {
        val s = v?.toString() ?: return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
