# Design revision 4: change log

**Date:** 2026-10-07 · **Revision:** r4 of the mesh design (`ASOM_MESH_DESIGN.md`; r3 was 2026-09-30) · **Scope:** a docs-only change under `docs/design/mesh/`. No code, vector, workflow or file outside this directory was changed; `PROGRESS.md` gets one pointer line.

**What r4 does.** It folds into the normative text (a) the 39 findings of `REVIEW_ROUND3.md`, which r3 left undispositioned, and (b) every reading the implementation tracks had to choose where the spec was silent, ambiguous or wrong, as recorded in `lab/ERRATA.md`, `desktop/ERRATA.md`, `apple/ERRATA.md` and `ubuntu-touch/ERRATA.md`. Each item is **ADOPTED** (the normative text now says it; the old text is quoted in the amendment), **OWNER DECISION** (put to the owner in `OWNER_DECISIONS.md` with options and a recommendation), **REJECTED** (not taken, with the reason) or **DEFERRED** (recorded, not specified now). "NO SPEC ITEM" marks an ERRATA row that records process, write-set or test mechanics and needs no spec text.

**Where the normative changes are.** Amendments carry ids and quote the old text:

| File | Section added | Ids | Inline `r4` markers |
|---|---|---|---|
| `LAB_SPEC.md` | §10 Revision 4 amendments | R4-L-01 … R4-L-27 | header, §2.4 check 4, §2.5, §3.6, §4.6 steps 17–18, §6.5, §7.4, §7.5, §7.6, §7.7 FC-5 |
| `trust.md` | §16 | R4-T-01 … R4-T-13 | title note, §3.2 resumption row, §3.3 limits line, §4.5 deleted sentence |
| `benchmark.md` | §23 | R4-B-01 … R4-B-10 | title note, §6.4 end reasons, §8 banner, §11.3 soft ceiling, §13.3 flags |
| `PLATFORM_PLAN.md` | §13 | R4-P-01 … R4-P-14 | header, P2, §1.1 S5, §1.2, §7 smoke, §8 Android holon line, §11 |
| `platforms/linux.md` | §12 | R4-LX-01 … R4-LX-07 | title note |
| `platforms/ubuntu-touch.md` | §12 | R4-UT-01 … R4-UT-08 | title note |
| `ASOM_MESH_DESIGN.md` | header "What changed in r4", §12.0 | — | §0.1, §0.5, §0.8, §1.1, §1.2, §1.5 X13, §3.1, §3.4.2, §3.6, §4.2 T2/T5/T6/T7, §4.3, §4.4, §5.7, §7.1, §7.8, §8.2 IC-4(b) and RT-1, §8.3 count, CD-19, RT-6, RT-15, §8.4, §9.1, §9.3, §10.0d, §10.2 D7/D18, §10.4 D12, §11 K11 |
| `OWNER_BRIEF.md` | — | — | revision, sentences 1 and 2, the count line (still byte-identical to §8.3's), the decision table heading and D12 row |
| `OWNER_DECISIONS.md` | new | D12.0, D12.4b, D30–D41, RT-15, two (sub) items, plus every r3 open decision | — |

**Precedence (unchanged rule, restated).** The design wins over `LAB_SPEC.md`, `PLATFORM_PLAN.md` and the siblings; within each of those files, its r4 amendments section wins over its r3 body.

---

## 1. Round-3 review findings (`REVIEW_ROUND3.md`): every finding

| Finding | Sev | Disposition | Where changed | Pins / builder action |
|---|---|---|---|---|
| R3-CONFORMANCE-1 | high | ADOPTED in part / OWNER DECISION | IC-4(b) is an enumerated list (desktop owner CLI; the asom app's own screen on Ubuntu Touch and iOS); "iOS host apps" removed from CD-19, the §8.4 schema comment and `LAB_SPEC.md` R4-L-24; RT-1 text names IC-4(b). Placement (Amendment 3 or 5) → D25 (sub) | — |
| R3-CONFORMANCE-2 | high | ADOPTED | `LAB_SPEC.md` R4-L-03; `PLATFORM_PLAN.md` R4-P-01 (the lab's `isolation.py` pinned-base mechanism, already built by every track) | the synthetic-repository self-test replaces the negative-control branch (ERR-CI-2) |
| R3-CONFORMANCE-3 | medium | ADOPTED | `LAB_SPEC.md` R4-L-04 and §2.5 note; design §3.6 ("chooses" removed) | — |
| R3-CONFORMANCE-4 | medium | ADOPTED | design §0.1, §3.1 (Android "full from M2+"); `OWNER_BRIEF.md` sentence 1; `PLATFORM_PLAN.md` §8 line | — |
| R3-CONFORMANCE-5 | medium | ADOPTED | `OWNER_BRIEF.md` sentence 1 (traffic addressed only to paired devices; relays and client logs stated) | — |
| R3-CONFORMANCE-6 | medium | OWNER DECISION | D37 (a proposed revision of D-C); design §10.2 D18 note | blocks D18 / v2 P6 wording |
| R3-CONFORMANCE-7 | medium | OWNER DECISION | D7/D24 (sub) iPhone bench shell; design §10.2 D7 note; `PLATFORM_PLAN.md` R4-P-14 | — |
| R3-CONFORMANCE-8 | medium | OWNER DECISION | D12.0 (`X-Asom-Egress: peer`) added to design §10.4, §9.3 M1 entry, `PLATFORM_PLAN.md` R4-P-04; RT-1 states AD-2 does not approve the header value | W00-100..104 stay `proposed` (ERR-FX-SKIP) |
| R3-CONFORMANCE-9 | medium | ADOPTED | RT-1 replacement text (design §8.2) names both granularity narrowings; listed under AD-2 in `OWNER_DECISIONS.md` | — |
| R3-CONFORMANCE-10 | low | ADOPTED | design §9.3 (D21 in D-v2; D22 Apple Team ID in M1b); `PLATFORM_PLAN.md` R4-P-04 | — |
| R3-CONFORMANCE-11 | low | ADOPTED | design §10.0d: iPhone lend-screen lending, battery lending and the desktop `asom-bench` CLI removed (left to D16, D28, D7) | — |
| R3-CONFORMANCE-12 | low | ADOPTED | design §1.1 R1c, §1.2 C-9, §1.5 X13, §7.8, §8.3 RT-6 | — |
| R3-CONFORMANCE-13 | low | ADOPTED | `PLATFORM_PLAN.md` R4-P-03 (scaffold defined; built-ahead items labelled; "no listener" = no network socket); `platforms/linux.md` R4-LX-06 | desktop ERR-SCOPE-1, ERR-DL2-3 already label them |
| R3-CONFORMANCE-14 | low | ADOPTED | `PLATFORM_PLAN.md` R4-P-05; `platforms/ubuntu-touch.md` R4-UT-08 | builder action at UT-1 |
| R3-OVERCLAIM-1 | high | ADOPTED in part / DEFERRED | design §5.7 and K11 (honest residual about 5×); `LAB_SPEC.md` R4-L-19 (normalised `outBytes`); the requester length-EWMA check DEFERRED (k needs simulation) | builder action: normalisation in both lanes; rewrite M08-017; new M08 padding vectors (a)(b)(c) |
| R3-OVERCLAIM-2 | high | ADOPTED in part | design §0.8, §7.1 Deck row and placement row (0.8–2.8×, "about 2–5×", the LO-basis usability-gate note); `OWNER_BRIEF.md` sentence 2. A full phone row in §7.1's table is DEFERRED to the D-v2 owner-device measurement, which replaces every figure | — |
| R3-OVERCLAIM-3 | high | ADOPTED | `LAB_SPEC.md` R4-L-25 (three independent instruments; overhead bound); design §8.4 and `PLATFORM_PLAN.md` R4-P-06 (Network.framework ESTIMATED; I3 against `sentApplicationByteCount`) | implemented in the lab: ERR-LL-8, ERR-PL-10, ERR-PI-1, ERR-PI-6, ERR-FX-5 |
| R3-OVERCLAIM-4 | medium | ADOPTED in part | design §3.4.2; `platforms/linux.md` R4-LX-04 (unknown is unsafe; claim restated; per-OS presence stated). The game/docked mechanism needs a spike (DEFERRED); a per-OS presence-input column in §3.1 is DEFERRED to the D-v2 revision | desktop ERR-DECK-1, ERR-FSM-4 |
| R3-OVERCLAIM-5 | medium | ADOPTED | as R3-CONFORMANCE-4 | — |
| R3-OVERCLAIM-6 | medium | ADOPTED | `trust.md` R4-T-01; `LAB_SPEC.md` R4-L-23; design §4.2 T2 and §4.4 | per-lane W08 expectation recorded (ERR-PL-1: JDK 17 and 21 both refuse) |
| R3-OVERCLAIM-7 | medium | ADOPTED | `LAB_SPEC.md` R4-L-05 (sparse-checkout independence; tag `cross-lane`); `PLATFORM_PLAN.md` R4-P-02 | no tag is cleared by r4 |
| R3-OVERCLAIM-8 | medium | ADOPTED | design §3.1 Windows row; `PLATFORM_PLAN.md` R4-P-11 (service mode only; device item W8-window); D28 note | — |
| R3-OVERCLAIM-9 | medium | ADOPTED | `PLATFORM_PLAN.md` R4-P-08; `platforms/ubuntu-touch.md` R4-UT-07 | record the image digest and FROM chain (builder) |
| R3-OVERCLAIM-10 | low | ADOPTED | (a) `platforms/linux.md` R4-LX-02 (LF05 split; assumption LA26 defined there), design §3.4.2; (b) design §8.4, R4-P-06; (c) `OWNER_DECISIONS.md` D8 note (tailscale issue 21088; DV-A8 gate) | — |
| R3-OVERCLAIM-11 | low | ADOPTED | `PLATFORM_PLAN.md` R4-P-07 | — |
| R3-OVERCLAIM-12 | low | ADOPTED | design §4.4 and §3.1 Ubuntu Touch row; `platforms/ubuntu-touch.md` R4-UT-07 | — |
| R3-OVERCLAIM-13 | low | ADOPTED | design §0.5 ("six values in five sign-off lines …"), §9.3 M1 scope (+CD-6b, CD-FO); `PLATFORM_PLAN.md` R4-P-10 (11.5–18.5) | — |
| R3-CLOSURE-1 | high | ADOPTED | `LAB_SPEC.md` R4-L-13 (`projectFile` and the FILE bench projection), R4-L-14 (B-patches, PP3) | pinned by M02-110..112, M02-115, M03-129, M03-132, M03-134, M03-135, M03-160..166, M06-201..204 |
| R3-CLOSURE-2 | high | ADOPTED | `LAB_SPEC.md` R4-L-01, R4-L-02 (commit manifest; "self-contained" retracted; this file is the amendment index) | — |
| R3-CLOSURE-3 | high | ADOPTED (observation → D38) | `LAB_SPEC.md` R4-L-09 and §3.6 note | pinned by W01b-002, -003, -004, -007 |
| R3-CLOSURE-4 | medium | ADOPTED | `LAB_SPEC.md` R4-L-06; design §9.1; `PLATFORM_PLAN.md` R4-P-02 | — |
| R3-CLOSURE-5 | medium | ADOPTED in part / OWNER DECISION | `LAB_SPEC.md` R4-L-16, R4-L-18 (types, fields, tracker key, claim decode, warm E1); `confFloor` caller-supplied (R4-L-12); class-ceiling values → D40; the 1-in-10 cap withdrawn (design T5) | pinned by R01-082..087, R02-047, R02-048, R03-032, R03-033 |
| R3-CLOSURE-6 | medium | ADOPTED in part / OWNER DECISION | `LAB_SPEC.md` R4-L-24 (the three grouping rules); the column versus `routeDetail` prefix → D34 | pinned by L02-013, L02-020 |
| R3-CLOSURE-7 | medium | ADOPTED | as R3-OVERCLAIM-6; S-A9 row recorded (JSSE measured; others UNVERIFIED) | — |
| R3-CLOSURE-8 | medium | ADOPTED | design §9.3 (D21, D22, D25 in entries; D12.0 and D34 in M1); `PLATFORM_PLAN.md` R4-P-04. CD-DOC1 "M1 only" is read from D3's placement in M1's entry (no earlier landing) | — |
| R3-CLOSURE-9 | low | OWNER DECISION | RT-15 added to design §8.3; `OWNER_DECISIONS.md` RT-15 (the roadmap itself is outside this change) | count line now says 15 text changes |
| R3-CLOSURE-10 | low | ADOPTED | design §0.5, §10.4 D12 header; D12.4b split out | — |
| R3-CLOSURE-11 | low | ADOPTED | `LAB_SPEC.md` R4-L-07 | pinned by W00-001, W00-002, W01-100..299 |
| R3-CLOSURE-12 | low | ADOPTED | as R3-OVERCLAIM-7 | — |

**Totals:** 29 ADOPTED in full; 6 ADOPTED in part (the remainder is an OWNER DECISION or DEFERRED as stated); 4 OWNER DECISION; 0 REJECTED.

---

## 2. Implementation readings: `lab/ERRATA.md`

Every id of the file, grouped. "LAB_SPEC R4-L-nn" etc. name the amendment that adopts it.

| Ids | Disposition | Where | Pinned by / note |
|---|---|---|---|
| ERR-ISO-1, ERR-ISO-3, ERR-CI-1, ERR-CI-2 | ADOPTED | LAB_SPEC R4-L-03 | the root floor is 140 at r4 (re-pin 2026-10-07) |
| ERR-ISO-2 | ADOPTED | LAB_SPEC R4-L-04 | — |
| ERR-W01B-1, -2, -3, -4 | ADOPTED | LAB_SPEC R4-L-09 | W01b-002, -003, -004, -007 |
| ERR-W01B-5, ERR-LL-10, ERR-FX-SKIP | NO SPEC ITEM | — | W01b-reach and W00-100..104 stay `proposed` until D3 and D12 are ruled |
| ERR-FMT-1 … ERR-FMT-5, ERR-ENV-1, ERR-ENV-2, ERR-W00-1, ERR-R5-1, ERR-GEN-1, ERR-BUILD-1 | ADOPTED | LAB_SPEC R4-L-07 | W00-001, W00-002, W01-100..299 |
| ERR-ORA-1, ERR-JSON-8, ERR-CLOSURE-12, ERR-XCK-1, ERR-VEC-1, ERR-R6-11, ERR-PT-11, ERR-PW-12 | ADOPTED | LAB_SPEC R4-L-05 | every vector stays `oracle: self` |
| ERR-PKG-1, ERR-RUN-1, ERR-REG-1, ERR-R6-12, ERR-PT-12, ERR-PT-13, ERR-PW-9, ERR-FX-RT-8, ERR-JSON-6, ERR-JSON-10, ERR-LP-8, ERR-LP-9, ERR-R6-13 | NO SPEC ITEM | — | write-set, package and directory records; the directories they chose stand (LAB_SPEC §3.1's layout is read as illustrative) |
| ERR-DOC-1 | ADOPTED | LAB_SPEC R4-L-01 | — |
| ERR-JSON-1 … ERR-JSON-5, ERR-JSON-9, ERR-JSON-7 | ADOPTED | LAB_SPEC R4-L-10 | M01-013..017, M01-107, M01-111..116, M01-128, M01-140..153, M01-301..325; M01-201..206 belong to `:manifest` (the DER codec, M01der) |
| ERR-CLOSURE-1 | ADOPTED | LAB_SPEC R4-L-13 | M02-110..112, M02-115, M03-129, M03-132, M03-134, M03-135, M03-160..166, M06-201..204 |
| ERR-CLOSURE-4 | ADOPTED | LAB_SPEC R4-L-06 | — |
| ERR-CLOSURE-5 | ADOPTED / OWNER DECISION | LAB_SPEC R4-L-12 (`confFloor`), R4-L-15; benchmark R4-B-04 | L1 pins stay `UNPINNED` until D18 |
| ERR-CLOSURE-8 (…10), ERR-CLOSURE-11 | ADOPTED | design §9.3, §0.5, RT-15; LAB_SPEC R4-L-07 | — |
| ERR-BENCH-1, ERR-SCHEMA-1 | ADOPTED | LAB_SPEC R4-L-14 | PP3 is new (builder action) |
| ERR-BENCH-3 | OWNER DECISION | D32; benchmark R4-B-04 | the lanes disagree on minimum reps |
| ERR-BENCH-4, -6, -9 | ADOPTED | benchmark R4-B-04 | — |
| ERR-BENCH-5 | ADOPTED | benchmark R4-B-06 | M04-401, M04-402, M04-403 (builder action: transcribe quick and ci JSON) |
| ERR-BENCH-7 | ADOPTED | benchmark R4-B-03 | M04-430 |
| ERR-BENCH-8, -10 | ADOPTED | benchmark R4-B-08 | M05 texts |
| ERR-MAN-1, -2, -4, -5 | ADOPTED | LAB_SPEC R4-L-11, R4-L-12 | M03-144, M03-158, M03-159, M03-169, M02-107 |
| ERR-MAN-3 | ADOPTED | LAB_SPEC R4-L-02 (move to `history/r0/` withdrawn) | — |
| ERR-LL-1 | NO SPEC ITEM | — | a grep-law spelling workaround; R4's grep rule unchanged |
| ERR-LL-2 | ADOPTED (rules) / OWNER DECISION (column) | LAB_SPEC R4-L-24; D34 | L02-013, L02-020 |
| ERR-LL-3, -5 | ADOPTED | LAB_SPEC R4-L-24 | — |
| ERR-LL-4, -6, -7, -11 | ADOPTED | LAB_SPEC R4-L-26 | L02-017 |
| ERR-LL-8, -9 | ADOPTED | LAB_SPEC R4-L-25 | — |
| ERR-LP-1, -2, -3, -4, -7 | ADOPTED | LAB_SPEC R4-L-17 | L01-011, W07-072, W07-073 |
| ERR-LP-5 | ADOPTED (corrected rule) | LAB_SPEC R4-L-17 and §6.5 inline: `min(2, last + 1)` | **builder action: W07-075 pins the r3 no-op and must be regenerated** |
| ERR-LP-6 | ADOPTED | trust R4-T-12 | L02-021 |
| ERR-R6-1 | ADOPTED | design §5.7; LAB_SPEC R4-L-19 | M08 `padding-over-cap` vectors |
| ERR-R6-2 | ADOPTED | design §0.8, §7.1 | R02-r3-001 is arithmetic on spec inputs only |
| ERR-R6-3 | ADOPTED in part / OWNER DECISION | LAB_SPEC R4-L-16; D40; the 1-in-10 cap withdrawn | — |
| ERR-R6-4, -9, -10 | ADOPTED | LAB_SPEC R4-L-16 | R04 through `MeshRouter` (112 variants) |
| ERR-R6-5, -6 | ADOPTED | LAB_SPEC R4-L-18 | — |
| ERR-R6-7, -8, -15 … -24 | ADOPTED | LAB_SPEC R4-L-20 | SC01, SC04, SC06, SC09 |
| ERR-R6-14 | OWNER DECISION | D35 | `FindingsTest` pins today's behaviour |
| ERR-PW-1 … -8, -10, -11, -13, -14 | ADOPTED | LAB_SPEC R4-L-21 | W06 (incl. W06-001..004) |
| ERR-PW-15, ERR-PS-20, ERR-PS-21, ERR-PS-22, ERR-PL-17, ERR-PI-12, ERR-PI-13, ERR-PI-16, ERR-FX-W08, ERR-FX-L15, ERR-FX-CNT, ERR-FX-MUT, ERR-FX-DOCS-1, ERR-FX-HON, ERR-FX-3, ERR-FX-4 | NO SPEC ITEM | — | gate mechanics, record corrections, test strengthening. ERR-FX-DOCS-1 also notes failed hosted JDK 17 lab jobs at two heads (process, not spec) |
| ERR-PT-1, -2, -3, -4, -5 | ADOPTED | trust R4-T-06 | W05-307, W05-308 (old letter), W05-310..313, W05-341, W05-342; builder action: leaf-lifetime vector; move the T6 refusal into the verifier |
| ERR-PT-6, -7, -8 | ADOPTED | trust R4-T-10 | W04 |
| ERR-PT-9 | ADOPTED | trust R4-T-11 | — |
| ERR-PT-10 | ADOPTED as stated limit | trust R4-T-13 | L11 unexercised until a listener exists |
| ERR-PT-14, ERR-PL-8, ERR-PL-11, ERR-PS-8 (API part) | NO SPEC ITEM | — | API shapes; the `verifiedPin` request stays with the owning track |
| ERR-PL-1 … -7, -9, -12 … -15 | ADOPTED | LAB_SPEC R4-L-23; trust R4-T-01 … R4-T-07 | W08 |
| ERR-PL-10 | ADOPTED | LAB_SPEC R4-L-25 | — |
| ERR-PL-16 | NO SPEC ITEM | — | no per-OS skips; Windows not run |
| ERR-PS-1, -5, -12, -13, -15 | ADOPTED | LAB_SPEC R4-L-21; trust R4-T-08, R4-T-12 | — |
| ERR-PS-2, -3, -8, -16, -17 | ADOPTED | LAB_SPEC R4-L-25 | — |
| ERR-PS-4 | ADOPTED | trust R4-T-12 | — |
| ERR-PS-6, -7, -14 | ADOPTED (PROVISIONAL values) | trust R4-T-08 | — |
| ERR-PS-9, -10 | ADOPTED | LAB_SPEC R4-L-25 (`MANIFEST_RECEIVED` verdict in `meshCode`; 26 of 32 codes through a session) | — |
| ERR-PS-11, -19 | ADOPTED | LAB_SPEC R4-L-26 (FC-6, L-L13 scope) | — |
| ERR-PS-18 | ADOPTED (as superseded) | trust R4-T-08 | superseded by ERR-PI-3, -4, -5 and ERR-FX2-PS-* |
| ERR-PI-1, -2, -6, -7, -8, -10, -11 | ADOPTED | LAB_SPEC R4-L-25 | builder action: `:ledger-model` ESTIMATED form (38 bytes, 16,367) |
| ERR-PI-3 | ADOPTED in part | trust R4-T-08 (idle semantics). **Its max-age of 24 h is REJECTED**: design §4.3/T9 fix 30 min | builder action: `Session.tick` max-age 30 min |
| ERR-PI-4, -5, -9, -14, -15 | ADOPTED | trust R4-T-08; LAB_SPEC R4-L-25, R4-L-26 | — |
| ERR-FX-CV1, CV4, CV5, CV6, CV7, CV8, CV10 | ADOPTED | LAB_SPEC R4-L-10, R4-L-11, R4-L-12 | M03-179..190, M02-122, M02-113, M03-193, M02-121, M03-194, M03-195, M03-196, M03-197..214, M03-215 |
| ERR-FX-CV3 | ADOPTED | LAB_SPEC R4-L-12 and §4.6 steps 17–18 inline (A1 is a label; only A0 is proven) | M03-191, M03-192, M03-124; M02-118, M02-120 retagged; apple cross-lane M02-930 |
| ERR-FX-CV9 | ADOPTED | LAB_SPEC R4-L-13 | `SeqRaceTest` (no vector) |
| ERR-FX-FILES, ERR-FX-VER | ADOPTED (rule restated) | LAB_SPEC R4-L-08 | builder action: `VERSION` 0.3.0 in one commit, after D30–D32 are ruled |
| ERR-FX-RT-1, -3, -4 | ADOPTED | LAB_SPEC R4-L-18 | R01-082, R01-083, R02-047, R02-048, R03-032, R03-033, M08-065..082 |
| ERR-FX-RT-2 | ADOPTED | LAB_SPEC R4-L-16 | R01-084..087 |
| ERR-FX-RT-5, -6, -7 | ADOPTED | LAB_SPEC R4-L-17 | R06-055..068, R01-088..091, R02-049, R02-050 |
| ERR-FX-RT-3 note (`wasBad` needs `claimSeq`) | ADOPTED (stricter) | LAB_SPEC R4-L-18 | builder action + vector |
| ERR-FX-RT-1 residual (ratios across a backend switch) | REJECTED (no new rule) | LAB_SPEC R4-L-18 | `DISCARD_SETTINGS` already excludes mismatched observations |
| ERR-FX-1 | ADOPTED | LAB_SPEC R4-L-24 | `SinkTest`, `DurabilityHarnessTest` |
| ERR-FX-2 | ADOPTED | LAB_SPEC R4-L-21 | L02-022, L02-023 |
| ERR-FX-5 | ADOPTED (a); (b), (c) NO SPEC ITEM | LAB_SPEC R4-L-25 (inclusive bound) | (b) an R02 boundary vector and (c) the RL16 counter are builder test actions |
| ERR-FX2-TT-1 | ADOPTED (30 s PROVISIONAL) | trust R4-T-08 | builder action: GOAWAY handed to the session driver, never written on the revoking thread |
| ERR-FX2-TT-2 | ADOPTED | trust R4-T-11 | `PeerRegistryConcurrencyTest` |
| ERR-FX2-TT-3 | ADOPTED | trust R4-T-04 | W08 `alert-uniform` |
| ERR-FX2-PW-1, -2, -3, -4, -5, -6 | ADOPTED (commit-before-reveal `PairProfile.R3`, typed code, no expiry gate on S, TLS-role directions, connection binding, 16-frame budget) | trust R4-T-10; LAB_SPEC R4-L-22, R4-L-25 | **builder action (ERR-FX2-PW-7): the 41 W04 `fsm` vectors, 104 `qrParse` vectors and `PairingFsmExhaustiveTest` pin the r0 defaults; add the listed W04 vectors and laws, move r0 to `R0_COMPAT`, re-orient the harnesses, flip the defaults** |
| ERR-FX2-PW-6 (established-session budget part) | DEFERRED | LAB_SPEC R4-L-25 | a budget for `EXT_IGNORED` and `REVOKE_NOTICE` rows of an established session is not specified |
| ERR-FX2-PW-7 | ADOPTED (as the builder plan) | trust R4-T-10 | — |
| ERR-FX2-PS-1, -2, -3, -5, -6, -7 | ADOPTED (PROVISIONAL values) | trust R4-T-08 | builder action for the `HELLO_NONCE_REUSE` refusal kind is optional (the wire code is `PROTOCOL_ERROR`) |
| ERR-FX2-PS-4 | ADOPTED | trust R4-T-08 | the same check for `PAIR_HELLO` is a builder action |
| ERR-FX2-PS-8, -9 | NO SPEC ITEM | — | callback containment and test boundaries |
| ERR-FX2-PS-10 | ADOPTED (supersession record) | trust R4-T-08 | — |
| ERR-FX2-BD-1, -2, -4, -5 | ADOPTED | benchmark R4-B-04, R4-B-05 | M04-037..039, M04-043, M04-055..060, M05-211 |
| ERR-FX2-BD-1 (refuse a misplaced sustain block) | OWNER DECISION | D33 | — |
| ERR-FX2-BD-3 | OWNER DECISION | D30 (`thermal-drift`), D31 (`restarted`, `swapped`) | `FixBenchDeriveTest` pins current JVM behaviour; 42 + 5 lane disagreements |
| ERR-FX2-BD-6 | OWNER DECISION | D32 | M04-001, the M04-002b vector |
| ERR-FX2-BD-7 | NO SPEC ITEM | — | the Python cross-check ports the r4 readings |
| ERR-FX2-BE-1 | ADOPTED | benchmark R4-B-01 | **builder action: M04-443, M04-444 pin the old names** |
| ERR-FX2-BE-2, -3, -4 | DEFERRED | benchmark R4-B-02, R4-B-10 | schema B-patches and an executor change; unspecified thresholds |
| ERR-FX2-BE-5 | ADOPTED (no change: `null` is valid) | benchmark R4-B-05 | — |
| ERR-FX2-BE-6 | ADOPTED | benchmark R4-B-09 | `SustainZeroTokenWindowTest` |
| ERR-FX2-BE-7 | OWNER DECISION | D36 | M05 texts change with the ruling |

## 3. Implementation readings: `desktop/ERRATA.md` (ids are that file's)

| Ids | Disposition | Where | Note |
|---|---|---|---|
| ERR-ISO-1, ERR-ISO-2, ERR-ISO-3, ERR-CI-1 | ADOPTED | LAB_SPEC R4-L-03, R4-L-04; PLATFORM_PLAN R4-P-01 | the desktop pin and baseline are the lab's mechanism |
| ERR-SCOPE-1, ERR-DL2-3, ERR-DL2-4, ERR-DL2-5 | ADOPTED | PLATFORM_PLAN R4-P-03; linux R4-LX-06 | ControlServer built, not started |
| ERR-SEAM-1, ERR-SEAM-2, ERR-GATE-1, ERR-TEST-1, ERR-TEST-2, ERR-ENV-1, ERR-FIX-1, ERR-DL2-10, ERR-DL2-14, ERR-MC-SOCK-1, ERR-DL3-RPM-1, ERR-DL2-15 (items 1, 2) | NO SPEC ITEM | — | seams, test mechanics, synthetic fixtures (labelled), hosted-CI fixes; ERR-MC-SOCK-1's 102-byte macOS path limit is CI-hosted-VM evidence |
| ERR-FSM-1, -2, -3, -5, -6 | ADOPTED | linux R4-LX-05 | — |
| ERR-FSM-4, ERR-DECK-1 | ADOPTED | linux R4-LX-04; design §3.4.2 | spike for the mechanism DEFERRED |
| ERR-DECK-2, ERR-DL2-6, -7, -8 | ADOPTED | linux R4-LX-02 | LA26 settled by DV-D4 |
| ERR-THERM-1, -2, ERR-POWER-1, ERR-GPU-1, ERR-CPU-1, ERR-FX-12 | ADOPTED | linux R4-LX-03 | 85 °C fallback PROVISIONAL |
| ERR-HOST-1, -2, ERR-CLI-1, -2, -3, ERR-DL2-1, -2, -9, -13, ERR-FX-13 | ADOPTED | linux R4-LX-06 | `jdk.net` in the jlink list (DL3) |
| ERR-SINK-1, ERR-FX-11 | ADOPTED | LAB_SPEC R4-L-24 | — |
| ERR-DL2-11, ERR-DL2-15 (item 3) | ADOPTED | linux R4-LX-01 | `SuccessExitStatus=143` added to the normative unit list |
| ERR-FX-8 | OWNER DECISION | D41 | — |
| ERR-DL2-12, ERR-FX-3, ERR-FX-4, ERR-FX-14 | ADOPTED (builder actions on workflows) | PLATFORM_PLAN R4-P-13 | workflows are outside this change |
| ERR-FX-1, -2, -7, -9 | ADOPTED | linux R4-LX-07 | owner input: the OpenPGP fingerprint (`OWNER-FILL`) |
| ERR-FX-5 | ADOPTED | PLATFORM_PLAN R4-P-12 | builder action: Windows and macOS hosts |
| ERR-FX-6, ERR-FX-10 | NO SPEC ITEM | — | law checkers and CI cross-check steps |

## 4. Implementation readings: `apple/ERRATA.md` (ids are that file's)

| Ids | Disposition | Where | Note |
|---|---|---|---|
| E-01, E-15 (orchestrator section), E-29 (fix-docs section) | ADOPTED | LAB_SPEC R4-L-03 | the root check runs `lab/tools/isolation.py` |
| E-02, E-16, E-17, E-33, ERR-FX2-ASC10 (labelling part) | ADOPTED | LAB_SPEC R4-L-05 | the Swift lane is cross-lane, never independent |
| E-03, E-04, E-19 | ADOPTED | LAB_SPEC R4-L-06 | — |
| E-05, E-06, E-07 (as superseded), E-09, E-10, E-11, E-20, E-21, E-22, E-27, F-2, ERR-FX-CV1 … CV8, CV10 | ADOPTED | LAB_SPEC R4-L-10 … R4-L-12 | see the lab rows |
| E-08, E-12, E-13, E-14, E-15 (`lines` entry), E-28, E-36, E-41, F-3, F-5, F-6, LF-3, ERR-FX-M08 (merge part), ERR-FX2-SW1, ERR-FX2-SW2 | NO SPEC ITEM | — | layout, equivalent mutants, r0 vector facts, vector-format readings, merge records, a description typo (M05-211) |
| E-17 (wordings), ERR-FX2-SW2 (NOTES rules) | ADOPTED | benchmark R4-B-08 | M05-101 … M05-111, M05-208, M04-057..059 texts |
| E-18, E-40, ERR-FX2-ASC04, ERR-FX2-ASC09 | ADOPTED | LAB_SPEC R4-L-13 | builder action: per-export key generated inside `signPresentation` on the production path |
| E-23 | ADOPTED in part | benchmark R4-B-04 (contention max; hard-ceiling names; warm start superseded by ERR-FX2-BD-5) | drift minimum → D32 |
| E-24, E-25, F-1, LF-1 | ADOPTED (E-24) / OWNER DECISION (E-25, F-1, LF-1) | benchmark R4-B-05; D30, D31 | — |
| E-26 | ADOPTED | LAB_SPEC R4-L-14 (PP3: `osFamily` gains windows, ubuntu-touch); curve-point selection index `(2j(n−1)+11)/22` and units as the lane reads them | builder action PP3 |
| E-29 (I0c executor traces), E-30, E-31, LF-4 | ADOPTED | LAB_SPEC R4-L-15; benchmark R4-B-06, R4-B-07 | M04-301..345, M04-401, M04-403, M04-425 are lab-implementation vectors |
| E-32, ERR-FX2-ASC05 | ADOPTED | benchmark R4-B-01, R4-B-02 | builder action: JVM ceiling rows for windows and ubuntu-touch; M04-443, M04-444 |
| E-34, E-38, E-35 (as superseded), ERR-FX-M08-1, ERR-FX-M08-2, ERR-FX2-ASC01, ERR-FX2-ASC10 | ADOPTED | LAB_SPEC R4-L-18 | builder action: interpolation vector; ASC01 `state`/`penalty` vectors |
| E-37, LF-5 | ADOPTED | benchmark R4-B-03 | M04-430 |
| E-39 | ADOPTED | LAB_SPEC R4-L-19 | — |
| F-4, LF-2 | OWNER DECISION | D32 | the deciding vector is F-4's |
| ERR-FX2-ASC02, ASC03, ASC07 | ADOPTED | benchmark R4-B-07 | builder action: JVM token carries the ticks |
| ERR-FX-CV2 | ADOPTED | LAB_SPEC R4-L-13 (`productionKeys` defaults to true in every lane) | — |

## 5. Implementation readings: `ubuntu-touch/ERRATA.md` (ids are that file's)

| Ids | Disposition | Where | Note |
|---|---|---|---|
| ERR-UT-ISO-1, ERR-UT-ISO-2 | ADOPTED | LAB_SPEC R4-L-03, R4-L-04 | — |
| ERR-UT-MAP-1, ERR-UT-SELFTEST-1, ERR-UT-SELFTEST-2 | ADOPTED | ubuntu-touch R4-UT-08; PLATFORM_PLAN R4-P-05 | — |
| ERR-UT-CTL-1, -2, -3, ERR-UT-ERR-1 | ADOPTED | ubuntu-touch R4-UT-01 | — |
| ERR-UT-FSM-1, -2, -4, -5, ERR-FX-UT-3 | ADOPTED | ubuntu-touch R4-UT-02 | — |
| ERR-UT-FSM-3 | OWNER DECISION | D39 | — |
| ERR-UT-PROJ-1, ERR-FX-UT-2, ERR-FX-UT-4 | ADOPTED | ubuntu-touch R4-UT-03 | the `self-ui:` label waits for D25 |
| ERR-UT-PATHS-1 | ADOPTED | ubuntu-touch R4-UT-04 | — |
| ERR-UT-JLINK-1, -2, ERR-UT-CDS-1, ERR-UT-JAR-1 | ADOPTED | ubuntu-touch R4-UT-05 | — |
| ERR-UT-CLICK-1, -2, -3, -4, ERR-UT-QML-1, -2, -3, ERR-UT-NET-1, ERR-FX-UT-1 | ADOPTED | ubuntu-touch R4-UT-06 | — |
| ERR-UT-CI-1 | ADOPTED | ubuntu-touch R4-UT-07; PLATFORM_PLAN R4-P-08 | — |
| ERR-UT-SIZE-1, ERR-UT-TEST-1 | NO SPEC ITEM | — | measurements (39 MB runtime, 4.2 MB jar, 52 MB installed) and a test gap closed |

---

## 6. Builder actions created by r4 (the code or vectors do not yet match the adopted text)

Each is a separate, reviewed change by the owning track; until it lands, the gate that covers it must say the spec and the code differ.

1. **Pairing r4 defaults** (trust R4-T-10): new W04 vectors and laws; r0 behaviour to `R0_COMPAT`; harness re-orientation; flip `PairProfile`, `QrExpiry` and `PairingChannel` orientation defaults.
2. **Max session age 30 min** (trust R4-T-08; supersedes ERR-PI-3's 24 h).
3. **GOAWAY on the session driver**, not the revoking thread (trust R4-T-08).
4. **Leaf lifetime cap** and the T6 refusal inside the verifier modes; W05 vectors (trust R4-T-06).
5. **Thermal substitution** `min(2, last + 1)`; regenerate W07-075 (LAB_SPEC R4-L-17).
6. **Padding normalisation** of `outBytes`; rewrite M08-017; three new M08 vectors (LAB_SPEC R4-L-19).
7. **Tracker:** DISCREPANT inheritance without a recorded body; ASC01 `state`/`penalty` vectors; an interpolation vector (LAB_SPEC R4-L-18).
8. **ESTIMATED overhead form** in `:ledger-model` (38 / 16,367 for JSSE; 22 / 16,384 uncalibrated) (LAB_SPEC R4-L-25).
9. **End reasons** `BATTERY_LOW`, `POWER_SAVER`, `DEVICE_BUSY` in both lanes; regenerate M04-443, M04-444; add `ipados` and iOS low-battery vectors (benchmark R4-B-01).
10. **Ceiling rows** for windows and ubuntu-touch, or the JVM refuses them as the Swift lane does (benchmark R4-B-02).
11. **Consent token ticks** in the JVM token and the conformance adapter (benchmark R4-B-07).
12. **Quick and ci plan JSON** transcribed into `benchmark.md` §5.2 (benchmark R4-B-06).
13. **Public derivative PP3** (`osFamily` windows, ubuntu-touch) (LAB_SPEC R4-L-14).
14. **Per-export key generated inside `signPresentation`** on the production path (LAB_SPEC R4-L-13).
15. **Lab `VERSION` 0.3.0** in one commit, after D30–D32 (LAB_SPEC R4-L-08).
16. **Strict governor start** on the Windows and macOS hosts (PLATFORM_PLAN R4-P-12).
17. **Workflow edits**: `ASOM_REQUIRE_DBUS=1` on the desktop JVM jobs; the Windows workflow pair; `apple-ios.yml` path filters (PLATFORM_PLAN R4-P-13).

## 7. DEFERRED and REJECTED items (recorded, not specified)

| Item | Disposition | Reason |
|---|---|---|
| Requester-side answer-length check (R3-OVERCLAIM-1) | DEFERRED | its factor k needs simulation; normalisation removes invisible padding now |
| A full phone row in design §7.1's table (R3-OVERCLAIM-2) | DEFERRED | the D-v2 owner-device measurement replaces every figure; the envelope is stated |
| Deck game/docked mechanism and a per-OS presence column (R3-OVERCLAIM-4) | DEFERRED | needs a spike; until then a Deck never lends |
| Soft-ceiling flag, per-rep thermal codes, `tg64`/cool-down gate, a sustain start-class member (ERR-FX2-BE-2…4, ERR-FX2-BD-5) | DEFERRED | schema B-patches and executor changes in both lanes |
| EXT_IGNORED / REVOKE_NOTICE row budget on an established session (ERR-FX2-PW-6) | DEFERRED | not specified; no lab behaviour to adopt |
| SNI token gating (S-A11; ERR-PL-14) | DEFERRED | design T13 stays open; the spike works with caveats |
| ERR-PI-3's 24 h maximum session age | REJECTED | design §4.3 and T9 fix 30 min |
| A new tracker rule for ratios across a backend switch (ERR-FX-RT-1 residual) | REJECTED | `DISCARD_SETTINGS` already excludes them |
| T5's 1-in-10 new-peer cap | REJECTED (withdrawn) | never specified or built; RL11 covers new peers |

## 8. Contradictions found that r4 could not resolve

1. **The lanes disagree on the projected row flags and on drift's minimum reps** until D30–D32 are ruled (42 verdict and 5 value disagreements are listed; the drift minimum is undecided by any vector).
2. **ERR-FX2-ASC05 and ERR-FX2-ASC01 report JVM behaviour "reportedly"** (Windows and Ubuntu Touch ceilings mapped to the Linux row; DISCREPANT kept under a tripped budget); neither was observed in a vector. r4 adopts the conservative reading; the JVM behaviour is unverified.
3. **The cause of JSSE's 38-byte record expansion** (against RFC 8446's 22-byte minimum) was not examined; r4 calibrates rather than explains.
4. **Network.framework overhead** stays ESTIMATED until IA07; Conscrypt and Network.framework rows of the S-A9 matrix are UNVERIFIED.
5. **`apple/ERRATA.md` uses `E-29` for two entries**: the I0c executor-trace entry and the fix-docs rename of the orchestrator section (which assumed E-28 was the highest id). r4 cites the latter as "E-01 and its orchestrator follow-up"; the file itself is outside this change.
6. **`lab/ERRATA.md` and `desktop/ERRATA.md` reuse ids** (`ERR-ISO-1…3`, `ERR-CI-1`, `ERR-ENV-1`, `ERR-FX-1…5`); r4 qualifies desktop ids by file.
7. **The r3 design header names `ASOM_MESH_DESIGN.r1.md` and `.r2.md` as preserved files**; they are not committed in this repository.
8. **The design wins over `trust.md` for pairing directions, but the lab's session harnesses run pairing with the TLS server as S** (ERR-PS-16, ERR-FX2-PW-3). r4 makes trust.md's orientation normative; the harnesses disagree until builder action 1.
9. **`platforms.md` §2.1 gives the Deck a 30 s drain grace; the design and `linux.md` give 2 s.** r4 keeps 2 s (design wins); `platforms.md` is not edited.

## 9. Consistency checks run for this revision

- Every ERRATA id cited in r4 text exists in its ERRATA file; every `R4-*` id cited exists exactly once as a definition; every `REVIEW_ROUND3` id appears in §1; every new owner id (D12.0, D12.4b, D30–D41, RT-15) is defined in `OWNER_DECISIONS.md`; every section number cited exists. The check commands and their output are in the commit that introduced this file (`PROGRESS.md` pointer line).
- The count line of design §8.3 and `OWNER_BRIEF.md` is byte-identical (checked with `diff`).
