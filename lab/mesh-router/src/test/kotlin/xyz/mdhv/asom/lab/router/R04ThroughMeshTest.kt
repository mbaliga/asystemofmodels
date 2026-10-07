package xyz.mdhv.asom.lab.router

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.routing.RouteQuery

/**
 * RL1's oracle (LAB_SPEC 6.7, 6.8): every R04 vector, pinned against the REAL v1 `Router` by the conformance suite, is fed through [MeshRouter.plan] with no
 * engine and no usable peer, and must give exactly the same ordered plan or the same typed error. This is the pin of the frozen adapter of [CloudAdapter].
 */
class R04ThroughMeshTest {
    private val vectors: JsonArray by lazy {
        Json.parseToJsonElement(File(W.repoRoot, "lab/conformance/router/R04-v1-pins.json").readText()).jsonObject["vectors"]!!.jsonArray
    }

    private fun strings(o: JsonObject, k: String) = (o[k] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()

    private fun run(v: JsonObject, meshOn: Boolean, withDeadPeer: Boolean): String {
        val input = v["input"]!!.jsonObject
        val catalogue = input["catalogue"].let { c -> if (c is JsonPrimitive) W.catalogue else CatalogueParser.parse(c!!.jsonObject.toString()) }
        val keys = strings(input, "keysPresent").toSet()
        val cooling = strings(input, "cooling").associateWith { W.snapshot().wallNowMs + 30_000 }
        val ewma = input["latencyEwmaMs"]!!.jsonObject.entries.associate { (k, x) -> k to java.lang.Double.parseDouble((x as JsonPrimitive).content) }
        val q = input["query"]!!.jsonObject
        val query = W.query(
            model = (q["model"] as JsonPrimitive).content, policyHeader = (q["policyHeader"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { Policy.fromWire(it) },
            fallback = strings(q, "fallback"), noTrain = (q["noTrain"] as JsonPrimitive).content.toBoolean(),
        )
        val peers = if (withDeadPeer) listOf(W.peer("dead-peer", row = PeerRowView(false, true, true, false, W.limits))) else emptyList()
        val snap = W.snapshot(self = W.self(situation = W.selfSituation(engine = false)), peers = peers, meshOn = meshOn, cloud = FrozenCloudView(catalogue, keys, cooling, ewma))
        return when (val r = tryPlan(query, snap)) {
            is PlanRes.Ok -> "ok " + r.plan.attempts.joinToString(",") { "${it.cloud!!.provider.id}/${it.cloud.modelId}" }
            is PlanRes.Err -> "reject ${r.code}"
        }
    }

    @Test
    fun everyR04VectorHoldsThroughTheMeshRouterWithNoEngineAndNoUsablePeer() {
        var cases = 0
        for (e in vectors) {
            val v = e.jsonObject
            val expect = v["expect"]!!.jsonObject
            val want = if ("ok" in expect) "ok " + expect["ok"]!!.jsonArray.joinToString(",") { (it as JsonPrimitive).content } else "reject " + (expect["reject"] as JsonPrimitive).content
            for ((meshOn, dead) in listOf(false to false, false to true, true to false, true to true)) {
                assertEquals(want, run(v, meshOn, dead), "${(v["id"] as JsonPrimitive).content} (mesh $meshOn, unpaired peer $dead)")
                cases++
            }
        }
        println("RL1 (R04 vectors through MeshRouter) iterations: $cases violations: 0")
        assertTrue(cases >= Laws.FLOOR)
    }
}
