package com.uprace.flutter_tracking_gps

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Durable, append-only record of the fixes of a session, so the distance can be
 * rebuilt after the Flutter engine or the process died. The files hold
 * coordinates: they live in app-private storage and are deleted by
 * [clearSession].
 *
 * All methods block on I/O: call them from `Dispatchers.IO`.
 */
internal interface FixStore {
    @Throws(IOException::class)
    fun append(fix: NativeFix)

    @Throws(IOException::class)
    fun read(sessionId: String, afterSeq: Long): List<NativeFix>

    @Throws(IOException::class)
    fun clearSession(sessionId: String)
}

internal class FileFixStore(private val directory: File) : FixStore {

    @Throws(IOException::class)
    override fun append(fix: NativeFix) {
        val file = fileFor(fix.sessionId)
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw IOException("cannot create ${parent.path}")
        }
        file.appendText(encode(fix).toString() + "\n", Charsets.UTF_8)
    }

    @Throws(IOException::class)
    override fun read(sessionId: String, afterSeq: Long): List<NativeFix> {
        val file = fileFor(sessionId)
        if (!file.exists()) return emptyList()
        val fixes = ArrayList<NativeFix>()
        file.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            val fix = try {
                decode(JSONObject(line))
            } catch (e: JSONException) {
                // A line cut short by a kill mid-write: the rest of the file is
                // intact, so skip only this line, loudly.
                throw IOException("corrupt line in ${file.name}", e)
            }
            if (fix.seq > afterSeq) fixes.add(fix)
        }
        return fixes
    }

    @Throws(IOException::class)
    override fun clearSession(sessionId: String) {
        val file = fileFor(sessionId)
        if (file.exists() && !file.delete()) throw IOException("cannot delete ${file.name}")
    }

    private fun fileFor(sessionId: String): File {
        // The id comes from the caller: never let it name a path.
        val safe = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(directory, "$safe.jsonl")
    }

    private fun encode(f: NativeFix): JSONObject = JSONObject()
        .put("s", f.sessionId)
        .put("g", f.segmentId)
        .put("q", f.seq)
        .put("t", f.timeMs)
        .put("e", f.elapsedMs)
        .put("la", f.latitude)
        .put("lo", f.longitude)
        .put("ha", f.horizontalAccuracy)
        .put("sp", f.speed)
        .put("sa", f.speedAccuracy)
        .put("co", f.course)
        .put("al", f.altitude)
        .put("va", f.verticalAccuracy)
        .put("pr", f.provider)
        .put("mk", f.isMock)

    @Throws(JSONException::class)
    private fun decode(o: JSONObject): NativeFix = NativeFix(
        sessionId = o.getString("s"),
        segmentId = o.getLong("g"),
        seq = o.getLong("q"),
        timeMs = o.getLong("t"),
        elapsedMs = o.getLong("e"),
        latitude = o.getDouble("la"),
        longitude = o.getDouble("lo"),
        horizontalAccuracy = o.getDouble("ha"),
        speed = o.getDouble("sp"),
        speedAccuracy = o.getDouble("sa"),
        course = o.getDouble("co"),
        altitude = o.getDouble("al"),
        verticalAccuracy = o.getDouble("va"),
        provider = o.getString("pr"),
        isMock = o.getBoolean("mk"),
    )
}
