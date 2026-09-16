package xyz.mdhv.asom.client

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.contract.Capabilities

data class AsomEndpoint(
    val port: Int,
    val version: String,
    val capabilities: Capabilities,
) {
    val baseUrl: String get() = "http://${Asom.BIND_HOST}:$port"
}

/** §5.1 discovery — see docs/CLIENT_API.md (pinned surface). */
object AsomDiscovery {

    private val json = Json { ignoreUnknownKeys = true }

    /** @return null when asom is not installed. */
    fun discover(context: Context): AsomEndpoint? {
        val uri = Uri.parse("content://${Asom.DISCOVERY_AUTHORITY}")
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val port = cursor.getInt(cursor.getColumnIndexOrThrow("port"))
                val version = cursor.getString(cursor.getColumnIndexOrThrow("version"))
                val capabilities = json.decodeFromString<Capabilities>(
                    cursor.getString(cursor.getColumnIndexOrThrow("capabilities")),
                )
                AsomEndpoint(port = port, version = version, capabilities = capabilities)
            }
        } catch (e: Exception) {
            // Provider present but unreadable — fall back to the documented default.
            null
        }
    }

    /** Documented fallback (§5.1): assume the default port. */
    fun defaultEndpoint(): AsomEndpoint = AsomEndpoint(
        port = Asom.DEFAULT_PORT,
        version = "unknown",
        capabilities = Capabilities.v1(catalogueVersion = 0),
    )
}
