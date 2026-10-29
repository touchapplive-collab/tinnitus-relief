package com.tinnitusrelief.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinnitusrelief.app.AppGraph
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.dsp.Personalizer
import com.tinnitusrelief.app.engine.PersonalizeFlow
import com.tinnitusrelief.app.ui.theme.LocalReliefColors
import kotlinx.coroutines.delay

/**
 * Find your perfect sound: Choose sound → Personalize → Compare → Save → Listen.
 * No technical terms: the listener only hears two versions and says which feels better.
 */
@Composable
fun PersonalizeScreen(graph: AppGraph, soundId: String, onClose: () -> Unit) {
    val flow = graph.personalize
    val s by flow.state.collectAsStateWithLifecycle()
    val playback by produceState(-1 to 0f, flow) {
        while (true) { value = flow.playing to flow.progress; delay(50) }
    }
    val (playing, progress) = playback

    LaunchedEffect(soundId) { flow.open(soundId) }
    DisposableEffect(Unit) { onDispose { flow.close() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { flow.stopListening() }
    val leave = { flow.finishLater(); onClose() }
    BackHandler(onBack = leave)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = leave, modifier = Modifier.padding(start = 0.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        }
        when (s.step) {
            PersonalizeFlow.Step.ANALYZING -> {
                Header(stringResource(R.string.pz_title), stringResource(R.string.pz_subtitle))
                Row(Modifier.padding(vertical = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.size(14.dp))
                    Muted(stringResource(R.string.pz_analyzing, s.base?.name ?: ""))
                }
            }
            PersonalizeFlow.Step.COMPARE -> Compare(graph, s, playing, progress)
            PersonalizeFlow.Step.RESULT -> Result(graph, s, playing, progress, onClose)
            PersonalizeFlow.Step.FAILED -> {
                Header(stringResource(R.string.pz_title), null)
                Body(stringResource(R.string.msg_load_failed))
                Button(onClick = onClose, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.done)) }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Header(title: String, subtitle: String?) {
    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 4.dp))
    if (subtitle != null) Muted(subtitle, Modifier.padding(top = 6.dp))
}

@Composable
private fun AbTiles(labelA: String, labelB: String, playing: Int, progress: Float, onListen: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        ListenTile(labelA, playing == 0, if (playing == 0) progress else 0f, { onListen(0) }, Modifier.weight(1f))
        ListenTile(labelB, playing == 1, if (playing == 1) progress else 0f, { onListen(1) }, Modifier.weight(1f))
    }
}

@Composable
private fun ColumnScope.Compare(graph: AppGraph, s: PersonalizeFlow.State, playing: Int, progress: Float) {
    val flow = graph.personalize
    val c = s.comparison ?: return
    Header(stringResource(R.string.pz_title), stringResource(R.string.pz_subtitle))
    Spacer(Modifier.height(22.dp))
    Text(stringResource(R.string.pz_progress, c.step, c.total), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(progress = { (c.step - 1f) / c.total }, modifier = Modifier.fillMaxWidth())
    if (s.resumed && c.step > 1) Muted(stringResource(R.string.pz_resumed), Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(22.dp))

    AbTiles(stringResource(R.string.pz_sound_a), stringResource(R.string.pz_sound_b), playing, progress) { flow.listen(it) }
    if (playing >= 0) {
        TextButton(onClick = { flow.stopListening() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(ReliefIcons.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.afc_stop))
        }
    } else Spacer(Modifier.height(48.dp))

    Text(stringResource(R.string.pz_question), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
    val ready = s.heardA && s.heardB
    if (!ready) Muted(stringResource(R.string.pz_listen_both), Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(onClick = { flow.answer(Personalizer.Answer.A_BETTER) }, enabled = ready, modifier = Modifier.weight(1f).height(52.dp)) {
            Text(stringResource(R.string.pz_a_better))
        }
        FilledTonalButton(onClick = { flow.answer(Personalizer.Answer.B_BETTER) }, enabled = ready, modifier = Modifier.weight(1f).height(52.dp)) {
            Text(stringResource(R.string.pz_b_better))
        }
    }
    OutlinedButton(
        onClick = { flow.answer(Personalizer.Answer.NO_DIFFERENCE) },
        enabled = ready,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp),
    ) { Text(stringResource(R.string.pz_no_difference)) }

    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { flow.back() }, enabled = s.canBack) { Text(stringResource(R.string.back)) }
        TextButton(onClick = { flow.answer(Personalizer.Answer.SKIP) }) { Text(stringResource(R.string.pz_skip)) }
    }
    Muted(stringResource(R.string.pz_safety), Modifier.padding(top = 12.dp))
}

@Composable
private fun ColumnScope.Result(graph: AppGraph, s: PersonalizeFlow.State, playing: Int, progress: Float, onClose: () -> Unit) {
    val flow = graph.personalize
    val base = s.base ?: return
    var name by rememberSaveable(base.id) { mutableStateOf(graph.newPersonalName(base)) }

    if (s.noClearPreference) {
        Header(stringResource(R.string.pz_result_title), null)
        Body(stringResource(R.string.pz_no_clear))
        Button(onClick = { flow.discard(); onClose() }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(52.dp)) {
            Text(stringResource(R.string.pz_keep_original, base.name))
        }
        OutlinedButton(onClick = { flow.refine() }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp)) {
            Text(stringResource(R.string.pz_refine))
        }
        TextButton(onClick = { graph.savePersonalized(name); onClose() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(stringResource(R.string.pz_save_anyway))
        }
        return
    }

    Header(stringResource(R.string.pz_result_title), null)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(40) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    )
    Button(
        onClick = { flow.listen(1) },
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp),
    ) {
        Icon(if (playing == 1) ReliefIcons.Stop else ReliefIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(if (playing == 1) R.string.afc_stop else R.string.play))
    }

    Text(stringResource(R.string.pz_compare_original), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))
    AbTiles(stringResource(R.string.variant_original), stringResource(R.string.pz_personalized), playing, progress) { flow.listen(it) }
    Muted(stringResource(R.string.pz_matched), Modifier.padding(top = 8.dp))

    Button(onClick = { graph.savePersonalized(name); onClose() }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(52.dp)) {
        Text(stringResource(R.string.pz_save))
    }
    OutlinedButton(onClick = { flow.refine() }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp)) {
        Text(stringResource(R.string.pz_refine))
    }
    TextButton(onClick = { flow.discard(); onClose() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(stringResource(R.string.pz_discard), color = LocalReliefColors.current.muted)
    }
    Muted(stringResource(R.string.pz_track_hint), Modifier.padding(top = 10.dp))
}
