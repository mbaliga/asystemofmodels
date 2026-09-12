package xyz.mdhv.asom.routing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomException
import xyz.mdhv.asom.contract.Policy

/**
 * Router behavior against the committed fixture. Fixture price map (blended
 * in+out USD/MTok): trainy-ai/llama .08 (trains!), openrouter/llama .40,
 * openrouter/deepseek 1.10, groq/llama 1.38, anthropic/claude 18.00,
 * webchat-only/llama .02 (programmaticAllowed=false).
 */
class RouterTest {

    private val catalogue = CatalogueParser.parse(File("../../fixtures/catalogue.v1.json").readText())
    private val latency = LatencyTracker()
    private var now = 0L
    private val cooldowns = CooldownRegistry(clock = { now })

    private var keyed: Set<String> = setOf("openrouter", "groq", "trainy-ai", "anthropic", "webchat-only")

    private fun router(defaultPolicy: Policy = Policy.AUTO, hasLocalEngine: Boolean = false) = Router(
        catalogue = { catalogue },
        keys = { it in keyed },
        latency = latency,
        cooldowns = cooldowns,
        defaultPolicy = defaultPolicy,
        hasLocalEngine = hasLocalEngine,
    )

    private fun ids(plan: List<Candidate>) = plan.map { "${it.provider.id}/${it.modelId}" }

    // ---- Filter laws ----

    @Test
    fun `programmaticAllowed=false providers never route, even when cheapest`() {
        val plan = router().plan(RouteQuery(model = "llama-3.3-70b", policyHeader = Policy.CHEAPEST))
        assertTrue(plan.none { it.provider.id == "webchat-only" })
    }

    @Test
    fun `no-train filter excludes trainsOnData providers`() {
        val q = RouteQuery(model = "llama-3.3-70b", policyHeader = Policy.CHEAPEST, noTrain = true)
        assertEquals(
            listOf("openrouter/llama-3.3-70b", "groq/llama-3.3-70b"),
            ids(router().plan(q)),
        )
    }

    @Test
    fun `key absence excludes a provider`() {
        keyed = setOf("groq")
        val plan = router().plan(RouteQuery(model = "llama-3.3-70b"))
        assertEquals(listOf("groq/llama-3.3-70b"), ids(plan))
    }

    @Test
    fun `no keys at all is a typed NO_PROVIDER_KEY`() {
        keyed = emptySet()
        val ex = assertFailsWith<AsomException> { router().plan(RouteQuery(model = "llama-3.3-70b")) }
        assertEquals(AsomErrorCode.NO_PROVIDER_KEY, ex.code)
    }

    @Test
    fun `unknown model is a typed MODEL_UNKNOWN`() {
        val ex = assertFailsWith<AsomException> { router().plan(RouteQuery(model = "gpt-99")) }
        assertEquals(AsomErrorCode.MODEL_UNKNOWN, ex.code)
    }

    // ---- local-only (v1: no engine, fail loudly §2) ----

    @Test
    fun `local-only model fails with typed LOCAL_ENGINE_ABSENT`() {
        val ex = assertFailsWith<AsomException> { router().plan(RouteQuery(model = "local-only")) }
        assertEquals(AsomErrorCode.LOCAL_ENGINE_ABSENT, ex.code)
    }

    @Test
    fun `local-only policy header fails the same way for concrete models`() {
        val ex = assertFailsWith<AsomException> {
            router().plan(RouteQuery(model = "llama-3.3-70b", policyHeader = Policy.LOCAL_ONLY))
        }
        assertEquals(AsomErrorCode.LOCAL_ENGINE_ABSENT, ex.code)
    }

    @Test
    fun `even a hasLocalEngine router refuses local routing in v1`() {
        val ex = assertFailsWith<AsomException> {
            router(hasLocalEngine = true).plan(RouteQuery(model = "local-only"))
        }
        assertEquals(AsomErrorCode.LOCAL_ENGINE_ABSENT, ex.code)
    }

    // ---- Ordering laws ----

    @Test
    fun `cheapest orders by blended price ascending — concrete model`() {
        val plan = router().plan(RouteQuery(model = "llama-3.3-70b", policyHeader = Policy.CHEAPEST))
        assertEquals(
            listOf("trainy-ai/llama-3.3-70b", "openrouter/llama-3.3-70b", "groq/llama-3.3-70b"),
            ids(plan),
        )
    }

    @Test
    fun `cheapest as virtual model spans all provider-model pairs`() {
        val plan = router().plan(RouteQuery(model = "cheapest"))
        assertEquals(
            listOf(
                "trainy-ai/llama-3.3-70b",
                "openrouter/llama-3.3-70b",
                "openrouter/deepseek-v3",
                "groq/llama-3.3-70b",
                "anthropic/claude-sonnet-4-5",
            ),
            ids(plan),
        )
    }

    @Test
    fun `fastest orders by ewma ascending, unmeasured providers last`() {
        latency.record("groq", 80)
        latency.record("openrouter", 400)
        val plan = router().plan(RouteQuery(model = "llama-3.3-70b", policyHeader = Policy.FASTEST))
        assertEquals(
            listOf("groq/llama-3.3-70b", "openrouter/llama-3.3-70b", "trainy-ai/llama-3.3-70b"),
            ids(plan),
        )
    }

    @Test
    fun `best-reasoning orders by catalogue rank, price breaks rank ties`() {
        val plan = router().plan(RouteQuery(model = "best-reasoning"))
        assertEquals(
            listOf(
                "anthropic/claude-sonnet-4-5",   // rank 1
                "openrouter/deepseek-v3",        // rank 2
                "trainy-ai/llama-3.3-70b",       // rank 3, blended .08
                "openrouter/llama-3.3-70b",      // rank 3, blended .40
                "groq/llama-3.3-70b",            // rank 3, blended 1.38
            ),
            ids(plan),
        )
    }

    @Test
    fun `auto is cheapest within the fastest latency band, out-of-band appended`() {
        latency.record("openrouter", 100)
        latency.record("groq", 150)      // in band (≤ 2×100)
        latency.record("trainy-ai", 900) // out of band
        val plan = router().plan(RouteQuery(model = "llama-3.3-70b"))
        assertEquals(
            listOf("openrouter/llama-3.3-70b", "groq/llama-3.3-70b", "trainy-ai/llama-3.3-70b"),
            ids(plan),
        )
    }

    @Test
    fun `auto treats unmeasured providers optimistically in-band`() {
        latency.record("openrouter", 100)
        latency.record("groq", 900) // out of band; trainy-ai unmeasured → in band
        val plan = router().plan(RouteQuery(model = "llama-3.3-70b"))
        assertEquals(
            listOf("trainy-ai/llama-3.3-70b", "openrouter/llama-3.3-70b", "groq/llama-3.3-70b"),
            ids(plan),
        )
    }

    @Test
    fun `auto with no latency data degrades to cheapest`() {
        val plan = router().plan(RouteQuery(model = "llama-3.3-70b"))
        assertEquals(
            listOf("trainy-ai/llama-3.3-70b", "openrouter/llama-3.3-70b", "groq/llama-3.3-70b"),
            ids(plan),
        )
    }

    // ---- X-Asom-Fallback (§7: overrides ordering) ----

    @Test
    fun `fallback header restricts and orders the candidate set`() {
        val plan = router().plan(
            RouteQuery(model = "llama-3.3-70b", fallback = listOf("groq", "trainy-ai")),
        )
        assertEquals(listOf("groq/llama-3.3-70b", "trainy-ai/llama-3.3-70b"), ids(plan))
    }

    @Test
    fun `fallback naming only unusable providers is a typed NO_PROVIDER_KEY`() {
        val ex = assertFailsWith<AsomException> {
            router().plan(RouteQuery(model = "llama-3.3-70b", fallback = listOf("webchat-only", "nope")))
        }
        assertEquals(AsomErrorCode.NO_PROVIDER_KEY, ex.code)
    }

    @Test
    fun `fallback still respects the no-train filter`() {
        val ex = assertFailsWith<AsomException> {
            router().plan(RouteQuery(model = "llama-3.3-70b", fallback = listOf("trainy-ai"), noTrain = true))
        }
        assertEquals(AsomErrorCode.NO_PROVIDER_KEY, ex.code)
    }

    // ---- Circuit breaker interaction ----

    @Test
    fun `cooling providers are skipped in plan order`() {
        cooldowns.recordFailure("trainy-ai")
        val plan = router().plan(RouteQuery(model = "llama-3.3-70b", policyHeader = Policy.CHEAPEST))
        assertEquals(listOf("openrouter/llama-3.3-70b", "groq/llama-3.3-70b"), ids(plan))
    }

    @Test
    fun `all providers cooling is a typed ALL_PROVIDERS_COOLING`() {
        listOf("trainy-ai", "openrouter", "groq").forEach { cooldowns.recordFailure(it) }
        val ex = assertFailsWith<AsomException> { router().plan(RouteQuery(model = "llama-3.3-70b")) }
        assertEquals(AsomErrorCode.ALL_PROVIDERS_COOLING, ex.code)
    }

    @Test
    fun `cooldown expiry restores the provider`() {
        cooldowns.recordFailure("trainy-ai")
        now += 31_000
        val plan = router().plan(RouteQuery(model = "llama-3.3-70b", policyHeader = Policy.CHEAPEST))
        assertEquals("trainy-ai/llama-3.3-70b", ids(plan).first())
    }
}
