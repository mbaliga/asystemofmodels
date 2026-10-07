package xyz.mdhv.asom.desktop.mac.macos

import java.nio.file.Path
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.HelperProcess
import xyz.mdhv.asom.desktop.mac.helper.ProcessHelperLauncher

/**
 * The "node" of PowerAssertionIT, as a process the test can kill with SIGKILL: it starts the real helper, takes the assertion, says
 * `held`, and then waits until it is killed. Whether the assertion goes away with it is AM11.
 */
fun main(args: Array<String>) {
    val p = HelperProcess(ProcessHelperLauncher(Path.of(args[0])))
    HelperClient(p).assertHold()
    println("held")
    System.out.flush()
    Thread.sleep(Long.MAX_VALUE)
}
