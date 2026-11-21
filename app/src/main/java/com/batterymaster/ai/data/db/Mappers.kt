package com.batterymaster.ai.data.db

import com.batterymaster.ai.domain.model.BatterySnapshot
import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.model.CapacityMethod
import com.batterymaster.ai.domain.model.ChargeStatus
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.PlugType
import com.batterymaster.ai.domain.model.SamplePoint
import com.batterymaster.ai.domain.model.SessionSummary
import com.batterymaster.ai.domain.model.SessionType
import com.batterymaster.ai.domain.model.measurementOrNull
import com.batterymaster.ai.domain.model.valueOrNull

inline fun <reified E : Enum<E>> safeEnum(name: String?, fallback: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback

fun SampleEntity.toPoint() = SamplePoint(
    timestamp = timestamp,
    levelPct = levelPct,
    status = safeEnum(status, ChargeStatus.UNKNOWN),
    plug = safeEnum(plug, PlugType.UNKNOWN),
    temperatureC = temperatureC,
    voltageMv = voltageMv,
    currentMa = currentMa,
    currentConfidence = currentConfidence?.let { safeEnum(it, Confidence.NONE) },
    chargeCounterMah = chargeCounterMah,
    screenOn = screenOn,
)

fun BatterySnapshot.toPoint() = SamplePoint(
    timestamp = timestamp,
    levelPct = levelPct,
    status = status,
    plug = plug,
    temperatureC = temperatureC.valueOrNull,
    voltageMv = voltageMv.valueOrNull,
    currentMa = currentMa.valueOrNull,
    currentConfidence = currentMa.measurementOrNull?.confidence,
    chargeCounterMah = chargeCounterMah.valueOrNull,
    screenOn = screenOn,
)

fun BatterySnapshot.toEntity(source: String) = SampleEntity(
    timestamp = timestamp,
    levelPct = levelPct,
    status = status.name,
    plug = plug.name,
    temperatureC = temperatureC.valueOrNull,
    voltageMv = voltageMv.valueOrNull,
    currentMa = currentMa.valueOrNull,
    currentConfidence = currentMa.measurementOrNull?.confidence?.name,
    chargeCounterMah = chargeCounterMah.valueOrNull,
    screenOn = screenOn,
    source = source,
)

fun SessionEntity.toSummary() = SessionSummary(
    id = id,
    type = safeEnum(type, SessionType.DISCHARGE),
    startTime = startTime,
    endTime = endTime,
    lastSampleTime = lastSampleTime,
    startLevel = startLevel,
    endLevel = endLevel,
    plug = safeEnum(plug, PlugType.UNKNOWN),
    startTempC = startTempC,
    maxTempC = maxTempC,
    avgTempC = avgTempC,
    avgCurrentMa = avgCurrentMa,
    maxCurrentMa = maxCurrentMa,
    avgVoltageMv = avgVoltageMv,
    avgPowerW = avgPowerW,
    energyWh = energyWh,
    screenOnMs = screenOnMs,
    screenOffMs = screenOffMs,
    screenOnDrainPct = screenOnDrainPct,
    screenOffDrainPct = screenOffDrainPct,
    sampleCount = sampleCount,
    dataGaps = dataGaps,
)

fun CapacityEstimateEntity.toDomain() = CapacityEstimate(
    timestamp = timestamp,
    sessionId = sessionId,
    method = safeEnum(method, CapacityMethod.CHARGE_COUNTER_DELTA),
    capacityMah = capacityMah,
    levelSpan = levelSpan,
    accepted = accepted,
    rejectReason = rejectReason,
    confidence = safeEnum(confidence, Confidence.NONE),
)

fun CapacityEstimate.toEntity() = CapacityEstimateEntity(
    timestamp = timestamp,
    sessionId = sessionId,
    method = method.name,
    capacityMah = capacityMah,
    levelSpan = levelSpan,
    accepted = accepted,
    rejectReason = rejectReason,
    confidence = confidence.name,
)
