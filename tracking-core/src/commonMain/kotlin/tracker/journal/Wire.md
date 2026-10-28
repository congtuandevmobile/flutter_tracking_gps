# Wire.kt

**Path:** `.../tracker/journal/Wire.kt` · **Layer:** journal (text helpers)

## Purpose
The two primitives the journal format needs, with no platform API and no library: escaping of the characters that would break a `|`-separated line, and a CRC-32.

| Function | Description |
|---|---|
| `escape(s)` / `unescape(s)` | `%`, `|`, `=`, CR and LF become `%XX`. `unescape` returns null for a malformed escape (the line is then damaged). |
| `crc32(text)` | Standard CRC-32 (IEEE) over the UTF-8 bytes. |
| `hex8(v)` | Eight upper-case hex digits. |

Used by `JournalCodec`. Tested through `JournalTest` (round trips and a flipped character).

## Recheck update

`escape` also escapes `,` (besides `%`, `|`, `=`, newline, carriage return) so separators in extras and ids round-trip.
