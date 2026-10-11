package com.vh.myrecap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Small chart kit drawn on Canvas (no chart library, so no extra dependency or APK weight).
 * Rules followed: one axis, thin marks, 2 dp lines, rounded data ends anchored to the baseline,
 * recessive grid, text in text colours (never the series colour), selective labels, a legend whenever
 * there are two or more series, tap or drag to read exact values, and a plain-text description for
 * screen readers. Colours come from the theme so Light and Dark both work.
 */

/** Tiny legend swatch + label. */
@Composable
internal fun LegendItem(color: Color, label: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
            drawLine(
                color,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null,
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Running total by day of a month against the previous month, with a dashed forecast to month end.
 * Tap or drag to read a day; [onSelect] gets the 0-based day so the caller can show the numbers in text.
 */
@Composable
internal fun CumulativeChart(
    current: List<Long>,
    previous: List<Long>,
    forecast: Long?,
    daysInMonth: Int,
    selectedDay: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = ink)
    val maxY = maxOf(current.maxOrNull() ?: 0L, previous.maxOrNull() ?: 0L, forecast ?: 0L).coerceAtLeast(1L)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(190.dp)
            .semantics { contentDescription = description }
            .pointerInput(daysInMonth) {
                detectTapGestures { o -> onSelect(dayAt(o.x, size.width.toFloat(), 44.dp.toPx(), daysInMonth)) }
            }
            .pointerInput(daysInMonth) {
                detectHorizontalDragGestures { change, _ -> onSelect(dayAt(change.position.x, size.width.toFloat(), 44.dp.toPx(), daysInMonth)) }
            },
    ) {
        val left = 44.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val width = size.width - left - 8.dp.toPx()
        val height = bottom - top
        fun x(day: Int) = left + width * day / (daysInMonth - 1).coerceAtLeast(1)
        fun y(v: Long) = bottom - height * v / maxY

        // Recessive grid with three value labels.
        for (i in 0..2) {
            val v = maxY * i / 2
            val yy = y(v)
            drawLine(grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1.dp.toPx())
            val t = measurer.measure(shortMoney(v), labelStyle)
            drawText(t, topLeft = Offset(left - 6.dp.toPx() - t.size.width, yy - t.size.height / 2f))
        }
        for (day in listOf(0, 7, 14, 21, daysInMonth - 1).distinct().filter { it < daysInMonth }) {
            val t = measurer.measure("${day + 1}", labelStyle)
            drawText(t, topLeft = Offset(x(day) - t.size.width / 2f, bottom + 4.dp.toPx()))
        }

        fun line(values: List<Long>, color: Color, dashed: Boolean = false) {
            if (values.isEmpty()) return
            val path = Path()
            values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            drawPath(
                path,
                color,
                style = Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null),
            )
        }
        line(previous, muted.copy(alpha = 0.8f), dashed = true)
        if (forecast != null && current.isNotEmpty()) {
            val path = Path().apply {
                moveTo(x(current.size - 1), y(current.last()))
                lineTo(x(daysInMonth - 1), y(forecast))
            }
            drawPath(path, primary.copy(alpha = 0.55f), style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))))
        }
        line(current, primary)

        selectedDay?.let { d ->
            drawLine(ink.copy(alpha = 0.5f), Offset(x(d), top), Offset(x(d), bottom), strokeWidth = 1.dp.toPx())
            fun dot(v: Long?, color: Color) {
                if (v == null) return
                drawCircle(surface, radius = 6.dp.toPx(), center = Offset(x(d), y(v)))
                drawCircle(color, radius = 4.dp.toPx(), center = Offset(x(d), y(v)))
            }
            dot(previous.getOrNull(d), muted)
            dot(current.getOrNull(d), primary)
        }
    }
}

private fun dayAt(px: Float, widthPx: Float, leftPx: Float, days: Int): Int? {
    if (days <= 0) return null
    val ratio = ((px - leftPx) / (widthPx - leftPx - 8f)).coerceIn(0f, 1f)
    return Math.round(ratio * (days - 1))
}

/** Value labels on the axis: "850k", "1,2tr". */
internal fun shortMoney(v: Long): String = when {
    v >= 1_000_000 -> (Math.round(v / 100_000.0) / 10.0).let { if (it == Math.floor(it)) "${it.toLong()}tr" else "$it".replace('.', ',') + "tr" }
    v >= 1_000 -> "${v / 1_000}k"
    else -> "$v"
}

/**
 * Horizontal ranked bars, one per row: name and amount are written out, so colour carries no
 * meaning here. [muted] rows (the "Khác" bucket) are drawn in a neutral colour.
 */
@Composable
internal fun RankedBars(rows: List<RankedRow>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val max = rows.maxOfOrNull { it.value }?.coerceAtLeast(1L) ?: 1L
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(row.valueText, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).background(track, RoundedCornerShape(4.dp))) {
                    Box(
                        Modifier
                            .fillMaxWidth((row.value.toFloat() / max).coerceIn(0.02f, 1f))
                            .height(8.dp)
                            .background(if (row.muted) neutral else primary, RoundedCornerShape(4.dp)),
                    )
                }
                row.caption?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

internal class RankedRow(val label: String, val value: Long, val valueText: String, val caption: String? = null, val muted: Boolean = false)

/**
 * Columns for a short series (six months). The selected column is solid, the others softened; only
 * the selected column and the highest one carry a value label. Tap to select.
 */
@Composable
internal fun ColumnChart(
    labels: List<String>,
    values: List<Long>,
    selected: Int,
    onSelect: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    format: (Long) -> String = ::shortMoney,
) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val strong = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val max = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val highest = values.indices.maxByOrNull { values[it] }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(170.dp)
            .semantics { contentDescription = description }
            .pointerInput(values.size) {
                detectTapGestures { o -> onSelect(((o.x / size.width) * values.size).toInt().coerceIn(0, values.size - 1)) }
            },
    ) {
        val bottom = size.height - 20.dp.toPx()
        val top = 22.dp.toPx()
        val slot = size.width / values.size
        val barWidth = (slot * 0.5f).coerceAtMost(36.dp.toPx())
        drawLine(grid, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
        values.forEachIndexed { i, v ->
            val h = (bottom - top) * v / max
            val cx = slot * i + slot / 2
            if (v > 0) {
                val r = 4.dp.toPx()
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            Rect(Offset(cx - barWidth / 2, bottom - h), Size(barWidth, h)),
                            topLeft = CornerRadius(r), topRight = CornerRadius(r),
                            bottomLeft = CornerRadius.Zero, bottomRight = CornerRadius.Zero,
                        ),
                    )
                }
                drawPath(path, primary.copy(alpha = if (i == selected) 1f else 0.35f))
            }
            val tl = measurer.measure(labels[i], TextStyle(fontSize = 11.sp, color = if (i == selected) strong else ink))
            drawText(tl, topLeft = Offset(cx - tl.size.width / 2f, bottom + 4.dp.toPx()))
            if (v > 0 && (i == selected || i == highest)) {
                val tv = measurer.measure(format(v), TextStyle(fontSize = 11.sp, color = strong))
                drawText(tv, topLeft = Offset(cx - tv.size.width / 2f, bottom - h - tv.size.height - 2.dp.toPx()))
            }
        }
    }
}

/** A "needs attention" line: warning icon plus words, so the state never rests on colour alone. */
@Composable
internal fun AlertRow(message: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.WarningAmber, null, tint = scheme.onErrorContainer, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = scheme.onErrorContainer)
        }
    }
    if (onClick != null) {
        androidx.compose.material3.Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = scheme.errorContainer, modifier = modifier.fillMaxWidth(), content = content)
    } else {
        androidx.compose.material3.Surface(shape = RoundedCornerShape(16.dp), color = scheme.errorContainer, modifier = modifier.fillMaxWidth(), content = content)
    }
}
