package xyz.mdhv.asom.lab.ledger

import java.nio.file.Path
import java.security.MessageDigest
import xyz.mdhv.asom.lab.ledger.sim.SimConfig
import xyz.mdhv.asom.lab.ledger.sim.SimWorld

/**
 * The child process of the durability harness. It appends real rows through the real [JsonlSink] (write, then `force(false)`), reports each row that
 * became durable on stdout, and blocks at the requested durability point until the parent kills it with SIGKILL (`Process.destroyForcibly()` on POSIX).
 *
 * Modes: `point <index> before|after` pauses just before or just after the first row matching FullScript.POINTS[index]; `stress` appends large
 * rows in a loop until it is killed at an arbitrary moment, possibly in the middle of a write.
 */
object DurabilityChild {
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class PausingSink(
        private val node: String,
        private val inner: JsonlSink,
        private val matches: ((LabRouteRecord) -> Boolean)?,
        private val before: Boolean,
        private val state: BooleanArray,
    ) : RowSink {
        override fun append(row: LabRouteRecord) {
            val hit = matches != null && !state[0] && matches.invoke(row)
            if (hit && before) {
                state[0] = true
                println("ABOUT $node")
                System.out.flush()
                Thread.sleep(Long.MAX_VALUE)
            }
            inner.append(row)
            println("DURABLE $node ${digest(row.toRowBytes())}")
            System.out.flush()
            if (hit && !before) {
                state[0] = true
                println("AT $node")
                System.out.flush()
                Thread.sleep(Long.MAX_VALUE)
            }
        }
    }

    /** Writes half of the third row and blocks inside `write`, the state SIGKILL leaves in the middle of a write. */
    private class TearingChannel(inner: java.nio.channels.FileChannel) : SpyChannel(inner) {
        private var calls = 0

        override fun write(src: java.nio.ByteBuffer): Int {
            if (++calls == 3) {
                val cut = src.duplicate().also { it.limit(src.position() + src.remaining() / 2) }
                inner.write(cut)
                println("TORN")
                System.out.flush()
                Thread.sleep(Long.MAX_VALUE)
            }
            return inner.write(src)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Path.of(args[0])
        if (args[1] == "tear") {
            JsonlSink(dir.resolve("T.jsonl"), opener = { p -> TearingChannel(JsonlSink.openChannel(p)) }).use { sink ->
                for (i in 1L..10L) {
                    val row = LabRouteRecord(
                        ts = i, callerPkg = "peer:tear", requestedModel = "", egress = LabEgress.peerClass, meshKind = MeshKind.CONTROL, meshCode = "HELLO", sessionId = "tear", bytesOut = i,
                    )
                    sink.append(row)
                    println("DURABLE T ${digest(row.toRowBytes())}")
                    System.out.flush()
                }
            }
        }
        if (args[1] == "stress") {
            JsonlSink(dir.resolve("S.jsonl")).use { sink ->
                var i = 0L
                while (true) {
                    val row = LabRouteRecord(
                        ts = i, callerPkg = "peer:" + "x".repeat(20_000), requestedModel = "", egress = LabEgress.peerClass, meshKind = MeshKind.CONTROL,
                        meshCode = "HELLO", sessionId = "stress", bytesOut = i,
                    )
                    sink.append(row)
                    println("DURABLE S ${digest(row.toRowBytes())}")
                    System.out.flush()
                    i++
                }
            }
        }
        val index = args[2].toInt()
        val before = args[3] == "before"
        val matches = FullScript.POINTS[index].second
        val state = BooleanArray(1)
        val sinks = HashMap<String, JsonlSink>()
        val cfg = SimConfig(seed = 1, sinkFor = { name -> PausingSink(name, JsonlSink(dir.resolve("$name.jsonl")).also { sinks[name] = it }, matches, before, state) })
        val w = SimWorld(cfg)
        w.run(FullScript.steps(w))
        println("FINISHED without reaching the point")
        System.out.flush()
    }
}
