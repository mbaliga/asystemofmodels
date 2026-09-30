package xyz.mdhv.asom.desktop.win

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import xyz.mdhv.asom.desktop.win.acl.JdkWinAcl
import xyz.mdhv.asom.desktop.win.acl.SidResolver
import xyz.mdhv.asom.desktop.win.acl.WinAcl
import xyz.mdhv.asom.desktop.win.api.CngPort
import xyz.mdhv.asom.desktop.win.api.CpuLoadSource
import xyz.mdhv.asom.desktop.win.api.DpapiPort
import xyz.mdhv.asom.desktop.win.api.InterfaceLister
import xyz.mdhv.asom.desktop.win.api.MutexPort
import xyz.mdhv.asom.desktop.win.api.PdhApi
import xyz.mdhv.asom.desktop.win.api.PowerRequestApi
import xyz.mdhv.asom.desktop.win.api.ProcessRunner
import xyz.mdhv.asom.desktop.win.api.SessionApi
import xyz.mdhv.asom.desktop.win.api.SuspendNotificationApi
import xyz.mdhv.asom.desktop.win.api.SystemPowerStatusSource
import xyz.mdhv.asom.desktop.win.api.TextFiles
import xyz.mdhv.asom.desktop.win.api.UserInputApi
import xyz.mdhv.asom.desktop.win.exec.SystemProcessRunner
import xyz.mdhv.asom.desktop.win.jna.JdkInterfaceLister
import xyz.mdhv.asom.desktop.win.jna.JnaCng
import xyz.mdhv.asom.desktop.win.jna.JnaDpapi
import xyz.mdhv.asom.desktop.win.jna.JnaPdh
import xyz.mdhv.asom.desktop.win.jna.JnaPowerRequests
import xyz.mdhv.asom.desktop.win.jna.JnaSessions
import xyz.mdhv.asom.desktop.win.jna.JnaSidResolver
import xyz.mdhv.asom.desktop.win.jna.JnaSuspendNotifications
import xyz.mdhv.asom.desktop.win.jna.JnaSystemPowerStatus
import xyz.mdhv.asom.desktop.win.jna.JnaUserInput
import xyz.mdhv.asom.desktop.win.jna.Kernel32Mutex
import xyz.mdhv.asom.desktop.win.presence.JdkCpuLoadSource

/**
 * Every Windows API the host uses, as a bundle of ports. [system] wires the real JNA implementations, none of which loads
 * a DLL until it is called; tests build the same bundle from fakes.
 */
class WinNative(
    val cng: CngPort,
    val dpapi: DpapiPort,
    val sids: SidResolver,
    val acl: WinAcl,
    val powerStatus: SystemPowerStatusSource,
    val powerRequests: PowerRequestApi,
    val suspend: SuspendNotificationApi,
    val userInput: UserInputApi,
    val sessions: SessionApi,
    val pdh: PdhApi,
    val cpu: CpuLoadSource,
    val mutex: MutexPort,
    val runner: ProcessRunner,
    val interfaces: InterfaceLister,
    val files: TextFiles,
) {
    companion object {
        fun system(systemRoot: String = System.getenv("SystemRoot") ?: "C:\\Windows"): WinNative {
            val sids = JnaSidResolver()
            return WinNative(
                cng = JnaCng(),
                dpapi = JnaDpapi(),
                sids = sids,
                acl = JdkWinAcl(sids),
                powerStatus = JnaSystemPowerStatus(),
                powerRequests = JnaPowerRequests(),
                suspend = JnaSuspendNotifications(),
                userInput = JnaUserInput(),
                sessions = JnaSessions(),
                pdh = JnaPdh(),
                cpu = JdkCpuLoadSource(),
                mutex = Kernel32Mutex(),
                runner = SystemProcessRunner(systemRoot),
                interfaces = JdkInterfaceLister(),
                files = JdkTextFiles,
            )
        }
    }
}

/** Reads other products' files (the Tailscale env file) as UTF-8 with replacement, dropping a byte-order mark. */
object JdkTextFiles : TextFiles {
    private const val MAX_BYTES = 1 shl 20

    override fun read(path: Path): String? = try {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) {
            null
        } else {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            decoder.decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString().removePrefix("\uFEFF")
        }
    } catch (_: Exception) {
        null
    }
}
