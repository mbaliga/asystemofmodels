package xyz.mdhv.asom.desktop.mac

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import xyz.mdhv.asom.desktop.mac.helper.HelperClient

/** What the macOS host reads from the process. Replaced wholesale in tests. */
class MacEnv(
    val userName: String,
    val home: String,
    val vars: Map<String, String>,
    /** The per-user temporary directory (the one the owner CLI also sees as `$TMPDIR`), or null when it is unknown. */
    val tempDir: () -> String?,
    /** The numeric uid of this process, or null when it cannot be read. */
    val ownUid: () -> Int?,
) {
    companion object {
        /**
         * The real environment. The temporary directory comes from the helper's `paths.get` when a helper is given (macos.md 3.3 c:
         * the node's own `java.io.tmpdir` is pinned inside the container, so it cannot be used), and from `$TMPDIR` otherwise.
         */
        fun system(helper: HelperClient? = null): MacEnv {
            val vars = System.getenv()
            return MacEnv(
                userName = System.getProperty("user.name") ?: "",
                home = System.getProperty("user.home") ?: "",
                vars = vars,
                tempDir = { helper?.let { runCatching { it.userTempDir() }.getOrNull() } ?: vars["TMPDIR"] },
                ownUid = {
                    runCatching {
                        (Files.getAttribute(Path.of(System.getProperty("user.home") ?: "/"), "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int)
                    }.getOrNull()
                },
            )
        }
    }
}

/**
 * Host options that have no place in the seam or in `NodeConfig`.
 *
 * @param teamId the Apple Team ID that names the app group container. It is fixed at build time and is an OWNER decision
 *   (macos.md 11.1 M-D10): the repository holds no value, and none is invented. Null means an unsigned or ad-hoc build, which
 *   uses the dev state directory and says `UNSIGNED BUILD: container protection absent`.
 */
data class MacOptions(
    val teamId: String? = MacBuildInfo.teamId(),
    val serviceLabel: String = SERVICE_LABEL,
) {
    init {
        require(teamId == null || TEAM_ID.matches(teamId)) { "a Team ID is exactly ten upper-case letters and digits" }
    }

    companion object {
        const val SERVICE_LABEL = "xyz.mdhv.asom.node"
        val TEAM_ID = Regex("^[A-Z0-9]{10}$")
    }
}

/**
 * The Team ID the build was signed with, read from `build-info.properties` on the classpath, which the (later, MC5) signing step
 * writes. This scaffold has no such resource, so the Team ID is null, so every run is a dev-state run. The value is never
 * guessed and never taken from an environment variable: a variable would let any same-user process name the group container.
 */
object MacBuildInfo {
    private const val RESOURCE = "/xyz/mdhv/asom/desktop/mac/build-info.properties"

    fun teamId(): String? = try {
        MacBuildInfo::class.java.getResourceAsStream(RESOURCE)?.use { s ->
            java.util.Properties().also { it.load(s.reader(Charsets.UTF_8)) }.getProperty("teamId")?.trim()?.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }
}
