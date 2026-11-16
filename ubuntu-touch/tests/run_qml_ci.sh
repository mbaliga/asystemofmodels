#!/usr/bin/env bash
# `clickable test` in the ci-ut24.04-1.x-amd64 image: run the QML tests against the REAL Lomiri.Components and the plugin that
# `clickable build --arch amd64` just built. Nothing here is a stand-in; the fake node is tests/fake_node.py (python3).
#
# How the real widgets are run (found by the first hosted runs, ERR-UT-QML-1): the real Lomiri Label and Button crash natively
# (SIGSEGV, exit 139) under the Qt software scene graph (QT_QUICK_BACKEND=software) on the offscreen platform; they pass under a
# virtual X display with Mesa software GL and the default scene graph. That is the primary path here. Without xvfb-run the
# offscreen platform with the software scene graph is used, and a native crash there is a failure that names its file.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
qmldir="$(find "$root/build" -path '*/qmlmodules/Asom/Bridge/qmldir' 2>/dev/null | head -1)"
[ -n "$qmldir" ] || { echo "run_qml_ci: no built Asom.Bridge plugin under build/ (run: clickable build --arch amd64)" >&2; exit 1; }
modules="$(dirname "$(dirname "$(dirname "$qmldir")")")"
command -v python3 >/dev/null || { echo "run_qml_ci: python3 is needed for the fake node" >&2; exit 1; }
export QML_DISABLE_DISK_CACHE=1
export LC_ALL=C.UTF-8
echo "run_qml_ci: plugin modules at $modules"

if command -v xvfb-run >/dev/null; then
  mode="xvfb + Mesa software GL, default scene graph"
  run_one() { LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a qmltestrunner -platform xcb -import "$modules" -input "$1"; }
else
  mode="offscreen, software scene graph (xvfb-run is absent)"
  run_one() { QT_QUICK_BACKEND=software qmltestrunner -platform offscreen -import "$modules" -input "$1"; }
fi
if command -v dbus-run-session >/dev/null; then
  inner="$(declare -f run_one)"
  run_one_wrapped() { dbus-run-session -- bash -c "$inner; modules='$modules'; run_one '$1'"; }
else
  run_one_wrapped() { run_one "$1"; }
fi
echo "run_qml_ci: mode: $mode"

# One process per test file: a native crash then names the file that caused it instead of ending the whole run at "exit -11".
failed=()
for f in "$root"/tests/qml/tst_*.qml; do
  echo "run_qml_ci: == $(basename "$f")"
  rc=0
  run_one_wrapped "$f" || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "run_qml_ci: $(basename "$f") failed with exit $rc ($mode)"
    failed+=("$(basename "$f")")
  fi
done
if [ "${#failed[@]}" -gt 0 ]; then
  echo "run_qml_ci: FAILED test files: ${failed[*]}" >&2
  exit 1
fi
echo "run_qml_ci: every test file passed"
