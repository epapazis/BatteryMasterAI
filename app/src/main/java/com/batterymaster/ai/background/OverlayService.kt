package com.batterymaster.ai.background

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.batterymaster.ai.data.prefs.AppSettings
import com.batterymaster.ai.data.prefs.OverlayColor
import com.batterymaster.ai.data.prefs.OverlayCorner
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.data.source.BatteryDataSource
import com.batterymaster.ai.data.source.ReliabilityEngine
import com.batterymaster.ai.domain.model.Reading
import com.batterymaster.ai.domain.model.valueOrNull
import com.batterymaster.ai.notifications.Notifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

/**
 * Small always-on-top readout of the live battery current (mA), optionally with watts and
 * temperature. Touches pass through it unless "Move by dragging" is on. It refreshes once a
 * second and pauses while the screen is off, so its own battery cost stays tiny.
 */
@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var source: BatteryDataSource
    @Inject lateinit var engine: ReliabilityEngine
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var notifier: Notifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var current = AppSettings()
    private var started = false
    private var dragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, Notifier.ID_OVERLAY, notifier.overlayNotification(), type)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start overlay in foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            addView()
            scope.launch {
                settings.settings.collect {
                    current = it
                    if (!dragging) applyStyle(it)
                }
            }
            scope.launch { refreshLoop() }
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addView() {
        val pad = dp(4)
        val tv = TextView(this).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(OverlayColor.GREEN.argb)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(pad, pad, pad, pad)
            text = "…"
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(8)
            y = dp(8)
            alpha = PASS_THROUGH_ALPHA
        }
        tv.setOnTouchListener(DragListener())
        try {
            windowManager().addView(tv, lp)
            view = tv
            params = lp
        } catch (e: Exception) {
            Log.w(TAG, "Overlay permission missing or revoked", e)
            stopSelf()
        }
    }

    private fun applyStyle(s: AppSettings) {
        val tv = view ?: return
        val lp = params ?: return
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.overlaySize.sp)
        if (s.overlayX >= 0 && s.overlayY >= 0) {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = s.overlayX
            lp.y = s.overlayY
        } else {
            lp.gravity = when (s.overlayCorner) {
                OverlayCorner.TOP_LEFT -> Gravity.TOP or Gravity.START
                OverlayCorner.TOP_RIGHT -> Gravity.TOP or Gravity.END
                OverlayCorner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
                OverlayCorner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            }
            lp.x = dp(8)
            // Keep clear of the status bar at the top.
            lp.y = if (s.overlayCorner == OverlayCorner.TOP_LEFT || s.overlayCorner == OverlayCorner.TOP_RIGHT) dp(32) else dp(8)
        }
        if (s.overlayDraggable) {
            lp.flags = BASE_FLAGS
            lp.alpha = 1f
        } else {
            // Android 12+ only lets touches pass through overlays at or below 0.8 opacity.
            lp.flags = BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            lp.alpha = PASS_THROUGH_ALPHA
        }
        runCatching { windowManager().updateViewLayout(tv, lp) }
    }

    private inner class DragListener : android.view.View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
            val lp = params ?: return false
            if (!current.overlayDraggable) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    // Switch to absolute top-left coordinates, keeping the current spot.
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.x = loc[0]
                    lp.y = loc[1]
                    runCatching { windowManager().updateViewLayout(v, lp) }
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (e.rawX - downX)).toInt().coerceAtLeast(0)
                    lp.y = (startY + (e.rawY - downY)).toInt().coerceAtLeast(0)
                    runCatching { windowManager().updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    val x = lp.x
                    val y = lp.y
                    scope.launch { settings.update { it.copy(overlayX = x, overlayY = y) } }
                }
            }
            return true
        }
    }

    private suspend fun refreshLoop() {
        val pm = getSystemService(PowerManager::class.java)
        while (scope.isActive) {
            if (pm?.isInteractive != false) {
                render()
                delay(1_000)
            } else {
                delay(3_000)
            }
        }
    }

    private suspend fun render() {
        val tv = view ?: return
        val raw = source.readNow()
        if (raw == null) {
            tv.text = "—"
            return
        }
        val snap = engine.process(raw)
        val s = current
        val ma = (snap.currentMa as? Reading.Available<Int>)?.measurement?.value
        val parts = ArrayList<String>(3)
        parts += when {
            ma == null -> "…"
            ma > 0 -> "+$ma"
            else -> ma.toString()
        } + if (s.overlayShowWatts || s.overlayShowTemp) " mA" else ""
        if (s.overlayShowWatts) {
            snap.powerW.valueOrNull?.let { parts += String.format(Locale.US, "%.1f W", abs(it)) }
        }
        if (s.overlayShowTemp) {
            snap.temperatureC.valueOrNull?.let { parts += String.format(Locale.US, "%.1f°C", it) }
        }
        tv.text = parts.joinToString(" · ")
        val color = if (s.overlayColor == OverlayColor.AUTO) {
            if ((ma ?: 0) >= 0) OverlayColor.GREEN.argb else ORANGE
        } else {
            s.overlayColor.argb
        }
        tv.setTextColor(color)
    }

    private fun windowManager() = getSystemService(WINDOW_SERVICE) as WindowManager

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        view?.let { runCatching { windowManager().removeView(it) } }
        view = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val PASS_THROUGH_ALPHA = 0.8f
        private val ORANGE = Color.rgb(255, 145, 0)
        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
