# Changelog

## 0.2.0 – 2026-11-26
Simplification and usability (feedback from the first test on a phone).
- 3 bottom tabs (Home, Matches, Settings); big "Record a match" button; one-screen match setup with
  "More options" collapsed.
- Session screen: Summary (score + main statistics) · Video · Points · More (map, coach, editor, export).
- One-tap "how the point ended" (winner, unforced/forced error, ace, double fault) on every point and
  right after each point while recording.
- Full screen: recording, calibration and video player (system bars hidden, landscape).
- New calibration editor: numbered corners, magnifier, fine "touchpad" drag, 1-pixel arrows, corners
  allowed outside the picture, automatic fine alignment (refines ~11 px errors to < 1.5 px in tests).
- Settings reduced to the essentials; everything else under "Advanced"; camera manual controls collapsed.
- Fixed: analysis crashed on some phones (MediaPipe int8 detector on GPU) – detector now runs on CPU,
  and analysis continues with classical vision if a model fails.
- "Saved" confirmation messages.

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
