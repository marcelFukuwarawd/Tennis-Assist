package com.courtvision.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VisionTest {
    private val court = CourtModel.TENNIS

    /** Synthetic camera: a perspective mapping court -> image used as ground truth. */
    private val truthCourtToImage = Homography(doubleArrayOf(
        60.0, -12.0, 400.0,
        0.0, 18.0, 150.0,
        0.0, -0.035, 1.0,
    ))

    private fun imgOf(p: Vec2) = truthCourtToImage.map(p)

    @Test fun homographyRecoversMappingFromFourPoints() {
        val courtPts = court.calibrationCorners()
        val imgPts = courtPts.map { imgOf(it) }
        val h = Homography.estimate(imgPts, courtPts)
        assertNotNull(h)
        val test = Vec2(3.3, 17.2)
        val back = h!!.map(imgOf(test))
        assertEquals(test.x, back.x, 1e-6)
        assertEquals(test.y, back.y, 1e-6)
        val inv = h.inverse()
        val again = inv.map(test)
        assertEquals(imgOf(test).x, again.x, 1e-6)
    }

    @Test fun homographyLeastSquaresWithManyPoints() {
        val courtPts = court.referencePoints().map { it.second }
        val imgPts = courtPts.map { imgOf(it) }
        val h = Homography.estimate(imgPts, courtPts)!!
        assertTrue(h.error(imgPts, courtPts) < 1e-6)
    }

    @Test fun calibrationValidity() {
        val corners = court.calibrationCorners().map { imgOf(it) }
        val cal = CourtCalibration.fromCorners(court, corners, 1280, 720)
        assertTrue(cal.isValid())
        assertTrue(cal.reprojectionErrorPx < 1e-6)
    }

    @Test fun kalmanFollowsConstantVelocity() {
        val kf = KalmanTracker(0.0, 0.0)
        for (i in 1..30) { kf.predict(1 / 30.0); kf.update(i * 10.0, i * 5.0) }
        val p = kf.peek(1 / 30.0)
        assertEquals(310.0, p.x, 3.0)
        assertEquals(155.0, p.y, 3.0)
    }

    /** Pinhole camera 4 m high, 4 m behind the near baseline, looking at the far service line. */
    private val cam = doubleArrayOf(5.485, 23.77 + 4.0, 4.0)
    private val f = 1100.0
    private fun project(x: Double, y: Double, z: Double): Vec2 {
        // forward vector towards target
        val tx = 5.485; val ty = 8.0; val tz = 0.0
        var fx = tx - cam[0]; var fy = ty - cam[1]; var fz = tz - cam[2]
        val fn = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz); fx /= fn; fy /= fn; fz /= fn
        // right = forward x up(0,0,1)
        var rx = -fy; var ry = fx; val rz = 0.0
        val rn = kotlin.math.sqrt(rx * rx + ry * ry); rx /= rn; ry /= rn
        // down = forward x right
        val dx = fy * rz - fz * ry; val dy = fz * rx - fx * rz; val dz = fx * ry - fy * rx
        val px = x - cam[0]; val py = y - cam[1]; val pz = z - cam[2]
        val cz = px * fx + py * fy + pz * fz
        val cx = px * rx + py * ry + pz * rz
        val cy = px * dx + py * dy + pz * dz
        return Vec2(960 + f * cx / cz, 540 - f * cy / cz)
    }

    /** Simulates a ball flight with gravity, projected by the 3D camera at 60 fps. */
    private fun simulateRally(): Pair<List<TrackPoint>, Vec2> {
        val pts = ArrayList<TrackPoint>()
        val start = Vec2(5.0, 23.0); val bounce = Vec2(6.0, 4.0)
        val t1 = 0.9; val g = 9.81; val h0 = 1.0
        val vz0 = (0 - h0 + 0.5 * g * t1 * t1) / t1
        val vBounce = 0.6 * (g * t1 - vz0)
        var t = 0.0; var frame = 0L
        while (t < t1 + 0.5) {
            val ground: Vec2; val z: Double
            if (t <= t1) {
                ground = start + (bounce - start) * (t / t1); z = h0 + vz0 * t - 0.5 * g * t * t
            } else {
                val tt = t - t1
                ground = bounce + (bounce - start) * (tt / t1) * 0.7; z = vBounce * tt - 0.5 * g * tt * tt
            }
            val img = project(ground.x, ground.y, z.coerceAtLeast(0.0))
            pts += TrackPoint(frame, (t * 1000).toLong(), img.x, img.y, true, 0.9, 2.0)
            frame++; t += 1 / 60.0
        }
        return pts to bounce
    }

    @Test fun bounceIsDetectedAndLocated() {
        val (track, bounce) = simulateRally()
        val h = Homography.estimate(court.calibrationCorners().map { project(it.x, it.y, 0.0) }, court.calibrationCorners())!!
        val events = EventDetector().detect(track, h)
        val bounces = events.filter { it.type == BallEventType.BOUNCE }
        assertEquals(1, bounces.size)
        val c = bounces[0].court!!
        assertTrue("bounce at $c", c.distanceTo(bounce) < 0.6)
        assertTrue(abs(bounces[0].timeMs - 900) < 60)
    }

    @Test fun kinkAtPlayerIsAHit() {
        val (track, _) = simulateRally()
        val h = Homography.estimate(court.calibrationCorners().map { project(it.x, it.y, 0.0) }, court.calibrationCorners())!!
        val bouncePx = track[54].pos
        val box = Box(bouncePx.x - 20, bouncePx.y - 60, bouncePx.x + 20, bouncePx.y + 10)
        val events = EventDetector().detect(track, h) { listOf(box) }
        assertTrue(events.any { it.type == BallEventType.HIT })
        assertTrue(events.none { it.type == BallEventType.BOUNCE })
    }

    @Test fun noEventsFromPredictedPoints() {
        val track = (0 until 50).map { TrackPoint(it.toLong(), it * 33L, 100.0 + it, 100.0 + (it % 7) * 30.0, false, 0.0, 0.0) }
        assertTrue(EventDetector().detect(track, null).isEmpty())
    }

    @Test fun lineCallsInOutAndInconclusive() {
        val caller = LineCaller(court, threshold = 0.95)
        val singles = court.area(PlayArea.SINGLES)
        val sigma = Vec2(0.05, 0.05)
        assertEquals(CallResult.IN, caller.call(Vec2(5.0, 10.0), sigma, singles).result)
        assertEquals(CallResult.OUT, caller.call(Vec2(5.0, -0.5), sigma, singles).result)
        // ball centre 1 cm outside the baseline edge: still touching the line (radius 3.35 cm) but within noise
        assertEquals(CallResult.INCONCLUSIVE, caller.call(Vec2(5.0, -0.03), sigma, singles).result)
    }

    @Test fun serviceBoxesAreDiagonal() {
        val near = court.serviceBox(serverNear = true, deuce = true)
        assertTrue(near.maxY <= court.netY && near.maxX <= court.doublesWidth / 2 + 1e-9)
        val far = court.serviceBox(serverNear = false, deuce = true)
        assertTrue(far.minY >= court.netY && far.minX >= court.doublesWidth / 2 - 1e-9)
    }

    @Test fun colorMotionDetectorFindsMovingYellowBlob() {
        val w = 160; val h = 90
        val bg = 0xFF336633.toInt()
        val f1 = IntArray(w * h) { bg }
        val f2 = IntArray(w * h) { bg }
        fun blob(f: IntArray, cx: Int, cy: Int) { for (y in cy - 2..cy + 2) for (x in cx - 2..cx + 2) f[y * w + x] = 0xFFD9F23A.toInt() }
        blob(f1, 40, 40); blob(f2, 60, 42)
        val d = ColorMotionBallDetector()
        d.detect(f1, w, h)
        val c = d.detect(f2, w, h)
        assertTrue(c.isNotEmpty())
        assertEquals(60.0, c[0].x, 1.0)
        assertEquals(42.0, c[0].y, 1.0)
    }

    @Test fun trackerConfirmsAndCoasts() {
        val tr = BallTracker()
        val out = ArrayList<TrackPoint>()
        for (i in 0 until 10) out += tr.update(i.toLong(), i * 33L, listOf(BallCandidate(10.0 + i * 5, 50.0, 2.0, 0.9, BallCandidate.Source.COLOR_MOTION)))
        assertEquals(10, out.size)
        out += tr.update(10, 330, emptyList())
        assertTrue(!out.last().observed)
    }

    @Test fun speedEstimate() {
        val v = SpeedEstimator.averageKmh(Vec2(5.0, 23.0), 0, Vec2(5.0, 4.0), 900)!!
        assertEquals(76.0, v, 0.5)
    }

    @Test fun placementAdvice() {
        val corners = court.calibrationCorners().map { imgOf(it) }
        val a = PlacementAdvisor.advise(0.0, 10.0, 120.0, corners, 1280, 720)
        assertTrue(a.isNotEmpty())
        assertTrue(PlacementAdvisor.advise(8.0, 10.0, 20.0, null, 1280, 720).containsAll(listOf(PlacementAdvice.LEVEL_DEVICE, PlacementAdvice.LOW_LIGHT, PlacementAdvice.CALIBRATE)))
    }

    @Test fun biomechanicsAngle() {
        assertEquals(90.0, Biomechanics.angle(Vec2(0.0, 1.0), Vec2(0.0, 0.0), Vec2(1.0, 0.0)), 1e-9)
    }
}
