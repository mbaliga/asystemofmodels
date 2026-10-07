# desktop: the shared asom node and the Linux host

A **separate Gradle build** (its own `settings.gradle.kts`) holding the JVM node that every desktop platform plugs into
(`:node-core`), the Linux host (`:node`) and, under `packaging/`, the Windows and macOS hosts and the Linux packaging.
**Status (corrected 2026-10-07, see the PROGRESS entry "Corrections to the record, 2026-10-07 (fix-docs)"): built through
DL0 to DL3 (Linux), W0 to W2 (Windows) and MC1 and MC2 (macOS), as LAB and CI evidence only. It ships nothing signed and
holds no engine. `asom-node` binds no socket: the control socket is built and tested but is not started by `asom-node`
until the owner rules D23 and D25. The Linux host's key store (`nikStore`) is still `NotYetImplemented`.** Authority: acting
decisions AD-3 (placement), AD-5 (verification honesty) and roadmap section 14 item 7; plan:
`docs/design/mesh/PLATFORM_PLAN.md` sections 2 to 5 (steps **DL0** skeleton and isolation, **DL1** probes and governor,
**DL2** host integration, **DL3** Linux packaging, **W0** to **W2**, **MC1** and **MC2**). Spec defects met and the reading
taken for each: [`ERRATA.md`](ERRATA.md), `packaging/linux/ERRATA.md`, `packaging/windows/ERRATA.md`,
`packaging/macos/ERRATA.md`.

What exists:

- the `DesktopPlatform` seam and its ports, `NodeConfig` (integers only), the owner CLI (`asom status --json`, the rest
  answer a typed `NOT_IMPLEMENTED`), `TtyConfirm`, the closed control-frame types and codec, the provider FSM
  (OFF, ARMED, SERVING, DRAINING) with the presence laws LP-0 to LP-2, the governor, the fail-closed JSONL ledger sink,
  and `NoopEngine` wiring;
- the Linux probes (power, thermal, memory, CPU, GPU) as pure parsers over sysfs and procfs text, the mode and path
  rules, and the Steam Deck rules;
- DL2, Linux host integration: the control socket (`ControlServer` and `ControlClient`, AF_UNIX with `SO_PEERCRED` in
  both directions; built and tested, **not started by `asom-node`**), a mini D-Bus reader (`MiniDbus`, `DbusWire`), the
  sleep watcher (`SleepWatcher`) and the `systemd-inhibit` keep-awake lock (`Inhibitor`), systemd units, sysusers.d and polkit;
- DL3, `packaging/linux`: app image, deb, rpm and tarball scripts, an install matrix, all UNSIGNED;
- the Windows host (`packaging/windows`, W0 to W2) and the macOS host (`packaging/macos`, MC1 and MC2), each its own
  `DesktopPlatform` module; their control sockets do not bind either;
- tests over SYNTHETIC fixture trees (`node/src/test/resources/fixtures/sysfs/{deck-oled,deck-lcd,dell,ci-vm}`, each
  labelled `SYNTHETIC.txt`).

What does **not** exist (later tracks; the seam has room for each): serving the control socket from `asom-node` (D23 and
D25), the Linux node-identity key store (`nikStore`), the engine (DL4), the mesh (DL6), signed or notarised release
artifacts.

## How to run

From the repository root, with the root wrapper. No Android SDK is needed and none is used.

```sh
./gradlew -p desktop desktopTest                 # every desktop test; prints the per-law report lines at the end
./gradlew -p desktop :node-core:test             # the shared core alone
./gradlew -p desktop :node:test                  # the Linux host alone (builds the launchers first)
./gradlew -p desktop :node:installDist           # desktop/node/build/install/asom-node/bin/{asom-node,asom}
desktop/node/build/install/asom-node/bin/asom status --json
desktop/node/build/install/asom-node/bin/asom-node --mode=selftest
desktop/tools/isolation.sh [--with-sdk]          # isolation checks 1-4 (4 against the pinned base) + static laws
python3 desktop/tools/isolation.py --selftest    # negative controls of the pinned-base check
```

`asom-node` and `asom` refuse to run as root. `asom-node` needs an explicit mode (`--mode=system|user|foreground|selftest`)
and only idles (lending stays OFF, no socket is bound); Ctrl-C or SIGTERM drains and exits.
JDK 17 and 21 are both gates (the build targets Java 17 bytecode; CI runs both).

## Layout and ownership

| Path | Contents | Owner |
|---|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` | the isolation mechanism (LAB_SPEC 2.2, copied): the five pure-JVM root projects mapped **by directory**, redirected build dirs, never `includeBuild`; `desktopTest` | desktop-core |
| `DESKTOP_BASE_SHA`, `ROOT_TEST_BASELINE` | the pinned base for isolation check 4; the root test-count floor (140, as `ROOT_TEST_BASELINE` states; it was 139 until the never-run `AnthropicDriverTest` case started running) | desktop-core |
| `node-core/` | `:node-core`, host-agnostic, no OS-specific code: `Main`, `DesktopPlatform`, `NodeConfig`, `control/`, `cli/`, `governor/`, `ledger/`, `engine/` | desktop-core |
| `node/` | `:node`, the Linux host: `LinuxPlatform`, `probes/`, `host/`; ServiceLoader registration; the `asom-node` and `asom` launchers | desktop-core |
| `tools/` | `isolation.py`, `isolation.sh`, `check_law.py` | desktop-core |
| `packaging/windows`, `packaging/macos` | the Windows host (`winplatform`, W0 to W2) and the macOS host (`macplatform`, the Swift helper, MC1 and MC2); each added ONE include line to `settings.gradle.kts` | windows-w0-w2, macos-mc1-mc2 |
| `packaging/linux` | DL3: units, sysusers.d, polkit, scripts, install-matrix tests (UNSIGNED) | linux-host-dl2, linux-packaging-dl3 |
| `docs/` | `LINUX.md`, `STEAM_DECK.md`, `DEVICE_CHECKLIST_LINUX.md` | linux-host-dl2, linux-packaging-dl3 |
| `native/` | no such directory at this commit (nothing native is built here) | n/a |

`../.github/workflows/desktop-linux.yml` holds the `desktop-jvm` (JDK 17 and 21), `desktop-jvm-arm`,
`desktop-isolation-with-sdk`, `root-unchanged`, `systemd-vm`, `package-linux` and `install-matrix` jobs;
`desktop-windows.yml` and `desktop-macos.yml` hold the other two hosts. Every artifact they produce is named
`UNSIGNED-not-for-release`.

## Seams for the tracks that follow

- **DL2 (done except two members):** `DesktopPlatform.controlSocket` is `ControlServer` (built; the declaration "serving
  from asom-node, until D23/D25" remains in `PartiallyImplemented.notYetImplemented`), and `PowerPort.hold` and
  `PowerPort.onSleepEvents` are built in `LinuxPowerPort` over `Inhibitor` and `SleepWatcher`. `nikStore` is still
  `NotYetImplementedNikStore`. When a member is built, delete its declaration: the selftest FAILS a member that is declared
  not-yet-implemented but works.
- **Windows and macOS:** implement `DesktopPlatform` in their own module, register it with `ServiceLoader`, add the one
  include line and append the module to `desktopModules` in `build.gradle.kts`. `:node-core` never learns the OS.
- **The engine (DL4):** `EngineWiring` refuses any engine that claims to exist until then.

## Evidence labels (never dropped)

- `LAB`: run in this build environment (a container, x86_64 Linux, real `/proc` and `/sys` of that container).
- `CI-APPROX`: a local approximation of a CI condition (for example isolation check 2 with a directory standing in for
  the Android SDK).
- `SYNTHETIC`: a fixture written by hand to exercise a parser. Never evidence about a device.
- **NOT DEVICE EVIDENCE.** Nothing here proves anything about a Steam Deck, a Dell, systemd, logind, real suspend, a
  GPU counter, a game, fan noise or battery behaviour. Those stay `NEEDS-DEVICE-VALIDATION` (`linux.md` 10.4).

## What the tests do not prove

- Real sensors, real thermal thresholds and real GPU counters: the parsers are checked against SYNTHETIC trees and
  hand-written text, not against any captured device tree (owner work: `asom doctor capture`, DV-D5, DV-L1).
- Power-loss durability: the ledger claim is process death only (a SIGKILL harness); the `volatile` model is a model of
  the durability contract, not of a disk.
- That the thresholds are good choices: every threshold is PROVISIONAL (D-v2 calibration, `NEEDS-OWNER-VALIDATION`).
- Anything about hardware: hosted-runner results (cited in `PROGRESS.md`) are `CI (hosted VM) evidence`, not device evidence.
