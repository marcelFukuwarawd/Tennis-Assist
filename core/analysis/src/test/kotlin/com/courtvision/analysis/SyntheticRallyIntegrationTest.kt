package com.courtvision.analysis

import com.courtvision.scoring.ScoringSession
import com.courtvision.scoring.Side
import com.courtvision.scoring.Sport
import com.courtvision.vision.BallEventType
import com.courtvision.vision.BallTracker
import com.courtvision.vision.CallResult
import com.courtvision.vision.ColorMotionBallDetector
import com.courtvision.vision.CourtAutoDetector
import com.courtvision.vision.CourtCalibration
import com.courtvision.vision.CourtModel
import com.courtvision.vision.EventDetector
import com.courtvision.vision.LineCaller
import com.courtvision.vision.PlayArea
import com.courtvision.vision.StaticObjectFilter
import com.courtvision.vision.TrackPoint
import com.courtvision.vision.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * End-to-end test of the vision chain on RENDERED PIXELS: a pinhole camera (4 m high, 4 m behind
 * the near baseline) films a tennis court and a ball following gravity. The frames go through the
 * same detector, tracker, event detector, line caller and point inference used by the app.
 * Metrics are printed so they can be copied into TEST_REPORT.md. This is synthetic data: it
 * validates the algorithms, not real-world accuracy.
 */
class SyntheticRallyIntegrationTest {
    private val court = CourtModel.TENNIS
    private val w = 640; private val h = 360; private val fps = 60.0
    /** Rally camera: 4 m high, 4 m behind the baseline (near corners slightly out of frame). */
    private var cam = doubleArrayOf(5.485, court.length + 4.0, 4.0)
    private var f = 520.0
    private var target = 9.0

    private fun basis(): Array<DoubleArray> {
        var fx = 5.485 - cam[0]; var fy = target - cam[1]; var fz = 0.0 - cam[2]
        val n = sqrt(fx * fx + fy * fy + fz * fz); fx /= n; fy /= n; fz /= n
        var rx = -fy; var ry = fx; val rn = sqrt(rx * rx + ry * ry); rx /= rn; ry /= rn
        val ux = fy * 0 - fz * ry; val uy = fz * rx - fx * 0; val uz = fx * ry - fy * rx
        return arrayOf(doubleArrayOf(fx, fy, fz), doubleArrayOf(rx, ry, 0.0), doubleArrayOf(ux, uy, uz))
    }
    private var b = basis()

    /** Returns image point and depth. */
    private fun project(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val px = x - cam[0]; val py = y - cam[1]; val pz = z - cam[2]
        val d = px * b[0][0] + py * b[0][1] + pz * b[0][2]
        val cx = px * b[1][0] + py * b[1][1] + pz * b[1][2]
        val cy = px * b[2][0] + py * b[2][1] + pz * b[2][2]
        return Triple(w / 2 + f * cx / d, h / 2 - f * cy / d, d)
    }

    private val truthCorners get() = court.calibrationCorners().map { project(it.x, it.y, 0.0).let { p -> Vec2(p.first, p.second) } }
    private val cal get() = CourtCalibration.fromCorners(court, truthCorners, w, h)

    private fun background(): IntArray {
        val img = IntArray(w * h)
        val toCourt = cal.imageToCourt!!
        for (y in 0 until h) for (x in 0 until w) {
            val p = Vec2(x + 0.5, y + 0.5)
            val c = if (toCourt.isInFront(p)) toCourt.map(p) else null
            var col = 0xFF2E6B3A.toInt() // surround (green)
            if (c != null && c.x in -0.5..court.doublesWidth + 0.5 && c.y in -6.0..court.length + 6.0) col = 0xFF3A7D46.toInt()
            if (c != null && court.area(PlayArea.DOUBLES).contains(c)) col = 0xFF2C5D8F.toInt() // blue hard court
            if (c != null) for (l in court.lines()) {
                if (l.name == "net") continue
                val d = distToSegment(c, l.a, l.b)
                if (d < 0.05) { col = 0xFFF2F2F2.toInt(); break }
            }
            img[y * w + x] = col
        }
        return img
    }

    private fun distToSegment(p: Vec2, a: Vec2, b2: Vec2): Double {
        val ab = b2 - a; val t = ((p - a).dot(ab) / ab.dot(ab)).coerceIn(0.0, 1.0)
        return p.distanceTo(a + ab * t)
    }

    private data class Leg(val from: Vec2, val z0: Double, val to: Vec2, val z1: Double, val t: Double)

    /** serve -> far return -> near shot that lands long. */
    private val legs = listOf(
        Leg(Vec2(6.0, 24.2), 2.6, Vec2(4.0, 7.0), 0.0, 0.65),   // serve into far deuce box
        Leg(Vec2(4.0, 7.0), 0.0, Vec2(3.5, 0.5), 1.0, 0.35),    // to far player
        Leg(Vec2(3.5, 0.5), 1.0, Vec2(7.0, 19.0), 0.0, 1.0),    // return, lands near half
        Leg(Vec2(7.0, 19.0), 0.0, Vec2(7.5, 24.5), 1.0, 0.40),  // to near player
        Leg(Vec2(7.5, 24.5), 1.0, Vec2(5.0, -1.2), 0.0, 1.10),  // shot lands LONG (out)
        Leg(Vec2(5.0, -1.2), 0.0, Vec2(4.5, -6.0), 0.3, 0.45),
    )
    private val truthBounces = listOf(Vec2(4.0, 7.0) to CallResult.IN, Vec2(7.0, 19.0) to CallResult.IN, Vec2(5.0, -1.2) to CallResult.OUT)

    private fun ballAt(t: Double): DoubleArray? {
        var acc = 0.0
        for (l in legs) {
            if (t <= acc + l.t) {
                val u = t - acc; val g = 9.81
                val vz = (l.z1 - l.z0 + 0.5 * g * l.t * l.t) / l.t
                val p = l.from + (l.to - l.from) * (u / l.t)
                return doubleArrayOf(p.x, p.y, (l.z0 + vz * u - 0.5 * g * u * u).coerceAtLeast(0.0))
            }
            acc += l.t
        }
        return null
    }

    @Test fun fullChainOnRenderedFrames() {
        val detector = ColorMotionBallDetector()
        val tracker = BallTracker()
        val filter = StaticObjectFilter(w, h)
        val track = ArrayList<TrackPoint>()
        var visible = 0; var detectedNear = 0
        val truthPx = HashMap<Long, Vec2>()
        val bg = background()
        val total = legs.sumOf { it.t }
        var frame = 0L
        val frames = (total * fps).toInt()
        // a still ball lying on the court must be ignored
        val still = project(9.0, 21.0, 0.0)
        for (i in 0 until frames) {
            val t = i / fps
            val img = bg.copyOf()
            fun disk(cx: Double, cy: Double, r: Double) {
                val rr = r.coerceAtLeast(1.6)
                for (y in (cy - rr).toInt()..(cy + rr).toInt() + 1) for (x in (cx - rr).toInt()..(cx + rr).toInt() + 1) {
                    if (x < 0 || y < 0 || x >= w || y >= h) continue
                    val dx = x + 0.5 - cx; val dy = y + 0.5 - cy
                    if (dx * dx + dy * dy <= rr * rr) img[y * w + x] = 0xFFDCEB3C.toInt()
                }
            }
            disk(still.first, still.second, f * court.ballRadius / still.third)
            val ball = ballAt(t)
            if (ball != null) {
                val (px, py, d) = project(ball[0], ball[1], ball[2])
                if (px in 0.0..w.toDouble() && py in 0.0..h.toDouble()) {
                    visible++
                    truthPx[frame] = Vec2(px, py)
                    disk(px, py, f * court.ballRadius / d)
                }
            }
            val cands = filter.filter(detector.detect(img, w, h))
            track += tracker.update(frame, (t * 1000).toLong(), cands)
            frame++
        }
        val observed = track.filter { it.observed }
        observed.forEach { p -> truthPx[p.frame]?.let { if (it.distanceTo(p.pos) < 3.0) detectedNear++ } }
        val recall = detectedNear.toDouble() / visible
        val precision = if (observed.isEmpty()) 0.0 else detectedNear.toDouble() / observed.size

        // player boxes as the person detector would report them (far and near player)
        fun box(x: Double, y: Double): com.courtvision.vision.Box {
            val feet = project(x, y, 0.0); val head = project(x, y, 1.85); val side = project(x + 0.35, y, 0.0)
            val half = kotlin.math.abs(side.first - feet.first)
            return com.courtvision.vision.Box(feet.first - half, head.second, feet.first + half, feet.second)
        }
        val playerBoxes = listOf(box(3.5, 0.5), box(7.5, 24.5))
        val events = EventDetector().detect(track, cal.imageToCourt) { playerBoxes }
        val bounces = events.filter { it.type == BallEventType.BOUNCE }
        val caller = LineCaller(court, 0.95)
        val errors = truthBounces.map { (truth, _) -> bounces.minOfOrNull { it.court!!.distanceTo(truth) } ?: Double.MAX_VALUE }
        val calls = truthBounces.map { (truth, _) ->
            val bc = bounces.minByOrNull { it.court!!.distanceTo(truth) }!!
            caller.call(bc.court!!, bc.sigma!!, court.area(PlayArea.SINGLES)).result
        }
        println("SYNTHETIC-RALLY frames=$frames visible=$visible recall=${"%.3f".format(recall)} precision=${"%.3f".format(precision)}")
        println("SYNTHETIC-RALLY bounces detected=${bounces.size} truth=${truthBounces.size} errors_m=${errors.joinToString { "%.2f".format(it) }}")
        println("SYNTHETIC-RALLY calls=${calls.joinToString()} sigmas_cm=${bounces.joinToString { "%.0f".format(maxOf(it.sigma!!.x, it.sigma!!.y) * 100) }}")
        println("SYNTHETIC-RALLY hits=${events.count { it.type == BallEventType.HIT }}")

        assertTrue("recall $recall", recall > 0.85)
        assertTrue("precision $precision", precision > 0.9)
        assertTrue("every true bounce found within 0.5 m: $errors", errors.all { it < 0.5 })
        assertEquals(CallResult.IN, calls[0]); assertEquals(CallResult.IN, calls[1]); assertEquals(CallResult.OUT, calls[2])

        // point inference: near player (A at start) hit the last ball long -> point for B
        val rallies = RallySegmenter.segment(events)
        val inference = PointInference(court, ScoringSession(Sport.TENNIS))
        val props = inference.infer(rallies, { e -> caller.call(e.court!!, e.sigma!!, court.area(PlayArea.SINGLES)).result })
        println("SYNTHETIC-RALLY rallies=${rallies.size} points=${props.map { "${it.point.winner}/${it.point.reason}/${"%.2f".format(it.point.confidence)}" }}")
        assertEquals(1, props.size)
        assertEquals(Side.B, props[0].point.winner)
    }

    @Test fun automaticCourtDetectionOnRenderedCourt() {
        // wide camera: 6 m high, 8 m behind the baseline, whole court visible
        cam = doubleArrayOf(5.485, court.length + 8.0, 6.0); f = 420.0; target = 11.0; b = basis()
        val bg = background()
        val det = CourtAutoDetector(court)
        println("SYNTHETIC-COURT white=${bg.count { it == 0xFFF2F2F2.toInt() }} blue=${bg.count { it == 0xFF2C5D8F.toInt() }} mask_pixels=${det.lineMask(bg, w, h).count { it }}")
        val res = det.detect(bg, w, h)
        println("SYNTHETIC-COURT result=${res?.corners} truth=$truthCorners support=${res?.lineSupport}")
        assertTrue(res != null)
        val err = res!!.corners.indices.map { res.corners[it].distanceTo(truthCorners[it]) }
        println("SYNTHETIC-COURT corner_errors_px=${err.joinToString { "%.1f".format(it) }} line_support=${"%.2f".format(res.lineSupport)}")
        assertTrue("corner errors $err", err.all { it < 12 })
        // a rough manual placement (up to ~10 px off) is pulled onto the lines by the fine adjustment
        val rough = truthCorners.mapIndexed { i, c -> Vec2(c.x + listOf(9.0, -8.0, 7.0, -10.0)[i], c.y + listOf(-7.0, 8.0, 9.0, 6.0)[i]) }
        val (refined, support) = det.refine(rough, det.lineMask(bg, w, h), w, h)
        val refErr = refined.indices.map { refined[it].distanceTo(truthCorners[it]) }
        println("SYNTHETIC-COURT refine rough_errors_px=${rough.indices.map { "%.1f".format(rough[it].distanceTo(truthCorners[it])) }} refined_errors_px=${refErr.joinToString { "%.1f".format(it) }} support=${"%.2f".format(support)}")
        assertTrue("refined errors $refErr", refErr.all { it < 3.5 })
        assertTrue(res.lineSupport > 0.5)
    }
}
