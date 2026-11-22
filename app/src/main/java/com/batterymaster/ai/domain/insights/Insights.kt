package com.batterymaster.ai.domain.insights

import com.batterymaster.ai.domain.health.HealthCalculator
import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.SamplePoint
import com.batterymaster.ai.domain.model.SessionSummary
import com.batterymaster.ai.domain.model.SessionType

private const val HOUR = 3_600_000.0
private const val GAP_MS = 20 * 60_000L

private fun SamplePoint.trustedDrawMa(): Int? {
    val i = currentMa ?: return null
    if (currentConfidence != Confidence.HIGH && currentConfidence != Confidence.MEDIUM) return null
    return if (i < 0) -i else null
}

// ---------------------------------------------------------------- Deep sleep / standby

enum class SleepVerdict(val label: String) {
    EXCELLENT("Excellent – the phone sleeps deeply"),
    NORMAL("Normal standby drain"),
    HIGH("High – something keeps the phone awake"),
}

data class SleepResult(
    val start: Long,
    val end: Long,
    val dropPct: Int,
    val ratePctPerHour: Double,
    val avgMa: Int?,
    val verdict: SleepVerdict,
) {
    val durationMs: Long get() = end - start
}

/** Longest unplugged, screen-off stretch (≥ 1 h) in the given samples – usually the night. */
object SleepAnalyzer {
    const val MIN_STRETCH_MS = 60 * 60_000L

    fun longestScreenOff(samplesUnsorted: List<SamplePoint>): SleepResult? {
        val s = samplesUnsorted.sortedBy { it.timestamp }
        var best: List<SamplePoint>? = null
        var run = ArrayList<SamplePoint>()
        fun close() {
            if (run.size >= 2) {
                val d = run.last().timestamp - run.first().timestamp
                val bd = best?.let { it.last().timestamp - it.first().timestamp } ?: -1L
                if (d > bd) best = run
            }
            run = ArrayList()
        }
        for (p in s) {
            val ok = !p.pluggedIn && !p.screenOn
            if (!ok) {
                close()
                continue
            }
            if (run.isNotEmpty() && p.timestamp - run.last().timestamp > GAP_MS) close()
            run.add(p)
        }
        close()
        val r = best ?: return null
        val dur = r.last().timestamp - r.first().timestamp
        if (dur < MIN_STRETCH_MS) return null
        val drop = (r.first().levelPct - r.last().levelPct).coerceAtLeast(0)
        val rate = drop / (dur / HOUR)
        val draws = r.mapNotNull { it.trustedDrawMa() }
        val verdict = when {
            rate < 0.8 -> SleepVerdict.EXCELLENT
            rate <= 2.0 -> SleepVerdict.NORMAL
            else -> SleepVerdict.HIGH
        }
        return SleepResult(r.first().timestamp, r.last().timestamp, drop, rate,
            draws.takeIf { it.isNotEmpty() }?.average()?.toInt(), verdict)
    }
}

// ---------------------------------------------------------------- Wear counter

data class WearStats(
    val sessions: Int,
    /** Sum of percentage charged / 100 – "equivalent full cycles". */
    val cycles: Double,
    /** Cycles weighted by stress (heat and charging to near 100%). */
    val weightedCycles: Double,
    val since: Long?,
) {
    /** 1.0 = gentle charging; higher means each cycle wears the battery more. */
    val stressFactor: Double get() = if (cycles > 0) weightedCycles / cycles else 1.0
}

/**
 * Transparent, simple wear estimate. Each charge counts its added percentage as a fraction of
 * a cycle, weighted ×1.5 if it got hot (≥ 40 °C) and ×1.5 if it ended at 95 % or more.
 * It's an indicator of habits, not a measurement of chemistry.
 */
object WearCalculator {
    fun compute(sessions: List<SessionSummary>): WearStats {
        val charges = sessions.filter { it.type == SessionType.CHARGE && !it.isOpen && it.levelChange > 0 }
        var cycles = 0.0
        var weighted = 0.0
        for (c in charges) {
            val part = c.levelChange / 100.0
            var w = 1.0
            if ((c.maxTempC ?: 0f) >= 40f) w *= 1.5
            if (c.endLevel >= 95) w *= 1.5
            cycles += part
            weighted += part * w
        }
        return WearStats(charges.size, cycles, weighted, charges.minOfOrNull { it.startTime })
    }
}

// ---------------------------------------------------------------- Health trend over months

data class HealthTrend(
    val fromMah: Int,
    val toMah: Int,
    val changePct: Double,
    val months: Double,
) {
    val changePctPerYear: Double get() = if (months > 0) changePct / months * 12 else 0.0
}

object HealthTrendCalculator {
    private const val DAY = 86_400_000L

    /** Compares the median of the first 30 days of estimates with the last 30 days (needs ≥ 60 days). */
    fun compute(all: List<CapacityEstimate>): HealthTrend? {
        val est = all.filter { it.accepted && it.capacityMah != null }.sortedBy { it.timestamp }
        if (est.size < 4) return null
        val first = est.first().timestamp
        val last = est.last().timestamp
        if (last - first < 60 * DAY) return null
        val a = est.filter { it.timestamp <= first + 30 * DAY }.map { it.capacityMah!!.toDouble() }
        val b = est.filter { it.timestamp >= last - 30 * DAY }.map { it.capacityMah!!.toDouble() }
        if (a.size < 2 || b.size < 2) return null
        val ma = HealthCalculator.median(a)
        val mb = HealthCalculator.median(b)
        return HealthTrend(ma.toInt(), mb.toInt(), (mb - ma) / ma * 100, (last - first) / (30.44 * DAY))
    }
}

// ---------------------------------------------------------------- Daily summary

data class DailyStats(
    val screenOnMs: Long,
    val usedPct: Int,
    val avgDrawMa: Int?,
    val maxTempC: Float?,
    val charges: Int,
    val sleep: SleepResult?,
)

object DailySummaryCalculator {
    fun compute(samplesUnsorted: List<SamplePoint>, sessions: List<SessionSummary>, from: Long, to: Long): DailyStats? {
        val s = samplesUnsorted.filter { it.timestamp in from..to }.sortedBy { it.timestamp }
        if (s.size < 2) return null
        var screenOn = 0L
        var used = 0
        for ((a, b) in s.zipWithNext()) {
            val dt = b.timestamp - a.timestamp
            if (dt <= 0 || dt > GAP_MS) continue
            if (a.screenOn) screenOn += dt
            if (!a.pluggedIn && !b.pluggedIn) used += (a.levelPct - b.levelPct).coerceAtLeast(0)
        }
        val draws = s.filter { !it.pluggedIn }.mapNotNull { it.trustedDrawMa() }
        return DailyStats(
            screenOnMs = screenOn,
            usedPct = used,
            avgDrawMa = draws.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            maxTempC = s.mapNotNull { it.temperatureC }.maxOrNull(),
            charges = sessions.count { it.type == SessionType.CHARGE && it.startTime in from..to && it.levelChange > 0 },
            sleep = SleepAnalyzer.longestScreenOff(s),
        )
    }
}

// ---------------------------------------------------------------- Charger test

data class ChargerTestResult(val avgMa: Int, val peakMa: Int, val avgW: Float?, val samples: Int)

object ChargerTestCalculator {
    /** [currents] in mA (positive = into battery); [watts] battery-side power where known. */
    fun compute(currents: List<Int>, watts: List<Float>): ChargerTestResult? {
        val c = currents.filter { it > 0 }
        if (c.size < 5) return null
        return ChargerTestResult(c.average().toInt(), c.max(), watts.takeIf { it.isNotEmpty() }?.average()?.toFloat(), c.size)
    }
}
