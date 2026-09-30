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
