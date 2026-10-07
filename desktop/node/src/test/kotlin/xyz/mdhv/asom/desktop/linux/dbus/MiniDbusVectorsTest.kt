package xyz.mdhv.asom.desktop.linux.dbus

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import xyz.mdhv.asom.desktop.linux.LawCounter

/**
 * The wire decoder and encoder against REAL bytes recorded from a dbus-daemon by `resources/dbus/record_vectors.py`
 * (an independent Python implementation), in both endiannesses, plus truncated, oversized and hand-broken variants
 * derived from them. Every law counts the cases that exercised it and the test fails if a count is zero.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName::class)
class MiniDbusVectorsTest {
    private class Vector(val id: String, val kind: String, val expect: String, val bytes: ByteArray)

    private val vectors: List<Vector> = run {
        val text = MiniDbusVectorsTest::class.java.getResourceAsStream("/dbus/recorded.txt")!!.readBytes().toString(Charsets.UTF_8)
        text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val f = line.split(" ; ")
            check(f.size == 4) { "bad vector line: $line" }
            Vector(f[0], f[1], f[2], f[3].chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }.toList()
    }

    private fun of(kind: String) = vectors.filter { it.kind == kind }
    private fun accepted(endian: String) = of("signal-accepted").first { "endian=$endian" in it.expect && it.expect.startsWith("true") }

    private val laws = LawCounter(
        listOf(
            "recorded-accepted-le", "recorded-accepted-be", "recorded-ignored", "recorded-method-return", "outbound-bytes-equal",
            "truncated-every-prefix", "oversize-refused-before-read", "malformed-rejected", "accept-rule-table", "sasl-line",
            "stream-framing", "unknown-header-field-skipped",
        ),
    )

    @Test
    fun `vectors from the daemon decode and are accepted or ignored as recorded, both endiannesses`() {
        assertTrue(vectors.size >= 10, "vector file is nearly empty: ${vectors.size}")
        for (v in of("signal-accepted")) {
            val big = "endian=be" in v.expect
            val m = DbusWire.decode(v.bytes)
            assertEquals(big, m.bigEndian, v.id)
            assertEquals(v.expect.startsWith("true"), MiniDbus.accept(m), v.id)
            laws.hit(if (big) "recorded-accepted-be" else "recorded-accepted-le")
        }
        for (v in of("signal-ignored")) {
            assertNull(MiniDbus.accept(DbusWire.decode(v.bytes)), v.id)
            laws.hit("recorded-ignored")
        }
        for (v in of("method-return")) {
            val m = DbusWire.decode(v.bytes)
            assertEquals(DbusType.METHOD_RETURN, m.type)
            val reply = Regex("reply=(\\d+)").find(v.expect)!!.groupValues[1].toLong()
            assertEquals(reply, m.replySerial, v.id)
            Regex("unique=(\\S+)").find(v.expect)?.let { assertEquals(it.groupValues[1], m.bodyString(), v.id) }
            laws.hit("recorded-method-return")
        }
    }

    @Test
    fun `the client's own two calls are byte-identical to the Python reference encoder`() {
        for (v in of("outbound-call")) {
            val serial = Regex("serial=(\\d+)").find(v.expect)!!.groupValues[1].toLong()
            val member = Regex("member=(\\w+)").find(v.expect)!!.groupValues[1]
            val arg = Regex("arg=(.*)$").find(v.expect)?.groupValues?.get(1)
            val mine = DbusWire.encodeMethodCall(serial, MiniDbus.BUS_NAME, MiniDbus.BUS_PATH, MiniDbus.BUS_NAME, member, arg)
            assertContentEquals(v.bytes, mine, "${v.id}: Kotlin encoder differs from the Python encoder")
            val back = DbusWire.decode(mine)
            assertEquals(member, back.member)
            assertEquals(MiniDbus.BUS_NAME, back.destination)
            laws.hit("outbound-bytes-equal")
        }
        assertEquals(setOf("call-hello-le", "call-addmatch-le"), of("outbound-call").map { it.id }.toSet())
        assertEquals(MiniDbus.MATCH_RULE, Regex("arg=(.*)$").find(of("outbound-call").first { it.id == "call-addmatch-le" }.expect)!!.groupValues[1])
    }

    @Test
    fun `every proper prefix of every recorded message is truncated, never a message and never a crash`() {
        for (v in vectors) {
            assertNull(DbusWire.read(ByteArrayInputStream(ByteArray(0))), "empty stream is a clean end")
            for (n in 1 until v.bytes.size) {
                val prefix = v.bytes.copyOf(n)
                assertFailsWith<DbusTruncatedException>("${v.id} prefix $n via read") { DbusWire.read(ByteArrayInputStream(prefix)) }
                if (n >= 16) assertFailsWith<DbusTruncatedException>("${v.id} prefix $n via decode") { DbusWire.decode(prefix) }
                laws.hit("truncated-every-prefix")
            }
            assertNotNull(DbusWire.read(ByteArrayInputStream(v.bytes)), "${v.id}: the whole message reads")
        }
    }

    /** An input that serves the first bytes and then FAILS if read again, proving no allocation-and-read past the header. */
    private class HeaderOnly(private val head: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < head.size) head[pos++].toInt() and 0xff else throw AssertionError("read past the header of an oversized message")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= head.size) throw AssertionError("read past the header of an oversized message")
            val n = minOf(len, head.size - pos)
            System.arraycopy(head, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun patchU32(bytes: ByteArray, offset: Int, value: Long, big: Boolean): ByteArray {
        val c = bytes.copyOf()
        for (i in 0 until 4) {
            val shift = if (big) (3 - i) * 8 else i * 8
            c[offset + i] = ((value shr shift) and 0xff).toByte()
        }
        return c
    }

    @Test
    fun `an oversized declared length is refused after the 16 header bytes and before anything is allocated or read`() {
        for (endian in listOf("le", "be")) {
            val big = endian == "be"
            val good = accepted(endian).bytes
            for (bodyLen in listOf((1L shl 20) + 1, 0x7fffffffL, 0xffffffffL)) {
                val bad = patchU32(good, 4, bodyLen, big).copyOf(16)
                assertFailsWith<DbusOversizeException>("$endian body length $bodyLen") { DbusWire.read(HeaderOnly(bad)) }
                laws.hit("oversize-refused-before-read")
            }
            for (fieldsLen in listOf((1L shl 20), 0xfffffff0L)) {
                val bad = patchU32(good, 12, fieldsLen, big).copyOf(16)
                assertFailsWith<DbusOversizeException>("$endian header fields length $fieldsLen") { DbusWire.read(HeaderOnly(bad)) }
                laws.hit("oversize-refused-before-read")
            }
        }
        // exactly at the cap is allowed by the length check (the message itself is then simply not present); one over is not
        val good = accepted("le").bytes
        val headerPart = good.size - 4
        assertEquals(DbusWire.MAX_MESSAGE_BYTES, DbusWire.totalLength(patchU32(good, 4, (DbusWire.MAX_MESSAGE_BYTES - headerPart).toLong(), false)))
        assertFailsWith<DbusOversizeException> { DbusWire.totalLength(patchU32(good, 4, (DbusWire.MAX_MESSAGE_BYTES - headerPart + 1).toLong(), false)) }
    }

    @Test
    fun `hand-broken variants of a good message are rejected with a protocol error`() {
        val le = accepted("le").bytes
        val be = accepted("be").bytes
        fun rejects(name: String, bytes: ByteArray) {
            assertFailsWith<DbusProtocolException>(name) { MiniDbus.accept(DbusWire.decode(bytes)) }
            laws.hit("malformed-rejected")
        }
        fun idx(bytes: ByteArray, needle: String): Int {
            val n = needle.toByteArray()
            outer@ for (i in 0..bytes.size - n.size) {
                for (j in n.indices) if (bytes[i + j] != n[j]) continue@outer
                return i
            }
            error("needle $needle not in message")
        }
        for ((tag, good, big) in listOf(Triple("le", le, false), Triple("be", be, true))) {
            rejects("$tag: bad endianness byte", good.copyOf().also { it[0] = 'x'.code.toByte() })
            rejects("$tag: protocol version 2", good.copyOf().also { it[3] = 2 })
            rejects("$tag: serial 0", patchU32(good, 8, 0, big))
            rejects("$tag: trailing byte after the message", good + byteArrayOf(0))
            rejects("$tag: body length grows by 8 (body is then not one boolean)", patchU32(good, 4, 12, big) + ByteArray(8))
            rejects("$tag: boolean value 2", good.copyOf().also { it[good.size - (if (big) 1 else 4)] = 2 })
            rejects("$tag: header signature 'b' -> 's' with a 4-byte body", good.copyOf().also { it[idx(good, "b\u0000") ] = 's'.code.toByte() })
            rejects("$tag: fields array shorter than its content", patchU32(good, 12, 8, big))
            rejects("$tag: fields array longer than the message", patchU32(good, 12, 4096, big))
            rejects("$tag: non-UTF-8 byte in the member name", good.copyOf().also { it[idx(good, "PrepareForSleep")] = 0xff.toByte() })
            rejects("$tag: NUL inside the member name", good.copyOf().also { it[idx(good, "PrepareForSleep") + 3] = 0 })
            rejects("$tag: member string not NUL-terminated", good.copyOf().also { it[idx(good, "PrepareForSleep") + "PrepareForSleep".length] = 'X'.code.toByte() })
            rejects("$tag: non-zero padding inside a header field (offset 87, between the interface string and the next 8-aligned field)", good.copyOf().also { g ->
                assertEquals(0, g[87].toInt(), "$tag: the recorded message must have a zero pad byte at offset 87")
                g[87] = 1
            })
            rejects("$tag: non-zero padding after the fields", good.copyOf().also { g ->
                val start = 16 + (if (big) java.nio.ByteBuffer.wrap(g, 12, 4).int else java.nio.ByteBuffer.wrap(g, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
                val pad = (8 - start % 8) % 8
                assertTrue(pad > 0, "$tag: the recorded message has no padding to corrupt")
                g[start] = 1
            })
        }
        rejects("a signal whose only header field is a duplicate PATH", duplicatedPathSignal())
        rejects("a signal without a member", signalWithout(3))
        rejects("a signal without an interface", signalWithout(2))
        rejects("a signal without a path", signalWithout(1))
    }

    private fun rawSignal(fields: List<Triple<Int, String, String>>, body: ByteArray = byteArrayOf(1, 0, 0, 0)): ByteArray {
        val f = java.io.ByteArrayOutputStream()
        fun align(n: Int) { repeat((n - (16 + f.size()) % n) % n) { f.write(0) } }
        fun u32(v: Int) { align(4); f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())) }
        for ((code, sig, value) in fields) {
            align(8); f.write(code); f.write(sig.length); f.write(sig.toByteArray()); f.write(0)
            if (sig == "g") { f.write(value.length); f.write(value.toByteArray()); f.write(0) } else { u32(value.length); f.write(value.toByteArray()); f.write(0) }
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf('l'.code.toByte(), 4, 0, 1, body.size.toByte(), 0, 0, 0, 5, 0, 0, 0, f.size().toByte(), (f.size() shr 8).toByte(), 0, 0))
        out.write(f.toByteArray())
        repeat((8 - out.size() % 8) % 8) { out.write(0) }
        out.write(body)
        return out.toByteArray()
    }

    private val goodFields = listOf(Triple(1, "o", "/org/freedesktop/login1"), Triple(2, "s", "org.freedesktop.login1.Manager"), Triple(3, "s", "PrepareForSleep"), Triple(8, "g", "b"))

    private fun duplicatedPathSignal() = rawSignal(goodFields + Triple(1, "o", "/x"))
    private fun signalWithout(code: Int) = rawSignal(goodFields.filter { it.first != code })

    @Test
    fun `a hand-built well-formed message decodes, so the rejections above are not an artefact of a broken builder`() {
        val m = DbusWire.decode(rawSignal(goodFields + Triple(7, "s", ":1.9")))
        assertEquals(true, MiniDbus.accept(m))
        laws.hit("accept-rule-table")
    }

    @Test
    fun `unknown header fields are skipped when they hold a single well-formed value, never trusted`() {
        // code 9 is UNIX_FDS (u); code 200 is unknown. Both must be skipped; the signal is still accepted.
        val m = DbusWire.decode(rawSignal(goodFields + Triple(7, "s", ":1.9") + Triple(200, "s", "ignored")))
        assertEquals(true, MiniDbus.accept(m))
        laws.hit("unknown-header-field-skipped")
        assertFailsWith<DbusProtocolException> { DbusWire.decode(rawSignal(goodFields + Triple(200, "zz", "x"))) }
        laws.hit("malformed-rejected")
    }

    @Test
    fun `the accept rule takes only a broadcast PrepareForSleep from a unique name with a boolean body`() {
        fun signal(edit: (MutableList<Triple<Int, String, String>>) -> Unit, body: ByteArray = byteArrayOf(1, 0, 0, 0)): Boolean? {
            val f = (goodFields + Triple(7, "s", ":1.9")).toMutableList()
            edit(f)
            return MiniDbus.accept(DbusWire.decode(rawSignal(f, body)))
        }
        assertEquals(true, signal({}))
        assertEquals(false, signal({}, byteArrayOf(0, 0, 0, 0)))
        assertNull(signal({ it += Triple(6, "s", ":1.9") }), "a unicast signal (destination set) is dropped")
        assertNull(signal({ it.removeAll { f -> f.first == 7 } }), "no sender")
        assertNull(signal({ it.replaceAll { f -> if (f.first == 7) Triple(7, "s", "org.freedesktop.login1") else f } }), "sender is a well-known name, not a unique name")
        assertNull(signal({ it.replaceAll { f -> if (f.first == 3) Triple(3, "s", "PrepareForShutdown") else f } }), "other member")
        assertNull(signal({ it.replaceAll { f -> if (f.first == 2) Triple(2, "s", "org.freedesktop.login1.Seat") else f } }), "other interface")
        assertNull(signal({ it.replaceAll { f -> if (f.first == 1) Triple(1, "o", "/org/freedesktop/login1/seat0") else f } }), "other path")
        assertFailsWith<DbusProtocolException>("a matching signal with a body that is not one boolean fails the connection") { signal({}, byteArrayOf(2, 0, 0, 0)) }
        val call = DbusWire.decode(DbusWire.encodeMethodCall(7, "x", "/org/freedesktop/login1", "org.freedesktop.login1.Manager", "PrepareForSleep"))
        assertNull(MiniDbus.accept(call), "a method call is never a signal")
        repeat(9) { laws.hit("accept-rule-table") }
    }

    @Test
    fun `SASL EXTERNAL sends the hex of the ASCII decimal uid`() {
        for ((uid, hex) in mapOf(0 to "30", 1000 to "31303030", 65534 to "3635353334", 7 to "37")) {
            assertEquals("AUTH EXTERNAL $hex\r\n", MiniDbus.saslExternalLine(uid))
            laws.hit("sasl-line")
        }
        assertFailsWith<IllegalArgumentException> { MiniDbus.saslExternalLine(-1) }
    }

    @Test
    fun `a stream of several messages is framed correctly, whatever the chunking`() {
        val a = accepted("le").bytes
        val b = accepted("be").bytes
        val c = of("method-return").first().bytes
        val all = a + b + c + a
        // deliver one byte at a time
        val slow = object : InputStream() {
            var p = 0
            override fun read(): Int = if (p < all.size) all[p++].toInt() and 0xff else -1
            override fun read(buf: ByteArray, off: Int, len: Int): Int {
                if (p >= all.size) return -1
                buf[off] = all[p++]
                return 1
            }
        }
        val got = generateSequence { DbusWire.read(slow) }.toList()
        assertEquals(4, got.size)
        assertEquals(listOf<Boolean?>(true, true, null, true), got.map { MiniDbus.accept(it) })
        assertEquals(listOf(false, true, false, false), got.map { it.bigEndian })
        laws.hit("stream-framing")
    }

    @Test
    fun zzEveryLawExercisedAtLeastOnce() {
        laws.assertAllExercised("MiniDbusVectors")
    }
}
