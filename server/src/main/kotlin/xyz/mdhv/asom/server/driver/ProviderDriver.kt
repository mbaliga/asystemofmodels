package xyz.mdhv.asom.server.driver

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.contract.openai.Usage

/** What one upstream attempt produced. */
sealed interface DriverOutcome {
    /** Non-streaming 2xx: the upstream JSON body (already OpenAI-shaped). */
    data class Json(val status: Int, val body: JsonObject, val usage: Usage?) : DriverOutcome

    /**
     * Streaming 2xx: raw SSE byte events, already `chat.completion.chunk`
     * shaped (openai-compat = byte pass-through; Anthropic driver re-maps,
     * §5.9). Each element is one or more complete SSE lines/events.
     */
    data class Stream(val events: Flow<ByteArray>) : DriverOutcome

    /**
     * Upstream error. [retryable] = 429/5xx/timeout → circuit-breaker
     * cooldown + fall to next candidate (§7); otherwise surfaced to caller.
     */
    data class Error(val status: Int, val bodyText: String, val retryable: Boolean) : DriverOutcome
}

/**
 * One provider `kind` = one driver (§6). v1 drivers: fake (P3 tests/desktop),
 * openai-compat and anthropic (P4).
 */
interface ProviderDriver {
    suspend fun chat(
        provider: ProviderEntry,
        apiKey: String,
        body: JsonObject,
        stream: Boolean,
    ): DriverOutcome

    suspend fun embeddings(
        provider: ProviderEntry,
        apiKey: String,
        body: JsonObject,
    ): DriverOutcome
}
