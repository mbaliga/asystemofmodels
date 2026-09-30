package xyz.mdhv.asom.desktop.win.jna

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import xyz.mdhv.asom.desktop.win.api.SessionApi
import xyz.mdhv.asom.desktop.win.api.WtsSession

internal interface WtsApi : StdCallLibrary {
    fun WTSQuerySessionInformationW(server: Pointer?, sessionId: Int, infoClass: Int, buffer: PointerByReference, bytesReturned: IntByReference): Boolean
    fun WTSFreeMemory(memory: Pointer?)
}

/**
 * Parser for `WTSINFOEXW` (windows.md 2.1, AW07). Layout on a 64-bit system: `Level` at 0; the `Level1` union at 8 (its
 * `LARGE_INTEGER` members force 8-byte alignment): `SessionId` 8, `SessionState` 12, `SessionFlags` 16, `WinStationName`
 * 20 (33 wide chars), `UserName` 86 (21), `DomainName` 128 (18), then the FILETIMEs from 168: `LogonTime`, `ConnectTime`,
 * `DisconnectTime`, `LastInputTime` 192, `CurrentTime` 200. A test cross-checks these offsets against a JNA `Structure`.
 *
 * `SessionFlags`: `WTS_SESSIONSTATE_LOCK` (0) means locked and `UNLOCK` (1) unlocked per the documentation, and reports
 * of the opposite polarity on some Windows versions exist; the value is therefore surfaced as a flag and never trusted alone.
 */
object WtsInfoExLayout {
    const val LEVEL = 0
    const val SESSION_ID = 8
    const val SESSION_STATE = 12
    const val SESSION_FLAGS = 16
    const val USER_NAME = 86
    const val USER_NAME_CHARS = 21
    const val LAST_INPUT_TIME = 192
    const val CURRENT_TIME = 200
    const val MIN_SIZE = 208

    fun parse(b: ByteArray): WtsSession? {
        if (b.size < MIN_SIZE) return null
        if (i32(b, LEVEL) != 1) return null
        val user = StringBuilder()
        for (i in 0 until USER_NAME_CHARS) {
            val c = (b[USER_NAME + 2 * i].toInt() and 0xff) or ((b[USER_NAME + 2 * i + 1].toInt() and 0xff) shl 8)
            if (c == 0) break
            user.append(c.toChar())
        }
        val flags = i32(b, SESSION_FLAGS)
        val last = i64(b, LAST_INPUT_TIME)
        val now = i64(b, CURRENT_TIME)
        val idle = if (last > 0 && now >= last) (now - last) / 10_000L else null
        return WtsSession(
            sessionId = i32(b, SESSION_ID),
            connectState = i32(b, SESSION_STATE),
            userName = user.toString(),
            lockFlagLocked = when (flags) { 0 -> true; 1 -> false; else -> null },
            idleMs = idle,
        )
    }

    private fun i32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun i64(b: ByteArray, o: Int): Long = (i32(b, o).toLong() and 0xffffffffL) or (i32(b, o + 4).toLong() shl 32)
}

class JnaSessions : SessionApi {
    override fun consoleSession(): WtsSession? {
        val id = Libs.kernel32.WTSGetActiveConsoleSessionId()
        return if (id == NO_SESSION) null else query(id)
    }

    override fun currentSession(): WtsSession? = query(WTS_CURRENT_SESSION)

    private fun query(sessionId: Int): WtsSession? {
        val w = Libs.wtsapi32
        val pp = PointerByReference()
        val n = IntByReference()
        if (!w.WTSQuerySessionInformationW(null, sessionId, WTS_SESSION_INFO_EX, pp, n)) return null
        val p = pp.value ?: return null
        try {
            if (n.value < WtsInfoExLayout.MIN_SIZE) return null
            return WtsInfoExLayout.parse(p.getByteArray(0, n.value))
        } finally {
            w.WTSFreeMemory(p)
        }
    }

    private companion object {
        const val WTS_CURRENT_SESSION = -1
        const val NO_SESSION = -1
        const val WTS_SESSION_INFO_EX = 25
    }
}
