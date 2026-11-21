package com.batterymaster.ai.ui.power

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterymaster.ai.domain.model.SessionType
import com.batterymaster.ai.ui.charts.ChartCard
import com.batterymaster.ai.ui.charts.ChartPoint
import com.batterymaster.ai.ui.charts.ChartSeries
import com.batterymaster.ai.ui.components.AppCard
import com.batterymaster.ai.ui.components.EmptyState
import com.batterymaster.ai.ui.components.InfoRow
import com.batterymaster.ai.ui.theme.LocalDataColors
import com.batterymaster.ai.ui.util.Format

@Composable
fun SessionDetailScreen(padding: PaddingValues, vm: SessionDetailViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val colors = LocalDataColors.current
    val s = st.session
    val gap = 25 * 60_000L

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s == null) {
            item { EmptyState("Session not found", "It may have been removed by the data-retention setting.") }
            return@LazyColumn
        }
        item {
            AppCard {
                Text(if (s.type == SessionType.CHARGE) "Charging session" else "Discharge session",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                InfoRow("Start", "${Format.dateTime(s.startTime)} · ${s.startLevel}%")
                InfoRow("End", s.endTime?.let { "${Format.dateTime(it)} · ${s.endLevel}%" } ?: "In progress · ${s.endLevel}%")
                InfoRow("Duration", Format.durationMs(s.durationMs))
                InfoRow("Source", s.plug.label)
                s.ratePctPerHour?.let { InfoRow(if (s.type == SessionType.CHARGE) "Average speed" else "Average drain", "${Format.num(it)} %/h") }
                s.startTempC?.let { InfoRow("Start temperature", "${Format.num(it)} °C") }
                s.maxTempC?.let { InfoRow("Peak temperature", "${Format.num(it)} °C") }
                s.avgTempC?.let { InfoRow("Average temperature", "${Format.num(it)} °C") }
                s.avgCurrentMa?.let { InfoRow("Average current", "$it mA") }
                s.maxCurrentMa?.let { InfoRow("Peak current", "$it mA") }
                s.avgVoltageMv?.let { InfoRow("Average voltage", "$it mV") }
                s.avgPowerW?.let { InfoRow("Average power (battery side)", "${Format.num(it, 2)} W") }
                s.energyWh?.let { InfoRow("Energy into battery (integrated)", "${Format.num(it, 2)} Wh") }
                s.screenOnMs?.let { InfoRow("Screen on", Format.durationMs(it)) }
                s.screenOffMs?.let { InfoRow("Screen off", Format.durationMs(it)) }
                InfoRow("Measurements", "${s.sampleCount} (${s.dataGaps} gaps)")
            }
        }
        if (st.estimates.isNotEmpty()) {
            item {
                AppCard {
                    Text("Capacity estimate from this session", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    st.estimates.forEach { e ->
                        if (e.accepted) {
                            InfoRow(e.method.label, "${e.capacityMah} mAh (${e.confidence.label} confidence)")
                        } else {
                            InfoRow(e.method.label, "Not used: ${e.rejectReason}")
                        }
                    }
                }
            }
        }
        val pts = st.samples
        item {
            ChartCard("Battery level", listOf(ChartSeries("Level", pts.map { ChartPoint(it.timestamp, it.levelPct.toFloat()) }, colors.measured)),
                "%", decimals = 0, gapMs = gap)
        }
        val current = pts.mapNotNull { p -> p.currentMa?.let { ChartPoint(p.timestamp, it.toFloat()) } }
        if (current.isNotEmpty()) {
            item {
                ChartCard("Current", listOf(ChartSeries("Current", current, colors.system)), "mA",
                    subtitle = "Positive = into the battery", decimals = 0, gapMs = gap)
            }
        }
        val temps = pts.mapNotNull { p -> p.temperatureC?.let { ChartPoint(p.timestamp, it) } }
        if (temps.isNotEmpty()) {
            item { ChartCard("Temperature", listOf(ChartSeries("Temperature", temps, colors.warning)), "°C", gapMs = gap) }
        }
        val volts = pts.mapNotNull { p -> p.voltageMv?.let { ChartPoint(p.timestamp, it.toFloat()) } }
        if (volts.isNotEmpty()) {
            item { ChartCard("Voltage", listOf(ChartSeries("Voltage", volts, colors.calculated)), "mV", decimals = 0, gapMs = gap) }
        }
    }
}
