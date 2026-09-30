package xyz.mdhv.asom.desktop.mac.keys

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import xyz.mdhv.asom.desktop.mac.MacPaths
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.ProtocolSpec

/** An existing identity cannot be used (its key does not answer, its blob is corrupt): a user decision is needed, never a silent downgrade. */
class NikUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException("NIK_UNAVAILABLE: $message", cause)

/**
 * T2: a Secure Enclave P-256 key made and used by the Swift helper (macos.md 5). The node keeps the wrapped key blob
 * (`dataRepresentation`) and the public key in `<container>/node/nik.se` (0600). The helper holds nothing between requests: every
 * signature arrives with its blob, so a process that spawns its own helper gets nothing without the blob, and the blob lives
 * only in the node's protected container. That is the whole of the protection against use by another same-user process: the
 * blob is NOT bound to asom's App ID (FM15), so what keeps others from it is the container, an operating-system policy.
 *
 * This class was written and tested against a scripted helper (a JCA-backed fake enclave). Whether a real Secure Enclave
 * accepts a key made by a Developer-ID-signed helper without entitlements is assumption AM01 (spike S-M1, owner device).
 */
class SecureEnclaveNik private constructor(
    private val file: Path,
    private val client: HelperClient,
    private val blob: ByteArray,
    private val spki: ByteArray,
) : NikKey {
    override val tier: NikTier = NikTier.T2_SECURE_ENCLAVE

    override fun sign(message: ByteArray): ByteArray {
        require(message.size <= ProtocolSpec.MAX_DATA_BYTES) { "the helper signs at most ${ProtocolSpec.MAX_DATA_BYTES} bytes" }
        val sig = client.seSign(blob, message)
        check(sig.size == Es256.SIGNATURE_BYTES) { "the helper returned a signature of ${sig.size} bytes, not 64" }
        return sig
    }

    override fun spki(): ByteArray = spki.copyOf()

    /** True when the Enclave signs a fixed string and the public key verifies it. Run at every start (macos.md 3.5). */
    fun selfTest(): Boolean = client.seSelftest(blob)

    /**
     * Forgets this node's copy of the blob (overwritten, then deleted). It does NOT destroy the Enclave key: a copy of the blob
     * made earlier by any process still works until the Mac is erased, and the helper has no operation that deletes an Enclave key.
     */
    override fun delete() {
        val size = Files.size(file).toInt()
        Files.write(file, ByteArray(size), StandardOpenOption.WRITE)
        Files.delete(file)
    }

    override fun close() {}

    companion object {
        const val FILE_NAME = "nik.se"
        private val MAGIC = "ASOMNSE1".toByteArray(Charsets.US_ASCII)

        /** Creates the key in the Enclave, checks that the public key is a real P-256 key, and persists the blob (never overwrites). */
        fun create(dir: Path, client: HelperClient, ownUid: Int?): SecureEnclaveNik {
            val bad = MacPaths.privateDirProblems(dir, ownUid)
            require(bad.isEmpty()) { "refusing to write the T2 blob: the identity directory is not private: $bad" }
            val k = client.seCreate()
            check(Es256.isP256Spki(k.spki)) { "the helper returned a public key that is not a P-256 SubjectPublicKeyInfo" }
            val bytes = ByteArrayOutputStream().also { o ->
                DataOutputStream(o).apply {
                    write(MAGIC)
                    writeInt(k.blob.size); write(k.blob)
                    writeInt(k.spki.size); write(k.spki)
                }
            }.toByteArray()
            val file = dir.resolve(FILE_NAME)
            MacPaths.writeNewPrivateFile(file, bytes)
            return SecureEnclaveNik(file, client, k.blob, k.spki)
        }

        /** Null when there is no blob file. A file that is not ours, is not private, or is corrupt is [NikUnavailableException]. */
        fun open(dir: Path, client: HelperClient, ownUid: Int?): SecureEnclaveNik? {
            val file = dir.resolve(FILE_NAME)
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
            val bad = MacPaths.privateFileProblems(file, ownUid)
            if (bad.isNotEmpty()) throw NikUnavailableException("the key blob file is not private: $bad")
            val bytes = Files.readAllBytes(file)
            try {
                require(bytes.size > MAGIC.size + 8 && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "not an asom T2 blob file" }
                val buf = ByteBuffer.wrap(bytes, MAGIC.size, bytes.size - MAGIC.size)
                val blobLen = buf.int
                require(blobLen in 1..ProtocolSpec.MAX_BLOB_BYTES && blobLen <= buf.remaining() - 4) { "corrupt blob length" }
                val blob = ByteArray(blobLen).also { buf.get(it) }
                val spkiLen = buf.int
                require(spkiLen == ProtocolSpec.SPKI_BYTES && spkiLen == buf.remaining()) { "corrupt public key length" }
                val spki = ByteArray(spkiLen).also { buf.get(it) }
                require(Es256.isP256Spki(spki)) { "the stored public key is not a P-256 SubjectPublicKeyInfo" }
                return SecureEnclaveNik(file, client, blob, spki)
            } catch (e: IllegalArgumentException) {
                throw NikUnavailableException("the key blob file is corrupt: ${e.message}", e)
            }
        }
    }
}
