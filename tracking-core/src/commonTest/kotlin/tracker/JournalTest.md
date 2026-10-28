# JournalTest.kt

`JournalCodecTest`: every record type round-trips (including `|`, `=`, newline and `%` in text), a flipped character is detected, a half-written last line is `TRUNCATED_TAIL` with the prefix trusted, a damaged middle line is `CORRUPT`, a newer version and an empty journal are refused.
`JournalReplayTest`: replaying the journal reproduces the live snapshot exactly (a scenario with fixes, steps, a GPS gap through a tick, a bridged gap, pause and resume, with and without adaptive sampling); a session that was running comes back paused with a journaled pause; a fix rejected live is rejected in replay; the header's configuration wins; a crash mid-write loses only the last record; nothing usable gives no recorder; the route is readable; `flush` is the host's barrier.

Added: the time the process was dead is not workout time; a reboot (the monotonic clock restarts) neither loses nor invents time; a journal of another engine version is replayed but flagged; reprocess may differ from restore and writes nothing; moving and uncredited time are parts of the active time. `ReplayPerformanceTest` (jvmTest) measures a ten-hour replay.

Added: a finished session restores as finished with its finality (exact, settled or provisional), a journal cut between `Finish` and `Finalized` has provisional steps, and a running session is not finished.
