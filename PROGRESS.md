# asom build progress

Gate log per ASOM_BUILD_BRIEF.md §11–§12. Rules: no gate is marked passed
without real command output pasted below; blocked items say `BLOCKED(<reason>)`;
device/owner items stay `NEEDS-DEVICE-VALIDATION` / `NEEDS-OWNER-VALIDATION`
until the owner confirms.

## P0 — Bootstrap

- [x] Module skeleton per §4 (12 modules; Android modules conditionally included when an SDK is present, so pure-JVM builds work on a bare JDK)
- [x] Version catalog (`gradle/libs.versions.toml`), wrapper 8.14.3
- [x] LICENSE (Apache-2.0, canonical text), README stub, CLAUDE.md
- [x] CI workflow (`.github/workflows/ci.yml`: `jvmTest` with SDK hidden + `:app:assembleDebug` artifact, Java 17, Gradle cache)
- [x] Brief committed as `ASOM_BUILD_BRIEF.md`
- [x] **Gate: CI green on skeleton** — run #1 (`c1b36dc`) concluded `success`
      (both jobs: JVM tests on bare JDK + debug APK artifact) —
      https://github.com/mbaliga/asystemofmodels/actions/runs/28896705015

Local pure-JVM verification (bare JDK, no Android SDK; session note: Gradle
distribution downloads are proxy-blocked in this environment, so the identical
system Gradle 8.14.3 was used — CI and the Deck use `./gradlew`):

```
$ gradle jvmTest --console=plain
asom: no Android SDK detected — Android modules excluded (pure-JVM mode).
...
> Task :core:contract:test
> Task :server:test NO-SOURCE
> Task :jvmTest
BUILD SUCCESSFUL in 44s
12 actionable tasks: 12 executed

$ gradle -q :server:run
asom server 0.1.0 — placeholder (P3 brings the Ktor CIO server on 127.0.0.1:11435)
```

## P1 — Contract + catalogue

- [x] `:core:contract`: header names, 7 typed error codes (+HTTP status map), `Policy`, `RouteRecord` + echo-header law, `Capabilities`, OpenAI DTOs (responses only — request bodies stay raw `JsonObject` per §5.9 pass-through)
- [x] `:core:catalogue`: §6 schema DTOs, tolerant parser with loud semantic validation
- [x] `fixtures/catalogue.v1.json` — 5 providers (≥3 ✓, one `trainsOnData:true` ✓, llama-3.3-70b priced differently by 3 providers ✓, one `programmaticAllowed:false` for filter tests)
- [x] `:core:inference-api`: `LocalEngine` seam + `NoopEngine` throwing typed `501 LOCAL_ENGINE_ABSENT`
- [x] Golden tests incl. fixture-law tests so the fixture can't rot
- [x] **Gate: `:core:*` tests pass**

```
$ gradle :core:contract:test :core:catalogue:test :core:inference-api:test
BUILD SUCCESSFUL in 12s
# JUnit XML: contract 13 tests / catalogue 13 tests / inference-api 2 tests — 0 failures, 0 errors
```

## P2 — Routing core

- [x] Deterministic `Router` per §7: resolve → filter (key ∧ serves ∧ programmatic ∧ no-train) → order (cheapest/fastest/best-reasoning/auto) → cooldown skip; typed errors on every failure path
- [x] `CooldownRegistry` circuit-breaker FSM: 30 s → 15 min exponential backoff, success resets, streak survives expired deadlines
- [x] `LatencyTracker` EWMA (persistable snapshot/preload seam)
- [x] `X-Asom-Fallback` restricts + orders; `local-only` → typed `LOCAL_ENGINE_ABSENT`
- [x] Property tests (seeded, 200 iters × 6 laws): filter soundness, ordering laws, auto-band law, determinism, total-order tie-breaks
- [x] JaCoCo branch-coverage verification wired into `check` (≥90% rule)
- [x] **Gate: `:core:routing:test` + coverage**

```
$ gradle :core:routing:check
> Task :core:routing:test
> Task :core:routing:jacocoTestCoverageVerification
BUILD SUCCESSFUL in 15s
# 38 tests, 0 failures. Branch coverage (jacocoTestReport.xml):
#   Router 44/45 = 97.8% · Router.Companion 2/2 = 100% · CooldownRegistry 16/16 = 100%
```

## P3 — Server (desktop-runnable)

- [x] Ktor CIO server; bind host hardcoded to `127.0.0.1` (no config surface for `0.0.0.0` — §1.2 by construction)
- [x] All §5.2 endpoints: `/v1/chat/completions` (SSE), `/v1/completions` shim (incl. stream translation), `/v1/embeddings`, `/v1/models` (concrete tagged by provider + virtual tagged `asom-virtual`), `/admin/health`, `/admin/catalogue` (merged + live cooldown state, key PRESENCE only)
- [x] Auth middleware: in-memory token registry, SHA-256-hashed tokens, constant-time compare; `NOT_PAIRED` / `TOKEN_REVOKED`
- [x] `RoutePipeline`: attempt loop with circuit breaker; virtual selectors resolved to concrete model ids before upstream dispatch; §5.9 `include_usage` injection
- [x] `FakeDriver` with per-provider failure injection (429/5xx/4xx) exercising breaker + fatal-relay paths
- [x] Echo headers + ledger row from the same `RouteRecord` (§1.9) — asserted in tests; streams commit headers pre-body, cost lands in the ledger (usage-based)
- [x] 23 JVM integration tests against a real CIO server on 127.0.0.1 (auth, routing, SSE, shim, embeddings, breaker, admin, key-never-leaks, ledger-row-per-request)
- [x] **Gate: `./gradlew :server:run` + committed curl transcript** → `docs/CURL_TRANSCRIPT.md` (captured live)

```
$ gradle :server:test
BUILD SUCCESSFUL — 23 tests, 0 failures (server/build/test-results)

$ gradle -q :server:run   # + curl transcript captured live, see docs/CURL_TRANSCRIPT.md
asom server 0.1.0 on http://127.0.0.1:11435
dev bearer token: asom-dev-token
providers (fake drivers): openrouter, groq, trainy-ai, anthropic, webchat-only

$ gradle jvmTest
BUILD SUCCESSFUL in 9s
```

## P4 — Real drivers

- [x] `OpenAICompatDriver(baseUrl)` (OkHttp): verbatim body pass-through, bearer auth, byte-level SSE pass-through, 429/5xx retryable vs 4xx fatal
- [x] `AnthropicDriver`: §5.9 text-chat subset translation (system extraction, `stop`→`stop_sequences`, `max_tokens` default), response + usage mapping, upstream-SSE → `chat.completion.chunk` re-mapping; untranslatable fields → typed `501 UNSUPPORTED_BY_DRIVER` **before any bytes leave** (asserted: mock got 0 requests)
- [x] usage→cost mapping shared with P3 path (`usageCost`)
- [x] Desktop real mode: `ASOM_REAL_DRIVERS=1` + `ASOM_KEY_<PROVIDER_ID>` env keys
- [x] **Gate: mock-server JVM tests green** (MockWebServer)
- [ ] Real-key smoke curls from the Deck — `NEEDS-OWNER-VALIDATION` (owner task §14.3; run `ASOM_REAL_DRIVERS=1 ASOM_KEY_OPENROUTER=… ./gradlew :server:run` and repeat the transcript)

```
$ gradle :server:test
BUILD SUCCESSFUL — 33 tests, 0 failures
# integration 23 · AnthropicDriverTest 6 · OpenAICompatDriverTest 4
```

## P5 — Android shell

- [x] `:vault`: `DataKeyVault` (Keystore-wrapped data key, AES-256-GCM, one active key per provider) with JVM-testable seams; `KeystoreWrappingCipher` (StrongBox → TEE fallback); Room ciphertext store; redaction-law unit test (§8)
- [x] `:ledger`: Room `route_log` + `verbose_log` (24h TTL query ready), `RouteRecord` ↔ entity lossless mapping tests
- [x] `:app`: `AsomService` FGS (`specialUse` + manifest property) hosting the P3/P4 server with vault-backed keys, Room ledger sink, real drivers; fixture catalogue synced into assets at build time (single source of truth)
- [x] Dashboard (placeholder-functional, token seam per §1.7): Status (start/stop, endpoint, dev token), Keys (the ONLY key write path, §1.4), Ledger (live rows). §1.6: violet/cyan + shape/label redundancy everywhere
- [x] CI extended: `:vault` + `:ledger` unit tests before APK assembly
- [ ] **Gate: CI APK artifact** — pending CI run on this push
- [ ] On-device checklist — `NEEDS-DEVICE-VALIDATION` → `docs/DEVICE_CHECKLIST_P5.md`

```
$ gradle jvmTest   # pure-JVM side unaffected
BUILD SUCCESSFUL
# Android modules compile in CI (no local SDK in this session — by design, §3)
```

## P6 — Pairing + client + §10A fallback

Brief updated mid-phase by owner (2026-07-08): §10A consuming-app integration,
`:client-cloud` module, `InferenceClient` in contract, reworded Invariant 1;
`ASOM_ROADMAP_BRIEF.md` committed alongside (v1.1→v4 — NOT started, per its
own entry criteria).

- [x] `docs/CLIENT_API.md` pinned (rewritten to the §10A surface) before implementation
- [x] `:pairing`: AIDL service (action `xyz.mdhv.asom.PAIR`), `Binder.getCallingUid()`-verified identity (uid → package + signing-cert SHA-256), Room store (token **hashes** only, constant-time compare), pending/approve/deny/revoke/remove FSM, one-shot raw-token delivery
- [x] `:app`: consent `PairingActivity` (verified identity only — nothing from extras; dismiss keeps PENDING), Hotspot tab (list/revoke/remove), `DiscoveryProvider` (§5.1), pairing-backed `TokenValidator` wired into the server auth path
- [x] `:core:contract`: §10A.1 `InferenceClient` + `RequestOptions`/`InferenceResponse`/`InferenceStream`
- [x] `:client`: `AsomDiscovery`, `AsomPairing` (BAL landmine handled: consent launched from the client's foreground context), `AsomChat` transport, `RemoteAsom`, `FallbackResolver` (RemoteAsom → Embedded(v2 seam) → CloudOnly, re-evaluated per call), `InventoryProvider`/`SuiteInventory` (§10A.4), `NudgePolicy` (§10A.5) + 6 unit tests of the anti-spam laws
- [x] `:client-cloud`: `CloudOnly` impl + its own `ClientVault` (Keystore AES-GCM + prefs ciphertext) — §10A.3 keys never transfer
- [x] `:sample-client`: full tier proof — pair, stream via resolved tier, CloudOnly key entry, nudge decision display; suite package list = OWNER-FILL (empty ⇒ nudges disabled)
- [x] CI: `:client` unit tests + both APKs (`asom` + `sample-client`)
- [x] Local full build green (JVM tests + all Android modules + both APKs; local SDK installed in-session at /opt/android-sdk)
- [ ] **Gate: device checklist** → `docs/DEVICE_CHECKLIST_P6.md` — `NEEDS-DEVICE-VALIDATION` (incl. THE §10A item: uninstall asom mid-session → graceful CloudOnly fallback)

```
$ ANDROID_HOME=/opt/android-sdk gradle jvmTest :client:testDebugUnitTest \
    :client-cloud:assembleDebug :app:assembleDebug :sample-client:assembleDebug
BUILD SUCCESSFUL in 2m 30s
```

## P7 — Storage

- [x] `:storage`: `ModelStore` (`filesDir/models/{modelId}/` layout, streaming SHA-256 verify, bytes-on-disk accounting)
- [x] `DownloadWorker` (WorkManager, resumable, wifi-only-by-default constraint) → verify → typed failure on hash mismatch (deletes partial file)
- [x] Room `model_download_state` (status/progress/pinned) + `ModelDownloadManager` facade (download/cancel/pin/evict/stats)
- [x] Download completion writes a ledger row (`egress: download`) via a process-wide bridge so `:storage` stays contract-only otherwise
- [x] `:app`: `ModelsProvider` (§5.8) — `openFile` mode `"r"` only, caller UID verified against the pairing registry (self-process always allowed); Models dashboard tab (download/progress/pin/evict/total storage, §1.6 shape+label states)
- [x] `:sample-client`: opens a model fd via the provider — proves the second-app read path structurally (real device needed to prove kernel dedup)
- [x] 3 JVM unit tests for SHA-256 verify (match/tamper/case-insensitive)
- [x] CI: `:storage` unit tests added
- [x] Local full build green (JVM tests + `:storage` unit tests + both APKs)
- [ ] **Gate: device checklist incl. second app reading a model fd** → `docs/DEVICE_CHECKLIST_P7.md` — `NEEDS-DEVICE-VALIDATION`

```
$ ANDROID_HOME=/opt/android-sdk gradle jvmTest :storage:testDebugUnitTest \
    :app:assembleDebug :sample-client:assembleDebug
BUILD SUCCESSFUL in 1m 56s
# storage: 3 tests, 0 failures
```

## P8 — Watched-object polish

- [x] Echo headers ↔ ledger row asserted end-to-end from the SAME `RouteRecord` (§1.9) — `AsomServerIntegrationTest` (P3), still green
- [x] Hotspot tab (list/revoke/remove) — built in P6, exercised again in the consolidated QA script
- [x] Verbose mode + 24h TTL: `verbose_log` Room table (P5), `VerbosePurgeWorker` (hourly WorkManager job, TTL 24h), Status tab toggle (default OFF) that schedules/cancels the purge worker
- [x] Quick-settings tile (`AsomTileService`): start/stop, reflects live running state
- [x] Boot-start toggle: `Settings.bootStartEnabled` **defaults OFF**; `BootReceiver` only starts the service when explicitly enabled — nothing runs unless the user starts it (or opts in)
- [x] Notification surfaces live state: `ActivityListener` seam added to `AsomServerConfig` (default no-op — existing P3/P4 tests unaffected), wired in `AsomService` to show idle / "serving a request…" / "streaming via `<provider>`…"; Status tab mirrors the same state
- [x] `QA_V1.md` committed — consolidated manual script across P0–P8; all device items `NEEDS-DEVICE-VALIDATION`, real build/test output pasted for everything executable in-session
- [x] Local full build green (JVM tests + all Android modules + both APKs) after the polish changes
- [ ] **Gate: execute QA_V1.md on hardware** — `NEEDS-DEVICE-VALIDATION`

```
$ ANDROID_HOME=/opt/android-sdk gradle jvmTest :app:assembleDebug :sample-client:assembleDebug
BUILD SUCCESSFUL in 30s
```

## Hyle design-system adoption (token seam)

Owner-directed adoption of the Hyle Design System (`mbaliga/Hyle-Design-System`,
`dev.aarso:hyle:0.2.0`) through the existing token-contract seam. Scope locked by
the owner: **tokens only** (no material/`Pulse` layer — honors §1.7), **vendor**
the generated tokens (no Gradle/network dependency — honors §1.1–§1.3 and keeps
the pure-JVM path intact), and **keep the violet/cyan pair** with the radiant hue
standardized on Hyle's cold-cyan (§1.6-safe; Hyle's radium-green + red/green
feedback hues never bound).

- [x] Vendored Hyle token surface into `:app` — `dev/aarso/hyle/tokens/HyleTokens.kt` (verbatim generated copy) + `dev/aarso/hyle/Argb.kt`, provenance + re-sync steps in `dev/aarso/hyle/README.md` (source commit `0fade5b`)
- [x] Token seam re-pointed: `AsomTokens` now sources every colour from `HyleSeam` (raw ARGB) → violet `#8E7BFF` (`accent.violet`), cyan `#35E0FF` (`provenance.cloud`), field/ink neutrals. Field names unchanged → all 6 screens + both Activities compile untouched
- [x] §1.6 guard: `HyleSeamTest` (pure-JVM, Compose-free) asserts violet+cold-cyan are the only meaning-bearing hues and that no seam colour is a banned red/green hue (radium-green, feedback success/danger, smog)
- [x] CI: `:app:testDebugUnitTest` added to the Android job so the §1.6 guard runs on every push
- [x] Spec synced: `CLAUDE.md` §6 + `ASOM_BUILD_BRIEF.md` §6 cyan updated `#08FED5` → `#35E0FF` (Hyle `provenance.cloud`)
- [ ] **Gate: CI green (`:app:testDebugUnitTest` + `:app:assembleDebug` APK)** — verified on CI; local `:app` build not runnable in this session (no Android SDK — pure-JVM-only environment, brief §3)
- [ ] **Gate: on-device visual confirmation of the Hyle palette on the dashboard** — `NEEDS-DEVICE-VALIDATION`

```
# Local environment has NO Android SDK (ANDROID_HOME unset), so `:app` is excluded
# from the Gradle build (settings.gradle.kts) and cannot be assembled here — CI is
# the build path per brief §3. Vendored token files are pure Kotlin; the §1.6 guard
# runs as a standard :app unit test (same kotlin.test + useJUnitPlatform wiring as
# :vault, which is green in CI). CI output to be pasted from the branch run.
```

---

## Production-readiness audit + remediation

Owner-directed hardening pass after P0–P8. Every phase had passed its own
gate, but no part of the codebase had been read adversarially. A 15-dimension
audit (the nine invariants individually, HTTP/SSE correctness, driver
translation fidelity, router determinism, the fd-sharing provider's security
surface, the client SDK contract, concurrency/resource lifetime, test-suite
honesty, and build/CI law) raised 104 candidate defects. Each was then put to
three independent adversarial reviewers — reproduction, spec-grounding, and
production-impact — and kept only on a majority verdict.

**71 confirmed** (15 critical, 28 high, 26 medium, 2 low); 33 refuted. All 71
are fixed across the commits below. 51 were upheld unanimously.

What the audit found, in order of seriousness:

- **The egress ledger — the artifact this product exists to provide — was
  under-reporting, and on one path asserting the opposite of the truth.**
  Failover wrote no row for providers that had already received the prompt (in
  the committed fixture, that includes the one provider with
  `trainsOnData: true`). `ALL_PROVIDERS_COOLING` recorded `egress=local`
  — "nothing left the device" — for a request transmitted to every candidate,
  and drove the echo headers from the same falsified record, so §1.9 held while
  both surfaces were wrong. The streaming path appended its row last, after the
  response completed, so a client disconnect — routine on mobile — lost it
  entirely. Android's sink only enqueued, so a process kill lost rows for
  egress that had already happened.
- **A BYOK key could reach a response body** (§1.4). OkHttp redacts
  `Authorization` in header-validation exceptions but not `x-api-key`, and the
  error envelope echoed raw exception messages.
- **A revoked app could silently un-revoke itself** over AIDL and reuse its old
  token, non-interactively, contradicting §5.7. `:pairing` — the device's only
  auth authority — had no test source set and was never run by CI.
- **`:client` shipped no AndroidManifest**, so no `<queries>`: on Android 11+
  both discovery paths fail silently and the RemoteAsom tier is dead in the
  field, indistinguishable from "asom is not installed". This is why P6's
  device checklist could look green.
- **Model files were served to other apps before SHA-256 verification**, with a
  path-traversal hole in the same provider.
- **Pin/Evict crashed the dashboard** (blocking Room on the main thread), and
  **ledger export did not exist** — the one sanctioned egress path in v1 (§9)
  was dead code.
- **The routing property tests could not catch a regression**: exceptions were
  swallowed so iterations skipped silently, and the ordering oracles were the
  implementation's own key functions, reducing each law to `sortedBy(f)` is
  sorted by `f`.

Verbose ledger mode (§2, §9) was inert — the setting, table, DAO and purge
worker existed but nothing wrote a row — and is now implemented end to end.
Capture policy: request bodies and non-streamed response bodies are stored;
streamed responses store metadata only, because buffering an SSE response to
log it would be a memory hazard and would change streaming behaviour.

```
$ ./gradlew jvmTest --rerun-tasks
BUILD SUCCESSFUL in 11s
21 actionable tasks: 21 executed
# AnthropicDriverTest: 11 tests, 0 failures
# AsomServerIntegrationTest: 41 tests, 0 failures
# AsomTest: 2 tests, 0 failures
# CatalogueGoldenTest: 13 tests, 0 failures
# ContractFreezeTest: 5 tests, 0 failures
# CooldownRegistryTest: 7 tests, 0 failures
# LatencyTrackerTest: 7 tests, 0 failures
# LedgerDurabilityTest: 2 tests, 0 failures
# NoopEngineTest: 2 tests, 0 failures
# OpenAICompatDriverTest: 5 tests, 0 failures
# RouteRecordTest: 6 tests, 0 failures
# RouterPropertyTest: 10 tests, 0 failures
# RouterTest: 22 tests, 0 failures
# VerboseRedactorTest: 6 tests, 0 failures
# TOTAL: 139 tests, 0 failures   (was 100 before the audit)
```

- [x] Pure-JVM suite green locally, 139 tests
- [x] Pure-JVM-first law intact — no `android.*` in `:core:*` or `:server`
- [x] `:pairing` and `:client-cloud` unit tests added to the CI Android job
      (neither module was previously exercised by CI at all)
- [ ] **Gate: the audit's device-observable fixes re-run on hardware** —
      `NEEDS-DEVICE-VALIDATION`. `QA_V1.md` §§4, 6, 7, 11, 12 cover them.

> The Android half of this remediation (`:app`, `:storage`, `:ledger`,
> `:vault`, `:pairing`, `:client`, `:client-cloud`, `:sample-client`) was
> written without a local Android SDK — brief §3 — so CI is its first compile
> and its only automated verification. It compiles and its unit tests pass
> there, but no Android change in this pass has been exercised on a device.

---

## v1 build status

P0–P8 all executed to their JVM/CI-verifiable gates, followed by the
production-readiness audit above (71 confirmed defects, all fixed). Code,
tests and CI are green.

Remaining gates are hardware- or owner-dependent: the `NEEDS-DEVICE-VALIDATION`
checklists (P5–P7 + `QA_V1.md`, now extended to cover the audit fixes) and the
P4 real-key cloud smoke (`NEEDS-OWNER-VALIDATION`) — both require the owner's
RedMagic per brief §12. Note that P6's original RemoteAsom results are **not
meaningful** and must be re-run: the `<queries>` defect meant the tier could not
have worked on the test device.

`ASOM_ROADMAP_BRIEF.md` (v1.1→v4) is committed but explicitly **not started**,
per its own entry criteria.

---

## Lab L0.1 gate — 2026-09-30 — LAB (not device evidence)
Commit: 1963166c500d9e83b6b5b076bb3c11263ef3e429 (the pinned base) + the uncommitted `lab-skeleton` working tree   JDK: openjdk version "21.0.10" 2026-01-20 (also Temurin 17.0.20.1+1)   Runner: local container (Ubuntu 24.04, NO Android SDK, no hosted CI run)
Scope: LAB_SPEC L0.1 plus the shared skeleton: `lab/` build isolation, nine empty-shell modules, `:conformance-runner` with families W00, W01, W01b, W02, W03, R04, `lab/tools/xcheck.py`, `.github/workflows/lab.yml`. Spec defects met and the readings taken: `lab/ERRATA.md` (notably ERR-ISO-1, the pinned-base isolation check, and ERR-W01B-1, the streaming header law).

```
$ ./gradlew -p lab labTest --stacktrace --rerun-tasks        (JDK 21.0.10)
    family W00: 7 vectors, 7 pass, 0 fail, 5 proposed-skipped, oracle: self=12
    family W01: 14 vectors, 14 pass, 0 fail, 0 proposed-skipped, proposed-lane 200 run: 200 pass, 0 fail, oracle: self=214
    family W01b: 9 vectors, 9 pass, 0 fail, 1 proposed-skipped, oracle: self=10
    family W02: 9 vectors, 9 pass, 0 fail, 0 proposed-skipped, oracle: self=9
    family W03: 7 vectors, 7 pass, 0 fail, 0 proposed-skipped, oracle: self=7
    family R04: 28 vectors, 28 pass, 0 fail, 0 proposed-skipped, oracle: self=28
BUILD SUCCESSFUL in 25s
46 actionable tasks: 46 executed
(every other family prints `family <F>: not-implemented`; 313 lab tests, 7 skipped = 6 proposed-skipped + the regenerate-only test, 0 failures)

$ JAVA_HOME=<Temurin 17.0.20.1+1> ./gradlew -p lab labTest --stacktrace --rerun-tasks
    (the same six family lines, byte-identical counts)
BUILD SUCCESSFUL in 28s
46 actionable tasks: 46 executed
```

Non-vacuity (law counters, normative vectors only; the run fails on a zero): W00 constants 7, headers-exhaustive 2; W01 echo-map 14, served-by-present 9, served-by-absent 5, cost-present 6, cost-absent 8; W01b exchanges 9, header-equal-nonstream 6, header-commit-stream 3, key-leak-checks 9, no-new-headers 9; W02 exchanges 9, header-equal-nonstream 9, key-leak-checks 9; W03 byte-passthrough 7, parse 7, header-commit-stream 7; R04 plans 28, orders 18, rejects 10, permutation-invariant 186.

```
$ ./gradlew -p lab :conformance-runner:run --args='lines W00,W01,W01b,W02,W03,R04' --quiet | tail -n 3
W03-005 ok
W03-006 ok
W03-007 ok
(274 lines in all: 7 + 214 + 9 + 9 + 7 + 28; e.g. `R04-003 reject ALL_PROVIDERS_COOLING`, `W02-001 reject NOT_PAIRED`)

$ python3 lab/tools/xcheck.py lab/conformance
xcheck W01: 214 agree, 0 disagree
xcheck keys: 2 agree, 0 disagree
xcheck INDEX: 9 agree, 0 disagree
xcheck M01: absent
xcheck M02: absent
xcheck M03: absent
xcheck W05: absent
```

Isolation (LAB_SPEC 2.4; check 4 is pinned to `lab/LAB_BASE_SHA`, see ERRATA ERR-ISO-1):
```
$ lab/tools/isolation.sh --with-sdk
isolation check 1 (root settings never name the lab): 0   expected 0
isolation check 2 (no Android tooling in the lab classpath; ANDROID_HOME='' ANDROID_SDK_ROOT=''): 0   expected 0
isolation check 2 positive control (the same output does list the Kotlin plugin, so a 0 above is not an empty read): 19   expected > 0
isolation check 2 again with ANDROID_HOME=/tmp/tmp.Uejge0BscF (a directory standing in for an SDK): 0   expected 0   [CI-APPROX]
isolation check 3 (no Android module in the lab build): 0   expected 0
isolation check 3 positive control (the mapped root projects and the nine lab modules are listed): 15   expected 15
isolation check 4: base 1963166c500d9e83b6b5b076bb3c11263ef3e429 (from lab/LAB_BASE_SHA), ancestor of HEAD confirmed
isolation check 4: 136 protected files compared byte-for-byte against base 1963166c500d
isolation check 4: OK (shipped tree byte-identical to the pinned base)
law: OK
ISOLATION: all checks passed
$ python3 lab/tools/isolation.py --selftest      -> selftest OK (12 cases: a committed edit of settings.gradle.kts, an untracked file under core/, a deleted file, an edited ci.yml, a flipped exec bit, a non-ancestor base and a missing base all FAIL; honest trees pass)
$ (real worktree negative control) append a line to settings.gradle.kts; python3 lab/tools/isolation.py
isolation check 4: FAILED, 1 difference(s):
  settings.gradle.kts: content differs from base (9d0a648667fa != 225f94e8bfd6)          exit=1   (then restored; exit=0)
$ git diff --exit-code -- core server gradle settings.gradle.kts build.gradle.kts gradle.properties .github/workflows/ci.yml   -> exit 0
$ ./gradlew -p lab :conformance-runner:dependencies --configuration runtimeClasspath | grep -cE 'com\.android|org\.bouncycastle|com\.google\.android'   -> 0
$ grep -rn 'Egress.PEER' lab/ | wc -l   -> 0
```
Check 2 with a real Android SDK present is NOT verified here (no SDK in this container); the `lab-isolation-with-sdk` job in `.github/workflows/lab.yml` is its proof and has not run.

Mutation checks (each run against a deliberately corrupted vector or implementation; every one FAILED as required, then the original was restored byte-for-byte and the suite re-run green):
```
W01-004 X-Asom-Egress cloud -> local     : family W01: 14 vectors, 13 pass, 1 fail ... FAIL W01-004: expected {...local...} but observed {...cloud...}; BUILD FAILED; xcheck W01: 213 agree, 1 disagree
R04-006 first two plan entries swapped   : family R04: 28 vectors, 27 pass, 1 fail ... FAIL R04-006: expected ["openrouter/...","trainy-ai/..."...] but observed ["trainy-ai/...","openrouter/..."...]; BUILD FAILED
W01b-001 response header egress -> local : family W01b: 9 vectors, 8 pass, 1 fail ... FAIL W01b-001; BUILD FAILED
W01b-004 attempt row dropped             : family W01b: 9 vectors, 8 pass, 1 fail ... FAIL W01b-004; BUILD FAILED
W02-006 expected HTTP 501 -> 500         : FAIL W02-006: expected detail {"httpStatus":500,...} but observed {"httpStatus":501,...}
W03-001 expected events truncated        : FAIL W03-001; W00-004 a fifth egress value `peer` expected : FAIL W00-004
W00-005 oracle tag removed               : ENVELOPE PROBLEM: W00-005: oracle '' invalid (every vector must carry an oracle tag)
a vector edited without regenerating INDEX.json : INDEX PROBLEM: wire/W00-constants.json sha256 differs from INDEX.json; xcheck INDEX: 8 agree, 1 disagree
```
Broken implementations (automated negative controls in `NegativeControlsTest`, run in every `labTest`): a provider driver that echoes the API key makes the key-leak law fail (W01b and W02); a driver that corrupts one byte of every SSE chunk makes W03 byte-pass-through fail; synthetic exchanges with a wrong Served-By, a wrong Egress, a missing cost header, a cost header on a stream, a new `X-Asom-Failover` header or no ledger row each fail the Invariant 9 laws; an empty family and an unexercised law are reported vacuous.

Root build unchanged:
```
$ ./gradlew jvmTest --rerun-tasks --stacktrace
BUILD SUCCESSFUL in 18s
21 actionable tasks: 21 executed
$ find core server -path '*/build/test-results/test/*.xml' -print0 | xargs -0 grep -ho 'tests="[0-9]*"' | tr -dc '0-9\n' | awk '{s+=$1} END {print s}'
root tests: 139   (baseline: 139)
```
Observed while measuring the baseline (not caused by the lab, root tests untouched): on the first cold full `./gradlew jvmTest --rerun-tasks` the root test `AsomServerIntegrationTest.a stream with no usage still bills output tokens heuristically` failed once (`NoSuchElementException: List is empty`); it passed on every later run (3 further `:server:test` runs, 2 further full runs). It reads the ledger immediately after a streamed response, but the server writes a stream's row after the last byte is sent (ERRATA ERR-W01B-3). Finding for the owner.

Oracle status: self-oracled (no independent implementation has agreed yet). `xcheck.py` and the Kotlin runner were written in one session and never clear the tag.
Not verified here: hosted-runner behaviour of `lab.yml` (never run; action SHAs resolved by `git ls-remote`, not executed); Windows/macOS lanes (later tracks); Android SDK present (CI-only); W01b-reach and W00-100..104 (proposed, need the L0.4 lab types).
Result: PASSED (L0.1 as amended by ERRATA; local LAB evidence only)


---

## Apple lane I0a gate (Swift: AsomJSON, AsomDSSE, asom-conformance) — 2026-09-30 — LAB (not device evidence)

Base: `1963166c500d9e83b6b5b076bb3c11263ef3e429` plus an uncommitted working tree (`apple/`, `.github/workflows/apple-ios.yml`, this entry).
Runner: local container, Ubuntu 24.04 x86_64, Swift 6.1 (swift-6.1-RELEASE), swift-crypto 4.5.2 (resolved), XCTest. **No Xcode, no macOS, no Docker daemon, no GitHub Actions run.**
Scope: half I0a only. `AsomManifest` and `AsomBenchCore` are not built; the vectors are the **r0** generation (confVersion 0.1.0), not the r3 set.
Errata and every conservative reading: `apple/ERRATA.md`. Boundary and evidence labels: `apple/README.md`.

```
$ export PATH=/opt/swift/usr/bin:$PATH; rm -rf apple/.build; swift build --package-path apple      (clean build, no warnings printed)
Computed https://github.com/apple/swift-crypto.git at 4.5.2 (1.91s)
[514/515] Linking asom-conformance
Build complete! (66.19s)

$ swift test --package-path apple
Test Suite 'debug.xctest' passed at 2026-09-30 07:48:09.782
	 Executed 49 tests, with 0 failures (0 unexpected) in 1.522 (1.522) seconds
Test Suite 'All tests' passed at 2026-09-30 07:48:09.782
	 Executed 49 tests, with 0 failures (0 unexpected) in 1.522 (1.522) seconds
   (AsomJSONTests 12, AsomDSSETests 30, AsomConformanceTests 7; 0 skipped)

$ same tests with swift-crypto pinned exact 3.15.1 (a copy of the package under the scratchpad; Package.resolved showed 3.15.1)
	 Executed 49 tests, with 0 failures (0 unexpected) in 1.387 (1.387) seconds
```

Non-vacuity: every table test asserts that it ran exactly as many cases as the table holds; the parser reject table asserts each of the six error classes was exercised;
the envelope reject table asserts that each of the 20 DSSE-layer reject codes was exercised at least once; the vector test pins 37 = 14 decided + 20 passed-DSSE-layer + 3 retired
and the exact set of 12 decided codes; the JCA comparison pins 35 agreeing lines and exactly two named differences.

```
$ swift run -q --package-path apple asom-conformance lines M01,M02,M03,M05,M06     (no lab/conformance: falls back to docs/design/mesh/manifest-vectors)
vectors: .../docs/design/mesh/manifest-vectors                      [stderr]
M01: not-implemented in half I0a                                     [stderr]
M02: 9 vectors, 0 printed, 7 need steps 11+ (half I0b, not printed), 2 retired by r3
M03: 28 vectors, 14 printed, 13 need steps 11+ (half I0b, not printed), 1 retired by r3
M05: not-implemented in half I0a
M06: not-implemented in half I0a
M03-101 reject SIGNATURE_INVALID
M03-102 reject SIGNATURE_ENCODING
M03-103 reject KEY_NOT_PINNED
M03-104 reject NON_CANONICAL
M03-105 reject DUPLICATE_KEY
M03-106 reject NON_INTEGER_NUMBER
M03-113 reject PAYLOAD_TYPE_UNSUPPORTED
M03-114 reject PAYLOAD_TYPE_UNSUPPORTED
M03-117 reject SIGNATURE_COUNT
M03-118 reject SCHEMA_MAJOR_UNKNOWN
M03-123 reject TRAILING_DATA
M03-125 reject ENCODING
M03-127 reject KEY_NOT_PINNED
M03-128 reject MALFORMED_JSON

$ swift run -q --package-path apple asom-conformance check M02,M03 | tail -1
checked 37, disagreements 0
   (each verdict compared with the vector's own expected code; a vector whose expected code is raised at step 11 or later must pass steps 1 to 10)

$ swift run -q --package-path apple asom-conformance sigcheck > swift.sig
$ grep sigValid docs/design/mesh/manifest-vectors/crosscheck.out > jvm.sig       (the JCA VerifyDsse column: signature layer only)
$ diff jvm.sig swift.sig                                                         (37 lines each)
32c32
< M03-123 sigValidUnderKey1=true
---
> M03-123 skipped (TRAILING_DATA)
34c34
< M03-125 sigValidUnderKey1=false
---
> M03-125 skipped (ENCODING)
diff exit 1
```

The JCA `crosscheck.out` column is a **signature-layer** verdict, not `ok|reject` lines; no JVM lines file exists until the lab's runner does, so this is the diff that was possible.
35 of 37 lines are identical. The two differences are expected and explained (ERRATA E-13): M03-123 (trailing data; the regex-based JCA check never parses the container, the same
difference the design spike found) and M03-125 (non-canonical base64: a lenient decoder gets a different `s` and says `false`, this lane refuses it as `ENCODING` first).

```
$ python3 -c "import yaml; yaml.safe_load(open('.github/workflows/apple-ios.yml'))"    -> parsed; jobs: root-unchanged, apple-swift-lane, ios-package-sim
$ actionlint 1.7.7 .github/workflows/apple-ios.yml                                       -> no findings, exit 0
```

Mutation checks (a source edit, `swift test`, restore). "Killed" means at least one test failed. 29 of 30 were killed:

```
M01 verify rejects high-S              killed (39)   M16 Unicode-aware fingerprint uppercasing  killed
M02 parser ignores trailing data       killed (17)   M17 FILE fingerprint mismatch accepted     killed
M03 keyid check skipped                killed (7)    M18 canonical-form check off               killed
M04 MESH trusts container signer.spki  killed (11)   M19 signature result ignored               killed (12)
M05 SPKI accepts trailing bytes        killed (2)    M20 r/s range check removed                SURVIVED (equivalent mutant, see below)
M06 SPKI skips curve equation          killed (5)    M21 SPKI prefix not compared               killed
M07 producer skips low-S               killed (167)  M22 signature count >= 1                   killed
M08 base64 non-zero unused bits ok     killed (9)    M23 names compared with String ==          killed
M09 base64 mixed alphabets ok          killed (3)    M24 depth limit 17                         killed
M10 JCS sorts by code point            killed (4)    M25 lone low surrogate accepted            killed
M11 duplicate names not detected       killed (10)   M26 safe-integer bound off by one          killed
M12 fingerprint compare = prefix       killed (6)    M27 low-S boundary strict                  killed
M13 TEST-ONLY deny-list off            killed (2)    M28 -0 accepted                            killed
M14 PAE counts characters              killed        M29 exponent accepted                      killed
M15 DER accepts non-minimal integer    killed (2)    M30 payloadType compared by prefix         killed
```

M20 survives because the crypto library rejects `r = 0`, `s = 0` and `r, s >= n` with the same code (`SIGNATURE_INVALID`), so our explicit range check (the spec's step 8) is redundant defence
in depth and not separately observable (ERRATA E-12). The first automated pass mis-read a singular "with 1 failure" as a survivor for seven mutations (M14, M16, M23, M24, M25, M28, M30);
they were re-run after fixing the harness and all were killed. M23 first failed to build and was corrected.

**Not run, so not passed** (`NEEDS-CI`; the workflow is written and syntax-checked only):
- `apple-swift-lane` on `macos-latest` (CryptoKit path: never compiled), and on `ubuntu-latest` in `swift:6.1-noble@sha256:3991e5dd...` (digest resolved from Docker Hub today; image not pulled).
- `ios-package-sim` (`xcodebuild -scheme AsomKit-Package -destination 'platform=iOS Simulator,name=iPhone 17' test`; whether that scheme name and simulator exist on `macos-latest` is unverified).
- `root-unchanged` (its diff logic ran nowhere; no negative control).
- `jvm-lines` and `lane-diff`: **not written**, they need the lab's `lines` mode (commented TODO in the workflow). `lane-diff` for M01–M03, M05, M06 is therefore **not achieved**.
- I0 gate items still open: `macos-latest` test green, `ios-package-sim` -> `** TEST SUCCEEDED **`, lane-diff exit 0.

Oracle status: **self-oracled**. This lane was written with the spikes and `VerifyDsse.java` in view and in a full checkout, so it does not clear the `self` tag (ERRATA E-02).
Result: PARTIAL — I0a Linux gates PASSED (LAB); macOS, simulator, container and lane-diff gates NOT RUN.
## Lab L0.2a-json gate (track `lab-json`: `:json` and vector family M01) — 2026-09-30 — LAB (not device evidence)
Base: 74fe9a04abcace8ebecfff906821e9efc7afa007 + uncommitted working tree   JDK: openjdk 21.0.10 (JDK 17 NOT available in this container, see below)   Runner: local (4 CPU container, `--no-daemon --max-workers=2`)
Scope: LAB_SPEC 4.2 as `xyz.mdhv.asom.lab.json` (typed `JValue`, strict tokenizer/parser with the six reject codes, JCS integer profile, strict base64) and vector family M01. NOT done here: M01-201..206 (DER/raw signature codec, belongs to `:manifest`). Reading choices: `lab/ERRATA.md` ERR-JSON-1..10.

```
$ ./gradlew -p lab :json:test :conformance-runner:run --args='lines M01' --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process --rerun-tasks
> Task :json:test
> Task :conformance-runner:run
BUILD SUCCESSFUL in 3m 16s
(:json:test: 42 tests in 6 classes, 0 failures: RejectTableTest 11, CheckOrderTest 6, JcsTest 10, Base64StrictTest 7, PropertyTest 5, M01VectorFileTest 3)
lines mode: 93 lines, e.g.
M01-001 ok
M01-002 ok
M01-003 ok
...
M01-138 reject TRAILING_DATA
M01-140 reject DUPLICATE_KEY
M01-141 reject NON_INTEGER_NUMBER
M01-142 reject NUMBER_RANGE
...
M01-323 reject ENCODING
M01-324 reject ENCODING
M01-325 ok
verdict counts: ok 26, reject DUPLICATE_KEY 5, reject ENCODING 15, reject INVALID_UNICODE 13, reject MALFORMED_JSON 13, reject NON_INTEGER_NUMBER 11, reject NUMBER_RANGE 5, reject TRAILING_DATA 5

$ ./gradlew -p lab labTest --stacktrace --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process --rerun-tasks
BUILD SUCCESSFUL in 2m
family W00: 7 vectors, 7 pass, 0 fail, 5 proposed-skipped, oracle: self=12
family W01: 14 vectors, 14 pass, 0 fail, 0 proposed-skipped, proposed-lane 200 run: 200 pass, 0 fail, oracle: self=214
family W01b: 9 vectors, 9 pass, 0 fail, 1 proposed-skipped, oracle: self=10
family W02: 9 vectors, 9 pass, 0 fail, 0 proposed-skipped, oracle: self=9
family W03: 7 vectors, 7 pass, 0 fail, 0 proposed-skipped, oracle: self=7
family M01: 93 vectors, 93 pass, 0 fail, 0 proposed-skipped, oracle: self=93
family R04: 28 vectors, 28 pass, 0 fail, 0 proposed-skipped, oracle: self=28
law M01/b64-ok: 9 cases
law M01/b64-reject: 15 cases
law M01/jcs-idempotent: 17 cases
law M01/jcs-ok: 17 cases
law M01/reject-DUPLICATE_KEY: 5 cases
law M01/reject-INVALID_UNICODE: 13 cases
law M01/reject-MALFORMED_JSON: 13 cases
law M01/reject-NON_INTEGER_NUMBER: 11 cases
law M01/reject-NUMBER_RANGE: 5 cases
law M01/reject-TRAILING_DATA: 5 cases
law M01/utf16-key-order-trap: 3 cases

$ python3 lab/tools/xcheck.py lab/conformance
xcheck W01: 214 agree, 0 disagree
xcheck keys: 2 agree, 0 disagree
xcheck INDEX: 10 agree, 0 disagree
xcheck M01: 94 agree, 0 disagree
xcheck M02: absent
xcheck M03: absent
xcheck W05: absent
$ python3 lab/json/tools/xcheck_m01.py lab/conformance --corpus lab/build/json-fuzz-corpus.tsv   (corpus = 4,000 mutated inputs written by the Kotlin PropertyTest: 113 ok, 2890 MALFORMED_JSON, 501 INVALID_UNICODE, 372 TRAILING_DATA, 69 NON_INTEGER_NUMBER, 53 NUMBER_RANGE, 2 DUPLICATE_KEY)
xcheck M01 corpus: 4000 inputs
xcheck M01: 4094 agree, 0 disagree

$ lab/tools/isolation.sh --with-sdk
isolation check 1 (root settings never name the lab): 0   expected 0
isolation check 2 (no Android tooling in the lab classpath; ANDROID_HOME='' ANDROID_SDK_ROOT=''): 0   expected 0
isolation check 2 positive control (the same output does list the Kotlin plugin, so a 0 above is not an empty read): 19   expected > 0
isolation check 2 again with ANDROID_HOME=/tmp/tmp.gn44JrjGY3 (a directory standing in for an SDK): 0   expected 0   [CI-APPROX]
isolation check 3 (no Android module in the lab build): 0   expected 0
isolation check 3 positive control (the mapped root projects and the nine lab modules are listed): 15   expected 15
isolation check 4: base 1963166c500d9e83b6b5b076bb3c11263ef3e429 (from lab/LAB_BASE_SHA), ancestor of HEAD confirmed
isolation check 4: 136 protected files compared byte-for-byte against base 1963166c500d
isolation check 4: OK (shipped tree byte-identical to the pinned base)
law: 9 module build files checked against the LAB_SPEC 1.2 dependency table
law: 52 Kotlin/Java sources scanned (no android imports, no frozen-enum reuse, no wildcard bind, listeners only in the harness or tests)
law: OK
ISOLATION: all checks passed
Check 2 with a real Android SDK is NOT verified here (no SDK in this container; the directory stand-in is CI-APPROX).

$ ./gradlew jvmTest --rerun-tasks --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process     (root build)
run 1: BUILD FAILED, root tests: 139, failures: 1  -> AsomServerIntegrationTest.a stream with no usage still bills output tokens heuristically: NoSuchElementException: List is empty (the ledger-read race of ERRATA ERR-W01B-3; a root test, untouched by this track)
run 2: BUILD SUCCESSFUL in 1m 27s, root tests: 139, failures: 0   (baseline: 139)

Mutation checks (each edit made in the working tree, `:json:test` run, then the original restored byte for byte; every one FAILED as required):
key sort by code point instead of code unit                -> FAILED (gradle exit 1), tests run=42, FAILED=4
duplicate-key detection removed                            -> FAILED (gradle exit 1), tests run=42, FAILED=9
trailing-data check removed                                -> FAILED (gradle exit 1), tests run=42, FAILED=5
range check removed                                        -> FAILED (gradle exit 1), tests run=42, FAILED=9
lone-surrogate check removed                               -> FAILED (gradle exit 1), tests run=42, FAILED=6
depth limit off by one (17 allowed)                        -> FAILED (gradle exit 1), tests run=42, FAILED=2
check order: duplicate outranks non-integer                -> FAILED (gradle exit 1), tests run=42, FAILED=6
base64 unused-bits check removed (3-char tail)             -> FAILED (gradle exit 1), tests run=42, FAILED=5
invalid UTF-8 whole-input check removed                    -> FAILED (gradle exit 1), tests run=42, FAILED=7
-0 accepted as zero                                        -> FAILED (gradle exit 1), tests run=42, FAILED=3
Under the key-sort mutation `:conformance-runner:test` also fails the M01 family: FAIL M01-001, FAIL M01-008, FAIL M01-012 (expected canonical bytes differ). `lines M01` was IDENTICAL under that mutation: the lines format is a verdict only (`ok` / `reject <CODE>`), so it cannot see canonical-byte differences; JUnit mode pins them.
```
Non-vacuity: every Cases group asserts a minimum case count (RejectTableTest rows 20 to 150, CheckOrderTest 56 pairs and 56 triples, PropertyTest 12,000 round-trip and 18,000 rendering checks, Base64StrictTest 177,156 exhaustive strings); the runner family requires each of the six reject codes, ENCODING, jcs-ok, jcs-idempotent, utf16-key-order-trap, b64-ok and b64-reject to be exercised (law counts above).
Oracle status: self-oracled (no independent implementation has agreed yet). The Python second implementation and the Kotlin module were written in one session; their agreement (4,094 checks) does not clear the tag.
Not verified here: JDK 17 (only JDK 21 in this container; the module targets JVM 17 bytecode and avoids JDK 21-only APIs, but that is unrun); ART, Swift lane; hosted CI.
Result: PASSED (`:json` and M01, self-oracled; local LAB evidence only). The root suite showed one known ledger-race failure on its first cold run and passed on the second.
## Desktop DL0 + DL1 gate (track `desktop-core`) — 2026-09-30 — LAB (not device evidence)
Base: `74fe9a04abcace8ebecfff906821e9efc7afa007` + the uncommitted `desktop-core` working tree (`desktop/**`, `.github/workflows/desktop-linux.yml`)   JDK: openjdk 21.0.10 and Temurin 17.0.20.1+1   Runner: local container (Ubuntu 24.04 x86_64, NO Android SDK, run as root; the launchers were run as uid 65534 through `setpriv`), no hosted CI run
Scope: PLATFORM_PLAN sections 2 and 3 steps DL0 (skeleton, isolation) and DL1 (probes, governor). `:node-core` (seam, `NodeConfig`, `ControlFrames`, `AsomCli`, `TtyConfirm`, `ProviderFsm`, governor, `JsonlLedgerSink`, `NoopEngine` wiring) and `:node` (`LinuxPlatform`, five probes, `host/*`). NOT built (DL2/DL3, declared `NOT_YET_IMPLEMENTED` and reported by the selftest): control socket, D-Bus sleep watcher, keep-awake locks, key store, packaging. Nothing binds a socket. Spec defects met and the readings taken: `desktop/ERRATA.md` (notably ERR-ISO-1, ERR-DECK-1, ERR-FSM-3/4, ERR-SINK-1).

```
$ ./gradlew -p desktop desktopTest --rerun-tasks --stacktrace          (JDK 21.0.10)
    transitions exercised: 36/36
    guard branches exercised: 41/41
    property test: 20000 sequences, 800000 steps, seed 20260930
    law presence-laws/LP-2-hold-down-599999: cases exercised: 1   (also -600000, LP-0 20, LP-1 shape 3, drain-at-once 2164, serve-entry-guarded 9104, ...; every law prints its count and the run fails on a zero)
    sigkill harness: 20 kills at 10 (model, point) pairs; claim: process death only, not power loss
    probe fixtures: 4 SYNTHETIC hosts x 6 families
BUILD SUCCESSFUL in 1m 27s
24 actionable tasks: 24 executed
(:node-core 67 tests, :node 53 tests, 0 failures, 0 skipped)

$ JAVA_HOME=<Temurin 17.0.20.1+1> ./gradlew -p desktop desktopTest --rerun-tasks --stacktrace
    transitions exercised: 36/36
    guard branches exercised: 41/41
    foreground node pid 31473: 1 socket descriptor(s) held, 0 inet, 0 listening, 0 bound unix (LAB, this container)
BUILD SUCCESSFUL in 1m 26s
```
(A first JDK 17 run FAILED: the launcher test wanted zero socket descriptors and the JDK 17 runtime holds one unbound, unconnected AF_UNIX descriptor of its own, also held by an idle 5-line Java program. The test now classifies descriptors instead (ERRATA ERR-TEST-2); the JDK 17 result above is the run after that change. One earlier JDK 21 attempt was killed by another builder's `gradlew --stop` and was simply re-run, ERR-ENV-1.)

DL0 gate 6 and the exact JSON shape (real installed launcher, uid 65534, LAB; the node has no control socket yet, so this is a LOCAL SNAPSHOT tagged `"source":"local-snapshot"`, ERRATA ERR-CLI-1):
```
$ desktop/node/build/install/asom-node/bin/asom status --json
{"host":"foreground","fsm":"OFF","listeners":[],"locks":[],"source":"local-snapshot","version":"0.0.0-scaffold","platform":"linux","mode":"foreground","lending":false,"keyStorage":"unknown","engine":{"backend":"none","hasLocalEngine":false},"peers":[],"sessions":0,"inflight":0,"paths":{"state":"/nonexistent/.local/state/asom",...},"governors":{"power":{"source":"ac","charging":false,"batteryBand":null},"thermal":{"band":0,"watchedSensors":0},"gpuContention":"off (no attributable counter)","rules":"desktop"},"keepAwake":"not-implemented","notYetImplemented":["nik-store (planned tier: file) (DL2)","control-socket (DL2)","keep-awake locks (Inhibitor) (DL2)","sleep watcher (logind PrepareForSleep) (DL2)"]}
$ desktop/node/build/install/asom-node/bin/asom-node --mode=selftest
    selftest: 11 ok, 4 not-yet-implemented, 0 failed
```
(`LauncherProcessTest` also runs a real `asom-node --foreground`: banner on stderr only, stdout empty, 0 inet / 0 listening / 0 bound-unix sockets held on JDK 21 and JDK 17, exit 143 on SIGTERM; and asserts exit 78 with "refusing to run as root" for every mode.)

DL1 gate command as written in the plan (ERRATA ERR-GATE-1: the FSM tests live in `:node-core`, so `*Fsm*` matches nothing in `:node`):
```
$ ./gradlew -p desktop :node:test --tests '*Probe*' --tests '*Fsm*'          BUILD SUCCESSFUL
$ ./gradlew -p desktop :node-core:test --tests '*Fsm*'                        BUILD SUCCESSFUL   (prints transitions exercised: 36/36)
```

Isolation (check 4 pinned to `desktop/DESKTOP_BASE_SHA`, the lab's mechanism copied):
```
$ ASOM_GRADLE_FLAGS='--no-daemon ...' desktop/tools/isolation.sh --with-sdk
isolation check 1 (root settings never name the desktop build): 0   expected 0
isolation check 2 (no Android tooling in the desktop classpath; ANDROID_HOME='' ANDROID_SDK_ROOT=''): 0   expected 0
isolation check 2 positive control (the same output does list the Kotlin plugin, so a 0 above is not an empty read): 19   expected > 0
isolation check 2 again with ANDROID_HOME=/tmp/tmp.rTGDSmGBfe (a directory standing in for an SDK): 0   expected 0   [CI-APPROX]
isolation check 3 (no Android module in the desktop build): 0   expected 0
isolation check 3 positive control (the mapped root projects and the desktop modules are listed): 8   expected 8
isolation check 4: base 74fe9a04abcace8ebecfff906821e9efc7afa007 (from desktop/DESKTOP_BASE_SHA), ancestor of HEAD confirmed
isolation check 4: 136 protected files compared byte-for-byte against base 74fe9a04abca
isolation check 4: OK (shipped tree byte-identical to the pinned base)
law: OK        ISOLATION: all checks passed
$ python3 desktop/tools/isolation.py --selftest   -> selftest OK (12 negative controls)
$ (real negative control) echo '// tamper' >> settings.gradle.kts; python3 desktop/tools/isolation.py
isolation check 4: FAILED, 1 difference(s):   settings.gradle.kts: content differs from base (9d0a648667fa != 225f94e8bfd6)     exit=1   (restored: exit=0)
```
Check 2 with a REAL Android SDK present is NOT verified here (no SDK in this container); the `desktop-isolation-with-sdk` job is its proof and has not run.

Root build unchanged:
```
$ ./gradlew jvmTest --rerun-tasks --stacktrace
BUILD SUCCESSFUL in 1m 5s      21 actionable tasks: 21 executed
root tests: 139   (baseline: 139)
$ git status --short  ->  ?? .github/workflows/desktop-linux.yml   ?? desktop/        (nothing else)
```

Mutation checks (each applied to the real source, the targeted tests run, then the original restored; every one FAILED as required):
```
LP-2 hold-down halved                       : 5 tests fail (PresenceLawsTest x4, ProviderFsmExhaustiveTest)
presence in SERVING no longer drains        : 8 tests fail (PresenceLawsTest x7, ProviderFsmExhaustiveTest)
a sleep drain records presence              : 4 tests fail
fsync-before-ack (force() removed)          : 4 tests fail (JsonlLedgerSinkTest x3, LedgerSigkillHarnessTest volatile model)
append failure swallowed (fail-open)        : 3 tests fail
TtyConfirm falls back to stdin              : 1 fails   root guard removed from asom-node : 1 fails
Deck block lock allowed                     : 1 fails   Deck battery no longer a hard NO  : 2 fail
node prints an env secret to stderr         : 1 fails (NoSecretsOnStdoutTest)
control frame length unchecked              : 1 fails
```
Note: the `real` SIGKILL model cannot see a missing fsync (the page cache survives a kill); the `volatile` channel model is what detects it, which is why both run (ERRATA ERR-SINK-1). The ledger claim is process death only.

Not verified here: hosted-runner behaviour of `desktop-linux.yml` (never run; action SHAs from `git ls-remote`); the `ubuntu-24.04-arm` lane; a real Android SDK for check 2; Windows/macOS; every device fact: the four sysfs fixture hosts are SYNTHETIC hand-written trees, so no probe result is evidence about a Steam Deck or a Dell (DV-D*, DV-L* stay `NEEDS-DEVICE-VALIDATION`); systemd, logind, real suspend; the Deck docked/game/Game-Mode mechanisms do not exist (unknown = not lending, ERR-DECK-1).
Result: PASSED (DL0 and DL1 as amended by ERRATA; local LAB evidence only)

## Lab L0.2 + L0.3 gate (track `lab-manifest`: `:bench-core`, `:manifest`, families M01der, M02-M06) — 2026-09-30 — LAB (not device evidence)
Commit: 95b4c97d3d820419f1bf8b937c36c65dc6eb9d48 + uncommitted working tree (not committed, not pushed)   JDK: openjdk version "21.0.10" 2026-01-20 (JDK 17 NOT available in this container)   Runner: local (`--no-daemon --max-workers=2`, GRADLE_OPTS=-Xmx1g)
Scope: `:bench-core` (asom.bench/1 model, M04 derive with checked integer arithmetic, projection, asom.text/1, run plans, governor FSM + fake host, bench-set pins) and `:manifest` (typed decoder, DSSE, ES256, key ids and fingerprints, the r3 verifier steps 1-19, signer, FILE projection, public derivative, asom.manifest-text/1). Vectors M01-2xx (DER codec), M02, M03, M04, M05, M06 regenerated or new for r3, every one `oracle: self`, TEST-ONLY keys only. NOT done: M07 (not built, LAB_SPEC 4.9), M08 (belongs to `:mesh-policy`), r0 files not moved to `history/r0` (outside this track's write set, ERRATA ERR-MAN-3).

```
$ ./gradlew -p lab labTest :conformance-runner:run --args='lines M01,M02,M03,M04,M05,M06,M08' --stacktrace --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process --rerun-tasks
BUILD SUCCESSFUL in 2m 30s
tests (JUnit XML, 0 failures in every module): :json 42, :bench-core 41, :manifest 36, :conformance-runner 651
family M01: 105 vectors, 105 pass, 0 fail, 0 proposed-skipped, oracle: self=105
family M02: 18 vectors, 18 pass, 0 fail, 0 proposed-skipped, oracle: self=18
family M03: 71 vectors, 71 pass, 0 fail, 0 proposed-skipped, oracle: self=71
family M04: 94 vectors, 94 pass, 0 fail, 0 proposed-skipped, oracle: self=94
family M05: 42 vectors, 42 pass, 0 fail, 0 proposed-skipped, oracle: self=42
family M06: 10 vectors, 10 pass, 0 fail, 0 proposed-skipped, oracle: self=10
family M08: not-implemented
lines mode: 340 lines (`M01-001 ok` ... `M03-101 reject SIGNATURE_INVALID` ...); compared with the expectation of every vector file: 340 expected, 340 printed, 0 differences.
`lines M07` exits 1 with `unknown family M07` (M07 is not a family). `lines ...,M08` exits 0 and prints `family M08: not-implemented`.
```
Non-vacuity (each prints its case count and fails below a minimum): bit flips over a container 20,454 (own) and the file container; defect-pair matrix 153 pairs plus 18 single defects; stat invariants 3,000; median laws 2,000; nearest-rank 1,500; confidence caps 1,500; q2 5,001; render laws 20 renderings; FSM 100 state pairs; exit-path injections 56; DER round trips 300. The runner fails a family with a required law or id at zero cases (`requiredLaws`, `requiredIds`).

```
$ python3 lab/tools/xcheck.py lab/conformance
xcheck W01: 214 agree, 0 disagree
xcheck keys: 4 agree, 0 disagree
xcheck INDEX: 17 agree, 0 disagree
xcheck M01: 94 agree, 0 disagree
xcheck M01der: 15 agree, 0 disagree
xcheck M02: 90 agree, 0 disagree
xcheck M03: 185 agree, 0 disagree
xcheck M04: 759 agree, 0 disagree     (8 vectors outside the reference sketch, reported as skipped)
xcheck M05: 210 agree, 0 disagree     (manifest header re-derivation 108, bench body against bench_ref.render 102; 12 skipped)
xcheck M06: 82 agree, 0 disagree
xcheck W05: absent
xcheck oracle status: self-oracled (same-session cross-check; never clears the oracle tag)
$ python3 lab/manifest/tools/xcheck_manifest.py    -> pure-Python P-256 verify; openssl cross-checked 75 signatures (all agree)
$ python3 lab/manifest/tools/schema_oracle.py      -> 22 accepted payloads agree, 14 SCHEMA_INVALID payloads rejected by the schema too, 0 disagree (jsonschema 4.26.0)
```
The cross-checks and the mutation run exposed two real defects, both fixed and re-generated: the executor trace did not log `UNLOAD` on abort, yield and sustain-end paths (M04-320..331 changed; ERRATA ERR-BENCH-7), and `:conformance-runner:test` failed on a stale `INDEX.json` (regenerated).

```
$ lab/tools/isolation.sh
isolation check 1 (root settings never name the lab): 0   expected 0
isolation check 2 (no Android tooling in the lab classpath; ANDROID_HOME='' ANDROID_SDK_ROOT=''): 0   expected 0
isolation check 2 positive control (the same output does list the Kotlin plugin, so a 0 above is not an empty read): 19   expected > 0
isolation check 3 (no Android module in the lab build): 0   expected 0
isolation check 3 positive control (the mapped root projects and the nine lab modules are listed): 15   expected 15
isolation check 4: base 770a44da21e7a917071ea1e12f1eed98a91aff03 (from lab/LAB_BASE_SHA), ancestor of HEAD confirmed
isolation check 4: 136 protected files compared byte-for-byte against base 770a44da21e7
isolation check 4: OK (shipped tree byte-identical to the pinned base)
law: 9 module build files checked against the LAB_SPEC 1.2 dependency table
law: 87 Kotlin/Java sources scanned (no android imports, no frozen-enum reuse, no wildcard bind, listeners only in the harness or tests)
law: OK
ISOLATION: all checks passed
$ ./gradlew jvmTest --rerun-tasks --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process     (root build)
BUILD SUCCESSFUL in 1m 9s
root tests: 139, failures: 0   (baseline: 139)
```
Mutation checks (`python3 lab/manifest/tools/mutants.py`; one source edit each, rebuild, three layers: L1 `lines` vs the unmutated build, L2 `:conformance-runner:test`, L3 `:manifest:test :bench-core:test`; the file is restored byte for byte and re-checked): 53 mutants, 50 KILLED (L1 35, L2 8, L3 7), 3 SURVIVED, 0 invalid. Killed include: signature verify skipped, MESH trusting `signer.spki` or the `keyid`, high-S rejected, canonical check skipped, derivation and confVersion-floor checks skipped, EXPIRED and TTL and skew off-by-one, TEST_ONLY deny-list skipped, null challenge accepted, nonce check skipped, audience check moved ahead of the nonce check, rollback `<=`, fingerprint compare skipped, manifest.v2 payload type accepted, subject match skipped, FILE key storage unchecked, wrapping multiply in `consistency()` and in `Checked.mul`, on-curve check skipped, non-minimal or negative DER accepted, low-S normalisation dropped, FILE projection keeping `platformIds` / `securityPatch` / battery level / exact time, seq without clock or always new, public-derivative rounding, catalogue, commit and model rules, rate median swap, exclusion budget, drift threshold, MAD constant, consent expiry and reuse, an extra FSM edge, yield limit, battery-temperature ceiling, abort path not closing the engine or restoring brightness, role threshold. The first mutation run SURVIVED for V11 (skew boundary), V21 (tee storage), V24 (wrapping product), B02 (exclusion budget), B14 (role threshold); each was answered with a new vector or unit test (M02-119, M02-120, M03-178, M04-025, M04-026, BoundaryTest, exclusion-budget and role-threshold tests) and re-run: KILLED. The three remaining survivors are EQUIVALENT: V02 (explicit range check: the JDK verifier also refuses r or s outside 1..n-1, so the verdict cannot change), V25 and V26 (strict SPKI prefix and length: the JDK key factory refuses the same encodings, so the extra check is defence in depth). The first three results of an earlier partial run (V01, V03, V04, all KILLED at L1) were kept; an earlier V02 "killed" result was discarded because it came from a stale `INDEX.json`.

Oracle status: self-oracled (no independent implementation has agreed yet). The Python cross-checks were written in the same session as the Kotlin; the openssl agreement on 75 signature layers clears no tag (LAB_SPEC 4.9 makes openssl external for the signature layer of the r0 vectors only, and this run is the same session).
Not verified here: JDK 17 (only JDK 21 in this container; the modules target JVM 17 bytecode); no hosted CI run; no ART, Swift, iOS, Windows or device run; no real engine, so every benchmark number is a fake-host simulation (SIMULATED, NOT DEVICE EVIDENCE); Q1 pins are PROPOSED (owner has not hashed the files, A17); L1 pins, numerics references, bytes-per-token (provisional 4000/8000) and the editorial constants stay BLOCKED or PROVISIONAL (D18); steps 11-19 of the verifier have no independent second implementation.
Result: PASSED (`:bench-core`, `:manifest`, M01der and M02-M06, self-oracled; local LAB evidence only). Runner registry edits are listed in ERRATA ERR-RUN-1.
## Lab L0.4 gate (track `lab-ledger-policy`: `:ledger-model`, `:mesh-policy`, vector families L01, L02, W07, W07p) — 2026-09-30 — LAB (not device evidence)
Commit: 95b4c97d3d820419f1bf8b937c36c65dc6eb9d48 (working tree, uncommitted)   JDK: openjdk 21.0.10 (JDK 17 lane NOT run here: only JDK 21 in the container)   Runner: local
```
$ ./gradlew -p lab :ledger-model:test :mesh-policy:test :conformance-runner:test --rerun-tasks      (tests: ledger-model 67, mesh-policy 32, conformance-runner 644; 0 failures)
BUILD SUCCESSFUL in 2m 27s
family W07: 67 vectors, 67 pass, 0 fail, 0 proposed-skipped, oracle: self=67
family W07p: 97 vectors, 97 pass, 0 fail, 0 proposed-skipped, oracle: self=97
family L01: 56 vectors, 56 pass, 0 fail, 0 proposed-skipped, oracle: self=56
family L02: 21 vectors, 21 pass, 0 fail, 0 proposed-skipped, oracle: self=21
(W00 W01 W01b W02 W03 R04 M01 unchanged: 0 fail)
L-L1 iterations: 740   L-L2: 282   L-L3: 282   L-L4: 3756   L-L5: 613   L-L5b: 613   L-L6: 657   L-L7: 3701   L-L8: 11942   L-L9: 2966   L-L10: 10245
L-L11 iterations: 548 death points (every step of 6 runs); 1408 outcome rows checked; 308 intents left without an outcome
L-L12 iterations: 564   L-L13: 3041 injected append failures over 2168 runs   L-L14: 793
L-L15 MEASURED sessions: 1341 (n > 0), mismatches: 0   (the ESTIMATED lane is excluded and counted; an all-ESTIMATED run fails the law: tested)
L-L16 per-frame-type counts: HELLO=926 HELLO_ACK=926 STATE_REQ=438 STATE=438 MANIFEST_REQ=290 MANIFEST=290 GOAWAY=322 ERROR=226 REVOKE_NOTICE=224 PAIR_HELLO=328 PAIR_CHALLENGE=328 PAIR_DECISION=656 PAIR_COMMIT=328 PAIR_COMMIT_ACK=328 EXT_IGNORED=135 (none zero)
LP-0: 46 (every input, both node kinds)   LP-1: 2000 + 2000 + 3000 outputs + 12000 states   LP-2: 180000 steps in 3000 random sequences (5905 presence-while-SERVING; 1846 ticks at t+599,999; 608 at t+600,000; 29597 PF waits for Start lending)
decision table: 20000 random situations, every row (1 2 3 4 5 6 6a 6b 7 8) deciding at least 880 times
rows intact after kill at <point> [before|after]: 32 lines (16 durability points x 2), every row reported durable verified byte for byte, torn tail 0;
   plus 3 kills at arbitrary moments of a write-heavy child (40, 77 and 114 durable rows verified)
$ ./gradlew -p lab :conformance-runner:run --args='lines L01,L02,W07,W07p' --quiet     -> 241 lines (196 `ok`, 45 `reject <CODE>`), e.g. `L01-201 reject F1_ELIGIBILITY`, `W07p-305 ok`
$ python3 lab/tools/xcheck.py lab/conformance --families L01,W07,W07p     xcheck L01: 56 agree, 0 disagree   xcheck W07: 67 agree, 0 disagree   xcheck W07p: 97 agree, 0 disagree
$ python3 lab/tools/xcheck.py lab/conformance     (defaults)  W01 214 agree, keys 2, INDEX 14 agree, M01 94 agree, 0 disagree everywhere
$ ANDROID_HOME= ANDROID_SDK_ROOT= lab/tools/isolation.sh
isolation check 1 (root settings never name the lab): 0   expected 0
isolation check 2 (no Android tooling in the lab classpath): 0   expected 0       positive control: 19   expected > 0
isolation check 3 (no Android module in the lab build): 0   expected 0            positive control: 15   expected 15
isolation check 4: 136 protected files compared byte-for-byte against base 770a44da21e7 ... OK (shipped tree byte-identical to the pinned base)
law: 84 Kotlin/Java sources scanned ... law: OK        ISOLATION: all checks passed
$ ./gradlew jvmTest --rerun-tasks      BUILD SUCCESSFUL in 1m 35s
root tests: 139   (baseline: 139)   failures: 0
```
Mutation checks (each applied to the real source, the targeted tests run, the original restored; every one FAILED as required):
```
L-L1   requester intent row never written before the offer      : 4 tests fail     L-L14  DIAL connects before its intent is durable : 5 fail
L-L15  rows drop the 9-byte header                                : 3 fail           L-L15  header dropped AND overhead derived from the writer's own counts (true by construction): 4 fail (the plaintext tap still catches it)
L-L16  no row for a sent GOAWAY                                   : 3 fail           L-L13  control-row failure swallowed, frame sent anyway : 4 fail
L-L3   INFER_END before the lender outcome row                    : 4 fail           L-L2   engine reads without a durable lender intent : 5 fail
L-L6   file sink truncates on reopen                              : 1 fails          durability  append returns without force            : 1 fails
L-L5   a declined offer raises reach                              : 2 fail           P7/L-L5b  terminal header = egress, not reach       : 1 fails
L-L9   intent row carries bytes : 24 fail   L-L10 peerPath guessed LAN : 1 fails   session column wrong on DIAL rows : 3 fail
LP-2   hold-down off by one (600,000 - 1)                         : 6 fail           LP-2   presence does not restart the hold-down       : 3 fail
LP-2   presence does not drain at once                            : 22 fail          LP-2   PF returns without a new Start lending        : 3 fail
LP-2   condition drain gets the presence hold-down                : 4 fail           LP-0   PF exception dropped                            : 5 fail
LP-1   screen state leaks as a `user` member                      : 19 fail          LP-1   st.gov computed from presence                   : 5 fail
LP-1   presence decline code PEER_BUSY                            : 8 fail           W07 skew  staleness uses the peer wall clock       : 4 fail
L01    destination sets ignore the cloud ban : 8 fail   quiescence  lender may initiate on a pending request : 3 fail
```
Permanent (in-tree) mutants: L-L15 is also shown to fail against three miscounting mutants run through the whole simulator (`LawNegativeControlsTest`), and every law has a hand-built violating trace that it must flag.
Findings for the owner (ERRATA ERR-LL-*, ERR-LP-*): (1) the spec's enum with a peer member cannot be spelled `Egress.PEER` under R4's own grep (ERR-LL-1); (2) rows cannot be grouped per session without one more column: `sessionId` added (ERR-LL-2); (3) the STALE thermal substitution `max(last, 1) if last >= 1` is a no-op (ERR-LP-5); (4) FC-5's "CANCEL the stream" points the wrong way (ERR-LL-6); (5) contract.md L-L9 is false with per-frame rows (ERR-LL-4).
Not verified here: JDK 17 lane; the real `SSLEngine` accounting (the meter and tap are a MODEL of RFC 8446 records, L0.5 owns the JSSE lane); death of ONE node while the other continues (L-L11 kills both); power loss (only process death is claimed); the runner still reports `W01b-reach` as proposed-skipped (covered by `RequestReachAndBytesTest` and L02-018, ERR-LL-10); no independent oracle: all 241 vectors are `oracle: self`, and the Python cross-check was written in the same session.
Oracle status: self-oracled (no independent implementation has agreed yet)
Result: PASSED (local LAB evidence only)

## Desktop DL2 gate (track `linux-host-dl2`: host integration) — 2026-09-30 — LAB and CI-ONLY (not device evidence)
Base: `95b4c97d3d820419f1bf8b937c36c65dc6eb9d48` (worktree was created at `98ab632f`, reset with `git fetch origin claude/asom-v1-build-brief-vw83oh && git reset --hard 95b4c97d...`, `HEAD` and `docs/design/mesh/LAB_SPEC.md` confirmed) + the uncommitted `linux-host-dl2` working tree.   JDK: openjdk 21.0.10 and Temurin 17.0.20.1+1 (a second copy under `/tmp`, because uid 65534 must be able to read the JDK)   Runner: local container (Ubuntu 24.04, NO Android SDK, run as root, `dbus-daemon` 1.14.10, systemd 255 tools only, NO running systemd, NO logind, NO polkitd)
Scope: PLATFORM_PLAN section 3 step DL2. NEW in `desktop/node`: `control/{ControlServer,ControlClient,PeerCredentials}`, `dbus/{DbusWire,MiniDbus}`, `power/{Inhibitor,SleepWatcher,InhibitorProbeMain}`, `LinuxPlatform` wiring; `desktop/packaging/linux/{systemd/asom.service,systemd/asom-user.service,sysusers.d/asom.conf,polkit/50-asom-inhibit.rules,test/journal-hygiene.sh,test/systemd-vm.sh}`; `desktop/docs/{LINUX,STEAM_DECK}.md`; a `systemd-vm` job in `desktop-linux.yml`; rows ERR-DL2-1 to ERR-DL2-14 in `desktop/ERRATA.md`. Two small edits outside the track's directories, both logged as ERR-DL2-3: `SourceHygieneTest` (`:node-core` test) and `desktop/tools/check_law.py` now allow ONE named listener, the AF_UNIX `ControlServer.kt`. Nothing in `:node-core` main, the root build, `core/`, `server/` or `ci.yml` was touched. NOT built (not in the assignment or blocked): the identity store (`nikStore`), starting the control socket from `asom-node` (BLOCKED, ERR-DL2-4), a consumer of the FSM effects that takes and releases the locks.

SO_PEERCRED (ERR-DL2-1), probed on both JDKs with a 20-line program (bind AF_UNIX server, connect, accept, read the option on both sockets):
```
$ java Peer.java                     (OpenJDK 21.0.10)
21.0.10 supported(accepted)=true / accepted peer=UnixDomainPrincipal[user=root, group=root] / client sees server=UnixDomainPrincipal[user=root, group=root] / client supports=true
$ /tmp/.../jdk-17.0.20.1+1/bin/java Peer.java     (Temurin 17.0.20.1)
17.0.20.1 supported(accepted)=true / (same three lines)
```
Available on both: NOT BLOCKED. A runtime without it refuses to start the control socket (no weaker fallback).

DL2 gate 1, `./gradlew -p desktop desktopTest --rerun-tasks` with `ASOM_REQUIRE_DBUS=1 ASOM_REQUIRE_SYSTEMD_TOOLS=1 ASOM_REQUIRE_NODE=1` (so a missing tool FAILS instead of skipping), JDK 21 then JDK 17:
```
BUILD SUCCESSFUL in 2m 37s     24 actionable tasks: 24 executed        (JDK 21.0.10)
BUILD SUCCESSFUL in 2m 47s     24 actionable tasks: 24 executed        (JDK 17.0.20.1)
:node tests 117, skipped 0, failures 0     :node-core tests 68, skipped 0, failures 0
law MiniDbusVectors/recorded-accepted-le: 2   recorded-accepted-be: 2   recorded-ignored: 2   recorded-method-return: 2   outbound-bytes-equal: 2
law MiniDbusVectors/truncated-every-prefix: 1457   oversize-refused-before-read: 10   malformed-rejected: 33   accept-rule-table: 10   sasl-line: 4   stream-framing: 1   unknown-header-field-skipped: 1
MiniDbusIT: connect/Hello/AddMatch and PrepareForSleep true+false in LE and BE against a private dbus-daemon: OK (LAB, NOT DEVICE EVIDENCE)
SleepWatcherIT: PrepareForSleep -> SLEEP_IMMINENT/RESUMED against a private dbus-daemon, LE and BE: OK (LAB, NOT DEVICE EVIDENCE)
law SleepWatcherGap/*: 1 each (7 laws: no-gap-quiet, unannounced-gap-emits-pair, threshold-exact, announced-gap-no-duplicate, stale-announce-does-not-mask, uptime-unreadable, listener-exception-survived)
ControlServer: a real client process at uid 65534 (server user "root") was refused FORBIDDEN by SO_PEERCRED (LAB, this container)
Inhibitor: lock holder ended after its JVM was SIGKILLed (exit 137): OK (LAB, fake systemd-inhibit)
Inhibitor: real systemd-inhibit REFUSED (exit 1: Failed to connect to bus: No such file or directory) in this environment (LAB; a refusal here is expected without logind)
```
`MiniDbusVectorsTest` reads `desktop/node/src/test/resources/dbus/recorded.txt`: REAL bytes recorded from `dbus-daemon 1.14.10` by `record_vectors.py`, an independent Python implementation (LE and BE fake logind, a spoofer, and the bytes the Python encoder makes for `Hello` and `AddMatch`, which the Kotlin encoder must equal byte for byte). The recording showed the daemon delivering a forged UNICAST `PrepareForSleep` to a subscriber whose match rule names `sender='org.freedesktop.login1'`; the client therefore drops unicast signals (ERR-DL2-8).

DL2 gate 2, `systemd-analyze` (real tool, this container, no running systemd). The plan's literal command on the repository file prints a line, because the binary it names is not installed here:
```
$ systemd-analyze verify desktop/packaging/linux/systemd/asom.service
asom.service: Command /opt/asom/current/bin/asom-node is not executable: No such file or directory          exit=1
$ (UnitFilesTest: same unit in a synthetic root holding a copy of the host's unit dir + a stub asom-node + passwd/group)
$ systemd-analyze --root=<synthetic root> verify /usr/lib/systemd/system/asom.service                          (no output)  exit=0
   4 broken copies (Nice=banana, missing ExecStart, unknown directive, unknown section): each prints or fails   (law UnitVerify/broken-unit-is-caught: 4)
$ systemd-analyze --user verify asom-user.service      only "Failed to connect to system bus: No such file or directory" (a container notice), exit 0; a broken copy is caught
$ systemd-sysusers --root=<tmp> desktop/packaging/linux/sysusers.d/asom.conf   ->   asom:x:999:999:asom node:/var/lib/asom:/usr/sbin/nologin
```
The plan's exact command on the INSTALLED unit is step 1 of `systemd-vm.sh` (CI-ONLY, never run). Both units are asserted directive by directive against linux.md 3.2 (`StandardOutput=null`, `StandardError=null`, `LimitCORE=0`, `MemorySwapMax=0`, ...), the packaging tree holds no symlink, `.wants` or `systemctl enable|start`, and the polkit rule is evaluated under `node` with a mock `polkit` object: 15 (action, user) cases, exactly one grant (`asom`, `inhibit-block-sleep`), widened and re-targeted mutants caught. The real polkitd is CI-ONLY.

DL2 gate 3, CI-ONLY items (WRITTEN, NEVER RUN: no systemd, logind or polkit here):
- `desktop/packaging/linux/test/systemd-vm.sh` (real systemd, real logind, real polkit; disposable VM, root) and the `systemd-vm` job in `desktop-linux.yml` (`ubuntu-24.04` only, ERR-DL2-12). `bash -n` passes; `shellcheck 0.11.0` reported only SC2015 info notes on `A && B || C` (where `ok` cannot fail) and one SC2034 warning, which was fixed (shellcheck itself exits 1 on the info notes). Its checks are labelled in the script header; the plan's "`asom status --json | jq -r .host` as a group-`asom` user" is BLOCKED (ERR-DL2-4) and replaced by the local snapshot run as `asom` under `systemd-run`.
- `journal-hygiene.sh` (real journal, CI-ONLY). Its LOGIC is tested against a stubbed journal, logger and CLI (LAB): clean = `PASS: 0 token matches, 0 ledger rows`; a planted canary = `FAIL: 2 token matches, 0 ledger rows`; a planted ledger row = FAIL; a dead positive control = `ERROR` exit 2; an empty unit journal = `ERROR` exit 2. It cannot yet prove the request pipeline (no request path carries a prompt, ERR-DL2-4).
- Real suspend/resume, real logind delay/block behaviour, the polkit rule under a real `polkitd`, the Deck (DV-D4, DV-L2, DV-L3): NEEDS-DEVICE-VALIDATION.

DL2 gate 4, the forked-JVM SIGKILL durability test of the JSONL sink (`:node-core`, run inside `desktopTest` on both JDKs):
```
sigkill harness: 20 kills at 10 (model, point) pairs; claim: process death only, not power loss
transitions exercised: 36/36
```

Isolation and root (real output, `ANDROID_HOME` empty; check 2 with a real SDK is NOT run here):
```
$ desktop/tools/isolation.sh
isolation check 1: 0   check 2: 0 (positive control 19 > 0)   check 3: 0 (positive control 8 = 8)
isolation check 4: base 770a44da21e7... 136 protected files compared byte-for-byte: OK (shipped tree byte-identical to the pinned base)
law: 79 Kotlin/Java sources scanned (no android imports, no frozen-enum reuse, no wildcard bind, no listener but the AF_UNIX control server, no bare print)   law: OK
ISOLATION: all checks passed                 $ python3 desktop/tools/isolation.py --selftest  ->  selftest OK
$ ./gradlew jvmTest --rerun-tasks            BUILD SUCCESSFUL in 1m 48s      root tests: 139 (core+server XML count), 0 failures      (baseline 139)
```

Mutation checks (each applied to the real source, the targeted tests run, the original restored; every one is now CAUGHT). Two mutants first SURVIVED and exposed test gaps, which were fixed and re-run: M5 (the first attempt hit the KDoc comment, not the code) and M9 (no test corrupted a padding byte INSIDE a header field, only the one before the body).
```
M1  control server authorises every peer                 : CAUGHT (3 tests: mock denial, cross-uid uid 65534, platform wiring)
M2  client skips the server-identity check               : CAUGHT (2 tests, one of them run as uid 65534)
M3  control server ignores the socket directory mode     : CAUGHT
M4  block-lock policy check removed (Deck takes a block) : CAUGHT (InhibitorTest, LinuxPlatformTest Deck cases)
M5  lock holder `exec sleep infinity` instead of `cat`   : CAUGHT (SIGKILL leak test)
M6  MiniDbus accepts unicast signals                     : CAUGHT (vectors, IT, SleepWatcherIT)
M7  MiniDbus accepts a non-unique-name sender            : CAUGHT
M8  D-Bus decoder has no size cap                        : CAUGHT
M9  D-Bus decoder tolerates non-zero padding             : CAUGHT (after the added case)
M10 journal-hygiene drops its positive control           : CAUGHT
M11 polkit rule granted to every user                    : CAUGHT
M12 system unit lets stdout reach the journal            : CAUGHT
M13 SleepWatcher never emits RESUMED                     : CAUGHT
M14 a second main source names a listener / the control server names InetSocketAddress : check_law.py exit 1 with VIOLATION (both)
```
One real bug found by a test and fixed: a server that answered `FORBIDDEN` and closed at once made the kernel reset the connection, and the other-uid client saw `Broken pipe` instead of the answer (ERR-DL2-13: graceful close with a 500 ms drain, and the client reads one frame if its write fails). One test-setup trap: a mode passed at directory creation is cut by the process umask, so the directory-mode tests now `chmod` explicitly.

Not verified here: everything CI-ONLY above; the workflow's new job on a hosted runner; JDK behaviour of `SO_PEERCRED` on other kernels (only this Linux 6.18 container); a real logind, polkitd, user manager, suspend, or a Steam Deck; the `ubuntu-24.04-arm` lane; shell scripts other than by `bash -n`, shellcheck and the stubbed journal test. `desktop/README.md` (not owned by this track) still describes DL2 as unbuilt.
Result: PASSED for the LAB parts of DL2 (as amended by ERRATA ERR-DL2-*); BLOCKED for "live `asom status` via a running node's control socket" (ERR-DL2-4); CI-ONLY items written and unrun.
## Windows W0 + W1 + W2 gate (track `windows-w0-w2`) — 2026-09-30 — LAB and CI-APPROX; every Windows result is CI-ONLY / NOT RUN (not device evidence)
Base: `95b4c97d3d820419f1bf8b937c36c65dc6eb9d48` + the uncommitted `windows-w0-w2` working tree (`desktop/packaging/windows/**`, `.github/workflows/desktop-windows.yml`, one include line in `desktop/settings.gradle.kts`, three lines in `.gitattributes`)   JDK: openjdk 21.0.10 and Temurin 17.0.20.1+1   Runner: local container (Ubuntu 24.04 x86_64, NO Android SDK, NO Windows, no Docker daemon, no way to run GitHub Actions). The worktree was created at `98ab632` and was reset to the base exactly as instructed (`git fetch origin claude/asom-v1-build-brief-vw83oh && git reset --hard 95b4c97...`); `git rev-parse HEAD` then printed `95b4c97d3d820419f1bf8b937c36c65dc6eb9d48` and `ls docs/design/mesh/LAB_SPEC.md` succeeded.
Scope: PLATFORM_PLAN section 4 steps W0, W1, W2 (`windows.md` 3 to 10). W0: the `lab-windows` workflow job, the eol check, `.gitattributes`. W1 and W2: module `:packaging:windows:winplatform` (pure JVM, JNA 5.19.1, Kotlin `--release 17` API surface via `-Xjdk-release=17`): `WinPlatform`, `WinPaths`, `NodeMutex`, the JNA bindings behind fakeable ports, keys (`NcryptNik` T2 and T1, `FileNik` T0, `NikTierSelector`), power (request hold and release, suspend watcher), presence, GPU engine probe, thermal zones, `InterfaceEligibility`, `FirewallGate`, `FirewallCommand` (prints, never applies), `WinControlSocket` (ACL policy; does NOT bind), `ServiceHost` and `ServiceEntry`, `WinDoctor`. NOT built (stubs marked NOT-YET-IMPLEMENTED): W3 to W7 (`wix/`, `scripts/*.ps1`, `winget/`, `native/`, `service/`), the tray, the elevated firewall apply step, the control-socket bind. Every spec defect met is in `desktop/packaging/windows/ERRATA.md` (27 rows).

Gate: `./gradlew -p desktop :packaging:windows:winplatform:test` on Linux, Windows ITs SKIPPED, count printed (LAB):
```
$ ./gradlew -p desktop :packaging:windows:winplatform:test --no-daemon ...          (JDK 21.0.10)
winplatform test summary: 141 tests, 114 passed, 0 failed, 27 skipped   (os=Linux; skipped tests are the @EnabledOnOs(WINDOWS) integration tests and the assumption-gated ones)
BUILD SUCCESSFUL in 47s
$ JAVA_HOME=/tmp/asom-jdk17 ./gradlew -p desktop :packaging:windows:winplatform:test --rerun-tasks ...          (Temurin 17.0.20.1, as configured: UTF-8 daemon)
winplatform test summary: 141 tests, 114 passed, 0 failed, 27 skipped
BUILD SUCCESSFUL in 1m 52s
$ JAVA_HOME=/tmp/asom-jdk17 ./gradlew -p desktop :packaging:windows:winplatform:test --rerun-tasks "-Dorg.gradle.jvmargs=-Xmx1500m" ...          (Temurin 17: the daemon and test workers default to US-ASCII, the container locale)
winplatform test summary: 141 tests, 114 passed, 0 failed, 27 skipped
BUILD SUCCESSFUL in 1m 34s
$ python3 desktop/packaging/windows/scripts/check_it_results.py --expect skipped desktop/packaging/windows/winplatform/build/test-results/test
integration tests: 27 found, 0 passed, 27 skipped, 0 failed (expecting: skipped)
integration tests: OK for mode skipped
```
The 27 skipped tests are the `@EnabledOnOs(OS.WINDOWS)` integration tests in `windows/*IT.kt` (DpapiIT 2, SoftwareKspIT 3, PcpIT 1, PowerRequestIT 3, PdhIT 2, AfUnixAclIT 2, MutexIT 2, FirewallProbeIT 2, SystemStateIT 4, IdentityIT 1, WerIT 1, WinPlatformIT 4). `check_it_results.py --expect windows` on the same Linux results FAILS (violations listed, no test passed), which is its negative control. **On Windows every one of them is CI-ONLY / NOT RUN**; the `winplatform` job of `desktop-windows.yml` is what runs them. Law counters (non-vacuity): 9 families, 112 laws, each with a printed non-zero case count, e.g. `law firewall-command/hostile-refused: cases exercised: 43`, `law nik-tier/es256-roundtrip: cases exercised: 200`.

Isolation and law checks (LAB; check 2 with a REAL Android SDK is NOT verified here, the `desktop-isolation-with-sdk` job in `desktop-linux.yml` is its proof):
```
$ ASOM_GRADLE_FLAGS='--no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process' desktop/tools/isolation.sh --with-sdk
isolation check 1 (root settings never name the desktop build): 0   expected 0
isolation check 2 (no Android tooling in the desktop classpath; ANDROID_HOME='' ANDROID_SDK_ROOT=''): 0   expected 0
isolation check 2 positive control (the same output does list the Kotlin plugin, so a 0 above is not an empty read): 19   expected > 0
isolation check 2 again with ANDROID_HOME=/tmp/tmp.OqNRz8lzZX (a directory standing in for an SDK): 0   expected 0   [CI-APPROX]
isolation check 3 (no Android module in the desktop build): 0   expected 0
isolation check 3 positive control (the mapped root projects and the desktop modules are listed): 8   expected 8
isolation check 4: base 770a44da21e7a917071ea1e12f1eed98a91aff03 (from desktop/DESKTOP_BASE_SHA), ancestor of HEAD confirmed
isolation check 4: 136 protected files compared byte-for-byte against base 770a44da21e7
isolation check 4: OK (shipped tree byte-identical to the pinned base)
law: 124 Kotlin/Java sources scanned (no android imports, no frozen-enum reuse, no wildcard bind, no listener, no bare print)
law: OK        ISOLATION: all checks passed
$ python3 desktop/tools/isolation.py --selftest   -> selftest OK (12 negative controls)      $ python3 lab/tools/isolation.py -> check 4: OK
```
Root build unchanged:
```
$ ANDROID_HOME= ./gradlew jvmTest --rerun-tasks --no-daemon ...
BUILD SUCCESSFUL in 1m 9s     21 actionable tasks: 21 executed
root tests: 139   failures+errors: 0   (baseline: 139)
$ git status --short  ->   M .gitattributes    M desktop/settings.gradle.kts    ?? .github/workflows/desktop-windows.yml    ?? desktop/packaging/       (nothing else; taken before this entry was appended to PROGRESS.md)
```
Workflow syntax (the Windows jobs themselves have NEVER run):
```
$ actionlint 1.7.12 .github/workflows/desktop-windows.yml desktop/packaging/windows/ci/desktop-windows.yml     -> no findings, exit 0
$ actionlint 1.7.7  .github/workflows/desktop-windows.yml   -> only: label "windows-11-arm" is unknown (1.7.7's label list predates it; FW39 documents it)
$ python3 -c "yaml.safe_load(...)" -> parsed; jobs: lab-linux-reference, lab-windows, root-unchanged, winplatform, winplatform-linux; the ci/ copy is byte-identical (a test keeps it so)
```

W0 pieces that WERE run here (LAB / CI-APPROX):
```
$ python3 desktop/packaging/windows/scripts/check_eol.py
eol check: 12 files under lab/conformance: all -text and LF
eol check: 4 files under docs/design/mesh/conformance-examples: all -text and LF
eol check: 20 files under docs/design/mesh/manifest-vectors: all -text and LF
eol check: 4 files under desktop/packaging/windows/winplatform/src/test/resources/fixtures: all -text and LF
eol check: OK (40 files under 4 paths)                                          exit=0
negative controls: docs/design/mesh/router-examples (no attribute) -> 3 VIOLATIONs, exit 1;   no/such/dir -> "refusing to pass by scanning nothing", exit 2;
                   scratch repository with a CRLF file in a -text directory -> "VIOLATION: v/x.json: line endings are i/crlf w/crlf", exit 1
$ ./gradlew -p lab labTest --rerun-tasks --no-build-cache ...         (as the spec writes it, JDK 21)      BUILD SUCCESSFUL in 1m 38s, 46 executed
$ ./gradlew -p lab labTest --rerun-tasks --no-build-cache "-Dorg.gradle.jvmargs=-Xmx1500m -Dfile.encoding=windows-1252" ...   (JDK 21)    BUILD SUCCESSFUL in 1m 36s
$ JAVA_HOME=/tmp/asom-jdk17 ./gradlew -p lab labTest ... "-Dorg.gradle.jvmargs=-Xmx1500m"   (Temurin 17, default charset US-ASCII)   BUILD SUCCESSFUL in 1m 48s
$ python3 desktop/packaging/windows/scripts/lab_counts.py lab      TOTAL tests=448 failures=0 errors=0 skipped=7     (10 lines, identical in all three runs; --compare exits 0)
   negative controls of the comparison: one changed count -> "lab counts differ", exit 1;  an empty directory -> "no test result files found", exit 2
$ ./gradlew -p lab :json:test --rerun --debug ... "-Dorg.gradle.jvmargs=-Xmx1500m"   ->  Test Executor started by /tmp/asom-jdk17/bin/java with -Dfile.encoding=US-ASCII
```
FINDING (windows ERRATA WIN-LANE-1): `lab/gradle.properties` sets `-Dfile.encoding=UTF-8` in `org.gradle.jvmargs`, so the lab's test workers run UTF-8 whatever the JDK defaults to, and the W0 lane as the spec writes it CANNOT catch a default-charset regression (AW20). The workflow therefore also runs the lab with the override above. Module-level control (LAB, JDK 21): a deliberate default-charset regression in `JdkTextFiles` PASSES `NativeBindingsTest` under Gradle as configured and FAILS it under the `-Dfile.encoding=windows-1252` override; the restored source passes both. NOT shown: the lab on a real Windows JDK 17, and the plan's own control (a regression injected into the lab fails the JDK 17 lane).

Mutation checks (each applied to the real source, the WHOLE `winplatform` test task run, then the original restored; all 20 KILLED, none survived, none failed to compile):
```
M01 probe failure read as OPEN                           KILLED (5 tests)   M11 store creates a key when only asked its tier        KILLED (3)
M02 block rules ignored                                  KILLED (5)         M12 hold release does not clear the power request       KILLED (2)
M03 AUTO falls through to the file tier                  KILLED (2)         M13 T0 file written without checking the directory DACL KILLED (2)
M04 key self-test skipped                                KILLED (2)         M14 display-off is not a drain                          KILLED (2)
M05 ACL verifier ignores foreign principals              KILLED (5)         M15 own GPU load not subtracted                         KILLED (3)
M06 firewall command alias not validated                 KILLED (2)         M16 roaming profile accepted                            KILLED (1)
M07 unreadable input idle counted as absent              KILLED (3)         M17 firewall probe: no judgeable rules is not a failure KILLED (3)
M08 lock accepted from one signal only                   KILLED (2)         M18 Everyone/Users may be planned into a DACL           KILLED (1)
M09 loopback allowed as a peer address                   KILLED (1)         M19 service key DACL adds Everyone                      KILLED (2)
M10 process allowlist removed                            KILLED (1)         M20 control socket start binds instead of refusing      KILLED (5)
```
Two design faults found and fixed while writing (not by a run on Windows): the LAN interface rule accepted Tailscale's own ULA block (`fd7a:115c:a1e0::/48` lies inside `fc00::/7`; WIN-NET-2), and the named mutex would have leaked a handle that keeps the object alive (WIN-MUTEX-1).

NOT VERIFIED (every item is `CI-ONLY / NOT RUN` or `NEEDS-DEVICE-VALIDATION`): anything on Windows: every `NCrypt*`, `Pdh*`, `Wts*`, `Power*`, `Wer*`, `OpenMutexW`/`CreateMutexW`, `GetLastInputInfo`, `SHQueryUserNotificationState`, `GetIfEntry2` binding (written from the documentation, layouts cross-checked against JNA `Structure` offsets on Linux only); that JNA raises the right `LastErrorException` for the mutex; the netsh parser on REAL netsh output (the fixtures are SYNTHETIC hand-written listings); the `lab-windows` and `winplatform` jobs and whether `actions/setup-python` has Python for `windows-11-arm`; the TPM tier (S-W1, AW01, AW02), the service-account key (S-W2), AF_UNIX peer credentials (S-W3), GPU counters (S-W4), thermal zones (S-W5), lock polarity and display state (S-W6), sleep, lid, Modern Standby, battery saver, presence with a person, Tailscale, real firewall profiles. `desktop-linux.yml`'s `desktop-isolation-with-sdk` job (real SDK) has not run. The control socket does NOT bind (WIN-CTL-1), the firewall helper only prints (WIN-FW-1), and JNA is a new dependency awaiting D23. `desktop/build.gradle.kts` `desktopTest` and `check_law.py` `ALLOWED_DEPS` still omit this module (WIN-GATE-1; not this track's files).
Result: PARTIAL by design. W0 and W1/W2 as far as a Linux container can honestly go: PASSED (LAB and CI-APPROX); the Windows halves of the W0 and W1/W2 gates are written and NOT RUN.

## Lab L0.6 gate (track `lab-router-sim`: `:mesh-router`, `:mesh-sim`, vector families R01, R02, R03, R05, R06, M08) — 2026-09-30 — LAB and SIMULATED (not device evidence)
Base: dc8a45dc4ced1cb9c4b80eaf54c4ad17b7758b4e (working tree, uncommitted, not pushed)   JDK: openjdk 21.0.10 (JDK 17 lane NOT run here)   Runner: local   Every simulator line below is SIMULATED — NOT DEVICE EVIDENCE.
```
$ ./gradlew -p lab :mesh-router:test :mesh-sim:test :conformance-runner:run --args='lines R01,R02,R03,R04,R05,R06,M08'     BUILD SUCCESSFUL in 1m 10s
tests: mesh-router 35, mesh-sim 36, 0 failures, 0 errors, 0 skipped (the test tasks are never up-to-date and never cached: outputs.upToDateWhen { false })
lines mode: 343 vector lines (R01 81, R02 46, R03 31, R04 28, R05 44, R06 54, M08 59), every one `ok` or `reject <CODE>`, none an error
$ ./gradlew -p lab :conformance-runner:test      BUILD SUCCESSFUL
family R01: 81 vectors, 81 pass, 0 fail   R02: 46/46   R03: 31/31   R04: 28/28 (unchanged)   R05: 44/44   R06: 54/54   M08: 59/59      oracle: self on every line
```
Router laws (`:mesh-router:test`, property tests with an independent oracle, seeds 1..20, floor 100; the count is what exercised the law):
```
RL1a 1365   RL1b 330   RL1c 412   RL1 (the 28 R04 vectors through MeshRouter, 4 variants each) 112   RL2 438   RL3 1600   RL4 143   RL5 495   RL6 1600   RL7 1098   RL8 179   RL9 115
RL10 702   RL10b 420   RL11 130   RL12 1086   RL13a 256   RL13b 156   RL18 171   RL20 20000 (largest P x 10^6 = 1,048,489,000,000 < 2^53)   RL20b 600   RL-H 1097   M08 no-peer-number 300
every line reads `violations: 0`
```
Simulator laws (`:mesh-sim:test`; the oracles read the run records, the event log and the wire trace; floor 100 asserted for each):
```
RL4 8942   RL14 104019 (L-L1, L-L2, L-L3, L-L16 frame-byte sum, join on attemptId)   RL15 50403 (L-L4, L-L5)   RL15b 11101 (L-L5b over 10,786 requests, plus 315 error-path requests checked one by one: terminal row, header, status, reach)   RL16 10790   RL17 9154   RL18 236 (+ returned after cooldown, asserted > 0)
RL19 100 runs (Jain minimum 965 permille; on-time at most 258 permille, so every run is saturated)   RL21 10790 decisions replayed, diff empty   RL22 406        L-L1..L-L14 over every trace: 0 violations; L-L13 24 cases (F-ledger-full)
scenarios for seeds 1..20: SC01 (Mac serves >= 950 permille of chat, phone energy below B1 by >= 800 permille), SC04 (20 of 20 seeds: typed MESH_STREAM_INTERRUPTED, joined ledgers, no attempt after delivered bytes),
   SC06 (DISCREPANT within 5 kept observations; the liar's share after request 50 <= B4's + 100 permille), SC09 (zero peer or cloud attempts for device-only and local-only requests): all pass
fault kinds: all 17 exercised by their own scenario under seeds 1..3 and pass the laws (FaultInjectionTest); T-RL4 flips the registry 1 ms after offers: 366 attempts cancelled before the body
```
Simulator run (per-scenario table; `./gradlew -p lab :mesh-sim:run --args='--replay --out /tmp/simgate'`; output files `events.jsonl` and `decisions.jsonl` each start with the record {"label":"SIMULATED — NOT DEVICE EVIDENCE"}):
```
scenario | seed | variant | decision | reason | ledger rows | law violations | SIMULATED — NOT DEVICE EVIDENCE
SC01  | seed 1   | B3        | decision: mac first (988 permille)   | reason: peer:best-score/time         | ledger rows: 11821  | law violations: 0 | SIMULATED — NOT DEVICE EVIDENCE
    replay SC01: 1067 decisions replayed from events.jsonl, diff EMPTY | SIMULATED — NOT DEVICE EVIDENCE
SC04  | seed 4   | B3        | decision: phone first (854 permille) | reason: self:only-eligible           | ledger rows: 201    | law violations: 0 | SIMULATED — NOT DEVICE EVIDENCE
    replay SC04: 55 decisions replayed from events.jsonl, diff EMPTY | SIMULATED — NOT DEVICE EVIDENCE
SC06  | seed 6   | B3        | decision: mac first (658 permille)   | reason: peer:best-score/time         | ledger rows: 3594   | law violations: 0 | SIMULATED — NOT DEVICE EVIDENCE
    replay SC06: 390 decisions replayed from events.jsonl, diff EMPTY | SIMULATED — NOT DEVICE EVIDENCE
SC09  | seed 9   | B3        | decision: phone first (780 permille) | reason: self:only-eligible           | ledger rows: 367    | law violations: 0 | SIMULATED — NOT DEVICE EVIDENCE
    replay SC09: 100 decisions replayed from events.jsonl, diff EMPTY | SIMULATED — NOT DEVICE EVIDENCE
SIMULATED — NOT DEVICE EVIDENCE: 4 run(s) of 4 scenario(s), 0 with law violations
```
Isolation and root:
```
$ lab/tools/isolation.sh      check 1: 0 (expected 0)   check 2: 0 (positive control 19)   check 3: 0 (positive control 15)   check 4: 136 protected files byte-identical to base 770a44da21e7   law: 157 sources scanned, OK   ISOLATION: all checks passed
   (the first run FAILED, honestly: `mesh-sim` named `:json` and two test files spelled the frozen egress enum member; both fixed, then re-run)
$ ./gradlew cleanTest jvmTest --no-build-cache      root tests: 139, failures 0 (baseline 139)
   The FIRST uncached run failed one test, `AsomServerIntegrationTest.every routed request writes exactly one ledger row` (a read of the ledger straight after the response, the race ERR-W01B-3 describes); nothing under core/ or server/ is touched by this track; the re-run passed 139/0. Reported, not hidden.
```
Mutation checks (each applied to the real source, the targeted tests run, the original restored and diffed against the saved copy; every one FAILED as required except where stated):
```
tracker reads a peer-reported token count (AttemptObserver, usage.completion_tokens)     : noPeerSuppliedNumberCanRaiseAClaim FAILS ("changed the answer length: expected 1200 but was 40000000")
tracker no longer only lowers (clamp min(1000, ..) and final min(rate, claim) removed)    : 2 tests fail (observationOnlyLowersAClaim, paddingToTheCapInflates...)
tracker never says DISCREPANT (best < DISC returns WEAK)                                  : SC06 fails ("the liar never reached DISCREPANT")
cap bound weakened (ceilDiv(wouldWin, 4) -> ceilDiv(wouldWin, 2))                         : RL11 fails
merge order (auto: cloud placed before usable sovereign)                                  : 4 laws fail (RL1c, RL10, RL1, RL11)
hard filter F9 removed (peer FSM not SERVING)                                             : RL5 fails
peer transport cooldown shortened (30 s -> 10 s)                                          : RL18 fails in the simulator (20 violations in the suite run, 28 in T-RL18)
provider breaker curve changed (cap 900 s -> 600 s)                                       : R06-050 (breaker pinned to the real CooldownRegistry) fails
failover: mid-stream loss retried instead of failing in band                              : SC04 fails ("the mid-stream vanish interrupted no request")
body-send guards removed (eligibleNow and worseNow)                                       : T-RL4 fails (494 RL4 violations)
body-send guard `eligibleNow` alone removed                                               : NOT CAUGHT (the re-plan in worseNow already drops an ineligible peer; the two guards are redundant against this test)
plan computed from state that is not in the event log (hidden reservation on the Mac)     : RL21 fails (19,238 violations)
```
Findings for the owner (lab/ERRATA.md ERR-R6-*): (1) R3-OVERCLAIM-1 stands: the padding worst case is 6.8x in bytes and 5.4x in predicted time, not 2x; the tracker still only lowers a claim. (2) R6-FINDING-COLD (ERR-R6-14): a lender's cold-load time is in `elapsed` but not in `predicted`, so an HONEST lender used less often than every 300 s reaches DISCREPANT after 5 kept observations (7 of 8 seeds of T-COLD); not fixed, pinned by FindingsTest. (3) SC01's 950 permille cannot hold over a short run because RL11 gives an UNVERIFIED key 1 win in 4 (ERR-R6-15). (4) BLOCKED and not invented: classCeiling, signedReferenceP90 values, confFloor, knownBadConf, the 1-in-10 brand-new-peer cap (ERR-R6-3).
Not verified here: JDK 17 lane; any device; the simulator's numbers are invented lab values, not measurements, and its thermal model is a threshold; RL19 is a weak law in a simulator without per-app scheduling (ERR-R6-17); the `duplicate-attempt` scenario skips L-L12, L-L16 and RL22 by construction (ERR-R6-20); the scenarios live in lab/mesh-sim/, not lab/conformance/scenarios/ (ERR-R6-13); no independent implementation has agreed with the vectors yet (`ref.py` is same-session).
Oracle status: self-oracled (hand-typed expectations cross-checked by `lab/mesh-router/tools/ref.py`, an independent reference written in the same session; it never clears the oracle tag)
Result: PASSED (local LAB and SIMULATED evidence only)
## macOS MC1 + MC2 gate (track `macos-mc1-mc2`) — 2026-09-30 — LAB and CI-APPROX; every macOS result is CI-ONLY / NOT RUN (not device evidence)
Base: `dc8a45dc4ced1cb9c4b80eaf54c4ad17b7758b4e` + the uncommitted `macos-mc1-mc2` working tree (`desktop/packaging/macos/**`, `.github/workflows/desktop-macos.yml`, one include line in `desktop/settings.gradle.kts`)   JDK: openjdk 21.0.10   Swift: 6.1 on Linux x86_64   Runner: local container (Ubuntu 24.04, NO Android SDK, NO macOS, no Xcode, no way to run GitHub Actions). The worktree was created at `98ab632` and was reset to the base exactly as instructed (`git fetch origin claude/asom-v1-build-brief-vw83oh && git reset --hard dc8a45dc...`); `git rev-parse HEAD` then printed `dc8a45dc4ced1cb9c4b80eaf54c4ad17b7758b4e` and `docs/design/mesh/LAB_SPEC.md` existed. Nothing was committed or pushed.
Scope: PLATFORM_PLAN section 5 steps MC1 and MC2 (`macos.md` 3 to 10). MC1: module `:packaging:macos:macplatform` (pure JVM, no new third-party dependency, no JNA, Kotlin `--release 17` with `-Xjdk-release=17`): `MacPlatform`, `MacPaths`, `MigrationGuard`, `NodeLock`, the helper client (`HelperProcess`, `HelperClient`, strict codec), keys (`SecureEnclaveNik` T2, `FileNik` T0, `NikTierSelector`, `MacNikStore`; no T1), power (one shared assertion, 2 s sleep acknowledgement), presence, thermal, GPU, `InterfaceEligibility`, `MacControlSocket` (getpeereid check in both directions; does NOT bind), `ServiceRegistration`, `MacDoctor`. MC2: Swift package `desktop/packaging/macos/helper` (`HelperProtocol` builds and is tested on Linux; the `asom-mac-helper` executable is behind `#if os(macOS)`), `helper-protocol/` (`SCHEMA.md`, 404 vectors in 7 `jsonl` files), the AM14 demo script, the `macplatform` workflow job. NOT built (stubs marked NOT-YET-IMPLEMENTED, scripts exit 3): MC3 and later (`launcher/`, `native/`, `resources/`, `homebrew/`, twelve scripts, `docs/MACOS_NODE.md`), the tray, mode B, the control-socket bind. Every spec defect met is in `desktop/packaging/macos/ERRATA.md` (39 rows, `MAC-*`).

Gate: `./gradlew -p desktop :packaging:macos:macplatform:test` on Linux, macOS ITs SKIPPED, count printed (LAB; two consecutive clean runs, the second after the last edit):
```
$ GRADLE_OPTS=-Xmx1g ./gradlew -p desktop :packaging:macos:macplatform:test --rerun-tasks --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process
macplatform test summary: 141 tests, 130 passed, 0 failed, 11 skipped   (os=Linux; skipped tests are the @EnabledOnOs(MAC) integration tests and the assumption-gated ones)
BUILD SUCCESSFUL in 2m 39s
18 actionable tasks: 18 executed
$ python3 desktop/packaging/macos/scripts/check_it_results.py --expect skipped desktop/packaging/macos/macplatform/build/test-results/test
integration tests: 11 found, 0 passed, 11 skipped, 0 failed (expecting: skipped)
integration tests: OK for mode skipped                                                   exit=0
$ python3 desktop/packaging/macos/scripts/check_it_results.py --expect mac <the same directory>      (negative control)      exit=1
VIOLATION: SKIPPED on macOS (not allowed): HelperIT: hostile lines are answered with the schema's errors and the helper keeps working() ... VIOLATION: no integration test passed
```
The 11 skipped tests are the `@EnabledOnOs(OS.MAC)` integration tests (SecureEnclaveIT 2, HelperIT 5, ControlSocketIT 2, PowerAssertionIT 2). **On a Mac every one of them is CI-ONLY / NOT RUN**; the `macplatform` job of `desktop-macos.yml` is what runs them. Law counters (non-vacuity; each prints its case count and the test fails at zero): 7 families, 82 laws, 0 with zero cases: keys 13 laws / 18 cases, net-control 12 / 100, paths-guard 16 / 43, ports 11 / 26, presence 9 / 78, scripts 9 / 30, service-doctor 12 / 56. One earlier run showed a flaky test (a loss-listener assertion raced the listener notification, which runs after the failed calls are released); the test now waits for the listener, and the source was not changed.

Gate: Swift protocol target on Linux, and the two lanes over the same vectors (LAB; the lanes are SELF-ORACLED, one author wrote both codecs and the expectations, so this is "cross-lane agreement", not independent evidence):
```
$ swift test --package-path desktop/packaging/macos/helper
	 Executed 29 tests, with 0 failures (0 unexpected) in 0.212 (0.212) seconds
$ desktop/packaging/macos/scripts/check-protocol-lanes.sh
BUILD SUCCESSFUL in 19s
cross-lane: 404 vectors in desktop/packaging/macos/helper-protocol/vectors; Swift lane printed 404 lines, Kotlin lane printed 404 lines
cross-lane: the two lanes are byte-identical over 404 vectors
```

Isolation and law checks (LAB; check 2 with a REAL Android SDK is NOT verified here, the `desktop-isolation-with-sdk` job in `desktop-linux.yml` is its proof):
```
$ ASOM_GRADLE_FLAGS='--no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process' desktop/tools/isolation.sh --with-sdk
isolation check 1 (root settings never name the desktop build): 0   expected 0
isolation check 2 (no Android tooling in the desktop classpath; ANDROID_HOME='' ANDROID_SDK_ROOT=''): 0   expected 0
isolation check 2 positive control (the same output does list the Kotlin plugin, so a 0 above is not an empty read): 19   expected > 0
isolation check 2 again with ANDROID_HOME=/tmp/tmp.wM9O3Xu8Q1 (a directory standing in for an SDK): 0   expected 0   [CI-APPROX]
isolation check 3 (no Android module in the desktop build): 0   expected 0
isolation check 3 positive control (the mapped root projects and the desktop modules are listed): 8   expected 8
isolation check 4: 136 protected files compared byte-for-byte against base 770a44da21e7
isolation check 4: OK (shipped tree byte-identical to the pinned base)
law: 192 Kotlin/Java sources scanned (no android imports, no frozen-enum reuse, no wildcard bind, no listener but the AF_UNIX control server, no bare print)
law: OK        ISOLATION: all checks passed
$ python3 desktop/tools/isolation.py --selftest -> selftest OK
```
Root build unchanged:
```
$ ANDROID_HOME= ./gradlew jvmTest --rerun-tasks --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process
BUILD SUCCESSFUL in 1m 45s     21 actionable tasks: 21 executed
root tests: 139   failures+errors: 0   (baseline: 139)
$ git status --short  ->   M desktop/settings.gradle.kts    ?? .github/workflows/desktop-macos.yml    ?? desktop/packaging/macos/      (nothing else; taken before this entry was appended to PROGRESS.md)
```
Workflow and script syntax (the macOS jobs themselves have NEVER run):
```
$ actionlint 1.7.12 .github/workflows/desktop-macos.yml desktop/packaging/macos/ci/desktop-macos.yml    -> no findings, exit 0
$ yaml.safe_load -> jobs: helper-protocol-linux, lane-diff, macplatform, macplatform-linux, root-unchanged; the ci/ copy is byte-identical (a test keeps it so); every action pinned by commit SHA (resolved with git ls-remote)
$ bash -n and shellcheck on desktop/packaging/macos/scripts/*.sh -> clean, exit 0
```

Mutation checks (each applied to the real source, the relevant test classes run, then the original restored; 50 mutants, all 50 KILLED, none survived; two first came out as compile errors, were rewritten so that they compile, and were then KILLED). Kotlin M01 to M40, Swift S01 to S10:
```
M01 codec accepts unknown fields                         KILLED    M21 lock file opened through a symbolic link             KILLED
M02 JSON parser allows duplicate names                   KILLED    M22 another console user is not presence                 KILLED
M03 \u escape accepts non-ASCII digits                   KILLED    M23 unknown HID idle counts as idle                      KILLED
M04 base64 without the canonical round trip              KILLED    M24 idle threshold minimum 0                             KILLED
M05 line limit off by one                                KILLED    M25 UPS does not count as battery                        KILLED
M06 CR tolerated inside a line                           KILLED    M26 unknown thermal state is band 0                      KILLED
M07 assert.hold reason not enforced                      KILLED    M27 sleep drain not cut off at the ack budget            KILLED
M08 helper restart limit ignored                         KILLED    M28 every IPv4 address is a LAN address                  KILLED
M09 helper inherits the whole environment                KILLED    M29 overlay block is a LAN address                       KILLED
M10 hung helper not killed at the call timeout           KILLED    M30 control socket authorises every peer                 KILLED
M11 lost helper read as no-Enclave (falls to T0)         KILLED    M31 doctor pmset allowlist accepts any arguments         KILLED
M12 T2 self-test skipped                                 KILLED    M32 daemon mode allowed                                  KILLED
M13 existing T2 that fails self-test downgrades to T0    KILLED    M33 own GPU load not subtracted                          KILLED
M14 existing identity may be replaced                    KILLED    M34 presence blocks dropped from the rules               KILLED
M15 new private files created with the default mode      KILLED    M35 fixed assertion reason changed                       KILLED
M16 socket path limit 104 instead of 103                 KILLED    M36 T0 key written into a non-private directory         KILLED
M17 directory check ignores group bits                   KILLED    M37 closing the client does not close helper stdin       KILLED
M18 binding mismatch read as bound                       KILLED    M38 battery percent rounds up                            KILLED
M19 identity without a binding read as fresh             KILLED    M39 daemon registration allowed                         KILLED
M20 per-process lock registry removed                    KILLED    M40 doctor reports the assertion as always held          KILLED
S01 Swift parser allows duplicate names                  KILLED    S06 Swift duplicate check by String equality             KILLED
S02 Swift base64 without the canonical round trip        KILLED    S07 Swift UTF-8 accepts overlong E0 80                   KILLED
S03 Swift line splitter limit off by one                 KILLED    S08 Swift codec accepts unknown fields                   KILLED
S04 Swift dispatcher takes a second assertion            KILLED    S09 Swift dispatcher skips the version check             KILLED
S05 Swift dispatcher does not release at EOF             KILLED    S10 Swift codec does not require an absolute path        KILLED
```
Findings fixed or recorded while writing (found by a run here, not on a Mac): a failed second `NodeLock` attempt released the first holder's POSIX lock (POSIX locks are per process; fixed with a per-process registry, MAC-LOCK-3, mutant M20); `:node-core`'s `StrictJson` accepts non-ASCII digits in `\u` escapes (MAC-JSON-1, shown by a real run; this track has its own parser and vector HP-REQX pins it; not edited in node-core); Swift `String` equality is canonical equivalence (names `é` and `é` would be called duplicates; the parser compares bytes, mutant S06).

NOT VERIFIED (every item is `CI-ONLY / NOT RUN` or `NEEDS-DEVICE-VALIDATION`): **the macOS-only Swift sources of the helper (`helper/Sources/asom-mac-helper/*.swift` except `main.swift`) have never been compiled**, only parsed (`swiftc -parse`); every IOKit, CryptoKit Secure Enclave, `SMAppService`, `IOPS` and `IORegisterForSystemPower` call is written from the documentation; the `macplatform` and the other four jobs of `desktop-macos.yml` (never run), including whether the pinned Swift image digest still resolves and whether `macos-latest` has the tools the job assumes; the 11 macOS integration tests; the Secure Enclave tier (S-M1, AM01, AM02: whether a Developer-ID-signed helper with no entitlements can create and use a key), the raw `r||s` signature form, the copied-blob behaviour (FM15); `SMAppService` from a secondary executable (AM03, needs MC4); IOPM assertion release on SIGKILL (AM11); `getpeereid` through the JDK and the real per-user socket path limit (FM27); HID idle, screen lock and console-user reads (AM12), GPU counters (AM13); the `security` command line weakness (AM14, `demo-keychain-cli-weakness.sh` never ran on a Mac); `socketfilterfw` and `pmset` output parsers (written against SYNTHETIC listings); Local Network privacy (AM09); Tailscale variants; sleep, lid and clamshell (D-PWR1, D-PWR2); the presence rule with a person. No Team ID exists (M-D10), so every run is an unsigned dev-state run. `desktop/build.gradle.kts` `desktopTest` and `check_law.py` `ALLOWED_DEPS` still omit this module (MAC-GATE-1; not this track's files). The device list is `desktop/packaging/macos/docs/DEVICE_CHECKLIST_MACOS.md`.
Result: PARTIAL by design. MC1 and MC2 as far as a Linux container can honestly go: PASSED (LAB and CI-APPROX); the macOS halves of the MC1 and MC2 gates are written and NOT RUN.
## Ubuntu Touch UT0.1 to UT0.7 gate (track `ubuntu-touch-ut0`) — 2026-09-30 — LAB and CI-APPROX; UT0.6 is NEEDS-DEVICE-VALIDATION (not device evidence)

Base `dc8a45dc4ced1cb9c4b80eaf54c4ad17b7758b4e` (pinned in `ubuntu-touch/UT_BASE_SHA`). Everything below is new under `ubuntu-touch/**` and `.github/workflows/ubuntu-touch.yml`; nothing else is touched. Nothing was committed or pushed. Every artefact is UNSIGNED. Per-file VERIFIED-BY / UNVERIFIED labels are in `ubuntu-touch/README.md`; spec readings in `ubuntu-touch/ERRATA.md` (ERR-UT-*).

```
$ ./gradlew -p ubuntu-touch/jvm utTest --rerun-tasks --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process     (JDK 21.0.10)
      law UTC05/stdout-only-frames: 27 cases
> Task :utTest      BUILD SUCCESSFUL in 2m 53s      36 actionable tasks: 36 executed
ut-host tests: 49   failures+errors: 0
$ (same command through the wrapper on Temurin 17)
> Task :utTest      BUILD SUCCESSFUL in 3m 3s       36 actionable tasks: 36 executed
ut-host tests: 49   failures+errors: 0
$ ./gradlew jvmTest --rerun-tasks ...     (root build, JDK 21)
BUILD SUCCESSFUL in 1m 23s        root tests: 139   failures+errors: 0   (baseline: 139)
git diff --exit-code -- core server settings.gradle.kts build.gradle.kts gradle.properties gradle .github/workflows/ci.yml   -> clean
$ ubuntu-touch/tools/isolation.sh --with-sdk
isolation check 4: 136 protected files compared byte-for-byte against base dc8a45dc4ced ... OK
law: settings.gradle.kts maps 16 projects by directory, no includeBuild ... law: OK       ISOLATION: all checks passed
$ ubuntu-touch/runtime/jlink.sh     (cross-jlink from the pinned aarch64 Temurin 21.0.12.1+1 tarball, sha256 23e37e02...c773e223 matches temurin.lock)
rt: 39 MB   bin/java: ELF 64-bit LSB pie executable, ARM aarch64   modules: java.base,java.logging,jdk.crypto.ec,jdk.unsupported
ELF files with .debug_* sections: 0   max GLIBC: 2.17 (over 15 ELF files)   rt.sha256: 49 files, manifest sha256 1c5089ac...261f1f0a
$ ./gradlew -p ubuntu-touch/jvm utNodeJar        asom-ut-node.jar 4243300 bytes
$ tools/check_jar.py java asom-ut-node.jar --limit-modules java.base,java.logging,jdk.crypto.ec,jdk.unsupported
selftest: 1 stdout line(s), stderr '', exit 0: ok
selftest: tls TLSv1.3, alpn asom-mesh/1, vectors {'M01': 105, 'M02': 18, 'M03': 71, 'failed': 0}, rssKiB 137672
--profile=ut session: frames ['hello_ack','state','error','state','peers','rows'], stderr '', exit 0, ledger mode 0o600: ok
check_fake_nodes: OK (29 identical frames)      check_jar: OK
$ tools/run_qml_tests_local.sh      (Qt 5.15.13, offscreen; plugin built locally)      tst_plugin (native, Qt Test): Totals: 16 passed, 0 failed;  qmltestrunner: Totals: 41 passed, 0 failed
$ actionlint .github/workflows/ubuntu-touch.yml   rc=0            $ tools/regen_utc_index.py --check   INDEX.json is current (5 files)
```

Mutation checks (each applied to the real source once, the WHOLE `:ut-host:test` task, or the plugin rebuild plus ctest, run, then the file restored): 25 Kotlin and 6 C++, 31 in all. First run: 24 of 25 Kotlin KILLED, 1 SURVIVED (`line-cap-off-by-one`: `FrameCodec.decodeUi` has its own TOO_LONG check, so the vectors could not see the reader boundary; ERR-UT-TEST-1). A test was ADDED (`ControlChannelTest.readerCapIsExactAtEveryBufferAlignment`; none was weakened), the mutant re-run: KILLED. Final: Kotlin 25/25 KILLED, C++ 6/6 KILLED (`cap-off-by-one`, `manifest-hash-ignored`, `manifest-path-escape`, `send-allows-newline`, `heartbeat-while-inactive`, `jar-hash-ignored`), none broken. Kotlin mutants cover the L-UT1 window, watchdog gap, idle close, mayDial, permit, display release, attempt cancellation, the frame closed-set, integer bounds, writer cap, Invariant-9 projection, alias sanitising, error order, secret leak into a frame, stdout takeover, crash exit code, TLS pin, XDG/HOME containment, directory mode, hello ordering.

NOT VERIFIED (each is `CI-ONLY / NOT RUN` or `NEEDS-DEVICE-VALIDATION`): UT0.3 the arm64 run of the jlinked runtime (the image was inspected, never run); UT0.4 and UT0.5 `clickable build`/`clickable test` (no Docker daemon here); UT0.7 `run-approx.sh` (needs `apparmor_parser` against the pinned policy tree in a container; the profile was only parsed locally); `.github/workflows/ubuntu-touch.yml` has never run on GitHub; the display hold, the real Lomiri styling and freeze behaviour on a device; **UT0.6 (S-UT1, the JVM self-test under real confinement) is NEEDS-DEVICE-VALIDATION**: `ubuntu-touch/docs/DEVICE_CHECKLIST_UT.md` DV-UT01 stays open until the owner runs it.
Result: PARTIAL by design. UT0.1 and UT0.2 and the LAB halves of UT0.3 to UT0.5: PASSED (LAB and CI-APPROX). UT0.6 open. UT0.7 written, not run.

## DL3 Linux packaging (`linux-packaging-dl3`), 2026-09-30

Scope: PLATFORM_PLAN section 3 step DL3 (`desktop/packaging/linux/**` except DL2's units, sysusers.d, polkit, `journal-hygiene.sh`, `systemd-vm.sh`; `desktop/docs/DEVICE_CHECKLIST_LINUX.md`; the `package-linux` and `install-matrix` jobs of `.github/workflows/desktop-linux.yml`; `:packaging:windows:winplatform:test` added to the `desktop-jvm` job). Base `dc8a45dc4ced1cb9c4b80eaf54c4ad17b7758b4e` (worktree reset to it first). **Evidence label: LAB (one container, x86_64, Ubuntu OpenJDK 21.0.10, NOT Temurin; JDK 17 not used for packaging). NOT DEVICE EVIDENCE. CI-ONLY items were NOT run.** Every artifact is UNSIGNED, not for release. Spec defects and the reading taken: `desktop/packaging/linux/ERRATA.md` (ERR-DL3-1 to 14).

Gate 1, app image (`bash desktop/packaging/linux/build-app-image.sh --arch x86_64`, Gradle run inside it):
```
build-app-image: WARNING runtime vendor is 'Ubuntu', not Eclipse Temurin (LD-3). This image is LAB evidence only.
jdeps reported:  java.base,java.desktop,java.instrument,java.logging,java.management,jdk.net,jdk.unsupported
shipped modules: java.base,java.logging,java.management,jdk.crypto.ec,jdk.net,jdk.unsupported   (derived 5, added 1, excluded 2)
jlink-modules: OK (jdeps output equals derived + excluded; jdk.net and jdk.crypto.ec present)
build-app-image: jlink --add-modules java.base,java.logging,java.management,jdk.crypto.ec,jdk.net,jdk.unsupported
check-native-deps: scanned 18 ELF files; system libraries needed: ld-linux-x86-64.so.2 libc.so.6 libgcc_s.so.1 libm.so.6 libstdc++.so.6 libz.so.1
check-native-deps: OK (all within the declared Depends set)
build-app-image: runtime Ubuntu 21.0.10, 6 modules in the image, 52M on disk
build-app-image: launcher says: asom-node 0.0.0-scaffold (desktop scaffold; UNSIGNED, not for release)
app image: build/asom-desktop-0.0.0-scaffold-linux-x86_64
```
Gate 2, nfpm (2.41.3 downloaded by `fetch-nfpm.sh`, SHA-256 checked against the pinned value before extraction; the pin is the release's own `checksums.txt`, a same-origin check, ERR-DL3-9):
```
created package: desktop/packaging/linux/build/dist/asom-desktop_0.0.0~scaffold_amd64.deb
created package: desktop/packaging/linux/build/dist/asom-desktop-0.0.0~scaffold-1.x86_64.rpm
```
Gate 3, `dpkg-deb -c *.deb | grep -c usr/lib/systemd/system/asom.service` -> `1`. Listing (159 entries; runtime and jars elided): `/opt/asom/0.0.0-scaffold/{bin/asom,bin/asom-node,lib/,share/{doc,polkit,systemd,sysusers.d}}`, `/opt/asom/current -> 0.0.0-scaffold`, `/usr/bin/asom -> /opt/asom/current/bin/asom`, `/usr/lib/systemd/system/asom.service`, `/usr/lib/systemd/user/asom.service`, `/usr/lib/sysusers.d/asom.conf`, `/usr/share/polkit-1/rules.d/50-asom-inhibit.rules`; all root/root; no `.wants`, no preset, no `/etc`, no installers. `Depends: libc6, libstdc++6, libgcc-s1, zlib1g`, `Recommends: libvulkan1`, `Version: 0.0.0~scaffold`. The rpm was built by nfpm but NOT inspected with `rpm -qlp` (no rpm tool here; the CI job does).

Packaged runtime, `asom-node --mode=selftest` from the tarball install AND from the extracted deb (via `/opt/asom/current`), as user `nobody` (the node refuses root: as root it exits 78, also checked):
```
asom-node selftest (host linux, mode foreground) asom-node 0.0.0-scaffold (desktop scaffold; UNSIGNED, not for release)
  [ok] host-identity ... [ok] ledger-roundtrip: one row appended, forced and read back intact
  [not-yet-implemented] nik-store (planned tier: file): DL2
  [not-yet-implemented] control-socket (serving from asom-node: built and tested, not started until D23/D25 are ruled): D23/D25
selftest: 11 ok, 2 not-yet-implemented, 0 failed
runtime-probe: jdk.net SO_PEERCRED OK (accepted side: nobody, client side: nobody)
runtime-probe: ES256 OK (secp256r1, DER and P1363 forms sign, verify and reject a tampered message)
runtime-probe: TLS1.3 handshake OK (in-memory SSLEngine, TLSv1.3, TLS_AES_256_GCM_SHA384, EC certificate, host name checked; NOT the mesh pinned-identity handshake)
```
The plan's expected `self-test: native OK (none), ES256 OK, TLS1.3 pinned handshake OK` was NOT produced: the node's selftest has no such lines (ERR-DL3-2). The probe replaces it and says what it is not.

`test/lab-packaging-check.sh` (root, `ASOM_REQUIRE_UNSHARE=1`): `checks passed: 104   failed: 0` in five families, none zero: install-tarball 27, install-refusals 27 (incl. 9 hostile archives: `..`, absolute path, symlink escape, absolute symlink, hard link, wrong architecture, two top dirs, not an asom image, bad name; and a corrupted archive, no SHA256SUMS, unlisted name), uninstall 12 (incl. `--purge` refused without a terminal, refused on a wrong phrase on a real pty, deletes only on the exact phrase), deb-layout 19, package-scripts 19 (postinstall/preremove/postremove against stub `systemctl` and `systemd-sysusers` in a private mount namespace: never enable, start, restart, preset or --now; 15 systemctl calls observed in the no-enable check).

Mutation checks (defect applied to the tested file, test run, file restored; checksums verified identical after): M1 install.sh checksum not enforced KILLED (`a corrupted archive fails the SHA-256 check`); M2 symlink-escape check removed KILLED (hostile archive installed); M3 uninstall `--purge` phrase check removed KILLED (`wrong phrase`); M4 postinstall adds `systemctl enable` KILLED (lab: `some script called enable...`; and `UnitFilesTest > shipped disabled, the packaging tree enables and starts nothing()` FAILED under Gradle); M5 postinstall restarts on a fresh install KILLED; M6 `jdk.net` dropped from `jlink-modules.txt` KILLED (`DRIFT: jdk.net is not in the shipped module set`); M7 `libz.so.1` removed from the allowed native set KILLED (`UNDECLARED system library: libz.so.1`); M8 preremove acts on upgrade KILLED. Runtime-module mutations (real jlink images): without `jdk.net` the probe FAILs (`NoClassDefFoundError: jdk/net/ExtendedSocketOptions`, the ERR-DL2-9 hazard, real); without `jdk.crypto.ec` it FAILs (`EC KeyPairGenerator not available`, `Get Key failed: EC KeyFactory not available`). A defect in the probe itself was found and fixed while building it (it asked a listening channel for SO_PEERCRED; only a connected channel supports it).

`./gradlew -p desktop desktopTest :packaging:windows:winplatform:test --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process` -> `BUILD SUCCESSFUL in 27s`; node-core tests=68 failures=0 errors=0 skipped=0; node tests=117 failures=0 errors=0 skipped=0 (includes `UnitFilesTest`, which scans every non-test `.sh` under `packaging/linux`, mine included); winplatform tests=141 failures=0 errors=0 skipped=27 (the Windows-only ITs). Verified before editing the workflow: `./gradlew -p desktop desktopTest --dry-run` lists only `:node:test` and `:node-core:test` (0 winplatform tasks), so the claim that `desktopTest` does not aggregate `:packaging:windows:winplatform` is TRUE; `:packaging:windows:winplatform:test` exists and runs.
```
$ python3 desktop/tools/isolation.py --selftest     -> selftest OK: the pinned-base check fails on every tampering case and passes on the honest ones
$ desktop/tools/isolation.sh
isolation check 1: 0 (expected 0)   check 2: 0 (expected 0), positive control 19 (> 0)   check 3: 0, positive control 8 (expected 8)
isolation check 4: 136 protected files compared byte-for-byte against base 770a44da21e7 ... OK (shipped tree byte-identical to the pinned base)
law: 145 Kotlin/Java sources scanned ... law: OK        ISOLATION: all checks passed
$ ANDROID_HOME= ./gradlew jvmTest --rerun-tasks --no-daemon ...   BUILD SUCCESSFUL in 1m 12s
root tests: 139   failures+errors: 0   (baseline: 139)
```
Static checks: `shellcheck 0.10.0 -x -S warning` over every script this track wrote: no findings (three benign findings in the lab script carry a `disable=` line). `actionlint 1.7.7 .github/workflows/desktop-linux.yml` -> no findings, exit 0 (shellcheck was not on PATH there, so the `run:` blocks were not shell-linted by actionlint; the scripts were linted directly). Job list parsed with PyYAML: `desktop-jvm, desktop-jvm-arm, desktop-isolation-with-sdk, root-unchanged, systemd-vm, package-linux, install-matrix`.

NOT RUN / NOT VERIFIED (each label is literal):
- `install-matrix` and `distro-matrix.sh`: `CI-ONLY / NOT RUN` (no Docker daemon here). Syntax and shellcheck only. Never executed against any distro image: the `apt-get`, `dnf` and `pacman` prerequisite steps, the `.rpm` install and every claim about ubuntu:22.04/24.04/26.04, fedora and archlinux are unverified. `ubuntu:26.04` was only found to exist on Docker Hub.
- `package-linux` job: `CI-ONLY / NOT RUN` on a hosted runner. In particular `systemctl is-enabled asom` -> `disabled` after `apt install` (gate 5) is NOT verified: no systemd here.
- Temurin 21: NOT used (Ubuntu OpenJDK 21.0.10 here); `Depends` was measured on that runtime and is re-checked by `check-native-deps.sh` in CI.
- aarch64 image: not built (no aarch64 runner here). `rpm -qlp`, `rpm -i`: not run. Signing, attestation, reproducibility: none.
- Real systemd, logind, polkitd, a Steam Deck, a Dell: `NEEDS-DEVICE-VALIDATION` (`desktop/docs/DEVICE_CHECKLIST_LINUX.md`: DV-D1 to DV-D11, DV-L1 to DV-L5, DV-B1 to B4, and DV-P1 to P6 added by this track).
Result: DL3 gates 1, 2, 3, 6 PASSED (LAB); gate 4 (`distro-matrix.sh`) and gate 5 (`systemctl is-enabled`) written and NOT RUN (CI-ONLY).
## Apple lane I0b gate (Swift: AsomBenchCore, AsomManifest, r3 `lines` and `check`, lane diff) — 2026-09-30 — LAB (not device evidence)

Base: `dc8a45dc4ced1cb9c4b80eaf54c4ad17b7758b4e` (confirmed with `git rev-parse HEAD` after `git reset --hard` to it; `docs/design/mesh/LAB_SPEC.md` present) plus an uncommitted working tree (`apple/`, `.github/workflows/apple-ios.yml`, this entry).
Runner: local container, Ubuntu 24.04 x86_64, Swift 6.1 (swift-6.1-RELEASE), swift-crypto 4.5.2, XCTest, JDK 21 for the JVM lane. **No Xcode, no macOS, no Docker daemon, no GitHub Actions run.**
Scope: half I0b. The vectors are the lab's **r3** set (confVersion 0.2.0, all `oracle: self`). Errata, every reading taken where the spec is silent and the disagreement table: `apple/ERRATA.md` (E-16 to E-28, F-1 to F-6). Boundary and labels: `apple/README.md`.

**Independence: NOT CLAIMED. The vectors stay `oracle: self`; this is cross-lane agreement at most.** What the author read and did not read (LAB_SPEC 4.10 asks for it to be recorded):
not read: `lab/manifest/src`, `lab/bench-core/src`, `lab/json/src`, `lab/*/tools`, the vector generators (`RegenerateVectors.kt`, `gen_manifest_vectors.py`), the runner's family adapters. Read: the spec sections listed in ERRATA E-16, **all of `lab/ERRATA.md`**
(so the JVM lane's stated readings were known), the vectors' inputs **and expected outputs before the code was written**, `bench_ref.py` lines 226-240 and 360-538 (the reference's formatters and `render`). Eight clauses were set from the vectors where the prose was silent (ERRATA E-17); agreement on those is not evidence about the spec.

```
$ export PATH=/opt/swift/usr/bin:$PATH; rm -rf apple/.build; swift build --package-path apple      (clean build, no warnings printed)
Computed https://github.com/apple/swift-crypto.git at 4.5.2 (2.08s)
Build complete! (46.61s)

$ swift test --package-path apple
	 Executed 166 tests, with 0 failures (0 unexpected) in 15.274 (15.274) seconds
   (I0a: 49 unchanged in count, one assertion changed from 2 to 4 deny-listed keys; I0b adds 117: AsomBenchCoreTests 64, AsomManifestTests 48, R3ConformanceTests 5; 0 skipped)
```

Non-vacuity: `R3ConformanceTests.testNonVacuity` fails when any family or any implemented input kind ran zero vectors (19 kinds), and pins the only kinds not run (the six M04 plan/governor/executor kinds); the mutation-of-JSON test asserts it rejected more than 50 and accepted more than 5 documents so that both paths ran;
the consistency test runs 18 single-rule rejects and 12 boundary cases that must pass step 15; the projection-flag and file-rule tests assert each stripped field individually.

```
$ swift run --skip-build --package-path apple asom-conformance check M02,M03          (ASOM_CONFORMANCE_DIR=docs/design/mesh/manifest-vectors; the r0 set of half I0a)
checked 37, disagreements 0

$ ASOM_CONFORMANCE_DIR=lab/conformance swift run --skip-build --package-path apple asom-conformance lines M01,M02,M03,M04,M05,M06 > swift.lines ; wc -l
294 swift.lines            (stderr: not implemented in this lane: M04/ceilings 9, M04/consent 6, M04/fsm 1, M04/pins 1, M04/plan 3, M04/trace 26 = 46)

$ ASOM_CONFORMANCE_DIR=lab/conformance swift run ... asom-conformance check                      -> checked 294, mismatches 42     (spec-literal reading: F-1, 37 verdicts + 5 values)
$ ASOM_CONFORMANCE_DIR=lab/conformance ASOM_DIAGNOSTIC_DRIFT_FLAG=1 swift run ... asom-conformance check   -> checked 294, mismatches 0
```

The lane diff. The JVM lines come from the lab runner as the assignment gives it (`./gradlew -p lab :conformance-runner:run --args="lines M01,M02,M03,M04,M05,M06" --quiet`, `--no-daemon --max-workers=2`), 340 lines.
The diff is per vector (`apple/ci/lane_diff.py`), not a whole-file diff (a whole-file `diff` prints 83 JVM-only lines: 37 disagreements and 46 vectors not implemented here).

```
family  jvm  agree  disagree  swift-not-implemented        (spec-literal Swift reading, the gate input)
M01     105  105    0         0
M02      18    1    17        0
M03      71   66     5        0
M04      94   48    0         46
M05      42   32   10         0
M06      10    5    5         0
total   340  257   37         46

$ python3 apple/ci/lane_diff.py jvm.lines swift.lines --known apple/ci/known-disagreements.txt --not-implemented apple/ci/not-implemented.txt
vectors: jvm=340 swift=294 agree=257 disagree=37 (known 37) jvm-only=46 (not implemented 46)          exit 0

$ python3 apple/ci/lane_diff.py jvm.lines swift-diagnostic.lines --not-implemented apple/ci/not-implemented.txt     (ASOM_DIAGNOSTIC_DRIFT_FLAG=1: F-1 set aside; a diagnostic, never the gate)
vectors: jvm=340 swift=294 agree=294 disagree=0 (known 0) jvm-only=46 (not implemented 46)            exit 0
```
Negative controls of the diff tool (run): a forced `M03-141 ok` is `PROBLEM: M03-141: disagreement not listed in the known list` (exit 1); the known list against the diagnostic lines is `PROBLEM: M06-104: listed as a known disagreement but the lanes agree (stale list)` (exit 1); deleting the `M02-114` line is `PROBLEM: M02-114: printed by the JVM lane only and not listed as not implemented` (exit 1).

**Every disagreement is listed in ERRATA F-1 with both verdicts, verbatim (37 rows).** One cause: the JVM lane's projection adds a row flag `thermal-drift` (design B7); benchmark.md 13.3 lists no such flag and `lab/ERRATA.md` does not record it (F-5). Classified: **spec ambiguity, resolved by the JVM lane without an ERRATA entry; not a Swift bug**. The Swift lane did not copy it.
Other findings from the diff, both Swift bugs and fixed by reading the spec: **M01-149** (invalid UTF-8 after the value must outrank trailing data, F-2) and **M03-167** (the deny-list lacked the lab's per-export keys 3 and 4, F-3). Undetermined, no vector decides it: the minimum number of kept reps for drift (F-4, with a proposed vector).

```
$ actionlint .github/workflows/apple-ios.yml     -> no findings, exit 0            (jobs: root-unchanged, apple-swift-lane x2, ios-package-sim, jvm-lines, lane-diff)
$ python3 -c "yaml.safe_load(...)"               -> parsed
$ python3 lab/tools/isolation.py                 -> isolation check 4: OK (shipped tree byte-identical to the pinned base)
$ git diff --stat -- core server app gradle settings.gradle.kts build.gradle.kts gradle.properties .github/workflows/ci.yml lab   -> empty
```
The workflow's `lane-diff` job is explicit about what it tolerates: `apple/ci/known-disagreements.txt` (37 ids, all F-1) and `apple/ci/not-implemented.txt` (46 ids); it fails on any other difference and on stale entries. It is not `continue-on-error`.

Mutation checks. 63 mutants, each applied to the real source with the WHOLE suite run and the original restored: **61 killed; 2 equivalent (survived, argued)**. Five survived the first pass and were killed by tests added afterwards (marked *):
```
V01 step 12 skipped KILLED        V02 expiry boundary <= KILLED     V03 skew allowance exclusive KILLED   V04 zero TTL accepted KILLED      V05 TTL upper bound exclusive KILLED
V06 nonce always equal KILLED     V07 consistency skipped KILLED    V08 derivation compare skipped KILLED V09 confFloor exclusive KILLED    V10 known-bad list ignored KILLED
V11 file-mode own accepted KILLED V12 evidence limit 3 KILLED       V13 rollback <= KILLED                V14 equivocation inverted KILLED  V15 tee not hardware KILLED
V16 tier check off by one KILLED  V17 key3 off the deny-list KILLED V18 payload size limit off KILLED
D01 unknown members tolerated at minor 0 KILLED   D02 forbidden-name scan off KILLED   D03 bidi embeddings allowed KILLED   D04 text limit 97 KILLED   D05 platformIds allowed in a file KILLED
D06 seq allowed in a file KILLED*   D07 file claims hardware storage KILLED   D08 file measuredAtMs not day-truncated KILLED   D09 rate bound not enforced KILLED   D10 rate of zero allowed KILLED
C01 TTFT boundary <= KILLED*   C02 consistency overflow wraps KILLED   C03 steady-vs-curve check off KILLED   C04 duplicate rows allowed KILLED
P01 file keeps seq KILLED   P02 keeps platformIds KILLED   P03 keeps securityPatch KILLED   P04 file keeps the node key as subject KILLED   P05 q2 truncates KILLED   P06 catalogue filter off KILLED   P07 curve selection floors KILLED
B01 file bench keeps battery KILLED   B02 file rows keep battery KILLED   B03 file run start not truncated KILLED   B04 kv cache product wraps KILLED*   B05 flags unsorted KILLED
S01 MAD 4450 KILLED*   S02 MAD-zero cut 251 KILLED*   S03 exclusions >= n/5 KILLED   S04 upper median KILLED   S05 nearest rank truncates KILLED   S06 drift threshold 101 KILLED*   S07 high needs 3 kept SURVIVED (equivalent)   S08 contention boundary exclusive KILLED
U01 onset 901 KILLED   U02 peak window 150 s KILLED*   U03 settle 45 s KILLED   U04 hard-ceiling medium cap removed SURVIVED (equivalent)
K01 checked mul wraps KILLED   K02 checked add wraps KILLED   T01 non-ASCII passes into text KILLED   T02 speeds round KILLED   T03 comfortable threshold KILLED   T04 process limit ignored KILLED   J01 invalid UTF-8 after the value is trailing data KILLED
```
Equivalent mutants, stated so nobody reads them as gaps: S07 (with n >= 5 the outlier rule leaves at least 4 kept reps, so `kept >= 3` and `kept >= 4` cannot differ there); U04 (the medium cap for a hard ceiling can only bite on a base of `high`, which needs an end reason of PLATEAU or TIME_CAP, and the hard-ceiling reasons THERMAL_HARD and BATTERY_TEMP are neither). The suite of half I0a was not re-mutated.

NOT VERIFIED (written, syntax-checked, never run): every macOS job (CryptoKit path, `swift test` on `macos-latest`), the `swift:6.1-noble` container by digest, `ios-package-sim` (the iPhone 17 device name is unverified), `jvm-lines` and `lane-diff` on GitHub Actions (the same commands were run here as separate steps; the artefact download layout `lanes/UNSIGNED-*/` is untested), the claim that the two Swift builds' lines files are byte-identical.
Not done in this half (ERRATA "Not done"): the 46 M04 plan, governor, consent and executor-trace vectors; the signer (`signPresentation`); M07, M08; the `OWN` pin.
Result: **PARTIAL by design, and the lane diff is NOT empty.** Built and tested: PASSED (LAB). Lane diff against the JVM lane: 257 agree, 37 disagree (one cause, F-1, explicit), 46 not implemented; 294 agree once F-1 is set aside. No independence claimed.

## Hosted CI state for the multi-platform mesh program, head aa060c4 (2026-09-30)

Evidence label: **CI (hosted VM)**. NOT DEVICE EVIDENCE. Read from GitHub's check runs for PR #1 at `aa060c4`; 51 of 52 runs succeeded, the one failure is the NON-GATING "Canary - the next series (26.04-1.x)" (Clickable exits 2; cause not investigated, recorded as open). Green at that head: root `jvmTest` (139 tests, unchanged), lab on JDK 17 and 21 and on Windows, winplatform on windows-2025 (JDK 17, 21) and windows-11-arm, desktop node (x86 and aarch64, JDK 17 and 21), the Linux systemd-VM integration job, Linux packages and the five-image install matrix, macplatform on a hosted macOS VM, the Apple Swift lane (macOS CryptoKit, Linux swift-crypto) and iOS-simulator package tests, lane-diff, Ubuntu Touch host tests (JDK 17, 21), the real-Lomiri QML tests, the arm64 click builds (24.04-1.x and 24.04-2.x), and the AppArmor approximation for policies 2404.1 and 2404.2.

What the first hosted runs found (each is in an ERRATA file): a colon in a fixture path made the repo uncheckable on Windows (ERR-DL2-13); a JVM SIGTERM exits 143 (unit files now declare `SuccessExitStatus=143`); the macOS control-socket limit measured 102 bytes, not 103 (ERR-MC-SOCK-1); an rpm left `/opt/asom` behind (ERR-DL3-RPM-1); Clickable parses the AppArmor file as JSON before substituting (ERR-UT-CLICK-2); three v1 tests had ledger-read or clock races (test-only fixes, isolation pins re-moved in later commits, see the re-pin tables).

Open, not closed by these runs: `tst_StatusPage.qml` crashes natively under the offscreen platform with the real Lomiri widgets and passes only via the xvfb retry in `tests/run_qml_ci.sh` (which retry is not yet read from a log); the AppArmor run printed denials for `/proc/<pid>/net/if_inet6`, hugepages and coredump_filter (flag for the device checklist); the Canary failure above; the Secure Enclave, power-assertion, PDH, netsh and DPAPI results are hosted-VM observations, not device evidence. Every device and real-key item stays `NEEDS-DEVICE-VALIDATION` / `NEEDS-OWNER-VALIDATION`. Not started: `:mesh-proto` (wave 3), the final independent Opus review, revision 4 of the design brief.

## Lab L0.5 gate, wire half (track `proto-wire`: `xyz.mdhv.asom.lab.proto.wire` in `:mesh-proto`, vector family W06, the STATE side of W07) — 2026-10-07 — LAB (not device evidence)
Base: 4cf2ad05a8d68594dec24ebf1d26c5dca78b3c52 (working tree, uncommitted)   JDK: openjdk 21.0.10 (JDK 17 lane NOT run here: only JDK 21 in the container; the build config targets 17)   Runner: local
Worktree base: HEAD was 98ab632, not the base, so `git fetch origin claude/asom-v1-build-brief-vw83oh && git reset --hard 4cf2ad05...` was run; `git rev-parse HEAD` then printed 4cf2ad05a8d68594dec24ebf1d26c5dca78b3c52 and `docs/design/mesh/LAB_SPEC.md` exists.
```
$ ./gradlew -p lab :mesh-proto:test :conformance-runner:test --rerun-tasks      BUILD SUCCESSFUL   (mesh-proto 42 tests, 0 failures; conformance-runner 1533 tests, 0 failures, 7 skipped = the existing proposed-lane skips, none of them W06/W07)
family W06: 284 vectors, 284 pass, 0 fail, 0 proposed-skipped, oracle: self=284
family W07: 98 vectors, 98 pass, 0 fail, 0 proposed-skipped, oracle: self=98          (67 existing in policy/W07-live-state.json + 31 new in wire/W07-state-frames.json)
law W06/byte-accounting: 208   encode-decode-roundtrip: 37   extension-skip: 9   frame-length-bounds: 55   incremental-split: 33827   no-message-member: 2   size-limits: 9
law W06/stream-parity: 244   strict-json: 223   typed-parse-reject: 73   unknown-type: 17   version-rule: 21         (cases counted from observed behaviour; a zero fails the family)
law W07/state-producer-strict: 13   wire-state-build: 4   wire-state-decode-ok: 5   wire-state-decode-reject: 9   wire-presence-ignored: 1      (the 15 existing W07 laws unchanged)
law wire/ (mesh-proto WireLawsTest, over many random cases, each > 0, plus a test that fails on any zero):
  frame-length-bounds: 78   stream-parity: 1056   unknown-type: 424   extension-skip: 513   strict-json: 51   size-limits: 11   no-message-member: 136   state-producer-strict: 403   version-rule: 443   incremental-split: 43028
$ ./gradlew -p lab :conformance-runner:run --args='lines W06' --quiet | tail -n 5        (284 lines)
W06-666 reject PROTOCOL_ERROR
W06-667 ok
W06-668 reject PROTOCOL_ERROR
W06-669 reject VERSION_UNSUPPORTED
W06-670 ok
$ python3 lab/mesh-proto/tools/wire/w06_tool.py --check
selftest: the four worked encodings of LAB_SPEC 7.1 match byte for byte (HELLO 16, GOAWAY 26, STATE_REQ 16, CANCEL 67 bytes in total)
xcheck-wire W06 (W06-frames.json): 284 of 284 agree
xcheck-wire W07 (W07-state-frames.json): 31 of 31 agree
xcheck-wire total: 315 agree, 0 disagree
$ python3 lab/tools/xcheck.py lab/conformance     keys 4 agree, INDEX 29 agree, M01 94, M01der 15, M02 90, M03 185, M04 759, M05 210, M06 82 (0 disagree everywhere), W05: absent
$ python3 lab/tools/isolation.py          isolation check 4: 136 protected files compared byte-for-byte against base 79ff5b81fcc4 ... OK (shipped tree byte-identical to the pinned base)
$ python3 lab/tools/check_law.py          law: 9 module build files checked ... law: 171 Kotlin/Java sources scanned ... law: OK
$ ./gradlew jvmTest --rerun-tasks         root tests: 139   failures: 0   (see the note on jacoco below)
```
What was built: the incremental frame decoder (`FrameDecoder`, byte-exact accounting, checks at fixed prefix lengths so the events do not depend on the chunking), the producer (`FrameEncoder`), every 7.2 frame as a typed builder and strict parser (`MessageCodec`), the PAIR_* type numbers with raw payloads and a `PairPayloadHook`, the version rule and the HELLO/HELLO_ACK decisions as pure functions (`Handshake`), `StoredTextProbe`. The STATE frame reuses `:mesh-policy`'s `StateDoc`, `StateParser` and `ProducerStrict` (no presence field exists to fill). Vectors: W06-frames.json (284: the four worked encodings W06-001..004, a golden encoding of every frame type, the negatives of the assignment, the schema accept and reject pairs for every frame type, the version rule) and W07-state-frames.json (31). Independent oracle: `lab/mesh-proto/tools/wire/w06_model.py` (struct.pack, Python json/ipaddress/base64, no Kotlin code) computed every expectation; `w06_tool.py --write` writes both files, `--check` re-derives them (315 of 315 agree).
Mutation checks (each applied to the real source, the WHOLE `:mesh-proto:test :conformance-runner:test` run, the original restored; the mesh-proto and runner suites together went green again afterwards: 42 + 1533 tests, 0 failures):
```
M01 length lower bound off by one (accepts 4)   KILLED 3      M02 length upper bound +1 (accepts MAX+1)   KILLED 4     M03 length upper bound -1 (rejects MAX)   KILLED 5
M04 stream parity inverted                      KILLED 159    M05 unknown type below 0x80 accepted         KILLED 17    M06 float accepted in a payload           KILLED 9
M07 message member stored from a received ERROR KILLED 7      M08 presence field emitted by the STATE builder KILLED 17  M09 0x80 skipped without EXT_IGNORED      KILLED 14
M10 INFER_BODY limit +1 KILLED 4    M11 JSON limit -1 KILLED 4    M12 version rule picks the lowest KILLED 5    M13 direction not enforced KILLED 8
M14 accounting counts payload only KILLED 149   M15 HELLO nodeId vs TLS identity not compared KILLED 3   M17 five endpoints KILLED 3   M18 unknown ERROR code kept as PROTOCOL_ERROR KILLED 6
M19 DNS name accepted as an address KILLED 3   M20 payload limit never refused early KILLED 7   M21 pairing mode accepts any type KILLED 4   M22 duplicate member accepted KILLED 3
M23 DECLINE retry floor 4999 KILLED 3   M24 STATE builder adds a presence member after the check KILLED 17   M25 decoder loses its header when a chunk ends inside it KILLED 220
M26 INFER_ACCEPT keeps queuePos KILLED 1   M27 nodeId alphabet and unused bits unchecked KILLED 3
M16 nodeId length check removed (>= 42): SURVIVED, EQUIVALENT: a string that decodes to 32 bytes under the unpadded URL alphabet has 43 characters, so the length test is redundant; M27 (decoding removed) is the real mutant and is killed.
```
Findings for the owner (ERRATA ERR-PW-1..15): (1) a length below 5 and above the maximum share the single code FRAME_TOO_LARGE as written (ERR-PW-1); (2) the spec gives no per-type stream table nor a code for a parity violation: the 7.2 "Dir, stream" column was made normative per type and the code is PROTOCOL_ERROR (ERR-PW-3); (3) a pairing-mode connection must also accept ERROR (ERR-PW-4); (4) platforms.md says W06 is `proposed`, LAB_SPEC says computed: normative taken (ERR-PW-10); (5) W07 is one family over two files and the registry needed a wrapper checker, one changed (not added) line in Suite.kt (ERR-PW-9).
Not verified here: JDK 17 lane (only JDK 21 present); Windows and macOS lanes (no OS-specific code or skip exists in this track: no Assumptions, no POSIX paths, `String(bytes)` is Kotlin's UTF-8 form); a hosted CI run. The W08 lines, L-L15, L-L16 and the S-A9/S-A11 tables of L0.5 are another track's (not done here). Session-level rules (reply on an unopened stream, HELLO first, authorize per frame, `st` only with scope `state`, the CONTROL EXT_IGNORED row) are not in the codec by design. `./gradlew jvmTest` as a whole task exits non-zero in this sandbox ONLY because `:core:routing:jacocoTestReport` cannot resolve `org.jacoco:org.jacoco.report:0.8.13` (not cached, offline-restricted network); every test task ran and 139 root tests pass with 0 failures, and `jvmTest -x :core:routing:jacocoTestReport` prints BUILD SUCCESSFUL. This is an environment fact, unrelated to this change (the root tree is byte-identical to the base).
Oracle status: self-oracled (an independent Python model agrees, but it was written in the same session; the tag stays `self`)
Result: PASSED for the wire half (local LAB evidence only)
## Lab L0.5 gate, trust half (track proto-trust: W04 pairing, W05 fingerprints, templates and verifyPeerChain) — 2026-10-07 — LAB (not device evidence)
Commit: base `4cf2ad05a8d68594dec24ebf1d26c5dca78b3c52` plus an UNCOMMITTED working tree (the orchestrator collects it). JDK: `openjdk version "21.0.10" 2026-01-20` (the only JDK on this machine; the JDK 17 lane was NOT run here). Runner: local, Linux, 4 cores shared, `--max-workers=2`. Oracle: **self** (see ERRATA ERR-PT-11).

What was built (all pure JVM, no new dependency, no socket, nothing listens): `lab/mesh-proto/src/main/kotlin/xyz/mdhv/asom/lab/proto/trust` (DER writer and strict reader, `Pin` with constant-time whole-value comparison, the two certificate templates of trust.md 2.4, `PeerChainVerifier.verify` and `verifyServer` returning a typed `ChainVerdict`, the peer registry state machine of 4.7 over a `PeerStore` DAO) and `.../pairing` (strict QR encoder and parser, the four pairing messages with the r3 changes, proof, SAS and transcript of 4.5, the D and S state machines of 4.6 as pure functions). Vectors `lab/conformance/wire/W04-pairing.json` (312) and `W05-fingerprints.json` (166), a runner checker `ProtoTrustFamilies.kt` (two registry lines in `Suite.kt`), `INDEX.json` regenerated, a second implementation in Python (`lab/mesh-proto/tools/trust/trustlib.py`, `gen_vectors.py`, `xcheck_trust.py`, `mutants.py`) and ERRATA rows ERR-PT-1 to ERR-PT-14. Touched outside the track's own directories, all additive (ERR-PT-13): `Suite.kt` (two `checkerFor` lines), `lab/tools/xcheck.py` (a dispatch to the trust checker; without it `xcheck` exits 1 as soon as a W05 file exists), `lab/conformance/INDEX.json` (regenerated). No `*_BASE_SHA`, `core/`, `server/`, build file or workflow was touched.

```
$ ./gradlew -p lab :mesh-proto:test --rerun :conformance-runner:test --max-workers=2 --offline
BUILD SUCCESSFUL in 15s
32 actionable tasks: 5 executed, 27 up-to-date
    family W04: 312 vectors, 312 pass, 0 fail, 0 proposed-skipped, oracle: self=312
    family W05: 166 vectors, 166 pass, 0 fail, 0 proposed-skipped, oracle: self=166
```
`:mesh-proto:test`: 39 tests, 0 failures, 0 skipped (PairCryptoTest 6, PairingFsmExhaustiveTest 5, QrUriAndMessagesTest 4, CertTemplatesTest 4, DerTest 6, PeerChainVerifierTest 8, PeerRegistryPropertyTest 3, TrustVectorsTest 2, LabModuleShellTest 1). `:conformance-runner:test`: ConformanceSuiteTest 1683 tests (6 skipped, none of them W04 or W05: both families print `0 proposed-skipped`), NegativeControlsTest 13, 0 failures. No test uses an assumption, a disabled-on-OS annotation or a per-OS skip, and none touches a temp file or a POSIX path, so the Linux and Windows test counts are the same by construction (hosted Windows NOT run).

Non-vacuity (runner family laws, every required law has at least one case; a zero would print `VACUOUS` and fail the family):
```
W05  cert-profile 9 | chain-valid 14 | chain-negatives 108 | registry-L8 122 | pin-derive 4 | fingerprint 9 | pin-compare 13 | spki-strict 13 | test-only-key 1
     chain-reject-<CODE> for every code of ChainReject except the defensive INTERNAL (23 codes, each >= 1: AKI_MISMATCH 5, BAD_SIGNATURE 8, CERT_MALFORMED 9, CHAIN_LENGTH 5, CLOCK_SKEW 4,
     EXTENSION_INVALID 9, EXTENSION_MISSING 10, ISSUER_MISMATCH 6, KEY_UNSUPPORTED 6, LEAF_EKU 4, LEAF_IS_CA 3, LEAF_KEY_USAGE 1, NODE_NOT_CA 3, PAIRING_WINDOW_CLOSED 1, PATHLEN_VIOLATION 3,
     PIN_MISMATCH 2, PIN_REVOKED 5, PIN_SUSPENDED 3, PIN_UNKNOWN 4, REGISTRY_UNREADABLE 5, SIG_ALG_UNSUPPORTED 7, TEST_ONLY_KEY 1, UNKNOWN_CRITICAL_EXTENSION 4)
W04  qr-grammar 108 | qr-roundtrip 20 | qr-reject-<CODE> for all 18 QrReject codes (each >= 1) | proof-sas-transcript 45 | proof-invalid 49 | pin-swap 21 | nonce-swap 21
     pair-message-ok 15 | pair-message-reject 32 | commit-no-locseed 1 | pairing-fsm-D 24 | pairing-fsm-S 17 | pairing-L1 11
     registry-L1 18 | L2 13 | L3 2 | L4 4 | L5 2 | L6 1 | L7 1 | registry-goaway 12 | registry-per-direction 6 | registry-fail-closed 2
```
Module tests print their own laws and `iterations: n` (read from `lab/mesh-proto/build/test-results/test/*.xml`; ERRATA ERR-PT-13): peer registry property test `iterations: 3000 sequences x 40 operations` (law counts: L1 network event changes nothing 17134, L1 no row without both approvals 7535, L2 authorize iff paired and granted 2,880,000, L3 revoked absorbing 3165, L4 restore only local 2876, L5 unknown status denies 156,120, per-direction independence 17,225, GOAWAY on leaving PAIRED 1896, fail-closed reads 660,624 and writes 1016, re-read per frame 480,000, revoked cannot re-pair 451); `verifyPeerChain`: every single-bit flip (two masks) of every byte of both certificates is refused (`iterations: 1666`), every truncation refused (834), 3009 hostile or mutated chains never throw and never accept, the 5-mode x 6-registry-state x 2-window grid (84 pairs) equals the spec grid written out by hand, 20 fresh-key chains accepted in all 6 entry points; pairing machines: D 8 states x 20 events = 160 pairs and S 12 states x 21 events = 252 pairs, each run and compared with a typed table (95 and 59 non-no-op cells; every other pair must be a no-op), and the test fails if a state or event class has no representative; proof, SAS, transcript: 200 seeded random cases against a second formulation; QR and messages: 500 round trips, 3500 garbage URIs and 2000 garbage payloads never throw; templates: 25 fresh-key certificate pairs parsed and signature-checked by the JDK's own X.509 parser (`jdk-verifies-signature` 25).

The worked vector of trust.md 4.5 reproduces from the spec text, untuned: proof `becQGXmCCTwCLN4BK4hj0qa3AW-AT9DgDikj7RA_baM`, SAS `865 412`, transcript `a7cfd74766ca9d6eab6393ad20c52a1e103e394678101b503f1e48c6f473ffb1`, and the pin-swapped proof `r3iMlWpfvBBdchUUFGHUoCSNIi283ymNyEqeqNjoAC8` (W04-200, W04-201; `PairCryptoTest.theWorkedVectorOfTrustMd45`, which types the strings from the spec). The 170-character URI of 4.2 also parses (W04-001).

```
$ ./gradlew -p lab :conformance-runner:run --args='lines W04,W05' --quiet | tail -n 5
W05-417 reject ISSUER_MISMATCH
W05-418 reject ISSUER_MISMATCH
W05-419 reject ISSUER_MISMATCH
W05-420 reject ISSUER_MISMATCH
W05-421 reject ISSUER_MISMATCH
(478 lines in all: 312 W04 and 166 W05; 190 `ok`, 288 `reject <CODE>`)
$ python3 lab/tools/xcheck.py lab/conformance          (tail)
xcheck W04: 249 agree, 0 disagree
xcheck W05: 195 agree, 0 disagree
xcheck oracle status: self-oracled (same-session cross-check; never clears the oracle tag)
$ python3 lab/mesh-proto/tools/trust/xcheck_trust.py lab/conformance
xcheck W04: 249 agree, 0 disagree (63 fsm/registry vectors are Kotlin-only)
xcheck W05: 166 agree, 0 disagree
xcheck W05 openssl (OpenSSL 3.0.13 30 Jan 2024 (Library: OpenSSL 3.0.13 30 Jan 2024)): templates 9 agree, 0 disagree; chains 20 agree, 0 disagree
$ python3 lab/tools/isolation.py
isolation check 4: OK (shipped tree byte-identical to the pinned base)
$ python3 lab/tools/check_law.py
law: OK
$ ./gradlew jvmTest --rerun-tasks --max-workers=2          (repo root, once, at the end; `--offline` failed on an uncached jacoco artefact, so this run used the network)
BUILD SUCCESSFUL in 15s      21 actionable tasks: 21 executed
root tests: 139   failures: 0   (baseline: 139)
```
The Python second implementation agrees on all 415 vectors it covers (it re-derives pins, tags, templates including the RFC 6979 signature byte for byte, the whole `verifyPeerChain` verdict of every chain vector with its own parser and verifier, QR parse and encode, proof, SAS, transcript, and the message codec). The 63 W04 state-machine and registry vectors are typed by hand from trust.md 4.6 and 4.7 and checked only by the Kotlin code (and by the exhaustive tables). OpenSSL 3.0.13 independently reads the nine golden templates (`x509 -text`: version 3, `CA:TRUE, pathlen:0`, `Certificate Sign`, `CA:FALSE`, `Digital Signature`, serverAuth and clientAuth, AKI, `Not After: Dec 31 23:59:59 9999 GMT`; `verify -check_ss_sig` accepts the node certificates) and agrees on 20 chain vectors (accepts the valid ones, rejects the bad signatures). `openssl asn1parse` on W05-200 and W05-205 shows the intended structure (UTF8String names, UTCTime and GeneralizedTime in their RFC forms, critical BC and KU, `30060101FF020100`, `03020204`, `03020780`). The Kotlin encoder's output is those same bytes (`template` vectors compare its TBS and assembled certificate with the Python-written golden ones). `gen_vectors.py` run twice writes byte-identical files.

Mutation checks (`python3 lab/mesh-proto/tools/trust/mutants.py`: each mutant is an exact-string edit of one source file, the suite is run, a failure is required, the original bytes are restored and compared). **21 mutants, 21 killed, 0 survivors, 0 non-compiling**, each against `:mesh-proto:test`:
```
 1 a CA leaf accepted                              KILLED (W05 chain vectors)    12 node pathLen check removed                       KILLED (W05)
 2 a P-384 key accepted                            KILLED (W05)                  13 leaf EKU not checked                              KILLED (W05)
 3 a SHA-1 signature accepted                      KILLED (W05)                  14 unknown critical extension ignored                KILLED (W05)
 4 AKI not compared with the node SKI              KILLED (W05)                  15 chain length 3 accepted                           KILLED (W05)
 5 leaf validity not checked                       KILLED (W05)                  16 ceremony commits without the LOCAL approval       KILLED (W04)
 6 a pin compared with a prefix                    KILLED (W05)                  17 unknown registry status reads as PAIRED           KILLED (property test)
 7 SAS truncation off by one                       KILLED (W04)                  18 REVOKED pin with a valid proof reaches the challenge KILLED (W04)
 8 proof checked over a swapped nonce              KILLED (D exhaustive table)   19 second valid hello accepted after consumption     KILLED (D exhaustive table)
 9 a revoked peer still authorised                 KILLED (W04)                  20 Forget removes a row that is not REVOKED          KILLED (W04)
10 a suspended peer authorised for any scope       KILLED (property test)        21 a network status claim restores a SUSPENDED row   KILLED (property test)
11 node self-signature not verified                KILLED (W05)
```
Mutants 1, 7, 9 and 17 were also run against `:conformance-runner:test` and the runner fails each (`runner(fails)`), so the gate command itself catches them, not only the module's tests. The ten the assignment names are 1, 2, 3, 4, 5, 6, 7, 8, 9, 10.

Findings during the build (each kept as a test or a vector): extension presence is checked before the CA profile, so a swapped chain reports `EXTENSION_MISSING` (my first hand-typed expectation said `NODE_NOT_CA`; the vector was corrected, not the code); OpenSSL does not check the signature of a trust anchor unless `-check_ss_sig` is given (the xcheck adds it); OpenSSL has no clock skew, so the two within-skew vectors are excluded from its comparison.

BLOCKED(L10, L11): trust.md 15 L10 needs the per-frame ledger writer and L11 the two listeners, both of the later track; nothing here can honestly exercise them and no counter claims them (ERRATA ERR-PT-10). L9 and L12 are superseded (LAB_SPEC 7.3).
NOT VERIFIED: the JDK 17 lane and the Windows lane (this machine has JDK 21 only, no hosted run); `JAVA_TOOL_OPTIONS` was left as the sandbox sets it (the module tests start no child JVM). Not independent: every vector is `oracle: self`; the Python tool shares an author and a session with the Kotlin code (ERR-PT-11). Not done, by design: the TLS profile, the hostile-node suite W08, the per-frame ledger writer and the S-A9 and S-A11 spikes (a later track); the verifier-side cap on a leaf's lifetime (ERR-PT-4); the registry is an in-memory DAO with fault injection, not a persistent store.
Result: **PASSED** for items 1 to 6 of the assignment (templates and strict pins, W05, `verifyPeerChain`, W04 with the worked vector reproduced, the registry laws L1 to L8, the law counters) in the LAB sense above; **BLOCKED(scope)** for L10 and L11. Evidence label: LAB (self-oracled; NOT DEVICE EVIDENCE).


## L0.5 TLS half (track `proto-tls`): JSSE TLS 1.3 mesh transport, record tap, S-A9, S-A11, W08 handshake suite

Evidence label: **LAB**, `oracle: self`, Linux only, JDK 17.0.12 and JDK 21.0.10, loopback 127.0.0.1 only. NOT Windows, NOT Conscrypt, NOT Network.framework, NOT DEVICE EVIDENCE. Worktree base: the worktree was created at 98ab632 and was reset to f028376704e26ef8e9ca3f6cc82593e73ce9dc2e (fetch of claude/asom-v1-build-brief-vw83oh, then reset --hard); `rev-parse HEAD` then printed f028376 and `docs/design/mesh/LAB_SPEC.md` exists. Nothing committed or pushed. Written: `lab/mesh-proto/src/main/kotlin/xyz/mdhv/asom/lab/proto/tls/**`, `.../src/test/kotlin/.../tls/**`, `lab/mesh-proto/tools/tls/tap_xcheck.py`, `lab/mesh-proto/docs/S-A9-S-A11.md`, an append to `lab/ERRATA.md` (ERR-PL-1 to ERR-PL-17) and this entry. Spec defects met are in ERRATA (the big ones: ERR-PL-1 the listener cannot suppress tickets scoped, so it builds a fresh `SSLContext` per accepted connection; ERR-PL-2 the client CertificateVerify is encrypted, so it is proved by what the server observes; ERR-PL-4 no scoped signature-scheme API on JDK 17; ERR-PL-5 JSSE accepts an absent ALPN).

```
$ ./gradlew -p lab :mesh-proto:cleanTest :conformance-runner:cleanTest :mesh-proto:test :conformance-runner:test --max-workers=2 --offline        (JDK 21.0.10, then JAVA_HOME=/opt/jdks/jdk-17)
BUILD SUCCESSFUL   (both JDKs)
mesh-proto          tests 120   skipped 0   failures 0   errors 0   (JDK 21)      same on JDK 17
  of which package tls: HandshakeTimeoutTest 2, KnobMatrixTest 12, MeshTlsTransportTest 8, NoGlobalJsseStateTest 3, RecordTapTest 5, SniGatingSpikeTest 1, TapCrossCheckDumpTest 1, W08HandshakeTest 8 = 40
conformance-runner  tests 2013  skipped 7   failures 0   errors 0   (both JDKs; the 7 skips are not from this track, I added no skip)
$ W08 (from TEST-xyz.mdhv.asom.lab.proto.tls.W08HandshakeTest.xml, system-out; the module's build file shows no test output)
JDK 21.0.10  W08-handshake: accepted bad chains: 0; ClientHellos with pre_shared_key: 0; sessions with client CertificateVerify: 6/6
JDK 17.0.12  W08-handshake: accepted bad chains: 0; ClientHellos with pre_shared_key: 0; sessions with client CertificateVerify: 6/6
$ python3 lab/tools/isolation.py
isolation check 4: OK (shipped tree byte-identical to the pinned base)
$ python3 lab/tools/check_law.py
law: OK
$ python3 lab/tools/xcheck.py lab/conformance          (tail)
xcheck W04: 249 agree, 0 disagree
xcheck W05: 195 agree, 0 disagree
xcheck oracle status: self-oracled (same-session cross-check; never clears the oracle tag)
$ python3 lab/mesh-proto/tools/tls/tap_xcheck.py          (second reader of the record tap, same author, so oracle: self)
tap-xcheck jdk17.json: 13 hellos, 0 disagree
tap-xcheck jdk21.json: 13 hellos, 0 disagree
tap-xcheck: 26 hellos, 0 disagree, kinds: client 18 server 8, psk 4, early_data 2, sni 4
$ ./gradlew jvmTest --rerun-tasks --max-workers=2 --offline -x :core:routing:jacocoTestReport          (repo root, once, at the end)
BUILD SUCCESSFUL      root tests: 139   failures: 0   (baseline: 139)
```
Law counters (non-vacuity; each class fails its `@AfterAll` on a zero, JDK 17 run): tls-1.3-only 2 | client-auth-required 2 | sigalg-only 6 | no-resumption 4 (+3 in transport, +W08) | no-early-data 3 | alpn-required 4 | no-sni 1 | no-hostname-check 2 | chain-only-through-verifyPeerChain 10 (+1 source scan) | no-system-properties 18 (sources scanned) + runtime-properties-unchanged 3 | measured-bytes 2 | handshake-timeout 3 | W08: typed-refusal 32, alert-uniform 22 (JDK 21: 18), client-verifies-server-first 11, client-cert-verify 6, record-tap-detects-psk 1, record-tap-detects-early-data 1 | record tap: psk 4, early_data 4, sni 4, reuse 1, reassembly 1. `sigalg-offer-scoped` is required only where `SSLParameters.setSignatureSchemes` exists (JDK 21: 1 case; absent on 17, not required there).

Mutation checks (`mutate.py` in the session scratchpad, not committed: one exact-string edit at a time, the whole `xyz.mdhv.asom.lab.proto.tls.*` suite on JDK 21 AND JDK 17, a failure required on both, the file restored from the saved text and the tree compared with `diff -r` afterwards). 28 mutants: **25 killed on both JDKs, 3 survivors (all redundant defensive layers, see below), 0 non-compiling**:
```
KILLED  hostname check enabled (endpoint identification HTTPS)                      3 tests fail     KILLED  a REVOKED pin accepted (VerifyPeerChain.kt)            2
KILLED  hostname check enabled (trust manager demands a SAN)                       25                KILLED  a SUSPENDED pin accepted (VerifyPeerChain.kt)          2
KILLED  verifyPeerChain verdict ignored on the client side                           8                KILLED  record tap does not detect pre_shared_key              9
KILLED  client side bypassed and post-handshake verdict requirement removed          8                KILLED  record tap does not detect early_data                  5
KILLED  server does not require client authentication                               21                KILLED  trust manager does chain logic of its own              2
KILLED  resumption allowed: shared SSLContext + peer host on the dialler            25                KILLED  a JSSE system property is set                         8
KILLED  ALPN not required (gate and post-handshake check removed)                    3                KILLED  byte counter off by one on large unwraps               3
KILLED  ALPN not required (post-handshake check removed)                             5                KILLED  verdict ignored on the server side                      6
KILLED  a P-384 (KEY_UNSUPPORTED) chain accepted *                                   1 (21) / 5 (17)  KILLED  refusals are not one alert (expired-path cause)       6 (21) / 2 (17)
KILLED  TLS 1.2 enabled                                                              5                KILLED  dialler does not insist on being asked for a certificate 1
KILLED  handshake timeout removed (deadline 1000x)                                   2                KILLED  TEST-ONLY keys trusted in production mode              2
KILLED  handshake timeout constant 5 s -> 60 s                                       3                KILLED  shared listener SSLContext (fresh per connection removed) 21
KILLED  SNI sent (explicit empty server-name list removed AND a dotted peer host)    2
SURVIVED  session.protocol not checked after the handshake      (redundant: the protocol restriction already holds; TLS 1.2 enabled is killed above)
SURVIVED  SNI via a dotted peer host only                        (redundant: the explicit empty server-name list still suppresses it; killed once both are removed)
SURVIVED  close_notify during the handshake no longer a refusal  (redundant: the JSSE throws on it first; the unit test `aCloseNotifyInsteadOfAServerHello...` is green either way)
* the first run SURVIVED on JDK 21 (0 failing tests: a JDK 21 hostile P-384 peer cannot sign with the one scheme offered, so the handshake fails by itself and masks the mutant). I added `theTrustManagerItselfRefusesEveryHostileChainWhateverTheJsseDoes`, which calls the trust manager entry point directly; re-run: killed on both JDKs (1 test on 21, 5 on 17).
```
Findings during the build (each kept as a test): JSSE accepts a ClientHello with no ALPN and a server that selects none, so the key managers withhold the certificate until ALPN matched (a listener sent 326 bytes on JDK 17 / 266 on JDK 21 to such a dialler, a ServerHello and an alert, against about 3.3 KB); a shared server `SSLContext` resumes a ticket on both JDKs and `setSessionTimeout`, `setSessionCacheSize` and server-side `invalidate` change nothing, a fresh context per accepted connection does; a custom `X509ExtendedTrustManager` is not given an identity check by JSSE even when `endpointIdentificationAlgorithm` is `HTTPS`; JDK 21 prefixes local alert text with `(alert_name)` and sends `certificate_required` where JDK 17 sends `bad_certificate`; the engine has no handshake timeout (5 s is enforced by the transport: measured 5306, 5306, 5303 ms on 17 and 5296, 5299, 5295 ms on 21 for three silent or dripping peers, including a 200 ms drain after the failure); S-A11 works with caveats on both JDKs (the token is replayable for the hour; not adopted).
Not done / not verified: Conscrypt and Network.framework columns of S-A9; a hostile post-handshake `CertificateRequest`; any JDK build other than the two named; the Windows lane (no hosted run here; no per-OS skip or Assumption was used, and the only OS-aware line is a tolerated reset on Windows); the per-frame ledger writer and L-L15 itself (session track; this track hands it exact `TransportCounters` and `RecordTap`); the W08 frame-level cases. `TlsMeshConnection.verifiedPin` is an ERRATA request against `MeshConnection` (ERR-PL-8). Not independent: the tap's Python second reader and every JSSE observation share one author and one session.
Result: **PASSED** in the LAB sense above for the assignment's items 1 to 6 on JDK 17.0.12 and JDK 21.0.10; items depending on other stacks or on the session track are listed as not done. Evidence label: LAB (self-oracled; NOT DEVICE EVIDENCE).
## Lab L0.5 gate, session half (track proto-session: `xyz.mdhv.asom.lab.proto.session` in `:mesh-proto`, per-frame ledger writer, FC-1/2/4/5, L-L13/L-L14/L-L16, W08 frame-level) — 2026-10-07 — LAB (not device evidence)
Evidence label: LAB, oracle: self (same session as the code; no independent implementation has agreed). NOT DEVICE EVIDENCE. Everything runs over in-memory `MeshConnection` pipes; no TLS, no socket.
Base: `f028376704e26ef8e9ca3f6cc82593e73ce9dc2e` (the worktree was created at 98ab632 and was reset to it with `git fetch origin claude/asom-v1-build-brief-vw83oh && git reset --hard f028376...`; `docs/design/mesh/LAB_SPEC.md` present). Nothing committed.
Commands (real; output tails below are from the XML test reports, because `:mesh-proto`'s test task does not echo stdout):
$ ./gradlew -p lab :mesh-proto:test :conformance-runner:test --max-workers=2            (JDK 21.0.10)   BUILD SUCCESSFUL; mesh-proto 145 tests 0 failures; conformance-runner 2013 tests 0 failures
$ JAVA_HOME=/opt/jdks/jdk-17 ./gradlew -p lab :mesh-proto:test :conformance-runner:test (JDK 17.0.12)   BUILD SUCCESSFUL; mesh-proto 145 tests 0 failures; conformance-runner 2013 tests 0 failures
Printed by the suite, IDENTICAL on JDK 21 and JDK 17 (seeded):
W08-frames: hostile cases: 807; refusal kinds exercised: 25/25; row checks: 11350; frames after control failure: 0
W08-frames manifest verdict codes: 32/32 (through a session: 26; by the verifier directly, because the frame layer refuses the document first or the context is FILE mode: 6)
== session laws (LAB; oracle: self; NOT DEVICE EVIDENCE): 600 clean runs, 1788 failure-injection runs, 60 pairing runs
L-L13 iterations: 1628 (failure events checked: 2478; control-row failures: 976; FC-1 requester intent: 172; FC-4 lender intent: 52; FC-5 lender outcome before INFER_END: 84; lender decline outcome: 94; DIAL intent: 80; frames after control failure: 0; content frames after a sticky failure: 0)
L-L14 iterations: 2308 (every connect had a durable DIAL intent before it)
L-L15 MEASURED sessions: 984 (n > 0), mismatches: 0 (clean runs only; ESTIMATED lane in AccountingAndDialTest)
L-L16 iterations: 4576 sessions; frames matched to a row on the node that sent or received them: 57567; rows of failed appends tolerated: 536
L-L16 per-frame-type counts: {ERROR=716, EXT_IGNORED=1326, GOAWAY=1554, HELLO=4136, HELLO_ACK=3736, MANIFEST=686, MANIFEST_REQ=1286, PAIR_CHALLENGE=120, PAIR_COMMIT=120, PAIR_COMMIT_ACK=120, PAIR_DECISION=240, PAIR_HELLO=120, REVOKE_NOTICE=1102, STATE=820, STATE_REQ=1184}
structural laws (iterations): {L-L1=1118, L-L12=2130, L-L2=1066, L-L3=1254, L-L6=35215, L-L7=22582, L-L8=35215, L-L9=5899}
$ python3 lab/tools/isolation.py   -> isolation check 4: OK (shipped tree byte-identical to the pinned base)
$ python3 lab/tools/check_law.py   -> law: OK
$ python3 lab/tools/xcheck.py lab/conformance -> xcheck W04: 249 agree, 0 disagree ... xcheck oracle status: self-oracled
$ ./gradlew jvmTest (root)         -> BUILD SUCCESSFUL; root tests: 139, 0 failures (the :core:routing and :server test tasks were FROM-CACHE; the XML counts are the cached results of the same inputs)
Mutation checks (`python3 lab/mesh-proto/tools/session/mutants.py`): 28 mutants, 28 KILLED, 0 survivors, each restored byte for byte. They include every one required: the row appended AFTER the frame is sent (control frames, lender decline, requester intent, lender outcome before INFER_END), a control-row failure that still sends GOAWAY (and one that does not close the connection), FC-4 not declining PEER_UNAVAILABLE, FC-5 sending INFER_END, FC-1 ignoring a failed intent, authorisation read once per session, a revoked peer served, a duplicate attemptId accepted, HELLO (and HELLO_ACK) nodeId not compared with the pin, a received MANIFEST accepted without verification (and a manifest key that does not hash to the pin), the extension skip without its EXT_IGNORED row, bytes counted as payload only, a STATE builder emitting a presence field; plus st to a peer without scope state, no registry listener, DIAL intent after the connect, no CLOCK_SKEW, a reused stream id accepted, MEASURED overhead taken from the writer, an unknown CANCEL or an INFER_BODY without an offer ignored, and the SESSION close row dropping uncovered bytes.
What was built: session engine for both TLS roles (HELLO/HELLO_ACK, version rule, nodeId = authenticated pin, 2 h CLOCK_SKEW, limits, granted scopes, per-frame `authorize` re-read, registry-change GOAWAY, stream parity and reuse, duplicate attempt, typed ERROR then close, extension skip with EXT_IGNORED), lender decision by `:mesh-policy` `LenderDecisionTable` with an `EnginePort` (NoopEngine in main; a scripted fake in tests), INFER_ACCEPT/DECLINE/BODY/HEAD/CHUNK/END/CANCEL, STATE (producer-strict, from `:mesh-policy`), MANIFEST (signed through a test provider, received ones verified by `:manifest` `Verifier` with the typed verdict in the MANIFEST_RECEIVED row), the per-frame ledger writer (durable before, 9 + payload bytes, SESSION open/close with MEASURED or ESTIMATED overhead), the DIAL writer (intent before connect through an injected `Dialer`), FC-1/2/4/5, a `PairingChannel` for the PAIR_* rows, and the structural laws.
Reading choices are recorded in `lab/ERRATA.md` ERR-PS-1..22 (conservative readings of R3-OVERCLAIM-3, R3-CLOSURE-6 and the silent points). BLOCKED / NOT DONE (honest): L-L15 over a real TLS stack and the S-A9/S-A11/handshake half of W08 (the `proto-tls` track); L-L10 (`MeshConnection` carries no `peerPath`); L-L11 process death over a live session (the sink SIGKILL harness exists in `:ledger-model`); timers other than the 5 s handshake timeout (idle, accept-without-body, max-age, stream and handshake limits); `MeshConnection` was not changed (ERR-PS-8 lists the three figures the TLS track should expose). The Windows lane and hosted CI were NOT run here.
Result: **PASSED** for items 1 to 6 of the assignment in the LAB (oracle: self).
## Apple lane I0c gate (Swift: M04 plan/consent/governor/ceilings/pins, signPresentation + cross-lane fixtures, M08 claim tracker, lane-diff controls) — 2026-10-07 — LAB (not device evidence)

Base: `f028376704e26ef8e9ca3f6cc82593e73ce9dc2e` (worktree already at it, `git rev-parse HEAD` confirmed; no reset performed) plus an uncommitted working tree (`apple/`, `.github/workflows/apple-ios.yml`, this entry). Runner: local container, Swift 6.1 on Linux (swift-crypto), JDK 21 and JDK 17 for the JVM lane. **No macOS, no CryptoKit run, no Xcode, no GitHub Actions run, actionlint not installed (YAML parsed with python only).**
Two sittings: a first builder was stopped by a usage limit (uncommitted work); the second took the tree over, found it building and green (216 tests), re-ran every gate below, reviewed the code against the spec text for M04 5.2, 11.1, 11.3, 11.4 and LAB_SPEC 4.7 and 6.6, wrote the ERRATA section "Half I0c" (E-29 to E-41, LF-1 to LF-5) which the code already cited but which did not exist, fixed README and workflow claims, and re-ran the mutation harness. Not re-derived line by line by the second builder: the bodies of `CrossLane.swift`, `M08Conformance.swift` and the unit-test bodies (they run green, are mutation-checked below, and the vectors are the cross-check).

```
$ export PATH=/opt/swift/usr/bin:$PATH; swift build --package-path apple    -> Build complete!
$ swift test --package-path apple
	 Executed 216 tests, with 0 failures (0 unexpected) in 22.001 (22.001) seconds          (0 skipped; I0b was 166)
$ ASOM_CONFORMANCE_DIR=lab/conformance swift run --skip-build --package-path apple asom-conformance lines M01,M02,M03,M04,M05,M06,M08 > swift.lines ; wc -l      -> 370
   stderr: not implemented in this lane: M04/consent-text-unspecified (1), M04/plan-unspecified (2), M04/trace (26)
$ ./gradlew -p lab :conformance-runner:run --args='lines M01,M02,M03,M04,M05,M06,M08' --quiet > jvm.lines ; wc -l      -> 399   (JDK 21; the same command under JAVA_HOME=/opt/jdks/jdk-17 gives a byte-identical file, cmp)
$ python3 apple/ci/lane_diff.py jvm.lines swift.lines --known apple/ci/known-disagreements.txt --not-implemented apple/ci/not-implemented.txt
vectors: jvm=399 swift=370 agree=333 disagree=37 (known 37) jvm-only=29 (not implemented 29)          exit 0
$ ASOM_DIAGNOSTIC_DRIFT_FLAG=1 ... lines ... > swift-diagnostic.lines ; python3 apple/ci/lane_diff.py jvm.lines swift-diagnostic.lines --not-implemented apple/ci/not-implemented.txt
vectors: jvm=399 swift=370 agree=370 disagree=0 (known 0) jvm-only=29 (not implemented 29)           exit 0
$ asom-conformance check M01,M02,M03,M04,M05,M06,M08  -> checked 370, mismatches 42 (spec-literal; LF-1: 37 verdicts + 5 values);  with ASOM_DIAGNOSTIC_DRIFT_FLAG=1 -> checked 370, mismatches 0
$ python3 apple/ci/test_lane_diff.py -> Ran 13 tests OK        python3 apple/ci/test_crosslane.py -> Ran 8 tests OK
$ python3 apple/ci/crosslane.py all --jvm-runner .../conformance-runner --swift-cli apple/.build/debug/asom-conformance
crosslane: 37 vectors (23 ok, 14 reject), reject codes EQUIVOCATION, EXPIRED, FINGERPRINT_MISMATCH, KEY_NOT_PINNED, NONCE_MISMATCH, NON_CANONICAL, NOT_YET_VALID, ROLLBACK, SIGNATURE_INVALID, SUBJECT_KEY_MISMATCH, TEST_ONLY_KEY, TIER_INSUFFICIENT
crosslane: JVM, Swift and the fixture agree on 37 of 37
$ python3 lab/tools/isolation.py -> isolation check 4: OK (shipped tree byte-identical to the pinned base)        $ python3 lab/tools/check_law.py -> law: OK
$ ./gradlew jvmTest --offline -> BUILD SUCCESSFUL (task results FROM-CACHE; the junit XML of the root modules sums to 139 tests, 0 failures)
```
The lane diff is **NOT empty**: 37 listed disagreements (one cause) and 29 vectors not implemented. Both lists are explicit files, the diff fails on anything else.

Item by item. **1 (F-1).** The premise of the assignment (a verifier step-order difference) is not what the evidence shows: the verifier order is LAB_SPEC 4.6 steps 1 to 19 in both lanes; the 37 vectors differ because the JVM projection adds a row flag `thermal-drift` that benchmark.md 13.3 does not list, so the Swift step 15a (a step before the one that decides the vector) says DERIVATION_MISMATCH; five of them are shadowed (the JVM verdict belongs to a later step). Decision from the spec text: the Swift lane is the literal reading, the JVM flag is unsupported by 13.3, so the Swift lane was NOT changed and a lab finding was recorded (apple/ERRATA.md LF-1) instead of editing lab/. known-disagreements.txt is now 37 lines with the same reason each, and the lane-diff tool fails on an unlisted disagreement, on a listed vector that agrees, on an entry with no reason and on a duplicate (`test_lane_diff.py`, 13 tests run against the real script).
**2 (M04).** Built: `plan` (standard, 1 of 3), `consent` (5 of 6), `fsm` (1), `ceilings` (9), `pins` (1); all agree with the JVM lane. BLOCKED, listed with reasons in `apple/ci/not-implemented.txt`: the 26 executor traces (E-29: the fake engine's timing model, presets and log grammar are not in the spec), the quick and ci plans (E-30: JCS form not in the spec), M04-425 (E-31: the run-today sheet wording is not in the spec). Wording of every consent sheet other than the spec's standard one is this lane's own (E-31).
**3 (signer).** `ManifestSigner.signPresentation` (own and file, self-check, `nextSeq`), keys read from `lab/conformance/keys/TEST-ONLY-keys.json` at test time (a search for the four `d_hex` values in `apple/` and `.github/` finds none). 37 Swift-signed documents in `apple/crosslane` (generated by this code, committed, each regeneration changes the signatures), and the JVM lane, the Swift lane and the fixture file give the same verdict for all 37 (above). The CryptoKit signing path is UNVERIFIED.
**4 (M08).** A second implementation (`AsomRouterCore`) written from LAB_SPEC 6.6 and the M08 vector file. All 59 M08 vectors agree (`diff` of the M08 lines of the two lanes is empty; none is in either list). Files read: LAB_SPEC 6.6, REVIEW_ROUND3 R3-CLOSURE-5 and R3-OVERCLAIM-1, `lab/conformance/router/M08-claim-tracker.json`. Deliberately not read: `lab/mesh-router/**`, the runner's M08 adapter, `lab/ERRATA.md` outside three lines (ERR-BENCH-3, -4, -10, printed by a search for "drift"). The first sitting's source header states the same; the second builder could not verify what the first opened beyond that. No oracle tag changed. The padding residual (R3-OVERCLAIM-1) is real and unfixed (E-39).

Non-vacuity: `R3ConformanceTests.testNonVacuity` fails when any family or any implemented kind ran zero vectors (28 kinds, now including M04 plan/consent/fsm/ceilings/pins and the four M08 kinds with exact counts 28/26/3/2), and pins the only skipped kinds (M04 trace 26, plan 2, consent 1). Token laws, discard-order combinations, boundaries (299,999 ms vs 300,000 ms; 127/128 bytes; 3/4 observations) and a sweep of all 100 governor pairs are unit tests.

Mutation checks of the new code: **38 mutants, 38 killed, 0 survived.** Each applied to the real source with the WHOLE suite run (216 tests) and the file touched back to the original. The harness is `swift test` exit status after "Build complete" (a mutant that crashed the test process counts as killed and is named). **First pass defect, found and fixed:** the first harness restored files with an old mtime, so SwiftPM did not rebuild after a restore and later mutants ran on top of earlier ones, and two mutants were reported as survivors because the test process crashed (no summary line); the whole set was re-run with a touch after every restore and a baseline run first (216 green) and the table below is that second run. Failure counts printed by the harness are the count in the last summary line only and are not a measure of strength.
```
S1 signer self-check removed          S2 seq ignores stored             S3 file issuedAt not day-truncated  S4 own challenge length unchecked  S5 own ttl 700000     S6 file export falls back to node key
C1 token lifetime inclusive           C2 spent token not refused        C3 token never marked spent         C4 hash compare always true        C5 plan binding dropped  C6 sheet wording changed
G1 DONE has an out edge               G2 ABORTING skips FINALIZING      G3 COOLING cannot abort
K1 android soft headroom 951          K2 android hard code 4            K3 gpu busy 10001                   K4 macos charger rule for every form  K5 ios low power mode ignored  K6 battery level 199
R1 plan interRepMs 501                R2 plan sustain window 15001
T1 CORR 801  T2 DISC 601  T3 WIN 21  T4 best index n/2  T5 budget trips at half  T6 SHORT inclusive  T7 ratio cap 5001  T8 no strike on OVERLONG  T9 median upper  T10 claim-body gate inclusive
T11 new seq forgets DISCREPANT  T12 net time floors (killed by a division-by-zero crash in the test process)  T13 held setting without claim not a difference  T14 terminal not checked  T15 short/overlong discard order swapped
all KILLED
```
Not mutation-checked: `CrossLane.swift` and the M08 adapter (their output is compared against the JVM lane by the lane diff and `crosslane.py`, whose own negative controls are `test_lane_diff.py` and `test_crosslane.py`), the Python tools beyond those controls, and every CryptoKit branch.

NOT VERIFIED (written, not run): everything on macOS (the CryptoKit verifier and signer, `swift test` on macos-latest, the iOS simulator job), the new workflow steps on GitHub Actions (the same commands were run here as separate steps; the artefact layout `lanes/UNSIGNED-*/` is untested), actionlint on the I0c workflow (python YAML parse only).
Result: **PARTIAL.** Built and tested here: PASSED (LAB). Lane diff vs the JVM lane: 333 agree, 37 disagree (LF-1, explicit), 29 not implemented (BLOCKED, explicit); 370 agree once LF-1 is set aside. No independence claimed.

## Lab L0.5 closing step (track `proto-integration`: the TLS half joined to the session half over real loopback TLS; L-L15 over a real stack, L-L16, L-L13, L-L14, the whole W08, the timers and limits of trust.md 3.3 and 5.1) — 2026-10-07 — LAB (not device evidence)
Evidence label: LAB, oracle: self (same session as the code; no independent implementation has agreed). NOT DEVICE EVIDENCE, NOT Windows, NOT Conscrypt, NOT Network.framework. Real TLS 1.3 (JSSE) over 127.0.0.1 with an ephemeral port, the real peer registry, the real per-frame ledger writer on the real JSONL sink in a temp directory.
Base: `f6a8f1efeea47c0c3ef8653a34762e9d6b706fe0`. The worktree was created at 98ab632 and was reset to the base (fetch of the branch, then a hard reset to that sha); `rev-parse HEAD` printed the base and `docs/design/mesh/LAB_SPEC.md` exists. Nothing committed.
Commands (real). `:mesh-proto`'s test task does not echo stdout, so the summary lines are read from the test report by `python3 lab/mesh-proto/tools/integration/summary.py`, which also fails on a zero or a missing line:
$ ./gradlew -p lab :mesh-proto:test :conformance-runner:test --rerun-tasks --max-workers=2 --offline          (JDK 21.0.10)  BUILD SUCCESSFUL in 1m 58s; mesh-proto 213 tests 0 failures; conformance-runner 2013 tests 0 failures
$ JAVA_HOME=/opt/jdks/jdk-17 ./gradlew -p lab :mesh-proto:test :conformance-runner:test --rerun-tasks ...   (JDK 17.0.12)  BUILD SUCCESSFUL in 1m 44s; mesh-proto 213 tests 0 failures; conformance-runner 2013 tests 0 failures
Summary lines printed by `ProtoIntegrationGateTest`, JDK 21.0.10:
```
== proto integration gate (LAB; oracle: self; NOT DEVICE EVIDENCE; JDK 21.0.10, os Linux) ==
calibration: cipher TLS_AES_256_GCM_SHA384; one TLS record costs 38 bytes over the plaintext (spec figure [F51]: 22), the close alert is 40 bytes, the largest single-record write is 16367 bytes
runs: 60 clean seeded sessions, 122 failure-injection runs, 17 runs cut by a peer reset, 16 pairing sessions, 1 lifecycle walk (28 rows)
L-L15 MEASURED sessions: 171 (n > 0), mismatches: 0
L-L15 detail: rows 531901 application bytes + 840527 overhead bytes = 1372428 = 1373757 bytes counted by the record tap; handshake figure 2530..4453 bytes; sessions that lost 5 write-ahead claims totalling 125 bytes; cross-checked socket to socket on every clean run: 176/176
L-L15 ESTIMATED sessions (a node that crashed and wrote no close row): 12; largest |estimate - tap| 3637 bytes; estimate 8390 real 4753 (B); estimate 8412 real 4790 (A); estimate 8434 real 4829 (B); estimate 8434 real 4865 (A); estimate 8478 real 4902 (B); estimate 8368 real 4749 (A); estimate 8412 real 4791 (B); estimate 8412 real 4829 (A); estimate 8456 real 4869 (B); estimate 8456 real 4902 (A); estimate 8390 real 4754 (B); estimate 8390 real 4792 (A)
L-L16 iterations: 398 sessions; frames matched to a row on the node that sent or received them: 4274; rows of failed appends tolerated: 34
L-L16 per-frame-type counts: {ERROR=28, EXT_IGNORED=60, GOAWAY=108, HELLO=342, HELLO_ACK=312, MANIFEST=70, MANIFEST_REQ=98, PAIR_CHALLENGE=32, PAIR_COMMIT=32, PAIR_COMMIT_ACK=32, PAIR_DECISION=64, PAIR_HELLO=32, REVOKE_NOTICE=146, STATE=54, STATE_REQ=70}
L-L13 iterations: 110 (failure events checked: 165; control-row failures: 64; FC-1 requester intent: 10; FC-4 lender intent: 4; FC-5 lender outcome before INFER_END: 6; lender decline outcome: 6; DIAL intent: 6; frames after control failure: 0; content frames after a sticky failure: 0; checked against the record tap: 76)
L-L14 iterations: 176 (every connect had a durable DIAL intent before it)
W08-frames-tls: hostile cases: 153; refusal kinds exercised: 25/25; row checks: 947; frames after control failure: 0; split runs: 14; closed by the honest end and seen as end of stream by the hostile end: 60; typed ERROR codes seen on the wire: CLOCK_SKEW=2, FRAME_TOO_LARGE=9, MANIFEST_UNAVAILABLE=1, PROTOCOL_ERROR=46, SCOPE_DENIED=2, VERSION_UNSUPPORTED=1
W08-frames-tls manifest verdict codes: 32/32 (through a TLS session: 26; by the verifier directly, because the frame layer refuses the document first or the context is FILE mode: 6)
W08-frames-tls L-L15 over its sessions: MEASURED 141, mismatches 0
W08-handshake-session: cases 41; dial outcomes {not-tls=1, pin-mismatch=10, refused=4}; INBOUND_REFUSED rows 17; sessions established under the session engine in this suite 15
W08-over-tls: accepted bad chains: 0; ClientHellos with pre_shared_key: 0; sessions with client CertificateVerify: 307/307
evidence: LAB, oracle: self, NOT DEVICE EVIDENCE, jdk feature 21
gate class: 10 tests, 0 failures, 0 errors
summary OK (LAB, oracle: self, NOT DEVICE EVIDENCE)
```
Summary lines, JDK 17.0.12:
```
== proto integration gate (LAB; oracle: self; NOT DEVICE EVIDENCE; JDK 17.0.12, os Linux) ==
calibration: cipher TLS_AES_256_GCM_SHA384; one TLS record costs 38 bytes over the plaintext (spec figure [F51]: 22), the close alert is 40 bytes, the largest single-record write is 16367 bytes
runs: 60 clean seeded sessions, 122 failure-injection runs, 17 runs cut by a peer reset, 16 pairing sessions, 1 lifecycle walk (28 rows)
L-L15 MEASURED sessions: 171 (n > 0), mismatches: 0
L-L15 detail: rows 530676 application bytes + 869496 overhead bytes = 1400172 = 1401501 bytes counted by the record tap; handshake figure 2650..4633 bytes; sessions that lost 3 write-ahead claims totalling 75 bytes; cross-checked socket to socket on every clean run: 176/176
L-L15 ESTIMATED sessions (a node that crashed and wrote no close row): 12; largest |estimate - tap| 3461 bytes; estimate 8390 real 4929 (B); estimate 8390 real 4969 (A); estimate 8434 real 5005 (B); estimate 8434 real 5043 (A); estimate 8478 real 5084 (B); estimate 8368 real 4930 (A); estimate 8412 real 4972 (B); estimate 8412 real 5010 (A); estimate 8456 real 5042 (B); estimate 8456 real 5084 (A); estimate 8390 real 4931 (B); estimate 8390 real 4971 (A)
L-L16 iterations: 398 sessions; frames matched to a row on the node that sent or received them: 4249; rows of failed appends tolerated: 34
L-L16 per-frame-type counts: {ERROR=28, EXT_IGNORED=60, GOAWAY=109, HELLO=342, HELLO_ACK=312, MANIFEST=70, MANIFEST_REQ=98, PAIR_CHALLENGE=32, PAIR_COMMIT=32, PAIR_COMMIT_ACK=32, PAIR_DECISION=64, PAIR_HELLO=32, REVOKE_NOTICE=146, STATE=54, STATE_REQ=70}
L-L13 iterations: 110 (failure events checked: 165; control-row failures: 64; FC-1 requester intent: 10; FC-4 lender intent: 4; FC-5 lender outcome before INFER_END: 6; lender decline outcome: 6; DIAL intent: 6; frames after control failure: 0; content frames after a sticky failure: 0; checked against the record tap: 76)
L-L14 iterations: 176 (every connect had a durable DIAL intent before it)
W08-frames-tls: hostile cases: 153; refusal kinds exercised: 25/25; row checks: 947; frames after control failure: 0; split runs: 14; closed by the honest end and seen as end of stream by the hostile end: 60; typed ERROR codes seen on the wire: CLOCK_SKEW=2, FRAME_TOO_LARGE=9, MANIFEST_UNAVAILABLE=1, PROTOCOL_ERROR=46, SCOPE_DENIED=2, VERSION_UNSUPPORTED=1
W08-frames-tls manifest verdict codes: 32/32 (through a TLS session: 26; by the verifier directly, because the frame layer refuses the document first or the context is FILE mode: 6)
W08-frames-tls L-L15 over its sessions: MEASURED 141, mismatches 0
W08-handshake-session: cases 41; dial outcomes {not-tls=1, pin-mismatch=11, refused=3}; INBOUND_REFUSED rows 17; sessions established under the session engine in this suite 15
W08-over-tls: accepted bad chains: 0; ClientHellos with pre_shared_key: 0; sessions with client CertificateVerify: 307/307
evidence: LAB, oracle: self, NOT DEVICE EVIDENCE, jdk feature 17
gate class: 10 tests, 0 failures, 0 errors
summary OK (LAB, oracle: self, NOT DEVICE EVIDENCE)
```
$ python3 lab/tools/isolation.py   -> isolation check 4: OK (136 protected files byte-identical to the pinned base)
$ python3 lab/tools/check_law.py   -> law: OK (259 sources scanned, listeners only in tests)
$ python3 lab/tools/xcheck.py lab/conformance -> xcheck W04: 249 agree, 0 disagree; W05: 195 agree, 0 disagree; oracle status: self-oracled
$ ./gradlew jvmTest --rerun-tasks (repo root, once, at the end) -> BUILD SUCCESSFUL; root tests: 139, failures: 0 (baseline: 139); the jacoco report resolved, no exclusion was needed
Mutation checks (`python3 lab/mesh-proto/tools/integration/mutants.py`, one exact-string edit set at a time, the whole `xyz.mdhv.asom.lab.proto.integration.*` suite, the file restored byte for byte): 32 mutants on JDK 21: 31 KILLED at the first run; mutant 16 (a connection-level frame restarts the idle timer) SURVIVED, because the test sent only an extension frame, which never reaches that code; a dispatched stream-0 frame (`REVOKE_NOTICE`) was added to `TimersTest` and it is KILLED. Re-run on JDK 17: mutants 1 to 12, 16, 20, 21, 22: 16 of 16 KILLED. The ones the assignment names, all KILLED on BOTH JDKs: bytes counted by the session layer but not by the tap source swapped (1, 2, 3); the row written after the frame (4); a control-row failure that still sends (5); a revoked pin served over TLS (6 at the lender, 7 in `verifyPeerChain`); the overhead snapshot taken too early (8 at the session, 9 in the DIAL row) and too late (10); the idle timer never firing (11); max-age (12). The reverts of this track's three fixes (20, 21, 22) are killed on both. Kills only on JDK 21 (not repeated on 17): 13 to 15, 17 to 19, 23 to 32 (body expiry, body wait, stream cap, both limiter limits, DIAL code mapping, counters not measured, trust manager lets a refused chain through, authorisation once per session, registry listener, duplicate attempt, EXT_IGNORED row, FC-5, FC-1, FC-4, DIAL intent after connect).
What was built: `integration/` (main): `SessionDriver` (reader and ticker threads on an injected clock), `HandshakeLimiter` (8 in flight, 10 per minute per source, pure), `DialOutcomes`. Tests: a TLS harness (loopback listener and dialler, tapped connections that log plaintext before it is handed to TLS, JSONL ledgers, hostile raw clients and servers), `TlsOracle` (L-L15 with three independent quantities, L-L16, write-ahead order), `L13Tls` (sink probes against the record tap), `TlsPairing`, `HostileFramesTls` (153 cases), `HostileHandshakeSession` (41 cases), `TimersTest`, `HandshakeLimiterTest`, `DialOutcomesTest`, `RisksTest`, `PartialRecord`, and the gate class. Changes inside `tls/` and `session/` (ERRATA ERR-PI-1, -3, -4, -5, -7): `TlsEngineIo` counts what the socket took and, once closed, every byte it delivered; `Session`/`LenderSide`/`RequesterSide` implement the idle, max-age, body-wait and 4-stream rules; `PairingChannel` takes the dialler's DIAL id and handshake figure and has a read loop; new `SessionLimits.kt`. Findings: JSSE puts 38 bytes (not 22) on each record on both JDKs (ERR-PI-2).
Reading choices: `lab/ERRATA.md` ERR-PI-1..16.
NOT DONE / UNVERIFIED (honest): any Windows run (the only OS-aware lines are listed in ERR-PI-16; the tolerance for a Windows hard reset is coded but never executed); the real pairing ceremony over TLS (frames and rows only, ERR-PI-14); a requester-side deadline for an unanswered offer; `local-network-denied` and `firewall-blocked` DIAL outcomes; the hostile frame cases are ported not shared and reduced in two places (ERR-PI-12); the ESTIMATED form is checked against the tap with a measured allowance, it is not exact, and its spec constants (22 bytes, 16,384) disagree with JSSE (ERR-PI-2); the limiter is not wired to any listener loop (the lab has none by rule R5); mutants 13 to 15, 17 to 19 and 23 to 32 were not repeated on JDK 17.
Result: **PASSED** in the LAB sense above for items 1 to 6 of the assignment on JDK 17.0.12 and JDK 21.0.10 (oracle: self; NOT DEVICE EVIDENCE).

## LAB: integration test timing flakes on hosted CI (oracle: self; NOT DEVICE EVIDENCE)

Scope: `lab/mesh-proto/src/test/kotlin/.../integration/` only; no main code changed. Two hosted-runner failures (HandshakeLimiterTest line 95, `w08FramesAttemptsAndRegistry`)
were not reproduced locally (12/12 passes of HandshakeLimiterTest under 8 busy loops on 4 cores); one other race was reproduced under load and fixed.
Races fixed (each by waiting on the condition with the 30 s `Wait.until`, assertions unchanged):
- `InboundRefusedCounter.refused()` is an unsynchronised `count++`; `acceptOn` called it from up to 8 acceptor threads at once, so a lost increment gives `refused:8`. The harness now serialises the calls (`countRefusal`). Not reproduced locally, so a hypothesis; the assertion text now shows the refusal list.
- `refused()`/counter reads right after the reply frame (the session thread bumps the counter) now `Wait.refusals(...)`; late-cancel count, engine `cancelled`, INTERRUPTED/close rows, `honest.closed` after a refusal, outcome rows in TimersTest likewise.
- REPRODUCED under load: `l15OverRunsCutMidStreamByAPeerResetAndMidRecord` "session B: closed with no SESSION close row" (`closed` flag is seen before the close row is logged). `TlsRuns.settle` now waits (5 s, then the oracle reports) for each closed session's close row or failed append.
Verification (8 busy loops, 4 cores): `./gradlew -p lab :mesh-proto:test --offline --max-workers=2 --rerun-tasks`
  JDK 17.0.12: 3 consecutive full passes after the last edit (plus 1 earlier pass); JDK 21 (default): 1 pass, all under load.
  `python3 lab/tools/isolation.py` -> isolation check 4: OK (shipped tree byte-identical to the pinned base)

## Review fixes CV-1 to CV-10 (track `fix-crypto`: `lab/manifest`, `lab/json`, `lab/conformance/manifest`, `apple/`) — 2026-10-07 — LAB (not device evidence)

Evidence label: LAB and CI-APPROX on this machine (JDK 21 default, JDK 17 at /opt/jdks/jdk-17, Swift 6.1 on Linux with swift-crypto). `oracle: self` on every vector. NOT DEVICE EVIDENCE. Nothing was run on macOS, Windows or hosted CI. Base commit `f6a8f1efeea47c0c3ef8653a34762e9d6b706fe0` (the worktree started on another commit and was reset to it). No commit was made.

Findings: CV-1, CV-3, CV-5, CV-6, CV-7, CV-8, CV-9, CV-10 FIXED; CV-2 and CV-4 FIXED (Swift); CV-3 is a ruling (see `lab/ERRATA.md` ERR-FX-CV3): the JVM and Swift lanes now refuse `requiredTier` A1 and A2 because the A1 label is a self-report. Readings: `lab/ERRATA.md` ERR-FX-CV1, -CV3 to -CV10 and ERR-FX-FILES; `apple/ERRATA.md` "Review fixes" section.

Failing-before evidence (real output, before the fix):
```
$ ./gradlew -p lab :manifest:test --tests '*OneRecordTiesTest' --tests '*SelfReportedTierTest' --tests '*FingerprintNormalisationTest' --tests '*LaneAgreementReadingsTest' --tests '*SeqRaceTest' --tests '*NonReducedCoordinateTest'
15 tests completed, 10 failed
FingerprintNormalisationTest > nonAsciiLettersThatUnicodeUppercasesToBase32LettersAreNotFolded() FAILED   expected: <ſ> but was: <S>
LaneAgreementReadingsTest > aContainerThatIsNotAnObjectIsContainerInvalid() FAILED   []: 3 ==> expected: <CONTAINER_INVALID> but was: <CONTAINER_VERSION_UNKNOWN>
LaneAgreementReadingsTest > aFileSignerSpkiThatIsNotAStringIsContainerInvalid() FAILED   signer.spki = 5: 7 no signer.spki ==> expected: <CONTAINER_INVALID> but was: <KEY_NOT_PINNED>
LaneAgreementReadingsTest > aPayloadTypeOrSchemaMajorMustBeACanonicalDecimal() FAILED   payloadType v02: 4 ==> expected: <PAYLOAD_TYPE_UNSUPPORTED> but was: <SCHEMA_MAJOR_UNKNOWN>
LaneAgreementReadingsTest > theShapeOfTheFirstSignatureEntryIsDecidedAtStepThreeBeforeThePayloadTypeAndTheCount() FAILED   signatures [1,2]: 5 ==> expected: <CONTAINER_INVALID> but was: <SIGNATURE_COUNT>
OneRecordTiesTest > everySecondCopyOfABenchFactMustAgreeWithBench() FAILED   producer confVersion below the floor: expected INCONSISTENT at step 15, the verifier accepted
OneRecordTiesTest > theSameTiesHoldForAFileExport() FAILED   body.producer.harness.confVersion: expected INCONSISTENT at step 15, the verifier accepted
SelfReportedTierTest > aHardwareClaimIsALabelAndNeverSatisfiesARequiredTierAboveA0() FAILED   strongbox required A1: expected TIER_INSUFFICIENT at step 18, the verifier accepted
SeqRaceTest > concurrentWritersOfTheFileStoreNeitherFailNorLeaveTemporaryFiles() FAILED   NoSuchFileException: .../seq.tmp -> .../seq
SeqRaceTest > twoDifferentBodiesSignedInTheSameSecondNeverShareASeq() FAILED   ExecutionException: NoSuchFileException: .../seq.tmp -> .../seq
$ (original src/main restored, new vectors in the generator) ./gradlew -p lab :manifest:genManifestVectors     -> generator refuses to write:
M03-179..M03-189: expected INCONSISTENT, but the verifier accepted        M03-191, M03-192: expected TIER_INSUFFICIENT, but the verifier accepted
M03-195, M03-196: expected FINGERPRINT_MISMATCH, but the verifier accepted
M03-201, M03-202: expected CONTAINER_INVALID at step 3, got CONTAINER_VERSION_UNKNOWN at step 3     M03-203: ... got SIGNATURE_COUNT at step 5     M03-204: ... got PAYLOAD_TYPE_UNSUPPORTED at step 4
M03-205: expected CONTAINER_INVALID at step 7, got KEY_NOT_PINNED at step 7     M03-206: expected PAYLOAD_TYPE_UNSUPPORTED at step 4, got SCHEMA_MAJOR_UNKNOWN at step 4     M03-207: expected SCHEMA_INVALID at step 11, got SCHEMA_MAJOR_UNKNOWN at step 11
$ ASOM_DIAGNOSTIC_DRIFT_FLAG=1 swift run --package-path apple asom-conformance check     (Swift lane before its fixes, new vectors present)
MISMATCH M03-179..M03-190 (expected reject INCONSISTENT, observed ok)   MISMATCH M03-191, M03-192 (expected reject TIER_INSUFFICIENT, observed ok)
MISMATCH M03-193 (expected reject SCHEMA_INVALID, observed ok)   MISMATCH M03-194 (expected reject FINGERPRINT_MISMATCH, observed ok)
MISMATCH M03-197..M03-200 (expected NON_INTEGER_NUMBER / DUPLICATE_KEY / INVALID_UNICODE / NUMBER_RANGE, observed reject MALFORMED_JSON)
MISMATCH M03-208 (expected reject CONTAINER_INVALID, observed ok)   MISMATCH M03-209, M03-210 (expected MALFORMED_JSON, observed TRAILING_DATA)   MISMATCH M03-211 (expected MALFORMED_JSON, observed NON_INTEGER_NUMBER)
MISMATCH M03-212, M03-213, M03-214 (expected NON_INTEGER_NUMBER, observed MALFORMED_JSON)   MISMATCH M05-109 (line 20: 96 '?' in this lane, 64 in the JVM lane)
checked 332, mismatches 28
```
Not reproduced as failures before the fix (test or vector gaps, as the review said): CV-10 (the JVM and Swift range clause existed; `NonReducedCoordinateTest`, `FixCryptoTests.testANonReducedCoordinate...` and M03-215 pass before and after, and are killed by the mutants below), CV-4, CV-5 and CV-7 on the JVM (already correct: the failure is Swift-only), and CV-6 in Swift (already ASCII-only). The Swift-only unit tests for CV-2 and CV-5 were written after the fix; their failing-before evidence is the mutants Y02, Y02b, Y05a and Y05b below, each of which restores the old code and fails the new tests (for example `FixCryptoManifestTests.testDefaultContextsRefuseTheTestOnlyKeys` and `testTheChallengeComparisonDoesNotFoldTheLengths` with `+256`).

After the fix (real output):
```
$ ./gradlew -p lab test                      (JDK 21)  -> BUILD SUCCESSFUL; M01 105, M02 20, M03 108, M05 42, M06 10 vectors, 0 fail
$ JAVA_HOME=/opt/jdks/jdk-17 ./gradlew -p lab test     -> BUILD SUCCESSFUL (Launcher JVM 17.0.12); :manifest 53 tests, :json 42, :conformance-runner 2052, :mesh-proto 185, 0 failures
$ swift test --package-path apple            -> Executed 176 tests, with 0 failures (0 unexpected)     (166 before; +10)
$ ASOM_DIAGNOSTIC_DRIFT_FLAG=1 swift run ... asom-conformance check -> checked 332, mismatches 0
$ python3 apple/ci/lane_diff.py jvm.lines swift.lines --known apple/ci/known-disagreements.txt ... -> vectors: jvm=379 swift=333 agree=291 disagree=42 (known 42) jvm-only=46 (not implemented 46)
$ python3 apple/ci/lane_diff.py jvm.lines swift_diag.lines --not-implemented ...                    -> vectors: jvm=379 swift=333 agree=333 disagree=0 (known 0) jvm-only=46 (not implemented 46)
$ python3 lab/tools/xcheck.py lab/conformance -> xcheck M02: 100 agree, 0 disagree; M03: 264 agree, 0 disagree; INDEX: 32 agree ... (all families 0 disagree)
$ python3 lab/tools/isolation.py -> isolation check 4: OK (shipped tree byte-identical to the pinned base)
$ python3 lab/tools/check_law.py -> law: OK
$ ./gradlew cleanTest jvmTest (root) -> BUILD SUCCESSFUL; root tests: 139, 0 failures
```
Mutation checks (narrow driver, each mutant applied, the suite run, the source restored byte for byte; the same mutants are registered as V31 to V41 in `lab/manifest/tools/mutants.py`, which was NOT run through its full three-layer harness): JVM, 23 mutants, 22 KILLED, 1 SURVIVED. Killed: every one-record tie separately (confVersion, engine name, commit, buildFlags, memory, os family, os version, vendor, model, soc), the whole rule, the tier gate on the label, Unicode uppercase, non-object container as version-unknown, no `signatures[0]` shape at step 3, non-string `signer.spki` as unpinned, a leading-zero major, no evidence item limit, no signer lock, a per-call lock, the fixed temp name, no coordinate range. SURVIVED: `X12-no-store-lock` (the per-path lock inside `FileSeqStore`): equivalent on Linux, because unique temp names and an atomic rename already make concurrent writers safe there; the lock exists for Windows, which refuses concurrent renames onto one target, and that cannot be tested here. Swift, 15 mutants, 15 KILLED: the whole one-record rule, default `productionKeys` false in `VerifyContext` and in `DSSEContext`, the tier gate on the label, the tolerant FILE presentation, the folding length compare for the challenge and for the fingerprint, the scan that stops at depth 17, two lexer mutants (a literal prefix followed by data; a digits-only number), no evidence item limit, no coordinate range, no long-word split. Two earlier lexer mutants were killed only by a crash or by an unrelated parse failure and were replaced by the two named. The directory sync after the rename in `FileSeqStore` is not observable by a test and has no mutant.

Changed existing tests (ruling-driven, each recorded in the ERRATA): `BoundaryTest.onlyHardwareBackedStorageIsTierA1` (CV-3); `VerifierTests.testTierFromKeyStorageAndTheRequiredTier` (CV-3); `StrictJSONTests.testRejectTable` row "lone minus" from `MALFORMED_JSON` to `NON_INTEGER_NUMBER` (CV-8, ERR-JSON-3); four Swift tests that edited one copy of a duplicated fact now edit both (CV-1); 27 Swift call sites now say `productionKeys: false` explicitly (CV-2). Changed vectors: M02-113, M02-118, M02-120, M03-131 (and M05-109, regenerated from M02-113). Added vectors: M02-121, M02-122, M03-179..215 (37, in `lab/conformance/manifest/M03-verify-reject-fx.json`).

NOT DONE / BLOCKED / for the orchestrator: (1) `lab/conformance/INDEX.json` was regenerated with `lab/tools/regen_index.py` (outside the track's directories; a generated dependant; it will conflict with any other track that regenerates it, so regenerate it once after merging). (2) `lab/conformance/VERSION` was not bumped and `lab/conformance/README.md` was not edited. (3) `HostileFramesTest` in `:mesh-proto` pins `vectors.size == 71` for `M03-verify-reject.json`; the new M03 vectors therefore live in a second file of the same family and that count still holds. (4) The M01-family vectors for the lexer and depth readings (CV-7, CV-8) are M03 vectors at step 2, because `lab/conformance/json` is outside this track. (5) `.github/workflows/apple-ios.yml` needed no edit (it reads `known-disagreements.txt`, which gained five ids). (6) Not run: macOS/CryptoKit, Windows, hosted CI; two PROCESSES writing one seq store is not covered (needs an OS file lock). (7) CV-3 is a design ruling the next design revision must fold into LAB_SPEC 4.6 steps 17/18 and the M02-118 row. (8) `docs/design/mesh/*.md` were not edited.
Result: **PASSED** for the ten findings in the LAB (oracle: self).

## Review fixes, track fix-router (LTQ-01, 02, 05, 06, 12, 13, 15: `:mesh-router`, `:mesh-sim`, R01/R02/R03/R06/M08 vectors) — 2026-10-07 — LAB
Evidence label: LAB and SIMULATED, oracle: self. NOT DEVICE EVIDENCE. The worktree was created at 98ab632 and was reset to BASE_SHA f6a8f1efeea47c0c3ef8653a34762e9d6b706fe0 (fetch of the branch claude/asom-v1-build-brief-vw83oh, then a hard reset to that sha; `docs/design/mesh/LAB_SPEC.md` present). Nothing committed.
Failing-before: every fix has a test that failed on the unfixed code (`TrackerFixTest` 8 of 13 failed, `LiveStateFixTest` 7 of 9 failed; the ones that passed before are the test-gap tests LTQ-12/13 and controls, and are mutation-checked below). Real commands:
$ ./gradlew -p lab :mesh-router:test :mesh-sim:test :conformance-runner:test --max-workers=2   (JDK 21.0.10)  BUILD SUCCESSFUL; mesh-router 59 tests 0 failures; mesh-sim 37 tests 0 failures; conformance-runner 2061 tests 0 failures (7 skipped, as before this change)
$ JAVA_HOME=/opt/jdks/jdk-17 ./gradlew -p lab :mesh-router:test :mesh-sim:test :conformance-runner:test --max-workers=2   (JDK 17.0.12)  BUILD SUCCESSFUL; same counts 59 / 37 / 2061, 0 failures
Vector families after the change: M08 77 (was 59), R01 91 (81), R02 50 (46), R03 33 (31), R06 68 (54), all pass; `power-fields iterations: 3564 violations: 0`.
$ python3 lab/tools/isolation.py -> isolation check 4: OK (shipped tree byte-identical to the pinned base)
$ python3 lab/tools/check_law.py -> law: OK
$ python3 lab/tools/xcheck.py lab/conformance -> xcheck W04: 249 agree, 0 disagree; W05: 195 agree, 0 disagree
$ ./gradlew jvmTest (root) -> BUILD SUCCESSFUL; root tests 139, 0 failures (`:core:routing:test` and `:server:test` FROM-CACHE: cached results of the same inputs)
Mutation checks (scratch runner outside the repo; each mutant applied to the source, `:mesh-router:test :conformance-runner:test` (+ `:mesh-sim:test` for the simulator ones) run, the file restored byte for byte): 25 mutants, 25 KILLED after two additions. First pass: 2 survivors (a sibling memory mark taken from the worst entry only: killed after adding `theMemoryMarkOfAnySiblingAppliesEvenWhenAnotherSiblingIsTheWorstState`; the simulator pulling on the digest class only: killed after adding `PowerFieldsTest`) and 2 mutants that did not compile (rewritten and killed). Killed: stateAt exact key only; capAt exact key only; memory mark not merged; slotOf exact key; penalty count per entry; claim body wipes `recent`; re-dial clears GOAWAY; re-dial clears regressed; GOAWAY not recorded for the full state; power freshness = digest freshness; equal-seq full state ignored; F11 reads digest expiry; F9 reads power expiry; STALE band substitution dropped; effective freshness = digest only; simulator pulls on digest class only; curve validation off; curve allows duplicates; curve allows five points; inherited DISCREPANT clears at MIN_STATE (R21); no penalty doubling (R19); best index (3n - 1)/4 (R12); `bad >= 3` in `disc` (R18); half-open peer not probe-only (R29); digest regression ignored (R9).
What was built: tracker keyed (peer, file) with the backend only picking the claim row (ERR-FX-RT-1), curve validation to F8 (RT-2), the discard budget kept across a claim seq (RT-3), GOAWAY and regression surviving a re-dial (RT-5), power fields aged from the last full STATE (RT-6, additive `NodeView.powerFreshness`, simulator pulls when either class is STALE), new M08 kinds `inherit`, `claimBudget`, `penalty`, `disc`. Reading choices and residuals are in `lab/ERRATA.md` ERR-FX-RT-1..8.
Not done / not verified: the Windows lane and hosted CI (not run here); ratios kept across a backend switch were measured against the old claim row (RT-1 residual, owner decision); the first-claim-body inheritance loophole (RT-3 note) is recorded and NOT changed; a half-open peer's probe-only flag has a unit test but no R01/R02 vector (the expected shape has no field for it); the simulator's state-pull counts rose slightly because of the added pulls and the gate scenarios still pass.
Result: **PASSED** in the LAB for the six findings; LTQ-06 is a SPEC GAP resolved conservatively and recorded.

## Fix track fix-ledger (LTQ-03, LTQ-04, LTQ-10, LTQ-11, LTQ-14) - 2026-10-07 - LAB (not device evidence)

Worktree base: HEAD was 98ab632f7460..., not BASE_SHA, so a fetch of `claude/asom-v1-build-brief-vw83oh` and a hard reset to f6a8f1efeea47c0c3ef8653a34762e9d6b706fe0 were run; HEAD then printed f6a8f1efeea47c0c3ef8653a34762e9d6b706fe0 and `ls docs/design/mesh/LAB_SPEC.md` succeeded. Nothing committed.

Failing-before evidence (real output, before any fix):
- LTQ-03: `SinkTest > reopeningAfterATornTailTruncatesItInsteadOfMergingOntoItAndKeepsEveryDurableRow_LTQ03() FAILED  CorruptRowException: line 3 is not a JSON object: Reject(MALFORMED_JSON at 18: expected ':')` (5 of 14 SinkTest tests failed).
- LTQ-04: `HostileCodesTest > aReceivedErrorCodeOutsideTheClosedSetIsStoredAsUNKNOWN_LTQ04() FAILED  code 'x 192.168.1.20 my prompt text' ==> expected: <ERROR:UNKNOWN> but was: <ERROR:x 192.168.1.20 my prompt text>`; GOAWAY `expected: <GOAWAY:unknown> but was: <GOAWAY:x 192.168.1.20 my prompt text>`; `5 tests completed, 4 failed`.
- LTQ-10, LTQ-11, LTQ-14 are TEST_GAPs: proved by mutation (below), not by a failing-before run.

Gate output after the fixes (Linux, this machine):
```
$ ./gradlew --max-workers=2 -p lab labTest --offline          (JDK 21)   -> BUILD SUCCESSFUL in 1m 18s, exit 0
$ JAVA_HOME=/opt/jdks/jdk-17 ./gradlew --max-workers=2 -p lab labTest --offline  (JDK 17.0.12)  -> BUILD SUCCESSFUL in 2m 11s, exit 0
  JDK 17 run, from the test XML: :ledger-model 94 tests, 0 failures; :conformance-runner 2015 tests, 0 failures; family L02: 23 vectors, 23 pass (L02-022, L02-023 added)
$ ./gradlew --max-workers=2 -p ubuntu-touch/jvm utTest --offline   -> BUILD SUCCESSFUL (JsonlSink caller)
$ ./gradlew --max-workers=2 jvmTest --offline                       -> BUILD SUCCESSFUL; root tests: 139, 0 failures
$ python3 lab/tools/isolation.py   -> isolation check 4: OK (shipped tree byte-identical to the pinned base)
$ python3 lab/tools/check_law.py   -> law: OK
DurabilityHarnessTest line: rows intact after kill inside a write: 2 durable rows verified, torn tail bytes: 294, restart append read back clean
```
Mutation checks (apply mutant, run the named tests, restore; 17 mutants, 17 KILLED, 0 survivors): LTQ03 no torn-tail truncation on open (5 tests fail); no rollback after a failed append (2); LTQ04 ERROR code raw (2); GOAWAY reason raw (1); no CONTROL grammar rule (1); grammar accepts any `ERROR:` suffix (1); LTQ11 default force a no-op (2); `force(true)` (1); constructor default an inline no-op (2); force before write (2); LTQ10 appendControl without failClosed (8); failClosed without wire.close (9); handOff without the closed check (1); close row status 200 (6); LTQ14 lower bound 0 (2); lower exclusive (2); upper exclusive (1). The L02-022 and L02-023 vectors were also shown to fail in `:conformance-runner:test` against the raw-ERROR-code mutant.

Result: LTQ-03, LTQ-04, LTQ-10, LTQ-11 FIXED; LTQ-14 PARTIAL (only the `withinRecordBound` lower bound is in this track; the router `Scoring.kt` boundary and the sim RL16 counter are outside its write set); LTQ-04 in `:mesh-proto` `SessionRows.kt` REJECTED (already a closed enum there). Reading choices: `lab/ERRATA.md` ERR-FX-1..5. UNVERIFIED: Windows (append-mode truncate), Linux only here. `lab/conformance/INDEX.json` changed by one line (the L02 sha256, regenerated by `lab/tools/regen_index.py`).
## Fix gate, track fix-desktop-linux-ci (review findings EGR-1..9, EGR-11, HLU-4..7, HA-09) — 2026-10-07 — LAB (not device evidence)
Base: `f6a8f1efeea47c0c3ef8653a34762e9d6b706fe0` (the worktree began at another commit and was reset to it; `git rev-parse HEAD` then printed the base and `docs/design/mesh/LAB_SPEC.md` exists). Uncommitted working tree. Machine: Ubuntu 24.04 container, x86_64, JDK 21 (default) and JDK 17.0.12 at /opt/jdks/jdk-17, no Android SDK, no Docker, no hosted runner. Readings and choices: `desktop/ERRATA.md` ERR-FX-1..14.
Per finding, failing-before evidence (a test or command run on the OLD code, output kept) and the result:
```
EGR-1  old install.sh + a scratch key "Mallory" signing SHA256SUMS: "signature SHA256SUMS.asc verified by gpg (this proves the owner's key signed the checksums, not that the code is safe)", rc=0
       new, unpinned: "signature SHA256SUMS.asc is valid and made by key 31C3946B... which is NOT checked against the owner's key (no owner fingerprint is pinned in this installer yet)"; pinned to another key: "...is NOT the owner's pinned key 0123..." rc=1, nothing installed; pinned to the signer: accepted (positive control)
EGR-2  old: top asom-desktop--rf-linux-x86_64 -> "mv: cannot stat .../current.tmp.28521" and a symlink "current.tmp.28521" left in the caller's cwd; top ...-current-... -> "are the same file", prefix/current left a real directory
       new: "unsafe version string '-rf' in the archive name (it must start with a digit)" / "'current'", cwd empty, nothing installed
EGR-5  old: lib/x -> ../../<top>/bin installs, rc=0, "no symlink leaving the image" printed. new: "the archive holds a symlink that leaves the image (...9.9.9-hostile/lib/x -> ../../...)", rc=1
EGR-8  lab-packaging-check.sh against the OLD install.sh (swapped in, then restored): checks passed 110, failed 16 (the new hostile cases, the cwd check, the 6 signature checks, "the tarball's install.sh differs")
       new: install-refusals 41 checks, install-signature 8 checks; "14 hostile archives exercised"; separate canary scans plus a planted-canary positive control
EGR-3/9/11 desktop/tools/check_workflows.py on the ORIGINAL desktop-linux, desktop-windows, lab, cleanup-artifacts workflows: 23 VIOLATION lines (15 checkouts keeping credentials, 5 images by tag, 2 github-script by tag, 1 input interpolated into a script body)
       new (desktop-linux, lab, cleanup-artifacts): "workflow-law: 3 workflow files; third-party uses checked: 33; run/script bodies: 34; checkouts: 10; container images: 5 / workflow-law: OK"
EGR-4  old lab check_law.py on a copy of lab/ with a mesh-sim Mutant.kt (HttpServer, AsynchronousServerSocketChannel, DatagramChannel on "::", HttpClient): "law: OK"
       new: 3 VIOLATION lines (wildcard bind InetSocketAddress(8080), listener HttpServer, outbound HttpClient). The old desktop/tools/check_law.py likewise printed "law: OK" on a desktop Mutant.kt; the new one flags it.
EGR-6  test/fetch-nfpm-check.sh against the OLD fetch-nfpm.sh and build-packages.sh: "fetch-nfpm-check: 1 passed, 8 failed" (the planted cached binary was executed; build-packages ran the PATH nfpm). New: "fetch-nfpm-check: 9 passed, 0 failed". Real download path (network via the proxy): sha256 22aa6d3b... matched, second call "cached tarball matches the pinned sha256, re-extracting the binary from it".
EGR-7  proposal only (ERR-FX-8). systemd-analyze security --offline: "Overall exposure level for asom.service: 3.6 OK"; systemd-vm.sh gained check 1b (ceiling 4.0), never run on a hosted runner.
HLU-4  PresenceLawsTest new cases (startup-mid-band-never-serves, startup-quiet-dwell-60s, startup-blind-sample-not-eligible, startup-gpu-counter-must-settle) against the OLD governor logic: 4 FAILED ("a blind first sample is not eligibility ==> expected: <[]> but was: <[CONDITIONS_MET]>", "59,999 ms below 200 is not 60 s", ...). New: pass.
HLU-5  JsonlLedgerSinkTest "a 65 MiB tail with no newline is removed at open and open returns" against the OLD sink: "execution timed out after 30000 ms". New: passes, recoveredTornBytes = 65 MiB.
HLU-6  PowerProbeTest "an unreadable or unclassifiable power tree is unknown..." against the OLD probe: "a power_supply path that cannot be listed (not ENOENT) is unknown ==> expected: <UNKNOWN> but was: <AC>". New: pass.
HLU-7  LinuxPathsPrepareTest (5 cases) against the OLD prepare: 5 of 5 failed (0755 identity dir kept, symlinked ledger and identity dirs accepted, CACHEDIR.TAG 0644, system runtime dir 0755 kept). New: 6 cases pass (the owner-mismatch case is new API).
HA-09  lab.yml: xcheck --families L01,W07,W07p -> "xcheck L01: 56 agree, 0 disagree / W07: 67 agree / W07p: 97 agree"; gen_vectors.py + regen_index.py on a copy of lab/ reproduce lab/conformance byte for byte ("IDENTICAL").
```
Gates, real output (JDK 21 unless stated):
```
$ lab-packaging-check.sh --dist <built dist> --probe <built probe>   (root, ASOM_REQUIRE_UNSHARE=1, TMPDIR=/tmp; image built with build-app-image.sh, packages with build-packages.sh using the pinned nfpm 2.41.3)
  family install-tarball: 27 checks / install-refusals: 41 / install-signature: 8 / uninstall: 12 / deb-layout: 19 / package-scripts: 19
  checks passed: 126   failed: 0          lab-packaging-check: PASS          (was 104 checks before this track)
$ test/fetch-nfpm-check.sh                -> fetch-nfpm-check: 9 passed, 0 failed
$ shellcheck -x -S warning <the CI list plus test/fetch-nfpm-check.sh>   (shellcheck-py, in a venv)   -> clean (one pre-existing SC2034 in test/systemd-vm.sh, which the CI list does not include, line 139, untouched)
$ python3 lab/tools/isolation.py          -> "isolation check 4: OK (shipped tree byte-identical to the pinned base)"
$ python3 lab/tools/check_law.py          -> law: OK  (236 sources)      --selftest -> law selftest OK: 17 mutants caught, 3 clean controls passed, harness and test scopes behave
$ desktop/tools/isolation.sh              -> ISOLATION: all checks passed  (incl. check_law --selftest "16 mutants caught ... patterns equal lab/tools/check_law.py" and check_workflows --selftest "10 mutants caught, 2 clean controls passed")
$ python3 desktop/tools/check_paths.py    -> check_paths: 1210 tracked paths checked, 0 problems
$ ./gradlew -p desktop desktopTest :packaging:windows:winplatform:test :packaging:macos:macplatform:test --rerun-tasks   (JDK 21 AND JDK 17.0.12)
  node-core 76 tests 0 failed; node 127 tests 0 failed; winplatform "141 tests, 114 passed, 0 failed, 27 skipped"; macplatform "141 tests, 130 passed, 0 failed, 11 skipped"   (both JDKs identical)
$ ./gradlew -p lab labTest                 -> BUILD SUCCESSFUL on JDK 21 and on JDK 17 (no lab Kotlin changed; lab/tools/check_law.py only)
$ ./gradlew jvmTest (root)                 -> BUILD SUCCESSFUL; root tests: 139, 0 failures
```
Mutation checks: install.sh (old script into the lab check: 16 failures, above); fetch-nfpm/build-packages (old scripts: 8 of 9 failed); lab check_law (HttpServer removed from LISTENER: "mutant not caught in main of mesh-sim: HttpServer"; HttpClient removed from DIAL: the lab selftest AND the desktop selftest's pattern-equality check fail); check_workflows (SHA rule weakened: "mutant not caught: tag-pinned action"; injection rule disabled: 3 mutants not caught); Governor, ledger sink, power probe, paths (old code: the new tests fail, above). NOT mutation-checked by a real escape: the install canary scan (GNU tar 1.35 refuses every write-through case, so only the planted-canary positive control exercises the scan).
BLOCKED / NOT DONE (honest): EGR-11 (`apple-ios.yml` is not in this track's file list; two path-filter lines, ERR-FX-14). EGR-9 PARTIAL: matrix images pinned by digest and checkouts stop persisting credentials in `desktop-linux.yml` and `lab.yml`; NOT `desktop-windows.yml` (its byte-for-byte canonical copy under desktop/packaging/windows/ci is the Windows track's, ERR-FX-4) and NOT the whole-checkout mount in `ubuntu-touch.yml`'s smoke container (not in the file list). The image digests came from mirror.gcr.io; Docker Hub returned 429. HLU-4 PARTIAL BY DESIGN: `Governor` is strict by default and the Linux host adopts it; Windows and macOS keep the old start until their (unowned) tests are warmed up, because with the strict start on, 2 Windows and 3 macOS tests failed (ERR-FX-5). EGR-7 is a proposal only; the unit file is unchanged. CI-ONLY, never run on a hosted runner: the new `fetch-nfpm-check.sh` step, the digest-pinned install matrix (the digests were not pulled here: no Docker), `systemd-vm.sh` check 1b, the new lab.yml steps, `check_workflows.py` inside `isolation.sh` on JDK 17/21 and aarch64. The signature accept path uses a scratch key and a sed-substituted copy of the installer; no real owner fingerprint exists.
Oracle status: self-oracled where vectors are concerned (none were changed); the Python cross-checks in lab.yml never clear the oracle tag.
Result: PASSED for the findings fixed (LAB evidence only); PARTIAL and BLOCKED items as listed.


## Gate: fix-windows-mac (review findings HWM-1 to HWM-9, HWM-11, HWM-12, HWM-13, HA-01, HA-08)

Evidence label: LAB (Linux container, fakes behind the Windows and macOS port interfaces). NOT DEVICE EVIDENCE: nothing here ran on Windows or macOS; the Windows and macOS integration tests (`*IT`) SKIPPED as designed.
Base `f6a8f1efeea47c0c3ef8653a34762e9d6b706fe0` confirmed (the worktree started at another commit and was reset to it).

Failing before the fix (new tests `FixWindowsTest`, `FixMacTest`, run against the production code with only behaviour-preserving seams added): winplatform had 11 failing tests (HWM-1 x2, HWM-2, HWM-3, HWM-4, HWM-5, HWM-6, HWM-12, HWM-13 x2, plus the law-vacuity check); macplatform had 9 failing tests (HWM-7, HWM-8, HWM-9 x3, HWM-11, HA-01, HA-08, plus the law-vacuity check). Captured JUnit XML is in the builder's scratchpad (`fixwm/win-before-fix.xml`, `fixwm/mac-before-fix.xml`).

After the fix, real output:

```
./gradlew --max-workers=2 --offline -p desktop :packaging:windows:winplatform:test :packaging:macos:macplatform:test   (JDK 21)
winplatform test summary: 153 tests, 126 passed, 0 failed, 27 skipped   (os=Linux; ...)
macplatform test summary: 150 tests, 139 passed, 0 failed, 11 skipped   (os=Linux; ...)
BUILD SUCCESSFUL in 50s
JAVA_HOME=/opt/jdks/jdk-17 ./gradlew ... (same command)
winplatform test summary: 153 tests, 126 passed, 0 failed, 27 skipped   (os=Linux; ...)
macplatform test summary: 150 tests, 139 passed, 0 failed, 11 skipped   (os=Linux; ...)
BUILD SUCCESSFUL in 1m 56s
python3 lab/tools/isolation.py      -> isolation check 4: OK (shipped tree byte-identical to the pinned base)
python3 lab/tools/check_law.py      -> law: OK
python3 desktop/tools/isolation.py  -> isolation check 4: OK (shipped tree byte-identical to the pinned base)
python3 desktop/tools/check_law.py  -> law: OK
python3 desktop/tools/check_paths.py -> check_paths: 1210 tracked paths checked, 0 problems
./gradlew --offline jvmTest (root)  -> BUILD SUCCESSFUL (tasks FROM-CACHE / UP-TO-DATE: the root build was not changed, so this re-ran nothing; the 139-test count is NOT re-observed here)
```

Mutation checks (apply, run the suite, restore; all restored, `git diff` shows only the intended changes): 26 mutants, 25 killed, 1 equivalent.
Killed: HWM-1 providerAbsent always true; HWM-1 store swallows existing(); HWM-2 old any-self-test bypass; HWM-3 no state-tree check; HWM-3 directory only; HWM-4 escalation rights folded into WRITE; HWM-5 socket file unchecked; HWM-5 socket owner rule relaxed; HWM-6 unreadable console read as none; HWM-12 narrow before range check; HWM-13 truncation ignored; HWM-13 exit code ignored; HWM-13 runner never reads past the cap; HWM-7 stored SPKI check removed; HWM-8 reresolve ignores the recorded address; HWM-8 several candidates pick lowest name; HWM-9 stale assertion never detected; HWM-9 epoch ignored; HWM-9 read does not re-assert; HWM-9 HelperProcess epoch constant; HWM-11 unreadable binding migrated; HA-01/08 node limit raised to 103; HA-08 zero escape restored; HA-01 checklist reverted; HA-08 tried-check removed (first run SURVIVED, a partial-probe case was added to the test, re-run killed).
Equivalent (not killable): HWM-12 `isFinite` check removed: `Math.round(+Infinity)` is `Long.MAX_VALUE`, which the range check on the Long rejects anyway, so the explicit check is redundant defence.

Existing assertions changed because they pinned the behaviour a finding says is wrong (recorded in the ERRATA rows, not hidden): `NetAndControlTest` (two cases: several overlay candidates without a selection, and a changed overlay address on the same utun; HWM-8) and `PathsAndGuardTest` (an unreadable binding record is `Unverifiable`, not `Migrated`; HWM-11).

NEEDS-DEVICE-VALIDATION (open): `desktop/packaging/windows/docs/DEVICE_CHECKLIST_WINDOWS.md` FX-1, FX-3, FX-5, FX-6; `DEVICE_CHECKLIST_MACOS.md` S-M7a (its pass criterion corrected). PARTIAL: HWM-6 does not enumerate RDP sessions. Rows: `desktop/packaging/windows/ERRATA.md` and `desktop/packaging/macos/ERRATA.md`, ids `ERR-FX-*`.
Result: **PASSED in the LAB** for the pure logic of every finding; the Windows and macOS runtime behaviour is unverified.

## Ubuntu Touch review fixes (track fix-ubuntu-touch: HLU-1, HLU-2, HLU-3, HLU-8) - 2026-10-07 - LAB (not device evidence)

Evidence label: LAB / CI-APPROX on this container, NOT DEVICE EVIDENCE. No Qt is installed here, so `tests/qml/tst_Text.qml` and the QML changes were NOT run by a QML runtime (the static check and the JVM tests were). Base: `f6a8f1efeea47c0c3ef8653a34762e9d6b706fe0` (the worktree was created at 98ab632 and reset to it with `git fetch origin claude/asom-v1-build-brief-vw83oh && git reset --hard f6a8f1e...`; `docs/design/mesh/LAB_SPEC.md` present).
Failing before the fix (real runs on the unfixed tree): `python3 ubuntu-touch/tools/check_qml_text.py` -> 18 VIOLATION lines, one per Label/Text, exit 1; `LedgerRecoveryTest` 8 tests, 8 failed (`CorruptRowException` out of `NodeSession.run`, `FrameTooLargeException`, exit 74 for an unreadable ledger, torn tail kept, last bad row accepted at open); `WatchdogLockTest`: `aTickMeasuresTheGapFromTheMomentItsThreadWokeNotFromWhenItGotTheLock` failed with states `[idle, active, interrupted, idle]` and `aSelfTestThatTakesTenSecondsDoesNotMakeTheWatchdogReportASuspend` failed with "the tick at 1000 ms is stuck behind the self-test: it holds the session lock".
$ ./gradlew -p ubuntu-touch/jvm utTest --max-workers=2                      (JDK 21.0.10)  BUILD SUCCESSFUL; ut-host 64 tests, 0 failed, 0 skipped (15 new: LedgerRecoveryTest 12, WatchdogLockTest 3)
$ JAVA_HOME=/opt/jdks/jdk-17 ./gradlew -p ubuntu-touch/jvm utTest --max-workers=2  (JDK 17.0.12)  BUILD SUCCESSFUL; ut-host 64 tests, 0 failed, 0 skipped
$ python3 ubuntu-touch/tools/check_qml_text.py --selftest -> check_qml_text selftest: OK (17 negative, 7 positive controls)
$ python3 ubuntu-touch/tools/check_qml_text.py          -> check_qml_text: 18 Label/Text/TextEdit objects in 13 QML/JS files / check_qml_text: OK
$ ASOM_GRADLE_FLAGS=--max-workers=2 ubuntu-touch/tools/isolation.sh -> ISOLATION: all checks passed (runs check_law.py and the new check_qml_text.py)
$ python3 lab/tools/isolation.py -> isolation check 4: OK (shipped tree byte-identical to the pinned base);  python3 lab/tools/check_law.py -> law: OK;  python3 ubuntu-touch/tools/check_law.py -> law: OK
$ ./gradlew :core:*:cleanTest :server:cleanTest jvmTest --no-build-cache -> BUILD SUCCESSFUL; root tests: 139, 0 failures (really executed, not from cache)
Built jar (`utNodeJar`, run with `-Duser.name=tester`, clean env, temp HOME), unfixed behaviour from the finding vs now: ledger.jsonl = `{"ts":1,"callerPkg":"x"{"ts":2}\n` then hello, active, peers open, ledger query -> now `state interrupted`, `error LEDGER_UNAVAILABLE` (twice, for peers and ledger), stderr `asom-ut: ledger unavailable`, exit 0 (finding: exit 70). Self-test under `-Xint` with a heartbeat every second -> `selftest` result at 14.07 s and states `idle`, `active` only (finding: `interrupted`, `idle` at 14.3 s).
Mutation checks (`python3 ubuntu-touch/tools/mutants.py --kotlin --only NAME --tests CLASS`, each restored): 12 new mutants, 12 KILLED: ledger-tail-kept, ledger-last-row-unchecked, ledger-rollback-skipped, ledger-poison-skipped, ledger-budget-ignored, ledger-limit-ignored, ledger-corrupt-uncaught, ledger-toolarge-uncaught, ledger-loss-keeps-state, selftest-under-lock, tick-stamped-after-lock, selftest-not-awaited. The QML static check was mutated by deleting one `textFormat` line from `PeersPage.qml`: it printed `VIOLATION: qml/PeersPage.qml:39 ...` and exit 1; restored. Not re-run: the older 25 mutants (their targets are unchanged apart from the `apply(event, atMs)` default parameter).
Not done / not verified: `tst_Text.qml` (needs Qt; CI `clickable test` runs it); the workflow file `.github/workflows/ubuntu-touch.yml` was not edited (outside this track), the check runs through `isolation.sh` and `clickable.yaml`; `FileLedger.append` has no production caller in UT-0. Reading choices: `ubuntu-touch/ERRATA.md` ERR-FX-UT-1 to ERR-FX-UT-4.
Result: HLU-1 FIXED, HLU-2 FIXED (PLAUSIBLE finding; real defect reproduced in tests), HLU-3 FIXED, HLU-8 FIXED, in the LAB sense above.

## Corrections to the record, 2026-10-07 (fix-docs): HA-03 to HA-07

Evidence label: **LAB** for the commands below (this machine, Linux, JDK 17.0.12 and JDK 21.0.10, `--max-workers=2`) and **CI (hosted VM)** for the check-run conclusions, read through GitHub's check-runs API (conclusions and step names only: the job logs are served from a blob host that this sandbox's `gh` refuses, so no hosted output is pasted here). NOT DEVICE EVIDENCE. Base: the worktree was created at `98ab632`, then `git fetch origin claude/asom-v1-build-brief-vw83oh && git reset --hard f6a8f1efeea47c0c3ef8653a34762e9d6b706fe0`; `git rev-parse HEAD` then printed `f6a8f1efeea47c0c3ef8653a34762e9d6b706fe0` and `docs/design/mesh/LAB_SPEC.md` exists. Nothing is committed or pushed. This section only appends; every earlier line stays as it was written, and where an earlier line is wrong this section says which one and why.

### HA-03: gates recorded PASSED with the JDK 17 half never run

Confirmed. LAB_SPEC 8.1 says a gate has not passed when the real output differs from the expected column, and the L0.2 gate asks for JDK 17 and 21. Each of these entries says in its own text that JDK 17 was not run, and still ends in a bare "Result: PASSED". Read them as follows (the earlier lines are not edited):

| Entry (heading above) | Its own words on JDK 17 | Status to read it as |
|---|---|---|
| Lab L0.2a-json gate (`:json`, M01) | "JDK 17 NOT available in this container" | PASSED on JDK 21 (LAB). JDK 17 half was open when written; closed below |
| Lab L0.2 + L0.3 gate (`:bench-core`, `:manifest`) | "JDK 17 NOT available in this container" | same |
| Lab L0.4 gate (`:ledger-model`, `:mesh-policy`) | "JDK 17 lane NOT run here" | same |
| Lab L0.6 gate (`:mesh-router`, `:mesh-sim`) | "JDK 17 lane NOT run here" | same |
| Lab L0.5 gate, wire half and trust half (`:mesh-proto` wire and trust) | "JDK 17 lane NOT run here" | same (not in the finding; identical defect) |

The TLS half and the session half of L0.5 and the DL0/DL1 and DL2 entries carry JDK 17 results of their own and are not affected.

How the JDK 17 half is closed, with real output. JDK 17 is selected through `JAVA_HOME`; `JAVA_HOME=/opt/jdks/jdk-17 ./gradlew -p lab --version` printed `Launcher JVM:  17.0.12 (Eclipse Adoptium 17.0.12+7)`.

```
$ JAVA_HOME=/opt/jdks/jdk-17 ./gradlew -p lab test --max-workers=2 --continue
BUILD SUCCESSFUL in 2m 14s
58 actionable tasks: 24 executed, 34 from cache
(every lab module's :test task executed; the 34 from cache were :core:* and :server tests, not lab modules)
```
Test counts read from `lab/<module>/build/test-results/test/*.xml` after that run (tests / failures / errors / skipped), JDK 17.0.12:
```
json                 42 / 0 / 0 / 0
bench-core           41 / 0 / 0 / 0
manifest             36 / 0 / 0 / 0
ledger-model         67 / 0 / 0 / 0
mesh-policy          32 / 0 / 0 / 0
mesh-proto          185 / 0 / 0 / 0
mesh-router          35 / 0 / 0 / 0
mesh-sim             36 / 0 / 0 / 0
conformance-runner 2013 / 0 / 0 / 7
```
The 7 skipped in `conformance-runner`: W00-100 to W00-104 and W01b-reach (PROPOSED vectors, reported `proposed-skipped`) and `RegenerateVectors` (runs only under `genVectors`); none is an Assumption.

The same command on JDK 21.0.10 (`./gradlew -p lab test --max-workers=2 --continue --rerun-tasks`) gave identical per-module counts and 0 failures in every lab module. It ended `BUILD FAILED in 2m 27s` for one reason outside the lab: `:server:test` (the unmodified v1 test `AsomServerIntegrationTest > a failed-over attempt gets its own cloud ledger row`, line 540, 1 of 65) failed once under the full `--rerun-tasks` load; `./gradlew -p lab :server:test --rerun` then printed `BUILD SUCCESSFUL in 6s`. That is the known v1 ledger-read race class (see the re-pin tables in `lab/ERRATA.md`); it was not investigated or touched here (`server/` is outside this track).

What this closes and what it does not. It is a JDK 17 result for the tree at `f6a8f1ef`, which contains those modules plus later work; it is not a re-run of each gate's own commit. It is Linux only; the Windows lane is covered only by the hosted conclusions below.

Hosted conclusions (CI (hosted VM), head `37332005d32538e30b1a4330c748efcdc0a6c7cb`, read with `gh api repos/mbaliga/asystemofmodels/commits/37332005/check-runs`): 52 check runs, 52 success. `Lab (pure JVM, JDK 17)` https://github.com/mbaliga/asystemofmodels/actions/runs/37566435041/job/112615204314 and `Lab (pure JVM, JDK 21)` https://github.com/mbaliga/asystemofmodels/actions/runs/37566435041/job/112615204415 (the JDK 17 job's step "Lab tests (prints the per-family vector counts)" concluded success); `W0 lab on Windows (JDK 17)` https://github.com/mbaliga/asystemofmodels/actions/runs/37566435127/job/112616034107 and `(JDK 21)` https://github.com/mbaliga/asystemofmodels/actions/runs/37566435127/job/112616034114 concluded success. At `6711bb5d`, 58 of 58 check runs concluded success. Pasted family lines from those jobs: none (logs not reachable).

Observed, outside the findings, NOT investigated: the hosted `Lab (pure JVM, JDK 17)` job concluded **failure** at `a358a1dd` (https://github.com/mbaliga/asystemofmodels/actions/runs/37604110270/job/112738342815) and at the branch tip `5b6b4352` (https://github.com/mbaliga/asystemofmodels/actions/runs/37607936621/job/112747899349), in the step "Lab tests (prints the per-family vector counts)", while the JDK 21 job concluded success at both; `Lab reference counts (Linux, JDK 17)` also failed at both. The tip commit's message is "lab: make integration tests wait on conditions (CI timing flakes)". So JDK 17 hosted is red at the tip as of this reading; the local JDK 17 pass above is at the older base `f6a8f1ef` and says nothing about those later commits.

### HA-04: Apple I0b mutation report count

Confirmed. The entry "Apple lane I0b gate" says "Five survived the first pass and were killed by tests added afterwards (marked *)" but marks seven:
```
$ sed -n 1305,1320p PROGRESS.md | grep -o 'KILLED\*' | wc -l
7
```
(run before this correction was appended; the seven are D06, C01, B04, S01, S02, S06, U02). The total is right: 61 `KILLED` plus 2 `SURVIVED (equivalent)` is 63 distinct ids, none repeated (counted in lines 1307 to 1318). The mutation log is not committed, so the repository cannot show which two of the seven stars are wrong or whether "Five" was the miscount. Conservative reading: the **stars are authoritative, so seven mutants survived the first pass and were killed afterwards**; read "Five" in that line as "Seven". Nothing was re-mutated here.

### HA-05: ERRATA ids that named two entries

Confirmed, exactly four. Before-check (a script written for this, kept in the session scratchpad and not committed; it lists ids defined as a table row, a bold `**E-n.` item or a heading):
```
./apple/ERRATA.md 40 ids; duplicates: {'E-15': [82, 90]}
./desktop/ERRATA.md 47 ids; duplicates: {'ERR-DL2-13': [52, 63]}
./desktop/packaging/linux/ERRATA.md 14 ids; duplicates: {}
./lab/ERRATA.md 170 ids; duplicates: {}
./ubuntu-touch/ERRATA.md 29 ids; duplicates: {'ERR-UT-CLICK-2': [31, 59], 'ERR-UT-QML-1': [34, 63]}
```
ERRATA files are append-only for this track, so no heading was edited. Each now has an appended disambiguation note that gives the orchestrator section a new id: `ERR-DL2-15` (desktop/ERRATA.md), `ERR-UT-CLICK-4` and `ERR-UT-QML-3` (ubuntu-touch/ERRATA.md), `E-29` (apple/ERRATA.md). Citations, resolved:

- `PROGRESS.md` line 900 cites `ERR-DL2-13` for the drain: that is the table row, correct as written.
- The hosted-CI entry for head `aa060c4` (line 1330) cites `ERR-DL2-13` for the colon path: that means **`ERR-DL2-15`**; and cites `ERR-UT-CLICK-2` for Clickable parsing the AppArmor file as JSON: that means **`ERR-UT-CLICK-4`**.
- `ubuntu-touch/CMakeLists.txt` line 22 cites `ERR-UT-CLICK-2` and means `ERR-UT-CLICK-4`; `ubuntu-touch/tests/run_qml_ci.sh` line 5 cites `ERR-UT-QML-1` and means `ERR-UT-QML-3`. Both files are outside this track's write set, so the comments were NOT changed (the finding suggested changing `CMakeLists.txt`); the ERRATA note is the key. A later track that owns those files should update the two comments.

### HA-06: the hosted-CI entry's "Open" list is stale

Confirmed against the ERRATA and the hosted state. The entry for head `aa060c4` ("Hosted CI state for the multi-platform mesh program") said the Canary failure's cause was "not investigated", the xvfb retry was "not yet read from a log", and cited no run URL. Updated state:

- **Canary (26.04-1.x), cause found** (`ubuntu-touch/ERRATA.md`, `ERR-UT-QML-3`, the orchestrator section formerly headed `ERR-UT-QML-1`): the click builds; the canary image's `click-review` does not know the `ubuntu-touch-26.04-1.x` framework. The canary job now builds with `clickable build --skip-review` and runs `clickable review` as an informational step. At `37332005` the check run `Canary - the next series (26.04-1.x), NON-GATING` concluded **success**: https://github.com/mbaliga/asystemofmodels/actions/runs/37566435049/job/112616062294 (this track read the conclusion, not the log).
- **`tst_StatusPage.qml` and the xvfb retry**: `tests/run_qml_ci.sh` now runs the real-Lomiri QML tests under `xvfb-run` with Mesa software GL as its primary path; the ERRATA records that the hosted log of the follow-up run shows 6 of 6 passing (this track did not read that log). At `37332005`, `QML tests against the real Lomiri.Components (fake node)` concluded success: https://github.com/mbaliga/asystemofmodels/actions/runs/37566435049/job/112615442506
- **Head `37332005`: 52 of 52 check runs success** (check-runs API). Workflow runs, all `head_sha` `37332005`, conclusion success: lab https://github.com/mbaliga/asystemofmodels/actions/runs/37566435041 , ubuntu-touch https://github.com/mbaliga/asystemofmodels/actions/runs/37566435049 , desktop-linux https://github.com/mbaliga/asystemofmodels/actions/runs/37566435063 , desktop-macos https://github.com/mbaliga/asystemofmodels/actions/runs/37566435017 , desktop-windows https://github.com/mbaliga/asystemofmodels/actions/runs/37566435127 , apple-ios https://github.com/mbaliga/asystemofmodels/actions/runs/37566435004 , CI https://github.com/mbaliga/asystemofmodels/actions/runs/37566435059 .
- **Still open** from that entry's list: the AppArmor approximation printed denials for `/proc/<pid>/net/if_inet6`, hugepages and coredump_filter (a device-checklist item); the Secure Enclave, power-assertion, PDH, netsh and DPAPI results are hosted-VM observations, not device evidence; every device and real-key item stays `NEEDS-DEVICE-VALIDATION` / `NEEDS-OWNER-VALIDATION`.
- **No longer true** in that entry's last sentence ("Not started: `:mesh-proto` (wave 3) ..."): `:mesh-proto` is built (wire, trust, pairing, tls, transport and session packages; 185 tests, see HA-03). Not claimed here: whether the final independent review and revision 4 of the design brief are finished.
- Also open and new: the hosted JDK 17 lab job at `a358a1dd` and `5b6b4352` (see HA-03).

### HA-07: READMEs that described the code as unbuilt or never run

Confirmed, with two refinements found while checking. What changed, each checked against the code or a command:

- `lab/README.md`: the "empty shells" rows now describe the built modules with their test counts (json 42, bench-core 41, manifest 36, ledger-model 67, mesh-policy 32, mesh-proto 185, mesh-router 35, mesh-sim 36, conformance-runner 2013). Refinement: **`mesh-proto` is not a shell either** (the finding left it as one); only `LabModule.kt` and its shell test remain in it.
- `lab/conformance/README.md`: the file table now lists the 30 indexed vector files by family with counts read from the files; `history/r0/` and `scenarios/` do not exist at this commit and the table called them reserved.
- `desktop/README.md`: status rewritten. Refinement: the finding's "listens on nothing ... all are built" is **half right**. Built and tested: the control socket server and client, the mini D-Bus reader, the sleep watcher, keep-awake locks, systemd units, DL3 packaging, the Windows host (W0 to W2) and the macOS host (MC1, MC2). Still true: `asom-node` does not start the control socket (`ControlServer.notYetImplemented`, D23/D25), so a running node binds nothing; the Linux `nikStore` is `NotYetImplementedNikStore`; no engine; no mesh. The README says exactly that. Its "reserved `native/`" row is replaced: that directory does not exist.
- `desktop/docs/LINUX.md`: the control socket's directory is 0700 for USER and FOREGROUND and 0750 for SYSTEM (`SocketDirRule.PRIVATE` and `GROUP_TRAVERSE` in `ControlServer.kt`; `RuntimeDirectoryMode=0750` in `desktop/packaging/linux/systemd/asom.service`).
- `apple/README.md`, `ubuntu-touch/README.md`, `desktop/packaging/windows/README.md`, `desktop/packaging/macos/README.md`, `desktop/packaging/linux/README.md`: the "never run on GitHub / until a hosted run has been seen / CI-ONLY, never run" statements keep their historical text and gain a dated "Superseded 2026-10-07" note naming the hosted check runs that concluded success at `37332005` (URLs in HA-03 and HA-06 above; conclusions and step names only). The notes keep the label `CI (hosted VM) evidence` and say device results are unchanged. Not changed because still true: `apple/README.md` says `signPresentation` is not built in the Swift lane (only `lab/manifest/.../Signer.kt` has it); the Steam Deck document says nothing has run on a Deck.
- `docs/design/mesh/OWNER_BRIEF.md`: not edited; no check made here showed a statement in it to be wrong.

### Final gates for this entry (real output, LAB, base `f6a8f1ef` plus the uncommitted documentation changes)

```
$ python3 lab/tools/isolation.py
isolation check 4: base 79ff5b81fcc4c8a435813acaa192be206874eacc (from lab/LAB_BASE_SHA), ancestor of HEAD confirmed
isolation check 4: 136 protected files compared byte-for-byte against base 79ff5b81fcc4
isolation check 4: OK (shipped tree byte-identical to the pinned base)
$ python3 lab/tools/check_law.py
law: 9 module build files checked against the LAB_SPEC 1.2 dependency table
law: 236 Kotlin/Java sources scanned (no android imports, no frozen-enum reuse, no wildcard bind, listeners only in the harness or tests)
law: OK
$ python3 desktop/tools/check_paths.py
check_paths: 1210 tracked paths checked, 0 problems
$ ./gradlew jvmTest --rerun-tasks --max-workers=2
BUILD SUCCESSFUL in 13s   (root tests read from the XML: 139 tests, 0 failures, 0 errors, 0 skipped; the jacoco report resolved here)
```
`desktop/tools/isolation.py`, `desktop/tools/check_law.py`, `ubuntu-touch/tools/isolation.py`, `ubuntu-touch/tools/check_law.py` and `ubuntu-touch/tools/check_texts.py` also printed OK. No source or test file changed in this track, so no module test result depends on it beyond the two full-lab runs above. Not run: the Swift lane (`swift test`; documentation only changed under `apple/`), any hosted job.

## Gate: Apple Swift lane repaired after the merge of the seven fix groups (2026-10-07)

Evidence: LAB, oracle: self, NOT DEVICE EVIDENCE. Linux Swift only; macOS and CryptoKit are unverified here. Output below is real (vector bodies not printed).

Findings: (1) `SignerTests` verified a TEST-ONLY-signed FILE document with the new fail-closed default (ERR-FX-CV2): the context now says `productionKeys: false`. (2) Cross-lane fixture M02-930 claimed requiredTier A1 accepted (pre ERR-FX-CV3); retagged to requiredTier A0 (still a strongbox claim, labelled A1) and the fixtures regenerated. (3) `not-implemented.txt` was right for M04 (29) but the router group added 14 M08 vectors (kinds inherit, claimBudget, penalty, disc) the Swift tracker does not run, and 4 M08 `state` vectors it does; `known-disagreements.txt` had been merged into two copies (37 + 37 + 5 reasonless) so `lane_diff.py` reported duplicates. See `apple/ERRATA.md` ERR-FX-M08. The Swift tracker still clears the discard budget on a new claim seq (lab ERR-FX-RT-3 says it must not): a real, listed disagreement in rule, not fixed here.

```
$ swift test --package-path apple
Executed 226 tests, with 0 failures (0 unexpected)
$ asom-conformance check M01,M02,M03,M04,M05,M06,M08
checked 413, mismatches 47     (42 LF-1 verdicts = known-disagreements.txt; 5 LF-1 values M04-042, M06-201..204)
$ ./gradlew -p lab :conformance-runner:run --args='lines M01,M02,M03,M04,M05,M06,M08'   (JDK 17)
456 lines
$ python3 apple/ci/lane_diff.py jvm.lines swift.lines --known ... --not-implemented ...
vectors: jvm=456 swift=413 agree=371 disagree=42 (known 42) jvm-only=43 (not implemented 43)
  (F-1 diagnostic reading, ASOM_DIAGNOSTIC_DRIFT_FLAG=1: agree=413 disagree=0)
$ python3 apple/ci/test_lane_diff.py   -> Ran 13 tests OK
$ python3 apple/ci/test_crosslane.py   -> Ran 8 tests OK
crosslane: JVM, Swift and the fixture agree on 37 of 37 (23 ok, 14 reject)
$ python3 lab/tools/isolation.py  -> isolation check 4: OK
$ ./gradlew -p lab :conformance-runner:test --offline -> BUILD SUCCESSFUL
```

## Gate: Swift claim tracker brought to agreement with the JVM router fix group, M08-065..M08-078 (2026-10-07)

Evidence: LAB, oracle: self, NOT DEVICE EVIDENCE. Linux Swift 6.1 only; macOS and CryptoKit are unverified here. Output below is real (vector bodies not printed).

Change: `ClaimTracker.onNewClaimSeq` no longer clears the discard-budget record (real defect; lab ERR-FX-RT-3; apple E-35 superseded). New `PeerClaimBook` / `DiscPenalty` (cross-file `disc`, 7-day penalty doubling on repeat); the M08 adapter runs `inherit` (3), `claimBudget` (2), `penalty` (4), `disc` (5); the 14 `BLOCKED(m08-tracker)` entries left `apple/ci/not-implemented.txt` (43 to 29). Independence: spec 6.6, the M08 vector files and the ERR-FX-RT rows of lab/ERRATA.md were read; no `lab/*/src` Kotlin (apple/ERRATA.md ERR-FX-M08-1, -2). Spec ambiguities that no vector settles (latch timing, repeat counter never reset, overflow saturation) are listed in ERR-FX-M08-2 with the conservative reading.

```
$ swift test --package-path apple     (new tests written first: build failed on the missing PeerClaimBook type)
Executed 235 tests, with 0 failures (0 unexpected)          (was 226; PeerClaimBookTests 9 new)
$ asom-conformance check M01,M02,M03,M04,M05,M06,M08
checked 427, mismatches 47     (the 42 + 5 LF-1 ones; 0 in M08)
$ ./gradlew -p lab :conformance-runner:run --args='lines M01,M02,M03,M04,M05,M06,M08' --quiet --offline   (JDK 17)
456 lines
$ python3 apple/ci/lane_diff.py jvm.lines swift.lines --known ... --not-implemented ...   (exit 0)
vectors: jvm=456 swift=427 agree=385 disagree=42 (known 42) jvm-only=29 (not implemented 29)
$ same with ASOM_DIAGNOSTIC_DRIFT_FLAG=1 (F-1 set aside, no --known)
vectors: jvm=456 swift=427 agree=427 disagree=0 (known 0) jvm-only=29 (not implemented 29)
$ python3 apple/ci/test_lane_diff.py  -> OK
$ python3 lab/tools/isolation.py      -> isolation check 4: OK (shipped tree byte-identical to the pinned base)
```

Mutation check (17 mutants of ClaimTracker.swift, each run against `swift test --filter AsomRouterCoreTests`; 17 killed, 0 survived): new seq clears `recent` again; inherited clears at 5 (constant); inherited clears at 5 (state); inherited clears at 5 (onObservation); no doubling on repeat; repeat counter does not advance; latch with one DISCREPANT file; `disc` count needs more than two files; penalty still runs at its end instant; a running penalty is extended; base penalty 6 days; repeated `disc` 500; `disc` ignores a running penalty; inherited files not counted; always inherit; best index off by one; overflow saturation dropped. (One first attempt at M06 matched a doc comment and showed "survived"; re-aimed at the code and killed.)

## Hosted CI after wave 3d, fix wave 1 and the Apple repairs (2026-10-07)

Head `384a1cfb14a96d1707b9da03aea29de7185b1602`: `gh api repos/mbaliga/asystemofmodels/commits/384a1cf/check-runs` lists **58 non-cleanup check runs, all `completed/success`** (lab Linux JDK 17 and 21, lab on Windows JDK 17 and 21, desktop Linux/macOS/Windows, apple-swift-lane on macos-latest (CryptoKit) and on swift:6.1-noble, ios-package-sim, jvm-lines, lane-diff, UT host JDK 17 and 21, QML tests against the real Lomiri.Components, click build arm64, AppArmor approximation, install matrix). Evidence label: `CI (hosted VM) evidence`; conclusions were read from GitHub's check runs, not the logs. **NOT DEVICE EVIDENCE.**

Failures seen on the way and what they were (all fixed in later commits on this branch, each with real output in the commit's own gate entry):
- lab `ProtoIntegrationGateTest`/`HandshakeLimiterTest`/`TimersTest` timing-dependent assertions on 2-core runners (`5b6b435`, `740dc56`): the tests now wait on the condition; the cause of the `HandshakeLimiterTest` failure was not reproduced locally, so that fix is a hypothesis that the hosted run since confirmed passes.
- `ut-host` expected exactly 4 lab vector files; the fix wave added a fifth (`d110c7c`).
- `tst_Text.qml` expected a ledger row's keys in insertion order; Qt returns them sorted (`bca0931`).
- `check_fake_nodes.py`: the JVM self-test frame could overtake a later frame on a slow runner; the script is now sent in two parts (`5e2528f`).
- Windows lab: the fix wave's torn-tail scan opened a ledger file that was never created where POSIX permissions do not exist (`384a1cf`).
- One Windows job failed on a transient 403 from repo.maven.apache.org (infrastructure, no code change).

Root `./gradlew jvmTest` is now 140 tests (was 139): the never-run `AnthropicDriverTest` case runs; `ROOT_TEST_BASELINE` and the three base-SHA pins were updated in a later commit with ERRATA re-pin rows.

Open: LF-1 (`thermal-drift` projection flag vs benchmark.md 13.3) needs an owner decision; the second independent review is running.

## Gate: fix-tls-trust (PTT-1 write stall and close, PTT-2 registry race, PTT-4 constant alert law)

Evidence label: LAB, oracle: self, NOT DEVICE EVIDENCE. Base 3a91da05 (worktree reset to it first). Failing-before runs (JDK 21, `./gradlew -p lab :mesh-proto:test --tests ...`):
- `WriteStallTest`: `closeReturnsInBoundedTimeWhileAWriterIsStalledAgainstAPeerThatDoesNotRead` and `revokingAPeerWhoseSessionIsStalledReturnsInBoundedTime` FAILED with "the call did not return within 8000 ms".
- `PeerRegistryConcurrencyTest`: 5 of 5 cases FAILED, e.g. "restore: a revoke that returned Changed was overwritten (status Known(status=PAIRED))", "pause: ... (status Known(status=SUSPENDED))", "round 0 ... status Known(status=PAIRED) after a revoke returned Changed".

After the fix: `WriteStallTest` 3 tests pass (revoke with a stalled session took 502 ms; laws close-bounded 1, close-wakes-writer 1, revoke-bounded 1, write-budget-typed 1); `PeerRegistryConcurrencyTest` 6 tests pass (stress: 40 of 40 rounds revoked; laws L3-under-race 4, L3-stress 40, revoke-landed-in-window 4). Mutation checks (each failed the suite, then restored): write timeout 0 (write-budget test fails "must end within its budget"); blocking `writeLock.lock()` in `flushClosing` (both close tests fail "did not return within 8000 ms"); no lock in `update` (scope and route cases and the stress case fail); no lock in `transition` (restore, pause, scope, route and stress cases fail).

Full runs, `./gradlew -p lab :mesh-proto:test :conformance-runner:test`: JDK 21 BUILD SUCCESSFUL, mesh-proto 221 tests 0 failures 0 skipped, conformance-runner 2102 tests 0 failures (7 skipped, the known PROPOSED/genVectors ones). JDK 17 (`JAVA_HOME=/opt/jdks/jdk-17`, fresh daemon, `--rerun-tasks`): BUILD SUCCESSFUL, the same counts. `python3 lab/tools/isolation.py`: "isolation check 4: OK". `python3 lab/tools/check_law.py`: "law: OK". Root `./gradlew jvmTest`: BUILD SUCCESSFUL, 140 tests, 0 failures (the base already pins 140; the assignment text said 139).

Open: BLOCKED(other-track) for PTT-1's last sentence: `integration/Node.kt` should post the GOAWAY to the session driver instead of writing on the revoking thread (see ERR-FX2-1).
## Gate: fix-pairing (review findings PPW-1, PPW-2, PPW-3, PPW-5, PPW-6, PSL-5 pairing-channel part), 2026-10-07

Worktree base confirmed: `git rev-parse HEAD` printed `98ab632f...` at the start (wrong commit, `docs/design/mesh/LAB_SPEC.md` missing), so `git fetch origin claude/asom-v1-build-brief-vw83oh && git reset --hard 3a91da05947359eebb468f05d5afb60424f315ba` was run; after it `git rev-parse HEAD` printed `3a91da05947359eebb468f05d5afb60424f315ba` and `ls docs/design/mesh/LAB_SPEC.md` succeeded. Nothing committed or pushed. Evidence label: LAB, oracle: self, NOT DEVICE EVIDENCE.

Failing before (new tests against the API skeleton with the old behaviour; `./gradlew -p lab :mesh-proto:test --tests '*PairR3Test*' --tests '*QrExpiryTest*' --tests '*PairDirectionTest*'`): `33 tests completed, 22 failed`, among them `PairDirectionTest.withTheTlsRoleOrientationDAcceptsOnlyWhatSMaySend...` ("PAIR_HELLO from the TLS server is the wrong direction ==> expected: <[]>"), `PairDirectionTest.aPeerThatSendsExtensionFramesWithoutEnd...` ("the connection is closed when the budget is spent"), `PairR3Test.underR3DShowsNoCodeAndAsksForOne` ("expected: <PromptTypedCode> but was: <ShowConsent(555 468)>"), `PairR3Test.underR3AnApprovalWithoutACodeNeverCounts...` ("expected: <AWAIT_REMOTE> but was: <COMMITTING>"), `PairR3Test.underR3AnApprovalFromSThatDoesNotOpenTheCommitment...` ("missing ==> expected: <CLOSED> but was: <AWAIT_REMOTE>"), `PairR3Test.underR3NothingSHasSentBeforeNonceDIsChosen...` ("R3 puts only the commitment on the wire"), `PairR3Test.onlyAnOpenUnexpiredWindowAdmitsAPairingConnection` (the skeleton used `state !is Closed`, the host wiring the finding describes), `QrExpiryTest.withoutTheGateAPhone...` ("now=1790000180 ==> expected: <OK> but was: <EXPIRED>"). The reproduction of the attack itself, against the first-built machines, is `PairRelayAttackTest.underR0AttackerGrindsNonceD...` (D ends in `COMMITTING` for the attacker's pin) and `PairR3Test.underR0AnAttackerInTheDRoleCanPredictTheCodeSWillShow...` (50 of 50 predictions equal the displayed code).

After the fix:
```
$ ./gradlew -p lab :mesh-proto:test :conformance-runner:test --max-workers=2 --offline -q --rerun-tasks      (JDK 21.0.10)
mesh-proto tests 252 failures 0 errors 0 skipped 0 ; conformance-runner tests 2102 failures 0 errors 0 skipped 7
$ JAVA_HOME=/opt/jdks/jdk-17 ./gradlew -p lab :mesh-proto:test :conformance-runner:test ... --rerun-tasks      (launcher JVM 17.0.12)
mesh-proto tests 252 failures 0 errors 0 skipped 0 ; conformance-runner tests 2102 failures 0 errors 0 skipped 7
$ ./gradlew jvmTest --rerun-tasks --max-workers=2 --offline
BUILD SUCCESSFUL in 13s ; root jvmTest tests 140 failures 0 errors 0 (the baseline is 140, see the entry above)
$ python3 lab/tools/isolation.py   -> isolation check 4: OK (shipped tree byte-identical to the pinned base)
$ python3 lab/tools/check_law.py   -> law: 273 Kotlin/Java sources scanned ... law: OK
```
The 7 skipped conformance tests are the PROPOSED vectors and `RegenerateVectors` (ERR-FX-DOCS-1), not new. New tests (all in new files): `PairR3Test` 18, `PairDirectionTest` 7, `PairRelayAttackTest` 3, `QrExpiryTest` 4, `W04R3RunnerTest` 7; each class counts its laws and fails on a zero count (for example `law pair-r3/r3-sas-not-predictable-from-wire: 2000 cases`, `law pair-direction/tls-role-refuses-wrong-direction: 4 cases`).

Mutation checks (a main-source line broken, the narrow suite run, the file restored; `git status` shows only the intended files afterwards): 16 mutants, 16 killed, 0 survived on the three first classes: commitment check always true; typed code not compared; S puts nonce_S on the wire; D's SAS from the commitment; connection binding off; direction guard off; budget off; window probe "any non-closed state"; D still shows the code; QR expiry always enforced; first frame may be anything; typed-code length unchecked; S reveals on decline; wrong codes uncounted; WINDOW_CLOSED treated as a decline; transcript over the commitment. `PairRelayAttackTest` alone kills the typed-compare, nonce-on-wire and (after `r3-late-opening-refused` was added) commitment-check mutants; the commitment mutant first SURVIVED it, which is why that test exists.

Honest limits. (1) The R3 profile, `QrExpiry.NONE` and `FROM_TLS_ROLE` are NOT the defaults: 41 frozen W04 `fsm` vectors, 104 `qrParse` vectors, `PairingFsmExhaustiveTest` and the session-track harnesses (which send `PAIR_HELLO` from the TLS server) pin the first-built behaviour, and none of those files is in this track's write set (ERRATA ERR-FX2-7 lists the flip). (2) No W04 vector was added to `W04-pairing.json` (outside the write set); `W04Vectors` can evaluate them. (3) PPW-6 and PSL-5 are PARTIAL (ERR-FX2-5, ERR-FX2-6). (4) `ProtoIntegrationGateTest.w08FramesAttemptsAndRegistry` ("revoked during a served attempt", engine never cancelled within 30 s) failed twice in a row on a loaded shared machine (load average about 5) and passed on the third run and in every later run; the same test passed at the untouched base copy; it does not touch pairing code, and was not changed.
## Review fixes, track fix-session (PSL-1, 2, 3, 4, 6, 7, 8, 9, 10; 2026-10-07)

Base `3a91da05` (worktree reset to it). Evidence label: LAB, oracle: self, NOT DEVICE EVIDENCE. Rows: `lab/ERRATA.md` ERR-FX2-1 to ERR-FX2-10.

```
$ ./gradlew -p lab :mesh-proto:test --tests '*SessionFixesTest*'   (session code at base; new constants only)
13 tests completed, 13 failed
$ same after the fix (JDK 21)                                       BUILD SUCCESSFUL (14 tests)
$ ./gradlew -p lab :mesh-proto:test --tests '*HandshakeLimiterTest*'  (limiter at base)
7 tests completed, 3 failed
$ ./gradlew -p lab test                      (JDK 21)   BUILD SUCCESSFUL; 2693 lab tests, 0 failures
$ JAVA_HOME=/opt/jdks/jdk-17 ./gradlew -p lab test --rerun-tasks   BUILD SUCCESSFUL; 2693 lab tests, 0 failures (mesh-proto 233)
$ python3 lab/tools/isolation.py -> isolation check 4: OK (shipped tree byte-identical to the pinned base)
$ python3 lab/tools/check_law.py -> law: OK
$ ./gradlew jvmTest                          BUILD SUCCESSFUL; 140 tests, 0 failures
```

Mutation check: 23 mutants of the session and limiter sources killed, 1 equivalent mutant (removed), 2 survived the first versions of the tests and were killed after strengthening them; list in ERR-FX2 closing paragraph. NOT RUN: Windows, hosted CI, `swift test` (outside this track).
## Gate: Apple lane review fixes ASC-01..ASC-10 (track fix-apple, 2026-10-07)

Evidence: LAB, oracle: self, NOT DEVICE EVIDENCE. Linux Swift 6.1 only; macOS and CryptoKit were not run. Output below is real. Readings and residuals are in `apple/ERRATA.md` ERR-FX2-ASC01..ASC10.

Findings: ASC-01 FIXED (a tripped discard budget no longer lifts DISCREPANT to WEAK), ASC-02 FIXED (spent state lives in the token), ASC-03 FIXED (hash length fold), ASC-04 FIXED with a stated limit (node-key guard only binds when the node key is passed), ASC-05 PARTIAL (ipados fixed; windows and ubuntu-touch stay refused; iOS low-battery label kept, needs a lab ruling), ASC-07 FIXED as a SPEC_GAP reading (token carries the ticks; a caller that passes none gets `heatTestPermitted == false` when the sheet needs the tick), ASC-09 FIXED, ASC-10 FIXED.

```
$ swift test --package-path apple
Executed 243 tests, with 0 failures (0 unexpected)          (was 235; 8 new tests)
$ asom-conformance check M01,M02,M03,M04,M05,M06,M08
checked 427, mismatches 47     (unchanged: the 42 + 5 LF-1 ones; 0 in M08)
$ conformance-runner lines M01,M02,M03,M04,M05,M06,M08   (JDK 21, installDist, -Dasom.repoRoot)
456 lines
$ python3 apple/ci/lane_diff.py jvm.lines swift.lines --known ... --not-implemented ...
vectors: jvm=456 swift=427 agree=385 disagree=42 (known 42) jvm-only=29 (not implemented 29)
$ python3 apple/ci/test_lane_diff.py -> OK
$ python3 lab/tools/isolation.py -> isolation check 4: OK (shipped tree byte-identical to the pinned base)
$ python3 lab/tools/check_law.py -> law: OK
$ ./gradlew jvmTest --max-workers=2 --offline -> BUILD SUCCESSFUL (every test task FROM-CACHE: no JVM source was touched)
```

Fail-first (each run before the fix, real failures): `PeerClaimBookTests.testATrippedBudgetNeverTakesAWindowOutOfDiscrepant` ("weak" is not equal to "discrepant"), `PeerClaimBookTests.testTruncatingEveryFileDoesNotEscapeThePenaltyOrTheDiscOf400` (count 1 not 2, penalty nil, disc 700 not 400), `ClaimTrackerTests.testATrackedRateIsNeverAboveTheClaimAndADiscOutsideZeroToOneThousandIsRefused` (did not throw for disc -1, 1001, 1500), `ProtocolTests.testASpentTokenIsSpentInEveryGateAndEveryCopyOfTheGate` (copy and other gate both succeeded), `ProtocolTests.testAHashWithTheRightPrefixAndExtraBytesMintsNothing` ("minted for a hash with 256 extra bytes"), `ProtocolTests.testIPadOSHasTheIOSHardCeilingsAndPlatformsWithoutARowAreRefused` (UnknownCeilingPlatform for ipados, 7 times), `SignerTests.testAFileExportIsNeverSignedByTheNodeKey` (did not throw, twice), `SignerTests.testSeqRule` (nextSeq nil/0 gave 0; 2^53, negative clock gave a value). `ProtocolTests.testTheTokenCarriesTheTicksAndRefusesWhatTheSheetDidNotOffer` uses the new API, so before the change it did not compile; its evidence is the mutants below.

Mutation check (15 mutants, each run against the matching `swift test --filter` suite; 14 killed, 1 survived): ASC-01 budget overrides DISCREPANT (killed, 11 failures); ASC-10 disc guard removed (killed), guard bound widened to 1001 (killed), final `min(rate, claimRate)` removed (SURVIVED: unreachable behind the guard, kept as defence in depth); ASC-02 spend never records (killed); ASC-03 length folded again (killed); ASC-07 unoffered opt-in accepted, rerun tick not enforced, `heatTestPermitted` always true, ticks dropped from the token (all killed); ASC-05 ipados row removed (killed); ASC-04 node-key guard removed (killed); ASC-09 floor of 1 removed, range cap removed, negative clock accepted (all killed). Each mutant was restored; the final run above is on the restored sources.

Not done: no M08 vector for ASC-01 was added (the vector files are the lab's); a scratch vector passed `asom-conformance check` here and the JVM runner's `lines` mode prints verdicts only, so the JVM lane's state value for that case was not observed. The conformance adapter (`R3Conformance.swift`, outside this track) still calls `confirm` without ticks. Existing assertions edited, not deleted: `testDiscCountsFilesDiscrepantByInheritanceToo` (it encoded the ASC-01 defect) and the `(nil, 0) -> 0` row of `testSeqRule` (the ASC-09 defect).
## Gate: fix-bench-derive (review findings BRP-01, -02, -04, -05 fixed; BRP-03, -07, -08 recorded for the owner), 2026-10-07

Base `3a91da05` (worktree reset to it, `ls docs/design/mesh/LAB_SPEC.md` ok). Evidence label: LAB (lab modules, JDK 17.0.12 and JDK 21), `oracle: self`, NOT DEVICE EVIDENCE. The Swift mirror (`apple/Sources/AsomBenchCore/Derive.swift`) was edited without a Swift toolchain: NOT COMPILED, NOT RUN here (apple/ERRATA.md ERR-FX2-SW1).

Fail-first (`FixBenchDeriveTest`, run before any fix): 7 of 9 failed: `aSustainBlockOnAFailedNumericsTierIsIgnoredByEveryAnswer` (expected null, was `Throttle(tier=T3, onsetMs=195000, stabilityPermille=663 ...)`), `aFastButWrongBackendIsNotAStrongProvider` ("sustain on T3, T3 numerics fail ==> expected: not equal but was: strong-provider"), `aFailedTiersSustainDoesNotLowerTheOverallConfidence` (expected HIGH, was LOW), `theOnsetThresholdIsTheFloorOfNineHundredPermillePeak` ("6824 < floor(6824.7) is false ==> expected null but was 3"), `aTierWithAnInsufficientTestIsNotProjectedSoNoRowOverclaims` (flags [charging, confidence-high]), `prefillAtDepthIsRefusedByTheDecoder` ("Expected an exception of class SchemaViolation, but completed successfully"), `aSustainedPhaseThatStartsWarmIsLowWhicheverRecordSaysSo` ("run cool, sustain tier warm ==> expected LOW but was HIGH"). The other two (the pinned LF-1 flag facts and the 9.5 seed order) pass before and after by design.

After the fixes: `./gradlew -p lab :bench-core:test :conformance-runner:test` JDK 21: bench-core 50 tests 0 failures; conformance-runner 2111 tests 0 failures 7 skipped (the 7 are the PROPOSED vectors and `RegenerateVectors`, as before). The same on `JAVA_HOME=/opt/jdks/jdk-17`: 50 / 0 and 2111 / 0 / 7. Full `./gradlew -p lab test` on both JDKs: json 42, ledger-model 94, manifest 53, mesh-policy 32, mesh-proto 213, mesh-router 59, mesh-sim 37, bench-core 50, conformance-runner 2111, 0 failures. Vectors: M04-043 and the text of M05-211 changed (regenerated by `genBenchVectors`; `INDEX.json` by `regen_index.py`); M04-037 to M04-039 and M04-055 to M04-060 are new (conformance-runner was 2102 tests before, 2111 now). `lab/conformance/VERSION` NOT bumped.

Mutation checks (each mutation applied, `FixBenchDeriveTest` failed, restored): sustain block of a failed tier not ignored (2 tests fail); tier map over all tiers (3 fail); real-valued onset threshold (1 fails); run-start-only sustain class (1 fails); tier-start-only sustain class (1 fails); projectable without the all-values rule (1 fails); depth refusal removed (1 fails).

`python3 lab/tools/isolation.py`: `isolation check 4: OK (shipped tree byte-identical to the pinned base)`. `python3 lab/tools/check_law.py`: `law: OK`. Root `./gradlew cleanTest jvmTest --no-build-cache`: BUILD SUCCESSFUL, 140 tests, 0 failures (the baseline is 140).

Not changed on purpose (owner decisions, lab/ERRATA.md ERR-FX2-3 and ERR-FX2-6): the row flags `thermal-drift`, `restarted`, `swapped` (LF-1), and the order of the outlier and drift rules (BRP-08). Not done: refusing a sustain block on a tier other than the 4.4 sustain tier (ERR-FX2-1); a sustain-block `startThermal` schema member (ERR-FX2-5).

Addendum to the fix-bench-derive gate (2026-10-07, same worktree, LAB, `oracle: self`, NOT DEVICE EVIDENCE): the first pass above did not run the hosted-CI step `python3 lab/tools/xcheck.py lab/conformance`. Run on that tree it printed `DISAGREE M04-037 ... onsetMs: expected None, xcheck 45000` and died with `TypeError: 'NoneType' object is not subscriptable` on M04-043. Fixed in `lab/bench-core/tools/xcheck_m04.py` (lab/ERRATA.md ERR-FX2-7). Now: `xcheck M04: 848 agree, 0 disagree`, `xcheck M05: 210 agree, 0 disagree`, exit 0; `xcheck.py lab/conformance --families L01,W07,W07p` exit 0. Re-run in this session: fail-first by reverting only `lab/bench-core/src/main` (`FixBenchDeriveTest`: `9 tests completed, 7 failed`), then `./gradlew -p lab test --rerun-tasks` on JDK 21 and on `/opt/jdks/jdk-17` (bench-core 50, conformance-runner 2111 with 7 skipped, json 42, ledger-model 94, manifest 53, mesh-policy 32, mesh-proto 213, mesh-router 59, mesh-sim 37, 0 failures each), `genBenchVectors` plus `regen_index.py` leave the tree unchanged, the seven mutations fail the suite again (2, 3, 1, 1, 1, 1, 1 tests) and are restored, `isolation.py` OK, `check_law.py` OK, root `cleanTest jvmTest --no-build-cache` BUILD SUCCESSFUL with 140 tests, 0 failures. The Swift mirror is still NOT COMPILED or RUN.
## fix-bench-exec (BRP-06, BRP-09, BRP-10, BRP-11), 2026-10-07, base 3a91da05

Evidence label: LAB (simulated host, NOT DEVICE EVIDENCE). BRP-10 FIXED in `Executor.kt` (zero-token sustain windows are dropped; an empty phase leaves `sustain` out). BRP-06, BRP-09 and BRP-11 are recorded as ERR-FX2-1 to ERR-FX2-7 in `lab/ERRATA.md` (each needs a closed-set, schema or renderer change outside this track).
Failing before the fix (`./gradlew -p lab :bench-core:test --tests '*SustainZeroTokenWindowTest*'`): `aWindowWhosePrefillOutlastsItIsNeverRecordedWithZeroTokens` failed with `zero-token window kept: [BWindow(tStartMs=0, tokens=0, micros=1, maxThermalCode=0), BWindow(tStartMs=15000, tokens=0, ...` (40 zero-token windows) and `aPhaseWhoseEveryWindowIsEmptyIsDroppedNotSigned` failed with `expected: <null> but was: <BSustain(tier=T2, ... endReason=TIME_CAP ...`.
After the fix, on JDK 21 and on JDK 17.0.12: `./gradlew --max-workers=2 -p lab :bench-core:test :conformance-runner:test` BUILD SUCCESSFUL both times; `SustainZeroTokenWindowTest` 4 tests (non-vacuity: hard ceiling at sustain start exercised 7 cases). Mutation checks: disabling the zero-token block fails 2 of 4; disabling the empty-phase check fails 3 of 4. `isolation.py` OK, `check_law.py` OK, root `jvmTest` 140 tests 0 failures (task results FROM-CACHE; XML counts summed: 140, 0).
## Corrections to the record, 2026-10-07 (fix-honesty): HON-1 to HON-9 except HON-7

Base `3a91da05947359eebb468f05d5afb60424f315ba`. Evidence label: LAB, oracle: self, NOT DEVICE EVIDENCE (the hosted-CI URLs are `CI (hosted VM) evidence`, read from the check-runs API, not from logs). Nothing below rewrites an earlier entry; each item says what the earlier text got wrong. The readings are in `lab/ERRATA.md`, `ERR-FX-HON` (rows `ERR-FX-VER`, `-SKIP`, `-L15`, `-W08`, `-CNT`, `-MUT`).

**HON-1 (Windows lab jobs failing at `6cde2e9`): already fixed, recorded here for accuracy.** The Windows lab failure was the torn-tail scan opening a ledger file that was never created where the file system has no POSIX permissions; it was fixed in `384a1cf`, and the entry "Hosted CI after wave 3d, fix wave 1 and the Apple repairs" above records 58 of 58 check runs `completed/success` at head `384a1cf`. Nothing was redone.

**HON-2 (a generated vector changed while `VERSION` stayed 0.2.0): OPEN, deliberately not bumped by this track.** The vectors that changed their expectation after `3733200` are M02-113, M02-118 (meaning moved under the CV-3 tier ruling), M02-120, M03-131 and M05-109; added: M02-121/122, M03-179..215 (new file), L02-022/023, R01-082..091, R02-047..050, R03-032/033, R06-055..068, M08-065..082, and the new files W04, W05, W06, W07-state. `lab/conformance/README.md` now lists them by id. The bump needs the generators and the Swift lane, which are outside this track: `lab/mesh-router/tools/common.py` (`CONF = "0.2.0"`, regenerated by the hosted router step that fails on any difference), `lab/mesh-policy/tools/gen_vectors.py`, `:manifest`'s `F.CONF` (the same constant is the file tag and the `harness.confVersion` inside signed documents) and `apple/Sources/AsomConformanceKit/R3Conformance.swift` (`supportedConfVersion`, and any other `VERSION` is read as the r0 set). The exact one-commit procedure is `ERR-FX-VER`. `INDEX.json` was re-run (`python3 lab/tools/regen_index.py` printed `INDEX.json: 31 entries`) and did not change, because no vector file changed.

**HON-3 (the six always-skipped vectors give a false reason): FIXED in text; the vectors still do not run, and the reason is now the true one.** Decided by reproduction. Before: `PureFamilies.kt:36` threw `NotRunnable("mesh addition: needs the lab types of :ledger-model / :mesh-policy (L0.4), ruled by ...")`, while `lab/ledger-model/.../LabRouteRecord.kt` exists and `:ledger-model` runs 94 tests, `:mesh-policy` 32. The vectors are `proposed` because owner decisions D12.1 to D12.4 (W00-100..104) and D3 (W01b-reach) are unruled and the v1 contract is frozen; no ruling appears in `OWNER_DIRECTIVES_2026-09-30.md`. After, the JUnit report of the suite says `Assumption failed: proposed-skipped: PROPOSED mesh addition: awaiting owner ruling D12.1, and the v1 contract is frozen; the runner has no checker for it (the lab modules exist)` (and D12.2, D12.3, D12.4 for the others). Both READMEs say the same. **Not fixed:** `ServerFamilies.kt:35` (W01b-reach) still says "needs LabRouteRecord from :ledger-model (L0.4)"; that file is outside this track (`ERR-FX-SKIP`). W01b-reach is exercised outside the runner by `RequestReachAndBytesTest.w01bReachHoldsThroughTheLabTypes` (ERRATA `ERR-LL-10`).

**HON-4 (the L-L15 detail line prints an equality whose sides differ): FIXED in the printed line; the stats class is not changed.** Failing before (a command over the two pasted gate entries of `PROGRESS.md`, the JDK 21 and JDK 17 `L-L15 detail` lines):
```
rows 531901 + overhead 840527 = 1372428 ; printed tap 1373757 ; equal? False ; gap 1329
rows 530676 + overhead 869496 = 1400172 ; printed tap 1401501 ; equal? False ; gap 1329
```
Cause: `TlsOracle.l15` checks `appSum - unsent + rawOut + overhead == tapTotal` per session but accumulates only `appSum - unsent` and `overhead` into `L15Stats`, so the sum of the out-of-session raw bytes (`rawOut`) was never printed. `ProtoIntegrationGateTest.summary` now prints `rows A + overhead O + R bytes written outside the session (derived as the tap total minus the other two; each session checks them exactly) = S = tap T` and asserts `R >= 0`. After (real lines from the test XML):
```
JDK 21.0.10: L-L15 detail: rows 531201 application bytes + 839482 overhead bytes + 1329 bytes written outside the session (...) = 1372012 = 1372012 bytes counted by the record tap
JDK 17.0.12: L-L15 detail: rows 531926 application bytes + 871355 overhead bytes + 1329 bytes written outside the session (...) = 1404610 = 1404610 bytes counted by the record tap
```
R is derived (tap minus rows minus overhead), so the identity is true by construction here and the exact check stays the per-session one; a real accumulated `rawOut` field in `L15Stats` (`TlsOracle.kt`, another track's file) is the better fix (`ERR-FX-L15`). Mutation check of the new assertion (`outOfSession >= 0` changed to `< 0`, narrow run of `ProtoIntegrationGateTest`, JDK 21): the suite FAILED with `AssertionFailedError: L-L15: the rows and their overhead count more bytes (1329) than the record tap saw`; restored byte for byte (md5 `4d4fc4bd...` before and after) and the narrow run then passed (`BUILD SUCCESSFUL`). That mutated run also showed one unrelated failure, `w08FramesAttemptsAndRegistry` ("hostile client case 'revoked during a served attempt': timed out after 30000 ms waiting for: the engine to be cancelled"), on a machine shared with other builders; it did not recur in the unmutated narrow run or in either full run, and it was not investigated.

**HON-5 (stale README counts): FIXED.** `./gradlew -p lab test` at head `3a91da05` (with this entry's edits), per module from the test XML, JDK 21.0.10 and JDK 17.0.12 identical: bench-core 41, conformance-runner 2102 (7 skipped), json 42, ledger-model 94, manifest 53, mesh-policy 32, mesh-proto 213, mesh-router 59, mesh-sim 37; 0 failures, 0 errors. `lab/README.md` and `lab/conformance/README.md` now carry these counts and the per-file vector counts read from `INDEX.json` (31 files, including `manifest/M03-verify-reject-fx.json`: M02 20, M03 71 + 37, L02 23, R01 91, R02 50, R03 33, R06 68, M08 77). The three "139" root floors (`lab/README.md`, `desktop/README.md`, `ubuntu-touch/README.md`) now say 140 and name `ROOT_TEST_BASELINE`, which reads 140 in all three directories. The HA-07 text above says "the 30 indexed vector files": it was 30 at `f6a8f1ef` and is 31 now.

**HON-6 (unrecorded changes after the fix-docs entry): CLOSED here.**
```
$ ./gradlew cleanTest jvmTest (root)  -> BUILD SUCCESSFUL; root tests (read from the test XML of :core:*, :server): 140, 0 failures, 0 errors, 0 skipped
```
(`:server:test` and `:core:routing:test` were restored `FROM-CACHE`, so their XML is the cached run's, not a fresh execution; the count is the point.) `5b16c79` added `Unit` to `server/src/test/.../AnthropicDriverTest.kt:224` so that a case that returned a non-Unit value and was never executed now runs, which is why the floor moved from 139 to 140; `8b12f1a` re-pinned `LAB_BASE_SHA`, `DESKTOP_BASE_SHA` and `UT_BASE_SHA` (ERRATA re-pin rows) and the three `ROOT_TEST_BASELINE` files read 140. The `Open` item of HA-06 and HA-03 about the hosted JDK 17 lab job is **closed**: at `740dc56` the check run `Lab (pure JVM, JDK 17)` concluded `success`, https://github.com/mbaliga/asystemofmodels/actions/runs/37608558354/job/112749939350 (the same run lists `W0 lab on Windows (JDK 17)` success and `Lab reference counts (Linux, JDK 17)` success; conclusions read from the API, not the logs). `740dc56` and `5b6b435` are the timing-assertion fixes, `d110c7c` bundles the fifth lab vector file in the Ubuntu Touch host test, `bca0931` makes `tst_Text` expect Qt's sorted key order, `5e2528f` sends the `check_fake_nodes` script in two parts; the entry "Hosted CI after wave 3d..." above already lists each with its cause.

**HON-8 (mutation evidence that cannot be reproduced): CORRECTED.** The fix-crypto entry reported 23 JVM mutants and said they are registered as V31 to V41 in `lab/manifest/tools/mutants.py`. `grep -c` over V31..V41 there gives **11** registered mutants (coordinate range, one-record whole rule, tier gate, Unicode uppercase, non-object container, `signatures[0]` shape, non-string spki, leading-zero major, evidence item limit, no signer lock, fixed temp name). **12 of the 23 are not registered and cannot be reproduced from committed code:** the ten separate one-record ties (confVersion, engine name, commit, buildFlags, memory, os family, os version, vendor, model, soc), the per-call lock, and the one survivor `X12-no-store-lock`. The 15 Swift mutants reported in the same entry are in no committed harness either (`grep -rli mutant apple` finds only ERRATA mentions); they were run by hand. The "22 killed, 1 survived" and "15 of 15 killed" results therefore stand as the author's report, not as something a reader can re-run. No mutant was added (the file belongs to the manifest track).

**HON-9 (`family W08: not-implemented`): label unchanged; meaning stated.** The runner still prints `family W08: not-implemented` on both JDKs. W08 has no vector file by design; its hostile cases are code in `:mesh-proto` (`W08HandshakeTest`, `HostileFramesTest`, `HostileFramesTls`, `HostileHandshakeSession`) and the integration gate prints their counts. A distinct printed status needs `Suite.kt` and changes the text that `ConformanceSuiteTest.unbuiltFamiliesAreNeverReportedAsPass` asserts for every unbuilt family; dropping W08 from `ALL_FAMILIES` would leave that test no unbuilt family and fail its `assertTrue(built.isNotEmpty())`. Neither file is in this track. `Vectors.kt` and `lab/conformance/README.md` now say that `not-implemented` for W08 means "no vectors here" and where W08 runs (`ERR-FX-W08`).

Other gates run for this entry (real output):
```
$ python3 lab/tools/isolation.py   -> isolation check 4: 136 protected files compared byte-for-byte against base 5b16c7992244 ... OK (shipped tree byte-identical to the pinned base)
$ python3 lab/tools/check_law.py   -> law: 267 Kotlin/Java sources scanned ... law: OK
$ python3 lab/tools/xcheck.py lab/conformance -> every family 0 disagree (last lines: xcheck W04: 249 agree, 0 disagree; xcheck W05: 195 agree, 0 disagree)
$ ./gradlew -p lab test (JDK 21.0.10) -> BUILD SUCCESSFUL, counts above
$ JAVA_HOME=/opt/jdks/jdk-17 ./gradlew -p lab test -> BUILD SUCCESSFUL, counts above
```
Not run: the Swift lane (`swift` is not installed on this machine) and hosted CI for these edits. Result: **PARTIAL**: HON-3 (W00 half), HON-4 (printed line), HON-5, HON-6 and HON-8 corrected; HON-9 explained, label unchanged; HON-2 open with the exact procedure recorded.

## Gate: Swift lane repaired after fix wave 2 (M04-057 to M04-059 text), 2026-10-07 (LAB, oracle: self, NOT DEVICE EVIDENCE)

Cause: `apple/Sources/AsomBenchCore/TextRenderer.swift` lacked two NOTES rules of the JVM renderer (heat-test low-confidence line; `running <name>` wording for a test outside the wording table). Derivation (BRP-01, BRP-05) was already in step. Details: `apple/ERRATA.md` ERR-FX2-SW2. No lab file touched, no vector or list edited. Real output (Linux Swift 6.1, JDK 21.0.10; JDK 17 not available here and not needed, nothing under lab/ changed):
```
$ swift test --package-path apple  -> Executed 243 tests, with 0 failures (0 unexpected)
$ swift run --package-path apple asom-conformance check M01,M02,M03,M04,M05,M06,M08  -> checked 436, mismatches 47 (the 42 LF-1 vectors of known-disagreements.txt + M04-042, M06-201..M06-204, all LF-1 value differences)
$ ./gradlew --offline -p lab :conformance-runner:run --args='lines M01,M02,M03,M04,M05,M06,M08'  -> 465 lines
$ python3 apple/ci/lane_diff.py jvm.lines swift.lines --known apple/ci/known-disagreements.txt --not-implemented apple/ci/not-implemented.txt  -> vectors: jvm=465 swift=436 agree=394 disagree=42 (known 42) jvm-only=29 (not implemented 29)
$ (diagnostic lines, ASOM_DIAGNOSTIC_DRIFT_FLAG=1, no known list)  -> agree=436 disagree=0 jvm-only=29 (not implemented 29)
$ python3 apple/ci/test_lane_diff.py  -> Ran 13 tests ... OK
$ ./gradlew -p lab :conformance-runner:test --offline  -> BUILD SUCCESSFUL
$ python3 lab/tools/isolation.py  -> isolation check 4: OK (shipped tree byte-identical to the pinned base)
```
Not run: macOS lane, hosted CI, JDK 17, the crosslane step.

## Design revision 4 (docs only), 2026-10-07

Pointer: `docs/design/mesh/REVISION_4.md` (change log: the 39 round-3 findings and every ERRATA reading, each ADOPTED, OWNER DECISION, REJECTED or DEFERRED) and `docs/design/mesh/OWNER_DECISIONS.md` (every open ratification). Docs-only: no code, vector or workflow changed; nothing here is device evidence.
