package xyz.mdhv.asom.lab.manifest

import java.util.Base64
import xyz.mdhv.asom.lab.bench.jo
import xyz.mdhv.asom.lab.bench.ja
import xyz.mdhv.asom.lab.bench.js
import xyz.mdhv.asom.lab.bench.ji
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs

object Dsse {
    const val PT_MANIFEST_V1 = "application/vnd.asom.manifest.v1+json"
    const val PT_KEY_ROLLOVER_V1 = "application/vnd.asom.key-rollover.v1+json"
    const val MAX_CONTAINER_BYTES = 524_288
    const val MAX_PAYLOAD_BYTES = 262_144

    /** `PAE(type, body) = "DSSEv1" SP LEN(type) SP type SP LEN(body) SP body`, LEN the decimal byte length without leading zeros. */
    fun pae(type: String, body: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.UTF_8)
        val head = "DSSEv1 ${t.size} ".toByteArray(Charsets.US_ASCII) + t + " ${body.size} ".toByteArray(Charsets.US_ASCII)
        return head + body
    }

    /** What the container carries besides the signed payload. `signer.spki` is unauthenticated: it is used only in FILE mode (step 7). */
    fun container(
        payload: ByteArray,
        rawSig: ByteArray,
        keyid: String,
        signerSpki: ByteArray?,
        payloadType: String = PT_MANIFEST_V1,
        evidence: List<JValue> = emptyList(),
    ): ByteArray {
        val members = mutableListOf<Pair<String, JValue>>(
            "asomCapabilityManifest" to ji(1),
            "dsse" to jo(
                "payload" to js(b64(payload)), "payloadType" to js(payloadType),
                "signatures" to ja(jo("keyid" to js(keyid), "sig" to js(b64(rawSig)))),
            ),
            "evidence" to ja(evidence),
        )
        if (signerSpki != null) members += "signer" to jo("spki" to js(b64(signerSpki)))
        return Jcs.serialize(jo(members))
    }

    /** Producers emit the standard alphabet with padding. */
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
