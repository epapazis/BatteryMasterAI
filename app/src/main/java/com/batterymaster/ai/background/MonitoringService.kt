package com.batterymaster.ai.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.batterymaster.ai.data.recording.BatteryRecorder
import com.batterymaster.ai.data.source.BatteryDataSource
import com.batterymaster.ai.data.source.ReliabilityEngine
import com.batterymaster.ai.notifications.Notifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Optional, user-enabled continuous monitoring. Without it, WorkManager still records a
 * sample about every 15 minutes, but charging sessions and target alerts are far less
 * precise. Event-driven: wakes on battery broadcasts, plus a 60 s refresh for current.
 */
@AndroidEntryPoint
class MonitoringService : Service() {

    @Inject lateinit var source: BatteryDataSource
    @Inject lateinit var engine: ReliabilityEngine
    @Inject lateinit var recorder: BatteryRecorder
    @Inject lateinit var notifier: Notifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastNotificationUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, Notifier.ID_MONITOR, notifier.monitoringNotification(null), type)
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from background.
            Log.w(TAG, "Could not enter foreground; falling back to periodic sampling", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (job == null) {
            job = scope.launch {
                source.observe(pollMs = POLL_MS).collect { raw ->
                    runCatching {
                        val snap = engine.process(raw)
                        recorder.record(snap, SOURCE)
                        val now = System.currentTimeMillis()
                        if (now - lastNotificationUpdate > 30_000) {
                            lastNotificationUpdate = now
                            notifier.updateMonitoring(snap)
                        }
                    }.onFailure { Log.e(TAG, "Recording failed", it) }
                }
            }
        }
        return START_STICKY
    }

    private fun stopSelfCleanly() {
        job?.cancel()
        job = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitoringService"
        const val ACTION_STOP = "com.batterymaster.ai.STOP_MONITORING"
        const val SOURCE = "SERVICE"
        private const val POLL_MS = 30_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringService::class.java))
        }
    }
}
