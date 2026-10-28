# FlutterTrackingGpsRuntime.swift

**Layer:** iOS native (process-wide owner of the engine). The host app calls `FlutterTrackingGpsRuntime.bootstrapAtLaunch()` from `application(_:didFinishLaunchingWithOptions:)`: if a session was recording or paused when the process died it is replayed, the dead interval is credited to the clocks, and a recording collects again, BEFORE Flutter exists. Safe to call when nothing is open and more than once.

Opt-in options (off by default, not verified on a device): `useLiveUpdates` (iOS 17+ modern Core Location sequence) and `wakeOnSignificantChange` (needs Always). Whether iOS relaunches the app after the user force-quits it is not guaranteed by Apple.
