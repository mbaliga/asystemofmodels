package xyz.mdhv.asom.ut

import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString

/**
 * HLU-3 (ERRATA ERR-FX-UT-3): the watchdog reports a suspend only for a real stall of its own thread. Work that takes long, such as
 * the self-test (a TLS handshake, 194 vectors, 100 forced appends: seconds on a cold phone), must not hold the session lock, and a
 * tick measures the gap from the moment its thread woke, not from the moment it got the lock.
 */
class WatchdogLockTest {
    private class Rig(
        selfTest: () -> JObject = { JObject(listOf("selftest" to JString("ok"))) },
        ledger: LedgerPort = EmptyLedger,
    ) {
        val frames = CopyOnWriteArrayList<NodeFrame>()
        val clock = FakeClock()
        val pin = PipedOutputStream()
        private val pout = PipedInputStream(pin, 1 shl 16)
        val session = NodeSession(LineReader(pout), { frames += it }, NodeLifecycle(clock), Diag(ByteArrayOutputStream()), { ledger }, selfTest)
        val done = CountDownLatch(1)
        @Volatile var rc = -1
        val thread = Thread { rc = session.run(); done.countDown() }.also { it.isDaemon = true; it.start() }

        fun send(s: String) {
            pin.write((s + "\n").toByteArray())
            pin.flush()
        }

        fun await(what: String, cond: () -> Boolean) {
            val end = System.nanoTime() + 10_000_000_000L
            while (!cond() && System.nanoTime() < end) Thread.sleep(2)
            assertTrue(cond(), "timed out waiting for $what")
        }

        fun states(): List<String> = frames.filterIsInstance<NodeFrame.State>().map { it.node }

        fun activeWithPeersOpen() {
            send("""{"t":"hello","v":1}""")
            send("""{"t":"lifecycle","state":"active"}""")
            send("""{"t":"peers","op":"open"}""")
            await("the peers list") { frames.any { it is NodeFrame.PeersList } }
        }

        fun close() {
            pin.close()
            done.await(10, TimeUnit.SECONDS)
        }

        /** Runs one tick on its own thread; false when it has not returned within [ms]. */
        fun tickReturnsWithin(ms: Long): Boolean {
            val t = Thread { session.tick() }.also { it.isDaemon = true; it.start() }
            t.join(ms)
            return !t.isAlive
        }
    }

    private object EmptyLedger : LedgerPort {
        override fun rows(since: Long, limit: Int): List<JObject> = emptyList()
        override fun close() {}
    }

    @Test
    fun aSelfTestThatTakesTenSecondsDoesNotMakeTheWatchdogReportASuspend() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rig = Rig(selfTest = {
            started.countDown()
            release.await(30, TimeUnit.SECONDS)
            JObject(listOf("selftest" to JString("ok")))
        })
        try {
            rig.activeWithPeersOpen()
            rig.send("""{"t":"selftest"}""")
            assertTrue(started.await(10, TimeUnit.SECONDS), "the self-test never started")
            for (ms in 1000..9000 step 1000) {
                rig.clock.now = ms.toLong()
                assertTrue(rig.tickReturnsWithin(2000), "the tick at $ms ms is stuck behind the self-test: it holds the session lock")
                rig.send("""{"t":"lifecycle","state":"active"}""")
            }
            release.countDown()
            rig.await("the self-test frame") { rig.frames.any { it is NodeFrame.SelfTestResult } }
            assertEquals(listOf("idle", "active"), rig.states(), "a slow self-test is not a suspend: nothing may be announced as interrupted")
            rig.send("""{"t":"shutdown"}""")
            assertTrue(rig.done.await(10, TimeUnit.SECONDS))
            assertEquals(0, rig.rc)
        } finally {
            release.countDown()
            rig.close()
        }
    }

    @Test
    fun theSelfTestResultStillComesBeforeTheShutdownItPrecedes() {
        val release = CountDownLatch(1)
        val rig = Rig(selfTest = {
            release.await(30, TimeUnit.SECONDS)
            JObject(listOf("selftest" to JString("ok")))
        })
        try {
            rig.send("""{"t":"hello","v":1}""")
            rig.send("""{"t":"selftest"}""")
            rig.send("""{"t":"shutdown"}""")
            Thread.sleep(100)
            assertTrue(rig.frames.none { it is NodeFrame.SelfTestResult })
            assertEquals(-1, rig.rc, "the session must wait for the self-test it was asked for before it ends")
            release.countDown()
            assertTrue(rig.done.await(10, TimeUnit.SECONDS))
            assertEquals(0, rig.rc)
            val types = rig.frames.map { it::class.simpleName }
            assertEquals("SelfTestResult", types.last { it != "State" }, "frames: $types")
        } finally {
            release.countDown()
            rig.close()
        }
    }

    @Test
    fun aTickMeasuresTheGapFromTheMomentItsThreadWokeNotFromWhenItGotTheLock() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slowLedger = object : LedgerPort {
            override fun rows(since: Long, limit: Int): List<JObject> {
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
                return emptyList()
            }

            override fun close() {}
        }
        val rig = Rig(ledger = slowLedger)
        try {
            rig.activeWithPeersOpen()
            rig.clock.now = 1000
            rig.session.tick()
            rig.send("""{"t":"ledger","since":0,"limit":1}""")
            assertTrue(started.await(10, TimeUnit.SECONDS))
            rig.clock.now = 2000
            val tick = Thread { rig.session.tick() }.also { it.isDaemon = true; it.start() }
            rig.await("the tick thread to wait for the lock") { tick.state == Thread.State.BLOCKED }
            rig.clock.now = 5000
            release.countDown()
            tick.join(10_000)
            rig.await("the ledger rows") { rig.frames.any { it is NodeFrame.Rows } }
            assertEquals(listOf("idle", "active"), rig.states(), "the tick woke 1000 ms after the last one; the 3000 ms spent waiting for the lock are not a stall")
        } finally {
            release.countDown()
            rig.close()
        }
    }
}
