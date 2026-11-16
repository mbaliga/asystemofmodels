package xyz.mdhv.asom.lab.proto.trust

import java.util.SplittableRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Timeout
import xyz.mdhv.asom.lab.policy.PeerStatus

/**
 * PTT-2: registry mutations are read-modify-write on a store row, so a revoke that lands between another mutation's read and its write must not be
 * overwritten (trust.md 15 L3: once REVOKED, a row stays REVOKED; only a local Forget removes it). The deterministic cases hold a mutation between its
 * read and its write and let a second thread revoke in that window; the stress case interleaves random operations. Evidence label: LAB, oracle: self.
 */
@Timeout(120)
class PeerRegistryConcurrencyTest {
    companion object {
        val laws = LawCounters("registry-concurrency")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("L3-revoked-absorbing-under-race", "L3-revoked-absorbing-stress", "revoke-landed-in-window"))

        private val pin = Pin.ofHash(ByteArray(32) { (it * 7 + 1).toByte() })
    }

    /** Parks the first reading thread after its read, until [release] or 600 ms, so another thread can act inside that mutation's read-write window. */
    private class WindowStore(val inner: InMemoryPeerStore) : PeerStore {
        @Volatile
        var armedThread: Thread? = null
        val readDone = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun read(pin: Pin): StoreRead {
            val r = inner.read(pin)
            val t = armedThread
            if (t != null && t === Thread.currentThread()) {
                armedThread = null
                readDone.countDown()
                release.await(600, TimeUnit.MILLISECONDS)
            }
            return r
        }

        override fun write(row: StoredRow): Boolean = inner.write(row)
        override fun delete(pin: Pin): Boolean = inner.delete(pin)
    }

    private fun paired(reg: PeerRegistry) {
        val c = PairingCeremony(pin, "peer", "linux", PeerClass.OWN, ByteArray(32) { 1 }, setOf(Scope.INFER))
        c.localApprove()
        c.remoteApprove()
        assertTrue(reg.commitPairing(c, 1) is RegistryResult.Changed)
    }

    private fun raceWithRevoke(name: String, prepare: (PeerRegistry) -> Unit, op: (PeerRegistry) -> RegistryResult) {
        val store = WindowStore(InMemoryPeerStore())
        val reg = PeerRegistry(store)
        paired(reg)
        prepare(reg)
        val revokeResult = AtomicReference<RegistryResult>()
        val revoked = CountDownLatch(1)
        val opResult = AtomicReference<RegistryResult>()
        val opDone = CountDownLatch(1)
        val mutator = Thread {
            try {
                opResult.set(op(reg))
            } finally {
                opDone.countDown()
            }
        }.also { it.isDaemon = true }
        store.armedThread = mutator
        mutator.start()
        assertTrue(store.readDone.await(10, TimeUnit.SECONDS), "$name: the mutation never read")
        Thread {
            try {
                revokeResult.set(reg.revoke(pin, 50))
            } finally {
                revoked.countDown()
            }
        }.also { it.isDaemon = true; it.start() }
        assertTrue(opDone.await(10, TimeUnit.SECONDS), "$name: the mutation did not finish")
        assertTrue(revoked.await(10, TimeUnit.SECONDS), "$name: the revoke did not finish")
        val revoke = revokeResult.get()
        if (revoke is RegistryResult.Changed) laws.bump("revoke-landed-in-window")
        val after = reg.statusLookup(pin)
        assertTrue(revoke is RegistryResult.Changed || (opResult.get() as? RegistryResult.Changed)?.change?.to == PeerStatus.REVOKED, "$name: neither side revoked the row: $revoke / ${opResult.get()}")
        assertEquals(StatusLookup.Known(PeerStatus.REVOKED), after, "$name: a revoke that returned Changed was overwritten (status $after)")
        assertTrue(reg.authorize(pin, "infer") is AuthDecision.Deny, "$name: a revoked peer is authorised")
        laws.bump("L3-revoked-absorbing-under-race")
    }

    @Test
    fun aRevokeBetweenAScopeEditsReadAndWriteIsNotOverwritten() =
        raceWithRevoke("setInboundScopes", {}) { it.setInboundScopes(pin, setOf(Scope.INFER, Scope.STATE)) }

    @Test
    fun aRevokeBetweenARouteEditsReadAndWriteIsNotOverwritten() =
        raceWithRevoke("setRoute", {}) { it.setRoute(pin, true, RouteCeiling.D2) }

    @Test
    fun aRevokeBetweenARestoresReadAndWriteIsNotOverwritten() =
        raceWithRevoke("restore", { assertTrue(it.pause(pin, 2) is RegistryResult.Changed) }) { it.restore(pin, 3) }

    @Test
    fun aRevokeBetweenAPausesReadAndWriteIsNotOverwritten() =
        raceWithRevoke("pause", {}) { it.pause(pin, 3) }

    private class JitterStore(val inner: InMemoryPeerStore, seed: Long) : PeerStore {
        private val rnd = ThreadLocal.withInitial { SplittableRandom(seed + Thread.currentThread().id) }
        private fun jitter() {
            when (rnd.get().nextInt(4)) {
                0 -> Thread.yield()
                1 -> Thread.sleep(0, 200_000)
                else -> Unit
            }
        }

        override fun read(pin: Pin): StoreRead {
            val r = inner.read(pin)
            jitter()
            return r
        }

        override fun write(row: StoredRow): Boolean {
            jitter()
            return inner.write(row)
        }

        override fun delete(pin: Pin): Boolean = inner.delete(pin)
    }

    @Test
    fun interleavedMutationsNeverResurrectARevokedRow() {
        var revokesThatReturnedChanged = 0
        repeat(40) { round ->
            val reg = PeerRegistry(JitterStore(InMemoryPeerStore(), round.toLong()))
            paired(reg)
            val revoked = AtomicBoolean(false)
            val violation = AtomicReference<String?>()
            val stop = AtomicBoolean(false)
            val ops = AtomicInteger()
            val workers = List(4) { w ->
                Thread {
                    val rnd = SplittableRandom(round * 31L + w)
                    while (!stop.get()) {
                        ops.incrementAndGet()
                        when (rnd.nextInt(5)) {
                            0 -> reg.setInboundScopes(pin, Scope.entries.filter { rnd.nextBoolean() }.toSet())
                            1 -> reg.setRoute(pin, rnd.nextBoolean(), if (rnd.nextBoolean()) RouteCeiling.D1 else RouteCeiling.D2)
                            2 -> reg.restore(pin, 5)
                            3 -> reg.pause(pin, 4)
                            else -> if (w == 0 && reg.revoke(pin, 6) is RegistryResult.Changed) revoked.set(true)
                        }
                    }
                }.also { it.isDaemon = true; it.start() }
            }
            val deadline = System.nanoTime() + 150_000_000L
            while (System.nanoTime() < deadline) {
                if (revoked.get()) {
                    val s = reg.statusLookup(pin)
                    if (s != StatusLookup.Known(PeerStatus.REVOKED)) violation.compareAndSet(null, "status $s after a revoke returned Changed")
                    if (reg.authorize(pin, "infer") is AuthDecision.Allow) violation.compareAndSet(null, "authorize allowed after a revoke returned Changed")
                }
            }
            stop.set(true)
            workers.forEach { it.join(10_000) }
            assertEquals(null, violation.get(), "round $round")
            if (revoked.get()) {
                revokesThatReturnedChanged++
                assertEquals(StatusLookup.Known(PeerStatus.REVOKED), reg.statusLookup(pin), "round $round: final status")
            }
            laws.bump("L3-revoked-absorbing-stress")
            assertTrue(ops.get() > 0)
        }
        assertTrue(revokesThatReturnedChanged > 0, "no round ever revoked: the stress test exercised nothing")
        println("  registry stress: $revokesThatReturnedChanged of 40 rounds revoked")
    }
}
