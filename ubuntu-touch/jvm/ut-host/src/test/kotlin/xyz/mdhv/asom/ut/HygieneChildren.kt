package xyz.mdhv.asom.ut

import kotlin.system.exitProcess

/**
 * Child JVM entry points for UTC05. `StrayPrintChild` takes the channel over exactly as `main` does and then does everything
 * a careless library could do: print to System.out and System.err, print a stack trace, call the JVM's own diagnostics.
 * `CrashChild` throws an exception whose message is a secret, from a thread other than main.
 */
object StrayPrintChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val io = Stdio.takeOver()
        println("SECRET-STRAY-STDOUT")
        System.out.println("SECRET-STRAY-STDOUT-2")
        System.err.println("SECRET-STRAY-STDERR")
        RuntimeException("SECRET-IN-TRACE").printStackTrace()
        print("SECRET-NO-NEWLINE")
        FrameWriter(io.output).write(NodeFrame.Sas("123 456"))
        exitProcess(0)
    }
}

object CrashChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val io = Stdio.takeOver()
        CrashGuard.install(io.diag)
        val t = Thread { throw IllegalStateException("SECRET-IN-EXCEPTION-MESSAGE") }
        t.start()
        t.join()
        Thread.sleep(10_000)
        exitProcess(0)
    }
}
