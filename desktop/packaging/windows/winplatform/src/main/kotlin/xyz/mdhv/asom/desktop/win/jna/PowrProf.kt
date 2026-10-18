package xyz.mdhv.asom.desktop.win.jna

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.util.UUID
import xyz.mdhv.asom.desktop.win.api.SuspendNotificationApi
import xyz.mdhv.asom.desktop.win.api.SuspendSignal
import xyz.mdhv.asom.desktop.win.api.WinApiException

/** `ULONG CALLBACK DeviceNotifyCallbackRoutine(PVOID Context, ULONG Type, PVOID Setting)`. Runs on an OS thread-pool thread. */
internal interface DeviceNotifyCallback : StdCallLibrary.StdCallCallback {
    fun callback(context: Pointer?, type: Int, setting: Pointer?): Int
}

/** `DEVICE_NOTIFY_SUBSCRIBE_PARAMETERS`, passed as the `HANDLE Recipient` when the flags say `DEVICE_NOTIFY_CALLBACK`. */
@Structure.FieldOrder("Callback", "Context")
internal class DeviceNotifySubscribeParameters : Structure() {
    @JvmField var Callback: DeviceNotifyCallback? = null
    @JvmField var Context: Pointer? = null
}

internal interface PowrProfApi : StdCallLibrary {
    fun PowerRegisterSuspendResumeNotification(flags: Int, recipient: DeviceNotifySubscribeParameters, handle: PointerByReference): Int
    fun PowerUnregisterSuspendResumeNotification(handle: Pointer): Int
    fun PowerSettingRegisterNotification(settingGuid: Pointer, flags: Int, recipient: DeviceNotifySubscribeParameters, handle: PointerByReference): Int
    fun PowerSettingUnregisterNotification(handle: Pointer): Int
}

/** Decodes what the power-broadcast callbacks hand over. Pure, so it is tested on any OS. */
object PowerBroadcastDecoder {
    const val PBT_APMSUSPEND = 0x4
    const val PBT_APMRESUMESUSPEND = 0x7
    const val PBT_APMRESUMEAUTOMATIC = 0x12
    const val PBT_POWERSETTINGCHANGE = 0x8013

    /** `GUID_CONSOLE_DISPLAY_STATE`: 0 = off, 1 = on, 2 = dimmed. */
    const val CONSOLE_DISPLAY_STATE = "6fe69556-704a-47a0-8f24-c28d936fda47"

    /** The 16-byte in-memory form of a GUID: Data1, Data2 and Data3 little-endian, then Data4 as written. */
    fun guidBytes(text: String): ByteArray {
        val u = UUID.fromString(text)
        val b = ByteArray(16)
        val hi = u.mostSignificantBits
        val lo = u.leastSignificantBits
        val d1 = (hi ushr 32).toInt()
        val d2 = ((hi ushr 16) and 0xffff).toInt()
        val d3 = (hi and 0xffff).toInt()
        for (i in 0 until 4) b[i] = (d1 shr (8 * i)).toByte()
        for (i in 0 until 2) b[4 + i] = (d2 shr (8 * i)).toByte()
        for (i in 0 until 2) b[6 + i] = (d3 shr (8 * i)).toByte()
        for (i in 0 until 8) b[8 + i] = (lo ushr (8 * (7 - i))).toByte()
        return b
    }

    /**
     * [setting] is the `POWERBROADCAST_SETTING` bytes for a `PBT_POWERSETTINGCHANGE` (GUID, DataLength, Data), else null.
     * Anything unrecognised is null: an unknown event is not a signal.
     */
    fun signalFor(type: Int, setting: ByteArray?): SuspendSignal? = when (type) {
        PBT_APMSUSPEND -> SuspendSignal.SUSPEND
        PBT_APMRESUMESUSPEND -> SuspendSignal.RESUME_SUSPEND
        PBT_APMRESUMEAUTOMATIC -> SuspendSignal.RESUME_AUTOMATIC
        PBT_POWERSETTINGCHANGE -> displaySignal(setting)
        else -> null
    }

    private fun displaySignal(s: ByteArray?): SuspendSignal? {
        if (s == null || s.size < 24) return null
        if (!s.copyOfRange(0, 16).contentEquals(guidBytes(CONSOLE_DISPLAY_STATE))) return null
        val len = le32(s, 16)
        if (len < 4) return null
        return when (le32(s, 20)) {
            0 -> SuspendSignal.DISPLAY_OFF
            1 -> SuspendSignal.DISPLAY_ON
            2 -> SuspendSignal.DISPLAY_DIMMED
            else -> null
        }
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}

/**
 * `PowerRegisterSuspendResumeNotification(DEVICE_NOTIFY_CALLBACK)` [FW10] and
 * `PowerSettingRegisterNotification(GUID_CONSOLE_DISPLAY_STATE)` (AW18, unverified). Neither needs a window. The callback
 * objects are held by the returned handle: a callback that is garbage-collected while registered crashes the process.
 */
class JnaSuspendNotifications : SuspendNotificationApi {
    override fun register(onSignal: (SuspendSignal) -> Unit): AutoCloseable {
        val lib = Libs.powrprof
        val callback = object : DeviceNotifyCallback {
            override fun callback(context: Pointer?, type: Int, setting: Pointer?): Int {
                val bytes = if (type == PowerBroadcastDecoder.PBT_POWERSETTINGCHANGE && setting != null) {
                    try {
                        setting.getByteArray(0, 24)
                    } catch (_: Throwable) {
                        null
                    }
                } else null
                PowerBroadcastDecoder.signalFor(type, bytes)?.let {
                    try {
                        onSignal(it)
                    } catch (_: Throwable) {
                    }
                }
                return 0
            }
        }
        val params = DeviceNotifySubscribeParameters().also { it.Callback = callback }
        val suspendHandle = PointerByReference()
        val s1 = lib.PowerRegisterSuspendResumeNotification(DEVICE_NOTIFY_CALLBACK, params, suspendHandle)
        if (s1 != 0) throw WinApiException("PowerRegisterSuspendResumeNotification failed ($s1)", s1)
        val guid = Memory(16).also { it.write(0, PowerBroadcastDecoder.guidBytes(PowerBroadcastDecoder.CONSOLE_DISPLAY_STATE), 0, 16) }
        val displayHandle = PointerByReference()
        val s2 = lib.PowerSettingRegisterNotification(guid, DEVICE_NOTIFY_CALLBACK, params, displayHandle)
        return object : AutoCloseable {
            @Volatile private var closed = false

            @Suppress("unused")
            private val keep = arrayOf<Any>(callback, params, guid)

            override fun close() {
                if (closed) return
                closed = true
                if (s2 == 0) runCatching { lib.PowerSettingUnregisterNotification(displayHandle.value) }
                runCatching { lib.PowerUnregisterSuspendResumeNotification(suspendHandle.value) }
            }
        }
    }

    companion object {
        const val DEVICE_NOTIFY_CALLBACK = 2
    }
}
