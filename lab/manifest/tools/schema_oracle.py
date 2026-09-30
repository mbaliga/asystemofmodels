#!/usr/bin/env python3
"""Test oracle: validates the payloads of the M02 accept vectors and the M06 public derivatives against the r3 conformance schemas
(lab/conformance/manifest/schema/), and checks that the M03 SCHEMA_INVALID vectors whose defect the schema can express are rejected by it.
Needs `jsonschema` (the design session used 4.26.0); prints `schema oracle: skipped (jsonschema absent)` and exits 0 without it.
The schema is documentation and an oracle only; the verifier's typed decoder is normative (LAB_SPEC 4.1).
"""
import base64
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CONF = os.path.normpath(os.path.join(HERE, "..", "..", "conformance"))

try:
    import jsonschema
except ImportError:
    print("schema oracle: skipped (jsonschema absent)")
    sys.exit(0)


def load(rel):
    with open(os.path.join(CONF, rel), encoding="utf-8") as f:
        return json.load(f)


def payload_of(document):
    c = json.loads(document)
    p = c["dsse"]["payload"].replace("-", "+").replace("_", "/")
    return json.loads(base64.b64decode(p + "=" * (-len(p) % 4)))


def main():
    man = jsonschema.Draft202012Validator(load("manifest/schema/asom.manifest.1.schema.json"))
    pub = jsonschema.Draft202012Validator(load("manifest/schema/asom.bench-public.1.schema.json"))
    ok = bad = 0
    for v in load("manifest/M02-verify-accept.json")["vectors"]:
        errs = list(man.iter_errors(payload_of(v["input"]["document"])))
        # M02-107 carries one unknown additive member on purpose (schemaMinor 1): the strict producer schema rejects exactly that
        if v["id"] == "M02-107":
            errs = [e for e in errs if "npuOffloadPermille" not in e.message]
        if errs:
            bad += 1
            print("  DISAGREE", v["id"], errs[0].message[:160], list(errs[0].path))
        else:
            ok += 1
    for v in load("manifest/M06-derivatives.json")["vectors"]:
        if v["input"].get("kind") == "public" and "jcsUtf8" in v["expect"].get("ok", {}):
            errs = list(pub.iter_errors(json.loads(v["expect"]["ok"]["jcsUtf8"])))
            if errs:
                bad += 1
                print("  DISAGREE", v["id"], errs[0].message[:160])
            else:
                ok += 1
    expressible = {"M03-134", "M03-135", "M03-138", "M03-139", "M03-140", "M03-142", "M03-160", "M03-161", "M03-162", "M03-163", "M03-164", "M03-165", "M03-173", "M03-174"}
    rejected = 0
    for v in load("manifest/M03-verify-reject.json")["vectors"]:
        if v["id"] in expressible:
            errs = list(man.iter_errors(payload_of(v["input"]["document"])))
            if errs:
                rejected += 1
            else:
                bad += 1
                print("  DISAGREE", v["id"], "the schema accepts a payload the verifier rejects with SCHEMA_INVALID")
    print(f"schema oracle: {ok} accepted payloads agree, {rejected} SCHEMA_INVALID payloads rejected by the schema too, {bad} disagree (jsonschema {jsonschema.__version__ if hasattr(jsonschema, '__version__') else ''})".replace("  ", " "))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
