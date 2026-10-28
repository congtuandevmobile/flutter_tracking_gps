/// Own GPS tracking engine for UpRace.
///
/// Native code collects raw fixes (iOS CoreLocation, Android Fused location in
/// a foreground service) and journals them; a shared Kotlin core (the same code
/// on both platforms) decides what counts as distance. Dart sends commands and
/// shows the result; its own engine is a reference and test oracle.
library;

export 'src/api/flutter_tracking_gps.dart';
export 'src/api/presenter_dispatcher.dart' show PresenterDispatcher, PresenterEvent;
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
        PlatformDistance,
        PlatformPermission,
        PlatformSnapshot,
        PlatformState,
        TrackingPlatform;
