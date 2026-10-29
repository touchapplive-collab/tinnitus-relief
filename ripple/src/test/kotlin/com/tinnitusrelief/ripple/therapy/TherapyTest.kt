package com.tinnitusrelief.ripple.therapy

import com.tinnitusrelief.ripple.RippleBand
import com.tinnitusrelief.ripple.RippleMetrics
import com.tinnitusrelief.ripple.engine.Arm
import com.tinnitusrelief.ripple.engine.Blocker
import com.tinnitusrelief.ripple.engine.EndReason
import com.tinnitusrelief.ripple.engine.NullSink
import com.tinnitusrelief.ripple.engine.InlineSource
import com.tinnitusrelief.ripple.engine.OutputCalibration
import com.tinnitusrelief.ripple.engine.PitchConfidence
import com.tinnitusrelief.ripple.engine.PitchEstimate
import com.tinnitusrelief.ripple.engine.RippleEngine
import com.tinnitusrelief.ripple.engine.RippleSafetyLimits
import com.tinnitusrelief.ripple.engine.SafetyLedger
import com.tinnitusrelief.ripple.engine.ScreeningAnswers
import com.tinnitusrelief.ripple.engine.SessionRecord
import com.tinnitusrelief.ripple.engine.StereoMix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class TherapyTest {
    private val limits = RippleSafetyLimits()
    private val day0 = 20_000L
    private val ms0 = day0 * 86_400_000L + 10 * 3_600_000L
    private val okScreen = ScreeningAnswers(true, false, false, false, false, false, false)
    private val pitch = PitchEstimate(5040.0, PitchConfidence.HIGH)
    private val plan = StudyPlan(day0, Arm.SHAM, ms0)
    private val ready = TherapyState(okScreen, ms0, 0.0, true, true, 4756.828460010884, plan)

    @Test fun planFollowsTheTrialTimeline() {
        assertEquals(126, plan.totalDays)
        assertEquals(PhaseKind.NOT_STARTED, plan.phase(day0 - 1).kind)
        assertEquals(PlanPhase(PhaseKind.LISTENING, 1, Arm.SHAM, 0, 42), plan.phase(day0))
        assertEquals(PlanPhase(PhaseKind.LISTENING, 1, Arm.SHAM, 41, 42), plan.phase(day0 + 41))
        assertEquals(PhaseKind.WASHOUT, plan.phase(day0 + 42).kind)
        assertEquals(null, plan.phase(day0 + 50).arm)
        assertEquals(PlanPhase(PhaseKind.LISTENING, 2, Arm.ACTIVE, 0, 42), plan.phase(day0 + 63))
        assertEquals(PhaseKind.WASHOUT, plan.phase(day0 + 105).kind)
        assertEquals(PhaseKind.FINISHED, plan.phase(day0 + 126).kind)
        assertFalse(plan.armsVisible(day0 + 125))
        assertTrue(plan.armsVisible(day0 + 126))
        assertTrue(plan.copy(unblindedAtMs = ms0).armsVisible(day0))
        // random order is balanced
        val r = java.util.Random(1)
        val active = (0 until 2000).count { StudyPlan.create(day0, r, ms0).firstArm == Arm.ACTIVE }
        assertTrue("$active", active in 900..1100)
    }

    @Test fun therapyGateUsesThePlanAndHidesNothingFromTheSafetyGate() {
        // listening day of period 1 (sham first): band = C1 = B3; audible → not authorised + no calibration
        val g = TherapyGate.evaluate(ready, pitch, SafetyLedger(), limits, ms0, day0, rehearsal = false, currentRoute = "wired")
        assertEquals(RippleBand.B3, g.prescription!!.band)
        assertEquals(Arm.SHAM, g.prescription!!.arm)
        assertTrue(Blocker.AUDIBLE_PLAYBACK_NOT_AUTHORIZED in g.blockers)
        assertTrue(Blocker.NO_CALIBRATION in g.blockers)
        // period 2 is active → A1 = B5
        assertEquals(RippleBand.B5, TherapyGate.evaluate(ready, pitch, SafetyLedger(), limits, ms0, day0 + 63, false).prescription!!.band)
        assertTrue(Blocker.PLAN_WASHOUT in TherapyGate.evaluate(ready, pitch, SafetyLedger(), limits, ms0, day0 + 45, false).blockers)
        assertTrue(Blocker.PLAN_FINISHED in TherapyGate.evaluate(ready, pitch, SafetyLedger(), limits, ms0, day0 + 200, false).blockers)
        assertTrue(Blocker.NO_PLAN in TherapyGate.evaluate(ready.copy(plan = null), pitch, SafetyLedger(), limits, ms0, day0, false).blockers)
        // a calibration record still does not unlock sound in this build
        val cal = OutputCalibration("Pixel", "X", "wired", ms0, "lab", -12.0, 70.0)
        val g2 = TherapyGate.evaluate(ready.copy(calibrations = listOf(cal)), pitch, SafetyLedger(), limits, ms0, day0, false, currentRoute = "wired")
        assertEquals(listOf(Blocker.AUDIBLE_PLAYBACK_NOT_AUTHORIZED), g2.blockers)
    }

    @Test fun rehearsalIsSilentAndAllowedWithoutSoundChecks() {
        val st = ready.copy(plan = null, a1Audible = null, a1CheckedMatchHz = null)
        val g = TherapyGate.evaluate(st, pitch, SafetyLedger(), limits, ms0, day0, rehearsal = true)
        assertTrue(g.blockers.toString(), g.allowed)
        assertTrue(g.rehearsal)
        assertFalse(g.request.audibleOutput)
        // rehearsal still requires screening and a usable pitch
        assertTrue(Blocker.SCREENING_INCOMPLETE in TherapyGate.evaluate(st.copy(screening = ScreeningAnswers()), pitch, SafetyLedger(), limits, ms0, day0, true).blockers)
        assertTrue(Blocker.PITCH_LOW_CONFIDENCE in TherapyGate.evaluate(st, pitch.copy(confidence = PitchConfidence.LOW), SafetyLedger(), limits, ms0, day0, true).blockers)
        // and runs on a silent engine
        val sink = NullSink()
        val e = RippleEngine(48_000, limits, sink) { c, s -> InlineSource(c, s) }
        assertTrue(e.start(g, 1))
        repeat(100) { e.process(480) }
        assertTrue(sink.frames > 0 && sink.peak > 0f)
    }

    @Test fun audibilityAnswerIsTiedToTheMatchTone() {
        assertEquals(true, ready.a1AudibleFor(5040.0))
        assertEquals(null, ready.a1AudibleFor(4000.0)) // different match tone → must be re-checked
        val tones = AudibilityCheck.tonesFor(RippleBand.B5, 0.0)
        assertEquals(listOf(4000, 4757, 5657, 6727, 8000), tones.map { Math.round(it.hz).toInt() })
        assertEquals(null, AudibilityCheck.result(tones, mapOf(tones[0].hz to true)))
        assertEquals(true, AudibilityCheck.result(tones, tones.associate { it.hz to true }))
        assertEquals(false, AudibilityCheck.result(tones, tones.associate { it.hz to (it.hz < 7000) }))
    }

    @Test fun hearingCheckSequencesFollowTheCorrectionCurve() {
        val s0 = HearingCheck.sequence(0.0); val s15 = HearingCheck.sequence(15.0)
        assertEquals(17, s15.size)
        assertTrue(s0.all { it.gainDb == 0.0 })
        assertEquals(0.0, s15.first().gainDb, 1e-9)
        assertEquals(15.0 * 8 / 9, s15[12].gainDb, 1e-9) // 8 kHz
        assertEquals(15.0, s15.last().gainDb, 1e-9)
    }

    @Test fun toneRendererLevelsRampsAndFrequency() {
        val sr = 48_000
        val x = ToneSequenceRenderer.render(listOf(CheckTone(1000.0, 0.0), CheckTone(8000.0, 6.0)), sr, 1000, 1000)
        assertEquals(4 * sr, x.size)
        fun rmsDb(a: Int, b: Int): Double { var s = 0.0; for (i in a until b) s += x[i] * x[i].toDouble(); return 10 * log10(s / (b - a)) }
        assertEquals(-40.0, rmsDb(sr / 10, sr - sr / 10), 0.05)
        assertEquals(-34.0, rmsDb(2 * sr + sr / 10, 3 * sr - sr / 10), 0.05)
        assertTrue(abs(x[0]) < 1e-6 && abs(x[sr - 1]) < 1e-3)
        assertTrue((sr until 2 * sr).all { x[it] == 0f })
        val ps = RippleMetrics.powerSpectrum(x.copyOfRange(0, sr), 1 shl 16)
        val peakBin = ps.indices.maxBy { ps[it] }
        assertEquals(1000.0, peakBin * sr.toDouble() / (1 shl 16), 1.0)
    }

    @Test fun outcomesUseUnmaskedWindowsAndStayBlinded() {
        val ratings = ArrayList<Pair<Long, Int>>()
        for (d in -7L..125L) ratings += (day0 + d) to when {
            d <= 0 -> 6
            d in 35..41 -> 4
            d in 56..62 -> 5
            d in 98..104 -> 6
            d in 119..125 -> 6
            else -> 5
        }
        val ledger = SafetyLedger().withSession(SessionRecord(ms0 + 86_400_000, ms0 + 86_400_000 + 3_600_000, 3_600_000, EndReason.LIMIT_REACHED, null, RippleBand.B3, Arm.SHAM, 0.0, -20.0))
        val dayOf = { ms: Long -> ms / 86_400_000L }
        val blinded = PlanOutcome.summarize(plan, ratings, ledger, day0 + 110, dayOf)
        assertEquals(null, blinded[0].arm)
        assertEquals(6.0, blinded[0].startMean!!, 1e-9)
        assertEquals(4.0, blinded[0].endMean!!, 1e-9)
        assertEquals(-2.0, blinded[0].change!!, 1e-9)
        assertEquals(-1.0, blinded[0].persisting!!, 1e-9)
        assertEquals(1, blinded[0].sessions); assertEquals(60L, blinded[0].listenedMinutes)
        assertEquals(null, blinded[1].washoutEndMean) // not reached yet
        val done = PlanOutcome.summarize(plan, ratings, ledger, day0 + 126, dayOf)
        assertEquals(Arm.SHAM, done[0].arm); assertEquals(Arm.ACTIVE, done[1].arm)
        // fewer than 3 ratings in a window → no value
        assertEquals(null, PlanOutcome.summarize(plan, listOf((day0 to 5)), ledger, day0 + 126, dayOf)[0].startMean)
    }

    @Test fun stateAndCalibrationRoundTrip() {
        val cal = OutputCalibration("Pixel 8", "Sony WH", "bt:A2DP", ms0, "Acoustics Lab A", -14.5, 72.3)
        val s = ready.copy(plan = plan.copy(unblindedAtMs = ms0 + 5), calibrations = listOf(cal), hearingProfileDb = 15.0)
        assertEquals(s, TherapyState.decode(s.encode()))
        assertEquals(cal, CalibrationCodec.decode(CalibrationCodec.encode(cal)))
        assertEquals(null, CalibrationCodec.decode("device:x;route:y")) // incomplete
        assertEquals(null, CalibrationCodec.decode(CalibrationCodec.encode(cal.copy(maxGainDb = 3.0)))) // gain above full scale
        assertEquals(TherapyState(), TherapyState.decode("garbage\nadult=maybe"))
    }

    @Test fun monoGoesEquallyToBothEars() {
        val mono = floatArrayOf(0.1f, -0.2f, 0.3f)
        val st = FloatArray(6) { 0.01f }
        StereoMix.addMonoToStereo(mono, 3, st, 0.5f)
        val want = listOf(0.06f, 0.06f, -0.09f, -0.09f, 0.16f, 0.16f)
        assertTrue(st.toList().zip(want).all { (a, b) -> abs(a - b) < 1e-6 })
        assertEquals(0.0, sqrt(0.0), 0.0)
    }
}
