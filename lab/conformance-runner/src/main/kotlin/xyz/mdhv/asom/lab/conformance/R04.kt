package xyz.mdhv.asom.lab.conformance

import java.util.SplittableRandom
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.contract.AsomException
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.routing.CooldownRegistry
import xyz.mdhv.asom.routing.LatencyTracker
import xyz.mdhv.asom.routing.RouteQuery
import xyz.mdhv.asom.routing.Router

/**
 * R04: the v1 router's pins as data, against the real `Router` (LAB_SPEC 3.9). The router is constructed as
 * `Router(catalogue, keys, latency, cooldowns, Policy.AUTO, hasLocalEngine = false)`. R04 is RL1's oracle.
 *
 * Law "permutation-invariant": every vector is re-run on shuffled catalogues (provider order, model order,
 * per-provider model order); the ordered plan must not change. `input.permutationSeeds` adds seeded shuffles.
 */
class R04Checker : FamilyChecker("R04") {
    override val requiredLaws = setOf("plans", "permutation-invariant", "rejects", "orders")

    override fun observe(v: Vector): Observed {
        val cat = catalogue(v.input)
        val first = plan(v.input, cat)
        bump("plans")
        bump(if (first is Observed.Reject) "rejects" else "orders")

        val seeds = v.input.arrOrNull("permutationSeeds")?.map { (it as kotlinx.serialization.json.JsonPrimitive).content.toLong() }
            ?: listOf(1L, 2L, 3L, 4L)
        val variants = listOf(reversed(cat)) + seeds.map { shuffled(cat, it) }
        for (variant in variants) {
            val again = plan(v.input, variant)
            if (again != first) throw LawViolation("plan changed under catalogue permutation: $first vs $again")
            bump("permutation-invariant")
        }
        return first
    }

    private fun catalogue(input: JsonObject): Catalogue {
        val spec = input["catalogue"]
        return if (spec == null || (spec is kotlinx.serialization.json.JsonPrimitive && spec.content == "fixture")) {
            CatalogueParser.parse(Repo.fixtureCatalogue.readUtf8())
        } else {
            CatalogueParser.parse(spec.jsonObject.toString())
        }
    }

    private fun plan(input: JsonObject, cat: Catalogue): Observed {
        val keysPresent = input.strList("keysPresent").toSet()
        val latency = LatencyTracker().apply {
            preload(input.objOrNull("latencyEwmaMs")?.entries?.associate { (k, v) -> k to java.lang.Double.parseDouble((v as kotlinx.serialization.json.JsonPrimitive).content) } ?: emptyMap())
        }
        val cooldowns = CooldownRegistry(clock = { FIXED_NOW }).also { c -> input.strList("cooling").forEach { c.recordFailure(it) } }
        val router = Router(
            catalogue = { cat },
            keys = { it in keysPresent },
            latency = latency,
            cooldowns = cooldowns,
            defaultPolicy = Policy.AUTO,
            hasLocalEngine = false,
        )
        val q = input.obj("query")
        val query = RouteQuery(
            model = q.str("model"),
            policyHeader = q.strOrNull("policyHeader")?.let { Policy.fromWire(it) ?: throw LawViolation("unknown policy '$it'") },
            fallback = q.strList("fallback"),
            noTrain = q.bool("noTrain"),
        )
        return try {
            Observed.Ok(jsonStrings(router.plan(query).map { "${it.provider.id}/${it.modelId}" }))
        } catch (e: AsomException) {
            Observed.Reject(e.code.name)
        }
    }

    private fun reversed(c: Catalogue): Catalogue =
        c.copy(
            providers = c.providers.reversed().map { it.copy(models = it.models.reversed()) },
            models = c.models.reversed(),
        )

    private fun shuffled(c: Catalogue, seed: Long): Catalogue {
        val rnd = SplittableRandom(seed)
        fun <T> List<T>.shuffle(): List<T> {
            val a = toMutableList()
            for (i in a.size - 1 downTo 1) {
                val j = rnd.nextInt(i + 1)
                val t = a[i]; a[i] = a[j]; a[j] = t
            }
            return a
        }
        return c.copy(
            providers = c.providers.shuffle().map { it.copy(models = it.models.shuffle()) },
            models = c.models.shuffle(),
        )
    }
}
