package xyz.mdhv.asom.desktop.cli

/** Runs the real [DefaultTtyOpener] in its own JVM; the parent decides what stdin and the controlling terminal look like. */
object TtyChildMain {
    @JvmStatic
    fun main(args: Array<String>) {
        println("RESULT " + TtyConfirm().confirm("Restore identity?"))
    }
}
