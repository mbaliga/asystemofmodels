# lab: the pure-JVM mesh lab

A **separate Gradle build** (its own `settings.gradle.kts`) that pins today's frozen v1 behaviour as conformance
vectors and hosts the mesh work items L0.2 to L0.6. **It ships nothing to a device and changes no shipped module.**
Authority: acting decisions AD-3 and AD-4 (`docs/design/mesh/OWNER_DIRECTIVES_2026-09-30.md`); spec:
`docs/design/mesh/LAB_SPEC.md`. Known spec defects and the reading taken for each: [`ERRATA.md`](ERRATA.md).

## How to run

Run everything from the repository root with the root wrapper. No Android SDK is needed (and none is used).

```sh
./gradlew -p lab labTest --stacktrace          # every lab test; prints one `family <F>: ...` line per vector family
./gradlew -p lab :conformance-runner:test      # the suite alone (never cached, never up to date)
./gradlew -p lab :conformance-runner:run --args='lines W00,W01,W01b,W02,W03,R04' --quiet
                                               # one line per vector: `<id> ok` or `<id> reject <CODE>`
python3 lab/tools/xcheck.py lab/conformance    # the Python cross-checker (standard library only)
lab/tools/isolation.sh [--with-sdk]            # isolation checks 1-4 (4 against the pinned base) + dependency law
python3 lab/tools/isolation.py --selftest      # negative controls of the pinned-base check
./gradlew -p lab :conformance-runner:genVectors && python3 lab/tools/regen_index.py
                                               # regenerate the RECORDED vector files, then INDEX.json (bump VERSION)
```

JDK 17 and 21 are both gates (CI pins 17; `.github/workflows/lab.yml` runs both). The report of the last suite run is
written to `lab/build/conformance-report.txt`.

## Layout and ownership

| Path | Contents | Track |
|---|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` | the isolation mechanism (LAB_SPEC 2): the five pure-JVM root projects mapped **by directory**, redirected build dirs for them | lab-skeleton |
| `LAB_BASE_SHA`, `ROOT_TEST_BASELINE` | the pinned base for isolation check 4; the root test-count floor (139) | lab-skeleton |
| `json`, `bench-core`, `manifest` | empty shells today (`LabModule` marker + shell test); packages `...lab.json`, `...lab.bench`, `...lab.manifest` | L0.2 / L0.3 |
| `ledger-model`, `mesh-policy`, `mesh-proto` | empty shells; packages `...lab.ledger`, `...lab.policy`, `...lab.proto` | L0.4 / L0.5 |
| `mesh-router`, `mesh-sim` | empty shells; packages `...lab.router`, `...lab.sim` | L0.6 |
| `conformance-runner` | the runner and the L0.1 families (W00, W01, W01b, W02, W03, R04) against the real `:server` and `Router` | lab-skeleton (L0.1) |
| `conformance/` | vector data, `VERSION`, `INDEX.json`, TEST-ONLY keys; see [`conformance/README.md`](conformance/README.md) | lab-skeleton (L0.1); later tracks add their families |
| `tools/` | `xcheck.py`, `isolation.py`, `isolation.sh`, `check_law.py`, `regen_index.py` | lab-skeleton |

Every module builds and tests green as an empty shell, and `settings.gradle.kts` already lists all nine, so parallel tracks
fill disjoint directories without touching the shared build files. The dependency law of LAB_SPEC 1.2/1.3 is enforced by
`tools/check_law.py` (only `:conformance-runner` may depend on `:server`; no `android.*`; nothing listens by default).

## Evidence labels (never dropped)

- `LAB`: run in this build environment, on a JVM, against the real v1 code where the spec says so.
- `SIMULATED — NOT DEVICE EVIDENCE`: simulator output (L0.6).
- `CI (hosted VM) evidence`: a hosted-runner run, cited by URL.
- `CI-APPROX`: a local approximation of a CI condition (for example check 2 with a directory standing in for the SDK).
- `oracle: self`: the vector's expected value was produced by this project's own generator or reasoning. It stays `self`
  until an implementation whose author had no access to the generator source agrees (LAB_SPEC 4.10). Kotlin and
  `xcheck.py` agreement written in one session never clears the tag.

## What the lab does not prove (LAB_SPEC 9)

- Nothing about any device: no speed, heat, battery, OS power policy, radio or overlay behaviour.
- Self-consistency, not independent correctness, until an independent lane has agreed.
- That the frozen v1 contract is *right*. W00 to W03, W01b and R04 describe what v1 does today, including anything v1
  does wrongly (for example the streaming header/row difference recorded in ERRATA `ERR-W01B-1`).
- That estimates or weights are good choices (L0.6).
- Security against a compromised peer beyond the W08 cases (L0.5).
- ART, Conscrypt, Network.framework or packaged runtimes: those lanes belong to `PLATFORM_PLAN.md`; a family passing
  on JDK 17 and 21 is not evidence for them.
