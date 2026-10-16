package xyz.mdhv.asom.lab.json

/** RFC 3629 / Unicode Table 3-7 well-formed UTF-8: no overlong forms, no encoded surrogates, nothing above U+10FFFF, no truncation. */
object Utf8Strict {
    /** The offset of the first byte that does not belong to a well-formed sequence, or -1 when the whole input is well formed. */
    fun firstInvalid(bytes: ByteArray): Int {
        var i = 0
        while (i < bytes.size) {
            val d = decodeAt(bytes, i)
            if (d < 0) return i
            i += (d and 7L).toInt()
        }
        return -1
    }

    fun isValid(bytes: ByteArray): Boolean = firstInvalid(bytes) < 0

    /** The sequence at [i] packed as `codePoint shl 3 or length`, or -1 when it is not well formed. */
    internal fun decodeAt(b: ByteArray, i: Int): Long {
        val b0 = b[i].toInt() and 0xFF
        if (b0 < 0x80) return (b0.toLong() shl 3) or 1L
        val len: Int
        var lo = 0x80
        var hi = 0xBF
        var cp: Int
        when (b0) {
            in 0xC2..0xDF -> { len = 2; cp = b0 and 0x1F }
            0xE0 -> { len = 3; lo = 0xA0; cp = b0 and 0x0F }
            in 0xE1..0xEC, 0xEE, 0xEF -> { len = 3; cp = b0 and 0x0F }
            0xED -> { len = 3; hi = 0x9F; cp = b0 and 0x0F }
            0xF0 -> { len = 4; lo = 0x90; cp = b0 and 0x07 }
            in 0xF1..0xF3 -> { len = 4; cp = b0 and 0x07 }
            0xF4 -> { len = 4; hi = 0x8F; cp = b0 and 0x07 }
            else -> return -1
        }
        if (i + len > b.size) return -1
        for (k in 1 until len) {
            val c = b[i + k].toInt() and 0xFF
            val min = if (k == 1) lo else 0x80
            val max = if (k == 1) hi else 0xBF
            if (c < min || c > max) return -1
            cp = (cp shl 6) or (c and 0x3F)
        }
        return (cp.toLong() shl 3) or len.toLong()
    }
}
