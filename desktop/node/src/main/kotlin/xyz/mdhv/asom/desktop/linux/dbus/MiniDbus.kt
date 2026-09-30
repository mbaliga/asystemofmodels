package xyz.mdhv.asom.desktop.linux.dbus

import java.io.IOException
import java.io.InputStream
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A minimal READ-ONLY system-bus client (LD-12). It does exactly this and nothing else: SASL `EXTERNAL` with the uid,
 * `BEGIN`, `Hello`, one `AddMatch` for logind's `PrepareForSleep`, then it only reads. It never sends a method call
 * other than those two (asserted by a test against the recorded transcript), never owns a name, never emits a signal.
 *
 * A `PrepareForSleep` is accepted only if it is a BROADCAST (no destination header), from a bus-assigned unique name,
 * on the exact path, interface and member, with a body that is one boolean. A unicast signal is dropped: the bus's
 * match rule only filters broadcasts, so a local process could otherwise address a forged one straight to us.
 */
class MiniDbus private constructor(
    private val channel: SocketChannel,
    private val input: InputStream,
    val uniqueName: String,
    private val pending: ArrayDeque<Boolean>,
    /** Every method call this client sent, as `interface.member`, in order (read-only evidence for tests). */
    val sentCalls: List<String>,
) : AutoCloseable {

    /**
     * Blocks until the next accepted `PrepareForSleep` and returns its argument (true = about to sleep, false =
     * resumed), or null when the bus closed the connection cleanly. Throws on a malformed message or a read failure:
     * the caller drops the connection.
     */
    fun nextPrepareForSleep(): Boolean? {
        pending.pollFirst()?.let { return it }
        while (true) {
            val m = DbusWire.read(input) ?: return null
            accept(m)?.let { return it }
        }
    }

    override fun close() {
        runCatching { channel.close() }
    }

    companion object {
        const val BUS_NAME = "org.freedesktop.DBus"
        const val BUS_PATH = "/org/freedesktop/DBus"
        const val LOGIND_PATH = "/org/freedesktop/login1"
        const val LOGIND_IFACE = "org.freedesktop.login1.Manager"
        const val MATCH_RULE =
            "type='signal',sender='org.freedesktop.login1',interface='org.freedesktop.login1.Manager',member='PrepareForSleep',path='/org/freedesktop/login1'"
        const val DEFAULT_SYSTEM_BUS = "/run/dbus/system_bus_socket"
        private const val MAX_SASL_LINE = 512
        private const val MAX_MESSAGES_BEFORE_REPLY = 64

        /** `AUTH EXTERNAL <hex of the ASCII decimal uid>`, e.g. uid 1000 -> `31303030`. */
        fun saslExternalLine(uid: Int): String {
            require(uid >= 0)
            val hex = uid.toString().toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "AUTH EXTERNAL $hex\r\n"
        }

        /** The decision rule for one incoming message; null when it is not an accepted PrepareForSleep. */
        fun accept(m: DbusMessage): Boolean? {
            if (m.type != DbusType.SIGNAL) return null
            if (m.destination != null) return null
            if (m.path != LOGIND_PATH || m.iface != LOGIND_IFACE || m.member != "PrepareForSleep") return null
            val sender = m.sender ?: return null
            if (!sender.startsWith(":")) return null
            return m.bodyBoolean()
        }

        /** Connects, authenticates and subscribes within [timeoutMs] in total; throws [IOException] on any failure. */
        fun connect(socketPath: Path, uid: Int, timeoutMs: Long = 5_000): MiniDbus {
            val ch = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))
            val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "asom-dbus-connect-watchdog").apply { isDaemon = true } }
            try {
                watchdog.schedule({ runCatching { ch.close() } }, timeoutMs, TimeUnit.MILLISECONDS)
                val input = Channels.newInputStream(ch)
                val output = Channels.newOutputStream(ch)

                output.write(byteArrayOf(0) + saslExternalLine(uid).toByteArray(Charsets.US_ASCII))
                output.flush()
                val reply = readLine(input)
                if (!reply.startsWith("OK ") && reply != "OK") throw DbusProtocolException("SASL EXTERNAL was not accepted: ${reply.take(40)}")
                output.write("BEGIN\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()

                val sent = ArrayList<String>(2)
                val pending = ArrayDeque<Boolean>()

                fun call(serial: Long, iface: String, member: String, arg: String?): DbusMessage {
                    output.write(DbusWire.encodeMethodCall(serial, BUS_NAME, BUS_PATH, iface, member, arg))
                    output.flush()
                    sent += "$iface.$member"
                    repeat(MAX_MESSAGES_BEFORE_REPLY) {
                        val m = DbusWire.read(input) ?: throw DbusProtocolException("the bus closed the connection before replying to $member")
                        if (m.type == DbusType.ERROR && m.replySerial == serial) throw DbusProtocolException("$member failed: ${m.errorName}")
                        if (m.type == DbusType.METHOD_RETURN && m.replySerial == serial) return m
                        accept(m)?.let { pending.addLast(it) }
                    }
                    throw DbusProtocolException("no reply to $member after $MAX_MESSAGES_BEFORE_REPLY messages")
                }

                val unique = call(1, BUS_NAME, "Hello", null).bodyString()
                if (!unique.startsWith(":")) throw DbusProtocolException("Hello returned a name that is not a unique name")
                call(2, BUS_NAME, "AddMatch", MATCH_RULE)
                watchdog.shutdownNow()
                return MiniDbus(ch, input, unique, pending, sent)
            } catch (e: Throwable) {
                runCatching { ch.close() }
                throw e
            } finally {
                watchdog.shutdownNow()
            }
        }

        private fun readLine(input: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw DbusProtocolException("the bus closed the connection during authentication")
                if (b == '\n'.code) {
                    if (sb.isEmpty() || sb[sb.length - 1] != '\r') throw DbusProtocolException("SASL line not terminated by CRLF")
                    sb.setLength(sb.length - 1)
                    return sb.toString()
                }
                sb.append(b.toChar())
                if (sb.length > MAX_SASL_LINE) throw DbusProtocolException("SASL line is too long")
            }
        }
    }
}
