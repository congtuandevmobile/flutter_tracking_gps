import 'dart:async';
import 'dart:collection';
import 'dart:developer' as developer;

import 'package:flutter_tracking_gps/src/api/tracking_presenter.dart';
import 'package:flutter_tracking_gps/src/domain/tracking_snapshot.dart';

/// What a [TrackingPresenter] is told.
enum PresenterEvent { start, update, pause, resume, finish }

/// Delivers recording events to a [TrackingPresenter] without ever letting it
/// hurt the recording:
///
///  * lifecycle events (start, pause, resume, finish) are delivered in order and
///    never dropped (the queue is capped; on overflow the oldest are discarded
///    and reported);
///  * `update` events are COALESCED: only the newest waits, so a slow presenter
///    never builds a backlog, and a pending update is discarded once `finish`
///    is queued;
///  * every call has a time limit ([callTimeout]); a presenter that hangs or is
///    slow is reported. A timeout does NOT cancel the original call (a Dart future
///    cannot be cancelled), so the dispatcher never starts another call while the
///    hung one is still running: events queue (lifecycle events bounded, updates
///    coalesced) and are delivered, in order, once it finally completes;
///  * a throwing presenter, or a throwing error handler, is contained;
///  * [close] waits for delivery but never longer than a bound, so `dispose`
///    cannot hang on a presenter that never answers.
class PresenterDispatcher {
  PresenterDispatcher(
    this.presenter, {
    this.onError,
    this.callTimeout = const Duration(seconds: 5),
    this.maxQueued = 32,
  });

  final TrackingPresenter presenter;
  final void Function(Object error, StackTrace stack)? onError;
  final Duration callTimeout;
  final int maxQueued;

  final _lifecycle = Queue<(PresenterEvent, TrackingSnapshot)>();
  TrackingSnapshot? _pendingUpdate;
  bool _pumping = false;
  bool _closed = false;
  bool _finishQueued = false;
  Completer<void>? _idle;

  /// A call that exceeded its time limit and has not completed yet.
  Future<void>? _stuck;

  void lifecycle(PresenterEvent event, TrackingSnapshot snapshot) {
    if (_closed) return;
    if (event == PresenterEvent.finish) {
      _finishQueued = true;
      _pendingUpdate = null; // nothing stale after the final snapshot
    }
    if (_lifecycle.length >= maxQueued) {
      _lifecycle.removeFirst();
      _report(
        StateError('presenter queue overflow: the oldest event was dropped'),
        StackTrace.current,
      );
    }
    _lifecycle.add((event, snapshot));
    _pump();
  }

  void update(TrackingSnapshot snapshot) {
    if (_closed || _finishQueued) return;
    _pendingUpdate = snapshot;
    _pump();
  }

  /// Waits until everything queued was delivered (or timed out), for at most
  /// [maxWait]; then stops accepting events.
  Future<void> close({Duration? maxWait}) async {
    final limit = maxWait ?? callTimeout * 2;
    if (_pumping) {
      final idle = _idle ??= Completer<void>();
      await idle.future.timeout(limit, onTimeout: () {});
    }
    _closed = true;
  }

  void _pump() {
    if (_pumping || _stuck != null) return;
    _pumping = true;
    unawaited(() async {
      while (true) {
        final (PresenterEvent, TrackingSnapshot)? next;
        if (_lifecycle.isNotEmpty) {
          next = _lifecycle.removeFirst();
        } else if (_pendingUpdate != null) {
          next = (PresenterEvent.update, _pendingUpdate!);
          _pendingUpdate = null;
        } else {
          break;
        }
        final timedOut = await _deliver(next.$1, next.$2);
        if (timedOut) break; // never overlap with the hung call: wait for it
      }
      _pumping = false;
      final idle = _idle;
      _idle = null;
      if (idle != null && !idle.isCompleted) idle.complete();
    }());
  }

  /// Returns true when the call exceeded its time limit and is still running.
  Future<bool> _deliver(PresenterEvent event, TrackingSnapshot s) async {
    final Future<void> call;
    try {
      call = switch (event) {
        PresenterEvent.start => presenter.onStart(s),
        PresenterEvent.update => presenter.onUpdate(s),
        PresenterEvent.pause => presenter.onPause(s),
        PresenterEvent.resume => presenter.onResume(s),
        PresenterEvent.finish => presenter.onFinish(s),
      };
    } on Object catch (error, stack) {
      _report(error, stack);
      return false;
    }
    final outcome = Completer<(Object, StackTrace)?>();
    unawaited(
      call.then<void>(
        (_) {
          if (!outcome.isCompleted) outcome.complete(null);
        },
        onError: (Object error, StackTrace stack) {
          if (!outcome.isCompleted) {
            outcome.complete((error, stack));
          } else {
            _report(error, stack); // it failed after we gave up on it
          }
        },
      ),
    );
    var timedOut = false;
    final timer = Timer(callTimeout, () {
      timedOut = true;
      if (!outcome.isCompleted) outcome.complete(null);
    });
    final failure = await outcome.future;
    timer.cancel();
    if (failure != null) _report(failure.$1, failure.$2);
    if (!timedOut) return false;
    _report(
      TimeoutException(
        'presenter ${event.name} did not finish within $callTimeout',
        callTimeout,
      ),
      StackTrace.current,
    );
    // Keep the hung call as the "stuck" one; resume once it ends, however it ends.
    final stuck = call.then<void>((_) {}, onError: (Object _) {});
    _stuck = stuck;
    unawaited(
      stuck.whenComplete(() {
        _stuck = null;
        _pump();
      }),
    );
    return true;
  }

  void _report(Object error, StackTrace stack) {
    final handler = onError;
    if (handler == null) {
      developer.log(
        'presenter failed',
        name: 'flutter_tracking_gps',
        error: error,
        stackTrace: stack,
      );
      return;
    }
    try {
      handler(error, stack);
    } on Object catch (handlerError, handlerStack) {
      developer.log(
        'presenter error handler failed',
        name: 'flutter_tracking_gps',
        error: handlerError,
        stackTrace: handlerStack,
      );
    }
  }
}
