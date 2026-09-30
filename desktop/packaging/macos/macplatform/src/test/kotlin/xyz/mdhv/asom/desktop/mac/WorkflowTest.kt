package xyz.mdhv.asom.desktop.mac

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The workflow file is part of the deliverable and could not be run here (no macOS, no GitHub Actions), so its shape is pinned by
 * this test as well as by actionlint. It checks text, not YAML semantics: `actionlint` and a YAML parse run in the gates.
 */
class WorkflowTest {
    private val repo = repoRoot()
    private val live = File(repo, ".github/workflows/desktop-macos.yml")
    private val canonical = File(repo, "desktop/packaging/macos/ci/desktop-macos.yml")

    private fun text() = live.readText(Charsets.UTF_8).replace("\r\n", "\n")

    @Test
    fun `the canonical copy under ci equals the workflow that runs`() {
        assertTrue(live.isFile && canonical.isFile, "both files must exist")
        assertEquals(text(), canonical.readText(Charsets.UTF_8).replace("\r\n", "\n"))
    }

    @Test
    fun `the macplatform job has the plan's commands in the plan's order on macos-latest`() {
        val t = text()
        val job = t.substringAfter("\n  macplatform:\n")
        assertTrue("runs-on: macos-latest" in job, "the macplatform job runs on macos-latest")
        val planned = listOf(
            "swift test --package-path desktop/packaging/macos/helper",
            "swift build -c release --package-path desktop/packaging/macos/helper",
            "./gradlew -p desktop :packaging:macos:macplatform:test --stacktrace",
            "desktop/packaging/macos/scripts/demo-keychain-cli-weakness.sh",
        )
        var from = 0
        for (cmd in planned) {
            val at = job.indexOf("run: $cmd", from)
            assertTrue(at >= from, "missing or out of order in the macplatform job: $cmd")
            from = at + cmd.length
        }
        assertTrue("java-version: \"21\"" in job, "the macOS job uses Temurin 21 (PLATFORM_PLAN section 5)")
        assertTrue("check_it_results.py --expect mac" in job)
        assertTrue("asom-mac-helper serve" in job && "\"op\":\"hello\",\"v\":1" in job, "the MC2 gate line is run through the real helper")
    }

    @Test
    fun `the other jobs exist and the isolation, Linux and lane-diff checks are wired`() {
        val t = text()
        for (needle in listOf(
            "  root-unchanged:", "  helper-protocol-linux:", "  macplatform-linux:", "  lane-diff:", "  macplatform:",
            "python3 desktop/tools/isolation.py --selftest", "check_it_results.py --expect skipped", "check-protocol-lanes.sh --selftest",
            "check-protocol-lanes.sh --no-run", "python3 desktop/tools/check_law.py", "jdk: [\"17\", \"21\"]", "fetch-depth: 0",
            "swift:6.1-noble@sha256:", "ASOM_VECTOR_LINES_OUT",
        )) assertTrue(needle in t, "missing: $needle")
    }

    @Test
    fun `every third-party action is pinned by a full commit id, nothing runs on pull_request_target, permissions are read-only, no secrets`() {
        val t = text()
        val uses = Regex("^\\s*-?\\s*uses:\\s*(\\S+)", RegexOption.MULTILINE).findAll(t).map { it.groupValues[1] }.toList()
        assertTrue(uses.size >= 14, "found only ${uses.size} uses: lines")
        for (u in uses) assertTrue(Regex("^[\\w.-]+/[\\w./-]+@[0-9a-f]{40}$").matches(u), "not pinned by commit id: $u")
        assertFalse("pull_request_target" in t)
        assertTrue("permissions:\n  contents: read" in t)
        assertFalse(Regex("secrets\\.", RegexOption.IGNORE_CASE).containsMatchIn(t), "no secrets in this workflow (nothing is signed or released)")
        assertFalse("environment:" in t, "no signing environment exists yet")
    }

    @Test
    fun `nothing signs, notarises, releases or publishes, and the MC3 to MC7 jobs do not exist yet`() {
        val t = text().lines().filter { !it.trim().startsWith("#") }.joinToString("\n").lowercase()
        for (banned in listOf("codesign", "notarytool", "stapler", "productbuild", "pkgbuild", "hdiutil", "gh release", "softprops", "package-and-smoke", "sign-notarize", "macos27-probes", "build-llama-jni", "smoke-app", "smoke-two-node", "brew install", "xcode-27")) {
            assertFalse(banned in t, "the workflow must not contain $banned (MC3 and later are not built)")
        }
    }

    @Test
    fun `the workflow says what it could not run and labels its evidence`() {
        val t = text()
        for (needle in listOf("CI (hosted VM) evidence", "NOT device evidence", "NEEDS-DEVICE-VALIDATION", "UNVERIFIED", "NEVER been compiled")) assertTrue(needle in t, "missing label: $needle")
    }
}
