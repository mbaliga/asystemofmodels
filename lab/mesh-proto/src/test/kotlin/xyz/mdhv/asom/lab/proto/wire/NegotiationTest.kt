package xyz.mdhv.asom.lab.proto.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NegotiationTest {
    private val nodeC = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val nodeS = "BAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val nonce = "AAAAAAAAAAAAAAAAAAAAAA"

    private fun hello(minV: Int, maxV: Int, v: Int = maxV, features: Set<Feature> = Feature.entries.toSet(), id: String = nodeC) =
        Hello(emptyList(), features, KeyTier.FILE, minV, maxV, "n", id, Platform.LINUX, nonce, "asom-desktop/1.0.0", 1, v)

    private fun ack(v: Int, id: String = nodeS) = HelloAck(
        emptyList(), setOf(Feature.STATE, Feature.MANIFEST), setOf(Scope.INFER, Scope.STATE), Limits(300000, 8388608, 1, 4096, 30), id, null, 1, v,
    )

    @Test
    fun theServerPicksTheHighestCommonVersion() {
        val d = Handshake.onHello(VersionRange(1, 3), setOf(Feature.STATE), hello(2, 5), nodeC) as HelloDecision.Established
        assertEquals(3, d.v)
        assertEquals(setOf(Feature.STATE), d.features)
    }

    @Test
    fun noCommonVersionIsVersionUnsupported() {
        val d = Handshake.onHello(VersionRange(1, 1), emptySet(), hello(2, 2), nodeC) as HelloDecision.Refuse
        assertEquals(MeshError.VERSION_UNSUPPORTED, d.error.code)
        val frame = MessageCodec.frame(d.error, 0)
        assertEquals(FrameTypes.ERROR, frame.type)
        assertEquals("""{"code":"VERSION_UNSUPPORTED"}""", String(frame.payload))
    }

    @Test
    fun aHelloForAnotherIdentityIsAProtocolErrorBeforeVersionsAreDiscussed() {
        val d = Handshake.onHello(VersionRange(1, 1), emptySet(), hello(2, 2), nodeS) as HelloDecision.Refuse
        assertEquals(MeshError.PROTOCOL_ERROR, d.error.code)
    }

    @Test
    fun theAckCarriesTheNegotiatedVersionAndTheFeatureIntersection() {
        val est = Handshake.onHello(VersionRange(1, 1), setOf(Feature.STATE, Feature.INFER_OFFER), hello(1, 1, features = setOf(Feature.STATE, Feature.MANIFEST)), nodeC) as HelloDecision.Established
        val a = Handshake.ack(est, nodeS, emptyList(), setOf(Scope.STATE), Limits(1, 2, 3, 4, 5), null, 7)
        assertEquals(1, a.v)
        assertEquals(setOf(Feature.STATE), a.features)
        assertEquals(nodeS, a.nodeId)
        val parsed = (MessageCodec.parse(MessageCodec.frame(a, 0)) as Parsed.Ok).message
        assertEquals(a, parsed)
    }

    @Test
    fun theClientChecksTheAckAgainstTheTlsIdentityAndItsOwnRange() {
        val ok = Handshake.onAck(VersionRange(1, 1), setOf(Feature.STATE), ack(1), nodeS) as AckDecision.Established
        assertEquals(setOf(Scope.INFER, Scope.STATE), ok.granted)
        assertEquals(setOf(Feature.STATE), ok.features)
        assertEquals(AckDecision.Refuse::class, Handshake.onAck(VersionRange(1, 1), emptySet(), ack(1), nodeC)::class)
        val low = Handshake.onAck(VersionRange(2, 4), emptySet(), ack(1), nodeS) as AckDecision.Refuse
        assertEquals(MeshError.VERSION_UNSUPPORTED, low.error.code)
        val ident = Handshake.onAck(VersionRange(1, 1), emptySet(), ack(1), nodeC) as AckDecision.Refuse
        assertEquals(MeshError.PROTOCOL_ERROR, ident.error.code)
    }

    @Test
    fun aVersionRangeMustBeWellFormed() {
        assertTrue(runCatching { VersionRange(0, 1) }.isFailure)
        assertTrue(runCatching { VersionRange(2, 1) }.isFailure)
        assertTrue(runCatching { VersionRange(1, 256) }.isFailure)
    }
}
