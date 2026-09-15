package xyz.mdhv.asom.server.ledger

import java.util.concurrent.CopyOnWriteArrayList

/**
 * One opt-in verbose-mode capture (§9): the request body asom put on the wire
 * and the response body it handed back.
 *
 * LOCAL STORAGE ONLY. Verbose rows are never part of the §9 export payload and
 * v1 has no upload path at all (§1.1). They carry bodies, never headers — the
 * drivers attach BYOK keys as HTTP headers, which this seam cannot see (§1.4).
 */
data class BodyRecord(
    val ts: Long,
    val callerPkg: String,
    val requestBody: String,
    val responseBody: String,
)

/**
 * Write-side seam for §9 verbose mode, alongside the metadata [LedgerSink].
 * The default is [Disabled]: metadata is the only thing v1 records unless the
 * user opts in. [isCapturing] is re-read per request so revoking the opt-in
 * takes effect on the next request rather than the next restart.
 */
interface BodySink {
    fun isCapturing(): Boolean

    suspend fun capture(record: BodyRecord)

    companion object {
        /** Default: verbose mode OFF, nothing captured. */
        val Disabled: BodySink = object : BodySink {
            override fun isCapturing(): Boolean = false
            override suspend fun capture(record: BodyRecord) = Unit
        }

        /** Per-field ceiling on a stored body — verbose mode is bounded, not a tap. */
        const val MAX_BODY_CHARS: Int = 16 * 1024
    }
}

/** Desktop/test sink: in-memory, inspectable. Never leaves the process. */
class InMemoryBodySink(@Volatile var capturing: Boolean = false) : BodySink {
    private val rows = CopyOnWriteArrayList<BodyRecord>()

    override fun isCapturing(): Boolean = capturing

    override suspend fun capture(record: BodyRecord) {
        rows.add(record)
    }

    fun all(): List<BodyRecord> = rows.toList()

    fun clear() = rows.clear()
}

/**
 * §8's redaction law applied to the one path in v1 that stores raw bodies.
 *
 * Bodies cannot carry a BYOK key by construction — both drivers attach the key
 * as a header (`Authorization` / `x-api-key`) and this path is handed bodies
 * only — but an upstream *error* body can echo back the credential it was sent,
 * so the served provider's exact key is struck out first, then anything else
 * key-shaped.
 */
object VerboseRedactor {
    const val REDACTED: String = "[REDACTED]"

    private val SECRET_FIELD = Regex(
        """(?i)"(api[_-]?key|authorization|x-api-key|access[_-]?token|secret|password)"\s*:\s*"(?:\\.|[^"\\])*"""",
    )
    private val BEARER = Regex("""(?i)bearer\s+[A-Za-z0-9._\-]{8,}""")
    private val KEY_SHAPED = Regex("""(?i)\b(?:sk|pk|rk)-[A-Za-z0-9_\-]{8,}""")

    /** [secrets] are the live key values for the provider this exchange touched. */
    fun redact(text: String, secrets: Collection<String>): String {
        var out = text
        for (secret in secrets) {
            if (secret.isNotEmpty()) out = out.replace(secret, REDACTED)
        }
        out = SECRET_FIELD.replace(out) { m -> "\"${m.groupValues[1]}\":\"$REDACTED\"" }
        out = BEARER.replace(out, REDACTED)
        return KEY_SHAPED.replace(out, REDACTED)
    }

    /** Caps one stored field at [BodySink.MAX_BODY_CHARS], marking the cut. */
    fun bound(text: String): String {
        if (text.length <= BodySink.MAX_BODY_CHARS) return text
        val dropped = text.length - BodySink.MAX_BODY_CHARS
        return text.take(BodySink.MAX_BODY_CHARS) + "…[truncated $dropped chars]"
    }
}
