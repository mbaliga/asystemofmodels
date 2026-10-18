package xyz.mdhv.asom.ut

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.TreeMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

fun parseJ(text: String): JValue = when (val r = StrictJson.parse(text.toByteArray(Charsets.UTF_8))) {
    is ParseResult.Ok -> r.value
    is ParseResult.Reject -> error("test data is not in the JSON profile: $r")
}

fun JObject.obj(k: String): JObject = this[k] as? JObject ?: error("$k is not an object")
fun JObject.objOrNull(k: String): JObject? = this[k] as? JObject
fun JObject.arr(k: String): List<JValue> = (this[k] as? JArray ?: error("$k is not an array")).items
fun JObject.str(k: String): String = (this[k] as? JString ?: error("$k is not a string")).value
fun JObject.strOrNull(k: String): String? = when (val v = this[k]) {
    null, JNull -> null
    is JString -> v.value
    else -> error("$k is not a string or null")
}
fun JObject.int(k: String): Long = (this[k] as? JInt ?: error("$k is not an integer")).value
fun JObject.bool(k: String): Boolean = (this[k] as? JBool ?: error("$k is not a boolean")).value
fun JObject.boolOr(k: String, default: Boolean): Boolean = (this[k] as? JBool)?.value ?: default
fun JValue.asStr(): String = (this as? JString ?: error("not a string")).value
fun JValue.asObj(): JObject = this as? JObject ?: error("not an object")

class UtcVector(
    val id: String,
    val status: String,
    val oracle: String,
    val description: String,
    val input: JObject,
    val expectOk: JValue?,
    val expectReject: String?,
)

/** Counts the cases that exercised each law and fails the family when a required law was never exercised (non-vacuity). */
class Laws(private val family: String) {
    val counts = TreeMap<String, Int>()

    fun bump(law: String) {
        counts[law] = (counts[law] ?: 0) + 1
    }

    fun requireAll(required: Set<String>, minimumVectors: Int, vectors: Int) {
        assertTrue(vectors >= minimumVectors, "family $family ran $vectors vectors, expected at least $minimumVectors")
        val missing = required.filter { (counts[it] ?: 0) == 0 }
        assertTrue(missing.isEmpty(), "family $family: laws exercised by zero cases: $missing")
        println("family $family: $vectors vectors, ${counts.size} laws, every required law exercised (min ${required.minOf { counts[it] ?: 0 }} cases)")
        counts.forEach { (k, v) -> println("  law $family/$k: $v cases") }
    }
}

object UtcVectors {
    val root: File = File(System.getProperty("asom.utRoot") ?: error("asom.utRoot is not set (run through Gradle)")).resolve("conformance/utc")
    private val ORIGINS = setOf("hand", "generated")
    private val STATUSES = setOf("normative", "proposed", "illustrative")
    private val ORACLES = setOf("self", "independent", "external")

    fun version(): String = File(root, "VERSION").readText(Charsets.UTF_8).trim()

    fun load(file: String, family: String): List<UtcVector> {
        val doc = parseJ(File(root, file).readText(Charsets.UTF_8)) as JObject
        assertEquals(family, doc.str("family"), "$file family")
        assertEquals(version(), doc.str("confVersion"), "$file confVersion differs from VERSION")
        assertTrue(doc.arr("specRefs").isNotEmpty(), "$file has no specRefs")
        val out = ArrayList<UtcVector>()
        val seen = HashSet<String>()
        for ((i, raw) in doc.arr("vectors").withIndex()) {
            val o = raw.asObj()
            val id = o.str("id")
            assertEquals("$family-%03d".format(i + 1), id, "$file: ids must be consecutive from 001")
            assertTrue(seen.add(id), "duplicate id $id")
            assertTrue(o.str("origin") in ORIGINS, "$id origin")
            assertTrue(o.str("status") in STATUSES, "$id status")
            assertTrue(o.str("oracle") in ORACLES, "$id oracle (every vector must carry an oracle tag)")
            val expect = o.obj("expect")
            val keys = expect.members.map { it.first }.toSet()
            assertTrue(keys == setOf("ok") || keys == setOf("reject"), "$id expect must be exactly one of ok or reject")
            out += UtcVector(
                id, o.str("status"), o.str("oracle"), o.str("description"), o.obj("input"),
                expect["ok"], (expect["reject"] as? JString)?.value,
            )
        }
        assertTrue(out.isNotEmpty(), "$file has no vectors")
        return out
    }

    fun sha256(f: File): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath())))
}
