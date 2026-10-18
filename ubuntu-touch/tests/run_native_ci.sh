#!/usr/bin/env bash
# The plugin's native Qt tests, built and run in the ci-ut24.04-1.x-amd64 image (a native build: no cross-compiling, no DESTDIR).
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build="$root/build/native"
cmake -S "$root" -B "$build" -DASOM_UT_NO_RUNTIME=ON -DASOM_UT_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build "$build" -j "$(nproc)"
( cd "$build" && ctest --output-on-failure )
