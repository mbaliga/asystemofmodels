package xyz.mdhv.asom.desktop.linux.dbus

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.linux.Report

/**
 * [MiniDbus] against a PRIVATE dbus-daemon (evidence label: LAB; never the system bus, never real logind). A test client
 * owns `org.freedesktop.login1` and emits the signal, in both endiannesses; another plays a spoofer.
 */
class MiniDbusIT {
    private fun <T> withBus(body: (PrivateDbus.Daemon) -> T): T = PrivateDbus.start().use(body)

    @Test
    fun `connects, says hello, subscribes, sends exactly two calls, and receives PrepareForSleep in both endiannesses`() = withBus { bus ->
        TestBusClient(bus.socket).use { logind ->
            logind.ownName("org.freedesktop.login1")
            MiniDbus.connect(bus.socket, PrivateDbus.realUid()).use { mini ->
                assertTrue(mini.uniqueName.startsWith(":"), mini.uniqueName)
                assertEquals(listOf("org.freedesktop.DBus.Hello", "org.freedesktop.DBus.AddMatch"), mini.sentCalls, "a read-only client sends nothing else")
                logind.emitPrepareForSleep(true)
                assertEquals(true, mini.nextPrepareForSleep())
                logind.emitPrepareForSleep(false)
                assertEquals(false, mini.nextPrepareForSleep())
            }
        }
        TestBusClient(bus.socket, big = true).use { logindBe ->
            logindBe.ownName("org.freedesktop.login1")
            MiniDbus.connect(bus.socket, PrivateDbus.realUid()).use { mini ->
                logindBe.emitPrepareForSleep(true)
                assertEquals(true, mini.nextPrepareForSleep())
                logindBe.emitPrepareForSleep(false)
                assertEquals(false, mini.nextPrepareForSleep())
            }
        }
        Report.line("MiniDbusIT: connect/Hello/AddMatch and PrepareForSleep true+false in LE and BE against a private dbus-daemon: OK (LAB, NOT DEVICE EVIDENCE)")
    }

    private fun nothingArrives(mini: MiniDbus, millis: Long) {
        val q = LinkedBlockingQueue<Any>()
        val t = Thread { q.add(runCatching { mini.nextPrepareForSleep() }.fold({ it ?: "eof" }, { it })) }.apply { isDaemon = true; start() }
        val got = q.poll(millis, TimeUnit.MILLISECONDS)
        mini.close()
        t.join(3_000)
        assertNull(got, "expected no PrepareForSleep, got $got")
    }

    @Test
    fun `a broadcast from a name that is not logind never reaches the client (the daemon filters on the sender)`() = withBus { bus ->
        TestBusClient(bus.socket).use { spoofer ->
            MiniDbus.connect(bus.socket, PrivateDbus.realUid()).let { mini ->
                spoofer.emitPrepareForSleep(true)
                nothingArrives(mini, 1_200)
            }
        }
    }

    @Test
    fun `a unicast forgery addressed straight to the client IS delivered by the bus and is dropped by the client`() = withBus { bus ->
        TestBusClient(bus.socket).use { spoofer ->
            val mini = MiniDbus.connect(bus.socket, PrivateDbus.realUid())
            spoofer.emitPrepareForSleep(true, destination = mini.uniqueName)
            nothingArrives(mini, 1_200)
        }
    }

    @Test
    fun `a real broadcast that follows a dropped forgery is still delivered`() = withBus { bus ->
        TestBusClient(bus.socket).use { logind ->
            logind.ownName("org.freedesktop.login1")
            TestBusClient(bus.socket).use { spoofer ->
                MiniDbus.connect(bus.socket, PrivateDbus.realUid()).use { mini ->
                    spoofer.emitPrepareForSleep(false, destination = mini.uniqueName)
                    logind.emitPrepareForSleep(true)
                    assertEquals(true, mini.nextPrepareForSleep())
                }
            }
        }
    }

    @Test
    fun `authentication as another uid is refused by the bus`() = withBus { bus ->
        assertFailsWith<IOException> { MiniDbus.connect(bus.socket, PrivateDbus.realUid() + 1) }
    }

    @Test
    fun `the bus going away ends the read with an exception or end of stream, never a hang`() = withBus { bus ->
        val mini = MiniDbus.connect(bus.socket, PrivateDbus.realUid())
        val done = CountDownLatch(1)
        Thread { runCatching { mini.nextPrepareForSleep() }; done.countDown() }.apply { isDaemon = true; start() }
        bus.kill()
        assertTrue(done.await(5, TimeUnit.SECONDS), "the reader did not notice the bus died")
    }

    @Test
    fun `an absent socket is an IOException within the timeout`() {
        assertFailsWith<IOException> { MiniDbus.connect(java.nio.file.Path.of("/nonexistent/asom-no-bus"), 0, 1_000) }
    }
}
