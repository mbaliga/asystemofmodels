package xyz.mdhv.asom.lab.proto.tls

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The extensions of a ClientHello or ServerHello that the lab cares about (LAB_SPEC 7.4: PSK, early data, SNI, ALPN, versions, schemes, groups). */
class HelloInfo(
    val isClientHello: Boolean,
    val sessionId: ByteArray,
    val cipherSuites: List<Int>,
    val extensionTypes: List<Int>,
    val supportedVersions: List<Int>,
    val signatureAlgorithms: List<Int>,
    val supportedGroups: List<Int>,
    val alpn: List<String>,
    val serverNames: List<String>,
    val selectedVersion: Int?,
    val helloRetryRequest: Boolean,
    /** The handshake message exactly as it was on the wire: type, 3-byte length, body. */
    val raw: ByteArray = ByteArray(0),
) {
    /** `pre_shared_key` (41): the client is offering resumption, or the server accepted it. */
    val hasPsk: Boolean get() = EXT_PSK in extensionTypes

    /** `early_data` (42): 0-RTT. */
    val hasEarlyData: Boolean get() = EXT_EARLY_DATA in extensionTypes

    val hasServerName: Boolean get() = EXT_SERVER_NAME in extensionTypes

    companion object {
        const val EXT_SERVER_NAME = 0
        const val EXT_PSK = 41
        const val EXT_EARLY_DATA = 42
    }
}

/** Remembers the session ids of every tapped connection so that a ClientHello reusing the id of ANOTHER connection (a TLS 1.2 style resumption) is seen. */
class SessionIdBook {
    private val owners = Collections.synchronizedMap(HashMap<String, Int>())

    fun note(id: ByteArray, tap: Int): Boolean {
        if (id.isEmpty()) return false
        val key = id.joinToString("") { "%02x".format(it) }
        val owner = owners.putIfAbsent(key, tap)
        return owner != null && owner != tap
    }
}

/**
 * The test-only record tap (LAB_SPEC 7.4, 7.6): a [NetChannel] wrapper that counts RAW bytes per direction and parses the TLS record layer of both
 * directions, including the ClientHello and ServerHello (the only plaintext handshake messages of TLS 1.3). It detects a `pre_shared_key` extension,
 * a `early_data` extension, and a ClientHello whose legacy session id was used by another connection. It never changes a byte.
 */
class RecordTap(private val inner: NetChannel, private val book: SessionIdBook = SessionIdBook()) : NetChannel {
    private val id = NEXT.incrementAndGet()
    private val rawIn = AtomicLong()
    private val rawOut = AtomicLong()
    private val parserIn = Parser()
    private val parserOut = Parser()
    private val hellos = Collections.synchronizedList(ArrayList<HelloInfo>())
    private val reuse = AtomicInteger()

    /** Bytes the wrapped channel delivered to the reader. */
    val bytesRead: Long get() = rawIn.get()

    /** Bytes the wrapped channel accepted from the writer. */
    val bytesWritten: Long get() = rawOut.get()

    val clientHellos: List<HelloInfo> get() = synchronized(hellos) { hellos.filter { it.isClientHello } }
    val serverHellos: List<HelloInfo> get() = synchronized(hellos) { hellos.filter { !it.isClientHello } }

    /** ClientHellos carrying `pre_shared_key`. */
    val pskClientHellos: Int get() = clientHellos.count { it.hasPsk }

    val earlyDataClientHellos: Int get() = clientHellos.count { it.hasEarlyData }

    /** ClientHellos whose session id equals one seen on another tapped connection. */
    val sessionIdReuses: Int get() = reuse.get()

    val recordsIn: Int get() = parserIn.records
    val recordsOut: Int get() = parserOut.records

    /** Plaintext alerts (record type 21, two bytes) seen in either direction, as (level, description). */
    val plaintextAlerts: List<Pair<Int, Int>> get() = synchronized(this) { parserIn.alerts + parserOut.alerts }

    override fun read(dst: ByteBuffer, timeoutMs: Long): Int {
        val start = dst.position()
        val n = inner.read(dst, timeoutMs)
        if (n > 0) {
            rawIn.addAndGet(n.toLong())
            val copy = ByteArray(n)
            dst.duplicate().position(start).get(copy)
            synchronized(this) { parserIn.feed(copy) }
        }
        return n
    }

    override fun write(src: ByteBuffer, timeoutMs: Long) {
        val start = src.position()
        val snapshot = ByteArray(src.remaining())
        src.duplicate().get(snapshot)
        try {
            inner.write(src, timeoutMs)
        } finally {
            val written = src.position() - start
            if (written > 0) {
                rawOut.addAndGet(written.toLong())
                synchronized(this) { parserOut.feed(snapshot.copyOf(written)) }
            }
        }
    }

    override fun shutdownOutput() = inner.shutdownOutput()

    override fun close() = inner.close()

    private inner class Parser {
        var records = 0
        val alerts = ArrayList<Pair<Int, Int>>()
        private var pending = ByteArray(0)
        private val handshake = ByteArrayOutputStream()
        private var parsingHandshake = true

        fun feed(bytes: ByteArray) {
            pending += bytes
            while (pending.size >= 5) {
                val len = u16(pending, 3)
                if (pending.size < 5 + len) return
                val type = pending[0].toInt() and 0xFF
                val body = pending.copyOfRange(5, 5 + len)
                pending = pending.copyOfRange(5 + len, pending.size)
                records++
                record(type, body)
            }
        }

        private fun record(type: Int, body: ByteArray) {
            when (type) {
                22 -> if (parsingHandshake) {
                    handshake.write(body)
                    messages()
                }
                21 -> if (body.size == 2) alerts += (body[0].toInt() and 0xFF) to (body[1].toInt() and 0xFF)
                20 -> Unit
                else -> parsingHandshake = false
            }
        }

        private fun messages() {
            var all = handshake.toByteArray()
            while (all.size >= 4) {
                val len = (u8(all, 1) shl 16) or u16(all, 2)
                if (all.size < 4 + len) break
                val type = u8(all, 0)
                val body = all.copyOfRange(4, 4 + len)
                val all0 = all
                all = all.copyOfRange(4 + len, all.size)
                when (type) {
                    1 -> hello(body, true, all0.copyOfRange(0, 4 + len))
                    2 -> hello(body, false, all0.copyOfRange(0, 4 + len))
                }
            }
            handshake.reset()
            handshake.write(all)
        }

        private fun hello(b: ByteArray, client: Boolean, raw: ByteArray) {
            try {
                var p = 2 + 32
                val random = b.copyOfRange(2, 34)
                val sidLen = u8(b, p++)
                val sid = b.copyOfRange(p, p + sidLen)
                p += sidLen
                val suites = ArrayList<Int>()
                if (client) {
                    val csLen = u16(b, p)
                    p += 2
                    for (i in 0 until csLen / 2) suites += u16(b, p + 2 * i)
                    p += csLen
                    p += 1 + u8(b, p)
                } else {
                    suites += u16(b, p)
                    p += 3
                }
                val extLen = u16(b, p)
                p += 2
                val end = p + extLen
                val types = ArrayList<Int>()
                val versions = ArrayList<Int>()
                val sigs = ArrayList<Int>()
                val groups = ArrayList<Int>()
                val alpn = ArrayList<String>()
                val names = ArrayList<String>()
                var selected: Int? = null
                while (p < end) {
                    val t = u16(b, p)
                    val l = u16(b, p + 2)
                    val d = b.copyOfRange(p + 4, p + 4 + l)
                    p += 4 + l
                    types += t
                    when (t) {
                        43 -> if (client) {
                            for (i in 0 until u8(d, 0) / 2) versions += u16(d, 1 + 2 * i)
                        } else {
                            selected = u16(d, 0)
                        }
                        13 -> for (i in 0 until u16(d, 0) / 2) sigs += u16(d, 2 + 2 * i)
                        10 -> for (i in 0 until u16(d, 0) / 2) groups += u16(d, 2 + 2 * i)
                        16 -> {
                            var q = 2
                            while (q < d.size) {
                                val n = u8(d, q)
                                alpn += String(d, q + 1, n, Charsets.ISO_8859_1)
                                q += 1 + n
                            }
                        }
                        0 -> if (d.size >= 5) {
                            var q = 2
                            while (q + 3 <= d.size) {
                                val n = u16(d, q + 1)
                                names += String(d, q + 3, n, Charsets.ISO_8859_1)
                                q += 3 + n
                            }
                        }
                    }
                }
                val hrr = !client && random.contentEquals(HELLO_RETRY_RANDOM)
                val info = HelloInfo(client, sid, suites, types, versions, sigs, groups, alpn, names, selected, hrr, raw)
                if (client && book.note(sid, id)) reuse.incrementAndGet()
                hellos += info
            } catch (e: IndexOutOfBoundsException) {
                malformed.incrementAndGet()
            }
        }
    }

    /** Hello messages the parser could not read (a hostile or truncated one). */
    val malformedHellos: Int get() = malformed.get()
    private val malformed = AtomicInteger()

    companion object {
        private val NEXT = AtomicInteger()

        private val HELLO_RETRY_RANDOM = byteArrayOf(
            0xCF.toByte(), 0x21, 0xAD.toByte(), 0x74, 0xE5.toByte(), 0x9A.toByte(), 0x61, 0x11, 0xBE.toByte(), 0x1D, 0x8C.toByte(), 0x02, 0x1E, 0x65,
            0xB8.toByte(), 0x91.toByte(), 0xC2.toByte(), 0xA2.toByte(), 0x11, 0x16, 0x7A, 0xBB.toByte(), 0x8C.toByte(), 0x5E, 0x07, 0x9E.toByte(),
            0x09, 0xE2.toByte(), 0xC8.toByte(), 0xA8.toByte(), 0x33, 0x9C.toByte(),
        )

        private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

        private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    }
}
