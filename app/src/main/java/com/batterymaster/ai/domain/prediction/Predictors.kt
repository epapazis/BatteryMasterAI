package com.batterymaster.ai.domain.prediction

import com.batterymaster.ai.domain.model.CapacityEstimate
import com.batterymaster.ai.domain.model.ChargeStatus
import com.batterymaster.ai.domain.model.Confidence
import com.batterymaster.ai.domain.model.DataClass
import com.batterymaster.ai.domain.model.SamplePoint
import kotlin.math.max

data class Prediction(
    val value: Double,
    val low: Double,
    val high: Double,
    val unit: String,
    val basis: String,
    val confidence: Confidence,
    val dataClass: DataClass,
)

object RuntimePredictor {
    private const val HOUR = 3_600_000.0

    /**
     * Minutes until empty, from the slope of the battery level over the most recent unplugged
     * stretch. Falls back to the user's own historical average if recent data is too thin.
     */
    fun dischargeRemainingMinutes(
        samples: List<SamplePoint>,
        currentLevel: Int,
        now: Long,
        historicalDrainPctPerHour: Float?,
    ): Prediction? {
        val recent = trailingRun(samples, now, 90 * 60_000L) { !it.pluggedIn }
        val fit = fitLevel(recent)
        if (fit != null && recent.size >= 4) {
            val spanMin = (recent.last().timestamp - recent.first().timestamp) / 60_000.0
            val drop = recent.first().levelPct - recent.last().levelPct
            val rate = -fit.slope // percent per hour
            if (spanMin >= 20 && drop >= 2 && rate > 0.1) {
                val se = fit.slopeStdErr.takeIf { !it.isNaN() } ?: 0.0
                val fast = rate + 2 * se
                val slow = max(rate - 2 * se, 0.1)
                val conf = if (drop >= 5 && spanMin >= 45) Confidence.MEDIUM else Confidence.LOW
                return Prediction(
                    value = currentLevel / rate * 60,
                    low = currentLevel / fast * 60,
                    high = currentLevel / slow * 60,
                    unit = "min",
                    basis = "Recent drain of ${"%.1f".format(rate)}%/h over the last ${spanMin.toInt()} min; assumes similar use continues",
                    confidence = conf,
                    dataClass = DataClass.PREDICTION,
                )
            }
        }
        if (historicalDrainPctPerHour != null && historicalDrainPctPerHour > 0.1f) {
            val v = currentLevel / historicalDrainPctPerHour * 60.0
            return Prediction(
                value = v, low = v * 0.7, high = v * 1.3, unit = "min",
                basis = "Your average drain over the last 7 days (${"%.1f".format(historicalDrainPctPerHour)}%/h)",
                confidence = Confidence.LOW,
                dataClass = DataClass.ESTIMATED,
            )
        }
        return null
    }

    fun minutesToTarget(
        samples: List<SamplePoint>,
        currentLevel: Int,
        target: Int,
        now: Long,
        systemEstimateMs: Long?,
    ): Prediction? {
        if (currentLevel >= target) return null
        if (target >= 100 && systemEstimateMs != null && systemEstimateMs > 0) {
            val m = systemEstimateMs / 60_000.0
            return Prediction(m, m * 0.85, m * 1.2, "min", "Android's own charge-time estimate",
                Confidence.MEDIUM, DataClass.SYSTEM_REPORTED)
        }
        val recent = trailingRun(samples, now, 30 * 60_000L) { it.pluggedIn && it.status == ChargeStatus.CHARGING }
        val fit = fitLevel(recent) ?: return null
        val spanMin = (recent.last().timestamp - recent.first().timestamp) / 60_000.0
        val rise = recent.last().levelPct - recent.first().levelPct
        val rate = fit.slope
        if (spanMin < 10 || rise < 2 || rate <= 0.1) return null
        val minutes = (target - currentLevel) / rate * 60
        val conf = if (target > 80) Confidence.LOW else Confidence.MEDIUM
        return Prediction(
            value = minutes, low = minutes * 0.9, high = minutes * if (target > 80) 1.6 else 1.2, unit = "min",
            basis = "Current rate of ${"%.0f".format(rate)}%/h. Charging usually slows near full, so the real time is often longer.",
            confidence = conf,
            dataClass = DataClass.PREDICTION,
        )
    }

    /** Most recent contiguous run of samples matching [predicate], within [windowMs]. */
    private fun trailingRun(samples: List<SamplePoint>, now: Long, windowMs: Long, predicate: (SamplePoint) -> Boolean): List<SamplePoint> {
        val sorted = samples.filter { it.timestamp >= now - windowMs }.sortedBy { it.timestamp }
        val run = ArrayList<SamplePoint>()
        for (p in sorted.asReversed()) {
            if (!predicate(p)) break
            if (run.isNotEmpty() && run.last().timestamp - p.timestamp > 20 * 60_000L) break
            run += p
        }
        return run.asReversed()
    }

    private fun fitLevel(run: List<SamplePoint>): LinearFit? {
        if (run.size < 3) return null
        val t0 = run.first().timestamp
        return LinearRegression.fit(run.map { (it.timestamp - t0) / HOUR }, run.map { it.levelPct.toDouble() })
    }
}

data class ForecastPoint(val time: Long, val value: Double, val low: Double, val high: Double)

sealed interface CapacityForecast {
    data class Available(
        val points: List<ForecastPoint>,
        val changeMahPerYear: Double,
        val confidence: Confidence,
        val basis: String,
    ) : CapacityForecast

    data class Insufficient(val reason: String) : CapacityForecast
}

object CapacityForecaster {
    const val MIN_ESTIMATES = 6
    const val MIN_SPAN_DAYS = 30.0
    private const val DAY = 86_400_000.0

    fun forecast(all: List<CapacityEstimate>, horizonDays: Int = 180): CapacityForecast {
        val est = all.filter { it.accepted && it.capacityMah != null }.sortedBy { it.timestamp }
        if (est.size < MIN_ESTIMATES) {
            return CapacityForecast.Insufficient("Needs at least $MIN_ESTIMATES reliable capacity estimates (have ${est.size}).")
        }
        val t0 = est.first().timestamp
        val spanDays = (est.last().timestamp - t0) / DAY
        if (spanDays < MIN_SPAN_DAYS) {
            return CapacityForecast.Insufficient("Needs estimates spread over at least ${MIN_SPAN_DAYS.toInt()} days (have ${spanDays.toInt()}).")
        }
        val fit = LinearRegression.fit(est.map { (it.timestamp - t0) / DAY }, est.map { it.capacityMah!!.toDouble() })
            ?: return CapacityForecast.Insufficient("Estimates are not spread over time.")
        val lastT = est.last().timestamp
        val points = (0..horizonDays step 15).map { d ->
            val t = lastT + (d * DAY).toLong()
            val x = (t - t0) / DAY
            val y = fit.predict(x)
            val hw = fit.predictionHalfWidth(x)
            ForecastPoint(t, y, y - hw, y + hw)
        }
        val conf = if (spanDays >= 90 && est.size >= 10) Confidence.MEDIUM else Confidence.LOW
        return CapacityForecast.Available(
            points = points,
            changeMahPerYear = fit.slope * 365,
            confidence = conf,
            basis = "Linear trend of ${est.size} estimates over ${spanDays.toInt()} days with a 95% prediction band. " +
                "Real batteries rarely degrade perfectly linearly; treat this as a trend, not a replacement date.",
        )
    }
}
