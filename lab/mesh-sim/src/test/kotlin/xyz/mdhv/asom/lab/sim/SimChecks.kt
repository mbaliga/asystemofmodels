package xyz.mdhv.asom.lab.sim

import xyz.mdhv.asom.lab.ledger.FrameKind
import xyz.mdhv.asom.lab.ledger.LabEgress
import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.lab.ledger.laws.ContentSent
import xyz.mdhv.asom.lab.ledger.laws.LawResult
import xyz.mdhv.asom.lab.ledger.laws.LedgerLaws
import xyz.mdhv.asom.lab.ledger.laws.Responded
import xyz.mdhv.asom.lab.ledger.laws.Sent
import xyz.mdhv.asom.lab.router.MeshConfig
import xyz.mdhv.asom.lab.router.Tier

/**
 * The simulator-level laws of LAB_SPEC 6.8 (RL4, RL14..RL19, RL21, RL22) as oracles over ONE finished run. Each oracle reads the run's records (attempt logs, the event log, the
 * wire trace) and never the simulator's own decision code; the constants they use (30 s transport cooldown, 5 s to 10 min decline back-off, `maxAttempts` 6) are restated from the spec.
 */
object SimChecks {
    private val CFG = MeshConfig()

    private fun ReqRecord.peerAttempts() = attempts.filter { it.tier == Tier.PEER }

    /** RL4: every PEER attempt that sent a body had `O in P`, PAIRED, `routeEnabled` and `infer` granted at that instant, judged from the requester's event log and the scenario. */
    fun rl4(run: SimRun, t: Tally, ctx: String) {
        val res = run.res
        val registry = res.events.filter { it.kind == "registry" }.groupBy { it.str("peer") }
        val meshEvents = res.events.filter { it.kind == "mesh" }
        var cases = 0
        val v = ArrayList<String>()
        for (r in res.requests) for (a in r.peerAttempts()) {
            if (!a.bodySent) continue
            cases++
            val bodyAt = a.bodyAt!!
            val reg = registry[a.target].orEmpty().lastOrNull { it.t <= bodyAt }
            val meshOn = meshEvents.lastOrNull { it.t <= bodyAt }?.bool("on") ?: true
            val app = run.sc.apps.first { it.pkg == r.app.pkg }
            val okReg = reg != null && reg.bool("paired") && reg.bool("route") && reg.bool("grant")
            val okDest = meshOn && app.meshAllowed && !app.deviceOnly && r.model != "local-only"
            if (!okReg || !okDest) v += "${r.id}: body to ${a.target} at t=$bodyAt with registry ok=$okReg, O in P=$okDest"
            if (a.registryOkAtBody != true) v += "${r.id}: the recorded registry check at the body instant is ${a.registryOkAtBody}"
        }
        t.add("RL4", cases, v, ctx)
    }

    /** RL14 (pipeline): L-L1, L-L2, L-L3 over the trace; `frames` adds L-L16 (the frame-byte sum against the outcome rows), which is quadratic and therefore run on small traces only. */
    fun rl14(run: SimRun, t: Tally, ctx: String, frames: Boolean) {
        val ev = run.res.trace.events
        t.add(renamed("RL14/L-L1", LedgerLaws.l1(ev)), ctx)
        t.add(renamed("RL14/L-L2", LedgerLaws.l2(ev)), ctx)
        t.add(renamed("RL14/L-L3", LedgerLaws.l3(ev)), ctx)
        if (frames) t.add(renamed("RL14/L-L16", LedgerLaws.l16(ev).result), ctx)
        val rowsByAttempt = run.res.ledgerRows.entries.flatMap { (node, rs) -> rs.filter { it.attemptId != null }.map { node to it } }
        val requesterRows = rowsByAttempt.filter { it.first == run.sc.self.id }.map { it.second.attemptId!! }.toSet()
        var cases = 0
        val v = ArrayList<String>()
        for ((node, row) in rowsByAttempt) {
            if (node == run.sc.self.id) continue
            cases++
            if (row.attemptId !in requesterRows) v += "lender $node holds a row for attempt ${row.attemptId} that the requester never wrote"
        }
        t.add("RL14/join-on-attemptId", cases, v, ctx)
    }

    /** RL15 (L-L4, L-L5) and RL15b (L-L5b: one terminal row whose egress equals the header, on the error path too). */
    fun rl15(run: SimRun, t: Tally, ctx: String) {
        val ev = run.res.trace.events
        t.add(renamed("RL15/L-L4", LedgerLaws.l4(ev)), ctx)
        t.add(renamed("RL15/L-L5", LedgerLaws.l5(ev)), ctx)
        t.add(renamed("RL15b/L-L5b", LedgerLaws.l5b(ev)), ctx)
        val terminal = run.res.ledgerRows.getValue(run.sc.self.id).filter { it.terminal == true }.groupBy { it.requestId }
        val responded = ev.filterIsInstance<Responded>().associateBy { it.requestId }
        var cases = 0
        val v = ArrayList<String>()
        for (r in run.res.requests) {
            if (r.status == 200 || r.ledgerUnavailable) continue
            cases++
            val rows = terminal[r.id].orEmpty()
            val resp = responded[r.id]
            if (rows.size != 1 || resp == null) {
                v += "${r.id}: an error-path request has ${rows.size} terminal rows and ${if (resp == null) "no" else "a"} response record"
                continue
            }
            if (rows.single().egress.wire != resp.headers[AsomHeaders.EGRESS]) v += "${r.id}: terminal egress ${rows.single().egress.wire} != header ${resp.headers[AsomHeaders.EGRESS]}"
            if (rows.single().status != r.status) v += "${r.id}: terminal status ${rows.single().status} != ${r.status}"
            if (rows.single().reach != rows.single().egress) v += "${r.id}: reach != egress on the error path"
        }
        t.add("RL15b/error-path", cases, v, ctx)
    }

    private fun renamed(name: String, r: LawResult) = LawResult(name, r.cases, r.violations, r.detail)

    /** RL16: at most `maxAttempts`; none starts after the deadline; nothing follows an attempt whose bytes reached the client. */
    fun rl16(run: SimRun, t: Tally, ctx: String) {
        var cases = 0
        val v = ArrayList<String>()
        for (r in run.res.requests) {
            cases++
            if (r.attempts.size > CFG.maxAttempts) v += "${r.id}: ${r.attempts.size} attempts"
            for (a in r.attempts) if (a.startT > r.startT + r.deadlineMs) v += "${r.id}: attempt ${a.index} started at ${a.startT}, after the deadline ${r.startT + r.deadlineMs}"
            val delivered = r.attempts.indexOfFirst { it.deliveredToClient }
            if (delivered >= 0 && delivered != r.attempts.lastIndex) v += "${r.id}: ${r.attempts.size - 1 - delivered} attempt(s) after attempt $delivered had delivered bytes to the client"
        }
        t.add("RL16", cases, v, ctx)
    }

    /** RL17: at most one attempt of a request between its body being sent and its end. */
    fun rl17(run: SimRun, t: Tally, ctx: String) {
        var cases = 0
        val v = ArrayList<String>()
        for (r in run.res.requests) {
            val active = r.attempts.filter { it.bodySent }
            for (a in active) {
                cases++
                if (a.endedAt == null && a !== r.attempts.last()) v += "${r.id}: attempt ${a.index} sent a body and never ended, and another attempt followed"
            }
            for (i in r.attempts.indices) for (j in i + 1 until r.attempts.size) {
                val a = r.attempts[i]
                val b = r.attempts[j]
                if (a.bodySent && a.endedAt != null && a.endedAt!! > b.startT) v += "${r.id}: attempt ${a.index} ended at ${a.endedAt} but attempt ${b.index} started at ${b.startT}"
            }
        }
        t.add("RL17", cases, v, ctx)
    }

    private class Ev(val t: Long, val seq: Long, val peer: String, val kind: String, val retry: Long)

    /** RL18: a peer whose transport failed is absent from every plan until 30 s after the last failure; one that declined is absent until `clamp(retryAfter, 5 s, 10 min)`; both come back. */
    fun rl18(run: SimRun, t: Tally, ctx: String) {
        val events = run.res.events.filter { it.kind == "breaker-fail" || it.kind == "breaker-ok" || it.kind == "backoff" }
            .map { Ev(it.t, it.seq, it.str("peer"), it.kind, if (it.kind == "backoff") it.long("retryAfterMs") else 0L) }
        var cases = 0
        var returned = 0
        val v = ArrayList<String>()
        for (d in run.res.decisions) {
            for (peer in run.sc.peers.map { it.id }) {
                val mine = events.filter { it.peer == peer && (it.t < d.t || (it.t == d.t && it.seq < d.seq)) }
                val lastFail = mine.lastOrNull { it.kind == "breaker-fail" || it.kind == "breaker-ok" }?.takeIf { it.kind == "breaker-fail" }
                val lastBackoff = mine.lastOrNull { it.kind == "backoff" }
                val inPlan = d.attempts.any { it.startsWith("PEER:$peer/") }
                if (lastFail != null && d.t < lastFail.t + 30_000) {
                    cases++
                    if (inPlan) v += "${d.requestId}: $peer is in the plan at ${d.t}, its transport failed at ${lastFail.t}"
                    if ("$peer:F14_BREAKER" !in d.excluded && "$peer:F1_ELIGIBILITY" !in d.excluded) v += "${d.requestId}: $peer is not recorded as excluded by F14 while cooling"
                }
                if (lastBackoff != null && d.t < lastBackoff.t + lastBackoff.retry.coerceIn(5_000, 600_000)) {
                    cases++
                    if (inPlan) v += "${d.requestId}: $peer is in the plan at ${d.t}, it declined at ${lastBackoff.t} with retryAfter ${lastBackoff.retry}"
                }
                if (lastFail != null && d.t >= lastFail.t + 30_000 && lastBackoff.let { it == null || d.t >= it.t + it.retry.coerceIn(5_000, 600_000) } && inPlan) returned++
            }
        }
        t.add("RL18", cases, v, ctx)
        t.add("RL18/returned-after-cooldown", returned)
    }

    /**
     * RL19: Jain's index over the apps' on-time completion, in permille and integer arithmetic, one case per time window of the run (the requests that ARRIVED in it). A window
     * counts only when every app has at least ten requests in it. Returns (jain permille, on-time permille) per counted window.
     */
    fun jainWindows(run: SimRun, windows: Int): List<Pair<Long, Long>> {
        val span = run.sc.durationMs / windows
        val out = ArrayList<Pair<Long, Long>>()
        for (w in 0 until windows) {
            val inWin = run.res.requests.filter { it.startT >= w * span && it.startT < (w + 1) * span }
            val perApp = run.sc.apps.map { app -> inWin.filter { it.app.pkg == app.pkg } }
            if (perApp.any { it.size < 10 }) continue
            val xs = perApp.map { rs -> rs.count { it.status == 200 && it.endT - it.startT <= it.deadlineMs } * 1000L / rs.size }
            val sum = xs.sum()
            val sq = xs.sumOf { it * it }
            val jain = if (sq == 0L) 1000L else sum * sum * 1000 / (xs.size * sq)
            out += jain to inWin.count { it.status == 200 && it.endT - it.startT <= it.deadlineMs } * 1000L / inWin.size
        }
        return out
    }

    /** RL22: no content to a candidate whose offer was declined, timed out or cancelled before the body; the wire trace holds no INFER_BODY for that attempt either. */
    fun rl22(run: SimRun, t: Tally, ctx: String) {
        val contentAttempts = run.res.trace.events.filterIsInstance<ContentSent>().filter { it.cls == LabEgress.peerClass }.map { it.attemptId }.toSet()
        val bodyAttempts = run.res.trace.events.filterIsInstance<Sent>().filter { it.frame.kind == FrameKind.INFER_BODY }.mapNotNull { it.frame.attemptId }.toSet()
        var cases = 0
        val v = ArrayList<String>()
        for (r in run.res.requests) for (a in r.peerAttempts()) {
            val noBody = a.declined || a.status == "OFFER_TIMEOUT" || a.status == "CANCELLED_BEFORE_BODY"
            if (!noBody) continue
            cases++
            val id = a.attemptId
            if (a.bodySent) v += "${r.id}: attempt ${a.index} (${a.status}) recorded a body"
            if (id in contentAttempts) v += "${r.id}: content was sent for the ${a.status} attempt $id"
            if (id in bodyAttempts) v += "${r.id}: an INFER_BODY frame went out for the ${a.status} attempt $id"
        }
        t.add("RL22", cases, v, ctx)
    }

    /** RL21: replaying the recorded `events.jsonl` reproduces every recorded decision. */
    fun rl21(run: SimRun, t: Tally, ctx: String) {
        val r = Replay.run(run.sc, run.sim.catalogue, run.res)
        t.add("RL21", r.decisions, r.diff, ctx)
    }

    fun ledgerLaws(run: SimRun, t: Tally, ctx: String, shared: Boolean = false) {
        for (l in SimLaws.run(run.res, shared)) t.add(l, ctx)
    }

    /** [shared]: the run reuses attempt ids on purpose (`duplicate-attempt`), which the oracles that join by `attemptId` (L-L12, L-L16, RL22) cannot tell apart; they are skipped for it. */
    fun all(run: SimRun, t: Tally, ctx: String, frames: Boolean = false, shared: Boolean = false) {
        rl4(run, t, ctx)
        rl14(run, t, ctx, frames && !shared)
        rl15(run, t, ctx)
        rl16(run, t, ctx)
        rl17(run, t, ctx)
        rl18(run, t, ctx)
        if (!shared) rl22(run, t, ctx)
        rl21(run, t, ctx)
        ledgerLaws(run, t, ctx, shared)
        for (r in run.res.requests) if (r.status == 0) t.add("terminal-status", 0, listOf("${r.id} has no terminal status"), ctx)
    }
}
