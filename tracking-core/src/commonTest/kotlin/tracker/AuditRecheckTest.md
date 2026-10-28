# AuditRecheckTest.kt

Regression probes for fcee633. `AuditSink` captures journal lines without native I/O; `auditSettings()` builds deterministic settings. Nine tests cover comma escaping, unjournaled active-time ticks, weak-start/resume coverage, measurement-time validation, cross-gap pending/evidence and stationary moving time. All nine fail on the baseline. Uses the repository TestFixes helpers and kotlin.test. These are behavioral expectations, not a production fix.
