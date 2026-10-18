package xyz.mdhv.asom.desktop.mac.keys

import java.security.SecureRandom
import xyz.mdhv.asom.desktop.mac.helper.HelperLostException

enum class KeyTierRequest {
    /** T2 where the Secure Enclave exists and works, T0 otherwise (first creation only). */
    AUTO,

    /** `--key-tier file`: T0 only, for CI and headless tests. */
    FILE,
}

data class TierAttempt(val tier: NikTier, val ok: Boolean, val detail: String)

class NikSelection(val key: NikKey, val attempts: List<TierAttempt>) {
    val tier: NikTier get() = key.tier
}

/** The key could not be made at all (a lost helper is not this: it is retried, see [NikTierSelector.select]). */
class NikSelectionFailure(val attempts: List<TierAttempt>) :
    IllegalStateException("no key tier could be created: " + attempts.joinToString("; ") { "${it.tier.id}: ${it.detail}" })

/**
 * The selection rule of macos.md 5, applied at the FIRST mesh enable and never for an existing identity (an existing T2
 * identity whose blob is missing or fails its self-test is `NIK_UNAVAILABLE` and needs a user decision, see [MacNikStore]):
 *  1. `hello.se == false`: T0, recorded as such;
 *  2. otherwise create a T2 key and self-test it (sign a fresh 32-byte challenge and verify with the exported public key);
 *  3. a failed create or self-test falls back to T0, and the failed T2 key is removed again;
 *  4. a helper that cannot be reached (`PROBE_LOST`) is not an Enclave failure: it is NOT read as "no Enclave", the exception
 *     propagates, and no key of either tier is made. Otherwise a temporarily missing helper would silently pick the weaker tier.
 * [KeyTierRequest.FILE] goes straight to T0.
 * The tier chosen is always shown with its limits ([NikTier.doesNotGuarantee]).
 */
object NikTierSelector {
    private val random = SecureRandom()

    fun select(
        request: KeyTierRequest,
        seAvailable: () -> Boolean,
        t2: () -> NikKey,
        t0: () -> NikKey,
        challenge: () -> ByteArray = { ByteArray(32).also(random::nextBytes) },
    ): NikSelection {
        val attempts = ArrayList<TierAttempt>(2)
        if (request == KeyTierRequest.AUTO) {
            val available = seAvailable()
            if (!available) {
                attempts += TierAttempt(NikTier.T2_SECURE_ENCLAVE, false, "no Secure Enclave on this Mac (hello.se is false)")
            } else {
                val key = try {
                    t2()
                } catch (e: HelperLostException) {
                    throw e
                } catch (e: Exception) {
                    attempts += TierAttempt(NikTier.T2_SECURE_ENCLAVE, false, "create failed: ${e.message ?: e::class.simpleName}")
                    null
                }
                if (key != null) {
                    val problem = selfTest(key, challenge())
                    if (problem == null) {
                        attempts += TierAttempt(NikTier.T2_SECURE_ENCLAVE, true, "created and self-tested")
                        return NikSelection(key, attempts)
                    }
                    val cleanup = try {
                        key.delete()
                        "blob removed (the Enclave key itself cannot be deleted)"
                    } catch (e: Exception) {
                        "blob NOT removed (${e.message ?: e::class.simpleName}); remove nik.se by hand"
                    }
                    attempts += TierAttempt(NikTier.T2_SECURE_ENCLAVE, false, "self-test failed: $problem; $cleanup")
                }
            }
        }
        val key = try {
            t0()
        } catch (e: Exception) {
            attempts += TierAttempt(NikTier.T0_FILE, false, "create failed: ${e.message ?: e::class.simpleName}")
            throw NikSelectionFailure(attempts)
        }
        val problem = selfTest(key, challenge())
        if (problem != null) {
            runCatching { key.delete() }
            attempts += TierAttempt(NikTier.T0_FILE, false, "self-test failed: $problem")
            throw NikSelectionFailure(attempts)
        }
        attempts += TierAttempt(NikTier.T0_FILE, true, if (request == KeyTierRequest.FILE) "requested explicitly (--key-tier file)" else "fallback; created and self-tested")
        return NikSelection(key, attempts)
    }

    /** Null when the key signs and the public key verifies the signature; otherwise the reason. */
    fun selfTest(key: NikKey, challenge: ByteArray): String? = try {
        require(challenge.size == 32) { "the challenge must be 32 bytes" }
        val sig = key.sign(challenge)
        when {
            sig.size != Es256.SIGNATURE_BYTES -> "signature is ${sig.size} bytes, not 64"
            !Es256.verify(key.spki(), challenge, sig) -> "signature does not verify against the public key"
            else -> null
        }
    } catch (e: HelperLostException) {
        throw e
    } catch (e: Exception) {
        "exception ${e::class.simpleName}: ${e.message}"
    }
}
