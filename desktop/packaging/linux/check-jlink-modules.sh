#!/usr/bin/env bash
# check-jlink-modules.sh: the jdeps drift check (PLATFORM_PLAN 3 DL3 gate 6).
#
# Runs `jdeps --print-module-deps` over the shipped jars and requires the result to equal jlink-modules.txt's [derived] plus
# [excluded] sections exactly, so a new dependency that needs a new JDK module fails here instead of at runtime on a
# device. It also pins the modules that jdeps cannot prove: jdk.net (desktop/ERRATA.md ERR-DL2-9) and jdk.crypto.ec.
#
# Usage: check-jlink-modules.sh [--lib <dir of the node jars>]   (default: desktop/node/build/install/asom-node/lib,
#        which `./gradlew -p desktop :node:installDist` produces). Needs JDK 21 (jdeps from $JAVA_HOME or PATH).
# Exit: 0 = no drift, 1 = drift or a broken list, 2 = cannot run (wrong JDK, no jars).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
# shellcheck source=modules.sh
. "$here/modules.sh"

lib="$repo/desktop/node/build/install/asom-node/lib"
list="$here/jlink-modules.txt"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --lib) lib="$2"; shift 2 ;;
    --list) list="$2"; shift 2 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "check-jlink-modules: unknown argument: $1" >&2; exit 2 ;;
  esac
done

jdeps_bin="jdeps"
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jdeps" ] && jdeps_bin="$JAVA_HOME/bin/jdeps"
command -v "$jdeps_bin" >/dev/null 2>&1 || { echo "check-jlink-modules: jdeps not found (needs a JDK 21)" >&2; exit 2; }
major="$("$jdeps_bin" --version 2>/dev/null | head -1 | cut -d. -f1)"
[ "$major" = "21" ] || { echo "check-jlink-modules: jdeps is version '$major'; the shipped runtime is 21, so the check needs JDK 21" >&2; exit 2; }
[ -f "$list" ] || { echo "check-jlink-modules: $list not found" >&2; exit 2; }
ls "$lib"/*.jar >/dev/null 2>&1 || { echo "check-jlink-modules: no jars in $lib (run ./gradlew -p desktop :node:installDist)" >&2; exit 2; }

fail=0
bad() { echo "DRIFT: $1"; fail=1; }

reported="$("$jdeps_bin" --multi-release 21 --ignore-missing-deps --print-module-deps "$lib"/*.jar 2>/dev/null | tr ',' '\n' | LC_ALL=C sort -u)"
[ -n "$reported" ] || { echo "check-jlink-modules: jdeps reported nothing (refusing to pass by comparing with nothing)" >&2; exit 2; }

derived="$(modules_section "$list" derived)"
excluded="$(modules_section "$list" excluded)"
added="$(modules_section "$list" added)"
[ -n "$derived" ] || bad "[derived] is empty"

expected="$(printf '%s\n%s\n' "$derived" "$excluded" | sed '/^$/d' | LC_ALL=C sort -u)"
missing="$(comm -23 <(printf '%s\n' "$reported") <(printf '%s\n' "$expected") | paste -sd' ' -)"
stale="$(comm -13 <(printf '%s\n' "$reported") <(printf '%s\n' "$expected") | paste -sd' ' -)"
[ -z "$missing" ] || bad "jdeps reports module(s) not listed in jlink-modules.txt: $missing (add to [derived], or to [excluded] with a reason)"
[ -z "$stale" ] || bad "jlink-modules.txt lists module(s) jdeps no longer reports: $stale (remove them)"

both="$(comm -12 <(printf '%s\n' "$derived") <(printf '%s\n' "$excluded") | paste -sd' ' -)"
[ -z "$both" ] || bad "module(s) in both [derived] and [excluded]: $both"
both="$(comm -12 <(printf '%s\n' "$added") <(printf '%s\n' "$expected") | paste -sd' ' -)"
[ -z "$both" ] || bad "module(s) in [added] that jdeps already reports (put them in [derived]): $both"

shipped="$(printf '%s\n%s\n' "$derived" "$added" | sed '/^$/d' | LC_ALL=C sort -u)"
for m in java.base jdk.net jdk.crypto.ec; do
  printf '%s\n' "$shipped" | grep -qx "$m" || bad "$m is not in the shipped module set (jdk.net: ERR-DL2-9; jdk.crypto.ec: ES256 and TLS 1.3)"
done
for m in $(printf '%s\n' "$excluded" "$added" | sed '/^$/d'); do
  grep -Eq "^${m//./\\.}[[:space:]]+#[[:space:]]*[^[:space:]]" "$list" || bad "$m has no reason comment in $list"
done

echo "jdeps reported:  $(printf '%s\n' "$reported" | paste -sd, -)"
echo "shipped modules: $(printf '%s\n' "$shipped" | paste -sd, -)   (derived $(printf '%s\n' "$derived" | wc -l), added $(printf '%s\n' "$added" | sed '/^$/d' | wc -l), excluded $(printf '%s\n' "$excluded" | sed '/^$/d' | wc -l))"
if [ "$fail" = "0" ]; then
  echo "jlink-modules: OK (jdeps output equals derived + excluded; jdk.net and jdk.crypto.ec present)"
  exit 0
fi
exit 1
