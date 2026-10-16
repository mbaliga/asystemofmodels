package xyz.mdhv.asom.lab.sim

import java.io.File
import xyz.mdhv.asom.lab.ledger.laws.LawResult
import xyz.mdhv.asom.lab.ledger.laws.LedgerLaws
import xyz.mdhv.asom.lab.router.Tier

/** What one run says about itself. Every figure is over SIMULATED requests. */
class ScenarioSummary(
    val scenario: String,
    val seed: Long,
    val variant: Variant,
    val requests: Int,
    val served: Int,
    val decision: String,
    val reason: String,
    val ledgerRows: Int,
    val servedShare: Map<String, Long>,
    val lawViolations: Int,
) {
    fun tableLine(): String =
        "%-5s | seed %-3d | %-9s | decision: %-26s | reason: %-28s | ledger rows: %-6d | law violations: %d | %s".format(
            scenario, seed, variant.name, decision, reason, ledgerRows, lawViolations, SIM_LABEL,
        )
}

object SimLaws {
    /**
     * The ledger laws that read a simulated trace: L-L1, 2, 3, 4, 5, 5b, 7, 9, 10, 12, 13, 14 (LAB_SPEC 6.9). Each one carries its own case count. [sharedAttemptIds] is for the
     * `duplicate-attempt` fault only: L-L12 joins a lender row to a frame by `attemptId`, and a duplicate offer reuses the id of an attempt that was already served, so that join is
     * ambiguous by construction there (ERRATA); the law is skipped for that one scenario and stays on everywhere else.
     */
    fun run(r: SimResult, sharedAttemptIds: Boolean = false): List<LawResult> {
        val t = r.trace.events
        return listOfNotNull(
            LedgerLaws.l1(t), LedgerLaws.l2(t), LedgerLaws.l3(t), LedgerLaws.l4(t), LedgerLaws.l5(t), LedgerLaws.l5b(t), LedgerLaws.l7(t), LedgerLaws.l9(t), LedgerLaws.l10(t),
            if (sharedAttemptIds) null else LedgerLaws.l12(t), LedgerLaws.l13(t), LedgerLaws.l14(t),
        )
    }

    fun violations(r: SimResult): List<String> = run(r).flatMap { l -> l.violations.map { "${l.law}: $it" } }
}

object Reports {
    fun ledgerRowCount(r: SimResult): Int = r.ledgerRows.values.sumOf { it.size }

    /** Successful requests only, by the target that served them: `nodeId`, `cloud:<provider>` or the requester itself. */
    fun servedCounts(r: SimResult): Map<String, Int> = r.requests.filter { it.status == 200 }.groupingBy { it.servedBy ?: "?" }.eachCount().toSortedMap()

    fun summarize(r: SimResult, selfId: String): ScenarioSummary {
        val ok = r.requests.filter { it.status == 200 }
        val firsts = r.requests.mapNotNull { it.firstTarget }.groupingBy { it }.eachCount()
        val top = firsts.entries.sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()
        val decision = if (top == null) "none" else "${top.key} first (${top.value * 1000 / r.requests.count { it.firstTarget != null }} permille)"
        val reasons = r.requests.mapNotNull { it.firstReason }.groupingBy { it }.eachCount()
        val reason = reasons.entries.sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()?.key ?: "none"
        val share = servedCounts(r).mapValues { (_, n) -> if (ok.isEmpty()) 0L else n * 1000L / ok.size }
        return ScenarioSummary(r.scenario, r.seed, r.variant, r.requests.size, ok.size, decision, reason, ledgerRowCount(r), share, SimLaws.violations(r).size)
    }

    fun writeOutputs(r: SimResult, dir: File) {
        dir.mkdirs()
        File(dir, "events.jsonl").writeText(r.eventsJsonl().joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        File(dir, "decisions.jsonl").writeText(r.decisionsJsonl().joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    fun findRepoRoot(start: File): File {
        var d: File? = start.absoluteFile
        while (d != null) {
            if (File(d, "fixtures/catalogue.v1.json").isFile) return d
            d = d.parentFile
        }
        throw IllegalStateException("no fixtures/catalogue.v1.json above $start")
    }

    fun selfShare(r: SimResult, selfId: String): Long {
        val ok = r.requests.filter { it.status == 200 }
        return if (ok.isEmpty()) 0 else ok.count { it.servedTier == Tier.SELF } * 1000L / ok.size
    }
}
