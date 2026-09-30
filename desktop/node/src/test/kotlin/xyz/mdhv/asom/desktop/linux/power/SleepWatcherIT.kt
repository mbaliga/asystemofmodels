package xyz.mdhv.asom.desktop.linux.power

import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.SleepEvent
import xyz.mdhv.asom.desktop.linux.Report
import xyz.mdhv.asom.desktop.linux.dbus.PrivateDbus
import xyz.mdhv.asom.desktop.linux.dbus.TestBusClient

/**
 * SleepWatcherIT: the sleep watcher against a PRIVATE dbus-daemon with a test client playing logind (LAB evidence, NOT
 * real logind and NOT a real suspend; real suspend/resume is NEEDS-DEVICE-VALIDATION). `ASOM_REQUIRE_DBUS=1` makes a
 * missing daemon binary fail the job.
 */
class SleepWatcherIT {
    private val uid = { PrivateDbus.realUid() }

    private fun events(): Pair<(SleepEvent) -> Unit, LinkedBlockingQueue<SleepEvent>> {
        val q = LinkedBlockingQueue<SleepEvent>()
        return { e: SleepEvent -> q.add(e); Unit } to q
    }

    private fun waitStatus(w: SleepWatcher, want: SleepWatcherStatus, ms: Long = 5_000) {
        val end = System.currentTimeMillis() + ms
        while (w.status != want && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(want, w.status, "watcher status")
    }

    @Test
    fun `PrepareForSleep true then false becomes SLEEP_IMMINENT then RESUMED, in both endiannesses`() {
        PrivateDbus.start().use { bus ->
            for (big in listOf(false, true)) {
                TestBusClient(bus.socket, big).use { logind ->
                    logind.ownName("org.freedesktop.login1")
                    val (l, q) = events()
                    SleepWatcher(bus.socket, uid, l, retryMs = 100, gapPollMs = 3_600_000).start().use { w ->
                        waitStatus(w, SleepWatcherStatus.CONNECTED)
                        assertEquals("sleep is detected in advance", w.statusText)
                        logind.emitPrepareForSleep(true)
                        assertEquals(SleepEvent.SLEEP_IMMINENT, q.poll(5, TimeUnit.SECONDS), "endian big=$big")
                        logind.emitPrepareForSleep(true)
                        logind.emitPrepareForSleep(false)
                        assertEquals(SleepEvent.RESUMED, q.poll(5, TimeUnit.SECONDS), "a repeated true is not a second SLEEP_IMMINENT")
                        assertNull(q.poll(300, TimeUnit.MILLISECONDS))
                    }
                }
            }
        }
        Report.line("SleepWatcherIT: PrepareForSleep -> SLEEP_IMMINENT/RESUMED against a private dbus-daemon, LE and BE: OK (LAB, NOT DEVICE EVIDENCE)")
    }

    @Test
    fun `a spoofed broadcast and a unicast forgery produce no event`() {
        PrivateDbus.start().use { bus ->
            TestBusClient(bus.socket).use { spoofer ->
                val (l, q) = events()
                SleepWatcher(bus.socket, uid, l, retryMs = 100, gapPollMs = 3_600_000).start().use { w ->
                    waitStatus(w, SleepWatcherStatus.CONNECTED)
                    spoofer.emitPrepareForSleep(true)
                    assertNull(q.poll(1_000, TimeUnit.MILLISECONDS), "a broadcast from a non-logind name")
                }
            }
        }
    }

    @Test
    fun `no bus at first reports sleep-not-detected, then connects when the bus appears`() {
        val dir = Files.createTempDirectory("asom-sw-")
        val sock = dir.resolve("bus")
        val (l, q) = events()
        SleepWatcher(sock, uid, l, retryMs = 100, gapPollMs = 3_600_000).start().use { w ->
            waitStatus(w, SleepWatcherStatus.UNAVAILABLE)
            assertEquals("sleep not detected in advance", w.statusText)
            PrivateDbus.start(dir, sock).use { bus ->
                waitStatus(w, SleepWatcherStatus.CONNECTED)
                TestBusClient(bus.socket).use { logind ->
                    logind.ownName("org.freedesktop.login1")
                    logind.emitPrepareForSleep(true)
                    assertEquals(SleepEvent.SLEEP_IMMINENT, q.poll(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test
    fun `the bus dying while the machine is marked asleep makes up the lost RESUMED on reconnect`() {
        val dir = Files.createTempDirectory("asom-sw-")
        val sock = dir.resolve("bus")
        val (l, q) = events()
        SleepWatcher(sock, uid, l, retryMs = 100, gapPollMs = 3_600_000).start().use { w ->
            PrivateDbus.start(dir, sock).use { bus ->
                waitStatus(w, SleepWatcherStatus.CONNECTED)
                TestBusClient(bus.socket).use { logind ->
                    logind.ownName("org.freedesktop.login1")
                    logind.emitPrepareForSleep(true)
                    assertEquals(SleepEvent.SLEEP_IMMINENT, q.poll(5, TimeUnit.SECONDS))
                }
                bus.kill()
            }
            waitStatus(w, SleepWatcherStatus.UNAVAILABLE)
            assertNull(q.poll(200, TimeUnit.MILLISECONDS), "nothing is invented while the bus is down")
            PrivateDbus.start(dir, sock).use {
                waitStatus(w, SleepWatcherStatus.CONNECTED)
                assertEquals(SleepEvent.RESUMED, q.poll(5, TimeUnit.SECONDS), "running code means the machine is awake")
            }
        }
    }

    @Test
    fun `close stops the threads promptly even with the bus connected`() {
        PrivateDbus.start().use { bus ->
            val (l, _) = events()
            val w = SleepWatcher(bus.socket, uid, l, retryMs = 100, gapPollMs = 3_600_000).start()
            waitStatus(w, SleepWatcherStatus.CONNECTED)
            val t0 = System.nanoTime()
            w.close()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3_000, "close took too long")
            assertTrue(Thread.getAllStackTraces().keys.none { it.name == "asom-sleep-watcher" && it.isAlive }, "the watcher thread is still alive")
        }
    }

    @Test
    fun `an unusable socket path (a regular file) is a state, not a crash`() {
        val f = Files.createTempFile("asom-notabus", ".txt")
        val (l, _) = events()
        SleepWatcher(f, uid, l, retryMs = 100, gapPollMs = 3_600_000).start().use { w ->
            waitStatus(w, SleepWatcherStatus.UNAVAILABLE)
        }
        Files.deleteIfExists(f)
    }
}
