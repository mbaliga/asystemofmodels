package xyz.mdhv.asom.lab.proto.integration

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import xyz.mdhv.asom.lab.proto.session.Session

/**
 * Runs one [Session] over a blocking transport: a reader thread (`runReadLoop`) and a ticker thread that steps the lender's engine and applies the timers
 * of trust.md 5.1. The clock is injected, so a test moves time by changing it and never sleeps. Nothing here opens a socket or a listener; the host hands
 * in a session whose connection already exists.
 */
class SessionDriver(private val session: Session, private val clock: () -> Long, name: String, private val pollMs: Long = 1) : AutoCloseable {
    private val stop = AtomicBoolean(false)
    private val reader = Thread({ session.runReadLoop() }, "$name-read").also { it.isDaemon = true }
    private val ticker = Thread({ loop() }, "$name-tick").also { it.isDaemon = true }

    fun start(): SessionDriver {
        reader.start()
        ticker.start()
        return this
    }

    private fun loop() {
        while (!stop.get() && !session.closed) {
            val moved = session.advance()
            session.tick(clock())
            if (moved == 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(pollMs))
        }
    }

    /** Stops the ticker, closes the session (idempotent) and waits, bounded, for both threads. */
    override fun close() {
        stop.set(true)
        session.close()
        ticker.join(2_000)
        reader.join(2_000)
    }

    val running: Boolean get() = reader.isAlive || ticker.isAlive
}
