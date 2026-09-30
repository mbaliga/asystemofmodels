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
  rc=0
  "${runner[@]}" -input "$f" || rc=$?
  if [ "$rc" -eq 0 ]; then continue; fi
  # A native crash (exit >= 128) under the offscreen platform is retried under a virtual X display, first with the software scene
  # graph and then with Mesa's software GL, which the real Lomiri widgets (ShaderEffect) may need. The same tests run each time and
  # every outcome is printed; if nothing passes, a gdb backtrace of the crash is printed when gdb is present.
  if [ "$rc" -ge 128 ] && command -v xvfb-run >/dev/null; then
    echo "run_qml_ci: $(basename "$f") crashed under offscreen (exit $rc)"
    xrun=("${runner[@]/offscreen/xcb}")
    echo "run_qml_ci: retry 1: xvfb, software scene graph"
    if xvfb-run -a "${xrun[@]}" -input "$f"; then echo "run_qml_ci: $(basename "$f") passed (retry 1)"; continue; fi
    echo "run_qml_ci: retry 2: xvfb, default scene graph on Mesa software GL"
    if env -u QT_QUICK_BACKEND LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a "${xrun[@]}" -input "$f"; then echo "run_qml_ci: $(basename "$f") passed (retry 2)"; continue; fi
    if command -v gdb >/dev/null; then
      echo "run_qml_ci: backtrace of the crash"
      xvfb-run -a gdb -q -batch -ex run -ex bt --args qmltestrunner -platform xcb -import "$modules" -input "$f" 2>&1 | tail -60 || true
    fi
  else
    echo "run_qml_ci: $(basename "$f") failed with exit $rc (no retry: the exit is not a crash, or xvfb-run is absent)"
  fi
  failed+=("$(basename "$f")")
done
if [ "${#failed[@]}" -gt 0 ]; then
  echo "run_qml_ci: FAILED test files: ${failed[*]}" >&2
  exit 1
fi
echo "run_qml_ci: every test file passed"
