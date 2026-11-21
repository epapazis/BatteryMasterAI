package com.batterymaster.ai.domain

import com.batterymaster.ai.TestData
import com.batterymaster.ai.TestData.MIN
import com.batterymaster.ai.TestData.T0
import com.batterymaster.ai.domain.alerts.AlertConfig
import com.batterymaster.ai.domain.alerts.AlertInput
import com.batterymaster.ai.domain.alerts.AlertRules
import com.batterymaster.ai.domain.alerts.AlertState
import com.batterymaster.ai.domain.alerts.AlertType
import com.batterymaster.ai.domain.model.ChargeStatus
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.SessionType
import com.batterymaster.ai.domain.recording.SampleThrottle
import com.batterymaster.ai.domain.session.SessionStatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val cfg = AlertConfig(true, 80, true, true, 15, true, 42f)

    private fun input(level: Int, plugged: Boolean = true, temp: Float? = 30f, session: Long = 1, now: Long = T0,
                      status: ChargeStatus = ChargeStatus.CHARGING) =
        AlertInput(now, session, plugged, level, status, temp)

    @Test fun `target alert fires once per session`() {
        val (a1, s1) = AlertRules.evaluate(cfg, AlertState(), input(80))
        assertEquals(listOf(AlertType.TARGET_REACHED), a1)
        val (a2, _) = AlertRules.evaluate(cfg, s1, input(81))
        assertTrue(a2.isEmpty())
        val (a3, _) = AlertRules.evaluate(cfg, s1, input(80, session = 2))
        assertEquals(listOf(AlertType.TARGET_REACHED), a3)
    }

    @Test fun `no target alert when target is 100 or unplugged`() {
        assertTrue(AlertRules.evaluate(cfg.copy(targetPct = 100), AlertState(), input(99)).first.isEmpty())
        assertTrue(AlertRules.evaluate(cfg, AlertState(), input(85, plugged = false, status = ChargeStatus.DISCHARGING)).first.isEmpty())
    }

    @Test fun `low battery alert only when unplugged`() {
        val (a, _) = AlertRules.evaluate(cfg, AlertState(), input(10, plugged = false, status = ChargeStatus.DISCHARGING))
        assertEquals(listOf(AlertType.LOW_BATTERY), a)
    }

    @Test fun `temperature alert uses cooldown and hysteresis`() {
        val (a1, s1) = AlertRules.evaluate(cfg, AlertState(), input(50, temp = 43f))
        assertTrue(AlertType.HIGH_TEMPERATURE in a1)
        val (a2, s2) = AlertRules.evaluate(cfg, s1, input(50, temp = 44f, now = T0 + 60 * MIN))
        assertFalse(AlertType.HIGH_TEMPERATURE in a2) // not re-armed yet
        val (_, s3) = AlertRules.evaluate(cfg, s2, input(50, temp = 39f, now = T0 + 61 * MIN))
        assertTrue(s3.tempArmed)
        val (a4, _) = AlertRules.evaluate(cfg, s3, input(50, temp = 43f, now = T0 + 62 * MIN))
        assertTrue(AlertType.HIGH_TEMPERATURE in a4)
    }

    @Test fun `throttle stores state changes immediately and skips duplicates`() {
        val a = TestData.point(T0, 50)
        assertTrue(SampleThrottle.shouldStore(null, a))
        assertFalse(SampleThrottle.shouldStore(a, a.copy(timestamp = T0 + 5_000)))
        assertTrue(SampleThrottle.shouldStore(a, TestData.point(T0 + 5_000, 50, plugged = false)))
        assertTrue(SampleThrottle.shouldStore(a, a.copy(timestamp = T0 + 20_000, levelPct = 51)))
        assertTrue(SampleThrottle.shouldStore(a, a.copy(timestamp = T0 + 61_000)))
        assertFalse(SampleThrottle.shouldStore(a, a.copy(timestamp = T0)))
    }

    @Test fun `discharge stats split screen-on and screen-off drain`() {
        val pts = listOf(
            TestData.point(T0, 80, plugged = false, screenOn = true),
            TestData.point(T0 + 15 * MIN, 77, plugged = false, screenOn = false),
            TestData.point(T0 + 30 * MIN, 77, plugged = false, screenOn = false),
            TestData.point(T0 + 45 * MIN, 76, plugged = false, screenOn = true),
            TestData.point(T0 + 60 * MIN, 74, plugged = false, screenOn = true),
        )
        val s = SessionStatsCalculator.compute(SessionType.DISCHARGE, pts)!!
        assertEquals(30 * MIN, s.screenOnMs)
        assertEquals(30 * MIN, s.screenOffMs)
        assertEquals(5f, s.screenOnDrainPct!!, 0.001f)
        assertEquals(1f, s.screenOffDrainPct!!, 0.001f)
        assertEquals(0, s.dataGaps)
    }

    @Test fun `charge stats integrate energy and ignore low-confidence current`() {
        val pts = (0..60).map { i -> TestData.point(T0 + i * MIN, 20 + i / 2, voltage = 4000, current = 2000) }
        val s = SessionStatsCalculator.compute(SessionType.CHARGE, pts)!!
        assertEquals(2000, s.avgCurrentMa)
        assertEquals(8f, s.avgPowerW!!, 0.01f)
        assertEquals(8f, s.energyWh!!, 0.05f) // 8 W for one hour
        val lowConf = pts.map { it.copy(currentConfidence = Confidence.LOW) }
        val s2 = SessionStatsCalculator.compute(SessionType.CHARGE, lowConf)!!
        assertNull(s2.avgCurrentMa)
        assertNull(s2.energyWh)
    }

    @Test fun `gaps are counted, not interpolated`() {
        val pts = listOf(TestData.point(T0, 50), TestData.point(T0 + 60 * MIN, 60))
        val s = SessionStatsCalculator.compute(SessionType.CHARGE, pts)
        assertNotNull(s)
        assertEquals(1, s!!.dataGaps)
    }
}
