# LAB_SPEC: the pure-JVM mesh lab (implementable specification)

**Date:** 2026-09-30; **revision 4 amendments 2026-10-07 (§10 wins over §0–§9)** · **Companion to:** `ASOM_MESH_DESIGN.md` r4 (§9.1 work items L0.1–L0.6). The Swift lane L0.7 and the platform scaffolds are in `PLATFORM_PLAN.md`.
**Authorised by:** acting decisions AD-3 (placement) and AD-4 (the lab is the sanctioned exception), plus roadmap §14 item 7. **The lab ships nothing.** It changes no shipped module and produces no release artefact.
**Audience:** builders who implement it (AD-6) without reading the 45,000-word design. Everything a builder needs is here, or in a sibling spec that this file cites by section (r4, R4-L-01: this file is **not** self-contained; each sibling is normative only as amended). Where this file and a sibling disagree, this file wins; where this file and the design disagree, the design wins, and the builder stops with `BLOCKED(spec conflict: <where>)`.
**Tags:** `[Fnn]`/`[Ann]` refer to the design's Appendix A (verified facts) and Appendix B (assumptions). `PROVISIONAL` marks a constant that is a starting value, not a measurement.

---

## 0. Rules for builders (read first)

| # | Rule |
|---|---|
| R1 | **Write only these paths:** `lab/**`; `.github/workflows/lab.yml`; three lines in `.gitattributes` (§2.6); `PROGRESS.md` gate entries. Never modify `core/`, `server/`, `app/` or any other shipped module, the root `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, or `.github/workflows/ci.yml`. The one exception is §2.5, which is a fallback and must be recorded |
| R2 | The root build and `./gradlew jvmTest` must behave exactly as before (§2.4 check 4 and the test-count check) |
| R3 | **No `android.*` import anywhere in `lab/`.** No new third-party dependency beyond those in §1.3 |
| R4 | **Never reuse a frozen name for a new meaning.** The lab's egress enum is `LabEgress`, its record `LabRouteRecord`. `Egress.PEER` must not exist anywhere (`grep -rn 'Egress.PEER' lab/` prints nothing) |
| R5 | **Sockets bind `127.0.0.1` only, and only inside tests.** No `main` in the lab opens a listener. The hostile-node suite (W08) runs over loopback, test-only |
| R6 | **Integers only** in every signed JSON document and in live-state JSON (C1): no fractions, exponents, `-0`, `NaN`, duplicates or values beyond ±(2⁵³−1). Where a vector pins **v1** behaviour that uses a `Double` (the USD cost, latency EWMAs), the double is written as a JSON **string** holding the shortest round-trip decimal (as JDK 21's `Double.toString` prints it) and read with `java.lang.Double.parseDouble` |
| R7 | **Evidence labels are part of the output and are never dropped:** `LAB`, `SIMULATED — NOT DEVICE EVIDENCE`, `CI (hosted VM) evidence`. Nothing the lab prints is device evidence |
| R8 | **No fabricated output.** A gate passes only when its real command output is pasted into `PROGRESS.md`. If something cannot be done as specified: write `BLOCKED(<reason>)` in `PROGRESS.md` and stop that item |
| R9 | **Self-oracled vectors stay tagged** `"oracle": "self"` until an independent implementation agrees (§4.10). A same-session Kotlin/Python agreement never clears the tag |
| R10 | Every law and every vector family **counts the cases that exercised it** and fails if the count is zero (non-vacuity) |

---

## 1. Modules and the lab dependency law

### 1.1 Build layout

`lab/` is a **separate Gradle build** (its own `settings.gradle.kts`), run with the root wrapper as `./gradlew -p lab <task>`. It reads the five pure-JVM root projects **by directory**, under their root project paths, and never evaluates the root `settings.gradle.kts` (§2).

### 1.2 Modules

Package prefix for every lab module: `xyz.mdhv.asom.lab.<module>` (no existing package is extended).

| Gradle path | Directory | Contents | Work item | May depend on |
|---|---|---|---|---|
| `:core:contract`, `:core:catalogue`, `:core:routing`, `:core:inference-api`, `:server` | `../core/*`, `../server` (mapped, §2.2) | the frozen v1 code, compiled unchanged | — | (their own root build files) |
| `:json` | `lab/json` | strict tokenizer, typed JSON values, JCS integer-profile serialiser, strict base64 | L0.2 (first) | nothing |
| `:bench-core` | `lab/bench-core` | engine-free benchmark maths: `asom.bench/1` model, M04 derivation, projection to result rows, `asom.text/1` renderer, run-plan interpreter, governor FSM (fakes), bench-set pins | L0.3 | `:json` |
| `:manifest` | `lab/manifest` | ES256/DSSE, key formats and fingerprints, typed decoder, the r3 verifier, signer, audience projections, public derivative, `asom.manifest-text/1` renderer | L0.2 | `:json`, `:bench-core` |
| `:ledger-model` | `lab/ledger-model` | `LabEgress`, `LabRouteRecord` (13 v1 fields + mesh columns), intent/outcome machine, byte accounting, JSONL sink with `force`, crash-injecting sink, projections (record → echo map, record → row), laws L-L1…L-L16 | L0.4 | `:core:contract`, `:json` |
| `:mesh-policy` | `lab/mesh-policy` | destination sets `P`, eligibility, lender decision table, quiescence predicate, availability FSM and presence laws LP-0…LP-2 | L0.4 | `:ledger-model`, `:json` |
| `:mesh-proto` | `lab/mesh-proto` | frame codec, frame schemas, strict SPKI pins, DER certificate templates, `verifyPeerChain`, JSSE TLS 1.3 profile, peer registry FSM, pairing proof/SAS/transcript, per-frame ledger writer, W08 hostile node (test sources) | L0.5 | `:json`, `:manifest`, `:ledger-model`, `:mesh-policy` |
| `:mesh-router` | `lab/mesh-router` | snapshot model, filters, estimator, scorer, merge with the **unmodified** v1 `Router`, cap, route reasons, pure breaker, `ClaimTracker` (r3) | L0.6 | `:core:contract`, `:core:catalogue`, `:core:routing`, `:mesh-policy`, `:json` |
| `:mesh-sim` | `lab/mesh-sim` | discrete-event simulator, scenario loader, fault injection, replay (all test-scope) | L0.6 | `:mesh-router`, `:mesh-policy`, `:ledger-model`, `:core:catalogue` |
| `:conformance-runner` | `lab/conformance-runner` | runs every vector family; `lines` mode for lane diffs; the v1 golden-vector harness against the **real** `:server` | L0.1 | everything above, including `:server` |

`lab/conformance/` is data, not a Gradle project (§3.1). `lab/tools/xcheck.py` is a standalone Python 3 script (§3.10).

### 1.3 Dependency law

1. **Direction.** Edges only as in the table. No cycles. Only `:conformance-runner` may depend on `:server`.
2. **Pure JVM.** No `android.*`, no AGP, no Android SDK on any classpath (§2.4 check 2).
3. **Third-party libraries:** only `org.jetbrains.kotlin:kotlin-stdlib`, `kotlinx-coroutines-core` and `kotlinx-serialization-json` (all already in `gradle/libs.versions.toml`), plus `kotlin-test` for tests. **`kotlinx-serialization-json` may load vector files and scenario files only. Every normative parser (manifest, frames, live state) is the hand-written `:json` tokenizer.** No BouncyCastle: certificates use fixed DER templates (§7.4). Cryptography is `java.security` / `javax.net.ssl` only.
4. **The v1 router is called, never copied or modified.** `:mesh-router` constructs `xyz.mdhv.asom.routing.Router` with frozen adapters (§6.7).
5. **Checks:** `./gradlew -p lab :conformance-runner:dependencies --configuration runtimeClasspath | grep -cE 'com\.android|org\.bouncycastle|com\.google\.android'` prints `0`.

---

## 2. Build isolation (exact)

### 2.1 Why this mechanism

The root `settings.gradle.kts` includes the Android modules whenever it sees an Android SDK (`ANDROID_HOME`, `ANDROID_SDK_ROOT` or `local.properties` `sdk.dir`), and GitHub's Ubuntu runners have `ANDROID_HOME` set. A lab that evaluated the root settings, for example through `includeBuild("..")`, would pull AGP into its build whenever the SDK is present, which breaks the pure-JVM law for the lab. **Project-directory mapping** avoids evaluating the root settings at all.

### 2.2 `lab/settings.gradle.kts` (write exactly this, adding modules only as §1.2 lists them)

```kotlin
// lab/settings.gradle.kts: a SEPARATE build. It never evaluates ../settings.gradle.kts.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "asom-lab"

// The five pure-JVM root projects, mapped by directory under their ROOT project paths.
// The paths must be identical to the root build's, because server/build.gradle.kts
// refers to project(":core:contract") and so on.
val mapped = linkedMapOf(
    ":core" to "../core",
    ":core:contract" to "../core/contract",
    ":core:catalogue" to "../core/catalogue",
    ":core:routing" to "../core/routing",
    ":core:inference-api" to "../core/inference-api",
    ":server" to "../server",
)
mapped.forEach { (path, dir) ->
    include(path)
    project(path).projectDir = file(dir)
}

listOf(
    "json", "bench-core", "manifest", "ledger-model", "mesh-policy",
    "mesh-proto", "mesh-router", "mesh-sim", "conformance-runner",
).forEach { include(":$it") }
```

### 2.3 `lab/build.gradle.kts` and `lab/gradle.properties`

```kotlin
// lab/build.gradle.kts
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// The mapped projects must never write into ../core/*/build or ../server/build.
val mappedPaths = setOf(":core", ":core:contract", ":core:catalogue", ":core:routing", ":core:inference-api", ":server")
subprojects {
    if (path in mappedPaths) {
        layout.buildDirectory.set(
            rootProject.layout.buildDirectory.dir("mapped/" + path.removePrefix(":").replace(':', '_'))
        )
    }
}

val labModules = listOf(
    "json", "bench-core", "manifest", "ledger-model", "mesh-policy",
    "mesh-proto", "mesh-router", "mesh-sim", "conformance-runner",
)

tasks.register("labTest") {
    group = "verification"
    description = "Runs every lab test. The mapped root projects' own tests belong to the root jvmTest, not here."
    dependsOn(labModules.map { ":$it:test" })
}
```

```properties
# lab/gradle.properties (the root gradle.properties is not read by a build started with -p lab)
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.parallel=true
kotlin.code.style=official
```

Each lab module's `build.gradle.kts` follows the root modules' pattern: `alias(libs.plugins.kotlin.jvm)` (plus `alias(libs.plugins.kotlin.serialization)` where vector files are loaded), Java and Kotlin target 17, `tasks.test { useJUnitPlatform() }`, and `systemProperty("asom.repoRoot", rootProject.projectDir.parentFile.absolutePath)` on every test task, so tests find `fixtures/` and `lab/conformance/` without relying on the working directory.

### 2.4 The four isolation checks (every lab gate runs all four)

| # | Check | Command | Expected |
|---|---|---|---|
| 1 | The root settings never name the lab | `grep -cwE 'lab' settings.gradle.kts` | `0` (use `-w`: a plain substring search matches the word "available" in the root file) |
| 2 | **No Android tooling in the lab's classpath, with the SDK present** | on a runner where `ANDROID_HOME` is set: `./gradlew -p lab buildEnvironment \| grep -c com.android` | `0` |
| 3 | The lab build contains only the mapped pure-JVM projects and lab modules | `./gradlew -p lab projects \| grep -cE "':(app\|vault\|pairing\|storage\|ledger\|client\|client-cloud\|sample-client)'"` | `0` |
| 4 | The shipped tree is unchanged | **r4: replaced by R4-L-03** (the pinned-base comparison); the old command `git diff --exit-code -- core server …` cannot fail on a fresh checkout | as R4-L-03 |

**Root test-count check.** Before the first lab commit, and at every lab gate, run the root suite and count the executed tests from the JUnit XML reports:

```sh
./gradlew jvmTest --rerun-tasks
find core server -path '*/build/test-results/test/*.xml' -print0 \
  | xargs -0 grep -ho 'tests="[0-9]*"' | tr -dc '0-9\n' | awk '{s+=$1} END {print s}'
```

The number must equal the baseline recorded in `PROGRESS.md` at the lab's first gate. It must also equal the last v1 figure, if `PROGRESS.md` records one.

### 2.5 The composite-build fallback (only if check 2 ever fails)

**r4 (R4-L-04): superseded.** A failing check 2 is `BLOCKED(lab isolation: check 2 failed)`; no builder edits a root file. The text below is kept only as the option the owner would be asked to approve.

Project-directory mapping is the mechanism. If a builder cannot make it pass check 2, the **only** permitted alternative is a composite build (`includeBuild("..")` in `lab/settings.gradle.kts`, with dependencies declared by coordinates such as `"asystemofmodels.core:contract"` and `"asystemofmodels:server"`, which are Gradle's default group names for those paths; confirm them with `./gradlew -p lab :conformance-runner:dependencies`). It needs this **one-line change to the root `settings.gradle.kts`**, and no other root change:

```diff
-if (hasAndroidSdk) {
+if (hasAndroidSdk && System.getenv("ASOM_PURE_JVM") != "1") {
```

Every lab invocation then runs with `ASOM_PURE_JVM=1`. With the variable unset, the root build behaves exactly as before, which the root test-count check proves. **This is the only root-file change ever permitted for the lab** (design §3.6). If it is made, the diff, the reason (the failing check-2 output) and the unchanged root test count go into `PROGRESS.md` as a recorded AD-3 exception for the owner's ratification.

### 2.6 Byte-exact data

Add to `.gitattributes` (create the file if absent):

```
lab/conformance/** -text
lab/**/fixtures/** -text
lab/**/*.jsonl -text
```

Every byte-exact read and write in the lab uses explicit UTF-8 (`Charsets.UTF_8`) and `\n`, never the platform default. JDK 17 on Windows is not UTF-8 by default [AW20].

---

## 3. The conformance suite, its runner, and the v1 golden vectors (L0.1)

### 3.1 Layout

```
lab/conformance/
  VERSION                      # 0.2.0 (r0 seeds were 0.1.0)
  INDEX.json                   # [{ "path", "sha256", "family", "status" }], sorted by path; detects incomplete checkouts only
  README.md                    # pass rules, comparison rules, the oracle rule (§4.10), the evidence labels
  keys/TEST-ONLY-keys.json     # §4.5; every production verifier and pin import refuses these keys
  wire/    W00-constants.json  W01-echo-headers.json  W01b-one-record.json  W02-error-envelopes.json  W03-sse.json
           W04-pairing.json  W05-fingerprints.json  W06-frames.json  W07-live-state.json  W07p-presence.json
  manifest/ M01-jcs.json  M02-verify-accept.json  M03-verify-reject.json  M04-derive.json  M05-render.json
           M06-derivatives.json  M08-claim-tracker.json  schema/asom.manifest.1.schema.json  schema/asom.bench-public.1.schema.json
  router/  R01-hard-filter.json  R02-scoring.json  R03-ordering.json  R04-v1-pins.json  R05-failover.json  R06-reducers.json
  ledger/  L01-destination-sets.json  L02-frame-rows.json
  scenarios/ SC01.json  SC04.json  SC06.json  SC09.json
  history/r0/                  # the r0 seed files, unchanged, never run (§4.9)
```

### 3.2 Vector file envelope

Every vector file is one JSON object. Vector files are **not** signed documents, so they may use kotlinx-serialization for loading (§1.3).

```json
{
  "family": "W01",
  "confVersion": "0.2.0",
  "specRefs": ["ASOM_BUILD_BRIEF.md §5.4", "LAB_SPEC.md §3.5"],
  "vectors": [
    {
      "id": "W01-001",
      "origin": "hand",
      "status": "normative",
      "oracle": "self",
      "description": "Served-By only when both provider and model are known.",
      "input": { },
      "expect": { "ok": { } }
    }
  ]
}
```

| Field | Rule |
|---|---|
| `family` | one of `W00 W01 W01b W02 W03 W04 W05 W06 W07 W07p W08 M01 M02 M03 M04 M05 M06 M08 R01 R02 R03 R04 R05 R06 L01 L02` |
| `id` | `<family>-<suffix>`, suffix `[0-9]{3}` or a short name in `[A-Za-z]{2,12}` (for example `M05-MLP`, `W01b-reach`); unique across the suite |
| `origin` | `hand` or `generated`. A generated file changes only together with a `VERSION` bump and a reviewed diff |
| `status` | `normative` (blocks the gate), `proposed` (depends on an unruled owner decision or an unpinned lane; runs in a non-blocking lane and is reported), `illustrative` |
| `oracle` | `self` (produced by this project's own generator or reasoning), `independent` (agreed by an implementation whose author had no access to the generator source, §4.10), `external` (checked by a third-party tool such as OpenSSL, for crypto primitives only) |
| `expect` | exactly one of `{ "ok": <value> }` or `{ "reject": "<CODE>" }` |

`INDEX.json` sha256 values detect an incomplete checkout. They do not authenticate anything; authenticity comes from signed tags.

### 3.3 The runner (`:conformance-runner`)

- **JUnit mode (the gate).** One dynamic test per vector, grouped by family. After each family it prints `family <F>: <n> vectors, <p> pass, <f> fail, <s> proposed-skipped`, and fails when `n == 0`.
- **Lines mode (for lane diffs).** `./gradlew -p lab :conformance-runner:run --args='lines M01,M02,M03,M05,M06' --quiet` prints one line per vector, sorted by id: `<id> ok` or `<id> reject <CODE>`. This is the implementation's own verdict, not pass/fail, so two lanes can be diffed byte for byte (the Swift lane prints the same format, `PLATFORM_PLAN.md` §6).
- **Scope.** It runs every family whose module exists; a family whose module is not yet built is reported `not-implemented`, never `pass`.

### 3.4 W00: frozen constants

The runner compares each list below with the frozen code by reflection (`AsomHeaders`, `AsomErrorCode.entries`, `Egress.entries`, `CostBasis.entries`, `Policy.VIRTUAL_MODELS`, `Asom`). Any difference fails.

| Vector | Expected (exact) |
|---|---|
| W00-001 request headers | `X-Asom-Policy`, `X-Asom-Fallback`, `X-Asom-No-Train` |
| W00-002 echo headers | `X-Asom-Served-By`, `X-Asom-Egress`, `X-Asom-Cost-Est`, `X-Asom-Cost-Basis` |
| W00-003 error codes (name, HTTP, OpenAI type) | `NOT_PAIRED 401 authentication_error`; `TOKEN_REVOKED 401 authentication_error`; `NO_PROVIDER_KEY 503 server_error`; `MODEL_UNKNOWN 404 invalid_request_error`; `ALL_PROVIDERS_COOLING 503 server_error`; `LOCAL_ENGINE_ABSENT 501 server_error`; `UNSUPPORTED_BY_DRIVER 501 invalid_request_error` |
| W00-004 egress wire values | `local`, `cloud`, `catalogue`, `download` |
| W00-005 cost basis wire values | `usage`, `heuristic`, `none` |
| W00-006 virtual models | `auto`, `cheapest`, `fastest`, `best-reasoning`, `local-only` |
| W00-007 bind and port | `127.0.0.1`, `11435` |
| W00-100… (status `proposed`) | the mesh additions of design §8.3 (`peer:` Served-By form, `asom-peer`, `MESH_STREAM_INTERRUPTED`, the two `X-Asom-Failover` values), each tagged with its ruling decision; they flip to `normative` only after the owner rules it |

### 3.5 W01: echo headers from one `RouteRecord` (pure)

Input: the 13 `RouteRecord` fields, with `costEst` as a decimal string (R6). Expected: the header map; names compared case-insensitively.

| Vector | Input (fields that matter) | Expected |
|---|---|---|
| W01-001 | provider `openrouter`, model `llama-3.3-70b`, egress cloud, no cost | `Served-By: openrouter/llama-3.3-70b`, `Egress: cloud`; no cost headers |
| W01-002 | provider known, model null | no `Served-By`; `Egress` present |
| W01-003 | egress local, error path (status 501) | only `Egress: local` |
| W01-004 | cost `"1.25E-7"`, basis usage | `Cost-Est: 0.00000013`, `Cost-Basis: usage` |
| W01-005 | cost `"2.5E-8"`, basis heuristic | `Cost-Est: 0.00000003` |
| W01-006 | cost `"123.456789125"`, basis usage | `Cost-Est: 123.45678913` |
| W01-007 | cost `"1.25E-9"`, basis usage | `Cost-Est: 0`, `Cost-Basis: usage` |
| W01-008 | cost present, basis none | no cost headers |
| W01-009 | cost null, basis usage | no cost headers |
| W01-100…W01-299 (generated, `proposed`) | 200 costs drawn with seed 1 from [10⁻⁹, 10²], written as JDK 21 `Double.toString` strings | `formatUsd` output on JDK 21 |

The values in W01-004…W01-007 were confirmed on OpenJDK 21.0.10 (`platforms.md` §8.9). **W01-100…W01-299 become normative only after they pass on JDK 17, JDK 21, the packaged runtimes and ART.** `BigDecimal.valueOf(double)` goes through `Double.toString`, whose output changed in JDK 19, so a JDK 17 difference is a real v1 behaviour difference. Record it as a finding for the owner, never as a vector edit.

### 3.6 W01b: one record, two renderings, against the real `:server`

**Harness (normative).** In `:conformance-runner` tests:

1. Start `xyz.mdhv.asom.server.AsomServer(AsomServerConfig(port = 0, catalogue = { CatalogueParser.parse(File(repoRoot, "fixtures/catalogue.v1.json").readText()) }, tokens = InMemoryTokenRegistry().apply { issue("w-token", "w.caller"); issue("w-revoked", "w.revoked"); revoke("w-revoked") }, keys = InMemoryKeyProvider(<per vector>), drivers = { scriptedDriver }, ledger = InMemoryLedger(), cooldowns = CooldownRegistry(clock = { fixedNow }), latency = LatencyTracker()))`.
2. Call `start(wait = false)` and use `resolvedPort()`. The server binds `127.0.0.1` itself (`Asom.BIND_HOST`).
3. `scriptedDriver` is a lab `ProviderDriver`. It delegates to `FakeDriver` or replays the vector's scripted outcome: a JSON body with usage, a stream of byte chunks, or an error status.
4. Send the vector's HTTP request with `java.net.http.HttpClient` to `http://127.0.0.1:<port>`.

**Law checked by every W01b vector (Invariant 9):** let `row` be the `InMemoryLedger` row that the request appended. Then `row.toEchoHeaders()` equals the response's `X-Asom-*` headers exactly, and `row` equals the vector's expected fields (ignoring `ts` and `latencyMs`). **r4: restated by R4-L-09** (false on streams for frozen v1; the terminal row decides; W01b-004 appends two rows).

| Vector | Scenario | Expected (beyond the law) |
|---|---|---|
| W01b-001 | non-stream chat, FakeDriver, key present | 200; egress cloud; tokens from usage; basis usage |
| W01b-002 | stream chat with a usage chunk | 200; basis usage |
| W01b-003 | stream chat, `omitStreamUsage = true` | 200; basis heuristic |
| W01b-004 | first provider 503 (`failWith`), second serves | 200; `Served-By` = second provider; one row for the request |
| W01b-005 | `model: local-only` | 501 `LOCAL_ENGINE_ABSENT`; egress local; no `Served-By` |
| W01b-006 | unknown concrete model | 404 `MODEL_UNKNOWN`; egress local |
| W01b-007 | `failMidStream` after 2 chunks | the row written after the socket closes still equals the headers sent at stream start |
| **W01b-reach** (status `proposed`, lab types) | `LabRouteRecord`: the peer attempt receives the body, then SELF serves | header `X-Asom-Egress: peer`; the terminal row has `egress = peer` and `servedClass = local`; header value = row value (design §7.6; ruled by D3) |

### 3.7 W02: typed error envelopes, against the real `:server`

One vector per frozen code: `NOT_PAIRED` (no bearer), `TOKEN_REVOKED` (`w-revoked`), `NO_PROVIDER_KEY` (empty `InMemoryKeyProvider`), `MODEL_UNKNOWN`, `ALL_PROVIDERS_COOLING` (every serving provider cooling via `CooldownRegistry.recordFailure` at `fixedNow`), `LOCAL_ENGINE_ABSENT`, and `UNSUPPORTED_BY_DRIVER` (the scripted driver throws `AsomException(UNSUPPORTED_BY_DRIVER, …)`).

- **Expected:** the HTTP status; `error.code`; `error.type`. The `message` text is excluded from the comparison.
- **The key-leak law:** every provider key is set to `sk-LAB-SECRET-<provider>`, and that marker never appears in any response byte, header or ledger row.

### 3.8 W03: SSE bytes

- **Pass-through (server side).** The scripted driver emits the vector's chunks (base64). The HTTP response body bytes equal their concatenation exactly, whatever the chunking. This pins the v1 server's re-framing, which moves emission boundaries but never bytes.
- **Parse (reference parser).** The lab's SSE parser turns the same bytes into events. Rules: `\n`, `\r\n` and `\r` line endings; comment lines (`:`) ignored; multi-line `data:` joined with `\n`; `data: [DONE]` ends the stream; a final event without a terminating blank line is **dropped** and reported as `TRUNCATED`.
- **W03-001** (seed): reads `ZGF0YTog`, `eyJhIjoxfQoK`, `OiBrZWVwLWFsaXZlCgpkYXRhOiB7ImIi`, `OjJ9DQoNCmRhdGE6IFtET05FXQoK` produce `{"a":1}`, `{"b":2}`, done.
- **Further vectors:** a split inside a multi-byte UTF-8 sequence, a CR-only stream, and a stream without a final blank line.

### 3.9 R04: v1 router pins as data (against the real `Router`)

**Input:**
- the catalogue (the fixture path, or an inline catalogue for edge cases);
- `keysPresent` (provider ids);
- `latencyEwmaMs` (provider → decimal string, R6);
- `cooling` (provider ids cooled by `recordFailure` at `fixedNow`);
- the query `{model, policyHeader?, fallback[], noTrain}`.

**Expected:** the ordered list of `"<provider>/<model>"`, or `{ "reject": "<AsomErrorCode>" }`. The runner builds `Router(catalogue, keys, latency, cooldowns, Policy.AUTO, hasLocalEngine = false)` with a `LatencyTracker` preloaded from the map and a `CooldownRegistry` on a fixed clock.

**Minimum cases** (each already asserted in `RouterTest`/`RouterPropertyTest`; R04 turns them into data):
- unknown model → `MODEL_UNKNOWN`;
- no key → `NO_PROVIDER_KEY`;
- all cooling → `ALL_PROVIDERS_COOLING`;
- `local-only` → `LOCAL_ENGINE_ABSENT`;
- `cheapest`: blended-price order, unpriced last, ties by provider id then model id;
- `fastest`: EWMA ascending, unmeasured last;
- `best-reasoning`: rank ascending, unranked last;
- `auto`: the factor-2.0 band, in-band by price;
- `X-Asom-Fallback`: restricts and orders, and repeated ids are deduplicated;
- `noTrain` filters training providers;
- determinism under catalogue permutation.

**R04 is RL1's oracle** (§6.8).

### 3.10 `lab/tools/xcheck.py` (the Python cross-checker)

- **Python 3 standard library only** (no `pip`): P-256 arithmetic in pure Python for verification, `hashlib`, `base64`, `decimal`.
- **It checks:** M01 (JCS), the signature layer of M02/M03 (PAE, base64 strictness, 64-octet r‖s, ES256 verify), the fingerprints of W05, and W01's USD strings (`decimal.Decimal(repr(float(s))).quantize(Decimal('1E-8'), ROUND_HALF_UP)`, then zeros stripped and plain notation; Python's `repr` is shortest round-trip).
- **Output:** one line per family, `xcheck <family>: <n> agree, <d> disagree`. It exits non-zero on any disagreement.
- **It is written in the same session as the generators, so its agreement never clears the `self` oracle tag** (§4.10).

### 3.11 L0.1 gate

See §8.1.

---

## 4. The signed capability manifest (L0.2)

Detailed background: `manifest.md` (the r0 section spec) as amended by design §5. This section lists every rule a builder needs; `manifest.md` is cited only for tables that are copied unchanged.

### 4.1 Schema reuse and the r3 patch list

Copy `manifest-vectors/asom.manifest.1.schema.json` and `asom.bench-public.1.schema.json` to `lab/conformance/manifest/schema/`. The source is the design session's `manifest-vectors/` directory, committed with the design under `docs/design/mesh/manifest-vectors/`; if it is absent, write `BLOCKED(missing manifest-vectors)`. Then apply patches P1–P10 to the manifest schema. **The JSON Schema is documentation and a test oracle only. Step 11 of the verifier is a hand-written typed decoder that enforces the same rules plus the bounds of §4.6.**

| Patch | Change | Reason (design ref) |
|---|---|---|
| P1 | `body.audience` enum becomes `["own", "file"]` (remove `other`) | D20; §5.2 |
| P2 | `body.required` loses `seq`; add `if audience == "own" then required seq`, `if audience == "file" then seq forbidden` | §5.2 |
| P3 | `presentation`: for `own`, `issuedAtMs`, `expiresAtMs`, `challenge` (string or null) required; for `file`, only `issuedAtMs`, `additionalProperties: false`, `issuedAtMs` a multiple of 86,400,000 | §5.2 (file form), R2-OVERCLAIM-12 |
| P4 | `subject.keyStorage` enum adds `ephemeral` | §5.3 (per-export keys) |
| P5 | `device.os.family` enum adds `windows`, `ubuntu-touch` | D-E; §5.2 note |
| P6 | `body` gains required `bench`: an `asom.bench/1` document, **active lane only** (`benchmark.md` §13.2 schema) | §5.2 (one measurement source) |
| P7 | Forbidden at any depth of `body`: `derived`, `render`, `textSha256`, `field`, `custom` | §5.2; the r0 bench examples carried `derived`/`render` and are superseded |
| P8 | For `file`: `device.platformIds` and `device.os.securityPatch` forbidden; every `measuredAtMs` a multiple of 86,400,000; `conditions.batteryStartPermille`, `conditions.screenOn`, `conditions.socStartMilliC` forbidden | §5.8 S2 projection |
| P9 | Physical bounds: every `milliTokPerSec` 1…10⁹; `ttftMicros` ≤ 3.6×10⁹; byte fields ≤ 2⁵⁰; every `p50` ≥ 1 | §5.2 |
| P10 | `schemaMinor` stays `0` (nothing has shipped) | §5.2 |

### 4.2 Strict JSON and the JCS integer profile (`:json`)

**Values:** `JObject` (members in input order; names unique), `JArray`, `JString`, `JInt` (a `Long` within ±(2⁵³−1)), `JBool`, `JNull`. There is no floating-point type.

**Parse `(bytes) → JValue | Reject(code)`**, in this order of checks:

| Condition | Code |
|---|---|
| a leading BOM, or bytes that are not one JSON value | `MALFORMED_JSON` |
| invalid UTF-8 (overlong, surrogate code points encoded in UTF-8, truncated sequences) | `INVALID_UNICODE` |
| a `\u` escape producing a lone surrogate | `INVALID_UNICODE` |
| a number that is not `-?(0\|[1-9][0-9]*)`: fractions, exponents, `-0`, `NaN`, `Infinity` | `NON_INTEGER_NUMBER` |
| an integer outside ±(2⁵³−1) | `NUMBER_RANGE` |
| duplicate member names (compared after unescaping, as UTF-16 code-unit sequences) at any depth | `DUPLICATE_KEY` |
| nesting deeper than 16 (the top-level value is depth 1; each object or array adds 1) | `MALFORMED_JSON` |
| non-whitespace after the value | `TRAILING_DATA` |

Whitespace is space, tab, LF and CR only.

**Serialise `JCS(value) → bytes`** (RFC 8785, integer profile):
1. No whitespace.
2. Object members sorted by name, compared as arrays of UTF-16 code units (unsigned). Arrays keep their order.
3. Strings: `"` → `\"`, `\` → `\\`; U+0008, U+0009, U+000A, U+000C, U+000D → `\b \t \n \f \r`; other U+0000–U+001F → `\u00xx` with lowercase hex; everything else literal (including `/` and non-ASCII); no Unicode normalisation.
4. Integers in shortest decimal form.
5. `true`, `false`, `null`.

A code-point sort is the classic bug: M01-001's keys include U+1F600 (a surrogate pair) and U+FF21, which a code-point sort orders the wrong way round.

**Strict base64 (`b64either`):**
- Accept the standard or the URL-safe alphabet, padded or unpadded.
- Reject mixed alphabets, whitespace, padding in the wrong place, and non-zero unused trailing bits (all `ENCODING`).
- Producers emit standard alphabet with padding.
- Separately, `b64url` (no padding) is used for ids.

### 4.3 DSSE and the container

- `PAE(type, body) = "DSSEv1" + SP + LEN(type) + SP + type + SP + LEN(body) + SP + body`, where LEN is the decimal byte length without leading zeros. Example: type `application/vnd.asom.manifest.v1+json`, body `{"a":1}` → `DSSEv1 37 application/vnd.asom.manifest.v1+json 7 {"a":1}` (57 bytes).
- **payloadType registry (closed):**
  - `application/vnd.asom.manifest.v1+json` (the manifest);
  - `application/vnd.asom.key-rollover.v1+json` (reserved; rejected by this verifier).
  - Any other type → `PAYLOAD_TYPE_UNSUPPORTED`; a newer manifest major → `SCHEMA_MAJOR_UNKNOWN`.
- **Container** (the `MANIFEST` frame payload and the `.asom-manifest.json` file), emitted in JCS form:

```json
{"asomCapabilityManifest":1,"dsse":{"payload":"<base64 of the JCS payload bytes>","payloadType":"application/vnd.asom.manifest.v1+json","signatures":[{"keyid":"<nodeId of the signing key>","sig":"<base64 of 64-octet r||s>"}]},"evidence":[],"signer":{"spki":"<base64 of the 91-byte SPKI DER>"}}
```

- **Only `dsse.*` feeds signature verification.** `signer.spki` is used only in FILE mode (§4.6 step 7). Unknown container members are ignored and never passed to consumers. `keyid` is an unauthenticated hint.

### 4.4 Algorithm: ES256 through `java.security`

**ES256 (ECDSA P-256 with SHA-256) is the only algorithm.** The task that commissioned this spec suggested Ed25519 through `java.security`. That is **not** taken:
- the keys that must sign manifests live in the Secure Enclave, StrongBox or a TPM, and none of them holds Ed25519 keys [F06][IF16];
- the JDK supports both (`Ed25519` and `SHA256withECDSAinP1363Format` are JDK 17 standard names [F52]), so the choice turns on the hardware, not the JDK.

ML-DSA-65 is parked for a post-quantum major version.

```kotlin
// Sign (producer): the JDK emits raw r||s in P1363 form; normalise to low-S.
val sig = java.security.Signature.getInstance("SHA256withECDSAinP1363Format")
sig.initSign(privateKey)                  // an ECPrivateKey on secp256r1
sig.update(pae)
val rs = sig.sign()                       // exactly 64 bytes: r (32) || s (32), big-endian, leading zeros kept
val lowS = normaliseLowS(rs)              // if s > n/2 then s = n - s; r unchanged

// Verify (consumer), after the §4.6 length and range checks:
val v = java.security.Signature.getInstance("SHA256withECDSAinP1363Format")
v.initVerify(publicKey)
v.update(pae)
val ok = v.verify(rs64)                   // high-S is accepted (M02-105)
```

- `n = FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551`.
- **Before calling `verify`:** the signature is exactly 64 bytes (`SIGNATURE_ENCODING` otherwise), and `0 < r < n` and `0 < s < n` (`SIGNATURE_INVALID` otherwise).
- **A DER signature** (the output of `SHA256withECDSA`) is never accepted on the wire (M03-102). The lab's DER↔raw codec exists only for platforms whose APIs return DER; its vectors are in M01.

### 4.5 Key formats, identifiers and fingerprints

| Item | Definition |
|---|---|
| Public key on the wire | **SPKI DER, exactly 91 bytes**: the 26-byte prefix `3059301306072a8648ce3d020106082a8648ce3d030107034200` followed by `04`‖x(32)‖y(32). Any other length or prefix (a compressed point, a trailing byte, another curve) → `ALG_UNSUPPORTED`. The point must satisfy y² = x³ − 3x + b (mod p), checked with `BigInteger` before `KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))` |
| Private key at rest (T0 file, lab tests) | PKCS#8 DER (`PKCS8EncodedKeySpec`), file mode 0600 where the OS supports it |
| TEST-ONLY keys | built from `d_hex` with `ECPrivateKeySpec(BigInteger(d, 16), secp256r1Params)`, where `secp256r1Params` comes from `AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)` |
| `pin` | SHA-256 of the SPKI DER (32 bytes) |
| `nodeId` | base64url(pin), no padding (43 characters) |
| `nodeTag` | the first 16 characters of lowercase RFC 4648 base32 of `pin` (80 bits); for ledger rows and UI; never used for authorisation |
| Node display fingerprint (pairing, Peers tab, signer line for MESH) | `nodeTag` uppercased, in groups of four: `XWWD-3XQW-7TEB-MU27` |
| **Export fingerprint (FILE context)** | uppercase base32 (RFC 4648, no padding) of the **first 16 bytes** of `pin`: 26 characters, displayed in groups of 5-5-4-4-4-4, e.g. `XWWD3-XQW7T-EBMU-27ML-PG3C-BTVY`. Comparison: uppercase the input, delete `-` and spaces, compare the 26 characters in constant time. No other normalisation |

**Worked values (TEST-ONLY keys; recomputed for this spec):**

| Key | `nodeId` | `nodeTag` | export fingerprint |
|---|---|---|---|
| key1 | `vaw93hb8yBZTX2LebYgzri1pfOnBlRIILoBLiyK_eN4` | `xwwd3xqw7tebmu27` | `XWWD3-XQW7T-EBMU-27ML-PG3C-BTVY` |
| key2 | `idgZ8sjS2Fz_gsDBjfMRF3rH08Z6lwdee3U3OoPlVGE` | `rhmbt4wi2lmfz74c` | `RHMBT-4WI2L-MFZ7-4CYD-AY34-YRC4` |

- **Keys file.** `lab/conformance/keys/TEST-ONLY-keys.json` copies `manifest-vectors/TEST-ONLY-keys.json` and adds an `exportFingerprint` field per key. The r0 `fingerprint` field (16 characters) is the node display fingerprint, not the export fingerprint.
- **Deny-list.** Every verifier and pin import in **production mode** (`VerifyContext.productionKeys = true`) rejects these `nodeId`s with `TEST_ONLY_KEY` (vector M03-141). The conformance runner uses conformance mode.

### 4.6 The verifier (r3; normative order)

```text
VerifyContext(
  mode:               MESH | FILE
  pinnedSpki:         bytes?    // MESH: the SPKI the TLS session authenticated for this peer (never looked up by keyid)
  expectedChallenge:  bytes?    // MESH: the 32 bytes this requester sent in MANIFEST_REQ
  comparedFingerprint: string?  // FILE: what the user typed or scanned, or null if they did not compare
  compareMethod:      "qr" | "typed" | null
  rollback:           RollbackStore?        // MESH only, keyed (peer nodeId, audience)
  requiredTier:       A0 | A1 | A2          // A2 is never reachable in the lab (attestation deferred)
  confFloor:          semver; knownBadConf: set of semver
  productionKeys:     boolean
  nowMs:              integer (wall clock; used only in step 13)
)

verifyManifest(doc, ctx) -> Verified | Reject(code)
 1  |doc| > 524,288 bytes                                           -> TOO_LARGE
 2  C = strictParse(doc)                                             -> MALFORMED_JSON | INVALID_UNICODE | NON_INTEGER_NUMBER
                                                                        | NUMBER_RANGE | DUPLICATE_KEY | TRAILING_DATA
 3  C.asomCapabilityManifest == 1                                    else CONTAINER_VERSION_UNKNOWN
    C.dsse is an object with a string payloadType, a string payload and an array signatures   else CONTAINER_INVALID
 4  payloadType == "application/vnd.asom.manifest.v1+json"           else SCHEMA_MAJOR_UNKNOWN (a manifest.vN type, N > 1)
                                                                        | PAYLOAD_TYPE_UNSUPPORTED (anything else)
 5  |signatures| == 1                                                else SIGNATURE_COUNT
 6  P = b64either(payload); S = b64either(signatures[0].sig)         -> ENCODING
    |S| == 64                                                        else SIGNATURE_ENCODING
 7  key selection
    MESH: K = ctx.pinnedSpki
          keyid present and keyid != nodeId(K)                       -> KEY_NOT_PINNED
    FILE: K = C.signer.spki (base64)                                 absent -> KEY_NOT_PINNED
          keyid present and keyid != nodeId(K)                       -> KEY_NOT_PINNED
    strictSpki(K) and on-curve                                       else ALG_UNSUPPORTED
    ctx.productionKeys and nodeId(K) in TEST_ONLY                    -> TEST_ONLY_KEY
 7b FILE only: if ctx.comparedFingerprint != null:
          normalise(ctx.comparedFingerprint) == exportFingerprint(K) else FINGERPRINT_MISMATCH
          pin = PINNED_BY_FINGERPRINT(ctx.compareMethod)
       else pin = SIGNER_UNVERIFIED
    MESH: pin = PINNED
 8  0 < r < n and 0 < s < n, and ES256.verify(K, PAE(payloadType, P), S)   else SIGNATURE_INVALID
    ---- from here on only P (the verified bytes) is read ----
 9  O = strictParse(P)                                               -> (as step 2)
10  JCS(O) == P                                                      else NON_CANONICAL
11  O.schema == "asom.manifest/1"                                    else SCHEMA_MAJOR_UNKNOWN ("asom.manifest/N", N > 1)
                                                                        | SCHEMA_INVALID (anything else)
    typedDecode(O) with P1-P10 and code-point string lengths         else SCHEMA_INVALID
12  O.body.subject.nodeId == nodeId(K)                               else SUBJECT_KEY_MISMATCH
13  O.presentation.issuedAtMs <= ctx.nowMs + 300,000                 else NOT_YET_VALID
    if audience == "own":
        ctx.nowMs < expiresAtMs                                      else EXPIRED
        0 < expiresAtMs - issuedAtMs <= 600,000                      else TTL_INVALID
    (audience "file" has no expiry: it is a one-shot file, never a presentation)
14  MESH: challenge != null and constantTimeEq(challenge, ctx.expectedChallenge)   else NONCE_MISMATCH
    FILE: skipped
15  consistency(O)  (manifest.md §8.4, unchanged)                     else INCONSISTENT
15a the confVersion pinned in body.bench (its harness block) >= ctx.confFloor, not in ctx.knownBadConf
                                                                     else DERIVATION_MISMATCH
    JCS(results) == JCS(project(derive(bench)))  (bench-core, §5.2)  else DERIVATION_MISMATCH
15b MESH: audience == "own";  FILE: audience == "file"               else AUDIENCE_MISMATCH
15c evidence absent or an array of <= 2 objects; only the first of each known type is read; DER input <= 16 KiB,
    depth <= 8                                                       else CONTAINER_INVALID
16  MESH only: D = b64url(SHA-256(JCS(O.body))); prev = rollback[(nodeId(K), "own")]
        prev and O.body.seq < prev.seq                               -> ROLLBACK
        prev and O.body.seq == prev.seq and D != prev.bodyDigest     -> EQUIVOCATION
17  displayTier = A1 if subject.keyStorage in {strongbox, tee, secure-enclave, tpm} else A0   (a self-reported label)
    attestedTier = A0                                                (evidence unused; A2 deferred)   [r4, R4-L-12]
18  attestedTier >= ctx.requiredTier                                 else TIER_INSUFFICIENT          [r4, R4-L-12]
19  MESH: rollback[(nodeId(K), "own")] = (max(seq), D)   (after the caller commits; the library default is display-only)
    return Verified{ payloadBytes: P, obj: O, bodyDigest: D, pin, tier, unknownFields: count of members
                     the typed decoder did not recognise under schemaMinor rules }
```

- **Typed rejects.**
  - **Removed in r3:** `KEY_CHANGED` and the TOFU states. TOFU keyed by an unauthenticated `keyid` was illusory, and per-export keys make it moot.
  - **New in r3:** `FINGERPRINT_MISMATCH`, `TEST_ONLY_KEY`, `DERIVATION_MISMATCH`, `AUDIENCE_MISMATCH`.
  - All other codes and their meanings are `manifest.md` §8.3.
- **Display rule.** A human viewer may display a report rejected only at steps 13–16, with the reject shown in the verification block. A report failing any other step is never rendered as content.
- **Arithmetic.** Every multiplication in `consistency()`, `derive()` and the tracker uses `Math.multiplyExact`/`addExact`; an overflow → `INCONSISTENT`.

### 4.7 Signer and audience projections

```text
signPresentation(bodyOwn, audience, challenge?, key, nowMs):
  if audience == "own":                    // MESH, NIK-signed, answers one MANIFEST_REQ
      require challenge != null (32 bytes)
      presentation = { issuedAtMs: nowMs, expiresAtMs: nowMs + 600,000, challenge: b64url(challenge) }
      body = bodyOwn                        // includes seq
  if audience == "file":                   // export, signed by a fresh per-export key, never by the NIK
      key = newPerExportKey()               // KeyPairGenerator("EC") on secp256r1; keyStorage = "ephemeral"
      body = projectFile(bodyOwn)           // P8 removals, day-truncation, no seq
      presentation = { issuedAtMs: floor(nowMs / 86,400,000) * 86,400,000 }
  obj = { schema: "asom.manifest/1", schemaMinor: 0, body, presentation }
  P = JCS(obj); rs = normaliseLowS(sign(key, PAE(PT_V1, P)))
  c = container(P, rs, keyid = nodeId(key), signer.spki = spki(key))
  verifyManifest(c, ctx for the same audience, self-check)  -> any reject: do not send; typed error MANIFEST_UNAVAILABLE
  if audience == "file": discard the private key; return (c, exportFingerprint(key)) for the export screen
```

- **The `seq` rule (own only).** When `JCS(bodyOwn)` changes, `seq = max(stored + 1, nowMs / 1000)`, written durably (with `force`) before the first signature over it.
- **Projections** are the design §5.8 S2 list: `projectFile` removes `platformIds` and `securityPatch`; truncates every `measuredAtMs` and run time to the day; removes the battery level, screen-on and SoC-temperature conditions; and omits `seq`.
- **The public derivative** is `manifest.md` §12 as amended by design §5.9:
  - an allow-list projection, with 2-significant-digit quantisation and month-granular dates;
  - results whose `fileSha256` is not in the held catalogue are dropped;
  - `engine.commit` and harness versions are emitted only if on the release allow-list, else `custom`;
  - never `field`, `custom`, `osBuild`, `gpuDriver` or `fingerprint`.

### 4.8 The report generator (renderers)

`M05(parse(P), vr)` renders `asom.manifest-text/1` in three parts:
- the header;
- the verification block, computed by the viewer from `vr` and never taken from the payload (LM-6);
- the `asom.text/1` body, rendered by `:bench-core` from `body.bench` (`benchmark.md` §12; layout §12.6; editorial constants §12.3, PROVISIONAL until the owner rules D18).

**Output bytes:** ASCII 0x20–0x7E plus LF. Times are UTC with no locale. Number formats follow `manifest.md` §14.2 (integer arithmetic only).

**Wording that changed in r3** (replacing the `pin` row of `manifest.md` §14.2):

| Context | Signer line | "Signer key:" line | Freshness line |
|---|---|---|---|
| MESH (`PINNED`) | `Signer: node <XWWD-3XQW-7TEB-MU27>` | `matches the key you paired with.` | `signed for your request.` |
| FILE, compared (`PINNED_BY_FINGERPRINT`) | `Signer: key <XWWD3-XQW7T-EBMU-27ML-PG3C-BTVY>` | `matches the fingerprint you compared (typed).` or `(scanned).` | `not applicable: an exported file answers no request.` |
| FILE, not compared (`SIGNER_UNVERIFIED`) | as above | `signed, but the signer is unverified: anyone could have made this key.` | as above |
| own device (`OWN`) | `Signer: this device` | `this device's own key.` | as MESH |

- **Header line 3.** MESH: `Report <seq>, signed <YYYY-MM-DD HH:MM> UTC, valid until <…> UTC`. FILE: `Report exported <YYYY-MM-DD> (day only)`.
- **The last line of the verification block is always:** `Not proven: that the measurements were honest or typical, that the device model is true, or that the benchmark software was unmodified.`
- **Exported plain text never contains a verification block.** No signed `.txt` exists; a recipient renders the JSON in their own verifier.
- **The MLPerf wording rule** (design §6.1; D18; law LM-9):
  - no rendering ever contains the string `MLPerf-comparable`;
  - the descriptive note exists as a constant with `MLPERF_NOTE_ENABLED = false`;
  - while it is `false` the note never renders, and it never renders on a default (Q1) run whatever the flag;
  - vector `M05-MLP` asserts all three.
- **Laws:**
  - **LM-3:** one source for text, digest and public derivative;
  - **LM-4:** the producer renders only after sign → verify → parse;
  - **LM-5:** `M05(parse(JCS(o)), vr) == M05(o, vr)`, as a property test over generated bodies;
  - **LM-6:** the verification block is computed by the viewer;
  - **LM-9:** the MLPerf wording rule above.

### 4.9 Vector list M01–M08: kept, retired, regenerated, new

**Why most r0 vectors are regenerated.** r3 changed the payload shape: `body.bench` is added, and `derived` and `render` are gone. Every r0 M02/M03/M05/M06 document therefore fails the r3 decoder, and each vector's **semantics** are regenerated into a new document under `confVersion 0.2.0`.
- The r0 files move unchanged to `lab/conformance/history/r0/` and are never run.
- The generator is a lab tool (`:conformance-runner` test fixtures or a `lab/tools/gen_vectors.kts`) that signs with the TEST-ONLY keys. Its outputs are `origin: generated`, `oracle: self`.
- **The high-S twin (M02-105)** is produced by replacing s with n − s in the generated low-S signature.
- **The OpenSSL cross-check is kept.** The run in `manifest-vectors/crosscheck.out` becomes an `external` oracle for the signature layer only: an `openssl dgst -sha256 -verify` of each accept vector's PAE with its DER-converted signature.

| Id | Status in r3 | Input (short) | Expected |
|---|---|---|---|
| M01-001…M01-012 | kept (r0 seeds) + extended | JCS inputs incl. the UTF-16 order trap, control characters, `/` unescaped, NFD kept, ±(2⁵³−1) | canonical bytes |
| M01-101…M01-110 | kept semantics | parse rejects: float, exponent, `-0`, duplicate, lone surrogate, > 2⁵³−1, depth 17, BOM, invalid UTF-8, trailing data | the §4.2 code |
| M01-201…M01-206 | **new** | DER↔raw codec: leading-zero r, leading-zero s, 33-byte DER integers, high-S → low-S normalisation (`(r, s)` → `(r, n − s)`), a non-minimal DER length (rejected), raw of 63/65 bytes (rejected) | raw bytes or `SIGNATURE_ENCODING` |
| M02-101 | regenerated | MESH, key1 pinned, challenge1, own audience | `ok {pin: PINNED, tier: A1, seq, bodyDigest}` |
| M02-102, M02-103 | **retired** (TOFU) | — | — |
| M02-104 | regenerated | `nowMs == expiresAtMs − 1` | ok |
| M02-105 | regenerated | high-S twin of M02-101 | ok, same `bodyDigest` |
| M02-106 | regenerated | URL-safe unpadded base64 | ok |
| M02-107 | regenerated | one unknown additive member | ok, `unknownFields: 1` |
| M02-108 | regenerated | rollback store holds seq − 1 | ok |
| M02-109 | regenerated | rollback holds the same seq and digest | ok |
| M02-110 | **new** | FILE, per-export key, not compared | ok `{pin: SIGNER_UNVERIFIED, tier: A0}` |
| M02-111 | **new** | FILE, fingerprint typed exactly | ok `{pin: PINNED_BY_FINGERPRINT(typed)}` |
| M02-112 | **new** | FILE, fingerprint scanned, lowercase with spaces | ok `{pin: PINNED_BY_FINGERPRINT(qr)}` |
| M02-113 | **new** | `device.model` of exactly 96 astral characters | ok |
| M02-114 | **new** | every rate 10⁹, bytes 2⁵⁰ (the maxima) | ok |
| M03-101…M03-121, M03-123…M03-126, M03-128 | regenerated, same meaning and code as r0 (`manifest.md` §16.2) | — | as r0 |
| M03-103 | regenerated | MESH pinned key1; document signed by key2 (keyid = key2) | `KEY_NOT_PINNED` |
| M03-122 | **retired** (TOFU `KEY_CHANGED`) | — | — |
| M03-127 | **relabelled** | FILE, no `signer.spki` | `KEY_NOT_PINNED` |
| M03-129 | **new** | FILE, fingerprint of key2 compared against a key1 file | `FINGERPRINT_MISMATCH` |
| M03-130 | **new** | `results` edited to disagree with `derive(bench)`, re-signed | `DERIVATION_MISMATCH` |
| M03-131 | **new** | `confVersion` below the floor | `DERIVATION_MISMATCH` |
| M03-132 | **new** | own-audience document verified in FILE mode | `AUDIENCE_MISMATCH` |
| M03-133 | **new** | three evidence items | `CONTAINER_INVALID` |
| M03-134 | **new** | file audience carrying `seq` | `SCHEMA_INVALID` |
| M03-135 | **new** | file audience, `issuedAtMs` not day-truncated | `SCHEMA_INVALID` |
| M03-136 | **new** | compressed-point SPKI (33-byte point) | `ALG_UNSUPPORTED` |
| M03-137 | **new** | valid SPKI plus one trailing byte | `ALG_UNSUPPORTED` |
| M03-138 | **new** | `device.model` of 97 astral characters | `SCHEMA_INVALID` |
| M03-139 | **new** | a rate of 0 | `SCHEMA_INVALID` |
| M03-140 | **new** | a rate of 10⁹ + 1 | `SCHEMA_INVALID` |
| M03-141 | **new** | production mode, key1 | `TEST_ONLY_KEY` |
| M03-142 | **new** | a `derived` member inside `body.bench` | `SCHEMA_INVALID` |
| M03-143 | **new** | a file-audience document presented in MESH mode | `NONCE_MISMATCH` (step 14 runs before 15b) |
| M04-* | `benchmark.md` §9.5 seeds M04-001…M04-008 kept; new drift and partial-run vectors | raw samples | derived values and confidence |
| M05-101, M05-102 | regenerated from M02-101 and M02-107 | — | byte-exact text |
| M05-103, M05-104 | **new** | M02-110 (not compared), M02-111 (typed) | byte-exact text with the r3 wording |
| M05-105 | **new** | a file export with the passive lane absent | text says "not measured" per `benchmark.md` §12 |
| M05-MLP | **new** | an L1 run with `MLPERF_NOTE_ENABLED = false`; a Q1 run with it `true` | no `MLPerf-comparable` in either; no note in either |
| M06-101 | regenerated | public derivative of M02-101 | exact JCS bytes |
| M06-102 | kept (pure q2 arithmetic) | q2 table | as r0 |
| M06-103 | **new** | a result whose `fileSha256` is not in the catalogue | dropped |
| M06-104 | **new** | `engine.commit` not on the allow-list | `custom` |
| M06-file-001…004 | **new** | own body → file body | exact JCS of the file projection |
| M07-* | **not built** | A2 attestation deferred (design §5.5) | — |
| M08-* | **new** | the claim tracker | §6.6 |

### 4.10 Oracle independence (normative; R2-OVERCLAIM-8)

- **The spec text is the oracle.** Every vector the lab's generator produced carries `"oracle": "self"`.
- **What clears the tag.** A vector moves to `"oracle": "independent"` only when an implementation **whose author had no access to the generator source** produces the same verdict and output. The obvious candidate is the Swift lane (`PLATFORM_PLAN.md` §6), if its author works from this spec's prose. The author must be a different session or person, and `PROGRESS.md` records who, when, and which families agreed.
- **What does not clear it:** the Kotlin verifier and `xcheck.py`, written in the same session as the generator. Their agreement shows consistency, not independent reading.
- **Gate wording.** A lab gate may say "M01–M03 green, self-oracled". It may not say "independently verified" until the recorded agreement exists.

---

## 5. Benchmark core (L0.3; `:bench-core`)

Spec: `benchmark.md` §2.2 (public surface), §5 (run plans), §6 (thermal protocol), §9 (M04 statistics), §10 (active and passive combination), §11 (governors), §12 (plain text), §13 (`asom.bench/1`), as amended by design §6.

| Deliverable | Rule | Vectors |
|---|---|---|
| `asom.bench/1` model and typed decoder | integers only; the active lane only in manifests; `field[]` and `custom[]` never leave the device | M04 inputs |
| `derive(bench)` (M04) | `benchmark.md` §9: floor division; lower median for rates, upper for durations; MAD outliers (4449/1000 scaled); confidence classes and caps; checked arithmetic | M04-001…008 + drift + partial runs |
| `project(derive(bench))` → result rows | exactly the manifest's `results` shape; step 15a compares JCS bytes | used by M02/M03 |
| `asom.text/1` renderer | `benchmark.md` §12; no MLPerf label (§4.8) | M05 |
| run-plan interpreter, governor FSM with fakes, executor traces | `benchmark.md` §5.4, §11.4 | executor-trace vectors |
| **bench-set pins** | **Q1** (Qwen3, Apache-2.0): copy `benchmark.md` §4.2's table as data with `status: proposed` until the owner confirms each sha256 [A17]. **L1** (Llama): a separate table that is loaded only when a D18 ruling flag is set; it is never a default | a vector proving L1 is absent from defaults |
| **bytes-per-token table** for §6.6 | `bptPermille` and `bptCapPermille` per `fileSha256`, from the owner's reference CPU run (median, and p99 × 1.25); until then the class defaults 4000 and 8000, `PROVISIONAL` [A37] | M08 |
| MLPerf metric mapping | a typed placeholder `MlperfMetricMapping.UNPINNED`; nothing uses it until spike S-B1 pins the formulas | M05-MLP |

**No engine code.** No model file is downloaded or read by the lab. A test that needs a model uses the `FakeEngine` of the executor traces.

---

## 6. Mesh router core, claim tracker and simulator (L0.6)

Background: `router.md` §2–§13 (the r0 section), as amended by design §7 and §5.7. **Where this section differs from `router.md`, this section is the r3 spec.** r2 and r3 removed live-state fields that `router.md` used: `user.active`, `busyForMs`, `engine.loaded`, `queue.estStartS`, the exact battery percentage, `forecastPermille`, `headroomPermille`, `memory.availBytes256M` and `availability.reason`. The formulas below no longer read them. For **SELF**, local knowledge (its own presence, busy time, loaded models, free memory) is still used; it never goes on the wire.

### 6.1 Interfaces (`xyz.mdhv.asom.lab.router`)

```kotlin
enum class Tier { SELF, PEER, CLOUD }                          // ordinal is part of the total order
enum class DeviceClass { PHONE, TABLET, HANDHELD, LAPTOP, DESKTOP, SBC }
enum class PeerPath { LAN, OVERLAY }
enum class Freshness { FRESH, WARM, STALE, EXPIRED }
enum class Fsm { OFF, ARMED, SERVING, DRAINING }
enum class Governor { RUN, QUEUE, HOLD }
enum class BatteryBand { GE80, B50_79, B20_49, LT20 }           // wire: "ge80" | "50-79" | "20-49" | "lt20" | null
enum class ClaimState { LOCAL_MEASURED, UNVERIFIED, CORROBORATED, WEAK, DISCREPANT }

data class FileKey(val modelId: String, val fileSha256: String, val quant: String?, val fileBytes: Long, val catalogueRank: Int?)
data class ClaimKey(val nodeId: String, val fileSha256: String, val backend: String)

data class PerfPrior(                                           // from a verified manifest (peers) or local calibration (SELF)
    val decodeAt: List<Pair<Int, Long>>,                         // (contextTokens, milliTok/s), 1..4 points, ascending
    val prefillMilliTokPerSec: Long, val ttft0Ms: Long, val steadyMilliTokPerSec: Long,
    val throttleOnsetMs: Long?, val powerMilliW: Long?, val kvBytesPerToken: Long, val peakProcessBytes: Long,
    val flags: Set<String>,                                      // "numerics-fail" makes the (file, backend) ineligible
)

data class LiveState(                                            // asom.state/1 as parsed (§7.2 STATE); integers and enums only
    val seq: Long, val sampledAgeMs: Long, val fsm: Fsm,
    val powerSource: String, val charging: Boolean, val batteryBand: BatteryBand?,
    val thermalBand: Int, val governor: Governor,                // band 0 = codes 0-1, 1 = code 2, 2 = codes >= 3
    val backend: String, val commit: String, val held: Set<String>,
    val queueBucket: Int,                                        // 0 | 1 | 2 (= 2 or more)
    val manifestSeq: Long?, val manifestDigest: String?,
)

data class SelfSituation(                                        // LOCAL ONLY; never serialised to a peer
    val batteryPermille: Int?, val charging: Boolean, val onBattery: Boolean, val saver: Boolean,
    val batteryDesignMilliWh: Long?, val thermalCode: Int, val governor: Governor,
    val availBytes: Long?, val loaded: Set<String>, val userActive: Boolean, val busyForMs: Long,
)

data class LinkStats(val rttMs: Long, val kbps: Long, val path: PeerPath, val metered: Boolean,
                     val sessionWarm: Boolean, val samples: Int)
data class BreakerView(val coolingUntilMonoMs: Long?, val declineBackoffUntilMonoMs: Long?)
data class PeerLimits(val maxConcurrent: Int, val maxBodyBytes: Long, val maxTokens: Int, val rpm: Int, val idleUnloadMs: Long)
data class PeerRowView(val paired: Boolean, val routeEnabled: Boolean, val inferGrantedToMe: Boolean,
                       val requireCharging: Boolean, val limits: PeerLimits)

data class NodeView(
    val nodeId: String, val nodeTag: String, val tier: Tier, val deviceClass: DeviceClass,
    val peer: PeerRowView?,                                      // null for SELF
    val files: List<FileKey>, val priors: Map<ClaimKey, PerfPrior>,
    val self: SelfSituation?,                                    // SELF only
    val state: LiveState?, val stateRxMonoMs: Long?,             // PEER only
    val sessionOpen: Boolean, val goawaySeen: Boolean,
    val link: LinkStats?, val breaker: BreakerView,
    val lastSameFileMonoMs: Map<String, Long>,                   // requester-held: when MY last attempt on this peer used this file
    val ownReservationsMs: Long,                                 // requester-held: my in-flight work on this peer (§9 of router.md, decayed)
)

data class AppPolicy(val pkg: String, val meshAllowed: Boolean, val cloudBanned: Boolean, val deviceOnly: Boolean,
                     val allowMeshOnMetered: Boolean, val neverCloudWhenDevicesCanAnswer: Boolean)

data class MeshQuery(
    val v1: xyz.mdhv.asom.routing.RouteQuery,                    // the frozen v1 query, untouched
    val op: String /* chat | completions | embeddings */, val stream: Boolean,
    val promptTokens: Int, val promptBytes: Long, val maxTokensCap: Int?,   // maxTokensCap only if D11(a) is ruled
    val app: AppPolicy, val deadlineMs: Long,
)

data class MeshSnapshot(
    val nowMonoMs: Long, val wallNowMs: Long, val meshGlobalOn: Boolean,
    val self: NodeView, val peers: List<NodeView>,
    val cloud: FrozenCloudView,                                  // catalogue, keys present, cooldown deadlines, latency EWMAs (§6.7)
    val tracker: Map<ClaimKey, TrackerState>,                    // §6.6
    val caps: Map<ClaimKey, CapCounter>,                         // §6.7
    val appEwmaOut: Map<String, Int>,                            // per-app output-length EWMA (tokens)
    val config: MeshConfig,                                      // §6.4 parameters
)

class MeshRouter { fun plan(q: MeshQuery, s: MeshSnapshot): MeshPlan }   // pure: no I/O, no clock, no hash-order iteration
data class MeshPlan(val attempts: List<PlannedAttempt>, val excluded: List<Exclusion>, val capDelta: CapDelta)
data class PlannedAttempt(val tier: Tier, val nodeId: String?, val file: FileKey?, val cloud: xyz.mdhv.asom.routing.Candidate?,
                          val estimate: Estimate?, val score: ScoreBreakdown?, val usable: Boolean,
                          val probeOnly: Boolean, val reason: String)
```

- **`plan()` rules.** It reads no clock (the snapshot carries `nowMonoMs` and `wallNowMs`), performs no I/O, and iterates only sorted collections in decision paths.
- **Reducers.** Everything a peer says enters through two pure reducers: `LiveStateCache.onState/onPiggyback` (§6.5) and `ClaimTracker.onObservation` (§6.6).

### 6.2 The destination set and the candidate universe

`P ⊆ {T, O, C}` (this device, own paired peer, cloud under the user's key). Start from all three and intersect with every rule (design §8.5; `contract.md` §5.2 without the `X` rows):

| Rule | Effect |
|---|---|
| global "use my devices" off | remove `O` |
| app mesh off (`meshAllowed = false`; the default for apps paired after the switch) | remove `O` |
| app cloud ban (the column AD-1 pulled into mesh-1) | remove `C` |
| app marked device-only | `P ∩ {T}` |
| `X-Asom-Policy: local-only` or `model: local-only` | `P ∩ {T}` |
| `X-Asom-Fallback` present | `P ∩ {C}`, restricted to the listed providers (v1 meaning) |
| `X-Asom-No-Train` | no effect on `P`; filters inside `C` exactly as v1 |

**Universe:**
- SELF if `T ∈ P` and SELF has an engine;
- every peer if `O ∈ P`;
- the v1 cloud plan if `C ∈ P`.

A concrete model id matches files with `modelId` equal to it. A virtual selector matches every file whose kind fits `op` (a rank floor, if the user set one, applies). Sort by `nodeId`, then `fileSha256`. Vectors: `L01-destination-sets.json` (the `contract.md` §5.2 decision table minus `X`, plus the r3 defaults).

### 6.3 Hard filters (R01; the first failing row is recorded)

| Code | Applies to | Excluded when |
|---|---|---|
| `F1_ELIGIBILITY` | peer | `O ∉ P`, or the peer row is re-read and denies (checked again immediately before each intent row and before `INFER_BODY`) |
| `F2_NOT_PAIRED` | peer | registry status is not PAIRED |
| `F3_NO_SCOPE` | peer | the peer did not grant this node `infer` (from `HELLO_ACK.granted`) |
| `F4_MODEL` | all | file not in `held` (peer) or not present (SELF); or `numerics-fail` for (file, backend) |
| `F5_CONTEXT` | all | `P + N > min(model context, node max context)` |
| `F6_MEMORY` | **SELF only** | file not loaded and `availBytes < fileBytes + kvBytesPerToken × (P + N) + 268,435,456`. Peers have no memory field on the wire; the lender decides at offer time |
| `F7_BODY` | peer | `promptBytes > limits.maxBodyBytes` or `N > limits.maxTokens` |
| `F8_CLAIM` | all | no usable prior (a rate ≤ 0, or no prior and no class default), or the tracker marks the memory claim DISCREPANT |
| `F9_AVAILABILITY` | peer | `fsm ≠ SERVING` (skipped when EXPIRED) |
| `F10_THERMAL` | all | peer: `thermalBand == 2` or governor HOLD (skipped when EXPIRED); SELF: thermal code ≥ 3 or governor HOLD (SELF with QUEUE is **not** excluded) |
| `F11_POWER` | peer | `powerSource == "battery"` and not charging and (`requireCharging` or `batteryBand ∈ {20-49, lt20}`) (skipped when EXPIRED) |
| `F13_METERED` | peer | this node's path to the peer is metered and the app does not allow it |
| `F14_BREAKER` | peer | `nowMonoMs < coolingUntilMonoMs` |
| `F15_DECLINE_BACKOFF` | peer | `nowMonoMs < declineBackoffUntilMonoMs` |
| `F16_EMBED_IDENTITY` | all | `op == embeddings` and the file differs from the request's embedding identity |

`F12_USER_ACTIVE` is **removed** in r3: the requester has no presence field. A lender that is in use declines or drains.

### 6.4 Estimator and score (integers; floor division unless `ceilDiv`; saturating at 2⁵³ − 1)

Notation: `P = promptTokens`, `B = promptBytes`, `N` = E0, `rtt` and `kbps` from `link`. All times are in ms, rates in milliTok/s, energy in mJ, battery in ‰.

```text
E0  N          = clamp(q.maxTokensCap ?: s.appEwmaOut[app] ?: 256, 1, 32_768)
E1  netMs      = SELF ? 0 : rtt + ceilDiv(B * 8, kbps) + (sessionWarm ? 0 : 3 * rtt + handshakeExtraMs)
                 // cold path: TCP + TLS 1.3 mutual handshake + HELLO/HELLO_ACK before the offer (design §7.4, E1)
E2  loadMs     = SELF : (f.sha in self.loaded ? 0 : ceilDiv(f.fileBytes, loadBytesPerMs(class)))
                 PEER : (nowMonoMs - lastSameFileMonoMs[f.sha] <= limits.idleUnloadMs ? 0
                         : ceilDiv(f.fileBytes, loadBytesPerMs(class)))          // requester-held only (pessimistic)
E3  queueMs    = SELF : localQueueMs  (v2 single-flight queue, local knowledge)
                 PEER : max(ownReservationsMs, queueBucketMs[state.queueBucket])
E4  preEff     = trackedPrefill(k)           // §6.6 "use for placement"; SELF: the local calibration (LOCAL_MEASURED)
E5  prefillMs  = ceilDiv(P * 1_000_000, preEff) + prior.ttft0Ms
E6  decEff     = trackedDecode(k) scaled by context: decodeAt(P) / decodeAt(512), piecewise linear, clamped to the curve's ends
E7  decodeMs   = thermalAwareDecode(N - 1, decEff, trackedSteady(k), prior.throttleOnsetMs,
                                    busyMs = SELF ? self.busyForMs : 0, queueMs, prefillMs,
                                    hot = SELF ? (self.thermalCode >= 2) : (state.thermalBand >= 1))
E8  ttftMs     = netMs + loadMs + queueMs + prefillMs + (SELF ? 0 : ceilDiv(rtt, 2))
E10 totalMs    = ttftMs + decodeMs
E11 energyMilliJ         = powerMilliW(node) * (prefillMs + decodeMs) / 1000
E12 batteryUsedPermille  = onBatteryNotCharging(node) ? ceilDiv(E11 * 1000, batteryDesignMilliWh(node) * 3600) : 0

thermalAwareDecode(m, dec, steady, onsetMs, busyMs, queueMs, prefillMs, hot):
    if m <= 0: return 0
    already = busyMs + queueMs + prefillMs
    if hot or (onsetMs != null and already >= onsetMs): return ceilDiv(m * 1_000_000, min(dec, steady))
    if onsetMs == null: return ceilDiv(m * 1_000_000, dec)
    coolMs = onsetMs - already; tokCool = coolMs * dec / 1_000_000
    if m <= tokCool: return ceilDiv(m * 1_000_000, dec)
    return coolMs + ceilDiv((m - tokCool) * 1_000_000, steady)
```

**Score terms** (lower is better; each is in milliseconds-equivalent and appears in `routeDetail`):

| Term | Formula | Unit / weight |
|---|---|---|
| S1 time | `totalMs + (stream ? ttftMs : 0)` | ms |
| S2 battery | `E12 × msPerBatteryPermille × lowBatteryMult / 1000`; `lowBatteryMult` = 1000 (SELF ≥ 500‰; peer band `ge80` or `50-79`), 2000 (200–499‰; `20-49`), 4000 (< 200‰; `lt20`); 0 on mains or while charging | ms per ‰ of battery |
| S3 heat | only for `PHONE`, `TABLET`, `HANDHELD`, and only when hot (SELF thermal code ≥ 1; peer band ≥ 1): `(prefillMs + decodeMs) × heatPermille / 1000`, where `heatPermille` = 500 for SELF with `userActive`, else 250 (peers are always 250: no presence field) | ‰ of active time |
| S4 locality | SELF 0; PEER `peerBiasMs` | ms |
| S5 quality | `quantPenaltyMs[quant]`; for a virtual selector add `(rank − bestRankInSet) × msPerRankStep` (unranked = best + 10) | ms |
| S6 uncertainty | STALE: `totalMs × 250 / 1000`; EXPIRED: `totalMs × 500 / 1000`; else 0 | ms |
| **S** | `S1 + S2 + S3 + S4 + S5 + S6` (saturating) | ms |

**Usable** (for `auto`, peers only; SELF keeps its v2 position): `decEff ≥ minDecodeMilliTokPerSec ∧ ttftMs ≤ maxTtftMs ∧ totalMs ≤ q.deadlineMs`.

**Parameters** (`MeshConfig`; every value `PROVISIONAL` [RA12]; the laws in §6.8 are normative, the numbers are not):

| Parameter | Default | Unit |
|---|---|---|
| `peerBiasMs` | 1,000 | ms |
| `msPerBatteryPermille` | 2,000 | ms per ‰ |
| `heatPermille` (SELF user active / otherwise) | 500 / 250 | ‰ |
| `minDecodeMilliTokPerSec` | 4,000 | milliTok/s |
| `maxTtftMs` | 20,000 | ms |
| default `deadlineMs` | 120,000 | ms |
| `handshakeExtraMs` | 40 | ms |
| `loadBytesPerMs` | PHONE 500,000; TABLET 500,000; HANDHELD 1,000,000; SBC 1,000,000; LAPTOP 2,000,000; DESKTOP 2,000,000 | bytes/ms |
| path `kbps` default | LAN 100,000; OVERLAY 20,000 | kbit/s |
| `powerMilliW` class default | PHONE 5,000; TABLET 7,000; HANDHELD 15,000; LAPTOP 30,000; DESKTOP 150,000; SBC 8,000 | mW |
| `queueBucketMs` | [0, 10,000, 30,000] for buckets 0, 1, 2 | ms |
| `quantPenaltyMs` | F16/BF16/Q8_0 0; Q6_K 200; Q5_K_M 400; Q4_K_M 800; Q4_0 1,000; Q3_K_M 2,500; Q2_K 5,000; unknown 1,000 | ms |
| `msPerRankStep` | 5,000 | ms |
| `cloudDecodePriorMilliTokPerSec` | 50,000 | milliTok/s |
| `maxAttempts` | 6 (at most 3 peer attempts) | count |
| `offerTimeoutMs` / `headTimeoutMs` | 2,000 / `max(5,000, 2 × ttftMs)` | ms |
| breaker | the v1 `CooldownRegistry` curve (30 s doubling to 15 min), peer transport failures capped at 30 s then a half-open offer-only probe | — |

**Bounds** (RL20): `P ≤ 2²⁰`, `N ≤ 2¹⁵`, rates between 1 and 10⁹, bytes ≤ 2⁴⁰, times ≤ 2⁴⁰. The largest product is `P × 10⁶`.

**Worked example R02-r3-001** (computed for this spec with the formulas above; `oracle: self`; all inputs invented):
- **Query:** `P = 500`, `B = 2,000`, `N = 300` (cap 300), stream.
- **SELF (phone):**
  - situation: battery 600‰, not charging, thermal code 1, user active, model loaded;
  - prior: prefill 30,000, decode 5,000, `ttft0` 200 ms, steady 4,000, onset 180,000 ms;
  - power: 5,000 mW, battery 19,000 mWh.
- **PEER (Deck, `HANDHELD`):**
  - state and link: FRESH, AC, band 0, RUN, bucket 0, LAN RTT 10 ms, 100,000 kbit/s, warm session, the requester's last attempt used this file inside `idleUnloadMs`;
  - claim: prefill 60,000, decode 12,000, `ttft0` 300, no onset;
  - tracker: n = 0, so `prior' = claim × 700 / 1000` (§6.6; `capRef` not binding): prefill 42,000, decode 8,400.

| | E1 | E5 | E7 | E8 ttft | E10 total | S1 | S2 | S3 | S4 | S5 | **S** |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PEER Deck | 11 | 12,205 | 35,596 | 12,221 | 47,817 | 60,038 | 0 | 0 | 1,000 | 800 | **61,838** |
| SELF phone | 0 | 16,867 | 59,800 | 16,867 | 76,667 | 93,534 | 12,000 (E11 383,335 mJ; E12 6‰) | 38,333 | 0 | 800 | **144,667** |

The Deck wins. Term differences (runner-up minus winner): time 33,496; battery 12,000; heat 38,333; locality −1,000. The dominant term is **heat**, so the ledger reason is `peer:best-score/heat`.

### 6.5 Live state, staleness and the presence laws (W07, W07p)

**Staleness** (requester's monotonic clock only):
- `ageMs = (nowMonoMs − stateRxMonoMs) + min(state.sampledAgeMs, 60,000)`;
- FRESH ≤ 5,000 < WARM ≤ 30,000 < STALE ≤ 300,000 < EXPIRED;
- also EXPIRED if the session is closed and the state is older than 30,000, if `seq` went backwards, or if a `GOAWAY` arrived since.

**Pessimistic substitution** for STALE:
- `thermalBand = min(2, last + 1)` if last ≥ 1 (r4, R4-L-17; r3 read `max(last, 1)`, a no-op);
- `queueBucket = min(2, last + 1)` if last ≥ 1;
- `batteryBand` one band lower if on battery.

EXPIRED makes the candidate **probe-only**: fast-field filters are skipped, and the `INFER_OFFER` itself is the probe. Peer clock skew must not change any class; W07 has skew vectors at ±10 min.

**Presence laws** (the lender side, in `:mesh-policy`; vectors `W07p-presence.json`, including PF cases):

| Law | Rule |
|---|---|
| **LP-0** classification | **Presence inputs:** screen on or interactive, input idle time, keyguard dismissal, foreground app, a game or heavy foreground process, the console user, login state, contention attributed to other processes. **Condition inputs:** power source and charging, battery level and temperature, thermal band, memory, path, sleep-imminent. **PF exception:** on a node that lends only while its own lend screen is frontmost (iPad M5, Android lend screen, Deck `asom lend --foreground`, Ubuntu Touch UT-2), the lend screen being frontmost, the screen-on state it requires and input **inside** the lend screen are consent, not presence. For such a node, presence = the lend screen leaving the foreground (or its scene resigning active, or its terminal losing focus), the screen turning off, or input outside it |
| **LP-1** | No wire field is computed from a presence input except (i) the accept/decline decision with its code and `retryAfterMs`, (ii) `availability.fsm`, (iii) `queue.bucket`. **Consequence for the frame schemas (§7.2):** `INFER_ACCEPT` carries no `queuePos` or `estStartMs`, and `INFER_END` carries no `ttftMs`, `totalMs` or `usage`, because those values move with the lender's own local use. The decline reason for any presence cause is `PEER_UNAVAILABLE` |
| **LP-2** | A presence signal drains at once (`SERVING → DRAINING`). `fsm` returns to `SERVING` no sooner than 600,000 ms after the last presence signal. A PF node additionally returns only after a new explicit "Start lending". A condition-caused drain (sleep, heat, unplugging) has no hold-down |

**W07p vectors** (minimum):
- a presence event while SERVING → DRAINING in the same step;
- a second presence event at t + 599,999 → still not SERVING at t' + 599,999;
- SERVING at the first evaluation ≥ t_last + 600,000 when conditions allow;
- a thermal drain returns as soon as the band drops;
- PF: frontmost lend screen + screen on + a touch inside it → stays SERVING;
- PF: a touch outside it → DRAINING, then no SERVING without "Start lending";
- the wire projection of every state is exactly `{fsm, qb, decline code, retryAfterMs}` for presence-driven changes (a property test over random event sequences checks that no other wire field changes when only presence inputs change).

### 6.6 The claim tracker (r3) and the M08 vectors

**Principle.** The tracker uses only (a) the requester's monotonic clock at points the requester controls or that the peer cannot move earlier, and (b) text the requester parsed itself. Observation only **lowers** a claim.

```text
key k = (peerNodeId, fileSha256); the claim row is the one whose backend equals the peer's live-state engine.backend
constants (PROVISIONAL): WIN=20 MIN_KEEP=3 MIN_STATE=5 CORR=800 DISC=600 RATIO_CAP=5000 SHORT_BYTES=128
                         bptPermille(file), bptCapPermille(file) from bench-core (§5); class defaults 4000 / 8000

per attempt that sent INFER_BODY and then ended (INFER_END, or loss after content):
  tBody    = requester monotonic ms when the last byte of INFER_BODY was handed to its TLS engine
  tEnd     = requester monotonic ms of the LATER of: INFER_END received, last content chunk received
  elapsed  = max(1, tEnd - tBody)
  outBytes = UTF-8 byte length of the answer text the requester parsed (sum of choices[0].delta.content in stream
             mode, or choices[0].message.content otherwise); SSE framing, JSON syntax, role and finish fields excluded
  outTokEst = ceilDiv(outBytes * 1000, bptPermille(file))
  predicted = E1(requester's own link estimate for this attempt) + E5(claim, P = offer.estTokensIn)
              + E7(claim, m = outTokEst - 1, busy 0, queue 0, hot = false)      // claim row only; no peer state
  ratio    = min(RATIO_CAP, predicted * 1000 / elapsed)                        // checked arithmetic

discard (requester-observed facts only; each counts toward the discard budget):
  terminal != done (or the stream was lost)                            -> DISCARD_INCOMPLETE
  outBytes < SHORT_BYTES                                               -> DISCARD_SHORT
  outBytes > offer.maxTokens * bptCapPermille(file) / 1000             -> DISCARD_OVERLONG, strikes[k] += 1
  another attempt of this requester was in flight on that peer         -> DISCARD_CONCURRENT
  INFER_ACCEPT.fileSha256 != k.fileSha256, or the latest engine.backend/commit
     this requester holds for the peer differs from the claim row's    -> DISCARD_SETTINGS
  (checked in this order; the first match wins)

state (W = the last WIN kept ratios, sorted ascending as x[0..n-1]):
  n < MIN_STATE            -> UNVERIFIED
  best = x[(3 * n) / 4]    // upper quartile, nearest-rank: queueing only slows observations, so the best quartile
                           // tests the claim without penalising an honest busy peer
  best >= CORR             -> CORROBORATED
  DISC <= best < CORR      -> WEAK
  best < DISC              -> DISCREPANT

use for placement (never above the claim):
  n >= MIN_KEEP: effRatio = min(1000, x[(n - 1) / 2])     // lower median: includes the queueing the requester will see
                 tracked rate = claim rate * effRatio / 1000   (prefill, decode and steady alike)
  n <  MIN_KEEP: prior' = min(claim, capRef) * disc / 1000
                 blended with the kept ratios (weight 2 for the prior):
                 rate = (2 * prior' + sum over kept i of claim * min(1000, x_i) / 1000) / (2 + n)
  capRef = min( signedReferenceP90(model, backend, class) * 12 / 10   [only if signed by the compiled-in reference key, RT-12],
                classCeiling(model, class) )                 // never the claim itself
  disc   = 700; 400 if >= 2 keys of this peer are DISCREPANT (for >= 7 days, doubling on repeat)

discard budget: once >= 4 candidate observations exist for k, if more than half of the last min(20, all) were
  discarded: state = WEAK ("cannot verify this device's claims"), and the tracked rate is clamped to
  claim * minRatio / 1000, where minRatio is the lowest ratio among those observations whose outBytes >= 8
  (discarded ones included), or 0 if none qualifies (the candidate then fails F8)
strikes survive seq and backend changes; a new claim seq restarts W but inherits DISCREPANT until 10 new
  observations have best >= CORR; a peer's new claim body is accepted for routing at most once per 24 h
```

**Worked numbers** (M08-001…M08-003; claim prefill 100,000, decode 20,000, `ttft0` 0, no onset; `P = 500`; `B = 5,120`; LAN RTT 10 ms, 100,000 kbit/s, warm; `outBytes` 1,200; bpt 4,000):
- `E1 = 10 + ceilDiv(40,960, 100,000) = 11`; `E5 = 5,000`; `outTokEst = 300`; `E7 = ceilDiv(299 × 10⁶, 20,000) = 14,950`; **predicted = 19,961 ms**.
- **Honest peer** finishing at `elapsed = 20,500`: ratio `973`.
- **A peer whose truth is half its claim:** `elapsed = 11 + 10,000 + 29,900 = 39,911`, ratio `500`. After 5 kept observations its state is **DISCREPANT**; its placement uses `min(1000, median) = 500`, i.e. its true speed.

**M08 adversary vectors** (each with a hand-computed expected value; `oracle: self`):

| Id | Peer behaviour | Expected |
|---|---|---|
| M08-010 burst-at-end | first chunk at `tBody + 50`, all remaining text in one chunk just before `tEnd` | ratio identical to the same total `elapsed` with evenly spaced chunks |
| M08-011 split chunks | the same text in 1 chunk vs 300 chunks vs 1 byte per chunk | identical `outBytes`, identical ratio |
| M08-012 merged chunks | the same text in 2 chunks | identical ratio |
| M08-013 lying `INFER_END` | the peer adds `usage`/`ttftMs`/`totalMs` members claiming 10× speed | members ignored (unknown members of a frame are ignored and never stored); ratio unchanged |
| M08-014 late `INFER_HEAD` | head sent 5 s after content began | no effect (the head's time is unused) |
| M08-015 reported heat | `st.tb = 2` on every piggyback | no effect on `predicted` (claim row only) |
| M08-016 reported queue | `qb = 2` on every piggyback | no discard; ratio unchanged |
| M08-017 padding under the cap | 3,000 bytes (1,200 real + 1,800 filler) in the honest time | ratio 2,071, but `effRatio = min(1000, …)`, so placement cannot exceed the claim; against a peer truly at half speed, padding at most offsets the factor `bptCap / bpt = 2` (the stated residual) |
| M08-018 padding over the cap | `outBytes > maxTokens × bptCap / 1000` (8,192 at `maxTokens` 1,024) | `DISCARD_OVERLONG`, strike + 1 |
| M08-019 truncation | four answers of < 128 bytes | discard budget trips at the 4th; state WEAK; clamp to the lowest ratio among observations ≥ 8 bytes |
| M08-020 honest busy peer | ratios {400, 450, 900, 950, 960} (queueing on 2 of 5) | `best = x[3] = 950` → CORROBORATED; placement `min(1000, x[2] = 900)` |
| M08-021 settings switch | `INFER_ACCEPT.fileSha256` differs from the claim key | `DISCARD_SETTINGS` |
| M08-022 new claim body | the peer publishes a new claim seq twice in one day | the second is ignored for routing until 24 h pass |

**What the vectors cannot show:** content fabrication (a fast peer returning text no model produced) and the padding residual below the cap. Design §5.7 states both as limits.

### 6.7 Merging with the v1 cloud tier, the cap, errors and route reasons

**Composition with the frozen v1 router.**
- `plan()` builds a throwaway v1 `Router(catalogue = { frozen.catalogue }, keys = { id -> id in frozen.keysPresent }, latency = LatencyTracker preloaded with frozen.ewma, cooldowns = CooldownRegistry(clock = { snap.wallNowMs }) replaying frozen deadlines, defaultPolicy, hasLocalEngine = false)` and calls `plan(q.v1)` unmodified.
- A typed `AsomException` becomes an empty cloud tier plus a remembered code.
- R04 (§3.9) pins this adapter.

**Merge per policy (R03).** Total-order tie-break: `(primary key, S, tier ordinal SELF < PEER, nodeId, modelId, fileSha256)`; cloud entries keep v1's order.

| Policy | Merged order |
|---|---|
| `auto` | `[SELF and usable PEERs, by S] + [cloud in v1 auto order] + [unusable PEERs, by S]`; probe-only entries last within each sovereign block; with `neverCloudWhenDevicesCanAnswer` the unusable PEERs move ahead of cloud |
| `cheapest` | `[sovereign by (S2 + S3, then S)] + [cloud in v1 cheapest order]` |
| `fastest` | cloud keeps v1 fastest order; each sovereign entry is inserted stably before the first cloud entry whose estimate it beats (cloud relative order never changes) |
| `best-reasoning` | by catalogue rank (unranked last); sovereign first on equal rank; cloud relative order unchanged |
| `local-only` | `[SELF]` (frozen) |
| any + `X-Asom-Fallback` | cloud only, exactly v1 (frozen) |

**Cap on UNVERIFIED claims** (`router.md` §5.5, unchanged): the top sovereign candidate with an UNVERIFIED claim wins at most `ceilDiv(wouldWin, 4)` of the placements it would win, and only when another usable sovereign candidate exists. The cap never moves work to the cloud. A brand-new peer is limited to 1 in 10 (T5).

**Errors** (design §7.6):
- no new code;
- the most specific true cause among the existing v1/v2 codes, computed over `P`;
- `ALL_PROVIDERS_COOLING` when every candidate, peers included, is cooling or backing off;
- a mid-stream loss ends with the SSE event `data: {"error":{"code":"MESH_STREAM_INTERRUPTED","type":"server_error","reason":"peer-lost"}}` and no `[DONE]`: one reason value for every cause (including a lender's `terminal = interrupted`), so no situation code reaches an app.

**Route reasons (ledger).** Grammar (`router.md` §10.1, r3 values):

```text
reason = tier ":" code [ "/" term ] *( ";" flag )
tier   = "v1" | "self" | "peer" | "cloud"
code   = "policy" | "only-eligible" | "best-score" | "no-usable-sovereign" | "policy-rank" | "policy-fastest"
       | "policy-cheapest" | "failover"
term   = "time" | "battery" | "heat" | "locality" | "quality" | "uncertainty"   ; the dominant term
flag   = "cap:unverified" | "probe" | "stale" | "prev:" <previous attempt's row status>
```

- **The dominant term.** For the winner `w` and the runner-up `r` in the same block, `d_t = r.S_t − w.S_t`; the largest `d_t` wins, ties broken in the order S1…S6.
- **`routeDetail`** holds the winner's and runner-up's breakdowns and the exclusions, as plain text.
- **App-facing projection** (only once v2.5's `X-Asom-Route-Reason` exists; D12.4): `tier ":" code ["/" term]`, with every peer decline or failure collapsed to `;prev:peer-unavailable`. It never carries `stale`, the battery or heat terms, or a `PEER_*` code. A law asserts this over generated plans.

### 6.8 Laws RL1–RL22 (property tests) and non-vacuity

The oracles are derived from the generated world, never from the router's own key functions. **Every law counts the iterations that exercised its antecedent and fails if that count is below its floor**, which defaults to 100 per run with seeds 1…20.

| Law | Statement (r3 amendments in bold) |
|---|---|
| RL1 | No engine and no surviving peer ⇒ exactly `Router.plan` (R04). **Engine present, mesh off ⇒ `[SELF] + v1 cloud plan`, SELF removed only by the v2 governor.** A long prompt on a phone keeps SELF first |
| RL2 | `local-only` ⇒ every attempt SELF |
| RL3 | `X-Asom-Fallback` ⇒ every attempt CLOUD, equal to v1's restricted plan |
| RL4 | Every PEER attempt satisfies `O ∈ P`, PAIRED, `routeEnabled`, `infer` granted, **evaluated at the instant `INFER_BODY` is sent** |
| RL5 | No attempt violates a §6.3 row (EXPIRED exceptions exactly as stated) |
| RL6 | Determinism; invariance under permutation of peers, files and catalogue order |
| RL7 | No two attempts compare equal under the tie-break chain |
| RL8 | Improving one input of one candidate never moves it later; worsening never earlier (**transitions out of EXPIRED excluded**) |
| RL9 | Term-wise dominance within a block ⇒ precedence (**excluding cap swaps and the probe-only partition**) |
| RL10 | Policy laws (`cheapest`: sovereign before priced cloud; `best-reasoning`: non-decreasing rank; **`fastest`: sovereign non-decreasing S1, cloud in v1 order**; `auto`: usable sovereign ≺ cloud ≺ unusable sovereign unless the user switch is on) |
| RL11 | The cap bound: an UNVERIFIED key wins at most ⌈k/4⌉ of k would-win plans; the cap never moves work to the cloud |
| RL12 | Claims never grant eligibility: the content-eligible set depends on policy and the registry only; claim- and state-driven rows only remove candidates |
| RL13 | **Among candidates passing the FRESH filters, an EXPIRED candidate is probe-only and never ahead of a non-expired one**; STALE never ranks better than identical FRESH |
| RL14 | Ledger completeness in the pipeline (simulator): each offer has a requester intent row before it; each body has `bytesOut > 0` durable before the send; rows link by `attemptId` |
| RL15 | Egress truth: the terminal row's `egress` = `reach`; **RL15b: on the error path too** |
| RL16 | Attempts ≤ `maxAttempts`; none starts after the deadline; no retry after the first byte reached a streaming client |
| RL17 | At most one attempt per request in BODY_SENT or RECEIVING at a time |
| RL18 | A failed peer is excluded until its cooldown ends and returns after it |
| RL19 | Fairness under saturation (simulator): Jain's index across apps ≥ 900‰ |
| RL20 | Arithmetic safety within the bounds of §6.4 |
| RL21 | Replaying a recorded event log reproduces every decision (**sovereign tier only until R06 passes**) |
| RL22 | No content to a candidate whose offer was declined, timed out or cancelled before the body |
| **RL-H** (new) | No app-facing projection contains a `PEER_*` code, a battery or heat term, or `stale` |

### 6.9 The simulator (`:mesh-sim`, test scope only)

**Architecture.** A discrete-event loop runs over a priority queue ordered by `(tMs, seq)`, on a virtual clock with no threads. It drives the **real** `MeshRouter`, the real reducers, the real ledger model (`:ledger-model`, with its write-ahead assertions) and a `SimTransport` with `asom-mesh/1` semantics (offer, accept or decline, body, head, chunk, end, cancel; the lender's decision table from design §4.3). Only the transport, engine, clock and probes are simulated.

**Scenario format** (`lab/conformance/scenarios/SCnn.json`; integers only; ratios in ‰):

```json
{
  "scenario": "SC01", "seed": 1, "durationMs": 3600000,
  "label": "SIMULATED — NOT DEVICE EVIDENCE",
  "catalogue": "fixtures/catalogue.v1.json",
  "nodes": [
    { "id": "self", "tier": "SELF", "class": "PHONE", "backend": "cpu",
      "files": [ { "modelId": "qwen3-8b", "fileSha256": "<64 hex>", "fileBytes": 5030000000, "quant": "Q4_K_M" } ],
      "truth": { "<64 hex>": { "prefillMilliTokPerSec": 30000, "decodeMilliTokPerSec": 5000, "ttft0Ms": 200,
                               "steadyMilliTokPerSec": 4000, "throttleOnsetMs": 180000 } },
      "claimScalePermille": 1000,
      "power": { "source": "battery", "batteryPermille": 600, "designMilliWh": 19000, "chargerSchedule": [] },
      "presence": [ { "fromMs": 0, "toMs": 600000 } ] },
    { "id": "mac", "tier": "PEER", "class": "DESKTOP", "backend": "metal",
      "files": [ { "modelId": "qwen3-8b", "fileSha256": "<64 hex>", "fileBytes": 5030000000, "quant": "Q4_K_M" } ],
      "truth": { "<64 hex>": { "prefillMilliTokPerSec": 400000, "decodeMilliTokPerSec": 40000, "ttft0Ms": 100,
                               "steadyMilliTokPerSec": 40000, "throttleOnsetMs": null } },
      "claimScalePermille": 1000,
      "power": { "source": "ac" }, "presence": [] }
  ],
  "links": [ { "a": "self", "b": "mac", "path": "LAN", "rttMedianMs": 10, "rttSigmaPermille": 300,
               "kbps": 100000, "metered": false, "dropsPerHour": 0 } ],
  "cloud": [ { "provider": "openrouter", "ttftMedianMs": 800, "decodeMilliTokPerSec": 50000,
               "errorPermille": { "429": 0, "5xx": 0 } } ],
  "apps": [ { "pkg": "app.a", "meshAllowed": true, "cloudBanned": false, "deviceOnly": false, "policy": "auto",
              "arrivalsPerHour": 60, "promptTokensMedian": 500, "outTokensMedian": 300, "sigmaPermille": 400,
              "streamPermille": 800 } ],
  "faults": [ { "atMs": 1200000, "kind": "peer-vanish", "node": "mac", "phase": "mid-stream" } ],
  "expect": { "lawViolations": 0, "minPlacementPermille": { "mac": 950 } }
}
```

- **The `truth`/claim split.** `claimScalePermille` multiplies `truth` to form the claim the node publishes: 2000 is a 2× liar (SC06).
- **Fault kinds** (closed list): `peer-vanish` (phases `before-head`, `mid-stream`), `session-drop`, `network-change`, `thermal-spike`, `charger-unplug`, `battery-floor`, `presence` (a user picks up the lender), `claim-lie`, `claim-stale` (engine commit changed), `decline-storm`, `state-delay`, `state-drop`, `clock-skew` (±600,000 ms on a peer; must not change any staleness class), `duplicate-attempt`, `overlay-only-high-rtt`, `metered-underlay`, `ledger-full` (the sink starts throwing; FC-1/FC-2 apply).
- **Determinism.** Randomness comes from one `java.util.SplittableRandom(seed)`, split in this fixed order: links, workload, faults, truth noise. The clock is virtual, there are no threads, and decision paths iterate sorted collections. The gate runs seeds 1…20 per scenario.
- **Replay.** Every run writes `events.jsonl` (`{"t", "seq", "kind", …}`, integers only) and `decisions.jsonl` (the plan and reason per request). Replay mode feeds `events.jsonl` back through the reducers and `plan()` and diffs the decisions: RL21 passes when the diff is empty. Output files carry the label line `SIMULATED — NOT DEVICE EVIDENCE` as their first record.
- **Baselines:**
  - B0: v1 cloud only;
  - B1: local only;
  - B2: static roadmap order with the same hard filters;
  - B3: this design;
  - B4: per-request greedy hindsight (not optimal).

**First slice (the L0.6 gate):**

| Scenario | Pass condition (beyond zero law violations and non-vacuity) |
|---|---|
| **SC01** phone + Mac holding the model | ≥ 950‰ of chat on the Mac; the phone's battery use below B1's by ≥ 800‰ |
| **SC04** Dell vanishes mid-stream | typed `MESH_STREAM_INTERRUPTED`; both ledgers consistent (joined on `attemptId`); no retry after delivered bytes |
| **SC06** lying manifest (claim 2× truth) | the key reaches DISCREPANT within 5 kept observations; the liar's placement share falls to B4's share + 100‰ within 50 requests |
| **SC09** device-only apps and `local-only` requests mixed in | zero peer attempts for them (RL2, and `P ∩ {T}`) |

The full thermal model and fan-out wait for the v2 engine (design §7.11). The simulator lives only in the lab build, never in `:core:routing:test`.

### 6.10 Router vector families

| Family | Contents | Status |
|---|---|---|
| R01 hard filter | (query, node view) → excluded with code; every §6.3 row incl. the EXPIRED exceptions | proposed until M1's rulings (D9, D11) |
| R02 scoring | E0–E12 and S1–S6 exactly, at zero, typical and saturating values; R02-r3-001 above. **The r0 `R02-worked-example.json` is retired**: it reads removed live-state fields. `router_ref.py` is updated to the r3 formulas as a self-oracled cross-check | proposed |
| R03 ordering | the merge per policy, ties and permutations; one counterexample vector for each r3 law exception | proposed |
| R04 v1 pins | §3.9 | **normative** |
| R05 failover | (attempt state, event) → action, row status, breaker change; every row of `router.md` §8.2 with the r3 codes (`PEER_UNAVAILABLE` replaces `PEER_THERMAL`, `PEER_BATTERY` and `PEER_USER_ACTIVE`; `terminal = interrupted` replaces `thermal`) | proposed |
| R06 reducers | integer EWMA, cap counters, freshness classes, and the pure breaker pinned to `CooldownRegistry`'s behaviour | proposed |

---

## 7. Peer protocol, ledger model and the per-frame ledger rule (L0.4, L0.5)

Background: `trust.md` §2–§5 and §15; `contract.md` §4. This section is the r3 wire spec for mesh-1: the r2/r3 trims are applied, and every JSON shape is given exactly. Examples are shown in JCS form, with keys sorted; producers emit JCS, and receivers parse strictly but do not require JCS for frames.

### 7.1 Frame codec

```text
frame   = length:u32be  type:u8  stream:u32be  payload
length  = 5 + |payload|        ; 5 <= length <= 16,777,221 (16 MiB + 5), else FRAME_TOO_LARGE and close
stream  = 0 for connection-level frames; odd = opened by the TLS client; even = opened by the TLS server;
          PAIR_* frames use stream 1 of a pairing-mode connection
payload = UTF-8 JSON (strict parse, §4.2) except INFER_BODY and INFER_CHUNK, which are raw bytes
types   = 0x80..0xFF: ignorable extensions (skipped; a CONTROL row EXT_IGNORED; mesh-1 senders never send one);
          an unknown type below 0x80 -> ERROR PROTOCOL_ERROR, close
```

- **Retired in mesh-1:** `0x03` and `0x04` (`PING`/`PONG`), `0x41` and `0x42` (revocation and locator hints), and the placement frames. They are handled as unknown types below `0x80`.
- **Size limits.** A JSON payload is at most 1,048,576 bytes: a lab constant, since `trust.md` §3.3 sets only the frame limit. `INFER_BODY` is at most 8,388,608 bytes.
- **Application bytes of a frame = `9 + |payload|`.** This is what ledger rows count (§7.6).

**Worked encodings** (computed; vectors W06-001…W06-004):

| Frame | Bytes (hex) | App bytes |
|---|---|---|
| `HELLO` `{"v":1}` on stream 0 (the `trust.md` example) | `0000000c 01 00000000 7b2276223a317d` | 16 |
| `GOAWAY` `{"reason":"idle"}` | `00000016 05 00000000 7b22726561736f6e223a2269646c65227d` | 26 |
| `STATE_REQ` `{"v":1}` on stream 3 | `0000000c 20 00000003 7b2276223a317d` | 16 |
| `CANCEL` `{"attemptId":"AAAAAAAAAAAAAAAAAAAAAA","reason":"deadline"}` on stream 5 | `0000003f 17 00000005 7b22617474656d70744964223a22414141…` (67 bytes in total) | 67 |

### 7.2 Frames (mesh-1)

**Common rules:**
- **Identifiers.** `nodeId` and `attemptId` are base64url without padding: `attemptId` is 16 CSPRNG bytes (22 characters), `sessionNonce` 16 bytes, `challenge` 32 bytes (43 characters).
- **Numbers** are integers (C1). A receiver ignores unknown members of a frame and never stores or displays them.
- **Authorisation** (`authorize(pin, scope)`) is re-read at every `INFER_OFFER`, `INFER_BODY`, `STATE_REQ` and `MANIFEST_REQ`. A registry change that leaves PAIRED closes the peer's sessions with `GOAWAY {"reason":"revoked"}` or `{"reason":"suspended"}`.

| Type | Name | Dir, stream | Payload (exact members; `?` = optional) |
|---|---|---|---|
| 0x01 | `HELLO` | C→S, 0 | `{"endpoints":[<endpoint>…],"features":["infer.offer","manifest","state"],"keyTier":"<tier>","maxV":1,"minV":1,"name":"<≤ 32 code points>","nodeId":"<43>","platform":"<platform>","proto":"asom-mesh/1","sessionNonce":"<22>","sw":"<product/version>","ts":<epoch ms>,"v":1}` |
| 0x02 | `HELLO_ACK` | S→C, 0 | `{"endpoints":[…],"features":[…],"granted":["infer","manifest","state"],"limits":{"idleUnloadMs":300000,"maxBodyBytes":8388608,"maxConcurrent":1,"maxTokens":4096,"rpm":30},"nodeId":"<43>","st"?:<st>,"ts":<epoch ms>,"v":1}` |
| 0x05 | `GOAWAY` | both, 0 | `{"reason":"revoked"\|"suspended"\|"shutdown"\|"network-change"\|"idle"\|"max-age"}`. Sleep, heat and presence drains all say `shutdown` (the cause stays in the local ledger; design §3.2) |
| 0x06 | `ERROR` | both, any | `{"attemptId"?:"<22>","code":"<MeshError>","retryAfterMs"?:<int>}`. **No `message` member**: peer-authored text never reaches a row, header or UI; a received `message` is ignored |
| 0x10 | `INFER_OFFER` | C→S, new odd | `{"attemptId":"<22>","deadlineMs":<int>,"estTokensIn":<int>,"maxTokens":<int>,"model":"<concrete catalogue id>","op":"chat"\|"completions"\|"embeddings","promptBytes":<int>,"stream":<bool>}`. No content, no `dataClass`, no `retain` (always none) |
| 0x11 | `INFER_ACCEPT` | S→C, same | `{"attemptId":"<22>","fileSha256":"<64 hex>","servedModel":"<id>","st"?:<st>}`. **No `queuePos`, no `estStartMs`** (LP-1, §6.5) |
| 0x12 | `INFER_DECLINE` | S→C, same | `{"attemptId":"<22>","code":"PEER_BUSY"\|"PEER_UNAVAILABLE"\|"MODEL_NOT_OFFERED"\|"SCOPE_DENIED"\|"DUPLICATE_ATTEMPT","retryAfterMs":<5000…600000>,"st"?:<st>}` |
| 0x13 | `INFER_BODY` | C→S, same | raw bytes: the OpenAI JSON body after the D11 normaliser (identity-bearing fields `user`, `metadata`, `safety_identifier`, `prompt_cache_key`, `store` dropped; their names recorded in `droppedFields`). The normaliser runs only if D11 is ruled (a) or (c) |
| 0x14 | `INFER_HEAD` | S→C, same | `{"attemptId":"<22>","engine":"local","servedModel":"<id>","status":<http status>}` |
| 0x15 | `INFER_CHUNK` | S→C, same | raw bytes: SSE event bytes (stream) or the whole JSON body (non-stream) |
| 0x16 | `INFER_END` | S→C, same | `{"attemptId":"<22>","st"?:<st>,"status":<int>,"terminal":"done"\|"cancelled"\|"interrupted"\|"oom"\|"error"}`. **No `usage`, `ttftMs` or `totalMs`**: the tracker ignores them (§6.6), rows never carry them, and timings move with the lender's local use (LP-1) |
| 0x17 | `CANCEL` | C→S, same | `{"attemptId":"<22>","reason":"client-gone"\|"deadline"\|"policy-changed"\|"superseded"}` |
| 0x20 | `STATE_REQ` | C→S, new odd | `{"v":1}`; needs scope `state` (else `ERROR SCOPE_DENIED`) |
| 0x21 | `STATE` | S→C, same | `asom.state/1` (below) |
| 0x22 | `MANIFEST_REQ` | C→S, new odd | `{"challenge":"<43>","v":1}`; needs scope `manifest` |
| 0x23 | `MANIFEST` | S→C, same | the container of §4.3, own audience, signed by the NIK, answering the challenge; or `ERROR {"code":"MANIFEST_UNAVAILABLE"}` |
| 0x30–0x34 | `PAIR_*` | §7.3 | pairing connections only |
| 0x40 | `REVOKE_NOTICE` | both, 0 | `{"reason":"user","v":1}` (a courtesy; the local revoke is authoritative) |

**Enumerations:**
- `<endpoint>` = `{"addr":"<IP literal>","port":<int>,"via":"lan"|"overlay"}`, at most 4.
- `<tier>` ∈ `strongbox | tee | secure-enclave | tpm | os-keystore | file`.
- `<platform>` ∈ `android | ios | ipados | macos | linux | windows | ubuntu-touch`.
- `<st>` = `{"fsm":"OFF"|"ARMED"|"SERVING"|"DRAINING","gov":"RUN"|"QUEUE"|"HOLD","qb":0|1|2,"seq":<int>,"tb":0|1|2}`. It is sent only to a peer that this node granted scope `state`.
- `<MeshError>` ∈ `PEER_NOT_PAIRED SCOPE_DENIED PROTOCOL_ERROR VERSION_UNSUPPORTED FRAME_TOO_LARGE DUPLICATE_ATTEMPT CLOCK_SKEW MODEL_NOT_OFFERED PEER_BUSY PEER_UNAVAILABLE MANIFEST_UNAVAILABLE PAIRING_WINDOW_CLOSED PAIRING_PROOF_INVALID PAIRING_REFUSED`. An unknown code is handled as `PROTOCOL_ERROR` and stored as `UNKNOWN`.

**`asom.state/1`** (`STATE` payload; integers and enums only; design §7.4):

```json
{"availability":{"fsm":"SERVING"},"engine":{"backend":"vulkan","commit":"4f1c2ab","confVersion":"1.0.0","held":["<64 hex>"]},"manifest":{"bodyDigest":"<43>","seq":17},"power":{"batteryBand":null,"charging":false,"source":"ac"},"queue":{"bucket":0},"sampledAgeMs":800,"seq":4711,"thermal":{"band":0,"governor":"RUN"},"v":1}
```

- `power.source` ∈ `ac | battery | unknown`; `batteryBand` ∈ `ge80 | 50-79 | 20-49 | lt20 | null`; `engine.backend` is a closed enum (`cpu`, `vulkan`, `metal`, `opencl`, `cuda`, `hexagon`).
- `manifest` is the latest **approved** body only, or `null`.
- W07 rejects any added presence field (for example `user`, `inflight`, `busyForMs`, `loaded`, `estStartS`, `reason`) **when the producer is asom**. A receiver still ignores unknown members, so W07's producer-strict vectors test the lab's own `STATE` builder.

**Version rule.** Pick the highest `v` in both ranges; if there is none, send `ERROR VERSION_UNSUPPORTED` and close.

**The lender's decision table** is `trust.md` §7.3 plus these rows:
- **6a:** a predicted thermal hold → `PEER_UNAVAILABLE`;
- **6b:** `estStartMs > deadlineMs` (the lender's own estimate, never sent) → `PEER_BUSY`;
- **8:** `retain` is always none.

### 7.3 Pairing frames, proof and SAS

- **QR payload grammar:** `trust.md` §4.2, unchanged (vectors `W04-pairing.json`).
- **Messages:** `trust.md` §4.4, with these r3 changes:
  - `PAIR_COMMIT` loses `locSeed` (D12.6 withdraws the mDNS locator);
  - `platform` uses the §7.2 enum;
  - `name` is at most 32 code points.
- **Proof, SAS and transcript:** `trust.md` §4.5, unchanged. The worked vector gives proof `becQGXmCCTwCLN4BK4hj0qa3AW-AT9DgDikj7RA_baM`, SAS `865 412`, transcript `a7cfd74766ca9d6eab6393ad20c52a1e103e394678101b503f1e48c6f473ffb1`. W04 carries it, 20 random cases, and the pin-swap and nonce-swap negatives.
- **Registry laws:** `trust.md` §15, L1–L8 and L10–L11. L9 and L12 are superseded (classes `other` and mDNS are out of mesh-1).

### 7.4 TLS, certificates and the hostile-node suite

- **TLS profile:** `trust.md` §3.2:
  - TLS 1.3 only, client authentication required, `ecdsa_secp256r1_sha256` only;
  - no 0-RTT, **no resumption** (r4, R4-L-23: a fresh `SSLContext` per dial **and per accepted connection**; tickets a JSSE listener still sends cannot be redeemed), no SNI (unless spike S-A11 succeeds and SNI-token gating is adopted, design T13);
  - ALPN `asom-mesh/1` required;
  - a custom `X509ExtendedTrustManager` that calls only `verifyPeerChain`; no hostname check; 5 s handshake timeout.
- **JSSE settings stay scoped.** Every JSSE setting is applied to the `SSLEngine`/`SSLParameters` of the mesh context, never as a JVM system property (C12).
- **Certificates:** the two fixed templates of `trust.md` §2.4 (node: self-signed, CA:TRUE pathLen 0, keyCertSign; leaf: 14 days, digitalSignature, serverAuth and clientAuth, AKI = node SKI). They are encoded by a small DER encoder with golden vectors (`W05-fingerprints.json` also holds the template bytes for the TEST-ONLY keys). No BouncyCastle.
- **`verifyPeerChain`:** exactly `trust.md` §3.2, with its negative vectors from `trust.md` §15 (`certs/verify-chain.json`, folded into W05).
- **W08 (the hostile-node suite, test-only, over `127.0.0.1`).** A reference node that misbehaves, in both roles:
  - wrong or missing chains, CA leaves, P-384 or RSA keys, SHA-1 signatures;
  - no client certificate; a wrong or absent ALPN;
  - resumption and early-data attempts;
  - REVOKED and SUSPENDED pins; oversize frames; an unknown type below `0x80`; a duplicate `attemptId`;
  - a `MANIFEST` that fails verification; `STATE` with floats or presence fields.
  - **Expected:** zero accepted bad chains; no `pre_shared_key` extension in any ClientHello (inspected with a record tap); a client `CertificateVerify` in every session; every refusal typed; every row per §7.6.

### 7.5 The ledger model (`:ledger-model`)

```kotlin
enum class LabEgress(val wire: String) { LOCAL("local"), CLOUD("cloud"), CATALOGUE("catalogue"), DOWNLOAD("download"),
    PEER("peer") /* CD-12, AD-2 */, CONTRIBUTION("contribution") /* CD-13, D6; unused by the mesh model */ }
enum class Phase { INTENT, OUTCOME }
enum class MeshKind { INFER_SENT, INFER_SERVED, DIAL, SESSION, CONTROL, INBOUND_REFUSED, MANIFEST_SENT, MANIFEST_RECEIVED,
    PAIRING, REVOCATION }
enum class OverheadBasis { MEASURED, ESTIMATED }
enum class PeerPath { LAN, OVERLAY }

data class LabRouteRecord(
    // the 13 v1 fields, same names and meanings as xyz.mdhv.asom.contract.RouteRecord (egress typed LabEgress)
    val ts: Long, val callerPkg: String, val requestedModel: String, val servedProvider: String?, val servedModel: String?,
    val egress: LabEgress, val bytesOut: Long, val tokensIn: Long?, val tokensOut: Long?, val costEst: Double?,
    val costBasis: String, val latencyMs: Long, val status: Int,
    // the 20 mesh columns of design §8.4 (and no others: §8.3 counts exactly these)
    val requestId: String?, val attemptId: String?, val phase: Phase?, val attemptIndex: Int?,
    val reach: LabEgress?, val terminal: Boolean?, val servedClass: LabEgress?,
    val peerNode: String?, val peerAlias: String?, val peerPath: PeerPath?,
    val meshKind: MeshKind?, val bytesIn: Long?, val meshCode: String?,
    val destAddr: String?, val addrSource: String?,
    val routeReason: String?, val routeDetail: String?, val droppedFields: List<String>?,
    val overheadBytes: Long?, val overheadBasis: OverheadBasis?,
)
```

**Projections (Invariant 9):**
- **`toEchoHeaders()`** is v1's rule, with `X-Asom-Egress` = `reach` on the terminal row (P7; ruled by D3). On every v1 path, `reach` equals v1's egress, and W01 must pass unchanged through this projection.
- **`toRow()`** is the JSONL row.
- **Both come from the same record**, and a property test asserts `headersOf(toRow(r)) == toEchoHeaders(r)`.

**Other row rules:**
- `requestId` never appears in a frame (L-L7).
- **Session rows.** `SESSION`, `CONTROL`, `PAIRING`, `MANIFEST_*` and `REVOCATION` rows carry the session id (the dialer's `HELLO.sessionNonce`; for pairing connections, base64url of the first 16 bytes of `PAIR_HELLO.nonceS`, which both sides know) in `attemptId`, as `contract.md` §4 specifies, so no extra column exists. (r4: the grouping rules are R4-L-24; whether the session id gets its own column is owner item D34.)
- IP addresses appear only in `DIAL` rows (P5).
- `tokensOut` on a requester's peer-attempt row is the requester's own `outTokEst` (§6.6).
- Intent rows carry no bytes, tokens or cost.
- **`callerPkg` forms:** the verified package (Android); `peer:<nodeTag>` (lender-served rows); `local-uid:<user>` (Linux and macOS owner CLI); `local-sid:<sid>(acl)` (Windows owner CLI); `self-ui:<artefact>` (Ubuntu Touch app, iOS). The last three depend on D25.

**JSONL sink (desktop and lab):**
- one row per line, UTF-8, `\n`;
- `FileChannel.write` then `force(false)` per append; the append returns only after `force`;
- **it throws on failure**, and only cloud *outcome* rows may degrade (a v1 behaviour kept for Android).

### 7.6 Which frame produces which rows (L02 vectors; law L-L16)

"Durable before" means that `force` returned before the frame's first byte was handed to the TLS engine (sending side), or before any reply was sent (receiving side). Bytes are application bytes (`9 + |payload|`) summed per direction into `bytesOut`/`bytesIn` of the covering row.

| Frame or event | Sender's row | Receiver's row | L-L16 |
|---|---|---|---|
| outbound TCP connect | `DIAL` intent before the SYN (`destAddr`, `addrSource` ∈ `qr \| hello \| user`); `DIAL` outcome ∈ `connected \| refused \| timeout \| pin-mismatch \| not-tls \| local-network-denied \| firewall-blocked`, with the handshake `overheadBytes` | — | no (L-L14) |
| inbound connection authenticated | — | `SESSION` open (`meshCode` `established` or `pairing`), handshake `overheadBytes` | no |
| inbound refused before authentication | — | counted in one `INBOUND_REFUSED` row per 10 min (no addresses) | no |
| `HELLO`, `HELLO_ACK`, `STATE_REQ`, `STATE`, `MANIFEST_REQ`, `GOAWAY`, connection-level `ERROR` | `CONTROL`, `meshCode` = frame name (or `ERROR:<code>`, `GOAWAY:<reason>`) | `CONTROL`, same | **yes** |
| `MANIFEST` | `MANIFEST_SENT` (links to the stored container bytes) | `MANIFEST_RECEIVED` (the verdict in `meshCode`) | **yes** |
| `PAIR_HELLO`, `PAIR_CHALLENGE`, `PAIR_DECISION`, `PAIR_COMMIT`, `PAIR_COMMIT_ACK` | one `PAIRING` row per frame | one `PAIRING` row per frame | **yes** |
| `REVOKE_NOTICE` | `REVOCATION` | `REVOCATION` | **yes** |
| extension frame received (≥ 0x80) | — | `CONTROL` `EXT_IGNORED` | **yes** |
| `INFER_*`, `CANCEL`, an `ERROR` carrying an `attemptId` | **attempt rows**: requester `INFER_SENT` intent before `INFER_OFFER`; outcome after the last frame, with the frame bytes of the attempt summed | lender: a decline gets an outcome only (`meshCode` = the decline code); a served attempt gets an `INFER_SERVED` intent after `INFER_BODY` arrives and before the engine reads it, and the outcome before `INFER_END` is sent | no (attempt laws) |
| piggybacked `st` | counted in the carrier's row | same | via carrier |
| session close | `SESSION` close with `overheadBytes` and `overheadBasis` | same | no |

**Transport overhead (`overheadBytes`):**
- **JSSE lab lane: `MEASURED`.** The mesh transport drives an `SSLEngine` itself; each `wrap`/`unwrap` result's `bytesProduced`/`bytesConsumed` gives exact network bytes. The overhead is network bytes minus the application bytes of the frames. (r4, R4-L-25: the overhead comes from the transport meter, never from the rows, and a plaintext tap checks the rows.)
- **`ESTIMATED` form** (used after a crash, or for an unknown stack): `22 × ceilDiv(appBytesOfFlush, 16384)` per flush, plus 4,096 bytes per direction for the handshake. (r4, R4-L-25: recalibrated; JSSE measured 38 bytes per record and 16,367-byte records; 22 is the RFC 8446 minimum.) The 22 bytes are the 5-byte record header, the 1-byte inner content type and the 16-byte tag [F51]; the handshake figure is [A36].
- **A record tap.** A test-only `SocketChannel` wrapper counts raw bytes. **L-L15:** per session, Σ row application bytes + `overheadBytes` = the tap's count, for `MEASURED` sessions. The law fails if every session in the run was `ESTIMATED` (non-vacuity).

### 7.7 Fail-closed rules and ledger laws

| Point | Rule |
|---|---|
| FC-1 requester intent append fails | send no bytes; try no further peer or cloud candidate; return `LEDGER_UNAVAILABLE` (a lab error type; CD-LU, D5) |
| FC-2 control-row append fails | send **nothing further** on that session (no `GOAWAY`); close the TLS connection and socket; mark the ledger unavailable, so every further intent fails (FC-1) until a write succeeds; attempt the `SESSION` close row |
| FC-4 lender intent append fails | decline `PEER_UNAVAILABLE` (that decline's own row is the next write; if it fails too, FC-2); the engine never starts |
| FC-5 lender outcome append fails | (r4, R4-L-26) send no frame (no `INFER_END`; no `CANCEL`, which is a requester frame), cancel the engine locally, then FC-2 |

**Laws:**
- L-L1…L-L12 as in `contract.md` §4.12.
- **L-L5:** echo headers equal `reach`.
- **L-L5b:** exactly one terminal row per request; its `egress` equals `X-Asom-Egress`; `servedClass` is the serving attempt's class (W01b-reach).
- **L-L13:** with a sink that throws at each durability point, zero content bytes reach any socket after the failure, and zero frames of any kind are sent on a session after a control-row failure.
- **L-L14:** every TCP connect has a durable `DIAL` intent before its SYN.
- **L-L15:** as in §7.6.
- **L-L16:** every frame in the "yes" rows has exactly one row of the stated kind on each node that sent or received it, and every attempt frame is covered by its attempt's rows. Each run prints per-frame-type counts and **fails if any listed type was never exercised**.

**Durability harness.** A forked JVM (`ProcessBuilder` running a small main in the test sources) appends through the JSONL sink and is killed with `SIGKILL` (`Process.destroyForcibly()` on POSIX) at each durability point. Every row reported durable before the kill is intact and parseable. Only process death is claimed, never power loss.

**Scope note.** The lab builds the D5(b) variant (per-frame control rows), which is the recommendation, **ahead of the D5 ruling**. The D5(a) variant (interval rows) is one alternative class, `IntervalControlLedger`, with its own interval law. If the owner rules D5(a), the lab swaps the class and the L-L16 vectors are replaced by the interval-law vectors. Nothing is promoted either way until the owning version's entry.

---

## 8. Gates (real commands; paste the real output into `PROGRESS.md`)

**Every lab gate includes:**
- the four isolation checks and the root test-count check (§2.4);
- the evidence label `LAB` (and `SIMULATED — NOT DEVICE EVIDENCE` for simulator output);
- for any vector family, the line `oracle: self` until §4.10's independent agreement is recorded.

**JDK lanes.** Temurin 17 and 21 on Linux with `ANDROID_HOME=""` and `ANDROID_SDK_ROOT=""` are the gate. Isolation check 2 runs in a separate step **with** the runner's SDK present.

### 8.1 Gates per work item

| Item | Command | Expected output |
|---|---|---|
| **L0.1** | `./gradlew -p lab labTest --stacktrace` (JDK 17, then 21); `./gradlew -p lab :conformance-runner:run --args='lines W00,W01,W01b,W02,W03,R04' --quiet \| tail -n 3`; `python3 lab/tools/xcheck.py lab/conformance` | `BUILD SUCCESSFUL`; family lines `family W00: 7 vectors, 7 pass …` for W00, W01, W01b, W02, W03 and R04 with 0 fail; `xcheck W01: <n> agree, 0 disagree`; the four isolation checks as in §2.4; the root test count equal to the baseline |
| **L0.2** | `./gradlew -p lab :json:test :manifest:test :conformance-runner:test --tests '*Manifest*'` (JDK 17 and 21); `python3 lab/tools/xcheck.py lab/conformance --families M01,M02,M03` | M01, M02, M03, M05, M06: 0 fail, and every id of §4.9 present (the runner fails on a missing id); `xcheck M02: <n> agree, 0 disagree`; the gate text says **self-oracled** |
| **L0.3** | `./gradlew -p lab :bench-core:test` | M04 (incl. seeds M04-001…008), projection, executor-trace and M05-MLP vectors: 0 fail; the defaults contain no L1 pin |
| **L0.4** | `./gradlew -p lab :ledger-model:test :mesh-policy:test` | L01 (destination sets) and L02 (frame rows) 0 fail; laws L-L1…L-L16 each print `iterations: <n>` with n > 0; the SIGKILL harness prints `rows intact after kill at <point>` for every durability point; W01b-reach passes (status proposed) |
| **L0.5** | `./gradlew -p lab :mesh-proto:test` | W04, W05, W06, W07, W07p, W08: 0 fail; W08 prints `accepted bad chains: 0; ClientHellos with pre_shared_key: 0; sessions with client CertificateVerify: <n>/<n>`; L-L15 `MEASURED sessions: <n> (n > 0), mismatches: 0`; L-L16 per-frame-type counts, none zero. Also the S-A9 matrix (each TLS knob and where JSSE enforces it, on JDK 17 and 21) and spike S-A11 (SNI-token gating in JSSE through `X509ExtendedKeyManager`, the T13 mitigation), recorded as tables |
| **L0.6** | `./gradlew -p lab :mesh-router:test :mesh-sim:test` | R01–R06 and M08: 0 fail; RL1…RL22 and RL-H: `violations: 0`, each with `iterations >= 100`; SC01, SC04, SC06, SC09 pass for seeds 1…20; replay diff empty; every simulator output file starts with `SIMULATED — NOT DEVICE EVIDENCE` |

If a command's real output differs from the expected column, the gate has not passed. Record the output and either fix the defect or write `BLOCKED(<reason>)`.

### 8.2 CI workflow `.github/workflows/lab.yml` (new file; `ci.yml` untouched)

```yaml
name: lab
on:
  push: { branches: [main] }
  pull_request: {}
permissions: { contents: read }
concurrency: { group: 'lab-${{ github.ref }}', cancel-in-progress: true }
jobs:
  lab-tests:
    name: Lab (pure JVM, JDK ${{ matrix.jdk }})
    runs-on: ubuntu-latest
    strategy: { matrix: { jdk: ['17', '21'] } }
    env: { ANDROID_HOME: '', ANDROID_SDK_ROOT: '' }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '${{ matrix.jdk }}' }
      - uses: gradle/actions/setup-gradle@v4
        with: { cache-read-only: "${{ github.ref != 'refs/heads/main' }}" }
      - name: Lab tests
        run: ./gradlew -p lab labTest --stacktrace
      - name: Isolation checks 1, 3, 4
        run: |
          test "$(grep -cwE 'lab' settings.gradle.kts)" = "0"
          test "$(./gradlew -p lab projects | grep -cE "':(app|vault|pairing|storage|ledger|client|client-cloud|sample-client)'")" = "0"
          git diff --exit-code -- core server gradle settings.gradle.kts build.gradle.kts gradle.properties .github/workflows/ci.yml
      - name: Python cross-check
        run: python3 lab/tools/xcheck.py lab/conformance
  lab-isolation-with-sdk:
    name: Lab isolation check 2 (Android SDK present)
    runs-on: ubuntu-latest            # ANDROID_HOME is set on this image; deliberately NOT cleared here
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - name: No Android tooling in the lab build
        run: |
          test -n "$ANDROID_HOME"
          test "$(./gradlew -p lab buildEnvironment | grep -c com.android)" = "0"
  root-unchanged:
    name: Root jvmTest unchanged
    runs-on: ubuntu-latest
    env: { ANDROID_HOME: '', ANDROID_SDK_ROOT: '' }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - run: ./gradlew jvmTest --rerun-tasks --stacktrace
      - name: Root test count
        run: |
          find core server -path '*/build/test-results/test/*.xml' -print0 \
            | xargs -0 grep -ho 'tests="[0-9]*"' | tr -dc '0-9\n' | awk '{s+=$1} END {print "root tests:", s}'
```

Third-party actions are pinned by commit SHA in the real file. The root test count is compared by hand with the `PROGRESS.md` baseline at each gate, because CI has no trusted store for it.

### 8.3 `PROGRESS.md` entry template

```text
## Lab L0.<n> gate — <date> — LAB (not device evidence)
Commit: <sha>   JDK: <java -version first line>   Runner: <local | GitHub ubuntu-latest run URL>
$ ./gradlew -p lab labTest --stacktrace
<real output tail incl. BUILD SUCCESSFUL and the family lines>
$ <isolation check 1..4 commands>
<real output>
$ <root test count command>
root tests: <n>   (baseline: <n>)
Oracle status: self-oracled (no independent implementation has agreed yet)
Result: PASSED | BLOCKED(<reason>)
```

---

## 9. What the lab does not prove

- **Nothing about any device.** It proves no speed, no heat, no battery behaviour, no OS power policy, and no radio or overlay behaviour. Every such item stays `NEEDS-DEVICE-VALIDATION` in `PLATFORM_PLAN.md`.
- **Self-consistency, not independent correctness**, until §4.10's agreement is recorded.
- **That the frozen v1 contract is right.** It proves only that it is pinned: W00–W03, W01b and R04 describe what v1 does today, including anything v1 does wrongly.
- **That estimates or weights are good choices.** The simulator shows the laws hold and the first-slice scenarios pass under invented truth profiles.
- **Security against a compromised peer beyond the W08 cases**, and nothing about content fabrication (§6.6).
- **ART, Conscrypt, Network.framework or packaged runtimes.** Those lanes belong to `PLATFORM_PLAN.md`; a family passing on JDK 17/21 is not evidence for them.

---

## 10. Revision 4 amendments (normative; 2026-10-07)

**Status.** Design revision 4 (`REVISION_4.md`) folds the round-3 review findings and the readings the implementation tracks recorded in their ERRATA files into this file. **Where this section and §0–§9 differ, this section wins.** Each amendment names: the section it amends, the old text (quoted or summarised), the new normative text, its source, and the vectors that pin it. "Builder action" marks a change that the code or the vectors do not yet carry; until a builder does it, the lab and this file disagree on that point and the gate that covers it must say so. Nothing here is device evidence. Owner rulings that are still open are named by their id in `OWNER_DECISIONS.md` and are **not** decided here.

ERRATA ids are qualified by file where two files use the same id: `lab/` ids are bare; `desktop/`, `apple/` and `ubuntu-touch/` ids carry the directory (for example `desktop ERR-FX-5`, `apple E-24`).

### 10.1 Process, isolation and the file set

**R4-L-01 (header; R3-CLOSURE-2, ERR-DOC-1).** Old: "Everything a builder needs is here, or in a sibling spec that this file cites by section." New: "This file is not self-contained. It cites sibling specs (`trust.md`, `manifest.md`, `benchmark.md`, `router.md`, `contract.md`, `platforms/*.md`); each sibling is normative only **as amended** by `ASOM_MESH_DESIGN.md` and by this §10. The complete input is the committed file set of R4-L-02 plus the frozen v1 sources."

**R4-L-02 (§0, §4.9; R3-CLOSURE-2, ERR-MAN-3).** Commit manifest. Every file under `docs/design/mesh/` is committed and is part of the spec input: the design, this file, `PLATFORM_PLAN.md`, `OWNER_BRIEF.md`, `OWNER_DIRECTIVES_2026-09-30.md`, `OWNER_DECISIONS.md`, `REVISION_4.md`, the review and disposition files, `TOOLCHAIN_NOTES.md`, the six r0 siblings, `platforms/*.md`, and the directories `manifest-vectors/`, `router-examples/`, `bench-examples/`, `conformance-examples/` and `spikes/`. Builders never write under `docs/`; a docs change is a design revision. §4.9's move of the r0 vectors to `lab/conformance/history/r0/` is **withdrawn**: they stay in `docs/design/mesh/manifest-vectors/` and no lab code reads them (their semantics are regenerated as M02-101..120 and M03-101..178, ERR-MAN-3).

**R4-L-03 (§2.4 check 4 and the root test count; R3-CONFORMANCE-2, ERR-ISO-1, ERR-ISO-3, ERR-CI-1, ERR-CI-2, desktop ERR-ISO-1, ubuntu-touch ERR-UT-ISO-1, apple E-01 and its orchestrator follow-up).** Old check 4: `git diff --exit-code -- core server gradle …` (it compares the working tree with the index and cannot fail on a fresh checkout). New check 4: every protected path (`core server app vault pairing storage ledger client client-cloud sample-client gradle settings.gradle.kts build.gradle.kts gradle.properties .github/workflows/ci.yml`) is compared byte for byte (blob id, executable bit, file set including untracked and deleted files) with the same path at a pinned base commit (`lab/LAB_BASE_SHA`, or the track's own pin file); the check fails loudly when the base is not a full commit id, is absent (shallow clone) or is not an ancestor of HEAD; CI checks out with `fetch-depth: 0` and runs the checker's negative-control self-test first. A deliberate change to a protected path is followed, in a later reviewed commit, by a re-pin recorded in the track's ERRATA re-pin table. Checks 2 and 3 carry positive controls (the same output must list `kotlin-gradle-plugin`, and every mapped project). Root test count: old "must equal the baseline"; new "must be at least the figure in `lab/ROOT_TEST_BASELINE` (140 at r4); a rise passes and is recorded by a reviewed bump of that file". **Not covered (stated):** a root edit already inside the pinned base, and a reviewed move of the pin.

**R4-L-04 (§2.5; R3-CONFORMANCE-3, ERR-ISO-2, desktop ERR-ISO-2, ubuntu-touch ERR-UT-ISO-2).** Old: a builder may make the one-line `ASOM_PURE_JVM` change to the root `settings.gradle.kts` and record it. New: **if check 2 fails, the builder writes `BLOCKED(lab isolation: check 2 failed)` with the real output and stops.** The guard text stays only as the option the owner would be asked to approve. No track has needed it (project-directory mapping passes check 2 in every track).

**R4-L-05 (§4.10, R9; R3-OVERCLAIM-7, R3-CLOSURE-12, ERR-ORA-1, ERR-JSON-8, ERR-XCK-1, ERR-PT-11, ERR-VEC-1, ERR-R6-11, apple E-02, E-16, E-33).** Old: independence = "an author with no access to the generator source", recorded in `PROGRESS.md`. New: an **independent** lane is written in a session whose checkout is sparse and contains only the spec prose (this file's §4 and the sibling sections it cites, as amended) and the vector files under `lab/conformance/` without `lab/tools/`, `lab/*/tools/` and `lab/*/src/`; `PROGRESS.md` records the session id, the checkout command, the sparse path list and the session's file-access log, and the owner confirms it. Agreement from any other lane is tagged **`cross-lane`**, which never clears `oracle: self`. The existing Swift lane (I0a, I0b, I0c and the fix waves) and every same-session Python cross-check are cross-lane or self, never independent; code derived from `IS1`/`FM42` can never clear the tag.

**R4-L-06 (§4.9, §8.1 L0.2; R3-CLOSURE-4, ERR-CLOSURE-4, apple E-03, E-19).** L0.2 is split. **L0.2a:** `:json`, JCS, DSSE/ES256, key formats, verifier steps 1–10 and their M01/M02/M03 signature-layer vectors. **L0.2b:** steps 11–19 incl. 15a, M05, M06 and the file vectors, gated after L0.3's `derive`/`project` and M04. The lab built L0.2b and L0.3 in one work item (no stub); the Swift lane may start from L0.2a.

**R4-L-07 (§3.3, §3.5, §3.10, §8.1, §8.2; R3-CLOSURE-11, ERR-FMT-1…5, ERR-ENV-1, ERR-ENV-2, ERR-W00-1, ERR-R5-1, ERR-GEN-1, ERR-BUILD-1).** Gate formats as the lab implements them: family lines come from `labTest` and read `family <F>: <n normative> vectors, <p> pass, <f> fail, <s> proposed-skipped[, proposed-lane …], oracle: <tag counts>`; `lines` mode prints only `<id> ok|reject <CODE>`, sorted, nothing for a vector that cannot run; `xcheck` prints `xcheck <F>: absent` and exits 0 for a family with no vectors; W01-100..299 come from `java.util.SplittableRandom(1)`, cost `StrictMath.pow(10.0, -9.0 + 11.0 * nextDouble())`, status `proposed`; `:conformance-runner` uses the `application` plugin with `mainClass = xyz.mdhv.asom.lab.conformance.MainKt`; action SHAs are resolved by `git ls-remote` and recorded; additive envelope fields `expectDetail`, `input.lane`, `input.decision` are allowed; W00-001/002 assert the exact set of seven `AsomHeaders` constants; `lines` starts real `AsomServer` instances (127.0.0.1, ephemeral port) only when a server-driven family is named; generators refuse to write when an observation contradicts a hand expectation; the runner's test task is never cached. Pinned by W00-001, W00-002, W01-100..299.

**R4-L-08 (§3.2 `VERSION`; ERR-FX-VER, ERR-FX-FILES).** Unchanged rule (a generated file changes only with a `VERSION` bump). Recorded: M02-113, M02-118, M02-120, M03-131 and M05-109 changed, and M02-121, M02-122, M03-179..215 and later vectors were added, while `VERSION` stayed `0.2.0`. **Builder action (one commit):** bump to `0.3.0`, make the generators read `VERSION` for the file-level `confVersion` only (never `harness.confVersion` inside signed documents), update the Swift `supportedConfVersion` and the cross-lane fixtures, regenerate, re-run `regen_index.py`.

### 10.2 W01b and the frozen v1 server

**R4-L-09 (§3.6 law; R3-CLOSURE-3, ERR-W01B-1…4).** Old: "`row.toEchoHeaders()` equals the response's `X-Asom-*` headers exactly", "W01b-004 … one row for the request". New law: (a) `X-Asom-Served-By` and `X-Asom-Egress` of the response always equal the same headers of the request's **terminal (last) row**; (b) on a non-stream response the full `X-Asom-*` header map equals `toEchoHeaders(terminal row)`; (c) on a stream response (`text/event-stream`) only the commit-time headers (`Served-By`, `Egress`) may be present, `Egress` must be, and no `X-Asom-Cost-*` header is present, while the row's cost fields are checked separately; (d) `expect.ok.rows` pins every row the request appended, in order (W01b-004 appends a failed-candidate attempt row, then the terminal row); (e) the harness waits for the row count to stop growing (the terminal row of a stream is appended after the last byte). Pinned by W01b-002, W01b-003, W01b-004, W01b-007. The observed v1 property (stream responses never carry cost headers although the row does) is owner item **D38** in `OWNER_DECISIONS.md`; it is inside frozen §5.4's commit-time allowance and is not a lab defect.

### 10.3 Strict JSON, keys and the verifier (L0.2)

**R4-L-10 (§4.2; ERR-JSON-1…5, ERR-JSON-9, ERR-FX-CV7, ERR-FX-CV8(e), apple F-2).** Readings made normative:
1. The reject code is that of the **first table row whose condition holds anywhere in the input**. A syntax error (row 1, and a BOM) outranks everything; row 2 is a check of the raw bytes of the whole input (invalid UTF-8 after the value is `INVALID_UNICODE`); bytes after the value are never parsed (`TRAILING_DATA`); the parser keeps scanning past depth 16, iteratively, so rows 4–6 outrank row 7. Pinned by M01-140..153 and M03-197..200.
2. Depth counts containers only: 16 nested arrays or objects (with or without a scalar inside) are accepted, 17 are `MALFORMED_JSON`; a top-level scalar is accepted. Pinned by M01-013, M01-014, M01-107, M01-128.
3. A number lexeme is the maximal run of `[0-9+-.eE]` starting at `-` or a digit; anything other than an integer without a leading zero (`-0`, `01`, `1.`, `1e`, `-`, `1-2`, and the words `NaN`, `Infinity`, `-Infinity`) is `NON_INTEGER_NUMBER`; a token starting with `+`, `.` or another letter run (`truex`, `nullnull`, `Infinityx`) is `MALFORMED_JSON`; more than 16 digits is `NUMBER_RANGE`. Pinned by M01-111..116 and M03-201..214. This supersedes `apple E-07`'s "a lone `-` is `MALFORMED_JSON`".
4. Strict base64 (`b64either`, `b64url`): the empty string decodes to zero bytes; a length of 1 mod 4 is refused; padding is one or two `=` at the end and only when the total length is a multiple of 4; any character outside the alphabet is `ENCODING`. Pinned by M01-301..325.
5. Object equality ignores member order; values outside the profile cannot be constructed.
6. `:json` sets no size cap; every caller sets one (frame and container limits).

**R4-L-11 (§4.5; ERR-MAN-5, ERR-FX-CV5, ERR-FX-CV6, ERR-FX-CV10, apple E-09).** Fingerprint normalisation uppercases ASCII `a`–`z` only and deletes `-` and space; every other character survives and never matches (M03-195, M03-196). Strict SPKI: the fixed 26-byte prefix, `04`, and `x`, `y` both below `p` and on the curve; anything else is `ALG_UNSUPPORTED` (M03-215). Constant-time comparisons compare lengths first (lengths are public) (M03-194).

**R4-L-12 (§4.6 verifier; ERR-FX-CV1, CV3, CV4, CV8, ERR-MAN-1, ERR-MAN-2, ERR-MAN-4, ERR-CLOSURE-5, apple E-05, E-06, E-10, E-11, E-20, E-21, E-22, E-27).** Changes to the normative order:
- **Step 3:** a container that is not an object is `CONTAINER_INVALID`; the shape of `signatures[0]` (not an object, no string `sig`, a non-string `keyid`) is `CONTAINER_INVALID` **here**, before steps 4 and 5; an empty `signatures` array is step 5 (`SIGNATURE_COUNT`).
- **Step 4 and step 11:** a "newer major" is a canonical decimal above 1 with no leading zero; `…v02+json` is `PAYLOAD_TYPE_UNSUPPORTED`, `asom.manifest/02` is `SCHEMA_INVALID`.
- **Step 6:** a decoded payload over 262,144 bytes is `TOO_LARGE` (unit test; the container limit is M03-144).
- **Step 7:** a FILE `signer.spki` that is not a string is `CONTAINER_INVALID`; one that is not strict base64 is `ENCODING`. The `keyid` test precedes the SPKI shape test, as written.
- **DER codec (signature layer):** `r = 0` or `s = 0` decodes and fails at step 8 (`SIGNATURE_INVALID`); a long-form length is `SIGNATURE_ENCODING`.
- **Step 11:** at `schemaMinor` 0 an unknown member anywhere in the payload is `SCHEMA_INVALID` (M03-158); above the known minor it is tolerated and counted (M02-107); the five P7 names are invalid at any minor (M03-159). A FILE `presentation` is exactly `{issuedAtMs}` at any minor (M03-193; M02-121 is the accepted sibling). Typed-decoder strictness of `apple E-22` applies (challenge is 32 bytes of strict base64url; `bench.tiers` unique and in tier order; the sustain tier is one of the measured tiers; `energy` is `null`; a tier's `sha256` equals the compiled-in pin; timestamps below 2100-01-01).
- **Step 15:** `consistency()` additionally requires every copy in the body to equal its `bench` twin: `producer.harness.confVersion`, `producer.engine.name`, `producer.engine.buildFlags`, `device.memory.totalBytes`, `device.os.family`, `device.os.version`, `device.vendor`, `device.model`, `device.soc.name`; `producer.engine.commit` and the bench commit must be prefixes of one another; `device.class` is not tied (open enum). Arithmetic overflow anywhere in step 15 or 15a is `INCONSISTENT` (M03-169). Pinned by M03-179..190, M02-122, M02-113 (and M03-131, whose two copies now change together).
- **Step 15a:** `ctx.confFloor` has no default; the caller supplies it (vectors use `0.2.0`).
- **Step 15c:** an evidence item whose JCS form exceeds 32,768 bytes is `CONTAINER_INVALID`.
- **Steps 17 and 18 (the tier).** Old: "17 tier = A1 if subject.keyStorage in {strongbox, tee, secure-enclave, tpm} else A0 (self-reported; evidence unused); 18 tier >= ctx.requiredTier else TIER_INSUFFICIENT". New: "17 `displayTier` = A1 if `subject.keyStorage` ∈ {strongbox, tee, secure-enclave, tpm} else A0 (a self-reported label, shown as such); `attestedTier` = A0 (evidence is unused; A2 is deferred). 18 `attestedTier >= ctx.requiredTier` else `TIER_INSUFFICIENT`." `Verified.tier` is `displayTier`. With A2 deferred, `requiredTier` A1 and A2 always reject. This makes the lab follow design §5.1 and `manifest.md` §9.1 ("A1 is treated exactly as A0 for every decision"). Pinned by M03-191, M03-192, M03-124; M02-118 and M02-120 now use `requiredTier` A0; apple cross-lane fixture M02-930.

**R4-L-13 (§4.7 signer and projections; R3-CLOSURE-1, ERR-CLOSURE-1, ERR-FX-CV9, apple E-18, E-40, ERR-FX2-ASC04, ERR-FX2-ASC09).** `projectFile(bodyOwn)` is defined:
- `audience` = `file`; `seq` omitted; `subject` = `{nodeId: nodeId(exportKey), keyAlg: "ES256", keyStorage: "ephemeral"}`; `device.platformIds` and `device.os.securityPatch` removed;
- the bench projection for FILE: `run.startedAtMs`, `run.endedAtMs` and every `tiers[].startedAtMs` truncated to the UTC day; `run.batteryStartPermille`, `run.screenOn`, `device.osBuild` and `device.gpuDriver` become `null`; every other bench member is kept;
- `results` = `project(derive(benchFile), FILE)`, so `measuredAtMs` is the truncated tier start and `conditions` holds only `charging` and `thermalStart`;
- step 15a for FILE re-derives exactly this.
Pinned by M02-110..112, M02-115, M03-129, M03-132, M03-134, M03-135, M03-160..166 and M06-201..204 (the spec's `M06-file-001..004`; the id grammar is `<family>-<number>`). Signer rules: load, compare, persist and sign `seq` under one lock per store, write a private temp file, sync the directory after the atomic rename; `nextSeq` is at least 1, refuses a negative clock and anything above 2^53 − 1; **a FILE document is never signed by the node key**, and the per-export key is generated inside `signPresentation` (production path). **Builder action:** both lanes accept an injected export key for tests; the production entry point must not.

**R4-L-14 (§4.1 schema patches; R3-CLOSURE-1(c), ERR-BENCH-1, ERR-SCHEMA-1, apple E-26).** The `asom.bench/1` patch list is normative as `lab/manifest/tools/patch_schema.py` labels it (B1..B8: `field`, `custom`, `derived`, `render`, `textSha256` forbidden and absent; raw samples only; "not measured" is an explicit `null`; `platform` gains `windows` and `ubuntu-touch`; `shell` names the daemon, standalone, CLI and app shells; `planSha256` required; `tiers` an array in tier order with per-tier `startThermal`, `restarts`, `swapDeltaBytes`; sustain windows `[tStartMs, tokens, micros, thermalCode]`; `energy` = `null`). P10 is a `$comment` on `schemaMinor`; P11: only the sustain tier's result carries `sustained`; PP1 and PP2 as `ERR-SCHEMA-1`. **New PP3 (builder action, no vector):** the public derivative's `osFamily` enum gains `windows` and `ubuntu-touch`. The Kotlin decoder is normative; the JSON schemas are test oracles.

### 10.4 Benchmark core (L0.3)

**R4-L-15 (§5).** `benchmark.md` §23 (revision 4 amendments) applies to `:bench-core`. The M04 vectors that pin lab-implementation detail that no spec text states (the executor traces M04-301..345, the quick and ci plans M04-401 and M04-403, the run-today consent sheet M04-425) are **lab-implementation vectors**: a second lane is not required to reproduce them until their inputs are transcribed into `benchmark.md` (apple LF-4).

### 10.5 Router, tracker and simulator (L0.6)

**R4-L-16 (§6.1–§6.4; R3-CLOSURE-5, ERR-R6-3, ERR-R6-4, ERR-R6-9, ERR-R6-10, ERR-CLOSURE-5).**
- Additive local types and fields (never serialised to a peer): `SelfSituation.hasEngine/backend/localQueueMs`; `NodeView.maxContextTokens/batteryDesignMilliWh/stateRegressed/powerFreshness`; `FileKind` and `FileKey.kind`; `MeshQuery.embeddingIdentity`; `TrackerState`, `CapCounter`, `FrozenCloudView`, `Estimate`, `ScoreBreakdown`, `Exclusion`, `CapDelta` as the lab defines them. A peer on battery with no design capacity, an unknown model context and an embeddings request with no identity are excluded (F8 `battery-capacity-undefined`, F5, F16).
- `classCeiling` values: none exist; `MeshConfig.classCeilings` and `signedReferenceP90` are empty, so the `capRef` term is absent (a prior is `claim × disc / 1000`) until the owner supplies values (**D40**). `confFloor` and `knownBadConf` belong to the manifest verifier (R4-L-12).
- The 1-in-10 new-peer cap of design T5 is **withdrawn** for mesh-1 (see design R4 note in §4.2): RL11's 1-in-4 cap on UNVERIFIED keys is the only new-peer cap.
- Error order (`MeshRouter.errorFor`): a v2 code when SELF was excluded for it (`THERMAL_HOLD`, `MODEL_OOM`, `CONTEXT_OVERFLOW`), then `ALL_PROVIDERS_COOLING`, then the cloud tier's own v1 code, then `LOCAL_ENGINE_ABSENT`, `MODEL_UNKNOWN`, `NO_PROVIDER_KEY`. No new code.
- The v1 `Router` is called unmodified through a throwaway instance per plan; RL6 compares a projection of the plan.
- A claim curve with more than 4 points or contexts that do not strictly ascend is F8 `claim-curve-invalid` (R01-084..087).

**R4-L-17 (§6.5; ERR-LP-1…4, ERR-LP-5, ERR-LP-7, ERR-LP-8, ERR-FX-RT-5, ERR-FX-RT-6, ERR-FX-RT-7).**
- An `X-Asom-Fallback` with an empty provider list leaves `P` empty (L01-011).
- The presence FSM consumes abstract named inputs; which host observes which input is device work. `DRAINING` is observable in the step that starts it.
- Receiver strictness for `asom.state/1`: the members of the §7.2 example are required (`manifest` and `batteryBand` may be `null`, not absent); a value outside a closed enum, a wrong type or an out-of-range integer is refused; unknown members are ignored and never stored; `sampledAgeMs` above 60,000 is accepted and clamped.
- `seq` strictly below the last seen is EXPIRED; an equal `seq` is a repeat (W07-072, W07-073). A re-dial forgets only the highest `seq`; GOAWAY and a regressed `seq` survive it (R06-055..058).
- **Pessimistic substitution, corrected.** Old: "`thermalBand = max(last, 1)` if last ≥ 1" (a no-op for every `last` it applies to). New: "`thermalBand = min(2, last + 1)` if last ≥ 1", the same rule as `queueBucket`. Reason: the guard "if last ≥ 1" is meaningful only for a formula that changes values ≥ 1. **Builder action:** W07-075 pins the literal no-op and must be regenerated; `LiveStateTest` follows.
- Fields a digest does not carry (power source, charging, battery band) age from the last FULL state; `powerFreshness` and `digestFreshness` are classified separately and the candidate's freshness is the worse of the two (R01-088..091, R02-049, R02-050, R06-061..068).
- `retryAfterMs` starting values (PROVISIONAL): 30,000 ms for a condition, model or scope decline; 5,000 ms for busy and duplicate; a presence cause uses the remaining LP-2 hold-down clamped into 5,000..600,000.

**R4-L-18 (§6.6 tracker; ERR-R6-5, ERR-R6-6, ERR-FX-RT-1, ERR-FX-RT-3, ERR-FX-RT-4, apple E-34, E-38, ERR-FX-M08-1, ERR-FX-M08-2, ERR-FX2-ASC01, ERR-FX2-ASC10).**
- Key semantics: the tracker is keyed by (peer, file); entries that differ only by backend are merged (worst state wins, any memory mark applies, strikes are the maximum, cap counters are summed; `disc` and the penalty latch count FILES). The backend selects the claim row and the ceiling only. Ratios kept across a backend switch: no new rule; `DISCARD_SETTINGS` keeps mismatched observations out. Pinned by R01-082, R01-083, R02-047, R02-048, R03-032, R03-033.
- "Claim decode" is `decodeAt` at contextTokens = P; one tracked ratio scales every curve point; between two points `floor((lo.rate × (hi.ctx − p) + hi.rate × (p − lo.ctx)) / (hi.ctx − lo.ctx))`, clamped outside the curve. **Builder action:** add an M08 vector at a non-integer interpolation point so both lanes are pinned on the floor.
- The tracker's E1 is the warm path (rtt plus transfer, no handshake term).
- `Observation` has no field that can carry a peer-reported count or timing.
- A new claim seq restarts the window W only; the discard-budget record and strikes survive (M08-068, M08-069). DISCREPANT is inherited also when no claim body was recorded before (old: inheritance required a recorded `claimSeq`; stricter now). **Builder action:** JVM `wasBad`; a vector.
- A tripped discard budget turns UNVERIFIED, CORROBORATED and WEAK into WEAK and **never lifts DISCREPANT**. **Builder action:** add the `state` and `penalty` vectors of `apple ERR-FX2-ASC01`; the JVM behaviour is not observed in a vector yet.
- The penalty latch: checked after every observation (kept or discarded); a running penalty is not extended; durations 7, 14, 28 … days per latch; the repeat counter never resets; durations saturate. `view()` refuses a `disc` outside 0..1000 and never returns a rate above the claim.
- Pinned by M08-065..082.

**R4-L-19 (§6.6 padding; R3-OVERCLAIM-1, ERR-R6-1, apple E-39).** Old (M08-017 row): "padding at most offsets the factor `bptCap / bpt = 2` (the stated residual)". New:
- `outBytes` counts the answer text after normalisation: NFC, Unicode `Cf` and control characters removed, whitespace runs collapsed to one space, trimmed.
- The stated residual is: below the cap, padding can inflate the observed ratio by up to `min(RATIO_CAP / 1000, maxTokensOffered × bptCap / trueOutBytes)`; with the spec's own numbers that is about 5×, not 2×. Placement still never exceeds the claim (`effRatio = min(1000, …)`).
- A requester-side length check (discard with a strike any answer longer than k × the requester's own per-(app, model) output-length EWMA) is **DEFERRED**: k needs simulation; recorded in `REVISION_4.md`.
**Builder action:** implement the normalisation in both lanes; rewrite M08-017's expected text; add M08 vectors for trailing-whitespace padding, zero-width padding and a short real answer under a large `max_tokens`.

**R4-L-20 (§6.7–§6.9 laws and simulator; ERR-R6-7, ERR-R6-8, ERR-R6-13, ERR-R6-15…24, ERR-FX-5).** RL9 excludes cap swaps and the probe-only partition; RL10 holds strictly only in calm worlds and, with the cap active, for the cheapest and auto blocks; RL13 is measured among equal primary keys; RL8 is checked with unlimited attempts, for other nodes' ranks, and at file level only for the rate and warm-model inputs; RL19 is Jain's index over on-time completion (a weak law in a simulator with no per-app scheduling); the fault kinds have the semantics of `ERR-R6-18`; ids use a fifth random split taken after the four; SC01's share is measured over a 72 h run at 15 requests an hour; B4 (hindsight) is the same plan from the peers' truth over the same snapshot; scenarios live in `lab/mesh-sim/scenarios/`. The R6-FINDING-COLD behaviour (an honest lender that unloads between uses reaches DISCREPANT) is owner item **D35**; until it is ruled, `FindingsTest` pins it.

### 10.6 Wire, session, TLS and ledger (L0.4, L0.5)

**R4-L-21 (§7.1, §7.2; ERR-PW-1…8, ERR-PW-10, ERR-PW-11, ERR-PW-13, ERR-PW-14, ERR-PS-1, ERR-FX-2).**
- A length below 5 and a length above 16,777,221 are both `FRAME_TOO_LARGE` and close, raised once 5 bytes are in; the length is unsigned.
- Class limits: JSON 1,048,576 (every type but `INFER_BODY` and `INFER_CHUNK`, incl. `PAIR_*` and `MANIFEST`); `INFER_BODY` 8,388,608; `INFER_CHUNK` bounded by the frame maximum. Over the limit: `FRAME_TOO_LARGE` and close, before any payload byte is buffered.
- The "Dir, stream" column of §7.2 is normative per type; a violation is `ERROR PROTOCOL_ERROR` and close. **The TLS client is the requester and the TLS server the lender for the life of one connection; a node borrows from a peer by dialling it.** `GOAWAY`, `ERROR`, `REVOKE_NOTICE` go both ways.
- Every JSON payload is one strict JSON object; an empty JSON payload is `MALFORMED_JSON`; receivers do not require JCS.
- Bounds of `ERR-PW-7` (HELLO versions 1..255; `proto` exactly `asom-mesh/1`; `name` 1–32 code points, no control character; `sw` grammar; integers 0..2^53 − 1; **all five `limits` members required**; model ids `[A-Za-z0-9._:-]{1,128}`; an optional member present as `null` is `WRONG_TYPE`).
- Identifiers: `sessionNonce` is 22 characters (16 bytes); `nodeId`, `challenge` 43; `attemptId` 22; decoded strictly. Endpoint addresses are IP literals; unknown entries of `features` and `granted` are dropped; an unknown value of a closed single-valued enum is refused.
- Extensions (0x80–0xFF) are skipped whatever their stream, mode or payload; producers never emit one.
- `ERROR` codes and `GOAWAY` reasons are stored only as closed-set members; anything else is stored as `ERROR:UNKNOWN` / `GOAWAY:unknown`, and a CONTROL row's `meshCode` must match that grammar (L02-022, L02-023).
- W06 vectors are `normative` (this file wins over `platforms.md` §5's "proposed").
Pinned by W06 (incl. W06-001..004) and the wire side of W07.

**R4-L-22 (§7.3).** Pairing frames, proof and SAS follow `trust.md` §16 (revision 4 amendments): commit-before-reveal and the typed code on D are normative from r4.

**R4-L-23 (§7.4 TLS; R3-OVERCLAIM-6, R3-CLOSURE-7, ERR-PL-1…7, ERR-PL-9, ERR-PL-12…15, ERR-FX2-TT-3).** Old: "no resumption (a fresh `SSLContext` per dial, and the server issues no tickets)". New: "no resumption: the dialler builds a fresh `SSLContext` for every dial and the listener builds a fresh `SSLContext` (own key manager, trust manager and empty caches) for every accepted connection. A JSSE listener may still send TLS 1.3 tickets (no scoped switch suppresses them on JDK 17 or 21); they cannot be redeemed because the issuing context is gone. W08 asserts, per lane: no `pre_shared_key` in any ClientHello of the honest dialler; a hostile client that replays a ticket gets a full handshake with the verifier invoked once." Measured on JDK 17.0.12 and JDK 21.0.10 (LAB); other builds, Conscrypt and Network.framework are UNVERIFIED. Further W08 readings: "a client CertificateVerify in every session" is proved by what the server observes (a 2-certificate peer chain, exactly one verifier call, an Accepted verdict); hostile ClientHellos are tapped separately and must have been seen; the signature scheme is scoped by `SSLParameters.setSignatureSchemes` where the JDK has it and otherwise enforced by the verifier; ALPN is enforced by parameters, by a key-manager gate (no certificate is presented unless the negotiated protocol is `asom-mesh/1`) and after the handshake, and an absent ALPN may end with `handshake_failure`; every verifier refusal is a `CertificateException` with no message (alert `certificate_unknown`), and W08's `alert-uniform` law, not a constant getter, is the evidence; the 5 s handshake timeout is one deadline for the whole handshake; a `legacy_session_id` is evidence of resumption only when it repeats across tapped connections; SNI token gating (S-A11) is **not adopted** (design T13 stays open; the spike lives in test sources only).

**R4-L-24 (§7.5 ledger model; R3-CLOSURE-6, R3-CONFORMANCE-1, ERR-LL-2, ERR-LL-3, ERR-LL-5, ERR-FX-1, desktop ERR-SINK-1, desktop ERR-FX-11, ubuntu-touch ERR-FX-UT-2).**
- **Session grouping rules (adopted):** the dialler generates its session id before the `DIAL` intent and writes it in the `DIAL` rows; the listener writes `SESSION` open after the first frame that names the session (`HELLO`, whose `sessionNonce` is the id) and before any reply, or under a locally minted id when the session ends without one (refusal, timeout); for a pairing connection the session id is base64url of the first 16 bytes of the `PAIR_HELLO.nonceS` member as sent (under `trust.md` R4-T-10 that member carries the commitment `C`), which both ends know once `PAIR_HELLO` has crossed, and the dialler's earlier rows are grouped under a local id until then; attempt rows carry the session that carried them; a session's id never changes after its open row. Pinned by L02-013 and L02-020.
- **Where the session id is stored is owner item D34.** The lab carries it in one additional nullable column `sessionId` (21 mesh columns). The alternative is a fixed `routeDetail` prefix `session=<id>` with 20 columns. Old text ("so no extra column exists") stands only if D34 rules the prefix.
- `costEst` is written as a JSON string holding the shortest round-trip decimal; every other number is an integer; a row line is the JCS bytes of the row; every column is present (null when unset).
- Row values of `ERR-LL-5` (per-frame rows have no phase; `SESSION` `meshCode` `established|pairing|close|close:ledger-failure`; statuses 200, 599, 503, 403; `INBOUND_REFUSED` keeps its count as `refused:<n>`; session and control rows use `callerPkg` `peer:<nodeTag>`; every mesh row has `egress = peer`; a request's terminal row has `bytesOut 0`).
- `callerPkg` `self-ui:` forms (once D25 is ruled): only the Ubuntu Touch asom app's own screen and the iOS asom app's own screen (R3-CONFORMANCE-1); never iOS host apps. The desktop owner CLI is `local-uid:`/`local-sid:…(acl)`.
- **JSONL sink, torn tail.** Opening a file whose last byte is not `\n` truncates it to just after its last `\n` (the cut bytes were never acknowledged); a failed append truncates back to the length before it and throws; a failed truncation poisons the sink; one writer per file. The torn-tail scan has no length cap (bounded memory). Only process death is claimed, never power loss.

**R4-L-25 (§7.6; R3-OVERCLAIM-3, ERR-LL-8, ERR-LL-9, ERR-PL-10, ERR-PI-1, ERR-PI-2, ERR-PI-6, ERR-PI-8, ERR-PI-10, ERR-PI-11, ERR-PS-2, ERR-PS-3, ERR-PS-8, ERR-PS-16, ERR-PI-7, ERR-FX-5).**
- **L-L15 needs three instruments that are independent of the row writer:** a socket tap (raw bytes), a plaintext tap at the frame layer that counts `9 + payload` per frame by its own formula (the rows' application bytes must equal it), and `overheadBytes` taken from the transport meter (engine `bytesProduced`/`bytesConsumed`, counted as what the channel actually took or delivered), never derived from the rows. The overhead must lie within `[22 × records, records × R + handshake allowance]`, both ends inclusive. Old: "The overhead is network bytes minus the application bytes of the frames" (true by construction).
- **ESTIMATED form, recalibrated (PROVISIONAL).** Old: "`22 × ceilDiv(appBytesOfFlush, 16384)` per flush, plus 4,096 bytes per direction". New: "`R × ceilDiv(appBytesOfFlush, Lmax)` per flush plus a handshake allowance of 8,192 bytes per session, where `R` and `Lmax` are the per-stack calibrated record expansion and largest one-record write; for JSSE 17.0.12 and 21.0.10 with TLS_AES_256_GCM_SHA384 (LAB) `R = 38`, `Lmax = 16,367`, close_notify 40 bytes; for an uncalibrated stack `R = 22` and `Lmax = 16,384` (the RFC 8446 minimum, labelled as such)." The 22-byte figure [F51] is a **lower bound**, not the JSSE cost; the cause of the extra 16 bytes was not examined. Measured handshakes: 2,530–2,540 bytes (dialler) and 4,440–4,460 bytes (listener) on JDK 21. **Builder action:** `:ledger-model`'s ESTIMATED form.
- Handshake overhead rides on the dialler's `DIAL` outcome and the listener's `SESSION` open; the close row carries `network − plaintext − handshake`, written after both close alerts.
- A refused frame gets no row of its own: the answering `ERROR` gets the CONTROL row and every uncovered application byte (refused frames, bytes after a failure, a partial frame) is carried by the `SESSION` close row's `bytesIn`/`bytesOut`; a row may claim bytes of a frame whose write then failed (counted and subtracted by the oracle).
- DIAL outcome mapping on a JVM: verifier refusal or nothing presented → `pin-mismatch`; no ALPN, no TLS 1.3, no client-auth request, failed or cut handshake → `not-tls`; the 5 s budget → `timeout`; peer alert, socket failure, refused connect → `refused`; `local-network-denied` and `firewall-blocked` come from platform layers.
- Network.framework overhead is **ESTIMATED** until spike IA07 passes (also design §8.4).
- A pairing-mode connection admits at most 16 inbound frames (`PAIR_*` and extensions together); the 17th is `ERROR PROTOCOL_ERROR` and close. The budget for `EXT_IGNORED` and `REVOKE_NOTICE` rows on an ESTABLISHED session is **not built** (open; `REVISION_4.md`).

**R4-L-26 (§7.7; ERR-LL-4, ERR-LL-6, ERR-LL-7, ERR-LL-11, ERR-PS-11, ERR-PS-19, ERR-PI-15).**
- **FC-5.** Old: "`CANCEL` the stream, send no `INFER_END`, then FC-2". New: "send no frame (no `INFER_END`, no `CANCEL`: `CANCEL` is a requester frame), cancel the engine locally, then FC-2." Pinned by L02-017.
- **FC-6 (new): requester outcome append fails.** The attempt keeps its intent row (outcome unknown), the session is not closed, the node ledger is marked unavailable until the next successful write.
- **L-L4** checks the class label only (the row is durable before the frame is sent, so a crash can leave a row for bytes that never left).
- **L-L9** reads: intent rows carry no bytes, tokens or cost (old: "summing over all rows equals summing over outcome rows only", false once per-frame rows exist).
- **L-L11** kills both simulated processes at every instrumented step and requires each node's durable rows to be a prefix of the failure-free run; the death of one node while the other continues is not modelled.
- **L-L13** is scoped to what depends on the failed row (no byte of that attempt, no frame on that session after a control-row failure, no engine read after a lender intent failure, no SYN after a DIAL intent failure; a sink that keeps failing sends no content frame at all); after a control-row failure the socket may carry at most one close_notify record of the calibrated size.

**R4-L-27 (§7.2 timers and limits).** The session timers and limits (idle, maximum age, body wait, request wait, cancel grace, write stall, stream and handshake limits, LRU sizes) are normative in `trust.md` §16 (R4-T-08). Note: ERR-PI-3's maximum session age of 24 h is **superseded** by design §4.3 and T9 (30 min). **Builder action:** `Session.tick` max-age constant.
