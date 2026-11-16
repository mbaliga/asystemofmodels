package xyz.mdhv.asom.desktop.mac.keys

import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom
import xyz.mdhv.asom.desktop.KeyStorage
import xyz.mdhv.asom.desktop.NikStore
import xyz.mdhv.asom.desktop.NodePaths
import xyz.mdhv.asom.desktop.mac.MigrationGuard
import xyz.mdhv.asom.desktop.mac.NikMigratedException
import xyz.mdhv.asom.desktop.mac.helper.HelperClient

/**
 * The macOS node identity store. Constructing it, and reading [keyStorage], creates NOTHING: the key is generated only by
 * [createAtFirstMeshEnable], which the mesh-enable command calls (the NIK is generated at first mesh enable only).
 *
 * Rules (macos.md 5):
 *  - an existing identity is never replaced and never downgraded: an existing T2 identity that cannot be opened or fails its
 *    self-test is [NikUnavailableException] (the user must decide: pair again with a new identity), it does NOT fall back to T0;
 *  - both key files present is ambiguous and refused;
 *  - the platform binding is checked before an identity is presented (`NIK_MIGRATED`), and written BEFORE a new key is made.
 */
class MacNikStore(
    private val paths: NodePaths,
    private val client: HelperClient,
    private val guard: MigrationGuard?,
    private val ownUid: () -> Int?,
) : NikStore {
    @Volatile
    private var resolved: KeyStorage? = null

    private val random = SecureRandom()

    /** The tier of the existing key, or `unknown` when there is none or it cannot be inspected. Never creates, never signs. */
    override val keyStorage: KeyStorage
        get() {
            resolved?.let { return it }
            val hasSe = Files.exists(paths.identityDir.resolve(SecureEnclaveNik.FILE_NAME), LinkOption.NOFOLLOW_LINKS)
            val hasFile = Files.exists(paths.identityDir.resolve(FileNik.FILE_NAME), LinkOption.NOFOLLOW_LINKS)
            val found = when {
                hasSe && !hasFile -> KeyStorage.SECURE_ENCLAVE
                hasFile && !hasSe -> KeyStorage.FILE
                else -> null
            }
            return found?.also { resolved = it } ?: KeyStorage.UNKNOWN
        }

    private fun identityExists(): Boolean =
        Files.exists(paths.identityDir.resolve(SecureEnclaveNik.FILE_NAME), LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(paths.identityDir.resolve(FileNik.FILE_NAME), LinkOption.NOFOLLOW_LINKS)

    /**
     * Opens the existing identity: null when there is none. Throws [NikMigratedException] when the binding says this is not the
     * Mac the identity was made on, [NikUnavailableException] when the identity cannot be used. T2 is self-tested at every open.
     */
    fun existing(): NikKey? {
        val exists = identityExists()
        when (val v = guard?.check(exists)) {
            is MigrationGuard.Verdict.Migrated -> throw NikMigratedException(v.reason)
            is MigrationGuard.Verdict.Unverifiable -> throw NikUnavailableException(v.reason)
            else -> {}
        }
        if (!exists) return null
        val se = Files.exists(paths.identityDir.resolve(SecureEnclaveNik.FILE_NAME), LinkOption.NOFOLLOW_LINKS)
        val file = Files.exists(paths.identityDir.resolve(FileNik.FILE_NAME), LinkOption.NOFOLLOW_LINKS)
        if (se && file) throw NikUnavailableException("both nik.se and nik.p8 exist; the node will not guess which identity is real")
        val key: NikKey = if (se) {
            val k = SecureEnclaveNik.open(paths.identityDir, client, ownUid()) ?: throw NikUnavailableException("nik.se vanished while opening")
            val ok = try {
                k.selfTest()
            } catch (e: Exception) {
                throw NikUnavailableException("the Secure Enclave self-test failed to run: ${e.message ?: e::class.simpleName}", e)
            }
            if (!ok) throw NikUnavailableException("the Secure Enclave self-test did not verify")
            // The helper's self-test checks the blob against the blob's OWN public key. The public key the node presents to peers is
            // the one stored beside it, so that one is checked too: a fresh challenge signed through the blob must verify with it.
            val problem = try {
                NikTierSelector.selfTest(k, ByteArray(CHALLENGE_BYTES).also(random::nextBytes))
            } catch (e: Exception) {
                throw NikUnavailableException("the stored public key could not be checked against the blob: ${e.message ?: e::class.simpleName}", e)
            }
            if (problem != null) throw NikUnavailableException("the stored public key does not belong to the blob: $problem")
            k
        } else {
            FileNik.open(paths.identityDir, ownUid()) ?: throw NikUnavailableException("nik.p8 vanished while opening")
        }
        resolved = key.tier.keyStorage
        return key
    }

    /** Refuses when an identity already exists: an identity is never silently replaced. */
    fun createAtFirstMeshEnable(request: KeyTierRequest): NikSelection {
        if (identityExists()) throw IllegalStateException("a node key already exists; destroy it explicitly (asom node forget --destroy-identity) if a new identity is meant")
        guard?.let {
            // No identity exists, so a leftover binding (a crash between the binding and the key) can only be stale.
            it.unbind()
            it.bind()
        }
        val selection = NikTierSelector.select(
            request,
            seAvailable = { client.hello().se },
            t2 = { SecureEnclaveNik.create(paths.identityDir, client, ownUid()) },
            t0 = { FileNik.create(paths.identityDir, ownUid()) },
        )
        resolved = selection.tier.keyStorage
        return selection
    }

    private companion object {
        const val CHALLENGE_BYTES = 32
    }
}
