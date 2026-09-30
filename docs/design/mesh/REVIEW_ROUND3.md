# Final-review findings on revision 3 (round 3) — NOT YET DISPOSITIONED

Recorded at the point the build phase began (2026-09-30). The design brief and specs are at revision 3; these 39 findings
were raised against it and have not been folded into a revision 4. Builders treat them as KNOWN SPEC DEFECTS: where one
touches what they implement they must choose the conservative reading, record the choice in their track's ERRATA file,
and never silently guess. None was rejected by the session; severity is the reviewers'.

## Lens: conformance — verdict: needs-changes

### R3-CONFORMANCE-1 [high] ASOM_MESH_DESIGN.md 8.2 IC-4(b), 8.3 CD-19, 8.4 row schema comment, 10.3 D25(a); LAB_SPEC.md 7.5 callerPkg forms; OWNER_BRIEF.md D25 row

**Problem:** IC-4(b) is a conditional clause added to the already-decided Amendment 3 through an open decision, and it goes beyond AD-2's stated scope for Invariant 5 in two ways. (1) Its subject is how a local caller is identified on each OS: SO_PEERCRED, getpeereid, the Windows socket ACL, self-ui. The brief itself assigns that subject to proposed Amendment 5 ('local identity per OS', IC-8). AD-2 limits Amendment 3's Invariant 5 change to node keys and says 'app identity on-device stays Binder-verified'. (2) The clause is worded generically: 'an app's own screen driving the requester built into that same app'. CD-19, the 8.4 schema and LAB_SPEC 7.5 apply self-ui: to 'iOS host apps' own AsomKit ledgers'. D25(a) and the owner brief name only the desktop CLI and the Ubuntu Touch screen. The RT-1 replacement text for roadmap 13 and roadmap 14 item 6 do not mention the clause at all.

**Consequence:** Ruling D25(a) as the owner brief presents it would also exempt every owner iOS host app from Invariant 5's app-identity regime. That is exactly the 'stretch an existing amendment' move AD-2 rejected, and 7.8 calls this kind of remote/host-app identity further-amendment material (D15(e)). In effect, part of a new amendment rides in on a decided one.

**Suggested change:** Move IC-4(b) into Amendment 5 part B (IC-8), or keep it in Amendment 3 but name it explicitly in the RT-1 roadmap-13 text and the roadmap 14 item 6 wording. Replace the generic wording with an enumerated list: the desktop owner CLI; the Ubuntu Touch asom app's own screen; the iOS asom app's own screen. Remove 'iOS host apps' from the self-ui: form in CD-19, the 8.4 schema comment and LAB_SPEC 7.5, or give it a separately named sub-line under D15 or D25 that the owner brief lists.

### R3-CONFORMANCE-2 [high] LAB_SPEC.md 2.4 check 4 and 8.2 lab.yml step 'Isolation checks 1, 3, 4'; PLATFORM_PLAN.md P2 and 10 ('Every non-Android workflow runs the git diff --exit-code check'); design 9.1 isolation rule 2

**Problem:** The only automated enforcement of AD-3's 'root Gradle build and jvmTest unchanged' is this command, run after actions/checkout: git diff --exit-code -- core server gradle settings.gradle.kts build.gradle.kts gradle.properties .github/workflows/ci.yml. It compares the working tree with the index, and a fresh checkout is always clean. So the check passes even when the PR itself edits settings.gradle.kts, core/ or ci.yml. It also runs with the default fetch-depth 1, so no base ref is available to diff against.

**Consequence:** AD-3 and the 'root build unchanged' claim in every gate entry rest on a check that cannot fail. PROGRESS.md would record an isolation pass that was never tested, which is the fabricated-evidence failure brief 12 forbids.

**Suggested change:** Use actions/checkout with fetch-depth: 0. For pull requests run git diff --exit-code origin/BASE...HEAD over the listed paths, where BASE is github.base_ref; for pushes diff github.event.before..HEAD. Add a negative-control branch that touches settings.gradle.kts and must fail. State what the check still does not cover: a root edit merged in a separate PR, and a local run before any commit.

### R3-CONFORMANCE-3 [medium] LAB_SPEC.md 2.5 (composite-build fallback); ASOM_MESH_DESIGN.md 3.6 ('If a builder chooses a composite build'), 0.2 AD-3 row

**Problem:** Both documents let an implementing builder edit the root settings.gradle.kts (the ASOM_PURE_JVM guard) and merely record it for later ratification. AD-3 decides that the root Gradle build is unchanged. LAB_SPEC R1 and PLATFORM_PLAN P2 say the root file is never edited, and isolation check 4 names that file. Design 3.6 goes further and frames the change as the builder's choice ('chooses'), not only a fallback for a failed check 2.

**Consequence:** A Sonnet builder (AD-6) can change a shipped root file without an owner ruling, contradicting a decided acting decision. The spec also contradicts its own isolation checks.

**Suggested change:** Make the fallback a stop: if check 2 fails, write BLOCKED(lab isolation: check 2 failed) with the real output and wait for an owner ruling on the guard. Delete 'chooses' from design 3.6. Keep the guard text only as the option the owner would be asked to approve.

### R3-CONFORMANCE-4 [medium] OWNER_BRIEF.md recommendation sentence 1; ASOM_MESH_DESIGN.md 0.1 table (Android row) and 2.1 'Delivered by' column; 3.1 matrix 'Holon status: full'

**Problem:** The owner brief says 'Only the Android phone is a full holon today (it serves its own apps, borrows and lends).' The design calls Android the 'full holon (the only one today)', but its own row says it borrows from M1 and lends only after v3. AD-1 made Android-as-provider a remaining v4 item after v3, and desktops lack the local-app API until M2. So under the decided order no node meets the brief's own definition of a full holon (serves its own apps, borrows and lends) until M2+.

**Consequence:** The owner brief overstates what the decided sequence delivers for directive D-B, which is the one framing the owner cares most about. It also contradicts R2-DIRECTIVES-4's fix ('claims only what the table says').

**Suggested change:** Say plainly: 'Today the Android phone only serves its own apps. From M1 it also borrows. It becomes a full holon when it lends, after v3 (AD-1). Desktops become full holons at M2 with D25(b) and D14 part B. From M1 until then, no node is a full holon; the protocol is symmetric by design.' Change the 0.1, 2.1 and 3.1 labels to match.

### R3-CONFORMANCE-5 [medium] OWNER_BRIEF.md recommendation sentence 1 ('never reaches anything you did not pair')

**Problem:** AD-2 requires the peer class to be stated truthfully: overlay traffic may be relayed, which is why it is not called lan. IC-2 says encrypted packets may cross the public internet and the overlay operator's relays. D8 records that Tailscale clients upload logs on some platforms even under Headscale. The owner brief tells the owner the mesh 'never reaches anything you did not pair'.

**Consequence:** The owner's one-page summary contradicts the truthfulness condition of the decided amendment. It is also the sentence most likely to be copied into user-facing text.

**Suggested change:** Replace it with: 'asom addresses traffic only to devices you paired. Over an overlay, the encrypted packets can pass through the overlay's coordinator and relays, and some overlay clients upload their own logs (D8).'

### R3-CONFORMANCE-6 [medium] ASOM_MESH_DESIGN.md 6.1 wording rule items 2-3, 6.2 L1 row, C-13, 10.2 D18 (a1) and sub-decision (iii); OWNER_BRIEF.md sentence 3 and D18 row

**Problem:** Directive D-C is decided. It says: adopt MLPerf Mobile's model set and metric definitions as the comparability baseline where licences permit, and write 'measured with MLPerf Mobile's model set and metric definitions'. r3 departs from it in three ways and records none of them as a departure. (1) It replaces the decided phrase with a different note. (2) It disables even that note behind a new owner trademark check. (3) It offers D18(iii), which forbids the MLPerf name altogether. Separately, C-13 finds the Llama licence permits use (with conditions), yet the recommendation (a1) makes Qwen3 the default and the MLPerf model set opt-in, so 'default runs are not comparable'.

**Consequence:** A decided directive is reopened inside an open decision without saying so. The MLCommons caution may be right, but presenting it this way makes D-C look optional.

**Suggested change:** Record it explicitly as a proposed revision of D-C, with its reasons: the F50 results-messaging rules, and the Llama licence conditions such as the Acceptable Use Policy, attribution and the gated official repositories. Otherwise keep D-C's wording and baseline as the default and present the alternatives as departures. In the owner brief, say which parts of D-C the recommendation would change.

### R3-CONFORMANCE-7 [medium] PLATFORM_PLAN.md 6 ('the benchmark on the owner's devices only (it feeds no router)'); ASOM_MESH_DESIGN.md 2.1 role B, 1.1 R3b row, 10.2 D7(a), 10.5 D24(a)

**Problem:** The recommended M1b iOS app hosts the benchmark on iPhones, which never lend. So the iPhone's results feed no router, and no peer ever needs its manifest. D-C allows differentiation only on a signed manifest that other devices or apps consume, plain text generated from the same data, and results feeding the router. It says Apple coverage is not a differentiator because MLPerf Client covers iOS. Role B is defined as 'to feed its own router', which contradicts PLATFORM_PLAN's own parenthesis. R3b is called 'partly met' on the strength of this same shell.

**Consequence:** It builds the me-too component that D-C decided against, adds scope to M1b, and leaves the brief inconsistent about what role B means.

**Suggested change:** Drop the bench shell from the iPhone build in M1b and keep the verifier and requester. Add bench on iPad at M5, when the iPad lends and its results feed routers. If the owner still wants it, name it as a departure from D-C inside D7/D24 and restate R3b's status.

### R3-CONFORMANCE-8 [medium] ASOM_MESH_DESIGN.md 8.3 CD-1 and CD-12 (Decision column 'AD-2 (the class; DECIDED)'); 0.5; OWNER_BRIEF.md count line

**Problem:** Frozen build brief 5.4 fixes 'X-Asom-Egress: local|cloud'. CD-1 adds the app-facing value peer and marks it decided by AD-2. AD-2's text decides a new egress class in Invariant 3, which is a ledger/invariant matter; it does not mention the frozen 5.4 header enum. Every other new app-facing value gets its own sign-off line (D12.1-D12.4), and the standing rule requires each frozen-contract addition to be flagged for owner sign-off.

**Consequence:** It is the one app-facing frozen-contract change with no sign-off line. Apps that treat 'not cloud' as local (I-5) would be affected without a ruling on the header itself.

**Suggested change:** Add a separate line (say D12.0: 'X-Asom-Egress value peer, frozen 5.4') ruled together with D3 and CD-DOC1. Alternatively, say explicitly in 8.2, and in the ratification text the owner signs, that AD-2's ratification includes the 5.4 header value, and repeat that in the owner brief.

### R3-CONFORMANCE-9 [medium] ASOM_MESH_DESIGN.md 8.2 IC-2 (Invariant 3(d)); RT-1 replacement text; OWNER_BRIEF.md Amendment 3 description

**Problem:** AD-2 describes Amendment 3's change to Invariant 3 as adding the egress class peer. IC-2 also changes how finely events are ledgered. Refused inbound connections are only counted, in one row per ten minutes, and TLS overhead is recorded once per session and may be estimated. Invariant 3 says 'Every network event writes a ledger row'. Neither the roadmap-13 text nor the owner brief mentions these narrowings.

**Consequence:** The declared amendment quietly relaxes 'every network event writes a ledger row', which is the kind of under-reporting AD-2 was adopted to prevent.

**Suggested change:** State both granularity rules in the RT-1 text and the owner brief as part of Amendment 3 ('refused, unauthenticated inbound connections are counted in 10-minute rows; transport overhead is per session'). Alternatively, write one row per refused connection with a stated rate cap and an overflow counter row.

### R3-CONFORMANCE-10 [low] ASOM_MESH_DESIGN.md 9.3 D-v2 and M1b entry criteria; PLATFORM_PLAN.md 1.2 ('D-v2 (needs D23, D25, D27, D28)', 'M1b (needs D14 part A, D15, D24, D28)')

**Problem:** Two decisions that the register says block a phase are missing from that phase's entry criteria. D21 (engine crash containment) is listed 'before D-v2' in 10.3 and in the owner brief, but not in D-v2's entry. D22 (Apple Team ID) blocks the iOS app on devices (AF-5) and the keychain access group that S-A12 needs, but not in M1b's entry.

**Consequence:** A builder can start the desktop engine port or the iOS app on an unruled item. The open-decision register and the phase gates disagree.

**Suggested change:** Add D21 to D-v2's entry and D22 (the Apple sub-decision) to M1b's entry, in both 9.3 and PLATFORM_PLAN 1.2.

### R3-CONFORMANCE-11 [low] ASOM_MESH_DESIGN.md 10.0d (Rejections; Build-order choices) vs 10.5 D16, 10.3 D28, 10.2 D7

**Problem:** 10.0d lists as adopted design choices ('no ruling needed'): iPhone lend-screen lending rejected, unplugged or battery lending on phones and the Deck rejected, and the desktop asom-bench CLI dropped. Yet D16 offers iPhone lending 'never / allowed', D28 offers 'The Deck on battery: (a) hard no; (b) off by default, overridable', and D7(b) offers the desktop CLI.

**Consequence:** The same items are both decided and open. The owner cannot tell which ruling binds, and builders may treat a still-open option as rejected.

**Suggested change:** Remove these three from 10.0d and leave them to D16, D28 and D7. Or keep them in 10.0d and delete the matching options from those decisions.

### R3-CONFORMANCE-12 [low] ASOM_MESH_DESIGN.md 1.1 rows R1c and R1d ('-> D2'), 1.2 C-9 ('itemised in D2'), 1.5 X13 ('D4(b1)', 'If the owner picks D4(b2), this row is reopened'), 7.8 ('third-amendment material'), 8.3 RT-6

**Problem:** Several passages still present decided items as open: r2's D2 is now AD-2 and D4 is now AD-1. With Amendment 3 declared, 'third-amendment material' now means something other than the passage intends. RT-6 quotes roadmap 7 as 'Device pairing ... per-device tokens'. That phrase is actually in Amendment 2's own bullet ('per-device tokens required'), so RT-6 can be read as rewriting Amendment 2's text, which AD-2 says stays exactly as written.

**Consequence:** Decided items read as re-litigable, and the RT-6 wording could lead an editor to change the sanctioned Amendment 2 text.

**Suggested change:** Point R1c, R1d and C-9 to AD-2, and X13 to AD-1 ('X13 stands'). In 7.8 say 'a further amendment beyond those in 8.2'. Make RT-6 name the exact sentence it edits (the Device pairing bullet: 'URL + token + cert fingerprint') and state that Amendment 2's 'per-device tokens required' is untouched and is read through IC-1 item 5.

### R3-CONFORMANCE-13 [low] PLATFORM_PLAN.md 1.1 S2-S4 and 3 DL0-DL3, 4 W1-W2, 5 MC1-MC2; ASOM_MESH_DESIGN.md 0.4 item 3, 9.1

**Problem:** The work labelled 'scaffold' and authorised by roadmap 14 item 7 to start before V1-close includes substantial D-v2 content. It covers about 6-9.5 engineer-weeks of Linux work: probes, the governor FSM, a D-Bus sleep watcher, the owner control socket server (CD-24, gated by D25 and D23) and the polkit keep-awake rule (open D28). On Windows it adds real TPM/KSP key tiers; on macOS a Secure Enclave helper. AD-4 names only the lab as 'the sanctioned exception'. 'No listener' is not squared with binding an AF_UNIX control socket. Unlike L0.4, which says it builds the D5(b) variant ahead of the ruling, these items do not say they are built ahead of rulings.

**Consequence:** It stretches 'scaffold' into early D-v2 implementation before v1 validation. That creates the sunk-cost pressure on D25 and D28 which r2's D1b split was meant to avoid, and the authorisation is not stated honestly.

**Suggested change:** Define 'scaffold' (skeleton, seam, fakes, packaging layout) and move the ControlServer and the polkit rule to D-v2. Otherwise label every now-item built ahead of an open decision the way L0.4 is, and say explicitly that 'no listener' covers network sockets only, with the control socket bound only inside tests.

### R3-CONFORMANCE-14 [low] PLATFORM_PLAN.md 7 file tree (ubuntu-touch/jvm/settings.gradle.kts 'maps ../../core/*, ../../lab/* ...'); 8 root-file note ('the only root edit, at M1'); design 9.1 isolation rule 7

**Problem:** The Ubuntu Touch jar, which ships as a click at UT-1, maps lab/* modules directly. That bypasses rule 7: promotion into shipped code happens only at the owning version's entry, under D23. Separately, PLATFORM_PLAN 8 calls include(':mesh-android', ':qr') 'the only root edit' at M1. But promoting :core:mesh (and :bench-core at v2) also requires root settings includes and an explicit jvmTest extension in the root build.gradle.kts.

**Consequence:** Lab code could ship without passing through the D23 promotion gate, and the root changes at promotion are under-counted.

**Suggested change:** From UT-1 onward, make ubuntu-touch/jvm map the promoted :core:mesh (and :bench-core), not lab/*. Restate PLATFORM_PLAN 8's note to list every root edit at promotion (the pure-JVM includes, the jvmTest extension and the Android includes), each under MOD-1/D23.

## Lens: overclaim — verdict: needs-changes

### R3-OVERCLAIM-1 [high] ASOM_MESH_DESIGN.md section 5.7 (padding residual) and section 5.11 claim-tracker row; LAB_SPEC.md section 6.6 vectors M08-017/M08-018; OWNER_BRIEF implicit via section 5.11

**Problem:** The claim tracker says padding can inflate a peer's apparent speed by 'at most bptCap / bptTrue (about 2x)'. The mechanism does not give that bound. Padding is only capped at maxTokensOffered x bptCap bytes, so the real inflation factor is (maxTokens x bptCap) / trueOutBytes. With the spec's own numbers (cap 8,192 bytes at maxTokens 1,024 in M08-018; a 1,200-byte honest answer in M08-001), the factor is about 6.8x, limited only by RATIO_CAP = 5x. The remedy for residual padding is that the owner will 'notice garbage in the answers', but that fails for padding the owner cannot see: trailing whitespace, newlines, or zero-width (Unicode Cf) characters, which all count in outBytes.

**Consequence:** A peer that is truly 3-5x slower than it claims can pad every answer invisibly, reach CORROBORATED (best quartile at or above 800) and keep full placement weight. The central 'over-claiming buys nothing once observed' promise in sections 5.7, 5.11 and 2.5 is false for any request whose max_tokens is large relative to the real answer, which is the common case.

**Suggested change:** Count outBytes over normalised text: NFC, drop Unicode Cf and control characters, collapse whitespace runs to one space, and trim. Add a requester-side length check: discard (with a strike) any answer longer than k times the requester's own per-(app, model) output-length EWMA. State the residual honestly as min(RATIO_CAP/1000, maxTokens x bptCap / trueBytes). Add M08 vectors for (a) trailing-whitespace padding, (b) zero-width padding, and (c) a short real answer under a large max_tokens. Rewrite the section 5.11 row and M08-017's expected text to match.

### R3-OVERCLAIM-2 [high] ASOM_MESH_DESIGN.md section 7.1 table and bullets, section 0.8; OWNER_BRIEF.md sentence 2; assumption A12a

**Problem:** Every lender row is derived bottom-up from peak memory bandwidth and FLOPS (Deck decode at 55-70% of peak, explicitly 'not sustained'). The phone baseline instead uses the roadmap's throttled planning figure (5 tok/s decode, 30 tok/s prefill). The two bases are not comparable. Applying the Deck's own method to a flagship phone (an ASSUMPTION: LPDDR5X at about 77-85 GB/s over a 5.03 GB file) gives about 8.4-11.8 tok/s decode. The phone's total time then drops from 76.5 s to 42-52 s, and the Deck's total-time ratio becomes about 0.8-1.9x instead of 1.5-2.8x. The M4 Pro ratio drops to about 5-6x. The owner brief also says the two Deck bases 'disagree by 3-5x'; the ranges (24.4-39.1 vs 80-120 tok/s) actually differ by about 2-5x. Separately, under basis LO the Deck's TTFT for a 500-token prompt (12.8-20.5 s) already sits at the router's peer usability gate (TTFT at most 20 s), so under auto it drops behind cloud for any longer prompt.

**Consequence:** The owner is asked to rule D28 (the Deck as an M1 lender) on a headline that assumes the phone is at its worst while the Deck is at its best. The brief says 'Phone to a CPU-only desktop may be no faster', but hides that the Deck may be no faster in total time either.

**Suggested change:** Add a phone row derived with the same method as the lenders (peak-fraction decode, plus an NPU or GPU prefill basis citing F40), and show every ratio as an envelope over both phone bases. Change the owner brief to 'Deck: about 0.8-2.8x total time, possibly no faster', and correct '3-5x' to 'about 2-5x'. State that under basis LO the Deck fails the auto usability gate for prompts above about 500 tokens. Keep the rule that the D-v2 measurement replaces all of this.

### R3-OVERCLAIM-3 [high] LAB_SPEC.md sections 7.6 and 7.7 (L-L15, overheadBytes definition); ASOM_MESH_DESIGN.md section 8.4 byte accounting and law L-L15; PLATFORM_PLAN.md section 6 gate I3 (TransferAccounting); platforms/ios.md IA07

**Problem:** Two gates are true by construction. (1) On JSSE, overheadBytes is defined as 'network bytes minus the application bytes of the frames', and L-L15 then checks that sum(row application bytes) + overheadBytes equals the network bytes a tap saw. If the implementation derives overhead from the rows, the equality holds whatever the rows say. (2) On Network.framework, overheadBytes is defined as DataTransferReport transport bytes minus sum(application bytes). Gate I3 then asserts sum(frame rows) + overheadBytes == DataTransferReport.sentTransportByteCount, which is algebraically always true. Also, the basis on Network.framework is labelled MEASURED even though what that counter includes (TLS records only, versus TCP/IP headers or retransmits) is assumption IA07, still unverified.

**Consequence:** Gates L0.5, W6, MC6 and I3 can pass while the rows under- or over-count application bytes. That defeats the purpose of IC-2 ('rows record the exact application bytes'), and it labels an assumption as a measurement.

**Suggested change:** Compute the two quantities independently. Add a plaintext tap at the frame codec that counts 9 + payload per frame; its total must equal the sum of row bytes (checks the rows). Separately require overheadBytes to fall within the RFC 8446 bound: records x 22 plus the handshake bytes, within tolerance (checks the overhead). In I3, compare the sum of rows with DataTransferReport.sentApplicationByteCount, which is independent of the rows, and not with a quantity defined from them. Label Network.framework overhead ESTIMATED until the IA07 packet-capture spike passes.

### R3-OVERCLAIM-4 [medium] ASOM_MESH_DESIGN.md section 3.2 item 4, section 7.4 LP-0 PF exception, section 2.1 PF row (Deck), section 3.4.2 'no game running'; LAB_SPEC.md section 6.5; PLATFORM_PLAN.md section 3 (Deck roles, DV-D9); platforms/linux.md sections 3.3-3.4

**Problem:** The Linux host's presence inputs are exactly two contention rows. linux.md section 3.4 says the node does not read keyboard or mouse idle time or the foreground window. Three consequences follow. (a) The Deck's PF presence rule ('its terminal losing focus', LP-0, W07p) has no specified mechanism, so it cannot be implemented as written. (b) 'A game starts, then DRAINING with 2 s grace' and DV-D9's 'game-start drain within 2 s' actually require over 400 permille of other-process GPU or CPU load for 10 s before the 2 s grace, and GPU accounting on SteamOS is unverified (LA04). A light game never trips the rule, which contradicts 'lends only with no game running' and 'games always win'. (c) Mac and Windows lend only after 10 minutes idle or when locked, while Linux lends while someone is at the keyboard. Section 3.1, section 3.2 and the user copy present one presence rule.

**Consequence:** On the Deck, a game and inference share the GPU for 12 s or longer, or indefinitely for light games. The Deck PF role and DV-D9 cannot pass as specified, and users get different 'yield to me' behaviour per OS without being told.

**Suggested change:** For the Deck, specify game detection by a named mechanism (for example Steam's reaper or SteamLaunch process, or gamescope's focused-app property) behind a new spike, or restate the claim as 'drains within about 12 s of heavy GPU/CPU contention; light games may run alongside lending'. Specify PF focus loss by terminal focus reporting (DECSET 1004 in Konsole) with a spike, or drop Deck PF from M1. Add a column to section 3.1 listing the presence inputs each host actually observes, and make the user copy per OS.

### R3-OVERCLAIM-5 [medium] OWNER_BRIEF.md recommendation sentence 1; ASOM_MESH_DESIGN.md section 0.1 table (Android row) and section 3.4.1

**Problem:** 'Only the Android phone is a full holon today (it serves its own apps, borrows and lends)' contradicts the brief's own definition and schedule. Today no node borrows or lends. In M1 through v3 Android borrows but does not lend, because lending is M2+, after v3 (D16). So by the brief's own definition Android is not a full holon at any time before M2+, the same phase in which desktops become full holons.

**Consequence:** This overstates the delivered holonic property to the owner, the exact finding R2-DIRECTIVES-4 asked to fix. The owner may think the mesh's symmetry is already delivered on the phone.

**Suggested change:** Replace with: 'No node is a full holon before M2+. Android becomes one when its lend modes ship after v3 (D16); desktops when their local-app API ships (D25(b)). Until then Android serves its own apps and borrows.' Change the section 0.1 cell from 'full holon (the only one today)' to 'full holon from M2+ (borrow-only mesh member M1-v3)'.

### R3-OVERCLAIM-6 [medium] LAB_SPEC.md section 7.4 ('no resumption ... the server issues no tickets'), W08 expectations; ASOM_MESH_DESIGN.md section 4.2 T2 and section 4.4, section 3.3 C12

**Problem:** The lab spec requires JSSE servers to issue no session tickets and W08 to show a client CertificateVerify in every session, including against hostile clients that attempt resumption. The design's T2 says JSSE 17 server-side tickets are a process-wide property, so server resumption is 'tolerated', and C12 forbids process-wide JSSE system properties. A builder on the JDK 17 lane cannot satisfy both. The expected result of W08's 'resumption attempts' vector in the server role is unspecified.

**Consequence:** L0.5 either blocks with BLOCKED(spec conflict) or passes by setting a JVM-wide system property that violates C12. Section 4.4's concession (a modified client with a PSK defeats T9 against JSSE 17) and the lab's claim contradict each other.

**Suggested change:** Make S-A9 decide, per JDK, whether ticket issuance or acceptance can be disabled per SSLContext (for example through the server SSLSessionContext settings or invalidating sessions after the handshake), and record the result. State W08's server-role expectation per lane (JDK 17: resumption accepted, recorded as a known gap; JDK 21 or the packaged runtime: refused, if S-A9 shows a per-context control). Because every desktop lender ships a jlinked Temurin 21, say which runtime the lender security claims rest on.

### R3-OVERCLAIM-7 [medium] ASOM_MESH_DESIGN.md section 3.5 oracle rule; LAB_SPEC.md section 4.10; PLATFORM_PLAN.md section 6 I0 gate and section 12 agent table; platforms/ios.md IS1

**Problem:** Clearing the self-oracled tag requires an author 'with no access to the generator source', recorded in PROGRESS.md. In this program every agent works in the same repository, where lab/ sources are present once L0.2 exists (the Apple track starts after L0.2). The only evidence of independence is a self-declaration. The Swift code already written (IS1) explicitly 'mirrors manifest-vectors/VerifyDsse.java exactly', and the brief says to reuse earlier artefacts. So the natural independent oracle is already derived from generator-side code, and the gate can be cleared without independence being true.

**Consequence:** Vectors can be promoted as independently confirmed while carrying common-mode errors: exactly the failure R2-OVERCLAIM-8 was meant to stop.

**Suggested change:** Define independence by a mechanism: the Swift lane is written in a sparse checkout containing only the spec prose (LAB_SPEC section 4 and the cited sibling sections) plus lab/conformance data. Record the checkout command, the sparse paths and the agent's tool-access log in PROGRESS.md, and have the owner confirm. State that IS1 and FM42 code, and anything derived from them, can never clear the tag.

### R3-OVERCLAIM-8 [medium] ASOM_MESH_DESIGN.md section 3.1 Windows row and section 3.4.3; PLATFORM_PLAN.md section 4 roles; platforms/windows.md role table vs lifecycle table

**Problem:** 'PA on AC desktops' for Windows is stronger than the mechanism on Modern Standby desktops. The Windows section says both that 'a held power request keeps a Modern Standby machine in the NoCS phase through idle display-off' and that 'display-off on Modern Standby machines' is a hard os_sleep_imminent drain. Also, the user-mode node is a desktop application, which the Desktop Activity Moderator suspends, and user-mode presence requires at least 10 minutes of input idle before SERVING. With a display timeout of about 10 minutes, the lending window on such a machine is roughly empty.

**Consequence:** D28 may place a Windows desktop as a lender that in practice never serves in its default (user) mode, and the M1 gate 13 idle test would only show failover.

**Suggested change:** Pick one rule: drain on display-off, or serve through NoCS on AC in service mode only with PowerRequestSystemRequired. State in section 3.1 that PA on Modern Standby desktops is service-mode only (or needs a display timeout above the idle threshold, which asom never sets). Add a W8 device item that measures the actual lending window on the owner's hardware.

### R3-OVERCLAIM-9 [medium] PLATFORM_PLAN.md section 7 (ut-arm64-smoke 'Proves', UT0.3); ASOM_MESH_DESIGN.md section 3.1 Ubuntu Touch 'CI proves'; platforms/ubuntu-touch.md UT0.3

**Problem:** The arm64 smoke job is described as proving the runtime starts 'in the real UT 24.04 userland' and 'the real arm64 Ubuntu Touch userland image'. It runs in clickable/arm64-ut24.04-1.x-arm64, a Clickable development image. The verified fact UF09 establishes only that this image exists for linux/arm64. It does not establish that its contents match a device rootfs: Lomiri, device-specific libraries, the shipped AppArmor abstractions and 2.x packages may all differ. I could not confirm the image's base.

**Consequence:** A pass is recorded as evidence about the Ubuntu Touch userland when it shows at most glibc and ABI compatibility with a noble-based SDK image. S-UT1 risk looks retired earlier than it is.

**Suggested change:** Rename the claim to 'starts in the Clickable UT 24.04-1.x arm64 SDK image (noble-based; an ASSUMPTION about its closeness to the device rootfs)'. Record the image digest and its FROM chain in PROGRESS.md. Keep 'runs in the UT userland' for DV-UT01/S-UT1 only.

### R3-OVERCLAIM-10 [low] ASOM_MESH_DESIGN.md section 3.2 SteamOS keep-awake row [LF05]; platforms/linux.md LF05; section 8.4 overheadBasis on Network.framework [IA07]; D8 disclosure and android-mesh AF21

**Problem:** Several labels are stronger than their sources. (a) LF05 is cited as showing that a block lock causes Game Mode fake sleep, but issue 1615 does not say whether the lock was block or delay mode; 'a delay lock avoids fake sleep' is an inference (DV-D4 tests it). (b) Network.framework overhead is labelled MEASURED while resting on assumption IA07. (c) The Android Tailscale 'Remote client logging' switch (AF21, verified only in code) is the basis of D8's disclosure, but tailscale issue 21088 (2026-09-03, v1.102.3) reports log.tailscale.com lookups continuing after opt-out on 2 of 3 devices.

**Consequence:** Normative rules and user-facing disclosures rest on facts presented as verified that are partly inference.

**Suggested change:** Split LF05 into the verified fact ('a systemd-inhibit sleep lock of unstated mode causes fake sleep in Game Mode') and assumption LA-n ('a delay lock does not'), settled by DV-D4. Label Network.framework overhead ESTIMATED until the IA07 spike passes. Add the tailscale 21088 report to AF21 and word the D8 disclosure as 'the switch is not verified to stop all log traffic', with DV-A8 as a packet-capture gate.

### R3-OVERCLAIM-11 [low] PLATFORM_PLAN.md section 3 DL6 ('0 packets captured on port 11436 over 3 min')

**Problem:** The CI desktop quiescence gate has no positive control and captures only port 11436. A capture on the wrong network namespace or interface, or a peer listening on a non-default port, passes vacuously. M1 gate 5 has a positive control; DL6 does not.

**Consequence:** A CI quiescence pass can be recorded without showing that the capture would have seen a violation.

**Suggested change:** Capture all TCP SYNs to every paired-peer address on any port, in the node's namespace. Add the M1 gate-5 positive control (open a peer-status view or run asom watch, then require a DIAL row and an observed SYN within 5 s), and fail the gate if the positive control is not seen.

### R3-OVERCLAIM-12 [low] ASOM_MESH_DESIGN.md section 4.4 (stolen devices) and section 3.1 Ubuntu Touch row

**Problem:** Section 4.4 says a stolen running device's access is 'bounded only by T9's keyguard binding on phones and tablets'. The Ubuntu Touch phone holds a T0 file key with no keyguard binding: only behavioural rules (L-UT1, L-UT3) apply, and without fscrypt a powered-off phone yields the key from flash. The platform section says so; the brief's general statement does not.

**Consequence:** The brief overstates stolen-phone protection for one in-scope phone platform.

**Suggested change:** Add to section 4.4 and the section 3.1 Ubuntu Touch row: 'Ubuntu Touch: no key-level lock binding; behavioural limits L-UT1/L-UT3 only; a stolen phone keeps its borrow scope until revoked on each lender; key at rest protected only if fscrypt is enabled'.

### R3-OVERCLAIM-13 [low] ASOM_MESH_DESIGN.md section 0.5 vs section 8.3 count line and OWNER_BRIEF count; section 9.3 M1 scope; PLATFORM_PLAN.md section 11 Linux estimate

**Problem:** There are three small count inconsistencies. (1) Section 8.3 and the owner brief say '6 new app-facing values', but section 0.5 says 'the five new app-facing values' (D12.1-D12.5, and D12.5 is a documentation change, not a value). (2) The M1 scope lists CD-1, CD-1m, CD-2 and CD-4 but omits CD-6b and CD-FO, which the registry phases at M1. (3) The Linux estimate of 12-19 does not equal the sum of its gate estimates (11.5-18.5).

**Consequence:** The 'complete count' the owner is asked to rely on disagrees with itself in one place, and an implementer reading section 9.3 may leave out two M1 values.

**Suggested change:** Enumerate the six values once (served-by form, owned_by, MESH_STREAM_INTERRUPTED, peer-unavailable, peer-lost, and the sixth), and make section 0.5 say 'six values in five sign-offs, plus one documentation change'. Add CD-6b and CD-FO to the M1 scope. Make the Linux total the sum of its rows.

## Lens: closure — verdict: needs-changes

### R3-CLOSURE-1 [high] LAB_SPEC s4.7 signPresentation and projectFile; s4.6 steps 12 and 15a; s4.1 patches P6-P8; benchmark.md s13.2 (the asom.bench/1 schema); design s5.2 and s5.8

**Problem:** The FILE-audience manifest is under-specified in three load-bearing ways. (a) body = projectFile(bodyOwn) keeps bodyOwn.subject, which holds the NIK nodeId and a hardware keyStorage value. The file is signed by a fresh per-export key, and in FILE mode step 12 requires subject.nodeId == nodeId(K), where K is the per-export key. No text says projectFile rewrites subject to the export key's nodeId and keyStorage 'ephemeral'. (b) projectFile truncates results[].measuredAtMs to the day and drops the batteryStartPermille, screenOn and socStartMilliC conditions. body.bench (tier startedAtMs, run.batteryStartPermille, run.screenOn) is left untouched. Step 15a requires JCS(results) == JCS(project(derive(bench))), and project() rebuilds exact measuredAtMs and the dropped conditions from bench (benchmark.md s13.3 mapping). So every file export fails 15a. (c) P6 says body.bench is an asom.bench/1 document 'per benchmark.md s13.2'. That schema REQUIRES field, custom, derived and render, which P7 forbids at any depth. Its device.platform and harness.shell enums lack windows and ubuntu-touch. No patch list exists for the bench schema.

**Consequence:** M02-110..112, M03-129, M05-103/104 and M06-file-001..004 cannot be generated consistently. The signer's own self-check rejects every export with SUBJECT_KEY_MISMATCH or DERIVATION_MISMATCH. A builder must guess among three paths. Signing with the NIK violates s5.3. Rewriting subject is unstated. Making 15a audience-aware is unstated. Keeping the NIK nodeId in a file would also make every export linkable, which the per-export-key design exists to prevent. The Swift lane would diverge from the JVM lane.

**Suggested change:** Define projectFile completely. Set subject = {nodeId: nodeId(exportKey), keyAlg: ES256, keyStorage: ephemeral}. Define a bench projection for the file audience: exactly which bench fields are truncated, dropped or kept, including run.startedAtMs, endedAtMs, dayUtc, batteryStartPermille, screenOn, tier startedAtMs, device.osBuild and gpuDriver. State 15a for FILE as results == projectResultsFile(derive(benchFile)), and give the audience-aware projection table. Add patches B1..Bn to asom.bench/1: drop field, custom, derived and render from required and forbid them; extend the platform and shell enums. Add one worked file-audience vector (own body, then file body, then verify ok) with its bytes.

### R3-CLOSURE-2 [high] LAB_SPEC header ('everything a builder needs is here, or in a sibling spec'), rule R1, s4.1; design s3.6 docs/design/mesh file list; PLATFORM_PLAN s0 rule P1

**Problem:** LAB_SPEC is not self-contained. It cites the r0 siblings about 40 times for normative content. From manifest.md: the M03-101..128 meanings (s16.2), consistency() (s8.4), the reject codes (s8.3), the renderer header and number formats (s14.2), the public derivative (s12). From benchmark.md: the M04 statistics (s9), run plans (s5), governors (s11), the text layout and editorial constants (s12), the bench schema and projection (s13), Q1 pins (s4.2). From trust.md: the QR grammar (s4.2), PAIR_* messages (s4.4), proof and SAS (s4.5), certificate templates (s2.4), verifyPeerChain and the TLS profile (s3.2), the lender decision table (s7.3), registry laws (s15). From router.md: the failover rows (s8.2) and cap semantics (s5.5). From contract.md: laws L-L1..L-L12 (s4.12) and the decision table (s5.2). It also has about 18 'as amended by design sX' references, so the 45k-word brief is still required. Design s3.6 lists docs/design/mesh as containing only the brief, reviews, LAB_SPEC, PLATFORM_PLAN and the directives. The siblings, platforms/*.md, manifest-vectors, router-examples and bench-examples are not listed. LAB_SPEC s4.1 assumes manifest-vectors is committed under docs/design/mesh and BLOCKs otherwise, yet R1 forbids the builder to write anywhere outside lab/, and no track in PLATFORM_PLAN owns docs/.

**Consequence:** A builder working in the repo cannot implement L0.2, L0.3, L0.5 or L0.6 without files that nobody is assigned to commit. By its own rule, L0.2 stops at BLOCKED(missing manifest-vectors). The claim 'implementable without the 45k-word brief' (AD-6, design s0.2) is false. Each sibling is also only correct 'as amended', so a builder reading a sibling alone implements superseded r0 rules, for example TOFU, KEY_CHANGED or the removed live-state fields.

**Suggested change:** Add a commit manifest to LAB_SPEC s0. List every file under docs/design/mesh/ (the siblings, platforms/*.md, manifest-vectors/, router-examples/, bench-examples/, conformance-examples/). Name who commits it and when: a docs-only PR before L0.1, and allow it in R1. For each cited sibling section, either inline the table LAB_SPEC depends on, or add an amendment index listing every r1-r3 change to that section. Retract 'self-contained' until this is done.

### R3-CLOSURE-3 [high] LAB_SPEC s3.6 W01b law ('row.toEchoHeaders() equals the response's X-Asom-* headers exactly') and vectors W01b-002, -003, -007; repo server/src/main/kotlin/xyz/mdhv/asom/server/AsomServer.kt lines 210-221 and 253-263

**Problem:** On streaming requests, the frozen v1 server commits the echo headers from a separate commitView RouteRecord that has no cost. The comment there says s5.4 allows this. The server then appends a different, finalized record that carries a usage or heuristic cost and the terminal status. So for W01b-002 (usage), W01b-003 (heuristic) and W01b-007 (failMidStream), row.toEchoHeaders() contains X-Asom-Cost-Est and X-Asom-Cost-Basis, while the HTTP response contains neither. The law LAB_SPEC calls normative for every W01b vector is false for today's v1 on 3 of its 8 vectors.

**Consequence:** L0.1 cannot pass as written, and it is the first work item and the one the owner brief names as the next step. Against the real :server, the builder will either BLOCK or quietly weaken the law. R8 forbids the second, and 'record a v1 difference as a finding, never as a vector edit' (s3.5) does not cover a wrong law. The spec also never says whether this v1 behaviour counts as an Invariant 9 deviation to report to the owner.

**Suggested change:** Restate the W01b law as follows. Served-By and Egress are always equal. On non-stream responses the full header map equals toEchoHeaders(row). On stream responses the response carries no cost headers (headers commit before the body, s5.4), and the row's cost fields are checked separately. Add a vector pinning that difference. Record it in PROGRESS.md as an observed v1 property for the owner, and make the lab's LabRouteRecord projection model the commit-time view explicitly.

### R3-CLOSURE-4 [medium] LAB_SPEC s1.2 (:manifest depends on :bench-core), s8.1 L0.2 gate; design s9.1 order L0.2 before L0.3; PLATFORM_PLAN s1.1 S5 ('after L0.2's vectors exist')

**Problem:** Verifier step 15a needs derive(bench) (M04) and the results projection, both from :bench-core, which is work item L0.3. Every M02 accept vector needs a body.bench whose derived results match. M03-130 and M03-131 test derivation. The L0.2 gate still requires M01-M03, M05 and M06 green, with every id of s4.9 present, before L0.3 exists. The Apple lane is told to start once L0.2's vectors exist.

**Consequence:** A builder following the numbered order cannot generate or pass the L0.2 vectors. It will either stub derive(), producing vectors that later change and forcing the Swift lane to redo its work, or reorder the items on its own initiative. That is a guess the spec should have made.

**Suggested change:** Split L0.2 into L0.2a and L0.2b. L0.2a: :json, JCS, DSSE/ES256, key formats, and the signature-layer M01/M02/M03 vectors. L0.2b: the full verifier with 15a, M05, M06 and the file vectors, gated after L0.3's derive/project and M04. Update design s9.1 and PLATFORM_PLAN S5 to start the Swift lane after L0.2a.

### R3-CLOSURE-5 [medium] LAB_SPEC s6.1 types, s6.3 filters, s6.4 estimator and parameters, s6.6 tracker, s6.7 cap, s4.6 VerifyContext

**Problem:** A builder would have to invent these constants, inputs and types:
(1) classCeiling(model, class) has no values. It is the only binding capRef term until the owner's reference key exists, because RT-12 fails closed.
(2) confFloor and knownBadConf have no values, yet M03-131 needs a floor.
(3) batteryDesignMilliWh for a PEER on battery is needed by E12/S2. It is not on the wire and has no class default.
(4) localQueueMs for SELF is used in E3 but is not a SelfSituation field.
(5) The 'node max context' used by F5 and the 'request's embedding identity' used by F16 are in neither NodeView nor MeshQuery.
(6) File 'kind' for virtual selectors is not a FileKey field.
(7) TrackerState, CapCounter, FrozenCloudView, Estimate, ScoreBreakdown, Exclusion and CapDelta are each used once and never defined.
(8) The 'brand-new peer' 1-in-10 cap is undefined.
(9) The tracker key is k = (peerNodeId, fileSha256), but MeshSnapshot.tracker is keyed by ClaimKey(nodeId, fileSha256, backend).
(10) It is unclear which decodeAt point is 'claim decode', and whether effRatio scales every decodeAt point.
(11) The tracker's predicted time uses the requester's full E1, which includes the cold-handshake term, although tBody is taken after the handshake. That inflates the ratio in the peer's favour.

**Consequence:** Two builders, or the JVM and a future second lane, will produce different plans and tracker states from the same snapshot. R01-R03 and M08 expected values cannot be hand-computed without these choices, and the L0.6 gate turns into agreement with the builder's own guesses.

**Suggested change:** Add a constants table (PROVISIONAL) for classCeiling, confFloor, knownBadConf, a batteryDesignMilliWh class default and the node max context. Add the missing fields to SelfSituation, PeerLimits, FileKey and MeshQuery. Give Kotlin definitions for every type named in s6.1. Fix the tracker key to one tuple. Define 'claim decode' as decodeAt at contextTokens = P, and say all curve points are scaled. Make the tracker's E1 the warm-path value (rtt plus transfer only).

### R3-CLOSURE-6 [medium] LAB_SPEC s7.5 'Session rows' rule, s7.6 table (SESSION open 'inbound connection authenticated', DIAL rows), L-L15; design s8.4

**Problem:** L-L15 is stated per session ('per session, sum of row application bytes plus overheadBytes equals the tap's count'), but the rows cannot be grouped by session. First, attempt rows put the attempt id in attemptId, and no column links an attempt to the session that carried it. Second, the listener writes SESSION open at TLS authentication, before HELLO.sessionNonce (the session id) has arrived. Third, the dialer's DIAL intent and outcome rows carry the handshake overhead, but the spec never says which id they carry or whether sessionNonce exists before the SYN.

**Consequence:** The builder cannot compute L-L15 from ledger rows alone. It will either add an extra column, which breaks the fixed count of 20 mesh columns, or use an undocumented in-memory map in the test. The law then no longer checks what the rows can reconstruct, and the join tool promised in design s8.4 cannot rebuild per-session byte accounts.

**Suggested change:** State three rules. The dialer generates sessionNonce before the DIAL intent and writes it in the DIAL rows' attemptId. The listener writes SESSION open after HELLO arrives, still before any reply, carrying the handshake overhead. Attempt rows name their session in an existing field; for example, specify a fixed routeDetail prefix 'session=<id>', or state that L-L15 is checked per connection through the test tap. Add an L02 vector covering one session with two attempts.

### R3-CLOSURE-7 [medium] LAB_SPEC s7.4 TLS profile ('no resumption (a fresh SSLContext per dial, and the server issues no tickets)') and rule C12 (no JVM system properties)

**Problem:** The spec requires a JSSE server that issues no TLS 1.3 session tickets, and it gives no mechanism. Observed locally in this container, with no source URL, on OpenJDK 21.0.10 (JDK 17 not tested): a TLS 1.3 SSLServerSocket produced a NewSessionTicket by default. It still did so after setSessionCacheSize(1) and setSessionTimeout(1) on the server session context, and with -Djdk.tls.server.newSessionTicketCount=0. C12 forbids JVM-wide system properties in any case.

**Consequence:** A builder cannot meet the requirement with scoped settings, and may reach for a global property that C12 bans. What W08 actually checks (no pre_shared_key in any ClientHello) is already met by a fresh client SSLContext per dial. The server-side sentence is therefore an unimplementable over-specification. The tickets it would forbid do exist on the wire, and they count toward transport overhead.

**Suggested change:** Replace the sentence with this. The server may send tickets; clients never resume, because each dial uses a fresh context; W08 asserts no pre_shared_key and a full handshake with a client CertificateVerify in every session. Add 'can JSSE suppress TLS 1.3 tickets per SSLContext on JDK 17 and 21' as a row of the S-A9 matrix, recorded as UNVERIFIED until run.

### R3-CLOSURE-8 [medium] Design s9.3 entry criteria (v2, D-v2, M1b rows); s8.3 CD-DOC1; s10.3 D21 and D22 'Blocks' lines; s10.3 D25 'Blocks'; OWNER_BRIEF decision table

**Problem:** This is the R2-CONFORMANCE-3/7 class of defect in smaller form. Four decisions that block a phase are missing from that phase's normative entry row. (1) D21 (desktop engine crash containment) sits before D-v2 in the owner brief but not in the D-v2 entry criteria. (2) D22 says it blocks 'the iOS app on devices' and any signed Windows or macOS release; AF-3, AF-4 and AF-5 are phased at D-v2/M1b/M4 with D22. D22 is still absent from the v2, D-v2 and M1b entries. (3) D25 says it blocks M1b (the Ubuntu Touch self-UI), and CD-19's self-ui: form lands at M1b, yet M1b's entry lists only D14 part A, D15 and D24. (4) CD-DOC1 is phased 'M1 (may land earlier)' and ruled by D3, while D3 first appears at M1's entry. That is the early-landing path R2-CONFORMANCE-7 removed for CD-1m.

**Consequence:** A later session following the s9.3 table can start D-v2 or M1b, or change CLIENT_API.md's egress semantics, before the ruling that governs it. D23's 'unruled means unapproved' rule catches only registry items, not phase starts.

**Suggested change:** Add D21 to D-v2's entry. Add D22 to D-v2's and M1b's entries, or state that D22 gates only release signing, not phase entry. Add D25 to M1b's entry. Re-phase CD-DOC1 to 'M1 only'. Then add a single check sentence: each decision's 'Blocks' line equals the set of entry rows it appears in.

### R3-CLOSURE-9 [low] Repo ASOM_ROADMAP_BRIEF.md s14 item 3; design s0.1 holon table; s8.3 RT rows

**Problem:** R2-DIRECTIVES-4 is closed in the brief but not in the committed roadmap. Roadmap s14 item 3, which is the session's wording, still says 'Every node is whole on its own ... it serves its own apps alone (with the mesh off it behaves exactly as v2)'. r3 s0.1 withdrew that universal claim: the iPhone, Ubuntu Touch and D26 nodes are borrow-only, and desktops are partial until M2. No RT row corrects the roadmap text, and the 14-row RT count does not include such a change.

**Consequence:** The binding roadmap log, which later sessions must follow, repeats the overclaim the round-2 reviewers asked to remove. A later session could treat the universal holon claim as met.

**Suggested change:** Add RT-15, ruled by D-B/AD-5 as a text correction: amend roadmap s14 item 3 to the s0.1 wording (a full holon today only on the Android phone; which nodes are partial). Update the count line in s8.3 and the owner brief together.

### R3-CLOSURE-10 [low] OWNER_BRIEF D12 row ('five new app-facing values'); design s10.4 D12 header; s8.3 count ('6 new app-facing values')

**Problem:** D12.1-D12.5 are called 'five new app-facing values', but they are not five values. D12.1-D12.4 cover five values: CD-2, CD-4, CD-6b and the two CD-FO values. D12.4 also carries v2.5's CD-RR values, which the count lists but does not count. D12.5 is a CLIENT_API.md documentation change, not a value. The count line's '6 app-facing values' includes 'peer', decided under AD-2.

**Consequence:** This is the same small counting inconsistency the round-2 reviewers flagged in the count line. The owner may approve D12.4 without seeing that it also pre-approves the v2.5 route-reason values.

**Suggested change:** Reword as 'five sign-off lines: 5 values (D12.1-D12.4; D12.4 carries two, plus the v2.5 CD-RR values, which are listed but not counted) and 1 SDK-doc change (D12.5)'. Consider splitting CD-RR into its own line, D12.4b.

### R3-CLOSURE-11 [low] LAB_SPEC s8.1 L0.1 expected output; s3.3 runner; s3.5 W01-100..299; s3.10 xcheck; s8.2 lab.yml

**Problem:** The gate texts contain small mismatches a builder must resolve by guessing:
(1) L0.1 expects 'family W00: 7 vectors, 7 pass', but W00-100 onward (proposed) sits in the same family, and the runner's format counts proposed-skipped vectors.
(2) The L0.1 command pipes the 'lines' mode, which prints '<id> ok' and no family lines.
(3) xcheck at L0.1 is asked about W01, but its behaviour for families not yet present (M01, W05) is unspecified.
(4) The generator for W01-100..299 has no stated distribution (uniform or log-uniform) or RNG.
(5) :conformance-runner:run needs the application plugin and a mainClass, neither named.
(6) lab.yml says actions are 'pinned by commit SHA in the real file' but gives no SHAs.

**Consequence:** A builder cannot tell whether a real run matches the expected column. R8 treats any mismatch as a failed gate, which invites either spurious BLOCKED entries or quiet edits to the expected text.

**Suggested change:** Make the expected lines exact: 'family W00: <7+k> vectors, 7 pass, 0 fail, k proposed-skipped'. Say where the family lines come from (labTest). Specify that xcheck prints 'xcheck <F>: absent' and exits 0 for families not yet present. Fix W01's generator to SplittableRandom(1) with log-uniform exponents. Name the runner's mainClass. Either list the action SHAs or tell the builder to resolve them and record them in PROGRESS.md.

### R3-CLOSURE-12 [low] LAB_SPEC s4.10 and R9; design s3.5; PLATFORM_PLAN s1.1 S5

**Problem:** Independence is defined as 'an author with no access to the generator source'. But the generator source is committed in the same repo (lab/tools/gen_vectors.kts or runner fixtures), and the Swift-lane agent works in that repo. PLATFORM_PLAN starts it after L0.2's vectors exist and calls it 'the natural independent oracle'. No mechanism makes the no-access condition true or recordable.

**Consequence:** The tag can be cleared by assertion: 'recorded in PROGRESS.md' becomes a self-report, which is the R2-OVERCLAIM-8 failure in a new place.

**Suggested change:** Define the access boundary operationally. The independent author's session receives only LAB_SPEC, the committed sibling specs and the vector JSON files (a sparse checkout without lab/tools and the lab sources). PROGRESS.md records the session id and the exact file list it was given. Otherwise, label cross-language agreement 'cross-lane', a weaker tag than 'independent'.
