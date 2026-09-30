package xyz.mdhv.asom.desktop.mac

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Static rules over this module's sources, checked by the build (the desktop-wide rules of `desktop/tools/check_law.py` apply too
 * and also run in CI). Every rule counts what it scanned and fails when the count is implausibly small (non-vacuity).
 */
class MacSourceHygieneTest {
    private val moduleDir = moduleDir()
    private val repo = repoRoot()
    private val main: List<File> = File(moduleDir, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private val tests: List<File> = File(moduleDir, "src/test/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private val swift: List<File> = File(moduleDir, "../helper/Sources").walkTopDown().filter { it.isFile && it.extension == "swift" }.toList()

    private fun rel(f: File) = f.relativeTo(moduleDir).path.replace(File.separatorChar, '/')

    private fun offenders(rule: String, files: List<File> = main, bad: (File, String) -> Boolean): List<String> =
        files.filter { bad(it, it.readText(Charsets.UTF_8)) }.map { "$rule: ${rel(it)}" }

    @Test
    fun `sources were found`() {
        assertTrue(main.size >= 22, "found only ${main.size} main sources; every rule below would be vacuous")
        assertTrue(tests.size >= 12, "found only ${tests.size} test sources")
        assertTrue(swift.size >= 12, "found only ${swift.size} Swift sources")
    }

    @Test
    fun `nothing prints outside the injected streams, nothing listens, nothing binds a wildcard, no Android, no JNA`() {
        val bare = Regex("(?<![\\w.])(?<!fun )(println|print)\\(")
        val v = offenders("bare print or System.out/err") { _, t -> bare.containsMatchIn(t) || Regex("System\\.(out|err)").containsMatchIn(t) } +
            offenders("listener API") { _, t -> Regex("\\b(ServerSocket|ServerSocketChannel|DatagramSocket|embeddedServer|AsomServer\\()").containsMatchIn(t) } +
            offenders("wildcard address") { _, t -> "0.0.0.0" in t || Regex("\"::\"").containsMatchIn(t) } +
            offenders("android import") { _, t -> Regex("^\\s*import\\s+(android|com\\.android|com\\.google\\.android)\\.", RegexOption.MULTILINE).containsMatchIn(t) } +
            offenders("JNA (macOS reaches Apple frameworks through the Swift helper, macos.md 7.1)") { _, t -> "com.sun.jna" in t || "net.java.dev.jna" in t }
        assertTrue(v.isEmpty(), v.toString())
    }

    @Test
    fun `an operating-system process is started in exactly two files, the helper launcher and the read-only runner`() {
        val users = main.filter { f -> f.readText(Charsets.UTF_8).let { "ProcessBuilder" in it || Regex("Runtime\\s*\\.\\s*getRuntime\\(\\)\\s*\\.\\s*exec").containsMatchIn(it) } }.map(::rel).sorted()
        assertEquals(
            listOf("src/main/kotlin/xyz/mdhv/asom/desktop/mac/exec/SystemProcessRunner.kt", "src/main/kotlin/xyz/mdhv/asom/desktop/mac/helper/HelperProcess.kt"),
            users,
        )
    }

    @Test
    fun `no login keychain, no security command line, no pmset or firewall setter, no launchctl, no shell`() {
        val v = offenders("keychain or security CLI use") { _, t ->
            listOf("KeychainStore", "KeyChainStore", "add-generic-password", "find-generic-password", "SecKeychain").any { it in t } ||
                Regex("\"/usr/bin/security\"").containsMatchIn(t)
        } + offenders("system setting changed or shell started") { _, t ->
            listOf("\"/bin/sh\"", "\"/bin/bash\"", "\"/bin/zsh\"", "\"-c\"", "launchctl", "osascript", "disablesleep", "\"--setglobalstate\"", "\"--add\"", "\"--unblockapp\"", "\"-a\", \"sleep\"", "sudo", "networksetup").any { it in t }
        }
        assertTrue(v.isEmpty(), v.toString())
    }

    @Test
    fun `the environment is read in one place only, and never chooses a program to run`() {
        val readers = main.filter { "System.getenv" in it.readText(Charsets.UTF_8) }.map(::rel).sorted()
        assertEquals(listOf("src/main/kotlin/xyz/mdhv/asom/desktop/mac/MacEnv.kt", "src/main/kotlin/xyz/mdhv/asom/desktop/mac/helper/HelperProcess.kt"), readers)
        val helperProcess = File(moduleDir, "src/main/kotlin/xyz/mdhv/asom/desktop/mac/helper/HelperProcess.kt").readText(Charsets.UTF_8)
        assertTrue("parentEnv: Map<String, String> = System.getenv()" in helperProcess, "the only getenv in the launcher is the default parent environment, which HelperEnvironment.scrub then reduces")
    }

    @Test
    fun `the Team ID is never taken from the environment or invented`() {
        val v = offenders("Team ID from the environment") { _, t -> Regex("getenv\\(\\s*\"[A-Z_]*TEAM").containsMatchIn(t) }
        assertTrue(v.isEmpty(), v.toString())
        val teamLiterals = main.flatMap { f -> Regex("\"[A-Z0-9]{10}\"").findAll(f.readText(Charsets.UTF_8)).map { rel(f) + ": " + it.value }.toList() }
        assertTrue(teamLiterals.isEmpty(), "a ten-character upper-case literal in main could be an invented Team ID: $teamLiterals")
        assertFalse(File(moduleDir, "src/main/resources/xyz/mdhv/asom/desktop/mac/build-info.properties").exists(), "no Team ID resource is committed (OWNER-FILL, M-D10)")
    }

    @Test
    fun `presence-tagged types are not serialisable`() {
        val v = offenders("PresenceTagged with @Serializable") { _, t -> "@PresenceTagged" in t && "@Serializable" in t }
        assertTrue(v.isEmpty(), v.toString())
        assertTrue(main.any { "@PresenceTagged" in it.readText() }, "non-vacuity: some type carries the tag")
    }

    @Test
    fun `the Swift helper reads no file, listens on no socket and writes nothing to stderr except two fixed lines`() {
        val v = swift.flatMap { f ->
            val t = f.readText(Charsets.UTF_8)
            listOf("FileManager", "contentsOfFile", "fopen(", "NWListener", "socket(", "bind(", "listen(", "URLSession", "NSXPC", "Process()", "posix_spawn", "system(")
                .filter { it in t }.map { "${f.name}: $it" }
        }
        assertTrue(v.isEmpty(), "the helper reads no files and opens no sockets (macos.md 3.5 security rule): $v")
        val stderrWriters = swift.filter { "stderr" in it.readText(Charsets.UTF_8) }.map { it.name }.sorted()
        assertEquals(listOf("main.swift", "HelperMain.swift").sorted(), stderrWriters)
        assertEquals(2, swift.sumOf { f -> Regex("fputs\\(").findAll(f.readText(Charsets.UTF_8)).count() }, "exactly two fixed stderr lines: the usage and the non-macOS refusal")
    }

    @Test
    fun `every macOS-only Swift source is inside a macOS guard, so swift build works on Linux`() {
        val macOnly = swift.filter { it.name != "main.swift" && it.parentFile.name == "asom-mac-helper" }
        assertTrue(macOnly.size >= 6)
        for (f in macOnly) {
            val lines = f.readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.trimStart().startsWith("//") }
            assertEquals("#if os(macOS)", lines.first().trim(), "${f.name} must open with the macOS guard")
            assertEquals("#endif", lines.last().trim(), "${f.name} must close the macOS guard")
        }
    }

    @Test
    fun `this module's tests do not weaken themselves and each IT class is macOS-only`() {
        val its = tests.filter { it.name.endsWith("IT.kt") }
        assertTrue(its.size >= 4, "expected the four macOS integration tests, found ${its.map { it.name }}")
        for (f in its) assertTrue("@EnabledOnOs(OS.MAC)" in f.readText(Charsets.UTF_8), "${f.name} must be @EnabledOnOs(OS.MAC)")
        val disabled = tests.filter { Regex("@" + "Disabled|@" + "Ignore").containsMatchIn(it.readText(Charsets.UTF_8)) }.map { it.name }
        assertTrue(disabled.isEmpty(), "a disabled test proves nothing: $disabled")
    }

    @Test
    fun `no file under the macOS directory touches the shipped tree`() {
        val root = File(repo, "desktop/packaging/macos")
        assertTrue(root.isDirectory)
        val bad = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "kts", "swift", "sh", "py", "yml") }
            .filter { f -> ("../../../" + "core") in f.readText(Charsets.UTF_8) || ("include" + "Build") in f.readText(Charsets.UTF_8) && f.extension == "kts" }
            .map { it.relativeTo(root).path }.toList()
        assertTrue(bad.isEmpty(), bad.toString())
    }
}
