package xyz.mdhv.asom.lab.conformance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.lab.ledger.PeerPath
import xyz.mdhv.asom.lab.policy.BatteryBand
import xyz.mdhv.asom.lab.policy.Freshness
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.StateDoc
import xyz.mdhv.asom.lab.router.AppPolicy
import xyz.mdhv.asom.lab.router.BreakerView
import xyz.mdhv.asom.lab.router.CapCounter
import xyz.mdhv.asom.lab.router.CeilingKey
import xyz.mdhv.asom.lab.router.ClaimKey
import xyz.mdhv.asom.lab.router.DeviceClass
import xyz.mdhv.asom.lab.router.FileKey
import xyz.mdhv.asom.lab.router.FileKind
import xyz.mdhv.asom.lab.router.FrozenCloudView
import xyz.mdhv.asom.lab.router.LinkStats
import xyz.mdhv.asom.lab.router.MeshConfig
import xyz.mdhv.asom.lab.router.MeshQuery
import xyz.mdhv.asom.lab.router.MeshSnapshot
import xyz.mdhv.asom.lab.router.NodeView
import xyz.mdhv.asom.lab.router.ObsRecord
import xyz.mdhv.asom.lab.router.PeerLimits
import xyz.mdhv.asom.lab.router.PeerPenalty
import xyz.mdhv.asom.lab.router.PeerRowView
import xyz.mdhv.asom.lab.router.PerfPrior
import xyz.mdhv.asom.lab.router.RateCeiling
import xyz.mdhv.asom.lab.router.SelfSituation
import xyz.mdhv.asom.lab.router.Tier
import xyz.mdhv.asom.lab.router.TrackerState
import xyz.mdhv.asom.routing.RouteQuery

private fun JsonObject.nlong(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull()

private fun JsonObject.nstr(k: String): String? = strOrNull(k)

internal fun JsonObject.reqBool(k: String): Boolean = (this[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: throw LawViolation("vector field '$k' is missing or not a boolean")

/** Decodes the JSON world of the router vector families (the shapes `lab/mesh-router/tools/worlds.py` writes) into the lab's own types. Every member is required. */
object RouterWorld {
    private fun file(o: JsonObject) = FileKey(
        o.str("modelId"), o.str("fileSha256"), o.nstr("quant"), o.long("fileBytes"), o.nlong("catalogueRank")?.toInt(), FileKind.valueOf(o.str("kind")), o.nlong("contextTokens"),
    )

    private fun prior(o: JsonObject) = PerfPrior(
        o.arr("decodeAt").map { val a = it as JsonArray; (a[0] as JsonPrimitive).content.toInt() to (a[1] as JsonPrimitive).content.toLong() },
        o.long("prefillMilliTokPerSec"), o.long("ttft0Ms"), o.long("steadyMilliTokPerSec"), o.nlong("throttleOnsetMs"), o.nlong("powerMilliW"),
        o.long("kvBytesPerToken"), o.long("peakProcessBytes"), o.strList("flags").toSet(),
    )

    private fun state(o: JsonObject) = StateDoc(
        o.long("seq"), o.long("sampledAgeMs"), Fsm.valueOf(o.str("fsm")), o.str("powerSource"), o.reqBool("charging"),
        o.nstr("batteryBand")?.let { BatteryBand.fromWire(it) ?: throw LawViolation("band $it") }, o.long("thermalBand").toInt(), Governor.valueOf(o.str("governor")),
        o.str("backend"), o.str("commit"), o.str("confVersion"), o.strList("held"), o.long("queueBucket").toInt(), o.nlong("manifestSeq"), o.nstr("manifestDigest"),
    )

    private fun selfSit(o: JsonObject) = SelfSituation(
        o.nlong("batteryPermille")?.toInt(), o.reqBool("charging"), o.reqBool("onBattery"), o.reqBool("saver"), o.nlong("batteryDesignMilliWh"), o.long("thermalCode").toInt(),
        Governor.valueOf(o.str("governor")), o.nlong("availBytes"), o.strList("loaded").toSet(), o.reqBool("userActive"), o.long("busyForMs"), o.reqBool("hasEngine"),
        o.str("backend"), o.long("localQueueMs"),
    )

    private fun link(o: JsonObject) = LinkStats(o.long("rttMs"), o.long("kbps"), PeerPath.valueOf(o.str("path")), o.reqBool("metered"), o.reqBool("sessionWarm"), o.long("samples").toInt())

    private fun peerRow(o: JsonObject): PeerRowView {
        val l = o.obj("limits")
        return PeerRowView(
            o.reqBool("paired"), o.reqBool("routeEnabled"), o.reqBool("inferGrantedToMe"), o.reqBool("requireCharging"),
            PeerLimits(l.long("maxConcurrent").toInt(), l.long("maxBodyBytes"), l.long("maxTokens").toInt(), l.long("rpm").toInt(), l.long("idleUnloadMs")),
        )
    }

    fun node(o: JsonObject): NodeView {
        val id = o.str("nodeId")
        val b = o.obj("breaker")
        return NodeView(
            nodeId = id, nodeTag = o.str("nodeTag"), tier = Tier.valueOf(o.str("tier")), deviceClass = DeviceClass.valueOf(o.str("deviceClass")),
            peer = o.objOrNull("peer")?.let { peerRow(it) }, files = o.arr("files").map { file(it as JsonObject) },
            priors = o.arr("priors").associate { p -> val po = p as JsonObject; ClaimKey(po.str("nodeId"), po.str("fileSha256"), po.str("backend")) to prior(po.obj("prior")) },
            self = o.objOrNull("self")?.let { selfSit(it) }, state = o.objOrNull("state")?.let { state(it) }, stateRxMonoMs = o.nlong("stateRxMonoMs"),
            sessionOpen = o.reqBool("sessionOpen"), goawaySeen = o.reqBool("goawaySeen"), link = o.objOrNull("link")?.let { link(it) },
            breaker = BreakerView(b.nlong("coolingUntilMonoMs"), b.nlong("declineBackoffUntilMonoMs"), b.reqBool("halfOpen")),
            lastSameFileMonoMs = o.obj("lastSameFileMonoMs").entries.associate { (k, v) -> k to (v as JsonPrimitive).content.toLong() }, ownReservationsMs = o.long("ownReservationsMs"),
            maxContextTokens = o.nlong("maxContextTokens"), batteryDesignMilliWh = o.nlong("batteryDesignMilliWh"), stateRegressed = o.reqBool("stateRegressed"),
            powerFreshness = o.nstr("powerFreshness")?.let { Freshness.valueOf(it) },
        )
    }

    fun query(o: JsonObject): MeshQuery {
        val a = o.obj("app")
        return MeshQuery(
            RouteQuery(o.str("model"), o.nstr("policyHeader")?.let { Policy.fromWire(it) ?: throw LawViolation("policy $it") }, o.strList("fallback"), o.reqBool("noTrain")),
            o.str("op"), o.reqBool("stream"), o.long("promptTokens").toInt(), o.long("promptBytes"), o.nlong("maxTokensCap")?.toInt(),
            AppPolicy(a.str("pkg"), a.reqBool("meshAllowed"), a.reqBool("cloudBanned"), a.reqBool("deviceOnly"), a.reqBool("allowMeshOnMetered"), a.reqBool("neverCloudWhenDevicesCanAnswer")),
            o.long("deadlineMs"), o.nstr("embeddingIdentity"),
        )
    }

    private fun ceiling(o: JsonObject) = RateCeiling(o.long("prefillMilliTokPerSec"), o.long("decodeMilliTokPerSec"), o.long("steadyMilliTokPerSec"))

    private fun ceilingKey(s: String): CeilingKey {
        val p = s.split('|')
        return CeilingKey(p[0], p[1], DeviceClass.valueOf(p[2]))
    }

    fun config(o: JsonObject): MeshConfig {
        var c = MeshConfig()
        o.nlong("autoRankFloor")?.let { c = c.copy(autoRankFloor = it.toInt()) }
        o.nlong("maxAttempts")?.let { c = c.copy(maxAttempts = it.toInt()) }
        o.nlong("maxPeerAttempts")?.let { c = c.copy(maxPeerAttempts = it.toInt()) }
        o.objOrNull("classCeilings")?.let { m -> c = c.copy(classCeilings = m.entries.associate { (k, v) -> ceilingKey(k) to ceiling(v as JsonObject) }) }
        o.objOrNull("signedReferenceP90")?.let { m -> c = c.copy(signedReferenceP90 = m.entries.associate { (k, v) -> ceilingKey(k) to ceiling(v as JsonObject) }) }
        o.objOrNull("loadBytesPerMs")?.let { m -> c = c.copy(loadBytesPerMs = c.loadBytesPerMs + m.entries.associate { (k, v) -> DeviceClass.valueOf(k) to (v as JsonPrimitive).content.toLong() }) }
        return c
    }

    fun trackerState(o: JsonObject) = TrackerState(
        claimSeq = o.nlong("claimSeq"), acceptedAtWallMs = o.nlong("acceptedAtWallMs"), ratios = o.arr("ratios").map { (it as JsonPrimitive).content.toLong() },
        recent = o.arr("recent").map { r -> val ro = r as JsonObject; ObsRecord(ro.reqBool("kept"), ro.long("ratio"), ro.long("outBytes"), null) },
        strikes = o.long("strikes").toInt(), inheritedDiscrepant = o.reqBool("inheritedDiscrepant"), memoryDiscrepant = o.reqBool("memoryDiscrepant"),
    )

    fun snapshot(i: JsonObject): MeshSnapshot {
        val cloud = i.obj("cloud")
        return MeshSnapshot(
            nowMonoMs = i.long("nowMonoMs"), wallNowMs = i.long("wallNowMs"), meshGlobalOn = i.reqBool("meshGlobalOn"), self = node(i.obj("self")),
            peers = i.arr("peers").map { node(it as JsonObject) },
            cloud = FrozenCloudView(
                CatalogueParser.parse(Repo.fixtureCatalogue.readUtf8()), cloud.strList("keysPresent").toSet(),
                cloud.obj("coolingUntilWallMs").entries.associate { (k, v) -> k to (v as JsonPrimitive).content.toLong() },
                cloud.obj("ewmaMs").entries.associate { (k, v) -> k to java.lang.Double.parseDouble((v as JsonPrimitive).content) },
                cloud.obj("cloudRates").entries.associate { (k, v) -> k to (v as JsonPrimitive).content.toLong() },
            ),
            tracker = i.arr("tracker").associate { t -> val to = t as JsonObject; ClaimKey(to.str("nodeId"), to.str("fileSha256"), to.str("backend")) to trackerState(to.obj("state")) },
            caps = i.arr("caps").associate { c -> val co = c as JsonObject; ClaimKey(co.str("nodeId"), co.str("fileSha256"), co.str("backend")) to CapCounter(co.long("wouldWin").toInt(), co.long("won").toInt()) },
            appEwmaOut = i.obj("appEwmaOut").entries.associate { (k, v) -> k to (v as JsonPrimitive).content.toInt() }, config = config(i.obj("config")),
            peerPenalty = i.obj("penalties").entries.associate { (k, v) -> val po = v as JsonObject; k to PeerPenalty(po.long("untilWallMs"), po.long("repeats").toInt()) },
        )
    }

    fun world(i: JsonObject): Pair<MeshQuery, MeshSnapshot> = query(i.obj("query")) to snapshot(i)
}
