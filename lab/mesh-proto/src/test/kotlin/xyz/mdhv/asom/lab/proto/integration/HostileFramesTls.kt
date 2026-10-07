package xyz.mdhv.asom.lab.proto.integration

import java.io.File
import java.util.SplittableRandom
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.B64Result
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.ledger.FailMode
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.manifest.CompareMethod
import xyz.mdhv.asom.lab.manifest.Mode
import xyz.mdhv.asom.lab.manifest.RejectCode
import xyz.mdhv.asom.lab.manifest.Rejected
import xyz.mdhv.asom.lab.manifest.RollbackEntry
import xyz.mdhv.asom.lab.manifest.RollbackStore
import xyz.mdhv.asom.lab.manifest.TestOnlyKeys
import xyz.mdhv.asom.lab.manifest.Tier
import xyz.mdhv.asom.lab.manifest.Verifier
import xyz.mdhv.asom.lab.manifest.VerifyContext
import xyz.mdhv.asom.lab.policy.StateDoc
import xyz.mdhv.asom.lab.proto.session.Build
import xyz.mdhv.asom.lab.proto.session.CLOCK_BASE
import xyz.mdhv.asom.lab.proto.session.EngineEvent
import xyz.mdhv.asom.lab.proto.session.FailPlan
import xyz.mdhv.asom.lab.proto.session.Fr
import xyz.mdhv.asom.lab.proto.session.Frames
import xyz.mdhv.asom.lab.proto.session.MANIFEST_NOW
import xyz.mdhv.asom.lab.proto.session.ManifestResult
import xyz.mdhv.asom.lab.proto.session.Refusal
import xyz.mdhv.asom.lab.proto.session.Rw
import xyz.mdhv.asom.lab.proto.session.ServingView
import xyz.mdhv.asom.lab.proto.session.StateResult
import xyz.mdhv.asom.lab.proto.session.attemptIdOf
import xyz.mdhv.asom.lab.proto.session.nonceOf
import xyz.mdhv.asom.lab.proto.session.randomPin
import xyz.mdhv.asom.lab.proto.session.spec
import xyz.mdhv.asom.lab.proto.trust.StatusCodes
import xyz.mdhv.asom.lab.proto.trust.StoreRead
import xyz.mdhv.asom.lab.proto.wire.StoredTextProbe

/**
 * W08, the frame-level half, over REAL loopback TLS (LAB_SPEC 7.4, 7.6, 8.1): the hostile cases of the session track re-run through the TLS transport, in both roles.
 * The hostile end holds a genuine TLS 1.3 connection with a valid, paired identity and writes raw plaintext frames into it; the honest end is the real session engine
 * with the real peer registry, the real per-frame ledger writer and the real JSONL sink. Every case checks the typed refusal on the wire, whether the session and the TLS
 * connection ended, every row against a table written by hand from LAB_SPEC 7.6, and (in [finishCase]) L-L15 for the session. Evidence label: LAB, oracle: self.
 */
object HostileFramesTls {
    private val open = Rw(MeshKind.SESSION, "established")

    private fun close(residual: Long = 0) = Rw(MeshKind.SESSION, "close", 0, residual)

    private fun errorFrame(f: List<Fr>, code: String): Fr {
        val e = f.single { it.type == 6 }
        assertEquals(code, e.code)
        assertEquals(0L, e.stream)
        W08Tls.noteRefusalCodes(listOf(e))
        return e
    }

    private fun refused(w: TlsWorld, r: Refusal, n: Int = 1, kit: TNode = w.b) = assertEquals(n, kit.counters.of(r), "refusal counter $r")

    private fun clientCase(name: String, seed: Long = (name.hashCode().toLong() and 0xFFFFFF) + 1000, keyA: String = "key1", keyB: String = "key2", limitsB: xyz.mdhv.asom.lab.proto.wire.Limits? = null, block: (TlsWorld, RawClient) -> Unit) {
        TlsWorld(seed, keyA = keyA, keyB = keyB, limitsB = limitsB).use { w ->
            val c = w.rawClient()
            try {
                block(w, c)
            } catch (e: Throwable) {
                throw AssertionError("hostile client case '$name': ${e.message}", e)
            }
        }
    }

    private fun serverCase(name: String, seed: Long = (name.hashCode().toLong() and 0xFFFFFF) + 1000, keyA: String = "key1", keyB: String = "key2", block: (TlsWorld, RawServer) -> Unit) {
        TlsWorld(seed, keyA = keyA, keyB = keyB).use { w ->
            val s = w.rawServer()
            try {
                block(w, s)
            } catch (e: Throwable) {
                throw AssertionError("hostile server case '$name': ${e.message}", e)
            }
        }
    }

    private fun done(w: TlsWorld, c: RawClient, tlsClosed: Boolean = false) = finishCase(w, w.b, c.honest, c.accepted.conn!!, c.peer, expectTlsClosed = tlsClosed)

    private fun doneServer(w: TlsWorld, s: RawServer, tlsClosed: Boolean = false) = finishCase(w, w.a, s.session, w.a.conn!!, s.peer, expectTlsClosed = tlsClosed)

    // ------------------------------------------------------------------------------------------------------------ HELLO

    fun hello() {
        clientCase("hello nodeId") { w, c ->
            val hello = Build.hello(randomPin(1).nodeId)
            c.peer.writeRaw(hello)
            val e = errorFrame(c.peer.awaitFrames(1), "PROTOCOL_ERROR")
            assertRows(w.b, 0, open, Rw(MeshKind.CONTROL, "HELLO", 0, hello.size.toLong()), Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), close())
            assertTrue(c.honest.closed)
            refused(w, Refusal.HELLO_NODE_MISMATCH)
            done(w, c, tlsClosed = true)
        }
        clientCase("hello version") { w, c ->
            val hello = Build.hello(c.hostileNodeId, minV = 2, maxV = 2)
            c.peer.writeRaw(hello)
            val e = errorFrame(c.peer.awaitFrames(1), "VERSION_UNSUPPORTED")
            assertRows(w.b, 0, open, Rw(MeshKind.CONTROL, "HELLO", 0, hello.size.toLong()), Rw(MeshKind.CONTROL, "ERROR:VERSION_UNSUPPORTED", e.app, 0), close())
            refused(w, Refusal.HELLO_BAD_VERSION)
            done(w, c, tlsClosed = true)
        }
        clientCase("hello v outside range") { w, c ->
            val json = """{"endpoints":[],"features":["state"],"keyTier":"file","maxV":1,"minV":1,"name":"h","nodeId":"${c.hostileNodeId}","platform":"linux","proto":"asom-mesh/1","sessionNonce":"${nonceOf(2)}","sw":"h/1","ts":$CLOCK_BASE,"v":2}"""
            val bytes = Frames.encode(1, 0, json)
            c.peer.writeRaw(bytes)
            val e = errorFrame(c.peer.awaitFrames(1), "PROTOCOL_ERROR")
            assertRows(w.b, 0, open, Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), close(bytes.size.toLong()))
            refused(w, Refusal.BAD_PAYLOAD)
            done(w, c, tlsClosed = true)
        }
        for (delta in listOf(10_800_000L, -10_800_000L)) clientCase("hello skew $delta") { w, c ->
            val hello = Build.hello(c.hostileNodeId, ts = w.clockB.peek() + delta)
            c.peer.writeRaw(hello)
            val e = errorFrame(c.peer.awaitFrames(1), "CLOCK_SKEW")
            assertRows(w.b, 0, open, Rw(MeshKind.CONTROL, "HELLO", 0, hello.size.toLong()), Rw(MeshKind.CONTROL, "ERROR:CLOCK_SKEW", e.app, 0), close())
            refused(w, Refusal.CLOCK_SKEW)
            done(w, c, tlsClosed = true)
        }
        clientCase("frame before hello") { w, c ->
            val req = Build.stateReq(1)
            c.peer.writeRaw(req)
            val e = errorFrame(c.peer.awaitFrames(1), "PROTOCOL_ERROR")
            assertRows(w.b, 0, open, Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), close(req.size.toLong()))
            refused(w, Refusal.FRAME_BEFORE_HELLO)
            done(w, c, tlsClosed = true)
        }
        clientCase("second hello") { w, c ->
            val hello = Build.hello(c.hostileNodeId)
            c.peer.writeRaw(hello)
            val ack = c.peer.awaitFrames(1).single()
            val second = Build.hello(c.hostileNodeId, nonceOf(3))
            c.peer.writeRaw(second)
            val e = errorFrame(c.peer.awaitFrames(1), "PROTOCOL_ERROR")
            assertRows(
                w.b, 0, open, Rw(MeshKind.CONTROL, "HELLO", 0, hello.size.toLong()), Rw(MeshKind.CONTROL, "HELLO_ACK", ack.app, 0), Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0),
                close(second.size.toLong()),
            )
            refused(w, Refusal.SECOND_HELLO)
            done(w, c, tlsClosed = true)
        }
    }

    // ------------------------------------------------------------------------------------------------------------ the codec's refusals, as the session sends them

    private fun rawHeader(len: Long, type: Int, stream: Long): ByteArray {
        val b = ByteArray(9)
        for (k in 0..3) b[k] = (len ushr (24 - 8 * k)).toByte()
        b[4] = type.toByte()
        for (k in 0..3) b[5 + k] = (stream ushr (24 - 8 * k)).toByte()
        return b
    }

    private fun establishedThenSend(name: String, bytes: ByteArray, code: String, refusal: Refusal, residual: Long) = clientCase(name) { w, c ->
        val hello = Build.hello(c.hostileNodeId)
        c.peer.writeRaw(hello)
        val ack = c.peer.awaitFrames(1).single()
        c.peer.writeRaw(bytes)
        val e = errorFrame(c.peer.awaitFrames(1), code)
        assertRows(
            w.b, 0, open, Rw(MeshKind.CONTROL, "HELLO", 0, hello.size.toLong()), Rw(MeshKind.CONTROL, "HELLO_ACK", ack.app, 0), Rw(MeshKind.CONTROL, "ERROR:$code", e.app, 0), close(residual),
        )
        refused(w, refusal)
        W08Tls.noteRefusalCodes(listOf(e))
        done(w, c, tlsClosed = true)
    }

    fun codec() {
        establishedThenSend("length above 16 MiB + 5", rawHeader(16_777_222, 0x10, 1), "FRAME_TOO_LARGE", Refusal.CODEC_FRAME_TOO_LARGE, 9)
        establishedThenSend("json payload above 1 MiB", rawHeader(5 + 1_048_577L, 0x20, 1), "FRAME_TOO_LARGE", Refusal.CODEC_FRAME_TOO_LARGE, 9)
        establishedThenSend("length below 5", rawHeader(4, 0x20, 1), "FRAME_TOO_LARGE", Refusal.CODEC_FRAME_TOO_LARGE, 9)
        establishedThenSend("infer body above 8 MiB", rawHeader(5 + 8_388_609L, 0x13, 1), "FRAME_TOO_LARGE", Refusal.CODEC_FRAME_TOO_LARGE, 9)
        for (t in listOf(0x7F, 0x03, 0x04, 0x41, 0x42, 0x24)) {
            val bytes = Frames.encode(t, 0, "{}")
            establishedThenSend("unknown type $t", bytes, "PROTOCOL_ERROR", Refusal.UNKNOWN_TYPE, bytes.size.toLong())
        }
        val bodies = listOf("""{"v":1.5}""", """{"v":1,"v":1}""", "[1]", """{"v":""", "", """{"v":1e2}""", """{"v":9007199254740992}""", "{\"v\":1}x")
        for (b in bodies) {
            val bytes = Frames.encode(0x20, 1, b)
            establishedThenSend("malformed json $b", bytes, "PROTOCOL_ERROR", Refusal.BAD_PAYLOAD, bytes.size.toLong())
        }
        val badUtf8 = Frames.encode(0x20, 1, byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte(), ':'.code.toByte(), '1'.code.toByte(), '}'.code.toByte()))
        establishedThenSend("invalid utf-8", badUtf8, "PROTOCOL_ERROR", Refusal.BAD_PAYLOAD, badUtf8.size.toLong())
        val even = Build.offer(attemptIdOf(1), 2)
        establishedThenSend("offer on an even stream", even, "PROTOCOL_ERROR", Refusal.STREAM_RULE, even.size.toLong())
        val ackToServer = Build.ack(randomPin(2).nodeId)
        establishedThenSend("hello_ack sent to the server", ackToServer, "PROTOCOL_ERROR", Refusal.WRONG_DIRECTION, ackToServer.size.toLong())
        val pair = Frames.encode(0x30, 1, """{"v":1}""")
        establishedThenSend("pair_hello on an established connection", pair, "PROTOCOL_ERROR", Refusal.MODE_REJECTS_TYPE, pair.size.toLong())
        clientCase("hello on a non-zero stream before hello") { w, c ->
            val bytes = Frames.encode(1, 5, """{"v":1}""")
            c.peer.writeRaw(bytes)
            val e = errorFrame(c.peer.awaitFrames(1), "PROTOCOL_ERROR")
            assertRows(w.b, 0, open, Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), close(bytes.size.toLong()))
            refused(w, Refusal.STREAM_RULE)
            done(w, c, tlsClosed = true)
        }
    }

    // ------------------------------------------------------------------------------------------------------------ attempts

    fun attempts() {
        clientCase("duplicate attemptId") { w, c ->
            c.establish()
            val x = attemptIdOf(1)
            val o1 = Build.offer(x, 1)
            c.peer.writeRaw(o1)
            val accept = c.peer.awaitFrames(1).single()
            assertEquals(0x11, accept.type)
            val o2 = Build.offer(x, 3)
            c.peer.writeRaw(o2)
            val decline = c.peer.awaitFrames(1).single()
            assertEquals(0x12, decline.type)
            assertEquals("DUPLICATE_ATTEMPT", decline.code)
            assertTrue(decline.text.contains("\"retryAfterMs\":5000"))
            assertTrue(!c.honest.closed, "a decline does not close the session")
            c.peer.close()
            Wait.until("the session to close") { c.honest.closed }
            assertRows(
                w.b, 3, Rw(MeshKind.INFER_SERVED, "DUPLICATE_ATTEMPT", decline.app, o2.size.toLong(), Phase.OUTCOME),
                Rw(MeshKind.INFER_SERVED, "INTERRUPTED", accept.app, o1.size.toLong(), Phase.OUTCOME), close(),
            )
            refused(w, Refusal.DUPLICATE_ATTEMPT)
            done(w, c)
        }
        clientCase("body on an unknown stream") { w, c ->
            c.establish()
            val body = Build.body(5)
            c.peer.writeRaw(body)
            val e = errorFrame(c.peer.awaitFrames(1), "PROTOCOL_ERROR")
            assertRows(w.b, 3, Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), close(body.size.toLong()))
            refused(w, Refusal.BODY_WITHOUT_OFFER)
            assertTrue(w.b.engine.opened.isEmpty())
            done(w, c, tlsClosed = true)
        }
        clientCase("body on a declined stream") { w, c ->
            c.establish()
            c.peer.writeRaw(Build.offer(attemptIdOf(2), 1, model = "nope"))
            assertEquals(0x12, c.peer.awaitFrames(1).single().type)
            c.peer.writeRaw(Build.body(1))
            errorFrame(c.peer.awaitFrames(1), "PROTOCOL_ERROR")
            refused(w, Refusal.BODY_WITHOUT_OFFER)
            assertTrue(w.b.engine.opened.isEmpty())
            done(w, c, tlsClosed = true)
        }
        clientCase("a second body on a served stream") { w, c ->
            c.establish()
            w.b.engine.script = { listOf(EngineEvent.Head(200, "m1")) + List(400) { EngineEvent.Chunk(ByteArray(8)) } }
            w.b.engine.paceNanos = PacedEngine.DEFAULT_PACE_NANOS
            c.peer.writeRaw(Build.offer(attemptIdOf(3), 1))
            c.peer.awaitFrames(1)
            c.peer.writeRaw(Build.body(1))
            Wait.until("the engine to open") { w.b.engine.opened.isNotEmpty() }
            c.peer.writeRaw(Build.body(1))
            val got = c.peer.awaitType(6, "the protocol error")
            assertTrue(got.any { it.type == 6 && it.code == "PROTOCOL_ERROR" })
            refused(w, Refusal.BODY_WITHOUT_OFFER)
            w.b.engine.paceNanos = 0
            done(w, c, tlsClosed = true)
        }
        clientCase("cancel on an unknown stream") { w, c ->
            c.establish()
            val cancel = Build.cancel(attemptIdOf(4), 7)
            c.peer.writeRaw(cancel)
            val e = errorFrame(c.peer.awaitFrames(1), "PROTOCOL_ERROR")
            assertRows(w.b, 3, Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), close(cancel.size.toLong()))
            refused(w, Refusal.CANCEL_UNKNOWN_ATTEMPT)
            done(w, c, tlsClosed = true)
        }
        clientCase("cancel with the wrong attemptId on a live stream") { w, c ->
            c.establish()
            c.peer.writeRaw(Build.offer(attemptIdOf(5), 1))
            c.peer.awaitFrames(1)
            c.peer.writeRaw(Build.cancel(attemptIdOf(6), 1))
            errorFrame(c.peer.awaitFrames(1), "PROTOCOL_ERROR")
            refused(w, Refusal.CANCEL_UNKNOWN_ATTEMPT)
            done(w, c, tlsClosed = true)
        }
        clientCase("cancel after the attempt finished is a late cancel, not an unknown attempt") { w, c ->
            c.establish()
            c.peer.writeRaw(Build.offer(attemptIdOf(7), 1))
            c.peer.writeRaw(Build.body(1))
            assertEquals(0x16, c.peer.awaitType(0x16, "INFER_END").last().type)
            val late = Build.cancel(attemptIdOf(7), 1)
            c.peer.writeRaw(late)
            val unknown = Build.cancel(attemptIdOf(8), 1)
            c.peer.writeRaw(unknown)
            val e = c.peer.awaitFrames(1).single()
            assertEquals("PROTOCOL_ERROR", e.code, "the late cancel got no reply; only the unknown one is refused")
            assertEquals(1, w.b.node.lateCancels.get())
            refused(w, Refusal.CANCEL_UNKNOWN_ATTEMPT)
            Wait.until("the session to close") { c.honest.closed }
            Wait.until("the close row") { w.b.rows().last().meshCode == "close" }
            assertEquals(late.size.toLong() + unknown.size, w.b.rows().last().bytesIn, "the uncovered input is on the SESSION close row")
            W08Tls.rowChecks.incrementAndGet()
            done(w, c, tlsClosed = true)
        }
        clientCase("stream reuse") { w, c ->
            c.establish()
            val o1 = Build.offer(attemptIdOf(8), 1)
            c.peer.writeRaw(o1)
            val accept = c.peer.awaitFrames(1).single()
            val o2 = Build.offer(attemptIdOf(9), 1)
            c.peer.writeRaw(o2)
            val e = errorFrame(c.peer.awaitFrames(1), "PROTOCOL_ERROR")
            assertRows(
                w.b, 3, Rw(MeshKind.CONTROL, "ERROR:PROTOCOL_ERROR", e.app, 0), Rw(MeshKind.INFER_SERVED, "INTERRUPTED", accept.app, o1.size.toLong(), Phase.OUTCOME), close(o2.size.toLong()),
            )
            refused(w, Refusal.STREAM_REUSE)
            done(w, c, tlsClosed = true)
        }
        clientCase("offer above limits") { w, c ->
            c.establish()
            val o = Build.offer(attemptIdOf(10), 1, promptBytes = 9_000_000)
            c.peer.writeRaw(o)
            val f = c.peer.awaitFrames(1).single()
            assertEquals(6, f.type)
            assertEquals("FRAME_TOO_LARGE", f.code)
            assertEquals(attemptIdOf(10), f.attemptId)
            assertTrue(!c.honest.closed)
            assertRows(w.b, 3, Rw(MeshKind.INFER_SERVED, "FRAME_TOO_LARGE", f.app, o.size.toLong(), Phase.OUTCOME))
            refused(w, Refusal.OFFER_TOO_LARGE)
            W08Tls.noteRefusalCodes(listOf(f))
            done(w, c)
        }
        clientCase("peer unavailable: serving conditions") { w, c ->
            w.b.serving = ServingView(servingConditionsOk = false)
            c.establish()
            val o = Build.offer(attemptIdOf(11), 1)
            c.peer.writeRaw(o)
            val f = c.peer.awaitFrames(1).single()
            assertEquals("PEER_UNAVAILABLE", f.code)
            assertTrue(f.text.contains("\"retryAfterMs\":30000"))
            assertRows(w.b, 3, Rw(MeshKind.INFER_SERVED, "PEER_UNAVAILABLE", f.app, o.size.toLong(), Phase.OUTCOME))
            refused(w, Refusal.PEER_UNAVAILABLE)
            done(w, c)
        }
        clientCase("peer unavailable: presence, with the hold-down as retryAfterMs") { w, c ->
            w.b.serving = ServingView(presenceActive = true, presenceHoldRemainingMs = 123_456)
            c.establish()
            c.peer.writeRaw(Build.offer(attemptIdOf(12), 1))
            val f = c.peer.awaitFrames(1).single()
            assertEquals("PEER_UNAVAILABLE", f.code)
            assertTrue(f.text.contains("\"retryAfterMs\":123456"))
            assertTrue(!f.text.contains("presence") && !f.text.contains("user"))
            W08Tls.rowChecks.incrementAndGet()
            done(w, c)
        }
        clientCase("peer unavailable: predicted thermal hold (row 6a)") { w, c ->
            w.b.serving = ServingView(predictedThermalHold = true)
            c.establish()
            c.peer.writeRaw(Build.offer(attemptIdOf(13), 1))
            assertEquals("PEER_UNAVAILABLE", c.peer.awaitFrames(1).single().code)
            W08Tls.rowChecks.incrementAndGet()
            done(w, c)
        }
        clientCase("peer busy: estimated start after the deadline (row 6b)") { w, c ->
            w.b.serving = ServingView(estStartMs = 60_001)
            c.establish()
            c.peer.writeRaw(Build.offer(attemptIdOf(14), 1, deadlineMs = 60_000))
            assertEquals("PEER_BUSY", c.peer.awaitFrames(1).single().code)
            refused(w, Refusal.PEER_BUSY)
            done(w, c)
        }
        clientCase("peer busy: concurrency (row 7)") { w, c ->
            c.establish()
            c.peer.writeRaw(Build.offer(attemptIdOf(15), 1))
            assertEquals(0x11, c.peer.awaitFrames(1).single().type)
            c.peer.writeRaw(Build.offer(attemptIdOf(16), 3))
            assertEquals("PEER_BUSY", c.peer.awaitFrames(1).single().code)
            refused(w, Refusal.PEER_BUSY)
            done(w, c)
        }
        clientCase("model not offered") { w, c ->
            c.establish()
            c.peer.writeRaw(Build.offer(attemptIdOf(17), 1, model = "unknown-model"))
            assertEquals("MODEL_NOT_OFFERED", c.peer.awaitFrames(1).single().code)
            refused(w, Refusal.MODEL_NOT_OFFERED)
            done(w, c)
        }
    }

    // ------------------------------------------------------------------------------------------------------------ the registry

    private fun pinRow(w: TlsWorld) = (w.b.store.read(w.a.pin) as StoreRead.Present).row

    fun registry() {
        clientCase("revoked mid-session") { w, c ->
            val hello = Build.hello(c.hostileNodeId)
            c.peer.writeRaw(hello)
            val ack = c.peer.awaitFrames(1).single()
            w.b.registry.revoke(w.a.pin, CLOCK_BASE)
            val g = c.peer.awaitFrames(1).single()
            assertEquals(5, g.type)
            assertEquals("revoked", g.reason)
            Wait.until("the session to close") { c.honest.closed }
            c.peer.awaitEof()
            assertRows(w.b, 0, open, Rw(MeshKind.CONTROL, "HELLO", 0, hello.size.toLong()), Rw(MeshKind.CONTROL, "HELLO_ACK", ack.app, 0), Rw(MeshKind.CONTROL, "GOAWAY:revoked", g.app, 0), close())
            assertTrue(w.b.engine.opened.isEmpty())
            done(w, c, tlsClosed = true)
        }
        clientCase("suspended mid-session") { w, c ->
            c.establish()
            w.b.registry.pause(w.a.pin, CLOCK_BASE)
            val g = c.peer.awaitFrames(1).single()
            assertEquals("suspended", g.reason)
            Wait.until("the session to close") { c.honest.closed }
            Wait.until("the GOAWAY row") { w.b.rows().size >= 4 }
            assertEquals("GOAWAY:suspended", w.b.rows()[3].meshCode)
            W08Tls.rowChecks.incrementAndGet()
            done(w, c, tlsClosed = true)
        }
        clientCase("revoked during a served attempt") { w, c ->
            w.b.engine.script = { listOf(EngineEvent.Head(200, "m1")) + List(400) { EngineEvent.Chunk(ByteArray(8)) } }
            w.b.engine.paceNanos = PacedEngine.DEFAULT_PACE_NANOS
            c.establish()
            val offer = Build.offer(attemptIdOf(1), 1)
            c.peer.writeRaw(offer)
            val accept = c.peer.awaitFrames(1).single()
            val body = Build.body(1)
            c.peer.writeRaw(body)
            Wait.until("the engine to run") { w.b.engine.opened.size == 1 }
            w.b.registry.revoke(w.a.pin, CLOCK_BASE)
            val got = c.peer.awaitType(5, "GOAWAY")
            assertEquals("revoked", got.last().reason)
            Wait.until("the session to close") { c.honest.closed }
            assertEquals(listOf(w.b.engine.opened[0].attemptId), w.b.engine.cancelled.toList())
            val rows = w.b.rows().drop(3).filter { it.meshKind != MeshKind.CONTROL || it.meshCode?.startsWith("GOAWAY") == true }
            val outcome = rows.last { it.phase == Phase.OUTCOME }
            assertEquals("INTERRUPTED", outcome.meshCode)
            assertEquals((offer.size + body.size).toLong(), outcome.bytesIn)
            assertEquals(Phase.INTENT, rows.first { it.meshKind == MeshKind.INFER_SERVED }.phase)
            assertTrue(accept.app > 0)
            w.b.engine.paceNanos = 0
            W08Tls.rowChecks.addAndGet(3)
            done(w, c, tlsClosed = true)
        }
        for (status in listOf(StatusCodes.REVOKED, StatusCodes.SUSPENDED)) {
            for ((label, req) in listOf<Pair<String, () -> ByteArray>>(
                "offer" to { Build.offer(attemptIdOf(1), 1) }, "state request" to { Build.stateReq(1) }, "manifest request" to { Build.manifestReq(1) },
            )) {
                clientCase("$label from a pin that left PAIRED ($status)") { w, c ->
                    c.establish()
                    w.b.store.plant(pinRow(w).copy(status = status))
                    c.peer.writeRaw(req())
                    val f = c.peer.awaitType(6, "PEER_NOT_PAIRED")
                    assertEquals("PEER_NOT_PAIRED", f.last().code)
                    Wait.until("the session to close") { c.honest.closed }
                    assertTrue(w.b.engine.opened.isEmpty(), "a peer that left PAIRED is never served")
                    refused(w, Refusal.PEER_NOT_PAIRED)
                    W08Tls.rowChecks.incrementAndGet()
                    done(w, c, tlsClosed = true)
                }
            }
        }
        clientCase("body from a pin that left PAIRED after the offer was accepted") { w, c ->
            c.establish()
            c.peer.writeRaw(Build.offer(attemptIdOf(2), 1))
            assertEquals(0x11, c.peer.awaitFrames(1).single().type)
            w.b.store.plant(pinRow(w).copy(status = StatusCodes.REVOKED))
            c.peer.writeRaw(Build.body(1))
            val f = c.peer.awaitFrames(1).single()
            assertEquals("PEER_NOT_PAIRED", f.code)
            assertEquals(attemptIdOf(2), f.attemptId)
            Wait.until("the session to close") { c.honest.closed }
            assertTrue(w.b.engine.opened.isEmpty())
            refused(w, Refusal.PEER_NOT_PAIRED)
            done(w, c, tlsClosed = true)
        }
        clientCase("an unreadable registry row denies") { w, c ->
            c.establish()
            w.b.store.markUnreadable(w.a.pin)
            c.peer.writeRaw(Build.stateReq(1))
            assertEquals("PEER_NOT_PAIRED", c.peer.awaitType(6, "PEER_NOT_PAIRED").last().code)
            Wait.until("the session to close") { c.honest.closed }
            done(w, c, tlsClosed = true)
        }
        clientCase("scope removed mid-session") { w, c ->
            c.establish()
            w.b.registry.setInboundScopes(w.a.pin, emptySet())
            val offer = Build.offer(attemptIdOf(1), 1)
            c.peer.writeRaw(offer)
            val d = c.peer.awaitFrames(1).single()
            assertEquals(0x12, d.type)
            assertEquals("SCOPE_DENIED", d.code)
            val sr = Build.stateReq(3)
            c.peer.writeRaw(sr)
            val e1 = c.peer.awaitFrames(1).single()
            assertEquals("SCOPE_DENIED", e1.code)
            val mr = Build.manifestReq(5)
            c.peer.writeRaw(mr)
            val e2 = c.peer.awaitFrames(1).single()
            assertEquals("SCOPE_DENIED", e2.code)
            assertTrue(!c.honest.closed)
            assertRows(
                w.b, 3, Rw(MeshKind.INFER_SERVED, "SCOPE_DENIED", d.app, offer.size.toLong(), Phase.OUTCOME), Rw(MeshKind.CONTROL, "STATE_REQ", 0, sr.size.toLong()),
                Rw(MeshKind.CONTROL, "ERROR:SCOPE_DENIED", e1.app, 0), Rw(MeshKind.CONTROL, "MANIFEST_REQ", 0, mr.size.toLong()), Rw(MeshKind.CONTROL, "ERROR:SCOPE_DENIED", e2.app, 0),
            )
            refused(w, Refusal.SCOPE_DENIED, 3)
            W08Tls.noteRefusalCodes(listOf(e1, e2))
            done(w, c)
        }
        clientCase("scope removed between offer and body") { w, c ->
            c.establish()
            c.peer.writeRaw(Build.offer(attemptIdOf(1), 1))
            c.peer.awaitFrames(1)
            w.b.registry.setInboundScopes(w.a.pin, emptySet())
            c.peer.writeRaw(Build.body(1))
            val f = c.peer.awaitFrames(1).single()
            assertEquals(6, f.type)
            assertEquals("SCOPE_DENIED", f.code)
            assertEquals(attemptIdOf(1), f.attemptId)
            assertTrue(w.b.engine.opened.isEmpty() && !c.honest.closed)
            refused(w, Refusal.SCOPE_DENIED)
            done(w, c)
        }
    }

    // ------------------------------------------------------------------------------------------------------------ extensions and splitting

    /** [count] is smaller than the in-memory suite's 5,000: every extension frame is one fsync'd row on the real JSONL sink. */
    fun extensions(count: Int = 400) {
        clientCase("extension flood") { w, c ->
            c.establish()
            val rnd = SplittableRandom(99)
            val sizes = ArrayList<Long>()
            val buf = java.io.ByteArrayOutputStream()
            repeat(count) {
                val payload = ByteArray(rnd.nextInt(41)) { rnd.nextInt(256).toByte() }
                buf.write(Build.ext(0x80 + rnd.nextInt(128), rnd.nextLong(1L shl 32), payload))
                sizes += 9L + payload.size
            }
            c.peer.writeRaw(buf.toByteArray())
            val req = Build.stateReq(1)
            c.peer.writeRaw(req)
            val state = c.peer.awaitFrames(1).single()
            assertEquals(0x21, state.type)
            assertTrue(!c.honest.closed)
            val expected = sizes.map { Rw(MeshKind.CONTROL, "EXT_IGNORED", 0, it) } + listOf(Rw(MeshKind.CONTROL, "STATE_REQ", 0, req.size.toLong()), Rw(MeshKind.CONTROL, "STATE", state.app, 0))
            assertRows(w.b, 3, *expected.toTypedArray())
            done(w, c)
        }
    }

    private fun splitScript(nodeId: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(Build.hello(nodeId))
        out.write(Build.ext(0x90, 0, ByteArray(5)))
        out.write(Build.stateReq(1))
        out.write(Build.offer(attemptIdOf(2), 3, model = "nope"))
        out.write(Build.manifestReq(5))
        out.write(Build.ext(0x95, 0, ByteArray(3)))
        out.write(Build.stateReq(7))
        return out.toByteArray()
    }

    private fun runSplit(seed: Long, chunks: List<IntRange>?): Pair<List<String>, List<String>> {
        var result: Pair<List<String>, List<String>>? = null
        TlsWorld(seed).use { w ->
            val c = w.rawClient()
            val bytes = splitScript(c.hostileNodeId)
            if (chunks == null) for (b in bytes) c.peer.writeRaw(byteArrayOf(b)) else for (r in chunks) c.peer.writeRaw(bytes.copyOfRange(r.first, r.last + 1))
            // HELLO_ACK, STATE, DECLINE, then the manifest answer and the second STATE: five answers
            val frames = c.peer.awaitFrames(5, "five answers")
            Wait.until("the last row") { w.b.rows().size >= 12 }
            Wait.stable("the rows") { w.b.rows().size }
            result = frames.map { "${it.type}/${it.stream}/${it.text.take(40)}" } to w.b.rows().map { "${it.meshKind}|${it.meshCode}|${it.bytesOut}|${it.bytesIn}|${it.phase}" }
            finishCase(w, w.b, c.honest, c.accepted.conn!!, c.peer)
        }
        W08Tls.splitRuns.incrementAndGet()
        return result!!
    }

    /** The same bytes, written whole, one byte per TLS record, and cut at sampled points (a different TLS record boundary each time): the same frames and the same rows. */
    fun splitting(samples: Int = 12) {
        val total = splitScript(randomPin(0).nodeId).size
        val whole = runSplit(500, listOf(0 until total))
        assertTrue(whole.first.size == 5 && whole.second.size >= 12, "the reference run: ${whole.first.size} answers, ${whole.second.size} rows")
        assertEquals(whole, runSplit(501, null), "one byte per record")
        val rnd = SplittableRandom(77)
        val points = (listOf(1, total - 1, 9, 10) + List(samples - 4) { 1 + rnd.nextInt(total - 1) }).distinct()
        for (k in points) assertEquals(whole, runSplit(502L + k, listOf(0 until k, k until total)), "split at byte $k")
        W08Tls.rowChecks.addAndGet(whole.second.size * (points.size + 2))
    }

    // ------------------------------------------------------------------------------------------------------------ FC-2 against hostile traffic

    fun controlRowFailures() {
        for (point in 0..2) for (sticky in listOf(false, true)) for (mode in listOf(FailMode.BEFORE_WRITE, FailMode.AFTER_WRITE)) {
            TlsWorld(40L + point, failB = FailPlan(setOf(point), sticky, mode)).use { w ->
                val c = w.rawClient()
                try {
                    c.peer.writeRaw(Build.hello(c.hostileNodeId))
                    Wait.until("the honest session to close after the failure") { c.honest.closed }
                    Wait.until("the SESSION close row to be attempted") {
                        w.log.snapshot().any { (it is xyz.mdhv.asom.lab.proto.session.Ev.Appended && it.row.meshCode == "close:ledger-failure") || (it is xyz.mdhv.asom.lab.proto.session.Ev.AppendFailed && it.row.meshCode == "close:ledger-failure") }
                    }
                    val failed = w.log.snapshot().filterIsInstance<xyz.mdhv.asom.lab.proto.session.Ev.AppendFailed>().first { it.node == "B" }
                    val after = w.log.snapshot().filterIsInstance<xyz.mdhv.asom.lab.proto.session.Ev.Write>().filter { it.conn == "B<H" && it.seq > failed.seq }
                    assertEquals(0, after.size, "frames handed to TLS after the control-row failure at append #$point")
                    val probe = w.b.probe.probes.first { it.seq == failed.seq }
                    val tap = c.accepted.conn!!.tap
                    val onWire = tap.bytesWritten - probe.tapWritten
                    assertTrue(onWire <= L13Tls.ALERT_RECORD_BYTES && tap.recordsOut - probe.recordsOut <= 1, "the socket carried $onWire bytes after the failure")
                    c.peer.awaitEof()
                    assertTrue(c.peer.receivedBytes <= probe.plainWritten, "the hostile end received ${c.peer.receivedBytes} plaintext bytes, ${probe.plainWritten} were handed to TLS before the failure")
                    assertTrue(c.honest.closedByLedgerFailure && c.accepted.conn!!.closedByUs)
                    assertTrue(w.b.ledger.unavailable == sticky, "the ledger is marked unavailable while the sink keeps failing (point $point sticky=$sticky)")
                    W08Tls.framesAfterControlFailure.addAndGet(after.size)
                    W08Tls.controlFailureCases.incrementAndGet()
                    W08Tls.tapAfterFailureChecks.incrementAndGet()
                    W08Tls.rowChecks.addAndGet(2)
                    W08Tls.finish(w)
                } catch (e: Throwable) {
                    throw AssertionError("control-row failure at #$point sticky=$sticky $mode: ${e.message}", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ the same node as a requester

    private fun stateJson(extra: String = "", seq: String = "4711") =
        """{"availability":{"fsm":"SERVING"},"engine":{"backend":"vulkan","commit":"4f1c2ab","confVersion":"1.0.0","held":[]},"manifest":null,"power":{"batteryBand":null,"charging":false,"source":"ac"$extra},"queue":{"bucket":0},"sampledAgeMs":800,"seq":$seq,"thermal":{"band":0,"governor":"RUN"},"v":1}"""

    fun requester() {
        serverCase("state with a float") { w, s ->
            s.establish()
            s.session.requestState {}
            s.peer.awaitFrames(1)
            s.peer.writeRaw(Frames.encode(0x21, 1, stateJson(seq = "4711.5")))
            val e = s.peer.awaitFrames(1).single()
            assertEquals("PROTOCOL_ERROR", e.code)
            Wait.until("the session to close") { s.session.closed }
            assertEquals(1, w.a.counters.of(Refusal.BAD_PAYLOAD))
            W08Tls.rowChecks.incrementAndGet()
            doneServer(w, s, tlsClosed = true)
        }
        for (extra in listOf(""","user":"alice"""", ""","screen":true""", ""","inflight":3""")) serverCase("state with a presence field $extra") { w, s ->
            s.establish()
            var result: StateResult? = null
            s.session.requestState { result = it }
            s.peer.awaitFrames(1)
            val bytes = Frames.encode(0x21, 1, stateJson(extra))
            s.peer.writeRaw(bytes)
            Wait.until("the answer") { result != null }
            assertTrue(result is StateResult.Ok, "a receiver ignores unknown members")
            for (needle in listOf("alice", "screen", "inflight", "user")) assertTrue(!StoredTextProbe.holds((result as StateResult.Ok).doc, needle), "a presence member was stored: $needle")
            assertTrue(!s.session.closed)
            assertRows(w.a, w.a.rows().indexOfFirst { it.meshCode == "STATE" }, Rw(MeshKind.CONTROL, "STATE", 0, bytes.size.toLong()))
            doneServer(w, s)
        }
        serverCase("ack nodeId") { w, s ->
            s.peer.awaitFrames(1)
            s.peer.writeRaw(Build.ack(randomPin(3).nodeId))
            assertEquals("PROTOCOL_ERROR", s.peer.awaitFrames(1).single().code)
            Wait.until("the session to close") { s.session.closed }
            assertEquals(1, w.a.counters.of(Refusal.HELLO_NODE_MISMATCH))
            W08Tls.rowChecks.incrementAndGet()
            doneServer(w, s, tlsClosed = true)
        }
        serverCase("ack version") { w, s ->
            s.peer.awaitFrames(1)
            s.peer.writeRaw(Build.ack(s.hostileNodeId, v = 2))
            assertEquals("VERSION_UNSUPPORTED", s.peer.awaitFrames(1).single().code)
            assertEquals(1, w.a.counters.of(Refusal.HELLO_BAD_VERSION))
            doneServer(w, s, tlsClosed = true)
        }
        serverCase("ack clock skew") { w, s ->
            s.peer.awaitFrames(1)
            s.peer.writeRaw(Build.ack(s.hostileNodeId, ts = w.clockA.peek() + 10_800_000))
            assertEquals("CLOCK_SKEW", s.peer.awaitFrames(1).single().code)
            assertEquals(1, w.a.counters.of(Refusal.CLOCK_SKEW))
            doneServer(w, s, tlsClosed = true)
        }
        serverCase("frame before hello_ack") { w, s ->
            s.peer.awaitFrames(1)
            s.peer.writeRaw(Frames.encode(0x11, 1, """{"attemptId":"${attemptIdOf(1)}","fileSha256":"${"a".repeat(64)}","servedModel":"m1"}"""))
            assertEquals("PROTOCOL_ERROR", s.peer.awaitFrames(1).single().code)
            assertEquals(1, w.a.counters.of(Refusal.FRAME_BEFORE_HELLO))
            doneServer(w, s, tlsClosed = true)
        }
        val accept = Frames.encode(0x11, 1, """{"attemptId":"${attemptIdOf(1)}","fileSha256":"${"a".repeat(64)}","servedModel":"m1"}""")
        serverCase("accept on a stream nobody opened") { w, s ->
            s.establish()
            s.peer.writeRaw(accept)
            assertEquals("PROTOCOL_ERROR", s.peer.awaitFrames(1).single().code)
            assertEquals(1, w.a.counters.of(Refusal.UNSOLICITED_REPLY))
            doneServer(w, s, tlsClosed = true)
        }
        serverCase("unsolicited state") { w, s ->
            s.establish()
            s.peer.writeRaw(Frames.encode(0x21, 1, stateJson()))
            assertEquals("PROTOCOL_ERROR", s.peer.awaitFrames(1).single().code)
            assertEquals(1, w.a.counters.of(Refusal.UNSOLICITED_REPLY))
            doneServer(w, s, tlsClosed = true)
        }
        serverCase("unsolicited manifest") { w, s ->
            s.establish()
            s.peer.writeRaw(Frames.encode(0x23, 1, "{}"))
            assertEquals("PROTOCOL_ERROR", s.peer.awaitFrames(1).single().code)
            doneServer(w, s, tlsClosed = true)
        }
        for (n in 0..2) serverCase("head before accept, chunk before head, end with the wrong attemptId #$n", seed = 4000L + n) { w, s ->
            s.establish()
            val rec = Waiting()
            s.session.offer(spec(), "{}".toByteArray(), rec)
            s.peer.awaitFrames(1)
            s.peer.writeRaw(
                when (n) {
                    0 -> Frames.encode(0x14, 1, """{"attemptId":"${attemptIdOf(1)}","engine":"local","servedModel":"m1","status":200}""")
                    1 -> Frames.encode(0x15, 1, ByteArray(4))
                    else -> Frames.encode(0x16, 1, """{"attemptId":"${attemptIdOf(9)}","status":200,"terminal":"done"}""")
                },
            )
            assertEquals("PROTOCOL_ERROR", s.peer.awaitFrames(1).single().code)
            Wait.until("the session to close") { s.session.closed }
            Wait.until("the attempt outcome") { rec.outcome != null }
            assertEquals(599, rec.outcome?.status, "an attempt open when the session closes ends as a lost peer")
            assertEquals("PEER_UNREACHABLE", rec.outcome?.meshCode)
            W08Tls.rowChecks.addAndGet(3)
            doneServer(w, s, tlsClosed = true)
        }
        serverCase("goaway from the lender") { w, s ->
            s.establish()
            val g = Frames.encode(5, 0, """{"reason":"shutdown"}""")
            s.peer.writeRaw(g)
            Wait.until("the session to close") { s.session.closed }
            Wait.until("the close row") { w.a.rows().last().meshCode == "close" }
            assertRows(w.a, w.a.rows().size - 2, Rw(MeshKind.CONTROL, "GOAWAY:shutdown", 0, g.size.toLong()), Rw(MeshKind.SESSION, "close", 0, 0))
            doneServer(w, s, tlsClosed = true)
        }
        serverCase("a frame cut in half and then the end of the stream") { w, s ->
            s.establish()
            val half = Frames.encode(0x21, 1, stateJson()).copyOf(40)
            s.peer.writeRaw(half)
            s.peer.close()
            Wait.until("the session to close") { s.session.closed }
            assertEquals(1, w.a.counters.of(Refusal.TRUNCATED))
            Wait.until("the close row") { w.a.rows().last().meshCode == "close" }
            assertRows(w.a, w.a.rows().size - 1, Rw(MeshKind.SESSION, "close", 0, half.size.toLong()))
            doneServer(w, s)
        }
    }

    // ------------------------------------------------------------------------------------------------------------ manifests

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

    private fun repoRoot() = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot is not set"))

    fun manifests() {
        val doc = (StrictJson.parse(File(repoRoot(), "lab/conformance/manifest/M03-verify-reject.json").readBytes()) as ParseResult.Ok).value as JObject
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
            W08Tls.verdictsDirect += expect
            val frameable = bytes.size <= 1_048_576 && (StrictJson.parse(bytes) as? ParseResult.Ok)?.value is JObject
            val pinned = TestOnlyKeys.entries.firstOrNull { e -> ctx.pinnedSpki?.contentEquals(e.spki) == true }
            if (ctx.mode == Mode.MESH && frameable && pinned != null) {
                val honestKey = TestOnlyKeys.entries.first { it.name != pinned.name }.name
                serverCase("manifest $id", seed = id.hashCode().toLong() and 0xFFFFFF, keyA = honestKey, keyB = pinned.name) { w, s ->
                    s.establish()
                    w.a.manifest.ctxOverride = ctx
                    var result: ManifestResult? = null
                    s.session.requestManifest(ByteArray(32) { it.toByte() }) { result = it }
                    s.peer.awaitFrames(1)
                    val frame = Frames.encode(0x23, 1, bytes)
                    s.peer.writeRaw(frame)
                    Wait.until("the verdict") { result != null }
                    val r = result
                    assertTrue(r is ManifestResult.Rejected && r.code == expect, "$id through a session: ${(r as? ManifestResult.Rejected)?.code} (${r?.let { it::class.simpleName }}), expected $expect")
                    assertRows(w.a, w.a.rows().size.coerceAtLeast(1) - 1, Rw(MeshKind.MANIFEST_RECEIVED, expect.name, 0, frame.size.toLong()))
                    assertEquals(1, w.a.counters.of(Refusal.MANIFEST_REJECTED))
                    assertTrue(!s.session.closed, "a manifest that fails verification does not close the session")
                    W08Tls.verdictsThroughSession += expect
                    doneServer(w, s)
                }
                through++
            } else {
                direct++
            }
        }
        println("W08-frames-tls manifest vectors: ${vectors.size} (through a TLS session: $through, by the verifier directly: $direct)")
        assertTrue(through > 20 && vectors.size == 71)
        serverCase("manifest signer key is not the pin", keyA = "key3", keyB = "key2") { w, s ->
            s.establish()
            var result: ManifestResult? = null
            val key1 = TestOnlyKeys.key("key1")
            w.a.manifest.ctxOverride = VerifyContext(Mode.MESH, pinnedSpki = key1.spki, expectedChallenge = ByteArray(32), confFloor = "0.2.0", productionKeys = false, nowMs = MANIFEST_NOW)
            s.session.requestManifest(ByteArray(32)) { result = it }
            s.peer.awaitFrames(1)
            val container = (((doc["vectors"] as JArray).items[0] as JObject)["input"] as JObject)["document"] as JString
            s.peer.writeRaw(Frames.encode(0x23, 1, container.value.toByteArray(Charsets.UTF_8)))
            Wait.until("the verdict") { result != null }
            assertTrue(result is ManifestResult.Rejected && (result as ManifestResult.Rejected).code == RejectCode.KEY_NOT_PINNED, "the peer is key2; a context that pins key1 is not used: ${(result as? ManifestResult.Rejected)?.code}")
            W08Tls.rowChecks.incrementAndGet()
            doneServer(w, s)
        }
        clientCase("manifest unavailable") { w, c ->
            c.establish()
            w.b.manifest.available = false
            val mr = Build.manifestReq(1)
            c.peer.writeRaw(mr)
            val e = c.peer.awaitFrames(1).single()
            assertEquals("MANIFEST_UNAVAILABLE", e.code)
            assertEquals(1L, e.stream)
            assertRows(w.b, 3, Rw(MeshKind.CONTROL, "MANIFEST_REQ", 0, mr.size.toLong()), Rw(MeshKind.CONTROL, "ERROR:MANIFEST_UNAVAILABLE", e.app, 0))
            refused(w, Refusal.MANIFEST_UNAVAILABLE)
            assertTrue(!c.honest.closed)
            W08Tls.noteRefusalCodes(listOf(e))
            done(w, c)
        }
    }

    fun all() {
        hello()
        codec()
        attempts()
        registry()
        extensions()
        splitting()
        controlRowFailures()
        requester()
        manifests()
    }
}
