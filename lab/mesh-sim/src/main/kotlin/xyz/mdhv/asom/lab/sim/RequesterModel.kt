package xyz.mdhv.asom.lab.sim

import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.lab.ledger.PeerPath
import xyz.mdhv.asom.lab.policy.BatteryBand
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.StateDoc
import xyz.mdhv.asom.lab.router.AppOutputEwma
import xyz.mdhv.asom.lab.router.AppPolicy
import xyz.mdhv.asom.lab.router.BreakerCurve
import xyz.mdhv.asom.lab.router.BreakerState
import xyz.mdhv.asom.lab.router.BreakerView
import xyz.mdhv.asom.lab.router.CapCounter
import xyz.mdhv.asom.lab.router.CapDelta
import xyz.mdhv.asom.lab.router.CapReducer
import xyz.mdhv.asom.lab.router.ClaimKey
import xyz.mdhv.asom.lab.router.ClaimTracker
import xyz.mdhv.asom.lab.router.DeclineBackoff
import xyz.mdhv.asom.lab.router.DeviceClass
import xyz.mdhv.asom.lab.router.FileKey
import xyz.mdhv.asom.lab.router.FileKind
import xyz.mdhv.asom.lab.router.FrozenCloudView
import xyz.mdhv.asom.lab.router.LinkReducer
import xyz.mdhv.asom.lab.router.LinkStats
import xyz.mdhv.asom.lab.router.LiveStateCache
import xyz.mdhv.asom.lab.router.MeshConfig
import xyz.mdhv.asom.lab.router.MeshQuery
import xyz.mdhv.asom.lab.router.MeshSnapshot
import xyz.mdhv.asom.lab.router.NodeView
import xyz.mdhv.asom.lab.router.Observation
import xyz.mdhv.asom.lab.router.PeerLimits
import xyz.mdhv.asom.lab.router.PeerRowView
import xyz.mdhv.asom.lab.router.PeerStateCache
import xyz.mdhv.asom.lab.router.PerfPrior
import xyz.mdhv.asom.lab.router.PureBreaker
import xyz.mdhv.asom.lab.router.SelfSituation
import xyz.mdhv.asom.lab.router.StDigestDoc
import xyz.mdhv.asom.lab.router.Tier
import xyz.mdhv.asom.lab.router.TrackerBook
import xyz.mdhv.asom.routing.LatencyTracker
import xyz.mdhv.asom.routing.RouteQuery

const val WALL_BASE_MS: Long = 1_790_000_000_000L

/**
 * Everything the requester knows about the mesh. It changes ONLY through [apply], fed by [SimEvent]s, so the log of those events is a complete record: replaying it
 * through a fresh model reproduces every decision (law RL21). The model holds no truth: node truth, links and cloud behaviour live in the simulated world.
 * The structural facts it reads from the scenario (which files a peer holds, its class, the app table) are what pairing and the manifest gave the requester.
 */
class RequesterModel(private val sc: Scenario, private val catalogue: Catalogue, val cfg: MeshConfig = MeshConfig()) {
    class Peer(val spec: SimNodeSpec) {
        var cache = PeerStateCache()
        var link: LinkStats? = null
        var transport = BreakerState()
        var declineUntilMono: Long? = null
        var lastSame: Map<String, Long> = emptyMap()
        var reservations: Long = 0
        var paired = true
        var route = true
        var grant = true
        var requireCharging = false
        var priors: Map<ClaimKey, PerfPrior> = emptyMap()
        var claimCommit: Map<String, String> = emptyMap()
    }

    val peers: Map<String, Peer> = sc.peers.associate { it.id to Peer(it) }
    var meshOn = true
        private set
    var book = TrackerBook()
        private set
    var caps: Map<ClaimKey, CapCounter> = emptyMap()
        private set
    var appEwma: Map<String, Int> = emptyMap()
        private set
    private val latency = LatencyTracker()
    private var cloudBreakers: Map<String, BreakerState> = emptyMap()
    private var selfPriors: Map<ClaimKey, PerfPrior> = emptyMap()
    private var selfSit: SelfSituation = SelfSituation(null, false, false, false, null, 0, Governor.RUN, null, emptySet(), false, 0, true, sc.self.backend, 0)

    private fun wall(t: Long) = WALL_BASE_MS + t

    private fun prior(e: SimEvent) = PerfPrior(
        listOf(512 to e.long("decode")), e.long("prefill"), e.long("ttft0"), e.long("steady"), e.nlong("onset")?.takeIf { it >= 0 }, e.nlong("power")?.takeIf { it >= 0 }, e.long("kv"),
        e.long("peak"), emptySet(),
    )

    fun apply(e: SimEvent) {
        when (e.kind) {
            "mesh" -> meshOn = e.bool("on")
            "registry" -> peers.getValue(e.str("peer")).apply {
                paired = e.bool("paired"); route = e.bool("route"); grant = e.bool("grant"); requireCharging = e.bool("requireCharging")
            }
            "claim" -> {
                val p = peers.getValue(e.str("peer"))
                val key = ClaimKey(p.spec.id, e.str("sha"), e.str("backend"))
                val (b, accepted) = ClaimTracker.onClaimBody(book, key, e.long("cseq"), wall(e.t))
                book = b
                if (accepted) {
                    p.priors = p.priors + (key to prior(e))
                    p.claimCommit = p.claimCommit + (key.fileSha256 to e.str("commit"))
                }
            }
            "calib" -> selfPriors = selfPriors + (ClaimKey(sc.self.id, e.str("sha"), e.str("backend")) to prior(e))
            "session-open" -> peers.getValue(e.str("peer")).apply {
                cache = LiveStateCache.onSessionOpen(cache)
                link = link?.copy(sessionWarm = true)
            }
            "session-close" -> peers.getValue(e.str("peer")).apply {
                cache = LiveStateCache.onSessionClose(cache)
                link = link?.copy(sessionWarm = false)
            }
            "goaway" -> peers.getValue(e.str("peer")).apply { cache = LiveStateCache.onGoaway(cache) }
            "state" -> peers.getValue(e.str("peer")).apply {
                val doc = StateDoc(
                    e.long("sseq"), e.long("age"), Fsm.valueOf(e.str("fsm")), e.str("src"), e.bool("chg"), e.nstr("band")?.let { BatteryBand.fromWire(it) }, e.int("tb"),
                    Governor.valueOf(e.str("gov")), e.str("backend"), e.str("commit"), "0.2.0", e.strs("held"), e.int("qb"), null, null,
                )
                cache = LiveStateCache.onState(cache, doc, e.t)
            }
            "piggyback" -> peers.getValue(e.str("peer")).apply {
                cache = LiveStateCache.onPiggyback(cache, StDigestDoc(e.long("sseq"), Fsm.valueOf(e.str("fsm")), e.int("tb"), Governor.valueOf(e.str("gov")), e.int("qb")), e.t)
            }
            "link" -> peers.getValue(e.str("peer")).apply {
                link = LinkStats(e.long("rtt"), e.long("kbps"), PeerPath.valueOf(e.str("path")), e.bool("metered"), e.bool("warm"), e.int("samples"))
            }
            "link-rtt" -> peers.getValue(e.str("peer")).apply { link = link?.let { LinkReducer.onRtt(it, e.long("ms")) } }
            "link-transfer" -> peers.getValue(e.str("peer")).apply { link = link?.let { LinkReducer.onTransfer(it, e.long("bytes"), e.long("ms")) } }
            "link-meta" -> peers.getValue(e.str("peer")).apply { link = link?.copy(metered = e.bool("metered"), path = PeerPath.valueOf(e.str("path"))) }
            "breaker-fail" -> peers.getValue(e.str("peer")).apply { transport = PureBreaker.recordFailure(transport, e.t, BreakerCurve.PEER_TRANSPORT) }
            "breaker-ok" -> peers.getValue(e.str("peer")).apply { transport = PureBreaker.recordSuccess() }
            "backoff" -> peers.getValue(e.str("peer")).apply { declineUntilMono = DeclineBackoff.until(e.t, e.long("retryAfterMs")) }
            "obs" -> {
                val p = peers.getValue(e.str("peer"))
                val key = ClaimKey(p.spec.id, e.str("sha"), e.str("backend"))
                val claim = p.priors[key] ?: return
                val o = Observation(
                    key, e.bool("done"), e.long("tBody"), e.long("tEnd"), e.long("outBytes"), e.long("P"), e.long("maxTokens"), e.long("netMs"), e.bool("concurrent"),
                    e.nstr("acceptedSha"), e.nstr("heldBackend"), e.nstr("heldCommit"), e.nstr("claimCommit"),
                )
                book = ClaimTracker.onObservation(book, o, claim, cfg.bpt(key.fileSha256), wall(e.t))
            }
            "obs-failed" -> book = ClaimTracker.onFailedAttempt(book, ClaimKey(e.str("peer"), e.str("sha"), e.str("backend")), wall(e.t))
            "obs-oom" -> book = ClaimTracker.onMemoryOom(book, ClaimKey(e.str("peer"), e.str("sha"), e.str("backend")))
            "reserve" -> peers.getValue(e.str("peer")).apply { reservations = maxOf(0L, reservations + e.long("delta")) }
            "same-file" -> peers.getValue(e.str("peer")).apply { lastSame = lastSame + (e.str("sha") to e.t) }
            "cap" -> caps = CapReducer.commit(caps, CapDelta(listOf(CapDelta.Entry(ClaimKey(e.str("node"), e.str("sha"), e.str("backend")), e.int("wouldWinInc"), e.int("wonInc"), e.bool("swapped")))))
            "app-ewma" -> appEwma = AppOutputEwma.onCompletion(appEwma, e.str("app"), e.int("tokens"))
            "cloud-fail" -> cloudBreakers = cloudBreakers + (e.str("provider") to PureBreaker.recordFailure(cloudBreakers[e.str("provider")] ?: BreakerState(), wall(e.t), BreakerCurve.PROVIDER))
            "cloud-ok" -> {
                latency.record(e.str("provider"), e.long("ms"))
                cloudBreakers = cloudBreakers + (e.str("provider") to PureBreaker.recordSuccess())
            }
            "self" -> selfSit = SelfSituation(
                e.nlong("battery")?.takeIf { it >= 0 }?.toInt(), e.bool("charging"), e.bool("onBattery"), false, e.nlong("design")?.takeIf { it >= 0 }, e.int("thermal"),
                Governor.valueOf(e.str("gov")), e.nlong("avail")?.takeIf { it >= 0 }, e.strs("loaded").toSet(), e.bool("active"), e.long("busy"), e.bool("engine"), sc.self.backend, e.long("queue"),
            )
            "request" -> Unit
            else -> throw IllegalArgumentException("unknown event kind ${e.kind}")
        }
    }

    private fun fileKeys(spec: SimNodeSpec) = spec.files.map { FileKey(it.modelId, it.fileSha256, it.quant, it.fileBytes, it.rank, FileKind.CHAT, it.ctx) }

    fun app(pkg: String): AppPolicy = sc.apps.first { it.pkg == pkg }.let {
        AppPolicy(it.pkg, it.meshAllowed, it.cloudBanned, it.deviceOnly, it.allowMeshOnMetered, it.neverCloudWhenDevicesCanAnswer)
    }

    fun query(e: SimEvent): MeshQuery = MeshQuery(
        RouteQuery(e.str("model"), e.nstr("header")?.let { Policy.fromWire(it) }, e.strs("fallback"), e.bool("noTrain")), e.str("op"), e.bool("stream"), e.int("P"), e.long("B"),
        e.nlong("cap")?.takeIf { it >= 0 }?.toInt(), app(e.str("app")), e.long("deadline"),
    )

    fun snapshot(t: Long): MeshSnapshot {
        val self = NodeView(
            nodeId = sc.self.id, nodeTag = sc.self.id.take(8), tier = Tier.SELF, deviceClass = sc.self.deviceClass, peer = null, files = fileKeys(sc.self), priors = selfPriors, self = selfSit,
            state = null, stateRxMonoMs = null, sessionOpen = false, goawaySeen = false, link = null, breaker = BreakerView(null, null), lastSameFileMonoMs = emptyMap(), ownReservationsMs = 0,
        )
        val nodes = peers.values.sortedBy { it.spec.id }.map { p ->
            NodeView(
                nodeId = p.spec.id, nodeTag = p.spec.id.take(8), tier = Tier.PEER, deviceClass = p.spec.deviceClass, peer = PeerRowView(p.paired, p.route, p.grant, p.requireCharging, LIMITS),
                files = fileKeys(p.spec), priors = p.priors, self = null, state = p.cache.doc, stateRxMonoMs = p.cache.rxMonoMs, sessionOpen = p.cache.sessionOpen,
                goawaySeen = p.cache.goawaySinceState, link = p.link,
                breaker = BreakerView(PureBreaker.coolingUntil(p.transport, t), p.declineUntilMono, PureBreaker.halfOpen(p.transport, t)), lastSameFileMonoMs = p.lastSame,
                ownReservationsMs = p.reservations, maxContextTokens = null, batteryDesignMilliWh = p.spec.power.designMilliWh, stateRegressed = p.cache.regressed,
                powerFreshness = if (p.cache.doc == null) null else LiveStateCache.powerFreshness(p.cache, t),
            )
        }
        val cooling = cloudBreakers.filter { it.value.coolingUntilMs > wall(t) }.mapValues { it.value.coolingUntilMs }
        return MeshSnapshot(
            nowMonoMs = t, wallNowMs = wall(t), meshGlobalOn = meshOn, self = self, peers = nodes,
            cloud = FrozenCloudView(catalogue, sc.cloud.map { it.provider }.toSet(), cooling, latency.snapshot()), tracker = book.states, caps = caps, appEwmaOut = appEwma, config = cfg,
            peerPenalty = book.penalties,
        )
    }

    companion object {
        /** What `HELLO_ACK.limits` gives the requester in this lab (the defaults of `LenderLimits`). */
        val LIMITS = PeerLimits(1, 8_388_608, 4_096, 30, 300_000)
    }
}
