# desktop/packaging/macos: the macOS host of the desktop node (PLATFORM_PLAN section 5, steps MC1 and MC2)

**Status: scaffold, ships nothing.** Nothing here is a release, nothing listens, nothing is signed, nothing registers a service, and no
Team ID exists. Every result is `LAB` (a Linux container) or `CI (hosted VM) evidence` (a GitHub-hosted macOS runner), **never device
evidence**. Whatever needs a real Mac stays `NEEDS-DEVICE-VALIDATION`.

**Written in a container with no macOS, no Xcode and no way to run GitHub Actions.** The Kotlin module was compiled and tested on Linux
against fakes and against a real child process that speaks the wire protocol. The Swift protocol target was compiled and tested on
Linux. **The macOS-only Swift sources of the helper (`helper/Sources/asom-mac-helper/*.swift` except `main.swift`) have never been
compiled**: only a syntax parse (`swiftc -parse`) was run. The workflow `.github/workflows/desktop-macos.yml` is what will compile and run
them, on `macos-latest`; until a hosted run has been seen, every macOS result below is `CI-ONLY / NOT RUN`.

Source of truth: `docs/design/mesh/platforms/macos.md` sections 3 to 10 and `docs/design/mesh/PLATFORM_PLAN.md` section 5, with the known
spec defects of `docs/design/mesh/REVIEW_ROUND3.md` read conservatively. Every choice is in [`ERRATA.md`](ERRATA.md); nothing was silently
guessed.

## What is built (MC1, MC2)

| Step | What | Where |
|---|---|---|
| MC1 | `:packaging:macos:macplatform`: the `DesktopPlatform` implementation `MacPlatform`, `MacPaths` (group container, dev state, daemon layout, 0700 and 0600 rules, the `sun_path` limit), `MigrationGuard` (`NIK_MIGRATED`), `NodeLock` (one lending node per Mac), the helper client (`HelperProcess`, `HelperClient`, the strict codec), the key tiers (`SecureEnclaveNik` T2, `FileNik` T0, `NikTierSelector`, `MacNikStore`), power (one shared assertion, the 2 s sleep acknowledgement), presence, thermal, GPU, `InterfaceEligibility` (and the Local Network classification), `MacControlSocket` (peer credentials in both directions; does NOT bind), `ServiceRegistration`, `MacDoctor` | `macplatform/` |
| MC2 | the Swift package: `HelperProtocol` (the codec, the dispatcher, the framing and a fixture machine; builds and is TESTED ON LINUX) and the `asom-mac-helper` executable (the macOS half is behind `#if os(macOS)`); the protocol `SCHEMA.md` and 404 shared vectors; the AM14 demo `scripts/demo-keychain-cli-weakness.sh` | `helper/`, `helper-protocol/`, `scripts/` |
| CI | the workflow with the plan's `macplatform` job, plus isolation, Linux and cross-lane jobs | `.github/workflows/desktop-macos.yml` (canonical copy `ci/desktop-macos.yml`, kept identical by a test) |

The module is pure JVM (no `android.*`, no JNA: macOS reaches the Apple frameworks through the Swift helper), Kotlin compiled `--release 17`
(the JDK 17 API surface is enforced with `-Xjdk-release=17`), and depends on `:node-core` only. It adds **no third-party dependency**.

## Run it

```
./gradlew -p desktop :packaging:macos:macplatform:test         # the pure tests run; the macOS-only ITs are SKIPPED off macOS (the summary prints the count)
python3 desktop/packaging/macos/scripts/check_it_results.py --expect skipped desktop/packaging/macos/macplatform/build/test-results/test
swift test --package-path desktop/packaging/macos/helper       # the protocol target, on Linux or macOS
desktop/packaging/macos/scripts/check-protocol-lanes.sh        # both lanes over the same vectors, then a byte diff
```

On a Mac, build the helper first (`swift build -c release --package-path desktop/packaging/macos/helper`); the integration tests then run
for real and `--expect mac` checks that they ran. A missing helper on a Mac FAILS the integration tests, it does not skip them.
`desktop/build.gradle.kts` (not owned by this track) still lists only `node-core` and `node` in `desktopTest`; this module is run by name
until the desktop-core owner appends it (ERRATA MAC-GATE-1).

## The helper protocol

`helper-protocol/SCHEMA.md` is normative for two implementations that must agree byte for byte: JSON Lines over the helper's stdin and
stdout, integers only, unknown fields rejected, an optional `id`, a fixed member order, closed reject and error codes, one decode order, lines
of at most 131,072 bytes. `helper-protocol/vectors/*.jsonl` (404 vectors, written by `tools/gen_vectors.py`) are read by both lanes; each
lane prints one line per vector and `scripts/check-protocol-lanes.sh` diffs them. The vectors are SELF-ORACLED (one author wrote both codecs
and the expectations), so agreement is "cross-lane", not "independent" (R3-CLOSURE-12).

## What is NOT built (and why)

* **MC3 and later:** the llama.cpp JNI build, the runtime, the hardened launcher, the app image, signing, the package and disk image,
  notarisation, the two-node smoke, the Homebrew tap and the user guide. `launcher/`, `native/`, `resources/`, `homebrew/`, `docs/MACOS_NODE.md`
  and twelve of the thirteen `scripts/*.sh` hold clearly marked `NOT-YET-IMPLEMENTED` stubs (the scripts fail with exit 3 on purpose). They are
  D-v2 work and wait for D-v2's entry criteria (D21, D22, D23, D25, D27, D28).
* **Until the hardened launcher exists, the node is rated "same-user compromise = node compromise"** (C13): a stock jpackage launcher passes
  the caller's environment into the JVM (AM26), so the group container's protection does not stop a same-user attacker who can inject a JVM
  option. Nothing in this track changes that.
* **The control socket does not bind.** `MacControlSocket.start` throws `NotYetImplementedException` (ERRATA MAC-CTL-2). The identity string,
  the peer check in both directions, the path limit and the directory rule are built and tested; a test binds a real AF_UNIX pair itself.
* **The Team ID does not exist.** It is an owner decision (M-D10). No value is in the repository, so every run is a dev-state run and says
  `UNSIGNED BUILD: container protection absent` (ERRATA MAC-TEAM-1).
* **Mode B (the LaunchDaemon) is refused**, not built (M2, after S-M5). **The tray is not built** (after D14 part A). **The T0 passphrase wrap
  is not built.** **Nothing turns lending on**: there is no owner command that calls `enableLending`; a node starts OFF and inert, registers nothing
  and creates no key until the owner's first mesh enable.

## What each key tier does NOT guarantee (also printed by `asom doctor`)

* **T2 `secure-enclave`** (the Secure Enclave through the Swift helper): the private key never exists outside the Enclave, cannot be extracted
  and is useless on another Mac. It does **not** stop *use* of the key on this Mac by any process that obtains the blob: the blob is **not bound**
  to asom's App ID or Team ID (FM15), and what keeps other processes from it is the Team-ID group container, an operating-system policy that
  root, apps with Full Disk Access, a user who approves access in Privacy & Security, or code injected into the node itself all defeat. There is
  **no attestation** (App Attest is rejected), so it is always shown as "hardware-backed (self-reported)". An erase or restore, or a logic-board swap,
  destroys the key, and the node must then be paired again on every peer. Deleting the node's copy of the blob does **not** destroy the Enclave key.
  Whether a real Enclave accepts a key made by a Developer-ID-signed helper with no entitlements is assumption AM01 (spike S-M1, owner device); the
  code here was tested against a JCA-backed fake.
* **T0 `file`** (a PKCS#8 file, 0600, in a 0700 directory): other unprivileged users cannot read it, and in a signed build's group container
  other developer teams' processes are denied by default on macOS 27 (unverified for every kind of process, AM08). It does **not** stop
  same-user code, root, backups or Migration Assistant, and a copied file **is** the node (the backup exclusion and the platform binding only
  turn an honest clone into a dead identity, and do nothing against a thief who controls the new Mac). An UNSIGNED build has none of the container
  protection at all.
* **There is no T1.** The login keychain is rejected: the file-based keychain is on the road to deprecation, the data protection keychain needs a
  provisioning profile and a user context, the `security` command line route puts the key on a command line and leaves an item that trusts
  `/usr/bin/security` so any same-user process can read it silently (AM14; `scripts/demo-keychain-cli-weakness.sh` demonstrates it on a Mac), and the
  JDK keychain store returns the key into JVM memory.

## What no macOS tier guarantees (macos.md 11.3)

That a Mac lends whenever it is powered (forced sleep by lid, Apple menu, low battery or a thermal emergency cannot be prevented by any app;
nothing runs after a restart until FileVault is unlocked); that the key is used only by asom; that the tier, container or signature proves which
software is running (peers see "self-reported"); that traffic outside asom's process is ledgered (Tailscale's own logs, Gatekeeper and
notarisation lookups, XProtect, Homebrew analytics and Apple diagnostics are disclosed, not controlled); or any speed (every Mac number is an
estimate until the owner-device measurement, and a hosted 3-vCPU VM is never quoted).

## Owner inputs still open

The Apple Developer account type and Team ID (M-D10, fixes the group container and every signature), the hosting mode (M-D2), the key tier
policy (M-D3), the launcher hardening (M-D4), distribution (M-D5), overlay guidance (M-D6), the OS floor and Intel (M-D7), `ProcessType`
(M-D8), the UI (M-D9), and the registry rows this section adds (M-D11 under D23). An Apple-silicon Mac and its RAM decide whether a Mac lends in
M1 (M-D1, D28). The device checklist is [`docs/DEVICE_CHECKLIST_MACOS.md`](docs/DEVICE_CHECKLIST_MACOS.md).
