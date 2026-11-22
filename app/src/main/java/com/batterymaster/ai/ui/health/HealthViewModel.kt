package com.batterymaster.ai.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.data.repo.BatteryRepository
import com.batterymaster.ai.data.repo.HistoryRepository
import com.batterymaster.ai.domain.health.HealthSummary
import com.batterymaster.ai.domain.insights.HealthTrend
import com.batterymaster.ai.domain.insights.HealthTrendCalculator
import com.batterymaster.ai.domain.insights.WearCalculator
import com.batterymaster.ai.domain.insights.WearStats
import com.batterymaster.ai.domain.model.SessionType
import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.prediction.CapacityForecast
import com.batterymaster.ai.domain.prediction.CapacityForecaster
import com.batterymaster.ai.domain.usecase.InsightsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HealthUiState(
    val summary: HealthSummary? = null,
    val estimates: List<CapacityEstimate> = emptyList(),
    val forecast: CapacityForecast? = null,
    val snapshot: BatterySnapshot? = null,
    val designCapacityMah: Int = 0,
    val wear: WearStats? = null,
    val trend: HealthTrend? = null,
)

@HiltViewModel
class HealthViewModel @Inject constructor(
    battery: BatteryRepository,
    history: HistoryRepository,
    insights: InsightsUseCase,
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<HealthUiState> = combine(
        insights.healthFlow(),
        history.observeEstimates(),
        battery.live,
        settings.settings,
        history.observeRecentSessions(SessionType.CHARGE, 5_000),
    ) { summary, est, snap, s, charges ->
        HealthUiState(
            summary = summary,
            estimates = est.sortedByDescending { it.timestamp },
            forecast = CapacityForecaster.forecast(est),
            snapshot = snap,
            designCapacityMah = s.designCapacityMah,
            wear = WearCalculator.compute(charges),
            trend = HealthTrendCalculator.compute(est),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthUiState())

    fun setDesignCapacity(mah: Int) {
        viewModelScope.launch { settings.update { it.copy(designCapacityMah = mah) } }
    }
}
