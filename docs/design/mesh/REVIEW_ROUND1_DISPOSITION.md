# Round-1 review: disposition of every finding

**Reviewed document:** `ASOM_MESH_DESIGN.md` r1 (preserved as `ASOM_MESH_DESIGN.r1.md`) and `OWNER_BRIEF.md` r1 (preserved as `OWNER_BRIEF.r1.md`).
**Findings source:** `REVIEW_ROUND1.md` (12 conformance, 12 overclaim).
**Revised documents:** `ASOM_MESH_DESIGN.md` r2 and `OWNER_BRIEF.md` r2, dated 2026-09-30. §12.1 of the brief carries the short form of this log.

**Dispositions:**
- **A** — accepted; the text or design changed.
- **OD** — converted to an owner decision. The text also changed, to put the question plainly.
- **R** — rejected, with the reason.

| | Accepted (A) | Owner decision (OD) | Rejected (R) | Total |
|---|---|---|---|---|
| Conformance | 8 | 4 | 0 | 12 |
| Overclaim | 12 | 0 | 0 | 12 |
| **All** | **20** | **4** | **0** | **24** |

**Why none was rejected.** Each finding identified a real defect, checked against the frozen documents or the r1 text. Where a reviewer offered two remedies, the one taken is named below. Two findings were re-checked against primary sources before being accepted:
- **OVERCLAIM-10:** the developer-verification page was re-fetched on 2026-09-30 and **confirmed the reviewer**.
- **OVERCLAIM-12:** checked against the repo's `settings.gradle.kts`, which does include all eight Android modules whenever `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `local.properties` provides an SDK.

---

## Conformance lens

### CONFORMANCE-1 [high] — The owner brief and D12(a) undercount frozen-contract changes — **A**
- **Reviewer asked:** replace owner-brief sentence 3 with an accurate count grouped by surface; add D5, D7 and D11 to the decision table as "changes frozen text".
- **r2 did:**
  - wrote a **complete count** at the top of §8.3, covering 6 new app-facing values, the restated `X-Asom-Egress` meaning, `LEDGER_UNAVAILABLE`, the two §5.9 changes, the `RemoteMesh` tier and `CLIENT_API.md`, the ledger classes and 19 columns, the control channel, the peer plane, the catalogue field, the invariant texts, the roadmap text, modules and dependencies;
  - copied it verbatim, as one line, into the owner brief with a pointer to §8.3;
  - rewrote D12(a) to say it rules 5 of the 6 values and to point to the full count;
  - added D5 and a "D3, D7, D11 — change frozen text" row to the owner-brief table.
- **Where:** §8.3; D12; `OWNER_BRIEF.md`.

### CONFORMANCE-2 [high] — SIGN-OFF items with no owner decision; `:bench-app` and `:bench-cli` omitted — **A**
- **Reviewer asked:** add D23 listing every CD and CB id and new module, or map each row to a D; state that an item with no ruling is not approved.
- **r2 did both:**
  - every §8.3 row now has a Decision column;
  - §8.3 opens with "an item whose decision the owner has not ruled is not approved";
  - the new **D23** covers what no other decision covers: CD-24, CD-C, CD-D (now also swift-crypto), MOD-1, MOD-2 and RT-5 (CB1's JNI additions);
  - MOD-1 lists `:bench-cli` and `:bench-app`;
  - roadmap and brief text changes became RT-1 to RT-9, each mapped.
- **Where:** §8.3; §3.5; D23.

### CONFORMANCE-3 [high] — §0 presented open owner decisions as "decided"; the reach rule framed as reversible engineering — **A**
- **Reviewer asked:** retitle the list "Recommended, pending owner ruling", tag each bullet with its D id, and move the reach rule under CD-1/D3 as a change to frozen §5.4's meaning.
- **r2 did:** §0.5 is now "Recommended, pending owner ruling", stating that "None of them is decided", with a decision column. The reach rule is **CD-1m**, "a change to the meaning of frozen §5.4", ruled in D3; D3's options now include it explicitly.
- **Where:** §0.5; §8.3 CD-1m; D3.

### CONFORMANCE-4 [high] — Invariant 9's purpose fails: the header says `peer` while the row's egress says `local` — **A**
- **Reviewer asked:** make the terminal or error row's egress column equal reach; carry the serving class in an additive column such as `servedClass`; add a W01b vector for peer-then-SELF.
- **r2 did:**
  - exactly one **terminal row** per request (`terminal = true`) is the record the headers are built from, and its `egress` is `reach`;
  - `servedClass` holds the serving attempt's class;
  - non-terminal attempt rows keep their own class;
  - new law L-L5b and principle P7;
  - vector **W01b-reach** asserts header value = row value;
  - §8.1's row 9 now reads "honoured in letter and purpose";
  - new M1 gate (11) checks this on a real exported ledger.
- **Where:** §2.4; §7.6; §8.1; §8.3 CD-14; §8.4; §9.3.

### CONFORMANCE-5 [high] — D6 hides that IC-5 and IC-6 are third-amendment material by the letter — **OD**
- **Reviewer asked:** add a "literal reading" paragraph to D6; offer: accept as part of Amendment 1 with the roadmap updated, record as a third amendment, or no exports; mirror this in the owner brief.
- **r2 did:**
  - §8.2 has a "literal reading" paragraph: Amendment 1 amends Invariant 1 only; IC-5 amends Invariant 3; IC-6 adds a second export where Invariant 1 says "exactly one more";
  - D6 now asks the owner to state the record, (R1) fold into Amendment 1 with the text updated or (R2) third amendment, and keeps option (d), no exports, noting that IC-5 is needed for P7 regardless;
  - §1.2 C-5 widened;
  - the owner-brief D6 row says "third-amendment material … You must state the record".
- **Owner must rule:** D6, both the record (R1/R2) and the export shape.
- **Where:** §1.2; §8.2; D6; `OWNER_BRIEF.md`.

### CONFORMANCE-6 [medium] — Per-host-app iOS pairing contradicts IC-4 and Invariant 5 — **A**
- **Reviewer offered:** an Invariant 5 clause for remote app requesters (third-amendment material), **or** per-device pairing so that IC-4 holds.
- **r2 took per-device pairing:**
  - the iPhone has one node key in a keychain access group shared only by apps signed with the owner's Team ID (A25, spike S-A12);
  - the lender ledgers and revokes the iPhone and never learns which app sent a request;
  - the per-app opt-in stays on the iPhone and is stated as enforced by AsomKit code, not the OS;
  - third-party host apps are excluded from M4. The reviewer's first alternative survives as **D15(e)**, flagged as third-amendment material and not recommended.
- **Where:** §7.8; §1.5 X1; §2.6; D15; §9.3 M4; Appendix B A25.

### CONFORMANCE-7 [medium] — B24 pulls the desktop CLI and macOS packaging into v2; the active lane contradicts "not a new subsystem" — **A**
- **Reviewer asked:** move the desktop CLI to D-v2 or M2 (or list it in D4 and D7 with its effort), and extend D7 to the "not a new subsystem" sentence.
- **r2 did:**
  - v2 ships **the Android daemon shell only**;
  - both desktop CLI shells move to D-v2;
  - macOS packaging goes with the macOS node (D-v2 variant or M2, per D24);
  - B24 is rewritten, X31 added, and the §9.3 and §9.4 rows moved;
  - D7(a) now amends both "feature, not a separate app" **and** "This is not a new subsystem" (RT-2);
  - §6.7 says openly that the active lane is a subsystem.
- **Where:** §6.4, §6.5 (B24), §6.7; §1.5 X31; §9.3; §9.4; D7; §8.3 RT-2.

### CONFORMANCE-8 [medium] — The per-app cloud ban is v2.5; under D4(b) X13's rejection rests on nothing — **OD**
- **Reviewer asked:** in D4(b), list the cloud ban as pulled forward or as a lost protection; re-check X13; offer pulling forward the cloud-ban column only.
- **r2 did:**
  - §1.4 lists the cloud ban as its own row, noting it is not replaced by the toggle;
  - D4 splits into **(b1)** mesh-1 after v2 pulling forward *only* the cloud-ban column (recommended, flagged as a pull-forward), and **(b2)** the same without it, with the loss stated and X13 reopened;
  - X13 is conditional on D4;
  - §8.3 RT-8; the owner-brief D4 row.
- **Owner must rule:** D4.
- **Where:** §1.4; §1.5 X13; D4; §8.3 RT-8; `OWNER_BRIEF.md`.

### CONFORMANCE-9 [medium] — IC-1 drops "every remote request ledgered on both nodes"; interval rows are an unflagged seventh change — **OD**
- **Reviewer asked:** add item 7 to the §8.2 list, and put it to the owner inside D2, with D5(d) (one row per control message) as the literal alternative.
- **r2 did:**
  - IC-1 keeps the sentence "Every remote request is ledgered on both nodes, as Invariant 3(d) specifies";
  - the §8.2 list gains item 7;
  - IC-2 carries the wording for each D5 option;
  - D5 is re-cut so that **(b)**, per-message control rows (with `PING`/`PONG` removed from mesh-1), meets the sanctioned text literally, and **the recommendation changed from r1's interval rows to (b)**;
  - D2's preamble cites the seventh change.
- **Owner must rule:** D2 and D5.
- **Where:** §8.2; §8.4; §4.3; D2; D5.

### CONFORMANCE-10 [medium] — D1 cites only CLAUDE.md; the lab claims to touch no build path; L0.4–L0.6 run ahead of D2/D3/D5 — **OD**
- **Reviewer asked:** split D1 into D1a (L0.1–L0.3 now, citing roadmap §0 and the v2 entry criteria) and D1b (L0.4–L0.6 after D2, D3, D5); correct the wording about the CI job and the repo-root directory.
- **r2 did:**
  - D1a and D1b as asked;
  - §9.1 opens with a table of **every rule the lab is an exception to**: CLAUDE.md, roadmap §0, v2 entry criteria, v4's "Do not cold-execute", and directive D;
  - the wording now says the lab **adds a `lab/` directory and one new CI job** to the guaranteed build path;
  - conformance data moved inside `lab/` until promotion, so no repo-root directory is added before then;
  - L0.7 (Swift lane) sits under D1a and D24.
- **Owner must rule:** D1a and D1b.
- **Where:** §9.1; §3.4; §3.5; §8.1; D1a; D1b; `OWNER_BRIEF.md`.

### CONFORMANCE-11 [low] — Other-owner slots remain in normative schemas — **A**
- **Reviewer asked:** remove `X` and audience `"other"` from the mesh-1 schemas and vectors; keep them as a note in D20.
- **r2 did:** the audience is a closed enum `"own" | "file"`; `P ⊆ {T, O, C}`; D20 carries the note.
- **Where:** §5.2; §8.5; §8.1; D20.

### CONFORMANCE-12 [low] — "Providers initiate none" contradicts quiescence rule 3 — **A**
- **Reviewer asked:** reword to "Providers initiate no peer connection except under rule 3", and match IC-3(vi).
- **r2 did:** reworded for the symmetric role model: "in its lending role, a node initiates no peer connection except under rule 3". IC-3(vi) and the §7.4 exchange row match.
- **Where:** §8.6; §8.2 IC-3; §7.4.

---

## Overclaim lens

### OVERCLAIM-1 [high] — "Nothing about user presence goes on the wire" is false — **A**
- **Reviewer asked:** reword §0 and IC-3(ii); list the channels; drop or coarsen `availBytes256M` and `engine.loaded`; add a law with N minutes, or state plainly that local use can change the wire.
- **r2 did both remedies where each is possible:**
  - **text:** IC-3(ii) now says no field names or encodes presence, but the accept/decline decision and availability change with local use, "so a paired peer can infer a coarse timeline of when the lending device is in use". §0.5 and §2.6 match, and the r1 claim is withdrawn;
  - **design:** removed `availability.reason`, `memory.availBytes256M`, `engine.loaded` and `queue.estStartS` (and `est` from `st`); added a 10-minute hold-down before `SERVING` is republished after a presence-caused drain; laws **LP-1** and **LP-2** with **W07-presence** vectors; §7.12's channel table;
  - the estimator compensates with requester-held load tracking and offer-time deadline declines.
- **Not done, and why:** the reviewer's "no wire change within N minutes" law is impossible while a lender yields to its own user at once, so r2 states plainly that it can change, as the reviewer allowed.
- **Where:** §7.4; §7.12; §8.2 IC-3; §2.6; §0.5; §4.3; §3.4 (W07-presence); X5; K28.

### OVERCLAIM-2 [high] — Peer-controlled `qb` and `tb` weaken the ClaimTracker — **A**
- **Reviewer asked:** a separate discard budget that clamps the prior; decide `hot` only from the requester's own trend; new M08 vectors.
- **r2 did:**
  - every observation input is measured by the requester (decode rate from its own chunk timing, TTFT from its own clock, completion tokens it counted);
  - discards depend only on those values, and the queue-bucket discard is removed. The single-flight engine means queueing does not change decode rate, and TTFT uses the lower quartile;
  - a **discard budget** clamps `prior'` to the lowest observed decode once more than 50% of observations are discarded (evaluated from 4 onward);
  - `hot` comes from the requester's own trend only; `st.tb` feeds only the router's estimate, where lying makes a peer look slower;
  - M08 vectors for a peer that always reports `qb=1`, one that reports `tb=2` while claiming 3×, and one that truncates answers.
- **Where:** §5.7; §5.11; X32; K11; §9.1 L0.2.

### OVERCLAIM-3 [high] — The owner brief and D12 undercount — **A** (same substance as CONFORMANCE-1)
- **r2 did:** the same fix as CONFORMANCE-1. The owner-brief count line is copied from §8.3's complete count, so the two cannot diverge silently.

### OVERCLAIM-4 [high] — A per-export signature means nothing without an out-of-band fingerprint; "subscribe" is not met — **A**
- **Reviewer asked:** owner-brief wording; mark third-party subscription NOT MET in §1.1 R3d; add a stable per-subscriber export key to D6 with its linkability cost.
- **r2 did:**
  - owner-brief sentence 4 says it plainly;
  - §5.4 defines a normative **fingerprint-comparison step**: exporter screen and local history; the recipient must scan or type the code over a channel the file did not travel through; the result context is not stored as trust. It also states the **honest equivalence**: for a one-shot file, a compared key fingerprint proves exactly what a compared file hash would;
  - §1.1 R3d/R3e mark third-party subscription NOT MET;
  - §5.8 S2 states the scope;
  - §5.11 has a new row, "signature on a file, not compared: nothing about origin";
  - D6 gains option **(e)**, a per-subscriber key, with its linkability cost;
  - K12 is updated.
- **Where:** §5.4; §5.8; §5.11; §1.1; D6; K12; `OWNER_BRIEF.md`.

### OVERCLAIM-5 [medium] — The 10× figure rests on desktop-class decode the named lenders may not reach — **A**
- **Reviewer asked:** per-lender ranges with bandwidth and quant assumptions labelled [A12]; a Deck range in the owner brief; the D-v2 owner-device benchmark as the gate.
- **r2 did:** §7.1 has a lender-class table with the workload stated (8B Q4_K_M, 5.03 GB; 500-token prompt, 300-token answer) and one assumption per row:
  - phone baseline [A12a];
  - **Steam Deck ~1.6–2.6× total, ~1.7–3.3× TTFT** [A12b];
  - CPU-only desktop ~0.9–2× [A12c];
  - M4 Pro-class or GPU desktop **~8–10×** [A12d]. r1's 45–50 tok/s for 8B was itself optimistic: scaling llama.cpp's 7B Q4_0 figure by file size gives ~38.
  §0.8 and the owner brief carry the Deck range. The D-v2 gate measures 8B decode and prefill on the Deck, the Dell and the phone, and the Peers tab may not quote a speed-up until then.
- **Where:** §7.1; §0.8; §9.3 D-v2; Appendix B A12a–d; `OWNER_BRIEF.md`.

### OVERCLAIM-6 [medium] — "Every transmission is ledgered" vs interval rows; lender STATE rows unspecified; byte law untestable — **A**
- **Reviewer asked:** put the aggregation and loss window into IC-2/IC-3; a durable CONTROL intent row before the first STATE or `st` per session on both sides; byte accounting at the TLS record layer with handshake bytes on DIAL rows; a vector for L-L15.
- **r2 did (and went further):**
  - the recommended D5(b) writes **one row per control message on both sides**, durable before sending, or before replying on receipt, so there is no interval aggregation;
  - IC-2 carries the D5(a) wording and loss window for the case where the owner picks (a);
  - byte accounting is at the TLS record layer: the initiator's handshake on `DIAL`, the listener's on a new `SESSION` open row, unattributed overhead on the `SESSION` close row (`overheadBytes`);
  - L-L15 is restated as testable with a record tap and a coalesced/split-frame vector;
  - new L-L16 (one row per control frame) and FC-2 (a control row that cannot be written means the message is not sent).
- **Where:** §8.2 IC-2, IC-3(v); §8.4; D5; K5.

### OVERCLAIM-7 [medium] — Tolerated TLS resumption defeats T9 — **A**
- **Reviewer asked:** require the client side never to offer resumption; W08 to assert a full handshake with CertificateVerify for every session; otherwise state the limit.
- **r2 did:**
  - JSSE and Conscrypt dialers create a fresh `SSLContext` per mesh connection, so no PSK can be offered; Network.framework disables resumption per connection (A26, in the S-A9 matrix);
  - W08 asserts no `pre_shared_key` in any ClientHello and a client CertificateVerify in every session;
  - §4.4 states the residual limit: a rooted or modified client holding a PSK defeats T9 against a lender that tolerates resumption.
- **Where:** §4.2 T2; §3.4 (W08); §4.4; Appendix B A26.

### OVERCLAIM-8 [medium] — The quiescence gate can pass without testing the law — **A**
- **Reviewer asked:** capture on the phone, all interfaces, 30 min, mesh on, an app opted in, state granted, Peers tab closed; zero SYNs to any peer address on any port and zero new DIAL/CONTROL rows; a positive control.
- **r2 did:**
  - M1 gate (5) is rewritten exactly so, and repeated on a desktop node in its borrowing role;
  - it names the capture method (root `tcpdump` or PCAPdroid root mode) and records the weaker fallback if the phone cannot be rooted (a gateway capture plus the overlay client's per-peer counters).
- **Where:** §9.3 M1 gate (5); §8.6.

### OVERCLAIM-9 [medium] — Three contradictions between status text and design — **A**
- **Reviewer asked:** mark the §8.1 row "requires owner exception (D1)"; reword IC-4 to allow pairing inside a user-opened window with confirmation on both devices; clarify that IC-1's range list applies to clause (b) and how overlay addresses become eligible.
- **r2 did all three:**
  - §8.1 row "requires owner exceptions (D1a, D1b)";
  - IC-4 reworded ("except the pairing messages exchanged inside a pairing window the user opened … and then only with the user's confirmation on both devices");
  - IC-1 now says overlay addresses (e.g. `100.64.0.0/10`, `fd7a:115c:a1e0::/48`) are eligible by interface selection, and the range list applies to (b) only.
- **Where:** §8.1; §8.2 IC-1, IC-4.

### OVERCLAIM-10 [low] — F26 overstates developer verification's 2026 scope — **A**
- **Reviewer asked:** restate F26 as the page puts it, and adjust D22.
- **r2 did:**
  - re-fetched developer.android.com/developer-verification on 2026-09-30, which **confirmed the reviewer**: from 30 Sep 2026 it covers installs from participating stores in Brazil, Indonesia, Singapore and Thailand; all apps on certified devices from 2027; an "Advanced Flow" exists; limited-distribution accounts reach up to 20 devices;
  - F26 is restated with quotes;
  - D22 and K20 are adjusted.
- **Where:** Appendix A F26; D22; K20.

### OVERCLAIM-11 [low] — `seq` "monotonic without trusted state"; file exports leak the export second — **A**
- **Reviewer asked:** state that `seq` is monotonic only if the clock is not behind; for file audience, truncate or omit `seq` and `issuedAtMs`; add projection vectors.
- **r2 did:**
  - the `seq` comment states its exact condition;
  - the file audience omits `seq`, `challenge` and `expiresAtMs` and truncates `issuedAtMs` to the day;
  - M06-file vectors;
  - B32.
- **Where:** §5.2; §5.8; §6.5 B32; §9.1 L0.2.

### OVERCLAIM-12 [low] — `includeBuild("..")` pulls Android modules into the lab when an SDK is present — **A**
- **Reviewer offered:** specify dependency-substitution rules, or consume published jars; run with `ANDROID_HOME` unset and add that to the gate.
- **r2 used a third mechanism**, project-directory mapping. The lab's own settings include only the five pure-JVM projects by path, pointing at `../core/*` and `../server`, and reuse the root version catalog file. The root settings are never evaluated, so AGP never enters the lab's classpath.
- **Why not the reviewer's options:**
  - substitution rules still need `includeBuild`, which evaluates the root settings;
  - consuming published jars would need publishing changes in the root build, which must stay untouched.
- **Gate:**
  - the lab job runs with no SDK;
  - `buildEnvironment` shows no `com.android` **even with the SDK present**;
  - `projects` lists only the mapped paths;
  - `git diff --exit-code` shows the root build untouched.
- **Where:** §3.5; §9.1; §1.5 X19.

---

## Synthesis-declared unresolved items (not findings; carried with status)

| Item | Status in r2 |
|---|---|
| Owner rulings pending on all 22 decisions | 26 open, 2 decided (D0 from directive B, D13 from directive A). D1 split into D1a/D1b; D23–D26 new; D18 absorbs the MLPerf baseline |
| Tailscale phone-client logs under Headscale | Unverified [A10]; D8 unchanged |
| Android 17 local-network permission vs loopback/VPN | Untested [A05][A06]; H5 unchanged |
| Spikes S-A2, S-A3, S-A9, S-A10, S-A11 | Open. New S-A12, S-B1, S-B2. S-A2, S-A10 and S-A12 can run in L0.7 if a Mac exists |
| Provisional thresholds | Still provisional [A11]. The tracker's inputs are now requester-only |
| GPU cancellation, ubatch effects, time-to-cool, plan fit | Unmeasured (B6, B8) |
| Deck Game Mode, inhibitors, fdinfo | Unverified [A07] |
| KV re-prefill cost on Deck/Dell (D19) | Unmeasured; the D-v2 owner-device benchmark now measures prefill |
| Effort estimates | Revised to 72–122 engineer-weeks; still rough [A24] |
| The Dell's OS, a Mac, CUDA terms | Owner inputs. The Dell's GPU is added; the Mac matters more under directive A |
| Bench-set sha256 pins | Q1 still to confirm by download; the L1 file source is new (D18) |
| Thermal polling conflict | Unchanged; conservative rule pending device confirmation |

## Owner directives applied alongside (not findings)

- **A** (Apple in scope): recorded as DECIDED D13. D14, D15 and D16 converted to "when and how". New D24 for sequencing.
- **B** (symmetric, holonic): recorded as DECIDED D0. §0 and §2 rewritten. Desktop CLIs borrow in mesh-1. New D25 and D26. Honest stop-line in §0.7.
- **C** (verified positioning): §0.3 added. §6 rewritten around MLPerf Mobile's baseline and four differentiators. D18 rewritten. Facts F40–F48.
- **D** (ordering): kept everywhere. Apple work before v2 is lab-only.

Details are in §12.2 of the brief.
