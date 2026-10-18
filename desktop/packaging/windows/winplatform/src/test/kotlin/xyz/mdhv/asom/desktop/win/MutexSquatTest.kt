package xyz.mdhv.asom.desktop.win

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.win.api.MutexResult
import xyz.mdhv.asom.desktop.win.fakes.FakeMutex

/**
 * A-LA-6: `Global\asom-node` is a fixed name, so any local account can create it first and make both modes refuse to start.
 * The refusal stays (fail closed), but its words must not claim a node runs when the node cannot know that.
 */
class MutexSquatTest {
    @Test
    fun `a mutex that cannot be opened is reported as either another account's node or a squatter, never as a known node`() {
        val msg = assertFailsWith<HostRefusedException> { NodeMutex(FakeMutex().also { it.result = MutexResult.AccessDenied }).acquire() }.message!!
        assertTrue("different account" in msg, msg)
        assertTrue("cannot be told apart" in msg && "squat" in msg, "the refusal must say that a node and a squatter look the same: $msg")
        assertTrue("Global\\asom-node" in msg, msg)
    }

    @Test
    fun `a mutex that opens is reported with the same caveat, and the existing wording is kept`() {
        val port = FakeMutex()
        port.held.add("Global\\asom-node")
        val msg = assertFailsWith<HostRefusedException> { NodeMutex(port).acquire() }.message!!
        assertTrue("another asom node already runs on this machine" in msg, msg)
        assertTrue("holder is not verified" in msg, "the holder of the name is not checked, and the message must not imply it is: $msg")
    }
}
