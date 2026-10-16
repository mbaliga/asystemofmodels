package xyz.mdhv.asom.lab.proto.pairing

import xyz.mdhv.asom.lab.json.B64Result
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.policy.PeerStatus
import xyz.mdhv.asom.lab.proto.trust.AuthDecision
import xyz.mdhv.asom.lab.proto.trust.InMemoryPeerStore
import xyz.mdhv.asom.lab.proto.trust.NetworkEvent
import xyz.mdhv.asom.lab.proto.trust.OutboundDecision
import xyz.mdhv.asom.lab.proto.trust.PairingCeremony
import xyz.mdhv.asom.lab.proto.trust.PeerClass
import xyz.mdhv.asom.lab.proto.trust.PeerRegistry
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.trust.RegistryResult
import xyz.mdhv.asom.lab.proto.trust.RouteCeiling
import xyz.mdhv.asom.lab.proto.trust.Scope
import xyz.mdhv.asom.lab.proto.trust.SessionDecision
import xyz.mdhv.asom.lab.proto.trust.StatusChange
import xyz.mdhv.asom.lab.proto.trust.StatusCodes
import xyz.mdhv.asom.lab.proto.trust.StatusLookup
import xyz.mdhv.asom.lab.proto.trust.StoredRow
import xyz.mdhv.asom.lab.proto.trust.V
import xyz.mdhv.asom.lab.proto.trust.VectorEval
import xyz.mdhv.asom.lab.proto.trust.VectorLawViolation
import xyz.mdhv.asom.lab.proto.trust.VectorOutcome
import xyz.mdhv.asom.lab.proto.trust.VectorShapeException
import xyz.mdhv.asom.lab.proto.trust.jlist
import xyz.mdhv.asom.lab.proto.trust.jobj
import xyz.mdhv.asom.lab.proto.trust.jstr

/**
 * Runs one W04 vector (pairing: QR grammar, proof, SAS, transcript, the four pairing messages, the two state machines and the peer registry;
 * trust.md 4.2 to 4.7; LAB_SPEC 7.3). Evidence label: LAB, oracle: self.
 *
 * Since revision 4 (R4-T-10) a vector without a `profile` member runs the R3 machines and one without an `expiry` member runs the no-expiry QR reading; the first-built
 * readings are selected explicitly, with `"profile": "r0"` and `"expiry": "enforce"`, and each such vector counts under `pairing-r0-compat` or `qr-expiry-enforce`.
 */
object W04Vectors {
    val requiredLaws: Set<String> =
        setOf(
            "qr-grammar", "qr-roundtrip", "proof-sas-transcript", "proof-invalid", "pin-swap", "nonce-swap", "pair-message-ok", "pair-message-reject", "commit-no-locseed",
            "pairing-fsm-D", "pairing-fsm-S", "pairing-L1", "registry-L6", "registry-L7", "registry-L1", "registry-L2", "registry-L3", "registry-L4", "registry-L5",
            "registry-goaway", "registry-per-direction", "registry-fail-closed",
            "pairing-typed-code", "pairing-commit-reveal", "qr-expiry-none", "pairing-connection-binding", "pairing-window-expired-on-d", "pairing-r0-compat",
            "qr-expiry-enforce", "commit-function", "pair-decision-reveal",
        ) + QrReject.entries.map { "qr-reject-$it" }

    fun evaluate(input: JObject): VectorEval = when (val kind = V.str(input, "kind")) {
        "qrParse" -> qrParse(input)
        "qrEncode" -> qrEncode(input)
        "proofSas" -> proofSas(input)
        "proofVerify" -> proofVerify(input)
        "commit" -> commit(input)
        "message" -> message(input)
        "fsm" -> if (V.str(input, "machine") == "D") fsmD(input) else fsmS(input)
        "registry" -> registry(input)
        else -> throw VectorShapeException("unknown W04 vector kind '$kind'")
    }

    // ---------------------------------------------------------------- QR

    private fun payloadValue(p: QrPayload): JObject = jobj(
        "k" to jstr(p.pinD.nodeId), "a" to jlist(p.endpoints.map { it.toString() }), "s" to jstr(Base64Strict.encodeUrlNoPad(p.secret)), "x" to JInt(p.expirySec), "n" to jstr(p.name),
    )

    private fun policyOf(i: JObject): AddrPolicy = when (V.str(i, "policy")) {
        "mesh" -> AddrPolicy.MESH
        "loopbackForTests" -> AddrPolicy.MESH_PLUS_LOOPBACK_FOR_TESTS
        else -> throw VectorShapeException("unknown address policy")
    }

    private val expiryReadings = setOf(QrReject.EXPIRED, QrReject.EXPIRY_TOO_FAR)

    /** `"expiry"` is `"none"` (design T8, the default since R4-T-10) or `"enforce"` (the first-built reading, R0_COMPAT); any other value is a malformed vector. */
    private fun expiryOf(i: JObject): QrExpiry = when (val v = V.strOrNull(i, "expiry")) {
        null, "none" -> QrExpiry.NONE
        "enforce" -> QrExpiry.ENFORCE
        else -> throw VectorShapeException("unknown expiry reading '$v'")
    }

    /** `"profile"` is `"r3"` (the default since R4-T-10) or `"r0"` (R0_COMPAT, the machines as first built); any other value is a malformed vector. */
    private fun profileOf(i: JObject): PairProfile = when (val v = V.strOrNull(i, "profile")) {
        null, "r3" -> PairProfile.R3
        "r0" -> PairProfile.R0_COMPAT
        else -> throw VectorShapeException("unknown pairing profile '$v'")
    }

    /**
     * The laws of a QR parse under [expiry]. Under NONE the verdict must never be an expiry verdict, and the law `qr-expiry-none` counts only a URI the enforcing
     * reading would refuse for its clock (so the case exercises the difference); under ENFORCE the case counts under `qr-expiry-enforce`.
     */
    private fun expiryLaws(text: String, now: Long, policy: AddrPolicy, expiry: QrExpiry, verdict: QrParse): List<String> {
        if (expiry == QrExpiry.ENFORCE) return listOf("qr-expiry-enforce")
        if (verdict is QrParse.Reject && verdict.code in expiryReadings) throw VectorLawViolation("a parse without the expiry gate produced the clock verdict ${verdict.code}")
        val enforced = QrUri.parse(text, now, policy, QrExpiry.ENFORCE)
        return if (enforced is QrParse.Reject && enforced.code in expiryReadings) listOf("qr-expiry-none") else emptyList()
    }

    private fun qrParse(i: JObject): VectorEval {
        val now = V.long(i, "nowSec")
        val policy = policyOf(i)
        val expiry = expiryOf(i)
        val text = V.str(i, "uri")
        val r = QrUri.parse(text, now, policy, expiry)
        val extra = expiryLaws(text, now, policy, expiry, r)
        return when (r) {
            is QrParse.Ok -> {
                val again = QrUri.parse(QrUri.encode(r.payload), now, policy, expiry)
                if (again !is QrParse.Ok || payloadValue(again.payload) != payloadValue(r.payload)) throw VectorLawViolation("encode(parse(uri)) does not parse back to the same payload")
                VectorEval(VectorOutcome.Ok(payloadValue(r.payload)), listOf("qr-grammar", "qr-roundtrip") + extra)
            }
            is QrParse.Reject -> VectorEval(VectorOutcome.Reject(r.code.name), listOf("qr-grammar", "qr-reject-${r.code}") + extra)
        }
    }

    private fun qrEncode(i: JObject): VectorEval {
        val pin = Pin.fromNodeId(V.str(i, "k")) ?: throw VectorShapeException("k is not a nodeId")
        val secret = (Base64Strict.decodeUrlNoPad(V.str(i, "s")) as B64Result.Ok).bytes
        val eps = V.strings(i, "a").map { Endpoints.parse(it) ?: throw VectorShapeException("endpoint '$it'") }
        val payload = QrPayload(pin, eps, secret, V.long(i, "x"), V.str(i, "n"))
        val text = QrUri.encode(payload)
        val back = QrUri.parse(text, V.long(i, "nowSec"), AddrPolicy.MESH)
        if (back !is QrParse.Ok || payloadValue(back.payload) != payloadValue(payload)) throw VectorLawViolation("the encoded URI does not parse back to its payload")
        return VectorEval(VectorOutcome.Ok(jobj("uri" to jstr(text))), listOf("qr-grammar", "qr-roundtrip"))
    }

    // ---------------------------------------------------------------- proof, SAS, transcript

    private fun pinField(i: JObject, key: String): Pin = Pin.fromNodeId(V.str(i, key)) ?: throw VectorShapeException("$key is not a nodeId")

    private fun b64Field(i: JObject, key: String, len: Int): ByteArray {
        val r = Base64Strict.decodeUrlNoPad(V.str(i, key)) as? B64Result.Ok ?: throw VectorShapeException("$key is not base64url")
        if (r.bytes.size != len) throw VectorShapeException("$key is not $len bytes")
        return r.bytes
    }

    private fun proofSas(i: JObject): VectorEval {
        val pinD = pinField(i, "pinD")
        val pinS = pinField(i, "pinS")
        val secret = b64Field(i, "secret", 32)
        val nS = b64Field(i, "nonceS", 32)
        val nD = b64Field(i, "nonceD", 32)
        val proof = PairCrypto.proof(secret, pinD, pinS, nS)
        val sas = PairCrypto.sas(pinD, pinS, nS, nD)
        if (!Regex("[0-9]{3} [0-9]{3}").matches(sas)) throw VectorLawViolation("the SAS is not two groups of three digits")
        if (!PairCrypto.proofValid(secret, pinD, pinS, nS, proof)) throw VectorLawViolation("a proof does not verify against itself")
        val v = jobj(
            "proof" to jstr(Base64Strict.encodeUrlNoPad(proof)), "sas" to jstr(sas), "transcriptHex" to jstr(Hex.encode(PairCrypto.transcript(pinD, pinS, nS, nD))),
        )
        return VectorEval(VectorOutcome.Ok(v), listOf("proof-sas-transcript"))
    }

    private fun proofVerify(i: JObject): VectorEval {
        val pinD = pinField(i, "pinD")
        val pinS = pinField(i, "pinS")
        val secret = b64Field(i, "secret", 32)
        val nS = b64Field(i, "nonceS", 32)
        val nD = b64Field(i, "nonceD", 32)
        val label = V.strOrNull(i, "negative").orEmpty()
        val decoded = (if (V.str(i, "presented").length == 43) Base64Strict.decodeUrlNoPad(V.str(i, "presented")) else null) as? B64Result.Ok
        val presented = decoded?.bytes ?: ByteArray(0)
        val laws = ArrayList<String>()
        if (label == "pin-swap") {
            // The vector must really be the swap: the presented bytes are the proof computed with the two pins exchanged.
            if (!presented.contentEquals(PairCrypto.proof(secret, pinS, pinD, nS))) throw VectorLawViolation("a vector labelled pin-swap does not hold the pin-swapped proof")
            laws += "pin-swap"
        }
        if (label == "nonce-swap") {
            if (!presented.contentEquals(PairCrypto.proof(secret, pinD, pinS, nD))) throw VectorLawViolation("a vector labelled nonce-swap does not hold the nonce-swapped proof")
            laws += "nonce-swap"
        }
        return if (PairCrypto.proofValid(secret, pinD, pinS, nS, presented)) {
            if (label.isNotEmpty() && label != "relay") throw VectorLawViolation("a '$label' negative was accepted")
            VectorEval(VectorOutcome.Ok(jobj("valid" to JBool(true))), listOf("proof-sas-transcript"))
        } else {
            VectorEval(VectorOutcome.Reject(Refusal.PAIRING_PROOF_INVALID.name), laws + "proof-invalid")
        }
    }

    /**
     * The commitment function of R4-T-10: `C = SHA-256("asom-pair-v1/commit" || 0x00 || nonce_S)` and whether an opening opens it. `revealed` is base64url of the
     * bytes D was given, of any length (a short opening is a mismatch, never an error); `commitment` is the value on the wire when it is not the honest one.
     */
    private fun commit(i: JObject): VectorEval {
        val nonce = b64Field(i, "nonceS", 32)
        val c = PairCrypto.commitNonce(nonce)
        if (c.size != 32 || c.contentEquals(nonce)) throw VectorLawViolation("the commitment of nonce_S is not a 32-byte digest different from the nonce")
        val onWire = V.strOrNull(i, "commitment")?.let { b64Field(i, "commitment", 32) } ?: c
        val revealedText = V.str(i, "revealed")
        val revealed = (Base64Strict.decodeUrlNoPad(revealedText) as? B64Result.Ok)?.bytes ?: ByteArray(0)
        val opens = PairCrypto.commitmentOpens(onWire, revealed)
        val v = jobj("commitment" to jstr(Base64Strict.encodeUrlNoPad(c)), "opens" to JBool(opens))
        return VectorEval(VectorOutcome.Ok(v), listOf("commit-function"))
    }

    // ---------------------------------------------------------------- messages

    private fun message(i: JObject): VectorEval {
        val type = V.str(i, "type")
        val payload = V.str(i, "payload").toByteArray(Charsets.UTF_8)
        val parsed: MsgParse<Any> = when (type) {
            "PAIR_HELLO" -> PairMessages.parseHello(payload)
            "PAIR_CHALLENGE" -> PairMessages.parseChallenge(payload)
            "PAIR_DECISION" -> PairMessages.parseDecision(payload)
            "PAIR_COMMIT" -> PairMessages.parseCommit(payload)
            "PAIR_COMMIT_ACK" -> PairMessages.parseCommitAck(payload)
            else -> throw VectorShapeException("unknown pairing message '$type'")
        }
        return when (parsed) {
            is MsgParse.Reject -> VectorEval(VectorOutcome.Reject(parsed.code), listOf("pair-message-reject"))
            is MsgParse.Ok -> {
                val v = parsed.value
                val normal = when (v) {
                    is PairHello -> PairMessages.encodeHello(v)
                    is PairChallenge -> PairMessages.encodeChallenge(v)
                    is PairDecision -> PairMessages.encodeDecision(v)
                    is PairCommit -> PairMessages.encodeCommit(v)
                    is PairCommitAck -> PairMessages.encodeCommitAck(v)
                    else -> throw VectorShapeException("unreachable")
                }
                val text = String(normal, Charsets.UTF_8)
                val laws = arrayListOf("pair-message-ok")
                if (v is PairCommit) {
                    if (text.contains("locSeed")) throw VectorLawViolation("PAIR_COMMIT still carries locSeed (withdrawn in r3)")
                    if (V.str(i, "payload").contains("locSeed")) laws += "commit-no-locseed"
                }
                if (v is PairDecision) {
                    if ((v.reveal != null) != text.contains("\"reveal\"")) throw VectorLawViolation("PAIR_DECISION re-encodes its reveal member differently from what was parsed")
                    if (v.reveal != null) laws += "pair-decision-reveal"
                }
                VectorEval(VectorOutcome.Ok(jobj("normal" to jstr(text))), laws)
            }
        }
    }

    // ---------------------------------------------------------------- state machines

    private fun statusLookup(s: String): StatusLookup = when (s) {
        "ABSENT" -> StatusLookup.Absent
        "PAIRED" -> StatusLookup.Known(PeerStatus.PAIRED)
        "SUSPENDED" -> StatusLookup.Known(PeerStatus.SUSPENDED)
        "REVOKED" -> StatusLookup.Known(PeerStatus.REVOKED)
        "CORRUPT" -> StatusLookup.Corrupt(9)
        "UNREADABLE" -> StatusLookup.Unreadable
        else -> throw VectorShapeException("unknown status '$s'")
    }

    private fun connId(o: JObject): Long = (o["connId"] as? JInt)?.value ?: 0L

    private fun dEvent(o: JObject): DEvent = when (val e = V.str(o, "e")) {
        "UserOpenWindow" -> DEvent.UserOpenWindow(V.hex(o, "secretHex"), V.long(o, "nowMs"))
        "HelloReceived" -> DEvent.HelloReceived(
            pinField(o, "pinS"), V.hex(o, "nonceSHex"), V.hex(o, "proofHex"), statusLookup(V.str(o, "status")), V.hex(o, "nonceDHex"), V.long(o, "nowMs"),
            connId(o),
        )
        "ChallengeSent" -> DEvent.ChallengeSent
        "LocalDecision" -> DEvent.LocalDecision(V.bool(o, "approve"), V.strOrNull(o, "typedCode"))
        "RemoteDecision" -> DEvent.RemoteDecision(V.bool(o, "approve"), V.strOrNull(o, "revealHex")?.let { V.hex(o, "revealHex") }, connId(o))
        "Tick" -> DEvent.Tick(V.long(o, "nowMs"))
        "UserCancel" -> DEvent.UserCancel
        "ConnectionLost" -> DEvent.ConnectionLost
        "RowDurable" -> DEvent.RowDurable(V.long(o, "nowMs"))
        "RowWriteFailed" -> DEvent.RowWriteFailed
        "AckReceived" -> DEvent.AckReceived(V.hex(o, "transcriptHex"), connId(o))
        "SecondUnknownConnection" -> DEvent.SecondUnknownConnection
        else -> throw VectorShapeException("unknown D event '$e'")
    }

    /**
     * What the R3 machine of D must do, derived here from the script and the pure functions of [PairCrypto] and not from the machine's state: the bound connection,
     * the commitment of the hello, the opening, the code typed and the wrong codes counted. A row write is expected exactly when an opening that opens the commitment
     * and a typed code equal to the SAS of that opening have both been seen on the bound connection (R4-T-10).
     */
    private class DModel(private val own: Pin, private val maxCodeTries: Int) {
        private var pinS: Pin? = null
        private var commitment: ByteArray? = null
        private var nonceD: ByteArray? = null
        private var bound = 0L
        private var opened: ByteArray? = null
        private var typed: String? = null
        private var wrong = 0

        private var accepted = false

        fun hello(e: DEvent.HelloReceived) {
            pinS = e.pinS
            commitment = e.nonceS.copyOf()
            nonceD = e.freshNonceD.copyOf()
            bound = e.connId
            opened = null
            typed = null
            wrong = 0
            accepted = true
        }

        /** A decision or an acknowledgement that arrived on a connection other than the hello's. */
        fun foreign(e: DEvent): Boolean = accepted && ((e is DEvent.RemoteDecision && e.connId != bound) || (e is DEvent.AckReceived && e.connId != bound))

        private fun has(step: DStep, f: (PairEffect) -> Boolean) = step.effects.any(f)

        private fun fail(msg: String): Nothing = throw VectorLawViolation(msg)

        /** Checks one step taken from an AWAIT state and returns the laws it exercised. */
        fun observe(e: DEvent, step: DStep): List<String> {
            val laws = ArrayList<String>()
            var rowExpected = false
            when (e) {
                is DEvent.LocalDecision -> if (e.approve) {
                    val code = e.typedCode
                    if (code == null) {
                        if (!has(step) { it is PairEffect.Warn && it.kind == "typed-code-required" } || has(step) { it is PairEffect.WritePairedRow || it is PairEffect.SendDecision }) {
                            fail("T7: an approval on D without a typed code was not ignored with a warning")
                        }
                        laws += "pairing-typed-code"
                    } else {
                        typed = code
                        rowExpected = judge(step, laws)
                    }
                }
                is DEvent.RemoteDecision -> if (e.approve) {
                    if (opened == null) {
                        val c = commitment ?: fail("a decision arrived before any hello was accepted")
                        val reveal = e.reveal
                        laws += "pairing-commit-reveal"
                        if (reveal == null || !PairCrypto.commitmentOpens(c, reveal)) {
                            val aborted = step.state is DState.Closed && has(step) { it is PairEffect.Abort && it.reason == AbortReason.PROTOCOL && it.refusal == Refusal.PROTOCOL_ERROR }
                            if (!aborted || has(step) { it is PairEffect.WritePairedRow }) fail("T10: an opening that does not open the commitment did not end the ceremony")
                        } else {
                            opened = reveal.copyOf()
                            rowExpected = judge(step, laws)
                        }
                    }
                }
                else -> Unit
            }
            if (has(step) { it is PairEffect.WritePairedRow } != rowExpected) fail("T7/T10: a row write was ${if (rowExpected) "expected" else "requested"} without both the opening and the matching typed code")
            return laws
        }

        /** Applies the judgement once both an opening and a code are known. Returns whether it is a match that requires the row write. */
        private fun judge(step: DStep, laws: MutableList<String>): Boolean {
            val reveal = opened
            val code = typed
            if (reveal == null || code == null) {
                if (has(step) { it is PairEffect.WritePairedRow || it is PairEffect.Warn }) fail("T7: a verdict was given before both the opening and the code were known")
                return false
            }
            laws += "pairing-typed-code"
            val sas = PairCrypto.sas(own, pinS ?: fail("no pin"), reveal, nonceD ?: fail("no nonce"))
            if (PairCrypto.typedCodeMatches(sas, code)) {
                if (!has(step) { it is PairEffect.SendDecision && it.approve && it.reveal == null }) fail("T7: a matching code did not send D's approval")
                return true
            }
            wrong++
            typed = null
            if (wrong >= maxCodeTries) {
                if (!has(step) { it is PairEffect.Abort && it.reason == AbortReason.TRIES_EXHAUSTED } || !has(step) { it is PairEffect.SendDecision && !it.approve }) {
                    fail("T7: the last wrong code did not decline and abort")
                }
            } else if (!has(step) { it is PairEffect.Warn && it.kind == "wrong-code" }) {
                fail("T7: a wrong code was not counted with a warning")
            }
            return false
        }
    }

    private fun fsmD(i: JObject): VectorEval {
        val profile = profileOf(i)
        val cfg = PairFsmConfig(pinField(i, "pinOwn"), profile = profile)
        val fsm = DFsm(cfg)
        val model = DModel(cfg.ownPin, cfg.maxCodeTries)
        var state: DState = DState.Closed
        val trace = ArrayList<String>()
        var localApproved = false
        var remoteApproved = false
        var challenges = 0
        var validHellos = 0
        var revokedValid = false
        var secret: ByteArray? = null
        val laws = linkedSetOf("pairing-fsm-D")
        if (profile == PairProfile.R0_COMPAT) laws += "pairing-r0-compat"
        for (el in V.arr(i, "script")) {
            val o = el as JObject
            val e = dEvent(o)
            if (profile == PairProfile.R0_COMPAT && e is DEvent.LocalDecision && e.approve) localApproved = true
            if (profile == PairProfile.R0_COMPAT && e is DEvent.RemoteDecision && e.approve) remoteApproved = true
            if (e is DEvent.UserOpenWindow) secret = e.secret
            if (e is DEvent.HelloReceived && secret != null && PairCrypto.proofValid(secret, ownPin(i), e.pinS, e.nonceS, e.proof)) {
                validHellos++
                if (e.pinSStatus == StatusLookup.Known(PeerStatus.REVOKED)) revokedValid = true
            }
            val before = state
            val step = fsm.step(state, e)
            state = step.state
            trace += step.signature
            if (profile == PairProfile.R3) {
                if (e is DEvent.HelloReceived && step.effects.any { it is PairEffect.SendChallenge }) model.hello(e)
                val bound = before is DState.Consumed || before is DState.AwaitDecisions || before is DState.Committing || before is DState.AwaitAck
                if (bound && model.foreign(e)) {
                    if (step.effects != listOf(PairEffect.Refuse(Refusal.PROTOCOL_ERROR)) || step.state !== before) throw VectorLawViolation("T10: a decision or acknowledgement from another connection was not refused without a state change")
                    laws += "pairing-connection-binding"
                } else if (before is DState.AwaitDecisions) {
                    laws += model.observe(e, step)
                }
                // Under R3 a decision counts only when the machine itself judged it: a checked code, an opened commitment, the bound connection.
                if (step.effects.any { it is PairEffect.SendDecision && it.approve }) localApproved = true
                if (e is DEvent.RemoteDecision && e.approve && before is DState.AwaitDecisions && state !is DState.Closed && step.effects.none { it is PairEffect.Refuse }) remoteApproved = true
            }
            if (step.effects.any { it is PairEffect.SendChallenge }) challenges++
            if (step.effects.any { it is PairEffect.WritePairedRow }) {
                if (!localApproved || !remoteApproved) throw VectorLawViolation("L1: a row write was requested without both approvals")
            }
        }
        if (challenges > 1) throw VectorLawViolation("L6: a window accepted more than one valid proof")
        if (revokedValid && challenges > 0) throw VectorLawViolation("L7: a REVOKED pin with a valid proof reached the challenge")
        if (validHellos >= 2 && challenges <= 1) laws += "registry-L6"
        if (revokedValid && challenges == 0) laws += "registry-L7"
        if (remoteApproved && trace.none { it.contains("WritePairedRow") } || localApproved && remoteApproved) laws += "pairing-L1"
        return VectorEval(VectorOutcome.Ok(jobj("trace" to jlist(trace))), laws.toList())
    }

    private fun ownPin(i: JObject): Pin = pinField(i, "pinOwn")

    private fun sEvent(o: JObject): SEvent = when (val e = V.str(o, "e")) {
        "Scanned" -> SEvent.Scanned(QrUri.parse(V.str(o, "uri"), V.long(o, "nowSec"), when (V.str(o, "policy")) { "loopbackForTests" -> AddrPolicy.MESH_PLUS_LOOPBACK_FOR_TESTS; else -> AddrPolicy.MESH }, expiryOf(o)))
        "UserConfirmConnect" -> SEvent.UserConfirmConnect(V.bool(o, "yes"), V.long(o, "nowMs"))
        "DialResult" -> SEvent.DialResult(V.strOrNull(o, "presentedPin")?.let { Pin.fromNodeId(it) ?: throw VectorShapeException("presentedPin") }, V.hex(o, "nonceSHex"), V.long(o, "nowMs"))
        "ChallengeReceived" -> SEvent.ChallengeReceived(V.hex(o, "nonceDHex"), V.long(o, "nowMs"))
        "ErrorReceived" -> SEvent.ErrorReceived(V.str(o, "code"))
        "LocalDecision" -> SEvent.LocalDecision(V.bool(o, "approve"))
        "RemoteDecision" -> SEvent.RemoteDecision(V.bool(o, "approve"))
        "CommitReceived" -> SEvent.CommitReceived(V.hex(o, "transcriptHex"))
        "RowDurable" -> SEvent.RowDurable
        "RowWriteFailed" -> SEvent.RowWriteFailed
        "Tick" -> SEvent.Tick(V.long(o, "nowMs"))
        "UserCancel" -> SEvent.UserCancel
        "ConnectionLost" -> SEvent.ConnectionLost
        else -> throw VectorShapeException("unknown S event '$e'")
    }

    private fun fsmS(i: JObject): VectorEval {
        val profile = profileOf(i)
        val cfg = PairFsmConfig(ownPin(i), profile = profile)
        val fsm = SFsm(cfg)
        var state: SState = SState.Idle
        val trace = ArrayList<String>()
        var localApproved = false
        var payload: QrPayload? = null
        var opened = false
        val laws = linkedSetOf("pairing-fsm-S")
        if (profile == PairProfile.R0_COMPAT) laws += "pairing-r0-compat"
        for (el in V.arr(i, "script")) {
            val o = el as JObject
            val e = sEvent(o)
            if (e is SEvent.LocalDecision && e.approve) localApproved = true
            if (e is SEvent.Scanned) {
                val parsed = e.parse
                if (parsed is QrParse.Ok) payload = parsed.payload
                val policy = when (V.str(o, "policy")) { "loopbackForTests" -> AddrPolicy.MESH_PLUS_LOOPBACK_FOR_TESTS; else -> AddrPolicy.MESH }
                laws += expiryLaws(V.str(o, "uri"), V.long(o, "nowSec"), policy, expiryOf(o), parsed)
            }
            val before = state
            val step = fsm.step(state, e)
            state = step.state
            trace += step.signature
            if (step.effects.any { it is PairEffect.WritePairedRow } && !localApproved) throw VectorLawViolation("L1: S requested a row write without the local approval")
            if (profile == PairProfile.R3) {
                laws += observeS(cfg.ownPin, e, before, step, payload, opened)
                if (step.effects.any { it is PairEffect.SendDecision && it.approve }) opened = true
            }
        }
        if (trace.any { it.contains("Abort(PROTOCOL") } || trace.any { it.contains("WritePairedRow") }) laws += "pairing-L1"
        return VectorEval(VectorOutcome.Ok(jobj("trace" to jlist(trace))), laws.toList())
    }

    /**
     * What the R3 machine of S must do (R4-T-10): the hello carries only the commitment of the nonce it drew, and the proof binds that commitment; the nonce itself leaves
     * with S's own approval and never with a decline; a `PAIRING_WINDOW_CLOSED` from D while S waits for the challenge is D's verdict, not S's clock.
     */
    private fun observeS(own: Pin, e: SEvent, before: SState, step: SStep, payload: QrPayload?, alreadyOpened: Boolean): List<String> {
        val laws = ArrayList<String>()
        fun fail(msg: String): Nothing = throw VectorLawViolation(msg)
        if (e is SEvent.DialResult) {
            val hello = step.effects.filterIsInstance<PairEffect.SendHello>().singleOrNull()
            if (hello != null) {
                val p = payload ?: fail("a hello was sent without a scanned QR")
                if (!hello.nonceS.contentEquals(PairCrypto.commitNonce(e.freshNonceS)) || hello.nonceS.contentEquals(e.freshNonceS)) fail("T10: PAIR_HELLO.nonceS is not the commitment of the nonce S drew")
                if (!hello.proof.contentEquals(PairCrypto.proof(p.secret, p.pinD, own, hello.nonceS))) fail("T10: the proof does not bind the commitment")
                laws += "pairing-commit-reveal"
            }
        }
        val decisions = step.effects.filterIsInstance<PairEffect.SendDecision>()
        for (d in decisions) {
            if (d.approve) {
                val awaiting = before as? SState.AwaitDecisions ?: fail("an approval left S outside the decision states")
                val reveal = d.reveal ?: fail("T10: S approved without opening its commitment")
                if (!reveal.contentEquals(awaiting.nonceS)) fail("T10: S opened something other than the nonce it drew")
                if (e !is SEvent.LocalDecision || !e.approve) fail("T10: S opened its nonce without its own approval")
                laws += "pairing-commit-reveal"
            } else if (d.reveal != null) {
                fail("T10: a decline carries an opening")
            }
        }
        if (e is SEvent.ErrorReceived && before is SState.SentHello && e.code == Refusal.PAIRING_WINDOW_CLOSED.name) {
            if (step.effects.none { it is PairEffect.Abort && it.reason == AbortReason.WINDOW_EXPIRED }) fail("T8: PAIRING_WINDOW_CLOSED from D was not reported as the window expired on the other device")
            laws += "pairing-window-expired-on-d"
        }
        if (alreadyOpened && decisions.any { it.approve }) fail("T10: S opened its nonce twice")
        return laws
    }

    // ---------------------------------------------------------------- registry

    private fun statusName(l: StatusLookup): String = when (l) {
        StatusLookup.Absent -> "ABSENT"
        is StatusLookup.Known -> l.status.name
        is StatusLookup.Corrupt -> "CORRUPT"
        StatusLookup.Unreadable -> "UNREADABLE"
    }

    private fun changeText(c: StatusChange): String = "Changed(${c.from?.name ?: "none"}>${c.to?.name ?: "none"},${c.goaway?.wire ?: "none"})"

    private fun resultText(r: RegistryResult): String = when (r) {
        is RegistryResult.Changed -> changeText(r.change)
        RegistryResult.Updated -> "Updated"
        is RegistryResult.Refused -> "Refused(${r.code})"
    }

    private fun scopesOf(o: JObject, key: String): Set<Scope> = V.strings(o, key).map { Scope.of(it) ?: throw VectorShapeException("unknown scope '$it'") }.toSet()

    private fun registry(i: JObject): VectorEval {
        val store = InMemoryPeerStore()
        val reg = PeerRegistry(store)
        val trace = ArrayList<String>()
        val laws = linkedSetOf<String>()

        fun st(pin: Pin): String = statusName(reg.statusLookup(pin))

        for (el in V.arr(i, "ops")) {
            val o = el as JObject
            val op = V.str(o, "op")
            val pin = V.strOrNull(o, "pin")?.let { Pin.fromNodeId(it) ?: throw VectorShapeException("pin") }
            val before = pin?.let { st(it) }
            when (op) {
                "commit" -> {
                    val c = PairingCeremony(pin!!, "peer", "linux", if (V.str(o, "clazz") == "own") PeerClass.OWN else PeerClass.OTHER, ByteArray(32) { 7 }, scopesOf(o, "scopes"))
                    if (V.bool(o, "remote")) c.remoteApprove()
                    if (V.bool(o, "local")) c.localApprove()
                    val r = reg.commitPairing(c, V.long(o, "nowMs"))
                    trace += "commit:" + resultText(r)
                    val after = st(pin)
                    if (after == "PAIRED" && before != "PAIRED" && (!V.bool(o, "local") || !V.bool(o, "remote"))) throw VectorLawViolation("L1: a row reached PAIRED without both approvals")
                    if (r is RegistryResult.Refused && (!V.bool(o, "local") || !V.bool(o, "remote"))) laws += "registry-L1"
                    if (r is RegistryResult.Refused && before == "REVOKED") {
                        if (after != "REVOKED") throw VectorLawViolation("L3: a REVOKED row changed by a ceremony")
                        laws += "registry-L3"
                    }
                    if (r is RegistryResult.Changed) laws += "registry-L1"
                }
                "pause" -> {
                    val r = reg.pause(pin!!, V.long(o, "nowMs"))
                    trace += "pause:" + resultText(r)
                    if (r is RegistryResult.Changed && r.change.goaway?.wire != "suspended") throw VectorLawViolation("a row that left PAIRED for SUSPENDED must close its sessions with suspended")
                    if (r is RegistryResult.Changed) laws += "registry-goaway"
                }
                "restore" -> {
                    val r = reg.restore(pin!!, V.long(o, "nowMs"))
                    trace += "restore:" + resultText(r)
                    if (r is RegistryResult.Changed && before != "SUSPENDED") throw VectorLawViolation("L4: a row reached PAIRED by Restore from $before")
                    laws += "registry-L4"
                    if (before == "REVOKED") {
                        if (st(pin) != "REVOKED") throw VectorLawViolation("L3: a REVOKED row changed by Restore")
                        laws += "registry-L3"
                    }
                }
                "revoke" -> {
                    val r = reg.revoke(pin!!, V.long(o, "nowMs"))
                    trace += "revoke:" + resultText(r)
                    if (r is RegistryResult.Changed && before == "PAIRED") {
                        if (r.change.goaway?.wire != "revoked") throw VectorLawViolation("a row that left PAIRED for REVOKED must close its sessions with revoked")
                        laws += "registry-goaway"
                    }
                }
                "forget" -> {
                    val r = reg.forget(pin!!)
                    trace += "forget:" + resultText(r)
                    if (r is RegistryResult.Changed && before != "REVOKED") throw VectorLawViolation("L3: Forget removed a row that was not REVOKED")
                    if (before == "REVOKED") laws += "registry-L3"
                }
                "setScopes" -> {
                    val sBefore = pin?.let { reg.authorizeOutbound(it) }
                    trace += "setScopes:" + resultText(reg.setInboundScopes(pin!!, scopesOf(o, "scopes")))
                    val sAfter = reg.authorizeOutbound(pin)
                    if (sBefore != null && sBefore::class != sAfter::class) throw VectorLawViolation("changing inbound scopes changed the outbound direction")
                    laws += "registry-per-direction"
                }
                "setRoute" -> {
                    val g = reg.granted(pin!!)
                    trace += "setRoute:" + resultText(reg.setRoute(pin, V.bool(o, "enabled"), RouteCeiling.valueOf(V.str(o, "ceiling"))))
                    if (reg.granted(pin) != g) throw VectorLawViolation("changing the outbound route changed the inbound scopes")
                    laws += "registry-per-direction"
                }
                "net" -> {
                    val before2 = pin?.let { st(it) }
                    val e: NetworkEvent = when (V.str(o, "event")) {
                        "hint" -> NetworkEvent.RevocationHint(Pin.fromNodeId(V.str(o, "frm"))!!, pin!!)
                        "statusClaim" -> NetworkEvent.StatusClaim(pin!!, PeerStatus.valueOf(V.str(o, "status")))
                        "revokeNotice" -> NetworkEvent.RevokeNotice(pin!!)
                        "pairMessage" -> NetworkEvent.PairMessage(pin!!)
                        else -> throw VectorShapeException("unknown network event")
                    }
                    trace += "net:" + resultText(reg.onNetworkEvent(e))
                    if (pin != null && st(pin) != before2) throw VectorLawViolation("L1/L3: a network event changed a registry row")
                    laws += "registry-L1"
                    if (before2 == "REVOKED") laws += "registry-L3"
                    if (before2 == "SUSPENDED") laws += "registry-L4"
                }
                "status" -> trace += "status:" + st(pin!!)
                "authorize" -> {
                    val scope = V.str(o, "scope")
                    val d = reg.authorize(pin!!, scope)
                    trace += "authorize:" + when (d) { AuthDecision.Allow -> "Allow"; is AuthDecision.Deny -> "Deny(${d.reason})" }
                    if (d == AuthDecision.Allow && (st(pin) != "PAIRED" || Scope.of(scope) == null)) throw VectorLawViolation("L2: authorize allowed a peer that is not PAIRED or a scope that does not exist")
                    laws += "registry-L2"
                    if (st(pin) == "CORRUPT") laws += "registry-L5"
                }
                "authorizeOut" -> {
                    val d = reg.authorizeOutbound(pin!!)
                    trace += "authorizeOut:" + when (d) { is OutboundDecision.Allow -> "Allow(${d.ceiling})"; is OutboundDecision.Deny -> "Deny(${d.reason})" }
                    if (d is OutboundDecision.Allow && st(pin) != "PAIRED") throw VectorLawViolation("outbound routing allowed to a peer that is not PAIRED")
                    if (st(pin) == "CORRUPT") laws += "registry-L5"
                    laws += "registry-per-direction"
                }
                "granted" -> {
                    trace += "granted:" + reg.granted(pin!!).joinToString(",") { it.wire }
                    if (st(pin) != "PAIRED" && reg.granted(pin).isNotEmpty()) throw VectorLawViolation("a peer that is not PAIRED was granted scopes")
                }
                "session" -> {
                    val d = reg.sessionDecision(pin!!)
                    trace += "session:" + when (d) { SessionDecision.Keep -> "Keep"; is SessionDecision.Goaway -> "Goaway(${d.reason.wire})" }
                    if (d == SessionDecision.Keep && st(pin) != "PAIRED") throw VectorLawViolation("a session of a peer that is not PAIRED is kept")
                    laws += "registry-goaway"
                }
                "plant" -> {
                    val status = V.long(o, "status").toInt()
                    store.plant(StoredRow(pin!!, "peer", "linux", "own", status, V.str(o, "scopesJson"), 0, "D1", null, false, "user", 0))
                    trace += "plant:ok"
                    if (StatusCodes.decode(status) == null) laws += "registry-L5"
                }
                "fault" -> {
                    store.failReads = V.bool(o, "reads")
                    store.failWrites = V.bool(o, "writes")
                    trace += "fault:ok"
                    if (store.failReads || store.failWrites) laws += "registry-fail-closed"
                }
                else -> throw VectorShapeException("unknown registry op '$op'")
            }
        }
        if (store.failReads || store.failWrites) laws += "registry-fail-closed"
        return VectorEval(VectorOutcome.Ok(jobj("trace" to jlist(trace))), laws.toList())
    }
}
