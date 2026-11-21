package com.batterymaster.ai.data.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.batterymaster.ai.domain.model.ChargeStatus
import com.batterymaster.ai.domain.model.HealthStatus
import com.batterymaster.ai.domain.model.PlugType
import com.batterymaster.ai.domain.model.RawBatteryReading
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ONLY class that talks to BatteryManager / battery broadcasts. Event-driven via the
 * sticky ACTION_BATTERY_CHANGED broadcast; optional polling only refreshes properties that
 * Android does not broadcast (instantaneous current, charge counter).
 */
@Singleton
class BatteryDataSource @Inject constructor(@ApplicationContext private val context: Context) {

    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    /** One-shot read from the sticky broadcast. Cheap; no receiver stays registered. */
    fun readNow(): RawBatteryReading? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        return parse(intent)
    }

    /**
     * Emits on every battery broadcast and screen on/off change. If [pollMs] is set, also
     * re-reads BatteryManager properties at that interval while collected.
     */
    fun observe(pollMs: Long? = null): Flow<RawBatteryReading> = callbackFlow {
        var lastBattery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        lastBattery?.let { trySend(parse(it)) }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        lastBattery = intent
                        trySend(parse(intent))
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> lastBattery?.let { trySend(parse(it)) }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // These are protected system broadcasts: no other app can send them.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        val poller = pollMs?.let { interval ->
            launch {
                while (isActive) {
                    delay(interval)
                    lastBattery?.let { trySend(parse(it)) }
                }
            }
        }
        awaitClose {
            poller?.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun parse(i: Intent): RawBatteryReading {
        val bm = batteryManager
        val currentNow = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeIf { it != Int.MIN_VALUE }
        val currentAvg = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            ?.takeIf { it != Int.MIN_VALUE && it != 0 }
        val counter = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it != Int.MIN_VALUE }
        val chargeTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            bm?.computeChargeTimeRemaining()?.takeIf { it > 0 }
        } else {
            null
        }
        val cycles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }
        } else {
            null
        }
        return RawBatteryReading(
            timestamp = System.currentTimeMillis(),
            level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
            status = mapStatus(i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)),
            health = mapHealth(i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)),
            plug = mapPlug(i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)),
            voltageRaw = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            temperatureRaw = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            technology = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() },
            present = i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true),
            currentNowRaw = currentNow,
            currentAverageRaw = currentAvg,
            chargeCounterRaw = counter,
            cycleCount = cycles,
            chargeTimeRemainingMs = chargeTime,
            screenOn = powerManager?.isInteractive ?: true,
        )
    }

    private fun mapStatus(v: Int) = when (v) {
        BatteryManager.BATTERY_STATUS_CHARGING -> ChargeStatus.CHARGING
        BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeStatus.DISCHARGING
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeStatus.NOT_CHARGING
        BatteryManager.BATTERY_STATUS_FULL -> ChargeStatus.FULL
        else -> ChargeStatus.UNKNOWN
    }

    private fun mapHealth(v: Int) = when (v) {
        BatteryManager.BATTERY_HEALTH_GOOD -> HealthStatus.GOOD
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> HealthStatus.OVERHEAT
        BatteryManager.BATTERY_HEALTH_DEAD -> HealthStatus.DEAD
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> HealthStatus.OVER_VOLTAGE
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> HealthStatus.FAILURE
        BatteryManager.BATTERY_HEALTH_COLD -> HealthStatus.COLD
        else -> HealthStatus.UNKNOWN
    }

    private fun mapPlug(v: Int) = when {
        v == 0 -> PlugType.NONE
        v and BatteryManager.BATTERY_PLUGGED_AC != 0 -> PlugType.AC
        v and BatteryManager.BATTERY_PLUGGED_USB != 0 -> PlugType.USB
        v and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> PlugType.WIRELESS
        v and DOCK_FLAG != 0 -> PlugType.DOCK
        else -> PlugType.UNKNOWN
    }

    private companion object {
        /** BatteryManager.BATTERY_PLUGGED_DOCK (API 33); value inlined for older SDKs. */
        const val DOCK_FLAG = 8
    }
}
