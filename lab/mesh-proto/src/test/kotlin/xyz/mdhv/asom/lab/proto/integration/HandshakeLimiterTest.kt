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

    private fun assertRefused(a: Admission, why: String) {
        assertTrue(a is Admission.Refused && a.reason.name == why, "expected a refusal for $why, got ${(a as? Admission.Refused)?.reason ?: "an admission"}")
    }

    @Test
    fun oneSourceCannotHoldEveryHandshakeSlot() {
        val clock = FakeClock()
        val l = HandshakeLimiter(clock::peek)
        val held = (0 until 4).map { admitted(l.admit("203.0.113.9")) }
        assertRefused(l.admit("203.0.113.9"), "PER_SOURCE_IN_FLIGHT")
        admitted(l.admit("198.51.100.4"))
        assertEquals(5, l.inFlightNow)
        held[0].release()
        admitted(l.admit("203.0.113.9"))
    }

    @Test
    fun anIpv6SourceIsKeyedByItsSlash64AndAnIpv4MappedAddressByItsIpv4Form() {
        val clock = FakeClock()
        val v6 = HandshakeLimiter(clock::peek)
        repeat(4) { admitted(v6.admit("2001:db8:1:2:${it + 1}:0:0:9")) }
        assertRefused(v6.admit("2001:DB8:1:2:ffff:eeee:dddd:cccc"), "PER_SOURCE_IN_FLIGHT")
        assertRefused(v6.admit("2001:db8:1:2::7%eth0"), "PER_SOURCE_IN_FLIGHT")
        admitted(v6.admit("2001:db8:1:3::1"))
        val mapped = HandshakeLimiter(clock::peek)
        repeat(4) { admitted(mapped.admit("10.0.0.5")) }
        assertRefused(mapped.admit("::ffff:10.0.0.5"), "PER_SOURCE_IN_FLIGHT")
        assertRefused(mapped.admit("::FFFF:a00:5"), "PER_SOURCE_IN_FLIGHT")
        admitted(mapped.admit("::ffff:10.0.0.6"))
        val rotating = HandshakeLimiter(clock::peek)
        repeat(10) { admitted(rotating.admit("2001:db8:7:7:${it + 1}::1")).release() }
        assertRefused(rotating.admit("2001:db8:7:7:abcd::1"), "PER_SOURCE_RATE")
        assertEquals(1, rotating.trackedSources, "ten addresses of one /64 are one source")
    }

    @Test
    fun aTicketTheHostNeverReleasesIsReclaimedAfterTenSecondsAndItsLateReleaseDoesNothing() {
        val clock = FakeClock(2_000_000)
        val l = HandshakeLimiter(clock::peek)
        val tickets = (0 until 8).map { admitted(l.admit("10.7.0.${it + 1}")) }
        assertRefused(l.admit("10.7.1.1"), "GLOBAL_IN_FLIGHT")
        clock.advance(9_999)
        assertRefused(l.admit("10.7.1.1"), "GLOBAL_IN_FLIGHT")
        clock.advance(1)
        val fresh = admitted(l.admit("10.7.1.1"))
        assertEquals(1, l.inFlightNow, "the eight leaked tickets were reclaimed")
        tickets.forEach { it.release() }
        assertEquals(1, l.inFlightNow, "a late release of a reclaimed ticket changes nothing")
        fresh.release()
        assertEquals(0, l.inFlightNow)
    }

    /** Nine connections from the one loopback source stall in the handshake: the first [held] hold a ticket, every later one is refused by the limiter before any TLS byte. */
    private fun nineStalledConnections(seed: Long, limiter: HandshakeLimiter, held: Int) {
        TlsWorld(seed).use { w ->
            val results = CopyOnWriteArrayList<Accepted>()
            val clients = ArrayList<SocketChannel>()
            Loopback().use { lb ->
                val threads = ArrayList<Thread>()
                repeat(9) { i ->
                    val client = SocketChannel.open(InetSocketAddress(Loop.ADDRESS, lb.port))
                    clients += client
                    val server = lb.accept()
                    threads += Thread { results += acceptOn(w.b, server, "B<S$i", w.log, limiter) }.also { it.isDaemon = true; it.start() }
                    if (i < held) {
                        Wait.until("connection $i to hold its ticket") { limiter.inFlightNow == i + 1 }
                    } else {
                        Wait.until("connection $i to be refused without a handshake") { results.size == i - held + 1 }
                    }
                }
                assertTrue(results.all { it.conn == null && it.refusal == null }, "refused by the limiter, before any TLS byte")
                assertEquals(held, limiter.inFlightNow, "$held tickets held while the rest are refused")
                clients.forEach { runCatching { it.close() } }
                threads.forEach { it.join(15_000) }
            }
            assertEquals(9, results.size, "all nine acceptor threads returned")
            assertEquals(0, limiter.inFlightNow, "every ticket was released, whatever its handshake did")
            val row = w.b.node.inboundRefused.flush(force = true)
            assertEquals(MeshKind.INBOUND_REFUSED, row!!.meshKind)
            assertEquals("refused:9", row.meshCode, "nine refusals counted: ${9 - held} by the limiter, $held by a handshake that ended with its peer closed; results=${results.map { it.refusal?.toString() ?: it.conn?.toString() ?: "limiter" }}")
            val text = String(row.toRowBytes(), Charsets.UTF_8)
            assertTrue(!Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}").containsMatchIn(text), "a refusal row carries no address")
            assertEquals(1, w.b.rows().count { it.meshKind == MeshKind.INBOUND_REFUSED })
        }
    }

    @Test
    fun overRealSocketsTheNinthStalledConnectionIsClosedAtOnceAndEveryRefusalIsOneRowWithoutAnAddress() {
        // every loopback connection has the one source 127.0.0.1, so the per-source share is lifted to the global cap here to test the global cap alone
        nineStalledConnections(7, HandshakeLimiter(FakeClock()::peek, perSourceInFlight = 8), held = 8)
    }

    @Test
    fun overRealSocketsOneSourceIsHeldToItsShareOfTheHandshakeSlots() {
        nineStalledConnections(8, HandshakeLimiter(FakeClock()::peek), held = 4)
    }
}
