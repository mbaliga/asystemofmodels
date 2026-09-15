package xyz.mdhv.asom.server

import java.io.IOException
import kotlinx.coroutines.CancellationException
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

    /**
     * An attempt that put the request body on the wire and did NOT serve the
     * request. The driver transmits before it can classify the response, so
     * each of these is a real network event and owes a ledger row (§1.3).
     */
    data class Attempt(
        val candidate: Candidate,
        val status: Int,
        val bytesOut: Long,
        val latencyMs: Long,
    )

    /**
     * @param onAttempt invoked for every candidate that egressed and failed,
     *   before the next candidate is tried.
     * @throws AsomException with a typed §5.6 code on every failure path.
     */
    suspend fun execute(
        query: RouteQuery,
        body: JsonObject,
        stream: Boolean,
        op: Operation,
        onAttempt: (Attempt) -> Unit = {},
    ): Result {
        val plan = router.plan(query)
        // A driver that cannot serve a candidate at all (translate() failure,
        // unsupported operation) raises AsomException BEFORE transmitting, so
        // that candidate is skipped rather than failing the whole request —
        // but the reason is surfaced if no candidate ever reached the wire.
        var preflightSkip: AsomException? = null
        var egressed = false
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: AsomException) {
                if (preflightSkip == null) preflightSkip = e
                continue
            } catch (e: IOException) {
                // Timeout/connect failure → retryable (§7).
                DriverOutcome.Error(599, e.message ?: "upstream I/O failure", retryable = true)
            } catch (e: Exception) {
                // The driver already transmitted and then failed to read the
                // reply (e.g. a 2xx body that is not the expected JSON). That
                // is an upstream fault, never the caller's: cool the provider
                // and fall through. The message is fixed rather than taken
                // from the exception, which can carry key material (§1.4).
                DriverOutcome.Error(
                    502,
                    """{"error":{"message":"upstream returned a response this driver could not read",""" +
                        """"type":"server_error"}}""",
                    retryable = true,
                )
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
                        egressed = true
                        onAttempt(
                            Attempt(
                                candidate = candidate,
                                status = outcome.status,
                                bytesOut = upstreamBody.toString().toByteArray().size.toLong(),
                                latencyMs = clock() - started,
                            ),
                        )
                        continue
                    }
                    return Result.UpstreamError(candidate, outcome)
                }
            }
        }
        if (!egressed) preflightSkip?.let { throw it }
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
