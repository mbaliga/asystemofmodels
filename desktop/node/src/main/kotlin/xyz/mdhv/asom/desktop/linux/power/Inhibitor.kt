package xyz.mdhv.asom.desktop.linux.power

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import xyz.mdhv.asom.desktop.KeepAwakeHold
import xyz.mdhv.asom.desktop.LockKind

enum class HoldState { HELD, REFUSED, RELEASED, LOST }

/**
 * A keep-awake lock, or the record of why there is none. A refused lock is a STATE (linux.md 3.3, 7.3), never an
 * exception and never a retry loop: the node serves without the lock and `asom status` says why.
 */
class InhibitHold internal constructor(
    override val kind: LockKind,
    initial: HoldState,
    val detail: String,
    private val process: Process?,
) : KeepAwakeHold {
    @Volatile
    var state: HoldState = initial
        internal set

    /** The pid of the process that holds the lock (tests only), or null when there is none. */
    internal val pid: Long? get() = process?.pid()

    /** The line `asom status` shows for this lock. */
    val statusText: String
        get() = when {
            state == HoldState.HELD && kind == LockKind.BLOCK -> "block-lock held"
            state == HoldState.HELD -> "delay-lock held"
            state == HoldState.REFUSED && kind == LockKind.BLOCK && POLKIT_WORDS.containsMatchIn(detail) -> "keep-awake: unavailable (polkit)"
            state == HoldState.REFUSED && kind == LockKind.BLOCK -> "keep-awake: unavailable ($detail)"
            state == HoldState.REFUSED -> "delay lock unavailable ($detail)"
            state == HoldState.LOST -> "${kind.name.lowercase()}-lock lost ($detail)"
            else -> "no lock"
        }

    @Synchronized
    override fun release() {
        val p = process
        val was = state
        if (was == HoldState.HELD || was == HoldState.LOST) state = HoldState.RELEASED
        if (p == null) return
        runCatching { p.outputStream.close() }
        p.destroy()
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            p.waitFor(2, TimeUnit.SECONDS)
        }
    }

    companion object {
        private val POLKIT_WORDS = Regex("access denied|not authorized|authentication|polkit", RegexOption.IGNORE_CASE)
    }
}

/**
 * Keep-awake locks through `systemd-inhibit` child processes (linux.md 3.3, 7.3). Pure JDK code cannot hold a logind
 * lock itself (`Inhibit()` returns a file descriptor and JDK Unix sockets cannot receive one, LF25), so the lock lives as
 * long as a child process does.
 *
 *  - A DELAY `sleep` lock is allowed for every subject and is taken whenever the node is SERVING.
 *  - A BLOCK lock is taken only when [blockLockAllowed] (false on SteamOS whatever the configuration says, ERR-DECK-2)
 *    AND polkit lets the process take it; a refusal is recorded, not retried.
 *  - `idle` locks are never taken, so screen blanking and locking are unaffected.
 *  - The child is `sh -c 'echo asom-lock-ready; exec cat >/dev/null'` on a stdin pipe, not `sleep infinity`: when the JVM
 *    dies, even by SIGKILL, the pipe closes, `cat` exits and the lock is released instead of leaking (ERR-DL2-6). The
 *    ready line is printed only after `systemd-inhibit` has the lock, because it execs the command after `Inhibit()`.
 *  - The child has no TTY (LA24): stdin is a pipe, stdout and stderr are pipes.
 */
class Inhibitor(
    private val blockLockAllowed: Boolean,
    private val inhibitCommand: List<String> = listOf("systemd-inhibit"),
    private val launcher: (List<String>) -> Process = { ProcessBuilder(it).start() },
    private val readyTimeoutMs: Long = 5_000,
    private val why: String = "asom is lending compute to your paired devices",
) {
    fun hold(kind: LockKind): InhibitHold {
        if (kind == LockKind.BLOCK && !blockLockAllowed) {
            return InhibitHold(kind, HoldState.REFUSED, "policy: a block lock is never taken on this host", null)
        }
        val command = inhibitCommand + listOf(
            "--what=sleep", "--mode=${if (kind == LockKind.BLOCK) "block" else "delay"}", "--who=asom", "--why=$why",
            "/bin/sh", "-c", "echo $READY; exec cat >/dev/null",
        )
        val p = try {
            launcher(command)
        } catch (e: Exception) {
            return InhibitHold(kind, HoldState.REFUSED, "cannot run ${inhibitCommand.first()}: ${e.javaClass.simpleName}", null)
        }

        val stderrFirstLine = CompletableFuture<String>()
        Thread({
            val line = runCatching { BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).use { r -> r.readLine().orEmpty() } }.getOrDefault("")
            stderrFirstLine.complete(line.take(160))
        }, "asom-inhibit-stderr").apply { isDaemon = true }.start()

        val ready = CompletableFuture<String?>()
        Thread({
            val line = runCatching { BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).readLine() }.getOrNull()
            ready.complete(line)
        }, "asom-inhibit-stdout").apply { isDaemon = true }.start()

        val line = try {
            ready.get(readyTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            p.destroyForcibly()
            return InhibitHold(kind, HoldState.REFUSED, "timed out waiting for the lock", null)
        }
        if (line == READY) {
            val hold = InhibitHold(kind, HoldState.HELD, "held", p)
            p.onExit().thenRun { if (hold.state == HoldState.HELD) hold.state = HoldState.LOST }
            return hold
        }
        p.waitFor(2, TimeUnit.SECONDS)
        val reason = runCatching { stderrFirstLine.get(500, TimeUnit.MILLISECONDS) }.getOrDefault("")
        val code = if (p.isAlive) "still running" else "exit ${p.exitValue()}"
        p.destroyForcibly()
        return InhibitHold(kind, HoldState.REFUSED, if (reason.isNotEmpty()) "$code: $reason" else code, null)
    }

    companion object {
        const val READY = "asom-lock-ready"
    }
}
