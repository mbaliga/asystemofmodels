package xyz.mdhv.asom.desktop.mac.fakes

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import xyz.mdhv.asom.desktop.mac.Vectors
import xyz.mdhv.asom.desktop.mac.helper.Event
import xyz.mdhv.asom.desktop.mac.helper.Fields
import xyz.mdhv.asom.desktop.mac.helper.HValue
import xyz.mdhv.asom.desktop.mac.helper.HelperCodec
import xyz.mdhv.asom.desktop.mac.helper.HelperLostException
import xyz.mdhv.asom.desktop.mac.helper.HelperTransport
import xyz.mdhv.asom.desktop.mac.helper.Reject
import xyz.mdhv.asom.desktop.mac.helper.Reply
import xyz.mdhv.asom.desktop.mac.helper.Request

/** The fixture machine of helper-protocol/SCHEMA.md section 8, as code: the same answers as the Swift `FixtureBackend`. */
object FixtureMachine {
    val blob = "ASOM-FIXTURE-SE-BLOB-V1".toByteArray(Charsets.US_ASCII)
    val spki = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04,
    ) + ByteArray(64) { (it + 1).toByte() }
    val signature = ByteArray(32) { 0x11 } + ByteArray(32) { 0x22 }
    val platformDigest: ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest("fixture-platform-uuid".toByteArray(Charsets.US_ASCII))

    fun failure(code: String, message: String, id: Long?) = Reply.Failure(id, code, message)

    fun answer(r: Request): Reply {
        fun ok(vararg f: Pair<String, HValue>) = Reply.Success(r.op, r.id, Fields.of(*f))
        return when (r.op) {
            "hello" -> if (r.fields.int("v") != 1L) failure("UNSUPPORTED_VERSION", "protocol version 1 only", r.id)
            else ok(
                "helper" to HValue.T("0.1.0"), "macos" to HValue.T("27.0.1"), "arch" to HValue.T("arm64"),
                "se" to HValue.B(true), "model" to HValue.T("Mac14,3"),
            )
            "se.create" -> ok("blob" to HValue.Bytes(blob), "spki" to HValue.Bytes(spki))
            "se.sign" -> if (r.fields.bytes("blob")!!.contentEquals(blob)) ok("sig" to HValue.Bytes(signature)) else failure("FAILED", "key blob not usable", r.id)
            "se.selftest" -> if (r.fields.bytes("blob")!!.contentEquals(blob)) ok("verified" to HValue.B(true)) else failure("FAILED", "key blob not usable", r.id)
            "power.get" -> ok("source" to HValue.T("ac"), "charging" to HValue.B(true), "batteryPermille" to HValue.I(870), "lowPower" to HValue.B(false))
            "thermal.get" -> ok("state" to HValue.T("nominal"))
            "presence.get" -> ok("hidIdleMs" to HValue.I(725_000), "screenLocked" to HValue.B(false), "consoleUserIsSelf" to HValue.B(true))
            "gpu.get" -> ok("deviceUtilPermille" to HValue.I(137))
            "mem.get" -> ok("physicalBytes" to HValue.I(17_179_869_184), "gpuRecommendedMaxWorkingSetBytes" to HValue.I(11_453_251_584))
            "assert.hold", "assert.release", "backup.exclude" ->
                if (r.op == "backup.exclude" && !r.fields.text("path")!!.startsWith("/tmp/")) failure("FAILED", "cannot exclude", r.id) else ok()
            "sleep.ack" -> if (r.fields.int("token") == 7L) ok() else failure("BAD_REQUEST", "unknown token", r.id)
            "svc.status" -> ok("status" to HValue.T(if (r.fields.text("kind") == "agent") "notRegistered" else "notFound"))
            "svc.register" -> ok("status" to HValue.T(if (r.fields.text("kind") == "agent") "requiresApproval" else "notFound"))
            "svc.unregister" -> ok("status" to HValue.T("notRegistered"))
            "platform.uuid" -> ok("digest" to HValue.Bytes(platformDigest))
            "paths.get" -> ok("userTempDir" to HValue.T("/var/folders/zz/fixture/T/"))
            else -> failure("UNKNOWN_OP", "UNKNOWN_OP", r.id)
        }
    }
}

/** A Secure Enclave made of JCA keys: real ES256 maths behind the helper's `se.*` ops, so a signature really verifies. */
class FakeEnclave(
    var available: Boolean = true,
    /** A wrong-sized or wrong-valued signature, to make the self-test fail. */
    var badSignatures: Boolean = false,
    var failCreate: Boolean = false,
) {
    val creates = AtomicInteger()

    fun create(): Pair<ByteArray, ByteArray> {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        creates.incrementAndGet()
        return kp.private.encoded to kp.public.encoded
    }

    fun sign(blob: ByteArray, data: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(blob))
        val s = Signature.getInstance("SHA256withECDSAinP1363Format")
        s.initSign(key)
        s.update(data)
        val sig = s.sign()
        return if (badSignatures) sig.also { it[0] = (it[0].toInt() xor 0x55).toByte() } else sig
    }

    fun answer(r: Request): Reply? {
        fun ok(vararg f: Pair<String, HValue>) = Reply.Success(r.op, r.id, Fields.of(*f))
        return when (r.op) {
            "hello" -> if (r.fields.int("v") != 1L) FixtureMachine.failure("UNSUPPORTED_VERSION", "protocol version 1 only", r.id) else ok(
                "helper" to HValue.T("0.1.0"), "macos" to HValue.T("27.0.1"), "arch" to HValue.T("arm64"),
                "se" to HValue.B(available), "model" to HValue.T("Mac14,3"),
            )
            "se.create" -> when {
                !available -> FixtureMachine.failure("UNAVAILABLE", "no Secure Enclave", r.id)
                failCreate -> FixtureMachine.failure("FAILED", "Secure Enclave key creation failed", r.id)
                else -> create().let { (b, p) -> ok("blob" to HValue.Bytes(b), "spki" to HValue.Bytes(p)) }
            }
            "se.sign" -> if (!available) FixtureMachine.failure("UNAVAILABLE", "no Secure Enclave", r.id) else try {
                ok("sig" to HValue.Bytes(sign(r.fields.bytes("blob")!!, r.fields.bytes("data")!!)))
            } catch (_: Exception) {
                FixtureMachine.failure("FAILED", "key blob not usable", r.id)
            }
            "se.selftest" -> if (!available) FixtureMachine.failure("UNAVAILABLE", "no Secure Enclave", r.id) else try {
                sign(r.fields.bytes("blob")!!, "asom-mac-helper selftest v1".toByteArray())
                ok("verified" to HValue.B(!badSignatures))
            } catch (_: Exception) {
                FixtureMachine.failure("FAILED", "key blob not usable", r.id)
            }
            else -> null
        }
    }
}

/**
 * An in-process helper. Requests are answered by [handler] (default: the fixture machine, then the fake enclave for `se.*`); a
 * test can make the helper "lost", push events, and count calls. Every request and reply goes through the codec, so what the
 * client sees is exactly what a real helper's bytes would decode to.
 */
class FakeHelper(
    val enclave: FakeEnclave = FakeEnclave(),
    var handler: (Request) -> Reply? = { null },
) : HelperTransport {
    val calls = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()

    @Volatile
    var lost: String? = null
    override val alive: Boolean get() = lost == null

    /** Ops that answer with this failure instead of the fixture. */
    val failures = HashMap<String, Reply.Failure>()

    override fun call(request: Request): Reply {
        lost?.let { throw HelperLostException(it) }
        // the request travels as bytes, like on the pipe
        val decoded = try {
            HelperCodec.decodeRequest(HelperCodec.encode(Request(request.op, 1, request.fields)))
        } catch (e: Reject) {
            throw AssertionError("the client sent a request the codec refuses: ${e.code.wire} for ${request.op}", e)
        }
        calls += request.op
        failures[request.op]?.let { return it }
        val reply = handler(decoded) ?: enclave.answer(decoded) ?: FixtureMachine.answer(decoded)
        return HelperCodec.decodeReply(request.op, HelperCodec.encode(withoutId(reply)))
    }

    private fun withoutId(r: Reply): Reply = when (r) {
        is Reply.Success -> Reply.Success(r.op, null, r.fields)
        is Reply.Failure -> Reply.Failure(null, r.code, r.message)
    }

    override fun subscribe(listener: (Event) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Delivers an event to the subscribers on the calling thread. */
    fun emit(e: Event) {
        val decoded = HelperCodec.decodeEvent(HelperCodec.encode(e))
        for (l in listeners) l(decoded)
    }

    fun opCount(op: String) = calls.count { it == op }
}

/**
 * Replays the recorded exchanges of helper-protocol/vectors/exchanges.jsonl: a request whose canonical line (without an id) is in the
 * file gets the recorded response. A request that is not in the file is a test error, not a guess.
 */
class ReplayHelper : HelperTransport {
    private val recorded: Map<String, ByteArray> = Vectors.load("exchanges.jsonl")
        .filter { it.requestVerdict == "accept" }
        .associate { String(it.request!!, Charsets.UTF_8) to it.response!! }

    val replayed = AtomicInteger()
    override val alive: Boolean = true

    val recordedRequests: Set<String> get() = recorded.keys

    override fun call(request: Request): Reply {
        val line = String(HelperCodec.encode(Request(request.op, null, request.fields)), Charsets.UTF_8)
        val response = recorded[line] ?: throw AssertionError("no recorded exchange for $line")
        replayed.incrementAndGet()
        return HelperCodec.decodeReply(request.op, response)
    }

    override fun subscribe(listener: (Event) -> Unit): AutoCloseable = AutoCloseable {}
}
