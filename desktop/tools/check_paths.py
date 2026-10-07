#!/usr/bin/env python3
"""Fails if any tracked path cannot exist on Windows (or collides on a case-insensitive filesystem).

The first hosted Windows run failed at checkout because a synthetic sysfs fixture directory contained ':'
(real Linux names do). Windows cannot check such a repository out at all, so every Windows job dies before it runs
anything. This check runs in Linux CI so the mistake fails there, in seconds, instead.

Illegal: < > : " | ? * and control characters; a component ending in '.' or ' '; the reserved device names
CON PRN AUX NUL COM1-9 LPT1-9 (with or without an extension); paths whose length exceeds 240.
Case collisions: two tracked paths equal after lower-casing.
Usage: check_paths.py [--repo DIR]      exit 0 when clean
"""
import re
import subprocess
import sys

ILLEGAL = re.compile(r'[<>:"|?*\x00-\x1f]')
RESERVED = re.compile(r'^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?$', re.I)


def problems(paths):
    out = []
    seen = {}
    for p in paths:
        if ILLEGAL.search(p):
            out.append(f"illegal character: {p}")
        if len(p) > 240:
            out.append(f"path longer than 240: {p[:80]}...")
        for comp in p.split('/'):
            if comp.endswith(('.', ' ')) and comp not in ('.', '..'):
                out.append(f"component ends in dot or space: {p}")
            if RESERVED.match(comp):
                out.append(f"reserved device name: {p}")
        low = p.lower()
        if low in seen and seen[low] != p:
            out.append(f"case collision: {p} vs {seen[low]}")
        seen[low] = p
    return out


def selftest():
    bad = problems(["a/b:c", "ok/file", "x/NUL.txt", "Dir/f", "dir/f", "trail./x", 'q/"n"'])
    kinds = sorted({b.split(':')[0] for b in bad})
    assert kinds == ["case collision", "component ends in dot or space", "illegal character", "reserved device name"], kinds
    assert problems(["hid-0003%3A28DE/x", "plain/path.kt"]) == []
    print("check_paths selftest: ok (4 problem kinds detected, 2 clean paths accepted)")


def main():
    if "--selftest" in sys.argv:
        selftest()
        return 0
    repo = "."
    if "--repo" in sys.argv:
        repo = sys.argv[sys.argv.index("--repo") + 1]
    paths = subprocess.check_output(["git", "-C", repo, "ls-files"], text=True).splitlines()
    bad = problems(paths)
    for b in bad:
        print("WINDOWS-UNSAFE:", b)
    print(f"check_paths: {len(paths)} tracked paths checked, {len(bad)} problems")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
