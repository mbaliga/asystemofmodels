package xyz.mdhv.asom.lab.proto.tls

import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.SplittableRandom

/**
 * A hand-built TLS 1.3 ClientHello (RFC 8446 section 4.1.2) for the cases the JSSE client cannot produce: an `early_data` extension, a
 * `pre_shared_key` with an identity the server never issued, an SNI, an ALPN of our choosing. It carries a real P-256 key share, so a server that
 * accepts it goes on to send a real ServerHello, and the tap can read what the server decided. It is a test tool: nothing here is used by the transport.
 */
class RawHello(
    val alpn: List<String> = listOf(MeshTlsProfile.ALPN),
    val earlyData: Boolean = false,
    val pskIdentity: ByteArray? = null,
    val serverName: String? = null,
    val versions: List<Int> = listOf(0x0304),
    val signatureAlgorithms: List<Int> = listOf(0x0403),
    val seed: Long = 1,
) {
    private fun u16(o: ByteArrayOutputStream, v: Int) {
        o.write(v shr 8)
        o.write(v)
    }

    private fun ext(o: ByteArrayOutputStream, type: Int, body: ByteArray) {
        u16(o, type)
        u16(o, body.size)
        o.write(body)
    }

    private fun body(f: (ByteArrayOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also(f).toByteArray()

    fun record(): ByteArray {
        val rnd = SplittableRandom(seed)
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val w = (kp.public as ECPublicKey).w
        fun fixed32(v: java.math.BigInteger): ByteArray {
            val raw = v.toByteArray()
            val s = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
            return ByteArray(32 - s.size) + s
        }
        val point = byteArrayOf(4) + fixed32(w.affineX) + fixed32(w.affineY)

        val exts = ByteArrayOutputStream()
        serverName?.let { name ->
            ext(exts, 0, body { b ->
                val n = name.toByteArray(Charsets.ISO_8859_1)
                u16(b, n.size + 3)
                b.write(0)
                u16(b, n.size)
                b.write(n)
            })
        }
        ext(exts, 10, body { b -> u16(b, 2); u16(b, 0x0017) })
        ext(exts, 13, body { b -> u16(b, signatureAlgorithms.size * 2); signatureAlgorithms.forEach { u16(b, it) } })
        if (alpn.isNotEmpty()) {
            ext(exts, 16, body { b ->
                val list = body { l -> alpn.forEach { a -> l.write(a.length); l.write(a.toByteArray(Charsets.ISO_8859_1)) } }
                u16(b, list.size)
                b.write(list)
            })
        }
        ext(exts, 43, body { b -> b.write(versions.size * 2); versions.forEach { u16(b, it) } })
        ext(exts, 45, byteArrayOf(1, 1))
        ext(exts, 51, body { b -> u16(b, point.size + 4); u16(b, 0x0017); u16(b, point.size); b.write(point) })
        if (earlyData) ext(exts, 42, ByteArray(0))
        pskIdentity?.let { id ->
            ext(exts, 41, body { b ->
                u16(b, id.size + 6)
                u16(b, id.size)
                b.write(id)
                b.write(byteArrayOf(0, 0, 0, 0))
                u16(b, 33)
                b.write(32)
                b.write(ByteArray(32) { rnd.nextInt(256).toByte() })
            })
        }

        val hello = ByteArrayOutputStream()
        u16(hello, 0x0303)
        hello.write(ByteArray(32) { rnd.nextInt(256).toByte() })
        hello.write(32)
        hello.write(ByteArray(32) { rnd.nextInt(256).toByte() })
        u16(hello, 6)
        u16(hello, 0x1301)
        u16(hello, 0x1302)
        u16(hello, 0x1303)
        hello.write(1)
        hello.write(0)
        val extBytes = exts.toByteArray()
        u16(hello, extBytes.size)
        hello.write(extBytes)

        val hs = ByteArrayOutputStream()
        hs.write(1)
        val h = hello.toByteArray()
        hs.write(h.size shr 16)
        u16(hs, h.size and 0xFFFF)
        hs.write(h)
        val msg = hs.toByteArray()

        val rec = ByteArrayOutputStream()
        rec.write(22)
        u16(rec, 0x0301)
        u16(rec, msg.size)
        rec.write(msg)
        return rec.toByteArray()
    }
}
