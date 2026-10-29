package com.tinnitusrelief.app.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tinnitusrelief.app.dsp.Band
import com.tinnitusrelief.app.dsp.Evidence
import com.tinnitusrelief.app.dsp.GainCurve
import com.tinnitusrelief.app.ui.theme.LocalReliefColors
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** A quiet card that groups one topic. */
@Composable
fun ReliefCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Native-style section header: short, sentence case, in the accent colour. */
@Composable
fun Eyebrow(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = color)
}

/** A plain Android list row: optional icon, title, one line of supporting text, chevron. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    trailing: String? = null,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val muted = LocalReliefColors.current.muted
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) androidx.compose.material3.Icon(icon, contentDescription = null, tint = muted)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 2)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodyMedium, color = muted)
        if (chevron && onClick != null) androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = muted,
        )
    }
}

/** Section title above a group of list rows. */
@Composable
fun ListHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
    )
}

/** White rounded group of rows (light) / raised group (dark), like system settings. */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { Column(content = content) }
}

/** Hairline between rows inside a [RowGroup], inset to the text. */
@Composable
fun GroupDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = LocalReliefColors.current.hairline,
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalReliefColors.current.muted, modifier = modifier)
}

/** A pill that can be selected. */
@Composable
fun Choice(text: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = if (selected) scheme.primary else scheme.surfaceContainerHigh,
        contentColor = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(options: List<T>, selected: T?, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) Choice(label(o), o == selected, { onPick(o) })
    }
}

/** 0–10 rating: "How noticeable is your tinnitus right now?" */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RatingPicker(value: Int?, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0..10) {
            val sel = value == i
            val scheme = MaterialTheme.colorScheme
            Surface(
                onClick = { onPick(i) },
                shape = RoundedCornerShape(12.dp),
                color = if (sel) scheme.primary else scheme.surfaceContainerHigh,
                contentColor = if (sel) scheme.onPrimary else scheme.onSurface,
                modifier = Modifier.size(42.dp),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("$i", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onFinished: (() -> Unit)? = null,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = LocalReliefColors.current.muted)
        }
        val accent = MaterialTheme.colorScheme.primary
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onFinished,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = accent.copy(alpha = 0.16f)),
        )
    }
}

/** Shows how the evidence for a mode is classified. */
@Composable
fun EvidenceTag(e: Evidence, published: String, generator: String) {
    val c = LocalReliefColors.current
    Text(
        if (e == Evidence.PUBLISHED_PARAMETERS) published else generator,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
        color = if (e == Evidence.PUBLISHED_PARAMETERS) MaterialTheme.colorScheme.primary else c.muted,
    )
}

/** Remaining time until [endsAtElapsed] as m:ss, ticking once a second. */
@Composable
fun countdown(endsAtElapsed: Long?): String {
    if (endsAtElapsed == null) return ""
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(endsAtElapsed) {
        while (true) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    val left = ((endsAtElapsed - now + 999) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(left / 60, left % 60)
}

/** Elapsed time since [startElapsed] as m:ss. */
@Composable
fun stopwatch(startElapsed: Long?): String {
    if (startElapsed == null) return ""
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startElapsed) {
        while (true) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    val s = ((now - startElapsed) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatHz(hz: Double): String = if (hz >= 1000) "%.2f kHz".format(hz / 1000) else "${hz.roundToInt()} Hz"

/**
 * Frequency response of a band list on a log axis 100 Hz – 16 kHz, ±15 dB, with the pitch marked.
 * Drawn from the same function the DSP uses (GainCurve.dbAt), so what you see is what is applied.
 */
@Composable
fun ResponseCurve(bands: List<Band>, pitchHz: Double?, transitionOctaves: Double, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = LocalReliefColors.current.hairline
    val mark = LocalReliefColors.current.warn
    Canvas(modifier.fillMaxWidth().height(110.dp)) {
        val fMin = 100.0; val fMax = 16000.0
        fun x(f: Double) = (ln(f / fMin) / ln(fMax / fMin) * size.width).toFloat()
        fun y(db: Double) = (size.height / 2 - db / 15.0 * size.height / 2 * 0.9).toFloat()
        for (f in listOf(250.0, 1000.0, 4000.0, 16000.0)) drawLine(grid, Offset(x(f), 0f), Offset(x(f), size.height), 1f)
        drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1f)
        val p = Path()
        val n = 160
        for (i in 0..n) {
            val f = fMin * (fMax / fMin).pow(i / n.toDouble())
            val db = GainCurve.dbAt(f, bands, transitionOctaves).coerceIn(-15.0, 15.0)
            if (i == 0) p.moveTo(x(f), y(db)) else p.lineTo(x(f), y(db))
        }
        drawPath(p, accent, style = Stroke(width = 2.5f))
        if (pitchHz != null) drawLine(mark, Offset(x(pitchHz), 0f), Offset(x(pitchHz), size.height), 2f)
    }
}

/** Large listen button used by A/B comparisons: tap to play, tap again to stop; thin bar shows progress. */
@Composable
fun ListenTile(
    label: String,
    playing: Boolean,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.height(128.dp),
        shape = RoundedCornerShape(24.dp),
        color = if (selected || playing) scheme.primaryContainer else scheme.surfaceContainerLowest,
        contentColor = if (selected || playing) scheme.onPrimaryContainer else scheme.onSurface,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, scheme.primary) else null,
    ) {
        androidx.compose.foundation.layout.Box {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.Icon(
                    if (playing) ReliefIcons.Stop else ReliefIcons.Play,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            if (playing) {
                androidx.compose.foundation.layout.Box(
                    Modifier.align(Alignment.BottomStart)
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(scheme.primary),
                )
            }
        }
    }
}
