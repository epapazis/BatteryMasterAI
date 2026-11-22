package com.batterymaster.ai.ui.power

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterymaster.ai.data.db.ProtectionEventEntity
import com.batterymaster.ai.data.prefs.AppSettings
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.data.repo.BatteryRepository
import com.batterymaster.ai.data.repo.HistoryRepository
import com.batterymaster.ai.domain.insights.SleepAnalyzer
import com.batterymaster.ai.domain.insights.SleepResult
import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.model.SamplePoint
import com.batterymaster.ai.domain.model.SessionSummary
import com.batterymaster.ai.domain.model.SessionType
import com.batterymaster.ai.domain.model.valueOrNull
import com.batterymaster.ai.domain.prediction.Prediction
import com.batterymaster.ai.domain.prediction.RuntimePredictor
import com.batterymaster.ai.domain.session.SessionStats
import com.batterymaster.ai.domain.session.SessionStatsCalculator
import com.batterymaster.ai.domain.usecase.InsightsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.sqrt

data class SessionGroupStats(
    val count: Int,
    val avgRate: Float?,
    val minRate: Float?,
    val maxRate: Float?,
    /** Coefficient of variation of the rate, in percent; lower = more consistent. */
    val rateVariationPct: Float?,
    val avgPeakTemp: Float?,
    val screenOnRate: Float?,
    val screenOffRate: Float?,
)

data class PowerUiState(
    val snapshot: BatterySnapshot? = null,
    val settings: AppSettings = AppSettings(),
    val openSession: SessionSummary? = null,
    val openSamples: List<SamplePoint> = emptyList(),
    val openStats: SessionStats? = null,
    val charges: List<SessionSummary> = emptyList(),
    val discharges: List<SessionSummary> = emptyList(),
    val chargeStats: SessionGroupStats? = null,
    val dischargeStats: SessionGroupStats? = null,
    val protectionEvents: List<ProtectionEventEntity> = emptyList(),
    val timeToTarget: Prediction? = null,
    val timeToFull: Prediction? = null,
    val runtime: Prediction? = null,
    val sleep: SleepResult? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PowerViewModel @Inject constructor(
    battery: BatteryRepository,
    private val history: HistoryRepository,
    settings: SettingsRepository,
    private val insights: InsightsUseCase,
) : ViewModel() {

    private val avgDrain = MutableStateFlow<Float?>(null)
    private val sleep = MutableStateFlow<SleepResult?>(null)

    init {
        viewModelScope.launch {
            avgDrain.value = insights.averageDrain7d()
            val now = System.currentTimeMillis()
            sleep.value = SleepAnalyzer.longestScreenOff(history.samples(now - 24 * 3_600_000L, now))
        }
    }

    private val open = history.observeOpenSession()
    private val openSamples = open.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else history.observeSamples(s.startTime, Long.MAX_VALUE)
    }
    private val lists = combine(
        history.observeRecentSessions(SessionType.CHARGE, 60),
        history.observeRecentSessions(SessionType.DISCHARGE, 60),
        history.observeProtectionEvents(),
    ) { c, d, p -> Triple(c, d, p) }

    val state: StateFlow<PowerUiState> = combine(
        battery.live, settings.settings, combine(open, openSamples) { o, s -> o to s }, lists,
        combine(avgDrain, sleep) { d, sl -> d to sl },
    ) { snap, s, (openSession, samples), (charges, discharges, events), (drain, sleepResult) ->
        val now = snap.timestamp
        val closedCharges = charges.filter { !it.isOpen }
        val closedDis = discharges.filter { !it.isOpen }
        PowerUiState(
            snapshot = snap,
            settings = s,
            openSession = openSession,
            openSamples = samples,
            openStats = openSession?.let { SessionStatsCalculator.compute(it.type, samples) },
            charges = closedCharges,
            discharges = closedDis,
            chargeStats = groupStats(closedCharges),
            dischargeStats = groupStats(closedDis.filter { it.startTime >= now - 7 * 86_400_000L }),
            protectionEvents = events,
            timeToTarget = if (snap.isPluggedIn && s.chargeTargetPct < 100) {
                RuntimePredictor.minutesToTarget(samples, snap.levelPct, s.chargeTargetPct, now, null)
            } else null,
            timeToFull = if (snap.isPluggedIn) {
                RuntimePredictor.minutesToTarget(samples, snap.levelPct, 100, now, snap.chargeTimeRemainingMs.valueOrNull)
            } else null,
            runtime = if (!snap.isPluggedIn) RuntimePredictor.dischargeRemainingMinutes(samples, snap.levelPct, now, drain) else null,
            sleep = sleepResult,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PowerUiState())

    private fun groupStats(list: List<SessionSummary>): SessionGroupStats? {
        if (list.isEmpty()) return null
        val rates = list.filter { it.durationMs >= 20 * 60_000L }.mapNotNull { it.ratePctPerHour }
        val mean = rates.takeIf { it.isNotEmpty() }?.average()
        val cv = if (rates.size >= 3 && mean != null && mean > 0) {
            val sd = sqrt(rates.sumOf { (it - mean) * (it - mean) } / (rates.size - 1))
            (sd / mean * 100).toFloat()
        } else null
        val onMs = list.sumOf { it.screenOnMs ?: 0L }
        val offMs = list.sumOf { it.screenOffMs ?: 0L }
        val onDrain = list.sumOf { (it.screenOnDrainPct ?: 0f).toDouble() }
        val offDrain = list.sumOf { (it.screenOffDrainPct ?: 0f).toDouble() }
        return SessionGroupStats(
            count = list.size,
            avgRate = mean?.toFloat(),
            minRate = rates.minOrNull(),
            maxRate = rates.maxOrNull(),
            rateVariationPct = cv,
            avgPeakTemp = list.mapNotNull { it.maxTempC }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            screenOnRate = if (onMs >= 30 * 60_000L) (onDrain / (onMs / 3_600_000.0)).toFloat() else null,
            screenOffRate = if (offMs >= 60 * 60_000L) (offDrain / (offMs / 3_600_000.0)).toFloat() else null,
        )
    }
}

data class SessionDetailState(
    val session: SessionSummary? = null,
    val samples: List<SamplePoint> = emptyList(),
    val estimates: List<CapacityEstimate> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    history: HistoryRepository,
) : ViewModel() {
    private val id: Long = handle.get<Long>("id") ?: -1L

    val state: StateFlow<SessionDetailState> = history.observeSession(id).flatMapLatest { s ->
        if (s == null) {
            flowOf(SessionDetailState())
        } else {
            combine(
                history.observeSamples(s.startTime, s.endTime ?: Long.MAX_VALUE),
                history.observeEstimatesForSession(s.id),
            ) { pts, est -> SessionDetailState(s, pts, est) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionDetailState())
}
