package com.batterymaster.ai.ui.chargertest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterymaster.ai.R
import com.batterymaster.ai.ui.components.AppCard
import com.batterymaster.ai.ui.components.InfoRow
import com.batterymaster.ai.ui.components.SectionHeader
import com.batterymaster.ai.ui.util.Format

@Composable
fun ChargerTestScreen(padding: PaddingValues, vm: ChargerTestViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val tests by vm.tests.collectAsStateWithLifecycle()
    val best = tests.maxOfOrNull { it.avgCurrentMa } ?: 1

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppCard {
                Text(stringResource(R.string.charger_test_how), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                val s = snap
                Text(
                    if (s == null) "…" else "${s.levelPct}% · ${s.plug.label} · ${s.status.label}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
                if (s != null && s.levelPct >= 80) {
                    Text(stringResource(R.string.charger_test_high_level), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ui.label, onValueChange = vm::setLabel, singleLine = true,
                    label = { Text(stringResource(R.string.charger_test_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                when (val p = ui.phase) {
                    is TestPhase.Running -> {
                        val total = ChargerTestViewModel.WARMUP_S + ChargerTestViewModel.MEASURE_S
                        LinearProgressIndicator(progress = { 1f - p.secondsLeft / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text(
                            (if (p.warmingUp) stringResource(R.string.charger_test_warmup) else stringResource(R.string.charger_test_measuring)) +
                                " · ${p.secondsLeft}s" + (p.lastMa?.let { " · %+d mA".format(it) } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = vm::cancel) { Text(stringResource(R.string.cancel)) }
                    }
                    is TestPhase.Done -> {
                        Text(stringResource(R.string.charger_test_result), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        InfoRow("Average", "${p.result.avgCurrentMa} mA")
                        InfoRow("Peak", "${p.result.peakCurrentMa} mA")
                        p.result.avgPowerW?.let { InfoRow("Power (battery side)", "${Format.num(it, 1)} W") }
                        Button(onClick = vm::start) { Text(stringResource(R.string.charger_test_again)) }
                    }
                    is TestPhase.Failed -> {
                        Text(p.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = vm::start) { Text(stringResource(R.string.charger_test_start)) }
                    }
                    TestPhase.Idle -> Button(onClick = vm::start, enabled = snap?.isPluggedIn == true) {
                        Text(stringResource(R.string.charger_test_start))
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.charger_test_history)) }
        if (tests.isEmpty()) {
            item { Text(stringResource(R.string.charger_test_none), style = MaterialTheme.typography.bodyMedium) }
        }
        items(tests, key = { it.id }) { t ->
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${Format.dateTime(t.timestamp)} · at ${t.startLevel}%" +
                                (t.temperatureC?.let { " · ${Format.num(it)} °C" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${t.avgCurrentMa} mA", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        t.avgPowerW?.let { Text("${Format.num(it, 1)} W", style = MaterialTheme.typography.bodySmall) }
                    }
                    IconButton(onClick = { vm.delete(t.id) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
                }
                LinearProgressIndicator(progress = { t.avgCurrentMa / best.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
