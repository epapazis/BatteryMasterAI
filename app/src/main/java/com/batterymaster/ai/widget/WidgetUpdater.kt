package com.batterymaster.ai.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
import com.batterymaster.ai.MainActivity
import com.batterymaster.ai.R
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.data.repo.HistoryRepository
import com.batterymaster.ai.domain.health.HealthCalculator
import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.valueOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes the latest real reading to every home-screen widget. Called by the recorder each
 * time a sample is stored, so widgets are as fresh as the recorded data (Android limits how
 * often widgets can refresh on their own).
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
) {
    private var lastKey: String? = null
    private var healthText: String? = null
    private var healthAt = 0L

    suspend fun update(s: BatterySnapshot?) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        val time = s?.let { DateFormat.getTimeFormat(context).format(Date(it.timestamp)) } ?: ""

        val mainIds = ids(mgr, BatteryWidgetProvider::class.java)
        val key = s?.let { "${it.levelPct}|${it.status}|${it.plug}|${it.temperatureC.valueOrNull?.toInt()}|$time" }
        if (mainIds.isNotEmpty() && key != lastKey) {
            lastKey = key
            val v = render(context, s)
            mainIds.forEach { mgr.updateAppWidget(it, v) }
        }

        ids(mgr, CurrentWidgetProvider::class.java).takeIf { it.isNotEmpty() }?.let { idList ->
            val ma = s?.currentMa?.valueOrNull
            val v = metric(context, context.getString(R.string.current), ma?.let { "%+d mA".format(Locale.US, it) } ?: "—", time)
            idList.forEach { mgr.updateAppWidget(it, v) }
        }

        ids(mgr, TempWidgetProvider::class.java).takeIf { it.isNotEmpty() }?.let { idList ->
            val t = s?.temperatureC?.valueOrNull
            val v = metric(context, context.getString(R.string.temperature), t?.let { "%.1f °C".format(Locale.US, it) } ?: "—", time)
            idList.forEach { mgr.updateAppWidget(it, v) }
        }

        ids(mgr, HealthWidgetProvider::class.java).takeIf { it.isNotEmpty() }?.let { idList ->
            val now = System.currentTimeMillis()
            if (healthText == null || now - healthAt > 30 * 60_000L) {
                val h = HealthCalculator.summarize(history.allEstimates(), settings.current().designCapacityMah, now)
                healthText = h.healthPct?.let { "%.0f%%".format(Locale.US, it.value) }
                    ?: h.estimatedCapacity?.let { "${it.value} mAh" }
                    ?: "—"
                healthAt = now
            }
            val v = metric(context, context.getString(R.string.health), healthText ?: "—", time)
            idList.forEach { mgr.updateAppWidget(it, v) }
        }
    }

    private fun ids(mgr: AppWidgetManager, cls: Class<out AppWidgetProvider>): IntArray =
        mgr.getAppWidgetIds(ComponentName(context, cls))

    companion object {
        private fun openApp(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
            context, requestCode, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun render(context: Context, s: BatterySnapshot?): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_battery)
            if (s == null) {
                v.setTextViewText(R.id.widget_level, "--")
                v.setTextViewText(R.id.widget_status, context.getString(R.string.widget_no_data))
                v.setTextViewText(R.id.widget_detail, "")
                v.setTextViewText(R.id.widget_updated, "")
            } else {
                v.setTextViewText(R.id.widget_level, "${s.levelPct}%")
                v.setTextViewText(R.id.widget_status, s.status.label)
                val t = s.temperatureC.valueOrNull
                v.setTextViewText(R.id.widget_detail, if (t != null) "%.1f °C".format(t) else context.getString(R.string.temperature_unavailable))
                val time = DateFormat.getTimeFormat(context).format(Date(s.timestamp))
                v.setTextViewText(R.id.widget_updated, context.getString(R.string.widget_updated, time))
            }
            v.setOnClickPendingIntent(R.id.widget_root, openApp(context, 1))
            return v
        }

        fun metric(context: Context, title: String, value: String, time: String): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_metric)
            v.setTextViewText(R.id.metric_title, title)
            v.setTextViewText(R.id.metric_value, value)
            v.setTextViewText(R.id.metric_updated, if (time.isEmpty()) "" else context.getString(R.string.widget_updated, time))
            v.setOnClickPendingIntent(R.id.metric_root, openApp(context, 3))
            return v
        }
    }
}
