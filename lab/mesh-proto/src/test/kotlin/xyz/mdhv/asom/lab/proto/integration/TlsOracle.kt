package xyz.mdhv.asom.lab.proto.integration

import java.util.TreeMap
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Overhead
import xyz.mdhv.asom.lab.ledger.OverheadBasis
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.proto.session.Counts
import xyz.mdhv.asom.lab.proto.session.Ev
import xyz.mdhv.asom.lab.proto.session.Fr
import xyz.mdhv.asom.lab.proto.session.Frames
import xyz.mdhv.asom.lab.proto.session.Session
import xyz.mdhv.asom.lab.proto.session.Table76
import xyz.mdhv.asom.lab.proto.trust.Scope

/** What L-L15 saw over real TLS, across every session of a run. A zero MEASURED count fails the law (non-vacuity). */
class L15Stats {
    var measuredSessions = 0
    var estimatedSessions = 0
    var mismatches = 0
    var sessionsWithUnsentClaims = 0
    var unsentBytesClaimed = 0L
    var maxEstimateDeviation = 0L
    var handshakeBytesMin = Long.MAX_VALUE
    var handshakeBytesMax = 0L
    var rowAppBytes = 0L
    var overheadBytes = 0L
    var tapBytes = 0L
    val lines = ArrayList<String>()
    val estimateLines = ArrayList<String>()

    fun add(o: L15Stats) {
        measuredSessions += o.measuredSessions
        estimatedSessions += o.estimatedSessions
        mismatches += o.mismatches
        sessionsWithUnsentClaims += o.sessionsWithUnsentClaims
        unsentBytesClaimed += o.unsentBytesClaimed
        maxEstimateDeviation = maxOf(maxEstimateDeviation, o.maxEstimateDeviation)
        handshakeBytesMin = minOf(handshakeBytesMin, o.handshakeBytesMin)
        handshakeBytesMax = maxOf(handshakeBytesMax, o.handshakeBytesMax)
        rowAppBytes += o.rowAppBytes
        overheadBytes += o.overheadBytes
        tapBytes += o.tapBytes
    }
}

/** One end of a link, as the oracle sees it. */
class Side(val kit: TNode, val conn: TappedConn, val session: Session?, private val sid: String? = null) {
    val name: String get() = kit.name
    val sessionId: String get() = sid ?: session!!.sessionId
}

/**
 * The laws of LAB_SPEC 7.6 and 7.7 over a run on real loopback TLS. It reads three independent things and trusts none of the session's own flags: the JSONL files
 * on disk, the plaintext log of every stream (what was handed to and taken from the TLS engine), and the raw bytes of the socket as counted by the record tap.
 * The frame parser and the row table (`Frames`, `Table76`) are the hand-written ones of the session track and share no code with `main`.
 */
object TlsOracle {
    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")

    private fun fail(msg: String): Nothing = throw AssertionError(msg)

    private fun key(kind: MeshKind, code: String, out: Long, inn: Long) = "$kind|$code|$out|$inn"

    private fun rk(r: LabRouteRecord) = key(r.meshKind!!, r.meshCode ?: "-", r.bytesOut, r.bytesIn ?: 0)

    fun rowsOf(e: Side): List<LabRouteRecord> = e.kit.rows().filter { it.sessionId == e.sessionId }

    /** The two ends of a link. */
    fun ends(link: TlsLink): Pair<Side, Side> =
        Side(link.world.a, link.connA!!, link.sessionA!!) to Side(link.world.b, link.connB!!, link.sessionB!!)

    // ------------------------------------------------------------------------------------------------------------ the entry point

    /**
     * Checks both ends of [link]. [strict] means no append was made to fail and nothing was reset. [verdicts] are the expected `MANIFEST_RECEIVED` verdicts
     * in order. [bGrantsA] are the scopes B granted A (for the `st` rule).
     */
    fun check(link: TlsLink, strict: Boolean, c: Counts, l15: L15Stats, verdicts: List<String> = emptyList(), bGrantsA: Set<Scope> = Scope.entries.toSet()) {
        val w = link.world
        val (a, b) = ends(link)
        structural(w, c)
        side(w, a, b, strict, c, verdicts, bGrantsA)
        side(w, b, a, strict, c, emptyList(), bGrantsA)
        if (strict) {
            l15(w, a, b.conn, l15)
            l15(w, b, a.conn, l15)
        }
    }

    /**
     * A run cut by a reset: [crashed] is "A" or "B" (its sink stopped being durable and its socket was reset, so it wrote no close row). The survivor's session is
     * checked as in a clean run (MEASURED, exact); the crashed node's overhead can only be ESTIMATED (LAB_SPEC 7.6), and is compared with what the tap saw.
     */
    fun checkReset(link: TlsLink, crashed: String, c: Counts, l15: L15Stats) {
        val w = link.world
        val (a, b) = ends(link)
        structural(w, c)
        side(w, a, b, false, c, emptyList(), Scope.entries.toSet())
        side(w, b, a, false, c, emptyList(), Scope.entries.toSet())
        val (dead, alive) = if (crashed == "A") a to b else b to a
        val peerOfDead = alive
        l15(w, alive, dead.conn, l15)
        estimated(w, dead, peerOfDead, l15)
    }

    /** ESTIMATED form (LAB_SPEC 7.6): 22 x ceilDiv(app, 16384) per frame in either direction plus 4,096 per direction, against the record tap. */
    fun estimated(w: TlsWorld, me: Side, peer: Side, into: L15Stats) {
        val events = events(w)
        val out = events.filterIsInstance<Ev.Write>().filter { it.conn == me.conn.name }.flatMap { Frames.parse(it.bytes) }.map { it.app }
        val inn = events.filterIsInstance<Ev.Write>().filter { it.conn == peer.conn.name && peer.conn.failedWrites.none { f -> f === it.bytes } }.flatMap { Frames.parse(it.bytes) }.map { it.app }
        val estimate = Overhead.estimatedRecords(out + inn) + Overhead.estimatedHandshake()
        val tap = me.conn.tap
        val real = tap.bytesRead + tap.bytesWritten - (me.conn.plaintextRead + me.conn.plaintextWritten)
        val frames = out.size + inn.size
        // the spec's 22 bytes per record undercount JSSE by TlsCalibration.recordOverhead - 22 per record, and each side's close alert; the handshake allowance of 2 x 4,096 overcounts
        val undercountAllowed = (TlsCalibration.recordOverhead - Overhead.TLS_RECORD_OVERHEAD) * frames + 2 * TlsCalibration.alertRecord
        if (estimate < real - undercountAllowed) fail("ESTIMATED overhead $estimate undercounts the tap's $real by more than the measured record excess $undercountAllowed (${me.name})")
        // the one transport artefact tolerated: on a hard reset a Windows peer may lose the last bytes it sent, so this node's tap read less than the peer wrote
        val lost = if (windows) maxOf(0L, peer.conn.tap.bytesWritten - tap.bytesRead) else 0L
        if (estimate - real > 2 * Overhead.HANDSHAKE_ESTIMATE_PER_DIRECTION + lost) fail("ESTIMATED overhead $estimate overcounts the tap's $real by more than the handshake allowance (${me.name})")
        into.estimatedSessions++
        into.maxEstimateDeviation = maxOf(into.maxEstimateDeviation, kotlin.math.abs(estimate - real))
        into.estimateLines += "estimate $estimate real $real (${me.name})"
    }

    // ------------------------------------------------------------------------------------------------------------ structural laws

    fun structuralOnly(w: TlsWorld, c: Counts) = structural(w, c)

    private fun structural(w: TlsWorld, c: Counts) {
        val events = w.log.snapshot()
        val appended = events.filterIsInstance<Ev.Appended>()
        val writes = events.filterIsInstance<Ev.Write>().filter { !it.raw }
        val offers = HashMap<Pair<String, Long>, String>()
        for (wr in writes) for (f in Frames.parse(wr.bytes)) {
            if (f.type == 0x10) offers[wr.conn to f.stream] = f.attemptId!!
            if (f.type == 0x13) {
                val id = offers[wr.conn to f.stream] ?: fail("L-L1: a body on a stream with no offer")
                val intent = appended.firstOrNull { it.node == kitOf(wr.conn) && it.row.meshKind == MeshKind.INFER_SENT && it.row.phase == Phase.INTENT && it.row.attemptId == id }
                if (intent == null || intent.seq > wr.seq) fail("L-L1: the first body byte of attempt $id was handed to TLS before its intent row was durable")
                c.law("L-L1")
            }
            if (f.type == 0x16 || f.type == 0x14) {
                val id = f.attemptId!!
                val out = appended.firstOrNull { it.node == kitOf(wr.conn) && it.row.meshKind == MeshKind.INFER_SERVED && it.row.phase == Phase.OUTCOME && it.row.attemptId == id }
                if (f.type == 0x16) {
                    if (out == null || out.seq > wr.seq) fail("L-L3: INFER_END of attempt $id was handed to TLS before the lender outcome row")
                    c.law("L-L3")
                }
                if (out != null) {
                    val r = out.row
                    if (f.type == 0x16) {
                        val terminal = Regex("\"terminal\":\"([a-z]+)\"").find(f.text)!!.groupValues[1]
                        val status = Regex("\"status\":([0-9]+)").find(f.text)!!.groupValues[1].toInt()
                        if (r.status != status || r.meshCode != (if (terminal == "done") null else terminal.uppercase())) fail("L-L12: INFER_END ($status, $terminal) and the outcome row (${r.status}, ${r.meshCode}) disagree")
                        c.law("L-L12")
                    } else if (r.servedModel != null) {
                        val model = Regex("\"servedModel\":\"([^\"]+)\"").find(f.text)!!.groupValues[1]
                        if (model != r.servedModel) fail("L-L12: INFER_HEAD names $model, the outcome row ${r.servedModel}")
                        c.law("L-L12")
                    }
                }
            }
        }
        for (e in events.filterIsInstance<Ev.EngineOpen>()) {
            val intent = appended.firstOrNull { it.node == e.node && it.row.meshKind == MeshKind.INFER_SERVED && it.row.phase == Phase.INTENT && it.row.attemptId == e.attemptId }
            if (intent == null || intent.seq > e.seq) fail("L-L2: the engine read the body of ${e.attemptId} before the lender intent row was durable")
            c.law("L-L2")
        }
        for (kit in listOf(w.a, w.b)) {
            val failedRows = events.filterIsInstance<Ev.AppendFailed>().filter { it.node == kit.name }.map { it.row }
            val logged = appended.filter { it.node == kit.name }.map { it.row }
            val onDisk = kit.rows()
            val loggedBytes = logged.map { String(it.toRowBytes(), Charsets.UTF_8) }
            val diskBytes = onDisk.map { String(it.toRowBytes(), Charsets.UTF_8) }
            if (kit.crash == null) {
                if (loggedBytes != diskBytes) fail("L-L6: the JSONL file of ${kit.name} holds ${diskBytes.size} rows, the sink appended ${loggedBytes.size}, or they differ or are out of order")
            } else {
                var at = 0
                for (r in loggedBytes) {
                    while (at < diskBytes.size && diskBytes[at] != r) at++
                    if (at >= diskBytes.size) fail("L-L6: a row that the sink reported durable is not in the JSONL file of ${kit.name}")
                    at++
                }
            }
            c.law("L-L6", onDisk.size)
            for (r in onDisk) {
                val text = String(r.toRowBytes(), Charsets.UTF_8)
                if (r.phase == Phase.INTENT && (r.bytesOut != 0L || r.bytesIn != null || r.tokensIn != null || r.tokensOut != null || r.costEst != null)) fail("L-L9: an intent row carries bytes, tokens or cost")
                if (r.phase == Phase.INTENT) c.law("L-L9")
                if (text.contains("Display Name")) fail("L-L8: a row holds a device display name")
                if (r.meshKind != MeshKind.DIAL && Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}").containsMatchIn(text)) fail("L-L8: an IP address outside a DIAL row")
                for (i in 0..text.length - 8) if (w.bodyGrams.contains(text.substring(i, i + 8))) fail("L-L8: a row holds 8 characters of a request body")
                c.law("L-L8")
            }
            if (failedRows.isNotEmpty() && kit.crash == null && !kit.dead.isDead) fail("an append failed on ${kit.name}, which had no failure plan")
        }
        for (wr in writes) for (f in Frames.parse(wr.bytes)) {
            for (id in w.requestIds) if (f.text.contains(id)) fail("L-L7: a request id is in a ${f.name} frame")
            c.law("L-L7")
        }
    }

    private fun kitOf(conn: String) = if (conn.startsWith("A")) "A" else "B"

    // ------------------------------------------------------------------------------------------------------------ L-L16 and the write-ahead order

    private fun side(w: TlsWorld, me: Side, peer: Side, strict: Boolean, c: Counts, verdicts: List<String>, bGrantsA: Set<Scope>) {
        val events = w.log.snapshot()
        val sid = me.sessionId
        val appended = events.filterIsInstance<Ev.Appended>().filter { it.node == me.name && it.row.sessionId == sid }
        val failed = events.filterIsInstance<Ev.AppendFailed>().filter { it.node == me.name && it.row.sessionId == sid }
        val writeEvs = events.filterIsInstance<Ev.Write>().filter { it.conn == me.conn.name && !it.raw }
        val sent = writeEvs.map { Frames.one(it.bytes) }
        val peerWritten = events.filterIsInstance<Ev.Write>().filter { it.conn == peer.conn.name }.flatMap { Frames.parse(it.bytes) }
        val dispatches = events.filterIsInstance<Ev.Dispatch>().filter { it.session === me.session!! }
        val recv = peerWritten.take(dispatches.size)
        c.sessions++
        for ((d, f) in dispatches.zip(recv)) {
            if (d.type != f.type || d.stream != f.stream) fail("session ${me.name}: dispatched frame ${d.type}/${d.stream} is not the peer's frame ${f.type}/${f.stream}")
        }
        val verdictIt = verdicts.iterator()

        // A: exactly one per-frame row per frame on the node that sent it and on the node that received it (L-L16)
        class Exp(val kind: MeshKind, val code: String, val out: Long, val inn: Long, val frame: Fr, val disp: Ev.Dispatch? = null)
        val expected = ArrayList<Exp>()
        for (f in sent) Table76.rowFor(f, true)?.let { expected += Exp(it.kind, it.code, f.app, 0, f) }
        for ((ri, f) in recv.withIndex()) {
            val verdict = if (f.type == 0x23 && verdictIt.hasNext()) verdictIt.next() else "VERIFIED"
            Table76.rowFor(f, false, verdict)?.let { expected += Exp(it.kind, it.code, 0, f.app, f, dispatches[ri]) }
        }
        val firstControlFailure = failed.firstOrNull { it.row.meshKind in Table76.PER_FRAME_KINDS || (it.row.meshKind == MeshKind.SESSION && it.row.meshCode in listOf("established", "pairing")) }
        val inProgress = firstControlFailure?.let { fc -> dispatches.lastOrNull { it.seq < fc.seq } }
        val actual = appended.filter { it.row.meshKind in Table76.PER_FRAME_KINDS }.toMutableList()
        val failedKeys = failed.filter { it.row.meshKind in Table76.PER_FRAME_KINDS }.map { rk(it.row) }.toMutableList()
        for (e in expected) {
            val k = key(e.kind, e.code, e.out, e.inn)
            val hit = actual.indexOfFirst { rk(it.row) == k }
            if (hit >= 0) {
                actual.removeAt(hit)
                Table76.l16Name(e.frame)?.let { c.type(it) }
                c.checks++
            } else if (!strict && (failedKeys.remove(k) || (e.disp != null && e.disp === inProgress))) {
                c.toleratedFailedRows++
            } else {
                fail("session ${me.name}/$sid: no row $k for frame ${e.frame.name} (rows: ${appended.map { rk(it.row) }})")
            }
        }
        if (actual.isNotEmpty()) fail("session ${me.name}/$sid: rows with no frame: ${actual.map { rk(it.row) }}")

        // B: durable before, sending side: the covering row is durable before the frame's first byte is handed to the TLS engine
        val usedRows = HashSet<Ev.Appended>()
        for ((idx, wr) in writeEvs.withIndex()) {
            val f = sent[idx]
            val shape = Table76.rowFor(f, true)
            val rowEv: Ev.Appended? = if (shape != null) {
                appended.firstOrNull { it !in usedRows && it.row.meshKind == shape.kind && it.row.meshCode == shape.code && it.row.bytesOut == f.app }
            } else when {
                f.type == 0x11 -> null
                f.type == 0x10 || f.type == 0x13 || f.type == 0x17 -> appended.firstOrNull { it.row.meshKind == MeshKind.INFER_SENT && it.row.phase == Phase.INTENT && it.row.attemptId == attemptOfStream(sent + recv, f.stream) }
                f.type == 0x14 || f.type == 0x15 -> appended.firstOrNull { it.row.meshKind == MeshKind.INFER_SERVED && it.row.phase == Phase.INTENT && it.row.attemptId == attemptOfStream(sent + recv, f.stream) }
                else -> appended.firstOrNull { it.row.meshKind == MeshKind.INFER_SERVED && it.row.phase == Phase.OUTCOME && it.row.attemptId == attemptOfStream(sent + recv, f.stream) }
            }
            if (f.type == 0x11) continue
            if (rowEv == null) fail("session ${me.name}: frame ${f.name} was sent with no covering row at all")
            if (rowEv.seq > wr.seq) fail("session ${me.name}: frame ${f.name} (event ${wr.seq}) was handed to the TLS engine BEFORE its row was durable (event ${rowEv.seq})")
            if (shape != null) usedRows += rowEv
            c.checks++
        }

        // C: durable before, receiving side: the row of a received frame precedes any later write of the session
        for ((d, f) in dispatches.zip(recv)) {
            val shape = Table76.rowFor(f, false, "VERIFIED") ?: continue
            val next = writeEvs.firstOrNull { it.seq > d.seq } ?: continue
            val row = appended.firstOrNull { it.seq > d.seq && it.row.meshKind == shape.kind && it.row.bytesIn == f.app && (shape.kind == MeshKind.MANIFEST_RECEIVED || it.row.meshCode == shape.code) }
            if (row == null) {
                if (strict) fail("session ${me.name}: a reply (event ${next.seq}) was handed to the TLS engine although the row of the received ${f.name} was never written")
                continue
            }
            if (row.seq > next.seq) fail("session ${me.name}: a reply (event ${next.seq}) was handed to the TLS engine BEFORE the row of the received ${f.name} (event ${row.seq})")
            c.checks++
        }

        // D: every attempt frame is covered by its attempt's rows (a CANCEL after the lender's outcome row is a late cancel: uncovered input on the close row)
        class RF(val f: Fr, val sent: Boolean, val seq: Int)
        val all = sent.mapIndexed { i, f -> RF(f, true, writeEvs[i].seq) } + recv.mapIndexed { i, f -> RF(f, false, dispatches[i].seq) }
        val byStream = all.filter { it.f.isAttemptFrame }.groupBy { it.f.stream }
        var lateBytes = 0L
        for ((_, frames0) in byStream) {
            val offer = frames0.firstOrNull { it.f.type == 0x10 } ?: continue
            val attemptId = offer.f.attemptId!!
            val rows = appended.filter { it.row.attemptId == attemptId && it.row.meshKind in listOf(MeshKind.INFER_SENT, MeshKind.INFER_SERVED) }
            val intents = rows.filter { it.row.phase == Phase.INTENT }
            val outcomes = rows.filter { it.row.phase == Phase.OUTCOME }
            val late = frames0.filter { !it.sent && it.f.type == 0x17 && outcomes.firstOrNull()?.let { o -> o.seq < it.seq } == true }
            lateBytes += late.sumOf { it.f.app }
            val frames = frames0 - late.toSet()
            val sumOut = frames.filter { it.sent }.sumOf { it.f.app }
            val sumIn = frames.filter { !it.sent }.sumOf { it.f.app }
            val failedForAttempt = failed.filter { it.row.attemptId == attemptId }
            val requester = offer.sent
            val expectIntent = if (requester) true else events.any { it is Ev.EngineOpen && it.node == me.name && it.attemptId == attemptId }
            if (strict) {
                if (intents.size != (if (expectIntent) 1 else 0)) fail("attempt $attemptId on ${me.name}: ${intents.size} intent rows, expected ${if (expectIntent) 1 else 0}")
                if (outcomes.size != 1) fail("attempt $attemptId on ${me.name}: ${outcomes.size} outcome rows, expected exactly 1")
            } else {
                if (intents.size > 1 || outcomes.size > 1) fail("attempt $attemptId on ${me.name}: more than one intent or outcome row")
                if (intents.isEmpty() && expectIntent && failedForAttempt.none { it.row.phase == Phase.INTENT }) fail("attempt $attemptId on ${me.name}: intent row missing without a failed append")
            }
            for (o in outcomes) {
                if (o.row.bytesOut != sumOut || o.row.bytesIn != sumIn) fail("attempt $attemptId on ${me.name}: outcome bytes ${o.row.bytesOut}/${o.row.bytesIn} differ from the frames $sumOut/$sumIn")
                c.checks++
            }
            val firstSentFrame = frames.firstOrNull { it.sent }
            if (requester) {
                intents.firstOrNull()?.let { if (firstSentFrame != null && it.seq > firstSentFrame.seq) fail("attempt $attemptId: INFER_OFFER was handed to TLS before the intent row was durable") }
            } else {
                val open = events.firstOrNull { it is Ev.EngineOpen && it.node == me.name && it.attemptId == attemptId }
                intents.firstOrNull()?.let { if (open != null && it.seq > open.seq) fail("attempt $attemptId: the engine opened before the lender intent row was durable") }
                for (rf in frames) {
                    if (rf.sent && (rf.f.type == 0x16 || rf.f.type == 0x12 || (rf.f.type == 0x06 && rf.f.attemptId != null))) {
                        val o = outcomes.firstOrNull() ?: continue
                        if (o.seq > rf.seq) fail("attempt $attemptId: ${rf.f.name} was handed to TLS before the lender outcome row")
                        c.checks++
                    }
                }
            }
        }
        c.lateCancelBytes += lateBytes

        // E: session rows
        val opens = appended.filter { it.row.meshKind == MeshKind.SESSION && (it.row.meshCode == "established" || it.row.meshCode == "pairing") }
        val closes = appended.filter { it.row.meshKind == MeshKind.SESSION && it.row.meshCode!!.startsWith("close") }
        if (me.conn.role.name == "TLS_SERVER") {
            if (strict && opens.size != 1) fail("session ${me.name}: ${opens.size} SESSION open rows")
            if (opens.size > 1) fail("session ${me.name}: more than one SESSION open row")
            opens.firstOrNull()?.let { o -> if (appended.any { it.seq < o.seq && it.row.meshKind != MeshKind.DIAL }) fail("session ${me.name}: a row precedes the SESSION open row") }
        } else if (opens.isNotEmpty()) fail("session ${me.name}: a dialer has no SESSION open row")
        if (me.session!!.closed) {
            if (strict && closes.size != 1) fail("session ${me.name}: ${closes.size} SESSION close rows")
            if (closes.size > 1) fail("session ${me.name}: more than one SESSION close row")
            if (closes.isEmpty() && failed.none { it.row.meshKind == MeshKind.SESSION }) fail("session ${me.name}: closed with no SESSION close row and no failed append")
            for (cl in closes) {
                if (strict && (cl.row.bytesOut != 0L || cl.row.bytesIn != lateBytes)) {
                    c.residualViolations++
                    fail("session ${me.name}: the SESSION close row carries uncovered bytes ${cl.row.bytesOut}/${cl.row.bytesIn}, expected 0/$lateBytes (late cancels)")
                }
                c.checks++
            }
        }

        // G: what must never be on the wire (LP-1), and `st` only for a peer that holds scope state
        for (f in sent) {
            if (f.text.contains("SECRET-")) fail("session ${me.name}: a presence or local-use value reached the wire in ${f.name}")
            if (f.type == 0x21) {
                val verdict = xyz.mdhv.asom.lab.policy.ProducerStrict.check(f.payload)
                if (verdict != null) fail("session ${me.name}: a STATE frame breaks the producer-strict rule: $verdict")
                c.stateFrames++
            }
            if (me.name == "B" && f.type in listOf(0x02, 0x11, 0x12, 0x16)) {
                val hasSt = f.text.contains("\"st\":")
                if (hasSt && Scope.STATE !in bGrantsA) fail("session B: st was sent to a peer without scope state (${f.name})")
                if (hasSt) c.stSent++ else if (Scope.STATE !in bGrantsA) c.stWithheld++
            }
        }
    }

    private fun attemptOfStream(frames: List<Fr>, stream: Long): String? = frames.firstOrNull { it.stream == stream && it.type == 0x10 }?.attemptId

    // ------------------------------------------------------------------------------------------------------------ L-L15

    /**
     * L-L15 over the real stack (LAB_SPEC 7.6, with R3-OVERCLAIM-3's two independent quantities):
     *  1. the rows' application bytes equal the plaintext that crossed the stream boundary, counted by the connection wrapper and not by the rows;
     *  2. the rows' application bytes plus their overhead equal the raw bytes of the socket as counted by the record tap;
     *  3. the overhead lies within the RFC 8446 record bound computed from the plaintext;
     * for every session whose rows are MEASURED. A write-ahead row may claim bytes of a frame whose write then failed (ERR-LL-11); those are subtracted
     * exactly and counted, and a clean run has none.
     */
    fun l15(w: TlsWorld, me: Side, peerConn: TappedConn, into: L15Stats) {
        val rows = rowsOf(me)
        if (rows.isEmpty()) fail("L-L15: no rows for session ${me.sessionId} on ${me.name}")
        val overheadRows = rows.filter { it.overheadBytes != null }
        if (overheadRows.any { it.overheadBasis != OverheadBasis.MEASURED }) fail("L-L15: a TLS session recorded an ESTIMATED overhead on ${me.name}")
        val appSum = rows.sumOf { it.bytesOut + (it.bytesIn ?: 0) }
        val tap = me.conn.tap
        val tapTotal = tap.bytesRead + tap.bytesWritten
        val plain = me.conn.plaintextRead + me.conn.plaintextWritten
        val unsent = me.conn.unsent
        val rawOut = events(w).filterIsInstance<Ev.Write>().filter { it.conn == me.conn.name && it.raw }.sumOf { it.bytes.size.toLong() }
        val overhead = overheadRows.sumOf { it.overheadBytes!! } - rawOut
        val tag = "session ${me.name}/${me.sessionId}"
        if (appSum - unsent + rawOut != plain) {
            into.mismatches++
            fail("L-L15: $tag: the rows count $appSum application bytes ($unsent of them claimed for writes that failed, $rawOut written outside the session), the plaintext tap saw $plain")
        }
        if (appSum - unsent + rawOut + overhead != tapTotal) {
            into.mismatches++
            fail("L-L15: $tag: rows $appSum - unsent $unsent + out-of-session $rawOut + overhead $overhead = ${appSum - unsent + rawOut + overhead}, the record tap saw $tapTotal (read ${tap.bytesRead}, written ${tap.bytesWritten}); the engine counted in ${me.conn.counters().networkBytesIn} out ${me.conn.counters().networkBytesOut}")
        }
        val flushesOut = events(w).count { it is Ev.Write && it.conn == me.conn.name }
        val flushesIn = events(w).count { it is Ev.Write && it.conn == peerConn.name }
        if (!Overhead.withinRecordBound(overhead, me.conn.plaintextWritten, me.conn.plaintextRead, flushesOut, flushesIn)) {
            into.mismatches++
            fail("L-L15: $tag: overhead $overhead is outside the RFC 8446 record bound for ${me.conn.plaintextWritten} bytes out in $flushesOut writes and ${me.conn.plaintextRead} bytes in")
        }
        val handshake = (rows.firstOrNull { it.meshKind == MeshKind.SESSION && (it.meshCode == "established" || it.meshCode == "pairing") } ?: rows.first { it.meshKind == MeshKind.DIAL && it.phase == Phase.OUTCOME }).overheadBytes!!
        into.handshakeBytesMin = minOf(into.handshakeBytesMin, handshake)
        into.handshakeBytesMax = maxOf(into.handshakeBytesMax, handshake)
        into.measuredSessions++
        if (unsent > 0) {
            into.sessionsWithUnsentClaims++
            into.unsentBytesClaimed += unsent
        }
        into.rowAppBytes += appSum - unsent
        into.overheadBytes += overhead
        into.tapBytes += tapTotal
    }

    private fun events(w: TlsWorld) = w.log.snapshot()

    /** The raw bytes one socket sent are what the other received, in both directions (a Windows peer may lose the last bytes of a hard reset, nothing else). */
    fun wireAgrees(a: Side, b: Side, resetRun: Boolean, windows: Boolean): Boolean {
        val ab = a.conn.tap.bytesWritten == b.conn.tap.bytesRead
        val ba = b.conn.tap.bytesWritten == a.conn.tap.bytesRead
        if (ab && ba) return true
        if (resetRun && windows) return a.conn.tap.bytesWritten >= b.conn.tap.bytesRead && b.conn.tap.bytesWritten >= a.conn.tap.bytesRead
        return false
    }

    val frameTypesRequired: List<String> = listOf(
        "HELLO", "HELLO_ACK", "STATE_REQ", "STATE", "MANIFEST_REQ", "MANIFEST", "GOAWAY", "ERROR:SCOPE_DENIED", "EXT_IGNORED", "REVOKE_NOTICE",
    )

    fun typeCountsLine(c: Counts): String = TreeMap(c.perType).entries.joinToString(", ") { "${it.key}=${it.value}" }
}
