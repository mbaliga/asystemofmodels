# asystemofmodels — Roadmap Brief: v1.1 → v4 ("asom")

**Status:** FROZEN companion to `ASOM_BUILD_BRIEF.md` (v1) · **Date:** 2026-07-08
**Governs:** everything after v1, up to and including the LAN/multi-node version. **Excludes by owner directive:** the Layer-3 global/decentralized (web3/DePIN) inference network. See §11 — nothing in this document may build toward it.

**Resolution grades** — be honest about what each section is:
- **EXECUTION-GRADE** — cold-executable by a Claude Code session, like the v1 brief.
- **CONTRACT-GRADE** — public contract, invariants, and gates are locked; implementation detail is resolved at execution time.
- **DIRECTION-GRADE** — direction and boundaries locked; **requires a design session before execution**. Do not cold-execute.

---

## 0. How to use this document

Read `ASOM_BUILD_BRIEF.md` first; its §1 invariants, §4 dependency law, §12 anti-overclaim discipline, and §13 do-not list apply to every version here unless a section explicitly amends them (only §7/v4 does, once). Each version has **entry criteria** — do not start a version until they are met. Versions are strictly sequential. Where `OWNER-FILL` or `NEEDS-OWNER-VALIDATION` appears, stop and ask; never invent.

---

## 1. Inherited context & planning assumptions

This roadmap consolidates decisions from the original router design sessions (the pre-rename "Urbana" daemon brief) and the three-layer distributed-inference research. Items deferred out of v1 that this document now places: the **rate-limit accountant** (proactive RPM/TPM/RPD window accounting), the **three-stage semantic router** and **`Route-NL`**, **web-origin callers** (CORS + origin allowlist), **NL loops** (closed node registry, NL→DAG compiler, cyclic DAG runner, `loop:<name>`), the **Tailscale-bind toggle**, and the **privacy-engineering layer** (on-device PII redaction with reversible placeholders, per-app policy table) — the last recovered from the Layer-1 research and never previously scheduled.

**Planning assumptions (Jan-2026-era; re-verify at the start of each version):**
- On-device envelope on Snapdragon-flagship class: roughly 10 tok/s for ~3B models, ~5 tok/s for ~8B; sustained inference thermally throttles (prime-core frequency drops sharply as SoC passes ~40 °C within minutes) and costs real battery. Interactive use = 1–8B; frontier stays cloud. Sparse-inference systems (PowerInfer-2 class) are the horizon, not the plan.
- Android platform law: `dataSync`/`mediaProcessing` FGS types are capped (~6 h) — this is why v1 chose `specialUse`. The Phantom Process Killer caps child processes — therefore the engine runs **in-process** (JNI in the daemon), never as a helper process. Binder transactions are ~1 MB-capped — AIDL stays **control-plane only**; all data-plane traffic is HTTP/SSE.
- Multi-device reality (for v4): pipeline/RPC sharding across consumer devices adds **capacity, not speed** — single-stream generation is sequential through layers, so slow links slow it further. v4 therefore routes **whole models to single nodes**; sharding is a parked experiment.
- Reference points: AICore is the canonical vendor analog and is deliberately narrow (single vendor model, foreground-only, no cloud routing, no user audit) — asom's differentiation is exactly those four gaps. Closest commercial analog is an in-process SDK, not a cross-app service; the cross-app sovereign daemon remains green-field.

---

## 2. Version ladder

| Version | Name | Theme | Grade | Entry criteria |
|---|---|---|---|---|
| v1.1 | The Accountant | Proactive quota accounting + hardening | CONTRACT | v1 P8 gate passed; RedMagic validation complete |
| v2 | The Engine | Local generation + embeddings, governors, on-device benchmarking | **EXECUTION** | v1.1 shipped; catalogue v2 fields live (§9) |
| v2.5 | Semantic Routing | 3-stage router, `Route-NL`, per-app policy | CONTRACT | v2 stable; embedding model present on device |
| v3 | Loops & the Egress Firewall | NL→DAG loops, PII redaction, web callers, scopes | CONTRACT | v2.5 shipped |
| v4 | The Literal Hotspot | Multi-node over private overlay (Layer 2) | DIRECTION | v3 shipped; **design session held**; tailnet exists |

---

## 3. v1.1 — The Accountant (CONTRACT-GRADE)

Purpose: make free-tier and rate-limited keys first-class citizens — the router should never *cause* a 429 it could have predicted.

- **Rate accountant:** Room-backed RPM/TPM/RPD windows per provider with scheduled resets, fed by catalogue `rate` fields (+`tpm`, §9). Pre-flight check removes exhausted providers from the candidate set silently; only when **all** candidates are exhausted return new error `QUOTA_EXHAUSTED` (with soonest-reset hint). Reactive cooldown from v1 §7 remains as the backstop.
- **Test law (carried from the original brief):** a forced 429 on the first of two providers serving the same model must transparently re-route to the second; an exhausted RPD counter must block selection before the wire.
- **Hardening:** FGS restart choreography (daemon recovers cleanly after process death; tokens and cooldown state survive), instrumented tests for `:vault` and `:pairing`, secret-redaction audit re-run, F-Droid packaging groundwork.
- Contract delta: error `QUOTA_EXHAUSTED` only. No new endpoints or headers.

---

## 4. v2 — The Engine (EXECUTION-GRADE)

Fills the `:inference` stub: local generation and embeddings behind the existing `:core:inference-api`, with the governors that make on-device inference *honest* rather than merely possible. `local-only` becomes truthful; `auto` gains local preference.

**Non-negotiables for this version:** engine runs in-process (JNI, never a child process); one owner of inference = **single-flight generation queue**; every cloud failover is visible (header + ledger), never silent; `local-only` never silently falls to cloud — it queues, then errors typed.

### P-ladder

- **P0 — Native toolchain.** NDK (pin r27+), CMake, `abiFilters arm64-v8a` only. CI native build with cache. Engine **conformance suite** written once against `:core:inference-api`, runnable against `NoopEngine` (JVM) and real engines (device). *Gate:* CI green with native build.
- **P1 — llama.cpp vendored.** Pinned commit (submodule or vendored), minimal JNI surface: `load/unload/tokenize/generate(stream)/embed/cancel`. CPU build also compiles for linux x86_64 so CI runs a tiny-GGUF generation test (model fetched at CI time from a pinned URL + sha256 — never committed). *Gate:* CI tiny-model generation passes; conformance suite green on JVM.
- **P2 — Model manager wiring.** Load from the daemon's own `filesDir/models/…` (the `/proc/self/fd` trick remains a *consumer-side* pattern); RAM guard (model bytes + KV budget vs `availMem`) → typed `MODEL_OOM`; LRU unload; per-model pin honored; chat-template resolution (GGUF-embedded template preferred, catalogue `family` fallback). *Gate:* load/unload/LRU unit + device checklist.
- **P3 — Generation service.** Single-flight FIFO queue with round-robin admission per app; SSE streaming in OpenAI chunk shape; client disconnect aborts generation (cancellation verified); context accounting → typed `CONTEXT_OVERFLOW` (no silent truncation). *Gate:* two paired apps streaming concurrently interleave fairly; disconnect frees the engine within 1 s.
- **P4 — Governors.** Thermal: `getThermalHeadroom` polling + status listener → states `RUN / QUEUE / HOLD`. Policy matrix: `local-only` → queue, then typed `THERMAL_HOLD` after a configurable wait; `auto` → cloud failover, **ledgered and echoed** via new response header `X-Asom-Failover: thermal|oom`. Battery floor (default 20%, setting). Governor transitions go to local diagnostics (not the ledger — the ledger is egress). *Gate:* simulated headroom exhaustion produces the matrix behavior exactly.
- **P5 — Embeddings.** Embedding-model kind in the engine; `/v1/embeddings` served locally when a model is present and policy allows; batching. *Gate:* conformance + parity test vs a cloud embedding on cosine sanity checks.
- **P6 — Truth, UX & on-device benchmarking.** Capabilities flip: `hasLocalEngine:true`, `loadedModels[]`. Engine settings tab (threads, GPU layers, default ctx, battery floor, thermal policy). **Recursive passive benchmarking — the benchmark app is a *feature*, not a separate app:** every real local inference is a benchmark sample. This is not a new subsystem — it is the generalization of the metric the router already keeps: extend the `fastest`-policy latency EWMA (v1 §7) to per-local-model **tok/s, time-to-first-token, and sustained-throughput-before-throttle**, stored as per-device-per-model calibration in Room. It feeds the P4 governors (when to `THERMAL_HOLD`, when to failover) tuned to *this exact device* instead of catalogue averages — the recursion you wanted: the device learns its own constraints by running. Diagnostics panel visualizes tok/s, headroom, queue depth, and the throughput-vs-time curve. Optional first-run seed: ASOM MAY read an **editorial** benchmark-reference table from the catalogue repo (owner-published, measured on the owner's own devices) so a fresh install has expectations before local data accrues — read-only, and never confused with uploaded user data. **Backend bakeoff** (llama.cpp CPU vs Vulkan; optionally one of MLC/LiteRT-LM/ExecuTorch+QNN) — `NEEDS-OWNER-VALIDATION` on the RedMagic; decision recorded, seam kept. *Gate:* QA script committed with real outputs; a benchmark sample provably shifts a governor threshold.
- **P7 — Benchmark contribution (opt-in upload — the sole egress exception).** Lets a user *occasionally* contribute their local benchmark data, enforcing the amended Invariant 1 (§13). Non-negotiable shape: **off by default**; an occasional gentle prompt is permitted but **sending is only ever an explicit foreground tap** (never automatic, never a timer that transmits without consent); a **payload viewer shows the exact JSON that will leave, before it leaves**; declining is one tap and suppresses re-prompting for a long cooldown. Payload is **anonymous by construction** — coarse device model (e.g. `RedMagic 11 Pro`), SoC, RAM class, model id, quant, tok/s, TTFT, throughput/thermal trace. **Never**: prompt/response content, keys, per-app usage, ledger rows, or any install ID / fingerprint beyond the coarse device class. POSTs to a configurable `BENCHMARK_SINK_URL` (`OWNER-FILL`; owner-operated static receiver — §12). *Gate:* on-device test shows the viewer rendering the literal outgoing bytes; off-by-default asserted; a unit test proves the payload builder is structurally incapable of emitting a key/content/fingerprint field.

Contract delta: errors `THERMAL_HOLD`, `MODEL_OOM`, `CONTEXT_OVERFLOW`; response header `X-Asom-Failover`. Ledger `egress: local` rows now occur (bytesOut 0, tokens counted locally, no `costEst`). Non-API additions: per-device benchmark calibration store; opt-in anonymous benchmark upload (P7).

**Keys note (reinforces v1 §10A.3):** `asom-standalone` (the embeddable engine built to fill v1's `Embedded` seam) holds its **own** human-entered keys; ASOM still never emits or ingests a key. Nothing about local inference changes the vault's one-way, human-only write path.

---

## 5. v2.5 — Semantic Routing (CONTRACT-GRADE)

The original three-stage router, now buildable because the engine exists.

- **Stages:** (1) v1 rule layer short-circuits when decisive → (2) on-device **embedding similarity** against an owner-curated exemplar set → (3) small on-device **classifier** model; stages 2–3 run parallelized, rules always win ties. Acceptance law carried from the original brief: the deciding stage is logged for every request.
- **`X-Asom-Route-NL`** (request): natural-language routing instruction ("something cheap and fast, never trains on data") parsed by a small local model into a **routing decision object validated against a JSON Schema** — NL is never executed, only compiled to the same policy structures the rule layer uses; invalid output is rejected/repaired.
- **`X-Asom-Route-Reason`** (response): which stage decided and why, one line — the watched object explains itself per request.
- **Per-app policy table** (Hotspot tab): per-app default policy, per-app cloud ban, per-app `No-Train` default. Enforced daemon-side; a banned app's `auto` never touches cloud.
- Contract delta: headers `X-Asom-Route-NL` (request), `X-Asom-Route-Reason` (response). No new endpoints.

---

## 6. v3 — Loops & the Egress Firewall (CONTRACT-GRADE)

Three items, deliberately shipped together because all three change what a *request* is.

- **NL loops.** Closed **node registry** (typed, enumerated node kinds — model call, merge, branch, critique; nothing arbitrary) + JSON Schema for loop definitions; **NL→DAG compiler** with strict validation and repair — an invalid spec is rejected or repaired, **never executed**; cyclic-capable Kotlin DAG runner; `/admin/loops` CRUD; invoked via `model:"loop:<name>"`. **Every loop declares `maxCostUSD` and `maxWallClock`** — breach halts with typed `LOOP_COST_CEILING` / `LOOP_WALLCLOCK_CEILING`. Ledger writes parent + child rows so a loop's full egress is one visible tree. Acceptance law carried: the canonical 3-step workflow (cheap summarize → reasoning critique → merge) compiles, runs, returns merged output.
- **Egress firewall (PII redaction).** On-device redaction of cloud-bound bodies using **deterministic reversible placeholders** (`{{ASOM_PII_n}}`), per-request local mapping table, re-substitution on the response before it returns to the client. Entity classes start regex-grade (emails, phone numbers, key/secret patterns), with model-NER as an optional upgrade (engine exists by now). Per-app toggle + global default; redaction count surfaced via `X-Asom-Redacted: <n>`; verbose ledger shows the mapping locally. The cloud never sees the originals; the client never sees the placeholders.
- **Web-origin callers.** Pairing-code flow: dashboard mints a short-lived, single-use code; the web app exchanges it (`POST /pair/web` with code + origin) for an **origin-bound token**; CORS/preflight against a per-origin allowlist. This is *not* the deleted v1 `/admin/register` returning: the code is generated by the user inside the dashboard, so consent precedes contact and identity is the code itself, not a spoofable claim. Sample PWA consumer included.
- **Token scopes** (additive): `chat` (default, all existing tokens) and `ledger-read`; new read-only `GET /admin/ledger?since=` for `ledger-read` tokens — this is the chakravyuha consumption path promised in v1 §15, arriving through the normal contract process.
- Contract delta: endpoints `/admin/loops` (CRUD), `POST /pair/web`, `GET /admin/ledger`; header `X-Asom-Redacted`; errors `LOOP_COST_CEILING`, `LOOP_WALLCLOCK_CEILING`, `ORIGIN_NOT_ALLOWED`; token scopes `chat|ledger-read`.

---

## 7. v4 — The Literal Hotspot (DIRECTION-GRADE — design session required before execution)

The pairing metaphor goes physical: asom nodes on the phone, the Steam Deck, and the Dell serve each other over a **private overlay only**. This is Layer 2 of the original analysis — and the **terminal version of this roadmap**.

- **asom-desktop.** The payoff of the pure-JVM law: `:server` + `:core:*` packaged as a desktop node (CLI shell first, UI later). Runs on the Dell/Deck. *This is the first version where the homelab is required at all.*
- **Invariant amendment (the only one).** v1 Invariant 2 ("binds 127.0.0.1 only") is amended for v4 exactly as follows: *an additional listener MAY bind a private-overlay interface (Tailscale-class tailnet) or mTLS-secured LAN, OFF by default, per-device tokens required, every remote request ledgered on **both** nodes.* Never a public interface. No other invariant changes.
- **Device pairing.** QR-based: one node displays URL + token + cert fingerprint; the other scans. Static peer registry — no mDNS, no open discovery.
- **Node-aware routing.** Capability exchange (models present, thermal/RAM headroom); placement policy: this device → LAN node holding the model → cloud. **Whole-model placement only**; cross-device sharding stays parked per §1 (capacity, not speed).
- Contract delta (provisional, to be frozen in the v4 design session): ledger egress class `lan`; response header `X-Asom-Node`.

---

## 8. Contract delta registry (cumulative)

| Version | Endpoints | Headers | Errors | Other |
|---|---|---|---|---|
| v1.1 | — | — | `QUOTA_EXHAUSTED` | rate windows |
| v2 | — | `X-Asom-Failover` | `THERMAL_HOLD`, `MODEL_OOM`, `CONTEXT_OVERFLOW` | `egress: local` live |
| v2.5 | — | `X-Asom-Route-NL`, `X-Asom-Route-Reason` | — | per-app policy table |
| v3 | `/admin/loops`, `POST /pair/web`, `GET /admin/ledger` | `X-Asom-Redacted` | `LOOP_COST_CEILING`, `LOOP_WALLCLOCK_CEILING`, `ORIGIN_NOT_ALLOWED` | scopes `chat\|ledger-read` |
| v4 | (design session) | `X-Asom-Node` | (design session) | egress class `lan`; Invariant-2 amendment |

All changes are **additive**; nothing shipped in v1 §5 is ever removed or reshaped.

---

## 9. Catalogue schema evolution (owner's news-app repo; all additive)

- **v2 fields:** per-model `minRamBytes`, multiple quant variants in `files[]` (already structured), `family`/`chatTemplate` metadata; provider `rate.tpm`.
- **v2.5:** nothing (exemplar set ships in-app, owner-curated).
- **v3+:** nothing anticipated.

---

## 10. Boundaries (extended, all one-way toward asom)

- **chakravyuha** — unchanged from v1 §15; its ledger-read path arrives in v3 via scopes, through the public contract like any consumer.
- **The multi-LLM orchestration platform** (five-layer homelab concept: adapters, DAG conversation state, methodology layer — MoE/ensemble/critic-debate — telemetry, branching-tree UI) is a **consumer**, with asom as one adapter. asom's loops are daemon-local primitives; the methodology layer never migrates into asom. If the orchestrator wants patterns, it composes asom calls.
- **FoneBru/aarso** — a consumer. Its "Council" terminology is FoneBru-side; asom never uses the word.
- **Nooz (the news app)** — **not a runtime dependency in either direction.** Nooz is the catalogue's *sensing layer*: it detects when a provider's free tier, pricing, or model lineup shifts in the wild and opens a PR against the catalogue repo; CI republishes `catalogue.json`; asom consumes it on its 24 h refresh. asom never calls Nooz and Nooz never calls asom — they meet only at the catalogue repo. Same one-way discipline as chakravyuha. (The optional editorial benchmark-reference table of P6 lives in that same repo.)

---

## 11. The stop-line (excluded by owner directive)

**Layer 3 — the torrent-scale, decentralized, token-incentivized public inference network ("BitTorrent for AI compute", DePIN-class, web3) — is out of scope for this roadmap and for asom.** Guardrails so nothing drifts toward it: no public-internet listeners, ever; no incentive/settlement layer for third parties; no peer discovery beyond explicitly paired, owner-controlled devices; no relaying for unpaired parties. v4's private-overlay pairing is the **permanent outer boundary** of this document. (Context, not justification: the prior research found that space technically proven but commercially marginal and largely dormant. The reason for exclusion is owner directive.)

---

## 12. Owner tasks by version

- **v1.1:** provide a second key on a shared-model provider pair for the forced-429 test.
- **v2:** land §9 catalogue v2 fields and re-publish; choose and download the validation models on the RedMagic; run the P6 bakeoff and pick the backend; re-verify §1 performance assumptions (they age fast). **Stand up the benchmark receiver** (§13.1) and paste its URL into `BENCHMARK_SINK_URL` — recommended: a Cloudflare Worker writing to R2 (free tier is far larger than this will need; teaches serverless; IP-logging left off); acceptable zero-server fallback: a Google Apps Script web app appending to a Sheet. Whatever it is, it only validates and stores the anonymous payload — no auth to embed in the client, so no secret ships in the APK.
- **v2.5:** author/approve the routing exemplar set; set default per-app policies.
- **v3:** decide default redaction entity classes; approve first web origins.
- **v4:** stand up the tailnet (phone + Deck + Dell — the homelab becomes required here, not before); commission the Dell node; **hold the v4 design session** before any execution.

---

## 13. Invariants across versions

v1 §1 applies to every version. **Exactly two amendments exist in this entire roadmap, and no session may introduce a third without owner escalation:**

1. **Invariant 1, at v2 (benchmark contribution).** The "no automatic egress" rule is *not* relaxed — it is honored — but one additional **explicit, user-initiated, view-first** export is permitted alongside the v1 ledger export: opt-in benchmark upload (v2 P7). It is off by default, shows its exact payload, is anonymous by construction, and declining is frictionless. **Honesty note that must stay visible:** the moment any benchmark data is *received*, the owner operates a receiver — a small dent in "no operator backend." Two mitigations are therefore mandatory, not optional: (a) the payload carries no fingerprint beyond coarse device class, so it cannot be tied to a person or install; (b) the receiver must not log source IPs (the transport layer/CDN sees them regardless — so the receiver is configured not to retain them, and this is stated in the privacy copy). A user who never opts in never contacts the receiver at all.
2. **Invariant 2, at v4 (private-overlay networking)**, under §7's conditions — an off-by-default, token-gated, private-overlay-only listener; never a public interface.

Any session finding itself wanting a *third* amendment must stop and escalate to the owner.
