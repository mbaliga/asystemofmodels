# Final-review findings on ASOM_MESH_DESIGN.md revision 2 (round 2)

## Lens: conformance — verdict: needs-changes

### R2-CONFORMANCE-1 [high] Section 0.4 (Then, in roadmap order (directive D)); section 9 gate discipline (Directive D binds every row below); section 9.3 Must wait (in order); D4 recommendation (b1); section 10.0 (Every recommendation below respects it); OWNER_BRIEF (Still binding: versions stay in order, and the D4 row Yes, pulling forward only the cloud-ban column)

**Problem:** Directive D, restated by the owner after round 1, says roadmap versions are sequential. The brief's own ordered plan (section 0.4 and the section 9.3 table) runs v1.1, v2, D-v2, mesh-1, M2-M5 and never lists v2.5 or v3. It calls this roadmap order. D4 recommends (b1): mesh-1, which is roadmap v4, ships before v2.5 and v3. Only D4(c) is marked as against directive D. The owner brief says versions stay in order, then recommends Yes to D4 and says it pulls forward only the cloud-ban column. Section 1.4 and D4(b1) themselves say it pulls forward v4 itself and D-v2.

**Consequence:** The owner is told the plan respects their newest ordering directive while the recommendation reorders the ladder (v4 before v2.5 and v3). That is an owner decision presented as already consistent with a decided directive, and the owner-facing page understates what is pulled forward.

**Suggested change:** Make the default plan in section 0.4, section 9.3 and the owner brief strict order: v1.1, v2, v2.5, v3, then D-v2, M1 and later. Present D4(b1) and D4(b2) as options that override directive D, and say so in D4, section 10.0 and section 12.2 row D. Either change the recommendation to D4(a) or state that recommending (b1) asks the owner to revise their own 2026-09-30 directive. Fix the owner brief D4 row to read: pulls forward v4 (D-v2 plus mesh-1) and the v2.5 cloud-ban column.

### R2-CONFORMANCE-2 [high] D2 option (a) and its recommendation; D6 recording option (R1); D14 option (c) and its recommendation; IC-3 (It permits: automatic, background transmission); OWNER_BRIEF D2 row

**Problem:** IC-3 relaxes Invariant 1 to permit automatic background transmission to peers. That is the first relaxation of the no-automatic-egress rule, and roadmap section 13 Amendment 1 says that rule is not relaxed. D2(a) nonetheless recommends recording IC-1 to IC-4 (Invariants 1, 2, 3 and 5) as the one v4 amendment, although the roadmap defines Amendment 2 as Invariant 2 only. D6(R1) likewise recommends folding IC-5 (an Invariant 3 class) and IC-6 into Amendment 1. Meanwhile D14(c) rejects relabelling invariant-text changes as the under-reporting the audit warned about, and recommends a third amendment for IC-7/IC-8. The principle is inconsistent. The owner brief describes IC-3 only as an Invariant 1 clause.

**Consequence:** The recommended recording keeps roadmap section 13's count of exactly two by stretching both existing amendments over changes to five invariants. This blunts the escalation rule. The owner, reading the one-pager, may approve background peer transmission without seeing that it relaxes the product's core no-automatic-egress rule.

**Suggested change:** Apply D14's principle uniformly. Recommend D2(c) and D6(R2): each invariant change beyond the sanctioned Invariant 1 export action and the Invariant 2 listener is recorded as its own numbered amendment, or at least present this as the consistent option. In the owner brief D2 row, state plainly that IC-3 permits automatic, background transmission of banded device state and approved manifests to paired devices, which Invariant 1 currently forbids and Amendment 1 says is never relaxed.

### R2-CONFORMANCE-3 [high] Section 9.3 D-v2 entry criteria (v2 shipped on Android; D4; D24) and M1 entry criteria; D8 option (c); section 1.4 (roadmap v4 itself, with the design session held)

**Problem:** D-v2 is roadmap v4's asom-desktop item and M1 is the rest of v4. Roadmap v4's entry criteria are v3 shipped, design session held, and tailnet exists. Neither normative entry row lists the v4 design session. It appears only in section 1.4 prose. D8(c), LAN-direct only, would drop the tailnet-exists criterion, and no RT row records that. The v3-shipped criterion is waived through D4 (issue 1).

**Consequence:** A later session following the section 9.3 table could start v4 execution (D-v2) with no design session held. That violates roadmap section 2 and v4's Do not cold-execute rule. A tailnet-free mesh could also ship without any recorded change to the v4 entry criterion.

**Suggested change:** Add v4 design session held and recorded to both the D-v2 and M1 entry criteria. Add an RT row, ruled by D8, for replacing tailnet exists if D8(c) stays an option. Cite roadmap section 2's v4 entry row in both.

### R2-CONFORMANCE-4 [medium] Section 10.0 D0 (Consequences in this brief); section 0.2 row B; D25 option (a)

**Problem:** D0 is marked DECIDED, and it lists design choices as decided consequences: desktop CLIs borrow in mesh-1, consent per pair and per direction (T5), the protocol carries model inference only, and the restated stop-line. Directive B decided none of these. D25(a) presents desktops borrowing only for the owner CLI in mesh-1 as an open recommendation, which contradicts D0. Inference-only narrows directive B's own car visualisation and simulation example, and D26(d) offers general compute as open.

**Consequence:** Owner decisions the owner did not take are shown as settled. The CLI-borrowing surface (CD-24) and the inference-only scope escape explicit ruling.

**Suggested change:** Limit D0 to the directive's own words (symmetric, holonic, phone not privileged, ubiquitous-computing goal). Move the listed consequences to a list headed Design consequences, recommended, each tied to its ruling decision (D25, D26, D2, D23).

### R2-CONFORMANCE-5 [medium] Section 8.3 complete count and the OWNER_BRIEF count; D25 option (b)

**Problem:** D25(b) adds a desktop local-app API. It either serves the frozen section 5 HTTP API on a non-Android host or uses HTTP over a Unix socket, which the text itself calls a contract addition. It also adds a new app-token minting path, the TTY-confirmed asom pair-app, in place of section 5.7 AIDL pairing, with self-reported app labels. None of this has a CD or RT row, and none of it is in the complete count.

**Consequence:** D23's rule (an item with no ruled decision is not approved) cannot catch an item the registry never lists. A new pairing mechanism and app-facing transport could reach M2 without an explicit contract sign-off.

**Suggested change:** Add registry rows for the desktop local-app transport, the asom pair-app token-minting path, and the self-reported callerPkg label, each ruled by D25 and D14 part B. Update the count in section 8.3 and in the owner brief.

### R2-CONFORMANCE-6 [medium] Section 1.2 C-2 (mesh-1 needs none of it); D25(a) (no new app-facing surface); section 8.4 callerPkg comment (local-uid:<uid> (unverified process)); CD-24

**Problem:** In mesh-1 the owner CLI is an unpaired, uid-identified inference caller whose prompts leave the device for peers. It uses the SO_PEERCRED identity that IC-8 part B is drafted to sanction. Yet the brief says mesh-1 needs no IC-8 text, and CD-24 is ruled only by the dependency registry D23. Invariant 5 currently admits only AIDL-verified identity for inference callers. Roadmap v4 sanctions only a CLI shell.

**Consequence:** An inference-caller identity model outside Invariant 5 ships in M1 without an invariant ruling. The claim that mesh-1 needs no IC-8 text is an overclaim.

**Suggested change:** Either gate M1 on D14 part B for the CLI-requester path, or add an explicit statement, ruled in D2 or D25(a), that Invariant 5 does not govern the owner CLI, citing roadmap v4's CLI shell. Also list D23 (CD-24) in the D-v2 entry criteria.

### R2-CONFORMANCE-7 [medium] Section 8.3 CD-1m (phase v2 / mesh-1) and CD-14 (reach, terminal, servedClass: phase v1.1 / v2 / mesh-1, decision D5 plus D3); section 9.3 v1.1 entry (D5) and v2 entry (D6, D7, D18, D23)

**Problem:** The meaning change to frozen section 5.4 X-Asom-Egress (CD-1m), and the columns that implement it, are phased for v1.1 and v2. Their ruling decision, D3, first appears as an entry criterion only at M1.

**Consequence:** A frozen header's meaning, and the ledger's egress semantics, could change in v1.1 or v2 without the owner ruling D3.

**Suggested change:** Add D3 to the v1.1 and v2 entry criteria, or re-phase CD-1m and the reach, terminal and servedClass columns to M1 only.

### R2-CONFORMANCE-8 [medium] D24 option (b); section 9.3 M3 row (may start right after v2 ships, in parallel with D-v2); RT-7 (D13 (DECIDED; text update only)); RT-8

**Problem:** Directive A leaves Apple sequencing open, so D24 is properly open. But D24(b) inserts new ladder entries into a strictly sequential roadmap: M3 immediately after v2, running in parallel with D-v2, and macOS inside D-v2. Under any D4 option, M3 then precedes v2.5. RT-7 calls the Apple roadmap change text-only, and RT-8 covers only mesh-1. D24 does not say where M3 goes under D4(a).

**Consequence:** A change to roadmap order, including a concurrent track, is recorded as a text-only update under a DECIDED item. That understates it.

**Suggested change:** Add an RT row, ruled by D24, for inserting the Apple phases and a parallel track into the version ladder. State D24(b)'s placement under each D4 option, and say explicitly that a parallel track departs from roadmap section 0's strictly sequential rule.

### R2-CONFORMANCE-9 [low] OWNER_BRIEF sentence 5 (Nothing ships before v1 is validated and v2 exists)

**Problem:** Under the recommendations, v1.1 ships H1 to H5 before v2 exists. H1 includes LEDGER_UNAVAILABLE, which changes v1 cloud behaviour and roadmap v1.1's locked delta.

**Consequence:** The owner may rule D5 believing nothing changes before v2.

**Suggested change:** Reword to: Nothing ships before v1 is validated; v1.1 then adds H1-H5, including the new LEDGER_UNAVAILABLE error (D5); mesh work waits for v2 and later.

### R2-CONFORMANCE-10 [low] Section 9.3 M2 (ACCESS_LOCAL_NETWORK flow) and M1 gate (9) (targetSdk still 35, or H5 passed); T14

**Problem:** The plan contemplates raising the Android target to targetSdk 37, but brief section 3 pins compileSdk 35 and targetSdk 35. No RT row or decision covers the change.

**Consequence:** A frozen build pin can change as a side effect of M2 with no owner ruling.

**Suggested change:** Add an RT row, for example ruled by D23, for any targetSdk bump. Or state that targetSdk stays 35 through M2 unless the owner rules otherwise.

### R2-CONFORMANCE-11 [low] Section 5.3 editorial reference table row; B22; section 5.7 capRef

**Problem:** Roadmap v2 P6 describes the editorial benchmark table as a read-only seed. The design adds a new owner offline signing key compiled into bench-core, fail-closed handling of unsigned entries, and use of the table to cap peer claims. None of this has an RT row or an owner task.

**Consequence:** The text of roadmap v2 P6, an execution-grade version, and the owner's obligations change without a flagged ruling.

**Suggested change:** Add an RT row for the v2 P6 editorial-table text, ruled by D18 or D23. Add owner-held reference key custody to the owner tasks.

### R2-CONFORMANCE-12 [low] D12 (Surface trims, deferrals and additions, as one ruling)

**Problem:** D12 bundles five new app-facing contract values (the peer: alias form, asom-peer, MESH_STREAM_INTERRUPTED and two X-Asom-Failover values) with deferrals and rejections into a single accept-the-package ruling. Brief section 5 requires owner sign-off for contract additions, and D23(c) itself says a blanket approval hides rulings.

**Consequence:** Additions to the frozen contract can be approved wholesale alongside uncontroversial trims.

**Suggested change:** Split D12's Add items into per-item sign-off lines, or move them to their own decision, and keep the trims and deferrals as the package.

## Lens: overclaim — verdict: needs-changes

### R2-OVERCLAIM-1 [high] Section 5.7 observation definitions (decodeObs, completionTokens); the 'Bound it gives' list; section 5.11 claim-tracker row; K11 residual

**Problem:** The r2 hardening says every input to the tracker is measured by the requester, so no peer can manipulate it. But decodeObs = (tokens received - 1) / (time from first to last content chunk), and the peer controls both parts. It can send the first chunk promptly, buffer the rest and burst them at the end, which drives the denominator toward zero. It can also split or merge chunks. And the requester has no stated way to count tokens: it may not hold the tokenizer for a model it is borrowing precisely because it cannot hold that model. Once n >= 3, Effective = median(decodeObs) with no cap: the 5000-permille ratio cap bounds only the state, not the rate used for placement.

**Consequence:** A hostile or buggy peer can look arbitrarily fast indefinitely and attract placements, which is the K11 threat. The stated bound (at most 1.25x over-claim, claims stop mattering after 3 observations) is false, and the three new M08 adversary vectors all miss this attack.

**Suggested change:** Define observations the peer cannot shape. Use end-to-end time per output unit, measured from sending INFER_BODY to INFER_END, and derive the estimator's total-time prediction from it rather than from a decode-only rate. Count output in units the requester can compute without the model: UTF-8 bytes of the delta text, or tokens from a catalogue-pinned tokenizer if one is shipped. Add M08 vectors for a burst-at-end peer and a split-chunk peer. Restate the bound, including what still cannot be bounded.

### R2-OVERCLAIM-2 [high] OWNER_BRIEF.md (decision table and the 'complete count'); D14(a) vs D6(R1) vs D14(c); section 8.1 row for roadmap section 13

**Problem:** Under the brief's own recommendations, the roadmap ends up with more than two amendments. D2(a) enlarges Amendment 2 to cover Invariants 1, 3 and 5. D6(R1) folds a new Invariant 3 class and a second export action into Amendment 1, which roadmap section 13 limits to Invariant 1 and exactly one action. D14(a) explicitly records a third amendment for platform equivalence (Invariants 4, 5, 7, 8). The one-page owner brief never mentions D14 and never says that accepting the recommendations creates a third amendment. That is the one item the standing rules require to be an explicit owner decision. The recording standard is also inconsistent. D14(c) rejects relabelling invariant text changes as 'interpretation' because that under-reports. D6(R1) and D2(a) instead recommend widening an already-sanctioned amendment by rewriting section 13, which is the same move in a different form.

**Consequence:** An owner who reads only the one-pager can approve D2 and D6 without seeing that the package also carries a recommended third amendment (D14), plus two enlarged sanctioned amendments. The anti-under-reporting discipline is applied selectively.

**Suggested change:** Add a D14 row to the owner brief that says: 'Recommended: record platform-equivalence text as a THIRD amendment (roadmap section 13 escalation)'. Add one sentence stating the net amendment count if every recommendation is taken. Then either apply one rule everywhere (any invariant text beyond an amendment's sanctioned scope is recorded as an additional amendment), or explain in D6 why folding IC-5/IC-6 is not the relabelling that D14(c) rejects.

### R2-OVERCLAIM-3 [high] Section 7.1 Steam Deck row; Appendix B A12b; section 0.8; OWNER_BRIEF sentence 2

**Problem:** The Deck figures do not follow from the assumption stated next to them. A12b gives decode at 55-70% of 88-102 GB/s over a 5.03 GB file, which is 9.6-14.2 tok/s, not 8-12. It gives prefill at 25-40% of ~1.6 TFLOPS, and an 8B model costs about 2 x 8.2 GFLOP per token, so prefill is about 24-39 tok/s, not 50-100. Recomputed, the Deck's TTFT for 500 prompt tokens is about 13-21 s against the phone's 16.7 s, roughly 0.8-1.3x, not the stated 1.7-3.3x. Total time comes to about 1.5-2.3x, not 1.6-2.6x.

**Consequence:** The owner-facing headline, the r1 overclaim fix, repeats a number that is not derived from its own stated basis, and it overstates the Deck's TTFT benefit. On a prefill-heavy request the Deck may be no better than the phone.

**Suggested change:** Recompute the Deck row from A12b, or restate A12b with the actual basis used (for example FP16 packed-math throughput, with a source). Carry the corrected ranges into section 0.8 and the owner brief. State explicitly that Deck TTFT may not beat the phone.

### R2-OVERCLAIM-4 [medium] Section 6.1 comparability rule; section 6.8 'MLPerf-comparable' label; D18; B29/B30; risk register

**Problem:** asom would stamp 'MLPerf-comparable' on its own rows, produced with a different runtime (llama.cpp), a different quantisation (Q4_K_M/Q8_0) and no MLCommons review. MLPerf is an MLCommons trademark, and its Results Messaging Guidelines restrict using the MLPerf name for results that do not follow the rules, with unverified results to be disclosed as such. The brief does not mention trademark or results-messaging policy anywhere. Its own section 6.9 says an asom number and an MLPerf number 'can differ widely', which the word 'comparable' contradicts. Separately, the metric-definition leg of the rule is a placeholder until S-B1.

**Consequence:** Possible trademark or policy conflict for an owner-published app, and a label that invites exactly the like-for-like comparison the brief disclaims.

**Suggested change:** Rename the label to something that states the fact without the mark, for example 'same models and metric definitions as MLPerf Mobile v6.0; not an MLPerf result'. Add an assumption and owner check against the MLCommons Results Messaging Guidelines before any shipped text uses the MLPerf name. Add a K-row, and a D18 sub-item. Keep the label disabled until S-B1 pins the formulas.

### R2-OVERCLAIM-5 [medium] Section 9.3 M1 gate (5), quiescence fallback method

**Problem:** If the phone cannot be rooted, the gate allows capture at the Wi-Fi gateway plus the overlay client's counters, recorded as weaker. Over the recommended overlay (D8(b), Headscale), SYNs to paired-peer overlay addresses travel inside WireGuard and are invisible at the gateway, while the overlay's own keepalives keep its counters moving. PCAPdroid's non-root mode uses VpnService, and Android runs only one VPN at a time, so it cannot run alongside the overlay. The positive control (a SYN within 5 s of opening the Peers tab) therefore cannot be observed on the overlay path either.

**Consequence:** The gate can be marked passed on the default underlay without observing the traffic the law is about. This is the OVERCLAIM-8 failure mode surviving in the fallback branch.

**Suggested change:** State that the fallback can pass only with a LAN-direct-only configuration (overlay off) whose positive control is observed at the gateway. With the overlay active, gate 5 requires rooted on-device capture, or otherwise stays NEEDS-DEVICE-VALIDATION. A 'weaker method' result must not count as passed.

### R2-OVERCLAIM-6 [medium] IC-2 (Invariant 3(d) text: 'Bytes are counted at the TLS record layer'); section 8.4 byte accounting; L-L15; control rows 'durable before the message is sent'; M4/M5 on Network.framework

**Problem:** Proposed invariant text hard-codes a mechanism, TLS-record-layer byte counts per frame, with the row made durable before sending. That requires the ciphertext record size before the write. This is feasible with JSSE or Conscrypt SSLEngine by wrapping first, but it is unverified on Network.framework, where NWProtocolFramer normally sits above TLS and exposes no record-layer bytes. No assumption or spike covers it, yet M4 (iOS requester) and M5 (iPad lender) must meet the same invariant, and S-A9 does not include it.

**Consequence:** Invariant text the owner is asked to sign may be unimplementable on the Apple stacks that directive A puts in scope. That would force either a false ledger or another invariant change later.

**Suggested change:** Add an assumption (for example A35: Network.framework exposes per-connection TLS record-layer byte counts before or at send) and add it to the S-A9 matrix. Alternatively, word the invariant platform-neutrally ('bytes counted at or below the TLS layer, method per stack in the conformance suite'), and keep L-L15's exact record-tap law for the JVM stacks only.

### R2-OVERCLAIM-7 [medium] Section 8.4 event table vs L-L16, IC-2, FC-2; section 4.3 frame list

**Problem:** IC-2 and L-L16 say every control frame on an authenticated session gets exactly one row on each node. The event table assigns rows only to HELLO/HELLO_ACK, STATE_REQ/STATE, GOAWAY, manifest presentations and pairing/revocation. ERROR, MANIFEST_REQ, CANCEL (outside an attempt) and HELLO-level declines have no specified row. FC-2 also says that when a control-row append fails, the session is closed 'with GOAWAY', but GOAWAY is itself a control message whose row must be durable before it is sent. FC-2 therefore requires the very write that just failed.

**Consequence:** L-L16 either cannot pass or is vacuous for the unlisted frames, and FC-2 is self-contradictory under disk-full conditions, which is precisely when it applies.

**Suggested change:** Give every frame type in section 4.3 a row rule, or list explicitly the frames exempt from L-L16 with a reason. Change FC-2 to close the TLS connection without a GOAWAY frame, or declare the ledger-failure GOAWAY row-exempt and say so in IC-2.

### R2-OVERCLAIM-8 [medium] Section 3.4 oracle rule; L0.2 gate ('vectors tagged self-oracled until the Kotlin verifier, written from the prose, agrees'); L0.1 xcheck.py

**Problem:** The only thing that clears the self-oracled tag is an implementation 'written from the prose, not ported from the generator'. Nothing enforces that independence. In the lab, the same author (the same session) writes the vector generator, the Kotlin verifier and the Python checker. Their agreement shows consistency, not an independent reading of the spec.

**Consequence:** A shared misreading of the spec passes the gate, and the tag is dropped without the independence it certifies. This is the K18 risk ('shared spec misunderstandings') being cleared by a gate that cannot detect it.

**Suggested change:** Define what counts as independent: a different session or author with no access to the generator source, recorded in PROGRESS.md, or agreement with the Swift lane (L0.7) written from the prose. Until then, keep the self-oracled tag on the vectors, and do not let the L0.2 gate claim independence.

### R2-OVERCLAIM-9 [medium] Section 7.4 LP-1/LP-2 and W07-presence vs X20, section 3.1 PF role (Android screen on, M2; iPad lend screen frontmost, M5)

**Problem:** LP-1 classifies screen state and foreground app as presence inputs. LP-2 says a presence signal drains immediately and blocks re-publishing SERVING for 10 minutes. But PF lenders serve only while the screen is on or a lend screen is frontmost, and X20 says a frontmost lend screen is consent, not activity. The laws and the W07-presence vectors do not carry that exception.

**Consequence:** As written, a PF lender either can never reach SERVING, because its required screen-on state is itself a presence signal, or it violates LP-2. One of the two M2/M5 lending shapes is incoherent with the laws.

**Suggested change:** Amend LP-1 to exclude the asom lend screen being frontmost (and the screen-on state it requires) from presence inputs. Define presence for PF as leaving the lend screen or any input outside it. Add W07-presence vectors for the PF case.

### R2-OVERCLAIM-10 [low] Section 8.3 CD-D ('4 new third-party dependencies'); section 8.1 row 8; section 6.1 S-B2; section 0.8 count list

**Problem:** Section 8.1 names 'zxing-core + CameraX' for GMS-free QR scanning, but CD-D counts only zxing-core. CameraX is not in the repo today, so it is a new dependency. S-B2 may add MLPerf LoadGen as a dependency with no conditional registry entry. Separately, section 0.8 says 'section 8.3 counts all of it', but its own list omits the control channel, the catalogue field, 9 RT changes, modules, dependencies and the conformance artefact. The owner brief lists them.

**Consequence:** The 'complete count' repeated to the owner is not complete, which is the CONFORMANCE-1 class of defect again, in small form.

**Suggested change:** Add CameraX (M2 or M1, D23) and a conditional LoadGen entry (v2, after S-B2) to CD-D and update the count. Make section 0.8 either repeat the full count line or point to it without listing a subset.

### R2-OVERCLAIM-11 [low] Section 0.3 bullet on MLPerf; section 6.0 facts list vs Appendix A F40

**Problem:** The prose says MLPerf Mobile v6.0 covers 'NPU paths on Snapdragon 8 Elite Gen 5, Dimensity 9500 and Exynos 2600' as a VERIFIED fact [F40]. The fetched source confirms NPU-accelerated LLM execution only on Snapdragon 8 Elite Gen 5 (Llama 3.1 8B). For Dimensity 9500 it says 'new support' and for Exynos 2600 'updated support', without saying the LLM runs on their NPUs. Appendix A's F40 row is worded correctly; the prose is stronger than its tag.

**Consequence:** A VERIFIED tag is attached to a claim broader than what the source supports. The owner's directive wording is repeated, but the brief's own rule is to keep verified facts distinct.

**Suggested change:** Reword sections 0.3 and 6.0 to 'NPU-accelerated Llama 3.1 8B on Snapdragon 8 Elite Gen 5; Dimensity 9500 and Exynos 2600 supported (LLM NPU path not stated by the source)', or mark the broader reading as owner-verified (directive C) separately from the r2 fetch.

### R2-OVERCLAIM-12 [low] Section 5.4 bullets ('Pins in FILE contexts expire after 90 days'; 'never stored as trust for later files'); D2(b) ('FILE-pinned context with a 30-day TTL')

**Problem:** Section 5.4 says a fingerprint match is never stored as trust, because every export has a new key. The same section then gives FILE pins a 90-day expiry, and D2(b) gives a 30-day TTL. The brief never says what is pinned, or for what, if nothing persists.

**Consequence:** Implementers of L0.2 get contradictory rules for the FILE context, and verifier behaviour will diverge across the JVM and Swift lanes.

**Suggested change:** State that per-export FILE matches are never stored (no TTL). Apply the 90-day and 30-day TTLs only to the persistent-key cases (a D6(e) per-subscriber key; D2(b) hand-shared mesh presentations), each with one value.

## Lens: directives — verdict: needs-changes

### R2-DIRECTIVES-1 [high] D4 recommendation (b1) and D24(b) against section 0.2 row D ('Kept. Every shipped phase follows V1-close, in roadmap order'), section 0.4 ('Then, in roadmap order (directive D)'), section 10.0 ('Every recommendation below respects it'), section 9.3 'Must wait (in order)', and OWNER_BRIEF 'Still binding: ... versions stay in order' next to the D4 row 'mesh-1 after v2, before v2.5 and v3? Yes'

**Problem:** Directive D and roadmap section 0 say versions are strictly sequential, and the roadmap's v4 entry criterion is 'v3 shipped'. The recommended D4(b1) ships v4 content (D-v2 asom-desktop and mesh-1) before v2.5 and v3. D24(b) also ships the iOS app (M3) between v2 and v2.5. The brief still claims each time that the recommendation respects directive D. Section 0.4 and section 9.3 leave v2.5 and v3 out of the ordered plan altogether, so nothing says where they fall relative to D-v2 and M1-M5. D4(c) is rejected only for building on an unvalidated v1, as if directive D meant nothing more than 'v1 first'.

**Consequence:** The owner is told that the directive they just restated is honoured, while the headline recommendation reorders the roadmap. A later session could run mesh-1 before v2.5 and v3 believing directive D allows it. This is the pre-emption pattern CONFORMANCE-3 was meant to remove, now applied to an owner directive.

**Suggested change:** Mark D4(b1), D4(b2) and D24(b) as requiring the owner to relax directive D, roadmap section 0 and v4's 'v3 shipped' entry criterion explicitly. Alternatively, make D4(a) the directive-compliant recommendation. Correct section 0.2 row D, section 10.0 and the owner-brief 'Still binding' line. Put v2.5 and v3 into section 0.4 and the section 9.3 table in their actual position under each D4 option. Extend RT-8 to cover M3 and the macOS variant shipping before v2.5.

### R2-DIRECTIVES-2 [high] Section 0.3 differentiator 3 and section 6.0 D-iii ('Desktop coverage: Linux (including the Steam Deck) and macOS. MLPerf Mobile's app targets Android, iOS and Windows [F43]'); the section 6.0 argument that 'MLPerf's numbers describe MLPerf's runtime and delegates, not the engine a request would run on'; OWNER_BRIEF sentence 3 ('adds only ... Linux/macOS coverage')

**Problem:** The brief checks only MLPerf Mobile. MLCommons also ships MLPerf Client, and its v1.6 release page (https://mlcommons.org/2026/04/mlperf-client-v1-6/) states that llama.cpp with Metal and MLX run on macOS and iPad, with GUI versions on the iOS and Mac App Stores and Steam. A search snippet for its GitHub releases (https://github.com/mlcommons/mlperf_client/releases, not fetched) lists Windows, Linux and macOS and a v2.0 model set that includes Llama 3.1 8B and Qwen 3 8B. So macOS coverage, and probably Linux, is not a differentiator. MLPerf Client also runs llama.cpp itself, which weakens the claim that MLPerf measures a different engine. Directive C's premise was incomplete, and the brief carried it forward without checking MLCommons' desktop benchmark.

**Consequence:** One of the four differentiators the owner is asked to fund (the D-v2 desktop bench CLIs and macOS packaging) may be me-too work, which is exactly what directive C tells the design to avoid. The positioning section, labelled 'verified', overclaims.

**Suggested change:** Add a fact F49 (MLPerf Client: platforms, runtimes and models, with source and fetch date). Re-scope D-iii to what MLPerf Client does not do, most likely signed manifests, results feeding a router and Steam Deck Linux specifics, after checking it. Evaluate MLPerf Client's desktop model set and methodology as the desktop comparability baseline, alongside the MLPerf Mobile baseline for phones. Tell the owner plainly that directive C's desktop-coverage premise needs revisiting.

### R2-DIRECTIVES-3 [medium] Section 0.3 ('§6 therefore adopts its models and metric definitions as the comparability baseline'); OWNER_BRIEF sentence 3 ('the benchmark adopts MLPerf Mobile v6.0's models and metrics' and 'What nobody else ships is the router'); D18(a); section 6.2

**Problem:** Under the recommended D18(a), the default bench set is Qwen3 (Q1), and the Llama set L1 is opt-in behind a licence screen. A default run therefore produces no row that meets the section 6.1 'MLPerf-comparable' rule. The MLPerf metric formulas and prompt subsets are unverified [A27], and the prompt licences are assumed [A30]. For a default user this is effectively D18(c), which the brief says leaves 'the me-too critique partly' standing. The owner brief also turns F48's 'absence of evidence, not proof' into 'nothing else ships'.

**Consequence:** The owner reads that the MLPerf baseline is adopted, while the recommended configuration makes comparability an opt-in minority path whose definitions are not yet known. This is an overclaim on the directive the owner gave.

**Suggested change:** State in section 0.3 and the owner brief: 'MLPerf-comparable only for users who opt into the Llama set, and only after S-B1 pins the definitions; default runs are not comparable.' Offer, as a D18 option, making L1 the default where the user accepts the licence at first run. Change 'What nobody else ships' to 'no project we found ships' [F48].

### R2-DIRECTIVES-4 [medium] Section 0.1 ('whole on its own: with the mesh off it serves its own apps exactly as a standalone asom does'); section 2.1 holonic table; D0 ('Every node is at once an individual, serving its own apps alone'); section 7.8 M4; D26(a) ('a requester-only node profile')

**Problem:** Directive B's holon is asserted for every node, but several scheduled or sketched nodes cannot serve their own apps alone. The M4 iPhone is an in-app SDK with a single home lender and only a CloudOnly fallback; no local engine serves its apps. The D26 car and appliance profile is requester-only, with no local serving. Desktop nodes in mesh-1 serve only the owner's CLI (no local-app API until M2 and D25). The brief never says which node classes fall short of the 'individual' half of the holon, or in which phase.

**Consequence:** The framing claims more symmetry than the plan delivers. The owner cannot see that iPhone, car and appliance nodes are borrow-only, and a later session could treat the universal claim as met.

**Suggested change:** Add a 'holon completeness' column to the section 2.1 role table. For each platform and phase, record whether the node can serve its own apps alone, borrow and lend. State plainly that the iPhone (M4) and the D26 profiles are borrow-only unless a local-serve path is scheduled, for example the section 10A Embedded tier inside AsomKit. List that as a D15 or D26 option.

### R2-DIRECTIVES-5 [medium] Section 3.2 item 1 ('SERVING → DRAINING, and the node reports availability.reason = sleeping in any open session'); section 7.4 laws LP-1 and LP-2; IC-3(ii)

**Problem:** OVERCLAIM-1 closure is incomplete. Section 7.4 says r2 removed availability.reason from the wire, yet the retained FSM change in section 3.2 still sends reason = sleeping to peers. LP-2 also says a change in presence inputs alone can change qb. That contradicts LP-1 and the IC-3(ii) text the owner is asked to sign: 'no field is computed from presence inputs except through the ... accept-or-decline decision and its availability state'.

**Consequence:** An implementer following section 3.2 puts back a field the design claims to have removed. The W07-presence vectors cannot satisfy LP-1 and LP-2 at once, and IC-3(ii) is again stronger than the mechanism.

**Suggested change:** In section 3.2 item 1, replace the wire reason with 'fsm = DRAINING; the reason stays in the local ledger'. Either remove qb from LP-2, making qb provably independent of presence inputs, or add qb to the exception list in LP-1 and IC-3(ii) and to the section 7.12 channel table.

### R2-DIRECTIVES-6 [medium] D24(b) and the section 9.3 M3 row ('the iOS benchmark app (M3) starts right after v2 ships'); the M4 entry criterion 'M3; M1; D15'; B25

**Problem:** The first Apple deliverable recommended is a standalone iOS benchmark app. MLPerf Mobile is already on the App Store, and MLPerf Client has iOS and iPad App Store GUIs. Under B25 and 'no mesh', the app's results never feed a router. Directive C says the router is the differentiator and the harness is not. M4, the iOS requester that actually carries router value, is gated on M3, with no stated technical reason.

**Consequence:** Apple effort goes first to the part of the product directive C calls me-too, and the router-bearing iOS work is delayed behind it.

**Suggested change:** Justify M3's priority in D24 against directive C, or offer a D24 option that removes M3 from M4's entry criteria and orders M4 first once M1 exists. State that M3's only differentiators are the signed file and plain text, since its results feed no router.

### R2-DIRECTIVES-7 [low] IC-2 ('Every control message sent or received on an authenticated session is ledgered in its own row'); L-L16; section 8.4 event table ('Pairing, revocation notice: one row each side'); D5(b) ('Meets the sanctioned text literally')

**Problem:** The per-message claim behind the CONFORMANCE-9 and OVERCLAIM-6 closure is not carried through the event table. Pairing is a multi-frame PAIR_* exchange but gets one row per side. The ERROR, CANCEL and MANIFEST_REQ frames are assigned no row, so L-L16 ('every control frame ... exactly one CONTROL row') either fails or is ambiguous.

**Consequence:** The 'literal compliance' argument for recommending D5(b) over D5(a) rests on an incomplete specification, which is the same byte and row under-accounting the audit lesson warns about.

**Suggested change:** Enumerate every frame of section 4.3 in the section 8.4 event table with its row kind on both sides, including PAIR_* per message, ERROR, CANCEL and MANIFEST_REQ. Alternatively, restrict L-L16 and IC-2 to a named frame list and state the exception.

### R2-DIRECTIVES-8 [low] Section 5.5 ('Decision (D12): Ship no A2 ...'); section 3.4 ('Decision: Option 1' and 'Hold them together with a language-neutral conformance suite at the repo root')

**Problem:** Residues of CONFORMANCE-3 and CONFORMANCE-10. Section 5.5 presents a D12 recommendation under the heading 'Decision'. Section 3.4 still puts the conformance suite 'at the repo root', although r2 moved it to lab/conformance/ until promotion (CD-C, section 9.1 rule 4).

**Consequence:** A reader of the body sections sees pending rulings as settled, and a contradictory location for a normative artefact.

**Suggested change:** Retitle section 5.5 to 'Recommendation (pending D12)'. Change section 3.4 to 'in lab/conformance/, promoted to a repo-root conformance/ only under D23'.

### R2-DIRECTIVES-9 [low] D1a scope, section 9.1 L0.2 and L0.3, against D1b's rationale ('builds the artefacts D2, D3 and D5 decide, which creates sunk-cost pressure')

**Problem:** L0.2 falls under D1a, which may start now. It includes the ClaimTracker with the M08 adversary vectors and MESH-mode verifier step 15b. Both exist only for the mesh and are ruled by D2 (CD-23 frames and IC-3). L0.3 pins the proposed L1 Llama set ahead of D18. This recreates, in D1a, the sunk-cost pattern that CONFORMANCE-10 split D1 to avoid.

**Consequence:** Mesh-only code is built ahead of the D2 ruling under an authorisation the owner is told covers only 'v2 manifest and bench maths'.

**Suggested change:** Move the ClaimTracker, M08 and step 15b to D1b, or list them explicitly in D1a as mesh-only items built ahead of D2. Mark the L1 pins in L0.3 as conditional on D18.

### R2-DIRECTIVES-10 [low] REVIEW_ROUND1_DISPOSITION CONFORMANCE-1 ('copied it verbatim, as one line, into the owner brief'); OWNER_BRIEF count line against the section 8.3 complete count

**Problem:** The owner-brief line is not verbatim. It drops 'an intent status and new export keys' (CD-16: status 0 means intent; CD-17: new keys in the v1 export JSON). Both change the semantics of the v1 ledger export in v1.1. It also collapses the peer-plane itemisation.

**Consequence:** A small version of the undercount that CONFORMANCE-1 and OVERCLAIM-3 fixed returns in the document the owner is most likely to read, and the disposition log overstates its own fix.

**Suggested change:** Copy the section 8.3 count into the owner brief exactly, or correct the disposition wording to 'condensed', and add 'an intent status (status 0) and new export keys (v1.1)'.

### R2-DIRECTIVES-11 [low] Section 2.1 PA row ('Linux, Deck and macOS while awake (mesh-1; macOS per D24)') against the section 9.3 M1 row ('Linux/Deck lenders') and the M1 gates

**Problem:** If D24(b) puts macOS into D-v2, the role table makes a Mac a mesh-1 lender. The M1 scope names only Linux and Deck lenders, and no M1 gate (W08 on the Mac's JSSE build, S-A3 key tier, Local Network behaviour for the launchd agent) covers a macOS lender.

**Consequence:** Under directive A's recommended sequencing, a Mac could lend in mesh-1 with no gate that validates it.

**Suggested change:** Add to the M1 row: 'macOS lends in M1 only if D24(b) placed it in D-v2', with gates for macOS (W08 on the macOS JVM, the S-A3 outcome recorded, and a Local Network permission check for the agent). Otherwise, state that the Mac lends from M2.
