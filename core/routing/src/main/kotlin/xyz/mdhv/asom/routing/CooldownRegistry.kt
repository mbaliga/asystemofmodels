package xyz.mdhv.asom.routing

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-provider circuit breaker (brief §7): on 429/5xx/timeout the provider
 * enters cooldown with exponential backoff 30 s → 15 min cap; a success
 * closes the breaker. There is no half-open bookkeeping — once the deadline
 * passes the provider is attemptable again, and the next failure re-cools
 * with a doubled backoff (the failure streak survives the deadline).
 */
class CooldownRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
    private val baseMs: Long = 30_000,
    private val capMs: Long = 900_000,
) {
    private data class BreakerState(val consecutiveFailures: Int, val coolingUntil: Long)

    private val states = ConcurrentHashMap<String, BreakerState>()

    fun isCooling(providerId: String): Boolean =
        (states[providerId]?.coolingUntil ?: 0L) > clock()

    /** Active cooldown deadline (epoch ms), or null when not cooling. */
    fun coolingUntil(providerId: String): Long? =
        states[providerId]?.coolingUntil?.takeIf { it > clock() }

    /** Records a routable failure (429/5xx/timeout) and opens/extends cooldown. */
    fun recordFailure(providerId: String) {
        states.compute(providerId) { _, prev ->
            val failures = (prev?.consecutiveFailures ?: 0) + 1
            // baseMs * 2^(failures-1), capped; shift guarded against overflow.
            val backoff = if (failures - 1 >= 30) capMs else minOf(capMs, baseMs shl (failures - 1))
            BreakerState(failures, clock() + backoff)
        }
    }

    /** A success fully closes the breaker and resets the failure streak. */
    fun recordSuccess(providerId: String) {
        states.remove(providerId)
    }

    /** Live cooldown state for `/admin/catalogue` (§5.2): providerId → deadline. */
    fun snapshot(): Map<String, Long> {
        val now = clock()
        return states.filterValues { it.coolingUntil > now }.mapValues { it.value.coolingUntil }
    }
}
