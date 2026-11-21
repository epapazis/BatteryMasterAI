package com.batterymaster.ai.ui.analytics

import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterymaster.ai.data.export.ExportManager
import com.batterymaster.ai.data.repo.HistoryRepository
import com.batterymaster.ai.domain.analytics.ChartCategory
import com.batterymaster.ai.domain.analytics.ChartMetric
import com.batterymaster.ai.domain.analytics.TimeRange
import com.batterymaster.ai.domain.model.BucketValue
import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.prediction.CapacityForecast
import com.batterymaster.ai.domain.prediction.CapacityForecaster
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MetricData(
    val metric: ChartMetric,
    val current: List<BucketValue>,
    val previous: List<BucketValue>?,
)

data class AnalyticsUiState(
    val category: ChartCategory = ChartCategory.LEVEL,
    val range: TimeRange = TimeRange.DAY,
    val from: Long = 0,
    val to: Long = 0,
    val bucketMs: Long = TimeRange.DAY.bucketMs,
    val compare: Boolean = false,
    val metrics: List<MetricData> = emptyList(),
    val estimates: List<CapacityEstimate> = emptyList(),
    val forecast: CapacityForecast? = null,
    val loading: Boolean = true,
)

private data class Query(
    val category: ChartCategory,
    val range: TimeRange,
    val custom: Pair<Long, Long>?,
    val compare: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val history: HistoryRepository,
    private val export: ExportManager,
) : ViewModel() {

    private val category = MutableStateFlow(ChartCategory.LEVEL)
    private val range = MutableStateFlow(TimeRange.DAY)
    private val custom = MutableStateFlow<Pair<Long, Long>?>(null)
    private val compare = MutableStateFlow(false)

    private val shareChannel = Channel<Intent>(Channel.BUFFERED)
    val shareEvents: Flow<Intent> = shareChannel.receiveAsFlow()

    private val query = combine(category, range, custom, compare) { c, r, cu, cmp -> Query(c, r, cu, cmp) }

    val state: StateFlow<AnalyticsUiState> = query.flatMapLatest { q ->
        // Sliding windows need a clock; long ranges refresh rarely.
        val tickMs = when (q.range) {
            TimeRange.LIVE -> 15_000L
            TimeRange.HOUR -> 60_000L
            else -> 5 * 60_000L
        }
        val ticks = if (q.range == TimeRange.CUSTOM) flowOf(0L) else flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(tickMs)
            }
        }
        ticks.flatMapLatest { buildFlow(q) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    private fun buildFlow(q: Query): Flow<AnalyticsUiState> {
        val now = System.currentTimeMillis()
        val (from, to) = if (q.range == TimeRange.CUSTOM && q.custom != null) q.custom else (now - effectiveSpan(q)) to now
        val span = to - from
        val bucket = if (q.range == TimeRange.CUSTOM) TimeRange.bucketFor(span) else q.range.bucketMs
        val base = AnalyticsUiState(q.category, q.range, from, to, bucket, q.compare, loading = false)

        if (q.category == ChartCategory.HEALTH) {
            return history.observeEstimates().map { est ->
                base.copy(estimates = est, forecast = CapacityForecaster.forecast(est))
            }
        }
        val metrics = ChartMetric.entries.filter { it.category == q.category }
        val flows: List<Flow<MetricData>> = metrics.map { m ->
            val cur = history.observeBuckets(m, from, to, bucket)
            if (q.compare) {
                combine(cur, history.observeBuckets(m, from - span, to - span, bucket)) { c, p ->
                    MetricData(m, c, p.map { it.copy(time = it.time + span) })
                }
            } else {
                cur.map { MetricData(m, it, null) }
            }
        }
        if (flows.isEmpty()) return flowOf(base)
        return combine(flows) { arr -> base.copy(metrics = arr.toList()) }
    }

    private fun effectiveSpan(q: Query): Long =
        if (q.range == TimeRange.CUSTOM) TimeRange.DAY.spanMs else q.range.spanMs

    fun setCategory(c: ChartCategory) { category.value = c }
    fun setRange(r: TimeRange) {
        range.value = r
        if (r != TimeRange.CUSTOM) custom.value = null
    }
    fun setCustom(from: Long, to: Long) {
        custom.value = from to to
        range.value = TimeRange.CUSTOM
    }
    fun setCompare(on: Boolean) { compare.value = on }

    fun exportChart(bitmap: Bitmap, name: String) {
        viewModelScope.launch {
            runCatching { export.writePng(bitmap, name) }.onSuccess { shareChannel.send(export.shareIntent(it, "image/png")) }
        }
    }
}
