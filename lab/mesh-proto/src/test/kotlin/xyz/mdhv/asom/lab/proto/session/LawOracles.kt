package xyz.mdhv.asom.lab.proto.session

import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase

class L13Result {
    var runsWithFailure = 0
    var failureEvents = 0
    var controlFailures = 0
    var requesterIntentFailures = 0
    var lenderIntentFailures = 0
    var lenderEndOutcomeFailures = 0
    var lenderDeclineOutcomeFailures = 0
    var dialFailures = 0
    var framesAfterControlFailure = 0
    var contentFramesAfterStickyFailure = 0
}

/**
 * L-L13 (LAB_SPEC 7.7, scoped by ERRATA ERR-LL-7): with a sink that throws at a durability point, nothing that depends on the failed row reaches a socket.
 * The instruments are the physical write log and the engine log; they are not the session's own flags.
 */
object L13 {
    private fun fail(msg: String): Nothing = throw AssertionError(msg)

    private val CONTENT_TYPES = setOf(0x13, 0x15, 0x23)

    fun check(w: World, sticky: Map<String, Boolean>, into: L13Result) {
        val events = w.log.snapshot()
        val failures = events.filterIsInstance<Ev.AppendFailed>()
        if (failures.isEmpty()) return
        into.runsWithFailure++
        for (f in failures) {
            into.failureEvents++
            val r = f.row
            val link = w.links.firstOrNull { it.kit == f.node && it.session.sessionId == r.sessionId }
            val writesAfter = if (link == null) emptyList() else events.filterIsInstance<Ev.Write>().filter { it.conn == link.conn.name && it.seq > f.seq }
            val perFrame = r.meshKind in Table76.PER_FRAME_KINDS || (r.meshKind == MeshKind.SESSION && r.meshCode != null && !r.meshCode!!.startsWith("close"))
            when {
                perFrame -> {
                    into.controlFailures++
                    if (link == null) fail("a control row failed on a session that is not in the world")
                    if (writesAfter.isNotEmpty()) fail("${writesAfter.size} frame(s) sent on ${link.conn.name} after the control-row failure of ${r.meshCode} (event ${f.seq})")
                    into.framesAfterControlFailure += writesAfter.size
                    if (!link.conn.closedByUs) fail("the connection was not closed after a control-row failure")
                    if (!link.session.closedByLedgerFailure) fail("the session does not report a ledger-failure close")
                    val closeTried = events.any { (it is Ev.Appended && it.node == f.node && it.row.sessionId == r.sessionId && it.row.meshCode == "close:ledger-failure") || (it is Ev.AppendFailed && it.seq > f.seq && it.node == f.node && it.row.meshCode == "close:ledger-failure") }
                    if (!closeTried) fail("the SESSION close row was not attempted after FC-2")
                }
                r.meshKind == MeshKind.INFER_SENT && r.phase == Phase.INTENT -> {
                    into.requesterIntentFailures++
                    if (events.filterIsInstance<Ev.Write>().any { String(it.bytes, Charsets.ISO_8859_1).contains(r.attemptId!!) }) fail("FC-1: a frame of attempt ${r.attemptId} left although its intent row failed")
                }
                r.meshKind == MeshKind.INFER_SERVED && r.phase == Phase.INTENT -> {
                    into.lenderIntentFailures++
                    if (events.any { it is Ev.EngineOpen && it.attemptId == r.attemptId }) fail("FC-4: the engine started although the lender intent row failed")
                    val declines = events.filterIsInstance<Ev.Write>().filter { it.seq > f.seq && it.conn == link?.conn?.name }.map { Frames.parse(it.bytes) }.flatten().filter { it.type == 0x12 && it.attemptId == r.attemptId }
                    val declineRowFailed = events.any { it is Ev.AppendFailed && it.seq > f.seq && it.row.attemptId == r.attemptId && it.row.phase == Phase.OUTCOME }
                    if (declines.size != 1 && !declineRowFailed) fail("FC-4: no INFER_DECLINE followed the failed lender intent row (and its own row did not fail)")
                    if (declines.isNotEmpty() && declines[0].code != "PEER_UNAVAILABLE") fail("FC-4: the decline code is ${declines[0].code}, not PEER_UNAVAILABLE")
                }
                r.meshKind == MeshKind.INFER_SERVED && r.phase == Phase.OUTCOME -> {
                    if (r.status == 503) into.lenderDeclineOutcomeFailures++ else into.lenderEndOutcomeFailures++
                    if (writesAfter.isNotEmpty()) fail("FC-5/FC-2: ${writesAfter.size} frame(s) sent after a failed lender outcome row (attempt ${r.attemptId})")
                    if (link != null && !link.conn.closedByUs) fail("the connection was not closed after a failed lender outcome row")
                    if (events.filterIsInstance<Ev.Write>().any { it.seq > f.seq && Frames.parse(it.bytes).any { fr -> fr.type == 0x16 && fr.attemptId == r.attemptId } }) fail("FC-5: INFER_END was sent after its outcome row failed")
                }
                r.meshKind == MeshKind.DIAL && r.phase == Phase.INTENT -> {
                    into.dialFailures++
                    val nextIntent = events.firstOrNull { it is Ev.Appended && it.seq > f.seq && it.row.meshKind == MeshKind.DIAL && it.row.phase == Phase.INTENT }?.seq ?: Int.MAX_VALUE
                    if (events.any { it is Ev.Connect && it.seq > f.seq && it.seq < nextIntent }) fail("L-L14: a connect followed a failed DIAL intent")
                }
                else -> Unit
            }
            if (sticky[f.node] == true) {
                val late = events.filterIsInstance<Ev.Write>().filter { it.seq > f.seq && it.conn.startsWith(f.node) }.flatMap { Frames.parse(it.bytes) }.filter { it.type in CONTENT_TYPES }
                if (late.isNotEmpty()) {
                    into.contentFramesAfterStickyFailure += late.size
                    fail("a sink that keeps failing: ${late.size} content frame(s) reached a socket after the failure (event ${f.seq})")
                }
            }
        }
    }
}

object L14 {
    /** Every connect has a durable DIAL intent row before it; returns the number of connects checked. */
    fun check(log: Log): Int {
        val events = log.snapshot()
        var n = 0
        for (c in events.filterIsInstance<Ev.Connect>()) {
            val intent = events.lastOrNull { it is Ev.Appended && it.seq < c.seq && it.node == c.node && it.row.meshKind == MeshKind.DIAL && it.row.phase == Phase.INTENT && it.row.destAddr == c.destAddr }
            if (intent == null) throw AssertionError("L-L14: a connect to ${c.destAddr} (event ${c.seq}) had no durable DIAL intent row before it")
            n++
        }
        return n
    }
}

/** The per-frame rows of a pairing channel against LAB_SPEC 7.6 and the session-id rule of 7.5. */
object PairOracle {
    private fun fail(msg: String): Nothing = throw AssertionError(msg)

    fun check(p: PairWorld, counts: Counts) {
        val events = p.log.snapshot()
        val derived = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(p.nonceS.copyOfRange(0, 16))
        for ((side, rows, conn, peerConn) in listOf(Quad("S", p.rowsS(), "S<D", "D>S"), Quad("D", p.rowsD(), "D>S", "S<D"))) {
            val writes = events.filterIsInstance<Ev.Write>().filter { it.conn == conn && !it.raw }
            val sent = writes.map { Frames.one(it.bytes) }
            val read = Frames.parsePrefix(events.filterIsInstance<Ev.Read>().filter { it.conn == conn }.fold(ByteArray(0)) { acc, e -> acc + e.bytes })
            val appended = events.filterIsInstance<Ev.Appended>().filter { it.node == side }
            val failed = events.filterIsInstance<Ev.AppendFailed>().filter { it.node == side }
            val expected = ArrayList<Triple<MeshKind, String, Pair<Long, Long>>>()
            for (f in sent) Table76.rowFor(f, true)?.let { expected += Triple(it.kind, it.code, f.app to 0L) }
            for (f in read) Table76.rowFor(f, false)?.let { expected += Triple(it.kind, it.code, 0L to f.app) }
            val actual = appended.filter { it.row.meshKind in Table76.PER_FRAME_KINDS }.toMutableList()
            for (e in expected) {
                val i = actual.indexOfFirst { it.row.meshKind == e.first && it.row.meshCode == e.second && it.row.bytesOut == e.third.first && (it.row.bytesIn ?: 0) == e.third.second }
                if (i >= 0) {
                    actual.removeAt(i)
                    counts.checks++
                } else if (failed.none { it.row.meshKind == e.first && it.row.meshCode == e.second }) {
                    fail("pairing $side: no row ${e.first}/${e.second} for a ${e.second} frame")
                }
            }
            if (actual.isNotEmpty()) fail("pairing $side: rows without a frame: ${actual.map { it.row.meshCode }}")
            for ((idx, w) in writes.withIndex()) {
                val f = sent[idx]
                val row = appended.firstOrNull { it.row.meshKind == Table76.rowFor(f, true)!!.kind && it.row.meshCode == Table76.rowFor(f, true)!!.code && it.row.bytesOut == f.app }
                    ?: fail("pairing $side: ${f.name} sent with no row")
                if (row.seq > w.seq) fail("pairing $side: ${f.name} was handed to the transport before its row")
                counts.checks++
            }
            sent.forEach { Table76.l16Name(it)?.let(counts::type) }
            read.forEach { Table76.l16Name(it)?.let(counts::type) }
            val sessionRows = appended.filter { it.row.meshKind == MeshKind.SESSION }
            val ids = (appended.map { it.row } + failed.map { it.row }).filter { it.meshKind != null }
            if (side == "S") {
                if (sessionRows.firstOrNull()?.row?.meshCode != "pairing") fail("pairing S: the first SESSION row is not the pairing open row")
                if (appended.firstOrNull()?.row?.meshKind != MeshKind.SESSION) fail("pairing S: a row precedes the SESSION open row")
                if (ids.any { it.sessionId != derived }) fail("pairing S: a row carries a session id other than the base64url of the first 16 bytes of nonceS")
            } else {
                val local = ids.first().sessionId
                if (ids.any { it.sessionId != local }) fail("pairing D: rows do not share one local connection id")
                val firstHello = appended.indexOfFirst { it.row.meshCode == "PAIR_HELLO" }
                for ((k, r) in appended.withIndex()) {
                    val want = if (firstHello in 0..k) derived else null
                    if (r.row.attemptId != want) fail("pairing D: attemptId ${r.row.attemptId} on row $k, expected $want")
                }
            }
            if (sessionRows.count { it.row.meshCode!!.startsWith("close") } > 1) fail("pairing $side: more than one SESSION close row")
            counts.sessions++
        }
    }

    private data class Quad(val side: String, val rows: List<LabRouteRecord>, val conn: String, val peerConn: String)
}
