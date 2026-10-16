package xyz.mdhv.asom.desktop.win.jna

import com.sun.jna.LastErrorException
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import xyz.mdhv.asom.desktop.win.api.MutexPort
import xyz.mdhv.asom.desktop.win.api.MutexResult
import xyz.mdhv.asom.desktop.win.api.PowerRequestApi
import xyz.mdhv.asom.desktop.win.api.PowerRequestHandle
import xyz.mdhv.asom.desktop.win.api.RawPowerStatus
import xyz.mdhv.asom.desktop.win.api.SystemPowerStatusSource
import xyz.mdhv.asom.desktop.win.api.WinApiException

/** The kernel32 entry points this module uses. `HANDLE` is a [Pointer]; a failed `PowerCreateRequest` returns `INVALID_HANDLE_VALUE`. */
internal interface Kernel32Api : StdCallLibrary {
    @Throws(LastErrorException::class)
    fun CreateMutexW(attributes: Pointer?, initialOwner: Boolean, name: String): Pointer?

    @Throws(LastErrorException::class)
    fun OpenMutexW(desiredAccess: Int, inheritHandle: Boolean, name: String): Pointer?

    fun CloseHandle(handle: Pointer?): Boolean

    @Throws(LastErrorException::class)
    fun PowerCreateRequest(context: ReasonContext): Pointer?

    fun PowerSetRequest(request: Pointer, type: Int): Boolean

    fun PowerClearRequest(request: Pointer, type: Int): Boolean

    fun GetSystemPowerStatus(out: SystemPowerStatus): Boolean

    fun GetTickCount(): Int

    fun WTSGetActiveConsoleSessionId(): Int

    fun LocalFree(memory: Pointer?): Pointer?
}

/**
 * `REASON_CONTEXT` with `POWER_REQUEST_CONTEXT_SIMPLE_STRING`: Version, Flags, then a union whose largest member (the
 * detailed form) is 24 bytes on a 64-bit system; the simple string pointer is its first member. The two padding fields
 * make this structure the full 32 bytes the OS may read.
 */
@Structure.FieldOrder("Version", "Flags", "SimpleReasonString", "Pad1", "Pad2")
internal class ReasonContext : Structure() {
    @JvmField var Version: Int = 0
    @JvmField var Flags: Int = POWER_REQUEST_CONTEXT_SIMPLE_STRING
    @JvmField var SimpleReasonString: Pointer? = null
    @JvmField var Pad1: Long = 0
    @JvmField var Pad2: Long = 0

    companion object {
        const val POWER_REQUEST_CONTEXT_SIMPLE_STRING = 0x1
    }
}

/** `SYSTEM_POWER_STATUS`: 12 bytes. The fourth byte is `SystemStatusFlag` (battery saver) on Windows 10 and later [FW12]. */
@Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
internal class SystemPowerStatus : Structure() {
    @JvmField var ACLineStatus: Byte = 0
    @JvmField var BatteryFlag: Byte = 0
    @JvmField var BatteryLifePercent: Byte = 0
    @JvmField var SystemStatusFlag: Byte = 0
    @JvmField var BatteryLifeTime: Int = 0
    @JvmField var BatteryFullLifeTime: Int = 0
}

class JnaSystemPowerStatus : SystemPowerStatusSource {
    override fun read(): RawPowerStatus? {
        val s = SystemPowerStatus()
        if (!Libs.kernel32.GetSystemPowerStatus(s)) return null
        s.read()
        return RawPowerStatus(s.ACLineStatus.toInt() and 0xff, s.BatteryFlag.toInt() and 0xff, s.BatteryLifePercent.toInt() and 0xff, s.SystemStatusFlag.toInt() and 0xff)
    }
}

/** `PowerCreateRequest` / `PowerSetRequest(PowerRequestSystemRequired)` / `PowerClearRequest` (windows.md 2.1, [FW08]). */
class JnaPowerRequests : PowerRequestApi {
    override fun create(reason: String): PowerRequestHandle {
        val k = Libs.kernel32
        val text = Memory((reason.length + 1) * 2L).also { it.setWideString(0, reason) }
        val ctx = ReasonContext().also { it.SimpleReasonString = text }
        val h = try {
            k.PowerCreateRequest(ctx)
        } catch (e: LastErrorException) {
            throw WinApiException("PowerCreateRequest failed (Windows error ${e.errorCode})", e.errorCode, e)
        }
        if (h == null || Pointer.nativeValue(h) == INVALID_HANDLE_VALUE) throw WinApiException("PowerCreateRequest returned INVALID_HANDLE_VALUE")
        return object : PowerRequestHandle {
            @Volatile private var closed = false

            @Suppress("unused")
            private val keepAlive = text

            override fun setSystemRequired() {
                if (!k.PowerSetRequest(h, POWER_REQUEST_SYSTEM_REQUIRED)) throw WinApiException("PowerSetRequest failed")
            }

            override fun clearSystemRequired() {
                if (!k.PowerClearRequest(h, POWER_REQUEST_SYSTEM_REQUIRED)) throw WinApiException("PowerClearRequest failed")
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    k.CloseHandle(h)
                }
            }
        }
    }

    companion object {
        const val POWER_REQUEST_SYSTEM_REQUIRED = 1
        private const val INVALID_HANDLE_VALUE = -1L
    }
}

/**
 * `Global\asom-node` as a real named mutex. `CreateMutexW` on an existing name succeeds with `ERROR_ALREADY_EXISTS`, which
 * JNA raises as a [LastErrorException] that REPLACES the returned handle: the handle would leak and keep the mutex object
 * alive for the life of the process. So the name is probed with `OpenMutexW` first: an existing mutex is opened and the
 * handle closed at once (held by another), a missing one (`ERROR_FILE_NOT_FOUND`) is created. Only a race between the two
 * calls can still reach `ERROR_ALREADY_EXISTS`, and it is treated as held. Access denied means the mutex exists under
 * another account.
 */
class Kernel32Mutex : MutexPort {
    override fun tryAcquire(name: String): MutexResult {
        val k = Libs.kernel32
        try {
            val existing = k.OpenMutexW(SYNCHRONIZE, false, name)
            if (existing != null) {
                k.CloseHandle(existing)
                return MutexResult.HeldByOther
            }
        } catch (e: LastErrorException) {
            when (e.errorCode) {
                ERROR_FILE_NOT_FOUND -> {}
                ERROR_ACCESS_DENIED -> return MutexResult.AccessDenied
                else -> return MutexResult.Failed(e.errorCode)
            }
        }
        val h = try {
            k.CreateMutexW(null, false, name)
        } catch (e: LastErrorException) {
            return when (e.errorCode) {
                ERROR_ALREADY_EXISTS -> MutexResult.HeldByOther
                ERROR_ACCESS_DENIED -> MutexResult.AccessDenied
                else -> MutexResult.Failed(e.errorCode)
            }
        }
        if (h == null) return MutexResult.Failed(null)
        return MutexResult.Acquired(AutoCloseable { k.CloseHandle(h) })
    }

    companion object {
        const val ERROR_ALREADY_EXISTS = 183
        const val ERROR_ACCESS_DENIED = 5
        const val ERROR_FILE_NOT_FOUND = 2
        private const val SYNCHRONIZE = 0x00100000
    }
}
