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
    private companion object {
        const val CONTROL_SERVER = "ControlServer.kt"

        /** The same pattern family as desktop/tools/check_law.py and lab/tools/check_law.py (its self-test compares them). */
        val LISTENER = Regex(
            "ServerSocket|DatagramSocket|DatagramChannel|MulticastSocket|embeddedServer|AsomServer\\(|HttpsServer|HttpServer" +
                "|com\\.sun\\.net\\.httpserver",
        )
        val WILDCARD = Regex("(InetSocketAddress|getByName|getAllByName|bind)\\(\\s*\"(::|0:0:0:0:0:0:0:0)\"|InetSocketAddress\\(\\s*[\\w.]+\\s*\\)")
        val DIAL = Regex(
            "HttpClient|HttpURLConnection|HttpsURLConnection|openConnection\\(|\\.openStream\\(|\\bSocket\\(|SSLSocket|\\bURL\\(" +
                "|SocketChannel\\.open\\([^)]*Inet|connect\\(\\s*(java\\.net\\.)?InetSocketAddress",
        )
    }

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
    fun `nothing listens or binds in main sources except the one AF_UNIX control server`() {
        val v = violations("listener API") { f, t -> f.name != CONTROL_SERVER && LISTENER.containsMatchIn(t) } +
            violations("wildcard address") { _, t -> "0.0.0.0" in t || Regex("\"::\"").containsMatchIn(t) || WILDCARD.containsMatchIn(t) }
        assertTrue(v.isEmpty(), v.toString())
    }

    @Test
    fun `nothing dials out from a main source`() {
        val v = violations("outbound network API") { _, t -> DIAL.containsMatchIn(t) }
        assertTrue(v.isEmpty(), v.toString())
    }

    @Test
    fun `the listener, wildcard and dial patterns catch every forbidden API and pass the clean controls`() {
        val listeners = listOf(
            "HttpServer.create(InetSocketAddress(\"127.0.0.1\", 8080), 0)",
            "AsynchronousServerSocketChannel.open()",
            "DatagramChannel.open()",
            "DatagramSocket(5353)",
            "MulticastSocket(5353)",
            "ServerSocketChannel.open()",
            "com.sun.net.httpserver.HttpsServer.create()",
        )
        val wildcards = listOf("InetSocketAddress(\"::\", 5353)", "InetSocketAddress(9090)", "InetAddress.getByName(\"::\")")
        val dials = listOf(
            "java.net.http.HttpClient.newHttpClient()",
            "URL(\"https://example.invalid\").openConnection()",
            "URL(\"https://example.invalid\").openStream()",
            "java.net.Socket(\"example.invalid\", 443)",
            "SSLSocket",
            "SocketChannel.open(InetSocketAddress(\"example.invalid\", 443))",
            "c.connect(InetSocketAddress(\"example.invalid\", 443))",
        )
        for (m in listeners) assertTrue(LISTENER.containsMatchIn(m), "listener mutant not caught: $m")
        for (m in wildcards) assertTrue(WILDCARD.containsMatchIn(m), "wildcard mutant not caught: $m")
        for (m in dials) assertTrue(DIAL.containsMatchIn(m), "dial mutant not caught: $m")
        for (c in listOf("SocketChannel.open(UnixDomainSocketAddress.of(path))", "fun add(a: Int, b: Int) = a + b", "InetSocketAddress(\"127.0.0.1\", 0)")) {
            assertTrue(!LISTENER.containsMatchIn(c) && !WILDCARD.containsMatchIn(c) && !DIAL.containsMatchIn(c), "clean control flagged: $c")
        }
    }

    /**
     * ERR-DL2-3: the DL2 control socket is the ONLY main source allowed to name a listener, and it may open only an
     * AF_UNIX one: no Internet address or family, no port, no wildcard, and it is the only such file.
     */
    @Test
    fun `the control server is the single allowed listener and is AF_UNIX only`() {
        val servers = mainFiles.filter { it.name == CONTROL_SERVER }
        assertTrue(servers.size == 1, "expected exactly one $CONTROL_SERVER, found ${servers.size}")
        val text = servers.single().readText(Charsets.UTF_8)
        assertTrue("StandardProtocolFamily.UNIX" in text, "the control server must open StandardProtocolFamily.UNIX")
        val inet = Regex("InetSocketAddress|InetAddress|StandardProtocolFamily\\.INET|\\.bind\\(\\s*null|ServerSocketChannel\\.open\\(\\s*\\)|ServerSocket\\(|DatagramSocket|DatagramChannel|localhost|127\\.0\\.0\\.1")
        assertTrue(!inet.containsMatchIn(text), "the control server names an Internet socket API or address: ${inet.find(text)?.value}")
        assertTrue(Regex("ServerSocketChannel\\.open\\(StandardProtocolFamily\\.UNIX\\)").findAll(text).count() == 1, "exactly one AF_UNIX listener")
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
