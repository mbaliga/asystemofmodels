package xyz.mdhv.asom.lab.bench

import java.io.File
import java.security.MessageDigest
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

/** Path edits over immutable [JValue] trees (member name = String, array index = Int). */
object BJ {
    fun set(v: JValue, path: List<Any>, value: JValue): JValue {
        if (path.isEmpty()) return value
        val head = path[0]
        val rest = path.drop(1)
        return if (head is String) {
            val o = v as JObject
            val existing = o.members.any { it.first == head }
            JObject(if (existing) o.members.map { (k, x) -> if (k == head) k to set(x, rest, value) else k to x } else o.members + (head to set(JObject(emptyList()), rest, value)))
        } else {
            JArray((v as JArray).items.mapIndexed { i, x -> if (i == head) set(x, rest, value) else x })
        }
    }

    fun remove(v: JValue, path: List<Any>): JValue {
        val head = path[0]
        if (path.size == 1) return JObject((v as JObject).members.filter { it.first != head })
        val rest = path.drop(1)
        return if (head is String) JObject((v as JObject).members.map { (k, x) -> if (k == head) k to remove(x, rest) else k to x })
        else JArray((v as JArray).items.mapIndexed { i, x -> if (i == head) remove(x, rest) else x })
    }

    fun parse(text: String): JValue = when (val r = StrictJson.parse(text.toByteArray(Charsets.UTF_8))) {
        is ParseResult.Ok -> r.value
        is ParseResult.Reject -> error("parse: $r")
    }
}

fun mdText(name: String): String = File(Repo.root, "docs/design/mesh/$name").readText(Charsets.UTF_8)

/** The pins of `benchmark.md` 4.2, the bench-set constants and the editorial switches (LAB_SPEC 5). */
class BenchPinsTest {
    @Test
    fun q1PinsEqualTheSpecTableRowForRow() {
        val c = Counter("Q1 pin rows")
        val md = mdText("benchmark.md")
        val sec = md.substring(md.indexOf("### 4.2 The pin"), md.indexOf("### 4.3"))
        val rx = Regex("""\| (T[0-5]) \| `([^`]+)` \| `([^`]+)` \| ([0-9,]+) \| `([0-9a-f]{64})` \| `([0-9a-f]{40})` \| ([0-9,]+) \| ([0-9,]+) \|""")
        val rows = rx.findAll(sec).toList()
        assertEquals(6, rows.size, "the spec table has six rows")
        for (m in rows) {
            val pin = BenchSets.Q1.pin(m.groupValues[1])!!
            assertEquals(m.groupValues[2], pin.modelId)
            assertEquals(m.groupValues[4].replace(",", "").toLong(), pin.bytes)
            assertEquals(m.groupValues[5], pin.sha256)
            assertEquals(m.groupValues[6], pin.revision)
            assertEquals(m.groupValues[7].replace(",", "").toLong(), pin.params)
            assertEquals(m.groupValues[8].replace(",", "").toLong(), pin.kvBytesPerToken)
            c.hit()
        }
        assertEquals(PinStatus.PROPOSED, BenchSets.Q1.status, "no pin is CONFIRMED before the owner downloads and hashes the files [A17]")
        c.requireNonVacuous(6)
    }

    @Test
    fun l1HasNoPinsAndIsNeverDefault() {
        assertEquals(PinStatus.UNPINNED, BenchSets.L1.status)
        assertTrue(BenchSets.L1.tiers.all { it.sha256 == null && it.bytes == null && it.revision == null && it.params == null })
        assertNull(BenchSets.find(BenchSets.L1_ID))
        assertFailsWith<IllegalArgumentException> { BenchSets.withD18Ruling(false) }
        assertNotNull(BenchSets.find(BenchSets.L1_ID, BenchSets.withD18Ruling(true)))
        // even with the ruling flag a document that names L1 cannot be decoded: no pins
        val doc = BenchCodec.encode(ExampleDocs.phoneDoc())
        val l1 = BJ.set(doc, listOf("benchSet"), JString(BenchSets.L1_ID))
        assertFailsWith<SchemaViolation> { BenchCodec.decode(l1, RdContext(false), BenchSets.withD18Ruling(true)) }
    }

    @Test
    fun provisionalConstantsAreLabelledAndMlperfWordingIsOff() {
        assertTrue(BytesPerToken.CLASS_DEFAULT.provisional)
        assertEquals(4000L, BytesPerToken.CLASS_DEFAULT.bptPermille)
        assertEquals(8000L, BytesPerToken.CLASS_DEFAULT.bptCapPermille)
        assertNull(NumericsReferences.lookup("0123456789abcdef0123456789abcdef01234567", BenchSets.Q1.pin("T1")!!.sha256!!), "no numerics reference is invented")
        assertFalse(Editorial.MLPERF_NOTE_ENABLED)
        assertFalse(Editorial.MLPERF_NOTE.contains("MLPerf-comparable"))
        assertEquals(MlperfMetricMapping.UNPINNED, MlperfMetricMapping.UNPINNED)
    }
}

/** Seeded property tests of the M04 statistics, each counting its cases (LAB_SPEC R10). */
class StatsPropertyTest {
    private fun samples(r: SplittableRandom, n: Int): List<Long> {
        val base = 1_000_000L + r.nextLong(9_000_000L)
        val noise = 1L + r.nextLong(100L)
        return List(n) { base + r.nextLong(base / noise + 1L) }
    }

    @Test
    fun keptSetInvariantsHoldOnRandomSamples() {
        val c = Counter("stat invariants")
        val r = SplittableRandom(20260930L)
        repeat(3000) {
            val n = 1 + r.nextInt(12)
            val v = samples(r, n)
            val st = Stats.stat(v)
            assertEquals(st.keptIdx, st.keptIdx.sorted())
            assertEquals(st.keptIdx.size, st.keptIdx.toSet().size)
            assertTrue(st.keptIdx.all { it in v.indices })
            val excluded = n - st.kept
            assertTrue(excluded <= n / Stats.MAX_EXCLUDE_DIV, "at most floor(n/5) reps are excluded ($excluded of $n)")
            if ("UNSTABLE" in st.flags) assertEquals(n, st.kept, "UNSTABLE keeps every rep")
            if (st.value != null) {
                val kept = st.keptIdx.map { v[it] }
                assertTrue(st.value!! in kept.min()..kept.max(), "the value lies between the kept extremes")
                assertEquals(Stats.lowerMedian(kept), st.value)
            } else {
                assertTrue(st.kept < 2)
            }
            assertTrue("OUTLIER_EXCLUDED" !in st.flags || excluded > 0 || "THERMAL_DRIFT" in st.flags)
            c.hit()
        }
        c.requireNonVacuous(3000)
    }

    @Test
    fun medianLawsAndPermutationInvariance() {
        val c = Counter("median laws")
        val r = SplittableRandom(7L)
        repeat(2000) {
            val n = 1 + r.nextInt(15)
            val v = List(n) { r.nextLong(1_000_000L) }
            assertTrue(Stats.lowerMedian(v) <= Stats.upperMedian(v))
            if (n % 2 == 1) assertEquals(Stats.lowerMedian(v), Stats.upperMedian(v))
            val shuffled = v.shuffled(java.util.Random(r.nextLong()))
            assertEquals(Stats.lowerMedian(v), Stats.lowerMedian(shuffled))
            assertEquals(Stats.upperMedian(v), Stats.upperMedian(shuffled))
            c.hit()
        }
        c.requireNonVacuous(2000)
    }

    @Test
    fun nearestRankIsMonotoneAndBounded() {
        val c = Counter("nearest rank")
        val r = SplittableRandom(11L)
        repeat(1500) {
            val v = List(1 + r.nextInt(20)) { r.nextLong(1_000_000L) }
            var prev = Long.MIN_VALUE
            for (p in listOf(0L, 1L, 100L, 500L, 900L, 999L, 1000L)) {
                val x = Stats.nearestRank(v, p)
                assertTrue(x >= prev)
                assertTrue(x in v.min()..v.max())
                prev = x
            }
            assertEquals(v.min(), Stats.nearestRank(v, 0L), "p = 0 is the minimum")
            assertEquals(v.max(), Stats.nearestRank(v, 1000L), "p = 1000 is the maximum")
            c.hit()
        }
        c.requireNonVacuous(1500)
    }

    @Test
    fun theKnownVectorsOfTheSpecHold() {
        // benchmark.md 9.1: one slow rep of five is excluded, and the median is the LOWER median of the kept rates
        val st = Stats.stat(listOf(7394L, 7380L, 7391L, 7397L, 5000L).map { it * 1000 })
        assertEquals(listOf("OUTLIER_EXCLUDED"), st.flags)
        assertEquals(listOf(0, 1, 2, 3), st.keptIdx)
        // a constant series has MAD 0 and excludes nobody
        val flat = Stats.stat(List(5) { 10_000L })
        assertTrue(flat.flags.isEmpty())
        assertEquals(5, flat.kept)
        // the thermal-drift rule (B7): slowing at every step over 4 kept reps
        val slow = Stats.stat(listOf(12_800L, 12_400L, 12_000L, 11_600L, 11_200L))
        assertTrue("THERMAL_DRIFT" in slow.flags)
        assertEquals(Confidence.LOW, Stats.confidence(slow, 0L, false, false, false))
        // and a monotone series of only three does not trigger the every-step rule (needs 4) unless first/last differ by over 10%
        val mild = Stats.stat(listOf(10_000L, 9_900L, 9_800L))
        assertFalse("THERMAL_DRIFT" in mild.flags)
        val steep = Stats.stat(listOf(10_000L, 9_500L, 8_900L))
        assertTrue("THERMAL_DRIFT" in steep.flags)
    }

    @Test
    fun theExclusionBudgetIsFloorOfNOverFive() {
        val c = Counter("exclusion budget")
        for (n in 2..12) {
            val budget = n / 5
            for (k in 0..3) {
                // k slow reps first (so no drift is possible), the rest identical
                val v = List(n) { if (it < k) 14_000_000L else 10_000_000L }.map { Checked.rate(128, it) }
                val st = Stats.stat(v)
                if (k == 0 || k >= n) {
                    assertTrue(st.flags.isEmpty(), "n=$n k=$k")
                } else if (k <= budget) {
                    assertEquals(n - k, st.kept, "n=$n k=$k: within budget, excluded")
                    assertEquals(listOf("OUTLIER_EXCLUDED"), st.flags)
                } else {
                    assertEquals(n, st.kept, "n=$n k=$k: over budget, UNSTABLE keeps all")
                    assertEquals(listOf("UNSTABLE"), st.flags)
                }
                c.hit()
            }
        }
        c.requireNonVacuous(40)
    }

    @Test
    fun confidenceCapsOnlyEverLowerTheClass() {
        val c = Counter("confidence caps")
        val r = SplittableRandom(3L)
        repeat(1500) {
            val st = Stats.stat(samples(r, 1 + r.nextInt(9)).map { Checked.rate(128, it) })
            val cont = r.nextLong(400L)
            val base = Stats.confidence(st, cont, false, false, false)
            for ((a, b, d) in listOf(Triple(true, false, false), Triple(false, true, false), Triple(false, false, true), Triple(true, true, true))) {
                assertTrue(Stats.confidence(st, cont, a, b, d).ordinal <= base.ordinal)
                assertTrue(Stats.confidence(st, cont, a, b, d).ordinal <= Confidence.MEDIUM.ordinal || base == Confidence.INSUFFICIENT)
            }
            if (st.kept < 2) assertEquals(Confidence.INSUFFICIENT, base)
            c.hit()
        }
        c.requireNonVacuous(1500)
    }
}

class CheckedArithmeticTest {
    @Test
    fun overflowIsAnErrorNeverAWrap() {
        assertFailsWith<BenchArithmeticException> { Checked.mul(Long.MAX_VALUE, 2) }
        assertFailsWith<BenchArithmeticException> { Checked.add(Long.MAX_VALUE, 1) }
        assertFailsWith<BenchArithmeticException> { Checked.sub(Long.MIN_VALUE, 1) }
        assertFailsWith<BenchArithmeticException> { Checked.div(1, 0) }
        assertFailsWith<BenchArithmeticException> { Checked.ceilDiv(1, 0) }
        assertFailsWith<BenchArithmeticException> { Checked.u53(Checked.MAX_SAFE + 1) }
        assertFailsWith<BenchArithmeticException> { Checked.u53(-1) }
        assertEquals(Checked.MAX_SAFE, Checked.u53(Checked.MAX_SAFE))
        assertEquals(0L, Checked.u53(0))
        assertEquals(3L, Checked.ceilDiv(5, 2))
        assertEquals(2L, Checked.ceilDiv(4, 2))
        assertEquals(7394L, Checked.rate(128, 17_310_000L))
        assertEquals(4L, Checked.permille(4, 1000))
        assertEquals(5L, Checked.absDiff(2, 7))
        // a rate whose product with 10^9 overflows 64 bits is an error, not a wrapped number
        assertFailsWith<BenchArithmeticException> { Checked.rate(Long.MAX_VALUE / 2, 1) }
    }
}

/** The strict typed decoder: every unknown or forbidden shape is refused with a path. */
class BenchDecoderRejectTest {
    private val good: JValue by lazy { BenchCodec.encode(ExampleDocs.phoneDoc()) }

    private fun bad(name: String, edit: (JValue) -> JValue, c: Counter, strictOnly: Boolean = true) {
        val v = edit(good)
        val ex = assertFailsWith<SchemaViolation>("case $name must be refused") { BenchCodec.decode(v, RdContext(!strictOnly)) }
        assertTrue(ex.path.startsWith("$"), "$name: the violation names a path (${ex.path})")
        c.hit()
    }

    @Test
    fun theExampleDocumentRoundTripsThroughTheStrictDecoder() {
        val doc = BenchCodec.decode(good, RdContext(false))
        assertEquals(good.let { Jcs.serialize(it).toList() }, Jcs.serialize(BenchCodec.encode(doc)).toList())
    }

    @Test
    fun malformedDocumentsAreRefused() {
        val c = Counter("decoder rejections")
        bad("unknown top member", { BJ.set(it, listOf("extra"), JInt(1)) }, c)
        bad("derived member (P7)", { BJ.set(it, listOf("derived"), JObject(emptyList())) }, c)
        bad("nested forbidden render", { BJ.set(it, listOf("harness", "render"), JString("x")) }, c)
        bad("field member", { BJ.set(it, listOf("field"), JArray(emptyList())) }, c)
        bad("custom member", { BJ.set(it, listOf("device", "custom"), JArray(emptyList())) }, c)
        bad("wrong schema", { BJ.set(it, listOf("schema"), JString("asom.bench/2")) }, c)
        bad("missing schema", { BJ.remove(it, listOf("schema")) }, c)
        bad("protocol 2", { BJ.set(it, listOf("benchProtocol"), JInt(2)) }, c)
        bad("unknown bench set", { BJ.set(it, listOf("benchSet"), JString("nope-1")) }, c)
        bad("energy not null", { BJ.set(it, listOf("energy"), JObject(emptyList())) }, c)
        bad("tier sha differs from the pin", { BJ.set(it, listOf("tiers", 0, "sha256"), JString("0".repeat(64))) }, c)
        bad("tier bytes differ from the pin", { BJ.set(it, listOf("tiers", 0, "bytes"), JInt(1)) }, c)
        bad("float-like string where an integer belongs", { BJ.set(it, listOf("tiers", 0, "nCtx"), JString("4096")) }, c)
        bad("negative sample", { BJ.set(it, listOf("tiers", 0, "tests", 0, "samples", 0), JInt(-5)) }, c)
        bad("zero sample", { BJ.set(it, listOf("tiers", 0, "tests", 0, "samples", 0), JInt(0)) }, c)
        bad("sample above the 24 h span limit", { BJ.set(it, listOf("tiers", 0, "tests", 0, "samples", 0), JInt(86_400_000_001L)) }, c)
        bad("empty samples", { BJ.set(it, listOf("tiers", 0, "tests", 0, "samples"), JArray(emptyList())) }, c)
        bad("paired test without the whole span", { BJ.remove(it, listOf("tiers", 0, "tests", 0, "wholeSamples")) }, c)
        bad("malformed test name", { BJ.set(it, listOf("tiers", 0, "tests", 0, "test"), JString("pp999@dx")) }, c)
        bad("tiers out of order", { d -> JObject((d as JObject).members.map { (k, x) -> if (k == "tiers") k to JArray((x as JArray).items.reversed()) else k to x }) }, c)
        bad("unknown thermal class", { BJ.set(it, listOf("run", "startThermal"), JString("lukewarm")) }, c)
        bad("date out of grammar", { BJ.set(it, listOf("run", "dayUtc"), JString("2026-13-40")) }, c)
        bad("ended before started", { BJ.set(it, listOf("run", "endedAtMs"), JInt(1_600_000_000_000L)) }, c)
        bad("epoch before 2020", { BJ.set(it, listOf("run", "startedAtMs"), JInt(1_000L)) }, c)
        bad("text of 97 code points", { BJ.set(it, listOf("device", "model"), JString("x".repeat(97))) }, c)
        bad("control character in text", { BJ.set(it, listOf("device", "model"), JString("bad\u0007")) }, c)
        bad("bidi override in text", { BJ.set(it, listOf("device", "maker"), JString("a‮b")) }, c)
        bad("empty text", { BJ.set(it, listOf("device", "soc"), JString("")) }, c)
        bad("sustain tier not measured", { BJ.set(it, listOf("sustain", "tier"), JString("T0")) }, c)
        bad("sustain end reason unknown", { BJ.set(it, listOf("sustain", "endReason"), JString("BORED")) }, c)
        bad("sustain window count zero", { BJ.set(it, listOf("sustain", "windows"), JArray(emptyList())) }, c)
        bad("plan hash not 43 base64url characters", { BJ.set(it, listOf("harness", "planSha256"), JString("short")) }, c)
        bad("engine commit not hex", { BJ.set(it, listOf("harness", "engine", "commit"), JString("zzzzzzz")) }, c)
        c.requireNonVacuous(30)
    }

    @Test
    fun unknownMembersAreToleratedOnlyWhenTheContextSaysSo() {
        val v = BJ.set(good, listOf("extra"), JInt(1))
        assertFailsWith<SchemaViolation> { BenchCodec.decode(v, RdContext(false)) }
        val ctx = RdContext(true)
        BenchCodec.decode(v, ctx)
        assertEquals(1, ctx.unknown)
    }
}

class RenderLawsTest {
    private fun docs(): List<Pair<String, BenchDoc>> {
        val out = mutableListOf<Pair<String, BenchDoc>>("example" to ExampleDocs.phoneDoc())
        val root = File(Repo.conformance, "bench/M04-derive.json")
        val v = parseFile(root)
        for (x in v["vectors"].list()) {
            val i = x["input"]
            if (i["kind"].str() != "doc" || optMember(x["expect"], "ok") == null) continue
            out += x["id"].str() to BenchCodec.decode(i["benchDoc"], RdContext(false))
        }
        return out
    }

    @Test
    fun everyRenderingIsAsciiLfAtMost72ColumnsWithoutMlperfWording() {
        val c = Counter("render laws")
        for ((id, d) in docs()) {
            for (mesh in listOf(false, true)) {
                val derived = Derive.derive(d)
                val text = TextRender.render(derived, RenderOptions(meshAvailable = mesh))
                assertTrue(text.all { it.code in 0x0A..0x7E && (it.code >= 0x20 || it == '\n') }, "$id: printable ASCII and LF only")
                assertFalse(text.contains('\r'))
                assertTrue(text.endsWith("\n") && !text.endsWith("\n\n"), "$id: one trailing newline")
                assertTrue(text.split("\n").all { it.length <= TextRender.MAX_COLS }, "$id: a line over ${TextRender.MAX_COLS} columns")
                assertFalse(text.lowercase().contains("mlperf"), "$id: MLPerf wording while the note is disabled (LM-9)")
                assertFalse(text.contains(TextRender.FORBIDDEN_LABEL))
                assertEquals(text, TextRender.render(derived, RenderOptions(meshAvailable = mesh)), "$id: rendering is deterministic")
                assertEquals(
                    TextRender.textSha256B64u(text),
                    xyz.mdhv.asom.lab.json.Base64Strict.encodeUrlNoPad(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII))),
                )
                c.hit()
            }
        }
        c.requireNonVacuous(20)
    }

    @Test
    fun theMeshOnlyChangesQuestionFive() {
        val d = Derive.derive(ExampleDocs.phoneDoc())
        val off = TextRender.render(d, RenderOptions(meshAvailable = false)).split("\n")
        val on = TextRender.render(d, RenderOptions(meshAvailable = true)).split("\n")
        val five = off.indexOfFirst { it.startsWith("5. ") }
        assertTrue(five > 0)
        assertEquals(off.take(five + 1), on.take(five + 1))
        assertTrue(off[five + 1].contains("does not share work"))
        assertNotEquals(off, on)
        val tail = off.indexOfFirst { it.startsWith("DETAILS") }
        assertEquals(off.drop(tail), on.drop(on.indexOfFirst { it.startsWith("DETAILS") }))
    }

    @Test
    fun nonAsciiIsReplacedOneQuestionMarkPerCodePoint() {
        assertEquals("a?b", TextRender.asciiOnly("aéb"))
        assertEquals("??", TextRender.asciiOnly("😀😁"))
        assertEquals("plain", TextRender.asciiOnly("plain"))
    }

    @Test
    fun theMlperfNoteNeverRendersWhileTheMetricMappingIsUnpinned() {
        // design 6.1 items 2-3 / LM-9: even with the flag on, the note needs an L1 document AND a pinned MLPerf metric mapping; the mapping is UNPINNED
        val d = Derive.derive(ExampleDocs.phoneDoc())
        val on = TextRender.render(d, RenderOptions(mlperfNoteEnabled = true))
        assertFalse(on.lowercase().contains("mlperf"))
        assertEquals(TextRender.render(d, RenderOptions(mlperfNoteEnabled = false)), on)
    }
}

/** The governor state machine: every edge the table allows works, every other pair is refused. */
class GovernorFsmTest {
    private val specEdges: Set<Pair<GState, GState>> = setOf(
        GState.IDLE to GState.PREFLIGHT, GState.PREFLIGHT to GState.IDLE, GState.PREFLIGHT to GState.AWAIT_CONSENT, GState.AWAIT_CONSENT to GState.IDLE,
        GState.AWAIT_CONSENT to GState.PREPARING, GState.PREPARING to GState.COOLING, GState.COOLING to GState.RUNNING, GState.RUNNING to GState.COOLING,
        GState.RUNNING to GState.FINALIZING, GState.RUNNING to GState.YIELDED, GState.YIELDED to GState.COOLING, GState.YIELDED to GState.FINALIZING,
        GState.FINALIZING to GState.DONE,
        GState.PREFLIGHT to GState.ABORTING, GState.AWAIT_CONSENT to GState.ABORTING, GState.PREPARING to GState.ABORTING, GState.COOLING to GState.ABORTING,
        GState.RUNNING to GState.ABORTING, GState.YIELDED to GState.ABORTING,
    )
    private val documentedExtra: Set<Pair<GState, GState>> = setOf(GState.ABORTING to GState.FINALIZING, GState.COOLING to GState.FINALIZING)

    private fun governorAt(target: GState): Governor {
        val host = FakeHost(Scenarios.preset("phone", "standard"))
        val g = Governor(host, RunPlans.STANDARD, mutableListOf()) { 0L }
        // breadth-first path from IDLE
        val prev = HashMap<GState, GState?>()
        prev[GState.IDLE] = null
        val q = ArrayDeque(listOf(GState.IDLE))
        while (q.isNotEmpty()) {
            val s = q.removeFirst()
            for (n in Governor.EDGES.getValue(s)) if (n !in prev) {
                prev[n] = s
                q.addLast(n)
            }
        }
        val path = generateSequence(target) { prev[it] }.toList().reversed()
        for (s in path.drop(1)) g.to(s)
        assertEquals(target, g.state)
        return g
    }

    @Test
    fun theTableEqualsTheSpecDiagramPlusTheDocumentedExtensions() {
        val table = Governor.EDGES.flatMap { (a, bs) -> bs.map { a to it } }.toSet()
        assertTrue(table.containsAll(specEdges), "missing ${specEdges - table}")
        assertEquals(documentedExtra, table - specEdges)
    }

    @Test
    fun everyPairIsEitherARealEdgeOrRefused() {
        val c = Counter("FSM pairs")
        var edges = 0
        var nonEdges = 0
        for (a in GState.entries) for (b in GState.entries) {
            val g = governorAt(a)
            if (b in Governor.EDGES.getValue(a)) {
                g.to(b)
                assertEquals(b, g.state)
                edges++
            } else {
                val ex = assertFailsWith<IllegalTransition>("$a -> $b must be refused") { g.to(b) }
                assertEquals(a, ex.from)
                assertEquals(b, ex.to)
                assertEquals(a, g.state, "a refused transition leaves the state unchanged")
                nonEdges++
            }
            c.hit()
        }
        assertEquals(GState.entries.size * GState.entries.size, c.n)
        assertTrue(edges >= 20 && nonEdges >= 60, "edges=$edges nonEdges=$nonEdges")
    }

    @Test
    fun doneIsTerminalAndIdleOnlyLeadsToPreflight() {
        assertTrue(Governor.EDGES.getValue(GState.DONE).isEmpty())
        assertEquals(setOf(GState.PREFLIGHT), Governor.EDGES.getValue(GState.IDLE))
        assertEquals(setOf(GState.FINALIZING), Governor.EDGES.getValue(GState.ABORTING))
    }
}

class ConsentTest {
    private val plan = RunPlans.STANDARD

    private fun sheet() = ConsentSheet.forPlan(plan, 4_300_000_000L, listOf("T1", "T2"), true, false)

    @Test
    fun onlyTheHashOfTheShownTextMintsAToken() {
        val s = sheet()
        s.confirm(s.textSha256, 1000L)
        val wrong = s.textSha256.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFailsWith<ConsentError> { s.confirm(wrong, 1000L) }
        assertFailsWith<ConsentError> { s.confirm(ByteArray(0), 1000L) }
        assertFailsWith<ConsentError> { s.confirm(ByteArray(32), 1000L) }
    }

    @Test
    fun aTokenExpiresAtFiveMinutesAndIsSingleUseAndPlanBound() {
        val s = sheet()
        val t1 = s.confirm(s.textSha256, 10_000L)
        t1.consume("standard", 10_000L + ConsentSheet.TOKEN_TTL_MS - 1)
        assertFailsWith<ConsentError> { t1.consume("standard", 10_000L + 1) }
        val t2 = s.confirm(s.textSha256, 10_000L)
        assertFailsWith<ConsentError> { t2.consume("standard", 10_000L + ConsentSheet.TOKEN_TTL_MS) }
        val t3 = s.confirm(s.textSha256, 10_000L)
        assertFailsWith<ConsentError> { t3.consume("quick", 10_001L) }
        t3.consume("standard", 10_001L)
        assertEquals(300_000L, ConsentSheet.TOKEN_TTL_MS)
    }

    @Test
    fun theSheetIsAPureFunctionOfItsInputsAndAsksForTheExtraTickAfterAHeatTestToday() {
        val a = ConsentSheet.forPlan(plan, 0L, emptyList(), false, false)
        val b = ConsentSheet.forPlan(plan, 0L, emptyList(), false, false)
        assertEquals(a.text, b.text)
        assertContentEquals(a.textSha256, b.textSha256)
        val again = ConsentSheet.forPlan(plan, 0L, emptyList(), false, true)
        assertTrue(again.text.contains("Run the heat test again today"))
        assertFalse(a.text.contains("Run the heat test again today"))
        assertTrue(a.text.contains("Downloads nothing"))
        assertTrue(sheet().text.contains("Downloads 4.3 GB"))
        assertTrue(a.text.all { it.code in 0x0A..0x7E })
    }
}

/** Exit paths: every scripted event ends with nothing leaked and a well-formed state chain. */
class ExecutorExitPathTest {
    private val kinds = listOf("CHARGER_REMOVED", "STOP", "BACKGROUND", "MEMORY_PRESSURE", "BATTERY_TEMP", "THERMAL_LOST", "REAL_REQUEST")

    private fun scenario(injections: String, extra: String = "") = BJ.parse("""{"preset":"phone","plan":"standard","shell":"android-standalone","daemon":false$extra,"injections":[$injections]}""")

    @Test
    fun everyInjectionAtManyInstantsLeavesNothingBehind() {
        val c = Counter("exit paths")
        var aborted = 0
        for (kind in kinds) {
            for (at in listOf(0L, 20_000L, 70_000L, 130_000L, 200_000L, 330_000L, 480_000L, 900_000L)) {
                val value = if (kind == "BATTERY_TEMP") ",\"value\":450" else ""
                val r = Scenarios.run(BJ.parse("""{"preset":"phone","plan":"standard","injections":[{"atMs":$at,"kind":"$kind"$value}]}"""))
                assertTrue(r.leaks.isEmpty(), "$kind@$at leaked ${r.leaks}")
                val states = r.events.filter { it.startsWith("STATE ") }
                assertTrue(states.last().endsWith("->DONE") || r.outcome.startsWith("refused"), "$kind@$at ended in ${states.lastOrNull()}")
                // every LOAD has a matching UNLOAD before the run finalises
                var open = 0
                for (e in r.events) {
                    if (e.startsWith("LOAD ")) open++
                    if (e.startsWith("UNLOAD ")) open--
                    assertTrue(open in 0..1, "$kind@$at: load/unload imbalance in $e")
                }
                assertEquals(0, open, "$kind@$at: a model is still loaded at the end")
                if (r.outcome.startsWith("aborted")) aborted++
                c.hit()
            }
        }
        assertTrue(aborted >= 20, "aborted only $aborted times")
        c.requireNonVacuous(56)
    }

    @Test
    fun aWallClockCapAbortsWithWallCapAndKeepsTheFinishedTests() {
        val cfg = Scenarios.preset("phone", "standard")
        val plan = RunPlans.STANDARD.copy(wallCapMs = mapOf("phone" to 100_000L))
        val host = FakeHost(cfg)
        val sheet = ConsentSheet.forPlan(plan, 0L, emptyList(), false, false)
        val session = BenchSession(host, plan, BenchSets.Q1, sheet.confirm(sheet.textSha256, host.epochMillis()))
        val out = session.run()
        val ab = out as? RunOutcome.Aborted ?: error("expected an abort, got $out")
        assertEquals("WALL_CAP", ab.reason)
        assertNotNull(ab.partial)
        assertTrue(host.leaks().isEmpty(), "leaks: ${host.leaks()}")
        assertEquals("WALL_CAP", ab.partial!!.run.abort!!.reason)
        assertTrue(session.trace.any { it.startsWith("ABORT WALL_CAP") })
    }

    @Test
    fun theStopInjectionAndTheUserStopRequestUseTheSamePath() {
        val viaInjection = Scenarios.run(BJ.parse("""{"preset":"phone","plan":"standard","injections":[{"atMs":100000,"kind":"STOP"}]}"""))
        assertEquals("aborted USER_STOP", viaInjection.outcome)
        val host = FakeHost(Scenarios.preset("phone", "standard"))
        val sheet = ConsentSheet.forPlan(RunPlans.STANDARD, 0L, emptyList(), false, false)
        val s = BenchSession(host, RunPlans.STANDARD, BenchSets.Q1, sheet.confirm(sheet.textSha256, host.epochMillis()))
        s.stop()
        val out = s.run()
        assertTrue(out is RunOutcome.Aborted && out.reason == "USER_STOP" || out is RunOutcome.Refused, "a stop before the first check ends the run: $out")
        assertTrue(host.leaks().isEmpty())
    }

    @Test
    fun refusedRunsStayAtPreflightAndTouchNothing() {
        val c = Counter("refusals")
        for ((extra, reason) in listOf(
            ""","batterySaver":true""" to "POWER_SAVER", ""","virtualized":true""" to "VIRTUALIZED", ""","memAvail":200000000""" to "NOT_ENOUGH_MEMORY",
            ""","thermalAvailable":false""" to "NO_THERMAL_SIGNAL", ""","modelsPresent":["T1"]""" to "MODELS_MISSING", ""","startCode":3""" to "TOO_WARM",
        )) {
            val r = Scenarios.run(BJ.parse("""{"preset":"phone","plan":"standard"$extra}"""))
            assertEquals("refused $reason", r.outcome)
            assertTrue(r.leaks.isEmpty())
            assertTrue(r.events.none { it.startsWith("LOAD ") || it.startsWith("STATE PREPARING") })
            assertNull(r.doc)
            c.hit()
        }
        c.requireNonVacuous(6)
    }

    @Test
    fun theSameScenarioGivesTheSameTraceAndDifferentSeedsDiffer() {
        val a = Scenarios.run(BJ.parse("""{"preset":"phone","plan":"standard","seed":5,"jitterPermille":20}"""))
        val b = Scenarios.run(BJ.parse("""{"preset":"phone","plan":"standard","seed":5,"jitterPermille":20}"""))
        val other = Scenarios.run(BJ.parse("""{"preset":"phone","plan":"standard","seed":6,"jitterPermille":20}"""))
        assertEquals(a.events, b.events)
        assertEquals(a.docSha256, b.docSha256)
        assertNotNull(a.docSha256)
        assertNotEquals(a.docSha256, other.docSha256)
    }

    @Test
    fun unknownScenarioMembersAreErrors() {
        assertFailsWith<IllegalArgumentException> { Scenarios.parse(BJ.parse("""{"preset":"phone","plan":"standard","injectons":[]}""")) }
    }
}

/** Derive, project and the plan objects. */
class DeriveAndPlanLawsTest {
    @Test
    fun theFileProjectionDropsBatteryScreenAndBuildIdentifiers() {
        val doc = ExampleDocs.phoneDoc()
        val file = Project.projectBenchFile(doc)
        assertTrue(Project.isFileForm(file))
        assertFalse(Project.isFileForm(doc))
        assertNull(file.run.batteryStartPermille)
        assertNull(file.run.screenOn)
        assertNull(file.device.osBuild)
        assertNull(file.device.gpuDriver)
        assertEquals(0L, file.run.startedAtMs % DAY_MS)
        assertEquals(0L, file.run.endedAtMs % DAY_MS)
        val rows = Project.results(Derive.derive(file), Audience.FILE)
        val json = Jcs.serialize(JArray(rows)).toString(Charsets.UTF_8)
        for (banned in listOf("batteryStartPermille", "screenOn", "socStartMilliC")) assertFalse(json.contains(banned), "FILE rows must not carry $banned")
    }

    @Test
    fun planHashesAreStableAndDistinct() {
        val hashes = RunPlans.all.map { it.sha256B64u() }
        assertEquals(3, hashes.toSet().size)
        for (p in RunPlans.all) {
            assertEquals(p.sha256B64u(), p.sha256B64u())
            assertEquals(43, p.sha256B64u().length)
            assertContentEquals(p.jcsBytes(), Jcs.serialize(p.toJson()))
        }
        val md = mdText("benchmark.md")
        val sec = md.substring(md.indexOf("### 5.2 Run-plan format"), md.indexOf("### 5.3"))
        val json = sec.substring(sec.indexOf("```json") + 7).substringBefore("```")
        assertEquals(RunPlans.STANDARD.sha256B64u(), xyz.mdhv.asom.lab.json.Base64Strict.encodeUrlNoPad(MessageDigest.getInstance("SHA-256").digest(Jcs.serialize(BJ.parse(json)))),
            "the standard plan is the benchmark.md 5.2 JSON, byte for byte after JCS")
    }

    @Test
    fun deriveIsDeterministicAndAnswersFollowTheDocument() {
        val doc = ExampleDocs.phoneDoc()
        val a = Derive.derive(doc)
        val b = Derive.derive(doc)
        assertEquals(a.answers, b.answers)
        assertEquals("occasional-helper", a.answers.role.code)
        // a document without the T3 tier and with too little memory cannot hold a 7-8B model
        val small = doc.copy(memory = doc.memory.copy(availAtStartBytes = 5_000_000_000L), tiers = doc.tiers.filter { it.tier != "T3" })
        assertEquals("cannot-hold", Derive.derive(small).answers.q7b.basis)
    }

    @Test
    fun theOccasionalHelperThresholdIsEightTokensPerSecond() {
        val doc = ExampleDocs.phoneDoc()
        val c = Counter("role threshold")
        // the T2 plateau is the T2 decode value times the heat-test stability (663 permille); pick decode values that land either side of 8000
        for ((micros, code) in listOf(10_000_000L to "occasional-helper", 10_500_000L to "occasional-helper", 11_000_000L to "requester", 12_000_000L to "requester")) {
            val tiers = doc.tiers.map { t ->
                if (t.tier != "T2") t else t.copy(tests = t.tests.map { x -> if (x.spec.name == "tg128@d0") x.copy(samples = List(x.samples.size) { micros }) else x })
            }
            val role = Derive.derive(doc.copy(tiers = tiers)).answers.role
            assertEquals(code, role.code, "T2 decode samples of $micros us give plateau ${role.t2PlateauMtps}")
            c.hit()
        }
        c.requireNonVacuous(4)
    }

    @Test
    fun theSustainMathFindsThePeakOnsetAndPlateau() {
        val flat = List(20) { BWindow(it * 15_000L, 100, 15_000_000L, 0) }
        val s = SustainMath.analyze(flat)
        assertNull(s.onsetIdx)
        assertEquals(6666L, s.peak)
        val drop = flat.mapIndexed { i, w -> if (i >= 8) BWindow(w.tStartMs, 60, 15_000_000L, 1) else w }
        val d = SustainMath.analyze(drop)
        assertNotNull(d.onsetIdx)
        assertTrue(d.onsetIdx!! in 7..9)
    }
}
