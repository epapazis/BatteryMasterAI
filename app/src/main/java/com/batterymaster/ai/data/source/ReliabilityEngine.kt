package com.batterymaster.ai.data.source

import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.di.ApplicationScope
import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.ChargeStatus
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.DataClass
import com.batterymaster.ai.domain.model.Measurement
import com.batterymaster.ai.domain.model.PlugType
import com.batterymaster.ai.domain.model.RawBatteryReading
import com.batterymaster.ai.domain.model.Reading
import com.batterymaster.ai.domain.reliability.CurrentCalibration
import com.batterymaster.ai.domain.reliability.CurrentNormalizer
import com.batterymaster.ai.domain.reliability.ValueNormalizers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns raw platform values into classified [BatterySnapshot]s: units checked, signs
 * corrected, implausible values rejected, every value tagged with source and confidence.
 */
@Singleton
class ReliabilityEngine @Inject constructor(
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var loaded = false
    private val _calibration = MutableStateFlow(CurrentCalibration())
    val calibration: StateFlow<CurrentCalibration> = _calibration.asStateFlow()
    private var updatesSinceSave = 0

    suspend fun process(raw: RawBatteryReading): BatterySnapshot = mutex.withLock {
        if (!loaded) {
            _calibration.value = settings.loadCalibration()
            loaded = true
        }
        val before = _calibration.value
        val chargingWhilePlugged = raw.plug != PlugType.NONE && raw.status == ChargeStatus.CHARGING
        val after = CurrentNormalizer.observe(before, raw.currentNowRaw, chargingWhilePlugged, raw.plug == PlugType.NONE)
        _calibration.value = after
        updatesSinceSave++
        if (after.unit != before.unit || after.signMode != before.signMode || updatesSinceSave >= 25) {
            updatesSinceSave = 0
            scope.launch { settings.saveCalibration(after) }
        }
        build(raw, after)
    }

    suspend fun resetCalibration() = mutex.withLock {
        _calibration.value = CurrentCalibration()
        settings.resetCalibration()
    }

    private fun build(raw: RawBatteryReading, cal: CurrentCalibration): BatterySnapshot {
        val ts = raw.timestamp
        val pct = raw.levelPct
        val voltage = ValueNormalizers.voltage(raw.voltageRaw, ts)
        val current = CurrentNormalizer.normalize(cal, raw.currentNowRaw, raw.plug != PlugType.NONE, ts, "BatteryManager.CURRENT_NOW")
        val currentAvg = CurrentNormalizer.normalize(cal, raw.currentAverageRaw, raw.plug != PlugType.NONE, ts, "BatteryManager.CURRENT_AVERAGE")
        return BatterySnapshot(
            timestamp = ts,
            level = Measurement(pct, "%", ts, "ACTION_BATTERY_CHANGED", DataClass.SYSTEM_REPORTED),
            status = raw.status,
            plug = raw.plug,
            health = raw.health,
            technology = raw.technology,
            present = raw.present,
            temperatureC = ValueNormalizers.temperature(raw.temperatureRaw, ts),
            voltageMv = voltage,
            currentMa = current,
            currentAverageMa = currentAvg,
            chargeCounterMah = ValueNormalizers.chargeCounter(raw.chargeCounterRaw, pct, ts),
            powerW = ValueNormalizers.power(voltage, current, ts),
            cycleCount = raw.cycleCount?.let {
                Reading.Available(Measurement(it, "cycles", ts, "EXTRA_CYCLE_COUNT", DataClass.SYSTEM_REPORTED))
            } ?: Reading.Unavailable("Requires Android 14+ and manufacturer support"),
            chargeTimeRemainingMs = raw.chargeTimeRemainingMs?.let {
                Reading.Available(Measurement(it, "ms", ts, "BatteryManager.computeChargeTimeRemaining", DataClass.SYSTEM_REPORTED,
                    Confidence.MEDIUM, "Android's own estimate"))
            } ?: Reading.Unavailable("Not provided by the system right now"),
            screenOn = raw.screenOn,
        )
    }
}
