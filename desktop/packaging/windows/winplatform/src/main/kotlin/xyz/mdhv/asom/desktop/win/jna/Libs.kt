package xyz.mdhv.asom.desktop.win.jna

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.win32.W32APITypeMapper
import xyz.mdhv.asom.desktop.win.api.WinApiUnavailableException

/**
 * Loads the Windows DLLs lazily, on first use, so that constructing any port on another operating system is harmless and
 * only the first CALL fails, with [WinApiUnavailableException]. Native code is loaded from the system path (`kernel32`,
 * `ncrypt` and the rest are system DLLs); JNA's own `jnidispatch` follows `-Djna.boot.library.path` and, when
 * `-Djna.nounpack=true`, is never extracted to a temp directory (windows.md 3.3(f), C13).
 *
 * Type mapping is Unicode: `String` is `wchar_t*`, `Boolean` is `BOOL`. Functions are declared with their exact exported
 * names (`...W` where the API has both); no function mapper is used, so no name is guessed.
 */
internal object Libs {
    private val OPTIONS: Map<String, Any> = mapOf(Library.OPTION_TYPE_MAPPER to W32APITypeMapper.UNICODE)

    private fun <T : Library> load(dll: String, api: Class<T>): T {
        try {
            check(Native.POINTER_SIZE == 8) { "only 64-bit Windows (x64, arm64) is supported" }
            return Native.load(dll, api, OPTIONS)
        } catch (e: LinkageError) {
            throw WinApiUnavailableException("$dll is not available on this system", e)
        } catch (e: IllegalStateException) {
            throw WinApiUnavailableException(e.message ?: "unsupported platform", e)
        }
    }

    val kernel32: Kernel32Api by lazy { load("kernel32", Kernel32Api::class.java) }
    val ncrypt: NCryptApi by lazy { load("ncrypt", NCryptApi::class.java) }
    val advapi32: Advapi32Api by lazy { load("advapi32", Advapi32Api::class.java) }
    val powrprof: PowrProfApi by lazy { load("powrprof", PowrProfApi::class.java) }
    val pdh: PdhApi32 by lazy { load("pdh", PdhApi32::class.java) }
    val wtsapi32: WtsApi by lazy { load("wtsapi32", WtsApi::class.java) }
    val user32: User32Api by lazy { load("user32", User32Api::class.java) }
    val shell32: Shell32Api by lazy { load("shell32", Shell32Api::class.java) }
    val wer: WerApi by lazy { load("wer", WerApi::class.java) }
}
