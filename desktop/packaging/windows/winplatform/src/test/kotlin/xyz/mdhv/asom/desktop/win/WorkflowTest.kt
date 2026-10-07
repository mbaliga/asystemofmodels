package xyz.mdhv.asom.desktop.win

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The workflow file is part of the deliverable, so its shape is pinned here as well as by actionlint (a CI run in the
 * container that wrote it was not possible). This checks text, not YAML semantics: `actionlint` and a YAML parse run in the gates.
 */
class WorkflowTest {
    private val repo = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot not set"))
    private val live = File(repo, ".github/workflows/desktop-windows.yml")
    private val canonical = File(repo, "desktop/packaging/windows/ci/desktop-windows.yml")

    /** A Windows checkout may convert line endings; the content is what is compared. */
    private fun text() = live.readText(Charsets.UTF_8).replace("\r\n", "\n")

    @Test
    fun `the canonical copy under ci equals the workflow that runs`() {
        assertTrue(live.isFile && canonical.isFile, "both files must exist")
        assertEquals(live.readText(Charsets.UTF_8).replace("\r\n", "\n"), canonical.readText(Charsets.UTF_8).replace("\r\n", "\n"))
    }

    @Test
    fun `the workflow has the W0 and W1 W2 jobs on the runners the plan names`() {
        val t = text()
        for (needle in listOf(
            "  lab-windows:", "  winplatform:", "  winplatform-linux:", "  lab-linux-reference:", "  root-unchanged:",
            "runs-on: windows-2025", "windows-11-arm", "jdk: [\"17\", \"21\"]",
            "./gradlew.bat -p lab labTest --stacktrace", "./gradlew.bat -p desktop :packaging:windows:winplatform:test --stacktrace",
            "check_eol.py", "check_it_results.py --expect windows", "check_it_results.py --expect skipped",
            "lab_counts.py --compare", "-Dorg.gradle.jvmargs=-Xmx3g", "--no-build-cache --rerun-tasks",
        )) assertTrue(needle in t, "missing: $needle")
    }

    @Test
    fun `every third-party action is pinned by a full commit id, nothing runs on pull_request_target, and permissions are read-only`() {
        val t = text()
        val uses = Regex("^\\s*-?\\s*uses:\\s*(\\S+)", RegexOption.MULTILINE).findAll(t).map { it.groupValues[1] }.toList()
        assertTrue(uses.size >= 15, "found only ${uses.size} uses: lines")
        for (u in uses) assertTrue(Regex("^[\\w.-]+/[\\w./-]+@[0-9a-f]{40}$").matches(u), "not pinned by commit id: $u")
        assertFalse("pull_request_target" in t)
        assertTrue("permissions:\n  contents: read" in t)
        assertFalse(Regex("secrets\\.", RegexOption.IGNORE_CASE).containsMatchIn(t), "no secrets in this workflow (nothing is signed or released)")
    }

    @Test
    fun `nothing in the workflow signs, releases or publishes, and W3 to W7 jobs do not exist yet`() {
        val t = text().lines().filter { !it.trim().startsWith("#") }.joinToString("\n").lowercase()
        for (banned in listOf("signtool", "wix", "msiexec", "winget", "gh release", "softprops", "package-and-smoke", "build-llama-jni", "smoke-install")) {
            assertFalse(banned in t, "the workflow must not contain $banned (W3 and later are not built)")
        }
    }
}
