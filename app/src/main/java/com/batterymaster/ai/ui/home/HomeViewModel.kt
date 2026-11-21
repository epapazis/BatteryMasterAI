package com.batterymaster.ai.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterymaster.ai.background.MonitoringService
import com.batterymaster.ai.data.prefs.AppSettings
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.data.protection.ChargingControlEngine
import com.batterymaster.ai.data.repo.BatteryRepository
import com.batterymaster.ai.data.repo.HistoryRepository
import com.batterymaster.ai.domain.doctor.Finding
import com.batterymaster.ai.domain.doctor.Severity
import com.batterymaster.ai.domain.health.HealthSummary
import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.SamplePoint
import com.batterymaster.ai.domain.model.valueOrNull
import com.batterymaster.ai.domain.prediction.Prediction
import com.batterymaster.ai.domain.prediction.RuntimePredictor
import com.batterymaster.ai.domain.protection.ProtectionStatus
import com.batterymaster.ai.domain.usecase.InsightsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val snapshot: BatterySnapshot? = null,
    val settings: AppSettings = AppSettings(),
    val health: HealthSummary? = null,
    val runtime: Prediction? = null,
    val timeToTarget: Prediction? = null,
    val timeToFull: Prediction? = null,
    val protection: ProtectionStatus? = null,
    val insight: Finding? = null,
    val lastRecorded: Long? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    battery: BatteryRepository,
    history: HistoryRepository,
    private val settingsRepo: SettingsRepository,
    private val insights: InsightsUseCase,
    private val protection: ChargingControlEngine,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val insight = MutableStateFlow<Finding?>(null)
    private val avgDrain = MutableStateFlow<Float?>(null)
    private val protectionStatus = MutableStateFlow(protection.status())

    init {
        viewModelScope.launch {
            while (isActive) {
                val snap = battery.readNow()
                runCatching {
                    val report = insights.doctorReport(snap)
                    insight.value = report.findings.firstOrNull { it.severity == Severity.WARNING || it.severity == Severity.NOTICE }
                        ?: report.findings.firstOrNull()
                    avgDrain.value = insights.averageDrain7d()
                }
                protectionStatus.value = protection.status()
                delay(10 * 60_000L)
            }
        }
    }

    private val recentSamples = history.observeSamples(System.currentTimeMillis() - 3 * 3_600_000L, Long.MAX_VALUE)

    val state: StateFlow<HomeUiState> = combine(
        battery.live,
        settingsRepo.settings,
        insights.healthFlow(),
        recentSamples,
        combine(insight, avgDrain, protectionStatus) { a, b, c -> Triple(a, b, c) },
    ) { snap, s, health, samples, extra ->
        val (finding, drain, prot) = extra
        build(snap, s, health, samples, finding, drain, prot)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private fun build(
        snap: BatterySnapshot,
        s: AppSettings,
        health: HealthSummary,
        samples: List<SamplePoint>,
        finding: Finding?,
        drain: Float?,
        prot: ProtectionStatus,
    ): HomeUiState {
        val now = snap.timestamp
        val runtime = if (!snap.isPluggedIn) {
            RuntimePredictor.dischargeRemainingMinutes(samples, snap.levelPct, now, drain)
        } else null
        val sys = snap.chargeTimeRemainingMs.valueOrNull
        val toTarget = if (snap.isPluggedIn && s.chargeTargetPct < 100) {
            RuntimePredictor.minutesToTarget(samples, snap.levelPct, s.chargeTargetPct, now, null)
        } else null
        val toFull = if (snap.isPluggedIn) RuntimePredictor.minutesToTarget(samples, snap.levelPct, 100, now, sys) else null
        return HomeUiState(snap, s, health, runtime, toTarget, toFull, prot, finding, samples.lastOrNull()?.timestamp)
    }

    fun enableMonitoring() {
        viewModelScope.launch {
            settingsRepo.update { it.copy(monitoringEnabled = true) }
            runCatching { MonitoringService.start(context) }
            protectionStatus.value = protection.status()
        }
    }

    fun refreshProtection() {
        protectionStatus.value = protection.status()
    }
}
