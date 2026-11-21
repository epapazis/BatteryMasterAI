package com.batterymaster.ai.ui.analytics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterymaster.ai.R
import com.batterymaster.ai.domain.analytics.ChartCategory
import com.batterymaster.ai.domain.analytics.TimeRange
import com.batterymaster.ai.domain.model.BucketValue
import com.batterymaster.ai.domain.prediction.CapacityForecast
import com.batterymaster.ai.ui.charts.ChartCard
import com.batterymaster.ai.ui.charts.ChartPoint
import com.batterymaster.ai.ui.charts.ChartSeries
import com.batterymaster.ai.ui.charts.TimeRangeSelector
import com.batterymaster.ai.ui.components.AppCard
import com.batterymaster.ai.ui.theme.LocalDataColors
import com.batterymaster.ai.ui.util.Format

private fun List<BucketValue>.toPoints(): List<ChartPoint> =
    map { ChartPoint(it.time, it.avg.toFloat(), it.min.toFloat(), it.max.toFloat()) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(padding: PaddingValues, vm: AnalyticsViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = LocalDataColors.current
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.shareEvents.collect { runCatching { context.startActivity(it) } }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChartCategory.entries.forEach { c ->
                    FilterChip(selected = st.category == c, onClick = { vm.setCategory(c) }, label = { Text(c.label) })
                }
            }
        }
        if (st.category != ChartCategory.HEALTH) {
            item {
                TimeRangeSelector(st.range, onSelect = { r ->
                    if (r == TimeRange.CUSTOM) showPicker = true else vm.setRange(r)
                })
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${Format.dateTime(st.from)} – ${Format.dateTime(st.to)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(R.string.compare_previous), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = st.compare, onCheckedChange = vm::setCompare, modifier = Modifier.height(32.dp))
                }
            }
        }

        if (st.category == ChartCategory.HEALTH) {
            item {
                val accepted = st.estimates.filter { it.accepted && it.capacityMah != null }.sortedBy { it.timestamp }
                val series = buildList {
                    add(ChartSeries("Estimated capacity", accepted.map { ChartPoint(it.timestamp, it.capacityMah!!.toFloat()) }, colors.estimated))
                    (st.forecast as? CapacityForecast.Available)?.let { fc ->
                        add(ChartSeries("Forecast (95% band)", fc.points.map {
                            ChartPoint(it.time, it.value.toFloat(), it.low.toFloat(), it.high.toFloat())
                        }, colors.prediction, dashed = true, fillRange = true))
                    }
                }
                ChartCard(
                    title = "Capacity history & forecast",
                    subtitle = when (val fc = st.forecast) {
                        is CapacityForecast.Available -> "Trend ${Format.num(fc.changeMahPerYear, 0)} mAh/year · ${fc.confidence.label} confidence"
                        is CapacityForecast.Insufficient -> fc.reason
                        null -> null
                    },
                    series = series, unit = "mAh", decimals = 0,
                    onExport = { vm.exportChart(it.asAndroidBitmap(), "capacity") },
                )
            }
            item {
                AppCard {
                    Text(stringResource(R.string.prediction_disclaimer), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            val gap = maxOf(st.bucketMs * 3, 25 * 60_000L)
            items(st.metrics, key = { it.metric.name }) { md ->
                val series = buildList {
                    add(ChartSeries(md.metric.label, md.current.toPoints(), colors.series[0], fillRange = st.bucketMs > 60_000L))
                    md.previous?.let { add(ChartSeries("Previous period", it.toPoints(), colors.series[1], dashed = true)) }
                }
                ChartCard(
                    title = md.metric.label,
                    subtitle = if (st.bucketMs > 60_000L) "Line = average, band = min–max per ${Format.durationMs(st.bucketMs)}" else null,
                    series = series,
                    unit = md.metric.unit,
                    xStart = st.from, xEnd = st.to, gapMs = gap,
                    decimals = if (md.metric.unit == "W" || md.metric.unit == "°C") 1 else 0,
                    onExport = { vm.exportChart(it.asAndroidBitmap(), md.metric.name.lowercase()) },
                )
            }
            if (!st.loading && st.metrics.all { it.current.isEmpty() }) {
                item {
                    AppCard {
                        Text(stringResource(R.string.no_chart_data), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedStartDateMillis != null,
                    onClick = {
                        val start = pickerState.selectedStartDateMillis
                        val end = pickerState.selectedEndDateMillis ?: start
                        if (start != null && end != null) vm.setCustom(start, end + 86_400_000L - 1)
                        showPicker = false
                    },
                ) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.height(520.dp))
        }
    }
}
