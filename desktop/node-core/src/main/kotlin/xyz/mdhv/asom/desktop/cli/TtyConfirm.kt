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
 * from stdin, arguments, environment or the control socket, so a script or a same-uid process cannot supply them.
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
 * `/dev/tty` where the platform has one (POSIX: Linux, macOS), else the JVM console when both stdin and stdout are a
 * terminal. Windows hosts may replace this opener with a console-only one.
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
                // no controlling terminal (ENXIO): fall through to the console check
            }
        }
        val console = System.console() ?: return null
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
