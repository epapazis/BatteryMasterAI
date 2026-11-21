package com.batterymaster.ai.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun duration(minutes: Double): String {
        if (minutes.isNaN() || minutes < 0) return "—"
        val m = minutes.toLong()
        val h = m / 60
        val d = h / 24
        return when {
            d >= 1 -> "${d}d ${h % 24}h"
            h >= 1 -> "${h}h ${m % 60}m"
            else -> "${m}m"
        }
    }

    fun durationMs(ms: Long): String = duration(ms / 60_000.0)

    fun time(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    fun dateTime(ts: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ts))

    fun date(ts: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ts))

    fun axisTime(ts: Long, spanMs: Long): String {
        val pattern = when {
            spanMs <= 2 * 86_400_000L -> "HH:mm"
            spanMs <= 60 * 86_400_000L -> "d MMM"
            else -> "MMM yy"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
    }

    fun age(ts: Long, now: Long = System.currentTimeMillis()): String {
        val s = (now - ts) / 1000
        return when {
            s < 10 -> "just now"
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> dateTime(ts)
        }
    }

    fun num(v: Double, decimals: Int = 1): String = "%.${decimals}f".format(v)
    fun num(v: Float, decimals: Int = 1): String = "%.${decimals}f".format(v)
}
