package xyz.mdhv.asom.desktop.win.jna

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import xyz.mdhv.asom.desktop.win.api.CngKeyHandle
import xyz.mdhv.asom.desktop.win.api.CngPort
import xyz.mdhv.asom.desktop.win.api.CngProvider
import xyz.mdhv.asom.desktop.win.api.DpapiPort
import xyz.mdhv.asom.desktop.win.api.KeyScope
import xyz.mdhv.asom.desktop.win.api.WinApiException

/** CNG key storage (`ncrypt.dll`). Every function returns a `SECURITY_STATUS` (0 = success). */
internal interface NCryptApi : StdCallLibrary {
    fun NCryptOpenStorageProvider(provider: PointerByReference, providerName: String?, flags: Int): Int
    fun NCryptCreatePersistedKey(provider: Pointer, key: PointerByReference, algorithm: String, keyName: String?, legacyKeySpec: Int, flags: Int): Int
    fun NCryptOpenKey(provider: Pointer, key: PointerByReference, keyName: String, legacyKeySpec: Int, flags: Int): Int
    fun NCryptSetProperty(obj: Pointer, property: String, input: ByteArray, inputSize: Int, flags: Int): Int
    fun NCryptFinalizeKey(key: Pointer, flags: Int): Int
    fun NCryptSignHash(key: Pointer, paddingInfo: Pointer?, hash: ByteArray, hashSize: Int, signature: ByteArray?, signatureSize: Int, resultSize: IntByReference, flags: Int): Int
    fun NCryptExportKey(key: Pointer, exportKey: Pointer?, blobType: String, parameters: Pointer?, output: ByteArray?, outputSize: Int, resultSize: IntByReference, flags: Int): Int
    fun NCryptDeleteKey(key: Pointer, flags: Int): Int
    fun NCryptFreeObject(obj: Pointer): Int
}

/**
 * The CNG port over JNA. Written from the documented signatures and NOT run in the container that built it: the Windows-only
 * integration tests (`SoftwareKspIT`, `PcpIT`) are what exercise it, on a hosted Windows runner.
 * Not verified by anything here: that the Platform Crypto Provider holds ECDSA P-256 on a given TPM (AW01), and that the
 * `Security Descr` property with a machine-scope key behaves as written for the service key (AW03).
 */
class JnaCng : CngPort {
    override fun createEcdsaP256(provider: CngProvider, name: String, scope: KeyScope, sddl: String?): CngKeyHandle {
        val lib = Libs.ncrypt
        val prov = openProvider(provider)
            ?: throw WinApiException("provider \"${provider.providerName}\" is not available on this machine")
        val key = PointerByReference()
        var created = false
        try {
            // No NCRYPT_OVERWRITE_KEY_FLAG: a key of that name already existing is an error, never silently replaced.
            check("NCryptCreatePersistedKey", lib.NCryptCreatePersistedKey(prov, key, ALG_ECDSA_P256, name, 0, scopeFlags(scope)))
            created = true
            val k = key.value
            check("NCryptSetProperty(Export Policy)", lib.NCryptSetProperty(k, "Export Policy", dword(0), 4, 0))
            check("NCryptSetProperty(Key Usage)", lib.NCryptSetProperty(k, "Key Usage", dword(NCRYPT_ALLOW_SIGNING_FLAG), 4, 0))
            if (sddl != null) {
                val sd = SecurityDescriptors.fromSddl(sddl)
                check("NCryptSetProperty(Security Descr)", lib.NCryptSetProperty(k, "Security Descr", sd, sd.size, DACL_SECURITY_INFORMATION))
            }
            check("NCryptFinalizeKey", lib.NCryptFinalizeKey(k, 0))
        } catch (e: Throwable) {
            // A key that was created but not finalised is not persisted; freeing it discards it.
            if (created) runCatching { lib.NCryptFreeObject(key.value) }
            runCatching { lib.NCryptFreeObject(prov) }
            throw e
        }
        return JnaCngKey(lib, prov, key.value)
    }

    override fun open(provider: CngProvider, name: String, scope: KeyScope): CngKeyHandle? {
        val lib = Libs.ncrypt
        val prov = openProvider(provider) ?: return null
        val key = PointerByReference()
        val status = lib.NCryptOpenKey(prov, key, name, 0, scopeFlags(scope))
        if (status != 0) {
            runCatching { lib.NCryptFreeObject(prov) }
            if (status == NTE_BAD_KEYSET || status == NTE_NO_KEY) return null
            throw WinApiException("NCryptOpenKey failed (0x${Integer.toHexString(status)})", status)
        }
        return JnaCngKey(lib, prov, key.value)
    }

    private fun openProvider(provider: CngProvider): Pointer? {
        val h = PointerByReference()
        val status = Libs.ncrypt.NCryptOpenStorageProvider(h, provider.providerName, 0)
        return if (status == 0) h.value else null
    }

    private fun scopeFlags(scope: KeyScope) = if (scope == KeyScope.MACHINE) NCRYPT_MACHINE_KEY_FLAG else 0

    companion object {
        const val ALG_ECDSA_P256 = "ECDSA_P256"
        const val NCRYPT_MACHINE_KEY_FLAG = 0x20
        const val NCRYPT_ALLOW_SIGNING_FLAG = 0x2
        const val DACL_SECURITY_INFORMATION = 0x4
        const val NTE_BAD_KEYSET = 0x80090016.toInt()
        const val NTE_NO_KEY = 0x8009000D.toInt()

        fun dword(v: Int): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

        fun check(what: String, status: Int) {
            if (status != 0) throw WinApiException("$what failed (0x${Integer.toHexString(status)})", status)
        }
    }
}

private class JnaCngKey(private val lib: NCryptApi, private val provider: Pointer, private val key: Pointer) : CngKeyHandle {
    @Volatile private var freed = false

    override fun signHash(digest32: ByteArray): ByteArray {
        require(digest32.size == 32) { "a P-256 digest is 32 bytes" }
        val size = IntByReference()
        JnaCng.check("NCryptSignHash(size)", lib.NCryptSignHash(key, null, digest32, digest32.size, null, 0, size, 0))
        val sig = ByteArray(size.value)
        JnaCng.check("NCryptSignHash", lib.NCryptSignHash(key, null, digest32, digest32.size, sig, sig.size, size, 0))
        return sig.copyOf(size.value)
    }

    override fun exportPublicBlob(): ByteArray {
        val size = IntByReference()
        JnaCng.check("NCryptExportKey(size)", lib.NCryptExportKey(key, null, BCRYPT_ECCPUBLIC_BLOB, null, null, 0, size, 0))
        val blob = ByteArray(size.value)
        JnaCng.check("NCryptExportKey", lib.NCryptExportKey(key, null, BCRYPT_ECCPUBLIC_BLOB, null, blob, blob.size, size, 0))
        return blob.copyOf(size.value)
    }

    override fun delete() {
        check(!freed) { "key handle already freed" }
        freed = true
        // NCryptDeleteKey frees the key handle itself.
        JnaCng.check("NCryptDeleteKey", lib.NCryptDeleteKey(key, 0))
        runCatching { lib.NCryptFreeObject(provider) }
    }

    override fun close() {
        if (freed) return
        freed = true
        runCatching { lib.NCryptFreeObject(key) }
        runCatching { lib.NCryptFreeObject(provider) }
    }

    private companion object {
        const val BCRYPT_ECCPUBLIC_BLOB = "ECCPUBLICBLOB"
    }
}

/** `CryptProtectData` / `CryptUnprotectData` with flags 0 (user scope). The port cannot pass `CRYPTPROTECT_LOCAL_MACHINE`. */
class JnaDpapi : DpapiPort {
    override fun protect(plain: ByteArray): ByteArray = try {
        com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plain, 0)
    } catch (e: LinkageError) {
        throw xyz.mdhv.asom.desktop.win.api.WinApiUnavailableException("crypt32 is not available on this system", e)
    }

    override fun unprotect(wrapped: ByteArray): ByteArray = try {
        com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(wrapped, 0)
    } catch (e: LinkageError) {
        throw xyz.mdhv.asom.desktop.win.api.WinApiUnavailableException("crypt32 is not available on this system", e)
    }
}
