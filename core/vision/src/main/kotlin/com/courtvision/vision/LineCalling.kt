package com.courtvision.vision

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

enum class CallResult { IN, OUT, INCONCLUSIVE }

data class LineCall(
    val result: CallResult,
    /** Model probability that the ball touched the area (Gaussian error model). */
    val probabilityIn: Double,
    /** Signed distance (m) of the ball edge to the boundary: >= 0 touches the area. */
    val marginMeters: Double,
    val sigmaMeters: Double,
    val calibrated: Boolean,
)

/**
 * Converts a bounce position + uncertainty into IN / OUT / INCONCLUSIVE.
 * The decision is never forced: when the probability is between the thresholds the call
 * is INCONCLUSIVE. Probabilities come from an explicit error model and can be mapped through
 * a [ReliabilityCalibration] fitted on annotated validation data.
 */
class LineCaller(
    private val court: CourtModel,
    /** Minimum probability for an automatic call, e.g. 0.95. */
    var threshold: Double = 0.95,
    var calibration: ReliabilityCalibration? = null,
) {
    fun call(bounce: Vec2, sigma: Vec2, area: CourtRect): LineCall {
        // the ball is IN if any part of it touches the line: expand the area by the ball radius
        val margin = area.signedDistance(bounce) + court.ballRadius
        // use the sigma along the axis of the closest boundary
        val dx = minOf(abs(bounce.x - area.minX), abs(area.maxX - bounce.x))
        val dy = minOf(abs(bounce.y - area.minY), abs(area.maxY - bounce.y))
        val s = (if (dx < dy) sigma.x else sigma.y).coerceAtLeast(0.005)
        var p = normalCdf(margin / s)
        val cal = calibration
        if (cal != null) p = cal.map(p)
        val result = when {
            p >= threshold -> CallResult.IN
            p <= 1 - threshold -> CallResult.OUT
            else -> CallResult.INCONCLUSIVE
        }
        return LineCall(result, p, margin, s, cal != null)
    }

    companion object {
        fun normalCdf(z: Double): Double {
            // Abramowitz-Stegun 7.1.26 approximation of erf
            val t = 1.0 / (1.0 + 0.3275911 * abs(z) / sqrt(2.0))
            val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-z * z / 2)
            return if (z >= 0) 0.5 * (1 + y) else 0.5 * (1 - y)
        }
    }
}

/**
 * Histogram-binning reliability calibration fitted on (predicted probability, true label) pairs
 * from annotated validation videos. Without data, no calibration is applied and the UI states it.
 */
class ReliabilityCalibration(private val bins: DoubleArray) {
    fun map(p: Double): Double {
        val i = (p * bins.size).toInt().coerceIn(0, bins.size - 1)
        return bins[i]
    }

    companion object {
        fun fit(samples: List<Pair<Double, Boolean>>, nBins: Int = 10): ReliabilityCalibration? {
            if (samples.size < nBins * 5) return null
            val pos = DoubleArray(nBins); val cnt = DoubleArray(nBins)
            for ((p, y) in samples) {
                val i = (p * nBins).toInt().coerceIn(0, nBins - 1)
                cnt[i]++; if (y) pos[i]++
            }
            val bins = DoubleArray(nBins) { i -> if (cnt[i] > 0) (pos[i] + 0.5) / (cnt[i] + 1) else (i + 0.5) / nBins }
            // enforce monotonicity (pool adjacent violators, simple pass)
            for (i in 1 until nBins) if (bins[i] < bins[i - 1]) bins[i] = bins[i - 1]
            return ReliabilityCalibration(bins)
        }
    }
}

/** Average horizontal ball speed between a hit position and the bounce position. */
object SpeedEstimator {
    /** Returns km/h, or null if the data is not physically plausible. */
    fun averageKmh(from: Vec2, fromMs: Long, to: Vec2, toMs: Long): Double? {
        val dt = (toMs - fromMs) / 1000.0
        if (dt <= 0.05 || dt > 4.0) return null
        val v = from.distanceTo(to) / dt * 3.6
        return if (v in 5.0..260.0) v else null
    }
}
