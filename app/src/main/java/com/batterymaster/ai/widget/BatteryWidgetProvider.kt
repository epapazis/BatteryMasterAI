package com.batterymaster.ai.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.batterymaster.ai.di.ReceiverEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home-screen widget. Shows the latest real reading and when it was taken. It is refreshed
 * by the recorder whenever a new sample is stored (no periodic widget polling).
 */
class BatteryWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val battery = ReceiverEntryPoint.get(context).battery()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val snap = runCatching { battery.readNow() }.getOrNull()
                val views = WidgetUpdater.render(context, snap)
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }
}
