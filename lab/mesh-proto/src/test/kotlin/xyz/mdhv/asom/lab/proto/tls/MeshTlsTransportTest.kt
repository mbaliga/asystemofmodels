package xyz.mdhv.asom.lab.proto.tls

import java.util.SplittableRandom
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Timeout
import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.trust.ChainReject
import xyz.mdhv.asom.lab.proto.trust.LawCounters
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.PeerRole

/**
 * The SSLEngine transport on loopback: an established session in both roles, exact byte counts against the record tap (L-L15 input), concurrent
 * streams, close behaviour, and the production-keys switch. Evidence label: LAB, oracle: self; JDK 17 and JDK 21 are both required, NOT DEVICE EVIDENCE.
 */
@Timeout(120)
class MeshTlsTransportTest {
    companion object {
        val laws = LawCounters("tls-transport")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("established-both-roles", "measured-bytes", "pairing-mode", "no-psk-hello", "full-duplex", "truncation-typed", "production-keys", "tap-equals-engine", "overhead-in-rfc8446-bound"))

        fun pattern(n: Int, seed: Long): ByteArray = SplittableRandom(seed).let { r -> ByteArray(n) { r.nextInt(256).toByte() } }

        fun readFully(c: TlsMeshConnection, n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = c.input.read(out, got, n - got)
                check(r > 0) { "stream ended after $got of $n bytes" }
                got += r
            }
            return out
        }
    }

    @Test
    fun anEstablishedSessionMovesBytesBothWaysAndTheCountsAreExact() {
        val (a, b) = HonestNode.pairedPair()
        val (cc, sc) = Loop.pair()
        val up = pattern(300_000, 1)
        val down = pattern(70_000, 2)
        val received = AtomicReference<ByteArray>()
        val (ca, sa) = both(
            {
                val at = a.dialPaired(cc, b.pin)
                val conn = at.end.value
                conn.output.write(up)
                val back = readFully(conn, down.size)
                assertContentEquals(down, back)
                conn.close()
                at
            },
            {
                val at = b.accept(sc)
                val conn = at.end.value
                received.set(readFully(conn, up.size))
                conn.output.write(down)
                assertEquals(-1, conn.input.read(), "the client's close_notify ends the stream")
                conn.close()
                at
            },
        )
        val client = ca.value
        val server = sa.value
        assertContentEquals(up, received.get())
        val cConn = client.end.value
        val sConn = server.end.value

        assertEquals(PeerRole.TLS_CLIENT, cConn.role)
        assertEquals(PeerRole.TLS_SERVER, sConn.role)
        assertEquals(ConnMode.ESTABLISHED, cConn.mode)
        assertEquals(ConnMode.ESTABLISHED, sConn.mode)
        assertEquals(b.pin, cConn.peerPin)
        assertEquals(a.pin, sConn.peerPin)
        assertEquals("TLSv1.3", cConn.facts.protocol)
        assertEquals(MeshTlsProfile.ALPN, sConn.facts.alpn)
        assertEquals(2, sConn.facts.peerChainLength)
        assertTrue(client.observer.trustInvocations == 1 && server.observer.trustInvocations == 1, "each side verified exactly one chain")
        laws.bump("established-both-roles", 2)

        val cc2 = cConn.counters()
        val sc2 = sConn.counters()
        assertTrue(cc2.measured && sc2.measured)
        laws.bump("measured-bytes", 2)
        assertEquals(cc2.networkBytesOut, sc2.networkBytesIn, "what the client wrapped is what the server unwrapped")
        assertEquals(cc2.networkBytesIn, sc2.networkBytesOut, "what the server wrapped is what the client unwrapped")
        assertTrue(cc2.networkBytesOut > up.size && sc2.networkBytesOut > down.size, "the network carries more than the application bytes")

        fun records(app: Int) = (app + 16_383) / 16_384
        val handshakeAllowance = 4_096L
        for ((who, net, app) in listOf(Triple("client", cc2.networkBytesOut, up.size), Triple("server", sc2.networkBytesOut, down.size))) {
            val overhead = net - app
            val floor = 22L * records(app)
            assertTrue(overhead >= floor, "$who: overhead $overhead is below the RFC 8446 record cost $floor")
            assertTrue(overhead <= floor + handshakeAllowance, "$who: overhead $overhead exceeds the record cost $floor plus the handshake allowance $handshakeAllowance")
        }
        laws.bump("overhead-in-rfc8446-bound", 2)

        assertEquals(cc2.networkBytesOut, client.tap.bytesWritten, "engine count equals the raw bytes the tap saw leave the client")
        assertEquals(cc2.networkBytesIn, client.tap.bytesRead)
        assertEquals(sc2.networkBytesOut, server.tap.bytesWritten)
        assertEquals(sc2.networkBytesIn, server.tap.bytesRead)
        assertEquals(client.tap.bytesWritten, server.tap.bytesRead, "the two taps agree about the wire")
        assertEquals(client.tap.bytesRead, server.tap.bytesWritten)
        laws.bump("tap-equals-engine", 4)

        assertEquals(0, client.tap.pskClientHellos)
        assertEquals(1, client.tap.clientHellos.size)
        laws.bump("no-psk-hello")
        println("tls-transport: client network out ${cc2.networkBytesOut} in ${cc2.networkBytesIn}; application out ${up.size} in ${down.size}")
    }

    @Test
    fun aPairingModeConnectionHasNoPeerPinButTheVerifiedOne() {
        val a = HonestNode(TestNode.testOnly("key1"))
        val b = HonestNode(TestNode.testOnly("key2"))
        val (cc, sc) = Loop.pair()
        val (ca, sa) = both(
            {
                val at = a.dial(cc, ChainMode.ExpectPairing(b.pin))
                at.end.value.also { it.close() }
            },
            {
                val at = b.accept(sc, windowOpen = true)
                at.end.value.also { it.close() }
            },
        )
        val c = ca.value
        val s = sa.value
        assertEquals(ConnMode.PAIRING, c.mode)
        assertEquals(ConnMode.PAIRING, s.mode)
        assertNull(c.peerPin)
        assertNull(s.peerPin)
        assertEquals(b.pin, c.verifiedPin)
        assertEquals(a.pin, s.verifiedPin)
        laws.bump("pairing-mode", 2)
    }

    @Test
    fun aListenerWithNoWindowRefusesAnUnknownDialer() {
        val a = HonestNode(TestNode.testOnly("key1"))
        val b = HonestNode(TestNode.testOnly("key2"))
        val (cc, sc) = Loop.pair()
        val (ca, sa) = both({ a.dial(cc, ChainMode.ExpectPairing(b.pin)) }, { b.accept(sc, windowOpen = false) })
        val server = sa.value
        assertEquals(MeshTlsRefusal.PEER_CHAIN_REJECTED, server.end.refusal.refusal)
        assertEquals(ChainReject.PIN_UNKNOWN, server.end.refusal.chainReject)
        laws.bump("pairing-mode")
        ca.value.end.let { e -> if (e is End.Ok) runCatching { e.value.close() } }
    }

    @Test
    fun repeatedDialsFromOneIdentityNeverOfferResumption() {
        val (a, b) = HonestNode.pairedPair()
        val book = SessionIdBook()
        var hellos = 0
        repeat(4) {
            val (cc, sc) = Loop.pair()
            val (ca, sa) = both(
                { a.dialPaired(cc, b.pin, book).also { at -> at.end.value.also { c -> c.output.write(1); readFully(c, 1); c.close() } } },
                { b.accept(sc).also { at -> at.end.value.also { s -> readFully(s, 1); s.output.write(2); s.close() } } },
            )
            val tap = ca.value.tap
            assertEquals(0, tap.pskClientHellos)
            assertEquals(0, tap.earlyDataClientHellos)
            assertEquals(0, tap.sessionIdReuses, "a fresh dial reuses no session id of another connection")
            hellos += tap.clientHellos.size
            assertNotNull(sa.value)
        }
        assertEquals(4, hellos)
        laws.bump("no-psk-hello", 4)
    }

    @Test
    fun bothDirectionsRunAtOnce() {
        val (a, b) = HonestNode.pairedPair()
        val (cc, sc) = Loop.pair()
        val size = 600_000
        val x = pattern(size, 7)
        val y = pattern(size, 8)
        val gotX = AtomicReference<ByteArray>()
        val gotY = AtomicReference<ByteArray>()
        fun duplex(conn: TlsMeshConnection, send: ByteArray, sink: AtomicReference<ByteArray>) {
            val w = Thread { conn.output.write(send) }.also { it.isDaemon = true; it.start() }
            sink.set(readFully(conn, send.size))
            w.join(30_000)
            check(!w.isAlive)
        }
        val (ca, sa) = both(
            { a.dialPaired(cc, b.pin).end.value.also { duplex(it, x, gotY); it.close() } },
            { b.accept(sc).end.value.also { duplex(it, y, gotX); it.close() } },
        )
        assertContentEquals(x, gotX.get())
        assertContentEquals(y, gotY.get())
        assertEquals(ca.value.counters().networkBytesOut, sa.value.counters().networkBytesIn)
        assertEquals(ca.value.counters().networkBytesIn, sa.value.counters().networkBytesOut)
        laws.bump("full-duplex")
    }

    @Test
    fun aPeerThatKillsTheSocketWithoutCloseNotifyIsATypedTruncation() {
        val (a, b) = HonestNode.pairedPair()
        val (cc, sc) = Loop.pair()
        val serverSide = AtomicReference<TlsMeshConnection>()
        val (ca, sa) = both(
            {
                val conn = a.dialPaired(cc, b.pin).end.value
                conn.output.write(1)
                val e = assertFailsWith<MeshTlsException> { conn.input.read() }
                conn.close()
                e
            },
            {
                val conn = b.accept(sc).end.value
                serverSide.set(conn)
                readFully(conn, 1)
                sc.close()
                conn
            },
        )
        val e = ca.value
        assertTrue(e.refusal == MeshTlsRefusal.PEER_CLOSED || e.refusal == MeshTlsRefusal.TRANSPORT_IO, "got ${e.refusal}")
        assertEquals(TlsStage.ESTABLISHED, e.stage)
        laws.bump("truncation-typed")
        assertNotNull(sa.value)
    }

    @Test
    fun aCloseNotifyInsteadOfAServerHelloIsATypedRefusalNotAHandshake() {
        val (a, b) = HonestNode.pairedPair()
        val (cc, sc) = Loop.pair()
        val (ca, _) = both(
            { a.dialPaired(cc, b.pin) },
            {
                val buf = java.nio.ByteBuffer.allocate(2048)
                val net = SocketNet(sc)
                net.read(buf, 5000)
                net.write(java.nio.ByteBuffer.wrap(byteArrayOf(21, 3, 3, 0, 2, 1, 0)), 5000)
                net.shutdownOutput()
                Thread.sleep(300)
                net.close()
            },
        )
        val e = ca.value.end.refusal
        assertEquals(MeshTlsRefusal.PEER_CLOSED, e.refusal)
        assertEquals(TlsStage.HANDSHAKE, e.stage)
        laws.bump("truncation-typed")
    }

    @Test
    fun productionModeRefusesTheTestOnlyKeysOnBothSides() {
        val a = HonestNode(TestNode.testOnly("key1"), productionKeys = true)
        val b = HonestNode(TestNode.testOnly("key2"), productionKeys = true)
        a.registry.pair(b.pin)
        b.registry.pair(a.pin)
        val (cc, sc) = Loop.pair()
        val (ca, sa) = both({ a.dialPaired(cc, b.pin) }, { b.accept(sc) })
        assertEquals(ChainReject.TEST_ONLY_KEY, ca.value.end.refusal.chainReject)
        assertTrue(sa.value.end is End.Failed, "the listener ends the handshake too")
        laws.bump("production-keys", 2)
    }
}
