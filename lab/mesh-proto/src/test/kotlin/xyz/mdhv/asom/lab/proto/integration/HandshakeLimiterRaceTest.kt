package xyz.mdhv.asom.lab.proto.integration

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Timeout

/** A host's late release racing the limiter's own TTL reclaim of the same ticket (ERRATA ERR-FX4-TR-1): neither may block the other, and the slot is freed exactly once. */
@Timeout(120)
class HandshakeLimiterRaceTest {
    private fun admitted(a: Admission) = a as Admission.Admitted

    @Test
    fun aReleaseHoldingTheTicketCannotDeadlockAnAdmitThatReclaimsThatTicket() {
        val clock = FakeClock(0)
        val limiter = HandshakeLimiter(clock::peek)
        val ticket = admitted(limiter.admit("10.0.0.1"))
        clock.advance(HandshakeLimits.TICKET_TTL_MS)
        val holding = CountDownLatch(1)
        val admitterRef = AtomicReference<Thread>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val releaser = thread(isDaemon = true) {
            synchronized(ticket) {
                holding.countDown()
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (admitterRef.get() == null && System.nanoTime() < until) Thread.sleep(1)
                val admitter = admitterRef.get() ?: error("the admitting thread never started")
                while (admitter.state != Thread.State.BLOCKED && admitter.state != Thread.State.TERMINATED && System.nanoTime() < until) Thread.sleep(1)
                try { ticket.release() } catch (e: Throwable) { errors += e }
            }
        }
        holding.await()
        val admitter = thread(isDaemon = true) { try { limiter.admit("10.0.0.2") } catch (e: Throwable) { errors += e } }
        admitterRef.set(admitter)
        admitter.join(5_000)
        releaser.join(5_000)
        assertFalse(admitter.isAlive || releaser.isAlive, "deadlock: admit ${admitter.state}, release ${releaser.state}")
        assertEquals(emptyList(), errors)
        assertEquals(1, limiter.inFlightNow, "the late release and the reclaim freed the one slot once, and the new admission holds the other")
    }

    @Test
    fun aReleaseAtTheSameInstantAsTheReclaimFreesTheSlotExactlyOnceWithoutAnError() {
        val rounds = 400
        val errors = CopyOnWriteArrayList<Throwable>()
        var stuck = 0
        repeat(rounds) { round ->
            val clock = FakeClock(0)
            val limiter = HandshakeLimiter(clock::peek)
            val ticket = admitted(limiter.admit("10.0.0.1"))
            clock.advance(HandshakeLimits.TICKET_TTL_MS)
            val barrier = CyclicBarrier(2)
            val a = thread(isDaemon = true) { try { barrier.await(); limiter.admit("10.0.0.2") } catch (e: Throwable) { errors += e } }
            val b = thread(isDaemon = true) { try { barrier.await(); ticket.release() } catch (e: Throwable) { errors += e } }
            a.join(3_000)
            b.join(3_000)
            if (a.isAlive || b.isAlive) stuck++
            else {
                assertEquals(1, limiter.inFlightNow, "round $round: the old ticket must be gone and only the new admission held")
                ticket.release()
                assertEquals(1, limiter.inFlightNow, "round $round: a further release of the old ticket changes nothing")
            }
        }
        assertEquals(0, stuck, "threads stuck in $stuck of $rounds rounds")
        assertEquals(emptyList(), errors)
    }
}
