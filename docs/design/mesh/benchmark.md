# Benchmarking utility: methodology, plain-text reporting and packaging

**Section of:** asom mesh design session (2026-09-29) · **Grade:** DIRECTION (input to roadmap v2 P6/P7 and to the v4 design session; nothing here authorises execution) · **Siblings:** `platforms.md` (per-OS roles, code strategy, conformance suite, constraints C1–C11), `trust.md` (node identity, pairing, peer data handling), `manifest.md` (signed envelope, attestation, subscribers, public derivative), `router.md` (placement and live state), `contract.md` (delta registry, invariants, phasing).

**This section owns:**
- what is measured, and how;
- the pinned test-model set;
- run plans and the thermal protocol;
- statistics and confidence (conformance family **M04**);
- how active and passive results combine and decay;
- the consent and safety governors;
- the device-side plain-text report (the **body** of M05);
- the measurement document, and its projection into the manifest (§13);
- packaging the benchmark as a daemon feature, a standalone app and a headless CLI.

**It does not own:**
- the signature envelope, key tiers, attestation, expiry, challenge nonce, subscriber surface or public-derivative rules (`manifest.md`);
- how the router weighs claims against live state (`router.md`);
- ledger rows for peer traffic (`trust.md`).

**Tags:**
- **[BVnn]**: verified this session, with the source in §22.1.
- **[Vnn]/[Cn]**: verified or fixed by `platforms.md`.
- **[BAnn]**: an assumption I did not verify (§22.2).

**Every performance number in this section is synthetic** unless a source is cited. No throughput is claimed for any real device.

**Companion files** (written this session; illustrative, not normative) are in `mesh/bench-examples/`:

| File | What it is |
|---|---|
| `bench_ref.py` | A reference sketch of M04 derivation, M05 rendering and the manifest projection, using integer arithmetic only |
| `raw-example-phone.json` | Synthetic raw samples |
| `example-phone.doc.json` / `.doc.jcs` | The measurement document and its canonical bytes |
| `example-phone.txt` | The report generated from that document |
| `example-phone.manifest-results.json` | Its projection into `manifest.md`'s `results[]` shape |
| `m04-seed-vectors.txt` | Edge-case outputs from the reference |

Re-running `bench_ref.py` reproduces byte-identical canonical output. The projection validates against the draft `asom.manifest.1.schema.json` (checked with `jsonschema` 4.26.0), except for one mismatch, recorded as R1 in §13.4.

---

## 0. The design in brief

- **One benchmark subsystem, three shells.** All measurement logic, statistics and text live in one library: `:bench-core` (pure JVM), plus a Swift port for iOS pinned by vectors, as `platforms.md` §6 requires. It ships in three forms:
  1. **Embedded in the asom daemon:** Android `:app` and desktop `:node-desktop`.
  2. **As a standalone app:** Android `xyz.mdhv.asom.bench` and iOS/iPadOS `AsomBench`. On macOS and Linux the standalone form is the `asom-bench` CLI [C10].
  3. **As a headless CLI** for lab and CI use. This is the same `asom-bench` binary.

  Shells supply probes, the engine binding, consent UI, storage and a signer. They contain no statistics and no sentences. Kotlin `internal` visibility, the M04/M05 vectors and a recorded core version make any fork visible (§2.5).
- **Two lanes, kept apart.**
  - *Active:* synthetic, time-boxed, started by the user, on a pinned test set, under safety governors.
  - *Passive:* every real local inference is a sample. This is roadmap v2 P6, generalised.

  The two lanes are stored and signed separately. They are combined only locally, for the node's own router and governors, with confidence weights and half-life decay (§10).
- **What is measured:**
  - prefill and decode throughput, with llama-bench-compatible test names [BV01];
  - time to first token (TTFT), taken from the same prefill repetition;
  - how speed changes with context depth;
  - optional batch concurrency;
  - load time, memory ceilings and headroom;
  - a sustained-load thermal curve (onset, plateau, stability);
  - energy per token, where the platform really exposes it;
  - a **numerics sanity check**. It is not a quality score (§3.4).
- **Test models:** the Qwen3 dense family (Apache-2.0, GGUFs published by the model authors), six tiers from 0.6B to 32B. Each is **pinned by sha256 and repository revision**, both retrieved this session [BV19]. The pin is compiled into the core. The catalogue supplies only download URLs.
- **The plain text is generated, never written.** The report is a pure function of the measurement document. It is ASCII and byte-exact across implementations, and its sha256 sits inside the signed data. It answers five user questions in fixed wording, labels every number *measured* or *estimated*, and always ends with what it does not tell you.
- **The manifest gets its benchmark numbers only through one projection** (§13.3) from the same document. The reference projection already validates against the manifest draft's schema, apart from item R1.
- **Governors:**
  - a consent sheet for every run;
  - a charger requirement for heat tests on battery devices;
  - hard ceilings on thermal state and battery temperature;
  - time caps;
  - abort when the app is backgrounded;
  - yield to any real request within 1 s;
  - no scheduled or automatic active runs, ever.
- **Sequencing.** Everything that measures needs the v2 engine. The derive, render, plan and governor core can be built now in pure JVM against a fake engine, with owner OK (BD2, §21).

### 0.1 Rules

| # | Rule | v1 analogue |
|---|---|---|
| B1 | **One core, many shells.** A shell never computes a statistic, a derived answer or a sentence. | `:client` stays minimal because it ships inside other apps |
| B2 | **One document, every rendering.** The plain text and the manifest's `results[]` are pure functions of the measurement document. The text's hash is inside the document. | Invariant 9: echo headers and ledger row come from one `RouteRecord` |
| B3 | **Measured, estimated and not-measured are distinct.** This holds in the data and in the text. Not-measured is an explicit `null`, never an omitted key or a default. | Cost headers only with a basis ("never estimate without a basis") |
| B4 | **Never average across conditions.** Rows that differ in model hash, backend, engine commit, build flags, OS build, GPU driver, power source or harness are kept apart [C11]. | Ledger rows are per attempt, never merged |
| B5 | **Active runs start only from an explicit foreground action** (a tap after the consent sheet, or an explicit CLI command). Nothing schedules them. | Boot-start defaults OFF: "nothing runs unless the user starts it" |
| B6 | **Local first.** A benchmark yields to any real request within 1 s. It never runs while serving peers. | `local-only` never silently degrades |
| B7 | **No new egress.** Test-model downloads are ordinary `download` rows. Benchmark data leaves the device only through `manifest.md`'s paths or the P7 contribution, whose payload is a separate allow-listed type. | Invariants 1 and 3 |
| B8 | **Integers only, in declared units** [C1]. | JDK `Double.toString` drift [V27] |
| B9 | **Every derivation rule is an M04 vector, and every template branch is an M05 vector.** | `ContractFreezeTest` |
| B10 | **Anti-flattery.** Medians are conservative and speeds are floored, not rounded. The sustained number appears next to the peak. The "does not tell you" block is mandatory. | Audit lesson: a stated egress class must be true |

---

## 1. Reconciling with roadmap v2 P6/P7

| Roadmap statement | This design | Status |
|---|---|---|
| P6: "every real local inference is a benchmark sample" | Kept as the **passive lane** (§10). It is generalised to buckets by context depth, thermal band and power source. Each sample comes from the same completion event that builds the `RouteRecord` (§10.1), so the ledger and the calibration store cannot disagree on token counts. | compatible |
| P6: "extend the `fastest`-policy latency EWMA … tok/s, TTFT, sustained-throughput-before-throttle … per-device-per-model calibration in Room" | Same store, now bucketed, with an integer EWMA of α = 1/8 (§10.2). Passive traffic rarely runs long enough to show throttling, so "sustained throughput before throttle" comes from the active thermal protocol (§6). | extends |
| P6: "the benchmark app is a *feature*, not a separate app" | There is still **one subsystem**, and it is a daemon feature. The standalone app is a thin shell over the same core, not a second subsystem. But the sentence as written forbids a separate app, and the owner's new requirement asks for one. | **contradicts the wording → BD1** |
| P6: "a benchmark sample provably shifts a governor threshold" | §10.6 gives the exact rule: the v2 P4 queue threshold is set from the thermal headroom observed at throttle onset. | compatible, now concrete |
| P6: optional editorial benchmark-reference table in the catalogue repo | Produced with the headless CLI on the owner's own devices (§14.5). Each entry is a signed document, so its provenance can be checked. It is shown as "maintainer reference" and never mixed with the user's own results. | extends |
| P6: backend bakeoff, NEEDS-OWNER-VALIDATION | The benchmark harness *is* the bakeoff tool: one `standard` run per backend, with rows kept apart by B4. Non-llama.cpp candidates (MLC, LiteRT-LM, ExecuTorch) use stream timing, capped at MEDIUM confidence (§2.3). | compatible |
| P7: opt-in, view-first, anonymous upload with no fingerprint beyond coarse device class | Kept. The payload is **never** the signed manifest, because a stable public key *is* a fingerprint. It is a projection of the document, built by `manifest.md`'s public-derivative function (M06 in its draft). §13.5 lists what the benchmark side requires of it. That list is stricter than the roadmap: no passive (usage-derived) data, and no custom-model hashes. | compatible; stricter |
| P7: POST to `BENCHMARK_SINK_URL` | An upload is a network event, so Invariant 3 needs an egress class for it. Amendment 1 (roadmap §13) amends Invariant 1 only. | **gap → BD4** |
| (not in the roadmap) Active synthetic benchmarking | A new lane. Local only, no egress, governed. It needs the engine, so it is v2 work at the earliest. | new; timing is BD2 |
| (not in the roadmap) iOS, macOS and Linux shells | Follows `platforms.md` stages S1, S3 and S5. | new; see `platforms.md` OD2 and OD4 |

**Conclusion.** The roadmap's architectural intent survives: one self-calibrating subsystem inside the daemon, and no second benchmark codebase. Two things change: one sentence of roadmap text (BD1), and the addition of an active lane.

---

## 2. Architecture: one core, three shells

### 2.1 Layers

```
 SHELLS (thin; no statistics, no text)
   Android daemon (:app, Benchmark tab)       Android standalone (:bench-app)
   iOS/iPadOS standalone (AsomBench, Swift)   asom-bench CLI (:bench-cli) = desktop standalone
                                                                           = `asom bench` in asom-desktop
                                                                           = headless lab/CI
        |  BenchHost SPI: probes, engine, verified model files, progress UI,
        |                 coexistence (daemon only), clocks
        v
 CORE  :bench-core (pure JVM)                  AsomBench Swift module (port)
        run-plan interpreter + governor FSM    same, pinned by vectors
        M04 derive          (internal)         M04 (vectors)
        M05 render          (internal)         M05 (vectors)
        manifest projection (internal)         projection (vectors)
        passive calibration store logic        (none in S5: iOS has no daemon)
        |
        v
 :core:contract   :core:mesh (JCS integer profile; platforms.md 7.1)   :core:inference-api (+ BenchEngine)
```

`:bench-core` follows brief §4's law: pure JVM, no `android.*`, and part of `jvmTest`. It depends on `:core:contract`, `:core:mesh` and `:core:inference-api`, exactly as `platforms.md` §7.1 proposes.

### 2.2 Public surface of `:bench-core`

Everything not listed here is `internal`, so no shell can compile against a derivation or rendering detail.

```kotlin
package xyz.mdhv.asom.bench

class BenchSession(host: BenchHost, plan: RunPlan, set: BenchSet, consent: ConsentToken) {
    val state: StateFlow<GovernorState>          // §11.4
    suspend fun run(): RunOutcome                // governor aborts are outcomes, never exceptions
    fun stop()                                   // user Stop; same path as a hard ceiling
}
sealed interface RunOutcome {
    data class Completed(val raw: RawRun) : RunOutcome
    data class Aborted(val reason: AbortReason, val partial: RawRun?) : RunOutcome
}

/** The consent sheet is the only way to obtain a ConsentToken (B5, view-first). */
class ConsentSheet internal constructor(/* ... */) {
    companion object { fun forPlan(plan: RunPlan, set: BenchSet, pre: PreflightReport): ConsentSheet }
    val text: String                                           // exact words shown (§11.1)
    fun confirm(shownTextSha256: ByteArray, nowMs: Long): ConsentToken  // throws unless the hash matches `text`
}

object Bench {
    fun derive(raws: List<RawRun>, field: List<FieldBucket>, set: BenchSet): BenchDocument   // M04
    fun render(doc: BenchDocument): ByteArray                                           // M05 body, ASCII + LF
    fun handoff(doc: BenchDocument): SignerInput                                        // §13.3
    fun calibration(store: CalibrationStore): BenchCalibration                          // §10.6, local only
}
```

`RunPlan` and `BenchSet` are parsed from JSON resources compiled into the core (§4.3, §5.2), not fetched.

### 2.3 Host SPI and the engine timing seam

```kotlin
interface BenchHost {
    val monotonicMicros: () -> Long      // System.nanoTime()/1000 | mach_absolute_time | CLOCK_MONOTONIC
    val epochMillis: () -> Long          // used only for run/tier start and end stamps
    val probes: ProbeSource
    val engine: BenchEngine
    val models: BenchModelStore          // resolves tier -> file whose sha256 matched the compiled-in pin
    val coexistence: Coexistence         // daemon: yield + availability; standalone: no-op
    val progress: BenchProgressUi        // progress and abort notices; never asks for consent
}

interface ProbeSource {
    fun device(): DeviceInfo                       // §13.2 "device"
    fun thermal(): ThermalReading                  // code 0..4 (§6.1) + raw: headroomPermille?, zonesMilliC?
    fun power(): PowerReading                      // source ac|battery, levelPermille?, battTempDeciC?,
                                                   // chargeCounterMicroAh?, currentMicroA?, voltageMilliV?
    fun energyMicroJoules(): EnergyReading?        // cumulative counter + method, or null (§7)
    fun memory(): MemoryReading                    // availBytes, processLimitBytes?, gpuWorkingSetBytes?,
                                                   // footprintBytes, swapUsedBytes?
    fun presence(): Presence                       // foreground, screenOn, batterySaver, lowPowerMode
    fun gpuBusyByOthersPermille(): Int?            // amdgpu (Steam Deck) only
    fun events(listener: (ProbeEvent) -> Unit): AutoCloseable  // thermal change, memory pressure,
                                                   // power change, backgrounded
}

interface BenchEngine {
    fun info(): EngineInfo       // name, commit (40 hex), backend, buildFlags, threads, gpuLayers, kvType, flashAttn
    fun load(file: VerifiedModelFile, nCtx: Int): BenchModel     // OOM -> typed failure, never a crash
}
interface BenchModel : AutoCloseable {
    val kvBytesPerToken: Long                   // allocated KV bytes / nCtx
    fun tokenize(utf8: ByteArray): IntArray
    fun prefill(tokens: IntArray, seq: Int = 0): Int   // one batched decode at the current KV position;
                                                       // returns the greedy argmax of the last position
    fun decodeGreedy(seqs: IntArray): IntArray  // one token per sequence; batch = seqs.size
    fun truncateKv(seq: Int, keepTokens: Int)   // KV rollback: depth fill is paid once per tier (§5.3)
    fun clearKv()
    fun nllMicroNats(tokens: IntArray): Long    // teacher-forced sum of -ln p, computed natively (§3.4)
    fun cancel()                                // from any thread; the in-flight call returns within 1 s
}
```

**Timing definition.** All durations are taken by the **host's monotonic clock around each engine call**. The engine never reports its own timings, so every implementation measures the same span, including JNI or Swift/C transition overhead.

A backend that cannot separate prefill from decode is measured in **stream mode**:
- TTFT is the time to the first streamed token.
- Decode rate is (n − 1) tokens over the time from the first to the last token.
- The harness records `timingSource: "stream"`, caps confidence at MEDIUM, and keeps these rows apart under B4.

**JNI additions.** Roadmap v2 P1 lists a JNI surface of `load/unload/tokenize/generate(stream)/embed/cancel`. The benchmark needs `prefill`, a batched `decodeGreedy`, `truncateKv`, `clearKv`, `nllMicroNats` and `info` as well (CB1). All map onto existing llama.cpp calls.

### 2.4 Shell inventory

| Shell | Where it lives | Lanes | Consent | Signer (identity is `manifest.md`'s) |
|---|---|---|---|---|
| Android daemon | `:app`, Benchmark tab | active + passive | Compose sheet | node key |
| Android standalone | new `:bench-app` → APK `xyz.mdhv.asom.bench` | active only | Compose sheet | its own app key |
| iOS/iPadOS standalone | `apple/AsomBench.xcodeproj` (`platforms.md` §7.2) | active only | SwiftUI sheet | Secure Enclave key |
| Desktop daemon | `:node-desktop`, subcommand `asom bench` | active + passive | terminal prompt | node key |
| Desktop standalone | `:bench-cli` → `asom-bench` | active only | terminal prompt | own file or keychain key |
| Headless lab/CI | same `asom-bench` | active only | `--yes=<plan>` flag (§11.1) | CI: none (unsigned). Lab: owner's key |

### 2.5 How forking is prevented (enforced, not hoped for)

1. **Visibility.** Derivation, rendering and projection are Kotlin `internal`. A shell *cannot* reimplement a statistic by calling into the internals, and duplicating one in a shell fails review rule B1.
2. **Dependency shape.** Shells depend on `:bench-core` and on the *same* engine and storage modules the daemon uses: `:storage`, `:ledger` and the v2 engine module on Android. Shells never depend on each other.
3. **One conformance bar.** Every shell's CI lane runs M04/M05 and the projection vectors against the core *as packaged in that shell*: JVM, the ART instrumented subset, and Swift.
4. **Recorded provenance.** Every document records `harness.shell`, `coreImpl`, `coreVersion` and `confVersion`, so a lagging shell is visible to every subscriber.
5. **Probe gaps are declared, never patched around.** A shell that lacks a probe returns `null` with a reason, and the core renders "not measured". Measurement features land in the core first.
6. **Release gate.** A shell release pastes real vector-run output into `PROGRESS.md` (brief §12). Device lanes stay NEEDS-DEVICE-VALIDATION.

### 2.6 The Swift port boundary

The Swift port has three parts:
- **Ported:** the run-plan interpreter, the governor FSM, M04, M05, the projection, and JCS/verify (from `platforms.md`).
- **Native Swift, no port needed:** the probes, the llama.cpp XCFramework binding and the Secure Enclave signer.
- **Not ported:** the passive store, because iOS has no daemon.

**Harness parity check** (the tolerance that `platforms.md` §6.4 left to this section):
- Run both harnesses on one Apple-silicon Mac, against the same GGUF and the same pinned commit, alternating 3 runs each.
- Pass if every decode or prefill p50 agrees within **30‰** and every TTFT p50 within **50‰**. [BA17] NEEDS-OWNER-VALIDATION.

---

## 3. What is measured

### 3.1 Test catalogue

Test names follow llama-bench's `pp`/`tg` convention and its depth notation (`pp512 @ d512` becomes `pp512@d512` here) [BV01], so numbers can be compared with community results at a glance. Two differences are deliberate:
- Decode includes the greedy argmax of each token. I assume that cost is negligible next to a decode step [BA18].
- Prefill repetitions also yield TTFT.

| Test | What it measures | Protocol per rep | Unit | Plans |
|---|---|---|---|---|
| `load` | Model load | Cold: 1 sample after best-effort cache eviction (§8). Warm: 3 unload/load cycles, upper median | µs | all |
| `ppP@d0` | Prefill throughput **and TTFT** for a P-token prompt, with an empty context | Two spans per rep. *Prefill span*: `prefill(P tokens)`, giving `rate = P·10⁹ / span` (mtps). *Whole span*: tokenize the pinned prompt text, prefill, and argmax the first token; this is the TTFT. In llama.cpp the first generated token comes from the prefill's last logits, so no extra decode step exists. Then `clearKv` | mtps + µs | P = 512 in all plans; P = 2048 on desktop plans |
| `tgN@dD` | Decode throughput: N greedy tokens, batch 1, starting at context depth D | Fill D tokens once per tier (not timed). Per rep: N × `decodeGreedy`, then `truncateKv(D)` | mtps | N = 128; D ∈ {0, 2048} standard, plus {8192, 32768} extended where the model's context allows [BV20] |
| `tg128xK@d0` | Aggregate decode with K parallel sequences | K sequences, each with a 64-token prefix; 128 batched steps | mtps (aggregate) | extended only |
| `nll1024` | Numerics sanity (§3.4) | Teacher-forced over the pinned 1024-token text | milli-nats per token | all |
| `sustain` | Thermal curve (§6) | Continuous decode in 15 s windows | windows | standard, sustained |
| `energy` | Energy per token (§7) | 5 min continuous decode, unplugged | µJ/token | battery |

**Why `tg128xK` is extended-only.** The v2 engine is single-flight (roadmap v2 P3). Batch capacity matters only for `router.md`'s fan-out of batch or embedding jobs, not for chat, so it must not colour the headline answers.

### 3.2 Memory

| Field | Meaning | Source |
|---|---|---|
| `memTotalBytes` | Physical RAM as the OS reports it | Android `MemoryInfo.totalMem`; Apple `ProcessInfo.physicalMemory`; Linux `MemTotal` |
| `availAtStartBytes` | Available memory before any model is loaded | Android `MemoryInfo.availMem`; iOS `os_proc_available_memory()` [BV09]; macOS `host_statistics64` free + inactive + purgeable [BA19]; Linux `MemAvailable` |
| `processLimitBytes` | Per-process ceiling, if the OS has one | iOS: `os_proc_available_memory()` + current footprint. That value is "the current memory limit minus the memory footprint", advisory and changing [BV09]. `null` elsewhere |
| `gpuWorkingSetBytes` | GPU allocation ceiling for full offload | Metal `recommendedMaxWorkingSetSize`, "an approximation of how much memory, in bytes, this GPU device can allocate without affecting its runtime performance" [BV10]; Vulkan device-local `heapBudget` [BV18]; `null` for CPU backends |
| per tier: `availBeforeLoadBytes`, `peakFootprintBytes`, `kvBytesPerToken`, `nCtx` | Headroom actually observed | Probes plus engine |

The limit is `memLimitBytes = min(availAtStartBytes, processLimitBytes?, gpuWorkingSetBytes?)`, ignoring nulls. On unified-memory devices (Apple, Steam Deck [V21]) the GPU figure and system availability overlap, and `min` handles that.

**Rejected: probing memory by allocating until pressure.** On Android that triggers the low-memory killer against the user's other apps. Apple warns that large allocations make the system "terminate other apps and system processes" [BV09]. The "largest model" answer is therefore an estimate anchored on the largest model *actually loaded* (§12.4 Q2).

### 3.3 Thermal, power and environment

Recorded at run start, at each tier start, and at each 1 s poll during the sustained phase (reduced per 15 s window):
- the normalised thermal code (§6.1);
- raw thermal readings: Android headroom‰; Linux zone m°C;
- battery temperature in deci-°C (Android `EXTRA_TEMPERATURE`; Linux `temp` [BV15]);
- power source and battery level;
- whether the screen is on;
- battery saver or Low Power Mode;
- the Linux CPU frequency governor;
- whether Android sustained-performance mode is *supported*. It is never enabled [BV26];
- contention‰ (§8).

Android headroom is polled at 1 s. The API returns `NaN` if called within 500 ms of the last call, and "there is no benefit to calling this function more frequently than about once per second" [BV02].

### 3.4 Quality probes: decision

**Decision: no quality score. There is a mandatory numerics sanity check instead.**

*Why no quality score:*
1. The weights are pinned by hash, so model quality is identical on every device by construction. A quality score would measure the model, not the device.
2. Useful task suites take hours on a phone and invite gaming.
3. Any single score would be read as "this device gives better answers". That is false.

*Why the numerics check anyway.* A backend can compute wrong answers fast: a driver bug, a broken kernel for one quant type, or a reduced-precision NPU path. Unchecked, it would *win* routing on speed. The check detects gross numeric divergence from the CPU reference, for the same file and the same engine commit.

*Method:*
- Compute the teacher-forced mean negative log-likelihood (milli-nats per token) over a pinned 1024-token public-domain text, shipped in the app and hashed in the bench-set definition [BA12].
- Deviation‰ = |nll − ref| × 1000 / ref, where the reference is computed by the owner on the CPU backend at the pinned commit (BD5; NEEDS-OWNER-VALIDATION).
- Verdict: **pass** if ≤ 20‰, **warn** if ≤ 50‰, **fail** otherwise [BA04].
- **not-run** if no reference exists for this commit. A result is never "pass" without a reference.

*Consequences of `fail`:*
- The tier's rows are kept but flagged.
- Derived answers skip them.
- The projection adds the `numerics-fail` flag, and `router.md` must treat that (model, backend) as ineligible.
- The text says so plainly.

*Rejected:*
- **KL divergence:** it needs reference logit files of 11–37 GiB per model [BV21], which is impossible on phones.
- **Exact greedy-token match:** legitimate backend differences change greedy output, so it raises false alarms.
- **Task-accuracy suites:** see "why no quality score" above.

*Does not guarantee:*
- It catches gross divergence on one text only.
- Bugs that appear only at long context, or only in some operations, can pass.
- The tolerance is a heuristic, to be calibrated.

---

## 4. The standard test-model set ("bench set 1")

### 4.1 Criteria and choice

| Criterion | Why |
|---|---|
| **Dense** architecture | The memory rule and the size scaling in §12.4 assume every weight is read for every token |
| **Permissive licence** (Apache-2.0) | Anyone downloads it; mirrors stay possible; no F-Droid anti-feature concern [BV24, BA20] |
| **GGUF published by the model authors** | One hop in the supply chain |
| **One family** | Constant tokenizer and architecture across tiers |
| Sizes from 0.6B to 32B | Covers phones through desktops |
| Supported by llama.cpp; stable for years | A set never changes once published (§4.5) |

**Choice:** Qwen3 dense, from the official `Qwen/Qwen3-*-GGUF` repositories, all Apache-2.0 [BV19].

**Quantisation:** Q4_K_M from 4B upward. Q8_0 for 0.6B and 1.7B, because the official repositories publish **only Q8_0** at those sizes [BV19]. Only same-quant tiers are ever compared against each other for size scaling (§12.4).

**Rejected:**
- *Self-quantised small tiers hosted by the owner:* that makes the owner an operator of infrastructure and a new trust root.
- *Third-party quantisers:* an extra supply-chain party.
- *Llama and Gemma families:* custom licences with conditions.
- *MoE models such as Qwen3-30B-A3B:* the bytes read per token are not the file size. A later set could add a MoE tier with its own rules.
- *Newer 2026 families:* not evaluated. Stability matters more than recency for a reference set.

### 4.2 The pin

Hashes and revisions come from the Hugging Face API (LFS `oid` and repository `sha`) on 2026-09-29 [BV19]. Before the set becomes normative, the owner downloads each file and confirms `sha256sum` [BA11]. Parameter counts and KV geometry come from each model's `config.json` and safetensors metadata [BV20].

KV bytes per token at f16 = 2 × layers × KV heads × head_dim × 2 bytes.

| Tier | Model id (CB2) | File | Bytes | sha256 | Repo revision | Params | KV B/token |
|---|---|---|---|---|---|---|---|
| T0 | `qwen3-0.6b` | `Qwen3-0.6B-Q8_0.gguf` | 639,446,688 | `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031` | `23749fefcc72300e3a2ad315e1317431b06b590a` | 751,632,384 | 114,688 |
| T1 | `qwen3-1.7b` | `Qwen3-1.7B-Q8_0.gguf` | 1,834,426,016 | `061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a` | `90862c4b9d2787eaed51d12237eafdfe7c5f6077` | 2,031,739,904 | 114,688 |
| T2 | `qwen3-4b` | `Qwen3-4B-Q4_K_M.gguf` | 2,497,280,256 | `7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5` | `bc640142c66e1fdd12af0bd68f40445458f3869b` | 4,022,468,096 | 147,456 |
| T3 | `qwen3-8b` | `Qwen3-8B-Q4_K_M.gguf` | 5,027,783,488 | `d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785` | `7c41481f57cb95916b40956ab2f0b139b296d974` | 8,190,735,360 | 147,456 |
| T4 | `qwen3-14b` | `Qwen3-14B-Q4_K_M.gguf` | 9,001,752,960 | `500a8806e85ee9c83f3ae08420295592451379b4f8cf2d0f41c15dffeb6b81f0` | `530227a7d994db8eca5ab5ced2fb692b614357fd` | 14,768,307,200 | 163,840 |
| T5 | `qwen3-32b` | `Qwen3-32B-Q4_K_M.gguf` | 19,762,149,024 | `efd971561896866f0e910cce52761ca77b1b138090c7f15fe284676d57d1f689` | `938a7432affaec9157f883a87164e2646ae17555` | 32,762,123,264 | 262,144 |

The Q4_K_M files work out to 0.603–0.621 bytes per parameter. The report uses **0.610** only to phrase "roughly an N-billion-parameter model" [BA05].

### 4.3 Where the pin lives, and how the files arrive

- **The pin is compiled into `:bench-core`.** It is a resource, `benchset-qwen3-dense-1.json`, mirrored in `conformance/bench/`.
- **The catalogue supplies only mirror URLs**, through an additive `benchSets` field in the owner's catalogue repository (CB2).
- **The host's model store verifies the sha256 against the compiled-in pin, not the catalogue's value.** A compromised or mistaken catalogue therefore cannot substitute a test model. It can only fail to provide a URL.
- **Downloads use each platform's existing path.** On Android that is the v1 P7 machinery: WorkManager, Wi-Fi only by default, sha256 verification, and a ledger row with `egress: download`. The consent sheet states the total bytes before anything downloads.
- **Offline import.** Every shell accepts a local file (Android SAF picker, iOS Files, `--model-dir` on the CLI), used only if its sha256 matches. A benchmark can therefore run with no network at all.
- **In the daemon, bench models are ordinary catalogue models.** They are usable chat models, so a user who wants to keep them loses nothing. The standalone shells offer to delete them afterwards.
- **The numerics text** (a few KB of public-domain prose) ships inside the core, with its sha256 in the set definition, so it needs no network either.

### 4.4 Which tiers run

```
usable       = floor(memLimitBytes * safety / 1000)
safety (‰)   = phone 750 · tablet 750 · handheld 800 · laptop 850 · desktop 900 · server 900
fits(tier)  <=> tier.bytes + tier.kvBytesPerToken * 4096 + 314_572_800 <= usable    // 300 MiB overhead [BA06]
```

| Plan | Phone, tablet | Handheld, laptop, desktop, server |
|---|---|---|
| quick | T1 if it fits, else T0 | T1 |
| standard | T1 and T2 if they fit; **T3 only if the user opts in on the consent sheet** (+5.0 GB download) | T2, T3, T4 if they fit |
| extended | every tier that fits | every tier that fits |

- **Headline tier** = the largest measured tier whose numerics result is not `fail`.
- **Sustain tier** = the largest measured tier with `tg128@d0` ≥ 2,000 mtps and numerics not `fail`.

**Why T3 is opt-in on phones.** 5.0 GB is a lot to download for a curious user. Without T3, answer Q1 is *estimated* from T2 and labelled as such (§12.4).

### 4.5 Custom models, versioning and retirement

**Custom models:**
- A user may benchmark any GGUF. It appears in `custom[]`, keyed by its sha256, with the same tests.
- It is never used for the five answers, never included in any public projection, and compared only with rows that have the same sha256.

**Versioning and retirement:**
- A set id is immutable.
- If an upstream file disappears, the catalogue may point at a mirror; the sha256 pin still guards it.
- A new set gets a new id. Old documents stay valid within their own set.
- The renderer never compares numbers across sets.

---

## 5. Run plans

### 5.1 Plans

Durations are **design targets**, not measurements.

| Plan | Tiers (§4.4) | Headline-tier tests | Other-tier tests | Reps | Sustained | Battery devices | Target wall time |
|---|---|---|---|---|---|---|---|
| **quick** | 1 | load, pp512@d0, tg128@d0, nll1024 | — | 3 | no | charger not required; battery ≥ 30% | ≤ 5 min |
| **standard** | 2–3 | load, pp512@d0, tg128@d0, tg128@d2048 (3 reps), nll1024; **desktop forms add** pp2048@d0 | load, pp512@d0, tg128@d0, nll1024 | 5 | yes: sustain tier; cap 10 min (phone, tablet, handheld) or 15 min | **charger required** | phone ≤ 35 min including cool-downs; desktop ≤ 45 min |
| **sustained** | sustain tier (or T2) | sustain | — | — | as standard | charger required | ≤ 20 min |
| **battery** | sustain tier (or T2) | energy (§7) | — | 1 | no | **must be unplugged**; battery ≥ 60% | ≤ 12 min |
| **extended** | all that fit | standard tests plus tg128@d8192, tg128@d32768, pp2048@d0, tg128x2@d0, tg128x4@d0 | as headline | 5 | yes, 15 min | charger required on laptops | ≤ 90 min |
| **ci** | T0 | pp512@d0, tg128@d0, nll1024 | — | 3 | no | n/a | — |

### 5.2 Run-plan format

Plans are data compiled into the core. A document records the plan id and the sha256 of the plan's JCS bytes, so a subscriber knows exactly which protocol produced it.

```json
{
  "plan": "standard", "planVersion": 1,
  "reps": 5, "depthReps": 3, "warmupReps": 1, "interRepMs": 500,
  "tiers": {
    "phone":   { "auto": ["T1", "T2"], "optIn": ["T3"] },
    "tablet":  { "auto": ["T1", "T2"], "optIn": ["T3"] },
    "handheld":{ "auto": ["T2", "T3", "T4"] }, "laptop": { "auto": ["T2", "T3", "T4"] },
    "desktop": { "auto": ["T2", "T3", "T4"] }, "server": { "auto": ["T2", "T3", "T4"] }
  },
  "headlineTests": ["load", "pp512@d0", "tg128@d0", "tg128@d2048", "nll1024"],
  "headlineTestsDesktop": ["pp2048@d0"],
  "otherTests": ["load", "pp512@d0", "tg128@d0", "nll1024"],
  "sustain": { "capMsMobile": 600000, "capMsDesktop": 900000, "windowMs": 15000,
               "chunkTokens": 256, "promptTokens": 64 },
  "coolDown": { "pollMs": 5000, "maxWaitMsMobile": 180000, "maxWaitMsDesktop": 120000,
                "maxWaitMsBeforeSustain": 600000 },
  "charger": { "phone": "required", "tablet": "required", "handheld": "required", "laptop": "required" },
  "minBatteryPermille": 500,
  "wallCapMs": { "phone": 2100000, "tablet": 2100000, "handheld": 2400000,
                 "laptop": 2700000, "desktop": 2700000, "server": 2700000 }
}
```

### 5.3 Phase order and the per-rep protocol

1. **PREFLIGHT** (§11.2). Contention baseline: the minimum of 5 spins (§8).
2. **For each tier, smallest first:**
   - COOL-DOWN gate (§6.5);
   - `load` (cold, then warm);
   - WARMUP (1 × pp512 and 1 × tg32, discarded);
   - the d0 tests;
   - `nll1024`;
   - depth fill, then the depth tests;
   - unload.

   Smallest first means heat builds up while the least heat-sensitive tiers run. The gate plus the recorded per-tier start class make any residual effect visible.
3. **SUSTAIN**, last because it heats the device most: a COOL-DOWN gate (up to 10 min), load the sustain tier, then §6.
4. **Final spin** (`contentionAfterPermille`).
5. **DERIVE → RENDER → HANDOFF.**

```
per rep (every timed test):
    governor.check()                               // may raise Abort or Yield (§11)
    t0 = host.monotonicMicros()
    <engine operation>                             // prefill | decodeGreedy x N | nll
    t1 = host.monotonicMicros()
    record(t1 - t0)                                // pp@d0 also records the whole span (TTFT)
    engine.truncateKv(seq, depth) | engine.clearKv()
    sleep(interRepMs)                              // lets DVFS settle; this is not a cooling pause
```

### 5.4 Executor (pseudocode)

```kotlin
suspend fun BenchSession.execute(): RunOutcome = governed {            // governed{} installs §11.3 checks
    val pre = preflight() ?: return Aborted(pre.reason)
    val baseline = (1..5).minOf { spin() }
    for (tier in plan.tiersFor(device, consent.optIns).filter { fits(it) }.sortedBy { it.bytes }) {
        coolDown(plan.coolDown.maxWaitFor(device))                    // records the tier's start class
        val m = engine.load(models.verified(tier), nCtx = tier.nCtx(plan))
        m.use {
            warmup(m)
            for (t in plan.testsFor(tier, isHeadline(tier))) runTest(m, t)  // reps + the rollback protocol
        }
    }
    sustainTier()?.let { coolDown(plan.coolDown.maxWaitMsBeforeSustain); sustain(it) }   // §6
    Completed(raw.finish(contentionAfter = spin().rel(baseline)))
}
```

---

## 6. Sustained-load thermal protocol

### 6.1 Thermal codes and start classes

| Code | Android `THERMAL_STATUS_*` [BV02] | Apple `thermalState` [BV11] | Linux (lowest-margin zone, with trip points [BA02]) |
|---|---|---|---|
| 0 | NONE | nominal | every zone ≥ 20 °C below its passive trip |
| 1 | LIGHT ("UX is not impacted") | fair ("slightly elevated") | ≥ 10 °C below |
| 2 | MODERATE | — | below the passive trip |
| 3 | SEVERE ("UX largely impacted") | serious ("reduces performance") | at or above the passive trip |
| 4 | CRITICAL, EMERGENCY, SHUTDOWN | critical ("device needs to cool down") | ≥ critical trip − 5 °C |

Linux zones without trip points fall back to package-temperature thresholds of 70/80/90/95 °C [BA02].

**Start classes:**
- **COOL:** code 0; *and* Android headroom(0) ≤ 600‰ where available; *and* battery ≤ 35.0 °C where readable; *and* contention ≤ 100‰.
- **HOT:** code ≥ 3. The run refuses to start.
- **WARM:** anything else.

### 6.2 Workload and sampling

- **Workload:** on the sustain tier, loop { `clearKv`; prefill a fixed 64-token prompt; 256 × `decodeGreedy` } without pause. The short context isolates heat from the slowdown that a growing context causes.
- **Windows:** 15 s of wall clock. Each window records `[tStartMs, decodeTokens, decodeMicros, maxThermalCode]`. The rate counts decode time only; prefill time is spent but not counted.
- **Polling:** thermal every 1 s [BV02]. Android also stores headroom(0) at each window's end.

### 6.3 Peak, onset, plateau, stability (normative; integer arithmetic)

```
rate[i]   = tokens[i] * 1_000_000_000 / micros[i]                 // mtps, floor
s[i]      = lowerMedian(rate[max(0,i-1) .. min(n-1,i+1)])         // 3-window smoothing (2 at the edges)
peakIdx   = argmax over { i : tStart[i] < 120_000 } of s[i]       // ties -> earliest
peak      = s[peakIdx]
onset     = first i > peakIdx such that s[i], s[i+1], s[i+2] are all < 900 * peak / 1000
if onset exists:
    settled = [ s[i] : tStart[i] >= tStart[onset] + 60_000 ]
    tail    = last 8 of settled                   // if settled is empty: s[onset..], flag PLATEAU_NOT_REACHED
else:
    tail    = last 8 of s
plateau   = lowerMedian(tail)
stability = plateau * 1000 / peak                                 // permille, floor
duration  = tStart[n-1] + windowMs
```

**Confidence:**
- start not COOL → **low**;
- `endReason` PLATEAU, or TIME_CAP with duration ≥ 480 s → **high**;
- duration ≥ 300 s → **medium**;
- otherwise → **low**.

A hard-ceiling abort keeps the windows, names the ceiling in `endReason`, adds the flag `HARD_CEILING`, and caps confidence at medium.

**Precedent and difference.** 3DMark's stress test reports stability as 100 × lowest loop / best loop, over 20 loops [BV22]. This design uses the *smoothed plateau over the smoothed peak* instead. One noisy window cannot set the score, and the number describes the steady state a user actually lives in.

### 6.4 End conditions (first that fires)

| endReason | Condition |
|---|---|
| `PLATEAU` | An onset exists, and the 12 most recent smoothed windows that start ≥ onset + 60 s all lie within ±50‰ of their lower median |
| `TIME_CAP` | `capMs` reached |
| `THERMAL_SOFT` | A soft ceiling (§11.3) has held for 120 s since it was first crossed. The windows from those 120 s are kept, so the throttled plateau is observed rather than cut off |
| `THERMAL_HARD`, `BATTERY_TEMP`, `USER_STOP`, `BACKGROUNDED`, `CHARGER_REMOVED`, `MEMORY_PRESSURE`, `WALL_CAP` | Abort (§11.3) |
| `YIELDED` | Daemon only: a real request arrived. The sustained phase never resumes within the same run |

### 6.5 Cool-down

- Before each tier and before the sustained phase, poll every 5 s until the device is COOL, up to the plan's maximum wait.
- The wait is shown to the user ("Letting the device cool: 1 min 20 s").
- On timeout the run proceeds, but every test in that tier records `startThermal: "warm"`, which caps its confidence (§9.4).
- A sustained phase that starts warm gets confidence **low**.

---

## 7. Energy and battery

| Method | Platforms | What it measures | Condition | Label in the report |
|---|---|---|---|---|
| `odpm` | Android devices with power-monitor rails [BV05] | Σ Δ energy (µW·s) over `POWER_MONITOR_TYPE_MEASUREMENT` rails | Works on AC too: readings are "total energy: both on-battery and plugged-in" [BV05] | "sum of this device's power rails (coverage varies by device)" |
| `battery-counter` | Android; Linux laptops and Deck; macOS laptops [BA08] | Δ charge counter (µAh) × mean voltage (mV) × 36 / 10 → µJ | Unplugged; Δ ≥ 10,000 µAh | "whole device, from the battery" |
| `battery-current` | Android; Linux | ∫ current × voltage, sampled at 1 Hz (Android µA, positive into the battery [BV03]; Linux µA, negative when discharging [BV15]) | Unplugged; cross-checked (below) | "whole device, from the battery" |
| `powermetrics-user` | macOS (CLI only) | Parses a log the *user* produced with `sudo powermetrics`. The CLI never asks for root | root is required [BV13] | "CPU, GPU and ANE estimate from powermetrics" |
| `rapl` | Linux x86 | Package `energy_uj` | Only if readable. It is root-only since Linux 5.10 (PLATYPUS) [BV14] | "CPU package only" |
| `unavailable` | iOS and iPadOS; desktops on AC without the above | — | — | "not measurable on this device" |

**Unit sanity check (Android).**
- Some devices report `CURRENT_NOW` in mA instead of the documented µA [BV04].
- Compare the integrated current against the Δ charge counter:
  - ratio within [800, 1250]‰ → consistent;
  - ratio within [0.8, 1.25]‰ (a factor of 1000 off) → treat the readings as mA, scale by 1000 and flag `UNIT_CORRECTED`;
  - otherwise → discard the current method.

**Outputs** (battery plan only):
- `microJoulesPerToken`;
- `avgMilliW`, equal to µJ divided by duration in ms;
- `drainPermillePerHour`, from level ticks, as a coarse figure;
- `method`, `screenOn`, `durationMs`.

The report says, for example: "Writing 1,000 tokens with Qwen3-4B used about X J, about Y% of a full battery (measured, unplugged, screen on)." Y uses a capacity estimate of charge counter ÷ level.

**iOS.** `batteryLevel` moves in 5% steps, which Apple states is "expected and intended behavior" [BV08]. There is no public power API, so a 5-minute run cannot be resolved. The report says "not measurable on iPhone/iPad" and does not guess.

**Does not guarantee:**
- Whole-device numbers include the screen and radios.
- Rail coverage is device-specific [BV05].
- Fuel-gauge resolution varies.
- Energy on a desktop running on AC is mostly unmeasurable without root access or an external meter.

---

## 8. Measurement artifacts and repeatability

| Artifact | Mitigation | Recorded as |
|---|---|---|
| Cold vs warm file cache | Load is measured separately (1 cold, 3 warm). Throughput is measured only after warm-up, with the model resident. Linux: `posix_fadvise(DONTNEED)` on the file before the cold load. It needs no privilege [BV16], but does not evict pages another process has mapped [BA03]. `drop_caches` is not used: it needs root. Android and iOS: no eviction control | `loadColdness: evicted \| best-effort \| unknown` |
| First-use compilation (Metal, Vulkan and OpenCL pipelines) | One discarded warm-up per test kind. llama-bench also warms up by default [BV01] | — |
| CPU frequency ramp-up (DVFS) | Warm-up, plus 500 ms between reps | — |
| Background load | Contention spin: a fixed single-thread integer loop, about 200 ms. `contention‰ = (spin − best) × 1000 / best`. "Best" is the lowest spin this fingerprint has recorded; for the before-and-after comparison within a run it is the run's own baseline. Linux also reads `/proc/stat`. Android apps cannot read `/proc/stat` [BV07], hence the spin. The Deck also checks GPU busy% of other processes. The run refuses to start above 300‰ | `contentionBefore/AfterPermille`; confidence caps (§9.4) |
| Starting thermal state | Cool-down gate; per-tier start class | `startThermal`, `startThermalCode` |
| Charging heat and power policy | Power source recorded; B4 separation; charger required for heat tests | `powerSource`, `charging` |
| OS power modes | Battery saver or Low Power Mode refuses the start. Linux governor and power profile recorded. Android sustained-performance mode is never enabled [BV26] | `presence`, `cpuGovernor` |
| Screen state and brightness | Standalone mobile: screen on, own-window brightness fixed low, restored afterwards [BA09] | `screenOn` |
| Memory pressure and swap | Swap growth > 256 MiB during a tier sets the flag `SWAPPED` and caps confidence at low | `swapDeltaBytes` |
| Threads and affinity | The engine's default policy is pinned per shell release | `engine.threads` |
| Timer resolution | Monotonic µs. Every timed operation lasts ≥ 100 ms | — |
| Virtualisation (CI) | Detected [BA13]; confidence capped at medium; a report banner says "VIRTUAL MACHINE" | `device.virtualized` |
| Harness overhead | Host timing definition (§2.3); parity check (§2.6) | `coreImpl`, `timingSource` |
| Tokenizer | Tests use token ids; one tokenizer across the whole set; TTFT includes tokenizing a fixed text | — |
| Order effects | Smallest tier first, plus cool-down gates | per-tier `startThermal` |

---

## 9. Statistics and confidence (M04, normative)

### 9.1 Arithmetic

- All values are non-negative integers ≤ 2⁵³ − 1 [C1].
- All division is floor division.
- Intermediate products fit in a signed 64-bit integer. The largest is `tokens × 10⁹` with tokens ≤ 10⁶, giving ≤ 10¹⁵.
- There are no floats anywhere in M04.

### 9.2 The value of a test

- Every rep yields one integer.
  - Rate tests (`pp`, `tg`) convert each rep: `rate = tokens × 10⁹ / micros` (mtps).
  - Duration fields (TTFT, load) stay in µs.
- **Conservative median** over the kept reps:
  - for rates, higher is better, so take the **lower** median `x[(n−1)/2]`;
  - for durations, lower is better, so take the **upper** median `x[n/2]`.

  For odd n both reduce to the ordinary median. For even n, the pessimistic choice is deliberate (B10).
- **Paired spans:** for `ppP@d0`, the outlier decision is made on the rate. The paired TTFT sample from an excluded rep is excluded with it.
- **`relSpreadPermille`** = (max − min) × 1000 / value, over the kept reps.

### 9.3 Outliers

```
med = lowerMedian(x);   mad = lowerMedian(|x_i - med|)
if mad > 0:  outlier(x_i) <=> |x_i - med| * 1000 > 4449 * mad        // > 3 scaled MADs (3 × 1.483)
else:        outlier(x_i) <=> |x_i - med| * 1000 > 250 * med         // > 25% from the median
if count(outliers) > floor(n / 5):   keep everything, flag UNSTABLE
else:                                exclude the outliers, flag OUTLIER_EXCLUDED if any
```

### 9.4 Confidence classes

The first matching row gives the base class:

| Class | Conditions |
|---|---|
| high | n ≥ 5, kept ≥ 4, relSpread ≤ 50‰, contention ≤ 50‰, not UNSTABLE |
| medium | kept ≥ 3, relSpread ≤ 150‰, contention ≤ 150‰ |
| low | kept ≥ 2 |
| insufficient | kept < 2. No value is reported: `null` plus a reason |

Then apply the caps, taking the minimum:

| Condition | Cap |
|---|---|
| tier start class not COOL | medium |
| `virtualized` | medium |
| `timingSource: stream` | medium |
| phase restarted after a yield | medium |
| `SWAPPED` | low |
| `bgContinued` (iPad background continuation, §14.2) | low |

The overall report confidence is the minimum over the headline tier's results and the sustained result.

### 9.5 Seed vectors (illustrative; from `bench_ref.py`, see `m04-seed-vectors.txt`)

| Id | Input (µs per rep) | Output |
|---|---|---|
| M04-001 | tg128: 17.28, 17.35, 17.31, 17.265, **23.0** s | 7394 mtps, kept 4, `OUTLIER_EXCLUDED`, high |
| M04-002a | tg128: 10, 10, 10, 10, **13** s (MAD = 0; the rate is 23% low) | kept 5, spread 230‰, **low**: the sample stays because it is under the 25% cut |
| M04-002b | same, but the last rep is **14** s (29% low) | kept 4, `OUTLIER_EXCLUDED`, high |
| M04-003 | two deviant reps out of five | `UNSTABLE`, none excluded, low |
| M04-004 | pp512 paired spans; rep 5 is an outlier | 59743 mtps; TTFT = upper median of 4 kept = 8,572,100 µs |
| M04-005 | a warm start with a tight spread | medium (cap) |
| M04-006 | contention 120‰ | medium |
| M04-007 | sustained, flat for 10 min | onset `null`, stability 1000‰, high |
| M04-008 | sustained, onset at 450 s, ends at 510 s | plateau-not-reached flag, stability 800‰, medium |

---

## 10. Active and passive: combination and decay

### 10.1 Passive samples (daemon shells only)

**Origin.** A passive sample is produced by the same completion event that builds the request's `RouteRecord`. That is one code path, so `tokensIn` and `tokensOut` agree with the ledger (Invariant 9's spirit).

**Record.** It is local only and never leaves the device:

```
{ modelSha256, backendKey, fingerprint, promptTokens, cachedTokens, genTokens,
  prefillMicros, decodeMicros, ttftMicros, depthAtStart, thermalCodeStart, thermalCodeEnd,
  powerSource, origin: "local" | "peer", dayUtc }
```

It contains no content, no app id and no peer id. `trust.md` §10.6 requires the same for peer-served requests.

**Filters:**
- A prefill sample is valid only if `promptTokens − cachedTokens ≥ 64`. Cached-prefix reuse makes TTFT incomparable otherwise.
- A decode sample is valid only if `genTokens ≥ 16`.
- Drop any sample taken `duringBench`, any request cancelled before 16 tokens, and anything run with concurrency > 0.

**Retention:** 90 days.

### 10.2 Buckets and the integer EWMA

- **Bucket key:** `(modelSha256, backendKey, fingerprint, depthBand, thermalBand, powerSource)`, where:
  - `depthBand` ∈ {`0-1k`, `1k-4k`, `4k-16k`, `16k+`}, by depth at start plus prompt length;
  - `thermalBand`: `cool` = code 0; `warm` = codes 1–2; `hot` = codes 3 and above.
- **Update rule:** `e ← (7·e + x) / 8` (floor; α = 1/8; effective memory of about 15 samples). The first sample initialises it.
- **Also stored:** `n`, saturating at 10⁶, and `lastDayUtc`.
- **Why not reuse v1's `LatencyTracker`:** it uses a double with α = 0.3 and tracks providers, not local models. The bench store must be integer and M04-reproducible.

### 10.3 Weights and decay

```
decay(ageDays, halfLifeDays):
    q = ageDays * 4 / halfLifeDays
    return q >= 40 ? 0 : BASE[q % 4] >> (q / 4)            // BASE = [1000, 840, 707, 594] = 1000·2^(-k/4)
activeWeight  = {high:1000, medium:600, low:250, insufficient:0}[conf] * decay(age, 180) / 1000
passiveWeight = min(1000, 50 * n) * decay(daysSince(lastDayUtc), 14) / 1000
```

**Half-lives.** Active results keep a long half-life (180 days), because the hardware is fixed and software changes are handled by hard invalidation (§10.5). Passive buckets decay quickly (14 days), because real use drifts.

### 10.4 Combination (local router and governors only; never signed)

```
for key (model, backend, depthBand, thermalBand, powerSource):
    a  = active estimate for the key:
           tg at matching depth, if the active run's start class and power source match; or,
           for warm/hot bands, tg128@d0 * stability / 1000 from the sustained result (basis: estimated)
    p  = passive EWMA
    wa, wp = weights (§10.3)
    if wa + wp == 0: unknown
    elif wa >= 250 and wp >= 250 and |a - p| * 1000 > 250 * max(a, p):
        value = min(a, p); flag DIVERGENT                   // anti-flattery
    else:
        value = (wa * a + wp * p) / (wa + wp)
```

**What gets signed.** The signed document carries active results and the coarse `field[]` summary (§13.2), separately. It never carries the combined value, which is local policy, not an observation.

**How the report uses divergence.** It shows field-versus-test divergence as a note, for example: "In everyday use … wrote 12.9 tokens/s, 10% below this test."

### 10.5 Invalidation

```
fingerprint = base64url(SHA-256(JCS({ osBuild, gpuDriver, engine.commit, engine.backend,
                                      engine.buildFlags, engine.threads, engine.gpuLayers,
                                      engine.kvType, engine.flashAttn })))
```

When the fingerprint changes:
- Earlier active results are marked `stale`. They are kept in local history and excluded from the current document.
- Passive buckets start fresh under the new key.
- The dashboard shows a single dismissible hint (§11.6).

### 10.6 What the router and governors receive

```kotlin
interface BenchCalibration {                                  // local; router.md consumes it
    fun decode(model: ModelKey, depth: DepthBand, thermal: ThermalBand, power: PowerSource): Estimate?
    fun prefill(model: ModelKey, promptTokens: Int, power: PowerSource): Estimate?
    fun throttle(model: ModelKey): ThrottleProfile?          // onsetMs, stabilityPermille, headroomAtOnsetPermille
    fun maxResidentWeightBytes(): Estimate?
    fun governorHints(): GovernorHints
}
data class Estimate(val value: Long, val unit: Unit, val confidence: Confidence,
                    val basis: Basis /* MEASURED | FIELD | COMBINED | ESTIMATED */, val weightPermille: Int)
data class GovernorHints(val queueHeadroomPermille: Int, val continuousLoadBudgetMs: Long?)
```

**Governor tuning rule** (this satisfies the roadmap P6 gate "a benchmark sample provably shifts a governor threshold"):
- `queueHeadroomPermille = clamp(headroomAtOnsetPermille − 50, 600, 900)`, taken from the latest sustained result of high or medium confidence.
- If no onset was observed, it is 900.
- The default before any result is 750 (`platforms.md` §2.1).
- `continuousLoadBudgetMs = onsetMs`.
- v2 P4 uses the first value for RUN→QUEUE. `router.md` may use the second to prefer another node for long generations.
- The gate test: feed a synthetic sustained result with onset headroom 830‰, and assert that the threshold moves from 750 to 780.

### 10.7 Observing peers (claims versus observations)

When a peer serves this node's requests, the requester records a *remote observation*:
- `obsDecode = (genTokens − 1) × 10⁹ / (tLastTokenArrival − tFirstTokenArrival)`;
- `obsTtft`, measured at the requester.

**Ratio.** Compare `obsDecode` with the peer's signed `decode` p50 for the same file, backend and depth band: `ratio‰ = obs × 1000 / claim`. Keep an EWMA of the ratio per (peer, model), using §10.2's rule. After ≥ 10 observations with an EWMA below 700‰, flag "claims not borne out".

`router.md` owns what the flag does.

**Honest limit.** The observation includes network delay, the peer's queue and its thermal state. A low ratio can be innocent. A lying peer that is *also* slow enough to match its lie cannot be detected this way.

---

## 11. Consent and safety governors

### 11.1 Consent

Every active run is preceded by a consent sheet whose exact text is generated by the core. The UI's "Start" action must submit the sha256 of the text it displayed (`ConsentSheet.confirm`); that is the only way to mint the `ConsentToken` a session requires (B5). Tokens expire 5 minutes after minting and cover exactly one run.

Standard sheet (the `{…}` fields are filled from preflight):

```
Run the standard device test?
- Takes about {20-30} minutes. You can stop at any time.
- Makes the device warm and uses power. Keep it on its charger.
- Downloads {4.3 GB} of test models over Wi-Fi first ({T1, T2}).
  [ ] Also test 8B models (+5.0 GB). Without this, 8B speed is estimated.
- Nothing is uploaded. Results stay on this device unless you share them.
- If the device gets too hot, the test stops by itself.
[Not now]   [Start]
```

**CLI:**
- Interactive runs print the same text and read `y/N`.
- Non-interactive runs require `--yes=<plan-id>`. A bare `--yes` is rejected, because the flag must name the plan it consents to.

### 11.2 Preflight gates (all must pass)

Each failure is shown in plain words, with a reason code.

| Gate | Applies to | Reason code |
|---|---|---|
| Engine present; bench-set files verified or downloadable | all | `NO_ENGINE`, `MODELS_MISSING` |
| Free storage ≥ downloads + 1 GB | all | `STORAGE` |
| Start class COOL. Otherwise offer to wait (§6.5) | all | `TOO_WARM` |
| Battery saver or Low Power Mode off | battery devices | `POWER_SAVER` |
| Charger connected, when the plan requires it | battery devices | `NEEDS_CHARGER` |
| Battery ≥ plan floor, even on the charger (quick 300‰, battery plan 600‰, others 500‰) | battery devices | `BATTERY_LOW` |
| Battery temperature ≤ 35.0 °C, where readable | Android, Linux | `BATTERY_WARM` |
| Contention ≤ 300‰; on the Deck, other-process GPU busy < 200‰ for 10 s | all / Deck | `DEVICE_BUSY` |
| No in-flight requests; peer availability moved to ARMED(`benchmarking`) (§11.7) | daemon shells | `SERVING` |
| App in foreground with the screen on | mobile standalone | `NOT_FOREGROUND` |
| At least T0 fits | all | `NOT_ENOUGH_MEMORY` |
| Not virtualised, unless the plan is `ci` or `--allow-virtual` is given | CLI | `VIRTUALIZED` |

### 11.3 Runtime ceilings

- **Soft ceiling:** the sustained phase ends 120 s after the ceiling is first crossed, so the throttled plateau is still observed. A soft ceiling during burst tests only records a flag.
- **Hard ceiling:** immediate abort. The engine is cancelled and returns within 1 s, using the mechanism behind v2 P3's "disconnect frees the engine within 1 s" gate.

| Platform | Soft | Hard |
|---|---|---|
| Android | headroom(10 s) ≥ 950‰; or battery ≥ 42.0 °C [BA01] | thermal status ≥ SEVERE; or battery ≥ 44.0 °C [BA01]; or battery < 200‰ |
| iOS / iPadOS | — (the move to `fair` is part of what is being measured) | `thermalState` ≥ serious; Low Power Mode switched on; battery < 20% (5% steps [BV08]) |
| macOS | `thermalState` serious | critical; on laptops, AC lost during the sustained phase |
| Linux / Deck | code 3 (at the passive trip) | code 4; battery ≥ 45.0 °C where readable [BA01]; Deck: other processes' GPU busy ≥ 200‰ for 10 s (a game started) |
| all | — | user Stop; app backgrounded (mobile standalone); charger removed when it was required; memory-pressure signal (Android `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)`, iOS memory warning, Linux PSI `some avg10` ≥ 20% [BA16]); wall-clock cap |

### 11.4 Governor state machine

```
IDLE --start(plan)--> PREFLIGHT --gates fail--> IDLE (reasons shown)
PREFLIGHT --gates ok--> AWAIT_CONSENT --Not now / 5 min--> IDLE
AWAIT_CONSENT --confirm(textSha)--> PREPARING (downloads, sha256 checks; cancellable)
PREPARING --> COOLING --COOL or max wait--> RUNNING(phase)
RUNNING(phase) --phase done--> COOLING | FINALIZING
RUNNING(sustain) --soft ceiling--> RUNNING(sustain, ends in 120 s)
RUNNING --real request (daemon)--> YIELDED --engine idle 30 s--> COOLING (phase restarts;
                                                                 sustain never resumes)
YIELDED --3rd yield--> FINALIZING (endReason DEVICE_BUSY)
any --hard ceiling | Stop | backgrounded | charger removed | wall cap--> ABORTING
ABORTING --engine cancelled, model unloaded, wake locks released, brightness restored--> ABORTED(reason)
FINALIZING --> DONE (derive, render, handoff)
```

- **Partial results are kept.** ABORTED keeps the completed tests, and the document records `run.abort`.
- **Transitions are local diagnostics, not ledger rows.** The ledger records egress, following the v2 P4 precedent and `platforms.md` §2.1.
- **Every exit path releases every resource.** A unit test drives each transition with fake probes and asserts that wake locks, brightness and engine state are restored.

### 11.5 Charger requirement

| Plan | Phone, tablet, handheld | Laptop | Why |
|---|---|---|---|
| quick | not required (battery ≥ 30%) | not required | Short, no heat test |
| standard / sustained | **required** | required | Heat tests drain fast. A phone's provider role is "while charging" (`platforms.md` §2.1), so the charging curve is the representative one for mesh decisions. Charging adds heat, so the curve is conservative for unplugged use. The report says "on charger" |
| battery | **must be unplugged** | must be unplugged | You cannot measure battery drain while charging |
| extended | required | required | Longest run |

### 11.6 Anti-annoyance rules

1. **No active run is ever scheduled**, whether periodic, at idle, on charge or at install. There is no API that could schedule one.
2. After an invalidation (§10.5), at most one dismissible in-app hint appears. Dismissing it suppresses hints until the next invalidation.
3. If a sustained phase completed in the last 24 h, the consent sheet says so and needs an extra tick ("Run the heat test again today").
4. **Notifications:**
   - Daemon: only the existing FGS notification while a run is active. A completion notification appears only if the user has left the dashboard.
   - Standalone: none, because the run is foreground-only.
5. Downloads are shown in bytes before consent. Wi-Fi only by default.
6. "Not now" is the default button.

### 11.7 Coexistence with the daemon

- **At start:** set provider availability to ARMED with reason `benchmarking` (CB4 extends `platforms.md` §2.1). The listener closes (DRAINING with `graceMs`), and the run waits for in-flight requests to reach zero.
- **When a local app request arrives:**
  - The benchmark yields within 1 s and the request is served normally. `local-only` is served too; no new error code is needed.
  - The interrupted phase restarts after 30 s of engine idle.
  - Passive samples taken while a benchmark is active are tagged `duringBench` and dropped.
- **Ledger:** the benchmark itself writes no rows, because it uses no network. Model downloads write `download` rows.

### 11.8 What the governors do NOT guarantee

- They react to signals the OS exposes.
  - Android headroom "only attempts to track … slow-moving sensors, such as the skin temperature sensor" [BV02]. Fast hotspots can precede it.
  - iOS exposes only four coarse states [BV11].
- The device may still become noticeably warm. This is disclosed on the consent sheet.
- The battery-temperature ceilings are conservative defaults, not certified safety limits [BA01].
- Heat while charging contributes to battery wear, which the governors do not measure.
- If the app is killed, the in-process engine dies with it, so the heat source stops too. There is no guarantee beyond that.

---

## 12. The plain-text report (the M05 body)

### 12.1 Single source

- **The text is generated, never stored separately.** `render(doc)` is a pure function. The document stores `render.renderer = "asom.text/1"` and `render.textSha256`, which is base64url(SHA-256(text)).
- **Order of operations.** The text is rendered from everything except the `render` member, then its hash is inserted. There is no circularity.
- **What a subscriber can check:**
  - *With the text and the signed document, but no renderer:* compare hashes.
  - *With a renderer:* re-render and compare bytes.

`manifest.md`'s draft viewer report (its M05) adds a VERIFICATION header computed by the viewer. §13.4 R7 proposes that the viewer's report be that header followed by *this* body, rendered from the verified payload, so the owner and every subscriber see the same answers in the same words.

### 12.2 Honesty rules (T1–T10)

| # | Rule |
|---|---|
| T1 | Every number is followed by, or grouped under, *measured* or *estimated*. Anything else reads "not measured" and gives the reason |
| T2 | An estimate states its basis in one clause ("estimated from the measured speeds and heat test") |
| T3 | Speeds are floored to 0.1 tokens/s. Capacity is floored to 0.1 GB. File sizes round half-up to 0.1 GB. Percentages are floored. Estimated durations are floored to 10 s, measured ones to 5 s |
| T4 | If there is a sustained result, the throttled number appears next to the starting number |
| T5 | Low or insufficient confidence is shown next to the value, with a reason |
| T6 | The DEVICE block says "as reported by the device itself" |
| T7 | The WHAT THIS REPORT DOES NOT TELL YOU block is always present and fixed |
| T8 | ASCII only (non-ASCII in device strings becomes `?`); LF line endings; ≤ 72 characters per line; no colour, no emphasis by colour (Invariant 6); verdicts in capitals, so meaning never depends on hue |
| T9 | Fixed English template, no locale (§12.8) |
| T10 | The report never compares this device with another device. Comparison belongs to `router.md` and the subscriber's UI |

### 12.3 Editorial thresholds (`asom.text/1` constants; owner-approved, BD5)

| Term | Meaning |
|---|---|
| **comfortable** | decode ≥ 10.0 tokens/s **and** 512-token TTFT ≤ 2.0 s |
| **usable** | decode ≥ 4.0 tokens/s **and** TTFT ≤ 10 s |
| **too slow for chat** | anything worse |

These are editorial choices, and the report states them in words ("We call a model comfortable at…"). They are not claims about reading speed.

### 12.4 The five questions and their derivations (all in M04; results in `derived`)

**Q1. Can this device run a 7–8B model comfortably?**
- If T3 was measured and its numerics are not `fail`: use T3's `tg128@d0` p50 and its measured TTFT (from `pp512@d0`). Basis: `measured`.
- Else, if T3 *fits* (§4.4) and T2 was measured, scale **through the origin** from T2:
  - `dec = T2.dec × T2.bytes / T3.bytes`;
  - `ttft = T2.ttft × T3.bytes / T2.bytes`;
  - basis `estimated`, worded "estimated from the 4B result".

  This assumes time per token is `a + b·bytes` with a ≥ 0, so pure proportional scaling overestimates the time: it is conservative [BA07].
- Else: `cannot-hold` if T3 does not fit, otherwise `not-measured`.
- Verdict per §12.3.

**Q2. What is the largest model it can hold?**
- `kvRatio‰ = kvBytesPerToken(L) × 4096 × 1000 / bytes(L)`, where L is the largest loaded tier (numerics not `fail`).
- `maxHold = (usable − 314,572,800) × 1000 / (1000 + kvRatio)`. Basis `estimated`.
- `approxParams = maxHold × 1000 / 610`, worded "roughly an N-billion-parameter model at 4-bit".
- The largest tier actually loaded is also stated. Basis `measured`.

**Q3. How long will a 2000-token answer take?**
- Use the headline tier H and its measured TTFT for a 512-token prompt.
- `depthRatio‰ = tg128@d2048 × 1000 / tg128@d0`, or 1000 if not measured.
- If H is the sustain tier:
  - `effPeak = peak × depthRatio / 1000` and `effPlat = plateau × depthRatio / 1000`;
  - with no onset: `gen = 2000 × 10⁹ / effPeak`;
  - otherwise, `byOnset = effPeak × onsetµs / 10⁹`, and `gen = onsetµs + (2000 − byOnset) × 10⁹ / effPlat` (or the peak-only formula if `byOnset ≥ 2000`).
- If H is not the sustain tier, use `tg128@d0 × depthRatio` with no thermal model.
- Answer: `ttft + gen`, basis `estimated`, "starting cool".

**Q4. Will it throttle after N minutes?** Report `onsetMs`, `stabilityPermille` and `durationMs` from §6.3, basis `measured`.
- With no onset: "No slowdown seen during {duration}".
- If there was no sustained run: "Not measured (the heat test was not run)".

**Q5. What role suits it in a mesh?** See §12.5.

### 12.5 Mesh-role decision table (first match)

A tier's *plateau* is the measured plateau if it is the sustain tier. Otherwise it is `tg128@d0 × stability / 1000` (estimated).

| # | Condition | Role code | Wording |
|---|---|---|---|
| 1 | platform ∈ {ios, ipados} | `requester-foreground-helper` | "REQUESTER (HELPS ONLY WHILE OPEN): iPhone and iPad apps cannot serve other devices in the background; an iPad can lend compute while the app is open." [V01] |
| 2 | form ∈ {desktop, server} and T3 plateau ≥ 10,000 mtps | `strong-provider` | "STRONG PROVIDER: can serve 7-8B models to your other devices." |
| 3 | form ∈ {desktop, server, laptop, handheld} and T2 plateau ≥ 10,000 | `small-model-provider` | "PROVIDER FOR SMALL MODELS: can serve 4B-class models to your other devices." |
| 4 | form ∈ {phone, tablet} and T2 plateau ≥ 8,000 | `occasional-helper` | "OCCASIONAL HELPER: can run small models for your other devices while charging; send 7-8B work to a stronger device when one is available." |
| 5 | otherwise | `requester` | "REQUESTER: best used to send work to your other devices." |

**Suitability is not permission.** The report never says a device *is* lending. Lending is the user's switch (`trust.md` §7).

### 12.6 Template and formatting

The layout is fixed:
1. Header lines.
2. DEVICE block.
3. ANSWERS 1–5.
4. DETAILS table (per tier: size, read, write, write@2k, start@512).
5. NOTES: outlier exclusions, low-confidence items, numerics verdicts, field-vs-test divergence, and "not measured" reasons, in document order.
6. WHAT THIS REPORT DOES NOT TELL YOU.

Every branch (verdict × basis, onset / no onset / not run, each role, each energy method, and the null wordings) is an M05 vector. Formatting functions are fully specified (see `bench_ref.py`: `f_rate`, `f_gb`, `f_gb_file`, `f_dur`, `f_pct`).

### 12.7 Worked example (synthetic input; generated, not hand-written)

The input is `raw-example-phone.json`, a synthetic 16 GB phone. The plan is `standard` with T3 opted in, on the charger. The output below is `example-phone.txt`, byte for byte (sha256 `241ca68f…2c4`). The document's `render.textSha256` is `JBymj2Pls2JL-lNnpVQiFd3_LvN1G1K9KDorzCjL4sQ`, the base64url form of the same hash.

```
ASOM DEVICE REPORT (asom.text/1)
Generated from this device's benchmark data. (measured) = timed on
this device. (estimated) = calculated from measured numbers.

DEVICE (as reported by the device itself)
  Example Phone X1 (synthetic) - android 16
  Chip: Example SoC 8 | Memory: 16.0 GB total, 9.6 GB free at start
  Engine: llama.cpp 01234567, opencl backend
  Tested: 2026-09-29, standard test, on charger, started cool
  Overall confidence: HIGH

ANSWERS
1. Can it run a 7-8B model comfortably?
   USABLE, NOT COMFORTABLE (measured with Qwen3-8B 4-bit).
   It writes about 7.3 tokens/s and starts answering a 512-token
   prompt after 8.5 s. We call a model comfortable at 10
   tokens/s or more and under 2 s to start.
2. What is the largest model it can hold?
   About 6.1 GB of model file (estimated), roughly a
   10-billion-parameter model at 4-bit. Largest actually loaded:
   Qwen3-8B 4-bit, 5.0 GB file (measured).
3. How long will a 2000-token answer take?
   About 5 min 50 s with Qwen3-8B 4-bit for a 512-token
   prompt, starting cool (estimated from the measured speeds and
   heat test).
4. Will it slow down when it gets warm?
   Yes. After 3 min 15 s of continuous writing, speed fell to
   66% of its starting speed and stayed there (measured for
   7 min 15 s, on charger, with Qwen3-8B 4-bit).
5. What role suits it in a group of your devices?
   OCCASIONAL HELPER: can run small models for your
   other devices while charging; send 7-8B work to a stronger
   device when one is available.

DETAILS (tokens per second, higher is better; start = seconds)
  model              size     read   write  write@2k  start@512
  Qwen3-1.7B 8-bit   1.8GB    259.6    20.9         -        1.9
  Qwen3-4B 4-bit     2.5GB    129.9    14.4         -        3.9
  Qwen3-8B 4-bit     5.0GB     59.7     7.3       6.5        8.5

NOTES
  - Qwen3-1.7B 8-bit output check: pass (0.3% from reference).
  - Qwen3-4B 4-bit output check: pass (0.2% from reference).
  - Qwen3-8B 4-bit, reading a 512-token prompt: 1 of 5 timings discarded
    as outliers.
  - Qwen3-8B 4-bit output check: pass (0.6% from reference).
  - In everyday use (10-99 requests, on battery), Qwen3-4B 4-bit wrote
    12.9 tokens/s, 10% below this test.
  - Battery use: not measured (needs an unplugged battery test).

WHAT THIS REPORT DOES NOT TELL YOU
- This device tested itself. A signature proves the report was not
  changed after it was made, and which device key made it. It does
  not prove the test ran honestly or that the device is what it says.
- Speeds are for the listed test models. Other models of the same size
  usually behave alike, but not always (mixture-of-experts models
  differ most).
- Heat, battery level, other apps and long conversations change speed.
- Nothing here measures how good the answers are.
```

**How each answer traces to the document** (excerpt below):

| Answer | Source in the document |
|---|---|
| Q1: 7.3 tokens/s | `tg128@d0` value 7394 mtps, floored |
| Q1: 8.5 s | TTFT 8,572,100 µs, the upper median of the 4 kept whole spans |
| Q2: 6.1 GB | `(7,200,000,000 − 314,572,800) × 1000 / 1120` = 6,147,702,857 |
| Q3: about 5 min 50 s | 355,499,032 µs, floored to 10 s. Depth ratio 892‰, so effective peak 6,764 and effective plateau 4,489 mtps. Total = 8.57 s TTFT + 195 s to onset (1,318 tokens) + 682 tokens at the plateau (151.9 s) |
| Q4: 66% | stability 663‰, floored |
| Q5: occasional helper | the T2 estimated plateau, 14,492 × 663‰ = 9,608 mtps, is ≥ 8,000 |

Document excerpt (`example-phone.doc.json`; T1, T2 and 26 windows omitted):

```json
{
  "schema": "asom.bench/1", "benchProtocol": 1, "benchSet": "qwen3-dense-1",
  "harness": { "shell": "android-daemon", "coreImpl": "jvm", "coreVersion": "1.0.0", "confVersion": "1.0.0",
               "timingSource": "host-monotonic",
               "engine": { "name": "llama.cpp", "commit": "0123456789abcdef0123456789abcdef01234567",
                           "backend": "opencl", "buildFlags": "GGML_OPENCL=ON", "threads": 6,
                           "gpuLayers": 99, "kvType": "f16", "flashAttn": "auto" } },
  "device": { "platform": "android", "form": "phone", "maker": "Example", "model": "Phone X1 (synthetic)",
              "soc": "Example SoC 8", "osVersion": "16", "osBuild": "EXAMPLE.260901.001",
              "gpu": "Example GPU", "gpuDriver": "example-512.0", "memTotalBytes": 16000000000,
              "unifiedMemory": true, "virtualized": false },
  "memory": { "availAtStartBytes": 9600000000, "processLimitBytes": null, "gpuWorkingSetBytes": null,
              "limitSource": "android-availmem" },
  "run": { "plan": "standard", "optInTiers": ["T3"], "startedAtMs": 1790668800000, "endedAtMs": 1790670240000,
           "powerSource": "ac", "batteryStartPermille": 740, "startThermal": "cool", "screenOn": true,
           "contentionBeforePermille": 12, "contentionAfterPermille": 18, "abort": null, "dayUtc": "2026-09-29" },
  "tiers": [
    { "tier": "T3", "sha256": "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785",
      "bytes": 5027783488, "quant": "Q4_K_M", "startThermal": "cool", "startThermalCode": 0,
      "startedAtMs": 1790669190000, "nCtx": 4096, "availBeforeLoadBytes": 9510000000,
      "peakFootprintBytes": 5980000000, "kvBytesPerToken": 147456, "loadColdMicros": 9800000,
      "loadColdness": "best-effort", "loadWarmMicros": 2395000,
      "results": [
        { "test": "pp512@d0", "depth": 0, "unit": "mtps",
          "samples": [8533000, 8611000, 8498000, 8570000, 9950000], "value": 59743, "kept": 4,
          "keptIdx": [0, 1, 2, 3], "relSpreadPermille": 13, "confidence": "high", "flags": ["OUTLIER_EXCLUDED"],
          "ttftSamples": [8535100, 8613000, 8500200, 8572100, 9952300], "ttftMicros": 8572100 },
        { "test": "tg128@d0", "depth": 0, "unit": "mtps",
          "samples": [17280000, 17350000, 17310000, 17265000, 17420000], "value": 7394, "kept": 5,
          "keptIdx": [0, 1, 2, 3, 4], "relSpreadPermille": 8, "confidence": "high", "flags": [] }
      ],
      "numerics": { "test": "nll1024", "milliNatsPerToken": 1893, "refMilliNatsPerToken": 1880,
                    "deviationPermille": 6, "verdict": "pass" } }
  ],
  "sustain": { "test": "sustain", "tier": "T3", "windowMs": 15000, "capMs": 600000,
               "windows": [[0, 113, 14900000, 0], [15000, 112, 14900000, 0], [30000, 113, 14900000, 0]],
               "peakMtps": 7583, "plateauMtps": 5033, "onsetMs": 195000, "stabilityPermille": 663,
               "durationMs": 435000, "endReason": "PLATEAU", "thermalCodeAtOnset": 2,
               "headroomAtOnsetPermille": 830, "confidence": "high", "flags": [] },
  "energy": null,
  "field": [ { "tier": "T2", "depthBand": "0-1k", "thermalBand": "cool", "powerSource": "battery",
               "countClass": "10-99", "decodeMtpsEwma": 12900, "prefillMtpsEwma": 121000,
               "lastDayUtc": "2026-09-27" } ],
  "derived": {
    "usableMemoryBytes": 7200000000, "safetyPermille": 750,
    "maxHold": { "weightBytes": 6147702857, "kvRatioPermille": 120, "approxParamsQ4": 10078201404,
                 "largestLoadedTier": "T3", "basis": "estimated" },
    "q7b": { "tier": "T3", "basis": "measured", "decodeMtps": 7394, "ttft512Micros": 8572100, "verdict": "usable" },
    "answer2000": { "tier": "T3", "promptTokens": 512, "genTokens": 2000, "depthRatioPermille": 892,
                    "micros": 355499032, "thermalModel": true, "basis": "estimated" },
    "throttle": { "tier": "T3", "onsetMs": 195000, "stabilityPermille": 663, "testedMs": 435000, "basis": "measured" },
    "role": { "code": "occasional-helper", "t2PlateauMtps": 9608, "t3PlateauMtps": 5033 },
    "overallConfidence": "high"
  },
  "render": { "renderer": "asom.text/1", "textSha256": "JBymj2Pls2JL-lNnpVQiFd3_LvN1G1K9KDorzCjL4sQ" }
}
```

### 12.8 Language

`asom.text/1` is English only, and deliberately so. Byte-exact cross-implementation rendering (M05) and a hash inside the signed data require one fixed template.

- **Localisation later:** a separate renderer id (for example `asom.text.de/1`), each with its own vectors. The document can carry several `{renderer, textSha256}` pairs; this is an additive change.
- **Rejected:** any "translated at display time" approach, because it would let the displayed words diverge from the signed ones.

---

## 13. The measurement document and the signer handoff

### 13.1 Ownership split

| Concern | Owner |
|---|---|
| Semantics, units and derivation of every benchmark number; the measurement document `asom.bench/1`; the M05 body; the projection into `results[]` | **benchmark.md** |
| Envelope (DSSE in `manifest.md`'s draft), keys, `subject`, `seq`, audience, issued and expiry times, challenge, attestation, verification, the viewer's VERIFICATION header, the public derivative (its draft M06) | **manifest.md** |

### 13.2 Schema of `asom.bench/1` (draft 2020-12; the normative subset)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "asom:schema/asom.bench/1",
  "type": "object", "additionalProperties": false,
  "required": ["schema","benchProtocol","benchSet","harness","device","memory","run","tiers","custom",
               "sustain","energy","field","derived","render"],
  "properties": {
    "schema": { "const": "asom.bench/1" },
    "benchProtocol": { "type": "integer", "minimum": 1 },
    "benchSet": { "type": "string", "pattern": "^[a-z0-9-]{1,40}$" },
    "harness": { "type": "object", "additionalProperties": false,
      "required": ["shell","coreImpl","coreVersion","confVersion","timingSource","engine","planSha256"],
      "properties": {
        "shell": { "enum": ["android-daemon","android-standalone","ios-standalone","desktop-daemon","desktop-cli"] },
        "coreImpl": { "enum": ["jvm","swift"] },
        "coreVersion": { "$ref": "#/$defs/semver" }, "confVersion": { "$ref": "#/$defs/semver" },
        "timingSource": { "enum": ["host-monotonic","stream"] },
        "planSha256": { "$ref": "#/$defs/b64u32" },
        "engine": { "type": "object", "additionalProperties": false,
          "required": ["name","commit","backend","buildFlags","threads","gpuLayers","kvType","flashAttn"],
          "properties": { "name": {"type":"string"}, "commit": {"type":"string","pattern":"^[0-9a-f]{40}$"},
            "backend": {"type":"string"}, "buildFlags": {"type":"string"}, "threads": {"$ref":"#/$defs/u"},
            "gpuLayers": {"$ref":"#/$defs/u"}, "kvType": {"type":"string"}, "flashAttn": {"enum":["on","off","auto"]} } } } },
    "device": { "type": "object", "additionalProperties": false,
      "required": ["platform","form","maker","model","soc","osVersion","osBuild","gpu","gpuDriver",
                   "memTotalBytes","unifiedMemory","virtualized"],
      "properties": { "platform": {"enum":["android","ios","ipados","macos","linux"]},
        "form": {"enum":["phone","tablet","handheld","laptop","desktop","server"]},
        "maker": {"$ref":"#/$defs/t"}, "model": {"$ref":"#/$defs/t"}, "soc": {"$ref":"#/$defs/t"},
        "osVersion": {"$ref":"#/$defs/t"}, "osBuild": {"$ref":"#/$defs/t"},
        "gpu": {"$ref":"#/$defs/tn"}, "gpuDriver": {"$ref":"#/$defs/tn"},
        "memTotalBytes": {"$ref":"#/$defs/u"}, "unifiedMemory": {"type":"boolean"}, "virtualized": {"type":"boolean"} } },
    "memory": { "type": "object", "additionalProperties": false,
      "required": ["availAtStartBytes","processLimitBytes","gpuWorkingSetBytes","limitSource"],
      "properties": { "availAtStartBytes": {"$ref":"#/$defs/u"}, "processLimitBytes": {"$ref":"#/$defs/un"},
        "gpuWorkingSetBytes": {"$ref":"#/$defs/un"}, "limitSource": {"type":"string"} } },
    "run": { "type": "object", "additionalProperties": false,
      "required": ["plan","optInTiers","startedAtMs","endedAtMs","dayUtc","powerSource","batteryStartPermille",
                   "startThermal","screenOn","contentionBeforePermille","contentionAfterPermille","abort"],
      "properties": { "plan": {"enum":["quick","standard","sustained","battery","extended","ci"]},
        "optInTiers": {"type":"array","items":{"$ref":"#/$defs/tier"}},
        "startedAtMs": {"$ref":"#/$defs/u"}, "endedAtMs": {"$ref":"#/$defs/u"},
        "dayUtc": {"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
        "powerSource": {"enum":["ac","battery"]}, "batteryStartPermille": {"$ref":"#/$defs/un"},
        "startThermal": {"enum":["cool","warm","hot"]}, "screenOn": {"type":["boolean","null"]},
        "contentionBeforePermille": {"$ref":"#/$defs/u"}, "contentionAfterPermille": {"$ref":"#/$defs/u"},
        "abort": { "oneOf": [ {"type":"null"}, {"type":"object","required":["reason","atMs"],
                   "properties":{"reason":{"type":"string"},"atMs":{"$ref":"#/$defs/u"}}} ] } } },
    "tiers":  { "type": "array", "maxItems": 6, "items": { "$ref": "#/$defs/tierDoc" } },
    "custom": { "type": "array", "maxItems": 8, "items": { "$ref": "#/$defs/tierDoc" } },
    "sustain": { "oneOf": [ {"type":"null"}, {"$ref":"#/$defs/sustain"} ] },
    "energy":  { "oneOf": [ {"type":"null"}, {"$ref":"#/$defs/energy"} ] },
    "field":   { "type": "array", "maxItems": 64, "items": { "$ref": "#/$defs/field" } },
    "derived": { "type": "object" },
    "render":  { "type": "object", "additionalProperties": false, "required": ["renderer","textSha256"],
                 "properties": { "renderer": {"const":"asom.text/1"}, "textSha256": {"$ref":"#/$defs/b64u32"} } }
  },
  "$defs": {
    "u":  { "type": "integer", "minimum": 0, "maximum": 9007199254740991 },
    "un": { "oneOf": [ {"$ref":"#/$defs/u"}, {"type":"null"} ] },
    "t":  { "type": "string", "maxLength": 128 },
    "tn": { "oneOf": [ {"$ref":"#/$defs/t"}, {"type":"null"} ] },
    "semver": { "type": "string", "pattern": "^[0-9]+\\.[0-9]+\\.[0-9]+$" },
    "b64u32": { "type": "string", "pattern": "^[A-Za-z0-9_-]{43}$" },
    "tier": { "enum": ["T0","T1","T2","T3","T4","T5","custom"] },
    "conf": { "enum": ["high","medium","low","insufficient"] },
    "tierDoc": { "type": "object", "required": ["tier","sha256","bytes","quant","startThermal","startThermalCode",
        "startedAtMs","nCtx","availBeforeLoadBytes","peakFootprintBytes","kvBytesPerToken","loadColdMicros",
        "loadColdness","loadWarmMicros","results","numerics"],
      "properties": { "results": { "type": "array", "items": { "type": "object",
          "required": ["test","depth","unit","samples","value","kept","keptIdx","relSpreadPermille","confidence","flags"],
          "properties": { "test": {"type":"string","pattern":"^(pp|tg)[0-9]+(x[0-9]+)?@d[0-9]+$"},
            "samples": {"type":"array","items":{"$ref":"#/$defs/u"},"maxItems":16},
            "value": {"$ref":"#/$defs/un"}, "confidence": {"$ref":"#/$defs/conf"},
            "ttftSamples": {"type":"array","items":{"$ref":"#/$defs/u"}}, "ttftMicros": {"$ref":"#/$defs/u"} } } },
        "numerics": { "type": "object", "required": ["test","milliNatsPerToken","refMilliNatsPerToken",
                      "deviationPermille","verdict"],
          "properties": { "verdict": {"enum":["pass","warn","fail","not-run"]} } } } },
    "sustain": { "type": "object", "required": ["tier","windowMs","capMs","windows","peakMtps","plateauMtps",
        "onsetMs","stabilityPermille","durationMs","endReason","thermalCodeAtOnset","headroomAtOnsetPermille",
        "confidence","flags"],
      "properties": { "windows": { "type": "array", "maxItems": 240, "items": { "type": "array",
          "prefixItems": [{"$ref":"#/$defs/u"},{"$ref":"#/$defs/u"},{"$ref":"#/$defs/u"},{"type":"integer","minimum":0,"maximum":4}],
          "minItems": 4, "maxItems": 4 } } } },
    "energy": { "type": "object", "required": ["tier","method","durationMs","microJoulesPerToken","avgMilliW",
        "drainPermillePerHour","screenOn","flags"] },
    "field": { "type": "object", "required": ["tier","depthBand","thermalBand","powerSource","countClass",
        "decodeMtpsEwma","prefillMtpsEwma","lastDayUtc"],
      "properties": { "countClass": { "enum": ["1-9","10-99","100+"] } } }
  }
}
```

**Size.** The example's canonical bytes are 6,148, including 29 windows and every raw sample. A 15-minute extended run with six tiers stays well under 32 KB.

**The reference sketch is not a conforming producer.** It omits `custom[]`, `harness.planSha256`, energy output and the stale-history handling. It exists to prove the arithmetic and the text/JSON single-source property, not the full schema.

### 13.3 The handoff

This is what `:bench-core` gives the signer.

```kotlin
data class SignerInput(
    val benchJcs: ByteArray,                   // JCS integer-profile bytes of the asom.bench/1 document
    val benchSha256: ByteArray,
    val results: List<ManifestResult>,         // body.results[] entries, from projectResults(doc) ONLY
    val deviceFragment: DeviceFragment,        // probe values for manifest.md's body.device
    val harnessFragment: HarnessFragment,      // id "asom-bench", version = coreVersion,
                                               // methodologyId "asom-bench-method/1" <=> benchProtocol 1, confVersion
    val text: ByteArray,                       // asom.text/1 body (ASCII, LF)
    val textSha256: ByteArray,
    val freshness: Freshness,                  // measuredAtMs of each result; stale == false by construction
)
interface ManifestSigner { suspend fun sign(input: SignerInput, audience: Audience, challenge: ByteArray?): SignedManifest }
```

**Rules:**
- **The single path.** Every benchmark number in a signed manifest body is produced by `projectResults(doc)`. There is no other path (B2).
- **Recommendation to `manifest.md`.** Add the whole `asom.bench/1` document as an additive member `body.bench` for audience `own`, or else its hash as `body.benchSha256` with the document available by request. A subscriber holding the document can then:
  - re-run M04 on the raw samples and confirm that `results[]` and `derived` follow from them;
  - re-render the M05 body.

  **What this proves is derivation integrity only:** a producer cannot sign flattering summaries of unflattering samples. It says nothing about whether the samples themselves were honest.

**Projection mapping** (implemented in `bench_ref.py: project_results`; the output for the example is `example-phone.manifest-results.json`):

| `results[]` field in the `manifest.md` draft | From `asom.bench/1` |
|---|---|
| `modelId`, `fileSha256`, `fileBytes`, `quant` | bench-set pin (§4.2) |
| `backend`, `settings.{threads,gpuLayers}` | `harness.engine` |
| `settings.ctxTokens` | tier `nCtx` |
| `settings.batchTokens` | 512 |
| `measuredAtMs` | tier `startedAtMs` |
| `runs.{planned, completed, discarded}` | from the test in the tier with the most discards: n, n, n − kept |
| `conditions.charging` | `powerSource == "ac"` and the form has a battery (see R3) |
| `conditions.batteryStartPermille` | `run.batteryStartPermille` |
| `conditions.thermalStart` | name of `startThermalCode`: 0 nominal, 1 light, 2 moderate, 3 severe, 4 critical |
| `conditions.socStartMilliC` | Linux zones only; otherwise `null` [BA21] |
| `conditions.screenOn` | `run.screenOn` |
| `prefill[] {promptTokens, milliTokPerSec, ttftMicros}` | each `ppP@d0`. `p50` = the conservative median (§9.2). `p10`/`p90` = nearest-rank over the kept reps: `x[ceil(p·n/1000) − 1]` |
| `decode[] {contextTokens, genTokens, milliTokPerSec}` | each `tgN@dD` → `{D, N, pct}` |
| `sustained {durationMs, intervalMs, steadyMilliTokPerSec, throttleOnsetMs, curve}` | the `sustain` block of the sustain tier; curve rows `[tStartMs, windowRate, socMilliC\|null, powerMilliW\|null]` |
| `memory {availableBeforeLoadBytes, peakProcessBytes, kvCacheBytes}` | tier `availBeforeLoadBytes`, `peakFootprintBytes`, `kvBytesPerToken × nCtx` |
| `power {method, avgMilliW}` | `energy` for that tier; otherwise `{unavailable, null}` |
| `flags` (open enum) | `charging`, `thermal-throttled`, `background-load` (contention > 50‰), `low-runs` (kept < 4), `unstable`, `warm-start`, `numerics-warn`/`numerics-fail`/`numerics-not-run`, `confidence-<class>` |

**Projected T3 entry from the example.** This validates against the draft `asom.manifest.1.schema.json` `$defs/result`; the curve is truncated here.

```json
{ "modelId": "qwen3-8b", "fileSha256": "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785",
  "fileBytes": 5027783488, "quant": "Q4_K_M", "backend": "opencl",
  "settings": { "threads": 6, "gpuLayers": 99, "ctxTokens": 4096, "batchTokens": 512 },
  "measuredAtMs": 1790669190000, "runs": { "planned": 5, "completed": 5, "discarded": 1 },
  "conditions": { "charging": true, "batteryStartPermille": 740, "thermalStart": "nominal",
                  "socStartMilliC": null, "screenOn": true },
  "prefill": [ { "promptTokens": 512, "milliTokPerSec": {"p10": 59458, "p50": 59743, "p90": 60249},
                 "ttftMicros": {"p10": 8500200, "p50": 8572100, "p90": 8613000} } ],
  "decode": [ { "contextTokens": 0, "genTokens": 128, "milliTokPerSec": {"p10": 7347, "p50": 7394, "p90": 7413} },
              { "contextTokens": 2048, "genTokens": 128, "milliTokPerSec": {"p10": 6560, "p50": 6597, "p90": 6607} } ],
  "sustained": { "durationMs": 435000, "intervalMs": 15000, "steadyMilliTokPerSec": 5033, "throttleOnsetMs": 195000,
                 "curve": [[0, 7583, null, null], [15000, 7516, null, null]] },
  "memory": { "availableBeforeLoadBytes": 9510000000, "peakProcessBytes": 5980000000, "kvCacheBytes": 603979776 },
  "power": { "method": "unavailable", "avgMilliW": null },
  "flags": ["charging", "confidence-high", "thermal-throttled"] }
```

### 13.4 Reconciliation items with the `manifest.md` draft

These are design coordination, not owner decisions. They were found by validating the projection against the draft schema, and are to be settled in the session's reconciliation pass.

| # | Item | Proposal |
|---|---|---|
| R1 | `results[].sustained` is **required** by the draft. Only the sustain tier has one, and T1/T2 fail validation | Make it nullable: `oneOf [object, null]` |
| R2 | The draft has no confidence field | Add an optional `confidence` enum. Until then, carry it as the flag `confidence-<class>` (the flags enum is open) |
| R3 | `conditions.charging` is ambiguous on desktops (AC, no battery) | Define it as "external power connected on a battery device". Also add optional `powerSource: ac\|battery` |
| R4 | The `power.method` enum lacks `battery-counter` and `powermetrics-user`; `odpm` maps to `pmic` | Add both values |
| R5 | `prefill[]` has no context depth; `pp@dD` with D > 0 cannot be carried | The standard plan no longer measures prefill at depth (§5.1). Add optional `contextTokens` (default 0) if extended plans ever need it |
| R6 | Percentile definitions are unstated in the draft | Adopt §13.3: p50 = the conservative median; p10/p90 = nearest-rank over kept reps. Vectorised as M04-009 |
| R7 | Two plain-text renderers (the draft's viewer report and this body) | One M05 with two parts. The viewer computes the VERIFICATION and DEVICE header (`manifest.md`). The answers body is rendered by §12 from the verified payload's `bench` member. The body's hash is in the signed payload, so the owner and subscribers read identical answers |
| R8 | Where `benchSet` and `planSha256` go | Inside `body.bench`, or as optional `producer.harness.benchSet` and `producer.harness.planSha256` |

### 13.5 Sensitivity of fields, and the public projection

| Field group | Identifying? | Private manifest (own peers) | Public P7 derivative |
|---|---|---|---|
| Node key, `nodeId`, signature, `textSha256`, `benchSha256` | **yes**: stable identifiers | envelope only | **never** |
| `osBuild`, `gpuDriver`, `fingerprint`, exact `memTotalBytes`, exact timestamps | narrows the population | yes | never. Use the OS major version, a RAM class, and month or ISO week |
| `field[]` (passive) | **usage-derived** | yes, as `countClass` only | **never** (stricter than the roadmap) |
| `custom[]` model hashes | can identify a private fine-tune | yes | **never** |
| raw `samples`, `windows` | no, but not needed | yes | curve downsampled; no raw samples |
| Bench-set active results, coarse device class, SoC, engine and backend | no beyond coarse class | yes | yes |

**Integrity of the public projection.** `manifest.md`'s draft defines the public derivative as **unsigned by construction**, and it must stay unsigned (the key is a fingerprint). The receiver can therefore verify nothing about it: anyone can post a well-formed payload. It is statistics input, not evidence. P7's structural test (roadmap: "the payload builder is structurally incapable of emitting a key/content/fingerprint field") is extended to assert that the output never contains `field`, `custom`, `osBuild`, `gpuDriver`, `fingerprint` or any value from the envelope.

---

## 14. Platform specifics

### 14.1 Android (the daemon shell and the standalone `xyz.mdhv.asom.bench`)

- **Probes:**
  - `PowerManager.getThermalHeadroom`, 1 s polling [BV02];
  - `getCurrentThermalStatus` and its listener;
  - `getThermalHeadroomThresholds` where available [BV02];
  - `BatteryManager`: CHARGE_COUNTER in µAh, CURRENT_NOW in µA with positive meaning into the battery, ENERGY_COUNTER in nWh, unsupported → `null` [BV03];
  - the battery `EXTRA_TEMPERATURE` extra;
  - `SystemHealthManager` power monitors (ODPM) where the list is non-empty [BV05]. The CPU/GPU headroom APIs [BV06] are recorded as diagnostics only, because their availability by API level is unverified;
  - `ActivityManager.MemoryInfo`;
  - `Build.MANUFACTURER` and `Build.MODEL`.
- **Standalone app lifecycle:**
  - **No foreground service.** The run lives in a foreground Activity with `FLAG_KEEP_SCREEN_ON`, and own-window brightness is lowered during the run [BA09, BA15].
  - `onStop` aborts the current phase (`BACKGROUNDED`); completed phases are kept.
  - This avoids declaring any FGS type at all. `specialUse` would need a Play justification, and `dataSync`/`mediaProcessing` are the wrong semantics and are time-capped [V13].
- **Daemon shell:** runs inside the existing `specialUse` FGS, in the Benchmark tab. Passive lane on.
- **Each standalone app has its own minimal ledger** (the v1 `:ledger` module) for catalogue fetches and model downloads (Invariant 3), with the v1 export path.
- **Backends:** CPU, OpenCL (Adreno) and Hexagon rows are kept separate. The v2 P6 bakeoff is simply one `standard` run per backend.
- **When the daemon is installed on the same device:**
  - The standalone app says "asom is installed here; your other devices see asom's results" and offers to open asom's Benchmark tab.
  - **It never imports or exports results to or from the daemon.** It never re-signs another harness's measurements. This is one signer per measurement, and avoids competing manifests for one device, which is C10's logic extended to Android.
- **Device-property attestation:** `KeyGenParameterSpec.setDevicePropertiesAttestationIncluded` can put brand, device, manufacturer, model and product into the attestation certificate [BV25]. Whether to use it is `manifest.md`'s decision; the benchmark only supplies `Build` values.

### 14.2 iOS and iPadOS (`AsomBench`, S5)

- **Swift port:** pinned by M04/M05 and the projection vectors, integer-only [C1]. Mac availability is OFF [C10].
- **Foreground-only:**
  - `isIdleTimerDisabled` during a run; brightness lowered and restored [BA09].
  - `sceneDidEnterBackground` aborts the phase. iOS suspends backgrounded apps [V01].
- **Background continuation (iPad, later, optional):** `BGContinuedProcessingTask` (iOS/iPadOS 26) must be submitted from the foreground "as a result of a person's action", shows progress in a Live Activity, can be cancelled by the user, and "can terminate … abruptly" under resource constraints [BV12]. Background GPU works only on supported devices [V04].
  - If adopted, runs continued in the background are tagged `bgContinued` and capped at **low** confidence, because the foreground app's load is uncontrolled.
  - **Not in the first release.**
- **Thermal:** `ProcessInfo.thermalState`, hard stop at `serious` [BV11]. That matches the App Review 2.4.2 heat concern [V05]. There is no battery-temperature API, which is why the iOS table in §11.3 has no battery-temperature ceiling.
- **Memory:**
  - `os_proc_available_memory()` [BV09] and Metal `recommendedMaxWorkingSetSize` [BV10];
  - whether the increased-memory-limit entitlement is present is recorded [V07], because its effect is unreliable.
- **Energy:** unavailable (§7) [BV08].
- **App Review exposure:**
  - Benchmark apps with sustained stress tests exist on the App Store: 3DMark's 20-loop stress tests [BV22] and Geekbench AI [BV23]. That shows existence only, not our outcome.
  - Model downloads have a precedent [V06].
  - The first release has no upload path and no cloud calls, so 5.1.2(i) does not apply.
- **Privacy label:** the first release can truthfully declare that no data is collected [BA22]. **If P7 ever ships on iOS, the label must change**; that is a release-checklist item.
- **Backup exclusion:** `isExcludedFromBackup` on documents, ledger and models [C7].

### 14.3 macOS (the JVM `asom-bench` CLI; one harness per OS [C10])

- **Engine:** llama.cpp Metal via JNI (`platforms.md` §3.3).
- **Native shim** `libasom-platform`: `thermalState` [BV11], IOKit power sources and the AppleSmartBattery values [BA08], and Metal `recommendedMaxWorkingSetSize` [BV10].
- **Laptops:** AC is required for the sustained phase. Low Power Mode refuses the start.
- **Energy:** `powermetrics` needs root [BV13]. The CLI accepts `--powermetrics-log <file>` that the *user* captured, and never escalates privileges itself.
- **Distribution:** as `platforms.md` §3.3 (notarised; a Homebrew tap). Distributing the CLI as a Homebrew *formula* rather than a cask may avoid the September 2026 Gatekeeper-cask policy [V19, BA10].

### 14.4 Linux and the Steam Deck

- **Probes** (`platforms.md` §3.1):
  - sysfs thermal zones with trip points [BA02];
  - `power_supply` values: current in µA, negative when discharging; voltage in µV; temperature in 1/10 °C [BV15];
  - `MemAvailable`;
  - Vulkan `heapBudget` [BV18];
  - the Deck's `gpu_busy_percent`.
- **Cold load:** `posix_fadvise(DONTNEED)` [BV16].
- **RAPL:** only if readable (root since 5.10 [BV14]). The CLI prints how to grant access and never does it.
- **Steam Deck:**
  - The 16 GB is shared between CPU and GPU [V21], so the memory limit is measured, never assumed.
  - A game (other-process GPU busy ≥ 200‰) refuses the start or aborts the run.
  - Charger required for the sustained phase.
- **Packaging:** tarball, `.deb`/`.rpm` (`platforms.md`). A Flatpak is acceptable only for a later GUI.

### 14.5 CI and lab (headless)

- **CI:** plan `ci` on T0, fetched at CI time from the pinned URL and sha256 (as roadmap v2 P1 already does for a tiny GGUF), plus `--allow-virtual`.
  - **CI asserts vectors and schema validity, never timings.** A virtual machine's numbers describe no device.
  - `device.virtualized = true` caps confidence at medium and prints a banner.
- **Lab:** the owner runs `asom-bench --plan extended --sign` on their own devices. The signed documents and texts form the roadmap P6 **editorial reference table**, published in the catalogue repository. asom shows them as "reference measurements by the asom maintainer on their own devices", never merged with the user's data.

---

## 15. Distribution and the no-fork plan

| Shell | Artifact | Channel | Identity | Notes |
|---|---|---|---|---|
| Android daemon | existing APK `xyz.mdhv.asom` | sideload, F-Droid (brief §14) | existing | Benchmark tab |
| Android standalone | new APK `xyz.mdhv.asom.bench` (a new applicationId under the anchor; no rename) | F-Droid and GitHub Releases; Play optional later | its own app key | No FGS; `INTERNET` only for catalogue and downloads, ledgered; import from file for offline use. Developer verification applies from 2026-09-30 regionally [V15] (`platforms.md` OD6) |
| iOS/iPadOS | `AsomBench` | TestFlight, then the App Store [V20] | Secure Enclave key | Mac availability off [C10]. Needs the Developer Program (`platforms.md` OD2) |
| Desktop daemon | asom-desktop includes `asom bench` | per `platforms.md` | node key | same `:bench-cli` main |
| Desktop standalone | `asom-bench` | GitHub Releases tarball; `.deb`/`.rpm`; notarised macOS build; Homebrew own tap | own key | no daemon, no listener, no unit files installed |
| Headless | `asom-bench` | same | CI: unsigned; lab: owner key | `--plan ci`, `--yes=<plan>` |

**Anti-fork checklist.** This checklist is part of each shell's release gate.
- The shell uses only the public surface of §2.2.
- The shell's CI lane runs M04, M05 and the projection vectors against its packaged core.
- The document records `coreVersion` and `confVersion`.
- Any new measurement lands in `:bench-core` first; the shells follow.
- The Swift port's version tracks `confVersion`, not its own numbering.

---

## 16. Contract and roadmap deltas (all additive; each needs owner sign-off)

| # | Delta | Touches the frozen v1 §5? |
|---|---|---|
| CB1 | JNI and engine seam additions: `prefill`, batched `decodeGreedy`, `truncateKv`, `clearKv`, `nllMicroNats`, `info` (§2.3). Adds a `BenchEngine` interface to `:core:inference-api` | No: internal Kotlin, not HTTP/AIDL. But it extends roadmap v2 P1's listed JNI surface |
| CB2 | Catalogue (owner's repository): additive top-level `benchSets[]` carrying `{id, tiers:[{tier, modelId, mirrors:[url], sha256}]}` and the catalogue `models[]` entries `qwen3-*`. The pin itself stays compiled in | No (roadmap §9 is additive) |
| CB3 | New artifacts: APK `xyz.mdhv.asom.bench`, the `AsomBench` iOS app, the `asom-bench` CLI; modules `:bench-core`, `:bench-cli`, `:bench-app` | No |
| CB4 | Provider availability reason `benchmarking` (`platforms.md` §2.1; live state is `router.md`'s) | No (mesh contract, proposed) |
| CB5 | Additive `manifest.md` members: `body.bench` or `body.benchSha256`; an optional per-result `confidence`; nullable `sustained`; the power methods; `powerSource` (§13.4) | Mesh contract, proposed |
| CB6 | Conformance: M04 derive, M05 body, and projection vectors under `conformance/manifest/`; the bench-set pin under `conformance/bench/` | No |
| CB7 | Roadmap v2 P6 wording (BD1) and the P7 egress class (BD4) | Roadmap text and Invariant 3 wording |

**No new HTTP endpoint, header or error code** is proposed by this section.
- A `local-only` request during a benchmark is served, because the benchmark yields, so no `BENCHMARK_IN_PROGRESS` error is needed.
- How local apps *subscribe* to the manifest is `manifest.md`'s surface.

---

## 17. Owner decisions

| # | Question | Options | Recommendation | Why only the owner can decide |
|---|---|---|---|---|
| BD1 | Amend roadmap v2 P6's "the benchmark app is a *feature*, not a separate app"? | (a) Amend it to "one benchmark subsystem, shipped in the daemon and as thin standalone shells over the same core". (b) Standalone on iOS only; Android and desktop use the daemon. (c) Keep the text; no standalone anywhere | **(a)**, sequenced: the daemon shell first, then the desktop CLI, then iOS (S5); Android standalone last. iOS needs a standalone in any case, because there is no daemon on iOS. Desktop GUI: CLI only until users ask for more | It is frozen roadmap text, and the owner's new requirement conflicts with it |
| BD2 | Build the pure-JVM core now (§21), ahead of v2? | (a) Wait for the v2 entry criteria. (b) Build only the engine-free core against a fake engine now. (c) Also pull v2 P1 JNI forward for desktop | **(b)**. No contract change, no Android code, no engine. Consistent with `platforms.md` OD4(b) | CLAUDE.md forbids starting later versions |
| BD3 | May a user **export** their report (text) or signed manifest (file) off the device through the share sheet? | (a) Treat it as covered by Amendment 1: benchmark data leaving by explicit, view-first user action. Needs owner confirmation of that reading. (b) A **third amendment**. (c) No export; the manifest goes only to paired peers (the v4 amendment) | **(a), confirmed explicitly in the amendment's text.** The sheet shows the exact bytes. It warns that a signed manifest contains a key that links all of this device's reports, and offers text-only export as the unlinkable default | Invariant 1 enumerates exports (ledger; v2 benchmark upload). Adding one without the owner is exactly what roadmap §13 forbids |
| BD4 | The P7 upload needs an Invariant 3 egress class (for example `contribution`). Amendment 1 amended only Invariant 1 | (a) Add explicit one-line Invariant 3 wording inside Amendment 1. (b) Treat it as implied | **(a)**, mirroring `platforms.md` OD7 for `lan`. An implicit class is how the audit's "stated class must be true" failures started | Invariant 3 says its classes are exhaustive |
| BD5 | Approve bench set 1 and the editorial constants | Approve Qwen3 dense (Apache-2.0; official Hugging Face repositories; pinned hashes and revisions) or name another set. Approve the thresholds (10 tok/s and 2 s comfortable; 4 tok/s and 10 s usable) and the role wording. Compute the reference NLLs (CPU, pinned commit) and choose the numerics text | **Approve as listed.** The owner downloads and re-verifies the sha256s and computes the reference NLLs (NEEDS-OWNER-VALIDATION) | Editorial voice; a third-party host dependency; reference values only the owner can publish |
| BD6 | Charger policy for heat tests on phones | (a) Required (default). (b) Preferred. (c) The user chooses per run | **(a).** It represents the provider condition, avoids draining the phone to empty, and keeps the report honest ("on charger") | Trade-off between user convenience and battery or thermal safety |

---

## 18. Invariant impacts

| Invariant | Status | Detail |
|---|---|---|
| 1 No automatic egress | honored; one export question surfaced | Active runs are local. There are no scheduled runs (B5). Downloads are permitted classes. P7 remains Amendment 1. Report or manifest export is **BD3**, a third-amendment candidate unless the owner confirms it falls under Amendment 1 |
| 2 Bind 127.0.0.1 only | honored | The benchmark adds no listener. The standalone apps have no server at all |
| 3 Egress classes exhaustive; every network event ledgered | needs-amendment (P7 only) | Downloads are `download` rows, and each standalone app keeps its own ledger. The P7 upload lacks a class: **BD4** |
| 4 BYOK keys | honored | The benchmark touches no keys. The standalone apps have no vault |
| 5 AIDL-verified pairing | honored | No pairing is added. The standalone apps pair with nothing |
| 6 Red/green never carries meaning | honored | Plain text has no colour; verdicts are words in capitals. The UI uses violet/cyan plus shape. The CLI emits no ANSI colour by default |
| 7 Placeholder UI | honored on Android; iOS SwiftUI and the desktop CLI fall under `platforms.md` OD3 | No visual design here |
| 8 No GMS | honored | Thermal, battery and ODPM are AOSP framework APIs. No Play services |
| 9 One `RouteRecord` | honored, and extended in spirit | Passive samples come from the same completion event as the `RouteRecord`. The text and manifest results come from one document (B2) |
| CLAUDE.md: no KMP | honored | A Swift port pinned by vectors |
| CLAUDE.md: do not start later versions | surfaced | BD2. Only the engine-free pure-JVM core is proposed now |
| Roadmap §11 stop-line | honored | No public leaderboard or registry of devices is proposed. The P7 receiver stays the owner's static sink. No incentives |

---

## 19. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| The benchmark overheats a device, or accelerates battery wear | high | Charger requirement; soft and hard ceilings on thermal state and battery temperature; time caps; a once-a-day heat-test prompt; an abort path tested for every transition. Residual risk disclosed (§11.8) |
| App Review rejects the iOS app under 2.4.2 | medium | User-initiated only, time-boxed, hard stop at `serious`, no background run in the first release. Precedents noted, not relied on [BV22, BV23] |
| Estimates are read as measurements (for example Q2 or Q3) | medium | T1/T2 labelling, conservative formulas, the fixed "does not tell you" block, M05 vectors for every wording |
| A manifest is gamed (fabricated samples, a chilled device, a modified harness) | medium | Not preventable. `manifest.md` attestation tiers; the derivation-integrity check (§13.3); the requester's observed-versus-claimed ratio (§10.7); honesty in the text |
| Swift and JVM harnesses drift | medium | C1, the vectors, the parity check (§2.6), the recorded `coreImpl` |
| A standalone shell becomes a fork | medium | §2.5 mechanisms; release checklist (§15) |
| Upstream model files move or change | medium | sha256 plus revision pin; catalogue mirrors; offline import; a set id never changes |
| Download size (1.8 GB quick; 4.3 GB standard phone; 16.5 GB desktop standard) deters users | medium | Quick plan; T3 opt-in; reuse of daemon models; deletion offered after standalone runs |
| OEM thermal or battery APIs misreport (NaN headroom, mA-for-µA) | medium | Fallbacks to status codes and temperatures; unit cross-check; `null` rather than guesses |
| No numerics reference yet | low | Verdict `not-run`, never `pass`; BD5 |
| Runs are too long and annoy users | low | Quick plan by default in the UI; standard needs an explicit choice; progress with a remaining-time estimate |

---

## 20. What this section does NOT guarantee (anti-overclaim)

- **Signatures** (`manifest.md`) prove that the document is unchanged since signing, and which key signed it. They do **not** prove:
  - that the benchmark ran honestly;
  - that the device is the model it names;
  - that the harness was unmodified;
  - that the device was not chilled, or otherwise unrepresentative, during the run.
- **The derivation-integrity check** (§13.3) proves only that the summaries follow from the signed samples. It says nothing about whether the samples were truthful.
- **Confidence classes** describe *repeatability under the recorded conditions*. HIGH does not mean "accurate for your use". Real prompts, other apps and ambient temperature differ.
- **"Measured"** means measured on this device, with these test models, at this engine commit, under the recorded conditions. **"Estimated"** means calculated by a stated formula whose assumptions (§12.4, BA05–BA07) may not hold for other models, especially mixture-of-experts models.
- **The numerics check** detects gross divergence on one text. It does not measure answer quality and does not rule out subtle bugs.
- **The thermal curve** is a single run. The throttle onset depends on ambient temperature, case, charger and starting charge level. It is a property of that run, not a specification of the device.
- **Energy figures** cover whatever the method covers (rails, whole device, CPU package). Figures from different methods are not comparable.
- **The governors** reduce risk; they do not certify safety (§11.8).
- **The public P7 derivative** is unsigned. Nobody, including the owner's receiver, can verify it.
- **The worked example is synthetic.** It demonstrates the pipeline, not any device's performance.

---

## 21. Buildable now (pure JVM; no contract change; no Android; subject to BD2)

1. **`:bench-core` statistics (M04):**
   - conservative medians, the outlier rule, confidence and caps;
   - sustained peak, onset and plateau;
   - integer EWMA and decay;
   - the derived answers.

   With seed vectors from `m04-seed-vectors.txt` promoted to `conformance/manifest/M04-derive.json` (status `proposed`).
2. **M05 body renderer** and its vectors (every template branch), using the §12 constants.
3. **Projection into `results[]`** (§13.3), with vectors. Status `proposed` until CB5 and R1–R8 are settled.
4. **Run-plan interpreter and governor FSM** against a deterministic `FakeBenchEngine`. The fake takes an injectable timing model (`a + b·bytes` per token) and a scripted thermal curve, and `FakeProbeSource` scripts ceilings, charger removal, memory pressure and yields. A test asserts that every exit path restores resources.
5. **`ConsentSheet` / `ConsentToken`**: the view-first hash binding, with tests.
6. **P7 projection builder** with the structural allow-list test, extended as in §13.5.
7. **Linux probe parsers** (thermal zones and trip points, `power_supply`, `/proc/meminfo`, `/proc/stat`) as pure-Kotlin parsers of fixture files.
8. **`benchset-qwen3-dense-1.json`** as a resource, with a golden test against §4.2. The hashes become normative only after the owner re-verifies them (BD5).

---

## 22. Verified facts and assumptions

### 22.1 Verified this session

| ID | Fact | Source | Conf. |
|---|---|---|---|
| BV01 | llama-bench: `-p`, `-n`, `-pg`, `-d/--n-depth`, `-r` (default 5), `--delay`, `-o csv\|json\|jsonl\|md\|sql`; warm-up by default (`--no-warmup` skips it); test names `pp 512`, `tg 128`, `pp512 @ d512`; JSON fields include `build_commit`, `avg_ts`, `stddev_ts`, `samples_ns` | github.com/ggml-org/llama.cpp `tools/llama-bench/README.md` | high |
| BV02 | `getThermalHeadroom(forecastSeconds 0..60)`: 1.0 = SEVERE; tracks "slow-moving sensors, such as the skin temperature sensor"; "no benefit to calling … more frequently than about once per second"; returns NaN if called faster (500 ms minimum in code); `getThermalHeadroomThresholds()`; a headroom listener on BAKLAVA; `THERMAL_STATUS_*` semantics; `isSustainedPerformanceModeSupported()` | AOSP `frameworks/base` `core/java/android/os/PowerManager.java` (main) | high |
| BV03 | `BATTERY_PROPERTY_CHARGE_COUNTER` µAh; `CURRENT_NOW` / `CURRENT_AVERAGE` µA, positive meaning into the battery; `ENERGY_COUNTER` nWh | AOSP `BatteryManager.java` | high |
| BV04 | Some Samsung devices report `CURRENT_NOW` in mA, varying by model and One UI version | github.com/home-assistant/android issue 2846; github.com/d4rken-org/amply PR 123 | medium |
| BV05 | `PowerMonitor`: ODPM rails or modeled consumers, names device-specific; readings in µW·s since boot, "total energy: both on-battery and plugged-in"; an empty list if ODPM is unsupported; API 35 | AOSP `PowerMonitor.java`, `PowerMonitorReadings.java`, `SystemHealthManager.java`; developer.android.com PowerMonitor reference | high (semantics) / medium (API level) |
| BV06 | `SystemHealthManager.getCpuHeadroom` / `getGpuHeadroom` exist (flag-gated), with minimum polling intervals | AOSP `SystemHealthManager.java` | high (existence) / low (API level) |
| BV07 | Android apps cannot read `/proc/stat` since 8.0 (EACCES) | github.com/codepath/android_guides issue 369; AnotherMonitor README | medium |
| BV08 | iOS `batteryLevel` moves in 5% steps; an Apple Frameworks Engineer called it "expected and intended behavior" (June 2025) | developer.apple.com/forums/thread/788911 | high |
| BV09 | `os_proc_available_memory()`: "the current memory limit minus the memory footprint"; advisory; do not maximise usage, because the system may terminate other apps; iOS 13+ | Apple docs JSON for `os/os_proc_available_memory` | high |
| BV10 | `MTLDevice.recommendedMaxWorkingSetSize`: "an approximation of how much memory, in bytes, this GPU device can allocate without affecting its runtime performance"; iOS 16+, macOS 10.12+ | Apple docs JSON | high |
| BV11 | `ProcessInfo.ThermalState` fair, serious and critical semantics (quoted in §6.1) | Apple docs JSON | high |
| BV12 | `BGContinuedProcessingTask` (iOS/iPadOS 26): submitted from the foreground "as a result of a person's action"; Live Activity progress; user-cancellable; the system may terminate it; progress reporting required | Apple docs JSON for `BGContinuedProcessingTask` and its request | high |
| BV13 | `powermetrics` must be run as root ("must be invoked as the superuser") | asitop README; powermetrics-go; darkmux issue 2 | medium-high |
| BV14 | Linux RAPL `energy_uj` has been root-only since 5.10 (PLATYPUS, CVE-2020-8694) | Intel advisory INTEL-SA-00389; Ubuntu CVE page; CodeCarbon docs | high |
| BV15 | `power_supply`: `current_now` in µA, negative when discharging; voltages in µV; temperatures in 1/10 °C | Linux `Documentation/ABI/testing/sysfs-class-power` | high |
| BV16 | `POSIX_FADV_DONTNEED` frees clean cached pages for a page-aligned range; no privilege is mentioned | man7.org `posix_fadvise(2)` | high |
| BV17 | `drop_caches` drops clean caches and is non-destructive (the root requirement is not stated there) | docs.kernel.org `admin-guide/sysctl/vm` | high (semantics) |
| BV18 | `VK_EXT_memory_budget`: `heapBudget` is "a rough estimate of how much memory the process can allocate from that heap before allocations may fail or cause performance degradation" | Khronos refpages | high |
| BV19 | Official `Qwen/Qwen3-*-GGUF` repositories are Apache-2.0. 0.6B and 1.7B have only Q8_0. 4B, 8B, 14B and 32B have Q4_K_M. File sizes, LFS sha256 and repository revisions are as in §4.2 | Hugging Face API `api/models/Qwen/<repo>` and `/tree/main`, queried 2026-09-29 | high (as reported by the API) |
| BV20 | Qwen3 geometry: 8 KV heads, head_dim 128; layers 28/28/36/36/40/64; parameter totals as in §4.2; `max_position_embeddings` 40960 | HF `config.json` and model API `safetensors.total` | high |
| BV21 | llama.cpp KL-divergence mode needs a reference logits file of "11 GiB for LLaMA 2 or 37 GiB for LLaMA 3" (wikitext-2) | llama.cpp `tools/perplexity/README.md` | high |
| BV22 | 3DMark Wild Life stress tests run 20 loops; stability = 100 × lowest / best loop; the app is on the iOS App Store | UL support articles; apps.apple.com/us/app/3dmark/id1512372293 | high (existence and definition) |
| BV23 | Geekbench AI is on the iOS App Store | apps.apple.com/us/app/geekbench-ai/id1563487010 | high (existence) |
| BV24 | F-Droid anti-feature definitions: NonFreeNet, NonFreeAssets, NonFreeDep, Tracking | f-droid.org/docs/Anti-Features | high (definitions) |
| BV25 | `setDevicePropertiesAttestationIncluded` puts brand, device, manufacturer, model and product into the attestation extension; it throws if unsupported | AOSP `KeyGenParameterSpec.java` | high |
| BV26 | `PowerManager.isSustainedPerformanceModeSupported()` / `Window#setSustainedPerformanceMode` exist | AOSP `PowerManager.java` | high |

**Relied on from `platforms.md`** (not re-verified here): V01 (iOS suspension), V04 (background GPU for continued tasks), V05 (App Review 2.4.2, 2.5.2, 5.1.2(i)), V06, V07, V13, V15, V19, V20, V21, V27; constraints C1, C7, C10, C11.

**Computed this session:** `bench_ref.py` outputs. The example document and text are deterministic across runs (identical canonical bytes, sha256 `560b9f87…22f1`), and the projection validates against the `manifest.md` draft schema except for R1.

### 22.2 Assumptions (not verified; load-bearing ones become spikes)

| ID | Assumption | How to settle |
|---|---|---|
| BA01 | Battery-temperature ceilings of 42 °C (soft) and 44–45 °C (hard) are safe, conservative defaults | Owner review against cell guidance; RedMagic observation |
| BA02 | The Linux thermal-code mapping via trip points, and the 70/80/90/95 °C fallback | Test on the Deck and the Dell |
| BA03 | `posix_fadvise(DONTNEED)` does not evict pages mapped by another process, so cold load is best-effort | Linux test |
| BA04 | Numerics tolerances of 20‰ (pass) and 50‰ (warn) separate healthy backend differences from broken ones | Calibrate across CPU, Vulkan, Metal and OpenCL at the pinned commit |
| BA05 | About 0.61 bytes per parameter at Q4_K_M holds for other dense models (used only for wording) | Check a few other GGUFs |
| BA06 | 300 MiB covers compute buffers and runtime overhead at `nCtx` 4096 | Engine measurements per backend |
| BA07 | Dense decode time per token ≈ a + b·bytes with a ≥ 0 on one backend, so proportional scaling is conservative | Fit on measured tiers |
| BA08 | macOS AppleSmartBattery values are readable without privileges | S3 spike |
| BA09 | Lowering own-window brightness during a run is permitted and restorable (Android, iOS) | Implementation test; App Review |
| BA10 | A Homebrew formula (not a cask) avoids the Gatekeeper cask policy for a JVM CLI | Owner check |
| BA11 | The Hugging Face LFS `oid` equals the SHA-256 of the file contents | Download and run `sha256sum` (BD5) |
| BA12 | About 4 KB of English prose yields 1024 tokens under the Qwen3 tokenizer; the exact count is fixed at pin time | Tokenize at pin time |
| BA13 | Virtualisation is detectable on CI runners (hypervisor flag, `systemd-detect-virt`) | CI test |
| BA14 | Android `onTrimMemory` and the iOS memory warning arrive before the low-memory killer or jetsam acts | Device test |
| BA15 | A foreground Activity without an FGS is not killed during a 35-minute run with the screen on | RedMagic test |
| BA16 | A Linux PSI `some avg10` of 20% or more is a reasonable memory-pressure abort signal | Deck test |
| BA17 | Harness parity tolerances: 30‰ for rates, 50‰ for TTFT | S5 parity check |
| BA18 | The greedy argmax per decoded token is negligible next to a decode step | Profile |
| BA19 | macOS "available" = free + inactive + purgeable approximates what a process can allocate | S3 spike |
| BA20 | Downloading Apache-2.0 GGUFs from Hugging Face triggers no F-Droid anti-feature | Ask F-Droid at submission |
| BA21 | Android and iOS apps have no public API for SoC temperature | Recheck at implementation |
| BA22 | The first iOS release qualifies for "Data Not Collected" | App Store Connect privacy questionnaire |
