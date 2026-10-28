package com.tinnitusrelief.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinnitusrelief.app.AppGraph
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.data.SoundLibrary
import com.tinnitusrelief.app.ui.theme.LocalReliefColors

@Composable
fun SettingsScreen(graph: AppGraph, onReplaceShower: () -> Unit) {
    val sounds by graph.sounds.collectAsStateWithLifecycle()
    val customized = sounds.firstOrNull { it.id == SoundLibrary.SHOWER }?.customized == true
    val extra = LocalReliefColors.current
    val context = LocalContext.current
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.displaySmall)

        Section(stringResource(R.string.settings_shower_title)) {
            Body(stringResource(R.string.settings_shower_body))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onReplaceShower) { Text(stringResource(R.string.settings_shower_choose)) }
                if (customized) {
                    OutlinedButton(onClick = { graph.restoreShower() }) {
                        Text(stringResource(R.string.settings_shower_restore))
                    }
                }
            }
        }

        Section(stringResource(R.string.settings_safety_title)) {
            Body(stringResource(R.string.settings_safety_body))
        }

        Section(stringResource(R.string.settings_battery_title)) {
            Body(stringResource(R.string.settings_battery_body))
        }

        Section(stringResource(R.string.settings_research_title)) {
            Body(stringResource(R.string.settings_research_body))
        }

        Section(stringResource(R.string.settings_about_title)) {
            Body(stringResource(R.string.settings_about_body))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_version, version),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
            )
        }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = LocalReliefColors.current.muted,
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
