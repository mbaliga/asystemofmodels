package xyz.mdhv.asom.server.keys

/**
 * Read-side seam over the BYOK vault. The server only ever ASKS for a key at
 * dispatch time; keys are written exclusively in the dashboard Keys tab
 * (invariant §1.4) and are never accepted or returned by any API.
 */
fun interface KeyProvider {
    /** The stored user key for [providerId], or null when none. */
    fun keyFor(providerId: String): String?
}

/** Desktop/test impl. On Android this is backed by the Keystore vault (§8). */
class InMemoryKeyProvider(private val keys: Map<String, String>) : KeyProvider {
    override fun keyFor(providerId: String): String? = keys[providerId]
}
