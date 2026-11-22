package com.batterymaster.ai.ui.apps

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.data.repo.BatteryRepository
import com.batterymaster.ai.data.repo.HistoryRepository
import com.batterymaster.ai.data.usage.AppUsageSource
import com.batterymaster.ai.domain.model.valueOrNull
import com.batterymaster.ai.domain.usage.AppPowerCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

enum class AppsPeriod { LAST_15_MIN, TODAY }

data class AppRow(
    val key: String,
    val label: String,
    val icon: Bitmap?,
    val mah: Double?,
    val pctDrop: Float,
    val durationMs: Long,
    val sharePct: Float,
    val avgMa: Double?,
    /** Android's own foreground time for today (Usage access), if available. */
    val screenTimeMs: Long?,
)

data class AppsUiState(
    val period: AppsPeriod = AppsPeriod.TODAY,
    val rows: List<AppRow> = emptyList(),
    val hasAccess: Boolean = false,
    val monitoringEnabled: Boolean = false,
    val totalMah: Double? = null,
    val totalPct: Float = 0f,
    val liveCurrentMa: Int? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val history: HistoryRepository,
    private val usage: AppUsageSource,
    private val settings: SettingsRepository,
    battery: BatteryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AppsUiState())
    val state: StateFlow<AppsUiState> = _state.asStateFlow()
    private var loop: Job? = null

    init {
        viewModelScope.launch {
            battery.live.collect { s -> _state.update { it.copy(liveCurrentMa = s.currentMa.valueOrNull) } }
        }
    }

    fun setPeriod(p: AppsPeriod) {
        _state.update { it.copy(period = p) }
        refresh()
    }

    /** (Re)starts the 30-second refresh loop; call when the screen becomes visible. */
    fun refresh() {
        loop?.cancel()
        loop = viewModelScope.launch {
            while (isActive) {
                compute()
                delay(30_000)
            }
        }
    }

    fun stop() {
        loop?.cancel()
    }

    private suspend fun compute() {
        val period = _state.value.period
        val now = System.currentTimeMillis()
        val from = when (period) {
            AppsPeriod.LAST_15_MIN -> now - 15 * 60_000L
            AppsPeriod.TODAY -> startOfToday()
        }
        val access = usage.hasAccess()
        val monitoring = settings.current().monitoringEnabled
        val rows = withContext(Dispatchers.Default) {
            val powers = AppPowerCalculator.compute(history.samples(from, now))
            val times = if (period == AppsPeriod.TODAY) usage.foregroundTimes(from, now) else emptyMap()
            val totalMah = powers.mapNotNull { it.mah }.sum()
            val totalPct = powers.sumOf { it.pctDrop.toDouble() }.toFloat()
            powers.map { p ->
                val share = when {
                    p.mah != null && totalMah > 0 -> (p.mah / totalMah * 100).toFloat()
                    totalPct > 0 -> p.pctDrop / totalPct * 100
                    else -> 0f
                }
                val isApp = p.key != AppPowerCalculator.SCREEN_OFF && p.key != AppPowerCalculator.UNKNOWN
                AppRow(
                    key = p.key,
                    label = if (isApp) usage.label(p.key) else p.key,
                    icon = if (isApp) usage.icon(p.key) else null,
                    mah = p.mah, pctDrop = p.pctDrop, durationMs = p.durationMs,
                    sharePct = share, avgMa = p.avgMa,
                    screenTimeMs = if (isApp) times[p.key] else null,
                )
            }
        }
        _state.update {
            it.copy(
                rows = rows,
                hasAccess = access,
                monitoringEnabled = monitoring,
                totalMah = rows.mapNotNull { r -> r.mah }.takeIf { l -> l.isNotEmpty() }?.sum(),
                totalPct = rows.sumOf { r -> r.pctDrop.toDouble() }.toFloat(),
                loading = false,
            )
        }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
