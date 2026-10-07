package xyz.mdhv.asom.lab.proto.integration

import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.proto.tls.Loop

/** The listener limits of trust.md 3.3 (8 unauthenticated connections in flight, 10 handshakes a minute per source) on an injected clock, and over real sockets. */
@Timeout(120)
class HandshakeLimiterTest {
    private fun admitted(a: Admission) = a as Admission.Admitted

    @Test
    fun atMostEightUnauthenticatedConnectionsAreInFlightAndAReleaseFreesASlot() {
        val clock = FakeClock()
        val l = HandshakeLimiter(clock::peek)
        val tickets = (0 until 8).map { admitted(l.admit("10.0.0.${it + 1}")) }
        assertEquals(8, l.inFlightNow)
        val ninth = l.admit("10.0.0.99")
        assertTrue(ninth is Admission.Refused && ninth.reason == Admission.Why.GLOBAL_IN_FLIGHT)
        tickets[3].release()
        tickets[3].release()
        assertEquals(7, l.inFlightNow, "a second release of the same ticket changes nothing")
        admitted(l.admit("10.0.0.99"))
        assertEquals(8, l.inFlightNow)
    }

    @Test
    fun tenHandshakesAMinutePerSourceAndTheWindowSlides() {
        val clock = FakeClock(1_000_000)
        val l = HandshakeLimiter(clock::peek)
        repeat(10) {
            admitted(l.admit("192.168.1.7")).release()
            clock.advance(1_000)
        }
        val eleventh = l.admit("192.168.1.7")
        assertTrue(eleventh is Admission.Refused && eleventh.reason == Admission.Why.PER_SOURCE_RATE)
        admitted(l.admit("192.168.1.8")).release()
        // the first admission was at t0; it leaves the window at t0 + 60,000 exactly
        clock.advance(60_000 - 10_000 - 1)
        assertTrue(l.admit("192.168.1.7") is Admission.Refused, "one millisecond before the first admission leaves the window")
        clock.advance(1)
        admitted(l.admit("192.168.1.7")).release()
        assertTrue(l.admit("192.168.1.7") is Admission.Refused, "nine earlier admissions are still inside the window, so the tenth is the last")
    }

    @Test
    fun aRefusedConnectionDoesNotExtendItsSourcesWindowAndTheTableIsPruned() {
        val clock = FakeClock(5_000_000)
        val l = HandshakeLimiter(clock::peek)
        repeat(10) { admitted(l.admit("10.1.1.1")).release() }
        repeat(500) {
            assertTrue(l.admit("10.1.1.1") is Admission.Refused)
            clock.advance(100)
        }
        // 50,000 ms of refusals; the 10 admissions were all at t0 and are gone 60,000 ms after it
        clock.advance(10_000)
        admitted(l.admit("10.1.1.1")).release()
        clock.advance(HandshakeLimits.WINDOW_MS)
        l.admit("10.9.9.9").let { admitted(it).release() }
        assertEquals(1, l.trackedSources, "only the source seen inside the window is tracked")
    }

    @Test
    fun overRealSocketsTheNinthStalledConnectionIsClosedAtOnceAndEveryRefusalIsOneRowWithoutAnAddress() {
        TlsWorld(7).use { w ->
            val clock = FakeClock()
            val limiter = HandshakeLimiter(clock::peek)
            val results = CopyOnWriteArrayList<Accepted>()
            val clients = ArrayList<SocketChannel>()
            Loopback().use { lb ->
                val threads = ArrayList<Thread>()
                repeat(9) { i ->
                    val client = SocketChannel.open(InetSocketAddress(Loop.ADDRESS, lb.port))
                    clients += client
                    val server = lb.accept()
                    threads += Thread { results += acceptOn(w.b, server, "B<S$i", w.log, limiter) }.also { it.isDaemon = true; it.start() }
                    if (i < 8) Wait.until("connection $i to hold its ticket") { limiter.inFlightNow == i + 1 }
                }
                Wait.until("the ninth connection to be refused without a handshake") { results.size == 1 }
                assertTrue(results.single().let { it.conn == null && it.refusal == null }, "refused by the limiter, before any TLS byte")
                assertEquals(8, limiter.inFlightNow)
                clients.forEach { runCatching { it.close() } }
                threads.forEach { it.join(15_000) }
            }
            assertEquals(9, results.size)
            assertEquals(0, limiter.inFlightNow, "every ticket was released, whatever its handshake did")
            val row = w.b.node.inboundRefused.flush(force = true)
            assertEquals(MeshKind.INBOUND_REFUSED, row!!.meshKind)
            assertEquals("refused:9", row.meshCode)
            val text = String(row.toRowBytes(), Charsets.UTF_8)
            assertTrue(!Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}").containsMatchIn(text), "a refusal row carries no address")
            assertEquals(1, w.b.rows().count { it.meshKind == MeshKind.INBOUND_REFUSED })
        }
    }
}
