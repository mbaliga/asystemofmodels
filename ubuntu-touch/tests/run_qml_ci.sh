#!/usr/bin/env bash
# `clickable test` in the ci-ut24.04-1.x-amd64 image: run the QML tests against the REAL Lomiri.Components and the plugin that
# `clickable build --arch amd64` just built. Nothing here is a stand-in; the fake node is tests/fake_node.py (python3).
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
qmldir="$(find "$root/build" -path '*/qmlmodules/Asom/Bridge/qmldir' 2>/dev/null | head -1)"
[ -n "$qmldir" ] || { echo "run_qml_ci: no built Asom.Bridge plugin under build/ (run: clickable build --arch amd64)" >&2; exit 1; }
modules="$(dirname "$(dirname "$(dirname "$qmldir")")")"
command -v python3 >/dev/null || { echo "run_qml_ci: python3 is needed for the fake node" >&2; exit 1; }
export QT_QUICK_BACKEND=software
export QML_DISABLE_DISK_CACHE=1
echo "run_qml_ci: plugin modules at $modules"
exec qmltestrunner -platform offscreen -import "$modules" -input "$root/tests/qml"
