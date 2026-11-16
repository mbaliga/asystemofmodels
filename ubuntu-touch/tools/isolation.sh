#!/usr/bin/env bash
# The isolation checks of PLATFORM_PLAN section 2 and ubuntu-touch.md 10.3 UT0.1, with check 4 pinned to ubuntu-touch/UT_BASE_SHA
# (the lab's mechanism, see ubuntu-touch/ERRATA.md ERR-UT-ISO-1), plus the static law checks. Run from anywhere in the repository.
# The Gradle flags for a small machine come from ASOM_GRADLE_FLAGS.
#
#   ubuntu-touch/tools/isolation.sh              checks 1, 3, 4 and the law checks, and check 2 with the CURRENT environment
#   ubuntu-touch/tools/isolation.sh --with-sdk   also re-runs check 2 with ANDROID_HOME pointing at a directory (CI-APPROX; the real
#                                                proof is the ut-isolation-with-sdk job on a runner that has a real SDK)
set -u
cd "$(git rev-parse --show-toplevel)"
status=0
say() { printf '%s\n' "$*"; }
# shellcheck disable=SC2086
gradle_out() { ./gradlew -p ubuntu-touch/jvm ${ASOM_GRADLE_FLAGS:-} "$@" 2>&1; }

c1=$(grep -c ubuntu-touch settings.gradle.kts)
say "isolation check 1 (root settings never name the Ubuntu Touch build): $c1   expected 0"
[ "$c1" = "0" ] || status=1

env_out=$(gradle_out buildEnvironment) || { say "gradle buildEnvironment failed"; printf '%s\n' "$env_out" | tail -20; exit 1; }
c2=$(printf '%s\n' "$env_out" | grep -c com.android)
say "isolation check 2 (no Android tooling in the Ubuntu Touch classpath; ANDROID_HOME='${ANDROID_HOME:-}' ANDROID_SDK_ROOT='${ANDROID_SDK_ROOT:-}'): $c2   expected 0"
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
say "isolation check 3 (no Android module in the Ubuntu Touch build): $c3   expected 0"
[ "$c3" = "0" ] || status=1
p3=$(printf '%s\n' "$proj_out" | grep -cE "':(core|core:contract|core:catalogue|core:routing|core:inference-api|server|json|bench-core|manifest|ledger-model|mesh-policy|mesh-proto|mesh-router|mesh-sim|conformance-runner|node-core|ut-host)'")
say "isolation check 3 positive control (the mapped projects and ut-host are listed): $p3   expected 17"
[ "$p3" = "17" ] || status=1

python3 ubuntu-touch/tools/isolation.py || status=1
python3 ubuntu-touch/tools/check_law.py || status=1
python3 ubuntu-touch/tools/check_qml_text.py --selftest || status=1
python3 ubuntu-touch/tools/check_qml_text.py || status=1

if [ "$status" = "0" ]; then say "ISOLATION: all checks passed"; else say "ISOLATION: FAILED"; fi
exit "$status"
