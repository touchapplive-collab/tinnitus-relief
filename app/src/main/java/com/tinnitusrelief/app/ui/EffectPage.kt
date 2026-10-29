package com.tinnitusrelief.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinnitusrelief.app.AppGraph
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.data.SoundKind
import com.tinnitusrelief.app.dsp.EffectSummary
import com.tinnitusrelief.app.dsp.ResponseRecord
import com.tinnitusrelief.app.engine.EffectFlow

/** Original vs personalised over several sessions (blinded, balanced random order). */
@Composable
fun EffectPage(graph: AppGraph, onBack: () -> Unit) {
    val flow = graph.effect
    val s by flow.state.collectAsStateWithLifecycle()
    val sounds by graph.sounds.collectAsStateWithLifecycle()
    val records by graph.tracker.records.collectAsStateWithLifecycle()
    val lab by graph.sessions.state.collectAsStateWithLifecycle()
    val personal = sounds.filter { it.kind == SoundKind.PERSONALIZED }
    LaunchedEffect(personal) { if (s.presetId == null || personal.none { it.id == s.presetId }) personal.firstOrNull()?.let { flow.setPreset(it.id) } }

    LabPageColumn(stringResource(R.string.ef_title), onBack) {
        when (s.step) {
            EffectFlow.Step.SETUP -> {
                Body(stringResource(R.string.ef_intro))
                Text(stringResource(R.string.ef_which), style = MaterialTheme.typography.titleMedium)
                ChoiceRow(personal.map { it.id }, s.presetId, { id -> personal.firstOrNull { it.id == id }?.name ?: id }) { flow.setPreset(it) }
                Text(stringResource(R.string.param_session_length), style = MaterialTheme.typography.titleMedium)
                ChoiceRow(EffectFlow.MINUTE_OPTIONS, s.minutes, { "$it min" }) { flow.setMinutes(it) }
                Button(onClick = { flow.begin() }, enabled = s.presetId != null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.rt_begin))
                }
                personal.firstOrNull { it.id == s.presetId }?.let { Summary(EffectSummary.of(records, it.name)) }
            }
            EffectFlow.Step.BEFORE -> {
                Text(stringResource(R.string.rate_question), style = MaterialTheme.typography.titleMedium)
                RatingPicker(null) { flow.rateBefore(it) }
                TextButton(onClick = { flow.rateBefore(null) }) { Text(stringResource(R.string.ef_skip_rating)) }
            }
            EffectFlow.Step.PLAYING -> {
                Text(countdown(lab?.endsAtElapsed), style = MaterialTheme.typography.displaySmall)
                Body(stringResource(R.string.ef_playing))
                Text(stringResource(R.string.ef_during), style = MaterialTheme.typography.titleMedium)
                RatingPicker(s.during) { flow.rateDuring(it) }
                OutlinedButton(onClick = { flow.stopEarly() }) { Text(stringResource(R.string.session_stop)) }
            }
            EffectFlow.Step.AFTER -> {
                Text(stringResource(R.string.ef_after), style = MaterialTheme.typography.titleMedium)
                RatingPicker(null) { flow.rateAfter(it) }
                TextButton(onClick = { flow.rateAfter(null) }) { Text(stringResource(R.string.ef_skip_rating)) }
            }
            EffectFlow.Step.COMFORT -> {
                Text(stringResource(R.string.ef_comfort), style = MaterialTheme.typography.titleMedium)
                RatingPicker(null) { flow.rateComfort(it) }
                TextButton(onClick = { flow.rateComfort(null) }) { Text(stringResource(R.string.ef_skip_rating)) }
            }
            EffectFlow.Step.DONE -> {
                Eyebrow(stringResource(R.string.afc_saved))
                Body(
                    stringResource(
                        R.string.ef_revealed,
                        stringResource(if (s.variant == EffectSummary.PERSONALIZED) R.string.pz_personalized else R.string.variant_original),
                    )
                )
                personal.firstOrNull { it.id == s.presetId }?.let { Summary(EffectSummary.of(records, it.name)) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { flow.reset() }) { Text(stringResource(R.string.again)) }
                    Button(onClick = { flow.reset(); onBack() }) { Text(stringResource(R.string.done)) }
                }
            }
            EffectFlow.Step.FAILED -> {
                Body(stringResource(R.string.msg_lab_failed))
                OutlinedButton(onClick = { flow.reset() }) { Text(stringResource(R.string.again)) }
            }
        }
    }
}

/** Two short lines: average change in noticeability while playing / after, and comfort, per version. */
@Composable
fun Summary(rows: List<EffectSummary>) {
    if (rows.all { it.sessions == 0 }) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in rows) {
            val name = stringResource(if (r.variant == EffectSummary.PERSONALIZED) R.string.pz_personalized else R.string.variant_original)
            Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Muted(
                stringResource(
                    R.string.ef_summary_row,
                    r.sessions,
                    r.maskingDrop?.let { "%+.1f".format(-it) } ?: "–",
                    r.afterDrop?.let { "%+.1f".format(-it) } ?: "–",
                    r.comfort?.let { "%.1f".format(it) } ?: "–",
                )
            )
        }
        Muted(stringResource(R.string.ef_summary_note))
    }
}

/** History detail line for one comparison session. */
fun effectDetail(r: ResponseRecord.Effect, original: String, personalized: String): String =
    "${r.preset} · ${if (r.variant == EffectSummary.PERSONALIZED) personalized else original} · ${r.minutes} min · " +
        "${r.before ?: "–"} → ${r.during ?: "–"} → ${r.after ?: "–"}" + (r.comfort?.let { " · ☺ $it" } ?: "")
