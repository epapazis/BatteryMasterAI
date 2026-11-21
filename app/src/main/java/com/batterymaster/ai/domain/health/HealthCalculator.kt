package com.batterymaster.ai.domain.health

import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.model.CapacityMethod
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.DataClass
import com.batterymaster.ai.domain.model.Measurement
import kotlin.math.abs

data class HealthSummary(
    val estimatedCapacity: Measurement<Int>?,
    val designCapacityMah: Int?,
    val healthPct: Measurement<Float>?,
    val degradationPct: Float?,
    val confidence: Confidence,
    val usedEstimates: Int,
    val dispersionPct: Float?,
    val lastEstimateTime: Long?,
    val method: CapacityMethod?,
    val explanation: String,
)

object HealthCalculator {
    const val WINDOW_DAYS = 120L
    const val MAX_ESTIMATES = 10

    fun summarize(all: List<CapacityEstimate>, designMah: Int?, now: Long): HealthSummary {
        val since = now - WINDOW_DAYS * 86_400_000L
        val accepted = all.filter { it.accepted && it.capacityMah != null && it.timestamp >= since }
        // Prefer charge-counter estimates; fall back to current integration only if none exist.
        val counter = accepted.filter { it.method == CapacityMethod.CHARGE_COUNTER_DELTA }
        val pool = (counter.ifEmpty { accepted.filter { it.method == CapacityMethod.CURRENT_INTEGRATION } })
            .sortedByDescending { it.timestamp }
            .take(MAX_ESTIMATES)
        val design = designMah?.takeIf { it > 0 }

        if (pool.isEmpty()) {
            return HealthSummary(
                null, design, null, null, Confidence.NONE, 0, null, null, null,
                "No reliable capacity estimate yet. A charging session that covers at least " +
                    "${CapacityEstimator.MIN_LEVEL_SPAN}% (for example 20% → 80%) with monitoring on is needed.",
            )
        }
        val values = pool.map { it.capacityMah!!.toDouble() }
        val med = median(values)
        val mad = median(values.map { abs(it - med) })
        val dispersion = (mad / med * 100).toFloat()
        val confidence = when {
            pool.size >= 5 && dispersion < 3f -> Confidence.HIGH
            pool.size >= 3 && dispersion < 6f -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        val method = pool.first().method
        val latest = pool.maxOf { it.timestamp }
        val capacity = Measurement(
            med.toInt(), "mAh", latest, method.label, DataClass.ESTIMATED, confidence,
            "Median of ${pool.size} session estimate(s)",
        )
        val health = design?.let {
            Measurement(
                (med / it * 100).toFloat(), "%", latest, "estimated capacity ÷ design capacity",
                DataClass.ESTIMATED, confidence,
                "Design capacity entered by you: $it mAh",
            )
        }
        val explanation = buildString {
            append("Capacity is the median of the ${pool.size} most recent qualifying charging session(s), ")
            append("using ${method.label.lowercase()}. Sessions must span ≥${CapacityEstimator.MIN_LEVEL_SPAN}% ")
            append("with no measurement gaps. Spread between sessions: ±${"%.1f".format(dispersion)}%.")
            if (design == null) append(" Enter your battery's design capacity in Settings to see a health percentage.")
        }
        return HealthSummary(
            estimatedCapacity = capacity,
            designCapacityMah = design,
            healthPct = health,
            degradationPct = health?.let { (100f - it.value).coerceAtLeast(0f) },
            confidence = confidence,
            usedEstimates = pool.size,
            dispersionPct = dispersion,
            lastEstimateTime = latest,
            method = method,
            explanation = explanation,
        )
    }

    fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2.0 else s[mid]
    }
}
