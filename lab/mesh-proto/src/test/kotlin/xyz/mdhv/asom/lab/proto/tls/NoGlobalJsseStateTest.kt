package xyz.mdhv.asom.lab.proto.tls

import java.io.File
import java.security.Security
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.trust.LawCounters

/**
 * Design C12: every JSSE setting is on the engine, its parameters or the context of the mesh, never a JVM system property or a security property.
 * Checked three ways: the sources are scanned for the calls that change global state (with a positive control, so a scanner that sees nothing fails);
 * a real handshake run leaves the JSSE-related system and security properties exactly as they were; and the trust manager source is scanned to prove
 * that the only chain logic it reaches is `PeerChainVerifier` (law chain-only-through-verifyPeerChain). Evidence label: LAB, oracle: self.
 */
class NoGlobalJsseStateTest {
    companion object {
        val laws = LawCounters("tls-global-state")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("no-system-properties", "scanner-positive-control", "runtime-properties-unchanged", "chain-only-through-verifyPeerChain", "no-hostname-code"))

        private fun root(): File = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot is not set by the build"))

        fun tlsSources(): List<File> {
            val dirs = listOf("lab/mesh-proto/src/main/kotlin/xyz/mdhv/asom/lab/proto/tls", "lab/mesh-proto/src/test/kotlin/xyz/mdhv/asom/lab/proto/tls")
            return dirs.flatMap { d -> File(root(), d).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }
        }

        // Built from pieces so that this file does not contain the patterns it looks for.
        private val GLOBAL_STATE = listOf(
            Regex("System" + "\\.set" + "Propert(y|ies)"),
            Regex("System" + "\\.clear" + "Property"),
            Regex("Security" + "\\.set" + "Property"),
            Regex("Security" + "\\.(add|insert|remove)" + "Provider"),
            Regex("SSLContext" + "\\.set" + "Default"),
            Regex("HttpsURLConnection" + "\\.set" + "Default"),
            Regex("[\"']jdk" + "\\.tls" + "\\.[A-Za-z.]+[\"']"),
            Regex("[\"']jdk" + "\\.certpath" + "\\.[A-Za-z.]+[\"']"),
            Regex("[\"']javax" + "\\.net" + "\\.ssl" + "\\.[A-Za-z.]+[\"']"),
            Regex("[\"']https" + "\\.protocols[\"']"),
            Regex("-D" + "jdk\\.tls"),
        )

        fun violations(name: String, text: String): List<String> = GLOBAL_STATE.mapNotNull { r -> r.find(text)?.let { "$name: ${it.value}" } }
    }

    @Test
    fun noSourceChangesGlobalJsseState() {
        val sources = tlsSources().filter { it.name != "NoGlobalJsseStateTest.kt" }
        assertTrue(sources.size >= 10, "the scan found only ${sources.size} files: the path is wrong")
        val found = sources.flatMap { violations(it.name, it.readText()) }
        assertEquals(emptyList(), found)
        laws.bump("no-system-properties", sources.size)

        val control = violations("control", "System" + ".set" + "Property(\"a\", \"b\"); Security" + ".set" + "Property(\"k\", \"v\")") +
            violations("control", "x = \"jdk" + ".tls.client.protocols\"")
        assertEquals(3, control.size, "the scanner must see a seeded violation")
        laws.bump("scanner-positive-control")
    }

    @Test
    fun aHandshakeRunLeavesTheJsseProperties() {
        val prefixes = listOf("jdk.tls", "jdk.certpath", "javax.net.ssl", "https.protocols", "ssl.", "jsse.", "com.sun.net.ssl")
        fun systemSnapshot() = System.getProperties().stringPropertyNames().filter { k -> prefixes.any { k.startsWith(it) } }.sorted().associateWith { System.getProperty(it) }
        val securityKeys = listOf("jdk.tls.disabledAlgorithms", "jdk.tls.legacyAlgorithms", "jdk.certpath.disabledAlgorithms", "keystore.type", "ssl.KeyManagerFactory.algorithm", "ssl.TrustManagerFactory.algorithm", "securerandom.source")
        fun securitySnapshot() = securityKeys.associateWith { Security.getProperty(it) }

        val sysBefore = systemSnapshot()
        val secBefore = securitySnapshot()
        val providersBefore = Security.getProviders().map { it.name }

        val (a, b) = HonestNode.pairedPair()
        repeat(3) {
            val (cc, sc) = Loop.pair()
            val (c, s) = both({ a.dialPaired(cc, b.pin).also { at -> (at.end as? End.Ok)?.value?.close() } }, { b.accept(sc).also { at -> (at.end as? End.Ok)?.value?.close() } })
            assertTrue(c.value.end is End.Ok && s.value.end is End.Ok)
        }
        val hostile = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), protocols = arrayOf("TLSv1.2")))
        assertTrue(!hostile.honestEstablished)
        val dial = Scenarios.hostileServer(a, ChainMode.ExpectPaired(b.pin), HostileSpec(HostileCerts.honest(b.node), alpn = null))
        assertTrue(!dial.honestEstablished)

        assertEquals(sysBefore, systemSnapshot(), "JSSE-related system properties changed during mesh handshakes")
        assertEquals(secBefore, securitySnapshot(), "JSSE-related security properties changed during mesh handshakes")
        assertEquals(providersBefore, Security.getProviders().map { it.name }, "the provider list changed")
        laws.bump("runtime-properties-unchanged", 3)
    }

    @Test
    fun theTrustManagerReachesNoChainLogicExceptTheVerifier() {
        val managers = File(root(), "lab/mesh-proto/src/main/kotlin/xyz/mdhv/asom/lab/proto/tls/MeshTlsManagers.kt").readText()
        val start = managers.indexOf("class MeshTrustManager(")
        val end = managers.indexOf("class MeshKeyManager(")
        assertTrue(start in 0 until end, "the trust manager source was not found")
        val tm = managers.substring(start, end)
        val verifierCalls = Regex("PeerChainVerifier\\.(verify|verifyServer)\\(").findAll(tm).count()
        assertEquals(2, verifierCalls, "one call per side, both into PeerChainVerifier")
        val forbidden = listOf(
            "TrustManagerFactory", "PKIX", "CertPathValidator", "CertPathBuilder", "checkValidity", "getBasicConstraints", "getKeyUsage", "getPublicKey",
            "getSubjectX500Principal", "getSubjectAlternativeNames", "HostnameVerifier", "HttpsURLConnection", "X509TrustManagerImpl", "getDefaultAlgorithm",
        )
        assertEquals(emptyList(), forbidden.filter { tm.contains(it) }, "the trust manager does chain logic of its own")
        assertTrue(!tm.contains("KeyStore"), "no trust store")
        laws.bump("chain-only-through-verifyPeerChain")

        val everyMain = File(root(), "lab/mesh-proto/src/main/kotlin/xyz/mdhv/asom/lab/proto/tls").listFiles { f -> f.name.endsWith(".kt") }!!.joinToString("\n") { it.readText() }
        val hostnameCode = listOf("HostnameVerifier", "checkIdentity", "setEndpointIdentificationAlgorithm(\"", "endpointIdentificationAlgorithm = \"", "getSubjectAlternativeNames", "SNIMatcher")
        assertEquals(emptyList(), hostnameCode.filter { everyMain.contains(it) }, "no hostname or SNI logic anywhere in the mesh transport")
        laws.bump("no-hostname-code")
    }
}
