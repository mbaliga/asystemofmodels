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
 * A client waiting on a consent decision. Keeps this module's state machine
 * free of `android.os` so it stays unit-testable; :pairing's AIDL layer backs
 * it with the caller's binder proxy.
 */
interface PendingClient {
    /** Pushes the decision; false when it could not be delivered (client gone). */
    fun deliver(status: Int, token: String?): Boolean

    /**
     * Registers [onDeath] for the client process. False when the client is
     * already gone — the registry then keeps no reference to it at all.
     */
    fun linkToDeath(onDeath: () -> Unit): Boolean

    /** Drops the death registration once the client is no longer being held. */
    fun unlink()
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

    /** Clients waiting on a decision, held only while one is genuinely owed. */
    private val callbacks = ConcurrentHashMap<Key, PendingClient>()

    /** Raw tokens minted but not yet fetched (delivered exactly once). */
    private val undelivered = ConcurrentHashMap<Key, String>()

    // ------------------------------------------------------------- AIDL side

    fun requestPairing(caller: VerifiedCaller, client: PendingClient) {
        val key = Key(caller.packageName, caller.certHash)
        val existing = dao.find(caller.packageName, caller.certHash)
        if (existing?.status == PairingStatusCode.REVOKED) {
            // §5.7: revocation is the only invalidation, so the revoked app must
            // not be able to reopen its own row over AIDL. Only the owner's
            // Remove in the Hotspot tab clears it.
            client.deliver(PairingStatusCode.REVOKED, null)
            return
        }
        undelivered.remove(key)
        dao.upsert(
            PairingEntity(
                packageName = caller.packageName,
                certHash = caller.certHash,
                label = caller.label,
                // A credential never survives a status reset. Re-requesting from a
                // PAIRED row is the re-pair path (the client lost its once-delivered
                // token, per docs/CLIENT_API.md): the old hash is retired here and a
                // fresh token is minted only if the owner consents again.
                tokenHash = null,
                status = PairingStatusCode.PENDING,
                createdAt = existing?.createdAt ?: clock(),
                approvedAt = null,
            ),
        )
        release(key)
        callbacks[key] = client
        // This registry lives in the long-lived foreground service, so a client
        // proxy held past the client's death would never be collected. Linking
        // after the put keeps the death race one-sided: a client that dies in
        // between fails to link and is dropped right here.
        if (!client.linkToDeath { callbacks.remove(key, client) }) {
            callbacks.remove(key, client)
        }
    }

    /**
     * §1.5 refusal for a caller whose identity cannot be resolved: no row, no
     * retained proxy — but an answer, so the client is not left waiting on a
     * callback that can never fire.
     */
    fun refuseUnverified(client: PendingClient) {
        client.deliver(PairingStatusCode.NOT_PAIRED, null)
    }

    /** Removes the waiting client for [key], if any, and stops watching it. */
    private fun release(key: Key): PendingClient? =
        callbacks.remove(key)?.also { it.unlink() }

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
        // Staged before the push so a client that races the callback with
        // getToken() still finds it, then dropped the moment the push lands:
        // plaintext must not sit in the daemon's heap for the process lifetime.
        // Only a failed push leaves it for the fetch-later path (§5.7).
        undelivered[key] = token
        if (release(key)?.deliver(PairingStatusCode.PAIRED, token) == true) {
            undelivered.remove(key, token)
        }
        return token
    }

    /** User denied: the row disappears (never paired). */
    fun deny(pkg: String, certHash: String) {
        dao.delete(pkg, certHash)
        release(Key(pkg, certHash))?.deliver(PairingStatusCode.NOT_PAIRED, null)
    }

    /**
     * Consent sheet closed without deciding. §5.7 keeps the row PENDING and
     * mints nothing, but the waiting client is still told so — the owner can
     * still approve out of band later, and the token is then picked up through
     * getToken().
     */
    fun dismiss() {
        for (row in pending()) {
            release(Key(row.packageName, row.certHash))
                ?.deliver(PairingStatusCode.PENDING, null)
        }
    }

    // -------------------------------------------------------- dashboard side

    /** Hotspot tab revocation — takes effect on the next request (§5.7). */
    fun revoke(pkg: String, certHash: String) {
        dao.setStatus(pkg, certHash, PairingStatusCode.REVOKED)
        val key = Key(pkg, certHash)
        undelivered.remove(key)
        release(key)?.deliver(PairingStatusCode.REVOKED, null)
    }

    fun remove(pkg: String, certHash: String) {
        dao.delete(pkg, certHash)
        val key = Key(pkg, certHash)
        undelivered.remove(key)
        release(key)?.deliver(PairingStatusCode.NOT_PAIRED, null)
    }

    // ------------------------------------------------------------ HTTP side

    /** Constant-time token check for the server's auth middleware. */
    fun check(rawToken: String): TokenCheck {
        val hash = sha256Hex(rawToken).toByteArray(Charsets.US_ASCII)
        for (row in dao.allWithTokens()) {
            val stored = row.tokenHash?.toByteArray(Charsets.US_ASCII) ?: continue
            if (MessageDigest.isEqual(stored, hash)) {
                // Allow-list, not deny-list: a hash that outlives PAIRED (revoked,
                // or a row reset to PENDING) must never authorize a request.
                return when (row.status) {
                    PairingStatusCode.PAIRED -> TokenCheck.Valid(row.packageName)
                    PairingStatusCode.REVOKED -> TokenCheck.Revoked
                    else -> TokenCheck.Unknown
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
