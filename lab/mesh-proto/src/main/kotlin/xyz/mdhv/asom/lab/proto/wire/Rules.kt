package xyz.mdhv.asom.lab.proto.wire

import xyz.mdhv.asom.lab.json.B64Result
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.MAX_SAFE_INT

internal fun bad(reason: String): Nothing = throw WireRefusal(MeshError.PROTOCOL_ERROR, reason)

/** The exact shape of every identifier and free-text field of LAB_SPEC 7.2. */
object Rules {
    private val modelId = Regex("^[A-Za-z0-9._:-]{1,128}$")
    private val sha256Hex = Regex("^[0-9a-f]{64}$")
    private val software = Regex("^[A-Za-z0-9._-]{1,32}/[A-Za-z0-9._+-]{1,32}$")

    const val NAME_MAX_CODE_POINTS = 32
    const val MAX_ENDPOINTS = 4

    private fun b64Url(text: String, bytes: Int): Boolean =
        (Base64Strict.decodeUrlNoPad(text) as? B64Result.Ok)?.bytes?.size == bytes

    /** 43 characters: the base64url, unpadded, of 32 bytes with zero unused bits. */
    fun isNodeId(s: String): Boolean = s.length == 43 && b64Url(s, 32)

    /** 22 characters: 16 bytes. */
    fun isAttemptId(s: String): Boolean = s.length == 22 && b64Url(s, 16)

    fun isSessionNonce(s: String): Boolean = s.length == 22 && b64Url(s, 16)

    fun isChallenge(s: String): Boolean = s.length == 43 && b64Url(s, 32)

    fun isModelId(s: String): Boolean = modelId.matches(s)

    fun isSha256Hex(s: String): Boolean = sha256Hex.matches(s)

    fun isSoftware(s: String): Boolean = software.matches(s)

    /** A device name: 1 to 32 code points and no control character. Normalisation and bidi stripping are display rules (design T15), not wire rules. */
    fun isName(s: String): Boolean {
        val cps = s.codePointCount(0, s.length)
        if (cps !in 1..NAME_MAX_CODE_POINTS) return false
        var k = 0
        while (k < s.length) {
            val cp = s.codePointAt(k)
            if (cp < 0x20 || cp in 0x7F..0x9F) return false
            k += Character.charCount(cp)
        }
        return true
    }

    fun isCount(v: Long): Boolean = v in 0..MAX_SAFE_INT
}

/** IP literals only: no DNS name, no zone identifier, no brackets. The parser is hand written so that no lookup can ever happen. */
object IpLiteral {
    fun isValid(s: String): Boolean = isV4(s) || isV6(s)

    fun isV4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.length in 1..3 && p.all { it in '0'..'9' } && (p.length == 1 || p[0] != '0') && p.toInt() <= 255
        }
    }

    private fun isHexGroup(g: String): Boolean = g.length in 1..4 && g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    fun isV6(s: String): Boolean {
        if (s.isEmpty() || s.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return false
        val dbl = s.indexOf("::")
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) return false
        val groups: List<String>
        val v4Allowed: Boolean
        if (dbl >= 0) {
            val left = s.substring(0, dbl)
            val right = s.substring(dbl + 2)
            val l = if (left.isEmpty()) emptyList() else left.split(':')
            val r = if (right.isEmpty()) emptyList() else right.split(':')
            groups = l + r
            v4Allowed = r.isNotEmpty()
        } else {
            groups = s.split(':')
            v4Allowed = true
        }
        if (groups.any { it.isEmpty() }) return false
        var width = 0
        groups.forEachIndexed { k, g ->
            val last = k == groups.lastIndex
            if (g.contains('.')) {
                if (!(last && v4Allowed && isV4(g))) return false
                width += 2
            } else {
                if (!isHexGroup(g)) return false
                width += 1
            }
        }
        return if (dbl >= 0) width <= 7 else width == 8
    }
}
