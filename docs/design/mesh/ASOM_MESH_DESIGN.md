# ASOM mesh: integrated design brief (revision 3)

**Date:** 2026-09-30 · **Revision:** r3. Earlier revisions are preserved unchanged as `ASOM_MESH_DESIGN.r1.md` and `ASOM_MESH_DESIGN.r2.md`. · **Status:** DESIGN SYNTHESIS. **This brief is the roadmap §7 (v4) design session** (roadmap §14 item 5, acting decision AD-1); §0.6 lists what it settles and what it leaves to the owner. Nothing here is frozen or executable until the owner's standing ratification of AD-1…AD-6 holds and each open decision in §10 is ruled.
**What changed in r3:**
- the owner directives and acting decisions of `OWNER_DIRECTIVES_2026-09-30.md` are applied **as decided** (§0.2): AD-1 sequencing replaces the old D4 debate; AD-2 declares **Amendment 3 (mesh)** explicitly (§8.2); Windows and Ubuntu Touch are in scope (D-E); MLPerf Client v1.6 is folded into the positioning (D-C);
- every one of the **35 round-2 review findings** is dispositioned (§12.1; full log `REVIEW_ROUND2_DISPOSITION.md`);
- the **six platform sections** (`platforms/linux.md`, `windows.md`, `macos.md`, `ios.md`, `ubuntu-touch.md`, `android-mesh.md`) are integrated into §3, with a consolidated matrix;
- two implementable companions are new: **`LAB_SPEC.md`** (the pure-JVM lab that builders implement next) and **`PLATFORM_PLAN.md`** (per-platform directories, CI jobs, gates and build order).

**Inputs:** the six r0 section designs (`platforms.md`, `trust.md`, `manifest.md`, `benchmark.md`, `router.md`, `contract.md`), the r3 platform sections under `platforms/`, both review rounds, the owner directives file, and the repo's frozen documents (`ASOM_BUILD_BRIEF.md`, `ASOM_ROADMAP_BRIEF.md` incl. its §14, `CLAUDE.md`, `PROGRESS.md`).
**How to read this:** §0 is the short answer. §10.0 lists what is **decided**; §10.1 onward lists what is **open**. §8.3 lists every contract change with the decision that rules it. §1–§9 give the reasoning and the normative deltas. Where this brief says a sibling's mechanism is *retained*, that sibling is the detailed spec **as amended here**; where this brief and a sibling disagree, this brief wins; where a platform section and this brief disagree, this brief wins and §12.3 says which platform correction was taken.
**Tags:** `[Fnn]` = fact verified, source in Appendix A. `[Ann]` = assumption, not verified (Appendix B). Platform tags `[LFnn]`/`[FWnn]`/`[FMnn]`/`[IFnn]`/`[UFnn]`/`[AFnn]` point to the verified-facts tables of the six platform sections (each with a source URL and fetch date); `[LAnn]`/`[AWnn]`/`[AMnn]`/`[IAnn]`/`[UAnn]`/`[AAnn]` to their assumption tables. `[ISn]` = a spike that was **run** and whose result is recorded in the iOS section's spike table (IS1: the Swift DSSE/ES256 slice reproduced the JCA verdicts); `S-…` ids (S-M1, S-W1, S-UT1, S-A9, S-L3, S-B1 and the like) = a named spike **not yet run** (a result is never implied by the name). `SIGN-OFF` = an addition to a frozen surface that needs the owner. `DECIDED` = an owner directive or an acting decision the owner delegated (AD-*), treated as decided unless overruled. Every performance number is an ESTIMATE unless it carries an `[F]`-class tag.

---

## 0. Executive summary

### 0.1 What to build

**An own-devices holonic mesh** (directive D-B, DECIDED). The owner's devices run asom. Each one:
- **serves its own apps alone when it can** (with the mesh off it behaves exactly as a standalone asom: law RL1);
- **borrows** model inference from, and where its platform allows **lends** it to, the other devices the owner paired, under consents given per pair and per direction.

**Not every node is a full holon, and this brief now says so plainly** (R2-DIRECTIVES-4). "Full holon" means: serves its own apps alone, borrows and lends. The table below is the honest version; §2.1 carries it per phase.

| Node | Serves its own apps alone | Borrows | Lends | Holon status |
|---|---|---|---|---|
| Android phone (v1 daemon) | yes (cloud BYOK today; local engine from v2) | mesh-1 | after v3 (charging, or lend screen frontmost) | **full holon** (the only one today) |
| Linux desktop/server, Steam Deck, Windows PC, Mac | only the owner's CLI in mesh-1 (no local-app API until M2, D25); no cloud tier (no BYOK off Android before D14) | mesh-1 | while awake, per OS power policy | **partial until M2**: whole for the owner's CLI, lender for others |
| iPhone | no (no cross-app daemon on iOS; each app can hold only its own `CloudOnly` keys, D14 part B) | M4 | **never** | **partial: borrow-only requester** |
| iPad | no (as iPhone) | M4 | only while a lend screen is frontmost (after v3) | **partial** |
| Ubuntu Touch phone | no (no engine, no cloud tier in UT-1) | UT-1 | never unattended; UT-2 lend screen unscheduled | **partial: borrow-only requester** |
| Car head unit, Wi-Fi appliance (D26) | only if an engine is scheduled for it (none is) | if owner-paired | — | **partial: borrow-only requester profile, unscheduled** |

The holonic property is therefore a **design property of the protocol** (every node runs the same roles and speaks the same frames; nothing is privileged) and a **delivered property of the Android phone and, from M2, of desktops**. For the other nodes the brief claims only what the table says.

**The mesh is symmetric.** No coordinator, home node, leader or relay; "the whole" is only the set of pairwise pairings. Platforms differ in what they *can* do (an iPhone cannot lend in the background [F01]), not in rank.

**How one request flows.** The node that receives a request from one of its own callers plans it and sends the **whole request** to whichever node will serve it best right now: itself, a paired peer, or the cloud through the user's own keys. Around this:
- a **router extension**, the product's differentiator (§0.3), which composes with the frozen v1 router and adds live-state-aware placement across the owner's devices;
- a **benchmark that exists only to feed that router** and to produce a signed manifest and plain text (§6); it adopts MLPerf Mobile's and MLPerf Client's model sets and metric definitions where licences permit, and it is **not** a product of its own;
- a **signed capability manifest** that paired peers pull and that users can export as a one-shot file (§5).

**What it carries is model inference only**: whole requests to a model the lender holds. General-purpose compute (simulation, rendering, running another device's code) is not in this design (§2.2, D26).

### 0.2 Owner directives and acting decisions applied (all DECIDED unless the owner overrules)

| | Directive or acting decision | What changed in r3 |
|---|---|---|
| **D-A** | Apple platforms are in scope | Kept (D13). Only sequencing and shape stay open (D24, D15, D16) |
| **D-B** | Symmetric, holonic mesh; stop-line unchanged | Kept (D0), **limited to the directive's own words** (R2-CONFORMANCE-4). Design consequences (CLI borrowing, per-direction consent, inference-only) are now listed as recommendations tied to their rulings (§10.0b) |
| **D-C** | Router is the differentiator; the harness is not; MLPerf Mobile v6.0 **and MLPerf Client v1.6** already cover consumer benchmarking; never self-stamp "MLPerf-comparable" | §0.3 and §6 rewritten: the differentiation list is exactly three items; desktop and Apple coverage is **not** a differentiator [F49]; the label "MLPerf-comparable" is deleted everywhere and replaced by the wording of §6.1, which may be shipped only after the owner's trademark check [F50] (D18) |
| **D-D** | Roadmap order stands except where AD-1 revises it; v1 validation first | §0.4 and §9 follow AD-1 exactly; nothing ships before V1-close |
| **D-E** | Platforms in scope: Android, Linux, Steam Deck, **Windows**, macOS, iOS/iPadOS, **Ubuntu Touch** | §3 rewritten with a consolidated matrix and a subsection per platform; Windows and Ubuntu Touch are first-class rows |
| **D-F** | Delegation: finish what can be finished; different agents per platform | Six platform sections were produced and integrated; this revision, `LAB_SPEC.md` and `PLATFORM_PLAN.md` are the outcome |
| **AD-1** | Sequencing: after V1-close and v1.1 → **v2 → mesh-1 → v2.5 → v3 → remaining v4 items**; v2.5's per-app cloud-ban column pulled into mesh-1 | Replaces r2's D4 (recorded as decided, §10.0). The contradiction the round-2 reviewers found ("versions stay in order" beside a plan that reorders them) is removed: the plan **does** reorder the roadmap, by the owner's delegated decision, and says so (§0.4, §9, RT-8, RT-10) |
| **AD-2** | Declare **Amendment 3 (mesh)** explicitly; Amendment 2 stays exactly as the roadmap wrote it | §8.2 rewritten: Amendment 2's text verbatim; Amendment 3's exact proposed wording for Invariants 1 (stated as a **relaxation**), 3 (class `peer`) and 5 (node key identity). r2's D2 is decided; D6 and D14 are re-put under the same principle (proposed Amendments 4 and 5) |
| **AD-3** | Separate directories: `docs/design/mesh/`, `lab/`, `desktop/`, `apple/`, `ubuntu-touch/`; root build and `jvmTest` unchanged | `PLATFORM_PLAN.md` uses exactly these; `LAB_SPEC.md` §2 specifies the isolation mechanism, which needs no root change, and the one-line root guard permitted only as a recorded fallback |
| **AD-4** | The lab is authorised (r2's D1a and D1b) | Both decided (§10.0); the lab now includes the router simulator and ledger model |
| **AD-5** | Verification honesty: CI where it can compile or test; device items stay NEEDS-DEVICE-VALIDATION | Every platform row in §3 names what hosted runners can verify and what they cannot |
| **AD-6** | Opus designs and reviews; Sonnet implements | `LAB_SPEC.md` is written to be implemented without reading this brief |

### 0.3 Positioning (verified; directive D-C)

- **Server-side routing is crowded and consolidating** [F44][F45][F46]. asom does not compete as a gateway.
- **The differentiator is the router in a place none of those occupy:** device-resident, shared by all of one user's apps, BYOK keys the user holds, an auditable egress ledger, no operator backend, and placement across a mesh of the user's own devices. **No project found in the owner's survey or in this session's searches does this** [F48]. That is absence of evidence, not proof.
- **The benchmark harness is not a differentiator, on any platform.**
  - MLPerf Mobile v6.0 (2026-06-15) ships a consumer LLM benchmark on Play, the App Store and GitHub (Llama 3.2 1B/3B, Llama 3.1 8B). The source confirms NPU-accelerated Llama 3.1 8B on Snapdragon 8 Elite Gen 5; for Dimensity 9500 and Exynos 2600 it states support, not an LLM NPU path [F40] (R2-OVERCLAIM-11).
  - **MLPerf Client v1.6** (2026-04-06) covers Windows, macOS, iPad and iOS with Windows ML, llama.cpp, and llama.cpp or MLX on Metal, with GUI builds on the iOS and Mac App Stores and Steam [F49]; its current benchmark page adds a CLI-only Ubuntu 24.04 build [LF37]. **So desktop and Apple coverage is not a differentiator** (R2-DIRECTIVES-2).
- **asom's benchmark differentiates on exactly three things** (D-C):
  1. a **signed, verifiable manifest** that other devices and apps consume (§5);
  2. **plain-text reports** rendered from the same verified data (§5.10, §6.8);
  3. **results that feed the router** (§5.7, §6.7).
- **Comparability, honestly.** A default run uses the Apache-2.0 Qwen3 set; only users who opt into the Llama set produce rows on MLPerf Mobile's models, and only after spike S-B1 pins MLPerf's metric formulas. MLCommons' results-messaging rules say MLPerf results "may not be compared against non-MLPerf results" and unverified uses must say "Result not verified by MLCommons Association" [F50]. asom's numbers are not MLPerf results. **asom never writes "MLPerf-comparable"**, never renders or ingests an MLPerf score, and ships the MLPerf name in any text only after the owner's trademark check (D18, K33).
- **Independent validation, with its limit.** AT&T's difficulty-based routing [F44] suggests the roadmap's v2.5 semantic router targets a real saving; it is not evidence that a device-resident router reaches AT&T's numbers.

### 0.4 In what order (AD-1, DECIDED; open placements marked)

**Now (ships nothing; authorised by AD-3/AD-4 and roadmap §14 item 7):**
1. `lab/`: the pure-JVM lab of `LAB_SPEC.md` (conformance vectors pinning frozen v1, manifest signer/verifier and report renderer, benchmark maths, ledger model, peer protocol with the hostile-node suite, mesh router core and simulator).
2. `apple/`: the Swift conformance lane over the same vectors (Linux container with swift-crypto, and macOS runners with CryptoKit).
3. `desktop/`: the desktop node skeleton and per-OS packaging scaffolds with `NoopEngine` and **no listener** (Linux DL0–DL3, Windows W0–W2, macOS MC1–MC2; `macos.md`'s MC0 is the Swift lane of item 2), CI-only artefacts.
4. `ubuntu-touch/`: the UT-0 click scaffold (self-test only).
5. **Android app code is untouched** until v1 device validation closes.

**Then, strictly in this order (AD-1; directive D-D binds the first step):**

| Step | Contents | Roadmap position |
|---|---|---|
| V1-close | the open v1 device checklists | v1 |
| v1.1 | roadmap v1.1 + H1–H7 (§9.2), incl. the new error `LEDGER_UNAVAILABLE` **if D5 is ruled that way** | v1.1 |
| v2 | the engine; the Android daemon's benchmark shell; P7 (if D6 rules it) | v2 |
| **D-v2** | the desktop engine port (asom-desktop): Linux and Deck first; Windows and macOS lending targets per D28 | **v4 content moved ahead of v2.5/v3 by AD-1** |
| **M1 mesh-1** | the v4 core: node identity, pairing, transport, signed manifest, mesh router; Android and desktop CLIs borrow; desktops lend; v2.5's per-app cloud-ban column | **v4 core, by AD-1** |
| **M1b** (open: D24, D28) | platform requesters on the same protocol: iPhone/iPad requester (M4), Ubuntu Touch requester (UT-1), any desktop OS not already in M1 | **a placement inside mesh-1's window that r3 recommends but the owner must rule** (RT-13) |
| v2.5 | semantic routing (minus the pulled-forward column) | v2.5 |
| v3 | loops, egress firewall, web callers | v3 |
| M2+ | remaining v4 items: Android lends; desktop local-app API (D25); iPad lends (M5); overlay polish; UT-2 and D26 only if scheduled | **remaining v4, by AD-1** |

**What moved relative to the frozen roadmap, stated once:** AD-1 puts v4 content (D-v2, M1, optionally M1b) **before** v2.5 and v3, pulls one v2.5 column forward, and replaces two v4 entry criteria ("v3 shipped" → "v2 shipped"; "tailnet exists" → "at least one Linux/Deck node commissioned"; roadmap §14 item 5). The owner delegated that decision; it is recorded as decided and remains open to overrule. Nothing else from v2.5 or v3 moves.

### 0.5 Recommended, pending owner ruling

| Recommendation | Rules it |
|---|---|
| `X-Asom-Egress` restated to mean the request's **furthest content reach**; the terminal ledger row stores the same value (changes the meaning of frozen §5.4) | D3 |
| Append-only ledger, per-message control rows, fail-closed writes, and the new error `LEDGER_UNAVAILABLE` from v1.1 (changes v1 cloud behaviour) | D5 |
| Benchmark contribution class and report export recorded as **Amendment 4**, not folded into Amendment 1 | D6 |
| Platform-equivalence invariant text (UI toolkits, key wrapping, local identity per OS) recorded as **Amendment 5**, approved just in time | D14 |
| The owner's own CLI (desktops) and the app's own UI (Ubuntu Touch) are **not apps** under Invariant 5; stated in Amendment 3's text | D25 |
| One benchmark subsystem, inside every node; no standalone benchmark products (desktop CLI dropped; Android standalone APK unscheduled) | D7 |
| The five new app-facing values, each signed off separately | D12.1–D12.5 |
| Mesh-1 lenders: the Dell (Linux system service, polkit keep-awake rule), the Deck (Game Mode lending only as an invisible-lending opt-in; never on battery), Windows/Mac only if that hardware is the owner's desktop | D28 |
| targetSdk stays 35 until separately ruled | D29 |

### 0.6 What this design session settles, and what it does not

**This brief is the v4 design session** (roadmap §7 "design session required before execution"; roadmap §14 item 5; R2-CONFORMANCE-3). **It settles**, subject to the standing ratification of AD-1…AD-6:
- the platform scope and, per platform, which roles are feasible and which are refused (§3);
- the trust model: pinned per-node keys, QR pairing with consent on both devices, TLS 1.3 mutual authentication, the `asom-mesh/1` frames (§4);
- the invariant accounting: Amendment 2 unchanged; Amendment 3 (mesh) with exact text (§8.2);
- the egress class `peer` and what it covers (§8.2, §8.4);
- the signed manifest's format, verification and honest limits (§5);
- the router extension's composition with the frozen v1 router, its estimator, its claim tracker and its laws (§7);
- the live-state schema and the presence laws (§7.4);
- the sequencing (§0.4, §9) and what is parked (§9.3).

**It does not settle** the 22 open decisions of §10 (the owner brief lists them), device behaviour (every NEEDS-DEVICE-VALIDATION item), or any performance claim.

**Entry criteria it makes explicit** (they appear in every v4 phase row of §9.3): "v4 design session held and recorded" = this r3 brief committed under `docs/design/mesh/` with AD-1…AD-6 recorded in roadmap §14 (already the case for the directives; the brief is committed with the lab PR).

### 0.7 The stop-line, stated honestly

Unchanged from r2 (roadmap §11; directive D-B's boundary):

| | Examples | Position |
|---|---|---|
| **Inside** | Devices the owner controls, paired by hand (QR, confirmed on both screens) and can revoke; reached only over the owner's overlay or a LAN the owner confirmed; each borrow a direct, pairwise request | In scope. "Inside" does not mean scheduled (cars and appliances: D26) |
| **Outside, permanently in this roadmap** | Anything that pairs itself or is discovered; serving parties the owner has not paired (guests, passengers, household members' own devices); relaying; public listeners; incentives or settlement | Excluded by owner directive |
| **Not a target** | An appliance without a radio; closed firmware; a car that only projects the phone (the phone is the node) | Nothing to build |

### 0.8 Honest bottom line

- **Feasible, large, and most of it cannot ship yet.** Mesh and platform work is roughly **92–150 engineer-weeks** across all seven platforms (r2's 72–122 covered no Windows, no Ubuntu Touch and a 4–6-week macOS), estimates only (§9.4); Windows alone is 10–16, macOS 11–17, Ubuntu Touch 7–11.5. The first user-visible mesh value arrives after V1-close, v1.1, v2 and the desktop engine port.
- **The speed-up depends on the lender and is estimated, not measured** (§7.1). For an 8B model, 500-token prompt, 300-token answer:
  - phone → Apple-M4-Pro-class or GPU desktop: **~8–10× total time**;
  - phone → **Steam Deck: ~1.5–2.8× total time; time to first token anywhere from 0.8× (slower than the phone) to 4×**, because two defensible bases for the Deck's prefill rate disagree by 3–5× and neither is a measurement of the owner's Deck (§7.1 shows both calculations);
  - phone → CPU-only desktop: **~0.9–2×**, possibly no faster.
  Battery and heat relief, and models the phone cannot hold, apply at any ratio. The D-v2 owner-device benchmark replaces every figure; until then no UI or doc quotes a speed-up.
- **"Distributing work" means placing whole requests**, not devices cooperating on one answer [F27].
- **The router is the product; the benchmark exists to feed it.**
- **What a signature proves.** Integrity since signing and which key signed. For an exported file, **nothing about who made it** unless the recipient compares the fingerprint out of band; then exactly what comparing the file's hash would. Never that the benchmark was honest.
- **What the mesh relaxes.** Amendment 3 **relaxes Invariant 1**: banded device status and approved manifests travel automatically to paired devices. Prompt and response content never does. A paired peer can still infer a coarse timeline of when a lending device is in use (§7.12).
- **The frozen contract grows, and §8.3 counts all of it** (the owner brief repeats that count verbatim). If every recommendation is taken, the roadmap records **five amendments**: 1 and 2 as written, 3 (mesh, AD-2), 4 (benchmark sharing, D6) and 5 (platform equivalence, D14).

---

## 1. Scope reconciliation with `ASOM_ROADMAP_BRIEF.md`

### 1.1 The three requests mapped to the roadmap

| Requirement (owner intent) | Roadmap today | Verdict | Where it lands in this plan |
|---|---|---|---|
| R1a asom on Linux / Deck | v4 asom-desktop, "CLI shell first" | PLANNED (v4); moved ahead of v2.5/v3 by AD-1 | D-v2 + mesh-1 (§9); `platforms/linux.md` |
| R1a Windows | not mentioned | **IN SCOPE: directive D-E, DECIDED** | The same JVM node as Linux plus a Windows adapter and packaging (§3.4.3); lends in mesh-1 only if the Dell runs Windows (D28) |
| R1a macOS | not mentioned | **IN SCOPE: directive D-A, DECIDED (D13)** | The same JVM node plus a Swift helper and a hardened launcher (§3.4.4); lends in mesh-1 only if the owner has an Apple-silicon desktop (D28) |
| R1a iOS / iPadOS | not mentioned; brief §0 "a daemon for Android"; §10A tiers assume a localhost daemon | **IN SCOPE: directive D-A, DECIDED (D13).** It still CONTRADICTS the product shape (no background server on iOS [F01]) and the Android-worded Invariants 4, 5, 7 and 8 | One app (`xyz.mdhv.asom`) hosting the benchmark, pairing and verifier; the requester library in the owner's other apps (M4, placement D24); iPad lending after v3 (M5, D16). Invariant text: D14 (proposed Amendment 5) |
| R1a Ubuntu Touch | not mentioned | **IN SCOPE: directive D-E, DECIDED** | A foreground-only requester and manifest verifier (UT-1, placement D24); no unattended lending, no cross-app service (§3.4.6) |
| R1b devices offer compute | v4 "serve each other over a private overlay only" | PLANNED; **symmetric and holonic by directive D-B (D0)**, with the per-platform limits of §2.1 | Every node runs both roles in code; which ones a platform can deliver is §2.1's holon table. mesh-1: desktops lend, the Android phone and desktop CLIs borrow; phone lending and iPad lending after v3 (AD-1). "Compute" means **model inference** only (§2.2) |
| R1c a way to connect them | v4 QR pairing, static registry, "no mDNS", "per-device tokens" | PLANNED; refined (pinned keys instead of bearer tokens) | §4. Refinement changes sanctioned amendment text → D2 |
| R1d requester understands capabilities, collected by benchmarking | v4 "capability exchange (models present, thermal/RAM headroom)" | PLANNED (coarse); NEW (signed, benchmark-derived) | §5 manifest + §7 live state; touches Invariant 1 → D2 |
| R1e intelligently distributes work | v4 "whole-model placement only"; §1 "sharding adds capacity, not speed" | PLANNED for whole-request placement; CONTRADICTS if it means splitting one request or model | §7.1 tiered answer; D10 |
| R2a–c router knows capabilities and **current situation**, routes intelligently | v1 §7 router; v2 P4 governors; v4 node-aware routing | PLANNED locally; NEW across devices | §7 |
| R3a benchmarking utility | v2 P6 passive benchmarking | PLANNED (passive); NEW (active runs), **scoped by directive D-C** to asom's own serving path, with MLPerf Mobile's (phones) and MLPerf Client's (desktops) model sets and metric definitions as the comparability baseline where licences permit | §6; D18 |
| R3b can act as an independent app | v2 P6 "the benchmark app is a *feature*, not a separate app" | CONTRADICTS the wording; **partly met in r3**: the iOS asom app works without any daemon; elsewhere the benchmark lives inside the node, because MLPerf Mobile and MLPerf Client already serve the standalone-benchmark use case [F40][F49] | §6; D7 |
| R3c plain-text explanation | v2 P6 diagnostics panel | EXTENDS | §6.8 |
| R3d manifest JSON for counterparts and any subscriber | v2 P7 anonymous upload; v4 capability exchange | PLANNED (partly); NEW (file format). **Met between paired asom nodes (mesh pull). One-shot file export met only for a recipient who compares the fingerprint out of band (§5.4). Third-party *subscription* (ongoing reports, linkable to one sender) is NOT MET in mesh-1**; it is option D6(e) | §5.8; touches Invariant 1 → D6 |
| R3e tamper-evidence | not mentioned | NEW; for files, **only with a fingerprint comparison** | §5; honest limits in §5.11 |

### 1.2 Contradictions with frozen text (all surfaced, none assumed away)

| # | Frozen text | The requirement or design that conflicts | Resolution |
|---|---|---|---|
| C-1 | Roadmap v2 P6: "the benchmark app is a *feature*, not a separate app" and "This is not a new subsystem" | R3b; the active lane | One core, thin shells; the active lane is named as a subsystem. Amend both sentences (D7) |
| C-2 | Brief §0 Android daemon; Invariants 4, 5, 7 and 8 written in Android terms | iOS, macOS, Windows, Ubuntu Touch, desktop key storage, desktop local apps and UI | Directives D-A and D-E settle *whether*. The invariant text still has to change before any non-Compose UI (SwiftUI, QML, a desktop tray) or key-holding non-Android tier ships: IC-7/IC-8, ruled in D14 (recommended: Amendment 5). **Correction (R2-CONFORMANCE-6):** r2 said "mesh-1 needs none of it". mesh-1 does need one clause: the desktop owner CLI is an inference caller outside AIDL identity, so Invariant 5 must say it is not an app. That clause is IC-4(b), inside Amendment 3, ruled in D25 |
| C-3 | Roadmap §7 "whole-model placement only"; §1 "capacity, not speed" | "Distribute work" read as splitting | Whole-request placement only in mesh-1 (D10) |
| C-4 | Roadmap §7 "no mDNS" | Convenience of locating peers | Kept. mDNS is deferred (D12) |
| C-5 | Roadmap §13 "exactly two amendments" and §7 "No other invariant changes"; brief Invariant 1 "v2 introduces exactly one more such action" | The mesh needs Invariant 1, 3 and 5 text changes beyond Invariant 2. The v2 benchmark work adds an Invariant 3 class (IC-5) and a second new user export action (IC-6). Apple, Windows and Ubuntu Touch need IC-7/IC-8 | **Resolved by AD-2 for the mesh:** Amendment 3 is declared explicitly (§8.2) and roadmap §13's count becomes three. The same principle is applied to the rest: IC-5/IC-6 are proposed as Amendment 4 (D6) and IC-7/IC-8 as Amendment 5 (D14). No change is folded into an existing amendment |
| C-6 | CLAUDE.md "no KMP" | iOS needs non-JVM code | Kept: Swift port held by vectors; re-escalation triggers (D17) |
| C-7 | Roadmap §11 stop-line: owner-controlled devices only, no relaying for unpaired parties | Cross-owner sharing; delegated routing | Both excluded (D15, D20) |
| C-8 | CLAUDE.md "do NOT start those versions"; roadmap §0 "do not start a version until [entry criteria] are met … strictly sequential"; v2 entry criteria; v4 "design session required before execution … Do not cold-execute" | Building ahead | **Resolved by the owner's delegation:** AD-4 authorises the lab (r2's D1a and D1b) and roadmap §14 item 7 the `desktop/`, `apple/` and `ubuntu-touch/` scaffolds, all shipping nothing. AD-1 re-sequences mesh-1 ahead of v2.5 and v3. This brief is the v4 design session (§0.6). Directive D-D's "v1 validation first" still binds every shipped phase |
| C-9 | Roadmap §7 amendment draft "per-device tokens required" | Pinned keys are safer than bearer tokens | Changes sanctioned text → itemised in D2 |
| C-10 | Brief §5.9 "the router parses only `model` and `stream`" | Router reads `max_tokens`; peer-body allow-list | D11 |
| C-11 | Roadmap: silent on Apple; brief §0 "a sovereign model-routing daemon for Android" | Owner directive A: Apple platforms in scope | Directive A supersedes the silence for *scope* (D13, DECIDED). Brief §0 and the roadmap's platform list need a text update (RT-7, RT-9 in §8.3); the invariant consequences stay with D14 |
| C-12 | Roadmap v1.1 contract delta: "error `QUOTA_EXHAUSTED` only" | H1 fail-closed intent rows add `LEDGER_UNAVAILABLE` in v1.1 and change v1 cloud behaviour | Flagged, not hidden: CD-LU + RT-3, ruled in D5 |
| C-13 | `benchmark.md` §4.1 rejected the Llama family ("custom licences with conditions") | Directive C: MLPerf Mobile's model set (Llama 3.2 1B/3B, 3.1 8B) is the comparability baseline where licensing permits | The licence permits use with conditions [F41]. Llama becomes an opt-in comparability set; the Apache-2.0 Qwen3 set stays the default (D18, §6.2) |
| C-14 | Brief §3 pins `compileSdk 35 / targetSdk 35` | The mesh contemplates Android's local-network permission at targetSdk 37 (T14) | targetSdk stays 35 through mesh-1 and the lender phase unless the owner rules a bump (RT-11, D29) (R2-CONFORMANCE-10) |
| C-15 | Invariant 7: "placeholder-functional Compose/Material3" | Desktop trays (AWT on Windows and macOS), SwiftUI, QML on Ubuntu Touch | No non-Compose UI ships before D14 part A. Desktops ship CLI-only in D-v2/M1 (roadmap v4 sanctions "CLI shell first"); trays, SwiftUI and QML wait for IC-7 |
| C-16 | Roadmap v2 P6: the editorial benchmark table is a "read-only" seed | r2 signs it with an owner offline key compiled into the benchmark core and uses it to cap peer claims | Flagged as a text change (RT-12, D18) with a new owner task: custody of the reference key (R2-CONFORMANCE-11) |

### 1.3 Inconsistencies inside the frozen documents (independent of the mesh)

| # | Inconsistency | Consequence | Resolution |
|---|---|---|---|
| I-1 | Roadmap §7 says "No other invariant changes" but its own registry adds egress class `lan`; Invariant 3 is "exhaustive"; `RouteRecord.kt`'s `Egress` enum is commented "Exhaustive — no additions (invariant)" [F36] | Amendment 2 cannot be implemented without more invariant text | Resolved by AD-2: Amendment 3 adds class `peer` (§8.2) |
| I-2 | v2 P7 POSTs to `BENCHMARK_SINK_URL`, but Amendment 1 amends Invariant 1 only; Invariant 3 has no class for it | The upload would be a network event with no true class | Class `contribution` (D6) |
| I-3 | `ContractFreezeTest` pins neither `Egress` nor the `AsomHeaders` names [F36] | Adding a class trips no test | Add the pins as v1.1 hygiene (§9.2 H2) |
| I-4 | "**Every** network event writes a ledger row", but v1 appends a cloud row only after the upstream call returns [F36] | A process death mid-call loses the row for a transmission that happened | Intent rows for cloud attempts, v1.1 H1 (D5) |
| I-5 | `docs/CLIENT_API.md` documents `egress` as `local \| cloud`; the SDK type is `String?` | Apps may treat "not cloud" as local | Document an open set: "anything other than `local` left this device". `CLIENT_API.md` is pinned as contract (brief P6), so this is a contract change: CD-DOC1, ruled in D3 |

### 1.4 Ordering consequences (AD-1, DECIDED)

**The dependency chain for the first mesh release (mesh-1):** V1-close → v1.1 → the v2 engine and governors → the desktop engine port (D-v2) → mesh-1 (M1). **v2.5 and v3 are not technical dependencies** of an own-devices mesh; AD-1 therefore places them after mesh-1. That is a change to roadmap §2's ladder and to v4's entry criteria, taken by the owner's delegation and recorded in roadmap §14 item 5 (RT-8, RT-10).

**Protections that mesh-1 now arrives without, and their replacements (r2's D4 table, now a decided consequence):**

| Deferred protection | Replacement in mesh-1 |
|---|---|
| (a) v2.5 per-app default policy | A per-app "may use my other devices" toggle, **default off for every app**; apps paired under the v1 consent sheet re-consent one by one |
| (b) v2.5 per-app **cloud ban** | **Pulled forward** by AD-1: one boolean per app, enforced daemon-side, in mesh-1. X13's rejection of an `own-devices` policy therefore stands |
| (c) v2.5 `X-Asom-Route-Reason` header | Route reasons stay ledger-only until v2.5 ships the header |
| (d) v3 PII redaction firewall | Needed only for other-owner peers, which are outside the stop-line |
| (e) v1.1 hardening | Not lost: v1.1 still precedes everything |

**What mesh-1 pulls forward from later versions:** roadmap v4 itself (design session held by this brief; Amendments 2 and 3); the desktop build of v2 phases P0–P4 and the benchmark core (D-v2); the v2.5 cloud-ban column. **Nothing else** from v2.5 or v3.

**What remains open about order** (each needs its own ruling, and each is a further departure from roadmap §0 recorded in RT-13):
- **M1b** (D24, D28): whether the iOS requester, the Ubuntu Touch requester and any desktop OS not in M1 land inside mesh-1's window (before v2.5) or after v3. r3 recommends inside, because they are the same protocol's requester role on in-scope platforms and carry router value (R2-DIRECTIVES-6).
- **No parallel tracks are recommended.** r2's D24(b) ran an iOS benchmark app in parallel with D-v2; r3 withdraws that recommendation (R2-CONFORMANCE-8): the Swift work that can start early is the lab lane, which ships nothing.

### 1.5 Cross-section contradictions found, and how they were resolved

| # | Contradiction | Resolution (and where) |
|---|---|---|
| X1 | `platforms.md` delegates iOS routing to a "home node" that runs the full router and forwards; `trust.md` R6 says peers never forward; `contract.md` and `router.md` reject relaying | **No delegation, no relay, anywhere.** iOS becomes a *direct* requester to one user-chosen home provider (M4). H is a provider only. Advisory placement frames are deferred (§7.8, D15). r2: the iPhone pairs **as a device** (one node key in a keychain access group shared by the owner's own apps), not per host app, so IC-4 holds (§7.8) |
| X2 | Egress class `lan` (roadmap, trust, manifest, platforms) vs `peer` (contract) | `peer` + `peerPath: lan\|overlay` taken from the socket's interface; overlay text says "may pass through the overlay's relays" (§8.4, D3) |
| X3 | Mutable ledger rows updated by `attemptId` (trust) vs append-only intent/outcome (contract) | Append-only (§8.4) |
| X4 | Echo headers from the served attempt, assuming a monotone reach order (contract) vs router plans that put peers before self, or cloud before peers | The header states the **request's furthest content reach** (`reach`). r2: the request's **terminal row** stores the same value in its `egress` column, and the serving attempt's class moves to a new `servedClass` column, so the header and the dashboard's Egress column always agree (Invariant 9's purpose, not just its letter). This changes the meaning of frozen §5.4 and needs sign-off (CD-1m, D3); laws L-L5 and L-L5b (§8.4) |
| X5 | Rich live state with `user.active`, `localActive`, exact battery and watches (router) vs banded fields and presence only as a decline (contract) | Banded state, no *field* that encodes presence, no watches in mesh-1, quiescence law (§7.4). r2 correction: presence is **still inferable** from declines, availability transitions and queue fields; r2 removes four more fields, adds a 10-minute hold-down, and states the residual channel in IC-3(ii) and §7.12 instead of claiming there is none |
| X6 | Two claim-versus-observed trackers (`manifest.md` §11.5; `benchmark.md` §10.7) | One tracker, `manifest.md`'s, hardened (§5.7). `benchmark.md` §10.7 becomes a display string only |
| X7 | Two measurement schemas (`manifest.md` `body.results` vs `benchmark.md` `asom.bench/1`) | Payload carries `body.bench` (active-lane `asom.bench/1`) **and** `results[]`. Verifiers re-derive `results[]` from `bench` (§5.2) |
| X8 | Producer-stored `derived` and `render.textSha256` (benchmark) vs "derived quantities are not stored" (manifest) | Removed from the signed payload. The viewer re-derives and renders (§5.10, §6.8) |
| X9 | NIK generated at the first signed report (manifest) vs only at mesh enable (trust) | NIK only at mesh enable. Exports use per-export keys, so v2 needs no device key (§5.3) |
| X10 | `D0 device-only` that still permits cloud (trust) vs destination sets (contract) | Destination sets (§8.5). `device-only` means {this device} |
| X11 | `retain: owner-verbose` and `dataClass` in the offer (trust) vs dropped (contract) | Dropped |
| X12 | `X-Asom-Node` header (roadmap, trust, router) vs withdrawn (contract) | Withdrawn for mesh-1. `X-Asom-Served-By` carries a **per-app alias**, not the node tag (§8.3) |
| X13 | `own-devices` policy + `NO_ELIGIBLE_NODE` (trust, router) vs rejected (contract) | Rejected (D12), **conditionally**: the cloud ban plus the per-app mesh toggle express "my devices, never cloud" only if the cloud-ban column exists. Under D4(b) it does not unless pulled forward (D4(b1)). If the owner picks D4(b2), this row is reopened: an app then has no way to say "never cloud" before v2.5 |
| X14 | KV cache discarded per attempt (contract) vs partitioned per requester pin (trust, router fairness) | Discard per attempt in mesh-1 by default; per-app partition is an owner option once measured (D19) |
| X15 | `benchmark.md` "usable" = TTFT ≤ 10 s vs router usability gate TTFT ≤ 20 s | The router's gate is renamed the *peer usability gate* with its own constants. "usable" belongs to the report text only |
| X16 | Thermal headroom polled at 1 s (benchmark, router; AOSP code says NaN under 500 ms) vs Android ADPF guidance "no more than once every 10 s" | One process-wide `ThermalSampler`: headroom at most every 10 s, status listener for changes (§6.5 B28) |
| X17 | `platforms.md` C1 "integers only for router vectors" vs R04/W01 pinning v1's double-based code | C1 scoped to signed and live-state JSON. W01/R04 carry v1 doubles as shortest round-trip decimal strings (§3.4) |
| X18 | C11 one llama.cpp commit across all backends vs rows already partitioned by backend | The comparability key is `(backend, commit, buildFlags)`; the lockstep rule applies per backend (§3.3) |
| X19 | Platforms module plan (`:core:mesh`, `:bench-core`, `:conformance`, `:node-desktop`, routing depends on mesh) vs brief §4 dependency law and the `jvmTest` definition | Now: a **separate Gradle build** under `lab/` that maps the pure-JVM projects by directory and never evaluates the root settings (§3.5; the r1 `includeBuild("..")` pulled in Android modules whenever an SDK was present). Promotion later amends the §4 table (SIGN-OFF, D23) |
| X20 | Router F12 "user active" excludes every foreground-only provider (Android screen on; iOS always) | User activity is decided provider-side only. A frontmost "lend compute" screen counts as consent, not activity (§7.3) |
| X21 | Router `fastest` re-sorts cloud by its own estimate vs frozen v1 `fastest` order | Cloud keeps v1 order. Sovereign entries are inserted stably (§7.5) |
| X22 | Router usability gate applied to SELF (a v2 semantics change with the mesh off) | Gate applies to PEER candidates only (§7.5, D9) |
| X23 | Contract relays a peer's error body verbatim vs "mesh codes never reach apps" | The requester authors the envelope from a fixed mapping. Peer text never reaches apps, headers or rows (§4.2 T15) |
| X24 | Per-app mesh default `own` (contract, trust) vs v1 consent scope | Default **off**. Explicit per-app opt-in (§8.5) |
| X25 | Trust registry allows pairing over a SUSPENDED row; the registry FSM has no such transition | SUSPENDED is refused like REVOKED in pairing (§4.2 T6) |
| X26 | `trust.md` "failed dials folded into parent rows" vs "every attempt reaching a wire has a row" | Every outbound connect has its own `dial` row (§8.4) |
| X27 | Router `D0`/`dataClass` gate vs contract destination set | Router consumes the destination set `P` (§7.3) |
| X28 | Benchmark consent sheet "Nothing is uploaded" vs automatic manifest serving | New results are not published to any peer until the user approves the new body digest (§5.8) |
| X29 | `platforms.md` H role in capability matrix and S6 vs `router.md` OD-R2(a) | H role deleted. "Home provider" is an ordinary PA provider the iOS app pairs with |
| X30 | `benchmark.md` Q5 "can serve to your other devices" / iPad lending vs mesh not shipped and OD5 open | Q5 gated on a `meshAvailable` flag. iPad clause removed until D16 (§6.8) |
| X31 | `benchmark.md` B24 ships the desktop `asom-bench` CLI in v2 vs D4(b) "pulls forward only v4 and D-v2" | r2: v2 ships the **Android daemon shell only**. Both desktop CLI shells move to D-v2; macOS packaging goes with the macOS variant (D24). **r3:** the standalone desktop CLI is dropped (D7); `asom bench` lives inside each desktop node; D4 is decided as AD-1 |
| X32 | `ClaimTracker` discard rules used the peer-reported queue bucket and the hot rule used the peer-reported thermal band vs "only the requester's observation bounds a lying peer" | r2: discards and `hot` depend **only on requester-observed values**; a discard budget makes claims stop mattering when most observations are discarded (§5.7) |
| X33 | `benchmark.md` builds a measurement harness of its own vs MLPerf Mobile v6.0 already shipping one to consumers [F40] | r2: MLPerf's models and metric definitions are the comparability baseline; asom measures only its own serving engine, and only for the four differentiators (§6.1). **r3:** exactly three differentiators (signed manifest, plain text, results feeding the router); desktop and Apple coverage is not one, because MLPerf Client covers it [F49] (§0.3, §6.0) |
| X34 | Roles R/PA/PF with "desktop only as a test harness" requester vs directive B (every node lends and borrows) | r2: one symmetric role model; per-platform limits are capabilities, not a hierarchy. Desktop nodes borrow for the owner's CLI in mesh-1 and for local apps once D25/D14 part B allow (§2.1) |
| X35 | r2's `ClaimTracker` "uses only requester-measured quantities" vs its `decodeObs` = tokens ÷ (first-to-last chunk time), both of which the peer shapes (burst at the end, split chunks, token counts) (R2-OVERCLAIM-1) | r3: one end-to-end observation from the requester's own send of `INFER_BODY` to its receipt of `INFER_END`, over output **bytes the requester parsed itself**; best-case quartile for the honesty state; claims can only be lowered by observation (§5.7) |
| X36 | §3.2 "reports `availability.reason = sleeping`" vs §7.4 "reason removed from the wire" (R2-DIRECTIVES-5) | The wire carries `fsm` only; every reason stays in the local ledger and diagnostics (§3.2, §7.4) |
| X37 | LP-1/LP-2 classify screen state and foreground app as presence vs lend-screen (PF) lenders that must have the screen on (R2-OVERCLAIM-9) | PF exception: the node's own lend screen being frontmost, the screen-on state it needs and touches inside it are consent; leaving it is presence (§7.4) |
| X38 | IC-2 "bytes counted at the TLS record layer" vs Network.framework, which exposes no record sizes before a send (R2-OVERCLAIM-6) | Rows count exact application bytes; transport overhead is a per-session figure, measured where the stack exposes it and otherwise estimated by a stated formula (§8.4) |
| X39 | §5.4 "a fingerprint match is never stored" vs "FILE pins expire after 90 days" and D2(b)'s 30-day TTL (R2-OVERCLAIM-12) | Per-export matches are never stored (no TTL). A TTL exists only for a persistent per-subscriber key (D6(e)): 90 days. D2(b) is gone (AD-2) |
| X40 | The Deck row of §7.1 (r2) vs its own stated basis (R2-OVERCLAIM-3), and `linux.md` §6.3's community measurement, which contradicts the reviewer's FP32 basis | Both bases are shown with their arithmetic; the headline is the envelope of the two (§7.1, A12b-lo/A12b-hi) |
| X41 | `linux.md` puts the whole node in `desktop/node` vs `ubuntu-touch.md`, which reuses the host-independent part | `desktop/node-core` (host-independent) + one host module per OS (`desktop/node` for Linux, `winplatform`, `macplatform`, `ut-host`) (§3.5, `PLATFORM_PLAN.md`) |
| X42 | r2 lab item L0.3 "Linux probe parsers over fixture files" vs `linux.md`'s single home in `desktop/node` | One home: the parsers live in `desktop/node` (DL1); the lab keeps only engine-free benchmark maths |
| X43 | r2 L0.7 "`lab/apple`" vs AD-3 and the macOS/iOS sections (`apple/` at the repo root) | `apple/` at the repo root; it reads vectors from `lab/conformance/` |
| X44 | SteamOS: r2 "one sleep rule on every platform: hold a block-mode sleep inhibitor while SERVING" vs the Game Mode fake-sleep hazard [LF05] | Never a block lock on SteamOS in any session; a delay lock only; Steam's own sleep setting decides availability (§3.2) |
| X45 | r2 M2 "Android lends" before v2.5 vs AD-1 "Android-as-provider is a remaining v4 item" | The Android lender moves after v3 (§9.3) |
| X46 | macOS and Windows sections propose AWT trays in D-v2 vs Invariant 7 (Compose/Material3 only) | CLI-only desktops until D14 part A (C-15) |
| X47 | The r0 vector files (`manifest-vectors/`, `router-examples/`, `bench-examples/`) vs the r2/r3 spec (TOFU contexts, a 30-day file TTL, audience `other`, `results` without `bench`, producer-stored `derived`, live-state fields since removed) | `LAB_SPEC.md` §4.9 lists which vectors are retained, retired and regenerated |

---

## 2. Architecture overview

### 2.1 Holons and roles (directive D-B DECIDED as D0; the mechanisms below are this design's recommendations)

**What makes the mesh holonic, mechanism by mechanism, and where it is only partly delivered:**

| Holonic property | Mechanism in this design | Delivered by |
|---|---|---|
| **Whole on its own** | With the mesh off, a node behaves exactly as v2 (law RL1). Its local API, vault and ledger are unchanged by pairing | **Android** fully. **Desktops** only for the owner's CLI until M2 (D25). **iPhone, iPad, Ubuntu Touch** not at all: they have no cross-app service and no engine serving other apps (§0.1) |
| **Part of a whole** | Pairwise pairing (§4); consent per pair and per direction: "I borrow from it", "I lend to it", "it may see my status" (T5) | every node that can pair |
| **No privileged node** | No coordinator, home node, leader or relay (X1, X29). Every node plans its own requests; no node routes for another | every node |
| **Self-governing** | A lender's own governors and decision table decide accept or decline. The lender is never told the requester's destination set | every lender |
| **Own account** | Each node's ledger is its own record of what it sent and served (§8.4, P1) | every node, including the iOS and Ubuntu Touch requesters (own JSONL ledgers) |
| **The whole has no state of its own** | No mesh-wide registry, election or global view | every node |

**One role model in code; delivered roles per platform.** Every implementation contains both the borrowing and lending sides (the JVM core, or the Swift port pinned by the same vectors). Platforms limit *which* roles they can deliver and *when*; that is capability, not hierarchy. Where a platform cannot deliver a role, this brief does not claim it.

| Role | Definition | Delivered on, and from when (AD-1 order; §3 details) |
|---|---|---|
| **R — borrow** (requester) | Takes a local caller's request, plans placement, runs the attempts, writes its rows | Android daemon (M1). Linux, Deck, Windows and macOS nodes for the owner's CLI (M1 or M1b, D28) and for local apps (M2, D25(b) + D14 part B). iPhone and iPad through AsomKit (M4, placement D24). Ubuntu Touch, own screen only (UT-1, placement D24). Cars and appliances (D26, unscheduled) |
| **PA — lend while awake** | Accepts `INFER_OFFER` from PAIRED peers with no human present, under its own governors | Linux desktop/server (M1). Steam Deck, docked on AC with no game (M1; Game Mode only by the D28 opt-in). Windows desktops on AC and macOS Apple-silicon desktops while logged in (M1 if D28 says so, else M1b). Android while charging (after v3, D16). **Never:** iPhone, iPad, Ubuntu Touch |
| **PF — lend while a lend screen is frontmost** | Serves only while a "lend compute" screen is frontmost | iPad (M5, after v3, D16). Android lend screen (after v3). Deck Desktop Mode with `asom lend --foreground` in a visible terminal (M1). Ubuntu Touch UT-2 (unscheduled). **Never** on iPhone (code-gated off) |
| **B — benchmark producer** | Runs the benchmark to feed its own router and produce a signed manifest | every node with an engine (Android v2; desktops D-v2; iOS asom app; UT-2 quick plan only) |
| **S — subscriber** | Verifies and consumes manifests | every asom node; a third-party app, through one-shot files |

The former role H ("home node that routes for others") stays **deleted** (X1). "Home lender" means an ordinary lender that an iPhone is paired with.

**Directive D-B's scenarios, mapped (timing per AD-1):**

| Scenario | Borrower | Lender | Stop-line | When |
|---|---|---|---|---|
| A phone borrows from a laptop or desktop | Android phone | Linux, Deck, Dell; Windows PC; Mac | inside | M1 (Linux, Deck); Windows/Mac per D28 |
| A phone borrows from another phone | Android | Android, while charging | inside | after v3 |
| A phone borrows from an iPad | Android or iPhone | iPad, lend screen frontmost | inside | after v3 (M5) |
| A laptop borrows from a desktop | desktop node: the owner's CLI, then local apps | desktop | inside | M1 (CLI); M2 (apps, D25) |
| An iPhone borrows from the home desktop | iPhone app (AsomKit) | desktop | inside | M4 (M1b if D24 says so) |
| An Ubuntu Touch phone borrows from the Deck or Dell | the asom app's own chat screen | desktop | inside | UT-1 (M1b if D24 says so) |
| A car head unit borrows from a laptop or iPad in the cabin, for visualisations and simulations | the head unit, **only if** it runs an asom requester the owner installed and paired [A32] | laptop or iPad, over the car's or the phone's hotspot | inside when owner-paired | unscheduled (D26). Covered only where the heavy work is model inference (§2.2) |
| A Wi-Fi appliance borrows compute | an appliance running an asom requester the owner installed (a Linux board; a Rabbit R1 on Ubuntu Touch 24.04 [UF02]) | any lender | inside when owner-paired | unscheduled (D26) |
| A device that pairs itself, or a lender that serves unpaired passengers or guests | — | — | **outside** (roadmap §11) | never |
| An appliance with no radio, or with closed firmware | — | — | not a target | never |

### 2.2 What "borrowing intelligence" means, and what it does not

- **It means:** one whole inference request (chat, completions or embeddings) served by one lender's local engine, on a model file that lender holds, with the output streamed back to the borrower.
- **It does not mean:**
  - splitting a model or a request across devices (§7.1, D10);
  - running code, simulations or rendering on the lender. The peer protocol has no frame for anything but inference, and "a peer serves with its local engine only" (R6);
  - sharing files, keys, ledgers or app identities (§2.6);
  - relaying a request onward.
- **For the car scenario:** a visualisation or simulation benefits only where its heavy step is a model inference call (for example, a model that generates a scene description or predicts the next state). Rendering and physics stay on the device that shows them. General compute offload would be a new design session with a far larger trust surface: running another device's code.

### 2.3 Planes

```
                        (unchanged v1)                                   (new: Amendment 2 listener, Amendment 3 traffic)
  paired local app ──HTTP 127.0.0.1:11435──▶ ┌────────────── asom node ──────────────┐ ◀──asom-mesh/1 over mTLS 1.3──▶ paired peer node
  (AIDL identity, Android)                   │ app-facing API   (frozen §5)          │     port 11436, bound only to eligible
  desktop owner CLI ──local socket, OS id ──▶│ MeshPipeline ── MeshRouter ── v1 Router│     overlay / user-confirmed LAN addresses
                                             │ Engine (v2)  Governors (v2 P4)        │     (the same node dials out when it borrows,
                                             │ Bench core  Manifest store  Registry  │      and is dialled when it lends)
                                             │ Ledger (append-only; Room | JSONL)    │ ──HTTPS──▶ cloud providers (user's keys)
                                             └───────────────────────────────────────┘ ──HTTPS──▶ catalogue, model downloads
```

**App-facing plane (unchanged).**
- Android keeps the frozen v1 API on loopback.
- A desktop node offers no local-app API in mesh-1, only the owner's CLI over a local socket whose caller the OS identifies: a Unix-domain socket with `SO_PEERCRED` on Linux, `getpeereid` on macOS, and an AF_UNIX socket protected **only by its file ACL** on Windows (no peer credentials, [AW04]) (§4.2 T17, CD-24). The CLI can borrow (`asom chat`), which is how desktops are requesters in mesh-1. That caller is not an app; Amendment 3's IC-4(b) says so if the owner rules D25(a). A desktop local-app API needs D25(b) and D14 part B.
- iOS and Ubuntu Touch have no app-facing API at all: the requester lives inside the owner's app (AsomKit) or behind the app's own UI (Ubuntu Touch, a private stdio channel, §3.4.6).

**Peer plane (new).** One protocol, `asom-mesh/1`: length-prefixed frames over TLS 1.3 with both sides authenticated and pinned. It is symmetric: either node of a pair can dial, and the same frames flow in either direction. It carries two kinds of traffic:
- **Control frames:** `HELLO`/`HELLO_ACK`, `STATE_REQ`/`STATE`, `MANIFEST_REQ`/`MANIFEST`, `PAIR_*`, `REVOKE_NOTICE`, `GOAWAY`, `ERROR` (r2: `PING`/`PONG` removed). Each has its own ledger row rule (§8.4).
- **Attempt frames:** `INFER_OFFER`, `INFER_ACCEPT` or `INFER_DECLINE`, then `INFER_BODY`, `INFER_HEAD`, `INFER_CHUNK`, `INFER_END`, and `CANCEL`. They are ledgered as attempt rows on both nodes.

**Rules that hold across both planes:**
- The peer plane never reaches the app-facing API.
- The app-facing API never accepts a mesh identity.
- The peer protocol never binds loopback.

### 2.4 Lifecycle of one request (mesh-1)

1. **App request arrives.** A paired app (or, on a desktop, the owner's CLI) sends a request to its own node. The destination set `P` is computed from policy (§8.5).
2. **Plan.**
   - `MeshRouter.plan(query, snapshot)` filters and scores SELF and each eligible PAIRED peer, then merges them with the **unchanged** v1 cloud plan (§7).
   - Inputs are the requester's own observations, peers' banded live state (piggybacked; no polling) and verified manifest claims.
   - `plan` is pure.
3. **Attempt loop.** For each attempt:
   1. Re-check eligibility against the live registry and app policy.
   2. Write the durable **intent row**.
   3. Dial, or reuse a session. Each new connect gets its own `DIAL` row.
   4. Send `INFER_OFFER` (metadata only).
   5. On `ACCEPT`: re-evaluate the plan, re-check eligibility, send `INFER_BODY`.
   6. Relay `HEAD`/`CHUNK`/`END`.
   7. Write the **outcome row**.
4. **Echo headers and the terminal row.** The request's last outcome row is its **terminal row**. The headers are built from that record, and its `egress` column is `reach`: the furthest class that received content in this request. So the header and the dashboard agree (Invariant 9). The serving attempt's own class is in `servedClass` (§7.6, CD-1m).
5. **Lender side.** The lender decides from its own decision table, writes its intent row before its engine reads the body, and runs the request on its **local engine only**. It keeps no body and no cache across attempts (default), and writes its outcome row before `INFER_END`.

### 2.5 Lifecycle of capability knowledge

1. **Benchmark (B, local).** The user starts a run. The core produces `asom.bench/1`, active lane only, with MLPerf-defined metrics where the comparability rule allows (§6.1). The daemon shows the report and the *exact body* that would be shared.
2. **Approve (local, view-first).** The user approves sharing *that body digest* with specific peers. Until then, peers keep the previously approved body.
3. **Pull (peer plane).** A requester pulls `MANIFEST` with a fresh challenge when:
   - it has no verified body for that peer;
   - the peer's `st` digest shows a new approved `bodyDigest`; or
   - the user opens the Peers tab.
4. **Verify (requester).** Signature under the **session's pinned key**, canonical bytes, schema, consistency, **re-derivation** of `results[]` from `bench`, and rollback.
5. **Use (router).** The claims become *priors* in `ClaimTracker`. Observation can only **lower** them: after 3 kept end-to-end observations measured entirely by the requester (its own send and receive times, output bytes it parsed itself), placement uses the lower of the claim and what was observed (§5.7).

### 2.6 Never on the mesh (in any version of this design)

- BYOK keys or key presence;
- ledger rows or verbose bodies;
- model files;
- app identities (r2: including an iPhone host app's identity, §7.8);
- the pairing registry;
- the app-facing API;
- cloud calls made for a peer;
- forwarding to a third node;
- any field computed from presence inputs (screen, input activity, foreground app, login), except through the accept/decline decision and the availability FSM (LP-1). Presence remains **inferable** from those (§7.12);
- passive (usage-derived) benchmark samples;
- custom-model hashes;
- code, or any workload other than model inference (§2.2).

Adding any of these is a new design session, not a flag.

---

## 3. Platform matrix and code strategy

The six platform sections under `platforms/` are the detailed specs for their platforms, **as amended by this section** (§12.3 lists which of their proposed corrections were taken). `PLATFORM_PLAN.md` turns them into directories, CI jobs, gates and a build order.

### 3.1 Consolidated platform matrix

**Legend.** R = borrow; PA = lend while awake with nobody present; PF = lend only while a lend screen or terminal is frontmost; B = benchmark producer; S = manifest subscriber. "M1" = mesh-1; "M1b" = the platform-requester placement inside mesh-1's window that D24/D28 rule; "after v3" = the remaining-v4 items of AD-1. Every performance figure is an estimate. "CI proves" names only what GitHub-hosted runners can compile or run; "stays NDV" names what remains NEEDS-DEVICE-VALIDATION.

| Platform | Roles (when) | Holon status | Runtime | Node key tier (reported `keyStorage`) | Engine backend | CI proves (hosted runners) | Stays NDV |
|---|---|---|---|---|---|---|---|
| **Android** (existing v1 daemon, `xyz.mdhv.asom`) | serve own apps (v1/v2); **R (M1)**; PF and PA-charging **after v3**; B (v2); S | **full** | Kotlin on ART; shared pure-JVM core + `:mesh-android`, `:qr` | StrongBox or TEE P-256 (`strongbox`/`tee`); keyguard-bound leaf, or an opt-in "serve while locked" leaf for charging lenders | llama.cpp JNI CPU/OpenCL/Vulkan/Hexagon per v2 P6 bakeoff | compile, JVM tests, APK policy (permissions, no GMS, 16 KB alignment) on `ubuntu-24.04`; KVM emulators API 35–37: ART conformance lane incl. W08 on Conscrypt, Room migrations, forced Doze, thermal overrides, the loopback/LNP probe (EMULATOR EVIDENCE) | StrongBox, real attestation roots, OEM power manager (RedMagic), heat, radio, overlay, Tailscale log switch |
| **Linux desktop/server** (the Dell, OS assumed Ubuntu/Fedora) | R for the owner CLI (M1); **PA (M1)**; PF (terminal); B (inside the node only); S | partial until M2 (CLI only; no cloud tier) | JVM: `desktop/node-core` + `desktop/node` (Linux host), jlinked Temurin 21 | T1 `systemd-creds` sealed `host+tpm2` in SYSTEM mode (`file`, sealing shown locally only); T2 TPM via tpm2-pkcs11 only after spike S-L3 (`tpm`) | llama.cpp JNI CPU (all variants) + Vulkan; CUDA per D27 | JDK 17/21 tests on x64 and arm64; native CPU and Vulkan builds; tiny-GGUF generation on CPU and on Mesa's software Vulkan; jlink/deb/rpm/tarball; distro container matrix incl. Arch; systemd units on the runner VM (disabled after install, journal hygiene, delay lock held, block lock refused without the polkit rule); multi-node network-namespace suite (M1) | GPU speed, real suspend/resume, logind `PrepareForSleep`, TPM sealing, polkit on the owner's distro, NVIDIA probes |
| **Steam Deck** (SteamOS 3.8) | R via SSH or Desktop Mode; **PA docked on AC, no game (M1)**, Game Mode only behind an invisible-lending opt-in; PF in Desktop Mode; never on battery | partial | same JVM node, USER mode, tarball in `$HOME` | T0 file only (SteamOS blacklists the TPM [LF03]); host label `shared-uid` | Vulkan (RADV) default, CPU fallback | nothing SteamOS-specific (the Arch container is a userland proxy only) | everything Deck-specific: Game Mode user service and linger, no fake sleep with a delay lock only, fdinfo GPU accounting, Vulkan heap budget, the 8B bakeoff, game-drain stutter |
| **Windows 10/11** | R for the owner CLI (M1/M1b); **PA on AC desktops** (M1 only if the Dell runs Windows, D28); laptops conditional; never in Modern Standby; PF feasible, not recommended | partial | same JVM node + `winplatform` (Kotlin + JNA 5.19.x) | T2 CNG Platform Crypto Provider (TPM, P-256 pending S-W1) (`tpm`); T1 CNG Software KSP (`os-keystore`); T0 file for CI only | llama.cpp CPU + Vulkan (x64), CPU + OpenCL-Adreno (arm64); CUDA per D27 | `windows-2025` and `windows-11-arm`: lab families on Windows JDK 17/21 (charset and CRLF traps), real DPAPI/CNG-software/power-request/PDH/AF_UNIX-ACL tests, native builds, tiny-GGUF CPU generation, WiX MSI, service install/start/stop/uninstall, listener and firewall-gate checks, two-node smoke, W08 | TPM tier, GPU execution, sleep/lid/Modern Standby, SmartScreen/Smart App Control, Tailscale and its log opt-out, real firewall profiles, winget acceptance |
| **macOS** (Apple silicon, macOS 15+) | R for the owner CLI; **PA on desktops while logged in** (M1 only if an Apple-silicon desktop exists, D28); laptops conditional; never asleep, never before FileVault unlock; PF not recommended | partial | same JVM node + `macplatform` (pure Kotlin) + Swift helper `asom-mac-helper` + hardened C launcher `asom-node` | T2 Secure Enclave P-256 through the helper, blob in the Team-ID group container (`secure-enclave`); T0 file fallback; login keychain rejected | llama.cpp Metal + CPU | `macos-latest` (macOS 26, M1 VM) and `xcode-27`: JDK 17/21 lab families; helper protocol and power-assertion tests; env-injection probe (stock launcher injectable, `asom-node` not); Metal build + CPU tiny-GGUF; unsigned pkg/dmg; two-node smoke; W08; **signing and notarisation only on `main`/tags with the owner's secrets** | Secure Enclave, Metal speed, Local Network prompt, Login Items UX, sleep/lid/FileVault, Tailscale variants |
| **iPhone** (iOS 26+ recommended) | **R (M4; placement D24)**: AsomKit in the owner's apps, one home lender, foreground + best-effort ~30 s grace; B (owner devices only; feeds no router); S; **never PA or PF** | **partial: borrow-only** | Swift 6, `apple/` package (independent implementation pinned by vectors) | Secure Enclave P-256 in a Team keychain access group (`secure-enclave`, pending S-A12); T1 software key only on simulator/CI | llama.cpp XCFramework (Metal) for the bench only; CPU in CI | Linux `swift test` (pure targets); `macos-latest` CryptoKit tests, iPhone/iPad simulator tests, unsigned device build, plist/entitlement/linked-framework lints, CPU tiny-model in the simulator, W08 against the JVM hostile node, SIGKILL ledger durability | Secure Enclave, keychain sharing across signed apps, `sec_identity` from the leaf (S-A2), Local Network prompt, Metal, background/GPU revocation, Tailscale, App Review |
| **iPad** | as iPhone, plus **PF after v3 (M5, D16)**: only while the lend screen is foreground-active, awake, unlocked, charging | partial | as iPhone | as iPhone | Metal with a stop-before-background guard; CPU fallback | as iPhone, plus the server-role W08 in the simulator | as iPhone, plus 8B on 16 GB iPads, background during Metal decode (process-abort risk) |
| **Ubuntu Touch** (24.04-1.x/2.x, Halium) | **R (UT-1; placement D24)**, own chat screen only, foreground only; S; B and PF only in the unscheduled UT-2; **never PA, never a cross-app service** | **partial: borrow-only** | shared JVM code in the click as a jlinked Temurin 21 aarch64 runtime (39 MB measured [UF37]), QML/Lomiri UI over a private stdio channel; **conditional on on-device spike S-UT1**, Rust single-lender core as fallback | T0 file (no reachable hardware keystore) (`file`) | none in UT-1; llama.cpp CPU in UT-2 | JDK 17/21 host tests; cross-jlink of the aarch64 runtime; arm64 click build and click-review in the Clickable container; QML tests; **the node self-test inside the real arm64 Ubuntu Touch userland image**; a labelled CI-APPROX AppArmor run | Halium kernel, Lomiri lifecycle (SIGSTOP), repowerd suspend, confinement on device (S-UT1), OpenStore install, Wi-Fi/overlay, performance |
| **Car head unit / Wi-Fi appliance** (D26) | R only, if the owner installs and pairs an asom requester | partial: borrow-only | the JVM requester profile (headless) | T0 | none | as Linux (the same jar) | everything |

**What the matrix does NOT say.** It does not say any node is fast enough to be worth lending from; §7.1 and the D-v2 owner-device benchmark decide that. It does not say a role is safe on a shared device: tiers are self-reported to peers, and a same-user process can use the node's identity on every desktop in user mode (T17(g), §3.4).

### 3.2 Provider availability FSM (all platforms; conditions differ)

The FSM of `platforms.md` §2.1 (OFF → ARMED → SERVING → DRAINING) is retained with these rules:

1. **Only `fsm` goes on the wire.** Sleep, presence, heat, a game or a lost charger all show to peers only as a change of `availability.fsm` (and as `PEER_UNAVAILABLE` on offers). **Every reason stays in the local ledger and diagnostics** (R2-DIRECTIVES-5; r2's "reports `availability.reason = sleeping`" is withdrawn).
2. **`os_sleep_imminent` drains.** Sources: logind `PrepareForSleep(true)` through a delay inhibitor (Linux; an in-house read-only D-Bus reader, LD-12 in D27); `PBT_APMSUSPEND` and display-off on Modern Standby (Windows); `kIOMessageSystemWillSleep` (macOS, 2 s acknowledgement, because the user who shut the lid expects sleep); scene resign-active (iOS); SIGSTOP-imminent lifecycle signals (Ubuntu Touch). A sleep-caused drain is **not** a presence drain: LP-2's 10-minute hold-down does not apply.
3. **Keep-awake is per platform, never assumed** (r2's "one sleep rule on every platform" was false on SteamOS, X44):

| Host | While SERVING | Never |
|---|---|---|
| Linux, SYSTEM mode (dedicated user `asom`) | a logind **block** `sleep` lock **only if** the shipped polkit rule grants it to user `asom` (upstream denies block locks to session-less processes [LF13]; D28); otherwise serve only while the machine happens to be awake, and `asom status` says `keep-awake: unavailable (polkit)` | an `idle` lock (screen lock stays unaffected) |
| Linux, USER mode with a session | try the block lock; if polkit refuses, serve without it and say so | — |
| **SteamOS, any session** | a **delay** lock only; Steam's plugged-in sleep setting decides availability | **a block lock**: in Game Mode it produces a "fake sleep" (screen off, system running and hot) [LF05] |
| Windows | `PowerSetRequest(PowerRequestSystemRequired)` with the reason "asom: lending compute to your paired devices", visible in `powercfg /requests` | preventing user-initiated sleep (impossible [FW07][FW08]); lending during Modern Standby |
| macOS | `PreventUserIdleSystemSleep` assertion held by the helper, visible in `pmset -g assertions` | `PreventSystemSleep` (Dark Wake); preventing lid-close or Apple-menu sleep (impossible [FM07][FM08]) |
| Android (lender, after v3) | the FGS; wired charging; no battery-exemption request | lending unplugged with the screen off |
| iPad (M5) | `isIdleTimerDisabled` on the lend screen only | any background serving |
| every host | a delay lock (or its equivalent) so a drain can finish | changing the OS's power settings |

4. **Presence drains immediately, and SERVING returns no sooner than 10 minutes after the last presence signal** (LP-2, §7.4). Grace for in-flight streams: 30 s on desktops, **2 s on the Deck** (a Vulkan dispatch cannot be pre-empted by a game [LA17]), 10 s on Android, none on iOS (GPU work must already have stopped at resign-active [IF08]).
5. **GPU contention** is measured as device busy minus the node's own busy (amdgpu `gpu_busy_percent` minus own DRM fdinfo on Linux [F20][LF19]; PDH per-process GPU counters on Windows [AW05]; device utilisation minus the engine's own in-flight fraction on macOS [AM12]). Hysteresis: eligible below 200‰ for 60 s; drain above 400‰ for 10 s. Where no counter exists the rule is off and `asom doctor` says so. NVIDIA on Linux has no own-versus-other attribution, so it drains on the thermal band only.
6. **Wording.** Every UI and doc says "lends while awake", never "always-on".

**The M1 gate includes an idle test** per lender class: idle 30 min on AC, then a request; it succeeds or fails over within the dial budget with a correct `DIAL` row.

### 3.3 Cross-cutting platform constraints (as amended)

| ID | Constraint | Status in r3 |
|---|---|---|
| C1 | Integers only, in declared units, within ±(2⁵³−1), in **signed JSON and live-state JSON**; floats, exponents, `-0` and duplicates rejected. v1 double-based behaviour is pinned as shortest round-trip decimal strings (W01/R04) and must pass on JDK 17, JDK 21, the packaged runtimes **and ART** before it is normative [F16] | unchanged |
| C2 | **ES256 (P-256) only**; wire form 64-octet r‖s; DER↔raw codec with vectors; **Swift producers normalise to low-S** (swift-crypto emitted high-S in about half of 1,200 signatures [IS1]); Ed25519 rejected everywhere (no Secure Enclave, StrongBox or TPM support [F06][IF16]); ML-DSA-65 parked for a post-quantum major | low-S producer rule added |
| C3 | Peer traffic is TLS at the application layer, even inside the overlay | unchanged |
| C4 | Apple peer transport is Network.framework with one verify block running `verifyPeerChain` (swift-certificates + swift-asn1); `SecTrust` evaluation forbidden; no ATS exception needed [IF11] | unchanged |
| C5 | Overlay or LAN is the owner's choice (D8); its exposure is disclosed | unchanged |
| C6 | No cross-app daemon on iOS **or Ubuntu Touch**. Their requesters serve only the caller they are built into | Ubuntu Touch added |
| C7 | Backup exclusion everywhere (iOS `isExcludedFromBackup` + `ThisDeviceOnly`; Android `dataExtractionRules`; Linux `CACHEDIR.TAG`; macOS container exclusion + an `IOPlatformUUID` binding guard; Windows `%LOCALAPPDATA%` non-roaming, with the roaming-profile limit of CNG user keys stated [FW16]) | extended to every desktop |
| C8 | A metered underlay blocks peer attempts unless the app allows it | unchanged |
| C9 | Local-caller identity per OS: Linux `SO_PEERCRED` (a user name, not a numeric uid [LF25]); macOS `getpeereid`; **Windows: socket file ACL only** (no peer credentials [AW04]); Ubuntu Touch: an anonymous stdio pipe owned by the app. Rows say so: `local-uid:<user>`, `local-sid:<sid>(acl)`, `self-ui:<artefact>` (CD-19, D25) | Windows and Ubuntu Touch added |
| C10 | One benchmark harness per OS; the iOS app does not run on Macs | unchanged |
| C11 | Comparability key `(backend, commit, buildFlags)`; one llama.cpp commit per backend across every first-party build | unchanged |
| C12 | No mesh setting is a process-wide JSSE system property | unchanged |
| C13 | Native libraries load only from inside the sealed or admin-owned install, sha256-checked, never extracted to temp (JNA with `jna.nounpack=true` on Windows; no JNA on macOS; RPATH `$ORIGIN` on Linux). Where the install is user-writable (a `$HOME` tarball, the Deck) the node is labelled `shared-uid` | Windows/Linux specifics added |
| **C14** | **No in-app update check on any platform.** An automatic version check is background egress with no Invariant 3 class. Updates arrive only through the user's own package manager or store action | new (every platform section) |
| **C15** | **No crash data leaves with content.** `-XX:-CreateCoredumpOnCrash`, `ErrorFile` in the private state directory, stdout/stderr never carry tokens, rows or prompts; Windows WER exclusion of asom's executables; `LimitCORE=0` and `MemorySwapMax=0` on Linux; OS-level diagnostics (WER, Apple crash sharing, TestFlight) disclosed, not controlled | new |
| **C16** | **Windows firewall consent.** The listener never starts before a narrow, explicitly consented, elevated allow rule exists; a cancelled first-listen prompt would create block rules that override allow rules [FW05] | new |
| **C17** | **Byte-exact paths are explicit UTF-8 and line-ending-proof**: JDK 17 on Windows is not UTF-8 by default [AW20]; `.gitattributes` marks `lab/conformance/** -text` and all fixtures | new |
| **C18** | **Weak-host exposure (Linux).** A listener bound to an overlay address also accepts that address from another interface; the node certificate is then disclosed to LAN hosts that route `100.64/10` via this machine [LF39]. Documented; `asom doctor` prints (never applies) an nftables rule; a CI netns test proves both cases | new (LD-11 in D27) |

### 3.4 Per-platform summaries

#### 3.4.1 Android (`platforms/android-mesh.md`)

- **Design-only until v1 device validation closes.** Nothing touches `app/` or any shipped module before then; no new directory is created now.
- **Loopback settled by source reading** (android-17.0.0_r1): the Android 17 local-network permission never covers `127.0.0.1`; a paired app in the **same profile** keeps reaching `127.0.0.1:11435` at any targetSdk [AF05]. **Cross-profile loopback is blocked for all apps on Android 17** [AF04]; a reserved `USE_LOOPBACK_INTERFACE` permission hints at future same-profile gating (new critical-impact watch risk K31). H5 stays as a confirmation gate; K3 is lowered.
- **Overlay peers** with single-IP Tailscale addresses are not "local network" under the r1 rules; same-subnet LAN peers are [AF05][AF23]. The rule changed between the 2025 `main` snapshot and 17 r1 [AF06], so it is re-checked per release.
- **Doze** keeps FGS network and partial wake locks on AOSP [AF12]; OEM behaviour (the RedMagic) is NDV. Unplugged lending stays rejected for battery and heat.
- **Roles:** full holon; borrows in M1 (outbound only, targetSdk stays 35, D29); lends after v3 in two opt-in shapes: charging with the screen off (needs a "serve while locked" leaf, a disclosed T9 trade-off) and lend-screen-frontmost. Never always-on.
- **New modules** (root build, after validation; D23): `:mesh-android` (key store, Conscrypt transport, dialer, listener, network classifier, local-network gate, probes, governor, node registry) and `:qr` (CameraX + zxing-core). **CameraX is a new dependency** (R2-OVERCLAIM-10).
- **Tailscale Android** gained a "Remote client logging" switch in 1.98, default on and forced on under MDM [AF21]; D8's disclosure changes accordingly.
- **Estimate:** 11–19 engineer-weeks Android-specific, overlapping M1 and the lender phase.

#### 3.4.2 Linux desktop/server and Steam Deck (`platforms/linux.md`)

- **The Dell is the program's best lender**: a dedicated-user systemd **system** service (`User=asom`, sandboxed, `StandardOutput=null`, `LimitCORE=0`, `MemorySwapMax=0`, `Nice=10`, `CPUWeight=20`, idle IO class), lending while awake; keep-awake only with the narrow polkit rule (D28).
- **The Deck lends only docked, on AC, with no game running**, and only while Steam lets it stay awake; **never a block lock on SteamOS** [LF05]; Game Mode lending is **invisible on the Deck's own screen**, so it needs an explicit opt-in whose copy says so (D28); battery lending is a hard no.
- **Headscale does not stop Linux clients uploading logs unless `TS_NO_LOGS_NO_SUPPORT=true` is set on each client** [LF10]; the Deck's Tailscale installer resets `override.conf` on every run [LF08]. asom cannot verify the setting (root's daemon); `asom doctor` prints instructions and says "not verified".
- **Packaging:** one jpackage app image per architecture (jlinked Temurin 21), wrapped as a `$HOME` tarball (Deck, immutable distros) and deb/rpm via nFPM; units shipped **disabled**; no Flatpak, AppImage, Snap or pacman; no in-app updater; `SHA256SUMS` signed offline by the owner plus GitHub attestations.
- **Benchmark:** `asom bench` exists only inside the node (MLPerf Client covers Ubuntu 24.04 [LF37]); the standalone desktop CLI is dropped (D7).
- **Estimate:** 12–19 engineer-weeks (DL0–DL7); DL0–DL3 (skeleton, probes, host integration, packaging; `NoopEngine`, no listener) are scaffolds authorised now (roadmap §14 item 7).

#### 3.4.3 Windows 10/11 (`platforms/windows.md`)

- **Same JVM node**; Windows-specific code is one adapter (`winplatform`, Kotlin + JNA 5.19.x, a new dependency) for keys, power, presence, ACLs and firewall probing, plus packaging.
- **Hosting:** user mode (logon task) by default; service mode opt-in through Apache procrun **in-process** (`StartMode=jvm`) as `NT SERVICE\asom`, for serve-while-logged-out. `sc.exe` cannot host a JVM launcher; WinSW is rejected.
- **Lending:** yes on AC desktops; conditional on laptops (AC, lid open or `LIDACTION=0` set by the owner, never by asom); **impossible during Modern Standby** (desktop apps suspended, services throttled to about 1 s per 30 s, network hidden [FW09][FW09b]).
- **Firewall:** the listener never starts before a consented, elevated, view-first allow rule exists (C16).
- **Owner CLI identity is the socket file's ACL only** (no peer credentials); weaker than Linux `SO_PEERCRED` and stated (D25).
- **Keys:** T2 CNG Platform Crypto Provider (TPM; P-256 pending S-W1), T1 Software KSP; Windows Hello rejected (per-signature gesture, RSA, online account).
- **Engine:** llama.cpp CPU/Vulkan (x64), CPU/OpenCL (arm64); DirectML and Windows ML rejected (sustained engineering; OS-mediated execution-provider downloads asom could not ledger [FW36][FW37]).
- **Packaging:** own WiX MSI (Node feature default; Service feature off), Authenticode on every PE and the MSI, winget `asystemofcells.asom`; unsigned builds are CI artefacts only (Smart App Control blocks unsigned files [FW26]).
- **UI:** CLI only until D14 part A; the AWT tray waits (C-15).
- **Estimate:** 10–16 engineer-weeks, additive to the r2 total.

#### 3.4.4 macOS (`platforms/macos.md`)

- **Same JVM node** plus three Mac-only pieces: `macplatform` (pure Kotlin, tested in the Linux container with a fake helper); `asom-mac-helper` (Swift; Secure Enclave via CryptoKit, `SMAppService`, IOKit sleep and power assertions, probes; spoken to over inherited JSON-Lines pipes; no entitlements; reads no files); and `asom-node`, a ~200-line C launcher that scrubs `JAVA_TOOL_OPTIONS` and friends, accepts a fixed argv and disables the attach mechanism, because a stock jpackage launcher would let any same-user process inject code into the process that holds the group-container entitlement.
- **Hosting:** an `SMAppService` LaunchAgent by default (user-visible in Login Items; Local Network use attributed to the app); an opt-in LaunchDaemon with a role user only at M2 after spike S-M5.
- **Lending:** Apple-silicon desktops while logged in; laptops only on AC with the lid open; never asleep (forced sleep cannot be prevented), never after a FileVault restart until unlock.
- **Local Network privacy** gates only outgoing LAN dials; accepting inbound needs none; overlay (VPN) traffic is exempt [FM01].
- **Keys:** Secure Enclave P-256 via the helper, blob in the Team-ID group container (protected by SIP; closed to other teams by default on macOS 27) — **the blob is not bound to the app**, so the container and the launcher are the protection, both OS policies; T0 fallback; login keychain rejected (the `security` CLI route is readable by any same-user process, demonstrated in CI).
- **Engine:** llama.cpp Metal; MLX, Core ML and Foundation Models are not mesh-1 backends. MLPerf Client already benchmarks macOS [F49].
- **Packaging:** notarised pkg/dmg (Developer ID, inside-out signing), an own Homebrew tap; no Mac App Store; signing only on `main`/tags with the owner's secrets. The Team ID is permanent (D22).
- **Estimate:** 11–17 engineer-weeks (minimal cut 7–10), replacing r2's 4–6.

#### 3.4.5 iOS and iPadOS (`platforms/ios.md`)

- **No daemon, ever**: iOS suspends backgrounded apps and cannot resume on a network request; listeners must close before suspension [IF01][IF04].
- **The node on iOS** is one app, `xyz.mdhv.asom` (display name "asom"), hosting the benchmark, pairing, verifier and (iPad, after v3) the lend screen, plus the AsomKit requester targets inside the owner's other apps. One Team-signed host holds the node key and registry (iOS-D2 in D24).
- **Roles:** iPhone R (M4), B (owner devices; feeds no router), S; **no PA, no PF** (iPhone PF is code-gated off). iPad adds PF after v3. Neither serves other apps: **borrow-only holons**.
- **The ~30 s background grace cannot carry inference**: GPU access is revoked on backgrounding, and ggml-metal has aborted processes when that happens [IF05][IF08]; engines stop GPU work on resign-active.
- **ES256 stays the only algorithm** (the Secure Enclave has no Ed25519 [IF16]); Swift producers normalise to low-S (C2). **App Attest and DeviceCheck are rejected** (they contact Apple; built for an operator server).
- **Byte accounting on Network.framework** is plaintext per frame plus `DataTransferReport` reconciliation at session close; this is why IC-2 now counts application bytes exactly and transport overhead per session (§8.4).
- **MLPerf Client already benchmarks iPad Pro** (M2+, 16 GB) with llama.cpp-Metal and MLX [IF29]; an iOS benchmark is therefore an owner-devices-only validation tool, and M4 does not wait for it (R2-DIRECTIVES-6).
- **Estimate:** 21–34 engineer-weeks (18–29 excluding the Swift lane shared with macOS).

#### 3.4.6 Ubuntu Touch (`platforms/ubuntu-touch.md`)

- **A foreground-only requester and verifier, nothing more, in mesh-1's window.** Lomiri SIGSTOPs every process in an unfocused app's cgroup [UF15][UF20]; repowerd suspends the system about 4 s after display-off [UF21]; no confined policy group can hold the system awake. **PA is a firm no; so is a cross-app daemon** (the node is frozen whenever another app is focused, and loopback callers have no OS-verified identity).
- **No BYOK keys, no cloud tier**: no hardware keystore or keyring policy group is reachable from a click. When no peer can serve, the request fails with a typed error.
- **Runtime:** the shared JVM node inside the click (jlinked Temurin 21 aarch64, 39 MB on disk, glibc 2.17 [UF37]), driven by a thin QML/Lomiri UI through a C++ `QProcess` bridge over a private stdio channel `asom-ut-ctl/1` (not a contract surface; listed in D23 for transparency). **Conditional on spike S-UT1** (a headless JVM with TLS 1.3 under click confinement on a real Halium device); fallback: a Rust single-home-lender core (+8–12 weeks). QML `XMLHttpRequest` and Qt 5.15 `QSslSocket` cannot speak pinned mutual-TLS `asom-mesh/1` and are rejected.
- **Keys:** T0 file in the app-private directory; fscrypt recommended; passphrase wrapping optional.
- **UI:** QML against the token seam needs D14 part A (IC-7 must name it).
- **Estimate:** 7–11.5 engineer-weeks (UT-0 + UT-1); 13–21.5 with the unscheduled UT-2.

#### 3.4.7 Long-horizon holons (D26; unscheduled)

Unchanged from r2 (car head units and Wi-Fi appliances as **borrow-only requesters**, only when owner-paired). New concrete example: a Rabbit R1 running Ubuntu Touch 24.04 is a D26 appliance requester through the UT-1 app [UF02]. General compute offload remains outside this design.

### 3.5 Code strategy: one JVM implementation, one Swift implementation, nothing else

**Decision (unchanged in substance; KMP ban kept, D17 settled by CLAUDE.md):**
- **One Kotlin/JVM implementation** of every spec runs on Android (ART), Linux, the Deck, Windows, macOS, Ubuntu Touch (inside the click) and the D26 profiles. Platforms differ only in thin host layers and in their TLS stack (JSSE on desktops and Ubuntu Touch; Conscrypt on Android), which W08 covers on each.
- **One independent Swift implementation** for iOS/iPadOS (and the Apple conformance lane on macOS), written from the spec prose and pinned by the same vectors. It is never linked into the Mac node.
- **No third implementation.** A Rust core exists only as the Ubuntu Touch fallback if S-UT1 fails, scoped to a single home lender like iOS M4, with its own conformance lane.
- **Rejected everywhere:** GraalVM native-image, a Rust/Go daemon on desktops, Python, a `llama-server` subprocess or llama.cpp RPC, KMP, Compose for Desktop (its tooling usually comes through the multiplatform plugin).

**Why it holds.** The iOS requester is single-provider (no router in Swift; R01–R06 and M08 never run on iOS). Multi-provider choice on iOS remains a D17 re-escalation trigger.

**Swift surface by stage** (estimates): L0.7 core ~3–4k lines (shared with the macOS lane); iOS app with bench (M3 code) ~6–8k; requester (M4) ~5–7k; iPad lender (M5) ~4–6k.

**Conformance suite:**
- **Location:** `lab/conformance/` until promotion; a repo-root `conformance/` only under D23 (CD-C) (R2-DIRECTIVES-8 corrects r2's "at the repo root").
- **INDEX.json** sha256s detect incomplete checkouts only; authenticity comes from signed tags or owner-signed releases.
- **Families:** W00–W03 (frozen v1), **W01b** (one `RouteRecord` → header map and ledger row), W04–W08 (pairing, pins, frames, live state, the hostile-node suite), **W07-presence** (incl. the PF exception), M01–M08 (JCS, verify accept/reject, derive, render, public derivative, evidence (deferred), claim tracker), R01–R06 (router), UTC01–UTC05 (Ubuntu Touch control channel), the macOS helper-protocol vectors. `LAB_SPEC.md` §4–§6 is the implementable list.
- **Oracle rule and independence (R2-OVERCLAIM-8).** The spec text is the oracle. Vectors produced by one generator stay tagged **self-oracled** until an implementation agrees whose author **had no access to the generator source**, recorded in `PROGRESS.md` with the session or author id. The Swift lane is written from the prose and is the natural independent implementation; the Kotlin verifier and the Python checker written in the same session as the generator do **not** clear the tag.
- **Lanes:** JDK 17 and 21 on Linux (the gate); Windows JDK 17/21 (charset/CRLF); macOS JDK 17/21; the packaged jlinked runtimes (Linux, Windows, macOS, Ubuntu Touch arm64); the ART emulator lane (W01, W01b, W05, W08 on Conscrypt, M01–M03, W07-presence); Swift on Linux (swift-crypto) and macOS (CryptoKit) with a per-vector `lane-diff` against the JVM runner; the Python checker.
- **Test keys:** a `TEST-ONLY` deny-list enforced by every production verifier and pin import.

**Drift cost (estimates, per platform section):** Windows adapter 2–3.5k lines Kotlin + ~600 WiX/PowerShell; macOS 1.5–2.5k Kotlin + 1–1.5k Swift + 0.2k C; Linux host 5–7k Kotlin; Ubuntu Touch host 1.5–2.5k Kotlin + 2–3k QML + 0.4–0.7k C++; Android host (`:mesh-android`) plus an ART lane at ~1 day per release. The recurring costs are platform behaviour under shared code (charsets, file semantics, socket options, probes), not second implementations.

### 3.6 Directory and module layout

**Now** (AD-3; every directory ships nothing; the root build and `./gradlew jvmTest` are unchanged):

```
docs/design/mesh/        # this brief, its reviews and dispositions, LAB_SPEC.md, PLATFORM_PLAN.md, the directives file
lab/                     # separate Gradle build (LAB_SPEC.md §2): maps the five pure-JVM root projects by directory,
                         #   never evaluates the root settings; conformance/, conformance-runner, manifest, bench-core,
                         #   ledger-model, mesh-proto, mesh-router, mesh-sim
desktop/                 # separate Gradle build: node-core, node (Linux host), packaging/{linux,windows,macos}
apple/                   # Swift package: AsomJSON, AsomDSSE, AsomManifest, AsomBenchCore, asom-conformance (+ iOS targets later)
ubuntu-touch/            # Clickable project + its own JVM build (jvm/) mapping core/, lab/ and desktop/node-core by directory
.github/workflows/       # new workflow files only: lab.yml, desktop-linux.yml, desktop-windows.yml, desktop-macos.yml,
                         #   apple-ios.yml, ubuntu-touch.yml; ci.yml's two jobs untouched
```

**The lab isolation mechanism** is specified exactly in `LAB_SPEC.md` §2: project-directory mapping with redirected build directories and four isolation checks (including `buildEnvironment` with the runner's Android SDK **present**). If a builder chooses a composite build (`includeBuild("..")`) instead, the root settings must gain the one-statement guard `ASOM_PURE_JVM` given there, which is the only root-file change ever permitted for the lab, and it must be recorded as such.

**Later, at each owning version's entry, promotion with SIGN-OFF (D23)** amends brief §4:
- root modules: `:core:mesh` (pure JVM → contract), `:bench-core` (pure JVM), `:mesh-android` and `:qr` (Android), `:bench-app` only if D7 keeps the Android standalone APK;
- the edge `:core:routing → :core:mesh`;
- outside the root build (listed for transparency, MOD-3): `desktop/node-core`, `desktop/node`, `winplatform`, `macplatform`, `ut-host`.

`jvmTest` is then extended explicitly.

---

## 4. Trust, pairing and transport

### 4.1 Retained from `trust.md` (the detailed spec)

**Rules R1–R8 hold as written.**
- Identity is a pinned key, never an address.
- Only PAIRED authorises, re-read per frame.
- No network message raises trust.
- Consent comes before contact, on both devices.
- Offer, accept, then body.
- A peer serves with its local engine only.
- Peers get no data-read capability.
- Every wire attempt has rows.

**Mechanisms retained, with the changes in §4.2:**
- **Keys.** Two levels. A P-256 node identity key (NIK), hardware-backed where possible, self-signs the pinned node certificate and signs 14-day session leaves [F06].
- **TLS.** TLS 1.3 only, mutual authentication required, a custom pinned-chain verifier replacing PKIX, ALPN `asom-mesh/1`, no SNI unless S-A11 succeeds.
- **Framing.** `asom-mesh/1`: length-prefixed frames, not HTTP. Ktor CIO server TLS is not established [F13]; iOS needs Network.framework [F03].
- **Pairing.** The QR carries the key fingerprint, 1–4 private address literals, a single-use 256-bit secret, an expiry of 120 s or less and a name. Proof, SAS and transcript are as in `trust.md` §4.5, with worked vectors.
- **Registry.** States PAIRED, SUSPENDED and REVOKED; only local actions raise trust.
- **Address eligibility.** Decided by interface, not by address range.

### 4.2 Changes made in this synthesis

| # | Change | Driven by |
|---|---|---|
| T1 | **Pin canonicalisation.** `pin = SHA-256` of the exact SPKI DER bytes sliced from `TBSCertificate`, never re-encoded. Those bytes must equal the fixed 26-byte P-256 namedCurve SPKI prefix followed by `04‖X‖Y`, 91 bytes in all; reject anything else (compressed points, explicit parameters, non-minimal lengths, trailing bytes). Certificate signatures are DER ECDSA-Sig-Value; W05/W08 carry negative vectors for each | trust security critique |
| T2 | **TLS knobs with the enforcement point per stack** (S-A9 becomes a per-knob, per-stack matrix run on JDK 17, JDK 21, Conscrypt and Network.framework):<br>• The signature-scheme row is **deleted**: TLS 1.3 ECDSA schemes are curve-bound, so the verifier's P-256 check already forces it.<br>• ALPN is **checked after the handshake** (`getApplicationProtocol()`; close if it is not `asom-mesh/1`).<br>• 0-RTT is off.<br>• **Resumption (r2, closes the T9 gap):** the **client side never offers it**, which each client controls per connection whatever the server does: JSSE and Conscrypt dialers create a **fresh `SSLContext` per mesh connection**, so the client session cache is empty and no PSK can be offered; Network.framework disables resumption on the connection's TLS options [A26]. Servers disable it where the stack allows per context; where it does not (JSSE 17 tickets are a process-wide property), server-side resumption is tolerated but never exercised by a conforming client. W08 asserts no `pre_shared_key` in any ClientHello and a client CertificateVerify in every session. The matrix records each stack | trust feasibility critique; [F14]; OVERCLAIM-7 |
| T3 | **iOS chain verification** uses swift-certificates + swift-asn1, pinned, with new spike S-A10: verify the golden chain and reject every negative vector. `SecTrust` is forbidden as the verifier. The §2.4 claim "parsing uses the platform's X.509 parser" now applies to the JVM and Android only [F04] | trust feasibility critique |
| T4 | **W08 live-handshake adversarial suite** (§3.4) is a gate for any phase that ships a listener or a dialer, on every stack, in both roles | platforms + trust critiques |
| T5 | **Defaults for a freshly paired row:**<br>• `routeEnabled = false`;<br>• inbound scopes none, except `manifest` (whose content still needs the digest approval of §5.8);<br>• `infer` and `state` granted only by separate, initially unchecked boxes after `PAIR_COMMIT`: "Send my apps' requests to this device" (I borrow), "Let this device use my compute" (I lend), "Let it see my status". Pairing is symmetric (both nodes pin each other); consent is **per pair and per direction**, which is how the holonic mesh (§2.1) lets a node lend without borrowing or the reverse;<br>• optional cap: a new peer wins at most 1 in 10 placements for 24 h until observations support its claims.<br>Law **L15**: a row created by a ceremony has no `infer`/`state` scope and `routeEnabled = false` until a separate local event | trust security critique |
| T6 | **Pairing fails closed on SUSPENDED.** `EXPECT_PAIRING` and `PAIRING_SERVER` require the row to be absent (or PAIRED, for address refresh). SUSPENDED needs a local Restore that shows `statusReason`, or a Forget. Vectors and laws L1/L7 extended | trust conformance + security critiques |
| T7 | **D's approval is a typed entry.** S shows the 6-digit SAS and the user types it on D. S keeps compare-and-approve, because S already pinned D's key from the QR. In the photographed-QR race there is no legitimate code on S, so D's approval cannot complete | trust security critique |
| T8 | **S does not gate on QR expiry.** D's `PAIRING_WINDOW_CLOSED` is authoritative; S shows "window expired on the other device". This removes a cross-device clock dependency | trust feasibility critique |
| T9 | **Stolen phone.**<br>• On phones and tablets the leaf key is keyguard-bound: Android `setUnlockedDeviceRequired(true)`, API 28+, OS-enforced not hardware-enforced [F24]; iOS keychain `WhenUnlockedThisDeviceOnly` [A02], a spike alongside S-A2.<br>• Maximum session age is 30 min, forcing a fresh handshake.<br>• A "serve while locked" opt-out exists only for charging providers, and peers see it as self-reported. **r3:** on Android this is not optional for screen-off lending: a keyguard-bound TEE leaf cannot sign a handshake on a locked phone [AF17], so PA-charging requires the owner to switch "serve while locked" on for that phone, and the lending consent sheet states the cost (a thief who takes it running keeps mesh access until revoked on each node) (D16).<br>Honest limit: root or an unlocked device defeats it | trust security critique |
| T10 | **Clone signal.** Two concurrent ESTABLISHED sessions for one pin from different source addresses → SUSPEND and "possible cloned identity". It detects only concurrent use | trust security critique |
| T11 | **Revocation and locator hints are deferred.** `REVOCATION_HINT`, `LOCATOR_HINTS` and the `revoke-hint` scope are not in mesh-1. If ever built, they must adopt:<br>• hints accepted from PAIRED **or SUSPENDED** senders, since they only lower trust;<br>• a mutual-accusation rule that suspends both parties and shows a conflict sheet;<br>• a sender hinting about 2 or more targets in 24 h is itself suspended;<br>• notifications offer "Keep paused" or "Restore" only;<br>• law L14 | trust security critique (the abuse path) |
| T12 | **Dialing.**<br>• Endpoints come only from the QR, the peer's own authenticated `HELLO` (at most 4, whose port equals the listener port the peer itself declared) or the user.<br>• Every outbound TCP connect writes its own `dial` row (§8.4).<br>• Per-path dial budget, covering TCP + TLS + `HELLO`: LAN 1.5 s, overlay 3 s.<br>• Concurrent connect-only dials to candidate peers are allowed when a request arrives (quiescence rule 1).<br>• A cold session costs about 4–5 round trips before content; the router charges this (§7.4) | trust + contract critiques |
| T13 | **Listener exposure, stated honestly.**<br>• In TLS 1.3 the server sends its certificate to whoever connected, so **the listener discloses its permanent node certificate, and hence its pin, to any party that can open a TCP connection to it**.<br>• Mitigations: bind only to the overlay interface and/or user-confirmed LAN networks (never a network merely *named* like one).<br>• Spike S-A11: the client puts a pairwise hourly token `trunc64(HMAC(pairKey, epochHour))` in SNI, and the server presents its chain only on a match (JSSE `X509ExtendedKeyManager`; Conscrypt and Network.framework unverified [A09]). A passive LAN observer could replay a token within the hour; stated.<br>• Tailscale userspace-networking mode forwards inbound connections to 127.0.0.1 [F11]. It is detected and reported as "overlay mode unsupported: use a kernel TUN", and the listener **never** binds loopback.<br>• **r3, weak host model (Linux):** binding to the overlay address does not bind to the overlay interface; a LAN host that routes `100.64/10` via this machine can reach the listener and receive its certificate [LF39]. The JDK has no `SO_BINDTODEVICE` [LF25]. `asom doctor` prints an nftables rule (never applies it) and checks for it read-only; a CI network-namespace test proves the exposure and the rule's effect (C18). The IC-1 documentation line names this.<br>• **r3, Windows:** Windows Firewall blocks inbound by default; the listener never starts before a consented, elevated allow rule scoped to the interface and overlay ranges exists (C16) | contract + trust critiques |
| T14 | **Android local-network permission (r3, settled from AOSP source, re-checked per release).**<br>• At targetSdk 37, `ACCESS_LOCAL_NETWORK` gates outgoing and incoming TCP to **same-subnet LAN** peers [F05][AF05].<br>• **Loopback is never covered**: the enforcement map has no entry for `lo`, so paired apps in the same profile keep reaching `127.0.0.1:11435` at any targetSdk [AF05]. **Cross-profile loopback is blocked for all apps** on Android 17 [AF04] (a documentation note in `CLIENT_API.md`, CD-DOC3).<br>• **Overlay peers** with single-IP tun addresses (Tailscale `100.x/32`) are not local network under the r1 rules [AF05][AF23]; the 2025 `main` rule was broader [AF06], so this is re-checked each release.<br>• New state `LOCAL_NETWORK_DENIED` in the listener, pairing-window and dialing FSMs; the permission is requested before a QR is shown.<br>• **targetSdk stays 35** through mesh-1 and the lender phase unless the owner rules a bump (D29, RT-11); any bump is preceded by the emulator probe and device test H5 | platforms + trust critiques; `android-mesh.md` §4 |
| T15 | **Peer-supplied strings never flow into headers, rows or UI unvalidated.**<br>• `servedModel` must byte-equal the model id in the requester's own offer; otherwise the attempt ends as `PROTOCOL_ERROR`.<br>• The header's model is **always the requester's offered id** (v1 parity: v1's Served-By uses its own `candidate.modelId`).<br>• Ids must match `[A-Za-z0-9._:-]{1,128}`.<br>• A peer `ERROR` is reduced to its code from a closed enum; its message never reaches an app, a header or a row.<br>• Names are NFC-normalised, control and bidi characters stripped, 32 characters maximum, and always shown as "(self-reported)".<br>• `usage` is clamped to `[0, maxTokens]` and flagged if exceeded.<br>• W06 vectors | contract security critique; trust security critique |
| T16 | **Peer-body normaliser** (requester side; SIGN-OFF as D11):<br>• forward an allow-list only: `model` (rewritten to the offered concrete id), `messages`, `stream`, `max_tokens`, `max_completion_tokens`, `temperature`, `top_p`, `stop`, `seed`, and `response_format`/`tools` only if the peer's engine declares support (otherwise that peer is not a candidate);<br>• drop `user`, `metadata`, `safety_identifier`, `prompt_cache_key`, `store` and anything unknown;<br>• record the dropped field **names** in the attempt row.<br>Claim restated: "asom adds no app identity to peer traffic; the prompt text itself may still identify the app or user" | trust security critique |
| T17 | **Desktop hosting security.**<br>(a) **Dedicated-user mode**, recommended for an always-on provider where root exists (the Dell): system unit `User=asom`, `ProtectHome=yes`, `NoNewPrivileges=yes`, key under `/var/lib/asom` mode 0600.<br>(b) **`systemd --user` mode** (Deck): labelled node tier "shared-uid" (every game and browser runs as `deck` [A08]); recommended as the dev and test provider, not as the privacy-critical one.<br>(c) **Never write tokens or ledger rows to stdout or stderr.** The existing `server/Main.kt` prints the dev bearer token and every ledger row [F36], and under systemd that goes to the journal, readable by `systemd-journal`/`adm`/`wheel` members [F22]. The unit sets `StandardOutput=null`, and a test captures stdout during a request and asserts neither appears.<br>(d) **Owner CLI over a Unix socket** in `$XDG_RUNTIME_DIR` (mode 0700) with an `SO_PEERCRED` uid check **in both directions**. There is no TCP loopback app API on desktop in mesh-1, which removes port squatting; a desktop local-app API is D25(b) at M2, with its transport and the squatting limit decided there.<br>(e) `pair-confirm` and `restore` need an interactive TTY confirmation, which the socket cannot script.<br>(f) The NIK is stored outside the ledger/state directory, on a documented backup-exclusion path. A T0 key may be passphrase-wrapped and unlocked per login; "serve while logged out" is an explicit opt-in that leaves it unwrapped.<br>(g) Stated limit: on a `systemd --user` node, any same-uid process can impersonate the node.<br>(h) **r3, per OS** (§3.4): Windows identifies the CLI by the socket file's ACL only (weaker than `SO_PEERCRED`, W-D11 in D25); macOS uses `getpeereid` and, to keep same-user processes from injecting code into the entitled node process, the hardened `asom-node` launcher (spike S-M3); Ubuntu Touch has no socket at all (a private stdio pipe to its own UI) | platforms + contract security critiques; r3 platform sections |
| T18 | **Remove `locSeed`/`locKey`** from the v4.0 contract until mDNS locate is approved (D12) | trust conformance critique |
| T19 | **The `attemptId` encoding** is base64url of 16 CSPRNG bytes, 22 characters, everywhere | contract X-8 |

### 4.3 `asom-mesh/1` for mesh-1 (trimmed)

**Frames kept:**

| Group | Frames |
|---|---|
| Connection | `HELLO`, `HELLO_ACK`, `GOAWAY`, `ERROR` (r2: `PING`/`PONG` removed; liveness comes from a per-chunk read timeout on streams, TCP keepalive and the 5-min idle close). **r3:** every frame type has a ledger row rule in §8.4's table; `ERROR` carries a closed-enum `code` and never peer-authored text that reaches a row, header or UI |
| Inference | `INFER_OFFER` (no `dataClass`, no `retain` choice: always `none`), `INFER_ACCEPT`, `INFER_DECLINE`, `INFER_BODY`, `INFER_HEAD`, `INFER_CHUNK`, `INFER_END`, `CANCEL` |
| State and manifest | `STATE_REQ`, `STATE`, `MANIFEST_REQ`, `MANIFEST` |
| Pairing and revocation | `PAIR_*`, `REVOKE_NOTICE` |

**Deferred:** `REVOCATION_HINT`, `LOCATOR_HINTS`, `PLACE_REQ`/`PLACE`.

**Transport:**
- default TCP port 11436;
- limits as in `trust.md` §3.3;
- idle close after 5 min;
- maximum session age 30 min (T9).

**Peer-channel error and decline codes** (never surfaced to apps):

| Category | Codes |
|---|---|
| Authorisation and protocol | `PEER_NOT_PAIRED`, `SCOPE_DENIED`, `PROTOCOL_ERROR`, `VERSION_UNSUPPORTED`, `FRAME_TOO_LARGE`, `DUPLICATE_ATTEMPT`, `CLOCK_SKEW` |
| Admission | `MODEL_NOT_OFFERED`, `PEER_BUSY`, **`PEER_UNAVAILABLE`** |
| Manifest | `MANIFEST_UNAVAILABLE` |
| Pairing | `PAIRING_WINDOW_CLOSED`, `PAIRING_PROOF_INVALID`, `PAIRING_REFUSED` |

- **`PEER_UNAVAILABLE` replaces** `PEER_THERMAL`, `PEER_BATTERY` and `PEER_USER_ACTIVE` on the wire. It carries `retryAfterMs`. The specific reason stays in the provider's own ledger row. **It is still a presence-correlated signal:** one code for every cause hides *why*, not *that*, the provider is yielding (§7.12, IC-3(ii)).
- **`INFER_END.terminal`** is one of `done | cancelled | interrupted | oom | error`.
- **r3 frame trims (LP-1, §5.7; exact shapes in `LAB_SPEC.md` §7.2):**
  - `INFER_ACCEPT` carries no `queuePos` or `estStartMs`, and `INFER_END` carries no `usage`, `ttftMs` or `totalMs`. Those timings move with the lender's own local use, and the claim tracker ignores peer-reported counts.
  - `ERROR` carries no free-text `message`.
  - `GOAWAY` reasons are `revoked`, `suspended`, `shutdown`, `network-change`, `idle` and `max-age`; sleep, heat and presence drains all say `shutdown`.

The provider decision table is `trust.md` §7.3 plus these rows:
- **6a** predicted thermal hold → `PEER_UNAVAILABLE`;
- **6b** `estStartMs > deadlineMs` → `PEER_BUSY`;
- **8** `retain` is always `none`.

### 4.4 What trust, pairing and transport do NOT guarantee

- **Plaintext at the peer.** The serving peer sees the plaintext prompt and output. Confidentiality stops at the peer process. `retain: none` and the cache rules bind only conforming, uncompromised peers.
- **Identity.**
  - A pin proves which key, not which software, which physical device, or who operates it.
  - At T0 and T1 it does not prove the key exists on only one machine.
  - Hardware backing prevents key extraction, not use by code running on the device.
- **Pairing.** It cannot stop a user who approves without comparing codes. Typed SAS on D narrows but does not remove this.
- **Stolen devices.** A stolen, running provider keeps access until revoked on each node, bounded only by T9's keyguard binding on phones and tablets. The clone signal (T10) catches only concurrent use. T9 holds against an **unmodified** asom on a locked phone, because its client never offers resumption (T2). A rooted or modified client holding a PSK from an earlier session defeats it against any provider that tolerates resumption (JSSE 17).
- **Observers.**
  - LAN and overlay observers learn that nodes talk, when, and how much.
  - On Linux, without the printed nftables rule, a LAN host that routes the overlay range via the lender can fetch its certificate (C18).
  - The overlay operator learns the device graph.
  - Relayed overlay traffic crosses public relays, encrypted [F09].
  - The listener discloses its certificate to anyone who can connect (T13).
- **Post-quantum.** Harvest-now-decrypt-later resistance depends on each stack offering hybrid ML-KEM groups. Unverified.
- **Correctness rests on one function.** `verifyPeerChain` must be the only trust path; one accept-all trust manager defeats everything. That is why W08 exists.

---

## 5. Signed capability manifest and attestation

### 5.1 Retained from `manifest.md` (the detailed spec)

- **Rules M1–M12.** A manifest is a claim, never a fact. One record feeds many renderings. The signed bytes must be canonical. ES256 only. A key taken from the document proves nothing against a forger. Attestation tiers never gate trust. Freshness has two parts. Private and public are different objects. No new endpoint is needed. Every rejection is typed. A fixed list of fields may never appear.
- **Envelope.** A DSSE envelope with a fixed asom profile:
  - exactly one signature, as a 64-octet r‖s;
  - producers emit low-S; verifiers accept high-S;
  - a closed `payloadType` registry;
  - `keyid` is an unauthenticated hint only [F17].
- **Canonical form.** The RFC 8785 JCS integer profile. Payload bytes must equal the JCS of what they parse to, or the verifier returns `NON_CANONICAL`. Keys are sorted by UTF-16 code units [F15].
- **Verifier.** The 19-step verifier (`manifest.md` §8.2) with typed rejects. 21 of the 28 reject vectors carry a valid signature: "the signature verifies" is necessary and nowhere near sufficient.
- **Tiers and pins.** Two axes: pin (r3: PINNED for mesh peers, PINNED_BY_FINGERPRINT or SIGNER_UNVERIFIED for files, OWN; TOFU is removed, §5.4) and tier (A0 self-signed; A1 hardware key, self-reported, treated exactly as A0; A2 platform-attested). The viewer computes the verification block; a producer can never sign "verified" into its own report (LM-6).
- **Algorithm support on every signer** (checked against §3.1):
  - Android Keystore StrongBox and TEE: P-256 [F06].
  - Secure Enclave: P-256 [F06].
  - JCA file key: yes.
  - Per-export software keys: CryptoKit `P256` and JCA.
  - macOS JVM node: through the Swift helper `asom-mac-helper` (CryptoKit `SecureEnclave.P256.Signing`), pending spike S-M1; T0 file fallback (§3.4.4). This replaces r2's A03 ("a JVM cannot reach the Enclave without a signed native helper": the helper is that).
  - Windows: CNG Platform Crypto Provider (TPM) or Software KSP, ECDSA P-256, raw r‖s from `NCryptSignHash` (pending S-W1) (§3.4.3).
  - Linux: file key, optionally sealed at rest by `systemd-creds` (T1); TPM via tpm2-pkcs11 only after S-L3.
  - **Swift producers normalise to low-S** (swift-crypto emitted high-S in about half of its signatures [IS1]); a producer vector pins it (C2).
  - Ed25519 is rejected (no Secure Enclave, StrongBox or common-TPM support); ML-DSA-65 is parked for a post-quantum major version.
  - No signer on any platform needs anything other than ES256.

### 5.2 Payload shape (changed: one measurement source, re-derivable)

```jsonc
{ "schema": "asom.manifest/1", "schemaMinor": 0,
  "body": {
    "audience": "own" | "file",                    // closed enum; r2 removes the reserved "other" (D20 note only)
    "seq": 1790670240,                             // max(stored+1, epochSeconds). Monotonic only while the stored value survives
                                                   // OR the wall clock is ahead of every seq issued before; a restore that loses
                                                   // the stored value while the clock is behind can repeat values. OMITTED for "file"
    "subject": { "nodeId": "<b64url sha256(spki)>", "keyAlg": "ES256",
                 "keyStorage": "strongbox|tee|secure-enclave|tpm|os-keystore|file|ephemeral|unknown" },
    "producer": { "app", "appVersion", "harness": {...}, "engine": {"name": <closed id>, "commit", "buildFlags": [<id>]} },
    "device":   { ... as manifest.md §4.1; platformIds and os.securityPatch only for audience "own";
                  r3: os.family gains "windows" and "ubuntu-touch" (additive, schemaMinor stays 0 before any release) },
    "bench":    { <asom.bench/1, ACTIVE LANE ONLY: harness, device, memory, run, tiers[raw samples], sustain, energy>
                  // never: field[] (passive, usage-derived), custom[] (private model hashes), derived, render },
    "results":  [ <manifest.md §4.1 result rows; MUST equal project(derive(bench)) byte-for-byte after JCS> ]
  },
  "presentation": { "issuedAtMs", "expiresAtMs", "challenge": "<b64url 32B>" | null } }
  // audience "file" (r2): no seq, no challenge, no expiresAtMs; issuedAtMs truncated to 00:00 UTC of the export day,
  // so the signed bytes no longer carry the exact second of export (M06-file projection vectors)
```

**New verifier steps.** These are inserted after `manifest.md` §8.2 step 15:

| Step | Check | Reject code |
|---|---|---|
| 15a | Every asom verifier runs M04 (the `confVersion` pinned in `bench`) over `bench` and requires `results == project(derive(bench))`. It also rejects `confVersion` below the compiled-in floor or on the known-bad list. Third-party verifiers that do not implement M04 must render "derivation not re-checked by this viewer". | `DERIVATION_MISMATCH` |
| 15b | In MESH mode, `body.audience` equals the audience this requester should receive. | `AUDIENCE_MISMATCH` |
| 15c | `evidence` has at most 2 items; only the first item of each known type is evaluated; DER input is capped in size and depth. | `CONTAINER_INVALID` |

**Changes to earlier steps and to parsing:**
- **Step 11 is a hand-written typed decoder** (the §4.1 field table plus explicit bounds). JSON Schema becomes non-normative documentation and a conformance oracle.
- **String lengths are counted in code points,** with vectors at 96 and 97 astral characters.
- **Container members** other than `asomCapabilityManifest`, `dsse`, `signer` and `evidence` are never passed to consumers.
- **Physical maxima:**
  - rates are between 1 and 10⁹ milliTok/s;
  - `ttftMicros` ≤ 3.6×10⁹;
  - byte fields ≤ 2⁵⁰;
  - p50 ≥ 1 for every rate and for `steady`.
- **Arithmetic** in `consistency()` and the tracker is overflow-checked (`multiplyExact` or `multipliedReportingOverflow`); overflow → `INCONSISTENT`.
- **Constants.** `1e9` literals are replaced with integer constants.
- **The Python reference emulates checked int64,** so it can no longer hide overflow.
- **New M03 vectors:** maximum values, zero rates, a derivation mismatch, and `confVersion` below the floor.

**Why this shape.** It removes two sources of truth: producer-stored `derived` and `textSha256`, and a separately authored results block. A producer can no longer sign flattering summaries of unflattering samples (derivation integrity). **It still cannot stop fabricated samples.**

### 5.3 Which key signs what

| Object | Signer | Why |
|---|---|---|
| Mesh manifest (to a PAIRED peer) | the **NIK**, generated at first mesh enable only (never in v2) | The peer already pinned it, so nothing new is revealed. The key is bound to the session |
| Exported file (any subscriber) | a **per-export software P-256 key**, generated for the export and then discarded. `keyStorage: "ephemeral"` | A stable device key in a file is a permanent linkable fingerprint. A per-export key plus an out-of-band fingerprint comparison gives tamper-evidence without that. The NIK **never** signs an exported file |
| Standalone shells (Android APK, desktop CLI, iOS app) | per-export keys only; **no long-lived key** | They never present to the mesh. This also removes the Secure Enclave requirement from the iOS benchmark app |
| Editorial reference table (catalogue repo) | an owner **offline reference key**, compiled into `bench-core`, distinct from every node key, held in hardware (a security key) | Its entries feed router priors. It may lower priors below a class ceiling, never raise them above it (§5.7). **r3 (R2-CONFORMANCE-11):** this changes roadmap v2 P6's "read-only seed" text (RT-12, ruled with D18) and adds an owner task: generate the key offline, keep it on a hardware security key, publish its public half with the release, and re-sign the table on every change. Until the owner does that, the table is not used (fail closed) |

On Android the NIK is generated with `setAttestationChallenge("asom-nik/1")` and device-properties attestation where supported. That keeps A2 *possible later* at no cost now.

### 5.4 Verification contexts (changed)

| Context | Key source | Wording the viewer shows |
|---|---|---|
| MESH | the session's authenticated peer key (never looked up by `keyid`) | "matches the key you paired with" |
| FILE, fingerprint compared | the fingerprint the user typed or scanned from the exporting screen | "matches the fingerprint you compared" (`PINNED_BY_FINGERPRINT`; the pairing wording is **not** used) |
| FILE, not compared | `signer.spki` | "signed, but the signer is unverified: anyone could have made this key" |

**Fingerprint-comparison step (normative, r2).** Without it, a per-export signature tells the recipient nothing about who made the file: anyone can edit the file and re-sign it with a fresh key.
1. **Exporter.** After signing, the export screen shows the per-export key's fingerprint as a 26-character base32 code (the first 128 bits of SHA-256 over the SPKI DER), in groups of four or five, and as a QR code. The local export history keeps (date, file sha256, fingerprint) so the code can be shown again later; the private key itself is discarded.
2. **Recipient.** The verifier shows "signed, but the signer is unverified: anyone could have made this key" until the user scans the exporter's QR or types the code. The screen tells the user to compare over a channel the file did not travel through: in person, a phone call, or a message from an account they already trust.
3. **Result.** A match yields the context `PINNED_BY_FINGERPRINT(method: qr|typed)`. It is never stored as trust for later files, because every export has a new key.
4. **Honest equivalence.** For a one-shot file, comparing the key fingerprint gives exactly the guarantee that comparing the file's own SHA-256 out of band would. The signature adds one verifier path shared with the mesh, and the derivation checks, not extra authenticity. A signature is worth more than a digest only with a key that persists across files; that is the per-subscriber option D6(e), which makes that subscriber's reports linkable to one another.

- **TOFU continuity is removed.** Keyed by an unauthenticated `keyid`, it was illusory, and per-export keys make it moot.
- **No context auto-commits anything** without a user confirmation. The library default is display-only.
- **r3 (R2-OVERCLAIM-12): a per-export FILE match is never stored**, so it has no expiry: every export has a new key, and each file is compared on its own. The only stored FILE-context trust is a **per-subscriber key** (option D6(e)), which the subscriber pins until the exporter revokes it, with a **90-day re-confirmation**. r2's 30-day TTL for hand-shared mesh presentations belonged to D2(b), which AD-2 closed.
- **Revocation cannot be checked in FILE contexts,** and the verification block says so.

### 5.5 Attestation (A2): deferred out of the first release (a recommendation; design choice §10.0b)

Platform attestation is display-only in any own-device mesh, and the facts make a correct, durable A2 hard:
- The extension must be searched **nearest to the root**. Only the first occurrence is trustworthy; later ones may come from "an attacker extending the chain" [F07]. `manifest.md` §9.3 scanned from the leaf, which is the **critical forgery bug**.
- **RKP certificates must have their validity periods checked** [F07] and are short-lived (about 61 days [F38], medium confidence). A NIK attestation captured at key generation therefore lapses on RKP-only devices (Android 16 launches).
- **Factory chains need revocation checks** [F07].

**Recommendation (a design choice without contract effect, listed in §10.0b; the owner may overrule):**
- Ship **no A2** in the first release.
- Keep the NIK attestable.
- Rewrite `evaluateAndroid` for when A2 is built:
  1. find the certificate nearest the root that carries OID `1.3.6.1.4.1.11129.2.1.17`;
  2. it must be `certs[0]`;
  3. any extension in `certs[1..]` → `EV_EXTENSION_POSITION`;
  4. apply the provisioning-info extension rule;
  5. every non-leaf certificate must be a CA with `keyCertSign`, and the leaf must not be a CA;
  6. enforce validity for RKP-issued certificates;
  7. treat A2 as **time-bounded** ("attested, evidence valid until DATE");
  8. anchors (roots, allow-listed signing certificates, custom-OS boot keys) ship only in signed app releases, **never through the catalogue**;
  9. a revocation mirror, if any, is DSSE-signed by the owner's offline key with a monotonic version, and is labelled "checked against the asom maintainer's mirror dated X (not against Google directly)";
  10. A2 never changes router priors or decisions until the M07 vectors pass on every implementation.
- **Apple App Attest is rejected.** `attestKey` contacts Apple [F08], which would be a new egress class.

### 5.6 Freshness (restated honestly)

- **The requester's 32-byte challenge proves** the signing key was used after the request.
- **It detects replay** by a party that holds only a copied leaf key or captured presentations.
- **It does not detect** an attacker with continuing code execution on the node. Such an attacker can ask the NIK to sign, as `trust.md` §2.1 already concedes.
- **On T0/T1 nodes** it adds nothing over the leaf.
- **`NONCE_MISMATCH`** is labelled "report did not answer this request (bug or compromise)", not "strong compromise signal".
- **`seq` plus the rollback store** defend against third-party replay only, never against a lying signer.

### 5.7 The one claim-versus-observed tracker (rewritten in r3; R2-OVERCLAIM-1)

`manifest.md` §11.5 remains the single tracker (X6). r2 claimed that every input was requester-measured, but its `decodeObs` divided *tokens received* by the time *from the first to the last content chunk*: a peer controls both. It can send the first chunk at once and burst the rest at the end (denominator near zero), split or merge chunks, or report any token count; a requester that cannot hold the model has no tokenizer to check. And once `n ≥ 3`, r2 used the observed median **uncapped**, so a peer that looked fast stayed fast. r3 replaces the observation and the way it is used.

**Principle.** The tracker uses only (a) the requester's own monotonic clock at points **the requester controls or that the peer cannot move earlier**, and (b) output the requester **parsed itself**. Observation can only **lower** a claim, never raise it.

```text
key k = (peerNodeId, fileSha256)          // backend selects the claim row matching the peer's live-state engine.backend
constants (PROVISIONAL; calibrate at M1 on >= 2 devices x >= 2 backends; NEEDS-DEVICE-VALIDATION):
    WIN=20  MIN_KEEP=3  MIN_STATE=5  CORR=800  DISC=600 (permille)  RATIO_CAP=5000
    SHORT_BYTES=128                        // below this, an answer says little about speed
    bptPermille(file), bptCapPermille(file) // bytes-per-token of this file's tokenizer on the reference prompt set,
                                            // pinned in bench-core from the owner's reference CPU run (median, and
                                            // p99 x 1.25); class default 4000 / 8000 when a file has no pin

one observation per attempt that reached INFER_END (or ended after content), measured by the REQUESTER:
    tBody    = requester monotonic time when the last byte of INFER_BODY was handed to its TLS engine
               (after the intent row was durable)
    tEnd     = requester monotonic time of the LATER of: INFER_END received, last content chunk received
    elapsed  = tEnd - tBody                             // includes network, the peer's queue and its load time
    outBytes = UTF-8 byte length of the answer text the requester parsed from the chunks
               (sum of choices[0].delta.content, or message.content when not streaming);
               SSE framing, JSON whitespace, role/finish fields and every INFER_END field are NOT counted
    outTokEst = ceilDiv(outBytes * 1000, bptPermille(file))
    predicted = E1 netMs (the requester's own link estimate for this path)
              + E5 prefillMs(claim, P)                  // P = the requester's own prompt-token estimate (the offer's estTokensIn)
              + E7 decodeMs(claim, outTokEst)           // thermal-aware, from the claim row; queue and load NOT included
    ratio    = min(RATIO_CAP, predicted * 1000 / max(1, elapsed))   // > 1000: faster than claimed; checked arithmetic

discard (only on requester-observed facts; discards count toward the discard budget):
    outBytes < SHORT_BYTES                                         -> DISCARD_SHORT
    outBytes > maxTokensOffered * bptCapPermille(file) / 1000      -> DISCARD_OVERLONG and a strike (more text than the
                                                                      offer allowed: padding or a different model)
    the requester had another attempt in flight on that peer        -> DISCARD_CONCURRENT
    the settings the requester offered differ from the claim row   -> DISCARD_SETTINGS
    the attempt did not end with terminal = done                   -> DISCARD_INCOMPLETE

state from the kept ratios in window W (last WIN kept):
    n < MIN_STATE                -> UNVERIFIED
    best = upper quartile (q3) of ratios in W       // queueing and contention only make observations SLOWER, so the
                                                     // best quartile tests the claim without penalising an honest busy peer
    best >= CORR                 -> CORROBORATED
    DISC <= best < CORR          -> WEAK
    best < DISC                  -> DISCREPANT

use for placement (the estimator scales the claim row; it never exceeds the claim):
    n >= MIN_KEEP:  effRatio = min(1000, median(ratios in W))      // the median includes real queueing: that is what the
                                                                     // requester will experience next time
                    prefill and decode priors are multiplied by effRatio / 1000
    n <  MIN_KEEP:  prior' = min(prior, capRef) * disc / 1000, blended as r2 (w0 = 2) with any kept ratios
    capRef = min( signedReferenceP90(model, backend, class) * 12/10  [only if signed by the compiled-in reference key],
                  classCeiling(model, device class) )               // never "the claim itself"
    disc   = 700; 400 if >= 2 keys of this peer are DISCREPANT (lasts >= 7 days, doubling on repeat)

discard budget: once >= 4 candidate observations exist, if > 50% of the last min(20, all) were discarded:
    state WEAK ("cannot verify this device's claims") and prior' is clamped to the LOWEST ratio seen among those
    observations (discarded ones with outBytes >= 8 included), or to 0 if none qualifies
strikes per (peer, fileSha256) survive seq and backend changes; a new seq restarts W but inherits DISCREPANT until
    10 new observations have best >= CORR; a peer's new claim body is accepted for ROUTING at most once per 24 h
```

**What the peer can no longer do** (each is an M08 vector with a hand-written oracle; `LAB_SPEC.md` §6.6):
- **Burst at the end** (first chunk at once, the rest in one burst): no effect; only `tBody` and `tEnd` are used.
- **Split or merge chunks:** no effect; bytes are counted from parsed content, not chunks.
- **Report any token count or timing in `INFER_END`:** ignored.
- **Send `INFER_HEAD` late to hide its queue:** no effect; `INFER_HEAD` is not used.
- **Claim heat (`st.tb`) to lower the bar:** no effect; the claim row's thermal-aware decode is computed from the claim, not from peer state.
- **Report `qb = 1` so observations are discarded:** no effect; no discard uses peer-reported state.
- **Truncate answers to dodge observation:** the discard budget trips after at most 4 attempts and clamps the prior to the worst observed ratio.
- **Pad the answer with filler to look faster per byte:** bounded by the cap: an answer longer than `maxTokensOffered × bptCap` is discarded with a strike; below the cap, padding can inflate the apparent ratio by at most `bptCap / bptTrue` (about 2× for English text at the class defaults, PROVISIONAL). **This is the residual**: a peer willing to corrupt answers with filler can look up to about 2× faster than it is, until the owner notices garbage in the answers.

**Bound it gives, restated honestly:**
- Placement after 3 kept observations uses `min(claim, median observed)`, so **over-claiming buys nothing once observed**; before that, the claim is capped by `capRef`, discounted by `disc`, limited to 1 in 4 wins while UNVERIFIED (`router.md` §5.5) and 1 in 10 for a brand-new peer (T5).
- **Under-claiming** simply loses placements (the peer's own loss).
- An honest but busy peer is not marked DISCREPANT (the best quartile excludes its queueing), but its placements fall with its median, which is the truth the requester experiences.

**What it does not bound:**
- content fabrication (a fast peer returning text not produced by the model is not detected at all);
- the padding residual above;
- bytes-per-token variance across languages (a CJK-heavy answer has more bytes per token than the reference prompts, so the peer looks better than it is; an emoji-free English answer the opposite), which is honest variance, not an attack;
- the first observations of an UNVERIFIED peer **are real user prompts**, limited by the caps and the destination set;
- a slow peer that claims to be slow is indistinguishable from an honest slow peer.

**Probes:** user-initiated synthetic probes ("Check this device's claims") are allowed; they carry no user data. Automatic probes are not (§10.0b).

**Scope:** the tracker runs only on nodes with a router (Android, desktops, the Ubuntu Touch requester). The iOS requester has one home lender and no router, so neither R01–R06 nor M08 run there; multi-provider choice on iOS is a D17 re-escalation trigger. The Ubuntu Touch requester has no tokenizer for models it cannot run, which is why the byte-based observation is not optional (`ubuntu-touch.md` §7.7).

### 5.8 Subscriber channels

**S1 — paired asom node: mesh pull.** The mesh never pushes; the requester pulls `MANIFEST_REQ`/`MANIFEST` with a challenge.
- **When the requester pulls:**
  - it holds no verified body for the peer;
  - the peer's `st`/`STATE` shows a new **approved** `bodyDigest`;
  - the Peers tab opens.

  Never more than once per 10 min per peer unless the digest changed.
- **Serving side:**
  - The provider serves only the most recent body **whose digest the user approved for that peer** (view-first).
  - A new benchmark does not change what peers receive until the user approves it (X28).
- **Ledger:** one `manifest-sent` row per presentation (peer, audience, seq, bodyDigest, bytes, outcome, including refusals), and one `manifest-received` row on the other side.
- **History:** every sent container's bytes are kept for as long as the ledger keeps rows, and each row links to them. The history is not only the last one sent.

**S2 — any app or device: a file the user exported.** The export screen is view-first and offers two choices:
- **Signed report** (`.asom-manifest.json`, per-export key, fingerprint shown as a grouped code and a QR for the comparison step of §5.4). The screen warns that the content (model list, SoC, memory) can still narrow down the device, and that **without the comparison the signature proves nothing about who produced the file**.
- **Scope, stated (r2):** S2 is one-shot. There is no *subscription*: successive exports use unrelated keys, so a third party cannot receive ongoing, linkable updates from one device. Third-party subscription is **NOT MET in mesh-1**; D6(e) (a user-created per-subscriber key) is the option that would meet it, at the cost of linkability to that subscriber.
- **Anonymous summary**: the public derivative, unsigned, with its own plain-text rendering headed "UNSIGNED — anyone could have written this".

Rules for S2:
- Exported text **never contains a verification block**.
- No signed `.txt` is exported. A recipient renders the JSON in their own verifier.
- The file-audience projection removes `platformIds` and `securityPatch`, truncates `measuredAtMs` and run times to the day, and drops battery level, screen-on and SoC temperature conditions. r2: it also omits `seq`, `challenge` and `expiresAtMs` and truncates `presentation.issuedAtMs` to the day (§5.2), which r1 missed; M06-file vectors cover it.

**S3 — the device's own display.** Local only.

**S4 — `GET /admin/manifest`.** Deferred (D12). It would need a per-app projection without any key.

### 5.9 Public derivative (P7 upload payload; `manifest.md` §12, amended)

The derivative is an unsigned allow-list projection. It carries no key, id, seq, challenge or evidence, and is quantised to 2 significant digits with a month-granular date. In addition:
- Results whose `fileSha256` is not in the catalogue the device holds are dropped. Law LM-2 is extended to cover this.
- `engine.commit` and harness versions are emitted only if they are in a release allow-list shipped in the app; otherwise they read `custom`.
- Vendor and model map to a catalogue-backed coarse list or `other`.
- It never contains `field`, `custom`, `osBuild`, `gpuDriver` or `fingerprint`.
- The receiver's hash echo proves only that the receiver *received* these bytes, not what it keeps.

### 5.10 Plain text from one source

- **Renderer.** The viewer renders `asom.manifest-text/1` from the verified payload bytes. It has three parts:
  1. the header;
  2. a **verification block computed by the viewer**;
  3. the `asom.text/1` answers body (§6.8), rendered by the viewer from `body.bench`.
- **Producer text.** Nothing is taken from producer-supplied text. A producer renders its own report only after signing and self-verifying (LM-4).
- **Guarantees.** Byte-exact across implementations (M05). ASCII 0x20–0x7E plus LF only; any other byte in an interpolated string means the document was already rejected at parse time.

### 5.11 Tamper-evidence: what each mechanism proves and does not

| Mechanism | Proves | Does NOT prove |
|---|---|---|
| ES256 over DSSE PAE | Bytes unchanged since signing; which key signed | That the content is true; which device holds the key (without a pin) |
| Mesh pin (pairing) | The key is the one a human paired | What software used it; that the key is on one machine only (T0/T1) |
| Fingerprint compared (file) | The file came from whoever showed you that code (the same guarantee as comparing the file's SHA-256) | Anything about the device; that the measurement was honest; continuity with any other file |
| Signature on a file, **not** compared | Nothing about origin: anyone could have edited and re-signed it | Everything above |
| Canonical-bytes check | No parser-differential ambiguity | Anything about the values |
| Re-derivation (15a) | Summaries follow from the signed samples | That the samples were honestly measured, from a cool or typical device, with an unmodified harness |
| Challenge | The key was used after the request | That the measurements are new; the absence of an on-host attacker |
| `seq` / rollback store | Not older than what this subscriber accepted | Anything at first contact; anything against the signer |
| Consistency rejects | The claims are not self-contradictory | That they are true |
| Claim tracker (r3) | After 3 kept observations, placement uses the lower of the claim and what the requester itself timed end to end over bytes it parsed; chunk timing, chunk splitting, token counts and `INFER_HEAD` timing cannot improve a peer's standing | Content fabrication or padding (bounded by the byte cap, §5.7); the honesty of a slow peer that claims slowness; anything about the first 3 prompts it received; the peer's conduct with data |
| Public derivative | Transparency of what was sent | Origin, device, honesty (anyone can post any JSON) |

---

## 6. Benchmark utility (r3: MLPerf Mobile and MLPerf Client; directive D-C)

### 6.0 Why asom measures anything, given MLPerf Mobile and MLPerf Client

**The facts:**
- **MLPerf Mobile v6.0** (2026-06-15): a consumer-installable benchmark on Google Play, the Apple App Store and GitHub, Apache-2.0; LLM inference with Llama 3.2 1B/3B Instruct and Llama 3.1 8B Instruct on requests drawn from TinyMMLU/MMLU and IFEval; CPU execution; NPU-accelerated Llama 3.1 8B on Snapdragon 8 Elite Gen 5. For Dimensity 9500 and Exynos 2600 the source says "new support" and "updated support", **not** that the LLM runs on their NPUs [F40] (R2-OVERCLAIM-11). It "reports token throughput" with a configurable input-token limit and thread count [F42]. Its app is Flutter (iOS, Android, Windows) and depends on Firebase auth, storage, Crashlytics and App Check [F43].
- **MLPerf Client v1.6** (2026-04-06): a PC/laptop/workstation LLM benchmark covering **Windows, macOS, iPad and iOS**, with Windows ML, llama.cpp, and llama.cpp or MLX on Metal; GUI builds on the iOS and Mac App Stores and Steam; open source; "standardized metrics for both responsiveness and throughput" [F49]. Its current benchmark page lists a **CLI-only Ubuntu Linux 24.04** build and a model list including Llama 3.1 8B Instruct and an experimental Qwen 3 8B [LF37]; its README lists TTFT and tokens/s [FW38]; its iPad GUI requires an M2-or-newer iPad Pro with 16 GB and does not run on iPhone [IF29].

**The consequence.** A general measurement harness would be me-too **on every platform asom targets**, including desktops and Apple devices (R2-DIRECTIVES-2; directive C's desktop-coverage premise is withdrawn). asom's benchmark is therefore **not a product**. It exists for one reason MLCommons' benchmarks cannot serve: **the router needs numbers about asom's own serving path**: the llama.cpp commit, backend, build flags and model files that asom actually places requests on (C11). MLPerf Client runs llama.cpp too [F49], but not asom's pinned build, not asom's files, and not through asom's governors; its numbers cannot become priors for asom's router.

**The three differentiators, and nothing else** (directive D-C):

| # | Differentiator | Where |
|---|---|---|
| D-i | A **signed, verifiable manifest** that paired devices pull and that other apps can check from a one-shot exported file | §5 |
| D-ii | **Plain text generated from the same data**, rendered by the viewer from the verified bytes | §5.10, §6.8 |
| D-iii | **Results that feed a router:** claims become priors in `ClaimTracker` (§5.7); the node's own calibration feeds its governors and estimator (§6.7) | §5.7, §6.7 |

r2's fourth item ("desktop coverage: Linux and macOS") is **deleted**: MLPerf Client covers Windows, macOS and Ubuntu 24.04, and Apple mobile hardware on iPad Pro [F49][LF37][IF29]. Steam Deck and Ubuntu Touch are not covered by MLCommons as far as found, but coverage is not a differentiator either way (directive D-C).

**What asom deliberately does not build:**
- a standalone benchmark product on any platform (the desktop `asom-bench` CLI is dropped; the Android standalone APK is unscheduled; the iOS app benchmarks only as part of the one asom app, for the owner's devices) (D7);
- NPU or vendor-delegate paths; cross-SoC coverage; an accuracy score or leaderboard;
- a results upload or web viewer (the P7 contribution stays anonymous, opt-in and view-first, roadmap §13);
- any energy or battery methodology beyond what the governors need.

### 6.1 Comparability baseline, and the words asom may use

| Element | Adoption | Status |
|---|---|---|
| **Phone model set:** MLPerf Mobile's Llama 3.2 1B/3B Instruct and Llama 3.1 8B Instruct | the opt-in set **L1** (§6.2), licence shown first | Model set VERIFIED [F40]; licence VERIFIED [F41]; file source OPEN (D18; official repositories probably gated [A28]) |
| **Desktop model set:** MLPerf Client's Llama 3.1 8B Instruct and Qwen 3 8B (experimental) | Llama 3.1 8B is already in L1; Qwen3-8B is already in Q1. No new set; the desktop spike **S-B1d** (read `mlcommons/mlperf_client` at the v1.6 tag) pins MLPerf Client's metric formulas and prompt sets | Model list VERIFIED for the current page [LF37]; formulas UNVERIFIED [A27d] |
| **Workload prompts** | the MLPerf-defined prompt sources as the length distribution for throughput workloads, **not** for accuracy scoring | Datasets named VERIFIED [F40][F42]; subsets and caps UNVERIFIED [A27][A27d]; prompt licences ASSUMED [A30] |
| **Metric definitions** (token throughput; time to first token; input-token limit; threads) | M04 reports the MLPerf-defined metrics **alongside** llama-bench-style `pp`/`tg` [F32], once S-B1 / S-B1d pin them; until then the fields are a typed placeholder that renders nothing | VERIFIED names; formulas UNVERIFIED |
| **Accuracy validators** | not adopted as a score; asom keeps its numerics sanity check (a fast but wrong backend must not win placement) | — |
| **MLPerf apps** (Flutter + Firebase; GUI apps) | **not reusable** (Invariants 1, 7, 8) | decided by the invariants |
| **C++ task pipeline and MLPerf LoadGen** | reuse evaluated by spike **S-B2**; if adopted, a conditional dependency (CD-D) | after S-B2 |

**Wording rule (normative; replaces r2's "comparability rule").** MLCommons' results-messaging guidelines require any use of the MLPerf name with a non-reviewed result to be marked "unverified" and to say "Result not verified by MLCommons Association", forbid implying a verified or official result, and state that **MLPerf results "may not be compared against non-MLPerf results"** [F50]. asom's measurements are **not MLPerf results** (different runtime, quantisation and no MLCommons review). Therefore:
1. **The string "MLPerf-comparable" never appears** in any report, UI, manifest, doc or release note (R2-OVERCLAIM-4; law LM-9, vector M05-MLP).
2. A row may carry at most the descriptive note **"same model set and metric definitions as MLPerf Mobile v6.0 (or MLPerf Client v1.6); not an MLPerf result; not comparable with MLPerf results"**, and only when all three hold: its file is one of the pinned L1 files (or the Qwen3-8B file for the MLPerf Client note), its workload is the pinned MLPerf prompt set and caps, and its metric follows the pinned definition. **Default runs (set Q1) never carry the note**: for a default user, comparability does not exist (R2-DIRECTIVES-3).
3. **Even that note ships only after the owner's trademark check** against the MLCommons policies (D18 sub-item; K33). Until then the note is compiled in but disabled by a build flag, and reports say only "measured with the Llama 3.2 1B model" (the model's own name), never "MLPerf".
4. asom never renders an MLPerf score, never ingests MLPerf results, and never merges them into its own rows.

### 6.2 Bench sets

| Set | Contents | Role | Licence and access |
|---|---|---|---|
| **L1** (opt-in; or the first-run default if D18(a2) is chosen) | Llama 3.2 1B Instruct (Q8_0), Llama 3.2 3B Instruct (Q4_K_M), Llama 3.1 8B Instruct (Q4_K_M) as GGUF | the only rows that can carry the §6.1 note | Llama Community Licence [F41]: licence and Acceptable Use Policy shown before any download; "Built with Llama" on the about page and every report with L1 rows; file source per D18; F-Droid effect unverified [A29] |
| **Q1** (default) | Qwen3 dense T0–T5 (0.6B–32B), official Apache-2.0 GGUF repositories, pinned by sha256 and revision [F31] | the default, and the only set above 8B (14B, 32B), which desktop lenders need for the router | Apache-2.0; the owner re-verifies each sha256 by download [A17] |

- Both pins are compiled into `bench-core`; the catalogue supplies only mirror URLs (`benchSets[]`, CD-26). The host verifies each file's sha256 against the compiled-in pin.
- **Per file, `bench-core` also pins `bptPermille` and `bptCapPermille`** (bytes per token of the file's tokenizer over the reference prompts, and its cap) for the claim tracker (§5.7), computed by the owner's reference CPU run.
- **Router use.** Claims are per file. A set calibrates the node; speeds for other files the node serves are *estimated* by bytes-per-token scaling and labelled so.

### 6.3 What is measured (re-scoped in r2)

| Kept, because the router or the governors need it | Deferred, because MLPerf or others cover it, or the router does not need it yet |
|---|---|
| `load` (cold and warm); `ppP@d0` with TTFT; `tgN@dD` at depths 0 and 2048; the MLPerf-defined throughput workload (after S-B1 / S-B1d) | `tg128xK` batched decode (fan-out is deferred, D10) |
| Memory limits and peak footprint | The `battery` and `extended` plans; energy per token (the router uses the battery *band*, not joules) |
| The sustained thermal curve: peak, onset, plateau, stability. The governors (v2 P4) and the estimator's `steady` rate depend on it | Accuracy scoring of any kind |
| The numerics sanity check (teacher-forced NLL against an owner-computed CPU reference): a backend that is fast but wrong must not win placement | Cross-backend "which device is better" comparisons |

**Plans kept for the MVP:** `quick` (at most 5 min, no heat test), `standard` (at most 35 min on phones including cool-downs; charger required), and `ci`.

### 6.4 Retained from `benchmark.md` (the detailed spec, as amended)

- **One core, thin shells.**
  - The core is `bench-core`, pure JVM, with a Swift port pinned by vectors.
  - The shells, in order (r3; D7):
    - the **Android daemon's Benchmark tab (v2)**;
    - the desktop node's `asom bench` subcommand (**D-v2**, Linux, Windows and macOS alike); **the standalone `asom-bench` CLI is dropped** (MLPerf Client covers desktops [F49][LF37]);
    - the iOS asom app's Run tab (owner devices only; with the requester, placement D24);
    - the Ubuntu Touch `quick` plan only in the unscheduled UT-2 (no thermal signal under confinement);
    - the Android standalone APK `xyz.mdhv.asom.bench`: **unscheduled** (MLPerf Mobile covers the standalone use case [F40]); D7 may keep it;
    - headless lab/CI.
  - Derivation, rendering and projection are Kotlin `internal`, so no shell can fork them.
- **Statistics (M04, integer only).** A conservative median (lower for rates, upper for durations); a MAD outlier rule; a table of confidence classes with caps; the sustained phase's onset and plateau rules.
- **Governors.** A consent sheet for every run; the charger for heat tests; thermal and battery-temperature ceilings; time caps; the run yields to real requests; **nothing is ever scheduled**.
- **Downloads** go through each platform's existing, ledgered download path. The consent sheet shows the byte counts before anything downloads. Offline import by sha256 is always available.

### 6.5 Changes to `benchmark.md` (r1 changes B1–B28, with r2 changes marked)

| # | Change | Driven by |
|---|---|---|
| B1 | **Passive data (`field[]`) never leaves the device**: not in the signed document, not in exports, not to peers. The field-versus-test note appears only in a local, unsigned "LOCAL NOTES" block on the device's own screen | benchmark conformance + security critiques |
| B2 | **`custom[]` (hashes of the user's own GGUFs) never appears in any signed, exported or public output.** Custom models are not routable across the mesh in mesh-1 (live state lists catalogue models only) | benchmark security critique |
| B3 | **`derived` and `render.textSha256` are removed from the document.** Viewers re-derive with M04 and render (§5.2 step 15a). A compiled-in `confVersion` floor and a known-bad list apply | benchmark security critique |
| B4 | **String hygiene.** `engine.name`, `backend`, `kvType` and `limitSource` become ids or closed enums; `buildFlags` becomes an array of ids; `abort.reason` becomes an enum; free text uses `manifest.md`'s `text` rule. Violations are **rejected** before rendering. The renderer accepts printable ASCII 0x20–0x7E and LF only. The CLI prints peer-derived text through a filter. M05 negative vectors for LF, CR, ESC and OSC in every string field | benchmark security critique |
| B5 | **Numerics references are compiled into the bench-set pin,** keyed by (engine commit, file sha256). The verifier derives the verdict from the pinned reference; a document stating a different reference is invalid. Stated: the verdict is self-attested. It does not detect a peer serving other weights under the pinned hash, and `not-run`/`warn` shows as "output unchecked" in the Peers tab | benchmark conformance + security critiques |
| B6 | **Cancellation latency is at most one engine call.** llama.cpp's abort callback "currently works only with CPU execution" [F18].<br>• `n_ubatch` is chosen per tier so a timed call takes about 1 s or less on mobile. It is recorded in the fingerprint and `settings.batchTokens`, because it changes prefill throughput.<br>• Otherwise the measured worst-case cancel latency is recorded.<br>• Rule B6 becomes "yield within max(1 s, one engine call)".<br>• The words "all map onto existing llama.cpp calls" are removed for `cancel`.<br>• Cancel latency per backend is a device-validation item | benchmark feasibility critique |
| B7 | **Thermal drift.** M04 flags `THERMAL_DRIFT` when reps are strictly monotone in time, or first minus last exceeds 100‰. That caps confidence at low, and the first rep is never excluded as an outlier under drift. Each rep records its thermal code and headroom. On mobile, tiers slower than 10 tok/s use `tg64`, or a cool-down gate runs between headline reps. Under drift, Q1 is worded "first-minute speed". New M04 vectors | benchmark feasibility critique |
| B8 | **Time budget as an invariant.** A unit test asserts Σ(max waits + caps + estimated test time at a conservative rate floor) ≤ `wallCapMs` for every plan × form; otherwise `wallCapMs` is derived from that sum. The COOL gate before the sustain phase becomes *relative*: return to within a stated margin of the run's own start temperature and thermal code. A warm-start sustained result may tune the governor, with its start class recorded and disclosed. Spike: time-to-COOL on a charging phone [A19] | benchmark feasibility critique |
| B9 | **FSM ends cleanly with partial results.**<br>• A thermal hard ceiling during the sustain phase *ends the phase* and goes to FINALIZING.<br>• Safety aborts (battery temperature, Stop, backgrounded, charger removed, memory pressure, probe lost) go ABORTING → FINALIZING(partial) → derive with `run.abort`.<br>• M04 vectors for partial runs.<br>• iOS sustain has a soft stop at `fair` + 120 s and a hard stop at `serious` | benchmark feasibility critique |
| B10 | **Memory.**<br>• The Android ceiling is `totalMem` minus a per-form reserve, confirmed by an actual load; `availMem` is recorded as a condition only.<br>• Overhead is the engine-reported compute and output buffer sizes after context creation, replacing the fixed 300 MiB (Qwen3's vocabulary makes a 512-row logits buffer about 297 MiB on its own).<br>• `nll1024` is evaluated in chunks of 128 output positions or fewer, and that buffer counts in `fits()` | benchmark feasibility critique |
| B11 | **Contention** is measured as the spin thread's CPU time divided by its wall time, which does not depend on clock frequency. The wall-clock spin stays as a "cpu speed" diagnostic, never a gate | benchmark feasibility critique |
| B12 | **Steam Deck GPU:** other-process time comes from DRM fdinfo during a run; `gpu_busy_percent` is used only in preflight [F20] | benchmark + platforms critiques |
| B13 | **Power fields.** `plugged` and `charging` are separate fields, and `charging` is projected only from charging status or the sign of battery current. The unit check is in ppm: 800,000–1,250,000 means consistent; 800–1,250 means mA mislabelled as µA, so scale and flag `UNIT_CORRECTED` | benchmark feasibility critique |
| B14 | **Thermal signal required.** New preflight gate `NO_THERMAL_SIGNAL`: the standard, sustained and extended plans refuse to start without at least one live thermal source. Runtime watchdog `PROBE_LOST`: every source failing or going stale for 3 polls, or a battery temperature that was readable becoming unreadable, is a hard abort | benchmark security critique |
| B15 | **Consent, honestly.**<br>• The token proves the shell's code had the exact text, not that a person read it.<br>• Consent mode is recorded as sheet, tty or flag.<br>• `--yes` never downloads. Downloads need `--allow-download-bytes=N` matching the preflight total, and are refused on metered networks.<br>• Tokens are single-use.<br>• A non-TTY `--yes` for a non-`ci` plan is refused unless `--unattended` is given, and the report names it.<br>• Rule reworded: "No asom component schedules an active run; a user's own scheduler invoking the CLI is outside that guarantee" | benchmark conformance + security critiques |
| B16 | **Daemon `asom bench`** uses the owner-only Unix socket (T17d), never HTTP | benchmark security critique |
| B17 | **Q5 (mesh role)** renders "Not applicable: this version does not share work between devices" unless the shell sets `meshAvailable`. The iPad lending clause is removed until D16. A role that rests on an estimated plateau carries a basis clause | benchmark conformance critique |
| B18 | **Fixed "does not tell you" block.** The signature sentence is replaced with: "This text proves nothing by itself. It can be checked only by opening the signed report in a verifier. A valid signature shows the report is unchanged since signing and which key signed it, not that the test was honest." | benchmark conformance critique |
| B19 | **Anonymous text export** uses renderer id `asom.text-anon/1`, driven from the public derivative: coarse class, month date, no free memory, no field notes, headed "UNSIGNED". Described as "less linkable, not anonymous". **The word "unlinkable" is removed everywhere** | benchmark conformance + security critiques |
| B20 | **Consent-sheet egress sentence per shell.** Daemon: "New results are shared with your paired devices only after you approve them." Standalone shells: "Nothing is uploaded. Results stay on this device unless you export them." | benchmark security critique; X28 |
| B21 | **Every shell has a ledger.** The desktop CLI and iOS `AsomBench` get their own append-only ledgers (JSONL and Swift, same row schema, W01b vectors). One row per network attempt per mirror, including failed, partial and resumed ones. The `ci` plan fetches only catalogue URLs, or runs behind a CI-only build flag that is excluded from release artifacts | benchmark conformance critique |
| B22 | **Editorial reference table** is signed by the owner reference key (§5.3). Unsigned entries are ignored (fail closed) | benchmark security critique |
| B23 | **Test honesty.**<br>• Executor-trace vectors: a scripted fake engine and probes produce an expected ordered event log, run in both the JVM and Swift lanes.<br>• Owner-device gate: `asom-bench` pp512/tg128 against `llama-bench` at the same commit, flags and threads, within a stated tolerance.<br>• Real sustained traces from the owner's devices become M04 fixtures | benchmark feasibility critique |
| B24 | **MVP cut, revised in r2 (CONFORMANCE-7):** v2 ships the **Android daemon shell only**, with the `quick`, `standard` and `ci` plans. The desktop `asom bench` and standalone `asom-bench` CLI move to D-v2; macOS packaging goes with the macOS node (D24); the Android standalone APK is M2 and the iOS app M3. The `battery`/`extended` plans and energy methods are deferred (B31) **r3 supersedes the shell list:** the standalone desktop CLI is dropped, the Android standalone APK is unscheduled and the iOS benchmark lives inside the one asom app (§6.4, B34, D7) | benchmark feasibility critique; CONFORMANCE-7 |
| B25 | **Standalone on a device that also runs the daemon.** It never imports results. Consequence stated: routing uses only results measured by the daemon | benchmark feasibility critique |
| B26 | **`BenchEngine` lives in lab `bench-core`** until v2 P1 is designed. The JNI additions (CB1) are a v2 P1 design input (SIGN-OFF). No v1 module changes | benchmark conformance critique |
| B27 | **`benchmark.md` §10.7 (second tracker) is deleted.** The Peers tab's "observed X% of claimed" comes from `ClaimTracker`'s window | X6 |
| B28 | **Thermal sampling.** One process-wide `ThermalSampler`, shared by the governor, the situation probe and the benchmark. Headroom is read at most once every 10 s, alternating forecast 0 and 10; the thermal-status listener catches changes between reads; NaN maps to null, and an initial NaN latches "unsupported". The two sources disagree (AOSP code: NaN under 500 ms; ADPF guide: no more than once per 10 s) [F19]; the conservative rule is adopted **r3 (`android-mesh.md`):** on API 36+ use `addThermalHeadroomListener` (callbacks at most every 5 s, on changes ≥ 0.03 [AF14]); the 10 s polling rule applies to API 30–35; API 29 has status only | router feasibility critique; X16 |
| B29 | **r2: MLPerf alignment** (directive C). §6.0–§6.3 replace `benchmark.md`'s scope. Spikes S-B1 (read `mobile_app_open` at tag v6.0.1 for metric formulas, prompt subsets, caps, runtime and format) and S-B2 (LoadGen around `BenchEngine`) are v2 P6 design inputs. `benchmark.md` §4.1's rejection of the Llama family is superseded for the comparability set only (C-13) | owner directive C; X33 |
| B30 | **r2: Llama licence handling.** L1 downloads only after the licence screen; "Built with Llama" wherever L1 results appear; no L1 file is ever bundled; declining leaves Q1 fully functional | [F41]; K26 |
| B31 | **r2: deferrals.** The `battery` and `extended` plans, `tg128xK` and energy methods move out of the MVP (§6.3) | directive C (differentiate only where MLPerf does not) |
| B32 | **r2: file-audience projection** omits `seq`, `challenge` and `expiresAtMs` and truncates `issuedAtMs` to the day (§5.2) | OVERCLAIM-11 |
| B33 | **r3: wording rule** (§6.1): "MLPerf-comparable" deleted everywhere; the descriptive note ships only after the owner's trademark check and never on default (Q1) runs; law LM-9 and vector M05-MLP assert the string never renders | R2-OVERCLAIM-4; R2-DIRECTIVES-3; [F50] |
| B34 | **r3: MLPerf Client as the desktop baseline**; spike S-B1d; no standalone desktop CLI (LD-8) | R2-DIRECTIVES-2; [F49] |
| B35 | **r3: per-file `bptPermille` / `bptCapPermille` pins** in `bench-core` for the claim tracker's byte-based observation (§5.7) | R2-OVERCLAIM-1 |
| B36 | **r3: platform thermal inputs.** Windows PCs often expose no thermal source [AW06] and Ubuntu Touch clicks cannot read thermal zones or battery [UF11]: the `standard` and sustained plans refuse with `NO_THERMAL_SIGNAL` there (B14); macOS uses the four-level `thermalState` (recorded as coarse); iOS stops GPU work on resign-active (the background grace is not inference time) | platform sections |

### 6.6 Thermal protocol (retained, with B6–B9, B14, B28)

**Workload.** Continuous decode on the sustain tier in 15 s windows.

**Integer 3-window smoothing.**
- **Peak:** within the first 2 min.
- **Onset:** the first of 3 consecutive smoothed windows below 90% of peak.
- **Plateau:** the lower median of the last settled windows.
- **Stability:** plateau ÷ peak.

**End conditions:**
- plateau reached;
- time cap;
- soft ceiling + 120 s;
- phase-ending hard ceiling.

**Start classes:**
- **COOL:** relative gate (B8).
- **WARM:** caps confidence.
- **HOT:** refuses to start.

### 6.7 Active and passive lanes

- **Active lane:** synthetic, user-started, governed; MLPerf-defined metrics where the comparability rule allows. **Roadmap v2 P6 says "This is not a new subsystem"; the active lane is one**, so D7 amends that sentence as well as the "feature, not a separate app" sentence (CONFORMANCE-7).
- **Passive lane** (roadmap v2 P6 as written):
  - produced by the same completion event that builds the `RouteRecord` (Invariant 9's spirit);
  - bucketed by (model, backend, fingerprint, depth band, thermal band, power);
  - combined with an integer EWMA of α = 1/8;
  - **local only**; never signed, exported or sent to peers (B1).
- **Combination.** Active and passive results are combined only locally, for the node's own router and governors: confidence weights, half-lives of 180 days (active) and 14 days (passive), and `min` when the lanes diverge by more than 25%.
- **Governor rule** (roadmap P6 gate, "a benchmark sample provably shifts a governor threshold"): `queueHeadroomPermille = clamp(headroomAtOnset − 50, 600, 900)`. Test: onset headroom 830‰ moves the threshold from 750 to 780.

### 6.8 The plain-text report (`asom.text/1` body)

**Five questions, each with a *measured* or *estimated* label:**
1. Can this device run a 7–8B model comfortably? (10 tok/s and 2 s to first token = "comfortable"; 4 tok/s and 10 s = "usable"; owner-approved editorial constants, D18.)
2. What is the largest model it can hold?
3. How long will a 2000-token answer take?
4. Will it slow down when it gets warm?
5. What role suits it among your devices? (B17; gated on `meshAvailable`)

**Format rules:**
- ASCII only, at most 72 columns, a fixed English template.
- Speeds are floored, never rounded up.
- A throttled number always sits next to the starting number.
- **r3:** no row ever says "MLPerf-comparable". Rows from set L1 may carry the §6.1 descriptive note only when the rule's three conditions hold **and** the owner's trademark check has enabled it. A report containing L1 rows ends with "Built with Llama".
- The fixed "WHAT THIS REPORT DOES NOT TELL YOU" block (B18) is always present.

**Rendering source.** The text is rendered by the *viewer* from verified bytes (§5.10); the producer has no separate text source.

### 6.9 What the benchmark does NOT guarantee

- **Comparability.** asom's numbers are not MLPerf results and are not comparable with them [F50]. The same model and metric definitions do not mean the same runtime, quantisation, delegate or accuracy; an asom number and an MLPerf number for "Llama 3.1 8B on this phone" can differ widely, and neither is wrong. Default runs (Q1) share no model with MLPerf Mobile at all.
- **Physical properties.**
  - The governors reduce risk but do not certify safety.
  - Battery-temperature ceilings are conservative defaults [A13].
  - Heat while charging contributes to battery wear.
- **What the numbers mean.**
  - "Measured" means on this device, with these models, at this commit, under the recorded conditions.
  - The thermal curve describes one run, not the device.
  - "High confidence" means repeatable, not representative.
- **Honesty of the run.** Nothing here proves the run was honest. Derivation integrity (§5.2) proves only that the summaries follow from the signed samples.
- **Cancellation.** It is bounded by one engine call on GPU backends (B6).
- **Licence compliance.** asom shows the Llama licence and the attribution; it cannot make a user comply with the Acceptable Use Policy.

---

## 7. Mesh router intelligence

### 7.1 What "distributing work between devices" really means (tiered answer)

The evidence ranks five possible meanings (`router.md` §1):
- The best published home-cluster scheduler runs only on the strongest device whenever a model fits [F27].
- Splitting a model that fits made a single request slower (49.3 → 39.7 tok/s on three Macs).
- On Wi-Fi, per-token synchronisation latency dominates [F27].
- llama.cpp RPC is "fragile and insecure" [F28].

| Rank | Mode | Ship in | Expected benefit (estimate; assumptions in `router.md` §1) | What it does not give |
|---|---|---|---|---|
| 1 | **Whole-request placement**, including concurrent requests spread by load | **mesh-1** | Depends on the lender (table below): ~8–10× total time phone → Apple-M4-Pro-class or GPU desktop, but ~1.5–2.8× phone → Steam Deck (time to first token 0.8–4×) and ~0.9–2× phone → CPU-only desktop, all estimates [A12a–d]. At any ratio the phone's battery and heat are avoided, and models the phone cannot hold become usable | Speed-up for a model that already runs well locally; capacity beyond the largest single node |
| 2 | Fan-out of independent items (e.g. an `/v1/embeddings` array) across nodes holding the identical file | **Deferred** past mesh-1 (D10). It needs body parsing and rewriting (frozen §5.9) and a new header shape | Up to Σrates/max for identical file **and** backend; heterogeneous meshes rarely qualify | Lower latency for small requests |
| 3 | Capacity sharding (layer/pipeline) for models no node can hold | **Parked** (roadmap §7). At most an owner-gated spike on AC desktops; never phones; never llama.cpp RPC as is | 30–70B *possible* at ~1–2 tok/s | Speed |
| 4 | Cross-device speculative decoding | **Not in this roadmap.** In-node speculation is a v2 engine feature | +3–5% in the one non-dominated case; negative when the phone drafts | — |
| 5 | Prefill on one node, decode on another | **Never** on consumer Wi-Fi | Negative: 7.9–15.1 s of KV transfer for a 4k prompt | — |

**Mode 1 benefit by lender class (r3; every row is an ESTIMATE; the arithmetic is shown so each number follows from its stated basis; R2-OVERCLAIM-3).**

Workload: an 8B model (Qwen3-8B Q4_K_M, 5.03 GB file, 8.19 B parameters), a 500-token prompt, a 300-token answer, warm model, Wi-Fi RTT 10 ms (network time is under 0.1 s and ignored below). Formulas: `TTFT = 500 / prefill`; `total = TTFT + 299 / decode`; ratios are phone ÷ lender. Decode is treated as memory-bandwidth-bound (every weight byte read per token); prefill as compute-bound (≈ 2 × parameters FLOP per token).

| Lender class | Basis (assumption id) | Decode (tok/s) | Prefill (tok/s) | TTFT | Total | vs phone: total | vs phone: TTFT |
|---|---|---|---|---|---|---|---|
| Flagship phone (baseline) | roadmap §1 planning figure [A12a] | 5 | 30 | 16.7 s | 76.5 s | 1× | 1× |
| **Steam Deck, basis LO** | **[A12b-lo]** decode at 55–70% of 88–102.4 GB/s peak over 5.03 GB → 0.55 × 88 / 5.03 = **9.6**, 0.70 × 102.4 / 5.03 = **14.3**; prefill at 25–40% of 1.6 TFLOPS FP32 [LF01] over 2 × 8.19 GFLOP/token → 0.25 × 1.6e12 / 16.38e9 = **24.4**, 0.40 × 1.6e12 / 16.38e9 = **39.1** | 9.6–14.3 | 24.4–39.1 | 12.8–20.5 s | 33.7–51.6 s | **1.5–2.3×** | **0.8–1.3×** |
| **Steam Deck, basis HI** | **[A12b-hi]** scaled from one community llama-bench row on a Deck, Llama 2 7B Q4_0 under Vulkan: pp512 144.31, tg128 17.52 [LF35]. Decode: 17.52 × 3.82 GB = 66.9 GB/s effective; 66.9 / 5.03 = 13.3, minus a K-quant allowance → **10–13**. Prefill: 144.31 × 2 × 6.74e9 = 1.95 TFLOPS effective (**above** the 1.6 TFLOPS FP32 peak, so the kernels use packed FP16 or integer dot products and the FP32 basis understates prefill); 144.31 × 6.74 / 8.19 = 118.8, minus a K-quant allowance → **80–120** | 10–13 | 80–120 | 4.2–6.3 s | 27.2–36.1 s | **2.1–2.8×** | **2.7–4.0×** |
| **Steam Deck, headline** | the envelope of LO and HI | 9.6–14.3 | 24.4–120 | 4.2–20.5 s | 27.2–51.6 s | **~1.5–2.8×** | **~0.8–4×** |
| Desktop, CPU only (dual-channel DDR4/DDR5, ~40–80 GB/s) | [A12c] | 5–10 | 20–60 | 8.3–25 s | 38.2–84.8 s | **~0.9–2×** | ~0.7–2× |
| Apple M4 Pro-class, or a desktop GPU with ≥ 8 GB VRAM and ≥ ~270 GB/s | [A12d] llama.cpp's M4 Pro figures for LLaMA 7B Q4_0 (TG128 50.74, PP512 439.78) scaled to the 8B file | 38–45 | 360–440 | 1.1–1.4 s | 7.8–9.3 s | **~8–10×** | ~12–15× |

- **Why the Deck has two bases.** r2 printed 1.6–2.6× beside an FP32-compute basis that actually yields prefill of 24–39 tok/s, not 50–100 (the reviewer's arithmetic, now basis LO). The one Deck measurement found (basis HI) contradicts basis LO, because its effective prefill exceeds the FP32 peak. But basis HI is **one community submission** (LCD or OLED unknown, power limit unknown, a short run, a different model, quantisation and commit), not a measurement of the owner's Deck, and not sustained: the Deck's fan-limited sustained rate is lower by an unknown amount. Neither basis is chosen; the headline is their envelope.
- **Stated plainly: on a prefill-heavy request the Deck may be no faster than the phone** (basis LO gives a TTFT ratio as low as 0.8×).
- **The Dell** could sit anywhere from the CPU-only row to the GPU row; its OS and GPU are owner inputs (D28).
- **If the phone is faster** than [A12a] (for example on the NPU paths MLPerf Mobile measures [F40]), every ratio shrinks in proportion.
- **The gate that replaces this table** is the owner-device benchmark at D-v2: 8B decode and prefill measured on the Deck (CPU and Vulkan, 10-min sustained), the Dell and the phone (NEEDS-OWNER-VALIDATION). Until then the Peers tab and docs must not quote a speed-up.

**Normative wording** for the Peers tab and the docs: *"Each request goes to the device that can serve it best right now. Several requests can run on different devices at once. Your devices do not work together on a single answer."*

### 7.2 Retained from `router.md`

- **G1–G12.** Whole requests go to one node. Filter, then integer score, then a total order. Eligibility is set by policy, never by a claim. Claims are priors that observation replaces. Frozen v1 meanings survive. Sovereign candidates come before cloud for `auto`. The router is pure over an immutable snapshot. No randomness or ML. Offer before body, and no hedging. Every attempt reaching a wire has a row. Every decision has a reason. The live-state protocol costs nothing when nobody is asking.
- **Composition.** The v1 `Router` is called **unmodified** for the cloud tier. `local-only`, `X-Asom-Fallback` and `X-Asom-No-Train` keep their frozen meanings (RL1–RL3).
- **Estimator E0–E12 and score S1–S6.** Integer-only, in millisecond equivalents. Money is never traded against time in one sum.
- **Learning.** Deterministic reducers only: integer EWMAs, `ClaimTracker`, breakers, output-length EWMA. No bandits, no exploration with user content.
- **Failover.** The attempt state machine and the 13-row retry table (`router.md` §8.1–§8.2).
  - Retry freely before content leaves.
  - Retry after content leaves only if no byte has reached the client.
  - After that, a mid-stream loss ends the stream with a typed in-band error.
  - Accept-time re-evaluation with cancel-before-body.

### 7.3 Filters: changes

| Filter | Change |
|---|---|
| F1 eligibility | Consumes the destination set `P` (§8.5). A peer is a candidate only if `O ∈ P`. It is then re-checked against the **live** registry and app policy immediately before writing each intent row **and** immediately before `INFER_BODY`. A `Deny` → `CANCEL`, status `POLICY_CHANGED`, next candidate. Law RL4 is restated "at the instant `INFER_BODY` is sent" |
| F11 power | Uses the banded `batteryBand` and `charging` from live state |
| F12 user active | **Removed from the requester.** Only the provider decides, from its own definition. A frontmost "lend compute" screen counts as consent, not activity, so PF providers can serve (X20). The requester sees at most `PEER_UNAVAILABLE`, which is still a presence-correlated signal (§7.12) |
| F14 breaker | Transport-level failures on a peer path: cooldown capped at 30 s, followed by a half-open, offer-only probe (no content). Declines back off by `retryAfterMs` (5 s minimum, 10 min maximum) |
| F10 thermal | Uses the thermal band. SELF keeps the v2 governor matrix |

### 7.4 Live state (`asom.state/1`, minimised) and exchange

**Schema for mesh-1.** All fields are integers or enums (C1). It replaces `router.md` Appendix A where they differ.

```jsonc
{ "v": 1, "seq": 4711, "sampledAgeMs": 800,
  "availability": { "fsm": "OFF|ARMED|SERVING|DRAINING" },        // the ONLY availability field on the wire; every reason
                                                                     // (sleep, presence, heat, game, charger) stays local (r3, X36)
  "power":   { "source": "ac|battery|unknown", "charging": false, "batteryBand": "ge80|50-79|20-49|lt20|null" },
  "thermal": { "band": 0, "governor": "RUN|QUEUE|HOLD" },          // band 0 = codes 0-1, 1 = code 2, 2 = codes >= 3
  "engine":  { "backend": "<closed enum>", "commit": "<hex>", "confVersion": "1.0.0",
               "held": [ "<catalogue file sha256>", … ] },           // r2: "loaded" removed; "held" changes only on download/evict
  "queue":   { "bucket": 0 },                                        // 0 | 1 | 2 (= 2+); r2: "estStartS" removed
  "manifest": { "seq": 17, "bodyDigest": "…" } }                     // the latest APPROVED body only
```

**Removed from `router.md`'s draft (r1):**
- `user.active`, `queue.localActive`, `inflight` and `busyForMs`, all of which are presence or usage signals;
- the exact battery percentage;
- `forecastPermille`, `headroomPermille` and `metered`;
- `watch`.

**Removed in r2 (OVERCLAIM-1), because each moved with local use of the providing device:**
- `availability.reason` (`conditions` tracked games and local load; `sleeping` and `user-off` tracked the owner's actions);
- `memory.availBytes256M` (dropped when the device's own user opened anything large);
- `engine.loaded` (changed when the device's own apps loaded a model);
- `queue.estStartS` (included the device's own local requests).

The `st` digest shrinks to `{seq, fsm, tb, gov, qb}`.

**Presence hold-down (normative, r2; r3 adds the lend-screen rule).** `SERVING → DRAINING` on a presence input (screen on, input activity, a game or heavy foreground process, login) is immediate, because it protects the device's own user. The return to `SERVING` after a presence-caused drain is published **no sooner than 10 min after the last presence signal**. The requester's picture of when someone is using the lender is therefore coarse on the way back, and nothing in the schema names the cause.

**Laws (r3; vectors W07-presence, including PF cases):**
- **LP-0 (classification).** Inputs are classified `presence` or `condition`. **Presence:** screen interactive or on, input idle time, keyguard dismissal, foreground app, a game or heavy foreground process, the console user, login state, contention attributed to other processes. **Condition:** power source and charging, battery level and temperature, thermal band, memory, path, sleep-imminent. **PF exception (R2-OVERCLAIM-9):** on a node that lends only while its own lend screen is frontmost (iPad M5, Android lend screen, Deck `asom lend --foreground`, Ubuntu Touch UT-2), **the lend screen being frontmost, the screen-on state it requires and input inside the lend screen are consent, not presence.** For such a node, presence is: the lend screen leaving the foreground (or its scene resigning active, or its terminal losing focus), the screen turning off, or input outside the lend screen.
- **LP-1.** No wire field is computed from a `presence` input except (i) the accept/decline decision with its code and `retryAfterMs`, (ii) `availability.fsm`, and (iii) `queue.bucket` (a lender's queue includes its own local requests, so the queue band moves with local use; r3 adds it to the exception list instead of claiming otherwise, R2-DIRECTIVES-5). IC-3(ii) names exactly these three.
- **LP-2.** A presence signal drains at once; `fsm` returns to `SERVING` no sooner than 10 min after the last presence signal; a PF node additionally returns only after a new explicit "Start lending". A condition-caused drain (sleep, heat, unplugging) has no hold-down.
- **Stated plainly:** a change in local use **does** change the wire within one offer or one `STATE`. A paired peer that keeps asking can reconstruct a coarse timeline of when the lending device is in use. r1's "nothing about user presence goes on the wire" was false and stays withdrawn (§7.12).

**Exchange rules:**

| Source | Rule |
|---|---|
| Piggyback | A compact digest `st = {seq, fsm, tb, gov, qb}` (r2: `est` removed) rides on `HELLO_ACK`, `INFER_ACCEPT`, `INFER_DECLINE` and `INFER_END`, **only to own-class peers holding the `state` scope**. Others get nothing. Offers that end in a decline count toward the `STATE_REQ` rate budget for `st` emission |
| Pull | `STATE_REQ` is sent only when a session is established and `HELLO_ACK` carried no `st`, or when the user opens the Peers tab. Limits: at most one per 5 s per peer and 60 per hour per requester. The Peers tab shows per-peer request counts and notes unusual polling |
| Pull-before-plan | **Dropped** (it was underspecified and added unbudgeted round trips). If the top candidate's state is STALE or EXPIRED, **the `INFER_OFFER` itself is the probe**. Its `ACCEPT` or `DECLINE` carries `st`, and accept-time re-evaluation handles the result |
| Watches | **Not in mesh-1** |
| Quiescence (normative, §8.6) | A node initiates no peer connection unless one of the four conditions holds; in its lender role it initiates none except under rule 3 |

**Staleness.** FRESH, WARM, STALE and EXPIRED are measured on the requester's monotonic clock (`router.md` §3.5), with **pessimistic substitution** for STALE and probe-only handling for EXPIRED.

**Estimator changes:**
- **E1 charges the cold path** when no session is warm: dial + TLS 1.3 mutual handshake + `HELLO` ≈ 4–5 round trips, plus per-handshake CPU, before content.
- **Load cost without `engine.loaded` (r2).** The estimator charges model load time unless the requester's *own* last attempt on that peer used the same file within the peer's declared idle-unload window (requester-held data). This is pessimistic when another requester or a local app loaded the model.
- **Start delay without `estStartS` (r2).** Deadlines are enforced by the provider at offer time (decline row 6b, `PEER_BUSY`), which costs one round trip instead of a prediction.
- **E7 uses the thermal band.** `tb ≥ 1` → `min(dec, steady)`.
- **Requester's own reservations** on a peer decay by `busy × max(0, 1 − idleMs/coolDownMs)` rather than resetting after 60 s. `coolDownMs` comes from the benchmark's measured cool-down or a class default [A11].

### 7.5 Merging with the cloud tier (changed)

| Policy | Merged order |
|---|---|
| `auto` | `[SELF and usable PEERs, by S] + [cloud in v1 auto order] + [unusable PEERs, by S]` |
| `cheapest` | `[sovereign by (S2 + S3, then S)] + [cloud in v1 cheapest order]` |
| `fastest` | Cloud entries keep **v1 fastest order**. Each sovereign entry is inserted stably before the first cloud entry whose v1-EWMA-based estimate it beats. Cloud relative order never changes (X21) |
| `best-reasoning` | Merge by catalogue rank (unranked last); sovereign first on equal rank; cloud relative order unchanged |
| `local-only` | `[SELF]` (frozen) |
| any + `X-Asom-Fallback` | Cloud only, exactly v1 (frozen) |

**Rules for `auto`:**
- The **peer usability gate** applies to **PEER candidates only**: decode ≥ 4 tok/s, TTFT ≤ 20 s, total ≤ deadline. These are the router's constants, not the report's "usable" (X15).
- **SELF keeps its v2 position.** It precedes cloud unless the v2 governor matrix excludes it (X22).
- A peer may outrank SELF when it scores better by at least `peerBiasMs` (D9).
- A per-user switch, "never use the cloud when one of my devices can answer, however slowly", moves unusable PEERs ahead of cloud. It also covers own devices that are temporarily unreachable: the router waits for the half-open probe.

**`own-devices` policy:** not proposed (X13).

### 7.6 Errors and egress truth on every path

- **Errors are computed over the destination set `P`.** When no attempt serves:
  - If sovereign candidates existed, return the most specific **true** cause from existing codes:
    - `THERMAL_HOLD`, `MODEL_OOM` or `CONTEXT_OVERFLOW` (v2) when SELF was excluded for that reason;
    - `ALL_PROVIDERS_COOLING` when every candidate, peers included, is in cooldown or back-off. Peer declines enter the breaker, so this is literally true. Message: "all candidates, including your paired devices, are unavailable";
    - the v1 code computed over the app's permitted universe otherwise. A model held only on a peer that the app may not use is, for that app, unavailable, exactly as in v1.
  - `NO_ELIGIBLE_NODE` is not added.
- **Mid-stream loss after headers are committed:** the stream ends with an in-band SSE error event carrying code `MESH_STREAM_INTERRUPTED` and no `[DONE]`. This is a new *value* inside an SSE error event, not an HTTP error code (SIGN-OFF, D12).
- **Egress truth (RL15, amended; r2 fixes Invariant 9's purpose):**
  - Every record carries `reach`: the furthest class (`local < peer < cloud`) that received **content** in this request so far.
  - The request ends in exactly one **terminal row** (`terminal = true`): the outcome row of the attempt that served, or of the final failure. The echo headers are built from that record, and **its `egress` column is `reach`**, so `X-Asom-Egress` and the dashboard's Egress column show the same value for the same request.
  - The class of the attempt that actually served goes in the new column `servedClass` (null on error). Non-terminal attempt rows keep `egress` = that attempt's own class, which is the truth about where *its* bytes went.
  - This changes the meaning of frozen §5.4 `X-Asom-Egress` from "where it was served" to "the furthest class that received this request's content". On every v1 path the two coincide (v1.1 pins that with W01). It needs sign-off: CD-1m, D3.
  - A request whose body reached a peer and then failed at the cloud's plan-time check records `peer`, never `local`.
  - Metadata-only offers do not raise reach but have their own rows.
  - Vectors cover: peer body then cloud plan-time error; declines then cloud error; **W01b-reach**: peer body then SELF serves, asserting header `peer`, terminal row `egress = peer`, `servedClass = local`, and header value = row value.

### 7.7 Route reasons (changed projection)

- **Ledger and dashboard.** The ledger carries `routeReason` (code and dominant term) and `routeDetail`: the score breakdown of the winner and the runner-up, plus exclusions. The dashboard renders plain text from them (`router.md` §10.3).
- **App-facing header.** `X-Asom-Route-Reason` is roadmap v2.5's header, so apps get it only once v2.5 ships it. The projection is coarse:
  - every peer decline or failure collapses to `prev:peer-unavailable`;
  - there is never a `stale` flag, a battery or heat term, or a decline sub-code.

  Law: no header projection ever contains a `PEER_*` situation code.
- **`X-Asom-Failover`** (the v2 header) gains only `peer-unavailable` and `peer-lost` (SIGN-OFF: CD-FO, D12).
- **Residual channel.** Whether a peer served at all is still a coarse availability signal that apps can see.

### 7.8 iOS as a requester (and the other borrow-only requesters)

**Holon status, stated (R2-DIRECTIVES-4):** the iPhone requester is **not a full holon**. It cannot serve other apps (no cross-app daemon on iOS) and has no local engine serving them; each host app borrows through AsomKit or uses its own `CloudOnly` keys (if D14 part B allows). The same holds for the Ubuntu Touch requester (its own screen only) and for the D26 requester profiles. An `Embedded` tier inside AsomKit would make an iPhone serve its own host app locally; it is **not scheduled** (a D15 option, not recommended now: it would put an engine and model storage into every host app, against brief §10A's dedup intent).

- **M4 is a direct requester to one user-chosen home provider.** No relay, no delegation, no advisory placement (X1).
- **Pairing is per device, not per host app (r2; CONFORMANCE-6).** r1 paired each host app separately, which made a remote *app* a paired requester with a self-asserted name. That contradicts IC-4 ("a remote node is never an app client") and Invariant 5 (app identity only via AIDL). r2 instead:
  - gives the iPhone **one node key**, held in a keychain access group shared only by apps signed with the owner's Team ID, so iOS code signing, not a claim, decides which apps can use it [A25; spike S-A12];
  - pairs that key with the home provider as an ordinary node: the provider ledgers and revokes **the iPhone**, and never learns which app sent a request (app identities never cross the mesh, §2.6);
  - keeps the per-app opt-in on the iPhone, in AsomKit settings stored in the shared app-group container. **Stated limit:** that toggle is enforced by AsomKit code inside apps the owner signs, not by the OS;
  - leaves **third-party host apps out of M4.** Supporting them would need an Invariant 5 clause for remote app requesters whose identity is a key pinned at pairing and whose label is self-reported. That is third-amendment material, offered as D15(e), not recommended.
- **No silent downgrade to the cloud.** A move from home provider to `CloudOnly` happens only under a per-app policy the user set in advance, or with per-request consent. The response shows the tier and the reason. Otherwise a hostile Wi-Fi network could force prompts to the cloud.
- **Stated limits:**
  - AsomKit runs inside the host app, so it cannot constrain that app's other code (analytics SDKs could read content).
  - The **provider's** ledger is the authoritative record of iOS-originated traffic, per device. Per-app attribution exists only in the iPhone's own AsomKit ledger (app-group container).
- **Advisory placement frames (`PLACE_REQ`/`PLACE`) are not scheduled.** They would disclose the iPhone's pairing graph to the home provider, which `trust.md` §7.4 puts on the never-over-the-mesh list.
- **Multi-provider choice on iOS** is a KMP re-escalation trigger (D17).

### 7.9 Determinism and the frozen v1 router

`plan()` must read no clock and do no I/O. Today, though, the v1 `Router` holds live `CooldownRegistry`, `LatencyTracker`, `KeyPresence` and catalogue objects on a wall clock.

**Fix, with no v1 code change:**
- **Snapshot additions.** `MeshSnapshot` gains `wallNowMs` (logged in the event log) and a frozen cloud view: cooldown deadlines and failure streaks, latency EWMAs, the key-presence set and the catalogue digest.
- **Throwaway v1 Router.** `plan()` builds a throwaway v1 `Router` from frozen adapters, with a clock returning `snap.wallNowMs`.
- **Breaker.** The breaker is a pure reducer pinned to `CooldownRegistry` behaviour by R06 vectors: reuse by behaviour, not by object.
- **Claim scope.** RL21 ("replay reproduces every decision") is claimed only for the sovereign tier until R06 passes.
- **Replay scope.** Decision replay needs the opt-in local diagnostics trace; the ledger alone supports arithmetic re-checks only.

### 7.10 Laws (amended; full list in `router.md` §13.7)

| Law | Amendment |
|---|---|
| **RL1** | Explicit oracles:<br>• no engine and no surviving peer ⇒ exactly `Router.plan` (R04 vectors);<br>• engine present, mesh off ⇒ `[SELF] + v1 cloud plan`, with SELF removed only by the v2 governor (F10/F11);<br>• new vector: long prompt on a phone keeps SELF first |
| **RL4** | Evaluated at the instant `INFER_BODY` is sent |
| **RL8** | Excludes transitions out of EXPIRED |
| **RL9** | "Within a block, excluding cap swaps (`cap:unverified`) and the probe-only partition" |
| **RL10** | `fastest` becomes "sovereign non-decreasing S1; cloud in v1 order" |
| **RL13** | "Among candidates passing the FRESH filters; an EXPIRED candidate is probe-only and never ahead of a non-expired one" |
| **RL15b** | Error-path egress = reach |
| New | Header projection never carries a `PEER_*` code |

Each exception has a counterexample vector in R03.

### 7.11 Simulator and first slice

`router.md` §13 is retained as the eventual design, corrected:
- B4 is "per-request greedy hindsight (not optimal)".
- B2 applies the same hard filters.
- B3 is judged on a vector: p95 total time, battery ‰ used, cloud egress count.

**First slice (lab):**
- the estimator and scorer, cross-checked against `router_ref.py`;
- `plan()` with RL1–RL13 on generated snapshots;
- scenarios SC01 (phone + Mac), SC04 (peer lost mid-stream) and SC09 (device-only apps mixed in).

The full discrete-event simulator, thermal model and fan-out wait until the v2 engine exists. All output is labelled **SIMULATED — NOT DEVICE EVIDENCE**. The simulator lives in the lab build, never in `:core:routing:test`, the v1 P2 gate.

### 7.12 What the router does NOT guarantee

- **Estimates and weights.**
  - Estimates are not measurements; the first request after a change can be misplaced.
  - Score weights are value judgements.
- **Claims.**
  - `ClaimTracker` detects claims not borne out, not honesty.
  - A FRESH state from a lying peer is still a lie.
- **Behaviour under load.**
  - Fairness across requesters is cooperative.
  - Short herding is possible.
  - Content minimisation is per attempt: a retry after a lost peer means the prompt was on two devices, both eligible and both ledgered.
- **What it does not do.** No mid-stream continuity. Route reasons explain arithmetic, not the world.
- **Presence is inferable (r2, OVERCLAIM-1).** A paired requester can infer when the lending device is in use. The channels, and what r2 does about each:

| Channel | What a paired requester can infer | Minimisation |
|---|---|---|
| `INFER_DECLINE` `PEER_UNAVAILABLE` + `retryAfterMs` | the lender is yielding right now, possibly to its own user | one code for every cause; back-off 5 s–10 min; offers count toward the `STATE_REQ` budget |
| `availability.fsm` transitions | the onset of local use (immediate `DRAINING`) | 10-min hold-down before `SERVING` is published again (LP-2) |
| `queue.bucket` | concurrent load, including the lender's own apps | 3-level band; named in LP-1 and IC-3(ii) as a presence-correlated field |
| PF lend-screen transitions (iPad, Android lend screen, Deck terminal) | when the lender's owner leaves the lend screen | 10-min hold-down and a fresh "Start lending" (LP-2); one decline code |
| thermal band and governor | sustained heavy local use heats the device | 3-level band |
| `power.charging`, `batteryBand` (phone and tablet lenders, M2+) | the device was plugged in, or is draining | bands; only to peers holding `state` |
| `engine.held` | models downloaded or evicted | changes rarely; catalogue models only |
| `manifest.bodyDigest` | the user approved a new benchmark | a user action, by design |
| connection timing from the lender's own requester role | when the lender's own apps borrow | quiescence (connections only for real requests) |
| observed serving speed | contention on the lender | inherent; cannot be removed |

Nothing here is a field *named* presence, and no field is computed from presence inputs except through the decision, the FSM and the queue band (LP-1). That is the whole claim; IC-3(ii) says exactly this.

---

## 8. Contract, invariant and ledger deltas

### 8.1 Invariant impact (r3; the honest status)

| Invariant or rule | Status | Instrument |
|---|---|---|
| 1 No automatic egress | **relaxed** for paired peers (banded live state and approved manifests, never content) | **Amendment 3, IC-3** (AD-2, DECIDED; exact text below for ratification). The v2 report export (IC-6) is **proposed Amendment 4** (D6) |
| 2 Bind 127.0.0.1 only | **Amendment 2 exactly as the roadmap wrote it**; asom implements it with a stricter, normative profile (IC-1) that changes no invariant text | AD-2; IC-1 profile. Outbound-only nodes (the mesh-1 phone, iOS and Ubuntu Touch requesters) bind nothing new |
| 3 Exhaustive egress classes; every event ledgered | new class **`peer`** (IC-2); the class `contribution` for the v2 P7 upload (IC-5) | Amendment 3 (AD-2) for `peer`; **proposed Amendment 4** (D6) for `contribution`. Control-row granularity is D5 |
| 4 BYOK keys in the Android Keystore | honoured through M1 by scoping: no BYOK keys exist off Android, and none travel on the mesh | **proposed Amendment 5, IC-8** (D14) before any key-holding non-Android tier |
| 5 AIDL pairing, no HTTP registration | node identity = a pinned node key verified at the transport layer (IC-4); the owner's own local UI is not an app (IC-4(b), D25) | Amendment 3 (AD-2); IC-4(b) only if D25(a); desktop local-app identity is IC-8 (D14) |
| 6 Red/green | honoured on every platform: glyph + label everywhere (CLI, SwiftUI SF Symbols, QML glyphs); violet/cyan as secondary cues only | — |
| 7 Compose placeholder UI | honoured through M1 because **desktops ship CLI-only** (roadmap v4: "CLI shell first") | **proposed Amendment 5, IC-7** (D14) before any SwiftUI app, QML UI or desktop tray |
| 8 No GMS | honoured on Android (framework APIs only; QR via CameraX + zxing-core). Apple, Windows and Ubuntu Touch analogues are in IC-7 | proposed Amendment 5 (D14) |
| 9 One `RouteRecord` | honoured in letter and purpose: headers come from the request's terminal record, whose `egress` is `reach`, with `servedClass` separate (W01b-reach); every requester implementation (Room, desktop JSONL, Swift `AsomLedger`, Ubuntu Touch UI projection UTC03) projects header/echo and row from one record | D3 for the `reach` meaning |
| Roadmap §13 "exactly two amendments" | becomes **three** (AD-2, RT-1); **five** if D6 and D14 are recorded as recommended (RT-14) | AD-2; D6; D14 |
| Roadmap §11 stop-line | honoured | — |
| Roadmap §0 order, v4 entry criteria | revised by AD-1 (RT-8, RT-10); any further placement (M1b, parallel tracks) is RT-13 | AD-1 (decided); D24/D28 |
| Brief §3 `targetSdk 35` | unchanged unless separately ruled | D29 (RT-11) |
| CLAUDE.md no KMP | honoured (D17 settled by CLAUDE.md) | — |

### 8.2 Invariant texts: Amendment 2 as written, Amendment 3 declared, Amendments 4 and 5 proposed

**Amendment 2 (Invariant 2), exactly as roadmap §7 wrote it; not changed by this brief (AD-2):**

> *an additional listener MAY bind a private-overlay interface (Tailscale-class tailnet) or mTLS-secured LAN, OFF by default, per-device tokens required, every remote request ledgered on **both** nodes.* Never a public interface.

**IC-1 — asom's implementation profile of Amendment 2 (normative for asom; every clause is narrower than the sanctioned text, so no invariant text changes):**
1. The app-facing API server binds `127.0.0.1` only and is never reachable from another device.
2. The **peer listener** is OFF by default; only the user switches it on, and a pairing window the user opens may switch it on for at most 120 seconds.
3. It binds specific addresses only: on a private-overlay interface the user selected on this device (its overlay-assigned addresses, e.g. `100.64.0.0/10`, `fd7a:115c:a1e0::/48`), and/or on a Wi-Fi or Ethernet interface on a network the user explicitly confirmed on this device, at private or link-local addresses only (IPv4 `10/8`, `172.16/12`, `192.168/16`, `169.254/16`; IPv6 `fc00::/7`, `fe80::/10`). Eligibility is decided by the interface the user selected, never by an address range alone. Never a wildcard, loopback, a publicly routable address or a cellular interface.
4. It speaks only the peer protocol, never the app-facing API; only TLS 1.3 with mutual authentication against per-node keys pinned at an in-person pairing; and serves only peers whose registry state is PAIRED, re-checked on every frame.
5. **"Per-device tokens required"** is met by a **per-device pinned key**, which is stronger than a bearer token; that reading is ratified through Amendment 3's Invariant 5 text (IC-4). If the owner reads "tokens" literally, IC-4 is the text that departs from it, and it says so.
6. **"Every remote request ledgered on both nodes"** is made precise by IC-2 (inference attempts on both nodes; control messages per D5).
7. asom never enables, requests or depends on an overlay feature that publishes a port beyond the overlay. No cleartext beyond localhost.

**Documentation lines that must sit beside it:** the listener discloses its node certificate to anyone who can open a TCP connection to it (T13); on Linux, without the printed nftables rule, that includes LAN hosts routing the overlay range via this machine (C18); a user's own overlay configuration can publish the port (Tailscale Funnel-type features [F12]).

---

**Amendment 3 (mesh), declared explicitly (AD-2, DECIDED; the exact wording below is what the owner ratifies).** It amends Invariants 1, 3 and 5. **It relaxes Invariant 1**, and says so.

**IC-3 — Invariant 1, new paragraph (a relaxation):**

> **1 (Amendment 3: peer status, a stated relaxation).** Invariant 1's rule that nothing leaves the device automatically is relaxed for exactly one purpose. A node may transmit automatically, in the background, (a) its live state and (b) a capability-manifest body the user approved, and **only to a peer the user paired on both devices and granted the matching scope**. All of the following hold: (i) nothing is sent to any other party, and nothing is uploaded; (ii) only fields enumerated in the published peer schema are sent, in banded form, and never request or response content, keys or key presence, app identities, ledger rows, per-app usage, passive benchmark samples or custom-model hashes; no field names or encodes whether a person is using a device, and no field is computed from presence inputs except the lending node's accept-or-decline decision (with its code and retry time), its availability state and its queue band — **those do change with local use, so a paired peer can infer a coarse timeline of when the lending device is in use**; (iii) a manifest body is shared with a peer only after the user has viewed that exact body and approved sharing it with that peer (only the presentation's timestamps and challenge differ per request); (iv) every manifest sent stays viewable for as long as its ledger row exists, and the last live-state payload sent to each peer is viewable; (v) every transmission is ledgered as Invariant 3(d) specifies; (vi) **quiescence**: a node initiates no peer connection unless a local request is pending whose permitted destinations include the user's other devices, an asom screen showing peer status is open, the user started a peer operation (pairing, sharing a report, revoking), or an in-flight attempt is finishing; in its lending role it initiates none except for a peer operation the user started. **Prompt and response content is never transmitted automatically:** it moves only as part of a request that one of the user's own callers made, to a destination that caller is allowed to use.

**User-facing copy that must accompany it** (the enable-mesh screen and the about page; AD-2 requires the relaxation to be stated to users): *"When you pair your devices, they tell each other automatically whether they are free to help (busy, charging, warm) and, once you approve it, what this device can run. They never send what you type unless you or one of your apps asks another device to answer it. A paired device can tell roughly when this one is in use."*

**IC-2 — Invariant 3, new clause (d):**

> **3(d) (Amendment 3) Peer traffic (egress class `peer`).** The peer protocol to or from a PAIRED peer, carrying inference attempts this node sends or serves and the enumerated control messages (session control, live state, capability manifest, pairing, revocation notice, protocol errors). asom addresses it only to PAIRED peers at addresses eligible under Invariant 2 and never relays it onward; over an overlay, the encrypted packets may cross the public internet and the overlay operator's relays. Every outbound connection attempt is ledgered before its first packet. Every inference attempt is ledgered on each node that takes part, with an intent row made durable before request content is sent or processed. Every control message sent or received on an authenticated session is ledgered in its own row on the node that sends or receives it. Inbound connections refused before authentication are counted in rows per ten minutes. Rows record the exact application bytes they cover; transport overhead (TLS records, handshakes, alerts) is recorded once per session, measured where the TLS implementation exposes it and otherwise estimated by a published formula, and labelled which.

*If D5(a) is chosen, the control sentence reads instead:* "Control messages are ledgered in per-session interval rows written at least hourly, at session close and before each live-state request, so up to one hour of control-message byte counts can be lost if the process dies." (OVERCLAIM-6, r2.)

**Why the byte wording changed (R2-OVERCLAIM-6).** r2 hard-coded "bytes are counted at the TLS record layer", which requires ciphertext record sizes before each send. JSSE and Conscrypt `SSLEngine` expose them; Network.framework does not (its framer sits above TLS; `DataTransferReport` gives transport totals only after the fact [IF14]). r3 counts what every stack can know before sending (application bytes) and moves the overhead to one per-session figure with an honest label (§8.4).

**IC-4 — Invariant 5, as amended:**

> **5.** Pairing identity for apps is AIDL-verified via `Binder.getCallingUid()`. No HTTP registration endpoint exists. **(Amendment 3)** Node identity between the user's devices is a node key pinned at pairing and verified at the transport layer on every connection; addresses and names are never identity. No network message can create, restore or raise a pairing, except the pairing messages exchanged inside a pairing window the user opened on the listening node (a single-use secret of at most 120 seconds, shown as a QR code and bound to both nodes' keys), and then only with the user's confirmation on both devices. A remote node is never an app client: it cannot reach the app-facing API, and it never acts under an app's pairing.
> **(b) (Amendment 3, only if the owner rules D25(a))** A node's own user interface is not an app client and holds no pairing token: the owner's command-line tool on a desktop, reached over a local socket whose caller the operating system identifies (peer credentials, or on Windows only the socket's access-control list), and an app's own screen driving the requester built into that same app. Its ledger rows name the local identity and how it was checked (`local-uid:`, `local-sid:…(acl)`, `self-ui:`).

**Proposed roadmap §13 replacement text (RT-1; AD-2):**

> v1 §1 applies to every version. **Three amendments exist in this roadmap; no session may introduce another without owner escalation.** 1. Invariant 1 at v2 (benchmark contribution), as written above. 2. Invariant 2 at v4 (private-overlay listener), as written in §7. 3. **Invariants 1, 3 and 5 at mesh-1 (the mesh; owner acting decision AD-2, 2026-09-30).** It **relaxes** Invariant 1: banded device status and user-approved capability manifests may be sent automatically, but only to explicitly paired devices, and prompt or response content is never sent automatically. It adds the egress class `peer` to Invariant 3 (not `lan`: overlay traffic may be relayed). It makes node identity a pinned node key verified at the transport layer; app identity on a device stays Binder-verified. Exact text: `docs/design/mesh/ASOM_MESH_DESIGN.md` §8.2.

If the owner records D6 and D14 as recommended, the same section gains items 4 and 5 and its first sentence says "Five" (RT-14).

---

**Proposed Amendment 4 (benchmark sharing, v2) — D6.** Roadmap §13's Amendment 1 amends Invariant 1 only and admits "exactly one more" user export (the P7 upload). The P7 upload is a network event with no Invariant 3 class (inconsistency I-2), and a report export is a second new export action. Under AD-2's principle (no amendment is stretched over changes it did not name; R2-CONFORMANCE-2, R2-OVERCLAIM-2), both are recorded as a new amendment:

> **IC-5 — 3(e) Benchmark contribution (egress class `contribution`).** A single HTTPS POST of the exact payload the user viewed, to `BENCHMARK_SINK_URL`, only on the user's tap.
> **IC-6 — Invariant 1, export instance.** …(ii)(b) user-directed export of the user's own benchmark report, either as an anonymous summary or as a report signed with a key generated for that export alone; the export screen shows the exact bytes first, states what the content can reveal, and states that a recipient can trust the signature only after comparing its fingerprint with this screen.

A share-sheet export is not a network event by asom, so IC-6 needs no Invariant 3 class.

**Proposed Amendment 5 (platform equivalence) — D14.** Directives D-A and D-E make this text necessary; they do not approve it.
- **IC-7 (Invariants 7 and 8; part A, before the first SwiftUI app, QML UI or desktop tray):** "UI is placeholder-functional, native to each platform (Compose/Material3 on Android; SwiftUI on Apple; QML with Lomiri.Components on Ubuntu Touch; a system-tray placeholder on Windows and macOS), wired against the token-contract seam. No platform-vendor cloud services are used: no Google Play services or Firebase; no CloudKit, iCloud sync, App Attest, DeviceCheck or Apple server-side model (`PrivateCloudComputeLanguageModel`); no Windows ML execution-provider downloads; no third-party analytics or crash SDKs. App data is excluded from cloud backup and device transfer on every platform. OS-level diagnostics outside asom's control (Windows Error Reporting, Apple crash sharing, TestFlight crash reports) are disclosed."
- **IC-8 (Invariants 4 and 5; part B, before the first key-holding non-Android tier or the desktop local-app API):** "BYOK keys are wrapped by the platform keystore (Android Keystore; Apple Keychain `ThisDeviceOnly`, non-synchronising; Windows CNG/DPAPI user scope; Linux: a sealed or passphrase-wrapped file, stated weaker), entered only in that platform's asom Keys screen, never accepted or returned by any API, never in logs or the ledger. Local-app identity on a non-Android node is established only by the operating system (Linux `SO_PEERCRED`, the macOS XPC audit token, Windows only as the socket ACL, stated weaker); where no OS-verified caller identity exists (iOS, Ubuntu Touch), there is no cross-app service." Whether Windows' ACL-only identity is acceptable for local **apps** (not only the owner's CLI) is the open sub-question of D14.

**Not proposed:** delegated routing (D15(c)); other-owner peers (outside the stop-line); a remote app identity for third-party iOS host apps (D15(e)).

### 8.3 Contract delta registry (r3; every item maps to a decision)

**Rule (unchanged).** Every row names the decision that rules it. **An item whose decision the owner has not ruled is not approved.** AD-* rows are decided by the owner's delegation and stand unless overruled. D23 collects items no other decision covers.

**Complete count (the owner brief repeats this line verbatim; R2-DIRECTIVES-10):**
6 new app-facing values and 1 restated header meaning (`X-Asom-Egress` = reach); 1 new HTTP error code landing in v1.1 that changes v1 cloud behaviour (`LEDGER_UNAVAILABLE`); 2 changes to §5.9 body handling; 1 new SDK tier (`RemoteMesh`) and 3 changes to the pinned `CLIENT_API.md`; 2 ledger egress classes, 20 ledger columns, an intent status (status 0), new export keys and 4 new `callerPkg` forms; 1 local control channel (the desktop owner socket); 3 desktop local-app items at M2 (transport, `asom pair-app` token minting, self-reported app label); a whole new peer plane (a protocol and port, 3 scopes, peer-channel codes, a live-state schema, manifest formats with 3 renderer ids and a public derivative); 1 catalogue field; 7 invariant texts plus 1 conditional clause, touching Invariants 1, 3, 4, 5, 7 and 8 (Invariant 2's text unchanged); 14 roadmap or brief text changes (including the targetSdk pin); up to 5 new root modules (4 while the Android standalone benchmark APK stays unscheduled) and 1 new dependency edge; 8 new shipped third-party dependencies, 4 build-time tools and 1 conditional dependency; 6 new artefact identifiers (one of them unscheduled); 2 Android manifest permission additions (`CAMERA` at M1; `ACCESS_LOCAL_NETWORK` only if D29 bumps targetSdk); 1 normative conformance artefact.

Values that arrive only with v2.5's `X-Asom-Route-Reason` are listed (CD-RR) but not counted.

**App-facing HTTP (frozen §5; additive values, one meaning change):**

| ID | Item | Frozen text touched | Phase | Decision |
|---|---|---|---|---|
| CD-1 | `X-Asom-Egress` value `peer` | §5.4 | M1 | AD-2 (the class; DECIDED) |
| CD-1m | `X-Asom-Egress` **meaning** restated: "the furthest class that received this request's content" (`reach`); coincides with v1 on every v1 path; the terminal row's `egress` carries the same value | §5.4, §9 | **M1 only** (r3 re-phases it: no v1.1 or v2 path can touch two content classes, so nothing earlier needs it; R2-CONFORMANCE-7) | D3 |
| CD-2 | `X-Asom-Served-By` value form `peer:<alias>/<model>`; `alias` = per-app pseudonym `base32(trunc80(HMAC(localAliasKey, appPairingId ‖ pin)))`; `model` = the requester's offered id | §5.4 | M1 | **D12.1** |
| CD-4 | `/v1/models` entries `owned_by: "asom-peer"` (one constant; only for apps whose mesh toggle is on) | §5.2 | M1 | **D12.2** |
| CD-6b | SSE in-band error value `MESH_STREAM_INTERRUPTED` (no new HTTP code) | §5.2 SSE | M1 | **D12.3** |
| CD-FO | `X-Asom-Failover` values `peer-unavailable`, `peer-lost` | roadmap v2 delta | M1 | **D12.4** |
| CD-RR | Mesh values for `X-Asom-Route-Reason` (coarse projection), only once v2.5 ships the header | roadmap v2.5 | ≥ v2.5 | D12.4 (with v2.5) |
| CD-LU | Error `LEDGER_UNAVAILABLE` (503): grows the frozen §5.6 enum, changes v1 cloud behaviour, changes roadmap v1.1's locked delta | §5.6; roadmap §3 | v1.1 | D5 |
| CD-BH1 | The router **reads** `max_tokens`/`max_completion_tokens` (read-only) | §5.9 | M1 | D11 |
| CD-BH2 | Peer-body allow-list normaliser (T16); provider pass-through unchanged | §5.9 | M1 | D11 |
| — | **Withdrawn / not proposed:** `X-Asom-Node`; the `own-devices` policy; `NO_ELIGIBLE_NODE`; new request headers, endpoints or AIDL methods; discovery-provider mesh fields; `GET /admin/manifest` (deferred); `locSeed`/`locKey` | — | — | **D12.6** |

**SDK contract (`docs/CLIENT_API.md`, pinned as contract in brief P6):**

| ID | Item | Phase | Decision |
|---|---|---|---|
| CD-DOC1 | `egress` documented as an open set: "treat any value other than `local` as content having left this device" | M1 (may land earlier) | D3 |
| CD-DOC2 | The `peer:` form of the served-by provider explained | M1 | **D12.5** |
| CD-DOC3 | "Same profile only": on Android 17, apps in a work profile or Private Space cannot reach asom's loopback API [AF04] (they could not pair anyway: AIDL binds are per profile) | v1.1 (documentation of an OS fact) | D23 |
| CD-10A | A new 10A tier **`RemoteMesh`** on iOS (a fourth `InferenceClient` implementation); the tier list and `FallbackResolver` order change; a Swift appendix | M4 | D15 |

**Ledger (brief §9; additive nullable columns):**

| ID | Item | Phase | Decision |
|---|---|---|---|
| CD-12 | `Egress.PEER` (Invariant 3(d)) | M1 | AD-2 (DECIDED) |
| CD-13 | `Egress.CONTRIBUTION` (Invariant 3(e)) | v2 | D6 |
| CD-14 | 7 columns: `requestId` (local only), `attemptId`, `phase`, `attemptIndex`, `reach`, `terminal`, `servedClass` | v1.1 (`requestId`, `attemptId`, `phase`, `attemptIndex` for cloud H1) / **M1** (`reach`, `terminal`, `servedClass`) | D5; `reach`/`terminal`/`servedClass` also D3 |
| CD-15 | 13 columns: `peerNode`, `peerAlias`, `peerPath`, `meshKind`, `bytesIn`, `meshCode`, `destAddr` + `addrSource` (dial rows), `routeReason`, `routeDetail`, `droppedFields`, `overheadBytes` + **`overheadBasis`** (`measured` \| `estimated`, r3) (session rows) | M1 | D5 |
| CD-16 | Status `0` means intent | v1.1 | D5 |
| CD-17 | Export is the same JSON array with new keys | v1.1 | D5 |
| CD-19 | `callerPkg` value forms: `peer:<nodeTag>` (lender-served rows), `local-uid:<user>` (Linux/macOS owner CLI), `local-sid:<sid>(acl)` (Windows owner CLI), `self-ui:<artefact id>` (Ubuntu Touch app, iOS host apps' own AsomKit ledgers) | M1 / M1b | D25 |

**Local control surfaces:**

| ID | Item | Phase | Decision |
|---|---|---|---|
| CD-24 | Desktop owner control channel: a local socket, length-prefixed JSON, closed command enum; identity by `SO_PEERCRED` (Linux), `getpeereid` (macOS), socket ACL only (Windows), checked in both directions where the OS allows | D-v2 | D25 + D23 |
| — | **Listed, not counted (internal, not reachable by other processes):** Ubuntu Touch `asom-ut-ctl/1` (stdio between the app's UI and its node); macOS helper protocol v1 (inherited pipes between the node and `asom-mac-helper`) | — | D23 (transparency) |
| CD-28 | Desktop **local-app** transport: either the frozen HTTP API on `127.0.0.1:11435` (port squatting by a same-user process stated) or HTTP over a Unix socket (a contract addition) (R2-CONFORMANCE-5) | M2 | D25(b) + D14 part B |
| CD-29 | `asom pair-app`: a TTY-confirmed token-minting path for desktop local apps, in place of §5.7 AIDL pairing | M2 | D25(b) + D14 part B |
| CD-30 | A self-reported app label in desktop local-app rows (identity is token + OS-checked caller; the label is not identity) | M2 | D25(b) + D14 part B |

**Peer plane (new surface; exists because of Amendment 3):**

| ID | Item | Phase | Decision |
|---|---|---|---|
| CD-18 | `asom-mesh/1` (§4.3; no `PING`/`PONG`), port 11436 | M1 | AD-2 (schemas ratified with §8.2) |
| CD-20 | Scopes `infer`, `state`, `manifest`, per pair and per direction | M1 | AD-2 |
| CD-21 | Peer-channel error and decline codes (never surfaced to apps) | M1 | AD-2 |
| CD-22 | `asom.state/1` (§7.4) and the `st` digest | M1 | AD-2 (IC-3) |
| CD-23 | Manifest container; payload type `application/vnd.asom.manifest.v1+json`; schema `asom.manifest/1`; public derivative `asom.bench-public/1`; renderers `asom.manifest-text/1`, `asom.text/1`, `asom.text-anon/1` | v2 (file, public) / M1 (frames) | D6 (file, public); AD-2 (frames) |

**Catalogue:**

| ID | Item | Phase | Decision |
|---|---|---|---|
| CD-26 | `benchSets[]` (mirror URLs only; pins compiled in) for Q1 and, if approved, L1; changes roadmap §9's v2 field list | v2 | D18 |
| CD-27 | Attestation block in the catalogue: **rejected** for anchors | — | §10.0b |

**Invariant texts (§8.2):**

| ID | Invariant | Instrument | Decision |
|---|---|---|---|
| IC-1 | 2 | implementation profile of Amendment 2 (no text change) | AD-2 |
| IC-2 | 3(d) | Amendment 3 | AD-2 (D5 selects the control sentence) |
| IC-3 | 1 (relaxation) | Amendment 3 | AD-2 |
| IC-4 | 5 | Amendment 3 | AD-2 |
| IC-4(b) | 5 (local UI is not an app) | Amendment 3, conditional | D25 |
| IC-5 | 3(e) | proposed Amendment 4 | D6 |
| IC-6 | 1 (export) | proposed Amendment 4 | D6 |
| IC-7 | 7, 8 | proposed Amendment 5, part A | D14 |
| IC-8 | 4, 5 | proposed Amendment 5, part B | D14 (and D25 for desktops) |

**Roadmap and brief text (14 rows):**

| ID | Text changed | Decision |
|---|---|---|
| RT-1 | Roadmap §7 "No other invariant changes" and §13 "exactly two amendments" → three (§8.2 text) | AD-2 (DECIDED) |
| RT-2 | Roadmap v2 P6 "the benchmark app is a *feature*, not a separate app" and "This is not a new subsystem" | D7 |
| RT-3 | Roadmap v1.1 contract delta "error `QUOTA_EXHAUSTED` only", and v1.1 scope (H1–H7) | D5 |
| RT-4 | Roadmap §9 catalogue v2 fields (`benchSets[]`) | D18 |
| RT-5 | Roadmap v2 P1's listed JNI surface (`prefill`, batched `decodeGreedy`, `truncateKv`, `clearKv`, `nllMicroNats`, `info`; a `BenchEngine` interface in `:core:inference-api`) | D23 |
| RT-6 | Roadmap §7/§8 v4 delta: `X-Asom-Node` withdrawn; egress `lan` becomes `peer`; §7 "Device pairing … per-device tokens" becomes pinned node keys | AD-2 (class, keys); D12.6 (withdrawal) |
| RT-7 | Roadmap platform scope (silent on Apple, Windows, Ubuntu Touch) | D-A, D-E (DECIDED; text already in roadmap §14) |
| RT-8 | Roadmap §2 ladder: mesh-1 before v2.5 and v3; the v2.5 cloud-ban column pulled forward | AD-1 (DECIDED) |
| RT-9 | Brief §0 "a sovereign model-routing daemon for Android" | D-A, D-E for scope; D14 for invariant consequences |
| RT-10 | Roadmap §2 v4 entry criteria: "design session held" satisfied by this brief; "v3 shipped" → "v2 shipped"; "tailnet exists" → "at least one Linux/Deck node commissioned" (R2-CONFORMANCE-3) | AD-1 (DECIDED; roadmap §14 item 5) |
| RT-11 | Brief §3 `targetSdk 35`: any bump (R2-CONFORMANCE-10) | D29 |
| RT-12 | Roadmap v2 P6 editorial reference table: signed by an owner offline key compiled into `bench-core`; unsigned entries ignored; used to cap peer claims; owner task: key custody (R2-CONFORMANCE-11) | D18 |
| RT-13 | Roadmap ladder insertions beyond AD-1: M1b placements (iOS, Ubuntu Touch, a desktop OS not in M1) and any parallel track (R2-CONFORMANCE-8) | D24, D28 |
| RT-14 | Roadmap §13 count beyond three, if D6 and D14 are recorded as Amendments 4 and 5 | D6, D14 |

**Dependency law (brief §4), dependencies and artefacts:**

| ID | Item | Phase | Decision |
|---|---|---|---|
| MOD-1 | New root modules: `:core:mesh`, `:bench-core`, `:mesh-android`, `:qr`, and `:bench-app` **only if D7 keeps the Android standalone APK** (5 counted with `:bench-app`; `:bench-cli` dropped, D7) | per phase | D23 |
| MOD-2 | New edge `:core:routing → :core:mesh` | M1 | D23 |
| MOD-3 | Modules in separate builds (listed, not in brief §4): `lab/*`, `desktop/node-core`, `desktop/node`, `winplatform`, `macplatform`, `ut-host`, `apple/*` targets | per phase | D23 (transparency) |
| CD-D | **Shipped third-party dependencies (8):** zxing-core 3.5.4 (Android, Ubuntu Touch); AndroidX CameraX (Android; R2-OVERCLAIM-10); swift-certificates and swift-asn1 (iOS, M4); swift-crypto incl. `_CryptoExtras` (Linux Swift lane now; the iOS app from M4 through swift-certificates); JNA 5.19.x (Windows); Apache Commons Daemon procrun (Windows service mode); a bundled jlinked Temurin 21 runtime (desktops, Ubuntu Touch). **Build-time tools (4):** nFPM, WiX v6/v7, XcodeGen, Clickable images. **Conditional (1):** MLPerf LoadGen, only if spike S-B2 adopts it | per phase | D23 (JNA also D27; CameraX also D23 via AN-5) |
| CD-C | `conformance/` as a normative, versioned artefact; stays in `lab/` until promotion | promotion | D23 |
| AP-1 | Android manifest permission additions (SIGN-OFF lines, not API items): `CAMERA` (runtime, asked only on "Scan", with `uses-feature … required=false`) at M1; `ACCESS_NETWORK_STATE` and `WAKE_LOCK` declared explicitly (already in the merged manifest through WorkManager [AF38], so no new merged permission); `ACCESS_LOCAL_NETWORK` only if D29 bumps targetSdk; never `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, location, `NEARBY_WIFI_DEVICES` or any GMS/Firebase artifact | M1 / lender | D23 (D29 for the last) |
| AF-1 | Android standalone benchmark APK `xyz.mdhv.asom.bench` — **unscheduled** unless D7 keeps it | — | D7 |
| AF-2 | Desktop Linux artefacts `asom-desktop` (tarball, deb, rpm) | D-v2 | D23 |
| AF-3 | Windows MSI and the winget identifier `asystemofcells.asom` | D-v2/M1b | D23 + D22 (signing) |
| AF-4 | macOS pkg/dmg, bundle id `xyz.mdhv.asom.desktop`, Homebrew tap `asystemofcells/homebrew-asom` | D-v2/M1b | D23 + D22 (Team ID) |
| AF-5 | The iOS app, bundle id `xyz.mdhv.asom` (a new platform artefact under the anchor, not a rename) | M4 | D24 + D22 |
| AF-6 | The Ubuntu Touch click `xyz.mdhv.asom.ut` | UT-1 | D23 |

### 8.4 Ledger design (normative; `contract.md` §4 with these changes)

**Principles:**
- **P1** Every attempt that puts bytes on a wire has its own rows on every node that handled content.
- **P2** A stated class is true.
- **P3** Content never moves before its intent row is durable.
- **P4** Rows are never modified.
- **P5** Rows never contain content or keys. IP addresses appear **only** in `DIAL` rows.
- **P6** Nodes link rows only by random ids.
- **P7** The request's terminal row and the echo headers carry the same egress value.
- **P8 (r3)** Rows count **exact application bytes**; transport overhead is a separate, labelled per-session figure.

**Row schema (r3):**

```kotlin
enum class Egress(val wire: String) { LOCAL("local"), CLOUD("cloud"), CATALOGUE("catalogue"), DOWNLOAD("download"),
    PEER("peer") /* CD-12, AD-2 */, CONTRIBUTION("contribution") /* CD-13, D6 */ }
enum class Phase { INTENT, OUTCOME }
enum class MeshKind { INFER_SENT, INFER_SERVED, DIAL, SESSION, CONTROL, INBOUND_REFUSED, MANIFEST_SENT, MANIFEST_RECEIVED,
    PAIRING, REVOCATION }
enum class OverheadBasis { MEASURED, ESTIMATED }                     // r3 (P8)
data class RouteRecord(
    /* the 13 v1 fields, unchanged. On the TERMINAL row, `egress` = reach (P7, CD-1m) */
    val requestId: String? = null,    // 16 CSPRNG bytes, b64url; LOCAL ONLY (no frame carries it: law L-L7)
    val attemptId: String? = null,    // 16 CSPRNG bytes, b64url (22 chars); on the wire for peer attempts only
    val phase: Phase? = null, val attemptIndex: Int? = null,
    val reach: Egress? = null,        // furthest class that received CONTENT in this request so far (local < peer < cloud)
    val terminal: Boolean? = null,    // true on exactly one row per request: the one the echo headers come from
    val servedClass: Egress? = null,  // class of the attempt that served (null on error)
    val peerNode: String? = null,     // nodeTag of the other node (ledger only)
    val peerAlias: String? = null,    // per-app alias used by X-Asom-Served-By
    val peerPath: PeerPath? = null,   // LAN | OVERLAY, from the connected socket's local interface
    val meshKind: MeshKind? = null, val bytesIn: Long? = null, val meshCode: String? = null,
    val destAddr: String? = null, val addrSource: String? = null,       // DIAL rows only: "qr" | "hello" | "user"
    val routeReason: String? = null, val routeDetail: String? = null, val droppedFields: List<String>? = null,
    val overheadBytes: Long? = null,  // SESSION close rows (and DIAL outcome / SESSION open for the handshake): see below
    val overheadBasis: OverheadBasis? = null,
)
// v1 fields bytesOut/bytesIn on mesh rows = APPLICATION bytes: frame header (9 bytes) + payload, per direction.
// callerPkg forms (CD-19): verified package (Android v1) | "peer:<nodeTag>" (lender-served rows)
//   | "local-uid:<user>" (Linux/macOS owner CLI) | "local-sid:<sid>(acl)" (Windows owner CLI) | "self-ui:<artefact>" (UT app, iOS)
// tokensOut on a requester's peer-attempt row = the requester's own estimate from the bytes it parsed (§5.7 outTokEst);
//   the peer's INFER_END usage is never written into a row.
```

Intent rows carry no bytes, tokens or cost; sums over all rows are correct totals, and the export documentation says a raw row count double-counts.

**Byte accounting (r3; R2-OVERCLAIM-6).**
1. **Application bytes, exact, known before the send.** Each frame's size is `9 + payload length` (4-byte length, 1-byte type, 4-byte stream id). Every row records the application bytes of the frames it covers, per direction, and is made durable before the send (P3).
2. **Transport overhead, per session, labelled.** TLS record headers, AEAD tags, handshakes, alerts and key updates are recorded as `overheadBytes`:
   - **`MEASURED`** on JSSE and Conscrypt (`SSLEngine.wrap`/`unwrap` report the exact network bytes; desktops, Android, Ubuntu Touch): the handshake bytes go on the initiator's `DIAL` outcome row and on the listener's `SESSION` open row; the rest on the `SESSION` close row;
   - **`MEASURED`** on Network.framework **at session close only**: `overheadBytes = DataTransferReport.sentTransportByteCount + received − Σ application bytes` [IF14][IA07];
   - **`ESTIMATED`** wherever neither is available (a crash before close; an unknown stack): `22 × ⌈appBytes_flush / 16384⌉` per flush (TLS 1.3 record: 5-byte header + 1-byte inner content type + 16-byte AEAD tag, plaintext ≤ 2¹⁴ bytes [F51]) plus a handshake estimate of 4,096 bytes per direction [A36], labelled estimated.
3. **What this does not claim:** that estimated overhead is exact; that TCP/IP headers, retransmissions or the overlay's own encapsulation are counted (they are not, on any stack); that a crash leaves the overhead figure (on Apple stacks it is lost; on JVM stacks the handshake part survives in `DIAL`/`SESSION` rows).

**Which frame produces which rows (every `asom-mesh/1` frame of §4.3; R2-OVERCLAIM-7, R2-DIRECTIVES-7).** "Durable before" means the row is committed (fsync) before the frame's first byte is handed to the TLS engine, or before any reply to a received frame is sent.

| Frame or event | Row on the sending node | Row on the receiving node | Covered by L-L16? |
|---|---|---|---|
| Outbound TCP connect (any purpose) | `DIAL` intent durable **before the SYN**; `DIAL` outcome (`connected`, `refused`, `timeout`, `pin-mismatch`, `not-tls`, `local-network-denied`, `firewall-blocked`) with `destAddr`, `addrSource`, handshake overhead | none (nothing authenticated yet) | no (attempt-level: L-L14) |
| Inbound connection authenticated | — | `SESSION` open (mode `established` or `pairing`), handshake overhead | no |
| Inbound connection refused before authentication | — | counted in one `INBOUND_REFUSED` row per 10 min (no addresses) | no (not a session) |
| `HELLO`, `HELLO_ACK` | one `CONTROL` row per frame, durable before send | one `CONTROL` row per frame, durable before any reply | **yes** |
| `STATE_REQ`, `STATE` | `CONTROL` per frame | `CONTROL` per frame | **yes** |
| `MANIFEST_REQ` | `CONTROL` | `CONTROL` | **yes** |
| `MANIFEST` | `MANIFEST_SENT`, linked to the stored container bytes | `MANIFEST_RECEIVED` | **yes** (its row kind is the manifest kind) |
| `GOAWAY` | `CONTROL` (reason code) | `CONTROL` | **yes**, except the ledger-failure close below, which sends no `GOAWAY` |
| `ERROR` (connection-level or not tied to an attempt) | `CONTROL` (code from the closed enum only) | `CONTROL` | **yes** |
| `REVOKE_NOTICE` | `REVOCATION` | `REVOCATION` | **yes** |
| `PAIR_HELLO`, `PAIR_CHALLENGE`, `PAIR_DECISION`, `PAIR_COMMIT`, `PAIR_COMMIT_ACK` | one `PAIRING` row **per frame** (r2 said "one row each side", which undercounted) | one `PAIRING` row per frame | **yes** |
| `INFER_OFFER`, `INFER_ACCEPT`, `INFER_DECLINE`, `INFER_BODY`, `INFER_HEAD`, `INFER_CHUNK`, `INFER_END`, `CANCEL`, and an `ERROR` carrying an `attemptId` | **attempt rows**, not per-frame rows: requester intent durable before `INFER_OFFER`, outcome after the last frame; per-direction application bytes of every frame of the attempt are summed into the outcome | lender: no intent for a decline (outcome only, with the decline code); for a served attempt, intent after `INFER_BODY` arrives and before the engine reads it, outcome before `INFER_END` is sent | no: covered by L-L1…L-L5b and L-L13/L-L14 (attempt laws) |
| Piggybacked `st` digests inside `HELLO_ACK`, `INFER_ACCEPT`, `INFER_DECLINE`, `INFER_END` | counted in the carrying frame's row | same | via the carrier |
| An ignorable extension frame (types 0x80–0xFF) received | — | `CONTROL` row `EXT_IGNORED` (mesh-1 senders never send one) | **yes** |
| Session close (either side) | `SESSION` close with `overheadBytes` + basis | same | no |
| Cloud attempt (v1.1 H1) | intent before `driver.chat`, outcome after | — | no |
| *D5(a) only* | `CONTROL` interval rows hourly, at close and before each `STATE_REQ`, instead of per-frame control rows | mirror image | law replaced by the interval law |

**Fail-closed rules (r3 fixes FC-2):**

| Point | Rule |
|---|---|
| **FC-1** requester intent append fails | Send no bytes, try no further peer or cloud candidate, return `LEDGER_UNAVAILABLE` (CD-LU). Covers disk full, `SQLITE_FULL`, I/O errors, read-only filesystems |
| **FC-2** control-row append fails (r3) | Send **nothing further** on that session (no `GOAWAY`: its row would need the write that just failed), close the TLS connection (`close_notify`) and the socket, and mark the node's ledger **unavailable**: every further intent fails (FC-1) until a write succeeds. The `SESSION` close row is attempted; if it also fails, the join tool reports the session as "closed by ledger failure". Stated in IC-2's scope: the one control message that can go unledgered is none, because none is sent |
| **FC-4** lender intent append fails | Decline with `PEER_UNAVAILABLE` (that decline's own row is the next write; if it fails too, apply FC-2); the engine never starts |
| **FC-5** lender outcome append fails | `CANCEL` the stream; send no `INFER_END`; then FC-2 |
| Android sink | The `runCatching` degrade path stays for *outcome* rows of cloud requests only; intent and control rows propagate failure |

**Durability claim:** a committed row survives **process death** (Room WAL with fsync per commit [F30], never `SYNC_MODE_NORMAL`; JSONL with `FileChannel.force` or `fsync` per append on desktops, Ubuntu Touch and Swift). Power loss is not claimed.

**Laws.** L-L1 to L-L12 from `contract.md` §4.12, plus:

| Law | Statement |
|---|---|
| **L-L5** | Echo headers equal `reach` |
| **L-L5b** | Exactly one row per request has `terminal = true`; its `egress` equals `X-Asom-Egress`; `servedClass` equals the class of the attempt that served (vector W01b-reach) |
| **L-L13** | With a sink that throws at each durability point, zero content bytes reach any socket after the failure, and (r3) zero frames of any kind are sent on that session after a control-row failure |
| **L-L14** | Every TCP connect has a durable `DIAL` intent before its SYN |
| **L-L15 (r3, per stack)** | Per session, Σ application bytes over the session's rows **plus** `overheadBytes` equals: on JSSE/Conscrypt, the bytes a test-only record tap saw on the socket (`MEASURED`); on Network.framework, the `DataTransferReport` transport byte count (IA07). Rows with `ESTIMATED` overhead are excluded from the equality and counted separately; a lane whose sessions are all `ESTIMATED` fails the law (non-vacuity) |
| **L-L16 (r3, named frame list)** | Under D5(b), every frame in the rows of the table above marked "yes" has exactly one row of the stated kind on each node that sent or received it; every attempt frame is covered by its attempt's rows; nothing else is sent. The law reports its per-frame-type iteration counts and fails if any listed type was never exercised |

**Honest tests.** L-L11 is a model-level law with a crash-injecting fake. Real durability: a forked JVM killed with SIGKILL at each step (JSONL sink); an Android instrumented `kill -9` of the FGS during a blocking mock upstream, then a Room query; the same SIGKILL harness for the Swift `AsomLedger` on macOS and Linux runners.

**Export.** Per node, user-triggered and view-first, as in v1. Joining two nodes' exports on `(requester nodeTag, lender nodeTag, attemptId)` rebuilds the cross-device account; collisions are flagged by the join tool.

**Does not guarantee:** a node's rows are its own account, not proof of what the other node did; rows are not tamper-evident; "outcome unknown" is honest but uninformative; a ledger swept up by the user's own backup tool is an egress asom cannot see; after a crash the `SESSION` close row (and the unattributed overhead) is missing and the join tool says so.

### 8.5 Data classification: permitted destination sets

`P ⊆ {T this device, O own peer, C cloud}`. r2 removes `X` (other-owner peer) from the normative lattice and its vectors; it survives only as a note in D20 (CONFORMANCE-11).

**How `P` is computed.** Start from all three destinations and intersect with every applicable rule. The most restrictive rule wins (`contract.md` §5.2 table, minus its `X` rows).

**What the lender is told.** Never `P` or its name; it receives only `retain: none`.

**Defaults (changed from `contract.md`, X24):**
- **Global "use my devices"** is off.
- **Existing apps.** When the user turns it on, the consent screen lists every paired app with an **unticked** box.
- **Apps paired later** default to mesh **off**. The AIDL pairing consent sheet gains a line naming the peers, so enabling them is an explicit choice.
- **Cloud-banned apps** (the column exists in mesh-1: AD-1 pulled it forward) stay off unless set individually.
- **Consent copy:** "The text of each request goes to that device and is processed there. asom on that device does not save it; fragments may remain in that device's memory or swap, and a device not running unmodified asom could keep it."

**Decision-table vectors:** `authz/classification.json`.

### 8.6 The quiescence law (normative; Amendment 3, IC-3(vi))

A node initiates a peer connection only when one of these holds:
1. a local request is pending whose `P` contains `O`;
2. an asom screen showing peer status is open;
3. the user started a peer operation (pairing, sharing a report, revoking); or
4. an in-flight attempt is finishing.

**Limits:**
- **In its lending role, a node initiates no peer connection except under rule 3** (r2, CONFORMANCE-12: r1 said "providers initiate none", which contradicted rule 3 for a user revoking a peer or sharing a report on a lender). A lender accepts connections only while SERVING, or while a pairing window or the Peers tab is open.
- Because every node can play both roles, a desktop's own borrowing (rule 1, from its CLI or local apps) is a requester-role connection, allowed like the phone's.
- Sessions close after 5 min idle and after 30 min of age.

**Consequence.** A node with the mesh on and no local caller using it sends nothing. The M1 gate tests this on the **phone**, across all interfaces (§9.3, gate 5).

**Per platform, the requester's own lifecycle makes quiescence stricter, never looser:** the iOS requester dials only while its host app is foreground (plus the best-effort grace to finish a stream); the Ubuntu Touch requester dials only while its app is focused and reported `active` within the last 10 s (law L-UT1); both open no listener.

**Does not guarantee.** The overlay's own background traffic, and its log upload [F10], belong to the overlay software, not to asom. They are disclosed (D8).

---

## 9. Phased plan with gates (AD-1 order)

**Gate discipline** (brief §11–§12; AD-5):
- Real command output is pasted into `PROGRESS.md` for every gate. If blocked, write `BLOCKED(<reason>)` and stop.
- `NEEDS-DEVICE-VALIDATION` and `NEEDS-OWNER-VALIDATION` stay open until the owner confirms.
- Evidence labels are part of the record and never dropped: **LAB**, **SIMULATED — NOT DEVICE EVIDENCE**, **CI (hosted VM) evidence**, **EMULATOR EVIDENCE**, **CI-APPROX — NOT DEVICE EVIDENCE** (the Ubuntu Touch AppArmor approximation), **SIMULATOR** (iOS).
- **Order (AD-1, DECIDED):** V1-close → v1.1 → v2 → D-v2 → M1 (→ M1b if D24/D28 place it there) → v2.5 → v3 → M2+. Only the ship-nothing work of §9.1 precedes V1-close.

### 9.1 What runs now (ships nothing; authorised by AD-3, AD-4 and roadmap §14 item 7)

**The rules these items are exceptions to, and the instrument that authorises each:**

| Rule | Text | Authorised by |
|---|---|---|
| CLAUDE.md | "do NOT start those versions" (v1.1→v4) | AD-4 (lab), roadmap §14 item 7 (scaffolds) |
| Roadmap §0 | "do not start a version until [its entry criteria] are met … strictly sequential" | same |
| Roadmap v2 entry | "v1.1 shipped; catalogue v2 fields live" | same (the benchmark maths and manifest code ship nothing) |
| Roadmap v4 | "design session required before execution … Do not cold-execute" | this brief is the design session (§0.6); the scaffolds bind no listener and have no engine |
| Directive D-D | v1 validation first | nothing here ships or changes a shipped module |

**Isolation rules (all builds under this section):**

| # | Rule |
|---|---|
| 1 | Each of `lab/`, `desktop/`, `ubuntu-touch/jvm/` is a **separate Gradle build** that maps the pure-JVM root projects by directory and **never evaluates the root settings** (`LAB_SPEC.md` §2). `apple/` is a SwiftPM package |
| 2 | The root `settings.gradle.kts`, `build.gradle.kts`, `jvmTest`, `core/`, `server/` and `.github/workflows/ci.yml` are unchanged; each new workflow checks this with `git diff --exit-code` |
| 3 | New CI lives in **new workflow files** (`lab.yml`, `desktop-linux.yml`, `desktop-windows.yml`, `desktop-macos.yml`, `apple-ios.yml`, `ubuntu-touch.yml`); pure-JVM jobs run with `ANDROID_HOME=""` and `ANDROID_SDK_ROOT=""`, plus one isolation check **with** the SDK present |
| 4 | Conformance data lives in `lab/conformance/` until promotion |
| 5 | Nothing binds anything but `127.0.0.1`, and only inside tests; the desktop scaffolds bind **nothing** (no listener, `NoopEngine`) |
| 6 | The lab never reuses frozen names (`LabEgress`, not `Egress.PEER`) |
| 7 | Promotion into shipped modules happens only at the owning version's entry, citing the decision record (D23) |
| 8 | Release artefacts are never produced; CI artefacts are labelled `UNSIGNED — not for release` |

**Work items (gates in `LAB_SPEC.md` §8 and `PLATFORM_PLAN.md`):**

| # | Deliverable | Contents (short) | Gate (real output in `PROGRESS.md`) |
|---|---|---|---|
| L0.1 | `lab/conformance/` + `lab/conformance-runner` + `tools/xcheck.py` | W00–W03 and **W01b** pinning frozen v1 by exercising the **real `:server`** on `127.0.0.1:0` with `FakeDriver`; R04 pinning the v1 `Router`; `INDEX.json`, `VERSION`, TEST-ONLY deny-list | `./gradlew -p lab labTest` on JDK 17 and 21 with no Android SDK; the four isolation checks; root `./gradlew jvmTest --rerun-tasks` with the same test count as the last v1 baseline; `xcheck.py` agreeing on every vector it covers |
| L0.2 | `lab/manifest` | strict JSON tokenizer; JCS; DSSE/ES256; the verifier with every typed reject + 15a–15c; typed decoder; audience projections incl. M06-file; the fingerprint step; public derivative; renderers | M01–M03, M05, M06 green on JDK 17/21; vectors stay **self-oracled** until an independent author agrees (§3.5) |
| L0.3 | `lab/bench-core` (engine-free) | M04 incl. drift and partial runs; the M05 body; projection; run-plan interpreter; governor FSM against fakes; executor-trace vectors; budget invariant; consent token; Q1 pins, and **L1 pins only as a D18-conditional table** (R2-DIRECTIVES-9); MLPerf metric mapping as a typed placeholder | M04/M05/projection/executor-trace vectors green; a vector proving the "MLPerf-comparable" string never renders (M05-MLP) |
| L0.4 | `lab/ledger-model` + `lab/mesh-policy` | destination sets; eligibility; lender decision table; quiescence predicate; `RouteRecord` v2 (lab types); intent/outcome machine; **per-frame control rows per §8.4's table**; application-byte accounting + overhead basis; laws L-L1…L-L16; crash-injecting sink; forked-JVM SIGKILL harness | authz and ledger vectors; W01b-reach; L-L15 with a record tap; L-L16 with per-frame-type iteration counts. **Built to D5(b), the recommendation, ahead of the D5 ruling**; the D5(a) variant is one class (stated) |
| L0.5 | `lab/mesh-proto` | frame codec; strict SPKI pin; certificate templates; `verifyPeerChain`; registry FSM; pairing proof/SAS/transcript; fresh `SSLContext` per dial; **W08 hostile-node suite over loopback, test-only**; W07-presence incl. PF; S-A9 matrix on JDK 17/21; S-A11 on JSSE | W04–W08 green; S-A9 matrix recorded |
| L0.6 | `lab/mesh-router` + `lab/mesh-sim` | estimator and scorer (r3 inputs); `plan()`; pure breaker (R06); **`ClaimTracker` (r3, §5.7) with M08 vectors incl. burst-at-end, split-chunk, padding and truncation adversaries**; RL1–RL22 on generated snapshots; the simulator with SC01, SC04, SC06, SC09 | R01–R06 and M08 green; zero law violations; every law reports non-zero iterations; labelled SIMULATED |
| L0.7 | `apple/` Swift lane | `AsomJSON`, `AsomDSSE`, `AsomManifest`, `AsomBenchCore`, `asom-conformance` | `swift test` on Linux (swift-crypto) and `macos-latest` (CryptoKit); `lane-diff` against the JVM runner empty for M01–M03, M05, M06 |
| DL0–DL3 | `desktop/` Linux skeleton, probes and governor, host integration, packaging | `NoopEngine`; **no listener**; units shipped disabled | `PLATFORM_PLAN.md` §3 gates (CI (hosted VM) evidence) |
| W0–W2 | `desktop/packaging/windows` lab lane, adapter skeleton, key tiers | fakes + real-API integration tests on `windows-2025` | `PLATFORM_PLAN.md` §4 |
| MC1–MC2 | `desktop/packaging/macos` adapter skeleton, helper + protocol vectors (`macos.md`'s MC0 is L0.7) | fakes; helper tests; power-assertion test | `PLATFORM_PLAN.md` §5 |
| UT-0 | `ubuntu-touch/` scaffold | the JVM self-test in the real arm64 Ubuntu Touch userland; click build and review | `PLATFORM_PLAN.md` §7 |

**Owner tasks that can happen now (no code):** answer §10; the inventory (the Dell's OS **and GPU**; whether an Apple-silicon Mac exists; which Ubuntu Touch device, if any; Headscale or not); download bench set Q1 and confirm each sha256 [A17]; choose the L1 file source (D18); run the on-device spike S-UT1 if a Ubuntu Touch device exists.

### 9.2 v1.1 additions (after V1-close; roadmap v1.1 entry criteria unchanged)

| # | Item | Why | Gate |
|---|---|---|---|
| H1 | Intent rows for cloud attempts (`requestId`, `attemptId`, `phase`, status 0) and fail-closed intent writes (`LEDGER_UNAVAILABLE`). **Changes roadmap v1.1's locked delta and v1 cloud behaviour** (RT-3, CD-LU; D5) | I-4: a process death mid-upstream loses the row today [F36] | JVM test with a driver that transmits then blocks; forked-JVM SIGKILL harness; Android `kill -9` of the FGS during a slow upstream (NEEDS-DEVICE-VALIDATION) |
| H2 | `ContractFreezeTest` pins the exact `Egress` set and the `AsomHeaders` names | I-3 | test output |
| H3 | No secret or ledger row on stdout/stderr in any shipped desktop entry point | journal exposure [F22] | stdout-capture test |
| H4 | Android `dataExtractionRules` excluding every domain from backup and device transfer | [F23] | manifest lint + a QA_V1-style check |
| H5 | The loopback confirmation: a CI-only `sample-client` flavour at targetSdk 37 calls `127.0.0.1:11435` on the API 37 emulator (EMULATOR EVIDENCE), then the device test DV-A2; a managed-profile case confirms the cross-profile block | [AF04][AF05] (source-read answer: loopback is not covered in the same profile) | emulator output + NEEDS-DEVICE-VALIDATION |
| H6 | `CLIENT_API.md` "same profile only" note (CD-DOC3) | [AF04] | doc diff |
| H7 | Room `exportSchema = true` and the ledger migration groundwork for CD-14 | M1 needs migration tests | `MigrationTestHelper` on the API 35 emulator |

### 9.3 Must wait (in AD-1 order)

**Every v4 phase below (D-v2, M1, M1b, M2+) carries the entry criterion "v4 design session held and recorded"** = this r3 brief committed under `docs/design/mesh/` with AD-1…AD-6 recorded in roadmap §14 (R2-CONFORMANCE-3). Roadmap §2's v4 row is replaced by RT-10.

| Phase | Scope | Entry criteria | Gate |
|---|---|---|---|
| **V1-close** | Existing: `QA_V1.md`, P5–P7 checklists, the audit re-run, the P6 RemoteAsom re-run, the P4 real-key smoke | owner hardware | as in `PROGRESS.md` |
| **v1.1** | Roadmap v1.1 + H1–H7 | roadmap v1.1 entry; **D5** (incl. RT-3); D23 (CD-DOC3) | §9.2 |
| **v2** | Roadmap v2, plus: P6 with `bench-core` promoted, the **Android daemon shell only**; set Q1, and L1 if D18 approves; plain text; a per-export signed report with the fingerprint step and an anonymous summary (if D6); P7 with class `contribution` (if D6) | roadmap v2 entry; **D6, D7, D18, D23** (CB1/RT-5). **D3 is not needed here**: CD-1m moved to M1 (R2-CONFORMANCE-7) | roadmap gates, plus: M-family and bench vectors on JDK 17/21 and ART; an exported report verifies in the independent checker, and an uncompared file renders "signer unverified"; the P7 builder cannot emit a key, content, fingerprint, `field`, `custom` or `osBuild` field; the wording vectors (M05-MLP); llama-bench parity on the phone (B23); cancel latency per backend measured (B6) |
| **D-v2** desktop engine port (roadmap v4's asom-desktop) | `desktop/node-core` + host layers; native build matrix (Linux x86_64/aarch64 CPU + Vulkan; Windows x64 CPU + Vulkan, arm64 CPU + OpenCL; macOS arm64 Metal + CPU); jlinked Temurin 21 runtime; packaging (tarball, deb/rpm; MSI; pkg/dmg) per D28; model manager with `download` rows and sha256; RAM guard; single-flight queue and cancellation; governors per §3.2; JSONL ledger with `force`; the owner CLI over the local socket (CD-24); `asom bench` inside the node | v2 shipped on Android; **v4 design session held and recorded**; **D23** (CD-24, MOD-3, CD-D); **D25** (the CLI caller is not an app, IC-4(b)); **D27** (platform engineering package); **D28** (which OSes are lending targets) | `PLATFORM_PLAN.md` gates per OS; **owner-device 8B decode and prefill on the Deck (CPU and Vulkan, 10-min sustained), the Dell and the phone, replacing §7.1's estimates** (NEEDS-OWNER-VALIDATION); llama-bench parity on each desktop (B23) |
| **M1 mesh-1** | The symmetric protocol. **Lenders:** the Dell and the Deck (Linux), plus Windows or macOS **only if D28 places them in M1**. **Borrowers:** the Android daemon and each desktop node's owner CLI. QR pairing (CameraX + zxing-core, or paste); scopes per pair and direction; banded state; digest-approved manifests; append-only ledger on both sides with per-frame control rows (D5(b)); class `peer`; CD-1, **CD-1m**, CD-2, CD-4; quiescence; **the per-app cloud-ban column (AD-1)** | D-v2; **v4 design session held and recorded**; Amendment 3 ratified (AD-2 stands); **D3, D5, D8, D9, D11, D12.1–D12.6, D19, D29** ruled; at least one Linux/Deck node commissioned (RT-10) | **(1)** root `jvmTest` + `labTest` + promoted module tests. **(2)** All conformance families on JDK 17/21, the packaged runtimes and the ART emulator lane (W01, W05, **W08 on Conscrypt**). **(3)** The CI multi-node suite in Linux network namespaces (2–3 desktop node processes, each able to borrow and lend; faults: forced decline, lender killed mid-stream, revoke then request, listener closed during DRAINING, policy toggled mid-request, clock-skewed state), row counts asserted on every node incl. L-L15 and L-L16, and the **weak-host test** (C18). **(4)** RedMagic → Deck and Dell, a ≥ 20-request script, both ledgers exported and joined; the phone shows no listening socket beyond `127.0.0.1:11435` (NEEDS-DEVICE-VALIDATION). **(5) Quiescence (r3; R2-OVERCLAIM-5).** Setup: mesh on, one app opted in, `state` granted both ways, Peers tab closed. **With the overlay active, the gate passes only with a root on-device capture on the phone** (all interfaces incl. the tun, 30 min: zero SYNs to any paired-peer address on any port, zero new `DIAL`/`CONTROL` rows; positive control: opening the Peers tab produces a `DIAL` row and a SYN within 5 s). **Without root, the gate may pass only in a LAN-direct-only configuration (overlay off)** with capture at the Wi-Fi gateway and the same positive control observed at the gateway; otherwise it **stays NEEDS-DEVICE-VALIDATION** and is never recorded as passed by a weaker method (PCAPdroid's non-root mode is a VPN and cannot run beside the overlay). Repeat on one desktop borrower (`tcpdump -i any`, CLI idle). **(6)** Lender idle 30 min on AC, then a request: success, or failover within the dial budget with a correct `DIAL` row. **(7)** A revoked phone cannot reconnect or un-revoke itself. **(8)** fsync cost of the per-attempt and per-control rows, measured on the RedMagic. **(9)** targetSdk still 35 (D29). **(10)** A lender rebooted to its login screen accepts no offer unless "serve while logged out" is on (NEEDS-OWNER-VALIDATION). **(11)** W01b-reach on a real exported ledger. **(12)** Symmetry: the Deck's CLI borrows from the Dell and the Dell's from the Deck, both ledgers joined. **(13) If Windows lends in M1** (D28): W08 on the Windows JDK; S-W1/S-W2 outcomes recorded; the firewall-consent and Tailscale-unattended checks; gate 5 on a Windows borrower by pktmon on the Wintun adapter, or left open. **(14) If macOS lends in M1** (D28; R2-DIRECTIVES-11): W08 on the macOS JDK; S-M1 and S-M3 outcomes recorded; the Local Network check for the agent (first LAN dial shows the prompt; denial writes a `local-network-denied` DIAL row); gate 6 on a Mac mini on AC; gate 10 (trivially true in agent mode); gate 5 by a root capture on the Mac covering every `en*` and `utun*` interface, or left open |
| **M1b** (placement open: D24, D28; RT-13) | Requesters on the same protocol: the **iOS requester** (M4: the asom app hosting bench, pairing and verifier; AsomKit `RemoteMesh` in the owner's other apps; device-level pairing through a Team-ID keychain access group); the **Ubuntu Touch requester** (UT-1); Windows or macOS lenders not placed in M1 | M1; **v4 design session held and recorded**; D14 part A (SwiftUI, QML); D15; D24; spikes S-A2, S-A9 (Network.framework column), S-A10, S-A12 on a device (iOS); S-UT1 on a device (Ubuntu Touch) | iOS: an iPhone app streams from the Deck or Dell; the lender's ledger shows the iPhone node and no app identity; lender off → fallback only per pre-set policy or consent; W08 client role in the simulator; device items DV-I8…DV-I12. Ubuntu Touch: UT1.1–UT1.3 (`PLATFORM_PLAN.md` §7) incl. the M1 netns suite with a UT-profile requester |
| **v2.5** | Roadmap v2.5, minus the cloud-ban column already shipped | roadmap v2.5 entry (v2 stable; embedding model present) | roadmap gates; CD-RR values if D12.4 extends to them |
| **v3** | Roadmap v3 | v2.5 shipped | roadmap gates |
| **M2+** remaining v4 | **Android lends** (lend screen, and charging with "serve while locked", D16); **desktop local apps borrow** (D25(b), D14 part B, CD-28…CD-30); **iPad lends** (M5, D16); overlay polish (locator hints, if ever approved); the optional LaunchDaemon mode on macOS; UT-2 and D26 profiles only if scheduled | v3 shipped; **v4 design session held and recorded**; D14 part B (desktop apps); D16; D29 if any targetSdk bump | Android: `android-mesh.md` L-1…L-4 incl. W08 server role and DV-L1…DV-L6. iPad: `ios.md` I4 incl. DV-I13…DV-I17 and the App Review outcome if submitted. Desktop apps: IC-8 identity vectors |
| **Parked** | Fan-out (D10 mode 2), sharding, delegated routing, other-owner peers, mDNS locate, revocation and locator hints, A2 attestation, `/admin/manifest`, automatic probes, lender KV partitioning beyond D19(a), per-subscriber export keys (unless D6(e)), third-party iOS host apps (D15(e)), an `Embedded` tier in AsomKit, CUDA builds (D27), car and appliance profiles (D26), general-purpose compute offload | each needs its own owner decision and, for most, a design session | — |

### 9.4 Effort (rough estimates, engineer-weeks of focused work; not measurements; not calendar time)

| Phase | Estimate | Source | Change from r2 |
|---|---|---|---|
| Lab L0.1–L0.6 | 16–25 | this brief | — |
| Swift lane L0.7 | 3–5 | `macos.md` MC0 / `ios.md` I0 | — |
| v1.1 additions (H1–H7) | 1–3 | this brief | Android H5–H7 detail from `android-mesh.md` (1–2 inside this range) |
| v2 benchmark additions | 3–6 | this brief | −1: no desktop CLI, no Android standalone APK |
| D-v2 Linux/Deck (DL0–DL5) | 9–14.5 | `linux.md` | replaces 9–16 |
| D-v2/M1b Windows | 10–16 | `windows.md` | **new** |
| D-v2/M1b macOS (mode A, full) | 11–17 (minimal cut 7–10) | `macos.md` | replaces 4–6 |
| M1 mesh-1 (Android requester + desktop integration) | 7–11, of which Android 5–8 (`android-mesh.md`) and Linux DL6 2–3 | this brief | — |
| M1b iOS requester + app (I1–I3) | 13–20 | `ios.md` | replaces M3 8–12 + M4 6–11 (M3 is no longer a separate app phase) |
| M1b Ubuntu Touch (UT-0 + UT-1) | 7–11.5 (+8–12 if S-UT1 fails) | `ubuntu-touch.md` | **new** |
| M2+ Android lender | 5–9 | `android-mesh.md` | moved after v3 |
| M2+ desktop local-app API | 3–5 | this brief | — |
| M2+ iPad lender (I4) | 4–7 | `ios.md` | — |
| **Total (sum of the rows above)** | **≈ 92–150** | — | r2's 72–122 covered a narrower scope: no Windows, no Ubuntu Touch, macOS at 4–6. Excludes roadmap v1.1/v2/v2.5/v3 themselves, UT-2, the Rust fallback and D26 |

These are estimates, not measurements [A24]. They assume one engineer per platform track familiar with its stack, the owner's hardware available for device sessions, and spikes passing on the first design.

---

## 10. Owner decision register

**How it is organised (r3).**
- **§10.0 lists what is decided**: the owner's directives, the acting decisions the owner delegated (AD-1…AD-6; each stands unless the owner overrules it), items settled by frozen text, and design choices this brief adopts without contract effect (overridable, no ruling needed).
- **§10.1 onward lists the 22 decisions still open**, grouped by the phase they block. r1/r2 ids are kept so earlier references stay valid; ids that are now decided are marked in §10.0. D12 is **unbundled** into separate sign-off lines (R2-CONFORMANCE-12). D27–D29 are new.
- **Every contract item in §8.3 names its decision. An item whose decision is unruled is not approved.**

### 10.0 Decided

#### 10.0a Owner directives (`OWNER_DIRECTIVES_2026-09-30.md`)

| Id | Directive | What it decides, and only that (R2-CONFORMANCE-4) |
|---|---|---|
| **D0** (D-B) | The mesh is symmetric and holonic; the phone is not privileged; the goal is ubiquitous computing among the owner's devices; the stop-line is unchanged | The framing and the boundary. **It does not decide** the design consequences listed in §10.0d, which are this brief's recommendations; nor does it make every node a full holon (§0.1 says which are partial) |
| **D13** (D-A) | Apple platforms (macOS, iOS, iPadOS) are in scope | Scope. Sequencing and shape stay open (D24, D15, D16); the invariant text stays open (D14) |
| **D-C** | Verified positioning: the router is the differentiator; the harness is not; never self-stamp "MLPerf-comparable" | §0.3, §6: the three differentiators; the wording rule. The MLPerf Client extension is this revision's fact-check [F49], applied under D-C |
| **D-D** | Roadmap order stands except where AD-1 revises it; v1 validation first | Nothing ships before V1-close |
| **D-E** | Platforms in scope: Android, Linux, Steam Deck, Windows, macOS, iOS/iPadOS, Ubuntu Touch | Scope (RT-7) |
| **D-F** | Delegation to finish what can be finished, with agents per platform | The acting decisions below |

#### 10.0b Acting decisions (submitted for ratification; decided unless overruled)

| Id | Decision | Replaces | Consequences recorded in |
|---|---|---|---|
| **AD-1** | After V1-close and v1.1: v2 → mesh-1 (the v4 core incl. asom-desktop) → v2.5 → v3 → remaining v4; v2.5's per-app cloud-ban column pulled into mesh-1 | r2's **D4** (decided as its option (b1)) | §0.4, §1.4, §9; RT-8, RT-10. The v4 entry criteria become: design session held (this brief), v2 shipped, at least one Linux/Deck node commissioned (roadmap §14 item 5) |
| **AD-2** | Amendment 3 (mesh) declared explicitly: Invariant 1 narrowly **relaxed**, Invariant 3 class `peer`, Invariant 5 node key identity; Amendment 2 unchanged; roadmap §13's count becomes three | r2's **D2** | §8.2 (exact texts, ratified with AD-2); RT-1, RT-6; CD-1, CD-12, CD-18…CD-23. D3 narrows to the header meaning; D6 and D14 are re-put under the same principle |
| **AD-3** | Repository placement: `docs/design/mesh/`, `lab/`, `desktop/`, `apple/`, `ubuntu-touch/`; root build and `jvmTest` unchanged | — | §3.6; `PLATFORM_PLAN.md` |
| **AD-4** | The lab is authorised as the sanctioned exception | r2's **D1a and D1b** | §9.1 incl. L0.4–L0.6. r2's D1b concern (building ahead of D2, D3, D5) is resolved for D2 by AD-2; the lab builds the D5(b) ledger variant ahead of D5 and says so; D3 affects only the header projection, which the lab parametrises |
| **AD-5** | Verification honesty | — | §9 evidence labels; every platform row of §3.1 |
| **AD-6** | Opus designs and reviews; Sonnet implements | — | `LAB_SPEC.md` is self-contained for implementers |
| (roadmap §14 item 7) | `desktop/`, `apple/`, `ubuntu-touch/` scaffolds gated by hosted CI, shipping nothing | `linux.md` **LD-1** (decided as its option (b)); the UT-0 part of **UT-D1**; the Swift-lane part of r2's **D24** | §9.1 |

#### 10.0c Settled by frozen text (no ruling needed)

| Id | Settled by | Content |
|---|---|---|
| **D10** | Roadmap §7 "whole-model placement only"; §1 "capacity, not speed" | Whole-request placement in mesh-1; fan-out deferred; sharding parked; cross-device speculation and prefill/decode split rejected |
| **D17** | CLAUDE.md "no KMP" | The ban stays; re-escalation to the owner with evidence only if (i) iOS must choose among providers, (ii) Swift conformance failures are found after merge in two consecutive releases, or (iii) Kotlin Swift export reaches Stable |
| **D20** | Roadmap §11 stop-line; directive D-B's boundary | Other-owner peers are outside this roadmap; `X` and audience `other` stay out of every schema |

#### 10.0d Design choices this brief adopts (no contract effect; the owner may overrule; no ruling needed)

- **Recommendations derived from D0** (formerly listed as "consequences" of D0): consent per pair and per direction (T5); the protocol carries model inference only (§2.2); desktop CLIs borrow in mesh-1 (**needs D25(a)** for its invariant clause); cars and appliances unscheduled (D26).
- **Deferrals** (formerly bundled in D12): `/admin/manifest`; mDNS locate; locator and revocation hints; advisory placement frames; fan-out headers; Android A2 attestation (the corrected algorithm of §5.5 is kept for when it is built); the revocation mirror.
- **Rejections:** App Attest and DeviceCheck (they contact Apple, a new egress class); catalogue-borne attestation anchors (CD-27); Ed25519 (C2); automatic requester probes; in-app update checks (C14); Windows Hello, DirectML and Windows ML for mesh-1; Flatpak, AppImage and Snap for the daemon; the macOS login keychain as a key tier; QML `XMLHttpRequest` or Qt 5.15 `QSslSocket` for the Ubuntu Touch node; iPhone lend-screen lending; unplugged or battery lending on phones and the Deck.
- **Build-order choices:** desktops ship CLI-only until D14 part A (C-15); the desktop `asom-bench` CLI is dropped (a D7 option the owner can reverse); the Linux probe parsers have one home in `desktop/node` (X42).

---

### 10.1 Decisions needed before v1.1

**D5 — Ledger shape, the v1 gap, the new v1.1 error, and control-traffic granularity.**
*Blocks:* v1.1 scope; the M1 ledger. *Rules:* CD-LU, CD-14…CD-17, RT-3; selects IC-2's control sentence.

| Option | Consequence |
|---|---|
| (a) Append-only intent/outcome rows; H1 for cloud attempts in v1.1; `DIAL` rows per connect; per-manifest rows; **interval rows** for other control traffic and refused inbound; fail-closed with `LEDGER_UNAVAILABLE` | Closes the v1 gap. Control byte counts have a **loss window of up to 1 h**, which weakens the sanctioned "every remote request ledgered on both nodes"; IC-2 then uses the D5(a) sentence |
| **(b) As (a), but one row per control frame on both nodes per §8.4's table (named frame list, incl. each `PAIR_*` frame and `ERROR`), application bytes exact and overhead per session, refused inbound counted per 10 min** | Meets the sanctioned text literally for authenticated sessions. Only per-session overhead can be lost on a crash. More fsyncs (bounded by the `STATE_REQ` budget; measured at M1 gate 8) |
| (c) Either of the above for the mesh only; no v1.1 change | The v1 gap stays open until the mesh; v1.1's delta stays "`QUOTA_EXHAUSTED` only" |
| (d) Mutable rows updated by `attemptId` | Breaks "rows never change". Rejected |

What (a) and (b) both change in frozen text: a **new §5.6 error code** `LEDGER_UNAVAILABLE` in v1.1, which **changes v1 cloud behaviour** (a ledger failure now blocks a cloud request) and roadmap v1.1's locked delta (RT-3).
**Recommendation: (b).**

---

**D23 — Contract and dependency-law registry.**
*Blocks:* each promotion and each phase that ships a listed item. *Rules:* every §8.3 item no other decision covers: CD-DOC3, CD-24 (with D25), CD-C, CD-D (8 shipped dependencies incl. CameraX and procrun, 4 build-time tools, 1 conditional), MOD-1, MOD-2, MOD-3 (transparency), RT-5, AF-2, AF-3, AF-4, AF-6, and the internal channels listed for transparency.

| Option | Consequence |
|---|---|
| **(a) Approve the registry as listed in §8.3, each item at its stated phase, with the rule that an item with no ruled decision is not approved** | Nothing ships on a sign-off nobody gave |
| (b) Rule item by item as each phase arrives | Finer control; more decisions later |
| (c) Approve every contract item across all decisions now | Hides rulings in a blanket approval. Not recommended |

**Recommendation: (a).**

### 10.2 Decisions needed before v2

**D6 — The P7 upload class and the benchmark report export: how recorded, and what is exported.**
*Blocks:* v2 P6/P7. *Rules:* IC-5, IC-6, CD-13, CD-23 (file, public), RT-14.

**The literal reading (unchanged from r2):** Amendment 1 amends Invariant 1 only and allows "exactly one more" user export (P7). The P7 POST has no Invariant 3 class (IC-5 is unavoidable if P7 ships), and a report export is a second new export action (IC-6).

**How to record it (R2-CONFORMANCE-2, R2-OVERCLAIM-2):**
- **(R4) Record IC-5 and IC-6 as Amendment 4 (benchmark sharing).** Consistent with AD-2's principle that no amendment is stretched over changes it did not name.
- (R1) Fold them into Amendment 1 and rewrite its text. **Now inconsistent with AD-2**: it is the same "stretch an existing amendment" move AD-2 rejected for the mesh. Kept only as the owner's alternative.

**The export shape:**

| Option | Consequence |
|---|---|
| (a) Class `contribution` + anonymous summary export only | R3e met only between paired nodes |
| **(b) (a) + a one-shot signed report with a per-export key and the fingerprint-comparison step; the NIK never signs exports** | Tamper-evidence for a recipient who compares the fingerprint; **without the comparison the signature proves nothing about who made the file**; with it, as much as comparing the file's hash. Third-party subscription NOT MET |
| (c) (a) + NIK-signed exports | A permanent device fingerprint in every file. Rejected |
| (d) No exports; P7 still needs IC-5 | R3d/R3e unmet outside the mesh |
| (e) (b) + a user-created per-subscriber key (pinned by the subscriber until revoked, 90-day re-confirmation) | Meets subscription at the cost of linkability to that subscriber |

**Recommendation: (b), recorded as (R4) Amendment 4.** Offer (e) only when a real third-party subscriber exists.

---

**D7 — Roadmap v2 P6 wording: one benchmark subsystem, and which shells.**
*Blocks:* v2 P6. *Rules:* RT-2, AF-1, MOD-1 (`:bench-app`).

| Option | Consequence |
|---|---|
| **(a) Amend both sentences to: "One benchmark subsystem, inside every asom node, that measures asom's own serving path to feed its router and to produce a signed manifest and plain text. Its active lane uses MLPerf Mobile's and MLPerf Client's model sets and metric definitions where licences permit; its passive lane is every real local inference." Shells: the Android daemon (v2), `asom bench` inside each desktop node (D-v2), the iOS asom app (owner devices). No standalone benchmark products; the Android standalone APK unscheduled** | R3b ("can act as an independent app") is **partly met**: the iOS app runs without any daemon; elsewhere MLPerf Mobile and MLPerf Client already serve the standalone use case [F40][F49] |
| (b) As r2: also the Android standalone APK at M2 and a standalone desktop CLI | Meets R3b fully; builds what directive C calls me-too |
| (c) Keep the roadmap text | R3b unmet; the active lane contradicts "not a new subsystem" |

**Recommendation: (a).**

---

**D18 — Comparability baseline, bench sets, the wording and trademark check, editorial constants, the editorial table key.**
*Blocks:* v2 P6 normative outputs. *Rules:* CD-26, RT-4, RT-12.

| Option | Consequence |
|---|---|
| **(a1) MLPerf Mobile's (phones) and MLPerf Client's (desktops) metric definitions and prompt sets for every run, pinned after S-B1 and S-B1d; the Llama set L1 opt-in behind a licence screen; Q1 (Qwen3, Apache-2.0) the default and the only set above 8B** | Nothing breaks for users who decline the Llama licence; **default runs are not comparable to anything MLPerf reports**, and reports say so (R2-DIRECTIVES-3) |
| (a2) As (a1), but L1 becomes the **first-run default for users who accept the licence at first run** (Q1 for those who decline) | More rows on MLPerf's models; the licence screen becomes part of first run |
| (b) L1 only | Every user must accept the Llama licence; no tiers above 8B |
| (c) Q1 only | Licence-clean; no model overlap with MLPerf Mobile (Qwen3-8B overlaps MLPerf Client's experimental set) |
| (d) Reuse MLPerf apps or harness wholesale | Firebase and Flutter (Invariants 1, 7, 8); a different engine than the router serves. Not viable |

**Sub-decisions:**
- **L1 file source:** (i) user import only; (ii) a pinned third-party GGUF; (iii) an owner mirror (the owner becomes a redistributor). Official repositories are probably gated [A28].
- **Trademark and results-messaging check (new, R2-OVERCLAIM-4):** before any shipped text uses the MLPerf name, the owner reads the MLCommons policies [F50] and either (i) approves the descriptive note of §6.1 as written, (ii) approves a variant, or (iii) forbids the MLPerf name in shipped text (reports name only the models). Until then the note is disabled (§6.1 rule 3).
- **Editorial reference table (RT-12):** approve the owner-offline-key signing and the claim-capping use, and take the key-custody task, or keep the table unsigned and unused.
- **Editorial constants:** "comfortable" = 10 tok/s and 2 s; "usable" = 4 tok/s and 10 s. **Charger** required for heat tests. **CPU reference NLLs** computed by the owner at the pinned commit.

**Recommendation: (a1); L1 files from (i) or (ii); trademark check (i) or (iii) at the owner's judgement; RT-12 approved with the key task.**

---

**D22 — Publisher identities.**
*Blocks:* Android distribution from 2027; any signed Windows or macOS release; the iOS app on devices.

| Sub-decision | Options | Recommendation |
|---|---|---|
| Android developer verification (all apps on certified devices from 2027 [F26]) | (a) register a developer identity and key before the global expansion; keep F-Droid; (b) limited-distribution account (≤ 20 devices); (c) rely on the "advanced flow" | **(a)** |
| Windows code signing (Smart App Control blocks unsigned files [FW26]) | (a) SignPath Foundation (free for qualifying OSS); (b) Azure Artifact Signing (individuals in the US/Canada only [FW27]); (c) an OV certificate on a token; never unsigned releases | **(a), else (b) if eligible, else (c)** |
| Apple Developer Program account and Team ID (names the macOS group container and every signature; permanent) | (a) an organisation account for asystemofcells; (b) an individual account | **Decide once before the first signed release**; (a) if a legal entity exists |

### 10.3 Decisions needed before D-v2

**D21 — Desktop engine crash containment.**

| Option | Consequence |
|---|---|
| **(a) In-process JNI everywhere (v2 non-negotiable), with a crash contract: typed requester error, requester outcome row, reconciliation of orphaned intents on restart** | A driver fault takes the node and its streams down |
| (b) A desktop-only engine child process over an inherited pipe | Survives engine crashes; changes a written non-negotiable |

**Recommendation: (a)**; revisit with evidence.

---

**D25 — Local callers that are not apps (mesh-1), and desktop local apps (M2).**
*Blocks:* D-v2 (the owner CLI), M1b (Ubuntu Touch self-UI), M2 (desktop apps). *Rules:* IC-4(b), CD-19, CD-24 (with D23), CD-28…CD-30 (with D14 part B). Absorbs `windows.md` **W-D11** and the Ubuntu Touch caller label of **UT-D9**.

| Option | Consequence |
|---|---|
| **(a) mesh-1: add IC-4(b) to Amendment 3's Invariant 5 text: the owner's own CLI (Linux `SO_PEERCRED`, macOS `getpeereid`, **Windows socket ACL only, stated weaker**) and an app's own screen (Ubuntu Touch) are not apps and hold no pairing token; rows say `local-uid:` / `local-sid:…(acl)` / `self-ui:`** | Desktop CLIs and the Ubuntu Touch app can borrow in mesh-1 with an explicit invariant basis (R2-CONFORMANCE-6). Roadmap v4 already sanctions a "CLI shell" |
| (b) As (a), and at M2 a **desktop local-app API**: tokens minted only by a TTY-confirmed `asom pair-app` (CD-29); identity = token + OS-checked caller; any app label self-reported (CD-30); transport either the frozen HTTP API on `127.0.0.1:11435` (port squatting stated) or HTTP over a Unix socket (CD-28) | Desktop apps borrow; needs IC-8 (D14 part B) |
| (c) Gate all desktop borrowing on D14 part B | No desktop borrowing in mesh-1 |
| (d) Desktops only lend | Contradicts D0's symmetry for desktops |
| (e) Require native peer-PID checks on Windows before M1 (`SIO_AF_UNIX_GETPEERPID`) | Stronger Windows identity; native code |

**Recommendation: (a) for mesh-1, then (b) at M2.**

---

**D27 — The platform engineering package (no contract or invariant effect; each item can be pulled out).**
*Blocks:* D-v2 and the platform scaffolds' promotion. It accepts, as one package, these platform-section recommendations:
- **Linux:** LD-3 (ship a jlinked Temurin 21; build on 17), LD-4 (nFPM for deb/rpm, build-time only), LD-5 (T1 `systemd-creds host+tpm2` on the Dell; T2 only after S-L3; the Deck T0), LD-8 (no standalone Linux bench), LD-9 (GitHub Releases + offline-signed `SHA256SUMS` + attestations; an apt/dnf repo later), LD-11 (weak-host: document, print, check read-only, test in CI), LD-12 (in-house read-only D-Bus reader for `PrepareForSleep`; no pre-sleep drain as fallback).
- **Windows:** W-D2 (user mode default; procrun service opt-in), W-D3 (elevated, view-first firewall enable), W-D5 (JNA 5.19.x now; FFM with JDK 25 later), W-D7 (own WiX v6/v7 source; the owner confirms the Open Source Maintenance Fee does not apply), W-D8 (Windows 11 23H2+; Windows 10 22H2 best-effort until ESU ends 2027-10-12), W-D9 (build and CI-test arm64; lend only after device validation), W-D10 (winget `asystemofcells.asom`, signed releases only; also an AF row).
- **macOS:** M-D2 (SMAppService agent by default; LaunchDaemon only at M2 after S-M5), M-D3 (Secure Enclave via the helper if S-M1 passes, else T0), **M-D4 (the hardened `asom-node` launcher if S-M3 passes; otherwise the stock launcher with the node rated "same-user compromise = node compromise" in the Peers tab)**, M-D5 (notarised pkg/dmg + own Homebrew tap; no Mac App Store), M-D6 (recommend open-source `tailscaled --no-logs-no-support` + Headscale; disclose the GUI variants' log upload), M-D7 (macOS 15+, arm64 only), M-D8 (ProcessType by measurement S-M4), M-D9 (CLI now; SwiftUI after D14 part A), M-D11 (registry rows, D23).
- **iOS:** iOS-D3 (minimum iOS 26.0, subject to inventory), iOS-D5 (development provisioning to the owner's devices; TestFlight only with its crash sharing disclosed), iOS-D6 (no signing secrets in CI), iOS-D7 (build the llama.cpp XCFramework in CI from the pinned commit), iOS-D8 (ES256 only), iOS-D9 (no `BGContinuedProcessingTask` in the MVP).
- **Ubuntu Touch:** UT-D2 (JVM in the click, conditional on S-UT1; Rust single-lender fallback), UT-D3 (OpenStore confined, common policy groups only, plus a GitHub Release click with published SHA-256), UT-D4 (camera QR with paste fallback), UT-D5 (LAN-direct by default; an owner-installed kernel-TUN overlay detected and disclosed), UT-D6 (one arm64 click on 24.04-1.x; 26.04 canary; no 20.04), UT-D7 (UT-2 unscheduled), UT-D8 (T0 file, fscrypt recommended, passphrase optional).
- **Android:** AN-3 (wireless charging does not count as plugged in), AN-4 (a phone lender listens on the overlay only at first), AN-5 (CameraX + zxing-core with paste fallback; the dependency itself is D23), AN-6 (watch each release for same-profile loopback gating; no Binder data plane now), AN-7 (disclose Tailscale's Android log switch at pairing), AN-8 (the loopback probe in v1.1 via a CI-only flavour), AN-9 (NIK attestation challenge only, never sent in mesh-1), AN-10 (lending constants PROVISIONAL).
- **Engines:** LD-7 and W-D6 (no CUDA in mesh-1; Vulkan on NVIDIA; a CUDA package linked against the system runtime only after the Dell inventory, a measured gap and a licence read).

| Option | Consequence |
|---|---|
| **(a) Accept the package; any item the owner names is pulled out and ruled separately** | Engineering defaults are settled without 50 separate rulings; nothing here changes a contract or an invariant |
| (b) Rule each platform's items when its phase begins | Finer control; more decisions later |

**Recommendation: (a).** M-D4 is the item most worth reading: it decides whether a same-user process on a Mac can inject code into the process that holds the node key.

---

**D28 — Which devices lend in mesh-1, and on what terms.**
*Blocks:* D-v2's lending targets and M1's gates. Absorbs `linux.md` LD-2, LD-6, LD-10, `windows.md` W-D1 and `macos.md` M-D1.

| Sub-decision | Options | Recommendation |
|---|---|---|
| The Dell (Linux) keep-awake | (a) ship the polkit rule that lets only user `asom` hold a sleep **block** lock, inert until lending is on; (b) ship it as a disabled example; (c) never: lend only while the machine happens to be awake | **(a)**: it grants one action to one user; `asom status` shows the energy cost |
| The Deck in Game Mode, where nothing on its screen shows that it lends | (a) never: Desktop Mode foreground lending only; (b) yes, behind an explicit opt-in whose copy says "this Deck can lend compute without showing anything on its screen"; docked, on AC, no game; availability left to Steam's sleep setting; (c) as (b) plus a block lock | **(b)**. **(c) is rejected**: the Game Mode fake-sleep hazard [LF05] |
| The Deck on battery | (a) hard no; (b) off by default, overridable | **(a)** |
| Windows as a mesh-1 lender | (a) requester-only in mesh-1; lends from M1b/M2; (b) a mesh-1 lender if the Dell runs Windows, with the Windows M1 gates (§9.3 gate 13) | **(b) if the Dell runs Windows, otherwise (a)** |
| A Mac as a mesh-1 lender | (a) a mesh-1 lender if an Apple-silicon desktop with ≥ 16 GB exists, with the macOS M1 gates (gate 14); (b) M1b; (c) requester only | **(a) if such a Mac exists; otherwise (b)** |

### 10.4 Decisions needed before M1

**D3 — What `X-Asom-Egress` means with the mesh (narrowed in r3).**
*Rules:* CD-1m, CD-DOC1, the `reach`/`terminal`/`servedClass` columns. The class `peer` itself is decided by AD-2.

| Option | Consequence |
|---|---|
| **(a) The header states the request's furthest content reach, stored in the terminal row's `egress`; the serving class in `servedClass`; `CLIENT_API.md` documents an open set** | True on every path; header and dashboard agree (Invariant 9's purpose). **Changes the meaning of frozen §5.4** (it coincides with v1's on every v1 path). Lands at M1 only |
| (b) The header states the serving attempt's class | A request whose content reached a peer and was then served locally would say `local`: the false-class failure the audit fixed. Not viable |

**Recommendation: (a).**

---

**D8 — Network underlay for the mesh.**

| Option | Consequence |
|---|---|
| (a) Tailscale-operated overlay | Off-LAN reach; the operator learns the device graph; relays carry encrypted traffic [F09]. **Client log uploads per platform:** Linux and Windows opt out only by `TS_NO_LOGS_NO_SUPPORT` on each client (and **Headscale does not stop them without it** [LF10]); macOS only with the open-source `tailscaled` and `--no-logs-no-support` (the App Store and Standalone apps have no documented opt-out [FM29]); **Android since 1.98 has a switch, on by default and forced on under MDM** [AF21]; **iOS none documented** [IF31]. Userspace-networking mode unsupported [F11] |
| **(b) Self-hosted Headscale, logs disabled on every client that allows it; LAN-direct on user-confirmed home networks as an equal path** | Removes the third-party coordinator; the iOS client probably still uploads logs [IA09]; asom discloses per platform what it cannot switch off or verify |
| (c) LAN-direct only | No third party; home network only; address churn; Android 17's LAN permission prompt at targetSdk 37; certificate visible on the LAN (T13) |

**Recommendation: (b), with the per-platform disclosure above.** The roadmap's "tailnet exists" criterion was replaced by AD-1 (RT-10), so (c) needs no further text change.

---

**D9 — What `auto` means with the mesh.**

| Option | Consequence |
|---|---|
| (a) Literal roadmap order: this device → a node holding the model → cloud; no scoring | A hot phone in hand serves before an idle desktop |
| (b) Scored sovereign block; sovereign always before cloud | Slow peers are tried before cloud even when far too slow |
| **(c) (b) + a usability gate on PEERS only; SELF keeps v2's position; a user switch "never cloud when my devices can answer"** | Prompts go to the cloud before a too-slow *peer*, never before SELF; mesh-off behaviour equals v2 exactly (RL1) |

**Recommendation: (c).**

---

**D11 — Body handling (frozen brief §5.9).** *Rules:* CD-BH1, CD-BH2.

| Option | Consequence |
|---|---|
| **(a) The router reads `max_tokens` (read-only), and bodies sent to peers pass an allow-list normaliser (identity-bearing fields dropped, names recorded)** | Better estimates; peers do not receive `user`, `metadata`, `safety_identifier`, `prompt_cache_key` or `store`; provider pass-through unchanged |
| (b) Neither | Output length from an EWMA only; app identifiers inside bodies reach peers |
| (c) Normaliser only | Middle ground |

**Recommendation: (a).**

---

**D12 — New app-facing values: five separate sign-offs, and the withdrawals (unbundled in r3; R2-CONFORMANCE-12).**

| Line | Item | Frozen text | Recommendation |
|---|---|---|---|
| **D12.1** | CD-2: `X-Asom-Served-By: peer:<alias>/<model>`, a per-app pseudonym, the requester's offered model id | §5.4 | **approve** (the real node tag would be a cross-app identifier) |
| **D12.2** | CD-4: `/v1/models` entries `owned_by: "asom-peer"`, shown only to apps whose mesh toggle is on | §5.2 | **approve** |
| **D12.3** | CD-6b: SSE in-band error value `MESH_STREAM_INTERRUPTED` (no new HTTP code; no `[DONE]`) | §5.2 SSE | **approve** (a mid-stream loss must be typed, not silent) |
| **D12.4** | CD-FO: `X-Asom-Failover` values `peer-unavailable`, `peer-lost`; and, with v2.5, the coarse CD-RR route-reason values | roadmap v2/v2.5 deltas | **approve** |
| **D12.5** | CD-DOC2: `CLIENT_API.md` explains the `peer:` form | pinned SDK doc | **approve** |
| **D12.6** | Withdrawals: `X-Asom-Node`; the `own-devices` policy and `NO_ELIGIBLE_NODE` (X13 stands because the cloud-ban column exists in mesh-1); `locSeed`/`locKey`; RT-6's withdrawal part | roadmap §7/§8 | **approve** |

Deferrals and rejections formerly in D12 are design choices in §10.0d and need no ruling.

---

**D19 — Lender KV/prefix cache for peer attempts.**

| Option | Consequence |
|---|---|
| **(a) Discard after every peer attempt (mesh-1 default)** | Closes the prompt-cache timing channel [F29]; multi-turn chat re-prefills each turn |
| (b) Partition by (requester pin, a per-app token rotating every 24 h), idle TTL 10 min | Faster multi-turn; the lender learns a daily per-app pseudonym |
| (c) Partition by requester pin only | Apps on one phone could probe each other's prefixes via timing |

**Recommendation: (a) for mesh-1**; revisit (b) once Deck/Dell prefill is measured at D-v2.

---

**D29 — Android `targetSdk` (a brief §3 build pin; R2-CONFORMANCE-10; `android-mesh.md` AN-1).** *Rules:* RT-11.

| Option | Consequence |
|---|---|
| **(a) Stay at 35 through mesh-1 and the lender phase unless separately ruled; any bump is its own ruling, preceded by the H5 emulator probe and device test DV-A2** | Every mesh path works at 35 with no new runtime permission [AF02] |
| (b) Bump to 37 at mesh-1 | `ACCESS_LOCAL_NETWORK` for same-subnet LAN peers; a new prompt |
| (c) Bump in v1.1 | Earlier exposure to Android 17 rules |

**Recommendation: (a).**

### 10.5 Decisions needed before M1b and M2+

**D14 — Platform-equivalence invariant text (IC-7, IC-8): approve, and how to record it.**
*Blocks:* the first SwiftUI app, QML UI or desktop tray (part A); the first key-holding non-Android tier and the desktop local-app API (part B). *Rules:* IC-7, IC-8, RT-9 (invariant side), RT-14.

| Option | Consequence |
|---|---|
| **(a) Approve part A before the first non-Compose UI ships (M1b), and part B before the first key-holding non-Android tier or the desktop local-app API (M2); record both as Amendment 5 (platform equivalence)** | Just-in-time text, recorded consistently with AD-2 |
| (b) Approve A and B now, as Amendment 5 | Earlier certainty; approves text before any code needs it |
| (c) Treat them as an "interpretation" | Changes invariant wording while calling it interpretation: the under-reporting the audit warned about. Rejected |

**Open sub-question (part B):** is a Windows socket ACL (no peer credentials) an acceptable OS-verified identity for local **apps**, or only for the owner's CLI (D25)? **Recommendation: CLI only**, until native peer-PID checks exist.
**Recommendation: (a).**

---

**D15 — iOS requester shape.** *Rules:* CD-10A.

| Option | Consequence |
|---|---|
| **(b) A direct requester to one user-chosen home lender, paired as a device (one node key in a Team keychain access group [A25; S-A12]); no silent cloud downgrade; the 10A tier `RemoteMesh`** | Most of the value with no router in Swift; the lender sees a node, never an app; the per-app toggle is enforced by AsomKit code, not the OS (stated) |
| (c) Delegated routing through a home node | A relay; a compromised home node sees every iPhone prompt; a further amendment. Rejected |
| (d) (b) with multi-provider choice in Swift | Routing in Swift: re-escalates D17 |
| (e) (b) with per-host-app pairing incl. third-party host apps | A remote app identity (Invariant 5 text beyond Amendment 3). Not recommended |
| (f) (b) plus an `Embedded` tier in AsomKit (a local engine inside host apps) | Would make an iPhone serve its own host app; puts an engine and model storage into every host app, against brief §10A's dedup intent. Not recommended now |

**Recommendation: (b).**

---

**D16 — Phone and tablet lending shapes (after v3).** Absorbs `ios.md` iOS-D4 and `android-mesh.md` AN-2.

| Sub-decision | Options | Recommendation |
|---|---|---|
| iPad lending | (a) at M5 with the lend screen; (b) in scope but unscheduled | **(a)**, with iOS-D4's conditions: charging required; a 2 h cap, then re-confirm; Metal with the stop-before-background guard, CPU automatically if DV-I5 ever crashes; **"re-authentication to leave" replaced** (not implementable) by: leaving the lend screen always stops serving; settings and other tabs need `LAContext` |
| iPhone lending | never / allowed | **never** (code-gated off) |
| Android lending shapes | (a) lend screen only; (b) lend screen + lend-while-charging with an explicit "serve while locked" leaf; (c) none | **(b)**, with the T9 cost in the consent sheet; (a) if the owner rejects any key usable while locked |

---

**D24 — Sequencing of platform requesters and apps within AD-1.** Absorbs `ios.md` iOS-D1 and iOS-D2 and the placement part of `ubuntu-touch.md` UT-D1. *Rules:* RT-13, AF-5.

| Option | Consequence |
|---|---|
| **(a) M1b: the iOS asom app (one app `xyz.mdhv.asom`, hosting bench, pairing, verifier; iPad lend screen later) and AsomKit `RemoteMesh`, and the Ubuntu Touch requester (UT-1), right after M1 and before v2.5; no separate iOS benchmark phase; no parallel tracks** | The router-bearing Apple and Ubuntu Touch work comes first (R2-DIRECTIVES-6); a placement inside mesh-1's window that departs from roadmap §0 and must be recorded (RT-13) |
| (b) As (a), plus an owner-devices-only iOS benchmark build running **in parallel** with D-v2 | Earlier Apple app on devices; a parallel track, a further departure from roadmap §0 (R2-CONFORMANCE-8) |
| (c) Strict AD-1: iOS and Ubuntu Touch requesters only after v3 | No departure; Apple and Ubuntu Touch users wait for v2.5 and v3 |

**Recommendation: (a).** The Swift lane (lab) already started under roadmap §14 item 7 and ships nothing.

---

**D26 — Long-horizon holons (car head units, Wi-Fi appliances).**

| Option | Consequence |
|---|---|
| **(a) Not scheduled; the design keeps them possible as a requester-only profile with QR pairing and no listener; revisit after M2** | No work now |
| (b) Schedule a headless Linux requester profile after M2 | Small (the JVM node with lending off); useful only if such a device exists |
| (c) Schedule an Android Automotive requester | Depends on head-unit sideloading rules [A32] |
| (d) General compute offload | Runs another device's code; a new design session. Not recommended |

**Recommendation: (a).**

---

**Owner inputs (not decisions), needed before D-v2/M1:**
- the Dell's OS **and GPU** (Windows changes D28; the GPU decides which §7.1 row applies);
- whether an Apple-silicon Mac exists, and whether it is a desktop (D28);
- whether a Ubuntu Touch device exists (S-UT1 needs one);
- whether Headscale is acceptable (D8);
- the owner's country (Windows signing route, D22);
- re-verification of the Q1 sha256s and the L1 file source (D18);
- whether a specific car head unit or appliance is in mind (D26).

---

## 11. Risk register (consolidated)

| # | Risk | Severity | Mitigation | Residual (stated) |
|---|---|---|---|---|
| K1 | **Invariant erosion.** Amendment 3 relaxes Invariant 1; proposed Amendments 4 and 5 touch Invariants 1, 3, 4, 5, 7, 8 | critical | One amendment per subject (AD-2's principle, applied to D6 and D14); every contract item mapped to a decision, unmapped = not approved (§8.3, D23); the owner brief carries the complete count and the five-amendment total; freeze pins (H2); the relaxation stated in user-facing copy (§8.2) | The owner may accept more change than intended; the text is shown in full (§8.2) |
| K2 | **`verifyPeerChain` wired wrong on any one stack** (accept-all trust manager, PKIX fallback, verify block that completes `true` on error), so any LAN attacker becomes a peer | critical | One verifier per implementation; T1 strict pins; **W08 live adversarial suite** in both roles on JSSE, Conscrypt and Network.framework as a phase gate; `SecTrust` forbidden as verifier; per-frame registry re-check | A wiring path W08 does not exercise |
| K3 | **Android local-network permission and loopback** (r3: lowered). Source reading shows loopback is never covered in the same profile [AF05]; cross-profile loopback is blocked for all apps on Android 17 [AF04] | medium (was critical) | H5 emulator probe + device test DV-A2; targetSdk stays 35 (D29); CD-DOC3 | OEM or later builds may differ; see K31 for same-profile gating |
| K4 | **Android attestation forgery** (extension scanned from the leaf) | critical if built as drafted | A2 deferred; corrected algorithm (§5.5) [F07]; display-only; M07 vectors before any use | — |
| K5 | **Cross-node ledger under-reports** (the audit's worst class) | high | Intent-before-content; `DIAL` rows; per-manifest rows; per-message control rows (D5(b), recommended in r2); fail-closed writes; byte accounting at the TLS record layer; L-L1…L-L16; forked-JVM SIGKILL + Android `kill -9` tests; multi-node CI with row-count assertions | Under D5(b): only the TLS record overhead since the last row is lost on a crash. Under D5(a): up to 1 h of control bytes |
| K6 | **A stated class or header is false** (`lan` for relayed traffic; `local` after a peer saw content; wrong error codes) | high | `peer` + `peerPath`; `reach`; RL15b vectors; errors computed over `P` | `peerPath=overlay` cannot say direct vs relayed |
| K7 | **Building on an unvalidated v1** | high | V1-close precedes every shipped phase; lab isolated | — |
| K8 | **Scope explosion for a solo owner** (seven platforms; ≈ 92–150 engineer-weeks, estimate) | high | Symmetric code; one JVM implementation on six platforms; roles ship per platform; benchmark scope cut to three differentiators; standalone bench products dropped; Apple and Ubuntu Touch requesters in M1b only by ruling (D24); iPad, Android lending and D26 after v3 or unscheduled; each platform directory can be deleted to drop the platform | The estimates may be low |
| K9 | **Prompt exposure on a weaker, shared or compromised own device** | high | Per-app opt-in, default off; dedicated-user hosting for always-on providers; Peers tab shows key tier "self-reported", "Used by other people?"; provider keeps no bodies; T17 | A compromised peer reads what it is sent |
| K10 | **Stolen or cloned device** keeps mesh access | high (impact) | Phones are requester-only in mesh-1; keyguard-bound leaf + 30 min session age (T9); clone signal (T10); wrapped key with "serve while logged out" opt-in; revoke on each node | Exposure lasts until the user revokes on every node |
| K11 | **Manifest poisoning** attracts traffic to a hostile peer | high | r3 tracker: end-to-end observations over requester-parsed bytes on the requester's clock; claims can only be lowered; best-quartile honesty state; byte cap with strikes; discard budget; caps from signed reference and class ceiling; 1-in-4 and 1-in-10 caps; claims never grant eligibility (RL12) | First observations are real prompts; a peer that pads answers with filler can look up to about 2× faster until the owner notices garbage (§5.7) |
| K12 | **"Signed" read as "true" or "from them"** by users or third-party apps | high | Viewer-computed verification block; the "Not proven" line; exports not device-key-signed; the fingerprint-comparison step (§5.4); FILE wording "anyone could have made this key" unless compared; the owner brief says it plainly | People still skim; an uncompared signed file looks official |
| K13 | **Same-uid desktop exposure** (Deck games, malware; token and ledger in the journal) | high | T17 (dedicated user where possible; no stdout secrets; Unix socket; TTY confirmations); H3 | On a `systemd --user` node any same-uid process can impersonate the node |
| K14 | **Benchmark overheats a device or cannot stop promptly** on GPU backends | high | Charger rule; ceilings; `PROBE_LOST` watchdog; cancel ≤ one engine call with tuned `n_ubatch` (B6); partial finalisation (B9) | Heat during at most one engine call after a ceiling |
| K15 | **Sim-to-real gap; uncalibrated constants** (tracker thresholds, score weights, numerics tolerances) | high | All marked PROVISIONAL; calibration gates at v2/M1; laws are parameter-independent | Early misplacements |
| K16 | **Overlay exposure** (operator metadata, relays, client log uploads [F10]) | medium–high | D8 per-platform disclosure: Linux/Windows `TS_NO_LOGS_NO_SUPPORT` on each client (Headscale does not stop uploads without it [LF10]); macOS open-source `tailscaled` only [FM29]; Android 1.98 switch, default on, forced on under MDM [AF21]; iOS none [IF31]; asom never edits another product's configuration and says "not verified by asom" | iOS client uploads; settings asom cannot read (root daemons) |
| K17 | **Listener certificate disclosure** enables device tracking by anyone who can connect | medium | Bind only overlay or user-confirmed LAN; S-A11 SNI gating; stated in docs (T13) | Until S-A11 lands on every stack |
| K18 | **Swift/JVM drift** | medium | Vectors incl. W01b, W08, executor traces; self-oracle tagging; independent checker; the D17 triggers | Shared spec misunderstandings |
| K19 | **Providers sleep or yield to games**, so requests stall | medium | §3.2 per-platform sleep rules (no block lock on SteamOS; power requests on Windows; assertions on macOS); drain on `os_sleep_imminent`; dial budgets; half-open probes; M1 idle gate | Modern Standby and forced sleep cannot be prevented |
| K20 | **Android developer verification** blocks sideload/F-Droid installs | high (distribution), from 2027 | D22. r2 correction: the 30 Sep 2026 phase covers installs from participating stores in four countries only; all apps on certified devices from 2027 [F26] | Depends on how the "advanced flow" treats sideloads |
| K21 | **Prompt-cache timing channel** between requesters or apps [F29] | medium | D19 default discard; v2 partitions any on-device prompt cache per app | Other engine timing channels |
| K22 | **App Review rejects** the iOS asom app (bench, pairing, verifier; D24(a)) or the iPad lend screen | medium | The app serves nothing on iPhone; the bench has hard thermal stops; iPad lending only at M5 (D16) | — |
| K23 | **Consuming apps treat `peer` as local** | low–medium | `CLIENT_API.md` open-set wording | Apps that ignore docs |
| K24 | **Durability overclaimed** (power loss) | low | Claim limited to process death; never `SYNC_MODE_NORMAL` | — |
| K25 | **Benchmark effort duplicates MLPerf Mobile and MLPerf Client** (directive D-C) | medium | Three differentiators only; no standalone products (D7); MLPerf model sets and metric definitions as the baseline where licences permit; spikes S-B1/S-B1d/S-B2 before harness code | asom's numbers still differ from MLPerf's, and users may compare them anyway |
| K26 | **Llama licence obligations and access** (attribution, Acceptable Use Policy, agreement copy, 700M-MAU clause [F41]; gated official repositories [A28]; possible F-Droid anti-feature [A29]) | medium | Llama set opt-in only, licence shown before download, "Built with Llama" wherever its results appear; Apache-2.0 Qwen3 set stays the default (D18) | If shipped, the owner carries the licence obligations for the app |
| K27 | **Holonic scope creep** (cars, appliances, general compute offload) | high (scope) | The protocol carries model inference only; long-horizon holons unscheduled (D26); stop-line restated (§0.7) | The long-horizon goal will keep pulling |
| K28 | **Presence inference by paired peers** (OVERCLAIM-1) | medium | Four fields removed; 10-min hold-down; LP-1/LP-2 with vectors; channel list (§7.12); IC-3(ii) states it | A peer that keeps asking learns a coarse timeline of the lender's use |
| K29 | **Platform work outruns the AD-1 order** (Apple, Windows, Ubuntu Touch) | medium | Before V1-close only ship-nothing lab and scaffold work; every shipped platform phase sits in the AD-1 ladder; any placement inside mesh-1's window is a recorded ruling (D24, D28; RT-13); no parallel tracks recommended | Pressure to ship an iOS app early |
| K30 | **The router differentiator erodes** if an OS vendor adds cross-app, cloud-routing, audited inference to its platform service [A34] | medium | Stay on what a vendor is unlikely to offer: BYOK keys held by the user, no operator backend, an exportable egress ledger, and a mesh across vendors (Android, Linux, Apple) | Not controllable by the project |
| K31 | **A future Android release gates same-profile cross-app loopback** (the reserved `USE_LOOPBACK_INTERFACE` [AF05]), breaking v1's app-facing HTTP API | critical impact, unknown likelihood | Watch each release's behaviour changes and the Connectivity source (AN-6); the AIDL control plane is unchanged; the contingency (a Binder-passed socket-pair data plane) is a frozen-contract change for the owner | Could arrive in a beta with little notice |
| K32 | **Steam Deck fake sleep**: a block lock in Game Mode leaves the Deck running dark and hot, possibly in a bag [LF05] | critical | Never a block lock on SteamOS in any session; a unit test on the policy; device test DV-D4 | Steam or SteamOS behaviour changes |
| K33 | **MLPerf name used against MLCommons' results-messaging rules or trademark** [F50] | medium | "MLPerf-comparable" deleted; the descriptive note disabled until the owner's check (D18); never on default runs; vector M05-MLP | Users compare numbers anyway |
| K34 | **Invisible lending** (the Deck in Game Mode shows nothing on its own screen) weakens the watched-object ethos | medium | Opt-in with explicit copy (D28); requester-side headers and ledgers; `asom status` over SSH | The Deck's screen shows nothing |
| K35 | **Unsigned or low-reputation installers are blocked** (Smart App Control, SmartScreen, Gatekeeper; developer verification on Android) | high (distribution) | D22 publisher identities; sign every PE/Mach-O; notarise; no unsigned releases; attestations | Reputation builds over weeks; account decisions are permanent (Team ID) |
| K36 | **Same-user code injection into an entitled desktop node** (macOS `JAVA_TOOL_OPTIONS`/attach; Windows and Linux user mode) | high | The hardened `asom-node` launcher (M-D4, S-M3); attach disabled; jlinked runtime without instrument/attach modules; dedicated-user system mode on Linux; `shared-uid`/`shared-SID` labels | Until S-M3 passes, "same-user compromise = node compromise" |
| K37 | **Spike S-UT1 fails**: the JVM does not run under click confinement on a Halium device | high (for Ubuntu Touch) | Run the on-device self-test and a Libertine spike first; the CI AppArmor approximation surfaces denials early; the scoped Rust single-lender fallback (+8–12 weeks) | A third implementation if it fails |
| K38 | **ggml-metal aborts an iOS process** when the GPU is revoked mid-compute [IF08] | high | Stop GPU work on resign-active; `n_ubatch` ≤ 1 s per compute; DV-I5 repeated 20×; automatic CPU fallback | A transition faster than one compute can still crash |
| K39 | **Weak-host exposure** of an overlay-bound Linux listener's certificate to LAN hosts [LF39] | medium | Printed nftables rule, read-only doctor check, CI netns test (C18) | Until the owner applies the rule |
| K40 | **JDK 17 support ends** (Temurin 17 to at least October 2027 [LF27]) before D-v2 ships | medium | Ship Temurin 21 in every image; build on 17; run the conformance runner on the build JDK and on each shipped runtime | Build/runtime skew bugs outside the vectors |
| K41 | **Vector drift from r0 artefacts**: builders reuse r0 vectors that encode superseded rules (TOFU, a 30-day file TTL, audience `other`, producer-stored `derived`, removed live-state fields) | medium | `LAB_SPEC.md` §4.9 retires and regenerates them by id; the runner rejects a vector file whose `confVersion` is below the lab floor | A builder ignoring the list |
| K42 | **Self-oracled vectors cleared without independence** (R2-OVERCLAIM-8) | medium | The tag clears only when an author with no access to the generator agrees, recorded in `PROGRESS.md`; the Swift lane is the intended independent reader | Shared misreadings of the prose |
| K43 | **CI green read as device evidence** (simulators, emulators, CI-APPROX AppArmor, hosted VMs without GPU or TPM) | medium | Evidence labels are mandatory in `PROGRESS.md` (§9); device checklists stay open | Reviewers skimming |

---

## 12. Critique disposition log

### 12.1 Round-2 final review (`REVIEW_ROUND2.md`): every finding

**Key:** **A** = accepted, and the text or design changed (where is named); **OD** = the text changed, and closing the finding needs an owner ruling (the decision is named); **R** = rejected, with the reason. Where a reviewer offered alternatives, the one taken is named. The full log, with each reviewer's suggested change beside what r3 did, is `REVIEW_ROUND2_DISPOSITION.md`.

**Result: 35 findings; 32 A, 3 OD, 0 R.** None was rejected: each identified a real defect. Two reviewer *remedies* were not taken because an owner acting decision superseded them (CONFORMANCE-1 and DIRECTIVES-1 proposed strict roadmap order as the default; AD-1 decided the order instead, and r3 now says so plainly).

| ID | Sev. | Issue (short) | Disp. | Where the text changed |
|---|---|---|---|---|
| R2-CONFORMANCE-1 | high | The plan says versions stay in order while reordering them | **A** | AD-1 (decided) now carries the reordering, stated as a revision of directive D-D's letter: §0.2 AD-1 row, §0.4 (full ladder incl. v2.5 and v3, "What moved" paragraph), §1.4, §9 header and §9.3 rows for v2.5/v3, §10.0b; RT-8, RT-10; the owner brief's "versions stay in order" line and D4 row removed |
| R2-CONFORMANCE-2 | high | Inconsistent recording principle; IC-3's relaxation understated | **A** | AD-2 declares Amendment 3 (§8.2) with IC-3 **stated as a relaxation** and user-facing copy; the same principle applied to D6 (recommend Amendment 4) and D14 (Amendment 5); §0.8 and the owner brief state the five-amendment total |
| R2-CONFORMANCE-3 | high | The v4 design session is missing from normative entry criteria; tailnet criterion unrecorded | **A** | §0.6 (this brief is the design session; what it settles); every v4 phase in §9.3 carries "design session held and recorded"; RT-10 records roadmap §14 item 5's replacement criteria |
| R2-CONFORMANCE-4 | medium | D0 lists design choices as decided | **A** | §10.0a limits D0 to the directive's words; §10.0d lists the former "consequences" as recommendations with their rulings (D25, D26); §0.2 D-B row |
| R2-CONFORMANCE-5 | medium | D25(b)'s transport, token path and label are not in the registry | **A** | CD-28, CD-29, CD-30 (§8.3), ruled by D25(b) + D14 part B; the complete count |
| R2-CONFORMANCE-6 | medium | The owner CLI is an inference caller outside Invariant 5 in mesh-1 | **OD (D25)** | IC-4(b) conditional clause (§8.2); C-2 corrected (§1.2); CD-19 caller forms; D-v2 entry lists D23 and D25 (§9.3); Windows ACL-only identity named (W-D11 absorbed) |
| R2-CONFORMANCE-7 | medium | CD-1m and its columns phased before D3 appears in entry criteria | **A** | CD-1m and `reach`/`terminal`/`servedClass` re-phased to M1 only (§8.3); D3 in M1's entry; the v2 row says D3 is not needed (§9.3) |
| R2-CONFORMANCE-8 | medium | D24(b) inserts ladder entries and a parallel track as "text-only" | **OD (D24)** | r3 withdraws the parallel-track recommendation (§1.4); D24's options state each placement and departure (§10.5); RT-13 |
| R2-CONFORMANCE-9 | low | Owner brief: "nothing ships before v1 is validated and v2 exists" | **A** | The owner brief now says v1.1 adds H1–H7, including `LEDGER_UNAVAILABLE` if D5 is ruled that way |
| R2-CONFORMANCE-10 | low | A targetSdk bump has no RT row | **A** | RT-11, D29, C-14; T14; M1 gate 9 |
| R2-CONFORMANCE-11 | low | The editorial table's signing key and claim-capping use change v2 P6 without a ruling | **A** | RT-12, a D18 sub-decision, the owner's key-custody task (§5.3 row, C-16) |
| R2-CONFORMANCE-12 | low | D12 bundles five contract additions with trims | **A** | D12.1–D12.5 separate sign-offs, D12.6 withdrawals; deferrals and rejections moved to §10.0d |
| R2-OVERCLAIM-1 | high | `decodeObs` is peer-shapeable; the stated bound is false | **A** | §5.7 rewritten (requester-controlled end-to-end time over requester-parsed bytes; claims only lowered; best-quartile state; byte cap with strikes); the bound restated with its residuals; M08 vectors for burst-at-end, split-chunk, padding and truncation (`LAB_SPEC.md` §6.6); §5.11 row; K11; X35 |
| R2-OVERCLAIM-2 | high | The owner brief hides D14's third amendment; the recording standard is selective | **A** | One rule applied everywhere (AD-2's principle); the owner brief lists D14 and the net count (five amendments if every recommendation is taken) |
| R2-OVERCLAIM-3 | high | The Deck figures do not follow from their stated basis | **A** | §7.1 shows both bases with full arithmetic (A12b-lo = the reviewer's FP32 basis; A12b-hi = one community measurement that contradicts it) and headlines the envelope (~1.5–2.8× total, 0.8–4× TTFT), stating that the Deck may be no faster on prefill-heavy requests; §0.8; the owner brief; Appendix B |
| R2-OVERCLAIM-4 | medium | "MLPerf-comparable" labels; no trademark or results-messaging check | **A** | §6.1 wording rule (the label deleted; a descriptive note disabled until the owner's check; never on default runs); [F50]; D18 sub-decision; K33; B33; law LM-9, vector M05-MLP |
| R2-OVERCLAIM-5 | medium | The quiescence gate's fallback cannot observe overlay traffic | **A** | M1 gate 5 (§9.3): with the overlay on, only a root on-device capture passes it; the gateway fallback only in a LAN-only configuration with its positive control observed; otherwise it stays open. The same rule in the Windows, macOS, Android and Ubuntu Touch device checklists |
| R2-OVERCLAIM-6 | medium | IC-2 hard-codes TLS-record counting, unimplementable on Network.framework | **A** | IC-2 counts exact application bytes plus a labelled per-session overhead (§8.2); §8.4 byte accounting per stack; [F51], [A36], [IA07]; L-L15 per stack; X38 |
| R2-OVERCLAIM-7 | medium | The event table omits frames; FC-2 needs the write that failed | **A** | §8.4 per-frame table covering every frame of §4.3 (each `PAIR_*` frame, `ERROR`, `MANIFEST_REQ`, `CANCEL`, extensions); FC-2 closes without `GOAWAY`; L-L16 over a named frame list with non-vacuity |
| R2-OVERCLAIM-8 | medium | Self-oracle tags can be cleared without independence | **A** | §3.5 oracle rule (independence = no access to the generator source, recorded in `PROGRESS.md`; the same-session Kotlin/Python agreement does not clear it); `LAB_SPEC.md` §4.10; K42 |
| R2-OVERCLAIM-9 | medium | LP-1/LP-2 are incoherent with lend-screen (PF) lenders | **A** | §7.4 LP-0 classification with the PF exception; LP-1, LP-2 rewritten; W07-presence PF vectors; X37 |
| R2-OVERCLAIM-10 | low | CameraX and LoadGen missing from CD-D; §0.8 lists a subset as complete | **A** | CD-D lists CameraX and a conditional LoadGen entry; AP-1 lists the Android permission additions; §0.8 points to §8.3's count instead of listing a subset |
| R2-OVERCLAIM-11 | low | The NPU claim is broader than its source | **A** | §0.3 and §6.0: NPU-accelerated Llama 3.1 8B on Snapdragon 8 Elite Gen 5 only; Dimensity 9500 and Exynos 2600 "supported (LLM NPU path not stated)" |
| R2-OVERCLAIM-12 | low | FILE pins: never stored vs 90-day and 30-day TTLs | **A** | §5.4: per-export matches never stored; one TTL (90-day re-confirmation) only for a per-subscriber key (D6(e)); D2(b)'s 30-day TTL gone with AD-2; X39 |
| R2-DIRECTIVES-1 | high | D4(b1) and D24(b) reorder the roadmap while claiming directive D | **A** | As R2-CONFORMANCE-1; plus D24's options state their departures and RT-13 records any placement inside mesh-1's window |
| R2-DIRECTIVES-2 | high | MLPerf Client already covers desktops and Apple; D-iii is me-too | **A** | [F49] (MLPerf Client v1.6, fetched); §0.3 and §6.0: the differentiator list is exactly three; D-iii deleted; MLPerf Client as the desktop baseline (spike S-B1d); the standalone desktop CLI dropped (D7, LD-8); B34; the owner brief |
| R2-DIRECTIVES-3 | medium | Default runs produce no comparable row; "nothing else ships" overstated | **A** | §0.3 and §6.1 rule 2 (default runs never carry the note; comparability exists only for L1 opt-in users after S-B1); D18 option (a2) (L1 default at first run for licence-accepters); the owner brief says "no project we found" |
| R2-DIRECTIVES-4 | medium | Holon completeness is asserted for nodes that cannot serve their own apps | **A** | §0.1 holon table; §2.1 "Delivered by" column and per-role delivery; §7.8 statement; D15(f) (`Embedded` in AsomKit) offered, not recommended |
| R2-DIRECTIVES-5 | medium | `reason = sleeping` still on the wire; `qb` contradicts LP-1 | **A** | §3.2 item 1 (only `fsm` on the wire); LP-1 names `qb` as an exception; IC-3(ii) names the three presence-correlated outputs; §7.12 row; X36 |
| R2-DIRECTIVES-6 | medium | The first Apple deliverable is a me-too benchmark app; M4 gated on it | **OD (D24)** | D24(a): no separate iOS benchmark phase; one asom app hosts bench, pairing and verifier; M4 is not gated on M3 (iOS-D1(b) absorbed); §9.3 M1b row; §9.4 |
| R2-DIRECTIVES-7 | low | The per-message claim is not carried through the event table | **A** | As R2-OVERCLAIM-7 |
| R2-DIRECTIVES-8 | low | §5.5 titled "Decision"; §3.4 puts conformance at the repo root | **A** | §5.5 retitled (a recommendation, §10.0d); §3.5 "in `lab/conformance/`, promoted only under D23" |
| R2-DIRECTIVES-9 | low | L0.2 builds mesh-only code under D1a; L0.3 pins L1 ahead of D18 | **A** | AD-4 now authorises the whole lab and AD-2 decides the mesh invariants, so the sunk-cost concern is gone for D2; the ClaimTracker moves to L0.6; the L1 pins are a D18-conditional table; the one piece built ahead of an open ruling (the D5(b) ledger) is labelled so (§9.1, §10.0b) |
| R2-DIRECTIVES-10 | low | The owner-brief count is not verbatim | **A** | The owner brief repeats §8.3's count line byte-for-byte (checked by a diff when both files were written) |
| R2-DIRECTIVES-11 | low | A Mac could lend in M1 with no gate | **A** | D28 decides whether a Mac or Windows PC lends in M1; §9.3 gates 13 (Windows) and 14 (macOS) |

### 12.2 Owner directives and acting decisions applied (r3)

| Directive | Where the text changed |
|---|---|
| D-A (Apple) | §3.1 rows; §3.4.4, §3.4.5; D15, D16, D24 as "when and how"; D22 (Team ID) |
| D-B (holonic) | §0.1 holon table; §2.1; §10.0a (limited to the directive's words); §10.0d |
| D-C (positioning) | §0.3; §6.0–§6.2 (three differentiators, wording rule); D7; D18; [F49][F50]; K25, K33 |
| D-D (order, v1 first) | §0.4; §9 (nothing ships before V1-close) |
| D-E (Windows, Ubuntu Touch) | §1.1 rows; §3.1; §3.4.3; §3.4.6; §9.3 M1b; D27, D28 |
| D-F (delegation) | the six platform sections, `LAB_SPEC.md`, `PLATFORM_PLAN.md` |
| AD-1 (sequencing) | §0.4; §1.4; §9; §10.0b (r2's D4 decided); RT-8, RT-10 |
| AD-2 (Amendment 3) | §8.1; §8.2 (Amendment 2 verbatim; IC-1 profile; IC-2/IC-3/IC-4 exact text; roadmap §13 text); §10.0b (r2's D2 decided); D6, D14 re-put; RT-1, RT-14 |
| AD-3 (placement) | §3.6; `PLATFORM_PLAN.md` |
| AD-4 (lab) | §9.1 (L0.1–L0.7); §10.0b (r2's D1a, D1b decided) |
| AD-5 (honesty) | §9 evidence labels; §3.1 "CI proves / stays NDV" columns |
| AD-6 (models) | `LAB_SPEC.md` written for Sonnet builders without the 45k-word brief |

### 12.3 Platform-section corrections: which were taken

| Section | Correction asked | Taken? | Where |
|---|---|---|---|
| `linux.md` §11.2 | 1 SteamOS never-block-lock and polkit precondition in §3.1 | yes | §3.1, §3.2 |
| | 2 §3.2 "one sleep rule" false on SteamOS; wire carries `fsm` only | yes | §3.2, X44, X36 |
| | 3 Deck row: replace the FP32 basis with the LF35 derivation | **in part**: both bases shown, headline is their envelope (the reviewer's FP32 arithmetic and LF35 disagree; neither is the owner's Deck) | §7.1, A12b-lo/hi |
| | 4 desktop coverage not a differentiator; no standalone Linux bench | yes | §6.0, D7 |
| | 5 weak-host line in IC-1's documentation | yes | §8.2, C18, T13 |
| | 6 A10 Linux half settled | yes | Appendix B A10 |
| | 7 probe parsers: one home | yes | X42, §9.1 |
| | 8 T1 sealing; no Deck TPM tier | yes | §3.1 |
| `windows.md` | "Windows is out of scope" superseded; effort additive; W-D11 named in the D2/D25 ruling | yes | §3.1, §3.4.3, §9.4, D25 |
| `macos.md` | ES256 not Ed25519; replace the 4–6-week estimate; helper outside `apple/`; A03 replaced by the helper design | yes | C2, §9.4, §3.4.4, §5.1 |
| `ios.md` §11.2 | 1 iPhone PF "NO"; holon note | yes | §3.1, §2.1 |
| | 2 D16 "re-authentication to leave" replaced | yes | D16 |
| | 3 LP-1 PF exception | yes | §7.4 |
| | 4 IC-2 platform-neutral byte wording + Network.framework method | yes | §8.2, §8.4 |
| | 5 B6/B9 iOS rule (stop GPU work on resign-active) | yes | B36, §3.2 item 4 |
| | 6 `trust.md` §4.2: `UIScreen.isCaptured` deprecated in iOS 27; use `UITraitCollection.sceneCaptureState` [IF38] | yes (recorded for M4; `trust.md` is amended by this row) | this row |
| | 7 ML-DSA in the Secure Enclave (parked); low-S producer rule | yes | C2, §5.1 |
| | 8 swift-crypto enters the iOS app at M4 | yes | CD-D |
| | 9 Apple mobile benchmarking covered by MLPerf Client on iPad Pro | yes | §6.0 |
| | 10 M4 not gated on M3 | yes | D24, §9.3 |
| | 11 `apple/` at the repo root | yes | X43, §3.6 |
| | 12 Appendix A additions | yes, by reference: platform fact ids are cited in place and their tables are the sources | tags note in the header |
| `ubuntu-touch.md` §7.7 | 1 split `desktop/node` into `node-core` + host | yes | X41, §3.6 |
| | 2 an Ubuntu Touch column | yes | §3.1 |
| | 3 IC-7 names QML/Lomiri | yes | §8.2 IC-7 |
| | 4 PF exception for UT-2 | yes | §7.4 |
| | 5 byte-based observation is required | yes | §5.7 |
| `android-mesh.md` §11.2 | 1 A05 → fact; K3 lowered; AF04; new risk R-A1 | yes | T14, K3, K31, Appendix B |
| | 2 A06 → fact for 17 r1 with caveat | yes | T14, Appendix B |
| | 3 A04 → fact for AOSP; OEM open | yes | Appendix B |
| | 4 AF21 Tailscale Android switch | yes | D8, K16 |
| | 5 thermal listener on API 36+ | yes | B28 |
| | 6 T9 vs PA-charging | yes | T9, D16 |
| | 7 LP-1 PF exception | yes | §7.4 |
| | 8 CameraX; permission additions as SIGN-OFF lines | yes | CD-D, AP-1 |
| | 9 targetSdk RT row | yes | RT-11, D29 |
| | 10 dev verification; no update check in the matrix | yes | §3.1, C14, D22 |
| | 11 Conscrypt knob gaps into S-A9 | yes | Appendix B A21 |

**Taken in part, and why:** only `linux.md`'s Deck derivation, because the reviewer and the platform section rely on bases that disagree by 3–5× for prefill; publishing either alone would repeat the r2 mistake of printing a number that one defensible basis contradicts.

*The history sections below are retained unchanged from r2 except for their headings. Decision ids in them are r2 ids: r2's D1a/D1b are now AD-4, D2 is AD-2, D4 is AD-1 (§10.0).*

### 12.4 History: round-1 final review (`REVIEW_ROUND1.md`), as dispositioned in r2

**Key:**
- **A** = accepted, and the text or design changed;
- **OD** = converted to an owner decision (the text also changed to put it plainly);
- **R** = rejected, with the reason.

**Result: 24 findings; 20 A, 4 OD, 0 R.** None was rejected: each identified a real defect, and where a reviewer offered alternatives, the one taken is named. The full log, with each reviewer's suggested change beside what r2 did, is `REVIEW_ROUND1_DISPOSITION.md`.

| ID | Sev. | Issue (short) | Disp. | Where the text changed |
|---|---|---|---|---|
| CONFORMANCE-1 | high | The owner brief and D12(a) undercount frozen-contract changes | **A** | §8.3 "complete count" line, repeated in the owner brief; D12(a) corrected; the owner brief lists D5, D7 and D11 as changing frozen text |
| CONFORMANCE-2 | high | SIGN-OFF items map to no decision; `:bench-app`/`:bench-cli` omitted | **A** | §8.3: a Decision column on every row, and "an item with no ruled decision is not approved"; new **D23**; MOD-1 adds both modules; CB1 becomes RT-5 |
| CONFORMANCE-3 | high | §0 "decided" list pre-empts owner decisions; the reach rule framed as engineering | **A** | §0.5 retitled "Recommended, pending owner ruling", each tied to its decision; the reach rule is CD-1m, "a change to the meaning of frozen §5.4", ruled in D3 |
| CONFORMANCE-4 | high | The header uses `reach` while the row's egress keeps the served class, so Invariant 9's purpose fails | **A** | §7.6 and §8.4: the terminal row's `egress` = `reach`; new `servedClass` and `terminal` columns; law L-L5b; vector W01b-reach; §8.1 row 9; M1 gate 11 |
| CONFORMANCE-5 | high | D6 hides that IC-5 and IC-6 are third-amendment material by the letter | **OD** | §8.2 "literal reading" paragraph; D6 now offers the record choice (R1 fold into Amendment 1 with the text updated / R2 third amendment) and option (d); §1.2 C-5; owner brief D6 row |
| CONFORMANCE-6 | medium | Per-host-app iOS pairing contradicts IC-4 and Invariant 5 | **A** | Took the reviewer's second alternative: **device-level pairing** through a Team-ID keychain access group (§7.8; A25; spike S-A12); third-party host apps become D15(e), flagged as third-amendment material; X1 |
| CONFORMANCE-7 | medium | B24 ships the desktop CLI (and macOS packaging) inside v2; "not a new subsystem" unaddressed | **A** | v2 ships the Android daemon shell only; the CLIs move to D-v2 and macOS to D24 (§6.4, B24, X31, §9.3, §9.4); D7 now amends "This is not a new subsystem" too |
| CONFORMANCE-8 | medium | The per-app cloud ban is v2.5; under D4(b) X13's rejection rests on nothing | **OD** | §1.4 row (b); D4 split into (b1) pull forward the cloud-ban column only and (b2) accept the loss; X13 made conditional; RT-8 |
| CONFORMANCE-9 | medium | IC-1 drops "every remote request ledgered on both nodes"; interval rows are an unflagged seventh change | **OD** | IC-1 item 7; IC-2 has wording per D5 option; D5 re-cut, with the recommendation changed to (b) per-message rows, which meets the sanctioned text |
| CONFORMANCE-10 | medium | D1 cites only CLAUDE.md; the lab "touches no build path" is false; L0.4–L0.6 run ahead of D2/D3/D5 | **OD** | D1 split into **D1a** (L0.1–L0.3, L0.7) and **D1b** (L0.4–L0.6 after D2, D3, D5); §9.1 exceptions table (CLAUDE.md, roadmap §0, v2 entry criteria, v4 "Do not cold-execute", directive D); "adds `lab/` and one CI job" stated; conformance moved inside `lab/` until promotion |
| CONFORMANCE-11 | low | Other-owner slots (`X`, audience `"other"`) remain in normative schemas | **A** | §5.2 audience enum; §8.5 `P ⊆ {T, O, C}`; kept only as a D20 note |
| CONFORMANCE-12 | low | "Providers initiate none" contradicts quiescence rule 3 | **A** | §8.6 "in its lending role a node initiates no connection except under rule 3"; IC-3(vi) matches; §7.4 exchange row |
| OVERCLAIM-1 | high | "Nothing about user presence goes on the wire" is false | **A** | IC-3(ii) reworded to say presence is inferable; four fields removed from `asom.state/1`; a 10-min hold-down; laws LP-1/LP-2 and W07-presence (§7.4); channel list (§7.12); §2.6; X5; K28 |
| OVERCLAIM-2 | high | Peer-reported `qb` and `tb` undermine the ClaimTracker | **A** | §5.7: discards and `hot` from requester-observed values only; TTFT by lower quartile; a discard budget that clamps the prior; three new adversary M08 vectors; X32; K11; §5.11 |
| OVERCLAIM-3 | high | The owner brief and D12 undercount (same substance as CONFORMANCE-1) | **A** | As CONFORMANCE-1 |
| OVERCLAIM-4 | high | A per-export signature proves nothing without an out-of-band fingerprint; no subscription exists | **A** | The owner brief says it plainly; §5.4 **fingerprint-comparison step**, with the honest equivalence to comparing a hash; §5.8 S2 scope; §1.1 R3d/R3e "third-party subscription NOT MET"; D6(e) per-subscriber key; §5.11 new row; K12 |
| OVERCLAIM-5 | medium | The 10× figure rests on desktop-class decode the Deck and Dell may not reach | **A** | §7.1 lender-class table (phone, Deck, CPU-only desktop, M4 Pro/GPU), with A12a–d; the Deck estimate is ~1.6–2.6×; §0.8 and the owner brief carry it; the D-v2 gate measures the owner's devices |
| OVERCLAIM-6 | medium | "Every transmission is ledgered" vs interval rows; lender STATE rows unspecified; byte law untestable | **A** | D5(b) per-message control rows on both sides (durable before send or reply); IC-2 wording per option; byte accounting at the TLS record layer, handshakes on `DIAL`/`SESSION` rows; L-L15 made testable with a record tap; L-L16; FC-2 |
| OVERCLAIM-7 | medium | Tolerated TLS resumption defeats T9's keyguard binding | **A** | T2: the client never offers resumption (fresh `SSLContext` per dial; Network.framework option, A26); W08 asserts no PSK and a client CertificateVerify in every session; §4.4 states the rooted-client limit |
| OVERCLAIM-8 | medium | The quiescence gate can pass without testing the law | **A** | M1 gate (5) rewritten: capture on the phone, all interfaces, mesh on, an app opted in, `state` granted; any port; ledger rows asserted; a positive control; repeated on a desktop borrower |
| OVERCLAIM-9 | medium | The §8.1 KMP/"do not start" row, IC-4 and IC-1's ranges contradict the design | **A** | §8.1 row "requires owner exceptions (D1a, D1b)"; IC-4 reworded for the pairing window; IC-1: the range list applies to (b) only, and overlay addresses are eligible by interface |
| OVERCLAIM-10 | low | F26 (VERIFIED) overstates developer verification's 2026 scope | **A** | Re-fetched 2026-09-30 and **confirmed**: F26 restated verbatim; D22 and K20 adjusted |
| OVERCLAIM-11 | low | `seq` "monotonic without trusted state"; file exports leak the export second | **A** | §5.2 `seq` comment states its condition; file audience omits `seq`, `challenge` and `expiresAtMs` and truncates `issuedAtMs` to the day; §5.8; B32; M06-file vectors |
| OVERCLAIM-12 | low | `includeBuild("..")` pulls Android modules in whenever an SDK is present | **A** | §3.5: project-directory mapping that never evaluates the root settings; isolation checks incl. `buildEnvironment` with the SDK present; §9.1 rules; X19. A third mechanism was chosen over the reviewer's two; the reasons are in §3.5 |

### 12.5 History: owner directives applied in r2

| Directive | Where the text changed |
|---|---|
| A (Apple in scope) | Header; §0.2; §1.1 R1a rows; §1.2 C-2, C-11; §1.4 Apple ordering; §3.1 note; §3.4 runners; §3.5 `lab/apple`; §8.1 rows 4, 7, 8; §8.2 IC-7/IC-8 preface; §8.3 RT-7, RT-9; §9.1 L0.7; §9.3 D-v2, M3; §10.0 D13 DECIDED; D14, D15, D16 converted to "when and how"; new D24; K29 |
| B (symmetric, holonic) | §0.1, §0.2, §0.7; §2.1–§2.3, §2.6 rewritten; §3.1 requester row and the long-horizon table; T5 wording; §4.3; §8.6; §9.3 M1 (desktop CLI borrowing, gate 12), M2; §10.0 D0 DECIDED; new D25, D26; X34; K27 |
| C (verified positioning) | §0.3; §0.8; §1.1 R3a; §1.2 C-13; §6 rewritten (§6.0–§6.3, B29–B31, §6.9); D7; D18 rewritten; §9.3 v2 gate; F40–F48; A27–A31, A34; K25, K26, K30; X33 |
| D (ordering) | §0.2, §0.4; §1.2 C-8; §1.4; §9 gate discipline; D1a/D1b; D4(c) marked not viable; D24(c) not viable |

### 12.6 History: the synthesis-declared unresolved items, status in r2 (r3 status: Appendix B)

| Item | Status in r2 |
|---|---|
| Owner rulings pending on all 22 decisions | 26 open, 2 decided (D0, D13). Directive A settled r1's D13; D1 split; D23–D26 new |
| Tailscale phone-client log upload under Headscale | Still unverified [A10]; D8 unchanged |
| Android 17 local-network permission vs loopback or VPN | Still untested [A05][A06]; H5 hard rule unchanged |
| Spikes S-A2, S-A3, S-A9, S-A10, S-A11 not run | Still open. New: S-A12 (keychain group), S-B1 (MLPerf definitions), S-B2 (LoadGen). S-A2, S-A10 and S-A12 can run in L0.7 if a Mac exists |
| Thresholds provisional and uncalibrated | Still provisional [A11]; the tracker's constants are unchanged, but its inputs are now requester-only |
| GPU cancellation, chunked ubatches, time-to-cool, plan fit | Unmeasured; B6, B8 unchanged |
| Steam Deck Game Mode, inhibitors, fdinfo | Unverified [A07] |
| KV re-prefill cost on Deck/Dell (D19) | Unmeasured; D19 unchanged; the D-v2 owner-device benchmark now also measures prefill |
| Effort estimates | Revised to 72–122 engineer-weeks; still rough [A24] |
| The Dell's OS, an Apple-silicon Mac, CUDA terms | Still owner inputs. The Dell's **GPU** is added (it decides the §7.1 row); the Mac matters more under directive A |
| Bench set sha256 pins | Still to confirm by download (Q1). The L1 file source is new (D18) |
| Thermal polling conflict (500 ms vs 10 s) | Unchanged: the conservative rule, pending device confirmation |

### 12.7 History: round-0 critique log (every critical and high issue from the 18 section critiques; retained from r1, with r2 corrections inline)

Key: **A** = accepted, and the design changed; **A-part** = accepted in part, with the remainder explained; **OD** = turned into an owner decision; **dup** = same substance as the listed row. No critical or high issue was rejected outright.

| # | Section / lens | Issue (short) | Sev. | Disposition | Where the design changed |
|---|---|---|---|---|---|
| 1 | platforms / conformance | iOS delegated routing hides a third amendment and Invariant 5; `RemoteMesh` is not "a translation"; Option 1 cost rests on it | critical | **A + OD** | Delegation removed (X1, §7.8); `RemoteMesh` listed as a 10A contract addition (CD-DOC); Option 1 cost restated for a single-provider iOS requester (§3.4); delegation is D15(c) and labelled a further third amendment **r2:** per-host-app pairing replaced by device-level pairing (CONFORMANCE-6, §7.8) |
| 2 | platforms / conformance | `lan` false for relayed overlay; the frozen `X-Asom-Egress` value set must grow | high | **A** | `peer` + `peerPath` (D3); CD-1 flagged SIGN-OFF; relay and operator exposure disclosed (§4.4, D8) |
| 3 | platforms / conformance | "Invariant 1 honoured" is false: ARMED adverts, live state, presence | high | **A** | Invariant 1 marked needs-amendment (§8.1, IC-3, D2); presence removed from the wire (§7.4); providers initiate nothing (§8.6); control traffic ledgered (§8.4) **r2 correction:** presence *fields* are removed, but presence stays inferable from declines and availability; IC-3(ii) and §7.12 now say so (OVERCLAIM-1) |
| 4 | platforms / conformance | OD4 re-sequencing hides the lost protections, the consent-scope widening and Route-Reason pull-forward | high | **OD + A** | D4 lists the lost protections and replacements (§1.4); per-app default off with re-consent (§8.5); route-reason header only with v2.5 (§7.7) |
| 5 | platforms / conformance *(medium, logged because it touches the frozen law)* | New modules change the brief §4 law and `jvmTest` | medium | **A** | Separate lab build; promotion SIGN-OFF (§3.5, X19) **r2:** the lab mechanism is corrected to project-directory mapping (OVERCLAIM-12, §3.5) |
| 6 | platforms / security | Home node H as relay and confused deputy | high | **A** (dup of #1) | Plus per-host-app pairing and no silent cloud downgrade (§7.8). **r2:** per-host-app pairing replaced by device-level pairing (CONFORMANCE-6) |
| 7 | platforms / security | `systemd --user`: same-uid processes can steal the key and impersonate the node; Deck recommended as H | high | **A** | Dedicated-user mode; Deck labelled shared-uid and not the privacy-critical provider; limit stated (T17) |
| 8 | platforms / security | Token and ledger printed to stdout → journal; port squatting on a TCP loopback API | high | **A** | H3; T17(c)(d); Unix socket with uid checks in both directions; no TCP loopback API on desktop |
| 9 | platforms / feasibility | Linux not always-on (GNOME suspend, lid, no pre-sleep event) | high | **A** | §3.2: `os_sleep_imminent`, sleep inhibitor for all of SERVING, wording "while awake", M1 idle gate; Deck Game Mode is A07 |
| 10 | platforms / feasibility | S1 pulls most of v2 forward; no estimates for S0–S4 | high | **A** | D-v2 phase explicit (§9.3); D4 lists what is pulled; effort table (§9.4) |
| 11 | platforms / feasibility | No automated multi-node test (loopback forbidden; no tailnet in CI) | high | **A** | M1 gate (3): network-namespace multi-node CI suite with fault script and row-count assertions; lab uses test-only transports (§9) |
| 12 | trust / conformance | The Invariant 1 "clarification" is a relaxation, a second amendment to Invariant 1 | high | **A + OD** | IC-3 labelled amendment text, narrowest form (digest approval, bands, no presence); D2(b) gives the no-IC-3 alternative **r2:** IC-3(ii) no longer claims presence cannot be inferred (OVERCLAIM-1) |
| 13 | trust / conformance | OD-1 leans on §7 against "No other invariant changes"; the sanctioned text is rewritten | high | **OD** | D2 quotes the literal reading and itemises the six text changes (§8.2) |
| 14 | trust / conformance | `lan` class and clause (d) false for overlay relays | high | **A** | IC-2 rewritten; `peerPath` |
| 15 | trust / security | Revocation hints let a stolen peer disable revocation (pre-emptive suspension) | high | **A** (deferral + binding fixes) | Hints deferred; the fixes are mandatory if ever built (T11) |
| 16 | trust / security | `routeEnabled` on by default, contradicting §4.8's safety claim | high | **A** | T5, law L15, new-peer cap |
| 17 | trust / security | Stolen locked phone; presence in STATE; no clone detection | high | **A-part** | T9 (keyguard leaf, session age), T10 (clone signal), presence removed (§7.4). Battery band and charging remain for own peers holding `state`, because F11 needs them |
| 18 | trust / feasibility | iOS has no platform X.509 parser for the verifier | high | **A** | T3, C4, S-A10; dependency SIGN-OFF (CD-D) |
| 19 | trust / feasibility | Vectors test pure functions, not TLS wiring (risk R-1) | high | **A** | W08 live adversarial suite as a gate; CI lanes named (§3.4) |
| 20 | trust / feasibility | Clause (d) false (DERP) | high | **A** (dup of #14) | — |
| 21 | manifest / conformance | Every export or auto-share option is a third amendment by the letter; fallback (b) not supported by the verifier | high | **OD + A** | D2/D6 state the literal reading; D2(b) specifies the user-initiated share frame verified in a FILE-pinned context **r2 correction:** r1's D6 did not in fact state the literal reading; r2 adds it (CONFORMANCE-5, §8.2, D6) |
| 22 | manifest / conformance | MC-5: the catalogue becomes a trust-anchor channel | high | **A** | Anchors only in signed releases; CD-27 rejected; revocation mirror owner-signed and monotone (§5.5) |
| 23 | manifest / conformance | MANIFEST sends folded into per-session rows | high | **A** | Per-presentation rows plus full sent-bytes history (§5.8, §8.4) |
| 24 | manifest / security | `evaluateAndroid` scans from the leaf: A2 forgeable by any Android owner | critical | **A** | Verified [F07]; corrected nearest-to-root rule and CA checks; A2 deferred and display-only; the third-party offload row and the A2 prior bonus removed (§5.5, §5.7) |
| 25 | manifest / security | Claim tracker defeated by `seq` reset, the hot branch and backend-keyed windows | high | **A** | §5.7: strikes survive `seq`; hot from requester data only; prior uses the same quantity; key by file; observed median after 3 **r2 correction:** r1 still decided `hot` from the peer-reported `st.tb`; r2 uses the requester's own trend only (OVERCLAIM-2) |
| 26 | manifest / security | Catalogue anchors; forged revocation freshness | high | **A** (dup of #22) | Label "checked against the maintainer's mirror dated X" |
| 27 | manifest / security | Automatic benchmark sends: frame counts only; other-owner default on; transferable NIK-signed statements | high | **A** | Digest approval; per-message rows; other-owner out (D20); exports never NIK-signed (§5.3) |
| 28 | manifest / feasibility | RKP validity periods not checked; no durable A2 on modern phones | high | **A** | Verified [F07]; A2 deferred; time-bounded A2 when built (§5.5) |
| 29 | manifest / feasibility | Integer overflow and division by zero in consistency and tracker (Swift traps; Kotlin wraps; Python hides it) | high | **A** | Physical maxima, checked arithmetic, Python int64 emulation, vectors (§5.2) |
| 30 | manifest / feasibility | Tracker flags honest busy peers as DISCREPANT | high | **A** | Discard rules; peer-wide discount only after ≥ 2 keys; constants PROVISIONAL with a calibration gate. Tension with #25 resolved: peer-reported state can only *discard* an observation, never lower the expectation, and > 50% discards → WEAK (§5.7) **r2 correction:** r1's resolution still let the peer-reported `qb` discard observations and `st.tb` lower the expectation. r2 discards only on requester-observed values and adds a discard budget (OVERCLAIM-2, §5.7) |
| 31 | benchmark / conformance | `field[]` (usage-derived) in the signed document sent to peers | high | **A** | B1 |
| 32 | benchmark / conformance | Text export is not unlinkable; its hash sits in the signed document | high | **A** | B3 (hash removed), B19 (anonymous renderer; "unlinkable" struck) |
| 33 | benchmark / security | Renderer prints producer-supplied `derived`; no re-derivation | high | **A** | B3; verifier step 15a `DERIVATION_MISMATCH`; `confVersion` floor |
| 34 | benchmark / security | Control characters and ESC/OSC injection via engine strings | high | **A** | B4 (ids/enums; reject at parse; printable ASCII only; CLI filter) |
| 35 | benchmark / security | `field[]`, `custom[]` exposed to other and file audiences | high | **A** | B1, B2; per-audience projections (§5.8) |
| 36 | benchmark / feasibility | llama.cpp abort is CPU-only; the 1 s promises are false on GPU | high | **A** | B6 [F18] |
| 37 | benchmark / feasibility | Thermal drift makes reps non-exchangeable | high | **A** | B7 |
| 38 | benchmark / feasibility | Wall-cap budget inconsistent; absolute COOL gate unreachable while charging | high | **A** | B8 (invariant test, relative gate, time-to-COOL spike) |
| 39 | router / conformance | Usability gate applied to SELF changes v2 `auto` | high | **A** | §7.5, RL1 oracle, D9 |
| 40 | router / conformance | "Third amendment: none" presents escalation as settled | high | **A** | D2 literal reading; the router's invariant row replaced (§8.1) |
| 41 | router / conformance | `user.active` and `localActive` in STATE and `st` (usage data, beyond the clause) | high | **A** | §7.4 minimised schema; `st` only to own-class peers with `state` **r2:** four more presence-correlated fields removed (§7.4) |
| 42 | router / conformance | `fastest` re-sorts cloud (RL1 vs RL10 contradiction) | high | **A** | §7.5, RL10 amended |
| 43 | router / security | Error-path egress unspecified (`local` stamped after a peer saw content) | high | **A** | `reach`, RL15b, vectors (§7.6) **r2:** the terminal row's `egress` now also equals `reach` (CONFORMANCE-4) |
| 44 | router / security | Usability gate on SELF | high | **A** (dup of #39) | — |
| 45 | router / security | Eligibility not re-checked at send (policy toggles do not fail closed) | high | **A** | §7.3 F1; RL4 at send instant; M1 fault test |
| 46 | router / security | Presence oracle via route reasons, failover values and a shared node tag | high | **A** | Coarse header projection; `X-Asom-Node` withdrawn; per-app alias (CD-2); residual channel stated (§7.7) |
| 47 | router / feasibility | Usability gate on SELF | high | **A** (dup of #39) | — |
| 48 | router / feasibility | The v1 Router is not pure (wall clock, live objects); RL21 unreachable | high | **A** | §7.9 frozen adapters, pure breaker pinned by R06, RL21 scoped |
| 49 | contract / conformance | Monotone reach is broken by the router's orders | high | **A** | `reach` field (X4) **r2:** the terminal row's `egress` now also equals `reach` (CONFORMANCE-4) |
| 50 | contract / conformance | The registry never ruled on RC-1…RC-9 (error codes, §5.9 change) | high | **A** | §8.3 rules on every one: RC-1/2 → CD-22; RC-3 → CD-15; RC-4 → CD-RR; RC-5 → CD-FO; RC-6 → CD-6b + rejection; RC-7/RC-8 deferred; RC-9 → D11; RC-10 → CD-C |
| 51 | contract / conformance | The TLS server certificate reaches any connecting party; IC-1/IC-2 claims false; inbound refusals unledgered | high | **A** | T13 disclosure + S-A11; IC-2 reworded; `INBOUND_REFUSED` interval rows (D5) |
| 52 | contract / conformance | `nodeTag` in `X-Asom-Served-By` is a cross-app identifier | high | **A** | Per-app alias (CD-2) |
| 53 | contract / security | NIK pin exposed in four contexts; LAN listener disclosure | high | **A-part + OD** | Alias in headers; exports use per-export keys; listener binding restricted; S-A11. Removing LAN-direct from mesh-1 is left to the owner (D8), because the overlay has its own verified exposure [F10] |
| 54 | contract / security | Signed export verification is TOFU-grade for first-contact subscribers | high | **A** | Per-export key + out-of-band fingerprint; D6 recommendation changed; wording (§5.4) **r2:** the comparison step is now specified, with its honest equivalence to a hash comparison (OVERCLAIM-4, §5.4) |
| 55 | contract / security | Peer-controlled strings flow into headers and rows; peer error bodies relayed | high | **A** | T15; X23 |
| 56 | contract / security | Stolen or cloned provider; key in the backed-up state directory | high | **A** | T17(f) key location and wrapping, dedicated-user mode, T10 clone signal; M1 device check: a provider rebooted to the login screen accepts no offer unless "serve while logged out" is on |
| 57 | contract / feasibility | Monotone reach broken | high | **A** (dup of #49) | — |
| 58 | contract / feasibility | Intent append fails open (`runCatching` degrade) | high | **A** | Fail-closed rules, L-L13, `LEDGER_UNAVAILABLE` (D5) **r2:** `LEDGER_UNAVAILABLE` is now flagged as a frozen-contract change (CD-LU, RT-3, D5) |
| 59 | contract / feasibility | No phase owns the desktop port of v2 | high | **A** | D-v2 (§9.3), estimates (§9.4) |

**Medium and low issues** are addressed in the change tables where applicable (§3.3, §4.2, §5.2–§5.9, §6.5, §7.3–§7.11, §8.4). They are not logged row by row. Examples:
- the SNI and ALPN knobs;
- the SUSPENDED pairing path;
- base64 and length units;
- contention measurement;
- chunk sizing;
- `busyForMs` decay;
- the RV4/RV9 citation slips (`router.md` §1.1 should cite the M1 8-GPU row consistently and label RV4 a single-user misconfiguration report).

---

## Appendix A — Verified facts (load-bearing ones; source; who verified)

"This synthesis" means re-fetched while writing this brief. Other facts were verified by the named section or critique in this session and are relied on here.

| ID | Fact | Source | Conf. |
|---|---|---|---|
| F01 | iOS suspends backgrounded apps; no mechanism runs a network server in the background; Apple DTS advises closing listeners when eligible for suspension | Apple forums 685525 (2026-01-09), 757385, 772637 (`platforms.md` V01) | high |
| F02 | Local Network privacy (iOS 14+, macOS 15+): outgoing LAN TCP needs it; accepting inbound does not; VPN and cellular are not "local network"; launchd agents are not exempt, daemons and Terminal tools are | Apple TN3179 (2026-02-17) (`platforms.md` V02, `trust.md` V1) | high |
| F03 | ATS governs URLSession, not Network.framework; URLSession trust can be tightened but not loosened; iOS 17 ATS blocks IP-literal connections by default | Apple ATS docs (`trust.md` V3) | high |
| F04 | `SecCertificateCopyValues` is macOS-only; iOS exposes no public API for certificate extensions or TBS bytes | Apple forums 737199, 103805 (trust feasibility critique) | medium-high |
| F05 | Android 17: `ACCESS_LOCAL_NETWORK` is mandatory for targetSdk 37+, covering outgoing and incoming TCP, UDP, mDNS and NsdManager; the page is silent on loopback and VPN | developer.android.com/privacy-and-security/local-network-permission (platforms, trust, contract, and their critiques) | high |
| F06 | StrongBox supports ECDSA P-256 and not Ed25519; the Secure Enclave's only classical curve is P-256 | developer.android.com keystore; Apple CryptoKit SecureEnclave docs | high |
| F07 | Android Key Attestation: "Only the first occurrence of the extension in the chain can be trusted … might have been issued by an attacker extending the chain"; "Find the nearest certificate to the root that contains the key attestation certificate extension"; RKP certificates "continue to have their validity period checked"; trust factory RSA-root chains regardless of validity; P-384 root signs from 2026-02-01; status list at android.googleapis.com/attestation/status; leaks do not apply to RKP keys | developer.android.com/privacy-and-security/security-key-attestation (**fetched in this synthesis**) | high |
| F08 | App Attest `attestKey` contacts Apple's server | Apple docs (`manifest.md` MV9) | high |
| F09 | Tailscale DERP relays traffic when a direct path fails; WireGuard-encrypted; servers in 20+ countries | tailscale.com/kb/1232 (`contract.md` CV8) | high |
| F10 | "Each Tailscale agent … streams its logs to a central log server (at log.tailscale.com)"; opt-out documented for Linux, macOS (open-source build) and Windows; none documented for Android or iOS | tailscale.com/kb/1011/log-mesh-traffic (**fetched in this synthesis**) | high (for what the page says) |
| F11 | In userspace-networking mode Tailscale forwards inbound tailnet connections to 127.0.0.1 | Tailscale docs; issues 7848, 2642 (trust feasibility critique) | medium-high |
| F12 | Tailscale Funnel can route internet traffic to a local service | tailscale.com/kb/1223 (`contract.md` CV5) | high |
| F13 | Ktor CIO server HTTPS issue open; CIO TLS lacks TLS 1.3 | ktor issue 886, KTOR-6737 (`trust.md` V11) | medium |
| F14 | `SSLParameters.setSignatureSchemes` exists only from Java 19 | Oracle SSLParameters API docs (**searched in this synthesis**) | high |
| F15 | RFC 8785 sorts by UTF-16 code units and defers number formatting to ECMA-262; RFC 7518 ES256 = 64-octet R‖S | rfc-editor.org (`platforms.md` V24) | high |
| F16 | JDK-4511638 fixed in JDK 19, so `Double.toString` can differ between JDK 17 and 19+ | bugs.openjdk.org (`platforms.md` V27) | high |
| F17 | DSSE: `keyid` is an unauthenticated hint; do not re-parse after verification; accept standard or URL-safe base64 | secure-systems-lab/dsse protocol.md (`manifest.md` MV13) | high |
| F18 | llama.cpp's context abort callback "currently works only with CPU execution" | llama.cpp `include/llama.h` master (benchmark feasibility critique) | high |
| F19 | Android thermal headroom: AOSP code returns NaN if polled within 500 ms (`benchmark.md` BV02); the ADPF guide says not to call more than once every 10 s (router feasibility critique). **The sources conflict**; the conservative rule is adopted | AOSP PowerManager.java; developer.android.com/games/optimize/adpf/thermal | high (each), conflicting |
| F20 | amdgpu `gpu_busy_percent` is a device-wide SMU aggregate; per-process use needs DRM fdinfo | kernel.org amdgpu docs (platforms feasibility critique) | high |
| F21 | GNOME suspends after 15 min of inactivity even on AC (Fedora 38+) | Fedora Discussion 79801 (platforms feasibility critique) | medium |
| F22 | Members of `systemd-journal`, `adm` and `wheel` can read all journal files | man7.org journalctl(1) (platforms security critique) | high |
| F23 | For apps targeting API 31+, `allowBackup=false` on some devices disables cloud backup but not device-to-device transfer | developer.android.com/identity/data/autobackup (platforms security critique) | high |
| F24 | `setUnlockedDeviceRequired(true)` (API 28+) makes key use fail while locked; enforced by the OS, not by hardware | Android KeyGenParameterSpec reference; Android Developers Blog 2018-12 (trust security critique) | high |
| F25 | Doze begins only when the device is unplugged, stationary and screen-off | developer.android.com doze-standby (`platforms.md` V14) | high |
| F26 | Android developer verification (**restated in r2**; r1 wrongly said the 2026 phase "covers apps outside Play"): "These protections begin for users installing apps from participating stores (Google Play, HONOR App Market, OPPO App Market, Galaxy Store, Palm Store, V-Appstore, GetApps) in Brazil, Indonesia, Singapore, and Thailand, on certified devices running Android 7+" from 30 Sep 2026; "In 2027, we'll expand this globally to all apps on certified devices"; an "Advanced Flow" serves "power users who want the ability to download unverified apps"; limited-distribution accounts share apps with "up to 20 devices". The page does not mention F-Droid or adb | developer.android.com/developer-verification (**fetched in r2**, 2026-09-30) | high (for what the page says) |
| F27 | prima.cpp runs only on the strongest device when a model under 14B fits; exo: 3×M4 Pro single request 39.7 vs 49.3 tok/s on one; Wi-Fi pipeline 8.7 vs 25.4 tok/s on Thunderbolt 5 (single-user report, misconfigured ring) | arXiv 2504.08791; blog.exolabs.net/day-1; exo issue 2295 (`router.md` RV2–RV4) | high (RV4: single setup) |
| F28 | llama.cpp RPC is "fragile and insecure … Never run the RPC server on an open network" | llama.cpp tools/rpc/README.md (`router.md` RV1) | high |
| F29 | Prompt-cache hits are detectable by timing; cross-user sharing found at 7 API providers | arXiv 2502.07776 (ICML 2025) (`contract.md` CV6) | high |
| F30 | Room enables WAL by default; with WAL every commit fsyncs by default; `SYNC_MODE_NORMAL` can lose commits on power loss, not on an app crash | developer.android.com SQLite best practices; source.android.com (`contract.md` CV1/CV2) | high |
| F31 | Official Qwen3 GGUF repositories are Apache-2.0; the pins in `benchmark.md` §4.2 are as the HF API reported them | Hugging Face API (`benchmark.md` BV19; 8B entry re-checked by the benchmark critique) | high (as reported; owner re-verification pending) |
| F32 | llama-bench conventions: `pp`/`tg` names, depth `-d`, 5 reps, warm-up | llama.cpp tools/llama-bench/README.md (`benchmark.md` BV01) | high |
| F35 | Homebrew disabled Gatekeeper-failing casks in the official tap on 2026-09-01 | Homebrew discussions (`platforms.md` V19; platforms feasibility critique) | medium-high |
| F36 | Repo facts:<br>• `Egress` commented "Exhaustive — no additions (invariant)";<br>• `ContractFreezeTest` pins neither `Egress` nor header names;<br>• v1 appends a cloud row only after the upstream call;<br>• `server/Main.kt` prints `dev bearer token: $devToken` and `[ledger] $record` to stdout (**grep in this synthesis**, lines 56 and 70);<br>• `allowBackup=false`; network security config permits cleartext to localhost only; targetSdk 35 | repo `f51dae9` (`contract.md` repo-1..5; this synthesis) | high |
| F38 | RKP certificates are valid about 61 days (up to two months) | search summary of Android docs/blog (manifest feasibility critique) | medium |

**Added in r2 (owner directive C and this revision).** "Owner-verified" means verified in the owner's session and given as fact in directive C; "fetched in r2" means re-fetched while writing this revision.

| ID | Fact | Source | Conf. |
|---|---|---|---|
| F40 | MLPerf Mobile v6.0, released 2026-06-15: a consumer-installable benchmark on the Google Play store, the Apple App Store and GitHub, "the permissive Apache 2.0 license"; LLM benchmarks with Llama 3.2 1B Instruct, Llama 3.2 3B Instruct and Llama 3.1 8B Instruct; requests "selected from the TinyMMLU and IFEval datasets to quantify the performance and accuracy"; CPU execution available; NPU-accelerated Llama 3.1 8B on Snapdragon 8 Elite Gen 5; MediaTek Dimensity 9500 ("new support") and Samsung Exynos 2600 ("updated support") covered, **with no statement that the LLM runs on their NPUs** (r3 wording, R2-OVERCLAIM-11) | mlcommons.org/2026/06/mlperf-mobile-v6/ (owner-verified; **fetched in r2**) | high |
| F41 | Llama 3.2 Community License: redistributors must "provide a copy of this Agreement with any such Llama Materials", "prominently display 'Built with Llama'" on a related website, UI, blog post, about page or product documentation, adhere to the Acceptable Use Policy, and request a licence from Meta above 700 million monthly active users | huggingface.co/meta-llama/Llama-3.2-1B-Instruct LICENSE.txt (**fetched in r2**) | high |
| F42 | mobile_app_open release notes v6.0/v6.0.1: "a generative AI workload that runs Llama on-device"; "three model sizes: 1B, 3B, and 8B"; "two evaluation datasets: MMLU and IFEval (with dedicated JSON and language validators)"; "reports token throughput"; "configurable input-token limit and config-based pipeline thread count"; in 6.0.1 "The LLM benchmark is now optional". Exact metric formulas are **not** in the notes | github.com/mlcommons/mobile_app_open/releases (**fetched in r2**) | high (for what the notes say) |
| F43 | mobile_app_open: Flutter app for iOS, Android and Windows; backends TensorFlow Lite (default), Core ML, QTI, Google Pixel, Samsung, MediaTek; Apache-2.0; models in a separate `mobile_models` repository. `flutter/pubspec.yaml` (mlperfbench 6.0.1+1) depends on `firebase_core`, `firebase_storage`, `firebase_auth`, `firebase_ui_auth`, `firebase_crashlytics` and `firebase_app_check` | github.com/mlcommons/mobile_app_open README and raw `flutter/pubspec.yaml` (**fetched in r2**) | high (README may predate the LLM work) |
| F44 | AT&T routes about 45 billion tokens a day through an in-house gateway built on LiteLLM that routes on task difficulty and cache state; some coding costs fell up to 56% at about 2% quality loss; about 40% of employee AI traffic is on open models | about.att.com/blogs/2026/the-tokenomics-equation.html (owner-verified; returned HTTP 403 to the r2 fetch; the figures are corroborated by fierce-network.com and mobileworldlive.com coverage found in r2) | high (owner) / medium (r2 re-check) |
| F45 | Routers ship at OpenRouter (Fusion), Cognition (Devin Fusion), Harvey, Vercel (AI Gateway), Splunk and vLLM (Semantic Router); AWS Bedrock and Microsoft Foundry have native routers; the gateway niche is consolidating, with Helicone in maintenance | pakodas.substack.com/p/llm-routers, 2026-07-28 (owner-verified). The r2 re-fetch confirmed the first six names and the date; it did not surface Bedrock, Foundry or Helicone, which therefore rest on directive C | high (owner) / partial (r2 re-check) |
| F46 | Palo Alto Networks agreed to acquire Portkey, an AI gateway company (2026-04-30), and completed the acquisition on 2026-05-29 | paloaltonetworks.com press releases; PANW FY2026 10-K (found in r2 search) | high |
| F47 | Google's Android Bench (2026-07-08) is a leaderboard of LLMs on Android **coding** tasks, a developer tool, not a device benchmark | owner-verified (directive C); not re-fetched | high (owner) |
| F48 | The mesh-relevant market gap: no project found that is device-resident, shared by all of one user's apps, BYOK, with an auditable egress ledger, no operator backend, and a mesh of the user's own devices | owner survey (directive C) plus the r2 searches above. **Absence of evidence, not proof** | medium |

**Added in r3.** "Fetched in r3" means re-fetched while writing this revision (2026-09-30). Platform facts are cited in place with their section's tag (`[LFnn]`, `[FWnn]`, `[FMnn]`, `[IFnn]`, `[UFnn]`, `[AFnn]`); each section's §1.1 table is their source list, with URLs and fetch dates.

| ID | Fact | Source | Conf. |
|---|---|---|---|
| F49 | MLPerf Client v1.6, released 2026-04-06: runs on Windows, macOS, iPad and iOS; backends Windows ML, llama.cpp, and llama.cpp or MLX on Metal; GUI builds on the iOS and Mac App Stores, Steam following; open source on GitHub; "standardized metrics for both responsiveness and throughput". The release page names no models; the current benchmark page (via `linux.md` [LF37]) lists a CLI-only Ubuntu 24.04 build and Llama 3.1 8B Instruct and an experimental Qwen 3 8B; its README (via `windows.md` [FW38]) names TTFT and tokens/s | mlcommons.org/2026/04/mlperf-client-v1-6/ (**fetched in r3**); mlcommons.org/benchmarks/client/ [LF37]; github.com/mlcommons/mlperf_client [FW38] | high (for what the pages say) |
| F50 | MLCommons MLPerf Results Messaging Guidelines: results not reviewed by MLCommons must be marked "unverified" and state "Result not verified by MLCommons Association"; users "may not imply your use is verified or official"; "MLPerf results may not be compared against non-MLPerf results". The MLPerf name and logo are MLCommons trademarks; a trademark licence agreement is available on request | github.com/mlcommons/policies `MLPerf_Results_Messaging_Guidelines.adoc` (**fetched in r3**); mlcommons.org/policies (search summary) | high (guidelines text) / medium (licence terms, not fetched) |
| F51 | TLS 1.3 records: `TLSCiphertext` has a 5-byte header (`opaque_type`, `legacy_record_version`, `length`); `TLSInnerPlaintext` carries the real content type in 1 byte plus optional zero padding; plaintext ≤ 2^14 bytes; the AEAD tag adds 16 bytes for the standard suites | rfc-editor.org/rfc/rfc8446 §5.1–§5.2 (**fetched in r3**) | high |
| F52 | JDK 17's standard algorithm names include `SHA256withECDSAinP1363Format` (raw r‖s output) and `Ed25519`/`EdDSA` | docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html (**fetched in r3**) | high |

---

## Appendix B — Assumptions (not verified) and the spikes that settle the load-bearing ones

| ID | Assumption | Load-bearing for | How to settle |
|---|---|---|---|
| A02 | On iOS, a software P-256 leaf key + certificate can become a `sec_identity` for `NWConnection` client auth, and `WhenUnlockedThisDeviceOnly` works for it | M4 transport; T9 on iOS | **Spike S-A2** (before M4 scheduling) |
| A03 | **Replaced in r3** by the macOS design: a Swift helper (`asom-mac-helper`) reaches the Secure Enclave for the JVM node; whether it works from the helper, and whether library validation can stay on with the hardened launcher | macOS key tier; C13 | **Spikes S-M1 and S-M3** (`macos.md`) |
| A04 | An FGS keeps network access during Doze. **r3: fact for AOSP** (network and partial wake locks kept at FGS state or higher [AF12]); OEM behaviour (the RedMagic) still open [AA04] | Android borrower while unplugged | DV-A5 (NEEDS-DEVICE-VALIDATION) |
| A05 | **r3: settled from AOSP source (android-17.0.0_r1):** the local-network permission never covers `127.0.0.1` in the same profile [AF05]; cross-profile loopback is blocked for all apps [AF04]. Open: OEM builds, later releases, and a possible future `USE_LOOPBACK_INTERFACE` gate (K31) | v1 at targetSdk 37 | H5 emulator probe + DV-A2 |
| A06 | **r3: fact for 17 r1:** single-IP tun addresses (Tailscale) are not "local network"; same-subnet LAN peers are [AF05][AF23]; the 2025 `main` rule differed [AF06] | Android requester over the overlay | re-check per release; DV-A3 |
| A07 | `systemd --user` keeps running in Deck Game Mode; Game Mode idle suspend honours logind inhibitors; fdinfo-based GPU accounting works on the SteamOS kernel | Deck provider | Owner test on the Deck (D-v2) |
| A08 | Steam and Proton games run as the `deck` uid with HOME access | T17 shared-uid label | Owner check |
| A09 | SNI-token gating works in JSSE via `X509ExtendedKeyManager`; Conscrypt and Network.framework support unknown | T13 mitigation | **Spike S-A11** (L0.5 for JSSE) |
| A10 | Whether any setting stops Tailscale client log uploads. **r3: Linux settled** (Headscale alone does not; `TS_NO_LOGS_NO_SUPPORT=true` does [LF10]); Windows only via `tailscaled-env.txt` [FW01]; macOS only with open-source `tailscaled` [FM29]; Android 1.98 switch, default on, forced on under MDM [AF21]; **iOS open** [IA09] | D8 disclosure | Packet captures DV-A8, DV-I17 |
| A11 | All scoring parameters, tracker thresholds (800/600/20/3), numerics tolerances (20/50‰), editorial thresholds and class defaults | router, tracker, report | Calibration gates (v2, M1); all marked PROVISIONAL |
| A12a | Flagship phone decodes 8B at ~5 tok/s and prefills at ~30 tok/s | the §7.1 baseline | Owner-device benchmark on the phone (v2) |
| A12b-lo | Steam Deck, FP32-compute basis: decode at 55–70% of 88–102.4 GB/s over 5.03 GB (9.6–14.3 tok/s); prefill at 25–40% of 1.6 TFLOPS FP32 over 16.38 GFLOP/token (24.4–39.1 tok/s) | the Deck row of §7.1 (lower bound) | Owner-device benchmark at D-v2 |
| A12b-hi | Steam Deck, scaled from one community llama-bench row (Llama 2 7B Q4_0, Vulkan, pp512 144.31, tg128 17.52 [LF35]): decode 10–13 tok/s, prefill 80–120 tok/s for Qwen3-8B Q4_K_M; not sustained; LCD/OLED and power limit unknown | the Deck row of §7.1 (upper bound) | Owner-device benchmark at D-v2 (DV-D6, DL4 gate 5) |
| A12c | A CPU-only desktop with dual-channel DDR4/DDR5 (~40–80 GB/s) decodes 8B Q4 at 5–10 tok/s and prefills at 20–60 tok/s | the CPU-only row | Owner-device benchmark at D-v2 (the Dell, if CPU-only) |
| A12d | 8B Q4_K_M on an M4 Pro-class machine decodes at 38–45 tok/s and prefills at 360–440 tok/s (7B Q4_0 figures [router.md RV9] scaled to the larger file) | the GPU row | Owner-device benchmark if such a lender exists |
| A13 | Battery temperature ceilings 42 °C soft, 44–45 °C hard are conservative | benchmark safety | Owner review; RedMagic observation |
| A14 | Per-exchange Wi-Fi radio energy is material on phones | the zero-idle state design | Measured at M1 (design is safe if wrong) |
| A15 | CUDA runtime redistribution terms | CUDA builds | Legal check |
| A16 | App Review outcome for an iPad foreground provider | M5 | Submission |
| A17 | Hugging Face LFS `oid` equals the file's SHA-256 | bench-set pin | Owner downloads and runs `sha256sum` |
| A18 | StrongBox P-256 signing takes ~100 ms | manifest rate limits | Measure on RedMagic |
| A19 | Time for a charging phone to return to COOL after a heavy tier | standard plan budget | Spike (B8) |
| A20 | Two ledger commits per attempt cost single-digit to tens of ms on phone flash | hot-path budget | Measure (M1 gate 8) |
| A21 | JSSE, Conscrypt and Network.framework each allow TLS-1.3-only, required client auth, a custom verifier, no 0-RTT, no client-offered resumption, and ALPN checks per connection. **r3:** Conscrypt's server-side resumption control and Keystore keys in its key manager are named S-A9 rows [AA06][AA07]; Network.framework knobs are [IA06] | T2 | **Spike S-A9** matrix (L0.5 JSSE on JDK 17/21; ART at M1; Network.framework at M4) |
| A22 | swift-certificates + swift-asn1 can implement `verifyPeerChain` exactly and reject every negative vector | T3 | **Spike S-A10** (M4) |
| A23 | `android/keyattestation` runs on a plain JVM | future A2 | Spike only if A2 is revived |
| A24 | The effort estimates in §9.4 | planning | Revisit after L0 with real velocity |
| A25 | An iOS keychain access group shared by apps signed with one Team ID lets them all use one node key, and nothing outside that Team ID can | M4 device-level pairing (§7.8) | **Spike S-A12** (with S-A2, before M4 scheduling) |
| A26 | Network.framework can disable TLS session resumption per connection (`sec_protocol_options`) | T2 on iOS | **Spike S-A9** matrix (M4) |
| A27 | MLPerf Mobile v6.0's exact LLM metric formulas (throughput, time to first token if reported), prompt subsets, input-token caps, runtime and model format | §6.1 comparability mapping | **Spike S-B1**: read `mobile_app_open` at tag v6.0.1 (v2 P6 design input) |
| A28 | Meta's official Llama repositories on Hugging Face are gated (account and licence acceptance), so an app cannot fetch them anonymously | D18 file-source options | Owner check |
| A29 | Downloading Llama-licensed weights would add an F-Droid anti-feature (e.g. NonFreeAssets or NonFreeNet) | D18, K26 | Ask F-Droid at submission |
| A30 | The TinyMMLU/MMLU and IFEval prompt texts may be redistributed inside the app | §6.1 workloads | Licence check before use |
| A31 | MLPerf LoadGen (C++) can drive asom's `BenchEngine` through the v2 NDK build without pulling in TFLite or vendor SDKs | §6.1 harness reuse | **Spike S-B2** (v2 P6) |
| A32 | Android Automotive OS head units allow sideloading only in developer mode; with Android Auto or CarPlay the apps run on the phone | §3.1 long-horizon holons | Not settled unless D26 schedules it |
| A33 | swift-crypto gives the CryptoKit P-256 API on Linux. **r3: shown by running it** (the iOS spike reproduced the JCA verdicts on all 37 manifest vectors with swift-crypto 3.15.1 and 4.5.2 [IS1]); kept as an assumption only for future swift-crypto versions | L0.7 | re-run per pin bump |
| A34 | An OS vendor could add cross-app, cloud-routing, audited inference to its platform service | K30 positioning risk | Watch; no settling test |
| A27d | MLPerf Client's exact metric formulas, prompt sets and caps (desktop twin of A27) | §6.1 desktop baseline | **Spike S-B1d**: read `mlcommons/mlperf_client` at the v1.6 tag |
| A35 | Network.framework's `DataTransferReport` transport byte counts include TLS records and handshakes (`ios.md` IA07) | §8.4 `MEASURED` overhead on Apple stacks | Compare with a packet capture on a Mac |
| A36 | A TLS 1.3 mutual handshake with two P-256 certificates per side costs about 4,096 bytes per direction | §8.4 `ESTIMATED` overhead | Calibrated by the JVM `MEASURED` lane (record tap) in L0.4/L0.5 |
| A37 | Per-file `bptPermille` / `bptCapPermille` values (bytes per token and its cap) on the reference prompts bound honest answers; about 4 and 8 bytes/token for English at the class defaults | §5.7 byte observation and padding residual | The owner's reference CPU run per bench file |
| A38 | The lab's project-directory mapping keeps AGP out of the lab's classpath even with an Android SDK present (the r2 mechanism, unchanged) | `LAB_SPEC.md` §2 | Isolation check 2 (buildEnvironment with the SDK present) on the first CI run; the `ASOM_PURE_JVM` guard is the documented fallback |

