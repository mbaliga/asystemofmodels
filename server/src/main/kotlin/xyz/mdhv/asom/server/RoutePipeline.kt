package xyz.mdhv.asom.server

import java.io.IOException
import kotlinx.serialization.json.JsonObject
import xyz.mdhv.asom.catalogue.ProviderKind
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomException
import xyz.mdhv.asom.routing.Candidate
import xyz.mdhv.asom.routing.CooldownRegistry
import xyz.mdhv.asom.routing.LatencyTracker
import xyz.mdhv.asom.routing.RouteQuery
import xyz.mdhv.asom.routing.Router
import xyz.mdhv.asom.server.driver.DriverOutcome
import xyz.mdhv.asom.server.driver.ProviderDriver
import xyz.mdhv.asom.server.keys.KeyProvider

enum class Operation { CHAT, EMBEDDINGS }

/**
 * Executes a routed request: plan → attempt candidates in order with the
 * circuit breaker (§7). Retryable upstream failures (429/5xx/timeout) cool
 * the provider and fall through; fatal upstream errors surface to the caller.
 */
class RoutePipeline(
    private val router: Router,
    private val keys: KeyProvider,
    private val drivers: (ProviderKind) -> ProviderDriver,
    private val cooldowns: CooldownRegistry,
    private val latency: LatencyTracker,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        val candidate: Candidate

        data class Json(override val candidate: Candidate, val outcome: DriverOutcome.Json) : Result
        data class Stream(override val candidate: Candidate, val outcome: DriverOutcome.Stream) : Result

        /** Non-retryable upstream error, relayed to the caller. */
        data class UpstreamError(override val candidate: Candidate, val outcome: DriverOutcome.Error) : Result
    }

    /** @throws AsomException with a typed §5.6 code on every failure path. */
    suspend fun execute(
        query: RouteQuery,
        body: JsonObject,
        stream: Boolean,
        op: Operation,
    ): Result {
        val plan = router.plan(query)
        for (candidate in plan) {
            // The router filtered on key presence; a vanished key just skips.
            val apiKey = keys.keyFor(candidate.provider.id) ?: continue
            val driver = drivers(candidate.provider.kind)
            // Virtual selectors ("cheapest", …) resolve here: the upstream
            // body must name the CONCRETE model — providers don't know asom's
            // virtual names. Every other field passes through verbatim (§5.9).
            val upstreamBody = withConcreteModel(body, candidate.modelId)
            val started = clock()
            val outcome = try {
                when (op) {
                    Operation.CHAT -> driver.chat(candidate.provider, apiKey, upstreamBody, stream)
                    Operation.EMBEDDINGS -> driver.embeddings(candidate.provider, apiKey, upstreamBody)
                }
            } catch (e: IOException) {
                // Timeout/connect failure → retryable (§7).
                DriverOutcome.Error(599, e.message ?: "upstream I/O failure", retryable = true)
            }
            when (outcome) {
                is DriverOutcome.Json -> {
                    cooldowns.recordSuccess(candidate.provider.id)
                    latency.record(candidate.provider.id, clock() - started)
                    return Result.Json(candidate, outcome)
                }
                is DriverOutcome.Stream -> {
                    cooldowns.recordSuccess(candidate.provider.id)
                    // Time-to-stream-start; good-enough EWMA signal for `fastest`.
                    latency.record(candidate.provider.id, clock() - started)
                    return Result.Stream(candidate, outcome)
                }
                is DriverOutcome.Error -> {
                    if (outcome.retryable) {
                        cooldowns.recordFailure(candidate.provider.id)
                        continue
                    }
                    return Result.UpstreamError(candidate, outcome)
                }
            }
        }
        // Every candidate failed retryably — they are all cooling now (§7).
        throw AsomException(
            AsomErrorCode.ALL_PROVIDERS_COOLING,
            "all candidate providers failed and are cooling down; retry later",
        )
    }

    private fun withConcreteModel(body: JsonObject, modelId: String): JsonObject {
        if ((body["model"] as? kotlinx.serialization.json.JsonPrimitive)?.content == modelId) return body
        return kotlinx.serialization.json.buildJsonObject {
            body.forEach { (k, v) -> if (k != "model") put(k, v) }
            put("model", kotlinx.serialization.json.JsonPrimitive(modelId))
        }
    }
}
