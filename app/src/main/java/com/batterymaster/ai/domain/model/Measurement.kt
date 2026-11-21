package com.batterymaster.ai.domain.model

/**
 * How a value was obtained. Shown in the UI next to every number so users can tell a
 * hardware reading from an app calculation or a forecast.
 */
enum class DataClass(val label: String, val shortLabel: String) {
    DIRECT_MEASUREMENT("Direct measurement", "Measured"),
    SYSTEM_REPORTED("Reported by Android", "System"),
    CALCULATED("Calculated from measurements", "Calculated"),
    ESTIMATED("Estimated from history", "Estimate"),
    PREDICTION("Prediction", "Forecast"),
}

enum class Confidence(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
    NONE("Insufficient data");

    fun min(other: Confidence): Confidence = if (other.ordinal > ordinal) other else this
}

/** A single value with full provenance. */
data class Measurement<T>(
    val value: T,
    val unit: String,
    val timestamp: Long,
    val source: String,
    val dataClass: DataClass,
    val confidence: Confidence = Confidence.HIGH,
    val note: String? = null,
)

/** Either a real measurement or an explicit, explained absence. Never a made-up value. */
sealed interface Reading<out T> {
    data class Available<T>(val measurement: Measurement<T>) : Reading<T>
    data class Unavailable(val reason: String) : Reading<Nothing>
}

val <T> Reading<T>.valueOrNull: T?
    get() = (this as? Reading.Available<T>)?.measurement?.value

val <T> Reading<T>.measurementOrNull: Measurement<T>?
    get() = (this as? Reading.Available<T>)?.measurement
