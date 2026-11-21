package com.batterymaster.ai.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterymaster.ai.ui.util.Format
import kotlin.math.abs

/** One plotted value. [low]/[high] carry either the bucket min/max or a prediction interval. */
data class ChartPoint(val x: Long, val y: Float, val low: Float? = null, val high: Float? = null)

data class ChartSeries(
    val name: String,
    val points: List<ChartPoint>,
    val color: Color,
    val dashed: Boolean = false,
    val fillRange: Boolean = false,
)

/** Visible-window statistics, shown under the chart. */
data class ChartStats(val min: Float, val max: Float, val avg: Float)

/**
 * Reusable interactive line chart drawn on a Compose Canvas.
 * Pinch to zoom, drag to pan, tap for exact values, double-tap to reset.
 * Lines break across data gaps instead of interpolating missing data.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    unit: String,
    modifier: Modifier = Modifier,
    xStart: Long? = null,
    xEnd: Long? = null,
    gapMs: Long = Long.MAX_VALUE,
    height: Dp = 240.dp,
    decimals: Int = 1,
    hidden: Set<String> = emptySet(),
    showStats: Boolean = true,
) {
    val allPoints = series.flatMap { it.points }
    if (allPoints.isEmpty()) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("No data recorded for this period", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val fullStart = xStart ?: allPoints.minOf { it.x }
    val fullEndRaw = xEnd ?: allPoints.maxOf { it.x }
    val fullEnd = if (fullEndRaw <= fullStart) fullStart + 60_000L else fullEndRaw
    val fullSpan = (fullEnd - fullStart).toDouble()

    var viewStart by remember(fullStart, fullEnd) { mutableStateOf(fullStart.toDouble()) }
    var viewEnd by remember(fullStart, fullEnd) { mutableStateOf(fullEnd.toDouble()) }
    var selectedX by remember(fullStart, fullEnd) { mutableStateOf<Long?>(null) }

    val visible = series.filter { it.name !in hidden }
    val inView = visible.flatMap { s -> s.points.filter { it.x >= viewStart && it.x <= viewEnd } }
    val yValues = inView.flatMap { listOfNotNull(it.y, it.low, it.high) }
    var yMin = yValues.minOrNull() ?: 0f
    var yMax = yValues.maxOrNull() ?: 1f
    if (yMax - yMin < 1e-3f) { yMin -= 1f; yMax += 1f }
    val pad = (yMax - yMin) * 0.08f
    yMin -= pad
    yMax += pad

    // Stats over the primary (first visible) series inside the viewport.
    val primary = visible.firstOrNull()?.points?.filter { it.x >= viewStart && it.x <= viewEnd }.orEmpty()
    val stats = if (primary.isEmpty()) null else ChartStats(
        primary.minOf { it.low ?: it.y }, primary.maxOf { it.high ?: it.y }, primary.map { it.y }.average().toFloat(),
    )

    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val tooltipBg = MaterialTheme.colorScheme.inverseSurface
    val tooltipFg = MaterialTheme.colorScheme.inverseOnSurface
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val tipStyle = TextStyle(fontSize = 11.sp, color = tooltipFg)
    val leftPad = 46.dp
    val bottomPad = 22.dp
    val topPad = 8.dp
    val rightPad = 10.dp

    val description = "${visible.joinToString { it.name }} chart, ${Format.num(yMin.toDouble())} to ${Format.num(yMax.toDouble())} $unit"

    Column(modifier.fillMaxWidth()) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(fullStart, fullEnd) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val plotW = size.width - leftPad.toPx() - rightPad.toPx()
                    if (plotW <= 0f) return@detectTransformGestures
                    val span = viewEnd - viewStart
                    val minSpan = minOf(maxOf(fullSpan / 60.0, 60_000.0), fullSpan)
                    val newSpan = (span / zoom).coerceIn(minSpan, fullSpan)
                    val f = ((centroid.x - leftPad.toPx()) / plotW).coerceIn(0f, 1f)
                    val anchor = viewStart + f * span
                    var ns = anchor - f * newSpan - pan.x / plotW * newSpan
                    ns = ns.coerceIn(fullStart.toDouble(), fullEnd - newSpan)
                    viewStart = ns
                    viewEnd = ns + newSpan
                }
            }
            .pointerInput(fullStart, fullEnd, visible.size) {
                detectTapGestures(
                    onDoubleTap = {
                        viewStart = fullStart.toDouble()
                        viewEnd = fullEnd.toDouble()
                        selectedX = null
                    },
                    onTap = { pos ->
                        val plotW = size.width - leftPad.toPx() - rightPad.toPx()
                        if (plotW <= 0f) return@detectTapGestures
                        val x = viewStart + ((pos.x - leftPad.toPx()) / plotW).coerceIn(0f, 1f) * (viewEnd - viewStart)
                        selectedX = visible.flatMap { it.points }.minByOrNull { abs(it.x - x) }?.x
                    },
                )
            },
    ) {
        val left = leftPad.toPx()
        val top = topPad.toPx()
        val right = size.width - rightPad.toPx()
        val bottom = size.height - bottomPad.toPx()
        val pw = right - left
        val ph = bottom - top
        val vs = viewStart
        val ve = viewEnd
        fun xp(x: Long): Float = left + ((x - vs) / (ve - vs) * pw).toFloat()
        fun yp(y: Float): Float = bottom - (y - yMin) / (yMax - yMin) * ph

        // Horizontal grid + Y labels
        for (i in 0..4) {
            val v = yMin + (yMax - yMin) * i / 4f
            val y = yp(v)
            drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
            val label = textMeasurer.measure(Format.num(v.toDouble(), decimals), labelStyle)
            drawText(label, topLeft = Offset(left - label.size.width - 6f, y - label.size.height / 2f))
        }
        // X labels
        for (i in 0..3) {
            val t = (vs + (ve - vs) * i / 3.0).toLong()
            val label = textMeasurer.measure(Format.axisTime(t, (ve - vs).toLong()), labelStyle)
            val maxX = maxOf(left - 10f, right - label.size.width)
            val x = (left + pw * i / 3f - label.size.width / 2f).coerceIn(left - 10f, maxX)
            drawText(label, topLeft = Offset(x, bottom + 4f))
        }

        clipRect(left, top, right, bottom) {
            for (s in visible) {
                val pts = s.points.sortedBy { it.x }
                if (s.fillRange) {
                    // Filled band between low and high, broken across gaps.
                    var segment = ArrayList<ChartPoint>()
                    fun flush() {
                        if (segment.size >= 2) {
                            val band = Path()
                            segment.forEachIndexed { i, p ->
                                val px = xp(p.x)
                                val py = yp(p.high ?: p.y)
                                if (i == 0) band.moveTo(px, py) else band.lineTo(px, py)
                            }
                            segment.asReversed().forEach { p -> band.lineTo(xp(p.x), yp(p.low ?: p.y)) }
                            band.close()
                            drawPath(band, s.color.copy(alpha = 0.15f))
                        }
                        segment = ArrayList()
                    }
                    for ((i, p) in pts.withIndex()) {
                        if (i > 0 && p.x - pts[i - 1].x > gapMs) flush()
                        segment.add(p)
                    }
                    flush()
                }
                val line = Path()
                var started = false
                for ((i, p) in pts.withIndex()) {
                    val px = xp(p.x)
                    val py = yp(p.y)
                    if (!started || (i > 0 && p.x - pts[i - 1].x > gapMs)) {
                        line.moveTo(px, py)
                        started = true
                    } else {
                        line.lineTo(px, py)
                    }
                }
                drawPath(
                    line, s.color,
                    style = Stroke(
                        width = 2.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null,
                    ),
                )
                if (pts.size == 1) drawCircle(s.color, 3.dp.toPx(), Offset(xp(pts[0].x), yp(pts[0].y)))
            }
        }

        // Selection crosshair and tooltip
        val sel = selectedX
        if (sel != null && sel >= vs && sel <= ve) {
            val sx = xp(sel)
            drawLine(labelColor, Offset(sx, top), Offset(sx, bottom), strokeWidth = 1.5f)
            val lines = mutableListOf(Format.dateTime(sel))
            for (s in visible) {
                val p = s.points.minByOrNull { abs(it.x - sel) } ?: continue
                drawCircle(s.color, 4.dp.toPx(), Offset(xp(p.x), yp(p.y)))
                var txt = "${s.name}: ${Format.num(p.y.toDouble(), decimals)} $unit"
                if (p.low != null && p.high != null) {
                    txt += " (${Format.num(p.low.toDouble(), decimals)}–${Format.num(p.high.toDouble(), decimals)})"
                }
                lines += txt
            }
            val measured = lines.map { textMeasurer.measure(it, tipStyle) }
            val w = measured.maxOf { it.size.width } + 16f
            val h = measured.sumOf { it.size.height } + 12f
            val bx = if (sx + w + 8f < right) sx + 8f else sx - w - 8f
            drawRoundRect(tooltipBg, Offset(bx, top), Size(w, h), CornerRadius(12f, 12f))
            var ty = top + 6f
            for (m in measured) {
                drawText(m, topLeft = Offset(bx + 8f, ty))
                ty += m.size.height
            }
        }
    }
    if (showStats && stats != null) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            StatLabel("Min", "${Format.num(stats.min.toDouble(), decimals)} $unit")
            StatLabel("Avg", "${Format.num(stats.avg.toDouble(), decimals)} $unit")
            StatLabel("Max", "${Format.num(stats.max.toDouble(), decimals)} $unit")
        }
        Text(
            "Pinch to zoom · drag to pan · tap for values · double-tap to reset",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    }
}

@Composable
private fun StatLabel(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
