# asystemofmodels (asom): constellation porting plan (thin delegate)

> Part of the constellation-wide porting program (`Personal-Tracker/PORTING_PROGRAM.md`, 2026-10-06).
> Status: **PLAN. Nothing here has been built, run on a device, signed or released.** **Authority:**
> `PLATFORM_PLAN.md` (PP below), with `ASOM_MESH_DESIGN.md` r3 and `platforms/*.md`, is this repo's porting plan and
> wins on any disagreement (program rule I-9). This file does not re-plan asom: it records what exists per platform,
> what asom offers other repos and when, and the owner rulings the program needs. PR base
> `claude/asom-v1-build-brief-vw83oh` (default branch; `main` at 98ab632 holds only the P0 skeleton (its PROGRESS.md marks P1–P8 not started) and none of the mesh program; PR #1 is 70 commits ahead, 0 behind (GitHub API, 2026-10-06)).

## 0. Evidence labels (never dropped)

asom's set: `LAB` · `CI (hosted VM) evidence` · `EMULATOR EVIDENCE` · `SIMULATOR` · `CI-APPROX — NOT DEVICE
EVIDENCE` · `SIMULATED — NOT DEVICE EVIDENCE` · `VIRTUALIZED — NOT DEVICE EVIDENCE` · `SYNTHETIC` · `CI-ONLY / NOT
RUN` · `NEEDS-DEVICE-VALIDATION` (NDV) · `NEEDS-OWNER-VALIDATION` (NOV). Program additions: `PLAN` ·
`NOT-APPLICABLE (<reason>)` · `CONTAINER-BUILD-ONLY` · `BROWSER-HEADLESS`.

## 1. What this repo is, in porting terms

- **Product and state.** asom: one Android app owns model files, BYOK keys, routing and an egress ledger behind an
  OpenAI-compatible API on `127.0.0.1:11435`; other apps pair over AIDL (`README.md`). `PROGRESS.md`: P0-P8 executed to
  JVM/CI gates plus an audit (71 defects fixed); root `jvmTest` 139 tests (`lab/ROOT_TEST_BASELINE`); every on-device
  gate is NDV, the P4 real-key smoke NOV. Since 2026-09-30 a mesh program (roadmap §14) adds a lab and ship-nothing
  scaffolds for the five targets; hosted CI at `aa060c4` was 51 of 52 runs green (the failure is the non-gating 26.04
  canary; head `4cf2ad0` not re-read). All on unmerged PR #1; nothing released; every artefact labelled UNSIGNED.
- **Stack.** Kotlin 2.1.21 (JVM 17 bytecode, Gradle 8.14, AGP 8.7.3), Ktor 3.1.3, OkHttp 4.12, Room 2.6.1, Compose over a
  vendored Hyle token seam; Swift 6 (`apple/`); QML + Qt 5.15 (`ubuntu-touch/`). Root build plus three separate Gradle
  builds (`lab/`, `desktop/`, `ubuntu-touch/jvm/`) that map root pure-JVM projects by directory.
- **Size, measured 2026-10-06 at `4cf2ad0`.** `git ls-files | wc -l` = 1,118; source files matching
  `\.(kt|swift|qml|js|cpp|h|c|py|sh|aidl)$` = 672, 88,179 lines (`xargs cat | wc -l`); `@Test` lines in tracked `.kt`
  ≈ 1,070 (`grep -h '@Test' | wc -l`); Swift `func test` or `@Test` lines ≈ 201.

## 2. Portable core vs platform-bound layers

| Module / dir | Role | Portability | Approx LOC (reader-measured) | Notes |
|---|---|---|---|---|
| `core/{contract,catalogue,routing,inference-api}`, `server` | frozen contract, router, engine seam, Ktor server | pure Kotlin/JVM | 574 · 261 · 1,093 · 60 · 3,547 | no `android.*`; `contract` has one JVM-only import (`java.math.BigDecimal`) |
| `app`, `client`, `client-cloud`, `vault`, `pairing`, `storage`, `ledger`, `sample-client` | Android daemon, SDK, AIDL, Keystore, Room | Android-bound | 1,866 · 900 · 543 · 482 · 900 · 546 · 365 · 246 | untouched until V1-close; `client/AsomChat.kt`, `AsomHttp.kt` and `client-cloud/CloudHttp.kt`, `CloudRouting.kt` import no `android.*` |
| `lab/` | vectors, manifest, bench core, mesh router, simulator | pure JVM | 29,789 | separate build; ships nothing |
| `desktop/{node-core,node}` | `DesktopPlatform` seam, Linux host | pure JVM | 3,616 · 5,577 | the seam every other host plugs into |
| `desktop/packaging/{linux,windows,macos}` | jlink/nfpm; `winplatform` (JNA); `macplatform` + Swift helper | JVM, scripts, Swift | 1,959 · 7,407 · 9,878 | Win32 and Apple-API code is exercised only on hosted runners |
| `apple/` | AsomKit: independent Swift JSON/DSSE/bench/manifest lane | Swift | 8,078 | never linked into the Mac node |
| `ubuntu-touch/` | `ut-host` JVM, QML, Qt plugin, AppArmor, click | JVM + Qt/QML | 3,249 · 3,669 | foreground-only by design |

Platform-bound APIs that matter. AIDL `Binder.getCallingUid()` identity has no off-Android equivalent: the mesh uses a
pinned ES256 node key (Amendment 3) and OS peer credentials for the owner CLI. The Keystore vault has no non-Android
equivalent (IC-8, D14 part B; nothing built). Compose gives way to QML (built), SwiftUI (planned) and a CLI; any
non-Compose UI needs the Invariant 7 text change (IC-7, D14 part A).

## 3. Binding rules this port must not break

- **Invariants 1-9** (`CLAUDE.md`, brief §1): no automatic egress or telemetry; bind `127.0.0.1` only; egress only
  provider, catalogue and model downloads, each with a ledger row; Keystore-wrapped keys; AIDL identity, no HTTP
  registration endpoint; red/green never carry meaning (violet `#8E7BFF`, cyan `#35E0FF`, with shape and label);
  placeholder Compose UI; no GMS/Firebase; one `RouteRecord` for echo headers and ledger rows. Environment honesty
  (brief §12, AD-5) binds every port.
- **Three invariant amendments only** (roadmap §13, §14 AD-2; Amendments 4 and 5 are proposed, D6 and D14). The v1 HTTP
  contract is frozen, additive only with sign-off (brief §5, `ContractFreezeTest`, lab W00-W03 and R04).
  `./gradlew jvmTest` never breaks; no `android.*` in `:core:*` or `:server`.
- **No KMP, Compose for Desktop, GraalVM native-image, `llama-server` subprocess, in-app update check or crash data
  with content** (brief §13, PP P8, D17). One Kotlin/JVM and one Swift implementation; Rust only as the UT fallback.
- **Root build unchanged** (PP P2): root build files, `core/`, `server/` and `ci.yml` are never edited by a track; each
  workflow runs the pinned-base check (`79ff5b8`). Disjoint directories (P1), map never `includeBuild` (P3), new
  workflow files only (P4), scaffolds bind nothing (P5), nothing released (P6).
- **Android untouched until V1-close** (D-D, roadmap §14 item 7); no package renames (`xyz.mdhv.asom`); no cross-app
  daemon on iOS or UT (C6); never an unattended UT lender or the `unconfined` click template; UT 24.04-1.x and 24.04-2.x only (26.04 as a CI canary; never 20.04).
  Wording: "lends while awake", never "always-on" or "MLPerf-comparable". Licence Apache-2.0 repo-wide.

## 4. Target matrix (owner's order; efforts are the master's §5 figures, ranges from PP §11; all estimates)

| Target | Feasibility | Approach (authority) | Blockers | Effort (eng-weeks, estimate) | Evidence today |
|---|---|---|---|---|---|
| Ubuntu Touch | moderate | PP §7: UT-0 click (built), spike S-UT1 on a device, then UT-1, a foreground-only requester for the app's own screen via the shared JVM mesh client. Never a lender or cross-app service; no BYOK keys | S-UT1 NDV; no UT device on record (D28); UT-1 waits on mesh-1, D14 A, D23, D24, UT-D1..D6; Rust fallback +8-12 if S-UT1 fails | 6.5 (UT-1 5-8; UT-0 2-3.5 spent; UT-2 6-10 unscheduled) | PLAN |
| Linux desktop (incl. Deck) | native-fit | PP §3: DL0-DL3 built; DL4-DL5 engine and bench at D-v2, DL6-DL7 at M1; desktop local-app API at M2 | D21, D23, D25, D27, D28; v2 engine lands on Android first (AD-1); control socket unstarted (ERR-DL2-4) | 7 (DL4-DL7 5.5-9; M2 API +3-5 unscheduled) | PLAN |
| iOS / iPadOS | reframe: no daemon on iOS; nearest shape is AsomKit, the `AsomBench` app and an in-app `RemoteMesh` borrower of one home lender | PP §6: I0 built; I1-I3 at M1b; iPad lender I4 after v3 | v2, D7, D14 A, D24; mesh-1, D15, spikes S-A2/S-A9/S-A10/S-A12; no Apple Team ID (D22); no Xcode anywhere; Swift 6.1 ran on Linux in the 2026-09-30 build container (`TOOLCHAIN_NOTES.md`, I0a `LAB`) but is absent from this session's container (`which swift` empty, `/opt/swift` missing) | 16.5 (I1-I3 13-20; I4 4-7) | PLAN |
| macOS | native-fit | PP §5: same JVM node plus `macplatform` and a Swift helper; MC3-MC8 pending, MC9 daemon at M2 | no Team ID (M-D10, D22); S-M1, S-M3, S-M4, S-M5 need a Mac; D-v2 criteria; is there a Mac (D28) | 8 (source total 11-17; minimal cut 7-10) | PLAN |
| Windows | native-fit | PP §4: same JVM node plus `winplatform` (JNA); W3-W7 at D-v2, W8 device items later | D23 row for JNA; D22 signing; Win32 bindings documentation-derived, TPM, peer-credential, GPU, thermal items NDV; WIN-GATE-1 | 8 (source total 10-16) | PLAN |

### 4.1 Built so far, per track (gates in `PROGRESS.md`; labels as recorded there)

| Track | Directory and workflow | Passed | Open |
|---|---|---|---|
| Lab | `lab/`, `lab.yml` | L0.1-L0.6 (`LAB`, self-oracled; JDK 17/21 and Windows lanes `CI (hosted VM)`) | `:mesh-proto` (wave 3) not started |
| Linux/Deck | `desktop/{node-core,node,packaging/linux}`, `desktop-linux.yml` | DL0-DL2, DL3 gates 1, 2, 3, 6 (`LAB`); systemd-VM job, deb/rpm/tarball, 5-image install matrix `CI (hosted VM)`; sysfs fixtures `SYNTHETIC` | DL4-DL7; real systemd/logind/polkit, suspend, Deck Game Mode NDV |
| Windows | `desktop/packaging/windows`, `desktop-windows.yml` | W0-W2 (`LAB` + `CI-APPROX`); 27 Windows ITs green on windows-2025 and windows-11-arm (`CI (hosted VM)`) | W3-W7 stubs (WiX, winget, procrun); S-W1..S-W6 NDV |
| macOS | `desktop/packaging/macos`, `desktop-macos.yml` | MC1-MC2 (`LAB` + `CI-APPROX`); `macplatform` ITs green on a hosted macOS VM; Kotlin and Swift helper lanes agree on 404 vectors | MC3-MC8 exit-3 stubs; S-M1, S-M3..S-M5 need a Mac |
| Apple | `apple/`, `apple-ios.yml` | I0a + I0b (`LAB`); Swift tests on Linux, macOS and an iPhone 17 simulator (`SIMULATOR`, `CI (hosted VM)`); lane diff 257 agree, 37 disagree (one cause, F-1), 46 not implemented | no app, network, requester or key storage |
| Ubuntu Touch | `ubuntu-touch/`, `ubuntu-touch.yml` | UT0.1-UT0.7 (`LAB` + `CI-APPROX — NOT DEVICE EVIDENCE`); arm64 clicks for 24.04-1.x/2.x and real-Lomiri QML tests `CI (hosted VM)` | UT0.6 / S-UT1 / DV-UT01 NDV; 26.04 canary fails; `tst_StatusPage.qml` passes only via an xvfb retry |

### 4.2 What asom offers other repos, per platform, and when

| Platform | Offered to another repo's port | When | Evidence |
|---|---|---|---|
| Android | v1 daemon, AIDL `:client` (`RemoteAsom`), `:client-cloud` (`CloudOnly`), `docs/CLIENT_API.md` | today | CI APK; P5-P7 NDV, P6 must be re-run |
| Any JVM host | the frozen HTTP contract via `./gradlew :server:run` (`/v1/chat/completions` SSE, `/v1/completions`, `/v1/embeddings`, `/v1/models`, echo headers; the desktop node scaffold does not serve it) and the pure-JVM `:core:contract` types (no publication is configured in its build file) | today | `LAB` (`docs/CURL_TRANSCRIPT.md`) |
| Linux, Windows, macOS | nothing for apps until the desktop local-app API (CD-28, `asom pair-app` CD-29) at M2, under D25(b) + D14 part B. Until then the node serves only the owner CLI and the scaffold listens on nothing | M2 | PLAN |
| iOS / iPadOS | AsomKit `RemoteMesh` (CD-10A), in-process, one home lender; each app keeps its own ledger and keys; no shared daemon or key transfer. asom's planned iOS app sets `SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD = NO` (C10, a sketch in `platforms/ios.md`; no Xcode project exists), so the program's "Designed for iPad" Mac reach would not cover it; asom's docs are silent on other apps | M4, at M1b if D24(a) | PLAN (only the Swift conformance lane exists) |
| Ubuntu Touch | nothing as a service, ever (C6); a UT app wanting inference needs its own in-app requester. `ut-host`, `asom-ut-ctl/1` and the Clickable/jlink pipeline are a reusable template | template now | `LAB` + `CI-APPROX` |

## 5. Tier and sequencing

**Tier A (own program)**, matching the master's §5 row: asom is the shared router and the only repo with a reviewed
program and CI-green scaffolds on all five targets. Execution is owner-gated, not engineer-gated: by AD-1 nothing ships
before V1-close, v1.1, v2, mesh-1, v2.5, v3 and the rest of v4, and `app/` stays untouched until the RedMagic validation
closes. The master does not re-sequence asom (its §7); it records where asom's milestones fall: P-0 S1/S2 continue
untouched; P-UT a UT-0; P-UT b UT-1 (own gates); P-LX DL4-DL7 and the M2 API (no asom row in that wave); P-iOS AsomKit
I1-I3; P-mac MC3 onward; P-win W3-W7.

Repo-local gates before any wave: V1-close (`QA_V1.md`, `DEVICE_CHECKLIST_P5`-`P7`, P4 real-key smoke); AD-1..AD-6
ratified; D5, D23, D22 ruled, then D25(b), D14 A/B, D24 (master OQ-7d); PR #1 merged or split per AD-3. Engineer-only
work left (`PROGRESS.md`): `:mesh-proto` wave 3, `REVIEW_ROUND3.md` disposition, design brief r4, final review.

## 6. Work breakdown (indexed, not re-planned)

Steps, gates and expected outputs are PP's; this indexes them. The tracks already satisfy master rules R1-R4 through
PP P1-P4. This delegate adds no steps, directories or workflows.

| Target | Ordered steps | Directory · workflow | Verifiable in the build container? |
|---|---|---|---|
| Ubuntu Touch | UT0.1-UT0.7 (built) → S-UT1 (DV-UT01) → UT1.1-UT1.3 (PP §7) | `ubuntu-touch/` · `ubuntu-touch.yml` | JVM host tests only; Clickable is CI-only; S-UT1 is the owner's |
| Linux | DL0-DL3 (built) → DL4-DL5 → DL6-DL7 → M2 API (PP §3) | `desktop/{node-core,node,packaging/linux}` (`native/` planned at DL4) · `desktop-linux.yml` | JVM compile and tests (`CONTAINER-BUILD-ONLY`); deb/rpm, systemd, GPU are CI or NDV |
| iOS / iPadOS | I0 (built) → I1-I3 → I4 (PP §6) | `apple/` · `apple-ios.yml` | SwiftPM on Linux when a Swift toolchain is installed (I0a `LAB`, swift-crypto lane); Xcode, simulator and CryptoKit are CI-only |
| macOS | MC1-MC2 (built) → MC3-MC8 → MC9 at M2 (PP §5) | `desktop/packaging/macos` · `desktop-macos.yml` | JVM side only; Swift helper and signing are hosted-macOS or NDV |
| Windows | W0-W2 (built) → W3-W7 → W8 (PP §4) | `desktop/packaging/windows` · `desktop-windows.yml` | Linux-runnable tests only (114 of 141); Windows ITs are hosted-VM |

## 7. Shared foundation this repo consumes or provides

- **F4 asom-client (asom's contract in, the client hosted outside).** The constellation consumes asom through a KMP
  client in Shared-Libraries-asoc, not in this repo, so asom's no-KMP rule (brief §13, PP P8, D17) stands: no KMP plugin
  enters an asom build and no asom module becomes multiplatform. The client takes the `InferenceClient` interface,
  `docs/CLIENT_API.md` and the W00-W03 vectors from asom, never a second definition (`client/.../AsomChat.kt` and
  `AsomHttp.kt` are the reading starting point). It is unusable off Android until M2 (D25(b)); iOS apps embed AsomKit.
  No D17 re-escalation trigger is recorded as fired (the master records Kotlin Swift export as Alpha). OQ-17 asks the owner to
  confirm the ban is asom-local.
- **F7, F9, F10, F11 (provided as seeds, not consumed).** The Clickable, cross-jlink and AppArmor-approximation pipeline
  and `asom-ut-ctl/1` (F7); the isolation check and hosted lanes (F9); jlink/nfpm, WiX and winget stubs and the S-M3
  entitlement verdict (F10); evidence labels and `DEVICE_CHECKLIST_{UT,LINUX,MACOS,WINDOWS}.md`, no iOS one yet (F11).
  asom itself supplies the S-UT1 verdict. Adoption and ownership under PP P1 are OQ-24.
- **F1, F2, F3, F5, F6 (not consumed).** asom vendors Hyle tokens by design (`app/.../hyle/tokens/HyleTokens.kt`);
  `ubuntu-touch/qml/tokens/Tokens.qml` and `ubuntu-touch/tools/check_tokens.py` (violet/cyan plus shape) are a reference for F1's `qml`
  generator, which should take values from Hyle's token source, not a consumer's hex. No crash-report module (C15), no
  KMP (so no kmp-conventions plugin), own per-platform seams (`DesktopPlatform`, key tiers); custody tiers for a
  non-Android BYOK vault are OQ-22.
- **F8 (not consumed before D-v2).** `llama.cpp.pin` does not exist yet; it is planned for DL4 and v2 P1 (design C11).
  Until asom creates it, F8's "asom's pin is the source" has nothing to mirror. Whether DL4, MC5 and W3 take F8
  prebuilts or build from the pin is open (OQ-24); prebuilts need a D23 row.
- **Proposals, not decisions** (for the `ASOM_MESH_DESIGN.md` §10 register under the next free D-number, only if the
  owner rules). **CP-1:** asom grants F4 read-only consumption of `docs/CLIENT_API.md`, `core/contract` and the W00-W03
  vectors at a pinned SHA, changes only as additive owner-signed requests (rule I-9). **CP-2 (optional):** after
  V1-close, extract the `android.*`-free transport in `client/` into a pure-JVM `:core:client-http` so F4 has one
  definition; it needs a D23 row and touches an Android module.

## 8. Open questions for the owner

| # | Question | Blocks | Master OQ |
|---|---|---|---|
| 1 | Ratify or overrule AD-1..AD-6, chiefly AD-1's order and Amendment 3's relaxation of Invariant 1 | every wave | OQ-7d |
| 2 | Rule D5 and D23 (they gate v1.1 and every registry row, including JNA and the promoted modules) | v1.1, then all later phases | OQ-7d |
| 3 | D22 publisher identities: Android verification; Windows SignPath, Azure or OV; one permanent Apple Team ID. The master (§4.5) records Azure Artifact Signing as unavailable to an individual in India, leaving SignPath or OV (not verified in asom's docs) | all signing and release | OQ-3, OQ-2, OQ-4 |
| 4 | D25: are the owner CLI and the UT app's own screen "not apps" (a), and is the desktop local-app API approved for M2 (b)? | whether any other app can use asom on Linux, Windows or macOS | OQ-7d |
| 5 | D14 (Amendment 5): approve non-Compose UIs and non-Android key wrapping | first iOS app, the UT UI, non-Android BYOK | OQ-7d, OQ-22 |
| 6 | D28 inventory: the Dell's OS and GPU, an Apple-silicon Mac, a UT device (Fairphone 4/5, Volla), Headscale | which devices lend in mesh-1; whether UT and macOS have hardware | OQ-1, OQ-5 |
| 7 | D24 (requester placement), D15, D16, D17 (does the KMP ban stay; is a second-language router ever acceptable) | I1-I3, UT-1 | OQ-7d, OQ-17 |
| 8 | Run V1 device validation on the RedMagic (P6 must be re-run) and the P4 real-key smoke; then the shape-deciding spikes S-UT1, S-M1/S-M3, S-W1, S-A2/S-A9/S-A10/S-A12 | every later phase; UT-1 vs the Rust fallback; macOS and Windows key tiers; the iOS requester | OQ-1, OQ-2 |
| 9 | Merge PR #1, or split it per AD-3 (`main` has only the P0 skeleton) | "on main" status; a stable SHA for F4 | none |
| 10 | Disposition `REVIEW_ROUND3.md`; authorise design brief r4 and the final review. Investigate the 26.04 canary failure and the AppArmor approximation's denials now, or leave them to the device checklist? | design closure; UT confidence on the next series | none |
| 11 | Confirm the maintainer identity in `ubuntu-touch/manifest.json.in` (now `asom-maintainers@example.invalid`) and the OpenStore or F-Droid intent (UT-D3). Existing identifiers (`xyz.mdhv.asom.ut`, `xyz.mdhv.asom`) predate program rule R11 and this plan changes none; do they need NAMES.md rows? | any UT listing | OQ-4, OQ-25 |
| 12 | Should asom's packaging and CI patterns become shared constellation templates, and who owns them under PP P1? Approve or reject CP-1 and CP-2 (§7) | F7, F9, F10, F11; F4's mechanism | OQ-24, OQ-7d |
| 13 | 14 lines across 6 asom workflows mention `upload-artifact` (3-7 day retention where set; `cleanup-artifacts.yml` runs every 6 hours). Program rule R6 and the exhausted account artifact storage may conflict; whether they still succeed is unknown. This plan edits no workflow | lane-diff and package jobs | OQ-20 |

## 9. Sources read

Reader profile (2026-10-06, head `4cf2ad0`): `CLAUDE.md`, `README.md`, the two briefs, `PROGRESS.md`, root and module build
files, `gradle/libs.versions.toml`, `docs/CLIENT_API.md`, `docs/design/mesh/{OWNER_DIRECTIVES_2026-09-30,OWNER_BRIEF,
PLATFORM_PLAN,ASOM_MESH_DESIGN,LAB_SPEC,TOOLCHAIN_NOTES,REVIEW_ROUND3}.md` and `platforms/*.md`, the `lab/`, `desktop/`,
`apple/`, `ubuntu-touch/` READMEs, docs and manifests, the seven workflows, the three `*_BASE_SHA` files. Re-read here:
`CLAUDE.md`, roadmap §14, PP §0, §1, §9-§12, the owner directives, the `OWNER_BRIEF.md` decision table, D17, D25 and
milestone lines of the design, platform effort tables, the `PROGRESS.md` hosted-CI section, `ubuntu-touch/ERRATA.md`,
and an `upload-artifact` grep of `.github/workflows/`.

## Owner rulings and the proposed line (added 2026-10-07)

Status: PLAN. Nothing here is built, run on a device, signed or submitted. The program-level plan is Personal-Tracker `PORTING_PROGRAM.md` ([PR #10](https://github.com/mbaliga/Personal-Tracker/pull/10)), which holds the owner's rulings and section 5A, the proposed port / no-port line. The cells, estimates and open questions above are this repo's original plan and are unedited. Where the owner has since answered a question, the answer is below. Section 5A is a proposal; the owner has not yet confirmed it.

### Where asystemofmodels sits in the proposed line (program section 5A.3, a proposal)

| Target       | Verdict | Weeks and flags |
| ------------ | ------- | --------------- |
| Ubuntu Touch | port    | 6.5w o          |
| Linux        | port    | 7w o            |
| iOS/iPadOS   | port    | 16.5w o         |
| macOS        | port    | 8w o            |
| Windows      | port    | 8w o            |

Key: `follows` means it ports only as far as the products that depend on it; `exists` means the program reads it as already running there, unverified (finish, verify and sign); flags: `g` gated on a prerequisite, `r` re-estimate or floor, `o` its own program, `s` scope note. The program's P4, P8, P12 and P13 gate whole columns or repos and are not flagged per cell. A port verdict counts the deliverable in the line; where this repo's plan calls a deliverable a reframe (program rule R12) it keeps that label. Tests cited in the reason: (a) the owner said it is needed there; (b) its job is really done on that OS by real users; (c) that OS is where it is sold or its audience is; it has no reason to exist if (x) its surface is absent or untouchable, (y) the capability is forbidden or impossible, or (z) the only form is a thin wrapper or a different product nobody asked for. P-numbers and OQ-numbers refer to the program plan (Personal-Tracker `PORTING_PROGRAM.md`, sections 5A.5 and 8).

Reason: Its own program under asom:D-E (decided 2026-09-30): UT is a foreground-only requester (UT-1), never an unattended lender or a cross-app daemon (UT-2, an optional frontmost lender, is unscheduled); iOS is iPad-first; macOS waits for an Apple-silicon Mac (its macOS node is macOS 15+ and arm64 only, so it cannot be built or tested on the 2015 Intel MacBook Pro). Not re-planned here (program section 4.0).

### Owner rulings that apply here

- **No program ruling changes an asom rule, invariant or decision (program directive I-9: PLATFORM_PLAN.md governs).** The rulings are recorded in Personal-Tracker `PORTING_PROGRAM.md`, section Owner rulings. In particular this section does not change asom's Ubuntu Touch rules (no BYOK keys; a foreground-only requester in UT-1, never an unattended lender or cross-app daemon; 24.04-1.x and 24.04-2.x only, with 26.04 a CI canary; never 20.04), Invariant 4 and the keyStorage rule, or the no-KMP rule. The program's focal pre-spike is the program's own S-UT1 (focal) on the owner's device, not asom's S-UT1 / DV-UT01.
- **OQ-31 Mac (2026-10-06 and 2026-10-07):** "Buy a Mac", and on 2026-10-07 an Apple-silicon Mac mini, not yet bought; no Apple device gate is called checkable before then.

### Prerequisites and open questions that touch this repo (program sections 5A.5 and 8)

Prerequisites (program-level; not costed here):

- program P4: A device that can run the 24.04 Ubuntu Touch the program plan targets (the owner's OnePlus 6 is read as 20.04-only)
- program P8: An Apple-silicon Mac (OQ-31: a Mac mini chosen on 2026-10-07, not yet bought)

Owner questions in the program register that concern this repo (status as of 2026-10-07):

- OQ-1 (ruled): Ubuntu Touch device: an input to asom's D28 inventory; asom's Ubuntu Touch rules (24.04-1.x and 24.04-2.x only, never 20.04) are unchanged
- OQ-5 (ruled): Hardware stance: a fact for asom's D28 inventory (the Dell's operating system); changes no asom rule
- OQ-7 (open): Repo-local gates, clause (d): the asom ratifications
- OQ-17 (ruled): Toolchain pins: the 'Also' confirmation that asom's no-KMP rule is asom-local is unanswered
- OQ-20 (ruled): CI minutes, storage and repo visibility (this repo is public; the ruling does not answer the artifact-storage point in plan item 13)
- OQ-22 (ruled): Secret custody per platform: asom's keys stay governed by asom (program directive I-9)
- OQ-24 (open): Sharing mechanism for non-Gradle artefacts and prebuilt binaries
- OQ-31 (ruled): CI for App Store builds; which Mac
- OQ-33 (open): Hardware details still open
- OQ-37 (answered in part): A second Ubuntu Touch device; the program focal pre-spike is on a 20.04 device that asom excludes

When the owner confirms or changes the line, this repo's original cells above stay as the engineering detail; only the verdicts and re-costs in program section 5A change.
