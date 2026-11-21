package com.batterymaster.ai.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
import com.batterymaster.ai.MainActivity
import com.batterymaster.ai.R
import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.valueOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdater @Inject constructor(@ApplicationContext private val context: Context) {
    private var lastKey: String? = null

    /** Pushes new data to all widgets, skipping redundant updates. */
    fun update(s: BatterySnapshot) {
        val key = "${s.levelPct}|${s.status}|${s.plug}|${s.temperatureC.valueOrNull?.toInt()}"
        if (key == lastKey) return
        val mgr = AppWidgetManager.getInstance(context) ?: return
        val ids = mgr.getAppWidgetIds(ComponentName(context, BatteryWidgetProvider::class.java))
        if (ids.isEmpty()) return
        lastKey = key
        val views = render(context, s)
        ids.forEach { mgr.updateAppWidget(it, views) }
    }

    companion object {
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
            val pi = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, pi)
            return v
        }
    }
}
