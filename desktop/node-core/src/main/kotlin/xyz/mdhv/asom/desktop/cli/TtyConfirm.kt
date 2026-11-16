package xyz.mdhv.asom.desktop.cli

import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path

/** A handle on the controlling terminal: a prompt goes out on it and the answer comes back from it, never via stdin. */
interface TtyIo : AutoCloseable {
    fun println(text: String)
    fun readLine(): String?
}

fun interface TtyOpener {
    /** Null when there is no controlling terminal. */
    fun open(): TtyIo?
}

sealed interface Confirmation {
    data object Confirmed : Confirmation
    data object Declined : Confirmation

    /** No controlling terminal: the confirmation is refused, not guessed (T17(e)). */
    data object NoTty : Confirmation
}

/**
 * Confirmations for `pair-confirm`, `restore` and `lan-confirm` come from the controlling TTY only (T17(e)): never
 * from stdin, arguments, environment or the control socket, so a non-interactive script cannot supply them. A same-uid
 * process that allocates a pseudo-terminal of its own CAN (desktop/packaging/linux/test/pty-run.py does exactly that):
 * the prompt stops scripts and mistakes, not an attacker who already runs as the owner (T17(g)).
 * Without a TTY the answer is [Confirmation.NoTty]. The answer must equal [expected] exactly (default: `yes`).
 */
class TtyConfirm(private val opener: TtyOpener = DefaultTtyOpener) {
    fun confirm(prompt: String, expected: String = "yes"): Confirmation {
        val tty = opener.open() ?: return Confirmation.NoTty
        tty.use {
            it.println(prompt)
            it.println("Type \"$expected\" and press Enter to confirm; anything else cancels:")
            val answer = it.readLine() ?: return Confirmation.Declined
            return if (answer.trim() == expected) Confirmation.Confirmed else Confirmation.Declined
        }
    }
}

/**
 * Whether the JVM console may stand in for a missing `/dev/tty`. Only on Windows, which has no `/dev/tty`: on POSIX a
 * missing `/dev/tty` means no controlling terminal, and the answer is NoTty. The JVM console is not a terminal check by
 * itself: with `-Djdk.console=jdk.internal.le` (the default provider from JDK 22) `System.console()` is non-null over a
 * pipe and reads file descriptor 0. So [isTerminal] (`Console.isTerminal()`, JDK 22 and later) decides when it exists; on
 * JDK 17 and 21 the default provider returns a console only when stdin and stdout are both terminals, which holds only while
 * no other provider is selected through `jdk.console`.
 */
object ConsoleFallback {
    fun allowed(osName: String, consoleProvider: String?, consolePresent: Boolean, isTerminal: Boolean?): Boolean {
        if (!osName.startsWith("Windows") || !consolePresent) return false
        if (isTerminal != null) return isTerminal
        return consoleProvider.isNullOrEmpty() || consoleProvider == "java.base"
    }

    internal fun isTerminal(console: java.io.Console): Boolean? = try {
        java.io.Console::class.java.getMethod("isTerminal").invoke(console) as? Boolean
    } catch (_: ReflectiveOperationException) {
        null
    }
}

/**
 * `/dev/tty` where the platform has one (POSIX: Linux, macOS, Ubuntu Touch); on Windows the JVM console when
 * [ConsoleFallback] allows it. Otherwise there is no terminal and the confirmation is refused. stdin is never consulted.
 */
object DefaultTtyOpener : TtyOpener {
    override fun open(): TtyIo? {
        val dev = Path.of("/dev/tty")
        if (Files.exists(dev)) {
            try {
                val reader = BufferedReader(InputStreamReader(FileInputStream(dev.toFile()), Charsets.UTF_8))
                val writer = PrintWriter(OutputStreamWriter(FileOutputStream(dev.toFile()), Charsets.UTF_8), true)
                return object : TtyIo {
                    override fun println(text: String) = writer.println(text)
                    override fun readLine(): String? = reader.readLine()
                    override fun close() {
                        reader.close()
                        writer.close()
                    }
                }
            } catch (_: java.io.IOException) {
                // no controlling terminal (ENXIO)
            }
        }
        val console = System.console()
        val ok = ConsoleFallback.allowed(
            System.getProperty("os.name").orEmpty(), System.getProperty("jdk.console"), console != null,
            console?.let { ConsoleFallback.isTerminal(it) },
        )
        if (!ok || console == null) return null
        return object : TtyIo {
            override fun println(text: String) {
                console.writer().println(text)
                console.writer().flush()
            }

            override fun readLine(): String? = console.readLine()
            override fun close() {}
        }
    }
}
