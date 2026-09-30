package xyz.mdhv.asom.client

import android.content.Context
import xyz.mdhv.asom.contract.client.InferenceClient

/**
 * §10A.2 fallback tiers. Priority: RemoteAsom → Embedded (if bundled, v2) →
 * CloudOnly. Re-evaluated on every [resolve] call, so installing or removing
 * asom mid-life degrades/upgrades gracefully — no data loss, no crash.
 */
class FallbackResolver(
    context: Context,
    /** v2 seam: the `Embedded` impl from asom-standalone. Null in v1. */
    private val embedded: InferenceClient? = null,
    /** The app's `CloudOnly` impl from `:client-cloud`, if it ships one. */
    private val cloudOnly: InferenceClient? = null,
) {
    enum class Tier { REMOTE_ASOM, EMBEDDED, CLOUD_ONLY, NONE }

    data class Resolved(val tier: Tier, val client: InferenceClient?)

    private val remote = RemoteAsom(context)

    /** The RemoteAsom instance (for pairing flows). */
    fun remoteAsom(): RemoteAsom = remote

    fun resolve(): Resolved = when {
        remote.available() -> Resolved(Tier.REMOTE_ASOM, remote)
        embedded != null -> Resolved(Tier.EMBEDDED, embedded)
        cloudOnly != null -> Resolved(Tier.CLOUD_ONLY, cloudOnly)
        else -> Resolved(Tier.NONE, null)
    }
}
