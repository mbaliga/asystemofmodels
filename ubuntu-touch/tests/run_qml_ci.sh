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
export LC_ALL=C.UTF-8
runner=(qmltestrunner -platform offscreen -import "$modules")
if command -v dbus-run-session >/dev/null; then runner=(dbus-run-session -- "${runner[@]}"); fi
# One process per test file: a native crash then names the file that caused it instead of ending the whole run at "exit -11".
failed=()
for f in "$root"/tests/qml/tst_*.qml; do
  echo "run_qml_ci: == $(basename "$f")"
  if "${runner[@]}" -input "$f"; then continue; fi
  rc=$?
  # A native crash (signal, exit >= 128 or -11 seen as 245) under the offscreen platform is retried once under a virtual X display,
  # which is what the widgets are written for; the retry runs the same tests and both outcomes are printed.
  if command -v xvfb-run >/dev/null && { [ "$rc" -ge 128 ] || [ "$rc" -eq 245 ]; }; then
    echo "run_qml_ci: $(basename "$f") crashed under offscreen (exit $rc); retrying under xvfb"
    if xvfb-run -a "${runner[@]/offscreen/xcb}" -input "$f"; then echo "run_qml_ci: $(basename "$f") passed under xvfb (offscreen crashed)"; continue; fi
  else
    echo "run_qml_ci: $(basename "$f") failed with exit $rc (no xvfb retry: exit is not a crash or xvfb-run is absent)"
  fi
  failed+=("$(basename "$f")")
done
if [ "${#failed[@]}" -gt 0 ]; then
  echo "run_qml_ci: FAILED test files: ${failed[*]}" >&2
  exit 1
fi
echo "run_qml_ci: every test file passed"
