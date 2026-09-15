package xyz.mdhv.asom.pairing

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
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
            val client = BinderClient(callback)
            val verified = caller()
            if (verified == null) {
                registry.refuseUnverified(client)
                return
            }
            registry.requestPairing(verified, client)
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

/**
 * The AIDL half of [PendingClient]: the caller's one-way callback proxy plus
 * the death registration that keeps it from outliving the client process.
 */
private class BinderClient(private val callback: IAsomPairingCallback?) : PendingClient {

    private var recipient: IBinder.DeathRecipient? = null

    override fun deliver(status: Int, token: String?): Boolean {
        val cb = callback ?: return false
        return try {
            cb.onDecision(status, token)
            true
        } catch (e: RemoteException) {
            // Client went away; it will poll getStatus()/getToken().
            false
        }
    }

    override fun linkToDeath(onDeath: () -> Unit): Boolean {
        val binder = callback?.asBinder() ?: return false
        val r = object : IBinder.DeathRecipient {
            override fun binderDied() {
                onDeath()
            }
        }
        return try {
            binder.linkToDeath(r, 0)
            recipient = r
            true
        } catch (e: RemoteException) {
            false
        }
    }

    override fun unlink() {
        val r = recipient ?: return
        recipient = null
        runCatching { callback?.asBinder()?.unlinkToDeath(r, 0) }
    }
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
