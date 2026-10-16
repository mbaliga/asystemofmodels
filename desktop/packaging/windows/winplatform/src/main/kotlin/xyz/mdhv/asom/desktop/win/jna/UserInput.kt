package xyz.mdhv.asom.desktop.win.jna

import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import xyz.mdhv.asom.desktop.win.api.NotificationState
import xyz.mdhv.asom.desktop.win.api.UserInputApi

@Structure.FieldOrder("cbSize", "dwTime")
internal class LastInputInfo : Structure() {
    @JvmField var cbSize: Int = 8
    @JvmField var dwTime: Int = 0
}

internal interface User32Api : StdCallLibrary {
    fun GetLastInputInfo(info: LastInputInfo): Boolean
}

internal interface Shell32Api : StdCallLibrary {
    /** Returns an HRESULT (0 = S_OK); [state] receives a `QUERY_USER_NOTIFICATION_STATE`. */
    fun SHQueryUserNotificationState(state: IntByReference): Int
}

/**
 * User-mode presence inputs: `GetLastInputInfo` (keyboard and mouse only; a gamepad does not count) and
 * `SHQueryUserNotificationState`. Both describe the session this process runs in, so they are useless from session 0
 * (service mode uses [JnaSessions]).
 */
class JnaUserInput : UserInputApi {
    override fun idleMs(): Long? {
        val info = LastInputInfo()
        if (!Libs.user32.GetLastInputInfo(info)) return null
        info.read()
        // Both are 32-bit millisecond tick counts that wrap every 49.7 days; unsigned subtraction handles the wrap.
        return (Libs.kernel32.GetTickCount().toLong() - info.dwTime.toLong()) and 0xffffffffL
    }

    override fun notificationState(): NotificationState? {
        val s = IntByReference()
        if (Libs.shell32.SHQueryUserNotificationState(s) != 0) return null
        return NotificationState.fromCode(s.value)
    }
}
