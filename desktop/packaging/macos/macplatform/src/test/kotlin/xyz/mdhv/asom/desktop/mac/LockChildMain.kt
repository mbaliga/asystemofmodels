package xyz.mdhv.asom.desktop.mac

import java.nio.file.Path
import kotlin.system.exitProcess

/** A second process that tries the lock once and reports: `ACQUIRED`, `HELD_BY_OTHER` or `UNAVAILABLE`. */
fun main(args: Array<String>) {
    val r = NodeLock(Path.of(args[0])).tryAcquire()
    println(
        when (r) {
            is NodeLock.Result.Acquired -> { r.handle.close(); "ACQUIRED" }
            NodeLock.Result.HeldByOther -> "HELD_BY_OTHER"
            is NodeLock.Result.Unavailable -> "UNAVAILABLE"
        },
    )
    exitProcess(0)
}
