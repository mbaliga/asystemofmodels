package xyz.mdhv.asom.lab.sim

import java.util.PriorityQueue
import java.util.SplittableRandom
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue

/** One `SplittableRandom(seed)`, split in the fixed order of LAB_SPEC 6.9: links, workload, faults, truth noise; `ids` is split last (ERRATA), so it moves none of the four. */
class SimRandom(seed: Long) {
    private val root = SplittableRandom(seed)
    val links: SplittableRandom = root.split()
    val workload: SplittableRandom = root.split()
    val faults: SplittableRandom = root.split()
    val truth: SplittableRandom = root.split()
    val ids: SplittableRandom = root.split()

    companion object {
        fun uniform(r: SplittableRandom): Double = (r.nextLong(1, 1L shl 53)).toDouble() / (1L shl 53).toDouble()

        /** Box-Muller with `StrictMath`, so a seed gives the same numbers on every JVM. */
        fun gauss(r: SplittableRandom): Double = StrictMath.sqrt(-2.0 * StrictMath.log(uniform(r))) * StrictMath.cos(2.0 * StrictMath.PI * uniform(r))

        fun lognormal(median: Long, sigmaPermille: Long, r: SplittableRandom): Long =
            maxOf(1L, StrictMath.round(median.toDouble() * StrictMath.exp(sigmaPermille / 1000.0 * gauss(r))))

        fun exponentialMs(meanMs: Double, r: SplittableRandom): Long = maxOf(1L, StrictMath.round(-meanMs * StrictMath.log(uniform(r))))
    }
}

/**
 * A discrete-event loop on a virtual clock: a priority queue ordered by `(tMs, seq)`, no threads, no wall clock. Work scheduled by a callback inherits that callback's
 * [context] (the request it belongs to), so a failure inside a callback can be attributed to its request by [onError].
 */
class EventLoop {
    var now: Long = 0
        private set
    private var seq = 0L
    var context: Any? = null
    var onError: ((Any?, Throwable) -> Boolean)? = null

    private class Item(val t: Long, val seq: Long, val label: String, val ctx: Any?, val f: () -> Unit)

    private val q = PriorityQueue<Item>(compareBy<Item>({ it.t }, { it.seq }))

    fun at(t: Long, label: String, f: () -> Unit) {
        q.add(Item(maxOf(t, now), seq++, label, context, f))
    }

    fun after(dt: Long, label: String, f: () -> Unit) = at(now + dt, label, f)

    fun runAll(maxEvents: Int = 5_000_000) {
        var n = 0
        while (q.isNotEmpty()) {
            val i = q.poll()
            now = i.t
            context = i.ctx
            try {
                i.f()
            } catch (e: Throwable) {
                if (onError?.invoke(i.ctx, e) != true) throw e
            }
            check(++n < maxEvents) { "the event loop did not quiesce" }
        }
    }
}

/** An event of the requester's log: everything the [RequesterModel] learns arrives as one of these (`events.jsonl`). Values are integers, booleans, strings, lists or null. */
class SimEvent(val t: Long, val seq: Long, val kind: String, val f: Map<String, Any?>) {
    fun long(k: String): Long = (f[k] as Number).toLong()

    fun int(k: String): Int = (f[k] as Number).toInt()

    fun str(k: String): String = f[k] as String

    fun nstr(k: String): String? = f[k] as String?

    fun bool(k: String): Boolean = f[k] as Boolean

    @Suppress("UNCHECKED_CAST")
    fun strs(k: String): List<String> = (f[k] as List<Any?>).map { it as String }

    @Suppress("UNCHECKED_CAST")
    fun longs(k: String): List<Long> = (f[k] as List<Any?>).map { (it as Number).toLong() }

    fun nlong(k: String): Long? = (f[k] as Number?)?.toLong()
}

object EventCodec {
    private fun toJ(v: Any?): JValue = when (v) {
        null -> JNull
        is Boolean -> JBool(v)
        is Number -> JInt(v.toLong())
        is String -> JString(v)
        is List<*> -> JArray(v.map { toJ(it) })
        else -> throw IllegalArgumentException("not a log value: $v")
    }

    private fun fromJ(v: JValue): Any? = when (v) {
        JNull -> null
        is JBool -> v.value
        is JInt -> v.value
        is JString -> v.value
        is JArray -> v.items.map { fromJ(it) }
        is JObject -> throw IllegalArgumentException("nested objects are not log values")
    }

    fun toJson(e: SimEvent): JObject = JObject(
        listOf("t" to JInt(e.t), "seq" to JInt(e.seq), "kind" to JString(e.kind)) + e.f.entries.sortedBy { it.key }.map { it.key to toJ(it.value) },
    )

    fun fromJson(o: JObject): SimEvent {
        val f = o.members.filter { it.first !in setOf("t", "seq", "kind") }.associate { it.first to fromJ(it.second) }
        return SimEvent((o["t"] as JInt).value, (o["seq"] as JInt).value, (o["kind"] as JString).value, f)
    }
}
