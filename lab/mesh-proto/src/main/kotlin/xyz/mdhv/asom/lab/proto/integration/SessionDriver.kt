package xyz.mdhv.asom.lab.proto.integration

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import xyz.mdhv.asom.lab.proto.session.Session
import xyz.mdhv.asom.lab.proto.session.SessionLimits

/**
 * Runs one [Session] over a blocking transport: a reader thread (`runReadLoop`) and a ticker thread that steps the lender's engine and applies the timers
 * of trust.md 5.1, and applies a `GOAWAY` that a registry change posted (trust.md R4-T-08: the revoking thread never writes). A third thread is the watchdog for the
 * one case the other two cannot handle: both can be stuck behind a write to a peer that does not read, so a posted `GOAWAY` that waits longer than
 * [SessionLimits.POSTED_GOAWAY_GRACE_MS] in real time makes the watchdog close the transport, which wakes the writer. The clock is injected, so a test moves time by changing it and never sleeps. Nothing here opens a socket or a listener; the host hands
 * in a session whose connection already exists.
 */
class SessionDriver(private val session: Session, private val clock: () -> Long, name: String, private val pollMs: Long = 1) : AutoCloseable {
    private val stop = AtomicBoolean(false)
    private val reader = Thread({ session.runReadLoop() }, "$name-read").also { it.isDaemon = true }
    private val ticker = Thread({ loop() }, "$name-tick").also { it.isDaemon = true }
    private val watchdog = Thread({ guard() }, "$name-watch").also { it.isDaemon = true }

    fun start(): SessionDriver {
        reader.start()
        ticker.start()
        watchdog.start()
        return this
    }

    private fun loop() {
        while (!stop.get() && !session.closed) {
            val moved = session.advance()
            session.tick(clock())
            if (moved == 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(pollMs))
        }
    }

    private fun guard() {
        while (!stop.get() && !session.closed) {
            if (session.goAwayPostedForMs() >= SessionLimits.POSTED_GOAWAY_GRACE_MS) {
                session.abortTransport()
                return
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        }
    }

    /** Stops the ticker, closes the session (idempotent) and waits, bounded, for both threads. */
    override fun close() {
        stop.set(true)
        session.close()
        ticker.join(2_000)
        reader.join(2_000)
        watchdog.join(2_000)
    }

    val running: Boolean get() = reader.isAlive || ticker.isAlive || watchdog.isAlive
}
