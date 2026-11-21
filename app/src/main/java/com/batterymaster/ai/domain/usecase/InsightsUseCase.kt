package com.batterymaster.ai.domain.usecase

import android.os.Build
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.data.protection.ChargingControlEngine
import com.batterymaster.ai.data.repo.HistoryRepository
import com.batterymaster.ai.domain.assistant.AssistantContext
import com.batterymaster.ai.domain.compat.CapabilityDetector
import com.batterymaster.ai.domain.compat.DeviceCapabilities
import com.batterymaster.ai.domain.doctor.BatteryDoctor
import com.batterymaster.ai.domain.doctor.DoctorInput
import com.batterymaster.ai.domain.doctor.DoctorReport
import com.batterymaster.ai.domain.health.HealthCalculator
import com.batterymaster.ai.domain.health.HealthSummary
import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.SessionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Assembles the inputs that several screens need for health, doctor and assistant views. */
@Singleton
class InsightsUseCase @Inject constructor(
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
    private val protection: ChargingControlEngine,
) {
    private val day = 86_400_000L

    fun healthFlow(): Flow<HealthSummary> =
        combine(history.observeEstimates(), settings.settings) { est, s ->
            HealthCalculator.summarize(est, s.designCapacityMah, System.currentTimeMillis())
        }

    suspend fun doctorReport(snapshot: BatterySnapshot?): DoctorReport {
        val now = System.currentTimeMillis()
        val s = settings.current()
        val health = HealthCalculator.summarize(history.allEstimates(), s.designCapacityMah, now)
        return BatteryDoctor.examine(
            DoctorInput(
                now = now,
                systemHealth = snapshot?.health,
                health = health,
                sessions30d = history.sessionsSince(now - 30 * day),
                temps7d = history.temperatureStats(now - 7 * day),
                dataSpanMs = history.dataSpanMs(now),
            ),
        )
    }

    suspend fun assistantContext(snapshot: BatterySnapshot?, report: DoctorReport?): AssistantContext {
        val now = System.currentTimeMillis()
        val s = settings.current()
        val health = HealthCalculator.summarize(history.allEstimates(), s.designCapacityMah, now)
        val lastCharge = history.sessionsSince(now - 90 * day)
            .firstOrNull { it.type == SessionType.CHARGE && !it.isOpen }
        return AssistantContext(
            snapshot = snapshot,
            health = health,
            report = report,
            lastCharge = lastCharge,
            avgDrainPctPerHour7d = history.averageDrainPctPerHour(now - 7 * day),
            chargeTargetPct = s.chargeTargetPct,
            protectionDescription = protection.status().method.label,
        )
    }

    suspend fun averageDrain7d(): Float? = history.averageDrainPctPerHour(System.currentTimeMillis() - 7 * day)

    fun capabilities(snapshot: BatterySnapshot?): DeviceCapabilities = CapabilityDetector.detect(
        manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
        model = Build.MODEL,
        androidVersion = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        snapshot = snapshot,
        protection = protection.status().method,
    )
}
