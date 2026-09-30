package xyz.mdhv.asom.lab.conformance

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomException
import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.contract.openai.Usage
import xyz.mdhv.asom.routing.CooldownRegistry
import xyz.mdhv.asom.routing.LatencyTracker
import xyz.mdhv.asom.server.AsomServer
import xyz.mdhv.asom.server.AsomServerConfig
import xyz.mdhv.asom.server.auth.InMemoryTokenRegistry
import xyz.mdhv.asom.server.driver.DriverOutcome
import xyz.mdhv.asom.server.driver.FakeDriver
import xyz.mdhv.asom.server.driver.ProviderDriver
import xyz.mdhv.asom.server.keys.InMemoryKeyProvider
import xyz.mdhv.asom.server.ledger.InMemoryLedger

/** The clock every cooldown and every routed vector uses (LAB_SPEC 3.6, `fixedNow`). */
const val FIXED_NOW: Long = 1_000_000_000_000L

/** Every provider key in a server-driven vector is `KEY_MARKER_PREFIX + <provider>` (the key-leak law, LAB_SPEC 3.7). */
const val KEY_MARKER_PREFIX: String = "sk-LAB-SECRET-"

const val TOKEN_OK = "w-token"
const val TOKEN_REVOKED = "w-revoked"

/**
 * A lab `ProviderDriver` (LAB_SPEC 3.6 step 3): delegates to [fake], or replays a scripted outcome per provider
 * (a JSON body with usage, a stream of byte chunks, an error status, or a thrown `AsomException`).
 */
class ScriptedDriver(
    private val fake: FakeDriver,
    private val scripts: Map<String, JsonObject> = emptyMap(),
    private val defaultScript: JsonObject? = null,
) : ProviderDriver {

    override suspend fun chat(provider: ProviderEntry, apiKey: String, body: JsonObject, stream: Boolean): DriverOutcome {
        val script = scripts[provider.id] ?: defaultScript ?: return fake.chat(provider, apiKey, body, stream)
        return replay(script)
    }

    override suspend fun embeddings(provider: ProviderEntry, apiKey: String, body: JsonObject): DriverOutcome {
        val script = scripts[provider.id] ?: defaultScript ?: return fake.embeddings(provider, apiKey, body)
        return replay(script)
    }

    private fun replay(script: JsonObject): DriverOutcome = when (val kind = script.str("kind")) {
        "json" -> {
            val usage = script.objOrNull("usage")?.let {
                Usage(it.long("prompt_tokens"), it.long("completion_tokens"), it.long("total_tokens"))
            }
            DriverOutcome.Json(200, script.obj("body"), usage)
        }
        "stream" -> {
            val chunks = streamChunks(script)
            DriverOutcome.Stream(flow { chunks.forEach { emit(it) } })
        }
        "error" -> {
            val status = script.long("status").toInt()
            DriverOutcome.Error(status, script.str("bodyText"), retryable = status == 429 || status >= 500)
        }
        "throw" -> throw AsomException(AsomErrorCode.valueOf(script.str("code")), script.strOrNull("message") ?: "scripted")
        else -> throw LawViolation("unknown scripted driver kind '$kind'")
    }

    companion object {
        /** The chunks of a `stream` script: `chunksB64`, or `dataB64` cut into `chunkSize`-byte pieces. */
        fun streamChunks(script: JsonObject): List<ByteArray> {
            script.arrOrNull("chunksB64")?.let { arr -> return arr.map { unb64((it as kotlinx.serialization.json.JsonPrimitive).content) } }
            val all = unb64(script.str("dataB64"))
            val size = script.long("chunkSize").toInt()
            return all.toList().chunked(size).map { it.toByteArray() }
        }
    }
}

class HttpResult(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray?,
    /** True when the body did not arrive completely (a stream cut mid-flight). */
    val bodyIncomplete: Boolean,
) {
    val contentType: String get() = headers["content-type"].orEmpty()
    val isEventStream: Boolean get() = contentType.startsWith("text/event-stream")
    val xAsom: Map<String, String> get() = headers.filterKeys { it.startsWith("x-asom-") }
}

/** One request against a fresh real `AsomServer` on `127.0.0.1:<ephemeral>`; test scope only (LAB_SPEC R5). */
class ServerHarness(
    catalogue: Catalogue,
    val keys: Map<String, String>,
    driver: ProviderDriver,
    cooling: List<String> = emptyList(),
    private val client: HttpClient = sharedClient,
) : AutoCloseable {
    val ledger = InMemoryLedger()
    private val tokens = InMemoryTokenRegistry().apply {
        issue(TOKEN_OK, "w.caller")
        issue(TOKEN_REVOKED, "w.revoked")
        revoke(TOKEN_REVOKED)
    }
    private val cooldowns = CooldownRegistry(clock = { FIXED_NOW }).also { c -> cooling.forEach { c.recordFailure(it) } }
    private val server = AsomServer(
        AsomServerConfig(
            port = 0,
            catalogue = { catalogue },
            tokens = tokens,
            keys = InMemoryKeyProvider(keys),
            drivers = { driver },
            ledger = ledger,
            cooldowns = cooldowns,
            latency = LatencyTracker(),
        ),
    )
    private var port = 0

    fun start(): ServerHarness {
        server.start(wait = false)
        port = kotlinx.coroutines.runBlocking { server.resolvedPort() }
        return this
    }

    override fun close() {
        server.stop()
    }

    /** `input.request`: `{method?, path, bearer?, headers?, body?}`. Bearer defaults to the valid token. */
    fun send(request: JsonObject): HttpResult {
        val method = request.strOrNull("method") ?: "POST"
        val path = request.str("path")
        val b = HttpRequest.newBuilder(URI("http://${Asom.BIND_HOST}:$port$path")).timeout(Duration.ofSeconds(15))
        val bearer = if ("bearer" in request) request.strOrNull("bearer") else TOKEN_OK
        if (bearer != null) b.header("Authorization", "Bearer $bearer")
        request.objOrNull("headers")?.forEach { (k, v) -> b.header(k, (v as kotlinx.serialization.json.JsonPrimitive).content) }
        if (method == "GET") b.GET()
        else {
            b.header("Content-Type", "application/json")
            b.POST(HttpRequest.BodyPublishers.ofString(request["body"]?.toString() ?: ""))
        }
        var info: HttpResponse.ResponseInfo? = null
        val handler = HttpResponse.BodyHandler<ByteArray> { ri ->
            info = ri
            HttpResponse.BodySubscribers.ofByteArray()
        }
        var body: ByteArray? = null
        var incomplete = false
        try {
            body = client.send(b.build(), handler).body()
        } catch (e: IOException) {
            incomplete = true
        }
        val ri = info ?: throw LawViolation("no response head received")
        val headers = LinkedHashMap<String, String>()
        ri.headers().map().forEach { (k, v) -> headers[k.lowercase()] = v.first() }
        return HttpResult(ri.statusCode(), headers, body, incomplete)
    }

    /**
     * Waits until the ledger holds at least [minRows] rows and has stopped growing (a streamed row is written
     * after the client has seen the last byte, so an immediate read is a race; found while building this harness).
     */
    fun awaitRows(minRows: Int, timeoutMs: Long = 5_000, settleMs: Long = 250): List<RouteRecord> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (ledger.all().size < minRows && System.currentTimeMillis() < deadline) Thread.sleep(10)
        var last = ledger.all().size
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() - stableSince < settleMs) {
            Thread.sleep(20)
            val now = ledger.all().size
            if (now != last) {
                last = now
                stableSince = System.currentTimeMillis()
            }
        }
        return ledger.all()
    }

    companion object {
        private val sharedClient: HttpClient by lazy {
            HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build()
        }

        fun catalogueFor(input: JsonObject): Catalogue {
            val spec = input["catalogue"]
            return if (spec == null || spec is JsonNull || (spec is kotlinx.serialization.json.JsonPrimitive && spec.content == "fixture")) {
                CatalogueParser.parse(Repo.fixtureCatalogue.readUtf8())
            } else {
                CatalogueParser.parse(spec.jsonObject.toString())
            }
        }

        fun keyMap(input: JsonObject): Map<String, String> =
            input.strList("keys").associateWith { "$KEY_MARKER_PREFIX$it" }

        fun fakeFor(input: JsonObject): FakeDriver {
            val fake = FakeDriver { FIXED_NOW }
            val cfg = input.objOrNull("driver")?.objOrNull("fake") ?: return fake
            cfg.objOrNull("failWith")?.forEach { (p, s) -> fake.failWith(p, (s as kotlinx.serialization.json.JsonPrimitive).content.toInt()) }
            cfg.objOrNull("failMidStream")?.forEach { (p, n) -> fake.failMidStream(p, (n as kotlinx.serialization.json.JsonPrimitive).content.toInt()) }
            if (cfg.bool("omitStreamUsage")) fake.omitStreamUsage = true
            return fake
        }

        fun driverFor(input: JsonObject): ScriptedDriver {
            val d = input.objOrNull("driver")
            val scripts = d?.objOrNull("scripts")?.mapValues { it.value.jsonObject } ?: emptyMap()
            return ScriptedDriver(fakeFor(input), scripts, d?.objOrNull("default"))
        }
    }
}

/** A finished exchange: the response and every ledger row the request appended, in order. */
class Exchange(val http: HttpResult, val rows: List<RouteRecord>)

/** Row projection used by every server-driven vector: `ts` and `latencyMs` are ignored (LAB_SPEC 3.6). */
fun rowJson(r: RouteRecord): JsonObject = buildJsonObject {
    put("callerPkg", r.callerPkg)
    put("requestedModel", r.requestedModel)
    put("servedProvider", jsonStringOrNull(r.servedProvider))
    put("servedModel", jsonStringOrNull(r.servedModel))
    put("egress", r.egress.wire)
    put("bytesOut", r.bytesOut)
    put("tokensIn", jsonLongOrNull(r.tokensIn))
    put("tokensOut", jsonLongOrNull(r.tokensOut))
    put("costEst", jsonStringOrNull(r.costEst?.toString()))
    put("costBasis", r.costBasis.wire)
    put("status", r.status)
}

/**
 * The laws that hold on every server-driven exchange, and that [counters] records (non-vacuity):
 *  - no X-Asom-* header outside the four frozen echo headers (the contract is frozen);
 *  - non-stream: the response's X-Asom-* headers equal `row.toEchoHeaders()` of the terminal row exactly;
 *  - stream (round-3 CLOSURE-3, conservative reading): the response carries only headers the frozen contract
 *    defines at commit time (Served-By, Egress, never cost); they equal the same-named headers of the FINAL row;
 *  - no provider key marker in any response byte, header or ledger row (LAB_SPEC 3.7).
 */
fun verifyExchangeLaws(x: Exchange, counters: (String) -> Unit) {
    val frozen = setOf(AsomHeaders.SERVED_BY, AsomHeaders.EGRESS, AsomHeaders.COST_EST, AsomHeaders.COST_BASIS).map { it.lowercase() }.toSet()
    val sent = x.http.xAsom
    val unknown = sent.keys - frozen
    if (unknown.isNotEmpty()) throw LawViolation("response carries X-Asom headers outside the frozen four: $unknown")
    counters("no-new-headers")

    val terminal = x.rows.lastOrNull() ?: throw LawViolation("the request appended no ledger row")
    val rowHeaders = terminal.toEchoHeaders().mapKeys { it.key.lowercase() }
    if (!x.http.isEventStream) {
        if (sent != rowHeaders) throw LawViolation("Invariant 9: response headers $sent != terminal row's echo headers $rowHeaders")
        counters("header-equal-nonstream")
    } else {
        val commitTime = setOf(AsomHeaders.SERVED_BY, AsomHeaders.EGRESS).map { it.lowercase() }.toSet()
        if (!commitTime.containsAll(sent.keys)) throw LawViolation("a streamed response carries headers not defined at commit time: ${sent.keys - commitTime}")
        if (AsomHeaders.EGRESS.lowercase() !in sent) throw LawViolation("a streamed response lacks ${AsomHeaders.EGRESS}")
        if (sent != rowHeaders.filterKeys { it in commitTime }) {
            throw LawViolation("committed stream headers $sent disagree with the final row's served-by/egress ${rowHeaders.filterKeys { it in commitTime }}")
        }
        counters("header-commit-stream")
    }

    val bodyText = x.http.body?.toString(Charsets.ISO_8859_1).orEmpty()
    val haystack = buildString {
        append(bodyText)
        x.http.headers.forEach { (k, v) -> append(k).append(':').append(v).append('\n') }
        x.rows.forEach { append(it.toString()).append('\n') }
    }
    if (KEY_MARKER_PREFIX in haystack) throw LawViolation("a provider key marker leaked into a response byte, header or ledger row")
    counters("key-leak-checks")
}

/** Runs one request and returns the exchange with the rows the request appended (waits for streamed rows). */
fun runExchange(input: JsonObject, expectedRows: Int?, wrap: (ProviderDriver) -> ProviderDriver = { it }): Exchange {
    ServerHarness(ServerHarness.catalogueFor(input), ServerHarness.keyMap(input), wrap(ServerHarness.driverFor(input)), input.strList("cooling")).start().use { h ->
        val http = h.send(input.obj("request"))
        val rows = h.awaitRows(expectedRows ?: 1)
        return Exchange(http, rows)
    }
}
