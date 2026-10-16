package xyz.mdhv.asom.lab.proto.integration

import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.proto.session.Ev
import xyz.mdhv.asom.lab.proto.session.Frames

import xyz.mdhv.asom.lab.proto.session.Table76

/**
 * L-L13 over real TLS (LAB_SPEC 7.7, scoped by ERRATA ERR-LL-7): with a sink that throws at a durability point, nothing that depends on the failed row reaches a
 * socket. Three instruments are independent of the session: the plaintext log (what was handed to the TLS engine), the record tap at the socket (what actually left,
 * snapshotted at the failure by the sink probe), and the peer's plaintext tap (what actually arrived). After a control-row failure the socket may carry at most
 * the one TLS alert record of the close, and the TLS connection is closed.
 */
object L13Tls {
    private fun fail(msg: String): Nothing = throw AssertionError(msg)

    private val CONTENT_TYPES = setOf(0x13, 0x15, 0x23)

    /** The size of the one TLS record a close puts on the wire, as measured on this JVM (see [TlsCalibration]). */
    val ALERT_RECORD_BYTES: Long get() = TlsCalibration.alertRecord

    fun check(w: TlsWorld, link: TlsLink?, sticky: Map<String, Boolean>, into: L13Counts) {
        val events = w.log.snapshot()
        val failures = events.filterIsInstance<Ev.AppendFailed>()
        if (failures.isEmpty()) return
        into.runsWithFailure++
        val ends = link?.takeIf { it.sessionA != null && it.sessionB != null }?.let { TlsOracle.ends(it) }
        for (f in failures) {
            into.failureEvents++
            val r = f.row
            val mine = ends?.toList()?.firstOrNull { it.name == f.node && it.sessionId == r.sessionId }
            val peer = mine?.let { m -> ends!!.toList().first { it !== m } }
            val writesAfter = if (mine == null) emptyList() else events.filterIsInstance<Ev.Write>().filter { it.conn == mine.conn.name && it.seq > f.seq }
            val perFrame = r.meshKind in Table76.PER_FRAME_KINDS || (r.meshKind == MeshKind.SESSION && r.meshCode != null && !r.meshCode!!.startsWith("close"))
            val node = if (f.node == "A") w.a else w.b
            val probe = node.probe.probes.firstOrNull { it.seq == f.seq }
            when {
                perFrame -> {
                    into.controlFailures++
                    if (mine == null) fail("a control row failed on a session that is not in the world")
                    if (writesAfter.isNotEmpty()) fail("${writesAfter.size} frame(s) handed to TLS on ${mine.conn.name} after the control-row failure of ${r.meshCode} (event ${f.seq})")
                    into.framesAfterControlFailure += writesAfter.size
                    if (probe == null) fail("no wire snapshot at the control-row failure")
                    val tap = mine.conn.tap
                    if (mine.conn.plaintextWritten != probe.plainWritten) fail("plaintext written grew after the failure: ${probe.plainWritten} -> ${mine.conn.plaintextWritten}")
                    val after = tap.bytesWritten - probe.tapWritten
                    if (after > ALERT_RECORD_BYTES || tap.recordsOut - probe.recordsOut > 1) fail("the socket carried $after bytes in ${tap.recordsOut - probe.recordsOut} record(s) after the failure; at most the one alert record of the close ($ALERT_RECORD_BYTES) may")
                    if (peer != null && peer.conn.plaintextRead > probe.plainWritten) fail("the peer received ${peer.conn.plaintextRead} plaintext bytes, but only ${probe.plainWritten} were handed to TLS before the failure")
                    if (!mine.conn.closedByUs) fail("the connection was not closed after a control-row failure")
                    if (!mine.session!!.closedByLedgerFailure) fail("the session does not report a ledger-failure close")
                    val closeTried = events.any { (it is Ev.Appended && it.node == f.node && it.row.sessionId == r.sessionId && it.row.meshCode == "close:ledger-failure") || (it is Ev.AppendFailed && it.seq > f.seq && it.node == f.node && it.row.meshCode == "close:ledger-failure") }
                    if (!closeTried) fail("the SESSION close row was not attempted after FC-2")
                    into.tapChecked++
                }
                r.meshKind == MeshKind.INFER_SENT && r.phase == Phase.INTENT -> {
                    into.requesterIntentFailures++
                    if (events.filterIsInstance<Ev.Write>().any { String(it.bytes, Charsets.ISO_8859_1).contains(r.attemptId!!) }) fail("FC-1: a frame of attempt ${r.attemptId} left although its intent row failed")
                }
                r.meshKind == MeshKind.INFER_SERVED && r.phase == Phase.INTENT -> {
                    into.lenderIntentFailures++
                    if (events.any { it is Ev.EngineOpen && it.attemptId == r.attemptId }) fail("FC-4: the engine started although the lender intent row failed")
                    val declines = events.filterIsInstance<Ev.Write>().filter { it.seq > f.seq && it.conn == mine?.conn?.name }.map { Frames.parse(it.bytes) }.flatten().filter { it.type == 0x12 && it.attemptId == r.attemptId }
                    val declineRowFailed = events.any { it is Ev.AppendFailed && it.seq > f.seq && it.row.attemptId == r.attemptId && it.row.phase == Phase.OUTCOME }
                    if (declines.size != 1 && !declineRowFailed) fail("FC-4: no INFER_DECLINE followed the failed lender intent row (and its own row did not fail)")
                    if (declines.isNotEmpty() && declines[0].code != "PEER_UNAVAILABLE") fail("FC-4: the decline code is ${declines[0].code}, not PEER_UNAVAILABLE")
                }
                r.meshKind == MeshKind.INFER_SERVED && r.phase == Phase.OUTCOME -> {
                    if (r.status == 503) into.lenderDeclineOutcomeFailures++ else into.lenderEndOutcomeFailures++
                    if (writesAfter.isNotEmpty()) fail("FC-5/FC-2: ${writesAfter.size} frame(s) handed to TLS after a failed lender outcome row (attempt ${r.attemptId})")
                    if (mine != null && !mine.conn.closedByUs) fail("the connection was not closed after a failed lender outcome row")
                    if (events.filterIsInstance<Ev.Write>().any { it.seq > f.seq && Frames.parse(it.bytes).any { fr -> fr.type == 0x16 && fr.attemptId == r.attemptId } }) fail("FC-5: INFER_END was sent after its outcome row failed")
                    if (mine != null && probe != null) {
                        val after = mine.conn.tap.bytesWritten - probe.tapWritten
                        if (after > ALERT_RECORD_BYTES) fail("the socket carried $after bytes after a failed lender outcome row")
                        into.tapChecked++
                    }
                }
                r.meshKind == MeshKind.DIAL && r.phase == Phase.INTENT -> {
                    into.dialFailures++
                    val nextIntent = events.firstOrNull { it is Ev.Appended && it.seq > f.seq && it.row.meshKind == MeshKind.DIAL && it.row.phase == Phase.INTENT }?.seq ?: Int.MAX_VALUE
                    if (events.any { it is Ev.Connect && it.seq > f.seq && it.seq < nextIntent }) fail("L-L14: a connect followed a failed DIAL intent")
                }
                else -> Unit
            }
            if (sticky[f.node] == true && mine != null) {
                val late = events.filterIsInstance<Ev.Write>().filter { it.seq > f.seq && it.conn.startsWith(f.node) }.flatMap { Frames.parse(it.bytes) }.filter { it.type in CONTENT_TYPES }
                if (late.isNotEmpty()) {
                    into.contentFramesAfterStickyFailure += late.size
                    fail("a sink that keeps failing: ${late.size} content frame(s) reached a socket after the failure (event ${f.seq})")
                }
            }
        }
    }
}
