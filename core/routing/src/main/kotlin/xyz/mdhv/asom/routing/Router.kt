package xyz.mdhv.asom.routing

import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.catalogue.Pricing
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomException
import xyz.mdhv.asom.contract.Policy

/** Pure view of "does the user have a key stored for this provider" (vault-backed on device). */
fun interface KeyPresence {
    fun hasKey(providerId: String): Boolean
}

/** What the router parses from a request — nothing more (§5.9). */
data class RouteQuery(
    /** Raw `model` field: concrete id or virtual policy name (§5.5). */
    val model: String,
    /** `X-Asom-Policy` header, already parsed; overrides the default policy. */
    val policyHeader: Policy? = null,
    /** `X-Asom-Fallback` provider ids, in order; overrides ordering (§7). */
    val fallback: List<String> = emptyList(),
    /** `X-Asom-No-Train: true` → exclude providers with trainsOnData=true. */
    val noTrain: Boolean = false,
)

/** One attemptable (provider, model) pair, in policy order. */
data class Candidate(
    val provider: ProviderEntry,
    val modelId: String,
) {
    val pricing: Pricing? get() = provider.pricing[modelId]
}

/**
 * Deterministic v1 router (brief §7): resolve → filter → order → (caller
 * attempts in order against the [CooldownRegistry]). No ML, no embeddings,
 * no NL parsing.
 *
 * Deterministic laws (also encoded in tests):
 *  - `cheapest`: blended price (in+out USD/MTok) ascending; unpriced last.
 *  - `fastest`: latency EWMA ascending; unmeasured providers after measured.
 *  - `best-reasoning`: catalogue model `rank` ascending (1 = best); unranked last.
 *  - `auto`: cheapest within the fastest latency band (EWMA ≤ [autoBandFactor] ×
 *    best known EWMA; unmeasured providers optimistically in-band); out-of-band
 *    candidates follow as fallbacks, cheapest first.
 *  - All orderings tie-break by provider id, then model id — total order.
 *  - `X-Asom-Fallback` replaces ordering AND restricts the candidate set to the
 *    listed providers, in the given order.
 */
class Router(
    private val catalogue: () -> Catalogue,
    private val keys: KeyPresence,
    private val latency: LatencyTracker,
    private val cooldowns: CooldownRegistry,
    private val defaultPolicy: Policy = Policy.AUTO,
    private val hasLocalEngine: Boolean = false,
    private val autoBandFactor: Double = 2.0,
) {

    /**
     * Produces the ordered, non-cooling attempt list for a request.
     * @throws AsomException with a typed §5.6 code on every failure path.
     */
    fun plan(query: RouteQuery): List<Candidate> {
        val cat = catalogue()
        val virtualPolicy = Policy.fromWire(query.model)
        val policy = virtualPolicy ?: query.policyHeader ?: defaultPolicy

        // v1 ships no local engine — local-only fails loudly (§2).
        if (policy == Policy.LOCAL_ONLY) {
            if (!hasLocalEngine) {
                throw AsomException(
                    AsomErrorCode.LOCAL_ENGINE_ABSENT,
                    "no local engine in v1; 'local-only' cannot be served",
                )
            }
            // Phase 2 territory — unreachable in v1.
            throw AsomException(AsomErrorCode.LOCAL_ENGINE_ABSENT, "local routing not implemented")
        }

        // Resolve the model selector to raw (provider, model) pairs.
        val universe: List<Candidate> = if (virtualPolicy != null) {
            cat.providers.flatMap { p -> p.models.map { Candidate(p, it) } }
        } else {
            val serving = cat.providers.filter { it.serves(query.model) }
            if (serving.isEmpty()) {
                throw AsomException(
                    AsomErrorCode.MODEL_UNKNOWN,
                    "model '${query.model}' is not served by any catalogue provider",
                )
            }
            serving.map { Candidate(it, query.model) }
        }

        // Filter (§7): user key stored ∧ programmaticAllowed ∧ No-Train.
        val filtered = universe.filter { c ->
            c.provider.programmaticAllowed &&
                keys.hasKey(c.provider.id) &&
                !(query.noTrain && c.provider.trainsOnData)
        }
        if (filtered.isEmpty()) {
            throw AsomException(
                AsomErrorCode.NO_PROVIDER_KEY,
                "no usable provider for '${query.model}' " +
                    "(need: stored key, programmatic access${if (query.noTrain) ", no-train" else ""})",
            )
        }

        // X-Asom-Fallback restricts + orders; otherwise order by policy.
        val ordered = if (query.fallback.isNotEmpty()) {
            val byProvider = filtered.groupBy { it.provider.id }
            val restricted = query.fallback.flatMap { id ->
                byProvider[id].orEmpty().sortedWith(CHEAPEST_ORDER)
            }
            if (restricted.isEmpty()) {
                throw AsomException(
                    AsomErrorCode.NO_PROVIDER_KEY,
                    "no provider in X-Asom-Fallback list ${query.fallback} is usable for '${query.model}'",
                )
            }
            restricted
        } else {
            order(policy, filtered)
        }

        // Circuit breaker: skip cooling providers; all cooling = typed 503.
        val attemptable = ordered.filterNot { cooldowns.isCooling(it.provider.id) }
        if (attemptable.isEmpty()) {
            throw AsomException(
                AsomErrorCode.ALL_PROVIDERS_COOLING,
                "all candidate providers are cooling down; retry later",
            )
        }
        return attemptable
    }

    private fun order(policy: Policy, candidates: List<Candidate>): List<Candidate> = when (policy) {
        Policy.CHEAPEST -> candidates.sortedWith(CHEAPEST_ORDER)
        Policy.FASTEST -> candidates.sortedWith(fastestOrder())
        Policy.BEST_REASONING -> candidates.sortedWith(bestReasoningOrder(catalogue()))
        Policy.AUTO -> autoOrder(candidates)
        Policy.LOCAL_ONLY -> error("unreachable — handled in plan()")
    }

    private fun fastestOrder(): Comparator<Candidate> =
        compareBy<Candidate> { latency.ewma(it.provider.id) ?: Double.MAX_VALUE }
            .thenComparing(CHEAPEST_ORDER)

    private fun bestReasoningOrder(cat: Catalogue): Comparator<Candidate> =
        compareBy<Candidate> { cat.model(it.modelId)?.rank ?: Int.MAX_VALUE }
            .thenComparing(CHEAPEST_ORDER)

    /** `auto` = cheapest within the fastest latency band; out-of-band appended. */
    private fun autoOrder(candidates: List<Candidate>): List<Candidate> {
        val known = candidates.mapNotNull { latency.ewma(it.provider.id) }
        if (known.isEmpty()) return candidates.sortedWith(CHEAPEST_ORDER)
        val best = known.min()
        val (inBand, outOfBand) = candidates.partition {
            (latency.ewma(it.provider.id) ?: best) <= best * autoBandFactor
        }
        return inBand.sortedWith(CHEAPEST_ORDER) + outOfBand.sortedWith(CHEAPEST_ORDER)
    }

    companion object {
        /** Blended USD/MTok price; unpriced candidates sort last. */
        fun blendedPrice(c: Candidate): Double =
            c.pricing?.let { it.inPerMTok + it.outPerMTok } ?: Double.MAX_VALUE

        /** Total order: price asc, then provider id, then model id. */
        val CHEAPEST_ORDER: Comparator<Candidate> =
            compareBy<Candidate> { blendedPrice(it) }
                .thenBy { it.provider.id }
                .thenBy { it.modelId }
    }
}
