package xyz.mdhv.asom.desktop.mac.macos

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xyz.mdhv.asom.desktop.mac.Report
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.HelperCodec
import xyz.mdhv.asom.desktop.mac.helper.HelperProcess
import xyz.mdhv.asom.desktop.mac.helper.HelperUnavailable
import xyz.mdhv.asom.desktop.mac.helper.ProcessHelperLauncher
import xyz.mdhv.asom.desktop.mac.helper.ProtocolSpec
import xyz.mdhv.asom.desktop.mac.helper.Reply

/**
 * The REAL `asom-mac-helper` on a Mac (CI: the macos-latest job; LAB/CI (hosted VM) evidence, NOT device evidence). Values a hosted
 * VM reports are recorded, never asserted: `se` is expected false there (AM02), and a VM's power, thermal and GPU numbers are not
 * representative. What IS asserted: the real helper's bytes decode under the strict codec, and it refuses hostile input the way
 * the schema says.
 */
@EnabledOnOs(OS.MAC)
class HelperIT {
    private val helper = MacIT.helperPath()

    private fun raw(vararg lines: String, stdinCloseAfterMs: Long = 0): List<String> {
        val pb = ProcessBuilder(helper.toString(), "serve")
        pb.environment().clear()
        pb.redirectError(ProcessBuilder.Redirect.PIPE)
        val p = pb.start()
        p.outputStream.use { o ->
            for (l in lines) { o.write(l.toByteArray(Charsets.UTF_8)); o.write('\n'.code); o.flush() }
            if (stdinCloseAfterMs > 0) Thread.sleep(stdinCloseAfterMs)
        }
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readLines()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "the helper exits when its stdin closes")
        assertEquals(0, p.exitValue())
        val err = p.errorStream.readBytes()
        assertEquals(0, err.size, "the helper writes nothing to stderr: ${String(err)}")
        return out
    }

    @Test
    fun `printf hello into serve prints one line with ok true`() {
        val out = raw("{\"op\":\"hello\",\"v\":1}")
        assertEquals(1, out.size)
        assertTrue("\"ok\":true" in out[0], out[0])
        Report.line("IT HelperIT: hello -> ${out[0]}")
        val hello = HelperCodec.decodeReply("hello", out[0].toByteArray()) as Reply.Success
        Report.line("IT HelperIT: se=${hello.fields.bool("se")} (AM02: expected false on a hosted runner, true on the owner's Apple-silicon Mac)")
    }

    @Test
    fun `every read-only op answers something the strict codec accepts, and unavailable is an answer`() {
        HelperProcess(ProcessHelperLauncher(listOf(helper.toString(), "serve"), System.getenv())).use { p ->
            val c = HelperClient(p)
            val hello = c.hello()
            assertTrue(hello.helper.isNotEmpty() && hello.macos.isNotEmpty())
            fun <T> probe(name: String, f: () -> T): T? = try {
                f().also { Report.line("IT HelperIT: $name -> $it") }
            } catch (e: HelperUnavailable) {
                Report.line("IT HelperIT: $name -> UNAVAILABLE (${e.message})")
                null
            }
            probe("power.get") { c.power() }
            probe("thermal.get") { c.thermal() }?.let { assertTrue(it in ProtocolSpec.THERMAL_STATES) }
            probe("presence.get") { c.presence() }
            probe("gpu.get") { c.gpuUtilPermille() }
            probe("mem.get") { c.mem() }?.let { assertTrue(it.physicalBytes > 0) }
            probe("platform.uuid") { c.platformDigest().size }?.let { assertEquals(32, it) }
            probe("paths.get") { c.userTempDir() }?.let { assertTrue(it.startsWith("/")) }
            probe("svc.status agent") { c.svc("status", "agent") }
            probe("svc.status daemon") { c.svc("status", "daemon") }
        }
    }

    @Test
    fun `hostile lines are answered with the schema's errors and the helper keeps working`() {
        val out = raw(
            "{\"op\":\"nope\",\"id\":1}",
            "not json",
            "{\"op\":\"assert.hold\",\"id\":2,\"reason\":\"keep awake\"}",
            "{\"op\":\"hello\",\"id\":3,\"v\":2}",
            "{\"op\":\"backup.exclude\",\"path\":\"/${"A".repeat(ProtocolSpec.MAX_LINE_BYTES)}\"}",
            "{\"op\":\"hello\",\"id\":4,\"v\":1}",
        )
        assertEquals(6, out.size, out.toString())
        assertEquals("{\"ok\":false,\"id\":1,\"code\":\"UNKNOWN_OP\",\"message\":\"UNKNOWN_OP\"}", out[0])
        assertEquals("{\"ok\":false,\"code\":\"BAD_REQUEST\",\"message\":\"MALFORMED_JSON\"}", out[1])
        assertEquals("{\"ok\":false,\"id\":2,\"code\":\"BAD_REQUEST\",\"message\":\"BAD_FIELD\"}", out[2])
        assertEquals("{\"ok\":false,\"id\":3,\"code\":\"UNSUPPORTED_VERSION\",\"message\":\"protocol version 1 only\"}", out[3])
        assertEquals("{\"ok\":false,\"code\":\"BAD_REQUEST\",\"message\":\"LINE_TOO_LONG\"}", out[4])
        assertTrue(out[5].startsWith("{\"ok\":true,\"id\":4,"), "after an overlong line the helper is in sync again: ${out[5]}")
    }

    @Test
    fun `a partial last line is dropped unanswered and EOF is the shutdown`() {
        val pb = ProcessBuilder(helper.toString(), "serve")
        pb.environment().clear()
        val p = pb.start()
        p.outputStream.write("{\"op\":\"hello\",\"v\":1}".toByteArray()) // no LF
        p.outputStream.close()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue())
        assertEquals(0, ByteArrayOutputStream().also { it.write(p.inputStream.readBytes()) }.size(), "no answer to an unterminated line")
    }

    @Test
    fun `a helper started with the wrong arguments refuses`() {
        val p = ProcessBuilder(helper.toString(), "frobnicate").also { it.environment().clear() }.start()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS))
        assertEquals(64, p.exitValue())
    }
}
