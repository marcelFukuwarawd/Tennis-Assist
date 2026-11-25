# Third-party components

All dependencies are free, actively maintained and compatible with distribution in a free app.
No proprietary SDK, analytics, advertising or network service is included.

## AI models (bundled in `app/src/main/assets/models`)

| Model | Version / variant | Origin | License | Dataset | Input | Format | Size | SHA-256 |
|---|---|---|---|---|---|---|---|---|
| EfficientDet-Lite0 | MediaPipe object detector, int8, "latest" as of 2026-11-25 | https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite | Apache-2.0 | COCO 2017 | 320×320 RGB | TFLite + metadata | 4,602,795 B | `0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb` |
| Pose Landmarker Lite | MediaPipe, float16, "latest" as of 2026-11-25 | https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task | Apache-2.0 | Google pose dataset (see MediaPipe model card) | 256×256 RGB | MediaPipe `.task` | 5,777,746 B | `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a` |

The models are used unmodified (no conversion was needed: both are already LiteRT/TFLite files for
MediaPipe Tasks). `tools/verify_models.sh` checks the hashes in CI (`models/models.json`).

## Libraries

| Library | Version | License |
|---|---|---|
| Android Gradle Plugin | 8.7.3 | Apache-2.0 |
| Kotlin, kotlinx.coroutines 1.9.0, kotlinx.serialization 1.7.3 | 2.0.21 | Apache-2.0 |
| AndroidX Core / AppCompat / Activity / Lifecycle | 1.15.0 / 1.7.0 / 1.9.3 / 2.8.7 | Apache-2.0 |
| Jetpack Compose (BOM) + Material 3 + Material Icons Extended | 2024.12.01 | Apache-2.0 |
| Navigation Compose | 2.8.5 | Apache-2.0 |
| CameraX (core, camera2, lifecycle, video, view) | 1.4.1 | Apache-2.0 |
| Media3 (ExoPlayer, UI, Transformer, Effect) | 1.5.1 | Apache-2.0 |
| Room | 2.6.1 | Apache-2.0 |
| WorkManager | 2.10.0 | Apache-2.0 |
| DataStore Preferences | 1.1.1 | Apache-2.0 |
| MediaPipe Tasks Vision | 0.10.20 | Apache-2.0 |
| JUnit (tests only) | 4.13.2 | EPL-1.0 |
| AndroidX Test (tests only) | 1.2.1 / 1.6.2 | Apache-2.0 |

## Not used (and why)

- **OpenCV**: the needed algorithms (homography/DLT, Kalman filter, connected components, line mask)
  are implemented in pure Kotlin in `core/vision` (≈1.5k lines, unit-tested), avoiding ~20 MB of
  native libraries per ABI.
- **ONNX Runtime**: no model in the app needs it; MediaPipe/LiteRT covers both models.
- **TrackNet / TrackNetV2 / TrackNetV3**: see IMPLEMENTATION_STATUS.md – no pretrained weights
  with a license and a mobile-ready format were verified, so they are not bundled.
