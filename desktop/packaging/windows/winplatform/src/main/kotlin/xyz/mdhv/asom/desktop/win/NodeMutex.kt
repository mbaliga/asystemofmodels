package xyz.mdhv.asom.desktop.win

import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.win.api.MutexPort
import xyz.mdhv.asom.desktop.win.api.MutexResult

/**
 * `Global\asom-node`: exactly one node identity per machine (windows.md 3.2). Whichever of user mode and service mode
 * starts second refuses to start and says why. An existing mutex that this process may not open counts as held (the
 * other mode runs as another account), never as free.
 */
class NodeMutex(private val port: MutexPort) {
    /** Returns the held mutex (close it to release), or throws [HostRefusedException]. */
    fun acquire(): AutoCloseable = when (val r = port.tryAcquire(NAME)) {
        is MutexResult.Acquired -> r.handle
        MutexResult.HeldByOther -> throw HostRefusedException(
            "another asom node already runs on this machine (user mode and service mode share one identity; mutex $NAME)",
        )
        MutexResult.AccessDenied -> throw HostRefusedException(
            "another asom node already runs on this machine under a different account (mutex $NAME exists and cannot be opened)",
        )
        is MutexResult.Failed -> throw HostRefusedException("cannot create mutex $NAME (Windows error ${r.status ?: "unknown"}); refusing to start without the single-identity guard")
    }

    companion object {
        const val NAME = "Global\\asom-node"
    }
}
