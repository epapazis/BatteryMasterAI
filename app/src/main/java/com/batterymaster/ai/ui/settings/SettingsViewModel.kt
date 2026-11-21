package com.batterymaster.ai.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterymaster.ai.background.MonitoringService
import com.batterymaster.ai.data.export.ExportManager
import com.batterymaster.ai.data.prefs.AppSettings
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.data.prefs.ThemeMode
import com.batterymaster.ai.data.protection.ChargingControlEngine
import com.batterymaster.ai.data.repo.BatteryRepository
import com.batterymaster.ai.data.repo.HistoryRepository
import com.batterymaster.ai.domain.compat.DeviceCapabilities
import com.batterymaster.ai.domain.protection.ProtectionStatus
import com.batterymaster.ai.domain.reliability.CurrentCalibration
import com.batterymaster.ai.domain.usecase.InsightsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val capabilities: DeviceCapabilities? = null,
    val calibration: CurrentCalibration = CurrentCalibration(),
    val sampleCount: Int = 0,
    val protection: ProtectionStatus? = null,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val battery: BatteryRepository,
    private val history: HistoryRepository,
    private val insights: InsightsUseCase,
    private val export: ExportManager,
    private val protection: ChargingControlEngine,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val protectionStatus = MutableStateFlow(protection.status())

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepo.settings,
        battery.live,
        battery.calibration,
        history.observeSampleCount(),
        combine(message, protectionStatus) { m, p -> m to p },
    ) { s, snap, cal, count, (msg, prot) ->
        SettingsUiState(s, insights.capabilities(snap), cal, count, prot, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private fun edit(t: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(t) }
    }

    fun setTheme(m: ThemeMode) = edit { it.copy(themeMode = m) }
    fun setDynamic(on: Boolean) = edit { it.copy(dynamicColor = on) }
    fun setTarget(pct: Int) = edit { it.copy(chargeTargetPct = pct) }
    fun setTargetAlert(on: Boolean) = edit { it.copy(targetAlertEnabled = on) }
    fun setFullAlert(on: Boolean) = edit { it.copy(fullAlertEnabled = on) }
    fun setLowAlert(on: Boolean) = edit { it.copy(lowAlertEnabled = on) }
    fun setLowPct(pct: Int) = edit { it.copy(lowAlertPct = pct) }
    fun setTempAlert(on: Boolean) = edit { it.copy(tempAlertEnabled = on) }
    fun setTempC(c: Float) = edit { it.copy(tempAlertC = c) }
    fun setSound(on: Boolean) = edit { it.copy(alertSound = on) }
    fun setRetention(days: Int) = edit { it.copy(retentionDays = days) }

    fun setMonitoring(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(monitoringEnabled = enabled) }
            runCatching {
                if (enabled) MonitoringService.start(context) else MonitoringService.stop(context)
            }.onFailure { message.value = "Couldn't change monitoring: ${it.message}" }
            refreshProtection()
        }
    }

    fun refreshProtection() {
        protectionStatus.value = protection.status()
    }

    fun exportSamples(uri: Uri) = viewModelScope.launch {
        message.value = runCatching { export.exportSamplesCsv(uri) }
            .fold({ "Exported $it measurements" }, { "Export failed: ${it.message}" })
    }

    fun exportSessions(uri: Uri) = viewModelScope.launch {
        message.value = runCatching { export.exportSessionsCsv(uri) }
            .fold({ "Exported $it sessions" }, { "Export failed: ${it.message}" })
    }

    fun deleteAll() = viewModelScope.launch {
        history.deleteAll()
        message.value = "All recorded battery data deleted"
    }

    fun resetCalibration() = viewModelScope.launch {
        battery.resetCalibration()
        message.value = "Calibration reset. Current readings will re-calibrate during the next charge."
    }

    fun consumeMessage() {
        message.value = null
    }
}
