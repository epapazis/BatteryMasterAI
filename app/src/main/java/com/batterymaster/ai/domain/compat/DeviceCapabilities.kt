package com.batterymaster.ai.domain.compat

import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.Reading
import com.batterymaster.ai.domain.protection.ProtectionMethod

enum class Support(val label: String) { SUPPORTED("Available"), UNSUPPORTED("Not available"), CALIBRATING("Calibrating") }

data class CapabilityItem(val name: String, val support: Support, val detail: String)

data class DeviceCapabilities(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val items: List<CapabilityItem>,
    val protectionMethod: ProtectionMethod,
)

object CapabilityDetector {
    fun detect(
        manufacturer: String,
        model: String,
        androidVersion: String,
        sdkInt: Int,
        snapshot: BatterySnapshot?,
        protection: ProtectionMethod,
    ): DeviceCapabilities {
        fun <T> item(name: String, r: Reading<T>?, okDetail: String): CapabilityItem = when (r) {
            null -> CapabilityItem(name, Support.UNSUPPORTED, "No reading yet")
            is Reading.Available -> CapabilityItem(name, Support.SUPPORTED,
                r.measurement.note ?: okDetail)
            is Reading.Unavailable -> CapabilityItem(name,
                if (r.reason.startsWith("Calibrating")) Support.CALIBRATING else Support.UNSUPPORTED, r.reason)
        }
        val s = snapshot
        val items = listOf(
            CapabilityItem("Battery level & status", if (s != null) Support.SUPPORTED else Support.UNSUPPORTED, "ACTION_BATTERY_CHANGED"),
            item("Temperature", s?.temperatureC, "Battery thermistor via ACTION_BATTERY_CHANGED"),
            item("Voltage", s?.voltageMv, "ACTION_BATTERY_CHANGED"),
            item("Instant current", s?.currentMa, "BatteryManager.CURRENT_NOW"),
            item("Average current", s?.currentAverageMa, "BatteryManager.CURRENT_AVERAGE"),
            item("Charge counter", s?.chargeCounterMah, "BatteryManager.CHARGE_COUNTER"),
            item("Cycle count", s?.cycleCount, "Android 14+ EXTRA_CYCLE_COUNT"),
            item("System charge-time estimate", s?.chargeTimeRemainingMs, "Android 9+ computeChargeTimeRemaining()"),
            CapabilityItem("Design capacity", Support.UNSUPPORTED,
                "No public Android API. Enter it manually in Settings for a health %."),
            CapabilityItem("Stop charging at target",
                if (protection.canStopCharging) Support.SUPPORTED else Support.UNSUPPORTED,
                if (protection.canStopCharging) protection.label
                else "Android doesn't let regular apps cut charging. This app alerts you instead."),
        )
        return DeviceCapabilities(manufacturer, model, androidVersion, sdkInt, items, protection)
    }
}
