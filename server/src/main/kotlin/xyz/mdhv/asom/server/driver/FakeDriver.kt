package xyz.mdhv.asom.server.driver

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.contract.openai.ChatCompletionChunk
import xyz.mdhv.asom.contract.openai.ChatCompletionResponse
import xyz.mdhv.asom.contract.openai.ChatMessage
import xyz.mdhv.asom.contract.openai.Choice
import xyz.mdhv.asom.contract.openai.ChunkChoice
import xyz.mdhv.asom.contract.openai.Delta
import xyz.mdhv.asom.contract.openai.EmbeddingObject
import xyz.mdhv.asom.contract.openai.EmbeddingsResponse
import xyz.mdhv.asom.contract.openai.Usage
import xyz.mdhv.asom.server.util.estimateTokens

/**
 * Deterministic in-process driver for P3 integration tests and the desktop
 * dev server. Produces OpenAI-shaped responses tagged `fake:<provider>/<model>`
 * so tests can assert routing decisions end-to-end. Failures are injected per
 * provider via [failWith] to exercise the circuit breaker.
 */
class FakeDriver(private val clock: () -> Long = System::currentTimeMillis) : ProviderDriver {

    private val json = Json { encodeDefaults = true }

    /** providerId → HTTP status the next attempts should fail with. */
    private val failures = ConcurrentHashMap<String, Int>()

    fun failWith(providerId: String, status: Int) {
        failures[providerId] = status
    }

    fun heal(providerId: String) {
        failures.remove(providerId)
    }

    private fun injectedError(provider: ProviderEntry): DriverOutcome.Error? {
        val status = failures[provider.id] ?: return null
        return DriverOutcome.Error(
            status = status,
            bodyText = """{"error":{"message":"injected failure","type":"server_error","code":null}}""",
            retryable = status == 429 || status >= 500,
        )
    }

    override suspend fun chat(
        provider: ProviderEntry,
        apiKey: String,
        body: JsonObject,
        stream: Boolean,
    ): DriverOutcome {
        injectedError(provider)?.let { return it }

        val model = body["model"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val lastUser = body["messages"]?.jsonArray
            ?.lastOrNull { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "user" }
            ?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: ""
        val content = "fake:${provider.id}/$model:$lastUser"
        val usage = Usage(
            promptTokens = estimateTokens(body),
            completionTokens = (content.length / 4).toLong().coerceAtLeast(1),
            totalTokens = 0,
        ).let { it.copy(totalTokens = it.promptTokens + it.completionTokens) }
        val id = "chatcmpl-fake-${provider.id}"
        val created = clock() / 1000

        if (!stream) {
            val response = ChatCompletionResponse(
                id = id,
                created = created,
                model = model,
                choices = listOf(
                    Choice(index = 0, message = ChatMessage("assistant", content), finishReason = "stop"),
                ),
                usage = usage,
            )
            val obj = json.parseToJsonElement(json.encodeToString(ChatCompletionResponse.serializer(), response)).jsonObject
            return DriverOutcome.Json(200, obj, usage)
        }

        val includeUsage = body["stream_options"]?.jsonObject
            ?.get("include_usage")?.jsonPrimitive?.booleanOrNull == true

        // Three content chunks + final usage/finish chunk + [DONE], SSE-framed.
        val events = flow {
            fun chunk(delta: Delta, finish: String? = null, chunkUsage: Usage? = null): ByteArray {
                val c = ChatCompletionChunk(
                    id = id, created = created, model = model,
                    choices = listOf(ChunkChoice(index = 0, delta = delta, finishReason = finish)),
                    usage = chunkUsage,
                )
                return "data: ${json.encodeToString(ChatCompletionChunk.serializer(), c)}\n\n".toByteArray()
            }
            emit(chunk(Delta(role = "assistant", content = "")))
            content.chunked((content.length / 2).coerceAtLeast(1)).forEach {
                emit(chunk(Delta(content = it)))
            }
            emit(chunk(Delta(), finish = "stop", chunkUsage = if (includeUsage) usage else null))
            emit("data: [DONE]\n\n".toByteArray())
        }
        return DriverOutcome.Stream(events)
    }

    override suspend fun embeddings(
        provider: ProviderEntry,
        apiKey: String,
        body: JsonObject,
    ): DriverOutcome {
        injectedError(provider)?.let { return it }

        val model = body["model"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val usage = Usage(promptTokens = estimateTokens(body), completionTokens = 0, totalTokens = 0)
            .let { it.copy(totalTokens = it.promptTokens) }
        val response = EmbeddingsResponse(
            data = listOf(EmbeddingObject(index = 0, embedding = listOf(0.1, 0.2, 0.3))),
            model = model,
            usage = usage,
        )
        val obj = json.parseToJsonElement(json.encodeToString(EmbeddingsResponse.serializer(), response)).jsonObject
        return DriverOutcome.Json(200, obj, usage)
    }
}
