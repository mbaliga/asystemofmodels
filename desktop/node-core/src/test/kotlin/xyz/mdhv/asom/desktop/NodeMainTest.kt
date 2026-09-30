package xyz.mdhv.asom.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.governor.LenderState

class NodeMainTest {
    private val found = HostFinder.find(listOf(FakePlatform()))

    private fun run(vararg args: String, user: String = "alice", lookup: HostLookup = found, await: (NodeRuntime) -> Unit = {}): Triple<Int, String, String> {
        val cap = Captured()
        val code = NodeMain.run(args.toList(), cap.env(user), lookup, await)
        return Triple(code, cap.outText, cap.errText)
    }

    @Test
    fun `a mode is required and unknown arguments are usage errors`() {
        assertEquals(ExitCodes.USAGE, run().first)
        assertEquals(ExitCodes.USAGE, run("--mode=bogus").first)
        assertEquals(ExitCodes.USAGE, run("--listen=0.0.0.0").first)
        assertEquals(ExitCodes.USAGE, run("--foreground", "--port=11436").first)
    }

    @Test
    fun `root is refused in every mode before any host is looked up`() {
        for (m in listOf("--mode=system", "--mode=user", "--mode=foreground", "--mode=selftest", "--foreground", "--self-test")) {
            val (code, out, err) = run(m, user = "root", lookup = HostLookup.None)
            assertEquals(ExitCodes.REFUSED, code, m)
            assertEquals("", out)
            assertTrue("root" in err, m)
        }
    }

    @Test
    fun `no host is exit 69 and a host that refuses the mode is exit 78`() {
        assertEquals(ExitCodes.NO_HOST, run("--foreground", lookup = HostLookup.None).first)
        val refusing = HostFinder.find(listOf(FakePlatform(refuse = "SYSTEM mode must run as user asom")))
        val (code, _, err) = run("--mode=system", lookup = refusing)
        assertEquals(ExitCodes.REFUSED, code)
        assertTrue("must run as user asom" in err)
    }

    @Test
    fun `foreground starts OFF with no listener, prints only a banner on stderr, and drains at shutdown`() {
        var rt: NodeRuntime? = null
        val (code, out, err) = run("--foreground") { r ->
            rt = r
            assertEquals(LenderState.OFF, r.fsm.state)
            r.enableLending()
            assertEquals(LenderState.ARMED, r.fsm.state)
            r.shutdown()
        }
        assertEquals(ExitCodes.OK, code)
        assertEquals("", out, "stdout stays empty")
        assertTrue("listeners=none" in err && "NOT_YET_IMPLEMENTED(DL2)" in err && "UNSIGNED" in err, err)
        assertEquals(LenderState.OFF, assertNotNull(rt).fsm.state)
    }

    @Test
    fun `selftest reports each not-yet-implemented member by name and exits 0 when nothing failed`() {
        val (code, out, _) = run("--mode=selftest")
        assertEquals(ExitCodes.OK, code, out)
        assertTrue("[not-yet-implemented] control-socket: DL2" in out, out)
        assertTrue(out.lines().any { it.startsWith("  [not-yet-implemented] nik-store") }, out)
        assertTrue("[ok] ledger-roundtrip" in out && "[ok] engine: NoopEngine" in out, out)
        assertTrue(out.trim().endsWith("0 failed"), out)
        assertEquals(run("--self-test").second.substringAfter('\n'), out.substringAfter('\n'), "alias")
    }

    @Test
    fun `a member declared not yet implemented that actually works is a selftest FAIL, not a pass`() {
        val lying = FakePlatform(extraNyi = listOf(NotYetImplementedFeature("keep-awake", "DL2") { /* works! */ }))
        val (code, out, _) = run("--mode=selftest", lookup = HostFinder.find(listOf(lying)))
        assertEquals(ExitCodes.ERROR, code)
        assertTrue("[FAIL] keep-awake" in out && "the call succeeded" in out, out)
        val wrong = FakePlatform(extraNyi = listOf(NotYetImplementedFeature("sleep", "DL2") { error("boom") }))
        val (code2, out2, _) = run("--mode=selftest", lookup = HostFinder.find(listOf(wrong)))
        assertEquals(ExitCodes.ERROR, code2)
        assertTrue("[FAIL] sleep" in out2, out2)
    }

    @Test
    fun `a bad config file is a usage error and a good one is accepted`() {
        val dir = Files.createTempDirectory("asom-cfg-")
        try {
            val bad = dir.resolve("bad.json").also { Files.writeString(it, """{"presenceHoldDownMs":1}""") }
            val (code, _, err) = run("--foreground", "--config=$bad")
            assertEquals(ExitCodes.USAGE, code)
            assertTrue("bad config" in err)
            val good = dir.resolve("good.json").also { Files.writeString(it, """{"graceMsDeck":1500}""") }
            var cfg: NodeConfig? = null
            assertEquals(ExitCodes.OK, run("--foreground", "--config=$good") { cfg = it.config }.first)
            assertEquals(1500, assertNotNull(cfg).graceMsDeck)
            assertEquals(ExitCodes.USAGE, run("--foreground", "--config=${dir.resolve("missing.json")}").first)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
