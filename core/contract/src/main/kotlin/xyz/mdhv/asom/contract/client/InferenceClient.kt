package xyz.mdhv.asom.contract.client

import kotlinx.coroutines.flow.Flow
import xyz.mdhv.asom.contract.Policy

/**
 * THE one interface consuming apps code against (brief §10A.1). The app's own
 * logic never knows which implementation is live:
 *
 *  - `RemoteAsom` (`:client`, v1) — routes through the asom daemon.
 *  - `CloudOnly` (`:client-cloud`, v1) — the app does cloud-BYOK itself.
 *  - `Embedded` (`asom-standalone`, v2) — in-app engine; seam only in v1.
 *
 * Bodies are OpenAI-compatible JSON strings; `model` may be a concrete id or
 * a virtual policy (§5.5).
 */
interface InferenceClient {

    /** Non-streaming chat completion. Throws only on transport failure. */
    suspend fun chat(requestJson: String, options: RequestOptions = RequestOptions()): InferenceResponse

    /** Streaming chat (`stream:true` set for you); chunks are SSE payloads. */
    fun chatStream(requestJson: String, options: RequestOptions = RequestOptions()): InferenceStream

    /** Embeddings request. */
    suspend fun embeddings(requestJson: String): InferenceResponse

    /** Available models, OpenAI list shape. */
    suspend fun models(): InferenceResponse
}

/** The §5.3 request headers, typed (ignored by impls with no routing). */
data class RequestOptions(
    val policy: Policy? = null,
    val fallback: List<String> = emptyList(),
    val noTrain: Boolean = false,
)

/** Response + the §5.4 echo headers (null where an impl has no routing daemon). */
class InferenceResponse(
    val status: Int,
    val bodyJson: String,
    val servedBy: String? = null,
    val egress: String? = null,
    val costEst: String? = null,
    val costBasis: String? = null,
)

interface InferenceStream {
    /** Status + echo headers, available once the stream has started. */
    val headers: InferenceResponse

    /** SSE `data:` payload strings (chunk JSON), excluding `[DONE]`. */
    val chunks: Flow<String>

    fun cancel()
}
