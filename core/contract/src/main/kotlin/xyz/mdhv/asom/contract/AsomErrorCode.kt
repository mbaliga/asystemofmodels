package xyz.mdhv.asom.contract

/**
 * Typed error codes of the public contract (brief §5.6). Serialized into the
 * OpenAI error envelope's `code` field. FROZEN — no additions without owner
 * sign-off.
 */
enum class AsomErrorCode(val httpStatus: Int, val openAiType: String) {
    /** Caller has never paired (no bearer token / unknown token). */
    NOT_PAIRED(401, "authentication_error"),

    /** Caller's pairing token was revoked from the Hotspot tab. */
    TOKEN_REVOKED(401, "authentication_error"),

    /** No stored user key for any provider that could serve the request. */
    NO_PROVIDER_KEY(503, "server_error"),

    /** The requested concrete model id is not in the catalogue. */
    MODEL_UNKNOWN(404, "invalid_request_error"),

    /** Every candidate provider is in circuit-breaker cooldown. */
    ALL_PROVIDERS_COOLING(503, "server_error"),

    /** v1 ships no local engine; `local-only` always fails with this (brief §2). */
    LOCAL_ENGINE_ABSENT(501, "server_error"),

    /** Driver cannot translate a body field (e.g. `tools` on the Anthropic driver, §5.9). */
    UNSUPPORTED_BY_DRIVER(501, "invalid_request_error"),
}

/** Exception carrying a typed contract error through server/router layers. */
class AsomException(
    val code: AsomErrorCode,
    override val message: String,
) : Exception(message)
