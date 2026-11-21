package com.batterymaster.ai.data.protection

import com.batterymaster.ai.domain.protection.ChargingController
import com.batterymaster.ai.domain.protection.ControlResult
import com.batterymaster.ai.domain.protection.ProtectionMethod
import com.batterymaster.ai.domain.protection.ProtectionStatus
import com.batterymaster.ai.notifications.Notifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alerts the user to unplug. This is the only method available to a regular, non-system
 * Android app: there is no public API to interrupt charging.
 */
class NotificationOnlyController @Inject constructor(private val notifier: Notifier) : ChargingController {
    override val method = ProtectionMethod.NOTIFICATION_ONLY
    override fun isAvailable(): Boolean = notifier.canNotify()
    override suspend fun applyLimit(targetPct: Int): ControlResult =
        ControlResult.NotSupported("Charging can't be stopped by this app; an alert is shown instead.")
}

/**
 * Picks the strongest available protection method. Additional controllers (a verified
 * manufacturer SDK, or explicit opt-in root control) can be added to [controllers] in
 * priority order without touching the rest of the app. None are bundled in this build
 * because no documented, verifiable vendor API is available to integrate against.
 */
@Singleton
class ChargingControlEngine @Inject constructor(
    notificationOnly: NotificationOnlyController,
) {
    private val controllers: List<ChargingController> = listOf(notificationOnly)

    fun active(): ChargingController? = controllers.firstOrNull { it.isAvailable() }

    fun status(): ProtectionStatus {
        val c = active()
        return when (c?.method) {
            null -> ProtectionStatus(
                ProtectionMethod.UNSUPPORTED,
                "Notifications are turned off, so the app can't alert you at your charging target. Turn them on to enable protection.",
            )
            ProtectionMethod.NOTIFICATION_ONLY -> ProtectionStatus(
                ProtectionMethod.NOTIFICATION_ONLY,
                "Android doesn't allow regular apps to stop charging. When your battery reaches the target you'll get an alert to unplug. " +
                    "Some phones have their own charging limit in system battery settings, which does stop charging.",
            )
            else -> ProtectionStatus(c!!.method, c.method.label)
        }
    }
}
