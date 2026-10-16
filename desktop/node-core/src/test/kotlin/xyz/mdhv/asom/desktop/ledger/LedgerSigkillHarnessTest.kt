package xyz.mdhv.asom.desktop.ledger

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.desktop.Report

/**
 * A forked JVM appends through the real sink and is killed with SIGKILL (`Process.destroyForcibly()`) at each
 * durability point. Every row the child acknowledged before the kill must be intact and parseable afterwards.
 *
 * Claim: PROCESS DEATH only, never power loss. Because the page cache survives SIGKILL, a missing fsync cannot show up
 * in `real` mode; `volatile` mode runs the same sink over a channel whose unforced bytes die with the process (a model of
 * the durability contract), and that is where an acknowledge-before-force defect becomes visible.
 */
class LedgerSigkillHarnessTest {
    private class Outcome(val acked: Int, val onDiskRows: Int, val tornBytes: Int, val exit: Int)

    private fun runChild(dir: Path, mode: String, point: String, row: Int, total: Int): Outcome {
        val file = dir.resolve("ledger-$mode-$point-$row.jsonl")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val cp = System.getProperty("asom.testClasspath") ?: error("asom.testClasspath is not set (run through Gradle)")
        val pb = ProcessBuilder(java, "-XX:TieredStopAtLevel=1", "-cp", cp, "xyz.mdhv.asom.desktop.ledger.SinkChildMain", file.toString(), mode, point, row.toString(), total.toString())
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        val p = pb.start()
        val lines = LinkedBlockingQueue<String>()
        val reader = Thread {
            p.inputStream.bufferedReader().forEachLine { lines.put(it) }
            lines.put("EOF")
        }.also { it.isDaemon = true; it.start() }
        var acked = 0
        var at: String? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (at == null) {
            val l = lines.poll(1, TimeUnit.SECONDS)
            check(System.nanoTime() < deadline) { "child did not reach $point/$row" }
            when {
                l == null -> continue
                l.startsWith("ACK ") -> acked = l.removePrefix("ACK ").toInt()
                l.startsWith("AT ") -> at = l
                else -> error("child ended without reaching $point/$row: $l")
            }
        }
        assertEquals("AT $point $row", at)
        p.destroyForcibly()
        val exit = if (p.waitFor(30, TimeUnit.SECONDS)) p.exitValue() else error("child survived SIGKILL")
        reader.join(5_000)
        // Rows the child acknowledged by printing after our AT line cannot exist: it parked at the kill point.
        return inspect(file, acked, exit)
    }

    private fun inspect(file: Path, acked: Int, exit: Int): Outcome {
        val bytes = Files.readAllBytes(file)
        val text = String(bytes, Charsets.UTF_8)
        val endsClean = text.isEmpty() || text.endsWith("\n")
        val lines = text.split("\n")
        val complete = lines.dropLast(1)
        val torn = if (endsClean) 0 else lines.last().toByteArray(Charsets.UTF_8).size
        for ((i, l) in complete.withIndex()) {
            val row = JsonlLedgerSink.JSON.decodeFromString(RouteRecord.serializer(), l)
            assertEquals(harnessRow(i + 1), row, "row ${i + 1} is intact")
        }
        return Outcome(acked, complete.size, torn, exit)
    }

    @Test
    fun `rows acknowledged before SIGKILL are intact at every durability point, in both channel models`() {
        val dir = Files.createTempDirectory("asom-sigkill-")
        val exercised = LinkedHashMap<String, Int>()
        var runs = 0
        try {
            for (mode in listOf("real", "volatile")) {
                for (row in listOf(1, 3)) {
                    for (point in listOf("BEFORE_WRITE", "MID_WRITE", "AFTER_WRITE_BEFORE_FORCE", "AFTER_FORCE_BEFORE_ACK", "AFTER_ACK")) {
                        val o = runChild(dir, mode, point, row, total = 5)
                        runs++
                        assertEquals(137, o.exit, "the child must die of SIGKILL (137), not exit by itself: $mode $point $row")
                        val ackedExpected = if (point == "AFTER_ACK") row else row - 1
                        assertEquals(ackedExpected, o.acked, "acknowledged rows at $mode $point $row")
                        // durable >= acknowledged, and at most one unacknowledged row survived
                        assertTrue(o.onDiskRows >= o.acked, "an acknowledged row is missing after the kill at $mode/$point/$row: acked=${o.acked} onDisk=${o.onDiskRows}")
                        assertTrue(o.onDiskRows <= o.acked + 1)
                        val expectedRows = when (mode to point) {
                            "real" to "BEFORE_WRITE", "real" to "MID_WRITE" -> row - 1
                            "real" to "AFTER_WRITE_BEFORE_FORCE", "real" to "AFTER_FORCE_BEFORE_ACK", "real" to "AFTER_ACK" -> row
                            "volatile" to "BEFORE_WRITE", "volatile" to "MID_WRITE", "volatile" to "AFTER_WRITE_BEFORE_FORCE" -> row - 1
                            else -> row // volatile, force returned
                        }
                        assertEquals(expectedRows, o.onDiskRows, "rows on disk at $mode/$point/$row")
                        val expectTorn = mode == "real" && point == "MID_WRITE"
                        assertEquals(expectTorn, o.tornBytes > 0, "torn tail at $mode/$point/$row")
                        Report.line("rows intact after kill at $point (row $row, $mode): acked=${o.acked} on-disk=${o.onDiskRows} torn-bytes=${o.tornBytes} exit=${o.exit}")

                        // Reopen: the torn tail (never acknowledged) is removed, acknowledged rows stay, and appending continues cleanly.
                        val file = dir.resolve("ledger-$mode-$point-$row.jsonl")
                        JsonlLedgerSink.open(file).use { sink ->
                            assertEquals(o.tornBytes.toLong(), sink.recoveredTornBytes)
                            sink.appendLine(JsonlLedgerSink.JSON.encodeToString(RouteRecord.serializer(), harnessRow(o.onDiskRows + 1)))
                        }
                        val after = inspect(file, o.acked, o.exit)
                        assertEquals(o.onDiskRows + 1, after.onDiskRows)
                        assertEquals(0, after.tornBytes)
                        exercised.merge("$mode/$point", 1, Int::plus)
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
        val expectedKeys = listOf("real", "volatile").flatMap { m -> listOf("BEFORE_WRITE", "MID_WRITE", "AFTER_WRITE_BEFORE_FORCE", "AFTER_FORCE_BEFORE_ACK", "AFTER_ACK").map { "$m/$it" } }
        assertEquals(expectedKeys.toSet(), exercised.keys, "every durability point must be exercised in both models")
        assertTrue(exercised.values.all { it >= 2 })
        Report.line("sigkill harness: $runs kills at ${exercised.size} (model, point) pairs; claim: process death only, not power loss")
    }
}
