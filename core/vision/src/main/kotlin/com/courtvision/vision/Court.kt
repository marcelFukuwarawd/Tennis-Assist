package com.courtvision.vision

/** Segment of a court line in court coordinates (metres). */
data class LineSegment(val a: Vec2, val b: Vec2, val name: String)

enum class CourtType { TENNIS, PICKLEBALL }

enum class PlayArea { SINGLES, DOUBLES }

/** Rectangle in court metres. */
data class CourtRect(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    fun contains(p: Vec2) = p.x in minX..maxX && p.y in minY..maxY

    /** Signed distance from [p] to the rectangle boundary: positive inside, negative outside. */
    fun signedDistance(p: Vec2): Double {
        val dx = minOf(p.x - minX, maxX - p.x)
        val dy = minOf(p.y - minY, maxY - p.y)
        return if (dx >= 0 && dy >= 0) minOf(dx, dy) else {
            val ox = maxOf(minX - p.x, 0.0, p.x - maxX)
            val oy = maxOf(minY - p.y, 0.0, p.y - maxY)
            -kotlin.math.hypot(ox, oy)
        }
    }

    val width get() = maxX - minX
    val height get() = maxY - minY
}

/**
 * Official court geometry. Coordinate system: X across the court (0 = left doubles sideline seen
 * from the near end), Y along the court (0 = FAR baseline, length = NEAR baseline), net at length/2.
 * Line coordinates are the OUTER edges of the lines, because lines belong to the court.
 */
data class CourtModel(
    val type: CourtType,
    val length: Double,
    val doublesWidth: Double,
    val singlesWidth: Double,
    /** Distance from the net to the service line (tennis) or to the NVZ line (pickleball). */
    val serviceDistance: Double,
    val lineWidth: Double,
    val ballRadius: Double,
) {
    val netY: Double get() = length / 2
    private val singlesInset: Double get() = (doublesWidth - singlesWidth) / 2

    fun area(play: PlayArea): CourtRect = if (play == PlayArea.DOUBLES || type == CourtType.PICKLEBALL)
        CourtRect(0.0, 0.0, doublesWidth, length)
    else CourtRect(singlesInset, 0.0, doublesWidth - singlesInset, length)

    /** Half of the court on the near (camera) side. */
    fun isNearHalf(p: Vec2) = p.y > netY

    /**
     * Service box (tennis) or service court (pickleball) that a serve from [serverNear] must land in.
     * [deuce] = server on the right side of their own baseline (score even).
     */
    fun serviceBox(serverNear: Boolean, deuce: Boolean): CourtRect {
        val centre = doublesWidth / 2
        val left = if (type == CourtType.TENNIS) singlesInset else 0.0
        val right = if (type == CourtType.TENNIS) doublesWidth - singlesInset else doublesWidth
        return if (type == CourtType.TENNIS) {
            // target box is on the opposite half, diagonal to the server
            if (serverNear) {
                // server at near baseline, right side = high X. Diagonal box = far half, low X.
                if (deuce) CourtRect(left, netY - serviceDistance, centre, netY) else CourtRect(centre, netY - serviceDistance, right, netY)
            } else {
                // far server faces the camera: his right is low X, diagonal box is near half, high X.
                if (deuce) CourtRect(centre, netY, right, netY + serviceDistance) else CourtRect(left, netY, centre, netY + serviceDistance)
            }
        } else {
            // pickleball: serve must clear the NVZ and land diagonally up to the baseline
            if (serverNear) {
                if (deuce) CourtRect(0.0, 0.0, centre, netY - serviceDistance) else CourtRect(centre, 0.0, doublesWidth, netY - serviceDistance)
            } else {
                if (deuce) CourtRect(centre, netY + serviceDistance, doublesWidth, length) else CourtRect(0.0, netY + serviceDistance, centre, length)
            }
        }
    }

    /** Non-volley zone ("kitchen") rectangles, pickleball only. */
    fun nonVolleyZones(): List<CourtRect> = if (type != CourtType.PICKLEBALL) emptyList() else listOf(
        CourtRect(0.0, netY - serviceDistance, doublesWidth, netY),
        CourtRect(0.0, netY, doublesWidth, netY + serviceDistance),
    )

    /** The four outer corners used for calibration: far-left, far-right, near-right, near-left. */
    fun calibrationCorners(): List<Vec2> = listOf(
        Vec2(0.0, 0.0), Vec2(doublesWidth, 0.0), Vec2(doublesWidth, length), Vec2(0.0, length),
    )

    /** Additional reference points (service line / centre-line intersections) for refinement. */
    fun referencePoints(): List<Pair<String, Vec2>> {
        val c = doublesWidth / 2
        val pts = mutableListOf(
            "far_left" to Vec2(0.0, 0.0), "far_right" to Vec2(doublesWidth, 0.0),
            "near_right" to Vec2(doublesWidth, length), "near_left" to Vec2(0.0, length),
            "net_left" to Vec2(0.0, netY), "net_right" to Vec2(doublesWidth, netY),
        )
        if (type == CourtType.TENNIS) {
            pts += "far_service_centre" to Vec2(c, netY - serviceDistance)
            pts += "near_service_centre" to Vec2(c, netY + serviceDistance)
        } else {
            pts += "far_nvz_left" to Vec2(0.0, netY - serviceDistance)
            pts += "near_nvz_left" to Vec2(0.0, netY + serviceDistance)
        }
        return pts
    }

    fun lines(): List<LineSegment> {
        val w = doublesWidth
        val l = length
        val c = w / 2
        val out = mutableListOf(
            LineSegment(Vec2(0.0, 0.0), Vec2(w, 0.0), "far_baseline"),
            LineSegment(Vec2(0.0, l), Vec2(w, l), "near_baseline"),
            LineSegment(Vec2(0.0, 0.0), Vec2(0.0, l), "left_sideline"),
            LineSegment(Vec2(w, 0.0), Vec2(w, l), "right_sideline"),
            LineSegment(Vec2(0.0, netY), Vec2(w, netY), "net"),
        )
        if (type == CourtType.TENNIS) {
            val i = singlesInset
            out += LineSegment(Vec2(i, 0.0), Vec2(i, l), "left_singles")
            out += LineSegment(Vec2(w - i, 0.0), Vec2(w - i, l), "right_singles")
            out += LineSegment(Vec2(i, netY - serviceDistance), Vec2(w - i, netY - serviceDistance), "far_service")
            out += LineSegment(Vec2(i, netY + serviceDistance), Vec2(w - i, netY + serviceDistance), "near_service")
            out += LineSegment(Vec2(c, netY - serviceDistance), Vec2(c, netY + serviceDistance), "centre_service")
        } else {
            out += LineSegment(Vec2(0.0, netY - serviceDistance), Vec2(w, netY - serviceDistance), "far_nvz")
            out += LineSegment(Vec2(0.0, netY + serviceDistance), Vec2(w, netY + serviceDistance), "near_nvz")
            out += LineSegment(Vec2(c, 0.0), Vec2(c, netY - serviceDistance), "far_centre")
            out += LineSegment(Vec2(c, netY + serviceDistance), Vec2(c, l), "near_centre")
        }
        return out
    }

    companion object {
        /** ITF: 23.77 m x 10.97 m (doubles), 8.23 m singles, service line 6.40 m from the net. */
        val TENNIS = CourtModel(CourtType.TENNIS, 23.77, 10.97, 8.23, 6.40, 0.05, 0.0335)

        /** USA Pickleball: 44 ft x 20 ft (13.41 m x 6.10 m), NVZ 7 ft (2.13 m). */
        val PICKLEBALL = CourtModel(CourtType.PICKLEBALL, 13.41, 6.10, 6.10, 2.13, 0.05, 0.037)

        fun of(type: CourtType) = if (type == CourtType.TENNIS) TENNIS else PICKLEBALL
    }
}

/** Court calibration: 4+ image points matched to known court points. */
data class CourtCalibration(
    val court: CourtModel,
    val imagePoints: List<Vec2>,
    val courtPoints: List<Vec2>,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val imageToCourt: Homography? = Homography.estimate(imagePoints, courtPoints)
    val courtToImage: Homography? = imageToCourt?.let { runCatching { it.inverse() }.getOrNull() }

    /** Mean reprojection error in pixels; large values indicate a bad calibration. */
    val reprojectionErrorPx: Double
        get() {
            val inv = courtToImage ?: return Double.POSITIVE_INFINITY
            return inv.error(courtPoints, imagePoints)
        }

    fun isValid(): Boolean {
        val h = imageToCourt ?: return false
        if (!imagePoints.all { h.isInFront(it) }) return false
        return reprojectionErrorPx < maxOf(imageWidth, imageHeight) * 0.02
    }

    companion object {
        /** Calibration from the 4 outer corners: far-left, far-right, near-right, near-left. */
        fun fromCorners(court: CourtModel, corners: List<Vec2>, w: Int, h: Int) =
            CourtCalibration(court, corners, court.calibrationCorners(), w, h)
    }
}
