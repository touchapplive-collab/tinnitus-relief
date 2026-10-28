package com.tinnitusrelief.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinnitusrelief.app.AppGraph
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.dsp.AcousticMode
import com.tinnitusrelief.app.dsp.ResponseRecord
import com.tinnitusrelief.app.dsp.SessionLimits
import com.tinnitusrelief.app.engine.LabActivity
import com.tinnitusrelief.app.ui.theme.LocalReliefColors
import kotlin.math.roundToInt

enum class LabPage { MAIN, PITCH_GUIDED, PITCH_MANUAL, RI, RELIEF, HISTORY }

@Composable
fun LabScreen(graph: AppGraph) {
    val ack by graph.labAcknowledged.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(LabPage.MAIN) }
    BackHandler(enabled = page != LabPage.MAIN) { page = LabPage.MAIN }
    val back = { page = LabPage.MAIN }

    if (!ack) { LabIntro(onAccept = graph::acknowledgeLab); return }
    when (page) {
        LabPage.MAIN -> LabMain(graph) { page = it }
        LabPage.PITCH_GUIDED -> GuidedPitchPage(graph, back)
        LabPage.PITCH_MANUAL -> ManualPitchPage(graph, back)
        LabPage.RI -> RiTestPage(graph, back)
        LabPage.RELIEF -> ReliefTestPage(graph, back)
        LabPage.HISTORY -> HistoryPage(graph, back)
    }
}

/** Scrollable page body shared by all Lab pages. */
@Composable
fun LabPageColumn(title: String?, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onBack != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                if (title != null) Text(title, style = MaterialTheme.typography.headlineSmall)
            }
        }
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LabIntro(onAccept: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    LabPageColumn(null, null) {
        Eyebrow(stringResource(R.string.lab_experimental), LocalReliefColors.current.warn)
        Text(stringResource(R.string.tab_lab), style = MaterialTheme.typography.displaySmall)
        Body(stringResource(R.string.lab_intro))
        ReliefCard {
            Text(stringResource(R.string.lab_ack_title), style = MaterialTheme.typography.titleMedium)
            Body(stringResource(R.string.lab_ack_body))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { checked = it })
                Text(stringResource(R.string.lab_ack_check), style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onAccept, enabled = checked) { Text(stringResource(R.string.lab_ack_button)) }
        }
    }
}

@Composable
private fun LabMain(graph: AppGraph, open: (LabPage) -> Unit) {
    val pitch by graph.pitch.collectAsStateWithLifecycle()
    val lab by graph.lab.collectAsStateWithLifecycle()
    val sounds by graph.sounds.collectAsStateWithLifecycle()
    val state by graph.sessions.state.collectAsStateWithLifecycle()
    val records by graph.tracker.records.collectAsStateWithLifecycle()
    val extra = LocalReliefColors.current

    LabPageColumn(null, null) {
        Eyebrow(stringResource(R.string.lab_experimental), extra.warn)
        Text(stringResource(R.string.tab_lab), style = MaterialTheme.typography.displaySmall)

        // ---------- pitch ----------
        ReliefCard {
            Eyebrow(stringResource(R.string.pitch_title))
            Text(pitch?.let { formatHz(it) } ?: stringResource(R.string.pitch_not_set), style = MaterialTheme.typography.headlineSmall)
            Muted(stringResource(R.string.pitch_disclaimer))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { open(LabPage.PITCH_GUIDED) }) { Text(stringResource(R.string.pitch_guided)) }
                OutlinedButton(onClick = { open(LabPage.PITCH_MANUAL) }) { Text(stringResource(R.string.pitch_manual)) }
            }
        }

        // ---------- acoustic engine ----------
        ReliefCard {
            Eyebrow(stringResource(R.string.engine_title))
            ChoiceRow(AcousticMode.entries, lab.mode, { graph.modeLabel(it) }) { m -> graph.updateLab { it.copy(mode = m) } }
            EvidenceTag(lab.mode.evidence, stringResource(R.string.evidence_published), stringResource(R.string.evidence_generator))
            Body(stringResource(modeDescription(lab.mode)))

            if (lab.mode.usesSource) {
                Text(stringResource(R.string.engine_source), style = MaterialTheme.typography.titleMedium)
                ChoiceRow(sounds.map { it.id }, lab.sourceId, { id -> sounds.firstOrNull { it.id == id }?.name ?: id }) { id ->
                    graph.updateLab { it.copy(sourceId = id) }
                }
                Muted(stringResource(R.string.engine_source_hint))
            }
            val bands = pitch?.let { lab.bands(it) }.orEmpty()
            if (bands.isNotEmpty()) ResponseCurve(bands, pitch, lab.transitionOctaves)

            when (lab.mode) {
                AcousticMode.DFCRS_INSPIRED -> LabeledSlider(
                    stringResource(R.string.param_band_gain), "%.0f dB".format(lab.bandGainDb),
                    lab.bandGainDb.toFloat(), { v -> graph.updateLab { it.copy(bandGainDb = v.roundToInt().toDouble()) } }, 0f..10f, 9,
                )
                AcousticMode.CUSTOM_FILTER -> {
                    LabeledSlider(stringResource(R.string.param_offset), "%+.2f oct".format(lab.customOffsetOctaves),
                        lab.customOffsetOctaves.toFloat(), { v -> graph.updateLab { it.copy(customOffsetOctaves = v.toDouble()) } }, -2f..2f)
                    LabeledSlider(stringResource(R.string.param_width), "%.2f oct".format(lab.customWidthOctaves),
                        lab.customWidthOctaves.toFloat(), { v -> graph.updateLab { it.copy(customWidthOctaves = v.toDouble()) } }, (1f / 12)..2f)
                    LabeledSlider(stringResource(R.string.param_gain), "%+.0f dB".format(lab.customGainDb),
                        lab.customGainDb.toFloat(), { v -> graph.updateLab { it.copy(customGainDb = v.roundToInt().toDouble()) } }, -40f..10f)
                }
                AcousticMode.AM_TONE -> {
                    LabeledSlider(stringResource(R.string.param_rate), "%.1f Hz".format(lab.amRateHz),
                        lab.amRateHz.toFloat(), { v -> graph.updateLab { it.copy(amRateHz = v.toDouble()) } }, 0.5f..20f)
                    LabeledSlider(stringResource(R.string.param_depth), "${(lab.amDepth * 100).roundToInt()} %",
                        lab.amDepth, { v -> graph.updateLab { it.copy(amDepth = v) } })
                }
                AcousticMode.FM_TONE -> {
                    LabeledSlider(stringResource(R.string.param_rate), "%.1f Hz".format(lab.fmRateHz),
                        lab.fmRateHz.toFloat(), { v -> graph.updateLab { it.copy(fmRateHz = v.toDouble()) } }, 0.5f..20f)
                    LabeledSlider(stringResource(R.string.param_deviation), "%.0f cents".format(lab.fmCents),
                        lab.fmCents.toFloat(), { v -> graph.updateLab { it.copy(fmCents = v.roundToInt().toDouble()) } }, 0f..300f)
                }
                else -> Unit
            }

            LabeledSlider(stringResource(R.string.param_volume), "${(lab.volume * 100).roundToInt()}",
                lab.volume, { v -> graph.updateLab { it.copy(volume = v) } })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.param_mix_with_masker), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = lab.mixWithMasker, onCheckedChange = { c -> graph.updateLab { it.copy(mixWithMasker = c) } })
            }
            Text(stringResource(R.string.param_session_length), style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(SessionLimits.OPTIONS_MINUTES, lab.minutes, { "$it min" }) { m -> graph.updateLab { it.copy(minutes = m) } }

            val running = state?.activity == LabActivity.SESSION
            if (running) {
                Text(stringResource(R.string.session_running, countdown(state?.endsAtElapsed)), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { graph.stopLabSession() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.session_stop)) }
            } else {
                Button(
                    onClick = { graph.startLabSession() },
                    enabled = pitch != null && state == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.session_start)) }
                if (pitch == null) Muted(stringResource(R.string.session_needs_pitch))
            }
            Muted(stringResource(R.string.session_safety))
        }

        // ---------- tests ----------
        ReliefCard {
            Eyebrow(stringResource(R.string.ri_title))
            Body(stringResource(R.string.ri_card_body))
            OutlinedButton(onClick = { open(LabPage.RI) }, enabled = pitch != null) { Text(stringResource(R.string.ri_open)) }
        }
        ReliefCard {
            Eyebrow(stringResource(R.string.rt_title))
            Body(stringResource(R.string.rt_card_body))
            OutlinedButton(onClick = { open(LabPage.RELIEF) }) { Text(stringResource(R.string.rt_open)) }
        }

        // ---------- progress ----------
        ReliefCard {
            Eyebrow(stringResource(R.string.progress_title))
            val daily = records.filterIsInstance<ResponseRecord.Daily>().sortedBy { it.day }
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            Text(stringResource(R.string.progress_question), style = MaterialTheme.typography.titleMedium)
            RatingPicker(daily.lastOrNull { it.day == today }?.rating) { graph.addDaily(it) }
            if (daily.size >= 2) DailyChart(daily.takeLast(90).map { it.rating })
            Muted(stringResource(R.string.progress_hint))
            OutlinedButton(onClick = { open(LabPage.HISTORY) }) { Text(stringResource(R.string.history_open)) }
        }
        Muted(stringResource(R.string.lab_disclaimer))
    }
}

private fun modeDescription(m: AcousticMode): Int = when (m) {
    AcousticMode.DFCRS_INSPIRED -> R.string.mode_dfcrs_desc
    AcousticMode.NOTCHED -> R.string.mode_notched_desc
    AcousticMode.HFEM -> R.string.mode_hfem_desc
    AcousticMode.CUSTOM_FILTER -> R.string.mode_custom_desc
    AcousticMode.NARROWBAND_NOISE -> R.string.mode_nbn_desc
    AcousticMode.PURE_TONE -> R.string.mode_tone_desc
    AcousticMode.AM_TONE -> R.string.mode_am_desc
    AcousticMode.FM_TONE -> R.string.mode_fm_desc
}

@Composable
private fun DailyChart(values: List<Int>) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = LocalReliefColors.current.hairline
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        fun y(v: Int) = size.height - v / 10f * size.height
        for (g in listOf(0, 5, 10)) drawLine(grid, Offset(0f, y(g)), Offset(size.width, y(g)), 1f)
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = if (values.size == 1) 0f else i * size.width / (values.size - 1)
            if (i == 0) p.moveTo(x, y(v)) else p.lineTo(x, y(v))
        }
        drawPath(p, accent, style = Stroke(width = 3f))
        val last = values.last()
        drawCircle(accent, 6f, Offset(size.width, y(last)))
    }
}
