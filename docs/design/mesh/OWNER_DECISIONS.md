# Owner decisions: every open ratification (consolidated, design revision 4.1)

**Date:** 2026-10-07; revision 4.1 additions 2026-10-18 (§J) · **Revision:** r4.1 · **Companion to:** `ASOM_MESH_DESIGN.md` r4.1 (§10 holds the full option tables of D3–D29), `REVISION_4.md` and `REVISION_4_1.md` (where each r4 and r4.1 item came from), `OWNER_BRIEF.md` (one page).

**New in r4.1 (§J):** acting decision **AD-7** (the readings the builders implemented after r4, adopted as normative text) and **D42–D48**. Sections A–I are unchanged except that §I's count still holds unless you rule D42(a).

**How to read this.** Every item below needs a ruling from you; nothing in it is approved by being written down. Each item gives the options, a recommendation, and **what is blocked until you answer**. Items D3–D29 keep their r3 ids and are summarised here; their full tables are in the design's §10. Items **D12.0, D12.4b, D30–D41, RT-15** and the two sub-questions marked **(sub)** are new in r4. Acting decisions AD-1…AD-6 stand unless you overrule them, but they were taken on your behalf and are listed first so that you ratify them explicitly.

**What r4 does not change.** The v1 HTTP contract stays frozen (no endpoint is added; every new app-facing value is a separate line below). The CLAUDE.md invariants stand. Nothing here is device evidence: every device item stays `NEEDS-DEVICE-VALIDATION`, and v1 device validation is still open.

---

## A. Acting decisions to ratify (taken under directive D-F, 2026-09-30)

| Id | Decision (as taken) | Options | Recommendation | Blocked until ratified |
|---|---|---|---|---|
| **AD-1** | Sequencing: after V1-close and v1.1, **v2 → mesh-1 → v2.5 → v3 → remaining v4**; v2.5's per-app cloud-ban column pulled into mesh-1 | (a) ratify; (b) restore roadmap §2's order (v2.5 and v3 before any mesh work); (c) another order you name | **(a)**: the router is the differentiator and the mesh router is part of it; v3's loops and firewall are not prerequisites | Nothing ships now either way. The order of every phase after v2; RT-8, RT-10; the meaning of D24's placements |
| **AD-2** | **Amendment 3 (mesh)** declared: Invariant 1 narrowly **relaxed** (banded status and approved manifests to paired nodes only; content never automatic); Invariant 3 class `peer`; Invariant 5 node identity is a pinned node key; roadmap §13 count becomes three | (a) ratify the exact text of design §8.2 incl. the r4 clarifications below; (b) ratify with edits you name; (c) refuse: no mesh | **(a)**. r4 clarifies, inside the same text: (i) Amendment 3 also **narrows** Invariant 3's "every network event writes a ledger row" in two stated ways (refused unauthenticated inbound connections are counted in 10-minute rows; transport overhead is per session) (R3-CONFORMANCE-9); (ii) ratifying it does **not** approve the app-facing header value `peer` (that is D12.0); (iii) the IC-4(b) clause rides in only if D25(a) is ruled | **M1** (its entry requires Amendment 3 ratified); the user-facing relaxation copy; RT-1 |
| **AD-3** | Repository placement: `docs/design/mesh/`, `lab/`, `desktop/`, `apple/`, `ubuntu-touch/`; root build and `./gradlew jvmTest` unchanged | (a) ratify; (b) split into separate PRs (allowed under (a)) | **(a)**. r4 replaces the never-failing `git diff --exit-code` check with a pinned-base comparison (`LAB_SPEC.md` R4-L-03) and forbids any root edit by a builder (R4-L-04) | Merging the program branch |
| **AD-4** | The lab is the sanctioned exception to "do not start later versions" (ships nothing) | (a) ratify; (b) stop the lab | **(a)** | Continued lab work |
| **AD-5** | Verification honesty: evidence labels; device items stay NDV | (a) ratify | **(a)** | — |
| **AD-6** | Opus designs and reviews; Sonnet implements | (a) ratify | **(a)** | — |

---

## B. Rulings that block nothing in the build but correct the record (answer any time)

**RT-15 — Correct roadmap §14 item 3** (R3-CLOSURE-9). The committed roadmap still says "Every node is whole on its own … it serves its own apps alone". No node is a full holon before M2+ (design §0.1, r4).
- Options: (a) replace item 3 with the §0.1 wording (design §8.3 RT-15 gives the text); (b) leave it.
- **Recommendation: (a).** Only you (or a session you direct) may edit `ASOM_ROADMAP_BRIEF.md`; this revision does not touch it.
- Blocked until answered: nothing technical; a later session reading the binding roadmap could treat the universal holon claim as met.

**D38 — The v1 stream-response observation** (R3-CLOSURE-3, `LAB_SPEC.md` R4-L-09). On streamed responses frozen v1 commits `X-Asom-Served-By`/`X-Asom-Egress` before the body and never sends `X-Asom-Cost-*`, although the ledger row records a cost. Invariant 9 ("API and dashboard never disagree") therefore holds only for the commit-time subset on streams.
- Options: (a) accept as inside frozen §5.4's commit-time allowance, and add one explanatory sentence to `CLIENT_API.md` at v1.1 (an SDK doc change, ruled with D23); (b) treat it as an Invariant 9 deviation and plan a v1.1 fix (there is no frozen header that can carry it after the body, so a fix means a contract change); (c) accept and document nothing.
- **Recommendation: (a).** No code change; the dashboard row stays the full record.
- Blocked until answered: nothing (the lab pins today's behaviour).

**D37 — Proposed revision of directive D-C** (R3-CONFORMANCE-6). D-C (decided) says: MLPerf Mobile's model set and metric definitions are the comparability baseline where licences permit, and reports may say "measured with MLPerf Mobile's model set and metric definitions". r3's D18(a1) instead makes Qwen3 the default, disables even a descriptive note until a trademark check, and offers forbidding the MLPerf name.
- Options: (a) keep D-C as decided (the MLPerf model set is the default baseline; the decided phrase is allowed; the Llama licence conditions are accepted at first run); (b) revise D-C to r3's D18(a1): Qwen3 default, MLPerf set opt-in behind the licence screen, the phrase disabled until your trademark check; (c) as (b) and never use the MLPerf name in shipped text.
- **Recommendation: (b)**, recorded as an explicit revision of D-C, because the MLCommons results-messaging rules [F50] and the Llama licence conditions (Acceptable Use Policy, attribution, gated official repositories) make an unattended default risky; say which parts of D-C change.
- Blocked until answered: D18 and v2 P6's normative outputs (wording vectors M05-MLP).

---

## C. Before v1.1

**D5 — Ledger shape: per-attempt rows, per-frame control rows, and the new v1.1 error.** Full table: design §10.1.
- Options: (a) append-only intent/outcome rows per attempt, hourly **interval rows** for control traffic (up to 1 h of control byte counts can be lost); **(b) as (a) but one row per control frame on both nodes**, application bytes exact, overhead per session, refused inbound per 10 min; (c) either, mesh only, no v1.1 change; (d) mutable rows (rejected).
- Both (a) and (b) add `LEDGER_UNAVAILABLE` in v1.1, which **changes v1 cloud behaviour** (a cloud request fails when the ledger cannot write) and roadmap v1.1's locked delta (RT-3).
- **Recommendation: (b).** The lab built (b) ahead of this ruling, and its laws (L-L13…L-L16) hold over real TLS in the lab (LAB evidence only).
- Blocked until answered: **v1.1 scope**; the M1 ledger; promotion of `:ledger-model`.

**D34 (new, with D5) — Where the session id of a row lives** (R3-CLOSURE-6, ERR-LL-2). Rows of one session must be groupable for the per-session byte law and the join tool. r4 adopts the grouping rules (`LAB_SPEC.md` R4-L-24); the storage is yours.
- Options: (a) one additional nullable ledger column `sessionId` (21 mesh columns; the lab does this; pinned by L02-013, L02-020); (b) no new column: a fixed `routeDetail` prefix `session=<id>` (20 columns; `routeDetail` becomes partly structured); (c) check per connection only through a test tap (rows alone cannot rebuild per-session accounts; the design §8.4 join tool loses that).
- **Recommendation: (a).** A typed column is cheaper to audit than a parsed free-text prefix, and it is what the lab, its vectors and its laws already use. It raises the contract count by one column (the count line says so).
- Blocked until answered: the M1 ledger contract (§8.3 count), D5's promotion.

**D23 — Contract and dependency-law registry.** Full table: design §10.1.
- Options: **(a) approve the §8.3 registry, each item at its phase, unruled means unapproved**; (b) item by item per phase; (c) blanket approval now (not recommended).
- **Recommendation: (a).** r4 adds to the registry, for transparency: the Ubuntu Touch internal channel frame of D39 if you approve it, and the `CLIENT_API.md` sentence of D38(a).
- Blocked until answered: v1.1 (CD-DOC3); every promotion; D-v2's CD-24 (the owner socket).

---

## D. Before v2 (the engine and the benchmark shell)

**D30 (new) — The projected row flag `thermal-drift`** (apple LF-1, lab ERR-FX2-BD-3; `benchmark.md` R4-B-05). The JVM projection adds `thermal-drift` to a manifest row when a test of the tier drifted; `benchmark.md` §13.3's flag list does not have it; the Swift lane follows the list. Because a signed document's `results` must re-derive byte for byte (verifier step 15a), this one difference causes **all 42 listed verdict disagreements and 5 value differences** between the lanes. Both lanes already cap confidence at low under drift, so no number changes either way.
- Options: (a) **add `thermal-drift` to §13.3's list** (Swift adds one line; no JVM vector changes; the diagnostic switch already proves the lane diff is then empty); (b) **drop it from the JVM projection** and regenerate the signed M02, M03, M05 and M06 documents, hashes and texts that carry it (125 occurrences inside signed payloads across five vector files).
- **Recommendation: (a).** Reasons: the flag tells a reader *why* confidence is low, which `confidence-low` alone does not (B7 asks for drift to be visible, and Q1 is reworded "first-minute speed" under drift); it costs one Swift line and regenerates nothing; option (b) churns every signed vector and the cross-lane fixtures for no gain in honesty. Rule D31 the same way so that the producer list stays one consistent rule ("every confidence cap that has a named cause has a flag").
- Blocked until answered: an empty lane diff (`apple/ci/known-disagreements.txt`); the lab `VERSION` 0.3.0 regeneration (do it once, after this ruling); promotion of `:bench-core` and `:manifest` at v2.

**D31 (new) — The projected row flags `restarted` and `swapped`** (lab ERR-FX2-BD-3). The JVM projection also adds `restarted` (a tier restarted after a yield) and `swapped` (swap growth over 256 MiB). No signed vector contains them, so the lane diff cannot see the disagreement, but **any real daemon run that yields once or swaps would be rejected by the Swift verifier** (step 15a).
- Options: (a) add both to §13.3 and to the Swift projection, plus one M02 accept vector with `restarts` 1 and swap growth over 256 MiB; (b) drop both from the JVM projection (no vector to regenerate).
- **Recommendation: (a)**, for the same reason as D30 (one rule; the reader sees the cause of a medium or low cap). If you prefer the shortest flag list, (b) is free here, but then rule D30(b) as well.
- Blocked until answered: cross-lane verification of any real run that yielded or swapped; v2 P6.

**D32 (new) — The thermal-drift rule (design B7): order and minimum reps** (lab ERR-FX2-BD-6, ERR-BENCH-3; apple LF-2, F-4, E-23).
- Part 1, order. Options: (a) **outlier rule first**, drift judged on the kept reps (today, both lanes; the seed rows M04-001 and M04-002b of `benchmark.md` §9.5 say `OUTLIER_EXCLUDED, high`); (b) **drift first, on all reps**, as B7 reads ("the first rep is never excluded as an outlier under drift"): a heat-slowed last rep then makes the test `low` instead of being discarded as an outlier.
- Part 2, minimum kept reps for the two drift tests ("slowing at every step"; "first minus last over 100‰"). Options: (a) 4 and 3 (the lab); (b) 3 and 2 (the Swift lane). Quick-plan documents have 3 reps, so the lanes differ on real data; vector F-4 (three reps of 10.0 s, 10.3 s, 10.6 s) decides it.
- **Recommendation: Part 1 (b) and Part 2 (b).** Both are the conservative readings: a heat-induced slow rep must lower confidence rather than vanish as an outlier, and a 3-rep quick run must be able to show drift. Cost: regenerate M04-001, the M04-002b vector and every vector whose reps end in a slow outlier, in both lanes, and amend §9.5's two seed rows. Revisit when per-rep thermal codes exist (deferred B-patch, R4-B-10): drift could then require a rising thermal code.
- Blocked until answered: final M04 seed vectors; agreement on quick-plan documents; v2 P6.

**D33 (new) — A sustain block on the wrong tier** (lab ERR-FX2-BD-1). r4 adopts: a sustain block on a tier whose numerics failed is ignored by every derived answer. The open question is whether a document whose sustain block is not on the §4.4 sustain tier is invalid.
- Options: (a) keep "ignore" (a valid document; the block is unused); (b) refuse it (`SCHEMA_INVALID` or `INCONSISTENT`) in both verifiers.
- **Recommendation: (a)**: ignoring already removes the harm, and a producer may legitimately have run the heat test on another tier (a yielded draft, a pinned plan).
- Blocked until answered: nothing now; the verifier's verdict set at v2.

**D36 (new) — The virtual-machine banner wording** (lab ERR-FX2-BE-7). `PLATFORM_PLAN.md` DL5 says `VIRTUALIZED — NOT DEVICE EVIDENCE`; `benchmark.md` §8 says "VIRTUAL MACHINE"; both renderers print "VIRTUAL MACHINE: results are capped at MEDIUM confidence."
- Options: (a) "VIRTUAL MACHINE - NOT DEVICE EVIDENCE: results are capped at MEDIUM confidence." (ASCII, one line); (b) keep today's line; (c) a wording you give.
- **Recommendation: (a)**: it is the only wording that says the result is not about a device, which AD-5 requires. Both renderers and the M05 texts change together.
- Blocked until answered: DL5 (D-v2); any regeneration of M05 texts with a VM run.

**D40 (new; owner input) — Class ceilings for peer claims** (R3-CLOSURE-5, lab ERR-R6-3). The router caps a peer's claimed speed by `min(claim, classCeiling, signed reference)`. No ceiling values exist and the signed reference table needs your offline key (RT-12, D18), so today a claim is capped only by observation (`claim × disc / 1000`).
- Options: (a) supply per-class ceiling values (with the D18 editorial table); (b) accept no ceiling until the D-v2 owner-device benchmark, relying on observation and the 1-in-4 cap for unverified peers.
- **Recommendation: (b) now, (a) after D-v2**: values invented before any measurement would be guesses.
- Blocked until answered: the `capRef` term of the M1 router.

**D6, D7, D18, D22** (design §10.2), unchanged in r4 except as noted:
- **D6** report export and the P7 class. Options (a)–(e); **recommendation (b), recorded as Amendment 4.** Blocks v2 P6/P7.
- **D7** roadmap v2 P6 wording. Options (a) one benchmark inside every node, (b) standalone products too, (c) keep the roadmap text; **recommendation (a).** Blocks v2 P6. r4 note: (a) as written included the desktop `asom bench` inside the node; the desktop standalone CLI is (b).
- **D7/D24 (sub) — the iPhone benchmark shell** (R3-CONFORMANCE-7). The iPhone never lends, so its benchmark feeds no router and is the me-too component D-C rejects. Options: (a) drop the bench shell from the iPhone build of M1b and add it on the iPad at M5; (b) keep it, recorded as a departure from D-C. **Recommendation: (a).** Blocks M1b's iOS scope.
- **D18** comparability, bench sets, trademark check, editorial constants and key. **Recommendation (a1)** (now read together with D37). Blocks v2 P6 normative outputs.
- **D22** publisher identities: Android developer verification (a); Windows SignPath, else Azure, else OV; one Apple Team ID chosen once. r4: the **Apple Team ID** sub-decision now also gates **M1b** entry (the iOS app on devices and the keychain access group need it); the Android and Windows parts gate release signing only.

---

## E. Before D-v2 (the desktop engine port)

**D21** desktop engine crash containment. Options (a) in-process JNI with a crash contract, (b) a child process. **Recommendation (a).** r4: now in D-v2's entry criteria (R3-CONFORMANCE-10). Blocks D-v2.

**D25** local callers that are not apps. Options (a) IC-4(b) in mesh-1, (b) plus a desktop local-app API at M2, (c) gate all desktop borrowing on D14 part B, (d) desktops only lend, (e) native Windows peer-PID checks first. **Recommendation (a), then (b) at M2.** r4: IC-4(b) is now an enumerated list (the desktop owner CLI; the asom app's own screen on Ubuntu Touch and on iOS; never host apps).
- **D25 (sub) — where IC-4(b) lives** (R3-CONFORMANCE-1). Options: (a) keep it in Amendment 3 and name it in the RT-1 roadmap text (r4 drafted that sentence); (b) move it to Amendment 5 part B (IC-8, "local identity per OS"). **Recommendation: (a)**: mesh-1 needs it before Amendment 5 part B is due (M2), and naming it in RT-1 removes the "rides in unannounced" problem.
- Blocks D-v2 (the owner CLI), M1b (the Ubuntu Touch and iOS `self-ui:` callers), M2.

**D27** platform engineering package. **Recommendation (a)**: accept, with M-D4 (the hardened macOS launcher) worth reading. Blocks D-v2 and scaffold promotion.

**D28** which devices lend in mesh-1. **Recommendation:** the Dell with the polkit keep-awake rule; the Deck in Game Mode only behind an opt-in that says it lends invisibly, never on battery; Windows or a Mac only if that is your desktop. r4 notes: (i) a Deck never lends until a mechanism for docked/game/Game Mode is ruled behind a spike (unknown is treated as unsafe, R3-OVERCLAIM-4); (ii) a Windows Modern Standby desktop lends only in service mode (R3-OVERCLAIM-8; device item W8-window). Blocks D-v2's lending targets and M1's gates.

**D41 (new) — Extra systemd hardening for the Linux system unit** (desktop ERR-FX-8). Proposed, not adopted: `ProtectKernelLogs=yes`, `ProtectHostname=yes`, `RestrictSUIDSGID=yes`, `ProtectProc=invisible`, `RemoveIPC=yes`, `PrivateIPC=yes`, `SystemCallFilter=@system-service` with `SystemCallErrorNumber=EPERM` (`platforms/linux.md` §3.2's list is normative today).
- Options: (a) add all, each conditional on a run on real systemd and the node's self-test; (b) add none; (c) a subset you name.
- **Recommendation: (a)**, conditional as stated; keep the justified relaxations (JIT, network, render groups).
- Blocked until answered: nothing now; the hardened unit at DL4 (D-v2).

---

## F. Before M1 (mesh-1)

**D3** meaning of `X-Asom-Egress`. Options (a) furthest content reach (changes frozen §5.4's meaning; equals v1's on every v1 path), (b) serving class (not viable). **Recommendation (a).** Blocks M1 (CD-1m, CD-DOC1).

**D12.0 (new) — The header value `peer`** (R3-CONFORMANCE-8). Frozen §5.4 fixes `X-Asom-Egress: local|cloud`. AD-2 decides the ledger class `peer`; it does not decide the app-facing header value. Options: (a) approve `peer` as an additive value of the frozen header, ruled together with D3 and CD-DOC1; (b) keep the header at `local|cloud` and report peer reach only in the ledger (then apps that read "not cloud" as local are misled). **Recommendation: (a).** Blocks M1 (CD-1).

**D12.1–D12.6** new app-facing values and withdrawals, one line each: D12.1 `X-Asom-Served-By: peer:<alias>/<model>`; D12.2 `owned_by: "asom-peer"`; D12.3 SSE `MESH_STREAM_INTERRUPTED`; D12.4 `X-Asom-Failover` `peer-unavailable`, `peer-lost`; D12.5 `CLIENT_API.md` explains the `peer:` form; D12.6 withdrawals (`X-Asom-Node`, `own-devices`, `NO_ELIGIBLE_NODE`, `locSeed`/`locKey`). **Recommendation: approve each.** Block M1. The W00-100..104 constants stay `proposed` until these are ruled.

**D12.4b (new) — v2.5 route-reason values (CD-RR)** (R3-CLOSURE-10). Split out of D12.4 so that approving D12.4 does not pre-approve them. Options: approve at v2.5; refuse. **Recommendation: approve at v2.5.** Blocks v2.5 only.

**D8** network underlay. Options (a) Tailscale-operated, (b) self-hosted Headscale plus confirmed-LAN direct, (c) LAN only. **Recommendation (b)** with the per-platform log-upload disclosure. r4: the Android "remote client logging" switch is **not verified to stop all log traffic** (tailscale issue 21088); a packet capture (DV-A8) gates that disclosure. Blocks M1.

**D9** what `auto` means. **Recommendation (c)**: scored, usability gate on peers only. Blocks M1.

**D11** body handling. **Recommendation (a)**: read `max_tokens`; strip identity fields from bodies sent to peers. Blocks M1.

**D19** lender prompt cache. **Recommendation (a)**: discard after every peer attempt. Blocks M1.

**D29** Android targetSdk. **Recommendation (a)**: stay at 35 unless ruled separately. Blocks M1.

**D35 (new) — Honest lenders that unload between uses** (lab ERR-R6-14, R6-FINDING-COLD). A lender unloads a model after 300 s idle. A requester that uses it less often than that measures the reload in every answer, so the claim tracker sees ratios around 500‰ and marks an **honest** device DISCREPANT after five observations (7 of 8 simulator seeds), with the 400‰ peer-wide discount and, for two files, the 7-day penalty.
- Options: (a) **DISCARD_COLD**: discard (no strike) an observation when the requester's own last body of that file to that peer is older than 300 s (the requester's constant, never the peer's declared `idleUnloadMs`); (b) add the lender's load time (E2) to `predicted` under the same condition; (c) keep today's rule.
- **Recommendation: (a).** It never raises a claim (a peer cannot buy slack by declaring a short unload time, because the requester's own constant decides), and a peer whose every observation is cold stays UNVERIFIED and capped at 1 in 4 placements. (b) gives every cold answer about 2.5 s of slack that a slow liar can also use.
- Blocked until answered: M1 router promotion (`FindingsTest` pins today's behaviour; the gate scenarios are tuned to keep peers warm).

---

## G. Before M1b and M2+

**D14** platform-equivalence text (Amendment 5, parts A and B). **Recommendation (a)**: approve just in time. Blocks the first SwiftUI/QML/tray UI (A) and the first key-holding non-Android tier and desktop local-app API (B).

**D15** iOS requester shape. **Recommendation (b)**: one home lender, device-level pairing. Blocks M1b (iOS).

**D16** phone and tablet lending. **Recommendation:** iPad lend screen at M5; iPhone never; Android lend screen plus charging with "serve while locked". r4: the design's §10.0d no longer pre-decides iPhone or battery lending (R3-CONFORMANCE-11). Blocks M2+.

**D24** Apple and Ubuntu Touch placement. **Recommendation (a)**: right after mesh-1, before v2.5 (a recorded departure, RT-13). Blocks M1b.

**D26** cars and appliances. **Recommendation (a)**: not scheduled.

**D39 (new) — An Ubuntu Touch channel frame for the display hold** (ubuntu-touch ERR-UT-FSM-3). Law L-UT2 holds the display between `INFER_BODY` and its outcome row; no frame of the closed UI-to-node channel tells the UI when that is, so the UI holds the display from `borrow` to the terminal frame (a superset of the law's window).
- Options: (a) keep the superset (no channel change; slightly more display-on time); (b) add one internal channel frame `display{hold|release}` before UT-1 (a D23 transparency item).
- **Recommendation: (a)**: the law is met, and the cost is seconds of screen time per borrow.
- Blocked until answered: UT-1 (M1b) channel freeze.

---

## H. Owner inputs (facts only you can supply; not decisions)

| Input | Needed for | Since |
|---|---|---|
| v1 device validation (`QA_V1.md`, P5–P7 checklists, the P4 real-key smoke) | V1-close; **nothing ships before it** | v1 |
| The Dell's OS **and GPU** | D28, §7.1 row | r3 |
| Whether an Apple-silicon Mac exists, and whether it is a desktop | D28 | r3 |
| Whether a Ubuntu Touch device exists | S-UT1, UT-1 | r3 |
| Headscale acceptable or not | D8 | r3 |
| Your country (Windows signing route) | D22 | r3 |
| Re-verification of the Q1 sha256s; the L1 file source | D18 | r3 |
| Whether a car head unit or appliance is in mind | D26 | r3 |
| Your OpenPGP primary-key fingerprint (the Linux installer holds `OWNER-FILL` and therefore never says "signed by the owner") | release verification (desktop ERR-FX-1) | r4 |
| CPU reference NLLs at the pinned commit, and per-file `bptPermille`/`bptCapPermille` pins | numerics verdicts (today self-attested, ERR-BENCH-6); the claim tracker's byte cap (A37) | r3, restated r4 |
| Class-ceiling values, if D40(a) | D40 | r4 |

---

## I. What the r4 recommendations add up to

If every recommendation above is taken: the contract count of design §8.3 holds 6 app-facing values (in five lines D12.0–D12.4), 21 ledger columns, 15 roadmap or brief text changes; the amendments are five (1 and 2 as written; 3 mesh, incl. IC-4(b); 4 benchmark sharing; 5 platform equivalence). The single next step is unchanged from r3: rule **D5 (with D34) and D23**, which gate v1.1, and send the hardware inventory; v1 device validation remains open.

---

## J. Revision 4.1 additions (2026-10-18; `REVISION_4_1.md`)

**What r4.1 changes here.** After r4 the builders implemented the r4 builder actions and two review-driven fix waves. Where the spec was silent or wrong they took readings, and several were labelled by the builders themselves as owner decisions that they applied while waiting for you (for example `lab/ERRATA.md` ERR-FX4-LD-3: "OWNER DECISION … the age is a HARD cap"; ERR-FX4-RT-6: "BLOCKED(owner-decision)"). r4.1 adopts the ones that change behaviour into normative text (AD-7 below, so that you ratify them explicitly, as for AD-1…AD-6) and puts to you what the builders could not decide (D42–D48). Nothing here is device evidence; the v1 HTTP contract stays frozen and no endpoint, header value or error code is added unless you rule D42(a).

### J.1 Acting decision to ratify

| Id | Decision (as taken) | Options | Recommendation | Blocked until ratified |
|---|---|---|---|---|
| **AD-7** | The readings `REVISION_4_1.md` §2 lists as **A1–A12**, now normative: **A1** the 30-minute maximum session age is a **hard cap**, with a PROVISIONAL 60 s grace for streams already open and `PEER_UNAVAILABLE` for every new offer from the age (`trust.md` R41-T-01); **A2** the availability state is decision row 6 and an aged session is row 6c (R41-T-02); **A3** a presence decline carries the constant `retryAfterMs` of a condition, never the remaining hold-down (R41-T-02, `LAB_SPEC.md` R41-L-02); **A4** one live-state `seq` counter per requester (R41-T-04); **A5** node-wide atomic admission for `maxConcurrent` and the per-peer stream cap (R41-T-03); **A6** `user_disable` drains with grace 0 then goes OFF (`platforms.md` R41-PL-01); **A7** the requester's offer-only probe of a half-open or EXPIRED peer (R41-L-04); **A8** the unverified-claim cap counted per node, against a partner of another node whose claim is not UNVERIFIED (R41-L-03, `router.md` §5.5); **A9** the unknown-battery self cost (R41-L-05); **A10** the corrected ledger law L-L12 (design §8.4); **A11** the ledger file permission rule (design §8.4); **A12** interface eligibility by adapter type on Windows and macOS (R41-T-05) | (a) ratify all twelve; (b) ratify with exceptions you name (each item of `REVISION_4_1.md` §2 says what reverting it would change and which vectors would follow); (c) refuse all (the lab returns to its r4 behaviour and the vectors that pin r4.1 are regenerated) | **(a).** Each item closes a defect that was reproduced in the lab before the change (for A1: one session kept open for 70 and 90 minutes; A2: an OFF lender ran its engine; A3: a decline revealed the last user input to the millisecond; A5: 456 of 1,500 racing pairs both admitted with `maxConcurrent` 1); each is pinned by vectors or failing-before tests; none adds an app-facing value | M1 (the lab modules that carry them are promoted at M1). Their PROVISIONAL numbers are D43 |

### J.2 New decisions

**D42 (new) — The error of a request whose every planned attempt failed when no existing code is true** (router finding RP-6; `lab/ERRATA.md` ERR-FX4-RT-6; design §7.6 r4.1; `router.md` §5.6; `LAB_SPEC.md` R41-L-05). `router.md` §5.6 still named `NO_ELIGIBLE_NODE`, which r3 removed (D12.6), and the simulator had invented `ALL_ATTEMPTS_FAILED` and `DEADLINE` (35 requests carried them in the review runs). Today the lab returns `ALL_PROVIDERS_COOLING` exactly when the plan had cloud attempts and every one failed (literally true: each failure puts the provider into the v1 cooldown). In the other cases no frozen v1 code and no v2 code is true: a plan with no cloud attempt whose peer failures set no breaker (an `oom` end, a model not offered, a scope refusal, a cancel before the body), the request deadline reached with attempts left, or a cloud tier only partly tried. The lab then returns **no code** (the label `all-attempts-failed` or `deadline`, never on a wire), which cannot ship: a v1 error envelope needs a code.
- Options: (a) **add one code** for "every attempt this request was allowed to make failed" (a new app-facing value, additive only, with a typed HTTP status; needs your sign-off as a design §8.3 line ruled with D12; raises the app-facing count by one); (b) **map to an existing code**: `ALL_PROVIDERS_COOLING` where it is literally true (every planned candidate attempted and each one now in its breaker or back-off, peers included, design §7.6), and in the remaining cases the v1 code computed over the permitted universe with the failed candidates set aside (`LOCAL_ENGINE_ABSENT`, `MODEL_UNKNOWN`, `NO_PROVIDER_KEY`, never `NO_PROVIDER_KEY` when the cloud is not permitted); (c) **return `UNMAPPED`** (no typed code; the lab's state today).
- **Recommendation: (b) where it is literally true, and (a) only for what is left (the deadline case and failures that set no breaker).** (b) keeps v1-era apps on codes they know and is honest when every candidate really is cooling; for the deadline and the no-breaker failures every existing code would state a false cause (for example "add a provider key" when a key would not have helped), so one additive code is the honest answer there. (c) is not shippable. If you prefer no new code at all, rule (b) for every case and accept that the deadline case is reported as `ALL_PROVIDERS_COOLING`, which is untrue for the candidates left untried.
- Blocked until answered: M1 (the router's error path is promoted at M1; the CD registry and the design §8.3 count if (a)); nothing now (the lab invents no code).

**D43 (new) — The PROVISIONAL constants to ratify** (`REVISION_4_1.md` §2; `trust.md` R4-T-08, §18; `LAB_SPEC.md` R4-L-17, R4-L-25, §6.4, §11; design §5.7; `PLATFORM_PLAN.md` R41-P-02; `platforms/linux.md` §12). Every value below is a judgement or a single-machine LAB measurement, not a device measurement; `trust.md` §16 already says each "must be reviewed with device data before M1". None was ratified in r4.

*New since r4 (chosen by the builders while implementing; adopted in r4.1):*

| Constant | Value | Spec | Code (lab or desktop) | Pinned by |
|---|---|---|---|---|
| Grace for streams open at the maximum session age | 60,000 ms | `trust.md` R41-T-01; design §4.3 | `Quiescence.MAX_AGE_GRACE_MS`, `SessionLimits.MAX_AGE_GRACE_MS` | L01-347, L01-348 |
| `retryAfterMs` of row 6c (session past its age) | 5,000 ms | `trust.md` R41-T-02 | `LenderDecisionTable.REDIAL_RETRY_MS` | W07p-231, W07p-233 |
| `retryAfterMs` of row 6 (availability, presence, conditions) and of the scope and model rows | 30,000 ms | `trust.md` R41-T-02; `LAB_SPEC.md` R4-L-17, R41-L-02 | `CONDITION_RETRY_MS` | W07p-210..212, W07p-222, W07p-226..228 |
| `retryAfterMs` for busy and duplicate | 5,000 ms | `LAB_SPEC.md` R4-L-17 | `BUSY_RETRY_MS` | lab tests |
| Offer-only probe interval per peer | 30,000 ms | `LAB_SPEC.md` R41-L-04 | `MeshConfig.probeIntervalMs` | lab tests |
| Design capacity charged to this device when unknown | 10,000 mWh | `LAB_SPEC.md` R41-L-05 | `MeshConfig.unknownSelfDesignMilliWh` | R02-051, R02-052 |
| Transport close after a posted `GOAWAY` waits on a stalled driver | 500 ms (real time) | `trust.md` R41-T-08 | `SessionLimits.POSTED_GOAWAY_GRACE_MS` | `RevokeDoesNotBlockTest` |
| Requester buffer for one answer | 4,194,304 UTF-16 code units | `LAB_SPEC.md` R41-L-06 | `ClaimTracker.MAX_COLLECT_CHARS` | lab test |
| Desktop GPU counter existence | absent after 3 consecutive misses; re-asked every 30 calls | `PLATFORM_PLAN.md` R41-P-02 | `GpuCounterExistence` | desktop `GpuExistenceTest` |

*Carried from r4 and earlier (marked PROVISIONAL, never ratified):*

| Group | Values | Spec |
|---|---|---|
| Session limits | 4 unauthenticated connections per source; ticket reclaim after 10 s; body wait `min(deadlineMs, 30,000 ms)`; request wait 30,000 ms; a cancelled attempt must end within 30,000 ms; write stall 30 s; 16 extension frames before `HELLO`; `attemptId` LRU 65,536 (24 h); session-id LRU 65,536 | `trust.md` R4-T-08 |
| `ESTIMATED` overhead form | JSSE `R` 38, `Lmax` 16,367; floor `R` 22, `Lmax` 16,384; handshake allowance 8,192 bytes per session | `LAB_SPEC.md` R4-L-25, R41-L-01; design §8.4 |
| Router parameters | `peerBiasMs` 1,000; `msPerBatteryPermille` 2,000; `heatPermille` 500 / 250; `minDecodeMilliTokPerSec` 4,000; `maxTtftMs` 20,000; default `deadlineMs` 120,000; `handshakeExtraMs` 40; `loadBytesPerMs` per class (500,000 to 2,000,000); path `kbps` LAN 100,000, OVERLAY 20,000; `powerMilliW` per class (5,000 to 150,000); `queueBucketMs` 0 / 10,000 / 30,000; `quantPenaltyMs` table; `msPerRankStep` 5,000; `cloudDecodePriorMilliTokPerSec` 50,000; `maxAttempts` 6 (3 peers); `offerTimeoutMs` 2,000; `headTimeoutMs` `max(5,000, 2 × ttftMs)`; peer breaker capped at 30 s | `LAB_SPEC.md` §6.4 |
| Claim tracker | `WIN` 20, `MIN_KEEP` 3, `MIN_STATE` 5, `CORR` 800‰, `DISC` 600‰, `RATIO_CAP` 5,000‰, `SHORT_BYTES` 128; class `bpt` default 4,000 / 8,000‰; `disc` 700 / 400‰; discard budget (more than 50% of the last 20, once 4 exist); penalty 7 days, doubling | design §5.7 (calibrate at M1 on ≥ 2 devices × ≥ 2 backends) |
| Linux host | D-Bus retry 30 s; sleep-gap fallback (15 s window, 5 s poll); thermal fallback 85 °C; control socket 8 connections, 30 s idle, 1 MiB | `platforms/linux.md` R4-LX-02 (bus, sleep gap), R4-LX-03 (thermal), R4-LX-06 (socket) |
| Benchmark plans | the `quick` and `ci` plan values the §5.1 table leaves silent | `benchmark.md` R4-B-06 |

- Options: (a) ratify every value **as a starting value** for the lab and the M1 build, each to be re-reviewed with the D-v2 owner-device data before M1's gate; (b) ratify item by item; (c) replace the values you name.
- **Recommendation: (a)**, with three you may want to judge yourself because they trade privacy or safety for convenience: the 60 s grace (how long a stolen phone's session can outlive the 30-minute cap, T9), the 30 s presence retry (D44) and the 10,000 mWh self cost (it decides how strongly an unknown battery pushes work away from this device).
- Blocked until answered: nothing now; M1's gate (every PROVISIONAL value of a promoted module must be ratified or replaced before M1, `trust.md` §16 status).

**D44 (new) — What a requester may learn from a presence hold** (`lab/ERRATA.md` ERR-FX4-LD-2; design §7.4 and §7.12 r4.1; `trust.md` R41-T-02). r4 made a presence decline carry the remaining hold-down (`LAB_SPEC.md` R4-L-17), which let a paired requester compute the lender's last user input to the millisecond (`last = now − (600,000 − retryAfterMs)`) and tell presence from a condition. r4.1 (AD-7 A3) sends the constant 30,000 ms instead, so **one decline says nothing about when the owner last touched the device**. Two things remain: (i) the switch from decline to accept when the 10-minute hold-down ends: a requester that keeps offering (rpm 30 allows one offer every 2 s) brackets that moment, and therefore the last presence signal, to within its offer interval; (ii) the cost of the constant: an honest requester re-offers every 30 s during a hold-down instead of waiting for its end (up to about 20 metadata-only offers per hold, each with a row on both nodes).
- Options: (a) accept r4.1 as written and keep the residual stated (design §7.12); (b) as (a), plus a random extension of each presence hold-down drawn on the lender, uniform in 0–120 s (PROVISIONAL), never sent, so the end of the hold-down no longer dates the last input (W07p vectors gain the drawn value as an input); (c) as (a), plus a per-peer throttle during a presence hold: the lender evaluates at most one offer per peer per `retryAfterMs` and repeats its last answer to the others, which coarsens the switch to 30 s; (d) restore r4's remaining hold-down (efficient re-offers; the countdown leaks to the millisecond).
- **Recommendation: (b).** It is the only option under which neither a single decline nor patient probing dates the owner's last input to better than about two minutes, and its cost is a short, lender-local delay before lending resumes (at most 2 minutes after a 10-minute hold). (c) is weaker (a 30 s bracket) and (d) undoes the r4.1 fix.
- Blocked until answered: nothing now; LP-2's text, the W07p presence vectors and IC-3(ii)'s wording before M1.

**D45 (new) — Binding the Windows and macOS LAN confirmation to the network** (review finding A-LA-7, second half; `desktop/ERRATA.md` ERR-FX4-DK-1; `trust.md` R41-T-05; `platforms/windows.md` §4.1, `platforms/macos.md` §4.1). On Linux the user's LAN confirmation is bound to a network fingerprint, `SHA-256(interface name ‖ default-gateway IPv4 ‖ gateway MAC)`, and a changed fingerprint closes the LAN listener until it is confirmed again (`platforms/linux.md` §4.1). On Windows (and macOS) the confirmation names the interface only, so the same Wi-Fi adapter confirmed at home stays confirmed on a café network. r4.1's adapter-type rule (AD-7 A12) closes the cellular and CGNAT confusion, not this.
- Options: (a) bind the Windows and macOS confirmation to the same fingerprint (gateway IPv4 and MAC from the OS routing and neighbour tables), re-checked at every re-bind; a changed fingerprint drains the LAN listener until re-confirmed; (b) keep the interface-only confirmation and say so in the confirmation copy; (c) ask for a fresh confirmation at every network change, with no fingerprint.
- **Recommendation: (a).** It is the same rule on every desktop and the one D8(b)'s "confirmed-LAN direct" assumes. Its stated limit is Linux's: a device that clones the gateway's IP and MAC matches; authentication still rests on pinned keys. Cost: a change to what `lan-confirm` records and Windows and macOS reads of the gateway MAC (NEEDS-DEVICE-VALIDATION).
- Blocked until answered: the Windows and macOS LAN listener (the confirmed-LAN path of D8(b), wherever D28 places those hosts); nothing now (no listener is wired).

**D46 (new) — Registry rows whose status cannot be read** (review finding B-TL-9; `lab/ERRATA.md` ERR-FX4-TR-5; `trust.md` R41-T-07). A peer row whose status integer is unknown (or whose class is not `own`) denies everything and closes its sessions (L5), which is right; but it can then be neither revoked, forgotten nor re-paired (`CORRUPT_ROW`), and the only way out was wiping the store. Committed vector **W04-613** pins `revoke:Refused(CORRUPT_ROW)` for a planted status 9, and it is also the Swift lane's contract. The lab first tried letting the ordinary revoke overwrite the status, which broke W04-613; it now carries a separate, explicit, local call (`revokeCorrupt`) that applies only to a corrupt row, writes REVOKED (lowering only, reason `user-corrupt`) and after which `forget` works. The Swift lane has no way out.
- Options: (a) keep W04-613 and make the explicit local operation normative ("Remove this unreadable peer": corrupt rows only; writes REVOKED; refuses a clean row, an absent row and an unreadable store), with a Swift mirror and new W04 vectors added at the `VERSION` 0.3.0 bump; (b) let the ordinary revoke overwrite a corrupt status with REVOKED and regenerate W04-613 (and the Swift expectation) at the bump; (c) no recovery in the registry: the user wipes the peer store (every pairing is lost).
- **Recommendation: (a).** The routine operation stays strict (a corrupt row is never changed by an action the user took for another reason), the way out is explicit, local and only lowers trust, and no committed vector changes meaning. (b) is simpler but quietly changes what a frozen vector says; (c) punishes the user for a disk fault.
- Blocked until answered: nothing now; the Swift registry and the Peers screen's copy at M1/M1b; a W04 vector at the 0.3.0 bump.

**D47 (new) — What "records" means in L-L15's overhead bound** (`lab/ERRATA.md` ERR-R4B-LT-4; `LAB_SPEC.md` R4-L-25). R4-L-25 says a measured session's overhead lies within `[22 × records, records × R + handshake allowance]` but does not say how `records` is counted for the upper end; the lab's committed law (`withinRecordBound`) uses 22, one record per flush and a 32,768-byte handshake allowance, and the lab added an additive ceiling (overhead ≤ the `ESTIMATED` form over both directions' flushes plus two close_notify records).
- Options: (a) `records` = the sum over flushes of `ceilDiv(application bytes of the flush, Lmax)` (the estimated record count): the lab's additive ceiling becomes the law and the 32,768 allowance is tightened to 8,192; (b) `records` = the number of TLS records a record tap parses (exact, but it needs a record parser per stack, and Network.framework has none); (c) keep the lab's looser committed bound and drop the literal interval from R4-L-25.
- **Recommendation: (a).** It is computable on every stack from the plaintext tap, and the lab's long run (603 frames per end) already shows it separates `R` 22, 38 and 54.
- Blocked until answered: nothing now; L-L15's text when the ledger is promoted at M1.

**D48 (new) — A newline in answer normalisation** (`lab/ERRATA.md` ERR-R4B-TR-1, ERR-FX3-TP-4; `LAB_SPEC.md` R41-L-06). R4-L-19 lists "control characters removed, whitespace runs collapsed"; every lane reads it literally, so a tab or a newline (`Cc`) is removed, not collapsed to a space. Formatted honest answers (code, markdown tables) lose up to about 22% of their raw bytes. With r4.1's rule that the bytes-per-token table is measured on the same normalised text, an honest lender still reads its true ratio (M08-107); only a table measured on raw bytes would under-rate it (M08-108).
- Options: (a) keep removal (r4.1 text); (b) collapse the whitespace controls (U+0009–U+000D, U+0085) together with the `Zs`/`Zl`/`Zp` runs into one space; (c) count each newline as one byte (kept, not collapsed).
- **Recommendation: (a) for mesh-1.** It is the direction that can only lower a claim, and the normalised `bpt` basis removes its cost for honest lenders; revisit if the D-v2 reference run shows a per-language or per-format bias.
- Blocked until answered: nothing now; the rule for generating the bench-core bytes-per-token table (A37).

### J.3 What r4.1 adds up to

If every r4.1 recommendation is taken: AD-7 is ratified, and of the new items only D42 can change the contract count of design §8.3 (one app-facing error value if D42(a) is ruled for the residual cases). Everything else in r4.1 is lab behaviour, normative text and PROVISIONAL numbers. The single next step is unchanged: rule **D5 (with D34) and D23**, and send the hardware inventory; v1 device validation remains open.
