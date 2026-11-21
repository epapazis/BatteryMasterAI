package com.batterymaster.ai.domain.protection

enum class ProtectionMethod(val label: String, val canStopCharging: Boolean) {
    HARDWARE_CONTROL("Hardware charging control", true),
    MANUFACTURER_API("Manufacturer-supported control", true),
    ROOT_CONTROL("Root-enabled control", true),
    NOTIFICATION_ONLY("Notification-only protection", false),
    UNSUPPORTED("Unsupported (notifications disabled)", false),
}

sealed interface ControlResult {
    /** Only returned when the device confirmed charging actually stopped. */
    data object Confirmed : ControlResult
    data class NotSupported(val reason: String) : ControlResult
    data class Failed(val reason: String) : ControlResult
}

/**
 * One way of protecting the battery at the charging target. Implementations must only
 * report [ControlResult.Confirmed] after the platform confirms success.
 */
interface ChargingController {
    val method: ProtectionMethod
    fun isAvailable(): Boolean
    suspend fun applyLimit(targetPct: Int): ControlResult
}

data class ProtectionStatus(
    val method: ProtectionMethod,
    val explanation: String,
)
