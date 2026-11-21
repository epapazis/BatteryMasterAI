package com.batterymaster.ai.domain.prediction

import com.batterymaster.ai.TestData
import com.batterymaster.ai.TestData.MIN
import com.batterymaster.ai.TestData.T0
import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.model.CapacityMethod
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.DataClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionTest {

    @Test fun `regression recovers an exact line`() {
        val xs = (0..9).map { it.toDouble() }
        val fit = LinearRegression.fit(xs, xs.map { 3 * it + 2 })!!
        assertEquals(3.0, fit.slope, 1e-9)
        assertEquals(2.0, fit.intercept, 1e-9)
        assertEquals(1.0, fit.r2, 1e-9)
    }

    @Test fun `regression refuses degenerate input`() {
        assertNull(LinearRegression.fit(listOf(1.0, 1.0, 1.0), listOf(1.0, 2.0, 3.0)))
        assertNull(LinearRegression.fit(listOf(1.0, 2.0), listOf(1.0, 2.0)))
    }

    @Test fun `runtime from steady 10 percent per hour drain`() {
        // 60 min unplugged, one sample every 5 min, 1% per 6 min.
        val pts = (0..12).map { i -> TestData.point(T0 + i * 5 * MIN, 80 - (i * 5) / 6, plugged = false) }
        val now = pts.last().timestamp
        val p = RuntimePredictor.dischargeRemainingMinutes(pts, pts.last().levelPct, now, null)!!
        // ~70% left at ~10%/h → ~7 h.
        assertTrue("got ${p.value}", p.value in 360.0..480.0)
        assertEquals(DataClass.PREDICTION, p.dataClass)
        assertTrue(p.low <= p.value && p.value <= p.high)
    }

    @Test fun `runtime falls back to history and labels it an estimate`() {
        val p = RuntimePredictor.dischargeRemainingMinutes(emptyList(), 50, T0, 5f)!!
        assertEquals(600.0, p.value, 0.1)
        assertEquals(DataClass.ESTIMATED, p.dataClass)
        assertEquals(Confidence.LOW, p.confidence)
    }

    @Test fun `no runtime prediction without data`() {
        assertNull(RuntimePredictor.dischargeRemainingMinutes(emptyList(), 50, T0, null))
    }

    @Test fun `time to target uses recent charging rate`() {
        val pts = (0..20).map { i -> TestData.point(T0 + i * MIN, 40 + i / 2) } // 30%/h
        val p = RuntimePredictor.minutesToTarget(pts, 50, 80, pts.last().timestamp, null)!!
        assertTrue("got ${p.value}", p.value in 50.0..70.0)
    }

    @Test fun `system estimate is preferred for time to full`() {
        val p = RuntimePredictor.minutesToTarget(emptyList(), 50, 100, T0, 3_600_000L)!!
        assertEquals(60.0, p.value, 0.01)
        assertEquals(DataClass.SYSTEM_REPORTED, p.dataClass)
    }

    private fun est(day: Int, mah: Int) = CapacityEstimate(
        T0 + day * 86_400_000L, day.toLong(), CapacityMethod.CHARGE_COUNTER_DELTA, mah, 60, true, null, Confidence.HIGH,
    )

    @Test fun `forecast needs enough estimates over enough time`() {
        val few = (0..3).map { est(it * 20, 4500) }
        assertTrue(CapacityForecaster.forecast(few) is CapacityForecast.Insufficient)
        val short = (0..9).map { est(it, 4500) }
        assertTrue(CapacityForecaster.forecast(short) is CapacityForecast.Insufficient)
    }

    @Test fun `forecast extends a declining trend with a widening band`() {
        val list = (0..9).map { est(it * 12, 4500 - it * 12 + if (it % 2 == 0) 15 else -15) }
        val fc = CapacityForecaster.forecast(list)
        assertTrue(fc is CapacityForecast.Available)
        fc as CapacityForecast.Available
        assertTrue(fc.changeMahPerYear < 0)
        val first = fc.points.first()
        val last = fc.points.last()
        assertTrue(last.value < first.value)
        assertTrue((last.high - last.low) > (first.high - first.low))
        assertNotNull(fc.basis)
    }
}
