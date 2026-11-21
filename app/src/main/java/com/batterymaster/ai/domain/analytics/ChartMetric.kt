package com.batterymaster.ai.domain.analytics

/**
 * Chartable metrics. `expression` and `filter` are fixed SQL fragments over the samples
 * table (never user input), aggregated in SQLite so long ranges never load raw rows.
 */
enum class ChartMetric(
    val label: String,
    val unit: String,
    val expression: String,
    val filter: String?,
    val category: ChartCategory,
) {
    LEVEL("Battery level", "%", "levelPct", null, ChartCategory.LEVEL),
    TEMPERATURE("Battery temperature", "°C", "temperatureC", null, ChartCategory.THERMAL),
    TEMP_CHARGING("Temperature while charging", "°C", "temperatureC", "plug != 'NONE'", ChartCategory.THERMAL),
    TEMP_DISCHARGING("Temperature while unplugged", "°C", "temperatureC", "plug = 'NONE'", ChartCategory.THERMAL),
    VOLTAGE("Battery voltage", "mV", "voltageMv", null, ChartCategory.ELECTRICAL),
    CHARGE_CURRENT("Charging current", "mA", "currentMa", "plug != 'NONE' AND currentMa > 0 AND currentConfidence IN ('HIGH','MEDIUM')", ChartCategory.CHARGING),
    DISCHARGE_CURRENT("Discharge current", "mA", "(-currentMa)", "plug = 'NONE' AND currentMa < 0 AND currentConfidence IN ('HIGH','MEDIUM')", ChartCategory.DISCHARGING),
    CHARGE_POWER("Charging power (battery side)", "W", "(voltageMv * currentMa / 1000000.0)", "plug != 'NONE' AND currentMa > 0 AND voltageMv IS NOT NULL AND currentConfidence IN ('HIGH','MEDIUM')", ChartCategory.CHARGING),
    LEVEL_SCREEN_ON("Level while screen on", "%", "levelPct", "plug = 'NONE' AND screenOn = 1", ChartCategory.DISCHARGING),
    LEVEL_SCREEN_OFF("Level while screen off", "%", "levelPct", "plug = 'NONE' AND screenOn = 0", ChartCategory.DISCHARGING),
}

enum class ChartCategory(val label: String) {
    LEVEL("Level"),
    CHARGING("Charging"),
    DISCHARGING("Discharging"),
    THERMAL("Thermal"),
    ELECTRICAL("Electrical"),
    HEALTH("Health & forecast"),
}

enum class TimeRange(val label: String, val spanMs: Long, val bucketMs: Long) {
    LIVE("Live", 15 * 60_000L, 15_000L),
    HOUR("1H", 3_600_000L, 60_000L),
    DAY("24H", 86_400_000L, 10 * 60_000L),
    WEEK("7D", 7 * 86_400_000L, 3_600_000L),
    MONTH("30D", 30 * 86_400_000L, 6 * 3_600_000L),
    YEAR("1Y", 365 * 86_400_000L, 86_400_000L),
    CUSTOM("Custom", 0L, 0L);

    companion object {
        /** About 200 buckets for any custom range, never below 15 s. */
        fun bucketFor(spanMs: Long): Long = (spanMs / 200).coerceAtLeast(15_000L)
    }
}
