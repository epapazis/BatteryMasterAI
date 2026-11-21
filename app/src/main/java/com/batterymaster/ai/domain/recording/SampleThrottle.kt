package com.batterymaster.ai.domain.recording

import com.batterymaster.ai.domain.model.SamplePoint
import kotlin.math.abs

/**
 * Decides whether a new reading is worth persisting. Keeps history dense when something
 * changes and sparse when nothing does, which bounds database growth and battery cost.
 */
object SampleThrottle {
    const val MIN_INTERVAL_MS = 15_000L
    const val MAX_INTERVAL_PLUGGED_MS = 60_000L
    const val MAX_INTERVAL_UNPLUGGED_MS = 5 * 60_000L

    fun shouldStore(last: SamplePoint?, next: SamplePoint): Boolean {
        if (last == null) return true
        val dt = next.timestamp - last.timestamp
        if (dt <= 0) return false
        val stateChanged = last.plug != next.plug || last.status != next.status
        if (stateChanged) return true
        if (dt < MIN_INTERVAL_MS) return false
        if (last.screenOn != next.screenOn) return true
        if (last.levelPct != next.levelPct) return true
        val lt = last.temperatureC
        val nt = next.temperatureC
        if (lt != null && nt != null && abs(lt - nt) >= 1f) return true
        val maxInterval = if (next.pluggedIn) MAX_INTERVAL_PLUGGED_MS else MAX_INTERVAL_UNPLUGGED_MS
        return dt >= maxInterval
    }
}
