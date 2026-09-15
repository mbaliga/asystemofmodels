package xyz.mdhv.asom.server

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.flow.flow
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
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.catalogue.ProviderKind
import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.contract.CostBasis
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.routing.CooldownRegistry
import xyz.mdhv.asom.routing.LatencyTracker
import xyz.mdhv.asom.server.auth.InMemoryTokenRegistry
import xyz.mdhv.asom.server.driver.AnthropicDriver
import xyz.mdhv.asom.server.driver.DriverOutcome
import xyz.mdhv.asom.server.driver.FakeDriver
import xyz.mdhv.asom.server.driver.ProviderDriver
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

    /** Swappable so a test can model a driver the FakeDriver cannot (chopped streams, bad bodies). */
    @Volatile
    private var driverFor: (ProviderKind) -> ProviderDriver = { fake }

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
            drivers = { kind: ProviderKind -> driverFor(kind) },
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
        driverFor = { fake }
        fake.omitStreamUsage = false
        listOf("openrouter", "groq", "trainy-ai", "anthropic").forEach {
            fake.heal(it)
            cooldowns.recordSuccess(it)
        }
    }

    /** The ledger row for an aborted stream is written after the socket dies. */
    private fun rowsSince(before: Int, expected: Int): List<RouteRecord> {
        val deadline = System.currentTimeMillis() + 5_000
        while (ledger.all().size - before < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        return ledger.all().drop(before)
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

    // -------------------------------------------------- egress ledger fidelity

    @Test
    fun `a failed-over attempt gets its own cloud ledger row`() {
        resetBreakers()
        try {
            fake.failWith("trainy-ai", 429)
            val before = ledger.all().size
            val r = request(
                "POST", "/v1/chat/completions", chatBody("llama-3.3-70b", "private text"),
                headers = mapOf(AsomHeaders.POLICY to "cheapest"),
            )
            assertEquals(200, r.statusCode())

            // The prompt reached trainy-ai before the 429 came back: §1.3 owes
            // that transmission a row of its own, not just the winner's.
            val rows = ledger.all().drop(before)
            assertEquals(2, rows.size, "expected one row per network event, got $rows")
            val attempt = rows[0]
            assertEquals("trainy-ai", attempt.servedProvider)
            assertEquals(Egress.CLOUD, attempt.egress)
            assertEquals(429, attempt.status)
            assertTrue(attempt.bytesOut > 0, "the attempted body size must be recorded")
            assertEquals("openrouter", rows[1].servedProvider)
            assertEquals(200, rows[1].status)
        } finally {
            resetBreakers()
        }
    }

    @Test
    fun `ALL_PROVIDERS_COOLING after dispatch never claims the request stayed local`() {
        resetBreakers()
        try {
            listOf("trainy-ai", "openrouter", "groq").forEach { fake.failWith(it, 500) }
            val before = ledger.all().size
            val r = request("POST", "/v1/chat/completions", chatBody("llama-3.3-70b", "confidential"))
            assertEquals(503, r.statusCode())
            assertEquals("ALL_PROVIDERS_COOLING", errorCode(r))

            val rows = ledger.all().drop(before)
            val attempts = rows.dropLast(1)
            assertEquals(3, attempts.size, "one row per provider actually contacted, got $rows")
            assertEquals(
                setOf("trainy-ai", "openrouter", "groq"),
                attempts.mapNotNull { it.servedProvider }.toSet(),
            )
            attempts.forEach {
                assertEquals(Egress.CLOUD, it.egress)
                assertEquals(500, it.status)
                assertTrue(it.bytesOut > 0)
            }

            // The terminal row and the echo header must not say "nothing left
            // the device" after three full transmissions.
            val terminal = rows.last()
            assertEquals(503, terminal.status)
            assertEquals(Egress.CLOUD, terminal.egress)
            assertEquals("cloud", r.headers().firstValue(AsomHeaders.EGRESS).get())
        } finally {
            resetBreakers()
        }
    }

    @Test
    fun `a pre-dispatch failure still ledgers egress local`() {
        resetBreakers()
        val before = ledger.all().size
        val r = request("POST", "/v1/chat/completions", chatBody("gpt-99"))
        assertEquals(404, r.statusCode())
        assertEquals(Egress.LOCAL, ledger.all().drop(before).single().egress)
        assertEquals("local", r.headers().firstValue(AsomHeaders.EGRESS).get())
    }

    @Test
    fun `a stream that dies mid-flight ledgers one cloud row, not a local 400`() {
        resetBreakers()
        try {
            fake.failMidStream("trainy-ai", 2)
            val before = ledger.all().size
            try {
                request("POST", "/v1/chat/completions", chatBody("cheapest", "stream me", stream = true))
            } catch (e: Exception) {
                // The stream is cut without a terminating chunk — expected.
            }

            val rows = rowsSince(before, 1)
            assertEquals(1, rows.size, "an aborted stream owes exactly one row, got $rows")
            val row = rows.single()
            // The echo headers already committed cloud/trainy-ai; the row must
            // agree and must not be reclassified as a local client error.
            assertEquals(Egress.CLOUD, row.egress)
            assertEquals("trainy-ai", row.servedProvider)
            assertEquals("llama-3.3-70b", row.servedModel)
            assertTrue(row.bytesOut > 0)
            assertTrue(row.status != 200 && row.status != 400, "expected a terminal failure status, got ${row.status}")
        } finally {
            resetBreakers()
        }
    }

    @Test
    fun `an upstream body the driver cannot read is not blamed on the caller`() {
        resetBreakers()
        try {
            // A captive portal answering 200 with HTML: the driver parses the
            // body and throws something that is not an IOException.
            val broken = object : ProviderDriver {
                override suspend fun chat(
                    provider: ProviderEntry,
                    apiKey: String,
                    body: JsonObject,
                    stream: Boolean,
                ): DriverOutcome {
                    if (provider.id == "trainy-ai") Json.parseToJsonElement("<!DOCTYPE html>")
                    return fake.chat(provider, apiKey, body, stream)
                }

                override suspend fun embeddings(
                    provider: ProviderEntry,
                    apiKey: String,
                    body: JsonObject,
                ): DriverOutcome = fake.embeddings(provider, apiKey, body)
            }
            driverFor = { broken }
            val before = ledger.all().size
            val r = request(
                "POST", "/v1/chat/completions", chatBody("llama-3.3-70b"),
                headers = mapOf(AsomHeaders.POLICY to "cheapest"),
            )

            // The provider malfunctioned, so the chain must fall through to the
            // next candidate rather than returning the caller a 400.
            assertEquals(200, r.statusCode())
            assertEquals("openrouter/llama-3.3-70b", r.headers().firstValue(AsomHeaders.SERVED_BY).get())
            assertTrue(cooldowns.isCooling("trainy-ai"), "the broken provider must be cooled")

            val rows = ledger.all().drop(before)
            assertEquals(2, rows.size)
            assertEquals(Egress.CLOUD, rows[0].egress)
            assertEquals("trainy-ai", rows[0].servedProvider)
        } finally {
            resetBreakers()
        }
    }

    @Test
    fun `a candidate its driver cannot serve is skipped, not fatal to the request`() {
        resetBreakers()
        try {
            val anthropic = AnthropicDriver()
            driverFor = { kind -> if (kind == ProviderKind.ANTHROPIC) anthropic else fake }

            // anthropic is first in the fallback order and has no embeddings
            // endpoint; openrouter behind it can serve, so the request must not
            // die on the first candidate's pre-flight 501.
            val r = request(
                "POST", "/v1/embeddings", """{"model":"cheapest","input":"x"}""",
                headers = mapOf(AsomHeaders.FALLBACK to "anthropic,openrouter"),
            )
            assertEquals(200, r.statusCode())
            assertTrue(r.headers().firstValue(AsomHeaders.SERVED_BY).get().startsWith("openrouter/"))

            // But when EVERY candidate is skipped that way the typed code still
            // reaches the caller rather than being swallowed.
            val only = request("POST", "/v1/embeddings", """{"model":"claude-sonnet-4-5","input":"x"}""")
            assertEquals(501, only.statusCode())
            assertEquals("UNSUPPORTED_BY_DRIVER", errorCode(only))
        } finally {
            resetBreakers()
        }
    }

    // ------------------------------------------------------------- stream cost

    @Test
    fun `a stream with no usage still bills output tokens heuristically`() {
        resetBreakers()
        try {
            fake.omitStreamUsage = true
            val before = ledger.all().size
            val r = request("POST", "/v1/chat/completions", chatBody("cheapest", "stream me", stream = true))
            assertEquals(200, r.statusCode())

            val record = ledger.all().drop(before).single()
            assertEquals(CostBasis.HEURISTIC, record.costBasis)
            assertNotNull(record.tokensOut, "output tokens must be estimated, not dropped")
            assertTrue(record.tokensOut!! > 0, "output billed as zero understates the row")
            assertNotNull(record.costEst)
        } finally {
            resetBreakers()
        }
    }

    // ----------------------------------------------------------- stream framing

    @Test
    fun `a stream chopped at arbitrary byte offsets survives the legacy shim intact`() {
        resetBreakers()
        try {
            // Reproduces the openai-compat driver's raw 8 KiB socket reads:
            // SSE events and multi-byte codepoints straddle emissions.
            driverFor = { ChoppingDriver(fake, chunkSize = 13) }
            val prompt = "契約書を要約してください"
            val s = request(
                "POST", "/v1/completions",
                """{"model":"llama-3.3-70b","prompt":"$prompt","stream":true}""",
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
            assertEquals("fake:trainy-ai/llama-3.3-70b:$prompt", text)
            assertFalse('�' in text, "a chunk boundary inside a codepoint corrupted the output")
        } finally {
            resetBreakers()
        }
    }

    @Test
    fun `a chopped chat stream is still forwarded byte-identically`() {
        resetBreakers()
        try {
            val prompt = "契約書"
            val whole = request(
                "POST", "/v1/chat/completions", chatBody("llama-3.3-70b", prompt, stream = true),
                headers = mapOf(AsomHeaders.POLICY to "cheapest"),
            ).body()
            driverFor = { ChoppingDriver(fake, chunkSize = 7) }
            val chopped = request(
                "POST", "/v1/chat/completions", chatBody("llama-3.3-70b", prompt, stream = true),
                headers = mapOf(AsomHeaders.POLICY to "cheapest"),
            ).body()
            // §5.9 pass-through: re-framing may move emission boundaries but
            // must never alter the byte sequence.
            assertEquals(whole, chopped)
        } finally {
            resetBreakers()
        }
    }

    /** Re-emits a delegate's stream at fixed byte offsets, ignoring SSE framing. */
    private class ChoppingDriver(
        private val delegate: ProviderDriver,
        private val chunkSize: Int,
    ) : ProviderDriver {
        override suspend fun chat(
            provider: ProviderEntry,
            apiKey: String,
            body: JsonObject,
            stream: Boolean,
        ): DriverOutcome {
            val outcome = delegate.chat(provider, apiKey, body, stream)
            if (outcome !is DriverOutcome.Stream) return outcome
            return DriverOutcome.Stream(
                flow {
                    val all = ByteArrayOutputStream()
                    outcome.events.collect { all.write(it) }
                    val bytes = all.toByteArray()
                    var i = 0
                    while (i < bytes.size) {
                        val end = minOf(i + chunkSize, bytes.size)
                        emit(bytes.copyOfRange(i, end))
                        i = end
                    }
                },
            )
        }

        override suspend fun embeddings(
            provider: ProviderEntry,
            apiKey: String,
            body: JsonObject,
        ): DriverOutcome = delegate.embeddings(provider, apiKey, body)
    }

    // ------------------------------------------------------------ legacy shim

    @Test
    fun `an array-form prompt is joined rather than silently dropped`() {
        resetBreakers()
        val r = request(
            "POST", "/v1/completions",
            """{"model":"llama-3.3-70b","prompt":["Summarize:","alpha"]}""",
            headers = mapOf(AsomHeaders.POLICY to "cheapest"),
        )
        assertEquals(200, r.statusCode())
        assertEquals(
            "fake:trainy-ai/llama-3.3-70b:Summarize:\nalpha",
            json(r)["choices"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.contentOrNull,
        )
    }

    @Test
    fun `a token-array prompt fails loudly instead of egressing an empty message`() {
        resetBreakers()
        val before = ledger.all().size
        val r = request(
            "POST", "/v1/completions",
            """{"model":"llama-3.3-70b","prompt":[[1,2,3]]}""",
            headers = mapOf(AsomHeaders.POLICY to "cheapest"),
        )
        assertEquals(501, r.statusCode())
        assertEquals("UNSUPPORTED_BY_DRIVER", errorCode(r))
        assertEquals(Egress.LOCAL, ledger.all().drop(before).single().egress)
    }

    // ---------------------------------------------------------- §5_4 contract

    @Test
    fun `models carries the §5_4 egress header on success and on 401`() {
        val ok = request("GET", "/v1/models")
        assertEquals(200, ok.statusCode())
        assertEquals("local", ok.headers().firstValue(AsomHeaders.EGRESS).get())

        val denied = request("GET", "/v1/models", token = null)
        assertEquals(401, denied.statusCode())
        assertEquals("local", denied.headers().firstValue(AsomHeaders.EGRESS).get())
    }

    @Test
    fun `an internal exception message never reaches the error envelope`() {
        val r = request("POST", "/v1/chat/completions", "{not json")
        assertEquals(400, r.statusCode())
        val message = json(r)["error"]!!.jsonObject["message"]!!.jsonPrimitive.contentOrNull
        assertEquals("malformed request", message)
    }
}
