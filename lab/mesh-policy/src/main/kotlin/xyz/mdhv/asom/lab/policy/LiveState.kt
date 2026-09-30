package xyz.mdhv.asom.lab.policy

import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

enum class BatteryBand(val wire: String) {
    GE80("ge80"),
    B50_79("50-79"),
    B20_49("20-49"),
    LT20("lt20"),
    ;

    /** One band lower, saturating at `lt20` (pessimistic substitution for STALE state on a battery node). */
    fun lower(): BatteryBand = entries.getOrElse(ordinal + 1) { LT20 }

    companion object {
        fun fromWire(w: String): BatteryBand? = entries.firstOrNull { it.wire == w }
    }
}

/** What the lender sees about ITSELF, presence included. Only some of it may reach the wire (LP-1); [StateBuilder] is the one place that decides which. */
data class PresenceSignals(
    val screenInteractive: Boolean,
    val inputIdleMs: Long,
    val keyguardDismissed: Boolean,
    val foregroundApp: String?,
    val heavyForegroundProcess: Boolean,
    val consoleUser: String?,
    val loginState: String?,
    val otherProcessContentionPermille: Int,
)

data class LenderLocalView(
    val fsm: Fsm,
    val powerSource: String,
    val charging: Boolean,
    val batteryBand: BatteryBand?,
    val batteryPercentExact: Int?,
    val thermalBand: Int,
    val governor: Governor,
    val backend: String,
    val commit: String,
    val confVersion: String,
    val held: List<String>,
    val localQueued: Int,
    val peerQueued: Int,
    val loadedModels: List<String>,
    val freeMemoryBytes: Long,
    val manifestSeq: Long?,
    val manifestDigest: String?,
    val presence: PresenceSignals,
)

/** `asom.state/1` as a receiver holds it: integers and enums only; unknown members are never stored. */
data class StateDoc(
    val seq: Long,
    val sampledAgeMs: Long,
    val fsm: Fsm,
    val powerSource: String,
    val charging: Boolean,
    val batteryBand: BatteryBand?,
    val thermalBand: Int,
    val governor: Governor,
    val backend: String,
    val commit: String,
    val confVersion: String,
    val held: List<String>,
    val queueBucket: Int,
    val manifestSeq: Long?,
    val manifestDigest: String?,
)

object StateSchema {
    val POWER_SOURCES = listOf("ac", "battery", "unknown")
    val BACKENDS = listOf("cpu", "vulkan", "metal", "opencl", "cuda", "hexagon")
    const val MAX_SAMPLED_AGE_MS = 60_000L

    /** Members `asom.state/1` may have, by object path. Anything else from an asom producer is a defect (W07 producer-strict). */
    val MEMBERS: Map<String, Set<String>> = mapOf(
        "" to setOf("availability", "engine", "manifest", "power", "queue", "sampledAgeMs", "seq", "thermal", "v"),
        "availability" to setOf("fsm"),
        "engine" to setOf("backend", "commit", "confVersion", "held"),
        "manifest" to setOf("bodyDigest", "seq"),
        "power" to setOf("batteryBand", "charging", "source"),
        "queue" to setOf("bucket"),
        "thermal" to setOf("band", "governor"),
    )

    /** Names that are presence or local-use signals: removed from the wire in r1 and r2 (LP-1). A producer that emits one is refused with its own code. */
    val PRESENCE_NAMES = setOf(
        "user", "active", "inflight", "busyForMs", "loaded", "estStartS", "estStartMs", "reason", "localActive", "depth", "queuePos", "ttftMs", "totalMs", "usage",
        "screen", "idle", "foreground", "console", "login", "keyguard", "batteryPermille", "availBytes", "memory", "headroomPermille", "forecastPermille",
    )
}

object StateBuilder {
    /** The queue bucket the wire shows: 0, 1 or 2 (= 2 or more). The lender's queue includes its own local requests, so it moves with local use (LP-1 exception iii). */
    fun queueBucket(localQueued: Int, peerQueued: Int): Int = minOf(2, localQueued + peerQueued)

    /**
     * The producer-strict `STATE` payload (LAB_SPEC 7.2). It reads the [LenderLocalView]'s condition fields, its FSM state and its queue, and NOTHING from
     * [LenderLocalView.presence], the exact battery percentage, the loaded models or the free memory (LP-1).
     */
    fun build(v: LenderLocalView, seq: Long, sampledAgeMs: Long): JObject {
        require(seq >= 1 && sampledAgeMs in 0..StateSchema.MAX_SAMPLED_AGE_MS)
        return JObject(
            listOf(
                "availability" to JObject(listOf("fsm" to JString(v.fsm.name))),
                "engine" to JObject(
                    listOf(
                        "backend" to JString(v.backend), "commit" to JString(v.commit), "confVersion" to JString(v.confVersion),
                        "held" to JArray(v.held.sorted().map { JString(it) }),
                    ),
                ),
                "manifest" to (if (v.manifestSeq == null || v.manifestDigest == null) JNull else JObject(listOf("bodyDigest" to JString(v.manifestDigest), "seq" to JInt(v.manifestSeq)))),
                "power" to JObject(
                    listOf(
                        "batteryBand" to (v.batteryBand?.let { JString(it.wire) } ?: JNull), "charging" to JBool(v.charging), "source" to JString(v.powerSource),
                    ),
                ),
                "queue" to JObject(listOf("bucket" to JInt(queueBucket(v.localQueued, v.peerQueued).toLong()))),
                "sampledAgeMs" to JInt(sampledAgeMs), "seq" to JInt(seq),
                "thermal" to JObject(listOf("band" to JInt(v.thermalBand.toLong()), "governor" to JString(v.governor.name))),
                "v" to JInt(1),
            ),
        )
    }

    fun jcs(v: JObject): String = Jcs.serializeToString(v)
}

/** The `st` digest that rides on `HELLO_ACK`, `INFER_ACCEPT`, `INFER_DECLINE` and `INFER_END`, only to a peer holding scope `state`: `{seq, fsm, tb, gov, qb}`. */
object StDigest {
    fun build(v: LenderLocalView, seq: Long): JObject = JObject(
        listOf(
            "fsm" to JString(v.fsm.name), "gov" to JString(v.governor.name), "qb" to JInt(StateBuilder.queueBucket(v.localQueued, v.peerQueued).toLong()),
            "seq" to JInt(seq), "tb" to JInt(v.thermalBand.toLong()),
        ),
    )
}

sealed interface StateParse {
    class Ok(val doc: StateDoc) : StateParse

    class Reject(val code: String) : StateParse
}

object StateParser {
    private val hex64 = Regex("^[0-9a-f]{64}$")
    private val commit = Regex("^[0-9a-f]{7,40}$")
    private val semver = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
    private val digest = Regex("^[A-Za-z0-9_-]{43}$")

    /**
     * The receiver's strict-but-tolerant parse of one `STATE` payload. Bytes go through `:json` (integers only; every JSON reject code passes through). A
     * required member that is missing or of the wrong type, an integer out of range, or a value outside a closed enum is refused (ERRATA ERR-LP-3: a closed enum is
     * refused, not mapped to "unknown", the conservative choice). Unknown members are ignored and never stored. `sampledAgeMs` above 60,000 is accepted and clamped by
     * the staleness formula, not here.
     */
    fun parse(bytes: ByteArray): StateParse {
        val root = when (val r = StrictJson.parse(bytes)) {
            is ParseResult.Reject -> return StateParse.Reject(r.code.name)
            is ParseResult.Ok -> r.value as? JObject ?: return StateParse.Reject("WRONG_TYPE")
        }
        return try {
            StateParse.Ok(read(root))
        } catch (e: Bad) {
            StateParse.Reject(e.code)
        }
    }

    private class Bad(val code: String) : Exception(code, null, false, false)

    private fun obj(o: JObject, k: String): JObject = when (val v = o[k]) {
        null -> throw Bad("MISSING_MEMBER")
        is JObject -> v
        else -> throw Bad("WRONG_TYPE")
    }

    private fun int(o: JObject, k: String): Long = when (val v = o[k]) {
        null -> throw Bad("MISSING_MEMBER")
        is JInt -> v.value
        else -> throw Bad("WRONG_TYPE")
    }

    private fun str(o: JObject, k: String): String = when (val v = o[k]) {
        null -> throw Bad("MISSING_MEMBER")
        is JString -> v.value
        else -> throw Bad("WRONG_TYPE")
    }

    private fun bool(o: JObject, k: String): Boolean = when (val v = o[k]) {
        null -> throw Bad("MISSING_MEMBER")
        is JBool -> v.value
        else -> throw Bad("WRONG_TYPE")
    }

    private fun read(root: JObject): StateDoc {
        if (int(root, "v") != 1L) throw Bad("BAD_VERSION")
        val seq = int(root, "seq")
        if (seq < 1) throw Bad("OUT_OF_RANGE")
        val age = int(root, "sampledAgeMs")
        if (age < 0) throw Bad("OUT_OF_RANGE")
        val fsm = Fsm.entries.firstOrNull { it.name == str(obj(root, "availability"), "fsm") } ?: throw Bad("UNKNOWN_ENUM")
        val power = obj(root, "power")
        val source = str(power, "source")
        if (source !in StateSchema.POWER_SOURCES) throw Bad("UNKNOWN_ENUM")
        val charging = bool(power, "charging")
        val band = when (val b = power["batteryBand"]) {
            null -> throw Bad("MISSING_MEMBER")
            JNull -> null
            is JString -> BatteryBand.fromWire(b.value) ?: throw Bad("UNKNOWN_ENUM")
            else -> throw Bad("WRONG_TYPE")
        }
        val thermal = obj(root, "thermal")
        val tb = int(thermal, "band")
        if (tb !in 0..2) throw Bad("OUT_OF_RANGE")
        val gov = Governor.entries.firstOrNull { it.name == str(thermal, "governor") } ?: throw Bad("UNKNOWN_ENUM")
        val engine = obj(root, "engine")
        val backend = str(engine, "backend")
        if (backend !in StateSchema.BACKENDS) throw Bad("UNKNOWN_ENUM")
        val commitText = str(engine, "commit")
        if (!commit.matches(commitText)) throw Bad("OUT_OF_RANGE")
        val conf = str(engine, "confVersion")
        if (!semver.matches(conf)) throw Bad("OUT_OF_RANGE")
        val heldArr = engine["held"] as? JArray ?: throw Bad(if (engine["held"] == null) "MISSING_MEMBER" else "WRONG_TYPE")
        if (heldArr.items.size > 64) throw Bad("OUT_OF_RANGE")
        val held = heldArr.items.map { (it as? JString)?.value ?: throw Bad("WRONG_TYPE") }
        if (held.any { !hex64.matches(it) }) throw Bad("OUT_OF_RANGE")
        val qb = int(obj(root, "queue"), "bucket")
        if (qb !in 0..2) throw Bad("OUT_OF_RANGE")
        var mSeq: Long? = null
        var mDigest: String? = null
        when (val m = root["manifest"]) {
            null -> throw Bad("MISSING_MEMBER")
            JNull -> Unit
            is JObject -> {
                mSeq = int(m, "seq")
                if (mSeq < 1) throw Bad("OUT_OF_RANGE")
                mDigest = str(m, "bodyDigest")
                if (!digest.matches(mDigest)) throw Bad("OUT_OF_RANGE")
            }
            else -> throw Bad("WRONG_TYPE")
        }
        return StateDoc(seq, age, fsm, source, charging, band, tb.toInt(), gov, backend, commitText, conf, held, qb.toInt(), mSeq, mDigest)
    }

    /** Re-serialises a parsed document through the producer's own field list: the normal form that vectors pin. Unknown members are gone. */
    fun normalForm(d: StateDoc): JObject = StateBuilder.build(
        LenderLocalView(
            fsm = d.fsm, powerSource = d.powerSource, charging = d.charging, batteryBand = d.batteryBand, batteryPercentExact = null, thermalBand = d.thermalBand,
            governor = d.governor, backend = d.backend, commit = d.commit, confVersion = d.confVersion, held = d.held, localQueued = d.queueBucket, peerQueued = 0,
            loadedModels = emptyList(), freeMemoryBytes = 0, manifestSeq = d.manifestSeq, manifestDigest = d.manifestDigest,
            presence = PresenceSignals(false, 0, false, null, false, null, null, 0),
        ),
        d.seq, minOf(d.sampledAgeMs, StateSchema.MAX_SAMPLED_AGE_MS),
    )
}

/** W07's producer-strict check: what the lab's OWN `STATE` builder is allowed to emit. A receiver ignores unknown members; a producer must not send them. */
object ProducerStrict {
    /** Null when the document conforms; otherwise `PRESENCE_FIELD` for a presence or local-use member, `UNKNOWN_MEMBER` for any other addition, or the parse code. */
    fun check(bytes: ByteArray): String? {
        val root = when (val r = StrictJson.parse(bytes)) {
            is ParseResult.Reject -> return r.code.name
            is ParseResult.Ok -> r.value as? JObject ?: return "WRONG_TYPE"
        }
        var unknown = false
        fun walk(o: JObject, path: String): String? {
            val allowed = StateSchema.MEMBERS[path] ?: return null
            for ((name, value) in o.members) {
                if (name !in allowed) {
                    if (name in StateSchema.PRESENCE_NAMES) return "PRESENCE_FIELD"
                    unknown = true
                } else if (value is JObject) {
                    walk(value, name)?.let { return it }
                }
            }
            return null
        }
        walk(root, "")?.let { return it }
        return if (unknown) "UNKNOWN_MEMBER" else null
    }

    /** Every member name that appears anywhere in a serialised output, for allow-list checks. */
    fun memberNames(v: JValue): Set<String> = when (v) {
        is JObject -> v.members.flatMap { setOf(it.first) + memberNames(it.second) }.toSet()
        is JArray -> v.items.flatMap { memberNames(it) }.toSet()
        else -> emptySet()
    }
}
