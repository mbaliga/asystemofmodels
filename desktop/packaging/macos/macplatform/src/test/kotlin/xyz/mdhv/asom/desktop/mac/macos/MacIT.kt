package xyz.mdhv.asom.desktop.mac.macos

import java.io.File
import java.nio.file.Path

/**
 * Shared by the macOS-only integration tests. On any other system these classes are SKIPPED (`@EnabledOnOs(OS.MAC)`), and the Gradle
 * summary prints how many were skipped. On a Mac the real helper must already be built (`swift build -c release
 * --package-path desktop/packaging/macos/helper`, which CI does first); a missing helper FAILS the test, it does not skip it,
 * because a silent skip on the one machine that can run these tests would hide exactly what they exist to show.
 */
object MacIT {
    fun helperPath(): Path {
        val p = System.getProperty("asom.macHelper") ?: error("asom.macHelper is not set")
        val f = File(p)
        check(f.isFile && f.canExecute()) {
            "the real helper is not built at $p. Run: swift build -c release --package-path desktop/packaging/macos/helper (or set ASOM_MAC_HELPER)"
        }
        return f.toPath()
    }
}
