package com.batterymaster.ai.data.recording

import androidx.room.withTransaction
import com.batterymaster.ai.data.db.AppDatabase
import com.batterymaster.ai.data.db.CapacityDao
import com.batterymaster.ai.data.db.SampleDao
import com.batterymaster.ai.data.db.SessionDao
import com.batterymaster.ai.data.db.SessionEntity
import com.batterymaster.ai.data.db.safeEnum
import com.batterymaster.ai.data.db.toEntity
import com.batterymaster.ai.data.db.toPoint
import com.batterymaster.ai.domain.health.CapacityEstimator
import com.batterymaster.ai.domain.model.SamplePoint
import com.batterymaster.ai.domain.model.SessionType
import com.batterymaster.ai.domain.session.SessionStatsCalculator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Splits the sample stream into charging (plugged in) and discharging (unplugged)
 * sessions. State lives in the database, so it survives process death and reboots.
 */
@Singleton
class SessionManager @Inject constructor(
    private val db: AppDatabase,
    private val sessions: SessionDao,
    private val samples: SampleDao,
    private val capacity: CapacityDao,
) {
    companion object {
        /** Readings this far apart split a session: we can't know what happened in between. */
        const val MAX_SESSION_GAP_MS = 6 * 3_600_000L
        const val MIN_SESSION_MS = 60_000L
    }

    suspend fun onSample(p: SamplePoint): SessionEntity = db.withTransaction {
        val desired = if (p.pluggedIn) SessionType.CHARGE else SessionType.DISCHARGE
        var open = sessions.open()
        if (open != null && (open.type != desired.name || p.timestamp - open.lastSampleTime > MAX_SESSION_GAP_MS)) {
            close(open, open.lastSampleTime, p.timestamp)
            open = null
        }
        if (open == null) {
            val fresh = SessionEntity(
                type = desired.name, startTime = p.timestamp, endTime = null, lastSampleTime = p.timestamp,
                startLevel = p.levelPct, endLevel = p.levelPct, plug = p.plug.name,
                startTempC = p.temperatureC, maxTempC = p.temperatureC, avgTempC = p.temperatureC,
                avgCurrentMa = null, maxCurrentMa = null, avgVoltageMv = null, avgPowerW = null, energyWh = null,
                screenOnMs = null, screenOffMs = null, screenOnDrainPct = null, screenOffDrainPct = null,
                sampleCount = 1, dataGaps = 0,
            )
            fresh.copy(id = sessions.insert(fresh))
        } else {
            if (p.timestamp <= open.lastSampleTime) return@withTransaction open
            val gap = p.timestamp - open.lastSampleTime > SessionStatsCalculator.GAP_MS
            val maxT = listOfNotNull(open.maxTempC, p.temperatureC).maxOrNull()
            val updated = open.copy(
                lastSampleTime = p.timestamp,
                endLevel = p.levelPct,
                maxTempC = maxT,
                sampleCount = open.sampleCount + 1,
                dataGaps = open.dataGaps + if (gap) 1 else 0,
            )
            sessions.update(updated)
            updated
        }
    }

    private suspend fun close(s: SessionEntity, endTime: Long, now: Long) {
        val type = safeEnum(s.type, SessionType.DISCHARGE)
        if (endTime - s.startTime < MIN_SESSION_MS && s.endLevel == s.startLevel) {
            sessions.delete(s.id) // Plug/unplug blip; not a real session.
            return
        }
        val pts = samples.between(s.startTime, endTime).map { it.toPoint() }
        val stats = SessionStatsCalculator.compute(type, pts)
        val closed = if (stats == null) s.copy(endTime = endTime) else s.copy(
            endTime = endTime,
            endLevel = stats.endLevel,
            startTempC = stats.startTempC ?: s.startTempC,
            maxTempC = stats.maxTempC,
            avgTempC = stats.avgTempC,
            avgCurrentMa = stats.avgCurrentMa,
            maxCurrentMa = stats.maxCurrentMa,
            avgVoltageMv = stats.avgVoltageMv,
            avgPowerW = stats.avgPowerW,
            energyWh = stats.energyWh,
            screenOnMs = stats.screenOnMs,
            screenOffMs = stats.screenOffMs,
            screenOnDrainPct = stats.screenOnDrainPct,
            screenOffDrainPct = stats.screenOffDrainPct,
            sampleCount = stats.sampleCount,
            dataGaps = stats.dataGaps,
        )
        sessions.update(closed)
        if (type == SessionType.CHARGE) {
            val estimates = CapacityEstimator.estimate(s.id, pts, now)
            if (estimates.isNotEmpty()) capacity.insertAll(estimates.map { it.toEntity() })
        }
    }
}
