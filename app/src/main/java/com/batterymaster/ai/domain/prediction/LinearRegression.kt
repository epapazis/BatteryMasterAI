package com.batterymaster.ai.domain.prediction

import kotlin.math.sqrt

data class LinearFit(
    val slope: Double,
    val intercept: Double,
    val n: Int,
    val xMean: Double,
    val sxx: Double,
    val residualStd: Double,
    val r2: Double,
) {
    fun predict(x: Double): Double = intercept + slope * x

    val slopeStdErr: Double get() = if (sxx > 0) residualStd / sqrt(sxx) else Double.NaN

    /** Half-width of the ~95 % prediction interval for a new observation at x. */
    fun predictionHalfWidth(x: Double): Double =
        LinearRegression.tCritical95(n - 2) * residualStd * sqrt(1.0 + 1.0 / n + (x - xMean) * (x - xMean) / sxx)
}

object LinearRegression {
    fun fit(xs: List<Double>, ys: List<Double>): LinearFit? {
        require(xs.size == ys.size)
        val n = xs.size
        if (n < 3) return null
        val xMean = xs.average()
        val yMean = ys.average()
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - xMean
            val dy = ys[i] - yMean
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        if (sxx <= 0.0) return null
        val slope = sxy / sxx
        val intercept = yMean - slope * xMean
        var sse = 0.0
        for (i in 0 until n) {
            val r = ys[i] - (intercept + slope * xs[i])
            sse += r * r
        }
        val residualStd = sqrt(sse / (n - 2))
        val r2 = if (syy > 0) 1.0 - sse / syy else 1.0
        return LinearFit(slope, intercept, n, xMean, sxx, residualStd, r2)
    }

    /** Two-sided 95 % Student-t critical values. */
    fun tCritical95(df: Int): Double = when {
        df <= 1 -> 12.71
        df == 2 -> 4.30
        df == 3 -> 3.18
        df == 4 -> 2.78
        df == 5 -> 2.57
        df <= 7 -> 2.40
        df <= 10 -> 2.26
        df <= 15 -> 2.15
        df <= 20 -> 2.09
        df <= 30 -> 2.04
        df <= 60 -> 2.00
        else -> 1.96
    }
}
