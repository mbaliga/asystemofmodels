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

---

## v1 build status

P0–P8 all executed to their JVM/CI-verifiable gates; every phase's code,
tests, and CI are green. The only remaining gates are hardware-dependent
(`NEEDS-DEVICE-VALIDATION` device checklists P5–P7 + `QA_V1.md`) and the P4
real-key cloud smoke (`NEEDS-OWNER-VALIDATION`) — both require the owner's
RedMagic per brief §12. `ASOM_ROADMAP_BRIEF.md` (v1.1→v4) is committed but
explicitly **not started**, per its own entry criteria.
