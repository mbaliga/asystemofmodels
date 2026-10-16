package xyz.mdhv.asom.desktop.win.jna

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import xyz.mdhv.asom.desktop.win.acl.SidResolver
import xyz.mdhv.asom.desktop.win.api.WinApiException

internal interface Advapi32Api : StdCallLibrary {
    fun ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl: String, revision: Int, descriptor: PointerByReference, size: IntByReference?): Boolean
}

internal object SecurityDescriptors {
    private const val SDDL_REVISION_1 = 1

    /** A self-relative security descriptor for [sddl]. */
    fun fromSddl(sddl: String): ByteArray {
        val pp = PointerByReference()
        val size = IntByReference()
        if (!Libs.advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1, pp, size)) {
            throw WinApiException("ConvertStringSecurityDescriptorToSecurityDescriptorW rejected \"$sddl\"")
        }
        val p: Pointer = pp.value
        try {
            return p.getByteArray(0, size.value)
        } finally {
            Libs.kernel32.LocalFree(p)
        }
    }
}

/** The identity of this process and SID <-> account name lookups, over jna-platform's `Advapi32Util`. Windows only. */
object JnaIdentity {
    /** The SID string of the account this process runs as; null on any failure or on another operating system. */
    fun currentUserSid(): String? = try {
        val token = com.sun.jna.platform.win32.WinNT.HANDLEByReference()
        val k = com.sun.jna.platform.win32.Kernel32.INSTANCE
        if (!com.sun.jna.platform.win32.Advapi32.INSTANCE.OpenProcessToken(k.GetCurrentProcess(), com.sun.jna.platform.win32.WinNT.TOKEN_QUERY, token)) {
            null
        } else {
            try {
                com.sun.jna.platform.win32.Advapi32Util.getTokenAccount(token.value).sidString
            } finally {
                k.CloseHandle(token.value)
            }
        }
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }
}

class JnaSidResolver : SidResolver {
    override fun sidOf(accountName: String): String? = try {
        com.sun.jna.platform.win32.Advapi32Util.getAccountByName(accountName).sidString
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    override fun accountNameOf(sid: String): String? = try {
        com.sun.jna.platform.win32.Advapi32Util.getAccountBySid(sid).fqn
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }
}
