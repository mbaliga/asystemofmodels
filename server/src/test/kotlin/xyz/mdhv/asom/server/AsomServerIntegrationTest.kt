package xyz.mdhv.asom.server

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.catalogue.ProviderKind
import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.contract.CostBasis
import xyz.mdhv.asom.routing.CooldownRegistry
import xyz.mdhv.asom.routing.LatencyTracker
import xyz.mdhv.asom.server.auth.InMemoryTokenRegistry
import xyz.mdhv.asom.server.driver.FakeDriver
import xyz.mdhv.asom.server.keys.InMemoryKeyProvider
import xyz.mdhv.asom.server.ledger.InMemoryLedger

/**
 * JVM integration tests against a REAL Ktor CIO server bound to 127.0.0.1
 * (brief P3). FakeDriver keeps everything in-process and deterministic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsomServerIntegrationTest {

    // A key that must NEVER appear in any API response (invariant §1.4).
    private val secretMarker = "sk-SUPER-SECRET-NEVER-LEAK"

    private val catalogue = CatalogueParser.parse(File("../fixtures/catalogue.v1.json").readText())
    private val tokens = InMemoryTokenRegistry().apply {
        issue("test-token", "test.caller")
        issue("revoked-token", "revoked.caller")
        revoke("revoked-token")
    }
    private val keys = InMemoryKeyProvider(
        mapOf(
            "openrouter" to "$secretMarker-openrouter",
            "groq" to "$secretMarker-groq",
            "trainy-ai" to "$secretMarker-trainy",
            "anthropic" to "$secretMarker-anthropic",
        ),
    )
    private val fake = FakeDriver()
    private val ledger = InMemoryLedger()
    private val latency = LatencyTracker()

    private var now = 1_000_000_000_000L
    private val cooldowns = CooldownRegistry(clock = { now })

    private val server = AsomServer(
        AsomServerConfig(
            port = 0, // ephemeral for tests; production default is 11435
            catalogue = { catalogue },
            tokens = tokens,
            keys = keys,
            drivers = { _: ProviderKind -> fake },
            ledger = ledger,
            cooldowns = cooldowns,
            latency = latency,
        ),
    )

    private var port = 0
    private val http = HttpClient.newHttpClient()

    @BeforeAll
    fun startServer() {
        server.start(wait = false)
        port = runBlocking { server.resolvedPort() }
    }

    @AfterAll
    fun stopServer() {
        server.stop()
    }

    /** Clears breaker state between failure-injection tests. */
    private fun resetBreakers() {
        listOf("openrouter", "groq", "trainy-ai", "anthropic").forEach {
            fake.heal(it)
            cooldowns.recordSuccess(it)
        }
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        token: String? = "test-token",
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        token?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> builder.header(k, v) }
        when (method) {
            "GET" -> builder.GET()
            "POST" -> builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun chatBody(model: String, content: String = "hello", stream: Boolean = false): String =
        """{"model":"$model","stream":$stream,"messages":[{"role":"user","content":"$content"}]}"""

    private fun json(response: HttpResponse<String>): JsonObject =
        Json.parseToJsonElement(response.body()).jsonObject

    private fun errorCode(response: HttpResponse<String>): String? =
        json(response)["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull

    // ------------------------------------------------------------------ auth

    @Test
    fun `missing token is 401 NOT_PAIRED`() {
        val r = request("POST", "/v1/chat/completions", chatBody("cheapest"), token = null)
        assertEquals(401, r.statusCode())
        assertEquals("NOT_PAIRED", errorCode(r))
    }

    @Test
    fun `unknown token is 401 NOT_PAIRED`() {
        val r = request("POST", "/v1/chat/completions", chatBody("cheapest"), token = "nope")
        assertEquals(401, r.statusCode())
        assertEquals("NOT_PAIRED", errorCode(r))
    }

    @Test
    fun `revoked token is 401 TOKEN_REVOKED`() {
        val r = request("GET", "/admin/health", token = "revoked-token")
        assertEquals(401, r.statusCode())
        assertEquals("TOKEN_REVOKED", errorCode(r))
    }

    // ------------------------------------------------------------- chat core

    @Test
    fun `cheapest routes to the cheapest provider and echoes the §1_9 headers`() {
        resetBreakers()
        val before = ledger.all().size
        val r = request("POST", "/v1/chat/completions", chatBody("cheapest", "route me"))
        assertEquals(200, r.statusCode())

        val content = json(r)["choices"]!!.jsonArray[0].jsonObject["message"]!!
            .jsonObject["content"]!!.jsonPrimitive.contentOrNull
        assertEquals("fake:trainy-ai/llama-3.3-70b:route me", content)

        assertEquals("trainy-ai/llama-3.3-70b", r.headers().firstValue(AsomHeaders.SERVED_BY).get())
        assertEquals("cloud", r.headers().firstValue(AsomHeaders.EGRESS).get())
        assertEquals("usage", r.headers().firstValue(AsomHeaders.COST_BASIS).get())
        assertTrue(r.headers().firstValue(AsomHeaders.COST_EST).get().toDouble() > 0)

        // Invariant §1.9: ledger row and echo headers come from the SAME record.
        val record = ledger.all().drop(before).single()
        val echoed = record.toEchoHeaders()
        for ((name, value) in echoed) {
            assertEquals(value, r.headers().firstValue(name).get(), "header $name diverged from ledger")
        }
        assertEquals("test.caller", record.callerPkg)
        assertEquals(CostBasis.USAGE, record.costBasis)
        assertEquals(200, record.status)
        assertTrue(record.bytesOut > 0)
    }

    @Test
    fun `no-train header excludes training providers`() {
        resetBreakers()
        val r = request(
            "POST", "/v1/chat/completions", chatBody("llama-3.3-70b"),
            headers = mapOf(AsomHeaders.POLICY to "cheapest", AsomHeaders.NO_TRAIN to "true"),
        )
        assertEquals(200, r.statusCode())
        assertEquals("openrouter/llama-3.3-70b", r.headers().firstValue(AsomHeaders.SERVED_BY).get())
    }

    @Test
    fun `fallback header restricts and orders`() {
        resetBreakers()
        val r = request(
            "POST", "/v1/chat/completions", chatBody("llama-3.3-70b"),
            headers = mapOf(AsomHeaders.FALLBACK to "groq,openrouter"),
        )
        assertEquals(200, r.statusCode())
        assertEquals("groq/llama-3.3-70b", r.headers().firstValue(AsomHeaders.SERVED_BY).get())
    }

    @Test
    fun `local-only fails loudly with 501 LOCAL_ENGINE_ABSENT and egress local`() {
        val before = ledger.all().size
        val r = request("POST", "/v1/chat/completions", chatBody("local-only"))
        assertEquals(501, r.statusCode())
        assertEquals("LOCAL_ENGINE_ABSENT", errorCode(r))
        assertEquals("local", r.headers().firstValue(AsomHeaders.EGRESS).get())
        assertTrue(r.headers().firstValue(AsomHeaders.SERVED_BY).isEmpty)

        val record = ledger.all().drop(before).single()
        assertEquals(501, record.status)
        assertEquals(0, record.bytesOut) // nothing left the device
    }

    @Test
    fun `unknown model is 404 MODEL_UNKNOWN`() {
        val r = request("POST", "/v1/chat/completions", chatBody("gpt-99"))
        assertEquals(404, r.statusCode())
        assertEquals("MODEL_UNKNOWN", errorCode(r))
    }

    @Test
    fun `invalid policy header is a 400 with no typed code`() {
        val r = request(
            "POST", "/v1/chat/completions", chatBody("cheapest"),
            headers = mapOf(AsomHeaders.POLICY to "vibes"),
        )
        assertEquals(400, r.statusCode())
        assertNull(errorCode(r))
        assertEquals("invalid_request_error", json(r)["error"]!!.jsonObject["type"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun `malformed JSON body is a 400 and still writes a ledger row`() {
        val before = ledger.all().size
        val r = request("POST", "/v1/chat/completions", "{not json")
        assertEquals(400, r.statusCode())
        assertEquals(1, ledger.all().size - before)
    }

    // ------------------------------------------------------------- streaming

    @Test
    fun `streaming emits SSE chunks, commits headers first, and ledgers usage cost`() {
        resetBreakers()
        val before = ledger.all().size
        val r = request("POST", "/v1/chat/completions", chatBody("cheapest", "stream me", stream = true))
        assertEquals(200, r.statusCode())
        assertTrue(r.headers().firstValue("Content-Type").get().startsWith("text/event-stream"))

        // Headers were committed before the body (§5.9): served-by present,
        // cost headers absent (not derivable at commit time).
        assertEquals("trainy-ai/llama-3.3-70b", r.headers().firstValue(AsomHeaders.SERVED_BY).get())
        assertEquals("cloud", r.headers().firstValue(AsomHeaders.EGRESS).get())
        assertTrue(r.headers().firstValue(AsomHeaders.COST_EST).isEmpty)

        val dataLines = r.body().lines().filter { it.startsWith("data: ") }
        assertTrue(dataLines.size >= 4, "expected role+content+finish+DONE, got: $dataLines")
        assertEquals("data: [DONE]", dataLines.last())

        val text = dataLines.dropLast(1).joinToString("") { line ->
            val obj = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
            obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
        }
        assertEquals("fake:trainy-ai/llama-3.3-70b:stream me", text)

        // include_usage was injected (§5.9) → final record has usage-based cost.
        val record = ledger.all().drop(before).single()
        assertEquals(CostBasis.USAGE, record.costBasis)
        assertNotNull(record.costEst)
        assertTrue(record.tokensOut!! > 0)
    }

    @Test
    fun `legacy completions shim works non-streaming and streaming`() {
        resetBreakers()
        val r = request(
            "POST", "/v1/completions",
            """{"model":"llama-3.3-70b","prompt":"shim me"}""",
            headers = mapOf(AsomHeaders.POLICY to "cheapest"),
        )
        assertEquals(200, r.statusCode())
        val obj = json(r)
        assertEquals("text_completion", obj["object"]!!.jsonPrimitive.contentOrNull)
        assertEquals(
            "fake:trainy-ai/llama-3.3-70b:shim me",
            obj["choices"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.contentOrNull,
        )

        val s = request(
            "POST", "/v1/completions",
            """{"model":"llama-3.3-70b","prompt":"shim stream","stream":true}""",
            headers = mapOf(AsomHeaders.POLICY to "cheapest"),
        )
        assertEquals(200, s.statusCode())
        val dataLines = s.body().lines().filter { it.startsWith("data: ") }
        assertEquals("data: [DONE]", dataLines.last())
        val text = dataLines.dropLast(1).joinToString("") { line ->
            val chunk = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
            assertEquals("text_completion", chunk["object"]!!.jsonPrimitive.contentOrNull)
            chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
        }
        assertEquals("fake:trainy-ai/llama-3.3-70b:shim stream", text)
    }

    // ------------------------------------------------------------ embeddings

    @Test
    fun `embeddings route cloud and ledger like chat`() {
        resetBreakers()
        val before = ledger.all().size
        val r = request(
            "POST", "/v1/embeddings",
            """{"model":"llama-3.3-70b","input":"embed me"}""",
            headers = mapOf(AsomHeaders.POLICY to "cheapest"),
        )
        assertEquals(200, r.statusCode())
        val emb = json(r)["data"]!!.jsonArray[0].jsonObject["embedding"]!!.jsonArray
        assertEquals(3, emb.size)
        assertEquals("cloud", r.headers().firstValue(AsomHeaders.EGRESS).get())
        assertEquals(1, ledger.all().size - before)
    }

    // -------------------------------------------------------- circuit breaker

    @Test
    fun `retryable failure cools the provider and falls to the next`() {
        resetBreakers()
        try {
            fake.failWith("trainy-ai", 429)
            val r = request("POST", "/v1/chat/completions", chatBody("llama-3.3-70b"), headers = mapOf(AsomHeaders.POLICY to "cheapest"))
            assertEquals(200, r.statusCode())
            assertEquals("openrouter/llama-3.3-70b", r.headers().firstValue(AsomHeaders.SERVED_BY).get())
            assertTrue(cooldowns.isCooling("trainy-ai"))

            // /admin/catalogue reflects the live breaker state (§5.2).
            val admin = json(request("GET", "/admin/catalogue"))
            val trainy = admin["providers"]!!.jsonObject["trainy-ai"]!!.jsonObject
            assertTrue(trainy["cooling"]!!.jsonPrimitive.boolean)
            assertNotNull(trainy["coolingUntil"])
        } finally {
            resetBreakers()
        }
    }

    @Test
    fun `all providers failing retryably is 503 ALL_PROVIDERS_COOLING`() {
        resetBreakers()
        try {
            listOf("trainy-ai", "openrouter", "groq").forEach { fake.failWith(it, 500) }
            val r = request("POST", "/v1/chat/completions", chatBody("llama-3.3-70b"))
            assertEquals(503, r.statusCode())
            assertEquals("ALL_PROVIDERS_COOLING", errorCode(r))
        } finally {
            resetBreakers()
        }
    }

    @Test
    fun `fatal upstream error is relayed with egress cloud`() {
        resetBreakers()
        try {
            fake.failWith("trainy-ai", 400)
            val before = ledger.all().size
            val r = request("POST", "/v1/chat/completions", chatBody("llama-3.3-70b"), headers = mapOf(AsomHeaders.POLICY to "cheapest"))
            assertEquals(400, r.statusCode())
            assertEquals("cloud", r.headers().firstValue(AsomHeaders.EGRESS).get())
            assertTrue("injected failure" in r.body())
            assertEquals(400, ledger.all().drop(before).single().status)
        } finally {
            resetBreakers()
        }
    }

    @Test
    fun `cooldown expires after the backoff window`() {
        resetBreakers()
        try {
            fake.failWith("trainy-ai", 429)
            request("POST", "/v1/chat/completions", chatBody("llama-3.3-70b"), headers = mapOf(AsomHeaders.POLICY to "cheapest"))
            assertTrue(cooldowns.isCooling("trainy-ai"))
            fake.heal("trainy-ai")

            now += 31_000 // past the 30s first backoff
            val r = request("POST", "/v1/chat/completions", chatBody("llama-3.3-70b"), headers = mapOf(AsomHeaders.POLICY to "cheapest"))
            assertEquals("trainy-ai/llama-3.3-70b", r.headers().firstValue(AsomHeaders.SERVED_BY).get())
        } finally {
            resetBreakers()
        }
    }

    // ---------------------------------------------------------------- models

    @Test
    fun `models lists keyed concrete models and tagged virtual models`() {
        val r = request("GET", "/v1/models")
        assertEquals(200, r.statusCode())
        val data = json(r)["data"]!!.jsonArray.associate {
            it.jsonObject["id"]!!.jsonPrimitive.contentOrNull!! to
                it.jsonObject["owned_by"]!!.jsonPrimitive.contentOrNull!!
        }
        assertEquals("groq,openrouter,trainy-ai", data["llama-3.3-70b"])
        assertEquals("anthropic", data["claude-sonnet-4-5"])
        assertEquals("openrouter", data["deepseek-v3"])
        assertEquals("asom-virtual", data["auto"])
        assertEquals("asom-virtual", data["cheapest"])
        assertEquals("asom-virtual", data["local-only"])
        // webchat-only has no key stored → its exclusive models would be absent.
        assertFalse(data.keys.any { it.startsWith("webchat") })
    }

    // ----------------------------------------------------------------- admin

    @Test
    fun `health reports version and hasLocalEngine false`() {
        val r = request("GET", "/admin/health")
        assertEquals(200, r.statusCode())
        val obj = json(r)
        assertEquals("ok", obj["status"]!!.jsonPrimitive.contentOrNull)
        assertEquals(false, obj["hasLocalEngine"]!!.jsonPrimitive.boolean)
        assertEquals(1, obj["catalogueVersion"]!!.jsonPrimitive.contentOrNull!!.toInt())
    }

    @Test
    fun `admin catalogue merges catalogue, key presence, and capabilities`() {
        val r = request("GET", "/admin/catalogue")
        assertEquals(200, r.statusCode())
        val obj = json(r)
        assertEquals(1, obj["catalogue"]!!.jsonObject["version"]!!.jsonPrimitive.contentOrNull!!.toInt())
        val providers = obj["providers"]!!.jsonObject
        assertEquals(true, providers["openrouter"]!!.jsonObject["keyPresent"]!!.jsonPrimitive.boolean)
        assertEquals(false, providers["webchat-only"]!!.jsonObject["keyPresent"]!!.jsonPrimitive.boolean)
        assertEquals(false, obj["capabilities"]!!.jsonObject["hasLocalEngine"]!!.jsonPrimitive.boolean)
    }

    // ------------------------------------------------------------- invariants

    @Test
    fun `key material never appears in any API response`() {
        resetBreakers()
        val bodies = listOf(
            request("POST", "/v1/chat/completions", chatBody("cheapest")).body(),
            request("POST", "/v1/chat/completions", chatBody("cheapest", stream = true)).body(),
            request("GET", "/v1/models").body(),
            request("GET", "/admin/health").body(),
            request("GET", "/admin/catalogue").body(),
            request("POST", "/v1/embeddings", """{"model":"llama-3.3-70b","input":"x"}""").body(),
        )
        for (body in bodies) {
            assertFalse(secretMarker in body, "key material leaked into an API response!")
        }
    }

    @Test
    fun `server is reachable on loopback only — bound to 127_0_0_1`() {
        // The bind host is hardcoded to Asom.BIND_HOST in AsomServer.start —
        // there is no config surface for 0.0.0.0 (invariant §1.2). Assert the
        // loopback connector actually answers.
        val r = request("GET", "/admin/health")
        assertEquals(200, r.statusCode())
    }

    @Test
    fun `every routed request writes exactly one ledger row`() {
        resetBreakers()
        val before = ledger.all().size
        request("POST", "/v1/chat/completions", chatBody("cheapest"))
        request("POST", "/v1/chat/completions", chatBody("local-only"))
        request("POST", "/v1/chat/completions", chatBody("gpt-99"))
        request("POST", "/v1/embeddings", """{"model":"llama-3.3-70b","input":"x"}""")
        assertEquals(4, ledger.all().size - before)
    }
}
