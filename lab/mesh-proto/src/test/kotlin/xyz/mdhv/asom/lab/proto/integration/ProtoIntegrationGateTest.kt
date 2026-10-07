package xyz.mdhv.asom.lab.proto.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.TestMethodOrder
import xyz.mdhv.asom.lab.ledger.FailMode
import xyz.mdhv.asom.lab.ledger.FrameRows
import xyz.mdhv.asom.lab.manifest.RejectCode
import xyz.mdhv.asom.lab.proto.session.Counts
import xyz.mdhv.asom.lab.proto.session.Ev
import xyz.mdhv.asom.lab.proto.session.FailPlan
import xyz.mdhv.asom.lab.proto.session.L14
import xyz.mdhv.asom.lab.proto.session.Refusal
import xyz.mdhv.asom.lab.proto.session.all
import xyz.mdhv.asom.lab.proto.tls.Matrix
import xyz.mdhv.asom.lab.proto.tls.W08Kind

/**
 * The closing step of work item L0.5 (LAB_SPEC 7.4, 7.6, 8.1): the two halves of the proto track, the TLS transport and the session engine, joined over real loopback
 * TLS 1.3 with the real peer registry, the real pairing-derived pins and the real per-frame ledger writer on the real JSONL sink. One class, so that its summary lines are
 * printed once and in one place: `python3 lab/mesh-proto/tools/integration/summary.py` reads them from the test report. Every law counts the cases that exercised it and the
 * class fails if a count is zero. Evidence label: LAB, oracle: self (same session as the code), JDK 17 and JDK 21, NOT DEVICE EVIDENCE.
 */
@Timeout(3_600)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ProtoIntegrationGateTest {
    companion object {
        const val CLEAN_RUNS = 60
        const val SWEEP_SEEDS = 3
        const val RESET_RUNS = 12
        const val PAIRING_RUNS = 16
        val windows = System.getProperty("os.name").lowercase().startsWith("windows")

        val l15 = L15Stats()
        val counts = Counts()
        val l13 = L13Counts()
        var connects = 0
        var cleanRuns = 0
        var failureRuns = 0
        var resetRuns = 0
        var pairingRuns = 0
        var lifecycleRows = 0
        var wireDisagreements = 0
        const val GATE_METHODS = 10
        val executed = java.util.concurrent.atomic.AtomicInteger()

        @JvmStatic
        @AfterAll
        fun summary() {
            val jdk = Matrix.jdk
            val rt = System.getProperty("java.version")
            println("== proto integration gate (LAB; oracle: self; NOT DEVICE EVIDENCE; JDK $rt, os ${System.getProperty("os.name")}) ==")
            println("calibration: cipher ${TlsCalibration.measured.cipherSuite}; one TLS record costs ${TlsCalibration.recordOverhead} bytes over the plaintext (spec figure [F51]: 22), the close alert is ${TlsCalibration.alertRecord} bytes, the largest single-record write is ${TlsCalibration.measured.maxRecordPlaintext} bytes")
            println("runs: $cleanRuns clean seeded sessions, $failureRuns failure-injection runs, $resetRuns runs cut by a peer reset, $pairingRuns pairing sessions, 1 lifecycle walk ($lifecycleRows rows)")
            println("L-L15 MEASURED sessions: ${l15.measuredSessions} (n > 0), mismatches: ${l15.mismatches}")
            println("L-L15 detail: rows ${l15.rowAppBytes} application bytes + ${l15.overheadBytes} overhead bytes = ${l15.rowAppBytes + l15.overheadBytes} = ${l15.tapBytes} bytes counted by the record tap; handshake figure ${l15.handshakeBytesMin}..${l15.handshakeBytesMax} bytes; sessions that lost ${l15.sessionsWithUnsentClaims} write-ahead claims totalling ${l15.unsentBytesClaimed} bytes; cross-checked socket to socket on every clean run: ${connects - wireDisagreements}/$connects")
            println("L-L15 ESTIMATED sessions (a node that crashed and wrote no close row): ${l15.estimatedSessions}; largest |estimate - tap| ${l15.maxEstimateDeviation} bytes; ${l15.estimateLines.joinToString("; ")}")
            println("L-L16 iterations: ${counts.sessions} sessions; frames matched to a row on the node that sent or received them: ${counts.checks}; rows of failed appends tolerated: ${counts.toleratedFailedRows}")
            println("L-L16 per-frame-type counts: ${counts.perType}")
            println("structural laws (iterations): ${counts.structural}")
            println("LP-1 STATE frames checked producer-strict: ${counts.stateFrames}; frames that carried st to a state-scoped peer: ${counts.stSent}; frames where st was withheld from a peer without scope state: ${counts.stWithheld}")
            println("L-L13 iterations: ${l13.runsWithFailure} (failure events checked: ${l13.failureEvents}; control-row failures: ${l13.controlFailures}; FC-1 requester intent: ${l13.requesterIntentFailures}; FC-4 lender intent: ${l13.lenderIntentFailures}; FC-5 lender outcome before INFER_END: ${l13.lenderEndOutcomeFailures}; lender decline outcome: ${l13.lenderDeclineOutcomeFailures}; DIAL intent: ${l13.dialFailures}; frames after control failure: ${l13.framesAfterControlFailure}; content frames after a sticky failure: ${l13.contentFramesAfterStickyFailure}; checked against the record tap: ${l13.tapChecked})")
            println("L-L14 iterations: $connects (every connect had a durable DIAL intent before it)")
            W08Tls.frameCases.get().let {
                println(
                    "W08-frames-tls: hostile cases: $it; refusal kinds exercised: ${W08Tls.kinds.size}/${Refusal.entries.size}; row checks: ${W08Tls.rowChecks.get()}; frames after control failure: ${W08Tls.framesAfterControlFailure.get()}; " +
                        "split runs: ${W08Tls.splitRuns.get()}; closed by the honest end and seen as end of stream by the hostile end: ${W08Tls.closedByHonestChecks.get()}; typed ERROR codes seen on the wire: ${W08Tls.refusalCodesLine()}",
                )
            }
            println("W08-frames-tls manifest verdict codes: ${(W08Tls.verdictsThroughSession + W08Tls.verdictsDirect).size}/${RejectCode.entries.size} (through a TLS session: ${W08Tls.verdictsThroughSession.size}; by the verifier directly, because the frame layer refuses the document first or the context is FILE mode: ${(W08Tls.verdictsDirect - W08Tls.verdictsThroughSession).size})")
            println("W08-frames-tls L-L15 over its sessions: MEASURED ${W08Tls.l15.measuredSessions}, mismatches ${W08Tls.l15.mismatches}")
            HostileHandshakeSession.lines.sorted().forEach { println(it) }
            println("W08-handshake-session: cases ${W08Tls.handshakeCases.get()}; dial outcomes ${java.util.TreeMap(W08Tls.dialOutcomes)}; INBOUND_REFUSED rows ${W08Tls.inboundRefusedRows.get()}; sessions established under the session engine in this suite ${W08Tls.sessionsUnderSessionEngine.get()}")
            println(
                "W08-over-tls: accepted bad chains: ${W08Tls.acceptedBadChains.get()}; ClientHellos with pre_shared_key: ${W08Tls.honestPskClientHellos.get()}; " +
                    "sessions with client CertificateVerify: ${W08Tls.sessionsWithClientCertVerify.get()}/${W08Tls.establishedSessions.get()}",
            )
            println("evidence: LAB, oracle: self, NOT DEVICE EVIDENCE, jdk feature $jdk")

            if (executed.get() < GATE_METHODS) {
                println("partial run: ${executed.get()} of $GATE_METHODS gate methods ran, so the non-vacuity assertions are not made (the gate command runs the whole class)")
                return
            }
            // non-vacuity: every law and every family counted something
            assertTrue(l15.measuredSessions > 0, "L-L15 exercised zero MEASURED sessions")
            assertEquals(0, l15.mismatches, "L-L15 mismatches")
            assertTrue(l15.estimatedSessions > 0, "no ESTIMATED session was exercised")
            assertTrue(W08Tls.l15.measuredSessions > 0 && W08Tls.l15.mismatches == 0, "L-L15 over the W08 sessions")
            val zero = FrameRows.L16_KINDS.filter { (counts.perType[it] ?: 0) == 0 }
            assertEquals(emptyList(), zero, "L-L16 never exercised these listed frame types")
            assertTrue(counts.sessions > 0 && counts.checks > 0)
            assertEquals(0, counts.residualViolations)
            for (law in listOf("L-L1", "L-L2", "L-L3", "L-L6", "L-L7", "L-L8", "L-L9", "L-L12")) assertTrue((counts.structural[law] ?: 0) > 0, "$law exercised zero cases")
            assertTrue(counts.stateFrames > 0 && counts.stSent > 0 && counts.stWithheld > 0, "LP-1 was not exercised in all three ways")
            assertTrue(l13.runsWithFailure > 0 && l13.controlFailures > 0 && l13.requesterIntentFailures > 0 && l13.lenderIntentFailures > 0, "L-L13 missed a durability point")
            assertTrue(l13.lenderEndOutcomeFailures > 0 && l13.lenderDeclineOutcomeFailures > 0 && l13.dialFailures > 0 && l13.tapChecked > 0, "L-L13 missed a durability point or never looked at the tap")
            assertEquals(0, l13.framesAfterControlFailure)
            assertEquals(0, l13.contentFramesAfterStickyFailure)
            assertTrue(connects > 0, "L-L14 exercised zero connects")
            assertEquals(0, wireDisagreements, "the two sockets of a run disagree about the bytes that crossed")
            assertTrue(W08Tls.frameCases.get() > 0 && W08Tls.rowChecks.get() > 0, "W08-frames-tls: a zero count")
            assertTrue(W08Tls.controlFailureCases.get() > 0 && W08Tls.tapAfterFailureChecks.get() > 0, "W08-frames-tls: no control-row failure case ran")
            assertEquals(0, W08Tls.framesAfterControlFailure.get(), "frames after a control-row failure")
            assertEquals(emptyList(), Refusal.entries.filter { it !in W08Tls.kinds }, "refusal kinds never exercised over TLS")
            assertEquals(emptyList(), RejectCode.entries.filter { it !in W08Tls.verdictsThroughSession && it !in W08Tls.verdictsDirect }, "manifest verdicts never exercised")
            assertEquals(emptySet(), HostileHandshakeSession.required() - W08Tls.exercised, "W08 handshake kinds (kind/role) that were never exercised")
            assertEquals(0, W08Tls.acceptedBadChains.get(), "W08: a bad chain was accepted")
            assertTrue(W08Tls.honestClientHellos.get() > 0, "W08 looked at no ClientHello of an honest dialler")
            assertEquals(0, W08Tls.honestPskClientHellos.get(), "W08: an honest dialler sent pre_shared_key")
            assertTrue(W08Tls.hostilePskClientHellos.get() >= 1, "the record tap never saw a hostile pre_shared_key: it cannot be trusted to see one")
            assertTrue(W08Tls.hostileEarlyDataClientHellos.get() >= 1, "the record tap never saw a hostile early_data extension")
            assertEquals(W08Tls.establishedSessions.get(), W08Tls.sessionsWithClientCertVerify.get(), "W08: an established session without a verified client chain")
            assertTrue(W08Tls.establishedSessions.get() > 0)
            assertTrue(W08Tls.sessionsUnderSessionEngine.get() > 0 && W08Tls.inboundRefusedRows.get() > 0 && W08Tls.dialOutcomes.isNotEmpty(), "the handshake half never reached the session engine")
        }
    }

    @AfterEach
    fun counted() {
        executed.incrementAndGet()
    }

    // ------------------------------------------------------------------------------------------------------------ 1: the whole lifecycle once

    @Test
    @Order(1)
    fun theWholeLifecycleOverARealTlsSession() {
        val r = Lifecycle.run(1)
        lifecycleRows = r.rowsA.size + r.rowsB.size
        val a = r.rowsA.map { "${it.meshKind}:${it.meshCode ?: "-"}:${it.phase ?: "-"}" }
        val b = r.rowsB.map { "${it.meshKind}:${it.meshCode ?: "-"}:${it.phase ?: "-"}" }
        assertEquals(
            listOf(
                "DIAL:-:INTENT", "DIAL:connected:OUTCOME", "CONTROL:HELLO:-", "CONTROL:HELLO_ACK:-", "CONTROL:STATE_REQ:-", "CONTROL:STATE:-", "CONTROL:MANIFEST_REQ:-", "MANIFEST_RECEIVED:VERIFIED:-",
                "INFER_SENT:-:INTENT", "INFER_SENT:-:OUTCOME", "INFER_SENT:-:INTENT", "INFER_SENT:CANCELLED:OUTCOME", "CONTROL:GOAWAY:shutdown:-", "SESSION:close:-",
            ),
            a,
        )
        assertEquals(
            listOf(
                "SESSION:established:-", "CONTROL:HELLO:-", "CONTROL:HELLO_ACK:-", "CONTROL:STATE_REQ:-", "CONTROL:STATE:-", "CONTROL:MANIFEST_REQ:-", "MANIFEST_SENT:MANIFEST:-", "INFER_SERVED:-:INTENT",
                "INFER_SERVED:-:OUTCOME", "INFER_SERVED:-:INTENT", "INFER_SERVED:CANCELLED:OUTCOME", "CONTROL:EXT_IGNORED:-", "CONTROL:GOAWAY:shutdown:-", "SESSION:close:-",
            ),
            b,
        )
        assertEquals(2, r.l15.measuredSessions)
        l15.add(r.l15)
        counts.add(r.counts)
        println("lifecycle A rows: $a")
        println("lifecycle B rows: $b")
    }

    // ------------------------------------------------------------------------------------------------------------ 2: L-L15 and L-L16 over seeded sessions

    @Test
    @Order(2)
    fun l15AndL16OverSeededCleanSessions() {
        for (seed in 1L..CLEAN_RUNS) {
            TlsRuns.clean(seed).use { r ->
                try {
                    TlsRuns.check(r, strict = true, counts, l15)
                    val link = r.link!!
                    val (a, b) = TlsOracle.ends(link)
                    connects += L14.check(r.world.log)
                    if (!TlsOracle.wireAgrees(a, b, resetRun = false, windows = windows)) wireDisagreements++
                    cleanRuns++
                } catch (e: AssertionError) {
                    throw AssertionError("clean run seed $seed: ${e.message}", e)
                }
            }
        }
        assertTrue(l15.measuredSessions >= 2 * CLEAN_RUNS, "every clean run has two MEASURED sessions")
    }

    @Test
    @Order(3)
    fun l16OverPairingSessionsOnRealTls() {
        for (seed in 1L..PAIRING_RUNS) {
            TlsPairing.run(seed).use { p ->
                try {
                    TlsPairing.check(p, counts, l15)
                    pairingRuns++
                } catch (e: AssertionError) {
                    throw AssertionError("pairing run seed $seed: ${e.message}", e)
                }
            }
        }
    }

    @Test
    @Order(4)
    fun l15OverRunsCutMidStreamByAPeerResetAndMidRecord() {
        for (i in 0 until RESET_RUNS) {
            TlsRuns.cutByReset(100L + i, crashDialer = i % 2 == 1, chunksBeforeCut = 2 + i % 5).use { r ->
                try {
                    TlsOracle.checkReset(r.link!!, r.crashed!!, counts, l15)
                    resetRuns++
                } catch (e: AssertionError) {
                    throw AssertionError("reset run $i (crashed ${r.crashed}): ${e.message}", e)
                }
            }
        }
        for (keep in listOf(1, 5, 7, 20, 38)) {
            val (w, link) = PartialRecord.run(300L + keep, keep)
            w.use { PartialRecord.checkSurvivor(w, link, l15) }
            resetRuns++
        }
        assertTrue(l15.estimatedSessions >= RESET_RUNS)
    }

    // ------------------------------------------------------------------------------------------------------------ 3: L-L13 and L-L14

    @Test
    @Order(5)
    fun l13AndL14WithASinkThatThrowsAtEveryDurabilityPointOfARealSession() {
        for (seed in 1L..SWEEP_SEEDS) {
            val base = TlsRuns.clean(seed)
            val nA = base.world.log.all<Ev.Appended>().count { it.node == "A" }
            val nB = base.world.log.all<Ev.Appended>().count { it.node == "B" }
            base.close()
            for ((node, n) in listOf("A" to nA, "B" to nB)) for (k in 0..n) for (variant in 0..1) {
                val sticky = (k + seed + variant) % 2L == 0L
                val mode = if (variant == 0) FailMode.BEFORE_WRITE else FailMode.AFTER_WRITE
                val plan = FailPlan(setOf(k), sticky, mode)
                TlsRuns.clean(seed, failA = if (node == "A") plan else null, failB = if (node == "B") plan else null).use { r ->
                    try {
                        TlsRuns.check(r, strict = false, counts, L15Stats())
                        L13Tls.check(r.world, r.link, mapOf(node to sticky), l13)
                        connects += L14.check(r.world.log)
                        failureRuns++
                    } catch (e: AssertionError) {
                        throw AssertionError("failure run seed $seed, $node fails append #$k (sticky=$sticky, $mode): ${e.message}", e)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ 4: the hostile-node suite, both halves, over real TLS

    @Test
    @Order(6)
    fun w08FramesHelloAndCodec() {
        HostileFramesTls.hello()
        HostileFramesTls.codec()
    }

    @Test
    @Order(7)
    fun w08FramesAttemptsAndRegistry() {
        HostileFramesTls.attempts()
        HostileFramesTls.registry()
    }

    @Test
    @Order(8)
    fun w08FramesExtensionsSplittingAndControlRowFailures() {
        HostileFramesTls.extensions()
        HostileFramesTls.splitting()
        HostileFramesTls.controlRowFailures()
    }

    @Test
    @Order(9)
    fun w08FramesAsARequesterAndManifests() {
        HostileFramesTls.requester()
        HostileFramesTls.manifests()
    }

    @Test
    @Order(10)
    fun w08HandshakeHalfAgainstTheRealSessionEngine() {
        HostileHandshakeSession.all()
    }
}
