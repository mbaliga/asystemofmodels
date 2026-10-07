package xyz.mdhv.asom.lab.proto.session

import java.util.SplittableRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.json.B64Result
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.manifest.CompareMethod
import xyz.mdhv.asom.lab.manifest.Mode
import xyz.mdhv.asom.lab.manifest.RejectCode
import xyz.mdhv.asom.lab.manifest.Rejected
import xyz.mdhv.asom.lab.manifest.RollbackEntry
import xyz.mdhv.asom.lab.manifest.RollbackStore
import xyz.mdhv.asom.lab.manifest.Tier
import xyz.mdhv.asom.lab.manifest.TestOnlyKeys
import xyz.mdhv.asom.lab.manifest.Verifier
import xyz.mdhv.asom.lab.manifest.VerifyContext
import xyz.mdhv.asom.lab.proto.trust.StatusCodes
import xyz.mdhv.asom.lab.proto.trust.StoreRead
import xyz.mdhv.asom.lab.proto.wire.StoredTextProbe
import java.io.File

/** What the hostile-node suite counted. The line it prints is the evidence; a zero in any count fails the class. */
object W08 {
    val cases = AtomicInteger()
    val rowChecks = AtomicInteger()
    val kinds: MutableSet<Refusal> = ConcurrentHashMap.newKeySet()
    val framesAfterControlFailure = AtomicInteger()
    val controlFailureCases = AtomicInteger()
    val verdictsThroughSession: MutableSet<RejectCode> = ConcurrentHashMap.newKeySet()
    val verdictsDirect: MutableSet<RejectCode> = ConcurrentHashMap.newKeySet()

    fun finish(w: World) {
        cases.incrementAndGet()
        for (n in listOf(w.a.node, w.b.node)) kinds += n.counters.snapshot().filterValues { it > 0 }.keys
    }
}

/**
 * W08, the frame-level half (LAB_SPEC 7.4): a reference node that misbehaves in both roles, over in-memory connections. Every case checks (a) the typed refusal
 * the honest node sent, (b) that the session ended or not as LAB_SPEC 7.1 says, (c) every row against LAB_SPEC 7.6, written out by hand, and (d) the refusal
 * counter. Evidence label: LAB, oracle: self.
 */
class HostileFramesTest {
    companion object {
        @JvmStatic
        @AfterAll
        fun summary() {
            val k = Refusal.entries.size
            val line = "W08-frames: hostile cases: ${W08.cases.get()}; refusal kinds exercised: ${W08.kinds.size}/$k; row checks: ${W08.rowChecks.get()}; frames after control failure: ${W08.framesAfterControlFailure.get()}"
            println(line)
            println("W08-frames manifest verdict codes: ${(W08.verdictsThroughSession + W08.verdictsDirect).size}/${RejectCode.entries.size} (through a session: ${W08.verdictsThroughSession.size}; by the verifier directly, because the frame layer refuses the document first or the context is FILE mode: ${(W08.verdictsDirect - W08.verdictsThroughSession).size})")
            println("evidence: LAB, oracle: self, NOT DEVICE EVIDENCE")
            assertTrue(W08.cases.get() > 0 && W08.rowChecks.get() > 0, "W08-frames: a zero count")
            assertTrue(W08.controlFailureCases.get() > 0, "W08-frames: no control-row failure case ran")
            assertEquals(0, W08.framesAfterControlFailure.get(), "frames after a control-row failure")
            assertEquals(emptyList(), Refusal.entries.filter { it !in W08.kinds }, "refusal kinds never exercised")
            assertEquals(emptyList(), RejectCode.entries.filter { it !in W08.verdictsThroughSession && it !in W08.verdictsDirect }, "manifest verdicts never exercised")
        }
    }

    private fun case(name: String, seed: Long = name.hashCode().toLong(), keyA: String = "key1", keyB: String = "key2", block: (World) -> Unit) {
        val w = World(seed, keyAName = keyA, keyBName = keyB)
        block(w)
        W08.finish(w)
    }

    private fun assertRows(actual: List<Rw>, vararg expected: Rw) {
        assertEquals(expected.toList().map { it.toString() }, actual.map { it.toString() })
        W08.rowChecks.addAndGet(expected.size)
    }

    private val open = Rw(MeshKind.SESSION, "established")

    private fun close(residual: Long = 0) = Rw(MeshKind.SESSION, "close", 0, residual)

    private fun errorFrame(f: List<Fr>, code: String): Fr {
        val e = f.single { it.type == 6 }
        assertEquals(code, e.code)
        assertEquals(0L, e.stream)
        return e
    }

    private fun refused(w: World, r: Refusal, n: Int = 1) = assertEquals(n, w.b.node.counters.of(r), "refusal counter $r")

    // ------------------------------------------------------------------------------------------------------------ HELLO

    @Test
    fun helloWithAWrongNodeIdIsATypedProtocolError() = case("hello nodeId") { w ->
        val h = HostileClient(w)
        val hello = Build.hello(randomPin(1).nodeId)
        h.send(hello)
        val e = errorFrame(h.frames(), "PROTOCOL_ERROR")
        assertTrue(h.honest.closed && h.closedByHonest())
        assertRows(h.rows(), open, Rw(MeshKind.CONTROL, "HELLO", 0, hello.size.toLong()), Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), close())
        refused(w, Refusal.HELLO_NODE_MISMATCH)
    }

    @Test
    fun helloAtABadVersionIsVersionUnsupported() = case("hello version") { w ->
        val h = HostileClient(w)
        val hello = Build.hello(h.hostileNodeId, minV = 2, maxV = 2)
        h.send(hello)
        val e = errorFrame(h.frames(), "VERSION_UNSUPPORTED")
        assertTrue(h.honest.closed)
        assertRows(h.rows(), open, Rw(MeshKind.CONTROL, "HELLO", 0, hello.size.toLong()), Rw(MeshKind.CONTROL, "ERROR:VERSION_UNSUPPORTED", e.app, 0), close())
        refused(w, Refusal.HELLO_BAD_VERSION)
    }

    @Test
    fun helloWithAVersionOutsideItsOwnRangeIsAMalformedFrame() = case("hello v outside range") { w ->
        val h = HostileClient(w)
        val json = """{"endpoints":[],"features":["state"],"keyTier":"file","maxV":1,"minV":1,"name":"h","nodeId":"${h.hostileNodeId}","platform":"linux","proto":"asom-mesh/1","sessionNonce":"${nonceOf(2)}","sw":"h/1","ts":$CLOCK_BASE,"v":2}"""
        val bytes = Frames.encode(1, 0, json)
        h.send(bytes)
        val e = errorFrame(h.frames(), "PROTOCOL_ERROR")
        assertRows(h.rows(), open, Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), close(bytes.size.toLong()))
        refused(w, Refusal.BAD_PAYLOAD)
    }

    @Test
    fun helloFromAClockMoreThanTwoHoursAwayIsClockSkew() {
        for (delta in listOf(10_800_000L, -10_800_000L)) case("hello skew $delta") { w ->
            val h = HostileClient(w)
            val hello = Build.hello(h.hostileNodeId, ts = CLOCK_BASE + delta)
            h.send(hello)
            val e = errorFrame(h.frames(), "CLOCK_SKEW")
            assertTrue(h.honest.closed)
            assertRows(h.rows(), open, Rw(MeshKind.CONTROL, "HELLO", 0, hello.size.toLong()), Rw(MeshKind.CONTROL, "ERROR:CLOCK_SKEW", e.app, 0), close())
            refused(w, Refusal.CLOCK_SKEW)
        }
    }

    @Test
    fun aFrameBeforeHelloIsRefused() = case("frame before hello") { w ->
        val h = HostileClient(w)
        val req = Build.stateReq(1)
        h.send(req)
        val e = errorFrame(h.frames(), "PROTOCOL_ERROR")
        assertTrue(h.honest.closed && h.honest.sessionId.isNotEmpty())
        assertRows(h.rows(), open, Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), close(req.size.toLong()))
        refused(w, Refusal.FRAME_BEFORE_HELLO)
    }

    @Test
    fun aSecondHelloIsRefused() = case("second hello") { w ->
        val h = HostileClient(w)
        val hello = Build.hello(h.hostileNodeId)
        h.send(hello)
        val ack = h.frames().single()
        val second = Build.hello(h.hostileNodeId, nonceOf(3))
        h.send(second)
        val e = errorFrame(h.frames(), "PROTOCOL_ERROR")
        assertRows(
            h.rows(), open, Rw(MeshKind.CONTROL, "HELLO", 0, hello.size.toLong()), Rw(MeshKind.CONTROL, "HELLO_ACK", ack.app, 0), Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0),
            close(second.size.toLong()),
        )
        refused(w, Refusal.SECOND_HELLO)
    }

    // ------------------------------------------------------------------------------------------------------------ the codec's refusals, as the session sends them

    private fun rawHeader(len: Long, type: Int, stream: Long): ByteArray {
        val b = ByteArray(9)
        for (k in 0..3) b[k] = (len ushr (24 - 8 * k)).toByte()
        b[4] = type.toByte()
        for (k in 0..3) b[5 + k] = (stream ushr (24 - 8 * k)).toByte()
        return b
    }

    private fun establishedThenSend(w: World, bytes: ByteArray, code: String, refusal: Refusal, residual: Long) {
        val h = HostileClient(w)
        val hello = Build.hello(h.hostileNodeId)
        h.send(hello)
        val ack = h.frames().single()
        h.send(bytes)
        val e = errorFrame(h.frames(), code)
        assertTrue(h.honest.closed && h.closedByHonest())
        assertRows(
            h.rows(), open, Rw(MeshKind.CONTROL, "HELLO", 0, hello.size.toLong()), Rw(MeshKind.CONTROL, "HELLO_ACK", ack.app, 0), Rw(MeshKind.CONTROL, "ERROR:$code", e.app, 0), close(residual),
        )
        refused(w, refusal)
    }

    @Test
    fun oversizeFramesAreFrameTooLargeAndClose() {
        case("length above 16 MiB + 5") { w -> establishedThenSend(w, rawHeader(16_777_222, 0x10, 1), "FRAME_TOO_LARGE", Refusal.CODEC_FRAME_TOO_LARGE, 9) }
        case("json payload above 1 MiB") { w -> establishedThenSend(w, rawHeader(5 + 1_048_577L, 0x20, 1), "FRAME_TOO_LARGE", Refusal.CODEC_FRAME_TOO_LARGE, 9) }
        case("length below 5") { w -> establishedThenSend(w, rawHeader(4, 0x20, 1), "FRAME_TOO_LARGE", Refusal.CODEC_FRAME_TOO_LARGE, 9) }
        case("infer body above 8 MiB") { w -> establishedThenSend(w, rawHeader(5 + 8_388_609L, 0x13, 1), "FRAME_TOO_LARGE", Refusal.CODEC_FRAME_TOO_LARGE, 9) }
    }

    @Test
    fun anUnknownTypeBelow0x80IsAProtocolErrorAndCloses() {
        for (t in listOf(0x7F, 0x03, 0x04, 0x41, 0x42, 0x24)) case("unknown type $t") { w ->
            val bytes = Frames.encode(t, 0, "{}")
            establishedThenSend(w, bytes, "PROTOCOL_ERROR", Refusal.UNKNOWN_TYPE, bytes.size.toLong())
        }
    }

    @Test
    fun malformedJsonIsAProtocolErrorAndCloses() {
        val bodies = listOf("""{"v":1.5}""", """{"v":1,"v":1}""", "[1]", """{"v":""", "", """{"v":1e2}""", """{"v":9007199254740992}""", "{\"v\":1}x")
        for (b in bodies) case("malformed json $b") { w ->
            val bytes = Frames.encode(0x20, 1, b)
            establishedThenSend(w, bytes, "PROTOCOL_ERROR", Refusal.BAD_PAYLOAD, bytes.size.toLong())
        }
        case("invalid utf-8") { w ->
            val bytes = Frames.encode(0x20, 1, byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte(), ':'.code.toByte(), '1'.code.toByte(), '}'.code.toByte()))
            establishedThenSend(w, bytes, "PROTOCOL_ERROR", Refusal.BAD_PAYLOAD, bytes.size.toLong())
        }
    }

    @Test
    fun streamAndDirectionRulesAreEnforced() {
        case("offer on an even stream") { w ->
            val bytes = Build.offer(attemptIdOf(1), 2)
            establishedThenSend(w, bytes, "PROTOCOL_ERROR", Refusal.STREAM_RULE, bytes.size.toLong())
        }
        case("hello_ack sent to the server") { w ->
            val bytes = Build.ack(randomPin(2).nodeId)
            establishedThenSend(w, bytes, "PROTOCOL_ERROR", Refusal.WRONG_DIRECTION, bytes.size.toLong())
        }
        case("pair_hello on an established connection") { w ->
            val bytes = Frames.encode(0x30, 1, """{"v":1}""")
            establishedThenSend(w, bytes, "PROTOCOL_ERROR", Refusal.MODE_REJECTS_TYPE, bytes.size.toLong())
        }
        case("hello on a non-zero stream before hello") { w ->
            val h = HostileClient(w)
            val bytes = Frames.encode(1, 5, """{"v":1}""")
            h.send(bytes)
            val e = errorFrame(h.frames(), "PROTOCOL_ERROR")
            assertRows(h.rows(), open, Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), close(bytes.size.toLong()))
            refused(w, Refusal.STREAM_RULE)
        }
    }

    // ------------------------------------------------------------------------------------------------------------ attempts

    @Test
    fun aDuplicateAttemptIdIsDeclinedDuplicateAttempt() = case("duplicate attemptId") { w ->
        val h = HostileClient(w)
        h.establish()
        val x = attemptIdOf(1)
        val o1 = Build.offer(x, 1)
        h.send(o1)
        val accept = h.frames().single()
        assertEquals(0x11, accept.type)
        val o2 = Build.offer(x, 3)
        h.send(o2)
        val decline = h.frames().single()
        assertEquals(0x12, decline.type)
        assertEquals("DUPLICATE_ATTEMPT", decline.code)
        assertTrue(decline.text.contains("\"retryAfterMs\":5000"))
        assertTrue(!h.honest.closed, "a decline does not close the session")
        h.conn.close()
        h.drain()
        val rows = h.rows()
        assertRows(
            rows.drop(3), Rw(MeshKind.INFER_SERVED, "DUPLICATE_ATTEMPT", decline.app, o2.size.toLong(), Phase.OUTCOME),
            Rw(MeshKind.INFER_SERVED, "INTERRUPTED", accept.app, o1.size.toLong(), Phase.OUTCOME), close(),
        )
        refused(w, Refusal.DUPLICATE_ATTEMPT)
    }

    @Test
    fun aBodyWithoutAnAcceptedOfferIsAProtocolError() {
        case("body on an unknown stream") { w ->
            val h = HostileClient(w)
            h.establish()
            val body = Build.body(5)
            h.send(body)
            val e = errorFrame(h.frames(), "PROTOCOL_ERROR")
            assertRows(h.rows().drop(3), Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), close(body.size.toLong()))
            refused(w, Refusal.BODY_WITHOUT_OFFER)
            assertTrue(w.b.engine.opened.isEmpty())
        }
        case("body on a declined stream") { w ->
            val h = HostileClient(w)
            h.establish()
            h.send(Build.offer(attemptIdOf(2), 1, model = "nope"))
            assertEquals(0x12, h.frames().single().type)
            h.send(Build.body(1))
            errorFrame(h.frames(), "PROTOCOL_ERROR")
            refused(w, Refusal.BODY_WITHOUT_OFFER)
            assertTrue(w.b.engine.opened.isEmpty())
            W08.rowChecks.incrementAndGet()
        }
        case("a second body on a served stream") { w ->
            val h = HostileClient(w)
            h.establish()
            h.send(Build.offer(attemptIdOf(3), 1))
            h.frames()
            h.conn.writeRaw(Build.body(1))
            h.honest.pumpAvailable(1 shl 20)
            h.send(Build.body(1))
            assertTrue(h.frames().any { it.type == 6 && it.code == "PROTOCOL_ERROR" })
            refused(w, Refusal.BODY_WITHOUT_OFFER)
            W08.rowChecks.incrementAndGet()
        }
    }

    @Test
    fun aCancelForAnUnknownAttemptIsAProtocolError() {
        case("cancel on an unknown stream") { w ->
            val h = HostileClient(w)
            h.establish()
            val c = Build.cancel(attemptIdOf(4), 7)
            h.send(c)
            val e = errorFrame(h.frames(), "PROTOCOL_ERROR")
            assertRows(h.rows().drop(3), Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), close(c.size.toLong()))
            refused(w, Refusal.CANCEL_UNKNOWN_ATTEMPT)
        }
        case("cancel with the wrong attemptId on a live stream") { w ->
            val h = HostileClient(w)
            h.establish()
            h.send(Build.offer(attemptIdOf(5), 1))
            h.frames()
            h.send(Build.cancel(attemptIdOf(6), 1))
            errorFrame(h.frames(), "PROTOCOL_ERROR")
            refused(w, Refusal.CANCEL_UNKNOWN_ATTEMPT)
            W08.rowChecks.incrementAndGet()
        }
        case("cancel after the attempt finished is a late cancel, not an unknown attempt") { w ->
            val h = HostileClient(w)
            h.establish()
            h.send(Build.offer(attemptIdOf(7), 1))
            h.send(Build.body(1))
            assertEquals(0x16, h.frames().last().type)
            val late = Build.cancel(attemptIdOf(7), 1)
            h.send(late)
            assertEquals(emptyList(), h.frames().map { it.name }, "a late cancel gets no reply")
            assertTrue(!h.honest.closed)
            assertEquals(1, w.b.node.lateCancels.get())
            refused(w, Refusal.CANCEL_UNKNOWN_ATTEMPT, 0)
            h.send(Build.cancel(attemptIdOf(8), 1))
            errorFrame(h.frames(), "PROTOCOL_ERROR")
            refused(w, Refusal.CANCEL_UNKNOWN_ATTEMPT, 1)
            assertTrue(w.b.rows().last().let { it.meshCode == "close" && it.bytesIn == late.size.toLong() + Build.cancel(attemptIdOf(8), 1).size }, "the uncovered input is on the SESSION close row")
            W08.rowChecks.incrementAndGet()
        }
    }

    @Test
    fun reusingAStreamIdIsAProtocolError() = case("stream reuse") { w ->
        val h = HostileClient(w)
        h.establish()
        val o1 = Build.offer(attemptIdOf(8), 1)
        h.send(o1)
        val accept = h.frames().single()
        val o2 = Build.offer(attemptIdOf(9), 1)
        h.send(o2)
        val e = errorFrame(h.frames(), "PROTOCOL_ERROR")
        assertRows(
            h.rows().drop(3), Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), Rw(MeshKind.INFER_SERVED, "INTERRUPTED", accept.app, o1.size.toLong(), Phase.OUTCOME),
            close(o2.size.toLong()),
        )
        refused(w, Refusal.STREAM_REUSE)
    }

    @Test
    fun anOfferAboveTheLimitsIsAnAttemptErrorFrameTooLargeAndTheSessionStays() = case("offer above limits") { w ->
        val h = HostileClient(w)
        h.establish()
        val o = Build.offer(attemptIdOf(10), 1, promptBytes = 9_000_000)
        h.send(o)
        val f = h.frames().single()
        assertEquals(6, f.type)
        assertEquals("FRAME_TOO_LARGE", f.code)
        assertEquals(attemptIdOf(10), f.attemptId)
        assertTrue(!h.honest.closed)
        assertRows(h.rows().drop(3), Rw(MeshKind.INFER_SERVED, "FRAME_TOO_LARGE", f.app, o.size.toLong(), Phase.OUTCOME))
        refused(w, Refusal.OFFER_TOO_LARGE)
    }

    @Test
    fun theLenderDeclinesForConditionsPresenceThermalAndBusy() {
        case("peer unavailable: serving conditions") { w ->
            w.b.serving = ServingView(servingConditionsOk = false)
            val h = HostileClient(w)
            h.establish()
            h.send(Build.offer(attemptIdOf(11), 1))
            val f = h.frames().single()
            assertEquals("PEER_UNAVAILABLE", f.code)
            assertTrue(f.text.contains("\"retryAfterMs\":30000"))
            assertRows(h.rows().drop(3), Rw(MeshKind.INFER_SERVED, "PEER_UNAVAILABLE", f.app, Build.offer(attemptIdOf(11), 1).size.toLong(), Phase.OUTCOME))
            refused(w, Refusal.PEER_UNAVAILABLE)
        }
        case("peer unavailable: presence, with the hold-down as retryAfterMs") { w ->
            w.b.serving = ServingView(presenceActive = true, presenceHoldRemainingMs = 123_456)
            val h = HostileClient(w)
            h.establish()
            h.send(Build.offer(attemptIdOf(12), 1))
            val f = h.frames().single()
            assertEquals("PEER_UNAVAILABLE", f.code)
            assertTrue(f.text.contains("\"retryAfterMs\":123456"))
            assertTrue(!f.text.contains("presence") && !f.text.contains("user"))
            W08.rowChecks.incrementAndGet()
        }
        case("peer unavailable: predicted thermal hold (row 6a)") { w ->
            w.b.serving = ServingView(predictedThermalHold = true)
            val h = HostileClient(w)
            h.establish()
            h.send(Build.offer(attemptIdOf(13), 1))
            assertEquals("PEER_UNAVAILABLE", h.frames().single().code)
            W08.rowChecks.incrementAndGet()
        }
        case("peer busy: estimated start after the deadline (row 6b)") { w ->
            w.b.serving = ServingView(estStartMs = 60_001)
            val h = HostileClient(w)
            h.establish()
            h.send(Build.offer(attemptIdOf(14), 1, deadlineMs = 60_000))
            assertEquals("PEER_BUSY", h.frames().single().code)
            refused(w, Refusal.PEER_BUSY)
            W08.rowChecks.incrementAndGet()
        }
        case("peer busy: concurrency (row 7)") { w ->
            val h = HostileClient(w)
            h.establish()
            h.send(Build.offer(attemptIdOf(15), 1))
            assertEquals(0x11, h.frames().single().type)
            h.send(Build.offer(attemptIdOf(16), 3))
            assertEquals("PEER_BUSY", h.frames().single().code)
            refused(w, Refusal.PEER_BUSY)
            W08.rowChecks.incrementAndGet()
        }
        case("model not offered") { w ->
            val h = HostileClient(w)
            h.establish()
            h.send(Build.offer(attemptIdOf(17), 1, model = "unknown-model"))
            assertEquals("MODEL_NOT_OFFERED", h.frames().single().code)
            refused(w, Refusal.MODEL_NOT_OFFERED)
            W08.rowChecks.incrementAndGet()
        }
    }

    // ------------------------------------------------------------------------------------------------------------ the registry

    private fun pinRow(w: World) = (w.b.store.read(w.a.pin) as StoreRead.Present).row

    @Test
    fun aRevokedPinMidSessionGetsGoawayRevokedAndIsClosed() = case("revoked mid-session") { w ->
        val h = HostileClient(w)
        val hello = Build.hello(h.hostileNodeId)
        h.send(hello)
        val ack = h.frames().single()
        w.b.registry.revoke(w.a.pin, CLOCK_BASE)
        val g = h.frames().single()
        assertEquals(5, g.type)
        assertEquals("revoked", g.reason)
        assertTrue(h.honest.closed && h.closedByHonest())
        assertRows(h.rows(), open, Rw(MeshKind.CONTROL, "HELLO", 0, hello.size.toLong()), Rw(MeshKind.CONTROL, "HELLO_ACK", ack.app, 0), Rw(MeshKind.CONTROL, "GOAWAY:revoked", g.app, 0), close())
        h.send(Build.offer(attemptIdOf(1), 1))
        assertTrue(w.b.engine.opened.isEmpty())
    }

    @Test
    fun aSuspendedPinMidSessionGetsGoawaySuspended() = case("suspended mid-session") { w ->
        val h = HostileClient(w)
        h.establish()
        w.b.registry.pause(w.a.pin, CLOCK_BASE)
        val g = h.frames().single()
        assertEquals("suspended", g.reason)
        assertTrue(h.honest.closed)
        assertEquals("GOAWAY:suspended", h.rows()[3].code)
        W08.rowChecks.incrementAndGet()
    }

    @Test
    fun aRevocationDuringAServedAttemptCancelsTheEngineAndFinishesTheRow() = case("revoked during a served attempt") { w ->
        w.b.engine.script = { listOf(EngineEvent.Head(200, "m1")) + List(20) { EngineEvent.Chunk(ByteArray(8)) } }
        val h = HostileClient(w)
        h.establish()
        val offer = Build.offer(attemptIdOf(1), 1)
        h.send(offer)
        val accept = h.frames().single()
        val body = Build.body(1)
        h.conn.writeRaw(body)
        h.honest.pumpAvailable(1 shl 20)
        assertEquals(1, w.b.engine.opened.size)
        w.b.registry.revoke(w.a.pin, CLOCK_BASE)
        val g = h.frames().last()
        assertEquals("revoked", g.reason)
        assertEquals(listOf(w.b.engine.opened[0].attemptId), w.b.engine.cancelled)
        assertRows(
            h.rows().drop(3), Rw(MeshKind.INFER_SERVED, null, 0, 0, Phase.INTENT), Rw(MeshKind.CONTROL, "GOAWAY:revoked", g.app, 0),
            Rw(MeshKind.INFER_SERVED, "INTERRUPTED", accept.app, (offer.size + body.size).toLong(), Phase.OUTCOME), close(),
        )
    }

    private fun racePath(name: String, status: Int, request: (HostileClient) -> ByteArray, code: String = "PEER_NOT_PAIRED") = case(name) { w ->
        val h = HostileClient(w)
        h.establish()
        w.b.store.plant(pinRow(w).copy(status = status))
        val req = request(h)
        h.send(req)
        val f = h.frames()
        assertEquals(6, f.last().type)
        assertEquals(code, f.last().code)
        assertTrue(h.honest.closed && h.closedByHonest())
        assertTrue(w.b.engine.opened.isEmpty(), "a peer that left PAIRED is never served")
        refused(w, Refusal.PEER_NOT_PAIRED)
        W08.rowChecks.incrementAndGet()
    }

    @Test
    fun theRegistryIsReadAtEveryAuthorisedFrameNotOncePerSession() {
        for (status in listOf(StatusCodes.REVOKED, StatusCodes.SUSPENDED)) {
            racePath("offer from a pin that left PAIRED ($status)", status, { Build.offer(attemptIdOf(1), 1) })
            racePath("state request from a pin that left PAIRED ($status)", status, { Build.stateReq(1) })
            racePath("manifest request from a pin that left PAIRED ($status)", status, { Build.manifestReq(1) })
        }
        case("body from a pin that left PAIRED after the offer was accepted") { w ->
            val h = HostileClient(w)
            h.establish()
            h.send(Build.offer(attemptIdOf(2), 1))
            assertEquals(0x11, h.frames().single().type)
            w.b.store.plant(pinRow(w).copy(status = StatusCodes.REVOKED))
            h.send(Build.body(1))
            val f = h.frames().single()
            assertEquals("PEER_NOT_PAIRED", f.code)
            assertEquals(attemptIdOf(2), f.attemptId)
            assertTrue(w.b.engine.opened.isEmpty() && h.honest.closed)
            refused(w, Refusal.PEER_NOT_PAIRED)
            W08.rowChecks.incrementAndGet()
        }
        case("an unreadable registry row denies") { w ->
            val h = HostileClient(w)
            h.establish()
            w.b.store.markUnreadable(w.a.pin)
            h.send(Build.stateReq(1))
            assertEquals("PEER_NOT_PAIRED", h.frames().last().code)
            assertTrue(h.honest.closed)
            W08.rowChecks.incrementAndGet()
        }
    }

    @Test
    fun aMissingScopeIsScopeDeniedAtEachFrameAndTheSessionStays() = case("scope removed mid-session") { w ->
        val h = HostileClient(w)
        h.establish()
        w.b.registry.setInboundScopes(w.a.pin, emptySet())
        val offer = Build.offer(attemptIdOf(1), 1)
        h.send(offer)
        val d = h.frames().single()
        assertEquals(0x12, d.type)
        assertEquals("SCOPE_DENIED", d.code)
        val sr = Build.stateReq(3)
        h.send(sr)
        val e1 = h.frames().single()
        assertEquals("SCOPE_DENIED", e1.code)
        val mr = Build.manifestReq(5)
        h.send(mr)
        val e2 = h.frames().single()
        assertEquals("SCOPE_DENIED", e2.code)
        assertTrue(!h.honest.closed)
        assertRows(
            h.rows().drop(3), Rw(MeshKind.INFER_SERVED, "SCOPE_DENIED", d.app, offer.size.toLong(), Phase.OUTCOME), Rw(MeshKind.CONTROL, "STATE_REQ", 0, sr.size.toLong()),
            Rw(MeshKind.CONTROL, "ERROR:SCOPE_DENIED", e1.app, 0), Rw(MeshKind.CONTROL, "MANIFEST_REQ", 0, mr.size.toLong()), Rw(MeshKind.CONTROL, "ERROR:SCOPE_DENIED", e2.app, 0),
        )
        refused(w, Refusal.SCOPE_DENIED, 3)
    }

    @Test
    fun aScopeRemovedAfterAcceptDeniesTheBody() = case("scope removed between offer and body") { w ->
        val h = HostileClient(w)
        h.establish()
        h.send(Build.offer(attemptIdOf(1), 1))
        h.frames()
        w.b.registry.setInboundScopes(w.a.pin, emptySet())
        h.send(Build.body(1))
        val f = h.frames().single()
        assertEquals(6, f.type)
        assertEquals("SCOPE_DENIED", f.code)
        assertEquals(attemptIdOf(1), f.attemptId)
        assertTrue(w.b.engine.opened.isEmpty() && !h.honest.closed)
        refused(w, Refusal.SCOPE_DENIED)
        W08.rowChecks.incrementAndGet()
    }

    // ------------------------------------------------------------------------------------------------------------ extensions

    @Test
    fun aFloodOfExtensionFramesIsSkippedWithOneExtIgnoredRowEach() = case("extension flood") { w ->
        val h = HostileClient(w)
        h.establish()
        val rnd = SplittableRandom(99)
        val sizes = ArrayList<Long>()
        val buf = java.io.ByteArrayOutputStream()
        repeat(5_000) {
            val payload = ByteArray(rnd.nextInt(41)) { rnd.nextInt(256).toByte() }
            buf.write(Build.ext(0x80 + rnd.nextInt(128), rnd.nextLong(1L shl 32), payload))
            sizes += 9L + payload.size
        }
        h.send(buf.toByteArray())
        assertTrue(!h.honest.closed)
        val req = Build.stateReq(1)
        h.send(req)
        val state = h.frames().single()
        assertEquals(0x21, state.type)
        val expected = sizes.map { Rw(MeshKind.CONTROL, "EXT_IGNORED", 0, it) } + listOf(
            Rw(MeshKind.CONTROL, "STATE_REQ", 0, req.size.toLong()), Rw(MeshKind.CONTROL, "STATE", state.app, 0),
        )
        assertRows(h.rows().drop(3), *expected.toTypedArray())
    }

    // ------------------------------------------------------------------------------------------------------------ splitting

    @Test
    fun theSameSessionAtEveryByteBoundaryGivesTheSameFramesAndRows() {
        fun script(h: HostileClient): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(Build.hello(h.hostileNodeId))
            out.write(Build.ext(0x90, 0, ByteArray(5)))
            out.write(Build.stateReq(1))
            out.write(Build.offer(attemptIdOf(1), 3))
            out.write(Build.body(3))
            out.write(Build.offer(attemptIdOf(2), 5, model = "nope"))
            return out.toByteArray()
        }

        fun run(chunks: List<IntRange>?): Pair<List<String>, List<String>> {
            val w = World(5)
            val h = HostileClient(w)
            h.autoAdvance = false
            val bytes = script(h)
            if (chunks == null) h.sendByByte(bytes) else for (c in chunks) h.send(bytes.copyOfRange(c.first, c.last + 1))
            h.settle()
            return h.frames().map { "${it.type}/${it.stream}/${it.text}" } to h.rows().map { "${it.kind}|${it.code}|${it.out}|${it.inn}|${it.phase}" }.map { it }
        }

        val total = script(HostileClient(World(5))).size
        val whole = run(listOf(0 until total))
        assertTrue(whole.first.isNotEmpty() && whole.second.size > 8)
        assertEquals(whole, run(null), "one byte at a time")
        var splits = 0
        for (k in 1 until total) {
            assertEquals(whole, run(listOf(0 until k, k until total)), "split at byte $k")
            splits++
        }
        W08.cases.addAndGet(splits + 1)
        W08.rowChecks.addAndGet(whole.second.size * (splits + 2))
    }

    // ------------------------------------------------------------------------------------------------------------ FC-2 against hostile traffic

    @Test
    fun aControlRowFailureSendsNothingFurtherNotEvenGoaway() {
        for (point in 0..2) for (sticky in listOf(false, true)) {
            val w = World(40L + point, failB = FailPlan(setOf(point), sticky))
            val h = HostileClient(w)
            h.send(Build.hello(h.hostileNodeId))
            val failedAt = w.log.all<Ev.AppendFailed>().first { it.node == "B" }
            val after = w.log.all<Ev.Write>().filter { it.conn == "B<H" && it.seq > failedAt.seq }
            assertEquals(0, after.size, "frames written after the control-row failure at append #$point")
            val before = w.log.all<Ev.Write>().filter { it.conn == "B<H" && it.seq < failedAt.seq }.sumOf { it.bytes.size }
            assertEquals(before, h.conn.takeAll().size, "the hostile end received bytes that were written after the failure")
            assertTrue(h.honest.closed && h.closedByHonest() && h.honest.closedByLedgerFailure)
            assertTrue(w.b.ledger.unavailable == sticky, "the ledger is marked unavailable while the sink keeps failing (point $point sticky=$sticky)")
            val close = w.b.rows().lastOrNull { it.meshKind == MeshKind.SESSION }
            if (sticky) assertTrue(close == null || close.meshCode == "established", "a sticky sink cannot take the close row") else assertEquals("close:ledger-failure", close?.meshCode)
            W08.framesAfterControlFailure.addAndGet(after.size)
            W08.controlFailureCases.incrementAndGet()
            W08.rowChecks.addAndGet(2)
            W08.finish(w)
        }
    }

    // ------------------------------------------------------------------------------------------------------------ the same node as a requester

    private fun stateJson(extra: String = "", seq: String = "4711") =
        """{"availability":{"fsm":"SERVING"},"engine":{"backend":"vulkan","commit":"4f1c2ab","confVersion":"1.0.0","held":[]},"manifest":null,"power":{"batteryBand":null,"charging":false,"source":"ac"$extra},"queue":{"bucket":0},"sampledAgeMs":800,"seq":$seq,"thermal":{"band":0,"governor":"RUN"},"v":1}"""

    @Test
    fun aStateWithFloatsIsRefusedAndWithPresenceFieldsIsIgnored() {
        case("state with a float") { w ->
            val s = HostileServer(w)
            s.establish()
            s.honest.requestState {}
            s.frames()
            val bytes = Frames.encode(0x21, 1, stateJson(seq = "4711.5"))
            s.send(bytes)
            val e = s.frames().single()
            assertEquals("PROTOCOL_ERROR", e.code)
            assertTrue(s.honest.closed)
            W08.rowChecks.incrementAndGet()
            assertTrue(w.a.node.counters.of(Refusal.BAD_PAYLOAD) == 1)
        }
        for (extra in listOf(""","user":"alice"""", ""","screen":true""", ""","inflight":3""")) case("state with a presence field $extra") { w ->
            val s = HostileServer(w)
            s.establish()
            var result: StateResult? = null
            s.honest.requestState { result = it }
            s.frames()
            val bytes = Frames.encode(0x21, 1, stateJson(extra))
            s.send(bytes)
            assertTrue(result is StateResult.Ok, "a receiver ignores unknown members")
            for (needle in listOf("alice", "screen", "inflight", "user")) assertTrue(!StoredTextProbe.holds((result as StateResult.Ok).doc, needle), "a presence member was stored: $needle")
            assertTrue(!s.honest.closed)
            val rows = s.rows().filter { it.code == "STATE" }
            assertRows(rows, Rw(MeshKind.CONTROL, "STATE", 0, bytes.size.toLong()))
        }
    }

    @Test
    fun aHelloAckThatIsWrongIsRefused() {
        case("ack nodeId") { w ->
            val s = HostileServer(w)
            s.frames()
            s.send(Build.ack(randomPin(3).nodeId))
            assertEquals("PROTOCOL_ERROR", s.frames().single().code)
            assertTrue(s.honest.closed)
            assertTrue(w.a.node.counters.of(Refusal.HELLO_NODE_MISMATCH) == 1)
            W08.rowChecks.incrementAndGet()
        }
        case("ack version") { w ->
            val s = HostileServer(w)
            s.frames()
            s.send(Build.ack(s.hostileNodeId, v = 2))
            assertEquals("VERSION_UNSUPPORTED", s.frames().single().code)
            assertTrue(w.a.node.counters.of(Refusal.HELLO_BAD_VERSION) == 1)
            W08.rowChecks.incrementAndGet()
        }
        case("ack clock skew") { w ->
            val s = HostileServer(w)
            s.frames()
            s.send(Build.ack(s.hostileNodeId, ts = CLOCK_BASE + 10_800_000))
            assertEquals("CLOCK_SKEW", s.frames().single().code)
            assertTrue(w.a.node.counters.of(Refusal.CLOCK_SKEW) == 1)
            W08.rowChecks.incrementAndGet()
        }
        case("frame before hello_ack") { w ->
            val s = HostileServer(w)
            s.frames()
            s.send(Frames.encode(0x11, 1, """{"attemptId":"${attemptIdOf(1)}","fileSha256":"${"a".repeat(64)}","servedModel":"m1"}"""))
            assertEquals("PROTOCOL_ERROR", s.frames().single().code)
            assertTrue(w.a.node.counters.of(Refusal.FRAME_BEFORE_HELLO) == 1)
            W08.rowChecks.incrementAndGet()
        }
    }

    @Test
    fun aReplyOnAStreamNobodyOpenedOrOutOfOrderIsAProtocolError() {
        val accept = Frames.encode(0x11, 1, """{"attemptId":"${attemptIdOf(1)}","fileSha256":"${"a".repeat(64)}","servedModel":"m1"}""")
        case("accept on a stream nobody opened") { w ->
            val s = HostileServer(w)
            s.establish()
            s.send(accept)
            assertEquals("PROTOCOL_ERROR", s.frames().single().code)
            assertTrue(w.a.node.counters.of(Refusal.UNSOLICITED_REPLY) == 1)
            W08.rowChecks.incrementAndGet()
        }
        case("unsolicited state") { w ->
            val s = HostileServer(w)
            s.establish()
            s.send(Frames.encode(0x21, 1, stateJson()))
            assertEquals("PROTOCOL_ERROR", s.frames().single().code)
            assertTrue(w.a.node.counters.of(Refusal.UNSOLICITED_REPLY) == 1)
            W08.rowChecks.incrementAndGet()
        }
        case("unsolicited manifest") { w ->
            val s = HostileServer(w)
            s.establish()
            s.send(Frames.encode(0x23, 1, "{}"))
            assertEquals("PROTOCOL_ERROR", s.frames().single().code)
            W08.rowChecks.incrementAndGet()
        }
        case("head before accept, chunk before head, end with the wrong attemptId") { w ->
            for (n in 0..2) {
                val s = HostileServer(w.takeIf { n == 0 } ?: World(77L + n))
                s.establish()
                val rec = Recorder()
                s.honest.offer(spec(), "{}".toByteArray(), rec)
                s.frames()
                s.send(
                    when (n) {
                        0 -> Frames.encode(0x14, 1, """{"attemptId":"${attemptIdOf(1)}","engine":"local","servedModel":"m1","status":200}""")
                        1 -> Frames.encode(0x15, 1, ByteArray(4))
                        else -> Frames.encode(0x16, 1, """{"attemptId":"${attemptIdOf(9)}","status":200,"terminal":"done"}""")
                    },
                )
                assertEquals("PROTOCOL_ERROR", s.frames().single().code)
                assertTrue(s.honest.closed)
                assertEquals(599, rec.outcome?.status, "an attempt open when the session closes ends as a lost peer")
                assertEquals("PEER_UNREACHABLE", rec.outcome?.meshCode)
                W08.rowChecks.addAndGet(3)
                if (n > 0) W08.finish(s.w)
            }
        }
    }

    @Test
    fun aPeerGoawayAndATruncatedFrameCloseTheSession() {
        case("goaway from the lender") { w ->
            val s = HostileServer(w)
            s.establish()
            val g = Frames.encode(5, 0, """{"reason":"shutdown"}""")
            s.send(g)
            assertTrue(s.honest.closed)
            val rows = s.rows()
            assertRows(rows.takeLast(2), Rw(MeshKind.CONTROL, "GOAWAY:shutdown", 0, g.size.toLong()), Rw(MeshKind.SESSION, "close", 0, 0))
        }
        case("a frame cut in half and then the end of the stream") { w ->
            val s = HostileServer(w)
            s.establish()
            val half = Frames.encode(0x21, 1, stateJson()).copyOf(40)
            s.send(half)
            s.serverConn.close()
            s.drain()
            assertTrue(s.honest.closed)
            assertEquals(1, w.a.node.counters.of(Refusal.TRUNCATED))
            assertRows(s.rows().takeLast(1), Rw(MeshKind.SESSION, "close", 0, half.size.toLong()))
        }
    }

    // ------------------------------------------------------------------------------------------------------------ manifests

    @Test
    fun amanifestThatFailsVerificationIsRecordedWithItsTypedVerdictForEveryCode() {
        val repo = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot is not set"))
        val doc = (StrictJson.parse(File(repo, "lab/conformance/manifest/M03-verify-reject.json").readBytes()) as ParseResult.Ok).value as JObject
        val vectors = (doc["vectors"] as JArray).items.map { it as JObject }
        var through = 0
        var direct = 0
        for (v in vectors) {
            val id = (v["id"] as JString).value
            val input = v["input"] as JObject
            val expect = RejectCode.valueOf(((v["expect"] as JObject)["reject"] as JString).value)
            val bytes: ByteArray = when {
                input["documentFill"] != null -> (input["documentFill"] as JObject).let { f -> ByteArray((f["count"] as JInt).value.toInt()) { (f["byte"] as JInt).value.toByte() } }
                input["documentHex"] != null -> (input["documentHex"] as JString).value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                else -> (input["document"] as JString).value.toByteArray(Charsets.UTF_8)
            }
            val ctx = vectorContext(input)
            val verdict = Verifier.verify(bytes, ctx)
            assertTrue(verdict is Rejected && verdict.code == expect, "$id: the verifier says ${(verdict as? Rejected)?.code}, the vector says $expect")
            W08.verdictsDirect += expect
            val frameable = bytes.size <= 1_048_576 && (StrictJson.parse(bytes) as? ParseResult.Ok)?.value is JObject
            val pinned = TestOnlyKeys.entries.firstOrNull { e -> ctx.pinnedSpki?.contentEquals(e.spki) == true }
            if (ctx.mode == Mode.MESH && frameable && pinned != null) {
                val honestKey = TestOnlyKeys.entries.first { it.name != pinned.name }.name
                case("manifest $id", seed = id.hashCode().toLong(), keyA = honestKey, keyB = pinned.name) { w ->
                    val s = HostileServer(w)
                    s.establish()
                    w.a.manifest.ctxOverride = ctx
                    var result: ManifestResult? = null
                    s.honest.requestManifest(ByteArray(32) { it.toByte() }) { result = it }
                    s.frames()
                    val frame = Frames.encode(0x23, 1, bytes)
                    s.send(frame)
                    val r = result
                    assertTrue(r is ManifestResult.Rejected && r.code == expect, "$id through a session: ${(r as? ManifestResult.Rejected)?.code} (${r?.let { it::class.simpleName }}), expected $expect")
                    assertRows(s.rows().takeLast(1), Rw(MeshKind.MANIFEST_RECEIVED, expect.name, 0, frame.size.toLong()))
                    assertEquals(1, w.a.node.counters.of(Refusal.MANIFEST_REJECTED))
                    assertTrue(!s.honest.closed, "a manifest that fails verification does not close the session")
                    W08.verdictsThroughSession += expect
                }
                through++
            } else {
                direct++
            }
        }
        println("W08-frames manifest vectors: ${vectors.size} (through a session: $through, by the verifier directly: $direct)")
        assertTrue(through > 20 && vectors.size == 71)
    }

    private fun vectorContext(input: JObject): VerifyContext {
        val c = input["context"] as JObject
        fun str(k: String) = (c[k] as? JString)?.value
        val rollback = (c["rollback"] as? JObject)?.members?.associate { (k, x) -> k to RollbackEntry(((x as JObject)["seq"] as JInt).value, (x["bodyDigest"] as JString).value) }.orEmpty()
        val store = if (rollback.isEmpty()) null else object : RollbackStore {
            override fun get(nodeId: String, audience: String): RollbackEntry? = rollback["$nodeId|$audience"]
        }
        return VerifyContext(
            mode = Mode.valueOf(str("mode")!!), pinnedSpki = str("pinnedSpkiB64")?.let { java.util.Base64.getDecoder().decode(it) },
            expectedChallenge = str("expectedChallengeB64u")?.let { (Base64Strict.decodeUrlNoPad(it) as B64Result.Ok).bytes },
            comparedFingerprint = str("comparedFingerprint"), compareMethod = str("compareMethod")?.let { m -> CompareMethod.entries.first { it.wire == m } }, rollback = store,
            requiredTier = Tier.valueOf(str("requiredTier")!!), confFloor = str("confFloor")!!, knownBadConf = ((c["knownBadConf"] as JArray).items.map { (it as JString).value }).toSet(),
            productionKeys = (c["productionKeys"] as JBool).value, nowMs = (input["nowMs"] as JInt).value,
        )
    }

    @Test
    fun aManifestUnavailableOnTheLenderIsATypedErrorOnTheStream() = case("manifest unavailable") { w ->
        val h = HostileClient(w)
        h.establish()
        w.b.manifest.available = false
        val mr = Build.manifestReq(1)
        h.send(mr)
        val e = h.frames().single()
        assertEquals("MANIFEST_UNAVAILABLE", e.code)
        assertEquals(1L, e.stream)
        assertRows(h.rows().drop(3), Rw(MeshKind.CONTROL, "MANIFEST_REQ", 0, mr.size.toLong()), Rw(MeshKind.CONTROL, "ERROR:MANIFEST_UNAVAILABLE", e.app, 0))
        refused(w, Refusal.MANIFEST_UNAVAILABLE)
        assertTrue(!h.honest.closed)
    }

    @Test
    fun aManifestKeyThatDoesNotHashToThePinIsNotTrusted() = case("manifest signer key is not the pin", keyA = "key3", keyB = "key2") { w ->
        val s = HostileServer(w)
        s.establish()
        var result: ManifestResult? = null
        val key1 = TestOnlyKeys.key("key1")
        w.a.manifest.ctxOverride = VerifyContext(Mode.MESH, pinnedSpki = key1.spki, expectedChallenge = ByteArray(32), confFloor = "0.2.0", productionKeys = false, nowMs = MANIFEST_NOW)
        s.honest.requestManifest(ByteArray(32)) { result = it }
        s.frames()
        val repo = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot is not set"))
        val doc = (StrictJson.parse(File(repo, "lab/conformance/manifest/M03-verify-reject.json").readBytes()) as ParseResult.Ok).value as JObject
        val container = (((doc["vectors"] as JArray).items[0] as JObject)["input"] as JObject)["document"] as JString
        s.send(Frames.encode(0x23, 1, container.value.toByteArray(Charsets.UTF_8)))
        assertTrue(result is ManifestResult.Rejected && (result as ManifestResult.Rejected).code == RejectCode.KEY_NOT_PINNED, "the peer is key2; a context that pins key1 is not used: ${(result as? ManifestResult.Rejected)?.code}")
        W08.rowChecks.incrementAndGet()
    }
}
