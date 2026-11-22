package com.batterymaster.ai.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.batterymaster.ai.R
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.data.repo.HistoryRepository
import com.batterymaster.ai.domain.insights.DailySummaryCalculator
import com.batterymaster.ai.notifications.Notifier
import com.batterymaster.ai.ui.util.Format
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Morning notification summarising yesterday's battery use. Runs once a day around 09:00. */
@HiltWorker
class DailySummaryWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        if (!settings.current().dailySummary) return@runCatching Result.success()
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val from = today - 86_400_000L
        val stats = DailySummaryCalculator.compute(
            history.samples(from, today), history.sessionsSince(from - 86_400_000L), from, today,
        ) ?: return@runCatching Result.success()
        val text = buildString {
            append(appContext.getString(R.string.summary_line, Format.durationMs(stats.screenOnMs), stats.usedPct, stats.charges))
            stats.avgDrawMa?.let { append(" · ").append(appContext.getString(R.string.summary_avg, it)) }
            stats.maxTempC?.let { append(" · ").append(appContext.getString(R.string.summary_max_temp, it)) }
            stats.sleep?.let {
                append("\n").append(
                    appContext.getString(R.string.summary_sleep, Format.durationMs(it.durationMs), it.ratePctPerHour),
                )
            }
        }
        notifier.showDailySummary(appContext.getString(R.string.summary_title), text)
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        private const val NAME = "daily_summary"

        fun schedule(context: Context) {
            val now = Calendar.getInstance()
            val next = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
            }
            val delay = next.timeInMillis - now.timeInMillis
            val req = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
