package xyz.mdhv.asom.lab.ledger

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ERR-FX4-LG-1 and -2: a sink whose rollback failed is poisoned (LAB_SPEC R4-L-24), and an existing ledger file is tightened to owner-only
 * (or refused) instead of keeping whatever mode it had.
 */
class SinkRecoveryTest {
    private fun row(i: Int) = LabRouteRecord(
        ts = i.toLong(), callerPkg = "peer:x", requestedModel = "", egress = LabEgress.peerClass, meshKind = MeshKind.CONTROL, meshCode = "HELLO", sessionId = "s", bytesOut = i.toLong(),
    )

    private fun tmp(): Path = Files.createTempFile("asom-ledger-recovery-", ".jsonl")

    private class TornWrite(inner: FileChannel, private val failTruncate: Boolean) : SpyChannel(inner) {
        var writes = 0
        var truncateCalls = 0

        override fun write(src: java.nio.ByteBuffer): Int {
            if (++writes == 2) {
                val cut = src.duplicate().also { it.limit(src.position() + src.remaining() / 2) }
                inner.write(cut)
                src.position(cut.limit())
                throw IOException("No space left on device")
            }
            return inner.write(src)
        }

        override fun truncate(size: Long): FileChannel {
            truncateCalls++
            if (failTruncate) throw IOException("EIO")
            return super.truncate(size)
        }
    }

    @Test
    fun aFailedRollbackPoisonsTheSinkSoNoLaterRowIsGluedOntoTheTornBytes_ERR_FX4_LG_1() {
        val f = tmp()
        Files.delete(f)
        lateinit var ch: TornWrite
        val sink = JsonlSink(f, opener = { p -> TornWrite(JsonlSink.openChannel(p), failTruncate = true).also { ch = it } })
        sink.append(row(1))
        assertFailsWith<LedgerWriteException> { sink.append(row(2)) }
        assertEquals(1, ch.truncateCalls, "the rollback was tried")
        val e3 = assertFailsWith<LedgerWriteException>("a later append must not be acknowledged on top of torn bytes") { sink.append(row(3)) }
        assertTrue(e3.message!!.contains("poisoned"), e3.message)
        assertEquals(2, ch.writes, "the poisoned sink wrote nothing further")
        sink.close()
        val read = JsonlReader.read(f)
        assertEquals(listOf(row(1)), read.rows, "the durable row 1 is still readable: the file ends in a torn tail, not in a corrupt line")
        assertTrue(read.tornTailBytes > 0)
        JsonlSink(f).use { it.append(row(4)) }
        assertEquals(listOf(row(1), row(4)), JsonlReader.read(f).rows, "reopening truncates the torn tail and the sink works again")
        Files.delete(f)
    }

    @Test
    fun aSuccessfulRollbackDoesNotPoisonTheSink_ERR_FX4_LG_1() {
        val f = tmp()
        Files.delete(f)
        val sink = JsonlSink(f, opener = { p -> TornWrite(JsonlSink.openChannel(p), failTruncate = false) })
        sink.append(row(1))
        assertFailsWith<LedgerWriteException> { sink.append(row(2)) }
        sink.append(row(3))
        sink.close()
        assertEquals(listOf(row(1), row(3)), JsonlReader.read(f).rows)
        Files.delete(f)
    }

    private fun posixOrNull(f: Path) = runCatching { Files.getPosixFilePermissions(f, LinkOption.NOFOLLOW_LINKS) }.getOrNull()

    @Test
    fun anExistingLedgerFileIsTightenedToOwnerOnlyWhenTheFileSystemCanSayWhoMayReadIt_ERR_FX4_LG_2() {
        val f = tmp()
        val posixBefore = posixOrNull(f)
        if (posixBefore != null) Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-rw-rw-"))
        JsonlSink(f).use { it.append(row(1)) }
        val posix = posixOrNull(f)
        if (posix != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), posix, "an existing 0666 ledger is tightened to 0600")
        } else {
            val acl = Files.getFileAttributeView(f, AclFileAttributeView::class.java)
            if (acl != null) {
                val owner = Files.getOwner(f)
                assertTrue(acl.acl.all { it.principal() == owner }, "no principal but the owner is on the ledger file's ACL: ${acl.acl}")
            } else {
                assertEquals(listOf(row(1)), JsonlReader.read(f).rows, "no permission model to check on this file system: the row is still written")
            }
        }
        Files.delete(f)
    }

    @Test
    fun aSymlinkAsTheLedgerPathIsRefusedAndItsTargetIsNotTouched_ERR_FX4_LG_2() {
        val dir = Files.createTempDirectory("asom-ledger-link-")
        val victim = dir.resolve("victim.txt")
        Files.write(victim, "line1\nTAIL-NO-NEWLINE".toByteArray())
        val link = dir.resolve("ledger.jsonl")
        val linked = try {
            Files.createSymbolicLink(link, victim)
            true
        } catch (_: Exception) {
            false
        }
        if (linked) {
            assertFailsWith<IOException> { JsonlSink(link) }
            assertEquals("line1\nTAIL-NO-NEWLINE", String(Files.readAllBytes(victim)), "the symlink target was not truncated by the open-time tail scan")
        } else {
            assertFailsWith<IOException>("this account cannot create symlinks: a directory as the ledger path is refused instead") { JsonlSink(dir) }
        }
        if (linked) Files.delete(link)
        Files.delete(victim)
        Files.delete(dir)
    }
}
