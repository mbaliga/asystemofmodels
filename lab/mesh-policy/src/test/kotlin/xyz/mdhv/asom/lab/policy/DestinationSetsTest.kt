package xyz.mdhv.asom.lab.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DestinationSetsTest {
    private fun inp(
        global: Boolean = true, mesh: Boolean = true, ban: Boolean = false, deviceOnly: Boolean = false, localOnly: Boolean = false,
        fallback: List<String>? = null, noTrain: Boolean = false,
    ) = PolicyInputs(global, mesh, ban, deviceOnly, localOnly, fallback, noTrain)

    private fun p(i: PolicyInputs) = DestinationSets.compute(i).wire

    /** The decision table of contract.md 5.2, minus the `X` rows and the secret-detector row (v3). */
    @Test
    fun theContractDecisionTableRows() {
        assertEquals(listOf("T", "C"), p(inp(global = false)), "mesh global off, auto")
        assertEquals(listOf("T", "O", "C"), p(inp()), "on, app own, no ban, auto")
        assertEquals(listOf("T", "O"), p(inp(ban = true)), "on, app own, cloud ban")
        assertEquals(listOf("T", "C"), p(inp(mesh = false)), "on, app mesh off")
        assertEquals(listOf("T"), p(inp(localOnly = true)), "local-only")
        val fb = DestinationSets.compute(inp(fallback = listOf("openrouter", "groq")))
        assertEquals(listOf("C"), fb.wire)
        assertEquals(listOf("openrouter", "groq"), fb.cloudRestrictedTo, "C restricted to the list")
    }

    @Test
    fun namedClassesAreDerivedFromPNeverStored() {
        assertEquals("device-only", DestinationSets.namedClass(setOf(Dest.T)))
        assertEquals("own-devices", DestinationSets.namedClass(setOf(Dest.O, Dest.T)))
        assertEquals("own-and-cloud", DestinationSets.namedClass(setOf(Dest.C, Dest.O, Dest.T)))
        assertEquals("cloud-no-peers", DestinationSets.namedClass(setOf(Dest.T, Dest.C)))
        assertNull(DestinationSets.namedClass(setOf(Dest.C)))
        assertNull(DestinationSets.namedClass(emptySet()))
    }

    @Test
    fun deviceOnlyAppsAndTheNoTrainHeaderAndTheDefaults() {
        assertEquals(listOf("T"), p(inp(deviceOnly = true)))
        assertEquals(p(inp()), p(inp(noTrain = true)), "X-Asom-No-Train filters inside C exactly as v1; it never changes P")
        assertEquals(emptyList(), p(inp(ban = true, fallback = listOf("groq"))), "a fallback needs C, and the app bans it")
        assertEquals(emptyList(), p(inp(localOnly = true, fallback = listOf("groq"))), "local-only and fallback leave nothing")
        assertEquals(emptyList(), p(inp(fallback = emptyList())), "ERR-LP-1: a fallback naming no provider leaves no destination")
        assertEquals(listOf("C"), p(inp(global = false, mesh = false, fallback = listOf("groq"))))
        assertEquals(false, DestinationSets.defaultAppMeshAllowed(pairedBeforeSwitch = true, cloudBanned = false, userTicked = null), "existing apps: an unticked box")
        assertEquals(false, DestinationSets.defaultAppMeshAllowed(pairedBeforeSwitch = false, cloudBanned = false, userTicked = null), "apps paired later default to off")
        assertEquals(false, DestinationSets.defaultAppMeshAllowed(pairedBeforeSwitch = true, cloudBanned = true, userTicked = null), "cloud-banned apps stay off")
        assertEquals(true, DestinationSets.defaultAppMeshAllowed(pairedBeforeSwitch = true, cloudBanned = true, userTicked = true), "unless set individually")
    }

    /** An independent oracle in positive form: which destinations may receive the request, decided destination by destination. */
    private fun oracle(i: PolicyInputs): Set<Dest> {
        val fallbackMode = i.fallbackProviders != null
        val deviceRestricted = i.appDeviceOnly || i.policyLocalOnly
        val t = !fallbackMode
        val o = !fallbackMode && i.meshGlobalOn && i.appMeshAllowed && !deviceRestricted
        val c = !deviceRestricted && !i.appCloudBanned && (!fallbackMode || i.fallbackProviders!!.isNotEmpty())
        return buildSet {
            if (t) add(Dest.T)
            if (o) add(Dest.O)
            if (c) add(Dest.C)
        }
    }

    @Test
    fun theWholeCrossProductAgreesWithAPositiveFormOracleAndNoRuleEverAddsADestination() {
        var cases = 0
        var empty = 0
        val bools = listOf(false, true)
        for (g in bools) for (m in bools) for (b in bools) for (d in bools) for (l in bools) for (nt in bools) for (fb in listOf(null, listOf("groq"), emptyList<String>())) {
            val i = PolicyInputs(g, m, b, d, l, fb, nt)
            val got = DestinationSets.compute(i).dests
            assertEquals(oracle(i), got, "inputs $i")
            cases++
            if (got.isEmpty()) empty++
            // Monotone: turning any restriction on never adds a destination.
            val more = listOf(i.copy(appCloudBanned = true), i.copy(appDeviceOnly = true), i.copy(policyLocalOnly = true), i.copy(meshGlobalOn = false), i.copy(appMeshAllowed = false))
            for (r in more) assertTrue(DestinationSets.compute(r).dests.all { it in got }, "a restriction added a destination: $i -> $r")
            assertEquals(got, DestinationSets.compute(i.copy(noTrain = !nt)).dests, "no-train never changes P")
        }
        assertEquals(2 * 2 * 2 * 2 * 2 * 2 * 3, cases)
        assertTrue(empty > 0, "the empty set must occur in the product")
        println("L01 destination sets: $cases input combinations checked against the positive-form oracle ($empty give an empty P)")
    }

    @Test
    fun eligibilityRecordsTheFirstFailingRow() {
        val all = setOf(Dest.T, Dest.O, Dest.C)
        val ok = PeerRegistryView(PeerStatus.PAIRED, routeEnabled = true, inferGrantedToMe = true)
        assertEquals(Eligibility.Eligible, PeerEligibility.evaluate(all, ok))
        assertEquals(Eligibility.Excluded("F1_ELIGIBILITY"), PeerEligibility.evaluate(setOf(Dest.T, Dest.C), ok))
        assertEquals(Eligibility.Excluded("F1_ELIGIBILITY"), PeerEligibility.evaluate(all, ok.copy(routeEnabled = false)))
        assertEquals(Eligibility.Excluded("F2_NOT_PAIRED"), PeerEligibility.evaluate(all, ok.copy(status = PeerStatus.SUSPENDED)))
        assertEquals(Eligibility.Excluded("F2_NOT_PAIRED"), PeerEligibility.evaluate(all, ok.copy(status = PeerStatus.REVOKED)))
        assertEquals(Eligibility.Excluded("F3_NO_SCOPE"), PeerEligibility.evaluate(all, ok.copy(inferGrantedToMe = false)))
        assertEquals(Eligibility.Excluded("F1_ELIGIBILITY"), PeerEligibility.evaluate(setOf(Dest.T), PeerRegistryView(PeerStatus.REVOKED, false, false)), "F1 outranks F2 and F3")
        assertEquals(Eligibility.Excluded("F2_NOT_PAIRED"), PeerEligibility.evaluate(all, PeerRegistryView(PeerStatus.REVOKED, true, false)), "F2 outranks F3")
    }

    @Test
    fun quiescenceMayInitiateOverTheWholeInputSpaceAgainstATruthTable() {
        var cases = 0
        val bools = listOf(false, true)
        for (role in Role.entries) for (a in bools) for (b in bools) for (c in bools) for (d in bools) {
            val got = Quiescence.mayInitiate(QuiescenceInputs(role, a, b, c, d))
            val want = if (role == Role.REQUESTER) (a || b || c || d) else c
            assertEquals(want, got, "$role pending=$a screen=$b userOp=$c finishing=$d")
            cases++
        }
        assertEquals(32, cases)
        assertEquals(false, Quiescence.mayInitiate(QuiescenceInputs(Role.REQUESTER, false, false, false, false)), "no local caller, no screen, no operation: send nothing")
        assertEquals(false, Quiescence.mayInitiate(QuiescenceInputs(Role.LENDER, true, true, false, true)), "a lender initiates none except under rule 3")
        assertEquals(true, Quiescence.mayInitiate(QuiescenceInputs(Role.LENDER, false, false, true, false)))
    }

    @Test
    fun aLenderAcceptsInboundOnlyWhileServingOrWithAPairingWindowOrThePeersTabOpen() {
        for (f in Fsm.entries) for (w in listOf(false, true)) for (t in listOf(false, true)) {
            assertEquals(f == Fsm.SERVING || w || t, Quiescence.mayAcceptInbound(f, w, t))
        }
        assertEquals(false, Quiescence.sessionShouldClose(299_999, 10, 0))
        assertEquals(true, Quiescence.sessionShouldClose(300_000, 10, 0))
        assertEquals(false, Quiescence.sessionShouldClose(400_000, 10, 1), "an open stream keeps the session")
        assertEquals(true, Quiescence.sessionShouldClose(0, 1_800_000, 1), "30 minutes of age")
        assertEquals(false, Quiescence.sessionShouldClose(0, 1_799_999, 1))
    }
}
