package xyz.mdhv.asom.lab.proto.integration

import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import xyz.mdhv.asom.lab.proto.session.Build
import xyz.mdhv.asom.lab.proto.session.Counts
import xyz.mdhv.asom.lab.proto.session.Frames
import xyz.mdhv.asom.lab.proto.session.attemptIdOf
import xyz.mdhv.asom.lab.proto.tls.HandshakeObserver
import xyz.mdhv.asom.lab.proto.tls.Loop
import xyz.mdhv.asom.lab.proto.tls.MeshTls
import xyz.mdhv.asom.lab.proto.tls.NetChannel
import xyz.mdhv.asom.lab.proto.tls.RecordTap
import xyz.mdhv.asom.lab.proto.tls.SocketNet
import xyz.mdhv.asom.lab.proto.trust.ChainMode

/**
 * A channel that, once armed, lets only the first [keep] bytes of the next write through and then resets the connection: the peer is left holding the start of a
 * TLS record. This is the mid-record cut that a real peer reset can produce and that a clean-boundary cut cannot.
 */
class TruncatingNet(private val inner: NetChannel, private val channel: SocketChannel, private val keep: Int) : NetChannel {
    private val armed = AtomicBoolean(false)
    private val cut = AtomicBoolean(false)

    fun arm() = armed.set(true)

    val wasCut: Boolean get() = cut.get()

    override fun read(dst: ByteBuffer, timeoutMs: Long): Int = inner.read(dst, timeoutMs)

    override fun write(src: ByteBuffer, timeoutMs: Long) {
        if (!armed.get() || cut.get()) return inner.write(src, timeoutMs)
        val head = ByteBuffer.allocate(minOf(keep, src.remaining()))
        val take = src.slice().limit(head.capacity()) as ByteBuffer
        head.put(take)
        head.flip()
        inner.write(head, timeoutMs)
        src.position(src.limit())
        cut.set(true)
        try {
            channel.setOption(StandardSocketOptions.SO_LINGER, 0)
        } catch (_: IOException) {
        }
        inner.close()
        throw IOException("cut after $keep bytes")
    }

    override fun shutdownOutput() = inner.shutdownOutput()

    override fun close() = inner.close()
}

object PartialRecord {
    /**
     * An honest listener B (the session engine under test) and a peer that establishes a session, then sends the first [keep] bytes of one more record and dies.
     * Returns B's side for the oracle: its rows and its record tap must still add up, although B read bytes that no `unwrap` could consume.
     */
    fun run(seed: Long, keep: Int): Pair<TlsWorld, TlsLink> {
        val w = TlsWorld(seed)
        Loopback().use { lb ->
            var accepted: Accepted? = null
            val t = Thread { accepted = acceptOn(w.b, lb.accept(), "B<A", w.log) }.also { it.isDaemon = true; it.start() }
            val ch = SocketChannel.open(InetSocketAddress(Loop.ADDRESS, lb.port))
            ch.socket().tcpNoDelay = true
            val trunc = TruncatingNet(SocketNet(ch), ch, keep)
            val tap = RecordTap(trunc)
            val obs = HandshakeObserver()
            val tls = MeshTls.dial(tap, w.a.identity(), ChainMode.ExpectPaired(w.b.pin), w.a.env(), obs)
            val conn = TappedConn(tls, "A>B", w.log, tap, obs, ch)
            val peer = RawPeer(conn)
            t.join(15_000)
            val acc = accepted!!
            peer.writeRaw(Build.hello(w.a.pin.nodeId))
            peer.awaitFrames(1, "HELLO_ACK")
            trunc.arm()
            try {
                peer.writeRaw(Build.stateReq(1))
            } catch (_: IOException) {
            }
            check(trunc.wasCut) { "the cut did not happen" }
            Wait.until("B to see the end of the stream") { acc.session!!.closed }
            acc.driver!!.close()
            TlsRuns.settle(w)
            val link = TlsLink(w, xyz.mdhv.asom.lab.proto.session.DialReport("", "connected", conn, 0, null), TlsDialer(w.a, w.b.pin, w.log, "A>B"), null, null, acc)
            link.connAOverride = conn
            return w to link
        }
    }

    /** B's L-L15 (the survivor), checked as in any run. */
    fun checkSurvivor(w: TlsWorld, link: TlsLink, into: L15Stats) {
        TlsOracle.l15(w, Side(w.b, link.connB!!, link.sessionB!!), link.connA!!, into)
    }
}
