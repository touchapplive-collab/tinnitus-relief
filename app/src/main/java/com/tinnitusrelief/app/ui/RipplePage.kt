package com.tinnitusrelief.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinnitusrelief.app.AppGraph
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.ripple.SessionPhase
import com.tinnitusrelief.app.ui.theme.LocalReliefColors
import com.tinnitusrelief.ripple.RippleBand
import com.tinnitusrelief.ripple.RippleBandTable
import com.tinnitusrelief.ripple.engine.AdverseSymptom
import com.tinnitusrelief.ripple.engine.Blocker
import com.tinnitusrelief.ripple.engine.EndReason
import com.tinnitusrelief.ripple.engine.LockReason
import com.tinnitusrelief.ripple.engine.PitchConfidence
import com.tinnitusrelief.ripple.engine.Screening
import com.tinnitusrelief.ripple.engine.ScreeningAnswers
import com.tinnitusrelief.ripple.engine.ScreeningResult
import com.tinnitusrelief.ripple.therapy.AudibilityCheck
import com.tinnitusrelief.ripple.therapy.PhaseKind
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

private enum class RipplePageId { HOME, SCREENING, PROFILE, AUDIBILITY, PLAN, SESSION, PROGRESS, SAFETY }

/**
 * Spectral ripple (experimental). One entry in the Lab; every step on its own page, built from the same
 * list rows and controls as the rest of the app. Therapy sessions stay blocked until audible playback is
 * authorised and calibrated; a silent rehearsal exercises the whole session flow without sound.
 */
@Composable
fun RipplePage(graph: AppGraph, onBack: () -> Unit, openPitch: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(RipplePageId.HOME) }
    var rehearsal by rememberSaveable { mutableStateOf(true) }
    BackHandler(enabled = page != RipplePageId.HOME) { page = RipplePageId.HOME }
    val home = { page = RipplePageId.HOME }
    when (page) {
        RipplePageId.HOME -> RippleHome(graph, onBack, openPitch) { target, isRehearsal -> rehearsal = isRehearsal; page = target }
        RipplePageId.SCREENING -> ScreeningPage(graph, home)
        RipplePageId.PROFILE -> ProfilePage(graph, home)
        RipplePageId.AUDIBILITY -> AudibilityPage(graph, home)
        RipplePageId.PLAN -> PlanPage(graph, home)
        RipplePageId.SESSION -> SessionPage(graph, rehearsal, home)
        RipplePageId.PROGRESS -> ProgressPage(graph, home)
        RipplePageId.SAFETY -> SafetyPage(graph, home)
    }
}

private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private fun dayText(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFmt)
private fun mmss(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
private fun bandText(b: RippleBand): String = "%.1f–%.1f kHz".format(b.lowHz / 1000, b.highHz / 1000)

@Composable
private fun RippleHome(graph: AppGraph, onBack: () -> Unit, openPitch: () -> Unit, open: (RipplePageId, Boolean) -> Unit) {
    val c = graph.ripple
    val st by c.state.collectAsStateWithLifecycle()
    val ledger by c.ledger.collectAsStateWithLifecycle()
    val session by c.session.collectAsStateWithLifecycle()
    val route by c.route.collectAsStateWithLifecycle()
    val pitchHz by graph.pitch.collectAsStateWithLifecycle()
    val records by graph.tracker.records.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { c.refreshRoute() }
    val pitch = remember(pitchHz, records) { c.pitchEstimate() }
    val therapyGate = remember(st, ledger, pitch, route) { c.evaluate(rehearsal = false) }
    val rehearsalGate = remember(st, pitch, route, session) { c.evaluate(rehearsal = true) }
    val warn = LocalReliefColors.current.warn

    LabPageColumn(stringResource(R.string.rp_title), onBack) {
        Text(stringResource(R.string.rp_disabled_banner), style = MaterialTheme.typography.bodyMedium, color = warn)
        Body(stringResource(R.string.rp_intro))

        session?.let { s ->
            RowGroup {
                ListRow(
                    stringResource(if (s.rehearsal) R.string.rp_rehearsal_running else R.string.rp_session_running),
                    subtitle = mmss(s.allowedMs - s.elapsedMs),
                    onClick = { open(RipplePageId.SESSION, s.rehearsal) },
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            ListHeader(stringResource(R.string.rp_before))
            RowGroup {
                val screening = Screening.evaluate(st.screening)
                ListRow(
                    stringResource(R.string.rp_screening),
                    subtitle = stringResource(
                        when (screening) {
                            is ScreeningResult.Eligible -> R.string.rp_screening_ok
                            is ScreeningResult.Excluded -> R.string.rp_screening_excluded
                            is ScreeningResult.Incomplete -> R.string.rp_not_done
                        }
                    ),
                    onClick = { open(RipplePageId.SCREENING, true) },
                )
                GroupDivider()
                val row = pitch?.let { RippleBandTable.forPitch(it.hz) }
                ListRow(
                    stringResource(R.string.rp_pitch),
                    subtitle = if (pitch == null) stringResource(R.string.rp_pitch_missing) else stringResource(
                        R.string.rp_pitch_value, formatHz(pitch.hz), formatHz(row!!.matchHz),
                        stringResource(
                            when (pitch.confidence) {
                                PitchConfidence.HIGH -> R.string.pitch_conf_high
                                PitchConfidence.MEDIUM -> R.string.pitch_conf_medium
                                PitchConfidence.LOW -> R.string.pitch_conf_low
                            }
                        ),
                    ),
                    onClick = openPitch,
                )
                GroupDivider()
                ListRow(
                    stringResource(R.string.rp_profile),
                    subtitle = if (st.hearingProfileChosen) stringResource(R.string.rp_profile_value, st.hearingProfileDb.toInt()) else stringResource(R.string.rp_not_done),
                    onClick = { open(RipplePageId.PROFILE, true) },
                )
                GroupDivider()
                val a1 = st.a1AudibleFor(pitch?.hz)
                ListRow(
                    stringResource(R.string.rp_audibility),
                    subtitle = stringResource(
                        when (a1) {
                            true -> R.string.rp_audibility_ok
                            false -> R.string.rp_audibility_alt
                            null -> R.string.rp_needs_sound
                        }
                    ),
                    onClick = { open(RipplePageId.AUDIBILITY, true) },
                )
                GroupDivider()
                ListRow(
                    stringResource(R.string.rp_output),
                    subtitle = (route?.label ?: "–") + " · " + stringResource(
                        if (st.calibrationFor(route?.id) != null) R.string.rp_calibrated else R.string.rp_not_calibrated
                    ),
                    onClick = { open(RipplePageId.SAFETY, true) },
                )
            }

            ListHeader(stringResource(R.string.rp_plan))
            RowGroup {
                ListRow(stringResource(R.string.rp_plan), subtitle = planSubtitle(graph), onClick = { open(RipplePageId.PLAN, true) })
            }

            ListHeader(stringResource(R.string.rp_sessions))
            RowGroup {
                ListRow(
                    stringResource(R.string.rp_start_session),
                    subtitle = if (therapyGate.allowed) stringResource(R.string.rp_ready) else blockerText(therapyGate.blockers.first()),
                    onClick = { open(RipplePageId.SESSION, false) },
                )
                GroupDivider()
                ListRow(
                    stringResource(R.string.rp_rehearsal),
                    subtitle = if (rehearsalGate.allowed) stringResource(R.string.rp_rehearsal_sub) else blockerText(rehearsalGate.blockers.first()),
                    onClick = { open(RipplePageId.SESSION, true) },
                )
            }

            ListHeader(stringResource(R.string.progress_title))
            RowGroup {
                ListRow(stringResource(R.string.rp_progress), subtitle = stringResource(R.string.rp_progress_sub, ledger.sessions.size), onClick = { open(RipplePageId.PROGRESS, true) })
                GroupDivider()
                ListRow(
                    stringResource(R.string.rp_safety),
                    subtitle = ledger.lock?.let { stringResource(R.string.rp_locked) } ?: stringResource(R.string.rp_safety_sub),
                    onClick = { open(RipplePageId.SAFETY, true) },
                )
            }
        }
        Muted(stringResource(R.string.rp_footer))
    }
}

@Composable
private fun planSubtitle(graph: AppGraph): String {
    val st by graph.ripple.state.collectAsStateWithLifecycle()
    val plan = st.plan ?: return stringResource(R.string.rp_plan_none)
    val ph = plan.phase(graph.ripple.today())
    return when (ph.kind) {
        PhaseKind.NOT_STARTED -> stringResource(R.string.rp_plan_starts, dayText(plan.startEpochDay))
        PhaseKind.LISTENING -> stringResource(R.string.rp_plan_listening, ph.period, ph.dayIndex + 1, ph.lengthDays)
        PhaseKind.WASHOUT -> stringResource(R.string.rp_plan_washout, ph.period, ph.dayIndex + 1, ph.lengthDays)
        PhaseKind.FINISHED -> stringResource(R.string.rp_plan_finished)
    }
}

@Composable
private fun blockerText(b: Blocker): String = stringResource(
    when (b) {
        Blocker.AUDIBLE_PLAYBACK_NOT_AUTHORIZED -> R.string.rp_b_not_authorized
        Blocker.NO_CALIBRATION, Blocker.ROUTE_NOT_CALIBRATED -> R.string.rp_b_no_calibration
        Blocker.CALIBRATION_ABOVE_LIMIT -> R.string.rp_b_calibration_limit
        Blocker.SCREENING_INCOMPLETE -> R.string.rp_b_screening
        Blocker.SCREENING_EXCLUDED -> R.string.rp_b_excluded
        Blocker.NO_PITCH -> R.string.rp_b_no_pitch
        Blocker.PITCH_LOW_CONFIDENCE -> R.string.rp_b_pitch_low
        Blocker.AUDIBILITY_UNKNOWN -> R.string.rp_b_audibility
        Blocker.NO_AUDIBLE_BAND -> R.string.rp_b_no_band
        Blocker.HEARING_PROFILE_LOCKED, Blocker.HEARING_PROFILE_INVALID -> R.string.rp_b_profile
        Blocker.LOCKED_SYMPTOMS -> R.string.rp_b_locked_symptoms
        Blocker.LOCKED_WORSENING -> R.string.rp_b_locked_worsening
        Blocker.POST_SESSION_REPORT_MISSING -> R.string.rp_b_report
        Blocker.BREAK_TOO_SHORT -> R.string.rp_b_break
        Blocker.DAILY_CAP_REACHED -> R.string.rp_b_daily
        Blocker.NOTHING_TO_RESUME -> R.string.rp_b_resume
        Blocker.NO_PLAN -> R.string.rp_b_no_plan
        Blocker.PLAN_NOT_STARTED -> R.string.rp_b_plan_not_started
        Blocker.PLAN_WASHOUT -> R.string.rp_b_washout
        Blocker.PLAN_FINISHED -> R.string.rp_b_plan_finished
    }
)

// ------------------------------------------------------------------ screening

@Composable
private fun ScreeningPage(graph: AppGraph, onBack: () -> Unit) {
    val st by graph.ripple.state.collectAsStateWithLifecycle()
    var a by remember { mutableStateOf(st.screening) }
    val yes = stringResource(R.string.rp_yes); val no = stringResource(R.string.rp_no)
    LabPageColumn(stringResource(R.string.rp_screening), onBack) {
        Body(stringResource(R.string.rp_screening_intro))
        @Composable fun q(text: Int, value: Boolean?, set: (Boolean) -> Unit) {
            Text(stringResource(text), style = MaterialTheme.typography.titleMedium)
            ChoiceRow(listOf(true, false), value, { if (it) yes else no }) { set(it) }
        }
        q(R.string.rp_q_adult, a.adult) { a = a.copy(adult = it) }
        q(R.string.rp_q_worse, a.worseWithModerateSounds) { a = a.copy(worseWithModerateSounds = it) }
        q(R.string.rp_q_sensitive, a.soundSensitivityOrPain) { a = a.copy(soundSensitivityOrPain = it) }
        q(R.string.rp_q_pulsatile, a.pulsatile) { a = a.copy(pulsatile = it) }
        q(R.string.rp_q_sudden, a.suddenHearingChange) { a = a.copy(suddenHearingChange = it) }
        q(R.string.rp_q_one_sided, a.newOneSidedTinnitus) { a = a.copy(newOneSidedTinnitus = it) }
        q(R.string.rp_q_ear, a.earPainDischargeOrDizziness) { a = a.copy(earPainDischargeOrDizziness = it) }
        when (val r = Screening.evaluate(a)) {
            is ScreeningResult.Excluded -> Text(stringResource(R.string.rp_screening_excluded_long), color = LocalReliefColors.current.warn)
            is ScreeningResult.Incomplete -> Muted(stringResource(R.string.rp_screening_incomplete, r.unanswered))
            ScreeningResult.Eligible -> Muted(stringResource(R.string.rp_screening_ok_long))
        }
        Button(onClick = { graph.ripple.saveScreening(a); onBack() }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("rp_screening_save")) {
            Text(stringResource(R.string.rp_save))
        }
    }
}

// ------------------------------------------------------------------ hearing profile

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfilePage(graph: AppGraph, onBack: () -> Unit) {
    val c = graph.ripple
    val st by c.state.collectAsStateWithLifecycle()
    LabPageColumn(stringResource(R.string.rp_profile), onBack) {
        Body(stringResource(R.string.rp_profile_intro))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (db in listOf(0.0, 15.0, 30.0, 45.0)) {
                val allowed = db in c.limits.allowedHearingProfilesDb
                Choice(stringResource(R.string.rp_profile_value, db.toInt()), st.hearingProfileChosen && st.hearingProfileDb == db, { c.setHearingProfile(db) }, enabled = allowed)
            }
        }
        Muted(stringResource(R.string.rp_profile_locked))
        OutlinedButton(onClick = {}, enabled = c.audibleAuthorized, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rp_profile_listen)) }
        Muted(stringResource(R.string.rp_needs_sound_long))
    }
}

// ------------------------------------------------------------------ audibility

@Composable
private fun AudibilityPage(graph: AppGraph, onBack: () -> Unit) {
    val c = graph.ripple
    val st by c.state.collectAsStateWithLifecycle()
    val pitch = c.pitchEstimate()
    LabPageColumn(stringResource(R.string.rp_audibility), onBack) {
        Body(stringResource(R.string.rp_audibility_intro))
        if (pitch == null) { Muted(stringResource(R.string.rp_pitch_missing)); return@LabPageColumn }
        val row = RippleBandTable.forPitch(pitch.hz)
        Text(stringResource(R.string.rp_audibility_band, bandText(row.active)), style = MaterialTheme.typography.titleMedium)
        val tones = AudibilityCheck.tonesFor(row.active, st.hearingProfileDb)
        Muted(tones.joinToString(" · ") { formatHz(it.hz) })
        OutlinedButton(onClick = {}, enabled = c.audibleAuthorized, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rp_audibility_play)) }
        Muted(stringResource(R.string.rp_needs_sound_long))
        st.a1AudibleFor(pitch.hz)?.let { Text(stringResource(if (it) R.string.rp_audibility_ok else R.string.rp_audibility_alt)) }
    }
}

// ------------------------------------------------------------------ plan

@Composable
private fun PlanPage(graph: AppGraph, onBack: () -> Unit) {
    val c = graph.ripple
    val st by c.state.collectAsStateWithLifecycle()
    var confirmUnblind by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    LabPageColumn(stringResource(R.string.rp_plan), onBack) {
        Body(stringResource(R.string.rp_plan_intro))
        val plan = st.plan
        if (plan == null) {
            Button(onClick = { c.startPlan(false) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.rp_plan_start_today)) }
            OutlinedButton(onClick = { c.startPlan(true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rp_plan_start_tomorrow)) }
            return@LabPageColumn
        }
        val today = c.today()
        val visible = plan.armsVisible(today)
        RowGroup {
            for (p in 1..2) {
                val arm = if (visible) " · " + stringResource(if (plan.armOf(p) == com.tinnitusrelief.ripple.engine.Arm.ACTIVE) R.string.rp_arm_active else R.string.rp_arm_sham) else ""
                ListRow(stringResource(R.string.rp_period_listen, p) + arm, subtitle = "${dayText(plan.listenStart(p))} – ${dayText(plan.listenEnd(p))}", chevron = false)
                GroupDivider()
                ListRow(stringResource(R.string.rp_period_washout, p), subtitle = "${dayText(plan.listenEnd(p) + 1)} – ${dayText(plan.washoutEnd(p))}", chevron = false)
                if (p == 1) GroupDivider()
            }
        }
        Text(planSubtitle(graph), style = MaterialTheme.typography.titleMedium)
        Muted(stringResource(R.string.rp_blinding_note))
        if (!visible) {
            TextButton(onClick = { if (confirmUnblind) { c.unblind(); confirmUnblind = false } else confirmUnblind = true }) {
                Text(stringResource(if (confirmUnblind) R.string.rp_unblind_confirm else R.string.rp_unblind), color = LocalReliefColors.current.warn)
            }
        }
        TextButton(onClick = { if (confirmEnd) { c.endPlan(); confirmEnd = false } else confirmEnd = true }) {
            Text(stringResource(if (confirmEnd) R.string.rp_end_plan_confirm else R.string.rp_end_plan), color = LocalReliefColors.current.warn)
        }
    }
}

// ------------------------------------------------------------------ session

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionPage(graph: AppGraph, rehearsal: Boolean, onBack: () -> Unit) {
    val c = graph.ripple
    val session by c.session.collectAsStateWithLifecycle()
    val ledger by (if (rehearsal) c.rehearsalLedger else c.ledger).collectAsStateWithLifecycle()
    val warn = LocalReliefColors.current.warn
    val s = session
    val title = stringResource(if (s?.rehearsal ?: rehearsal) R.string.rp_rehearsal else R.string.rp_start_session)
    LabPageColumn(title, onBack) {
        if (s?.rehearsal ?: rehearsal) Text(stringResource(R.string.rp_rehearsal_banner), color = warn, style = MaterialTheme.typography.bodyMedium)
        when (s?.phase) {
            null -> {
                val g = remember(ledger) { c.evaluate(rehearsal) }
                if (g.allowed) {
                    Body(stringResource(R.string.rp_session_intro, (g.allowedMs / 60_000).toInt()))
                    Button(onClick = { c.prepare(rehearsal) }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("rp_prepare")) { Text(stringResource(R.string.rp_continue)) }
                } else {
                    Text(stringResource(R.string.rp_cannot_start), style = MaterialTheme.typography.titleMedium)
                    for (b in g.blockers) Text("• " + blockerText(b), style = MaterialTheme.typography.bodyMedium)
                }
                ledger.lock?.let { LockNotice(it.reason) }
            }
            SessionPhase.PRE -> {
                var pre by remember { mutableStateOf<Int?>(null) }
                Text(stringResource(R.string.progress_question), style = MaterialTheme.typography.titleMedium)
                RatingPicker(pre) { pre = it }
                Muted(stringResource(R.string.rp_pre_note))
                Button(onClick = { if (!c.begin(pre)) onBack() }, enabled = pre != null, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("rp_begin")) {
                    Text(stringResource(R.string.rp_begin))
                }
                TextButton(onClick = { c.cancel(); onBack() }) { Text(stringResource(R.string.rp_cancel)) }
            }
            SessionPhase.RUNNING -> {
                Text(mmss(s.allowedMs - s.elapsedMs), style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("rp_remaining"))
                LinearProgressIndicator(progress = { (s.elapsedMs.toFloat() / s.allowedMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                LabeledSlider(
                    stringResource(R.string.rp_level), "%.0f dB".format(s.levelDb), s.levelDb.toFloat(),
                    { c.setLevel(it.toDouble()) }, (-60f)..c.limits.maxLevelDb.toFloat(),
                )
                Muted(stringResource(R.string.rp_level_note))
                OutlinedButton(onClick = { c.confirmLevel() }, enabled = !s.levelConfirmed) {
                    Text(stringResource(if (s.levelConfirmed) R.string.rp_level_confirmed else R.string.rp_level_confirm))
                }
                Button(onClick = { c.stop() }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("rp_stop")) { Text(stringResource(R.string.session_stop)) }
            }
            SessionPhase.POST -> {
                Eyebrow(stringResource(endText(s.endReason)))
                Text(stringResource(R.string.rp_listened, mmss(s.elapsedMs)), style = MaterialTheme.typography.bodyLarge)
                var after by remember { mutableStateOf<Int?>(null) }
                val symptoms = remember { mutableStateMapOf<AdverseSymptom, Boolean>() }
                Text(stringResource(R.string.rp_after_question), style = MaterialTheme.typography.titleMedium)
                RatingPicker(after) { after = it }
                Text(stringResource(R.string.rp_symptoms_question), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in AdverseSymptom.entries) {
                        val on = symptoms[a] == true
                        Choice(stringResource(symptomText(a)), on, { symptoms[a] = !on })
                    }
                }
                Muted(stringResource(R.string.rp_symptoms_note))
                Button(
                    onClick = { c.submitReport(after, symptoms.filterValues { it }.keys); },
                    enabled = after != null,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("rp_submit"),
                ) { Text(stringResource(R.string.rp_submit)) }
            }
        }
    }
}

@Composable
private fun LockNotice(reason: LockReason) {
    Text(
        stringResource(if (reason == LockReason.SYMPTOMS) R.string.rp_lock_symptoms else R.string.rp_lock_worsening),
        color = LocalReliefColors.current.warn, style = MaterialTheme.typography.bodyLarge,
    )
}

private fun endText(r: EndReason?): Int = when (r) {
    EndReason.LIMIT_REACHED -> R.string.rp_end_limit
    EndReason.INTERRUPTED -> R.string.rp_end_interrupted
    EndReason.ERROR -> R.string.rp_end_error
    else -> R.string.rp_end_user
}

private fun symptomText(a: AdverseSymptom): Int = when (a) {
    AdverseSymptom.EAR_FULLNESS -> R.string.rp_sym_fullness
    AdverseSymptom.EAR_PAIN -> R.string.rp_sym_pain
    AdverseSymptom.SOUND_DISTORTION -> R.string.rp_sym_distortion
    AdverseSymptom.TINNITUS_LOUDER_OR_CHANGED -> R.string.rp_sym_louder
    AdverseSymptom.DIZZINESS -> R.string.rp_sym_dizziness
    AdverseSymptom.HEARING_DECREASE -> R.string.rp_sym_hearing
}

// ------------------------------------------------------------------ progress

@Composable
private fun ProgressPage(graph: AppGraph, onBack: () -> Unit) {
    val c = graph.ripple
    val st by c.state.collectAsStateWithLifecycle()
    val ledger by c.ledger.collectAsStateWithLifecycle()
    val rehearsals by c.rehearsalLedger.collectAsStateWithLifecycle()
    val records by graph.tracker.records.collectAsStateWithLifecycle()
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LabPageColumn(stringResource(R.string.rp_progress), onBack) {
        Body(stringResource(R.string.rp_progress_intro))
        val outcomes = remember(st, ledger, records) { c.outcomes() }
        fun f(v: Double?) = v?.let { "%.1f".format(it) } ?: "–"
        for (o in outcomes) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val arm = o.arm?.let { " · " + stringResource(if (it == com.tinnitusrelief.ripple.engine.Arm.ACTIVE) R.string.rp_arm_active else R.string.rp_arm_sham) } ?: ""
                Text(stringResource(R.string.rp_period_listen, o.period) + arm, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.rp_outcome_line, f(o.startMean), f(o.endMean), f(o.washoutEndMean)), style = MaterialTheme.typography.bodyMedium)
                Muted(stringResource(R.string.rp_outcome_sessions, o.sessions, o.listenedMinutes.toInt()))
            }
        }
        if (st.plan == null) Muted(stringResource(R.string.rp_plan_none))
        Muted(stringResource(R.string.rp_outcome_note))
        Text(stringResource(R.string.rp_sessions), style = MaterialTheme.typography.titleMedium)
        if (ledger.sessions.isEmpty()) Muted(stringResource(R.string.rp_no_sessions))
        for (r in ledger.sessions.asReversed().take(30)) {
            Muted("${df.format(Date(r.startMs))} · ${mmss(r.audioMs)} · ${stringResource(endText(r.reason))}" + (r.report?.let { " · ${it.before ?: "–"} → ${it.after ?: "–"}" } ?: ""))
        }
        Muted(stringResource(R.string.rp_rehearsals_count, rehearsals.sessions.size))
    }
}

// ------------------------------------------------------------------ safety

@Composable
private fun SafetyPage(graph: AppGraph, onBack: () -> Unit) {
    val c = graph.ripple
    val st by c.state.collectAsStateWithLifecycle()
    val ledger by c.ledger.collectAsStateWithLifecycle()
    val route by c.route.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var imported by remember { mutableStateOf<Int?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { imported = c.importCalibration(uri) }
    }
    LabPageColumn(stringResource(R.string.rp_safety), onBack) {
        Body(stringResource(R.string.rp_safety_rules, c.limits.maxSessionMs / 60_000, c.limits.minBreakMs / 60_000, c.limits.dailyCapMs / 60_000))
        ledger.lock?.let { lock ->
            LockNotice(lock.reason)
            var ack by remember { mutableStateOf(false) }
            var clinician by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ack, { ack = it }); Text(stringResource(R.string.rp_ack_advice)) }
            if (lock.reason == LockReason.SYMPTOMS) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(clinician, { clinician = it }); Text(stringResource(R.string.rp_ack_clinician)) }
            var result by remember { mutableStateOf<Boolean?>(null) }
            OutlinedButton(onClick = { result = c.clearLock(ack, clinician) }) { Text(stringResource(R.string.rp_unlock)) }
            if (result == false) Muted(stringResource(if (lock.reason == LockReason.WORSENING) R.string.rp_unlock_wait else R.string.rp_unlock_clinician))
        }
        Text(stringResource(R.string.rp_output), style = MaterialTheme.typography.titleMedium)
        Muted(stringResource(R.string.rp_route_now, route?.label ?: "–"))
        if (st.calibrations.isEmpty()) Muted(stringResource(R.string.rp_no_calibration))
        for (cal in st.calibrations) Muted("${cal.deviceModel} · ${cal.headphoneModel} · ${cal.route} · ${"%.1f".format(cal.laeqAtMaxGainDbA)} dB(A) · ${cal.measuredBy}")
        OutlinedButton(onClick = { picker.launch(arrayOf("text/*")) }) { Text(stringResource(R.string.rp_import_calibration)) }
        imported?.let { Muted(stringResource(R.string.rp_imported, it)) }
        Text(stringResource(R.string.rp_authorization_note), color = LocalReliefColors.current.warn, style = MaterialTheme.typography.bodyMedium)
    }
}
