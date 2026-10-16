package xyz.mdhv.asom.lab.proto.trust

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.manifest.Es256
import xyz.mdhv.asom.lab.manifest.Spki
import xyz.mdhv.asom.lab.manifest.TestOnlyKeys

/**
 * The fixed templates of trust.md 2.4 against the platform's own X.509 parser (the spec says parsing uses it) and its own signature check, on
 * freshly generated keys and on the TEST-ONLY keys. Evidence label: LAB, oracle: self (the JDK parser is a second reader, not an independent author).
 */
class CertTemplatesTest {
    companion object {
        val laws = LawCounters("cert-templates")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("jdk-parses-node", "jdk-parses-leaf", "jdk-verifies-signature", "template-deterministic", "ski-method", "random-keys"))

        fun x509(der: ByteArray): X509Certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

        fun serial(rnd: SplittableRandom): ByteArray {
            val b = ByteArray(16) { rnd.nextInt(256).toByte() }
            b[0] = (b[0].toInt() and 0x7f).toByte()
            if (b.all { it == 0.toByte() }) b[15] = 1
            return b
        }
    }

    @Test
    fun jdkParserAndSignatureCheckAgreeOnFreshKeys() {
        val rnd = SplittableRandom(20260930)
        val iterations = 25
        repeat(iterations) {
            val nik = Es256.generate()
            val leafKey = Es256.generate()
            val created = 1_790_000_000L + rnd.nextLong(0, 50_000_000)
            val node = CertTemplates.nodeCertificate(nik.private, nik.spki, serial(rnd), created)
            val leaf = CertTemplates.leafCertificate(nik.private, nik.spki, leafKey.spki, serial(rnd), created + 86_400)
            val n = x509(node)
            val l = x509(leaf)
            laws.bump("jdk-parses-node")
            laws.bump("jdk-parses-leaf")
            assertEquals(0, n.basicConstraints, "pathLen 0 on a CA")
            assertEquals(-1, l.basicConstraints, "not a CA")
            assertTrue(n.keyUsage[5] && n.keyUsage.count { it } == 1, "keyCertSign only")
            assertTrue(l.keyUsage[0] && l.keyUsage.count { it } == 1, "digitalSignature only")
            assertEquals(listOf("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"), l.extendedKeyUsage)
            assertEquals(Oids.ECDSA_SHA256, n.sigAlgOID)
            assertEquals(Oids.ECDSA_SHA256, l.sigAlgOID)
            assertEquals(setOf(Oids.BASIC_CONSTRAINTS, Oids.KEY_USAGE), n.criticalExtensionOIDs)
            assertEquals(setOf(Oids.BASIC_CONSTRAINTS, Oids.KEY_USAGE), l.criticalExtensionOIDs)
            assertEquals(n.subjectX500Principal, n.issuerX500Principal)
            assertEquals(n.subjectX500Principal, l.issuerX500Principal)
            assertEquals("CN=asom-node ${Spki.nodeTag(nik.spki)}", n.subjectX500Principal.name)
            assertEquals("CN=asom-session ${Spki.nodeTag(nik.spki)}", l.subjectX500Principal.name)
            assertEquals((created + 86_400 + 14L * 86_400) * 1000, l.notAfter.time)
            assertEquals((created + 86_400 - 3600) * 1000, l.notBefore.time)
            assertEquals(CertTemplates.NODE_NOT_AFTER_EPOCH_SEC * 1000, n.notAfter.time)
            assertEquals((created - 3600) * 1000, n.notBefore.time)
            n.verify(n.publicKey)
            l.verify(n.publicKey)
            laws.bump("jdk-verifies-signature")
            assertContentEquals(nik.spki, n.publicKey.encoded)
            assertContentEquals(leafKey.spki, l.publicKey.encoded)
            // The SKI extension value is OCTET STRING { OCTET STRING keyId }, the AKI is SEQUENCE { [0] keyId }.
            val ski = n.getExtensionValue(Oids.SUBJECT_KEY_ID)!!
            assertContentEquals(CertTemplates.subjectKeyId(nik.spki), DerReader.single(DerReader.single(ski).content).content)
            assertTrue(Hex.encode(l.getExtensionValue(Oids.AUTHORITY_KEY_ID)!!).endsWith(Hex.encode(CertTemplates.subjectKeyId(nik.spki))))
            laws.bump("random-keys")
        }
        println("iterations: $iterations")
    }

    @Test
    fun theSkiIsTheLeftmost160BitsOfSha256OfThePoint() {
        for (k in TestOnlyKeys.entries) {
            val h = java.security.MessageDigest.getInstance("SHA-256").digest(k.spki.copyOfRange(26, 91)).copyOf(20)
            assertContentEquals(h, CertTemplates.subjectKeyId(k.spki))
            laws.bump("ski-method")
        }
    }

    @Test
    fun templatesAreDeterministicInTheirInputs() {
        val k1 = TestOnlyKeys.key("key1")
        val k3 = TestOnlyKeys.key("key3")
        val s = Hex.decode("0102030405060708090a0b0c0d0e0f10")
        assertContentEquals(CertTemplates.nodeTbs(k1.spki, s, 1_790_000_000 - 86_400), CertTemplates.nodeTbs(k1.spki, s, 1_790_000_000 - 86_400))
        assertContentEquals(CertTemplates.leafTbs(k1.spki, k3.spki, s, 1_790_000_000), CertTemplates.leafTbs(k1.spki, k3.spki, s, 1_790_000_000))
        val a = CertTemplates.leafTbs(k1.spki, k3.spki, s, 1_790_000_000)
        val b = CertTemplates.leafTbs(k1.spki, k3.spki, s, 1_790_000_001)
        assertTrue(!a.contentEquals(b), "a different time gives different bytes")
        laws.bump("template-deterministic", 3)
    }

    @Test
    fun theEncoderRefusesWhatTheTemplatesForbid() {
        val k1 = TestOnlyKeys.key("key1")
        val bad = kotlin.runCatching { CertTemplates.nodeTbs(k1.spki, ByteArray(16) { 0xff.toByte() }, 0) }
        assertTrue(bad.isFailure, "a serial with the high bit set is refused")
        assertTrue(kotlin.runCatching { CertTemplates.nodeTbs(k1.spki, ByteArray(15) { 1 }, 0) }.isFailure, "a serial of 15 bytes is refused")
        assertTrue(kotlin.runCatching { CertTemplates.nodeTbs(k1.spki, ByteArray(16), 0) }.isFailure, "an all-zero serial is refused")
        assertTrue(kotlin.runCatching { CertTemplates.nodeTbs(k1.spki.copyOf(90), ByteArray(16) { 1 }, 0) }.isFailure, "a short SPKI is refused")
        assertNotNull(CertTemplates.nodeTbs(k1.spki, ByteArray(16) { 1 }, 0))
    }
}
