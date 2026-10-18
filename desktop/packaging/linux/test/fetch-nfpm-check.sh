#!/usr/bin/env bash
# fetch-nfpm-check.sh: fetch-nfpm.sh and build-packages.sh never run an nfpm they have not verified (finding EGR-6).
# Hermetic: no network (NFPM_BASE_URL points at a file:// directory), no root. Evidence label: LAB.
#
#   fetch-nfpm-check.sh
#
# Each case plants an "nfpm" that writes a marker file when it is EXECUTED; the marker must stay absent.
# Exit 0 only when every check passed and at least one ran.
set -u

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
pkg="$(cd "$here/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

pass=0; fail=0
ok()  { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1"; fail=$((fail + 1)); }

version="$(sed -n 's/^NFPM_VERSION="\(.*\)"/\1/p' "$pkg/fetch-nfpm.sh")"
case "$(uname -m)" in
  x86_64) asset="nfpm_${version}_Linux_x86_64.tar.gz" ;;
  aarch64) asset="nfpm_${version}_Linux_arm64.tar.gz" ;;
  *) echo "FAIL: no pinned nfpm for $(uname -m)"; exit 1 ;;
esac
[ -n "$version" ] && ok "read the pinned version ($version) and asset name ($asset) from fetch-nfpm.sh" || bad "could not read NFPM_VERSION"

plant() { # plant <dest dir> : a fake nfpm that reports the pinned version and leaves a marker when run
  local d="$1"
  mkdir -p "$d/nfpm-$version"
  printf '#!/bin/sh\ntouch "%s/EXECUTED"\necho "GitVersion: %s"\n' "$work" "$version" > "$d/nfpm-$version/nfpm"
  chmod 755 "$d/nfpm-$version/nfpm"
}

mkdir -p "$work/empty" "$work/garbage"
printf 'not the real release\n' > "$work/garbage/$asset"

# 1. a binary already in the cache, no tarball, nothing to download: refused, and never executed
rm -f "$work/EXECUTED"; plant "$work/d1"
out="$(NFPM_BASE_URL="file://$work/empty" "$pkg/fetch-nfpm.sh" --dest "$work/d1" 2>&1)"; rc=$?
[ ! -e "$work/EXECUTED" ] && ok "a cached nfpm binary with no verified tarball is NOT executed" || bad "the planted cached binary was executed"
[ "$rc" != "0" ] && ok "  ...and the script refuses (exit $rc) instead of trusting it" || bad "  ...but exited 0: $out"

# 2. a binary plus a tarball that does not match the pinned sum, and a download that does not match either
rm -f "$work/EXECUTED"; plant "$work/d2"; cp "$work/garbage/$asset" "$work/d2/nfpm-$version/$asset"
out="$(NFPM_BASE_URL="file://$work/garbage" "$pkg/fetch-nfpm.sh" --dest "$work/d2" 2>&1)"; rc=$?
[ ! -e "$work/EXECUTED" ] && ok "a cached binary beside a tarball with the wrong sum is NOT executed" || bad "the planted binary was executed beside a wrong tarball"
[ "$rc" = "1" ] && echo "$out" | grep -q "SHA-256 MISMATCH" && ok "  ...and a download with the wrong sum is refused as a mismatch" || bad "  ...mismatch not reported: exit $rc, $out"
[ ! -e "$work/d2/nfpm-$version/nfpm" ] && ok "  ...and the planted binary was removed, not kept" || bad "  ...the planted binary is still in the cache"

# 3. build-packages.sh does not prefer an nfpm on PATH over the pinned download
mkdir -p "$work/pathbin"
printf '#!/bin/sh\ntouch "%s/EXECUTED"\necho "GitVersion: 0.0.0"\n' "$work" > "$work/pathbin/nfpm"; chmod 755 "$work/pathbin/nfpm"
grep -q 'command -v nfpm' "$pkg/build-packages.sh" && bad "build-packages.sh still looks for nfpm on PATH" || ok "build-packages.sh does not look for nfpm on PATH"
grep -q 'nfpm route:' "$pkg/build-packages.sh" && ok "build-packages.sh prints which nfpm route it took" || bad "build-packages.sh does not print the nfpm route"
rm -f "$work/EXECUTED"
mkdir -p "$work/img/asom-desktop-0.0.0-linux-$(uname -m)/lib/runtime" "$work/img/asom-desktop-0.0.0-linux-$(uname -m)/bin"
printf '#!/bin/sh\n' > "$work/img/asom-desktop-0.0.0-linux-$(uname -m)/bin/asom-node"; chmod 755 "$work/img/asom-desktop-0.0.0-linux-$(uname -m)/bin/asom-node"
out="$(PATH="$work/pathbin:$PATH" NFPM_DEST="$work/d3" NFPM_BASE_URL="file://$work/empty" "$pkg/build-packages.sh" --image "$work/img/asom-desktop-0.0.0-linux-$(uname -m)" --out "$work/out" --formats deb 2>&1)"; rc=$?
[ ! -e "$work/EXECUTED" ] && [ "$rc" != "0" ] && ok "with a hostile nfpm first on PATH and no verified download, build-packages.sh fails without running it" || bad "build-packages.sh ran the PATH nfpm or succeeded: exit $rc, $out"

echo "fetch-nfpm-check: $pass passed, $fail failed"
[ "$fail" = "0" ] && [ "$pass" -gt 0 ] && exit 0
exit 1
