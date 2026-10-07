package xyz.mdhv.asom.client

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * §10A.4 sibling detection — backend-free, channel-robust. First-party apps
 * OPTIONALLY register this provider (authority `<pkg>.asom.inventory`) so
 * siblings can compute suite size and reclaimable storage. Model inventory
 * is not sensitive → no permission. This is NOT a security boundary; asom's
 * AIDL cert verification (§5.7) remains the only trust gate.
 */
class InventoryProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val inventory = InventoryRegistry.current()
        return MatrixCursor(arrayOf("modelIds", "bytesHeld", "appLabel")).apply {
            addRow(
                arrayOf(
                    inventory.modelIds.joinToString(","),
                    inventory.bytesHeld,
                    inventory.appLabel,
                ),
            )
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.asom.inventory"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

data class AppInventory(
    val modelIds: List<String> = emptyList(),
    val bytesHeld: Long = 0,
    val appLabel: String = "",
)

/** The hosting app publishes its inventory here (in-process, no I/O). */
object InventoryRegistry {
    @Volatile
    private var inventory = AppInventory()

    fun set(value: AppInventory) {
        inventory = value
    }

    fun current(): AppInventory = inventory
}

/** Sums sibling inventories across a configured suite package list. */
object SuiteInventory {

    data class Sibling(val packageName: String, val inventory: AppInventory)

    data class Summary(val siblings: List<Sibling>) {
        val suiteAppCount: Int get() = siblings.size
        val reclaimableBytes: Long get() = siblings.sumOf { it.inventory.bytesHeld }
        val appLabels: List<String> get() = siblings.map { it.inventory.appLabel.ifEmpty { it.packageName } }
    }

    /**
     * Queries `content://<pkg>.asom.inventory` for each package in
     * [suitePackages]. Apps without the provider simply don't contribute
     * (graceful). The list is OWNER-FILL config — empty disables detection
     * (and therefore §10A.5 multi-app nudges).
     */
    fun query(context: Context, suitePackages: List<String>): Summary {
        val siblings = suitePackages.mapNotNull { pkg ->
            if (pkg == context.packageName) return@mapNotNull null
            try {
                context.contentResolver.query(
                    Uri.parse("content://$pkg.asom.inventory"), null, null, null, null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@mapNotNull null
                    Sibling(
                        packageName = pkg,
                        inventory = AppInventory(
                            modelIds = cursor.getString(cursor.getColumnIndexOrThrow("modelIds"))
                                .split(',').filter { it.isNotBlank() },
                            bytesHeld = cursor.getLong(cursor.getColumnIndexOrThrow("bytesHeld")),
                            appLabel = cursor.getString(cursor.getColumnIndexOrThrow("appLabel")),
                        ),
                    )
                }
            } catch (e: Exception) {
                null // not installed / no provider — graceful
            }
        }
        return Summary(siblings)
    }
}
