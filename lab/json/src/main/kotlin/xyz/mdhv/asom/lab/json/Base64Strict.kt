package xyz.mdhv.asom.lab.json

sealed interface B64Result {
    class Ok(val bytes: ByteArray) : B64Result

    /** Every base64 reject is the single code `ENCODING` (LAB_SPEC 4.2); [reason] is for diagnostics only. */
    class Reject(val reason: String) : B64Result {
        val code: String get() = "ENCODING"
    }
}

/**
 * Hand-written strict base64 (LAB_SPEC 4.2). No decoder from the JDK is involved, so none of its leniencies
 * (non-zero trailing bits, ignored characters, missing padding) can leak in.
 */
object Base64Strict {
    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /**
     * `b64either`: the standard or the URL-safe alphabet, padded or unpadded. Rejects mixed alphabets, whitespace or any
     * other character, padding anywhere but the end or of the wrong length, a length of 1 mod 4, and non-zero unused bits.
     */
    fun decodeEither(text: String): B64Result = decode(text, allowStd = true, allowUrl = true, allowPad = true)

    /** `b64url`, used for ids: the URL-safe alphabet only, no padding, zero unused bits. */
    fun decodeUrlNoPad(text: String): B64Result = decode(text, allowStd = false, allowUrl = true, allowPad = false)

    /** Standard alphabet with padding: what producers emit. */
    fun encodeStandard(bytes: ByteArray): String = encode(bytes, STD, pad = true)

    fun encodeUrlNoPad(bytes: ByteArray): String = encode(bytes, URL, pad = false)

    private fun decode(text: String, allowStd: Boolean, allowUrl: Boolean, allowPad: Boolean): B64Result {
        var core = text.length
        while (core > 0 && text[core - 1] == '=') core--
        val pad = text.length - core
        if (pad > 0) {
            if (!allowPad) return B64Result.Reject("padding not allowed")
            if (pad > 2 || text.length % 4 != 0) return B64Result.Reject("bad padding")
        }
        val values = IntArray(core)
        var sawStd = false
        var sawUrl = false
        for (k in 0 until core) {
            val c = text[k]
            val v = when {
                c in 'A'..'Z' -> c - 'A'
                c in 'a'..'z' -> c - 'a' + 26
                c in '0'..'9' -> c - '0' + 52
                c == '+' || c == '/' -> { sawStd = true; if (c == '+') 62 else 63 }
                c == '-' || c == '_' -> { sawUrl = true; if (c == '-') 62 else 63 }
                else -> return B64Result.Reject("character not in the alphabet at $k")
            }
            values[k] = v
        }
        if (sawStd && !allowStd) return B64Result.Reject("standard alphabet not allowed")
        if (sawUrl && !allowUrl) return B64Result.Reject("URL-safe alphabet not allowed")
        if (sawStd && sawUrl) return B64Result.Reject("mixed alphabets")
        val rem = core % 4
        if (rem == 1) return B64Result.Reject("length is 1 mod 4")
        if (rem == 2 && values[core - 1] and 0x0F != 0) return B64Result.Reject("non-zero unused bits")
        if (rem == 3 && values[core - 1] and 0x03 != 0) return B64Result.Reject("non-zero unused bits")
        val out = ByteArray(core / 4 * 3 + if (rem == 0) 0 else rem - 1)
        var o = 0
        var k = 0
        while (k + 4 <= core) {
            val n = (values[k] shl 18) or (values[k + 1] shl 12) or (values[k + 2] shl 6) or values[k + 3]
            out[o++] = (n shr 16).toByte()
            out[o++] = (n shr 8).toByte()
            out[o++] = n.toByte()
            k += 4
        }
        if (rem == 2) {
            out[o] = ((values[k] shl 2) or (values[k + 1] shr 4)).toByte()
        } else if (rem == 3) {
            out[o++] = ((values[k] shl 2) or (values[k + 1] shr 4)).toByte()
            out[o] = ((values[k + 1] shl 4) or (values[k + 2] shr 2)).toByte()
        }
        return B64Result.Ok(out)
    }

    private fun encode(bytes: ByteArray, alphabet: String, pad: Boolean): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var k = 0
        while (k + 3 <= bytes.size) {
            val n = ((bytes[k].toInt() and 0xFF) shl 16) or ((bytes[k + 1].toInt() and 0xFF) shl 8) or (bytes[k + 2].toInt() and 0xFF)
            sb.append(alphabet[n shr 18]).append(alphabet[(n shr 12) and 63]).append(alphabet[(n shr 6) and 63]).append(alphabet[n and 63])
            k += 3
        }
        when (bytes.size - k) {
            1 -> {
                val n = (bytes[k].toInt() and 0xFF) shl 4
                sb.append(alphabet[n shr 6]).append(alphabet[n and 63])
                if (pad) sb.append("==")
            }
            2 -> {
                val n = (((bytes[k].toInt() and 0xFF) shl 8) or (bytes[k + 1].toInt() and 0xFF)) shl 2
                sb.append(alphabet[n shr 12]).append(alphabet[(n shr 6) and 63]).append(alphabet[n and 63])
                if (pad) sb.append('=')
            }
        }
        return sb.toString()
    }
}
