package xyz.mdhv.asom.desktop.mac.helper

import java.io.IOException

/** The helper is gone, hung or could not be started: every probe is lost (`PROBE_LOST`, macos.md 3.5, B14). */
class HelperLostException(message: String, cause: Throwable? = null) : IOException("PROBE_LOST: $message", cause)

/** The helper answered `ok:false`. [code] is one of the closed error codes of SCHEMA section 5. */
open class HelperFailure(val code: String, message: String) : RuntimeException("$code: $message")

/** The capability does not exist on this Mac (no Secure Enclave, no GPU counter, unreadable power source). */
class HelperUnavailable(message: String) : HelperFailure("UNAVAILABLE", message)

/** The helper broke the protocol (a reply that does not decode, an unexpected line). The session is dropped. */
class HelperProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface HelperTransport {
    /** Sends [request] (the transport assigns the id) and returns the decoded reply. Throws [HelperLostException] when the helper is gone. */
    fun call(request: Request): Reply

    /** Subscribes to unsolicited events. Listeners run on a transport-owned thread; the handle unsubscribes. */
    fun subscribe(listener: (Event) -> Unit): AutoCloseable

    /** True while the transport believes the helper is alive. */
    val alive: Boolean

    /**
     * Which helper process answers: it changes every time a new one is started. State the helper keeps (the power assertion)
     * dies with its process, so a caller that placed such state compares this before relying on it (mac ERRATA ERR-FX-HWM-9).
     */
    val epoch: Long get() = 0L
}

/** Typed calls over a [HelperTransport]. Every reply is validated by the codec before it reaches this class. */
class HelperClient(private val transport: HelperTransport) {
    data class Hello(val helper: String, val macos: String, val arch: String, val se: Boolean, val model: String)
    class SeKey(val blob: ByteArray, val spki: ByteArray)
    data class PowerInfo(val source: String, val charging: Boolean, val batteryPermille: Int?, val lowPower: Boolean)
    data class PresenceInfo(val hidIdleMs: Long?, val screenLocked: Boolean?, val consoleUserIsSelf: Boolean?)
    data class MemInfo(val physicalBytes: Long, val gpuRecommendedMaxWorkingSetBytes: Long?)

    val alive: Boolean get() = transport.alive

    /** See [HelperTransport.epoch]. */
    val epoch: Long get() = transport.epoch

    fun subscribe(listener: (Event) -> Unit): AutoCloseable = transport.subscribe(listener)

    private fun ok(op: String, fields: Fields = Fields()): Fields = when (val r = transport.call(Request(op, null, fields))) {
        is Reply.Success -> r.fields
        is Reply.Failure -> throw if (r.code == "UNAVAILABLE") HelperUnavailable(r.message) else HelperFailure(r.code, r.message)
    }

    fun hello(): Hello {
        val f = ok("hello", Fields.of("v" to HValue.I(ProtocolSpec.VERSION)))
        return Hello(f.text("helper")!!, f.text("macos")!!, f.text("arch")!!, f.bool("se")!!, f.text("model")!!)
    }

    fun seCreate(): SeKey = ok("se.create").let { SeKey(it.bytes("blob")!!, it.bytes("spki")!!) }

    fun seSign(blob: ByteArray, data: ByteArray): ByteArray =
        ok("se.sign", Fields.of("blob" to HValue.Bytes(blob), "data" to HValue.Bytes(data))).bytes("sig")!!

    fun seSelftest(blob: ByteArray): Boolean = ok("se.selftest", Fields.of("blob" to HValue.Bytes(blob))).bool("verified")!!

    fun power(): PowerInfo = ok("power.get").let {
        PowerInfo(it.text("source")!!, it.bool("charging")!!, it.int("batteryPermille")?.toInt(), it.bool("lowPower")!!)
    }

    fun thermal(): String = ok("thermal.get").text("state")!!

    fun presence(): PresenceInfo = ok("presence.get").let { PresenceInfo(it.int("hidIdleMs"), it.bool("screenLocked"), it.bool("consoleUserIsSelf")) }

    fun gpuUtilPermille(): Int = ok("gpu.get").int("deviceUtilPermille")!!.toInt()

    fun mem(): MemInfo = ok("mem.get").let { MemInfo(it.int("physicalBytes")!!, it.int("gpuRecommendedMaxWorkingSetBytes")) }

    fun assertHold() {
        ok("assert.hold", Fields.of("reason" to HValue.T(ProtocolSpec.HOLD_REASON)))
    }

    fun assertRelease() {
        ok("assert.release")
    }

    fun sleepAck(token: Long) {
        ok("sleep.ack", Fields.of("token" to HValue.I(token)))
    }

    /** [action] is `status`, `register` or `unregister`; [kind] is `agent` or `daemon`. Returns the SMAppService status word. */
    fun svc(action: String, kind: String): String {
        require(action in listOf("status", "register", "unregister")) { "unknown service action" }
        return ok("svc.$action", Fields.of("kind" to HValue.T(kind))).text("status")!!
    }

    fun backupExclude(path: String) {
        ok("backup.exclude", Fields.of("path" to HValue.T(path)))
    }

    fun platformDigest(): ByteArray = ok("platform.uuid").bytes("digest")!!

    fun userTempDir(): String = ok("paths.get").text("userTempDir")!!
}
