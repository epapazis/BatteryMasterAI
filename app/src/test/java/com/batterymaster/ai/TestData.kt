package com.batterymaster.ai

import com.batterymaster.ai.domain.model.ChargeStatus
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.PlugType
import com.batterymaster.ai.domain.model.SamplePoint

object TestData {
    const val MIN = 60_000L
    const val T0 = 1_700_000_000_000L

    fun point(
        t: Long,
        level: Int,
        plugged: Boolean = true,
        status: ChargeStatus = if (plugged) ChargeStatus.CHARGING else ChargeStatus.DISCHARGING,
        temp: Float? = 30f,
        voltage: Int? = 4000,
        current: Int? = null,
        currentConf: Confidence? = if (current != null) Confidence.HIGH else null,
        counter: Int? = null,
        screenOn: Boolean = true,
    ) = SamplePoint(
        timestamp = t,
        levelPct = level,
        status = status,
        plug = if (plugged) PlugType.AC else PlugType.NONE,
        temperatureC = temp,
        voltageMv = voltage,
        currentMa = current,
        currentConfidence = currentConf,
        chargeCounterMah = counter,
        screenOn = screenOn,
    )

    /**
     * A clean charging session from [from]% to [to]% for a battery of [capacityMah], sampled
     * every [stepMin] minutes. The counter includes level-rounding noise like real gauges.
     */
    fun chargeSession(
        from: Int,
        to: Int,
        capacityMah: Int,
        stepMin: Int = 1,
        pctPerMin: Double = 0.7,
        withCounter: Boolean = true,
        withCurrent: Boolean = false,
    ): List<SamplePoint> {
        val out = ArrayList<SamplePoint>()
        var exact = from.toDouble()
        var t = T0
        while (exact <= to + 1e-9) {
            val counter = (exact / 100.0 * capacityMah).toInt()
            val currentMa = (capacityMah * pctPerMin / 100.0 * 60).toInt() // mAh per hour
            out += point(
                t = t,
                level = Math.round(exact).toInt(),
                counter = if (withCounter) counter else null,
                current = if (withCurrent) currentMa else null,
            )
            exact += pctPerMin * stepMin
            t += stepMin * MIN
        }
        return out
    }
}
