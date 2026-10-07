package xyz.mdhv.asom.lab.proto.integration

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import xyz.mdhv.asom.lab.proto.session.Build
import xyz.mdhv.asom.lab.proto.session.DialReport
import xyz.mdhv.asom.lab.proto.session.Session
import xyz.mdhv.asom.lab.proto.session.nonceOf
import xyz.mdhv.asom.lab.proto.tls.HandshakeObserver
import xyz.mdhv.asom.lab.proto.tls.Loop
import xyz.mdhv.asom.lab.proto.tls.MeshTls
import xyz.mdhv.asom.lab.proto.tls.NetChannel
import xyz.mdhv.asom.lab.proto.tls.RecordTap
import xyz.mdhv.asom.lab.proto.tls.SocketNet
import xyz.mdhv.asom.lab.proto.trust.ChainMode

/** A hostile TLS client with the valid identity of A against the honest listener B: [peer] writes raw bytes, [accepted] is B's session engine (waits for the listener thread). */
class RawClient(val world: TlsWorld, val peer: RawPeer, private val listener: Thread, private val box: Array<Any?>) {
    val accepted: Accepted
        get() {
            listener.join(20_000)
            (box[1] as? Throwable)?.let { throw it }
            return box[0] as Accepted
        }
    val honest: Session get() = accepted.session!!
    val hostileNodeId: String get() = world.a.pin.nodeId

    fun establish(nonce: String = nonceOf(1)) {
        peer.writeRaw(Build.hello(hostileNodeId, nonce))
        val f = peer.awaitFrames(1, "HELLO_ACK")
        check(f.map { it.type } == listOf(2)) { "expected HELLO_ACK, got ${f.map { it.name }}" }
    }
}

/**
 * A client-side channel that sends the first write at once and holds every later one until [release], which sends them as ONE write: the listener's last
 * handshake flight (the client Finished) and the client's first application record then reach it in the same read.
 */
class HoldingNet(private val inner: NetChannel) : NetChannel {
    private var writes = 0
    private val held = java.io.ByteArrayOutputStream()

    @Volatile
    var holding = true

    override fun read(dst: ByteBuffer, timeoutMs: Long): Int = inner.read(dst, timeoutMs)

    override fun write(src: ByteBuffer, timeoutMs: Long) {
        synchronized(held) {
            if (writes++ == 0 || !holding) return inner.write(src, timeoutMs)
            val b = ByteArray(src.remaining())
            src.get(b)
            held.write(b)
        }
    }

    fun release() {
        synchronized(held) {
            holding = false
            val all = held.toByteArray()
            held.reset()
            if (all.isNotEmpty()) inner.write(ByteBuffer.wrap(all), 5_000)
        }
    }

    override fun shutdownOutput() = inner.shutdownOutput()

    override fun close() = inner.close()
}

fun TlsWorld.rawClient(wrapNet: (NetChannel) -> NetChannel = { it }, beforeSession: () -> Unit = {}): RawClient {
    val lb = Loopback()
    val box = arrayOfNulls<Any?>(2)
    val t = Thread {
        try {
            box[0] = acceptOn(b, lb.accept(), "B<H", log, beforeSession = beforeSession)
        } catch (e: Throwable) {
            box[1] = e
        } finally {
            lb.close()
        }
    }.also { it.isDaemon = true; it.name = "accept-B-raw"; it.start() }
    val ch = SocketChannel.open(InetSocketAddress(Loop.ADDRESS, lb.port))
    ch.socket().tcpNoDelay = true
    val tap = RecordTap(wrapNet(SocketNet(ch)), a.tapBook)
    val obs = HandshakeObserver()
    val tls = MeshTls.dial(tap, a.identity(), ChainMode.ExpectPaired(b.pin), a.env(), obs)
    val conn = TappedConn(tls, "H>B", log, tap, obs, ch)
    val rc = RawClient(this, RawPeer(conn).also { track(it) }, t, box)
    track(AutoCloseable { rc.accepted.driver?.close() })
    return rc
}

/** A hostile TLS server with the valid identity of B against the honest dialler A: [peer] holds B's end, [session] is A's session engine (HELLO already sent). */
class RawServer(val world: TlsWorld, val peer: RawPeer, val session: Session, val report: DialReport) {
    val hostileNodeId: String get() = world.b.pin.nodeId

    /** Reads the dialler's HELLO and answers with a valid HELLO_ACK. */
    fun establish(ack: ByteArray = Build.ack(hostileNodeId)) {
        val hello = peer.awaitFrames(1, "HELLO").single()
        check(hello.type == 1) { "the dialer sends HELLO first" }
        peer.writeRaw(ack)
        Wait.until("the honest dialler to establish") { session.established }
    }
}

fun TlsWorld.rawServer(): RawServer {
    Loopback().use { lb ->
        var peer: RawPeer? = null
        var error: Throwable? = null
        val t = Thread {
            try {
                val ch = lb.accept()
                val tap = RecordTap(SocketNet(ch), b.tapBook)
                val obs = HandshakeObserver()
                val tls = MeshTls.accept(tap, b.identity(), b.env(), obs)
                peer = RawPeer(TappedConn(tls, "H<A", log, tap, obs, ch))
            } catch (e: Throwable) {
                error = e
            }
        }.also { it.isDaemon = true; it.name = "accept-raw-server"; it.start() }
        val dialer = TlsDialer(a, b.pin, log, "A>H")
        val report = a.node.dial(b.pin, lb.address, "qr", dialer)
        t.join(15_000)
        error?.let { throw it }
        val session = a.node.openDialed(report, b.pin)
        track(SessionDriver(session, { clockA.peek() }, "A-cli-raw").start())
        return RawServer(this, peer!!.also { track(it) }, session, report)
    }
}
