#!/usr/bin/env bash
# The isolation checks of PLATFORM_PLAN section 2 / linux.md 10.3 DL0, with check 4 pinned to DESKTOP_BASE_SHA (the
# lab's mechanism, lab/tools/isolation.py, copied; see desktop/ERRATA.md ERR-ISO-1), plus the static law checks.
# Run from anywhere inside the repository. The Gradle flags for this container's limits come from ASOM_GRADLE_FLAGS.
#
#   desktop/tools/isolation.sh              checks 1, 3, 4 and the law checks, and check 2 with the CURRENT environment
#   desktop/tools/isolation.sh --with-sdk   also re-runs check 2 with ANDROID_HOME pointing at a directory
#                                           (CI-APPROX: proves the desktop build ignores a present SDK; the real proof is
#                                           the desktop-isolation-with-sdk job on a runner that has a real SDK)
set -u
cd "$(git rev-parse --show-toplevel)"
status=0
say() { printf '%s\n' "$*"; }
# shellcheck disable=SC2086
gradle_out() { ./gradlew -p desktop ${ASOM_GRADLE_FLAGS:-} "$@" 2>&1; }

c1=$(grep -c desktop settings.gradle.kts)
say "isolation check 1 (root settings never name the desktop build): $c1   expected 0"
[ "$c1" = "0" ] || status=1

env_out=$(gradle_out buildEnvironment) || { say "gradle buildEnvironment failed"; printf '%s\n' "$env_out" | tail -20; exit 1; }
c2=$(printf '%s\n' "$env_out" | grep -c com.android)
say "isolation check 2 (no Android tooling in the desktop classpath; ANDROID_HOME='${ANDROID_HOME:-}' ANDROID_SDK_ROOT='${ANDROID_SDK_ROOT:-}'): $c2   expected 0"
[ "$c2" = "0" ] || status=1
pos=$(printf '%s\n' "$env_out" | grep -c kotlin-gradle-plugin)
say "isolation check 2 positive control (the same output does list the Kotlin plugin, so a 0 above is not an empty read): $pos   expected > 0"
[ "$pos" -gt 0 ] || status=1

if [ "${1:-}" = "--with-sdk" ]; then
  fake=$(mktemp -d)
  env_sdk=$(ANDROID_HOME="$fake" ANDROID_SDK_ROOT="$fake" gradle_out buildEnvironment) || { say "gradle failed with SDK set"; exit 1; }
  c2b=$(printf '%s\n' "$env_sdk" | grep -c com.android)
  say "isolation check 2 again with ANDROID_HOME=$fake (a directory standing in for an SDK): $c2b   expected 0   [CI-APPROX]"
  [ "$c2b" = "0" ] || status=1
  rmdir "$fake"
fi

proj_out=$(gradle_out projects) || { say "gradle projects failed"; exit 1; }
c3=$(printf '%s\n' "$proj_out" | grep -cE "':(app|vault|pairing|storage|ledger|client|client-cloud|sample-client)'")
say "isolation check 3 (no Android module in the desktop build): $c3   expected 0"
[ "$c3" = "0" ] || status=1
p3=$(printf '%s\n' "$proj_out" | grep -cE "':(core|core:contract|core:catalogue|core:routing|core:inference-api|server|node-core|node)'")
say "isolation check 3 positive control (the mapped root projects and the desktop modules are listed): $p3   expected 8"
[ "$p3" = "8" ] || status=1

python3 desktop/tools/isolation.py || status=1
python3 desktop/tools/check_law.py || status=1

if [ "$status" = "0" ]; then say "ISOLATION: all checks passed"; else say "ISOLATION: FAILED"; fi
exit "$status"
