package xyz.mdhv.asom.desktop.linux.power

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.SleepEvent
import xyz.mdhv.asom.desktop.linux.Report
import xyz.mdhv.asom.desktop.linux.dbus.DbusWire
import xyz.mdhv.asom.desktop.linux.dbus.MiniDbus

/**
 * A-LA-2: any local user may send a unicast signal to a bus client's unique name. A burst of them during the handshake, or one
 * over the 1 MiB cap, used to end the session; and a session that ended with an exception was never closed (one leaked
 * socket per drop). The bus here is a scripted fake in this process (evidence label: LAB, NOT a real dbus-daemon; the real
 * daemon's policy that lets a user send the signal is read from /usr/share/dbus-1/system.conf, not exercised).
 */
class SleepWatcherHostileBusTest {
    private val le = ByteOrder.LITTLE_ENDIAN

    private fun message(type: Int, serial: Int, fields: List<Triple<Int, String, Any>>, body: ByteArray): ByteArray {
        val f = ByteArrayOutputStream()
        fun pad(n: Int) { while (f.size() % n != 0) f.write(0) }
        fun u32(v: Int) { pad(4); f.write(ByteBuffer.allocate(4).order(le).putInt(v).array()) }
        fun str(s: String) { val d = s.toByteArray(); u32(d.size); f.write(d); f.write(0) }
        fun sig(s: String) { f.write(s.length); f.write(s.toByteArray()); f.write(0) }
        for ((code, sg, v) in fields) {
            pad(8); f.write(code); sig(sg)
            when (sg) {
                "o", "s" -> str(v as String)
                "u" -> u32(v as Int)
                "g" -> sig(v as String)
                else -> error(sg)
            }
        }
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(16).order(le).put('l'.code.toByte()).put(type.toByte()).put(0).put(1).putInt(body.size).putInt(serial).putInt(f.size()).array())
        out.write(f.toByteArray())
        while (out.size() % 8 != 0) out.write(0)
        out.write(body)
        return out.toByteArray()
    }

    private var serial = 1000

    private fun unicastSignal(bodyBytes: Int = 0): ByteArray {
        val fields = mutableListOf<Triple<Int, String, Any>>(Triple(1, "o", "/noise"), Triple(2, "s", "x.y"), Triple(3, "s", "Z"), Triple(6, "s", ":1.7"), Triple(7, "s", ":1.99"))
        var body = ByteArray(0)
        if (bodyBytes > 0) {
            fields += Triple(8, "g", "ay")
            body = ByteBuffer.allocate(4 + bodyBytes).order(le).putInt(bodyBytes).array()
        }
        return message(4, serial++, fields, body)
    }

    private fun prepareForSleep(value: Int): ByteArray = message(
        4, serial++,
        listOf(
            Triple(1, "o", "/org/freedesktop/login1"), Triple(2, "s", "org.freedesktop.login1.Manager"), Triple(3, "s", "PrepareForSleep"),
            Triple(7, "s", ":1.5"), Triple(8, "g", "b"),
        ),
        ByteBuffer.allocate(4).order(le).putInt(value).array(),
    )

    private fun methodReturn(replySerial: Long, unique: String?): ByteArray {
        val fields = mutableListOf<Triple<Int, String, Any>>(Triple(5, "u", replySerial.toInt()), Triple(6, "s", ":1.7"), Triple(7, "s", "org.freedesktop.DBus"))
        var body = ByteArray(0)
        if (unique != null) {
            fields += Triple(8, "g", "s")
            body = ByteBuffer.allocate(4 + unique.length + 1).order(le).putInt(unique.length).put(unique.toByteArray()).put(0).array()
        }
        return message(2, serial++, fields, body)
    }

    private class Script(
        val preHello: (OutputStream) -> Unit = {},
        val preAddMatch: (OutputStream) -> Unit = {},
        val afterConnect: (OutputStream) -> Unit = {},
    )

    private class FakeBus(val path: Path, private val script: Script, private val reply: (Long, String?) -> ByteArray) : AutoCloseable {
        private val server: ServerSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { it.bind(UnixDomainSocketAddress.of(path)) }
        val accepted = AtomicInteger()
        private val acceptor = Thread {
            while (true) {
                val ch = try { server.accept() } catch (_: Exception) { return@Thread }
                accepted.incrementAndGet()
                Thread { serve(ch) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; start() }

        private fun line(input: InputStream, until: String) {
            val sb = StringBuilder()
            while (!sb.endsWith(until)) { val b = input.read(); if (b < 0) error("eof"); sb.append(b.toChar()) }
        }

        private fun serve(ch: SocketChannel) {
            try {
                val input = Channels.newInputStream(ch)
                val out = Channels.newOutputStream(ch)
                input.read()
                line(input, "\r\n")
                out.write("OK 00112233445566778899aabbccddeeff\r\n".toByteArray()); out.flush()
                line(input, "BEGIN\r\n")
                val hello = DbusWire.read(input) ?: return
                script.preHello(out)
                out.write(reply(hello.serial, ":1.7")); out.flush()
                val add = DbusWire.read(input) ?: return
                script.preAddMatch(out)
                out.write(reply(add.serial, null)); out.flush()
                script.afterConnect(out)
                out.flush()
                while (input.read() >= 0) { /* hold the connection until the client closes it */ }
            } catch (_: Exception) {
            } finally {
                runCatching { ch.close() }
            }
        }

        override fun close() {
            runCatching { server.close() }
            Files.deleteIfExists(path)
            acceptor.join(2_000)
        }
    }

    private fun sockPath(): Path = Files.createTempDirectory("asom-fakebus-").resolve("bus")

    private fun openSockets(): Int = Files.list(Path.of("/proc/self/fd")).use { s ->
        s.filter { runCatching { Files.readSymbolicLink(it).toString().startsWith("socket:") }.getOrDefault(false) }.count().toInt()
    }

    @Test
    fun `a burst of unicast signals and an oversize one during the handshake do not fail the connect, and the next PrepareForSleep still arrives`() {
        val noise = { o: OutputStream -> repeat(200) { o.write(unicastSignal()) }; o.write(unicastSignal(2 shl 20)); repeat(200) { o.write(unicastSignal()) } }
        val script = Script(preHello = noise, preAddMatch = noise, afterConnect = { o -> o.write(unicastSignal(3 shl 20)); o.write(unicastSignal()); o.write(prepareForSleep(1)) })
        FakeBus(sockPath(), script, ::methodReturn).use { bus ->
            MiniDbus.connect(bus.path, 0).use { conn ->
                assertEquals(":1.7", conn.uniqueName)
                assertEquals(listOf("org.freedesktop.DBus.Hello", "org.freedesktop.DBus.AddMatch"), conn.sentCalls)
                assertEquals(true, conn.nextPrepareForSleep(), "the oversize and the unicast noise were skipped, the broadcast was not")
            }
        }
        Report.line("SleepWatcherHostileBusTest: 400 unicast signals plus two over the 1 MiB cap during the handshake: connect OK, signal delivered (LAB, scripted fake bus)")
    }

    @Test
    fun `an oversize unicast signal does not drop a connected watcher or delay its next event`() {
        val release = LinkedBlockingQueue<Boolean>()
        val script = Script(afterConnect = { o ->
            o.write(unicastSignal(2 shl 20)); o.flush()
            release.poll(10, TimeUnit.SECONDS)
            o.write(prepareForSleep(1))
        })
        FakeBus(sockPath(), script, ::methodReturn).use { bus ->
            val events = LinkedBlockingQueue<SleepEvent>()
            val connects = AtomicInteger()
            SleepWatcher(bus.path, { 0 }, { events.add(it) }, connector = { p, u -> MiniDbus.connect(p, u).also { connects.incrementAndGet() } }, retryMs = 100, gapPollMs = 3_600_000).start().use { w ->
                val end = System.currentTimeMillis() + 5_000
                while (w.status != SleepWatcherStatus.CONNECTED && System.currentTimeMillis() < end) Thread.sleep(10)
                Thread.sleep(500)
                release.add(true)
                assertEquals(SleepEvent.SLEEP_IMMINENT, events.poll(5, TimeUnit.SECONDS))
                assertEquals(1, connects.get(), "the session was dropped and rebuilt")
                assertEquals(1, bus.accepted.get())
            }
        }
    }

    @Test
    fun `a session that ends with an exception is closed, so repeated drops leak no socket`() {
        val before = openSockets()
        val script = Script(afterConnect = { o -> o.write(prepareForSleep(2)) })
        val connects = AtomicInteger()
        FakeBus(sockPath(), script, ::methodReturn).use { bus ->
            SleepWatcher(bus.path, { 0 }, { }, connector = { p, u -> MiniDbus.connect(p, u).also { connects.incrementAndGet() } }, retryMs = 20, gapPollMs = 3_600_000).start().use {
                val end = System.currentTimeMillis() + 10_000
                while (connects.get() < 8 && System.currentTimeMillis() < end) Thread.sleep(20)
                assertTrue(connects.get() >= 8, "only ${connects.get()} connections were made")
            }
        }
        var after = openSockets()
        val end = System.currentTimeMillis() + 5_000
        while (after > before && System.currentTimeMillis() < end) { Thread.sleep(50); after = openSockets() }
        assertEquals(before, after, "${connects.get()} dropped sessions left ${after - before} sockets open")
        Report.line("SleepWatcherHostileBusTest: ${connects.get()} sessions dropped by a protocol violation, open sockets before=$before after=$after (LAB)")
    }
}
