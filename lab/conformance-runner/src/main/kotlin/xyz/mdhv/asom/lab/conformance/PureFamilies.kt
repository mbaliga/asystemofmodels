package xyz.mdhv.asom.lab.conformance

import java.lang.reflect.Modifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.contract.CostBasis
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.contract.RouteRecord

/** W00: the frozen constants, compared with the real code by reflection (LAB_SPEC 3.4). */
class W00Checker : FamilyChecker("W00") {
    override val requiredLaws = setOf("constants", "headers-exhaustive")

    private fun headerConstants(): Map<String, String> =
        AsomHeaders::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.name to (it.also { f -> f.isAccessible = true }.get(null) as String) }

    private fun headerValues(fields: List<String>): JsonArray {
        val all = headerConstants()
        return buildJsonArray {
            fields.forEach { add(JsonPrimitive(all[it] ?: throw LawViolation("AsomHeaders has no constant $it"))) }
        }
    }

    override fun observe(v: Vector): Observed {
        if (v.id.substringAfter('-').toIntOrNull()?.let { it >= 100 } == true) {
            throw NotRunnable("mesh addition: needs the lab types of :ledger-model / :mesh-policy (L0.4), ruled by ${v.input.strOrNull("decision")}")
        }
        bump("constants")
        return when (val kind = v.input.str("kind")) {
            "headers" -> {
                val fields = v.input.strList("fields")
                if (v.input.bool("exhaustiveWith")) {
                    bump("headers-exhaustive")
                    val union = fields + v.input.strList("otherGroupFields")
                    val actual = headerConstants().keys
                    if (actual != union.toSet()) {
                        throw LawViolation("AsomHeaders declares $actual but the frozen set is ${union.toSet()}: a header was added or removed")
                    }
                }
                Observed.Ok(headerValues(fields))
            }
            "errorCodes" -> Observed.Ok(
                buildJsonArray {
                    AsomErrorCode.entries.forEach {
                        add(buildJsonObject { put("name", it.name); put("http", it.httpStatus); put("type", it.openAiType) })
                    }
                },
            )
            "egress" -> Observed.Ok(jsonStrings(Egress.entries.map { it.wire }))
            "costBasis" -> Observed.Ok(jsonStrings(CostBasis.entries.map { it.wire }))
            "virtualModels" -> Observed.Ok(jsonStrings(Policy.VIRTUAL_MODELS.toList()))
            "bind" -> Observed.Ok(buildJsonObject { put("host", Asom.BIND_HOST); put("port", Asom.DEFAULT_PORT) })
            else -> throw LawViolation("unknown W00 kind '$kind'")
        }
    }
}

/** W01: echo headers from one `RouteRecord`, no server involved (LAB_SPEC 3.5). */
class W01Checker : FamilyChecker("W01") {
    override val requiredLaws = setOf("echo-map", "served-by-present", "served-by-absent", "cost-present", "cost-absent")

    override fun observe(v: Vector): Observed {
        val inp = v.input
        val egress = Egress.entries.firstOrNull { it.wire == inp.str("egress") }
            ?: throw LawViolation("unknown egress '${inp.strOrNull("egress")}'")
        val basis = CostBasis.entries.firstOrNull { it.wire == inp.str("costBasis") }
            ?: throw LawViolation("unknown cost basis '${inp.strOrNull("costBasis")}'")
        val cost = inp.strOrNull("costEst")?.let { java.lang.Double.parseDouble(it) }
        val record = RouteRecord(
            ts = 0, callerPkg = "w01", requestedModel = "m",
            servedProvider = inp.strOrNull("servedProvider"), servedModel = inp.strOrNull("servedModel"),
            egress = egress, costEst = cost, costBasis = basis, latencyMs = 0,
            status = if (inp["status"] == null) 200 else inp.long("status").toInt(),
        )
        val headers = record.toEchoHeaders()
        bump("echo-map")
        bump(if (AsomHeaders.SERVED_BY in headers) "served-by-present" else "served-by-absent")
        bump(if (AsomHeaders.COST_EST in headers) "cost-present" else "cost-absent")
        return Observed.Ok(headerMapJson(headers))
    }
}

/** Canonical-case header names for the four echo headers; anything else is kept as sent. */
fun canonicalHeaderName(name: String): String =
    listOf(AsomHeaders.SERVED_BY, AsomHeaders.EGRESS, AsomHeaders.COST_EST, AsomHeaders.COST_BASIS)
        .firstOrNull { it.equals(name, ignoreCase = true) } ?: name

/** Names are compared case-insensitively (LAB_SPEC 3.5): the observed map is written with canonical names in sorted order. */
fun headerMapJson(headers: Map<String, String>): JsonElement =
    buildJsonObject {
        headers.entries.sortedBy { canonicalHeaderName(it.key) }
            .forEach { put(canonicalHeaderName(it.key), it.value) }
    }
