package xyz.mdhv.asom.ut

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString

/** Property tests for the codec, over strings a real prompt or a hostile peer name can contain. */
class ControlChannelTest {
    private val rnd = java.util.SplittableRandom(7)

    private fun randomString(max: Int): String {
        val sb = StringBuilder()
        val n = rnd.nextInt(max + 1)
        while (sb.length < n) {
            when (rnd.nextInt(8)) {
                0 -> sb.append('\n')
                1 -> sb.append('\r')
                2 -> sb.append(rnd.nextInt(0x20).toChar())
                3 -> sb.append(if (rnd.nextBoolean()) ' ' else ' ')
                4 -> sb.appendCodePoint(0x1F600 + rnd.nextInt(50))
                5 -> sb.append('"').append('\\')
                6 -> sb.append((0x4E00 + rnd.nextInt(200)).toChar())
                else -> sb.append(('a'.code + rnd.nextInt(26)).toChar())
            }
        }
        return sb.toString()
    }

    @Test
    fun everyEncodedNodeFrameIsOneLineAndRoundTrips() {
        var cases = 0
        repeat(3000) {
            val delta = randomString(80)
            val frames: List<NodeFrame> = listOf(
                NodeFrame.Chunk("r-${rnd.nextInt(1000)}", delta),
                NodeFrame.Error(if (rnd.nextBoolean()) null else "r1", UiErrorCode.entries[rnd.nextInt(UiErrorCode.entries.size)]),
                NodeFrame.PeersList(listOf(JObject(listOf("alias" to JString(delta))))),
                NodeFrame.Rows(listOf(JObject(listOf("a" to JString(delta), "n" to JInt(rnd.nextLong(-1000, 1000)), "l" to JArray(listOf(JString(delta))))))),
                NodeFrame.State(NodeStates.ALL.elementAt(rnd.nextInt(3)), rnd.nextLong(0, 100), "off"),
            )
            for (f in frames) {
                val bytes = FrameCodec.encode(f)
                assertTrue(bytes.none { it == '\n'.code.toByte() || it == '\r'.code.toByte() }, "raw line break in $f")
                assertTrue(String(bytes, Charsets.UTF_8).none { it == ' ' || it == ' ' }, "raw U+2028/2029")
                assertEquals(f, (FrameCodec.decodeNode(bytes) as Decoded.Ok).frame)
                cases++
            }
        }
        println("ControlChannelTest: $cases node frames round-tripped")
    }

    @Test
    fun everyUiFrameRoundTrips() {
        var cases = 0
        repeat(2000) {
            val text = randomString(60)
            val frames: List<UiFrame> = listOf(
                UiFrame.Borrow("r${rnd.nextInt(99)}", "m-${rnd.nextInt(9)}", listOf(ChatMessage("user", text), ChatMessage("assistant", randomString(20))), 1 + rnd.nextLong(1000), rnd.nextBoolean()),
                UiFrame.Pair(PairOp.BEGIN, text.ifEmpty { "x" }),
                UiFrame.Lifecycle(UiLifecycle.entries[rnd.nextInt(3)]),
                UiFrame.LedgerQuery(rnd.nextLong(0, 1L shl 40), 1 + rnd.nextLong(500)),
            )
            for (f in frames) {
                val bytes = FrameCodec.encodeUi(f)
                assertTrue(bytes.none { it == '\n'.code.toByte() })
                assertEquals(f, (FrameCodec.decodeUi(bytes) as Decoded.Ok).frame)
                cases++
            }
        }
        println("ControlChannelTest: $cases UI frames round-tripped")
    }

    @Test
    fun aStreamOfRandomFramesSurvivesAnyChunking() {
        val frames = (0 until 200).map { NodeFrame.Chunk("r1", randomString(200)) }
        val bytes = frames.flatMap { (FrameCodec.encode(it) + '\n'.code.toByte()).toList() }.toByteArray()
        for (step in listOf(1, 2, 3, 5, 64, 4096)) {
            val stream = object : java.io.InputStream() {
                var pos = 0
                override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xff else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos >= bytes.size) return -1
                    val n = minOf(len, step, bytes.size - pos)
                    System.arraycopy(bytes, pos, b, off, n)
                    pos += n
                    return n
                }
            }
            val reader = LineReader(stream)
            val got = ArrayList<NodeFrame>()
            while (true) {
                when (val l = reader.next()) {
                    Line.Eof -> break
                    Line.TooLong -> error("unexpected TooLong")
                    is Line.Data -> got += (FrameCodec.decodeNode(l.bytes) as Decoded.Ok).frame
                }
            }
            assertEquals<List<NodeFrame>>(frames, got, "chunk size $step")
        }
    }

    @Test
    fun aHostileByteNeverEscapesAsAnException() {
        var rejected = 0
        repeat(20000) {
            val n = rnd.nextInt(60)
            val b = ByteArray(n) { (if (rnd.nextInt(3) == 0) "{}[]\":,-01e.".random(kotlin.random.Random(rnd.nextLong())).code else rnd.nextInt(256)).toByte() }
            if (FrameCodec.decodeUi(b) is Decoded.Bad) rejected++
            FrameCodec.decodeNode(b)
        }
        assertTrue(rejected > 19000, "random bytes should almost never be a valid frame")
    }

    @Test
    fun readerHandlesBackToBackEmptyLinesAndCrlf() {
        val reader = LineReader(ByteArrayInputStream("\n\n{\"t\":\"shutdown\"}\r\n".toByteArray()))
        assertEquals(0, (reader.next() as Line.Data).bytes.size)
        assertEquals(0, (reader.next() as Line.Data).bytes.size)
        val third = (reader.next() as Line.Data).bytes
        assertEquals(UiFrame.Shutdown, (FrameCodec.decodeUi(third) as Decoded.Ok).frame, "a trailing CR is JSON whitespace")
        assertEquals(Line.Eof, reader.next())
    }

    @Test
    fun readerCapIsExactAtEveryBufferAlignment() {
        for (cap in listOf(1, 7, 8191, 8192, 8193, 16384, CtlProtocol.MAX_LINE_BYTES)) {
            val at = ByteArray(cap) { 'a'.code.toByte() } + '\n'.code.toByte()
            val ok = LineReader(ByteArrayInputStream(at), cap).next()
            assertEquals(cap, (ok as Line.Data).bytes.size, "a line of exactly $cap bytes is accepted")
            val over = ByteArray(cap + 1) { 'a'.code.toByte() } + '\n'.code.toByte()
            assertEquals(Line.TooLong, LineReader(ByteArrayInputStream(over), cap).next(), "a line of $cap + 1 bytes is refused by the reader itself")
        }
    }
}
