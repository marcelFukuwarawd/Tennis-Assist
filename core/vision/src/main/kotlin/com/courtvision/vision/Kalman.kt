package com.courtvision.vision

/**
 * Constant-acceleration Kalman filter in image space, state [x, y, vx, vy, ax, ay].
 * Implemented with small dense matrices to avoid dependencies.
 */
class KalmanTracker(
    x0: Double, y0: Double,
    private val processNoise: Double = 50.0,
    private val measurementNoise: Double = 2.0,
) {
    private val n = 6
    var state = doubleArrayOf(x0, y0, 0.0, 0.0, 0.0, 0.0)
        private set
    private var p = Array(n) { i -> DoubleArray(n) { j -> if (i == j) (if (i < 2) 10.0 else 1e4) else 0.0 } }

    val position: Vec2 get() = Vec2(state[0], state[1])
    val velocity: Vec2 get() = Vec2(state[2], state[3])

    /** Standard deviation of the predicted position (pixels). */
    val positionSigma: Double get() = kotlin.math.sqrt((p[0][0] + p[1][1]) / 2)

    private fun f(dt: Double): Array<DoubleArray> {
        val h = 0.5 * dt * dt
        return arrayOf(
            doubleArrayOf(1.0, 0.0, dt, 0.0, h, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0, dt, 0.0, h),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0, dt, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, dt),
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0),
        )
    }

    fun predict(dt: Double): Vec2 {
        val fm = f(dt)
        state = DoubleArray(n) { i -> (0 until n).sumOf { k -> fm[i][k] * state[k] } }
        val fp = Array(n) { i -> DoubleArray(n) { j -> (0 until n).sumOf { k -> fm[i][k] * p[k][j] } } }
        p = Array(n) { i -> DoubleArray(n) { j -> (0 until n).sumOf { k -> fp[i][k] * fm[j][k] } } }
        val q = processNoise
        val dt2 = dt * dt
        for (i in 0 until 2) { p[i][i] += q * dt2 * dt2 / 4; p[i + 2][i + 2] += q * dt2; p[i + 4][i + 4] += q }
        return position
    }

    /** Peeks the prediction without modifying the filter. */
    fun peek(dt: Double): Vec2 = Vec2(
        state[0] + state[2] * dt + 0.5 * state[4] * dt * dt,
        state[1] + state[3] * dt + 0.5 * state[5] * dt * dt,
    )

    fun update(zx: Double, zy: Double, r: Double = measurementNoise) {
        val r2 = r * r
        // H selects x, y. S = P[0..1][0..1] + R
        val s00 = p[0][0] + r2; val s01 = p[0][1]; val s10 = p[1][0]; val s11 = p[1][1] + r2
        val det = s00 * s11 - s01 * s10
        if (kotlin.math.abs(det) < 1e-12) return
        val i00 = s11 / det; val i01 = -s01 / det; val i10 = -s10 / det; val i11 = s00 / det
        val k = Array(n) { i -> doubleArrayOf(p[i][0] * i00 + p[i][1] * i10, p[i][0] * i01 + p[i][1] * i11) }
        val y0 = zx - state[0]; val y1 = zy - state[1]
        for (i in 0 until n) state[i] += k[i][0] * y0 + k[i][1] * y1
        val newP = Array(n) { i -> DoubleArray(n) { j -> p[i][j] - (k[i][0] * p[0][j] + k[i][1] * p[1][j]) } }
        p = newP
    }
}
