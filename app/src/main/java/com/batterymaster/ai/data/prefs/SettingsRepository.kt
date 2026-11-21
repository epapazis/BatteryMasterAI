package com.batterymaster.ai.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.batterymaster.ai.data.db.safeEnum
import com.batterymaster.ai.domain.alerts.AlertConfig
import com.batterymaster.ai.domain.alerts.AlertState
import com.batterymaster.ai.domain.reliability.CurrentCalibration
import com.batterymaster.ai.domain.reliability.CurrentUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val monitoringEnabled: Boolean = false,
    val chargeTargetPct: Int = 80,
    val targetAlertEnabled: Boolean = true,
    val fullAlertEnabled: Boolean = false,
    val lowAlertEnabled: Boolean = true,
    val lowAlertPct: Int = 15,
    val tempAlertEnabled: Boolean = true,
    val tempAlertC: Float = 42f,
    val alertSound: Boolean = true,
    val designCapacityMah: Int = 0,
    val retentionDays: Int = 365,
) {
    val alertConfig: AlertConfig
        get() = AlertConfig(
            targetEnabled = targetAlertEnabled,
            targetPct = chargeTargetPct,
            fullEnabled = fullAlertEnabled,
            lowEnabled = lowAlertEnabled,
            lowPct = lowAlertPct,
            tempEnabled = tempAlertEnabled,
            tempLimitC = tempAlertC,
        )
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val store get() = context.dataStore

    private object K {
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val monitoring = booleanPreferencesKey("monitoring_enabled")
        val target = intPreferencesKey("charge_target")
        val targetAlert = booleanPreferencesKey("target_alert")
        val fullAlert = booleanPreferencesKey("full_alert")
        val lowAlert = booleanPreferencesKey("low_alert")
        val lowPct = intPreferencesKey("low_pct")
        val tempAlert = booleanPreferencesKey("temp_alert")
        val tempC = floatPreferencesKey("temp_c")
        val sound = booleanPreferencesKey("alert_sound")
        val design = intPreferencesKey("design_capacity")
        val retention = intPreferencesKey("retention_days")

        // Learned device calibration
        val calUnit = stringPreferencesKey("cal_unit")
        val calMax = longPreferencesKey("cal_max_abs")
        val calChargeObs = intPreferencesKey("cal_charge_obs")
        val calCPos = intPreferencesKey("cal_c_pos")
        val calCNeg = intPreferencesKey("cal_c_neg")
        val calDPos = intPreferencesKey("cal_d_pos")
        val calDNeg = intPreferencesKey("cal_d_neg")
        val calNonZero = booleanPreferencesKey("cal_nonzero")

        // Alert de-duplication state
        val aTarget = longPreferencesKey("alert_target_session")
        val aFull = longPreferencesKey("alert_full_session")
        val aLow = longPreferencesKey("alert_low_session")
        val aTempArmed = booleanPreferencesKey("alert_temp_armed")
        val aTempAt = longPreferencesKey("alert_temp_at")

        val lastCleanup = longPreferencesKey("last_cleanup")
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = safeEnum(p[K.theme], ThemeMode.SYSTEM),
            dynamicColor = p[K.dynamic] ?: d.dynamicColor,
            monitoringEnabled = p[K.monitoring] ?: d.monitoringEnabled,
            chargeTargetPct = p[K.target] ?: d.chargeTargetPct,
            targetAlertEnabled = p[K.targetAlert] ?: d.targetAlertEnabled,
            fullAlertEnabled = p[K.fullAlert] ?: d.fullAlertEnabled,
            lowAlertEnabled = p[K.lowAlert] ?: d.lowAlertEnabled,
            lowAlertPct = p[K.lowPct] ?: d.lowAlertPct,
            tempAlertEnabled = p[K.tempAlert] ?: d.tempAlertEnabled,
            tempAlertC = p[K.tempC] ?: d.tempAlertC,
            alertSound = p[K.sound] ?: d.alertSound,
            designCapacityMah = p[K.design] ?: d.designCapacityMah,
            retentionDays = p[K.retention] ?: d.retentionDays,
        )
    }

    val settings: Flow<AppSettings> = store.data.map { read(it) }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val cur = read(p)
            val n = transform(cur)
            p[K.theme] = n.themeMode.name
            p[K.dynamic] = n.dynamicColor
            p[K.monitoring] = n.monitoringEnabled
            p[K.target] = n.chargeTargetPct.coerceIn(50, 100)
            p[K.targetAlert] = n.targetAlertEnabled
            p[K.fullAlert] = n.fullAlertEnabled
            p[K.lowAlert] = n.lowAlertEnabled
            p[K.lowPct] = n.lowAlertPct.coerceIn(5, 50)
            p[K.tempAlert] = n.tempAlertEnabled
            p[K.tempC] = n.tempAlertC.coerceIn(30f, 60f)
            p[K.sound] = n.alertSound
            p[K.design] = n.designCapacityMah.coerceIn(0, 30_000)
            p[K.retention] = n.retentionDays.coerceAtLeast(0)
        }
    }

    suspend fun loadCalibration(): CurrentCalibration {
        val p = store.data.first()
        return CurrentCalibration(
            unit = safeEnum(p[K.calUnit], CurrentUnit.UNKNOWN),
            maxAbsRaw = p[K.calMax] ?: 0,
            chargingObservations = p[K.calChargeObs] ?: 0,
            chargingPos = p[K.calCPos] ?: 0,
            chargingNeg = p[K.calCNeg] ?: 0,
            dischargingPos = p[K.calDPos] ?: 0,
            dischargingNeg = p[K.calDNeg] ?: 0,
            everNonZero = p[K.calNonZero] ?: false,
        )
    }

    suspend fun saveCalibration(c: CurrentCalibration) {
        store.edit { p ->
            p[K.calUnit] = c.unit.name
            p[K.calMax] = c.maxAbsRaw
            p[K.calChargeObs] = c.chargingObservations
            p[K.calCPos] = c.chargingPos
            p[K.calCNeg] = c.chargingNeg
            p[K.calDPos] = c.dischargingPos
            p[K.calDNeg] = c.dischargingNeg
            p[K.calNonZero] = c.everNonZero
        }
    }

    suspend fun resetCalibration() = saveCalibration(CurrentCalibration())

    suspend fun loadAlertState(): AlertState {
        val p = store.data.first()
        return AlertState(
            targetNotifiedSession = p[K.aTarget] ?: -1,
            fullNotifiedSession = p[K.aFull] ?: -1,
            lowNotifiedSession = p[K.aLow] ?: -1,
            tempArmed = p[K.aTempArmed] ?: true,
            lastTempAlertAt = p[K.aTempAt] ?: 0,
        )
    }

    suspend fun saveAlertState(s: AlertState) {
        store.edit { p ->
            p[K.aTarget] = s.targetNotifiedSession
            p[K.aFull] = s.fullNotifiedSession
            p[K.aLow] = s.lowNotifiedSession
            p[K.aTempArmed] = s.tempArmed
            p[K.aTempAt] = s.lastTempAlertAt
        }
    }

    suspend fun lastCleanup(): Long = store.data.first()[K.lastCleanup] ?: 0L

    suspend fun setLastCleanup(t: Long) {
        store.edit { it[K.lastCleanup] = t }
    }
}
