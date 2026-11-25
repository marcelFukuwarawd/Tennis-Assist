# Changelog

## 0.1.0 – 2026-11-25
First version.
- Multi-module project (app, core:scoring, core:vision, core:analysis) built by GitHub Actions.
- Recording with CameraX: lens choice, 720p/1080p/4K, 30/60 fps (as supported), zoom, exposure,
  manual ISO/shutter/focus, white balance, AE/AWB locks, countdown, touch lock, dimmed screen,
  segmented recording with crash recovery, storage and thermal monitoring.
- Placement assistant (level, tilt, light, court framing) and manual/automatic court calibration.
- Offline analysis queue (pause, resume, cancel, reprocess from a point) with hardware decoding,
  colour/motion + neural ball detection, Kalman tracking, player tracking, bounce/hit detection,
  line calls with uncertainty, speed estimates, stroke classification with pose, rallies and
  reviewable automatic score proposals.
- Tennis and pickleball scoring engines with full correction, undo/redo.
- Statistics, heat maps, coach insights, weekly goals, precision training, biomechanics 2D tools.
- Non-destructive video editor with automatic highlights and MP4 export in 16:9, 9:16, 1:1, 4:5.
- CSV, JSON, PDF exports; local ZIP backup/restore; Portuguese and English UI; dark/light themes.
- Fixed: homography "in front of camera" test depended on an arbitrary sign (found by the
  synthetic end-to-end test).
