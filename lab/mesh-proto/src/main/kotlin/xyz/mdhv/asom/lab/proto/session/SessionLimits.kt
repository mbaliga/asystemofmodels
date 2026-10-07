package xyz.mdhv.asom.lab.proto.session

/**
 * The timers and limits of an established session. Two numbers come from the spec (trust.md 3.3 "Limits (defaults, per peer)": 4 concurrent streams, idle close after
 * 5 min without a stream); the other two are PROVISIONAL choices recorded in ERRATA (ERR-PI-3, ERR-PI-4), because no document pins them. All time is read from the
 * injected clock of the node's ledger; nothing here sleeps.
 */
object SessionLimits {
    /** trust.md 3.3 and 5.1: idle close after 5 min without a stream. */
    const val IDLE_MS: Long = 300_000L

    /** trust.md 3.3: 4 concurrent streams per peer. A fifth offer is declined `PEER_BUSY`. */
    const val MAX_STREAMS: Int = 4

    /**
     * PROVISIONAL: no document gives the age at which `GOAWAY max-age` is sent. 24 h is the replay window of the attemptId LRU (trust.md 5.4), so no session outlives
     * the memory that guards it. A session with an open stream is never cut for age; it is closed at the first moment it has none.
     */
    const val MAX_AGE_MS: Long = 86_400_000L

    /** PROVISIONAL: how long an accepted attempt waits for its `INFER_BODY` (never longer than the offer's own `deadlineMs`). */
    const val BODY_WAIT_MS: Long = 30_000L

    /** PROVISIONAL (ERRATA ERR-FX2-2): how long the requester waits for the answer to a `STATE_REQ` or `MANIFEST_REQ` before it gives up on it. */
    const val REQUEST_WAIT_MS: Long = 30_000L

    /** PROVISIONAL (ERRATA ERR-FX2-2): how long after a `CANCEL` the requester waits for the attempt to end before it treats the lender as wedged and closes the session. */
    const val CANCEL_GRACE_MS: Long = 30_000L

    /** PROVISIONAL (ERRATA ERR-FX2-7): the extension frames a listener holds before `HELLO` has named the session; one more is `FRAME_BEFORE_HELLO`. */
    const val MAX_EARLY_EXTENSIONS: Int = 16
}
