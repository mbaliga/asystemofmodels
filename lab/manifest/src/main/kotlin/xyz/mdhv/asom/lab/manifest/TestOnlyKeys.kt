package xyz.mdhv.asom.lab.manifest

import java.util.Base64

/**
 * The TEST-ONLY keys of `lab/conformance/keys/TEST-ONLY-keys.json`. Their private scalars are published deliberately, so every
 * verifier and pin import in production mode refuses their node ids with `TEST_ONLY_KEY` (LAB_SPEC 4.5, vector M03-141). A test
 * (`TestOnlyKeysFileTest`) fails if this table and the file ever differ.
 */
object TestOnlyKeys {
    class Entry(val name: String, val dHex: String, val spkiB64: String, val nodeId: String, val exportFingerprint: String) {
        val spki: ByteArray get() = Base64.getDecoder().decode(spkiB64)

        /** Built from `d_hex` with `ECPrivateKeySpec(BigInteger(d, 16), secp256r1Params)` (LAB_SPEC 4.5). */
        fun keyPair(): EcKeyPair = EcKeyPair(Es256.privateKeyFromScalar(dHex), spki)
    }

    val entries: List<Entry> = listOf(
        Entry(
            "key1", "aac50e2a2423464df8043623dad7a02d793d2d47fffb54e6ccd0dd65a32b8313",
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE4qjYZv8IPpVldevJ/GAe+oYlG/YpAPoqkmMxjeyqofErSay/5ls13fJbnWLTZ7Oj51JprjQj628DEDqwZM1yTw==",
            "vaw93hb8yBZTX2LebYgzri1pfOnBlRIILoBLiyK_eN4", "XWWD3-XQW7T-EBMU-27ML-PG3C-BTVY",
        ),
        Entry(
            "key2", "3ef2b0d09033044dc4dd9bfeba7545299e44cabe0eaad29ac5f08badc57a051e",
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+N+gnIA8/3sQcbv/rdPdsdZXkKiOSfjkpAn/0T/+MtZgmIgg9C5bSICP3VXPPcv8T9YgM/An0QaE3jBbT8klew==",
            "idgZ8sjS2Fz_gsDBjfMRF3rH08Z6lwdee3U3OoPlVGE", "RHMBT-4WI2L-MFZ7-4CYD-AY34-YRC4",
        ),
        Entry(
            "key3", "61e596d036ac80a5f6735c24e5b60022941c030fb6db61e388942ade776129dd",
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEvW9J02rbkaDFtCXpNMq77Jh1yZmpFLDSKvKj/oTzfQnmzMNjnoC8J/NgJ5KhPqBECLBvzMP1mUPXyvbkuw0yxw==",
            "Ees1kUrYV_JxZfeLes0ifZTbzDZgbHOzSicMPUhhxlA", "CHVTL-EKK3B-L7E4-LF66-FXVT-JCPU",
        ),
        Entry(
            "key4", "b7efb656fbb409c511576f4b1c1c1889a73f6ecd6e8c2433472259f47cac82b8",
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAElc6GhXJwwZ5mQB1Pbb50SCPaPjvK6vzHgd/8x4jVDLbGoNYMhp+LjMEwm7RsJtHCUL3AIoLMF4/8hLp2JRol8w==",
            "Fs7m5h_0Asnep04eOQ30T6BtAI6rXroeBjGk82GQWj8", "C3HON-ZQ76Q-BMTX-VHJY-PDSD-PUJ4",
        ),
    )

    val nodeIds: Set<String> = entries.map { it.nodeId }.toSet()

    fun key(name: String): Entry = entries.first { it.name == name }

    fun isTestOnly(nodeId: String): Boolean = nodeId in nodeIds
}
