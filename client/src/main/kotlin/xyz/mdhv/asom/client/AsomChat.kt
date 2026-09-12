package xyz.mdhv.asom.client

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
    private val client: OkHttpClient = OkHttpClient(),
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

    /** Streaming (`stream:true` set for you); emits SSE data payloads, no `[DONE]`. */
    fun stream(bodyJson: String, options: RequestOptions = RequestOptions()): InferenceStream {
        val withStream = Json.parseToJsonElement(bodyJson).jsonObject.let { obj ->
            buildJsonObject {
                obj.forEach { (k, v) -> if (k != "stream") put(k, v) }
                put("stream", JsonPrimitive(true))
            }
        }.toString()
        val call = client.newCall(build("/v1/chat/completions", withStream, options))

        var headerResponse: InferenceResponse? = null
        val flow = callbackFlow {
            val response = call.execute()
            headerResponse = response.toInferenceResponse("")
            response.body?.source()?.let { source ->
                val buffer = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data:")) {
                        buffer.append(line.removePrefix("data:").trim())
                    } else if (line.isEmpty() && buffer.isNotEmpty()) {
                        val payload = buffer.toString()
                        buffer.setLength(0)
                        if (payload == "[DONE]") break
                        trySend(payload)
                    }
                }
            }
            response.close()
            close()
            awaitClose { call.cancel() }
        }

        return object : InferenceStream {
            override val headers: InferenceResponse
                get() = headerResponse ?: InferenceResponse(0, "")
            override val chunks: Flow<String> = flow
            override fun cancel() = call.cancel()
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
