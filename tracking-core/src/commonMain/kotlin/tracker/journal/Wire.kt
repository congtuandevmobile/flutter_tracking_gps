package tracker.journal

/** Text helpers of the journal format: escaping and a CRC-32 (no platform API, no library). */
internal object Wire {
    private const val HEX = "0123456789ABCDEF"

    /** Escapes every character the format uses as a separator or terminator: `%`, `|`, `=`, `,`, CR, LF. */
    fun escape(s: String): String {
        val b = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '%', '|', '=', ',', '\n', '\r' -> {
                    b.append('%')
                    b.append(HEX[(c.code shr 4) and 0xF])
                    b.append(HEX[c.code and 0xF])
                }
                else -> b.append(c)
            }
        }
        return b.toString()
    }

    /** Inverse of [escape]; null when the text holds a malformed escape. */
    fun unescape(s: String): String? {
        if (s.indexOf('%') < 0) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '%') {
                b.append(c)
                i++
                continue
            }
            if (i + 2 >= s.length) return null
            val hi = HEX.indexOf(s[i + 1])
            val lo = HEX.indexOf(s[i + 2])
            if (hi < 0 || lo < 0) return null
            b.append(((hi shl 4) or lo).toChar())
            i += 3
        }
        return b.toString()
    }

    private val table: IntArray = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun crc32(text: String): Int {
        var c = -1
        for (byte in text.encodeToByteArray()) c = table[(c xor byte.toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }

    fun hex8(v: Int): String {
        val b = StringBuilder(8)
        for (shift in 28 downTo 0 step 4) b.append(HEX[(v ushr shift) and 0xF])
        return b.toString()
    }
}
