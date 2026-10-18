package xyz.mdhv.asom.lab.proto.integration

import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.manifest.RejectCode
import xyz.mdhv.asom.lab.proto.session.Fr
import xyz.mdhv.asom.lab.proto.session.Refusal
import xyz.mdhv.asom.lab.proto.session.Rw
import xyz.mdhv.asom.lab.proto.session.Session
import xyz.mdhv.asom.lab.proto.session.rw

/** What the hostile-node suite over real TLS counted. Every figure printed in the gate lines comes from here, and a zero in any required count fails the run. */
object W08Tls {
    val frameCases = AtomicInteger()
    val rowChecks = AtomicInteger()
    val kinds: MutableSet<Refusal> = ConcurrentHashMap.newKeySet()
    val framesAfterControlFailure = AtomicInteger()
    val controlFailureCases = AtomicInteger()
    val verdictsThroughSession: MutableSet<RejectCode> = ConcurrentHashMap.newKeySet()
    val verdictsDirect: MutableSet<RejectCode> = ConcurrentHashMap.newKeySet()
    val splitRuns = AtomicInteger()
    val typedRefusalsSeen: MutableMap<String, Int> = ConcurrentHashMap()
    val l15 = L15Stats()
    val closedByHonestChecks = AtomicInteger()
    val tapAfterFailureChecks = AtomicInteger()

    // the handshake half, against the real session engine
    val handshakeCases = AtomicInteger()
    val acceptedBadChains = AtomicInteger()
    val honestClientHellos = AtomicInteger()
    val honestPskClientHellos = AtomicInteger()
    val hostilePskClientHellos = AtomicInteger()
    val hostileEarlyDataClientHellos = AtomicInteger()
    val establishedSessions = AtomicInteger()
    val sessionsWithClientCertVerify = AtomicInteger()
    val sessionsUnderSessionEngine = AtomicInteger()
    val dialOutcomes: MutableMap<String, Int> = ConcurrentHashMap()
    val exercised: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val inboundRefusedRows = AtomicInteger()

    fun noteRefusalCodes(frames: List<Fr>) {
        for (f in frames) if (f.type == 6) f.code?.let { typedRefusalsSeen.merge(it, 1, Int::plus) }
    }

    fun finish(w: TlsWorld) {
        frameCases.incrementAndGet()
        for (n in listOf(w.a.node, w.b.node)) kinds += n.counters.snapshot().filterValues { it > 0 }.keys
    }

    fun refusalCodesLine(): String = TreeMap(typedRefusalsSeen).entries.joinToString(", ") { "${it.key}=${it.value}" }
}

/** Reads a node's JSONL rows once the expected number is there and the file has stopped growing, then compares them with a hand-written table. */
fun assertRows(kit: TNode, from: Int, vararg expected: Rw) {
    Wait.until("${kit.name} to have ${from + expected.size} rows") { kit.rows().size >= from + expected.size }
    Wait.stable("the JSONL file of ${kit.name}") { kit.rows().size }
    val actual = kit.rows().drop(from).map { it.rw().toString() }
    kotlin.test.assertEquals(expected.toList().map { it.toString() }, actual, "rows of ${kit.name} from #$from")
    W08Tls.rowChecks.addAndGet(expected.size)
}

fun rowsOfKit(kit: TNode): List<LabRouteRecord> = kit.rows()

/**
 * What every hostile case must also leave behind, whatever it was about: the honest session is closed (it is closed here if the case left it open), the rows of its
 * session add up to the plaintext and, with the overhead, to the socket (L-L15, MEASURED), and nothing was recorded as ESTIMATED. A refusal that closes also closed the TLS
 * connection: the hostile peer reads the end of the stream.
 */
fun finishCase(w: TlsWorld, honestKit: TNode, honest: Session, honestConn: TappedConn, hostile: RawPeer, sessionId: String = honest.sessionId, expectTlsClosed: Boolean = false) {
    if (expectTlsClosed) {
        hostile.awaitEof()
        W08Tls.closedByHonestChecks.incrementAndGet()
    }
    if (!honest.closed) honest.close()
    Wait.until("the honest session to close") { honest.closed }
    TlsRuns.settle(w, honestKit.name to honest)
    TlsOracle.l15(w, Side(honestKit, honestConn, honest, sessionId), hostile.conn, W08Tls.l15)
    W08Tls.finish(w)
}
