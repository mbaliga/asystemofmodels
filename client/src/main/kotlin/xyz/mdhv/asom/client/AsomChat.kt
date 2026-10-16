package xyz.mdhv.asom.client

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.contract.client.InferenceResponse
import xyz.mdhv.asom.contract.client.InferenceStream
import xyz.mdhv.asom.contract.client.RequestOptions

/**
 * Low-level HTTP/SSE transport to the asom daemon. Most apps should use
 * [RemoteAsom] (the §10A [xyz.mdhv.asom.contract.client.InferenceClient])
 * instead of this class directly.
 */
class AsomChat(
    private val endpoint: AsomEndpoint,
    private val token: String,
    private val client: OkHttpClient = asomDefaultHttpClient(),
) {

    suspend fun complete(bodyJson: String, options: RequestOptions = RequestOptions()): InferenceResponse =
        execute(build("/v1/chat/completions", bodyJson, options))

    suspend fun embeddings(bodyJson: String): InferenceResponse =
        execute(build("/v1/embeddings", bodyJson, RequestOptions()))

    suspend fun models(): InferenceResponse {
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/v1/models")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return execute(request)
    }

    /**
     * Streaming (`stream:true` set for you); emits SSE data payloads, no
     * `[DONE]`. [InferenceStream.headers] is only populated once the first
     * chunk has been collected.
     */
    fun stream(bodyJson: String, options: RequestOptions = RequestOptions()): InferenceStream {
        val withStream = Json.parseToJsonElement(bodyJson).jsonObject.let { obj ->
            buildJsonObject {
                obj.forEach { (k, v) -> if (k != "stream") put(k, v) }
                put("stream", JsonPrimitive(true))
            }
        }.toString()
        val request = build("/v1/chat/completions", withStream, options)

        val echo = AtomicReference(InferenceResponse(0, ""))
        val active = AtomicReference<Call?>(null)
        val cancelled = AtomicBoolean(false)

        // The Call is created per collection (not once, outside) so the Flow is
        // re-collectable; `use` closes the response on a mid-stream failure, and
        // the suspending `emit` is the cancellation checkpoint the blocking read
        // loop would otherwise have none of.
        val chunkFlow = flow {
            val call = client.newCall(request).also { active.set(it) }
            if (cancelled.get()) call.cancel()
            call.execute().use { response ->
                echo.set(response.toInferenceResponse(""))
                val source = response.body?.source()
                if (source != null) {
                    val buffer = StringBuilder()
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data:")) {
                            buffer.append(line.removePrefix("data:").trim())
                        } else if (line.isEmpty() && buffer.isNotEmpty()) {
                            val payload = buffer.toString()
                            buffer.setLength(0)
                            if (payload == "[DONE]") break
                            emit(payload)
                        }
                    }
                }
            }
        }.flowOn(Dispatchers.IO)

        return object : InferenceStream {
            override val headers: InferenceResponse get() = echo.get()
            override val chunks: Flow<String> = chunkFlow
            override fun cancel() {
                cancelled.set(true)
                active.get()?.cancel()
            }
        }
    }

    private suspend fun execute(request: Request): InferenceResponse {
        val response = suspendCancellableCoroutine<Response> { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response)
                }
            })
        }
        return response.use { it.toInferenceResponse(it.body?.string().orEmpty()) }
    }

    private fun build(path: String, bodyJson: String, options: RequestOptions): Request {
        val builder = Request.Builder()
            .url("${endpoint.baseUrl}$path")
            .header("Authorization", "Bearer $token")
            .post(bodyJson.toRequestBody(JSON_MEDIA))
        options.policy?.let { builder.header(AsomHeaders.POLICY, it.wire) }
        if (options.fallback.isNotEmpty()) {
            builder.header(AsomHeaders.FALLBACK, options.fallback.joinToString(","))
        }
        if (options.noTrain) builder.header(AsomHeaders.NO_TRAIN, "true")
        return builder.build()
    }

    private fun Response.toInferenceResponse(body: String) = InferenceResponse(
        status = code,
        bodyJson = body,
        servedBy = header(AsomHeaders.SERVED_BY),
        egress = header(AsomHeaders.EGRESS),
        costEst = header(AsomHeaders.COST_EST),
        costBasis = header(AsomHeaders.COST_BASIS),
    )

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
