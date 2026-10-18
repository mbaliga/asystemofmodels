package xyz.mdhv.asom.lab.proto.tls

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.trust.LawCounters

/**
 * Writes the hellos the record tap read (client and server, hand-built and real JSSE ones from this JDK) together with the tap's own reading of each,
 * to `lab/mesh-proto/build/tls-tap-xcheck/jdk<N>.json`. `lab/mesh-proto/tools/tls/tap_xcheck.py` parses the same bytes with its own code and compares.
 * Evidence label: LAB, oracle: self (a second parser by the same author, not an independent one).
 */
class TapCrossCheckDumpTest {
    companion object {
        val laws = LawCounters("tap-xcheck-dump")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("hellos-dumped"))

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun ints(l: List<Int>) = l.joinToString(",", "[", "]")
        private fun strs(l: List<String>) = l.joinToString(",", "[", "]") { "\"$it\"" }

        fun json(h: HelloInfo, source: String): String =
            "{\"source\":\"$source\",\"jdk\":${Matrix.jdk},\"client\":${h.isClientHello},\"hex\":\"${hex(h.raw)}\",\"tap\":{" +
                "\"sessionId\":\"${hex(h.sessionId)}\",\"suites\":${ints(h.cipherSuites)},\"extensions\":${ints(h.extensionTypes)}," +
                "\"versions\":${ints(h.supportedVersions)},\"sigs\":${ints(h.signatureAlgorithms)},\"groups\":${ints(h.supportedGroups)}," +
                "\"alpn\":${strs(h.alpn)},\"sni\":${strs(h.serverNames)},\"selected\":${h.selectedVersion ?: "null"},\"psk\":${h.hasPsk},\"early\":${h.hasEarlyData},\"hrr\":${h.helloRetryRequest}}}"
    }

    @Test
    fun dumpTheHellosTheTapRead() {
        val out = ArrayList<String>()
        fun add(h: List<HelloInfo>, source: String) = h.forEach { out += json(it, source) }

        val (a, b) = HonestNode.pairedPair()
        val raws = listOf(
            "raw-plain" to RawHello(seed = 1),
            "raw-psk-early-sni" to RawHello(earlyData = true, pskIdentity = ByteArray(24) { it.toByte() }, serverName = "peer.example", seed = 2),
            "raw-two-alpn-two-versions" to RawHello(alpn = listOf("asom-mesh/1", "h2"), versions = listOf(0x0304, 0x0303), signatureAlgorithms = listOf(0x0403, 0x0804), seed = 3),
            "raw-no-alpn" to RawHello(alpn = emptyList(), seed = 4),
        )
        for ((name, r) in raws) {
            val bytes = r.record()
            val tap = RecordTap(ScriptedNet(bytes, 97))
            RecordTapTest.drain(tap, bytes.size)
            add(tap.clientHellos, name)
        }

        val honest = Scenarios.hostileServer(a, ChainMode.ExpectPaired(b.pin), HostileSpec(HostileCerts.honest(b.node)))
        add(honest.honest.tap.clientHellos + honest.honest.tap.serverHellos, "honest-dial")
        val legacy = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), protocols = arrayOf("TLSv1.3", "TLSv1.2"), peerHost = "peer.example", serverNames = listOf("peer.example")))
        add(legacy.hostileTap.clientHellos + legacy.hostileTap.serverHellos, "jsse-default-client")
        val tls12 = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), protocols = arrayOf("TLSv1.2")))
        add(tls12.hostileTap.clientHellos, "jsse-tls12-client")
        val ctx = HostileContext(HostileCerts.honest(a.node))
        repeat(2) {
            val d = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), peerHost = "127.0.0.1", context = ctx))
            add(d.hostileTap.clientHellos + d.hostileTap.serverHellos, "jsse-resumption-round-${it + 1}")
        }

        assertTrue(out.size >= 13, "only ${out.size} hellos")
        val dir = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot"), "lab/mesh-proto/build/tls-tap-xcheck")
        dir.mkdirs()
        File(dir, "jdk${Matrix.jdk}.json").writeText(out.joinToString(",\n", "[\n", "\n]\n"))
        laws.bump("hellos-dumped", out.size)
        println("tap-xcheck-dump: ${out.size} hellos written for jdk ${Matrix.jdk}")
    }
}
