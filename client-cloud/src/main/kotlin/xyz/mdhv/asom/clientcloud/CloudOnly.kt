package xyz.mdhv.asom.clientcloud

import android.content.Context
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
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
) {
    init {
        // Invariant §1.2 permits cleartext on localhost only, and this request
        // carries the user's key in an Authorization header. Fail at wiring
        // time rather than relying on the host app's network security config.
        require(baseUrl.startsWith("https://")) {
            "CloudProvider '$id' baseUrl must be https:// — cleartext is never permitted off localhost"
        }
    }
}

/**
 * §10A.1 `CloudOnly`: the app does cloud-BYOK itself — HTTPS straight to
 * providers using keys from ITS OWN vault. No engine, no local models, no
 * asom required. Lightweight by design; no routing intelligence beyond
 * first-usable-provider (asom is where routing lives), but the §5.5 virtual
 * selectors are still honoured so this tier and `RemoteAsom` are
 * interchangeable — see [CloudRouting].
 */
class CloudOnly(
    context: Context,
    private val providers: List<CloudProvider>,
    private val client: OkHttpClient = cloudDefaultHttpClient(),
) : InferenceClient {

    val vault = ClientVault(context)

    private fun keyed(): List<CloudRouting.Keyed> =
        providers.mapNotNull { p -> vault.getKey(p.id)?.let { CloudRouting.Keyed(p, it) } }

    override suspend fun chat(requestJson: String, options: RequestOptions): InferenceResponse =
        when (val route = CloudRouting.route(requestJson, keyed())) {
            is CloudRouting.Route.Refused -> route.response
            is CloudRouting.Route.Upstream -> execute(
                build(route.provider, route.key, "/chat/completions", route.bodyJson),
                route.provider,
                route.model,
            )
        }

    /**
     * Streaming. [InferenceStream.headers] is only populated once the first
     * chunk has been collected.
     */
    override fun chatStream(requestJson: String, options: RequestOptions): InferenceStream {
        val upstream = when (val route = CloudRouting.route(requestJson, keyed())) {
            is CloudRouting.Route.Refused -> return object : InferenceStream {
                override val headers: InferenceResponse = route.response
                override val chunks: Flow<String> = emptyFlow()
                override fun cancel() {}
            }
            is CloudRouting.Route.Upstream -> route
        }
        val request = build(
            upstream.provider,
            upstream.key,
            "/chat/completions",
            CloudRouting.withStreaming(upstream.bodyJson),
        )

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
                echo.set(response.toInferenceResponse("", upstream.provider, upstream.model))
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

    override suspend fun embeddings(requestJson: String): InferenceResponse =
        when (val route = CloudRouting.route(requestJson, keyed())) {
            is CloudRouting.Route.Refused -> route.response
            is CloudRouting.Route.Upstream -> execute(
                build(route.provider, route.key, "/embeddings", route.bodyJson),
                route.provider,
                route.model,
            )
        }

    override suspend fun models(): InferenceResponse {
        // Static view: models of providers the user has keyed.
        val keyedProviders = providers.filter { vault.hasKey(it.id) }
        val data = keyedProviders.flatMap { p -> p.models.map { m -> """{"id":"$m","object":"model","owned_by":"${p.id}"}""" } }
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

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
