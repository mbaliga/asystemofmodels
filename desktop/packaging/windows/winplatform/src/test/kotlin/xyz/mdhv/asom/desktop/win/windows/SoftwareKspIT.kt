package xyz.mdhv.asom.desktop.win.windows

import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xyz.mdhv.asom.desktop.win.acl.AclPlan
import xyz.mdhv.asom.desktop.win.acl.AclSnapshot
import xyz.mdhv.asom.desktop.win.api.CngProvider
import xyz.mdhv.asom.desktop.win.api.KeyScope
import xyz.mdhv.asom.desktop.win.fakes.FakeAcl
import xyz.mdhv.asom.desktop.win.fakes.OWNER_SID
import xyz.mdhv.asom.desktop.win.fakes.Report
import xyz.mdhv.asom.desktop.win.jna.JnaCng
import xyz.mdhv.asom.desktop.win.jna.JnaDpapi
import xyz.mdhv.asom.desktop.win.keys.Es256
import xyz.mdhv.asom.desktop.win.keys.FileNik
import xyz.mdhv.asom.desktop.win.keys.KeyTierRequest
import xyz.mdhv.asom.desktop.win.keys.NcryptNik
import xyz.mdhv.asom.desktop.win.keys.NikTier
import xyz.mdhv.asom.desktop.win.keys.NikTierSelector

@EnabledOnOs(OS.WINDOWS)
class SoftwareKspIT {
    private val name = "asom-nik-it-" + UUID.randomUUID()

    /** JCA `SHA256withECDSA` takes DER; the wire form is r||s, so the conversion is part of what is under test. */
    private fun rawToDer(raw: ByteArray): ByteArray {
        fun int(b: ByteArray): ByteArray {
            var v = BigInteger(1, b).toByteArray()
            if (v.isEmpty()) v = byteArrayOf(0)
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val r = int(raw.copyOfRange(0, 32))
        val s = int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }

    @Test
    fun `1000 sign and verify round trips through the Software KSP in raw and DER form, zero failures`() {
        val cng = JnaCng()
        val key = NcryptNik.create(cng, NikTier.T1_OS_KEYSTORE, name, KeyScope.USER)
        try {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key.spki()))
            val rnd = SecureRandom()
            var failures = 0
            repeat(1000) {
                val msg = ByteArray(1 + rnd.nextInt(300)).also(rnd::nextBytes)
                val raw = key.sign(msg)
                if (raw.size != 64 || !Es256.verify(key.spki(), msg, raw)) failures++
                val der = Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update(msg) }
                if (!der.verify(rawToDer(raw))) failures++
            }
            Report.line("IT SoftwareKspIT: 1000 sign/verify round trips (r||s via P1363 and via DER), failures=$failures (CI hosted VM)")
            assertEquals(0, failures)
        } finally {
            key.delete()
        }
    }

    @Test
    fun `the key persists, reopens with the same public key, and is gone after delete`() {
        val cng = JnaCng()
        val n = "$name-persist"
        val created = NcryptNik.create(cng, NikTier.T1_OS_KEYSTORE, n, KeyScope.USER)
        val spki = created.spki()
        created.close()
        val reopened = NcryptNik.open(cng, NikTier.T1_OS_KEYSTORE, n, KeyScope.USER)
        assertNotNull(reopened)
        assertTrue(spki.contentEquals(reopened.spki()))
        val second = try { NcryptNik.create(cng, NikTier.T1_OS_KEYSTORE, n, KeyScope.USER); false } catch (_: Exception) { true }
        assertTrue(second, "creating a key of an existing name must fail, never overwrite")
        reopened.delete()
        assertNull(NcryptNik.open(cng, NikTier.T1_OS_KEYSTORE, n, KeyScope.USER), "deleted")
    }

    @Test
    fun `the tier selector lands on the Software KSP when no TPM answers, and never on the file tier`() {
        val cng = JnaCng()
        val n = "$name-select"
        val sel = NikTierSelector.select(
            KeyTierRequest.AUTO,
            t2 = { NcryptNik.create(cng, NikTier.T2_TPM, n, KeyScope.USER) },
            t1 = { NcryptNik.create(cng, NikTier.T1_OS_KEYSTORE, n, KeyScope.USER) },
            t0 = { error("T0 must not be asked for") },
        )
        try {
            Report.line("IT SoftwareKspIT: tier selected on this runner = ${sel.tier.id} (${sel.tier.label}); attempts=${sel.attempts.map { "${it.tier.id}:${it.ok}" }}")
            assertTrue(sel.tier == NikTier.T1_OS_KEYSTORE || sel.tier == NikTier.T2_TPM)
        } finally {
            sel.key.delete()
        }
    }
}
