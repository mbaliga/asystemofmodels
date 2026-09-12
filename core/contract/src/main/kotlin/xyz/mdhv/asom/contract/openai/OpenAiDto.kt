package xyz.mdhv.asom.contract.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Minimal OpenAI-shape DTOs — only what asom itself must GENERATE (error
 * envelopes, /v1/models, fake/Anthropic-driver responses). Incoming request
 * bodies are handled as raw [JsonObject]s because §5.9 mandates verbatim
 * pass-through of unrecognized fields: the daemon never gatekeeps provider
 * capabilities.
 */

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null,
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: Delta = Delta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice> = emptyList(),
    val usage: Usage? = null,
)

/** /v1/models entry (§5.2). `owned_by` tags concrete vs virtual models. */
@Serializable
data class ModelObject(
    val id: String,
    @SerialName("object") val objectType: String = "model",
    val created: Long = 0,
    /** Concrete models: the provider id(s); virtual models: "asom-virtual". */
    @SerialName("owned_by") val ownedBy: String,
)

@Serializable
data class ModelListResponse(
    @SerialName("object") val objectType: String = "list",
    val data: List<ModelObject>,
)

/** OpenAI error envelope + typed asom `code` (§5.6). `code` is null only for
 *  plain malformed-request 400s; every routed failure carries a §5.6 code. */
@Serializable
data class ErrorBody(
    val message: String,
    val type: String,
    val param: String? = null,
    val code: String? = null,
)

@Serializable
data class ErrorEnvelope(
    val error: ErrorBody,
)

/** Legacy /v1/completions response shape (shimmed over chat, §5.2). */
@Serializable
data class TextCompletionChoice(
    val index: Int = 0,
    val text: String,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class TextCompletionResponse(
    val id: String,
    @SerialName("object") val objectType: String = "text_completion",
    val created: Long,
    val model: String,
    val choices: List<TextCompletionChoice>,
    val usage: Usage? = null,
)

/** /v1/embeddings response (cloud-routed in v1, §5.2). */
@Serializable
data class EmbeddingObject(
    @SerialName("object") val objectType: String = "embedding",
    val index: Int = 0,
    val embedding: List<Double>,
)

@Serializable
data class EmbeddingsResponse(
    @SerialName("object") val objectType: String = "list",
    val data: List<EmbeddingObject>,
    val model: String,
    val usage: Usage? = null,
)

/** Raw request body alias — §5.9 pass-through semantics. */
typealias RawBody = JsonObject
