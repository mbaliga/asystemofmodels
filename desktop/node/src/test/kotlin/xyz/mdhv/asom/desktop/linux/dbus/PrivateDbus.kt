package xyz.mdhv.asom.desktop.linux.dbus

import java.io.File
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions

/**
 * A PRIVATE `dbus-daemon` for tests: its own socket in a temp directory, EXTERNAL auth only, permissive policy. It is
 * never the system bus. `ASOM_REQUIRE_DBUS=1` (set by CI) turns a missing binary into a FAILURE instead of a skip.
 */
object PrivateDbus {
    val required: Boolean = System.getenv("ASOM_REQUIRE_DBUS") == "1"

    fun binary(): String? =
        (listOf("/usr/bin/dbus-daemon", "/bin/dbus-daemon") + (System.getenv("PATH") ?: "").split(':').map { "$it/dbus-daemon" })
            .firstOrNull { File(it).canExecute() }

    /** Returns normally when a daemon binary exists; otherwise fails (CI) or skips the test (no `ASOM_REQUIRE_DBUS`). */
    fun requireBinaryOrSkip() {
        if (binary() != null) return
        if (required) throw AssertionError("ASOM_REQUIRE_DBUS=1 but no dbus-daemon binary was found: this job must not skip the private-bus tests")
        Assumptions.assumeTrue(false, "no dbus-daemon binary; set ASOM_REQUIRE_DBUS=1 to make this a failure")
    }

    class Daemon(val socket: Path, private val process: Process, private val dir: Path) : AutoCloseable {
        fun kill() {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
        }

        override fun close() {
            kill()
            runCatching { Files.deleteIfExists(socket) }
            runCatching { Files.deleteIfExists(dir.resolve("bus.conf")) }
            runCatching { Files.deleteIfExists(dir) }
        }
    }

    /** Starts a daemon listening on [socket] (default: a fresh temp directory). Waits until it accepts connections. */
    fun start(dir: Path = Files.createTempDirectory("asom-dbus-"), socket: Path = dir.resolve("bus")): Daemon {
        requireBinaryOrSkip()
        Files.createDirectories(dir)
        val cfg = dir.resolve("bus.conf")
        Files.writeString(
            cfg,
            """<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN" "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<busconfig>
  <listen>unix:path=$socket</listen>
  <auth>EXTERNAL</auth>
  <policy context="default">
    <allow send_destination="*" eavesdrop="true"/>
    <allow eavesdrop="true"/>
    <allow own="*"/>
    <allow user="*"/>
  </policy>
</busconfig>
""",
        )
        val p = ProcessBuilder(binary()!!, "--config-file=$cfg", "--nofork", "--print-address=1").redirectErrorStream(true).start()
        val ready = p.inputStream.bufferedReader().readLine()
        check(ready != null && ready.startsWith("unix:")) { "dbus-daemon did not print its address: $ready" }
        Thread { runCatching { p.inputStream.transferTo(java.io.OutputStream.nullOutputStream()) } }.apply { isDaemon = true }.start()
        return Daemon(socket, p, dir)
    }

    fun realUid(): Int =
        Files.readAllLines(Path.of("/proc/self/status")).first { it.startsWith("Uid:") }.removePrefix("Uid:").trim().split(Regex("\\s+"))[0].toInt()
}

/** A test-only bus client: the fake logind, or a spoofer. Its encoder is written apart from the production one. */
class TestBusClient(socket: Path, private val big: Boolean = false, uid: Int = PrivateDbus.realUid()) : AutoCloseable {
    private val ch: SocketChannel = SocketChannel.open(UnixDomainSocketAddress.of(socket))
    private val out = Channels.newOutputStream(ch)
    private val input = Channels.newInputStream(ch)
    private var serial = 0
    var uniqueName: String = ""
        private set

    init {
        out.write(byteArrayOf(0) + "AUTH EXTERNAL ${uid.toString().toByteArray().joinToString("") { "%02x".format(it) }}\r\n".toByteArray())
        out.flush()
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n")) sb.append(input.read().toChar())
        check(sb.startsWith("OK ")) { "auth: $sb" }
        out.write("BEGIN\r\n".toByteArray())
        out.flush()
        uniqueName = call("Hello", null).bodyString()
    }

    private fun order() = if (big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN

    private class W(val order: ByteOrder, val base: Int = 0) {
        val b = java.io.ByteArrayOutputStream()
        fun align(n: Int) { repeat((n - (base + b.size()) % n) % n) { b.write(0) } }
        fun u8(v: Int) = b.write(v)
        fun u32(v: Int) { align(4); b.write(ByteBuffer.allocate(4).order(order).putInt(v).array()) }
        fun str(s: String) { val d = s.toByteArray(); u32(d.size); b.write(d); b.write(0) }
        fun sig(s: String) { b.write(s.length); b.write(s.toByteArray()); b.write(0) }
    }

    private fun build(type: Int, fields: List<Triple<Int, String, String>>, body: ByteArray): ByteArray {
        serial++
        val f = W(order(), 16)
        for ((code, sig, value) in fields) {
            f.align(8); f.u8(code); f.sig(sig)
            if (sig == "g") f.sig(value) else f.str(value)
        }
        val head = ByteBuffer.allocate(16).order(order())
        head.put((if (big) 'B' else 'l').code.toByte()).put(type.toByte()).put(0).put(1).putInt(body.size).putInt(serial).putInt(f.b.size())
        val out = java.io.ByteArrayOutputStream()
        out.write(head.array()); out.write(f.b.toByteArray())
        repeat((8 - out.size() % 8) % 8) { out.write(0) }
        out.write(body)
        return out.toByteArray()
    }

    private fun call(member: String, arg: Pair<String, Int>?): DbusMessage {
        val fields = mutableListOf(Triple(1, "o", "/org/freedesktop/DBus"), Triple(2, "s", "org.freedesktop.DBus"), Triple(3, "s", member), Triple(6, "s", "org.freedesktop.DBus"))
        var body = ByteArray(0)
        if (arg != null) {
            fields += Triple(8, "g", "su")
            val w = W(order()); w.str(arg.first); w.u32(arg.second); body = w.b.toByteArray()
        }
        val bytes = build(1, fields, body)
        val mySerial = serial
        out.write(bytes); out.flush()
        while (true) {
            val m = DbusWire.read(input) ?: error("bus closed")
            if (m.replySerial?.toInt() == mySerial) {
                check(m.type == DbusType.METHOD_RETURN) { "call $member failed: ${m.errorName}" }
                return m
            }
        }
    }

    /** Becomes the owner of [name] (the fake logind). */
    fun ownName(name: String) {
        val r = call("RequestName", name to 0)
        check(r.body.size == 4 && ByteBuffer.wrap(r.body).order(if (r.bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN).int == 1) { "did not become primary owner of $name" }
    }

    /** Emits PrepareForSleep from `/org/freedesktop/login1`; a [destination] makes it a unicast (a forgery when sent by a spoofer). */
    fun emitPrepareForSleep(value: Boolean, destination: String? = null) {
        val fields = mutableListOf(Triple(1, "o", "/org/freedesktop/login1"), Triple(2, "s", "org.freedesktop.login1.Manager"), Triple(3, "s", "PrepareForSleep"))
        if (destination != null) fields += Triple(6, "s", destination)
        fields += Triple(8, "g", "b")
        out.write(build(4, fields, ByteBuffer.allocate(4).order(order()).putInt(if (value) 1 else 0).array())); out.flush()
    }

    override fun close() {
        runCatching { ch.close() }
    }
}
