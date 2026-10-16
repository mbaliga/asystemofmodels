package xyz.mdhv.asom.desktop.linux.packaging

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.linux.LawCounter
import xyz.mdhv.asom.desktop.linux.Report

/**
 * `journal-hygiene.sh` run against a STUBBED journal, logger and CLI (evidence label: LAB, and explicitly NOT a journal
 * result: the real one is CI-ONLY on a hosted VM). What is proven here is the script's own logic: it prints PASS only when
 * the canary and the ledger-row markers are absent AND both positive controls hold, and it FAILS or ERRORs otherwise.
 */
class JournalHygieneScriptTest {
    private class Stubs(
        val leakCanaryToUnit: Boolean = false,
        val leakLedgerRow: Boolean = false,
        val loggerWorks: Boolean = true,
        val unitHasStartLines: Boolean = true,
    ) {
        val dir: Path = Files.createTempDirectory("asom-hygiene-")
        val all: Path = dir.resolve("journal-all.txt")
        val unit: Path = dir.resolve("journal-unit.txt")
        val cliLog: Path = dir.resolve("cli.log")

        fun script(name: String, body: String): Path {
            val p = dir.resolve(name)
            Files.writeString(p, "#!/bin/sh\n$body\n")
            p.toFile().setExecutable(true)
            return p
        }

        fun env(): Map<String, String> {
            Files.writeString(all, "")
            Files.writeString(unit, if (unitHasStartLines) "Started asom.service - asom node.\n" else "")
            val journalctl = script("journalctl", "case \"\$*\" in *\" -u \"*) cat '$unit' ;; *) cat '$all' ;; esac")
            val logger = script("logger", if (loggerWorks) "for a; do last=\"\$a\"; done; echo \"\$last\" >> '$all'" else "exit 0")
            val leak = buildString {
                if (leakCanaryToUnit) append("echo \"node: request model=\$*\" >> '$unit'; ")
                if (leakLedgerRow) append("echo '{\"callerPkg\":\"local-uid:alice\",\"requestedModel\":\"x\"}' >> '$unit'; ")
            }
            val cli = script("asom", "echo \"\$*\" >> '$cliLog'; $leak exit 3")
            return mapOf("JOURNALCTL" to journalctl.toString(), "LOGGER" to logger.toString(), "ASOM_CLI" to cli.toString(), "SLEEP" to "true")
        }
    }

    private fun run(s: Stubs) = Pkg.run(listOf("bash", Pkg.journalHygiene.toString()), s.env())

    @Test
    fun `the script's decision table`() {
        val laws = LawCounter(listOf("clean-passes", "canary-leak-fails", "ledger-row-fails", "control-missing-errors", "empty-unit-journal-errors", "requests-were-sent"))

        val clean = Stubs()
        val ok = run(clean)
        assertEquals(0, ok.exit, ok.out)
        assertEquals("PASS: 0 token matches, 0 ledger rows", ok.out.trim().lines().last())
        laws.hit("clean-passes")
        val sent = Files.readAllLines(clean.cliLog)
        assertTrue(sent.any { it.startsWith("chat --model ASOMCANARY-") } && sent.any { it.startsWith("status") }, "the script must actually send the canary through the CLI: $sent")
        laws.hit("requests-were-sent")

        val leaked = run(Stubs(leakCanaryToUnit = true))
        assertEquals(1, leaked.exit, leaked.out)
        assertTrue(leaked.out.trim().lines().last().startsWith("FAIL: 2 token matches, 0 ledger rows"), leaked.out)
        laws.hit("canary-leak-fails")

        val row = run(Stubs(leakLedgerRow = true))
        assertEquals(1, row.exit, row.out)
        assertTrue(row.out.trim().lines().last().startsWith("FAIL: 0 token matches, "), row.out)
        assertTrue(!row.out.contains("FAIL: 0 token matches, 0 ledger rows"), row.out)
        laws.hit("ledger-row-fails")

        val noControl = run(Stubs(loggerWorks = false))
        assertEquals(2, noControl.exit, noControl.out)
        assertTrue(noControl.out.contains("positive control failed"), noControl.out)
        assertTrue(!noControl.out.contains("PASS"), "an untrustworthy check must never print PASS")
        laws.hit("control-missing-errors")

        val emptyUnit = run(Stubs(unitHasStartLines = false))
        assertEquals(2, emptyUnit.exit, emptyUnit.out)
        assertTrue(emptyUnit.out.contains("is empty"), emptyUnit.out)
        laws.hit("empty-unit-journal-errors")

        laws.assertAllExercised("JournalHygieneScript")
        Report.line("journal-hygiene.sh against a STUBBED journal: clean=PASS, planted canary=FAIL, planted ledger row=FAIL, dead control=ERROR, empty unit journal=ERROR (LAB; the real journal is CI-ONLY)")
    }

    @Test
    fun `a canary given on the command line is the one that is sent and searched`() {
        val s = Stubs(leakCanaryToUnit = true)
        val env = s.env()
        val r = Pkg.run(listOf("bash", Pkg.journalHygiene.toString(), "--canary", "MYTOKEN-123"), env)
        assertEquals(1, r.exit, r.out)
        assertTrue(Files.readString(s.cliLog).contains("MYTOKEN-123"))
        assertTrue(Files.readString(s.unit).contains("MYTOKEN-123"))
    }

    @Test
    fun `an unknown argument is refused`() {
        val r = Pkg.run(listOf("bash", Pkg.journalHygiene.toString(), "--frobnicate"))
        assertEquals(2, r.exit)
    }

    @Test
    fun `both scripts parse under bash -n and refuse to run without their preconditions`() {
        for (f in listOf(Pkg.journalHygiene, Pkg.systemdVm)) assertEquals(0, Pkg.run(listOf("bash", "-n", f.toString())).exit, "$f has a syntax error")
        val r = Pkg.run(listOf("bash", Pkg.systemdVm.toString()))
        assertEquals(2, r.exit, r.out)
        assertTrue(r.out.contains("--app"), r.out)
    }
}
