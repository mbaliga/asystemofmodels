package xyz.mdhv.asom.lab.proto.trust

import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.policy.PeerStatus

/**
 * Property tests of the peer registry (trust.md 4.7, laws L1 to L5) over random operation sequences, against an independent model: a plain map that
 * applies the transition table of the spec and predicts every observable (status lookup, `authorize` for each scope, the outbound decision, the
 * granted list and the session decision) after every operation. Each law counts the cases that exercised it; the iteration count is printed.
 *
 * L6 and L7 are pairing laws (PairingFsmExhaustiveTest); L8 is verifyPeerChain (PeerChainVerifierTest, W05); L9 and L12 are superseded (LAB_SPEC 7.3).
 * L10 (a durable row before INFER_BODY) and L11 (the two listeners never reach each other's code) need the per-frame ledger writer and the listeners, which are a
 * later track: they are BLOCKED here, not covered. Evidence label: LAB, oracle: self.
 */
class PeerRegistryPropertyTest {
    companion object {
        val laws = LawCounters("registry")
        const val SEQUENCES = 3000
        const val OPS = 40

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(
            setOf(
                "L1-no-row-without-local-and-remote-approval", "L1-network-event-changes-nothing", "L2-authorize-iff-paired-and-granted", "L3-revoked-absorbing", "L4-restore-only-local",
                "L5-unknown-status-denies", "per-direction-independent", "goaway-on-leaving-paired", "fail-closed-reads", "fail-closed-writes", "revoked-cannot-repair", "re-read-per-frame",
            ),
        )
    }

    private class Model {
        var present = false
        var status = PeerStatus.PAIRED
        var statusCorrupt = false
        var scopes: Set<Scope> = emptySet()
        var scopesCorrupt = false
        var route = false
        var ceiling = RouteCeiling.D1
    }

    private val pins = List(4) { Pin.ofHash(ByteArray(32) { b -> (it * 40 + b).toByte() }) }
    private val scopeNames = listOf("infer", "state", "manifest", "revoke-hint", "", "INFER")

    private fun randomScopes(rnd: SplittableRandom): Set<Scope> = Scope.entries.filter { rnd.nextBoolean() }.toSet()

    private fun check(reg: PeerRegistry, store: InMemoryPeerStore, models: List<Model>, step: String) {
        for ((i, pin) in pins.withIndex()) {
            val m = models[i]
            val readable = !store.failReads
            val expectedLookup: StatusLookup = when {
                !readable -> StatusLookup.Unreadable
                !m.present -> StatusLookup.Absent
                m.statusCorrupt -> StatusLookup.Corrupt(9)
                else -> StatusLookup.Known(m.status)
            }
            val gotLookup = reg.statusLookup(pin)
            if (expectedLookup is StatusLookup.Corrupt) assertTrue(gotLookup is StatusLookup.Corrupt, "$step: a corrupt row must read as Corrupt, got $gotLookup")
            else assertEquals(expectedLookup, gotLookup, "$step: status lookup of pin $i")
            val paired = readable && m.present && !m.statusCorrupt && m.status == PeerStatus.PAIRED
            for (name in scopeNames) {
                val s = Scope.of(name)
                val allow = paired && !m.scopesCorrupt && s != null && s in m.scopes
                val got = reg.authorize(pin, name)
                assertEquals(allow, got == AuthDecision.Allow, "$step: authorize($i, '$name') must be ${if (allow) "Allow" else "Deny"}")
                laws.bump("L2-authorize-iff-paired-and-granted")
                if (readable && m.present && m.statusCorrupt) {
                    assertTrue(got is AuthDecision.Deny, "$step: an unknown status value must deny")
                    laws.bump("L5-unknown-status-denies")
                }
                if (!readable) laws.bump("fail-closed-reads")
                if (m.present && m.status != PeerStatus.PAIRED && readable && !m.statusCorrupt) assertTrue(got is AuthDecision.Deny)
            }
            val out = reg.authorizeOutbound(pin)
            val outAllowed = paired && m.route
            assertEquals(outAllowed, out is OutboundDecision.Allow, "$step: outbound decision of pin $i")
            if (out is OutboundDecision.Allow) assertEquals(m.ceiling, out.ceiling)
            val granted = reg.granted(pin)
            assertEquals(if (paired && !m.scopesCorrupt) Scope.entries.filter { it in m.scopes } else emptyList(), granted, "$step: granted of pin $i")
            val expectedSession: SessionDecision = when {
                !readable || (m.present && m.statusCorrupt) -> SessionDecision.Goaway(GoawayReason.SUSPENDED)
                !m.present -> SessionDecision.Goaway(GoawayReason.REVOKED)
                m.status == PeerStatus.PAIRED -> SessionDecision.Keep
                m.status == PeerStatus.SUSPENDED -> SessionDecision.Goaway(GoawayReason.SUSPENDED)
                else -> SessionDecision.Goaway(GoawayReason.REVOKED)
            }
            val gotSession = reg.sessionDecision(pin)
            assertEquals(expectedSession::class, gotSession::class, "$step: session decision of pin $i")
            if (expectedSession is SessionDecision.Goaway) assertEquals(expectedSession.reason, (gotSession as SessionDecision.Goaway).reason)
            laws.bump("re-read-per-frame")
        }
    }

    private fun expectChange(r: RegistryResult, from: PeerStatus?, to: PeerStatus?, goaway: GoawayReason?, step: String) {
        assertTrue(r is RegistryResult.Changed, "$step: expected a change from $from to $to, got $r")
        assertEquals(from, r.change.from, step)
        assertEquals(to, r.change.to, step)
        assertEquals(goaway, r.change.goaway, "$step: GOAWAY reason")
        if (from == PeerStatus.PAIRED && to != PeerStatus.PAIRED) laws.bump("goaway-on-leaving-paired")
    }

    @Test
    fun randomOperationSequencesFollowTheTransitionTable() {
        val rnd = SplittableRandom(20260930)
        repeat(SEQUENCES) { seq ->
            val store = InMemoryPeerStore()
            val reg = PeerRegistry(store)
            val models = List(pins.size) { Model() }
            val heard = ArrayList<StatusChange>()
            reg.addListener { heard += it }
            var changesReturned = 0
            repeat(OPS) { opNo ->
                val i = rnd.nextInt(pins.size)
                val pin = pins[i]
                val m = models[i]
                val faultsRead = store.failReads
                val faultsWrite = store.failWrites
                val canRead = !faultsRead
                val rowOk = canRead && m.present && !m.statusCorrupt
                val step = "seq $seq op $opNo"
                when (rnd.nextInt(14)) {
                    0, 1 -> { // commit, with random approvals and class
                        val local = rnd.nextInt(4) != 0
                        val remote = rnd.nextInt(4) != 0
                        val own = rnd.nextInt(5) != 0
                        val scopes = randomScopes(rnd)
                        val c = PairingCeremony(pin, "peer", "linux", if (own) PeerClass.OWN else PeerClass.OTHER, ByteArray(32) { 1 }, scopes)
                        if (remote) c.remoteApprove()
                        if (local) c.localApprove()
                        val r = reg.commitPairing(c, 1)
                        val shouldPair = local && remote && own && canRead && !m.present && !faultsWrite
                        if (shouldPair) {
                            expectChange(r, null, PeerStatus.PAIRED, null, step)
                            m.present = true; m.status = PeerStatus.PAIRED; m.statusCorrupt = false; m.scopes = scopes; m.scopesCorrupt = false; m.route = false; m.ceiling = RouteCeiling.D1
                            changesReturned++
                        } else {
                            assertTrue(r is RegistryResult.Refused, "$step: a commit that must be refused was $r")
                            if (!local || !remote) laws.bump("L1-no-row-without-local-and-remote-approval")
                            if (canRead && m.present && !m.statusCorrupt && m.status == PeerStatus.REVOKED && local && remote && own) {
                                assertEquals(RegistryRefusal.REVOKED_CANNOT_REPAIR, (r as RegistryResult.Refused).code)
                                laws.bump("revoked-cannot-repair")
                            }
                            if (faultsWrite && local && remote && own && canRead && !m.present) laws.bump("fail-closed-writes")
                        }
                    }
                    2 -> {
                        val r = reg.pause(pin, 2)
                        if (rowOk && m.status == PeerStatus.PAIRED && !faultsWrite) { expectChange(r, PeerStatus.PAIRED, PeerStatus.SUSPENDED, GoawayReason.SUSPENDED, step); m.status = PeerStatus.SUSPENDED; changesReturned++ }
                        else { assertTrue(r is RegistryResult.Refused, "$step: pause must be refused"); if (faultsWrite && rowOk && m.status == PeerStatus.PAIRED) laws.bump("fail-closed-writes") }
                    }
                    3 -> {
                        val r = reg.restore(pin, 3)
                        if (rowOk && m.status == PeerStatus.SUSPENDED && !faultsWrite) { expectChange(r, PeerStatus.SUSPENDED, PeerStatus.PAIRED, null, step); m.status = PeerStatus.PAIRED; changesReturned++ }
                        else { assertTrue(r is RegistryResult.Refused, "$step: restore must be refused"); if (rowOk && m.status != PeerStatus.SUSPENDED) laws.bump("L4-restore-only-local") }
                    }
                    4 -> {
                        val r = reg.revoke(pin, 4)
                        if (rowOk && m.status != PeerStatus.REVOKED && !faultsWrite) {
                            expectChange(r, m.status, PeerStatus.REVOKED, if (m.status == PeerStatus.PAIRED) GoawayReason.REVOKED else null, step)
                            m.status = PeerStatus.REVOKED
                            changesReturned++
                        } else assertTrue(r is RegistryResult.Refused, "$step: revoke must be refused")
                    }
                    5 -> {
                        val r = reg.forget(pin)
                        if (rowOk && m.status == PeerStatus.REVOKED && !faultsWrite) { expectChange(r, PeerStatus.REVOKED, null, null, step); m.present = false; changesReturned++ }
                        else assertTrue(r is RegistryResult.Refused, "$step: forget must be refused")
                    }
                    6 -> {
                        val scopes = randomScopes(rnd)
                        val before = reg.authorizeOutbound(pin)
                        val r = reg.setInboundScopes(pin, scopes)
                        if (rowOk && m.status != PeerStatus.REVOKED && !faultsWrite) { assertTrue(r is RegistryResult.Updated); m.scopes = scopes; m.scopesCorrupt = false }
                        else assertTrue(r is RegistryResult.Refused)
                        assertEquals(before::class, reg.authorizeOutbound(pin)::class, "$step: changing inbound scopes changed the outbound decision")
                        laws.bump("per-direction-independent")
                    }
                    7 -> {
                        val enabled = rnd.nextBoolean()
                        val ceiling = RouteCeiling.entries[rnd.nextInt(2)]
                        val before = reg.granted(pin)
                        val r = reg.setRoute(pin, enabled, ceiling)
                        if (rowOk && m.status != PeerStatus.REVOKED && !faultsWrite) { assertTrue(r is RegistryResult.Updated); m.route = enabled; m.ceiling = ceiling }
                        else assertTrue(r is RegistryResult.Refused)
                        assertEquals(before, reg.granted(pin), "$step: changing the route changed the inbound scopes")
                        laws.bump("per-direction-independent")
                    }
                    8, 9 -> { // a network event: never a change of any row
                        val other = pins[rnd.nextInt(pins.size)]
                        val e: NetworkEvent = when (rnd.nextInt(4)) {
                            0 -> NetworkEvent.RevocationHint(other, pin)
                            1 -> NetworkEvent.StatusClaim(pin, PeerStatus.entries[rnd.nextInt(3)])
                            2 -> NetworkEvent.RevokeNotice(other)
                            else -> NetworkEvent.PairMessage(pin)
                        }
                        reg.onNetworkEvent(e)
                        // The model is not touched: the comparison at the end of this iteration proves that no row moved.
                        laws.bump("L1-network-event-changes-nothing")
                        if (m.present && m.status == PeerStatus.REVOKED) laws.bump("L3-revoked-absorbing")
                        if (m.present && m.status == PeerStatus.SUSPENDED) laws.bump("L4-restore-only-local")
                    }
                    10 -> { // plant a row the code must treat as corrupt, or repair it
                        when (rnd.nextInt(4)) {
                            0 -> {
                                val bad = intArrayOf(0, 1, 5, 9, -1, Int.MAX_VALUE)[rnd.nextInt(6)]
                                store.plant(StoredRow(pin, "peer", "linux", "own", bad, PeerRegistry.encodeScopes(m.scopes), 0, "D1", null, false, "user", 0))
                                m.present = true; m.statusCorrupt = true
                            }
                            1 -> {
                                val scopesText = arrayOf("[\"infer\",\"infer\"]", "[\"admin\"]", "{}", "", "[1]")[rnd.nextInt(5)]
                                store.plant(StoredRow(pin, "peer", "linux", "own", StatusCodes.PAIRED, scopesText, if (m.route) 1 else 0, m.ceiling.name, null, false, "user", 0))
                                m.present = true; m.statusCorrupt = false; m.status = PeerStatus.PAIRED; m.scopesCorrupt = true
                            }
                            else -> {
                                val st = PeerStatus.entries[rnd.nextInt(3)]
                                val sc = randomScopes(rnd)
                                store.plant(StoredRow(pin, "peer", "linux", "own", StatusCodes.encode(st), PeerRegistry.encodeScopes(sc), 0, "D1", null, false, "user", 0))
                                m.present = true; m.statusCorrupt = false; m.status = st; m.scopes = sc; m.scopesCorrupt = false; m.route = false; m.ceiling = RouteCeiling.D1
                            }
                        }
                    }
                    11 -> store.failReads = rnd.nextInt(3) == 0
                    12 -> store.failWrites = rnd.nextInt(3) == 0
                    else -> { /* only the observation below */ }
                }
                // Corrupt rows refuse every mutation; the model must not have moved. (Checked by the observable comparison.)
                check(reg, store, models, "seq $seq after op $opNo")
            }
            assertTrue(heard.size == changesReturned, "seq $seq: the listener heard ${heard.size} changes, ${changesReturned} were returned")
            laws.bump("L3-revoked-absorbing", models.count { it.present && it.status == PeerStatus.REVOKED })
        }
        println("iterations: $SEQUENCES sequences x $OPS operations")
    }

    @Test
    fun aRevokedRowSurvivesEveryOperationExceptForget() {
        val store = InMemoryPeerStore()
        val reg = PeerRegistry(store)
        val pin = pins[0]
        val c = PairingCeremony(pin, "peer", "linux", PeerClass.OWN, ByteArray(32) { 1 }, setOf(Scope.INFER))
        c.remoteApprove(); c.localApprove()
        assertTrue(reg.commitPairing(c, 1) is RegistryResult.Changed)
        assertTrue(reg.revoke(pin, 2) is RegistryResult.Changed)
        val c2 = PairingCeremony(pin, "peer", "linux", PeerClass.OWN, ByteArray(32) { 1 }, setOf(Scope.INFER))
        c2.remoteApprove(); c2.localApprove()
        for (r in listOf(reg.commitPairing(c2, 3), reg.pause(pin, 3), reg.restore(pin, 3), reg.revoke(pin, 3), reg.setInboundScopes(pin, setOf(Scope.STATE)), reg.setRoute(pin, true, RouteCeiling.D1))) {
            assertTrue(r is RegistryResult.Refused)
        }
        for (e in listOf(NetworkEvent.StatusClaim(pin, PeerStatus.PAIRED), NetworkEvent.RevocationHint(pins[1], pin), NetworkEvent.PairMessage(pin), NetworkEvent.RevokeNotice(pin))) reg.onNetworkEvent(e)
        assertEquals(StatusLookup.Known(PeerStatus.REVOKED), reg.statusLookup(pin))
        assertTrue(reg.forget(pin) is RegistryResult.Changed)
        assertEquals(StatusLookup.Absent, reg.statusLookup(pin))
        laws.bump("L3-revoked-absorbing")
    }

    @Test
    fun aCeremonyNeedsTheLocalApprovalWhateverTheNetworkSays() {
        val store = InMemoryPeerStore()
        val reg = PeerRegistry(store)
        val c = PairingCeremony(pins[0], "peer", "linux", PeerClass.OWN, ByteArray(32) { 1 }, setOf(Scope.INFER))
        c.remoteApprove()
        assertEquals(RegistryRefusal.LOCAL_APPROVAL_REQUIRED, (reg.commitPairing(c, 1) as RegistryResult.Refused).code)
        c.remoteApprove()
        assertEquals(StatusLookup.Absent, reg.statusLookup(pins[0]))
        laws.bump("L1-no-row-without-local-and-remote-approval")
    }
}
