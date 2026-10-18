#!/usr/bin/env python3
"""W0 eol check (PLATFORM_PLAN 4 CI table; windows.md 7 drift item 2, C17). Standard library only.

Every tracked file under the byte-exact vector directories must carry the `-text` attribute, so that `core.autocrlf` on a
Windows runner cannot rewrite a byte-exact vector, and none may contain CRLF. It is run on Windows by the `lab-windows` job
and on Linux by the same workflow, so the rule is checked where the trap is and where the files are authored.

  check_eol.py [path ...]          paths are relative to the repository root; the default is DEFAULT_PATHS

Exit 0: every listed path has at least one tracked file, every file is -text, none has CRLF or mixed line endings in the
index or in the working tree. Exit 1: a violation. Exit 2: cannot run (not a git work tree, a path with no tracked file).
Non-vacuity: a path with zero tracked files fails, so a typo or a moved directory cannot make the check pass by scanning nothing.
"""
import re
import subprocess
import sys

DEFAULT_PATHS = [
    "lab/conformance",
    "docs/design/mesh/conformance-examples",
    "docs/design/mesh/manifest-vectors",
    "desktop/packaging/windows/winplatform/src/test/resources/fixtures",
]

LINE = re.compile(r"^i/(?P<index>\S*)\s+w/(?P<work>\S+)\s+attr/(?P<attr>.*?)\s*\t(?P<path>.+)$")


def ls_eol(path):
    out = subprocess.run(["git", "ls-files", "--eol", "--cached", "--others", "--exclude-standard", "--", path], capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        print(f"eol check: git ls-files failed for {path}: {out.stderr.strip()}")
        sys.exit(2)
    rows = []
    for raw in out.stdout.splitlines():
        m = LINE.match(raw)
        if not m:
            print(f"eol check: cannot parse git output line: {raw!r}")
            sys.exit(2)
        rows.append(m.groupdict())
    return rows


def main(argv):
    paths = argv or DEFAULT_PATHS
    bad = []
    total = 0
    for p in paths:
        rows = ls_eol(p)
        if not rows:
            print(f"eol check: no tracked file under {p}; refusing to pass by scanning nothing")
            return 2
        total += len(rows)
        for r in rows:
            attrs = r["attr"].split()
            if "-text" not in attrs:
                bad.append(f"{r['path']}: attribute is '{r['attr']}', expected -text")
            if r["index"] in ("crlf", "mixed") or r["work"] in ("crlf", "mixed"):
                bad.append(f"{r['path']}: line endings are i/{r['index']} w/{r['work']}, expected LF (or no text conversion)")
        print(f"eol check: {len(rows)} files under {p}: all -text and LF" if not any(p in b for b in bad) else f"eol check: violations under {p}")
    if bad:
        for b in bad:
            print("VIOLATION: " + b)
        return 1
    print(f"eol check: OK ({total} files under {len(paths)} paths)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
