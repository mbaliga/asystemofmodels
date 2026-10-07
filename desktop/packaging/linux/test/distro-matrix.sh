#!/usr/bin/env bash
# distro-matrix.sh (PLATFORM_PLAN 3 DL3 gate 4; linux.md 9 and 10.3): install, run and remove the packaged node in a set of
# distro containers.
#
# EVIDENCE LABEL: CI-ONLY. It was WRITTEN in a container without a container-engine daemon and has NEVER RUN. What it will
# show, when it runs on a hosted runner, is "installs, selftest and the runtime probe pass, uninstall leaves state, in these
# userlands". It says NOTHING about systemd (containers here have no PID 1 systemd; `systemctl is-enabled` is checked by the
# package-linux job on the runner VM and by systemd-vm.sh), NOTHING about SteamOS (Arch is a userland proxy only, and only the
# tarball route runs there), and it is not device evidence.
#
# Outer form (on the runner):
#   distro-matrix.sh --dist <dir> --probe <dir> [--images "ubuntu:22.04 ubuntu:24.04 ubuntu:26.04 fedora:latest archlinux:latest"] [--engine docker]
#     <dir> --dist  : build-packages.sh output (deb, rpm, tar.gz, SHA256SUMS, install.sh, uninstall.sh)
#     <dir> --probe : build-probe.sh output (probe.jar, probe-ec.p12)
# Inner form (inside a container; the outer form calls it):  distro-matrix.sh --inside
#
# Per image it prints:  asom <ver> (desktop, linux-<arch>, runtime <jdk>)  and, if everything passed,
#   self-test: selftest <n> ok, <m> not-yet-implemented, 0 failed; runtime probe: SO_PEERCRED OK, ES256 OK, TLS1.3 handshake OK (in-memory)
# It does NOT print the plan's "native OK (none), ES256 OK, TLS1.3 pinned handshake OK": the node's selftest has no native
# loader (DL4) and no pinned-identity handshake (mesh) yet, and this script will not claim what nothing checked
# (desktop/packaging/linux/ERRATA.md ERR-DL3-6). ubuntu:26.04 exists on Docker Hub (probed 2026-09-30); it stays in the list.
set -u

mode="outer"
dist=""
probe=""
images="ubuntu:22.04 ubuntu:24.04 ubuntu:26.04 fedora:latest archlinux:latest"
engine="docker"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --inside) mode="inside"; shift ;;
    --dist) dist="${2:-}"; shift 2 ;;
    --probe) probe="${2:-}"; shift 2 ;;
    --images) images="${2:-}"; shift 2 ;;
    --engine) engine="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1"; exit 2 ;;
  esac
done

# =====================================================================================================================
if [ "$mode" = "outer" ]; then
  [ -d "$dist" ] && [ -f "$probe/probe.jar" ] || { echo "ERROR: --dist <dir> and --probe <dir> are required"; exit 2; }
  command -v "$engine" >/dev/null 2>&1 || { echo "ERROR: '$engine' not found. This script is CI-ONLY and refuses to pass by running nothing."; exit 2; }
  "$engine" info >/dev/null 2>&1 || { echo "ERROR: the $engine daemon is unavailable. Nothing was run; this is NOT a pass."; exit 2; }
  dist="$(cd "$dist" && pwd)"; probe="$(cd "$probe" && pwd)"
  self="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  failed=0; ran=0; summary=""
  for img in $images; do
    echo "=================== $img"
    ran=$((ran + 1))
    if "$engine" run --rm -e ASOM_MATRIX_IMAGE="$img" \
        -v "$dist":/artifacts:ro -v "$probe":/probe:ro -v "$self":/matrix.sh:ro "$img" bash /matrix.sh --inside; then
      summary="$summary\n  PASS  $img"
    else
      summary="$summary\n  FAIL  $img"; failed=$((failed + 1))
    fi
  done
  printf 'distro matrix (CI-ONLY; containers, no systemd):%b\n' "$summary"
  [ "$ran" -gt 0 ] || { echo "ERROR: no images"; exit 2; }
  [ "$failed" = "0" ] && { echo "distro-matrix: PASS ($ran images)"; exit 0; }
  echo "distro-matrix: FAIL ($failed of $ran images)"; exit 1
fi

# =====================================================================================================================
# inside a container: root, /artifacts (ro), /probe (ro)
img="${ASOM_MATRIX_IMAGE:-unknown}"
pass=0; fail=0
ok()  { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1"; fail=$((fail + 1)); }
expect() { local d="$1"; shift; if "$@" >/dev/null 2>&1; then ok "$d"; else bad "$d"; fi; }

[ "$(id -u)" = "0" ] || { echo "ERROR: run inside a container as root"; exit 2; }
. /etc/os-release
arch="$(uname -m)"
echo "image $img: ID=$ID VERSION_ID=${VERSION_ID:-rolling} arch=$arch"

# -- prerequisites: a way to run as an unprivileged user, and systemd-sysusers for the package's postinstall ----------------
case "$ID" in
  ubuntu|debian)
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq --no-install-recommends systemd util-linux tar gzip coreutils gawk >/dev/null 2>&1 || bad "prerequisites (apt: systemd util-linux)"
    kind=deb ;;
  fedora)
    dnf -y -q install util-linux systemd tar gzip gawk >/dev/null 2>&1 || bad "prerequisites (dnf: util-linux systemd)"
    kind=rpm ;;
  arch)
    pacman -Sy --noconfirm --needed util-linux tar gzip gawk >/dev/null 2>&1 || bad "prerequisites (pacman: util-linux)"
    kind=tarball-only ;;
  *) echo "ERROR: unsupported distro $ID"; exit 2 ;;
esac
command -v setpriv >/dev/null 2>&1 && ok "setpriv is available (to run as an unprivileged user)" || bad "setpriv missing"
as_user() { setpriv --reuid=65534 --regid=65534 --clear-groups "$@"; }

selftest_ok() { # selftest_ok <label> <asom-node path> <home>
  local out rc
  out="$(as_user env HOME="$3" "$2" --mode=selftest 2>&1)"; rc=$?
  echo "$out" | grep -E '^asom-node selftest|^selftest:' | sed 's/^/    | /'
  if [ "$rc" = "0" ] && echo "$out" | grep -Eq '^selftest: [0-9]+ ok, [0-9]+ not-yet-implemented, 0 failed$'; then ok "$1: selftest, 0 failed"; else bad "$1: selftest exit $rc"; fi
  SELFTEST_LINE="$(echo "$out" | grep -E '^selftest:' | tail -1)"
}
probe_ok() { # probe_ok <label> <java path>
  local out rc
  out="$(as_user "$2" -cp /probe/probe.jar RuntimeProbe /probe/probe-ec.p12 2>&1)"; rc=$?
  echo "$out" | sed 's/^/    | /'
  if [ "$rc" = "0" ] && echo "$out" | grep -q 'jdk.net SO_PEERCRED OK' && echo "$out" | grep -q 'ES256 OK' && echo "$out" | grep -q 'TLS1.3 handshake OK'; then ok "$1: runtime probe (SO_PEERCRED, ES256, TLS1.3)"; else bad "$1: runtime probe exit $rc"; fi
  JDK_LINE="$(echo "$out" | sed -n 's/^runtime-probe: java \([^ ]*\).*/\1/p' | head -1)"
}
mkdir -p /tmp/asom-home && chmod 1777 /tmp/asom-home

# -- package route (deb or rpm) ------------------------------------------------------------------------------------------
if [ "$kind" = "deb" ] || [ "$kind" = "rpm" ]; then
  pkgfile="$(ls /artifacts/*."$kind" 2>/dev/null | head -1)"
  [ -n "$pkgfile" ] || bad "no .$kind in /artifacts"
  cp "$pkgfile" /tmp/asom-pkg."$kind"; chmod 644 /tmp/asom-pkg."$kind"
  mkdir -p /var/lib/asom-preexisting-marker
  if [ "$kind" = "deb" ]; then
    apt-get install -y -qq /tmp/asom-pkg.deb >/tmp/asom-install.log 2>&1 && ok "apt-get install ./asom-desktop.deb" || { bad "apt-get install: $(tail -5 /tmp/asom-install.log)"; }
  else
    dnf -y -q install --nogpgcheck /tmp/asom-pkg.rpm >/tmp/asom-install.log 2>&1 && ok "dnf install (--nogpgcheck: the rpm is UNSIGNED)" || { bad "dnf install: $(tail -5 /tmp/asom-install.log)"; }
  fi
  [ -x /opt/asom/current/bin/asom-node ] && ok "/opt/asom/current/bin/asom-node is executable" || bad "asom-node missing"
  [ -L /usr/bin/asom ] && [ "$(readlink /usr/bin/asom)" = "/opt/asom/current/bin/asom" ] && ok "/usr/bin/asom -> /opt/asom/current/bin/asom" || bad "/usr/bin/asom link"
  [ -f /usr/lib/systemd/system/asom.service ] && [ -f /usr/lib/systemd/user/asom.service ] && ok "both units are installed" || bad "units missing"
  wants="$(find /etc/systemd /usr/lib/systemd /lib/systemd -path '*.wants/*asom*' 2>/dev/null)"
  [ -z "$wants" ] && ok "no .wants entry for asom anywhere: nothing is enabled (systemctl is-enabled is CI-VM-only)" || bad "enablement entries: $wants"
  getent passwd asom >/dev/null && ok "user asom exists (systemd-sysusers ran in postinstall)" || bad "user asom was not created"
  getent passwd asom | cut -d: -f7 | grep -q nologin && ok "user asom has a nologin shell" || bad "user asom has a login shell: $(getent passwd asom)"
  selftest_ok "$img package" /opt/asom/current/bin/asom-node /tmp/asom-home
  probe_ok "$img package" /opt/asom/current/lib/runtime/bin/java
  ver_line="$(as_user /opt/asom/current/bin/asom-node --version 2>&1 | grep '^asom-node' | head -1)"
  echo "asom ${ver_line#asom-node } | image $img | runtime ${JDK_LINE:-unknown} | linux-$arch"
  st="$(as_user env HOME=/tmp/asom-home /usr/bin/asom status --json 2>&1 | grep '^{' | head -1)"
  case "$st" in '{"host":"foreground","fsm":"OFF","listeners":[],"locks":[],'*) ok "asom status --json: foreground, OFF, no listeners, no locks" ;; *) bad "status: $st" ;; esac

  mkdir -p /var/lib/asom && echo marker > /var/lib/asom/ledger-marker
  if [ "$kind" = "deb" ]; then apt-get remove -y -qq asom-desktop >/tmp/asom-remove.log 2>&1 && ok "apt-get remove" || bad "apt-get remove: $(tail -3 /tmp/asom-remove.log)"
  else dnf -y -q remove asom-desktop >/tmp/asom-remove.log 2>&1 && ok "dnf remove" || bad "dnf remove: $(tail -3 /tmp/asom-remove.log)"; fi
  [ ! -e /opt/asom ] && [ ! -e /usr/bin/asom ] && [ ! -e /usr/lib/systemd/system/asom.service ] && ok "remove deleted /opt/asom, /usr/bin/asom and the units" || bad "files remain after remove: $(ls -d /opt/asom /usr/bin/asom 2>&1)"
  [ -f /var/lib/asom/ledger-marker ] && ok "remove KEPT /var/lib/asom (identity, ledger, models)" || bad "remove deleted /var/lib/asom"
  if [ "$kind" = "deb" ]; then
    apt-get purge -y -qq asom-desktop >/dev/null 2>&1
    [ -f /var/lib/asom/ledger-marker ] && ok "purge also KEPT /var/lib/asom (deliberate: ERR-DL3-5)" || bad "purge deleted /var/lib/asom"
  fi
  rm -rf /var/lib/asom
fi

# -- tarball route (every image, including Arch) as an unprivileged user ------------------------------------------------------
tgz="$(ls /artifacts/asom-desktop-*-linux-*.tar.gz 2>/dev/null | head -1)"
if [ -z "$tgz" ]; then bad "no tar.gz in /artifacts"; else
  mkdir -p /tmp/asom-t && cp /artifacts/asom-desktop-*-linux-*.tar.gz /artifacts/SHA256SUMS /artifacts/install.sh /artifacts/uninstall.sh /tmp/asom-t/ && chmod -R a+rX /tmp/asom-t
  th=/tmp/asom-tarhome; mkdir -p "$th"; chown 65534:65534 "$th"
  out="$(as_user env HOME="$th" bash /tmp/asom-t/install.sh --archive "/tmp/asom-t/$(basename "$tgz")" --home "$th" 2>&1)"; rc=$?
  echo "$out" | sed 's/^/    | /'
  [ "$rc" = "0" ] && ok "install.sh (tarball route, unprivileged)" || bad "install.sh exit $rc"
  selftest_ok "$img tarball" "$th/.local/opt/asom/current/bin/asom-node" "$th"
  probe_ok "$img tarball" "$th/.local/opt/asom/current/lib/runtime/bin/java"
  [ -z "$(find "$th" -name '*.wants' 2>/dev/null)" ] && ok "tarball install enabled nothing" || bad "enablement artefacts in $th"
  printf 'x' >> /tmp/asom-t/corrupt.tar.gz 2>/dev/null; cp "$tgz" /tmp/asom-t/corrupt-copy.tar.gz
  printf 'x' >> "/tmp/asom-t/$(basename "$tgz")"
  th2=/tmp/asom-tarhome2; mkdir -p "$th2"; chown 65534:65534 "$th2"
  as_user env HOME="$th2" bash /tmp/asom-t/install.sh --archive "/tmp/asom-t/$(basename "$tgz")" --home "$th2" >/dev/null 2>&1 && bad "a corrupted archive was installed" || ok "a corrupted archive is refused"
  as_user env HOME="$th" bash /tmp/asom-t/uninstall.sh --home "$th" >/dev/null 2>&1 && [ ! -e "$th/.local/opt/asom" ] && ok "uninstall.sh removes the install" || bad "uninstall.sh"
fi

echo "== $img: $pass passed, $fail failed"
if [ -n "${SELFTEST_LINE:-}" ] && [ "$fail" = "0" ]; then
  echo "self-test: $SELFTEST_LINE; runtime probe: SO_PEERCRED OK, ES256 OK, TLS1.3 handshake OK (in-memory SSLEngine; NOT the pinned-identity handshake)"
fi
[ "$fail" = "0" ] && [ "$pass" -gt 0 ]
