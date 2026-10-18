package xyz.mdhv.asom.lab.proto.tls

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.proto.trust.LawCounters

/** An in-memory [NetChannel]: reads come from a script, in chunks of a chosen size; writes are kept. */
class ScriptedNet(private val script: ByteArray, private val chunk: Int) : NetChannel {
    private var at = 0
    val written = ByteArrayOutputStream()

    override fun read(dst: ByteBuffer, timeoutMs: Long): Int {
        if (at >= script.size) return -1
        val n = minOf(chunk, dst.remaining(), script.size - at)
        dst.put(script, at, n)
        at += n
        return n
    }

    override fun write(src: ByteBuffer, timeoutMs: Long) {
        val b = ByteArray(src.remaining())
        src.get(b)
        written.write(b)
    }

    override fun shutdownOutput() = Unit

    override fun close() = Unit
}

/**
 * The record tap checked on its own, against ClientHellos built by hand (RawHello): it must SEE a `pre_shared_key`, an `early_data`, an SNI, a reused
 * session id, however the bytes arrive, and it must count raw bytes exactly. A tap that cannot see a PSK would make W08's "no pre_shared_key" vacuous,
 * which is why these cases exist. Evidence label: LAB, oracle: self.
 */
class RecordTapTest {
    companion object {
        val laws = LawCounters("record-tap")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("tap-detects-psk", "tap-detects-early-data", "tap-detects-sni", "tap-session-id-reuse", "tap-reassembly", "tap-exact-counts", "tap-clean-hello-clean"))

        fun drain(tap: RecordTap, bytes: Int) {
            val buf = ByteBuffer.allocate(1024)
            var total = 0
            while (true) {
                buf.clear()
                val n = tap.read(buf, 0)
                if (n < 0) break
                total += n
            }
            assertEquals(bytes, total)
        }
    }

    @Test
    fun itSeesAPreSharedKeyAnEarlyDataExtensionAndAnSni() {
        val hello = RawHello(earlyData = true, pskIdentity = ByteArray(24) { it.toByte() }, serverName = "peer.example", alpn = listOf("asom-mesh/1", "h2")).record()
        for (chunk in intArrayOf(1, 7, 64, 4096)) {
            val tap = RecordTap(ScriptedNet(hello, chunk))
            drain(tap, hello.size)
            val h = tap.clientHellos.single()
            assertTrue(h.hasPsk, "chunk $chunk: pre_shared_key")
            assertTrue(h.hasEarlyData, "chunk $chunk: early_data")
            assertTrue(h.hasServerName)
            assertEquals(listOf("peer.example"), h.serverNames)
            assertEquals(listOf("asom-mesh/1", "h2"), h.alpn)
            assertEquals(listOf(0x0304), h.supportedVersions)
            assertEquals(listOf(0x0403), h.signatureAlgorithms)
            assertEquals(listOf(0x0017), h.supportedGroups)
            assertEquals(41, h.extensionTypes.last(), "pre_shared_key is the last extension, as RFC 8446 requires")
            assertEquals(1, tap.pskClientHellos)
            assertEquals(1, tap.earlyDataClientHellos)
            laws.bump("tap-detects-psk")
            laws.bump("tap-detects-early-data")
            laws.bump("tap-detects-sni")
        }
    }

    @Test
    fun aCleanHelloIsReportedClean() {
        val hello = RawHello(alpn = listOf("asom-mesh/1")).record()
        val tap = RecordTap(ScriptedNet(hello, 33))
        drain(tap, hello.size)
        val h = tap.clientHellos.single()
        assertFalse(h.hasPsk)
        assertFalse(h.hasEarlyData)
        assertFalse(h.hasServerName)
        assertEquals(0, tap.pskClientHellos + tap.earlyDataClientHellos)
        laws.bump("tap-clean-hello-clean")
    }

    @Test
    fun aHelloSplitAcrossTwoRecordsIsReassembled() {
        val whole = RawHello(pskIdentity = ByteArray(10) { 1 }, seed = 8).record()
        val body = whole.copyOfRange(5, whole.size)
        val cut = 60
        fun record(part: ByteArray) = byteArrayOf(22, 3, 1, (part.size shr 8).toByte(), part.size.toByte()) + part
        val split = record(body.copyOfRange(0, cut)) + record(body.copyOfRange(cut, body.size))
        val tap = RecordTap(ScriptedNet(split, 11))
        drain(tap, split.size)
        assertEquals(2, tap.recordsIn)
        assertTrue(tap.clientHellos.single().hasPsk)
        laws.bump("tap-reassembly")
    }

    @Test
    fun aSessionIdUsedByAnotherConnectionIsFlagged() {
        val book = SessionIdBook()
        val a = RawHello(seed = 1).record()
        val first = RecordTap(ScriptedNet(a, 100), book)
        drain(first, a.size)
        assertEquals(0, first.sessionIdReuses)
        val again = RecordTap(ScriptedNet(a, 100), book)
        drain(again, a.size)
        assertEquals(1, again.sessionIdReuses, "the same legacy session id on a second connection")
        val fresh = RawHello(seed = 2).record()
        val third = RecordTap(ScriptedNet(fresh, 100), book)
        drain(third, fresh.size)
        assertEquals(0, third.sessionIdReuses)
        laws.bump("tap-session-id-reuse")
    }

    @Test
    fun rawByteCountsAreExactPerDirection() {
        val rnd = SplittableRandom(4)
        val inbound = ByteArray(5000) { rnd.nextInt(256).toByte() }
        val net = ScriptedNet(inbound, 313)
        val tap = RecordTap(net)
        drain(tap, inbound.size)
        val out = ByteArray(777) { rnd.nextInt(256).toByte() }
        tap.write(ByteBuffer.wrap(out), 0)
        assertEquals(inbound.size.toLong(), tap.bytesRead)
        assertEquals(out.size.toLong(), tap.bytesWritten)
        assertContentEquals(out, net.written.toByteArray(), "the tap never changes a byte")
        assertEquals(0, tap.malformedHellos + tap.pskClientHellos, "random bytes are not a hello")
        laws.bump("tap-exact-counts", 2)
    }
}
