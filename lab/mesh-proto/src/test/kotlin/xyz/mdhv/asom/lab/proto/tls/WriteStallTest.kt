package xyz.mdhv.asom.lab.proto.tls

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Timeout
import xyz.mdhv.asom.lab.policy.PeerStatus
import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.trust.LawCounters
import xyz.mdhv.asom.lab.proto.trust.RegistryListener
import xyz.mdhv.asom.lab.proto.trust.RegistryResult

/**
 * PTT-1: a peer that connects, completes the handshake and then never reads (a zero window) must not decide when its session is torn down.
 * The writer stalls in the socket; close(), revocation and the write budget must still return in bounded time. Evidence label: LAB, oracle:
 * self, NOT DEVICE EVIDENCE. The bounds are generous (seconds) because the point is "bounded", not "fast".
 */
@Timeout(120)
class WriteStallTest {
    companion object {
        val laws = LawCounters("tls-write-stall")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("close-bounded-under-stalled-writer", "revoke-bounded-under-stalled-writer", "close-wakes-writer", "write-budget-typed"))

        const val BOUND_MS = 8_000L
    }

    private class Stalled(
        val listener: TlsMeshConnection,
        val dialler: TlsMeshConnection,
        val written: AtomicLong,
        val writerEnd: AtomicReference<Throwable?>,
        val writerDone: CountDownLatch,
    )

    private fun envOf(n: HonestNode, writeStallMs: Long) =
        MeshTlsEnv(n.registry.registry.asStatusSource(), { java.time.Instant.ofEpochSecond(TestNode.NOW.epochSecond) }, { false }, n.productionKeys, writeStallMs)

    private fun setUp(a: HonestNode, b: HonestNode, writeStallMs: Long = MeshTlsProfile.WRITE_STALL_MS): Stalled {
        val (cc, sc) = Loop.pair()
        val (ca, sa) = both(
            { MeshTls.dial(SocketNet(cc), a.node.identity(), ChainMode.ExpectPaired(b.pin), envOf(a, MeshTlsProfile.WRITE_STALL_MS)) },
            { MeshTls.accept(SocketNet(sc), b.node.identity(), envOf(b, writeStallMs)) },
        )
        val dialler = ca.value
        val listener = sa.value
        val written = AtomicLong()
        val end = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val chunk = ByteArray(16_000) { 7 }
        Thread {
            try {
                while (true) {
                    listener.output.write(chunk)
                    written.addAndGet(chunk.size.toLong())
                }
            } catch (t: Throwable) {
                end.set(t)
            } finally {
                done.countDown()
            }
        }.also { it.name = "stalled-writer"; it.isDaemon = true; it.start() }
        return Stalled(listener, dialler, written, end, done)
    }

    private fun awaitStall(s: Stalled) {
        var last = -1L
        var stableSince = System.nanoTime()
        val limit = System.nanoTime() + 30_000L * 1_000_000L
        while (System.nanoTime() < limit) {
            Thread.sleep(100)
            val now = s.written.get()
            if (now != last) {
                last = now
                stableSince = System.nanoTime()
            } else if ((System.nanoTime() - stableSince) / 1_000_000L >= 1_500L) return
        }
        error("the writer never stalled (written ${s.written.get()})")
    }

    private fun boundedMs(f: () -> Unit): Long {
        val t0 = System.nanoTime()
        val done = CountDownLatch(1)
        val err = AtomicReference<Throwable?>()
        Thread { try { f() } catch (t: Throwable) { err.set(t) } finally { done.countDown() } }.also { it.isDaemon = true; it.start() }
        val ok = done.await(BOUND_MS, TimeUnit.MILLISECONDS)
        val took = (System.nanoTime() - t0) / 1_000_000L
        assertTrue(ok, "the call did not return within $BOUND_MS ms")
        err.get()?.let { throw it }
        return took
    }

    @Test
    fun closeReturnsInBoundedTimeWhileAWriterIsStalledAgainstAPeerThatDoesNotRead() {
        val (a, b) = HonestNode.pairedPair()
        val s = setUp(a, b)
        try {
            awaitStall(s)
            assertTrue(s.written.get() > 0)
            boundedMs { s.listener.close() }
            laws.bump("close-bounded-under-stalled-writer")
            assertTrue(s.writerDone.await(BOUND_MS, TimeUnit.MILLISECONDS), "closing the connection must wake the stalled writer")
            assertNotNull(s.writerEnd.get())
            assertTrue(s.writerEnd.get() is MeshTlsException, "the writer ends with a typed refusal, not ${s.writerEnd.get()}")
            laws.bump("close-wakes-writer")
        } finally {
            runCatching { s.dialler.close() }
            runCatching { s.listener.close() }
        }
    }

    @Test
    fun revokingAPeerWhoseSessionIsStalledReturnsInBoundedTime() {
        val (a, b) = HonestNode.pairedPair()
        val s = setUp(a, b)
        try {
            awaitStall(s)
            b.registry.registry.addListener(RegistryListener { c -> if (c.to == PeerStatus.REVOKED) s.listener.close() })
            val took = boundedMs { assertTrue(b.registry.registry.revoke(a.pin, 10) is RegistryResult.Changed) }
            println("  revoke with a stalled session took $took ms")
            assertEquals(PeerStatus.REVOKED, b.registry.status(a.pin))
            laws.bump("revoke-bounded-under-stalled-writer")
            assertTrue(s.writerDone.await(BOUND_MS, TimeUnit.MILLISECONDS))
        } finally {
            runCatching { s.dialler.close() }
            runCatching { s.listener.close() }
        }
    }

    @Test
    fun aWriteThatStallsPastTheBudgetEndsWithATypedRefusalAndKillsTheConnection() {
        val (a, b) = HonestNode.pairedPair()
        val s = setUp(a, b, writeStallMs = 1_000L)
        try {
            assertTrue(s.writerDone.await(30, TimeUnit.SECONDS), "a write against a peer that does not read must end within its budget")
            val e = s.writerEnd.get()
            assertTrue(e is MeshTlsException, "typed, not $e")
            assertEquals(MeshTlsRefusal.TRANSPORT_IO, (e as MeshTlsException).refusal)
            assertEquals(TlsStage.ESTABLISHED, e.stage)
            val again = runCatching { s.listener.output.write(ByteArray(10)) }.exceptionOrNull()
            assertTrue(again is MeshTlsException, "the connection is dead after a stalled write, not $again")
            boundedMs { s.listener.close() }
            laws.bump("write-budget-typed")
        } finally {
            runCatching { s.dialler.close() }
            runCatching { s.listener.close() }
        }
    }
}
