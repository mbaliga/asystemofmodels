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
 */
object W04Vectors {
    val requiredLaws: Set<String> =
        setOf(
            "qr-grammar", "qr-roundtrip", "proof-sas-transcript", "proof-invalid", "pin-swap", "nonce-swap", "pair-message-ok", "pair-message-reject", "commit-no-locseed",
            "pairing-fsm-D", "pairing-fsm-S", "pairing-L1", "registry-L6", "registry-L7", "registry-L1", "registry-L2", "registry-L3", "registry-L4", "registry-L5",
            "registry-goaway", "registry-per-direction", "registry-fail-closed",
        ) + QrReject.entries.map { "qr-reject-$it" }

    fun evaluate(input: JObject): VectorEval = when (val kind = V.str(input, "kind")) {
        "qrParse" -> qrParse(input)
        "qrEncode" -> qrEncode(input)
        "proofSas" -> proofSas(input)
        "proofVerify" -> proofVerify(input)
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

    /** An optional `"expiry": "none"` selects design T8 (ERRATA ERR-FX2-4); a vector without it is the frozen r0 reading. */
    private fun expiryOf(i: JObject): QrExpiry = if (V.strOrNull(i, "expiry") == "none") QrExpiry.NONE else QrExpiry.ENFORCE

    /** An optional `"profile": "r3"` selects the R3 machines (ERRATA ERR-FX2-1, -2, -5); a vector without it is the frozen r0 reading. */
    private fun profileOf(i: JObject): PairProfile = if (V.strOrNull(i, "profile") == "r3") PairProfile.R3 else PairProfile.R0_COMPAT

    private fun qrParse(i: JObject): VectorEval {
        val now = V.long(i, "nowSec")
        val policy = policyOf(i)
        val expiry = expiryOf(i)
        return when (val r = QrUri.parse(V.str(i, "uri"), now, policy, expiry)) {
            is QrParse.Ok -> {
                val again = QrUri.parse(QrUri.encode(r.payload), now, policy, expiry)
                if (again !is QrParse.Ok || payloadValue(again.payload) != payloadValue(r.payload)) throw VectorLawViolation("encode(parse(uri)) does not parse back to the same payload")
                VectorEval(VectorOutcome.Ok(payloadValue(r.payload)), listOf("qr-grammar", "qr-roundtrip"))
            }
            is QrParse.Reject -> VectorEval(VectorOutcome.Reject(r.code.name), listOf("qr-grammar", "qr-reject-${r.code}"))
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

    private fun fsmD(i: JObject): VectorEval {
        val profile = profileOf(i)
        val fsm = DFsm(PairFsmConfig(pinField(i, "pinOwn"), profile = profile))
        var state: DState = DState.Closed
        val trace = ArrayList<String>()
        var localApproved = false
        var remoteApproved = false
        var challenges = 0
        var validHellos = 0
        var revokedValid = false
        var secret: ByteArray? = null
        val laws = linkedSetOf("pairing-fsm-D")
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
        val fsm = SFsm(PairFsmConfig(ownPin(i), profile = profileOf(i)))
        var state: SState = SState.Idle
        val trace = ArrayList<String>()
        var localApproved = false
        val laws = linkedSetOf("pairing-fsm-S")
        for (el in V.arr(i, "script")) {
            val e = sEvent(el as JObject)
            if (e is SEvent.LocalDecision && e.approve) localApproved = true
            val step = fsm.step(state, e)
            state = step.state
            trace += step.signature
            if (step.effects.any { it is PairEffect.WritePairedRow } && !localApproved) throw VectorLawViolation("L1: S requested a row write without the local approval")
        }
        if (trace.any { it.contains("Abort(PROTOCOL") } || trace.any { it.contains("WritePairedRow") }) laws += "pairing-L1"
        return VectorEval(VectorOutcome.Ok(jobj("trace" to jlist(trace))), laws.toList())
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
