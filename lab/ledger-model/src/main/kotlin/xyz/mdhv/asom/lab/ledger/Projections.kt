package xyz.mdhv.asom.lab.ledger

import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString

/**
 * `headersOf(toRow(r))`: the echo headers computed from the JSONL row alone, reading the JSON members directly. It shares no
 * code with [LabRouteRecord.toEchoHeaders] apart from the frozen USD formatter, so `headersOf(toRow(r)) == toEchoHeaders(r)` is a real
 * comparison of two derivations (Invariant 9: the API and the dashboard read the same fact).
 */
object RowProjection {
    fun headersOf(row: JObject): Map<String, String> {
        fun text(k: String): String? = (row[k] as? JString)?.value
        val out = LinkedHashMap<String, String>()
        val provider = text("servedProvider")
        val model = text("servedModel")
        if (provider != null && model != null) out[AsomHeaders.SERVED_BY] = "$provider/$model"
        val terminal = (row["terminal"] as? JBool)?.value == true
        out[AsomHeaders.EGRESS] = if (terminal && text("reach") != null) text("reach")!! else text("egress")!!
        val cost = text("costEst")
        val basis = text("costBasis")
        if (cost != null && basis != null && basis != "none") {
            out[AsomHeaders.COST_EST] = RouteRecord.formatUsd(java.lang.Double.parseDouble(cost))
            out[AsomHeaders.COST_BASIS] = basis
        }
        return out
    }
}
