# Community research – SwingVision user reports

Research date: **2026-11-25**. Method: web search over public pages (App Store listing and reviews,
vendor help center, release notes quoted on the App Store, press, review aggregators).
**Limitation:** Reddit (r/swingvisionapp, r/10s) threads did not appear in the search results and
reddit.com could not be read from the build environment, so Reddit is *not* represented below.
Frequencies are qualitative ("seen in N independent sources") – no claim of statistical counts.

## Sources

| # | Source | Type | Accessed |
|---|---|---|---|
| S1 | https://apps.apple.com/us/app/swingvision-tennis-pickleball/id989461317 | App Store listing, reviews, release notes | 2026-11-25 |
| S2 | https://swingvision.zendesk.com/hc/en-us/articles/23466837300635-Video-Upload-Remaining-Stuck | Vendor help center (upload stuck) + user comments | 2026-11-25 |
| S3 | https://justuseapp.com/en/app/989461317/swingvision-a-i-tennis-app/reviews | Review aggregator | 2026-11-25 |
| S4 | https://justuseapp.com/en/app/989461317/swingvision-a-i-tennis-app/problems | Problem reports aggregator | 2026-11-25 |
| S5 | https://www.tennis.com/news/articles/fair-play-swingvision-puts-its-electronic-line-calling-to-the-test-in-tournament-play | Press (line-calling claims) | 2026-11-25 |
| S6 | https://www.imore.com/apps/level-up-your-serve-with-ai-powered-tennis-coaching-iphone-app-swingvision | Press review | 2026-11-25 |
| S7 | https://www.tomsguide.com/news/i-just-tried-apple-watch-with-swingvision-for-tennis-heres-what-happened | Press review | 2026-11-25 |
| S8 | https://appshunter.io/ios/app/989461317/reviews | Review aggregator | 2026-11-25 |
| S9 | https://insider.fitt.co/?p=37846 | Press release (Android launch, 2026-11-18) | 2026-11-25 |

## Problems reported and how Court Vision AI addresses them

| Problem | Seen in | Court Vision AI solution |
|---|---|---|
| Uploads/processing stuck at a percentage (2 %, 47 %, 88 %, 100 %), reload not working | S1, S2, S4 (release notes also list fixes for stuck uploads) | No upload at all: processing is local. Real progress with named stages, checkpoint every 5 s, pause / resume / cancel, automatic resume after process death, "reprocess from here". Errors are shown in plain language. |
| Dependence on Wi-Fi / cloud | S2 | Fully offline; app has no INTERNET permission. |
| Line calls wrong, especially serves near the centre line; camera blocked by players or the net | S1 (reviews and release notes), S5 | Three-way result (IN / OUT / INCONCLUSIVE) with a configurable threshold, per-call uncertainty ellipse and probability, slow-motion replay with zoom on the bounce, recreational disclaimer. Serve calls use the correct service box (deuce/ad from server position). |
| Serve tracking problems, data mixed up in practice sets | S3 | All events are editable; automatic points flagged for review; manual points are never overwritten; score always recomputed. |
| Battery drain, app running in the background | S3, S6 | No background activity except the user-visible analysis job (foreground notification). Economy processing mode; screen dimming while recording; thermal throttling. |
| Price / subscription for scoring and line calling | S1, S7 | Everything free, no subscription, no locked features. |
| Accuracy not sufficient for high-level coaching | S3 | Documented limitations; biomechanics labelled as 2D; validation pipeline described in IMPLEMENTATION_STATUS.md. |
| New issues after updates / reliability | S8 | Pure-Kotlin core with automated tests run on every build in CI. |
| Android availability (only launched on Google Play on 2026-11-18, import-based) | S9 | Native Android app with live recording, from Android 12. |

## Requested improvements (prompt section 23) – status

- Processing: local, resumable, staged progress, clear errors, partial reprocessing → **done**.
- Tracking on clay / dark scenes: colour profile is relative to hue (optic yellow vs. orange clay
  do not overlap), adaptive low-light thresholds, static-ball filter → **implemented, not validated
  on real clay/night footage**.
- Occlusion recovery: Kalman coasting with explicit "estimated" segments → **done (basic)**.
- Player identity across side changes: clothing-colour re-identification + manual merge → **done (basic)**.
- Scoring: easy correction, preserved corrections, no accumulated errors, review queue → **done**.
- Recording: segments, >2 h sessions, storage monitor, recovery, thermal → **done**
  (not tested on a physical device in this environment).
- Usability: one-tap quick start, search/sort/filter, exports, overlay options, offline → **done**.
