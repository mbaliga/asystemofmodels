package xyz.mdhv.asom.lab.bench

import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.Jcs

/** benchmark.md R4-B-07: the consent token carries the ticks (`optIns`, only offered names; `rerunHeatToday`) and the executor reads them only from the token. */
class R4ConsentTicksTest {
    private val standard = RunPlans.STANDARD

    private fun offered() = ConsentSheet.forPlan(standard, 4_300_000_000L, listOf("T1", "T2"), true, false)

    private fun runToday() = ConsentSheet.forPlan(standard, 0L, emptyList(), false, true)

    @Test
    fun theTokenCarriesWhatWasTickedAndOnlyOfferedNames() {
        val c = Counter("r4 consent ticks")
        val s = offered()
        val bare = s.confirm(s.textSha256, 1_000L)
        assertEquals(emptySet(), bare.optIns)
        assertFalse(bare.rerunHeatToday)
        assertTrue(bare.heatTestPermitted, "the sheet had no rerun tick, so nothing is withheld")
        c.hit()
        val ticked = s.confirm(s.textSha256, 1_000L, ConsentTicks(setOf("T3")))
        assertEquals(setOf("T3"), ticked.optIns)
        c.hit()
        for (bad in listOf("T1", "T4", "t3", "", "T3 ")) {
            assertFailsWith<ConsentError>(bad) { s.confirm(s.textSha256, 1_000L, ConsentTicks(setOf(bad))) }
            c.hit()
        }
        val notOffered = ConsentSheet.forPlan(standard, 4_300_000_000L, listOf("T1", "T2"), false, false)
        assertFailsWith<ConsentError>("T3 was not on the sheet") { notOffered.confirm(notOffered.textSha256, 1_000L, ConsentTicks(setOf("T3"))) }
        c.hit()
        assertFailsWith<ConsentError>("no rerun tick on this sheet") { s.confirm(s.textSha256, 1_000L, ConsentTicks(rerunHeatToday = true)) }
        c.hit()
        c.requireNonVacuous(9)
    }

    @Test
    fun aSheetThatNeedsTheRerunTickAndDoesNotGetItMintsATokenWhoseHeatTestIsNotPermitted() {
        val s = runToday()
        val unticked = s.confirm(s.textSha256, 1_000L, ConsentTicks())
        assertFalse(unticked.heatTestPermitted)
        assertFalse(unticked.rerunHeatToday)
        val noReport = s.confirm(s.textSha256, 1_000L)
        assertFalse(noReport.heatTestPermitted, "a caller that reports no ticks cannot start a heat phase on this sheet")
        val ticked = s.confirm(s.textSha256, 1_000L, ConsentTicks(rerunHeatToday = true))
        assertTrue(ticked.heatTestPermitted)
        assertTrue(ticked.rerunHeatToday)
    }

    @Test
    fun theExecutorReadsTheOptInTiersOnlyFromTheToken() {
        val cfg = Scenarios.preset("phone", "standard").copy(heat = Scenarios.HEAT_PROFILES.getValue("flat"))
        val s = offered()
        fun run(token: ConsentToken): BenchDoc {
            val host = FakeHost(cfg)
            val out = BenchSession(host, standard, BenchSets.Q1, token).run()
            return (out as RunOutcome.Completed).doc
        }
        val without = run(s.confirm(s.textSha256, cfg.epochStartMs))
        assertEquals(emptyList(), without.run.optInTiers)
        assertTrue(without.tiers.none { it.tier == "T3" })
        val with = run(s.confirm(s.textSha256, cfg.epochStartMs, ConsentTicks(setOf("T3"))))
        assertEquals(listOf("T3"), with.run.optInTiers)
        assertTrue(with.tiers.any { it.tier == "T3" })
    }

    @Test
    fun aTokenWhoseHeatTestIsNotPermittedRunsTheTiersAndSkipsTheHeatTest() {
        val cfg = Scenarios.preset("phone", "standard")
        val s = runToday()
        val host = FakeHost(cfg)
        val session = BenchSession(host, standard, BenchSets.Q1, s.confirm(s.textSha256, cfg.epochStartMs, ConsentTicks()))
        val doc = (session.run() as RunOutcome.Completed).doc
        assertTrue(doc.tiers.isNotEmpty())
        assertEquals(null, doc.sustain)
        assertTrue(session.trace.any { it.startsWith("SUSTAIN skipped (heat test not permitted") }, session.trace.takeLast(5).toString())
        assertTrue(host.leaks().isEmpty())
        val host2 = FakeHost(cfg)
        val s2 = runToday()
        val ticked = BenchSession(host2, standard, BenchSets.Q1, s2.confirm(s2.textSha256, cfg.epochStartMs, ConsentTicks(rerunHeatToday = true)))
        assertNotNull((ticked.run() as RunOutcome.Completed).doc.sustain, "with the tick the heat test runs")
    }

    @Test
    fun aHashWithTheRightPrefixAndExtraBytesOrTooFewBytesMintsNothing() {
        val s = offered()
        val c = Counter("r4 hash length")
        val h = s.textSha256
        for (extra in listOf(1, 31, 32, 256, 288, 512)) {
            assertFailsWith<ConsentError>("extra=$extra") { s.confirm(h + ByteArray(extra), 0L) }
            c.hit()
        }
        for (n in listOf(0, 1, 31)) {
            assertFailsWith<ConsentError>("prefix=$n") { s.confirm(h.copyOf(n), 0L) }
            c.hit()
        }
        c.requireNonVacuous(9)
    }

    @Test
    fun aTokenIsSpentOnceWhoeverHoldsACopyEvenUnderRacingThreads() {
        val s = ConsentSheet.forPlan(RunPlans.QUICK, 0L, emptyList(), false, false)
        val token = s.confirm(s.textSha256, 1_000L)
        val winners = AtomicInteger()
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..64).map {
                pool.submit {
                    gate.await()
                    try {
                        token.consume("quick", 2_000L)
                        winners.incrementAndGet()
                    } catch (_: ConsentError) {
                    }
                }
            }
            gate.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, winners.get())
    }

    @Test
    fun aRefusedSpendLeavesTheTokenUnspent() {
        val s = ConsentSheet.forPlan(RunPlans.QUICK, 0L, emptyList(), false, false)
        val token = s.confirm(s.textSha256, 1_000L)
        assertFailsWith<ConsentError> { token.consume("standard", 2_000L) }
        assertFailsWith<ConsentError> { token.consume("quick", 1_000L + ConsentSheet.TOKEN_TTL_MS) }
        token.consume("quick", 2_000L)
        assertFailsWith<ConsentError> { token.consume("quick", 2_000L) }
    }
}

/** benchmark.md R4-B-02: a platform with no row in 11.3 is refused before the run starts and an executor treats a refusal as a hard abort. */
class R4CeilingRowsTest {
    private val cool = ThermalReading(true, 0, 300, 300)
    private val live = Presence(foreground = true, screenOn = true, batterySaver = false, lowPowerMode = false)

    @Test
    fun windowsAndUbuntuTouchHaveNoRowAndAreRefusedNotMappedToLinux() {
        val c = Counter("r4 rows")
        for (platform in listOf("windows", "ubuntu-touch", "", "IOS", "iPadOS", "Linux")) {
            val e = assertFailsWith<NoCeilingRow>(platform) { Ceilings.evaluate(platform, "desktop", cool, PowerReading("ac", 700, true), live, 0L) }
            assertEquals(platform, e.platform)
            assertFalse(Ceilings.hasRow(platform))
            c.hit()
        }
        for (platform in listOf("android", "ios", "ipados", "macos", "linux")) {
            assertTrue(Ceilings.hasRow(platform), platform)
            Ceilings.evaluate(platform, "phone", cool, PowerReading("ac", 700, true), live, 0L)
            c.hit()
        }
        c.requireNonVacuous(11)
    }

    private fun scenario(platform: String) = BJ.parse("""{"preset":"desktop","plan":"standard","platform":"$platform"}""")

    @Test
    fun preflightRefusesAPlatformWithNoRowBeforeAnythingRuns() {
        val c = Counter("r4 preflight rows")
        for (platform in listOf("windows", "ubuntu-touch")) {
            val r = Scenarios.run(scenario(platform))
            assertEquals("refused NO_CEILINGS", r.outcome, platform)
            assertTrue(r.events.none { it.startsWith("LOAD") }, "nothing may be loaded: ${r.events}")
            assertEquals(null, r.doc)
            assertTrue(r.leaks.isEmpty())
            c.hit()
        }
        val ok = Scenarios.run(scenario("linux"))
        assertEquals("completed", ok.outcome)
        c.hit()
        c.requireNonVacuous(3)
    }

    @Test
    fun anExecutorThatMeetsARefusalMidRunTreatsItAsAHardAbort() {
        val cfg = Scenarios.preset("desktop", "standard")
        val windows = cfg.copy(device = cfg.device.copy(platform = "windows"))
        val host = FakeHost(windows)
        val trace = mutableListOf<String>()
        val gov = Governor(host, RunPlans.STANDARD, trace) { 0L }
        val e = assertFailsWith<GovSignal.Abort> { gov.check(Phase.TIER) }
        assertEquals("PROBE_LOST", e.reason)
    }
}

/** benchmark.md R4-B-06: the quick and ci plans are JSON in 5.2, and the JCS bytes of each block are the bytes the plan data hashes. */
class R4PlansInSpecTest {
    private fun blocks(): List<String> {
        val md = mdText("benchmark.md")
        val sec = md.substring(md.indexOf("### 5.2 Run-plan format"), md.indexOf("### 5.3"))
        return Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(sec).map { it.groupValues[1] }.toList()
    }

    @Test
    fun section52HoldsTheStandardQuickAndCiPlansByteForByteAfterJcs() {
        val byId = blocks().associateBy { b -> ((BJ.parse(b) as JObject)["plan"] as JString).value }
        assertEquals(setOf("standard", "quick", "ci"), byId.keys)
        for (plan in RunPlans.all) {
            val bytes = Jcs.serialize(BJ.parse(byId.getValue(plan.plan)))
            assertContentEquals(plan.jcsBytes(), bytes, "${plan.plan}: 5.2 block differs from the plan data")
            assertEquals(plan.sha256B64u(), Base64Strict.encodeUrlNoPad(MessageDigest.getInstance("SHA-256").digest(bytes)))
        }
        val md = mdText("benchmark.md")
        for ((id, sha) in listOf("quick" to "39Jq5InzqMUCbHKyI4XqqvL2ddj0UCIXeFhRHaw5f-E", "ci" to "ervAvYozEUXI8dn9t0fu9sJXOC16z4LpTyt6E7ZnZXo", "standard" to "U5x4LJS7iliO0QOew1tjsNkJ14-JeCLGuJt6fIUAD9s")) {
            assertEquals(sha, RunPlans.byId(id)!!.sha256B64u(), id)
            assertTrue(md.contains(sha), "R4-B-06 names the hash of $id")
        }
    }
}
