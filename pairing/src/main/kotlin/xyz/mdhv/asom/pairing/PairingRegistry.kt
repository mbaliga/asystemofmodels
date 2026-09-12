package xyz.mdhv.asom.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import xyz.mdhv.asom.contract.PairingStatusCode

/** Server-agnostic validation result; :app adapts this to the HTTP layer. */
sealed interface TokenCheck {
    data class Valid(val callerPkg: String) : TokenCheck
    data object Revoked : TokenCheck
    data object Unknown : TokenCheck
}

/**
 * Pairing state machine (§5.7). Room persists rows (hashes only); pending
 * consent callbacks and once-only undelivered raw tokens live in memory —
 * if the process dies before delivery, the client simply re-pairs.
 */
class PairingRegistry(
    private val dao: PairingDao,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val pkg: String, val certHash: String)

    /** Consent decisions waiting to be pushed back over AIDL. */
    private val callbacks = ConcurrentHashMap<Key, (status: Int, token: String?) -> Unit>()

    /** Raw tokens minted but not yet fetched (delivered exactly once). */
    private val undelivered = ConcurrentHashMap<Key, String>()

    // ------------------------------------------------------------- AIDL side

    fun requestPairing(caller: VerifiedCaller, callback: (Int, String?) -> Unit) {
        val key = Key(caller.packageName, caller.certHash)
        val existing = dao.find(caller.packageName, caller.certHash)
        if (existing?.status == PairingStatusCode.PAIRED) {
            callback(PairingStatusCode.PAIRED, null) // already paired; token not re-issuable
            return
        }
        dao.upsert(
            PairingEntity(
                packageName = caller.packageName,
                certHash = caller.certHash,
                label = caller.label,
                tokenHash = existing?.tokenHash,
                status = PairingStatusCode.PENDING,
                createdAt = existing?.createdAt ?: clock(),
                approvedAt = null,
            ),
        )
        callbacks[key] = callback
    }

    fun status(caller: VerifiedCaller): Int =
        dao.find(caller.packageName, caller.certHash)?.status ?: PairingStatusCode.NOT_PAIRED

    /** Raw token exactly once after approval; null forever after. */
    fun takeToken(caller: VerifiedCaller): String? =
        undelivered.remove(Key(caller.packageName, caller.certHash))

    // ---------------------------------------------------------- consent side

    /** All requests currently awaiting user consent. */
    fun pending(): List<PairingEntity> =
        dao.all().filter { it.status == PairingStatusCode.PENDING }

    /** User approved on the consent sheet: mint 256-bit token, store hash. */
    fun approve(pkg: String, certHash: String): String? {
        val row = dao.find(pkg, certHash) ?: return null
        if (row.status != PairingStatusCode.PENDING) return null
        val token = ByteArray(32).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        dao.upsert(
            row.copy(
                tokenHash = sha256Hex(token),
                status = PairingStatusCode.PAIRED,
                approvedAt = clock(),
            ),
        )
        val key = Key(pkg, certHash)
        undelivered[key] = token
        callbacks.remove(key)?.invoke(PairingStatusCode.PAIRED, token)
        return token
    }

    /** User denied: the row disappears (never paired). */
    fun deny(pkg: String, certHash: String) {
        dao.delete(pkg, certHash)
        callbacks.remove(Key(pkg, certHash))?.invoke(PairingStatusCode.NOT_PAIRED, null)
    }

    // -------------------------------------------------------- dashboard side

    /** Hotspot tab revocation — takes effect on the next request (§5.7). */
    fun revoke(pkg: String, certHash: String) {
        dao.setStatus(pkg, certHash, PairingStatusCode.REVOKED)
        undelivered.remove(Key(pkg, certHash))
    }

    fun remove(pkg: String, certHash: String) {
        dao.delete(pkg, certHash)
        undelivered.remove(Key(pkg, certHash))
    }

    // ------------------------------------------------------------ HTTP side

    /** Constant-time token check for the server's auth middleware. */
    fun check(rawToken: String): TokenCheck {
        val hash = sha256Hex(rawToken).toByteArray(Charsets.US_ASCII)
        for (row in dao.allWithTokens()) {
            val stored = row.tokenHash?.toByteArray(Charsets.US_ASCII) ?: continue
            if (MessageDigest.isEqual(stored, hash)) {
                return if (row.status == PairingStatusCode.REVOKED) {
                    TokenCheck.Revoked
                } else {
                    TokenCheck.Valid(row.packageName)
                }
            }
        }
        return TokenCheck.Unknown
    }

    companion object {
        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
