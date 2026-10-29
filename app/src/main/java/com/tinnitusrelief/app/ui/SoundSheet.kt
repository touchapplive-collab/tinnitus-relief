package com.tinnitusrelief.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinnitusrelief.app.AppGraph
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.audio.MixerCore
import com.tinnitusrelief.app.data.Sound
import com.tinnitusrelief.app.data.SoundKind
import com.tinnitusrelief.app.ui.theme.LocalReliefColors

/** The mixer: up to three sounds with their own volume, presets, and import. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SoundSheet(graph: AppGraph, onImport: () -> Unit, onDismiss: () -> Unit) {
    val sounds by graph.sounds.collectAsStateWithLifecycle()
    val mix by graph.mix.collectAsStateWithLifecycle()
    val presets by graph.presets.collectAsStateWithLifecycle()
    val loading by graph.loadingSounds.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf("") }
    val extra = LocalReliefColors.current
    val accent = MaterialTheme.colorScheme.primary

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.padding(horizontal = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.sounds_title), style = MaterialTheme.typography.headlineSmall)
            Muted(stringResource(R.string.mixer_subtitle, MixerCore.MAX_MASKER_CHANNELS))

            // ---- current mix ----
            Eyebrow(stringResource(R.string.mixer_in_mix))
            val rows = mix.mapNotNull { e -> sounds.firstOrNull { it.id == e.soundId }?.let { e to it } }
            for ((entry, s) in rows) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.name + if (entry.soundId in loading) " · " + stringResource(R.string.status_loading) else "",
                            style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        )
                        Slider(
                            value = entry.volume,
                            onValueChange = { graph.setSoundVolume(s.id, it) },
                            onValueChangeFinished = { graph.commitMix() },
                            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = accent.copy(alpha = 0.16f)),
                        )
                    }
                    IconButton(onClick = { graph.toggleSound(s.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.mixer_remove), tint = extra.muted)
                    }
                }
            }

            // ---- presets ----
            Spacer(Modifier.height(6.dp))
            Eyebrow(stringResource(R.string.presets_title))
            if (presets.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in presets) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    p.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { graph.applyPreset(p) }
                                        .padding(start = 14.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                                )
                                IconButton(onClick = { graph.deletePreset(p) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.delete), modifier = Modifier.size(14.dp), tint = extra.muted)
                                }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it.take(40) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.preset_name_hint)) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { graph.savePreset(presetName); presetName = "" }, enabled = mix.isNotEmpty()) {
                    Text(stringResource(R.string.preset_save))
                }
            }

            // ---- library ----
            Spacer(Modifier.height(6.dp))
            Eyebrow(stringResource(R.string.mixer_library))
            for (s in sounds) {
                SoundRow(
                    sound = s,
                    inMix = mix.any { it.soundId == s.id },
                    confirming = confirmDelete == s.id,
                    onToggle = { graph.toggleSound(s.id) },
                    onDelete = {
                        if (confirmDelete == s.id) { graph.delete(s); confirmDelete = null } else confirmDelete = s.id
                    },
                )
                HorizontalDivider(color = extra.hairline)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.import_sound))
            }
            Muted(stringResource(R.string.import_hint))
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SoundRow(sound: Sound, inMix: Boolean, confirming: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val extra = LocalReliefColors.current
    val subtitle = when (sound.kind) {
        SoundKind.BUILT_IN -> stringResource(if (sound.customized) R.string.kind_custom else R.string.kind_builtin)
        SoundKind.GENERATED -> stringResource(R.string.kind_generated)
        SoundKind.IMPORTED -> stringResource(R.string.kind_imported)
        SoundKind.PERSONALIZED -> stringResource(R.string.kind_personalized)
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(sound.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = extra.muted)
        }
        if (sound.kind == SoundKind.IMPORTED || sound.kind == SoundKind.PERSONALIZED) {
            if (confirming) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete_confirm), color = extra.warn) }
            } else {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), tint = extra.muted)
                }
            }
        }
        Icon(
            if (inMix) Icons.Filled.Check else Icons.Filled.Add,
            contentDescription = stringResource(if (inMix) R.string.selected else R.string.mixer_add),
            tint = if (inMix) MaterialTheme.colorScheme.primary else extra.muted,
        )
    }
}
