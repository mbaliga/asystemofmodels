# Final-review findings on ASOM_MESH_DESIGN.md (round 1)

## Lens: conformance — verdict: needs-changes

### CONFORMANCE-1 [high] OWNER_BRIEF.md sentence 3: 'Keep the frozen v1 contract intact. The mesh adds only three header/model values, one in-band stream error value and new ledger columns.' Also D12(a): 'mesh-1's app-facing change is three values plus one SSE error value'

**Problem:** The owner-facing summary understates the change to frozen surface. Items it leaves out: (1) the new section 5.6 error code LEDGER_UNAVAILABLE (CD-LU, 503). It lands in v1.1, whose locked contract delta is 'QUOTA_EXHAUSTED only', and it changes shipped v1 behaviour, because a ledger failure now blocks cloud requests. (2) The two X-Asom-Failover values. (3) The change to frozen section 5.9 body handling: the router reads max_tokens and a normaliser rewrites bodies (D11). (4) A fourth InferenceClient implementation, RemoteMesh, which changes the 10A resolver order, and the change of the egress set in docs/CLIENT_API.md to an open set. That doc is pinned as contract in brief P6. (5) A new owner control channel over a Unix socket (CD-24). (6) An entire new peer protocol and listener. The one-pager also omits D5, D7 and D11, each of which rewrites frozen text.

**Consequence:** The owner could grant D1/D2 believing the v1 contract and the v1.1 locked delta stay untouched. This is the overclaim that brief section 12 forbids, made in the document the owner is most likely to read.

**Suggested change:** Replace sentence 3 with an accurate count, grouped by surface: app-facing values, a new error code in v1.1, the section 5.9 wording, the 10A tier and CLIENT_API.md, the peer plane, and ledger columns. Add D5, D7 and D11 to the decision table as 'changes frozen text', each with a one-line consequence.

### CONFORMANCE-2 [high] Section 8.3 'Contract delta registry (final; every item needs SIGN-OFF)' vs section 10 decision register ('the owner can skim straight to it')

**Problem:** Several SIGN-OFF items map to no owner decision in section 10: CD-2 (the Served-By form peer:alias/model), CD-4 (owned_by asom-peer in /v1/models), CD-24 (Unix-socket control channel), CD-26 (catalogue benchSets[], which changes the roadmap section 9 v2 field list), CD-C (conformance/ as a normative published artefact), CD-D (the new dependencies swift-certificates, swift-asn1 and zxing-core), CD-23 (payload type and renderer ids), B26/CB1 (JNI additions to the EXECUTION-GRADE v2 P1 surface), and the section 3.5 module promotions that amend the brief section 4 table. The promotion list also leaves out :bench-app and :bench-cli, which benchmark.md CB3 creates.

**Consequence:** Sign-offs are buried in body tables that the owner is told they can skip. A later session could treat an item the owner never ruled on as sanctioned, because it sits in a 'final' registry.

**Suggested change:** Add one decision, D23 'Contract and dependency-law registry', that lists every CD and CB id and every new module (including :bench-app and :bench-cli) with its target version, or map each CD row to an existing D. State in section 8.3 that an item with no D ruling is not approved.

### CONFORMANCE-3 [high] Section 0 'Decided in this synthesis (engineering choices; reversible, and listed in section 1.5)'

**Problem:** This list presents as already decided several items that section 10 treats as open owner decisions: the peer egress class (D3), the append-only, fail-closed ledger (D5), per-export signing keys (D6), the usability gate applying to peers only (D9), deferral of A2 (D12), and no delegation (D15). It also lists 'The echo header states the request's furthest content reach', which redefines the meaning of the frozen section 5.4 X-Asom-Egress header, as an engineering choice.

**Consequence:** Owner decisions are pre-empted, and a change to the meaning of a frozen header is framed as reversible engineering. A reader who stops at the executive summary sees them as settled. This conflicts with the brief's own statement that 'This brief does not assume it'.

**Suggested change:** Retitle the list 'Recommended, pending owner ruling' and tag each bullet with its D id. Move the reach rule under CD-1 or D3 as an explicit change to the meaning of frozen section 5.4, needing sign-off.

### CONFORMANCE-4 [high] Section 7.6 egress truth ('Echo headers and the final or error record use reach'; vector 'peer body then self serves (header peer)'), section 8.4 RouteRecord (egress plus a separate reach field), section 8.1 Invariant 9 row 'honoured, extended'

**Problem:** In v1, RouteRecord.toEchoHeaders builds X-Asom-Egress from the same egress field the route_log row stores. The code comment reads 'so the API answer and the dashboard can never disagree'. The design adds a separate reach field and builds the header from it, while the row's egress column keeps the class of the attempt that served. When a peer receives the body and SELF then serves, the header says peer and the dashboard's egress column says local. Both come from one struct, so the letter of Invariant 9 holds and its purpose fails. The PROGRESS.md audit found this failure mode before: ALL_PROVIDERS_COOLING recorded egress=local while section 1.9 technically held.

**Consequence:** The API and the dashboard state different egress for the same request. That breaks a hard invariant ('fails the build'), and section 8.1 reports the invariant as honoured.

**Suggested change:** Make it normative that the terminal or error row's egress column equals reach, which is the value in the header. Carry the serving attempt's class in a separate additive column such as servedClass. Alternatively, require the dashboard and the export to render reach as 'Egress'. Add a W01b vector for peer-then-SELF that asserts the header value equals the row value.

### CONFORMANCE-5 [high] D6 ('Complete Amendment 1'), IC-5 (Invariant 3(e)), IC-6 (the export instance), section 12 row 21 (disposition 'OD + A: D2/D6 state the literal reading'), OWNER_BRIEF D6 row

**Problem:** Roadmap section 13 Amendment 1 changes Invariant 1 only, and Invariant 1 says v2 adds 'exactly one more such action', the P7 upload. IC-5 adds a new Invariant 3 egress class at v2, and IC-6 adds a second user export action (the signed or anonymous report file). By the letter, both go beyond Amendment 1, and critique 21 said exactly that. D6 never states this literal reading: it says 'completes' and 'widens', and it names a third-amendment risk only for option (c). So the section 12 disposition claims a change the body does not contain.

**Consequence:** What is, by the letter, a third-amendment change reaches the owner described as housekeeping, against the roadmap section 13 escalation rule. The disposition log also overstates its own coverage.

**Suggested change:** Add to D6 a 'literal reading' paragraph like D2's. It should say that IC-5 amends Invariant 3 at v2 and IC-6 adds an export action beyond 'exactly one more', so both are third-amendment material under roadmap section 13 unless the owner rules otherwise. Offer the options: accept as part of Amendment 1 with the roadmap text updated, record as a third amendment, or option (d) with no exports. Mirror this in OWNER_BRIEF.

### CONFORMANCE-6 [medium] IC-4 ('A remote node is never an app client ... never acts under an app's pairing') vs section 7.8 / D15(b) ('The pairing is per host app ... The home provider ledgers and can revoke each host app individually')

**Problem:** Under M4, the requester is AsomKit running inside third-party iOS host apps, each paired with the provider under its own key. A remote app therefore becomes a paired requester identity whose app name is self-asserted, with no OS verification. The drafted IC-4 text forbids that, and Invariant 5 limits app identity to identities verified through AIDL. D15(b) lists only a 10A tier SIGN-OFF, and D14 part B covers local-app identity, not a remote app's identity.

**Consequence:** The recommended iOS shape needs Invariant 5 text that no decision asks for, and it contradicts the invariant text the brief itself asks the owner to adopt under D2.

**Suggested change:** In D15(b), state that per-host-app pairing needs an Invariant 5 clause for remote app requesters whose identity is a key pinned at QR pairing and whose app label is self-reported. List it as third-amendment material inside D14 part B or D15. Alternatively, make M4 pairing per device, not per app, so IC-4 still holds.

### CONFORMANCE-7 [medium] Section 6.2 B24 ('MVP cut (in v2): daemon shell plus desktop CLI'), section 9.3 v2 row (gate includes the owner-device llama-bench parity gate B23), D4(b) ('Pulls forward only v4 and a desktop port of v2 P0-P4 (D-v2)'), section 9.4

**Problem:** B24 ships the desktop asom-bench CLI in v2. Per benchmark.md, that means desktop native llama.cpp builds and tarball, .deb/.rpm, notarised macOS and Homebrew packaging. That is roadmap v4 asom-desktop work, and macOS work that D13 places at M2, pulled into the EXECUTION-GRADE v2. D4 says only D-v2, after v2, is pulled forward. The section 9.4 v2 estimate does not budget for it. Separately, the active benchmark lane contradicts v2 P6's 'This is not a new subsystem', and D7 addresses only the standalone-app wording.

**Consequence:** Later-version work starts inside v2 without being flagged, and v2's frozen scope and gates grow beyond what the owner is asked to approve.

**Suggested change:** Either move the desktop CLI to D-v2 or M2 and keep v2's MVP as the daemon shell only, or list 'desktop asom-bench in v2' explicitly in D4 and D7 with its effort. Extend D7 to cover the 'not a new subsystem' sentence (the active lane).

### CONFORMANCE-8 [medium] Section 1.2 X13 ('Cloud ban + per-app mesh toggle express my devices, never cloud'), section 8.5 ('Cloud-banned apps stay off'), section 1.4 ('What it does not pull forward: anything from v2.5 or v3'), D4(b)

**Problem:** The per-app cloud ban belongs to the v2.5 per-app policy table (roadmap section 5), and contract.md's P table depends on it. Under the recommended D4(b), mesh-1 ships before v2.5, so the cloud ban does not exist. The X13 resolution that rejects the own-devices policy therefore either rests on nothing, or quietly pulls part of v2.5 forward. The D9 switch is per user and does not replace it.

**Consequence:** Either a v2.5 feature is pulled forward without a flag, or mesh-1 has no way to express 'my devices, never cloud' per app, although the rejection of the own-devices policy and NO_ELIGIBLE_NODE relied on one.

**Suggested change:** In D4(b), list the per-app cloud ban as pulled forward or as a lost protection. Then re-check X13 and D12's rejection of the own-devices policy under that sequencing, and give the owner the option to pull forward the cloud-ban column only.

### CONFORMANCE-9 [medium] Section 8.2 IC-1 and its six-item 'How this differs from the roadmap's sanctioned text'; IC-2; section 8.4 (interval CONTROL rows, 'Loss window ... at most 1 h', decline at offer with no provider intent)

**Problem:** Roadmap section 7's sanctioned Amendment 2 text requires 'every remote request ledgered on both nodes'. IC-1 drops that phrase. IC-2 and section 8.4 then ledger remote control requests (STATE_REQ, HELLO, PING) in hourly interval rows that can be lost on a crash. The six-item list of differences does not mention this weakening, and D5 calls interval rows 'an interpretation' without saying they change the sanctioned text.

**Consequence:** A seventh change to the one sanctioned amendment text is not flagged, in exactly the area where the audit found under-reporting.

**Suggested change:** Add item 7 to the section 8.2 list: 'every remote request ledgered on both nodes' becomes per-attempt rows for content and interval rows for control, with a loss window of up to 1 h. Put it to the owner inside D2 with D5(d), one row per control message, as the literal alternative.

### CONFORMANCE-10 [medium] D1 and section 9.1 (lab isolation rules; L0.4-L0.6); OWNER_BRIEF D1 row

**Problem:** D1 is framed as an exception to CLAUDE.md alone. It does not cite roadmap section 0 ('do not start a version until [entry criteria] are met; strictly sequential'), v4's DIRECTION-GRADE 'do not cold-execute', or v2's entry criteria. It also claims the lab 'touches no frozen surface or build path', yet it adds a job to .github/workflows/ci.yml (the guaranteed build path), a repo-root conformance/ directory declared normative (CD-C), and includeBuild('..'). L0.4-L0.6 build live state, the peer class and the IC-3 quiescence rule before D2, D3 and D5 are decided.

**Consequence:** Later-version execution begins ahead of the rulings it depends on, which creates sunk-cost pressure on D2 and D3. The claim of isolation is overstated.

**Suggested change:** Split D1. D1a: L0.1 (which pins frozen v1) and L0.2/L0.3 (v2 manifest and bench maths) now, citing roadmap section 0 and the v2 entry criteria as the exceptions. D1b: L0.4-L0.6 only after the D2, D3 and D5 rulings. Correct the wording to say that a CI job and a repo-root data directory are added.

### CONFORMANCE-11 [low] Section 8.5 'P subset of {T, O, X other-owner peer, C}'; section 5.2 'audience: "other" reserved, unused (D20)'

**Problem:** D20 recommends (a), 'not within this roadmap', and does not recommend (b), 'designed but disabled'. The normative classification lattice and the manifest schema still reserve other-owner slots.

**Consequence:** It builds a little toward the direction excluded by the section 11 stop-line ('nothing in this document may build toward it') and makes a later reversal cheaper by default.

**Suggested change:** Remove X and audience 'other' from the normative mesh-1 schemas and vectors. Keep them only as a note in D20.

### CONFORMANCE-12 [low] Section 8.6 quiescence law ('Providers accept connections only while SERVING and initiate none') vs rule 3 ('the user started a peer operation (pairing, sharing a report, revoking)') and the REVOKE_NOTICE frame

**Problem:** The rule that providers initiate nothing contradicts rule 3 whenever the user revokes a peer or shares a report on a provider. IC-3(vi) would freeze this rule into Invariant 1.

**Consequence:** The text proposed for the invariant is internally inconsistent, so either an implementation breaks the invariant or user-initiated revocation cannot notify the peer.

**Suggested change:** Reword to 'Providers initiate no peer connection except under rule 3', and make IC-3(vi) match.

## Lens: overclaim — verdict: needs-changes

### OVERCLAIM-1 [high] Section 0 'Nothing about user presence goes on the wire'; IC-3 (ii) 'never ... any signal of whether a person is using a device'; Section 7.3 F12; Section 7.4 schema

**Problem:** The design breaks the invariant text it asks the owner to sign. F12 moves user-activity decisions to the provider, and the provider then answers with PEER_UNAVAILABLE plus retryAfterMs. That decline, together with availability.fsm and reason transitions, memory.availBytes256M at 256 MiB steps, engine.loaded, queue.bucket and the Deck 'no game' GPU-contention drain, lets a requester infer when someone is using the provider. contract.md itself listed presence 'only as a decline code', and the synthesis then claimed there was none.

**Consequence:** The owner would sign IC-3 believing presence never leaves the device, while mesh-1 as specified leaks it through several channels. This is a false privacy claim written into an invariant, the kind of failure the audit lesson (a stated class must be true) exists to prevent.

**Suggested change:** Reword Section 0 and IC-3 (ii) to: no field names or encodes presence, but availability transitions, declines, memory and loaded-model fields can correlate with use by the owner of the providing device. List each channel in Section 7.12. Either drop availBytes256M and engine.loaded from st and STATE, or quantise them more coarsely and rate-limit how often they can change. Add a law and a vector showing that a change in local usage alone cannot change any wire field within N minutes, or state plainly that it can.

### OVERCLAIM-2 [high] Section 5.7 ClaimTracker: discard rules and 'hot ... decided ONLY from requester-held data: the accept-time st.tb'; disposition row 30; Section 5.11 'Placement follows observation after 3 samples'

**Problem:** Two peer-controlled inputs undermine the tracker. First, observations are discarded when the accept-time queue bucket is above 0, and st.qb is reported by the peer. A lying peer can report qb >= 1 on every accept, keep n below 3 for ever, and stay UNVERIFIED, so its claim-derived prior keeps counting at up to 1 in 4 placements indefinitely. Going WEAK when more than 50% of observations are discarded does not change Effective(k), which depends only on n. Second, st.tb is also peer-reported. Calling it 'requester-held' is mislabelled, and a peer that sets tb >= 1 lowers the expectation to steady. That contradicts row 30's statement that peer-reported state can only discard an observation, never lower the expectation.

**Consequence:** The headline anti-poisoning guarantee in Sections 5.11 and K11 ('after 3 observations the claim no longer matters') does not hold against the adversary it is written for.

**Suggested change:** Count discarded observations toward a separate budget. Once they exceed 50% of the last 20, set prior' to the minimum of the observed decodes (or to 0), so claims stop mattering. Decide 'hot' only from the requester's own observation trend, never from st.tb. Add M08 vectors: a peer that always reports qb=1, and a peer that always reports tb=2, while claiming 3x its true speed.

### OVERCLAIM-3 [high] OWNER_BRIEF item 3 'The mesh adds only three header/model values, one in-band stream error value and new ledger columns'; D12 '(a) ... three values plus one SSE error value'

**Problem:** The one-page brief and D12 undercount the additions to the frozen contract. Section 8.3 also adds: LEDGER_UNAVAILABLE, a new 503 error code that changes v1 cloud behaviour from v1.1 (CD-LU); two X-Asom-Failover values (CD-FO), which the D12 package itself adds; the router reading max_tokens, which changes frozen Section 5.9 (RC-9 and D11); a body normaliser that rewrites model and drops fields for peers (T16); a fourth InferenceClient tier, RemoteMesh (CD-DOC and D15); and a new desktop control channel (CD-24).

**Consequence:** An owner deciding from the one-pager would approve a smaller change to the frozen contract than the design actually makes. That runs against the 'add only, flag every addition' rule.

**Suggested change:** Replace the sentence with an exact count taken from Section 8.3: app-facing values (CD-1, CD-2, CD-4, CD-FO x2), one SSE error value, one new HTTP error code, one Section 5.9 wording change, one new 10A tier, plus ledger columns. Correct D12(a) to match.

### OVERCLAIM-4 [high] OWNER_BRIEF item 2 'any app can verify as a per-export signed file. A signature proves the report is unchanged and who signed it'; Section 1.1 row R3d

**Problem:** An exported file is signed with a throwaway per-export key. Without an out-of-band fingerprint comparison, a subscriber learns nothing about who signed it: Section 5.4 itself says 'anyone could have made this key', and an attacker can edit the file and re-sign it. The requirement also says 'subscribes'. Because every export uses a fresh key, successive reports from one device cannot be linked, and the only standing channel for third parties (/admin/manifest) is deferred. Outside paired asom nodes, R3's 'any app or device that subscribes' is therefore not met, and Section 1.1 does not say so.

**Consequence:** The owner is told that R3's tamper-evidence works for 'any app'. In practice it works only for a recipient who compares a fingerprint by hand on each export, and there is no subscription at all.

**Suggested change:** Owner brief: 'proves the file is unchanged since signing only if you compare its fingerprint with the exporting screen; otherwise anyone could have produced it.' Section 1.1 R3d: mark third-party subscription (ongoing updates, linkable across reports) as NOT MET in mesh-1. Add it to D6 as an explicit option, for example a user-created stable per-subscriber export key, with its linkability cost stated.

### OVERCLAIM-5 [medium] Section 0 'around 10x total time and 13x time to first token' next to 'The first user-visible mesh value (phone to Deck)'; OWNER_BRIEF item 1 'roughly 10x for an 8B model on phone to desktop'; A12

**Problem:** The 10x figure depends on A12, which assumes a desktop-class node decoding 8B at about 45-50 tok/s. The named mesh-1 providers are a Steam Deck and a Dell whose OS and GPU are unknown. The Deck's shared LPDDR5 bandwidth (roughly 88 GB/s, my assumption, not verified) caps 8B Q4 decode at well under 20 tok/s, so phone to Deck is more like 2-3x. The owner brief drops the assumption entirely, and Section 0 sets the 10x figure beside the Deck.

**Consequence:** The main value claim for the first release is a number whose basis does not describe the first release's hardware. That is the 'numbers without stated assumptions' failure.

**Suggested change:** State per-provider ranges with the bandwidth and quant assumptions (phone to Deck, phone to GPU desktop), label both [A12] estimates, and put the Deck range in the owner brief. Make the owner-device benchmark at D-v2 the gate that replaces them.

### OVERCLAIM-6 [medium] IC-3 (v) 'every transmission is ledgered'; IC-2; OWNER_BRIEF 'Every mesh egress is ledgered'; Section 8.4 loss window and L-L15

**Problem:** Section 8.4 aggregates control traffic, including STATE payloads and st digests, into hourly interval rows. It admits that up to about 1 h of control bytes can be lost on a crash. The provider side of STATE is specified only as 'mirror image', with no rule for when its row is written. L-L15 (interval + attempt + manifest bytes = the socket's total) does not say whether bytes are counted as plaintext frames or TLS records, so it cannot be implemented or tested as written.

**Consequence:** The proposed invariant text and the owner brief claim a completeness the mechanism does not give, echoing the audit's under-reporting lesson. The byte law would pass or fail depending on an unstated accounting choice.

**Suggested change:** Put the aggregation and the loss window into the IC-2 and IC-3 text itself. Require a durable CONTROL intent row before the first STATE or st is sent in each session, on both sides. Define byte accounting at the TLS record layer, with handshake bytes on DIAL rows, and add a vector for L-L15.

### OVERCLAIM-7 [medium] Section 4.2 T9 'Maximum session age is 30 min, forcing a fresh handshake' vs T2 'resumption is tolerated' (JSSE 17); W08 'a resumption attempt'

**Problem:** T9's stolen-phone protection depends on each new session signing with the keyguard-bound leaf key. T2 tolerates TLS 1.3 PSK resumption where a stack cannot disable it per context, and JSSE 17 is exactly the desktop-provider stack the phone connects to. A resumed session sends no CertificateVerify, so a locked phone could keep opening sessions without using the key. trust.md said 'No resumption'; the synthesis weakened that without revisiting T9. Because W08's expected outcome for resumption now varies by stack, W08 can pass while T9 is defeated.

**Consequence:** The security claim of a keyguard-bound leaf plus 30-minute sessions is stronger than the mechanism supports on the mesh-1 path.

**Suggested change:** Require the client side (Conscrypt, Network.framework) to never offer resumption. That is controllable per client context and closes the path whatever the server does. Make W08 assert a full handshake with CertificateVerify for every session. Otherwise, state in Section 4.4 that T9 does not hold against providers that tolerate resumption.

### OVERCLAIM-8 [medium] Section 9.3 M1 gate (5) 'Quiescence: 30 min idle, a packet capture on both providers filtered to 11436 shows no connection'

**Problem:** The gate can pass without testing the law. It does not require the mesh to be on, an app opted in, or the state scope granted. It filters to port 11436, although T12 lets a peer declare its own listener port in HELLO. It captures on the providers, not on the phone, so phone-originated traffic to any other address or port is not seen.

**Consequence:** A green quiescence gate would not show that a mesh-enabled phone sends nothing when idle, which is the IC-3 (vi) claim the owner is asked to rely on.

**Suggested change:** Capture on the phone across all interfaces for 30 minutes, with the mesh on, at least one app opted in, state granted and the Peers tab closed. Assert zero SYNs to any paired-peer address on any port and zero new DIAL or CONTROL rows. Add a positive control: opening the Peers tab must produce a connection.

### OVERCLAIM-9 [medium] Section 8.1 row 'CLAUDE.md no KMP / do not start later versions: honoured' vs D1(b) 'an explicit exception to CLAUDE.md'; IC-4 'No network message can create, restore or raise a pairing'; IC-1 address ranges vs Section 4.1 'decided by interface, not by address range'

**Problem:** The status table and the text for signature contradict the design. First, Section 8.1 says the 'do not start later versions' rule is honoured, while D1 calls the lab an exception to it. Second, IC-4 says no network message can create a pairing, yet PAIR_* frames do exactly that inside the user-opened window. Third, IC-1's 'private or link-local addresses only' range list is ambiguous about whether it covers overlay interfaces. Headscale and Tailscale IPv4 addresses are in 100.64/10, which is not in the list, and Section 4.1 says eligibility is decided by interface, not by range.

**Consequence:** The owner may sign text that, read literally, forbids the recommended overlay (D8b) or the pairing protocol, or believe a CLAUDE.md constraint needs no exception.

**Suggested change:** Mark the Section 8.1 row 'requires owner exception (D1)'. Reword IC-4 to 'no network message outside a user-opened pairing window, and no message without on-device confirmation on both devices, can create...'. In IC-1, make clear that the range list applies to clause (b) only, and say how overlay addresses such as 100.64/10 and fd7a:115c:a1e0::/48 are made eligible (by interface selection).

### OVERCLAIM-10 [low] Appendix A F26; D22 'regional from 30 Sep 2026'

**Problem:** F26 is labelled VERIFIED as 'covers apps outside Play'. The current developer.android.com page says the 30 Sep 2026 phase covers installs from participating stores (Play, Galaxy Store, OPPO and others) in Brazil, Indonesia, Singapore and Thailand. Sideloading and F-Droid are handled through an 'advanced flow', and 'all apps on certified devices' arrives only in 2027. A 20-device limited-distribution account exists.

**Consequence:** The urgency and scope of D22 for sideload and F-Droid distribution are overstated under a VERIFIED label.

**Suggested change:** Restate F26 exactly as the page puts it (participating stores in 2026; all apps on certified devices in 2027; advanced flow for other installs) and adjust D22's wording.

### OVERCLAIM-11 [low] Section 5.2 'seq: max(stored+1, epochSeconds): monotonic without trusted state (restore-safe)'; Section 5.8 file-audience projection 'truncates measuredAtMs and run times to the day'

**Problem:** seq is derived from the wall clock, so it is not monotonic 'without trusted state': a clock set backwards after a restore breaks it. Signed seq and presentation.issuedAtMs also carry the exact second of export, which undoes the file projection's day truncation.

**Consequence:** A small overclaim about rollback protection, and a timing and linkability leak in exports that the text says has been removed.

**Suggested change:** Say that seq is monotonic only if the wall clock is not behind the last issued seq. For audience 'file', truncate seq and issuedAtMs to the day, or omit them, and add the rule to the projection vectors.

### OVERCLAIM-12 [low] Section 3.5 and Section 9.1 L0.1 'lab reaches :core:* read-only through includeBuild("..")'; gate 'root build untouched'

**Problem:** includeBuild("..") evaluates the root settings, which include the Android modules whenever an SDK is present, as it always is in CI. The lab CI job would then need AGP and is no longer bare-JDK. The root projects also declare no group coordinates, so dependency substitution needs explicit dependencySubstitution rules, which the design does not specify.

**Consequence:** L0.1 is not implementable exactly as written. Its isolation gate (no 'lab' string in the root settings) passes without proving that the lab job is independent of the Android toolchain.

**Suggested change:** Specify the substitution rules, or have the lab consume the published jars of :core:*. Run the lab CI job with ANDROID_HOME unset, and add that to the L0.1 gate.

## Synthesis-declared unresolved items

- Owner rulings are pending on all 22 decisions, above all D2 (whether the invariant package counts as the one v4 amendment or more), D1 (lab pre-work), D4 (sequencing) and D8 (underlay).
- Whether any Headscale or client configuration stops the Tailscale phone client uploading logs is unverified; only desktop opt-outs are documented.
- Whether the Android 17 local-network permission covers loopback 127.0.0.1 or VPN/tailnet interfaces is untested; loopback coverage would break v1 at targetSdk 37.
- Load-bearing platform spikes are not yet run: S-A2 (iOS software leaf as sec_identity), S-A3 (Secure Enclave from a macOS JVM and library validation), S-A9 (per-stack TLS knob matrix), S-A10 (swift-certificates verifier), S-A11 (SNI-token gating to hide the listener certificate).
- All thresholds are provisional and uncalibrated: claim-tracker thresholds, score weights, numerics tolerances and editorial constants.
- GPU cancellation latency and the effect of chunked ubatches on benchmark throughput are unmeasured; so are time-to-cool on a charging phone and whether the standard plan fits its wall cap.
- Steam Deck Game Mode behaviour of user services, sleep inhibitors and fdinfo-based GPU accounting on SteamOS is unverified.
- The prefill cost of discarding provider KV caches for multi-turn chat on Deck- or Dell-class hardware is unmeasured (D19).
- The effort estimates (64 to 107 mesh-specific engineer-weeks) are rough and unmeasured.
- The Dell's operating system, whether the owner has an Apple-silicon Mac, and the CUDA redistribution terms are unknown owner inputs.
- Bench set 1 sha256 pins must be confirmed by download before they become normative.
- Two thermal-polling sources conflict (AOSP code 500 ms versus ADPF guidance 10 s); the conservative rule was adopted without device confirmation.