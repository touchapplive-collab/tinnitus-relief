package com.tinnitusrelief.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinnitusrelief.app.AppGraph
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.dsp.ResponseRecord
import com.tinnitusrelief.app.dsp.ThreeAfcPitch
import com.tinnitusrelief.app.dsp.ToneSequenceVoice
import com.tinnitusrelief.app.engine.FrequencyMatchingEngine
import com.tinnitusrelief.app.engine.ReliefTestFlow
import com.tinnitusrelief.app.engine.RiTestFlow
import com.tinnitusrelief.app.ui.theme.LocalReliefColors
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.ln
import kotlin.math.pow

// ======================= pitch matching =======================

@Composable
private fun MatchingSession(graph: AppGraph, title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val m = graph.matching
    var ready by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { ready = m.begin(title) }
    DisposableEffect(Unit) { onDispose { m.end() } }
    LabPageColumn(title, onBack) {
        when (ready) {
            null -> Muted(stringResource(R.string.starting))
            false -> Body(stringResource(R.string.msg_lab_failed))
            true -> content()
        }
        Muted(stringResource(R.string.pitch_disclaimer))
    }
}

@Composable
private fun AbcButtons(enabled: Boolean, playing: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        for (i in 0..2) {
            val scheme = MaterialTheme.colorScheme
            Surface(
                onClick = { onPick(i) },
                enabled = enabled,
                shape = RoundedCornerShape(18.dp),
                color = if (playing == i) scheme.primary else scheme.surfaceContainerHigh,
                contentColor = if (playing == i) scheme.onPrimary else scheme.onSurface,
                modifier = Modifier.weight(1f).height(72.dp),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("ABC"[i].toString(), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}

@Composable
private fun rememberSequenceIndex(seq: ToneSequenceVoice?): Pair<Int, Boolean> {
    var idx by remember { mutableIntStateOf(-1) }
    var done by remember { mutableStateOf(true) }
    LaunchedEffect(seq) {
        if (seq == null) return@LaunchedEffect
        done = false
        while (!seq.finished) { idx = seq.currentIndex; delay(40) }
        idx = -1; done = true
    }
    return idx to done
}

@Composable
fun GuidedPitchPage(graph: AppGraph, onBack: () -> Unit) {
    val m = graph.matching
    val toneDb by m.toneDb.collectAsStateWithLifecycle()
    var step by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(ThreeAfcPitch.Phase.COARSE) }
    var triple by remember { mutableStateOf(doubleArrayOf()) }
    var trial by remember { mutableIntStateOf(1) }
    var seq by remember { mutableStateOf<ToneSequenceVoice?>(null) }
    var heard by remember { mutableStateOf(false) }
    var freq by remember { mutableDoubleStateOf(1000.0) }
    var threshold by remember { mutableDoubleStateOf(-60.0) }
    var loudTrial by remember { mutableIntStateOf(0) }
    val (playingIdx, seqDone) = rememberSequenceIndex(seq)

    MatchingSession(graph, stringResource(R.string.pitch_guided), onBack) {
        when (step) {
            0 -> {
                LaunchedEffect(Unit) { m.restartAfc(); triple = m.afc.triple; m.playSteady(1000.0) }
                Body(stringResource(R.string.afc_level_body))
                LabeledSlider(stringResource(R.string.afc_level), "%.0f dB".format(toneDb), toneDb.toFloat(),
                    { m.setToneDb(it.toDouble()) }, FrequencyMatchingEngine.MIN_TONE_DB.toFloat()..FrequencyMatchingEngine.MAX_TONE_DB.toFloat())
                Button(onClick = { m.silence(); step = 1 }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.afc_start)) }
            }
            1 -> {
                Eyebrow(stringResource(when (phase) {
                    ThreeAfcPitch.Phase.COARSE -> R.string.afc_phase_coarse
                    ThreeAfcPitch.Phase.FINE -> R.string.afc_phase_fine
                    else -> R.string.afc_phase_octave
                }))
                Text(stringResource(R.string.afc_trial, trial), style = MaterialTheme.typography.headlineSmall)
                Body(stringResource(R.string.afc_instructions))
                Button(onClick = { seq = m.playTriple(triple); heard = true }, enabled = seqDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (heard) R.string.afc_play_again else R.string.afc_play))
                }
                AbcButtons(enabled = heard && seqDone, playing = playingIdx) { i ->
                    m.afc.choose(i)
                    heard = false
                    trial++
                    phase = m.afc.phase
                    triple = m.afc.triple
                    if (m.afc.phase == ThreeAfcPitch.Phase.DONE) { freq = m.afc.result ?: triple[1]; step = 2 }
                }
            }
            2 -> {
                LaunchedEffect(Unit) { m.playSteady(freq) }
                Eyebrow(stringResource(R.string.afc_result))
                Text(formatHz(freq), style = MaterialTheme.typography.displaySmall)
                Body(stringResource(R.string.afc_finetune_body))
                FineTuneRow { oct ->
                    freq = (freq * 2.0.pow(oct)).coerceIn(125.0, m.maxHz)
                    m.setSteady(freq, toneDb)
                }
                Button(onClick = { graph.savePitch(freq, "3afc"); m.silence(); step = 5 }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pitch_save))
                }
                OutlinedButton(onClick = { graph.savePitch(freq, "3afc"); step = 3 }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.loud_also))
                }
            }
            3 -> {
                LaunchedEffect(Unit) { m.playSteady(freq, threshold) }
                Body(stringResource(R.string.loud_threshold_body))
                LabeledSlider(stringResource(R.string.afc_level), "%.0f dB".format(threshold), threshold.toFloat(),
                    { threshold = it.toDouble(); m.setSteady(freq, threshold) }, -80f..-20f)
                Button(onClick = { m.silence(); m.startLoudness(threshold); step = 4 }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.loud_threshold_done))
                }
            }
            4 -> {
                val l = m.loudness
                if (l != null) {
                    Text(stringResource(R.string.afc_trial, loudTrial + 1), style = MaterialTheme.typography.headlineSmall)
                    Body(stringResource(R.string.loud_instructions))
                    Button(onClick = { seq = m.playTriple(DoubleArray(3) { freq }, l.levels); heard = true }, enabled = seqDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (heard) R.string.afc_play_again else R.string.afc_play))
                    }
                    AbcButtons(enabled = heard && seqDone, playing = playingIdx) { i ->
                        l.choose(i)
                        heard = false
                        loudTrial++
                        if (l.done) { graph.savePitch(freq, "3afc+loudness", l.sensationLevelDb); step = 5 }
                    }
                }
            }
            else -> {
                Eyebrow(stringResource(R.string.afc_saved))
                Text(formatHz(freq), style = MaterialTheme.typography.displaySmall)
                m.loudness?.takeIf { it.done }?.let {
                    Body(stringResource(R.string.loud_result, it.sensationLevelDb))
                }
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

@Composable
private fun FineTuneRow(onShift: (Double) -> Unit) {
    val step = ThreeAfcPitch.FINE_TUNE_OCTAVES
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onShift(-1.0) }) { Text("½×") }
        OutlinedButton(onClick = { onShift(-step) }) { Text("−") }
        OutlinedButton(onClick = { onShift(step) }) { Text("+") }
        OutlinedButton(onClick = { onShift(1.0) }) { Text("2×") }
    }
}

@Composable
fun ManualPitchPage(graph: AppGraph, onBack: () -> Unit) {
    val m = graph.matching
    val saved by graph.pitch.collectAsStateWithLifecycle()
    val toneDb by m.toneDb.collectAsStateWithLifecycle()
    var freq by remember { mutableDoubleStateOf(saved ?: 4000.0) }
    var playing by remember { mutableStateOf(true) }
    var stored by remember { mutableStateOf(false) }
    val lo = 125.0
    val hi = m.maxHz
    fun toPos(f: Double) = (ln(f / lo) / ln(hi / lo)).toFloat()
    fun fromPos(p: Float) = lo * (hi / lo).pow(p.toDouble())

    MatchingSession(graph, stringResource(R.string.pitch_manual), onBack) {
        LaunchedEffect(Unit) { m.playSteady(freq) }
        Body(stringResource(R.string.manual_body))
        Text(formatHz(freq), style = MaterialTheme.typography.displaySmall)
        LabeledSlider(stringResource(R.string.manual_frequency), "", toPos(freq), { p ->
            freq = fromPos(p); stored = false
            if (playing) m.setSteady(freq, toneDb)
        })
        FineTuneRow { oct ->
            freq = (freq * 2.0.pow(oct)).coerceIn(lo, hi); stored = false
            if (playing) m.setSteady(freq, toneDb)
        }
        LabeledSlider(stringResource(R.string.afc_level), "%.0f dB".format(toneDb), toneDb.toFloat(),
            { m.setToneDb(it.toDouble()) }, FrequencyMatchingEngine.MIN_TONE_DB.toFloat()..FrequencyMatchingEngine.MAX_TONE_DB.toFloat())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                playing = !playing
                if (playing) m.playSteady(freq) else m.silence()
            }) { Text(stringResource(if (playing) R.string.pause else R.string.play)) }
            Button(onClick = { graph.savePitch(freq, "manual"); stored = true }) {
                Text(stringResource(if (stored) R.string.afc_saved else R.string.pitch_save))
            }
        }
    }
}

// ======================= residual inhibition =======================

@Composable
fun RiTestPage(graph: AppGraph, onBack: () -> Unit) {
    val flow = graph.riTest
    val s by flow.state.collectAsStateWithLifecycle()
    val pitch by graph.pitch.collectAsStateWithLifecycle()
    val label = stringResource(R.string.ri_title)
    DisposableEffect(Unit) { onDispose { if (flow.state.value.step != RiTestFlow.Step.WAITING) flow.reset() } }

    LabPageColumn(label, onBack) {
        when (s.step) {
            RiTestFlow.Step.INTRO -> {
                Body(stringResource(R.string.ri_intro))
                Text(stringResource(R.string.ri_extra), style = MaterialTheme.typography.titleMedium)
                ChoiceRow(RiTestFlow.EXTRA_DB_OPTIONS, s.extraDb, { "+$it dB" }) { flow.setExtraDb(it) }
                Muted(stringResource(R.string.ri_extra_hint))
                Button(onClick = { pitch?.let { flow.begin(it, label) } }, enabled = pitch != null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ri_start))
                }
            }
            RiTestFlow.Step.LEVEL -> {
                Body(stringResource(R.string.ri_level_body))
                LabeledSlider(stringResource(R.string.param_volume), "${(s.level * 100).toInt()}", s.level, { flow.setLevel(it) })
                Button(onClick = { flow.confirmLevel() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ri_level_done)) }
            }
            RiTestFlow.Step.BEFORE -> {
                Text(stringResource(R.string.rate_question), style = MaterialTheme.typography.titleMedium)
                RatingPicker(null) { flow.rateBefore(it) }
            }
            RiTestFlow.Step.STIMULUS -> {
                Text(countdown(s.stimulusEndsAt), style = MaterialTheme.typography.displaySmall)
                Body(stringResource(R.string.ri_stimulus_body))
                if (s.capped) Muted(stringResource(R.string.ri_capped))
                OutlinedButton(onClick = { flow.reset() }) { Text(stringResource(R.string.session_stop)) }
            }
            RiTestFlow.Step.AFTER -> {
                var outcome by remember { mutableStateOf<RiTestFlow.Outcome?>(null) }
                var rating by remember { mutableStateOf<Int?>(null) }
                Text(stringResource(R.string.ri_after_question), style = MaterialTheme.typography.titleMedium)
                ChoiceRow(RiTestFlow.Outcome.entries, outcome, { outcomeLabel(it) }) { outcome = it }
                Text(stringResource(R.string.rate_question), style = MaterialTheme.typography.titleMedium)
                RatingPicker(rating) { rating = it }
                Button(
                    onClick = { flow.rateAfter(rating!!, outcome!!) },
                    enabled = rating != null && outcome != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.continue_)) }
            }
            RiTestFlow.Step.WAITING -> {
                Text(stopwatch(s.offsetAt), style = MaterialTheme.typography.displaySmall)
                Body(stringResource(R.string.ri_waiting_body))
                Button(onClick = { flow.returned() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ri_returned)) }
            }
            RiTestFlow.Step.DONE -> {
                Eyebrow(stringResource(R.string.afc_saved))
                Body(
                    when (val sec = s.riSeconds) {
                        null -> stringResource(R.string.ri_result_long)
                        0 -> stringResource(R.string.ri_result_none)
                        else -> stringResource(R.string.ri_result_seconds, sec)
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { flow.reset() }) { Text(stringResource(R.string.again)) }
                    Button(onClick = { flow.reset(); onBack() }) { Text(stringResource(R.string.done)) }
                }
            }
            RiTestFlow.Step.FAILED -> {
                Body(stringResource(R.string.msg_lab_failed))
                OutlinedButton(onClick = { flow.reset() }) { Text(stringResource(R.string.again)) }
            }
        }
        Muted(stringResource(R.string.ri_disclaimer))
    }
}

@Composable
private fun outcomeLabel(o: RiTestFlow.Outcome): String = stringResource(
    when (o) {
        RiTestFlow.Outcome.GONE -> R.string.ri_gone
        RiTestFlow.Outcome.QUIETER -> R.string.ri_quieter
        RiTestFlow.Outcome.SAME -> R.string.ri_same
        RiTestFlow.Outcome.LOUDER -> R.string.ri_louder
    }
)

// ======================= relief test =======================

@Composable
fun ReliefTestPage(graph: AppGraph, onBack: () -> Unit) {
    val flow = graph.reliefTest
    val s by flow.state.collectAsStateWithLifecycle()
    val pitch by graph.pitch.collectAsStateWithLifecycle()
    val lab by graph.sessions.state.collectAsStateWithLifecycle()

    LabPageColumn(stringResource(R.string.rt_title), onBack) {
        when (s.step) {
            ReliefTestFlow.Step.SETUP -> {
                Body(stringResource(R.string.rt_intro))
                Text(stringResource(R.string.rt_stimulus), style = MaterialTheme.typography.titleMedium)
                val options = if (pitch != null) ReliefTestFlow.Stimulus.entries else listOf(ReliefTestFlow.Stimulus.MIX)
                ChoiceRow(options, s.stimulus, {
                    if (it == ReliefTestFlow.Stimulus.MIX) graph.mixTitle() else graph.modeLabel(graph.lab.value.mode)
                }) { flow.setStimulus(it) }
                Text(stringResource(R.string.param_session_length), style = MaterialTheme.typography.titleMedium)
                ChoiceRow(ReliefTestFlow.MINUTE_OPTIONS, s.minutes, { "$it min" }) { flow.setMinutes(it) }
                Button(onClick = { flow.begin() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rt_begin)) }
            }
            ReliefTestFlow.Step.BEFORE -> {
                Text(stringResource(R.string.rate_question), style = MaterialTheme.typography.titleMedium)
                RatingPicker(null) { flow.rateBefore(it) }
            }
            ReliefTestFlow.Step.PLAYING -> {
                Text(countdown(lab?.endsAtElapsed), style = MaterialTheme.typography.displaySmall)
                Body(stringResource(R.string.rt_playing))
                OutlinedButton(onClick = { flow.stopEarly() }) { Text(stringResource(R.string.session_stop)) }
            }
            ReliefTestFlow.Step.AFTER -> {
                Text(stringResource(R.string.rate_question), style = MaterialTheme.typography.titleMedium)
                RatingPicker(null) { flow.rateAfter(it) }
            }
            ReliefTestFlow.Step.WAITING -> {
                Text(countdown(s.waitEndsAt), style = MaterialTheme.typography.displaySmall)
                Body(stringResource(R.string.rt_waiting))
                TextButton(onClick = { flow.rateLater(null) }) { Text(stringResource(R.string.rt_skip)) }
            }
            ReliefTestFlow.Step.LATER -> {
                Text(stringResource(R.string.rate_question), style = MaterialTheme.typography.titleMedium)
                RatingPicker(null) { flow.rateLater(it) }
            }
            ReliefTestFlow.Step.DONE -> {
                Eyebrow(stringResource(R.string.afc_saved))
                Body(stringResource(R.string.rt_summary, s.before ?: 0, s.after ?: 0))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { flow.reset() }) { Text(stringResource(R.string.again)) }
                    Button(onClick = { flow.reset(); onBack() }) { Text(stringResource(R.string.done)) }
                }
            }
            ReliefTestFlow.Step.FAILED -> {
                Body(stringResource(R.string.msg_lab_failed))
                OutlinedButton(onClick = { flow.reset() }) { Text(stringResource(R.string.again)) }
            }
        }
    }
}

// ======================= history =======================

@Composable
fun HistoryPage(graph: AppGraph, onBack: () -> Unit) {
    val records by graph.tracker.records.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Long?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val extra = LocalReliefColors.current
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    LabPageColumn(stringResource(R.string.history_title), onBack) {
        if (records.isEmpty()) Muted(stringResource(R.string.history_empty))
        for (r in records.asReversed()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(recordTitle(r), style = MaterialTheme.typography.titleMedium)
                    Text(recordDetail(r), style = MaterialTheme.typography.bodyMedium)
                    Text(df.format(Date(r.atMs)), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                }
                TextButton(onClick = {
                    if (confirm == r.atMs) { graph.deleteRecord(r.atMs); confirm = null } else confirm = r.atMs
                }) {
                    Text(stringResource(if (confirm == r.atMs) R.string.delete_confirm else R.string.delete),
                        color = if (confirm == r.atMs) extra.warn else extra.muted)
                }
            }
            HorizontalDivider(color = extra.hairline)
        }
        if (records.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(graph.tracker.asText())) }) {
                    Text(stringResource(R.string.history_copy))
                }
                TextButton(onClick = { if (confirmAll) { graph.clearRecords(); confirmAll = false } else confirmAll = true }) {
                    Text(stringResource(if (confirmAll) R.string.history_delete_all_confirm else R.string.history_delete_all), color = extra.warn)
                }
            }
        }
    }
}

@Composable
private fun recordTitle(r: ResponseRecord): String = when (r) {
    is ResponseRecord.Pitch -> stringResource(R.string.rec_pitch)
    is ResponseRecord.Relief -> stringResource(R.string.rec_relief)
    is ResponseRecord.Ri -> stringResource(R.string.rec_ri)
    is ResponseRecord.Daily -> stringResource(R.string.rec_daily)
}

@Composable
private fun recordDetail(r: ResponseRecord): String = when (r) {
    is ResponseRecord.Pitch -> formatHz(r.freqHz) + " · " + r.method + (r.loudnessDbSl?.let { " · %.0f dB".format(it) } ?: "")
    is ResponseRecord.Relief -> "${r.stimulus} · ${r.minutes} min · ${r.before} → ${r.after ?: "–"} → ${r.later ?: "–"}"
    is ResponseRecord.Ri -> formatHz(r.freqHz) + " · ${r.before} → ${r.after ?: "–"} · " + (r.riSeconds?.let { "${it}s" } ?: ">10 min")
    is ResponseRecord.Daily -> "${r.rating}/10"
}
