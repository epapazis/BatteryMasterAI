package com.batterymaster.ai.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.batterymaster.ai.MainActivity
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.data.protection.ChargingControlEngine
import com.batterymaster.ai.data.repo.BatteryRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings tile showing level, charging state and protection status. Tapping opens the
 * app. It offers no on/off toggle because the app can't actually switch charging off.
 */
@AndroidEntryPoint
class BatteryTileService : TileService() {
    @Inject lateinit var battery: BatteryRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var protection: ChargingControlEngine

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            val snap = runCatching { battery.readNow() }.getOrNull()
            val target = runCatching { settings.current().chargeTargetPct }.getOrDefault(80)
            val tile = qsTile ?: return@launch
            if (snap == null) {
                tile.label = "Battery"
                tile.state = Tile.STATE_UNAVAILABLE
            } else {
                tile.label = "${snap.levelPct}% · ${snap.status.label}"
                tile.state = if (snap.isPluggedIn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Target $target% · ${if (protection.status().method.canStopCharging) "limit" else "alert"}"
                }
            }
            tile.updateTile()
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
