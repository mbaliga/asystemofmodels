package xyz.mdhv.asom.server.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import xyz.mdhv.asom.catalogue.Pricing
import xyz.mdhv.asom.contract.openai.Usage

/**
 * Heuristic input-token estimate (§5.9: the router reads `messages` for
 * heuristic token counts — chars/4). Used when a provider omits usage.
 */
fun estimateTokens(body: JsonObject): Long {
    val messages = body["messages"] as? JsonArray ?: return estimatePromptTokens(body)
    var chars = 0
    for (m in messages) {
        val content = (m as? JsonObject)?.get("content")
        if (content is JsonPrimitive) chars += content.contentOrNull?.length ?: 0
        // Multi-part content arrays: count text parts.
        if (content is JsonArray) {
            for (part in content) {
                val text = (part as? JsonObject)?.get("text") as? JsonPrimitive
                chars += text?.contentOrNull?.length ?: 0
            }
        }
    }
    return (chars / 4).toLong().coerceAtLeast(1)
}

private fun estimatePromptTokens(body: JsonObject): Long {
    val prompt = body["prompt"] as? JsonPrimitive
        ?: (body["input"] as? JsonPrimitive)
        ?: return 1
    return ((prompt.contentOrNull?.length ?: 0) / 4).toLong().coerceAtLeast(1)
}

/** usage → USD (§5.4 basis `usage`); null when the model has no pricing. */
fun usageCost(pricing: Pricing?, usage: Usage): Double? {
    pricing ?: return null
    return usage.promptTokens * pricing.inPerMTok / 1_000_000.0 +
        usage.completionTokens * pricing.outPerMTok / 1_000_000.0
}

/** Reads `usage` from an OpenAI-shaped response/chunk JSON, if present. */
fun usageFrom(obj: JsonObject): Usage? {
    val u = obj["usage"] as? JsonObject ?: return null
    fun long(name: String) = (u[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
    val usage = Usage(long("prompt_tokens"), long("completion_tokens"), long("total_tokens"))
    return if (usage.promptTokens == 0L && usage.completionTokens == 0L && usage.totalTokens == 0L) null else usage
}
