package xyz.mdhv.asom.ut

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString

/** UTC01: framing and size limits of `asom-ut-ctl/1` (ubuntu-touch.md 7.3). */
class Utc01FramingTest {
    private sealed interface Obs {
        data class Ok(val t: String, val canonical: String?) : Obs
        data class Reject(val code: String) : Obs
        data class Stream(val types: List<String>, val eof: Boolean) : Obs
    }

    /** A stream that hands out at most [step] bytes per read, like a pipe under load. */
    private class Trickle(private val data: ByteArray, private val step: Int) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, step, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun typeOf(encoded: ByteArray): String = ((parseJ(String(encoded, Charsets.UTF_8)) as JObject)["t"] as JString).value

    private fun paddedBorrow(total: Int): ByteArray {
        val head = """{"t":"borrow","rid":"r1","model":"m","messages":[{"role":"user","content":""""
        val tail = """"}],"maxTokens":1,"stream":false}"""
        val pad = total - head.length - tail.length
        require(pad >= 0)
        return (head + "a".repeat(pad) + tail).toByteArray(Charsets.UTF_8).also { check(it.size == total) }
    }

    private fun lineBytes(input: JObject): ByteArray = when (input.str("kind")) {
        "ui-line", "node-line" -> input.str("line").toByteArray(Charsets.UTF_8)
        "ui-bytes" -> Hex.decode(input.str("hex"))
        "ui-padded" -> {
            val n = input.int("totalBytes").toInt()
            if (n <= 1 shl 20) paddedBorrow(n) else ByteArray(n) { 'a'.code.toByte() }
        }
        else -> error("no line for kind ${input.str("kind")}")
    }

    private fun readOneLine(bytes: ByteArray): Line = LineReader(ByteArrayInputStream(bytes + '\n'.code.toByte())).next()

    private fun observe(v: UtcVector, laws: Laws): Obs {
        val input = v.input
        return when (val kind = input.str("kind")) {
            "ui-line", "ui-bytes", "ui-padded" -> {
                val bytes = lineBytes(input)
                when (val line = readOneLine(bytes)) {
                    Line.TooLong -> Obs.Reject("TOO_LONG")
                    Line.Eof -> fail("a terminated line was reported as end of input")
                    is Line.Data -> when (val d = FrameCodec.decodeUi(line.bytes)) {
                        is Decoded.Bad -> Obs.Reject(d.reason.name)
                        is Decoded.Ok -> {
                            val enc = FrameCodec.encodeUi(d.frame)
                            assertEquals(d.frame, (FrameCodec.decodeUi(enc) as Decoded.Ok).frame, "${v.id}: decode(encode(frame)) != frame")
                            laws.bump("ui-roundtrip")
                            Obs.Ok(typeOf(enc), if (bytes.size > 1000) null else String(enc, Charsets.UTF_8))
                        }
                    }
                }
            }
            "node-line" -> when (val d = FrameCodec.decodeNode(lineBytes(input))) {
                is Decoded.Bad -> Obs.Reject(d.reason.name)
                is Decoded.Ok -> {
                    val enc = FrameCodec.encode(d.frame)
                    assertEquals(d.frame, (FrameCodec.decodeNode(enc) as Decoded.Ok).frame, "${v.id}: node roundtrip")
                    assertTrue(enc.none { it == '\n'.code.toByte() || it == '\r'.code.toByte() }, "${v.id}: an encoded frame holds a raw line break")
                    laws.bump("node-roundtrip")
                    Obs.Ok(typeOf(enc), String(enc, Charsets.UTF_8))
                }
            }
            "split" -> {
                val text = input.arr("lines").joinToString("") { it.asStr() + "\n" }.toByteArray(Charsets.UTF_8)
                val step = input.int("step").toInt()
                val reader = LineReader(Trickle(text, step))
                val types = ArrayList<String>()
                while (true) {
                    when (val l = reader.next()) {
                        Line.Eof -> break
                        Line.TooLong -> fail("${v.id}: unexpected TooLong")
                        is Line.Data -> types += typeOf(FrameCodec.encodeUi((FrameCodec.decodeUi(l.bytes) as Decoded.Ok).frame))
                    }
                }
                laws.bump("split-$step".let { if (step == 1 || step == 7) it else "split-all" })
                Obs.Stream(types, true)
            }
            "raw-stream" -> {
                val reader = LineReader(ByteArrayInputStream(input.str("text").toByteArray(Charsets.UTF_8)))
                val first = reader.next()
                assertTrue(first === Line.Eof, "${v.id}: a line cut by end of input was delivered")
                laws.bump("eof-partial")
                Obs.Stream(emptyList(), true)
            }
            else -> fail("${v.id}: unknown kind $kind")
        }
    }

    @Test
    fun vectors() {
        val vectors = UtcVectors.load("UTC01-framing.json", "UTC01")
        val laws = Laws("UTC01")
        for (v in vectors) {
            val obs = observe(v, laws)
            if (v.input.str("kind") == "ui-padded") laws.bump(if (obs is Obs.Ok) "boundary-accepted" else "boundary-refused")
            when {
                v.expectReject != null -> {
                    assertEquals(Obs.Reject(v.expectReject), obs, "${v.id}: ${v.description}")
                    laws.bump("reject-${v.expectReject}")
                }
                obs is Obs.Ok -> {
                    val want = v.expectOk!!.asObj()
                    assertEquals(want.str("t"), obs.t, "${v.id}: ${v.description}")
                    want.strOrNull("canonical")?.let { assertEquals(it, obs.canonical, "${v.id}: canonical form") }
                    laws.bump(if (v.input.str("kind") == "node-line") "node-ok-${obs.t}" else "ui-ok-${obs.t}")
                }
                obs is Obs.Stream -> {
                    val want = v.expectOk!!.asObj()
                    assertEquals(want.arr("types").map { it.asStr() }, obs.types, "${v.id}: ${v.description}")
                    assertEquals(want.boolOr("eof", true), obs.eof)
                }
                else -> fail("${v.id}: ${v.description}: observed $obs, expected ok")
            }
        }
        val uiTypes = listOf("hello", "lifecycle", "borrow", "cancel", "peers", "pair", "revoke", "ledger", "export", "selftest", "shutdown")
        val nodeTypes = listOf("hello_ack", "state", "chunk", "end", "error", "peers", "sas", "rows", "export_ready", "selftest")
        val required = uiTypes.map { "ui-ok-$it" } + nodeTypes.map { "node-ok-$it" } + BadFrame.entries.map { "reject-${it.name}" } +
            listOf("boundary-accepted", "boundary-refused", "split-1", "split-7", "split-all", "eof-partial", "ui-roundtrip", "node-roundtrip")
        laws.requireAll(required.toSet(), minimumVectors = 70, vectors = vectors.size)
    }

    @Test
    fun lineReaderNeverAllocatesPastTheCap() {
        val huge = object : InputStream() {
            var served = 0L
            override fun read(): Int = 'a'.code.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte())
                served += len
                return len
            }
        }
        val reader = LineReader(huge)
        assertEquals(Line.TooLong, reader.next())
        assertTrue(huge.served <= (1 shl 20) + 8192, "read ${huge.served} bytes before refusing an endless line")
    }

    @Test
    fun aFrameOverTheCapIsRefusedByTheWriterNotTruncated() {
        val out = java.io.ByteArrayOutputStream()
        val big = NodeFrame.Chunk("r1", "x".repeat(CtlProtocol.MAX_LINE_BYTES))
        try {
            FrameWriter(out).write(big)
            fail("an over-cap frame was written")
        } catch (_: FrameTooLargeException) {
        }
        assertEquals(0, out.size(), "nothing may be written for a refused frame")
    }
}
