#!/usr/bin/env bash
# fetch-nfpm.sh: download the pinned nfpm release and verify its SHA-256 before it is ever run.
#
#   fetch-nfpm.sh [--dest <dir>]      prints the path of the verified nfpm binary on its last line
#   NFPM_BASE_URL and NFPM_DEST override the download directory URL and the default --dest (the tests use file:// and a scratch
#   directory); the pinned sum still decides.
#
# The pinned checksums below are the values in the release's own checksums.txt (goreleaser/nfpm v2.41.3), read on
# 2026-09-30 and compared with the downloaded file. They detect a changed or corrupted download from then on; they do not
# prove the release itself is honest (that is a same-origin check). Bump NFPM_VERSION and both sums together.
set -euo pipefail

NFPM_VERSION="2.41.3"
SUM_X86_64="22aa6d3bc2ec239d62d3d190bcb036a47f2b24e0c3c6edfccebb6a55fbb2078e"
SUM_ARM64="f20852f79109c8a77cb19150d26fc5c5a0d3bbde33bf46a76341e434ea411225"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dest="${NFPM_DEST:-$here/build/tools}"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dest) dest="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "fetch-nfpm: unknown argument: $1" >&2; exit 2 ;;
  esac
done

case "$(uname -m)" in
  x86_64) asset="nfpm_${NFPM_VERSION}_Linux_x86_64.tar.gz"; want="$SUM_X86_64" ;;
  aarch64) asset="nfpm_${NFPM_VERSION}_Linux_arm64.tar.gz"; want="$SUM_ARM64" ;;
  *) echo "fetch-nfpm: no pinned nfpm for $(uname -m)" >&2; exit 2 ;;
esac

mkdir -p "$dest"
dest="$(cd "$dest" && pwd)"
root="$dest/nfpm-$NFPM_VERSION"
bin="$root/nfpm"
tarball="$root/$asset"
sum_of() { sha256sum "$1" | awk '{print $1}'; }

# Nothing already on disk is ever executed on trust: only the kept tarball, whose sum matches the pin, is trusted, and the
# binary is always freshly extracted from it. A planted binary, or a tarball that no longer matches, is discarded.
if [ -f "$tarball" ] && [ "$(sum_of "$tarball")" = "$want" ]; then
  echo "fetch-nfpm: cached tarball matches the pinned sha256, re-extracting the binary from it"
else
  rm -rf "$root"
  mkdir -p "$root"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  url="${NFPM_BASE_URL:-https://github.com/goreleaser/nfpm/releases/download/v${NFPM_VERSION}}/${asset}"
  echo "fetch-nfpm: downloading $url"
  curl -fsSL --retry 3 -o "$tmp/$asset" "$url"
  got="$(sum_of "$tmp/$asset")"
  if [ "$got" != "$want" ]; then
    echo "fetch-nfpm: SHA-256 MISMATCH for $asset: expected $want, got $got. Not extracting, not running." >&2
    exit 1
  fi
  echo "fetch-nfpm: sha256 $got matches the pinned value"
  mv "$tmp/$asset" "$tarball"
fi
rm -f "$bin"
tar -xzf "$tarball" -C "$root" nfpm
"$bin" --version | sed -n 's/^GitVersion: *//p;s/^BuildDate: *//p' | paste -sd' ' - | sed 's/^/fetch-nfpm: nfpm /'
echo "$bin"
