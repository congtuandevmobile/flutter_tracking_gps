package tracker.journal

/** How reading a journal ended. */
enum class JournalStatus {
    /** Every line is intact. */
    OK,

    /** Only the LAST line is incomplete or damaged (a kill mid-write): everything before it is trusted. */
    TRUNCATED_TAIL,

    /** A line before the last one is damaged: the valid prefix is returned, the rest is NOT trusted. */
    CORRUPT,

    /** The header says a newer format than this code understands. */
    UNSUPPORTED_VERSION,

    /** There is nothing to read. */
    EMPTY,
}

class JournalContents(
    val records: List<JournalRecord>,
    val status: JournalStatus,
    /** Zero-based index of the first damaged line, or -1. */
    val damagedLine: Int,
)

/**
 * One record per line: `CRC32|TYPE|field|field...`. The CRC covers everything
 * after it, so a half-written or flipped line is detected, never guessed at.
 * Numbers use Kotlin's shortest round-trip text; text fields are escaped.
 */
object JournalCodec {
    /** The journal FORMAT. A reader refuses a newer one. */
    const val VERSION = 1

    /**
     * The RULES of the engine (`GpsDistanceEngine` and what it uses). Bump it whenever a
     * change can alter a result. A journal replays with the code that reads it, so a journal
     * written by another engine version is replayed anyway but flagged: restore is
     * "rebuild what was shown" only while the version matches.
     */
    const val ENGINE_VERSION = 1

    fun encode(r: JournalRecord): String {
        val body = when (r) {
            is JournalRecord.Header -> join(
                "H", r.version, r.engineVersion, e(r.sessionId), r.atMs, r.wallAtMs, r.segmentId, r.initialSteps,
                r.settings.maxAccuracyMeters, r.settings.maxFixAgeMs, r.settings.silenceAfterMs, r.settings.maxSpeedMps,
                r.settings.minStepMeters, r.settings.accuracyNoiseFactor, r.settings.stationarySpeedMps,
                r.settings.maxBridgeMs, r.settings.reanchorAfterRejects, b(r.settings.rejectMock), b(r.settings.pdrEnabled),
                r.settings.pdrConfirmRatio, b(r.settings.kalman), r.settings.stopRadiusMeters, r.settings.stopMinDwellMs,
                r.settings.initialStrideMeters, b(r.settings.pdrTrustedWhenCalibrated), r.settings.recoveryStepEvidenceAfterMs,
                b(r.settings.adaptiveSampling), b(r.settings.batterySaver),
                r.extras.entries.joinToString(",") { e(it.key) + "=" + e(it.value) },
            )
            is JournalRecord.Pause -> join("P", r.atMs)
            is JournalRecord.Resume -> join("R", r.atMs, r.segmentId)
            is JournalRecord.Finish -> join("F", r.atMs)
            is JournalRecord.Fix -> join(
                "X", e(r.sessionId), r.segmentId, r.seq, r.timeMs, r.wallTimeMs, r.latitude, r.longitude,
                r.horizontalAccuracy, r.speed, r.speedAccuracy, r.course, r.altitude, r.verticalAccuracy,
                e(r.provider), b(r.isMock), r.nowMs,
            )
            is JournalRecord.Tick -> join("T", r.atMs)
            is JournalRecord.StepCounter -> join("C", r.segmentId, r.measuredAtMs, r.deliveredAtMs, r.raw)
            is JournalRecord.SegmentSteps -> join("S", r.segmentId, r.measuredAtMs, r.deliveredAtMs, r.count)
            is JournalRecord.StepsCorrection -> join("K", r.exactSteps)
            is JournalRecord.StepSensor -> join("A", b(r.active))
            is JournalRecord.Pdr -> join("D", r.segmentId, r.atMs, r.meters)
            is JournalRecord.Finalized -> join("Z", b(r.stepsFinal))
        }
        return Wire.hex8(Wire.crc32(body)) + "|" + body
    }

    /** Null when the line is damaged (bad CRC, wrong field count, unknown type, malformed number). */
    fun decode(line: String): JournalRecord? {
        val cut = line.indexOf('|')
        if (cut != 8) return null
        val body = line.substring(9)
        if (line.substring(0, 8) != Wire.hex8(Wire.crc32(body))) return null
        val f = body.split('|')
        return try {
            when (f[0]) {
                "H" -> header(f)
                "P" -> need(f, 2) { JournalRecord.Pause(f[1].toLong()) }
                "R" -> need(f, 3) { JournalRecord.Resume(f[1].toLong(), f[2].toInt()) }
                "F" -> need(f, 2) { JournalRecord.Finish(f[1].toLong()) }
                "X" -> need(f, 17) {
                    JournalRecord.Fix(
                        sessionId = u(f[1]), segmentId = f[2].toInt(), seq = f[3].toLong(), timeMs = f[4].toLong(),
                        wallTimeMs = f[5].toLong(), latitude = f[6].toDouble(), longitude = f[7].toDouble(),
                        horizontalAccuracy = f[8].toDouble(), speed = f[9].toDouble(), speedAccuracy = f[10].toDouble(),
                        course = f[11].toDouble(), altitude = f[12].toDouble(), verticalAccuracy = f[13].toDouble(),
                        provider = u(f[14]), isMock = f[15] == "1", nowMs = f[16].toLong(),
                    )
                }
                "T" -> need(f, 2) { JournalRecord.Tick(f[1].toLong()) }
                "C" -> need(f, 5) { JournalRecord.StepCounter(f[1].toInt(), f[2].toLong(), f[3].toLong(), f[4].toLong()) }
                "S" -> need(f, 5) { JournalRecord.SegmentSteps(f[1].toInt(), f[2].toLong(), f[3].toLong(), f[4].toLong()) }
                "K" -> need(f, 2) { JournalRecord.StepsCorrection(f[1].toLong()) }
                "A" -> need(f, 2) { JournalRecord.StepSensor(f[1] == "1") }
                "D" -> need(f, 4) { JournalRecord.Pdr(f[1].toInt(), f[2].toLong(), f[3].toDouble()) }
                "Z" -> need(f, 2) { JournalRecord.Finalized(f[1] == "1") }
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        } catch (e: MalformedEscape) {
            null
        }
    }

    /**
     * Reads every line. The first damaged LAST line is a normal crash artefact
     * ([JournalStatus.TRUNCATED_TAIL]); a damaged line followed by more lines is
     * real corruption ([JournalStatus.CORRUPT]). Either way only the intact prefix
     * is returned, and the first record must be a header of a known version.
     */
    fun read(lines: List<String>): JournalContents {
        val nonBlank = lines.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return JournalContents(emptyList(), JournalStatus.EMPTY, -1)
        val out = ArrayList<JournalRecord>(nonBlank.size)
        for ((i, line) in nonBlank.withIndex()) {
            val r = decode(line)
            if (r == null) {
                val status = if (i == nonBlank.lastIndex) JournalStatus.TRUNCATED_TAIL else JournalStatus.CORRUPT
                return JournalContents(out, status, i)
            }
            if (i == 0) {
                if (r !is JournalRecord.Header) return JournalContents(emptyList(), JournalStatus.CORRUPT, 0)
                if (r.version > VERSION) return JournalContents(emptyList(), JournalStatus.UNSUPPORTED_VERSION, 0)
            }
            out.add(r)
        }
        return JournalContents(out, JournalStatus.OK, -1)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private class MalformedEscape : Exception()

    private fun header(f: List<String>): JournalRecord? {
        if (f.size != 29) return null
        val extras = LinkedHashMap<String, String>()
        if (f[28].isNotEmpty()) {
            for (pair in f[28].split(',')) {
                val kv = pair.split('=')
                if (kv.size != 2) return null
                extras[u(kv[0])] = u(kv[1])
            }
        }
        return JournalRecord.Header(
            version = f[1].toInt(), engineVersion = f[2].toInt(), sessionId = u(f[3]), atMs = f[4].toLong(),
            wallAtMs = f[5].toLong(), segmentId = f[6].toInt(), initialSteps = f[7].toLong(),
            settings = BridgeSettings(
                maxAccuracyMeters = f[8].toDouble(), maxFixAgeMs = f[9].toLong(), silenceAfterMs = f[10].toLong(),
                maxSpeedMps = f[11].toDouble(), minStepMeters = f[12].toDouble(), accuracyNoiseFactor = f[13].toDouble(),
                stationarySpeedMps = f[14].toDouble(), maxBridgeMs = f[15].toLong(), reanchorAfterRejects = f[16].toInt(),
                rejectMock = f[17] == "1", pdrEnabled = f[18] == "1", pdrConfirmRatio = f[19].toDouble(),
                kalman = f[20] == "1", stopRadiusMeters = f[21].toDouble(), stopMinDwellMs = f[22].toLong(),
                initialStrideMeters = f[23].toDouble(), pdrTrustedWhenCalibrated = f[24] == "1",
                recoveryStepEvidenceAfterMs = f[25].toLong(), adaptiveSampling = f[26] == "1", batterySaver = f[27] == "1",
            ),
            extras = extras,
        )
    }

    private inline fun need(f: List<String>, n: Int, block: () -> JournalRecord): JournalRecord? = if (f.size == n) block() else null

    private fun join(vararg parts: Any): String = parts.joinToString("|")
    private fun e(s: String): String = Wire.escape(s)
    private fun b(v: Boolean): String = if (v) "1" else "0"
    private fun u(s: String): String = Wire.unescape(s) ?: throw MalformedEscape()
}
