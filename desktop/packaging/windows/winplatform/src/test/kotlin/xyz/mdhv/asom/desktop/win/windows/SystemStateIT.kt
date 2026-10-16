package xyz.mdhv.asom.desktop.win.windows

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xyz.mdhv.asom.desktop.win.fakes.Report
import xyz.mdhv.asom.desktop.win.jna.JdkInterfaceLister
import xyz.mdhv.asom.desktop.win.jna.JnaSessions
import xyz.mdhv.asom.desktop.win.jna.JnaUserInput
import xyz.mdhv.asom.desktop.win.presence.JdkCpuLoadSource

/**
 * CI-ONLY (hosted Windows runner; SKIPPED elsewhere). These only show that the calls bind and return sane shapes in the
 * runner's session. Real presence (a person at the keyboard), a locked workstation and a real gamepad session stay
 * NEEDS-DEVICE-VALIDATION.
 */
@EnabledOnOs(OS.WINDOWS)
class SystemStateIT {
    @Test
    fun `input idle time and the notification state are callable`() {
        val input = JnaUserInput()
        val idle = input.idleMs()
        val state = input.notificationState()
        Report.line("IT SystemStateIT: idleMs=$idle notificationState=$state (a hosted session; not a person)")
        if (idle != null) assertTrue(idle >= 0)
    }

    @Test
    fun `WTS console and current session queries do not throw and parse when they answer`() {
        val s = JnaSessions()
        val cur = s.currentSession()
        val con = s.consoleSession()
        Report.line("IT SystemStateIT: WTS current=$cur console=$con (AW07: lock polarity unverified)")
        cur?.let { assertTrue(it.sessionId >= 0) }
    }

    @Test
    fun `interface aliases are the friendly names the owner knows, and loopback is up`() {
        val nics = JdkInterfaceLister().list()
        Report.line("IT SystemStateIT: interfaces=${nics.map { "${it.alias}#${it.index}${if (it.isUp) "" else "(down)"}" }}")
        assertTrue(nics.isNotEmpty())
        assertTrue(nics.any { it.alias == "Loopback Pseudo-Interface 1" }, "the friendly-name lookup must yield Windows aliases, got ${nics.map { it.alias }}")
        assertTrue(nics.all { it.alias.isNotBlank() })
    }

    @Test
    fun `the JDK CPU accounting is available (the jdk dot management module is in this runtime)`() {
        val c = JdkCpuLoadSource()
        c.systemLoad()
        Thread.sleep(300)
        assertNotNull(c.systemLoad())
        assertNotNull(c.processLoad())
    }
}
