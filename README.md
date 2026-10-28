# flutter_tracking_gps

Own GPS tracking engine. Native code collects raw fixes and keeps them
on disk (iOS CoreLocation; Android Fused location inside a foreground service);
a deterministic distance engine (shared Kotlin core, `tracking-core`) running
natively decides what counts as distance; Dart sends commands and shows the
snapshots.

**Status: 0.0.1-beta (first beta). The shared Kotlin core runs natively on both platforms
(distance keeps being measured when the Flutter engine is gone); every input is
journaled so a killed recording is restored exactly; builds and unit tests pass;
not tested on a device yet.** Read `doc/FLOW.md` for what happens in every
situation and what is not done.

## Install

```yaml
dependencies:
  flutter_tracking_gps: ^0.0.1-beta
```

Releases are tagged `v<version>` from `pubspec.yaml` (for example `v0.0.1-beta`,
`v0.0.1-beta.1`, `v0.0.1-beta.2`); see `CHANGELOG.md`.

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
final result = await session.finish(); // FINAL: journal durable, steps finalized
print(result.activeTime);              // workout time, pauses excluded
print(result.uncreditedGap);           // GPS time nothing was credited for (reported, not hidden)
print(await session.report());         // coordinate-free timeline (from native)
final route = await session.persistedFixes(); // the raw route, oldest first
await session.clearPersisted();        // after the activity was saved
```

After the process died: `await gps.listSessions()` shows what is on the device and
`await session.restore(sessionId: 'activity-123')` replays its journal with the
configuration it ran with (distance, steps, stride and times come back exactly; the
session is paused and `resume()` continues it in a new segment).

## Steps and live activity

```dart
await gps.requestStepsPermission();            // Android 10+ / iOS Motion & Fitness
final session = gps.createSession(
  stepsEnabled: true,                          // steps help while GPS is weak
  presenter: MyLiveActivityPresenter(),        // optional, see doc/PRESENTER.md
);
```

Steps never become distance on their own: while GPS is weak they make a pending
estimate that only counts as far as the GPS that returns confirms it. The SDK always runs its own Android foreground service with a plain notification
(text from `notificationText`; it keeps the recording alive). A third-party live
activity plugs in through `TrackingPresenter` and only draws: it never decides
whether the recording survives.

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
android/ ios/       native engines (follow doc/NATIVE_RULES.md)
pigeons/            the channel contract
example/            a small recorder screen
```

`finish()` returns a FINAL snapshot (`RecordingState.finished`); while it is being
finalized the state is `finishing`. `snapshot.isDurable` says whether everything shown
has reached the disk.

What is proven by unit tests and what still needs a real device: see `doc/DEVICE_TESTS.md`.
