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
     * trust.md 3.3 as amended by R4-T-08 (design 4.3 and T9): 30 min forces a fresh handshake and the keyguard-bound leaf. It supersedes ERR-PI-3's PROVISIONAL 24 h.
     * A session with an open stream is never cut for age; it says `GOAWAY max-age` at the first moment it has none.
     */
    const val MAX_AGE_MS: Long = 1_800_000L

    /**
     * PROVISIONAL (ERRATA ERR-R4B-ST-2): real milliseconds a `GOAWAY` posted by a registry change may wait for its session's driver before the driver's watchdog closes the
     * transport (the driver is stalled in a write to a peer that does not read). The same figure as the close wait of the TLS transport.
     */
    const val POSTED_GOAWAY_GRACE_MS: Long = 500L

    /** PROVISIONAL: how long an accepted attempt waits for its `INFER_BODY` (never longer than the offer's own `deadlineMs`). */
    const val BODY_WAIT_MS: Long = 30_000L

    /** PROVISIONAL (ERRATA ERR-FX2-2): how long the requester waits for the answer to a `STATE_REQ` or `MANIFEST_REQ` before it gives up on it. */
    const val REQUEST_WAIT_MS: Long = 30_000L

    /** PROVISIONAL (ERRATA ERR-FX2-2): how long after a `CANCEL` the requester waits for the attempt to end before it treats the lender as wedged and closes the session. */
    const val CANCEL_GRACE_MS: Long = 30_000L

    /** PROVISIONAL (ERRATA ERR-FX2-7): the extension frames a listener holds before `HELLO` has named the session; one more is `FRAME_BEFORE_HELLO`. */
    const val MAX_EARLY_EXTENSIONS: Int = 16
}
