#!/usr/bin/env python3
"""Test oracle: validates the payloads of the M02 accept vectors and the M06 public derivatives against the r3 conformance schemas
(lab/conformance/manifest/schema/), and checks that the M03 SCHEMA_INVALID vectors whose defect the schema can express are rejected by it.
Needs `jsonschema` (the design session used 4.26.0); prints `schema oracle: skipped (jsonschema absent)` and exits 0 without it,
or exits 2 with `--require` (CI passes it: a skipped oracle must not read as a pass).
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
    if "--require" in sys.argv[1:]:
        print("schema oracle: jsonschema is required and absent")
        sys.exit(2)
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
    # PP3 (LAB_SPEC R4-L-14): every family a manifest may state is a family its public derivative may state
    families = man.schema["$defs"]["body"]["properties"]["device"]["properties"]["os"]["properties"]["family"]["enum"]
    pp3 = 0
    for v in load("manifest/M06-derivatives.json")["vectors"]:
        if v["input"].get("kind") == "public" and "jcsUtf8" in v["expect"].get("ok", {}):
            for family in families:
                d = json.loads(v["expect"]["ok"]["jcsUtf8"])
                d["device"]["osFamily"] = family
                errs = list(pub.iter_errors(d))
                if errs:
                    bad += 1
                    print("  DISAGREE", v["id"], family, errs[0].message[:160])
                else:
                    pp3 += 1
    if pp3 == 0 or not {"windows", "ubuntu-touch"} <= set(families):
        bad += 1
        print("  DISAGREE PP3 exercised", pp3, "derivatives over", families)
    print(f"schema oracle PP3: {pp3} (vector, family) derivatives accepted by the public schema")
    # R4-B-01: a manifest embedding each closed end reason (old and new) validates, an unknown one does not, in both places the enum lives
    sample = next(payload_of(v["input"]["document"]) for v in load("manifest/M02-verify-accept.json")["vectors"]
                  if payload_of(v["input"]["document"])["body"]["bench"]["sustain"] is not None)
    reasons = ["THERMAL_HARD", "BATTERY_TEMP", "BATTERY_LOW", "POWER_SAVER", "USER_STOP", "BACKGROUNDED", "CHARGER_REMOVED", "MEMORY_PRESSURE", "WALL_CAP", "PROBE_LOST", "DEVICE_BUSY"]
    end_extra = ["PLATEAU", "TIME_CAP", "THERMAL_SOFT", "YIELDED"]
    r4 = rejected_r4 = 0
    for reason in reasons + end_extra:
        for place in ("abort", "endReason"):
            if place == "abort" and reason in end_extra:
                continue
            d = json.loads(json.dumps(sample))
            if place == "abort":
                d["body"]["bench"]["run"]["abort"] = {"reason": reason, "atMs": 400000}
            else:
                d["body"]["bench"]["sustain"]["endReason"] = reason
            errs = list(man.iter_errors(d))
            if errs:
                bad += 1
                print("  DISAGREE R4-B-01", place, reason, errs[0].message[:160])
            else:
                r4 += 1
    for place in ("abort", "endReason"):
        for unknown in ("BATTERY_WARM", "battery_low", ""):
            d = json.loads(json.dumps(sample))
            if place == "abort":
                d["body"]["bench"]["run"]["abort"] = {"reason": unknown, "atMs": 400000}
            else:
                d["body"]["bench"]["sustain"]["endReason"] = unknown
            if list(man.iter_errors(d)):
                rejected_r4 += 1
            else:
                bad += 1
                print("  DISAGREE R4-B-01 the schema accepts the unknown reason", repr(unknown), "in", place)
    bench_props = man.schema["$defs"]["bench"]["properties"]
    abort_enum = next(o["properties"]["reason"]["enum"] for o in bench_props["run"]["properties"]["abort"]["oneOf"] if "properties" in o)
    end_enum = next(o["properties"]["endReason"]["enum"] for o in bench_props["sustain"]["oneOf"] if "properties" in o)
    for need in ("BATTERY_LOW", "POWER_SAVER", "DEVICE_BUSY"):
        for place, enum in (("run.abort.reason", abort_enum), ("sustain.endReason", end_enum)):
            if need not in enum:
                bad += 1
                print("  DISAGREE R4-B-01", need, "is missing from the generated schema enum", place)
    if r4 < 3 * 2 or rejected_r4 < 6:
        bad += 1
        print("  DISAGREE R4-B-01 exercised", r4, "accepted and", rejected_r4, "rejected, expected at least 6 and 6")
    print(f"schema oracle R4-B-01: {r4} end-reason embeddings accepted, {rejected_r4} unknown reasons rejected")
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
    if ok < 20 or rejected < 14:
        bad += 1
        print("  DISAGREE the oracle exercised", ok, "accepted and", rejected, "rejected payloads, expected at least 20 and 14")
    from importlib.metadata import version
    print(f"schema oracle: {ok} accepted payloads agree, {rejected} SCHEMA_INVALID payloads rejected by the schema too, {bad} disagree (jsonschema {version('jsonschema')})")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
