package com.batterymaster.ai.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterymaster.ai.R
import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.model.measurementOrNull
import com.batterymaster.ai.domain.prediction.CapacityForecast
import com.batterymaster.ai.ui.charts.ChartCard
import com.batterymaster.ai.ui.charts.ChartPoint
import com.batterymaster.ai.ui.charts.ChartSeries
import com.batterymaster.ai.ui.components.AppCard
import com.batterymaster.ai.ui.components.InfoRow
import com.batterymaster.ai.ui.components.MetricTile
import com.batterymaster.ai.ui.components.ReadingTile
import com.batterymaster.ai.ui.components.ReliabilityBadge
import com.batterymaster.ai.ui.components.SectionHeader
import com.batterymaster.ai.ui.components.UnavailableTile
import com.batterymaster.ai.ui.theme.LocalDataColors
import com.batterymaster.ai.ui.util.Format
import com.batterymaster.ai.domain.model.DataClass

@Composable
fun HealthScreen(padding: PaddingValues, vm: HealthViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val colors = LocalDataColors.current
    val summary = st.summary

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val hp = summary?.healthPct
                if (hp != null) {
                    MetricTile(stringResource(R.string.health), Format.num(hp.value, 0), "%", hp, Modifier.weight(1f))
                } else {
                    UnavailableTile(stringResource(R.string.health),
                        if (summary?.estimatedCapacity != null) stringResource(R.string.enter_design_capacity)
                        else stringResource(R.string.capacity_needs_session), Modifier.weight(1f))
                }
                val cap = summary?.estimatedCapacity
                if (cap != null) {
                    MetricTile(stringResource(R.string.est_capacity), "%,d".format(cap.value), "mAh", cap, Modifier.weight(1f))
                } else {
                    UnavailableTile(stringResource(R.string.est_capacity), stringResource(R.string.capacity_needs_session), Modifier.weight(1f))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val deg = summary?.degradationPct
                if (summary != null && deg != null && summary.healthPct != null) {
                    MetricTile(stringResource(R.string.degradation), Format.num(deg, 0), "%", summary.healthPct, Modifier.weight(1f),
                        dataClass = DataClass.ESTIMATED)
                } else {
                    UnavailableTile(stringResource(R.string.degradation), stringResource(R.string.needs_health_pct), Modifier.weight(1f))
                }
                val snap = st.snapshot
                if (snap != null) {
                    ReadingTile(stringResource(R.string.cycle_count), snap.cycleCount, Modifier.weight(1f))
                } else {
                    UnavailableTile(stringResource(R.string.cycle_count), "…", Modifier.weight(1f))
                }
            }
        }
        item {
            DesignCapacityCard(st.designCapacityMah, vm::setDesignCapacity)
        }
        st.trend?.let { t ->
            item {
                AppCard {
                    Text(stringResource(R.string.health_trend_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.health_trend_body, t.fromMah, t.toMah, t.changePct, t.months, t.changePctPerYear),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ReliabilityBadge(DataClass.ESTIMATED)
                }
            }
        }
        st.wear?.takeIf { it.sessions > 0 }?.let { w ->
            item {
                AppCard {
                    Text(stringResource(R.string.wear_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    InfoRow(stringResource(R.string.wear_cycles), Format.num(w.cycles, 1))
                    InfoRow(stringResource(R.string.wear_weighted), Format.num(w.weightedCycles, 1))
                    InfoRow(
                        stringResource(R.string.wear_stress),
                        "×" + Format.num(w.stressFactor, 2) + " · " + stringResource(
                            when {
                                w.stressFactor < 1.2 -> R.string.wear_gentle
                                w.stressFactor < 1.6 -> R.string.wear_moderate
                                else -> R.string.wear_harsh
                            },
                        ),
                    )
                    w.since?.let { InfoRow(stringResource(R.string.wear_since), Format.date(it)) }
                    Text(stringResource(R.string.wear_explain), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReliabilityBadge(DataClass.ESTIMATED)
                }
            }
        }
        item {
            val accepted = st.estimates.filter { it.accepted && it.capacityMah != null }.sortedBy { it.timestamp }
            val series = buildList {
                add(ChartSeries("Estimated capacity", accepted.map { ChartPoint(it.timestamp, it.capacityMah!!.toFloat()) }, colors.estimated))
                val fc = st.forecast
                if (fc is CapacityForecast.Available) {
                    add(ChartSeries("Forecast", fc.points.map { ChartPoint(it.time, it.value.toFloat(), it.low.toFloat(), it.high.toFloat()) },
                        colors.prediction, dashed = true, fillRange = true))
                }
                if (st.designCapacityMah > 0 && accepted.isNotEmpty()) {
                    add(ChartSeries("Design capacity", listOf(
                        ChartPoint(accepted.first().timestamp, st.designCapacityMah.toFloat()),
                        ChartPoint((st.forecast as? CapacityForecast.Available)?.points?.last()?.time ?: accepted.last().timestamp,
                            st.designCapacityMah.toFloat()),
                    ), colors.system, dashed = true))
                }
            }
            ChartCard(
                title = stringResource(R.string.capacity_history),
                subtitle = when (val fc = st.forecast) {
                    is CapacityForecast.Available -> fc.basis
                    is CapacityForecast.Insufficient -> "Forecast: ${fc.reason}"
                    null -> null
                },
                series = series, unit = "mAh", decimals = 0,
            )
        }
        if (summary != null) {
            item {
                AppCard {
                    Text(stringResource(R.string.methodology), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(summary.explanation, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Confidence", summary.confidence.label)
                    InfoRow("Sessions used", summary.usedEstimates.toString())
                    summary.lastEstimateTime?.let { InfoRow("Last reliable estimate", Format.dateTime(it)) }
                    st.snapshot?.chargeCounterMah?.measurementOrNull?.let {
                        InfoRow("Charge stored now (charge counter)", "${it.value} mAh")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.counter_not_capacity), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SectionHeader(stringResource(R.string.session_estimates)) }
        if (st.estimates.isEmpty()) {
            item { Text(stringResource(R.string.no_estimates), style = MaterialTheme.typography.bodyMedium) }
        }
        items(st.estimates.take(30), key = { "${it.sessionId}-${it.method}" }) { EstimateRow(it) }
    }
}

@Composable
private fun EstimateRow(e: CapacityEstimate) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (e.accepted) Icons.Outlined.CheckCircle else Icons.Outlined.RemoveCircleOutline, null,
                tint = if (e.accepted) LocalDataColors.current.good else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (e.accepted) "${"%,d".format(e.capacityMah)} mAh" else "Not used",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("${Format.dateTime(e.timestamp)} · ${e.levelSpan}% span · ${e.method.label}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                e.rejectReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (e.accepted) ReliabilityBadge(DataClass.ESTIMATED, e.confidence)
        }
    }
}

@Composable
private fun DesignCapacityCard(current: Int, onSave: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(if (current > 0) current.toString() else "") }
    val parsed = text.toIntOrNull()
    val valid = text.isEmpty() || (parsed != null && parsed in 500..30_000)
    AppCard {
        Text(stringResource(R.string.design_capacity), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.design_capacity_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(5) },
                label = { Text("mAh") },
                singleLine = true,
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = valid && (parsed ?: 0) != current, onClick = { onSave(parsed ?: 0) }) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
