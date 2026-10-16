package xyz.mdhv.asom.lab.ledger

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The JSONL sink through a channel it does not own: what the file system cannot show (a short write, whether force ran). */
class SinkHooksTest {
    private fun row(i: Int) = LabRouteRecord(
        ts = i.toLong(), callerPkg = "peer:x", requestedModel = "", egress = LabEgress.peerClass, meshKind = MeshKind.CONTROL, meshCode = "HELLO", sessionId = "s", bytesOut = i.toLong(),
    )

    private fun tmp(): Path = Files.createTempFile("asom-ledger-hooks-", ".jsonl")

    @Test
    fun aWriteThatFailsPartwayThroughLeavesNoPartialLineBehind_LTQ03() {
        val f = tmp()
        Files.delete(f)
        var calls = 0
        val sink = JsonlSink(f, opener = { p ->
            object : SpyChannel(JsonlSink.openChannel(p)) {
                override fun write(src: java.nio.ByteBuffer): Int {
                    if (++calls == 2) {
                        val cut = src.duplicate().also { it.limit(src.position() + src.remaining() / 2) }
                        inner.write(cut)
                        src.position(cut.limit())
                        throw java.io.IOException("short write")
                    }
                    return inner.write(src)
                }
            }
        })
        sink.append(row(1))
        val size = Files.size(f)
        assertFailsWith<LedgerWriteException> { sink.append(row(2)) }
        assertEquals(size, Files.size(f), "the bytes of the half-written row were truncated back")
        sink.append(row(3))
        sink.close()
        assertEquals(listOf(row(1), row(3)), JsonlReader.read(f).rows)
        Files.delete(f)
    }

    @Test
    fun theDefaultSinkForcesEveryAppendAndForcesWithoutMetadata_LTQ11() {
        val f = tmp()
        Files.delete(f)
        lateinit var spy: SpyChannel
        val sink = JsonlSink(f, opener = { p -> SpyChannel(JsonlSink.openChannel(p)).also { spy = it } })
        for (i in 1..4) {
            sink.append(row(i))
            assertEquals(i, spy.forces.size, "the default force ran once per append, before append returned")
            assertEquals(Files.size(f), spy.sizeAtForce.last(), "and after the whole row was written")
        }
        assertTrue(spy.forces.all { !it }, "force(false): data only, as LAB_SPEC 7.5 states")
        sink.close()
        Files.delete(f)
    }

    @Test
    fun theWriteThenForceOrderHolds_LTQ11() {
        val f = tmp()
        Files.delete(f)
        lateinit var spy: SpyChannel
        val sink = JsonlSink(f, opener = { p -> SpyChannel(JsonlSink.openChannel(p)).also { spy = it } })
        sink.append(row(1))
        assertEquals(listOf("write", "force"), spy.log.filter { it == "write" || it == "force" }.distinct())
        assertEquals("force", spy.log.last { it == "write" || it == "force" })
        sink.close()
        Files.delete(f)
    }
}

open class SpyChannel(val inner: java.nio.channels.FileChannel) : java.nio.channels.FileChannel() {
    val forces = ArrayList<Boolean>()
    val sizeAtForce = ArrayList<Long>()
    val log = ArrayList<String>()

    override fun force(metaData: Boolean) {
        log += "force"
        forces += metaData
        sizeAtForce += inner.size()
        inner.force(metaData)
    }

    override fun write(src: java.nio.ByteBuffer): Int {
        log += "write"
        return inner.write(src)
    }

    override fun read(dst: java.nio.ByteBuffer): Int = inner.read(dst)
    override fun read(dsts: Array<out java.nio.ByteBuffer>, offset: Int, length: Int): Long = inner.read(dsts, offset, length)
    override fun write(srcs: Array<out java.nio.ByteBuffer>, offset: Int, length: Int): Long = inner.write(srcs, offset, length)
    override fun position(): Long = inner.position()
    override fun position(newPosition: Long): java.nio.channels.FileChannel = also { inner.position(newPosition) }
    override fun size(): Long = inner.size()
    override fun truncate(size: Long): java.nio.channels.FileChannel = also { log += "truncate"; inner.truncate(size) }
    override fun transferTo(position: Long, count: Long, target: java.nio.channels.WritableByteChannel): Long = inner.transferTo(position, count, target)
    override fun transferFrom(src: java.nio.channels.ReadableByteChannel, position: Long, count: Long): Long = inner.transferFrom(src, position, count)
    override fun read(dst: java.nio.ByteBuffer, position: Long): Int = inner.read(dst, position)
    override fun write(src: java.nio.ByteBuffer, position: Long): Int = inner.write(src, position)
    override fun map(mode: MapMode, position: Long, size: Long): java.nio.MappedByteBuffer = inner.map(mode, position, size)
    override fun lock(position: Long, size: Long, shared: Boolean): java.nio.channels.FileLock = inner.lock(position, size, shared)
    override fun tryLock(position: Long, size: Long, shared: Boolean): java.nio.channels.FileLock? = inner.tryLock(position, size, shared)
    override fun implCloseChannel() = inner.close()
}
