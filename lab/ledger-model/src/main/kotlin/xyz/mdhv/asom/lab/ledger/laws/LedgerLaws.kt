package xyz.mdhv.asom.lab.ledger.laws

import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.ledger.FrameKind
import xyz.mdhv.asom.lab.ledger.FrameRows
import xyz.mdhv.asom.lab.ledger.LabEgress
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.LedgerClass
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Overhead
import xyz.mdhv.asom.lab.ledger.OverheadBasis
import xyz.mdhv.asom.lab.ledger.Phase

class LawResult(val law: String, val cases: Int, val violations: List<String>, val detail: Map<String, Int> = emptyMap()) {
    val ok: Boolean get() = violations.isEmpty()

    operator fun plus(o: LawResult): LawResult {
        require(law == o.law)
        val d = HashMap(detail)
        o.detail.forEach { (k, v) -> d[k] = (d[k] ?: 0) + v }
        return LawResult(law, cases + o.cases, violations + o.violations, d)
    }
}

/**
 * The ledger laws L-L1..L-L16 (contract.md 4.12, LAB_SPEC 7.7) as pure oracles over a [Trace]. Each one returns the number of cases that exercised it
 * (non-vacuity, R10) and every violation it found. None of them reads the ledger implementation's own counters: they read rows only as
 * data, and compare them with what the wire, the socket tap and the scripted application recorded.
 */
object LedgerLaws {
    private fun rows(t: List<Ev>): List<Pair<Int, Appended>> = t.withIndex().filter { it.value is Appended }.map { it.index to it.value as Appended }

    private data class Key(val node: String, val kind: MeshKind?, val phase: Phase?, val attemptId: String?)

    private fun firstAppended(t: List<Ev>): Map<Key, Int> {
        val m = HashMap<Key, Int>()
        for ((i, a) in rows(t)) m.putIfAbsent(Key(a.node, a.row.meshKind, a.row.phase, a.row.attemptId), i)
        return m
    }

    /** L-L1: an intent row with the attempt's id was committed before the first byte of the attempt was handed to the wire (offer and body alike). */
    fun l1(t: List<Ev>): LawResult {
        val first = firstAppended(t)
        val requester = HashMap<String, String>()
        t.forEach { if (it is Sent && it.frame.kind == FrameKind.INFER_OFFER) requester.putIfAbsent(it.frame.attemptId!!, it.node) }
        val v = mutableListOf<String>()
        var cases = 0
        val seen = HashSet<String>()
        for ((i, e) in t.withIndex()) {
            if (e !is Sent) continue
            val a = e.frame.attemptId ?: continue
            if (requester[a] != e.node || !e.frame.kind.isAttemptFrame) continue
            cases++
            val intent = first[Key(e.node, MeshKind.INFER_SENT, Phase.INTENT, a)]
            if (intent == null || intent > i) v += "attempt $a: ${e.frame.kind} was sent before its intent row was durable"
            seen += a
        }
        return LawResult("L-L1", cases, v, mapOf("attempts" to seen.size))
    }

    /** L-L2: a lender intent row was committed before the engine read the body. */
    fun l2(t: List<Ev>): LawResult {
        val first = firstAppended(t)
        val v = mutableListOf<String>()
        var cases = 0
        for ((i, e) in t.withIndex()) {
            if (e !is EngineRead) continue
            cases++
            val intent = first[Key(e.node, MeshKind.INFER_SERVED, Phase.INTENT, e.attemptId)]
            if (intent == null || intent > i) v += "attempt ${e.attemptId}: the engine read the body before the lender intent row was durable"
        }
        return LawResult("L-L2", cases, v)
    }

    /** L-L3: the lender's outcome row was committed before `INFER_END` was written. */
    fun l3(t: List<Ev>): LawResult {
        val first = firstAppended(t)
        val v = mutableListOf<String>()
        var cases = 0
        for ((i, e) in t.withIndex()) {
            if (e !is Sent || e.frame.kind != FrameKind.INFER_END) continue
            cases++
            val out = first[Key(e.node, MeshKind.INFER_SERVED, Phase.OUTCOME, e.frame.attemptId)]
            if (out == null || out > i) v += "attempt ${e.frame.attemptId}: INFER_END was written before the lender outcome row was durable"
        }
        return LawResult("L-L3", cases, v)
    }

    /** L-L4: every row with `bytesOut > 0` names the class of the destination that received those bytes. */
    fun l4(t: List<Ev>): LawResult {
        val v = mutableListOf<String>()
        var cases = 0
        val contentByRequest = t.filterIsInstance<ContentSent>().groupBy { it.requestId }
        for ((_, a) in rows(t)) {
            val r = a.row
            if (r.bytesOut <= 0) continue
            cases++
            if (r.meshKind != null) {
                if (r.egress != LabEgress.peerClass) v += "row ${r.meshKind}/${r.meshCode}: bytesOut ${r.bytesOut} to a peer but egress is ${r.egress.wire}"
            } else if (r.requestId != null) {
                val classes = contentByRequest[r.requestId].orEmpty().map { it.cls }
                if (r.egress != LabEgress.reachOf(classes)) v += "request ${r.requestId}: bytesOut ${r.bytesOut} with egress ${r.egress.wire}, content went to ${classes.map { it.wire }}"
            }
        }
        return LawResult("L-L4", cases, v)
    }

    /** L-L5: for every request the echo header equals the furthest class that received content (local < peer < cloud), or local when none did. */
    fun l5(t: List<Ev>): LawResult {
        val v = mutableListOf<String>()
        var cases = 0
        val content = t.filterIsInstance<ContentSent>().groupBy { it.requestId }
        for (e in t) {
            if (e !is Responded) continue
            cases++
            val want = LabEgress.reachOf(content[e.requestId].orEmpty().map { it.cls })
            val got = e.headers[AsomHeaders.EGRESS]
            if (got != want.wire) v += "request ${e.requestId}: X-Asom-Egress '$got' but content reached ${want.wire}"
        }
        return LawResult("L-L5", cases, v)
    }

    /** L-L5b: exactly one terminal row per request; its egress equals the header; `servedClass` is the serving attempt's class. */
    fun l5b(t: List<Ev>): LawResult {
        val v = mutableListOf<String>()
        var cases = 0
        val terminals = rows(t).map { it.second }.filter { it.row.terminal == true }.groupBy { it.row.requestId }
        val headers = t.filterIsInstance<Responded>().associate { it.requestId to it.headers }
        for (e in t) {
            if (e !is ServedBy) continue
            cases++
            val ts = terminals[e.requestId].orEmpty()
            if (ts.size != 1) {
                v += "request ${e.requestId}: ${ts.size} terminal rows"
                continue
            }
            val row = ts.single().row
            if (row.egress.wire != headers[e.requestId]?.get(AsomHeaders.EGRESS)) v += "request ${e.requestId}: terminal egress ${row.egress.wire} != header ${headers[e.requestId]?.get(AsomHeaders.EGRESS)}"
            if (row.servedClass != e.cls) v += "request ${e.requestId}: servedClass ${row.servedClass?.wire} but the serving attempt was ${e.cls?.wire}"
            if (row.reach != row.egress) v += "request ${e.requestId}: reach ${row.reach?.wire} != egress ${row.egress.wire}"
        }
        return LawResult("L-L5b", cases, v)
    }

    /** L-L6, file form: the file after an append still starts with every byte it held before. */
    fun l6PrefixPreserved(before: ByteArray, after: ByteArray): LawResult {
        val ok = after.size >= before.size && before.indices.all { before[it] == after[it] }
        return LawResult("L-L6", 1, if (ok) emptyList() else listOf("a byte written earlier changed or was removed"))
    }

    /** L-L7: no request id appears in any encoded frame payload. */
    fun l7(t: List<Ev>): LawResult {
        val ids = (t.filterIsInstance<ContentSent>().map { it.requestId } + rows(t).mapNotNull { it.second.row.requestId }).toSet()
        val v = mutableListOf<String>()
        var cases = 0
        if (ids.isNotEmpty()) {
            for (e in t) {
                if (e !is Sent || e.payloadText == null) continue
                cases++
                ids.filter { e.payloadText.contains(it) }.forEach { v += "${e.frame.kind} payload contains the request id $it" }
            }
        }
        return LawResult("L-L7", cases, v)
    }

    private val ipv4 = Regex("(?<![0-9A-Za-z])[0-9]{1,3}(\\.[0-9]{1,3}){3}(?![0-9A-Za-z])")
    private val ipv6 = Regex("(?<![0-9A-Za-z])([0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Za-z])")

    /** L-L8: no row holds a length-8 substring of a request body, a key, an IP address outside DIAL rows, or a device display name. */
    fun l8(t: List<Ev>): LawResult {
        val secrets = t.filterIsInstance<Secrets>()
        val grams = HashSet<String>()
        secrets.flatMap { it.bodies }.forEach { b -> for (i in 0..b.length - 8) grams += b.substring(i, i + 8) }
        val keys = secrets.flatMap { it.keys }
        val names = secrets.flatMap { it.displayNames }
        val v = mutableListOf<String>()
        var cases = 0
        for ((_, a) in rows(t)) {
            val text = String(a.row.toRowBytes(), Charsets.UTF_8)
            cases++
            for (i in 0..text.length - 8) if (text.substring(i, i + 8) in grams) {
                v += "a row holds the body fragment '${text.substring(i, i + 8)}'"
                break
            }
            keys.filter { text.contains(it) }.forEach { v += "a row holds the key $it" }
            names.filter { text.contains(it) }.forEach { v += "a row holds the device name $it" }
            val stripped = if (a.row.meshKind == MeshKind.DIAL) text.replace(a.row.destAddr!!, "") else text
            if (ipv4.containsMatchIn(stripped) || ipv6.containsMatchIn(stripped)) v += "a ${a.row.meshKind} row holds an IP address outside a DIAL row's destAddr"
        }
        return LawResult("L-L8", cases, v)
    }

    /** L-L9: summing bytes, tokens and cost over all rows equals summing over the rows that are not intent rows: intent rows carry nothing. */
    fun l9(t: List<Ev>): LawResult {
        val all = rows(t).map { it.second.row }
        fun sums(rs: List<LabRouteRecord>) = listOf(
            rs.sumOf { it.bytesOut }, rs.sumOf { it.bytesIn ?: 0 }, rs.sumOf { it.tokensIn ?: 0 }, rs.sumOf { it.tokensOut ?: 0 },
            rs.sumOf { (it.costEst ?: 0.0) * 1e9 }.toLong(),
        )
        val v = mutableListOf<String>()
        if (sums(all) != sums(all.filter { it.phase != Phase.INTENT })) v += "an intent row carries bytes, tokens or cost"
        return LawResult("L-L9", all.count { it.phase == Phase.INTENT } + all.count { it.phase == Phase.OUTCOME }, v)
    }

    /** L-L10: `peerPath` equals the kind of the socket's local interface in the simulated network. */
    fun l10(t: List<Ev>): LawResult {
        val facts = t.filterIsInstance<ConnFacts>().associateBy { it.node to it.sessionId }
        val v = mutableListOf<String>()
        var cases = 0
        for ((_, a) in rows(t)) {
            val f = facts[a.node to a.row.sessionId] ?: continue
            if (a.row.meshKind == MeshKind.DIAL && a.row.phase == Phase.INTENT) continue
            cases++
            if (a.row.peerPath != f.expectedPath) v += "${a.row.meshKind}/${a.row.meshCode}: peerPath ${a.row.peerPath} but the interface gives ${f.expectedPath}"
        }
        return LawResult("L-L10", cases, v)
    }

    /** L-L12: `INFER_HEAD` and `INFER_END` agree field by field with the lender's outcome row. */
    fun l12(t: List<Ev>): LawResult {
        val out = rows(t).map { it.second }.filter { it.row.meshKind == MeshKind.INFER_SERVED && it.row.phase == Phase.OUTCOME }.associateBy { it.node to it.row.attemptId }
        val v = mutableListOf<String>()
        var cases = 0
        fun obj(text: String?): JObject? {
            if (text == null) return null
            return (StrictJson.parse(text.toByteArray()) as? ParseResult.Ok)?.value as? JObject
        }
        for (e in t) {
            if (e !is Sent || e.frame.kind !in setOf(FrameKind.INFER_HEAD, FrameKind.INFER_END)) continue
            val row = out[e.node to e.frame.attemptId]?.row ?: continue
            val p = obj(e.payloadText) ?: run { v += "${e.frame.kind}: payload does not parse"; null } ?: continue
            cases++
            val status = (p["status"] as? JInt)?.value?.toInt()
            if (status != row.status) v += "${e.frame.kind}: status $status but the row says ${row.status}"
            if (e.frame.kind == FrameKind.INFER_HEAD && (p["servedModel"] as? JString)?.value != row.servedModel) v += "INFER_HEAD servedModel differs from the row"
            if (e.frame.kind == FrameKind.INFER_END) {
                val terminal = (p["terminal"] as? JString)?.value
                val want = if (terminal == "done") null else terminal?.uppercase()
                if (want != row.meshCode) v += "INFER_END terminal '$terminal' but the row's meshCode is ${row.meshCode}"
            }
        }
        return LawResult("L-L12", cases, v)
    }

    /**
     * L-L13: after an append fails, nothing that depends on that row reaches the wire: no byte of an attempt whose intent failed, no frame
     * of any kind on a session after a control-row failure, no engine read after a lender intent failure, no INFER_END or INFER_DECLINE
     * after the outcome row that must precede it failed, no content frame on the session at all, and no SYN after a DIAL intent failure.
     */
    fun l13(t: List<Ev>): LawResult {
        val v = mutableListOf<String>()
        var cases = 0
        for ((i, e) in t.withIndex()) {
            if (e !is AppendFailed) continue
            cases++
            val r = e.row
            val after = t.subList(i + 1, t.size)
            fun scope(desc: String, bad: (Ev) -> Boolean) {
                after.filter(bad).forEach { v += "$desc: ${it::class.simpleName} after the failure at #$i" }
            }
            when {
                r.meshKind == MeshKind.DIAL && r.phase == Phase.INTENT -> scope("DIAL intent failed") { it is Syn && it.node == e.node && it.sessionId == r.sessionId }
                r.meshKind == MeshKind.INFER_SENT && r.phase == Phase.INTENT ->
                    scope("requester intent failed") { it is Sent && it.node == e.node && it.frame.attemptId == r.attemptId }
                r.meshKind == MeshKind.INFER_SERVED && r.phase == Phase.INTENT ->
                    scope("lender intent failed") { it is EngineRead && it.node == e.node && it.attemptId == r.attemptId }
                r.meshKind == MeshKind.INFER_SERVED && r.phase == Phase.OUTCOME ->
                    scope("lender outcome failed") { it is Sent && it.node == e.node && it.frame.attemptId == r.attemptId && it.frame.kind in setOf(FrameKind.INFER_END, FrameKind.INFER_DECLINE) }
                r.sessionId != null && r.meshKind in setOf(MeshKind.CONTROL, MeshKind.MANIFEST_SENT, MeshKind.MANIFEST_RECEIVED, MeshKind.PAIRING, MeshKind.REVOCATION, MeshKind.SESSION) &&
                    r.meshCode?.startsWith("close") != true ->
                    scope("control row failed") { it is Sent && it.node == e.node && it.sessionId == r.sessionId }
            }
        }
        return LawResult("L-L13", cases, v)
    }

    /** L-L14: every TCP connect has a durable DIAL intent before its SYN. */
    fun l14(t: List<Ev>): LawResult {
        val first = firstAppended(t)
        val v = mutableListOf<String>()
        var cases = 0
        for ((i, e) in t.withIndex()) {
            if (e !is Syn) continue
            cases++
            val intent = first[Key(e.node, MeshKind.DIAL, Phase.INTENT, e.sessionId)]
            if (intent == null || intent > i) v += "session ${e.sessionId}: SYN before a durable DIAL intent"
        }
        return LawResult("L-L14", cases, v)
    }

    class L15(val result: LawResult, val measuredSessions: Int, val estimatedSessions: Int, val excludedSessions: Int, val mismatches: Int)

    /**
     * L-L15, per node and session: the sum of the rows' application bytes plus their `overheadBytes` equals the raw bytes the SOCKET TAP counted
     * (MEASURED sessions only). ESTIMATED sessions and sessions closed by ledger failure are counted separately and excluded; a run in which
     * no session was MEASURED fails (non-vacuity). Two more checks follow R3-OVERCLAIM-3 so that the law is not true by construction: the rows'
     * application bytes must equal the PLAINTEXT tap's count (checks the rows), and the overhead must lie within the RFC 8446 record bound
     * (checks the overhead), both against instruments that are independent of the row writer.
     */
    fun l15(t: List<Ev>): L15 {
        val raw = t.filterIsInstance<Tap>().associateBy { it.node to it.sessionId }
        val plain = t.filterIsInstance<PlainTap>().associateBy { it.node to it.sessionId }
        val bySession = rows(t).map { it.second }.filter { it.row.sessionId != null }.groupBy { it.node to it.row.sessionId!! }
        val v = mutableListOf<String>()
        var measured = 0
        var estimated = 0
        var excluded = 0
        var mismatches = 0
        for ((key, tap) in raw) {
            val rs = bySession[key].orEmpty().map { it.row }
            val hasClose = rs.any { it.meshKind == MeshKind.SESSION && it.meshCode?.startsWith("close") == true && it.overheadBytes != null }
            val bases = rs.mapNotNull { it.overheadBasis }.toSet()
            if (!hasClose || rs.any { it.meshCode == "close:ledger-failure" }) {
                excluded++
                continue
            }
            if (bases != setOf(OverheadBasis.MEASURED)) {
                estimated++
                continue
            }
            measured++
            val app = rs.sumOf { it.bytesOut + (it.bytesIn ?: 0) }
            val overhead = rs.sumOf { it.overheadBytes ?: 0 }
            if (app + overhead != tap.rawBytes) {
                mismatches++
                v += "${key.first}/${key.second}: rows hold $app application bytes + $overhead overhead = ${app + overhead}, the socket tap counted ${tap.rawBytes}"
            }
            val p = plain[key]
            if (p != null) {
                if (app != p.outBytes + p.inBytes) {
                    mismatches++
                    v += "${key.first}/${key.second}: rows hold $app application bytes, the plaintext tap counted ${p.outBytes + p.inBytes}"
                }
                if (!Overhead.withinRecordBound(overhead, p.outBytes, p.inBytes, p.flushesOut, p.flushesIn)) {
                    mismatches++
                    v += "${key.first}/${key.second}: overhead $overhead lies outside the RFC 8446 record bound for ${p.outBytes}+${p.inBytes} application bytes"
                }
            }
        }
        return L15(LawResult("L-L15", measured, v, mapOf("measured" to measured, "estimated" to estimated, "excluded" to excluded)), measured, estimated, excluded, mismatches)
    }

    /** The run-level non-vacuity rule of L-L15: a run in which no session was MEASURED (every one ESTIMATED, excluded or absent) fails the law. */
    fun l15NonVacuity(measuredSessions: Int, estimatedSessions: Int): LawResult =
        LawResult(
            "L-L15", measuredSessions,
            if (measuredSessions == 0) listOf("no MEASURED session exercised L-L15 ($estimatedSessions ESTIMATED): a run of only ESTIMATED sessions fails the law") else emptyList(),
            mapOf("measured" to measuredSessions, "estimated" to estimatedSessions),
        )

    class L16(val result: LawResult, val perType: Map<String, Int>)

    /**
     * L-L16: every frame of the "yes" rows has exactly one row of the stated kind (and bytes) on each node that sent or received it, durable
     * before the send or before any reply; every attempt frame is covered by its attempt's outcome rows (bytes summed per direction). The
     * per-frame-type counts are returned so that the caller can fail when a listed type was never exercised.
     */
    fun l16(t: List<Ev>): L16 {
        val v = mutableListOf<String>()
        val perType = LinkedHashMap<String, Int>().also { m -> FrameRows.L16_KINDS.forEach { m[it] = 0 } }
        val closedByFailure = rows(t).filter { it.second.row.meshCode == "close:ledger-failure" }.map { it.second.node to it.second.row.sessionId }.toSet()
        val perFrameKinds = setOf(MeshKind.CONTROL, MeshKind.MANIFEST_SENT, MeshKind.MANIFEST_RECEIVED, MeshKind.PAIRING, MeshKind.REVOCATION)
        val allRows = rows(t)
        val eventsBySession = LinkedHashMap<Pair<String, String>, MutableList<Pair<Int, Ev>>>()
        for ((i, e) in t.withIndex()) {
            when (e) {
                is Sent -> if (e.frame.ledgerClass != LedgerClass.ATTEMPT) eventsBySession.getOrPut(e.node to e.sessionId) { mutableListOf() } += i to e
                is Received -> if (e.frame.ledgerClass != LedgerClass.ATTEMPT) eventsBySession.getOrPut(e.node to e.sessionId) { mutableListOf() } += i to e
                else -> Unit
            }
        }
        var cases = 0
        for ((key, evs) in eventsBySession) {
            if (key in closedByFailure) continue
            val rs = allRows.filter { it.second.node == key.first && it.second.row.sessionId == key.second && it.second.row.meshKind in perFrameKinds }
            if (rs.size != evs.size) {
                v += "${key.first}/${key.second}: ${evs.size} frames but ${rs.size} per-frame rows"
                continue
            }
            for ((k, pair) in evs.withIndex()) {
                val (evIdx, ev) = pair
                val sent = ev is Sent
                val frame = if (ev is Sent) ev.frame else (ev as Received).frame
                val want = FrameRows.shape(frame, sent)!!
                val (rowIdx, row) = rs[k].let { it.first to it.second.row }
                cases++
                perType[FrameRows.l16Name(frame)!!] = perType.getValue(FrameRows.l16Name(frame)!!) + 1
                if (row.meshKind != want.meshKind || row.meshCode != want.meshCode) v += "${key.second}: frame ${frame.kind} expected row ${want.meshKind}/${want.meshCode} but found ${row.meshKind}/${row.meshCode}"
                val wantOut = if (sent) frame.appBytes else 0L
                val wantIn = if (sent) 0L else frame.appBytes
                if (row.bytesOut != wantOut || (row.bytesIn ?: 0) != wantIn) v += "${key.second}: frame ${frame.kind} is ${frame.appBytes} application bytes but the row says out ${row.bytesOut} in ${row.bytesIn}"
                if (sent && rowIdx > evIdx) v += "${key.second}: the row for sent ${frame.kind} was not durable before the frame was handed to the wire"
                if (!sent) {
                    val nextSend = t.withIndex().firstOrNull { it.index > evIdx && it.value is Sent && it.value.node == key.first }?.index
                    if (rowIdx < evIdx || (nextSend != null && rowIdx > nextSend)) v += "${key.second}: the row for received ${frame.kind} was not durable before any reply"
                }
            }
        }
        val outcomes = allRows.map { it.second }.filter { it.row.attemptId != null && it.row.phase == Phase.OUTCOME && it.row.meshKind in setOf(MeshKind.INFER_SENT, MeshKind.INFER_SERVED) }
        for (o in outcomes) {
            if ((o.node to o.row.sessionId) in closedByFailure) continue
            val out = t.filterIsInstance<Sent>().filter { it.node == o.node && it.frame.attemptId == o.row.attemptId && it.frame.ledgerClass == LedgerClass.ATTEMPT }.sumOf { it.frame.appBytes }
            val inn = t.filterIsInstance<Received>().filter { it.node == o.node && it.frame.attemptId == o.row.attemptId && it.frame.ledgerClass == LedgerClass.ATTEMPT }.sumOf { it.frame.appBytes }
            cases++
            if (o.row.bytesOut != out || (o.row.bytesIn ?: 0) != inn) {
                v += "attempt ${o.row.attemptId} on ${o.node}: frames hold out $out in $inn but the outcome row says out ${o.row.bytesOut} in ${o.row.bytesIn}"
            }
        }
        return L16(LawResult("L-L16", cases, v, perType), perType)
    }
}
