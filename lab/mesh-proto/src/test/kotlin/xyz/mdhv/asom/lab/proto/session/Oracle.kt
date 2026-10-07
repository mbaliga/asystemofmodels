package xyz.mdhv.asom.lab.proto.session

import java.util.TreeMap
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.OverheadBasis
import xyz.mdhv.asom.lab.ledger.Phase

/** A frame as the oracles read it from raw bytes. This parser and `Table76` below are written from LAB_SPEC 7.1 and 7.6 and share no code with `main`. */
class Fr(val type: Int, val stream: Long, val payload: ByteArray) {
    val app: Long get() = 9L + payload.size
    val text: String get() = String(payload, Charsets.UTF_8)
    private val json: Boolean get() = type !in listOf(0x13, 0x15) && type < 0x80
    val attemptId: String? get() = if (json) Regex("\"attemptId\":\"([A-Za-z0-9_-]{22})\"").find(text)?.groupValues?.get(1) else null
    val code: String? get() = if (json) Regex("\"code\":\"([A-Z_]+)\"").find(text)?.groupValues?.get(1) else null
    val reason: String? get() = if (json) Regex("\"reason\":\"([a-z-]+)\"").find(text)?.groupValues?.get(1) else null

    val name: String
        get() = when (type) {
            0x01 -> "HELLO"; 0x02 -> "HELLO_ACK"; 0x05 -> "GOAWAY"; 0x06 -> "ERROR"; 0x10 -> "INFER_OFFER"; 0x11 -> "INFER_ACCEPT"; 0x12 -> "INFER_DECLINE"
            0x13 -> "INFER_BODY"; 0x14 -> "INFER_HEAD"; 0x15 -> "INFER_CHUNK"; 0x16 -> "INFER_END"; 0x17 -> "CANCEL"; 0x20 -> "STATE_REQ"; 0x21 -> "STATE"
            0x22 -> "MANIFEST_REQ"; 0x23 -> "MANIFEST"; 0x30 -> "PAIR_HELLO"; 0x31 -> "PAIR_CHALLENGE"; 0x32 -> "PAIR_DECISION"; 0x33 -> "PAIR_COMMIT"
            0x34 -> "PAIR_COMMIT_ACK"; 0x40 -> "REVOKE_NOTICE"
            else -> if (type >= 0x80) "EXT" else "0x%02x".format(type)
        }

    /** The frame an attempt owns: `INFER_*`, `CANCEL`, and an `ERROR` that carries an `attemptId`. */
    val isAttemptFrame: Boolean get() = type in 0x10..0x17 || (type == 0x06 && attemptId != null)
}

object Frames {
    fun parse(bytes: ByteArray): List<Fr> {
        val out = ArrayList<Fr>()
        var at = 0
        while (at < bytes.size) {
            require(bytes.size - at >= 9) { "a partial frame in a recorded write" }
            val len = ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)
            val type = bytes[at + 4].toInt() and 0xFF
            val stream = ((bytes[at + 5].toLong() and 0xFF) shl 24) or ((bytes[at + 6].toLong() and 0xFF) shl 16) or ((bytes[at + 7].toLong() and 0xFF) shl 8) or (bytes[at + 8].toLong() and 0xFF)
            val end = at + 4 + len.toInt()
            require(end <= bytes.size) { "a frame cut off inside a recorded write" }
            out += Fr(type, stream, bytes.copyOfRange(at + 9, end))
            at = end
        }
        return out
    }

    fun one(bytes: ByteArray): Fr = parse(bytes).single()

    /** The complete frames at the start of [bytes]; a partial frame at the end is left out (reads can cut a frame anywhere). */
    fun parsePrefix(bytes: ByteArray): List<Fr> {
        var end = 0
        while (bytes.size - end >= 9) {
            val len = ((bytes[end].toLong() and 0xFF) shl 24) or ((bytes[end + 1].toLong() and 0xFF) shl 16) or ((bytes[end + 2].toLong() and 0xFF) shl 8) or (bytes[end + 3].toLong() and 0xFF)
            if (end + 4 + len > bytes.size) break
            end += (4 + len).toInt()
        }
        return parse(bytes.copyOf(end))
    }

    fun encode(type: Int, stream: Long, payload: ByteArray): ByteArray {
        val len = 5L + payload.size
        val out = ByteArray(9 + payload.size)
        for (k in 0..3) out[k] = (len ushr (24 - 8 * k)).toByte()
        out[4] = type.toByte()
        for (k in 0..3) out[5 + k] = (stream ushr (24 - 8 * k)).toByte()
        payload.copyInto(out, 9)
        return out
    }

    fun encode(type: Int, stream: Long, json: String): ByteArray = encode(type, stream, json.toByteArray(Charsets.UTF_8))
}

/** LAB_SPEC 7.6, written out by hand: which frame produces which per-frame row. */
object Table76 {
    class Row(val kind: MeshKind, val code: String)

    val PER_FRAME_KINDS = setOf(MeshKind.CONTROL, MeshKind.MANIFEST_SENT, MeshKind.MANIFEST_RECEIVED, MeshKind.PAIRING, MeshKind.REVOCATION)

    fun rowFor(f: Fr, sent: Boolean, verdict: String = "VERIFIED"): Row? = when {
        f.type >= 0x80 -> if (sent) error("mesh-1 senders never send an extension frame") else Row(MeshKind.CONTROL, "EXT_IGNORED")
        f.isAttemptFrame -> null
        f.type == 0x05 -> Row(MeshKind.CONTROL, "GOAWAY:" + f.reason)
        f.type == 0x06 -> Row(MeshKind.CONTROL, "ERROR:" + f.code)
        f.type == 0x23 -> if (sent) Row(MeshKind.MANIFEST_SENT, "MANIFEST") else Row(MeshKind.MANIFEST_RECEIVED, verdict)
        f.type == 0x40 -> Row(MeshKind.REVOCATION, "REVOKE_NOTICE")
        f.type in 0x30..0x34 -> Row(MeshKind.PAIRING, f.name)
        f.type in listOf(0x01, 0x02, 0x20, 0x21, 0x22) -> Row(MeshKind.CONTROL, f.name)
        else -> error("no row rule for frame type ${f.type}")
    }

    /** The L-L16 type name of a frame, or null for an attempt frame. */
    fun l16Name(f: Fr): String? = when {
        f.type >= 0x80 -> "EXT_IGNORED"
        f.isAttemptFrame -> null
        else -> f.name
    }
}

class Counts {
    var checks = 0
    var toleratedFailedRows = 0
    var sessions = 0
    var measuredSessions = 0
    val perType = TreeMap<String, Int>()
    var residualViolations = 0
    var lateCancelBytes = 0L
    var stateFrames = 0
    val structural = TreeMap<String, Int>()

    fun law(name: String, by: Int = 1) {
        structural.merge(name, by, Int::plus)
    }
    var stSent = 0
    var stWithheld = 0

    fun type(name: String) {
        perType.merge(name, 1, Int::plus)
    }

    fun add(o: Counts) {
        checks += o.checks
        toleratedFailedRows += o.toleratedFailedRows
        sessions += o.sessions
        measuredSessions += o.measuredSessions
        stateFrames += o.stateFrames
        o.structural.forEach { (k, v) -> structural.merge(k, v, Int::plus) }
        stSent += o.stSent
        stWithheld += o.stWithheld
        lateCancelBytes += o.lateCancelBytes
        o.perType.forEach { (k, v) -> perType.merge(k, v, Int::plus) }
    }
}

/** Checks one world's rows against LAB_SPEC 7.6 and the write-ahead rules, using only the recorded bytes and events. [strict] means no append was made to fail. */
class RunOracle(private val log: Log, private val world: World, private val strict: Boolean, private val verdicts: List<String> = emptyList()) {
    private fun fail(msg: String): Nothing = throw AssertionError(msg)

    fun checkAll(): Counts {
        val c = Counts()
        var i = 0
        while (i + 1 < world.links.size) {
            checkSide(world.links[i], world.links[i + 1], c)
            checkSide(world.links[i + 1], world.links[i], c)
            i += 2
        }
        structural(c)
        return c
    }

    private fun kitOf(conn: String) = if (conn.startsWith("A")) "A" else "B"

    /** The structural laws that need no real TLS: L-L1, L-L2, L-L3, L-L6, L-L7, L-L8, L-L9 and L-L12, over the recorded writes, rows and engine events. */
    private fun structural(c: Counts) {
        val events = log.snapshot()
        val appended = events.filterIsInstance<Ev.Appended>()
        val writes = events.filterIsInstance<Ev.Write>().filter { !it.raw }
        val offers = HashMap<Pair<String, Long>, String>()
        for (w in writes) for (f in Frames.parse(w.bytes)) {
            if (f.type == 0x10) offers[w.conn to f.stream] = f.attemptId!!
            if (f.type == 0x13) {
                val id = offers[w.conn to f.stream] ?: fail("L-L1: a body on a stream with no offer")
                val intent = appended.firstOrNull { it.node == kitOf(w.conn) && it.row.meshKind == MeshKind.INFER_SENT && it.row.phase == Phase.INTENT && it.row.attemptId == id }
                if (intent == null || intent.seq > w.seq) fail("L-L1: the first body byte of attempt $id was written before its intent row was durable")
                c.law("L-L1")
            }
            if (f.type == 0x16 || f.type == 0x14) {
                val id = f.attemptId!!
                val out = appended.firstOrNull { it.node == kitOf(w.conn) && it.row.meshKind == MeshKind.INFER_SERVED && it.row.phase == Phase.OUTCOME && it.row.attemptId == id }
                if (f.type == 0x16) {
                    if (out == null || out.seq > w.seq) fail("L-L3: INFER_END of attempt $id was written before the lender outcome row")
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
        for (kit in listOf(world.a, world.b)) {
            val failedRows = events.filterIsInstance<Ev.AppendFailed>().filter { it.node == kit.name }.map { it.row }
            val rows = kit.rows().filter { r -> failedRows.none { it === r } }
            val logged = appended.filter { it.node == kit.name }.map { it.row }
            if (rows.size != logged.size || rows.zip(logged).any { (x, y) -> x !== y }) fail("L-L6: the ledger of ${kit.name} holds rows that were not appended in order, or lost one")
            c.law("L-L6", rows.size)
            for (r in rows) {
                val text = String(r.toRowBytes(), Charsets.UTF_8)
                if (r.phase == Phase.INTENT && (r.bytesOut != 0L || r.bytesIn != null || r.tokensIn != null || r.tokensOut != null || r.costEst != null)) fail("L-L9: an intent row carries bytes, tokens or cost")
                if (r.phase == Phase.INTENT) c.law("L-L9")
                if (text.contains("Display Name")) fail("L-L8: a row holds a device display name")
                if (r.meshKind != MeshKind.DIAL && Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}").containsMatchIn(text)) fail("L-L8: an IP address outside a DIAL row")
                for (w in 0..text.length - 8) if (world.bodyGrams.contains(text.substring(w, w + 8))) fail("L-L8: a row holds 8 characters of a request body")
                c.law("L-L8")
            }
        }
        for (w in writes) for (f in Frames.parse(w.bytes)) {
            for (id in world.requestIds) if (f.text.contains(id)) fail("L-L7: a request id is in a ${f.name} frame")
            c.law("L-L7")
        }
    }

    private fun key(kind: MeshKind, code: String, out: Long, inn: Long) = "$kind|$code|$out|$inn"

    private fun checkSide(me: Link, peer: Link, c: Counts) {
        val events = log.snapshot()
        val sid = me.session.sessionId
        val appended = events.filterIsInstance<Ev.Appended>().filter { it.node == me.kit && it.row.sessionId == sid }
        val failed = events.filterIsInstance<Ev.AppendFailed>().filter { it.node == me.kit && it.row.sessionId == sid }
        val writeEvs = events.filterIsInstance<Ev.Write>().filter { it.conn == me.conn.name && !it.raw }
        val sent = writeEvs.map { Frames.one(it.bytes) }
        val peerWritten = events.filterIsInstance<Ev.Write>().filter { it.conn == peer.conn.name }.flatMap { Frames.parse(it.bytes) }
        val dispatches = events.filterIsInstance<Ev.Dispatch>().filter { it.session === me.session }
        val recv = peerWritten.take(dispatches.size)
        c.sessions++
        for ((d, f) in dispatches.zip(recv)) {
            if (d.type != f.type || d.stream != f.stream) fail("session ${me.kit}: dispatched frame ${d.type}/${d.stream} is not the peer's frame ${f.type}/${f.stream}")
        }
        val verdictIt = verdicts.iterator()

        // ---- A: one per-frame row per frame, on the node that sent it and on the node that received it (L-L16)
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
        val failedKeys = failed.filter { it.row.meshKind in Table76.PER_FRAME_KINDS }.map { key(it.row.meshKind!!, it.row.meshCode!!, it.row.bytesOut, it.row.bytesIn ?: 0) }.toMutableList()
        for (e in expected) {
            val k = key(e.kind, e.code, e.out, e.inn)
            val hit = actual.indexOfFirst { key(it.row.meshKind!!, it.row.meshCode!!, it.row.bytesOut, it.row.bytesIn ?: 0) == k }
            if (hit >= 0) {
                actual.removeAt(hit)
                Table76.l16Name(e.frame)?.let { c.type(it) }
                c.checks++
            } else if (!strict && (failedKeys.remove(k) || (e.disp != null && e.disp === inProgress))) {
                c.toleratedFailedRows++
            } else {
                fail("session ${me.kit}/$sid: no row $k for frame ${e.frame.name} (rows: ${appended.map { key(it.row.meshKind!!, it.row.meshCode ?: "-", it.row.bytesOut, it.row.bytesIn ?: 0) }})")
            }
        }
        if (actual.isNotEmpty()) fail("session ${me.kit}/$sid: rows with no frame: ${actual.map { key(it.row.meshKind!!, it.row.meshCode!!, it.row.bytesOut, it.row.bytesIn ?: 0) }}")

        // ---- B: durable before (sending side): the covering row is in the ledger before the frame's first byte is handed over
        val usedRows = HashSet<Ev.Appended>()
        for ((idx, w) in writeEvs.withIndex()) {
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
            if (rowEv == null) fail("session ${me.kit}: frame ${f.name} was sent with no covering row at all")
            if (rowEv.seq > w.seq) fail("session ${me.kit}: frame ${f.name} (event ${w.seq}) was handed to the transport BEFORE its row (event ${rowEv.seq})")
            if (shape != null) usedRows += rowEv
            c.checks++
        }

        // ---- C: durable before (receiving side): the row of a received frame precedes any later write of the session
        val myWrites = writeEvs
        for ((d, f) in dispatches.zip(recv)) {
            val shape = Table76.rowFor(f, false, "VERIFIED") ?: continue
            val next = myWrites.firstOrNull { it.seq > d.seq } ?: continue
            val row = appended.firstOrNull { it.seq > d.seq && it.row.meshKind == shape.kind && it.row.bytesIn == f.app && (shape.kind == MeshKind.MANIFEST_RECEIVED || it.row.meshCode == shape.code) }
            if (row == null) {
                if (strict) fail("session ${me.kit}: a reply (event ${next.seq}) was sent although the row of the received ${f.name} was never written")
                continue
            }
            if (row.seq > next.seq) fail("session ${me.kit}: a reply (event ${next.seq}) was sent BEFORE the row of the received ${f.name} (event ${row.seq})")
            c.checks++
        }

        // ---- D: attempt frames are covered by their attempt's rows (a CANCEL that arrives after the lender's outcome row is a late cancel: uncovered input)
        class RF(val f: Fr, val sent: Boolean, val seq: Int)
        val all = sent.mapIndexed { i, f -> RF(f, true, writeEvs[i].seq) } + recv.mapIndexed { i, f -> RF(f, false, dispatches[i].seq) }
        val byStream = all.filter { it.f.isAttemptFrame }.groupBy { it.f.stream }
        var lateBytes = 0L
        for ((stream, frames0) in byStream) {
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
            val expectIntent = if (requester) true else events.any { it is Ev.EngineOpen && it.node == me.kit && it.attemptId == attemptId }
            if (strict) {
                if (intents.size != (if (expectIntent) 1 else 0)) fail("attempt $attemptId on ${me.kit}: ${intents.size} intent rows, expected ${if (expectIntent) 1 else 0}")
                if (outcomes.size != 1) fail("attempt $attemptId on ${me.kit}: ${outcomes.size} outcome rows, expected exactly 1")
            } else {
                if (intents.size > 1 || outcomes.size > 1) fail("attempt $attemptId on ${me.kit}: more than one intent or outcome row")
                if (intents.isEmpty() && expectIntent && failedForAttempt.none { it.row.phase == Phase.INTENT }) fail("attempt $attemptId on ${me.kit}: intent row missing without a failed append")
            }
            for (o in outcomes) {
                if (o.row.bytesOut != sumOut || o.row.bytesIn != sumIn) fail("attempt $attemptId on ${me.kit}: outcome bytes ${o.row.bytesOut}/${o.row.bytesIn} differ from the frames ${sumOut}/${sumIn}")
                c.checks++
            }
            val firstSentFrame = frames.firstOrNull { it.sent }
            if (requester) {
                intents.firstOrNull()?.let { if (firstSentFrame != null && it.seq > firstSentFrame.seq) fail("attempt $attemptId: INFER_OFFER left before the intent row was durable") }
            } else {
                val open = events.firstOrNull { it is Ev.EngineOpen && it.node == me.kit && it.attemptId == attemptId }
                intents.firstOrNull()?.let { if (open != null && it.seq > open.seq) fail("attempt $attemptId: the engine opened before the lender intent row was durable") }
                for (rf in frames) {
                    if (rf.sent && (rf.f.type == 0x16 || rf.f.type == 0x12 || (rf.f.type == 0x06 && rf.f.attemptId != null))) {
                        val o = outcomes.firstOrNull() ?: continue
                        if (o.seq > rf.seq) fail("attempt $attemptId: ${rf.f.name} was sent before the lender outcome row")
                        c.checks++
                    }
                }
                val declined = frames.any { it.sent && (it.f.type == 0x12 || it.f.type == 0x06) } && frames.none { it.f.type == 0x13 }
                if (declined && strict && intents.isNotEmpty()) fail("attempt $attemptId: a decline has an intent row")
            }
        }
        c.lateCancelBytes += lateBytes

        // ---- E: session rows
        val opens = appended.filter { it.row.meshKind == MeshKind.SESSION && (it.row.meshCode == "established" || it.row.meshCode == "pairing") }
        val closes = appended.filter { it.row.meshKind == MeshKind.SESSION && it.row.meshCode!!.startsWith("close") }
        if (me.conn.role.name == "TLS_SERVER") {
            if (strict && opens.size != 1) fail("session ${me.kit}: ${opens.size} SESSION open rows")
            if (opens.size > 1) fail("session ${me.kit}: more than one SESSION open row")
            opens.firstOrNull()?.let { o -> if (appended.any { it.seq < o.seq && it.row.meshKind != MeshKind.DIAL }) fail("session ${me.kit}: a row precedes the SESSION open row") }
        } else if (opens.isNotEmpty()) fail("session ${me.kit}: a dialer has no SESSION open row")
        if (me.session.closed) {
            if (strict && closes.size != 1) fail("session ${me.kit}: ${closes.size} SESSION close rows")
            if (closes.size > 1) fail("session ${me.kit}: more than one SESSION close row")
            if (closes.isEmpty() && failed.none { it.row.meshKind == MeshKind.SESSION }) fail("session ${me.kit}: closed with no SESSION close row and no failed append")
            for (cl in closes) {
                if (strict && (cl.row.bytesOut != 0L || cl.row.bytesIn != lateBytes)) {
                    c.residualViolations++
                    fail("session ${me.kit}: the SESSION close row carries uncovered bytes ${cl.row.bytesOut}/${cl.row.bytesIn}, expected 0/$lateBytes (late cancels)")
                }
                c.checks++
            }
        }

        // ---- G: what must never be on the wire (LP-1), and `st` only for a peer that holds scope state
        for (f in sent) {
            if (f.text.contains("SECRET-")) fail("session ${me.kit}: a presence or local-use value reached the wire in ${f.name}")
            if (f.type == 0x21) {
                val verdict = xyz.mdhv.asom.lab.policy.ProducerStrict.check(f.payload)
                if (verdict != null) fail("session ${me.kit}: a STATE frame breaks the producer-strict rule: $verdict")
                c.stateFrames++
            }
            if (me.kit == "B" && f.type in listOf(0x02, 0x11, 0x12, 0x16)) {
                val hasSt = f.text.contains("\"st\":")
                if (hasSt && xyz.mdhv.asom.lab.proto.trust.Scope.STATE !in world.bGrantsA) fail("session B: st was sent to a peer without scope state (${f.name})")
                if (hasSt) c.stSent++ else if (xyz.mdhv.asom.lab.proto.trust.Scope.STATE !in world.bGrantsA) c.stWithheld++
            }
        }

        // ---- F: transport accounting (L-L15), from independent figures
        val wroteOutsideTheSession = events.any { it is Ev.Write && it.conn == me.conn.name && it.raw }
        if (strict && me.session.closed && me.conn.counters().measured && !wroteOutsideTheSession) {
            val plaintext = writeEvs.sumOf { it.bytes.size.toLong() } + events.filterIsInstance<Ev.Read>().filter { it.conn == me.conn.name }.sumOf { it.bytes.size.toLong() }
            val rowBytes = appended.sumOf { it.row.bytesOut + (it.row.bytesIn ?: 0) }
            if (rowBytes != plaintext) fail("session ${me.kit}: rows count $rowBytes application bytes, the plaintext tap saw $plaintext")
            val overheadRows = appended.mapNotNull { it.row.overheadBytes }.sum()
            val myWrites2 = events.filterIsInstance<Ev.Write>().filter { it.conn == me.conn.name }
            val readTotal = events.filterIsInstance<Ev.Read>().filter { it.conn == me.conn.name }.sumOf { it.bytes.size.toLong() }
            var cum = 0L
            var inRecords = 0L
            for (w in events.filterIsInstance<Ev.Write>().filter { it.conn == peer.conn.name }) {
                if (cum >= readTotal) break
                inRecords += 22L * ((w.bytes.size + 16_383L) / 16_384L)
                cum += w.bytes.size
            }
            val expectedOverhead = me.conn.handshakeIn + me.conn.handshakeOut + myWrites2.sumOf { 22L * ((it.bytes.size + 16_383L) / 16_384L) } + inRecords
            if (overheadRows != expectedOverhead) fail("session ${me.kit}: overhead rows sum to $overheadRows, RFC 8446 arithmetic gives $expectedOverhead")
            if (overheadRows + rowBytes != me.conn.networkTotal()) fail("session ${me.kit}: rows + overhead ${overheadRows + rowBytes} differ from the network tap ${me.conn.networkTotal()}")
            if (appended.any { it.row.overheadBytes != null && it.row.overheadBasis != OverheadBasis.MEASURED }) fail("session ${me.kit}: a measured connection recorded an ESTIMATED overhead")
            c.measuredSessions++
            c.checks++
        }
    }

    private fun attemptOfStream(frames: List<Fr>, stream: Long): String? = frames.firstOrNull { it.stream == stream && it.type == 0x10 }?.attemptId

    companion object {
        fun rowsOf(w: World, kit: String): List<LabRouteRecord> = if (kit == "A") w.a.rows() else w.b.rows()
    }
}
