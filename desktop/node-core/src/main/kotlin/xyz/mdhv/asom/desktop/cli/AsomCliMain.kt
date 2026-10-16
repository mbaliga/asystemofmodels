package xyz.mdhv.asom.desktop.cli

import kotlin.system.exitProcess
import xyz.mdhv.asom.desktop.HostFinder
import xyz.mdhv.asom.desktop.NodeEnv

/** Main class of the `asom` launcher script (the second start script of `:node`). */
fun main(args: Array<String>) {
    exitProcess(runAsomCli(args.toList(), NodeEnv.system(), HostFinder.find()))
}
