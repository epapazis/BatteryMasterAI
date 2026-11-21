package com.batterymaster.ai.domain.reliability

import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.DataClass
import com.batterymaster.ai.domain.model.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizersTest {

    private fun calibrate(
        chargingRaw: Int?, dischargingRaw: Int?, nCharge: Int = 12, nDischarge: Int = 12,
    ): CurrentCalibration {
        var c = CurrentCalibration()
        repeat(nCharge) { c = CurrentNormalizer.observe(c, chargingRaw, chargingWhilePlugged = true, unplugged = false) }
        repeat(nDischarge) { c = CurrentNormalizer.observe(c, dischargingRaw, chargingWhilePlugged = false, unplugged = true) }
        return c
    }

    @Test fun `microamps with standard sign are detected and converted`() {
        val c = calibrate(1_500_000, -400_000)
        assertEquals(CurrentUnit.MICROAMPS, c.unit)
        assertEquals(SignMode.STANDARD, c.signMode)
        val r = CurrentNormalizer.normalize(c, 1_500_000, true, 0, "t") as Reading.Available
        assertEquals(1500, r.measurement.value)
        assertEquals(Confidence.HIGH, r.measurement.confidence)
        assertEquals(DataClass.DIRECT_MEASUREMENT, r.measurement.dataClass)
    }

    @Test fun `milliamp devices are detected after enough charging readings`() {
        val c = calibrate(1500, -400)
        assertEquals(CurrentUnit.MILLIAMPS, c.unit)
        val r = CurrentNormalizer.normalize(c, -400, false, 0, "t") as Reading.Available
        assertEquals(-400, r.measurement.value)
    }

    @Test fun `inverted sign is corrected`() {
        val c = calibrate(-1_500_000, 400_000)
        assertEquals(SignMode.INVERTED, c.signMode)
        val r = CurrentNormalizer.normalize(c, -1_500_000, true, 0, "t") as Reading.Available
        assertEquals(1500, r.measurement.value)
    }

    @Test fun `magnitude-only devices take direction from plug state`() {
        val c = calibrate(1_500_000, 400_000)
        assertEquals(SignMode.MAGNITUDE_ONLY, c.signMode)
        val unplugged = CurrentNormalizer.normalize(c, 400_000, false, 0, "t") as Reading.Available
        assertEquals(-400, unplugged.measurement.value)
        assertEquals(Confidence.MEDIUM, unplugged.measurement.confidence)
    }

    @Test fun `unknown unit is reported unavailable instead of guessed`() {
        val c = calibrate(null, -300, nCharge = 0, nDischarge = 3)
        assertEquals(CurrentUnit.UNKNOWN, c.unit)
        assertTrue(CurrentNormalizer.normalize(c, -300, false, 0, "t") is Reading.Unavailable)
    }

    @Test fun `zero from a device that never reported nonzero is unavailable`() {
        assertTrue(CurrentNormalizer.normalize(CurrentCalibration(), 0, false, 0, "t") is Reading.Unavailable)
        assertTrue(CurrentNormalizer.normalize(CurrentCalibration(), null, false, 0, "t") is Reading.Unavailable)
    }

    @Test fun `voltage units are normalised and implausible values rejected`() {
        assertEquals(4123, (ValueNormalizers.voltage(4123, 0) as Reading.Available).measurement.value)
        assertEquals(4123, (ValueNormalizers.voltage(4_123_000, 0) as Reading.Available).measurement.value)
        assertEquals(4000, (ValueNormalizers.voltage(4, 0) as Reading.Available).measurement.value)
        assertTrue(ValueNormalizers.voltage(0, 0) is Reading.Unavailable)
        assertTrue(ValueNormalizers.voltage(9_000, 0) is Reading.Unavailable)
    }

    @Test fun `temperature is tenths of a degree`() {
        assertEquals(31.5f, (ValueNormalizers.temperature(315, 0) as Reading.Available).measurement.value, 0.001f)
        assertTrue(ValueNormalizers.temperature(2000, 0) is Reading.Unavailable)
    }

    @Test fun `charge counter units are validated against the level`() {
        // 2 500 000 uAh at 50% implies a 5000 mAh battery: microamp-hours.
        val micro = ValueNormalizers.chargeCounter(2_500_000, 50, 0) as Reading.Available
        assertEquals(2500, micro.measurement.value)
        assertEquals(DataClass.SYSTEM_REPORTED, micro.measurement.dataClass)
        // 2500 at 50% implies 5000: device reports mAh.
        val milli = ValueNormalizers.chargeCounter(2500, 50, 0) as Reading.Available
        assertEquals(2500, milli.measurement.value)
        assertEquals(Confidence.MEDIUM, milli.measurement.confidence)
        assertTrue(ValueNormalizers.chargeCounter(50, 50, 0) is Reading.Unavailable)
    }

    @Test fun `power needs both trustworthy inputs`() {
        val v = ValueNormalizers.voltage(4000, 0)
        val c = calibrate(1_500_000, -400_000)
        val i = CurrentNormalizer.normalize(c, 2_000_000, true, 0, "t")
        val p = ValueNormalizers.power(v, i, 0) as Reading.Available
        assertEquals(8.0f, p.measurement.value, 0.001f)
        assertEquals(DataClass.CALCULATED, p.measurement.dataClass)
        assertTrue(ValueNormalizers.power(v, Reading.Unavailable("x"), 0) is Reading.Unavailable)
    }
}
