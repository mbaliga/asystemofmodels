package xyz.mdhv.asom.lab.router

import java.util.SplittableRandom
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.lab.ledger.PeerPath
import xyz.mdhv.asom.lab.policy.BatteryBand
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.StateDoc
import xyz.mdhv.asom.routing.RouteQuery

class GenWorld(val q: MeshQuery, val s: MeshSnapshot)

/** Random worlds for the law tests. One `SplittableRandom(seed)` per generator; nothing else is random. */
class WorldGen(seed: Long) {
    private val r = SplittableRandom(seed)

    fun int(lo: Int, hi: Int): Int = lo + r.nextInt(hi - lo + 1)
    fun long(lo: Long, hi: Long): Long = lo + (r.nextLong() ushr 1) % (hi - lo + 1)
    fun chance(percent: Int): Boolean = r.nextInt(100) < percent
    fun <T> pick(xs: List<T>): T = xs[r.nextInt(xs.size)]
    fun shuffled(n: Int): List<Int> = (0 until n).toMutableList().also { a -> for (i in a.size - 1 downTo 1) { val j = r.nextInt(i + 1); val t = a[i]; a[i] = a[j]; a[j] = t } }

    private val models = listOf("llama-3.3-70b" to 3, "deepseek-v3" to 2, "qwen3-8b" to null, "qwen3-1.7b" to null)
    private val quants = listOf("Q4_K_M", "Q8_0", "Q6_K", "Q2_K", "F16", null, "IQ1_S")
    private val cloudIds = listOf("openrouter", "groq", "trainy-ai", "anthropic", "webchat-only")

    private fun sha(i: Int) = (('a' + i).toString() + i.toString(16)).padEnd(64, ('a' + i))

    fun files(): List<FileKey> {
        val idx = shuffled(models.size).take(int(1, models.size))
        return idx.map { i ->
            val (m, rank) = models[i]
            FileKey(m, sha(i), pick(quants), long(400_000_000, 20_000_000_000), rank, FileKind.CHAT, pick(listOf(2048L, 8192L, 32_768L, 131_072L)))
        }
    }

    private fun prior(): PerfPrior {
        val dec = long(2_000, 90_000)
        val curve = when (int(0, 2)) {
            0 -> listOf(512 to dec)
            1 -> listOf(512 to dec, 4096 to maxOf(1_000L, dec - long(0, dec / 2)))
            else -> listOf(128 to dec + 1_000, 1024 to dec, 8192 to maxOf(1_000L, dec / 2))
        }
        return PerfPrior(
            curve, long(5_000, 900_000), long(0, 800), maxOf(1_000L, dec - long(0, dec / 2)), if (chance(50)) null else long(20_000, 600_000),
            if (chance(50)) null else long(2_000, 200_000), long(20_000, 400_000), long(1_000_000_000, 12_000_000_000),
            if (chance(3)) setOf("numerics-fail") else emptySet(),
        )
    }

    private fun tracker(id: String, files: List<FileKey>, backend: String): Map<ClaimKey, TrackerState> {
        val out = LinkedHashMap<ClaimKey, TrackerState>()
        for (f in files) {
            if (chance(50)) continue
            val n = int(0, 8)
            val ratios = List(n) { long(100, 1500) }
            out[ClaimKey(id, f.fileSha256, backend)] = TrackerState(claimSeq = 1, ratios = ratios, strikes = 0, memoryDiscrepant = chance(4), inheritedDiscrepant = chance(3))
        }
        return out
    }

    fun peer(i: Int, now: Long, files: List<FileKey>, backend: String, forceEligible: Boolean = false): Pair<NodeView, Map<ClaimKey, TrackerState>> {
        val id = "peer-$i-" + int(0, 9)
        val cls = pick(DeviceClass.entries)
        val prior = prior()
        val priors = files.filter { !chance(4) }.associate { ClaimKey(id, it.fileSha256, backend) to prior() }.let { m -> if (chance(70)) m.mapValues { prior } else m }
        val fresh = pick(listOf(0L, 2_000L, 10_000L, 100_000L, 500_000L))
        val rx = now - fresh
        val held = files.filter { !chance(6) }.map { it.fileSha256 }
        val onBattery = chance(20)
        val state = if (chance(4)) null else StateDoc(
            seq = long(1, 100), sampledAgeMs = pick(listOf(0L, 500L, 5_000L, 90_000L)), fsm = if (chance(85)) Fsm.SERVING else pick(Fsm.entries),
            powerSource = if (onBattery) "battery" else pick(listOf("ac", "ac", "unknown")), charging = chance(30),
            batteryBand = if (onBattery) pick(BatteryBand.entries + listOf(null)) else null, thermalBand = pick(listOf(0, 0, 0, 1, 2)),
            governor = pick(listOf(Governor.RUN, Governor.RUN, Governor.RUN, Governor.QUEUE, Governor.HOLD)), backend = backend, commit = "0123abc",
            confVersion = "0.2.0", held = held, queueBucket = pick(listOf(0, 0, 1, 2)), manifestSeq = null, manifestDigest = null,
        )
        val row = if (forceEligible) PeerRowView(true, true, true, chance(10), PeerLimits(1, 8_388_608, 4096, 30, 300_000))
        else PeerRowView(chance(95), chance(92), chance(95), chance(10), PeerLimits(int(1, 2), pick(listOf(1_000L, 8_388_608L)), pick(listOf(64, 512, 4096)), 30, pick(listOf(1_000L, 300_000L))))
        val bk = when (int(0, 9)) {
            0 -> BreakerView(now + long(1, 60_000), null)
            1 -> BreakerView(null, now + long(1, 60_000))
            2 -> BreakerView(now - 1, null, halfOpen = true)
            else -> BreakerView(null, null)
        }
        val lastSame = files.filter { chance(50) }.associate { it.fileSha256 to now - long(0, 600_000) }
        val node = NodeView(
            nodeId = id, nodeTag = id.take(8), tier = Tier.PEER, deviceClass = cls, peer = row, files = files, priors = priors, self = null, state = state,
            stateRxMonoMs = if (state == null) null else rx, sessionOpen = chance(85), goawaySeen = chance(4),
            link = if (chance(3)) null else LinkStats(long(1, 80), if (chance(20)) 0 else long(20_000, 600_000), pick(PeerPath.entries), chance(8), chance(50), int(0, 6)),
            breaker = bk, lastSameFileMonoMs = lastSame, ownReservationsMs = if (chance(30)) long(0, 20_000) else 0,
            maxContextTokens = if (chance(20)) pick(listOf(1_000L, 4_096L)) else null, batteryDesignMilliWh = if (chance(75)) long(10_000, 60_000) else null,
            stateRegressed = chance(3),
        )
        return node to tracker(id, files, backend)
    }

    fun selfNode(files: List<FileKey>): NodeView {
        val engine = chance(85)
        val sit = SelfSituation(
            batteryPermille = if (chance(5)) null else int(50, 1000), charging = chance(20), onBattery = chance(70), saver = false,
            batteryDesignMilliWh = if (chance(90)) long(10_000, 25_000) else null, thermalCode = pick(listOf(0, 0, 1, 2, 3, 4)), governor = pick(listOf(Governor.RUN, Governor.RUN, Governor.RUN, Governor.QUEUE, Governor.HOLD)),
            availBytes = if (chance(5)) null else pick(listOf(300_000_000L, 4_000_000_000L, 30_000_000_000L)), loaded = files.filter { chance(50) }.map { it.fileSha256 }.toSet(),
            userActive = chance(50), busyForMs = pick(listOf(0L, 5_000L, 200_000L)), hasEngine = engine, backend = "cpu", localQueueMs = pick(listOf(0L, 0L, 3_000L)),
        )
        val id = "self-node"
        val priors = files.filter { !chance(4) }.associate { ClaimKey(id, it.fileSha256, "cpu") to prior() }
        return NodeView(
            nodeId = id, nodeTag = "self", tier = Tier.SELF, deviceClass = pick(listOf(DeviceClass.PHONE, DeviceClass.TABLET, DeviceClass.LAPTOP)), peer = null, files = files,
            priors = priors, self = sit, state = null, stateRxMonoMs = null, sessionOpen = false, goawaySeen = false, link = null, breaker = BreakerView(null, null),
            lastSameFileMonoMs = emptyMap(), ownReservationsMs = 0,
        )
    }

    /** A whole world: query, snapshot. [calm] worlds keep every peer CORROBORATED so that the cap and the tracker do not interfere with metamorphic laws. */
    fun world(calm: Boolean = false): GenWorld {
        val now = 1_000_000_000L
        val backend = pick(listOf("metal", "cpu", "vulkan"))
        val selfFiles = files()
        val self = selfNode(selfFiles)
        val nPeers = int(0, 4)
        val peers = ArrayList<NodeView>()
        var tracker = LinkedHashMap<ClaimKey, TrackerState>()
        for (i in 0 until nPeers) {
            val (p, t) = peer(i, now, files(), backend)
            if (peers.none { it.nodeId == p.nodeId }) {
                peers += p
                if (!calm) tracker.putAll(t)
            }
        }
        if (calm) {
            for (p in peers) for (f in p.files) tracker[ClaimKey(p.nodeId, f.fileSha256, backend)] = TrackerState(claimSeq = 1, ratios = List(6) { 1000L })
        }
        val policy = pick(listOf("auto", "auto", "cheapest", "fastest", "best-reasoning", "local-only", "llama-3.3-70b", "deepseek-v3", "qwen3-8b"))
        val header = if (chance(10)) pick(listOf(Policy.CHEAPEST, Policy.FASTEST, Policy.AUTO)) else null
        val fallback = if (chance(6)) listOf(pick(cloudIds)) else emptyList()
        val prompt = pick(listOf(10, 100, 500, 2_000, 30_000))
        val embeddings = chance(6)
        val app = AppPolicy("app.${int(0, 3)}", chance(85), chance(10), chance(5), chance(20), chance(10))
        val query = MeshQuery(
            RouteQuery(policy, header, fallback, chance(10)), if (embeddings) "embeddings" else pick(listOf("chat", "completions")), chance(70), prompt, prompt * 4L,
            if (chance(50)) pick(listOf(16, 64, 300, 1024)) else null, app, pick(listOf(20_000L, 120_000L)), if (embeddings && chance(50)) selfFiles.firstOrNull()?.fileSha256 else null,
        )
        val keys = cloudIds.filter { chance(70) }.toSet()
        val cooling = cloudIds.filter { chance(15) }.associateWith { 1_790_000_000_000 + long(-5_000, 60_000) }
        val ewma = cloudIds.filter { chance(50) }.associateWith { long(100, 5_000).toDouble() + (r.nextInt(100) / 100.0) }
        val cfg = if (chance(15)) MeshConfig(autoRankFloor = pick(listOf(1, 2, 3))) else MeshConfig()
        val caps = tracker.keys.filter { chance(40) }.associateWith { CapCounter(int(0, 12), int(0, 4)) }
        val snap = MeshSnapshot(
            nowMonoMs = now, wallNowMs = 1_790_000_000_000, meshGlobalOn = chance(85), self = self, peers = peers,
            cloud = FrozenCloudView(W.catalogue, keys, cooling, ewma, if (chance(20)) cloudIds.filter { chance(50) }.associateWith { long(10_000, 90_000) } else emptyMap()),
            tracker = tracker, caps = caps, appEwmaOut = if (chance(40)) mapOf(app.pkg to int(20, 1500)) else emptyMap(), config = cfg,
        )
        return GenWorld(query, snap)
    }
}
