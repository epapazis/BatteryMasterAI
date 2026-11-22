package com.batterymaster.ai.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ElectricalServices
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterymaster.ai.R
import com.batterymaster.ai.domain.doctor.Severity
import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.prediction.Prediction
import com.batterymaster.ai.ui.components.AppCard
import com.batterymaster.ai.ui.components.MetricTile
import com.batterymaster.ai.ui.components.ReadingTile
import com.batterymaster.ai.ui.components.ReliabilityBadge
import com.batterymaster.ai.ui.components.UnavailableTile
import com.batterymaster.ai.ui.components.rememberNotificationPermissionRequest
import com.batterymaster.ai.ui.theme.LocalDataColors
import com.batterymaster.ai.ui.util.Format

@Composable
fun HomeScreen(
    padding: PaddingValues,
    onOpenDoctor: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenApps: () -> Unit = {},
    onOpenChargerTest: () -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val requestNotif = rememberNotificationPermissionRequest { vm.enableMonitoring() }
    val snap = st.snapshot

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            if (snap == null) {
                AppCard { Text(stringResource(R.string.reading_battery)) }
            } else {
                HeroCard(snap, st.lastRecorded)
            }
        }
        if (!st.settings.monitoringEnabled) {
            item {
                AppCard {
                    Text(stringResource(R.string.monitoring_off_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.monitoring_off_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = requestNotif) { Text(stringResource(R.string.enable_monitoring)) }
                }
            }
        }
        if (snap != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReadingTile(stringResource(R.string.temperature), snap.temperatureC, Modifier.weight(1f), Icons.Outlined.Thermostat) { Format.num(it) }
                    ReadingTile(stringResource(R.string.voltage), snap.voltageMv, Modifier.weight(1f), Icons.Outlined.ElectricalServices) { "%,d".format(it) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReadingTile(stringResource(R.string.current), snap.currentMa, Modifier.weight(1f), Icons.Outlined.Bolt) { "%+,d".format(it) }
                    if (snap.isPluggedIn) {
                        ReadingTile(stringResource(R.string.power), snap.powerW, Modifier.weight(1f), Icons.Outlined.Speed) { Format.num(it, 2) }
                    } else {
                        PredictionTile(stringResource(R.string.runtime_left), st.runtime, Modifier.weight(1f),
                            stringResource(R.string.runtime_unavailable))
                    }
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
        item {
            val h = st.health
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val cap = h?.estimatedCapacity
                if (cap != null) {
                    MetricTile(stringResource(R.string.est_capacity), "%,d".format(cap.value), "mAh", cap, Modifier.weight(1f), Icons.Outlined.Favorite)
                } else {
                    UnavailableTile(stringResource(R.string.est_capacity), stringResource(R.string.capacity_needs_session), Modifier.weight(1f), Icons.Outlined.Favorite)
                }
                val hp = h?.healthPct
                if (hp != null) {
                    MetricTile(stringResource(R.string.health), Format.num(hp.value, 0), "%", hp, Modifier.weight(1f))
                } else {
                    val reason = if (cap != null) stringResource(R.string.enter_design_capacity) else stringResource(R.string.capacity_needs_session)
                    UnavailableTile(stringResource(R.string.health), reason, Modifier.weight(1f))
                }
            }
        }
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.charging_target_value, st.settings.chargeTargetPct),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.change)) }
                }
                st.protection?.let {
                    Text(it.method.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(it.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.latest_insight), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                val f = st.insight
                if (f == null) {
                    Text(stringResource(R.string.insight_collecting), style = MaterialTheme.typography.bodyMedium)
                } else {
                    val c = LocalDataColors.current
                    val color = when (f.severity) {
                        Severity.WARNING -> c.warning
                        Severity.NOTICE -> c.notice
                        Severity.GOOD -> c.good
                        Severity.INFO -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(f.title, style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = FontWeight.Medium)
                    Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenDoctor) { Text(stringResource(R.string.open_doctor)) }
                    OutlinedButton(onClick = onOpenAnalytics) { Text(stringResource(R.string.open_charts)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenApps) { Text(stringResource(R.string.apps_title)) }
                    OutlinedButton(onClick = onOpenChargerTest) { Text(stringResource(R.string.charger_test_title)) }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(s: BatterySnapshot, lastRecorded: Long?) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.semantics {
                contentDescription = "Battery ${s.levelPct} percent, ${s.status.label}"
            }) {
                CircularProgressIndicator(
                    progress = { s.levelPct / 100f },
                    modifier = Modifier.size(112.dp),
                    strokeWidth = 10.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text("${s.levelPct}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(s.status.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(s.plug.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("Battery health (Android): ${s.health.label}", style = MaterialTheme.typography.bodySmall)
                s.technology?.let { Text("Chemistry: $it", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(6.dp))
                ReliabilityBadge(s.level.dataClass)
                Text("Live · updated ${Format.time(s.timestamp)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                lastRecorded?.let {
                    Text("Last saved to history ${Format.age(it, s.timestamp)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun PredictionTile(title: String, p: Prediction?, modifier: Modifier, unavailableReason: String) {
    if (p == null) {
        UnavailableTile(title, unavailableReason, modifier, Icons.Outlined.Schedule)
    } else {
        Column(modifier) {
            MetricTile(
                title = title,
                value = Format.duration(p.value),
                unit = null,
                measurement = null,
                icon = Icons.Outlined.Schedule,
                dataClass = p.dataClass,
                confidence = p.confidence,
            )
            Text(
                "Range ${Format.duration(p.low)} – ${Format.duration(p.high)} · ${p.basis}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}
