# FileJournalStoreTest.kt

Tests of the REAL file-backed journal store: ids the old naming would have collided (`a/b`, `a?b`) get different files and are listed by their real ids; many appends from two threads are all written and ordered per thread; `read` is itself a barrier; `clear` closes the writer so a late append neither re-creates the file nor is silently lost; `rewrite` replaces atomically and appending continues after it; a write killed mid-line leaves a truncated tail the reader recognises.

Added: `pending` drops to zero once the writer reached the disk; repairing with `keepBackup` keeps the original next to the file.
