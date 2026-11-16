package xyz.mdhv.asom.lab.proto.wire

import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameCodecTest {
    private val server = PeerRole.TLS_SERVER
    private val client = PeerRole.TLS_CLIENT

    @Test
    fun theSpecsWorkedEncodingsMatchByteForByte() {
        val hello = FrameEncoder.encode(RawFrame(FrameTypes.HELLO, 0, """{"v":1}""".toByteArray()))
        assertEquals("0000000c0100000000" + "7b2276223a317d", hello.toHex())
        assertEquals(16L, RawFrame(FrameTypes.HELLO, 0, """{"v":1}""".toByteArray()).appBytes)

        val goaway = FrameEncoder.encode(RawFrame(FrameTypes.GOAWAY, 0, """{"reason":"idle"}""".toByteArray()))
        assertEquals("00000016" + "05" + "00000000" + "7b22726561736f6e223a2269646c65227d", goaway.toHex())
        assertEquals(26, goaway.size)

        val stateReq = FrameEncoder.encode(RawFrame(FrameTypes.STATE_REQ, 3, """{"v":1}""".toByteArray()))
        assertEquals("0000000c" + "20" + "00000003" + "7b2276223a317d", stateReq.toHex())

        val cancel = FrameEncoder.encode(RawFrame(FrameTypes.CANCEL, 5, """{"attemptId":"AAAAAAAAAAAAAAAAAAAAAA","reason":"deadline"}""".toByteArray()))
        assertEquals(67, cancel.size, "67 bytes in total")
        assertTrue(cancel.toHex().startsWith("0000003f" + "17" + "00000005" + "7b22617474656d70744964223a22414141"))
        assertEquals(0x3f, cancel[3].toInt(), "length is 5 + the 58 byte payload")
    }

    @Test
    fun theTypedBuildersProduceTheWorkedEncodingsToo() {
        assertEquals("0000000c2000000003" + "7b2276223a317d", MessageCodec.encode(StateReq, 3).toHex())
        assertEquals("0000001605" + "00000000" + "7b22726561736f6e223a2269646c65227d", MessageCodec.encode(GoAway(GoAwayReason.IDLE), 0).toHex())
        val cancel = MessageCodec.encode(Cancel("AAAAAAAAAAAAAAAAAAAAAA", CancelReason.DEADLINE), 5)
        assertEquals(67, cancel.size)
        assertTrue(cancel.toHex().startsWith("0000003f1700000005"))
    }

    @Test
    fun aFrameWithEveryRuleBrokenIsRefusedByTheProducerToo() {
        for ((frame, reason) in listOf(
            RawFrame(0x80, 0, "{}".toByteArray()) to Reason.EXTENSION_NOT_SENT,
            RawFrame(0xFF, 0, "{}".toByteArray()) to Reason.EXTENSION_NOT_SENT,
            RawFrame(0x03, 0, "{}".toByteArray()) to Reason.UNKNOWN_TYPE,
            RawFrame(0x41, 0, "{}".toByteArray()) to Reason.UNKNOWN_TYPE,
            RawFrame(0x20, 2, """{"v":1}""".toByteArray()) to Reason.STREAM_RULE,
            RawFrame(0x01, 1, "{}".toByteArray()) to Reason.STREAM_RULE,
            RawFrame(0x30, 3, "{}".toByteArray()) to Reason.STREAM_RULE,
            RawFrame(0x20, 1, "[]".toByteArray()) to Reason.NOT_AN_OBJECT,
            RawFrame(0x20, 1, """{"v":1.5}""".toByteArray()) to "NON_INTEGER_NUMBER",
        )) {
            assertEquals(reason, WireLaws.assertFailsRefusal { FrameEncoder.encode(frame) }.reason)
        }
    }

    @Test
    fun everyPrefixOfAFrameIsTruncatedNotDelivered() {
        val bytes = FrameEncoder.encode(RawFrame(FrameTypes.STATE_REQ, 3, """{"v":1}""".toByteArray()))
        assertNull(FrameDecoder(server).finish(), "nothing fed, nothing truncated")
        for (n in 1 until bytes.size) {
            val f = decodeWhole(bytes.copyOf(n), server)
            val x = failureOf(f)
            assertEquals(Reason.TRUNCATED, x.reason, "prefix of $n bytes")
            assertEquals(n.toLong(), x.consumed)
            assertTrue(f.events.none { it is Inbound.Frame })
        }
        assertEquals(1, decodeWhole(bytes, server).events.size)
    }

    @Test
    fun aClosedDecoderDiscardsEverythingAfterTheFailureAndStillAccountsForIt() {
        val d = FrameDecoder(server)
        val bad = FrameEncoderBypass.frame(0x03, 0, "{}".toByteArray()) + FrameEncoder.encode(RawFrame(0x20, 1, """{"v":1}""".toByteArray()))
        val events = d.feed(bad)
        assertEquals(1, events.size)
        assertTrue(events.single() is Inbound.Failure)
        assertTrue(d.closed)
        assertEquals(emptyList(), d.feed(byteArrayOf(1, 2, 3)))
        assertEquals(bad.size + 3L, d.totalFed)
        assertEquals(5L, d.pendingBytes)
        assertEquals(bad.size + 3L - 5L, d.discardedAfterClose)
        assertEquals(d.totalFed, d.appBytesDelivered + d.pendingBytes + d.discardedAfterClose)
    }

    @Test
    fun anExtensionIsSkippedWithoutBeingStoredAndTheEventCarriesNoPayload() {
        val ext = FrameEncoderBypass.frame(0x80, 9, ByteArray(100_000) { 7 })
        val d = FrameDecoder(server)
        var total = 0
        val events = ArrayList<Inbound>()
        for (chunk in ext.toList().chunked(997)) events += d.feed(chunk.toByteArray()).also { total += chunk.size }
        val ev = events.single() as Inbound.ExtIgnored
        assertEquals(0x80, ev.type)
        assertEquals(9L, ev.stream)
        assertEquals(100_009L, ev.appBytes)
        assertEquals(100_009L, d.appBytesDelivered)
    }

    @Test
    fun aFailureIsRaisedWithoutBufferingThePayload() {
        // A header announcing a 1 MiB + 1 JSON payload is refused after 5 bytes: nothing was allocated for the payload.
        val d = FrameDecoder(server)
        val events = d.feed(header(5L + 1_048_577, 0x20, 1))
        val x = events.single() as Inbound.Failure
        assertEquals(Reason.PAYLOAD_LIMIT, x.reason)
        assertEquals(5L, x.consumed)
        assertEquals(4L, d.discardedAfterClose)
    }

    @Test
    fun theDecoderNeverDeliversAFrameWhoseJsonIsInvalid() {
        val rnd = SplittableRandom(3)
        repeat(300) {
            val payload = ByteArray(1 + rnd.nextInt(40)) { (rnd.nextInt(95) + 32).toByte() }
            val f = decodeWhole(FrameEncoderBypass.frame(0x20, 1, payload), server)
            val delivered = f.events.filterIsInstance<Inbound.Frame>()
            for (e in delivered) assertTrue(xyz.mdhv.asom.lab.json.StrictJson.parse(e.frame.payload) is xyz.mdhv.asom.lab.json.ParseResult.Ok)
        }
    }

    @Test
    fun rawFramesAreEqualByContent() {
        assertEquals(RawFrame(1, 2, byteArrayOf(1)), RawFrame(1, 2, byteArrayOf(1)))
        assertContentEquals(byteArrayOf(0, 0, 0, 5, 0x15, 0, 0, 0, 1), FrameEncoder.encode(RawFrame(0x15, 1, ByteArray(0))))
    }

    @Test
    fun theDecoderRoleDecidesWhichWayAFrameMayTravel() {
        val hello = FrameEncoderBypass.frame(0x01, 0, "{}".toByteArray())
        assertTrue(decodeWhole(hello, server).events.single() is Inbound.Frame)
        assertEquals(Reason.WRONG_DIRECTION, failureOf(decodeWhole(hello, client)).reason)
        val head = FrameEncoderBypass.frame(0x14, 1, "{}".toByteArray())
        assertTrue(decodeWhole(head, client).events.single() is Inbound.Frame)
        assertEquals(Reason.WRONG_DIRECTION, failureOf(decodeWhole(head, server)).reason)
    }
}
