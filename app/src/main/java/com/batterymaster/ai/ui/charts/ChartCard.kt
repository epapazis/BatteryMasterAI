package com.batterymaster.ai.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.batterymaster.ai.domain.analytics.TimeRange
import com.batterymaster.ai.ui.components.AppCard
import kotlinx.coroutines.launch

@Composable
fun ChartCard(
    title: String,
    series: List<ChartSeries>,
    unit: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    xStart: Long? = null,
    xEnd: Long? = null,
    gapMs: Long = Long.MAX_VALUE,
    decimals: Int = 1,
    height: Dp = 220.dp,
    onExport: ((ImageBitmap) -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    var hidden by remember { mutableStateOf(setOf<String>()) }
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onExport != null && series.any { it.points.isNotEmpty() }) {
                IconButton(onClick = { scope.launch { onExport(layer.toImageBitmap()) } }) {
                    Icon(Icons.Outlined.Share, "Export chart as image")
                }
            }
        }
        if (series.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                series.forEach { s ->
                    FilterChip(
                        selected = s.name !in hidden,
                        onClick = { hidden = if (s.name in hidden) hidden - s.name else hidden + s.name },
                        label = { Text(s.name) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(s.color, CircleShape)) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            },
        ) {
            LineChart(
                series = series, unit = unit, xStart = xStart, xEnd = xEnd, gapMs = gapMs,
                height = height, decimals = decimals, hidden = hidden,
            )
        }
        footer?.let {
            Spacer(Modifier.height(8.dp))
            it()
        }
    }
}

@Composable
fun TimeRangeSelector(selected: TimeRange, onSelect: (TimeRange) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimeRange.entries.forEach { r ->
            FilterChip(selected = r == selected, onClick = { onSelect(r) }, label = { Text(r.label) })
        }
    }
}
