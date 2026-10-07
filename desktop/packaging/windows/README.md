# desktop/packaging/windows: the Windows host of the desktop node (PLATFORM_PLAN section 4, steps W0 to W2)

**Status: scaffold, ships nothing.** Nothing here is a release, nothing listens, nothing is signed. Every result is `LAB` (a
Linux container) or `CI (hosted VM) evidence` (a GitHub-hosted Windows runner), **never device evidence**. Whatever needs a
real Windows machine stays `NEEDS-DEVICE-VALIDATION`.

**Superseded 2026-10-07 (status of the paragraph below):** the hosted jobs it waits for have run. At head `37332005` the check runs "W0 lab on Windows" (JDK 17 and 21), "W1 W2 winplatform on windows-2025" (JDK 17 and 21) and "... on windows-11-arm (JDK 21)" all concluded success. URLs: PROGRESS entry "Corrections to the record, 2026-10-07 (fix-docs)". This track did not read the job logs, only the conclusions. They are `CI (hosted VM) evidence`, not a Windows machine in anyone's hands: DPAPI, the Platform Crypto Provider, the firewall and power results stay `NEEDS-DEVICE-VALIDATION`.

**Historical, as written: a container with no Windows, no Android SDK and no way to run GitHub Actions.** Everything that touches a Windows
API was compiled and unit-tested against fakes on Linux, and has **not been run on Windows**. The workflow
`.github/workflows/desktop-windows.yml` is what will run it, on `windows-2025` and `windows-11-arm`; until a hosted run has
been seen, every Windows result below was `CI-ONLY / NOT RUN` (see the supersession above).

Source of truth: `docs/design/mesh/platforms/windows.md` sections 3 to 10 and `docs/design/mesh/PLATFORM_PLAN.md` section 4,
with the known spec defects of `docs/design/mesh/REVIEW_ROUND3.md` read conservatively. Every choice is in
[`ERRATA.md`](ERRATA.md); nothing was silently guessed.

## What is built (W0, W1, W2)

| Step | What | Where |
|---|---|---|
| W0 | the lab on Windows JDK 17 and 21, an eol check that every byte-exact vector file is `-text` and LF, a per-module test-count comparison with the Linux lane, and a lane that really runs the lab under the JDK 17 default charset | `.github/workflows/desktop-windows.yml` (`lab-windows`), `scripts/check_eol.py`, `scripts/lab_counts.py`, root `.gitattributes` |
| W1 | `:packaging:windows:winplatform`: the `DesktopPlatform` implementation `WinPlatform`, paths and DACL policy, the single-identity mutex, the JNA bindings behind fakeable ports, power, presence, GPU and thermal probes, the firewall consent gate, the printed firewall commands, the control-socket ACL policy, the service host, the doctor | `winplatform/` |
| W2 | the node-identity key tiers: T2 CNG Platform Crypto Provider, T1 CNG Software KSP, T0 file (CI and headless only), `NikTierSelector` | `winplatform/src/main/kotlin/.../keys/` |

The module is pure JVM (no `android.*`), Kotlin compiled `--release 17`, and depends on `:node-core` and on JNA 5.19.1
(`jna` and `jna-platform`) only. **JNA is a new third-party dependency** and needs the CD-D registry row under D23. Version check:
Maven Central `maven-metadata.xml` listed `5.19.1` as `<release>` on 2026-09-30, so the plan's "5.19.x" exists; the pin is a
literal in `winplatform/build.gradle.kts` because the root version catalogue is read-only for this track.

## Run it

```
./gradlew -p desktop :packaging:windows:winplatform:test          # pure tests run; the Windows-only ITs are SKIPPED off Windows
python3 desktop/packaging/windows/scripts/check_it_results.py --expect skipped desktop/packaging/windows/winplatform/build/test-results/test
```

On a Windows machine the same first command runs the integration tests for real (`--expect windows` checks that they ran). Note
that `desktop/build.gradle.kts` (not owned by this track) still lists only `node-core` and `node` in `desktopTest`; this module is
run by name until the desktop-core owner appends it.

## What is NOT built (and why)

* **W3 and later:** the JNI engine builds, the app image, the MSI, signing, the service install smoke, the two-node smoke, winget
  and the full docs. `wix/`, `scripts/*.ps1`, `winget/` and `native/` hold clearly marked `NOT-YET-IMPLEMENTED` stubs so the tree
  matches the plan. They are D-v2 work and wait for D-v2's entry criteria (D23, D25, D27, D28, D21).
* **The control socket does not bind.** `WinControlSocket.start` throws `NotYetImplementedException`: `desktop/tools/check_law.py`
  forbids a listener API in any main source, and the socket is built ahead of the open D23/D25 rulings (R3-CONFORMANCE-13). The ACL
  policy, the SID identity string, the path limit and the client-side precheck are built and tested; `AfUnixAclIT` binds a real
  AF_UNIX socket inside the test (windows ERRATA WIN-CTL-1).
* **The firewall helper only prints.** It never applies a rule, never elevates, never starts PowerShell (windows ERRATA WIN-FW-1).
* **The tray icon** (`tray/TrayUi.kt`) is not built: the plan schedules it after D14 part A.
* **Nothing turns lending on.** There is no owner command that calls `enableLending`; a service or user node starts OFF and inert.

## What each key tier does NOT guarantee (also printed by `asom doctor`)

* **T2 `tpm`** (Microsoft Platform Crypto Provider): the key cannot be extracted by software and cannot move to another machine.
  It does **not** stop *use* of the key by any code running as the owner (user mode), as `NT SERVICE\asom`, as an Administrator
  or as SYSTEM while the machine runs. There is **no attestation** (that would need a manufacturer CA path and online checks, a new
  egress), so it is always shown as "hardware-backed (self-reported)". Clearing the TPM or some firmware updates destroys the key,
  and the node must then be re-paired with every peer. ECDSA P-256 on a given TPM is assumption AW01 (spike S-W1, owner device).
* **T1 `os-keystore`** (Microsoft Software KSP): the key bytes never enter asom's process. It does **not** stop same-user malware
  *using* the key, or an Administrator or SYSTEM *extracting* it ("non-exportable" is a policy flag), and user keys live in the
  roaming profile of a domain user.
* **T0 `file`**: protects against other unprivileged users only. Same-user code, Administrators, backups, Volume Shadow Copy and
  System Restore snapshots read it. Selected only by an explicit `--key-tier file`.
* Windows Hello is rejected (a user gesture per signature, RSA keys, an online account).

## Layout

```
winplatform/                     Gradle project :packaging:windows:winplatform
  src/main/kotlin/.../win/       WinPlatform, WinPaths, NodeMutex, WinMain, WinEnv, WinNative
    acl/ api/ ctl/ doctor/ exec/ gpu/ jna/ keys/ net/ power/ presence/ service/ thermal/
  src/test/kotlin/.../win/       pure tests (fakes/) and windows/*IT.kt (@EnabledOnOs(WINDOWS))
  src/test/resources/fixtures/   SYNTHETIC netsh listings (labelled; not captures)
scripts/                         check_eol.py, lab_counts.py, check_it_results.py (real); *.ps1 (NOT-YET-IMPLEMENTED stubs)
ci/desktop-windows.yml           canonical copy of .github/workflows/desktop-windows.yml (a test keeps them equal)
wix/ winget/ native/ service/    NOT-YET-IMPLEMENTED stubs
docs/                            WINDOWS_NODE.md, DEVICE_CHECKLIST_WINDOWS.md (W0 to W2 items only)
```

## Owner inputs still open

The Dell's OS and GPU (W-D1), the owner's country (signing route, W-D4), whether WiX's maintenance fee applies (W-D7), the D22 signing
ruling, D23 for JNA, D27/D28 for what Windows may do in mesh-1, and one Windows machine with a TPM 2.0 for W8.
