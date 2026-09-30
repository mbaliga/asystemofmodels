package xyz.mdhv.asom.lab.manifest

import java.io.File
import java.math.BigInteger
import xyz.mdhv.asom.lab.bench.Audience
import xyz.mdhv.asom.lab.bench.BenchCodec
import xyz.mdhv.asom.lab.bench.BenchDoc
import xyz.mdhv.asom.lab.bench.RunOutcome
import xyz.mdhv.asom.lab.bench.Scenarios
import xyz.mdhv.asom.lab.bench.ja
import xyz.mdhv.asom.lab.bench.ji
import xyz.mdhv.asom.lab.bench.jo
import xyz.mdhv.asom.lab.bench.js
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

object Repo {
    val root: File = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot is not set")).canonicalFile
    val conformance: File = File(root, "lab/conformance")
}

fun parseJ(text: String): JValue = when (val r = StrictJson.parse(text.toByteArray(Charsets.UTF_8))) {
    is ParseResult.Ok -> r.value
    is ParseResult.Reject -> error("parse: $r")
}

fun parseB(bytes: ByteArray): JValue = when (val r = StrictJson.parse(bytes)) {
    is ParseResult.Ok -> r.value
    is ParseResult.Reject -> error("parse: $r")
}

/** Path edits over immutable [JValue] trees. A path element is a member name (String) or an array index (Int). */
object JEdit {
    fun get(v: JValue, path: List<Any>): JValue {
        var cur = v
        for (p in path) cur = if (p is String) (cur as JObject)[p] ?: error("no member $p") else (cur as JArray).items[p as Int]
        return cur
    }

    fun set(v: JValue, path: List<Any>, value: JValue): JValue {
        if (path.isEmpty()) return value
        val head = path[0]
        val rest = path.drop(1)
        return if (head is String) {
            val o = v as JObject
            val existing = o.members.any { it.first == head }
            val members = if (existing) o.members.map { (k, x) -> if (k == head) k to set(x, rest, value) else k to x } else o.members + (head to set(JObject(emptyList()), rest, value))
            JObject(members)
        } else {
            val a = v as JArray
            JArray(a.items.mapIndexed { i, x -> if (i == head) set(x, rest, value) else x })
        }
    }

    fun remove(v: JValue, path: List<Any>): JValue {
        if (path.size == 1) {
            val head = path[0] as String
            return JObject((v as JObject).members.filter { it.first != head })
        }
        val head = path[0]
        val rest = path.drop(1)
        return if (head is String) JObject((v as JObject).members.map { (k, x) -> if (k == head) k to remove(x, rest) else k to x })
        else JArray((v as JArray).items.mapIndexed { i, x -> if (i == head) remove(x, rest) else x })
    }
}

/** How a test container is put together. Anything can be forged here because these are TEST-ONLY keys. */
class DocSpec(
    val payload: ByteArray,
    val signKey: TestOnlyKeys.Entry = F.key1,
    val type: String = Dsse.PT_MANIFEST_V1,
    val keyid: String? = signKey.nodeId,
    val signerSpki: ByteArray? = signKey.spki,
    val sig: ByteArray? = null,
    val extraSigs: Int = 0,
    val encoder: (ByteArray) -> String = { Dsse.b64(it) },
    val highS: Boolean = false,
    val evidence: List<JValue>? = emptyList(),
    val signOver: ByteArray? = null,
    val sigText: String? = null,
    val payloadText: String? = null,
)

/** The shared fixtures of the manifest tests and of the vector generator. Everything is synthetic; every key is TEST-ONLY. */
object F {
    val key1 = TestOnlyKeys.key("key1")
    val key2 = TestOnlyKeys.key("key2")
    val key3 = TestOnlyKeys.key("key3")
    val key4 = TestOnlyKeys.key("key4")
    val challenge1: ByteArray = (Base64Strict.decodeUrlNoPad("nvsK-QRFTzOSUx0XtUEgqKOWIsUY8j2dL4ikBHOh5pw") as xyz.mdhv.asom.lab.json.B64Result.Ok).bytes
    const val CONF = "0.2.0"
    const val ISSUED = 1_790_676_000_000L
    const val NOW = ISSUED + 60_000L
    const val SEQ = 17L

    val bench: BenchDoc by lazy { benchFrom("""{"preset":"phone","plan":"standard"}""") }

    fun benchFrom(scenario: String): BenchDoc {
        val r = Scenarios.run(parseJ(scenario))
        check(r.outcome == "completed") { "scenario did not complete: ${r.outcome}" }
        return r.doc!!
    }

    fun deviceFor(b: BenchDoc): Device = Device(
        cls = b.device.form, vendor = b.device.maker, model = b.device.model,
        platformIds = PlatformIds("example", "ex1", "Example", "EX-1", "ex1_global"),
        os = Os(b.device.platform, b.device.osVersion, "2026-09-01"), socVendor = "ExampleSilicon", socName = b.device.soc,
        logicalCores = 8, clusters = listOf(Cluster(2, 4_320_000), Cluster(6, 3_530_000)), memoryTotalBytes = b.device.memTotalBytes,
        accelerators = listOf(Accelerator("gpu", "ExampleSilicon", "ExampleGPU 800", listOf("opencl", "vulkan"), null), Accelerator("npu", "ExampleSilicon", "ExampleNPU", emptyList(), null)),
        battery = true, batteryDesignMilliWh = 25_000, batteryDesignPresent = true, cooling = "fan", stateSource = "android-thermal-headroom",
    )

    fun producerFor(b: BenchDoc): Producer = Producer(
        "asom-android", "4.0.0", ProducerHarness("asom-bench", "1.0.0", "asom-bench-method/1", b.harness.confVersion),
        ProducerEngine(b.harness.engine.name, b.harness.engine.commit.take(8), b.harness.engine.buildFlags),
    )

    fun inputs(b: BenchDoc = bench): ManifestInputs = ManifestInputs(producerFor(b), deviceFor(b), b)

    fun ownBody(
        key: TestOnlyKeys.Entry = key1, storage: String = "strongbox", seq: Long = SEQ, inputs: ManifestInputs = inputs(), bench: BenchDoc = inputs.bench,
        device: Device = inputs.device,
    ): JObject = ManifestBuilder.bodyJson(Audience.OWN, seq, ManifestBuilder.subjectJson(key.nodeId, storage), inputs, bench, device)

    fun ownPresentation(issued: Long = ISSUED, expires: Long = issued + 600_000L, challenge: ByteArray? = challenge1): JValue = jo(
        "issuedAtMs" to ji(issued), "expiresAtMs" to ji(expires),
        "challenge" to (challenge?.let { js(Base64Strict.encodeUrlNoPad(it)) } ?: xyz.mdhv.asom.lab.json.JNull),
    )

    fun ownObj(body: JObject = ownBody(), presentation: JValue = ownPresentation(), minor: Int = 0, schema: String = "asom.manifest/1"): JObject =
        jo("schema" to js(schema), "schemaMinor" to ji(minor), "body" to body, "presentation" to presentation)

    fun fileBody(key: TestOnlyKeys.Entry = key3, inputs: ManifestInputs = inputs()): JObject {
        val benchFile = xyz.mdhv.asom.lab.bench.Project.projectBenchFile(inputs.bench)
        val deviceFile = inputs.device.copy(platformIds = null, os = inputs.device.os.copy(securityPatch = null))
        return ManifestBuilder.bodyJson(Audience.FILE, null, ManifestBuilder.subjectJson(key.nodeId, "ephemeral"), inputs, benchFile, deviceFile)
    }

    val FILE_DAY: Long = ISSUED / xyz.mdhv.asom.lab.bench.DAY_MS * xyz.mdhv.asom.lab.bench.DAY_MS

    fun filePresentation(issued: Long = FILE_DAY): JValue = jo("issuedAtMs" to ji(issued))

    fun fileObj(body: JObject = fileBody(), presentation: JValue = filePresentation()): JObject =
        jo("schema" to js("asom.manifest/1"), "schemaMinor" to ji(0), "body" to body, "presentation" to presentation)

    fun payloadOf(obj: JValue): ByteArray = Jcs.serialize(obj)

    /** Signs with RFC 6979 (deterministic) so that the same call always gives the same bytes. */
    fun rawSig(key: TestOnlyKeys.Entry, paeBytes: ByteArray, highS: Boolean = false): ByteArray {
        val low = Rfc6979.sign(key.dHex, paeBytes, lowS = true)
        if (!highS) return low
        val s = BigInteger(1, low.copyOfRange(32, 64))
        return low.copyOfRange(0, 32) + Es256.fixed32(P256.N.subtract(s))
    }

    fun container(spec: DocSpec): ByteArray {
        val signed = spec.signOver ?: Dsse.pae(spec.type, spec.payload)
        val sig = spec.sig ?: rawSig(spec.signKey, signed, spec.highS)
        val one = mutableListOf<Pair<String, JValue>>()
        spec.keyid?.let { one += "keyid" to js(it) }
        one += "sig" to js(spec.sigText ?: spec.encoder(sig))
        val sigs = List(1 + spec.extraSigs) { jo(one) }
        val members = mutableListOf<Pair<String, JValue>>(
            "asomCapabilityManifest" to ji(1),
            "dsse" to jo("payload" to js(spec.payloadText ?: spec.encoder(spec.payload)), "payloadType" to js(spec.type), "signatures" to ja(sigs)),
        )
        spec.evidence?.let { members += "evidence" to ja(it) }
        spec.signerSpki?.let { members += "signer" to jo("spki" to js(Dsse.b64(it))) }
        return Jcs.serialize(jo(members))
    }

    fun ctxMesh(
        pinned: TestOnlyKeys.Entry? = key1, challenge: ByteArray? = challenge1, now: Long = NOW, rollback: RollbackStore? = null, requiredTier: Tier = Tier.A0,
        confFloor: String = CONF, knownBad: Set<String> = emptySet(), production: Boolean = false,
    ) = VerifyContext(Mode.MESH, pinnedSpki = pinned?.spki, expectedChallenge = challenge, rollback = rollback, requiredTier = requiredTier, confFloor = confFloor, knownBadConf = knownBad, productionKeys = production, nowMs = now)

    fun ctxFile(
        compared: String? = null, method: CompareMethod? = null, now: Long = NOW, confFloor: String = CONF, production: Boolean = false,
        requiredTier: Tier = Tier.A0,
    ) = VerifyContext(Mode.FILE, comparedFingerprint = compared, compareMethod = method, requiredTier = requiredTier, confFloor = confFloor, productionKeys = production, nowMs = now)

    fun verify(doc: ByteArray, ctx: VerifyContext): VerifyResult = Verifier.verify(doc, ctx)

    fun rejectOf(r: VerifyResult): RejectCode? = (r as? Rejected)?.code

    fun sha256B64u(b: ByteArray): String = Base64Strict.encodeUrlNoPad(java.security.MessageDigest.getInstance("SHA-256").digest(b))

    fun strOf(v: JValue): String = (v as JString).value
}
