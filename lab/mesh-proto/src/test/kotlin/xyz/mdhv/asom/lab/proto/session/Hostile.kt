package xyz.mdhv.asom.lab.proto.session

import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.Endpoint
import xyz.mdhv.asom.lab.proto.wire.Feature
import xyz.mdhv.asom.lab.proto.wire.Hello
import xyz.mdhv.asom.lab.proto.wire.HelloAck
import xyz.mdhv.asom.lab.proto.wire.KeyTier
import xyz.mdhv.asom.lab.proto.wire.Limits
import xyz.mdhv.asom.lab.proto.wire.Message
import xyz.mdhv.asom.lab.proto.wire.MessageCodec
import xyz.mdhv.asom.lab.proto.wire.Platform
import xyz.mdhv.asom.lab.proto.wire.Scope

/** An expected row, written by hand from the table: kind, code, bytes out and in (a phase for attempt rows). */
class Rw(val kind: MeshKind, val code: String?, val out: Long = 0, val inn: Long = 0, val phase: Phase? = null) {
    override fun toString() = "$kind|$code|$out|$inn|$phase"
}

fun LabRouteRecord.rw(): Rw = Rw(meshKind!!, meshCode, bytesOut, bytesIn ?: 0, phase)

fun attemptIdOf(n: Int): String = Base64Strict.encodeUrlNoPad(ByteArray(16) { (n * 16 + it).toByte() })

fun nonceOf(n: Int): String = Base64Strict.encodeUrlNoPad(ByteArray(16) { (n * 7 + it + 100).toByte() })

/** Builds valid hostile frames (a hostile node may send perfectly well-formed frames that are wrong in context). */
object Build {
    fun hello(nodeId: String, nonce: String = nonceOf(1), ts: Long = CLOCK_BASE, minV: Int = 1, maxV: Int = 1, v: Int = maxV, name: String = "hostile"): ByteArray = MessageCodec.encode(
        Hello(emptyList(), setOf(Feature.INFER_OFFER, Feature.MANIFEST, Feature.STATE), KeyTier.FILE, minV, maxV, name, nodeId, Platform.LINUX, nonce, "hostile/1", ts, v),
        0,
    )

    fun ack(nodeId: String, ts: Long = CLOCK_BASE, v: Int = 1, granted: Set<Scope> = setOf(Scope.INFER, Scope.MANIFEST, Scope.STATE)): ByteArray = MessageCodec.encode(
        HelloAck(emptyList<Endpoint>(), setOf(Feature.INFER_OFFER, Feature.MANIFEST, Feature.STATE), granted, Limits(300_000, 8_388_608, 1, 4_096, 30), nodeId, null, ts, v),
        0,
    )

    fun msg(m: Message, stream: Long): ByteArray = MessageCodec.encode(m, stream)

    fun offer(attemptId: String, stream: Long, model: String = "m1", promptBytes: Long = 100, maxTokens: Long = 256, deadlineMs: Long = 60_000): ByteArray = Frames.encode(
        0x10, stream,
        """{"attemptId":"$attemptId","deadlineMs":$deadlineMs,"estTokensIn":10,"maxTokens":$maxTokens,"model":"$model","op":"chat","promptBytes":$promptBytes,"stream":true}""",
    )

    fun body(stream: Long, n: Int = 20): ByteArray = Frames.encode(0x13, stream, ByteArray(n) { 'x'.code.toByte() })

    fun cancel(attemptId: String, stream: Long): ByteArray = Frames.encode(0x17, stream, """{"attemptId":"$attemptId","reason":"client-gone"}""")

    fun stateReq(stream: Long): ByteArray = Frames.encode(0x20, stream, """{"v":1}""")

    fun manifestReq(stream: Long, challenge: String = Base64Strict.encodeUrlNoPad(ByteArray(32) { it.toByte() })): ByteArray =
        Frames.encode(0x22, stream, """{"challenge":"$challenge","v":1}""")

    fun ext(type: Int = 0x85, stream: Long = 0, payload: ByteArray = ByteArray(12) { 1 }): ByteArray = Frames.encode(type, stream, payload)
}

/**
 * A hostile TLS client against an honest listener: the hostile end writes raw bytes, the honest session reads and answers, and the test looks at every frame the
 * honest node sent and every row it wrote. The hostile node is the identity of `world.a`, which the registry of `world.b` has paired.
 */
class HostileClient(val w: World, mode: ConnMode = ConnMode.ESTABLISHED) {
    private val pair = MemConnection.pair(w.b.pin, w.a.pin, w.log, "H>B", "B<H", mode)
    val conn: MemConnection = pair.first
    private val honestConn = pair.second
    val honest: Session = w.b.node.accept(pair.second)
    var autoAdvance = true
    private val received = ArrayList<Fr>()
    private var receivedBytes = 0L

    val hostileNodeId: String get() = w.a.pin.nodeId

    fun drain() {
        while (honest.pumpAvailable(1 shl 20) + (if (autoAdvance) honest.advance() else 0) > 0) Unit
        if (honestConn.peerClosed() && !honest.closed && honestConn.drained()) honest.onTransportClosed()
    }

    /** Runs the engine of every served attempt to the end. */
    fun settle() {
        while (honest.advance() + honest.pumpAvailable(1 shl 20) > 0) Unit
    }

    fun send(bytes: ByteArray) {
        conn.writeRaw(bytes)
        drain()
    }

    /** Sends the bytes one at a time (every split point at once). */
    fun sendByByte(bytes: ByteArray) {
        for (b in bytes) {
            conn.writeRaw(byteArrayOf(b))
            drain()
        }
    }

    /** The frames the honest node has sent since the last call. */
    fun frames(): List<Fr> {
        val raw = conn.takeAll()
        receivedBytes += raw.size
        return Frames.parse(raw).also { received += it }
    }

    fun establish(nonce: String = nonceOf(1)) {
        send(Build.hello(hostileNodeId, nonce))
        val f = frames()
        check(f.map { it.type } == listOf(2)) { "expected HELLO_ACK, got ${f.map { it.name }}" }
    }

    fun rows(): List<Rw> = w.b.rows().map { it.rw() }

    /** The honest node closed its side of the connection. */
    fun closedByHonest(): Boolean = conn.peerClosed()
}

/** A hostile TLS server against an honest dialer (`world.a` dials, the hostile end is the identity of `world.b`). */
class HostileServer(val w: World) {
    private val pair = MemConnection.pair(w.b.pin, w.a.pin, w.log, "A>H", "H<A")
    val serverConn: MemConnection = pair.second
    private val honestConn = pair.first
    val report = w.a.node.dial(w.b.pin, "192.168.1.20:11436", "qr") { ConnectResult("connected", pair.first) }
    val honest: Session = w.a.node.openDialed(report, w.b.pin)
    var helloNonce: String = ""

    val hostileNodeId: String get() = w.b.pin.nodeId

    fun drain() {
        while (honest.pumpAvailable(1 shl 20) + honest.advance() > 0) Unit
        if (honestConn.peerClosed() && !honest.closed && honestConn.drained()) honest.onTransportClosed()
    }

    fun send(bytes: ByteArray) {
        serverConn.writeRaw(bytes)
        drain()
    }

    fun frames(): List<Fr> = Frames.parse(serverConn.takeAll())

    /** Reads the dialer's HELLO and answers with a valid HELLO_ACK. */
    fun establish() {
        val hello = frames().single()
        check(hello.type == 1) { "the dialer sends HELLO first" }
        helloNonce = Regex("\"sessionNonce\":\"([^\"]+)\"").find(hello.text)!!.groupValues[1]
        send(Build.ack(hostileNodeId))
        check(honest.established) { "the honest dialer did not establish" }
    }

    fun rows(): List<Rw> = w.a.rows().map { it.rw() }
}

/** The pin of a fresh random node (no key behind it). */
fun randomPin(n: Int): Pin = Pin.ofHash(ByteArray(32) { (n * 31 + it * 7 + 3).toByte() })
