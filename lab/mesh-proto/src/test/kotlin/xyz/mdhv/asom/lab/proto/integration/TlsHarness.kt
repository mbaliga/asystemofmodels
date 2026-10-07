package xyz.mdhv.asom.lab.proto.integration

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import xyz.mdhv.asom.lab.ledger.CrashInjectingSink
import xyz.mdhv.asom.lab.ledger.JsonlReader
import xyz.mdhv.asom.lab.ledger.JsonlSink
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.LedgerWriteException
import xyz.mdhv.asom.lab.ledger.NodeLedger
import xyz.mdhv.asom.lab.ledger.RowSink
import xyz.mdhv.asom.lab.manifest.TestOnlyKeys
import xyz.mdhv.asom.lab.proto.session.CLOCK_BASE
import xyz.mdhv.asom.lab.proto.session.ConnectResult
import xyz.mdhv.asom.lab.proto.session.Dialer
import xyz.mdhv.asom.lab.proto.session.Ev
import xyz.mdhv.asom.lab.proto.session.FailPlan
import xyz.mdhv.asom.lab.proto.session.FakeLive
import xyz.mdhv.asom.lab.proto.session.Fr
import xyz.mdhv.asom.lab.proto.session.Frames
import xyz.mdhv.asom.lab.proto.session.Log
import xyz.mdhv.asom.lab.proto.session.MeshNode
import xyz.mdhv.asom.lab.proto.session.NodeConfig
import xyz.mdhv.asom.lab.proto.session.RefusalCounters
import xyz.mdhv.asom.lab.proto.session.SeededIds
import xyz.mdhv.asom.lab.proto.session.ServingView
import xyz.mdhv.asom.lab.proto.session.Session
import xyz.mdhv.asom.lab.proto.session.SessionObserver

import xyz.mdhv.asom.lab.proto.session.TestManifestPort
import xyz.mdhv.asom.lab.proto.tls.HandshakeObserver
import xyz.mdhv.asom.lab.proto.tls.Loop
import xyz.mdhv.asom.lab.proto.tls.MeshIdentity
import xyz.mdhv.asom.lab.proto.tls.MeshTls
import xyz.mdhv.asom.lab.proto.tls.MeshTlsEnv
import xyz.mdhv.asom.lab.proto.tls.MeshTlsException
import xyz.mdhv.asom.lab.proto.tls.RecordTap
import xyz.mdhv.asom.lab.proto.tls.SessionIdBook
import xyz.mdhv.asom.lab.proto.tls.SocketNet
import xyz.mdhv.asom.lab.proto.tls.TestNode
import xyz.mdhv.asom.lab.proto.tls.TlsMeshConnection
import xyz.mdhv.asom.lab.proto.transport.MeshConnection
import xyz.mdhv.asom.lab.proto.transport.TransportCounters
import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.trust.InMemoryPeerStore
import xyz.mdhv.asom.lab.proto.trust.PairingCeremony
import xyz.mdhv.asom.lab.proto.trust.PeerClass
import xyz.mdhv.asom.lab.proto.trust.PeerRegistry
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.trust.Scope
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.KeyTier
import xyz.mdhv.asom.lab.proto.wire.PeerRole
import xyz.mdhv.asom.lab.proto.wire.Platform

/** Polling waits for I/O that finishes on other threads. Timers are never waited for: those tests move an injected clock instead. */
object Wait {
    /** Waits until the refusal counter of [kit] for [r] reaches [n], then returns its value (the session thread bumps it; a reader that has just seen the reply may be ahead). */
    fun refusals(kit: TNode, r: xyz.mdhv.asom.lab.proto.session.Refusal, n: Int): Int {
        until("refusal counter $r to reach $n on ${kit.name}") { kit.counters.of(r) >= n }
        return kit.counters.of(r)
    }


    fun until(what: String, timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!cond()) {
            check(System.nanoTime() < deadline) { "timed out after $timeoutMs ms waiting for: $what" }
            Thread.sleep(2)
        }
    }

    /** Waits until [probe] returned the same value for [quiet] polls in a row (a sink or a stream that was written asynchronously has stopped growing). */
    fun stable(what: String, quiet: Int = 8, timeoutMs: Long = 30_000, probe: () -> Any?): Any? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var last = probe()
        var same = 0
        while (same < quiet) {
            check(System.nanoTime() < deadline) { "timed out after $timeoutMs ms waiting for $what to settle" }
            Thread.sleep(4)
            val now = probe()
            if (now == last) same++ else {
                same = 0
                last = now
            }
        }
        return last
    }
}

/** A clock the test owns: every read moves it by 1 ms (so row timestamps are strictly increasing) and [advance] jumps. */
class FakeClock(start: Long = CLOCK_BASE) {
    private val t = AtomicLong(start)

    fun now(): Long = t.incrementAndGet()

    fun peek(): Long = t.get()

    fun advance(ms: Long): Long = t.addAndGet(ms)
}

/**
 * An established-mode mesh connection with the instruments the laws need and the session must not be trusted with: every plaintext write is logged BEFORE it
 * is handed to the TLS engine (the event order is the durable-before order), every plaintext read is logged, a write that fails is counted as `unsent`
 * (a write-ahead row may claim bytes that never left, ERR-LL-11), and the record tap of the socket underneath is kept. [writeRaw] is a hostile or
 * out-of-session write: it is logged as raw and goes through the same write lock.
 */
class TappedConn(val inner: TlsMeshConnection, val name: String, private val log: Log, val tap: RecordTap, val observer: HandshakeObserver, val channel: SocketChannel?) : MeshConnection {
    override val role: PeerRole get() = inner.role
    override val mode: ConnMode get() = inner.mode
    override val peerPin: Pin? get() = inner.peerPin

    /** The figures at the instant TLS handed the connection over: the engine's counters and the raw socket totals. Nothing has been read or written by a session yet. */
    class Established(val engineIn: Long, val engineOut: Long, val tapRead: Long, val tapWritten: Long) {
        val engine: Long get() = engineIn + engineOut
        val tapTotal: Long get() = tapRead + tapWritten
    }

    val atEstablishment: Established = inner.counters().let { Established(it.networkBytesIn, it.networkBytesOut, tap.bytesRead, tap.bytesWritten) }

    private val writeLock = Any()
    private val plainIn = AtomicLong()
    private val plainOut = AtomicLong()
    private val unsentBytes = AtomicLong()
    private val unsentWrites = AtomicInteger()

    /** The byte arrays of the writes that failed (the same instances the log holds), so that a count of what the peer received can leave them out. */
    val failedWrites = CopyOnWriteArrayList<ByteArray>()
    private val closedFlag = AtomicBoolean(false)

    /** Plaintext bytes that crossed the stream boundary (not the rows, not the session's own counters). */
    val plaintextRead: Long get() = plainIn.get()
    val plaintextWritten: Long get() = plainOut.get()
    val unsent: Long get() = unsentBytes.get()
    val failedWriteCount: Int get() = unsentWrites.get()
    val closedByUs: Boolean get() = closedFlag.get()

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            while (true) {
                val n = read(one, 0, 1)
                if (n < 0) return -1
                if (n == 1) return one[0].toInt() and 0xFF
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.input.read(b, off, len)
            if (n > 0) {
                plainIn.addAndGet(n.toLong())
                log.add(Ev.Read(name, b.copyOfRange(off, off + n)))
            }
            return n
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) = send(b.copyOfRange(off, off + len), raw = false)
    }

    private fun send(bytes: ByteArray, raw: Boolean) {
        synchronized(writeLock) {
            log.add(Ev.Write(name, bytes, raw))
            try {
                inner.output.write(bytes, 0, bytes.size)
                plainOut.addAndGet(bytes.size.toLong())
            } catch (e: IOException) {
                unsentBytes.addAndGet(bytes.size.toLong())
                unsentWrites.incrementAndGet()
                failedWrites += bytes
                throw e
            }
        }
    }

    /** Bytes that do not come from the session (a hostile peer's frames, or an extension frame an honest test peer injects). */
    fun writeRaw(bytes: ByteArray) = send(bytes, raw = true)

    override fun counters(): TransportCounters = inner.counters()

    override fun close() {
        if (closedFlag.compareAndSet(false, true)) log.add(Ev.Closed(name))
        inner.close()
    }

    /** A crashed process: the connection is reset (SO_LINGER 0), nothing is flushed and no close_notify is sent. */
    fun reset() {
        if (!closedFlag.compareAndSet(false, true)) return
        log.add(Ev.Closed("$name(reset)"))
        val ch = channel ?: error("a reset needs the socket")
        try {
            ch.setOption(StandardSocketOptions.SO_LINGER, 0)
        } catch (_: IOException) {
        }
        runCatching { ch.close() }
    }
}

/** A sink that behaves as a dead process after [crash]: nothing more becomes durable. Used only to model a crash; every append after it throws. */
class DeadAfterCrash(private val inner: RowSink) : RowSink {
    @Volatile
    private var dead = false

    val isDead: Boolean get() = dead

    fun crash() {
        dead = true
    }

    override fun append(row: LabRouteRecord) {
        if (dead) throw LedgerWriteException("the process crashed")
        inner.append(row)
    }
}

/** What the wire looked like at the instant an append failed: nothing may follow it (L-L13). */
class FailureProbe(val seq: Int, val row: LabRouteRecord, val tapWritten: Long, val recordsOut: Int, val plainWritten: Long, val plainRead: Long)

/** The logging sink of the session track, which also snapshots the socket and the plaintext tap at every failed append (taken under the session lock, before anything else can be written). */
class ProbeSink(private val node: String, private val inner: RowSink, private val log: Log, private val conn: () -> TappedConn?) : RowSink {
    val probes = CopyOnWriteArrayList<FailureProbe>()

    override fun append(row: LabRouteRecord) {
        try {
            inner.append(row)
        } catch (e: LedgerWriteException) {
            val ev = log.add(Ev.AppendFailed(node, row))
            conn()?.let { probes += FailureProbe(ev.seq, row, it.tap.bytesWritten, it.tap.recordsOut, it.plaintextWritten, it.plaintextRead) }
            throw e
        }
        log.add(Ev.Appended(node, row))
    }
}

class TNode(
    val name: String,
    val key: TestOnlyKeys.Entry,
    val log: Log,
    val clock: FakeClock,
    seed: Long,
    dir: Path,
    fail: FailPlan?,
    peerSpkis: Map<String, ByteArray>,
    val limits: xyz.mdhv.asom.lab.proto.wire.Limits? = null,
) {
    val tn: TestNode = TestNode.testOnly(key.name)
    val pin: Pin = Pin.fromNodeId(key.nodeId)!!
    val store = InMemoryPeerStore()
    val registry = PeerRegistry(store)
    val ledgerPath: Path = dir.resolve("ledger-$name.jsonl")
    val jsonl = JsonlSink(ledgerPath)
    val crash: CrashInjectingSink? = fail?.let { CrashInjectingSink(jsonl, it.failAt, it.mode, it.sticky) }
    val dead = DeadAfterCrash(crash ?: jsonl)
    val probe = ProbeSink(name, dead, log) { conn }
    val ledger = NodeLedger(name, probe) { clock.now() }
    val engine = PacedEngine(name, log)
    val live = FakeLive()
    var serving = ServingView()
    val manifest = TestManifestPort(key, peerSpkis)
    val tapBook = SessionIdBook()
    val node = MeshNode(
        NodeConfig(pin, "Display Name $name", Platform.LINUX, KeyTier.FILE, "asom-lab/0.0.1", productionKeys = false).let { c ->
            if (limits == null) c else NodeConfig(c.pin, c.name, c.platform, c.keyTier, c.sw, limits = limits, productionKeys = false)
        },
        ledger, registry, engine, { serving }, live, manifest, SeededIds(seed),
        SessionObserver { s, type, stream, payload -> log.add(Ev.Dispatch(name, s, type, stream, payload)) },
    )
    val counters: RefusalCounters get() = node.counters

    fun identity(): MeshIdentity = tn.identity()

    @Volatile
    var pairingWindow: Boolean = false

    @Volatile
    var conn: TappedConn? = null

    fun env(): MeshTlsEnv = MeshTlsEnv(registry.asStatusSource(), { Instant.ofEpochSecond(TestNode.NOW.epochSecond) }, { pairingWindow }, productionKeys = false)

    /** The rows in the real JSONL file (parsed strictly; a torn tail is not a row). */
    fun rows(): List<LabRouteRecord> = JsonlReader.read(ledgerPath).rows

    fun pair(other: Pin, scopes: Set<Scope> = setOf(Scope.INFER, Scope.MANIFEST, Scope.STATE)) {
        val c = PairingCeremony(other, "peer", "linux", PeerClass.OWN, ByteArray(32) { 7 }, scopes)
        c.localApprove()
        c.remoteApprove()
        registry.commitPairing(c, CLOCK_BASE)
    }

    fun close() {
        runCatching { jsonl.close() }
    }
}

/** The dialler: connects a socket to the loopback port in `destAddr`, runs the real handshake, and returns the tapped connection (or the typed DIAL outcome). */
class TlsDialer(private val from: TNode, private val peer: Pin, private val log: Log, private val connName: String, private val expect: ChainMode = ChainMode.ExpectPaired(peer)) : Dialer {
    var conn: TappedConn? = null
        private set
    var failure: Throwable? = null
        private set

    /** The record tap of the last dial, success or not: the ClientHellos of the honest dialler are read from it (W08: no `pre_shared_key`). */
    var tap: RecordTap? = null
        private set

    override fun connect(destAddr: String): ConnectResult {
        val port = destAddr.substringAfterLast(':').toInt()
        log.add(Ev.Connect(from.name, destAddr))
        val ch = try {
            SocketChannel.open(InetSocketAddress(Loop.ADDRESS, port))
        } catch (e: IOException) {
            failure = e
            return ConnectResult(DialOutcomes.of(e))
        }
        ch.socket().tcpNoDelay = true
        val tap = RecordTap(SocketNet(ch), from.tapBook)
        this.tap = tap
        val observer = HandshakeObserver()
        val tls = try {
            MeshTls.dial(tap, from.identity(), expect, from.env(), observer)
        } catch (e: MeshTlsException) {
            failure = e
            noteClientHellos(tap)
            return ConnectResult(DialOutcomes.of(e))
        }
        noteClientHellos(tap)
        val c = TappedConn(tls, connName, log, tap, observer, ch)
        conn = c
        from.conn = c
        return ConnectResult(DialOutcomes.CONNECTED, c)
    }
}

private fun noteClientHellos(tap: RecordTap) {
    W08Tls.honestClientHellos.addAndGet(tap.clientHellos.size)
    W08Tls.honestPskClientHellos.addAndGet(tap.pskClientHellos)
    W08Tls.hostileEarlyDataClientHellos.addAndGet(0)
}

/** The client CertificateVerify cannot be seen (encrypted); what the server side proves is a 2-certificate chain, exactly one verifier call and an Accepted verdict (ERRATA ERR-PL-2). */
fun noteEstablishedListener(tls: TlsMeshConnection, observer: HandshakeObserver) {
    W08Tls.establishedSessions.incrementAndGet()
    if (tls.facts.peerChainLength == 2 && observer.trustInvocations == 1 && observer.verdict is xyz.mdhv.asom.lab.proto.trust.ChainVerdict.Accepted) W08Tls.sessionsWithClientCertVerify.incrementAndGet()
}

/** What the listener side of one accepted socket produced: a session over a tapped connection, or the typed refusal. */
class Accepted(val conn: TappedConn?, val session: Session?, val driver: SessionDriver?, val refusal: MeshTlsException?)

/** A loopback listener, test-only: bound to 127.0.0.1 with an ephemeral port, closed by [close]. */
class Loopback : AutoCloseable {
    private val server: ServerSocketChannel = ServerSocketChannel.open().also { it.bind(InetSocketAddress(Loop.ADDRESS, 0)) }
    val port: Int get() = (server.localAddress as InetSocketAddress).port
    val address: String get() = "127.0.0.1:$port"

    fun accept(): SocketChannel = server.accept().also { it.socket().tcpNoDelay = true }

    override fun close() {
        runCatching { server.close() }
    }
}

/**
 * `InboundRefusedCounter` is a plain, unsynchronised `count++` (one writer in the node's design); the harness runs one acceptor thread per connection, so the
 * calls are serialised here. Without it, concurrent refusals (eight stalled handshakes all failing at once, on a 2-core runner) lose increments.
 */
private val refusalLock = Any()

private fun countRefusal(node: TNode) = synchronized(refusalLock) { node.node.inboundRefused.refused() }

/** Accepts one socket on [node]: TLS handshake, then the session engine and its driver. A refused handshake is counted as an inbound refusal. */
fun acceptOn(node: TNode, ch: SocketChannel, connName: String, log: Log, limiter: HandshakeLimiter? = null, beforeSession: () -> Unit = {}): Accepted {
    val ticket = limiter?.let {
        val source = (ch.remoteAddress as InetSocketAddress).address.hostAddress
        when (val a = it.admit(source)) {
            is Admission.Admitted -> a
            is Admission.Refused -> {
                runCatching { ch.close() }
                countRefusal(node)
                return Accepted(null, null, null, null)
            }
        }
    }
    val tap = RecordTap(SocketNet(ch), node.tapBook)
    val observer = HandshakeObserver()
    val tls = try {
        MeshTls.accept(tap, node.identity(), node.env(), observer)
    } catch (e: MeshTlsException) {
        ticket?.release()
        countRefusal(node)
        return Accepted(null, null, null, e)
    }
    ticket?.release()
    noteEstablishedListener(tls, observer)
    val conn = TappedConn(tls, connName, log, tap, observer, ch)
    node.conn = conn
    beforeSession()
    val session = node.node.accept(conn)
    val driver = SessionDriver(session, { node.clock.peek() }, "${node.name}-srv").start()
    return Accepted(conn, session, driver, null)
}

/** One two-node world over real loopback TLS 1.3: real registries, real JSONL ledgers in a temp directory, the real session engine, the scripted fake engine. */
class TlsWorld(
    val seed: Long = 1,
    failA: FailPlan? = null,
    failB: FailPlan? = null,
    keyA: String = "key1",
    keyB: String = "key2",
    limitsB: xyz.mdhv.asom.lab.proto.wire.Limits? = null,
    paired: Boolean = true,
) : AutoCloseable {
    val dir: Path = Files.createTempDirectory("asom-integration-")
    val log = Log()
    val clockA = FakeClock()
    val clockB = FakeClock()
    private val spkis = TestOnlyKeys.entries.associate { it.nodeId to it.spki }
    val a = TNode("A", TestOnlyKeys.key(keyA), log, clockA, seed * 2 + 1, dir, failA, spkis)
    val b = TNode("B", TestOnlyKeys.key(keyB), log, clockB, seed * 2 + 2, dir, failB, spkis, limitsB)
    val bodyGrams = HashSet<String>()
    val requestIds = ArrayList<String>()
    val links = CopyOnWriteArrayList<TlsLink>()

    @Volatile
    var lastAccepted: Accepted? = null
    private val drivers = CopyOnWriteArrayList<SessionDriver>()

    init {
        if (paired) {
            a.pair(b.pin)
            b.pair(a.pin)
        }
    }

    fun noteBody(body: ByteArray, requestId: String?) {
        val t = String(body, Charsets.ISO_8859_1)
        for (i in 0..t.length - 8) bodyGrams += t.substring(i, i + 8)
        requestId?.let { requestIds += it }
    }

    /** A dials B over a fresh loopback connection: the DIAL rows, the real handshake, HELLO / HELLO_ACK. Returns the link, established or not (a refused dial has no sessions). */
    fun connect(limiter: HandshakeLimiter? = null, dialerName: String = "A>B", listenerName: String = "B<A"): TlsLink {
        Loopback().use { lb ->
            var accepted: Accepted? = null
            var acceptError: Throwable? = null
            val t = Thread {
                try {
                    accepted = acceptOn(b, lb.accept(), listenerName, log, limiter)
                } catch (e: Throwable) {
                    acceptError = e
                }
            }.also { it.isDaemon = true; it.name = "accept-B"; it.start() }
            val dialer = TlsDialer(a, b.pin, log, dialerName)
            val report = try {
                a.node.dial(b.pin, lb.address, "qr", dialer)
            } catch (e: Throwable) {
                runCatching { lb.close() }
                t.join(5_000)
                accepted?.driver?.let { drivers += it }
                lastAccepted = accepted
                throw e
            }
            var sessionA: Session? = null
            var driverA: SessionDriver? = null
            if (report.connection != null) {
                sessionA = a.node.openDialed(report, b.pin)
                driverA = SessionDriver(sessionA, { clockA.peek() }, "A-cli").start().also { drivers += it }
            }
            t.join(15_000)
            acceptError?.let { throw it }
            val acc = accepted
            acc?.driver?.let { drivers += it }
            return TlsLink(this, report, dialer, sessionA, driverA, acc).also { links += it }
        }
    }

    fun awaitEstablished(link: TlsLink) {
        Wait.until("both sessions established") { link.sessionA!!.established && link.sessionB!!.established }
    }

    private val extras = CopyOnWriteArrayList<AutoCloseable>()

    /** Something to close when the world closes (a session driver, a raw peer). */
    fun track(c: AutoCloseable) {
        extras += c
    }

    override fun close() {
        extras.forEach { runCatching { it.close() } }
        drivers.forEach { runCatching { it.close() } }
        a.close()
        b.close()
        runCatching { dir.toFile().deleteRecursively() }
    }
}

class TlsLink(val world: TlsWorld, val report: xyz.mdhv.asom.lab.proto.session.DialReport, val dialer: TlsDialer, val sessionA: Session?, val driverA: SessionDriver?, val accepted: Accepted?) {
    val sessionB: Session? get() = accepted?.session
    var connAOverride: TappedConn? = null
    val connA: TappedConn? get() = connAOverride ?: dialer.conn
    val connB: TappedConn? get() = accepted?.conn
}

/** A peer that is not a session: it holds an established TLS connection and exchanges raw bytes (a hostile node with a valid identity). */
class RawPeer(val conn: TappedConn) : AutoCloseable {
    private val inbound = ByteArrayOutputStream()
    private var consumed = 0
    private val lock = Object()

    @Volatile
    var eof = false
        private set

    @Volatile
    var error: Throwable? = null
        private set
    private val reader = Thread {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = conn.input.read(buf, 0, buf.size)
                if (n < 0) break
                synchronized(lock) { inbound.write(buf, 0, n) }
            }
        } catch (e: Throwable) {
            error = e
        }
        eof = true
    }.also { it.isDaemon = true; it.name = "raw-peer-read"; it.start() }

    fun writeRaw(bytes: ByteArray) = conn.writeRaw(bytes)

    /** All complete frames not yet returned. */
    fun frames(): List<Fr> = synchronized(lock) {
        val all = inbound.toByteArray()
        val rest = all.copyOfRange(consumed, all.size)
        val got = Frames.parsePrefix(rest)
        consumed += got.sumOf { it.app }.toInt()
        got
    }

    val receivedBytes: Int get() = synchronized(lock) { inbound.size() }

    fun awaitFrames(n: Int, what: String = "$n frame(s)"): List<Fr> {
        val out = ArrayList<Fr>()
        Wait.until("the raw peer to receive $what") {
            out += frames()
            out.size >= n
        }
        return out
    }

    /** Every frame up to and including the first one of [type]. */
    fun awaitType(type: Int, what: String): List<Fr> {
        val out = ArrayList<Fr>()
        Wait.until("the raw peer to receive $what") {
            out += frames()
            out.any { it.type == type }
        }
        return out
    }

    fun awaitEof() = Wait.until("the connection to end") { eof }

    override fun close() {
        runCatching { conn.close() }
    }
}
