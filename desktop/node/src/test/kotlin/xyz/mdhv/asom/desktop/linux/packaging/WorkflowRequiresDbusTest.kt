package xyz.mdhv.asom.desktop.linux.packaging

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PLATFORM_PLAN R4-P-13: every desktop-linux job that runs the private-bus tests sets `ASOM_REQUIRE_DBUS: "1"`, so a missing
 * `dbus-daemon` fails the job instead of being reported as skipped. This reads the workflow text; it does not run GitHub Actions.
 */
class WorkflowRequiresDbusTest {
    private val workflow = Pkg.repoRoot.resolve(".github/workflows/desktop-linux.yml")

    private fun jobBody(name: String): String {
        val lines = Files.readAllLines(workflow)
        val start = lines.indexOfFirst { it == "  $name:" }
        assertTrue(start >= 0, "job $name not found")
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { Regex("^  [A-Za-z0-9_-]+:\\s*$").matches(it) }
        return (if (end < 0) rest else rest.take(end)).joinToString("\n")
    }

    /** True when the job's own `    env:` block (four-space key, six-space entries) sets the variable to "1"; a step's env does not count. */
    private fun jobEnvRequiresDbus(body: String): Boolean {
        val lines = body.lines()
        val start = lines.indexOf("    env:")
        if (start < 0) return false
        val entries = lines.drop(start + 1).takeWhile { it.isBlank() || it.startsWith("      ") }
        return entries.any { it == "      ASOM_REQUIRE_DBUS: \"1\"" }
    }

    @Test
    fun `the JVM jobs and the systemd VM job set ASOM_REQUIRE_DBUS so the private-bus tests cannot skip`() {
        val jobs = listOf("desktop-jvm", "desktop-jvm-arm", "systemd-vm")
        var seen = 0
        for (job in jobs) {
            assertTrue(jobEnvRequiresDbus(jobBody(job)), "$job does not set ASOM_REQUIRE_DBUS: \"1\" in its job-level env")
            seen++
        }
        assertEquals(3, seen, "non-vacuity: three jobs were inspected")
    }

    @Test
    fun `the check accepts only the job-level env, not a step env, a comment, or another value (HC-R5)`() {
        val ok = "    env:\n      ANDROID_HOME: \"\"\n      ASOM_REQUIRE_DBUS: \"1\"\n    steps:\n      - run: x"
        assertTrue(jobEnvRequiresDbus(ok), "control: the job-level line is accepted")
        val moved = "    env:\n      ANDROID_HOME: \"\"\n    steps:\n      - name: Isolation checks\n        env:\n          ASOM_REQUIRE_DBUS: \"1\"\n        run: x"
        assertTrue(!jobEnvRequiresDbus(moved), "moved into a step env (block form, deeper indent)")
        val stepSixSpace = "    env:\n      ANDROID_HOME: \"\"\n    steps:\n      - name: a\n      ASOM_REQUIRE_DBUS: \"1\""
        assertTrue(!jobEnvRequiresDbus(stepSixSpace), "a six-space line after the env block has ended is not in the env")
        val commented = "    env:\n      # ASOM_REQUIRE_DBUS: \"1\"\n    steps:\n      - run: x"
        assertTrue(!jobEnvRequiresDbus(commented), "commented out")
        val zero = "    env:\n      ASOM_REQUIRE_DBUS: \"0\"\n    steps:\n      - run: x"
        assertTrue(!jobEnvRequiresDbus(zero), "another value")
        assertTrue(!jobEnvRequiresDbus("    steps:\n      - run: x"), "no env block at all")
    }

    @Test
    fun `a job that does not run the private-bus tests is not required to set it`() {
        assertTrue(!jobBody("desktop-isolation-with-sdk").contains("ASOM_REQUIRE_DBUS"), "control: the SDK isolation job runs no desktop test")
    }
}
