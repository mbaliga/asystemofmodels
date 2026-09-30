package xyz.mdhv.asom.lab.sim

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.router.Tier

/** The scenario loader, determinism, the output files, the per-scenario table and the baselines B0, B1, B2, B4. Evidence label: SIMULATED — NOT DEVICE EVIDENCE. */
class SimHarnessTest {
    private fun sc01() = String(Sim.mainBytes("SC01"), Charsets.UTF_8)

    private fun parse(text: String) = ScenarioLoader.parse(text.toByteArray(Charsets.UTF_8))

    @Test
    fun theLoaderAcceptsTheFourGateScenariosAndTheLabelIsRequired() {
        for (n in listOf("SC01", "SC04", "SC06", "SC09")) assertEquals(n, ScenarioLoader.parse(Sim.mainBytes(n)).id)
        assertFailsWith<ScenarioException> { parse(sc01().replace(SIM_LABEL, "simulated")) }
        assertFailsWith<ScenarioException> { parse(sc01().replace("\"label\": \"$SIM_LABEL\",", "")) }
    }

    @Test
    fun theLoaderIsStrictAboutIntegersDuplicatesUnknownFaultsAndUnknownNodes() {
        assertFailsWith<ScenarioException>("a fraction") { parse(sc01().replace("\"arrivalsPerHour\": 15", "\"arrivalsPerHour\": 15.5")) }
        assertFailsWith<ScenarioException>("an exponent") { parse(sc01().replace("\"arrivalsPerHour\": 15", "\"arrivalsPerHour\": 1e1")) }
        assertFailsWith<ScenarioException>("a duplicate name") { parse(sc01().replace("\"seed\": 1,", "\"seed\": 1, \"seed\": 2,")) }
        val withFault = sc01().replace("\"faults\": []", "\"faults\": [{\"atMs\": 1, \"kind\": \"meteor\", \"node\": null, \"phase\": null, \"durationMs\": null, \"valuePermille\": null}]")
        assertFailsWith<ScenarioException>("an unknown fault kind") { parse(withFault) }
        assertFailsWith<ScenarioException>("a link to an unknown node") { parse(sc01().replace("\"b\": \"mac\"", "\"b\": \"nobody\"")) }
        assertFailsWith<ScenarioException>("two SELF nodes") { parse(sc01().replace("\"tier\": \"PEER\"", "\"tier\": \"SELF\"")) }
        assertFailsWith<ScenarioException>("a truth row that does not match a file") { parse(sc01().replaceFirst(Regex("\"truth\": \\{\\s*\"a1a1"), "\"truth\": {\"b2b2")) }
    }

    @Test
    fun theSeedOfAFileCanBeOverridden() {
        assertEquals(1L, ScenarioLoader.parse(Sim.mainBytes("SC01")).seed)
        assertEquals(7L, ScenarioLoader.parse(Sim.mainBytes("SC01"), 7).seed)
    }

    private fun sig(r: SimResult) = r.eventsJsonl() + r.decisionsJsonl()

    @Test
    fun aSeedGivesTheSameRunAndAnotherSeedGivesAnotherOne() {
        val a = Sim.runMain("SC04", 3).res
        val b = Sim.runMain("SC04", 3).res
        val c = Sim.runMain("SC04", 4).res
        assertEquals(sig(a), sig(b), "the same seed must reproduce events and decisions byte for byte")
        assertEquals(a.ledgerRows.mapValues { e -> e.value.size }, b.ledgerRows.mapValues { e -> e.value.size })
        assertNotEquals(sig(a), sig(c))
    }

    @Test
    fun everyOutputFileStartsWithTheLabelLine() {
        val dir = File(System.getProperty("java.io.tmpdir"), "asom-sim-test-" + System.nanoTime())
        try {
            val run = Sim.runMain("SC04", 1)
            Reports.writeOutputs(run.res, dir)
            for (name in listOf("events.jsonl", "decisions.jsonl")) {
                val lines = File(dir, name).readLines(Charsets.UTF_8)
                assertEquals("{\"label\":\"$SIM_LABEL\"}", lines.first(), name)
                assertTrue(lines.size > 10, name)
                assertTrue(lines.drop(1).none { it.contains(SIM_LABEL) && !it.contains("\"kind\"") })
            }
            val replay = Replay.run(run.sc, Sim.catalogue, File(dir, "events.jsonl").readLines(Charsets.UTF_8), File(dir, "decisions.jsonl").readLines(Charsets.UTF_8))
            assertTrue(replay.empty, replay.diff.joinToString("\n"))
            assertTrue(replay.decisions > 20)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theEventLogHoldsOnlyIntegersStringsBooleansAndNull() {
        val lines = Sim.runMain("SC06", 1).res.eventsJsonl().drop(1)
        assertTrue(lines.none { Regex("[0-9]\\.[0-9]|[0-9][eE][+-]?[0-9]").containsMatchIn(it.replace(Regex("\"[^\"]*\""), "\"\"")) }, "a non-integer number in events.jsonl")
    }

    @Test
    fun replayCatchesADecisionThatWasTamperedWith() {
        val run = Sim.runMain("SC04", 1)
        val decisions = run.res.decisionsJsonl().toMutableList()
        val i = decisions.indexOfFirst { it.contains("PEER:dell") }
        decisions[i] = decisions[i].replace("PEER:dell", "PEER:dull")
        val r = Replay.run(run.sc, Sim.catalogue, run.res.eventsJsonl(), decisions)
        assertEquals(1, r.diff.size)
    }

    @Test
    fun replayCatchesAnEventThatWasTamperedWith() {
        val run = Sim.runMain("SC04", 1)
        val events = run.res.eventsJsonl().toMutableList()
        val i = events.indexOfFirst { it.contains("\"kind\":\"claim\"") }
        assertTrue(i > 0)
        val changed = events.map { it.replace("\"decode\":20000", "\"decode\":90000") }
        val r = Replay.run(run.sc, Sim.catalogue, changed, run.res.decisionsJsonl())
        assertTrue(!r.empty, "a changed claim must change at least one replayed decision")
    }

    @Test
    fun theTableHasOneLinePerScenarioAndEveryLineCarriesTheLabel() {
        val lines = listOf("SC01", "SC04", "SC06", "SC09").map { n ->
            val run = Sim.runMain(n, 1)
            Reports.summarize(run.res, run.sc.self.id).tableLine()
        }
        lines.forEach { println(it) }
        for (l in lines) {
            assertTrue(l.endsWith(SIM_LABEL), l)
            assertTrue("decision:" in l && "reason:" in l && "ledger rows:" in l, l)
        }
        assertEquals(4, lines.map { it.substringBefore(" ") }.toSet().size)
    }

    @Test
    fun baselineB1IsLocalOnlyAndUsesNoPeerAndNoCloud() {
        val run = Sim.runMain("SC04", 1, Variant.B1)
        assertTrue(run.res.requests.isNotEmpty())
        assertTrue(run.res.requests.all { r -> r.attempts.all { it.tier == Tier.SELF } })
    }

    @Test
    fun baselineB0IsTheV1CloudOnlyPath() {
        val run = Sim.runTest("F-none", 1, Variant.B0)
        assertTrue(run.res.requests.isNotEmpty())
        assertTrue(run.res.requests.all { r -> r.attempts.all { it.tier == Tier.CLOUD } }, "B0 must use the cloud only")
        assertTrue(run.res.requests.count { it.status == 200 } > run.res.requests.size / 2)
    }

    @Test
    fun baselineB2KeepsTheSameFiltersButOrdersStatically() {
        val b3 = Sim.runMain("SC06", 1, Variant.B3)
        val b2 = Sim.runMain("SC06", 1, Variant.B2)
        assertTrue(b2.res.decisions.all { d -> d.attempts.map { it.substringBefore("|") }.let { a -> a == a.sortedBy { x -> if (x.startsWith("SELF")) 0 else if (x.startsWith("PEER")) 1 else 2 } } })
        assertNotEquals(b3.res.decisions.map { it.attempts.map { a -> a.substringBefore("|") } }, b2.res.decisions.map { it.attempts.map { a -> a.substringBefore("|") } })
    }

    @Test
    fun hindsightBaselineB4NamesATargetForEveryPlannedRequest() {
        val run = Sim.runMain("SC06", 1)
        val planned = run.res.requests.filter { it.error == null }
        assertTrue(planned.all { it.hindsight != null })
        assertTrue(planned.map { it.hindsight }.toSet().size >= 2, "a hindsight baseline that always names one node would be a constant")
    }
}
