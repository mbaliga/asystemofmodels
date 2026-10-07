package xyz.mdhv.asom.ut

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Static rules for the Ubuntu Touch host: it listens on nothing, dials nothing, prints only through one file, sets no JVM property. */
class SourceHygieneTest {
    private val utRoot = File(System.getProperty("asom.utRoot") ?: error("asom.utRoot is not set"))
    private val mainSources = utRoot.resolve("jvm/ut-host/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun scan(rule: String, pattern: Regex, allowIn: Set<String> = emptySet()) {
        val hits = mainSources.filter { it.name !in allowIn }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> !l.trimStart().startsWith("*") && !l.trimStart().startsWith("//") && pattern.containsMatchIn(l) }
                .map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }
        assertTrue(hits.isEmpty(), "$rule: $hits")
    }

    @Test
    fun theSourceSetIsNotEmpty() {
        assertTrue(mainSources.size >= 9, "found ${mainSources.size} main sources")
    }

    @Test
    fun noAndroidImportAnywhere() = scan("android import", Regex("""^\s*import\s+(android|com\.android|com\.google\.android)\."""))

    @Test
    fun onlyStdioTouchesTheStandardStreams() {
        scan("System.out / System.err", Regex("""System\.(out|err)\b"""), allowIn = setOf("Stdio.kt"))
        scan("bare print", Regex("""(?<![\w.])(println|print)\("""))
        scan("System.in", Regex("""System\.`?in`?\b"""))
    }

    @Test
    fun nothingListensAndNothingDials() {
        scan("a listener or a dial API", Regex("""\b(ServerSocket|ServerSocketChannel|DatagramSocket|DatagramChannel|SocketChannel|InetSocketAddress|HttpClient|HttpServer|embeddedServer|AsomServer|URL\(|URI\(|HttpURLConnection|MulticastSocket)\b"""))
        scan("a wildcard address", Regex("""0\.0\.0\.0|"::""""))
        scan("a socket constructor", Regex("""(?<![\w.])Socket\("""))
    }

    @Test
    fun noProcessIsSpawnedAndNoJvmPropertyIsSet() {
        scan("a child process", Regex("""ProcessBuilder|Runtime\.getRuntime\(\)\.exec"""))
        scan("System.setProperty (C12: no JSSE or other JVM property)", Regex("""System\.(setProperty|setProperties|clearProperty)"""))
        scan("an internal JDK class", Regex("""\bsun\.|jdk\.internal"""))
    }

    @Test
    fun theFrozenEgressEnumNeverGrowsAPeerMember() {
        val name = "Egress" + "." + "PEER"
        scan("frozen enum reuse", Regex("(?<![A-Za-z])" + Regex.escape(name)))
    }

    @Test
    fun theBuildIsSeparateAndMapsByDirectory() {
        val settings = utRoot.resolve("jvm/settings.gradle.kts").readText()
        assertTrue(!Regex("""^\s*includeBuild\(""", RegexOption.MULTILINE).containsMatchIn(settings), "includeBuild is forbidden (R3-CONFORMANCE-3)")
        val mapped = Regex("""^\s*":([\w:-]+)"\s+to\s+"\.\./\.\./""", RegexOption.MULTILINE).findAll(settings).map { it.groupValues[1] }.toSet()
        assertTrue(mapped.containsAll(setOf("core:contract", "core:catalogue", "core:routing", "core:inference-api", "server", "json", "manifest", "ledger-model", "node-core")), "mapped: $mapped")
        assertEquals(mapped.size, mapped.toSet().size)
        val rootSettings = utRoot.resolve("../settings.gradle.kts").readText()
        assertEquals(0, Regex("ubuntu-touch").findAll(rootSettings).count(), "the root build must never name ubuntu-touch")
    }

    @Test
    fun noPushNotificationsAndNoDownloadManager() {
        scan("push or download manager", Regex("""push-notification|PushClient|DownloadManager""", RegexOption.IGNORE_CASE))
    }

    @Test
    fun jvmOptionsKeepStdoutForFramesAndSetNoJsseProperty() {
        val flags = utRoot.resolve("runtime/jvm.options").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue("-Xlog:disable" in flags && "-XX:+DisplayVMOutputToStderr" in flags, "the JVM's own output must not reach stdout (ERR-UT-CDS-1): $flags")
        assertTrue(flags.none { it.startsWith("-Djavax.net.ssl") || it.startsWith("-Djdk.tls") || it.startsWith("-Djdk.http") || it.startsWith("-Dhttps.") }, "C12: no JSSE property: $flags")
        assertTrue(flags.none { it.contains("SharedArchiveFile") || it.contains("AutoCreateSharedArchive") }, "no CDS flag on a jlinked runtime (ERR-UT-CDS-1): $flags")
        for (needed in listOf("-XX:-UsePerfData", "-XX:-UseContainerSupport", "-XX:+UseSerialGC", "-Xmx128m", "-Xss512k")) assertTrue(needed in flags, "missing $needed")
        assertTrue(flags.none { it.startsWith("-agentlib") || it.startsWith("-javaagent") || it.contains("jdwp") }, "no agent or debug flag: $flags")
        assertTrue(flags.any { it.startsWith("-Djava.io.tmpdir=") })
    }
}
