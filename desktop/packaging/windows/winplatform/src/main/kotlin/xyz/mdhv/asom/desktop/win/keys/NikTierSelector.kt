package xyz.mdhv.asom.desktop.win.keys

import java.security.SecureRandom

enum class KeyTierRequest {
    /** T2, then T1 after a failed create or self-test. Never T0. */
    AUTO,

    /** `--key-tier file`: T0 only, for CI and headless tests. */
    FILE,
}

data class TierAttempt(val tier: NikTier, val ok: Boolean, val detail: String)

class NikSelection(val key: NikKey, val attempts: List<TierAttempt>) {
    val tier: NikTier get() = key.tier
}

/** Every allowed tier failed. There is deliberately no fall-through to T0, so the node cannot silently store its key in a file. */
class NikSelectionFailure(val attempts: List<TierAttempt>) :
    IllegalStateException("no key tier could be created: " + attempts.joinToString("; ") { "${it.tier.id}: ${it.detail}" })

/**
 * The selection rule of windows.md 5, applied at the FIRST mesh enable and never earlier (the NIK is generated at first mesh
 * enable only):
 *  1. try T2;
 *  2. on a failed create or a failed self-test (sign and verify a fresh 32-byte challenge) fall back to T1;
 *  3. T0 only when [KeyTierRequest.FILE] is asked for explicitly.
 * A key that fails its self-test is deleted again, so a half-working T2 key is not left behind on the TPM.
 */
object NikTierSelector {
    private val random = SecureRandom()

    fun select(
        request: KeyTierRequest,
        t2: () -> NikKey,
        t1: () -> NikKey,
        t0: () -> NikKey,
        challenge: () -> ByteArray = { ByteArray(32).also(random::nextBytes) },
    ): NikSelection {
        val attempts = ArrayList<TierAttempt>(3)
        val order: List<Pair<NikTier, () -> NikKey>> = when (request) {
            KeyTierRequest.AUTO -> listOf(NikTier.T2_TPM to t2, NikTier.T1_OS_KEYSTORE to t1)
            KeyTierRequest.FILE -> listOf(NikTier.T0_FILE to t0)
        }
        for ((tier, create) in order) {
            val key = try {
                create()
            } catch (e: Exception) {
                attempts += TierAttempt(tier, false, "create failed: ${e.message ?: e::class.simpleName}")
                continue
            }
            val problem = selfTest(key, challenge())
            if (problem == null) {
                attempts += TierAttempt(tier, true, "created and self-tested")
                return NikSelection(key, attempts)
            }
            val cleanup = try {
                key.delete()
                "key deleted"
            } catch (e: Exception) {
                "key NOT deleted (${e.message ?: e::class.simpleName}); remove it by hand"
            }
            attempts += TierAttempt(tier, false, "self-test failed: $problem; $cleanup")
        }
        throw NikSelectionFailure(attempts)
    }

    /** Null when the key signs and the public key verifies the signature; otherwise the reason. */
    fun selfTest(key: NikKey, challenge: ByteArray): String? = try {
        require(challenge.size == 32) { "the challenge must be 32 bytes" }
        val sig = key.sign(challenge)
        when {
            sig.size != Es256.SIGNATURE_BYTES -> "signature is ${sig.size} bytes, not 64"
            !Es256.verify(key.spki(), challenge, sig) -> "signature does not verify against the exported public key"
            else -> null
        }
    } catch (e: Exception) {
        "exception ${e::class.simpleName}: ${e.message}"
    }
}
