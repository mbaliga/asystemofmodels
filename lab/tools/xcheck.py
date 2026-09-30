#!/usr/bin/env python3
"""xcheck: the Python cross-checker for lab/conformance (LAB_SPEC 3.10). Python 3 standard library only.

Usage: python3 lab/tools/xcheck.py lab/conformance [--families W01,keys,INDEX,M01,...]

Prints one line per family: `xcheck <family>: <n> agree, <d> disagree`, or `xcheck <family>: absent` (exit 0)
for a family that has no vectors yet. Exits non-zero on any disagreement.

What it checks at L0.1:
  W01   every vector's whole header map (Served-By rule, Egress always, cost headers only with an estimate
        AND a basis) and its USD string, re-derived independently from the input:
        decimal.Decimal(repr(float(s))).quantize(Decimal('1E-8'), ROUND_HALF_UP), zeros stripped, plain notation.
  keys  the TEST-ONLY keys file: SPKI shape (91 bytes, fixed prefix), point on P-256, nodeId, nodeTag and both
        fingerprint forms recomputed from the SPKI (LAB_SPEC 4.5).
  INDEX every sha256 in INDEX.json, sort order, and that no vector file is unlisted.
  M01der, M02, M03, M05 (manifest renderings), M06   lab/manifest/tools/xcheck_manifest.py: a pure-Python P-256/ES256 verifier and an
        independent implementation of verifier steps 1-10, plus openssl on every 64-octet signature; DER codec; header, public derivative
        and FILE projection re-derivation
  M04, M05 (bench body)   lab/bench-core/tools/xcheck_m04.py: bench_ref.py (the design sketch) plus the B7/9.4 rules ported, the
        benchmark.md 4.2 pin table, the 5.2 plan JSON, the 11.3 ceilings and the 11.4 edge list parsed or transcribed from the spec
Family W05 is owned by a later work item; it prints `absent` until its vectors exist.

It was written in the same session as the generators, so its agreement NEVER clears the `oracle: self` tag
(LAB_SPEC 4.10, R9): it shows consistency, not independent reading.
"""
import base64
import hashlib
import json
import os
import sys
from decimal import ROUND_HALF_UP, Decimal

VECTOR_DIRS = ["wire", "manifest", "router", "ledger", "json", "bench", "policy"]
DEFAULT_FAMILIES = ["W01", "keys", "INDEX", "M01", "M01der", "M02", "M03", "M04", "M05", "M06", "W05"]

P256_P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
P256_B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
SPKI_PREFIX = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200")


def load_vector_files(root):
    for d in VECTOR_DIRS:
        dd = os.path.join(root, d)
        if not os.path.isdir(dd):
            continue
        for n in sorted(os.listdir(dd)):
            if n.endswith(".json"):
                with open(os.path.join(dd, n), "rb") as f:
                    yield f"{d}/{n}", json.loads(f.read().decode("utf-8"))


def usd(cost_text):
    q = Decimal(repr(float(cost_text))).quantize(Decimal("1E-8"), rounding=ROUND_HALF_UP)
    return format(q.normalize(), "f")


def w01_expected(inp):
    sp, sm = inp.get("servedProvider"), inp.get("servedModel")
    cost, basis = inp.get("costEst"), inp["costBasis"]
    h = {}
    if sp is not None and sm is not None:
        h["x-asom-served-by"] = f"{sp}/{sm}"
    h["x-asom-egress"] = inp["egress"]
    if cost is not None and basis != "none":
        h["x-asom-cost-est"] = usd(cost)
        h["x-asom-cost-basis"] = basis
    return h


def check_w01(root):
    agree = disagree = 0
    for rel, doc in load_vector_files(root):
        if doc.get("family") != "W01":
            continue
        for v in doc["vectors"]:
            want = w01_expected(v["input"])
            got = {k.lower(): val for k, val in v["expect"]["ok"].items()}
            if want == got:
                agree += 1
            else:
                disagree += 1
                print(f"  DISAGREE {v['id']}: xcheck derived {want} but the vector says {got}", file=sys.stderr)
    return agree, disagree


def b32(b):
    return base64.b32encode(b).decode().rstrip("=")


def check_keys(root):
    path = os.path.join(root, "keys", "TEST-ONLY-keys.json")
    if not os.path.isfile(path):
        return None
    doc = json.load(open(path, encoding="utf-8"))
    agree = disagree = 0
    if doc.get("TEST_ONLY") is not True:
        print("  DISAGREE keys: TEST_ONLY is not true", file=sys.stderr)
        disagree += 1
    for name in [k for k in ("key1", "key2", "key3", "key4") if k in doc]:
        k = doc[name]
        spki = base64.b64decode(k["spki_b64"])
        problems = []
        if len(spki) != 91 or spki[:26] != SPKI_PREFIX or spki[26] != 4:
            problems.append("SPKI is not the 91-byte uncompressed P-256 form")
        else:
            x = int.from_bytes(spki[27:59], "big")
            y = int.from_bytes(spki[59:91], "big")
            if (y * y - (x * x * x - 3 * x + P256_B)) % P256_P != 0:
                problems.append("point is not on P-256")
        pin = hashlib.sha256(spki).digest()
        node_id = base64.urlsafe_b64encode(pin).decode().rstrip("=")
        tag = b32(pin).lower()[:16]
        display = "-".join(tag.upper()[i:i + 4] for i in range(0, 16, 4))
        fp = b32(pin[:16])
        export = "-".join([fp[0:5], fp[5:10], fp[10:14], fp[14:18], fp[18:22], fp[22:26]])
        for field, want in (("nodeId", node_id), ("nodeTag", tag), ("fingerprint", display), ("exportFingerprint", export)):
            if k.get(field) != want:
                problems.append(f"{field}: file has {k.get(field)!r}, recomputed {want!r}")
        if problems:
            disagree += 1
            print(f"  DISAGREE keys/{name}: {problems}", file=sys.stderr)
        else:
            agree += 1
    return agree, disagree


def check_index(root):
    path = os.path.join(root, "INDEX.json")
    if not os.path.isfile(path):
        return None
    entries = json.load(open(path, encoding="utf-8"))
    agree = disagree = 0
    paths = [e["path"] for e in entries]
    if paths != sorted(paths):
        disagree += 1
        print("  DISAGREE INDEX: not sorted by path", file=sys.stderr)
    else:
        agree += 1
    for e in entries:
        f = os.path.join(root, e["path"])
        if not os.path.isfile(f):
            disagree += 1
            print(f"  DISAGREE INDEX: {e['path']} missing", file=sys.stderr)
        elif hashlib.sha256(open(f, "rb").read()).hexdigest() != e["sha256"]:
            disagree += 1
            print(f"  DISAGREE INDEX: {e['path']} sha256 differs", file=sys.stderr)
        else:
            agree += 1
    listed = set(paths)
    for rel, _ in load_vector_files(root):
        if rel not in listed:
            disagree += 1
            print(f"  DISAGREE INDEX: {rel} is a vector file but is not listed", file=sys.stderr)
    return agree, disagree


def check_m01(root):
    """M01 is checked by the :json track's own Python implementation (lab/json/tools/xcheck_m01.py)."""
    import importlib.util

    spec = importlib.util.spec_from_file_location("xcheck_m01", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "json", "tools", "xcheck_m01.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.check_m01(root)


def _load_tool(rel, name):
    import importlib.util

    spec = importlib.util.spec_from_file_location(name, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", *rel))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def check_manifest_family(root, fam):
    mod = _load_tool(("manifest", "tools", "xcheck_manifest.py"), "xcheck_manifest")
    out, _ = mod.run_all(root, [fam])
    c = out.get(fam)
    return None if c is None else (c.agree, c.dis)


def check_bench_family(root, fam):
    mod = _load_tool(("bench-core", "tools", "xcheck_m04.py"), "xcheck_m04")
    a = d = 0
    if fam == "M04":
        r = mod.check_m04(root)
        if r is not None:
            a, d = r[0].agree, r[0].dis
    else:
        c = mod.check_m05_body(root)
        if c is not None:
            a, d = c.agree, c.dis
    return (a, d)
def check_policy(root, family):
    """L01, W07 and W07p are checked by the lab-ledger-policy track's own Python implementation (lab/mesh-policy/tools/xcheck_policy.py)."""
    import importlib.util

    spec = importlib.util.spec_from_file_location("xcheck_policy", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "mesh-policy", "tools", "xcheck_policy.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.check(root, family)


def family_present(root, family):
    return any(doc.get("family") == family for _, doc in load_vector_files(root))


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    root = args[0] if args else "lab/conformance"
    families = DEFAULT_FAMILIES
    for i, a in enumerate(argv):
        if a == "--families" and i + 1 < len(argv):
            families = argv[i + 1].split(",")
    bad = 0
    for fam in families:
        if fam == "W01":
            res = check_w01(root)
        elif fam == "keys":
            res = check_keys(root)
        elif fam == "INDEX":
            res = check_index(root)
        elif fam == "M01":
            res = check_m01(root)
        elif fam in ("M01der", "M02", "M03", "M06"):
            res = check_manifest_family(root, fam)
        elif fam == "M04":
            res = check_bench_family(root, fam)
        elif fam == "M05":
            m = check_manifest_family(root, "M05") or (0, 0)
            b = check_bench_family(root, "M05") or (0, 0)
            res = (m[0] + b[0], m[1] + b[1])
        elif fam in ("L01", "W07", "W07p"):
            res = check_policy(root, fam)
        elif family_present(root, fam):
            print(f"xcheck {fam}: present but not covered by this xcheck build", file=sys.stderr)
            bad += 1
            continue
        else:
            res = None
        if res is None or (res[0] == 0 and res[1] == 0):
            print(f"xcheck {fam}: absent")
            continue
        print(f"xcheck {fam}: {res[0]} agree, {res[1]} disagree")
        bad += res[1]
    print("xcheck oracle status: self-oracled (same-session cross-check; never clears the oracle tag)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
