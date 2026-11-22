package com.batterymaster.ai.data.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which app is on screen, via UsageStatsManager. Needs the user to grant "Usage access";
 * every method degrades to null/empty without it. Nothing leaves the device.
 */
@Singleton
class AppUsageSource @Inject constructor(@ApplicationContext private val context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val pm = context.packageManager
    private var lastPackage: String? = null
    private var lastQuery = 0L
    private val labels = HashMap<String, String>()
    private val icons = HashMap<String, Bitmap?>()

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Package most recently brought to the foreground, or null if unknown / no access. */
    @Synchronized
    fun currentForegroundApp(now: Long): String? {
        val manager = usm ?: return null
        if (!hasAccess()) return null
        val from = if (lastQuery == 0L) now - 30 * 60_000L else maxOf(lastQuery - 5_000L, now - 6 * 3_600_000L)
        runCatching {
            val events = manager.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == RESUMED) lastPackage = e.packageName
            }
        }
        lastQuery = now
        return lastPackage
    }

    /** Foreground time per package between [from] and [to], as reported by Android. */
    fun foregroundTimes(from: Long, to: Long): Map<String, Long> {
        val manager = usm ?: return emptyMap()
        if (!hasAccess()) return emptyMap()
        return runCatching {
            manager.queryAndAggregateUsageStats(from, to)
                .mapValues { it.value.totalTimeInForeground }
                .filterValues { it > 0 }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    @Synchronized
    fun icon(pkg: String): Bitmap? = icons.getOrPut(pkg) {
        runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()
    }

    private companion object {
        /** UsageEvents.Event.ACTIVITY_RESUMED (API 29) == MOVE_TO_FOREGROUND (older), value 1. */
        const val RESUMED = 1
    }
}
