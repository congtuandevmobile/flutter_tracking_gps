

`CoreLocationProvider(monitorSignificantChanges:)` can also monitor significant location changes (a wake source, needs Always). `LiveUpdatesLocationProvider` (iOS 17+) uses `CLLocationUpdate.liveUpdates`, `CLBackgroundActivitySession` and, on iOS 18, `CLServiceSession`. Both options are off by default and not verified on a device.
