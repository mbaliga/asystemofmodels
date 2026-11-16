package xyz.mdhv.asom.lab.proto.trust

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.policy.PeerStatus

/**
 * Fix wave 4 of the trust track (ERRATA ERR-FX4-TR-2 to ERR-FX4-TR-8): the class column fails closed, a corrupt row can be revoked and then forgotten, a throwing
 * registry listener is contained, and a store write that took effect and then threw is not reported as a failure. Evidence label: LAB, oracle: self.
 */
class PeerRegistryFixesTest {
    companion object {
        val laws = LawCounters("registry-fixes")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(
            setOf(
                "class-column-denies", "corrupt-row-revoked-then-forgotten", "listener-contained", "write-then-throw-verified", "write-then-throw-unknown", "write-not-applied-refused",
            ),
        )
    }

    private val pin = Pin.ofHash(ByteArray(32) { (it * 5 + 3).toByte() })
    private val other = Pin.ofHash(ByteArray(32) { (it * 9 + 1).toByte() })

    private fun pairedRegistry(store: PeerStore = InMemoryPeerStore(), p: Pin = pin): PeerRegistry {
        val reg = PeerRegistry(store)
        val c = PairingCeremony(p, "peer", "linux", PeerClass.OWN, ByteArray(32) { 1 }, setOf(Scope.INFER, Scope.STATE))
        c.localApprove()
        c.remoteApprove()
        assertTrue(reg.commitPairing(c, 1) is RegistryResult.Changed)
        return reg
    }

    private fun plant(store: InMemoryPeerStore, clazz: String, status: Int, p: Pin = pin) =
        store.plant(StoredRow(p, "peer", "linux", clazz, status, "[\"infer\",\"state\"]", 1, "D1", null, false, "user", 0))

    // ------------------------------------------------------------------ the class column

    @Test
    fun aRowOfAnyClassButOwnIsNeitherServedNorRoutedNorKept() {
        for (cls in listOf("other", "OTHER", "garbage", "", "Own", " own", "own ")) {
            for (status in listOf(StatusCodes.PAIRED, StatusCodes.SUSPENDED)) {
                val store = InMemoryPeerStore()
                val reg = PeerRegistry(store)
                plant(store, cls, status)
                val tag = "class '$cls' status $status"
                for (scope in listOf("infer", "state", "manifest")) {
                    val d = reg.authorize(pin, scope)
                    assertTrue(d is AuthDecision.Deny, "$tag: authorize($scope) was $d")
                    assertEquals("UNKNOWN_CLASS", d.reason.name, tag)
                }
                val o = reg.authorizeOutbound(pin)
                assertTrue(o is OutboundDecision.Deny, "$tag: outbound was $o")
                assertEquals("UNKNOWN_CLASS", o.reason.name, tag)
                assertEquals(emptyList(), reg.granted(pin), tag)
                val s = reg.sessionDecision(pin)
                assertTrue(s is SessionDecision.Goaway && s.reason == GoawayReason.SUSPENDED, "$tag: session decision was $s")
                assertTrue(reg.statusLookup(pin) is StatusLookup.Corrupt, "$tag: a chain verifier must see the row as not clean, got ${reg.statusLookup(pin)}")
                assertEquals(RegistryRefusal.CORRUPT_ROW, (reg.restore(pin, 5) as? RegistryResult.Refused)?.code, tag)
                assertEquals(RegistryRefusal.CORRUPT_ROW, (reg.setRoute(pin, true, RouteCeiling.D1) as? RegistryResult.Refused)?.code, tag)
                laws.bump("class-column-denies")
            }
        }
    }

    @Test
    fun aRevokedRowIsStillRevokedWhateverItsClass() {
        val store = InMemoryPeerStore()
        val reg = PeerRegistry(store)
        plant(store, "other", StatusCodes.REVOKED)
        assertEquals(StatusLookup.Known(PeerStatus.REVOKED), reg.statusLookup(pin))
        assertTrue(reg.forget(pin) is RegistryResult.Changed)
    }

    @Test
    fun anOwnRowStillWorksAsBefore() {
        val reg = pairedRegistry()
        assertEquals(AuthDecision.Allow, reg.authorize(pin, "infer"))
        assertEquals(listOf(Scope.INFER, Scope.STATE), reg.granted(pin))
    }

    // ------------------------------------------------------------------ a corrupt row can be revoked, then forgotten

    @Test
    fun aRowWithAnUnknownStatusCanBeRevokedAndThenForgottenAndNothingElseMovesIt() {
        for (bad in listOf(0, 1, 5, 7, 9, -1, Int.MAX_VALUE)) {
            val store = InMemoryPeerStore()
            val reg = PeerRegistry(store)
            plant(store, "own", bad)
            val heard = ArrayList<StatusChange>()
            reg.addListener { heard += it }
            val tag = "status $bad"
            assertEquals(RegistryRefusal.CORRUPT_ROW, (reg.pause(pin, 2) as? RegistryResult.Refused)?.code, tag)
            assertEquals(RegistryRefusal.CORRUPT_ROW, (reg.restore(pin, 2) as? RegistryResult.Refused)?.code, tag)
            assertEquals(RegistryRefusal.CORRUPT_ROW, (reg.forget(pin) as? RegistryResult.Refused)?.code, "$tag: forget needs a REVOKED row")
            assertEquals(RegistryRefusal.CORRUPT_ROW, (reg.setInboundScopes(pin, setOf(Scope.INFER)) as? RegistryResult.Refused)?.code, tag)
            val c = PairingCeremony(pin, "peer", "linux", PeerClass.OWN, ByteArray(32) { 1 }, setOf(Scope.INFER)).apply { localApprove(); remoteApprove() }
            assertEquals(RegistryRefusal.CORRUPT_ROW, (reg.commitPairing(c, 2) as? RegistryResult.Refused)?.code, tag)
            assertTrue(reg.statusLookup(pin) is StatusLookup.Corrupt, tag)
            assertEquals(RegistryRefusal.CORRUPT_ROW, (reg.revoke(pin, 8) as? RegistryResult.Refused)?.code, "$tag: the ordinary revoke stays refused (vector W04-613)")
            val r = reg.revokeCorrupt(pin, 9)
            assertTrue(r is RegistryResult.Changed, "$tag: revokeCorrupt of a corrupt row was $r")
            assertEquals(PeerStatus.REVOKED, r.change.to, tag)
            assertEquals(GoawayReason.REVOKED, r.change.goaway, "$tag: the sessions of the peer are closed")
            assertEquals(1, heard.size, tag)
            assertEquals(StatusLookup.Known(PeerStatus.REVOKED), reg.statusLookup(pin), tag)
            assertTrue(reg.authorize(pin, "infer") is AuthDecision.Deny, tag)
            assertEquals(RegistryRefusal.NOT_CORRUPT, (reg.revokeCorrupt(pin, 10) as? RegistryResult.Refused)?.code, "$tag: a second revokeCorrupt is refused")
            assertTrue(reg.forget(pin) is RegistryResult.Changed, "$tag: forget after revoke")
            assertEquals(StatusLookup.Absent, reg.statusLookup(pin), tag)
            laws.bump("corrupt-row-revoked-then-forgotten")
        }
    }

    @Test
    fun aSuspendedRowOfAnUnknownClassCanBeRevokedAndThenForgotten() {
        val store = InMemoryPeerStore()
        val reg = PeerRegistry(store)
        plant(store, "other", StatusCodes.SUSPENDED)
        val r = reg.revokeCorrupt(pin, 3)
        assertTrue(r is RegistryResult.Changed)
        assertEquals(PeerStatus.SUSPENDED, r.change.from)
        assertNull(r.change.goaway, "it was already suspended")
        assertTrue(reg.forget(pin) is RegistryResult.Changed)
        laws.bump("corrupt-row-revoked-then-forgotten")
    }

    @Test
    fun revokeCorruptRefusesACleanOrAbsentOrUnreadableRow() {
        val store = InMemoryPeerStore()
        val reg = pairedRegistry(store)
        assertEquals(RegistryRefusal.NOT_CORRUPT, (reg.revokeCorrupt(pin, 2) as? RegistryResult.Refused)?.code)
        assertEquals(AuthDecision.Allow, reg.authorize(pin, "infer"), "a clean PAIRED row is untouched")
        assertEquals(RegistryRefusal.NOT_FOUND, (reg.revokeCorrupt(other, 2) as? RegistryResult.Refused)?.code)
        plant(store, "own", 7, other)
        store.markUnreadable(other)
        assertEquals(RegistryRefusal.STORE_UNREADABLE, (reg.revokeCorrupt(other, 2) as? RegistryResult.Refused)?.code)
    }

    @Test
    fun revokingACorruptRowFailsClosedWhenTheWriteFails() {
        val store = InMemoryPeerStore()
        val reg = PeerRegistry(store)
        plant(store, "own", 7)
        store.failWrites = true
        assertEquals(RegistryRefusal.STORE_WRITE_FAILED, (reg.revokeCorrupt(pin, 3) as? RegistryResult.Refused)?.code)
        store.failWrites = false
        assertTrue(reg.statusLookup(pin) is StatusLookup.Corrupt)
    }

    // ------------------------------------------------------------------ listener containment

    @Test
    fun aThrowingListenerNeitherFailsADurableChangeNorHidesItFromTheLaterListeners() {
        val reg = pairedRegistry(InMemoryPeerStore(), pin)
        var heardBefore = 0
        var heardAfter = 0
        reg.addListener { heardBefore++ }
        reg.addListener { throw IllegalStateException("ui listener bug") }
        reg.addListener { heardAfter++ }
        val r = reg.revoke(pin, 2)
        assertTrue(r is RegistryResult.Changed, "the row is durable, so the call reports the change: $r")
        assertEquals(GoawayReason.REVOKED, r.change.goaway)
        assertEquals(1, heardBefore)
        assertEquals(1, heardAfter, "a listener registered after the throwing one still hears the change")
        assertEquals(1, reg.listenerErrors.get(), "the failure is counted, not hidden")
        assertEquals(StatusLookup.Known(PeerStatus.REVOKED), reg.statusLookup(pin))
        assertTrue(reg.revoke(pin, 3) is RegistryResult.Refused, "a retry is a revoked-row refusal, not a second change")
        laws.bump("listener-contained")
    }

    @Test
    fun everyKindOfChangeContainsAThrowingListener() {
        val store = InMemoryPeerStore()
        val reg = PeerRegistry(store)
        var heard = 0
        reg.addListener { throw RuntimeException("always") }
        reg.addListener { heard++ }
        val c = PairingCeremony(pin, "peer", "linux", PeerClass.OWN, ByteArray(32) { 1 }, setOf(Scope.INFER)).apply { localApprove(); remoteApprove() }
        assertTrue(reg.commitPairing(c, 1) is RegistryResult.Changed)
        assertTrue(reg.pause(pin, 2) is RegistryResult.Changed)
        assertTrue(reg.restore(pin, 3) is RegistryResult.Changed)
        assertTrue(reg.revoke(pin, 4) is RegistryResult.Changed)
        assertTrue(reg.forget(pin) is RegistryResult.Changed)
        assertEquals(5, heard)
        assertEquals(5, reg.listenerErrors.get())
        laws.bump("listener-contained", 5)
    }

    // ------------------------------------------------------------------ a write whose outcome is not what it reported

    private enum class Mode { NORMAL, APPLY_THEN_THROW, THROW_WITHOUT_APPLYING, APPLY_THEN_THROW_AND_UNREADABLE }

    private class FlakyStore(val inner: InMemoryPeerStore) : PeerStore {
        @Volatile var mode = Mode.NORMAL
        @Volatile private var unreadableNow = false

        override fun read(pin: Pin): StoreRead = if (unreadableNow) StoreRead.Unreadable else inner.read(pin)

        override fun write(row: StoredRow): Boolean {
            when (mode) {
                Mode.NORMAL -> return inner.write(row)
                Mode.APPLY_THEN_THROW -> { inner.write(row); throw IOException("fsync failed after the row was in place") }
                Mode.THROW_WITHOUT_APPLYING -> throw IOException("no space left")
                Mode.APPLY_THEN_THROW_AND_UNREADABLE -> { inner.write(row); unreadableNow = true; throw IOException("fsync failed, store gone") }
            }
        }

        override fun delete(pin: Pin): Boolean {
            when (mode) {
                Mode.NORMAL -> return inner.delete(pin)
                Mode.APPLY_THEN_THROW -> { inner.delete(pin); throw IOException("fsync failed after the delete") }
                Mode.THROW_WITHOUT_APPLYING -> throw IOException("no space left")
                Mode.APPLY_THEN_THROW_AND_UNREADABLE -> { inner.delete(pin); unreadableNow = true; throw IOException("store gone") }
            }
        }
    }

    @Test
    fun aRestoreWhoseWriteTookEffectThenThrewIsReportedAsDoneNotAsFailed() {
        val store = FlakyStore(InMemoryPeerStore())
        val reg = pairedRegistry(store)
        val heard = ArrayList<StatusChange>()
        reg.addListener { heard += it }
        assertTrue(reg.pause(pin, 2) is RegistryResult.Changed)
        store.mode = Mode.APPLY_THEN_THROW
        val r = reg.restore(pin, 3)
        assertTrue(r is RegistryResult.Changed, "the row is PAIRED in the store, so the outcome is not a failure: $r")
        assertEquals(PeerStatus.PAIRED, r.change.to)
        assertEquals(2, heard.size)
        assertEquals(AuthDecision.Allow, reg.authorize(pin, "infer"))
        laws.bump("write-then-throw-verified")
    }

    @Test
    fun aRevokeWhoseWriteTookEffectThenThrewStillPostsTheGoaway() {
        val store = FlakyStore(InMemoryPeerStore())
        val reg = pairedRegistry(store)
        val heard = ArrayList<StatusChange>()
        reg.addListener { heard += it }
        store.mode = Mode.APPLY_THEN_THROW
        val r = reg.revoke(pin, 2)
        assertTrue(r is RegistryResult.Changed, "$r")
        assertEquals(GoawayReason.REVOKED, heard.single().goaway)
        assertEquals(StatusLookup.Known(PeerStatus.REVOKED), reg.statusLookup(pin))
        laws.bump("write-then-throw-verified")
    }

    @Test
    fun aPairingCommitAndAForgetThatTookEffectThenThrewAreReportedAsDone() {
        val store = FlakyStore(InMemoryPeerStore())
        store.mode = Mode.APPLY_THEN_THROW
        val reg = PeerRegistry(store)
        val c = PairingCeremony(pin, "peer", "linux", PeerClass.OWN, ByteArray(32) { 1 }, setOf(Scope.INFER)).apply { localApprove(); remoteApprove() }
        assertTrue(reg.commitPairing(c, 1) is RegistryResult.Changed)
        assertEquals(StatusLookup.Known(PeerStatus.PAIRED), reg.statusLookup(pin))
        assertTrue(reg.revoke(pin, 2) is RegistryResult.Changed)
        assertTrue(reg.forget(pin) is RegistryResult.Changed)
        assertEquals(StatusLookup.Absent, reg.statusLookup(pin))
        laws.bump("write-then-throw-verified", 3)
    }

    @Test
    fun aWriteThatThrewWithoutApplyingIsAFailureAndNobodyIsTold() {
        val store = FlakyStore(InMemoryPeerStore())
        val reg = pairedRegistry(store)
        val heard = ArrayList<StatusChange>()
        reg.addListener { heard += it }
        store.mode = Mode.THROW_WITHOUT_APPLYING
        assertEquals(RegistryRefusal.STORE_WRITE_FAILED, (reg.revoke(pin, 2) as? RegistryResult.Refused)?.code)
        assertEquals(RegistryRefusal.STORE_WRITE_FAILED, (reg.pause(pin, 2) as? RegistryResult.Refused)?.code)
        assertEquals(RegistryRefusal.STORE_WRITE_FAILED, (reg.setRoute(pin, true, RouteCeiling.D1) as? RegistryResult.Refused)?.code)
        assertEquals(emptyList(), heard)
        assertEquals(StatusLookup.Known(PeerStatus.PAIRED), reg.statusLookup(pin))
        laws.bump("write-not-applied-refused", 3)
    }

    @Test
    fun aWriteThatThrewAndCannotBeReadBackIsAnUnknownOutcomeAndTheSessionsAreToldToSuspend() {
        val store = FlakyStore(InMemoryPeerStore())
        val reg = pairedRegistry(store)
        val heard = ArrayList<StatusChange>()
        reg.addListener { heard += it }
        store.mode = Mode.APPLY_THEN_THROW_AND_UNREADABLE
        val r = reg.revoke(pin, 2)
        assertTrue(r is RegistryResult.Refused)
        assertEquals("STORE_OUTCOME_UNKNOWN", r.code.name, "not a plain write failure: the row may be in place")
        val change = heard.single()
        assertEquals(GoawayReason.SUSPENDED, change.goaway, "a peer whose row might have left PAIRED loses its sessions")
        assertEquals(pin, change.pin)
        assertTrue(change.outcomeUnknown)
        assertTrue(reg.sessionDecision(pin).let { it is SessionDecision.Goaway && it.reason == GoawayReason.SUSPENDED })
        assertTrue(reg.authorize(pin, "infer") is AuthDecision.Deny)
        laws.bump("write-then-throw-unknown")
    }

    @Test
    fun anUnknownOutcomeOfARestoreTellsNoListener() {
        val store = FlakyStore(InMemoryPeerStore())
        val reg = pairedRegistry(store)
        assertTrue(reg.pause(pin, 2) is RegistryResult.Changed)
        val heard = ArrayList<StatusChange>()
        reg.addListener { heard += it }
        store.mode = Mode.APPLY_THEN_THROW_AND_UNREADABLE
        val r = reg.restore(pin, 3)
        assertTrue(r is RegistryResult.Refused)
        assertEquals("STORE_OUTCOME_UNKNOWN", r.code.name)
        assertEquals(emptyList(), heard, "a restore never opens sessions; the unreadable row denies")
        laws.bump("write-then-throw-unknown")
    }
}
