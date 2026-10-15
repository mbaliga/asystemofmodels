package xyz.mdhv.asom.lab.proto.integration

import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout
import xyz.mdhv.asom.lab.policy.PeerStatus
import xyz.mdhv.asom.lab.proto.session.Build
import xyz.mdhv.asom.lab.proto.session.EngineEvent
import xyz.mdhv.asom.lab.proto.session.attemptIdOf
import xyz.mdhv.asom.lab.proto.tls.HandshakeObserver
import xyz.mdhv.asom.lab.proto.tls.Loop
import xyz.mdhv.asom.lab.proto.tls.MeshTls
import xyz.mdhv.asom.lab.proto.tls.RecordTap
import xyz.mdhv.asom.lab.proto.tls.SocketNet
import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.trust.RegistryResult
import xyz.mdhv.asom.lab.proto.trust.StatusLookup
import xyz.mdhv.asom.lab.proto.wire.Terminal

/**
 * Builder action 3 of REVISION_4.md section 6 (trust.md R4-T-08 "Registry listeners"; finishes review finding PTT-1, ERR-FX2-TT-1): a registry change never writes to
 * a socket on the thread that made it. The peer here completes the handshake, asks for a long stream and then never reads (a zero window), so the lender's driver
 * is stalled inside a write that holds the session lock. The revoking thread must still return at once, and the session must still end soon after the revoke, not
 * after the 30 s write budget. Evidence label: LAB, oracle: self, NOT DEVICE EVIDENCE. The bounds are generous (seconds): the point is "bounded", not "fast".
 */
@Timeout(180)
class RevokeDoesNotBlockTest {
    private val boundMs = 8_000L

    private class Stalled(val w: TlsWorld, val accepted: Accepted, val peer: xyz.mdhv.asom.lab.proto.tls.TlsMeshConnection)

    private fun stalledLender(seed: Long): Stalled {
        val w = TlsWorld(seed)
        w.b.engine.script = { listOf(EngineEvent.Head(200, "m1")) + List(6_000) { EngineEvent.Chunk(ByteArray(8_000)) } + EngineEvent.End(Terminal.DONE, 200, 1) }
        val box = arrayOfNulls<Any?>(2)
        val lb = Loopback()
        val t = Thread {
            try {
                box[0] = acceptOn(w.b, lb.accept(), "B<H", w.log)
            } catch (e: Throwable) {
                box[1] = e
            } finally {
                lb.close()
            }
        }.also { it.isDaemon = true; it.name = "accept-B-stalled"; it.start() }
        val ch = SocketChannel.open()
        ch.setOption(StandardSocketOptions.SO_RCVBUF, 4_096)
        ch.connect(InetSocketAddress(Loop.ADDRESS, lb.port))
        ch.socket().tcpNoDelay = true
        val tap = RecordTap(SocketNet(ch), w.a.tapBook)
        val peer = MeshTls.dial(tap, w.a.identity(), ChainMode.ExpectPaired(w.b.pin), w.a.env(), HandshakeObserver())
        t.join(20_000)
        (box[1] as? Throwable)?.let { throw it }
        val accepted = box[0] as Accepted
        w.track(AutoCloseable { accepted.driver?.close() })
        w.track(AutoCloseable { runCatching { peer.close() } })
        val hello = Build.hello(w.a.pin.nodeId)
        peer.output.write(hello, 0, hello.size)
        Wait.until("the lender to establish") { accepted.session!!.established }
        val offer = Build.offer(attemptIdOf(1), 1)
        peer.output.write(offer, 0, offer.size)
        val body = Build.body(1)
        peer.output.write(body, 0, body.size)
        return Stalled(w, accepted, peer)
    }

    private fun awaitStall(s: Stalled) {
        val conn = s.accepted.conn!!
        Wait.until("the lender to start streaming") { conn.plaintextWritten > 100_000 }
        var last = -1L
        var since = System.nanoTime()
        val limit = System.nanoTime() + 30_000L * 1_000_000L
        while (System.nanoTime() < limit) {
            Thread.sleep(100)
            val now = conn.plaintextWritten
            if (now != last) {
                last = now
                since = System.nanoTime()
            } else if ((System.nanoTime() - since) / 1_000_000L >= 1_500L) return
        }
        error("the lender never stalled (written ${conn.plaintextWritten})")
    }

    @Test
    fun revokingAPeerWhoseLenderIsStalledInAWriteReturnsAtOnceAndTheSessionEndsSoonAfter() {
        val s = stalledLender(940)
        s.w.use { w ->
            awaitStall(s)
            val session = s.accepted.session!!
            assertTrue(!session.closed)
            val done = CountDownLatch(1)
            val result = AtomicReference<Any?>()
            val t0 = System.nanoTime()
            Thread { try { result.set(w.b.registry.revoke(w.a.pin, 10)) } catch (t: Throwable) { result.set(t) } finally { done.countDown() } }.also { it.isDaemon = true; it.start() }
            assertTrue(done.await(boundMs, TimeUnit.MILLISECONDS), "revoke() did not return within $boundMs ms while the lender was stalled in a write")
            val tookMs = (System.nanoTime() - t0) / 1_000_000L
            println("  revoke with a stalled lender session took $tookMs ms")
            assertTrue(result.get() is RegistryResult.Changed, "${result.get()}")
            assertEquals(StatusLookup.Known(PeerStatus.REVOKED), w.b.registry.statusLookup(w.a.pin))
            Wait.until("the revoked, stalled session to end", timeoutMs = boundMs) { session.closed }
        }
    }
}
