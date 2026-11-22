package com.batterymaster.ai.domain

import com.batterymaster.ai.TestData
import com.batterymaster.ai.TestData.MIN
import com.batterymaster.ai.TestData.T0
import com.batterymaster.ai.data.prefs.AppSettings
import com.batterymaster.ai.domain.insights.ChargerTestCalculator
import com.batterymaster.ai.domain.insights.DailySummaryCalculator
import com.batterymaster.ai.domain.insights.HealthTrendCalculator
import com.batterymaster.ai.domain.insights.SleepAnalyzer
import com.batterymaster.ai.domain.insights.SleepVerdict
import com.batterymaster.ai.domain.insights.WearCalculator
import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.model.CapacityMethod
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.PlugType
import com.batterymaster.ai.domain.model.SessionSummary
import com.batterymaster.ai.domain.model.SessionType
import com.batterymaster.ai.domain.usage.AppPowerCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFeaturesTest {
    private val day = 86_400_000L

    @Test fun `app power is attributed to the app on screen`() {
        val pts = listOf(
            TestData.point(T0, 80, plugged = false, current = -600).copy(foregroundApp = "yt"),
            TestData.point(T0 + 10 * MIN, 78, plugged = false, current = -600).copy(foregroundApp = "chat"),
            TestData.point(T0 + 20 * MIN, 77, plugged = false, current = -300, screenOn = false),
            TestData.point(T0 + 40 * MIN, 76, plugged = false, current = -60, screenOn = false),
        )
        val r = AppPowerCalculator.compute(pts).associateBy { it.key }
        assertEquals(100.0, r.getValue("yt").mah!!, 0.01)          // 600 mA × 10 min
        assertEquals(75.0, r.getValue("chat").mah!!, 0.01)         // (600+300)/2 × 10 min
        assertEquals(2f, r.getValue("yt").pctDrop, 0.001f)
        assertEquals(20 * MIN, r.getValue(AppPowerCalculator.SCREEN_OFF).durationMs)
        assertEquals("yt", AppPowerCalculator.compute(pts).first().key)
    }

    @Test fun `app power ignores charging time and gaps`() {
        val pts = listOf(
            TestData.point(T0, 50, plugged = true, current = 1500).copy(foregroundApp = "yt"),
            TestData.point(T0 + 10 * MIN, 52, plugged = true, current = 1500).copy(foregroundApp = "yt"),
            TestData.point(T0 + 60 * MIN, 52, plugged = false, current = -500).copy(foregroundApp = "yt"),
        )
        assertTrue(AppPowerCalculator.compute(pts).isEmpty())
    }

    @Test fun `sleep analyzer finds the night and grades it`() {
        val night = (0..16).map { i ->
            TestData.point(T0 + i * 30 * MIN, 80 - i / 4, plugged = false, screenOn = false, current = -40)
        } // 8 h, 4% drop
        val r = SleepAnalyzer.longestScreenOff(night)!!
        assertEquals(8 * 60 * MIN, r.durationMs)
        assertEquals(4, r.dropPct)
        assertEquals(SleepVerdict.EXCELLENT, r.verdict)
        assertEquals(40, r.avgMa)
        assertNull(SleepAnalyzer.longestScreenOff(night.take(2)))
    }

    private fun charge(start: Int, end: Int, maxT: Float) = SessionSummary(
        1, SessionType.CHARGE, T0, T0 + 3_600_000L, T0 + 3_600_000L, start, end, PlugType.AC, 30f, maxT, 32f,
        null, null, null, null, null, null, null, null, null, 10, 0,
    )

    @Test fun `wear weights hot and full charges`() {
        val w = WearCalculator.compute(listOf(charge(20, 80, 35f), charge(50, 100, 42f)))
        assertEquals(1.1, w.cycles, 1e-9)
        assertEquals(0.6 + 0.5 * 2.25, w.weightedCycles, 1e-9)
        assertTrue(w.stressFactor > 1.0)
    }

    private fun est(d: Int, mah: Int) = CapacityEstimate(T0 + d * day, d.toLong(), CapacityMethod.CHARGE_COUNTER_DELTA,
        mah, 60, true, null, Confidence.HIGH)

    @Test fun `health trend compares first and last month`() {
        val list = listOf(est(0, 4500), est(10, 4520), est(100, 4300), est(110, 4320))
        val t = HealthTrendCalculator.compute(list)!!
        assertEquals(4510, t.fromMah)
        assertEquals(4310, t.toMah)
        assertTrue(t.changePct < 0)
        assertNull(HealthTrendCalculator.compute(list.take(2)))
    }

    @Test fun `daily summary totals screen time and usage`() {
        val pts = (0..12).map { i ->
            TestData.point(T0 + i * 10 * MIN, 90 - i, plugged = false, screenOn = i < 6, current = -400)
        }
        val s = DailySummaryCalculator.compute(pts, emptyList(), T0, T0 + day)!!
        assertEquals(60 * MIN, s.screenOnMs)
        assertEquals(12, s.usedPct)
        assertEquals(400, s.avgDrawMa)
    }

    @Test fun `charger test needs enough positive readings`() {
        assertNull(ChargerTestCalculator.compute(listOf(1000, 1100), emptyList()))
        val r = ChargerTestCalculator.compute(listOf(1000, 1200, 1400, 1200, 1200, -50), listOf(5f, 6f))!!
        assertEquals(1200, r.avgMa)
        assertEquals(1400, r.peakMa)
        assertNotNull(r.avgW)
    }

    @Test fun `quiet hours wrap past midnight`() {
        val s = AppSettings(quietHoursEnabled = true, quietStartHour = 23, quietEndHour = 7)
        assertTrue(s.isQuietHour(23))
        assertTrue(s.isQuietHour(3))
        assertFalse(s.isQuietHour(7))
        assertFalse(s.isQuietHour(12))
        assertFalse(AppSettings().isQuietHour(3))
    }
}
