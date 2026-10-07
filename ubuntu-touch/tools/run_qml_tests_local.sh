#!/usr/bin/env bash
# Builds the Asom.Bridge plugin for THIS machine's Qt 5.15 and runs, offscreen:
#   1. the plugin's native Qt tests (ctest)          -> LAB (real Qt, x86_64 or whatever this host is; not the phone)
#   2. the QML tests under qmltestrunner             -> LAB; the pages import a tiny stand-in for Lomiri.Components
#      (tests/qml/lomiri-stub), so bindings and logic run but layout and styling are NOT verified. CI (`clickable test`) runs the
#      same tests against the real Lomiri.Components in a UBports image.
# Needs: cmake, g++, qtbase5-dev, qtdeclarative5-dev, qtdeclarative5-dev-tools, qml-module-qttest, qml-module-qtquick2, python3.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build="${ASOM_UT_QML_BUILD:-$here/build/local}"
export QT_QPA_PLATFORM=offscreen
export QML_DISABLE_DISK_CACHE=1
export QT_QUICK_BACKEND=software
unset JAVA_TOOL_OPTIONS

cmake -S "$here" -B "$build" -DASOM_UT_NO_RUNTIME=ON -DASOM_UT_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/ >/dev/null
cmake --build "$build" -j 2
echo "== native Qt tests (ctest)"
( cd "$build" && ctest --output-on-failure )

runner="$(command -v qmltestrunner || true)"
[ -n "$runner" ] || runner=/usr/lib/qt5/bin/qmltestrunner
[ -x "$runner" ] || { echo "qmltestrunner not found"; exit 1; }
echo "== QML tests (qmltestrunner, Lomiri.Components stand-in)"
"$runner" -platform offscreen -import "$build/qmlmodules" -import "$here/tests/qml/lomiri-stub" -input "$here/tests/qml" "$@"
