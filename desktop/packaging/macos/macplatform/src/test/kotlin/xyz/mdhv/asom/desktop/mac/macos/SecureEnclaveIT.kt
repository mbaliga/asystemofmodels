package xyz.mdhv.asom.desktop.mac.macos

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.KeyStorage
import xyz.mdhv.asom.desktop.NodePaths
import xyz.mdhv.asom.desktop.mac.MacPaths
import xyz.mdhv.asom.desktop.mac.Report
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.HelperProcess
import xyz.mdhv.asom.desktop.mac.helper.ProcessHelperLauncher
import xyz.mdhv.asom.desktop.mac.keys.Es256
import xyz.mdhv.asom.desktop.mac.keys.KeyTierRequest
import xyz.mdhv.asom.desktop.mac.keys.MacNikStore
import xyz.mdhv.asom.desktop.mac.keys.NikTier

/**
 * The Secure Enclave from the real helper (assumption AM01; spike S-M1 on the owner's Mac). SKIPPED when the helper reports
 * `se: false`, which is what a hosted VM reports (AM02): a skip is then the honest result and the S-M1 item stays open
 * (NEEDS-DEVICE-VALIDATION). On an Apple-silicon Mac it creates a real Enclave key, signs, verifies with the JCA, and records sign latency.
 */
@EnabledOnOs(OS.MAC)
class SecureEnclaveIT {
    private val helper = MacIT.helperPath()
    private val random = SecureRandom()

    @Test
    fun `create, sign and self-test a real Secure Enclave key, verify with the JCA, record latency`() {
        HelperProcess(ProcessHelperLauncher(helper)).use { p ->
            val c = HelperClient(p)
            val hello = c.hello()
            Report.line("IT SecureEnclaveIT: hello.se=${hello.se} on ${hello.model} (macOS ${hello.macos}, ${hello.arch})")
            assumeTrue(hello.se, "SKIPPED: this Mac reports no Secure Enclave (a hosted VM, AM02); S-M1 stays NEEDS-DEVICE-VALIDATION")
            val key = c.seCreate()
            assertTrue(Es256.isP256Spki(key.spki), "the Enclave's public key is a P-256 SubjectPublicKeyInfo")
            assertTrue(c.seSelftest(key.blob))
            val times = ArrayList<Long>()
            repeat(20) {
                val msg = ByteArray(200).also(random::nextBytes)
                val t0 = System.nanoTime()
                val sig = c.seSign(key.blob, msg)
                times += (System.nanoTime() - t0) / 1_000
                assertEquals(64, sig.size)
                assertTrue(Es256.verify(key.spki, msg, sig), "a real Enclave signature verifies with the JCA (the r||s form)")
            }
            times.sort()
            Report.line("IT SecureEnclaveIT: 20 signatures verified; sign latency p50=${times[10]}us p95=${times[19]}us (NDV evidence when run on the owner's Mac)")
        }
    }

    @Test
    fun `the store makes a T2 identity end to end on a Mac that has an Enclave`() {
        HelperProcess(ProcessHelperLauncher(helper)).use { p ->
            val c = HelperClient(p)
            assumeTrue(c.hello().se, "SKIPPED: no Secure Enclave (AM02)")
            val root = Files.createTempDirectory("asom-se-it-")
            val dir = root.resolve("node")
            MacPaths.createPrivateDir(dir)
            val uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Int
            val paths = NodePaths(HostMode.FOREGROUND, root, root, root.resolve("ledger"), dir, root.resolve("run"), root.resolve("run/ctl.sock"))
            val store = MacNikStore(paths, c, null) { uid }
            val made = store.createAtFirstMeshEnable(KeyTierRequest.AUTO)
            assertEquals(NikTier.T2_SECURE_ENCLAVE, made.tier)
            assertEquals(KeyStorage.SECURE_ENCLAVE, store.keyStorage)
            val again = store.existing()!!
            assertEquals(NikTier.T2_SECURE_ENCLAVE, again.tier)
            Report.line("IT SecureEnclaveIT: keyStorage=secure-enclave (self-reported), identity reopened and self-tested")
        }
    }
}
