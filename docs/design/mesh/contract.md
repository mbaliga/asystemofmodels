# Contract, invariants, ledger, privacy and roadmap reconciliation

**Section of:** the ASOM multi-device ("mesh") design session, 2026-09-29.
**Status:** DESIGN PROPOSAL. Nothing here is approved, frozen or executable. v1 device validation is still open (`PROGRESS.md`, "v1 build status"), and roadmap versions are strictly sequential with entry criteria (roadmap §0, §2). Every contract addition and every invariant change below needs owner sign-off, and several are owner decisions in their own right (§8).
**Siblings:** `platforms.md` (roles per OS, code strategy, conformance suite), `trust.md` (node identity, pairing, `asom-mesh/1`, scopes, revocation), `manifest.md` (signed capability manifest, attestation, subscribers), `benchmark.md` (benchmark core and shells), `router.md` (placement, live state, simulator; not yet written when this section was drafted, so references to it are to its assignment).
**This section owns:** how the owner's three requirements map onto the frozen v1 brief and the roadmap; the complete list of invariant changes with exact wording and a third-amendment judgment for each; the single contract delta registry (every sibling proposal is admitted, deferred, rejected or withdrawn here); the cross-node ledger; the privacy and threat analysis for sending prompts to other devices, including the data-classification model; the phased plan; the risk register; the consolidated owner decisions.

Tags: **[CVn]** fact verified in this session (sources in §9.1). **[CAn]** assumption, not verified (§9.2). Sibling facts keep their own tags (for example `platforms.md` [V01], `trust.md` [V1]) and are relied on, not re-verified. **[repo]** means read directly from the repository at `f51dae9`.

---

## 0. The section in brief

- **Most of what the owner asked for is already on the roadmap, but at the wrong resolution, and in three places the roadmap says the opposite.** The v4 "Literal Hotspot" already plans desktop nodes, QR pairing, capability exchange, node-aware placement and the Invariant 2 amendment. v2 P6/P7 already plan passive benchmarking and an anonymous upload. What contradicts the written roadmap: a *separate* benchmark app (v2 P6 says it is "a feature, not a separate app"), iOS and macOS (never mentioned; the v1 brief is "a daemon for Android"), and any reading of "distribute work" that splits one model or one request across devices (v4: "whole-model placement only"). Genuinely new: signed, verifiable manifests for arbitrary subscribers; active benchmarking; a live-state protocol; a data-classification model for peers; iOS in any form.
- **The roadmap is internally inconsistent in two places that this design cannot avoid** (§1.4). Roadmap §7 says "No other invariant changes" while its own delta registry adds egress class `lan`, which Invariant 3 declares exhaustive. Roadmap v2 P7 adds an upload whose network egress has no Invariant 3 class. Both need owner text, whatever the mesh does.
- **Invariant changes (§2).** One package fits the sanctioned v4 Amendment 2 (Invariant 2 text, a new Invariant 3 class, an Invariant 1 clause for peer control messages, an Invariant 5 clarification). One package completes the sanctioned v2 Amendment 1 (the missing Invariant 3 class for the upload; user-directed export of a benchmark report). **One package is a genuine third amendment and must be escalated**: platform equivalence for Invariants 4, 5, 7 and 8 on macOS, Linux and iOS. The first mesh release is scoped so that it does not need the third package at all; iOS in any form does.
- **Contract (§3): no new app-facing endpoint, request header or error code in the first mesh release.** Two new values inside existing response headers (`X-Asom-Egress: peer`; `X-Asom-Served-By: peer:<nodeTag>/<model>`), one `owned_by` value in `/v1/models`, additive ledger fields, and the new peer protocol. The roadmap's provisional `X-Asom-Node` header is withdrawn for the first release; sibling proposals for `own-devices`, `NO_ELIGIBLE_NODE`, `/admin/manifest`, locator hints, revocation hints and mDNS are deferred or rejected, with reasons.
- **Egress class `peer`, not `lan` (§4.6).** An overlay path can be relayed through Tailscale-operated DERP servers in other countries when a direct path fails [CV8]. A row that says `lan` for that transmission states something false, which is exactly the audit's lesson. The class is `peer` with a `peerPath: lan | overlay` field taken from the socket's interface.
- **Ledger (§4): append-only intent and outcome rows, per attempt, on each node that handles content.** The requester's intent row is durable before the first byte of the attempt leaves; the provider's intent row is durable before its engine touches the body; each outcome row is durable before the response completes. The two nodes' rows are linked only by a random 128-bit `attemptId`; the logical request id never leaves the device. Reading the v1 code for this design surfaced a residual v1 gap: v1 writes a cloud row only *after* the upstream call returns, so a process death during an in-flight upstream call leaves no row for a transmission that happened [repo]. The same intent-row mechanism closes it; it is proposed as v1.1 hardening.
- **Privacy (§5).** A request carries a *permitted destination set* drawn from {this device, own peer, other-owner peer, cloud}, computed as the intersection of every applicable rule; the most restrictive rule always wins. Other-owner peers stay disabled (stop-line). Providers never persist peer content and keep no KV or prefix cache across peer attempts, because shared prompt caches are a demonstrated timing side channel [CV6]. A **quiescence law** means a requester makes no peer connection while nothing local needs one.
- **Plan (§6).** Seven pure-JVM "lab" deliverables can be built now without touching the frozen contract, the shipped modules or any Android code, if the owner authorises them as an explicit exception to "do not start later versions". Everything that measures, serves or ships waits for v1 device validation and then v2. The first mesh release ("mesh-1": Linux and Deck providers, Android requester, own devices, overlay-first) is proposed to enter after v2 instead of after v3; that re-sequencing is an owner decision.

### 0.1 Governance rules for this section

| # | Rule | Source |
|---|---|---|
| G1 | Nothing silently overrides a frozen decision. Every change names the frozen text it touches and quotes it. | Brief §5, §12; roadmap §13 |
| G2 | Additive only. Frozen v1 §5 is untouched. Every addition is owner-signed before it ships. | Brief §5 header |
| G3 | Every invariant change is classified by the test in §2.1. Anything not clearly inside a sanctioned amendment's anticipated substance is escalated as a third, never assumed. | Roadmap §13 |
| G4 | A stated class must be true: egress classes, echo headers, error codes and row statuses say what happened, not what was intended. | `PROGRESS.md` audit |
| G5 | Every attempt that reaches a wire has its own rows, and content never moves before the intent row is durable. | `PROGRESS.md` audit; Invariant 3 |
| G6 | A delta enters the registry only if a proposed phase needs it and no existing surface can express it truthfully. | Brief §13 |
| G7 | Specification (vectors) before a second implementation. | `platforms.md` §8 |
| G8 | Nothing ships before its version's entry criteria. Pre-work is non-shipping, isolated and owner-authorised. | CLAUDE.md; roadmap §0 |

---

## 1. Roadmap reconciliation

### 1.1 The requirements, decomposed

| ID | Owner requirement (intent) |
|---|---|
| R1a | ASOM installable on Linux, macOS and iOS as well as Android |
| R1b | Each installed device can offer its compute to the others |
| R1c | A way to connect the devices |
| R1d | The requesting device understands every other device's capabilities, collected by benchmarking |
| R1e | The requester intelligently distributes work between them |
| R2a | A router that interfaces with each device to learn its capabilities |
| R2b | ... and its current situation |
| R2c | ... and routes intelligently |
| R3a | A benchmarking utility |
| R3b | That can run as an independent app if required |
| R3c | That explains results to users in plain text |
| R3d | That emits a manifest JSON that counterparts and any subscribing app or device can consume |
| R3e | That lets the subscriber verify the report was not tampered with |

### 1.2 Mapping onto the roadmap

Verdicts: **PLANNED** (the roadmap already decided it), **EXTENDS** (compatible with roadmap text, adds detail or scope), **CONTRADICTS** (conflicts with roadmap or brief text as written), **NEW** (the roadmap is silent).

| ID | Roadmap today (quoted) | Verdict | Resolution |
|---|---|---|---|
| R1a Linux / Deck | v4: "**asom-desktop.** ... `:server` + `:core:*` packaged as a desktop node (CLI shell first, UI later). Runs on the Dell/Deck." | PLANNED (v4) | `platforms.md` S1; mesh-1 scope (§6) |
| R1a macOS | Not mentioned anywhere | NEW | Same JVM artifact plus packaging (`platforms.md` S3). Needs platform wording for Invariant 8 only if it ships a GUI or Apple services (§2, IC-7) |
| R1a iOS | Not mentioned. Brief §0: "a **sovereign model-routing daemon for Android**"; §10A tiers assume a localhost daemon | CONTRADICTS the product shape (no cross-app daemon is possible on iOS, `platforms.md` [V01]) and NEW | iOS starts as a standalone benchmark app (R3b); requester and provider roles are separate owner decisions (OD-C8; `platforms.md` OD5). Needs a third amendment (IC-7) |
| R1b | v4: "asom nodes on the phone, the Steam Deck, and the Dell serve each other over a **private overlay only**" | PLANNED (v4) | mesh-1 narrows it: desktops provide, the phone only requests. The phone as provider follows later (§6, M2) |
| R1c | v4: "QR-based: one node displays URL + token + cert fingerprint; the other scans. Static peer registry — no mDNS, no open discovery." Amendment draft: "per-device tokens required" | PLANNED; EXTENDS | `trust.md` replaces bearer tokens with keys pinned at pairing. That refines provisional v4 text (the design session's remit) and is flagged in IC-1. mDNS stays out of mesh-1 (OD-C11) |
| R1d | v4: "Capability exchange (models present, thermal/RAM headroom)" | PLANNED (coarse); NEW (benchmark-derived, signed) | `manifest.md`; live state `router.md`. Benchmark data to peers touches Invariant 1 (IC-3) |
| R1e | v4: "placement policy: this device → LAN node holding the model → cloud. **Whole-model placement only**; cross-device sharding stays parked". §1: "pipeline/RPC sharding across consumer devices adds **capacity, not speed**" | PLANNED for whole-request placement; CONTRADICTS if "distribute" means splitting one model or one request | §1.6 defines exactly what "distribute" means contractually |
| R2a | v1 §7 deterministic router; v4 "node-aware routing" | PLANNED | `router.md` |
| R2b | v2 P4 governors (`RUN / QUEUE / HOLD`, battery floor); v2 P6 per-device calibration | PLANNED on the local device; NEW across devices | Live-state protocol (`router.md`), bounded by the privacy rules in §5.3 and the quiescence law §5.7 |
| R2c | v2.5 three-stage *semantic* router chooses a model for a request, not a device | EXTENDS | Device placement stays deterministic and explainable (`router.md`). v2.5 is not a dependency of mesh-1 (§1.7) |
| R3a | v2 P6: "Recursive passive benchmarking ... every real local inference is a benchmark sample" | PLANNED (passive); NEW (active synthetic runs) | `benchmark.md` two lanes |
| R3b | v2 P6: "**the benchmark app is a *feature*, not a separate app**" | CONTRADICTS | One subsystem, thin shells (`benchmark.md` BD1). Owner decision OD-C10 |
| R3c | v2 P6: "Diagnostics panel visualizes tok/s, headroom, queue depth, and the throughput-vs-time curve" | EXTENDS | Generated plain text (`benchmark.md` §12, `manifest.md` §14) |
| R3d | v2 P7 anonymous upload payload; v4 capability exchange | PLANNED (upload, peers); NEW (file format for any subscriber) | `manifest.md` S1 mesh pull, S2 file. User-directed export touches Invariant 1 (IC-6) |
| R3e | Not mentioned | NEW | `manifest.md`. Tension with P7 anonymity resolved there by separating the private signed manifest from the unsigned public derivative |

### 1.3 Contradictions, in detail

**C-1 A separate benchmark app.** Roadmap v2 P6: "the benchmark app is a *feature*, not a separate app". The owner now asks for one "that can also act as an independent app, if required". The roadmap's *reason* (no second benchmark codebase; the daemon learns its own constraints by running) survives if there is one core and the standalone app is a thin shell over it (`benchmark.md` §2). The *sentence* does not survive. Resolution: amend the sentence (OD-C10). Not an invariant change.

**C-2 iOS and macOS are absent, and iOS cannot be a daemon.** The v1 brief defines the product as an Android daemon serving other apps over localhost. On iOS a backgrounded app is suspended and there is no mechanism to run a network server in the background (`platforms.md` [V01]), so the §10A `RemoteAsom` tier cannot exist there. Consequences: (a) an iOS counterpart is necessarily a different product shape (a standalone app, or an in-app SDK); (b) Invariants 4, 5 and 7 are written in Android terms and cannot be satisfied literally on Apple or desktop platforms. Resolution: IC-7/IC-8 (third amendment, escalated), scoped so mesh-1 does not need it.

**C-3 "Distribute work" versus whole-model placement.** Resolved in §1.6: whole-request placement and concurrent placement of independent requests are compatible with the roadmap; splitting a model or a single request is not, and stays parked.

**C-4 "No mDNS".** The owner's requirement asks for "a way to connect them", which QR pairing plus a static registry already provides. `trust.md` §6.3 argues that mDNS used only to *locate* already-paired peers is safe on trust grounds, and recommends it as a later, off-by-default option. Ruling for mesh-1: the roadmap line stands. v4's own entry criteria require a tailnet, and overlay addresses are stable, so the churn problem that motivates mDNS does not arise in mesh-1. Revisit only with an owner decision (OD-C11).

**C-5 "Exactly two amendments".** Roadmap §13: "Exactly two amendments exist in this entire roadmap, and no session may introduce a third without owner escalation." §2 classifies every change against that rule. One package is a genuine third amendment and is escalated (IC-7, IC-8).

**C-6 The KMP ban.** CLAUDE.md and brief §13: "no KMP". iOS needs code that runs without a JVM. `platforms.md` Option 1 keeps the ban: JVM everywhere a JVM runs, and a Swift implementation held to language-neutral conformance vectors. This section adopts that and adds one contract consequence: every wire format and every signed format is specified by vectors before a second implementation exists (G7).

**C-7 The stop-line.** Roadmap §11: "no public-internet listeners, ever; no incentive/settlement layer for third parties; no peer discovery beyond explicitly paired, owner-controlled devices; no relaying for unpaired parties. v4's private-overlay pairing is the **permanent outer boundary**". "They can all offer to share the compute" is read as *offering to the owner's own paired devices*, never as advertising to anyone. Two sibling ideas press on the line:
- **Cross-owner peers** (`trust.md` class `other`): the stop-line says "owner-controlled devices". Recommendation: not within this roadmap (OD-C9).
- **Delegated routing through a home node** (`platforms.md` role H): the home node would forward a paired iOS app's request to other peers or to the cloud. That is relaying for a *paired* party, which the stop-line's text does not forbid, but it contradicts `trust.md` R6 ("a peer serves with its local engine only") and needs a remote app-client identity that Invariant 5 does not provide. Ruling in §1.5 (X-1) and OD-C8.

**C-8 "Do not start those versions" and strict sequencing.** CLAUDE.md: "Roadmap for v1.1→v4 lives in `ASOM_ROADMAP_BRIEF.md` — do NOT start those versions from this brief." Roadmap §2: v4 entry is "v3 shipped; **design session held**; tailnet exists". §6 splits what could be built now (non-shipping, owner-authorised) from what must wait.

**C-9 Tokens versus keys in the v4 amendment draft.** The roadmap draft says "per-device tokens required" and the QR carries "URL + token + cert fingerprint". `trust.md` §3.1 rejects bearer tokens on a network (a leaked token replays from anywhere) in favour of mutual TLS with keys pinned at pairing. The v4 section is DIRECTION-GRADE and says its delta is "provisional, to be frozen in the v4 design session", so refining it is within this session's remit, but it changes the words of the sanctioned amendment and therefore needs owner sign-off (IC-1).

### 1.4 Inconsistencies inside the frozen documents themselves

These exist independently of the mesh. The design has to resolve them, and only the owner can.

| ID | Inconsistency | Evidence | Consequence | Where resolved |
|---|---|---|---|---|
| I-1 | Roadmap §7 ends the amendment with "No other invariant changes", yet §7 and the §8 registry add egress class `lan`, and Invariant 3 says its classes are listed "exhaustively". §7 also plans "capability exchange" (Invariant 1 names "benchmark data") and "device pairing" (Invariant 5 speaks only of AIDL identity) | Roadmap §7, §8; brief §1.3; `RouteRecord.kt`: `/** Egress classes ... Exhaustive — no additions (invariant). */` [repo] | Amendment 2 as drafted cannot be implemented without further invariant text | IC-2, IC-3, IC-4; OD-C1 |
| I-2 | Roadmap v2 P7 POSTs to `BENCHMARK_SINK_URL`, but Amendment 1 amends Invariant 1 only; Invariant 3's exhaustive list has no class for an upload | Roadmap §4 P7, §13.1; brief §1.3 | The upload would be a network event with no true egress class | IC-5; OD-C2 (same finding as `benchmark.md` BD4) |
| I-3 | The `Egress` enum is marked exhaustive, but `ContractFreezeTest` pins only error codes, virtual models, capabilities and constants. It does not pin `Egress` or the `AsomHeaders` names | `ContractFreezeTest.kt` [repo] | Adding `Egress.PEER` would not trip any test. The tripwire the audit relied on is missing for exactly the surface the mesh changes | §3.6: add the pins as v1.1 hygiene, before any mesh code |
| I-4 | "**Every** network event writes a ledger row" (brief §1.3). v1 appends a cloud row only after the upstream call returns or fails. `RoutePipeline.execute` calls `driver.chat(...)` (which transmits) before any `append`, and `LedgerDurabilityTest` asserts only that the row is committed "before the response reaches the caller" and survives a client hang-up | `RoutePipeline.kt` lines 82–136; `AsomServer.kt` `handleChatLike`; `LedgerDurabilityTest.kt` [repo] | A process death while an upstream call is in flight leaves no row for a transmission that happened. Coroutine `NonCancellable` protects against cancellation, not against process death | §4.11: intent rows for cloud attempts, proposed as v1.1 hardening (OD-C6) |
| I-5 | `docs/CLIENT_API.md` documents `egress` as `("local" \| "cloud")`. The SDK type is `String?`, so an unknown value does not crash, but consuming apps may branch on two values | `CLIENT_API.md` line 86; `InferenceClient.kt` `val egress: String?` [repo] | A new value is safe for the SDK and possibly unsafe for apps that treat "not cloud" as "local" | §3.5 |

### 1.5 Cross-section rulings

The five sibling sections were written in parallel. Where they disagree on something this section owns (contract, invariants, ledger, privacy), this is the ruling. The synthesis may overturn a ruling, but it should not concatenate both positions.

| ID | Disagreement | Ruling | Why |
|---|---|---|---|
| X-1 | `platforms.md` delegates iOS routing to a home node H that runs "the full mesh router" (so H forwards an iOS app's request to its peers or to the cloud with H's keys). `trust.md` R6: "A peer serves with its local engine only. It never sends a peer's request on to the cloud, never forwards it to another peer" | **R6 stands for mesh-1. Delegation is not in mesh-1** and is an owner decision (OD-C8). The alternative that fits R6 is a *direct* requester: the iOS app offers work to its own paired providers, and the providers' own admission decisions carry the "current situation" | Delegation makes H a relay, makes a remote *app* an authenticated client of H (an Invariant 5 question the v1 text cannot answer), and needs a max-reach echo rule so the iPhone learns that its prompt reached the cloud through H (§4.6). Each is a larger change than mesh-1 needs |
| X-2 | `trust.md` §10.2 defines `D0 device-only` as "must not leave this device (no peer; cloud eligibility is unchanged)" | Replaced by the destination-set model (§5.2): "device-only" means {this device}; "no peers, cloud allowed" is a different class | The `D0` definition contradicts itself. A label that says "device-only" while allowing cloud is a stated class that is false (G4) |
| X-3 | `trust.md` §11.2: one row per attempt, written IN_FLIGHT and later **updated** by `attemptId` | **Append-only**: an intent row, then an outcome row (§4) | v1's ledger is insert-only, and keeping it so preserves a simple law ("rows are never modified"), makes successive exports consistent, and leaves room for a hash chain later. Double counting is handled by field design (intent rows carry no bytes, tokens or cost), not by mutation |
| X-4 | Roadmap, `trust.md`, `manifest.md` and `platforms.md` all use class `lan` | Class **`peer`** plus `peerPath: lan \| overlay` (§4.6; OD-C5) | Overlay traffic can be relayed through Tailscale-operated DERP servers in 20+ countries when a direct path fails [CV8]. "lan" would be false for that path |
| X-5 | `X-Asom-Node` (roadmap provisional; `trust.md` C-3; `platforms.md`) | **Withdrawn for mesh-1**; `X-Asom-Served-By: peer:<nodeTag>/<model>` carries the node | With R6 there is exactly one serving node per attempt, and Served-By already names it. `X-Asom-Node` earns a place only if delegation (X-1) is approved |
| X-6 | `trust.md` C-6: virtual policy `own-devices` plus error `NO_ELIGIBLE_NODE` | **Rejected for mesh-1** | The v2.5 per-app cloud ban (roadmap §5: "a banned app's `auto` never touches cloud") plus the per-app mesh setting already expresses "my devices, never cloud", with no wire change |
| X-7 | `trust.md` `retain: owner-verbose` (providers may keep bodies in their verbose log for own-class peers) | **Dropped for mesh-1**; `retain` is always `none` | The requester's own verbose mode already captures the same bodies on the device that originated them. A second copy on another device adds exposure and no capability |
| X-8 | `attemptId` is base64url in `trust.md` frames and base32 in its ledger table | One encoding everywhere: base64url without padding of 16 CSPRNG bytes (22 characters) | Two encodings of one id is a join bug waiting to happen |
| X-9 | `trust.md` scope `revoke-hint`, frames `REVOCATION_HINT` and `LOCATOR_HINTS` | **Deferred** past mesh-1 | mesh-1 is overlay-first with a handful of the owner's devices. Revoking on each device is manageable, and overlay addresses do not churn. Each deferred item is a new surface and, for revocation hints, a denial-of-service channel |
| X-10 | `trust.md` `INFER_OFFER` carries `dataClass` | **Removed** from the offer | The provider needs only `retain` (always `none` in mesh-1). Sending the class tells the provider how sensitive the requesting app is configured to be |
| X-11 | `manifest.md` MC-4 `GET /admin/manifest` | **Deferred** (agrees with `manifest.md` OD-M5) | §3.2 CD-9 |
| X-12 | `benchmark.md` BD4 and `platforms.md` OD7 both ask for explicit Invariant 3 wording for new classes | **Adopted** (IC-2, IC-5) | I-1, I-2 |

### 1.6 What "distribute work" means, contractually

| Mode | What it is | Contract consequence | Status |
|---|---|---|---|
| D1 Whole-request placement | Each request runs entirely on one node: this device, one peer, or the cloud | One serving node per attempt; one `RouteRecord` per attempt; existing headers suffice | **mesh-1** (roadmap v4 as written) |
| D2 Concurrent placement of independent requests | Several in-flight requests (from different apps, or a loop's independent children) land on different nodes at once | None beyond D1: each request is placed independently | **mesh-1** |
| D3 Splitting one request's independent items | For example an `/v1/embeddings` request with an array input split across nodes | One response served by several nodes: `X-Asom-Served-By` names one provider, so the echo headers can no longer describe the request truthfully without a new header shape | Deferred. Needs a contract design of its own |
| D4 Prefill on one node, decode on another; cross-device speculative decoding | One request's content on two nodes; model-fidelity questions | Two serving nodes per attempt; both see content; ledger linkage per stage | Parked (evaluated in `router.md`) |
| D5 Layer, pipeline or tensor sharding | One model's weights across devices | Roadmap §1: "capacity, not speed"; parked. The obvious implementation, llama.cpp RPC, is documented upstream as "fragile and insecure. Never run the RPC server on an open network" [CV7] | Parked; would need the peer protocol to carry tensors, not requests |
| — Task decomposition | Splitting one *task* into sub-requests (ensemble, critic, map-reduce) | Roadmap §10: "the methodology layer never migrates into asom. If the orchestrator wants patterns, it composes asom calls." v3 loops are daemon-local and their child calls are ordinary D1/D2 requests | Consumer's job, not asom's |

"Intelligently distribute" in mesh-1 is therefore D1 plus D2, with intelligence in *which node gets which request, when* (`router.md`), not in splitting work.

### 1.7 Ordering constraints

| # | Constraint | Source | Effect |
|---|---|---|---|
| O1 | v1 device validation (`QA_V1.md`, P5–P7 checklists, audit fixes, P6 RemoteAsom re-run) is still open | `PROGRESS.md` | Nothing that ships is built on top of v1 until it closes |
| O2 | v1.1 entry: "v1 P8 gate passed; RedMagic validation complete" | Roadmap §2 | Same |
| O3 | Everything that measures or serves needs a local engine; "current situation" needs the v2 P4 governors | Roadmap §4; `trust.md` R6 (peers serve with their local engine only) | Active benchmarks, manifests with real numbers, and every provider role are v2 or later |
| O4 | v4 entry: "v3 shipped; **design session held**; tailnet exists" | Roadmap §2 | mesh-1 needs the amendment package signed, the owner's tailnet, and either v3 or an owner re-sequencing (OD-C4) |
| O5 | The per-app mesh setting belongs in the v2.5 per-app policy table | Roadmap §5 | If mesh-1 comes before v2.5, it needs a minimal per-app on/off toggle in the Hotspot tab instead |
| O6 | The v3 egress firewall (redaction) is needed only for other-owner peers | Roadmap §6 | Not a mesh-1 dependency, because other-owner peers are not in mesh-1 |
| O7 | iOS needs an Apple-silicon Mac, the Developer Program and a frozen spec | `platforms.md` A02, A03, OD2 | iOS comes last and never blocks anything earlier |
| O8 | Specification before second implementation | G7 | Swift code starts only after the M- and W-vector families are frozen on the JVM |
| O9 | Pre-work may not land in shipped modules | CLAUDE.md; G8 | Lab modules (§6.3) until the owning version's entry criteria are met |

**Honest dependency note.** v4's entry criterion "v3 shipped" is sequencing discipline, not a technical dependency of an own-devices mesh. mesh-1's real dependencies are v2 (engine and governors on the providers), a per-app toggle, the signed amendment package and the tailnet. That is why OD-C4 offers the owner a re-sequencing option. This section does not assume it.

---

## 2. (a) Proposed invariant changes

### 2.1 The classification test

For every proposed change:

1. **Does it change the text or the meaning of a v1 §1 invariant, or add an instance to a list the invariant declares exhaustive or enumerated?** If no, it is a clarification or a design choice, not an amendment.
2. **If yes: did the roadmap anticipate its substance in the same version as one of the two sanctioned amendments** (Amendment 1: v2 benchmark contribution; Amendment 2: v4 private-overlay listener)? If yes, it is a *companion* of that amendment. It still needs owner sign-off on the exact words.
3. **If not anticipated, it is a third amendment.** It is escalated with exact wording, options and a recommendation, and nothing that depends on it is scheduled until the owner rules.

One aggravating fact applies to every companion of Amendment 2: roadmap §7 says "No other invariant changes." A literal reader can call every companion clause a third amendment. This section recommends reading §7 by its own delta registry, which already names `lan` and capability exchange, but that is the owner's call (OD-C1), not an assumption.

### 2.2 Summary

| ID | Invariant | Change | Needed by | Classification (§2.1) | Recommendation |
|---|---|---|---|---|---|
| **IC-1** | 2 | Peer listener: exact addresses, interfaces, protocol and authentication; replaces the roadmap draft | mesh-1 providers | **Amendment 2** (sanctioned; this refines provisional wording, C-9) | Approve the text in §2.3.1 |
| **IC-2** | 3 | New class (d) `peer` | mesh-1 | **Companion of Amendment 2**: anticipated (roadmap §7, §8 name the class) but contradicts "No other invariant changes" (I-1) | Approve as part of Amendment 2 (OD-C1) |
| **IC-3** | 1 | Clause permitting enumerated peer control messages (live state, capability manifest) to paired peers, with a quiescence rule | mesh-1 | **Companion of Amendment 2**, with third-amendment risk: capability exchange is anticipated, but a *benchmark* manifest goes beyond "models present, thermal/RAM headroom", and Invariant 1 names "benchmark data" | Approve as part of Amendment 2; if the owner reads it as a third, the fallback is §2.3.3's "share only on a tap" variant |
| **IC-4** | 5 | Clarify that node pairing is key-based inside a user-opened window, and that a remote node is never an app client | mesh-1 | **Not an amendment**: a clarification with roadmap precedent. v3 plans `POST /pair/web` (a network pairing path with a user-minted code) and treats it as consistent with Invariant 5 without counting an amendment ("consent precedes contact and identity is the code itself") | Write it into the brief anyway |
| **IC-5** | 3 | New class (e) `contribution` for the v2 P7 upload | v2 P7 | **Completion of Amendment 1** (fixes I-2) | Approve as part of Amendment 1 (OD-C2) |
| **IC-6** | 1 | User-directed export of the user's own benchmark report | v2 (R3b, R3d) | Anonymous forms (plain-text report, anonymous summary): **within Amendment 1's substance** (benchmark data leaving by an explicit, view-first action, anonymous by construction). Signed identified form (carries the device key's fingerprint): **not** within Amendment 1, whose payload must carry "no fingerprint beyond coarse device class" → **third-amendment risk** | Approve the anonymous forms under Amendment 1; owner decides the signed form (OD-C2) |
| **IC-7** | 7, 8 | Platform equivalence, part A: UI toolkit and vendor-service wording for Apple and desktop | Any Apple app (iOS benchmark app, macOS GUI) | **THIRD AMENDMENT** (the roadmap never mentions these platforms) | Escalate; approve when the owner commits to Apple (OD-C3) |
| **IC-8** | 4, 5 | Platform equivalence, part B: key wrapping and local app identity per OS | A desktop node that holds BYOK keys or serves local apps; any iOS requester SDK | **THIRD AMENDMENT** | Escalate only when a phase needs it. mesh-1 is scoped so it does not (§2.3.6) |
| IC-9 (conditional) | 2, 5 | Remote app clients / delegated routing | iOS requester via a home node | **THIRD AMENDMENT**: contradicts IC-1's "never the app-facing API" and IC-4's "a remote node is never an app client" | Recommend not in this roadmap (OD-C8) |
| IC-10 (conditional) | stop-line | Other-owner peers | — | Beyond the roadmap §11 stop-line ("owner-controlled devices") | Recommend no (OD-C9) |

Unchanged: Invariant 6 (applies as written on every platform, including CLI output: labels, never colour alone); Invariant 9 (extended additively in §4.6, text unchanged); Invariant 4 and 8 on Android.

### 2.3 Exact wording

#### 2.3.1 IC-1 — Invariant 2, as amended at v4 (Amendment 2)

Adapted from `trust.md` §12.2 with two corrections: a node can promise only its own ledger, not its peer's; and asom must not rely on overlay features that publish ports.

> **2. Binding.** The app-facing API server binds `127.0.0.1` only — never `0.0.0.0`, `::`, or any other address — and is never reachable from another device. Separately, and only while the user has switched it on (OFF by default), a **peer listener** MAY bind specific addresses on (a) a private-overlay interface the user selected (Tailscale-class tailnet), and/or (b) a Wi-Fi or Ethernet interface on a network the user enabled it for, using private or link-local addresses only (IPv4 `10/8`, `172.16/12`, `192.168/16`, `169.254/16`; IPv6 `fc00::/7`, `fe80::/10`). It never binds a wildcard, a publicly routable address, or a cellular interface. A pairing window the user opens may switch it on for at most 120 seconds. The peer listener speaks only the peer protocol, never the app-facing API; only TLS 1.3 with mutual authentication against per-node keys pinned at an in-person pairing; and serves only peers whose registry state is PAIRED, re-checked on every request. asom never enables, requests or depends on an overlay feature that publishes a port beyond the overlay. No cleartext beyond localhost. Never a public interface.

**Minimal scope.** It applies only to a node that grants some peer an inbound scope. An outbound-only node (the mesh-1 phone, any iOS requester) binds nothing beyond loopback, and for it Invariant 2 is unchanged.

**What it does not guarantee.** It cannot stop a user from exposing the listener through third-party configuration. Tailscale Funnel, for example, "lets you route traffic from the broader internet to a local service" once a tailnet policy attribute enables it [CV5]. asom cannot reliably detect that. The listener would still require a pinned key, so the exposure is a handshake surface rather than an open API, but the words "never a public interface" would then be false by the user's configuration. The documentation must say so (risk K-10).

#### 2.3.2 IC-2 — Invariant 3, new clause (d) (companion of Amendment 2)

> **3(d) Peer traffic (egress class `peer`).** The peer protocol to or from a PAIRED peer at an address eligible under Invariant 2, carrying inference attempts this node sends or serves and the enumerated control messages (live state, capability manifest, pairing, revocation notice). Peer traffic is never sent to an unpaired party and never relayed onward. Every inference attempt is ledgered on each node that takes part, with an intent row made durable before request content is sent or processed. Control traffic is ledgered per session, and each capability manifest sent or received is ledgered individually.

Also strike roadmap §7's "No other invariant changes." and replace it with "Companion clauses: Invariant 3(d), Invariant 1 clause (peer control), Invariant 5 clarification."

#### 2.3.3 IC-3 — Invariant 1, peer-control clause (companion of Amendment 2)

> **1, peer-control clause (v4).** Peer control messages are not usage or benchmark uploads when all of the following hold: they go only to a PAIRED peer that the user granted the matching scope; they contain only fields enumerated in the published peer schema — never request or response content, keys or key presence, app identities, ledger rows or per-app usage; a capability manifest is shared with a peer only after the user has viewed the exact report body and approved sharing it with that peer; the last payload of each kind sent to each peer can be viewed; and every transmission is ledgered. **Quiescence:** a node initiates no peer connection unless a local request is pending that may be placed on a peer, an asom screen showing peer status is open, or the user started a peer operation (pairing, sharing a report, revoking).

**Minimal scope.** It covers only `STATE` and `MANIFEST` frames (and the frames needed to carry them) between PAIRED own devices. It does not cover any upload, any third party, or any unpaired device.

**Fallback if the owner reads this as a third amendment.** Remove the clause and ship without it: manifests are then shared only by a per-share tap (`manifest.md` OD-M1 option (b)), and live state is not exchanged at all; providers answer only `INFER_OFFER` with accept or decline. `router.md` then routes on offer outcomes and observed performance only. Requirement R1d degrades from "knows every device's capabilities in advance" to "learns them by asking at request time and from what the user shared by hand".

#### 2.3.4 IC-4 — Invariant 5, clarification (not an amendment)

> **5.** Pairing identity for apps is AIDL-verified via `Binder.getCallingUid()`. No HTTP registration endpoint exists (the legacy `POST /admin/register` design is deleted). **Node pairing** between the user's devices is key-based and possible only inside a pairing window the user opened on the listening node: a single-use secret of at most 120 seconds, shown as a QR code, bound to both nodes' keys, and confirmed by the user on both devices. No network message can create, restore or raise a pairing. A remote node is never an app client: it cannot reach the app-facing API, and it never acts under an app's pairing.

**Why this is a clarification.** It adds no new path for *app* identity, which is what Invariant 5 governs, and its node-pairing rule follows the same "consent precedes contact" argument the roadmap already accepted for v3 web pairing without counting an amendment. The last sentence is deliberately restrictive: it makes delegated routing (IC-9) visibly a change, not an interpretation.

#### 2.3.5 IC-5 and IC-6 — Amendment 1, completed (v2)

> **1 (as amended at v2).** No automatic egress. No analytics, no crash-reporting SaaS, no telemetry SDKs, and no background or silent transmission of usage or benchmark data — ever. Data leaves the device only by an explicit foreground user action that shows the exact payload first. Two kinds of such action exist: (i) user-triggered ledger export via the share sheet; (ii) benchmark data, as either (a) **opt-in benchmark contribution**: off by default, the payload anonymous by construction (coarse device class only; no install id, key or fingerprint), sent to `BENCHMARK_SINK_URL` only on the user's tap, declining in one tap with a long re-prompt cooldown; or (b) **user-directed export of the user's own benchmark report**: the plain-text report or the anonymous summary by default [**owner option:** or the signed report, whose export screen first states that it carries this device's key fingerprint and links every report exported with it].

> **3(e) Benchmark contribution (egress class `contribution`).** A single HTTPS POST of the exact payload the user viewed, to `BENCHMARK_SINK_URL`, only on the user's tap.

**Classification.** 3(e) is the half of Amendment 1 the roadmap forgot (I-2): same substance, same version. 1(ii)(b) in its anonymous forms has the same substance and the same safeguards as the contribution, but a different destination (one the user picks), so it widens Amendment 1's text. The bracketed signed option breaks Amendment 1's anonymity condition, so it is the owner's call and carries third-amendment risk (OD-C2). A share-sheet export is not a network event by asom, so it needs no Invariant 3 class, exactly as the v1 ledger export needs none.

#### 2.3.6 IC-7 and IC-8 — platform equivalence (THIRD AMENDMENT; escalate)

Part A (IC-7), needed by any Apple app, including the iOS benchmark app:

> **7.** UI is placeholder-functional, built with each platform's native toolkit (Compose/Material3 on Android, SwiftUI on Apple platforms, a command-line interface on desktop), wired against the token-contract seam. No visual design ambition.
> **8.** No GMS, Firebase or Play-services dependencies. On Apple platforms: no CloudKit, no iCloud Keychain or iCloud Drive synchronisation of asom data, no App Attest or DeviceCheck calls, and no third-party SDKs; asom data is excluded from device backups. On desktop: no vendor telemetry.

Part B (IC-8), needed only by a desktop node that holds BYOK keys or serves local apps, and by any iOS SDK tier that holds keys:

> **4.** BYOK keys are wrapped by a non-exportable, hardware-backed key where the platform provides one — Android Keystore (StrongBox when available, TEE fallback); Apple Secure Enclave with Keychain items `ThisDeviceOnly` and non-synchronisable — entered only in that node's own key-entry UI, never accepted or returned by any API, never in logs, the ledger, backups or any peer message. A node without hardware-backed wrapping holds no BYOK keys.
> **5 (platform clause).** App identity comes only from an OS-verified caller identity: `Binder.getCallingUid()` on Android; `SO_PEERCRED` on a Unix-domain socket, same OS user only, on Linux; the XPC audit token checked against a code-signing requirement on macOS. Where no OS-verified cross-app identity exists (iOS), there is no cross-app service.

**Why this is a third amendment.** Invariants 4, 5 and 7 are written in Android terms. Applying them to platforms the roadmap never mentions changes what they mean. The owner's own requirement (R1a) is the trigger for escalation, but the requirement is not the approval: the exact text needs a ruling.

**How mesh-1 avoids it.** Desktop nodes in mesh-1 are providers plus the owner's own CLI (the node's administrative UI, like the Android dashboard; not a third-party app): no BYOK vault, no cloud routing, no third-party local apps. The roadmap already sanctions "CLI shell first" for asom-desktop, so Invariant 7 is not engaged. No Apple software ships in mesh-1. Part A is needed first, by the iOS benchmark app (§6, M3). Part B is needed only if the owner later wants a desktop node to act as a hub for local apps or to route to the cloud.

**What part B does not guarantee.** Hardware wrapping stops extraction, not use by malware on a running, unlocked device. `SO_PEERCRED` identifies an OS user, not an application, so on Linux every process of that user is the same "app".

### 2.4 Frozen meanings kept (no change, stated so nobody reinterprets them)

| Frozen item | Meaning under the mesh |
|---|---|
| `local-only` (brief §5.3, §5.5; roadmap v2 "never silently falls to cloud") | **This device only.** Never a peer. Unchanged |
| `X-Asom-No-Train: true` | Filters cloud providers exactly as in v1. Peers run local engines, which do not train, so they are unaffected |
| `X-Asom-Fallback` | Restricts candidates to the listed *providers* (v1 `Router`). Peers are not providers, so a request carrying this header never goes to a peer |
| `X-Asom-Egress` | Describes where request **content** went. A metadata-only offer that a peer declined does not change it (§4.6) |
| AIDL pairing (§5.7), discovery provider (§5.1), models provider (§5.8) | Untouched. No mesh data is exposed through any of them (§3.2 CD-8) |

### 2.5 Do-not list (brief §13) items engaged

| Item | Status |
|---|---|
| "no extra egress classes" | IC-2 and IC-5 add classes. They are proposed only as amendment text, never as code ahead of it |
| "no endpoints/headers beyond §5" | §3: none new in mesh-1; new *values* in two existing headers, each flagged |
| "no KMP" | Kept (C-6; `platforms.md` OD1) |
| "no telemetry libraries", "no Firebase/GMS" | Unaffected; extended to Apple and desktop by IC-7 |
| CLAUDE.md "do NOT start those versions" | Pre-work only with explicit owner authorisation (OD-C4) |

---

## 3. (b) Contract delta registry

### 3.1 Admission test

An item is admitted only if all four hold:
1. **Needed** by a phase this design actually proposes (§6), not by a possible future.
2. **No existing surface can express it truthfully.** A new value in an existing field beats a new field; a new field beats a new header; a new header beats a new endpoint.
3. **It does not disclose identity or sensitivity to a less-trusted party** (for example, anything on the permissionless discovery provider).
4. **It is pinned by a vector or freeze test** before it ships.

### 3.2 The registry

Verdicts: **PROPOSE** (needed for the named phase; owner sign-off required), **DEFER** (not needed yet; revisit with the named trigger), **REJECT** (fails the test), **WITHDRAW** (a roadmap provisional item this design does not need).

**App-facing surface (frozen v1 §5; additive only)**

| ID | Item | Phase | Verdict | Reason |
|---|---|---|---|---|
| CD-1 | `X-Asom-Egress` value **`peer`** | mesh-1 | PROPOSE | Invariant 9 and the audit: the header must say where content went. Neither `local` nor `cloud` is true for a peer-served request |
| CD-2 | `X-Asom-Served-By` value form **`peer:<nodeTag>/<model>`** (`nodeTag` per `trust.md` §2.3: 16 lowercase base32 characters, never an authorization input) | mesh-1 | PROPOSE | Identifies the serving device without a new header. Splitting on the first `/` stays correct because `nodeTag` contains no `/` |
| CD-3 | `X-Asom-Node` (roadmap §7, §8 provisional) | — | **WITHDRAW** for mesh-1 | Redundant with CD-2 while one node serves each attempt. Reconsider only if OD-C8 approves delegation |
| CD-4 | `/v1/models`: peer-held models listed with `owned_by: "asom-peer"` — one constant, no device tag — only to apps whose mesh setting is not `off`, and only for models that a PAIRED peer granting me `infer` reported in its last verified manifest or live state | mesh-1 | PROPOSE | Without it an app cannot discover a model that only the Dell holds. Brief §5.2 already requires concrete entries to be "tagged via `owned_by`/metadata so clients can distinguish"; this adds one value |
| CD-5 | New error codes | — | **none** | Every mesh situation maps truthfully to an existing code (§3.4) |
| CD-6 | Virtual policy `own-devices` and error `NO_ELIGIBLE_NODE` (`trust.md` C-6) | — | REJECT for mesh-1 | X-6 |
| CD-7 | New request headers (for example a per-request "no peers" switch) | — | REJECT | `local-only`, `X-Asom-Fallback` and the per-app setting already cover it (§2.4) |
| CD-8 | Discovery capabilities JSON (§5.1) additions such as `mesh`, `nodes`, `peerModels` | — | REJECT | The provider needs no permission. Any mesh field would tell every app on the device, paired or not, that the user owns other devices and what they hold |
| CD-9 | `GET /admin/manifest[?challenge=]` on 127.0.0.1, bearer-gated (`manifest.md` MC-4) | — | DEFER | Requirement R3d is met by mesh pull and file export. Trigger: a consuming app that needs live, machine-readable capability data from the local daemon. Note: a stable node-key fingerprint handed to every paired app is a cross-app device identifier, so the endpoint would need a per-app projection without the key |
| CD-10 | AIDL additions | — | none | — |
| CD-11 | App token scopes (v3 `chat \| ledger-read`) | — | none | Peer scopes live in the peer registry, not on app tokens (CD-17) |

**Ledger and export (brief §9; additive columns, nullable, defaulted)**

| ID | Item | Phase | Verdict | Reason |
|---|---|---|---|---|
| CD-12 | `Egress.PEER` (wire `peer`) | mesh-1 | PROPOSE (with IC-2) | §4.6 |
| CD-13 | `Egress.CONTRIBUTION` (wire `contribution`) | v2 P7 | PROPOSE (with IC-5) | I-2 |
| CD-14 | Fields `requestId`, `attemptId`, `phase` | v1.1 (cloud) / mesh-1 | PROPOSE | Append-only write-ahead (§4.2). Each challenged: `attemptId` is the only cross-node link; `requestId` groups one request's attempts locally and never leaves; `phase` distinguishes intent from outcome without mutating rows |
| CD-15 | Fields `peerNode`, `peerPath`, `meshKind`, `bytesIn`, `meshCode` | mesh-1 | PROPOSE | `peerNode`: the other node, also for control rows where `servedProvider` is meaningless. `peerPath`: needed for the class to be true (§4.6). `meshKind`: one field for role and kind (replaces `trust.md`'s two). `bytesIn`: served rows receive content, which v1's `bytesOut` cannot express. `meshCode`: a typed reason (a decline is not an HTTP status) |
| CD-16 | Status `0` means intent (in flight) | v1.1 / mesh-1 | PROPOSE | §4.2 |
| CD-17 | Export: same JSON array, new keys on each row; v1 rows unchanged | v1.1 / mesh-1 | PROPOSE | A wrapper object would break v1 export consumers |

**Mesh surfaces (new; outside §5)**

| ID | Item | Phase | Verdict | Reason |
|---|---|---|---|---|
| CD-18 | Peer protocol `asom-mesh/1` (`trust.md` C-1), **trimmed for mesh-1** to: `HELLO`, `HELLO_ACK`, `PING`, `PONG`, `GOAWAY`, `ERROR`, `INFER_OFFER` (without `dataClass`), `INFER_ACCEPT`, `INFER_DECLINE`, `INFER_BODY`, `INFER_HEAD`, `INFER_CHUNK`, `INFER_END`, `CANCEL`, `STATE_REQ`, `STATE`, `MANIFEST_REQ`, `MANIFEST`, `PAIR_*`, `REVOKE_NOTICE`. Default TCP port 11436 | mesh-1 | PROPOSE | The transport R1c requires |
| CD-19 | `REVOCATION_HINT`, `LOCATOR_HINTS`, scope `revoke-hint` | — | DEFER | X-9 |
| CD-20 | Peer scopes `infer`, `state`, `manifest` | mesh-1 | PROPOSE | `state` and `manifest` stay separate because the manifest carries benchmark data governed by IC-3's view-and-approve rule |
| CD-21 | Mesh error codes on the peer channel only (`trust.md` §3.3 list, minus codes for deferred frames) | mesh-1 | PROPOSE | Separate from the frozen §5.6 enum; never surfaced to apps (§3.4) |
| CD-22 | Live-state schema (`router.md`), under the field limits of §5.3 | mesh-1 | PROPOSE | R2b |
| CD-23 | Manifest file and frame formats (`manifest.md` MC-1, MC-2, MC-3) and the public derivative (MC-6) | v2 (file, public) / mesh-1 (frames) | PROPOSE | R3d, R3e. A file format that third parties code against is a public contract |
| CD-24 | Desktop local control channel: a Unix-domain socket, same-uid only (`SO_PEERCRED`), for the node's own CLI | mesh-1 | PROPOSE | The owner's CLI must reach the running daemon. HTTP would add a local API surface and a token; the socket adds neither |
| CD-25 | mDNS locate-only (`trust.md` OD-3) | — | DEFER | C-4 |

**Catalogue (owner's repository; additive, roadmap §9)**

| ID | Item | Phase | Verdict | Reason |
|---|---|---|---|---|
| CD-26 | `benchSets[]` (`benchmark.md` CB2) | v2 | PROPOSE | Test-model downloads remain Invariant 3(c) downloads from catalogue URLs |
| CD-27 | `attestation.android` block (`manifest.md` MC-5) | — | DEFER | Only when Android attestation verification is actually built |

**Net app-facing change for mesh-1:** two header values (CD-1, CD-2) and one `owned_by` value (CD-4). No new endpoint, request header, error code, AIDL method or discovery field.

### 3.3 Router semantics the registry depends on

These are not contract items, but CD-1 is only truthful if they hold (details in `router.md`):
- **Monotone reach (law E-1).** Within one request, attempts are ordered so that content reach never decreases: this device, then peers, then cloud. Consequence: the served attempt's class always equals the furthest content reach of the request, so `X-Asom-Egress` stays built from the served attempt's record exactly as in v1, and no extra field is needed. This matches roadmap §7's placement order. If `router.md` needs non-monotone orders, the price is an additive `requestEgress` field on the terminal record and a header built from it. That is the documented fallback, not part of this proposal.
- **Peer declines enter the breaker.** A decline with `retryAfterMs`, and an unreachable peer, put that peer in cooldown until the stated time (or the v1 backoff for unreachable). This is what makes `ALL_PROVIDERS_COOLING` literally true when every candidate, peers included, is unavailable.

### 3.4 Mesh situations mapped to existing error codes

| Situation | Code returned to the app | Why it is true |
|---|---|---|
| Every candidate (peers and cloud) declined, was unreachable, or failed retryably | `ALL_PROVIDERS_COOLING` (503) | Each of them is now in cooldown (§3.3). Authored message: "all candidates, including your paired devices, are unavailable; retry later" |
| A catalogue model with no cloud key, held only by a peer, requested by an app whose mesh setting is `off` | `NO_PROVIDER_KEY` (503) | Identical to v1 for that app: no usable provider. Authored message names "paired devices not permitted for this app" |
| `local-only` | v1 `LOCAL_ENGINE_ABSENT`; v2 `THERMAL_HOLD`, `MODEL_OOM` | Unchanged; never a peer |
| Peer returns an error for the body (for example context overflow on the peer) | The peer's typed error body relayed verbatim, as v1 relays a fatal upstream error | Same as v1 `UpstreamError`; the ledger row carries the peer's status |
| Peer lost mid-stream, after echo headers were committed | No code is reachable (200 already sent); the ledger row records 502 with `meshCode` `PEER_LOST` | Same as v1 streaming failures |
| Peer declined for thermal, battery or user activity during `auto` | Not an error: failover to the next candidate | Every step ledgered (§4.5) |

### 3.5 Compatibility for consuming apps

- `:client` exposes `egress` as `String?`, so `peer` reaches apps unchanged [repo]. `docs/CLIENT_API.md` must change its comment from `("local" | "cloud")` to an open set, and say plainly: "treat any value other than `local` as content having left this device".
- An app that parses `X-Asom-Served-By` into provider and model by the first `/` receives provider `peer:<nodeTag>`. An app that looks the provider up in the catalogue will not find it; the SDK documentation must say so.
- No change for `CloudOnly` or `Embedded`.

### 3.6 Freeze-test evolution (the tripwire, fixed)

1. **v1.1 hygiene (before any mesh code):** add pins to `ContractFreezeTest` for the exact `Egress` set (`local, cloud, catalogue, download`) and the exact `AsomHeaders` names (I-3). This makes the audit's tripwire real for the surface the mesh changes.
2. **When an amendment is signed:** the same commit that adds `Egress.PEER` (or `CONTRIBUTION`) updates the pin and cites the owner's decision record. A pin changed without a cited decision fails review.
3. The mesh's own freeze tests live with its vectors (`conformance/`; `platforms.md` §8): frame types, mesh error codes, scope names, ledger field names, the export key set.

---

## 4. (c) Ledger design for cross-node requests

### 4.1 Principles (the audit, applied)

| # | Principle | Audit finding it answers |
|---|---|---|
| P1 | Every attempt that puts bytes on a wire has rows of its own, on every node that handled content | "Failover wrote no row for providers that had already received the prompt" |
| P2 | A stated class is true for the event the row describes | "`ALL_PROVIDERS_COOLING` recorded `egress=local` ... for a request transmitted to every candidate" |
| P3 | Content never moves before its intent row is durable; the response is not complete until its outcome row is durable | "The streaming path appended its row last ... a client disconnect ... lost it"; "Android's sink only enqueued" |
| P4 | Rows are never modified. Later knowledge arrives as a new row | New (X-3) |
| P5 | Rows carry no content, keys, addresses, device names or the other node's app identities | Invariant 4; §5 |
| P6 | Linkage between nodes is by a random id only | New |

### 4.2 Row schema

Additive fields on `RouteRecord` and `route_log` (a desktop node writes the same record as JSON Lines). Every new field is nullable or defaulted, so v1 rows and v1 serialisation are unchanged. The Kotlin below is a proposal for `:lab:ledger-model` (§6); it enters `:core:contract` only after IC-2 and the registry are signed.

```kotlin
enum class Egress(val wire: String) {
    LOCAL("local"), CLOUD("cloud"), CATALOGUE("catalogue"), DOWNLOAD("download"),
    PEER("peer"),                 // proposed, IC-2 / CD-12
    CONTRIBUTION("contribution"), // proposed, IC-5 / CD-13
}
enum class Phase(val wire: String) { INTENT("intent"), OUTCOME("outcome") }
enum class PeerPath(val wire: String) { LAN("lan"), OVERLAY("overlay") }
enum class MeshKind(val wire: String) {
    INFER_SENT("infer-sent"), INFER_SERVED("infer-served"),
    CONTROL("control"), MANIFEST_SENT("manifest-sent"), MANIFEST_RECEIVED("manifest-received"),
    PAIRING("pairing"), REVOCATION("revocation"),
}

data class RouteRecord(
    /* ... the 13 v1 fields, unchanged ... */
    val requestId: String? = null,   // 16 CSPRNG bytes, base64url no padding; LOCAL ONLY, never on any wire
    val attemptId: String? = null,   // 16 CSPRNG bytes, base64url no padding; on the wire only for peer attempts
    val phase: Phase? = null,        // null = v1-style single row
    val peerNode: String? = null,    // nodeTag of the other node
    val peerPath: PeerPath? = null,  // from the connected socket's local interface, never guessed
    val meshKind: MeshKind? = null,
    val bytesIn: Long? = null,       // content bytes received on this attempt
    val meshCode: String? = null,    // typed mesh outcome, e.g. PEER_THERMAL, PEER_LOST
)
```

Field rules:

| Field | Requester (`infer-sent`) | Provider (`infer-served`) |
|---|---|---|
| `callerPkg` | the local app's verified package (v1 meaning) | `peer:<nodeTag of requester>`. The requester's app identity is never transmitted |
| `servedProvider` / `servedModel` | `peer:<nodeTag>` / the model **as the peer reported it** in `INFER_HEAD` (a claim) | `local` / the model it actually loaded |
| `egress` | `peer` | `peer` (it sends generated content back) |
| `bytesOut` | request **content** bytes sent (`INFER_BODY`); 0 when declined. Offer metadata is not content | response content bytes sent |
| `bytesIn` | response content bytes received | request content bytes received |
| `tokensIn` / `tokensOut` | from `INFER_END.usage` (the peer's claim) | counted locally |
| `costEst` / `costBasis` | `null` / `none` (no money changes hands; energy is `router.md`'s concern) | same |
| `status` | intent rows `0`; outcomes use HTTP-like values: 200 served; 4xx/5xx relayed; 499 client or requester closed; 502 peer lost or protocol failure; 503 declined; 599 unreachable or timeout (v1 already uses 599 for I/O) | same scheme |

Intent rows always have `bytesOut = 0`, `bytesIn = null`, `tokens*` null, `costEst` null, on both nodes. A consumer that sums bytes, tokens or cost across all rows therefore gets correct totals even if it ignores `phase`. Only a raw row count double-counts, and the export documentation says so. The cost of this rule: an intent row left without an outcome by a crash does not say how many content bytes moved, only that content may have moved. That is stated in the dashboard text rather than estimated.

### 4.3 Which network event produces which rows

| Event | Rows on the node that sends | Rows on the other node |
|---|---|---|
| Inference attempt, declined at offer | intent (before `INFER_OFFER`), outcome 503 + `meshCode` | one outcome row (503 + code). No intent: no content was processed |
| Inference attempt, accepted and served | intent (before `INFER_OFFER`), outcome | intent (after `INFER_BODY` arrives, before the engine reads it), outcome (before `INFER_END` is written) |
| Dial failure (TCP, TLS pin mismatch, timeout) | outcome 599 `PEER_UNREACHABLE` on the attempt or session that dialled | none (nothing was authenticated) |
| Control session (`HELLO`, `STATE`, `PING`) | intent at dial; an **interval** outcome row hourly and at close, with the bytes since the previous row | the same, on its side |
| Capability manifest presented or received | one `manifest-sent` row per presentation (bytes excluded from the session interval rows, so nothing is counted twice) | one `manifest-received` row |
| Pairing ceremony | one `pairing` row per side (200 paired, 403 refused, 408 window closed) | same |
| `REVOKE_NOTICE` sent or received | one `revocation` row | one `revocation` row |

Per-session aggregation for control traffic (instead of one row per `PING` or `STATE`) is an interpretation of "**every** network event writes a ledger row". It is recommended because the aggregate row is written durably, states the peer, the path, the interval and the bytes, and so gives a complete and true account at session granularity. It is the owner's call (OD-C7). Content-bearing events (inference attempts, manifests) are never aggregated.

### 4.4 Sequences and durability points

**Requester, one peer attempt.** `D` marks a durable commit that must complete before the next line runs.

```kotlin
suspend fun attemptOnPeer(req: LocalRequest, peer: PeerRow, offer: OfferMeta): AttemptResult {
    val aId = randomB64u16()
    ledger.append(intent(req, peer, aId))                                   // D1: before any byte of this attempt
    val s = sessions.openOrReuse(peer)                                      // dial + mutual TLS + HELLO (trust.md)
        ?: return finish(aId, status = 599, code = "PEER_UNREACHABLE", bytesOut = 0)
    s.send(INFER_OFFER(aId, offer))                                         // metadata only (R5); no dataClass
    when (val r = s.awaitDecision(aId, timeoutMs = 3_000)) {
        is Decline  -> { breaker.coolUntil(peer, r.retryAfterMs); return finish(aId, 503, r.code, bytesOut = 0) }
        is Timeout  -> { breaker.recordFailure(peer);              return finish(aId, 599, "PEER_TIMEOUT", 0) }
        is Accept   -> Unit
    }
    s.send(INFER_BODY(req.bodyBytes))                                       // content leaves; D1 already durable
    return relayResponse(aId, s, req)                                       // headers from the commit view (4.6);
}                                                                           // outcome row D2 before the response completes
```

`finish` appends the outcome row (D2) and returns. For streams, D2 happens in a `NonCancellable` block after the stream ends, exactly like v1's `handleChatLike`. For non-streamed responses D2 happens before `respond`.

**Provider.**

```kotlin
onOffer(pin, offer):  decisionTable(pin, offer)                            // trust.md §7.3
    Decline(code) -> { ledger.append(outcomeServed(offer.aId, 503, code, bytesIn = 0)); send(INFER_DECLINE) }  // D3
    Accept        -> send(INFER_ACCEPT)
onBody(pin, aId, bytes):
    ledger.append(intentServed(aId, pin))                                   // D4: before the engine reads the body
                                                                            // (no counters on intent rows; bytesIn goes on the outcome)
    val rec = engine.run(bytes, kvPolicy = DISCARD_AT_END)                  // §5.4: no cross-attempt cache
    send(INFER_HEAD(rec.head)); stream(INFER_CHUNK*)
    ledger.append(outcomeServed(aId, rec))                                  // D5: before INFER_END is written
    send(INFER_END(rec.end))                                                // head, end and row come from one record
```

**Invariant 9 on the peer channel.** `INFER_HEAD` and `INFER_END` fields and the provider's outcome row are built from one record, just as echo headers and the ledger row are on the app-facing side.

**Crash semantics.** An intent row with no outcome row means "outcome unknown". The dashboard says, on the requester: "Offered to Deck; the content may have been delivered (this device stopped before the result)". On the provider: "Served a request from phone; this device stopped before finishing". The ledger never guesses the missing half.

**What the provider does not record.** If a provider dies after receiving `INFER_BODY` but before D4, it has no row. It received content (ingress) but sent nothing and persisted nothing. The requester's intent row, written before the content left, is the record of the transmission.

### 4.5 Worked scenarios

Phone A (app `com.example.notes`, request `r1`); providers Deck B and Dell C; cloud `openrouter`. Rows abbreviated as `phase/kind status code bytesOut bytesIn`.

**S1 — served by a peer, streaming, success.**

| Node | Rows |
|---|---|
| A | `intent/infer-sent a1 0 · bytesOut 0` → `outcome/infer-sent a1 200 · bytesOut 5120 · bytesIn 9876 · tokens 1300/400 (claimed)` |
| B | `intent/infer-served a1 0` → `outcome/infer-served a1 200 · bytesIn 5120 · bytesOut 9876 · tokens 1300/400` |

Echo to the app: `X-Asom-Served-By: peer:<B>/qwen3-8b`, `X-Asom-Egress: peer`.

**S2 — failover: B declines (hot), C accepts then drops before replying, cloud serves.**

| Node | Rows |
|---|---|
| A | `intent a2(B)` → `outcome a2 503 PEER_THERMAL bytesOut 0` · `intent a3(C)` → `outcome a3 502 PEER_LOST bytesOut 5120` · cloud attempt a4 (v1.1 intent/outcome, §4.11) `outcome a4 200 egress cloud` |
| B | `outcome/infer-served a2 503 PEER_THERMAL bytesIn 0` |
| C | `intent a3` → `outcome a3 499 REQUESTER_LOST · bytesIn 5120` (or no outcome, if C itself crashed) |

Echo: `X-Asom-Served-By: openrouter/...`, `X-Asom-Egress: cloud`. Reach went peer, then cloud (monotone). The content reached C and the cloud; B received metadata only. All of this is in A's rows; the header states the furthest reach, which is also the serving attempt's class.

**S3 — the app hangs up mid-stream.** A: `outcome a5 499`; A sends `CANCEL`. B: `outcome a5 499 CANCELLED`, engine stopped.

**S4 — the requester process dies after sending the body.** A: `intent a6` only ("outcome unknown"). B: `intent a6` → `outcome a6 499 REQUESTER_LOST`.

**S5 — the provider revokes the requester mid-stream.** B: `outcome a7 403 REVOKED_MID_STREAM`; `GOAWAY{revoked}`. A: `outcome a7 502 PEER_REVOKED`, and B leaves A's candidate set.

**S6 — control session.** A: `intent/control s1` at dial, `outcome/control s1 200 bytesOut 1400 bytesIn 3200` at close (5 minutes idle). B: the mirror image.

### 4.6 Echo headers, reach, and why the class is `peer`

- **E-1 Monotone reach** (§3.3): the headers for a request are built, as in v1, from the served attempt's record (non-stream) or its commit-time view (stream).
- **E-2 Error rows** keep v1's reach semantics: a routed error's `egress` is the furthest class that received *content* in this request, or `local` if none did. v1 already does this for cloud (`respondRoutedError`) [repo]; the mesh adds `peer` to the order `local < peer < cloud`.
- **E-3 Metadata-only offers do not raise reach.** A declined offer sent a model id, sizes and a deadline, not content. It has its own row (P1) with `bytesOut 0`, so the metadata leak is recorded, but `X-Asom-Egress` does not claim content went there.

**Why `peer` and not `lan`.** Tailscale relays traffic through its DERP servers "when a direct connection isn't possible", runs those servers in more than 20 countries, and the relayed traffic stays WireGuard-encrypted end to end [CV8]. A phone on cellular reaching the Dell at home over the tailnet is therefore not a LAN transmission in any sense a user would recognise, even though it is private and encrypted. `lan` would be the same class of falsehood the audit found. `peer` says what is true (a paired device of mine received it); `peerPath` says how, from the socket's own interface:

```
peerPath(socket) =
  OVERLAY  if socket.localInterface is the user-selected overlay interface
  LAN      if socket.localInterface.kind in {wifi, ethernet} and remote address is private/link-local
  (anything else is refused before TLS by trust.md §6.6, so no third value exists)
```

`peerPath` does not say whether an overlay path was direct or relayed; asom cannot observe that. The dashboard text for `overlay` is: "sent over your private overlay (encrypted; may pass through the overlay's relay servers)".

### 4.7 Linkage without content

| Id | Generated | On the wire | In rows | Purpose |
|---|---|---|---|---|
| `attemptId` | requester, 16 CSPRNG bytes per attempt | yes (offer onward) | both nodes | joins the two nodes' rows for one attempt |
| `requestId` | requester, 16 CSPRNG bytes per logical request | **never** (structural test: no frame type has a field for it) | requester only | groups the failover attempts of one request |
| control session id | dialer, 16 CSPRNG bytes, sent in `HELLO` | yes | both nodes, as `attemptId` on control rows | joins session interval rows |

- No id is derived from content, time, app identity or device identity.
- Two providers that each received one attempt of the same request cannot link them: the only common id (`requestId`) stays on the requester.
- **Rejected: any hash of the prompt in a row.** Short prompts are guessable, so a plain hash can be reversed by dictionary from an exported ledger. A keyed hash protects only while its key stays secret, and adds nothing the random id does not already give.

### 4.8 What "durable" means (and does not)

- **Android (Room).** Room enables full write-ahead logging by default on API 16+ devices that are not low-RAM [CV2]. Android's own guidance states that "when using WAL, by default every commit issues an `fsync`", and that with `SYNC_MODE_NORMAL` "a commit can return before the data is stored in a disk ... on loss of power or a kernel panic, the committed data might be lost ... If only your app crashes, your data still reaches the disk" [CV1]. **Claim made by this design: a committed row survives process death.** Survival of power loss or a kernel panic depends on the device's actual sync mode and on storage that honours `fsync` [CA1, CA2]. The ledger code must not set `SYNC_MODE_NORMAL`.
- **Desktop (JSON Lines).** Append one line, then `FileChannel.force(false)`, before proceeding. Without the force a returned `write` survives process death but not an OS crash.
- **Cost.** Two commits per attempt on each node that handles content. The design assumes this costs single-digit to tens of milliseconds on phone flash [CA3], small against local inference measured in seconds. The lab simulator (§6) makes it a parameter so `router.md` accounts for it.

### 4.9 Export

- **Per node, user-triggered, view-first**, exactly as v1: the dashboard (or `asom ledger export` on desktop, which prints the exact bytes and their SHA-256 and writes only after confirmation) shows the payload before it leaves. Each node exports only its own rows. No node can request, receive or export another node's rows (`trust.md` R7).
- **Format:** the v1 JSON array; every row gains the new keys (null on v1 rows).
- **Excluded from rows and exports:** device display names, IP addresses and SSIDs (they stay in the local peer registry), content, keys, the other node's app identities.
- **What the user sees** (dashboard grouping by `requestId`):

```
09:14  com.example.notes  auto  →  served by the cloud (openrouter/llama-3.3-70b)   2.9 s
       attempt 1  Deck  (overlay)  declined: too hot                    content not sent
       attempt 2  Dell  (overlay)  connection lost before a reply       content SENT (5.0 KB)
       attempt 3  openrouter       served                               content SENT (5.0 KB)   $0.00041 (usage)
```

- **Join.** Joining two nodes' exports on `attemptId` reconstructs a cross-device account with no content in either file. Example rows for S2's attempt `a3`:

```json
[{"ts":1790000000512,"callerPkg":"com.example.notes","requestedModel":"auto",
  "servedProvider":"peer:c4mhx2b7qkz9wd3e","servedModel":"qwen3-8b","egress":"peer",
  "bytesOut":5120,"tokensIn":null,"tokensOut":null,"costEst":null,"costBasis":"none",
  "latencyMs":1830,"status":502,"requestId":"t0Wf3yJq6bXcA1nRk8sPzQ","attemptId":"Zm9vYmFyYmF6cXV4MTIzNA",
  "phase":"outcome","peerNode":"c4mhx2b7qkz9wd3e","peerPath":"overlay","meshKind":"infer-sent",
  "bytesIn":0,"meshCode":"PEER_LOST"}]
```

```json
[{"ts":1790000000498,"callerPkg":"peer:rwlssqq3seza5mrw","requestedModel":"qwen3-8b",
  "servedProvider":"local","servedModel":"qwen3-8b","egress":"peer","bytesOut":0,
  "tokensIn":1300,"tokensOut":null,"costEst":null,"costBasis":"none","latencyMs":1790,"status":499,
  "requestId":null,"attemptId":"Zm9vYmFyYmF6cXV4MTIzNA","phase":"outcome","peerNode":"rwlssqq3seza5mrw",
  "peerPath":"overlay","meshKind":"infer-served","bytesIn":5120,"meshCode":"REQUESTER_LOST"}]
```

(Ids, tags and numbers are illustrative.)

- **Linkability, stated.** Anyone who holds both exports can link them. That is the purpose of the id, and exports are user-held.

### 4.10 Desktop ledger

`$XDG_STATE_HOME/asom/ledger.jsonl` (macOS: `~/Library/Application Support/asom/ledger.jsonl`), mode 0600, one `RouteRecord` JSON per line, append-only, monthly rotation to `ledger-YYYY-MM.jsonl`. **Not guaranteed:** Linux and macOS users often back up their home directories with tools asom does not control [CA4]. A backup tool that uploads the state directory is an egress asom cannot see. The documentation says so, and the CLI prints the ledger path on first mesh enable.

### 4.11 The v1 residual gap, and the v1.1 proposal

**Finding (I-4).** v1 writes a cloud row after the upstream call returns or fails. A process death while the call is in flight (low-memory kill, crash, force-stop) leaves no row for a prompt that was transmitted. This is the same class of defect the audit fixed for the enqueue-only sink, one step earlier in the pipeline.

**Proposal (v1.1 hardening "H1").** Apply the intent/outcome pair to every cloud attempt: append the intent row before `driver.chat` or `driver.embeddings` is called; the existing attempt and completed rows become outcome rows carrying the same `attemptId` and `requestId`. The contract change is CD-14, CD-16 and CD-17 (additive columns); no API change. This also builds, a version early and on the simpler cloud path, the machinery the mesh reuses.

**Gate for H1:** a JVM test with a driver that transmits and then blocks, and a sink backed by a file that the test reopens after abandoning the server, shows the intent row present and no outcome row; a second test shows the dashboard query labels it "outcome unknown". Device: force-stop the FGS during a slow upstream request and observe the row (NEEDS-DEVICE-VALIDATION).

### 4.12 Ledger laws (property tests; oracles written independently of the implementation)

| Law | Statement |
|---|---|
| L-L1 | For every attempt whose `INFER_BODY` was written to a socket, an intent row with that `attemptId` was committed before the first body byte was written |
| L-L2 | For every attempt the provider accepted and whose body reached its engine, a provider intent row was committed before the engine read the body |
| L-L3 | Every provider outcome row was committed before the `INFER_END` frame of that attempt was written |
| L-L4 | Every row with `bytesOut > 0` has `egress` equal to the class of the destination that received those bytes |
| L-L5 | For every request, `X-Asom-Egress` equals the maximum (`local < peer < cloud`) class over that request's attempts with content bytes sent, or `local` if none |
| L-L6 | Rows are never updated or deleted (verbose-table TTL purges are unaffected: that is a different table) |
| L-L7 | `requestId` appears in no encoded frame (structural test over every frame type) |
| L-L8 | No row contains a substring of the request body of length ≥ 8 (fuzzed bodies), a key, an IP address, or a device display name |
| L-L9 | Summing `bytesOut`, `bytesIn`, tokens and cost over all rows equals summing them over outcome rows only |
| L-L10 | `peerPath` equals the kind of the socket's local interface in the simulated network |
| L-L11 | Under injected process death at every step of §4.4, the set of rows on each node matches the table in §4.3 truncated at the death point: never a missing intent for content that moved, never an outcome for an attempt that did not reach it |
| L-L12 | `INFER_HEAD`/`INFER_END` metadata and the provider's outcome row agree field by field (Invariant 9 on the peer channel) |

### 4.13 What the ledger does NOT guarantee

- **A node's rows are that node's account, not proof of what the other node did.** A requester's row records what it sent and what the peer *claimed* (model, tokens). A provider's rows are written by the provider. A compromised or non-conforming peer can keep no rows, false rows, or copies of the content. The requester can enforce only its own side.
- **Power-loss durability is not claimed** (§4.8).
- **The ledger is not tamper-evident.** It is local, user-owned and writable by anything with the app's privileges or root. A hash chain could later make truncation evident; it is not proposed now.
- **"Outcome unknown" is honest but not informative.** Whether the content was delivered in a crash window cannot be known from the requester alone.

---

## 5. (d) Privacy and threat analysis: prompts on other devices

### 5.1 Assets and who sees what

| Asset | Examples |
|---|---|
| A1 Content | Prompts, system prompts, attachments passed as text, generated output |
| A2 Request metadata | Which app, when, which model, prompt and response sizes, timing, data class |
| A3 Device graph | Which devices the user owns, their names, their addresses, when each is on, charging, hot or in use |
| A4 Capability data | Device class, SoC, RAM, benchmark results, loaded models |
| A5 Secrets | BYOK keys, node keys, pairing secrets |
| A6 Ledger and verbose bodies | Rows on each node; opt-in bodies on the requester |

Per inference attempt, placed on own peer B over the overlay:

| Party | A1 content | App identity | Timing and sizes | A3 device graph |
|---|---|---|---|---|
| Requester A | yes | yes | yes | yes |
| Provider B (conforming) | yes, in memory only | no | yes | A's tag and display name; A's activity pattern |
| LAN observer | no (TLS) | no | encrypted sizes and timing | that A and B talk |
| Overlay coordination server | no | no | connection metadata | the full device list |
| Overlay relay (DERP), if relayed | no (WireGuard + TLS) [CV8] | no | sizes and timing | endpoints |
| Cloud provider | only if a cloud attempt happens | no | yes | no |

### 5.2 The data-classification model

**Destinations.** Every request has a **permitted destination set** `P ⊆ {T, O, X, C}`:

| Symbol | Destination |
|---|---|
| `T` | this device's local engine |
| `O` | an own-class paired peer's local engine |
| `X` | an other-owner peer (disabled in this roadmap: OD-C9) |
| `C` | a cloud provider under the user's key |

**Derivation.** Start from `{T, O, X, C}` and intersect with every applicable rule. The most restrictive rule always wins, and no rule can add a destination another removed.

| Rule (source) | Effect on `P` |
|---|---|
| Global "use my devices" off (default) | remove `O`, `X` |
| Cross-owner off (fixed by stop-line) | remove `X` |
| `X-Asom-Policy: local-only` or `model: local-only` (frozen) | `P ∩ {T}` |
| App mesh setting `off` | remove `O`, `X` |
| App mesh setting `own` | remove `X` |
| App cloud ban (v2.5 per-app table) | remove `C` |
| App marked "device-only" (per-app table) | `P ∩ {T}` |
| v3 secret detector hit, with the app rule "secrets stay on this device" | `P ∩ {T}` |
| `X-Asom-Fallback` present (frozen semantics) | `P ∩ {C}` restricted to the listed providers |
| `X-Asom-No-Train` | no effect on `P`; filters inside `C` as in v1 |
| Peer-specific: peer `routeEnabled` off, or the peer not PAIRED, or no `infer` granted | that peer is not a candidate (does not change `P`) |

**Named classes** (for UI and for vectors; derived from `P`, never stored separately):

| Name | `P` | Typical source |
|---|---|---|
| `device-only` | `{T}` | `local-only`; app marked device-only; secret hit |
| `own-devices` | `{T, O}` | App mesh `own` + cloud ban |
| `own-and-cloud` | `{T, O, C}` | App mesh `own`, no cloud ban (the default after "use my devices" is on) |
| `cloud-no-peers` | `{T, C}` | Mesh off (the v1 behaviour, and the default) |

**Defaults when the user switches on "use my devices":** apps with no per-app entry get mesh `own`; apps with a v2.5 cloud ban get mesh `off` until the user sets them (a cloud-banned app is the likeliest to be one whose owner wants it kept here); `local-only` requests are unaffected. The switch's consent screen says: "Requests from these apps may run on [Deck], [Dell]. The text of each request goes to that device and is processed there. asom on that device keeps no copy; a device that is not running unmodified asom could."

**Decision table (vectors: `authz/classification.json`).**

| Mesh global | App mesh | App cloud ban | Policy | Secret hit + rule | `P` |
|---|---|---|---|---|---|
| off | any | no | auto | no | `{T, C}` |
| on | own | no | auto | no | `{T, O, C}` |
| on | own | yes | auto | no | `{T, O}` |
| on | off | no | auto | no | `{T, C}` |
| on | own | no | local-only | no | `{T}` |
| on | own | no | auto | yes | `{T}` |
| on | own | no | auto + `X-Asom-Fallback` | no | `C` restricted to the list |

The provider is never told `P` or its name (X-10). It needs only `retain`, which is `none`.

### 5.3 Metadata that crosses to peers (limits on live state and offers)

| Field | Goes to | Allowed form | Never |
|---|---|---|---|
| Availability | own peers with `state` | enum (`serving`, `armed(reason)`, `draining`) | the foreground app, screen content |
| Thermal | own peers with `state` | 3-level band | raw sensor temperatures |
| Battery | own peers with `state` | charging flag + band (`≥80`, `50–79`, `20–49`, `<20`) | exact percentage over time |
| Queue | own peers with `state` | `0`, `1`, `2+` | which requester or app is queued |
| Loaded models | own peers with `state` | catalogue ids | custom model paths or file names |
| Manifest digest | own peers with `state` | `seq` + digest | — |
| User activity | only as a decline code (`PEER_USER_ACTIVE`) on an offer | boolean at offer time | a stream of presence data |
| Offer | the chosen peer | `attemptId`, `op`, concrete model, `stream`, content length, token estimate, `maxTokens`, `retain`, deadline | app identity, `requestId`, data class, the requester's other peers |

Every field in `STATE` is an integer or an enum (`platforms.md` C1). The receiver caches state only for its time-to-live and never ledgers its contents (the session row records bytes, not values).

### 5.4 What a conforming provider may do with peer content (normative)

| Item | Rule |
|---|---|
| Request and response bodies | Never persisted. The provider's verbose mode is suppressed for peer attempts (X-7) |
| KV and prefix cache | Discarded at the end of each peer attempt. No reuse across attempts, requesters or local apps |
| Logs and diagnostics | No content; the v1 redaction law applies |
| Ledger | Metadata rows only (§4) |
| Passive benchmark samples (v2 P6) | Performance numbers only; no requester id, no content |
| Public contribution (v2 P7) | Nothing derived from peer-served attempts |
| Forwarding | Never to another peer, never to the cloud (`trust.md` R6) |

**The cache rule costs performance.** Discarding the KV cache means a multi-turn conversation re-processes its whole history on every turn. That is the price of closing a demonstrated channel: timing reveals whether a prompt prefix was cached, and an ICML 2025 audit found cache sharing across users at seven API providers, including OpenAI, which could leak information about other users' prompts [CV6]. On a provider serving several paired requesters, or its own apps and a peer, a shared prefix cache recreates that channel between them. A later refinement may allow reuse keyed by (requester pin, an opaque per-app partition token), but not in mesh-1.

**Side finding for v2 (not a mesh item).** The same channel exists *on one device* between apps if the v2 engine shares a prefix cache across paired apps. The v2 design should partition any prompt cache per calling app. Flagged in the risk register (K-12).

### 5.5 Threats

| # | Adversary | Can | Limited by | NOT guaranteed |
|---|---|---|---|---|
| T1 | Compromised own peer (malware or root on the Deck) | Read and keep every prompt routed to it; return wrong or adversarial output (prompt injection aimed at the requesting app); claim a model it did not run; lie in its manifest and state | Nothing it holds is a key (R7); it never learns app identities; the requester's per-attempt rows record exactly what it was sent; claim-versus-observed tracking (`manifest.md` §11.5) bounds placement skew | Confidentiality of anything it was sent. Detection of the compromise |
| T2 | Stolen own peer, still running | Keep requesting from my providers; keep receiving work routed to it | Revocation on each node; mesh-1 phones are outbound-only, so a stolen phone receives no work | Timely revocation: exposure lasts until the user revokes it on every node (`trust.md` §9.3) |
| T3 | Another person using a shared own device (a family Deck) | Read the Deck's ledger (metadata of my phone's usage); with the same OS account, read the Deck's process memory or swap | Content is never persisted by the provider; ledger rows carry no content | Anything about a device the owner labels "own" but shares. "Own" is a declaration (`trust.md` §10.1). The Peers tab should ask "Do other people use this device?" and show the answer |
| T4 | LAN attacker | Observe sizes and timing; attempt MITM | Pinned mutual TLS; peer listener on private addresses only | Traffic analysis |
| T5 | Overlay operator or relay | See the device graph and connection metadata; deny service | Overlay is an underlay only; asom keys are pinned, so it cannot impersonate a peer (`trust.md` §6.5) | Metadata privacy from the overlay operator. Headscale reduces this to the owner's own server (`trust.md` [V10]) |
| T6 | Malicious paired app on the requester | Send its own content to my other devices (that is the feature); probe timing | Per-app mesh setting; no cross-attempt cache on providers (§5.4) | That an app does not misuse compute the user granted it |
| T7 | Malicious app on the provider | With a v3 `ledger-read` token, read served rows (metadata of my phone's activity); run local requests to probe the engine | No content in rows; KV discarded per attempt | The metadata. v3's `GET /admin/ledger` design should exclude `infer-served` rows unless the user opts in (a v3 design note, not a delta now) |
| T8 | Another paired requester on the same provider | Probe cache timing for my prompts | §5.4 cache rule | Timing channels in the engine other than the prefix cache |
| T9 | Forensic access to a provider after the fact | Recover fragments from swap, hibernation images or crash dumps | No deliberate persistence | Absence of content in swap or crash dumps: the model and KV are in ordinary memory, and platforms may page or dump it [CA5] |
| T10 | The user's own configuration | Publish the listener through overlay port publishing; label a friend's device "own" | Documentation; IC-1's wording; consent copy | Anything asom cannot observe |
| T11 | llama.cpp RPC or any tensor-level sharding, if later adopted | An unauthenticated RPC server receives weights and activations | Parked (§1.6 D5). Upstream warns it is "fragile and insecure" [CV7] | — |

### 5.6 The weakest-link rule

A prompt placed on a peer is only as confidential as the least protected device that handled it. The requester cannot verify the provider's disk encryption, OS updates, account sharing or key storage tier; all are self-reported unless `manifest.md`'s attestation says otherwise. The Peers tab therefore shows, per peer: key storage tier ("self-reported" or "attested"), "Used by other people: yes / no / not answered", and "Allowed for: all apps with mesh on / none". An app marked device-only never reaches a peer, whatever the peer claims.

### 5.7 The quiescence law

A node initiates a peer connection only when:
1. a local request is pending whose `P` contains `O`; or
2. an asom screen that shows peer status is open; or
3. the user started a peer operation (pairing, sharing a report, revoking); or
4. an in-flight attempt is finishing.

Sessions close after 5 minutes without an open stream (`trust.md` §3.3). Providers accept inbound connections while SERVING; they initiate none.

**Consequences.** A phone with mesh on and no apps using asom sends nothing, which answers Invariant 1's "no background or silent transmission" directly rather than by argument, and saves battery. `router.md` must work with live state that is absent or stale at request time. The offer and accept exchange is the ground truth at decision time, and cached state only orders the offers.

### 5.8 What leaks regardless of policy

- To a conforming provider: that the requester is active, when, and how much; which model; request and response sizes; the requester's device name (own class only).
- To the overlay operator: the device graph and connection times.
- To a LAN observer: that two devices talk, when, and how much.
- To whoever holds two nodes' exports: the linkage between them (by design).

### 5.9 What this analysis does NOT guarantee

- **The requester can enforce only what it sends, and to whom.** Every provider-side rule (§5.4) holds only for a conforming, uncompromised provider.
- **Classification limits exposure; it does not find sensitive content.** The v3 detector is regex-grade at first, and until v3 there is no detector at all: sensitivity comes only from per-app settings.
- **"Own" is a label the user applies**, not a verified fact.
- **Discarding caches does not erase memory.** Freed memory, swap and crash dumps may hold fragments.
- **Traffic analysis is not addressed.**

---

## 6. (e) Phased plan

### 6.1 Build now versus must wait

| Can be built now (pure JVM; no frozen-contract change; no shipped-module change; no Android) — **only with OD-C4(i)** | Must wait |
|---|---|
| `conformance/` layout, vector envelope and runner, plus vectors that pin **frozen v1 behaviour** (echo headers from `RouteRecord`, `formatUsd`, the error envelope) (`platforms.md` S0) | Anything in `:core:*`, `:server` or the Android modules (frozen v1 in review; O1) |
| Manifest library: JCS integer profile, DSSE/ES256, verifier with typed rejects, public derivative, text renderer (`manifest.md` §21) | Signing with a real node key on a device (needs the key design and device validation) |
| Benchmark core: statistics, renderer, projection, run-plan interpreter and governor FSM against a fake engine (`benchmark.md` §21) | Any real measurement (needs the v2 engine; O3) |
| Mesh policy: the §5.2 classification, `meshEligibility`, the provider decision table, the quiescence predicate, as pure functions with exhaustive cross-product tests | Per-app settings UI (Android; v2.5 or a minimal toggle) |
| Ledger model: `LedgerRowV2`, intent/outcome state machine, laws L-L1 to L-L12 with a crash-injecting sink, export v2 renderer and join tool | Ledger columns in Room, JSONL writer in a shipped desktop node |
| Router simulator (`router.md`), depending on `:core:routing` read-only | Any router change in `:core:routing` |
| Peer protocol pieces: frame codec, `verifyPeerChain`, peer registry FSM, pairing proof and SAS, with a loopback-only two-node test harness (`trust.md` §17) | Any listener on a non-loopback address; any shipped peer code |
| — | Freeze-test pins (§3.6 step 1): a v1 test change, so it waits for v1.1 |

### 6.2 Lab isolation rules (what makes "now" safe)

1. New modules live under `lab/` with Gradle paths `:lab:*`. Their aggregate task is `labTest`, run by a **separate CI job**. `jvmTest` and the existing two CI jobs are not touched, so lab work can never break the v1 build path (CLAUDE.md: "this must never break").
2. A Gradle check (`:lab:isolationCheck`) fails if any module outside `lab/` depends on a `:lab:*` module.
3. Lab modules are pure JVM: no `android.*` imports, no Android plugin.
4. No lab test binds anything other than `127.0.0.1`, and a lab module has no `main` that opens a socket.
5. Lab types never reuse frozen names: there is no `Egress.PEER` in `:core:contract` until IC-2 is signed; the lab defines its own `LabEgress`.
6. Promotion from `:lab:x` to a shipped module happens only at the owning version's entry, through the normal gate, with the owner's decision record cited.

### 6.3 Phases and gates

Gate style follows brief §11–§12: real command output pasted into `PROGRESS.md`; `NEEDS-DEVICE-VALIDATION` and `NEEDS-OWNER-VALIDATION` stay open until the owner confirms; `BLOCKED(<reason>)` and stop if blocked.

| Phase | Scope | Entry criteria | Gate |
|---|---|---|---|
| **P-0 Rulings** | The owner answers §8; the owner (not a session) commits the roadmap and brief text changes | This design synthesised | Owner decision record committed, quoting each decision; `NEEDS-OWNER-VALIDATION` by definition |
| **L0 Lab** | §6.1 left column, as `:lab:conformance`, `:lab:manifest`, `:lab:bench-core`, `:lab:mesh-policy`, `:lab:ledger-model`, `:lab:mesh-sim`, `:lab:mesh-proto` | OD-C4(i) granted. Nothing else | `./gradlew labTest --rerun-tasks` real output with per-suite counts; `./gradlew jvmTest --rerun-tasks` real output showing the same test counts as the last recorded v1 baseline; `./gradlew :lab:isolationCheck` output showing zero edges from shipped modules; the independent (non-JVM) checker's output agreeing with the JVM on the manifest accept/reject vectors; all of it runnable on the Deck with no Android SDK |
| **V1-close** | Existing: `QA_V1.md`, P5–P7 checklists, audit re-run, P6 RemoteAsom re-run, P4 real-key smoke | Owner hardware | As already defined in `PROGRESS.md`; `NEEDS-DEVICE-VALIDATION` |
| **v1.1** | Roadmap v1.1, plus H1 (intent rows for cloud attempts, §4.11) and H2 (freeze pins, §3.6) if OD-C6 approves | Roadmap v1.1 entry (O2) | Roadmap gates; H1's JVM crash test output; `ContractFreezeTest` output showing the new pins; H1 device check `NEEDS-DEVICE-VALIDATION` |
| **v2** | Roadmap v2, with: P6 = `:lab:bench-core` promoted to `:bench-core`; plain-text report; signed report and file export under completed Amendment 1 (OD-C2); P7 with class `contribution`; desktop engine build (v2 P1 already requires linux x86_64) | Roadmap v2 entry; OD-C2, OD-C10 | Roadmap gates, plus: manifest and benchmark vector families green in CI on JDK 17 and 21; an exported signed report verifies with the independent checker; a unit test proves the P7 payload builder cannot emit a key, content or fingerprint field (roadmap P7 gate) |
| **D-SEQ** | The owner decides whether mesh-1 comes before v2.5 and v3 | v2 shipped | Decision recorded |
| **M1 mesh-1** | Linux and Deck providers (asom-desktop: provider plus owner CLI over CD-24; no vault; no local apps); Android requester, outbound-only; own devices only; overlay-first, LAN-direct optional; QR pairing; scopes `infer`, `state`, `manifest`; append-only ledger on both nodes; class `peer`; CD-1, CD-2, CD-4; quiescence | v2 shipped on Android and desktop; Amendment 2 package signed (OD-C1); per-app mesh setting present (v2.5 table, or the minimal toggle if D-SEQ puts mesh first); tailnet exists (roadmap v4 entry) | (1) `./gradlew jvmTest labTest` plus the two-node loopback integration suite, real output. (2) All conformance families green on JVM 17 and 21. (3) Deck and Dell over the tailnet: a scripted run of at least 20 requests including a forced decline, a provider killed mid-stream, and a revoke; both ledgers exported and joined; every intent has an outcome except where the script killed a process, and those show "outcome unknown" (`NEEDS-OWNER-VALIDATION`; paste both exports and the join output). (4) RedMagic to Deck, same script, plus evidence that the phone has no listening socket beyond `127.0.0.1:11435` (`NEEDS-DEVICE-VALIDATION`). (5) Quiescence: 30 minutes idle with mesh on and no app activity: no new peer rows on the phone and no connection attempts in a packet capture on the Deck (`NEEDS-OWNER-VALIDATION`). (6) A revoked phone cannot reconnect and cannot un-revoke itself (`NEEDS-DEVICE-VALIDATION`) |
| **M2** | macOS provider (packaging, notarisation); Android as a provider (charging-only; the phone gets a peer listener under IC-1); standalone benchmark shells (desktop CLI; Android benchmark APK) | M1; OD-C3 part A if any macOS GUI; `platforms.md` OD2; before any targetSdk 37 bump, a device test that `127.0.0.1:11435` still works for paired apps (Android 17's local-network permission covers accepting TCP, and its documentation does not address loopback [CV4]) | Per `platforms.md` S3 and S4 gates; `NEEDS-DEVICE-VALIDATION` / `NEEDS-OWNER-VALIDATION` |
| **M3** | iOS standalone benchmark app: produces and verifies manifests; exports the report; no mesh, no keys, no server | OD-C3 part A (IC-7) approved; Apple commitment (`platforms.md` OD2); manifest and benchmark vectors frozen | `swift test` output for all required vector families; a report exported from the iPhone verifies with the JVM verifier and the independent checker; on-device Secure Enclave signing (`NEEDS-DEVICE-VALIDATION`) |
| **M4** | iOS direct requester (OD-C8 (b)): in-app SDK, offers to own providers, no delegation | M3; M1; OD-C8; OD-C3 part B if the SDK's `CloudOnly` tier holds keys | iPhone app streams from the Deck over the tailnet; provider off → graceful fallback, mirroring the v1 P6 uninstall test (`NEEDS-DEVICE-VALIDATION`) |
| **M5** | iPad foreground-only provider | M4; `platforms.md` OD5 | Per `platforms.md` S7 |
| Parked | Delegated routing; other-owner peers; D3–D5; mDNS; locator and revocation hints; `/admin/manifest` | Each needs its owner decision and, for most, a design session | — |

---

## 7. (f) Risk register

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| K-1 | **Invariant erosion by piecemeal reinterpretation.** Five sections each found a "small" clause; together they rewrite Invariants 1, 2, 3, 4, 5, 7 and 8 | critical | §2's single classification test and three explicit packages; nothing scheduled on an unsigned package; the owner's decision record cited in every commit that changes a pinned name |
| K-2 | **Cross-node ledger under-reports**, repeating the audit's most serious finding | high | Write-ahead intent rows (P3); laws L-L1 to L-L12 with independent oracles and crash injection; M1 gate (3) joins real exports |
| K-3 | **A stated class is false**: `lan` for relayed overlay traffic; `local` for a request that reached a peer | high | Class `peer` plus `peerPath` (X-4); E-1 to E-3; L-L4, L-L5 |
| K-4 | **Building on an unvalidated base.** v1 has never run on the owner's device since the audit; P6 results were invalid | high | O1; V1-close precedes every shipped phase; lab work is isolated |
| K-5 | **Scope explosion.** Five platforms, three shells, a protocol and a manifest for a solo owner | high | mesh-1 is one direction (phone to desktop), one platform pair, own devices only; iOS last; everything else parked with triggers |
| K-6 | **Prompt exposure on a weaker or shared device** | high | §5.2 per-app control; §5.6 per-peer disclosure; device-only never leaves |
| K-7 | **Compromised or stolen own peer** reads prompts | high (impact) / low (likelihood) | R6, R7, per-attempt rows, revocation; residual stated (T1, T2) |
| K-8 | **Existing v1 ledger gap** (I-4) is read as a mesh-only concern and left open | medium | H1 in v1.1, independent of the mesh |
| K-9 | **Freeze tripwire missing** for `Egress` and headers (I-3) | medium | §3.6 step 1 before any mesh code |
| K-10 | **Listener made public by the user's overlay configuration** (Funnel-type features [CV5]) | medium | IC-1 wording; documentation; the listener still requires a pinned key |
| K-11 | **Android 17 local-network permission** affects loopback or overlay behaviour (undocumented [CV4]) | high if it affects loopback (probability unknown) | No targetSdk 37 bump without the device test in M2's entry; overlay treatment tested at M1 |
| K-12 | **Prompt-cache timing channels** between requesters on a provider (mesh) or between apps on one device (v2) [CV6] | medium | §5.4 discard rule (mesh); per-app partition flagged to the v2 design |
| K-13 | **Stop-line creep** through cross-owner sharing, discovery or delegation | medium | OD-C8, OD-C9, OD-C11; IC-4's last sentence makes delegation a visible change |
| K-14 | **Swift and JVM drift** (KMP ban) | medium | G7; conformance families; independent checker (`platforms.md` §8) |
| K-15 | **Consuming apps misread `peer`** as local | low-medium | §3.5 documentation; "anything other than `local` left this device" |
| K-16 | **Durability overclaimed** (power loss) | low-medium | §4.8 states the claim as process death only |
| K-17 | **Desktop ledger uploaded by user backup tools** | low-medium | §4.10 documentation; path printed at first enable |
| K-18 | **Signed report read as "verified true"** by third-party subscribers | high (`manifest.md` risk) | `manifest.md` LM-6 (verification block computed by the viewer); the export screen's wording |
| K-19 | **Metadata-only offers leak app sensitivity** | low | X-10: no data class in offers |

---

## 8. Owner decisions (consolidated)

Ordered by what they block. Sibling decision ids that each one subsumes are listed so the synthesis can deduplicate.

| ID | Question | Options | Recommendation | Why only the owner | Blocks |
|---|---|---|---|---|---|
| **OD-C1** | Is the Amendment 2 package (IC-1 Invariant 2 text, IC-2 class `peer`, IC-3 peer-control clause with quiescence, IC-4 clarification) the sanctioned second amendment, or partly a third? Strike roadmap §7's "No other invariant changes"? | (a) One package, as §2.3; (b) Invariant 2 only, the rest escalated as a third; (c) Invariant 2 plus IC-2 and IC-4, and IC-3 replaced by §2.3.3's fallback (manifests shared per tap, no live state) | **(a)**. The roadmap's own delta registry anticipates the class and the capability exchange; the "No other invariant changes" sentence is the inconsistency (I-1), not the intent | Roadmap §13 reserves third amendments to owner escalation; only the owner can say what the sanctioned amendment covers. Subsumes `trust.md` OD-1, `manifest.md` OD-M1, `platforms.md` OD7 | M1 |
| **OD-C2** | Complete Amendment 1: class `contribution` (IC-5), and user-directed export of the benchmark report (IC-6), including or excluding the signed, device-identified form | (a) IC-5 plus anonymous exports only; (b) (a) plus the signed form with the fingerprint warning; (c) IC-5 only, no exports | **(b)**. Requirement R3e needs a verifiable file for "any app or device", which only the signed form provides; the user sees the linkability warning first. If the owner judges (b) a third amendment, choose (a) and serve R3e only between paired asom nodes | Invariant 1's export instances are enumerated and counted by roadmap §13. Subsumes `manifest.md` OD-M2, `benchmark.md` BD3, BD4 | v2 P6/P7 |
| **OD-C3** | Platform equivalence (IC-7 part A; IC-8 part B): a genuine third amendment | (a) Approve A when committing to Apple; defer B until a phase needs it; (b) approve A and B now; (c) no non-Android platforms beyond the roadmap's CLI desktop | **(a)** | Third amendment by §2.1. Subsumes `platforms.md` OD3 | M3 (A); M4 and any desktop vault (B) |
| **OD-C4** | Sequencing: (i) authorise L0 now as an exception to "do not start those versions"; (ii) after v2 ships, may mesh-1 precede v2.5 and v3; (iii) a parallel mesh track before v2 | (i) yes / no; (ii) yes / no; (iii) yes / no | **(i) yes** (non-shipping, isolated, produces the spec every later step needs); **(ii) yes** (own-device mesh has no technical dependency on v2.5 or v3, §1.7); **(iii) no** (the Android requester needs v1 validated anyway; a solo owner should not run two tracks) | CLAUDE.md and roadmap §0/§2 are owner rules. Subsumes `platforms.md` OD4, `benchmark.md` BD2 | L0; M1 timing |
| **OD-C5** | Egress class name | (a) `peer` + `peerPath`; (b) keep `lan`, defined to include overlay paths that may cross the public internet | **(a)** | Renames a roadmap provisional item; the audit's truth rule is the owner's standard | M1 |
| **OD-C6** | Ledger shape and the v1 gap | (a) Append-only intent/outcome rows (§4), applied to cloud attempts in v1.1 (H1); (b) the same, mesh only; (c) `trust.md`'s mutable update-by-id | **(a)** | Changes brief §9's table and v1.1's scope | v1.1; M1 |
| **OD-C7** | Control-plane ledger granularity: per-session interval rows, with individual rows for manifests | (a) Yes; (b) one row per message | **(a)** | An interpretation of Invariant 3's "**every** network event writes a ledger row" | M1 |
| **OD-C8** | iOS as a requester | (a) None in mesh-1; (b) later, a direct requester (offers to own providers; no relay; fits Amendment 2); (c) delegated routing through a home node (IC-9, third amendment; H becomes a relay; needs `X-Asom-Node` and a max-reach echo rule) | **(a) then (b)**; not (c) | Changes the product shape on iOS and possibly Invariants 2 and 5. Resolves `platforms.md` C6 vs `trust.md` R6 | M4 |
| **OD-C9** | Other-owner peers (household, friends) | (a) Not within this roadmap; (b) designed but disabled; (c) allowed with the v3 firewall | **(a)** | Roadmap §11 says "owner-controlled devices"; only the owner can move the stop-line. Subsumes `trust.md` OD-2 | — |
| **OD-C10** | Roadmap v2 P6 wording: "the benchmark app is a *feature*, not a separate app" | (a) "One benchmark subsystem, in the daemon and as thin standalone shells over the same core"; (b) standalone on iOS only; (c) keep | **(a)** | Frozen roadmap text. Same as `benchmark.md` BD1 | v2 P6; M3 |
| **OD-C11** | Surface trims, as one ruling: withdraw `X-Asom-Node`; reject `own-devices` and `NO_ELIGIBLE_NODE`; defer `/admin/manifest`, mDNS, locator hints, revocation hints and `revoke-hint`; drop `retain: owner-verbose` and the offer's `dataClass` | (a) Accept all; (b) accept with named exceptions | **(a)** | Withdraws a roadmap provisional item and declines sibling proposals. Subsumes `trust.md` OD-3, `manifest.md` OD-M5 | M1 |
| **OD-C12** | Adopt the quiescence law (§5.7) as normative | (a) Yes; (b) allow background polling of live state | **(a)** | It is the concrete reading of Invariant 1 for the mesh, and it constrains `router.md` | M1 |

Not owner decisions (engineering defaults, reversible): the §5.4 cache-discard rule; the §4.8 durability wording; the lab module names; the `attemptId` encoding.

---

## 9. Verified facts and assumptions

### 9.1 Verified in this session

| ID | Fact | Source | Confidence |
|---|---|---|---|
| CV1 | Android: "When using WAL, by default every commit issues an `fsync` to help ensure that the data reaches the disk." With `SYNC_MODE_NORMAL`, "a commit can return before the data is stored in a disk. If a device shutdown occurs, such as on loss of power or a kernel panic, the committed data might be lost ... If only your app crashes, your data still reaches the disk." | developer.android.com/topic/performance/sqlite-performance-best-practices | high |
| CV2 | "For apps using Room, full write-ahead logging mode (not Compatibility WAL) is enabled by default" on API 16+ devices not categorised as low-memory | source.android.com/docs/core/perf/compatibility-wal | high |
| CV3 | SQLite: "A transaction committed in WAL mode with synchronous=NORMAL might roll back following a power loss or system crash." | sqlite.org documentation as quoted at avi.im/blag/2025/sqlite-fsync (sqlite.org itself returned 503 during this session) | medium (secondary quotation) |
| CV4 | Android 17 makes local-network protection mandatory for apps targeting SDK 37+. It covers outgoing TCP, **accepting incoming TCP**, UDP, mDNS and `NsdManager`. The page does not mention loopback or VPN interfaces | developer.android.com/privacy-and-security/local-network-permission | high (for what the page says and omits) |
| CV5 | Tailscale Funnel "lets you route traffic from the broader internet to a local service"; it listens only on ports 443, 8443 and 10000 and needs a `funnel` node attribute in the tailnet policy; Serve shares within the tailnet only | tailscale.com/kb/1223/funnel | high |
| CV6 | "Auditing Prompt Caching in Language Model APIs" (Gu et al., ICML 2025): a cache hit is detectable from response time; the audit detected cache sharing across users in seven API providers, including OpenAI, creating potential leakage about other users' prompts | arxiv.org/abs/2502.07776; proceedings.mlr.press/v267/gu25b.html | high |
| CV7 | llama.cpp RPC: "This example and the RPC backend are currently in a proof-of-concept development stage. As such, the functionality is fragile and insecure. Never run the RPC server on an open network or in a sensitive environment!" It distributes model weights and KV cache across local and remote devices; the README documents no authentication or encryption | github.com/ggml-org/llama.cpp `tools/rpc/README.md` | high |
| CV8 | Tailscale DERP servers relay traffic "when a direct connection isn't possible"; relayed traffic stays WireGuard-encrypted and DERP cannot decrypt it; Tailscale runs DERP servers in 20+ countries | tailscale.com/kb/1232/derp-servers | high |
| repo-1 | v1 appends a cloud ledger row only after the upstream call returns or fails; `LedgerDurabilityTest` asserts commit before the response and survival of a client hang-up, not a row before transmission | `server/.../RoutePipeline.kt`, `AsomServer.kt`, `LedgerDurabilityTest.kt` at `f51dae9` | high |
| repo-2 | `Egress` is commented "Exhaustive — no additions (invariant)"; `ContractFreezeTest` does not pin `Egress` or `AsomHeaders` | `core/contract/.../RouteRecord.kt`, `ContractFreezeTest.kt` | high |
| repo-3 | Routed error rows already use reach semantics (`cloud` if any attempt transmitted, else `local`) | `AsomServer.kt` `respondRoutedError` | high |
| repo-4 | The client SDK carries `egress` as `String?`; `CLIENT_API.md` documents `"local" \| "cloud"` | `InferenceClient.kt`, `AsomChat.kt`, `docs/CLIENT_API.md` | high |
| repo-5 | `android:allowBackup="false"` on `:app` and `:sample-client` | `AndroidManifest.xml` files | high |

Relied on from siblings, not re-verified here: `platforms.md` [V01] (iOS background execution), [V02] (Local Network privacy, VPN exemption), [V12] (Android 17 permission); `trust.md` [V10] (Headscale); `manifest.md` [MV9] (App Attest contacts Apple).

### 9.2 Assumptions (not verified)

| ID | Assumption | Load-bearing? | How to settle |
|---|---|---|---|
| CA1 | Shipping Android devices keep the framework default (an `fsync` per WAL commit); OEMs could change it | Only for the power-loss claim, which is not made | Device check of the effective `PRAGMA synchronous` on the RedMagic |
| CA2 | Target devices' storage honours `fsync` | Same | Not testable in software; stated as a limit |
| CA3 | Two commits per attempt cost single-digit to tens of milliseconds on phone flash | Sizing only | Measure at M1 |
| CA4 | Desktop users commonly back up home directories, including state directories, with tools asom does not control | Motivates documentation only | — |
| CA5 | Swap, hibernation images and crash dumps (for example Android tombstones) can contain process memory fragments | Motivates the T9 limit | — |
| CA6 | Consuming apps may branch on `local` versus `cloud` in `X-Asom-Egress` | Motivates §3.5 | Survey first-party apps |

---

## 10. What this section does NOT guarantee

- **Classifying a change as "part of Amendment 2" or "completing Amendment 1" is a recommendation.** Only the owner's ruling makes it so. Until then every such item is unapproved.
- **The contract registry is minimal for the phases proposed**, not for every future the requirements might imply. D3–D5, delegation and cross-owner sharing will each need their own deltas.
- **The ledger records each node's own account.** It proves nothing about what another node did, it is not tamper-evident, and it claims durability against process death only.
- **The privacy model limits where content is sent.** It cannot detect sensitive content without v3, it cannot verify a peer's conduct, and it does not address traffic analysis.
- **The quiescence law removes background transmission by requesters.** It does not remove the provider's listener (which is the amendment's purpose) or the overlay's own background traffic, which is the overlay's, not asom's.
- **The phased plan's gates prove behaviour on the owner's devices under scripted conditions.** They do not certify behaviour on other hardware, OS versions or networks.
- **Verified facts are verified as of 2026-09-29** against the cited pages. Platform policies change; re-verify at the start of each phase, as roadmap §1 already requires for its planning assumptions.
