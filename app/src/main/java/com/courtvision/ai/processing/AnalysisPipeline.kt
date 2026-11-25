package com.courtvision.ai.processing

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.courtvision.ai.data.AnalysisState
import com.courtvision.ai.data.AppDatabase
import com.courtvision.ai.data.AppJson
import com.courtvision.ai.data.AppSettings
import com.courtvision.ai.data.BallColor
import com.courtvision.ai.data.BallTrackEntity
import com.courtvision.ai.data.BounceEntity
import com.courtvision.ai.data.CalibrationData
import com.courtvision.ai.data.PlayerSampleEntity
import com.courtvision.ai.data.PointEntity
import com.courtvision.ai.data.ProcessingMode
import com.courtvision.ai.data.RallyEntity
import com.courtvision.ai.data.SessionEntity
import com.courtvision.ai.data.ShotEntity
import com.courtvision.ai.data.calibration
import com.courtvision.ai.data.court
import com.courtvision.ai.data.scoringSession
import com.courtvision.ai.data.toCalibration
import com.courtvision.ai.data.toRecord
import com.courtvision.ai.data.toScoringEvents
import com.courtvision.ai.util.Thermal
import com.courtvision.analysis.PointInference
import com.courtvision.analysis.RallySegmenter
import com.courtvision.vision.BallColorProfile
import com.courtvision.vision.BallEvent
import com.courtvision.vision.BallEventType
import com.courtvision.vision.BallTracker
import com.courtvision.vision.Box
import com.courtvision.vision.CallResult
import com.courtvision.vision.CandidateFusion
import com.courtvision.vision.ColorMotionBallDetector
import com.courtvision.vision.CourtAutoDetector
import com.courtvision.vision.CourtCalibration
import com.courtvision.vision.CourtModel
import com.courtvision.vision.CourtType
import com.courtvision.vision.EventDetector
import com.courtvision.vision.LineCaller
import com.courtvision.vision.PersonDetection
import com.courtvision.vision.PlayArea
import com.courtvision.vision.PlayerTracker
import com.courtvision.vision.Pose
import com.courtvision.vision.ShotClassifier
import com.courtvision.vision.ShotFeatures
import com.courtvision.vision.SpeedEstimator
import com.courtvision.vision.StaticObjectFilter
import com.courtvision.vision.TrackPoint
import com.courtvision.vision.Vec2
import kotlinx.coroutines.delay

/** Parameters derived from the processing mode (economy / balanced / max accuracy). */
data class PipelineConfig(val analysisWidth: Int, val maxFps: Int, val personEvery: Int, val usePose: Boolean, val gpu: Boolean) {
    companion object {
        fun of(mode: ProcessingMode) = when (mode) {
            ProcessingMode.ECONOMY -> PipelineConfig(480, 15, 6, false, false)
            ProcessingMode.BALANCED -> PipelineConfig(640, 30, 3, true, true)
            ProcessingMode.MAX_ACCURACY -> PipelineConfig(960, 60, 1, true, true)
        }
    }
}

class AnalysisException(val code: String) : Exception(code)

/**
 * Full offline analysis of a session:
 *  1. DECODE  - hardware decode, ball candidates (colour+motion+neural), Kalman tracking, people tracking;
 *               results are flushed with a checkpoint every few seconds so the job can resume;
 *  2. EVENTS  - bounce / hit detection, rally segmentation, line calls with uncertainty;
 *  3. SHOTS   - pose at each contact, stroke classification, speed estimation;
 *  4. SCORE   - automatic point proposals (reviewable), never overriding manual points.
 */
class AnalysisPipeline(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: AppSettings,
    private val progress: suspend (stage: String, value: Float) -> Unit,
    private val isStopped: () -> Boolean,
) {
    private val cfg = PipelineConfig.of(settings.processing)

    suspend fun run(sessionId: Long) {
        val session = db.sessions().get(sessionId) ?: throw AnalysisException("session_missing")
        val segments = db.segments().forSession(sessionId).filter { it.valid }
        if (segments.isEmpty()) throw AnalysisException("no_valid_video")
        val court = session.court()
        val ml = MlModels(context, cfg.gpu)
        try {
            val cal = ensureCalibration(session, segments.first().uri, court)
            decodePass(session, segments, cal.first, cal.second, ml)
            if (isStopped()) return
            progress("events", 0.85f)
            eventsPass(db.sessions().get(sessionId)!!, cal.first, cal.second, ml)
        } finally {
            ml.close()
        }
    }

    /** Uses the stored calibration or tries the automatic court detector on the first frame. */
    private suspend fun ensureCalibration(s: SessionEntity, firstUri: String, court: CourtModel): Pair<CalibrationData, Float> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(firstUri))
            val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw AnalysisException("unreadable_video")
            val aspect = frame.height / frame.width.toFloat()
            s.calibration()?.let { return it to aspect }
            progress("court", 0.01f)
            val w = cfg.analysisWidth
            val h = (w * aspect).toInt()
            val small = Bitmap.createScaledBitmap(frame, w, h, true)
            val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
            val res = CourtAutoDetector(court).detect(px, w, h)
            if (res == null || res.lineSupport < 0.35 || !res.calibration.isValid()) throw AnalysisException("calibration_needed")
            val data = CalibrationData(res.corners.map { it.x / w }, res.corners.map { it.y / h }, auto = true, lineSupport = res.lineSupport)
            db.sessions().setCalibration(s.id, AppJson.encodeToString(CalibrationData.serializer(), data))
            return data to aspect
        } finally {
            runCatching { r.release() }
        }
    }

    private fun profile(): BallColorProfile = when (settings.ballColor) {
        BallColor.OPTIC_YELLOW -> BallColorProfile.TENNIS_OPTIC_YELLOW
        BallColor.ORANGE -> BallColorProfile.PICKLEBALL_ORANGE
        BallColor.GREEN -> BallColorProfile.PICKLEBALL_GREEN
    }

    private suspend fun decodePass(
        s: SessionEntity, segments: List<com.courtvision.ai.data.SegmentEntity>,
        calData: CalibrationData, aspect: Float, ml: MlModels,
    ) {
        val court = s.court()
        val total = segments.sumOf { it.durationMs }.coerceAtLeast(1)
        val checkpoint = s.analysisCheckpointMs
        // drop partial results after the checkpoint (they will be recomputed)
        db.analysis().deleteTrackFrom(s.id, checkpoint)
        db.analysis().deletePlayersFrom(s.id, checkpoint)
        for (seg in segments) {
            val segEnd = seg.startOffsetMs + seg.durationMs
            if (segEnd <= checkpoint) continue
            val localStartUs = ((checkpoint - seg.startOffsetMs).coerceAtLeast(0)) * 1000
            val srcFps = if (s.fps > 1) s.fps else 30f
            val stride = maxOf(1, (srcFps / cfg.maxFps).toInt())
            val decoder = VideoFrameDecoder(context, Uri.parse(seg.uri), cfg.analysisWidth)
            val detector = ColorMotionBallDetector(profile())
            val tracker = BallTracker()
            var players: PlayerTracker? = null
            var staticFilter: StaticObjectFilter? = null
            var calibration: CourtCalibration? = null
            var roi: IntArray? = null
            var bitmap: Bitmap? = null
            val trackBuf = ArrayList<BallTrackEntity>()
            val playerBuf = ArrayList<PlayerSampleEntity>()
            var lastFlushMs = -1L
            var lastPlayerSampleMs = -1000L
            var lastPersons: List<PersonDetection> = emptyList()
            var frameNo = 0L
            suspend fun flush(t: Long) {
                if (trackBuf.isNotEmpty()) db.analysis().insertTrack(ArrayList(trackBuf))
                if (playerBuf.isNotEmpty()) db.analysis().insertPlayers(ArrayList(playerBuf))
                trackBuf.clear(); playerBuf.clear()
                db.sessions().setCheckpoint(s.id, t)
                progress("decode", 0.03f + 0.8f * (t.toFloat() / total))
            }
            val finished = decoder.decode(localStartUs, stride, isStopped) { f ->
                val tMs = seg.startOffsetMs + f.ptsUs / 1000
                if (calibration == null) {
                    calibration = calData.toCalibration(court, f.width, f.height)
                    players = PlayerTracker(court = court)
                    staticFilter = StaticObjectFilter(f.width, f.height)
                    roi = courtRoi(calibration!!, f.width, f.height)
                    bitmap = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)
                }
                val hgm = calibration!!.imageToCourt
                var cands = staticFilter!!.filter(detector.detect(f.argb, f.width, f.height, roi))
                if (ml.available && frameNo % cfg.personEvery == 0L) {
                    bitmap!!.setPixels(f.argb, 0, f.width, 0, 0, f.width, f.height)
                    val det = ml.detect(bitmap!!)
                    lastPersons = det.persons.map { it.copy(appearance = PlayerTracker.appearance(f.argb, f.width, f.height, it.box)) }
                    cands = CandidateFusion.fuse(cands, det.balls)
                    val samples = players!!.update(frameNo, tMs, lastPersons, hgm)
                    if (tMs - lastPlayerSampleMs >= 250) {
                        lastPlayerSampleMs = tMs
                        samples.forEach { sm ->
                            playerBuf += PlayerSampleEntity(
                                sessionId = s.id, playerId = sm.playerId, timeMs = tMs,
                                courtX = sm.court?.x, courtY = sm.court?.y,
                                left = sm.box.left / f.width, top = sm.box.top / f.height,
                                right = sm.box.right / f.width, bottom = sm.box.bottom / f.height,
                            )
                        }
                    }
                }
                for (p in tracker.update(frameNo, tMs, cands)) {
                    trackBuf += BallTrackEntity(
                        sessionId = s.id, timeMs = p.timeMs, frame = p.frame,
                        x = p.x / f.width, y = p.y / f.height, observed = p.observed,
                        confidence = p.confidence, radius = p.radius / f.width,
                    )
                }
                frameNo++
                if (lastFlushMs < 0) lastFlushMs = tMs
                if (tMs - lastFlushMs >= 5000) {
                    lastFlushMs = tMs
                    flush(tMs)
                    throttleIfHot()
                }
                true
            }
            if (isStopped()) {
                // keep everything up to the last flushed checkpoint only
                return
            }
            flush(if (finished) segEnd else lastFlushMs.coerceAtLeast(checkpoint))
        }
    }

    private suspend fun throttleIfHot() {
        // Reduce AI load first when the device is hot; never try to bypass thermal protection.
        var waited = 0
        while (Thermal.isHot(context) && waited < 60 && !isStopped()) {
            progress("cooling", -1f)
            delay(5_000); waited++
        }
        if (Thermal.isCritical(context)) throw AnalysisException("too_hot")
    }

    private fun courtRoi(cal: CourtCalibration, w: Int, h: Int): IntArray? {
        val inv = cal.courtToImage ?: return null
        val c = cal.court
        val margin = if (c.type == CourtType.TENNIS) 5.0 else 2.5
        val pts = listOf(Vec2(-margin, -margin), Vec2(c.doublesWidth + margin, -margin), Vec2(c.doublesWidth + margin, c.length + margin), Vec2(-margin, c.length + margin))
            .filter { cal.imageToCourt?.isInFront(inv.map(it)) == true }
            .map { inv.map(it) }
        if (pts.size < 4) return null
        val minX = pts.minOf { it.x }.toInt() - 10
        // the ball can fly above the far baseline: extend the ROI to the top of the image
        return intArrayOf(minX.coerceAtLeast(0), 0, (pts.maxOf { it.x }.toInt() + 10).coerceAtMost(w), (pts.maxOf { it.y }.toInt() + 10).coerceAtMost(h))
    }

    private suspend fun eventsPass(s: SessionEntity, calData: CalibrationData, aspect: Float, ml: MlModels) {
        val court = s.court()
        val w = cfg.analysisWidth
        val h = (w * aspect).toInt()
        val cal = calData.toCalibration(court, w, h)
        val toCourt = cal.imageToCourt ?: throw AnalysisException("calibration_needed")
        val track = db.analysis().track(s.id).map {
            TrackPoint(it.frame, it.timeMs, it.x * w, it.y * h, it.observed, it.confidence, it.radius * w)
        }
        val playerRows = db.analysis().players(s.id)
        val byTime = playerRows.groupBy { it.timeMs / 250 }
        fun boxesAt(t: Long): List<Box> = (byTime[t / 250].orEmpty() + byTime[t / 250 - 1].orEmpty())
            .map { Box(it.left * w, it.top * h, it.right * w, it.bottom * h) }
        fun playerAt(t: Long, img: Vec2): PlayerSampleEntity? = (byTime[t / 250].orEmpty() + byTime[t / 250 - 1].orEmpty() + byTime[t / 250 + 1].orEmpty())
            .minByOrNull { Vec2((it.left + it.right) / 2 * w, (it.top + it.bottom) / 2 * h).distanceTo(img) }

        // split the trajectory where it has gaps so the detector never bridges two rallies
        val events = ArrayList<BallEvent>()
        var start = 0
        for (i in 1..track.size) {
            if (i == track.size || track[i].timeMs - track[i - 1].timeMs > 400) {
                events += EventDetector().detect(track.subList(start, i), toCourt, if (playerRows.isEmpty()) null else ::boxesAt)
                start = i
            }
        }
        val rallies = RallySegmenter.segment(events)
        progress("calls", 0.88f)
        val caller = LineCaller(court, settings.callThreshold)
        val area = court.area(if (s.mode.isDoubles) PlayArea.DOUBLES else PlayArea.SINGLES)
        val callOf = HashMap<BallEvent, CallResult>()
        val bounceRows = ArrayList<BounceEntity>()
        val shotRows = ArrayList<ShotEntity>()
        val rallyRows = ArrayList<RallyEntity>()

        // pose extraction at contact instants
        val retriever = if (cfg.usePose) MediaMetadataRetriever() else null
        val segments = db.segments().forSession(s.id).filter { it.valid }
        var currentSegUri: String? = null
        fun poseAt(t: Long): List<Pose> {
            val r = retriever ?: return emptyList()
            val seg = segments.lastOrNull { it.startOffsetMs <= t } ?: return emptyList()
            if (seg.uri != currentSegUri) { r.setDataSource(context, Uri.parse(seg.uri)); currentSegUri = seg.uri }
            val bmp = r.getScaledFrameAtTime((t - seg.startOffsetMs) * 1000, MediaMetadataRetriever.OPTION_CLOSEST, w, h) ?: return emptyList()
            return ml.poses(bmp)
        }

        try {
            rallies.forEachIndexed { ri, rally ->
                if (isStopped()) return
                progress("shots", 0.88f + 0.1f * ri / rallies.size.coerceAtLeast(1))
                val hits = rally.filter { it.type == BallEventType.HIT }
                rallyRows += RallyEntity(sessionId = s.id, index = ri, startMs = rally.first().timeMs, endMs = rally.last().timeMs, shots = hits.size)
                var lastHitNear: Boolean? = null
                var lastHitCourt: Vec2? = null
                var hitIndex = -1
                for (e in rally) {
                    when (e.type) {
                        BallEventType.HIT -> {
                            hitIndex++
                            val player = playerAt(e.timeMs, e.image)
                            val feet = player?.let { if (it.courtX != null && it.courtY != null) Vec2(it.courtX, it.courtY) else null }
                            lastHitCourt = feet ?: e.court
                            lastHitNear = lastHitCourt?.let { court.isNearHalf(it) }
                            val next = rally.firstOrNull { it.timeMs > e.timeMs }
                            val nextBounce = next?.takeIf { it.type == BallEventType.BOUNCE }
                            val speed = if (nextBounce?.court != null && lastHitCourt != null) SpeedEstimator.averageKmh(lastHitCourt!!, e.timeMs, nextBounce.court!!, nextBounce.timeMs) else null
                            val poses = if (cfg.usePose && ml.available) poseAt(e.timeMs) else emptyList()
                            val pose = poses.minByOrNull { it.box.centre.distanceTo(e.image) }?.takeIf { it.box.centre.distanceTo(e.image) < it.box.height * 1.5 }
                            val prevBounceOnMySide = rally.lastOrNull { it.timeMs < e.timeMs && it.type == BallEventType.BOUNCE }?.court?.let { lastHitNear != null && court.isNearHalf(it) == lastHitNear }
                            val features = ShotFeatures(
                                sport = court.type, indexInRally = hitIndex, hitterCourt = lastHitCourt, ballAtContact = e.image, pose = pose,
                                rightHanded = true, nextBounce = nextBounce?.court, flightMs = nextBounce?.let { it.timeMs - e.timeMs },
                                avgSpeedKmh = speed, bouncedBeforeHit = prevBounceOnMySide,
                            )
                            val cls = ShotClassifier.classify(features, court)
                            shotRows += ShotEntity(
                                sessionId = s.id, timeMs = e.timeMs, sideA = null, playerId = player?.playerId,
                                type = cls.type.name, grip = cls.grip?.name, speedKmh = speed,
                                hitterX = lastHitCourt?.x, hitterY = lastHitCourt?.y,
                                rallyIndex = ri, indexInRally = hitIndex, support = cls.support,
                            )
                        }
                        BallEventType.BOUNCE -> {
                            val bc = e.court ?: continue
                            val sigma = e.sigma ?: Vec2(0.3, 0.3)
                            val target = when {
                                lastHitNear == null -> area
                                hitIndex == 0 -> {
                                    // serve: deuce side when the server stands on his right half
                                    val x = lastHitCourt?.x ?: court.doublesWidth / 2
                                    val deuce = if (lastHitNear!!) x > court.doublesWidth / 2 else x < court.doublesWidth / 2
                                    court.serviceBox(lastHitNear!!, deuce)
                                }
                                lastHitNear!! -> area.copy(maxY = court.netY)
                                else -> area.copy(minY = court.netY)
                            }
                            val call = caller.call(bc, sigma, target)
                            callOf[e] = call.result
                            bounceRows += BounceEntity(
                                sessionId = s.id, timeMs = e.timeMs, x = bc.x, y = bc.y, sigmaX = sigma.x, sigmaY = sigma.y,
                                call = call.result.name, probabilityIn = call.probabilityIn,
                                imageX = e.image.x / w, imageY = e.image.y / h, calibrated = call.calibrated,
                            )
                            // attach the first bounce after a hit to that shot
                            val idx = shotRows.indexOfLast { it.rallyIndex == ri && it.timeMs < e.timeMs }
                            if (idx >= 0 && shotRows[idx].bounceX == null) {
                                shotRows[idx] = shotRows[idx].copy(bounceX = bc.x, bounceY = bc.y, call = call.result.name, probabilityIn = call.probabilityIn)
                            }
                        }
                    }
                }
            }
        } finally {
            retriever?.let { runCatching { it.release() } }
        }

        // ---- scoring proposals ----
        progress("score", 0.98f)
        val existing = db.points().forSession(s.id)
        val manual = existing.filter { !it.auto }
        var points = existing
        if (settings.autoScoring && s.mode.isMatch && manual.none { it.startMs == null }) {
            val inference = PointInference(court, s.scoringSession())
            val freeRallies = rallies.filter { r -> manual.none { m -> m.startMs != null && m.endMs != null && r.first().timeMs <= m.endMs && r.last().timeMs >= m.startMs } }
            val proposals = inference.infer(freeRallies, { callOf[it] }, manual.map { it.toRecord() }, settings.reviewThreshold)
            val merged = (manual + proposals.map { p ->
                PointEntity(
                    sessionId = s.id, order = 0, winnerA = p.point.winner == com.courtvision.scoring.Side.A, reason = p.point.reason.name,
                    startMs = p.point.startMs, endMs = p.point.endMs, confidence = p.point.confidence,
                    needsReview = p.point.needsReview, auto = true,
                )
            }).sortedBy { it.startMs ?: Long.MAX_VALUE }
            db.points().replaceAll(s.id, merged)
            points = db.points().forSession(s.id)
        }
        // side of each shot: map court half to team using the score state (players change ends)
        val session = s.scoringSession()
        val pointEnds = points.mapNotNull { it.endMs }
        val finalShots = shotRows.map { sh ->
            val near = sh.hitterY?.let { it > court.netY }
            if (near == null) sh else {
                val before = points.filter { (it.endMs ?: Long.MAX_VALUE) < sh.timeMs }
                val aNear = if (s.mode.isMatch && pointEnds.isNotEmpty()) session.board(before.toScoringEvents()).aOnNearEnd else true
                sh.copy(sideA = near == aNear)
            }
        }
        db.analysis().clearAutoShots(s.id)
        db.analysis().clearBounces(s.id)
        db.analysis().clearRallies(s.id)
        db.analysis().insertShots(finalShots)
        db.analysis().insertBounces(bounceRows)
        db.analysis().insertRallies(rallyRows)
        db.sessions().setAnalysis(s.id, AnalysisState.DONE, 1f, "done", null)
    }
}
