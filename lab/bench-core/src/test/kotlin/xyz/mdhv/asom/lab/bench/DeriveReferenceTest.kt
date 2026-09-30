package xyz.mdhv.asom.lab.bench

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.Jcs

/** Every case a test runs is counted; a suite that ran nothing fails (LAB_SPEC R10). */
class Counter(private val name: String) {
    var n = 0
        private set

    fun hit() {
        n++
    }

    fun requireNonVacuous(min: Int = 1) {
        assertTrue(n >= min, "$name exercised $n cases, expected at least $min")
        println("non-vacuity: $name exercised $n cases")
    }
}

/**
 * Cross-check of `derive`, `project` and `render` against the design session's reference outputs for the worked example
 * (`bench-examples/example-phone.*`, written by `bench_ref.py`). The reference predates the r3 amendments, so the documented
 * differences are normalised explicitly (ERRATA ERR-BENCH-1, -3, -7).
 */
class DeriveReferenceTest {
    private val doc = ExampleDocs.phoneDoc()
    private val d = Derive.derive(doc)
    private val ref = parseFile(File(Repo.benchExamples, "example-phone.doc.json"))

    @Test
    fun everyTierAndTestValueEqualsTheReference() {
        val c = Counter("reference test values")
        val refTiers = ref["tiers"].list()
        assertEquals(refTiers.map { it["tier"].str() }, d.tiers.map { it.tier })
        for ((rt, t) in refTiers.zip(d.tiers)) {
            assertEquals(rt["loadWarmMicros"].long(), t.loadWarmMicros)
            for (rr in rt["results"].list()) {
                val r = t.test(rr["test"].str())!!
                assertEquals(rr["value"].long(), r.value, "${t.tier} ${r.name} value")
                assertEquals(rr["kept"].long().toInt(), r.stat.kept)
                assertEquals(rr["keptIdx"].list().map { it.long().toInt() }, r.stat.keptIdx)
                assertEquals(rr["relSpreadPermille"].long(), r.stat.relSpreadPermille)
                assertEquals(rr["confidence"].str(), r.confidence.wire)
                assertEquals(rr["flags"].list().map { it.str() }, r.stat.flags)
                optMember(rr, "ttftMicros")?.let { assertEquals(it.long(), r.ttftMicros) }
                c.hit()
            }
            val nv = rt["numerics"]
            assertEquals(nv["deviationPermille"].long(), t.numerics.deviationPermille)
            assertEquals(nv["verdict"].str(), t.numerics.verdict)
        }
        c.requireNonVacuous(6)
    }

    @Test
    fun sustainAndAnswersEqualTheReference() {
        val s = d.sustain!!
        val rs = ref["sustain"]
        assertEquals(rs["peakMtps"].long(), s.peakMtps)
        assertEquals(rs["plateauMtps"].long(), s.plateauMtps)
        assertEquals(rs["onsetMs"].long(), s.onsetMs)
        assertEquals(rs["stabilityPermille"].long(), s.stabilityPermille)
        assertEquals(rs["durationMs"].long(), s.durationMs)
        assertEquals(rs["thermalCodeAtOnset"].long().toInt(), s.thermalCodeAtOnset)
        assertEquals(rs["headroomAtOnsetPermille"].long(), s.headroomAtOnsetPermille)
        assertEquals(rs["confidence"].str(), s.confidence.wire)
        val a = d.answers
        val rd = ref["derived"]
        assertEquals(rd["usableMemoryBytes"].long(), a.usableMemoryBytes)
        assertEquals(rd["maxHold"]["weightBytes"].long(), a.maxHold!!.weightBytes)
        assertEquals(rd["maxHold"]["kvRatioPermille"].long(), a.maxHold!!.kvRatioPermille)
        assertEquals(rd["maxHold"]["approxParamsQ4"].long(), a.maxHold!!.approxParamsQ4)
        assertEquals(rd["q7b"]["decodeMtps"].long(), a.q7b.decodeMtps)
        assertEquals(rd["q7b"]["ttft512Micros"].long(), a.q7b.ttft512Micros)
        assertEquals(rd["q7b"]["verdict"].str(), a.q7b.verdict)
        assertEquals(rd["answer2000"]["micros"].long(), a.answer2000!!.micros)
        assertEquals(rd["answer2000"]["depthRatioPermille"].long(), a.answer2000!!.depthRatioPermille)
        assertEquals(rd["throttle"]["stabilityPermille"].long(), a.throttle!!.stabilityPermille)
        assertEquals(rd["role"]["code"].str(), a.role.code)
        assertEquals(rd["role"]["t2PlateauMtps"].long(), a.role.t2PlateauMtps)
        assertEquals(rd["overallConfidence"].str(), a.overallConfidence.wire)
    }

    @Test
    fun projectionEqualsTheReferenceResultsByteForByte() {
        val refRows = parseFile(File(Repo.benchExamples, "example-phone.manifest-results.json"))
        val mine = Project.results(d, Audience.OWN)
        assertEquals(refRows.list().size, mine.size)
        for ((a, b) in refRows.list().zip(mine)) {
            assertEquals(String(Jcs.serialize(a), Charsets.UTF_8), String(Jcs.serialize(b), Charsets.UTF_8))
        }
    }

    @Test
    fun rendererReproducesTheWorkedExampleModuloDocumentedDifferences() {
        val expected = File(Repo.benchExamples, "example-phone.txt").readText(Charsets.US_ASCII)
        val mine = TextRender.render(d, RenderOptions(meshAvailable = true))
        val lines = expected.split("\n").toMutableList()
        val dropAt = lines.indexOfFirst { it.startsWith("  - In everyday use") }
        assertNotEquals(-1, dropAt)
        lines.removeAt(dropAt)
        lines.removeAt(dropAt)
        val sig = lines.indexOfFirst { it.startsWith("- This device tested itself.") }
        assertNotEquals(-1, sig)
        repeat(3) { lines.removeAt(sig) }
        lines.addAll(
            sig,
            listOf(
                "- This text proves nothing by itself. It can be checked only by",
                "  opening the signed report in a verifier. A valid signature shows",
                "  the report is unchanged since signing and which key signed it,",
                "  not that the test was honest.",
            ),
        )
        val basis = lines.indexOfFirst { it.startsWith("   device when one is available.") }
        lines.add(basis + 1, "   Basis: estimated from the measured speeds and heat test.")
        assertEquals(lines.joinToString("\n"), mine)
    }

    @Test
    fun theTextHashIsOfTheRenderedText() {
        val text = TextRender.render(d, RenderOptions(meshAvailable = true))
        assertEquals(43, TextRender.textSha256B64u(text).length)
    }

    @Test
    fun theDecoderRoundTripsTheEncodedDocument() {
        val json = BenchCodec.encode(doc)
        val back = BenchCodec.decode(json, RdContext(false))
        assertEquals(doc, back)
        assertTrue(json is JObject)
    }
}
