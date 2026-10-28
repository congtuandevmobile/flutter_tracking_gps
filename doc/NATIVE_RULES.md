# Native code rules

Mandatory for every change under `android/` and `ios/`. They were set by the
package owner; do not write native code that ignores them.

## Android (Kotlin)

- **Concurrency:** Kotlin Coroutines and Flow, not Thread or RxJava. Heavy work
  on `Dispatchers.IO`; results go back on `Dispatchers.Main`.
- **Null safety:** use the type system (`?`, `let`, `run`). Never use `!!`.
- **Memory:** release resources in `onDetachedFromEngine` (`FlutterPlugin`) and
  `onDetachedFromActivity` (`ActivityAware`). No `Context` / Activity leaks.

## iOS (Swift)

- **Concurrency:** Swift Concurrency (`async`/`await`) or GCD. Every result
  handed to Flutter is wrapped in `DispatchQueue.main.async { ... }`.
- **Protocol-oriented:** protocols and delegates for abstraction and tests
  (`LocationProviding`, `JournalStoring`, `StepProviding`).
- **Memory:** no strong reference cycles: `[weak self]` / `unowned self` in
  closures, especially `EventChannel` callbacks.

## Errors (both)

Never swallow an error. Every native failure is caught and turned into a
`FlutterError` (Swift: `PigeonError`) with three clear parts:

- `errorCode`: a stable identifier (`START_FAILED`, `MISSING_BACKGROUND_MODE`...)
- `errorMessage`: short and readable
- `errorDetails`: the stack trace or a map with the underlying error

Codes live in `TrackingErrors.kt` and `TrackingErrors.swift`. A failure that must
not stop the recording (a fix that could not be written) is logged and shown in
`NativeSnapshot.error`; it is still never silent.

## Where the code follows them

| Rule | Android | iOS |
|---|---|---|
| Async work off the main thread | `TrackingEngine` (`scope.launch`, `Dispatchers.IO` writes) | `TrackingEngine.queue` / `storeQueue` |
| Results on main | `FlutterTrackingGpsPlugin.command` (`Dispatchers.Main.immediate`) | `TrackingEngine.deliver` |
| No `!!` / no cycles | none used | `[weak self]` in every closure |
| Release on detach | `onDetachedFromEngine`, `detach()` | `detachFromEngine` |
| Errors | `Throwable.toFlutterError` | `trackingError(code:message:underlying:)` |
