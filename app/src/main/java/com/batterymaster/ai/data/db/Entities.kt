package com.batterymaster.ai.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "samples",
    indices = [Index(value = ["timestamp"], unique = true)],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val levelPct: Int,
    val status: String,
    val plug: String,
    val temperatureC: Float?,
    val voltageMv: Int?,
    /** Normalised: milliamps, positive = into the battery. */
    val currentMa: Int?,
    val currentConfidence: String?,
    val chargeCounterMah: Int?,
    val screenOn: Boolean,
    /** SERVICE or WORKER. */
    val source: String,
)

@Entity(
    tableName = "sessions",
    indices = [Index("startTime"), Index("type"), Index("endTime")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val startTime: Long,
    val endTime: Long?,
    val lastSampleTime: Long,
    val startLevel: Int,
    val endLevel: Int,
    val plug: String,
    val startTempC: Float?,
    val maxTempC: Float?,
    val avgTempC: Float?,
    val avgCurrentMa: Int?,
    val maxCurrentMa: Int?,
    val avgVoltageMv: Int?,
    val avgPowerW: Float?,
    val energyWh: Float?,
    val screenOnMs: Long?,
    val screenOffMs: Long?,
    val screenOnDrainPct: Float?,
    val screenOffDrainPct: Float?,
    val sampleCount: Int,
    val dataGaps: Int,
)

@Entity(
    tableName = "capacity_estimates",
    indices = [Index("timestamp"), Index("sessionId")],
)
data class CapacityEstimateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val sessionId: Long,
    val method: String,
    val capacityMah: Int?,
    val levelSpan: Int,
    val accepted: Boolean,
    val rejectReason: String?,
    val confidence: String,
)

@Entity(tableName = "protection_events", indices = [Index("timestamp")])
data class ProtectionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val sessionId: Long,
    val targetPct: Int,
    val levelPct: Int,
    val method: String,
    val outcome: String,
)

/** Result row for SQL-side time-bucket aggregation. */
data class BucketRow(
    val bucket: Long,
    val avgValue: Double,
    val minValue: Double,
    val maxValue: Double,
    val n: Int,
)
