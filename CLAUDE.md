# asystemofmodels (asom) — session guide

**Read `ASOM_BUILD_BRIEF.md` (root) first — it is the frozen v1 spec. This file is the distilled operating manual for build sessions.**

asom is a sovereign model-routing daemon for Android: one app owns model files, BYOK cloud keys, routing, and an egress ledger, exposing an OpenAI-compatible API on `127.0.0.1:11435`. Other apps pair via AIDL ("AI hotspot") and are thin clients.

## Invariants — violating any of these fails the build (brief §1)

1. **No telemetry.** No analytics, no crash-reporting SaaS, no third-party data egress.
2. Server binds `127.0.0.1` **only**. Never `0.0.0.0`. No cleartext beyond localhost.
3. Only permitted egress: provider API calls (user's keys), catalogue.json fetch, model downloads. **Every** network event writes a ledger row.
4. BYOK keys: Keystore-wrapped, entered only in dashboard Keys tab, never in any API/logs/ledger.
5. Pairing identity is AIDL-verified via `Binder.getCallingUid()`. **No HTTP registration endpoint** (`POST /admin/register` is deleted legacy — do not implement).
6. Red/green never carry meaning (owner colorblind). Semantic pair: violet `#8E7BFF` / cyan `#08FED5`, always with shape/label redundancy.
7. UI is placeholder-functional Compose/Material3 against a token-contract seam. No visual design ambition.
8. No GMS/Firebase/Play-services dependencies.
9. Echo headers and ledger rows are built from the **same `RouteRecord`** — API and dashboard can never disagree.

Also: no new endpoints/headers beyond brief §5 without owner sign-off · no package renames (`xyz.mdhv.asom`) · no local engine in v1 (`NoopEngine` only; `local-only` → `501 LOCAL_ENGINE_ABSENT`) · no KMP · no chakravyuha hooks.

## Build commands (brief §3)

- Pure-JVM (works on bare JDK, no Android SDK — **this must never break**):
  - `./gradlew jvmTest` — all `:core:*` + `:server` tests
  - `./gradlew :server:test`, `./gradlew :core:routing:test`, etc.
  - `./gradlew :server:run` — desktop server (from P3)
- Full Android (needs `ANDROID_HOME` or `local.properties`; CI always has it):
  - `./gradlew :app:assembleDebug`
- Android modules are conditionally included in `settings.gradle.kts` — with no SDK present the build silently degrades to pure-JVM mode. That is by design.
- CI (`.github/workflows/ci.yml`) is the guaranteed build path: JVM tests on a bare JDK + debug APK artifact, Java 17.

## Module dependency law (brief §4)

Pure JVM (no `android.*` imports, ever): `:core:contract` (no deps) · `:core:catalogue` → contract · `:core:routing` → contract, catalogue · `:core:inference-api` → contract · `:server` → all `:core:*`.

Android: `:vault` → contract · `:pairing` → contract · `:storage` → contract, catalogue · `:ledger` → contract · `:app` → everything · `:client` → contract only (**dependency-minimal, ships in other apps**) · `:sample-client` → client.

## Phases & gates (brief §11) — log every gate to PROGRESS.md with real output

- P0 skeleton+CI → gate: CI green
- P1 contract+catalogue → gate: `:core:*` tests
- P2 routing → gate: `:core:routing:test`, ≥90% branch coverage on policy/cooldown
- P3 server desktop-runnable → gate: `:server:run` + committed curl transcript
- P4 real drivers → gate: mock-server tests; real-key smoke = `NEEDS-OWNER-VALIDATION`
- P5 Android shell → gate: CI APK; device checklist = `NEEDS-DEVICE-VALIDATION`
- P6 pairing+client (pin `docs/CLIENT_API.md` **before** implementing) → device checklist
- P7 storage → device checklist
- P8 polish → execute + commit `QA_V1.md`
- Phase 2 (local engine, semantic routing, loops): **do not start.**

## Discipline (brief §12)

Never mark a gate passed without pasting real command output into `PROGRESS.md`. No fabricated logs. If blocked: `BLOCKED(<reason>)` and stop. Device-only items stay open as `NEEDS-DEVICE-VALIDATION` until the owner confirms. Where `OWNER-FILL` appears (e.g. `CATALOGUE_URL`), use the committed fixture `fixtures/catalogue.v1.json` — never invent values.
