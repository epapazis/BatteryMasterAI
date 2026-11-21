package com.batterymaster.ai.domain.doctor

import com.batterymaster.ai.domain.health.HealthSummary
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.HealthStatus
import com.batterymaster.ai.domain.model.SessionSummary
import com.batterymaster.ai.domain.model.SessionType

enum class Severity { GOOD, INFO, NOTICE, WARNING }

data class Finding(
    val category: String,
    val title: String,
    val severity: Severity,
    val detail: String,
    val evidence: List<String>,
    val recommendation: String?,
    val confidence: Confidence,
    val penalty: Int,
)

data class TemperatureStats(val samples: Int, val aboveWarm: Int, val maxC: Float?, val avgC: Float?) {
    val warmShare: Float get() = if (samples == 0) 0f else aboveWarm.toFloat() / samples
}

data class DoctorInput(
    val now: Long,
    val systemHealth: HealthStatus?,
    val health: HealthSummary,
    val sessions30d: List<SessionSummary>,
    val temps7d: TemperatureStats,
    val dataSpanMs: Long,
)

data class DoctorReport(
    val generatedAt: Long,
    val score: Int?,
    val condition: String,
    val findings: List<Finding>,
    val confidence: Confidence,
    val dataSummary: String,
) {
    companion object {
        /** Shown to the user so the score is never a black box. */
        val SCORING_CRITERIA = listOf(
            "Starts at 100 points.",
            "Android reports a battery problem (overheat, failure, dead, over-voltage, cold): −25.",
            "Estimated health 75–85%: −10 · 65–75%: −20 · below 65%: −30 (only with medium or high confidence).",
            "Over 10% of the last 7 days above 40 °C: −15 · over 3%: −7.",
            "3 or more charging sessions above 42 °C in 30 days: −8.",
            "70%+ of charges end at 98–100% (5+ sessions): −5.",
            "3 or more charges started at or below 10% in 30 days: −5.",
            "Screen-off drain above 2%/h (3+ hours observed): −5.",
            "No score until at least 24 hours of monitoring data exist.",
        )
        const val WARM_C = 40f
        const val HOT_CHARGE_C = 42f
    }
}

object BatteryDoctor {
    private const val DAY = 86_400_000L

    fun examine(input: DoctorInput): DoctorReport {
        val f = ArrayList<Finding>()

        // 1. System-reported health status.
        val sys = input.systemHealth
        if (sys != null && sys.isProblem) {
            f += Finding(
                "System status", "Android reports: ${sys.label}", Severity.WARNING,
                "The battery driver itself flagged a condition. This comes from the device, not from this app's analysis.",
                listOf("BatteryManager health = ${sys.label}"),
                "If it persists after the phone cools down or restarts, have the battery checked by an authorised service centre.",
                Confidence.HIGH, 25,
            )
        } else if (sys == HealthStatus.GOOD) {
            f += Finding("System status", "Android reports the battery as Good", Severity.GOOD,
                "No fault flag from the battery driver.", listOf("BatteryManager health = Good"), null, Confidence.HIGH, 0)
        }

        // 2. Estimated capacity / health.
        val h = input.health
        val pct = h.healthPct?.value
        when {
            pct != null && (h.confidence == Confidence.HIGH || h.confidence == Confidence.MEDIUM) -> {
                val (sev, pen, title) = when {
                    pct >= 85f -> Triple(Severity.GOOD, 0, "Capacity is in good shape")
                    pct >= 75f -> Triple(Severity.NOTICE, 10, "Moderate capacity wear")
                    pct >= 65f -> Triple(Severity.WARNING, 20, "Significant capacity wear")
                    else -> Triple(Severity.WARNING, 30, "Heavy capacity wear")
                }
                f += Finding(
                    "Capacity", title, sev,
                    "Estimated full-charge capacity is ${"%.0f".format(pct)}% of the design capacity you entered.",
                    listOf(
                        "Estimated capacity: ${h.estimatedCapacity?.value} mAh (${h.usedEstimates} sessions, ±${"%.1f".format(h.dispersionPct ?: 0f)}%)",
                        "Design capacity: ${h.designCapacityMah} mAh",
                    ),
                    if (pen >= 20) "Expect shorter runtime than when new. A replacement may be worth considering if runtime no longer fits your day." else null,
                    h.confidence, pen,
                )
            }
            pct != null -> f += Finding(
                "Capacity", "Early capacity estimate", Severity.INFO,
                "Health is about ${"%.0f".format(pct)}%, but based on too few sessions to score.",
                listOf("${h.usedEstimates} qualifying session(s)"), "Keep monitoring on during a few long charges (e.g. 20% → 90%).",
                Confidence.LOW, 0,
            )
            h.estimatedCapacity != null -> f += Finding(
                "Capacity", "Capacity estimated, health % unknown", Severity.INFO,
                "Estimated capacity is ${h.estimatedCapacity.value} mAh. Enter the design capacity in Settings to compare.",
                listOf("${h.usedEstimates} qualifying session(s)"), "Find the rated capacity on the manufacturer's spec sheet.",
                h.confidence, 0,
            )
            else -> f += Finding(
                "Capacity", "Capacity not yet measurable", Severity.INFO, h.explanation, emptyList(), null, Confidence.NONE, 0,
            )
        }

        // 3. Temperature exposure over 7 days.
        val t = input.temps7d
        if (t.samples >= 30) {
            val share = t.warmShare
            val (sev, pen) = when {
                share > 0.10f -> Severity.WARNING to 15
                share > 0.03f -> Severity.NOTICE to 7
                else -> Severity.GOOD to 0
            }
            f += Finding(
                "Temperature",
                if (pen == 0) "Temperatures are mostly comfortable" else "Battery often runs warm",
                sev,
                "${"%.1f".format(share * 100)}% of readings in the last 7 days were above ${DoctorReport.WARM_C.toInt()} °C. Heat is the main accelerator of lithium-ion ageing.",
                listOfNotNull(
                    "${t.samples} temperature readings",
                    t.maxC?.let { "Maximum: ${"%.1f".format(it)} °C" },
                    t.avgC?.let { "Average: ${"%.1f".format(it)} °C" },
                ),
                if (pen > 0) "Avoid charging under pillows or in cases while gaming; remove thick cases during fast charging." else null,
                Confidence.HIGH, pen,
            )
        }

        val charges = input.sessions30d.filter { it.type == SessionType.CHARGE && !it.isOpen }
        val discharges = input.sessions30d.filter { it.type == SessionType.DISCHARGE && !it.isOpen }

        // 4. Hot charging sessions.
        val hot = charges.filter { (it.maxTempC ?: 0f) >= DoctorReport.HOT_CHARGE_C }
        if (hot.size >= 3) {
            f += Finding(
                "Charging", "Charging often gets hot", Severity.NOTICE,
                "${hot.size} of ${charges.size} charging sessions in 30 days peaked at or above ${DoctorReport.HOT_CHARGE_C.toInt()} °C.",
                hot.take(3).map { "Session peaked at ${"%.1f".format(it.maxTempC)} °C" },
                "Charge in a cooler spot, or use a slower charger overnight.", Confidence.HIGH, 8,
            )
        }

        // 5. Full-charge habit.
        if (charges.size >= 5) {
            val full = charges.count { it.endLevel >= 98 }
            val share = full.toFloat() / charges.size
            if (share >= 0.7f) {
                f += Finding(
                    "Charging habits", "Most charges go to 100%", Severity.NOTICE,
                    "$full of ${charges.size} charges ended at 98–100%. Sitting at full charge, especially when warm, adds wear.",
                    listOf("${"%.0f".format(share * 100)}% of sessions ended at ≥98%"),
                    "Use the charging target alert (for example 80–90%), or your phone's built-in charging limit if it has one.",
                    Confidence.HIGH, 5,
                )
            } else {
                f += Finding("Charging habits", "Charging habits look balanced", Severity.GOOD,
                    "$full of ${charges.size} charges ended at 98–100%.", emptyList(), null, Confidence.HIGH, 0)
            }
        }

        // 6. Deep discharges.
        val deep = charges.count { it.startLevel <= 10 }
        if (deep >= 3) {
            f += Finding(
                "Charging habits", "Frequent deep discharges", Severity.NOTICE,
                "$deep charges in 30 days started at 10% or lower.",
                listOf("$deep sessions started ≤10%"),
                "Plugging in a little earlier (around 20%) is gentler on the battery.", Confidence.HIGH, 5,
            )
        }

        // 7. Standby drain.
        val offMs = discharges.sumOf { it.screenOffMs ?: 0L }
        val offDrain = discharges.sumOf { (it.screenOffDrainPct ?: 0f).toDouble() }
        if (offMs >= 3 * 3_600_000L) {
            val rate = offDrain / (offMs / 3_600_000.0)
            val high = rate > 2.0
            f += Finding(
                "Standby", if (high) "High screen-off drain" else "Standby drain is normal",
                if (high) Severity.NOTICE else Severity.GOOD,
                "Screen-off drain averages ${"%.2f".format(rate)}%/h over ${"%.1f".format(offMs / 3_600_000.0)} h observed. This is an estimate from level changes, not per-app energy.",
                listOf("${"%.1f".format(offDrain)}% lost while screen off"),
                if (high) "Check Android's battery usage screen for apps active in the background." else null,
                if (offMs >= 12 * 3_600_000L) Confidence.MEDIUM else Confidence.LOW, if (high) 5 else 0,
            )
        }

        val enoughData = input.dataSpanMs >= DAY
        val score = if (enoughData) (100 - f.sumOf { it.penalty }).coerceIn(0, 100) else null
        val condition = when {
            score == null -> "Collecting data"
            score >= 85 -> "Good"
            score >= 70 -> "Fair"
            score >= 50 -> "Needs attention"
            else -> "Poor"
        }
        val confidence = when {
            !enoughData -> Confidence.NONE
            input.dataSpanMs < 7 * DAY -> Confidence.LOW
            h.confidence == Confidence.NONE || h.confidence == Confidence.LOW -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }
        val spanH = input.dataSpanMs / 3_600_000.0
        val summary = "Based on ${"%.0f".format(spanH)} h of history, ${charges.size} charging and ${discharges.size} discharging sessions in the last 30 days. " +
            "Software can't see inside the battery: this report describes measurable behaviour and cannot confirm physical damage."
        return DoctorReport(input.now, score, condition,
            f.sortedByDescending { it.severity.ordinal }, confidence, summary)
    }
}
