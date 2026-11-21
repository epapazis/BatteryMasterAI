package com.batterymaster.ai.domain.reliability

import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.DataClass
import com.batterymaster.ai.domain.model.Measurement
import com.batterymaster.ai.domain.model.Reading
import kotlin.math.abs
import kotlin.math.max

enum class CurrentUnit { UNKNOWN, MICROAMPS, MILLIAMPS }

enum class SignMode(val description: String) {
    UNKNOWN("Not yet determined"),
    STANDARD("Positive while charging (Android standard)"),
    INVERTED("Negative while charging (manufacturer-inverted)"),
    MAGNITUDE_ONLY("Magnitude only; direction taken from plug state"),
}

/**
 * Learned facts about how this specific device reports BATTERY_PROPERTY_CURRENT_NOW.
 * Android documents microamps with positive = charging, but many devices use milliamps
 * and/or the opposite sign. We learn both from observations instead of assuming.
 */
data class CurrentCalibration(
    val unit: CurrentUnit = CurrentUnit.UNKNOWN,
    val maxAbsRaw: Long = 0,
    val chargingObservations: Int = 0,
    val chargingPos: Int = 0,
    val chargingNeg: Int = 0,
    val dischargingPos: Int = 0,
    val dischargingNeg: Int = 0,
    val everNonZero: Boolean = false,
) {
    val signMode: SignMode get() = CurrentNormalizer.decideSign(this)
    val signConfirmed: Boolean
        get() = chargingPos + chargingNeg >= CurrentNormalizer.MIN_VOTES &&
            dischargingPos + dischargingNeg >= CurrentNormalizer.MIN_VOTES
}

object CurrentNormalizer {
    /** No phone battery sustains 20 A, so any raw magnitude above this must be microamps. */
    const val MICRO_THRESHOLD = 20_000L
    const val MIN_VOTES = 10
    private const val DOMINANCE = 0.8
    private const val MAX_PLAUSIBLE_MA = 20_000

    fun observe(
        cal: CurrentCalibration,
        raw: Int?,
        chargingWhilePlugged: Boolean,
        unplugged: Boolean,
    ): CurrentCalibration {
        if (raw == null || raw == 0) return cal
        val absRaw = abs(raw.toLong())
        var c = cal.copy(maxAbsRaw = max(cal.maxAbsRaw, absRaw), everNonZero = true)
        if (chargingWhilePlugged) {
            c = c.copy(
                chargingObservations = c.chargingObservations + 1,
                chargingPos = c.chargingPos + if (raw > 0) 1 else 0,
                chargingNeg = c.chargingNeg + if (raw < 0) 1 else 0,
            )
        } else if (unplugged) {
            c = c.copy(
                dischargingPos = c.dischargingPos + if (raw > 0) 1 else 0,
                dischargingNeg = c.dischargingNeg + if (raw < 0) 1 else 0,
            )
        }
        val unit = when {
            c.maxAbsRaw >= MICRO_THRESHOLD -> CurrentUnit.MICROAMPS
            // Real charging currents are >= ~100 mA, i.e. >= 100 000 uA. If ten charging
            // readings never exceeded 20 000, the device must be reporting milliamps.
            c.chargingObservations >= MIN_VOTES -> CurrentUnit.MILLIAMPS
            else -> CurrentUnit.UNKNOWN
        }
        return c.copy(unit = unit)
    }

    fun decideSign(c: CurrentCalibration): SignMode {
        val cTotal = c.chargingPos + c.chargingNeg
        val dTotal = c.dischargingPos + c.dischargingNeg
        val cPos = cTotal >= MIN_VOTES && c.chargingPos >= DOMINANCE * cTotal
        val cNeg = cTotal >= MIN_VOTES && c.chargingNeg >= DOMINANCE * cTotal
        val dPos = dTotal >= MIN_VOTES && c.dischargingPos >= DOMINANCE * dTotal
        val dNeg = dTotal >= MIN_VOTES && c.dischargingNeg >= DOMINANCE * dTotal
        return when {
            cPos && dNeg -> SignMode.STANDARD
            cNeg && dPos -> SignMode.INVERTED
            (cPos && dPos) || (cNeg && dNeg) -> SignMode.MAGNITUDE_ONLY
            // Tentative decisions from one side only.
            cPos && dTotal < MIN_VOTES -> SignMode.STANDARD
            dNeg && cTotal < MIN_VOTES -> SignMode.STANDARD
            cNeg && dTotal < MIN_VOTES -> SignMode.INVERTED
            else -> SignMode.UNKNOWN
        }
    }

    fun normalize(
        cal: CurrentCalibration,
        raw: Int?,
        pluggedIn: Boolean,
        timestamp: Long,
        source: String,
    ): Reading<Int> {
        if (raw == null) return Reading.Unavailable("Not exposed by this device")
        if (raw == 0 && !cal.everNonZero) {
            return Reading.Unavailable("Device reports 0 — current sensing likely not supported")
        }
        if (cal.unit == CurrentUnit.UNKNOWN) {
            return Reading.Unavailable("Calibrating — needs a few readings while charging to learn this device's units")
        }
        val ma = if (cal.unit == CurrentUnit.MICROAMPS) raw / 1000 else raw
        val mode = cal.signMode
        val signed = when (mode) {
            SignMode.STANDARD, SignMode.UNKNOWN -> ma
            SignMode.INVERTED -> -ma
            SignMode.MAGNITUDE_ONLY -> if (pluggedIn) abs(ma) else -abs(ma)
        }
        if (abs(signed) > MAX_PLAUSIBLE_MA) return Reading.Unavailable("Implausible value rejected ($signed mA)")
        val confidence = when {
            mode == SignMode.UNKNOWN -> Confidence.LOW
            mode == SignMode.MAGNITUDE_ONLY -> Confidence.MEDIUM
            cal.signConfirmed -> Confidence.HIGH
            else -> Confidence.MEDIUM
        }
        val note = when (mode) {
            SignMode.UNKNOWN -> "Direction not yet verified on this device"
            SignMode.MAGNITUDE_ONLY -> "Direction inferred from plug state"
            SignMode.INVERTED -> "Device reports inverted sign; corrected"
            SignMode.STANDARD -> null
        }
        return Reading.Available(
            Measurement(signed, "mA", timestamp, source, DataClass.DIRECT_MEASUREMENT, confidence, note),
        )
    }
}

object ValueNormalizers {
    private const val SRC_BROADCAST = "ACTION_BATTERY_CHANGED"
    private const val SRC_COUNTER = "BatteryManager.CHARGE_COUNTER"

    /** EXTRA_VOLTAGE is documented in millivolts; some devices report volts or microvolts. */
    fun voltage(raw: Int?, timestamp: Long): Reading<Int> {
        if (raw == null || raw <= 0) return Reading.Unavailable("Not reported by this device")
        val (mv, note) = when {
            raw < 10 -> raw * 1000 to "Device reports whole volts; precision limited"
            raw > 100_000 -> raw / 1000 to "Device reports microvolts; converted"
            else -> raw to null
        }
        if (mv !in 2_500..5_500) return Reading.Unavailable("Implausible value rejected ($raw)")
        val conf = if (raw < 10) Confidence.LOW else Confidence.HIGH
        return Reading.Available(Measurement(mv, "mV", timestamp, SRC_BROADCAST, DataClass.DIRECT_MEASUREMENT, conf, note))
    }

    /** EXTRA_TEMPERATURE is tenths of a degree Celsius. */
    fun temperature(raw: Int?, timestamp: Long): Reading<Float> {
        if (raw == null) return Reading.Unavailable("Not reported by this device")
        val c = raw / 10f
        if (c < -30f || c > 90f) return Reading.Unavailable("Implausible value rejected ($raw)")
        return Reading.Available(Measurement(c, "°C", timestamp, SRC_BROADCAST, DataClass.DIRECT_MEASUREMENT))
    }

    /**
     * Charge counter is the charge currently stored (documented as microampere-hours).
     * It is NOT design capacity nor full-charge capacity. Units are checked against the
     * level: counter / level must imply a plausible full capacity.
     */
    fun chargeCounter(raw: Int?, levelPct: Int, timestamp: Long): Reading<Int> {
        if (raw == null || raw <= 0) return Reading.Unavailable("Not exposed by this device")
        if (levelPct < 3) return Reading.Unavailable("Level too low to validate units")
        val impliedFull = raw.toDouble() * 100.0 / levelPct
        return when {
            impliedFull in 500_000.0..40_000_000.0 -> Reading.Available(
                Measurement(raw / 1000, "mAh", timestamp, SRC_COUNTER, DataClass.SYSTEM_REPORTED, Confidence.HIGH,
                    "Charge currently stored — not total capacity"),
            )
            impliedFull in 500.0..40_000.0 -> Reading.Available(
                Measurement(raw, "mAh", timestamp, SRC_COUNTER, DataClass.SYSTEM_REPORTED, Confidence.MEDIUM,
                    "Device reports mAh instead of µAh; converted"),
            )
            else -> Reading.Unavailable("Implausible value rejected ($raw)")
        }
    }

    /** Power flowing into/out of the battery, only when both inputs are trustworthy. */
    fun power(voltage: Reading<Int>, current: Reading<Int>, timestamp: Long): Reading<Float> {
        val v = (voltage as? Reading.Available<Int>)?.measurement
            ?: return Reading.Unavailable("Needs voltage, which is unavailable")
        val i = (current as? Reading.Available<Int>)?.measurement
            ?: return Reading.Unavailable("Needs current, which is unavailable")
        if (i.confidence == Confidence.LOW || i.confidence == Confidence.NONE) {
            return Reading.Unavailable("Current direction not yet verified")
        }
        val watts = v.value.toFloat() * i.value.toFloat() / 1_000_000f
        return Reading.Available(
            Measurement(watts, "W", timestamp, "voltage × current", DataClass.CALCULATED,
                v.confidence.min(i.confidence), "Battery-side power, not charger output"),
        )
    }
}
