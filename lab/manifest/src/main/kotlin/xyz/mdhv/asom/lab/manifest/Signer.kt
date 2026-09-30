package xyz.mdhv.asom.lab.manifest

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import xyz.mdhv.asom.lab.bench.Audience
import xyz.mdhv.asom.lab.bench.BenchCodec
import xyz.mdhv.asom.lab.bench.BenchDoc
import xyz.mdhv.asom.lab.bench.DAY_MS
import xyz.mdhv.asom.lab.bench.Derive
import xyz.mdhv.asom.lab.bench.Project
import xyz.mdhv.asom.lab.bench.ja
import xyz.mdhv.asom.lab.bench.ji
import xyz.mdhv.asom.lab.bench.jo
import xyz.mdhv.asom.lab.bench.js
import xyz.mdhv.asom.lab.bench.jsOrNull
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs

/** What the node knows about itself: the parts of the body that are not benchmark numbers. `results` are never an input; they come from `project(derive(bench))` only. */
class ManifestInputs(val producer: Producer, val device: Device, val bench: BenchDoc)

/** The signer could not produce a manifest that its own verifier accepts; nothing is sent (typed error `MANIFEST_UNAVAILABLE`). */
class ManifestUnavailable(val code: RejectCode?, message: String) : Exception(message)

class StoredSeq(val seq: Long, val contentDigest: String)

/** The `seq` rule needs a durable store: the value is written (with `force`) before the first signature over the new body. */
interface SeqStore {
    fun load(): StoredSeq?
    fun persist(stored: StoredSeq)
}

class InMemorySeqStore : SeqStore {
    private var s: StoredSeq? = null

    override fun load(): StoredSeq? = s

    override fun persist(stored: StoredSeq) {
        s = stored
    }
}

/** A file store: temp file, `FileChannel.force(true)`, atomic move. */
class FileSeqStore(private val file: File) : SeqStore {
    override fun load(): StoredSeq? {
        if (!file.isFile) return null
        val lines = file.readText(Charsets.UTF_8).split("\n")
        val seq = lines.getOrNull(0)?.toLongOrNull() ?: return null
        val digest = lines.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return StoredSeq(seq, digest)
    }

    override fun persist(stored: StoredSeq) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write("${stored.seq}\n${stored.contentDigest}\n".toByteArray(Charsets.UTF_8))
            out.channel.force(true)
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

class Signed(val container: ByteArray, val verified: Verified)

class SignedFile(val container: ByteArray, val exportFingerprint: String, val verified: Verified)

/**
 * `projectFile(bodyOwn)` (LAB_SPEC 4.7, design 5.8 S2, ERRATA ERR-CLOSURE-1): the FILE body carries no stable device identifier. The subject becomes
 * the per-export key (`ephemeral`), `platformIds` and `securityPatch` go, every timestamp truncates to the day, the battery level, screen state,
 * OS build and GPU driver are dropped, and `seq` is omitted. `results` are then recomputed from the file-form bench.
 */
object FileProjection {
    fun inputsOf(b: Body): ManifestInputs = ManifestInputs(b.producer, b.device, b.bench)

    fun body(inputs: ManifestInputs, exportNodeId: String): JObject {
        val benchFile = Project.projectBenchFile(inputs.bench)
        val deviceFile = inputs.device.copy(platformIds = null, os = inputs.device.os.copy(securityPatch = null))
        return ManifestBuilder.bodyJson(Audience.FILE, null, ManifestBuilder.subjectJson(exportNodeId, "ephemeral"), inputs, benchFile, deviceFile)
    }

    fun fromOwn(own: Body, exportNodeId: String): JObject = body(inputsOf(own), exportNodeId)
}

object ManifestBuilder {
    fun subjectJson(nodeId: String, keyStorage: String): JValue = jo("nodeId" to js(nodeId), "keyAlg" to js("ES256"), "keyStorage" to js(keyStorage))

    fun bodyJson(audience: Audience, seq: Long?, subject: JValue, inputs: ManifestInputs, bench: BenchDoc, device: Device): JObject {
        val results = Project.results(Derive.derive(bench), audience)
        val m = mutableListOf<Pair<String, JValue>>(
            "audience" to js(audience.wire), "subject" to subject, "producer" to ManifestDecoder.producerJson(inputs.producer),
            "device" to ManifestDecoder.deviceJson(device), "bench" to BenchCodec.encode(bench), "results" to ja(results),
        )
        if (seq != null) m += "seq" to ji(seq)
        return jo(m)
    }

    fun payload(body: JValue, presentation: JValue): ByteArray = Jcs.serialize(jo("schema" to js("asom.manifest/1"), "schemaMinor" to ji(0), "body" to body, "presentation" to presentation))
}

/**
 * `signPresentation` (LAB_SPEC 4.7): builds the body, signs `PAE(type, JCS(obj))`, normalises to low-S, wraps the container, then verifies
 * its own output through the CONSUMER path; any reject means nothing is sent.
 */
class ManifestSigner(
    private val confFloor: String,
    private val knownBadConf: Set<String> = emptySet(),
    private val productionKeys: Boolean = true,
) {
    private fun sha256B64u(b: ByteArray): String = Base64Strict.encodeUrlNoPad(MessageDigest.getInstance("SHA-256").digest(b))

    /** Audience `own`: NIK-signed, answers one `MANIFEST_REQ`; `challenge` is the requester's 32 bytes. */
    fun signOwn(inputs: ManifestInputs, key: EcKeyPair, keyStorage: String, challenge: ByteArray, nowMs: Long, seqStore: SeqStore): Signed {
        require(challenge.size == 32) { "the challenge is 32 bytes" }
        val subject = ManifestBuilder.subjectJson(key.nodeId, keyStorage)
        val content = ManifestBuilder.bodyJson(Audience.OWN, null, subject, inputs, inputs.bench, inputs.device)
        val digest = sha256B64u(Jcs.serialize(content))
        val stored = seqStore.load()
        val seq = if (stored != null && stored.contentDigest == digest) {
            stored.seq
        } else {
            val next = maxOf((stored?.seq ?: 0L) + 1L, nowMs / 1000L)
            seqStore.persist(StoredSeq(next, digest))
            next
        }
        val body = ManifestBuilder.bodyJson(Audience.OWN, seq, subject, inputs, inputs.bench, inputs.device)
        val presentation = jo("issuedAtMs" to ji(nowMs), "expiresAtMs" to ji(nowMs + 600_000L), "challenge" to js(Base64Strict.encodeUrlNoPad(challenge)))
        val payload = ManifestBuilder.payload(body, presentation)
        val sig = Es256.sign(key.private, Dsse.pae(Dsse.PT_MANIFEST_V1, payload))
        val container = Dsse.container(payload, sig, key.nodeId, key.spki)
        val ctx = VerifyContext(Mode.MESH, pinnedSpki = key.spki, expectedChallenge = challenge, confFloor = confFloor, knownBadConf = knownBadConf, productionKeys = productionKeys, nowMs = nowMs)
        return when (val r = Verifier.verify(container, ctx)) {
            is Verified -> Signed(container, r)
            is Rejected -> throw ManifestUnavailable(r.code, "own presentation refused by its own verifier at step ${r.step}: ${r.code}")
        }
    }

    /**
     * Audience `file`: signed by a fresh per-export key (never the NIK), the private key is discarded on return. The body is
     * `projectFile(bodyOwn)`: a new subject (the export key's node id, `ephemeral`), no `platformIds`, no `securityPatch`, day-granular times, no
     * battery level, screen state, OS build or GPU driver, no `seq`, no expiry, no challenge, no evidence (ERRATA ERR-CLOSURE-1).
     */
    fun signFile(inputs: ManifestInputs, nowMs: Long, exportKey: EcKeyPair = Es256.generate()): SignedFile {
        val body = FileProjection.body(inputs, exportKey.nodeId)
        val presentation = jo("issuedAtMs" to ji(nowMs / DAY_MS * DAY_MS))
        val payload = ManifestBuilder.payload(body, presentation)
        val sig = Es256.sign(exportKey.private, Dsse.pae(Dsse.PT_MANIFEST_V1, payload))
        val container = Dsse.container(payload, sig, exportKey.nodeId, exportKey.spki)
        val fp = Spki.exportFingerprint(exportKey.spki)
        val ctx = VerifyContext(Mode.FILE, comparedFingerprint = fp, compareMethod = CompareMethod.TYPED, confFloor = confFloor, knownBadConf = knownBadConf, productionKeys = productionKeys, nowMs = nowMs)
        return when (val r = Verifier.verify(container, ctx)) {
            is Verified -> SignedFile(container, fp, r)
            is Rejected -> throw ManifestUnavailable(r.code, "file export refused by its own verifier at step ${r.step}: ${r.code}")
        }
    }
}
