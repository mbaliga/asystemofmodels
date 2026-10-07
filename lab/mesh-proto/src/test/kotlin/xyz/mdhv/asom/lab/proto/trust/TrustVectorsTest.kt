package xyz.mdhv.asom.lab.proto.trust

import java.io.File
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.proto.pairing.W04Vectors

/** A vector of the trust families as the tests read it: no kotlinx-serialization, only the lab's strict parser. */
class TrustVector(val id: String, val status: String, val input: JObject, val expectOk: JValue?, val expectReject: String?)

object TrustVectorFiles {
    fun load(name: String, family: String): List<TrustVector> {
        val root = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot is not set"))
        val bytes = File(root, "lab/conformance/wire/$name").readBytes()
        val doc = (StrictJson.parse(bytes) as ParseResult.Ok).value as JObject
        assertEquals(family, (doc["family"] as JString).value)
        return (doc["vectors"] as JArray).items.map { el ->
            val o = el as JObject
            val expect = o["expect"] as JObject
            TrustVector(
                (o["id"] as JString).value, (o["status"] as JString).value, o["input"] as JObject, expect["ok"],
                (expect["reject"] as? JString)?.value,
            )
        }
    }
}

/** Runs every committed W04 and W05 vector through the evaluators and requires the family laws to be exercised (LAB_SPEC R10). Evidence label: LAB, oracle: self. */
class TrustVectorsTest {
    private fun run(family: String, vectors: List<TrustVector>, required: Set<String>, evaluate: (JObject) -> VectorEval) {
        val laws = TreeMap<String, Int>()
        val failures = ArrayList<String>()
        for (v in vectors) {
            try {
                val e = evaluate(v.input)
                e.laws.forEach { laws.merge(it, 1, Int::plus) }
                when (val o = e.outcome) {
                    is VectorOutcome.Ok -> if (v.expectOk == null) failures += "${v.id}: expected reject ${v.expectReject}, accepted" else if (v.expectOk != o.value) failures += "${v.id}: expected ${v.expectOk} but observed ${o.value}"
                    is VectorOutcome.Reject -> if (v.expectReject != o.code) failures += "${v.id}: expected ${v.expectReject ?: "ok"} but rejected with ${o.code}"
                }
            } catch (e: VectorLawViolation) {
                failures += "${v.id}: law violation: ${e.message}"
            }
        }
        println("family $family: ${vectors.size} vectors, ${vectors.size - failures.size} pass, ${failures.size} fail")
        laws.forEach { (k, n) -> println("  law $family/$k: $n cases") }
        assertEquals(emptyList(), failures)
        assertTrue(vectors.isNotEmpty(), "family $family has no vectors")
        assertEquals(emptyList(), required.filter { (laws[it] ?: 0) == 0 }, "family $family: laws exercised zero cases")
        Companion.record(family, vectors.size, laws)
    }

    @Test
    fun w05FingerprintsTemplatesAndChains() {
        run("W05", TrustVectorFiles.load("W05-fingerprints.json", "W05"), W05Vectors.requiredLaws) { W05Vectors.evaluate(it) }
    }

    @Test
    fun w04Pairing() {
        run("W04", TrustVectorFiles.load("W04-pairing.json", "W04"), W04Vectors.requiredLaws) { W04Vectors.evaluate(it) }
    }

    companion object {
        val counts = TreeMap<String, Int>()
        val lawTotals = TreeMap<String, Int>()

        @Synchronized
        fun record(family: String, n: Int, laws: Map<String, Int>) {
            counts[family] = n
            laws.forEach { (k, v) -> lawTotals["$family/$k"] = v }
        }
    }
}
