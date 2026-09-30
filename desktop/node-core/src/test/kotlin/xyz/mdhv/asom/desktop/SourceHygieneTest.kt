package xyz.mdhv.asom.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Static rules over the desktop sources (main scope of :node-core and :node): nothing prints outside the injected streams,
 * nothing listens, nothing binds a wildcard address, no Android import, and presence-tagged types are never serialisable.
 * `desktop/tools/check_law.py` runs the same rules from the command line; this copy fails the Gradle build too.
 */
class SourceHygieneTest {
    private val desktopDir: File = File(System.getProperty("asom.moduleDir") ?: error("asom.moduleDir not set")).parentFile
    private val mainFiles: List<File> = listOf("node-core", "node").flatMap { m ->
        File(desktopDir, "$m/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun violations(rule: String, test: (File, String) -> Boolean): List<String> =
        mainFiles.filter { test(it, it.readText(Charsets.UTF_8)) }.map { "$rule: ${it.relativeTo(desktopDir)}" }

    @Test
    fun `sources were found`() {
        assertTrue(mainFiles.size >= 20, "found only ${mainFiles.size} main sources; the scan would be vacuous")
    }

    @Test
    fun `nothing writes to the process streams except NodeEnv`() {
        val bare = Regex("(?<![\\w.])(?<!fun )(println|print)\\(")
        val v = violations("bare print or System.out/err") { f, t -> f.name != "NodeEnv.kt" && (bare.containsMatchIn(t) || Regex("System\\.(out|err)").containsMatchIn(t)) }
        assertTrue(v.isEmpty(), v.toString())
    }

    @Test
    fun `nothing listens or binds in main sources`() {
        val v = violations("listener API") { _, t -> Regex("\\b(ServerSocket|ServerSocketChannel|DatagramSocket|embeddedServer|AsomServer\\()").containsMatchIn(t) } +
            violations("wildcard address") { _, t -> "0.0.0.0" in t || Regex("\"::\"").containsMatchIn(t) }
        assertTrue(v.isEmpty(), v.toString())
    }

    @Test
    fun `no android import and no composite build`() {
        val v = violations("android import") { _, t -> Regex("^\\s*import\\s+(android|com\\.android|com\\.google\\.android)\\.", RegexOption.MULTILINE).containsMatchIn(t) }
        assertTrue(v.isEmpty(), v.toString())
        val settings = File(desktopDir, "settings.gradle.kts").readText()
        assertTrue(!Regex("^\\s*includeBuild\\(", RegexOption.MULTILINE).containsMatchIn(settings), "desktop/settings.gradle.kts must never use includeBuild")
    }

    @Test
    fun `presence-tagged types are not serialisable`() {
        val v = violations("PresenceTagged with @Serializable") { _, t -> "@PresenceTagged" in t && "@Serializable" in t }
        assertTrue(v.isEmpty(), v.toString())
        assertTrue(mainFiles.any { "@PresenceTagged" in it.readText() }, "non-vacuity: some type carries the tag")
    }
}
