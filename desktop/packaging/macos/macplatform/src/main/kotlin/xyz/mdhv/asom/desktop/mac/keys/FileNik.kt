package xyz.mdhv.asom.desktop.mac.keys

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import xyz.mdhv.asom.desktop.mac.MacPaths

/**
 * T0: a PKCS#8 file at `<container>/node/nik.p8` (0600) (macos.md 5). It is the fallback for a Mac without a Secure Enclave, a
 * failed Enclave self-test, CI, and an explicit `--key-tier file`. It protects against other unprivileged users and (in a signed
 * build's group container) against other developer teams' processes, and against nothing else: same-user code, root and backups
 * read it, and a copied file is the node. The directory is checked BEFORE the key is written, so a key never lands in a
 * directory other users can read.
 *
 * NOT BUILT: the optional passphrase wrap (macos.md 5, T17f), which would need a passphrase source and a KDF. The file is
 * always unwrapped, which is what "serve while logged out" would opt into anyway. Declared in mac ERRATA MAC-KEY-2.
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

        fun create(dir: Path, ownUid: Int?): FileNik {
            val bad = MacPaths.privateDirProblems(dir, ownUid)
            require(bad.isEmpty()) { "refusing to write the T0 key: the identity directory is not private: $bad" }
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val spki = kp.public.encoded
            val bytes = ByteArrayOutputStream().also { o ->
                DataOutputStream(o).apply {
                    write(MAGIC)
                    writeShort(spki.size); write(spki)
                    write(kp.private.encoded)
                }
            }.toByteArray()
            val file = dir.resolve(FILE_NAME)
            MacPaths.writeNewPrivateFile(file, bytes)
            return FileNik(file, kp.private, spki)
        }

        /** Null when there is no key file. A file that is not ours, not private, or corrupt is [NikUnavailableException]. */
        fun open(dir: Path, ownUid: Int?): FileNik? {
            val file = dir.resolve(FILE_NAME)
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
            val bad = MacPaths.privateFileProblems(file, ownUid)
            if (bad.isNotEmpty()) throw NikUnavailableException("the T0 key file is not private: $bad")
            val bytes = Files.readAllBytes(file)
            try {
                require(bytes.size > MAGIC.size + 2 && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "not an asom T0 key file" }
                val buf = ByteBuffer.wrap(bytes, MAGIC.size, bytes.size - MAGIC.size)
                val n = buf.short.toInt() and 0xffff
                require(n == 91 && n < buf.remaining()) { "corrupt public key length" }
                val spki = ByteArray(n).also { buf.get(it) }
                val pkcs8 = ByteArray(buf.remaining()).also { buf.get(it) }
                val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                return FileNik(file, key, spki)
            } catch (e: Exception) {
                throw NikUnavailableException("the T0 key file is corrupt: ${e.message ?: e::class.simpleName}", e)
            }
        }
    }
}
