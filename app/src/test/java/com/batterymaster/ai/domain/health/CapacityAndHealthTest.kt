package com.batterymaster.ai.domain.health

import com.batterymaster.ai.TestData
import com.batterymaster.ai.TestData.chargeSession
import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.model.CapacityMethod
import com.batterymaster.ai.domain.model.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CapacityAndHealthTest {

    @Test fun `wide clean charge gives accurate counter estimate`() {
        val est = CapacityEstimator.estimate(1, chargeSession(20, 90, 4500), 0)
        val counter = est.single { it.method == CapacityMethod.CHARGE_COUNTER_DELTA }
        assertTrue(counter.accepted)
        assertTrue("got ${counter.capacityMah}", abs(counter.capacityMah!! - 4500) <= 4500 * 0.03)
        assertEquals(Confidence.HIGH, counter.confidence)
    }

    @Test fun `short partial charge is rejected with a reason`() {
        val est = CapacityEstimator.estimate(1, chargeSession(70, 85, 4500), 0)
        val counter = est.single { it.method == CapacityMethod.CHARGE_COUNTER_DELTA }
        assertFalse(counter.accepted)
        assertNull(counter.capacityMah)
        assertNotNull(counter.rejectReason)
    }

    @Test fun `data gaps reject the session`() {
        val s = chargeSession(10, 80, 4000).toMutableList()
        // Remove 20 consecutive minutes in the middle.
        val gapped = s.filterIndexed { i, _ -> i !in 30..50 }
        val counter = CapacityEstimator.estimate(1, gapped, 0).single { it.method == CapacityMethod.CHARGE_COUNTER_DELTA }
        assertFalse(counter.accepted)
    }

    @Test fun `counter derived purely from level is detected`() {
        // counter = level * 45 exactly → no independent measurement
        val pts = (20..90).map { lvl -> TestData.point(TestData.T0 + (lvl - 20) * TestData.MIN, lvl, counter = lvl * 45) }
        val counter = CapacityEstimator.estimate(1, pts, 0).single { it.method == CapacityMethod.CHARGE_COUNTER_DELTA }
        assertFalse(counter.accepted)
        assertTrue(counter.rejectReason!!.contains("derived"))
    }

    @Test fun `current integration works when counter is missing`() {
        val est = CapacityEstimator.estimate(1, chargeSession(20, 80, 5000, withCounter = false, withCurrent = true), 0)
        val integ = est.single { it.method == CapacityMethod.CURRENT_INTEGRATION }
        assertTrue(integ.accepted)
        assertTrue("got ${integ.capacityMah}", abs(integ.capacityMah!! - 5000) <= 5000 * 0.05)
    }

    @Test fun `no estimate rows when device has neither counter nor current`() {
        assertTrue(CapacityEstimator.estimate(1, chargeSession(20, 80, 5000, withCounter = false), 0).isEmpty())
    }

    private fun est(t: Long, mah: Int, accepted: Boolean = true) =
        CapacityEstimate(t, t, CapacityMethod.CHARGE_COUNTER_DELTA, if (accepted) mah else null, 60, accepted, null, Confidence.HIGH)

    @Test fun `health uses median of accepted estimates and design capacity`() {
        val now = TestData.T0
        val list = listOf(4400, 4500, 4450, 4480, 4420, 9999).mapIndexed { i, v ->
            est(now - i * 86_400_000L, v, accepted = v != 9999)
        }
        val h = HealthCalculator.summarize(list, 5000, now)
        assertEquals(4450, h.estimatedCapacity!!.value)
        assertEquals(89f, h.healthPct!!.value, 0.5f)
        assertEquals(Confidence.HIGH, h.confidence)
        assertEquals(5, h.usedEstimates)
    }

    @Test fun `no health percentage without design capacity`() {
        val h = HealthCalculator.summarize(listOf(est(TestData.T0, 4000)), null, TestData.T0)
        assertNotNull(h.estimatedCapacity)
        assertNull(h.healthPct)
        assertEquals(Confidence.LOW, h.confidence)
    }

    @Test fun `empty history yields explicit insufficient state`() {
        val h = HealthCalculator.summarize(emptyList(), 5000, TestData.T0)
        assertNull(h.estimatedCapacity)
        assertEquals(Confidence.NONE, h.confidence)
    }
}
