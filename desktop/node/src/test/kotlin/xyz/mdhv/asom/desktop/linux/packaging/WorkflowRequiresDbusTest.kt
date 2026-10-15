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

    @Test
    fun `the JVM jobs and the systemd VM job set ASOM_REQUIRE_DBUS so the private-bus tests cannot skip`() {
        val jobs = listOf("desktop-jvm", "desktop-jvm-arm", "systemd-vm")
        var seen = 0
        for (job in jobs) {
            val body = jobBody(job)
            assertTrue(Regex("^\\s+ASOM_REQUIRE_DBUS:\\s*\"1\"\\s*$", RegexOption.MULTILINE).containsMatchIn(body), "$job does not set ASOM_REQUIRE_DBUS: \"1\"")
            seen++
        }
        assertEquals(3, seen, "non-vacuity: three jobs were inspected")
    }

    @Test
    fun `a job that does not run the private-bus tests is not required to set it`() {
        assertTrue(!jobBody("desktop-isolation-with-sdk").contains("ASOM_REQUIRE_DBUS"), "control: the SDK isolation job runs no desktop test")
    }
}
