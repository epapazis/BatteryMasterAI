package com.batterymaster.ai.domain.model

enum class SessionType { CHARGE, DISCHARGE }

/** A stored measurement row as used by the pure domain logic (no Room types). */
data class SamplePoint(
    val timestamp: Long,
    val levelPct: Int,
    val status: ChargeStatus,
    val plug: PlugType,
    val temperatureC: Float?,
    val voltageMv: Int?,
    val currentMa: Int?,
    val currentConfidence: Confidence?,
    val chargeCounterMah: Int?,
    val screenOn: Boolean,
) {
    val pluggedIn: Boolean get() = plug != PlugType.NONE
}

data class SessionSummary(
    val id: Long,
    val type: SessionType,
    val startTime: Long,
    val endTime: Long?,
    val lastSampleTime: Long,
    val startLevel: Int,
    val endLevel: Int,
    val plug: PlugType,
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
) {
    val isOpen: Boolean get() = endTime == null
    val effectiveEnd: Long get() = endTime ?: lastSampleTime
    val durationMs: Long get() = (effectiveEnd - startTime).coerceAtLeast(0)
    val levelChange: Int get() = endLevel - startLevel

    /** Percentage points per hour; positive while charging, positive drain while discharging. */
    val ratePctPerHour: Float?
        get() {
            val hours = durationMs / 3_600_000f
            if (hours < 5f / 60f || levelChange == 0) return null
            return when (type) {
                SessionType.CHARGE -> levelChange / hours
                SessionType.DISCHARGE -> -levelChange / hours
            }
        }
}

enum class CapacityMethod(val label: String) {
    CHARGE_COUNTER_DELTA("Charge-counter change over a charging session"),
    CURRENT_INTEGRATION("Integrated charging current over a charging session"),
}

data class CapacityEstimate(
    val timestamp: Long,
    val sessionId: Long,
    val method: CapacityMethod,
    val capacityMah: Int?,
    val levelSpan: Int,
    val accepted: Boolean,
    val rejectReason: String?,
    val confidence: Confidence,
)

data class BucketValue(
    val time: Long,
    val avg: Double,
    val min: Double,
    val max: Double,
    val count: Int,
)
