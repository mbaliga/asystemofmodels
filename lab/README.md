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

JDK 17 and 21 are both gates (CI pins 17; `.github/workflows/lab.yml` runs both). A gate entry in `PROGRESS.md` is complete only when both JDKs have a result in it, or when the entry says in words that one half is open. The report of the last suite run is
written to `lab/build/conformance-report.txt`.

## Layout and ownership

| Path | Contents | Track |
|---|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` | the isolation mechanism (LAB_SPEC 2): the five pure-JVM root projects mapped **by directory**, redirected build dirs for them | lab-skeleton |
| `LAB_BASE_SHA`, `ROOT_TEST_BASELINE` | the pinned base for isolation check 4; the root test-count floor (139) | lab-skeleton |
| `json` | `...lab.json`: strict JSON, JCS, strict base64 (L0.2a). 42 tests | lab-json |
| `bench-core`, `manifest` | `...lab.bench` (M04 derivation, governor, consent, executor, text renderer; 41 tests) and `...lab.manifest` (DSSE, ES256, the verifier, projections, the TEST-ONLY signer; 36 tests) (L0.2, L0.3) | lab-manifest |
| `ledger-model`, `mesh-policy` | `...lab.ledger` (destination sets, frame rows, `LabRouteRecord`, the sink discipline; 67 tests) and `...lab.policy` (live state, availability FSM, lender decision; 32 tests) (L0.4) | lab-ledger-policy |
| `mesh-proto` | `...lab.proto`: `wire` (frame codec, messages), `trust` (DER certificates, `verifyPeerChain`, peer registry), `pairing`, `tls` (JSSE TLS 1.3 transport, record tap), `transport`, `session` (per-frame ledger writer). 185 tests; `LabModule.kt` and its shell test are the only leftovers of the original shell | L0.5 (proto-wire, proto-trust, proto-tls, proto-session) |
| `mesh-router`, `mesh-sim` | `...lab.router` (filters, scoring, failover, reducers, claim tracker; 35 tests) and `...lab.sim` (the simulator; 36 tests, SIMULATED) (L0.6) | lab-router-sim |
| `conformance-runner` | the runner and every family listed in `conformance/README.md` (L0.1 against the real `:server` and `Router`, plus the families of L0.2 to L0.6 over the lab modules; 2013 tests) | lab-skeleton (L0.1); later tracks add adapters |
| `conformance/` | vector data, `VERSION`, `INDEX.json`, TEST-ONLY keys; see [`conformance/README.md`](conformance/README.md) | lab-skeleton (L0.1); later tracks add their families |
| `tools/` | `xcheck.py`, `isolation.py`, `isolation.sh`, `check_law.py`, `regen_index.py` | lab-skeleton |

All nine modules are built (test counts above are `./gradlew -p lab test` at the pinned base `f6a8f1ef`, JDK 17.0.12 and JDK 21.0.10 alike, 0 failures; `conformance-runner` runs 2013 tests of which 7 are skipped by design: the six PROPOSED vectors W00-100 to W00-104 and W01b-reach, and `RegenerateVectors`, which only runs under `genVectors`). The README said until 2026-10-07 that seven of them were "empty shells"; that went stale as the L0.2 to L0.6 tracks landed (see the PROGRESS entry "Corrections to the record, 2026-10-07 (fix-docs)"). The dependency law of LAB_SPEC 1.2/1.3 is enforced by
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
