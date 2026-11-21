package com.batterymaster.ai.domain.alerts

import com.batterymaster.ai.domain.model.ChargeStatus

enum class AlertType { TARGET_REACHED, FULLY_CHARGED, LOW_BATTERY, HIGH_TEMPERATURE }

data class AlertConfig(
    val targetEnabled: Boolean,
    val targetPct: Int,
    val fullEnabled: Boolean,
    val lowEnabled: Boolean,
    val lowPct: Int,
    val tempEnabled: Boolean,
    val tempLimitC: Float,
)

/** Persisted so alerts fire once per session and are not repeated after process restarts. */
data class AlertState(
    val targetNotifiedSession: Long = -1,
    val fullNotifiedSession: Long = -1,
    val lowNotifiedSession: Long = -1,
    val tempArmed: Boolean = true,
    val lastTempAlertAt: Long = 0,
)

data class AlertInput(
    val now: Long,
    val sessionId: Long,
    val pluggedIn: Boolean,
    val levelPct: Int,
    val status: ChargeStatus,
    val temperatureC: Float?,
)

object AlertRules {
    const val TEMP_COOLDOWN_MS = 30 * 60_000L
    const val TEMP_HYSTERESIS_C = 2f

    fun evaluate(cfg: AlertConfig, state: AlertState, input: AlertInput): Pair<List<AlertType>, AlertState> {
        val alerts = ArrayList<AlertType>()
        var s = state

        if (cfg.targetEnabled && cfg.targetPct < 100 && input.pluggedIn &&
            input.levelPct >= cfg.targetPct && s.targetNotifiedSession != input.sessionId
        ) {
            alerts += AlertType.TARGET_REACHED
            s = s.copy(targetNotifiedSession = input.sessionId)
        }
        if (cfg.fullEnabled && input.pluggedIn &&
            (input.status == ChargeStatus.FULL || input.levelPct >= 100) &&
            s.fullNotifiedSession != input.sessionId
        ) {
            alerts += AlertType.FULLY_CHARGED
            s = s.copy(fullNotifiedSession = input.sessionId)
        }
        if (cfg.lowEnabled && !input.pluggedIn && input.levelPct <= cfg.lowPct &&
            s.lowNotifiedSession != input.sessionId
        ) {
            alerts += AlertType.LOW_BATTERY
            s = s.copy(lowNotifiedSession = input.sessionId)
        }
        val t = input.temperatureC
        if (cfg.tempEnabled && t != null) {
            if (t >= cfg.tempLimitC && s.tempArmed && input.now - s.lastTempAlertAt >= TEMP_COOLDOWN_MS) {
                alerts += AlertType.HIGH_TEMPERATURE
                s = s.copy(tempArmed = false, lastTempAlertAt = input.now)
            } else if (!s.tempArmed && t <= cfg.tempLimitC - TEMP_HYSTERESIS_C) {
                s = s.copy(tempArmed = true)
            }
        }
        return alerts to s
    }
}
