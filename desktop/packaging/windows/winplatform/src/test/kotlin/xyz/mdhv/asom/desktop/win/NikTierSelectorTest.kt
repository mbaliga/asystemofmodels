package xyz.mdhv.asom.desktop.win

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.KeyStorage
import xyz.mdhv.asom.desktop.win.api.CngProvider
import xyz.mdhv.asom.desktop.win.api.KeyScope
import xyz.mdhv.asom.desktop.win.fakes.FakeCng
import xyz.mdhv.asom.desktop.win.fakes.LawCounter
import xyz.mdhv.asom.desktop.win.keys.EccBlob
import xyz.mdhv.asom.desktop.win.keys.Es256
import xyz.mdhv.asom.desktop.win.keys.KeyTierRequest
import xyz.mdhv.asom.desktop.win.keys.NcryptNik
import xyz.mdhv.asom.desktop.win.keys.NikKey
import xyz.mdhv.asom.desktop.win.keys.NikSelectionFailure
import xyz.mdhv.asom.desktop.win.keys.NikTier
import xyz.mdhv.asom.desktop.win.keys.NikTierSelector
import xyz.mdhv.asom.desktop.win.keys.Spki

/** windows.md 5: T2 then T1, T0 only by flag, a failed self-test deletes the key, and the tier labels never drop their limits. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NikTierSelectorTest {
    private val laws = LawCounter(
        listOf(
            "order-T2-first", "T2-create-fails-falls-back", "T2-selftest-fails-deleted-falls-back", "both-fail-never-T0",
            "T0-only-by-flag", "T0-flag-skips-cng", "labels-carry-limits", "T2-always-self-reported", "es256-roundtrip", "es256-rejects-tamper",
        ),
    )

    private fun tierKey(cng: FakeCng, tier: NikTier, name: String = "asom-nik-v1") = { NcryptNik.create(cng, tier, name, KeyScope.USER) }
    private val noT0: () -> NikKey = { error("T0 must not be asked for") }

    @Test
    fun `AUTO tries T2 first and stops there when it works`() {
        val cng = FakeCng()
        val sel = NikTierSelector.select(
            KeyTierRequest.AUTO,
            t2 = tierKey(cng, NikTier.T2_TPM),
            t1 = { error("T1 must not be tried when T2 works") },
            t0 = noT0,
        )
        assertEquals(NikTier.T2_TPM, sel.tier)
        assertEquals(KeyStorage.TPM, sel.tier.keyStorage)
        assertEquals(1, sel.attempts.size)
        assertEquals(listOf("PLATFORM/asom-nik-v1/USER"), cng.created)
        laws.hit("order-T2-first")
    }

    @Test
    fun `a failed create on the TPM falls back to the software KSP`() {
        val cng = FakeCng().also { it.failCreate = setOf(CngProvider.PLATFORM) }
        val sel = NikTierSelector.select(KeyTierRequest.AUTO, tierKey(cng, NikTier.T2_TPM), tierKey(cng, NikTier.T1_OS_KEYSTORE), noT0)
        assertEquals(NikTier.T1_OS_KEYSTORE, sel.tier)
        assertEquals(KeyStorage.OS_KEYSTORE, sel.tier.keyStorage)
        assertEquals(listOf(false, true), sel.attempts.map { it.ok })
        assertTrue("create failed" in sel.attempts[0].detail)
        laws.hit("T2-create-fails-falls-back")
    }

    @Test
    fun `a T2 key that fails its self-test is deleted and T1 is used`() {
        for (mode in listOf("length", "corrupt")) {
            val cng = FakeCng()
            if (mode == "length") cng.badSignatureLength = setOf(CngProvider.PLATFORM) else cng.corruptSignature = setOf(CngProvider.PLATFORM)
            val sel = NikTierSelector.select(KeyTierRequest.AUTO, tierKey(cng, NikTier.T2_TPM), tierKey(cng, NikTier.T1_OS_KEYSTORE), noT0)
            assertEquals(NikTier.T1_OS_KEYSTORE, sel.tier, mode)
            assertEquals(listOf("PLATFORM/asom-nik-v1"), cng.deleted, "the failed T2 key must not stay on the TPM ($mode)")
            assertNull(cng.keys[CngProvider.PLATFORM to "asom-nik-v1"], mode)
            assertTrue("self-test failed" in sel.attempts[0].detail && "key deleted" in sel.attempts[0].detail, sel.attempts[0].detail)
            laws.hit("T2-selftest-fails-deleted-falls-back")
        }
    }

    @Test
    fun `when both CNG tiers fail there is NO fall-through to the file tier`() {
        val cng = FakeCng().also { it.providerMissing = setOf(CngProvider.PLATFORM, CngProvider.SOFTWARE) }
        val e = assertFailsWith<NikSelectionFailure> {
            NikTierSelector.select(KeyTierRequest.AUTO, tierKey(cng, NikTier.T2_TPM), tierKey(cng, NikTier.T1_OS_KEYSTORE), noT0)
        }
        assertEquals(listOf(NikTier.T2_TPM, NikTier.T1_OS_KEYSTORE), e.attempts.map { it.tier })
        assertTrue(e.attempts.none { it.tier == NikTier.T0_FILE })
        laws.hit("both-fail-never-T0")
    }

    @Test
    fun `T0 is selected only by the explicit file request and then CNG is not touched`() {
        val cng = FakeCng()
        var t0Asked = 0
        val file = NikTierSelectorTestFile.fileKey()
        val sel = NikTierSelector.select(
            KeyTierRequest.FILE,
            t2 = { error("CNG must not be touched for a file request") },
            t1 = { error("CNG must not be touched for a file request") },
            t0 = { t0Asked++; file },
        )
        assertEquals(1, t0Asked)
        assertEquals(NikTier.T0_FILE, sel.tier)
        assertEquals(KeyStorage.FILE, sel.tier.keyStorage)
        assertTrue(cng.created.isEmpty())
        laws.hit("T0-only-by-flag")
        laws.hit("T0-flag-skips-cng")
    }

    @Test
    fun `every tier states what it does NOT guarantee and T2 is always self-reported`() {
        for (t in NikTier.entries) {
            assertTrue(t.doesNotGuarantee.size >= 3 || (t == NikTier.T2_TPM && t.doesNotGuarantee.size >= 4), "$t")
            assertTrue(t.doesNotGuarantee.all { it.length > 20 })
            laws.hit("labels-carry-limits")
        }
        assertTrue(NikTier.T2_TPM.label.endsWith("(self-reported)"))
        assertTrue(NikTier.T2_TPM.doesNotGuarantee.any { "attestation" in it })
        assertTrue(NikTier.T2_TPM.doesNotGuarantee.any { "USE of the key" in it })
        assertTrue(NikTier.T1_OS_KEYSTORE.doesNotGuarantee.any { "roaming" in it })
        assertTrue(NikTier.T0_FILE.doesNotGuarantee.any { "Volume Shadow Copy" in it })
        laws.hit("T2-always-self-reported")
    }

    @Test
    fun `ES256 over the fake CNG round-trips and verifies with the JCA, and any tampering fails`() {
        val cng = FakeCng()
        val key = NcryptNik.create(cng, NikTier.T1_OS_KEYSTORE, "k", KeyScope.USER)
        val rnd = SecureRandom(byteArrayOf(1, 2, 3))
        repeat(200) {
            val msg = ByteArray(rnd.nextInt(200)).also(rnd::nextBytes)
            val sig = key.sign(msg)
            assertEquals(64, sig.size)
            assertTrue(Es256.verify(key.spki(), msg, sig))
            laws.hit("es256-roundtrip")
            val bad = sig.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            assertFalse(Es256.verify(key.spki(), msg, bad))
            assertFalse(Es256.verify(key.spki(), msg + 0, sig))
            assertFalse(Es256.verify(key.spki(), msg, sig.copyOf(63)))
            laws.hit("es256-rejects-tamper")
        }
        // The SPKI is a real P-256 key the JCA accepts.
        val jca = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key.spki()))
        assertEquals("EC", jca.algorithm)
        // sha256 helper is SHA-256
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1)), Es256.sha256(byteArrayOf(1)))
    }

    @Test
    fun `the ECC public blob parser accepts only ECDSA P-256 blobs`() {
        val cng = FakeCng()
        val h = cng.createEcdsaP256(CngProvider.SOFTWARE, "b", KeyScope.USER)
        val blob = h.exportPublicBlob()
        val xy = EccBlob.parseP256(blob)
        assertEquals(64, xy.size)
        assertEquals(91, Spki.p256(xy).size)
        assertFailsWith<IllegalArgumentException> { EccBlob.parseP256(blob.copyOf(71)) }
        assertFailsWith<IllegalArgumentException> { EccBlob.parseP256(blob.copyOf().also { it[0] = 0x45; it[1] = 0x43; it[2] = 0x4B; it[3] = 0x31 }) }
        assertFailsWith<IllegalArgumentException> { EccBlob.parseP256(blob.copyOf().also { it[4] = 48 }) }
        assertFailsWith<IllegalArgumentException> { Spki.p256(ByteArray(63)) }
        assertNotNull(cng.open(CngProvider.SOFTWARE, "b", KeyScope.USER))
    }

    @Test
    fun `a created key whose blob is wrong is deleted, not left persisted`() {
        val cng = FakeCng().also { it.badBlob = setOf(CngProvider.SOFTWARE) }
        assertFailsWith<IllegalArgumentException> { NcryptNik.create(cng, NikTier.T1_OS_KEYSTORE, "x", KeyScope.USER) }
        assertEquals(listOf("SOFTWARE/x"), cng.deleted)
        assertNull(cng.keys[CngProvider.SOFTWARE to "x"])
    }

    @Test
    fun `a signature that is not 64 bytes is refused by the key itself`() {
        val cng = FakeCng().also { it.badSignatureLength = setOf(CngProvider.SOFTWARE) }
        val key = NcryptNik.create(cng, NikTier.T1_OS_KEYSTORE, "y", KeyScope.USER)
        assertFailsWith<IllegalStateException> { key.sign(ByteArray(3)) }
    }

    @AfterAll
    fun nonVacuity() = laws.assertAllExercised("nik-tier")
}
