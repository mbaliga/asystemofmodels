# Design revision 4.1: change log

**Date:** 2026-10-18 · **Revision:** r4.1 of the mesh design (`ASOM_MESH_DESIGN.md`; r4 was 2026-10-07) · **Base:** branch `claude/asom-v1-build-brief-vw83oh` at `30733e3` · **Scope:** a docs-only change under `docs/design/mesh/`. No code, vector, workflow, ERRATA file or file outside this directory was changed; `PROGRESS.md` gets one pointer line. `REVISION_4.md` is not edited: it stays the log of r4, and where r4.1 supersedes one of its sentences this file says so.

**What r4.1 does.**
1. It **fixes stale r3 text** that review rounds 3 and 4 found still standing after r4 (§1): the `ESTIMATED` overhead form of design §8.4, design §5.7's "Bound it gives" and risk K11, the Linux estimate, `router.md`'s STALE substitution, and further stale constants found by grep.
2. It **adopts, as normative text, the acting decisions the builders implemented** after r4 while waiting for the owner (§2, items A1–A12), each with the old text quoted and the vectors that pin it, and it dispositions **every ERRATA row added since r4** (§3–§5: `lab/ERRATA.md` ERR-R4B-*, ERR-FX3-*, ERR-FX4-*; `desktop/ERRATA.md` ERR-R4B-HC-*, ERR-FX3-HC-*, ERR-FX4-*; `apple/ERRATA.md` ERR-R4B-*, ERR-FX3-*, ERR-FX4-*). Each item is **ADOPTED** (the normative text now says it), **OWNER DECISION** (put to the owner in `OWNER_DECISIONS.md` §J), **DEFERRED** (recorded, not specified now), **REJECTED** (not taken, with the reason) or **NO SPEC ITEM** (a record of process, tests, tooling or a builder action that needs no spec text).
3. It **puts seven new decisions to the owner** (D42–D48) and one acting decision to ratify (AD-7, covering A1–A12) in `OWNER_DECISIONS.md` §J.

Nothing in r4.1 is device evidence. The v1 HTTP contract stays frozen, the design §8.3 count is unchanged, the CLAUDE.md invariants stand, and no endpoint, header value or error code is added (D42 asks the owner whether one should be).

**Where the normative changes are.** Amendments carry ids and quote the old text:

| File | Section added | Ids | Inline `r4.1` markers |
|---|---|---|---|
| `ASOM_MESH_DESIGN.md` | header "What changed in r4.1", §12.0 paragraph | — | title and revision line, §3.2 rule 1, §3.4.2, §4.2 T17(e), §4.3 (transport, decision rows 6 and 6c), §5.7 (`bpt` comment, padding, "Bound it gives", "does not bound"), §7.3 F11 and F14, §7.4 (hold-down, `seq` row, staleness), §7.6, §7.12, §8.4 (`ESTIMATED`, frame table, file permission, laws L-L12, L-L13, L-L16, L-L17, L-L18, L-L19), §8.6, §11 K11, Appendix B A36, A37 |
| `trust.md` | §18 | R41-T-01 … R41-T-10 | revision line, §6.6, §7.3 row 6, §10.7, R4-T-08 maximum-age row |
| `LAB_SPEC.md` | §11 | R41-L-01 … R41-L-12 | header, §6.5, §6.7, §7.2, §7.6, §7.7, R4-L-10.3, R4-L-13, R4-L-17, R4-L-19, R4-L-27 |
| `PLATFORM_PLAN.md` | §14 | R41-P-01, R41-P-02 | header, §3 estimate |
| `benchmark.md` | §24 | R41-B-01 … R41-B-03 | revision line |
| `platforms.md` | §14 | R41-PL-01 … R41-PL-03 | revision line, §2.1 `graceMs` |
| `router.md` | — (r0 file; corrected in place) | — | revision note, §3.5 (STALE row, plan-level consequence), §5.5, §5.6 |
| `contract.md` | — | — | revision note, §4.3 (two rows), §4.10, §4.12 (L-L9, L-L12, pointer to L-L13 … L-L19) |
| `platforms/linux.md` | — | — | revision line, §10.3 total row and estimate assumptions |
| `platforms/windows.md` | — | — | revision line, §4.1 interface eligibility |
| `platforms/macos.md` | — | — | revision line, §4.1 inbound listener |
| `platforms/ubuntu-touch.md` | — | — | revision line, R4-UT-06 |
| `OWNER_DECISIONS.md` | §J | AD-7, D42 … D48 | title, revision line |
| `OWNER_BRIEF.md` | — | — | title and first paragraph (a pointer; the count line is unchanged and still byte-identical to design §8.3's) |

**Precedence (unchanged rule, restated).** The design wins over `LAB_SPEC.md`, `PLATFORM_PLAN.md` and the siblings. Within each of those files the r4.1 section wins over its r4 section, and both win over its body. `router.md`, `contract.md` and the platform sections have no r4.1 section: their inline r4.1 corrections apply, and the design still wins wherever they differ from it.

---

## 1. Stale text fixed (found still standing by review rounds 3 and 4, or by grep)

The round-4 review report itself is not committed in this repository; its findings reach the spec only through the ids the ERRATA files cite (`A-*`, `B-*`, `R4*`, `RP-*`). The items the brief for this revision named are S1–S5; S6–S18 were found by grepping the spec for the old constants.

| # | Where | Old text (quoted) | New text (where) | Source | Pinned by |
|---|---|---|---|---|---|
| S1 | design §8.4, `ESTIMATED` bullet | "`22 × ⌈appBytes_flush / 16384⌉` per flush … plus a handshake estimate of 4,096 bytes per direction [A36]" (r3; r4 left it although R4-L-25 had replaced it in `LAB_SPEC.md`) | R4-L-25's form: `R × ⌈appBytes_flush / Lmax⌉` per flush plus 8,192 bytes per session; JSSE 17/21 with `TLS_AES_256_GCM_SHA384`: `R` 38, `Lmax` 16,367; any other stack: the RFC 8446 floor 22 / 16,384, labelled as such (design §8.4; `LAB_SPEC.md` R41-L-01) | R3-OVERCLAIM-3; ERR-R4B-LT-2, ERR-R4B-SE-1, ERR-FX3-SS-3 | lab tests `AccountingAndDialTest`, `EstimatedLongRunTest` |
| S2 | design §5.7, "Bound it gives" | "before that, the claim is capped by `capRef`, discounted by `disc`, limited to 1 in 4 wins while UNVERIFIED (`router.md` §5.5) and 1 in 10 for a brand-new peer (T5)" | the claim is only discounted by `disc`; `capRef` has no inputs in mesh-1 (D40, D18/RT-12; R4-L-16) and the 1-in-10 cap was withdrawn in r4 (T5); the 1-in-4 cap is per node and needs a verified partner of another node; without one, UNVERIFIED peers are not capped against each other (design §5.7, also "What it does not bound") | R3-CLOSURE-5, R3-OVERCLAIM-1; ERR-FX4-RT-1 | R03-034..037 |
| S3 | design §11, risk K11 | mitigations "caps from signed reference and class ceiling; 1-in-4 and 1-in-10 caps" | the `disc` discount and the per-node 1-in-4 cap; the two removed mitigations are named as not in mesh-1; the residual gains the unpartnered case | as S2 | R03-034..037 |
| S4 | design §3.4.2 Linux estimate (the brief for this revision said "§4.4"; design §4.4 has no Linux total: the stale figure was in §3.4.2), `PLATFORM_PLAN.md` §3, `platforms/linux.md` §10.3 total row and its estimate assumptions | "12–19 engineer-weeks (DL0–DL7)" (three places); "the design's §9.4 D-v2 figure for Linux/Deck (9–16)" | 11.5–18.5, the sum of `linux.md` §10.3's rows (6–9.5 + 3–5 + 2–3 + 0.5–1). **Which won:** the design's own itemised figures (§9.4: DL0–DL5 9–14.5 under D-v2, DL6 2–3 inside M1) plus DL7 0.5–1; `PLATFORM_PLAN.md` R4-P-10 already said 11.5–18.5, so design and plan now agree, and the stale headline lost to the design's rows (`PLATFORM_PLAN.md` R41-P-01) | R3-OVERCLAIM-13 | — (arithmetic on spec inputs) |
| S5 | `router.md` §3.5, STALE row | "`thermal.code = max(last, 1)` if last ≥ 1 else last; `queue.estStartMs = last + …`; `batteryPermille = last − 50` if on battery; `user.active = last`" (the r3 no-op; R4-L-17 had corrected it in `LAB_SPEC.md` only) | `thermal.band = min(2, last + 1)` and `queue.bucket = min(2, last + 1)` when last ≥ 1; `batteryBand` one lower unless the source is `ac`; plus the **plan-level consequence**: a band-1 lender is excluded by F10 while STALE (it reads band 2) and kept as probe-only once EXPIRED (no fast-field filter applies), which is intended (`router.md` §3.5; design §7.4) | R4-L-17 (ERR-LP-5); ERR-FX4-RT-3, ERR-FX4-RT-11 | R01-092 (STALE: F10), R01-093 (WARM), R01-094 (EXPIRED: probe-only), R02-053, R02-054, W07-075, W07-081 |
| S6 | `LAB_SPEC.md` §6.7 | "A brand-new peer is limited to 1 in 10 (T5)." | marked withdrawn inline; the cap is per node with a partner (R41-L-03) | R4-L-16; ERR-FX4-RT-1 | R03-034..037 |
| S7 | `LAB_SPEC.md` §7.6, `ESTIMATED` bullet | "`22 × ceilDiv(appBytesOfFlush, 16384)` per flush, plus 4,096 bytes per direction" (r4 added a note but kept the formula) | marked superseded in place by R4-L-25 (R41-L-01) | as S1 | as S1 |
| S8 | design Appendix B, A36 | "about 4,096 bytes per direction" | calibrated values recorded (2,530–2,540 and 4,440–4,460 bytes, LAB, JDK 21); A36 no longer feeds a number (flat 8,192 per session) | R4-L-25 | — |
| S9 | `router.md` §5.6 | "`own-devices` … `NO_ELIGIBLE_NODE` (RC-6)" and "`ALL_PROVIDERS_COOLING` if cloud candidates were tried and are cooling; else `NO_ELIGIBLE_NODE`" | the `own-devices` row is marked withdrawn (r3, D12.6); the last row gives `ALL_PROVIDERS_COOLING` when every cloud attempt failed, else owner item D42; the first row never gives `NO_PROVIDER_KEY` when the cloud is not in `P` | ERR-FX4-RT-5, ERR-FX4-RT-6 | R01-095 |
| S10 | `platforms.md` §2.1 | "Linux/Deck/macOS: 30,000 (configurable)"; "iOS: `min(5,000, remaining background task time)`"; "advertises `availability=armed(reason)`"; the "proposed live-state field" with a reason code | Deck 2,000 ms and iOS none (design §3.2 rule 4 wins); ARMED advertises only the state; the reason field is withdrawn (design §3.2 rule 1, §7.4) (`platforms.md` R41-PL-03). Resolves `REVISION_4.md` §8 item 9 | design §3.2 | — |
| S11 | `trust.md` §7.3 row 6 | "`PEER_BATTERY` / `PEER_THERMAL` / `PEER_USER_ACTIVE` + `retryAfterMs`" | marked superseded by R41-T-02 (one `PEER_UNAVAILABLE`, design §4.3 since r2) | design §4.3 | W07p-226..230 |
| S12 | `trust.md` R4-T-08, maximum-age row | "A session with an open stream says `GOAWAY max-age` at the first moment it has none." | marked superseded by R41-T-01 (the hard cap) | ERR-FX4-LD-3 | L01-343, L01-345..349 |
| S13 | `LAB_SPEC.md` R4-L-17, last bullet | "a presence cause uses the remaining LP-2 hold-down clamped into 5,000..600,000" | marked superseded by R41-L-02 (the condition value) | ERR-FX4-LD-2 | W07p-210..212, W07p-222 |
| S14 | `contract.md` §4.12 | L-L9 "Summing … over all rows equals summing them over outcome rows only"; L-L12 "agree field by field" | L-L9 points to R4-L-26's reading; L-L12 corrected (A10) | R4-L-26; ERR-FX4-LG-9 | lab tests |
| S15 | design §5.7, padding paragraph | "Invisible padding (whitespace, newlines, zero-width characters) is removed" (true only for `Cc` and `Cf`) | the removal set is named (`Cc`, `Cf`, default-ignorable, U+2800) and NFC can never count more than was sent (R41-L-06) | ERR-FX3-TP-1, -2, -3 | M08-102..106 |
| S16 | `REVISION_4.md` §7 row 1 and §6 item 2 | "normalisation removes invisible padding now"; "Max session age 30 min (trust R4-T-08; supersedes ERR-PI-3's 24 h)" | not edited (`REVISION_4.md` is the r4 log); superseded by S15 and A1 here | ERR-FX3-TP-3; ERR-FX4-LD-3 | as S15 and A1 |
| S17 | `lab/ERRATA.md` ERR-R4B-TR-1 | "about 1.7% lower ratio" (the cost of removing newlines from honest text) | not edited (outside this directory); withdrawn in `LAB_SPEC.md` R41-L-06: about 22% for code or markdown | ERR-FX3-TP-4 | M08-107, M08-108 |
| S18 | `LAB_SPEC.md` §7.2, lender decision table | "**The lender's decision table** is `trust.md` §7.3 plus these rows: 6a …; 6b …; 8 …" | rows 6 and 6c and the order added (R41-L-09) | ERR-FX4-LD-1, ERR-FX4-LD-3 | W07p-226..233 |

**Stale text found and left, because the design already wins over it** (recorded so nobody reads it as current): the rest of `router.md`'s r0 text that design §7 superseded (its revision note lists the `own-devices` policy, `PEER_THERMAL`/`PEER_BATTERY`/`PEER_USER_ACTIVE`, the removed live-state fields, `queuePos`/`estStartMs` on `INFER_ACCEPT`; also §8.2's rows and §12's `NO_ELIGIBLE_NODE`); `trust.md` §3.3's r0 frame table (R4-T-09 already points to `LAB_SPEC.md` §7.2); `contract.md` §4.3's `PING` row and interval rows (D5(a) wording; design §8.4 holds both D5 variants).

---

## 2. Acting decisions the builders implemented, adopted as normative text (A1–A12; ratified together as AD-7)

Each was implemented in the lab (LAB evidence, `oracle: self`, NOT DEVICE EVIDENCE) and recorded in the named ERRATA row; several were labelled there as owner decisions applied while waiting for the owner. None is an owner ruling recorded in this repository: r4.1 adopts them as acting decisions, and **AD-7** in `OWNER_DECISIONS.md` §J asks the owner to ratify them. Their PROVISIONAL values are listed in **D43**.

| Id | Decision | Old text | New text | ERRATA source | Pinned by | If overruled |
|---|---|---|---|---|---|---|
| **A1** | The 30-minute maximum session age is a **hard cap**: no new attempt from the age (lender row 6c declines `PEER_UNAVAILABLE`; the requester sends nothing more and dials afresh); streams open at the age get a **60 s grace (PROVISIONAL)** counted from the age; at age + grace `GOAWAY max-age` whatever is open (lender engines cancelled, `INTERRUPTED`; requester attempts 599). **Supersedes ERR-PI-3's "a session with an open stream is never cut for age"** and the "wait for the first stream-free moment" reading | `trust.md` R4-T-08: "A session with an open stream says `GOAWAY max-age` at the first moment it has none." | `trust.md` R41-T-01; design §4.3, §8.6; `LAB_SPEC.md` R41-L-11 | ERR-FX4-LD-3 (A-R4X-1, B-MP-5, B-TL-5, B-R5X-1, A-TRUST-3); supersedes ERR-PI-3, ERR-R4B-ST-1 (stream sentence) | L01-343, L01-345..349, W07p-231..233; `LenderFixesTest`, `MaxAgeTest`, `TimersTest` | sessions with overlapping attempts live for ever again (T9 defeated); L01 and W07p vectors regenerated |
| **A2** | The availability FSM state is an input of the lender decision: **row 6** (`fsm ≠ SERVING` → `PEER_UNAVAILABLE`), after rows 1–5 and before 6c, 6a, 6b, 7; the host must supply the state (no defaults; fail closed) | `trust.md` §7.3 row 6 (serving conditions only; codes `PEER_BATTERY`/`PEER_THERMAL`/`PEER_USER_ACTIVE`); `LAB_SPEC.md` §7.2 "plus 6a, 6b, 8" | `trust.md` R41-T-02; design §3.2 rule 1, §4.3; `platforms.md` R41-PL-02; `LAB_SPEC.md` R41-L-09 | ERR-FX4-LD-1 (A-MP-2) | W07p-226..230, W07p-232; `LenderFixesTest.aLenderThatIsNotServing…` | an OFF, ARMED or DRAINING lender with a session (pairing window, Peers tab) runs its engine for a peer |
| **A3** | A presence decline carries the **constant** `retryAfterMs` of a condition (30,000, PROVISIONAL), never the remaining hold-down | `LAB_SPEC.md` R4-L-17: "a presence cause uses the remaining LP-2 hold-down clamped into 5,000..600,000" | `trust.md` R41-T-02; `LAB_SPEC.md` R41-L-02; design §7.4, §7.12 | ERR-FX4-LD-2 (A-MP-8); amends ERR-LP-7 | W07p-210, W07p-211, W07p-212, W07p-222; `LenderDecisionTest` | a decline reveals the last user input to the millisecond; the residual (the accept switch) is D44 either way |
| **A4** | The live-state `seq` is **one counter per requester pin**, started from the node counter at first contact | the lab's `LivePort.nextSeq` ("a value that rises with every call"), one counter for every peer; `trust.md` §10.7 silent | `trust.md` R41-T-04 (and §10.7); design §7.4, §7.12 | ERR-FX4-LD-6 (B-MP-8) | `LenderFixesTest.theLiveStateSequenceOfOneRequester…` (no vector) | a requester counts other peers' attempts from the gaps in its own `seq` |
| **A5** | **Node-wide atomic admission**: read both counts, decide row 7 and the 4-per-pin cap, reserve the slot, in one step | `trust.md` §7.2 `maxConcurrent`, R4-T-08 "4 per peer (pin), counted across all of that peer's sessions" (stated as counts; the lab checked and reserved in two steps) | `trust.md` R41-T-03 | ERR-FX4-LD-4 (A-MP-6, A-F1, B-MP-3) | `LenderFixesTest` race tests (no vector form) | two sessions both pass `maxConcurrent` 1 (456 of 1,500 racing pairs) |
| **A6** | `user_disable` = DRAINING with grace 0 in the step that starts it, then OFF at the next evaluation; from ARMED or OFF: OFF at once; during any drain: grace 0 from `now`; an enable cancels only the pending disable | `platforms.md` §2.1: "user_disable (from any state; runs DRAINING with grace = 0 then → OFF)", with no timing rule; the lab went straight to OFF | `platforms.md` R41-PL-01 | ERR-FX4-PO-1 (A-MP-3); desktop ERR-FSM-5 already read it so | W07p-008 (regenerated), W07p-020..024 | the host gets no DRAINING step to close the listener on; the lab and desktop lanes disagree again |
| **A7** | The requester's **offer-only probe** of a half-open or EXPIRED peer: plan order unchanged; at most one probe per request, one per peer per 30 s (PROVISIONAL); `CANCEL superseded` before any body; the accept closes the breaker | design §7.3 F14 named "a half-open, offer-only probe" with no schedule | `LAB_SPEC.md` R41-L-04; design §7.3 F14 | ERR-FX4-RT-2 (RP-2) | R01-094 (EXPIRED is probe-only); `ReviewFixSimTest.rp2_…`, `ReviewFixTest` (no probe vector) | a peer whose breaker opened never serves again while this device can answer |
| **A8** | The unverified-claim cap is **counted per node**, applied only against the first usable candidate of **another node** whose claim is **not UNVERIFIED** (SELF qualifies); no partner, no cap | `router.md` §5.5 pseudocode (per (peer, file); swap with "the best other usable sovereign candidate") | `router.md` §5.5 r4.1; `LAB_SPEC.md` R41-L-03; design §5.7, K11 | ERR-FX4-RT-1 (RP-1) | R03-034, R03-035, R03-036, R03-037; law RL11b | a liar with two files wins 20 of 20 placements against a bound of 5 |
| **A9** | **This device on battery with an unknown design capacity** is charged 10,000 mWh (PROVISIONAL); a design ≤ 0 is unknown; a peer stays excluded (F8) | `LAB_SPEC.md` §6.4 E12 (silently 0 when the capacity is missing) | `LAB_SPEC.md` R41-L-05 | ERR-FX4-RT-4 (RP-4) | R02-051, R02-052 | an unknown battery looks free and this device wins placements it should not |
| **A10** | **L-L12 corrected:** `INFER_END` agrees with the lender's outcome row on `status` and `terminal`; `INFER_HEAD` on `servedModel`, and its `status` is the row's or 200 (the commit-time status of a stream that began); an attempt that ends before its body sends no head | `contract.md` §4.12: "`INFER_HEAD`/`INFER_END` metadata and the provider's outcome row agree field by field" | design §8.4 (laws table); `contract.md` §4.12; `LAB_SPEC.md` R41-L-10 | ERR-FX4-LG-9 (B-LDG-4, A-LED-3) | `FixTrackLawsTest.l12AcceptsAHeadThatCommittedWith200…`, `l12StillFailsOnAnEndOrHeadThatDisagrees…`; mutant M15 | the law cannot hold for a real lender, which commits the head 200 and may end 499 or 500 |
| **A11** | The **ledger file permission rule**: an existing file is tightened to `0600` on POSIX (the open fails otherwise); owner-only ACL on Windows (UNVERIFIED); a non-regular path is refused; links are not followed | `contract.md` §4.10 "mode 0600" (applied only to a new file; a 0666 file stayed 0666) | design §8.4 "File permission"; `contract.md` §4.10; `LAB_SPEC.md` R41-L-10 | ERR-FX4-LG-2 (A-LED-9, B-LDG-9); desktop ERR-FX4-LG-2D | `SinkRecoveryTest.anExistingLedgerFileIsTightenedToOwnerOnly…`, `aSymlinkAsTheLedgerPathIsRefused…`; desktop `JsonlLedgerSinkTest` | a world-readable ledger stays world-readable; a symlink's target can be truncated at open |
| **A12** | **Interface eligibility by adapter type** on Windows and macOS: the OS's adapter classification decides the kind, never the alias or name; cellular, unknown and other kinds are refused for both paths | `platforms/windows.md` §4.1 "Overlay eligibility needs the alias the user picked … LAN eligibility needs a user-confirmed interface"; `trust.md` §6.6 left `iface.kind` per platform | `trust.md` R41-T-05; `platforms/windows.md` §4.1; `platforms/macos.md` §4.1 | desktop ERR-FX4-DK-1 (B-LA-2, A-LA-7 first half) | desktop `InterfaceKindTest`, `HardwarePortTest` (no vector); IfType of real tunnels NEEDS-DEVICE-VALIDATION | a cellular adapter at a carrier 10/8 or CGNAT address can be bound as LAN or overlay |

The STALE thermal substitution (S5) is not an acting decision: it is r4's adopted rule (`LAB_SPEC.md` R4-L-17), now carried into `router.md` and the design.

---

## 3. Implementation readings: `lab/ERRATA.md` rows since r4 (every id)

| Ids | Disposition | Where | Pinned by / note |
|---|---|---|---|
| ERR-R4B-PW-1 | NO SPEC ITEM | — | builder action 1 of r4 done (the R3 profile, `QrExpiry.NONE` and TLS-role orientation are the defaults; r0 behaviour is the explicit `R0_COMPAT`); W04-500..540 carry `"profile": "r0"` |
| ERR-R4B-PW-2 | ADOPTED (no new text) | `LAB_SPEC.md` R4-L-24 and `trust.md` R4-T-10 already say it | resolves `REVISION_4.md` §8 item 8 (the harnesses now run S as the TLS client) |
| ERR-R4B-PW-3, ERR-R4B-PW-4 | ADOPTED | `trust.md` R41-T-09 (cold frame; binding in every bound state) | W04-734; W04-719..723 |
| ERR-R4B-PW-5 | ADOPTED (status quo); the stricter alternative REJECTED | `trust.md` R41-T-09 | W04-718; abort on a second decision gains nothing (the opened nonce is fixed) |
| ERR-R4B-PW-6 | NO SPEC ITEM | — | the R4-T-10 builder plan, executed: W04-017..024, W04-450..458, W04-460..468, W04-480..499, W04-800..819, W04-700..743, W04-760..780 and the listed laws |
| ERR-R4B-PW-7, ERR-R4B-PW-8, ERR-R4B-PW-9, ERR-R4B-PW-10 | NO SPEC ITEM | — | generator tooling; the literal 16-frame budget (R4-L-25); L11 still unexercised (R4-T-13); no budget on malformed `PAIR_*` bodies (part of the DEFERRED item of ERR-FX2-PW-6) |
| ERR-R4B-ST-1 | ADOPTED, then superseded in part | `trust.md` R41-T-01 | 30 min done; its "not cut while a stream is open" sentence superseded by A1 |
| ERR-R4B-ST-2 | ADOPTED | `trust.md` R41-T-08 (500 ms PROVISIONAL, in D43) | builder action 3 of r4 done |
| ERR-R4B-ST-3, ERR-R4B-ST-4 | ADOPTED | `trust.md` R41-T-10 | W05-427..440; W05-307, W05-308, W05-422..426 (builder action 4 of r4 done) |
| ERR-R4B-LT-1 | NO SPEC ITEM | — | write-set block, closed by ERR-R4B-TH-1 |
| ERR-R4B-LT-2, ERR-R4B-LT-3, ERR-R4B-SE-1, ERR-R4B-SE-3, ERR-R4B-SE-4 | ADOPTED | `LAB_SPEC.md` R41-L-01; design §8.4 | builder action 8 of r4 done |
| ERR-R4B-LT-4 | OWNER DECISION | D47 | the `records` count of L-L15's upper bound |
| ERR-R4B-LT-5, ERR-R4B-SE-2 | NO SPEC ITEM | — | test method (calibration pin, long run); a harness wrapper with no effect today |
| ERR-R4B-TR-1, ERR-R4B-TR-2, ERR-R4B-TR-3 | ADOPTED (with the ERR-FX3-TP corrections) / OWNER DECISION (newline) | `LAB_SPEC.md` R41-L-06; D48 | M08-083..093; builder action 6 of r4 done |
| ERR-R4B-TR-4 | NO SPEC ITEM | — | independence; its last sentence is withdrawn by ERR-FX3-TP-5 |
| ERR-R4B-TR-5, ERR-R4B-TR-6, ERR-R4B-TR-8 | NO SPEC ITEM | — | additive vector members; M08-017's text; pins M08-094..101 |
| ERR-R4B-TR-7 | ADOPTED (no new text) | `LAB_SPEC.md` R4-L-18 already says it | M08-097; builder action 7 of r4 done |
| ERR-R4B-TR-9 | DEFERRED | as `REVISION_4.md` §7 | the requester-side length check (k needs simulation); M08-086 pins the residual |
| ERR-R4B-BN-1, ERR-R4B-BN-2, ERR-R4B-BN-3 | ADOPTED | `benchmark.md` R41-B-01 | M04-449, M04-450, M04-454..458; builder actions 9 and 10 of r4 done (10 as a refusal) |
| ERR-R4B-BN-4 | ADOPTED | `benchmark.md` R41-B-03 | M04-419, M04-420, M04-425, M04-426..429; builder action 11 of r4 done |
| ERR-R4B-BN-5, ERR-R4B-BN-6, ERR-R4B-BN-7, ERR-R4B-BN-8, ERR-R4B-SW-1, ERR-R4B-SW-2 | NO SPEC ITEM | — | builder action 12 of r4 done (M04-401, M04-403); schema enums completed; Python cross-check aligned; M04-443, M04-444 regenerated |
| ERR-R4B-MF-1, ERR-R4B-MF-2, ERR-R4B-MF-4 | NO SPEC ITEM | — | builder actions 13 and 14 of r4 done (`LAB_SPEC.md` R4-L-14 PP3, R4-L-13); action 15 untouched |
| ERR-R4B-TH-1 | ADOPTED | `LAB_SPEC.md` R41-L-02 (the `last = 0` guard; FRESH and WARM pass through); `router.md` §3.5 | W07-075, W07-081, W07-082; builder action 5 of r4 done |
| ERR-FX3-PW-1, ERR-FX3-PW-2, ERR-FX3-PW-4 | ADOPTED | `trust.md` R41-T-09 | `PairingAndLoopTest` cases |
| ERR-FX3-PW-3, ERR-FX3-PW-5 | NO SPEC ITEM | — | vector inputs corrected (W04-454, W04-466; W04-459 added); the generator asserts both expiry readings |
| ERR-FX3-SS-1 | ADOPTED | `trust.md` R41-T-08 | `GoawayPostedTest` |
| ERR-FX3-SS-2, ERR-FX3-SS-4 | NO SPEC ITEM | — | `close()` aborts the transport first (R4-T-08's write-stall rule, implemented); mutation harness `--check` (the r4 mutant lists were never committed: recorded, not reconstructed) |
| ERR-FX3-SS-3 | ADOPTED | `LAB_SPEC.md` R41-L-01 (the stack per connection) | `AccountingAndDialTest.theJsseCalibrationIsUsedOnlyForTheSuiteAndTheJdks…` |
| ERR-FX3-TP-1, ERR-FX3-TP-2, ERR-FX3-TP-3, ERR-FX3-TP-4 | ADOPTED / OWNER DECISION (newline) | `LAB_SPEC.md` R41-L-06; design §5.7, A37; D48 | M08-102..108 |
| ERR-FX3-TP-5, ERR-FX3-TP-6 | NO SPEC ITEM | — | independence withdrawn (the oracle stays `self`); header citations |
| ERR-FX3-BP-1 | ADOPTED | `benchmark.md` R41-B-01 (reporting order) | `FixBenchPolicyTest`; a two-condition trace vector is a builder action |
| ERR-FX3-BP-2 | ADOPTED | `LAB_SPEC.md` R41-L-02 | W07-083, W07-084 |
| ERR-FX3-BP-3 | NO SPEC ITEM | — | the router oracle applies the substitution |
| ERR-FX3-BP-4 | ADOPTED | `benchmark.md` R41-B-03 | M04-431 |
| ERR-FX3-MF-1, ERR-FX3-MF-2, ERR-FX3-MF-3 | ADOPTED | `LAB_SPEC.md` R41-L-08 | `SeqLockIdentityTest`, `SeqStoreFailsClosedTest`, `NextSeqBoundsTest` |
| ERR-FX3-MF-4, ERR-FX3-MF-5, ERR-FX3-HN-1, ERR-FX3-HN-3, ERR-FX3-HN-4 | NO SPEC ITEM | — | the schema-oracle CI job (CI-ONLY); the signer surface test; record corrections (ERR-FX-CV9 is fixed only from `d4baacb`); the generator guard; README counts |
| ERR-FX3-HN-2 | ADOPTED (rule restated, scope widened) | `LAB_SPEC.md` R41-L-12 | builder action 15 covers all vector changes since `2a67003` |
| ERR-FX4-LD-1 | ADOPTED (A2) | `trust.md` R41-T-02; design §3.2 | W07p-226..230 |
| ERR-FX4-LD-2 | ADOPTED (A3) / OWNER DECISION (residual) | `trust.md` R41-T-02; D44 | W07p-210..212, W07p-222 |
| ERR-FX4-LD-3 | ADOPTED (A1); its 60 s in D43 | `trust.md` R41-T-01 | L01-343, L01-345..349, W07p-231..233 |
| ERR-FX4-LD-4 | ADOPTED (A5) | `trust.md` R41-T-03 | race tests |
| ERR-FX4-LD-5, ERR-FX4-LD-8 | ADOPTED | `trust.md` R41-T-08 | `LenderFixesTest` |
| ERR-FX4-LD-6 | ADOPTED (A4) | `trust.md` R41-T-04 | `LenderFixesTest` |
| ERR-FX4-LD-7 | ADOPTED (empty chunks) / DEFERRED (a per-attempt total deadline) | `trust.md` R41-T-08 | `LenderFixesTest`; the router's request deadline must drive `Session.cancel` at promotion |
| ERR-FX4-LD-9 | ADOPTED | `LAB_SPEC.md` R41-L-09 | the runner's optional members and required laws `decision-not-serving`, `decision-session-aged` |
| ERR-FX4-PO-1 | ADOPTED (A6) | `platforms.md` R41-PL-01 | W07p-008, W07p-020..024; a runner law counter is a builder action |
| ERR-FX4-PO-2 | ADOPTED | `LAB_SPEC.md` R41-L-02 | W07-051..059 |
| ERR-FX4-PO-3 | ADOPTED | `LAB_SPEC.md` R41-L-02; design §7.3 F11 | W07-085..087; builder action: the router's F11 |
| ERR-FX4-PO-4 | ADOPTED | `LAB_SPEC.md` R41-L-12 | W07 and W07p are generated files changed under 0.2.0 |
| ERR-FX4-LG-1 | NO SPEC ITEM | — | `LAB_SPEC.md` R4-L-24's poisoned sink, now implemented |
| ERR-FX4-LG-2 | ADOPTED (A11) | design §8.4; `contract.md` §4.10 | `SinkRecoveryTest`; the Windows ACL path UNVERIFIED |
| ERR-FX4-LG-3, ERR-FX4-LG-4, ERR-FX4-LG-5, ERR-FX4-LG-7, ERR-FX4-LG-8, ERR-FX4-LG-10, ERR-FX4-LG-12 | ADOPTED | `LAB_SPEC.md` R41-L-10; design §8.4 (frame table row, L-L13, L-L16, L-L17, L-L18, L-L19); `contract.md` §4.3 | L02-024, L02-025; `AttemptCoverageTest`, `FixTrackLawsTest`, `CrashConsistencyTest`; builder action: `mesh-proto` `RequesterSide` served fields |
| ERR-FX4-LG-6, ERR-FX4-LG-11, ERR-FX4-LG-13, ERR-FX4-LG-15 | NO SPEC ITEM | — | oracles from the plan; the key-echo path exercised; the simulator revoke test; the mutation record |
| ERR-FX4-LG-9 | ADOPTED (A10) | design §8.4; `contract.md` §4.12 | `FixTrackLawsTest` |
| ERR-FX4-LG-14 | ADOPTED | `LAB_SPEC.md` R41-L-12 | L02-017 (expectation changed), L02-024, L02-025 |
| ERR-FX4-JS-1, ERR-FX4-JS-2, ERR-FX4-JS-3 | ADOPTED | `LAB_SPEC.md` R41-L-07 | `DeepInputTest`, `NumberLexemeTest`, `xcheck_m01.py`; builder action: M01 vectors |
| ERR-FX4-JS-4, ERR-FX4-JS-5 | NO SPEC ITEM | — | CI steps (NOT RUN on a hosted runner) |
| ERR-FX4-JS-6 | ADOPTED | `platforms/ubuntu-touch.md` R4-UT-06 (inline r4.1) | `check_qml_text.py --selftest` (29 negative, 11 positive controls) |
| ERR-FX4-TR-1, ERR-FX4-TR-9 | NO SPEC ITEM | — | a deadlock and a counter race fixed; behaviour unchanged |
| ERR-FX4-TR-2, ERR-FX4-TR-3, ERR-FX4-TR-6, ERR-FX4-TR-7 | ADOPTED | `trust.md` R41-T-07 | `PeerRegistryFixesTest`, `PeerRegistryPropertyTest` |
| ERR-FX4-TR-4 | DEFERRED | — | a first-priority registry hook is an API addition for the node's owner; containment (TR-6) removes the harm |
| ERR-FX4-TR-5 | OWNER DECISION | D46 | W04-613 (`revoke:Refused(CORRUPT_ROW)`) |
| ERR-FX4-TR-8 | ADOPTED | `trust.md` R41-T-06 | `HelloEndpointEligibilityTest`; builder action: the dialler |
| ERR-FX4-BD-1 | ADOPTED | `benchmark.md` R41-B-02 | `FixBenchCeilingTest`; builder action at the 0.3.0 regeneration (a `ceilings` vector, M04-445's description) |
| ERR-FX4-BD-2 | NO SPEC ITEM | — | `LAB_SPEC.md` R4-L-18 already says `view()` refuses a `disc` outside 0..1000 |
| ERR-FX4-BD-3 | ADOPTED | `LAB_SPEC.md` R41-L-12 | the README's third change table |
| ERR-FX4-BD-4, ERR-FX4-BD-5 | NO SPEC ITEM | — | a workflow comment; `xcheck_trust`'s disclosed limit (its W04 agreement is a generator check, not a second implementation) |
| ERR-FX4-RT-1 | ADOPTED (A8) | `router.md` §5.5; `LAB_SPEC.md` R41-L-03 | R03-034..037 |
| ERR-FX4-RT-2 | ADOPTED (A7) | `LAB_SPEC.md` R41-L-04 | `ReviewFixSimTest` |
| ERR-FX4-RT-3, ERR-FX4-RT-11 | ADOPTED (stale fix S5) | `router.md` §3.5; design §7.4 | R01-092..094, R02-053, R02-054 |
| ERR-FX4-RT-4 | ADOPTED (A9); its 10,000 mWh in D43 | `LAB_SPEC.md` R41-L-05 | R02-051, R02-052 |
| ERR-FX4-RT-5, ERR-FX4-RT-8, ERR-FX4-RT-9, ERR-FX4-RT-10 | ADOPTED | `LAB_SPEC.md` R41-L-05; design §7.6; `router.md` §5.6 | R01-095, R01-096, R01-097, R01-098, M08-109 |
| ERR-FX4-RT-6 | ADOPTED in part (the `ALL_PROVIDERS_COOLING` case) / OWNER DECISION (the rest) | `LAB_SPEC.md` R41-L-05; design §7.6; D42 | `ReviewFixSimTest.rp6_…` |
| ERR-FX4-RT-7 | ADOPTED (one runner-up selection) / DEFERRED (the route reason on the terminal row: cross-track, and app-facing only from v2.5) | `LAB_SPEC.md` R41-L-05 | `ReviewFixTest.rp7_…` |
| ERR-FX4-RT-12, ERR-FX4-RT-13 | NO SPEC ITEM | — | an inclusive boundary test; error decisions record their exclusions |

## 4. Implementation readings: `desktop/ERRATA.md` rows since r4 (ids are that file's)

| Ids | Disposition | Where | Note |
|---|---|---|---|
| ERR-R4B-HC-1 | ADOPTED | `PLATFORM_PLAN.md` R41-P-02 | builder action 16 of r4 done |
| ERR-R4B-HC-2, ERR-R4B-HC-3, ERR-R4B-HC-4, ERR-R4B-HC-5 | NO SPEC ITEM | — | builder action 17 of r4 (workflow edits; CI-ONLY until a hosted run is seen) |
| ERR-FX3-HC-1, ERR-FX3-HC-2, ERR-FX3-HC-3 | ADOPTED (HC-1's un-settle question DEFERRED; HC-2's 3 and 30 in D43) | `PLATFORM_PLAN.md` R41-P-02 | `StrictStartReenableTest`, `GpuExistenceTest`, `GovernorTest` |
| ERR-FX3-HC-4, ERR-FX3-HC-5, ERR-FX3-HC-6, ERR-FX3-HC-7 | NO SPEC ITEM | — | workflow path filters, credentials, test strictness, mutation record (it corrects ERR-R4B-HC-4) |
| ERR-FX4-LG-2D | ADOPTED (A11) | design §8.4; `contract.md` §4.10 | the Windows path relies on the directory's protected DACL: UNVERIFIED |
| ERR-FX4-DK-1 | ADOPTED (A12) / OWNER DECISION (the LAN confirmation binding, A-LA-7 second half) | `trust.md` R41-T-05; `platforms/windows.md` §4.1; `platforms/macos.md` §4.1; D45 | `InterfaceKindTest`, `HardwarePortTest`; macOS listing not wired (fail closed) |
| ERR-FX4-DK-2, ERR-FX4-DK-4, ERR-FX4-DK-7, ERR-FX4-DK-8, ERR-FX4-DK-9 | NO SPEC ITEM | — | `linux.md` §3.1 decides the package unit; hostile-bus survival; bind-race socket; a KDoc; the mutation record |
| ERR-FX4-DK-3 | NO SPEC ITEM (stated difference) | — | control frames decode strict UTF-8; the desktop's local JSON profile keeps depth 32 (config and the local socket only, never on the wire) against `LAB_SPEC.md` §4.2's 16 for mesh frames |
| ERR-FX4-DK-5 | ADOPTED | design §4.2 T17(e) | `DefaultTtyOpenerTest`; corrects ERR-CLI-3's claim |
| ERR-FX4-DK-6 | DEFERRED | — | the Windows mutex owner check needs a Windows machine (NEEDS-DEVICE-VALIDATION); the refusal's wording is honest today |

## 5. Implementation readings: `apple/ERRATA.md` rows since r4 (ids are that file's)

| Ids | Disposition | Where | Note |
|---|---|---|---|
| ERR-R4B-TR-10, ERR-R4B-TR-11, ERR-R4B-TR-12, ERR-R4B-TR-13 | ADOPTED (the Swift mirror) | `LAB_SPEC.md` R41-L-06, R4-L-18 | M08-083..097 |
| ERR-R4B-TR-14 | NO SPEC ITEM | — | cross-lane, not independent |
| ERR-R4B-BN-1, ERR-R4B-BN-2, ERR-R4B-BN-3, ERR-R4B-BN-4, ERR-R4B-BN-5 | ADOPTED (mirrors of the lab rows of the same ids) | `benchmark.md` R41-B-01, R41-B-03 | M04-449, M04-450, M04-451, M04-453..455 |
| ERR-R4B-MF-3, ERR-R4B-SW-3, ERR-R4B-SW-4, ERR-R4B-SW-5 | NO SPEC ITEM | — | R4-L-13 builder action in Swift; cross-lane fixtures M02-943 and M03-915 (in `apple/crosslane/`) |
| ERR-R4B-MF-5, ERR-FX3-HN-4a | DEFERRED | `benchmark.md` R41-B-01 | no ceiling rows for `windows` and `ubuntu-touch` until device thresholds exist; the R4-B-02 refusal stands |
| ERR-FX3-TP-1/-2 | ADOPTED (mirror) | `LAB_SPEC.md` R41-L-06 | the Swift set is checked against the runtime's own default-ignorable property |
| ERR-FX3-TP-5, ERR-FX3-TP-6, ERR-FX3-BP-4 | NO SPEC ITEM | — | independence; headers; a count edit (NOT COMPILED where it was written) |
| ERR-FX4-BD-1a | ADOPTED (mirror) | `benchmark.md` R41-B-02 | UNVERIFIED: no Swift toolchain where it was written |
| ERR-FX4-RT-10 | ADOPTED (mirror) | `LAB_SPEC.md` R41-L-05 | M08-109; UNVERIFIED (Swift NOT RUN); `M08Conformance.swift` does not read `path` yet |

---

## 6. Builder actions

**Status of the 17 builder actions of `REVISION_4.md` §6** (as recorded in `PROGRESS.md` and the ERRATA rows; LAB evidence; hosted CI is recorded only where `PROGRESS.md` says so; nothing here is device evidence): 1 pairing defaults: done (ERR-R4B-PW-1). 2 maximum session age: done (ERR-R4B-ST-1), then made a hard cap (ERR-FX4-LD-3). 3 `GOAWAY` on the driver: done (ERR-R4B-ST-2, ERR-FX3-SS-1). 4 leaf lifetime and the T6 refusal: done (ERR-R4B-ST-3, ERR-R4B-ST-4). 5 thermal substitution: done (ERR-R4B-TH-1). 6 padding normalisation: done (ERR-R4B-TR-1..6), widened (ERR-FX3-TP-1..4). 7 tracker vectors: done (ERR-R4B-TR-7, ERR-R4B-TR-8). 8 `ESTIMATED` form: done (ERR-R4B-LT-2, ERR-R4B-SE-1). 9 end reasons: done (ERR-R4B-BN-1, ERR-R4B-BN-8). 10 ceiling rows: done as a refusal (ERR-R4B-BN-3). 11 consent ticks: done (ERR-R4B-BN-4). 12 quick and ci JSON: done (ERR-R4B-BN-5). 13 PP3: done (ERR-R4B-MF-1). 14 export key inside the signer: done (ERR-R4B-MF-2; apple ERR-R4B-MF-3, ERR-R4B-SW-3). 15 lab `VERSION` 0.3.0: **not done, WAIT-OWNER** (after D30–D32; scope widened by R41-L-12). 16 strict governor start: done (desktop ERR-R4B-HC-1). 17 workflow edits: done, CI-ONLY (desktop ERR-R4B-HC-2..5).

**Builder actions created or left open by r4.1** (each is a separate, reviewed change by the owning track; until it lands, the gate that covers it must say the spec and the code differ):
1. The router's F11 reads "not `ac`" instead of `== "battery"`, with an R01 vector (`LAB_SPEC.md` R41-L-02; ERR-FX4-PO-3).
2. M01 vectors `-e`, `-E5`, `[-e1]`, `-NaN` and a deep vector, and the Swift pins (R41-L-07; ERR-FX4-JS-2, ERR-FX4-JS-3).
3. `mesh-proto` `RequesterSide` writes `servedProvider` and `servedModel` on its outcome row (R41-L-10; ERR-FX4-LG-7).
4. The dialler applies the address test to `HELLO` endpoints and requires `authorizeOutbound == Allow` before the `DIAL` intent (`trust.md` R41-T-06; ERR-FX4-TR-8).
5. The macOS host runs the hardware-port listing (an allowlist entry and a test); until then every macOS LAN selection is refused (`trust.md` R41-T-05).
6. The Swift registry mirrors R41-T-07 (strict class, write-outcome read-back) and, once D46 is ruled, the corrupt-row exit; the Swift lane gains the STALE substitution if it ever routes.
7. The executed route reason and detail on the terminal ledger row (R41-L-05; ERR-FX4-RT-7, cross-track `:ledger-model`).
8. The router's request deadline drives `Session.cancel` when the router is promoted (`trust.md` R41-T-08; ERR-FX4-LD-7).
9. Builder action 15 (`VERSION` 0.3.0) covers every vector change since `2a67003`, including the fix-wave-4 changes the conformance README does not list yet, and corrects M04-445's description (R41-L-12).
10. A conformance-runner required-law counter for the `user_disable` drain (ERR-FX4-PO-1); a governor trace vector with two conditions at one instant, once `apple/ci/not-implemented.txt` lists it (R41-B-01); a `ceilings` vector with source `battery` and a quick-plan macOS-laptop trace (R41-B-02).
11. The mutant harnesses' `--check` mode is wired into `lab/tools/check_law.py` or CI (ERR-FX3-SS-4); the Apple workflow consumes the JSON fuzz corpus artifact (ERR-FX4-JS-4).

---

## 7. DEFERRED and REJECTED items (recorded, not specified)

| Item | Disposition | Reason |
|---|---|---|
| A per-attempt total deadline on the lender session (ERR-FX4-LD-7) | DEFERRED | it is the router's request deadline (`router.md` §8.2 row 12); the lab has no router executor driving `Session.cancel`; the hard cap (A1) bounds the session meanwhile |
| Whether a long blind stretch or `RESUMED` un-settles a governor gate (ERR-FX3-HC-1) | DEFERRED | a spec gap; no sleep wiring in `NodeRuntime` yet |
| The Windows mutex owner check (ERR-FX4-DK-6) | DEFERRED | needs a Windows machine to know which owners are legitimate (NEEDS-DEVICE-VALIDATION) |
| A first-priority registry listener hook (ERR-FX4-TR-4) | DEFERRED | an API addition for the node's owner; listener containment removes the harm |
| Ceiling rows for `windows` and `ubuntu-touch` (apple ERR-R4B-MF-5, ERR-FX3-HN-4a) | DEFERRED | thresholds need device data; R4-B-02's refusal stands |
| The route reason on the terminal ledger row (ERR-FX4-RT-7 b) | DEFERRED | cross-track, and app-facing only from v2.5 (CD-RR, D12.4b) |
| The requester-side length check (ERR-R4B-TR-9) and the established-session row budget (ERR-FX2-PW-6, ERR-R4B-PW-10) | DEFERRED (unchanged from r4) | as `REVISION_4.md` §7 |
| Aborting the ceremony on a second approving `PAIR_DECISION` (ERR-R4B-PW-5) | REJECTED | the opened nonce is fixed and nothing the second decision carries is used; aborting adds no security |
| The cap with an UNVERIFIED partner (ERR-FX4-RT-1) | REJECTED | it hands placements to a claim whose own counter never moved |
| A coarse rounding of the presence `retryAfterMs` (ERR-FX4-LD-2) | REJECTED | the step boundaries would still be crossed at exact times |
| Letting the ordinary revoke overwrite a corrupt status (ERR-FX4-TR-5, first attempt) | not decided here: option (b) of D46 | it changes what committed vector W04-613 says |

---

## 8. What r4.1 could not resolve, and contradictions found

1. **"The Linux total in section 4.4."** Design §4.4 ("What trust, pairing and transport do NOT guarantee") holds no Linux total. The stale "12–19" was in design §3.4.2 (and `PLATFORM_PLAN.md` §3 and `platforms/linux.md` §10.3); r4.1 corrected those (S4). If another figure was meant, it was not found by grep (`12–19`, `12-19`, `18.5`, `9–16` were searched in every file of this directory).
2. **The round-4 review report is not committed**, so its findings can be cited only through the ERRATA ids; r4.1 cannot quote it.
3. **No owner ruling is recorded for the builders' "OWNER DECISION" labels** (ERR-FX4-LD-3 and the "owner decision applied" line of `PROGRESS.md`'s fix4-lender entry). r4.1 adopts them as acting decisions under AD-7, pending ratification; until the owner ratifies, they are as provisional as AD-1…AD-6 were.
4. **D42 cannot be closed without a contract item or an untrue code**: for the deadline case and failures that set no breaker, no frozen v1 or v2 code is true. The lab returns no code, which is not shippable.
5. **`trust.md` has two sections numbered 16** ("Verified facts and assumptions" and "Revision 4 amendments"). Renumbering would move every r4 citation; r4.1 adds §18 and states the reading (R4-T-nn and "`trust.md` §16" in r4 mean the amendments).
6. **Two mechanisms for an owner-only ledger on Windows**: the lab replaces the file's ACL with one owner entry, the desktop node relies on the ledger directory's protected DACL being inherited. Both are UNVERIFIED (no Windows run); the design states the outcome (owner-only), not the mechanism.
7. **The desktop's local JSON profile allows depth 32; mesh frames allow 16** (`LAB_SPEC.md` §4.2). Recorded as a stated difference (local JSON only, never on the wire), not unified.
8. **The Swift lane lags the JVM lane** on r4.1 items: no STALE substitution (it has no staleness code), no registry class strictness, write-outcome read-back or corrupt-row exit, `M08Conformance.swift` ignores `path`, and two Swift changes (apple ERR-FX4-BD-1a, ERR-FX4-RT-10) were written without a Swift toolchain and are NOT RUN.
9. **`LAB_SPEC.md` §3.2 is literally violated** until the 0.3.0 bump: generated and hand vector files changed under `VERSION` 0.2.0 (ERR-FX3-HN-2, ERR-FX4-BD-3, ERR-FX4-LG-14, ERR-FX4-PO-4). The bump waits for D30–D32.
10. **ERRATA rows that r4.1 supersedes are not edited** (they are outside this directory): ERR-PI-3's and ERR-R4B-ST-1's stream sentence, ERR-R4B-TR-1's "about 1.7%", ERR-LP-7's presence clause. This file and the amendments state the supersession.
11. **`lab/ERRATA.md` files some rows under another track's heading**: ERR-FX4-JS-1..6 sit inside the fix4-ledger table and ERR-FX4-RT-1..13 inside the fix4-bench-docs table. The ids are unique, so nothing is ambiguous; it is a layout note.

## 9. Consistency checks run for this revision

Run on the working tree of this change, over the text r4.1 added (the added lines of the diff against `30733e3`, plus this whole file), with a checker kept in the session scratchpad (not committed). Results:
- **ERRATA ids:** 184 distinct `ERR-*` ids cited (ranges such as `ERR-R4B-TR-1..6` expanded); every one is defined in an ERRATA file, and the 8 qualified ones (`desktop …`, `apple …`) in the file they name. Every id in the first column of §3, §4 and §5 is defined in `lab/ERRATA.md`, `desktop/ERRATA.md` and `apple/ERRATA.md` respectively, and every id those files define after r4 (lab 135, desktop 22, apple 23) is dispositioned here (ERR-FX4-LG-2D and the `/-2` and lower-case-suffix forms checked by hand).
- **Vector ids:** 356 cited after expanding every `..` range; all exist in `lab/conformance/` (M02-943 and M03-915 in `apple/crosslane/`).
- **Amendment ids:** 30 `R41-*` ids, each defined exactly once; all 60 `R4-*` and `R41-*` ids cited are defined.
- **Owner items:** AD-7 and D42–D48 are defined in `OWNER_DECISIONS.md` §J; no higher `D` number is cited.
- **Section numbers:** 212 file-qualified references (`` `file.md` §n `` and "design §n") resolve to a heading of that file; 209 bare `§n` references resolve to a heading of their own file or of a document the same line names.
- **Count line:** `OWNER_BRIEF.md`'s contract count line is byte-identical to design §8.3's (`diff` empty), and r4.1 changed neither.
- **Tables:** every table row in the edited files has its header's cell count.
- **Write set:** only files under `docs/design/mesh/` and one appended line of `PROGRESS.md` differ from `30733e3`.
