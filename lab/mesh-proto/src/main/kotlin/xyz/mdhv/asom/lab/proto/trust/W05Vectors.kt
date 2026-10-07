package xyz.mdhv.asom.lab.proto.trust

import java.time.Instant
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.manifest.Es256
import xyz.mdhv.asom.lab.manifest.Spki
import xyz.mdhv.asom.lab.manifest.TestOnlyKeys
import xyz.mdhv.asom.lab.policy.PeerStatus

/**
 * Runs one W05 vector (fingerprints, strict pins, the two certificate templates and `verifyPeerChain`; trust.md 2.3, 2.4, 3.2, 15; LAB_SPEC 7.4).
 * It is the one place that turns a vector's JSON into calls on the implementation, so `:mesh-proto` tests and the conformance runner share it.
 * Evidence label: LAB, oracle: self.
 */
object W05Vectors {
    /** The laws a complete W05 run must have exercised (LAB_SPEC R10). `chain-reject-*` has one entry per [ChainReject] except the defensive INTERNAL. */
    val requiredLaws: Set<String> =
        setOf("pin-derive", "fingerprint", "spki-strict", "test-only-key", "pin-compare", "cert-profile", "chain-valid", "chain-negatives", "registry-L8") +
            ChainReject.entries.filter { it != ChainReject.INTERNAL }.map { "chain-reject-$it" }

    fun evaluate(input: JObject): VectorEval = when (val kind = V.str(input, "kind")) {
        "pinDerive" -> pinDerive(input)
        "pinTag" -> pinTag(input)
        "spkiImport" -> spkiImport(input)
        "pinCompare" -> pinCompare(input)
        "template" -> template(input)
        "chain" -> chain(input)
        else -> throw VectorShapeException("unknown W05 vector kind '$kind'")
    }

    private fun pinDerive(i: JObject): VectorEval {
        val spki = V.hex(i, "spkiHex")
        val imp = Pin.fromSpki(spki, productionKeys = false) as? PinImport.Ok ?: throw VectorLawViolation("a strict SPKI was not imported")
        val pin = imp.pin
        if (pin.nodeTag != Spki.nodeTag(spki)) throw VectorLawViolation("nodeTag differs from the :manifest derivation")
        if (pin.display != Spki.displayFingerprint(spki)) throw VectorLawViolation("display fingerprint differs from the :manifest derivation")
        if (!pin.bytes().contentEquals(Spki.pin(spki))) throw VectorLawViolation("pin differs from SHA-256 of the SPKI")
        val v = jobj("pinHex" to jstr(Hex.encode(pin.bytes())), "nodeId" to jstr(pin.nodeId), "nodeTag" to jstr(pin.nodeTag), "display" to jstr(pin.display))
        return VectorEval(VectorOutcome.Ok(v), listOf("pin-derive", "fingerprint"))
    }

    private fun pinTag(i: JObject): VectorEval {
        val pin = Pin.ofHash(V.hex(i, "pinHex"))
        if (Pin.fromNodeId(pin.nodeId)?.equalsConstantTime(pin) != true) throw VectorLawViolation("nodeId does not decode back to the pin")
        return VectorEval(VectorOutcome.Ok(jobj("nodeId" to jstr(pin.nodeId), "nodeTag" to jstr(pin.nodeTag), "display" to jstr(pin.display))), listOf("fingerprint"))
    }

    private fun spkiImport(i: JObject): VectorEval = when (val r = Pin.fromSpki(V.hex(i, "spkiHex"), V.bool(i, "productionKeys"))) {
        is PinImport.Ok -> VectorEval(VectorOutcome.Ok(jobj("pinHex" to jstr(Hex.encode(r.pin.bytes())))), listOf("spki-strict"))
        is PinImport.Reject -> VectorEval(VectorOutcome.Reject(r.code.name), listOf("spki-strict") + if (r.code == PinReject.TEST_ONLY_KEY) listOf("test-only-key") else emptyList())
    }

    private fun pinCompare(i: JObject): VectorEval {
        val a = V.hex(i, "aHex")
        val b = V.hex(i, "bHex")
        val eq = Pin.constantTimeEquals(a, b)
        if (eq != Pin.constantTimeEquals(b, a)) throw VectorLawViolation("pin comparison is not symmetric")
        if (eq && !a.contentEquals(b)) throw VectorLawViolation("unequal values compared equal")
        return VectorEval(VectorOutcome.Ok(jobj("equal" to JBool(eq))), listOf("pin-compare"))
    }

    private fun template(i: JObject): VectorEval {
        val role = V.str(i, "role")
        val nik = TestOnlyKeys.key(V.str(i, "nikKey"))
        val serial = V.hex(i, "serialHex")
        val t = V.long(i, "epochSec")
        val sig = V.hex(i, "sigRawHex")
        val tbs: ByteArray
        val leafKey: TestOnlyKeys.Entry?
        if (role == "node") {
            tbs = CertTemplates.nodeTbs(nik.spki, serial, t)
            leafKey = null
        } else {
            leafKey = TestOnlyKeys.key(V.str(i, "leafKey"))
            tbs = CertTemplates.leafTbs(nik.spki, leafKey.spki, serial, t)
        }
        val cert = CertTemplates.assemble(tbs, sig)
        if (!Es256.verify(Spki.strict(nik.spki)!!, tbs, sig)) throw VectorLawViolation("the golden signature does not verify under the node key")
        profile(role, cert, nik.spki, leafKey?.spki, t)
        val v = jobj("tbsHex" to jstr(Hex.encode(tbs)), "certHex" to jstr(Hex.encode(cert)), "skiHex" to jstr(Hex.encode(CertTemplates.subjectKeyId(nik.spki))))
        return VectorEval(VectorOutcome.Ok(v), listOf("cert-profile"))
    }

    /** Reads the produced certificate back with the strict parser and asserts every row of the trust.md 2.4 tables. */
    private fun profile(role: String, cert: ByteArray, nikSpki: ByteArray, leafSpki: ByteArray?, t: Long) {
        fun need(ok: Boolean, what: String) { if (!ok) throw VectorLawViolation("template profile: $what") }
        val c = ParsedCert.parse(cert)
        need(c.signatureAlgorithm.contentEquals(CertTemplates.ECDSA_SHA256_ALG_ID) && c.tbsSignatureAlgorithm.contentEquals(CertTemplates.ECDSA_SHA256_ALG_ID), "ecdsa-with-SHA256 both times")
        need(c.duplicateExtension == null, "no duplicate extension")
        val bc = BasicConstraints.parse(c.extension(Oids.BASIC_CONSTRAINTS)!!.value)
        val ku = KeyUsageParser.parse(c.extension(Oids.KEY_USAGE)!!.value)
        need(c.extension(Oids.BASIC_CONSTRAINTS)!!.critical && c.extension(Oids.KEY_USAGE)!!.critical, "basicConstraints and keyUsage critical")
        val nodeTag = Spki.nodeTag(nikSpki)
        if (role == "node") {
            need(bc.ca && bc.pathLen == 0L, "CA:TRUE pathLen 0")
            need(ku == setOf(KeyUsageBits.KEY_CERT_SIGN), "keyUsage keyCertSign only")
            need(c.issuerDer.contentEquals(c.subjectDer), "issuer = subject")
            need(c.notBefore == t - 3600 && c.notAfter == CertTemplates.NODE_NOT_AFTER_EPOCH_SEC, "notBefore = creation - 1 h, notAfter = 99991231235959Z")
            need(c.spki.contentEquals(nikSpki), "SPKI is the NIK")
            need(c.extension(Oids.SUBJECT_KEY_ID) != null && c.extensions.size == 3, "exactly BC, KU, SKI")
            need(String(c.subjectDer, Charsets.UTF_8).contains("asom-node $nodeTag"), "CN=asom-node <nodeTag>")
        } else {
            need(!bc.ca && bc.pathLen == null, "CA:FALSE")
            need(ku == setOf(KeyUsageBits.DIGITAL_SIGNATURE), "keyUsage digitalSignature only")
            need(ExtKeyUsageParser.parse(c.extension(Oids.EXT_KEY_USAGE)!!.value) == listOf(Oids.SERVER_AUTH, Oids.CLIENT_AUTH), "serverAuth, clientAuth")
            need(KeyIdParsers.authorityKeyId(c.extension(Oids.AUTHORITY_KEY_ID)!!.value).contentEquals(CertTemplates.subjectKeyId(nikSpki)), "AKI = node SKI")
            need(c.notBefore == t - 3600 && c.notAfter == t + 14L * 86400, "now - 1 h .. now + 14 d")
            need(c.spki.contentEquals(leafSpki!!), "SPKI is the leaf key")
            need(c.extensions.size == 4, "exactly BC, KU, EKU, AKI")
            need(String(c.subjectDer, Charsets.UTF_8).contains("asom-session $nodeTag") && String(c.issuerDer, Charsets.UTF_8).contains("asom-node $nodeTag"), "names")
        }
    }

    private fun chain(i: JObject): VectorEval {
        val chain = V.strings(i, "chainHex").map { Hex.decode(it) }
        val modeObj = V.obj(i, "mode")
        val now = Instant.ofEpochSecond(V.long(i, "nowEpochSec"))
        val default = V.str(i, "registryDefault")
        val table = V.obj(i, "registry").members.associate { (k, v) -> k to (v as? xyz.mdhv.asom.lab.json.JString)?.value.orEmpty() }
        val window = V.bool(i, "windowOpen")
        val production = V.bool(i, "productionKeys")
        val registry = PinStatusSource { pin ->
            when (table[pin.nodeId] ?: default) {
                "PAIRED" -> StatusLookup.Known(PeerStatus.PAIRED)
                "SUSPENDED" -> StatusLookup.Known(PeerStatus.SUSPENDED)
                "REVOKED" -> StatusLookup.Known(PeerStatus.REVOKED)
                "CORRUPT" -> StatusLookup.Corrupt(9)
                "ABSENT" -> StatusLookup.Absent
                "UNREADABLE" -> StatusLookup.Unreadable
                else -> throw VectorShapeException("unknown registry state")
            }
        }
        fun pinOf(): Pin = Pin.fromNodeId(V.str(modeObj, "pin")) ?: throw VectorShapeException("mode.pin is not a nodeId")
        val verdict = when (val kind = V.str(modeObj, "kind")) {
            "expectPaired" -> PeerChainVerifier.verify(chain, ChainMode.ExpectPaired(pinOf()), now, registry, { window }, production)
            "expectPairing" -> PeerChainVerifier.verify(chain, ChainMode.ExpectPairing(pinOf()), now, registry, { window }, production)
            "established" -> PeerChainVerifier.verify(chain, ChainMode.EstablishedServer, now, registry, { window }, production)
            "pairingServer" -> PeerChainVerifier.verify(chain, ChainMode.PairingServer, now, registry, { window }, production)
            "server" -> PeerChainVerifier.verifyServer(chain, now, registry, { window }, production)
            else -> throw VectorShapeException("unknown mode '$kind'")
        }
        return when (verdict) {
            is ChainVerdict.Accepted -> {
                val name = when (verdict.mode) {
                    is ChainMode.ExpectPaired -> "expectPaired"
                    is ChainMode.ExpectPairing -> "expectPairing"
                    ChainMode.EstablishedServer -> "established"
                    ChainMode.PairingServer -> "pairingServer"
                }
                VectorEval(VectorOutcome.Ok(jobj("pin" to jstr(verdict.pin.nodeId), "mode" to jstr(name))), listOf("chain-valid", "registry-L8"))
            }
            is ChainVerdict.Rejected -> {
                if (verdict.alert != "certificate_unknown") throw VectorLawViolation("a refusal must reach the peer as certificate_unknown")
                VectorEval(VectorOutcome.Reject(verdict.code.name), listOf("chain-negatives", "registry-L8", "chain-reject-${verdict.code}"))
            }
        }
    }
}
