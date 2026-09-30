package xyz.mdhv.asom.lab.ledger

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.laws.LedgerLaws
import xyz.mdhv.asom.lab.ledger.sim.SimConfig

class SinkTest {
    private fun row(i: Int, session: String = "s") = LabRouteRecord(
        ts = i.toLong(), callerPkg = "peer:x", requestedModel = "", egress = LabEgress.peerClass, meshKind = MeshKind.CONTROL, meshCode = "HELLO", sessionId = session, bytesOut = i.toLong(),
    )

    private fun tmp(): Path = Files.createTempFile("asom-ledger-", ".jsonl")

    @Test
    fun theSinkInterfaceOffersAppendAndNothingElse_L6() {
        assertEquals(setOf("append"), RowSink::class.java.methods.map { it.name }.toSet(), "rows are never updated or deleted: no other operation exists")
        assertEquals(setOf("append", "all"), MemorySink::class.java.declaredMethods.map { it.name }.filter { !it.contains("$") }.toSet())
    }

    @Test
    fun jsonlAppendsOneJcsLinePerRowAndOnlyEverGrowsTheFile_L6() {
        val f = tmp()
        var checks = 0
        JsonlSink(f).use { sink ->
            var prev = Files.readAllBytes(f)
            for (i in 1..50) {
                sink.append(row(i))
                val now = Files.readAllBytes(f)
                assertTrue(LedgerLaws.l6PrefixPreserved(prev, now).ok, "append #$i changed earlier bytes")
                assertTrue(now.size > prev.size)
                assertEquals('\n'.code.toByte(), now.last())
                prev = now
                checks++
            }
        }
        val read = JsonlReader.read(f)
        assertEquals((1..50).map { row(it) }, read.rows)
        assertEquals(0, read.tornTailBytes)
        assertEquals(50, checks)
        Files.readAllLines(f).forEach { assertTrue(!it.contains('\n') && it.startsWith("{") && it.endsWith("}")) }
        Files.delete(f)
    }


    @Test
    fun reopeningTheFileAppendsAfterEveryEarlierRow_L6() {
        val f = tmp()
        JsonlSink(f).use { it.append(row(1)); it.append(row(2)) }
        val before = Files.readAllBytes(f)
        JsonlSink(f).use { it.append(row(3)) }
        val after = Files.readAllBytes(f)
        assertTrue(LedgerLaws.l6PrefixPreserved(before, after).ok && after.size > before.size)
        assertEquals((1..3).map { row(it) }, JsonlReader.read(f).rows)
        Files.delete(f)
    }

    @Test
    fun theAppendReturnsOnlyAfterForceAndForceSeesTheWholeRow() {
        val f = tmp()
        Files.delete(f)
        val sizes = ArrayList<Long>()
        JsonlSink(f, force = { ch -> sizes += ch.size() }).use { sink ->
            for (i in 1..5) {
                sink.append(row(i))
                assertEquals(i, sizes.size, "one force per append, before append returned")
                assertEquals(Files.size(f), sizes.last(), "the whole row was written before force ran")
            }
        }
        assertEquals(sizes.sorted(), sizes)
        val failing = JsonlSink(f, force = { throw java.io.IOException("disk gone") })
        assertFailsWith<LedgerWriteException> { failing.append(row(9)) }
        failing.close()
        Files.delete(f)
    }

    @Test
    fun aNewLedgerFileIsCreatedWithMode0600() {
        val f = tmp()
        Files.delete(f)
        JsonlSink(f).use { it.append(row(1)) }
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f)))
        } else {
            assertTrue(Files.isRegularFile(f), "the sink still creates the file where there are no POSIX permissions")
        }
        Files.delete(f)
    }

    @Test
    fun aTornTailIsReportedNeverParsedAndACorruptCompleteLineIsRefused() {
        val f = tmp()
        JsonlSink(f).use { it.append(row(1)); it.append(row(2)) }
        Files.write(f, "{\"ts\":3,\"callerP".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        val read = JsonlReader.read(f)
        assertEquals(2, read.rows.size)
        assertTrue(read.tornTailBytes > 0)
        Files.write(f, "\n".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        assertFailsWith<CorruptRowException> { JsonlReader.read(f) }
        Files.delete(f)
    }

    @Test
    fun anAppendToAClosedFileThrowsAndAnInvalidRowNeverReachesTheFile() {
        val f = tmp()
        val sink = JsonlSink(f)
        sink.close()
        assertFailsWith<LedgerWriteException> { sink.append(row(1)) }
        val open = JsonlSink(f)
        assertFailsWith<IllegalArgumentException> { open.append(row(1).copy(sessionId = null)) }
        assertEquals(0, Files.size(f))
        open.close()
        Files.delete(f)
    }

    @Test
    fun theCrashInjectingSinkFailsAtTheChosenAppendsInBothModes() {
        val before = MemorySink()
        val s1 = CrashInjectingSink(before, setOf(1))
        s1.append(row(0))
        assertFailsWith<LedgerWriteException> { s1.append(row(1)) }
        s1.append(row(2))
        assertEquals(listOf(0L, 2L), before.all().map { it.ts }, "BEFORE_WRITE: the failed row is absent, the next append succeeds")
        val after = MemorySink()
        val s2 = CrashInjectingSink(after, setOf(0), FailMode.AFTER_WRITE)
        assertFailsWith<LedgerWriteException> { s2.append(row(0)) }
        assertEquals(1, after.all().size, "AFTER_WRITE: written, but the append reported failure: the caller acts as if it is not durable")
        val sticky = CrashInjectingSink(MemorySink(), setOf(0), sticky = true)
        assertFailsWith<LedgerWriteException> { sticky.append(row(0)) }
        assertFailsWith<LedgerWriteException> { sticky.append(row(1)) }
        assertEquals(2, sticky.failures)
    }

    private class PrefixChecking(val inner: JsonlSink, val path: Path) : RowSink {
        var checks = 0
        private var prev = Files.readAllBytes(path)

        override fun append(row: LabRouteRecord) {
            inner.append(row)
            val now = Files.readAllBytes(path)
            assertTrue(LedgerLaws.l6PrefixPreserved(prev, now).ok)
            prev = now
            checks++
        }
    }

    @Test
    fun randomWorldsThroughTheFileSinkLeaveExactlyTheRowsTheTraceSawAndL6Holds() {
        val dirs = Files.createTempDirectory("asom-jsonl-")
        var rows = 0
        var checks = 0
        for (seed in 1L..25L) {
            val sinks = HashMap<String, PrefixChecking>()
            val cfg = SimConfig(seed = seed, sinkFor = { name ->
                val p = dirs.resolve("$seed-$name.jsonl")
                Files.createFile(p)
                PrefixChecking(JsonlSink(p), p).also { sinks[name] = it }
            })
            val w = runWorld(seed, cfg)
            for (name in listOf("A", "B")) {
                val read = JsonlReader.read(dirs.resolve("$seed-$name.jsonl"))
                assertEquals(w.rows(name), read.rows, "seed $seed $name: the file holds exactly the rows that became durable, in order")
                rows += read.rows.size
                checks += sinks.getValue(name).checks
                sinks.getValue(name).inner.close()
            }
        }
        assertTrue(rows > 500 && checks == rows, "rows $rows, prefix checks $checks")
        println("L-L6 iterations: $checks appends, each followed by a prefix check of the file, over 25 random worlds")
        dirs.toFile().deleteRecursively()
    }
}
