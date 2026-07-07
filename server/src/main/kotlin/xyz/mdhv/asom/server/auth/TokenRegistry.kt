package xyz.mdhv.asom.server.auth

import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** Outcome of bearer-token validation, mapped to §5.6 codes by the HTTP layer. */
sealed interface AuthResult {
    /** Token valid; [callerPkg] is the verified caller identity. */
    data class Valid(val callerPkg: String) : AuthResult

    /** Token was minted once but has been revoked (→ TOKEN_REVOKED). */
    data object Revoked : AuthResult

    /** Token unknown (→ NOT_PAIRED). */
    data object Unknown : AuthResult
}

/** Server-side seam; Android plugs the pairing Room store in here (P6). */
interface TokenValidator {
    fun validate(rawToken: String): AuthResult
}

/**
 * In-memory token registry for the desktop server and JVM tests (P3).
 * Stores only SHA-256 hashes of tokens and compares constant-time —
 * same laws as the on-device pairing store (§5.7).
 */
class InMemoryTokenRegistry : TokenValidator {

    private data class Entry(val tokenHash: ByteArray, val callerPkg: String, val revoked: Boolean)

    private val entries = CopyOnWriteArrayList<Entry>()

    fun issue(rawToken: String, callerPkg: String) {
        entries.add(Entry(sha256(rawToken), callerPkg, revoked = false))
    }

    fun revoke(rawToken: String) {
        val hash = sha256(rawToken)
        entries.replaceAll { e ->
            if (MessageDigest.isEqual(e.tokenHash, hash)) e.copy(revoked = true) else e
        }
    }

    override fun validate(rawToken: String): AuthResult {
        val hash = sha256(rawToken)
        // Scan all entries with constant-time hash comparison; no early key
        // material exposure — only hashes are ever stored (§5.7).
        for (e in entries) {
            if (MessageDigest.isEqual(e.tokenHash, hash)) {
                return if (e.revoked) AuthResult.Revoked else AuthResult.Valid(e.callerPkg)
            }
        }
        return AuthResult.Unknown
    }

    companion object {
        fun sha256(value: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    }
}
