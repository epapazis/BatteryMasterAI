package com.batterymaster.ai.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterymaster.ai.BuildConfig
import com.batterymaster.ai.R
import com.batterymaster.ai.data.prefs.OverlayColor
import com.batterymaster.ai.data.prefs.OverlayCorner
import com.batterymaster.ai.data.prefs.OverlaySize
import com.batterymaster.ai.data.prefs.ThemeMode
import com.batterymaster.ai.domain.compat.Support
import com.batterymaster.ai.domain.protection.ProtectionMethod
import com.batterymaster.ai.ui.components.AppCard
import com.batterymaster.ai.ui.components.InfoRow
import com.batterymaster.ai.ui.components.SectionHeader
import com.batterymaster.ai.ui.components.rememberNotificationPermissionRequest
import com.batterymaster.ai.ui.components.rememberOpenNotificationSettings
import com.batterymaster.ai.ui.components.rememberOpenSystemBatterySettings
import com.batterymaster.ai.ui.theme.LocalDataColors

@Composable
fun SettingsScreen(padding: PaddingValues, vm: SettingsViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val s = st.settings
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val enableMonitoring = rememberNotificationPermissionRequest { vm.setMonitoring(true) }
    val requestNotif = rememberNotificationPermissionRequest { vm.refreshProtection() }
    val openBatterySettings = rememberOpenSystemBatterySettings()
    val openNotifSettings = rememberOpenNotificationSettings()
    val samplesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportSamples)
    }
    val sessionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportSessions)
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(vm::exportBackup)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { confirmRestore = it }
    }
    val overlayPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) vm.setOverlay(true)
    }

    LaunchedEffect(st.message) {
        st.message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Monitoring
        item { SectionHeader(stringResource(R.string.section_monitoring)) }
        item {
            AppCard {
                SwitchRow(stringResource(R.string.continuous_monitoring), s.monitoringEnabled) { on ->
                    if (on) enableMonitoring() else vm.setMonitoring(false)
                }
                Text(stringResource(R.string.continuous_monitoring_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Live current overlay
        item { SectionHeader(stringResource(R.string.section_overlay)) }
        item {
            AppCard {
                SwitchRow(stringResource(R.string.overlay_toggle), s.overlayEnabled) { on ->
                    when {
                        !on -> vm.setOverlay(false)
                        Settings.canDrawOverlays(context) -> vm.setOverlay(true)
                        else -> overlayPermLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")),
                        )
                    }
                }
                Text(stringResource(R.string.overlay_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.overlayEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.overlay_position), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverlayCorner.entries.forEach { c ->
                            FilterChip(selected = s.overlayCorner == c, onClick = { vm.setOverlayCorner(c) },
                                label = { Text(cornerLabel(c)) })
                        }
                    }
                    Text(stringResource(R.string.overlay_size), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverlaySize.entries.forEach { z ->
                            FilterChip(selected = s.overlaySize == z, onClick = { vm.setOverlaySize(z) },
                                label = { Text(sizeLabel(z)) })
                        }
                    }
                    Text(stringResource(R.string.overlay_color), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverlayColor.entries.forEach { c ->
                            FilterChip(selected = s.overlayColor == c, onClick = { vm.setOverlayColor(c) },
                                label = { Text(colorLabel(c)) })
                        }
                    }
                    SwitchRow(stringResource(R.string.overlay_show_watts), s.overlayShowWatts, vm::setOverlayWatts)
                    SwitchRow(stringResource(R.string.overlay_show_temp), s.overlayShowTemp, vm::setOverlayTemp)
                    SwitchRow(stringResource(R.string.overlay_drag), s.overlayDraggable, vm::setOverlayDraggable)
                    Text(stringResource(R.string.overlay_drag_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Charging protection
        item { SectionHeader(stringResource(R.string.section_protection)) }
        item {
            AppCard {
                var target by remember(s.chargeTargetPct) { mutableFloatStateOf(s.chargeTargetPct.toFloat()) }
                Text(stringResource(R.string.charging_target_value, target.toInt()), style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = target, onValueChange = { target = it }, valueRange = 50f..100f, steps = 9,
                    onValueChangeFinished = { vm.setTarget(target.toInt()) },
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(70, 75, 80, 85, 90, 95, 100).forEach { p ->
                        FilterChip(selected = s.chargeTargetPct == p, onClick = { vm.setTarget(p) }, label = { Text("$p%") })
                    }
                }
                SwitchRow(stringResource(R.string.alert_at_target), s.targetAlertEnabled, vm::setTargetAlert)
                SwitchRow(stringResource(R.string.alert_when_full), s.fullAlertEnabled, vm::setFullAlert)
                SwitchRow(stringResource(R.string.target_alarm), s.targetAlarm, vm::setTargetAlarm)
                Text(stringResource(R.string.target_alarm_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                st.protection?.let { p ->
                    Text(stringResource(R.string.protection_method, p.method.label), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text(p.explanation, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.method == ProtectionMethod.UNSUPPORTED) {
                            OutlinedButton(onClick = requestNotif) { Text(stringResource(R.string.allow_notifications)) }
                        }
                        OutlinedButton(onClick = openBatterySettings) { Text(stringResource(R.string.open_battery_settings)) }
                    }
                }
            }
        }

        // Alerts
        item { SectionHeader(stringResource(R.string.section_alerts)) }
        item {
            AppCard {
                SwitchRow(stringResource(R.string.low_battery_alert, s.lowAlertPct), s.lowAlertEnabled, vm::setLowAlert)
                var low by remember(s.lowAlertPct) { mutableFloatStateOf(s.lowAlertPct.toFloat()) }
                Slider(value = low, onValueChange = { low = it }, valueRange = 5f..50f, steps = 8,
                    enabled = s.lowAlertEnabled, onValueChangeFinished = { vm.setLowPct(low.toInt()) })
                SwitchRow(stringResource(R.string.temp_alert, s.tempAlertC.toInt()), s.tempAlertEnabled, vm::setTempAlert)
                var temp by remember(s.tempAlertC) { mutableFloatStateOf(s.tempAlertC) }
                Slider(value = temp, onValueChange = { temp = it }, valueRange = 35f..55f, steps = 19,
                    enabled = s.tempAlertEnabled, onValueChangeFinished = { vm.setTempC(temp) })
                SwitchRow(stringResource(R.string.alert_sound), s.alertSound, vm::setSound)
                SwitchRow(stringResource(R.string.quiet_hours), s.quietHoursEnabled, vm::setQuietHours)
                if (s.quietHoursEnabled) {
                    var qs by remember(s.quietStartHour) { mutableFloatStateOf(s.quietStartHour.toFloat()) }
                    var qe by remember(s.quietEndHour) { mutableFloatStateOf(s.quietEndHour.toFloat()) }
                    Text(stringResource(R.string.quiet_from, qs.toInt()), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = qs, onValueChange = { qs = it }, valueRange = 0f..23f, steps = 22,
                        onValueChangeFinished = { vm.setQuietStart(qs.toInt()) })
                    Text(stringResource(R.string.quiet_to, qe.toInt()), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = qe, onValueChange = { qe = it }, valueRange = 0f..23f, steps = 22,
                        onValueChangeFinished = { vm.setQuietEnd(qe.toInt()) })
                    Text(stringResource(R.string.quiet_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SwitchRow(stringResource(R.string.daily_summary), s.dailySummary, vm::setDailySummary)
                TextButton(onClick = openNotifSettings) { Text(stringResource(R.string.notification_settings)) }
            }
        }

        // Appearance
        item { SectionHeader(stringResource(R.string.section_appearance)) }
        item {
            AppCard {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = s.themeMode == m, onClick = { vm.setTheme(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(themeLabel(m), maxLines = 1) }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(stringResource(R.string.dynamic_color), s.dynamicColor, vm::setDynamic)
                }
                InfoRow(stringResource(R.string.language), stringResource(R.string.language_value))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_LOCALE_SETTINGS, android.net.Uri.parse("package:${context.packageName}")),
                            )
                        }
                    }) { Text(stringResource(R.string.app_language_settings)) }
                }
            }
        }

        // Data
        item { SectionHeader(stringResource(R.string.section_data)) }
        item {
            AppCard {
                InfoRow(stringResource(R.string.stored_measurements), "%,d".format(st.sampleCount))
                Text(stringResource(R.string.retention), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(90 to "90 days", 180 to "6 months", 365 to "1 year", 0 to "Forever").forEach { (d, label) ->
                        FilterChip(selected = s.retentionDays == d, onClick = { vm.setRetention(d) }, label = { Text(label) })
                    }
                }
                Text(stringResource(R.string.retention_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { samplesLauncher.launch("battery-measurements.csv") }) { Text(stringResource(R.string.export_measurements)) }
                    OutlinedButton(onClick = { sessionsLauncher.launch("battery-sessions.csv") }) { Text(stringResource(R.string.export_sessions)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { backupLauncher.launch("battery-master-backup.db") }) { Text(stringResource(R.string.backup)) }
                    OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.restore)) }
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.delete_all_data), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Compatibility
        item { SectionHeader(stringResource(R.string.section_compatibility)) }
        item {
            val caps = st.capabilities
            val colors = LocalDataColors.current
            AppCard {
                if (caps != null) {
                    InfoRow("Device", "${caps.manufacturer} ${caps.model}")
                    InfoRow("Android", "${caps.androidVersion} (API ${caps.sdkInt})")
                    Spacer(Modifier.height(8.dp))
                    caps.items.forEach { item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(
                                    item.support.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = when (item.support) {
                                        Support.SUPPORTED -> colors.good
                                        Support.CALIBRATING -> colors.notice
                                        Support.UNSUPPORTED -> MaterialTheme.colorScheme.outline
                                    },
                                )
                            }
                            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.current_calibration), style = MaterialTheme.typography.labelLarge)
                InfoRow("Unit", st.calibration.unit.name.lowercase())
                InfoRow("Direction", st.calibration.signMode.description)
                InfoRow("Observations (charging / unplugged)",
                    "${st.calibration.chargingPos + st.calibration.chargingNeg} / ${st.calibration.dischargingPos + st.calibration.dischargingNeg}")
                TextButton(onClick = vm::resetCalibration) { Text(stringResource(R.string.reset_calibration)) }
            }
        }

        // Privacy & about
        item { SectionHeader(stringResource(R.string.section_about)) }
        item {
            AppCard {
                Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.limitations_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.limitations_body), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                InfoRow(stringResource(R.string.version), BuildConfig.VERSION_NAME)
                val developer = stringResource(R.string.developer_name)
                if (developer.isNotBlank()) InfoRow(stringResource(R.string.developer), developer)
            }
        }
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text(stringResource(R.string.restore)) },
            text = { Text(stringResource(R.string.restore_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmRestore = null; vm.restoreBackup(uri) }) { Text(stringResource(R.string.restore)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_all_data)) },
            text = { Text(stringResource(R.string.delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteAll(); confirmDelete = false }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun cornerLabel(c: OverlayCorner): String = stringResource(
    when (c) {
        OverlayCorner.TOP_LEFT -> R.string.corner_top_left
        OverlayCorner.TOP_RIGHT -> R.string.corner_top_right
        OverlayCorner.BOTTOM_LEFT -> R.string.corner_bottom_left
        OverlayCorner.BOTTOM_RIGHT -> R.string.corner_bottom_right
    },
)

@Composable
private fun colorLabel(c: OverlayColor): String = stringResource(
    when (c) {
        OverlayColor.GREEN -> R.string.color_green
        OverlayColor.WHITE -> R.string.color_white
        OverlayColor.YELLOW -> R.string.color_yellow
        OverlayColor.CYAN -> R.string.color_cyan
        OverlayColor.AUTO -> R.string.color_auto
    },
)

@Composable
private fun themeLabel(m: ThemeMode): String = stringResource(
    when (m) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.AMOLED -> R.string.theme_amoled
    },
)

@Composable
private fun sizeLabel(z: OverlaySize): String = stringResource(
    when (z) {
        OverlaySize.SMALL -> R.string.size_small
        OverlaySize.MEDIUM -> R.string.size_medium
        OverlaySize.LARGE -> R.string.size_large
    },
)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
