package com.batterymaster.ai.domain.health

import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.model.CapacityMethod
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.SamplePoint

/**
 * Estimates full-charge capacity from ONE charging session. Results are only accepted when
 * the session covers a wide level span with continuous data; everything else is recorded
 * with an explicit rejection reason so the methodology stays auditable.
 *
 * Integer battery levels add up to ±1 percentage point of error at each end, so a 30-point
 * span carries roughly ±7 % error and a 60-point span roughly ±3 %. That is why the minimum
 * span is 30 and why single estimates are never shown as the final health number.
 */
object CapacityEstimator {
    const val MIN_LEVEL_SPAN = 30
    const val MIN_PLAUSIBLE_MAH = 500
    const val MAX_PLAUSIBLE_MAH = 30_000
    const val MAX_COUNTER_GAP_MS = 10 * 60_000L
    const val MAX_INTEGRATION_GAP_MS = 5 * 60_000L

    fun estimate(sessionId: Long, samplesUnsorted: List<SamplePoint>, now: Long): List<CapacityEstimate> {
        val samples = samplesUnsorted.sortedBy { it.timestamp }
        if (samples.size < 2) return emptyList()
        val span = samples.last().levelPct - samples.first().levelPct
        if (span <= 0) return emptyList() // Nothing charged; not worth recording a rejection.

        val out = ArrayList<CapacityEstimate>(2)
        counterEstimate(sessionId, samples, now)?.let { out += it }
        integrationEstimate(sessionId, samples, now)?.let { out += it }
        return out
    }

    private fun reject(sessionId: Long, now: Long, method: CapacityMethod, span: Int, reason: String) =
        CapacityEstimate(now, sessionId, method, null, span, false, reason, Confidence.NONE)

    private fun counterEstimate(sessionId: Long, samples: List<SamplePoint>, now: Long): CapacityEstimate? {
        val m = CapacityMethod.CHARGE_COUNTER_DELTA
        val withCounter = samples.filter { it.chargeCounterMah != null }
        if (withCounter.size < 2) return null // Device has no charge counter; nothing to report.
        val first = withCounter.first()
        val last = withCounter.last()
        val span = last.levelPct - first.levelPct
        if (span < MIN_LEVEL_SPAN) return reject(sessionId, now, m, span, "Charged only $span% (needs at least $MIN_LEVEL_SPAN%)")
        if (withCounter.zipWithNext().any { (a, b) -> b.timestamp - a.timestamp > MAX_COUNTER_GAP_MS }) {
            return reject(sessionId, now, m, span, "Gap in measurements during charging")
        }
        val delta = last.chargeCounterMah!! - first.chargeCounterMah!!
        if (delta <= 0) return reject(sessionId, now, m, span, "Charge counter did not increase")
        val drops = withCounter.zipWithNext().sumOf { (a, b) ->
            (a.chargeCounterMah!! - b.chargeCounterMah!!).coerceAtLeast(0)
        }
        if (drops > 0.05 * delta) return reject(sessionId, now, m, span, "Charge counter decreased during charging (heavy use while charging?)")

        // If counter/level is perfectly constant the "counter" is computed from the level
        // and a fixed nominal capacity, so it can never reveal wear.
        val ratios = withCounter.filter { it.levelPct >= 10 }.map { it.chargeCounterMah!!.toDouble() / it.levelPct }
        if (ratios.size >= 5) {
            val mean = ratios.average()
            val spread = (ratios.max() - ratios.min()) / mean
            if (spread < 0.001) {
                return reject(sessionId, now, m, span, "Charge counter appears to be derived from the level, not measured")
            }
        }

        val capacity = (delta.toDouble() * 100.0 / span).toInt()
        if (capacity !in MIN_PLAUSIBLE_MAH..MAX_PLAUSIBLE_MAH) {
            return reject(sessionId, now, m, span, "Result $capacity mAh outside plausible range")
        }
        val confidence = when {
            span >= 60 -> Confidence.HIGH
            span >= 40 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return CapacityEstimate(now, sessionId, m, capacity, span, true, null, confidence)
    }

    private fun integrationEstimate(sessionId: Long, samples: List<SamplePoint>, now: Long): CapacityEstimate? {
        val m = CapacityMethod.CURRENT_INTEGRATION
        val trusted = samples.filter {
            it.currentMa != null && (it.currentConfidence == Confidence.HIGH || it.currentConfidence == Confidence.MEDIUM)
        }
        if (trusted.size < 2) return null // No usable current sensor; nothing to report.
        val span = trusted.last().levelPct - trusted.first().levelPct
        if (span < MIN_LEVEL_SPAN) return reject(sessionId, now, m, span, "Charged only $span% (needs at least $MIN_LEVEL_SPAN%)")
        var mah = 0.0
        var coveredMs = 0L
        for ((a, b) in trusted.zipWithNext()) {
            val dt = b.timestamp - a.timestamp
            if (dt <= 0 || dt > MAX_INTEGRATION_GAP_MS) continue
            mah += (a.currentMa!! + b.currentMa!!) / 2.0 * dt / 3_600_000.0
            coveredMs += dt
        }
        val totalMs = trusted.last().timestamp - trusted.first().timestamp
        if (totalMs <= 0 || coveredMs < 0.9 * totalMs) {
            return reject(sessionId, now, m, span, "Current samples too sparse (covered ${(100.0 * coveredMs / totalMs.coerceAtLeast(1)).toInt()}% of the session)")
        }
        if (mah <= 0) return reject(sessionId, now, m, span, "Net charge was not positive")
        val capacity = (mah * 100.0 / span).toInt()
        if (capacity !in MIN_PLAUSIBLE_MAH..MAX_PLAUSIBLE_MAH) {
            return reject(sessionId, now, m, span, "Result $capacity mAh outside plausible range")
        }
        // Sampling at ~1/min misses short current spikes, so cap confidence at MEDIUM.
        val confidence = if (span >= 50) Confidence.MEDIUM else Confidence.LOW
        return CapacityEstimate(now, sessionId, m, capacity, span, true, null, confidence)
    }
}
