package xyz.mdhv.asom.desktop.mac

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import xyz.mdhv.asom.desktop.mac.helper.HelperCodec
import xyz.mdhv.asom.desktop.mac.helper.HelperJson
import xyz.mdhv.asom.desktop.mac.helper.JV

/** Every registry row of this node goes back to "unpaired" (C7): the peers must be paired again with the new identity. */
fun interface PairedRegistry {
    fun unpairAll(): Int
}

class NikMigratedException(val reason: String) : IllegalStateException("NIK_MIGRATED: $reason")

/**
 * The clone guard of macos.md 3.3(e): `node/binding.json` holds `sha256(platformDigest || salt)`, where `platformDigest` is the
 * helper's `platform.uuid` (the SHA-256 of `IOPlatformUUID`; the raw UUID never reaches the JVM). If the record does not match
 * this Mac at start, the node refuses to present the old identity (`NIK_MIGRATED`), unpairs every peer, and asks the user to
 * pair again. Migration Assistant or a restored backup copies a T0 key file, and this is what turns "a copy of the file is the
 * node" into "a copy of the file on another Mac is a dead identity" for an honest user (AM18, AM19).
 *
 * WHAT IT DOES NOT DO: it does not stop an attacker who controls the new Mac, who can write a fresh binding record for the
 * copied key. The record is a guard against accidents, not against theft.
 *
 * The binding is written BEFORE the key (see [MacNikStore]), so a crash between the two leaves a binding with no key, which is
 * harmless; an identity with no binding is treated as migrated (it cannot be told from a copy).
 */
class MigrationGuard(
    private val bindingFile: Path,
    private val platformDigest: () -> ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    sealed interface Verdict {
        /** No binding and no identity: nothing to protect yet. */
        data object Fresh : Verdict

        /** The binding matches this Mac. */
        data object Bound : Verdict

        /** `NIK_MIGRATED`: do not present the identity. */
        class Migrated(val reason: String) : Verdict

        /** This Mac's own digest could not be read, so nothing can be concluded. The identity is not presented. */
        class Unverifiable(val reason: String) : Verdict
    }

    fun check(identityExists: Boolean): Verdict {
        val bytes = try {
            Files.readAllBytes(bindingFile)
        } catch (_: NoSuchFileException) {
            return if (identityExists) Verdict.Migrated("an identity exists but there is no binding record") else Verdict.Fresh
        } catch (e: Exception) {
            // EACCES, EIO, a directory in its place: nothing was learned about this Mac, and the answer to a mismatch is to
            // unpair every peer, which cannot be undone. Not presented, not unpaired (ERR-FX-HWM-11).
            return Verdict.Unverifiable("the binding record cannot be read (${e::class.simpleName}); the identity is not presented, and no peer is unpaired")
        }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            return Verdict.Migrated("the binding record is corrupt")
        }
        val record = parse(text) ?: return Verdict.Migrated("the binding record is corrupt")
        val digest = try {
            platformDigest()
        } catch (e: Exception) {
            return Verdict.Unverifiable("this Mac's platform digest cannot be read (${e.message ?: e::class.simpleName})")
        }
        val expected = compute(digest, record.first)
        return if (MessageDigest.isEqual(expected, record.second)) Verdict.Bound
        else Verdict.Migrated("this Mac differs from the one the identity was created on")
    }

    /** Applies the verdict: a migrated identity unpairs every registry row. Returns the verdict and how many rows were unpaired. */
    fun enforce(identityExists: Boolean, registry: PairedRegistry): Pair<Verdict, Int> {
        val v = check(identityExists)
        return v to if (v is Verdict.Migrated) registry.unpairAll() else 0
    }

    /** Creates the binding record (0600, never overwrites). */
    fun bind() {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val binding = compute(platformDigest(), salt)
        val json = "{\"v\":1,\"salt\":\"${HelperCodec.base64Encode(salt)}\",\"binding\":\"${HelperCodec.base64Encode(binding)}\"}\n"
        MacPaths.writeNewPrivateFile(bindingFile, json.toByteArray(Charsets.UTF_8))
    }

    /** Removes the record. Only the explicit identity-destroy flow calls this; a new identity then writes a new binding. */
    fun unbind() {
        Files.deleteIfExists(bindingFile)
    }

    private fun compute(digest: ByteArray, salt: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(digest + salt)

    private fun parse(text: String): Pair<ByteArray, ByteArray>? = try {
        val o = HelperJson.parse(text.trimEnd('\n')) as? JV.Obj
        if (o == null || o.members.map { it.first }.toSet() != setOf("v", "salt", "binding")) null
        else if ((o["v"] as? JV.Num)?.value != 1L) null
        else {
            val salt = (o["salt"] as? JV.Str)?.value?.let(HelperCodec::base64Decode)
            val binding = (o["binding"] as? JV.Str)?.value?.let(HelperCodec::base64Decode)
            if (salt == null || binding == null || salt.size != SALT_BYTES || binding.size != 32) null else salt to binding
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val SALT_BYTES = 16
    }
}
