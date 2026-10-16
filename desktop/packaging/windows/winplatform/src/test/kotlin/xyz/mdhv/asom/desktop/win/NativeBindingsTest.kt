package xyz.mdhv.asom.desktop.win

import com.sun.jna.Native
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import xyz.mdhv.asom.desktop.win.exec.SystemProcessRunner
import xyz.mdhv.asom.desktop.win.fakes.offsetOf
import xyz.mdhv.asom.desktop.win.jna.DeviceNotifySubscribeParameters
import xyz.mdhv.asom.desktop.win.jna.JnaCng
import xyz.mdhv.asom.desktop.win.jna.LastInputInfo
import xyz.mdhv.asom.desktop.win.jna.ReasonContext
import xyz.mdhv.asom.desktop.win.jna.SystemPowerStatus

/** Struct layouts, constants and the read-only process allowlist: everything about the native layer that can be checked without Windows. */
class NativeBindingsTest {
    @Test
    fun `the structures the power and input calls pass have the sizes the Windows SDK gives them`() {
        assumeTrue(Native.POINTER_SIZE == 8, "64-bit layout")
        assertEquals(12, SystemPowerStatus().size(), "SYSTEM_POWER_STATUS")
        assertEquals(8, LastInputInfo().size(), "LASTINPUTINFO")
        assertEquals(8, LastInputInfo().cbSize, "cbSize is set to the structure size")
        val rc = ReasonContext()
        assertEquals(32, rc.size(), "REASON_CONTEXT with its 24-byte union")
        assertEquals(8L, rc.offsetOf("SimpleReasonString").toLong(), "the simple string pointer opens the union")
        assertEquals(1, rc.Flags, "POWER_REQUEST_CONTEXT_SIMPLE_STRING")
        assertEquals(0, rc.Version, "POWER_REQUEST_CONTEXT_VERSION")
        assertEquals(16, DeviceNotifySubscribeParameters().size(), "DEVICE_NOTIFY_SUBSCRIBE_PARAMETERS")
    }

    @Test
    fun `status codes and property values are the documented ones`() {
        assertEquals(0x80090016.toInt(), JnaCng.NTE_BAD_KEYSET)
        assertEquals(0x8009000D.toInt(), JnaCng.NTE_NO_KEY)
        assertEquals(0x20, JnaCng.NCRYPT_MACHINE_KEY_FLAG)
        assertEquals(0x2, JnaCng.NCRYPT_ALLOW_SIGNING_FLAG)
        assertEquals("ECDSA_P256", JnaCng.ALG_ECDSA_P256)
        assertContentEquals(byteArrayOf(2, 0, 0, 0), JnaCng.dword(2))
        assertContentEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), JnaCng.dword(0x12345678))
        assertFailsWith<xyz.mdhv.asom.desktop.win.api.WinApiException> { JnaCng.check("x", 5) }
        JnaCng.check("x", 0)
    }

    @Test
    fun `only the two read-only system tools may run, by absolute path, with their read-only arguments`() {
        val r = SystemProcessRunner("C:\\Windows")
        val netsh = "C:\\Windows\\System32\\netsh.exe"
        val powercfg = "C:\\Windows\\System32\\powercfg.exe"
        assertNull(r.refusal(netsh, listOf("advfirewall", "firewall", "show", "rule", "name=all", "dir=in", "verbose")))
        assertNull(r.refusal(netsh.uppercase(), listOf("advfirewall", "firewall", "show", "rule")))
        assertNull(r.refusal(powercfg, listOf("/query", "SCHEME_CURRENT", "a", "b")))
        val refused = mapOf(
            "relative name (PATH lookup)" to ("netsh.exe" to listOf("advfirewall", "firewall", "show", "rule")),
            "other directory" to ("C:\\Temp\\netsh.exe" to listOf("advfirewall", "firewall", "show", "rule")),
            "powershell" to ("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" to listOf("-Command", "Get-NetFirewallRule")),
            "cmd" to ("C:\\Windows\\System32\\cmd.exe" to listOf("/c", "dir")),
            "netsh add rule" to (netsh to listOf("advfirewall", "firewall", "add", "rule", "name=x")),
            "netsh set" to (netsh to listOf("advfirewall", "set", "allprofiles", "state", "off")),
            "netsh no args" to (netsh to emptyList()),
            "powercfg set" to (powercfg to listOf("/setacvalueindex", "SCHEME_CURRENT", "a", "b", "0")),
            "powercfg hibernate" to (powercfg to listOf("/hibernate", "off")),
            "control char" to (powercfg to listOf("/query", "a\nb")),
        )
        for ((name, c) in refused) assertNotNull(r.refusal(c.first, c.second), name)
        assertFailsWith<SecurityException> { r.run("cmd.exe", listOf("/c", "calc"), 1000) }
    }

    @Test
    fun `text files are read as UTF-8 with an explicit charset, a byte-order mark dropped and bad bytes replaced`() {
        val dir = Files.createTempDirectory("asom-text-")
        val bom = dir.resolve("bom.txt")
        Files.write(bom, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "TS_NO_LOGS_NO_SUPPORT=true\n".toByteArray())
        assertEquals("TS_NO_LOGS_NO_SUPPORT=true\n", JdkTextFiles.read(bom))
        val bad = dir.resolve("bad.txt")
        Files.write(bad, byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte()))
        assertEquals("a\uFFFDb", JdkTextFiles.read(bad))
        val utf8 = dir.resolve("utf8.txt")
        Files.write(utf8, "Ü\u00e9\u20ac".toByteArray(Charsets.UTF_8))
        assertEquals("Ü\u00e9\u20ac", JdkTextFiles.read(utf8), "the default charset of the JVM must not matter (AW20)")
        assertNull(JdkTextFiles.read(dir.resolve("missing.txt")))
        assertNull(JdkTextFiles.read(dir), "a directory is not a file")
        val big = dir.resolve("big.txt")
        Files.write(big, ByteArray((1 shl 20) + 1) { 'x'.code.toByte() })
        assertNull(JdkTextFiles.read(big), "a file over 1 MiB is not read")
        assertTrue(Files.exists(big))
    }
}
