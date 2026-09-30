package xyz.mdhv.asom.lab.router

import java.io.File
import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.lab.ledger.PeerPath
import xyz.mdhv.asom.lab.policy.BatteryBand
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.StateDoc
import xyz.mdhv.asom.routing.RouteQuery

/** Builders for hand-written worlds. Every default is a plain, healthy value; tests override only what they exercise. */
object W {
    val repoRoot: File by lazy { File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot is not set")).canonicalFile }
    val catalogue: Catalogue by lazy { CatalogueParser.parse(File(repoRoot, "fixtures/catalogue.v1.json").readText()) }

    const val SHA_A = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
    const val SHA_B = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"
    const val SHA_C = "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3"

    fun file(
        sha: String = SHA_A, model: String = "qwen3-8b", quant: String? = "Q4_K_M", bytes: Long = 5_027_784_832, rank: Int? = null,
        kind: FileKind = FileKind.CHAT, ctx: Long? = 32_768,
    ) = FileKey(model, sha, quant, bytes, rank, kind, ctx)

    fun prior(
        prefill: Long = 60_000, decode: Long = 12_000, ttft0: Long = 300, steady: Long = 12_000, onset: Long? = null, power: Long? = null,
        kv: Long = 100_000, peak: Long = 6_000_000_000, flags: Set<String> = emptySet(), curve: List<Pair<Int, Long>>? = null,
    ) = PerfPrior(curve ?: listOf(512 to decode), prefill, ttft0, steady, onset, power, kv, peak, flags)

    fun state(
        seq: Long = 10, age: Long = 0, fsm: Fsm = Fsm.SERVING, source: String = "ac", charging: Boolean = false, band: BatteryBand? = null, thermal: Int = 0,
        gov: Governor = Governor.RUN, backend: String = "metal", held: List<String> = listOf(SHA_A), queue: Int = 0,
    ) = StateDoc(seq, age, fsm, source, charging, band, thermal, gov, backend, "0123abc", "0.2.0", held, queue, null, null)

    val limits = PeerLimits(1, 8_388_608, 4096, 30, 300_000)

    fun link(rtt: Long = 10, kbps: Long = 100_000, path: PeerPath = PeerPath.LAN, metered: Boolean = false, warm: Boolean = true, samples: Int = 5) =
        LinkStats(rtt, kbps, path, metered, warm, samples)

    fun peer(
        id: String, cls: DeviceClass = DeviceClass.DESKTOP, files: List<FileKey> = listOf(file()), backend: String = "metal", prior: PerfPrior? = prior(),
        priors: Map<ClaimKey, PerfPrior>? = null, state: StateDoc? = state(backend = backend), rxMonoMs: Long? = 1_000_000, link: LinkStats? = link(),
        row: PeerRowView = PeerRowView(true, true, true, false, limits), breaker: BreakerView = BreakerView(null, null),
        lastSame: Map<String, Long> = emptyMap(), reservations: Long = 0, design: Long? = null, sessionOpen: Boolean = true, goaway: Boolean = false,
        maxCtx: Long? = null, regressed: Boolean = false,
    ): NodeView {
        val pri = priors ?: files.mapNotNull { f -> prior?.let { ClaimKey(id, f.fileSha256, backend) to it } }.toMap()
        return NodeView(
            nodeId = id, nodeTag = id.take(8), tier = Tier.PEER, deviceClass = cls, peer = row, files = files, priors = pri, self = null, state = state,
            stateRxMonoMs = if (state == null) null else rxMonoMs, sessionOpen = sessionOpen, goawaySeen = goaway, link = link, breaker = breaker,
            lastSameFileMonoMs = lastSame, ownReservationsMs = reservations, maxContextTokens = maxCtx, batteryDesignMilliWh = design, stateRegressed = regressed,
        )
    }

    fun selfSituation(
        permille: Int? = 900, charging: Boolean = false, onBattery: Boolean = true, thermal: Int = 0, gov: Governor = Governor.RUN, avail: Long? = 20_000_000_000,
        loaded: Set<String> = setOf(SHA_A), active: Boolean = false, busy: Long = 0, design: Long? = 19_000, engine: Boolean = true, backend: String = "cpu", queue: Long = 0,
    ) = SelfSituation(permille, charging, onBattery, false, design, thermal, gov, avail, loaded, active, busy, engine, backend, queue)

    fun self(
        id: String = "self-node", cls: DeviceClass = DeviceClass.PHONE, files: List<FileKey> = listOf(file()), prior: PerfPrior? = prior(prefill = 30_000, decode = 5_000, ttft0 = 200, steady = 4_000, onset = 180_000, power = 5_000),
        situation: SelfSituation = selfSituation(),
    ): NodeView {
        val backend = situation.backend
        val pri = files.mapNotNull { f -> prior?.let { ClaimKey(id, f.fileSha256, backend) to it } }.toMap()
        return NodeView(
            nodeId = id, nodeTag = id.take(8), tier = Tier.SELF, deviceClass = cls, peer = null, files = files, priors = pri, self = situation, state = null, stateRxMonoMs = null,
            sessionOpen = false, goawaySeen = false, link = null, breaker = BreakerView(null, null), lastSameFileMonoMs = emptyMap(), ownReservationsMs = 0,
        )
    }

    val app = AppPolicy("app.a", meshAllowed = true, cloudBanned = false, deviceOnly = false, allowMeshOnMetered = false, neverCloudWhenDevicesCanAnswer = false)

    fun query(
        model: String = "auto", policyHeader: Policy? = null, fallback: List<String> = emptyList(), noTrain: Boolean = false, op: String = "chat", stream: Boolean = true,
        prompt: Int = 500, bytes: Long = 2_000, cap: Int? = 300, app: AppPolicy = W.app, deadline: Long = 120_000, embedding: String? = null,
    ) = MeshQuery(RouteQuery(model, policyHeader, fallback, noTrain), op, stream, prompt, bytes, cap, app, deadline, embedding)

    fun cloud(keys: Set<String> = emptySet(), cooling: Map<String, Long> = emptyMap(), ewma: Map<String, Double> = emptyMap()) =
        FrozenCloudView(catalogue, keys, cooling, ewma)

    fun snapshot(
        self: NodeView = self(), peers: List<NodeView> = emptyList(), now: Long = 1_000_500, wall: Long = 1_790_000_000_000, meshOn: Boolean = true, cloud: FrozenCloudView = cloud(),
        tracker: Map<ClaimKey, TrackerState> = emptyMap(), caps: Map<ClaimKey, CapCounter> = emptyMap(), appEwma: Map<String, Int> = emptyMap(), config: MeshConfig = MeshConfig(),
        penalties: Map<String, PeerPenalty> = emptyMap(),
    ) = MeshSnapshot(now, wall, meshOn, self, peers, cloud, tracker, caps, appEwma, config, penalties)
}
