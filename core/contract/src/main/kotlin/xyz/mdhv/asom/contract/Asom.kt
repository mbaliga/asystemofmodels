package xyz.mdhv.asom.contract

/**
 * Root constants of the asom public contract (ASOM_BUILD_BRIEF.md §5).
 * Expanded with DTOs, header names, error codes and [RouteRecord] in P1.
 */
object Asom {
    /** Default localhost port. The server binds 127.0.0.1 ONLY (invariant §1.2). */
    const val DEFAULT_PORT: Int = 11435

    /** Loopback bind address. Never 0.0.0.0. */
    const val BIND_HOST: String = "127.0.0.1"

    /** Discovery ContentProvider authority (§5.1). */
    const val DISCOVERY_AUTHORITY: String = "xyz.mdhv.asom.discovery"

    /** Model file sharing ContentProvider authority (§5.8). */
    const val MODELS_AUTHORITY: String = "xyz.mdhv.asom.models"

    /** AIDL pairing action (§5.7). */
    const val PAIRING_ACTION: String = "xyz.mdhv.asom.PAIR"

    const val VERSION: String = "0.1.0"
}
