package xyz.mdhv.asom.lab.ledger

import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs

/**
 * The lab's row (LAB_SPEC 7.5): the 13 v1 fields with the egress typed [LabEgress] and the cost basis kept as its wire string,
 * the 20 mesh columns of design 8.4, and ONE more column, [sessionId] (ERRATA ERR-LL-2, R3-CLOSURE-6), without which a row
 * cannot be grouped by session and law L-L15 cannot be computed from rows alone. Every mesh column is null on a v1-style row.
 */
data class LabRouteRecord(
    val ts: Long,
    val callerPkg: String,
    val requestedModel: String,
    val servedProvider: String? = null,
    val servedModel: String? = null,
    val egress: LabEgress,
    val bytesOut: Long = 0,
    val tokensIn: Long? = null,
    val tokensOut: Long? = null,
    val costEst: Double? = null,
    val costBasis: String = "none",
    val latencyMs: Long = 0,
    val status: Int = 0,
    val requestId: String? = null,
    val attemptId: String? = null,
    val phase: Phase? = null,
    val attemptIndex: Int? = null,
    val reach: LabEgress? = null,
    val terminal: Boolean? = null,
    val servedClass: LabEgress? = null,
    val peerNode: String? = null,
    val peerAlias: String? = null,
    val peerPath: PeerPath? = null,
    val meshKind: MeshKind? = null,
    val bytesIn: Long? = null,
    val meshCode: String? = null,
    val destAddr: String? = null,
    val addrSource: String? = null,
    val routeReason: String? = null,
    val routeDetail: String? = null,
    val droppedFields: List<String>? = null,
    val overheadBytes: Long? = null,
    val overheadBasis: OverheadBasis? = null,
    val sessionId: String? = null,
) {
    /** Every rule a row must satisfy before any sink accepts it. Empty when the row is well formed. */
    fun violations(): List<String> {
        val out = mutableListOf<String>()
        fun need(ok: Boolean, what: String) {
            if (!ok) out += what
        }
        need(bytesOut >= 0, "bytesOut is negative")
        need((bytesIn ?: 0) >= 0, "bytesIn is negative")
        need((overheadBytes ?: 0) >= 0, "overheadBytes is negative")
        need(costBasis in setOf("usage", "heuristic", "none"), "costBasis '$costBasis' is not usage, heuristic or none")
        need(costEst == null || costBasis != "none", "costEst is set with costBasis none")
        if (phase == Phase.INTENT) {
            need(bytesOut == 0L && bytesIn == null, "an intent row carries no bytes")
            need(tokensIn == null && tokensOut == null && costEst == null, "an intent row carries no tokens or cost")
            need(overheadBytes == null, "an intent row carries no overhead")
            need(status == 0, "an intent row has status 0")
        }
        need((overheadBytes == null) == (overheadBasis == null), "overheadBytes and overheadBasis go together")
        if (destAddr != null || addrSource != null) {
            need(meshKind == MeshKind.DIAL, "an address appears only in DIAL rows (P5)")
        }
        if (meshKind == MeshKind.DIAL) {
            need(destAddr != null && addrSource in setOf("qr", "hello", "user"), "a DIAL row carries destAddr and addrSource in qr|hello|user")
        }
        if (overheadBytes != null) {
            need(meshKind == MeshKind.DIAL || meshKind == MeshKind.SESSION, "overhead is recorded on DIAL and SESSION rows only")
        }
        if (meshKind != null) {
            need(egress == LabEgress.peerClass, "every mesh row has egress peer (the destination class)")
            need(costEst == null && costBasis == "none", "a peer row carries no cost")
            if (meshKind != MeshKind.INBOUND_REFUSED) need(sessionId != null, "a mesh row names its session")
            if (meshKind == MeshKind.INFER_SENT || meshKind == MeshKind.INFER_SERVED) {
                need(phase != null && attemptId != null, "an attempt row has a phase and an attemptId")
            }
            if (meshKind == MeshKind.INFER_SERVED) need(requestId == null, "a lender row never carries a requestId")
            if (meshKind != MeshKind.INFER_SENT && meshKind != MeshKind.INFER_SERVED && meshKind != MeshKind.DIAL) {
                need(phase == null, "$meshKind rows are single rows without a phase")
            }
        } else {
            need(sessionId == null, "only mesh rows carry a session id")
        }
        if (terminal == true) {
            need(reach?.reachRank != null, "a terminal row carries a reach in local|peer|cloud")
            need(meshKind == null && phase == null, "the terminal row is a request row, not an attempt row")
        }
        need(servedClass == null || servedClass.reachRank != null, "servedClass is one of local|peer|cloud")
        return out
    }

    fun requireWellFormed(): LabRouteRecord {
        val v = violations()
        require(v.isEmpty()) { "malformed row: ${v.joinToString("; ")}" }
        return this
    }

    /**
     * v1's rule (`RouteRecord.toEchoHeaders`), except that `X-Asom-Egress` is `reach` on the terminal row (P7, ruled by D3). On every
     * v1 path `reach` equals v1's egress, so W01 passes unchanged through this projection.
     */
    fun toEchoHeaders(): Map<String, String> = buildMap {
        if (servedProvider != null && servedModel != null) put(AsomHeaders.SERVED_BY, "$servedProvider/$servedModel")
        val egressForHeader = if (terminal == true) (reach ?: egress) else egress
        put(AsomHeaders.EGRESS, egressForHeader.wire)
        if (costEst != null && costBasis != "none") {
            put(AsomHeaders.COST_EST, RouteRecord.formatUsd(costEst))
            put(AsomHeaders.COST_BASIS, costBasis)
        }
    }

    /**
     * The JSONL row. Integers stay integers; the one v1 double, [costEst], is a JSON STRING holding the shortest round-trip decimal
     * (rule R6, ERRATA ERR-LL-3). Every column is present, null when unset.
     */
    fun toRow(): JObject {
        fun s(v: String?): JValue = if (v == null) JNull else JString(v)
        fun n(v: Long?): JValue = if (v == null) JNull else JInt(v)
        return JObject(
            listOf(
                "ts" to JInt(ts), "callerPkg" to JString(callerPkg), "requestedModel" to JString(requestedModel),
                "servedProvider" to s(servedProvider), "servedModel" to s(servedModel), "egress" to JString(egress.wire),
                "bytesOut" to JInt(bytesOut), "tokensIn" to n(tokensIn), "tokensOut" to n(tokensOut),
                "costEst" to (if (costEst == null) JNull else JString(costEst.toString())), "costBasis" to JString(costBasis),
                "latencyMs" to JInt(latencyMs), "status" to JInt(status.toLong()),
                "requestId" to s(requestId), "attemptId" to s(attemptId), "phase" to s(phase?.wire),
                "attemptIndex" to n(attemptIndex?.toLong()), "reach" to s(reach?.wire),
                "terminal" to (if (terminal == null) JNull else JBool(terminal)), "servedClass" to s(servedClass?.wire),
                "peerNode" to s(peerNode), "peerAlias" to s(peerAlias), "peerPath" to s(peerPath?.wire),
                "meshKind" to s(meshKind?.wire), "bytesIn" to n(bytesIn), "meshCode" to s(meshCode),
                "destAddr" to s(destAddr), "addrSource" to s(addrSource), "routeReason" to s(routeReason), "routeDetail" to s(routeDetail),
                "droppedFields" to (if (droppedFields == null) JNull else JArray(droppedFields.map { JString(it) })),
                "overheadBytes" to n(overheadBytes), "overheadBasis" to s(overheadBasis?.wire), "sessionId" to s(sessionId),
            ),
        )
    }

    /** One JSONL line without its `\n`: JCS bytes, so a row has exactly one byte form. */
    fun toRowBytes(): ByteArray = Jcs.serialize(toRow())

    companion object {
        val COLUMNS: List<String> = listOf(
            "ts", "callerPkg", "requestedModel", "servedProvider", "servedModel", "egress", "bytesOut", "tokensIn", "tokensOut",
            "costEst", "costBasis", "latencyMs", "status",
            "requestId", "attemptId", "phase", "attemptIndex", "reach", "terminal", "servedClass", "peerNode", "peerAlias",
            "peerPath", "meshKind", "bytesIn", "meshCode", "destAddr", "addrSource", "routeReason", "routeDetail", "droppedFields",
            "overheadBytes", "overheadBasis", "sessionId",
        )

        /** The columns beyond the 13 v1 fields: the 20 of design 8.4 plus [sessionId]. */
        val MESH_COLUMNS: List<String> = COLUMNS.drop(13)

        /** Strict decoder for a row this module wrote: every column present, no other member, every enum a known wire value. */
        fun fromRow(o: JObject): LabRouteRecord {
            val names = o.members.map { it.first }.toSet()
            require(names == COLUMNS.toSet()) { "row members differ from the column set: ${(names - COLUMNS.toSet()) + (COLUMNS.toSet() - names)}" }
            fun str(k: String): String? = when (val v = o[k]) {
                JNull -> null
                is JString -> v.value
                else -> throw IllegalArgumentException("$k is not a string or null")
            }
            fun lng(k: String): Long? = when (val v = o[k]) {
                JNull -> null
                is JInt -> v.value
                else -> throw IllegalArgumentException("$k is not an integer or null")
            }
            fun <E> enumOf(k: String, f: (String) -> E?): E? = str(k)?.let { f(it) ?: throw IllegalArgumentException("$k has an unknown value '$it'") }
            val dropped = when (val v = o["droppedFields"]) {
                JNull -> null
                is JArray -> v.items.map { (it as? JString)?.value ?: throw IllegalArgumentException("droppedFields holds a non-string") }
                else -> throw IllegalArgumentException("droppedFields is not an array or null")
            }
            return LabRouteRecord(
                ts = lng("ts")!!, callerPkg = str("callerPkg")!!, requestedModel = str("requestedModel")!!,
                servedProvider = str("servedProvider"), servedModel = str("servedModel"),
                egress = enumOf("egress", LabEgress::fromWire) ?: throw IllegalArgumentException("egress is null"),
                bytesOut = lng("bytesOut")!!, tokensIn = lng("tokensIn"), tokensOut = lng("tokensOut"),
                costEst = str("costEst")?.let { java.lang.Double.parseDouble(it) }, costBasis = str("costBasis")!!,
                latencyMs = lng("latencyMs")!!, status = lng("status")!!.toInt(),
                requestId = str("requestId"), attemptId = str("attemptId"), phase = enumOf("phase", Phase::fromWire),
                attemptIndex = lng("attemptIndex")?.toInt(), reach = enumOf("reach", LabEgress::fromWire),
                terminal = when (val v = o["terminal"]) {
                    JNull -> null
                    is JBool -> v.value
                    else -> throw IllegalArgumentException("terminal is not a boolean or null")
                },
                servedClass = enumOf("servedClass", LabEgress::fromWire),
                peerNode = str("peerNode"), peerAlias = str("peerAlias"), peerPath = enumOf("peerPath", PeerPath::fromWire),
                meshKind = enumOf("meshKind", MeshKind::fromWire), bytesIn = lng("bytesIn"), meshCode = str("meshCode"),
                destAddr = str("destAddr"), addrSource = str("addrSource"), routeReason = str("routeReason"),
                routeDetail = str("routeDetail"), droppedFields = dropped, overheadBytes = lng("overheadBytes"),
                overheadBasis = enumOf("overheadBasis", OverheadBasis::fromWire), sessionId = str("sessionId"),
            )
        }
    }
}
