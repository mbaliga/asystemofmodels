package xyz.mdhv.asom.client

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.contract.PairingStatusCode
import xyz.mdhv.asom.ipc.IAsomPairing
import xyz.mdhv.asom.ipc.IAsomPairingCallback

enum class PairingStatus { NOT_INSTALLED, NOT_PAIRED, PENDING, PAIRED, REVOKED }

/**
 * §5.7 pairing flow — pinned surface in docs/CLIENT_API.md.
 *
 * Landmine handled here: the daemon (a background bound service) cannot
 * launch activities, so THIS SDK launches asom's consent activity from the
 * client app's foreground context after registering the request over AIDL.
 * Identity always comes from Binder.getCallingUid() daemon-side.
 */
class AsomPairing(private val context: Context) {

    private val prefs = context.getSharedPreferences("asom-client", Context.MODE_PRIVATE)

    fun token(): String? = prefs.getString(PREF_TOKEN, null)

    fun forget() {
        prefs.edit().remove(PREF_TOKEN).apply()
    }

    suspend fun status(): PairingStatus = withBinder { binder ->
        when (binder.status) {
            PairingStatusCode.PAIRED ->
                if (token() != null) PairingStatus.PAIRED else PairingStatus.NOT_PAIRED
            PairingStatusCode.PENDING -> PairingStatus.PENDING
            PairingStatusCode.REVOKED -> PairingStatus.REVOKED
            else -> PairingStatus.NOT_PAIRED
        }
    } ?: PairingStatus.NOT_INSTALLED

    /** Runs the full consent flow; suspends until decided or dismissed. */
    suspend fun pair(activity: Activity): PairingStatus {
        val result = withBinder { binder ->
            suspendCancellableCoroutine<Int> { cont ->
                val callback = object : IAsomPairingCallback.Stub() {
                    override fun onDecision(status: Int, token: String?) {
                        if (token != null) {
                            prefs.edit().putString(PREF_TOKEN, token).apply()
                        }
                        if (cont.isActive) cont.resume(status)
                    }
                }
                binder.requestPairing(callback)
                // Launch the consent sheet from OUR foreground context (§5.7).
                val consent = Intent(ACTION_CONSENT).setPackage(daemonPackage())
                activity.startActivity(consent)
                cont.invokeOnCancellation { /* daemon keeps the request PENDING */ }
            }
        } ?: return PairingStatus.NOT_INSTALLED

        return when (result) {
            PairingStatusCode.PAIRED -> PairingStatus.PAIRED
            PairingStatusCode.PENDING -> PairingStatus.PENDING
            else -> PairingStatus.NOT_PAIRED
        }
    }

    /** One-shot fetch of an approved-but-undelivered token (recovery path). */
    suspend fun fetchToken(): String? = withBinder { binder ->
        binder.token?.also { prefs.edit().putString(PREF_TOKEN, it).apply() }
    }

    // ------------------------------------------------------------- plumbing

    private fun daemonPackage(): String? {
        val intent = Intent(Asom.PAIRING_ACTION)
        val services = context.packageManager.queryIntentServices(intent, 0)
        return services.firstOrNull()?.serviceInfo?.packageName
    }

    /** Binds, runs [block], unbinds. Null when asom is not installed. */
    private suspend fun <T> withBinder(block: suspend (IAsomPairing) -> T): T? {
        val pkg = daemonPackage() ?: return null
        val intent = Intent(Asom.PAIRING_ACTION).setPackage(pkg)
        var connection: ServiceConnection? = null
        return try {
            val binder = suspendCancellableCoroutine<IAsomPairing?> { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (cont.isActive) cont.resume(service?.let { IAsomPairing.Stub.asInterface(it) })
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                }
                connection = conn
                val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                if (!bound && cont.isActive) cont.resume(null)
                cont.invokeOnCancellation { runCatching { context.unbindService(conn) } }
            } ?: return null
            block(binder)
        } finally {
            connection?.let { runCatching { context.unbindService(it) } }
        }
    }

    companion object {
        private const val PREF_TOKEN = "asom_token"

        /** Consent activity action (part of the §5.7 flow). */
        const val ACTION_CONSENT = "xyz.mdhv.asom.PAIR_CONSENT"
    }
}
