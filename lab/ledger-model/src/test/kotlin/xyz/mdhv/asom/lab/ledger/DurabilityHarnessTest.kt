package xyz.mdhv.asom.lab.ledger

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The durability harness (LAB_SPEC 7.7): a forked JVM appends through the real JSONL sink and is killed with SIGKILL (`Process.destroyForcibly()`) at each
 * durability point. Every row the child reported durable before the kill must be intact and parseable in the file. Only PROCESS death is claimed, never
 * power loss (LAB, oracle: self; NOT DEVICE EVIDENCE). The child is blocked (not inside a write) at the point, so the kill is deterministic; a second
 * test kills a write-heavy child at arbitrary moments.
 */
class DurabilityHarnessTest {
    private class ChildRun(val durable: List<Pair<String, String>>, val stopped: String?, val exit: Int)

    private fun java(): String = File(System.getProperty("java.home"), "bin/java").path

    private fun launch(dir: Path, vararg args: String, killWhen: (String) -> Boolean, killAfterLines: Int = Int.MAX_VALUE): ChildRun {
        val pb = ProcessBuilder(listOf(java(), "-cp", System.getProperty("java.class.path"), "xyz.mdhv.asom.lab.ledger.DurabilityChild", dir.toString()) + args)
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        val p = pb.start()
        val durable = ArrayList<Pair<String, String>>()
        var stopped: String? = null
        BufferedReader(InputStreamReader(p.inputStream)).use { r ->
            var lines = 0
            while (true) {
                val line = r.readLine() ?: break
                lines++
                val parts = line.split(' ')
                if (parts[0] == "DURABLE") durable += parts[1] to parts[2]
                if (killWhen(line) || lines >= killAfterLines) {
                    stopped = line
                    p.destroyForcibly()
                    break
                }
            }
        }
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the child did not die")
        return ChildRun(durable, stopped, p.exitValue())
    }

    private fun verify(dir: Path, run: ChildRun, nodes: List<String>): Int {
        val perNode = nodes.associateWith { n ->
            val f = dir.resolve("$n.jsonl")
            JsonlReader.read(f)
        }
        var verified = 0
        val cursor = HashMap<String, Int>()
        for ((node, hash) in run.durable) {
            val read = perNode.getValue(node)
            val i = cursor.getOrDefault(node, 0)
            assertTrue(i < read.rows.size, "row #$i reported durable on $node is missing from the file")
            assertEquals(hash, DurabilityChild.digest(read.rows[i].toRowBytes()), "row #$i on $node differs from what the child reported durable")
            cursor[node] = i + 1
            verified++
        }
        return verified
    }

    @Test
    fun rowsAreIntactAfterSigkillAtEveryDurabilityPoint() {
        var points = 0
        for ((index, entry) in FullScript.POINTS.withIndex()) {
            for (mode in listOf("before", "after")) {
                val dir = Files.createTempDirectory("asom-dur-")
                try {
                    val run = launch(dir, "point", index.toString(), mode, killWhen = { it.startsWith("ABOUT") || it.startsWith("AT") })
                    assertNotNull(run.stopped, "the child never reached durability point '${entry.first}' ($mode): the script must exercise every point")
                    assertTrue(run.exit != 0, "the child must have been killed, not have exited normally (exit ${run.exit})")
                    val verified = verify(dir, run, listOf("A", "B"))
                    val expectedInFile = run.durable.size
                    val inFile = listOf("A", "B").sumOf { JsonlReader.read(dir.resolve("$it.jsonl")).rows.size }
                    assertEquals(expectedInFile, inFile, "before the kill the files hold exactly the rows reported durable")
                    val matching = listOf("A", "B").sumOf { n -> JsonlReader.read(dir.resolve("$n.jsonl")).rows.count(entry.second) }
                    assertEquals(if (mode == "before") 0 else 1, matching, "rows of this kind on disk after a kill $mode the point")
                    println("rows intact after kill at ${entry.first} [$mode]: $verified durable rows verified byte for byte, torn tail bytes: ${listOf("A", "B").sumOf { JsonlReader.read(dir.resolve("$it.jsonl")).tornTailBytes }}")
                    points++
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
        }
        assertEquals(FullScript.POINTS.size * 2, points)
    }

    @Test
    fun rowsAreIntactAfterSigkillDuringAWriteHeavyRun() {
        var totalDurable = 0
        repeat(3) { round ->
            val dir = Files.createTempDirectory("asom-dur-stress-")
            try {
                val run = launch(dir, "stress", killWhen = { false }, killAfterLines = 40 + round * 37)
                assertTrue(run.exit != 0)
                val read = JsonlReader.read(dir.resolve("S.jsonl"))
                assertTrue(read.rows.size >= run.durable.size, "the file holds every row reported durable (${read.rows.size} >= ${run.durable.size})")
                for ((i, d) in run.durable.withIndex()) assertEquals(d.second, DurabilityChild.digest(read.rows[i].toRowBytes()), "row $i differs")
                assertTrue(read.tornTailBytes >= 0)
                totalDurable += run.durable.size
                println("rows intact after kill at arbitrary moment #$round: ${run.durable.size} durable rows verified, ${read.rows.size} complete rows in the file, torn tail bytes: ${read.tornTailBytes}")
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
        assertTrue(totalDurable > 100)
    }
}
