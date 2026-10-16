package xyz.mdhv.asom.discovery

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.serialization.json.Json
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.contract.Capabilities

/**
 * Discovery ContentProvider (§5.1): authority `xyz.mdhv.asom.discovery`,
 * no permission required — discovery only, NO secrets. Single row:
 * port, version, capabilities JSON.
 */
class DiscoveryProvider : ContentProvider() {

    private val json = Json { encodeDefaults = true }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val catalogueVersion = try {
            ServiceLocator.catalogue.version
        } catch (e: Exception) {
            0
        }
        val capabilities = json.encodeToString(
            Capabilities.serializer(),
            Capabilities.v1(catalogueVersion),
        )
        return MatrixCursor(arrayOf("port", "version", "capabilities")).apply {
            addRow(arrayOf(Asom.DEFAULT_PORT, Asom.VERSION, capabilities))
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.asom.discovery"

    // Discovery is read-only.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
