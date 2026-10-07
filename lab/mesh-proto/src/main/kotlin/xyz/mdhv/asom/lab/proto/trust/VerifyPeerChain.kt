package xyz.mdhv.asom.lab.proto.trust

import java.security.PublicKey
import java.time.Instant
import xyz.mdhv.asom.lab.manifest.Es256
import xyz.mdhv.asom.lab.manifest.SigCodec
import xyz.mdhv.asom.lab.manifest.Spki
import xyz.mdhv.asom.lab.policy.PeerStatus

/** What the registry says about a pin. Anything that is not a clean, known row is a denial in every mode (trust.md 4.7, law L5). */
sealed interface StatusLookup {
    data object Absent : StatusLookup
    data class Known(val status: PeerStatus) : StatusLookup
    data class Corrupt(val raw: Int) : StatusLookup
    data object Unreadable : StatusLookup
}

fun interface PinStatusSource {
    fun statusOf(pin: Pin): StatusLookup
}

/** The modes of trust.md 3.2. The two server modes are chosen by [PeerChainVerifier.verifyServer]. */
sealed interface ChainMode {
    data class ExpectPaired(val pin: Pin) : ChainMode
    data class ExpectPairing(val pinFromQr: Pin) : ChainMode
    data object EstablishedServer : ChainMode
    data object PairingServer : ChainMode
}

/**
 * Every refusal of the verifier. All of them reach the peer as the same TLS alert ([ALERT]); the code is for the local ledger and the vectors.
 * Nothing in a code or in a [ChainVerdict.Rejected.why] ever carries a peer-authored string.
 */
enum class ChainReject {
    CHAIN_LENGTH, CERT_MALFORMED, KEY_UNSUPPORTED, SIG_ALG_UNSUPPORTED, UNKNOWN_CRITICAL_EXTENSION,
    EXTENSION_MISSING, EXTENSION_INVALID, NODE_NOT_CA, PATHLEN_VIOLATION, LEAF_IS_CA, LEAF_KEY_USAGE, LEAF_EKU,
    ISSUER_MISMATCH, AKI_MISMATCH, BAD_SIGNATURE, CLOCK_SKEW, TEST_ONLY_KEY,
    PIN_MISMATCH, PIN_UNKNOWN, PIN_SUSPENDED, PIN_REVOKED, PAIRING_WINDOW_CLOSED, REGISTRY_UNREADABLE, INTERNAL,
}

sealed interface ChainVerdict {
    /** [mode] is the mode that admitted the peer (for a server this is the one it selected). */
    class Accepted(val pin: Pin, val mode: ChainMode) : ChainVerdict

    class Rejected(val code: ChainReject, val why: String) : ChainVerdict {
        val alert: String get() = PeerChainVerifier.ALERT
    }
}

/**
 * `verifyPeerChain` (trust.md 3.2): the one function both ends of every connection use. It returns a typed verdict, never a boolean, and
 * it does not throw on any input: a certificate that cannot be read, a registry that cannot be read and an unexpected exception are all
 * refusals. The chain is exactly `[leaf, node]` as DER. The TLS stack checks CertificateVerify against the leaf key; this function ties the leaf to the node
 * key and the node key to a pin and to the registry.
 */
object PeerChainVerifier {
    const val ALERT = "certificate_unknown"
    const val SKEW_SEC = 2L * 3600

    fun verify(
        chain: List<ByteArray>,
        mode: ChainMode,
        now: Instant,
        registry: PinStatusSource,
        pairingWindowOpen: () -> Boolean,
        productionKeys: Boolean = true,
    ): ChainVerdict = guarded {
        when (val a = analyse(chain, now, productionKeys)) {
            is Analysis.Bad -> a.verdict
            is Analysis.Good -> applyMode(a.pin, mode, lookup(registry, a.pin), pairingWindowOpen)
        }
    }

    /**
     * The server side: ESTABLISHED_SERVER, unless the pin is unknown and a pairing window is open, in which case PAIRING_SERVER
     * (trust.md 3.2). The chain is analysed once; the mode then decides what the registry must say.
     */
    fun verifyServer(
        chain: List<ByteArray>,
        now: Instant,
        registry: PinStatusSource,
        pairingWindowOpen: () -> Boolean,
        productionKeys: Boolean = true,
    ): ChainVerdict = guarded {
        when (val a = analyse(chain, now, productionKeys)) {
            is Analysis.Bad -> a.verdict
            is Analysis.Good -> {
                val status = lookup(registry, a.pin)
                val mode = if (status is StatusLookup.Absent && windowOpen(pairingWindowOpen)) ChainMode.PairingServer else ChainMode.EstablishedServer
                applyMode(a.pin, mode, status, pairingWindowOpen)
            }
        }
    }

    private inline fun guarded(block: () -> ChainVerdict): ChainVerdict = try {
        block()
    } catch (e: Exception) {
        reject(ChainReject.INTERNAL, "unexpected ${e::class.simpleName}")
    } catch (e: StackOverflowError) {
        reject(ChainReject.INTERNAL, "unexpected StackOverflowError")
    }

    private fun reject(code: ChainReject, why: String) = ChainVerdict.Rejected(code, why)

    private fun lookup(registry: PinStatusSource, pin: Pin): StatusLookup = try {
        registry.statusOf(pin)
    } catch (e: Exception) {
        StatusLookup.Unreadable
    }

    private fun windowOpen(probe: () -> Boolean): Boolean = try {
        probe()
    } catch (e: Exception) {
        false
    }

    private fun applyMode(pin: Pin, mode: ChainMode, status: StatusLookup, pairingWindowOpen: () -> Boolean): ChainVerdict {
        when (mode) {
            is ChainMode.ExpectPaired -> if (!pin.equalsConstantTime(mode.pin)) return reject(ChainReject.PIN_MISMATCH, "the node key is not the expected pin")
            is ChainMode.ExpectPairing -> if (!pin.equalsConstantTime(mode.pinFromQr)) return reject(ChainReject.PIN_MISMATCH, "the node key is not the pin from the QR")
            ChainMode.EstablishedServer, ChainMode.PairingServer -> Unit
        }
        if (mode == ChainMode.PairingServer && !windowOpen(pairingWindowOpen)) return reject(ChainReject.PAIRING_WINDOW_CLOSED, "no pairing window is open")
        val needsPaired = mode is ChainMode.ExpectPaired || mode == ChainMode.EstablishedServer
        when (status) {
            StatusLookup.Unreadable -> return reject(ChainReject.REGISTRY_UNREADABLE, "the registry could not be read")
            is StatusLookup.Corrupt -> return reject(ChainReject.REGISTRY_UNREADABLE, "the registry holds a status this code does not know")
            StatusLookup.Absent -> if (needsPaired) return reject(ChainReject.PIN_UNKNOWN, "no registry row for this pin")
            is StatusLookup.Known -> when (status.status) {
                PeerStatus.PAIRED -> Unit
                PeerStatus.SUSPENDED -> if (needsPaired) return reject(ChainReject.PIN_SUSPENDED, "the peer is suspended")
                PeerStatus.REVOKED -> return reject(ChainReject.PIN_REVOKED, "the peer is revoked")
            }
        }
        return ChainVerdict.Accepted(pin, mode)
    }

    private sealed interface Analysis {
        class Good(val pin: Pin) : Analysis
        class Bad(val verdict: ChainVerdict.Rejected) : Analysis
    }

    private fun bad(code: ChainReject, why: String): Analysis = Analysis.Bad(reject(code, why))

    private fun strictKey(spki: ByteArray): PublicKey? = Spki.strict(spki)

    private fun critical(c: ParsedCert): Extension? = c.extensions.firstOrNull { it.critical && it.oid !in KNOWN }

    private val KNOWN = setOf(Oids.BASIC_CONSTRAINTS, Oids.KEY_USAGE, Oids.EXT_KEY_USAGE, Oids.SUBJECT_KEY_ID, Oids.AUTHORITY_KEY_ID)

    private fun analyse(chain: List<ByteArray>, now: Instant, productionKeys: Boolean): Analysis {
        if (chain.size != 2) return bad(ChainReject.CHAIN_LENGTH, "the chain has ${chain.size} certificates; exactly [leaf, node] is required")
        val leaf: ParsedCert
        val node: ParsedCert
        try {
            leaf = ParsedCert.parse(chain[0])
            node = ParsedCert.parse(chain[1])
        } catch (e: DerException) {
            return bad(ChainReject.CERT_MALFORMED, "a certificate is not well-formed DER of the template shape")
        }

        val nodeKey = strictKey(node.spki)
        val leafKey = strictKey(leaf.spki)
        if (nodeKey == null || leafKey == null) return bad(ChainReject.KEY_UNSUPPORTED, "a key is not an uncompressed P-256 public key")

        val ecdsaSha256 = CertTemplates.ECDSA_SHA256_ALG_ID
        for (c in listOf(node, leaf)) {
            if (!c.signatureAlgorithm.contentEquals(ecdsaSha256) || !c.tbsSignatureAlgorithm.contentEquals(ecdsaSha256)) {
                return bad(ChainReject.SIG_ALG_UNSUPPORTED, "a certificate is not signed with ecdsa-with-SHA256")
            }
        }
        for (c in listOf(node, leaf)) {
            c.duplicateExtension?.let { return bad(ChainReject.EXTENSION_INVALID, "an extension appears twice") }
            critical(c)?.let { return bad(ChainReject.UNKNOWN_CRITICAL_EXTENSION, "a critical extension is not understood") }
        }

        val nodeBc = node.extension(Oids.BASIC_CONSTRAINTS) ?: return bad(ChainReject.EXTENSION_MISSING, "the node certificate has no basicConstraints")
        val nodeKu = node.extension(Oids.KEY_USAGE) ?: return bad(ChainReject.EXTENSION_MISSING, "the node certificate has no keyUsage")
        val nodeSki = node.extension(Oids.SUBJECT_KEY_ID) ?: return bad(ChainReject.EXTENSION_MISSING, "the node certificate has no subjectKeyIdentifier")
        val leafBc = leaf.extension(Oids.BASIC_CONSTRAINTS) ?: return bad(ChainReject.EXTENSION_MISSING, "the leaf has no basicConstraints")
        val leafKu = leaf.extension(Oids.KEY_USAGE) ?: return bad(ChainReject.EXTENSION_MISSING, "the leaf has no keyUsage")
        val leafEku = leaf.extension(Oids.EXT_KEY_USAGE) ?: return bad(ChainReject.EXTENSION_MISSING, "the leaf has no extKeyUsage")
        val leafAki = leaf.extension(Oids.AUTHORITY_KEY_ID) ?: return bad(ChainReject.EXTENSION_MISSING, "the leaf has no authorityKeyIdentifier")
        if (!nodeBc.critical || !nodeKu.critical || !leafBc.critical || !leafKu.critical) return bad(ChainReject.EXTENSION_INVALID, "basicConstraints and keyUsage must be critical")

        val nBc: BasicConstraints
        val nKu: Set<Int>
        val nSki: ByteArray
        val lBc: BasicConstraints
        val lKu: Set<Int>
        val lEku: List<String>
        val lAki: ByteArray
        try {
            nBc = BasicConstraints.parse(nodeBc.value)
            nKu = KeyUsageParser.parse(nodeKu.value)
            nSki = KeyIdParsers.subjectKeyId(nodeSki.value)
            lBc = BasicConstraints.parse(leafBc.value)
            lKu = KeyUsageParser.parse(leafKu.value)
            lEku = ExtKeyUsageParser.parse(leafEku.value)
            lAki = KeyIdParsers.authorityKeyId(leafAki.value)
        } catch (e: DerException) {
            return bad(ChainReject.EXTENSION_INVALID, "an extension value is not well-formed")
        }

        if (!nBc.ca || nKu != setOf(KeyUsageBits.KEY_CERT_SIGN)) return bad(ChainReject.NODE_NOT_CA, "the node certificate is not a CA with exactly keyCertSign")
        if (nBc.pathLen != 0L) return bad(ChainReject.PATHLEN_VIOLATION, "the node certificate must carry pathLen 0")
        if (lBc.ca || KeyUsageBits.KEY_CERT_SIGN in lKu) return bad(ChainReject.LEAF_IS_CA, "the leaf is a CA or may sign certificates")
        if (lBc.pathLen != null) return bad(ChainReject.EXTENSION_INVALID, "a non-CA leaf carries a path length")
        if (KeyUsageBits.DIGITAL_SIGNATURE !in lKu) return bad(ChainReject.LEAF_KEY_USAGE, "the leaf keyUsage lacks digitalSignature")
        if (Oids.SERVER_AUTH !in lEku || Oids.CLIENT_AUTH !in lEku) return bad(ChainReject.LEAF_EKU, "the leaf must allow both serverAuth and clientAuth")

        if (!leaf.issuerDer.contentEquals(node.subjectDer) || !node.issuerDer.contentEquals(node.subjectDer)) {
            return bad(ChainReject.ISSUER_MISMATCH, "the leaf issuer is not the node subject, or the node is not self-issued")
        }
        if (!MessageDigestEq.equal(lAki, nSki)) return bad(ChainReject.AKI_MISMATCH, "the leaf authorityKeyIdentifier is not the node subjectKeyIdentifier")

        if (!signatureOk(node, nodeKey) || !signatureOk(leaf, nodeKey)) return bad(ChainReject.BAD_SIGNATURE, "a certificate signature does not verify under the node key")

        val t = now.epochSecond
        if (t < leaf.notBefore - SKEW_SEC) return bad(ChainReject.CLOCK_SKEW, "the leaf is not yet valid")
        if (t > leaf.notAfter + SKEW_SEC) return bad(ChainReject.CLOCK_SKEW, "the leaf has expired")

        val pinImport = Pin.fromSpki(node.spki, productionKeys)
        return when (pinImport) {
            is PinImport.Ok -> Analysis.Good(pinImport.pin)
            is PinImport.Reject -> bad(if (pinImport.code == PinReject.TEST_ONLY_KEY) ChainReject.TEST_ONLY_KEY else ChainReject.KEY_UNSUPPORTED, "the node key cannot be a pin")
        }
    }

    private fun signatureOk(c: ParsedCert, key: PublicKey): Boolean {
        val raw = (SigCodec.derToRaw(c.signatureBits) as? SigCodec.Result.Ok)?.bytes ?: return false
        if (!Es256.rangeOk(raw)) return false
        return Es256.verify(key, c.tbs, raw)
    }
}

private object MessageDigestEq {
    fun equal(a: ByteArray, b: ByteArray): Boolean = a.size == b.size && java.security.MessageDigest.isEqual(a, b)
}
