package xyz.mdhv.asom.lab.proto.pairing

import xyz.mdhv.asom.lab.json.B64Result
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.proto.trust.Pin

/** One endpoint literal of the QR `a` parameter: an IP literal and a port, never a DNS name. */
class QrEndpoint(val addr: String, val port: Int, val ipv6: Boolean) {
    override fun toString(): String = if (ipv6) "[$addr]:$port" else "$addr:$port"
    override fun equals(other: Any?): Boolean = other is QrEndpoint && addr == other.addr && port == other.port && ipv6 == other.ipv6
    override fun hashCode(): Int = toString().hashCode()
}

class QrPayload(val pinD: Pin, val endpoints: List<QrEndpoint>, val secret: ByteArray, val expirySec: Long, val name: String)

enum class QrReject {
    TOO_LONG, SCHEME, VERSION, SYNTAX, MISSING_FIELD, DUPLICATE_FIELD, UNKNOWN_FIELD, BAD_PIN, BAD_SECRET, BAD_EXPIRY, EXPIRED, EXPIRY_TOO_FAR,
    ENDPOINT_COUNT, ENDPOINT_SYNTAX, DNS_NAME, ADDRESS_NOT_ELIGIBLE, BAD_NAME, NAME_TOO_LONG,
}

sealed interface QrParse {
    class Ok(val payload: QrPayload) : QrParse
    class Reject(val code: QrReject, val why: String) : QrParse
}

/** Which literal addresses a QR may name. [MESH] is trust.md 6.6 without its interface check (the dialer makes that check after connecting). */
enum class AddrPolicy { MESH, MESH_PLUS_LOOPBACK_FOR_TESTS }

/**
 * The QR payload grammar of trust.md 4.2, strictly:
 *
 * ```
 * asom-pair-uri = "asom-pair:1?" param *( "&" param )
 * k=<43 b64url: pin_D>  a=<1..4 endpoints>  s=<43 b64url: 32-byte secret>  x=<unix seconds>  n=<pct-encoded UTF-8 name, <= 32 code points>
 * ```
 *
 * Every parameter is required exactly once, in any order; an unknown parameter is refused. The encoder always writes `k a s x n`.
 */
object QrUri {
    const val PREFIX = "asom-pair:1?"
    const val MAX_ENDPOINTS = 4
    const val MAX_NAME_CODE_POINTS = 32
    const val MAX_LENGTH = 2048
    const val EXPIRY_GRACE_SEC = 60L
    const val WINDOW_SEC = 120L

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun encode(p: QrPayload): String {
        require(p.endpoints.size in 1..MAX_ENDPOINTS) { "1 to $MAX_ENDPOINTS endpoints" }
        require(p.secret.size == PairCrypto.SECRET_LENGTH)
        require(p.name.codePointCount(0, p.name.length) in 1..MAX_NAME_CODE_POINTS) { "name is 1 to $MAX_NAME_CODE_POINTS code points" }
        return PREFIX + "k=" + p.pinD.nodeId + "&a=" + p.endpoints.joinToString(",") + "&s=" + Base64Strict.encodeUrlNoPad(p.secret) +
            "&x=" + p.expirySec + "&n=" + percentEncode(p.name)
    }

    fun percentEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (b >= 0 && c in UNRESERVED) sb.append(c) else sb.append('%').append("0123456789ABCDEF"[(b.toInt() shr 4) and 15]).append("0123456789ABCDEF"[b.toInt() and 15])
        }
        return sb.toString()
    }

    private fun reject(code: QrReject, why: String) = QrParse.Reject(code, why)

    /** [nowSec]: S accepts a URI while `now < x + 60 s` (trust.md 4.6) and refuses an `x` beyond `now + 120 s + 60 s`. */
    fun parse(text: String, nowSec: Long, policy: AddrPolicy = AddrPolicy.MESH): QrParse {
        if (text.length > MAX_LENGTH) return reject(QrReject.TOO_LONG, "longer than $MAX_LENGTH characters")
        if (!text.startsWith("asom-pair:")) return reject(QrReject.SCHEME, "not an asom-pair URI")
        if (!text.startsWith(PREFIX)) return reject(QrReject.VERSION, "only version 1 exists")
        val query = text.substring(PREFIX.length)
        if (query.isEmpty()) return reject(QrReject.MISSING_FIELD, "no parameters")
        val fields = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) return reject(QrReject.SYNTAX, "a parameter has no name or no '='")
            val key = part.substring(0, eq)
            if (key !in setOf("k", "a", "s", "x", "n")) return reject(QrReject.UNKNOWN_FIELD, "unknown parameter")
            if (key in fields) return reject(QrReject.DUPLICATE_FIELD, "a parameter is repeated")
            fields[key] = part.substring(eq + 1)
        }
        for (key in listOf("k", "a", "s", "x", "n")) if (key !in fields) return reject(QrReject.MISSING_FIELD, "parameter $key is missing")

        val pinText = fields.getValue("k")
        val pinBytes = if (pinText.length == 43) (Base64Strict.decodeUrlNoPad(pinText) as? B64Result.Ok)?.bytes else null
        if (pinBytes == null || pinBytes.size != 32) return reject(QrReject.BAD_PIN, "k is not 43 base64url characters of a 32-byte pin")
        val pin = Pin.ofHash(pinBytes)

        val secretText = fields.getValue("s")
        val secret = if (secretText.length == 43) (Base64Strict.decodeUrlNoPad(secretText) as? B64Result.Ok)?.bytes else null
        if (secret == null || secret.size != 32) return reject(QrReject.BAD_SECRET, "s is not 43 base64url characters of 32 bytes")

        val xText = fields.getValue("x")
        if (xText.isEmpty() || xText.length > 12 || !xText.all { it in '0'..'9' } || (xText.length > 1 && xText[0] == '0')) return reject(QrReject.BAD_EXPIRY, "x is not plain unix seconds")
        val x = xText.toLong()
        if (nowSec >= x + EXPIRY_GRACE_SEC) return reject(QrReject.EXPIRED, "the pairing window has expired")
        if (x > nowSec + WINDOW_SEC + EXPIRY_GRACE_SEC) return reject(QrReject.EXPIRY_TOO_FAR, "x is more than a window and a skew allowance ahead")

        val endpoints = ArrayList<QrEndpoint>()
        val aText = fields.getValue("a")
        if (aText.isEmpty()) return reject(QrReject.ENDPOINT_COUNT, "no endpoints")
        val items = aText.split(',')
        if (items.size > MAX_ENDPOINTS) return reject(QrReject.ENDPOINT_COUNT, "more than $MAX_ENDPOINTS endpoints")
        for (item in items) {
            val ep = Endpoints.parse(item) ?: return reject(if (Endpoints.looksLikeDnsName(item)) QrReject.DNS_NAME else QrReject.ENDPOINT_SYNTAX, "an endpoint is not an IP literal with a port")
            if (!Endpoints.eligible(ep, policy)) return reject(QrReject.ADDRESS_NOT_ELIGIBLE, "an endpoint is not a private LAN or overlay-range address")
            endpoints += ep
        }

        val name = decodeName(fields.getValue("n")) ?: return reject(QrReject.BAD_NAME, "n is not strictly pct-encoded UTF-8 without control characters")
        val cps = name.codePointCount(0, name.length)
        if (cps == 0) return reject(QrReject.BAD_NAME, "n is empty")
        if (cps > MAX_NAME_CODE_POINTS) return reject(QrReject.NAME_TOO_LONG, "n is longer than $MAX_NAME_CODE_POINTS code points")
        return QrParse.Ok(QrPayload(pin, endpoints, secret, x, name))
    }

    /** Raw characters must be unreserved; everything else must be `%XX` with two hex digits. The bytes must be strict UTF-8. */
    fun decodeName(s: String): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0 || s[i + 1].code > 127 || s[i + 2].code > 127) return null
                bytes.write((hi shl 4) or lo)
                i += 3
            } else if (c in UNRESERVED) {
                bytes.write(c.code)
                i++
            } else return null
        }
        val raw = bytes.toByteArray()
        if (!xyz.mdhv.asom.lab.json.Utf8Strict.isValid(raw)) return null
        val text = String(raw, Charsets.UTF_8)
        var k = 0
        while (k < text.length) {
            val cp = text.codePointAt(k)
            k += Character.charCount(cp)
            if (Character.getType(cp) == Character.CONTROL.toInt() || cp == 0x2028 || cp == 0x2029 || cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0xFFFD) return null
        }
        return text
    }
}

/** Literal-only endpoint syntax and the address classes a QR may carry. No resolver is ever called. */
object Endpoints {
    fun looksLikeDnsName(item: String): Boolean {
        val host = item.substringBeforeLast(':', item)
        return host.isNotEmpty() && !host.startsWith("[") && host.any { it in 'a'..'z' || it in 'A'..'Z' } && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    fun parse(item: String): QrEndpoint? {
        if (item.startsWith("[")) {
            val close = item.indexOf(']')
            if (close < 0 || close + 1 >= item.length || item[close + 1] != ':') return null
            val addr = item.substring(1, close)
            val port = port(item.substring(close + 2)) ?: return null
            val groups = ipv6Groups(addr.lowercase()) ?: return null
            return QrEndpoint(canonicalV6(groups), port, true)
        }
        val colon = item.lastIndexOf(':')
        if (colon < 0) return null
        val addr = item.substring(0, colon)
        val port = port(item.substring(colon + 1)) ?: return null
        if (ipv4Octets(addr) == null) return null
        return QrEndpoint(addr, port, false)
    }

    private fun port(s: String): Int? {
        if (s.isEmpty() || s.length > 5 || !s.all { it in '0'..'9' } || (s.length > 1 && s[0] == '0')) return null
        val p = s.toInt()
        return if (p in 1..65535) p else null
    }

    fun ipv4Octets(s: String): IntArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v
        }
        return out
    }

    /** Eight 16-bit groups of a strict IPv6 literal (lowercase hex only, at most one `::`, no zone, no dotted tail), or null. */
    fun ipv6Groups(s: String): IntArray? {
        if (s.isEmpty() || s.any { it !in "0123456789abcdef:" }) return null
        val dbl = s.indexOf("::")
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) return null
        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            val out = ArrayList<Int>()
            for (g in part.split(':')) {
                if (g.isEmpty() || g.length > 4) return null
                out += g.toInt(16)
            }
            return out
        }
        val result = IntArray(8)
        if (dbl < 0) {
            val g = groups(s) ?: return null
            if (g.size != 8) return null
            g.forEachIndexed { i, v -> result[i] = v }
        } else {
            val head = groups(s.substring(0, dbl)) ?: return null
            val tail = groups(s.substring(dbl + 2)) ?: return null
            if (head.size + tail.size > 7) return null
            head.forEachIndexed { i, v -> result[i] = v }
            tail.forEachIndexed { i, v -> result[8 - tail.size + i] = v }
        }
        return result
    }

    /** RFC 5952 text of eight groups: lowercase, no leading zeros, the longest run of two or more zero groups (first on a tie) as `::`. */
    fun canonicalV6(g: IntArray): String {
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (g[i] == 0) {
                var j = i
                while (j < 8 && g[j] == 0) j++
                if (j - i > bestLen) { bestStart = i; bestLen = j - i }
                i = j
            } else i++
        }
        if (bestLen < 2) return g.joinToString(":") { it.toString(16) }
        val head = g.take(bestStart).joinToString(":") { it.toString(16) }
        val tail = g.drop(bestStart + bestLen).joinToString(":") { it.toString(16) }
        return "$head::$tail"
    }

    /** trust.md 6.6, address part only: RFC 1918, link-local, fc00::/7, fe80::/10, and the overlay range 100.64.0.0/10. Never public, wildcard or loopback (tests may allow loopback). */
    fun eligible(e: QrEndpoint, policy: AddrPolicy): Boolean {
        if (!e.ipv6) {
            val o = ipv4Octets(e.addr) ?: return false
            if (o[0] == 127) return policy == AddrPolicy.MESH_PLUS_LOOPBACK_FOR_TESTS
            return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254) || (o[0] == 100 && o[1] in 64..127)
        }
        val g = ipv6Groups(e.addr) ?: return false
        if (g.all { it == 0 }) return false
        if (g.take(7).all { it == 0 } && g[7] == 1) return policy == AddrPolicy.MESH_PLUS_LOOPBACK_FOR_TESTS
        return (g[0] and 0xFE00) == 0xFC00 || (g[0] and 0xFFC0) == 0xFE80
    }
}
