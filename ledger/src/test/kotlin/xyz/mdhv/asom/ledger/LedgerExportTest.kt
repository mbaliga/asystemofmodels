package xyz.mdhv.asom.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The §9 export payload — the one thing v1 is allowed to hand to another app. */
class LedgerExportTest {

    private fun row(
        id: Long = 1,
        callerPkg: String = "xyz.mdhv.asom.sample",
        requestedModel: String = "cheapest",
        servedProvider: String? = "openrouter",
    ) = RouteLogEntity(
        id = id,
        ts = 1700000000000L,
        callerPkg = callerPkg,
        requestedModel = requestedModel,
        servedProvider = servedProvider,
        servedModel = if (servedProvider == null) null else "llama-3.3-70b",
        egress = "cloud",
        bytesOut = 512,
        tokensIn = 10,
        tokensOut = 20,
        costEst = 0.000123,
        costBasis = "usage",
        latencyMs = 240,
        status = 200,
    )

    @Test
    fun `every ledger field round-trips through the payload`() {
        val parsed = Json.parseToJsonElement(LedgerExport.toJson(listOf(row()))) as JsonArray
        assertEquals(1, parsed.size)
        val obj = parsed[0].jsonObject
        assertEquals(1700000000000L, obj.getValue("ts").jsonPrimitive.content.toLong())
        assertEquals("xyz.mdhv.asom.sample", obj.getValue("callerPkg").jsonPrimitive.content)
        assertEquals("cheapest", obj.getValue("requestedModel").jsonPrimitive.content)
        assertEquals("openrouter", obj.getValue("servedProvider").jsonPrimitive.content)
        assertEquals("cloud", obj.getValue("egress").jsonPrimitive.content)
        assertEquals(512L, obj.getValue("bytesOut").jsonPrimitive.content.toLong())
        assertEquals("usage", obj.getValue("costBasis").jsonPrimitive.content)
        assertEquals(200, obj.getValue("status").jsonPrimitive.content.toInt())
    }

    @Test
    fun `unserved rows export explicit nulls`() {
        val unserved = row(servedProvider = null).copy(tokensIn = null, tokensOut = null, costEst = null)
        val obj = (Json.parseToJsonElement(LedgerExport.toJson(listOf(unserved))) as JsonArray)[0].jsonObject
        assertEquals(JsonNull, obj.getValue("servedProvider"))
        assertEquals(JsonNull, obj.getValue("servedModel"))
        assertEquals(JsonNull, obj.getValue("costEst"))
    }

    @Test
    fun `caller-supplied text cannot break out of the payload`() {
        val hostile = "\"},{\"injected\":true,\"x\":\"\n\\"
        val json = LedgerExport.toJson(listOf(row(requestedModel = hostile)))
        val parsed = Json.parseToJsonElement(json) as JsonArray
        assertEquals(1, parsed.size, "a hostile model name must not add rows")
        assertEquals(hostile, parsed[0].jsonObject.getValue("requestedModel").jsonPrimitive.content)
    }

    @Test
    fun `the payload carries no key material fields`() {
        val json = LedgerExport.toJson(listOf(row()))
        for (forbidden in listOf("apiKey", "api_key", "authorization", "bearer", "sk-")) {
            assertFalse(json.contains(forbidden, ignoreCase = true), "'$forbidden' must never reach the export")
        }
        assertTrue(json.contains("callerPkg"))
    }

    @Test
    fun `an empty ledger exports an empty array`() {
        val parsed = Json.parseToJsonElement(LedgerExport.toJson(emptyList())) as JsonArray
        assertTrue(parsed.isEmpty())
    }
}
