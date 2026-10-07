# ubuntu-touch: the UT-0 scaffold of asom on Ubuntu Touch

**Status: scaffold. It ships nothing.** No OpenStore upload, no release, no signing, no listener, no key, no model download.
Every artefact it builds is UNSIGNED and named so. Authority: acting decisions AD-3 (placement), AD-4 and AD-5
(`docs/design/mesh/OWNER_DIRECTIVES_2026-09-30.md`), roadmap section 14 item 7, and the platform section
`docs/design/mesh/platforms/ubuntu-touch.md` (sections 3 to 10) as amended by `docs/design/mesh/PLATFORM_PLAN.md` section 7.
Known spec defects and the reading taken for each: [`ERRATA.md`](ERRATA.md).

## What Ubuntu Touch is in the mesh (the tiers)

| Tier | What it is | State here |
|---|---|---|
| **UT-0** | A click scaffold that proves the shared JVM node code runs in the Ubuntu Touch userland (CI, arm64) and, on an owner device, under click confinement. | This directory. Ships nothing. |
| **UT-1** | A **foreground-only requester and verifier** for the app's own chat screen. Borrows from paired lenders. | Not started (entry: M1 green, D14 part A, D23 rows, UT-D1 to UT-D6, the v4 design session). |
| **UT-2** | Optional: local CPU engine, `quick` benchmark, a lend screen. | Unscheduled (UT-D7). |
| **Never** | An unattended lender, a cross-app daemon, BYOK keys, a GPU/NPU engine, a 20.04 build. | The reasons are in the platform section. |

The app runs **only while it is open on screen**: Lomiri freezes an unfocused app and everything in its cgroup, and repowerd
suspends the system about 4 s after the display goes off (UF15, UF20, UF21). That is why the node is a child of the UI, talks to it
over a private pipe, and does nothing on the network until the owner asks.

## Layout

| Path | What it is |
|---|---|
| `jvm/` | A SEPARATE Gradle build (`settings.gradle.kts` maps `../../core/*`, `../../lab/*` and `../../desktop/node-core` by directory; never the composite-build include). `:ut-host` holds `Main`, `ControlChannel`, `NodeLifecycle`, `UtPaths`, `SelfTest`, `UiProjection`, `ErrorMapping` (+ `NodeSession`, `FakeNode`, `Stdio`). Gate: `./gradlew -p ubuntu-touch/jvm utTest`. |
| `conformance/utc/` | The families UTC01 to UTC05 (framing, lifecycle, projection, errors, hygiene), `VERSION`, `INDEX.json` (sha256 list: detects an incomplete checkout, authenticates nothing). Every vector carries an oracle tag; all are `self`. |
| `runtime/` | `temurin.lock` (the aarch64 Temurin 21 tarball, URL and sha256, VERIFIED against the Adoptium API and by hashing the download), `jlink-modules.txt`, `jlink.sh` (cross-jlink, size and glibc assertions, `rt.sha256`), `jvm.options`. |
| `plugin/` | The C++ QML plugin `Asom.Bridge` (Qt 5.15): `NodeProcess` (spawns the JVM after checking `rt.sha256` and `jar.sha256`; 1 MiB line cap both ways), `DisplayKeeper` (`keep-display-on`), `LifecycleForwarder` (Qt state to `lifecycle` frames, 1 s heartbeat). |
| `qml/` | The UI against a token seam: `tokens/Tokens.qml` (violet `#8E7BFF` / cyan `#35E0FF`, each with a shape and a label; no red/green meaning), `Main`, `StatusPage`, `BorrowPage`, `PeersPage`, `PairPage`, `LedgerPage`, `NodeModel`, `StatusHeader`, `Glyph`, `js/{Frames,ErrorText,Provenance}.js`. |
| `clickable.yaml`, `CMakeLists.txt`, `manifest.json.in`, `asom.apparmor.in`, `asom.desktop.in`, `assets/` | The click: framework `ubuntu-touch-24.04-1.x`, policy groups `networking`, `keep-display-on`, `camera`, `content_exchange_source`, template `ubuntu-sdk`, never `unconfined`. |
| `apparmor-ci/` | The pinned UBports policy (`policy.lock`), the pinned review lists (`policy-groups.json`), `check-groups.py`, `make-profile.sh`, `run-approx.sh`. |
| `tests/` | `qml/` (qmltestrunner tests), `native/` (Qt Test), `fake_node.py`, `smoke/arm64-selftest.sh`. |
| `tools/` | Isolation checks, token and text checks, click-tree check, jar check, local runners. |
| `docs/` | `UBUNTU_TOUCH.md` (what runs when, what leaves the phone), `DEVICE_CHECKLIST_UT.md` (DV-UT01 to DV-UT16). |
| `UT_BASE_SHA`, `ROOT_TEST_BASELINE` | The pinned base for the isolation check and the root test-count floor (139). |
| `../.github/workflows/ubuntu-touch.yml` | The CI jobs. |

## How to run what runs here

```sh
./gradlew -p ubuntu-touch/jvm utTest --no-daemon                 # UT0.1: host tests + UTC01-UTC05 (JDK 17 and 21 are both gates)
./gradlew -p ubuntu-touch/jvm utNodeJar                           # the shipped jar (about 4 MB)
python3 ubuntu-touch/tools/check_jar.py java ubuntu-touch/jvm/ut-host/build/libs/asom-ut-node.jar \
        --limit-modules java.base,java.logging,jdk.crypto.ec,jdk.unsupported   # runs the jar the way the phone will
ubuntu-touch/runtime/jlink.sh                                     # UT0.2: needs binutils-aarch64-linux-gnu; downloads 205 MB once
ubuntu-touch/tools/run_qml_tests_local.sh                         # builds the plugin with THIS machine's Qt 5.15, runs Qt tests + QML tests
ubuntu-touch/tools/isolation.sh                                   # isolation checks 1-4 (4 against UT_BASE_SHA) + the law checks
python3 ubuntu-touch/apparmor-ci/check-groups.py --selftest       # negative controls of the AppArmor group check
ubuntu-touch/apparmor-ci/make-profile.sh                          # generate the profile from the pinned template; the parser accepts it
```

## What was verified, with what (2026-09-30, this container: Ubuntu 24.04 x86_64, JDK 21.0.10 and Temurin 17.0.20.1, Qt 5.15.13)

Labels: `VERIFIED-BY <tool>` means the tool was run on the file here and its output is in `PROGRESS.md`. `UNVERIFIED` means it was
written and never executed. `CI-ONLY` means it needs a container or a runner this environment does not have.
**Nothing here is device evidence.**

| Files | Label |
|---|---|
| `jvm/**`, `conformance/utc/**` | VERIFIED-BY `./gradlew utTest` (JDK 21 and Temurin 17), mutation checks (`PROGRESS.md`), `tools/check_law.py` |
| `runtime/temurin.lock`, `runtime/jlink.sh`, `runtime/jlink-modules.txt` | VERIFIED-BY running `jlink.sh` here (real download, checksum, cross-jlink, `objdump`, `file`): 39 MB, ARM aarch64, max GLIBC 2.17. shellcheck clean. The image was inspected, **never run** (no aarch64 here). |
| `runtime/jvm.options` | VERIFIED-BY running the jar with exactly these flags under `--limit-modules` on x86_64 (`tools/check_jar.py`) and by a dry run of the smoke script; the aarch64 run is CI-ONLY |
| `plugin/**` | VERIFIED-BY compiling against Qt 5.15.13 headers (g++ 13, moc), a native Qt Test run (16 results, 0 failed), and 41 QML tests through the plugin. `DisplayKeeper` was verified only for failing quietly without the D-Bus service; **holding the display on a phone is UNVERIFIED** (DV-UT05). |
| `qml/**` | VERIFIED-BY qmllint (syntax only; Qt 5.15's qmllint resolves no imports) and by the QML tests. The pages ran against a stand-in for `Lomiri.Components` (`tests/qml/lomiri-stub`): bindings and logic are exercised, **layout and styling are UNVERIFIED** until `clickable test` runs in CI. Superseded 2026-10-07: the hosted check run "QML tests against the real Lomiri.Components (fake node)" concluded success at head `37332005` (`CI (hosted VM) evidence`, ERRATA ERR-UT-QML-3, the orchestrator section formerly headed ERR-UT-QML-1). Layout and styling on a phone remain UNVERIFIED. |
| `manifest.json.in`, `asom.apparmor.in`, `asom.desktop.in` | VERIFIED-BY `tools/check_click_tree.py` on a `cmake --install` tree (placeholders substituted as Clickable does) and `apparmor-ci/check-groups.py` (18 negative controls). click-review itself is CI-ONLY. |
| `clickable.yaml` | VERIFIED-BY Clickable 8.10.0's own `project.schema` (a bogus key is refused). `clickable build` and `clickable test` are CI-ONLY. Superseded 2026-10-07: the hosted check runs "Click build and review (Clickable 24.04-1.x, arm64)" and "(Clickable 24.04-2.x, arm64)" concluded success at head `37332005`. |
| `apparmor-ci/make-profile.sh`, `fetch-policy.sh`, `tree-hash.sh`, `policy.lock` | VERIFIED-BY running them: the pinned tree hash matched, `aa-easyprof` generated the profile for 2404.1 and 2404.2, `apparmor_parser -QK` accepted both. |
| `apparmor-ci/run-approx.sh` | UNVERIFIED when written (needs an enforcing AppArmor kernel on aarch64); `bash -n` and shellcheck only. Superseded 2026-10-07: the hosted check runs "AppArmor approximation, policy 2404.1" and "policy 2404.2 - CI-APPROX, NOT DEVICE EVIDENCE" concluded success at head `37332005` (denials they printed: ERRATA ERR-UT-QML-3). |
| `tests/smoke/arm64-selftest.sh` | Mechanics VERIFIED-BY a local dry run with a host-arch runtime in a hand-made `.deb`; the arm64 run is CI-ONLY. Superseded 2026-10-07: the hosted check run "Bundled runtime starts on arm64 (self-test) - LAB / CI, NOT DEVICE EVIDENCE" concluded success at head `37332005`. |
| `../.github/workflows/ubuntu-touch.yml` | VERIFIED-BY actionlint 1.7.12 (syntax and embedded shell). **As written it had never run on GitHub**; the container jobs were UNVERIFIED. Superseded 2026-10-07: the workflow has run: 12 of its check runs (host tests on JDK 17 and 21, click builds, QML, AppArmor approximation, arm64 self-test, cross-jlinked runtime, isolation, the 26.04 canary, root unchanged) concluded success at head `37332005`; URLs in the PROGRESS entry "Corrections to the record, 2026-10-07 (fix-docs)". Not device evidence. |

Not done, and why, is listed in `ERRATA.md` (section "Not done") and in the final report of the track.
