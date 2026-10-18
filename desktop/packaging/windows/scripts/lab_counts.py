#!/usr/bin/env python3
"""Per-module JUnit totals of the lab, in a form that can be diffed between operating systems (PLATFORM_PLAN 4, W0 gate:
"the same test count as the Linux lab lane"). Standard library only.

  lab_counts.py <lab-dir> [--out FILE]    print `<module> tests=N failures=F errors=E skipped=S` per module and a TOTAL line
                                          (and write the same lines to FILE, UTF-8 without a byte-order mark, LF)
  lab_counts.py --compare <a> <b>         exit 1 when the two files differ (line ends normalised), printing the difference

It reads `<lab-dir>/<module>/build/test-results/test/*.xml`, which every `labTest` run writes. A run that produced no result
file, or a total of zero, fails: a count of nothing is not a match.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def counts(lab):
    rows = {}
    for f in sorted(glob.glob(os.path.join(lab, "*", "build", "test-results", "test", "*.xml"))):
        module = os.path.relpath(f, lab).split(os.sep)[0]
        r = ET.parse(f).getroot()
        t = rows.setdefault(module, [0, 0, 0, 0])
        t[0] += int(r.get("tests", 0))
        t[1] += int(r.get("failures", 0))
        t[2] += int(r.get("errors", 0))
        t[3] += int(r.get("skipped", 0))
    return rows


def render(rows):
    lines = [f"{m} tests={t[0]} failures={t[1]} errors={t[2]} skipped={t[3]}" for m, t in sorted(rows.items())]
    tot = [sum(t[i] for t in rows.values()) for i in range(4)]
    lines.append(f"TOTAL tests={tot[0]} failures={tot[1]} errors={tot[2]} skipped={tot[3]}")
    return lines, tot


def read(path):
    with open(path, encoding="utf-8") as fh:
        return [line.rstrip("\r\n") for line in fh]


def main(argv):
    if argv and argv[0] == "--compare":
        a, b = read(argv[1]), read(argv[2])
        if a == b:
            print(f"lab counts: identical ({len(a)} lines): {a[-1]}")
            return 0
        print("lab counts differ:")
        for x in sorted(set(a) - set(b)):
            print(f"  only in {argv[1]}: {x}")
        for x in sorted(set(b) - set(a)):
            print(f"  only in {argv[2]}: {x}")
        return 1
    out_file = None
    if "--out" in argv:
        i = argv.index("--out")
        out_file = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    rows = counts(argv[0] if argv else "lab")
    if not rows:
        print("lab counts: no test result files found (run labTest first)", file=sys.stderr)
        return 2
    lines, tot = render(rows)
    print("\n".join(lines))
    if out_file:
        with open(out_file, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(lines) + "\n")
    if tot[0] == 0:
        print("lab counts: total is zero", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
