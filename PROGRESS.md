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

## P6 — Pairing + client — not started

## P7 — Storage — not started

## P8 — Watched-object polish — not started
