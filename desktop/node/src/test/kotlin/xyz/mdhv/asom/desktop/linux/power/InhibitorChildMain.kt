package xyz.mdhv.asom.desktop.linux.power

import xyz.mdhv.asom.desktop.LockKind

/** Forked by InhibitorTest: takes a delay lock through the given (fake) systemd-inhibit, prints the holder's pid and waits to be killed. */
fun main(args: Array<String>) {
    val hold = Inhibitor(blockLockAllowed = true, inhibitCommand = listOf(args[0])).hold(LockKind.DELAY)
    println("pid=${hold.pid}")
    System.out.flush()
    Thread.sleep(600_000)
}
