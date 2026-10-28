/// Own GPS tracking engine for UpRace.
///
/// Native code collects raw fixes (iOS CoreLocation, Android Fused location in
/// a foreground service) and persists them; a deterministic Dart engine decides
/// what counts as distance.
library;

export 'src/api/flutter_tracking_gps.dart';
export 'src/api/tracking_presenter.dart' show TrackingPresenter;
export 'src/api/tracking_session.dart' show TrackingSession, TrackingStateError;
export 'src/diagnostics/tracking_log.dart' show TrackingLog;
export 'src/domain/distance_ledger.dart' show DistanceLedger, LedgerEntry;
export 'src/domain/tracking_config.dart';
export 'src/domain/tracking_fix.dart' show TrackingFix;
export 'src/domain/tracking_snapshot.dart' show TrackingSnapshot;
export 'src/domain/tracking_state.dart';
export 'src/engine/gps_distance_engine.dart' show FixResult, GpsDistanceEngine;
export 'src/platform/tracking_platform.dart'
    show
        AndroidForegroundMode,
        PlatformDistance,
        PlatformPermission,
        PlatformSnapshot,
        PlatformState,
        TrackingPlatform;
