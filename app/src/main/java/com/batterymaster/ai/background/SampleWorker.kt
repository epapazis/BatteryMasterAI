package com.batterymaster.ai.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.data.recording.BatteryRecorder
import com.batterymaster.ai.data.repo.BatteryRepository
import com.batterymaster.ai.data.repo.HistoryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Low-cost periodic sampling (≈ every 15 min, deferred by Doze as Android decides).
 * Also applies the data-retention policy once a day.
 */
@HiltWorker
class SampleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val battery: BatteryRepository,
    private val recorder: BatteryRecorder,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        battery.readNow()?.let { recorder.record(it, SOURCE) }
        val now = System.currentTimeMillis()
        if (now - settings.lastCleanup() > 86_400_000L) {
            history.applyRetention(settings.current().retentionDays, now)
            settings.setLastCleanup(now)
        }
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val SOURCE = "WORKER"
        private const val NAME = "battery_sample"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SampleWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
