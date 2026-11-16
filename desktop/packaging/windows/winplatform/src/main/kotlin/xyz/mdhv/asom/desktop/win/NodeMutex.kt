package xyz.mdhv.asom.desktop.win

import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.win.api.MutexPort
import xyz.mdhv.asom.desktop.win.api.MutexResult

/**
 * `Global\asom-node`: exactly one node identity per machine (windows.md 3.2). Whichever of user mode and service mode
 * starts second refuses to start and says why. An existing mutex that this process may not open counts as held (the
 * other mode runs as another account), never as free. The name is fixed and global, so any local account can create it first
 * and make both modes refuse to start (A-LA-6): the refusal stays, because the node cannot tell the holder from a squatter, and
 * the messages say so. Reading the owner of the object (GetSecurityInfo) to tell them apart is NEEDS-DEVICE-VALIDATION
 * (desktop/packaging/windows/ERRATA.md and desktop/ERRATA.md ERR-FX4-DK-6).
 */
class NodeMutex(private val port: MutexPort) {
    /** Returns the held mutex (close it to release), or throws [HostRefusedException]. */
    fun acquire(): AutoCloseable = when (val r = port.tryAcquire(NAME)) {
        is MutexResult.Acquired -> r.handle
        MutexResult.HeldByOther -> throw HostRefusedException(
            "another asom node already runs on this machine (user mode and service mode share one identity; mutex $NAME; the holder is not verified, so a local account that created the name first looks the same)",
        )
        MutexResult.AccessDenied -> throw HostRefusedException(
            "another asom node already runs on this machine under a different account, or a local account is squatting the name (mutex $NAME exists and cannot be opened; the two cannot be told apart)",
        )
        is MutexResult.Failed -> throw HostRefusedException("cannot create mutex $NAME (Windows error ${r.status ?: "unknown"}); refusing to start without the single-identity guard")
    }

    companion object {
        const val NAME = "Global\\asom-node"
    }
}
