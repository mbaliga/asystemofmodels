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
class PcpIT {
    @Test
    fun `the Platform Crypto Provider signs and verifies when a TPM answers, and skips with a reason when none does`() {
        val cng = JnaCng()
        val name = "asom-nik-it-pcp-" + UUID.randomUUID()
        val key = try {
            NcryptNik.create(cng, NikTier.T2_TPM, name, KeyScope.USER)
        } catch (e: Exception) {
            Report.line("IT PcpIT: SKIPPED: no TPM (${e.message}) (CI hosted VM; AW11; S-W1 stays NEEDS-DEVICE-VALIDATION)")
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "SKIPPED: no TPM (${e.message})")
            return
        }
        try {
            val times = ArrayList<Long>()
            val rnd = SecureRandom()
            repeat(50) {
                val msg = ByteArray(64).also(rnd::nextBytes)
                val t0 = System.nanoTime()
                val sig = key.sign(msg)
                times += (System.nanoTime() - t0) / 1_000
                assertTrue(Es256.verify(key.spki(), msg, sig), "AW02: the TPM signature is r||s and verifies with the JCA")
            }
            times.sort()
            Report.line("IT PcpIT: keyStorage=tpm sign latency p50=${times[times.size / 2]}us p95=${times[(times.size * 95) / 100]}us (self-reported; not attestation)")
        } finally {
            key.delete()
        }
        assertNotNull(CngProvider.PLATFORM)
    }
}
