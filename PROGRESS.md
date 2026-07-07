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
- [ ] **Gate: CI green on skeleton** — pending first push

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

## P3 — Server (desktop-runnable) — not started

## P4 — Real drivers — not started

## P5 — Android shell — not started

## P6 — Pairing + client — not started

## P7 — Storage — not started

## P8 — Watched-object polish — not started
