#!/usr/bin/env bash
# Stages the node jar next to the runtime image: copies asom-ut-node.jar (./gradlew -p ubuntu-touch/jvm utNodeJar) into runtime/stage
# and writes jar.sha256, which the Asom.Bridge plugin verifies before it spawns the JVM. UNSIGNED, not for release.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
stage="${ASOM_UT_STAGE:-$root/runtime/stage}"
jar="${1:-$root/jvm/ut-host/build/libs/asom-ut-node.jar}"
[ -f "$jar" ] || { echo "stage_node: $jar does not exist: run ./gradlew -p ubuntu-touch/jvm utNodeJar" >&2; exit 1; }
mkdir -p "$stage"
cp "$jar" "$stage/asom-ut-node.jar"
( cd "$stage" && sha256sum asom-ut-node.jar > jar.sha256 )
echo "stage_node: $(du -k "$stage/asom-ut-node.jar" | cut -f1) KiB, $(cat "$stage/jar.sha256")"
