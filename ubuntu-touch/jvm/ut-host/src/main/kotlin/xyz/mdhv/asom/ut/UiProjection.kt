package xyz.mdhv.asom.ut

import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.ledger.LabEgress
import xyz.mdhv.asom.lab.ledger.LabRouteRecord

/**
 * The `record` of an `end{rid, record}` frame: a projection of the request's TERMINAL row (ubuntu-touch.md 3.5 and 7.3,
 * Invariant 9). Every member named in [COLUMNS] is the ledger row's own member, taken from `row.toRow()`, so the UI and the
 * ledger cannot disagree; `provenance` is the only derived member and is built from those same columns. `egress` is the row's
 * `egress`, which on a terminal row IS `reach` (design 7.6), so it equals the `X-Asom-Egress` a v1 caller would see.
 */
object UiProjection {
    val COLUMNS: List<String> = listOf(
        "ts", "requestId", "requestedModel", "servedProvider", "servedModel", "egress", "reach", "servedClass",
        "peerNode", "peerAlias", "peerPath", "routeReason", "status", "latencyMs", "tokensIn", "tokensOut", "terminal", "meshCode",
    )

    fun project(row: LabRouteRecord): JObject {
        require(row.terminal == true) { "only a terminal row is projected for the UI" }
        row.requireWellFormed()
        val full = row.toRow()
        val members = COLUMNS.map { it to (full[it] ?: error("row has no member $it")) } + ("provenance" to JString(provenance(row)))
        return JObject(members)
    }

    /** Peer names are self-reported (trust.md): control, format and separator characters are replaced so a name cannot restyle the line. */
    fun sanitizeAlias(alias: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < alias.length) {
            val cp = alias.codePointAt(i)
            i += Character.charCount(cp)
            val t = Character.getType(cp)
            val bad = t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt() || t == Character.LINE_SEPARATOR.toInt() ||
                t == Character.PARAGRAPH_SEPARATOR.toInt() || t == Character.UNASSIGNED.toInt() || t == Character.SURROGATE.toInt() ||
                t == Character.PRIVATE_USE.toInt()
            out.appendCodePoint(if (bad) 0xFFFD else cp)
        }
        return out.toString().take(MAX_ALIAS)
    }

    /** The line under an answer: `served by peer:<alias>/<model> · via lan`. The UI adds "(self-reported)" beside a peer alias. */
    fun provenance(row: LabRouteRecord): String {
        val served = row.servedClass
        return when {
            served == LabEgress.PEER -> {
                val alias = sanitizeAlias(row.peerAlias ?: "?")
                val model = row.servedModel ?: "?"
                "served by peer:$alias/$model · via ${row.peerPath?.wire ?: "?"}"
            }
            served == LabEgress.LOCAL -> "served by this device/${row.servedModel ?: "?"}"
            served == LabEgress.CLOUD -> "served by cloud:${row.servedProvider ?: "?"}/${row.servedModel ?: "?"}"
            row.reach == LabEgress.LOCAL -> "not served · nothing left this device"
            else -> "not served · content reached: ${row.reach?.wire ?: "none"}"
        }
    }

    private const val MAX_ALIAS = 32
}
