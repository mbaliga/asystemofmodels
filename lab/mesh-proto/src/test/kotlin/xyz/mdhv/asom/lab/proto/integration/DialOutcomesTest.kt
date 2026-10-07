package xyz.mdhv.asom.lab.proto.integration

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.proto.tls.MeshTlsException
import xyz.mdhv.asom.lab.proto.tls.MeshTlsRefusal
import xyz.mdhv.asom.lab.proto.tls.TlsStage

/** The DIAL outcome set of LAB_SPEC 7.6 as a JVM can produce it: a closed set, written out by hand here, and the three outcomes that need no hostile TLS (refused, not-tls, timeout) over real sockets. */
@Timeout(120)
class DialOutcomesTest {
    @Test
    fun everyTypedRefusalMapsToTheOutcomeOfTheTableAndTheSetIsClosed() {
        val expected = mapOf(
            MeshTlsRefusal.PEER_CHAIN_REJECTED to "pin-mismatch", MeshTlsRefusal.PEER_CERTIFICATE_MISSING to "pin-mismatch",
            MeshTlsRefusal.ALPN_MISSING to "not-tls", MeshTlsRefusal.ALPN_MISMATCH to "not-tls", MeshTlsRefusal.PROTOCOL_VERSION to "not-tls",
            MeshTlsRefusal.CLIENT_AUTH_NOT_REQUESTED to "not-tls", MeshTlsRefusal.HANDSHAKE_FAILED to "not-tls", MeshTlsRefusal.PEER_CLOSED to "not-tls",
            MeshTlsRefusal.HANDSHAKE_TIMEOUT to "timeout", MeshTlsRefusal.PEER_ALERT to "refused", MeshTlsRefusal.TRANSPORT_IO to "refused",
        )
        assertEquals(MeshTlsRefusal.entries.toSet(), expected.keys, "the table covers every refusal")
        for ((r, code) in expected) assertEquals(code, DialOutcomes.of(MeshTlsException(r, TlsStage.HANDSHAKE)), "$r")
        assertEquals("refused", DialOutcomes.of(ConnectException()))
        assertEquals("timeout", DialOutcomes.of(SocketTimeoutException()))
        assertTrue(expected.values.all { it in xyz.mdhv.asom.lab.proto.session.MeshNode.DIAL_CODES })
    }

    private fun rowsOfDial(w: TlsWorld) = w.a.rows().filter { it.meshKind == MeshKind.DIAL }

    @Test
    fun aClosedPortIsRefusedAndAPlainTcpServerIsNotTls() {
        TlsWorld(61).use { w ->
            val dead = Loopback()
            val deadAddress = dead.address
            dead.close()
            val closed = w.a.node.dial(w.b.pin, deadAddress, "user", TlsDialer(w.a, w.b.pin, w.log, "A>X"))
            assertEquals("refused", closed.code)
            assertTrue(closed.connection == null)
            assertEquals(listOf(Phase.INTENT, Phase.OUTCOME), rowsOfDial(w).map { it.phase })
            assertEquals("refused", rowsOfDial(w)[1].meshCode)
            assertEquals(599, rowsOfDial(w)[1].status)
        }
        TlsWorld(62).use { w ->
            val lb = Loopback()
            val t = Thread {
                val ch = lb.accept()
                ch.write(ByteBuffer.wrap("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray()))
                Thread.sleep(300)
                ch.close()
                lb.close()
            }.also { it.isDaemon = true; it.start() }
            val report = w.a.node.dial(w.b.pin, lb.address, "hello", TlsDialer(w.a, w.b.pin, w.log, "A>X"))
            t.join(10_000)
            assertEquals("not-tls", report.code)
            assertEquals(listOf("INTENT:-", "OUTCOME:not-tls"), rowsOfDial(w).map { "${it.phase}:${it.meshCode ?: "-"}" })
            assertEquals("hello", rowsOfDial(w)[0].addrSource)
            assertTrue(w.a.node.openSessions().isEmpty())
        }
    }

    @Test
    fun aServerThatNeverAnswersTimesOutAfterFiveSecondsOfTheWholeHandshake() {
        TlsWorld(63).use { w ->
            val lb = Loopback()
            var held: java.nio.channels.SocketChannel? = null
            val t = Thread { held = lb.accept() }.also { it.isDaemon = true; it.start() }
            val t0 = System.nanoTime()
            val report = w.a.node.dial(w.b.pin, lb.address, "qr", TlsDialer(w.a, w.b.pin, w.log, "A>X"))
            val ms = (System.nanoTime() - t0) / 1_000_000
            t.join(5_000)
            runCatching { held?.close() }
            lb.close()
            assertEquals("timeout", report.code)
            assertTrue(ms in 4_800..9_000, "the handshake budget is 5 s (ERRATA ERR-PL-9): $ms ms")
            assertEquals("timeout", rowsOfDial(w)[1].meshCode)
        }
    }
}
