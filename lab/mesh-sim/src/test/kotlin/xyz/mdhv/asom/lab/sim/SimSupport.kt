package xyz.mdhv.asom.lab.sim

import java.io.File
import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.lab.ledger.laws.LawResult

class SimRun(val sim: Simulation, val res: SimResult) {
    val sc: Scenario get() = sim.sc
}

/** Loads and runs scenarios. Main scenarios live in `lab/mesh-sim/scenarios/`; test-only ones (one per fault kind, the RL18/RL19 worlds) in the test resources. */
object Sim {
    val root: File = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot is not set"))
    val catalogue: Catalogue by lazy { CatalogueParser.parse(File(root, "fixtures/catalogue.v1.json").readText()) }

    fun mainBytes(name: String): ByteArray = File(root, "lab/mesh-sim/scenarios/$name.json").readBytes()

    fun testBytes(name: String): ByteArray = (Sim::class.java.getResourceAsStream("/scenarios/$name.json") ?: error("no test scenario $name")).readBytes()

    fun make(bytes: ByteArray, seed: Long?, variant: Variant = Variant.B3): Simulation = Simulation(ScenarioLoader.parse(bytes, seed), catalogue, variant)

    fun run(bytes: ByteArray, seed: Long? = null, variant: Variant = Variant.B3, setup: (Simulation) -> Unit = {}): SimRun {
        val sim = make(bytes, seed, variant)
        setup(sim)
        return SimRun(sim, sim.run())
    }

    fun runMain(name: String, seed: Long? = null, variant: Variant = Variant.B3): SimRun = run(mainBytes(name), seed, variant)

    fun runTest(name: String, seed: Long? = null, variant: Variant = Variant.B3, setup: (Simulation) -> Unit = {}): SimRun = run(testBytes(name), seed, variant, setup)
}

/** Sums the case counts of a law across runs (non-vacuity) and collects every violation. */
class Tally {
    val cases = java.util.TreeMap<String, Int>()
    val violations = ArrayList<String>()

    fun add(law: String, n: Int, v: List<String> = emptyList(), context: String = "") {
        cases.merge(law, n, Int::plus)
        v.forEach { violations += "$law${if (context.isEmpty()) "" else " [$context]"}: $it" }
    }

    fun add(r: LawResult, context: String = "") = add(r.law, r.cases, r.violations, context)

    fun print(title: String) {
        println("== $title (SIMULATED — NOT DEVICE EVIDENCE)")
        cases.forEach { (law, n) -> println("$law iterations: $n violations: ${violations.count { it.startsWith("$law ") || it.startsWith("$law:") }}") }
    }
}
