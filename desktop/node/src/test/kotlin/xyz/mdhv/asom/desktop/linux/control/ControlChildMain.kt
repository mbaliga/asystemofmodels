package xyz.mdhv.asom.desktop.linux.control

import java.nio.file.Path
import xyz.mdhv.asom.desktop.control.ControlCommand
import xyz.mdhv.asom.desktop.control.ControlRequest

/**
 * Forked by ControlSocketTest as ANOTHER uid (through setpriv) to prove that SO_PEERCRED sees a different user, not the
 * test JVM's own. Prints one line: `RESULT ok=<bool> code=<code|-> message=<text|-> result=<json|->`.
 * args: socket path, expected server user.
 */
fun main(args: Array<String>) {
    val client = ControlClient(Path.of(args[0]), args[1], timeoutMs = 5_000)
    val line = try {
        val r = client.call(ControlRequest(7, ControlCommand.STATUS))
        "RESULT ok=${r.ok} code=${r.code ?: "-"} message=${r.message ?: "-"} result=${r.result ?: "-"}"
    } catch (e: Exception) {
        "RESULT error=${e.javaClass.simpleName}: ${e.message}"
    }
    println(line)
}
