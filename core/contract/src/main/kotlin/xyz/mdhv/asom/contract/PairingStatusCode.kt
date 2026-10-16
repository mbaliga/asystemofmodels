package xyz.mdhv.asom.contract

/** AIDL wire ints for pairing status (§5.7; see docs/CLIENT_API.md). */
object PairingStatusCode {
    const val NOT_PAIRED = 0
    const val PENDING = 1
    const val PAIRED = 2
    const val REVOKED = 3
}
