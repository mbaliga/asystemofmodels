package xyz.mdhv.asom.clientcloud

import android.content.Context
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import xyz.mdhv.asom.contract.client.InferenceClient
import xyz.mdhv.asom.contract.client.InferenceResponse
import xyz.mdhv.asom.contract.client.InferenceStream
import xyz.mdhv.asom.contract.client.RequestOptions

/** An OpenAI-compatible provider this app can reach on its own (§10A.1). */
data class CloudProvider(
    val id: String,
    /** e.g. `https://openrouter.ai/api/v1` — OpenAI-compat providers only. */
    val baseUrl: String,
    /** Model ids this provider serves (first provider with key + model wins). */
    val models: List<String> = emptyList(),
)

/**
 * §10A.1 `CloudOnly`: the app does cloud-BYOK itself — HTTPS straight to
 * providers using keys from ITS OWN vault. No engine, no local models, no
 * asom required. Lightweight by design; no routing intelligence beyond
 * first-usable-provider (asom is where routing lives).
 */
class CloudOnly(
    context: Context,
    private val providers: List<CloudProvider>,
    private val client: OkHttpClient = OkHttpClient(),
) : InferenceClient {

    val vault = ClientVault(context)

    private fun pick(model: String?): Pair<CloudProvider, String>? {
        for (p in providers) {
            val key = vault.getKey(p.id) ?: continue
            if (model == null || p.models.isEmpty() || model in p.models) return p to key
        }
        return null
    }

    override suspend fun chat(requestJson: String, options: RequestOptions): InferenceResponse {
        val model = modelOf(requestJson)
        val (provider, key) = pick(model) ?: return noKey()
        val request = build(provider, key, "/chat/completions", requestJson)
        return execute(request, provider, model)
    }

    override fun chatStream(requestJson: String, options: RequestOptions): InferenceStream {
        val model = modelOf(requestJson)
        val picked = pick(model)
        val withStream = Json.parseToJsonElement(requestJson).jsonObject.let { obj ->
            buildJsonObject {
                obj.forEach { (k, v) -> if (k != "stream") put(k, v) }
                put("stream", JsonPrimitive(true))
            }
        }.toString()

        if (picked == null) {
            return object : InferenceStream {
                override val headers: InferenceResponse = noKey()
                override val chunks: Flow<String> = callbackFlow { close() }
                override fun cancel() {}
            }
        }
        val (provider, key) = picked
        val call = client.newCall(build(provider, key, "/chat/completions", withStream))
        var headerResponse: InferenceResponse? = null
        val flow = callbackFlow {
            val response = call.execute()
            headerResponse = response.toInferenceResponse("", provider, model)
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

    override suspend fun embeddings(requestJson: String): InferenceResponse {
        val model = modelOf(requestJson)
        val (provider, key) = pick(model) ?: return noKey()
        return execute(build(provider, key, "/embeddings", requestJson), provider, model)
    }

    override suspend fun models(): InferenceResponse {
        // Static view: models of providers the user has keyed.
        val keyed = providers.filter { vault.hasKey(it.id) }
        val data = keyed.flatMap { p -> p.models.map { m -> """{"id":"$m","object":"model","owned_by":"${p.id}"}""" } }
        return InferenceResponse(200, """{"object":"list","data":[${data.joinToString(",")}]}""")
    }

    // ----------------------------------------------------------------- http

    private fun build(provider: CloudProvider, key: String, path: String, body: String): Request =
        Request.Builder()
            .url(provider.baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

    private suspend fun execute(request: Request, provider: CloudProvider, model: String?): InferenceResponse {
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
        return response.use { it.toInferenceResponse(it.body?.string().orEmpty(), provider, model) }
    }

    private fun Response.toInferenceResponse(body: String, provider: CloudProvider, model: String?) =
        InferenceResponse(
            status = code,
            bodyJson = body,
            servedBy = model?.let { "${provider.id}/$it" } ?: provider.id,
            egress = "cloud", // CloudOnly is by definition cloud egress
        )

    private fun modelOf(requestJson: String): String? = try {
        Json.parseToJsonElement(requestJson).jsonObject["model"]?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        null
    }

    private fun noKey(): InferenceResponse = InferenceResponse(
        status = 503,
        bodyJson = """{"error":{"message":"no provider key stored in this app's vault","type":"server_error","code":"NO_PROVIDER_KEY"}}""",
        egress = "local",
    )

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
