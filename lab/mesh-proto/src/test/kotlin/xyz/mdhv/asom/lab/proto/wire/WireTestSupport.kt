package xyz.mdhv.asom.lab.proto.wire

import java.util.SplittableRandom
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.policy.BatteryBand
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.StateDoc

fun hex(s: String): ByteArray {
    val t = s.replace(" ", "")
    return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** What a decoder produced, comparable across chunkings. */
fun describe(events: List<Inbound>): List<String> = events.map {
    when (it) {
        is Inbound.Frame -> "F ${it.frame.type} ${it.frame.stream} ${it.frame.payload.toHex()}"
        is Inbound.ExtIgnored -> "X ${it.type} ${it.stream} ${it.appBytes}"
        is Inbound.Failure -> "E ${it.error} ${it.reason} ${it.consumed}"
    }
}

class Fed(val events: List<Inbound>, val decoder: FrameDecoder)

fun decodeChunks(bytes: ByteArray, receiver: PeerRole, mode: ConnMode, sizes: List<Int>?): Fed {
    val d = FrameDecoder(receiver, mode)
    val out = ArrayList<Inbound>()
    if (sizes == null) out += d.feed(bytes)
    else {
        var at = 0
        for (n in sizes) {
            out += d.feed(bytes, at, n)
            at += n
            check(d.totalFed == at.toLong())
            check(d.totalFed == d.appBytesDelivered + d.pendingBytes + d.discardedAfterClose) { "byte accounting after $at bytes" }
        }
        check(at == bytes.size)
    }
    d.finish()?.let { out += it }
    return Fed(out, d)
}

fun decodeWhole(bytes: ByteArray, receiver: PeerRole, mode: ConnMode = ConnMode.ESTABLISHED): Fed = decodeChunks(bytes, receiver, mode, null)

fun id(rnd: SplittableRandom, bytes: Int): String = Base64Strict.encodeUrlNoPad(ByteArray(bytes).also { b -> for (k in b.indices) b[k] = rnd.nextInt(256).toByte() })

fun <T> SplittableRandom.pick(list: List<T>): T = list[nextInt(list.size)]

private val modelIds = listOf("qwen3-8b-q4", "llama-3.1-8b-instruct", "m", "a.b_c:d-e", "x".repeat(128))
private val names = listOf("Madhav's iPhone", "Dell tower", "n", "Büro-PC", "🖥 desk", "x".repeat(32), "日本語のデバイス")
private val v4 = listOf("192.168.1.20", "10.0.0.1", "0.1.2.3", "255.255.255.255", "100.64.0.9")
private val v6 = listOf("fd7a:115c:a1e0::1", "::1", "::", "2001:db8::ff00:42:8329", "::ffff:10.0.0.1", "FE80::1", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7::")

fun randomEndpoints(rnd: SplittableRandom): List<Endpoint> = List(rnd.nextInt(5)) {
    Endpoint(if (rnd.nextBoolean()) rnd.pick(v4) else rnd.pick(v6), 1 + rnd.nextInt(65535), if (rnd.nextBoolean()) Via.LAN else Via.OVERLAY)
}

fun randomSt(rnd: SplittableRandom): St? =
    if (rnd.nextBoolean()) null else St(rnd.pick(Fsm.entries), rnd.pick(Governor.entries), rnd.nextInt(3), 1L + rnd.nextInt(1_000_000), rnd.nextInt(3))

fun randomCount(rnd: SplittableRandom): Long = when (rnd.nextInt(4)) {
    0 -> 0
    1 -> rnd.nextLong(1000)
    2 -> rnd.nextLong(1L shl 40)
    else -> 9_007_199_254_740_991L - rnd.nextLong(3)
}

fun randomState(rnd: SplittableRandom): StateDoc {
    val withManifest = rnd.nextBoolean()
    return StateDoc(
        seq = 1L + rnd.nextInt(1_000_000), sampledAgeMs = rnd.nextLong(60_001), fsm = rnd.pick(Fsm.entries), powerSource = rnd.pick(listOf("ac", "battery", "unknown")),
        charging = rnd.nextBoolean(), batteryBand = if (rnd.nextBoolean()) null else rnd.pick(BatteryBand.entries), thermalBand = rnd.nextInt(3), governor = rnd.pick(Governor.entries),
        backend = rnd.pick(listOf("cpu", "vulkan", "metal", "opencl", "cuda", "hexagon")), commit = rnd.pick(listOf("4f1c2ab", "0123456", "a".repeat(40))),
        confVersion = "${rnd.nextInt(5)}.${rnd.nextInt(30)}.${rnd.nextInt(30)}", held = List(rnd.nextInt(4)) { ByteArray(32) { rnd.nextInt(256).toByte() }.toHex() }.sorted(),
        queueBucket = rnd.nextInt(3), manifestSeq = if (withManifest) 1L + rnd.nextInt(100) else null, manifestDigest = if (withManifest) id(rnd, 32) else null,
    )
}

/** A random valid message of [type] (every non-raw type, and the raw ones with random bytes). */
fun randomMessage(rnd: SplittableRandom, type: Int): Message = when (type) {
    FrameTypes.HELLO -> {
        val lo = 1 + rnd.nextInt(3)
        val hi = lo + rnd.nextInt(3)
        Hello(
            randomEndpoints(rnd), Feature.entries.filter { rnd.nextBoolean() }.toSet(), rnd.pick(KeyTier.entries), lo, hi, rnd.pick(names), id(rnd, 32), rnd.pick(Platform.entries),
            id(rnd, 16), "asom-${rnd.pick(listOf("ios", "android", "desktop"))}/${rnd.nextInt(9)}.${rnd.nextInt(9)}.${rnd.nextInt(9)}", randomCount(rnd), lo + rnd.nextInt(hi - lo + 1),
        )
    }
    FrameTypes.HELLO_ACK -> HelloAck(
        randomEndpoints(rnd), Feature.entries.filter { rnd.nextBoolean() }.toSet(), Scope.entries.filter { rnd.nextBoolean() }.toSet(),
        Limits(randomCount(rnd), randomCount(rnd), randomCount(rnd), randomCount(rnd), randomCount(rnd)), id(rnd, 32), randomSt(rnd), randomCount(rnd), 1 + rnd.nextInt(255),
    )
    FrameTypes.GOAWAY -> GoAway(rnd.pick(GoAwayReason.entries))
    FrameTypes.ERROR -> PeerError(rnd.pick(MeshError.entries.filter { it != MeshError.UNKNOWN }), if (rnd.nextBoolean()) null else id(rnd, 16), if (rnd.nextBoolean()) null else randomCount(rnd))
    FrameTypes.INFER_OFFER -> InferOffer(id(rnd, 16), randomCount(rnd), randomCount(rnd), randomCount(rnd), rnd.pick(modelIds), rnd.pick(InferOp.entries), randomCount(rnd), rnd.nextBoolean())
    FrameTypes.INFER_ACCEPT -> InferAccept(id(rnd, 16), ByteArray(32) { rnd.nextInt(256).toByte() }.toHex(), rnd.pick(modelIds), randomSt(rnd))
    FrameTypes.INFER_DECLINE -> InferDecline(id(rnd, 16), rnd.pick(DeclineWire.entries), 5000L + rnd.nextInt(595_001), randomSt(rnd))
    FrameTypes.INFER_BODY -> InferBody(ByteArray(rnd.nextInt(300)) { rnd.nextInt(256).toByte() })
    FrameTypes.INFER_HEAD -> InferHead(id(rnd, 16), rnd.pick(modelIds), 100 + rnd.nextInt(500))
    FrameTypes.INFER_CHUNK -> InferChunk(ByteArray(rnd.nextInt(300)) { rnd.nextInt(256).toByte() })
    FrameTypes.INFER_END -> InferEnd(id(rnd, 16), 100 + rnd.nextInt(500), rnd.pick(Terminal.entries), randomSt(rnd))
    FrameTypes.CANCEL -> Cancel(id(rnd, 16), rnd.pick(CancelReason.entries))
    FrameTypes.STATE_REQ -> StateReq
    FrameTypes.STATE -> StateMsg(randomState(rnd))
    FrameTypes.MANIFEST_REQ -> ManifestReq(id(rnd, 32))
    FrameTypes.MANIFEST -> ManifestMsg("""{"payload":"${id(rnd, 12)}","payloadType":"application/vnd.asom.manifest+json","signatures":[]}""".toByteArray())
    FrameTypes.REVOKE_NOTICE -> RevokeNotice
    in FrameTypes.PAIR_HELLO..FrameTypes.PAIR_COMMIT_ACK -> PairMsg(type, """{"v":1,"n":${rnd.nextInt(1000)}}""".toByteArray())
    else -> error("no generator for type $type")
}

fun streamFor(rnd: SplittableRandom, rule: StreamRule): Long = when (rule) {
    StreamRule.ZERO -> 0
    StreamRule.ODD -> (rnd.nextLong(1L shl 31) shl 1) or 1L
    StreamRule.ANY -> if (rnd.nextBoolean()) 0 else rnd.nextLong(1L shl 32)
    StreamRule.PAIRING_ONE -> 1
}

/** Types a receiver in the given role (and mode) accepts. */
fun acceptedTypes(receiver: PeerRole, mode: ConnMode): List<Int> = FrameTypes.specs.values.filter { s ->
    val modeOk = if (mode == ConnMode.PAIRING) FrameTypes.isPair(s.type) || s.type == FrameTypes.ERROR else !FrameTypes.isPair(s.type)
    val dirOk = when (s.direction) {
        Direction.BOTH -> true
        Direction.CLIENT_TO_SERVER -> receiver == PeerRole.TLS_SERVER
        Direction.SERVER_TO_CLIENT -> receiver == PeerRole.TLS_CLIENT
    }
    modeOk && dirOk
}.map { it.type }.sorted()

class Sequence(val bytes: ByteArray, val expected: List<String>, val messages: List<Message?>)

/** A random valid byte sequence for [receiver]: typed frames and ignorable extensions, with the events a decoder must report. */
fun randomSequence(rnd: SplittableRandom, receiver: PeerRole, mode: ConnMode): Sequence {
    val types = acceptedTypes(receiver, mode)
    val out = java.io.ByteArrayOutputStream()
    val expected = ArrayList<String>()
    val messages = ArrayList<Message?>()
    repeat(1 + rnd.nextInt(6)) {
        if (rnd.nextInt(5) == 0) {
            val t = FrameTypes.EXTENSION_FIRST + rnd.nextInt(128)
            val payload = ByteArray(rnd.nextInt(40)) { rnd.nextInt(256).toByte() }
            val stream = rnd.nextLong(1L shl 32)
            val raw = ByteArray(9 + payload.size)
            val length = 5L + payload.size
            for (k in 0..3) raw[k] = (length ushr (24 - 8 * k)).toByte()
            raw[4] = t.toByte()
            for (k in 0..3) raw[5 + k] = (stream ushr (24 - 8 * k)).toByte()
            payload.copyInto(raw, 9)
            out.write(raw)
            expected += "X $t $stream ${9 + payload.size}"
            messages += null
        } else {
            val t = rnd.pick(types)
            val msg = randomMessage(rnd, t)
            val stream = streamFor(rnd, FrameTypes.specs.getValue(t).stream)
            val frame = MessageCodec.frame(msg, stream)
            out.write(FrameEncoder.encode(frame))
            expected += "F ${frame.type} ${frame.stream} ${frame.payload.toHex()}"
            messages += msg
        }
    }
    return Sequence(out.toByteArray(), expected, messages)
}
