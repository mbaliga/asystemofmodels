package xyz.mdhv.asom.server

import java.io.ByteArrayOutputStream
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.catalogue.ProviderKind
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomException
import xyz.mdhv.asom.contract.Capabilities
import xyz.mdhv.asom.contract.CostBasis
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.contract.openai.ErrorBody
import xyz.mdhv.asom.contract.openai.ErrorEnvelope
import xyz.mdhv.asom.contract.openai.ModelListResponse
import xyz.mdhv.asom.contract.openai.ModelObject
import xyz.mdhv.asom.contract.openai.Usage
import xyz.mdhv.asom.routing.CooldownRegistry
import xyz.mdhv.asom.routing.LatencyTracker
import xyz.mdhv.asom.routing.RouteQuery
import xyz.mdhv.asom.routing.Router
import xyz.mdhv.asom.server.auth.AuthResult
import xyz.mdhv.asom.server.auth.TokenValidator
import xyz.mdhv.asom.server.driver.ProviderDriver
import xyz.mdhv.asom.server.keys.KeyProvider
import xyz.mdhv.asom.server.ledger.LedgerSink
import xyz.mdhv.asom.server.util.estimateTokens
import xyz.mdhv.asom.server.util.usageCost
import xyz.mdhv.asom.server.util.usageFrom
import xyz.mdhv.asom.contract.AsomHeaders

/**
 * Live-state hook for the P8 notification (idle/streaming/provider) — a
 * seam, not a new egress class: purely local UI signal, never ledgered.
 */
fun interface ActivityListener {
    fun onActivity(busy: Boolean, providerId: String?)
}

class AsomServerConfig(
    val port: Int = Asom.DEFAULT_PORT,
    val catalogue: () -> Catalogue,
    val tokens: TokenValidator,
    val keys: KeyProvider,
    val drivers: (ProviderKind) -> ProviderDriver,
    val ledger: LedgerSink,
    val cooldowns: CooldownRegistry = CooldownRegistry(),
    val latency: LatencyTracker = LatencyTracker(),
    val defaultPolicy: Policy = Policy.AUTO,
    val clock: () -> Long = System::currentTimeMillis,
    val activity: ActivityListener = ActivityListener { _, _ -> },
)

/**
 * The asom daemon's HTTP server (§5.2). Ktor CIO. Binds [Asom.BIND_HOST]
 * (127.0.0.1) ONLY — the bind host is hardcoded, not configurable
 * (invariant §1.2 by construction).
 */
class AsomServer(private val config: AsomServerConfig) {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val router = Router(
        catalogue = config.catalogue,
        keys = { config.keys.keyFor(it) != null },
        latency = config.latency,
        cooldowns = config.cooldowns,
        defaultPolicy = config.defaultPolicy,
        hasLocalEngine = false, // v1 law (§2)
    )

    private val pipeline = RoutePipeline(
        router = router,
        keys = config.keys,
        drivers = config.drivers,
        cooldowns = config.cooldowns,
        latency = config.latency,
        clock = config.clock,
    )

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start(wait: Boolean = false) {
        engine = embeddedServer(CIO, host = Asom.BIND_HOST, port = config.port) { module() }
            .also { it.start(wait = wait) }
    }

    /** Actual bound port (differs from config when port 0 is used in tests). */
    suspend fun resolvedPort(): Int =
        engine!!.engine.resolvedConnectors().first().port

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }

    // ---------------------------------------------------------------- module

    private fun Application.module() {
        routing {
            post("/v1/chat/completions") { handleChatLike(call, legacy = false) }
            post("/v1/completions") { handleChatLike(call, legacy = true) }
            post("/v1/embeddings") { handleEmbeddings(call) }
            get("/v1/models") { handleModels(call) }
            get("/admin/health") { handleHealth(call) }
            get("/admin/catalogue") { handleAdminCatalogue(call) }
        }
    }

    // ------------------------------------------------------------------ auth

    /** @throws AsomException NOT_PAIRED / TOKEN_REVOKED. */
    private fun authenticate(call: ApplicationCall): String {
        val header = call.request.headers["Authorization"]
            ?: throw AsomException(AsomErrorCode.NOT_PAIRED, "missing Authorization bearer token; pair with asom first")
        val token = header.removePrefix("Bearer ").trim()
        if (token.isEmpty() || token == header) {
            throw AsomException(AsomErrorCode.NOT_PAIRED, "malformed Authorization header; expected 'Bearer <token>'")
        }
        return when (val result = config.tokens.validate(token)) {
            is AuthResult.Valid -> result.callerPkg
            AuthResult.Revoked -> throw AsomException(AsomErrorCode.TOKEN_REVOKED, "this pairing was revoked")
            AuthResult.Unknown -> throw AsomException(AsomErrorCode.NOT_PAIRED, "unknown token; pair with asom first")
        }
    }

    // ---------------------------------------------------------- chat + shim

    private suspend fun handleChatLike(call: ApplicationCall, legacy: Boolean) {
        val t0 = config.clock()
        var caller = "unknown"
        var requestedModel = ""
        var egressed = false
        var responseCommitted = false
        config.activity.onActivity(true, null)
        try {
            caller = authenticate(call)
            val raw = parseBody(call.receiveText())
            val chatBody = if (legacy) legacyToChat(raw) else raw
            requestedModel = chatBody.stringField("model")
                ?: throw AsomException(AsomErrorCode.MODEL_UNKNOWN, "request body has no 'model' field")
            val stream = chatBody["stream"]?.jsonPrimitive?.booleanOrNull == true
            val query = routeQuery(call, requestedModel)
            // §5.9: for usage-based cost on streams, inject include_usage when absent.
            val upstreamBody = if (stream) withIncludeUsage(chatBody) else chatBody
            val bytesOut = upstreamBody.toString().toByteArray().size.toLong()
            val onAttempt = { attempt: RoutePipeline.Attempt ->
                egressed = true
                config.ledger.append(attemptRecord(caller, requestedModel, attempt))
            }

            when (val result = pipeline.execute(query, upstreamBody, stream, Operation.CHAT, onAttempt)) {
                is RoutePipeline.Result.Json -> {
                    val usage = result.outcome.usage ?: usageFrom(result.outcome.body)
                    val record = completedRecord(
                        t0, caller, requestedModel, result.candidate.provider.id, result.candidate.modelId,
                        bytesOut, usage, result.candidate.pricing?.let { usageCost(it, usage ?: Usage()) },
                        heuristicIn = estimateTokens(upstreamBody), status = 200,
                        usagePresent = usage != null, pricing = result.candidate,
                    )
                    config.ledger.append(record)
                    applyEchoHeaders(call, record)
                    val bodyOut = if (legacy) chatToLegacy(result.outcome.body) else result.outcome.body
                    call.respondText(bodyOut.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                }
                is RoutePipeline.Result.Stream -> {
                    config.activity.onActivity(true, result.candidate.provider.id)
                    egressed = true
                    // Echo headers commit BEFORE the body (§5.9). Cost is not
                    // yet derivable at commit time, so cost headers are
                    // omitted (§5.4 allows this); the ledger row — same
                    // record, finalized post-stream — carries usage cost and
                    // the terminal status the stream actually reached.
                    val commitView = RouteRecord(
                        ts = config.clock(), callerPkg = caller, requestedModel = requestedModel,
                        servedProvider = result.candidate.provider.id, servedModel = result.candidate.modelId,
                        egress = Egress.CLOUD, bytesOut = bytesOut,
                        latencyMs = config.clock() - t0, status = 200,
                    )
                    applyEchoHeaders(call, commitView)
                    call.response.header("Cache-Control", "no-cache")
                    responseCommitted = true
                    var tailUsage: Usage? = null
                    var outChars = 0L
                    var terminalStatus = 200
                    // Both transforms need whole SSE events; the openai-compat
                    // driver emits raw socket reads (§5.9 byte pass-through),
                    // so re-frame first. The byte SEQUENCE is unchanged — only
                    // the emission boundaries are.
                    val framed = reframeSse(result.outcome.events)
                    val events = if (legacy) chunksToLegacy(framed) else framed
                    try {
                        call.respondBytesWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                            events.collect { bytes ->
                                val seen = scanEvents(bytes)
                                seen.usage?.let { tailUsage = it }
                                outChars += seen.outChars
                                writeFully(bytes)
                                flush()
                            }
                        }
                    } catch (e: Throwable) {
                        // 200 + echo headers are already on the wire, so no
                        // error status is reachable — but the row must still
                        // say what really happened (§1.3).
                        terminalStatus =
                            if (e is CancellationException || e is ChannelWriteException) CLIENT_CLOSED_REQUEST
                            else 502
                        throw e
                    } finally {
                        val usage = tailUsage
                        // No suspension point below, so this runs even when the
                        // call coroutine is cancelled mid-stream.
                        config.ledger.append(
                            completedRecord(
                                t0, caller, requestedModel, result.candidate.provider.id, result.candidate.modelId,
                                bytesOut, usage, result.candidate.pricing?.let { p -> usage?.let { usageCost(p, it) } },
                                heuristicIn = estimateTokens(upstreamBody), status = terminalStatus,
                                usagePresent = usage != null, pricing = result.candidate,
                                heuristicOut = (outChars / 4).coerceAtLeast(if (outChars > 0) 1 else 0),
                            ),
                        )
                    }
                }
                is RoutePipeline.Result.UpstreamError -> {
                    // Fatal upstream error relayed verbatim; bytes DID leave
                    // the device — egress cloud, ledgered (§1.3).
                    egressed = true
                    val record = RouteRecord(
                        ts = config.clock(), callerPkg = caller, requestedModel = requestedModel,
                        servedProvider = result.candidate.provider.id, servedModel = result.candidate.modelId,
                        egress = Egress.CLOUD, bytesOut = bytesOut,
                        latencyMs = config.clock() - t0, status = result.outcome.status,
                    )
                    config.ledger.append(record)
                    applyEchoHeaders(call, record)
                    call.respondText(
                        result.outcome.bodyText, ContentType.Application.Json,
                        HttpStatusCode.fromValue(result.outcome.status),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AsomException) {
            if (!responseCommitted) respondRoutedError(call, e, t0, caller, requestedModel, egressed)
        } catch (e: Exception) {
            // Once the stream committed, its own finally already wrote the row
            // and no status is reachable — a second respond would throw and a
            // second row would contradict the headers already sent (§1.9).
            if (!responseCommitted) respondBadRequest(call, e, t0, caller, requestedModel, egressed)
        } finally {
            config.activity.onActivity(false, null)
        }
    }

    // ------------------------------------------------------------ embeddings

    private suspend fun handleEmbeddings(call: ApplicationCall) {
        val t0 = config.clock()
        var caller = "unknown"
        var requestedModel = ""
        var egressed = false
        config.activity.onActivity(true, null)
        try {
            caller = authenticate(call)
            val body = parseBody(call.receiveText())
            requestedModel = body.stringField("model")
                ?: throw AsomException(AsomErrorCode.MODEL_UNKNOWN, "request body has no 'model' field")
            val query = routeQuery(call, requestedModel)
            val bytesOut = body.toString().toByteArray().size.toLong()
            val onAttempt = { attempt: RoutePipeline.Attempt ->
                egressed = true
                config.ledger.append(attemptRecord(caller, requestedModel, attempt))
            }

            when (val result = pipeline.execute(query, body, stream = false, op = Operation.EMBEDDINGS, onAttempt)) {
                is RoutePipeline.Result.Json -> {
                    val usage = result.outcome.usage ?: usageFrom(result.outcome.body)
                    val record = completedRecord(
                        t0, caller, requestedModel, result.candidate.provider.id, result.candidate.modelId,
                        bytesOut, usage, result.candidate.pricing?.let { usageCost(it, usage ?: Usage()) },
                        heuristicIn = estimateTokens(body), status = 200,
                        usagePresent = usage != null, pricing = result.candidate,
                    )
                    config.ledger.append(record)
                    applyEchoHeaders(call, record)
                    call.respondText(result.outcome.body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                }
                is RoutePipeline.Result.Stream -> error("embeddings never stream")
                is RoutePipeline.Result.UpstreamError -> {
                    egressed = true
                    val record = RouteRecord(
                        ts = config.clock(), callerPkg = caller, requestedModel = requestedModel,
                        servedProvider = result.candidate.provider.id, servedModel = result.candidate.modelId,
                        egress = Egress.CLOUD, bytesOut = bytesOut,
                        latencyMs = config.clock() - t0, status = result.outcome.status,
                    )
                    config.ledger.append(record)
                    applyEchoHeaders(call, record)
                    call.respondText(
                        result.outcome.bodyText, ContentType.Application.Json,
                        HttpStatusCode.fromValue(result.outcome.status),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AsomException) {
            respondRoutedError(call, e, t0, caller, requestedModel, egressed)
        } catch (e: Exception) {
            respondBadRequest(call, e, t0, caller, requestedModel, egressed)
        } finally {
            config.activity.onActivity(false, null)
        }
    }

    // ---------------------------------------------------------------- models

    private suspend fun handleModels(call: ApplicationCall) {
        val t0 = config.clock()
        var caller = "unknown"
        try {
            caller = authenticate(call)
            val cat = config.catalogue()
            val keyedProviders = cat.providers.filter { config.keys.keyFor(it.id) != null }
            // Concrete models with a key present, tagged by serving providers (§5.2).
            val concrete = keyedProviders
                .flatMap { p -> p.models.map { it to p.id } }
                .groupBy({ it.first }, { it.second })
                .toSortedMap()
                .map { (model, providers) ->
                    ModelObject(id = model, ownedBy = providers.sorted().joinToString(","))
                }
            val virtual = Policy.VIRTUAL_MODELS.sorted().map {
                ModelObject(id = it, ownedBy = "asom-virtual")
            }
            val response = ModelListResponse(data = concrete + virtual)
            // §5.4 headers are owed on EVERY /v1/* response. The listing is
            // served from the in-memory catalogue, so egress is genuinely
            // local and no ledger row is owed (§1.3 covers network events).
            applyEchoHeaders(call, localRecord(t0, caller, 200))
            call.respondText(
                json.encodeToString(ModelListResponse.serializer(), response),
                ContentType.Application.Json, HttpStatusCode.OK,
            )
        } catch (e: AsomException) {
            applyEchoHeaders(call, localRecord(t0, caller, e.code.httpStatus))
            respondError(call, e.code, e.message)
        }
    }

    // ----------------------------------------------------------------- admin

    private suspend fun handleHealth(call: ApplicationCall) {
        try {
            authenticate(call)
            val body = buildJsonObject {
                put("status", "ok")
                put("version", Asom.VERSION)
                put("hasLocalEngine", false)
                put("catalogueVersion", config.catalogue().version)
            }
            call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        } catch (e: AsomException) {
            respondError(call, e.code, e.message)
        }
    }

    private suspend fun handleAdminCatalogue(call: ApplicationCall) {
        try {
            authenticate(call)
            val cat = config.catalogue()
            // Merged catalogue + live cooldown state (§5.2). Key PRESENCE only —
            // key material never appears in any API (invariant §1.4).
            val body = buildJsonObject {
                put("catalogue", json.encodeToJsonElement(Catalogue.serializer(), cat))
                putJsonObject("providers") {
                    for (p in cat.providers) {
                        putJsonObject(p.id) {
                            put("keyPresent", config.keys.keyFor(p.id) != null)
                            put("cooling", config.cooldowns.isCooling(p.id))
                            config.cooldowns.coolingUntil(p.id)?.let { put("coolingUntil", it) }
                            config.latency.ewma(p.id)?.let { put("latencyEwmaMs", it) }
                        }
                    }
                }
                put("capabilities", json.encodeToJsonElement(Capabilities.serializer(), Capabilities.v1(cat.version)))
            }
            call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        } catch (e: AsomException) {
            respondError(call, e.code, e.message)
        }
    }

    // --------------------------------------------------------------- helpers

    private fun parseBody(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun routeQuery(call: ApplicationCall, model: String): RouteQuery {
        val policyHeader = call.request.headers[AsomHeaders.POLICY]?.let {
            Policy.fromWire(it.trim())
                ?: throw BadRequest("unknown ${AsomHeaders.POLICY} value '$it'")
        }
        val fallback = call.request.headers[AsomHeaders.FALLBACK]
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val noTrain = call.request.headers[AsomHeaders.NO_TRAIN]?.trim()?.equals("true", ignoreCase = true) == true
        return RouteQuery(model = model, policyHeader = policyHeader, fallback = fallback, noTrain = noTrain)
    }

    private fun completedRecord(
        t0: Long,
        caller: String,
        requestedModel: String,
        provider: String,
        model: String,
        bytesOut: Long,
        usage: Usage?,
        usageBasedCost: Double?,
        heuristicIn: Long,
        status: Int,
        usagePresent: Boolean,
        pricing: xyz.mdhv.asom.routing.Candidate,
        heuristicOut: Long = 0,
    ): RouteRecord {
        // §5.4: cost with basis `usage` when the provider reported usage;
        // otherwise heuristic (chars/4) when pricing is known; otherwise none.
        val tokensIn = usage?.promptTokens ?: heuristicIn
        // Output is priced 3-5x input, so counting it as zero when a provider
        // drops stream_options would understate the row by ~100x.
        val tokensOut = usage?.completionTokens ?: heuristicOut.takeIf { it > 0 }
        val (cost, basis) = when {
            usagePresent && usageBasedCost != null -> usageBasedCost to CostBasis.USAGE
            pricing.pricing != null ->
                usageCost(pricing.pricing, Usage(tokensIn, tokensOut ?: 0, 0)) to CostBasis.HEURISTIC
            else -> null to CostBasis.NONE
        }
        return RouteRecord(
            ts = config.clock(), callerPkg = caller, requestedModel = requestedModel,
            servedProvider = provider, servedModel = model, egress = Egress.CLOUD,
            bytesOut = bytesOut, tokensIn = tokensIn, tokensOut = tokensOut,
            costEst = cost, costBasis = basis,
            latencyMs = config.clock() - t0, status = status,
        )
    }

    /**
     * A candidate that received the request body and failed retryably. The
     * driver transmits before it can classify the reply, so this is a network
     * event in its own right and owes its own row (§1.3) — the request's
     * terminal row describes only the candidate that answered.
     */
    private fun attemptRecord(
        caller: String,
        requestedModel: String,
        attempt: RoutePipeline.Attempt,
    ): RouteRecord = RouteRecord(
        ts = config.clock(), callerPkg = caller, requestedModel = requestedModel,
        servedProvider = attempt.candidate.provider.id, servedModel = attempt.candidate.modelId,
        egress = Egress.CLOUD, bytesOut = attempt.bytesOut,
        latencyMs = attempt.latencyMs, status = attempt.status,
    )

    private fun localRecord(t0: Long, caller: String, status: Int): RouteRecord = RouteRecord(
        ts = config.clock(), callerPkg = caller, requestedModel = "",
        egress = Egress.LOCAL, latencyMs = config.clock() - t0, status = status,
    )

    private fun applyEchoHeaders(call: ApplicationCall, record: RouteRecord) {
        record.toEchoHeaders().forEach { (name, value) -> call.response.header(name, value) }
    }

    /**
     * Routed failure: typed envelope + echo headers + ledger row (§7).
     *
     * [egressed] distinguishes the two sources of the same code: a plan-time
     * failure never touched the network, while ALL_PROVIDERS_COOLING raised
     * after the pipeline contacted candidates did. Stamping `local` on the
     * latter is a positive falsehood in the artifact the product exists to
     * produce. The bytes themselves are accounted on the per-attempt rows, so
     * this row carries none.
     */
    private suspend fun respondRoutedError(
        call: ApplicationCall,
        e: AsomException,
        t0: Long,
        caller: String,
        requestedModel: String,
        egressed: Boolean,
    ) {
        val record = RouteRecord(
            ts = config.clock(), callerPkg = caller, requestedModel = requestedModel,
            egress = if (egressed) Egress.CLOUD else Egress.LOCAL,
            latencyMs = config.clock() - t0, status = e.code.httpStatus,
        )
        config.ledger.append(record)
        applyEchoHeaders(call, record)
        respondError(call, e.code, e.message)
    }

    private suspend fun respondBadRequest(
        call: ApplicationCall,
        e: Exception,
        t0: Long,
        caller: String,
        requestedModel: String,
        egressed: Boolean,
    ) {
        val record = RouteRecord(
            ts = config.clock(), callerPkg = caller, requestedModel = requestedModel,
            egress = if (egressed) Egress.CLOUD else Egress.LOCAL,
            latencyMs = config.clock() - t0, status = 400,
        )
        config.ledger.append(record)
        applyEchoHeaders(call, record)
        // Only messages authored here reach the caller: an arbitrary internal
        // exception message can carry key material into a third-party app's
        // response string (§1.4).
        val envelope = ErrorEnvelope(
            ErrorBody(
                message = (e as? BadRequest)?.message ?: "malformed request",
                type = "invalid_request_error",
            ),
        )
        call.respondText(
            json.encodeToString(ErrorEnvelope.serializer(), envelope),
            ContentType.Application.Json, HttpStatusCode.BadRequest,
        )
    }

    private suspend fun respondError(call: ApplicationCall, code: AsomErrorCode, message: String) {
        val envelope = ErrorEnvelope(
            ErrorBody(message = message, type = code.openAiType, code = code.name),
        )
        call.respondText(
            json.encodeToString(ErrorEnvelope.serializer(), envelope),
            ContentType.Application.Json, HttpStatusCode.fromValue(code.httpStatus),
        )
    }

    // ------------------------------------------------- body/stream transforms

    /** §5.9: inject stream_options.include_usage=true when absent. */
    private fun withIncludeUsage(body: JsonObject): JsonObject {
        val existing = body["stream_options"] as? JsonObject
        if (existing?.get("include_usage")?.jsonPrimitive?.booleanOrNull == true) return body
        return buildJsonObject {
            body.forEach { (k, v) -> if (k != "stream_options") put(k, v) }
            putJsonObject("stream_options") {
                existing?.forEach { (k, v) -> if (k != "include_usage") put(k, v) }
                put("include_usage", true)
            }
        }
    }

    /**
     * Legacy /v1/completions request → chat body (§5.2 shim). `prompt` is the
     * one field the shim consumes rather than passing through verbatim, so an
     * untranslatable shape must 501 rather than silently become an empty
     * message that is still dispatched and billed.
     */
    private fun legacyToChat(body: JsonObject): JsonObject = buildJsonObject {
        body.forEach { (k, v) -> if (k != "prompt") put(k, v) }
        val prompt = when (val raw = body["prompt"]) {
            null, kotlinx.serialization.json.JsonNull -> ""
            is JsonPrimitive -> raw.content
            is kotlinx.serialization.json.JsonArray ->
                if (raw.all { it is JsonPrimitive && it.isString }) {
                    raw.joinToString("\n") { it.jsonPrimitive.content }
                } else {
                    throw AsomException(
                        AsomErrorCode.UNSUPPORTED_BY_DRIVER,
                        "the /v1/completions shim supports a string or string-array 'prompt' only",
                    )
                }
            else -> throw AsomException(
                AsomErrorCode.UNSUPPORTED_BY_DRIVER,
                "the /v1/completions shim supports a string or string-array 'prompt' only",
            )
        }
        put(
            "messages",
            kotlinx.serialization.json.buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    },
                )
            },
        )
    }

    /** Chat response → legacy text_completion response. */
    private fun chatToLegacy(chat: JsonObject): JsonObject = buildJsonObject {
        put("id", chat["id"] ?: JsonPrimitive("cmpl-asom"))
        put("object", "text_completion")
        put("created", chat["created"] ?: JsonPrimitive(0))
        put("model", chat["model"] ?: JsonPrimitive(""))
        put(
            "choices",
            kotlinx.serialization.json.buildJsonArray {
                val choices = chat["choices"] as? kotlinx.serialization.json.JsonArray
                choices?.forEachIndexed { i, c ->
                    val obj = c.jsonObject
                    val content = obj["message"]?.jsonObject?.get("content") as? JsonPrimitive
                    add(
                        buildJsonObject {
                            put("index", i)
                            put("text", content?.contentOrNull ?: "")
                            put("finish_reason", obj["finish_reason"] ?: JsonPrimitive("stop"))
                        },
                    )
                }
            },
        )
        chat["usage"]?.let { put("usage", it) }
    }

    /** Chat SSE chunk events → legacy text_completion chunk events. */
    private fun chunksToLegacy(events: Flow<ByteArray>): Flow<ByteArray> = events.map { bytes ->
        val text = bytes.toString(Charsets.UTF_8)
        val transformed = text.split("\n\n").joinToString("\n\n") { block ->
            if (!block.startsWith("data: ") || block.startsWith("data: [DONE]")) return@joinToString block
            val payload = block.removePrefix("data: ")
            try {
                val chunk = Json.parseToJsonElement(payload).jsonObject
                val delta = chunk["choices"]?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() }
                    ?.jsonObject
                val content = delta?.get("delta")?.jsonObject?.get("content") as? JsonPrimitive
                val legacyChunk = buildJsonObject {
                    put("id", chunk["id"] ?: JsonPrimitive("cmpl-asom"))
                    put("object", "text_completion")
                    put("created", chunk["created"] ?: JsonPrimitive(0))
                    put("model", chunk["model"] ?: JsonPrimitive(""))
                    put(
                        "choices",
                        kotlinx.serialization.json.buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("index", 0)
                                    put("text", content?.contentOrNull ?: "")
                                    put("finish_reason", delta?.get("finish_reason") ?: kotlinx.serialization.json.JsonNull)
                                },
                            )
                        },
                    )
                    chunk["usage"]?.let { put("usage", it) }
                }
                "data: $legacyChunk"
            } catch (e: Exception) {
                block
            }
        }
        transformed.toByteArray()
    }

    /**
     * Re-frames a driver's byte stream onto SSE event boundaries. The
     * openai-compat driver emits raw 8 KiB socket reads, so a `data:` event —
     * and any multi-byte codepoint inside it — can straddle two emissions;
     * everything downstream (the legacy transform, the usage tee) assumes
     * whole events. Only emission boundaries change: the concatenated byte
     * sequence is identical, preserving §5.9 pass-through.
     */
    private fun reframeSse(events: Flow<ByteArray>): Flow<ByteArray> = flow {
        val carry = ByteArrayOutputStream()
        events.collect { bytes ->
            carry.write(bytes)
            val buffered = carry.toByteArray()
            val cut = lastEventBoundary(buffered)
            if (cut > 0) {
                emit(buffered.copyOfRange(0, cut))
                carry.reset()
                carry.write(buffered, cut, buffered.size - cut)
            }
        }
        if (carry.size() > 0) emit(carry.toByteArray())
    }

    /** End index (exclusive) of the last complete SSE event, or 0 if none. */
    private fun lastEventBoundary(buf: ByteArray): Int {
        val lf = '\n'.code.toByte()
        val cr = '\r'.code.toByte()
        for (i in buf.size - 1 downTo 1) {
            if (buf[i] != lf) continue
            if (buf[i - 1] == lf) return i + 1
            if (i >= 2 && buf[i - 1] == cr && buf[i - 2] == lf) return i + 1
        }
        return 0
    }

    /** What one re-framed emission contributed to the stream's ledger row. */
    private class StreamScan(val usage: Usage?, val outChars: Long)

    /** Tee over the forwarded stream: usage + assistant text size (§5.9 cost). */
    private fun scanEvents(bytes: ByteArray): StreamScan {
        var usage: Usage? = null
        var outChars = 0L
        for (block in bytes.toString(Charsets.UTF_8).split("\n\n")) {
            val line = block.trim()
            if (!line.startsWith("data: ") || line.startsWith("data: [DONE]")) continue
            val obj = try {
                Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
            } catch (e: Exception) {
                continue // foreign payload — the tee must never break pass-through
            }
            usageFrom(obj)?.let { usage = it }
            val choice = (obj["choices"] as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull()?.jsonObject
            val text = choice?.get("delta")?.jsonObject?.get("content") as? JsonPrimitive
                ?: choice?.get("text") as? JsonPrimitive // legacy text_completion shape
            outChars += text?.contentOrNull?.length ?: 0
        }
        return StreamScan(usage, outChars)
    }

    /** A 400 whose message is authored here and therefore safe to echo. */
    private class BadRequest(message: String) : Exception(message)

    private companion object {
        /** Not an HTTP response code — a ledger-only marker for a caller that hung up. */
        const val CLIENT_CLOSED_REQUEST = 499
    }
}
