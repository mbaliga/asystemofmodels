package xyz.mdhv.asom.lab.ledger

import java.io.File
import xyz.mdhv.asom.lab.ledger.laws.Ev
import xyz.mdhv.asom.lab.ledger.laws.LawResult
import xyz.mdhv.asom.lab.ledger.laws.LedgerLaws
import xyz.mdhv.asom.lab.ledger.sim.Scenarios
import xyz.mdhv.asom.lab.ledger.sim.SimConfig
import xyz.mdhv.asom.lab.ledger.sim.SimWorld

fun repoRoot(): File = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot is not set (run through Gradle: ./gradlew -p lab ...)")).canonicalFile

fun runWorld(seed: Long, cfg: SimConfig = SimConfig(seed = seed)): SimWorld {
    val w = SimWorld(cfg)
    w.run(Scenarios.random(w, seed))
    return w
}

/** The laws that are pure functions of one trace, by name. L-L6 and L-L11 need files and crashes and have their own tests. */
fun traceLaws(t: List<Ev>): List<LawResult> = listOf(
    LedgerLaws.l1(t), LedgerLaws.l2(t), LedgerLaws.l3(t), LedgerLaws.l4(t), LedgerLaws.l5(t), LedgerLaws.l5b(t), LedgerLaws.l7(t), LedgerLaws.l8(t),
    LedgerLaws.l9(t), LedgerLaws.l10(t), LedgerLaws.l12(t), LedgerLaws.l13(t), LedgerLaws.l14(t), LedgerLaws.l15(t).result, LedgerLaws.l16(t).result,
)

class LawTally {
    private val byLaw = java.util.TreeMap<String, LawResult>(compareBy { it.removePrefix("L-L").filter { c -> c.isDigit() }.toInt() * 10 + (if (it.endsWith("b")) 1 else 0) })
    val perType = LinkedHashMap<String, Int>()
    var measuredSessions = 0
    var estimatedSessions = 0
    var excludedSessions = 0

    fun add(t: List<Ev>) {
        traceLaws(t).forEach { r -> byLaw[r.law] = byLaw[r.law]?.plus(r) ?: r }
        val l15 = LedgerLaws.l15(t)
        measuredSessions += l15.measuredSessions
        estimatedSessions += l15.estimatedSessions
        excludedSessions += l15.excludedSessions
        LedgerLaws.l16(t).perType.forEach { (k, v) -> perType[k] = (perType[k] ?: 0) + v }
    }

    fun result(law: String): LawResult = byLaw[law] ?: error("no result for $law")
    fun all(): List<LawResult> = byLaw.values.toList()
}
