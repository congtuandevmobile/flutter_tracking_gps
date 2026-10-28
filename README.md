# flutter_tracking_gps

Own GPS tracking engine for UpRace. Native code collects raw fixes and keeps them
on disk (iOS CoreLocation; Android Fused location inside a foreground service);
a deterministic distance engine (shared Kotlin core, `tracking-core`) running
natively decides what counts as distance; Dart sends commands and shows the
snapshots.

**Status: first version. The shared Kotlin core runs natively on both platforms
(distance keeps being measured when the Flutter engine is gone); builds and
unit tests pass; not tested on a device yet.** Read `docs/FLOW.md` for what happens in every
situation and what is not done.

## Why

The distance is computed from raw fixes (position, Doppler speed, accuracy), not
read from a black-box odometer. GPS is the truth: a step estimate only becomes
distance when the GPS that returns confirms it, so a phone shaken in the hand
without GPS adds nothing.

## Use

```dart
final gps = FlutterTrackingGps();

if (await gps.permissionStatus() != PlatformPermission.granted) {
  await gps.requestPermission();
}

final session = gps.createSession(
  config: TrackingConfig.forSport(TrackingSport.running),
  notificationTitle: 'Recording',
);
session.updates.listen((s) => print('${s.totalMeters} m, gps ${s.quality.name}'));
// adaptiveSampling: true lets native sample less often while standing still.

await session.start(sessionId: 'activity-123');
await session.pause();
await session.resume();               // new segment: nothing is bridged
final result = await session.finish(); // final TrackingSnapshot
print(await session.report());         // coordinate-free timeline (from native)
await session.clearPersisted();        // after the activity was saved
```

After the process died: `await session.restore(sessionId: 'activity-123')`
(native replays the persisted fixes; the session comes back paused and
`resume()` continues it in a new segment).

## Steps and live activity

```dart
await gps.requestStepsPermission();            // Android 10+ / iOS Motion & Fitness
final session = gps.createSession(
  stepsEnabled: true,                          // steps help while GPS is weak
  presenter: MyLiveActivityPresenter(),        // optional, see docs/PRESENTER.md
);
```

Steps never become distance on their own: while GPS is weak they make a pending
estimate that only counts as far as the GPS that returns confirms it. Without a
presenter, Android shows the SDK's own foreground notification (it keeps the
recording alive); a third-party live activity plugs in through
`TrackingPresenter`.

## Setup

- **iOS (minimum 14):** `Info.plist` → `NSLocationWhenInUseUsageDescription`,
  `UIBackgroundModes: [location]` and, for steps, `NSMotionUsageDescription`.
- **Android:** nothing to add; the plugin manifest declares the permissions and the
  foreground service. Start from the foreground.

## Layout

```
lib/src/domain      value types (fix, config, snapshot, ledger)
lib/src/engine      Dart reference twin of the distance rules (pure, tested)
tracking-core/      the shipped distance engine + algorithms (Kotlin Multiplatform)
lib/src/api         TrackingSession, FlutterTrackingGps
lib/src/platform    TrackingPlatform + Pigeon bridge
android/ ios/       native engines (follow docs/NATIVE_RULES.md)
pigeons/            the channel contract
example/            a small recorder screen
```
