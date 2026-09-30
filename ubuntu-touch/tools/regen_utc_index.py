#!/usr/bin/env python3
"""Regenerates ubuntu-touch/conformance/utc/INDEX.json (sha256 of every vector file). INDEX.json detects an incomplete
checkout; it authenticates nothing (the same statement as LAB_SPEC 3.2). Bump VERSION when a vector file changes.
Usage: regen_utc_index.py [--check]. Standard library only."""
import glob
import hashlib
import json
import os
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "conformance", "utc"))


def build():
    entries = []
    for path in sorted(glob.glob(os.path.join(ROOT, "UTC*.json"))):
        raw = open(path, "rb").read()
        doc = json.loads(raw.decode("utf-8"))
        statuses = sorted({v["status"] for v in doc["vectors"]})
        entries.append({
            "path": os.path.basename(path),
            "sha256": hashlib.sha256(raw).hexdigest(),
            "family": doc["family"],
            "status": "normative" if statuses == ["normative"] else "+".join(statuses),
        })
    return entries


def main(argv):
    text = json.dumps(build(), indent=1) + "\n"
    target = os.path.join(ROOT, "INDEX.json")
    if "--check" in argv:
        if not os.path.isfile(target) or open(target, encoding="utf-8").read() != text:
            print("INDEX.json is missing or stale")
            return 1
        print(f"INDEX.json is current ({len(build())} files)")
        return 0
    with open(target, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"wrote {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
