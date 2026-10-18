package xyz.mdhv.asom.clientcloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.contract.client.InferenceResponse

/**
 * Turns a request's `model` field into (provider, concrete id, upstream body).
 *
 * §10A.1 requires that "the app's own logic never knows which implementation is
 * live", so the §5.5 virtual selectors a paired `RemoteAsom` accepts must work
 * here too — otherwise the same body 200s on one tier and fails on the other.
 * `:client-cloud` has no catalogue and no router (routing is what asom exists
 * for), so the resolution rule is deliberately dumb and deterministic: every
 * selector except `local-only` resolves to the FIRST keyed provider that
 * declares models and to that provider's FIRST declared model — declaration
 * order is the app's own preference order. `local-only` fails with the same
 * `501 LOCAL_ENGINE_ABSENT` the daemon returns, since v1 ships no engine.
 *
 * Kept free of Context and the Keystore so these rules are unit-testable.
 */
internal object CloudRouting {

    data class Keyed(val provider: CloudProvider, val key: String)

    sealed interface Route {
        data class Upstream(
            val provider: CloudProvider,
            val key: String,
            val model: String?,
            val bodyJson: String,
        ) : Route

        data class Refused(val response: InferenceResponse) : Route
    }

    fun route(requestJson: String, keyed: List<Keyed>): Route {
        val requested = modelOf(requestJson)
        if (requested == Policy.LOCAL_ONLY.wire) {
            return Route.Refused(
                refusal(AsomErrorCode.LOCAL_ENGINE_ABSENT, "this app has no local engine; v1 local-only requires asom"),
            )
        }
        if (keyed.isEmpty()) return Route.Refused(noKey())

        if (requested != null && requested in Policy.VIRTUAL_MODELS) {
            val resolved = keyed.firstNotNullOfOrNull { k ->
                k.provider.models.firstOrNull()?.let { k to it }
            } ?: return Route.Refused(
                refusal(
                    AsomErrorCode.MODEL_UNKNOWN,
                    "cannot resolve virtual model '$requested': no keyed provider declares any model",
                ),
            )
            val (keyedProvider, model) = resolved
            return Route.Upstream(
                provider = keyedProvider.provider,
                key = keyedProvider.key,
                model = model,
                bodyJson = withModel(requestJson, model),
            )
        }

        val match = keyed.firstOrNull { k ->
            requested == null || k.provider.models.isEmpty() || requested in k.provider.models
        } ?: return Route.Refused(noKey())
        return Route.Upstream(match.provider, match.key, requested, requestJson)
    }

    fun withStreaming(bodyJson: String): String =
        Json.parseToJsonElement(bodyJson).jsonObject.let { obj ->
            buildJsonObject {
                obj.forEach { (k, v) -> if (k != "stream") put(k, v) }
                put("stream", JsonPrimitive(true))
            }
        }.toString()

    fun noKey(): InferenceResponse =
        refusal(AsomErrorCode.NO_PROVIDER_KEY, "no provider key stored in this app's vault")

    private fun withModel(bodyJson: String, modelId: String): String =
        Json.parseToJsonElement(bodyJson).jsonObject.let { obj ->
            buildJsonObject {
                obj.forEach { (k, v) -> if (k != "model") put(k, v) }
                put("model", JsonPrimitive(modelId))
            }
        }.toString()

    private fun modelOf(requestJson: String): String? = try {
        Json.parseToJsonElement(requestJson).jsonObject["model"]?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        null
    }

    private fun refusal(code: AsomErrorCode, message: String) = InferenceResponse(
        status = code.httpStatus,
        bodyJson = """{"error":{"message":"$message","type":"${code.openAiType}","code":"${code.name}"}}""",
        egress = "local",
    )
}
