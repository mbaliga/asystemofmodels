#!/usr/bin/env python3
"""Rewrites lab/conformance/INDEX.json (LAB_SPEC 3.1): [{path, sha256, family, status}], sorted by path.

The index detects an incomplete checkout. It authenticates nothing; authenticity comes from signed tags.
Vector files are the JSON files under wire/, manifest/, router/, ledger/; the TEST-ONLY keys file is listed too.
Python 3 standard library only.
"""
import hashlib
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "conformance")
VECTOR_DIRS = ["wire", "manifest", "router", "ledger", "json", "bench"]


def entry(rel):
    path = os.path.join(ROOT, rel)
    data = open(path, "rb").read()
    doc = json.loads(data.decode("utf-8"))
    if rel.startswith("keys/"):
        family, status = "keys", "test-only"
    else:
        family = doc["family"]
        statuses = {v["status"] for v in doc["vectors"]}
        status = "normative" if "normative" in statuses else sorted(statuses)[0]
    return {"path": rel, "sha256": hashlib.sha256(data).hexdigest(), "family": family, "status": status}


def main():
    rels = []
    for d in VECTOR_DIRS:
        dd = os.path.join(ROOT, d)
        if os.path.isdir(dd):
            rels += [f"{d}/{n}" for n in os.listdir(dd) if n.endswith(".json")]
    if os.path.isfile(os.path.join(ROOT, "keys", "TEST-ONLY-keys.json")):
        rels.append("keys/TEST-ONLY-keys.json")
    entries = [entry(r) for r in sorted(rels)]
    out = os.path.join(ROOT, "INDEX.json")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(entries, indent=2) + "\n")
    print(f"INDEX.json: {len(entries)} entries")
    return 0


if __name__ == "__main__":
    sys.exit(main())
