package xyz.mdhv.asom.lab.proto.tls

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Timeout
import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.trust.LawCounters

/**
 * The 5 s handshake budget (trust.md 3.2): JSSE has no handshake timeout on an engine, so the transport enforces it. A peer that says nothing, or drips
 * the ClientHello a byte at a time, ends in `HANDSHAKE_TIMEOUT` after about five seconds, in both roles, and the socket is closed. The three
 * peers run at once so the test costs one budget, not three. `@Timeout` makes a missing timeout fail the suite instead of hanging it.
 */
@Timeout(60)
class HandshakeTimeoutTest {
    companion object {
        val laws = LawCounters("tls-timeout")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("handshake-timeout", "timeout-closes-socket"))
    }

    @Test
    fun theBudgetIsFiveSeconds() {
        assertEquals(5_000L, MeshTlsProfile.HANDSHAKE_TIMEOUT_MS)
    }

    private class Timed(val refusal: MeshTlsException, val elapsedMs: Long)

    @Test
    fun aSilentOrDrippingPeerEndsInATypedTimeout() {
        val (a, b) = HonestNode.pairedPair()
        val (c1, s1) = Loop.pair() // honest listener, silent dialler
        val (c2, s2) = Loop.pair() // honest dialler, silent listener
        val (c3, s3) = Loop.pair() // honest listener, a dialler that drips its ClientHello
        val stop = AtomicBoolean(false)
        val hello = RawHello(seed = 3).record()

        val results = arrayOfNulls<Timed>(3)
        val errors = arrayOfNulls<Throwable>(3)
        val closed = arrayOfNulls<Boolean>(3)

        fun timed(i: Int, f: () -> Attempt) = Thread {
            val t0 = System.nanoTime()
            try {
                val at = f()
                results[i] = Timed(at.end.refusal, (System.nanoTime() - t0) / 1_000_000)
            } catch (t: Throwable) {
                errors[i] = t
            }
        }.also { it.isDaemon = true; it.start() }

        val t1 = timed(0) { b.accept(s1) }
        val t2 = timed(1) { a.dialPaired(c2, b.pin) }
        val t3 = timed(2) { b.accept(s3) }
        val drip = Thread {
            try {
                for (byte in hello) {
                    if (stop.get()) break
                    c3.write(ByteBuffer.wrap(byteArrayOf(byte)))
                    Thread.sleep(400)
                }
            } catch (e: Exception) {
                // the listener closed the socket on us, which is the point
            }
        }.also { it.isDaemon = true; it.start() }

        listOf(t1, t2, t3).forEach { it.join(30_000) }
        stop.set(true)
        drip.join(5_000)
        errors.forEachIndexed { i, e -> if (e != null) throw AssertionError("peer $i threw $e", e) }

        for (i in 0..2) {
            val r = results[i] ?: error("peer $i produced no result")
            assertEquals(MeshTlsRefusal.HANDSHAKE_TIMEOUT, r.refusal.refusal, "peer $i")
            assertTrue(r.elapsedMs in 4_800..9_000, "peer $i gave up after ${r.elapsedMs} ms: the budget is 5000 ms")
            laws.bump("handshake-timeout")
        }

        // the three sockets are closed by the transport: the silent peers see the end of the stream
        for ((i, ch) in listOf(c1, s2, c3).withIndex()) {
            val buf = ByteBuffer.allocate(16)
            ch.configureBlocking(false)
            var n = 0
            val deadline = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < deadline) {
                buf.clear()
                n = try {
                    ch.read(buf)
                } catch (e: java.io.IOException) {
                    -1
                }
                if (n < 0) break
                if (n == 0) Thread.sleep(20)
            }
            closed[i] = n < 0
            assertTrue(closed[i]!!, "socket $i was left open after the timeout")
            laws.bump("timeout-closes-socket")
        }
        println("tls-timeout: elapsed ms ${results.map { it!!.elapsedMs }}")
    }
}
