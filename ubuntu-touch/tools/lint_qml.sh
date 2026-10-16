#!/usr/bin/env bash
# qmllint over every QML and JS file of the click and its tests. `Lomiri.Components` is not installable outside a UBports image, so
# qmllint reports it as an unresolved import (and the names that come from it as unqualified): those two kinds of message are counted
# and listed as "toolkit" findings, everything else (syntax errors, typos, bad bindings) fails the run.
# Needs qmllint (qtdeclarative5-dev-tools). Exit 0 when nothing but the toolkit findings remain.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lint="$(command -v qmllint || echo /usr/lib/qt5/bin/qmllint)"
[ -x "$lint" ] || { echo "lint_qml: qmllint not found" >&2; exit 1; }
status=0
files=0
toolkit=0
while IFS= read -r -d '' f; do
  files=$((files + 1))
  out="$("$lint" "$f" 2>&1)"
  rc=$?
  real="$(printf '%s\n' "$out" | grep -Ev 'Lomiri\.Components|is not installed|^\s*(\^|import |$)|Warning: .*(Button|Label|TextField|MainView|units)\b|QML module|Failed to import|Could not find' | grep -E 'Error|error|Warning|warning|expected|Unexpected|syntax' || true)"
  tk="$(printf '%s\n' "$out" | grep -cE 'Lomiri\.Components|is not installed' || true)"
  toolkit=$((toolkit + tk))
  if [ -n "$real" ] || { [ "$rc" -ne 0 ] && [ "$tk" -eq 0 ]; }; then
    echo "lint_qml: $(basename "$f"): $real"
    status=1
  fi
done < <(find "$here/qml" "$here/tests/qml" -path "$here/tests/qml/lomiri-stub" -prune -o \( -name '*.qml' -o -name '*.js' \) -print0)
echo "lint_qml: $files files, $toolkit toolkit finding(s) (Lomiri.Components is not installable here), status $status"
exit "$status"
