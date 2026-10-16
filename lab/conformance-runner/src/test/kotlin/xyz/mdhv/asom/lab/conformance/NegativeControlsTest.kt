package xyz.mdhv.asom.lab.conformance

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.server.driver.DriverOutcome
import xyz.mdhv.asom.server.driver.ProviderDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.map
import kotlin.test.assertTrue

/**
 * Negative controls: each law and each checker must FAIL against a deliberately broken implementation or a
 * deliberately corrupted vector, or the corresponding green result would prove nothing (LAB_SPEC R10).
 */
class NegativeControlsTest {

    private val loaded = VectorLoader.loadAll()

    private fun vec(id: String): Vector = loaded.all.single { it.id == id }

    private fun mutated(v: Vector, expectOk: kotlinx.serialization.json.JsonElement? = v.expectOk, expectReject: String? = v.expectReject) =
        Vector(v.file, v.family, v.id, v.origin, v.status, v.oracle, v.description, v.input, expectOk, expectReject, v.detail)

    private fun exchange(status: Int, headers: Map<String, String>, rows: List<RouteRecord>, body: String = "{}"): Exchange =
        Exchange(HttpResult(status, headers, body.toByteArray(), false), rows)

    private fun row(cost: Double? = 0.5, basis: xyz.mdhv.asom.contract.CostBasis = xyz.mdhv.asom.contract.CostBasis.USAGE) = RouteRecord(
        ts = 0, callerPkg = "c", requestedModel = "m", servedProvider = "p", servedModel = "x", egress = Egress.CLOUD,
        costEst = cost, costBasis = basis, latencyMs = 0, status = 200,
    )

    // ---------------------------------------------------------------- the exchange laws

    @Test
    fun invariant9LawFailsWhenAHeaderDisagreesWithTheRow() {
        val r = row()
        val good = r.toEchoHeaders().mapKeys { it.key.lowercase() }
        verifyExchangeLaws(exchange(200, good + ("content-type" to "application/json"), listOf(r))) { }
        val wrongProvider = good + ("x-asom-served-by" to "other/x")
        assertFailsWith<LawViolation> { verifyExchangeLaws(exchange(200, wrongProvider, listOf(r))) { } }
        val wrongEgress = good + ("x-asom-egress" to "local")
        assertFailsWith<LawViolation> { verifyExchangeLaws(exchange(200, wrongEgress, listOf(r))) { } }
        val missingCost = good - "x-asom-cost-est"
        assertFailsWith<LawViolation> { verifyExchangeLaws(exchange(200, missingCost, listOf(r))) { } }
    }

    @Test
    fun streamCommitLawRejectsCostHeadersAndDisagreement() {
        val r = row()
        val sse = "content-type" to "text/event-stream"
        val ok = mapOf(sse, "x-asom-served-by" to "p/x", "x-asom-egress" to "cloud")
        verifyExchangeLaws(exchange(200, ok, listOf(r))) { }
        assertFailsWith<LawViolation> { verifyExchangeLaws(exchange(200, ok + ("x-asom-cost-est" to "0.5"), listOf(r))) { } }
        assertFailsWith<LawViolation> { verifyExchangeLaws(exchange(200, ok + ("x-asom-egress" to "local"), listOf(r))) { } }
        assertFailsWith<LawViolation> { verifyExchangeLaws(exchange(200, mapOf(sse, "x-asom-served-by" to "p/x"), listOf(r))) { } }
    }

    @Test
    fun aNewXAsomHeaderIsAFrozenContractViolation() {
        val r = row()
        val headers = r.toEchoHeaders().mapKeys { it.key.lowercase() } + ("x-asom-failover" to "peer-lost")
        assertFailsWith<LawViolation> { verifyExchangeLaws(exchange(200, headers, listOf(r))) { } }
    }

    @Test
    fun aRequestThatAppendedNoRowFails() {
        assertFailsWith<LawViolation> { verifyExchangeLaws(exchange(200, mapOf("x-asom-egress" to "cloud"), emptyList())) { } }
    }

    // ----------------------------------------------------------------- corrupted vectors fail

    @Test
    fun flippingAnExpectedEchoHeaderFailsW01() {
        val v = vec("W01-001")
        assertEquals(Outcome.Pass, W01Checker().check(v))
        val bad = JsonObject((v.expectOk as JsonObject) + ("X-Asom-Egress" to JsonPrimitive("local")))
        val out = W01Checker().check(mutated(v, expectOk = bad))
        assertTrue(out is Outcome.Fail, "a flipped echo header must fail, got $out")
    }

    @Test
    fun flippingAnExpectedRouterOrderFailsR04() {
        val v = vec("R04-006")
        assertEquals(Outcome.Pass, R04Checker().check(v))
        val flipped = jsonStrings(((v.expectOk as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }).reversed())
        assertTrue(R04Checker().check(mutated(v, expectOk = flipped)) is Outcome.Fail)
        assertTrue(R04Checker().check(mutated(v, expectOk = null, expectReject = "MODEL_UNKNOWN")) is Outcome.Fail)
    }

    @Test
    fun flippingAWireConstantFailsW00() {
        val v = vec("W00-004")
        assertEquals(Outcome.Pass, W00Checker().check(v))
        assertTrue(W00Checker().check(mutated(v, expectOk = jsonStrings(listOf("local", "cloud", "catalogue", "download", "peer")))) is Outcome.Fail)
    }

    @Test
    fun aVectorWithoutARejectAnOkOrAnOracleIsAnEnvelopeProblem() {
        val dir = Files.createTempDirectory("asom-lab-envelope").toFile()
        try {
            File(dir, "VERSION").writeUtf8("0.2.0\n")
            File(dir, "wire").mkdirs()
            File(dir, "wire/W00-x.json").writeUtf8(
                """{"family":"W00","confVersion":"0.2.0","specRefs":["x"],"vectors":[
                  {"id":"W00-001","origin":"hand","status":"normative","description":"d","input":{},"expect":{}},
                  {"id":"W00-001","origin":"hand","status":"normative","oracle":"self","description":"d","input":{},"expect":{"ok":1,"reject":"X"}}
                ]}""",
            )
            val problems = VectorLoader.loadAll(dir).problems
            assertTrue(problems.any { "oracle" in it }, "$problems")
            assertTrue(problems.any { "expect must be exactly one" in it }, "$problems")
            assertTrue(problems.any { "duplicate vector id" in it }, "$problems")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------- non-vacuity

    @Test
    fun aFamilyWhoseLawNeverRanIsVacuous() {
        val c = W01Checker()
        val r = FamilyResult("W01", c, emptyList())
        assertTrue(r.vacuity().any { "zero normative vectors" in it })
        assertTrue(r.vacuity().any { "exercised zero cases" in it })
    }

    @Test
    fun theSuiteExercisedEveryRequiredLaw() {
        val report = ConformanceSuiteTest.report
        for (f in report.families.filter { it.implemented }) {
            assertEquals(emptyList(), f.vacuity())
            f.checker!!.requiredLaws.forEach { law -> assertTrue((f.checker.laws[law] ?: 0) > 0, "${f.family}/$law") }
        }
    }

    // ---------------------------------------------------------------------- SSE parser

    @Test
    fun sseParserRules() {
        fun p(s: String) = Sse.parse(s.toByteArray(Charsets.UTF_8))
        assertEquals(Sse.Result(listOf("a", "b"), true, false), p("data: a\n\ndata: b\r\n\r\ndata: [DONE]\n\n"))
        assertEquals(Sse.Result(listOf("x", "y"), true, false), p("data: x\r\rdata: y\r\rdata: [DONE]\r\r"))
        assertEquals(Sse.Result(listOf("l1\nl2"), false, false), p("data: l1\ndata: l2\n\n"))
        assertEquals(Sse.Result(listOf("a"), false, true), p("data: a\n\ndata: b"))
        assertEquals(Sse.Result(listOf("a"), false, true), p("data: a\n\ndata: b\n"))
        assertEquals(Sse.Result(listOf("a"), false, false), p(": keep-alive\n\ndata: a\n\n"))
        assertEquals(Sse.Result(emptyList(), true, false), p("data: [DONE]\n\ndata: never\n\n"))
        assertNotNull(p(""))
    }

    // -------------------------------------------------- broken implementations, through the real checkers

    private fun leaking(inner: ProviderDriver) = object : ProviderDriver {
        override suspend fun chat(provider: ProviderEntry, apiKey: String, body: JsonObject, stream: Boolean): DriverOutcome =
            DriverOutcome.Json(200, buildJsonObject { put("id", "x"); put("leak", apiKey) }, null)
        override suspend fun embeddings(provider: ProviderEntry, apiKey: String, body: JsonObject): DriverOutcome =
            inner.embeddings(provider, apiKey, body)
    }

    private fun corrupting(inner: ProviderDriver) = object : ProviderDriver {
        override suspend fun chat(provider: ProviderEntry, apiKey: String, body: JsonObject, stream: Boolean): DriverOutcome {
            val out = inner.chat(provider, apiKey, body, stream)
            return if (out is DriverOutcome.Stream) {
                DriverOutcome.Stream(out.events.map { b -> if (b.isEmpty()) b else b.copyOf().also { it[it.size - 1] = 'X'.code.toByte() } })
            } else out
        }
        override suspend fun embeddings(provider: ProviderEntry, apiKey: String, body: JsonObject): DriverOutcome =
            inner.embeddings(provider, apiKey, body)
    }

    @Test
    fun aKeyLeakingDriverFailsW01bAndW02() {
        val w1 = W01bChecker { leaking(it) }.check(vec("W01b-001"))
        assertTrue(w1 is Outcome.Fail && "leaked" in w1.why, "$w1")
        val w2 = W02Checker { leaking(it) }.check(vec("W02-007"))
        assertTrue(w2 is Outcome.Fail, "$w2")
        assertEquals(Outcome.Pass, W01bChecker().check(vec("W01b-001")))
    }

    @Test
    fun aByteCorruptingPassThroughFailsW03() {
        assertEquals(Outcome.Pass, W03Checker().check(vec("W03-001")))
        val out = W03Checker { corrupting(it) }.check(vec("W03-001"))
        assertTrue(out is Outcome.Fail && "pass-through" in out.why, "$out")
    }
}
