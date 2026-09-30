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

/**
 * CI-ONLY: these run on a hosted Windows runner and report SKIPPED everywhere else. Evidence label when they pass:
 * "CI (hosted VM) evidence", NOT device evidence (no TPM is expected on a hosted VM, AW11).
 */
@EnabledOnOs(OS.WINDOWS)
class DpapiIT {
    @Test
    fun `DPAPI round-trips user-scope data and the wrapped bytes are not the plain bytes`() {
        val d = JnaDpapi()
        val plain = "asom node key material".toByteArray(Charsets.UTF_8) + ByteArray(64) { it.toByte() }
        val wrapped = d.protect(plain)
        assertFalse(wrapped.contentEquals(plain))
        assertTrue(wrapped.size > plain.size)
        assertTrue(plain.contentEquals(d.unprotect(wrapped)))
        val tampered = wrapped.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val failed = try { d.unprotect(tampered); false } catch (_: Exception) { true }
        assertTrue(failed, "a tampered blob must not unwrap")
        Report.line("IT DpapiIT: round trip ok, tamper rejected (CI hosted VM)")
    }

    @Test
    fun `a T0 key file wrapped by real DPAPI reopens to the same key`() {
        val plan = AclPlan.userState(OWNER_SID)
        val acl = FakeAcl(OWNER_SID)
        val dir = Files.createTempDirectory("asom-it-t0-")
        try {
            acl.replace(dir, plan.required)
            acl.store[dir.resolve(FileNik.FILE_NAME)] = AclSnapshot(OWNER_SID, plan.required)
            val key = FileNik.create(dir, JnaDpapi(), acl, plan)
            val msg = "manifest presentation".toByteArray()
            val back = FileNik.open(dir, JnaDpapi())!!
            assertTrue(key.spki().contentEquals(back.spki()))
            assertTrue(Es256.verify(key.spki(), msg, back.sign(msg)))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
