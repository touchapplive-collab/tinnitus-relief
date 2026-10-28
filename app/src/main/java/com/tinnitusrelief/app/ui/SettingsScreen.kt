package com.tinnitusrelief.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinnitusrelief.app.AppGraph
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.data.SoundLibrary

/** Settings as a plain Android list. Long texts open on their own page. */
@Composable
fun SettingsScreen(graph: AppGraph, onReplaceShower: () -> Unit) {
    val sounds by graph.sounds.collectAsStateWithLifecycle()
    val customized = sounds.firstOrNull { it.id == SoundLibrary.SHOWER }?.customized == true
    val context = LocalContext.current
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""
    // 0 = list, otherwise the string resource of the page body
    var page by rememberSaveable { mutableIntStateOf(0) }
    var pageTitle by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = page != 0) { page = 0 }

    if (page != 0) {
        LabPageColumn(stringResource(pageTitle), onBack = { page = 0 }) { Body(stringResource(page)) }
        return
    }
    fun open(title: Int, body: Int) { pageTitle = title; page = body }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle(stringResource(R.string.tab_settings))

        ListHeader(stringResource(R.string.settings_shower_title))
        ListRow(
            stringResource(R.string.settings_shower_choose),
            subtitle = stringResource(if (customized) R.string.kind_custom else R.string.kind_builtin),
            icon = ReliefIcons.Drop,
            onClick = onReplaceShower,
        )
        if (customized) ListRow(
            stringResource(R.string.settings_shower_restore),
            icon = Icons.Filled.Refresh,
            chevron = false,
            onClick = { graph.restoreShower() },
        )

        ListHeader(stringResource(R.string.settings_info_header))
        ListRow(stringResource(R.string.settings_safety_title), icon = Icons.Filled.Warning,
            onClick = { open(R.string.settings_safety_title, R.string.settings_safety_body) })
        ListRow(stringResource(R.string.settings_battery_title), icon = ReliefIcons.Moon,
            onClick = { open(R.string.settings_battery_title, R.string.settings_battery_body) })
        ListRow(stringResource(R.string.settings_research_title), icon = ReliefIcons.Wave,
            onClick = { open(R.string.settings_research_title, R.string.settings_research_body) })
        ListRow(stringResource(R.string.settings_about_title), icon = Icons.Filled.Info,
            trailing = version, onClick = { open(R.string.settings_about_title, R.string.settings_about_body) })
    }
}

@Composable
fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
