# Shared Kotlin core (`tracking-core`)

Pure algorithms written once in Kotlin Multiplatform, used by both platforms, so
there is never a second Kalman / fusion implementation that disagrees with the
first.

```
tracking-core/src/commonMain/kotlin/tracker/   plain Kotlin, no platform API,
                                               no third-party library
  ├─ algorithms/geometry     GeoMath, DouglasPeucker
  ├─ algorithms/filtering    Kalman2D, LowPassFilter, OutlierRejector
  ├─ algorithms/motion       StopDetector, StrideEstimator, StepSession
  ├─ algorithms/fusion       GapReconciler
  ├─ algorithms/power        AdaptiveSamplingPolicy
  ├─ engine                  GpsDistanceEngine, DistanceLedger, TrackingLog
  ├─ journal                 SessionRecorder, JournalCodec, Wire (CRC-32), records:
  │                          write-ahead journal + exact replay (see JOURNAL.md)
  ├─ model                   TrackingFix, TrackingConfig, TrackingSnapshot, ...
  └─ auxiliary               Dbscan, KMeans, RoadGraph (Dijkstra, A*), HmmMapMatcher
tracking-core/src/commonTest/kotlin/tracker/   tests, run on the JVM (fast)
```

## How each platform uses it

- **Android:** `android/build.gradle.kts` adds `tracking-core/src/commonMain/kotlin`
  to the plugin's source dirs. Plain common Kotlin compiles as Android Kotlin;
  there is no second Gradle project to wire into the host app.
- **iOS:** `tool/build_core.sh [debug|release]` builds `TrackingCore.xcframework`
  and copies it to `ios/Frameworks/` (git-ignored); the podspec vendors it.
  Swift calls it as `GeoMath.shared.haversineMeters(lat1:lon1:lat2:lon2:)`.
- **Tests:** `cd tracking-core && ./gradlew jvmTest`.

Native code (CoreLocation, Fused location, foreground service, persistence) stays
in Swift/Kotlin and follows `doc/NATIVE_RULES.md`.

## Verified on this machine

- `./gradlew jvmTest` passes; `assembleTrackingCoreDebugXCFramework` builds
  (first run ~5 min: it downloads the Kotlin/Native toolchain).
- The example app builds for Android (debug APK) and the iOS simulator with a
  call into the core from each side.

## What is NOT in the tree on purpose

| Idea | Why not |
|---|---|
| Trilateration, A-GPS | The GNSS chip and the OS do this; an app only receives the fix |
| Map matching (geometric, topological, HMM) | Needs a road graph the app does not have; it also changes the distance, and runs/trails are often off-road. If wanted, do it server-side on the finished route |
| Dijkstra / A\* routing | Route planning, not recording |
| BLE source | No sensor in scope |
| K-Means | Not a fit for stop detection; use a stay-point detector (online, O(1)) |
| Barometer / elevation fusion | Valid, but a separate feature (ascent) |

## Status

Wired end to end. `TrackingCoreBridge` (`bridge/`) is the one door: Android's
`TrackingEngine` and iOS's `TrackingEngine` create a bridge per session, feed it
every fix and a 1 Hz tick, and publish its snapshot to Dart over the Pigeon event
channel. Adaptive sampling and the silence limit live in the bridge.

Verified here: 177 JVM tests of the core (including the bridge, the audit
regressions and the shared CSV fixtures, which the Dart engine also passes), 52
JVM unit tests of the Android layer (fake location source and store, real file
store, foreground handshake), 41 XCTests of the iOS engine (real Kotlin core
through the XCFramework), 87 Dart tests, and debug builds of the
example app for Android and the iOS simulator.

Not verified: anything on a physical device (battery, background survival, OEM
behaviour), and every threshold is an unmeasured starting value.

Every Kotlin and Swift file has an English `.md` next to it describing it.
