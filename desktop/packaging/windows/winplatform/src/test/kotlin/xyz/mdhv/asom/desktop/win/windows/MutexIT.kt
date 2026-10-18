package xyz.mdhv.asom.desktop.win.windows

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.win.NodeMutex
import xyz.mdhv.asom.desktop.win.api.MutexResult
import xyz.mdhv.asom.desktop.win.fakes.Report
import xyz.mdhv.asom.desktop.win.jna.Kernel32Mutex

/** CI-ONLY (hosted Windows runner; SKIPPED elsewhere). A test-only mutex name is used for the raw API; the real name only for `NodeMutex`. */
@EnabledOnOs(OS.WINDOWS)
class MutexIT {
    @Test
    fun `a named mutex is acquired once, reports HeldByOther the second time, and can be taken again after release`() {
        val port = Kernel32Mutex()
        val name = "Global\\asom-it-" + UUID.randomUUID()
        val first = port.tryAcquire(name)
        assertTrue(first is MutexResult.Acquired, first.toString())
        val second = port.tryAcquire(name)
        Report.line("IT MutexIT: second acquire of an existing named mutex = ${second::class.simpleName}")
        assertEquals(MutexResult.HeldByOther, second)
        (first as MutexResult.Acquired).handle.close()
        val third = port.tryAcquire(name)
        assertTrue(third is MutexResult.Acquired, "after the only handle closed the mutex no longer exists: $third")
        (third as MutexResult.Acquired).handle.close()
    }

    @Test
    fun `NodeMutex on the real name refuses a second node`() {
        val held = NodeMutex(Kernel32Mutex()).acquire()
        try {
            val e = assertFailsWith<HostRefusedException> { NodeMutex(Kernel32Mutex()).acquire() }
            assertTrue("another asom node already runs" in e.message!!, e.message)
        } finally {
            held.close()
        }
        NodeMutex(Kernel32Mutex()).acquire().close()
    }
}
