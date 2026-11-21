package com.batterymaster.ai.ui.power

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ElectricalServices
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterymaster.ai.R
import com.batterymaster.ai.domain.model.DataClass
import com.batterymaster.ai.domain.model.SessionSummary
import com.batterymaster.ai.domain.model.SessionType
import com.batterymaster.ai.ui.charts.ChartCard
import com.batterymaster.ai.ui.charts.ChartPoint
import com.batterymaster.ai.ui.charts.ChartSeries
import com.batterymaster.ai.ui.components.AppCard
import com.batterymaster.ai.ui.components.InfoRow
import com.batterymaster.ai.ui.components.MetricTile
import com.batterymaster.ai.ui.components.ReadingTile
import com.batterymaster.ai.ui.components.SectionHeader
import com.batterymaster.ai.ui.home.PredictionTile
import com.batterymaster.ai.ui.theme.LocalDataColors
import com.batterymaster.ai.ui.util.Format

@Composable
fun PowerScreen(padding: PaddingValues, onOpenSession: (Long) -> Unit, vm: PowerViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(if (st.snapshot?.isPluggedIn == false) 1 else 0) }
    val colors = LocalDataColors.current
    val chargingHistoryTitle = stringResource(R.string.charging_history)
    val dischargeHistoryTitle = stringResource(R.string.discharge_history)

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_charging)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_discharging)) })
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val snap = st.snapshot
            val open = st.openSession
            val wantType = if (tab == 0) SessionType.CHARGE else SessionType.DISCHARGE
            val liveSession = open?.takeIf { it.type == wantType }

            if (snap != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReadingTile(stringResource(R.string.current), snap.currentMa, Modifier.weight(1f), Icons.Outlined.Bolt) { "%+,d".format(it) }
                        ReadingTile(stringResource(R.string.temperature), snap.temperatureC, Modifier.weight(1f), Icons.Outlined.Thermostat) { Format.num(it) }
                    }
                }
            }
            if (tab == 0) {
                if (snap != null) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ReadingTile(stringResource(R.string.voltage), snap.voltageMv, Modifier.weight(1f), Icons.Outlined.ElectricalServices) { "%,d".format(it) }
                            ReadingTile(stringResource(R.string.power), snap.powerW, Modifier.weight(1f), Icons.Outlined.Speed) { Format.num(it, 2) }
                        }
                    }
                    if (snap.isPluggedIn) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PredictionTile(stringResource(R.string.time_to_target, st.settings.chargeTargetPct), st.timeToTarget,
                                    Modifier.weight(1f), stringResource(R.string.charge_rate_unavailable))
                                PredictionTile(stringResource(R.string.time_to_full), st.timeToFull, Modifier.weight(1f),
                                    stringResource(R.string.charge_rate_unavailable))
                            }
                        }
                    }
                }
            } else if (snap != null && !snap.isPluggedIn) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PredictionTile(stringResource(R.string.runtime_left), st.runtime, Modifier.weight(1f),
                            stringResource(R.string.runtime_unavailable))
                        val rate = liveSession?.ratePctPerHour
                        if (rate != null) {
                            MetricTile(stringResource(R.string.drain_rate), Format.num(rate), "%/h", null, Modifier.weight(1f),
                                dataClass = DataClass.CALCULATED)
                        } else {
                            Box(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (liveSession != null && st.openSamples.size >= 2) {
                item { LiveSessionCard(liveSession, st) }
            }

            if (tab == 0) {
                st.chargeStats?.let { cs -> item { GroupStatsCard(stringResource(R.string.charging_analysis), cs, true) } }
                if (st.charges.size >= 2) {
                    item {
                        ComparisonBars(
                            title = stringResource(R.string.speed_comparison),
                            sessions = st.charges.take(12).reversed(),
                            color = colors.measured,
                        )
                    }
                }
                if (st.protectionEvents.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.protection_history)) }
                    items(st.protectionEvents.take(10), key = { "p${it.id}" }) { e ->
                        AppCard {
                            Text("${Format.dateTime(e.timestamp)} · target ${e.targetPct}% reached at ${e.levelPct}%",
                                style = MaterialTheme.typography.bodyMedium)
                            Text(e.outcome, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                sessionList(chargingHistoryTitle, st.charges, onOpenSession)
            } else {
                st.dischargeStats?.let { ds -> item { GroupStatsCard(stringResource(R.string.discharge_analysis_7d), ds, false) } }
                if (st.discharges.size >= 2) {
                    item {
                        ComparisonBars(
                            title = stringResource(R.string.drain_comparison),
                            sessions = st.discharges.filter { it.durationMs >= 20 * 60_000L }.take(12).reversed(),
                            color = colors.notice,
                        )
                    }
                }
                sessionList(dischargeHistoryTitle, st.discharges, onOpenSession)
            }
        }
    }
}

@Composable
private fun LiveSessionCard(s: SessionSummary, st: PowerUiState) {
    val colors = LocalDataColors.current
    val levelSeries = ChartSeries("Level", st.openSamples.map { ChartPoint(it.timestamp, it.levelPct.toFloat()) }, colors.measured)
    val currentPts = st.openSamples.mapNotNull { p -> p.currentMa?.let { ChartPoint(p.timestamp, it.toFloat()) } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard {
            Text(stringResource(R.string.current_session), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            InfoRow("Started", "${Format.dateTime(s.startTime)} at ${s.startLevel}%")
            InfoRow("Duration", Format.durationMs(s.durationMs))
            InfoRow("Change", "%+d%%".format(s.levelChange))
            s.ratePctPerHour?.let { InfoRow("Average speed", "${Format.num(it)} %/h") }
            st.openStats?.maxTempC?.let { InfoRow("Peak temperature", "${Format.num(it)} °C") }
            if (s.type == SessionType.DISCHARGE) {
                st.openStats?.screenOnMs?.let { InfoRow("Screen on", Format.durationMs(it)) }
                st.openStats?.screenOffMs?.let { InfoRow("Screen off", Format.durationMs(it)) }
            }
            if (s.dataGaps > 0) {
                Text("${s.dataGaps} gap(s) in measurements — turn on continuous monitoring for complete sessions.",
                    style = MaterialTheme.typography.bodySmall, color = colors.notice)
            }
        }
        ChartCard(title = "Battery level", series = listOf(levelSeries), unit = "%", decimals = 0, gapMs = 25 * 60_000L)
        if (currentPts.size >= 2) {
            ChartCard(title = "Current", subtitle = "Positive = into the battery",
                series = listOf(ChartSeries("Current", currentPts, colors.system)), unit = "mA", decimals = 0, gapMs = 25 * 60_000L)
        }
    }
}

@Composable
private fun GroupStatsCard(title: String, g: SessionGroupStats, charging: Boolean) {
    AppCard {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        InfoRow("Sessions", g.count.toString())
        g.avgRate?.let { InfoRow(if (charging) "Average speed" else "Average drain", "${Format.num(it)} %/h") }
        if (g.minRate != null && g.maxRate != null) {
            InfoRow(if (charging) "Slowest / fastest" else "Lowest / highest drain", "${Format.num(g.minRate)} / ${Format.num(g.maxRate)} %/h")
        }
        g.rateVariationPct?.let {
            InfoRow("Consistency", "±${Format.num(it, 0)}% session-to-session" + if (it < 15) " (consistent)" else " (variable)")
        }
        g.avgPeakTemp?.let { InfoRow("Average peak temperature", "${Format.num(it)} °C") }
        if (!charging) {
            g.screenOnRate?.let { InfoRow("Screen-on drain", "${Format.num(it)} %/h") }
            g.screenOffRate?.let { InfoRow("Screen-off drain", "${Format.num(it, 2)} %/h") }
            Text(stringResource(R.string.drain_estimate_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComparisonBars(title: String, sessions: List<SessionSummary>, color: androidx.compose.ui.graphics.Color) {
    val rates = sessions.map { it.ratePctPerHour ?: 0f }
    val max = (rates.maxOrNull() ?: 0f).coerceAtLeast(1f)
    AppCard {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("%/h per session, oldest → newest", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom) {
            rates.forEach { r ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Format.num(r, 0), style = MaterialTheme.typography.labelSmall)
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight((r / max).coerceIn(0.02f, 0.85f))
                            .background(color, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.sessionList(title: String, list: List<SessionSummary>, onOpen: (Long) -> Unit) {
    item { SectionHeader(title) }
    if (list.isEmpty()) {
        item {
            Text("No completed sessions yet. Sessions are recorded automatically as you plug and unplug.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
    items(list, key = { it.id }) { s ->
        AppCard(Modifier.clickable { onOpen(s.id) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${s.startLevel}% → ${s.endLevel}%", style = MaterialTheme.typography.titleSmall)
                    Text("${Format.dateTime(s.startTime)} · ${Format.durationMs(s.durationMs)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    s.ratePctPerHour?.let { Text("${Format.num(it)} %/h", style = MaterialTheme.typography.bodyMedium) }
                    s.maxTempC?.let { Text("max ${Format.num(it)} °C", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
