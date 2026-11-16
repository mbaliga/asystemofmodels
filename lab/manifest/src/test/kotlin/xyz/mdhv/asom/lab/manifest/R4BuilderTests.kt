package xyz.mdhv.asom.lab.manifest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.bench.BAbort
import xyz.mdhv.asom.lab.bench.BenchDoc
import xyz.mdhv.asom.lab.bench.BenchEnums
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue

private fun at(v: JValue, vararg path: String): JValue {
    var cur = v
    for (p in path) cur = (cur as JObject)[p] ?: error("no member $p")
    return cur
}

private fun strings(v: JValue): List<String> = (v as JArray).items.map { (it as JString).value }

/** Builder action 13 (REVISION_4 section 6; LAB_SPEC R4-L-14, PP3): the public derivative can carry every OS family a manifest can. */
class PublicDerivativeOsFamilyTest {
    private val verified = F.verify(F.container(DocSpec(F.payloadOf(F.ownObj()))), F.ctxMesh()) as Verified
    private val catalogue = verified.obj.body.results.map { it.fileSha256 }.toSet()

    private fun schema(name: String): JValue = parseB(File(Repo.conformance, "manifest/schema/$name").readBytes())

    private val manifestFamilies: List<String> by lazy {
        strings(at(schema("asom.manifest.1.schema.json"), "\$defs", "body", "properties", "device", "properties", "os", "properties", "family", "enum"))
    }
    private val publicFamilies: List<String> by lazy {
        strings(at(schema("asom.bench-public.1.schema.json"), "properties", "device", "properties", "osFamily", "enum"))
    }

    @Test
    fun thePublicSchemaAcceptsExactlyTheFamiliesTheManifestSchemaAccepts() {
        assertTrue("windows" in manifestFamilies && "ubuntu-touch" in manifestFamilies, "the manifest schema lists both desktop and Ubuntu Touch: $manifestFamilies")
        assertEquals(manifestFamilies.toSet(), publicFamilies.toSet(), "PP3: osFamily of asom.bench-public/1 is the manifest family enum")
        assertEquals(publicFamilies.size, publicFamilies.toSet().size, "no duplicate enum value")
    }

    @Test
    fun aDerivativeOfEveryFamilyOfManifestIsInsideThePublicSchemaEnum() {
        val c = Counter("public derivative os families")
        for (family in manifestFamilies) {
            val os = verified.obj.body.device.os.copy(family = family)
            val body = verified.obj.body.copy(device = verified.obj.body.device.copy(os = os))
            val out = PublicDerivative.from(verified.obj.copy(body = body), catalogue)!!
            val emitted = (at(out, "device", "osFamily") as JString).value
            assertEquals(family, emitted)
            assertTrue(emitted in publicFamilies, "a $family manifest produced a derivative its own schema refuses")
            c.hit()
        }
        c.requireNonVacuous(manifestFamilies.size)
        assertTrue(c.n >= 7, "all seven families were exercised, not a subset")
    }
}

/** Builder action 14 (LAB_SPEC R4-L-13): the per-export key is generated inside the production signing path and cannot be injected through it. */
class ExportKeyGeneratedInsideSignerTest {
    private val inputs = F.inputs()

    @Test
    fun noPublicFileSigningMethodTakesAKey() {
        val c = Counter("file signing entry points")
        val signer = ManifestSigner::class.java
        for (m in signer.methods.filter { SignedFile::class.java.isAssignableFrom(it.returnType) && !it.name.contains('$') }) {
            assertEquals("signFile", m.name, "the production file entry point has one name")
            assertFalse(m.parameterTypes.any { it == EcKeyPair::class.java }, "${m.name}${m.parameterTypes.toList()} lets a caller choose the export key")
            c.hit()
        }
        c.requireNonVacuous(1)
    }

    @Test
    fun everyProductionExportHasItsOwnKeyAndNeverATestOnlyOrNodeKey() {
        val c = Counter("production file exports")
        val signer = ManifestSigner(F.CONF, productionKeys = true)
        val ids = mutableSetOf<String>()
        val prints = mutableSetOf<String>()
        repeat(6) {
            val e = signer.signFile(inputs, F.ISSUED)
            val id = e.verified.obj.body.subject.nodeId
            assertTrue(ids.add(id), "each export has a key of its own")
            assertTrue(prints.add(e.exportFingerprint))
            assertFalse(TestOnlyKeys.isTestOnly(id), "the production path never signs under a published test key")
            assertNotEquals(F.key1.nodeId, id)
            assertEquals(PinState.ByFingerprint(CompareMethod.TYPED).wire, e.verified.pin.wire)
            c.hit()
        }
        c.requireNonVacuous(6)
    }

    @Test
    fun anInjectedKeyIsReachableOnlyThroughTheTestEntryAndAProductionVerifierStillRefusesATestOnlyOne() {
        val c = Counter("injected export keys")
        val lenient = ManifestSigner(F.CONF, productionKeys = false)
        for (k in listOf(F.key3, F.key4)) {
            val e = lenient.signFileWithInjectedKeyForTests(inputs, F.ISSUED, k.keyPair())
            assertEquals(k.nodeId, e.verified.obj.body.subject.nodeId)
            assertEquals(k.exportFingerprint, e.exportFingerprint)
            c.hit()
        }
        val strict = ManifestSigner(F.CONF, productionKeys = true)
        val ex = assertFailsWith<ManifestUnavailable> { strict.signFileWithInjectedKeyForTests(inputs, F.ISSUED, F.key3.keyPair()) }
        assertEquals(RejectCode.TEST_ONLY_KEY, ex.code)
        c.hit()
        c.requireNonVacuous(3)
    }
}

/** Follow-up (a) of builder action 9 (benchmark R4-B-01): the asom.bench/1 enums inside the manifest schema carry the three r4 end reasons. */
class BenchEndReasonSchemaTest {
    private val newReasons = listOf("BATTERY_LOW", "POWER_SAVER", "DEVICE_BUSY")

    private fun schema(): JValue = parseB(File(Repo.conformance, "manifest/schema/asom.manifest.1.schema.json").readBytes())

    private fun benchProps(): JValue = at(schema(), "\$defs", "bench", "properties")

    private fun abortReasons(): List<String> {
        val abort = at(benchProps(), "run", "properties", "abort", "oneOf") as JArray
        return strings(at(abort.items.first { it is JObject && it["properties"] != null }, "properties", "reason", "enum"))
    }

    private fun endReasons(): List<String> = strings(at(benchProps(), "sustain", "oneOf").let { (it as JArray).items.first { o -> o is JObject && o["properties"] != null } }.let { at(it, "properties", "endReason", "enum") })

    private fun doc(reason: String): BenchDoc {
        val b = F.bench
        return b.copy(run = b.run.copy(abort = BAbort(reason, 400_000L)), sustain = b.sustain!!.copy(endReason = reason))
    }

    @Test
    fun theSchemaEnumsAreTheBenchCoreEnumsExactly() {
        val c = Counter("schema end reason enums")
        for (r in newReasons) {
            assertTrue(r in abortReasons(), "run.abort.reason lacks $r")
            assertTrue(r in endReasons(), "sustain.endReason lacks $r")
            c.hit()
        }
        assertEquals(BenchEnums.abortReasons, abortReasons().toSet(), "run.abort.reason enum")
        assertEquals(BenchEnums.endReasons, endReasons().toSet(), "sustain.endReason enum")
        c.requireNonVacuous(3)
    }

    @Test
    fun aSignedManifestEmbeddingEachNewEndReasonVerifies() {
        val c = Counter("manifests with new end reasons")
        for (reason in newReasons) {
            val own = F.verify(F.container(DocSpec(F.payloadOf(F.ownObj(F.ownBody(inputs = F.inputs(doc(reason))))))), F.ctxMesh())
            assertTrue(own is Verified, "own manifest with $reason: ${(own as? Rejected)?.let { "${it.code} ${it.step} ${it.detail}" }}")
            val file = F.verify(F.container(DocSpec(F.payloadOf(F.fileObj(F.fileBody(inputs = F.inputs(doc(reason))))), signKey = F.key3)), F.ctxFile())
            assertTrue(file is Verified, "file manifest with $reason: ${(file as? Rejected)?.let { "${it.code} ${it.step} ${it.detail}" }}")
            c.hit()
        }
        c.requireNonVacuous(3)
    }

    @Test
    fun anUnknownEndReasonInEitherPlaceIsSchemaInvalid() {
        val c = Counter("unknown end reasons")
        val base = F.ownObj(F.ownBody(inputs = F.inputs(doc("BATTERY_LOW"))))
        for (path in listOf(listOf("body", "bench", "run", "abort", "reason"), listOf("body", "bench", "sustain", "endReason"))) {
            for (bad in listOf("BATTERY_WARM", "battery_low", "")) {
                val r = F.verify(F.container(DocSpec(F.payloadOf(JEdit.set(base, path, JString(bad))))), F.ctxMesh())
                assertEquals(RejectCode.SCHEMA_INVALID, F.rejectOf(r), "$path = '$bad'")
                c.hit()
            }
        }
        c.requireNonVacuous(6)
    }
}
