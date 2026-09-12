package xyz.mdhv.asom.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.MODE_READ_ONLY
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.pairing.CallerVerifier
import xyz.mdhv.asom.pairing.PairingRegistryHolder

/**
 * Model file sharing (§5.8): authority `xyz.mdhv.asom.models`, `openFile`
 * mode "r" ONLY. Caller UID must map to an active pairing — same identity
 * verification law as §5.7 (never trust the calling package string).
 * URIs: `content://xyz.mdhv.asom.models/models/{modelId}`.
 */
class ModelsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") return null // read-only, no exceptions (§5.8)

        val modelId = uri.pathSegments.getOrNull(1) ?: return null
        if (!callerIsPaired()) return null

        val file = ServiceLocator.modelDownloadManager.fileFor(modelId) ?: return null
        return ParcelFileDescriptor.open(file, MODE_READ_ONLY)
    }

    private fun callerIsPaired(): Boolean {
        val context = context ?: return false
        val pm = context.packageManager
        val uid = Binder.getCallingUid()
        // Self-calls (asom's own process) are always allowed.
        if (uid == android.os.Process.myUid()) return true
        val verified = CallerVerifier.verify(pm, uid) ?: return false
        val registry = PairingRegistryHolder.get(context)
        return registry.status(verified) == xyz.mdhv.asom.contract.PairingStatusCode.PAIRED
    }

    // No querying/listing — discovery of what's available is via /v1/models
    // and /admin/catalogue, not this provider (§5.8 is fd access only).
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
