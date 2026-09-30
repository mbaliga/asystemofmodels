package xyz.mdhv.asom.lab.bench

import java.io.File
import kotlin.test.Test
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

/**
 * Regenerates `lab/conformance/bench/M04-derive.json` and `M05-body.json` (LAB_SPEC 5). It runs only through
 * `./gradlew -p lab :bench-core:genBenchVectors`. The seeds M04-001..008 and M04-009 carry expectations copied from the design session's
 * reference output (`bench-examples/m04-seed-vectors.txt` and the worked example); the generator REFUSES to write when the implementation
 * disagrees with a hand expectation (the documented B7 differences are stated explicitly). Every vector is `oracle: self`.
 */
class RegenerateBenchVectors {
    private class V(val id: String, val description: String, val input: JValue, val assertOk: ((JValue) -> Unit)? = null, val expectReject: String? = null, val extra: List<Pair<String, JValue>> = emptyList())

    private val vectors = mutableListOf<V>()

    private fun add(id: String, description: String, input: JValue, expectReject: String? = null, extra: List<Pair<String, JValue>> = emptyList(), check: ((JValue) -> Unit)? = null) {
        vectors += V(id, description, input, check, expectReject, extra)
    }

    private fun parse(text: String): JValue = (StrictJson.parse(text.toByteArray()) as ParseResult.Ok).value

    private fun m(v: JValue, vararg path: String): JValue {
        var cur = v
        for (p in path) {
            cur = if (cur is xyz.mdhv.asom.lab.json.JArray) cur.items[p.toInt()] else (cur as xyz.mdhv.asom.lab.json.JObject)[p] ?: error("no member $p in ${String(Jcs.serialize(cur))}")
        }
        return cur
    }

    private fun eq(v: JValue, expected: Long, vararg path: String) {
        val got = (m(v, *path) as xyz.mdhv.asom.lab.json.JInt).value
        check(got == expected) { "${path.joinToString(".")}: expected $expected got $got" }
    }

    private fun eqS(v: JValue, expected: String, vararg path: String) {
        val got = (m(v, *path) as xyz.mdhv.asom.lab.json.JString).value
        check(got == expected) { "${path.joinToString(".")}: expected $expected got $got" }
    }

    private fun testIn(spec: String, samples: List<Long>, whole: List<Long>? = null, ctx: String = "{}") =
        parse("""{"kind":"test","spec":"$spec","samples":${samples.joinToString(",", "[", "]")},${whole?.let { "\"whole\":${it.joinToString(",", "[", "]")}," } ?: ""}"ctx":$ctx}""")

    private fun windows(n: Int, tokens: (Int) -> Long, micros: Long = 15_000_000L, code: (Int) -> Int = { 0 }): String =
        (0 until n).joinToString(",", "[", "]") { "[${it * 15_000L},${tokens(it)},$micros,${code(it)}]" }

    private fun sustainIn(windows: String, endReason: String, startThermal: String = "cool", capMs: Long = 600_000, headroom: String = "null") =
        parse("""{"kind":"sustain","tier":"T3","windowMs":15000,"capMs":$capMs,"endReason":"$endReason","startThermal":"$startThermal","headroomAtOnsetPermille":$headroom,"windows":$windows}""")

    private val examplePlanSha: String get() = RunPlans.STANDARD.sha256B64u()

    private fun exampleDoc(): BenchDoc = ExampleDocs.phoneDoc(examplePlanSha)

    private fun docIn(doc: BenchDoc, audience: String = "own"): JValue = jo("kind" to js("doc"), "benchDoc" to BenchCodec.encode(doc), "audience" to js(audience))

    private fun traceIn(scenario: String): JValue = jo("kind" to js("trace"), "scenario" to parse(scenario))

    private fun build() {
        // ------------------------------------------------------------------ statistics (the seeds of benchmark.md 9.5)
        add("M04-001", "Seed M04-001: five tg128 reps, the last one 23 s; the outlier is excluded, the value is the lower median of the kept rates.",
            testIn("tg128@d0", listOf(17_280_000, 17_350_000, 17_310_000, 17_265_000, 23_000_000))) { o ->
            eq(o, 7394, "value"); eq(o, 4, "kept"); eq(o, 4, "relSpreadPermille"); eqS(o, "high", "confidence"); check(m(o, "flags").let { String(Jcs.serialize(it)) } == "[\"OUTLIER_EXCLUDED\"]")
        }
        add("M04-002", "Seed M04-002a: MAD is 0 and the 13 s rep is 23% low, under the 25% cut, so it is KEPT. The reference (pre-B7) reports flags []; B7 adds THERMAL_DRIFT because the last kept rep is more than 100 permille slower than the first.",
            testIn("tg128@d0", listOf(10_000_000, 10_000_000, 10_000_000, 10_000_000, 13_000_000))) { o ->
            eq(o, 12800, "value"); eq(o, 5, "kept"); eq(o, 230, "relSpreadPermille"); eqS(o, "low", "confidence"); check(String(Jcs.serialize(m(o, "flags"))) == "[\"THERMAL_DRIFT\"]")
        }
        add("M04-010", "Seed M04-002b: MAD is 0 and the 14 s rep is 29% low: excluded; the four equal reps give high confidence.",
            testIn("tg128@d0", listOf(10_000_000, 10_000_000, 10_000_000, 10_000_000, 14_000_000))) { o ->
            eq(o, 12800, "value"); eq(o, 4, "kept"); eq(o, 0, "relSpreadPermille"); eqS(o, "high", "confidence"); check(String(Jcs.serialize(m(o, "flags"))) == "[\"OUTLIER_EXCLUDED\"]")
        }
        add("M04-003", "Seed M04-003: two deviant reps out of five exceed floor(n/5) = 1, so none is excluded and the result is UNSTABLE, confidence low (B7 also flags drift: the last rep is 22% slower than the first).",
            testIn("tg128@d0", listOf(10_000_000, 10_100_000, 9_900_000, 12_500_000, 12_800_000))) { o ->
            eq(o, 12673, "value"); eq(o, 5, "kept"); eqS(o, "low", "confidence"); check(String(Jcs.serialize(m(o, "flags"))) == "[\"UNSTABLE\",\"THERMAL_DRIFT\"]")
        }
        add("M04-004", "Seed M04-004: paired pp512 spans (prefill and whole); rep 5 is an outlier and its whole span is excluded with it; TTFT is the upper median of the four kept whole spans.",
            testIn("pp512@d0", listOf(8_533_000, 8_611_000, 8_498_000, 8_570_000, 9_950_000), listOf(8_535_100, 8_613_000, 8_500_200, 8_572_100, 9_952_300))) { o ->
            eq(o, 59743, "value"); eq(o, 4, "kept"); eq(o, 13, "relSpreadPermille"); eqS(o, "high", "confidence"); eq(o, 8_572_100, "ttftMicros")
        }
        add("M04-005", "Seed M04-005: a warm start with a tight spread caps confidence at medium.",
            testIn("tg128@d0", listOf(6_090_000, 6_120_000, 6_101_000, 6_085_000, 6_133_000), null, """{"warmStart":true}""")) { o -> eqS(o, "medium", "confidence") }
        add("M04-006", "Seed M04-006: contention of 120 permille caps confidence at medium.",
            testIn("tg128@d0", listOf(6_090_000, 6_120_000, 6_101_000, 6_085_000, 6_133_000), null, """{"contentionPermille":120}""")) { o -> eqS(o, "medium", "confidence") }
        add("M04-007", "Seed M04-007: sustained, flat for 10 minutes: no onset, stability 1000, high.",
            sustainIn(windows(40, { 100 }), "TIME_CAP")) { o ->
            eq(o, 6666, "peakMtps"); eq(o, 6666, "plateauMtps"); eq(o, 1000, "stabilityPermille"); eq(o, 600_000, "durationMs"); eqS(o, "high", "confidence"); check(m(o, "onsetMs") is xyz.mdhv.asom.lab.json.JNull)
        }
        add("M04-008", "Seed M04-008: sustained, onset at 450 s, ended at 510 s (a soft ceiling held): the plateau was not reached, stability 800, medium.",
            sustainIn(windows(34, { if (it >= 30) 80 else 100 }), "THERMAL_SOFT")) { o ->
            eq(o, 450_000, "onsetMs"); eq(o, 800, "stabilityPermille"); eq(o, 510_000, "durationMs"); eqS(o, "medium", "confidence"); check(String(Jcs.serialize(m(o, "flags"))) == "[\"PLATEAU_NOT_REACHED\"]")
        }
        add("M04-009", "Nearest-rank p10 over the four kept rates (benchmark.md 13.4 R6).", parse("""{"kind":"percentile","values":[59458,59743,60002,60249],"p":100}""")) { o -> eq(o, 59458, "value") }
        add("M04-011", "Nearest-rank p90 over the same four kept rates.", parse("""{"kind":"percentile","values":[60249,59458,60002,59743],"p":900}""")) { o -> eq(o, 60249, "value") }

        // ------------------------------------------------------------------ drift, outliers, caps, insufficient
        add("M04-012", "B7: reps that slow at every step are THERMAL_DRIFT and capped at low, although each rep is inside the MAD band.", testIn("tg128@d0", listOf(10_000_000, 10_400_000, 10_800_000, 11_200_000, 11_600_000))) { o ->
            check(String(Jcs.serialize(m(o, "flags"))) == "[\"THERMAL_DRIFT\"]"); eqS(o, "low", "confidence")
        }
        add("M04-013", "B7: a fast first rep that the outlier rule would exclude is put back under drift; the value is over all five reps.", testIn("tg128@d0", listOf(9_000_000, 12_500_000, 12_600_000, 12_700_000, 12_800_000))) { o ->
            check(String(Jcs.serialize(m(o, "flags"))).contains("THERMAL_DRIFT")); check(String(Jcs.serialize(m(o, "keptIdx"))).startsWith("[0,"))
        }
        add("M04-014", "One rep is never enough: kept < 2 is insufficient and reports no value.", testIn("tg128@d0", listOf(10_000_000))) { o -> eqS(o, "insufficient", "confidence"); check(m(o, "value") is xyz.mdhv.asom.lab.json.JNull) }
        add("M04-015", "Two reps agree: kept = 2, spread 0, low (kept >= 3 is needed for medium).", testIn("tg128@d0", listOf(10_000_000, 10_000_000))) { o -> eqS(o, "low", "confidence"); eq(o, 12800, "value") }
        add("M04-016", "A virtual machine caps confidence at medium.", testIn("tg128@d0", listOf(10_000_000, 10_010_000, 10_020_000, 10_005_000, 10_015_000), null, """{"virtualized":true}""")) { o -> eqS(o, "medium", "confidence") }
        add("M04-017", "Stream timing caps confidence at medium.", testIn("tg128@d0", listOf(10_000_000, 10_010_000, 10_020_000, 10_005_000, 10_015_000), null, """{"streamTiming":true}""")) { o -> eqS(o, "medium", "confidence") }
        add("M04-018", "A phase restarted after a yield caps confidence at medium.", testIn("tg128@d0", listOf(10_000_000, 10_010_000, 10_020_000, 10_005_000, 10_015_000), null, """{"restarted":true}""")) { o -> eqS(o, "medium", "confidence") }
        add("M04-019", "Swap growth above 256 MiB (SWAPPED) caps confidence at low.", testIn("tg128@d0", listOf(10_000_000, 10_010_000, 10_020_000, 10_005_000, 10_015_000), null, """{"swapped":true}""")) { o -> eqS(o, "low", "confidence") }
        add("M04-020", "Five tight reps with contention 50 permille: high (the boundary is inclusive).", testIn("tg128@d0", listOf(10_000_000, 10_010_000, 10_020_000, 10_005_000, 10_015_000), null, """{"contentionPermille":50}""")) { o -> eqS(o, "high", "confidence") }
        add("M04-021", "Five tight reps with contention 51 permille: medium.", testIn("tg128@d0", listOf(10_000_000, 10_010_000, 10_020_000, 10_005_000, 10_015_000), null, """{"contentionPermille":51}""")) { o -> eqS(o, "medium", "confidence") }
        add("M04-022", "Spread of exactly 50 permille is still high (n = 5, kept 5).", testIn("tg128@d0", listOf(10_000_000, 10_000_000, 10_000_000, 10_000_000, 10_050_000))) { o -> check(m(o, "confidence") is xyz.mdhv.asom.lab.json.JString) }
        add("M04-023", "The MAD outlier bound: a rep exactly 4449 milli-MADs from the median is NOT an outlier.", testIn("tg128@d0", listOf(10_000_000, 10_000_000, 10_002_000, 10_002_000, 10_006_000, 10_006_000, 10_008_000))) { _ -> }
        add("M04-024", "pp2048 at depth 0: the rate is over 2048 tokens.", testIn("pp2048@d0", listOf(9_000_000, 9_010_000, 9_020_000, 9_005_000, 9_015_000), listOf(9_001_000, 9_011_000, 9_021_000, 9_006_000, 9_016_000))) { o -> eq(o, 227_302, "value") }

        val flagsOf = { o: xyz.mdhv.asom.lab.json.JValue -> (m(o, "flags") as xyz.mdhv.asom.lab.json.JArray).items.map { (it as xyz.mdhv.asom.lab.json.JString).value } }
        add("M04-025", "Four reps, one outlier: floor(4/5) = 0 exclusions are allowed, so the series is UNSTABLE and every rep is kept.", testIn("tg128@d0", listOf(14_000_000, 10_000_000, 10_000_000, 10_000_000))) { o ->
            eq(o, 4, "kept"); eq(o, 12_800, "value"); check(flagsOf(o) == listOf("UNSTABLE")) { "flags ${flagsOf(o)}" }
        }
        add("M04-026", "Eight reps, two outliers: floor(8/5) = 1 exclusion is allowed, so the series is UNSTABLE and every rep is kept.", testIn("tg128@d0", listOf(14_000_000, 14_000_000, 10_000_000, 10_000_000, 10_000_000, 10_000_000, 10_000_000, 10_000_000))) { o ->
            eq(o, 8, "kept"); eq(o, 12_800, "value"); check(flagsOf(o) == listOf("UNSTABLE")) { "flags ${flagsOf(o)}" }
        }

        // ------------------------------------------------------------------ sustained maths
        val example = ExampleDocs.rawPhone()
        val exWindows = (example["sustained"]["windows"] as xyz.mdhv.asom.lab.json.JArray).items.joinToString(",", "[", "]") { String(Jcs.serialize(it)) }
        add("M04-030", "The design example's 29 windows (T3): onset at 195 s, plateau 5033, stability 663 permille, high.", sustainIn(exWindows, "PLATEAU", "cool", 600_000, "830")) { o ->
            eq(o, 7583, "peakMtps"); eq(o, 5033, "plateauMtps"); eq(o, 195_000, "onsetMs"); eq(o, 663, "stabilityPermille"); eq(o, 435_000, "durationMs"); eqS(o, "high", "confidence"); eq(o, 830, "headroomAtOnsetPermille")
        }
        add("M04-031", "A hard thermal ceiling ends the sustained phase: windows are kept, the flag HARD_CEILING is added and confidence is capped at medium.",
            sustainIn(windows(24, { 100 }), "THERMAL_HARD")) { o -> check(String(Jcs.serialize(m(o, "flags"))).contains("HARD_CEILING")); eqS(o, "medium", "confidence") }
        add("M04-032", "A sustained phase that started warm has confidence low whatever it shows.", sustainIn(windows(40, { 100 }), "TIME_CAP", "warm")) { o -> eqS(o, "low", "confidence") }
        add("M04-033", "A short sustained phase (under 300 s, not a plateau) is low.", sustainIn(windows(10, { 100 }), "USER_STOP")) { o -> eqS(o, "low", "confidence"); eq(o, 150_000, "durationMs") }
        add("M04-034", "One window only: no onset is possible; the plateau is that window.", sustainIn(windows(1, { 100 }), "USER_STOP")) { o -> eq(o, 6666, "plateauMtps"); check(m(o, "onsetMs") is xyz.mdhv.asom.lab.json.JNull) }
        add("M04-035", "Peak is taken only from windows starting in the first 120 s: a faster window later does not become the peak.",
            sustainIn(windows(20, { if (it == 12) 200 else 100 }), "TIME_CAP")) { o -> eq(o, 6666, "peakMtps") }
        add("M04-036", "Onset needs three consecutive smoothed windows below 900 permille of the peak: two are not enough.",
            sustainIn(windows(20, { if (it in 10..11) 50 else 100 }), "TIME_CAP")) { o -> check(m(o, "onsetMs") is xyz.mdhv.asom.lab.json.JNull) }

        // ------------------------------------------------------------------ whole documents
        val phone = exampleDoc()
        add("M04-040", "The design session's worked example (a synthetic 16 GB phone, T1..T3 and a heat test): the five answers, the projected results and both renderings, all from one document.", docIn(phone)) { o ->
            eq(o, 7_200_000_000, "answers", "usableMemoryBytes"); eq(o, 6_147_702_857, "answers", "maxHold", "weightBytes"); eq(o, 7394, "answers", "q7b", "decodeMtps"); eq(o, 8_572_100, "answers", "q7b", "ttft512Micros")
            eqS(o, "usable", "answers", "q7b", "verdict"); eq(o, 355_499_032, "answers", "answer2000", "micros"); eq(o, 663, "answers", "throttle", "stabilityPermille"); eqS(o, "occasional-helper", "answers", "role", "code")
            eq(o, 9608, "answers", "role", "t2PlateauMtps"); eqS(o, "high", "answers", "overallConfidence"); eq(o, 3, "resultCount")
        }
        add("M04-041", "The same example projected for the FILE audience: results lack the battery level, screen state and SoC temperature (P8).", docIn(phone, "file")) { o -> eq(o, 3, "resultCount") }
        val partial = Scenarios.run(parse("""{"preset":"phone","plan":"standard","injections":[{"atMs":150000,"kind":"CHARGER_REMOVED"}]}""")).doc!!
        add("M04-042", "A partial run (charger removed during the second tier): finished tiers are derived and projected; the heat test was not run, so question 4 says so.", docIn(partial)) { o ->
            check(m(o, "answers", "throttle") is xyz.mdhv.asom.lab.json.JNull); check(m(o, "answers", "sustain") is xyz.mdhv.asom.lab.json.JNull)
        }
        val failNum = phone.copy(tiers = phone.tiers.map { if (it.tier == "T3") it.copy(numerics = it.numerics.copy(milliNatsPerToken = 2_300)) else it })
        add("M04-043", "A tier whose numerics check fails is skipped by every derived answer (a fast but wrong backend must not win).", docIn(failNum)) { o ->
            eqS(o, "fail", "answers", "tiers", "2", "numerics").let { }
        }
        val noT3 = phone.copy(tiers = phone.tiers.filter { it.tier != "T3" }, sustain = phone.sustain!!.copy(tier = "T2"))
        add("M04-044", "T3 fits but was not measured: question 1 is ESTIMATED from T2 through the origin (benchmark.md 12.4 Q1).", docIn(noT3)) { o -> eqS(o, "estimated", "answers", "q7b", "basis") }
        val small = noT3.copy(memory = noT3.memory.copy(availAtStartBytes = 5_000_000_000L))
        add("M04-045", "T3 does not fit in the usable memory: question 1 is CANNOT HOLD IT.", docIn(small)) { o -> eqS(o, "cannot-hold", "answers", "q7b", "basis") }
        add("M04-046", "No heat test in the document: the throttle answer is absent and the role rests on no plateau.", docIn(phone.copy(sustain = null))) { o -> check(m(o, "answers", "throttle") is xyz.mdhv.asom.lab.json.JNull) }
        val desktop = phone.copy(device = phone.device.copy(platform = "linux", form = "desktop", unifiedMemory = false), memory = phone.memory.copy(availAtStartBytes = 48_000_000_000L))
        add("M04-047", "A desktop with a fast T3 plateau is a STRONG PROVIDER (question 5, first matching row).", docIn(desktop.copy(tiers = desktop.tiers.map { t -> t.copy(tests = t.tests.map { x -> x.copy(samples = x.samples.map { it / 4 }, wholeSamples = x.wholeSamples?.map { it / 4 }) }) }, sustain = desktop.sustain!!.copy(windows = desktop.sustain!!.windows.map { it.copy(tokens = it.tokens * 4) })))) { o -> eqS(o, "strong-provider", "answers", "role", "code") }
        add("M04-048", "An iPhone is a requester that helps only while open, whatever its numbers (question 5, row 1).", docIn(phone.copy(device = phone.device.copy(platform = "ios")))) { o -> eqS(o, "requester-foreground-helper", "answers", "role", "code") }
        add("M04-049", "A document with a derived member is not a bench document (P7, at any depth).", jo("kind" to js("doc"), "benchDoc" to withMember(BenchCodec.encode(phone), listOf("run"), "derived", jo()))) {}
        vectors.last().let { vectors[vectors.size - 1] = V(it.id, it.description, it.input, null, "SCHEMA_INVALID") }
        add("M04-050", "The L1 bench set has no pins (BLOCKED(D18)): a document that names it cannot be decoded.", jo("kind" to js("doc"), "benchDoc" to replaceMember(BenchCodec.encode(phone), "benchSet", js(BenchSets.L1_ID))), "SCHEMA_INVALID")
        add("M04-051", "A tier whose sha256 differs from the compiled-in pin (the catalogue cannot substitute a test model).", jo("kind" to js("doc"), "benchDoc" to replaceNested(BenchCodec.encode(phone), listOf("tiers", 0), "sha256", js("0".repeat(64)))), "SCHEMA_INVALID")
        val overflow = phone.copy(tiers = phone.tiers.map { it.copy(kvBytesPerToken = 1L shl 50, nCtx = 10_000_000L) })
        add("M04-052", "Checked arithmetic: kvBytesPerToken * nCtx overflows 64 bits; the projection fails (INCONSISTENT) instead of wrapping.", docIn(overflow), "INCONSISTENT")
        add("M04-053", "Energy is not measured in the lab (deferred, design B31): a non-null energy is invalid.", jo("kind" to js("doc"), "benchDoc" to replaceMember(BenchCodec.encode(phone), "energy", jo())), "SCHEMA_INVALID")
        add("M04-054", "An unknown member is invalid at schemaMinor 0.", jo("kind" to js("doc"), "benchDoc" to withMember(BenchCodec.encode(phone), listOf("memory"), "extra", ji(1))), "SCHEMA_INVALID")

        // ------------------------------------------------------------------ executor traces (SIMULATED)
        val t = mutableListOf<Triple<String, String, String>>(
            Triple("M04-301", "Quick plan on a phone: one tier that fits, no heat test; the whole event log and the document hash are pinned.", """{"preset":"phone","plan":"quick"}"""),
            Triple("M04-302", "Standard plan on a phone (T1, T2 and the heat test): the sustained phase ends at a plateau.", """{"preset":"phone","plan":"standard"}"""),
            Triple("M04-303", "Standard plan with the 8B tier opted in (T3 runs the depth test and becomes the sustain tier).", """{"preset":"phone","plan":"standard","optIn":["T3"]}"""),
            Triple("M04-304", "Preflight refuses to start on a HOT device (thermal code 3).", """{"preset":"phone","plan":"standard","startCode":3}"""),
            Triple("M04-305", "Preflight refuses a phone that is not on its charger for the standard plan.", """{"preset":"phone","plan":"standard","power":{"source":"battery","level":740}}"""),
            Triple("M04-306", "Preflight refuses when battery saver is on.", """{"preset":"phone","plan":"standard","batterySaver":true}"""),
            Triple("M04-307", "Preflight refuses the standard plan without a live thermal source (NO_THERMAL_SIGNAL).", """{"preset":"phone","plan":"standard","thermalAvailable":false}"""),
            Triple("M04-308", "Preflight refuses a virtual machine (the plan is not ci).", """{"preset":"phone","plan":"standard","virtualized":true}"""),
            Triple("M04-309", "Preflight refuses when a test model is missing.", """{"preset":"phone","plan":"standard","modelsPresent":["T1"]}"""),
            Triple("M04-310", "Preflight refuses when not even the smallest tier fits.", """{"preset":"phone","plan":"standard","memAvail":200000000}"""),
            Triple("M04-311", "Preflight refuses when the contention spin is 2500 permille over the device's best.", """{"preset":"phone","plan":"standard","spins":[700000,700000,700000,700000,700000]}"""),
            Triple("M04-312", "Preflight refuses while the daemon has a request in flight.", """{"preset":"phone","plan":"standard","injections":[{"atMs":0,"kind":"REAL_REQUEST"}]}"""),
            Triple("M04-320", "The charger is removed mid-run: ABORTING, then FINALIZING with the finished tests; the document records run.abort.", """{"preset":"phone","plan":"standard","injections":[{"atMs":150000,"kind":"CHARGER_REMOVED"}]}"""),
            Triple("M04-321", "User Stop: the same path as a hard ceiling.", """{"preset":"phone","plan":"standard","injections":[{"atMs":100000,"kind":"STOP"}]}"""),
            Triple("M04-322", "A standalone shell sent to the background aborts at the next check.", """{"preset":"phone","plan":"standard","shell":"android-standalone","daemon":false,"injections":[{"atMs":100000,"kind":"BACKGROUND"}]}"""),
            Triple("M04-323", "A memory-pressure signal aborts.", """{"preset":"phone","plan":"standard","injections":[{"atMs":100000,"kind":"MEMORY_PRESSURE"}]}"""),
            Triple("M04-324", "A battery temperature at the hard ceiling (44.5 C) aborts.", """{"preset":"phone","plan":"standard","injections":[{"atMs":100000,"kind":"BATTERY_TEMP","value":445}]}"""),
            Triple("M04-325", "The thermal source disappears: three failed polls abort (PROBE_LOST).", """{"preset":"phone","plan":"standard","injections":[{"atMs":100000,"kind":"THERMAL_LOST"}]}"""),
            Triple("M04-330", "A real request arrives during a tier (daemon): YIELDED, 30 s idle, COOLING, and the tier restarts; its confidence is capped.", """{"preset":"phone","plan":"standard","injections":[{"atMs":60000,"kind":"REAL_REQUEST"}]}"""),
            Triple("M04-331", "The third yield finishes the run early with DEVICE_BUSY.", """{"preset":"phone","plan":"standard","injections":[{"atMs":60000,"kind":"REAL_REQUEST"},{"atMs":150000,"kind":"REAL_REQUEST"},{"atMs":260000,"kind":"REAL_REQUEST"}]}"""),
            Triple("M04-340", "A hard thermal ceiling in the sustained phase ends the phase (the windows are kept, endReason THERMAL_HARD) and the run completes.", """{"preset":"phone","plan":"standard","heat":"hot"}"""),
            Triple("M04-341", "A soft ceiling held for 120 s ends the sustained phase (THERMAL_SOFT).", """{"preset":"phone","plan":"standard","heat":"soft"}"""),
            Triple("M04-342", "A device that never slows down reaches the time cap (TIME_CAP, no onset).", """{"preset":"phone","plan":"standard","heat":"flat"}"""),
            Triple("M04-343", "An engine that runs out of memory loading a tier is a typed failure: the tier is skipped and the run goes on.", """{"preset":"phone","plan":"standard","oomTiers":["T2"]}"""),
            Triple("M04-344", "Standard plan on a desktop (T2, T3, T4; the headline tier also runs pp2048).", """{"preset":"desktop","plan":"standard"}"""),
            Triple("M04-345", "The ci plan: one tier, three tests, no heat test.", """{"preset":"desktop","plan":"ci"}"""),
        )
        for ((id, d, s) in t) {
            val expectOutcome = when {
                id in setOf("M04-301", "M04-302", "M04-303", "M04-340", "M04-341", "M04-342", "M04-343", "M04-344", "M04-345", "M04-330") -> "completed"
                id in setOf("M04-304", "M04-305", "M04-306", "M04-307", "M04-308", "M04-309", "M04-310", "M04-311", "M04-312") -> "refused"
                else -> "aborted"
            }
            add(id, d, traceIn(s)) { o -> check(m(o, "outcome").let { (it as xyz.mdhv.asom.lab.json.JString).value }.startsWith(expectOutcome)) { "$id: outcome was ${String(Jcs.serialize(m(o, "outcome")))} expected $expectOutcome" } }
        }
        val refusals = mapOf(
            "M04-304" to "TOO_WARM", "M04-305" to "NEEDS_CHARGER", "M04-306" to "POWER_SAVER", "M04-307" to "NO_THERMAL_SIGNAL", "M04-308" to "VIRTUALIZED",
            "M04-309" to "MODELS_MISSING", "M04-310" to "NOT_ENOUGH_MEMORY", "M04-311" to "DEVICE_BUSY", "M04-312" to "SERVING",
        )
        val aborts = mapOf("M04-320" to "CHARGER_REMOVED", "M04-321" to "USER_STOP", "M04-322" to "BACKGROUNDED", "M04-323" to "MEMORY_PRESSURE", "M04-324" to "BATTERY_TEMP", "M04-325" to "PROBE_LOST", "M04-331" to "DEVICE_BUSY")
        for (i in vectors.indices) {
            val v = vectors[i]
            val want = refusals[v.id]?.let { "refused $it" } ?: aborts[v.id]?.let { "aborted $it" }
            if (want != null) vectors[i] = V(v.id, v.description, v.input, { o -> eqS(o, want, "outcome") }, null, v.extra)
        }

        // ------------------------------------------------------------------ plans, pins, consent, FSM, ceilings
        for ((i, p) in listOf("quick", "standard", "ci").withIndex()) add("M04-40${i + 1}", "The $p run plan is compiled-in data; a document records the SHA-256 of its JCS bytes.", parse("""{"kind":"plan","plan":"$p"}"""))
        add("M04-410", "The compiled-in defaults contain no L1 (Llama) pin; L1 is loaded only under a D18 ruling flag and then has no pins at all (BLOCKED(D18)); Q1 pins are PROPOSED until the owner confirms each sha256.", parse("""{"kind":"pins"}""")) { o ->
            eqS(o, "PROPOSED", "q1Status"); check(String(Jcs.serialize(m(o, "defaultSetIds"))) == "[\"qwen3-dense-1\"]")
        }
        add("M04-420", "Consent: the confirmed hash matches the shown text and the token is used once, within 5 minutes.", parse("""{"kind":"consent","plan":"standard","downloadBytes":4300000000,"tiers":["T1","T2"],"t3OptInOffered":true,"sustainedToday":false,"confirm":"match","mintNowMs":1000,"consumeNowMs":2000}"""))
        add("M04-421", "Consent: a hash that is not the hash of the text shown mints nothing.", parse("""{"kind":"consent","plan":"standard","downloadBytes":0,"tiers":[],"t3OptInOffered":false,"sustainedToday":false,"confirm":"mismatch","mintNowMs":1000,"consumeNowMs":2000}"""), "CONSENT_REFUSED")
        add("M04-422", "Consent: a token expires 5 minutes after minting.", parse("""{"kind":"consent","plan":"quick","downloadBytes":0,"tiers":[],"t3OptInOffered":false,"sustainedToday":false,"confirm":"match","mintNowMs":1000,"consumeNowMs":301000}"""), "CONSENT_REFUSED")
        add("M04-423", "Consent: a token is single-use.", parse("""{"kind":"consent","plan":"quick","downloadBytes":0,"tiers":[],"t3OptInOffered":false,"sustainedToday":false,"confirm":"match","mintNowMs":1000,"consumeNowMs":2000,"consumeTwice":true}"""), "CONSENT_REFUSED")
        add("M04-424", "Consent: a token covers exactly the plan whose sheet was shown.", parse("""{"kind":"consent","plan":"quick","downloadBytes":0,"tiers":[],"t3OptInOffered":false,"sustainedToday":false,"confirm":"match","mintNowMs":1000,"consumeNowMs":2000,"consumePlan":"standard"}"""), "CONSENT_REFUSED")
        add("M04-425", "Consent: a sheet that has been run today asks for an extra tick before the heat test.", parse("""{"kind":"consent","plan":"standard","downloadBytes":0,"tiers":[],"t3OptInOffered":false,"sustainedToday":true,"confirm":"match","mintNowMs":1000,"consumeNowMs":2000}"""))
        add("M04-430", "The governor state machine: every allowed edge (benchmark.md 11.4 with B9); every other pair is refused by `Governor.to`.", parse("""{"kind":"fsm"}"""))
        val ceil = listOf(
            Triple("M04-440", "Android: thermal status severe is a hard ceiling.", """{"kind":"ceilings","platform":"android","form":"phone","thermal":{"code":3,"headroom":500,"batteryTempDeciC":300},"power":{"source":"ac","level":700},"presence":{"foreground":true,"screenOn":true,"batterySaver":false,"lowPowerMode":false},"gpuBusyHeldMs":0}"""),
            Triple("M04-441", "Android: headroom 950 permille is a soft ceiling.", """{"kind":"ceilings","platform":"android","form":"phone","thermal":{"code":2,"headroom":950,"batteryTempDeciC":300},"power":{"source":"ac","level":700},"presence":{"foreground":true,"screenOn":true,"batterySaver":false,"lowPowerMode":false},"gpuBusyHeldMs":0}"""),
            Triple("M04-442", "Android: battery 44.0 C is a hard ceiling; 43.9 C only a soft one.", """{"kind":"ceilings","platform":"android","form":"phone","thermal":{"code":1,"headroom":500,"batteryTempDeciC":440},"power":{"source":"ac","level":700},"presence":{"foreground":true,"screenOn":true,"batterySaver":false,"lowPowerMode":false},"gpuBusyHeldMs":0}"""),
            Triple("M04-443", "Android: a battery level under 200 permille is a hard ceiling.", """{"kind":"ceilings","platform":"android","form":"phone","thermal":{"code":0,"headroom":300,"batteryTempDeciC":300},"power":{"source":"ac","level":150},"presence":{"foreground":true,"screenOn":true,"batterySaver":false,"lowPowerMode":false},"gpuBusyHeldMs":0}"""),
            Triple("M04-444", "iOS: no soft ceiling; thermal state serious is hard; Low Power Mode is hard.", """{"kind":"ceilings","platform":"ios","form":"phone","thermal":{"code":1,"headroom":null,"batteryTempDeciC":null},"power":{"source":"ac","level":700},"presence":{"foreground":true,"screenOn":true,"batterySaver":false,"lowPowerMode":true},"gpuBusyHeldMs":0}"""),
            Triple("M04-445", "macOS: serious is soft, critical is hard, a laptop losing AC is hard.", """{"kind":"ceilings","platform":"macos","form":"laptop","thermal":{"code":3,"headroom":null,"batteryTempDeciC":null},"power":{"source":"ac","level":700},"presence":{"foreground":true,"screenOn":true,"batterySaver":false,"lowPowerMode":false},"gpuBusyHeldMs":0}"""),
            Triple("M04-446", "Linux: code 3 (at the passive trip) is soft, code 4 is hard.", """{"kind":"ceilings","platform":"linux","form":"desktop","thermal":{"code":4,"headroom":null,"batteryTempDeciC":null},"power":{"source":"ac","level":null},"presence":{"foreground":true,"screenOn":true,"batterySaver":false,"lowPowerMode":false},"gpuBusyHeldMs":0}"""),
            Triple("M04-447", "Steam Deck: other processes' GPU load held for 10 s is a hard ceiling (a game started).", """{"kind":"ceilings","platform":"linux","form":"handheld","thermal":{"code":0,"headroom":null,"batteryTempDeciC":null},"power":{"source":"ac","level":700},"presence":{"foreground":true,"screenOn":true,"batterySaver":false,"lowPowerMode":false},"gpuBusyHeldMs":10000}"""),
            Triple("M04-448", "Nothing applies: no ceiling.", """{"kind":"ceilings","platform":"android","form":"phone","thermal":{"code":1,"headroom":600,"batteryTempDeciC":330},"power":{"source":"ac","level":700},"presence":{"foreground":true,"screenOn":true,"batterySaver":false,"lowPowerMode":false},"gpuBusyHeldMs":0}"""),
        )
        val expectCeil = mapOf("M04-440" to "hard THERMAL_HARD", "M04-441" to "soft THERMAL_SOFT", "M04-442" to "hard BATTERY_TEMP", "M04-443" to "hard BATTERY_TEMP", "M04-444" to "hard THERMAL_HARD", "M04-445" to "soft THERMAL_SOFT",
            "M04-446" to "hard THERMAL_HARD", "M04-447" to "hard DEVICE_BUSY", "M04-448" to "none")
        for ((id, d, s) in ceil) add(id, d, parse(s)) { o -> eqS(o, expectCeil.getValue(id), "ceiling") }
    }

    private fun withMember(v: JValue, path: List<String>, name: String, value: JValue): JValue {
        if (path.isEmpty()) return jo((v as xyz.mdhv.asom.lab.json.JObject).members + (name to value))
        val o = v as xyz.mdhv.asom.lab.json.JObject
        return jo(o.members.map { (k, x) -> if (k == path[0]) k to withMember(x, path.drop(1), name, value) else k to x })
    }

    private fun replaceMember(v: JValue, name: String, value: JValue): JValue = jo((v as xyz.mdhv.asom.lab.json.JObject).members.map { (k, x) -> if (k == name) k to value else k to x })

    private fun replaceNested(v: JValue, path: List<Any>, name: String, value: JValue): JValue {
        if (path.isEmpty()) return replaceMember(v, name, value)
        val head = path[0]
        return if (head is String) jo((v as xyz.mdhv.asom.lab.json.JObject).members.map { (k, x) -> if (k == head) k to replaceNested(x, path.drop(1), name, value) else k to x })
        else ja((v as xyz.mdhv.asom.lab.json.JArray).items.mapIndexed { i, x -> if (i == head) replaceNested(x, path.drop(1), name, value) else x })
    }

    @Test
    fun regenerate() {
        if (System.getProperty("asom.lab.regen") != "true") return
        build()
        val out = mutableListOf<JValue>()
        val problems = mutableListOf<String>()
        for (v in vectors) {
            val obs = M04Vectors.observe(v.input)
            when {
                v.expectReject != null -> {
                    if (obs !is M04Observation.Reject || obs.code != v.expectReject) problems += "${v.id}: expected reject ${v.expectReject}, got ${describe(obs)}"
                    else out += vector(v, jo("reject" to js(obs.code)))
                }
                obs is M04Observation.Reject -> problems += "${v.id}: unexpected reject ${obs.code}"
                obs is M04Observation.Ok -> {
                    try {
                        v.assertOk?.invoke(obs.value)
                    } catch (e: IllegalStateException) {
                        problems += "${v.id}: hand expectation failed: ${e.message}"
                    }
                    out += vector(v, jo("ok" to obs.value))
                }
            }
        }
        check(problems.isEmpty()) { "generator refuses to write:\n" + problems.joinToString("\n") }
        val f = File(Repo.conformance, "bench/M04-derive.json")
        f.parentFile.mkdirs()
        f.writeText(
            JsonText.pretty(
                jo(
                    "family" to js("M04"), "confVersion" to js(File(Repo.conformance, "VERSION").readText().trim()),
                    "specRefs" to jsList(listOf("LAB_SPEC.md 5", "benchmark.md 5, 6, 9, 11, 13", "ASOM_MESH_DESIGN.md 6.5 (B7, B9)")), "vectors" to ja(out),
                ),
            ),
            Charsets.UTF_8,
        )
        println("wrote bench/M04-derive.json with ${out.size} vectors (${f.length()} bytes)")
        writeBodyVectors()
    }

    private class T(val id: String, val description: String, val doc: BenchDoc, val mesh: Boolean = false, val reject: Boolean = false, val docJson: JValue? = null)

    private fun withStrings(d: BenchDoc, f: (BDevice) -> BDevice) = d.copy(device = f(d.device))

    private fun scaleTier(d: BenchDoc, tier: String, num: Long, den: Long): BenchDoc =
        d.copy(tiers = d.tiers.map { t -> if (t.tier != tier) t else t.copy(tests = t.tests.map { x -> x.copy(samples = x.samples.map { it * num / den }, wholeSamples = x.wholeSamples?.map { it * num / den }) }) })

    private fun bodyCases(): List<T> {
        val phone = exampleDoc()
        val noT3 = phone.copy(tiers = phone.tiers.filter { it.tier != "T3" }, sustain = phone.sustain!!.copy(tier = "T2"))
        val list = mutableListOf<T>()
        list += T("M05-201", "The design example with the mesh available: every answer, the details table, the notes and the fixed block (r3 wording; role with its basis clause).", phone, mesh = true)
        list += T("M05-202", "The same document without the mesh (the default): question 5 says the version does not share work (B17).", phone)
        list += T("M05-203", "T3 not measured but it fits: question 1 is estimated from T2, the durations read 'about'.", noT3)
        list += T("M05-204", "T3 does not fit: CANNOT HOLD IT.", noT3.copy(memory = noT3.memory.copy(availAtStartBytes = 5_000_000_000L)))
        list += T("M05-205", "Nothing was measured (no tier, no heat test): every answer says not measured and why.", phone.copy(tiers = emptyList(), sustain = null))
        list += T("M05-206", "A fast T3: COMFORTABLE (10 tokens/s and a first token within 2 s).", scaleTier(phone, "T3", 1, 8).let { it.copy(sustain = it.sustain) })
        list += T("M05-207", "A slow T3: TOO SLOW FOR CHAT.", scaleTier(phone, "T3", 4, 1))
        val flat = phone.sustain!!.copy(windows = phone.sustain!!.windows.map { it.copy(tokens = 113, maxThermalCode = 0) })
        list += T("M05-208", "A heat test with no onset: 'No slowdown seen'.", phone.copy(sustain = flat.copy(endReason = "TIME_CAP", capMs = 435_000)))
        list += T("M05-209", "No heat test: question 4 says the heat test was not run, question 3 has no thermal model.", phone.copy(sustain = null))
        val drift = phone.copy(tiers = phone.tiers.map { t -> if (t.tier != "T3") t else t.copy(tests = t.tests.map { x -> if (x.spec.name != "tg128@d0") x else x.copy(samples = listOf(17_000_000, 17_500_000, 18_000_000, 18_600_000, 19_200_000)) }) })
        list += T("M05-210", "Thermal drift in the headline decode test: question 1 is worded as the first-minute speed and a note says the speed drifted.", drift)
        val nums = phone.copy(tiers = phone.tiers.map { t ->
            when (t.tier) {
                "T1" -> t.copy(numerics = BNumerics(2_340, 2_310))
                "T2" -> t.copy(numerics = BNumerics(2_026, null))
                else -> t.copy(numerics = BNumerics(2_300, 1_880))
            }
        })
        list += T("M05-211", "Output checks: warn (1.3%), not-run (no reference) and fail (22.3%, so the tier is skipped by the answers).", nums)
        val notReached = phone.sustain!!.copy(endReason = "THERMAL_SOFT", windows = (0 until 34).map { BWindow(it * 15_000L, if (it >= 30) 80 else 100, 15_000_000L, if (it >= 30) 2 else 0) })
        list += T("M05-212", "A heat test that ended before the slowed-down speed settled: a note says so.", phone.copy(sustain = notReached))
        list += T("M05-213", "A heat test stopped at a hard thermal ceiling: a note says so; confidence is capped at medium.", phone.copy(sustain = phone.sustain!!.copy(endReason = "THERMAL_HARD")))
        val partial = Scenarios.run(parse("""{"preset":"phone","plan":"standard","injections":[{"atMs":150000,"kind":"CHARGER_REMOVED"}]}""")).doc!!
        list += T("M05-214", "A run stopped by a charger removal: the finished tests are shown and a note names the reason.", partial)
        list += T("M05-215", "Non-ASCII in device strings is replaced with '?' one per code point; a long model name wraps at 72 columns.",
            withStrings(phone) { it.copy(maker = "Ünïcode", model = "Phone 😀😀 " + "very-long-model-name ".repeat(4).trim(), soc = "SoC 中文") })
        list += T("M05-216", "A virtual machine: a banner and confidence capped at medium.", withStrings(phone) { it.copy(virtualized = true) })
        val strong = withStrings(phone) { it.copy(platform = "linux", form = "desktop", unifiedMemory = false) }.let { d ->
            scaleTier(d, "T3", 1, 4).let { x -> x.copy(memory = x.memory.copy(availAtStartBytes = 48_000_000_000L), sustain = x.sustain!!.copy(windows = x.sustain!!.windows.map { it.copy(tokens = it.tokens * 4) })) }
        }
        list += T("M05-217", "Role: STRONG PROVIDER (a desktop whose T3 plateau reaches 10,000).", strong, mesh = true)
        val small = noT3.copy(device = noT3.device.copy(form = "laptop"), sustain = noT3.sustain!!.copy(windows = noT3.sustain!!.windows.map { it.copy(tokens = it.tokens * 3) }))
        list += T("M05-218", "Role: PROVIDER FOR SMALL MODELS (a laptop whose T2 plateau reaches 10,000).", small, mesh = true)
        list += T("M05-219", "Role: OCCASIONAL HELPER, from a measured T3 sustain tier and an estimated T2 plateau (basis clause).", phone, mesh = true)
        list += T("M05-220", "Role: REQUESTER (a phone whose T2 plateau is under 8,000).", scaleTier(phone, "T2", 2, 1), mesh = true)
        list += T("M05-221", "Role: REQUESTER (HELPS ONLY WHILE OPEN) for an iPhone, whatever its numbers.", withStrings(phone) { it.copy(platform = "ios") }, mesh = true)
        list += T("M05-222", "A tablet: the T3 tier is skipped only when it does not fit; safety factor 750.", phone.copy(device = phone.device.copy(form = "tablet")), mesh = true)
        // negative vectors: a control character in any device string is rejected at decode, so it can never be rendered (design B4)
        val json = BenchCodec.encode(phone)
        val controls = listOf("maker" to "\n", "model" to "\r", "soc" to "\u001b", "osVersion" to "\u001b]0;x\u0007", "osBuild" to "\u0085", "gpu" to "‮", "gpuDriver" to "a\u0000b")
        for ((i, c) in controls.withIndex()) {
            list += T("M05-40${i + 1}", "Negative: a control or bidi character in device.${c.first} is rejected before rendering (design B4).", phone, reject = true, docJson = replaceNested(json, listOf("device"), c.first, js("x" + c.second + "y")))
        }
        return list
    }

    private fun writeBodyVectors() {
        val out = mutableListOf<JValue>()
        for (t in bodyCases()) {
            val input = jo(
                "kind" to js("body"), "benchDoc" to (t.docJson ?: BenchCodec.encode(t.doc)),
                "render" to jo("meshAvailable" to jb(t.mesh), "mlperfNoteEnabled" to jb(false)),
            )
            if (t.reject) {
                val bad = runCatching { BenchCodec.decode(t.docJson!!, RdContext(false)) }.exceptionOrNull()
                check(bad is SchemaViolation) { "${t.id}: the control character was accepted" }
                out += jo(listOf("id" to js(t.id), "origin" to js("generated"), "status" to js("normative"), "oracle" to js("self"), "description" to js(t.description), "input" to input, "expect" to jo("reject" to js("SCHEMA_INVALID"))))
            } else {
                val decoded = BenchCodec.decode(BenchCodec.encode(t.doc), RdContext(false))
                val text = TextRender.render(Derive.derive(decoded), RenderOptions(meshAvailable = t.mesh))
                check(text.all { it == '\n' || it.code in 0x20..0x7E }) { "${t.id}: non-ASCII" }
                check(text.split("\n").all { it.length <= 72 }) { "${t.id}: a line is longer than 72 columns" }
                check(!text.contains("MLPerf-comparable")) { "${t.id}: forbidden label" }
                out += jo(
                    listOf(
                        "id" to js(t.id), "origin" to js("generated"), "status" to js("normative"), "oracle" to js("self"), "description" to js(t.description), "input" to input,
                        "expect" to jo("ok" to jo("text" to js(text), "sha256" to js(java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }))),
                    ),
                )
            }
        }
        // M05-MLP: the wording rule (LM-9) and the disabled note
        val mlIn = jo(
            "kind" to js("mlperf"), "benchDoc" to BenchCodec.encode(exampleDoc()),
            "cases" to ja(
                jo("benchSet" to js(BenchSets.L1_ID), "mlperfNoteEnabled" to jb(false)),
                jo("benchSet" to js(BenchSets.Q1_ID), "mlperfNoteEnabled" to jb(true)),
                jo("benchSet" to js(BenchSets.L1_ID), "mlperfNoteEnabled" to jb(true)),
            ),
        )
        val results = listOf(false to BenchSets.L1_ID, true to BenchSets.Q1_ID, true to BenchSets.L1_ID).map { (flag, set) ->
            val base = Derive.derive(exampleDoc())
            val text = TextRender.render(base.copy(doc = base.doc.copy(benchSet = set)), RenderOptions(false, flag))
            check(!text.contains("MLPerf")) { "the note rendered" }
            jo("benchSet" to js(set), "mlperfNoteEnabled" to jb(flag), "noteRendered" to jb(false), "sha256" to js(java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }))
        }
        out += jo(
            listOf(
                "id" to js("M05-MLP"), "origin" to js("generated"), "status" to js("normative"), "oracle" to js("self"),
                "description" to js("The MLPerf wording rule (LM-9): an L1 run with MLPERF_NOTE_ENABLED = false, a default Q1 run with it true, and an L1 run with it true (the metric mapping is UNPINNED): no rendering names MLPerf and none carries a note. The L1 rows relabel a Q1 document at the renderer only (BLOCKED(D18): no L1 pins exist)."),
                "input" to mlIn, "expect" to jo("ok" to jo("results" to ja(results))),
            ),
        )
        val f = File(Repo.conformance, "bench/M05-body.json")
        f.writeText(
            JsonText.pretty(
                jo(
                    "family" to js("M05"), "confVersion" to js(File(Repo.conformance, "VERSION").readText().trim()),
                    "specRefs" to jsList(listOf("LAB_SPEC.md 4.8", "benchmark.md 12", "ASOM_MESH_DESIGN.md 6.1, 6.5 (B4, B7, B17, B18)")), "vectors" to ja(out),
                ),
            ),
            Charsets.UTF_8,
        )
        println("wrote bench/M05-body.json with ${out.size} vectors (${f.length()} bytes)")
    }

    private fun describe(o: M04Observation): String = when (o) {
        is M04Observation.Ok -> "ok"
        is M04Observation.Reject -> "reject ${o.code}"
    }

    private fun vector(v: V, expect: JValue): JValue = jo(
        listOf("id" to js(v.id), "origin" to js("generated"), "status" to js("normative"), "oracle" to js("self"), "description" to js(v.description), "input" to v.input) + v.extra + listOf("expect" to expect),
    )
}
