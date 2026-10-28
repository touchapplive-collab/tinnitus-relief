package com.tinnitusrelief.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import com.tinnitusrelief.app.data.Sound
import com.tinnitusrelief.app.data.SoundKind
import com.tinnitusrelief.app.ui.theme.LocalReliefColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSheet(graph: AppGraph, onImport: () -> Unit, onDismiss: () -> Unit) {
    val sounds by graph.sounds.collectAsStateWithLifecycle()
    val selected by graph.selected.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val extra = LocalReliefColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.sounds_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.sounds_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(sounds, key = { it.id }) { s ->
                    SoundRow(
                        sound = s,
                        selected = s.id == selected?.id,
                        confirming = confirmDelete == s.id,
                        onClick = { graph.select(s); onDismiss() },
                        onDelete = {
                            if (confirmDelete == s.id) { graph.delete(s); confirmDelete = null }
                            else confirmDelete = s.id
                        },
                    )
                    HorizontalDivider(color = extra.hairline)
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.import_sound))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.import_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SoundRow(
    sound: Sound,
    selected: Boolean,
    confirming: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val extra = LocalReliefColors.current
    val subtitle = when (sound.kind) {
        SoundKind.BUILT_IN -> stringResource(if (sound.customized) R.string.kind_custom else R.string.kind_builtin)
        SoundKind.GENERATED -> stringResource(R.string.kind_generated)
        SoundKind.IMPORTED -> stringResource(R.string.kind_imported)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(sound.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = extra.muted)
        }
        if (sound.kind == SoundKind.IMPORTED) {
            if (confirming) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.delete_confirm), color = extra.warn)
                }
            } else {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), tint = extra.muted)
                }
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.selected),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
