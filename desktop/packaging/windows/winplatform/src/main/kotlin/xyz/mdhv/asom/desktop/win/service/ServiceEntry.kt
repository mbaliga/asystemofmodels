// The file lives under service/ as the plan's tree says, but the class keeps the package windows.md 3.2 gives the procrun
// `StartClass`/`StopClass` (`xyz.mdhv.asom.desktop.win.ServiceEntry`), so the installer track can copy that text verbatim.
package xyz.mdhv.asom.desktop.win

import xyz.mdhv.asom.desktop.win.jna.Kernel32Mutex
import xyz.mdhv.asom.desktop.win.service.ServiceHost

/** The procrun `StartMode=jvm` entry points (`StartMethod=start`, `StopMethod=stop`). See [ServiceHost] for the behaviour. */
object ServiceEntry {
    @Volatile
    private var host: ServiceHost? = null

    @JvmStatic
    fun start(args: Array<String>) {
        val h = ServiceHost(WinPlatform(), NodeMutex(Kernel32Mutex()))
        host = h
        h.start()
    }

    @JvmStatic
    fun stop(args: Array<String>) {
        host?.stop()
    }
}
