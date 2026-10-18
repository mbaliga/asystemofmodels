package xyz.mdhv.asom.pairing

import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import java.security.MessageDigest

/**
 * AIDL-verified caller identity (§5.7 / invariant §1.5): derived from
 * Binder.getCallingUid() + PackageManager, NEVER from intent extras or
 * `callingPackage` strings.
 */
data class VerifiedCaller(
    val uid: Int,
    val packageName: String,
    /** SHA-256 of the signing certificate, lowercase hex. */
    val certHash: String,
    /** Human-readable label shown on the consent sheet. */
    val label: String,
) {
    /** `AA:BB:…` short fingerprint for the consent UI. */
    fun fingerprint(): String =
        certHash.chunked(2).joinToString(":").uppercase().take(29) + "…"
}

object CallerVerifier {

    /** Resolves the verified identity for [uid]; null when unresolvable. */
    fun verify(pm: PackageManager, uid: Int): VerifiedCaller? {
        val packageName = pm.getPackagesForUid(uid)?.firstOrNull() ?: return null
        val info = try {
            pm.getPackageInfo(packageName, GET_SIGNING_CERTIFICATES)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        val cert = signers.firstOrNull()?.toByteArray() ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(cert)
            .joinToString("") { "%02x".format(it) }
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
        return VerifiedCaller(uid, packageName, hash, label)
    }
}
