package xyz.mdhv.asom.routing

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.catalogue.AuthSpec
import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.catalogue.ModelEntry
import xyz.mdhv.asom.catalogue.Pricing
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.catalogue.ProviderKind
import xyz.mdhv.asom.contract.AsomException
import xyz.mdhv.asom.contract.Policy

/**
 * Property-style tests (seeded, deterministic): ordering/filter laws hold on
 * randomly generated catalogues, not just the fixture.
 */
class RouterPropertyTest {

    private val seed = 20260704
    private val iterations = 200

    private data class World(
        val catalogue: Catalogue,
        val keyed: Set<String>,
        val latency: LatencyTracker,
    )

    private fun randomWorld(rng: Random): World {
        val modelIds = (1..rng.nextInt(1, 5)).map { "model-$it" }
        val models = modelIds.map { id ->
            ModelEntry(id = id, family = "fam", kind = "chat", rank = if (rng.nextBoolean()) rng.nextInt(1, 6) else null)
        }
        val providers = (1..rng.nextInt(1, 7)).map { i ->
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
        val keyed = providers.map { it.id }.filter { rng.nextBoolean() }.toSet()
        val latency = LatencyTracker().apply {
            providers.forEach { if (rng.nextBoolean()) record(it.id, rng.nextLong(20, 2000)) }
        }
        return World(Catalogue(1, "2026-07-04T00:00:00Z", providers, models), keyed, latency)
    }

    private fun router(w: World, policy: Policy = Policy.AUTO) = Router(
        catalogue = { w.catalogue },
        keys = { it in w.keyed },
        latency = w.latency,
        cooldowns = CooldownRegistry(clock = { 0L }),
        defaultPolicy = policy,
    )

    private fun planOrNull(w: World, q: RouteQuery, policy: Policy = Policy.AUTO): List<Candidate>? =
        try {
            router(w, policy).plan(q)
        } catch (e: AsomException) {
            null
        }

    @Test
    fun `filter soundness — every planned candidate is keyed, programmatic, and no-train-clean`() {
        val rng = Random(seed)
        repeat(iterations) {
            val w = randomWorld(rng)
            val noTrain = rng.nextBoolean()
            val plan = planOrNull(w, RouteQuery(model = "cheapest", noTrain = noTrain)) ?: return@repeat
            for (c in plan) {
                assertTrue(c.provider.id in w.keyed, "unkeyed provider planned")
                assertTrue(c.provider.programmaticAllowed, "non-programmatic provider planned")
                if (noTrain) assertTrue(!c.provider.trainsOnData, "no-train violated")
                assertTrue(c.provider.serves(c.modelId), "provider does not serve planned model")
            }
        }
    }

    @Test
    fun `cheapest law — blended price is non-decreasing`() {
        val rng = Random(seed + 1)
        repeat(iterations) {
            val w = randomWorld(rng)
            val plan = planOrNull(w, RouteQuery(model = "cheapest")) ?: return@repeat
            val prices = plan.map { Router.blendedPrice(it) }
            assertEquals(prices.sorted(), prices, "cheapest order not price-ascending")
        }
    }

    @Test
    fun `fastest law — known ewmas are non-decreasing and precede unmeasured`() {
        val rng = Random(seed + 2)
        repeat(iterations) {
            val w = randomWorld(rng)
            val plan = planOrNull(w, RouteQuery(model = "fastest")) ?: return@repeat
            val ewmas = plan.map { w.latency.ewma(it.provider.id) }
            val firstUnknown = ewmas.indexOfFirst { it == null }
            if (firstUnknown >= 0) {
                assertTrue(ewmas.drop(firstUnknown).all { it == null }, "measured provider after unmeasured")
            }
            val known = ewmas.filterNotNull()
            assertEquals(known.sorted(), known, "fastest order not ewma-ascending")
        }
    }

    @Test
    fun `best-reasoning law — rank is non-decreasing, unranked last`() {
        val rng = Random(seed + 3)
        repeat(iterations) {
            val w = randomWorld(rng)
            val plan = planOrNull(w, RouteQuery(model = "best-reasoning")) ?: return@repeat
            val ranks = plan.map { w.catalogue.model(it.modelId)?.rank ?: Int.MAX_VALUE }
            assertEquals(ranks.sorted(), ranks, "best-reasoning order not rank-ascending")
        }
    }

    @Test
    fun `auto law — in-band prefix is price-sorted and within 2x best known ewma`() {
        val rng = Random(seed + 4)
        repeat(iterations) {
            val w = randomWorld(rng)
            val plan = planOrNull(w, RouteQuery(model = "auto")) ?: return@repeat
            val known = plan.mapNotNull { w.latency.ewma(it.provider.id) }
            if (known.isEmpty()) {
                val prices = plan.map { Router.blendedPrice(it) }
                assertEquals(prices.sorted(), prices, "auto without latency data must be cheapest")
                return@repeat
            }
            val best = known.min()
            val inBand = plan.takeWhile { (w.latency.ewma(it.provider.id) ?: best) <= best * 2.0 }
            val inBandPrices = inBand.map { Router.blendedPrice(it) }
            assertEquals(inBandPrices.sorted(), inBandPrices, "auto in-band prefix not price-sorted")
        }
    }

    @Test
    fun `determinism — identical inputs produce identical plans`() {
        val rng = Random(seed + 5)
        repeat(iterations) {
            val w = randomWorld(rng)
            for (model in listOf("cheapest", "fastest", "best-reasoning", "auto")) {
                val a = planOrNull(w, RouteQuery(model = model))
                val b = planOrNull(w, RouteQuery(model = model))
                assertEquals(
                    a?.map { it.provider.id to it.modelId },
                    b?.map { it.provider.id to it.modelId },
                )
            }
        }
    }

    @Test
    fun `total order — equal prices tie-break by provider id then model id`() {
        val price = Pricing(1.0, 1.0)
        fun provider(id: String, models: List<String>) = ProviderEntry(
            id = id, displayName = id, kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "https://x/v1", auth = AuthSpec("bearer"),
            trainsOnData = false, programmaticAllowed = true,
            pricing = models.associateWith { price }, models = models,
        )
        val cat = Catalogue(
            version = 1, updatedAt = "t",
            providers = listOf(provider("b-prov", listOf("m2", "m1")), provider("a-prov", listOf("m1"))),
            models = listOf(ModelEntry("m1", "f", "chat"), ModelEntry("m2", "f", "chat")),
        )
        val w = World(cat, setOf("a-prov", "b-prov"), LatencyTracker())
        val plan = router(w).plan(RouteQuery(model = "cheapest"))
        assertEquals(
            listOf("a-prov" to "m1", "b-prov" to "m1", "b-prov" to "m2"),
            plan.map { it.provider.id to it.modelId },
        )
    }
}
