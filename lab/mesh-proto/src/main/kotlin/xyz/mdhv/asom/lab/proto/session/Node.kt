package xyz.mdhv.asom.lab.proto.session

import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import xyz.mdhv.asom.lab.ledger.InboundRefusedCounter
import xyz.mdhv.asom.lab.ledger.LabEgress
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.LedgerUnavailableException
import xyz.mdhv.asom.lab.ledger.LedgerWriteException
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.NodeLedger
import xyz.mdhv.asom.lab.ledger.Overhead
import xyz.mdhv.asom.lab.ledger.OverheadBasis
import xyz.mdhv.asom.lab.ledger.PeerPath
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.proto.transport.MeshConnection
import xyz.mdhv.asom.lab.proto.trust.PeerRegistry
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.trust.RegistryListener
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.PeerRole

/** The `attemptId`s seen from each peer in the last 24 h (trust.md 5.4); a repeat is `DUPLICATE_ATTEMPT`. Bounded, oldest first out. */
internal class AttemptLru(private val windowMs: Long = 86_400_000L, private val cap: Int = 65_536) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun seenBeforeAndRecord(pin: Pin, attemptId: String, now: Long): Boolean {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value >= windowMs) it.remove() else break
        }
        val key = pin.nodeId + "|" + attemptId
        val before = seen.containsKey(key)
        seen.remove(key)
        seen[key] = now
        while (seen.size > cap) seen.remove(seen.keys.first())
        return before
    }
}

/** Offers per minute from each peer. */
internal class RpmWindow(private val windowMs: Long = 60_000L) {
    private val perPin = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun countAndRecord(pin: Pin, now: Long): Int {
        val q = perPin.getOrPut(pin.nodeId) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.peekFirst() >= windowMs) q.pollFirst()
        val n = q.size
        q.addLast(now)
        return n
    }
}

/** What a dial reports: the id the dialer chose for the session, the code of the outcome row, and (for `connected`) the transport and the handshake figure the DIAL row carries. */
class DialReport(val sessionId: String, val code: String, val connection: MeshConnection?, val handshakeRecorded: Long, val peerPath: PeerPath?)

/**
 * One node: its identity, ledger, registry and ports, and every session it hosts. A registry change that leaves PAIRED closes that peer's sessions with
 * `GOAWAY revoked|suspended` (LAB_SPEC 7.2); the per-frame authorisation read in each session is the second, independent defence (trust.md 5.3).
 */
class MeshNode(
    val cfg: NodeConfig,
    val ledger: NodeLedger,
    val registry: PeerRegistry,
    val engine: EnginePort,
    val policy: LenderPolicyPort,
    val live: LivePort,
    val manifest: ManifestPort,
    val ids: IdSource,
    val observer: SessionObserver? = null,
) {
    val counters = RefusalCounters()
    val inboundRefused = InboundRefusedCounter(ledger)
    internal val attempts = AttemptLru()
    internal val rpm = RpmWindow()
    internal val inflight = AtomicInteger(0)
    private val sessions = CopyOnWriteArrayList<Session>()

    /** Counts input errors that are bugs, not peer behaviour (a verifier that threw). A test requires it to stay zero. */
    val internalErrors = AtomicInteger(0)

    /** `CANCEL` frames that named an attempt this lender had already finished (the requester cancelled before it saw the end): ignored, their bytes are uncovered input (ERRATA ERR-PS-14). */
    val lateCancels = AtomicInteger(0)

    init {
        registry.addListener(
            RegistryListener { change ->
                val g = change.goaway ?: return@RegistryListener
                sessionsOf(change.pin).forEach { it.goAwayForRegistry(g) }
            },
        )
    }

    fun sessionsOf(pin: Pin): List<Session> = sessions.filter { it.peer == pin }

    fun openSessions(): List<Session> = sessions.toList()

    internal fun register(s: Session) {
        sessions += s
    }

    internal fun unregister(s: Session) {
        sessions -= s
    }

    /** An authenticated inbound connection (TLS server role). The `SESSION` open row is written when the first frame names the session. */
    fun accept(conn: MeshConnection, peerPath: PeerPath? = null): Session {
        require(conn.role == PeerRole.TLS_SERVER && conn.mode == ConnMode.ESTABLISHED) { "accept takes an established-mode connection in the TLS server role" }
        val pin = requireNotNull(conn.peerPin) { "an established connection has an authenticated peer pin" }
        return Session.listener(this, conn, pin, peerPath)
    }

    /**
     * An outbound TCP connect (LAB_SPEC 7.6): the session id is chosen first, the `DIAL` intent is durable BEFORE the connect (L-L14), and the outcome row
     * carries the handshake overhead. An intent that is not durable means no connect at all ([LedgerUnavailableException]); an outcome that is not durable
     * closes the new connection before any byte crosses it.
     */
    fun dial(peer: Pin, destAddr: String, addrSource: String, dialer: Dialer): DialReport {
        require(addrSource in ADDR_SOURCES) { "addrSource is qr, hello or user" }
        val sessionId = ids.b64(16)
        val tag = peer.nodeTag

        fun row(phase: Phase, code: String?, status: Int, overhead: Long?, basis: OverheadBasis?, path: PeerPath?) = LabRouteRecord(
            ts = ledger.now(), callerPkg = "peer:$tag", requestedModel = "", egress = LabEgress.peerClass, status = status, attemptId = sessionId, phase = phase,
            peerNode = tag, peerPath = path, meshKind = MeshKind.DIAL, bytesIn = if (phase == Phase.INTENT) null else 0, meshCode = code, destAddr = destAddr,
            addrSource = addrSource, overheadBytes = overhead, overheadBasis = basis, sessionId = sessionId,
        )

        ledger.appendIntent(row(Phase.INTENT, null, 0, null, null, null))
        val result = dialer.connect(destAddr)
        require(result.code in DIAL_CODES) { "a DIAL outcome is one of $DIAL_CODES" }
        val conn = result.connection
        val ok = result.code == "connected"
        require(ok == (conn != null)) { "a connection exists exactly when the dial connected" }
        var handshake = 0L
        var basis: OverheadBasis? = null
        if (conn != null) {
            val c = conn.counters()
            basis = if (c.measured) OverheadBasis.MEASURED else OverheadBasis.ESTIMATED
            handshake = if (c.measured) c.networkBytesIn + c.networkBytesOut else Overhead.estimatedHandshake()
        }
        try {
            ledger.append(row(Phase.OUTCOME, result.code, if (ok) 200 else 599, if (ok) handshake else null, basis, if (ok) result.peerPath else null))
        } catch (e: LedgerWriteException) {
            conn?.close()
            throw LedgerUnavailableException("LEDGER_UNAVAILABLE", e)
        }
        return DialReport(sessionId, result.code, conn, handshake, result.peerPath)
    }

    /** The session on a connection a [dial] returned. It sends `HELLO` at once. */
    fun openDialed(report: DialReport, peer: Pin): Session {
        val conn = requireNotNull(report.connection) { "the dial did not connect" }
        require(conn.role == PeerRole.TLS_CLIENT && conn.mode == ConnMode.ESTABLISHED) { "a dialed session is the TLS client of an established-mode connection" }
        require(conn.peerPin == peer) { "the authenticated pin is the pin that was dialed" }
        return Session.dialer(this, conn, peer, report.sessionId, report.handshakeRecorded, report.peerPath).also { it.start() }
    }

    companion object {
        val ADDR_SOURCES = setOf("qr", "hello", "user")
        val DIAL_CODES = setOf("connected", "refused", "timeout", "pin-mismatch", "not-tls", "local-network-denied", "firewall-blocked")
    }
}
