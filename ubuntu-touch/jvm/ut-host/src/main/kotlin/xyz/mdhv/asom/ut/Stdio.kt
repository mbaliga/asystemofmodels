package xyz.mdhv.asom.ut

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream

/** The fixed things the node may say on stderr. Nothing else ever reaches it: no prompt text, token, key, row or path. */
enum class DiagCode(val text: String) {
    PROTOCOL_VIOLATION("asom-ut: protocol violation"),
    BAD_ARGUMENTS("asom-ut: bad arguments"),
    REFUSED("asom-ut: refused to start"),
    LEDGER_UNAVAILABLE("asom-ut: ledger unavailable"),
    INTERNAL("asom-ut: internal error"),
}

class Diag(private val out: OutputStream) {
    fun emit(code: DiagCode, reason: Enum<*>? = null) {
        val line = if (reason == null) code.text else "${code.text} ${reason.name}"
        try {
            out.write((line + "\n").toByteArray(Charsets.US_ASCII))
            out.flush()
        } catch (_: java.io.IOException) {
        }
    }
}

class ChannelStreams(val input: InputStream, val output: OutputStream, val diag: Diag)

/**
 * The only place that touches the process's standard streams. The child's stdout carries protocol frames and nothing else,
 * so the moment the channel is taken the JVM's own `System.out` and `System.err` are pointed at a sink: a stray print from
 * any library cannot corrupt a frame or leak a byte (UTC05).
 */
object Stdio {
    fun takeOver(): ChannelStreams {
        val input = FileInputStream(FileDescriptor.`in`)
        val output = FileOutputStream(FileDescriptor.out)
        val err = FileOutputStream(FileDescriptor.err)
        val sink = PrintStream(OutputStream.nullOutputStream(), true)
        System.setOut(sink)
        System.setErr(sink)
        return ChannelStreams(input, output, Diag(err))
    }

    /** For `--selftest`: stdout carries exactly one line; the JVM's own streams are pointed at the sink first. */
    fun takeOverForSelfTest(): Pair<PrintStream, Diag> {
        val out = PrintStream(FileOutputStream(FileDescriptor.out), false, Charsets.UTF_8)
        val err = FileOutputStream(FileDescriptor.err)
        val sink = PrintStream(OutputStream.nullOutputStream(), true)
        System.setOut(sink)
        System.setErr(sink)
        return out to Diag(err)
    }
}
