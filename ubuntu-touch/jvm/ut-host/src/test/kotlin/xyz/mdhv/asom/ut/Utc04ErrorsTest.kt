package xyz.mdhv.asom.ut

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomException
import xyz.mdhv.asom.routing.CooldownRegistry
import xyz.mdhv.asom.routing.LatencyTracker
import xyz.mdhv.asom.routing.RouteQuery
import xyz.mdhv.asom.routing.Router
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JString

/**
 * UTC04: the error of a request that no peer can serve. With no paired peer the answer must be exactly what the real v1
 * `Router.plan` answers for a universe with no stored key (RL1's oracle, checked here against the real router and the fixture
 * catalogue), so the two can never drift.
 */
class Utc04ErrorsTest {
    private val fixture: Catalogue by lazy {
        val root = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot is not set"))
        CatalogueParser.parse(File(root, "fixtures/catalogue.v1.json").readText(Charsets.UTF_8))
    }

    private fun routerCode(model: String): String? = try {
        Router(catalogue = { fixture }, keys = { false }, latency = LatencyTracker(), cooldowns = CooldownRegistry(), hasLocalEngine = false)
            .plan(RouteQuery(model))
        null
    } catch (e: AsomException) {
        e.code.name
    }

    @Test
    fun vectors() {
        val vectors = UtcVectors.load("UTC04-errors.json", "UTC04")
        val laws = Laws("UTC04")
        for (v in vectors) {
            val model = v.input.str("model")
            val peers = v.input.arr("peers").map {
                val o = it.asObj()
                PeerView(o.str("alias"), o.arr("models").map { m -> m.asStr() }.toSet(), o.bool("cooling"))
            }
            val catalogueModels = if (v.input["catalogue"] is JString) fixture.models.map { it.id }.toSet() else null
            val got = ErrorMapping.map(model, peers, catalogueModels)
            val want = (v.expectOk!!.asObj()["code"]).let { if (it == JNull) null else (it as JString).value }
            assertEquals(want, got?.name, "${v.id}: ${v.description}")
            laws.bump("code-${want ?: "servable"}")
            if (peers.isEmpty() && catalogueModels != null) {
                assertEquals(want, routerCode(model), "${v.id}: differs from the real v1 Router.plan on a key-less universe")
                laws.bump("router-oracle")
            }
            if (got != null) {
                assertEquals(got.message, UiErrorCode.valueOf(got.name).message)
                laws.bump("message-defined")
            }
        }
        laws.requireAll(
            setOf(
                "code-NO_PROVIDER_KEY", "code-MODEL_UNKNOWN", "code-ALL_PROVIDERS_COOLING", "code-LOCAL_ENGINE_ABSENT", "code-servable", "router-oracle", "message-defined",
            ),
            minimumVectors = 20, vectors = vectors.size,
        )
    }

    @Test
    fun everyUiErrorCodeIsTheV1EnumPlusThreeUiOnlyValues() {
        val v1 = AsomErrorCode.entries.map { it.name }.toSet()
        val ui = UiErrorCode.entries.map { it.name }.toSet()
        assertEquals(setOf("LEDGER_UNAVAILABLE", "MESH_STREAM_INTERRUPTED", "INTERRUPTED_BY_SUSPEND"), ui - v1)
        assertEquals(v1, ui - setOf("LEDGER_UNAVAILABLE", "MESH_STREAM_INTERRUPTED", "INTERRUPTED_BY_SUSPEND"))
    }
}
