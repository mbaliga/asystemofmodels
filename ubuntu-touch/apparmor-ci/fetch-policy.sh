#!/usr/bin/env bash
# Downloads the pinned UBports policy tree (policy.lock), verifies its tree hash and prints the directory it was extracted to.
# Fails loudly on any mismatch: a retargeted symlink or an edited template must never be approximated silently.
# Usage: fetch-policy.sh [cache dir]      (default: $ASOM_UT_POLICY_CACHE or apparmor-ci/.cache)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lock="$here/policy.lock"
cache="${1:-${ASOM_UT_POLICY_CACHE:-$here/.cache}}"
val() { grep -E "^$1=" "$lock" | head -1 | cut -d= -f2-; }
url="$(val archive_url)"; want="$(val tree_sha256)"; commit="$(val commit)"
[ -n "$url" ] && [ -n "$want" ] && [ -n "$commit" ] || { echo "fetch-policy: policy.lock is incomplete" >&2; exit 1; }
dest="$cache/apparmor-easyprof-ubuntu-$commit"
if [ ! -d "$dest/data" ]; then
  mkdir -p "$cache"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL --retry 3 --retry-delay 2 -o "$tmp/policy.tar.gz" "$url"
  mkdir -p "$tmp/x"
  tar -xzf "$tmp/policy.tar.gz" -C "$tmp/x" --strip-components=1
  rm -rf "$dest"
  mv "$tmp/x" "$dest"
fi
got="$("$here/tree-hash.sh" "$dest")"
if [ "$got" != "$want" ]; then
  echo "fetch-policy: tree hash mismatch for $commit: pinned $want, got $got" >&2
  exit 1
fi
echo "$dest"
