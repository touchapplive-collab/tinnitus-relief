package com.tinnitusrelief.app

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinnitusrelief.app.ripple.RippleVoice
import com.tinnitusrelief.app.ripple.SessionPhase
import com.tinnitusrelief.ripple.engine.Blocker
import com.tinnitusrelief.ripple.engine.EndReason
import com.tinnitusrelief.ripple.engine.Interruption
import com.tinnitusrelief.ripple.engine.OutputAuthorization
import com.tinnitusrelief.ripple.engine.ScreeningAnswers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test on a device or emulator (CI: emulator without audio). Drives the real app UI through the
 * spectral-ripple pages and a complete silent rehearsal. Audible therapy must stay blocked.
 */
@RunWith(AndroidJUnit4::class)
class RippleFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int, vararg a: Any) = ctx.getString(id, *a)
    private val graph get() = ctx.graph

    /** Each test starts from empty ripple files (other app data is untouched). */
    @Before fun cleanRippleState() {
        java.io.File(ctx.filesDir, "ripple").deleteRecursively()
        kotlinx.coroutines.runBlocking { graph.ripple.load() }
    }

    private fun waitFor(text: String, ms: Long = 10_000) = rule.waitUntil(ms) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun silentRehearsalEndToEndAndTherapyBlocked() {
        // preconditions through the app's own API (same calls the pitch pages make)
        rule.runOnUiThread {
            graph.acknowledgeLab()
            graph.savePitch(5040.0, "manual")
        }
        rule.waitUntil(5_000) { graph.tracker.records.value.isNotEmpty() }
        rule.runOnUiThread { graph.setPitchConfidence(3) } // one "very sure" estimate → medium confidence
        rule.waitForIdle()

        // open Lab → Spectral ripple
        rule.onAllNodesWithText(s(R.string.tab_lab))[0].performClick()
        rule.onAllNodesWithText(s(R.string.rp_title))[1].performScrollTo().performClick()
        waitFor(s(R.string.rp_disabled_banner))

        // audible session is blocked
        rule.onNodeWithText(s(R.string.rp_start_session)).performScrollTo().performClick()
        waitFor(s(R.string.rp_cannot_start))
        rule.onNodeWithText("• " + s(R.string.rp_b_not_authorized)).assertExists()
        rule.activity.onBackPressedDispatcher.let { rule.runOnUiThread { it.onBackPressed() } }

        // safety questions
        rule.onNodeWithText(s(R.string.rp_screening)).performScrollTo().performClick()
        waitFor(s(R.string.rp_q_adult))
        rule.onAllNodesWithText(s(R.string.rp_yes))[0].performScrollTo().performClick()
        for (i in 1..6) rule.onAllNodesWithText(s(R.string.rp_no))[i].performScrollTo().performClick()
        rule.onNodeWithTag("rp_screening_save").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(ScreeningAnswers(true, false, false, false, false, false, false), graph.ripple.state.value.screening)

        // silent rehearsal: pre rating → run → stop → report
        rule.onNodeWithText(s(R.string.rp_rehearsal)).performScrollTo().performClick()
        waitFor(s(R.string.rp_rehearsal_banner))
        rule.onNodeWithTag("rp_prepare").performScrollTo().performClick()
        waitFor(s(R.string.rp_pre_note))
        rule.onAllNodes(hasText("5"))[0].performClick()
        rule.onNodeWithTag("rp_begin").performScrollTo().performClick()
        rule.waitUntil(5_000) { graph.ripple.session.value?.phase == SessionPhase.RUNNING }
        rule.waitUntil(15_000) { (graph.ripple.session.value?.elapsedMs ?: 0) >= 3_000 }
        rule.onNodeWithTag("rp_remaining").assertExists()
        rule.onNodeWithTag("rp_stop").performScrollTo().performClick()
        rule.waitUntil(5_000) { graph.ripple.session.value?.phase == SessionPhase.POST }
        assertEquals(EndReason.USER_STOP, graph.ripple.session.value?.endReason)
        waitFor(s(R.string.rp_after_question))
        rule.onAllNodes(hasText("5"))[0].performScrollTo().performClick()
        rule.onNodeWithTag("rp_submit").performScrollTo().performClick()
        rule.waitUntil(5_000) { graph.ripple.session.value == null }
        val reh = graph.ripple.rehearsalLedger.value
        assertEquals(1, reh.sessions.size)
        assertTrue(reh.sessions[0].audioMs >= 3_000)
        assertEquals(5, reh.sessions[0].report?.after)
        // rehearsals never enter the therapy ledger or the daily ratings
        assertEquals(0, graph.ripple.ledger.value.sessions.size)

        // therapy remains blocked by the authorisation layer on the device
        val g = graph.ripple.evaluate(rehearsal = false)
        assertTrue(Blocker.AUDIBLE_PLAYBACK_NOT_AUTHORIZED in g.blockers)
        assertFalse(OutputAuthorization.AUDIBLE_PLAYBACK_AUTHORIZED)
        assertEquals(null, RippleVoice.open(graph.engine.sampleRate, graph.ripple.limits, null))
    }

    @Test fun interruptionEndsARehearsal() {
        rule.runOnUiThread {
            graph.ripple.saveScreening(ScreeningAnswers(true, false, false, false, false, false, false))
            graph.savePitch(5040.0, "manual")
        }
        rule.waitUntil(5_000) { graph.tracker.records.value.isNotEmpty() }
        rule.runOnUiThread { graph.setPitchConfidence(3) }
        rule.waitForIdle()
        val g = graph.ripple.evaluate(rehearsal = true)
        assertTrue(g.blockers.toString(), g.allowed)
        rule.runOnUiThread { graph.ripple.prepare(rehearsal = true); graph.ripple.begin(6) }
        rule.waitUntil(5_000) { graph.ripple.session.value?.phase == SessionPhase.RUNNING }
        rule.waitUntil(10_000) { (graph.ripple.session.value?.elapsedMs ?: 0) >= 2_000 }
        rule.runOnUiThread { graph.ripple.interrupt(Interruption.BECOMING_NOISY) }
        rule.waitUntil(5_000) { graph.ripple.session.value?.phase == SessionPhase.POST }
        assertEquals(EndReason.INTERRUPTED, graph.ripple.session.value?.endReason)
        assertEquals(Interruption.BECOMING_NOISY, graph.ripple.session.value?.interruption)
        // never resumes by itself …
        Thread.sleep(1_500)
        assertEquals(SessionPhase.POST, graph.ripple.session.value?.phase)
        // … but the user may continue it, inside the same 60 minutes
        var resumed = false
        rule.runOnUiThread { resumed = graph.ripple.resume() }
        assertTrue(resumed)
        rule.waitUntil(5_000) { graph.ripple.session.value?.phase == SessionPhase.RUNNING }
        assertTrue(graph.ripple.session.value!!.allowedMs < 60 * 60_000L)
        rule.waitUntil(10_000) { (graph.ripple.session.value?.elapsedMs ?: 0) >= 1_000 }
        rule.runOnUiThread { graph.ripple.stop() }
        rule.waitUntil(5_000) { graph.ripple.session.value?.phase == SessionPhase.POST }
        rule.runOnUiThread { graph.ripple.submitReport(6, emptySet()) }
        val l = graph.ripple.rehearsalLedger.value
        assertEquals(2, l.sessions.size)
        assertEquals(l.sessions[0].startMs, l.sessions[1].resumedFromStartMs)
        assertEquals(6, l.sessions[1].report?.before)
        // a symptom report locks (rehearsal ledger here; the therapy ledger uses the same rule)
        assertEquals(null, l.lock)
    }
}
