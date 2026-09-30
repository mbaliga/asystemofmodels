package xyz.mdhv.asom.ut

import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.Policy

/**
 * The `error{rid, code}` values of `asom-ut-ctl/1` (ubuntu-touch.md 7.3): the v1 5.6 enum plus three more. The three
 * extra values are UI-only; none of them is an HTTP code or ever appears on a wire between nodes.
 */
enum class UiErrorCode(val message: String) {
    NOT_PAIRED("this app is not paired"),
    TOKEN_REVOKED("this pairing was revoked"),
    NO_PROVIDER_KEY("pair a device to use asom here"),
    MODEL_UNKNOWN("no paired device has this model"),
    ALL_PROVIDERS_COOLING("all your paired devices are unavailable"),
    LOCAL_ENGINE_ABSENT("this device has no local engine"),
    UNSUPPORTED_BY_DRIVER("this build cannot do that yet"),
    LEDGER_UNAVAILABLE("the ledger cannot be written, so nothing was sent"),
    MESH_STREAM_INTERRUPTED("the paired device stopped in the middle of the answer"),
    INTERRUPTED_BY_SUSPEND("the app was paused, so the answer was cut");

    companion object {
        fun of(code: AsomErrorCode): UiErrorCode = valueOf(code.name)
        fun fromWire(name: String): UiErrorCode? = entries.firstOrNull { it.name == name }
    }
}

/** What a paired peer looks like to the error mapping: which models it holds and whether it is in cooldown or back-off. */
data class PeerView(val alias: String, val models: Set<String>, val cooling: Boolean)

/**
 * The error of a request that no attempt served, over the PEERS-ONLY universe of an Ubuntu Touch node (ubuntu-touch.md 7.3,
 * design 7.6). The order is the v1 router's (`Router.plan`): `local-only`, then an unknown concrete model, then "no usable
 * provider", then "everything is cooling". With no paired peer it therefore returns exactly what `Router.plan` returns for a
 * universe with no stored key (RL1's oracle; `ErrorMappingOracleTest` compares the two on the fixture catalogue).
 * Returns null when a peer can serve.
 */
object ErrorMapping {
    fun map(model: String, peers: List<PeerView>, catalogueModels: Set<String>?): UiErrorCode? {
        val policy = Policy.fromWire(model)
        if (policy == Policy.LOCAL_ONLY) return UiErrorCode.LOCAL_ENGINE_ABSENT
        if (policy == null && catalogueModels != null && model !in catalogueModels) return UiErrorCode.MODEL_UNKNOWN
        if (peers.isEmpty()) return UiErrorCode.NO_PROVIDER_KEY
        val holders = if (policy != null) peers.filter { it.models.isNotEmpty() } else peers.filter { model in it.models }
        if (holders.isEmpty()) return if (policy != null) UiErrorCode.NO_PROVIDER_KEY else UiErrorCode.MODEL_UNKNOWN
        if (holders.all { it.cooling }) return UiErrorCode.ALL_PROVIDERS_COOLING
        return null
    }
}
