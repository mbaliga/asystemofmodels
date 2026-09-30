package xyz.mdhv.asom.desktop.win.keys

import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.KeyStorage
import xyz.mdhv.asom.desktop.NikStore
import xyz.mdhv.asom.desktop.NodePaths
import xyz.mdhv.asom.desktop.win.acl.AclPlan
import xyz.mdhv.asom.desktop.win.acl.ServiceSid
import xyz.mdhv.asom.desktop.win.acl.WinAcl
import xyz.mdhv.asom.desktop.win.api.CngPort
import xyz.mdhv.asom.desktop.win.api.DpapiPort
import xyz.mdhv.asom.desktop.win.api.KeyScope

class WinNikBackends(val cng: CngPort, val dpapi: DpapiPort?, val acl: WinAcl)

/**
 * The Windows node identity store. Constructing it, and reading [keyStorage], creates NOTHING: the key is generated only
 * by [createAtFirstMeshEnable], which the mesh-enable command calls (the NIK is generated at first mesh enable only).
 * User modes use a user-scope CNG key `asom-nik-v1`; service mode uses a machine-scope key `asom-nik-v1-svc` whose DACL
 * names SYSTEM and `NT SERVICE\asom` only (AW03: unverified until spike S-W2).
 */
class WinNikStore(
    private val paths: NodePaths,
    private val backends: WinNikBackends,
    private val ownerSid: () -> String?,
    private val serviceName: String = "asom",
) : NikStore {
    private val service = paths.mode == HostMode.SYSTEM
    private val scope = if (service) KeyScope.MACHINE else KeyScope.USER
    private val keyName = if (service) NcryptNik.SERVICE_KEY_NAME else NcryptNik.USER_KEY_NAME

    @Volatile
    private var resolved: KeyStorage? = null

    /** The tier of the existing key, or `unknown` when there is none or it cannot be inspected. Never creates. */
    override val keyStorage: KeyStorage
        get() {
            resolved?.let { return it }
            val found = try {
                existing()?.use { it.tier.keyStorage }
            } catch (_: Exception) {
                null
            }
            return found?.also { resolved = it } ?: KeyStorage.UNKNOWN
        }

    /** SDDL of the machine-key DACL: SYSTEM and the service SID, nothing else. */
    fun serviceKeySddl(): String = "D:P(A;;GA;;;SY)(A;;GA;;;${ServiceSid.of(serviceName)})"

    /** Opens the existing key: T2, then T1, then T0 (the order a key could have been created in). Null when none exists. */
    fun existing(): NikKey? {
        NcryptNik.open(backends.cng, NikTier.T2_TPM, keyName, scope)?.let { return it }
        NcryptNik.open(backends.cng, NikTier.T1_OS_KEYSTORE, keyName, scope)?.let { return it }
        return FileNik.open(paths.identityDir, backends.dpapi)
    }

    /** Refuses when a key already exists: an identity is never silently replaced. */
    fun createAtFirstMeshEnable(request: KeyTierRequest): NikSelection {
        val present = existing()
        if (present != null) {
            present.close()
            throw IllegalStateException("a node key already exists; delete it explicitly (asom key destroy) if a new identity is meant")
        }
        val sddl = if (service) serviceKeySddl() else null
        val selection = NikTierSelector.select(
            request,
            t2 = { NcryptNik.create(backends.cng, NikTier.T2_TPM, keyName, scope, sddl) },
            t1 = { NcryptNik.create(backends.cng, NikTier.T1_OS_KEYSTORE, keyName, scope, sddl) },
            t0 = { FileNik.create(paths.identityDir, backends.dpapi, backends.acl, filePlan()) },
        )
        resolved = selection.tier.keyStorage
        return selection
    }

    private fun filePlan(): AclPlan =
        if (service) AclPlan.serviceState(ServiceSid.of(serviceName))
        else AclPlan.userState(ownerSid() ?: throw IllegalStateException("the owner SID cannot be read; the T0 file needs an owner-only DACL"))
}
