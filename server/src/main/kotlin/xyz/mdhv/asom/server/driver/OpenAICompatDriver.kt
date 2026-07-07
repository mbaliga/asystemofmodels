package xyz.mdhv.asom.server.driver

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.server.util.usageFrom

/**
 * Generic baseUrl-parameterized OpenAI-compatible driver (§6): one driver
 * covers OpenRouter/Groq/Together/Mistral/DeepSeek/Gemini-compat.
 *
 * §5.9 laws: the request body passes through VERBATIM (the daemon never
 * gatekeeps provider capabilities) and streams are byte-level pass-through.
 */
class OpenAICompatDriver(
    private val client: OkHttpClient = defaultClient(),
) : ProviderDriver {

    override suspend fun chat(
        provider: ProviderEntry,
        apiKey: String,
        body: JsonObject,
        stream: Boolean,
    ): DriverOutcome = execute(provider, apiKey, body, "/chat/completions", stream)

    override suspend fun embeddings(
        provider: ProviderEntry,
        apiKey: String,
        body: JsonObject,
    ): DriverOutcome = execute(provider, apiKey, body, "/embeddings", stream = false)

    private suspend fun execute(
        provider: ProviderEntry,
        apiKey: String,
        body: JsonObject,
        path: String,
        stream: Boolean,
    ): DriverOutcome = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(provider.baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val text = response.body?.string().orEmpty()
            response.close()
            return@withContext DriverOutcome.Error(
                status = response.code,
                bodyText = text,
                retryable = response.code == 429 || response.code >= 500, // §7
            )
        }

        if (!stream) {
            val text = response.body!!.string()
            val obj = Json.parseToJsonElement(text).jsonObject
            DriverOutcome.Json(response.code, obj, usageFrom(obj))
        } else {
            // Byte-level pass-through (§5.9): forward the upstream SSE body
            // untouched. The server tees usage out of the tail for the ledger.
            val streamBody = response.body!!
            DriverOutcome.Stream(
                flow {
                    streamBody.byteStream().use { input ->
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) emit(buf.copyOf(n))
                        }
                    }
                }.flowOn(Dispatchers.IO),
            )
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS) // long-lived SSE reads
            .build()
    }
}
