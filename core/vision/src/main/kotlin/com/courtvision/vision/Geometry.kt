package com.courtvision.vision

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    fun dot(o: Vec2) = x * o.x + y * o.y
    val length: Double get() = hypot(x, y)
    fun distanceTo(o: Vec2) = hypot(x - o.x, y - o.y)
}

/** Solves A x = b in place with partial pivoting. Returns null for singular systems. */
internal fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
    val n = b.size
    for (col in 0 until n) {
        var pivot = col
        for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
        if (abs(a[pivot][col]) < 1e-12) return null
        if (pivot != col) {
            val t = a[pivot]; a[pivot] = a[col]; a[col] = t
            val tb = b[pivot]; b[pivot] = b[col]; b[col] = tb
        }
        for (r in col + 1 until n) {
            val f = a[r][col] / a[col][col]
            if (f == 0.0) continue
            for (c in col until n) a[r][c] -= f * a[col][c]
            b[r] -= f * b[col]
        }
    }
    val x = DoubleArray(n)
    for (r in n - 1 downTo 0) {
        var s = b[r]
        for (c in r + 1 until n) s -= a[r][c] * x[c]
        x[r] = s / a[r][r]
    }
    return x
}

/**
 * Planar projective transform (3x3, h33 = 1) mapping image pixels to court metres.
 * Estimated with the normalised DLT (least squares for > 4 correspondences).
 */
class Homography(val m: DoubleArray, private val reference: Vec2? = null) {
    init { require(m.size == 9) }

    private fun w(p: Vec2) = m[6] * p.x + m[7] * p.y + m[8]

    /**
     * Sign of the projective scale on the side of the plane that contains the calibration points.
     * The overall sign of an estimated homography is arbitrary, so it is fixed from a reference
     * point (the centroid of the source correspondences) instead of assuming w > 0.
     */
    private val orientation: Double = reference?.let { if (w(it) < 0) -1.0 else 1.0 } ?: 1.0

    fun map(p: Vec2): Vec2 {
        val w = m[6] * p.x + m[7] * p.y + m[8]
        return Vec2((m[0] * p.x + m[1] * p.y + m[2]) / w, (m[3] * p.x + m[4] * p.y + m[5]) / w)
    }

    /** True if the point is on the same side of the horizon line as the calibration points. */
    fun isInFront(p: Vec2): Boolean = orientation * w(p) > 0

    fun inverse(): Homography {
        val a = m
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        require(abs(det) > 1e-15) { "Singular homography" }
        val inv = doubleArrayOf(
            (a[4] * a[8] - a[5] * a[7]), -(a[1] * a[8] - a[2] * a[7]), (a[1] * a[5] - a[2] * a[4]),
            -(a[3] * a[8] - a[5] * a[6]), (a[0] * a[8] - a[2] * a[6]), -(a[0] * a[5] - a[2] * a[3]),
            (a[3] * a[7] - a[4] * a[6]), -(a[0] * a[7] - a[1] * a[6]), (a[0] * a[4] - a[1] * a[3]),
        ).map { it / det }.toDoubleArray()
        val s = inv[8]
        return Homography(inv.map { it / s }.toDoubleArray(), reference?.let { map(it) })
    }

    /** Numerical Jacobian d(court)/d(pixel) at [p]: rows (dX/dx, dX/dy), (dY/dx, dY/dy). */
    fun jacobian(p: Vec2, eps: Double = 0.5): DoubleArray {
        val px = map(Vec2(p.x + eps, p.y)); val nx = map(Vec2(p.x - eps, p.y))
        val py = map(Vec2(p.x, p.y + eps)); val ny = map(Vec2(p.x, p.y - eps))
        return doubleArrayOf(
            (px.x - nx.x) / (2 * eps), (py.x - ny.x) / (2 * eps),
            (px.y - nx.y) / (2 * eps), (py.y - ny.y) / (2 * eps),
        )
    }

    /** Propagates an isotropic pixel standard deviation to court metres along X and Y. */
    fun propagateSigma(p: Vec2, sigmaPx: Double): Vec2 {
        val j = jacobian(p)
        val sx = sqrt(j[0] * j[0] + j[1] * j[1]) * sigmaPx
        val sy = sqrt(j[2] * j[2] + j[3] * j[3]) * sigmaPx
        return Vec2(sx, sy)
    }

    /** Mean reprojection error (in destination units) of the given correspondences. */
    fun error(src: List<Vec2>, dst: List<Vec2>): Double =
        src.indices.sumOf { map(src[it]).distanceTo(dst[it]) } / src.size

    companion object {
        private fun normalization(pts: List<Vec2>): DoubleArray {
            val cx = pts.sumOf { it.x } / pts.size
            val cy = pts.sumOf { it.y } / pts.size
            val meanDist = pts.sumOf { hypot(it.x - cx, it.y - cy) } / pts.size
            val s = if (meanDist < 1e-12) 1.0 else sqrt(2.0) / meanDist
            return doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0)
        }

        private fun apply(t: DoubleArray, p: Vec2) = Vec2(t[0] * p.x + t[1] * p.y + t[2], t[3] * p.x + t[4] * p.y + t[5])

        private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
            val r = DoubleArray(9)
            for (i in 0..2) for (j in 0..2) {
                var s = 0.0
                for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]
                r[i * 3 + j] = s
            }
            return r
        }

        /** Estimates H such that H(src[i]) ≈ dst[i]. Needs at least 4 non-degenerate pairs. */
        fun estimate(src: List<Vec2>, dst: List<Vec2>): Homography? {
            require(src.size == dst.size)
            if (src.size < 4) return null
            val ts = normalization(src)
            val td = normalization(dst)
            val s = src.map { apply(ts, it) }
            val d = dst.map { apply(td, it) }
            // normal equations of the 2n x 8 system
            val ata = Array(8) { DoubleArray(8) }
            val atb = DoubleArray(8)
            for (i in s.indices) {
                val (x, y) = s[i]; val (u, v) = d[i]
                val r1 = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y)
                val r2 = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y)
                for (a in 0 until 8) {
                    atb[a] += r1[a] * u + r2[a] * v
                    for (b in 0 until 8) ata[a][b] += r1[a] * r1[b] + r2[a] * r2[b]
                }
            }
            val h = solveLinear(ata, atb) ?: return null
            val hn = doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
            // denormalise: H = Td^-1 * Hn * Ts
            val tdInv = Homography(td).inverse().m
            val full = mul(mul(tdInv, hn), ts)
            if (abs(full[8]) < 1e-15) return null
            val centroid = Vec2(src.sumOf { it.x } / src.size, src.sumOf { it.y } / src.size)
            val res = Homography(full.map { it / full[8] }.toDoubleArray(), centroid)
            if (res.m.any { it.isNaN() || it.isInfinite() }) return null
            return res
        }
    }
}
