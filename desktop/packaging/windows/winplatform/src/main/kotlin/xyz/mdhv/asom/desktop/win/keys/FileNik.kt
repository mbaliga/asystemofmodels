package xyz.mdhv.asom.desktop.win.keys

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import xyz.mdhv.asom.desktop.win.acl.AclPlan
import xyz.mdhv.asom.desktop.win.acl.AclVerifier
import xyz.mdhv.asom.desktop.win.acl.WinAcl
import xyz.mdhv.asom.desktop.win.api.DpapiPort

/**
 * T0: a PKCS#8 file (windows.md 5). It exists for CI and headless tests and is selected only by `--key-tier file`.
 * It protects against other unprivileged users, and against nothing else: same-user code, Administrators, backups, Volume
 * Shadow Copy and System Restore snapshots all read it. A DPAPI wrap (user scope only, never `CRYPTPROTECT_LOCAL_MACHINE`,
 * which the [DpapiPort] cannot even express) is only as strong as the user's logon credential and roams with a roaming profile.
 *
 * The directory's DACL is verified against [AclPlan] BEFORE the key is written, and the file's DACL after, so a key never
 * lands in a directory that other users can read.
 */
class FileNik private constructor(
    private val path: Path,
    private val privateKey: PrivateKey,
    private val spki: ByteArray,
) : NikKey {
    override val tier: NikTier = NikTier.T0_FILE

    override fun sign(message: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSAinP1363Format")
        s.initSign(privateKey)
        s.update(message)
        return s.sign()
    }

    override fun spki(): ByteArray = spki.copyOf()

    /** Best effort: the bytes are overwritten before the file is deleted. That does not defeat snapshots or backups. */
    override fun delete() {
        val size = Files.size(path).toInt()
        Files.write(path, ByteArray(size), StandardOpenOption.WRITE)
        Files.delete(path)
    }

    override fun close() {}

    companion object {
        const val FILE_NAME = "nik.p8"
        private val MAGIC = "ASOMNIK1".toByteArray(Charsets.US_ASCII)
        private const val FLAG_DPAPI = 1

        fun create(dir: Path, dpapi: DpapiPort?, acl: WinAcl, plan: AclPlan): FileNik {
            val bad = AclVerifier.violations(plan, acl.snapshot(dir))
            require(bad.isEmpty()) { "refusing to write the T0 key: directory DACL does not honour the plan: $bad" }
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val spki = kp.public.encoded
            val plain = ByteArrayOutputStream().also { o -> DataOutputStream(o).apply { writeShort(spki.size); write(spki); write(kp.private.encoded) } }.toByteArray()
            val payload = dpapi?.protect(plain) ?: plain
            val flags = if (dpapi != null) FLAG_DPAPI else 0
            val file = dir.resolve(FILE_NAME)
            Files.write(file, MAGIC + byteArrayOf(flags.toByte()) + payload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            val fileBad = AclVerifier.violations(plan, acl.snapshot(file))
            if (fileBad.isNotEmpty()) {
                runCatching { Files.delete(file) }
                throw IllegalStateException("T0 key file DACL does not honour the plan, key removed: $fileBad")
            }
            return FileNik(file, kp.private, spki)
        }

        /** Null when there is no key file. A file that is not ours, or a wrapped file without a DPAPI port, is an error. */
        fun open(dir: Path, dpapi: DpapiPort?): FileNik? {
            val file = dir.resolve(FILE_NAME)
            if (!Files.isRegularFile(file)) return null
            val bytes = Files.readAllBytes(file)
            require(bytes.size > MAGIC.size + 1 && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "not an asom T0 key file" }
            val flags = bytes[MAGIC.size].toInt() and 0xff
            var payload = bytes.copyOfRange(MAGIC.size + 1, bytes.size)
            if (flags and FLAG_DPAPI != 0) {
                requireNotNull(dpapi) { "the T0 key file is DPAPI-wrapped and no DPAPI is available here" }
                payload = dpapi.unprotect(payload)
            }
            val buf = ByteBuffer.wrap(payload)
            val n = buf.short.toInt() and 0xffff
            require(n in 1..payload.size - 2) { "corrupt T0 key file" }
            val spki = ByteArray(n).also { buf.get(it) }
            val pkcs8 = ByteArray(buf.remaining()).also { buf.get(it) }
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            return FileNik(file, key, spki)
        }
    }
}
