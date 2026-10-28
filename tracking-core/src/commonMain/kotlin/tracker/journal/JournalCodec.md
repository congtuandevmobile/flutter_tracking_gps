# JournalCodec.kt

**Path:** `.../tracker/journal/JournalCodec.kt` · **Layer:** journal (format)

## Purpose
Encodes a `JournalRecord` as one line `CRC32|TYPE|field|...` and decodes it back; reads a whole journal tolerantly.

| Function | Description |
|---|---|
| `encode(record)` | One line, CRC first. |
| `decode(line)` | The record, or null when the line is damaged (bad CRC, wrong field count, unknown type, malformed number or escape). |
| `read(lines)` | `JournalContents(records, status, damagedLine)`: the intact prefix plus `OK`, `TRUNCATED_TAIL` (only the last line is damaged: a kill mid-write), `CORRUPT` (a line before the last is damaged), `UNSUPPORTED_VERSION` or `EMPTY`. The first record must be a header of a known version. |

Never guesses at a damaged line. Format and policy: `doc/JOURNAL.md`. Tested by `JournalTest` (`JournalCodecTest`).

`ENGINE_VERSION` (rules) is separate from `VERSION` (format); the header stores both.
