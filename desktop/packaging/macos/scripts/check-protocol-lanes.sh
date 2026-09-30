#!/usr/bin/env bash
# The helper-protocol cross-lane check (PLATFORM_PLAN section 5 step MC2; helper-protocol/SCHEMA.md section 7): the Swift helper's
# codec and the Kotlin client's codec run the SAME vectors and must print the SAME line for every one of them.
#
#   check-protocol-lanes.sh               runs both lanes, then diffs
#   check-protocol-lanes.sh --no-run A B  diffs two existing lane files A (Swift) and B (Kotlin) only
#   check-protocol-lanes.sh --selftest    proves the diff can fail (a one-byte difference must be reported, an empty file must be refused)
#
# Exit 0 only when the lines are identical and their count equals the number of vectors. The vectors are SELF-ORACLED (one author wrote
# both codecs and the expectations), so agreement is "cross-lane", never "independent" (R3-CLOSURE-12). Gradle flags for a
# constrained container come from ASOM_GRADLE_FLAGS; the Swift toolchain is found on PATH or at /opt/swift/usr/bin.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
here=desktop/packaging/macos
vectors=$here/helper-protocol/vectors
swift_lines_default=${TMPDIR:-/tmp}/asom-swift.lines
kotlin_lines_default=$here/macplatform/build/reports/desktop/helper-protocol.lines

count_vectors() { cat "$vectors"/*.jsonl | grep -c . ; }

compare() {
  local swift=$1 kotlin=$2 expected
  [ -s "$swift" ] || { echo "CROSS-LANE: the Swift lane wrote no lines ($swift)"; return 2; }
  [ -s "$kotlin" ] || { echo "CROSS-LANE: the Kotlin lane wrote no lines ($kotlin)"; return 2; }
  expected=$(count_vectors)
  local ns nk
  ns=$(grep -c . "$swift"); nk=$(grep -c . "$kotlin")
  echo "cross-lane: $expected vectors in $vectors; Swift lane printed $ns lines, Kotlin lane printed $nk lines"
  if [ "$ns" != "$expected" ] || [ "$nk" != "$expected" ]; then echo "CROSS-LANE: a lane did not run every vector"; return 1; fi
  if diff "$swift" "$kotlin"; then
    echo "cross-lane: the two lanes are byte-identical over $expected vectors"
    return 0
  fi
  echo "CROSS-LANE: the lanes DISAGREE (diff above: < Swift, > Kotlin)"
  return 1
}

if [ "${1:-}" = "--selftest" ]; then
  d=$(mktemp -d); trap 'rm -rf "$d"' EXIT
  n=$(count_vectors)
  seq 1 "$n" | sed 's/^/HP\taccept\t-\t/' > "$d/a"; cp "$d/a" "$d/b"
  compare "$d/a" "$d/b" > /dev/null || { echo "selftest FAILED: identical files were reported different"; exit 1; }
  sed '5s/accept/reject/' "$d/b" > "$d/c"
  if compare "$d/a" "$d/c" > /dev/null; then echo "selftest FAILED: a one-line difference was not reported"; exit 1; fi
  head -n $((n - 1)) "$d/b" > "$d/e"
  if compare "$d/a" "$d/e" > /dev/null; then echo "selftest FAILED: a missing line was not reported"; exit 1; fi
  : > "$d/f"
  if compare "$d/a" "$d/f" > /dev/null; then echo "selftest FAILED: an empty lane file was accepted"; exit 1; fi
  echo "selftest OK (4 controls: identical passes; a changed line, a missing line and an empty file are each refused)"
  exit 0
fi

if [ "${1:-}" = "--no-run" ]; then
  compare "$2" "$3"
  exit $?
fi

export PATH="/opt/swift/usr/bin:$PATH"
rm -f "$swift_lines_default"
ASOM_VECTOR_LINES_OUT="$swift_lines_default" swift test --package-path "$here/helper" 2>&1 | grep -E "Executed [0-9]+ tests|error:" | tail -3
# shellcheck disable=SC2086
./gradlew -p desktop ${ASOM_GRADLE_FLAGS:-} :packaging:macos:macplatform:test --tests '*ProtocolVectorsTest*' 2>&1 | grep -E "BUILD|FAILED" | tail -3
compare "$swift_lines_default" "$kotlin_lines_default"
