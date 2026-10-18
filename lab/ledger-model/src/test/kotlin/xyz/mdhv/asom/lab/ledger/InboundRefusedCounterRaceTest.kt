package xyz.mdhv.asom.lab.ledger

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every refusal is in exactly one `INBOUND_REFUSED` row, however `refused()` and `flush()` interleave (ERRATA ERR-FX4-TR-9). */
class InboundRefusedCounterRaceTest {
    private fun total(rows: List<LabRouteRecord>) = rows.sumOf { it.meshCode!!.removePrefix("refused:").toLong() }

    @Test
    fun aRefusalCountedWhileTheRowIsBeingWrittenLandsInTheNextRowAndIsNotLost() {
        var now = 0L
        val sink = MemorySink()
        lateinit var counter: InboundRefusedCounter
        val armed = AtomicBoolean(false)
        val racing = object : RowSink {
            override fun append(row: LabRouteRecord) {
                sink.append(row)
                if (armed.compareAndSet(true, false)) thread { counter.refused() }.join()
            }
        }
        counter = InboundRefusedCounter(NodeLedger("B", racing, { now }), windowMs = 600_000)
        repeat(3) { counter.refused() }
        now = 600_000
        armed.set(true)
        assertEquals("refused:3", counter.flush()?.meshCode)
        val second = counter.flush(force = true)
        assertNotNull(second, "the refusal that arrived while the first row was written has a window of its own")
        assertEquals("refused:1", second.meshCode)
        assertEquals(4L, total(sink.all()))
    }

    @Test
    fun aRowThatCouldNotBeWrittenKeepsItsCountAndItsWindowStart() {
        var now = 1_000L
        val sink = MemorySink()
        val fail = AtomicBoolean(true)
        val flaky = object : RowSink {
            override fun append(row: LabRouteRecord) {
                if (fail.get()) throw LedgerWriteException("disk full")
                sink.append(row)
            }
        }
        val counter = InboundRefusedCounter(NodeLedger("B", flaky, { now }), windowMs = 600_000)
        repeat(2) { counter.refused() }
        now = 601_000
        counter.refused()
        assertFailsWith<LedgerWriteException> { counter.flush() }
        fail.set(false)
        now = 601_001
        val r = counter.flush()
        assertNotNull(r, "the window start survived the failed write, so the window is still due")
        assertEquals("refused:3", r.meshCode)
        assertNull(counter.flush())
        assertEquals(3L, total(sink.all()))
    }

    @Test
    fun noRefusalIsLostWhenManyThreadsCountWhileAnotherFlushes() {
        val sink = MemorySink()
        val clock = AtomicLong(0)
        val counter = InboundRefusedCounter(NodeLedger("B", sink, { clock.incrementAndGet() }), windowMs = 1)
        val issued = AtomicLong(0)
        val rowsWritten = AtomicLong(0)
        val start = CountDownLatch(1)
        val deadline = System.nanoTime() + 30_000_000_000L
        val counters = List(4) {
            thread {
                start.await()
                while (rowsWritten.get() < 50 && System.nanoTime() < deadline) {
                    counter.refused()
                    issued.incrementAndGet()
                }
            }
        }
        val flusher = thread {
            start.await()
            while (rowsWritten.get() < 50 && System.nanoTime() < deadline) {
                if (counter.flush(force = true) != null) rowsWritten.incrementAndGet()
                Thread.yield()
            }
        }
        start.countDown()
        counters.forEach { it.join() }
        flusher.join()
        counter.flush(force = true)
        assertTrue(rowsWritten.get() >= 50, "the flusher interleaved with the counters in at least 50 rows")
        assertEquals(issued.get(), total(sink.all()))
    }
}
