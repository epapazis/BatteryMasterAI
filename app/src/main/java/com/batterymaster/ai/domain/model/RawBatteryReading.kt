package com.batterymaster.ai.domain.model

/**
 * Unprocessed values exactly as Android returned them. `null` means the platform returned
 * its "unsupported" sentinel (Integer.MIN_VALUE / Long.MIN_VALUE / -1) for that property.
 */
data class RawBatteryReading(
    val timestamp: Long,
    val level: Int,
    val scale: Int,
    val status: ChargeStatus,
    val health: HealthStatus,
    val plug: PlugType,
    val voltageRaw: Int?,
    val temperatureRaw: Int?,
    val technology: String?,
    val present: Boolean,
    val currentNowRaw: Int?,
    val currentAverageRaw: Int?,
    val chargeCounterRaw: Int?,
    val cycleCount: Int?,
    val chargeTimeRemainingMs: Long?,
    val screenOn: Boolean,
) {
    val levelPct: Int
        get() = if (scale > 0) Math.round(level * 100f / scale).coerceIn(0, 100) else level.coerceIn(0, 100)
}
