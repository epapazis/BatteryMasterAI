package com.batterymaster.ai.data.recording

import com.batterymaster.ai.data.db.ProtectionDao
import com.batterymaster.ai.data.db.ProtectionEventEntity
import com.batterymaster.ai.data.db.SampleDao
import com.batterymaster.ai.data.db.SessionDao
import com.batterymaster.ai.data.db.toEntity
import com.batterymaster.ai.data.db.toPoint
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.data.protection.ChargingControlEngine
import com.batterymaster.ai.data.usage.AppUsageSource
import com.batterymaster.ai.domain.alerts.AlertInput
import com.batterymaster.ai.domain.alerts.AlertRules
import com.batterymaster.ai.domain.alerts.AlertType
import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.valueOrNull
import com.batterymaster.ai.domain.protection.ControlResult
import com.batterymaster.ai.domain.recording.SampleThrottle
import com.batterymaster.ai.notifications.Notifier
import com.batterymaster.ai.widget.WidgetUpdater
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single write path for battery history: throttle → persist → update session →
 * evaluate alerts → refresh widget. Called by the foreground service and by WorkManager.
 */
@Singleton
class BatteryRecorder @Inject constructor(
    private val samples: SampleDao,
    private val sessionDao: SessionDao,
    private val sessionManager: SessionManager,
    private val protectionDao: ProtectionDao,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
    private val protection: ChargingControlEngine,
    private val widgets: WidgetUpdater,
    private val usage: AppUsageSource,
) {
    private val mutex = Mutex()

    suspend fun record(s: BatterySnapshot, source: String) = mutex.withLock {
        val app = if (s.screenOn) runCatching { usage.currentForegroundApp(s.timestamp) }.getOrNull() else null
        val point = s.toPoint().copy(foregroundApp = app)
        val last = samples.latest()?.toPoint()
        val session = if (SampleThrottle.shouldStore(last, point)) {
            samples.insert(s.toEntity(source, app))
            sessionManager.onSample(point)
        } else {
            sessionDao.open() ?: sessionManager.onSample(point)
        }
        evaluateAlerts(s, session.id)
        widgets.update(s)
    }

    private suspend fun evaluateAlerts(s: BatterySnapshot, sessionId: Long) {
        val cfg = settings.current()
        val state = settings.loadAlertState()
        val (alerts, newState) = AlertRules.evaluate(
            cfg.alertConfig, state,
            AlertInput(s.timestamp, sessionId, s.isPluggedIn, s.levelPct, s.status, s.temperatureC.valueOrNull),
        )
        if (newState != state) settings.saveAlertState(newState)
        if (!s.isPluggedIn) notifier.cancelTargetAlarm()
        val quiet = cfg.isQuietHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        for (a in alerts) {
            // During quiet hours only the charging-target alarm may make a sound.
            notifier.showAlert(a, s, cfg.chargeTargetPct, cfg.tempAlertC, cfg.alertSound && !quiet, cfg.targetAlarm)
            if (a == AlertType.TARGET_REACHED) {
                val controller = protection.active()
                val outcome = when (val r = controller?.applyLimit(cfg.chargeTargetPct)) {
                    ControlResult.Confirmed -> "Charging stopped (confirmed by device)"
                    is ControlResult.NotSupported -> "Alert shown; charging continues (${r.reason})"
                    is ControlResult.Failed -> "Control failed: ${r.reason}; alert shown"
                    null -> "No alert possible: notifications disabled"
                }
                protectionDao.insert(
                    ProtectionEventEntity(
                        timestamp = s.timestamp, sessionId = sessionId, targetPct = cfg.chargeTargetPct,
                        levelPct = s.levelPct, method = protection.status().method.name, outcome = outcome,
                    ),
                )
            }
        }
    }
}
