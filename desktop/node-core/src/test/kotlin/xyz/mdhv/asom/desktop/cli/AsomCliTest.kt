package xyz.mdhv.asom.desktop.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.mdhv.asom.desktop.Captured
import xyz.mdhv.asom.desktop.ExitCodes
import xyz.mdhv.asom.desktop.FakePlatform
import xyz.mdhv.asom.desktop.HostFinder
import xyz.mdhv.asom.desktop.HostLookup
import xyz.mdhv.asom.desktop.Report
import xyz.mdhv.asom.desktop.json.StrictJson

class AsomCliTest {
    private fun run(vararg args: String, user: String = "alice", platform: FakePlatform = FakePlatform()): Triple<Int, String, String> {
        val cap = Captured()
        val code = AsomCli(cap.env(user), platform).run(args.toList())
        return Triple(code, cap.outText, cap.errText)
    }

    @Test
    fun `status --json prints the plan's shape with the four leading keys and integers only`() {
        val (code, out, _) = run("status", "--json")
        assertEquals(ExitCodes.OK, code)
        val line = out.trim()
        assertTrue(line.startsWith("""{"host":"foreground","fsm":"OFF","listeners":[],"locks":[],"""), line)
        val o = StrictJson.parse(line) as JsonObject // integer-profile parse: also proves no float or duplicate key
        assertEquals(listOf("host", "fsm", "listeners", "locks"), o.keys.take(4))
        assertEquals(JsonArray(emptyList()), o["listeners"])
        assertEquals(JsonArray(emptyList()), o["locks"])
        assertEquals("local-snapshot", (o["source"] as JsonPrimitive).content)
        val nyi = (o["notYetImplemented"] as JsonArray).map { (it as JsonPrimitive).content }
        assertTrue(nyi.any { it.startsWith("control-socket") }, "the control socket is declared not yet implemented: $nyi")
        assertEquals(1, out.trim().lines().size, "one line of JSON")
    }

    @Test
    fun `status label follows the mode`() {
        assertTrue(run("status", "--json", "--mode=user").second.contains(""""host":"shared-uid""""))
        assertTrue(run("status", "--json", "--mode=system").second.contains(""""host":"dedicated-user""""))
    }

    @Test
    fun `human status says it is a local snapshot, not a live node`() {
        val (code, out, _) = run("status")
        assertEquals(ExitCodes.OK, code)
        assertTrue("not a live node" in out && "fsm:         OFF" in out, out)
    }

    @Test
    fun `every other command answers a typed NOT_IMPLEMENTED with exit 3`() {
        var n = 0
        for (name in AsomCli.STUB_NAMES) {
            val words = name.split(" ").toTypedArray()
            val (code, out, _) = run(*words, "--json")
            assertEquals(ExitCodes.NOT_IMPLEMENTED, code, name)
            val o = StrictJson.parse(out.trim()) as JsonObject
            assertEquals("NOT_IMPLEMENTED", (o["code"] as JsonPrimitive).content, name)
            assertEquals(name, (o["command"] as JsonPrimitive).content)
            assertEquals(false, (o["ok"] as JsonPrimitive).content.toBooleanStrict())
            val (code2, _, err2) = run(*words)
            assertEquals(ExitCodes.NOT_IMPLEMENTED, code2)
            assertTrue("NOT_IMPLEMENTED" in err2)
            n++
        }
        // the command surface named by the plan
        val surface = listOf("watch", "chat", "lend", "lan confirm", "unlock", "doctor", "install", "uninstall", "upgrade", "rollback", "bench", "ledger export")
        assertTrue(AsomCli.STUB_NAMES.containsAll(surface), "missing from the surface: " + (surface - AsomCli.STUB_NAMES.toSet()))
        Report.line("asom cli: $n stub commands answered NOT_IMPLEMENTED")
    }

    @Test
    fun `unknown commands and modes are usage errors and root is refused`() {
        assertEquals(ExitCodes.USAGE, run("frobnicate").first)
        assertEquals(ExitCodes.USAGE, run().first)
        assertEquals(ExitCodes.USAGE, run("status", "--mode=bogus").first)
        val (code, _, err) = run("status", "--json", user = "root")
        assertEquals(ExitCodes.REFUSED, code)
        assertTrue("root" in err)
    }

    @Test
    fun `a host that refuses the mode makes status exit REFUSED with the reason`() {
        val (code, out, err) = run("status", "--json", platform = FakePlatform(refuse = "nope: test refusal"))
        assertEquals(ExitCodes.REFUSED, code)
        assertEquals("", out)
        assertTrue("nope: test refusal" in err)
    }

    @Test
    fun `no host or two hosts is refused, never guessed`() {
        val none = Captured()
        assertEquals(ExitCodes.NO_HOST, runAsomCli(listOf("status"), none.env(), HostFinder.find(emptyList())))
        val two = Captured()
        val lookup = HostFinder.find(listOf(FakePlatform("a"), FakePlatform("b")))
        assertTrue(lookup is HostLookup.Ambiguous)
        assertEquals(ExitCodes.REFUSED, runAsomCli(listOf("status"), two.env(), lookup))
        assertTrue("a, b" in two.errText)
    }
}
