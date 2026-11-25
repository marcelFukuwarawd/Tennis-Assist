# Implementation status – v0.1.0 (2026-11-25)

Legend: ✅ done (implemented, compiles, covered by tests where possible) · 🟡 partial ·
🧪 experimental · ⛔ pending (disabled / not present) · ❗ not validated on real footage/devices.

**Important:** no physical Android device or emulator was available in the development environment
(no hardware virtualisation). Everything below was built and unit/integration-tested on the JVM,
compiled into debug and release APKs, and lint-checked. **No on-device test was run.** Runtime
behaviour on phones (camera, MediaCodec, MediaPipe, Transformer) must be verified by installing the APK.

## Phase overview

| Phase | Scope | Status |
|---|---|---|
| 1 | Architecture, UI, local DB, CI build | ✅ |
| 2 | Camera, recording, import, playback | ✅ ❗ |
| 3 | Calibration and court detection | ✅ (auto-detector 🟡: classical, verified on rendered courts only) |
| 4 | Ball and player tracking | 🟡 ❗ classical + MediaPipe detector; no TrackNet |
| 5 | Shots and rallies | 🟡 ❗ rule-based classifier |
| 6 | Scoring and manual correction | ✅ |
| 7 | Statistics and heat maps | ✅ (values depend on phase 4/5 quality) |
| 8 | Video editor and highlights | ✅ ❗ |
| 9 | Line calling and speed | 🟡 ❗ uncalibrated probabilities, average speed only |
| 10 | Biomechanics and coach | 🟡 2D only; coach rule-based |
| 11 | Multi-camera and Wear OS | ⛔ |
| 12 | Optimisation and tests | 🟡 JVM tests only |

## Features

### Done ✅
- 5-tab navigation, dark/light theme, lime accent, Portuguese (Brazil) + English, per-app language.
- No login/accounts/ads/subscriptions; **no INTERNET permission** (removed from merged manifest, CI enforces).
- Session setup: tennis singles/doubles, pickleball singles/doubles, 6 training modes; optional local
  player labels; rules (best of 1/3/5, no-ad, match tie-break, short sets; pickleball side-out/rally,
  11/15/21, 1/3/5 games); first server; surface; handedness. One-tap quick start.
- Recording: lens list (ultrawide/main/tele by 35 mm-equivalent focal length), 480p–4K (as supported),
  24/30/60/120 fps when the camera reports a fixed range, zoom, EV, manual ISO + shutter, manual focus,
  white-balance presets, AE/AWB lock, tap-to-focus / long-press lock, countdown, optional microphone,
  touch lock, screen dimming, landscape lock, keep-screen-on, segmented files (5–20 min),
  minutes-left indicator, low-storage warning and automatic safe stop (< 300 MB), thermal indicator,
  live AI disabled when SEVERE, safe stop when CRITICAL, interrupted-recording recovery at start-up.
- Placement assistant: level (gravity sensor), tilt, low light, court not fully visible, centre, zoom, camera too low.
- Calibration: 4-corner drag editor with live projected court model (on recorded video or the live
  camera frame), automatic proposal, line-agreement score. Official ITF/USA Pickleball dimensions,
  service boxes, NVZ.
- Import from any video file via the system picker (persisted permission), metadata probe.
- Analysis job: queue, foreground notification, real progress + stages, pause/resume/cancel,
  checkpoint resume, "reprocess from here"/"all", plain-language errors.
- Playback: multi-segment gapless player, 0.125×–2×, frame step, instant slow-motion replay,
  zoom on the last bounce, trajectory overlay (observed vs. estimated), bounce markers, bookmarks.
- Scoring engines (tennis: 0/15/30/40, deuce/advantage, no-ad, games, sets, tie-break 1-2-2 rotation,
  match tie-break, best of 1/3/5, custom games per set, doubles serve rotation, end changes;
  pickleball: side-out with server 1/2 and "0-0-2" start, rally scoring, win-by-2, multi-game).
  Live big buttons, undo/redo, voice score. Edit any point: winner, reason, serve number, delete,
  insert, server correction, mark reviewed; later scores recomputed; video untouched.
- Statistics per session / set: points, aces, double faults, winners, forced/unforced errors (manual),
  1st-serve %, points won on 1st/2nd serve, service/return points, break points, shots by type and
  accuracy, depth, max/avg/serve speed, rally count/avg/longest/duration; "—" when no data.
- Map: bounces with uncertainty ellipses, heat map, player-position heat map, shot directions; filters
  by player, call, shot type, min speed; tap → video; least-explored zone.
- Coach: evidence-based insights with minimum sample sizes, video links, drills; weekly goals.
- Editor: auto presets (highlights, remove dead time, best serves/forehands/backhands, winners, longest
  rallies, fastest shots); reorder, trim, split, speed, favourite, hide/restore, undo/redo; MP4 export
  16:9 / 9:16 / 1:1 / 4:5 with overlays (score, names, speed, shot type, rally, label) to Movies/CourtVisionAI.
- Precision training: target-zone editor + presets; live voice HIT / OUT / SHORT / LONG / MISS; history.
- Files: rename, favourites, search, sort, filter, notes, delete, CSV/JSON/PDF export & share, ZIP
  backup (optionally with videos) and restore, in-app privacy policy and licenses.

### Partial / experimental 🟡🧪
- **Ball detection** (🟡❗): colour + frame-difference detector fused with MediaPipe EfficientDet-Lite0
  "sports ball" (the COCO detector is weak on tiny far balls). Kalman tracking with gating and
  static-ball rejection. No neural temporal tracker (TrackNet).
- **Bounce/hit detection** (🟡❗): velocity-discontinuity detector. Hits are recognised when the kink is
  in a player's racket zone → requires person detection; without it, hits close to the camera can be
  confused with bounces. Net contact and let detection are **not** implemented.
- **Line calling** (🟡❗): probabilities come from the error model and are **not calibrated** with
  annotated validation data (the `ReliabilityCalibration` code exists; data is missing). Lens distortion
  is not corrected (homography assumes a pinhole camera).
- **Speed** (🟡): average ground speed between contact and bounce (not peak speed). Height/depth of the
  ball in 3D is not estimated.
- **Player tracking** (🟡❗): greedy IoU + clothing histogram re-identification, max 4 players, manual
  merge. Reaction time, offensive/defensive positioning and recovery metrics are **not** computed.
- **Stroke classification** (🟡❗): rules from pose + geometry (serve, smash, volley, lob, drop, return,
  forehand/backhand, one/two-handed backhand, dink, drive, third-shot drop). Spin type
  (topspin/slice/flat) and passing shot are **not** classified. Audio of impact not used.
- **Automatic scoring** (🟡❗): rule-based proposals from the last bounce of each rally, flagged for review.
  Faults/double faults on serve are not inferred automatically (set them in the Score tab).
- **Live analysis** (🧪): live ball tracking on the 640×360 analysis stream for voice line calls and
  precision training; works only if the device supports Preview + Video + Analysis together.
- **Biomechanics** (🟡): MediaPipe pose on a chosen frame, 2D joint angles, angle tool, lines, side-by-side
  comparison and normalised pose overlay. Not 3D, not validated.
- **Coach** (🟡): rule-based (no local LLM). Weekly goals are computed from the last 7 days.

### Pending ⛔ (not present in this version)
- Multi-camera mode (Wi-Fi Direct/LAN/BLE pairing, sync, 3D reconstruction).
- Wear OS module.
- Optional 3D trajectory view.
- TrackNet-class neural ball tracker and its training pipeline (see "Models needed").
- 1440p recording option (CameraX `Quality` has no 1440p level) and high-speed 120 fps sessions
  (needs CameraX high-speed API, newer than the version used).
- Choice of an arbitrary storage directory for recordings (videos are kept in the app-specific external
  folder; exports go to the public Movies folder).
- NDK/C++ code: not needed so far (Kotlin loops on downscaled frames); revisit after on-device profiling.

## Models

| Model | Status |
|---|---|
| EfficientDet-Lite0 int8 (person / sports ball) | ✅ bundled, SHA-256 verified in CI |
| Pose Landmarker Lite float16 | ✅ bundled, SHA-256 verified in CI |
| Ball tracker (TrackNet family) | ⛔ needed for robust small/fast-ball tracking |

**Models needed / training pipeline (not done):** a heat-map ball detector (TrackNet-like, 3 stacked
frames, ~288×512 input) trained on tennis/pickleball broadcast + phone footage with per-frame ball
annotations (visible/occluded flags), exported to LiteRT with INT8 calibration. Required data: a few
thousand annotated rally clips covering clay/hard/grass/indoor, day/night, multiple camera heights.
The same annotated set (with bounce positions measured on court) is needed to fit the line-call
`ReliabilityCalibration` and to measure bounce error and speed error.

## Test report

Environment: Linux x86_64 container, JDK 21 (target JVM 17), Android SDK 35, Gradle 8.11.1.
Command: `./gradlew test lintDebug assembleDebug assembleRelease`.

| Suite | Tests | Result |
|---|---|---|
| core:scoring – TennisEngineTest | 13 | ✅ pass |
| core:scoring – PickleballEngineTest | 4 | ✅ pass |
| core:vision – VisionTest (homography, calibration, Kalman, bounce on 3D-projected physics, hit at player, line calls, service boxes, detector, tracker, speed, placement, angles) | 14 | ✅ pass |
| core:analysis – AnalysisTest (rallies, point inference, statistics, coach, highlights) | 5 | ✅ pass |
| core:analysis – SyntheticRallyIntegrationTest (rendered frames, full chain) | 2 | ✅ pass |
| app – MappersTest (JVM) | 4 | ✅ pass |
| app – DatabaseTest (instrumented) | 1 | compiled, **not run** (no device) |
| Android lint | – | 0 errors, 94 warnings (mostly "newer dependency available") |

**Synthetic end-to-end metrics** (pinhole camera 4 m high, 4 m behind the baseline, 640×360, 60 fps,
ball rendered with physical radius, gravity, one still ball lying on the court as a distractor):

| Metric | Value |
|---|---|
| Ball detection recall (within 3 px) | 92.4 % |
| Ball detection precision | 100 % |
| Bounces detected / true | 3 / 3 (0 false) |
| Bounce localisation error | 0.27 m, 0.02 m, 0.35 m |
| Line calls | IN, IN, OUT – all correct |
| Hits detected (with player boxes) | 3 / 3 |
| Point inference | correct winner (B, ball out) |
| Auto court detection (wide camera) | corner errors 0.2–4.0 px, line agreement 82 % |

With a farther camera (8 m behind, 6 m high) recall dropped to 81 % and the out ball 1.2 m long became
INCONCLUSIVE (σ ≈ 96 cm) – the system refused to force a call, as designed.
These numbers come from **synthetic** frames and do **not** represent real-world accuracy.
Not measured (needs real annotated videos / devices): real detection precision/recall, identity
switches, real bounce and speed error, stroke-classification accuracy, scoring accuracy.

Real-situation tests requested (singles/doubles, clay, night, low resolution, occlusion, bad placement,
interrupted recording, full storage, overheating, interrupted processing, offline, manual corrections,
video export) and device/SoC matrix (Android 12–16, Snapdragon/MediaTek/Exynos/Tensor): **not run** –
no devices. Emulator vs device results: none available.

## Performance report

| Item | Value (measured here) |
|---|---|
| Debug APK (arm64 + x86_64, not minified) | 102 MB |
| Release APK (arm64 only, R8 minified) | 31.7 MB |
| Bundled models | 10.4 MB |
| Unit test suite runtime (JVM) | ~10 s |

On-device FPS, memory, processing time, temperature and battery: **not measured** (no device).

## Known limitations
- Monocular camera: no ball height; airborne projections are not ground positions (handled by only
  locating bounces); lens distortion uncorrected (ultrawide lenses reduce accuracy).
- A single camera behind one baseline sees the far court small: far-side calls are less certain.
- Line-call probabilities are model-based, not validated.
- Recording runs while the recording screen is open (no background recording); there is a short gap
  (~hundreds of ms) between segment files.
- Restore replaces the current database and restarts the app.
