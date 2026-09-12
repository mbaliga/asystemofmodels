package xyz.mdhv.asom.server.driver

import java.io.BufferedReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomException
import xyz.mdhv.asom.contract.openai.ChatCompletionChunk
import xyz.mdhv.asom.contract.openai.ChatCompletionResponse
import xyz.mdhv.asom.contract.openai.ChatMessage
import xyz.mdhv.asom.contract.openai.Choice
import xyz.mdhv.asom.contract.openai.ChunkChoice
import xyz.mdhv.asom.contract.openai.Delta
import xyz.mdhv.asom.contract.openai.Usage

/**
 * Native Anthropic Messages API driver (§5.9): translates the text-chat
 * subset (`messages`/`system`, `max_tokens`, `temperature`, `top_p`, `stop`,
 * `stream`) and re-maps upstream SSE events to `chat.completion.chunk` shape.
 * Any field it cannot translate fails loudly BEFORE any bytes leave the
 * device: typed 501 UNSUPPORTED_BY_DRIVER.
 */
class AnthropicDriver(
    private val client: OkHttpClient = OpenAICompatDriver.defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ProviderDriver {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun chat(
        provider: ProviderEntry,
        apiKey: String,
        body: JsonObject,
        stream: Boolean,
    ): DriverOutcome {
        val anthropicBody = translate(body, stream) // throws UNSUPPORTED_BY_DRIVER pre-flight
        val model = body["model"]?.jsonPrimitive?.contentOrNull ?: "unknown"

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(provider.baseUrl.trimEnd('/') + "/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(anthropicBody.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                response.close()
                return@withContext DriverOutcome.Error(
                    status = response.code,
                    bodyText = text,
                    retryable = response.code == 429 || response.code >= 500,
                )
            }

            if (!stream) {
                val obj = Json.parseToJsonElement(response.body!!.string()).jsonObject
                val mapped = mapResponse(obj, model)
                val usage = mapUsage(obj["usage"] as? JsonObject)
                DriverOutcome.Json(
                    200,
                    Json.parseToJsonElement(json.encodeToString(ChatCompletionResponse.serializer(), mapped)).jsonObject,
                    usage,
                )
            } else {
                val streamBody = response.body!!
                DriverOutcome.Stream(
                    flow {
                        streamBody.charStream().buffered().use { reader ->
                            remapSse(reader, model) { emit(it) }
                        }
                    }.flowOn(Dispatchers.IO),
                )
            }
        }
    }

    override suspend fun embeddings(
        provider: ProviderEntry,
        apiKey: String,
        body: JsonObject,
    ): DriverOutcome = throw AsomException(
        AsomErrorCode.UNSUPPORTED_BY_DRIVER,
        "the anthropic driver has no embeddings endpoint",
    )

    // ------------------------------------------------------------ translate

    private fun translate(body: JsonObject, stream: Boolean): JsonObject {
        // Fail loudly on anything outside the §5.9 text-chat subset.
        val unsupported = body.keys - TRANSLATABLE_FIELDS
        if (unsupported.isNotEmpty()) {
            throw AsomException(
                AsomErrorCode.UNSUPPORTED_BY_DRIVER,
                "anthropic driver cannot translate field(s): ${unsupported.sorted().joinToString()}",
            )
        }

        val systemParts = mutableListOf<String>()
        (body["system"] as? JsonPrimitive)?.contentOrNull?.let { systemParts.add(it) }
        val messages = buildJsonArray {
            for (element in body["messages"]?.jsonArray ?: JsonArray(emptyList())) {
                val m = element.jsonObject
                val role = m["role"]?.jsonPrimitive?.contentOrNull
                val content = m["content"]
                if (content !is JsonPrimitive || !content.isString) {
                    throw AsomException(
                        AsomErrorCode.UNSUPPORTED_BY_DRIVER,
                        "anthropic driver supports plain string message content only",
                    )
                }
                when (role) {
                    "system" -> systemParts.add(content.content)
                    "user", "assistant" -> add(
                        buildJsonObject {
                            put("role", role)
                            put("content", content.content)
                        },
                    )
                    else -> throw AsomException(
                        AsomErrorCode.UNSUPPORTED_BY_DRIVER,
                        "anthropic driver cannot translate message role '$role'",
                    )
                }
            }
        }

        return buildJsonObject {
            put("model", body["model"] ?: JsonPrimitive("unknown"))
            put("messages", messages)
            if (systemParts.isNotEmpty()) put("system", systemParts.joinToString("\n"))
            // Anthropic requires max_tokens; default generously when absent.
            put("max_tokens", body["max_tokens"] ?: JsonPrimitive(4096))
            body["temperature"]?.let { put("temperature", it) }
            body["top_p"]?.let { put("top_p", it) }
            body["stop"]?.let { stop ->
                // OpenAI stop: string | array → Anthropic stop_sequences: array.
                put("stop_sequences", if (stop is JsonArray) stop else buildJsonArray { add(stop) })
            }
            if (stream) put("stream", true)
            // stream_options is asom-injected for §5.9 usage; Anthropic streams
            // always report usage, so it is consumed here, not forwarded.
        }
    }

    // ------------------------------------------------------------- responses

    private fun mapResponse(obj: JsonObject, model: String): ChatCompletionResponse {
        val text = obj["content"]?.jsonArray
            ?.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
            ?: ""
        return ChatCompletionResponse(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "chatcmpl-anthropic",
            created = clock() / 1000,
            model = model,
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage("assistant", text),
                    finishReason = mapStopReason(obj["stop_reason"]?.jsonPrimitive?.contentOrNull),
                ),
            ),
            usage = mapUsage(obj["usage"] as? JsonObject),
        )
    }

    private fun mapUsage(usage: JsonObject?): Usage? {
        usage ?: return null
        val input = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0
        val output = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0
        return Usage(promptTokens = input, completionTokens = output, totalTokens = input + output)
    }

    private fun mapStopReason(reason: String?): String = when (reason) {
        "max_tokens" -> "length"
        else -> "stop" // end_turn, stop_sequence, null
    }

    // ------------------------------------------------------------- streaming

    /** Anthropic SSE → OpenAI `chat.completion.chunk` SSE re-mapping (§5.9). */
    private suspend fun remapSse(reader: BufferedReader, model: String, emit: suspend (ByteArray) -> Unit) {
        val id = "chatcmpl-anthropic-${clock()}"
        val created = clock() / 1000
        var inputTokens = 0L
        var outputTokens = 0L
        var stopReason: String? = null

        suspend fun emitChunk(delta: Delta, finish: String? = null, usage: Usage? = null) {
            val chunk = ChatCompletionChunk(
                id = id, created = created, model = model,
                choices = listOf(ChunkChoice(index = 0, delta = delta, finishReason = finish)),
                usage = usage,
            )
            emit("data: ${json.encodeToString(ChatCompletionChunk.serializer(), chunk)}\n\n".toByteArray())
        }

        var dataBuf = StringBuilder()
        suspend fun handleEvent() {
            if (dataBuf.isEmpty()) return
            val payload = dataBuf.toString()
            dataBuf = StringBuilder()
            val obj = try {
                Json.parseToJsonElement(payload).jsonObject
            } catch (e: Exception) {
                return
            }
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "message_start" -> {
                    val usage = obj["message"]?.jsonObject?.get("usage") as? JsonObject
                    inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0
                    emitChunk(Delta(role = "assistant", content = ""))
                }
                "content_block_delta" -> {
                    val delta = obj["delta"] as? JsonObject
                    if (delta?.get("type")?.jsonPrimitive?.contentOrNull == "text_delta") {
                        val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        emitChunk(Delta(content = text))
                    }
                }
                "message_delta" -> {
                    stopReason = obj["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                        ?: stopReason
                    outputTokens = obj["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.longOrNull
                        ?: outputTokens
                }
                "message_stop" -> {
                    emitChunk(
                        Delta(),
                        finish = mapStopReason(stopReason),
                        usage = Usage(inputTokens, outputTokens, inputTokens + outputTokens),
                    )
                    emit("data: [DONE]\n\n".toByteArray())
                }
                // ping, content_block_start/stop, error → nothing to forward.
            }
        }

        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.startsWith("data:") -> dataBuf.append(line.removePrefix("data:").trim())
                line.isEmpty() -> handleEvent()
                // "event: …" lines are implied by each data payload's `type`.
            }
        }
        handleEvent() // trailing event without final blank line
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()

        /** The §5.9 text-chat subset the driver can translate. */
        val TRANSLATABLE_FIELDS: Set<String> = setOf(
            "model", "messages", "system", "max_tokens", "temperature",
            "top_p", "stop", "stream", "stream_options",
        )
    }
}
