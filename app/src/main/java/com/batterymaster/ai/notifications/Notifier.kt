package com.batterymaster.ai.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.batterymaster.ai.MainActivity
import com.batterymaster.ai.R
import com.batterymaster.ai.domain.alerts.AlertType
import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.valueOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        const val CH_MONITOR = "monitoring"
        const val CH_ALERTS = "alerts"
        const val CH_ALERTS_QUIET = "alerts_quiet"
        const val ID_MONITOR = 1
        private const val ID_TARGET = 10
        private const val ID_FULL = 11
        private const val ID_LOW = 12
        private const val ID_TEMP = 13
    }

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val monitor = NotificationChannel(CH_MONITOR, context.getString(R.string.channel_monitoring), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.channel_monitoring_desc)
            setShowBadge(false)
        }
        val alerts = NotificationChannel(CH_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_alerts_desc)
            enableVibration(true)
        }
        val quiet = NotificationChannel(CH_ALERTS_QUIET, context.getString(R.string.channel_alerts_quiet), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.channel_alerts_quiet_desc)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannels(listOf(monitor, alerts, quiet))
    }

    fun canNotify(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun monitoringNotification(s: BatterySnapshot?): Notification {
        val text = s?.let {
            buildString {
                append("${it.levelPct}% · ${it.status.label}")
                it.temperatureC.valueOrNull?.let { t -> append(" · ${"%.1f".format(t)} °C") }
                it.currentMa.valueOrNull?.let { c -> append(" · $c mA") }
            }
        } ?: context.getString(R.string.monitoring_starting)
        return NotificationCompat.Builder(context, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(context.getString(R.string.monitoring_active))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .build()
    }

    fun updateMonitoring(s: BatterySnapshot) {
        if (!canNotify()) return
        notifySafely(ID_MONITOR, monitoringNotification(s))
    }

    fun showAlert(type: AlertType, s: BatterySnapshot, targetPct: Int, tempLimit: Float, withSound: Boolean) {
        if (!canNotify()) return
        val (id, title, text) = when (type) {
            AlertType.TARGET_REACHED -> Triple(ID_TARGET, context.getString(R.string.alert_target_title, targetPct),
                context.getString(R.string.alert_target_text))
            AlertType.FULLY_CHARGED -> Triple(ID_FULL, context.getString(R.string.alert_full_title),
                context.getString(R.string.alert_full_text))
            AlertType.LOW_BATTERY -> Triple(ID_LOW, context.getString(R.string.alert_low_title, s.levelPct),
                context.getString(R.string.alert_low_text))
            AlertType.HIGH_TEMPERATURE -> Triple(ID_TEMP,
                context.getString(R.string.alert_temp_title, s.temperatureC.valueOrNull ?: tempLimit),
                context.getString(R.string.alert_temp_text))
        }
        val n = NotificationCompat.Builder(context, if (withSound) CH_ALERTS else CH_ALERTS_QUIET)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(if (withSound) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent())
            .build()
        notifySafely(id, n)
    }

    @SuppressLint("MissingPermission") // checked in canNotify()
    private fun notifySafely(id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; nothing else to do.
        }
    }
}
