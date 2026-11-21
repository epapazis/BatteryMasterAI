package com.batterymaster.ai.domain

import com.batterymaster.ai.TestData.T0
import com.batterymaster.ai.domain.assistant.AssistantContext
import com.batterymaster.ai.domain.assistant.BatteryAssistant
import com.batterymaster.ai.domain.doctor.BatteryDoctor
import com.batterymaster.ai.domain.doctor.DoctorInput
import com.batterymaster.ai.domain.doctor.Severity
import com.batterymaster.ai.domain.doctor.TemperatureStats
import com.batterymaster.ai.domain.health.HealthCalculator
import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.model.CapacityMethod
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.HealthStatus
import com.batterymaster.ai.domain.model.PlugType
import com.batterymaster.ai.domain.model.SessionSummary
import com.batterymaster.ai.domain.model.SessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoctorAndAssistantTest {
    private val day = 86_400_000L

    private fun charge(i: Int, start: Int, end: Int, maxT: Float = 35f) = SessionSummary(
        id = i.toLong(), type = SessionType.CHARGE, startTime = T0 - i * day, endTime = T0 - i * day + 3_600_000L,
        lastSampleTime = T0 - i * day + 3_600_000L, startLevel = start, endLevel = end, plug = PlugType.AC,
        startTempC = 30f, maxTempC = maxT, avgTempC = 33f, avgCurrentMa = 2000, maxCurrentMa = 3000, avgVoltageMv = 4100,
        avgPowerW = 8f, energyWh = 8f, screenOnMs = null, screenOffMs = null, screenOnDrainPct = null,
        screenOffDrainPct = null, sampleCount = 60, dataGaps = 0,
    )

    private fun health(capacities: List<Int>, design: Int?) = HealthCalculator.summarize(
        capacities.mapIndexed { i, c ->
            CapacityEstimate(T0 - i * day, i.toLong(), CapacityMethod.CHARGE_COUNTER_DELTA, c, 60, true, null, Confidence.HIGH)
        },
        design, T0,
    )

    @Test fun `no score before 24 hours of data`() {
        val r = BatteryDoctor.examine(DoctorInput(T0, HealthStatus.GOOD, health(emptyList(), null), emptyList(),
            TemperatureStats(0, 0, null, null), 3_600_000L))
        assertNull(r.score)
        assertEquals(Confidence.NONE, r.confidence)
    }

    @Test fun `healthy battery with good habits scores 100`() {
        val sessions = (1..6).map { charge(it, 30, 80) }
        val r = BatteryDoctor.examine(DoctorInput(T0, HealthStatus.GOOD, health(List(5) { 4800 }, 5000), sessions,
            TemperatureStats(500, 2, 38f, 31f), 10 * day))
        assertEquals(100, r.score)
        assertEquals("Good", r.condition)
        assertTrue(r.findings.none { it.severity == Severity.WARNING })
    }

    @Test fun `penalties are transparent and additive`() {
        val sessions = (1..6).map { charge(it, 5, 100, maxT = 44f) }
        val r = BatteryDoctor.examine(DoctorInput(T0, HealthStatus.GOOD, health(List(5) { 3600 }, 5000), sessions,
            TemperatureStats(500, 100, 46f, 39f), 10 * day))
        // 72% health (−20), warm 20% (−15), hot charging (−8), full charges (−5), deep discharges (−5)
        assertEquals(100 - 20 - 15 - 8 - 5 - 5, r.score)
        assertEquals(r.findings.sumOf { it.penalty }, 100 - r.score!!)
    }

    @Test fun `system-reported fault is surfaced as a warning`() {
        val r = BatteryDoctor.examine(DoctorInput(T0, HealthStatus.OVERHEAT, health(emptyList(), null), emptyList(),
            TemperatureStats(0, 0, null, null), 2 * day))
        assertTrue(r.findings.any { it.severity == Severity.WARNING && it.category == "System status" })
    }

    @Test fun `assistant routes questions and never invents missing data`() {
        val ctx = AssistantContext(null, health(emptyList(), null), null, null, null, 80, "Notification-only protection")
        val h = BatteryAssistant.answer("How healthy is my battery?", ctx)
        assertEquals(Confidence.NONE, h.confidence)
        assertTrue(h.detected.contains("isn't a reliable"))
        val lim = BatteryAssistant.answer("What charging limit should I use?", ctx)
        assertTrue(lim.detected.contains("80%"))
        val last = BatteryAssistant.answer("How was my last charge?", ctx)
        assertTrue(last.detected.contains("No completed charging session"))
        val other = BatteryAssistant.answer("What's the weather?", ctx)
        assertEquals(Confidence.NONE, other.confidence)
    }
}
