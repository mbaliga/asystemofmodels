package xyz.mdhv.asom.desktop.win.service

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import xyz.mdhv.asom.desktop.DesktopPlatform
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodeRuntime
import xyz.mdhv.asom.desktop.governor.LenderState
import xyz.mdhv.asom.desktop.win.NodeMutex
import xyz.mdhv.asom.desktop.win.WinPlatform
import xyz.mdhv.asom.desktop.win.acl.AclPlan
import xyz.mdhv.asom.desktop.win.acl.AclVerifier
import xyz.mdhv.asom.desktop.win.acl.ServiceSid
import xyz.mdhv.asom.desktop.win.acl.WinAcl

/**
 * The node inside a service (windows.md 3.2, 3.3). Apache procrun with `StartMode=jvm` loads the jlinked `jvm.dll` in
 * process and calls the static entry `xyz.mdhv.asom.desktop.win.ServiceEntry.start/stop`, which drive this class.
 *
 * The service has no `--StdOutput`/`--StdError` and this code writes nothing to either. It starts the single-identity mutex
 * guard, resolves the SYSTEM-mode paths, builds the node runtime (OFF and inert: nothing listens, nothing lends until an
 * owner action, which does not exist yet), and BLOCKS in [start] until [stop]. That `start` may block until `stop` in
 * `StartMode=jvm` is what this code assumes; it is unverified until the W5 install smoke has run on a Windows host.
 * [stop] drains within [STOP_TIMEOUT_MS] (procrun `StopTimeout=35` leaves 5 s of slack).
 */
class ServiceHost(
    private val platform: DesktopPlatform,
    private val mutex: NodeMutex,
    private val stopTimeoutMs: Long = STOP_TIMEOUT_MS,
    private val acl: WinAcl = (platform as? WinPlatform)?.acl ?: throw IllegalArgumentException("a service host needs the DACL reader of a WinPlatform, or one passed explicitly"),
    private val serviceSid: String = ServiceSid.of((platform as? WinPlatform)?.serviceName ?: "asom"),
) {
    private val stopped = CountDownLatch(1)
    private var held: AutoCloseable? = null

    @Volatile
    var runtime: NodeRuntime? = null
        private set

    @Volatile
    var stopDrainedInTime: Boolean? = null
        private set

    /** Blocks until [stop]. Throws (procrun logs it and the service fails to start) if the identity is taken or the paths are refused. */
    fun start() {
        held = mutex.acquire()
        try {
            val paths = platform.paths(HostMode.SYSTEM)
            val configFile = paths.stateDir.resolve("config.json")
            val config = if (Files.isRegularFile(configFile)) {
                verifyStateTree(paths.stateDir, configFile)
                NodeConfig.parse(Files.readString(configFile, Charsets.UTF_8))
            } else {
                NodeConfig()
            }
            runtime = NodeRuntime(platform, HostMode.SYSTEM, config)
        } catch (e: Throwable) {
            release()
            throw e
        }
        stopped.await()
    }

    /**
     * `%ProgramData%` lets any local user create a sub-folder, and CREATOR OWNER keeps control of what they made, so the
     * service reads nothing from its state directory until the directory and `config.json` show the service plan's owner
     * (SYSTEM, Administrators or the service SID) and no ACE for anyone else (windows ERRATA ERR-FX-HWM-3). A snapshot
     * that cannot be read refuses too. TOCTOU-prone: it is a moment, taken immediately before the read.
     */
    private fun verifyStateTree(stateDir: java.nio.file.Path, configFile: java.nio.file.Path) {
        val plan = AclPlan.serviceState(serviceSid)
        val problems = ArrayList<String>()
        for (target in listOf(stateDir, configFile)) {
            val found = try {
                AclVerifier.violations(plan, acl.snapshot(target))
            } catch (e: Exception) {
                listOf("cannot read the ACL: ${e.message ?: e::class.simpleName}")
            }
            found.mapTo(problems) { "${target.fileName}: $it" }
        }
        if (problems.isNotEmpty()) {
            throw HostRefusedException("refusing to read the service configuration: its state directory does not carry the service DACL (${problems.joinToString("; ")})")
        }
    }

    /** Drains (a user disable, then OFF), releases the mutex, and lets [start] return. Never blocks longer than the timeout. */
    fun stop() {
        val rt = runtime
        if (rt != null) {
            val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "asom-service-drain").also { it.isDaemon = true } }
            try {
                pool.submit { rt.shutdown() }.get(stopTimeoutMs, TimeUnit.MILLISECONDS)
                stopDrainedInTime = rt.fsm.state == LenderState.OFF
            } catch (_: TimeoutException) {
                stopDrainedInTime = false
            } catch (_: Exception) {
                stopDrainedInTime = false
            } finally {
                pool.shutdownNow()
            }
        }
        release()
        stopped.countDown()
    }

    private fun release() {
        held?.let { runCatching { it.close() } }
        held = null
    }

    companion object {
        const val STOP_TIMEOUT_MS = 30_000L
    }
}
