package xyz.mdhv.asom.ledger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The §9 export payload. Metadata rows only — `route_log` has no body and no
 * key column, and the vault is a separate database (§1.4), so nothing here can
 * carry key material. Rendering is pure so the dashboard can show the exact
 * bytes to the user before anything is shared (§1.1).
 */
object LedgerExport {

    private val json = Json { prettyPrint = true }

    fun toJson(rows: List<RouteLogEntity>): String =
        json.encodeToString(JsonArray.serializer(), JsonArray(rows.map { it.toJsonObject() }))

    private fun RouteLogEntity.toJsonObject(): JsonObject = JsonObject(
        mapOf(
            "ts" to JsonPrimitive(ts),
            "callerPkg" to JsonPrimitive(callerPkg),
            "requestedModel" to JsonPrimitive(requestedModel),
            "servedProvider" to servedProvider.orJsonNull(),
            "servedModel" to servedModel.orJsonNull(),
            "egress" to JsonPrimitive(egress),
            "bytesOut" to JsonPrimitive(bytesOut),
            "tokensIn" to tokensIn.orJsonNull(),
            "tokensOut" to tokensOut.orJsonNull(),
            "costEst" to costEst.orJsonNull(),
            "costBasis" to JsonPrimitive(costBasis),
            "latencyMs" to JsonPrimitive(latencyMs),
            "status" to JsonPrimitive(status),
        ),
    )

    private fun String?.orJsonNull(): JsonElement = if (this == null) JsonNull else JsonPrimitive(this)

    private fun Number?.orJsonNull(): JsonElement = if (this == null) JsonNull else JsonPrimitive(this)
}
