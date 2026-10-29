package com.tinnitusrelief.ripple.engine

import com.tinnitusrelief.ripple.RippleBand
import com.tinnitusrelief.ripple.RippleConfig
import com.tinnitusrelief.ripple.RippleMetrics
import com.tinnitusrelief.ripple.RippleModulation
import com.tinnitusrelief.ripple.RippleSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** G3 engine and safety interlocks. No audio device is used anywhere. */
class RippleEngineTest {

    private val limits = RippleSafetyLimits()
    private val okScreening = ScreeningAnswers(true, false, false, false, false, false, false)
    private val pitch = PitchEstimate(5040.0, PitchConfidence.MEDIUM)
    private fun req(
        screening: ScreeningAnswers = okScreening, p: PitchEstimate? = pitch, a1: Boolean? = true, arm: Arm = Arm.ACTIVE,
        profile: Double = 0.0, audible: Boolean = false, resume: Boolean = false,
    ) = StartRequest(screening, p, a1, arm, profile, audible, resume = resume)

    private val day = 24 * 3_600_000L
    private val t0 = 1_800_000_000_000L
    private fun gate(r: StartRequest = req(), l: SafetyLedger = SafetyLedger(), now: Long = t0) = RippleGate.evaluate(r, l, limits, now)
    private fun blockers(r: StartRequest = req(), l: SafetyLedger = SafetyLedger(), now: Long = t0) = gate(r, l, now).blockers.toSet()

    private fun session(start: Long, minutes: Long, reason: EndReason = EndReason.USER_STOP, report: SessionReport? = SessionReport(5, 5)) =
        SessionRecord(start, start + minutes * 60_000, minutes * 60_000, reason, if (reason == EndReason.INTERRUPTED) Interruption.PHONE_CALL else null,
            RippleBand.B5, Arm.ACTIVE, 0.0, -20.0, null, report)

    // ------------------------------------------------------------- output authorisation

    @Test fun audiblePlaybackIsImpossibleInG3() {
        assertFalse(OutputAuthorization.AUDIBLE_PLAYBACK_AUTHORIZED)
        val audibleSink = object : RippleSink { override val audible = true; override fun write(buffer: FloatArray, frames: Int) {} }
        var threw = false
        try { RippleEngine(48_000, limits, audibleSink) } catch (_: IllegalArgumentException) { threw = true }
        assertTrue("engine must refuse an audible sink", threw)
        val b = blockers(req(audible = true))
        assertTrue(Blocker.AUDIBLE_PLAYBACK_NOT_AUTHORIZED in b && Blocker.NO_CALIBRATION in b)
        // even a calibration record does not unlock audible output in G3
        val cal = OutputCalibration("Pixel", "X", "wired", t0, "lab", -10.0, 70.0)
        val r = gate(req(audible = true).copy(calibration = cal, currentRoute = "wired"))
        assertTrue(Blocker.AUDIBLE_PLAYBACK_NOT_AUTHORIZED in r.blockers)
        // and an engine with a silent sink refuses a request that claims audible output
        val e = RippleEngine(48_000, limits, NullSink(), sourceFactory = { c, s -> InlineSource(c, s) })
        assertFalse(e.start(r, 1))
    }

    @Test fun calibrationMustMatchRouteAndLimit() {
        val cal = OutputCalibration("Pixel", "X", "wired", t0, "lab", -10.0, 76.0)
        val b1 = blockers(req(audible = true).copy(calibration = cal, currentRoute = "bluetooth"))
        assertTrue(Blocker.ROUTE_NOT_CALIBRATED in b1)
        val b2 = blockers(req(audible = true).copy(calibration = cal, currentRoute = "wired"))
        assertTrue(Blocker.CALIBRATION_ABOVE_LIMIT in b2)
    }

    // ------------------------------------------------------------- screening, pitch, bands, profiles

    @Test fun baselineRequestIsAllowed() {
        val g = gate()
        assertTrue(g.blockers.toString(), g.allowed)
        assertEquals(RippleBand.B5, g.prescription!!.band)
        assertEquals(60 * 60_000L, g.allowedMs)
        assertEquals(limits.firstSessionLevelDb, g.startLevelCapDb, 0.0)
    }

    @Test fun screeningExcludesOrBlocks() {
        assertTrue(Blocker.SCREENING_INCOMPLETE in blockers(req(screening = okScreening.copy(pulsatile = null))))
        val cases = listOf(
            okScreening.copy(adult = false), okScreening.copy(worseWithModerateSounds = true), okScreening.copy(soundSensitivityOrPain = true),
            okScreening.copy(pulsatile = true), okScreening.copy(suddenHearingChange = true), okScreening.copy(newOneSidedTinnitus = true),
            okScreening.copy(earPainDischargeOrDizziness = true),
        )
        for (c in cases) assertTrue(c.toString(), Blocker.SCREENING_EXCLUDED in blockers(req(screening = c)))
        // an exclusion is final even when other answers are missing
        assertTrue(Screening.evaluate(ScreeningAnswers(pulsatile = true)) is ScreeningResult.Excluded)
    }

    @Test fun pitchAndAudibilityGates() {
        assertTrue(Blocker.NO_PITCH in blockers(req(p = null)))
        assertTrue(Blocker.PITCH_LOW_CONFIDENCE in blockers(req(p = pitch.copy(confidence = PitchConfidence.LOW))))
        assertTrue(Blocker.AUDIBILITY_UNKNOWN in blockers(req(a1 = null)))
        // 5.04 kHz: A1 B5 / A2 B4 / C1 B3 / C2 B2
        assertEquals(RippleBand.B5, gate(req(arm = Arm.ACTIVE)).prescription!!.band)
        assertEquals(RippleBand.B3, gate(req(arm = Arm.SHAM)).prescription!!.band)
        assertEquals(RippleBand.B4, gate(req(arm = Arm.ACTIVE, a1 = false)).prescription!!.band)
        assertEquals(RippleBand.B2, gate(req(arm = Arm.SHAM, a1 = false)).prescription!!.band)
        // 4 kHz has no A2: an inaudible A1 blocks
        assertTrue(Blocker.NO_AUDIBLE_BAND in blockers(req(p = PitchEstimate(4000.0, PitchConfidence.HIGH), a1 = false)))
        // 2.4 kHz: A2 exists but no C2 → sham keeps C1
        assertEquals(RippleBand.B5, gate(req(p = PitchEstimate(2400.0, PitchConfidence.HIGH), a1 = false, arm = Arm.SHAM)).prescription!!.band)
    }

    @Test fun hearingProfiles30And45AreLocked() {
        assertTrue(gate(req(profile = 0.0)).allowed)
        assertTrue(gate(req(profile = 15.0)).allowed)
        assertTrue(Blocker.HEARING_PROFILE_LOCKED in blockers(req(profile = 30.0)))
        assertTrue(Blocker.HEARING_PROFILE_LOCKED in blockers(req(profile = 45.0)))
        assertTrue(Blocker.HEARING_PROFILE_INVALID in blockers(req(profile = 20.0)))
    }

    // ------------------------------------------------------------- session limits

    @Test fun breakDailyCapAndResume() {
        val l1 = SafetyLedger().withSession(session(t0 - 90 * 60_000, 30))
        assertTrue(Blocker.BREAK_TOO_SHORT in blockers(l = l1, now = t0 - 30 * 60_000))
        assertTrue(gate(l = l1, now = t0).allowed)
        // 2 × 60 min in the last 24 h → daily cap
        val l2 = SafetyLedger().withSession(session(t0 - 5 * 3_600_000, 60)).withSession(session(t0 - 3 * 3_600_000, 60))
        assertTrue(Blocker.DAILY_CAP_REACHED in blockers(l = l2))
        assertTrue(gate(l = l2, now = t0 + day).allowed)
        // 90 min used → next session limited to 30 min
        val l3 = SafetyLedger().withSession(session(t0 - 5 * 3_600_000, 60)).withSession(session(t0 - 3 * 3_600_000, 30))
        assertEquals(30 * 60_000L, gate(l = l3).allowedMs)
        // interrupted after 20 min: may resume within 10 min, for the remaining 40 min, without a break or report
        val li = SafetyLedger().withSession(session(t0 - 25 * 60_000, 20, EndReason.INTERRUPTED, report = null))
        val r = gate(req(resume = true), li, t0)
        assertTrue(r.blockers.toString(), r.allowed)
        assertEquals(40 * 60_000L, r.allowedMs)
        assertTrue(Blocker.NOTHING_TO_RESUME in blockers(req(resume = true), li, t0 + 11 * 60_000))
        // a non-resume start after an interruption needs the report and the break
        val b = blockers(req(), li, t0)
        assertTrue(Blocker.POST_SESSION_REPORT_MISSING in b && Blocker.BREAK_TOO_SHORT in b)
    }

    // ------------------------------------------------------------- stop rules

    @Test fun stopRulesLockAndUnlock() {
        val base = SafetyLedger().withSession(session(t0 - 3 * 3_600_000, 30, report = null))
        assertTrue(Blocker.POST_SESSION_REPORT_MISSING in blockers(l = base))
        // +1 point: no lock
        assertTrue(gate(l = base.withReport(SessionReport(4, 5), t0, limits)).allowed)
        // +2 points: locked for ≥ 24 h, lifted only with acknowledgement
        val w = base.withReport(SessionReport(4, 6), t0, limits)
        assertTrue(Blocker.LOCKED_WORSENING in blockers(l = w))
        assertEquals(w, w.clearLock(t0 + day - 1, adviceAcknowledged = true, clinicianCleared = false, limits = limits))
        assertEquals(w, w.clearLock(t0 + day, adviceAcknowledged = false, clinicianCleared = false, limits = limits))
        assertTrue(gate(l = w.clearLock(t0 + day, true, false, limits), now = t0 + day).allowed)
        // any symptom: locked until a clinician clears it, regardless of time
        val s = base.withReport(SessionReport(4, 4, setOf(AdverseSymptom.EAR_FULLNESS)), t0, limits)
        assertTrue(Blocker.LOCKED_SYMPTOMS in blockers(l = s))
        assertEquals(s, s.clearLock(t0 + 30 * day, adviceAcknowledged = true, clinicianCleared = false, limits = limits))
        assertTrue(gate(l = s.clearLock(t0 + day, true, true, limits), now = t0 + day).allowed)
        // a later worsening does not downgrade a symptom lock
        assertEquals(LockReason.SYMPTOMS, s.withReport(SessionReport(3, 6), t0, limits).lock!!.reason)
    }

    @Test fun ledgerRoundTrip() {
        val l = SafetyLedger()
            .withSession(session(t0, 30, EndReason.INTERRUPTED, report = null))
            .withSession(session(t0 + 3_600_000, 15, report = SessionReport(5, 7, setOf(AdverseSymptom.DIZZINESS, AdverseSymptom.EAR_PAIN))))
            .copy(lock = Lock(LockReason.SYMPTOMS, t0), lastConfirmedLevelDb = -22.5)
        assertEquals(l, SafetyLedger.decode(l.encode()))
    }

    // ------------------------------------------------------------- engine runtime

    private fun engine(sink: RippleSink, sr: Int = 48_000, clock: () -> Long = { t0 }) =
        RippleEngine(sr, limits, sink, clock) { c, s -> InlineSource(c, s) }

    @Test fun refusesToStartWithoutAnAllowedGate() {
        val e = engine(NullSink())
        assertFalse(e.start(gate(req(p = null)), 1))
        assertEquals(RippleEngine.State.IDLE, e.state)
    }

    @Test fun fadeInIsFiveSecondRaisedCosineFromSilenceAtTheCappedLevel() {
        val sr = 48_000
        val cap = CaptureSink(sr * 8)
        val e = engine(cap, sr)
        val g = gate()
        assertTrue(e.start(g, 7, startLevelDb = 0.0)) // request 0 dB: capped at first-session level −40 dB
        assertEquals(limits.firstSessionLevelDb, e.levelDb, 0.0)
        repeat(8 * sr / 480) { e.process(480) }
        // reference: the same segments without engine gain
        val ref = RippleSequence(RippleConfig(RippleBand.B5, RippleModulation.AMPLITUDE, 0.0, sr), 7)
        val raw = ref.next().samples + ref.next().samples
        val lin = RippleEngine.dbToLin(limits.firstSessionLevelDb)
        var worst = 0.0
        for (i in 0 until 8 * sr) {
            val expectedEnv = if (i >= 5 * sr) 1.0 else 0.5 * (1 - cos(PI * i / (5.0 * sr)))
            worst = maxOf(worst, abs(cap.data[i] - raw[i] * expectedEnv * lin))
        }
        assertTrue("fade-in deviation $worst", worst < 1e-7)
        assertTrue(cap.data[0] == 0f)
    }

    @Test fun levelNeverRisesByItselfAndNeverAboveMax() {
        val e = engine(NullSink())
        val ledger = SafetyLedger().confirmLevel(-20.0)
        val g = gate(l = ledger)
        assertEquals(-20.0, g.startLevelCapDb, 0.0)
        assertTrue(e.start(g, 1, startLevelDb = -10.0))
        assertEquals(-20.0, e.levelDb, 0.0) // never above the last confirmed level
        assertEquals(-20.0, e.setLevel(-12.0, userInitiated = false), 0.0) // automatic raise refused
        assertEquals(-25.0, e.setLevel(-25.0, userInitiated = false), 0.0) // automatic lowering allowed
        assertEquals(-15.0, e.setLevel(-15.0, userInitiated = true), 0.0) // user raise allowed
        assertEquals(limits.maxLevelDb, e.setLevel(0.0, userInitiated = true), 0.0) // capped at −6 dB
    }

    @Test fun sessionEndsAtTheLimitWithAFade() {
        val sr = 48_000
        var now = t0
        val sink = NullSink()
        val e = RippleEngine(sr, limits, sink, { now }) { c, s -> InlineSource(c, s) }
        var rec: SessionRecord? = null
        e.onSessionEnded = { rec = it }
        val l = SafetyLedger().withSession(session(t0 - 5 * 3_600_000, 60)).withSession(session(t0 - 3 * 3_600_000, 58))
        val g = gate(l = l)
        assertEquals(2 * 60_000L, g.allowedMs)
        assertTrue(e.start(g, 3))
        var frames = 0L
        while (e.state != RippleEngine.State.STOPPED && frames < 4L * 60 * sr) { e.process(960); frames += 960; now += 20 }
        assertEquals(EndReason.LIMIT_REACHED, rec!!.reason)
        assertTrue("audio ${rec!!.audioMs} ms", abs(rec!!.audioMs - 120_000) <= 20)
        assertEquals(0L, sink.nonFinite)
        assertEquals(0L, e.guardClamps)
    }

    @Test fun everyInterruptionFadesOutFastAndNeverResumes() {
        val sr = 48_000
        for (i in Interruption.entries) {
            val cap = CaptureSink(sr * 12)
            val e = engine(cap, sr)
            var rec: SessionRecord? = null
            e.onSessionEnded = { rec = it }
            assertTrue(e.start(gate(), 5, -20.0))
            repeat(6 * sr / 480) { e.process(480) }
            val at = cap.size
            e.interrupt(i)
            repeat(sr / 480) { e.process(480) } // 1 s more
            val fadeEnd = at + (limits.interruptFadeMs * sr / 1000).toInt()
            for (k in fadeEnd + 1 until cap.size) assertTrue("$i silent after fade", cap.data[k] == 0f)
            assertEquals(RippleEngine.State.STOPPED, e.state)
            assertEquals(EndReason.INTERRUPTED, rec!!.reason)
            assertEquals(i, rec!!.interruption)
            // no automatic resume: further processing stays silent and start() needs a new gate
            repeat(10) { e.process(480) }
            assertEquals(RippleEngine.State.STOPPED, e.state)
            assertFalse(e.start(gate(), 6))
        }
    }

    @Test fun userStopFadesOverOneSecond() {
        val sr = 48_000
        val cap = CaptureSink(sr * 10)
        val e = engine(cap, sr)
        assertTrue(e.start(gate(), 9, -20.0))
        repeat(6 * sr / 480) { e.process(480) }
        val at = cap.size
        e.stop()
        repeat(2 * sr / 480) { e.process(480) }
        val fade = (limits.userStopFadeMs * sr / 1000).toInt()
        assertTrue(cap.data.slice(at + fade + 1 until cap.size).all { it == 0f })
        assertTrue(cap.data.slice(at until at + fade / 2).any { it != 0f })
    }

    @Test fun underrunsOnlyOccurAtSegmentBoundariesAndDoNotClick() {
        val sr = 48_000
        // a source that withholds every second segment for 0.5 s (a starved render thread)
        class Starved(c: RippleConfig, s: Long) : SegmentSource {
            val seq = RippleSequence(c, s); var k = 0; var waitPolls = 0
            override fun poll(): FloatArray? {
                if (k % 2 == 1 && waitPolls < sr / 2) { waitPolls++; return null }
                waitPolls = 0; k++; return seq.next().samples
            }
            override fun close() {}
        }
        val cap = CaptureSink(sr * 20)
        val e = RippleEngine(sr, limits, cap, { t0 }) { c, s -> Starved(c, s) }
        assertTrue(e.start(gate(), 11, -20.0))
        repeat(20 * sr / 480) { e.process(480) }
        assertTrue("underruns ${e.underrunEvents}", e.underrunEvents >= 2)
        // same segments without starvation: the starved output must not contain any larger sample step
        val ref = CaptureSink(sr * 20)
        val r = RippleEngine(sr, limits, ref, { t0 }) { c, s -> InlineSource(c, s) }
        assertTrue(r.start(gate(), 11, -20.0))
        repeat(20 * sr / 480) { r.process(480) }
        fun maxStep(x: CaptureSink): Double { var m = 0.0; for (k in 1 until x.size) m = maxOf(m, abs(x.data[k] - x.data[k - 1]).toDouble()); return m }
        assertTrue("step ${maxStep(cap)} vs ${maxStep(ref)}", maxStep(cap) <= maxStep(ref) * 1.05)
        // every gap starts and ends at (near) zero amplitude: segments are ramped to zero at both ends
        var k = 1
        while (k < cap.size) {
            if (cap.data[k] == 0f && cap.data[k - 1] != 0f) assertTrue("gap entry ${cap.data[k - 1]}", abs(cap.data[k - 1]) < 1e-6)
            k++
        }
        assertTrue(cap.data.all { it.isFinite() })
    }

    @Test fun renderAheadThreadKeepsUpAndStopsCleanly() {
        val sr = 48_000
        var src: RenderAheadSource? = null
        val sink = NullSink()
        val e = RippleEngine(sr, limits, sink, { t0 }) { c, s -> RenderAheadSource(c, s).also { src = it } }
        assertTrue(e.start(gate(), 13, -20.0))
        val stats = PacedDriver(e, sr, 192, 8).run(12_000)
        assertTrue(stats.summary(), stats.glitches == 0)
        assertEquals(0, e.underrunEvents)
        assertTrue("pre-roll ${e.preRollFrames}", e.preRollFrames < sr) // first segment ready within 1 s
        e.stop(); repeat(100) { e.process(480) }
        assertEquals(RippleEngine.State.STOPPED, e.state)
        Thread.sleep(100)
        assertFalse("render thread stopped", src!!.alive)
        println("JVM pacing: " + stats.summary() + "; segment render max ${"%.0f".format(src!!.renderTimesMs.max())} ms")
    }

    @Test fun supportedSampleRates() {
        for (sr in listOf(44_100, 48_000, 96_000)) {
            val cap = CaptureSink(sr * 9)
            val e = RippleEngine(sr, limits, cap, { t0 }) { c, s -> InlineSource(c, s) }
            assertTrue(e.start(gate(), 17, -20.0))
            repeat(9 * sr / 441) { e.process(441) }
            // spectrum stays inside 1–16 kHz at every rate (second segment, 4–8 s: whole segment, natural ramps)
            val n = 1 shl 19
            val ps = RippleMetrics.powerSpectrum(cap.data.copyOfRange(4 * sr, 8 * sr), n)
            val total = ps.sum()
            val out = RippleMetrics.bandPower(ps, n, sr, 0.0, 950.0) + RippleMetrics.bandPower(ps, n, sr, 16050.0, sr / 2.0)
            assertTrue("$sr Hz: out-of-range ${10 * kotlin.math.log10(out / total)} dB", 10 * kotlin.math.log10(out / total) < -50)
            assertTrue(cap.data.all { it.isFinite() && abs(it) <= RippleEngine.GUARD })
        }
    }

    @Test fun sixtyMinuteSessionRunsToTheLimit() {
        val sr = 48_000
        var now = t0
        val sink = NullSink()
        val e = RippleEngine(sr, limits, sink, { now }) { c, s -> InlineSource(c, s) }
        var rec: SessionRecord? = null
        e.onSessionEnded = { rec = it }
        assertTrue(e.start(gate(), 19, limits.maxLevelDb))
        e.setLevel(limits.maxLevelDb, userInitiated = true)
        while (e.state != RippleEngine.State.STOPPED) { e.process(4800); now += 100 }
        assertEquals(EndReason.LIMIT_REACHED, rec!!.reason)
        assertTrue(abs(rec!!.audioMs - 3_600_000) <= 100)
        assertEquals(0L, sink.nonFinite)
        assertEquals(0L, e.guardClamps)
        assertTrue("peak ${sink.peak}", sink.peak < 0.25f) // ≤ −12 dBFS at the maximum level
    }

    @Test fun focusChangesMapToStopsAndNeverToResume() {
        assertEquals(Interruption.FOCUS_LOSS, InterruptionMapper.fromFocusChange(-1))
        assertEquals(Interruption.FOCUS_LOSS_TRANSIENT, InterruptionMapper.fromFocusChange(-2))
        assertEquals(Interruption.FOCUS_DUCK, InterruptionMapper.fromFocusChange(-3))
        assertEquals(null, InterruptionMapper.fromFocusChange(1))
    }
}
