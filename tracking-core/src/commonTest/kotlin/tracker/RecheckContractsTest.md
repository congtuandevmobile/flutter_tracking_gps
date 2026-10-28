# RecheckContractsTest

Contract tests from the recheck report: encoding of every separator, unicode and emoji; format versions; every tick durable; step measurement-time validation; weak-interval boundaries; moving time while stationary; the validation boundary (refused inputs are not journaled, nothing throws); per-segment step finality; calendar time of an accepted fix.

`RestorePlanTest`: an impossible CRC-valid record is CORRUPT and dropped before appending; an intact journal needs no rewrite; a version-1 header is migrated (engine version kept, `engineChanged` reported); a finished v1 journal stays read-only.
