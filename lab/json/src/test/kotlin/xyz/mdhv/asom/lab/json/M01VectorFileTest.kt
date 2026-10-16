package xyz.mdhv.asom.lab.json

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs lab/conformance/json/M01-jcs.json with THIS module's own parser (no kotlinx-serialization), independently of
 * the conformance runner. The vector file is plain strict JSON, so `:json` reads its own vectors.
 */
class M01VectorFileTest {
    private val file = File(repoRoot(), "lab/conformance/json/M01-jcs.json")

    private fun vectors(): List<JObject> {
        val doc = assertAccepts(file.readBytes(), "the vector file is strict JSON") as JObject
        assertEquals(JString("M01"), doc["family"])
        assertEquals(JString(File(repoRoot(), "lab/conformance/VERSION").readText().trim()), doc["confVersion"])
        return (doc["vectors"] as JArray).items.map { it as JObject }
    }

    private fun str(o: JObject, k: String): String = (o[k] as? JString)?.value ?: fail("missing string '$k' in $o")

    @Test
    fun everyVectorIsSelfOracledHandWrittenAndNormative() {
        val vs = vectors()
        assertTrue(vs.size >= 90, "vector count ${vs.size}")
        val ids = vs.map { str(it, "id") }
        assertEquals(ids.size, ids.toSet().size, "unique ids")
        for (v in vs) {
            assertEquals("self", str(v, "oracle"), str(v, "id"))
            assertEquals("hand", str(v, "origin"), str(v, "id"))
            assertEquals("normative", str(v, "status"), str(v, "id"))
            assertTrue(Regex("M01-[0-9]{3}").matches(str(v, "id")), str(v, "id"))
        }
    }

    @Test
    fun theSpecIdRangesAreAllPresent() {
        val ids = vectors().map { str(it, "id") }.toSet()
        for (n in 1..12) assertTrue("M01-%03d".format(n) in ids, "M01-%03d missing".format(n))
        for (n in 101..110) assertTrue("M01-%03d".format(n) in ids, "M01-%03d missing".format(n))
    }

    @Test
    fun everyVectorGivesItsExpectedVerdictAndEveryCodeAndLawIsExercised() {
        val c = Cases("vector file")
        val codes = HashMap<String, Int>()
        var canonical = 0
        var trapVectors = 0
        for (v in vectors()) {
            val id = str(v, "id")
            val input = v["input"] as JObject
            val expect = v["expect"] as JObject
            val kind = str(input, "kind")
            when (kind) {
                "canonicalize" -> {
                    val bytes = (input["inputHex"] as? JString)?.let { Hex.decode(it.value) } ?: str(input, "inputText").toByteArray(Charsets.UTF_8)
                    val got = Canonicalizer.canonicalize(bytes)
                    val want = expect["ok"] as? JObject
                    if (want != null) {
                        got as? Canonicalizer.Result.Canonical ?: fail("$id: expected ok, got ${(got as Canonicalizer.Result.Rejected).code}")
                        c.run { assertEquals(str(want, "text"), got.text, id) }
                        c.run { assertEquals(str(want, "utf8Hex"), Hex.encode(got.bytes), id) }
                        canonical++
                        if (id == "M01-001" || id == "M01-008") trapVectors++
                    } else {
                        val code = str(expect, "reject")
                        got as? Canonicalizer.Result.Rejected ?: fail("$id: expected reject $code, but it was accepted")
                        c.run { assertEquals(code, got.code.name, id) }
                        codes.merge(code, 1, Int::plus)
                    }
                }
                "base64Either", "base64UrlNoPad" -> {
                    val text = str(input, "text")
                    val got = if (kind == "base64Either") Base64Strict.decodeEither(text) else Base64Strict.decodeUrlNoPad(text)
                    val want = expect["ok"] as? JObject
                    if (want != null) {
                        got as? B64Result.Ok ?: fail("$id: expected ok, got $got")
                        c.run { assertEquals(str(want, "hex"), Hex.encode(got.bytes), id) }
                    } else {
                        got as? B64Result.Reject ?: fail("$id: expected ENCODING but it decoded")
                        c.run { assertEquals(str(expect, "reject"), got.code, id) }
                        codes.merge("ENCODING", 1, Int::plus)
                    }
                }
                else -> fail("$id: unknown kind")
            }
        }
        for (code in JsonRejectCode.entries.map { it.name } + "ENCODING") assertTrue((codes[code] ?: 0) >= 2, "reject code $code exercised ${codes[code]} times: $codes")
        assertTrue(canonical >= 15, "canonical vectors: $canonical")
        assertEquals(2, trapVectors)
        c.requireNonVacuous(100)
    }
}
