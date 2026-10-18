package xyz.mdhv.asom.desktop

import java.io.PrintStream

/** Everything the entry points read from the process, so tests can replace all of it. */
class NodeEnv(
    val userName: String,
    val vars: Map<String, String>,
    val out: PrintStream,
    val err: PrintStream,
) {
    companion object {
        fun system(): NodeEnv = NodeEnv(System.getProperty("user.name") ?: "", System.getenv(), System.out, System.err)
    }
}

object ExitCodes {
    const val OK = 0
    const val ERROR = 1
    const val USAGE = 2
    const val NOT_IMPLEMENTED = 3
    const val NO_HOST = 69
    const val REFUSED = 78
}

object NodeVersion {
    const val STRING = "0.0.0-scaffold"
    const val BANNER = "asom-node $STRING (desktop scaffold; UNSIGNED, not for release)"
}

/** The portable part of "refuse to run as root": the account name. The Linux host adds the real-uid check. */
object RootGuard {
    fun refusal(env: NodeEnv): String? =
        if (env.userName.equals("root", ignoreCase = true)) "refusing to run as root: use a normal user or the dedicated `asom` user" else null
}
