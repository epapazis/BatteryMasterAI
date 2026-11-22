package com.batterymaster.ai.data.repo

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.batterymaster.ai.data.db.AppDatabase
import com.batterymaster.ai.data.db.CapacityDao
import com.batterymaster.ai.data.db.ChargerTestDao
import com.batterymaster.ai.data.db.ChargerTestEntity
import com.batterymaster.ai.data.db.ProtectionDao
import com.batterymaster.ai.data.db.ProtectionEventEntity
import com.batterymaster.ai.data.db.SampleDao
import com.batterymaster.ai.data.db.SampleEntity
import com.batterymaster.ai.data.db.SessionDao
import com.batterymaster.ai.data.db.toDomain
import com.batterymaster.ai.data.db.toPoint
import com.batterymaster.ai.data.db.toSummary
import com.batterymaster.ai.data.source.BatteryDataSource
import com.batterymaster.ai.data.source.ReliabilityEngine
import com.batterymaster.ai.di.ApplicationScope
import com.batterymaster.ai.domain.analytics.ChartMetric
import com.batterymaster.ai.domain.doctor.DoctorReport
import com.batterymaster.ai.domain.doctor.TemperatureStats
import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.BucketValue
import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.model.SamplePoint
import com.batterymaster.ai.domain.model.SessionSummary
import com.batterymaster.ai.domain.model.SessionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** Live battery state for the UI. Only active while something is collecting it. */
@Singleton
class BatteryRepository @Inject constructor(
    private val source: BatteryDataSource,
    private val engine: ReliabilityEngine,
    @ApplicationScope scope: CoroutineScope,
) {
    val live: SharedFlow<BatterySnapshot> = source.observe(pollMs = LIVE_POLL_MS)
        .map { engine.process(it) }
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    suspend fun readNow(): BatterySnapshot? = source.readNow()?.let { engine.process(it) }

    val calibration get() = engine.calibration

    suspend fun resetCalibration() = engine.resetCalibration()

    companion object {
        /** Foreground-only refresh for values Android doesn't broadcast (current). */
        const val LIVE_POLL_MS = 3_000L
    }
}

/** Single source of truth for everything persisted. */
@Singleton
class HistoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val samples: SampleDao,
    private val sessions: SessionDao,
    private val capacity: CapacityDao,
    private val protection: ProtectionDao,
    private val chargerTests: ChargerTestDao,
) {
    fun observeLatestSample(): Flow<SamplePoint?> = samples.observeLatest().map { it?.toPoint() }

    fun observeSamples(from: Long, to: Long): Flow<List<SamplePoint>> =
        samples.observeBetween(from, to).map { list -> list.map { it.toPoint() } }

    suspend fun samples(from: Long, to: Long): List<SamplePoint> = samples.between(from, to).map { it.toPoint() }

    fun observeBuckets(metric: ChartMetric, from: Long, to: Long, bucketMs: Long): Flow<List<BucketValue>> {
        // Align buckets to local midnight so daily bars match the user's calendar day.
        val offset = TimeZone.getDefault().getOffset(to).toLong()
        val where = buildString {
            append("timestamp >= ? AND timestamp < ? AND ${metric.expression} IS NOT NULL")
            metric.filter?.let { append(" AND ").append(it) }
        }
        val sql = "SELECT (((timestamp + ?) / ?) * ?) - ? AS bucket, " +
            "AVG(${metric.expression}) AS avgValue, MIN(${metric.expression}) AS minValue, " +
            "MAX(${metric.expression}) AS maxValue, COUNT(*) AS n " +
            "FROM samples WHERE $where GROUP BY bucket ORDER BY bucket"
        val query = SimpleSQLiteQuery(sql, arrayOf<Any>(offset, bucketMs, bucketMs, offset, from, to))
        return samples.observeBuckets(query).map { rows ->
            rows.map { BucketValue(it.bucket, it.avgValue, it.minValue, it.maxValue, it.n) }
        }
    }

    fun observeOpenSession(): Flow<SessionSummary?> = sessions.observeOpen().map { it?.toSummary() }

    fun observeRecentSessions(type: SessionType, limit: Int = 50): Flow<List<SessionSummary>> =
        sessions.observeRecent(type.name, limit).map { l -> l.map { it.toSummary() } }

    fun observeSessionsSince(since: Long): Flow<List<SessionSummary>> =
        sessions.observeSince(since).map { l -> l.map { it.toSummary() } }

    suspend fun sessionsSince(since: Long): List<SessionSummary> = sessions.since(since).map { it.toSummary() }

    fun observeSession(id: Long): Flow<SessionSummary?> = sessions.observe(id).map { it?.toSummary() }

    fun observeEstimates(): Flow<List<CapacityEstimate>> = capacity.observeAll().map { l -> l.map { it.toDomain() } }

    fun observeEstimatesForSession(id: Long): Flow<List<CapacityEstimate>> =
        capacity.observeForSession(id).map { l -> l.map { it.toDomain() } }

    suspend fun allEstimates(): List<CapacityEstimate> = capacity.all().map { it.toDomain() }

    fun observeProtectionEvents(limit: Int = 30): Flow<List<ProtectionEventEntity>> = protection.observeRecent(limit)

    fun observeSampleCount(): Flow<Int> = samples.observeCount()

    suspend fun temperatureStats(from: Long): TemperatureStats {
        val r = samples.temperatureStats(from, DoctorReport.WARM_C)
        return TemperatureStats(r.n, r.warm ?: 0, r.maxC, r.avgC)
    }

    suspend fun dataSpanMs(now: Long): Long = samples.firstTimestamp()?.let { now - it } ?: 0L

    /** Average discharge rate (%/h) over closed or open discharge sessions since [since]. */
    suspend fun averageDrainPctPerHour(since: Long): Float? {
        val ds = sessions.since(since).map { it.toSummary() }
            .filter { it.type == SessionType.DISCHARGE && it.durationMs >= 10 * 60_000L && it.dataGaps == 0 }
        val hours = ds.sumOf { it.durationMs } / 3_600_000.0
        val drop = ds.sumOf { (it.startLevel - it.endLevel).coerceAtLeast(0) }
        if (hours < 1.0 || drop < 3) return null
        return (drop / hours).toFloat()
    }

    suspend fun samplePage(afterId: Long, limit: Int): List<SampleEntity> = samples.page(afterId, limit)

    suspend fun allSessions(): List<SessionSummary> = sessions.all().map { it.toSummary() }

    suspend fun applyRetention(days: Int, now: Long) {
        if (days <= 0) return
        val before = now - days * 86_400_000L
        db.withTransaction {
            samples.deleteOlderThan(before)
            sessions.deleteOlderThan(before)
        }
    }

    suspend fun deleteAll() {
        db.withTransaction {
            samples.deleteAll()
            sessions.deleteAll()
            capacity.deleteAll()
            protection.deleteAll()
            chargerTests.deleteAll()
        }
    }

    fun observeChargerTests(): Flow<List<ChargerTestEntity>> = chargerTests.observeAll()

    suspend fun addChargerTest(t: ChargerTestEntity): Long = chargerTests.insert(t)

    suspend fun deleteChargerTest(id: Long) = chargerTests.delete(id)
}
