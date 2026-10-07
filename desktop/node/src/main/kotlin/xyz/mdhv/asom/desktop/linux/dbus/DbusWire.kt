package xyz.mdhv.asom.desktop.linux.dbus

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A message that breaks the D-Bus wire format or this client's limits. The connection is dropped, never repaired. */
open class DbusProtocolException(message: String) : java.io.IOException(message)

/** The stream ended inside a message. */
class DbusTruncatedException(message: String) : DbusProtocolException(message)

/** A message (or its declared length) is over the 1 MiB cap. Checked before any allocation. */
class DbusOversizeException(message: String) : DbusProtocolException(message)

object DbusType {
    const val METHOD_CALL = 1
    const val METHOD_RETURN = 2
    const val ERROR = 3
    const val SIGNAL = 4
}

class DbusMessage(
    val bigEndian: Boolean,
    val type: Int,
    val flags: Int,
    val serial: Long,
    val path: String?,
    val iface: String?,
    val member: String?,
    val errorName: String?,
    val replySerial: Long?,
    val destination: String?,
    val sender: String?,
    val signature: String?,
    val body: ByteArray,
) {
    private fun order() = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN

    /** The body as one BOOLEAN (signature `b`): a uint32 that must be exactly 0 or 1 and fill the body. */
    fun bodyBoolean(): Boolean {
        if (signature != "b" || body.size != 4) throw DbusProtocolException("body is not a single boolean")
        return when (val v = ByteBuffer.wrap(body).order(order()).int) {
            0 -> false
            1 -> true
            else -> throw DbusProtocolException("boolean value $v is neither 0 nor 1")
        }
    }

    /** The body as one STRING (signature `s`). */
    fun bodyString(): String {
        if (signature != "s") throw DbusProtocolException("body is not a single string")
        val r = Reader(body, 0, body.size, bigEndian)
        val s = r.string()
        if (r.pos != body.size) throw DbusProtocolException("trailing bytes after the string")
        return s
    }
}

/** Bounds-checked cursor over one message. Alignment is relative to the start of the message, as the spec says. */
internal class Reader(private val buf: ByteArray, var pos: Int, private val end: Int, private val big: Boolean, private val base: Int = 0) {
    private fun need(n: Int) {
        if (n < 0 || pos + n > end) throw DbusProtocolException("field runs past its container")
    }

    fun align(n: Int) {
        val rel = pos - base
        val pad = (n - rel % n) % n
        need(pad)
        for (i in 0 until pad) if (buf[pos + i].toInt() != 0) throw DbusProtocolException("non-zero padding byte")
        pos += pad
    }

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xff
    }

    fun u32(): Long {
        align(4)
        need(4)
        val v = ByteBuffer.wrap(buf, pos, 4).order(if (big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        pos += 4
        return v
    }

    private fun utf8(from: Int, len: Int): String {
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            dec.decode(ByteBuffer.wrap(buf, from, len)).toString()
        } catch (_: CharacterCodingException) {
            throw DbusProtocolException("string is not valid UTF-8")
        }
    }

    /** STRING and OBJECT_PATH: uint32 length, bytes, NUL. No interior NUL. */
    fun string(): String {
        val n = u32()
        if (n > MAX_STRING) throw DbusProtocolException("string length $n is over the cap")
        need(n.toInt() + 1)
        val len = n.toInt()
        for (i in 0 until len) if (buf[pos + i].toInt() == 0) throw DbusProtocolException("NUL inside a string")
        if (buf[pos + len].toInt() != 0) throw DbusProtocolException("string is not NUL-terminated")
        val s = utf8(pos, len)
        pos += len + 1
        return s
    }

    /** SIGNATURE: uint8 length, bytes, NUL. */
    fun signature(): String {
        val n = u8()
        need(n + 1)
        for (i in 0 until n) if (buf[pos + i].toInt() == 0) throw DbusProtocolException("NUL inside a signature")
        if (buf[pos + n].toInt() != 0) throw DbusProtocolException("signature is not NUL-terminated")
        val s = String(buf, pos, n, Charsets.US_ASCII)
        pos += n + 1
        return s
    }

    /** Skips one value whose single complete type is `sig[i...]`; returns the index after that type. */
    fun skip(sig: String, start: Int, depth: Int = 0): Int {
        if (depth > MAX_DEPTH || start >= sig.length) throw DbusProtocolException("signature is too deep or ends early")
        var i = start
        when (sig[i]) {
            'y' -> { need(1); pos += 1 }
            'b', 'i', 'u', 'h' -> { u32() }
            'n', 'q' -> { align(2); need(2); pos += 2 }
            'x', 't', 'd' -> { align(8); need(8); pos += 8 }
            's', 'o' -> { string() }
            'g' -> { signature() }
            'v' -> {
                val inner = signature()
                if (inner.isEmpty()) throw DbusProtocolException("empty variant signature")
                val after = skip(inner, 0, depth + 1)
                if (after != inner.length) throw DbusProtocolException("variant holds more than one complete type")
            }
            'a' -> {
                val n = u32()
                if (n > MAX_ARRAY) throw DbusProtocolException("array length $n is over the cap")
                val elemStart = i + 1
                val elemEnd = typeEnd(sig, elemStart, depth + 1)
                align(alignOf(sig[elemStart]))
                need(n.toInt())
                val arrayEnd = pos + n.toInt()
                val sub = Reader(buf, pos, arrayEnd, big, base)
                while (sub.pos < arrayEnd) sub.skip(sig.substring(elemStart, elemEnd), 0, depth + 1)
                if (sub.pos != arrayEnd) throw DbusProtocolException("array elements overrun the array")
                pos = arrayEnd
                return elemEnd
            }
            '(' , '{' -> {
                align(8)
                val close = if (sig[i] == '(') ')' else '}'
                i++
                if (i < sig.length && sig[i] == close) throw DbusProtocolException("empty struct")
                while (i < sig.length && sig[i] != close) i = skip(sig, i, depth + 1)
                if (i >= sig.length) throw DbusProtocolException("unterminated struct")
            }
            else -> throw DbusProtocolException("unsupported type code '${sig[i]}'")
        }
        return i + 1
    }

    private fun typeEnd(sig: String, start: Int, depth: Int): Int {
        if (depth > MAX_DEPTH || start >= sig.length) throw DbusProtocolException("signature is too deep or ends early")
        return when (sig[start]) {
            'a' -> typeEnd(sig, start + 1, depth + 1)
            '(', '{' -> {
                val open = sig[start]
                val close = if (open == '(') ')' else '}'
                var i = start + 1
                while (i < sig.length && sig[i] != close) i = typeEnd(sig, i, depth + 1)
                if (i >= sig.length) throw DbusProtocolException("unterminated struct")
                i + 1
            }
            else -> start + 1
        }
    }

    private fun alignOf(c: Char): Int = when (c) {
        'y', 'g', 'v' -> 1
        'n', 'q' -> 2
        'b', 'i', 'u', 'h', 's', 'o', 'a' -> 4
        else -> 8
    }

    companion object {
        const val MAX_STRING = 1L shl 20
        const val MAX_ARRAY = 1L shl 20
        const val MAX_DEPTH = 32
    }
}

/** Decoder for the messages a read-only client receives, and an encoder for the two method calls it sends. */
object DbusWire {
    const val MAX_MESSAGE_BYTES = 1 shl 20
    private const val FIXED_HEADER = 16

    private const val F_PATH = 1
    private const val F_INTERFACE = 2
    private const val F_MEMBER = 3
    private const val F_ERROR_NAME = 4
    private const val F_REPLY_SERIAL = 5
    private const val F_DESTINATION = 6
    private const val F_SENDER = 7
    private const val F_SIGNATURE = 8

    /**
     * Reads one whole message from [input], or returns null when the stream ends cleanly BETWEEN messages. The declared
     * length is checked against the cap before the body is allocated.
     */
    fun read(input: InputStream): DbusMessage? {
        val head = ByteArray(FIXED_HEADER)
        val first = input.read(head, 0, FIXED_HEADER)
        if (first < 0) return null
        var got = first
        while (got < FIXED_HEADER) {
            val r = input.read(head, got, FIXED_HEADER - got)
            if (r < 0) throw DbusTruncatedException("stream ended inside the fixed header")
            got += r
        }
        val total = totalLength(head)
        val all = head.copyOf(total)
        var off = FIXED_HEADER
        while (off < total) {
            val r = input.read(all, off, total - off)
            if (r < 0) throw DbusTruncatedException("stream ended inside a message ($off of $total bytes)")
            off += r
        }
        return decode(all)
    }

    /** Total wire length from the 16 fixed bytes, refusing anything over the cap. */
    fun totalLength(head: ByteArray): Int {
        if (head.size < FIXED_HEADER) throw DbusTruncatedException("fewer than 16 header bytes")
        val big = when (head[0].toInt().toChar()) {
            'l' -> false
            'B' -> true
            else -> throw DbusProtocolException("bad endianness byte 0x${Integer.toHexString(head[0].toInt() and 0xff)}")
        }
        if (head[3].toInt() != 1) throw DbusProtocolException("unsupported protocol version ${head[3].toInt() and 0xff}")
        val bb = ByteBuffer.wrap(head).order(if (big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val bodyLen = bb.getInt(4).toLong() and 0xffffffffL
        val fieldsLen = bb.getInt(12).toLong() and 0xffffffffL
        val headerLen = FIXED_HEADER + fieldsLen
        val padded = (headerLen + 7) / 8 * 8
        val total = padded + bodyLen
        if (total > MAX_MESSAGE_BYTES) throw DbusOversizeException("message of $total bytes is over the ${MAX_MESSAGE_BYTES}-byte cap")
        return total.toInt()
    }

    /** Decodes exactly one complete message; [bytes] must be the whole wire form and nothing more. */
    fun decode(bytes: ByteArray): DbusMessage {
        val total = totalLength(bytes)
        if (bytes.size < total) throw DbusTruncatedException("message is ${bytes.size} bytes, declared $total")
        if (bytes.size > total) throw DbusProtocolException("${bytes.size - total} bytes after the declared end of the message")
        val big = bytes[0].toInt().toChar() == 'B'
        val bb = ByteBuffer.wrap(bytes).order(if (big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val type = bytes[1].toInt() and 0xff
        val flags = bytes[2].toInt() and 0xff
        val bodyLen = (bb.getInt(4).toLong() and 0xffffffffL).toInt()
        val serial = bb.getInt(8).toLong() and 0xffffffffL
        val fieldsLen = (bb.getInt(12).toLong() and 0xffffffffL).toInt()
        if (serial == 0L) throw DbusProtocolException("serial 0 is not allowed")
        val fieldsEnd = FIXED_HEADER + fieldsLen
        val bodyStart = (fieldsEnd + 7) / 8 * 8

        var path: String? = null
        var iface: String? = null
        var member: String? = null
        var errorName: String? = null
        var replySerial: Long? = null
        var destination: String? = null
        var sender: String? = null
        var signature: String? = null
        val seen = HashSet<Int>()
        val r = Reader(bytes, FIXED_HEADER, fieldsEnd, big)
        while (r.pos < fieldsEnd) {
            r.align(8)
            if (r.pos >= fieldsEnd) throw DbusProtocolException("header field array ends inside padding")
            val code = r.u8()
            if (code == 0) throw DbusProtocolException("header field code 0 is invalid")
            if (!seen.add(code)) throw DbusProtocolException("duplicate header field $code")
            val sig = r.signature()
            if (sig.isEmpty()) throw DbusProtocolException("empty header field signature")
            fun expect(want: String) {
                if (sig != want) throw DbusProtocolException("header field $code has signature \"$sig\", expected \"$want\"")
            }
            when (code) {
                F_PATH -> { expect("o"); path = r.string() }
                F_INTERFACE -> { expect("s"); iface = r.string() }
                F_MEMBER -> { expect("s"); member = r.string() }
                F_ERROR_NAME -> { expect("s"); errorName = r.string() }
                F_REPLY_SERIAL -> { expect("u"); replySerial = r.u32() }
                F_DESTINATION -> { expect("s"); destination = r.string() }
                F_SENDER -> { expect("s"); sender = r.string() }
                F_SIGNATURE -> { expect("g"); signature = r.signature() }
                else -> {
                    val after = r.skip(sig, 0)
                    if (after != sig.length) throw DbusProtocolException("unknown header field $code holds more than one type")
                }
            }
        }
        if (r.pos != fieldsEnd) throw DbusProtocolException("header fields overrun the declared array length")
        for (i in fieldsEnd until bodyStart) if (bytes[i].toInt() != 0) throw DbusProtocolException("non-zero padding before the body")
        when (type) {
            DbusType.METHOD_CALL -> if (path == null || member == null) throw DbusProtocolException("method call without path and member")
            DbusType.METHOD_RETURN -> if (replySerial == null) throw DbusProtocolException("method return without reply serial")
            DbusType.ERROR -> if (errorName == null || replySerial == null) throw DbusProtocolException("error without name and reply serial")
            DbusType.SIGNAL -> if (path == null || iface == null || member == null) throw DbusProtocolException("signal without path, interface and member")
        }
        if (signature == null && bodyLen != 0) throw DbusProtocolException("a body without a signature")
        return DbusMessage(big, type, flags, serial, path, iface, member, errorName, replySerial, destination, sender, signature, bytes.copyOfRange(bodyStart, bodyStart + bodyLen))
    }

    /** A little-endian METHOD_CALL, the only kind this client sends. [stringArg] is the single STRING argument, if any. */
    fun encodeMethodCall(serial: Long, destination: String, path: String, iface: String, member: String, stringArg: String? = null): ByteArray {
        require(serial in 1..0xffffffffL)
        val fields = LeWriter()
        fun field(code: Int, sig: String, write: LeWriter.() -> Unit) {
            fields.align(8)
            fields.u8(code)
            fields.signature(sig)
            fields.write()
        }
        field(F_PATH, "o") { string(path) }
        field(F_INTERFACE, "s") { string(iface) }
        field(F_MEMBER, "s") { string(member) }
        field(F_DESTINATION, "s") { string(destination) }
        if (stringArg != null) field(F_SIGNATURE, "g") { signature("s") }
        val body = LeWriter().apply { if (stringArg != null) string(stringArg) }.toByteArray()
        val fieldBytes = fields.toByteArray()

        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(FIXED_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        head.put('l'.code.toByte()).put(DbusType.METHOD_CALL.toByte()).put(0).put(1)
        head.putInt(body.size).putInt(serial.toInt()).putInt(fieldBytes.size)
        out.write(head.array())
        out.write(fieldBytes)
        repeat(((8 - (FIXED_HEADER + fieldBytes.size) % 8) % 8)) { out.write(0) }
        out.write(body)
        return out.toByteArray()
    }

    /** The header field array and the body both start on an 8-byte boundary, so a writer that starts at 0 aligns correctly. */
    private class LeWriter {
        private val out = ByteArrayOutputStream()

        fun align(n: Int) {
            repeat((n - out.size() % n) % n) { out.write(0) }
        }

        fun u8(v: Int) = out.write(v)

        fun u32(v: Long) {
            align(4)
            out.write((v and 0xff).toInt()); out.write(((v shr 8) and 0xff).toInt())
            out.write(((v shr 16) and 0xff).toInt()); out.write(((v shr 24) and 0xff).toInt())
        }

        fun string(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            u32(b.size.toLong())
            out.write(b)
            out.write(0)
        }

        fun signature(s: String) {
            out.write(s.length)
            out.write(s.toByteArray(Charsets.US_ASCII))
            out.write(0)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
