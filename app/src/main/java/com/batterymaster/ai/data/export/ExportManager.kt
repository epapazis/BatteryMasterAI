package com.batterymaster.ai.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.batterymaster.ai.data.repo.HistoryRepository
import com.batterymaster.ai.domain.doctor.DoctorReport
import com.batterymaster.ai.domain.health.HealthSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val history: HistoryRepository,
) {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)

    /** Streams all samples to a user-chosen document in pages (no full-table load). */
    suspend fun exportSamplesCsv(uri: Uri): Int = withContext(Dispatchers.IO) {
        var count = 0
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
            w.appendLine("timestamp_iso,timestamp_ms,level_pct,status,plug,temperature_c,voltage_mv,current_ma,current_confidence,charge_counter_mah,screen_on,source")
            var after = 0L
            while (true) {
                val page = history.samplePage(after, 2_000)
                if (page.isEmpty()) break
                for (s in page) {
                    w.appendLine(
                        listOf(
                            iso.format(Date(s.timestamp)), s.timestamp, s.levelPct, s.status, s.plug,
                            s.temperatureC ?: "", s.voltageMv ?: "", s.currentMa ?: "", s.currentConfidence ?: "",
                            s.chargeCounterMah ?: "", s.screenOn, s.source,
                        ).joinToString(","),
                    )
                }
                count += page.size
                after = page.last().id
            }
        } ?: error("Could not open the selected file")
        count
    }

    suspend fun exportSessionsCsv(uri: Uri): Int = withContext(Dispatchers.IO) {
        val all = history.allSessions()
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
            w.appendLine("type,start_iso,end_iso,duration_min,start_level,end_level,rate_pct_per_h,plug,max_temp_c,avg_temp_c,avg_current_ma,max_current_ma,avg_voltage_mv,avg_power_w,energy_wh,screen_on_min,screen_off_min,screen_on_drain_pct,screen_off_drain_pct,samples,data_gaps")
            for (s in all) {
                w.appendLine(
                    listOf(
                        s.type, iso.format(Date(s.startTime)), s.endTime?.let { iso.format(Date(it)) } ?: "open",
                        s.durationMs / 60_000, s.startLevel, s.endLevel, s.ratePctPerHour?.let { "%.2f".format(Locale.US, it) } ?: "",
                        s.plug, s.maxTempC ?: "", s.avgTempC ?: "", s.avgCurrentMa ?: "", s.maxCurrentMa ?: "",
                        s.avgVoltageMv ?: "", s.avgPowerW ?: "", s.energyWh ?: "",
                        s.screenOnMs?.div(60_000) ?: "", s.screenOffMs?.div(60_000) ?: "",
                        s.screenOnDrainPct ?: "", s.screenOffDrainPct ?: "", s.sampleCount, s.dataGaps,
                    ).joinToString(","),
                )
            }
        } ?: error("Could not open the selected file")
        all.size
    }

    suspend fun writePng(bitmap: Bitmap, name: String): File = withContext(Dispatchers.IO) {
        val file = File(exportDir(), "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file
    }

    suspend fun writeReportPdf(report: DoctorReport, health: HealthSummary, deviceLine: String): File = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val pageW = 595
        val pageH = 842
        val margin = 48f
        val title = Paint().apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val h2 = Paint().apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val body = Paint().apply { textSize = 10.5f; isAntiAlias = true }
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        var y = margin

        fun ensure(space: Float) {
            if (y + space > pageH - margin) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                y = margin
            }
        }
        fun line(text: String, paint: Paint, gap: Float = 4f) {
            for (l in wrap(text, paint, pageW - 2 * margin)) {
                ensure(paint.textSize + gap)
                y += paint.textSize
                page.canvas.drawText(l, margin, y, paint)
                y += gap
            }
        }

        line("Battery Master AI — Battery report", title, 8f)
        line("Generated ${SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(report.generatedAt))} · $deviceLine", body, 12f)
        line("Overall condition: ${report.condition}" + (report.score?.let { " · Score $it/100" } ?: ""), h2)
        line("Report confidence: ${report.confidence.label}", body)
        line(report.dataSummary, body, 12f)

        line("Capacity", h2)
        health.estimatedCapacity?.let { line("Estimated full-charge capacity: ${it.value} mAh (${it.confidence.label} confidence)", body) }
        health.healthPct?.let { line("Estimated health: ${"%.0f".format(it.value)}% of ${health.designCapacityMah} mAh design", body) }
        line(health.explanation, body, 12f)

        line("Findings", h2)
        for (f in report.findings) {
            line("• [${f.severity}] ${f.title}", h2.apply { textSize = 11f }, 2f)
            line(f.detail, body, 2f)
            f.evidence.forEach { line("   – $it", body, 2f) }
            f.recommendation?.let { line("   What you can do: $it", body, 2f) }
            line("   Confidence: ${f.confidence.label}" + if (f.penalty > 0) " · −${f.penalty} points" else "", body, 8f)
        }
        h2.textSize = 13f
        line("How the score works", h2)
        DoctorReport.SCORING_CRITERIA.forEach { line("• $it", body, 2f) }
        doc.finishPage(page)

        val file = File(exportDir(), "battery-report.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        file
    }

    fun shareIntent(file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }

    private fun exportDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (word in text.split(' ')) {
            val candidate = if (cur.isEmpty()) word else "$cur $word"
            if (paint.measureText(candidate) <= width) {
                cur = StringBuilder(candidate)
            } else {
                if (cur.isNotEmpty()) out += cur.toString()
                cur = StringBuilder(word)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out.ifEmpty { listOf("") }
    }
}
