package com.batterymaster.ai.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.batterymaster.ai.di.ReceiverEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restores user-enabled monitoring after reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = ReceiverEntryPoint.get(context).settings()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SampleWorker.schedule(context)
                if (settings.current().monitoringEnabled) {
                    runCatching { MonitoringService.start(context) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
