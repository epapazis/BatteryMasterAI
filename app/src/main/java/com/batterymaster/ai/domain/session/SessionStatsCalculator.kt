package com.batterymaster.ai.domain.session

import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.SamplePoint
import com.batterymaster.ai.domain.model.SessionType

data class SessionStats(
    val endLevel: Int,
    val startTempC: Float?,
    val maxTempC: Float?,
    val avgTempC: Float?,
    val avgCurrentMa: Int?,
    val maxCurrentMa: Int?,
    val avgVoltageMv: Int?,
    val avgPowerW: Float?,
    val energyWh: Float?,
    val screenOnMs: Long?,
    val screenOffMs: Long?,
    val screenOnDrainPct: Float?,
    val screenOffDrainPct: Float?,
    val sampleCount: Int,
    val dataGaps: Int,
)

object SessionStatsCalculator {
    /** Consecutive samples further apart than this are treated as missing data, not interpolated. */
    const val GAP_MS = 20 * 60_000L

    private fun SamplePoint.trustedCurrent(): Int? =
        currentMa?.takeIf { currentConfidence == Confidence.HIGH || currentConfidence == Confidence.MEDIUM }

    fun compute(type: SessionType, samplesUnsorted: List<SamplePoint>): SessionStats? {
        if (samplesUnsorted.isEmpty()) return null
        val s = samplesUnsorted.sortedBy { it.timestamp }
        val temps = s.mapNotNull { it.temperatureC }
        val volts = s.mapNotNull { it.voltageMv }

        // Magnitude of current in the session's direction.
        val currents = s.mapNotNull { p ->
            val i = p.trustedCurrent() ?: return@mapNotNull null
            when (type) {
                SessionType.CHARGE -> i.takeIf { it > 0 }
                SessionType.DISCHARGE -> (-i).takeIf { it > 0 }
            }
        }

        var gaps = 0
        var energyWh = 0.0
        var energyCoveredMs = 0L
        var onMs = 0L
        var offMs = 0L
        var onDrain = 0f
        var offDrain = 0f
        val powers = ArrayList<Double>()

        for (idx in s.indices) {
            val p = s[idx]
            val i = p.trustedCurrent()
            if (type == SessionType.CHARGE && i != null && i > 0 && p.voltageMv != null) {
                powers += p.voltageMv.toDouble() * i / 1_000_000.0
            }
            if (idx == 0) continue
            val a = s[idx - 1]
            val dt = p.timestamp - a.timestamp
            if (dt > GAP_MS) {
                gaps++
                continue
            }
            if (type == SessionType.CHARGE) {
                val ia = a.trustedCurrent()
                if (ia != null && i != null && a.voltageMv != null && p.voltageMv != null && ia > 0 && i > 0) {
                    val pa = a.voltageMv.toDouble() * ia / 1_000_000.0
                    val pb = p.voltageMv.toDouble() * i / 1_000_000.0
                    energyWh += (pa + pb) / 2.0 * dt / 3_600_000.0
                    energyCoveredMs += dt
                }
            } else {
                val drop = (a.levelPct - p.levelPct).toFloat()
                if (a.screenOn) {
                    onMs += dt
                    onDrain += drop
                } else {
                    offMs += dt
                    offDrain += drop
                }
            }
        }
        val totalMs = (s.last().timestamp - s.first().timestamp).coerceAtLeast(1)
        // Only report energy if most of the session was covered by valid power data.
        val energy = if (type == SessionType.CHARGE && energyCoveredMs >= 0.8 * totalMs) energyWh.toFloat() else null

        return SessionStats(
            endLevel = s.last().levelPct,
            startTempC = s.first().temperatureC,
            maxTempC = temps.maxOrNull(),
            avgTempC = temps.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            avgCurrentMa = currents.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            maxCurrentMa = currents.maxOrNull(),
            avgVoltageMv = volts.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            avgPowerW = powers.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            energyWh = energy,
            screenOnMs = if (type == SessionType.DISCHARGE) onMs else null,
            screenOffMs = if (type == SessionType.DISCHARGE) offMs else null,
            screenOnDrainPct = if (type == SessionType.DISCHARGE) onDrain else null,
            screenOffDrainPct = if (type == SessionType.DISCHARGE) offDrain else null,
            sampleCount = s.size,
            dataGaps = gaps,
        )
    }
}
