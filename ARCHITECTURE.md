# Architecture

Clean Architecture + MVVM, split so that every algorithm that matters is pure Kotlin and testable
on the JVM without a device.

```
app/                      Android (UI, camera, video, persistence, background jobs)
 ├─ ui/                   Jetpack Compose + Material 3 screens, ViewModels (session/)
 ├─ camera/               CameraX controller (manual controls, segmented recording), LiveAnalyzer
 ├─ processing/           VideoFrameDecoder (MediaCodec), MlModels (MediaPipe), AnalysisPipeline, AnalysisWorker
 ├─ data/                 Room entities/DAOs, DataStore settings, repository, mappers
 ├─ export/               CSV/JSON/PDF/ZIP (DataExporter) and MP4 (VideoExporter, Media3 Transformer)
 └─ util/                 storage, thermal status, offline text-to-speech
core/scoring  (JVM)       Tennis and pickleball state machines, ScoreBoard facade, undo/redo History
core/vision   (JVM)       Homography (normalised DLT), official court models, Kalman filter,
                          colour+motion ball detector, static-object filter, ball tracker, event
                          (bounce/hit) detector, line caller + reliability calibration, speed,
                          player tracker (IoU + clothing-colour re-identification), pose biomechanics,
                          stroke classifier, automatic court detector, placement advisor
core/analysis (JVM)       Rally segmentation, automatic point proposals, statistics, coach,
                          highlight selection (depends on scoring + vision)
```

The requested logical modules map to code as follows: Camera → `app/camera`; Computer Vision,
Ball Tracking, Player Tracking, Court Detection, Pose Estimation → `core/vision` (+ `MlModels`);
AI Inference → `app/processing/MlModels`; Match Analysis, Statistics → `core/analysis`; Scoring
Engine → `core/scoring`; Video Processing → `app/processing` + `app/export/VideoExporter`; Local
Database → `app/data`; Export → `app/export`; Testing → `*/src/test`, `app/src/androidTest`.
Wear OS and multi-camera are not implemented (see IMPLEMENTATION_STATUS.md).

## Key decisions

1. **Score = pure function of the point list.** Points are stored in order; the board is replayed
   from scratch on every change (`ScoringSession.board`). Corrections cannot accumulate errors,
   and they never touch the video. Undo/redo stores whole point lists.
2. **AI proposes, user decides.** Automatic points carry a confidence and `needsReview`; manual
   points are never overwritten; rallies overlapping manual points are skipped.
3. **No invented data.** Only observed detections create events; predicted (coasted) ball
   positions are stored with `observed=false`, drawn dashed, and ignored by event detection.
   Statistics keep sample sizes (`Ratio`) and show "—" when there is no data.
4. **Three-way line calls.** Probability from an explicit Gaussian error model (detection noise +
   half-frame temporal quantisation + ball radius, propagated through the homography Jacobian).
   Between the thresholds the result is INCONCLUSIVE. A `ReliabilityCalibration` can be fitted
   with annotated data; until then the UI says the probabilities are not calibrated.
5. **Resumable offline processing.** WorkManager foreground job; every 5 s of video the track and
   player samples are flushed with a checkpoint. Pause/cancel/crash/reboot resume from it.
   Thermal: the job waits while the device is SEVERE and stops at CRITICAL.
6. **Crash-safe recording.** Video is written in N-minute segment files; at start-up, sessions
   left in RECORDING are scanned and every readable segment is recovered.
7. **Memory.** Frames are decoded by MediaCodec and downscaled directly from the YUV planes into a
   reused `IntArray`; 4K video never creates full-resolution bitmaps in the analysis loop.
   ROI = calibrated court bounding box. Pose runs only at contact instants.
8. **Privacy by construction.** The merged manifest has no INTERNET permission (CI fails if it
   appears), cloud backup is disabled, no analytics.

## Analysis pipeline

```
segment files ─► MediaCodec ─► downscaled ARGB (480/640/960 px)
     ├─► ColorMotionBallDetector ─► StaticObjectFilter ─┐
     └─► MediaPipe EfficientDet (person, sports ball) ───┴► CandidateFusion ─► BallTracker (Kalman)
                     └─► PlayerTracker (IoU + appearance) ─► player samples (court metres)
track + players ─► EventDetector (bounces / hits) ─► RallySegmenter
bounces ─► homography ─► LineCaller (IN/OUT/INCONCLUSIVE + σ)        hits ─► pose at contact ─► ShotClassifier
                                         └──► PointInference (reviewable) ─► Room
```

## Coordinates

Court metres: X across (0 = left doubles sideline seen from the camera end), Y along (0 = far
baseline, length = near baseline). Lines are the outer edges of the painted lines (lines are "in").
Image coordinates are stored normalised (0..1) so calibration is resolution independent.
