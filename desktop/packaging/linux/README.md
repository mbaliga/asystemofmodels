# desktop/packaging/linux: the Linux app image, packages and installers (DL3)

**Status: scaffold. Every artifact is UNSIGNED, not for release. Nothing here enables, starts or listens.** Authority:
`docs/design/mesh/platforms/linux.md` sections 7.1, 8, 10 and PLATFORM_PLAN section 3 step DL3. Spec defects met and the
reading taken: [`ERRATA.md`](ERRATA.md). The units, the sysusers.d file, the polkit rule, `test/journal-hygiene.sh` and
`test/systemd-vm.sh` belong to DL2 and are shipped, not changed, here.

| File | What it is |
|---|---|
| `jlink-modules.txt`, `modules.sh` | the JDK module list in three sections (derived by jdeps, excluded with a reason, added with a reason); `jdk.net` and `jdk.crypto.ec` are in it |
| `check-jlink-modules.sh` | the jdeps drift check: jdeps output must equal `[derived]` + `[excluded]` exactly |
| `build-app-image.sh` | `jlink` (JDK 21) + `jpackage --type app-image` (launchers `asom-node` and `asom`) + units, docs and installers; prints `app image: build/asom-desktop-<ver>-linux-<arch>` |
| `check-native-deps.sh` | the system libraries the image's ELF files need must be inside the package's `Depends` set |
| `build-packages.sh`, `nfpm.yaml`, `fetch-nfpm.sh` | the tarball, `.deb` and `.rpm` (nfpm 2.41.3, pinned and checksum-verified), `SHA256SUMS`, the two installers |
| `scripts/postinstall.sh`, `preremove.sh`, `postremove.sh` | package scripts; sysusers and daemon-reload; a running node is `try-restart`ed on upgrade only; NEVER enable or start; removal keeps `/var/lib/asom` |
| `install.sh`, `uninstall.sh` | the per-user route (no root): verify SHA256SUMS always, inspect the archive, swap `current`, write the USER unit disabled; `uninstall.sh --purge` needs a typed confirmation on the terminal |
| `test/lab-packaging-check.sh` | everything checkable without systemd or a container engine (LAB): installers, 14 hostile archives, signature binding, purge on a pty, deb layout, maintainer scripts against stub `systemctl` in a private mount namespace |
| `test/distro-matrix.sh` | containers `ubuntu:22.04/24.04/26.04`, `fedora`, `archlinux` (CI-ONLY, never run) |
| `test/build-probe.sh`, `test/probe/RuntimeProbe.java` | a probe run on the SHIPPED runtime: `jdk.net` SO_PEERCRED, ES256, TLS 1.3 (in memory) |
| `test/fetch-nfpm-check.sh` | hermetic check that no unverified nfpm is ever executed (LAB, no network) |
| `test/make-hostile-archives.py`, `test/pty-run.py` | helpers for the lab check |

## Run it (repository root; JDK 21 for the image, `sudo` for the lab check's setpriv and unshare)

```sh
bash desktop/packaging/linux/build-app-image.sh --arch x86_64      # ASOM_GRADLE_FLAGS='--no-daemon --max-workers=2' to limit Gradle
bash desktop/packaging/linux/test/build-probe.sh desktop/packaging/linux/build/probe
bash desktop/packaging/linux/build-packages.sh --image desktop/packaging/linux/build/asom-desktop-*-linux-x86_64
sudo TMPDIR=/tmp bash desktop/packaging/linux/test/lab-packaging-check.sh \
  --dist desktop/packaging/linux/build/dist --probe desktop/packaging/linux/build/probe
```

## Signature (what `install.sh` does and does not claim)

`SHA256SUMS` is verified always. A detached signature beside it (`SHA256SUMS.asc`, `.sig` or `.gpg`) is checked with `gpg` when
`gpg` is installed. The owner's OpenPGP primary-key fingerprint is pinned in `install.sh` as `OWNER_FPR`. **It is `OWNER-FILL`
today: the owner has not published a fingerprint, and there is none in this repository.** While it is unset a valid signature
is reported as "made by key <fingerprint>, NOT checked against the owner's key"; the installer never says the owner signed
anything unless the signing key's fingerprint equals the pinned one. Once the owner publishes the fingerprint (and signs
`SHA256SUMS` offline, `linux.md` 8.3), it goes into `OWNER_FPR` and into this section.

## Evidence labels (never dropped)

- `LAB`: run in a container here, on Ubuntu's OpenJDK 21 (not Temurin), x86_64 only.
- `CI-ONLY`: written, never run on a hosted runner: the `package-linux` and `install-matrix` jobs of `desktop-linux.yml`,
  and everything that needs real systemd (`systemctl is-enabled`).
- **NOT DEVICE EVIDENCE.** Nothing proves anything about a Steam Deck or a Dell: [`../../docs/DEVICE_CHECKLIST_LINUX.md`](../../docs/DEVICE_CHECKLIST_LINUX.md).

## Not done here

aarch64 images (need an aarch64 runner), signing, attestations, a reproducibility check, SteamOS itself, a systemd run of
`systemd-vm.sh` against the packaged image, `lib/native` (DL4), `asom upgrade/rollback/uninstall` CLI commands.
