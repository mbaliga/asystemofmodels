package xyz.mdhv.asom.lab.proto.trust

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Raised by [DerReader] on any input that is not strict, definite-length DER of the shapes the certificate templates use. */
class DerException(message: String) : Exception(message, null, false, false)

/**
 * The small deterministic DER encoder behind the two fixed certificate templates of trust.md 2.4 (no BouncyCastle, JDK only).
 * Every function emits the minimal definite-length encoding, so the same inputs always give the same bytes.
 */
object Der {
    const val SEQUENCE = 0x30
    const val SET = 0x31
    const val INTEGER = 0x02
    const val BIT_STRING = 0x03
    const val OCTET_STRING = 0x04
    const val OID = 0x06
    const val UTF8_STRING = 0x0C
    const val UTC_TIME = 0x17
    const val GENERALIZED_TIME = 0x18
    const val BOOLEAN = 0x01

    fun length(n: Int): ByteArray {
        require(n >= 0)
        return when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            else -> error("a certificate field of $n bytes is not expected")
        }
    }

    fun tlv(tag: Int, body: ByteArray): ByteArray {
        require(tag in 0..0xFF)
        return byteArrayOf(tag.toByte()) + length(body.size) + body
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(SEQUENCE, concat(*parts))

    fun set(vararg parts: ByteArray): ByteArray = tlv(SET, concat(*parts))

    /** A context-specific constructed tag `[n]` (explicit tagging). */
    fun explicit(n: Int, body: ByteArray): ByteArray = tlv(0xA0 or n, body)

    /** A context-specific primitive tag `[n]` (implicit tagging of an OCTET STRING). */
    fun implicitPrimitive(n: Int, body: ByteArray): ByteArray = tlv(0x80 or n, body)

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { BigInteger(it) }
        require(arcs.size >= 2)
        val out = ByteArrayOutputStream()
        fun base128(v: BigInteger) {
            val groups = ArrayList<Int>()
            var x = v
            do {
                groups += x.and(BigInteger.valueOf(0x7F)).toInt()
                x = x.shiftRight(7)
            } while (x.signum() > 0)
            for (i in groups.indices.reversed()) out.write(if (i == 0) groups[i] else groups[i] or 0x80)
        }
        base128(arcs[0].multiply(BigInteger.valueOf(40)).add(arcs[1]))
        for (i in 2 until arcs.size) base128(arcs[i])
        return tlv(OID, out.toByteArray())
    }

    /** A positive INTEGER from unsigned big-endian bytes: leading zero bytes are dropped, one is added if the high bit is set. */
    fun integerUnsigned(bytes: ByteArray): ByteArray {
        var k = 0
        while (k < bytes.size - 1 && bytes[k] == 0.toByte()) k++
        var body = bytes.copyOfRange(k, bytes.size)
        if (body.isEmpty()) body = byteArrayOf(0)
        if (body[0].toInt() and 0x80 != 0) body = byteArrayOf(0) + body
        return tlv(INTEGER, body)
    }

    fun integer(v: Long): ByteArray = tlv(INTEGER, BigInteger.valueOf(v).toByteArray())

    fun bitString(bytes: ByteArray, unusedBits: Int = 0): ByteArray = tlv(BIT_STRING, byteArrayOf(unusedBits.toByte()) + bytes)

    fun octetString(bytes: ByteArray): ByteArray = tlv(OCTET_STRING, bytes)

    fun utf8String(s: String): ByteArray = tlv(UTF8_STRING, s.toByteArray(Charsets.UTF_8))

    fun boolean(v: Boolean): ByteArray = tlv(BOOLEAN, byteArrayOf(if (v) 0xFF.toByte() else 0))

    private fun utc(epochSec: Long): ZonedDateTime = Instant.ofEpochSecond(epochSec).atZone(ZoneOffset.UTC)

    /** RFC 5280 4.1.2.5: UTCTime up to and including 2049, GeneralizedTime from 2050. Whole seconds, always `Z`. */
    fun time(epochSec: Long): ByteArray {
        val t = utc(epochSec)
        return if (t.year in 1950..2049) {
            tlv(UTC_TIME, String.format(java.util.Locale.ROOT, "%02d%02d%02d%02d%02d%02dZ", t.year % 100, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second).toByteArray(Charsets.US_ASCII))
        } else {
            generalizedTime(epochSec)
        }
    }

    fun generalizedTime(epochSec: Long): ByteArray {
        val t = utc(epochSec)
        return tlv(GENERALIZED_TIME, String.format(java.util.Locale.ROOT, "%04d%02d%02d%02d%02d%02dZ", t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second).toByteArray(Charsets.US_ASCII))
    }
}

/** One element of a DER document: its tag and the byte ranges of the whole element and of its content. */
class Tlv(val tag: Int, val bytes: ByteArray, val start: Int, val contentStart: Int, val end: Int) {
    val content: ByteArray get() = bytes.copyOfRange(contentStart, end)
    val whole: ByteArray get() = bytes.copyOfRange(start, end)
    val contentLength: Int get() = end - contentStart
}

/**
 * Strict DER reading: single-byte tags, definite and minimal lengths, nothing outside the bounds. It does not validate the semantic
 * type of any element; callers check the tag and the content form they need.
 */
object DerReader {
    private const val MAX_LEN = 1 shl 20

    fun read(bytes: ByteArray, offset: Int, limit: Int): Tlv {
        if (offset < 0 || limit > bytes.size || offset >= limit) throw DerException("element starts outside the input")
        var p = offset
        val tag = bytes[p++].toInt() and 0xFF
        if (tag and 0x1F == 0x1F) throw DerException("multi-byte tags are not used")
        if (p >= limit) throw DerException("missing length")
        val first = bytes[p++].toInt() and 0xFF
        val len: Int
        if (first < 0x80) {
            len = first
        } else {
            val n = first and 0x7F
            if (n == 0) throw DerException("indefinite length")
            if (n > 3) throw DerException("length of $n octets")
            if (p + n > limit) throw DerException("truncated length")
            var v = 0
            for (k in 0 until n) v = (v shl 8) or (bytes[p++].toInt() and 0xFF)
            if (v < 0x80) throw DerException("non-minimal length")
            if (n >= 2 && v < 0x100) throw DerException("non-minimal length")
            if (n == 3 && v < 0x10000) throw DerException("non-minimal length")
            len = v
        }
        if (len > MAX_LEN || p + len > limit) throw DerException("element overruns its container")
        return Tlv(tag, bytes, offset, p, p + len)
    }

    /** The children of a constructed element, which must fill it exactly. */
    fun children(t: Tlv): List<Tlv> {
        val out = ArrayList<Tlv>()
        var p = t.contentStart
        while (p < t.end) {
            val c = read(t.bytes, p, t.end)
            out += c
            p = c.end
        }
        return out
    }

    /** One element that must span the whole input. */
    fun single(bytes: ByteArray): Tlv {
        val t = read(bytes, 0, bytes.size)
        if (t.end != bytes.size) throw DerException("trailing bytes after the element")
        return t
    }

    fun expect(t: Tlv, tag: Int, what: String): Tlv {
        if (t.tag != tag) throw DerException("$what: tag ${"%02x".format(t.tag)} where ${"%02x".format(tag)} was expected")
        return t
    }

    /** Dotted form of an OID element's content. */
    fun oidString(t: Tlv): String {
        expect(t, Der.OID, "OBJECT IDENTIFIER")
        if (t.contentLength == 0) throw DerException("empty OID")
        val c = t.content
        val arcs = ArrayList<BigInteger>()
        var v = BigInteger.ZERO
        var fresh = true
        for (b in c) {
            val x = b.toInt() and 0xFF
            if (fresh && x == 0x80) throw DerException("non-minimal OID arc")
            fresh = false
            v = v.shiftLeft(7).or(BigInteger.valueOf((x and 0x7F).toLong()))
            if (x and 0x80 == 0) {
                arcs += v
                v = BigInteger.ZERO
                fresh = true
            }
        }
        if (!fresh) throw DerException("truncated OID")
        val first = arcs[0]
        val head = when {
            first < BigInteger.valueOf(40) -> listOf("0", first.toString())
            first < BigInteger.valueOf(80) -> listOf("1", first.subtract(BigInteger.valueOf(40)).toString())
            else -> listOf("2", first.subtract(BigInteger.valueOf(80)).toString())
        }
        return (head + arcs.drop(1).map { it.toString() }).joinToString(".")
    }

    /** BOOLEAN with the DER values only (`00` or `ff`). */
    fun boolean(t: Tlv): Boolean {
        expect(t, Der.BOOLEAN, "BOOLEAN")
        if (t.contentLength != 1) throw DerException("BOOLEAN length")
        return when (t.bytes[t.contentStart].toInt() and 0xFF) {
            0 -> false
            0xFF -> true
            else -> throw DerException("BOOLEAN is not 00 or ff")
        }
    }

    /** A non-negative INTEGER that fits a Long, in minimal form. */
    fun smallInteger(t: Tlv): Long {
        expect(t, Der.INTEGER, "INTEGER")
        val c = t.content
        if (c.isEmpty() || c.size > 8) throw DerException("INTEGER length")
        if (c[0].toInt() and 0x80 != 0) throw DerException("negative INTEGER")
        if (c.size > 1 && c[0] == 0.toByte() && c[1].toInt() and 0x80 == 0) throw DerException("non-minimal INTEGER")
        return BigInteger(1, c).toLong().also { if (it < 0) throw DerException("INTEGER too large") }
    }

    /** `YYMMDDHHMMSSZ` (UTCTime, 1950..2049) or `YYYYMMDDHHMMSSZ` (GeneralizedTime, 2050 and later): the RFC 5280 forms only. */
    fun time(t: Tlv): Long {
        val s = String(t.content, Charsets.US_ASCII)
        val (digits, year) = when (t.tag) {
            Der.UTC_TIME -> {
                if (s.length != 13 || s.last() != 'Z') throw DerException("UTCTime form")
                val yy = s.substring(0, 2).toIntOrNull() ?: throw DerException("UTCTime digits")
                s.substring(0, 12) to (if (yy >= 50) 1900 + yy else 2000 + yy)
            }
            Der.GENERALIZED_TIME -> {
                if (s.length != 15 || s.last() != 'Z') throw DerException("GeneralizedTime form")
                val yyyy = s.substring(0, 4).toIntOrNull() ?: throw DerException("GeneralizedTime digits")
                if (yyyy < 2050) throw DerException("GeneralizedTime before 2050 (RFC 5280 requires UTCTime)")
                s.substring(0, 14) to yyyy
            }
            else -> throw DerException("not a time")
        }
        if (!digits.all { it in '0'..'9' }) throw DerException("time digits")
        val rest = digits.substring(digits.length - 10)
        val mo = rest.substring(0, 2).toInt()
        val d = rest.substring(2, 4).toInt()
        val h = rest.substring(4, 6).toInt()
        val mi = rest.substring(6, 8).toInt()
        val sec = rest.substring(8, 10).toInt()
        if (mo !in 1..12 || d !in 1..31 || h > 23 || mi > 59 || sec > 59) throw DerException("time field out of range")
        return try {
            ZonedDateTime.of(year, mo, d, h, mi, sec, 0, ZoneOffset.UTC).also { if (it.dayOfMonth != d) throw DerException("no such day") }.toEpochSecond()
        } catch (e: java.time.DateTimeException) {
            throw DerException("no such date")
        }
    }
}
