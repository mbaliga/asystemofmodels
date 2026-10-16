package xyz.mdhv.asom.routing

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.mdhv.asom.catalogue.AuthSpec
import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.catalogue.ModelEntry
import xyz.mdhv.asom.catalogue.Pricing
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.catalogue.ProviderKind
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomException
import xyz.mdhv.asom.contract.Policy

/**
 * Property-style tests (seeded, deterministic): ordering/filter/breaker laws
 * hold on randomly generated catalogues, not just the fixture.
 *
 * Two rules keep these honest. Expected orderings are re-derived from the
 * generated [World] — raw catalogue pricing and the raw latency samples — and
 * never from the router's own key functions, so an assertion cannot agree with
 * a mutated implementation. And every law counts the iterations that actually
 * exercised it: a regression that made `plan` throw, or collapse to one
 * candidate, fails the run instead of passing vacuously.
 */
class RouterPropertyTest {

    private val seed = 20260704
    private val iterations = 200

    private data class World(
        val catalogue: Catalogue,
        val keyed: Set<String>,
        /**
         * Exactly one latency sample per measured provider, so the expected
         * `fastest`/`auto` ordering is derivable without asking LatencyTracker
         * (the first sample seeds the EWMA verbatim).
         */
        val samples: Map<String, Double>,
    ) {
        val latency: LatencyTracker =
            LatencyTracker().apply { samples.forEach { (id, ms) -> record(id, ms.toLong()) } }
    }

    private fun randomWorld(rng: Random): World {
        val modelIds = (1..rng.nextInt(1, 5)).map { "model-$it" }
        val models = modelIds.map { id ->
            ModelEntry(id = id, family = "fam", kind = "chat", rank = if (rng.nextBoolean()) rng.nextInt(1, 6) else null)
        }
        val providers = (1..rng.nextInt(2, 7)).map { i ->
            val served = modelIds.filter { rng.nextBoolean() }.ifEmpty { listOf(modelIds.first()) }
            ProviderEntry(
                id = "prov-$i",
                displayName = "Provider $i",
                kind = ProviderKind.OPENAI_COMPAT,
                baseUrl = "https://p$i.example/v1",
                auth = AuthSpec("bearer"),
                trainsOnData = rng.nextInt(4) == 0,
                programmaticAllowed = rng.nextInt(5) != 0,
                pricing = served.filter { rng.nextInt(5) != 0 }.associateWith {
                    Pricing(rng.nextDouble(0.01, 5.0), rng.nextDouble(0.01, 20.0))
                },
                models = served,
            )
        }
        val keyed = providers.map { it.id }.filter { rng.nextInt(4) != 0 }.toSet()
        val samples = providers.filter { rng.nextBoolean() }
            .associate { it.id to rng.nextLong(20, 2000).toDouble() }
        return World(Catalogue(1, "2026-07-04T00:00:00Z", providers, models), keyed, samples)
    }

    private fun router(
        w: World,
        policy: Policy = Policy.AUTO,
        cooldowns: CooldownRegistry = CooldownRegistry(clock = { 0L }),
    ) = Router(
        catalogue = { w.catalogue },
        keys = { it in w.keyed },
        latency = w.latency,
        cooldowns = cooldowns,
        defaultPolicy = policy,
    )

    /**
     * Returns null only for the failures the generator can legitimately
     * produce; any other typed failure is a router regression, not a skip.
     */
    private fun planOrNull(
        w: World,
        q: RouteQuery,
        policy: Policy = Policy.AUTO,
    ): List<Candidate>? =
        try {
            router(w, policy).plan(q)
        } catch (e: AsomException) {
            assertEquals(AsomErrorCode.NO_PROVIDER_KEY, e.code, "unexpected planning failure: ${e.message}")
            null
        }

    // ---- Oracles derived from the World, never from Router ----

    /** §7 `cheapest`: blended in+out USD/MTok, straight from catalogue pricing. */
    private fun price(w: World, providerId: String, modelId: String): Double {
        val p = w.catalogue.providers.first { it.id == providerId }.pricing[modelId] ?: return Double.MAX_VALUE
        return p.inPerMTok + p.outPerMTok
    }

    private fun rank(w: World, modelId: String): Double =
        (w.catalogue.models.first { it.id == modelId }.rank ?: Int.MAX_VALUE).toDouble()

    private fun usable(w: World, noTrain: Boolean = false): Set<Pair<String, String>> =
        w.catalogue.providers
            .filter { it.programmaticAllowed && it.id in w.keyed && !(noTrain && it.trainsOnData) }
            .flatMap { p -> p.models.map { p.id to it } }
            .toSet()

    private fun pairs(plan: List<Candidate>) = plan.map { it.provider.id to it.modelId }

    /** Total sort key: policy primary, then §7's price → provider id → model id tie-break. */
    private data class Key(val primary: Double, val price: Double, val providerId: String, val modelId: String)

    private val keyOrder = compareBy<Key>({ it.primary }, { it.price }, { it.providerId }, { it.modelId })

    private fun keys(w: World, plan: List<Candidate>, primary: (Candidate) -> Double): List<Key> =
        plan.map { Key(primary(it), price(w, it.provider.id, it.modelId), it.provider.id, it.modelId) }

    private fun assertAscending(keys: List<Key>, message: String) =
        assertEquals(keys.sortedWith(keyOrder), keys, message)

    private fun assertExercised(count: Int, min: Int, law: String) =
        assertTrue(count >= min, "$law was exercised by only $count of $iterations iterations (need $min)")

    // ---- Filter laws ----

    @Test
    fun `filter law — the plan is exactly the usable candidate set, no duplicates`() {
        val rng = Random(seed)
        var exercised = 0
        repeat(iterations) {
            val w = randomWorld(rng)
            val noTrain = rng.nextBoolean()
            val plan = planOrNull(w, RouteQuery(model = "cheapest", noTrain = noTrain)) ?: return@repeat
            exercised++
            assertEquals(usable(w, noTrain), pairs(plan).toSet(), "plan is not the usable candidate set")
            assertEquals(plan.size, plan.toSet().size, "plan contains a duplicate candidate")
            for (c in plan) {
                assertTrue(c.provider.id in w.keyed, "unkeyed provider planned")
                assertTrue(c.provider.programmaticAllowed, "non-programmatic provider planned")
                if (noTrain) assertTrue(!c.provider.trainsOnData, "no-train violated")
                assertTrue(c.provider.serves(c.modelId), "provider does not serve planned model")
            }
        }
        assertExercised(exercised, iterations / 2, "filter law")
    }

    // ---- Ordering laws ----

    @Test
    fun `cheapest law — ascending by catalogue-derived blended price`() {
        val rng = Random(seed + 1)
        var exercised = 0
        repeat(iterations) {
            val w = randomWorld(rng)
            val plan = planOrNull(w, RouteQuery(model = "cheapest")) ?: return@repeat
            if (plan.size > 1) exercised++
            assertAscending(keys(w, plan) { 0.0 }, "cheapest order is not blended-price ascending")
        }
        assertExercised(exercised, iterations / 2, "cheapest law")
    }

    @Test
    fun `fastest law — ascending by recorded latency sample, unmeasured last`() {
        val rng = Random(seed + 2)
        var exercised = 0
        repeat(iterations) {
            val w = randomWorld(rng)
            val plan = planOrNull(w, RouteQuery(model = "fastest")) ?: return@repeat
            val measured = plan.count { it.provider.id in w.samples }
            if (plan.size > 1 && measured > 0) exercised++
            assertAscending(
                keys(w, plan) { w.samples[it.provider.id] ?: Double.MAX_VALUE },
                "fastest order is not latency ascending with unmeasured providers last",
            )
        }
        assertExercised(exercised, iterations / 2, "fastest law")
    }

    @Test
    fun `best-reasoning law — ascending by catalogue rank, unranked last`() {
        val rng = Random(seed + 3)
        var exercised = 0
        repeat(iterations) {
            val w = randomWorld(rng)
            val plan = planOrNull(w, RouteQuery(model = "best-reasoning")) ?: return@repeat
            if (plan.size > 1) exercised++
            assertAscending(keys(w, plan) { rank(w, it.modelId) }, "best-reasoning order is not rank ascending")
        }
        assertExercised(exercised, iterations / 2, "best-reasoning law")
    }

    @Test
    fun `auto law — every in-band candidate precedes every out-of-band one, both halves price-sorted`() {
        val rng = Random(seed + 4)
        var exercised = 0
        var split = 0
        repeat(iterations) {
            val w = randomWorld(rng)
            val plan = planOrNull(w, RouteQuery(model = "auto")) ?: return@repeat
            val measured = plan.mapNotNull { w.samples[it.provider.id] }
            if (measured.isEmpty()) {
                assertAscending(keys(w, plan) { 0.0 }, "auto without latency data must be pure cheapest")
                return@repeat
            }
            val best = measured.min()
            // Partition the WHOLE plan, not a prefix: a plan that led with an
            // out-of-band candidate would make a takeWhile-based check vacuous.
            val (inBand, outOfBand) = plan.partition { (w.samples[it.provider.id] ?: best) <= best * 2.0 }
            assertEquals(inBand + outOfBand, plan, "auto emitted an out-of-band candidate before an in-band one")
            assertAscending(keys(w, inBand) { 0.0 }, "auto in-band half is not price-sorted")
            assertAscending(keys(w, outOfBand) { 0.0 }, "auto out-of-band half is not price-sorted")
            if (plan.size > 1) exercised++
            if (inBand.isNotEmpty() && outOfBand.isNotEmpty()) split++
        }
        assertExercised(exercised, iterations / 2, "auto law")
        assertExercised(split, iterations / 10, "auto partition (both halves non-empty)")
    }

    @Test
    fun `permutation invariance — the plan does not depend on catalogue ordering`() {
        val rng = Random(seed + 5)
        var exercised = 0
        repeat(iterations) {
            val w = randomWorld(rng)
            val shuffled = w.copy(
                catalogue = w.catalogue.copy(
                    providers = w.catalogue.providers.shuffled(rng).map { it.copy(models = it.models.shuffled(rng)) },
                    models = w.catalogue.models.shuffled(rng),
                ),
            )
            for (model in listOf("cheapest", "fastest", "best-reasoning", "auto")) {
                val a = planOrNull(w, RouteQuery(model = model)) ?: continue
                val b = planOrNull(shuffled, RouteQuery(model = model))
                assertEquals(pairs(a), b?.let { pairs(it) }, "plan for '$model' depends on catalogue iteration order")
                if (a.size > 1) exercised++
            }
        }
        assertExercised(exercised, iterations, "permutation invariance")
    }

    // ---- X-Asom-Fallback law (§7: restricts AND orders) ----

    @Test
    fun `fallback law — restricted to the header providers, in header order, once each`() {
        val rng = Random(seed + 6)
        var exercised = 0
        var repeatsTried = 0
        repeat(iterations) {
            val w = randomWorld(rng)
            val named = w.catalogue.providers.map { it.id }.filter { rng.nextInt(3) != 0 }
            if (named.isEmpty()) return@repeat
            // A repeated id must not become a second attempt on one provider.
            val repeated = rng.nextBoolean()
            val header = if (repeated) named + named.first() else named
            val plan = planOrNull(w, RouteQuery(model = "cheapest", fallback = header)) ?: return@repeat
            if (plan.size > 1) exercised++
            if (repeated) repeatsTried++

            val planned = pairs(plan)
            assertEquals(usable(w).filter { it.first in named }.toSet(), planned.toSet(), "fallback set is not the restriction")
            assertEquals(plan.size, plan.toSet().size, "repeated fallback id produced a duplicate attempt")
            assertEquals(
                named.filter { id -> planned.any { it.first == id } },
                planned.map { it.first }.distinct(),
                "fallback plan is not grouped in header order",
            )
            for (id in named) {
                val group = plan.filter { it.provider.id == id }
                assertAscending(keys(w, group) { 0.0 }, "candidates within a fallback provider are not price-sorted")
            }
        }
        assertExercised(exercised, iterations / 2, "fallback law")
        assertExercised(repeatsTried, iterations / 4, "fallback law with a repeated provider id")
    }

    // ---- Breaker law (§7 P2 gate: breaker behavior) ----

    @Test
    fun `breaker law — cooling providers are excluded, and return when the deadline passes`() {
        val rng = Random(seed + 7)
        var exercised = 0
        var allCooling = 0
        repeat(iterations) {
            val w = randomWorld(rng)
            var now = 1_000L
            val cooldowns = CooldownRegistry(clock = { now })
            val failing = w.catalogue.providers.map { it.id }.filter { rng.nextBoolean() }.toSet()
            failing.forEach { cooldowns.recordFailure(it) }

            val router = router(w, Policy.CHEAPEST, cooldowns)
            val q = RouteQuery(model = "cheapest")
            val all = usable(w)
            val live = all.filterNot { it.first in failing }.toSet()

            when {
                all.isEmpty() -> assertCode(AsomErrorCode.NO_PROVIDER_KEY) { router.plan(q) }
                live.isEmpty() -> {
                    assertCode(AsomErrorCode.ALL_PROVIDERS_COOLING) { router.plan(q) }
                    allCooling++
                }
                else -> {
                    assertEquals(live, pairs(router.plan(q)).toSet(), "a cooling provider was planned")
                    if (live.size < all.size) exercised++
                }
            }

            now += 900_001
            if (all.isNotEmpty()) {
                assertEquals(all, pairs(router.plan(q)).toSet(), "providers did not return after the cooldown deadline")
            }
        }
        assertExercised(exercised, iterations / 2, "breaker law (some providers cooling)")
        assertExercised(allCooling, iterations / 20, "breaker law (all providers cooling)")
    }

    private fun assertCode(code: AsomErrorCode, block: () -> Unit) {
        assertEquals(code, assertFailsWith<AsomException>(block = block).code)
    }

    // ---- Concrete pins for the pricing semantics the laws above rely on ----

    @Test
    fun `cheapest compares blended price, not input price`() {
        val cat = Catalogue(
            version = 1, updatedAt = "t",
            providers = listOf(
                priced("cheap-in", mapOf("m1" to Pricing(0.10, 10.0))),
                priced("balanced", mapOf("m1" to Pricing(0.20, 0.30))),
            ),
            models = listOf(ModelEntry("m1", "f", "chat")),
        )
        val w = World(cat, setOf("cheap-in", "balanced"), emptyMap())
        val plan = router(w).plan(RouteQuery(model = "cheapest"))
        assertEquals(listOf("balanced" to "m1", "cheap-in" to "m1"), pairs(plan))
    }

    @Test
    fun `total order — equal prices tie-break by provider id then model id`() {
        val price = Pricing(1.0, 1.0)
        val cat = Catalogue(
            version = 1, updatedAt = "t",
            providers = listOf(
                priced("b-prov", mapOf("m2" to price, "m1" to price)),
                priced("a-prov", mapOf("m1" to price)),
            ),
            models = listOf(ModelEntry("m1", "f", "chat"), ModelEntry("m2", "f", "chat")),
        )
        val w = World(cat, setOf("a-prov", "b-prov"), emptyMap())
        val plan = router(w).plan(RouteQuery(model = "cheapest"))
        assertEquals(listOf("a-prov" to "m1", "b-prov" to "m1", "b-prov" to "m2"), pairs(plan))
    }

    private fun priced(id: String, pricing: Map<String, Pricing>) = ProviderEntry(
        id = id, displayName = id, kind = ProviderKind.OPENAI_COMPAT,
        baseUrl = "https://x/v1", auth = AuthSpec("bearer"),
        trainsOnData = false, programmaticAllowed = true,
        pricing = pricing, models = pricing.keys.toList(),
    )
}
