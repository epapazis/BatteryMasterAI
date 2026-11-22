package com.batterymaster.ai.domain.usage

import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.SamplePoint

/** Estimated battery use attributed to one app (or to "screen off"). */
data class AppPower(
    val key: String,
    /** Estimated charge drawn while this app was on screen; null if the device has no current sensor. */
    val mah: Double?,
    /** Battery percentage points lost while this app was on screen. */
    val pctDrop: Float,
    val durationMs: Long,
) {
    val avgMa: Double? get() = mah?.let { if (durationMs > 0) it / (durationMs / 3_600_000.0) else null }
}

/**
 * Attributes unplugged battery drain to whichever app was on screen between two consecutive
 * measurements. This is an ESTIMATE: Android doesn't expose per-app energy to normal apps, so
 * background work done by other apps is counted against the visible app or "screen off".
 */
object AppPowerCalculator {
    const val SCREEN_OFF = "__screen_off__"
    const val UNKNOWN = "__unknown__"
    const val MAX_GAP_MS = 20 * 60_000L

    private fun SamplePoint.drawMa(): Double? {
        val i = currentMa ?: return null
        if (currentConfidence != Confidence.HIGH && currentConfidence != Confidence.MEDIUM) return null
        return if (i < 0) -i.toDouble() else null
    }

    fun compute(samplesUnsorted: List<SamplePoint>): List<AppPower> {
        val s = samplesUnsorted.sortedBy { it.timestamp }
        val mah = HashMap<String, Double>()
        val hasCurrent = HashSet<String>()
        val drop = HashMap<String, Float>()
        val time = HashMap<String, Long>()
        for ((a, b) in s.zipWithNext()) {
            if (a.pluggedIn || b.pluggedIn) continue
            val dt = b.timestamp - a.timestamp
            if (dt <= 0 || dt > MAX_GAP_MS) continue
            val key = if (!a.screenOn) SCREEN_OFF else a.foregroundApp ?: UNKNOWN
            time[key] = (time[key] ?: 0L) + dt
            drop[key] = (drop[key] ?: 0f) + (a.levelPct - b.levelPct).coerceAtLeast(0)
            val da = a.drawMa()
            val db = b.drawMa()
            val draw = when {
                da != null && db != null -> (da + db) / 2
                else -> da ?: db
            }
            if (draw != null) {
                mah[key] = (mah[key] ?: 0.0) + draw * dt / 3_600_000.0
                hasCurrent += key
            }
        }
        return time.keys.map { k ->
            AppPower(k, if (k in hasCurrent) mah[k] else null, drop[k] ?: 0f, time[k] ?: 0L)
        }.sortedWith(compareByDescending<AppPower> { it.mah ?: -1.0 }.thenByDescending { it.pctDrop }.thenByDescending { it.durationMs })
    }
}
