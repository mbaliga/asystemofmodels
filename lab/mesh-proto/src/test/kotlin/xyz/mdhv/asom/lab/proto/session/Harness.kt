package xyz.mdhv.asom.lab.proto.session

import java.util.SplittableRandom
import java.util.concurrent.atomic.AtomicLong
import xyz.mdhv.asom.lab.bench.Scenarios
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.ledger.CrashInjectingSink
import xyz.mdhv.asom.lab.ledger.FailMode
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.MemorySink
import xyz.mdhv.asom.lab.ledger.NodeLedger
import xyz.mdhv.asom.lab.ledger.RowSink
import xyz.mdhv.asom.lab.manifest.Accelerator
import xyz.mdhv.asom.lab.manifest.Cluster
import xyz.mdhv.asom.lab.manifest.Device
import xyz.mdhv.asom.lab.manifest.InMemorySeqStore
import xyz.mdhv.asom.lab.manifest.ManifestInputs
import xyz.mdhv.asom.lab.manifest.ManifestSigner
import xyz.mdhv.asom.lab.manifest.Mode
import xyz.mdhv.asom.lab.manifest.Os
import xyz.mdhv.asom.lab.manifest.PlatformIds
import xyz.mdhv.asom.lab.manifest.Producer
import xyz.mdhv.asom.lab.manifest.ProducerEngine
import xyz.mdhv.asom.lab.manifest.ProducerHarness
import xyz.mdhv.asom.lab.manifest.TestOnlyKeys
import xyz.mdhv.asom.lab.manifest.VerifyContext
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.LenderLocalView
import xyz.mdhv.asom.lab.policy.PresenceSignals
import xyz.mdhv.asom.lab.proto.trust.InMemoryPeerStore
import xyz.mdhv.asom.lab.proto.trust.PairingCeremony
import xyz.mdhv.asom.lab.proto.trust.PeerClass
import xyz.mdhv.asom.lab.proto.trust.PeerRegistry
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.trust.Scope
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.Feature
import xyz.mdhv.asom.lab.proto.wire.KeyTier
import xyz.mdhv.asom.lab.proto.wire.Platform
import xyz.mdhv.asom.lab.proto.wire.Terminal

const val CLOCK_BASE = 1_790_676_000_000L
const val MANIFEST_NOW = 1_790_676_060_000L

/** A seeded id source: runs are reproducible. */
class SeededIds(seed: Long) : IdSource {
    private val rnd = SplittableRandom(seed)

    override fun bytes(n: Int): ByteArray = ByteArray(n) { rnd.nextInt(256).toByte() }
}

// ------------------------------------------------------------------------------------------------------------------ the engine double

/** A fake engine: it streams scripted bytes, can fail (a `Throwable` in the script is thrown from `next`), and can be cancelled. Nothing generates tokens. */
class FakeEngine(private val node: String, private val log: Log) : EnginePort {
    val models = linkedMapOf("m1" to ModelOffer("m1", "a".repeat(64)), "m2" to ModelOffer("m2", "b".repeat(64)))
    var script: (EngineRequest) -> List<Any> = {
        listOf(EngineEvent.Head(200, "m1"), EngineEvent.Chunk("hello".toByteArray()), EngineEvent.Chunk(" world".toByteArray()), EngineEvent.End(Terminal.DONE, 200, 12))
    }
    val opened = ArrayList<EngineRequest>()
    val cancelled = ArrayList<String>()

    override fun offered(model: String): ModelOffer? = models[model]

    override fun open(request: EngineRequest): EngineRun {
        opened += request
        log.add(Ev.EngineOpen(node, request.attemptId))
        return Run(request.attemptId, script(request))
    }

    private inner class Run(private val id: String, private val steps: List<Any>) : EngineRun {
        private var i = 0
        private var stopped = false

        override fun next(): EngineEvent {
            if (stopped) return EngineEvent.End(Terminal.CANCELLED, 499, null)
            if (i >= steps.size) return EngineEvent.End(Terminal.DONE, 200, 0)
            val s = steps[i++]
            if (s is Throwable) throw s
            return s as EngineEvent
        }

        override fun cancel() {
            stopped = true
            cancelled += id
            log.add(Ev.EngineCancel(node, id))
        }
    }
}

// ------------------------------------------------------------------------------------------------------------------ live state and manifests

/** Live state with every presence signal set to a distinctive value, so a leak into any frame is visible. */
class FakeLive : LivePort {
    private var seq = 0L
    var fsm: Fsm = Fsm.SERVING

    override fun view(): LenderLocalView = LenderLocalView(
        fsm = fsm, powerSource = "ac", charging = true, batteryBand = null, batteryPercentExact = 87, thermalBand = 1, governor = Governor.RUN, backend = "vulkan", commit = "4f1c2ab",
        confVersion = "1.0.0", held = listOf("c".repeat(64)), localQueued = 1, peerQueued = 0, loadedModels = listOf("SECRET-MODEL"), freeMemoryBytes = 123_456_789L,
        manifestSeq = null, manifestDigest = null,
        presence = PresenceSignals(true, 42, true, "SECRET-APP", true, "SECRET-USER", "SECRET-LOGIN", 777),
    )

    override fun nextSeq(): Long = ++seq

    override fun sampledAgeMs(): Long = 800
}

object ManifestFixtures {
    val cache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    val bench by lazy {
        val r = Scenarios.run((StrictJson.parse("""{"preset":"phone","plan":"standard"}""".toByteArray()) as ParseResult.Ok).value)
        check(r.outcome == "completed") { "scenario did not complete: ${r.outcome}" }
        r.doc!!
    }

    val inputs: ManifestInputs by lazy {
        val b = bench
        ManifestInputs(
            Producer("asom-android", "4.0.0", ProducerHarness("asom-bench", "1.0.0", "asom-bench-method/1", b.harness.confVersion), ProducerEngine(b.harness.engine.name, b.harness.engine.commit.take(8), b.harness.engine.buildFlags)),
            Device(
                cls = b.device.form, vendor = b.device.maker, model = b.device.model, platformIds = PlatformIds("example", "ex1", "Example", "EX-1", "ex1_global"),
                os = Os(b.device.platform, b.device.osVersion, "2026-09-01"), socVendor = "ExampleSilicon", socName = b.device.soc, logicalCores = 8,
                clusters = listOf(Cluster(2, 4_320_000), Cluster(6, 3_530_000)), memoryTotalBytes = b.device.memTotalBytes,
                accelerators = listOf(Accelerator("gpu", "ExampleSilicon", "ExampleGPU 800", listOf("opencl", "vulkan"), null), Accelerator("npu", "ExampleSilicon", "ExampleNPU", emptyList(), null)),
                battery = true, batteryDesignMilliWh = 25_000, batteryDesignPresent = true, cooling = "fan", stateSource = "android-thermal-headroom",
            ),
            b,
        )
    }
}

/** Signs with a TEST-ONLY key through the lab signer, and verifies a received manifest against the SPKI it is told the peer has. */
class TestManifestPort(private val key: TestOnlyKeys.Entry, private val peerSpkis: Map<String, ByteArray>) : ManifestPort {
    private val signer = ManifestSigner("0.2.0", productionKeys = false)
    private val seqs = InMemorySeqStore()
    var available = true
    var override: ByteArray? = null
    var ctxOverride: VerifyContext? = null
    val verified = ArrayList<String>()

    override fun present(challenge: ByteArray): ByteArray? {
        if (!available) return null
        override?.let { return it }
        return ManifestFixtures.cache.getOrPut(key.name + ":" + challenge.joinToString("") { "%02x".format(it) }) {
            signer.signOwn(ManifestFixtures.inputs, key.keyPair(), "strongbox", challenge, MANIFEST_NOW, seqs).container
        }
    }

    override fun contextFor(peer: Pin, challenge: ByteArray): VerifyContext = ctxOverride ?: VerifyContext(
        Mode.MESH, pinnedSpki = peerSpkis[peer.nodeId], expectedChallenge = challenge, confFloor = "0.2.0", productionKeys = false, nowMs = MANIFEST_NOW,
    )

    override fun onVerified(peer: Pin, verified: xyz.mdhv.asom.lab.manifest.Verified) {
        this.verified += peer.nodeId
    }
}

// ------------------------------------------------------------------------------------------------------------------ one node and a world of two

class FailPlan(val failAt: Set<Int>, val sticky: Boolean, val mode: FailMode = FailMode.BEFORE_WRITE)

class Kit(val name: String, val key: TestOnlyKeys.Entry, val log: Log, seed: Long, fail: FailPlan?, peerSpki: Map<String, ByteArray>, limits: xyz.mdhv.asom.lab.proto.wire.Limits? = null) {
    val pin: Pin = Pin.fromNodeId(key.nodeId)!!
    val store = InMemoryPeerStore()
    val registry = PeerRegistry(store)
    val mem = MemorySink()
    val crash: CrashInjectingSink? = fail?.let { CrashInjectingSink(mem, it.failAt, it.mode, it.sticky) }
    private val clock = AtomicLong(CLOCK_BASE)

    /** The injected clock without reading it (a read moves it by 3 ms). */
    fun peek(): Long = clock.get()

    fun advance(ms: Long): Long = clock.addAndGet(ms)

    val ledger = NodeLedger(name, SpySink(name, crash ?: mem, log)) { clock.addAndGet(3) }
    val engine = FakeEngine(name, log)
    val live = FakeLive()
    var serving = ServingView()
    val manifest = TestManifestPort(key, peerSpki)
    val node = MeshNode(
        if (limits == null) NodeConfig(pin, "Display Name $name", Platform.LINUX, KeyTier.FILE, "asom-lab/0.0.1", productionKeys = false)
        else NodeConfig(pin, "Display Name $name", Platform.LINUX, KeyTier.FILE, "asom-lab/0.0.1", limits = limits, productionKeys = false),
        ledger, registry, engine, { serving }, live, manifest, SeededIds(seed),
        SessionObserver { s, type, stream, payload -> log.add(Ev.Dispatch(name, s, type, stream, payload)) },
    )

    fun rows(): List<LabRouteRecord> = mem.all()

    /** Pairs [other] (both approvals) with the scopes it may use on this node. */
    fun pair(other: Pin, scopes: Set<Scope> = setOf(Scope.INFER, Scope.MANIFEST, Scope.STATE)) {
        val c = PairingCeremony(other, "peer", "linux", PeerClass.OWN, ByteArray(32) { 7 }, scopes)
        c.localApprove()
        c.remoteApprove()
        registry.commitPairing(c, CLOCK_BASE)
    }
}

class Link(val kit: String, val session: Session, val conn: MemConnection) {
    var eof = false
}

class World(val seed: Long = 1, failA: FailPlan? = null, failB: FailPlan? = null, val maxChunk: Int = 64, keyAName: String = "key1", keyBName: String = "key2", val measured: Boolean = true, limitsB: xyz.mdhv.asom.lab.proto.wire.Limits? = null) {
    val log = Log()
    val rnd = SplittableRandom(seed)
    private val keyA = TestOnlyKeys.key(keyAName)
    private val keyB = TestOnlyKeys.key(keyBName)
    private val spkis = TestOnlyKeys.entries.associate { it.nodeId to it.spki }
    val a = Kit("A", keyA, log, seed * 2 + 1, failA, spkis)
    val b = Kit("B", keyB, log, seed * 2 + 2, failB, spkis, limitsB)
    val links = ArrayList<Link>()

    /** The scopes B granted A (random runs change them before the dial). */
    var bGrantsA: Set<Scope> = setOf(Scope.INFER, Scope.MANIFEST, Scope.STATE)

    /** Every 8-character window of every request body sent in this world (L-L8), and every request id (L-L7). */
    val bodyGrams = HashSet<String>()
    val requestIds = ArrayList<String>()

    fun noteBody(body: ByteArray, requestId: String?) {
        val t = String(body, Charsets.ISO_8859_1)
        for (i in 0..t.length - 8) bodyGrams += t.substring(i, i + 8)
        requestId?.let { requestIds += it }
    }

    init {
        a.pair(b.pin)
        b.pair(a.pin)
    }

    /** A dials B: the DIAL rows, then both sessions (A sends HELLO). Returns A's and B's session. */
    fun connect(): Pair<Session, Session> {
        val (cc, sc) = MemConnection.pair(b.pin, a.pin, log, "A>B", "B<A", measured = measured)
        val report = a.node.dial(b.pin, "192.168.1.20:11436", "qr") {
            log.add(Ev.Connect("A", it))
            ConnectResult("connected", cc)
        }
        val sb = b.node.accept(sc)
        val sa = a.node.openDialed(report, b.pin)
        links += Link("A", sa, cc)
        links += Link("B", sb, sc)
        return sa to sb
    }

    /** One round: every session reads what is ready (a random chunk) and runs its engines one step. Returns how much moved. */
    fun pumpOnce(): Int {
        var progress = 0
        for (l in links) {
            if (l.session.closed) continue
            progress += l.session.pumpAvailable(1 + rnd.nextInt(maxChunk))
            progress += l.session.advance()
            if (!l.eof && l.conn.peerClosed() && l.conn.drained() && !l.session.closed) {
                l.eof = true
                l.session.onTransportClosed()
                progress++
            }
        }
        return progress
    }

    /** Delivers bytes (in random chunk sizes up to [maxChunk]) and runs engines until nothing moves. */
    fun pump(limit: Int = 200_000) {
        var steps = 0
        while (pumpOnce() > 0) check(++steps < limit) { "the world did not quiesce" }
    }
}
