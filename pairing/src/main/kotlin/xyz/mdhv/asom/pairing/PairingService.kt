package xyz.mdhv.asom.pairing

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import xyz.mdhv.asom.contract.PairingStatusCode
import xyz.mdhv.asom.ipc.IAsomPairing
import xyz.mdhv.asom.ipc.IAsomPairingCallback

/**
 * The exported "AI hotspot" bound service, action `xyz.mdhv.asom.PAIR`
 * (§5.7). Identity is read from [Binder.getCallingUid] on EVERY call —
 * invariant §1.5. No HTTP registration endpoint exists.
 */
class PairingService : Service() {

    private val registry: PairingRegistry by lazy {
        PairingRegistryHolder.get(applicationContext)
    }

    private val binder = object : IAsomPairing.Stub() {

        private fun caller(): VerifiedCaller? =
            CallerVerifier.verify(packageManager, Binder.getCallingUid())

        override fun requestPairing(callback: IAsomPairingCallback?) {
            val verified = caller() ?: return
            registry.requestPairing(verified) { status, token ->
                try {
                    callback?.onDecision(status, token)
                } catch (e: android.os.RemoteException) {
                    // Client went away; it will poll getStatus()/getToken().
                }
            }
        }

        override fun getToken(): String? {
            val verified = caller() ?: return null
            return registry.takeToken(verified)
        }

        override fun getStatus(): Int {
            val verified = caller() ?: return PairingStatusCode.NOT_PAIRED
            return registry.status(verified)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

/** Process-wide registry so the AIDL service, HTTP auth, and UI share state. */
object PairingRegistryHolder {
    @Volatile
    private var registry: PairingRegistry? = null

    fun get(context: android.content.Context): PairingRegistry =
        registry ?: synchronized(this) {
            registry ?: PairingRegistry(PairingDatabase.open(context).dao())
                .also { registry = it }
        }
}
