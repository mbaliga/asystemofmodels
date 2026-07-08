package xyz.mdhv.asom.client

import android.content.Context
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.OkHttpClient
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.client.InferenceClient
import xyz.mdhv.asom.contract.client.InferenceResponse
import xyz.mdhv.asom.contract.client.InferenceStream
import xyz.mdhv.asom.contract.client.RequestOptions

/**
 * §10A.1 `RemoteAsom`: the [InferenceClient] that routes through the asom
 * daemon on 127.0.0.1 after AIDL pairing. No keys, no models, no engine in
 * the consuming app.
 */
class RemoteAsom(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : InferenceClient {

    val pairing = AsomPairing(context)

    /** asom installed, discoverable, and this app holds a pairing token. */
    fun available(): Boolean =
        AsomDiscovery.discover(context) != null && pairing.token() != null

    /** asom installed at all (pairing may still be needed). */
    fun installed(): Boolean = AsomDiscovery.discover(context) != null

    private fun transport(): AsomChat? {
        val endpoint = AsomDiscovery.discover(context) ?: return null
        val token = pairing.token() ?: return null
        return AsomChat(endpoint, token, httpClient)
    }

    override suspend fun chat(requestJson: String, options: RequestOptions): InferenceResponse =
        transport()?.complete(requestJson, options) ?: notPaired()

    override fun chatStream(requestJson: String, options: RequestOptions): InferenceStream =
        transport()?.stream(requestJson, options) ?: object : InferenceStream {
            override val headers: InferenceResponse = notPaired()
            override val chunks = emptyFlow<String>()
            override fun cancel() {}
        }

    override suspend fun embeddings(requestJson: String): InferenceResponse =
        transport()?.embeddings(requestJson) ?: notPaired()

    override suspend fun models(): InferenceResponse =
        transport()?.models() ?: notPaired()

    private fun notPaired(): InferenceResponse = InferenceResponse(
        status = AsomErrorCode.NOT_PAIRED.httpStatus,
        bodyJson = """{"error":{"message":"asom not installed or not paired","type":"${AsomErrorCode.NOT_PAIRED.openAiType}","code":"${AsomErrorCode.NOT_PAIRED.name}"}}""",
        egress = "local",
    )
}
