package com.tinnitusrelief.app.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinnitusrelief.app.AppGraph
import com.tinnitusrelief.app.R
import com.tinnitusrelief.app.ui.theme.LocalReliefColors
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

val TIMER_OPTIONS = listOf(0, 15, 30, 60, 90)

/**
 * Relief, design direction "C · light and airy":
 * greeting and sound name, a soft hero card with a slow living wave, a round play button that overlaps
 * the card, and three white rows (volume, sleep timer, sounds).
 */
@Composable
fun ReliefScreen(
    graph: AppGraph,
    controlsReady: Boolean,
    onPlayPause: () -> Unit,
    onOpenSounds: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenLab: () -> Unit,
) {
    val mix by graph.mix.collectAsStateWithLifecycle()
    val sounds by graph.sounds.collectAsStateWithLifecycle()
    val playing by graph.isPlaying.collectAsStateWithLifecycle()
    val volume by graph.volume.collectAsStateWithLifecycle()
    val loadingIds by graph.loadingSounds.collectAsStateWithLifecycle()
    val labState by graph.sessions.state.collectAsStateWithLifecycle()
    val timerMinutes by graph.timerMinutes.collectAsStateWithLifecycle()
    val endsAt by graph.timerEndsAt.collectAsStateWithLifecycle()
    val title = remember(mix, sounds) { graph.mixTitle() }
    val muted = LocalReliefColors.current.muted

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(28.dp))
        Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = muted)
        AnimatedContent(
            targetState = title,
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) },
            label = "title",
        ) { t ->
            Text(t, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            when {
                loadingIds.isNotEmpty() -> stringResource(R.string.status_loading)
                labState != null -> stringResource(R.string.status_lab_active, labState!!.label)
                playing -> stringResource(R.string.status_playing)
                else -> stringResource(R.string.status_ready)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )

        Spacer(Modifier.height(22.dp))
        Hero(playing = playing, enabled = controlsReady && mix.isNotEmpty(), onPlayPause = onPlayPause)
        Spacer(Modifier.height(48.dp))

        RowGroup {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.volume), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${(volume * 100).roundToInt()} %", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                val accent = MaterialTheme.colorScheme.primary
                val volumeLabel = stringResource(R.string.volume)
                Slider(
                    value = volume,
                    onValueChange = graph::setVolume,
                    onValueChangeFinished = graph::commitVolume,
                    modifier = Modifier.semantics { contentDescription = volumeLabel },
                    colors = SliderDefaults.colors(
                        thumbColor = accent, activeTrackColor = accent,
                        inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
            GroupDivider()
            ListRow(
                title = stringResource(R.string.timer_off),
                trailing = timerLabel(timerMinutes, endsAt),
                icon = ReliefIcons.Moon,
                chevron = false,
                onClick = {
                    val i = TIMER_OPTIONS.indexOf(timerMinutes)
                    graph.setTimer(TIMER_OPTIONS[(i + 1) % TIMER_OPTIONS.size])
                },
            )
            GroupDivider()
            ListRow(
                title = stringResource(R.string.more_sounds),
                trailing = "${mix.size}/3",
                icon = ReliefIcons.Library,
                onClick = onOpenSounds,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun greeting(): String {
    val h = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    return stringResource(
        when (h) {
            in 5..11 -> R.string.greet_morning
            in 12..17 -> R.string.greet_day
            in 18..22 -> R.string.greet_evening
            else -> R.string.greet_night
        }
    )
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
    return if (minutes == 0) stringResource(R.string.timer_none) else stringResource(R.string.timer_minutes, minutes)
}

/** Soft card with a slowly moving wave (still when paused) and the play button overlapping its bottom edge. */
@Composable
private fun Hero(playing: Boolean, enabled: Boolean, onPlayPause: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val waveColor = scheme.primary
    val flow = rememberInfiniteTransition(label = "wave")
    val phase by flow.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val amp by animateFloatAsState(if (playing) 1f else 0.25f, tween(1200), label = "amp")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.55f), label = "press")
    val label = stringResource(if (playing) R.string.pause else R.string.play)

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(28.dp)).background(scheme.primaryContainer),
        ) {
            Canvas(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 40.dp)) {
                val mid = size.height / 2
                for (layer in 0..2) {
                    val p = Path()
                    val a = size.height * 0.28f * amp * (1f - layer * 0.28f)
                    val k = 2f + layer * 0.7f
                    val steps = 120
                    for (i in 0..steps) {
                        val x = size.width * i / steps
                        val t = i.toFloat() / steps
                        val y = mid + a * sin(k * 2 * PI.toFloat() * t + phase * (1 + layer * 0.35f)) *
                            sin(PI.toFloat() * t)
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    drawPath(p, waveColor.copy(alpha = 0.9f - layer * 0.3f), style = Stroke(width = 5f - layer * 1.5f, cap = StrokeCap.Round))
                }
            }
        }
        Box(
            modifier = Modifier
                .offset(y = 32.dp)
                .size(68.dp)
                .scale(pressScale)
                .clip(CircleShape)
                .background(scheme.primary.copy(alpha = if (enabled) 1f else 0.5f))
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onPlayPause)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = playing, animationSpec = tween(250), label = "icon") { p ->
                Icon(if (p) ReliefIcons.Pause else ReliefIcons.Play, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(30.dp))
            }
        }
    }
}
