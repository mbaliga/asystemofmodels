package xyz.mdhv.asom.server.driver

import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomException

/**
 * Guards the one place a stored key could escape the device inside an
 * exception message: OkHttp's header validator appends the offending VALUE to
 * its `IllegalArgumentException` for every header name it does not consider
 * sensitive, and `x-api-key` is not on that list. Rejecting an unusable key
 * here — with a message that names only the provider — keeps key material out
 * of every error path (invariant §1.4).
 *
 * @throws AsomException NO_PROVIDER_KEY before any bytes leave the device.
 */
internal fun requireHeaderSafeKey(providerId: String, apiKey: String): String {
    val usable = apiKey.isNotEmpty() && apiKey.all { it == '\t' || (it >= ' ' && it <= '~') }
    if (!usable) {
        throw AsomException(
            AsomErrorCode.NO_PROVIDER_KEY,
            "the stored key for provider '$providerId' is not a usable HTTP header value; re-enter it in the Keys tab",
        )
    }
    return apiKey
}
