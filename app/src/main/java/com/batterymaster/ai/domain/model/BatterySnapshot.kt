package com.batterymaster.ai.domain.model

enum class ChargeStatus(val label: String) {
    CHARGING("Charging"),
    DISCHARGING("Discharging"),
    NOT_CHARGING("Plugged in, not charging"),
    FULL("Full"),
    UNKNOWN("Unknown"),
}

enum class PlugType(val label: String) {
    NONE("Unplugged"),
    AC("AC charger"),
    USB("USB"),
    WIRELESS("Wireless"),
    DOCK("Dock"),
    UNKNOWN("Unknown source"),
}

enum class HealthStatus(val label: String, val isProblem: Boolean) {
    GOOD("Good", false),
    OVERHEAT("Overheating", true),
    DEAD("Dead", true),
    OVER_VOLTAGE("Over voltage", true),
    FAILURE("Failure reported", true),
    COLD("Too cold", true),
    UNKNOWN("Not reported", false),
}

/**
 * One fully classified view of the battery at a moment in time, produced by the
 * reliability engine from a raw BatteryManager reading.
 */
data class BatterySnapshot(
    val timestamp: Long,
    val level: Measurement<Int>,
    val status: ChargeStatus,
    val plug: PlugType,
    val health: HealthStatus,
    val technology: String?,
    val present: Boolean,
    val temperatureC: Reading<Float>,
    val voltageMv: Reading<Int>,
    /** Positive = current flowing into the battery. */
    val currentMa: Reading<Int>,
    val currentAverageMa: Reading<Int>,
    val chargeCounterMah: Reading<Int>,
    val powerW: Reading<Float>,
    val cycleCount: Reading<Int>,
    val chargeTimeRemainingMs: Reading<Long>,
    val screenOn: Boolean,
) {
    val levelPct: Int get() = level.value
    val isPluggedIn: Boolean get() = plug != PlugType.NONE
    val isCharging: Boolean get() = status == ChargeStatus.CHARGING
}
