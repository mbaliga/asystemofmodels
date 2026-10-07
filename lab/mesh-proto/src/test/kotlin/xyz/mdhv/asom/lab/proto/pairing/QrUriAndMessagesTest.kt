package xyz.mdhv.asom.lab.proto.pairing

import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.proto.trust.LawCounters
import xyz.mdhv.asom.lab.proto.trust.Pin

/** The QR grammar and the four pairing messages as properties over seeded random inputs. Evidence label: LAB, oracle: self. */
class QrUriAndMessagesTest {
    companion object {
        val laws = LawCounters("qr-and-messages")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("qr-roundtrip-random", "qr-garbage-never-throws", "message-roundtrip-random", "message-garbage-never-throws", "name-code-points", "ipv6-canonical"))
    }

    private val now = 1_790_000_000L

    private fun randomName(rnd: SplittableRandom): String {
        val pool = "abcXYZ019 -._~'%&=+/#é😀日本"
        val cps = pool.codePoints().toArray()
        val n = rnd.nextInt(1, 33)
        return buildString { repeat(n) { appendCodePoint(cps[rnd.nextInt(cps.size)]) } }
    }

    private fun randomEndpoint(rnd: SplittableRandom): QrEndpoint = when (rnd.nextInt(4)) {
        0 -> QrEndpoint("10.${rnd.nextInt(256)}.${rnd.nextInt(256)}.${rnd.nextInt(1, 255)}", rnd.nextInt(1, 65536), false)
        1 -> QrEndpoint("192.168.${rnd.nextInt(256)}.${rnd.nextInt(1, 255)}", rnd.nextInt(1, 65536), false)
        2 -> QrEndpoint("100.${rnd.nextInt(64, 128)}.${rnd.nextInt(256)}.${rnd.nextInt(1, 255)}", rnd.nextInt(1, 65536), false)
        else -> QrEndpoint(Endpoints.canonicalV6(intArrayOf(0xfd00 or rnd.nextInt(256), rnd.nextInt(65536), 0, 0, rnd.nextInt(2) * rnd.nextInt(65536), 0, rnd.nextInt(65536), rnd.nextInt(1, 65536))), rnd.nextInt(1, 65536), true)
    }

    @Test
    fun encodeThenParseIsTheIdentity() {
        val rnd = SplittableRandom(20260930)
        val iterations = 500
        repeat(iterations) {
            val payload = QrPayload(
                Pin.ofHash(ByteArray(32) { rnd.nextInt(256).toByte() }), List(rnd.nextInt(1, 5)) { randomEndpoint(rnd) }, ByteArray(32) { rnd.nextInt(256).toByte() },
                now + rnd.nextLong(-59, 181), randomName(rnd),
            )
            val text = QrUri.encode(payload)
            val parsed = QrUri.parse(text, now)
            assertTrue(parsed is QrParse.Ok, "parse(encode(p)) refused: ${(parsed as? QrParse.Reject)?.code} for $text")
            val q = parsed.payload
            assertTrue(q.pinD.equalsConstantTime(payload.pinD))
            assertEquals(payload.endpoints, q.endpoints)
            assertContentEquals(payload.secret, q.secret)
            assertEquals(payload.expirySec, q.expirySec)
            assertEquals(payload.name, q.name)
            laws.bump("qr-roundtrip-random")
            laws.bump("name-code-points")
        }
        println("iterations: $iterations")
    }

    @Test
    fun ipv6LiteralsNormaliseToRfc5952() {
        for ((input, canonical) in listOf(
            "fd00:0:0:0:0:0:0:1" to "fd00::1", "FD00::0001" to "fd00::1", "fd00:0:0:1:0:0:0:1" to "fd00:0:0:1::1", "fe80:0:0:0:1:0:0:0" to "fe80::1:0:0:0",
            "fd00:1:2:3:4:5:6:7" to "fd00:1:2:3:4:5:6:7", "fd00:0:1:0:1:0:1:0" to "fd00:0:1:0:1:0:1:0",
        )) {
            val g = Endpoints.ipv6Groups(input.lowercase())
            assertTrue(g != null, input)
            assertEquals(canonical, Endpoints.canonicalV6(g))
            laws.bump("ipv6-canonical")
        }
    }

    @Test
    fun garbageNeverThrows() {
        val rnd = SplittableRandom(99)
        val base = "asom-pair:1?k=jZcpQhuRMg6yNp81ynXIGjfGCcZeStuotMWTOtRFPgs&a=192.168.1.40:11436&s=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&x=1790000120&n=Dell%20tower"
        val alphabet = "asom-pair:1?k=a&s=x%[]:,.0123456789ABCDEFabcdef \u0000é😀"
        var cases = 0
        repeat(3000) {
            val sb = StringBuilder(base)
            repeat(rnd.nextInt(1, 6)) {
                when (rnd.nextInt(3)) {
                    0 -> if (sb.isNotEmpty()) sb.setCharAt(rnd.nextInt(sb.length), alphabet[rnd.nextInt(alphabet.length)])
                    1 -> sb.insert(rnd.nextInt(sb.length + 1), alphabet[rnd.nextInt(alphabet.length)])
                    else -> if (sb.isNotEmpty()) sb.deleteCharAt(rnd.nextInt(sb.length))
                }
            }
            val r = QrUri.parse(sb.toString(), now)
            if (r is QrParse.Ok) assertTrue(QrUri.parse(QrUri.encode(r.payload), now) is QrParse.Ok, "an accepted URI does not re-encode to an accepted URI")
            cases++
        }
        repeat(500) {
            QrUri.parse(String(CharArray(rnd.nextInt(0, 300)) { (rnd.nextInt(0x20, 0x250)).toChar() }), now)
            cases++
        }
        laws.bump("qr-garbage-never-throws", cases)
        println("iterations: $cases")
    }

    @Test
    fun messagesRoundTripAndGarbageNeverThrows() {
        val rnd = SplittableRandom(5)
        val iterations = 300
        fun b32() = ByteArray(32) { rnd.nextInt(256).toByte() }
        repeat(iterations) {
            val hello = PairHello(
                b32(), b32(), randomName(rnd), PairEnums.PLATFORMS[rnd.nextInt(PairEnums.PLATFORMS.size)], PairEnums.KEY_TIERS[rnd.nextInt(PairEnums.KEY_TIERS.size)],
                List(rnd.nextInt(0, 5)) { PairEndpoint("10.0.${rnd.nextInt(256)}.${rnd.nextInt(1, 255)}", rnd.nextInt(1, 65536), PairEnums.VIAS[rnd.nextInt(2)]) },
            )
            val bytes = PairMessages.encodeHello(hello)
            val back = (PairMessages.parseHello(bytes) as MsgParse.Ok).value
            assertContentEquals(bytes, PairMessages.encodeHello(back))
            assertContentEquals(hello.nonceS, back.nonceS)
            assertContentEquals(hello.proof, back.proof)
            assertEquals(hello.name, back.name)
            val chal = PairChallenge(b32(), hello.name, hello.platform, hello.keyTier)
            val cb = PairMessages.encodeChallenge(chal)
            assertContentEquals(cb, PairMessages.encodeChallenge((PairMessages.parseChallenge(cb) as MsgParse.Ok).value))
            val commit = PairCommit(b32())
            assertContentEquals(commit.transcript, ((PairMessages.parseCommit(PairMessages.encodeCommit(commit))) as MsgParse.Ok).value.transcript)
            assertContentEquals(commit.transcript, ((PairMessages.parseCommitAck(PairMessages.encodeCommitAck(PairCommitAck(commit.transcript)))) as MsgParse.Ok).value.transcript)
            val dec = PairDecision(rnd.nextBoolean())
            assertEquals(dec.approve, ((PairMessages.parseDecision(PairMessages.encodeDecision(dec))) as MsgParse.Ok).value.approve)
            assertTrue(!String(PairMessages.encodeCommit(commit)).contains("locSeed"))
            laws.bump("message-roundtrip-random")
        }
        var cases = 0
        repeat(2000) {
            val good = PairMessages.encodeHello(PairHello(b32(), b32(), "n", "ios", "file", emptyList()))
            val m = good.copyOf(rnd.nextInt(0, good.size + 1))
            if (m.isNotEmpty() && rnd.nextBoolean()) m[rnd.nextInt(m.size)] = rnd.nextInt(256).toByte()
            PairMessages.parseHello(m)
            PairMessages.parseChallenge(m)
            PairMessages.parseDecision(m)
            PairMessages.parseCommit(m)
            PairMessages.parseCommitAck(m)
            cases++
        }
        laws.bump("message-garbage-never-throws", cases)
        println("iterations: $iterations / $cases")
    }
}
