"""Shared helpers of the vector generators (see gen_vectors.py)."""
import json
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "conformance")
CONF = "0.2.0"


def vec(id_, desc, inp, expect, status="normative", origin="hand", detail=None):
    v = {"id": id_, "origin": origin, "status": status, "oracle": "self", "description": desc, "input": inp, "expect": expect}
    if detail is not None:
        v["expectDetail"] = detail
    return v


def ok(v):
    return {"ok": v}


def rej(code):
    return {"reject": code}


def write(name, family, refs, vectors):
    path = os.path.join(ROOT, "router", name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    ids = [v["id"] for v in vectors]
    assert len(ids) == len(set(ids)), "duplicate ids"
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"family": family, "confVersion": CONF, "specRefs": refs, "vectors": vectors}, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"router/{name}: {len(vectors)} vectors")


def s8(sha):
    return sha[:8]
