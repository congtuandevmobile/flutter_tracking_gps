# flutter_tracking_gps

Own GPS tracking engine for UpRace (a planned replacement of
`flutter_background_geolocation`). Read `docs/FLOW.md` before changing the engine
and `docs/NATIVE_RULES.md` before touching `android/` or `ios/`.

- The distance rules live in `tracking-core` (Kotlin, shipped) and have a Dart
  reference twin in `lib/src/engine`. Change a rule in BOTH and add it to a
  fixture in `test/fixtures` so the two cannot drift apart.
- Native code collects, persists and feeds the core through `TrackingCoreBridge`;
  it never decides distance itself.
- Native code MUST follow `docs/NATIVE_RULES.md` (coroutines/Flow or GCD, no `!!`,
  weak self, results on main, every error as `FlutterError` with code, message,
  details).
- Pigeon: edit `pigeons/tracking_api.dart`, then
  `dart run pigeon --input pigeons/tracking_api.dart`. Never edit `*.g.*` files.
- Every threshold in `TrackingConfig` is an unmeasured starting value; say so
  when you change one.
- Do not commit or push without the owner's approval.
- `tracking-core/` is the shared Kotlin core (see `docs/KMP.md`). Algorithms go
  there once, with a test in `commonTest`; do not re-implement one in Swift,
  Kotlin-Android or Dart. Rebuild the iOS framework with `tool/build_core.sh`.
- **Every Swift and Kotlin file has an English `.md` next to it with the same base
  name** (`HmmMapMatcher.kt` → `HmmMapMatcher.md`): purpose, how it works, every
  class, property and function, limits, who uses it. Create it with the file and
  keep it in sync when the file changes. Generated Pigeon files get a short note
  pointing to `pigeons/tracking_api.dart`.
- Every time the engine sees (`fix.timeMs`, `nowMs`, `atMs`) must come from the SAME
  monotonic clock (Android `elapsedRealtime`, iOS `CLOCK_MONOTONIC`), never from
  the wall clock. Keep the wall time only for display and storage.
- A step estimate always carries its segment id.
- Steps: native only reads the sensor; `StepSession` (accounting) and
  `StrideEstimator` (metres) live in `tracking-core` with a Dart twin. A step
  estimate is always PENDING until returning GPS confirms it. See `docs/FLOW.md`.
- Live activity / notification: the SDK depends on no such package; apps plug one
  in through `TrackingPresenter` (`docs/PRESENTER.md`). The Android default
  notification is native (`TrackingService`) so it survives the Flutter engine.
