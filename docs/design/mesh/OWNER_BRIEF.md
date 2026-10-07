# ASOM mesh: owner brief (one page, revision 4)

Full design: `ASOM_MESH_DESIGN.md` (r4): what is decided in §10.0, what is open in §10.1–§10.5, every contract change in §8.3. Builders' specs: `LAB_SPEC.md` and `PLATFORM_PLAN.md`. Review log: `REVIEW_ROUND2_DISPOSITION.md` (35 findings: 32 accepted, 3 need your ruling, 0 rejected); round 3 and the implementation readings: `REVISION_4.md`. **Every ruling still open, with options and what it blocks: `OWNER_DECISIONS.md`.**

**Decided baseline (your directives, plus the acting decisions you delegated; overrule any of them and the plan changes):** Apple, Windows and Ubuntu Touch are in scope beside Android, Linux and the Steam Deck. The mesh is symmetric and holonic, inside the unchanged stop-line. The router is the differentiator; the benchmark is not. **Order (AD-1):** v1 device validation, then v1.1, then v2, then mesh-1 (the v4 core with the desktop port), then v2.5, then v3, then the rest of v4. That moves v4 content ahead of v2.5 and v3, and the brief says so. **Amendment 3 (AD-2)** covers the mesh and **relaxes Invariant 1**: paired devices automatically exchange banded status and the manifests you approved. Prompt and response content never moves automatically. Only the ship-nothing lab and scaffolds start now; Android app code stays untouched until v1 is validated.

## The recommendation, in five sentences

1. Build a mesh of your own paired devices. Each sends a whole request to whichever device, or your own cloud keys, can serve it best right now. It carries model inference only, never code. asom addresses traffic only to devices you paired; over an overlay the encrypted packets can pass through the overlay's coordinator and relays, and some overlay clients upload their own logs (D8). No node is a full holon (serves its own apps, borrows and lends) before M2+: today the Android phone only serves its own apps; from M1 it also borrows; it becomes a full holon when it lends, after v3. Desktops become full holons at M2 with D25(b) and D14 part B. iPhone, Ubuntu Touch and any car or appliance node can only borrow (§0.1). The protocol is symmetric by design.
2. Every speed-up is estimated, not measured. For an 8B model: phone to an Apple-M4-Pro-class or GPU desktop is about 8–10× total time. **Phone to the Steam Deck is about 0.8–2.8× total time, possibly no faster, and time to first token lies anywhere from 0.8× (slower than the phone) to 4×.** Two defensible bases for the Deck's prefill rate disagree by about 2–5× (§7.1); on the lower one the Deck fails the `auto` usability gate for prompts above about 500 tokens. Phone to a CPU-only desktop may be no faster at all. So measuring your Deck and Dell at D-v2 comes before any promise.
3. The router is on the device, shared by all your apps, uses your own keys, keeps an auditable ledger and needs no backend. **No project we found** does this, which is absence of evidence, not proof. MLPerf Mobile and MLPerf Client already cover phones, desktops and Apple devices. So the benchmark adds only three things: a signed manifest, plain text from the same data, and input to the router. asom never writes "MLPerf-comparable", and it ships the MLPerf name only after your trademark check.
4. **An exported report's signature proves nothing about who made it unless the recipient compares its fingerprint with your exporting screen over another channel.** Then it proves exactly what comparing the file's hash would.
5. Nothing ships before v1 is validated. **v1.1 then adds H1–H7, including the new error `LEDGER_UNAVAILABLE` if you rule D5 that way** (a cloud request then fails when the ledger cannot write). Mesh work ships after v2.

**Frozen-contract changes, complete count (copied verbatim from §8.3; each item names the decision that rules it, and none is approved until that decision is ruled):**
6 new app-facing values and 1 restated header meaning (`X-Asom-Egress` = reach); 1 new HTTP error code landing in v1.1 that changes v1 cloud behaviour (`LEDGER_UNAVAILABLE`); 2 changes to §5.9 body handling; 1 new SDK tier (`RemoteMesh`) and 3 changes to the pinned `CLIENT_API.md`; 2 ledger egress classes, 20 ledger columns (21 if D34 rules the `sessionId` column; r4), an intent status (status 0), new export keys and 4 new `callerPkg` forms; 1 local control channel (the desktop owner socket); 3 desktop local-app items at M2 (transport, `asom pair-app` token minting, self-reported app label); a whole new peer plane (a protocol and port, 3 scopes, peer-channel codes, a live-state schema, manifest formats with 3 renderer ids and a public derivative); 1 catalogue field; 7 invariant texts plus 1 conditional clause, touching Invariants 1, 3, 4, 5, 7 and 8 (Invariant 2's text unchanged); 15 roadmap or brief text changes (including the targetSdk pin, and RT-15 added in r4); up to 5 new root modules (4 while the Android standalone benchmark APK stays unscheduled) and 1 new dependency edge; 8 new shipped third-party dependencies, 4 build-time tools and 1 conditional dependency; 6 new artefact identifiers (one of them unscheduled); 2 Android manifest permission additions (`CAMERA` at M1; `ACCESS_LOCAL_NETWORK` only if D29 bumps targetSdk); 1 normative conformance artefact.

**Amendments if every recommendation is taken: five.** 1 and 2 as written; 3 (mesh; AD-2, decided); 4 (benchmark sharing: the contribution class and the report export; D6); 5 (platform equivalence: UI toolkits, key wrapping, local identity per OS; D14).

## The 22 open decisions of r3 (recommendation in bold; full options in §10). Revision 4 adds D12.0, D12.4b, D30–D41, RT-15 and two sub-questions; all are in `OWNER_DECISIONS.md`

| Before | Decision | Recommendation |
|---|---|---|
| v1.1 | **D5** ledger shape and the new error | **(b)** append-only rows, one row per control frame, `LEDGER_UNAVAILABLE` from v1.1 (changes v1 cloud behaviour) |
| v1.1 | **D23** registry of contract items and dependencies | **(a)** approve as listed; any item without a ruled decision stays unapproved |
| v2 | **D6** report export and upload class | **(b)** one-shot files with per-export keys and the fingerprint step, **recorded as Amendment 4** |
| v2 | **D7** roadmap v2 P6 wording | **(a)** one benchmark inside every node; no standalone benchmark products |
| v2 | **D18** comparability, bench sets, trademark check, editorial key | **(a1)** Qwen3 default, Llama set opt-in; your MLPerf-name check before any shipped use; reference-table key held by you |
| v2 | **D22** publisher identities | **Android developer verification (a); Windows SignPath, else Azure, else OV; one Apple Team ID, chosen once** |
| D-v2 | **D21** engine crash containment | **(a)** in-process with a crash contract |
| D-v2 | **D25** local callers that are not apps | **(a)** your CLI and the Ubuntu Touch app's own screen are not apps (Amendment 3 clause); **(b)** desktop app API at M2 |
| D-v2 | **D27** platform engineering package | **(a)** accept; M-D4 (the hardened macOS launcher) is the item worth reading |
| D-v2 | **D28** which devices lend in mesh-1 | **Dell with the polkit keep-awake rule; Deck in Game Mode only behind an opt-in that says it lends invisibly, never on battery; Windows or Mac only if that is your desktop** |
| M1 | **D3** meaning of `X-Asom-Egress` | **(a)** furthest content reach (changes frozen §5.4's meaning) |
| M1 | **D8** network underlay | **(b)** Headscale plus confirmed-LAN direct, with a per-platform log-upload disclosure |
| M1 | **D9** what `auto` means | **(c)** scored, with a usability gate on peers only |
| M1 | **D11** body handling | **(a)** read `max_tokens`; strip identity fields from bodies sent to peers |
| M1 | **D12** six new app-facing values in five lines (r4: D12.0 `peer` header value added; D12.4b CD-RR at v2.5) | **approve D12.0–D12.5 one by one; D12.6 withdrawals** |
| M1 | **D19** lender prompt cache | **(a)** discard after every peer attempt |
| M1 | **D29** Android targetSdk | **(a)** stay at 35 unless ruled separately |
| M1b/M2 | **D14** platform-equivalence invariant text | **(a)** approve just in time, **recorded as Amendment 5** |
| M1b | **D15** iOS requester shape | **(b)** one home lender, device-level pairing |
| after v3 | **D16** phone and tablet lending | **iPad lend screen (M5); iPhone never; Android lend screen plus charging with "serve while locked"** |
| M1b | **D24** Apple and Ubuntu Touch placement | **(a)** iOS app and Ubuntu Touch requester right after mesh-1, before v2.5 (a recorded departure, RT-13) |
| — | **D26** cars and appliances | **(a)** not scheduled |

## The single next step

Rule **D5 and D23** (they gate v1.1). Send the inventory: the Dell's OS **and GPU**, whether you have an Apple-silicon desktop Mac, whether a Ubuntu Touch device exists, and Headscale yes or no. Meanwhile a builder session implements work item L0.1 (`LAB_SPEC.md` §3): a separate `lab/` build that pins today's frozen v1 behaviour against the real `:server`. It runs on JDK 17 and 21 with no Android SDK, proves the root build unchanged, and pastes real output into `PROGRESS.md`.
