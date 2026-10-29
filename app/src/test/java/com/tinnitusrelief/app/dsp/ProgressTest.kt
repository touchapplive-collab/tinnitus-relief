package com.tinnitusrelief.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Long-term tracking: pitch confidence, weekly summaries, trend and CSV export. */
class ProgressTest {

    private val zone = TimeZone.getTimeZone("Europe/Belgrade")
    private val day = 86_400_000L
    // Monday 2026-10-05 12:00 Belgrade (UTC+2 in October) = 10:00 UTC
    private val monday = 1_791_194_400_000L

    private fun pitch(d: Int, hz: Double, sure: Int? = null) = ResponseRecord.Pitch(monday + d * day, hz, "3afc", null, sure)
    private fun daily(d: Int, r: Int, unmasked: Boolean? = true, bother: Int? = null) =
        ResponseRecord.Daily(monday + d * day, "d$d", r, unmasked, bother)

    @Test fun pitchConfidenceFollowsTheDocumentedRule() {
        val now = monday + 30 * day
        assertEquals(null, PitchProfile.of(emptyList(), now))
        assertEquals(PitchProfile.Confidence.LOW, PitchProfile.of(listOf(pitch(0, 4000.0)), now)!!.confidence)
        assertEquals(PitchProfile.Confidence.MEDIUM, PitchProfile.of(listOf(pitch(0, 4000.0, sure = 3)), now)!!.confidence)
        // two estimates a quarter octave apart → medium; three within 1/3 octave → high
        assertEquals(PitchProfile.Confidence.MEDIUM, PitchProfile.of(listOf(pitch(0, 4000.0), pitch(3, 4757.0)), now)!!.confidence)
        val three = listOf(pitch(0, 4000.0), pitch(3, 4400.0), pitch(9, 4200.0, sure = 2))
        val p = PitchProfile.of(three, now)!!
        assertEquals(PitchProfile.Confidence.HIGH, p.confidence)
        assertEquals(4200.0, p.latestHz, 0.0)
        assertEquals(3, p.measurements)
        assertEquals(log2(4400.0 / 4000.0), p.spreadOctaves!!, 1e-9)
        // octave confusion (4 kHz vs 8 kHz) → low
        assertEquals(PitchProfile.Confidence.LOW, PitchProfile.of(listOf(pitch(0, 4000.0), pitch(2, 8000.0), pitch(4, 4000.0)), now)!!.confidence)
        // "not sure" on the latest keeps it below high
        assertEquals(PitchProfile.Confidence.MEDIUM, PitchProfile.of(three.dropLast(1) + pitch(9, 4200.0, sure = 1), now)!!.confidence)
        // estimates older than the window do not count
        assertEquals(PitchProfile.Confidence.LOW, PitchProfile.of(listOf(pitch(0, 4000.0), pitch(1, 4100.0), pitch(300, 4050.0)), monday + 300 * day)!!.confidence)
    }

    @Test fun weeksSeparateUnmaskedMaskingAndResidualInhibition() {
        val recs = listOf(
            daily(0, 6, bother = 4), daily(1, 4), daily(2, 0),
            daily(3, 9, unmasked = null), // older entry: not counted as unmasked
            ResponseRecord.Effect(monday + day, "My Shower", "original", 10, 6, 2, 5, 8),
            ResponseRecord.Ri(monday + 2 * day, 4000.0, 60, 10, 6, 3, "quieter", 40),
            daily(7, 5), // next Monday
        )
        val w = Progress.weeks(recs, zone)
        assertEquals(listOf("2026-10-05", "2026-10-12"), w.map { it.startDay })
        assertEquals(3, w[0].unmaskedCount)
        assertEquals(10.0 / 3, w[0].unmaskedMean!!, 1e-9)
        assertEquals(4.0, w[0].botherMean!!, 1e-9)
        assertEquals(1, w[0].tinnitusFreeDays)
        assertEquals(2.0, w[0].maskingMean!!, 1e-9)
        assertEquals(8.0, w[0].comfortMean!!, 1e-9)
        assertEquals(1, w[0].riTests); assertEquals(40.0, w[0].riMeanSeconds!!, 1e-9)
        assertEquals(1, w[1].unmaskedCount)
        // Sunday 23:30 local still belongs to the same week
        assertEquals("2026-10-05", Progress.weekStart(monday + 6 * day + 11 * 3_600_000L + 30 * 60_000L, zone))
    }

    @Test fun trendNeedsEnoughDataAndNeverCallsOneGoodDayRemission() {
        fun week(start: Int, ratings: List<Int>) = ratings.mapIndexed { i, r -> daily(start + i, r) }
        // three qualifying weeks: not enough for a trend
        val three = week(0, listOf(6, 6, 6)) + week(7, listOf(5, 5, 5)) + week(14, listOf(4, 4, 4))
        assertEquals(null, Progress.trend(Progress.weeks(three, zone)))
        // four weeks: baseline 6.0 → recent 3.5 = lower
        val four = three + week(21, listOf(3, 3, 3))
        val t = Progress.trend(Progress.weeks(four, zone))!!
        assertEquals(5.5, t.baseline, 1e-9); assertEquals(3.5, t.recent, 1e-9)
        assertEquals(LongTermTrend.Direction.LOWER, t.direction)
        assertEquals(0, t.tinnitusFreeWeeks)
        // a single 0 day is not a tinnitus-free week
        val oneZero = four + week(28, listOf(0, 3, 3))
        assertEquals(0, Progress.trend(Progress.weeks(oneZero, zone))!!.tinnitusFreeWeeks)
        // two full weeks of 0 (≥ 4 ratings each) → 2 self-reported tinnitus-free weeks
        val free = four + week(28, listOf(0, 0, 0, 0)) + week(35, listOf(0, 0, 0, 0, 0))
        assertEquals(2, Progress.trend(Progress.weeks(free, zone))!!.tinnitusFreeWeeks)
        // small changes are "about the same"
        val flat = week(0, listOf(5, 5, 5)) + week(7, listOf(5, 5, 5)) + week(14, listOf(5, 4, 5)) + week(21, listOf(5, 4, 5))
        assertEquals(LongTermTrend.Direction.STABLE, Progress.trend(Progress.weeks(flat, zone))!!.direction)
    }

    @Test fun csvExportHasOneRowPerEntryAndEscapesText() {
        val recs = listOf(
            daily(1, 4, bother = 2),
            pitch(0, 4123.45, sure = 2),
            ResponseRecord.Relief(monday + 2 * day, "Shower, \"my\" mix", 10, 6, 3, 4, 5),
        )
        val csv = HistoryExport.csv(recs, zone)
        val lines = csv.trimEnd().lines()
        assertEquals(4, lines.size)
        assertEquals(HistoryExport.COLUMNS.joinToString(","), lines[0])
        assertTrue(lines[1].startsWith("pitch,2026-10-05,12:00,"))
        assertTrue(lines[1].contains(",4123.5,3afc,2,"))
        assertTrue(lines[2].startsWith("daily,d1,"))
        assertTrue(lines[2].contains(",4,yes,2,"))
        assertTrue(lines[3].contains("\"Shower, \"\"my\"\" mix\""))
        lines.drop(1).forEach { l ->
            // every row has the same number of columns (outside quotes)
            var cols = 1; var q = false
            for (ch in l) { if (ch == '"') q = !q else if (ch == ',' && !q) cols++ }
            assertEquals(HistoryExport.COLUMNS.size, cols)
        }
    }

    @Test fun newFieldsRoundTripAndOldLinesStillLoad() {
        val recs = listOf(pitch(0, 4000.0, sure = 3), daily(1, 4, unmasked = true, bother = 7), daily(2, 5, unmasked = null))
        assertEquals(recs, ResponseCodec.decodeAll(recs.joinToString("\n") { ResponseCodec.encode(it) }))
        val old = "DAILY\tat=1\tday=2026-01-01\trating=5\nPITCH\tat=2\tf=4000.0\tmethod=manual"
        val back = ResponseCodec.decodeAll(old)
        assertEquals(null, (back[0] as ResponseRecord.Daily).unmasked)
        assertEquals(null, (back[1] as ResponseRecord.Pitch).sure)
    }
}
