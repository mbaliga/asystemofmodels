package xyz.mdhv.asom.catalogue

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Golden tests against the committed fixture `fixtures/catalogue.v1.json` —
 * the single source of truth until the owner provides `CATALOGUE_URL`.
 */
class CatalogueGoldenTest {

    private val fixtureFile = File("../../fixtures/catalogue.v1.json")
    private val catalogue: Catalogue by lazy { CatalogueParser.parse(fixtureFile.readText()) }

    @Test
    fun `fixture parses`() {
        assertTrue(fixtureFile.exists(), "fixture missing at ${fixtureFile.absolutePath}")
        assertEquals(1, catalogue.version)
        assertEquals("2026-07-04T00:00:00Z", catalogue.updatedAt)
    }

    @Test
    fun `golden provider — openrouter`() {
        val p = catalogue.provider("openrouter")!!
        assertEquals("OpenRouter", p.displayName)
        assertEquals(ProviderKind.OPENAI_COMPAT, p.kind)
        assertEquals("https://openrouter.ai/api/v1", p.baseUrl)
        assertEquals("bearer", p.auth.type)
        assertEquals(false, p.trainsOnData)
        assertEquals(true, p.programmaticAllowed)
        assertEquals(60, p.rate?.rpm)
        assertEquals(10000, p.rate?.rpd)
        assertEquals(Pricing(inPerMTok = 0.10, outPerMTok = 0.30), p.pricing["llama-3.3-70b"])
        assertTrue(p.serves("llama-3.3-70b"))
    }

    @Test
    fun `golden provider — anthropic uses the native driver kind`() {
        assertEquals(ProviderKind.ANTHROPIC, catalogue.provider("anthropic")!!.kind)
    }

    @Test
    fun `golden model — llama has downloadable weights`() {
        val m = catalogue.model("llama-3.3-70b")!!
        assertEquals("llama", m.family)
        assertEquals("chat", m.kind)
        assertEquals(131072L, m.ctx)
        assertEquals(3, m.rank)
        val f = m.files.single()
        assertEquals(42520397952L, f.bytes)
        assertEquals("Q4_K_M", f.quant)
        assertEquals(64, f.sha256.length)
    }

    // ---- Brief §6 requires the fixture to keep §7's tests exercisable. ----

    @Test
    fun `fixture law — at least 3 providers`() {
        assertTrue(catalogue.providers.size >= 3, "brief §6: fixture must contain ≥3 providers")
    }

    @Test
    fun `fixture law — at least one provider trains on data`() {
        assertTrue(catalogue.providers.any { it.trainsOnData }, "brief §6: need a trainsOnData:true provider")
    }

    @Test
    fun `fixture law — a common model served at different prices by ≥2 providers`() {
        val priced = catalogue.models.map { model ->
            catalogue.providers
                .filter { it.serves(model.id) && it.pricing[model.id] != null }
                .map { it.pricing[model.id]!! }
        }
        assertTrue(
            priced.any { prices -> prices.size >= 2 && prices.toSet().size >= 2 },
            "brief §6: need ≥2 providers serving a common model at different prices",
        )
    }

    @Test
    fun `fixture law — a programmaticAllowed false provider exists for filter tests`() {
        assertTrue(catalogue.providers.any { !it.programmaticAllowed })
    }

    // ---- Parser behavior ----

    @Test
    fun `unknown keys are tolerated`() {
        val extended = fixtureFile.readText().replaceFirst(
            "\"version\": 1,",
            "\"version\": 1, \"futureField\": {\"x\": 1},",
        )
        assertEquals(1, CatalogueParser.parse(extended).version)
    }

    @Test
    fun `unknown provider kind fails loudly`() {
        val broken = fixtureFile.readText().replaceFirst("\"openai-compat\"", "\"quantum-compat\"")
        assertFailsWith<Exception> { CatalogueParser.parse(broken) }
    }

    @Test
    fun `wrong version fails loudly`() {
        val broken = fixtureFile.readText().replaceFirst("\"version\": 1", "\"version\": 2")
        assertFailsWith<CatalogueValidationException> { CatalogueParser.parse(broken) }
    }

    @Test
    fun `duplicate provider ids fail loudly`() {
        val dup = fixtureFile.readText().replaceFirst("\"id\": \"groq\"", "\"id\": \"openrouter\"")
        assertFailsWith<CatalogueValidationException> { CatalogueParser.parse(dup) }
    }

    @Test
    fun `pricing for an unserved model fails loudly`() {
        val broken = fixtureFile.readText().replaceFirst(
            "\"models\": [\"llama-3.3-70b\", \"deepseek-v3\"]",
            "\"models\": [\"llama-3.3-70b\"]",
        )
        assertFailsWith<CatalogueValidationException> { CatalogueParser.parse(broken) }
    }
}
