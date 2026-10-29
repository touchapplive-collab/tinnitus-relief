package com.tinnitusrelief.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tinnitusrelief.app.AppGraph
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.ui.theme.LocalReliefColors

private const val TAB_RELIEF = 0
private const val TAB_LAB = 1
private const val TAB_SETTINGS = 2

@Composable
fun AppRoot(graph: AppGraph, controlsReady: Boolean, onPlayPause: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_RELIEF) }
    var showSounds by rememberSaveable { mutableStateOf(false) }
    var personalizeId by rememberSaveable { mutableStateOf<String?>(null) }
    var choosePersonalize by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { graph.messages.collect { snackbar.showSnackbar(it) } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) graph.importSound(uri)
    }
    val showerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) graph.replaceShower(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                NavItem(tab == TAB_RELIEF, ReliefIcons.Drop, stringResource(R.string.tab_relief)) { tab = TAB_RELIEF }
                NavItem(tab == TAB_LAB, ReliefIcons.Wave, stringResource(R.string.tab_lab)) { tab = TAB_LAB }
                NavItem(tab == TAB_SETTINGS, Icons.Filled.Settings, stringResource(R.string.tab_settings)) { tab = TAB_SETTINGS }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Crossfade(targetState = tab, animationSpec = tween(280), label = "tab") { t ->
                when (t) {
                    TAB_RELIEF -> {
                        val pid = personalizeId
                        if (pid != null) {
                            PersonalizeScreen(graph, pid, onClose = { personalizeId = null })
                        } else ReliefScreen(
                            graph = graph,
                            controlsReady = controlsReady,
                            onPlayPause = onPlayPause,
                            onOpenSounds = { showSounds = true },
                            onOpenLab = { tab = TAB_LAB },
                            onPersonalize = {
                                val m = graph.mix.value
                                if (m.size == 1) personalizeId = m.first().soundId else if (m.size > 1) choosePersonalize = true
                            },
                        )
                    }
                    TAB_LAB -> LabScreen(graph)
                    else -> SettingsScreen(graph, onReplaceShower = { showerLauncher.launch(arrayOf("audio/*")) })
                }
            }
        }
    }

    if (choosePersonalize) {
        val names = graph.mix.value.mapNotNull { e -> graph.sounds.value.firstOrNull { it.id == e.soundId } }
        AlertDialog(
            onDismissRequest = { choosePersonalize = false },
            title = { Text(stringResource(R.string.pz_choose)) },
            text = {
                Column {
                    for (snd in names) {
                        ListRow(snd.name, chevron = true, onClick = { choosePersonalize = false; personalizeId = snd.id })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosePersonalize = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showSounds) {
        SoundSheet(
            graph = graph,
            onImport = { importLauncher.launch(arrayOf("audio/*")) },
            onDismiss = { showSounds = false },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val extra = LocalReliefColors.current
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.onBackground,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = extra.muted,
            unselectedTextColor = extra.muted,
        ),
    )
}
