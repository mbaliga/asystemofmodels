#!/usr/bin/env bash
# fetch-nfpm.sh: download the pinned nfpm release and verify its SHA-256 before it is ever run.
#
#   fetch-nfpm.sh [--dest <dir>]      prints the path of the verified nfpm binary on its last line
#
# The pinned checksums below are the values in the release's own checksums.txt (goreleaser/nfpm v2.41.3), read on
# 2026-09-30 and compared with the downloaded file. They detect a changed or corrupted download from then on; they do not
# prove the release itself is honest (that is a same-origin check). Bump NFPM_VERSION and both sums together.
set -euo pipefail

NFPM_VERSION="2.41.3"
SUM_X86_64="22aa6d3bc2ec239d62d3d190bcb036a47f2b24e0c3c6edfccebb6a55fbb2078e"
SUM_ARM64="f20852f79109c8a77cb19150d26fc5c5a0d3bbde33bf46a76341e434ea411225"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dest="$here/build/tools"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dest) dest="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
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
bin="$dest/nfpm-$NFPM_VERSION/nfpm"
if [ -x "$bin" ] && [ "$("$bin" --version 2>/dev/null | sed -n 's/^GitVersion: *//p')" = "$NFPM_VERSION" ]; then
  echo "fetch-nfpm: already present and version $NFPM_VERSION"
  echo "$bin"
  exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
url="https://github.com/goreleaser/nfpm/releases/download/v${NFPM_VERSION}/${asset}"
echo "fetch-nfpm: downloading $url"
curl -fsSL --retry 3 -o "$tmp/$asset" "$url"
got="$(sha256sum "$tmp/$asset" | awk '{print $1}')"
if [ "$got" != "$want" ]; then
  echo "fetch-nfpm: SHA-256 MISMATCH for $asset: expected $want, got $got. Not extracting, not running." >&2
  exit 1
fi
echo "fetch-nfpm: sha256 $got matches the pinned value"
mkdir -p "$dest/nfpm-$NFPM_VERSION"
tar -xzf "$tmp/$asset" -C "$dest/nfpm-$NFPM_VERSION" nfpm
"$bin" --version | sed -n 's/^GitVersion: *//p;s/^BuildDate: *//p' | paste -sd' ' - | sed 's/^/fetch-nfpm: nfpm /'
echo "$bin"
