package xyz.mdhv.asom.ut

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString

class SelfTestTest {
    private fun <T> withHome(block: (Map<String, String>) -> T): T {
        val home = Files.createTempDirectory("asom-ut-selftest-home-").toFile()
        try {
            return block(mapOf("HOME" to home.absolutePath, "TMPDIR" to home.absolutePath))
        } finally {
            home.deleteRecursively()
        }
    }

    /** The lab's own count of normative vectors of a family, read from the vector files (the same files the runner reads). */
    private fun labCount(family: String): Long {
        val root = File(System.getProperty("asom.repoRoot"), "lab/conformance")
        return listOf("json", "manifest").flatMap { dir -> root.resolve(dir).listFiles { f -> f.name.endsWith(".json") }!!.toList() }
            .map { parseJ(it.readText(Charsets.UTF_8)) as JObject }
            .filter { it.str("family") == family }
            .sumOf { doc -> doc.arr("vectors").count { it.asObj().str("status") == "normative" }.toLong() }
    }

    @Test
    fun theSelfTestPassesAndReportsTheRuntimeItRanOn() {
        val r = withHome { SelfTest(it).run() }
        assertEquals("selftest", r.members[0].first, "the first member must be selftest")
        assertEquals(JString("ok"), r["selftest"], "self-test result: $r")
        assertEquals(JString("TLSv1.3"), r["tls"])
        assertEquals(JString("asom-mesh/1"), r["alpn"])
        assertEquals(JBool(true), r["clientAuth"])
        assertEquals(JBool(true), r["pinMismatchRefused"])
        assertEquals(JString("ok"), r["es256"])
        assertEquals(JString("ok"), r["sha256"])
        val runtime = r.obj("runtime")
        assertTrue(runtime.str("javaVersion").isNotEmpty())
        val rss = r.int("rssKiB")
        assertTrue(rss > 0, "VmRSS was not read: $rss")
        assertTrue(r["thermalReadable"] is JBool && r["batteryReadable"] is JBool, "thermal and battery readability are recorded, not asserted")
        assertEquals(100, r.obj("jsonl").int("appends").toInt())
        assertEquals(JBool(true), r.obj("jsonl")["forced"])
        val paths = r.obj("paths")
        assertEquals(JBool(true), paths["resolved"])
        for (d in listOf("data", "ledger", "identity", "cache", "config", "tmp")) assertEquals("writable", paths.str(d), d)
        val line = FrameCodec.encodeValue(r)
        assertFalse('\n' in line, "the result must be one line")
        assertTrue(line.startsWith("{\"selftest\":\"ok\""), line.take(40))
    }

    @Test
    fun vectorCountsEqualTheLabsCountsForTheSameFamilies() {
        val r = withHome { SelfTest(it).run() }
        val v = r.obj("vectors")
        for (family in listOf("M01", "M02", "M03")) {
            val want = labCount(family)
            assertTrue(want > 0, "the lab has no $family vectors: the comparison would be vacuous")
            assertEquals(want, v.int(family), "the self-test ran a different number of $family vectors than the lab has")
        }
        assertEquals(0L, v.int("failed"))
    }

    @Test
    fun anUnresolvableHomeIsAFailureNotASkip() {
        val r = SelfTest(mapOf("HOME" to "relative")).run()
        assertEquals(JString("fail"), r["selftest"])
        assertTrue(r.arr("failed").any { (it as JString).value == "paths" })
        assertEquals(JBool(false), r.obj("paths")["resolved"])
    }

    @Test
    fun aFailingCheckIsReportedWithItsNameAndNeverFlattered() {
        val r = withHome { SelfTest(it, ledgerRows = 0).run() }
        assertEquals(JString("fail"), r["selftest"], "zero appends cannot satisfy the read-back check")
        assertEquals(listOf(JString("jsonl")), (r["failed"] as JArray).items)
        assertTrue(r.arr("failedDetail").isNotEmpty())
    }

    @Test
    fun theHandshakeRefusesAWrongPin() {
        assertTrue(TlsInMemory.handshakeRefused())
        val ok = TlsInMemory.handshake(pinMismatch = false)
        assertEquals("TLSv1.3", ok.protocol)
        assertEquals("asom-mesh/1", ok.alpn)
        assertTrue(ok.clientCertificateSeen)
    }

    @Test
    fun theTlsSelfTestSetsNoJvmSystemProperty() {
        val before = System.getProperties().stringPropertyNames().filter { it.startsWith("jdk.tls") || it.startsWith("javax.net.ssl.") || it.startsWith("https.") }.associateWith { System.getProperty(it) }
        TlsInMemory.handshake(pinMismatch = false)
        val after = System.getProperties().stringPropertyNames().filter { it.startsWith("jdk.tls") || it.startsWith("javax.net.ssl.") || it.startsWith("https.") }.associateWith { System.getProperty(it) }
        assertEquals(before, after, "C12: no JSSE system property may be set by the node")
    }
}
