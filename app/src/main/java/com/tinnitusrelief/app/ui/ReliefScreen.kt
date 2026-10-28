package com.tinnitusrelief.app.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinnitusrelief.app.AppGraph
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.ui.theme.LocalReliefColors
import kotlinx.coroutines.delay

val TIMER_OPTIONS = listOf(0, 15, 30, 60, 90)

@Composable
fun ReliefScreen(
    graph: AppGraph,
    controlsReady: Boolean,
    onPlayPause: () -> Unit,
    onOpenSounds: () -> Unit,
    onOpenLab: () -> Unit,
) {
    val sound by graph.selected.collectAsStateWithLifecycle()
    val playing by graph.isPlaying.collectAsStateWithLifecycle()
    val volume by graph.volume.collectAsStateWithLifecycle()
    val loading by graph.loading.collectAsStateWithLifecycle()
    val timerMinutes by graph.timerMinutes.collectAsStateWithLifecycle()
    val endsAt by graph.timerEndsAt.collectAsStateWithLifecycle()
    val extra = LocalReliefColors.current

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.7f))
        Text(
            stringResource(R.string.relief_label).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = extra.muted,
        )
        Spacer(Modifier.height(10.dp))
        AnimatedContent(
            targetState = sound?.name ?: "",
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) },
            label = "sound-name",
        ) { name ->
            Text(
                name,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                loading -> stringResource(R.string.status_loading)
                playing -> stringResource(R.string.status_playing)
                else -> stringResource(R.string.status_ready)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = extra.muted,
        )

        Spacer(Modifier.weight(1f))
        PlayOrb(playing = playing, enabled = controlsReady && !loading, onClick = onPlayPause)
        Spacer(Modifier.weight(1f))

        VolumeRow(volume, onChange = graph::setVolume, onFinished = graph::commitVolume)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(
                icon = ReliefIcons.Moon,
                text = timerLabel(timerMinutes, endsAt),
                active = timerMinutes > 0,
                onClick = {
                    val i = TIMER_OPTIONS.indexOf(timerMinutes)
                    graph.setTimer(TIMER_OPTIONS[(i + 1) % TIMER_OPTIONS.size])
                },
            )
            Pill(icon = ReliefIcons.Library, text = stringResource(R.string.more_sounds), onClick = onOpenSounds)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.open_lab),
            style = MaterialTheme.typography.labelLarge,
            color = extra.muted,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onOpenLab)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Spacer(Modifier.weight(0.35f))
    }
}

@Composable
private fun timerLabel(minutes: Int, endsAt: Long?): String {
    if (endsAt != null) {
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(endsAt) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1000)
            }
        }
        val left = ((endsAt - now) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(left / 60, left % 60)
    }
    return if (minutes == 0) stringResource(R.string.timer_off)
    else stringResource(R.string.timer_minutes, minutes)
}

@Composable
private fun PlayOrb(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val glow = LocalReliefColors.current.glow
    val breathing = rememberInfiniteTransition(label = "breath")
    val breath by breathing.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath-value",
    )
    val active by animateFloatAsState(if (playing) 1f else 0f, tween(1200), label = "active")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.55f), label = "press")
    val label = stringResource(if (playing) R.string.pause else R.string.play)

    Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension / 2f
            for (i in 0..2) {
                val r = base * (0.56f + 0.14f * i + 0.06f * breath * active * (i + 1) / 3f)
                val a = (0.11f - 0.03f * i) * (0.25f + 0.75f * active)
                drawCircle(glow.copy(alpha = a), radius = r)
            }
        }
        Box(
            modifier = Modifier
                .size(152.dp)
                .scale(pressScale)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (enabled) 1f else 0.5f))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = playing, animationSpec = tween(250), label = "icon") { p ->
                Icon(
                    if (p) ReliefIcons.Pause else ReliefIcons.Play,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(54.dp),
                )
            }
        }
    }
}

@Composable
private fun VolumeRow(value: Float, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    val muted = LocalReliefColors.current.muted
    val accent = MaterialTheme.colorScheme.primary
    val volumeLabel = stringResource(R.string.volume)
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(ReliefIcons.VolumeLow, contentDescription = null, tint = muted, modifier = Modifier.size(20.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            modifier = Modifier.weight(1f).semantics { contentDescription = volumeLabel },
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = accent.copy(alpha = 0.16f),
            ),
        )
        Icon(ReliefIcons.VolumeHigh, contentDescription = null, tint = muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun Pill(icon: ImageVector, text: String, onClick: () -> Unit, active: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (active) scheme.secondaryContainer else scheme.surfaceContainer,
        contentColor = if (active) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
