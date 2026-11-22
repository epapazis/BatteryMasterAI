package com.batterymaster.ai.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.batterymaster.ai.di.ReceiverEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Base for all home-screen widgets: when Android asks for an update (e.g. a widget was just
 * added), read the battery now and refresh every widget. Ongoing updates come from the
 * recorder each time a sample is stored.
 */
abstract class BaseBatteryWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val entry = ReceiverEntryPoint.get(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val snap = runCatching { entry.battery().readNow() }.getOrNull()
                runCatching { entry.widgets().update(snap) }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Level, status and temperature. */
class BatteryWidgetProvider : BaseBatteryWidgetProvider()

/** Live current in mA. */
class CurrentWidgetProvider : BaseBatteryWidgetProvider()

/** Battery temperature. */
class TempWidgetProvider : BaseBatteryWidgetProvider()

/** Estimated health (or capacity if no design capacity is set). */
class HealthWidgetProvider : BaseBatteryWidgetProvider()
