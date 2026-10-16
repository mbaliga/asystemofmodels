# Mesh router intelligence: placement across this device, paired peers and the cloud

**Section of:** the ASOM multi-device ("mesh") design session, 2026-09-29.
**Status:** DESIGN PROPOSAL. Nothing here is approved, frozen or executable. It turns the owner's requirement 2 (a router that understands each device's capabilities and *current situation* and routes intelligently) and the "intelligently distributing work" half of requirement 1 into implementable text for the roadmap §7 (v4) design session. v4's entry criteria stand; v1 device validation is still open (`PROGRESS.md`). Every contract addition (§14) and every owner question (§15) needs owner sign-off. Building anything here ahead of v4 needs the owner authorisation described in `platforms.md` OD4 and §15 OD-R6.
**Siblings:** `trust.md` (identity, pairing, the `asom-mesh/1` frames incl. `INFER_*`, `STATE_REQ`/`STATE`, scopes, data classes, `meshEligibility`, write-ahead ledger rows, `lan` egress class), `manifest.md` (signed capability manifest; the claim-versus-observed tracker and its `ClaimView`), `benchmark.md` (measurement, `BenchCalibration`, thermal codes 0–4, passive samples), `platforms.md` (roles R/PA/PF/H, provider availability FSM, constraints C1–C11, conformance families W07 and R01–R04), `contract.md` (combined delta registry).
**This section owns:** the live-state payload (`asom.state/1`) and its exchange and staleness rules; the placement objective; the estimator and scoring arithmetic; the plan algorithm and its composition with the frozen v1 `Router`; the learning policy; failover and retry rules; fairness; route reasons; the honest ranking of what "distributing work" can mean; the offline simulator; the router laws and the R/W07 vector families.

**Artefacts produced in this session** (under `mesh/router-examples/`): `router_ref.py` (reference arithmetic for §1 and the normative integer estimator and scorer of §4–§5; no dependencies), `router_ref.out` (its real output, quoted below), `R02-worked-example.json` (three illustrative R02 vectors generated from the §5.7 example). All device numbers in the example are **assumptions or invented**; none is a measurement.

Tags: **[RVn]** verified this session (sources in §20.1). **[RAn]** assumption (§20.2). Sibling facts keep their own tags (`trust.md` [V1], `platforms.md` [V01], `benchmark.md` [BV02]).

---

## 0. The design in twelve rules

| # | Rule | v1 analog |
|---|---|---|
| G1 | **Whole requests go to one node.** The unit of placement is one attempt of one request on one node (this device, one paired peer, or one cloud provider). Splitting one generation across devices is not a v4 feature (§1). | v1 attempts one provider at a time |
| G2 | **Filter on hard constraints, then score, then order by a total order.** Hard constraints are never traded against speed. Scores are sums of named integer terms in milliseconds-equivalent, so every decision is explainable term by term. | v1 §7: filter → order; `CHEAPEST_ORDER` total order |
| G3 | **Eligibility is decided by policy, never by a claim.** `local-only`, per-app `mesh`, data class, peer class, scopes and pairing status (`trust.md` §7.3) gate which nodes may see content. No manifest or live-state value can make a node eligible. | `No-Train` filter; revocation is checked per request |
| G4 | **Claims are priors; observations replace them.** A peer's manifest and live state are its claims. What the requester measures itself (RTT, transfer rate, time to first token, decode rate) wins as soon as it has enough samples. | v1 `fastest` uses measured EWMA, not catalogue claims |
| G5 | **The frozen meanings survive.** With no local engine and no usable peer (mesh off, no peers, or every peer filtered out) the plan is exactly v1's. `local-only` still means this device only; `X-Asom-Fallback` still restricts to the listed cloud providers; `X-Asom-No-Train` still filters cloud providers. | Contract §5 is frozen |
| G6 | **Sovereign before cloud for `auto`; the cloud is still a watched object.** Within the sovereign set (this device + own peers), the best score wins, with a locality bias toward this device. Cloud comes after every usable sovereign candidate. | Roadmap §7: this device → LAN node → cloud |
| G7 | **The router is a pure function of an immutable snapshot.** `plan(query, snapshot) → plan`. All state (estimators, breakers, counters) lives in the snapshot and changes only through pure reducers fed by events. Same events in, same decisions out. | v1 `Router.plan` is pure given its injected trackers |
| G8 | **No randomness and no ML in placement.** Deterministic estimators (integer EWMA, median windows) are allowed. Stochastic exploration, bandits and learned models are not (§6). | v1 §7: "No ML" |
| G9 | **Content leaves only after the peer accepts, and at most one node holds a body at a time.** Offer, accept, then body (`trust.md` R5). No hedged duplicate requests. | — (new; minimal exposure) |
| G10 | **Every attempt that reaches a wire has a row, on both nodes, before content leaves.** Declined offers, failed dials, retries and probes each get their own row. | The audit's under-reporting lesson |
| G11 | **Every decision carries a route reason** built from the same `RouteRecord` as the echo headers and the ledger row. The app sees a coarse code; the dashboard sees the term-by-term breakdown. | Invariant 9 |
| G12 | **The live-state protocol costs nothing when nobody is asking.** Pull on demand, piggyback on frames already sent, optional short watches only from nodes on mains power. No heartbeats, no broadcast, no gossip. | Invariant 1 ethos; `trust.md` "PING only while a stream is open" |

---

## 1. What "distributing work between devices" really means

This is the question the owner's requirement 1 turns on, so it comes first. There are five candidate meanings for consumer devices over Wi-Fi. I rank them by the benefit a single user with 2–5 own devices can actually get, using current evidence rather than the architecture diagrams of the systems involved.

### 1.1 Evidence (verified this session)

| Evidence | What it shows | Tag |
|---|---|---|
| exo, "Transparent benchmarks" (2024-12-01): one M4 Pro 49.3 tok/s; three M4 Pro in a cluster, single request, **39.7 tok/s**; same cluster, concurrent requests, 108.8 tok/s (2.2×). "The bottleneck here is generally the latency between devices, not the bandwidth." | Splitting a model that fits on one device makes a single request **slower**; concurrency across devices adds throughput | [RV3] |
| exo issue #2295 (2026-09-02): four M3 Ultra, 4-way pipeline, 110 GB model: **25.4 tok/s over Thunderbolt 5 RDMA vs 8.7 tok/s over Wi-Fi LAN** (single request); 69.1 vs 32.8 with 16 concurrent. Measured Wi-Fi RTT 4.9–54 ms (7.5–30 ms average) vs 0.38–0.95 ms on the Thunderbolt bridge | Per-token synchronisation latency, not bandwidth, sets pipeline speed; Wi-Fi costs about 3× here | [RV4] |
| prima.cpp (arXiv 2504.08791): a four-device home cluster (Mac M1, two Linux GPU desktops, an Android phone) on Wi-Fi at 320–610 Mbps and 3–7 ms. For models under 14B its scheduler "removes the other devices and runs only on D3" with the same TPOT and TTFT as plain llama.cpp. Gains appear only at 30B+, where no single device holds the model; 70B reaches 674 ms/token. "Due to Wi-Fi's high latency, pipeline parallelism becomes more suitable" | The best published home-cluster optimiser itself concludes: **if one device can hold the model, use only that device** | [RV2] |
| llama.cpp RPC README: distributes weights and KV cache "in proportion to each device's available memory"; "proof of concept", "fragile and insecure"; "Never run the RPC server on an open network or in a sensitive environment!"; optional RDMA transport (RoCEv2, Thunderbolt 5) with TCP fallback | Layer sharding is a memory-pooling tool; its transport has no authentication and cannot sit on the mesh as is | [RV1] |
| exo 1.0 with RDMA over Thunderbolt 5 (macOS 26.2): reported up to 1.8× with 2 Macs and 3.2× with 4 (tensor parallelism) | Speed from splitting exists, but only on microsecond-latency wired links between Macs | [RV5] (vendor-reported) |
| llama.cpp speculative decoding: draft-model, EAGLE-3, MTP and several n-gram modes, all inside one server (`--spec-type`, `-md`, `--spec-draft-n-max`, default 3) | The speculative-decoding gain is already available **inside one node** with no network round trip | [RV6] |
| SLED (SEC '25, arXiv 2506.09397): edge devices draft, one shared edge server verifies with batching; the headline benefit is **system capacity** (2.6–2.9× more concurrent sessions than centralised serving) | Cross-device speculation pays when many clients share one verifier, not for one user's latency | [RV7] |
| Qwen3-8B: 36 layers, 8 KV heads, head dim 128, hidden 4096 → KV cache 147,456 bytes/token at f16 (144 KiB) | Sizes the KV-transfer cost of prefill/decode disaggregation (computed in `router_ref.out`) | [RV8] |
| llama.cpp Apple-silicon table, LLaMA 7B Q4_0: M4 Pro PP512 439.78 / TG128 50.74 tok/s; M1 107.81 / 14.19; M2 Ultra 1238.48 / 94.27 | A reference point for how much faster a desktop-class node is than a phone (roadmap §1 assumes ~5 tok/s for 8B on a flagship phone) | [RV9], [RA1] |

### 1.2 The five modes

**Mode 1: whole-request placement on the best node.** The request runs start to finish on one node chosen per request. Network cost: one offer/accept round trip, the prompt bytes (kilobytes), and streamed output (bytes per token, pipelined, so it does not slow decoding).
*Arithmetic* (`router_ref.out` [WR], assumptions [RA1][RA2][RA3]): a 500-token prompt with a 300-token answer takes about **76.5 s on the phone** (16.7 s to first token) and about **7.9 s on an M4 Pro-class Mac** (1.27 s to first token) over Wi-Fi with a 10 ms RTT: roughly 10× in total time and 13× in time to first token. It also avoids about 382 J on the phone (0.7% of a 15.4 Wh battery at an assumed 5 W draw [RA4]) and its heat.
*Verdict:* this is where nearly all of the user-visible value is. It is what prima.cpp's own optimiser does when a model fits [RV2].

**Mode 2: parallel fan-out of independent work.** (a) Concurrent requests from different apps (or different users of one device) land on different nodes. (b) One request whose work is naturally independent (an `/v1/embeddings` call with thousands of inputs, a batch of documents) is split into chunks that run on several nodes holding the **identical model file**.
*Arithmetic:* throughput adds up to the sum of node rates, minus one round trip per chunk. The ceiling relative to the fastest node alone is `Σ rates / max rate`; for three heterogeneous nodes at an assumed 1000/400/250 embeddings/s that is 1.65× (`router_ref.out` [FO]). exo measured 2.2× for three equal M4 Pro devices serving concurrent requests (in its pipeline configuration, not whole-request placement) [RV3].
*Verdict:* real value, but for throughput, not latency. 2(a) falls out of load-aware mode-1 placement for free. 2(b) needs a small header-semantics change (one response served by several nodes) and a numerics caveat (§12).

**Mode 3: prefill on one node, decode on another (disaggregation).** Datacenter systems do this to separate compute-bound prefill from memory-bound decode. Across devices it requires shipping the KV cache.
*Arithmetic* (`router_ref.out` [KV]): Qwen3-8B at f16 needs 144 KiB of KV per token. A 4,096-token prompt is **576 MiB of KV, 7.9–15.1 s over 610–320 Mbps Wi-Fi** [RV2][RV8] (4.2–8.0 s at q8_0). The M4 Pro-class node that could do the prefill would take about 9.3 s to prefill it and could simply keep decoding at 45–50 tok/s. The phone that would receive the KV decodes at about 5 tok/s. Every configuration of "strong node prefills, weak node decodes" loses to "strong node does both"; the reverse split is worse. It also needs bit-compatible KV formats across engines and backends [RA8].
*Verdict:* rejected for consumer Wi-Fi. Not parked; there is no plausible configuration where it wins for one user.

**Mode 4: speculative decoding across devices** (a small draft model on one node, verification on another). Each speculation round costs at least one network round trip.
*Arithmetic* (`router_ref.out` [SD], assumptions [RA7]): in the direction a personal mesh actually has (the phone drafts, the desktop verifies), the phone's draft step is slower than the desktop's full-model step, so throughput falls to **10–14 tok/s against 50 tok/s for the desktop alone**. The only non-dominated case is a capacity-bound target (a big model on a slow node S) with a faster drafter D that cannot hold the target: there cross-device speculation gave 3.06–3.51 tok/s against **2.98–3.35 tok/s for the same speculation inside S** and 1.49 tok/s plain, i.e. **about +3–5% over in-node speculation**. In general cross-device beats in-node only when `k × (t_draft,S − t_draft,D) > RTT`, and a Wi-Fi RTT of 7.5–30 ms on average with tails to 54 ms [RV4] eats that margin. In-node speculation is already in llama.cpp [RV6]; SLED's gain is multi-tenant capacity [RV7].
*Verdict:* rejected for v4. In-node speculative decoding is an **engine** feature (v2), not a mesh feature. Parked with a revisit trigger (§1.3).

**Mode 5: layer, pipeline or tensor sharding** (llama.cpp RPC, exo, distributed-llama, prima.cpp).
*Arithmetic* (`router_ref.out` [PP]): in decode, each pipeline boundary moves only 8 KiB per token for an 8B-class model (hidden size 4096 at f16), so bandwidth is irrelevant and **each generated token pays at least one round trip**. For a node that decodes at 50 tok/s alone, adding a 7.5–30 ms RTT per token gives 36–20 tok/s (−27% to −60%). For a 5 tok/s node the loss is 4–13%, which is why sharding looks harmless on slow hardware and why its only real benefit is running a model that no single device can hold (prima.cpp 70B at 674 ms/token, about 1.5 tok/s [RV2]).
*Further costs, independent of speed:* every shard node must stay available for the whole generation, so availability is the product of all nodes' availabilities; every shard node sees activations derived from the prompt, so each is a content-seeing peer for data-class purposes [RA13]; llama.cpp RPC has no authentication and must never face a network [RV1], so it cannot be used through the mesh as is; and the phones in the mesh are the devices whose conditions (`platforms.md` §2.1) fail most often.
*Verdict:* capacity, not speed, over Wi-Fi and Ethernet: **confirmed** with 2024–2026 evidence. Refinement: on Thunderbolt-5 RDMA between Macs, tensor parallelism does give speed [RV5]; that is a wired desk cluster outside this design's network model, and nothing in this section prevents a user from running exo on it separately.

### 1.3 Ranking and tiered recommendation

| Rank | Mode | Ship in | Expected benefit (estimate) | Assumptions behind the estimate | What it does not give |
|---|---|---|---|---|---|
| 1 | **Whole-request placement** (incl. 2(a): concurrent requests spread by load) | **v4.0** | Phone → desktop: ~10× total time, ~13× time to first token for an 8B model; ~0.7% phone battery saved per 300-token answer. Desktop ↔ desktop: benefit only under contention | [RA1]–[RA4]; M4 Pro-class numbers from [RV9]; Wi-Fi RTT 10 ms | Any speed-up for a model that already runs well on the requesting device; any capacity beyond the largest single node |
| 2 | **Fan-out of independent chunks** within one request (2(b): embeddings arrays; later batch) | **v4.1** (needs RC-8) | Up to `Σ rates / max rate` throughput, e.g. 1.65× for 1000/400/250 per s; near 2–3× for equal nodes | Nodes hold the identical file (same sha256); chunk ≥ 8 inputs so one RTT per chunk is negligible | Lower latency for a small request; bit-identical embeddings across different backends [RA9] |
| 3 | **Capacity sharding** (mode 5) for models no single node can hold | **Parked.** Owner-gated design spike at the earliest (OD-R4); AC-powered desktop nodes only; never phones; never llama.cpp RPC as is | Makes a 30–70B model *possible* on a home cluster at roughly 1–2 tok/s (prima.cpp's 70B at 674 ms/token [RV2]) | prima.cpp-class scheduler; wired or low-latency links | Speed. Interactive chat at 70B on Wi-Fi |
| 4 | **Speculative decoding across devices** | **Not in this roadmap.** In-node speculation belongs to the v2 engine | +3–5% over in-node speculation in the one non-dominated case; negative in the common phone-drafts case | [RA7]; Wi-Fi RTT [RV4] | Anything that in-node speculation (already in llama.cpp [RV6]) does not give. Revisit only if measurements show `k × Δt_draft ≫ RTT` on the user's own hardware |
| 5 | **Prefill/decode disaggregation** | **Never** (for consumer Wi-Fi) | Negative: 7.9–15.1 s of KV transfer for a 4k prompt | [RV2][RV8] | — |

**What this means for the owner's requirement 1.** "Intelligently distributing work" is delivered, honestly, as: *every request goes to the node that will serve it best right now; concurrent work spreads across nodes; large independent jobs are chunked across identical models.* It is not "all my devices cooperate on one answer". The Peers tab and the documentation must say so in those words, because the second reading is what a user will otherwise assume.

---

## 2. Scope, layering and the pure-core boundary

### 2.1 Where the router sits

```
 app (AIDL-paired)        iOS thin requester (§11)        peer requester
      │ HTTP 127.0.0.1         │ asom-mesh (no routing)        │ asom-mesh INFER_OFFER
      ▼                        ▼                               ▼
┌──────────────────────────────── node (JVM: :server + :node-desktop / :app) ─────────────────────────────┐
│  MeshPipeline (pure JVM, :server)  ── executes attempts, writes rows, commits echo headers ─┐           │
│      │ plan(query, snapshot)                                                                │           │
│      ▼                                                                                      │           │
│  MeshRouter (pure JVM, :core:routing/mesh) ── calls ──▶ v1 Router.plan (unchanged)          │           │
│      ▲ immutable MeshSnapshot                                                               ▼           │
│  MeshStateStore (pure reducers: estimators, breakers, claim views, link stats, counters) ◀── events     │
│      ▲                        ▲                         ▲                                               │
│  SituationProbe (platform)  PeerStateCache (STATE, piggyback)  ClaimTracker (manifest.md §11.5)          │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Module placement (additive; honours brief §4)

| Piece | Module | Why there |
|---|---|---|
| `asom.state/1` DTOs, codec, staleness classifier | `:core:mesh` (pure JVM, `platforms.md` §7.1) | Shared by router, peer listener and conformance runner |
| `MeshRouter`, estimator, scorer, reasons, `MeshStateStore` reducers | `:core:routing` in package `xyz.mdhv.asom.routing.mesh` | Next to the v1 `Router` it composes; pure JVM |
| `MeshPipeline` (attempt loop, ledger choreography, failover) | `:server` | Mirrors v1 `RoutePipeline`; pure JVM |
| `SituationProbe` implementations | `:app` (Android), `:node-desktop` (Linux sysfs; macOS via `libasom-platform`) | Platform code stays out of the pure core |
| Simulator | test-only source set of `:core:routing` (or a test-only `:mesh-sim` module) | Never shipped; runs in `jvmTest` on a bare JDK |

No `android.*` import enters any pure module. Swift never gets a router (`platforms.md` C6, §6.3).

### 2.3 Interfaces between the pure core and its providers

```kotlin
package xyz.mdhv.asom.routing.mesh

// ---------- identities and keys ----------
@JvmInline value class NodeId(val b64u: String)                 // trust.md §2.3 nodeId; SELF uses the local nodeId
enum class Tier { SELF, PEER, CLOUD }                            // ordinal is part of the total order (§5.4)
data class FileKey(val modelId: String, val fileSha256: String, val quant: String?, val fileBytes: Long)
data class ClaimKey(val node: NodeId, val fileSha256: String, val backend: String)

// ---------- static claims (manifest.md ClaimView; benchmark.md BenchCalibration for SELF) ----------
data class PerfPrior(
    val decodeAt: List<Pair<Int, Long>>,        // (contextTokens, milliTokPerSec), 1..4 points, ascending ctx
    val effectiveDecodeAt512: Long,             // ClaimView.effectiveMilliTokPerSec (observed-corrected)
    val prefillMilliTokPerSec: Long,
    val ttft0Ms: Long,
    val steadyMilliTokPerSec: Long,
    val throttleOnsetMs: Long?,                 // null = no onset observed
    val powerMilliW: Long?,                     // null = not measured -> class default (§4.4)
    val kvBytesPerToken: Long,
    val peakProcessBytes: Long,
    val claimState: ClaimState,                 // UNVERIFIED | CORROBORATED | WEAK | DISCREPANT | LOCAL_MEASURED
    val observedN: Int,
    val flags: Set<String>,                     // e.g. "numerics-fail" (benchmark.md §3.4) -> ineligible
)

// ---------- dynamic situation (asom.state/1 parsed + classified, §3) ----------
data class Situation(
    val freshness: Freshness,                   // FRESH | WARM | STALE | EXPIRED (computed by the requester, §3.5)
    val availability: Availability,             // mode + fsm + reason (platforms.md §2.1)
    val powerSource: PowerSource, val charging: Boolean, val batteryPermille: Int?, val saver: Boolean,
    val batteryDesignMilliWh: Long?,
    val thermalCode: Int,                       // benchmark.md §6.1 codes 0..4
    val headroomPermille: Int?, val forecastPermille: Int?, val governor: Governor, val busyForMs: Long,
    val availBytes: Long?,
    val loaded: Set<String>, val held: Set<String>,          // file sha256s
    val inflight: Int, val maxConcurrent: Int, val depth: Int, val estStartMs: Long, val localActive: Boolean,
    val userActive: Boolean?, val metered: Boolean,
    val limits: PeerLimits,                     // trust.md §7.2, from HELLO_ACK
)

// ---------- requester-observed (never a peer claim) ----------
data class LinkStats(val rttMs: Long, val kbps: Long, val path: Path, val samples: Int, val sessionWarm: Boolean)
data class BreakerView(val coolingUntilMs: Long?, val declineBackoffUntilMs: Long?)

data class NodeView(
    val node: NodeId, val nodeTag: String, val tier: Tier, val deviceClass: String,
    val peer: PeerRowView?,                     // null for SELF; status, class, scopes, routeEnabled, ceiling
    val files: List<FileKey>,
    val perf: Map<ClaimKey, PerfPrior>,
    val situation: Situation,
    val link: LinkStats?,                       // null for SELF
    val breaker: BreakerView,
)

data class MeshSnapshot(
    val nowMs: Long,                            // requester monotonic clock, ms
    val meshEnabled: Boolean,
    val self: NodeView,
    val peers: List<NodeView>,                  // order irrelevant (law RL6)
    val cloudRates: Map<String, Long>,          // providerId -> observed milliTok/s (CloudRateTracker), absent = prior
    val caps: Map<ClaimKey, CapCounter>,        // §5.5 anti-poisoning counters
    val config: MeshConfig,                     // §4.4 parameters
)

data class MeshQuery(
    val v1: RouteQuery,                         // the frozen v1 query, untouched
    val op: Op,                                 // CHAT | COMPLETIONS | EMBEDDINGS
    val stream: Boolean,
    val promptTokens: Int, val promptBytes: Long,
    val maxTokensCap: Int?,                     // max_tokens / max_completion_tokens, only if RC-9 is approved
    val outTokens: Int,                         // §4.2 E0 (computed once, before planning)
    val app: AppPolicy,                         // v2.5 per-app table incl. trust.md C-7 `mesh`
    val dataClass: DataClass,                   // trust.md §10.2 classify(req, app)
    val deadlineMs: Long,
)

// ---------- the providers the platform layer implements ----------
fun interface MeshStateSource { fun snapshot(nowMs: Long): MeshSnapshot }   // assembles from the store; no I/O inside plan()
interface SituationProbe { fun sample(nowMs: Long): SelfSituationSample }   // Android/Linux/macOS/iOS adapters
interface PeerStateCache { fun onState(peer: NodeId, s: StateFrame, rxMonoMs: Long); fun onPiggyback(peer: NodeId, st: StateDigest, rxMonoMs: Long) }

// ---------- the router ----------
class MeshRouter(private val v1: Router, private val eligibility: MeshEligibility /* trust.md §7.3 */) {
    fun plan(q: MeshQuery, snap: MeshSnapshot): MeshPlan         // pure; throws AsomException with a typed code
}
data class MeshPlan(val attempts: List<PlannedAttempt>, val excluded: List<Exclusion>, val capDelta: CapDelta)
data class PlannedAttempt(
    val tier: Tier, val node: NodeId?, val file: FileKey?, val cloud: Candidate?,   // Candidate = v1 type
    val estimate: Estimate?, val score: ScoreBreakdown?, val reason: RouteReason, val probeOnly: Boolean,
)
```

Rules for the boundary:
- `plan()` performs **no I/O, reads no clock and iterates no hash map in a decision path** (sorted collections only). The snapshot carries `nowMs`.
- `SituationProbe` is the only platform-specific input on the self node. Its per-platform sources are listed in §3.2. It is sampled on demand at plan time if its last sample is older than 1 s (Android headroom returns `NaN` if polled faster [RV10]; `benchmark.md` [BV02]), otherwise the cached sample is used.
- Everything a peer tells us enters through `PeerStateCache` (live state) or `ClaimTracker` (manifests, `manifest.md` §11.5). Both are pure reducers over typed events.

---

## 3. Inputs

### 3.1 Static capability: claims and calibration

| Input | Source | Used for |
|---|---|---|
| Per (peer, file sha256, backend): decode rate by context, prefill rate, TTFT offset, steady rate, throttle onset, memory, power | The verified manifest body (`manifest.md` §4.1) through `ClaimView` (§11.5 there): `effectiveMilliTokPerSec`, `state`, `n` | Priors for E3–E8 (§4.2) |
| Per (self, file, backend): the same quantities | `benchmark.md` §10.6 `BenchCalibration` (active + passive lanes combined, local only) | Self estimates; `LOCAL_MEASURED` claim state |
| Model identity, file size, quant, `rank`, `ctx` | Catalogue (v1 §6, v2 fields in roadmap §9) | Filters (context, memory), quality term |
| Device class, battery design capacity, cooling class | Manifest `device.*` (self-reported) | Class defaults and the battery term |
| No manifest yet (a newly paired peer that never benchmarked) | Editorial reference table from the catalogue repo (roadmap v2 P6) for (model, backend, device class), else **class defaults** (§4.4), state `UNVERIFIED` with `disc = 700‰` | Pessimistic priors until observed |

**One claim-versus-observed tracker, not two (coordination item).** `manifest.md` §11.5 defines `ClaimTracker` (states UNVERIFIED/CORROBORATED/WEAK/DISCREPANT, window of 20, integer medians). `benchmark.md` §10.7 defines a second per-(peer, model) EWMA of observed-to-claimed ratios with a "claims not borne out" flag and leaves "what the flag does" to this section. Two trackers over the same observations can disagree (the M2/B2 one-source rule). **Recommendation:** the router consumes `manifest.md`'s `ClaimView` only; `benchmark.md` §10.7's ratio becomes a *display* of the same window (`observed X% of claimed`), computed from `ClaimTracker`'s window rather than kept separately. The flag's effect is `DISCREPANT` in §5.3/§5.5.

### 3.2 Dynamic situation: `asom.state/1`

Integer-only (C1), additive-evolution rules as `manifest.md` §4.3. Schema in Appendix A. Example:

```jsonc
{
  "v": 1,
  "seq": 4711,                         // per (sender, session), strictly increasing; receiver drops seq <= last seen
  "sampledAgeMs": 800,                 // age of the oldest fast field when sent (sender's monotonic clock); cap 60000
  "availability": { "mode": "always", "fsm": "SERVING", "reason": "none" },     // platforms.md §2.1
  "power":   { "source": "ac", "charging": false, "batteryPermille": null, "saver": false },
  "thermal": { "code": 0, "headroomPermille": 180, "forecastPermille": 240, "forecastSec": 10,
               "governor": "RUN", "busyForMs": 0 },
  "memory":  { "availBytes": 21474836480 },
  "engine":  { "backend": "metal", "commit": "0123abcd", "confVersion": "1.0.0",
               "loaded": [ { "fileSha256": "6bc3…c810", "ctxTokens": 8192, "kvFreeTokens": 8192 } ],
               "held":   [ "6bc3…c810", "9f1e…0a77" ] },
  "queue":   { "inflight": 0, "maxConcurrent": 1, "depth": 0, "estStartMs": 0, "localActive": false },
  "user":    { "active": false },
  "net":     { "metered": false },
  "manifest": { "seq": 17, "bodyDigest": "k3Jd…" },
  "watch": "none"                      // none | granted | refused-battery | refused-limit
}
```

**Fields, classes and per-platform sources.** "Fast" fields change within seconds and are subject to the §3.5 substitution rules; "slow" fields are valid until the state expires.

| Field | Class | Android | Linux / Deck | macOS | iOS / iPadOS (PF only) | Router use |
|---|---|---|---|---|---|---|
| `availability.*` | slow | FGS + conditions FSM (`platforms.md` §2.1) | same | same | same (foreground-only) | Hard filter F9 |
| `power.source`, `charging` | slow | `BatteryManager` / sticky `ACTION_BATTERY_CHANGED` | `/sys/class/power_supply/*/online`, `status` | IOKit power source (shim) | `UIDevice.batteryState` | F11; S2 |
| `power.batteryPermille` | fast | `BATTERY_PROPERTY_CAPACITY` ×10 [RV11] | `capacity` ×10 | IOKit | `batteryLevel` (5% steps, `benchmark.md` [BV08]) | F11; S2 multiplier |
| `power.saver` | slow | `PowerManager.isPowerSaveMode()` [RV14] | power profile daemon if present, else false | Low Power Mode | Low Power Mode | F11 |
| `thermal.code` | fast | `PowerManager.getCurrentThermalStatus()` [RV10] mapped per `benchmark.md` §6.1 | lowest-margin zone vs trip points | `thermalState` | `thermalState` [RV12] | F10; E7; S3 |
| `thermal.headroomPermille`, `forecastPermille` | fast | `getThermalHeadroom(0)` and `(10)` ×1000, polled ≤ 1 Hz [RV10] | null | null | null | S3 |
| `thermal.governor` | fast | v2 P4 governor | same | same | same | F10 |
| `thermal.busyForMs` | fast | continuous engine-busy time (resets after 60 s idle) | same | same | same | E7 |
| `memory.availBytes` | fast | `ActivityManager.MemoryInfo.availMem` | `MemAvailable` | `host_statistics64` (shim) | `os_proc_available_memory()` [RV13] | F6 |
| `engine.loaded`, `held` | slow | engine + model store | same | same | same | F4, E4 |
| `queue.*` | fast | v2 P3 single-flight queue | same | same | same | E3; fairness |
| `user.active` | fast | `PowerManager.isInteractive()` (screen on and interactive) [RV14] | desktop session idle hint (logind `IdleHint`) [RA16], else false | HID idle time via the shim [RA16] | always true while serving (PF) | F12 |
| `net.metered` | slow | `NET_CAPABILITY_NOT_METERED` | NetworkManager `Metered` | `NWPathMonitor.isExpensive` | `NWPathMonitor.isExpensive` | F13 (`platforms.md` C8) |
| `manifest.seq`, `bodyDigest` | slow | manifest store | same | same | same | Pull-on-hint (`manifest.md` §13.2) |

**What live state never contains:** app identities, request contents or counts per app, ledger rows, keys or key presence, cloud providers, location, the device name (already in `HELLO` for own-class peers only), precise temperatures in °C. Other-class peers (if `trust.md` OD-2 ever allows them) receive only `{"v":1,"availability":{"fsm":…}}`; everything else is omitted.

### 3.3 Requester-observed inputs (never taken from the peer)

| Quantity | How the requester measures it | Estimator |
|---|---|---|
| `rttMs` per (peer, path) | `STATE_REQ→STATE` turnaround; `INFER_OFFER→ACCEPT/DECLINE` turnaround minus the peer's reported `decideUs` if present; `PING/PONG` while a stream is open | Integer EWMA `e ← (7e + x)/8` (the `benchmark.md` §10.2 rule), separate per path (`lan`, `overlay`), because a relayed overlay path can be far slower [RA14] |
| `kbps` per (peer, path) | Only from transfers ≥ 256 KiB (large bodies, non-streamed responses, embedding chunks): bytes × 8 / ms | Same EWMA; until measured, a path-class default (§4.4) |
| Observed TTFT and decode rate | Requester monotonic clock around `INFER_BODY` send, first chunk, last chunk (`manifest.md` §11.5 formula) | Fed to `ClaimTracker` |
| Declines, failures, unreachability | Attempt outcomes | Breaker (§8) and decline back-off |
| Cloud decode rate per provider | usage tokens and stream duration of completed cloud streams | `CloudRateTracker` EWMA (new, pure); used only by `fastest` (§5.4) |
| Output length per app | completion tokens of the app's past requests | Per-app EWMA → E0 (§4.2) |

### 3.4 Exchange protocol

The frames `STATE_REQ` (0x20) and `STATE` (0x21) are reserved by `trust.md` §3.3 and require the inbound scope `state` (default on for own-class peers, `trust.md` §7.1). This section defines their payloads and when they are sent.

```jsonc
// 0x20 STATE_REQ (requester -> provider), new odd stream
{ "v": 1,
  "watch": null }                      // or { "maxMs": 120000, "minIntervalMs": 5000 } (see "watch" below)

// 0x21 STATE (provider -> requester), same stream: exactly one asom.state/1 object.
// If a watch was granted, further STATE frames follow on the same stream, then the provider ends the stream
// with a minimal schema-valid STATE {"v":1,"seq":<next>,"sampledAgeMs":0,"availability":{"fsm":"<current>"},"end":true}
// when maxMs elapses; if the session closes first, the watch simply ends with it.
```

**Three sources of state, in order of preference (cheapest first):**

1. **Piggyback (free).** Every frame the peer already sends carries a compact digest `st`:
   ```jsonc
   "st": { "seq": 4712, "fsm": "SERVING", "code": 0, "gov": "RUN", "depth": 1, "inflight": 1,
           "estStartMs": 6200, "bat": null, "chg": false, "ua": false }
   ```
   Added as an optional member to `HELLO_ACK`, `INFER_ACCEPT`, `INFER_DECLINE` and `INFER_END` (contract delta RC-2). A requester that is actively using a peer therefore has fresh fast fields without asking.
2. **Pull (on demand).** A requester sends `STATE_REQ` only when (a) it is about to plan and a *candidate that could win* has state older than `WARM` (§3.5), and the plan's first-choice decision would change depending on it; (b) a session was just established and `HELLO_ACK` carried no `st`; (c) the user opened the Peers tab; (d) a fan-out job (§12) is about to start. Never on a timer.
3. **Watch (optional, provider-controlled).** A requester may ask for a watch of at most 120 s while it has work in flight or queued for that peer (typically a fan-out job or a long queue). **The provider grants a watch only if it is on mains power** (`power.source == ac`); otherwise it answers once with `"watch":"refused-battery"`. During a watch the provider sends a new `STATE` only when a **banded** field changes: `fsm`, `governor`, `thermal.code`, `queue.depth` (0, 1, 2, ≥3), `user.active`, `power.source`, `charging`, or `batteryPermille` crossing a 100‰ boundary; and never more often than `minIntervalMs` (≥ 5 s).

**Rate limits (server-enforced; excess gets `ERROR PEER_BUSY {retryAfterMs}`):** ≤ 1 `STATE_REQ` per 5 s per peer; ≤ 1 active watch per requester; ≤ 12 watch notifications per minute per requester.

**Ledger.** State exchange is control-plane traffic, recorded in the per-session `meshKind=control` row that `trust.md` §11.3 defines (frame counts and bytes in and out). This design adds no per-heartbeat rows because there are no heartbeats. Whether per-session granularity satisfies "every network event writes a ledger row" is `trust.md` OD-1; this section does not assume the answer.

### 3.5 Staleness

Staleness is computed entirely on the **requester's monotonic clock**, so a wrong peer clock cannot make old state look fresh:

```
ageMs(state) = (nowMono - rxMono) + min(state.sampledAgeMs, 60_000)
               where rxMono is when the frame (STATE or piggyback) was received
freshness    = FRESH   if ageMs <= 5_000
               WARM    if ageMs <= 30_000
               STALE   if ageMs <= 300_000
               EXPIRED otherwise, or if the session to that peer is closed and the last state is older than 30_000,
                       or if seq went backwards (reset), or if the peer sent GOAWAY since
```

**What each class may be used for:**

| Freshness | Slow fields | Fast fields | Effect on the plan |
|---|---|---|---|
| FRESH, WARM | as received | as received | normal |
| STALE | as received | **pessimistic substitution:** `thermal.code = max(last, 1)` if last ≥ 1 else last; `queue.estStartMs = last + (last depth > 0 ? meanServiceMs : 0)`; `batteryPermille = last − 50` if on battery; `user.active = last` | uncertainty term S6 = 25% of `totalMs` (§4.3); may not override a fresher candidate on a tie |
| EXPIRED | last known (models held, availability mode, class) | **unknown** | candidate is **probe-only**: hard filters that depend on fast fields are skipped (the peer's own `INFER_OFFER` decision table is authoritative, `trust.md` §7.3); S6 = 50% of `totalMs`; placed after every non-expired sovereign candidate of the same usability class |

A probe-only attempt costs at most one offer and one decline: one round trip, **no content** (R5). That is the entire price of stale knowledge, which is why the protocol can afford to be lazy.

**Law RL13** (§13.7): a candidate's rank with STALE or EXPIRED state is never better than the rank the same candidate would have with identical FRESH state.

### 3.6 The battery cost of the protocol

The design goal is that an idle mesh costs **zero** radio wake-ups for state, and an active one costs at most one extra exchange per request that is not already piggybacked.

| Pattern | Exchanges while idle | Exchanges per request | Why accepted or rejected |
|---|---|---|---|
| **This design** (piggyback + lazy pull + mains-only watch) | 0 | 0 when the peer was used in the last 30 s; else ≤ 1 `STATE_REQ` per candidate that could win, or 0 if the offer itself is used as the probe | Accepted |
| Periodic heartbeat (e.g. every 5 s) | 720/h per peer pair, on **both** radios | 0 | Rejected: keeps phone radios out of power save [RA5]; a mesh of N nodes has N(N−1) streams of it |
| Provider push on every change | unbounded; driven by thermal jitter | 0 | Rejected: phones' thermal and battery fields change constantly; the watch's banding + mains-only rule keeps the useful part |
| Gossip (peers relay others' state) | N² | 0 | Rejected: relays information about third nodes (a privacy leak toward other-class peers, `trust.md` §6.2 limits even locator hints) and makes staleness unauditable |
| Pull every peer before every request | 0 | N | Rejected: adds N round trips and N wake-ups per request, most of them for peers that could not win |

**Honest limits.** The per-exchange energy on Wi-Fi is not quantified here [RA5]; the claim is only that this design sends strictly fewer exchanges than the alternatives. Session establishment itself (TLS handshake, `HELLO`) is `trust.md`'s cost; sessions close after 5 min idle (`trust.md` §3.3), so the first request after a quiet period pays a handshake (E1 includes it).

### 3.7 The requester's picture of the mesh

What "the requesting device understands all the other devices" means concretely: for every PAIRED peer with `routeEnabled`, the requester holds one `NodeView` (§2.3) assembled from four sources with different trust levels, and the Peers tab renders it with the source of every number:

| Row in the Peers tab | Source | Label shown |
|---|---|---|
| "Holds: Qwen3-8B 4-bit, Qwen3-1.7B 8-bit" | `STATE.engine.held` + catalogue | (reported by the device) |
| "Writes ~45 tokens/s with Qwen3-8B" | `ClaimView.effectiveMilliTokPerSec` with its state | (claimed) / (confirmed by 7 of your requests) / (claims more than it delivers: 58% of claimed) |
| "First word after ~1.3 s for a short question" | E8 for a 500-token prompt at current state | (estimated now) |
| "Right now: on power, cool, idle" / "busy: 1 queued" / "not lending: on battery" | `STATE` fast fields, with freshness | (as of 12 s ago) |
| "Network: 11 ms, Wi-Fi" | requester's own `LinkStats` | (measured by this device) |
| "Last 24 h: 31 requests sent, 2 declined, 0 lost" | own ledger rows for that peer | (from this device's ledger) |
| "Report: signed, pinned key, seq 17, measured 2026-09-20" | `manifest.md` verification result | (verified signature; not proof of honest measurement) |

This is the only place the user sees another device's situation; apps never do (§10.3).

### 3.8 Rejected input sources

| Source | Why rejected |
|---|---|
| mDNS TXT or broadcast capability announcements | Open disclosure to the LAN; roadmap §11 stop-line; `manifest.md` §13.4 |
| A peer's own report of its RTT or bandwidth to me | The requester can measure both itself; a claim adds nothing but a lie channel (G4) |
| Peer-reported "estimated tokens/s right now" as a scalar | Replaced by the fields it would be computed from, so the requester's own estimator (and its laws) apply uniformly |
| Reading another node's ledger to learn its load | Peers get no data-read capability (`trust.md` R7) |

---

## 4. The placement objective

### 4.1 Objectives and how each enters

| Objective | Representation | Kind |
|---|---|---|
| Time to first token | E8 `ttftMs`; counted twice in S1 for streaming requests | score |
| Total time | E10 `totalMs` | score |
| Energy on each device | E11 `energyMilliJ` → S2 battery cost on battery-powered, not-charging nodes; S3 heat cost on small devices near throttling | score |
| Monetary cost of the cloud alternative | v1 blended price (catalogue pricing) orders the cloud tier; `cheapest` puts every $0 sovereign candidate first | tier order |
| Privacy class of the peer | `meshEligibility` (`trust.md` §7.3), data class vs peer ceiling; a small **locality bias** S4 toward this device | hard filter + score |
| Model quality fit | Concrete model: exact `modelId`, quant penalty S5. Virtual selector: catalogue `rank` floor and rank penalty | hard filter + score |
| Device and user protection | battery floor, charging rules, user-active, thermal code, governor | hard filter |
| Deadline | `deadlineMs` vs E10 | usability gate |

Money and time are **not** traded in one weighted sum. Sovereign candidates cost $0 and cloud candidates are ordered by the v1 rules; the policy decides how the two tiers merge (§5.4). This avoids an exchange rate ("ms per micro-dollar") that would be arbitrary and hard to explain.

### 4.2 The estimator (normative; integers only, floor division unless `ceilDiv`)

For a sovereign candidate `c = (node n, file f)` and query `q`. `P = q.promptTokens`, `B = q.promptBytes`, `N = q.outTokens`, `rtt = n.link.rttMs`, `kbps = n.link.kbps`.

```
E0  outTokens N      = clamp(q.maxTokensCap ?: appEwmaOut(app) ?: 256, 1, 32_768)
                       (maxTokensCap only if RC-9 is approved; otherwise the per-app EWMA of past completions)
E1  netMs            = SELF ? 0 : rtt                               // offer -> accept
                              + ceilDiv(B * 8, kbps)                // body upload
                              + (sessionWarm ? 0 : 2 * rtt + handshakeExtraMs)
E2  loadMs           = f.sha in loaded ? 0 : ceilDiv(f.fileBytes, loadBytesPerMs(n))
E3  queueMs          = max(situation.estStartMs, ownReservationsMs(n))     // §9 reservations
E4  preEff           = prior.prefillMilliTokPerSec * discPermille(prior) / 1000
                       discPermille = LOCAL_MEASURED or CORROBORATED ? 1000 : WEAK ? 800 : UNVERIFIED ? 700 : DISCREPANT ? 400
E5  prefillMs        = ceilDiv(P * 1_000_000, preEff) + prior.ttft0Ms
E6  decEff           = prior.effectiveDecodeAt512 * decodeAt(P) / decodeAt(512)     // context-depth scaling; piecewise linear, clamped
E7  decodeMs         = thermalAwareDecode(N - 1, decEff, prior.steadyMilliTokPerSec, prior.throttleOnsetMs,
                                          situation.busyForMs, queueMs, prefillMs, situation.thermalCode)
E8  ttftMs           = netMs + loadMs + queueMs + prefillMs + (SELF ? 0 : ceilDiv(rtt, 2))
E9  (reserved)
E10 totalMs          = ttftMs + decodeMs
E11 energyMilliJ     = powerMilliW(n) * (prefillMs + decodeMs) / 1000
E12 batteryUsedPermille = onBatteryNotCharging(n) ? ceilDiv(E11 * 1000, batteryDesignMilliWh(n) * 3600) : 0

thermalAwareDecode(m, dec, steady, onsetMs, busyMs, queueMs, prefillMs, code):
    if m <= 0: return 0
    already = busyMs + queueMs + prefillMs
    if code >= 2 or (onsetMs != null and already >= onsetMs):        // moderate or already past onset
        return ceilDiv(m * 1_000_000, min(dec, steady))
    if onsetMs == null: return ceilDiv(m * 1_000_000, dec)
    coolMs  = onsetMs - already
    tokCool = coolMs * dec / 1_000_000
    if m <= tokCool: return ceilDiv(m * 1_000_000, dec)
    return coolMs + ceilDiv((m - tokCool) * 1_000_000, steady)
```

Bounds that make overflow impossible (checked by RL20): `P ≤ 2^20`, `N ≤ 2^15`, all rates ≥ 1 and ≤ 10^9 milliTok/s, bytes ≤ 2^40, times ≤ 2^40 ms. The largest product is `P × 10^6 ≈ 1.05 × 10^12`. All additions saturate at `2^53 − 1` (C1).

**Cloud candidates** are estimated only for `fastest` (§5.4): `ttftMs = LatencyTracker.ewma(provider)` (v1's time-to-stream-start signal), `totalMs = ttftMs + ceilDiv((N − 1) × 10^6, cloudRate(provider))`, where `cloudRate` is the observed `CloudRateTracker` value or the prior `cloudDecodePriorMilliTokPerSec` [RA11]. An unmeasured provider keeps v1's rule: it sorts after measured ones.

### 4.3 Score terms (lower is better; each shown in the route reason)

| Term | Formula | Meaning shown to the user |
|---|---|---|
| **S1 time** | `totalMs + (stream ? ttftMs : 0)` | "est. first word 1.3 s, done in 7.9 s" |
| **S2 battery** | `E12 × msPerBatteryPermille × lowBatteryMult / 1000`, where `lowBatteryMult = 1000` (≥ 500‰), `2000` (200–499‰), `4000` (< 200‰); 0 on mains or while charging | "would use about 0.7% of this phone's battery" |
| **S3 heat** | on `phone`/`tablet`/`handheld` only, when `thermalCode ≥ 1` or `forecastPermille ≥ 750`: `(prefillMs + decodeMs) × heatPermille / 1000`, with `heatPermille = 500` if that node's `user.active`, else 250 | "this phone is warm and in your hand" |
| **S4 locality** | `SELF: 0`; `PEER: peerBiasMs` | "content stays on this device unless another device is at least 1 s better" |
| **S5 quality** | concrete model: `quantPenaltyMs[f.quant]`; virtual selector: `+ (rank − bestRankInSet) × msPerRankStep` | "uses a 4-bit copy" |
| **S6 uncertainty** | `STALE: totalMs × 250 / 1000`; `EXPIRED: totalMs × 500 / 1000`; else 0 | "last heard from the Deck 3 min ago" |
| **Total** | `S = S1 + S2 + S3 + S4 + S5 + S6` (saturating) | — |

**Usability gate** (used by `auto`, §5.4): `usable(c) ⇔ decEff ≥ minDecodeMilliTokPerSec ∧ ttftMs ≤ maxTtftMs ∧ totalMs ≤ q.deadlineMs`.

### 4.4 Parameters (defaults; every value is a starting guess [RA12] unless marked)

| Parameter | Default | Unit | Status |
|---|---|---|---|
| `peerBiasMs` | 1,000 | ms | assumption; owner-tunable (OD-R1) |
| `msPerBatteryPermille` | 2,000 | ms per 1‰ of battery | assumption |
| `heatPermille` / user-active | 250 / 500 | ‰ of active time | assumption |
| `minDecodeMilliTokPerSec` | 4,000 | milliTok/s | assumption (OD-R1). `benchmark.md` §12.3 "comfortable" is 10 tok/s; usable is deliberately lower |
| `maxTtftMs` | 20,000 | ms | assumption (OD-R1) |
| default `deadlineMs` | 120,000 | ms | assumption; overridden by the offer's `deadlineMs` for peer-originated work |
| `handshakeExtraMs` | 40 | ms | assumption [RA6] |
| `loadBytesPerMs` defaults | phone 500,000; laptop/desktop 2,000,000; handheld/sbc 1,000,000 | bytes/ms | assumption [RA15]; replaced by `benchmark.md` load measurements when present |
| path `kbps` defaults | `lan` 100,000; `overlay` 20,000 | kbit/s | assumption; conservative vs the 320–610 Mbps measured in [RV2] |
| class `powerMilliW` defaults | phone 5,000; tablet 7,000; handheld 15,000; laptop 30,000; desktop 150,000; sbc 8,000 | mW | assumption [RA10]; used only for S2 when not measured |
| `quantPenaltyMs` | F16/BF16/Q8_0: 0; Q6_K: 200; Q5_K_M: 400; Q4_K_M: 800; Q4_0: 1,000; Q3_K_M: 2,500; Q2_K: 5,000; unknown: 1,000 | ms | heuristic prior, not a measurement of quality; model-dependent |
| `msPerRankStep` | 5,000 | ms | assumption. An unranked model counts as `bestRankInSet + 10` |
| `autoRankFloor` | none (no floor) | catalogue rank | user setting in the Models tab ("models `auto` may use on my devices"); with no floor only S5's rank penalty applies |
| `cloudDecodePriorMilliTokPerSec` | 50,000 | milliTok/s | assumption [RA11] |
| `maxAttempts` | 6 (at most 3 peer attempts) | count | engineering choice |
| `offerTimeoutMs` / `headTimeoutMs` | 2,000 / `max(5,000, 2 × ttftMs)` | ms | engineering choice |
| breaker curve | 30 s → 15 min, doubling | — | **same as v1 `CooldownRegistry`** (reused) |

**Any parameter values** must satisfy the laws in §13.7; the laws are what is normative, not the defaults.

---

## 5. The algorithm

### 5.1 Overview

Filter on hard constraints → estimate → score → apply the anti-poisoning cap → merge the sovereign set with the v1 cloud plan per policy → truncate to `maxAttempts` → attach reasons. No step consults randomness, wall-clock time or iteration order.

### 5.2 `plan()` (normative pseudocode)

```kotlin
fun plan(q: MeshQuery, s: MeshSnapshot): MeshPlan {
    val policy = Policy.fromWire(q.v1.model) ?: q.v1.policyHeader ?: s.config.defaultPolicy

    // 0. Conservative extension (law RL1): no engine and no mesh -> exactly v1. (No engine + mesh on but every
    //    peer filtered also yields exactly v1's list, via steps 4-8 with an empty sovereign set.)
    if (!s.meshEnabled && !s.self.hasEngine()) return v1Only(q)            // v1.plan(q.v1), each Candidate as a CLOUD attempt

    // 1. local-only keeps its frozen meaning: this device only (trust.md §10.3; roadmap v2 queue-then-error).
    if (policy == Policy.LOCAL_ONLY) return selfOnly(q, s)                // LOCAL_ENGINE_ABSENT if no engine (v1 behaviour)

    // 2. The cloud tier, from the UNCHANGED v1 router. Typed failure = empty tier + remembered code.
    val (cloud, cloudErr) = try { v1.plan(q.v1) to null } catch (e: AsomException) { emptyList<Candidate>() to e.code }

    // 3. X-Asom-Fallback keeps its v1 meaning: restrict to the listed cloud providers. No self, no peers (RL3).
    if (q.v1.fallback.isNotEmpty()) return cloudOnly(cloud, cloudErr)

    // 4. The sovereign universe: self, plus peers unless the data class is device-only.
    val nodes = listOf(s.self) + (if (q.dataClass == DataClass.D0 || !s.meshEnabled) emptyList() else s.peers)
    val universe = nodes.sortedBy { it.node.b64u }.flatMap { n ->
        n.files.filter { f -> serves(f, q, policy, s) }.sortedBy { it.fileSha256 }.map { f -> n to f } }

    // 5. Hard filters, each with a reason code (R01).
    val excluded = mutableListOf<Exclusion>()
    val eligible = universe.filter { (n, f) -> hardFilter(n, f, q, s)?.let { excluded += Exclusion(n.node, f, it); false } ?: true }

    // 6. Estimate and score (R02).
    val scored = eligible.map { (n, f) ->
        val e = estimate(n, f, q, s); val sc = score(n, f, q, e, s)
        Scored(n, f, e, sc, usable = usable(e, q, s.config), probeOnly = n.situation.freshness == Freshness.EXPIRED)
    }

    // 7. Anti-poisoning cap on UNVERIFIED claims (§5.5).
    val (capped, capDelta) = applyUnverifiedCap(scored, s.caps)

    // 8. Merge per policy with the v1 cloud order (R03, §5.4), then truncate.
    val attempts = merge(policy, capped, cloud, q, s).take(s.config.maxAttempts)
    if (attempts.isEmpty()) throw errorFor(policy, universe, excluded, cloudErr)     // §5.6
    return MeshPlan(withReasons(attempts, capped), excluded, capDelta)
}

fun serves(f: FileKey, q: MeshQuery, policy: Policy, s: MeshSnapshot): Boolean =
    if (Policy.fromWire(q.v1.model) == null) f.modelId == q.v1.model                  // concrete id: exact match
    else kindMatches(f, q.op) && rankOk(f, s.config.autoRankFloor)                    // virtual selector: rank floor
```

### 5.3 Hard filters (R01; the first failing row is recorded)

| Code | Applies to | Excluded when | Source |
|---|---|---|---|
| `F1_ELIGIBILITY` | peer | `meshEligibility(q, app, peer, crossOwnerEnabled)` is `Deny` (carries its sub-reason) | `trust.md` §7.3 |
| `F2_NOT_PAIRED` | peer | registry status ≠ `PAIRED` (re-read at plan time) | `trust.md` R2 |
| `F3_NO_SCOPE` | peer | peer did not grant this node `infer` (from `HELLO_ACK.granted`) | `trust.md` §7.1 |
| `F4_MODEL` | all | file not in `held`, or not in `limits.allowedModels`, or flag `numerics-fail` for (file, backend) | `benchmark.md` §3.4 |
| `F5_CONTEXT` | all | `P + N > min(model ctx, node maxContext)` | `trust.md` §7.2 |
| `F6_MEMORY` | all | file not loaded and `availBytes < fileBytes + kvBytesPerToken × (P + N) + 256 MiB` (skipped when EXPIRED) | v2 P2 `MODEL_OOM` guard |
| `F7_BODY` | peer | `B > limits.maxBodyBytes` or `N > limits.maxTokens` | `trust.md` §7.2 |
| `F8_CLAIM` | all | a prior has any rate ≤ 0 or is missing and no class default exists; or `DISCREPANT` **for memory** on this file (`manifest.md` §11.5) | anti-poisoning |
| `F9_AVAILABILITY` | peer | `availability.fsm ≠ SERVING` (skipped when EXPIRED) | `platforms.md` §2.1 |
| `F10_THERMAL` | all | `thermal.code ≥ 3`, or governor `HOLD` (skipped when EXPIRED). SELF with governor `QUEUE` is **not** excluded (v2 P4 matrix decides) | v2 P4; Apple `serious` asks apps to cut networking and compute [RV12] |
| `F11_POWER` | peer | on battery and (`requireCharging` or `batteryPermille < minBatteryPct × 10`), or `saver` (skipped when EXPIRED) | `trust.md` §7.2 |
| `F12_USER_ACTIVE` | peer | `user.active ∧ notWhileUserActive` (skipped when EXPIRED) | `trust.md` §7.2 |
| `F13_METERED` | peer | this node's path to the peer is metered (cellular underlay) and `app.allowMeshOnMetered` is false (default false) | `platforms.md` C8 |
| `F14_BREAKER` | peer | cooling after unreachability or failures (v1 curve) | §8 |
| `F15_DECLINE_BACKOFF` | peer | `now < declineBackoffUntil` (from the peer's `retryAfterMs`) | §8 |
| `F16_EMBED_IDENTITY` | all | `op == EMBEDDINGS` and the file sha differs from the request's embedding identity (§12.3) | numerics consistency |

SELF with no engine is not "excluded"; it is simply absent from the universe, exactly as in v1.

### 5.4 Merging with the cloud tier per policy (R03)

`sov` = the scored sovereign candidates. `cloud` = the v1 plan, **unchanged in content and order**. Total-order tie-break for every sort below: `(primary key, S, tier ordinal SELF < PEER, nodeId, modelId, fileSha256)`; for cloud entries, v1's `CHEAPEST_ORDER` tie-break.

| Policy (wire) | Order of the merged plan | Notes |
|---|---|---|
| `auto` (default) | `[usable sov by S] + [cloud in v1 auto order] + [unusable sov by S]`, with probe-only entries last within each sovereign block | G6. OD-R1 decides whether unusable sovereign candidates go before cloud instead |
| `cheapest` | `[sov by (S2 + S3, then S)] + [cloud in v1 cheapest order]` | Every sovereign candidate costs $0, so all precede priced cloud; among them the least battery and heat wins |
| `fastest` | `merge(sov, cloud) by S1` (cloud S1 from §4.2's cloud estimate; unmeasured cloud last) | The only policy where cloud may precede a sovereign candidate on speed alone |
| `best-reasoning` | `merge(sov, cloud) by (catalogue rank asc, unranked last)`, then S for sovereign and v1 order for cloud; equal rank → sovereign first ($0 is cheapest, v1's tie-break) | A sovereign copy of a lower-ranked model never beats a better-ranked cloud model |
| `local-only` | `[self only]` | Frozen meaning. No peers, no cloud (RL2) |
| `own-devices` (optional, `trust.md` C-6) | `[sov by S]`, no cloud | Error `NO_ELIGIBLE_NODE` when empty |
| any + `X-Asom-Fallback` | `[cloud restricted and ordered by the header]` | Frozen v1 meaning (RL3) |

### 5.5 Anti-poisoning cap on unverified claims

`manifest.md` §11.5 recommends that an `UNVERIFIED` claim may win at most one in four of the placements it would win on score until `n ≥ 3`. Implemented deterministically:

```
for the top sovereign candidate c in the plan (after merging):
    if c.claimState == UNVERIFIED and there exists another USABLE sovereign candidate d:
        k = ClaimKey(c)
        wouldWin[k] += 1                                   // in capDelta, committed only if the plan executes
        if won[k] >= ceilDiv(wouldWin[k], 4):              // wins allowed at would-win counts 1, 5, 9, ...
            swap c with d (the best other usable sovereign candidate); reason += "cap:unverified"
        else:
            won[k] += 1
```

The cap applies **only when another usable sovereign candidate exists**: it moves work between the user's own devices, never from an own device to the cloud. It never changes eligibility (RL12) and is replayable because the counters live in the snapshot.

### 5.6 Error precedence (when the plan is empty, or every attempt fails)

| Situation | Error | Why |
|---|---|---|
| Mesh off, or the sovereign universe was empty | the v1 error unchanged (`MODEL_UNKNOWN`, `NO_PROVIDER_KEY`, `ALL_PROVIDERS_COOLING`, `LOCAL_ENGINE_ABSENT`) | RL1: v1 clients see v1 behaviour |
| `local-only` and self unusable | v2 matrix: queue, then `THERMAL_HOLD` / `MODEL_OOM` / `CONTEXT_OVERFLOW` | Frozen meaning + roadmap v2 |
| `own-devices` and nothing eligible or all attempts failed | `NO_ELIGIBLE_NODE` (RC-6) | New policy, new code |
| Sovereign candidates existed but were all excluded or failed, and the cloud tier had a typed error | the **cloud tier's v1 code**; the ledger row's `routeDetail` lists the sovereign exclusions | The v1 code stays true (e.g. there really is no provider key); no new code for v1-era clients |
| A peer attempt sent a body and every later attempt failed | `ALL_PROVIDERS_COOLING` if cloud candidates were tried and are cooling; else `NO_ELIGIBLE_NODE` | Only reachable with the mesh on, i.e. after the user opted in |

### 5.7 Worked example (generated by `router_ref.py`; all inputs assumed or invented)

Query: streaming chat, 500 prompt tokens (2,000 bytes), `N = 300`, `auto`, deadline 120 s. Requester: a phone on battery (60%), thermal code 1, user holding it. Peers: a Mac on mains, idle, model loaded, state WARM; a Deck on mains with one queued job (est. start 9 s), file not loaded, state STALE, session cold. Inputs are shown **after** the §3.5 substitution (the script takes them as given) and after F1–F16 passed for all three.

```
#1 peer Mac (AC, idle, model loaded): score=10977 usable=True
    estimate: netMs 11, loadMs 0, queueMs 0, prefillMs 1250, ttftMs 1266, decodeMs 6645, totalMs 7911
    terms:    S1 9177, S2 0, S3 0, S4 1000, S5 800, S6 0
#2 peer Deck (AC, busy, not loaded, STALE): score=69623 usable=True
    estimate: netMs 77, loadMs 5028, queueMs 9000, prefillMs 4167, ttftMs 18278, decodeMs 21358, totalMs 39636
    terms:    S1 57914, S2 0, S3 0, S4 1000, S5 800, S6 9909
#3 self (phone, battery 60%, light thermal): score=146167 usable=True
    estimate: prefillMs 16667, ttftMs 16667, decodeMs 59800, totalMs 76467, batteryUsedPermille 7
    terms:    S1 93134, S2 14000, S3 38233, S4 0, S5 800, S6 0
winner=RWLS-SQQ3-SEZA-5MRW runner-up=K7QD-2MXA-PL4E-9TNB dominant term=S1_timeMs (+48737 ms for runner-up)
```

Plan: `[Mac, Deck, self, <cloud in v1 auto order>]` (all three sovereign candidates pass the usability gate, so cloud comes after them). Route reason at the API: `peer:best-score/time`; in the ledger, the full breakdown above. Vectors: `router-examples/R02-worked-example.json`.

---

## 6. Learning: what is allowed, and how the router stays deterministic

**Decision.** The router **may adapt from observed outcomes, deterministically**. It may not explore stochastically, run a bandit, or use a learned model.

| Allowed (all deterministic reducers over the event log, integer arithmetic, bounded state, shown in the UI) | Precedent |
|---|---|
| Integer EWMA of RTT and bandwidth per (peer, path) | v1 `LatencyTracker` (EWMA); `benchmark.md` §10.2 integer rule |
| `ClaimTracker` window medians per (peer, file, backend) (`manifest.md` §11.5) | "claims are priors" (G4) |
| `BenchCalibration` passive lane on self | roadmap v2 P6 "recursive passive benchmarking" |
| Breakers and decline back-off per peer | v1 `CooldownRegistry` |
| Per-app output-length EWMA | v1 heuristic token counts |
| `CloudRateTracker` per provider | v1 `fastest` EWMA |
| Anti-poisoning counters (§5.5) | `manifest.md` §11.5 recommendation |

| Rejected | Why |
|---|---|
| ε-greedy, Thompson sampling or any randomised exploration | A decision cannot be reproduced from the ledger or replayed in the simulator; RL6 (determinism) could not be tested; explaining "why the Deck?" with "a coin flip" defeats the watched-object ethos |
| UCB-style optimism (deterministic, but it sends real prompts to under-observed nodes *to learn*) | Exploration with user content contradicts minimal exposure (G9). Mesh sizes are 2–5 nodes, so the value of exploring is small, and a signed manifest already gives a prior, which is exactly what bandits lack |
| Contextual bandits or any trained model (e.g. predicting latency from features) | Opaque; untestable by laws; v1 §7 "No ML"; a model trained on one user's traces overfits their week |
| Online weight tuning of S-terms | The weights are the user's values (battery vs time), not facts to be learned; changing them silently would make yesterday's decision inexplicable today |

**How underexplored nodes get measured without exploration:** (1) the node benchmarks itself (`benchmark.md`) and its manifest `seq` increases, which restarts its claim window; (2) user-initiated probes (`manifest.md` §11.6, "Check this device's claims"); (3) ordinary placements whenever it genuinely scores best. A node that never scores best is, by construction, not needed.

**How it stays testable.** State = `MeshStateStore`, a persistent value; events = typed records (`AttemptOutcome`, `StateFrame`, `ManifestVerified`, `LinkSample`, `Clock`); `reduce(state, event) → state` is pure; `plan(query, snapshot(state)) → plan` is pure. Laws RL6 and RL21 assert that replaying a recorded event log reproduces every decision exactly.

---

## 7. Composition with the v1 router, the policies and the v2 governors

**The v1 `Router` is not modified.** `MeshRouter` calls `Router.plan(RouteQuery)` for the cloud tier and uses its output verbatim (order, dedup, cooldown skip, typed errors). The existing 38 routing tests and property laws keep holding because the code they test does not change; RL1 adds the stronger statement that the *mesh* router degenerates to v1.

| Request feature | v1 meaning (frozen) | Mesh behaviour |
|---|---|---|
| `model: "<concrete id>"` | providers serving it | self/peers holding a file of that `modelId` (any quant, S5 penalises lower quants) + v1 cloud plan |
| `model: "auto"` etc. (virtual) | all provider models | sovereign files passing `autoRankFloor` (S5 rank penalty) + v1 cloud plan |
| `X-Asom-Policy` | overrides default policy | same, per §5.4 |
| `X-Asom-Fallback` | restricts and orders cloud providers | **cloud only**, exactly v1 (RL3). A future token for "local"/"mesh" in that list is not proposed |
| `X-Asom-No-Train: true` | exclude `trainsOnData` providers | cloud tier only; peers run local engines, which do not train (`trust.md` §10.3) |
| `local-only` | this device only (501 in v1) | this device only; v2 queue-then-typed-error (RL2) |
| per-app `mesh = off` (C-7) | — | `D0`: sovereign set = self only |
| app cloud ban (v2.5) | `auto` never touches cloud | cloud tier removed; sovereign set per `mesh` column |
| v2 governor on self: `QUEUE` | local queues | self stays a candidate with E3 including its queue; peers and cloud compete on score |
| v2 governor on self: `HOLD` / battery floor | `auto` → cloud failover, `X-Asom-Failover: thermal` | self excluded (F10/F11); next candidate may be a **peer** before cloud; header value `thermal` still set (RC-5) |

**Header truth when a peer serves** (`trust.md` §11.4): `X-Asom-Served-By: peer:<nodeTag>/<model as claimed>`, `X-Asom-Egress: lan`, `X-Asom-Node: <nodeTag>`, `X-Asom-Route-Reason: <code>` (§10), all from the same `RouteRecord` as the requester's ledger row.

---

## 8. Execution and failure handling

### 8.1 The attempt state machine (requester side, one attempt)

```
                    ┌──────────── dial fails / TLS pin mismatch ───────────▶ DONE(PEER_UNREACHABLE, bytesOut=0) ─▶ next
PLANNED ─(row IN_FLIGHT durable)─▶ OFFERING ─ INFER_DECLINE ─────────────▶ DONE(<code>, bytesOut=0) ─▶ next (no content left)
                                      │ offerTimeout ─────────────────────▶ CANCEL; DONE(OFFER_TIMEOUT, 0) ─▶ next
                                      │ INFER_ACCEPT
                                      ▼
                                 RE-EVALUATE (§8.4) ─ worse than runner-up by > hysteresis ─▶ CANCEL; DONE(CANCELLED_BEFORE_BODY, 0) ─▶ next
                                      │ keep
                         (row bytesOut=len(body) durable)
                                      ▼
                                 BODY_SENT ─ headTimeout / GOAWAY / reset ──▶ CANCEL; DONE(PEER_LOST_PRE_HEAD, bytesOut=len) ─▶ next (retry allowed)
                                      │ INFER_HEAD
                                      ▼
                    stream: commit echo headers from RouteRecord(view@HEAD)    non-stream: buffer
                                      ▼
                                 RECEIVING ─ INFER_END done ─────────────────▶ DONE(ok) ─▶ return
                                      │ loss / INFER_END thermal|oom|error
                                      ├─ non-stream (nothing delivered) ─────▶ DONE(<terminal>) ─▶ next (retry allowed)
                                      └─ stream (bytes delivered) ───────────▶ DONE(<terminal>); SSE error event; return (no retry)
client disconnect in any state after OFFERING ─▶ CANCEL to peer; DONE(CANCELLED) on both nodes
```

SELF attempts follow the v2 local path (governors, single-flight queue); CLOUD attempts follow v1 `RoutePipeline` semantics for a single candidate (its per-attempt rows are unchanged).

### 8.2 Retry or fail: decision table

| # | Event | Content left this device? | Bytes delivered to the client? | Action | Breaker / back-off | Row status |
|---|---|---|---|---|---|---|
| 1 | Dial or TLS failure | no | no | next candidate | breaker +1 (v1 curve) | `PEER_UNREACHABLE` |
| 2 | `INFER_DECLINE` (`PEER_BUSY`, `PEER_THERMAL`, `PEER_BATTERY`, `PEER_USER_ACTIVE`) | no (offer only) | no | next candidate; update cached state from `st` | back-off until `retryAfterMs` (min 5 s) | the decline code |
| 3 | `INFER_DECLINE MODEL_NOT_OFFERED` | no | no | next | feeds ClaimTracker as a failed observation (`manifest.md` §11.7) | `MODEL_NOT_OFFERED` |
| 4 | `SCOPE_DENIED` / `PEER_NOT_PAIRED` | no | no | next; refresh registry view | exclude until session re-established | code |
| 5 | Offer timeout | no | no | `CANCEL`; next | breaker +1 | `OFFER_TIMEOUT` |
| 6 | Accepted, then re-evaluated worse (§8.4) | no | no | `CANCEL`; next | none | `CANCELLED_BEFORE_BODY` |
| 7 | Body sent, no `INFER_HEAD` in `headTimeoutMs`, or `GOAWAY`/reset | **yes** | no | `CANCEL`; next | breaker +1 | `PEER_LOST_PRE_HEAD` |
| 8 | Non-stream: loss or `terminal ≠ done` before `INFER_END` | yes | no | next | thermal: back-off 60 s; oom: ClaimTracker marks memory claim DISCREPANT; else breaker +1 | terminal code |
| 9 | Stream: loss after `INFER_HEAD` | yes | **yes** | **fail**: send `data: {"error":{"code":"MESH_STREAM_INTERRUPTED","reason":"peer-lost"}}` then close (no `[DONE]`) | breaker +1 | `PEER_LOST_MID_STREAM` |
| 10 | Stream: `INFER_END terminal=thermal` (peer's governor hit HOLD mid-generation) | yes | yes | fail as row 9 with `reason:"peer-thermal"` | back-off 60 s | `PEER_THERMAL_MID_STREAM` |
| 11 | Self governor `HOLD` mid-stream (v2) | no | yes | v2 behaviour (typed error in-band) | — | v2 |
| 12 | Deadline passed | — | — | stop; error per §5.6 | — | — |
| 13 | `DUPLICATE_ATTEMPT` | no | no | bug signal: never retried with the same `attemptId`; next candidate with a new id | — | `DUPLICATE_ATTEMPT` |

**Why rows 7–8 may retry but 9–10 may not.** Before any byte reaches the client, the echo headers are not committed, so the next attempt's `RouteRecord` can truthfully describe what served (Invariant 9). After bytes are delivered, switching nodes would produce an answer from two models (or two samplings) under headers that name one. Transparent continuation is OD-R3.

**Honest cost of rows 7–8.** A retry after row 7 or 8 means the prompt has now been on two devices. Both are eligible own devices, both attempts have rows, and the dashboard shows both. It is still a second exposure.

### 8.3 Avoiding mid-generation holds instead of reacting to them

The offer carries `estTokensIn`, `maxTokens` and `deadlineMs` (`trust.md` §10.5). A conforming provider adds one admission row to `trust.md` §7.3 (between rows 6 and 7):

| # | Check | Fail result |
|---|---|---|
| 6a | predicted run time `prefill + decode` (its own `BenchCalibration`) exceeds its `continuousLoadBudgetMs` remaining (`benchmark.md` §10.6: `onsetMs − busyForMs`) **and** its thermal forecast (`forecastPermille`) ≥ its RUN→QUEUE threshold | `PEER_THERMAL` with `retryAfterMs` = estimated cool-down |
| 6b | `queue estStartMs > offer.deadlineMs` | `PEER_BUSY` with `retryAfterMs = estStartMs` |

The requester's E7 already accounts for throttling; 6a makes the provider refuse work it predicts it cannot finish without a hold. Neither guarantees there will be no hold (the forecast can be wrong).

### 8.4 Accept-time re-evaluation, and no hedging

`INFER_ACCEPT` carries `queuePos` and `estStartMs` (`trust.md` §3.3). On acceptance the requester recomputes the candidate's score with `queueMs = estStartMs` and compares it with the next candidate in the plan. If it is worse by more than `max(1,000 ms, 10%)`, it sends `CANCEL` **before the body** and moves on. Content never left. This handles two requesters converging on the same peer (§9) at the cost of one round trip.

**Hedging (sending the same request to two nodes and taking the first answer) is rejected:** it doubles content exposure and egress rows for a latency gain that accept-time re-evaluation already captures most of. Law RL17 forbids more than one body in flight per request.

### 8.5 Ledgering every attempt on both nodes

Follows `trust.md` §11.2 exactly (write-ahead `IN_FLIGHT` row durable before `INFER_OFFER`; `bytesOut` updated durably before `INFER_BODY`; the server's row durable before the engine starts; linkage only by random `meshAttemptId`). This section adds:

- **One row per attempt**, including declined offers, cancelled-before-body, probe-only offers, and cloud attempts in the same request (v1's audit fix already gives each failed cloud attempt its own row). A request that tried the Deck (declined), the Mac (lost before head) and then the cloud has **three** requester rows and **two** peer-side rows (Deck: declined; Mac: accepted, cancelled).
- Additive nullable `RouteRecord` fields (RC-3): `routeReason` (code), `routeDetail` (the score breakdown of the chosen and runner-up candidates and the exclusion list; ledger only, never a header), `attemptIndex` (0-based within the request), `requestGroupId` (random, **local only**, never sent; `trust.md` §11.5).
- The final response's echo headers come from the **serving** attempt's record; earlier attempts' rows are independent records (the v1 audit fix pattern: a failover attempt that reached a wire is not folded into the final row).

### 8.6 Cancellation

Client disconnect (routine on mobile) → `CANCEL {attemptId, reason:"client"}` → the peer aborts its engine (v2 P3: frees within 1 s) → both rows finalised `CANCELLED`. The requester's row is already durable (write-ahead), so a disconnect cannot lose it (the v1 streaming-row audit lesson).

---

## 9. Fairness and starvation

| Level | Mechanism | Starvation bound |
|---|---|---|
| **Apps on the requester** | v2 P3 single-flight queue with round-robin admission per app, unchanged; the mesh adds capacity, not a new queue | inherited |
| **Requesters on a provider** | The provider's queue has one round-robin partition per requester pin, plus one partition for its **local** apps (the same partitioning as `trust.md` §10.6's KV-cache rule). `localFirst` (default on) serves local partitions first **at admission**, never by pre-empting a running generation | A peer request is never queued beyond its `deadlineMs`: rule 6b declines it up front with `PEER_BUSY` and `retryAfterMs`, so it is rerouted instead of starving silently |
| **Herding** (phone and laptop both pick the idle Dell) | (1) piggybacked `depth` and `estStartMs`; (2) **own reservations**: after placing on P, the requester adds its own outstanding estimated service time to E3 for P until `INFER_END`; (3) accept-time re-evaluation (§8.4) | Each requester sees its own load immediately, and others' within one piggyback |
| **Fan-out jobs vs interactive work** (§12) | Fan-out chunks are offered with `priority: batch` (additive offer field, RC-2); providers admit batch chunks only when no interactive work is queued, and at most one batch chunk at a time | Interactive requests wait at most one chunk's duration (chunks sized ≤ 10 s of work) |
| **Nodes as resources** | Not a fairness object. A slow node that never wins is not "starved"; it is not needed | — |

**What this does not guarantee.** Fairness is per-node and cooperative. A non-conforming provider can ignore `localFirst` or its partitions; the requester can observe only the outcome (queue estimates versus reality feed the ClaimTracker). Across several requesters there is no global scheduler; convergence relies on piggybacked state and re-evaluation, so short bursts of mild herding are possible.

---

## 10. Route reasons

### 10.1 Codes (closed set per version; additive)

```
reason   = tier ":" code [ "/" term ] *( ";" flag )
tier     = "v1" | "self" | "peer" | "cloud"
code     = "policy"            ; v1 path: mesh not involved (mesh off, no sovereign candidate, or X-Asom-Fallback)
         | "only-eligible"     ; exactly one candidate survived the filters (e.g. D0, local-only)
         | "best-score"        ; top of the sovereign block
         | "no-usable-sovereign"   ; auto: cloud because every sovereign candidate failed the usability gate
         | "policy-rank" | "policy-fastest" | "policy-cheapest"   ; cross-tier merge decided by the policy key
         | "failover"          ; attempt index > 0
term     = "time" | "battery" | "heat" | "locality" | "quality" | "uncertainty"   ; dominant term (§10.2)
flag     = "cap:unverified" | "probe" | "stale" | "prev:" <previous attempt's status code>
```

Examples: `peer:best-score/time`, `self:best-score/locality`, `cloud:no-usable-sovereign`, `peer:failover;prev:PEER_BUSY`, `v1:policy`.

### 10.2 The dominant term

For the chosen candidate `w` and the runner-up `r` in the same block: `d_t = r.S_t − w.S_t` for each term `t`; the dominant term is the `t` with the largest `d_t`, ties broken by the order S1…S6. It answers "what mainly made this win?". In the worked example, S1 (time) is dominant by 48,737 ms.

### 10.3 Two renderings from one record (Invariant 9)

- **App-facing:** `X-Asom-Route-Reason: <reason>` (roadmap v2.5's header; mesh codes are additional values, RC-4). It carries the code and dominant term only: **no** peer battery, thermal or user-activity values, which are the user's other devices' situation and none of the calling app's business.
- **Ledger and dashboard:** the same `routeReason` plus `routeDetail`, rendered as plain text, e.g.:
  > Sent to **Mac** (your device, paired) because it was faster: first word in about 1.3 s instead of 16.7 s here, finished in about 8 s instead of 76 s. Running here would have used about 0.7% of this phone's battery while warm in your hand. Next choice was the Deck (busy, 9 s queue). Content left this phone for the Mac only.

Both come from the same `RouteRecord`; the header is a projection of it, not a second computation.

---

## 11. Thin requesters (iOS) and a conflict between sibling sections

**The conflict.** `platforms.md` §0/§6.3 has the iOS requester "delegate routing to a paired home node (H)". `trust.md` R6 says a peer "never sends a peer's request on to the cloud, never forwards it to another peer". If H *routes* an iPhone's request, H either forwards it (violating R6, adding a three-node ledger chain, and using H's BYOK keys for another device, which R6 also forbids) or it does not route at all.

| Option | What H does | R6 | Value | Verdict |
|---|---|---|---|---|
| (a) **H as provider only** | Serves the iPhone's requests with its own local engine. The iOS SDK's `FallbackResolver` tries H, then `CloudOnly` (its own keys) | kept | Most of the value (H is usually the strongest node) with no routing in Swift | **Recommended for S6** |
| (b) **Advisory placement** | New frames `PLACE_REQ`/`PLACE` (scope `place`): the iPhone sends the offer metadata (no content); H runs `MeshRouter.plan` over **the iPhone's** paired peers that H also knows, and returns a ranked list of `nodeId`s with reasons. The iPhone executes the attempts itself (offer/accept/body to each peer directly) and ledgers them itself | kept (H never sees content it does not serve) | The iPhone benefits from H's knowledge of every peer's state without holding sessions to all of them | Recommended later (v4.x), RC-7, owner sign-off |
| (c) **Relay** | H receives the content and forwards it | **violated** | Simplest client | **Rejected**: breaks R6 and "keys never transfer" in spirit; a compromised H sees everything |

The iOS SDK needs only an attempt loop and the retry table (§8.2) under (b); the scorer and all state stay in Kotlin, which preserves `platforms.md`'s bound on the Swift surface. This is OD-R2 because it resolves a disagreement between two sibling sections, and option (b) adds a new frame.

---

## 12. Fan-out of independent work (v4.1)

### 12.1 Scope

Only operations whose parts are independent and whose results are combined by position: `/v1/embeddings` with an array `input` (v4.1), and later a batch surface if the owner ever approves one (none is proposed; OpenAI's batch endpoint would be a new endpoint). Chat is never split.

### 12.2 Algorithm

```
K = number of inputs; nodes = usable sovereign candidates holding EXACTLY file f (same sha256), after §5.3 filters
if K < 64 or |nodes| < 2: whole-request placement (mode 1)
chunk = clamp(ceilDiv(K, 4 * |nodes|), 8, 256)            // >= 4 chunks per node for work stealing
queue = [0..K) split into chunks, in order
each node pulls the next chunk when it is idle (work stealing -> share proportional to real throughput);
    one INFER_OFFER/ACCEPT/BODY/END per chunk, priority: batch; one ledger row per chunk attempt on both nodes
chunk failure (any §8.2 row) -> chunk returns to the FRONT of the queue with its original index; the failed node is excluded
    for this job if it failed twice
all chunks done -> reassemble by index -> one response
sovereign nodes exhausted with chunks left:
    policy allows cloud -> the WHOLE request is re-sent to the cloud tier (content leaves again; rows for each attempt)
    else -> NO_ELIGIBLE_NODE
```

The simulator runs this with virtual time, so chunk scheduling is deterministic under replay.

### 12.3 Numerics and truth

- **Identity:** embeddings are comparable only from the identical model file; F16 enforces the same sha256. Different backends (Metal vs Vulkan vs CPU) on the same file produce slightly different floats [RA9]. Default rule: the same `(fileSha256, backend)` for all chunks of one request; a user setting may relax backend identity for speed. The response and the ledger say which was used.
- **Headers:** one response served by several nodes cannot be described by today's `X-Asom-Served-By: <provider>/<model>`. RC-8 proposes `X-Asom-Served-By: mesh/<model>` and `X-Asom-Node: <tag1>,<tag2>,…` (the list form of `trust.md` C-3), `X-Asom-Egress: lan` if any chunk went to a peer, else `local`. Mixed sovereign + cloud answers are **not** produced (the cloud fallback re-sends the whole request), so the egress header never has to express a mixture.

---

## 13. Simulator and test strategy (pure JVM, buildable before any device exists)

### 13.1 Architecture

```
┌──────────────── test-only: xyz.mdhv.asom.routing.mesh.sim ────────────────┐
│ ScenarioSpec (JSON) ──▶ World: SimNodes, SimLinks, SimCloud, Workload, Faults │
│                                  │  discrete-event engine: PriorityQueue by (tMs, seq)
│  SimTransport (asom-mesh semantics: offer/accept/body/head/chunk/end/cancel, declines per trust.md §7.3)
│  REAL MeshRouter + REAL MeshStateStore reducers + REAL MeshPipeline state machine (§8)
│  InMemoryLedger (asserts write-ahead order: row durable before OFFER, bytesOut before BODY)
│  Oracles: hindsight-best placement; law checkers (§13.7)
└──────────────▶ Metrics JSON + law-violation list (must be empty) ─────────┘
```

- **Determinism:** one `SplittableRandom(seed)` per scenario, split per component in a fixed order; virtual clock only; no threads (the pipeline's suspend points are driven by the event loop); sorted collections in every decision path.
- **The router under test is the production code**, not a model of it. The simulator replaces only the transport, the engine, the clock and the platform probes.

### 13.2 Models

| Component | Model | Parameters from |
|---|---|---|
| Node performance | Per (file, backend): prefill rate, decode rate by context (piecewise linear), TTFT offset, first-order thermal model `T' = (P·R − (T − T_amb))/τ`; decode rate falls linearly from peak to steady between the onset temperature and the throttle temperature; single-flight queue with local-first RR partitions | A manifest file (`asom.manifest/1`), plus a **truth profile** that may differ from the claim (`truthScale`, onset shift) to model lying or stale manifests |
| Power and battery | Capacity (mWh), draw by activity state (idle/prefill/decode), charger schedule; availability FSM (`platforms.md` §2.1) driven by charger, thermal, battery floor, user activity | Class defaults [RA10] or manifest `power.avgMilliW` |
| User activity | Schedules per device (e.g. phone in use 08:00–09:00) | Scenario |
| Links | Per (a, b, path): RTT lognormal (median, σ), bandwidth, session drop as a Poisson process, network-change events | Defaults from [RV4] (Wi-Fi RTT 7.5–30 ms average, 4.9–54 ms range) and [RV2] (320–610 Mbps) |
| Cloud | Per provider: TTFT distribution, decode rate, 429/5xx rates, pricing from `fixtures/catalogue.v1.json` | The committed fixture; never invented URLs |
| Workload | Apps with Poisson arrivals, lognormal prompt and output tokens, stream mix, policy mix, data-class mix, embedding jobs | Scenario |

### 13.3 Fault injection

Scripted, at virtual times: peer vanishes (before head / mid-stream); session drop and network change; thermal spike on a node (e.g. the Deck starts a game); charger unplugged; battery falls through the floor; user picks up the provider phone; manifest lies (claims 2× the truth); manifest stale (engine commit changed); decline storm (`PEER_BUSY` on every offer); STATE frames delayed or dropped; clock skew on a peer (±10 min; must not change any staleness class); duplicate `attemptId`; overlay-only path with high RTT; cellular underlay (metered).

### 13.4 Baselines and metrics

| Baseline | Definition |
|---|---|
| B0 | v1: cloud only (the current router) |
| B1 | local only (v2 engine on self; `local-only` semantics) |
| B2 | static roadmap order: self → first peer holding the model → cloud, no scoring |
| **B3** | this design |
| B4 | hindsight oracle: with perfect knowledge of the truth profiles and future events, the best single placement per request under the same filters (a lower bound for regret) |

Metrics per run: TTFT and total time p50/p95 (per app, per policy); energy consumed per battery node (mWh) and battery ‰ consumed; requests served per tier; cloud egress count, bytes and $ (usage cost from fixture pricing); bytes of content sent to peers; attempts per request; mid-stream interruptions; declines per request; Jain's fairness index across apps and across requesters; maximum queue wait; **law violations (must be 0)**; regret vs B4 (p95 total time).

### 13.5 Scenario suite and the pure-JVM gate

| Id | Scenario | Pass condition (beyond zero law violations) |
|---|---|---|
| SC01 | Phone (battery 60%) + Mac holding the model | ≥ 95% of chat on the Mac; phone battery use < B1's by ≥ 80% |
| SC02 | Phone charging and cool, short prompts, Mac available | Placement flips only when the Mac is better by > `peerBiasMs` (checked against B4 terms) |
| SC03 | Phone + laptop requesters, one Dell | Jain ≥ 0.9 across requesters; no request waits past its deadline without a decline |
| SC04 | Dell vanishes mid-stream | Typed `MESH_STREAM_INTERRUPTED`; both ledgers consistent; no retry after delivered bytes |
| SC05 | Deck enters a game mid-generation (thermal + availability change) | Non-stream requests retried; stream requests interrupted, typed; later requests avoid the Deck within one piggyback |
| SC06 | Lying manifest (2× claim) | The key reaches DISCREPANT; the liar's share of placements falls below B4's share + 10% within 50 requests |
| SC07 | State EXPIRED for all peers (after a network change) | Probe-only offers only; ≤ 1 decline per peer per request; no content to a declining peer |
| SC08 | All peers busy, cloud keys present | `auto` → cloud with `cloud:no-usable-sovereign`; `own-devices` → `NO_ELIGIBLE_NODE` |
| SC09 | D0 app and `local-only` requests mixed in | Zero peer attempts for them (RL2, RL3) |
| SC10 | 10,000-input embeddings over 3 nodes (v4.1) | Throughput ≥ 90% of `Σ rates` in the sim; order preserved; one row per chunk attempt |
| SC11 | Peer reachable only over a cellular (metered) underlay | No peer attempts unless the app allows metered |
| SC12 | 24 h replay with charging schedules and user activity | No battery node driven below its floor by peer work; phone providers never serve while user-active |

**Gate text (brief §12 style):** `./gradlew :core:routing:test` includes the simulator suite; PROGRESS.md gets the real output, labelled **SIMULATED — NOT DEVICE EVIDENCE**. Quality bar: B3 no worse than B2 on p95 total time in at least 10 of 12 scenarios and never worse by more than 10%; zero law violations in all. Device behaviour stays `NEEDS-DEVICE-VALIDATION`.

### 13.6 Replay from real use

- **Decision-audit replay** needs nothing new: the ledger export (the v1 share-sheet path) contains `routeReason`, `routeDetail` and every attempt row, which is enough to re-check each decision's arithmetic against R02.
- **Full-state replay** (snapshots + events) uses a local, opt-in diagnostics trace (content-free: counts, sizes, rates, codes; no prompts, no app ids). It is written only on the device. Whether the app may offer to export it is the same question as `manifest.md` OD-M2 (a new view-first user export under Invariant 1); this section does not assume the answer. On desktop nodes the trace is an ordinary local file.

### 13.7 Laws (property tests; oracles derived from the generated World, never from the router's own key functions, and every law counts the iterations that exercised it — the v1 audit's lessons)

| Law | Statement |
|---|---|
| **RL1** Conservative extension | With no local engine and no surviving peer candidate (mesh disabled, no peers, or all peers filtered), `MeshRouter.plan` returns exactly `Router.plan`'s candidates in the same order, and throws the same typed errors. (R04 vectors apply verbatim.) With a local engine and the mesh disabled, the plan equals the v2 local+cloud plan with no PEER attempt |
| **RL2** `local-only` | Every attempt is SELF. No peer, no cloud, for any snapshot |
| **RL3** Fallback header | With `X-Asom-Fallback`, every attempt is CLOUD and the list equals v1's restricted plan |
| **RL4** Eligibility soundness | Every PEER attempt satisfies `meshEligibility == Allow`, status `PAIRED`, `routeEnabled`, `infer` granted, `dataClass ≤ ceiling`; `D0` ⇒ no PEER attempt |
| **RL5** Hard-filter soundness | No attempt violates any §5.3 row (with the EXPIRED exceptions exactly as specified) |
| **RL6** Determinism | Same (query, snapshot) ⇒ same plan; invariant under permutation of `peers`, of each node's `files`, and of catalogue order |
| **RL7** Total order | No two attempts compare equal under the §5.4 tie-break chain |
| **RL8** Monotonicity (metamorphic) | Improving one input of one candidate (lower RTT, higher rate, shorter queue, more battery, charging, cooler, fresher state) never moves it later; worsening never moves it earlier |
| **RL9** Dominance | If A's every score term ≤ B's and one is strictly less, and both are in the same block, A precedes B |
| **RL10** Policy laws | `cheapest`: every sovereign attempt precedes every priced cloud attempt. `best-reasoning`: non-decreasing rank. `fastest`: non-decreasing S1. `auto`: usable sovereign ≺ cloud ≺ unusable sovereign |
| **RL11** Cap bound | For each UNVERIFIED key, over any sequence of k plans where it would win, it wins at most ⌈k/4⌉; the cap never moves work to the cloud |
| **RL12** Claims never grant eligibility | The set of nodes allowed to receive content (F1–F3, data class, peer class) is a function of policy and the registry only; no manifest or live-state value changes it. Claim- and state-driven rows (F4, F6, F8–F12) can only **remove** candidates: making any claimed value more favourable never removes a candidate, and never adds one that F1–F3 removed |
| **RL13** Staleness | A candidate's position with STALE or EXPIRED state is never earlier than with identical FRESH state; EXPIRED ⇒ `probeOnly` |
| **RL14** Ledger completeness (pipeline, simulator) | Every attempt that sent `INFER_OFFER` has exactly one requester row, written before the offer; every attempt that sent `INFER_BODY` has `bytesOut > 0` durable before the send; every accepted attempt has one server row; rows link one-to-one by `meshAttemptId`; declined attempts have `bytesOut = 0` |
| **RL15** Egress truth | The final `RouteRecord.egress` is the serving attempt's tier (`local`, `lan`, `cloud`); every earlier attempt that reached a wire has its own row with its own egress; echo headers equal the serving record's projection |
| **RL16** Bounded attempts | Attempts ≤ `maxAttempts`; no attempt starts after the deadline; no retry after the first byte is delivered to a streaming client |
| **RL17** No hedging | At most one attempt of a request is in state BODY_SENT or RECEIVING at any time |
| **RL18** Breaker | A failed peer is excluded until its cooldown ends and returns after it (v1 curve) |
| **RL19** Fairness (simulator) | Under saturating load, Jain's index across apps ≥ 0.9 and no peer request waits past its deadline without a decline |
| **RL20** Arithmetic safety | For inputs within §4.2's bounds, no intermediate exceeds `2^53 − 1`; every division has a positive divisor |
| **RL21** Replay | Replaying a recorded event log through the reducers and `plan` reproduces every recorded decision |
| **RL22** Content minimisation | No content is sent to a candidate whose offer was declined, timed out, or cancelled before body |

### 13.8 Vector families

| Family | Contents | Status |
|---|---|---|
| **R01 hard filter** | (query, node view) → excluded with code, for every §5.3 row, including the EXPIRED exceptions | proposed |
| **R02 scoring** | (query, candidate) → E0–E12 and S1–S6 exactly (integers), incl. zero, typical, saturating values; `R02-worked-example.json` seeds three | illustrative seeds |
| **R03 ordering** | (query, scored set, v1 cloud plan, policy) → merged list; ties; permutations | proposed |
| **R04 v1 pins** | Frozen v1 behaviour as data (`platforms.md` §8.4); also RL1's oracle | **writable now** |
| **R05 failover** *(new; needs the `platforms.md` §8.3 family regex extended to `R0[1-6]`)* | (attempt state, event) → action, row status, breaker change; every §8.2 row | proposed |
| **R06 reducers** *(new)* | (estimator state, event sequence) → state: integer EWMA, cap counters, freshness classes | proposed |
| **W07 live state** | `asom.state/1` parse accept/reject; `st` digests; staleness class at `nowMs`, incl. peer clock skew (must not matter) | proposed |

---

## 14. Proposed contract deltas (all additive; every item needs owner sign-off)

| ID | Delta | Notes |
|---|---|---|
| RC-1 | Payloads of `STATE_REQ`/`STATE` (frames 0x20/0x21, reserved by `trust.md` C-1): `asom.state/1` (Appendix A), watch semantics and rate limits (§3.4) | Peer protocol only; no HTTP surface |
| RC-2 | Optional members added to existing `trust.md` frame payloads: `st` digest on `HELLO_ACK`, `INFER_ACCEPT`, `INFER_DECLINE`, `INFER_END`; `decideUs` on `INFER_ACCEPT`/`INFER_DECLINE`; `priority: interactive\|batch` on `INFER_OFFER` | Additive JSON members |
| RC-3 | `RouteRecord` / `route_log` nullable fields `routeReason`, `routeDetail` (ledger only), `attemptIndex`, `requestGroupId` (local only, never sent) | Alongside `trust.md` C-4 |
| RC-4 | Mesh values for `X-Asom-Route-Reason` (the header itself is roadmap v2.5's delta) per §10.1 | Header must be built from `RouteRecord.routeReason` |
| RC-5 | Additional values for `X-Asom-Failover` (roadmap v2's header): `peer-declined`, `peer-unreachable`, `peer-lost`, `peer-thermal` | Set when the serving attempt is not the plan's first |
| RC-6 | Error codes: `NO_ELIGIBLE_NODE` (shared with `trust.md` C-6) and `MESH_STREAM_INTERRUPTED` (in-band SSE error event only; the HTTP status was already committed) | Only reachable once the user enabled the mesh |
| RC-7 | *(optional, OD-R2 (b))* frames `PLACE_REQ`/`PLACE` (0x24/0x25) and scope `place` for advisory placement to thin requesters | Metadata only; no content |
| RC-8 | *(v4.1)* fan-out headers: `X-Asom-Served-By: mesh/<model>`, list form of `X-Asom-Node` | Requires `trust.md` C-3 |
| RC-9 | Router **reads** `max_tokens` / `max_completion_tokens` (read-only, body still verbatim) to cap E0 | Changes brief §5.9's statement of what the router parses (OD-R5) |
| RC-10 | Conformance families R01–R06 and W07 in `conformance/` | Spec artefacts; `status: proposed` until the above are signed off |

Not proposed: any new `/v1/*` or `/admin/*` endpoint; any change to v1 policies, error codes or header meanings; a batch endpoint.

---

## 15. Owner decisions

| ID | Question | Options | Recommendation | Why only the owner |
|---|---|---|---|---|
| **OD-R1** | What does `auto` mean once the mesh exists? Roadmap §7's literal order is "this device → LAN node holding the model → cloud" | (a) strict literal order, no scoring; (b) scored sovereign block (a peer may outrank this device when better by `peerBiasMs`), sovereign always before cloud; (c) (b) plus a usability gate: sovereign candidates too slow for the gate go **after** cloud; apps that must never use cloud use `own-devices` or the v2.5 cloud ban | **(c)** with the §4.4 defaults, and a per-user switch "never use the cloud when one of my devices can answer, however slowly" that turns (c) into (b) | It changes the product's default egress behaviour (when the cloud sees a prompt) and deviates from a roadmap sentence |
| **OD-R2** | How does a thin iOS requester use the mesh, given `platforms.md`'s "delegated routing" and `trust.md` R6 "never forward"? | (a) H as provider only; (b) advisory placement frames (RC-7); (c) relay through H | **(a) at S6, (b) later; reject (c)** | Resolves a disagreement between two sibling sections and adds a frame |
| **OD-R3** | What happens when a peer is lost mid-stream? | (a) terminate with a typed in-band error (client retries); (b) continuation on another node holding the identical file (send prompt + partial output, v4.x experiment); (c) hedged duplicate streams | **(a)** now; (b) as a later experiment behind a setting; **reject (c)** | (b) produces one answer from two samplings under headers naming one node, a truthfulness question about the watched object |
| **OD-R4** | Accept the ranking in §1.3 and its version tiers? In particular, keep capacity sharding parked | (a) as §1.3 (sharding parked; at most an owner-gated design spike for AC desktops, never phones, never llama.cpp RPC as is); (b) schedule a sharding "capacity mode" in v4.x; (c) never | **(a)** | Roadmap §7 parks sharding; changing that is the owner's roadmap call |
| **OD-R5** | May the router read `max_tokens` (RC-9)? | (a) yes, read-only cap; (b) no, per-app EWMA only | **(a)**; the body stays verbatim | Brief §5.9 (frozen) says what the router parses |
| **OD-R6** | May the pure-JVM pieces (§18) be built now, ahead of v4's entry criteria? | (a) no; (b) yes, in a scratch or test-only module outside the shipped settings; (c) yes, in shipped modules | **(b)**, together with `platforms.md` OD4(b) | CLAUDE.md forbids starting later versions |

No **third invariant amendment** is needed by this section beyond what `trust.md` OD-1 already escalates (the `lan` egress class and the Invariant 1 clarification for control-plane state). If OD-1 is decided as "(b) Amendment 2 limited to Invariant 2", then the live-state exchange defined here falls under the escalated third amendment together with `trust.md`'s items.

---

## 16. Invariant impacts

| Invariant | Status | Detail |
|---|---|---|
| 1 No automatic egress | needs-amendment (via `trust.md` OD-1) | `STATE` frames are control-plane messages to PAIRED own peers with the `state` scope, enumerated fields only, no content or usage. They are exactly what `trust.md` §12.2's "Invariant 1 (clarification)" covers. They reveal the user's *situation* (battery, user-active, thermal) to their own other devices; other-class peers get only `fsm`. No new destination, no upload. The Peers tab shows the last `STATE` sent to each peer |
| 2 Bind 127.0.0.1 only | honored | The router binds nothing. The peer listener is `trust.md`'s Amendment 2 |
| 3 Egress classes | needs-amendment (via `trust.md` C-2 / OD-1) | Peer attempts are egress class `lan`; every attempt, declined offer and probe has a row (G10) |
| 4 BYOK keys | honored | Peers never use their keys for others (R6); the iOS thin requester uses its own `CloudOnly` keys (OD-R2 (a)/(b)); no key or key presence appears in state, reasons or plans |
| 5 Pairing identity | honored | Router consumes `trust.md` registry status per plan; no HTTP registration |
| 6 Red/green never carry meaning | honored | Peers tab and reason renderings use text and shape; no colour semantics are introduced here |
| 7 Placeholder UI | honored | Plain-text renderings only |
| 8 No GMS | honored | Probes use framework APIs (`PowerManager`, `BatteryManager`, `ConnectivityManager`) |
| 9 One `RouteRecord` | honored | Echo headers (incl. `X-Asom-Route-Reason`, `X-Asom-Node`) and the ledger row come from the serving attempt's record; each earlier attempt has its own record |
| CLAUDE.md "no KMP" | honored | Router stays Kotlin; Swift gets no router (OD-R2) |
| CLAUDE.md "do not start later versions" | honored in this document | Build-now items (§18) need OD-R6 |
| Frozen contract §5 | honored with additive deltas | v1 meanings of `local-only`, `X-Asom-Fallback`, `X-Asom-No-Train` and every v1 error are unchanged (RL1–RL3); RC-9 touches §5.9's wording (OD-R5) |

---

## 17. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| **Sim-to-real gap**: the simulator's thermal, radio and scheduling models are wrong, so tuned defaults misbehave on devices | high | Laws are parameter-independent; defaults are marked assumptions; device checklists stay `NEEDS-DEVICE-VALIDATION`; decision-audit replay from ledger exports (§13.6) |
| **Mis-estimation from lying or stale claims** attracts work to a weak peer | medium | ClaimTracker (observations replace claims after 3 samples); anti-poisoning cap; `disc` factors; accept-time re-evaluation; eligibility unaffected by claims (RL12) |
| **User surprise at cloud use** under OD-R1 (c) when own devices are slow | medium | Route reason `cloud:no-usable-sovereign` on every such response; per-user "never cloud when a device can answer" switch; `own-devices` policy |
| **Mid-stream interruptions** annoy users on flaky Wi-Fi | medium | Admission rule 6a/6b; prefer wired/AC nodes via S1–S3; typed error so apps can retry; continuation parked (OD-R3) |
| **Herding** between several requesters | medium | Piggybacked queue state, own reservations, accept-time re-evaluation |
| **Privacy of the user's situation** (battery, user-active) shared with own devices | low–medium | Own class only; enumerated fields; shown in Peers tab; other class gets `fsm` only |
| **Battery cost of the protocol** is larger than expected on some phones [RA5] | low–medium | Zero-idle design; mains-only watches; measurable in SC12 once the radio model is calibrated on a device |
| **Snapshot mutation during planning** makes decisions irreproducible | medium | Immutable snapshot values; RL6/RL21 in CI |
| **Parameter sprawl** (§4.4) becomes unexplainable | medium | Every term appears in the plain-text reason; parameters are few, named and unit-bearing; laws hold for any values |
| **Over-promising "distributed compute"** in UI copy | medium | §1.3 wording is normative for docs and the Peers tab |

---

## 18. What could be built now (pure JVM, no contract change, no Android; only with OD-R6)

1. **R04 vectors** pinning the frozen v1 `Router` (also RL1's oracle).
2. `asom.state/1` DTOs, strict parser, `st` digest, and the staleness classifier with W07 vectors (in a scratch `:core:mesh`).
3. The integer estimator and scorer (§4.2–§4.3) as pure functions, cross-checked against `router_ref.py` (a second implementation, so the JVM is not the only oracle).
4. `MeshRouter.plan` with filters, merge, cap and reasons, behind the §2.3 interfaces, calling the unmodified v1 `Router`.
5. `MeshStateStore` reducers (integer EWMA, breakers reused from `CooldownRegistry`, cap counters) with RL21 replay tests.
6. The simulator (§13) as a test-only source set, the scenario suite SC01–SC12, baselines B0–B4, and laws RL1–RL22.
7. The `MeshPipeline` attempt state machine against `SimTransport` only (no sockets), with RL14–RL17.

None of these needs a listener, a key, an Android API or a contract change; all run in `jvmTest` on a bare JDK.

---

## 19. What this section does NOT guarantee

- **Estimates are not measurements.** Every placement rests on claims and on past observations; the first request after a change (new model, new engine commit, a game starting) can be misplaced. The retry table bounds the cost; it does not remove it.
- **Scores are value judgements.** The battery, heat and locality terms encode default preferences, not facts. A different user may rightly prefer different weights.
- **Distribution is not speed-up of one answer.** §1: whole-request placement and fan-out of independent work are the only modes offered; a model that fits nowhere is not made fast by the mesh.
- **The ClaimTracker detects claims not borne out, not honesty.** A peer that is slow *and* claims to be slow is indistinguishable from an honest slow peer; a peer that is fast and mishandles content is not detected at all (`trust.md` §9.1).
- **Fairness is cooperative.** A non-conforming provider can ignore partitions and `localFirst`.
- **Staleness classes bound the age of what the requester knows, not the truth of it.** A FRESH state from a lying peer is still a lie.
- **No mid-stream continuity.** A peer lost after the first byte ends the stream with a typed error.
- **Content minimisation is per attempt.** A retry after a lost peer means the prompt was on two devices, both eligible, both ledgered.
- **Route reasons explain the router's arithmetic, not the world.** "Faster" means "estimated faster from these inputs".
- **The simulator proves laws and relative behaviour under its own models.** It is not evidence about any device; its results are labelled SIMULATED.
- **The §1 benefit figures are estimates** built from the stated assumptions and a few verified reference points; they will be wrong for any particular user's hardware.

---

## 20. Verified facts and assumptions

### 20.1 Verified this session

| ID | Fact | Source | Confidence |
|---|---|---|---|
| RV1 | llama.cpp RPC distributes weights and KV cache across local and remote devices "in proportion to each device's available memory"; the feature is a "proof of concept", "fragile and insecure"; "Never run the RPC server on an open network or in a sensitive environment!"; optional RDMA (RoCEv2 on Linux, Thunderbolt 5 on macOS) with TCP fallback | github.com/ggml-org/llama.cpp, tools/rpc/README.md (fetched 2026-09-29) | high |
| RV2 | prima.cpp: 4-device home cluster (Mac M1, two Linux GPU desktops, Android phone) on Wi-Fi at 320–610 Mbps and 3–7 ms; for models < 14B its scheduler runs only on the strongest device with the same TPOT/TTFT as llama.cpp; advantages start at 30B+ where no device holds the model; 70B at 674 ms/token; "Due to Wi-Fi's high latency, pipeline parallelism becomes more suitable" | arXiv 2504.08791 (html), fetched | high |
| RV3 | exo: single M4 Pro 49.3 tok/s; three M4 Pro single-request 39.7 tok/s; concurrent 108.8 tok/s (2.2×); bottleneck is latency, not bandwidth | blog.exolabs.net/day-1 (2024-12-01) | high |
| RV4 | exo issue #2295 (2026-09-02): 4× M3 Ultra, 4-way pipeline, single request 25.4 tok/s over Thunderbolt 5 RDMA vs 8.7 tok/s over Wi-Fi; 16 concurrent 69.1 vs 32.8; Wi-Fi RTT 4.9–54 ms (7.5–30 ms average), Thunderbolt bridge 0.38–0.95 ms | github.com/exo-explore/exo/issues/2295 | high for the report; single user's setup |
| RV5 | exo 1.0 supports RDMA over Thunderbolt 5 (macOS 26.2); reported tensor-parallel speed-ups up to 1.8× (2 Macs) and 3.2× (4 Macs) | exo announcements and Jeff Geerling's write-up, via search results | medium (vendor-reported; not fetched in full) |
| RV6 | llama.cpp speculative decoding supports draft-model, EAGLE-3, MTP and several n-gram modes within one server (`--spec-type`, `-md`, `--spec-draft-n-max` default 3) | github.com/ggml-org/llama.cpp docs/speculative.md | high |
| RV7 | SLED: edge devices draft, a shared edge server verifies with batching; reported 2.6–2.9× system capacity vs centralised edge serving | arXiv 2506.09397; SEC '25 (via search summary) | medium-high |
| RV8 | Qwen3-8B: 36 layers, 8 KV heads, 32 attention heads, head dim 128, hidden 4096, bf16 | huggingface.co/Qwen/Qwen3-8B config.json | high |
| RV9 | llama.cpp on Apple silicon, LLaMA 7B Q4_0 (PP512 / TG128 tok/s): M1 8-GPU 117.96 / 14.15; M4 Pro 439.78 / 50.74; M4 Max 885.68 / 83.06; M2 Ultra 1238.48 / 94.27 | github.com/ggml-org/llama.cpp discussions/4167 | high |
| RV10 | Android `PowerManager.getThermalHeadroom(forecastSeconds 0–60)`: 1.0 = SEVERE throttling; "no benefit to calling this function more frequently than about once per second"; may return NaN if called faster; forecasting needs several samples; a headroom listener reports only significant changes; thermal status constants NONE…SHUTDOWN | AOSP `PowerManager.java` (copy in the session's research folder) | medium-high (source branch not identified) |
| RV11 | `BatteryManager`: `BATTERY_PROPERTY_CURRENT_NOW` (µA, positive into the battery), `CHARGE_COUNTER` (µAh), `CAPACITY` (%), `ENERGY_COUNTER` (nWh) | AOSP `BatteryManager.java` (same folder) | high |
| RV12 | Apple `ProcessInfo.ThermalState`: nominal, fair ("reduce or defer background work, like prefetching content over the network"), serious ("reduce or defer I/O operations, such as networking"; reduces performance), critical | Apple documentation JSON (same folder) | high |
| RV13 | `os_proc_available_memory()` returns the bytes the app may allocate before hitting its memory limit | Apple documentation JSON (same folder) | high |
| RV14 | `PowerManager.isInteractive()`, `isPowerSaveMode()`, `isDeviceIdleMode()` exist | AOSP `PowerManager.java` | high |

Computed this session (not facts about the world, but checkable arithmetic): every figure tagged `router_ref.out` in §1 and §5.7, produced by `router-examples/router_ref.py`.

### 20.2 Assumptions (not verified)

| ID | Assumption | Load-bearing? |
|---|---|---|
| RA1 | A flagship phone decodes an 8B Q4 model at about 5 tok/s (roadmap §1 planning assumption, Jan-2026-era) | Only for §1's benefit estimate |
| RA2 | Phone prefill for 8B is about 30 tok/s | Only for §1 |
| RA3 | An 8B Q4_K_M model runs about 10% slower than LLaMA 7B Q4_0 on the same Mac | Only for §1 |
| RA4 | Phone inference draws about 5 W; a typical battery is about 15.4 Wh | S2 defaults; §1 |
| RA5 | Frequent small Wi-Fi exchanges keep phone radios out of power save and cost measurable energy; per-exchange energy is unknown | Motivates §3.6; the design is safe if it is wrong |
| RA6 | A mesh TLS handshake costs about 2 RTT + 40 ms on phones | E1 |
| RA7 | Speculative-decoding parameters in `router_ref.py` (acceptance 0.6–0.8, draft and verify timings) | Mode 4 verdict (robust to reasonable variation; the verdict rests on the structure `k × Δt_draft vs RTT`) |
| RA8 | KV-cache state is not portable across engines/backends without conversion | Only strengthens the mode-3 rejection |
| RA9 | The same GGUF on different backends yields slightly different embedding floats | §12.3 default of backend identity |
| RA10 | Class-default power figures (§4.4) | S2 when unmeasured |
| RA11 | Cloud decode prior of 50 tok/s | `fastest` before observations |
| RA12 | All §4.4 defaults are starting guesses | Tuned in the simulator, then on devices |
| RA13 | Pipeline activations can leak prompt content to shard nodes | Mode-5 privacy note |
| RA14 | A relayed overlay path (e.g. DERP) has much higher RTT than direct LAN | Per-path link stats |
| RA15 | Model-load throughput defaults | E2 until `benchmark.md` measures load |
| RA16 | An unprivileged desktop node can read a user-activity signal (logind `IdleHint` on Linux; HID idle time through the macOS shim) | F12 on desktops only; if absent, desktops report `user.active: null` and F12 does not apply |

---

## Appendix A — `asom.state/1` (JSON Schema, draft 2020-12; producer-strict form)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "asom:state/1",
  "type": "object",
  "additionalProperties": false,
  "required": ["v", "seq", "sampledAgeMs", "availability"],
  "properties": {
    "v": { "const": 1 },
    "seq": { "type": "integer", "minimum": 1, "maximum": 9007199254740991 },
    "sampledAgeMs": { "type": "integer", "minimum": 0, "maximum": 60000 },
    "end": { "const": true },
    "availability": {
      "type": "object", "additionalProperties": false, "required": ["fsm"],
      "properties": {
        "mode": { "enum": ["always", "when-charging", "foreground-only", "requester-only"] },
        "fsm": { "enum": ["OFF", "ARMED", "SERVING", "DRAINING"] },
        "reason": { "type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,31}$" }
      }
    },
    "power": {
      "type": "object", "additionalProperties": false, "required": ["source", "charging"],
      "properties": {
        "source": { "enum": ["ac", "battery", "unknown"] },
        "charging": { "type": "boolean" },
        "batteryPermille": { "type": ["integer", "null"], "minimum": 0, "maximum": 1000 },
        "saver": { "type": "boolean" }
      }
    },
    "thermal": {
      "type": "object", "additionalProperties": false, "required": ["code", "governor"],
      "properties": {
        "code": { "type": "integer", "minimum": 0, "maximum": 4 },
        "headroomPermille": { "type": ["integer", "null"], "minimum": 0, "maximum": 10000 },
        "forecastPermille": { "type": ["integer", "null"], "minimum": 0, "maximum": 10000 },
        "forecastSec": { "type": ["integer", "null"], "minimum": 0, "maximum": 60 },
        "governor": { "enum": ["RUN", "QUEUE", "HOLD"] },
        "busyForMs": { "type": "integer", "minimum": 0 }
      }
    },
    "memory": {
      "type": "object", "additionalProperties": false,
      "properties": { "availBytes": { "type": ["integer", "null"], "minimum": 0 } }
    },
    "engine": {
      "type": "object", "additionalProperties": false, "required": ["backend", "commit"],
      "properties": {
        "backend": { "type": "string", "pattern": "^[a-z0-9][a-z0-9._+-]{0,63}$" },
        "commit": { "type": "string", "pattern": "^[0-9a-f]{7,40}$" },
        "confVersion": { "type": "string", "pattern": "^[0-9]+\\.[0-9]+\\.[0-9]+$" },
        "loaded": {
          "type": "array", "maxItems": 8,
          "items": {
            "type": "object", "additionalProperties": false, "required": ["fileSha256"],
            "properties": {
              "fileSha256": { "type": "string", "pattern": "^[0-9a-f]{64}$" },
              "ctxTokens": { "type": "integer", "minimum": 1 },
              "kvFreeTokens": { "type": "integer", "minimum": 0 }
            }
          }
        },
        "held": { "type": "array", "maxItems": 64, "items": { "type": "string", "pattern": "^[0-9a-f]{64}$" } }
      }
    },
    "queue": {
      "type": "object", "additionalProperties": false, "required": ["inflight", "maxConcurrent", "depth", "estStartMs"],
      "properties": {
        "inflight": { "type": "integer", "minimum": 0 },
        "maxConcurrent": { "type": "integer", "minimum": 0 },
        "depth": { "type": "integer", "minimum": 0 },
        "estStartMs": { "type": "integer", "minimum": 0 },
        "localActive": { "type": "boolean" }
      }
    },
    "user": { "type": "object", "additionalProperties": false, "properties": { "active": { "type": ["boolean", "null"] } } },
    "net": { "type": "object", "additionalProperties": false, "properties": { "metered": { "type": "boolean" } } },
    "manifest": {
      "type": "object", "additionalProperties": false, "required": ["seq", "bodyDigest"],
      "properties": {
        "seq": { "type": "integer", "minimum": 1 },
        "bodyDigest": { "type": "string", "pattern": "^[A-Za-z0-9_-]{43}$" }
      }
    },
    "watch": { "enum": ["none", "granted", "refused-battery", "refused-limit"] }
  }
}
```

Consumers validate tolerantly (every `additionalProperties: false` relaxed to `true`, as `manifest.md` §4.3), ignore unknown members, and render unknown enum values as "unknown" in logic. The `st` digest (§3.4) is a projection of this object with short names and the same value rules.
