package xyz.mdhv.asom.desktop.mac

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.mac.fakes.FakeHelper
import xyz.mdhv.asom.desktop.mac.fakes.FixtureMachine
import xyz.mdhv.asom.desktop.mac.fakes.ReplayHelper
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.HelperFailure
import xyz.mdhv.asom.desktop.mac.helper.HelperLostException
import xyz.mdhv.asom.desktop.mac.helper.HelperUnavailable
import xyz.mdhv.asom.desktop.mac.helper.ProtocolSpec
import xyz.mdhv.asom.desktop.mac.helper.Reply

/** The typed client over the recorded exchanges (the same bytes the Swift dispatcher is pinned to) and over scripted failures. */
class HelperClientTest {
    @Test
    fun `every typed call replays against the recorded fixture exchanges`() {
        val replay = ReplayHelper()
        val c = HelperClient(replay)
        val hello = c.hello()
        assertEquals(HelperClient.Hello("0.1.0", "27.0.1", "arm64", true, "Mac14,3"), hello)
        val key = c.seCreate()
        assertContentEquals(FixtureMachine.blob, key.blob)
        assertContentEquals(FixtureMachine.spki, key.spki)
        assertContentEquals(FixtureMachine.signature, c.seSign(FixtureMachine.blob, ByteArray(0)))
        assertTrue(c.seSelftest(FixtureMachine.blob))
        assertEquals(HelperClient.PowerInfo("ac", true, 870, false), c.power())
        assertEquals("nominal", c.thermal())
        assertEquals(HelperClient.PresenceInfo(725_000, false, true), c.presence())
        assertEquals(137, c.gpuUtilPermille())
        assertEquals(HelperClient.MemInfo(17_179_869_184, 11_453_251_584), c.mem())
        c.assertHold()
        c.assertRelease()
        c.sleepAck(7)
        assertEquals("notRegistered", c.svc("status", "agent"))
        assertEquals("notFound", c.svc("status", "daemon"))
        assertEquals("requiresApproval", c.svc("register", "agent"))
        assertEquals("notFound", c.svc("register", "daemon"))
        assertEquals("notRegistered", c.svc("unregister", "agent"))
        c.backupExclude("/tmp/asom")
        assertContentEquals(FixtureMachine.platformDigest, c.platformDigest())
        assertEquals("/var/folders/zz/fixture/T/", c.userTempDir())
        assertTrue(replay.replayed.get() == 20, "non-vacuity: ${replay.replayed.get()} exchanges replayed")
    }

    @Test
    fun `recorded failures become typed exceptions`() {
        val c = HelperClient(ReplayHelper())
        val e = assertFailsWith<HelperFailure> { c.seSign(byteArrayOf(0, 1, 2), "hello".toByteArray()) }
        assertEquals("FAILED", e.code)
        val e2 = assertFailsWith<HelperFailure> { c.sleepAck(8) }
        assertEquals("BAD_REQUEST", e2.code)
        val e3 = assertFailsWith<HelperFailure> { c.backupExclude("/Users/x/asom") }
        assertEquals("FAILED", e3.code)
    }

    @Test
    fun `UNAVAILABLE is its own exception and a lost helper is not a helper failure`() {
        val h = FakeHelper()
        h.failures["gpu.get"] = Reply.Failure(null, "UNAVAILABLE", "no GPU utilisation counter")
        val c = HelperClient(h)
        assertFailsWith<HelperUnavailable> { c.gpuUtilPermille() }
        h.lost = "the helper is gone"
        assertFailsWith<HelperLostException> { c.thermal() }
    }

    @Test
    fun `the client sends exactly the requests the protocol allows, every op at least once`() {
        val h = FakeHelper()
        val c = HelperClient(h)
        val key = c.hello().let { c.seCreate() }
        c.seSign(key.blob, ByteArray(3)); c.seSelftest(key.blob)
        c.power(); c.thermal(); c.presence(); c.gpuUtilPermille(); c.mem(); c.assertHold(); c.assertRelease(); c.sleepAck(7)
        c.svc("status", "agent"); c.svc("register", "agent"); c.svc("unregister", "agent"); c.backupExclude("/tmp/x")
        c.platformDigest(); c.userTempDir()
        assertEquals(ProtocolSpec.requestOps.toSet(), h.calls.toSet(), "every protocol op is reachable through the typed client")
        assertFailsWith<IllegalArgumentException> { c.svc("restart", "agent") }
    }
}
