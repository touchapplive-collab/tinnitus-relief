package com.tinnitusrelief.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.ui.theme.LocalReliefColors

/**
 * Phase 1 placeholder. The Acoustic Lab (frequency matching, signal generator, relief test)
 * is built in a later phase, after the masker is verified on devices.
 */
@Composable
fun LabScreen() {
    val extra = LocalReliefColors.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            stringResource(R.string.lab_experimental).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = extra.warn,
        )
        Text(stringResource(R.string.tab_lab), style = MaterialTheme.typography.displaySmall)
        Body(stringResource(R.string.lab_intro))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.lab_coming_title), style = MaterialTheme.typography.titleMedium)
                Body(stringResource(R.string.lab_coming_body))
            }
        }
        Text(
            stringResource(R.string.lab_disclaimer),
            style = MaterialTheme.typography.bodyMedium,
            color = extra.muted,
        )
    }
}
