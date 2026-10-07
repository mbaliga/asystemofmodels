package xyz.mdhv.asom.desktop.win.jna

import com.sun.jna.win32.StdCallLibrary

internal interface WerApi : StdCallLibrary {
    fun WerAddExcludedApplication(exeName: String, allUsers: Boolean): Int
    fun WerRemoveExcludedApplication(exeName: String, allUsers: Boolean): Int
}

/**
 * Windows Error Reporting exclusion (windows.md 3.3(b), [FW34]). The MSI writes the machine-wide list in HKLM; this exists
 * for the tests, which use the per-user list (`allUsers = false`, HKCU, no elevation) with a made-up executable name. The
 * node itself never calls it, and never excludes `java.exe`.
 */
object JnaWer {
    /** Returns the HRESULT (0 = S_OK). */
    fun add(exeName: String): Int {
        require(!exeName.equals("java.exe", ignoreCase = true) && !exeName.equals("javaw.exe", ignoreCase = true)) { "never exclude the JVM" }
        return Libs.wer.WerAddExcludedApplication(exeName, false)
    }

    fun remove(exeName: String): Int = Libs.wer.WerRemoveExcludedApplication(exeName, false)
}
