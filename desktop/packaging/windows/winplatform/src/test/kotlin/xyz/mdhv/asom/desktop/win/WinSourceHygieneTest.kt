package xyz.mdhv.asom.desktop.win

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Static rules over this module's sources, checked by the build (the desktop-wide rules of `desktop/tools/check_law.py` apply
 * too and also run in CI). Every rule counts what it scanned and fails when the count is implausibly small (non-vacuity).
 */
class WinSourceHygieneTest {
    private val moduleDir = File(System.getProperty("asom.moduleDir") ?: error("asom.moduleDir not set"))
    private val main: List<File> = File(moduleDir, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private val tests: List<File> = File(moduleDir, "src/test/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun rel(f: File) = f.relativeTo(moduleDir).path.replace(File.separatorChar, '/')

    private fun offenders(rule: String, files: List<File> = main, bad: (File, String) -> Boolean): List<String> =
        files.filter { bad(it, it.readText(Charsets.UTF_8)) }.map { "$rule: ${rel(it)}" }

    @Test
    fun `sources were found`() {
        assertTrue(main.size >= 35, "found only ${main.size} main sources; every rule below would be vacuous")
        assertTrue(tests.size >= 12, "found only ${tests.size} test sources")
    }

    @Test
    fun `nothing prints outside the injected streams, nothing listens, nothing binds a wildcard, no Android`() {
        val bare = Regex("(?<![\\w.])(?<!fun )(println|print)\\(")
        val v = offenders("bare print or System.out/err") { _, t -> bare.containsMatchIn(t) || Regex("System\\.(out|err)").containsMatchIn(t) } +
            offenders("listener API") { _, t -> Regex("\\b(ServerSocket|ServerSocketChannel|DatagramSocket|embeddedServer|AsomServer\\()").containsMatchIn(t) } +
            offenders("wildcard address") { _, t -> "0.0.0.0" in t || Regex("\"::\"").containsMatchIn(t) } +
            offenders("android import") { _, t -> Regex("^\\s*import\\s+(android|com\\.android|com\\.google\\.android)\\.", RegexOption.MULTILINE).containsMatchIn(t) }
        assertTrue(v.isEmpty(), v.toString())
    }

    @Test
    fun `the node never applies a firewall command, elevates, or starts a shell`() {
        val v = offenders("elevation, shell or command execution") { _, t ->
            listOf("Start-Process", "-Verb RunAs", "RunAs", "Invoke-Expression", "ShellExecute", "powershell.exe", "pwsh.exe", "cmd.exe", "Set-ExecutionPolicy", "netsh advfirewall firewall add")
                .any { it.lowercase() in t.lowercase() }
        }
        assertTrue(v.isEmpty(), v.toString())
    }

    @Test
    fun `ProcessBuilder and Runtime exec exist in exactly one file, the read-only allowlisted runner`() {
        val users = main.filter { f -> f.readText(Charsets.UTF_8).let { "ProcessBuilder" in it || Regex("Runtime\\s*\\.\\s*getRuntime\\(\\)\\s*\\.\\s*exec").containsMatchIn(it) } }.map(::rel)
        assertEquals(listOf("src/main/kotlin/xyz/mdhv/asom/desktop/win/exec/SystemProcessRunner.kt"), users)
    }

    @Test
    fun `JNA is confined to the jna package and the pure logic compiles without it`() {
        val v = offenders("com.sun.jna outside the jna package") { f, t -> "com.sun.jna" in t && !rel(f).contains("/win/jna/") }
        assertTrue(v.isEmpty(), v.toString())
        assertTrue(main.count { rel(it).contains("/win/jna/") } >= 8, "the bindings are where the rule says")
    }

    @Test
    fun `presence-tagged types are not serialisable`() {
        val v = offenders("PresenceTagged with Serializable") { _, t -> "@PresenceTagged" in t && "@Serializable" in t }
        assertTrue(v.isEmpty(), v.toString())
        assertTrue(main.any { "@PresenceTagged" in it.readText(Charsets.UTF_8) }, "non-vacuity: some type carries the tag")
    }

    @Test
    fun `no API that silently uses the JVM default charset, because JDK 17 on Windows is not UTF-8 (AW20)`() {
        // Kotlin's own String and Path extensions default to UTF-8; these Java entry points default to the platform charset.
        val rules = listOf(
            "FileReader" to Regex("\\bFileReader\\("), "FileWriter" to Regex("\\bFileWriter\\("), "Scanner" to Regex("\\bScanner\\("),
            "InputStreamReader without a charset" to Regex("InputStreamReader\\([^,()]*\\)"),
            "OutputStreamWriter without a charset" to Regex("OutputStreamWriter\\([^,()]*\\)"),
            "PrintStream without a charset" to Regex("PrintStream\\([^,()]*\\)"),
            "getBytes()" to Regex("\\.getBytes\\(\\s*\\)"), "defaultCharset" to Regex("defaultCharset"),
        )
        val v = rules.flatMap { (name, re) -> offenders(name) { _, t -> re.containsMatchIn(t) } }
        assertTrue(v.isEmpty(), v.toString())
        // the rule can fail: a sample with each construct is caught
        for ((name, re) in rules) {
            val sample = when (name) {
                "FileReader" -> "FileReader(f)"
                "FileWriter" -> "FileWriter(f)"
                "Scanner" -> "Scanner(x)"
                "InputStreamReader without a charset" -> "InputStreamReader(x)"
                "OutputStreamWriter without a charset" -> "OutputStreamWriter(x)"
                "PrintStream without a charset" -> "PrintStream(x)"
                "getBytes()" -> "s.getBytes()"
                else -> "Charset.defaultCharset()"
            }
            assertTrue(re.containsMatchIn(sample), "the rule for $name must match its own sample")
        }
        assertTrue(!Regex("InputStreamReader\\([^,()]*\\)").containsMatchIn("InputStreamReader(x, Charsets.UTF_8)"))
    }

    @Test
    fun `the module depends on node-core and JNA only, and JNA is pinned`() {
        val text = File(moduleDir, "build.gradle.kts").readText(Charsets.UTF_8)
        val projects = Regex("project\\(\"(:[^\"]+)\"\\)").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(listOf(":node-core"), projects)
        assertTrue("net.java.dev.jna:jna:\$jnaVersion" in text && "net.java.dev.jna:jna-platform:\$jnaVersion" in text)
        assertTrue(Regex("val jnaVersion = \"5\\.\\d+\\.\\d+\"").containsMatchIn(text), "the JNA version is a literal 5.x pin")
        assertTrue(Regex("(implementation|api)\\(\"[^\"]+\"\\)").findAll(text).none { !it.value.contains("jna") }, "no other third-party dependency")
    }

    @Test
    fun `the host is registered for ServiceLoader`() {
        val f = File(moduleDir, "src/main/resources/META-INF/services/xyz.mdhv.asom.desktop.DesktopPlatform")
        assertEquals("xyz.mdhv.asom.desktop.win.WinPlatform", f.readText(Charsets.UTF_8).trim())
    }

    @Test
    fun `every Windows-only integration test class is annotated to skip elsewhere`() {
        val its = File(moduleDir, "src/test/kotlin/xyz/mdhv/asom/desktop/win/windows").listFiles { f -> f.extension == "kt" }.orEmpty().toList()
        assertTrue(its.size >= 10, "found only ${its.size} integration test files")
        // one annotation per top-level class: a second class in a file cannot ride on the first one's guard
        val classes = Regex("^class \\w+", RegexOption.MULTILINE)
        val guarded = Regex("^@EnabledOnOs\\(OS\\.WINDOWS\\)\\s*\\nclass ", RegexOption.MULTILINE)
        val bad = its.filter { f -> f.readText(Charsets.UTF_8).let { classes.findAll(it).count() != guarded.findAll(it).count() } }.map(::rel)
        assertTrue(bad.isEmpty(), "integration tests without @EnabledOnOs(OS.WINDOWS): $bad")
        val testCount = its.sumOf { f -> Regex("^\\s*@Test", RegexOption.MULTILINE).findAll(f.readText(Charsets.UTF_8)).count() }
        assertTrue(testCount >= 20, "only $testCount integration tests")
        // and no pure test hides in the integration directory
        assertTrue(its.all { it.name.endsWith("IT.kt") }, "files in windows/ must be named *IT.kt")
    }
}
