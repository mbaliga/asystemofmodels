package xyz.mdhv.asom.client

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
            val decision = CompletableDeferred<Int>()
            val callback = object : IAsomPairingCallback.Stub() {
                override fun onDecision(status: Int, token: String?) {
                    if (token != null) {
                        prefs.edit().putString(PREF_TOKEN, token).apply()
                    }
                    decision.complete(status)
                }
            }
            binder.requestPairing(callback)
            // Launch the consent sheet from OUR foreground context (§5.7).
            val consent = Intent(ACTION_CONSENT).setPackage(daemonPackage() ?: return@withBinder null)
            try {
                activity.startActivity(consent)
            } catch (e: ActivityNotFoundException) {
                return@withBinder null
            }
            awaitDecision(activity, binder, decision)
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

    /**
     * "Decide later" and a back-press finish the consent sheet leaving the row
     * PENDING, so no decision ever reaches the callback. Waiting on the callback
     * alone would park this coroutine — and with it [withBinder]'s unbind —
     * forever, so our own activity coming back to the foreground is treated as
     * an answer too and the daemon's authoritative status is re-read.
     */
    private suspend fun awaitDecision(
        activity: Activity,
        binder: IAsomPairing,
        decision: CompletableDeferred<Int>,
    ): Int = coroutineScope {
        val returned = CompletableDeferred<Unit>()
        val watcher = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                if (a === activity) returned.complete(Unit)
            }

            override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        activity.application.registerActivityLifecycleCallbacks(watcher)
        val onReturn = launch {
            returned.await()
            val status = binder.status
            // The sheet may have approved and raced us; recover the token the
            // callback would have delivered (§5.7 one-shot getToken()).
            if (status == PairingStatusCode.PAIRED && token() == null) {
                binder.token?.let { prefs.edit().putString(PREF_TOKEN, it).apply() }
            }
            decision.complete(status)
        }
        try {
            decision.await()
        } finally {
            onReturn.cancel()
            activity.application.unregisterActivityLifecycleCallbacks(watcher)
        }
    }

    /**
     * Pinned to the daemon's package: the pairing action is public, so any
     * installed app could publish a matching service (and consent activity) and
     * be bound here. §5.7 verification is one-directional by design — the daemon
     * checks the caller's uid — which leaves this pin as the client's only guard.
     * Signing certs are deliberately not checked: §10A.4 records that first-party
     * cert matching is unreliable across Play/F-Droid/direct-APK channels.
     */
    private fun daemonPackage(): String? =
        context.packageManager.queryIntentServices(Intent(Asom.PAIRING_ACTION), 0)
            .map { it.serviceInfo?.packageName }
            .firstOrNull { it == DAEMON_PACKAGE }

    /** Binds, runs [block], unbinds. Null when asom is not installed. */
    private suspend fun <T> withBinder(block: suspend (IAsomPairing) -> T): T? {
        val pkg = daemonPackage() ?: return null
        val intent = Intent(Asom.PAIRING_ACTION).setPackage(pkg)
        var connection: ServiceConnection? = null
        return try {
            // Bounded: bindService can return true and then never deliver
            // onServiceConnected, which would hang every SDK entry point.
            val binder = withTimeoutOrNull(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine<IAsomPairing?> { cont ->
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
                }
            } ?: return null
            block(binder)
        } finally {
            connection?.let { runCatching { context.unbindService(it) } }
        }
    }

    companion object {
        private const val PREF_TOKEN = "asom_token"

        private const val DAEMON_PACKAGE = "xyz.mdhv.asom"

        private const val BIND_TIMEOUT_MS = 10_000L

        /** Consent activity action (part of the §5.7 flow). */
        const val ACTION_CONSENT = "xyz.mdhv.asom.PAIR_CONSENT"
    }
}
