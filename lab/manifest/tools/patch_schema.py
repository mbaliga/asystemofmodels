#!/usr/bin/env python3
"""Writes lab/conformance/manifest/schema/asom.manifest.1.schema.json and asom.bench-public.1.schema.json from the design session's r0 schemas
(docs/design/mesh/manifest-vectors/) by applying the r3 patch list P1..P10 of LAB_SPEC 4.1, the extra patches P11 and B1..B8 recorded in
lab/ERRATA.md (ERR-CLOSURE-1, ERR-SCHEMA-1), and the public-derivative patches PP1..PP2. Python 3 standard library only.

The JSON Schema is DOCUMENTATION AND A TEST ORACLE ONLY (LAB_SPEC 4.1): the verifier's step 11 is the hand-written typed decoder in
xyz.mdhv.asom.lab.manifest.ManifestDecoder. Run:  python3 lab/manifest/tools/patch_schema.py
"""
import copy
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
SRC = os.path.join(ROOT, "..", "docs", "design", "mesh", "manifest-vectors")
DST = os.path.join(ROOT, "conformance", "manifest", "schema")

FORBIDDEN = ["derived", "render", "textSha256", "field", "custom"]
DAY = 86400000
BYTES_MAX = 2 ** 50


def load(name):
    with open(os.path.join(SRC, name), encoding="utf-8") as f:
        return json.load(f)


def forbid_names(node):
    """P7: no `derived`, `render`, `textSha256`, `field` or `custom` member in any object the schema describes."""
    if isinstance(node, dict):
        if node.get("type") == "object" and "properties" in node:
            node["propertyNames"] = {"not": {"enum": FORBIDDEN}}
        for v in node.values():
            forbid_names(v)
    elif isinstance(node, list):
        for v in node:
            forbid_names(v)


def bench_schema():
    """B1..B8: the asom.bench/1 document as it travels in body.bench (active lane only, raw samples, nothing derived)."""
    u = {"$ref": "#/$defs/u53"}
    un = {"oneOf": [u, {"type": "null"}]}
    t = {"$ref": "#/$defs/text"}
    tn = {"oneOf": [t, {"type": "null"}]}
    tiers = ["T0", "T1", "T2", "T3", "T4", "T5"]
    win = {"type": "array", "minItems": 4, "maxItems": 4,
           "prefixItems": [{"type": "integer", "minimum": 0, "maximum": 86400000}, {"type": "integer", "minimum": 1, "maximum": 10000000},
                           {"type": "integer", "minimum": 1, "maximum": 3600000000}, {"type": "integer", "minimum": 0, "maximum": 4}]}
    span = {"type": "integer", "minimum": 1, "maximum": 86400000000}
    return {
        "type": "object", "additionalProperties": False,
        "required": ["schema", "benchProtocol", "benchSet", "harness", "device", "memory", "run", "tiers", "sustain", "energy"],
        "properties": {
            "schema": {"const": "asom.bench/1"},
            "benchProtocol": {"const": 1},
            "benchSet": {"type": "string", "pattern": "^[a-z0-9-]{1,40}$"},
            "harness": {"type": "object", "additionalProperties": False,
                        "required": ["shell", "coreImpl", "coreVersion", "confVersion", "timingSource", "planSha256", "engine"],
                        "properties": {
                            "shell": {"enum": ["android-daemon", "android-standalone", "ios-standalone", "desktop-daemon", "desktop-cli", "ios-app", "ubuntu-touch-app"]},
                            "coreImpl": {"enum": ["jvm", "swift"]}, "coreVersion": {"$ref": "#/$defs/semver"}, "confVersion": {"$ref": "#/$defs/semver"},
                            "timingSource": {"enum": ["host-monotonic", "stream"]}, "planSha256": {"$ref": "#/$defs/b64u32"},
                            "engine": {"type": "object", "additionalProperties": False,
                                       "required": ["name", "commit", "backend", "buildFlags", "threads", "gpuLayers", "kvType", "flashAttn"],
                                       "properties": {"name": {"$ref": "#/$defs/id"}, "commit": {"type": "string", "pattern": "^[0-9a-f]{40}$"},
                                                      "backend": {"$ref": "#/$defs/id"},
                                                      "buildFlags": {"type": "array", "maxItems": 16, "uniqueItems": True, "items": t},
                                                      "threads": {"type": "integer", "minimum": 0, "maximum": 1024},
                                                      "gpuLayers": {"type": "integer", "minimum": 0, "maximum": 100000},
                                                      "kvType": {"$ref": "#/$defs/id"}, "flashAttn": {"enum": ["on", "off", "auto"]}}}}},
            "device": {"type": "object", "additionalProperties": False,
                       "required": ["platform", "form", "maker", "model", "soc", "osVersion", "osBuild", "gpu", "gpuDriver", "memTotalBytes", "unifiedMemory", "virtualized"],
                       "properties": {"platform": {"enum": ["android", "ios", "ipados", "macos", "linux", "windows", "ubuntu-touch"]},
                                      "form": {"enum": ["phone", "tablet", "handheld", "laptop", "desktop", "server"]},
                                      "maker": t, "model": t, "soc": t, "osVersion": t, "osBuild": tn, "gpu": tn, "gpuDriver": tn,
                                      "memTotalBytes": {"$ref": "#/$defs/bytes"}, "unifiedMemory": {"type": "boolean"}, "virtualized": {"type": "boolean"}}},
            "memory": {"type": "object", "additionalProperties": False, "required": ["availAtStartBytes", "processLimitBytes", "gpuWorkingSetBytes", "limitSource"],
                       "properties": {"availAtStartBytes": {"$ref": "#/$defs/bytes"}, "processLimitBytes": {"oneOf": [{"$ref": "#/$defs/bytes"}, {"type": "null"}]},
                                      "gpuWorkingSetBytes": {"oneOf": [{"$ref": "#/$defs/bytes"}, {"type": "null"}]}, "limitSource": {"$ref": "#/$defs/id"}}},
            "run": {"type": "object", "additionalProperties": False,
                    "required": ["plan", "optInTiers", "startedAtMs", "endedAtMs", "dayUtc", "powerSource", "batteryStartPermille", "startThermal", "screenOn",
                                 "contentionBeforePermille", "contentionAfterPermille", "abort"],
                    "properties": {"plan": {"enum": ["quick", "standard", "sustained", "battery", "extended", "ci"]},
                                   "optInTiers": {"type": "array", "maxItems": 6, "uniqueItems": True, "items": {"enum": tiers}},
                                   "startedAtMs": {"$ref": "#/$defs/epochMs"}, "endedAtMs": {"$ref": "#/$defs/epochMs"},
                                   "dayUtc": {"$ref": "#/$defs/date"}, "powerSource": {"enum": ["ac", "battery"]},
                                   "batteryStartPermille": {"oneOf": [{"type": "integer", "minimum": 0, "maximum": 1000}, {"type": "null"}]},
                                   "startThermal": {"enum": ["cool", "warm", "hot"]}, "screenOn": {"type": ["boolean", "null"]},
                                   "contentionBeforePermille": {"type": "integer", "minimum": 0, "maximum": 1000000},
                                   "contentionAfterPermille": {"type": "integer", "minimum": 0, "maximum": 1000000},
                                   "abort": {"oneOf": [{"type": "null"}, {"type": "object", "additionalProperties": False, "required": ["reason", "atMs"],
                                                                          "properties": {"reason": {"enum": ["THERMAL_HARD", "BATTERY_TEMP", "USER_STOP", "BACKGROUNDED", "CHARGER_REMOVED", "MEMORY_PRESSURE", "WALL_CAP", "PROBE_LOST", "DEVICE_BUSY"]},
                                                                                         "atMs": {"type": "integer", "minimum": 0}}}]}}},
            "tiers": {"type": "array", "maxItems": 6, "items": {
                "type": "object", "additionalProperties": False,
                "required": ["tier", "sha256", "bytes", "quant", "startThermal", "startThermalCode", "startedAtMs", "nCtx", "availBeforeLoadBytes", "peakFootprintBytes",
                             "kvBytesPerToken", "loadColdMicros", "loadColdness", "loadWarmMicros", "tests", "numerics", "restarts", "swapDeltaBytes"],
                "properties": {"tier": {"enum": tiers}, "sha256": {"$ref": "#/$defs/sha256hex"}, "bytes": {"$ref": "#/$defs/bytes"},
                               "quant": {"type": "string", "pattern": "^[A-Za-z0-9_.-]{1,24}$"}, "startThermal": {"enum": ["cool", "warm", "hot"]},
                               "startThermalCode": {"type": "integer", "minimum": 0, "maximum": 4}, "startedAtMs": {"$ref": "#/$defs/epochMs"},
                               "nCtx": {"type": "integer", "minimum": 1, "maximum": 10000000},
                               "availBeforeLoadBytes": {"$ref": "#/$defs/bytes"}, "peakFootprintBytes": {"$ref": "#/$defs/bytes"}, "kvBytesPerToken": {"$ref": "#/$defs/bytes"},
                               "loadColdMicros": span, "loadColdness": {"enum": ["evicted", "best-effort", "unknown"]},
                               "loadWarmMicros": {"type": "array", "minItems": 1, "maxItems": 8, "items": span},
                               "tests": {"type": "array", "maxItems": 16, "items": {
                                   "type": "object", "additionalProperties": False, "required": ["test", "samples"],
                                   "properties": {"test": {"type": "string", "pattern": "^(pp|tg)[0-9]{1,7}@d[0-9]{1,8}$"},
                                                  "samples": {"type": "array", "minItems": 1, "maxItems": 16, "items": span},
                                                  "wholeSamples": {"type": "array", "minItems": 1, "maxItems": 16, "items": span}}}},
                               "numerics": {"type": "object", "additionalProperties": False, "required": ["milliNatsPerToken", "refMilliNatsPerToken"],
                                            "properties": {"milliNatsPerToken": u, "refMilliNatsPerToken": {"oneOf": [{"type": "integer", "minimum": 1, "maximum": 9007199254740991}, {"type": "null"}]}}},
                               "restarts": {"type": "integer", "minimum": 0, "maximum": 2}, "swapDeltaBytes": {"oneOf": [{"$ref": "#/$defs/bytes"}, {"type": "null"}]}}}},
            "sustain": {"oneOf": [{"type": "null"}, {
                "type": "object", "additionalProperties": False,
                "required": ["tier", "windowMs", "capMs", "endReason", "headroomAtOnsetPermille", "windows"],
                "properties": {"tier": {"enum": tiers}, "windowMs": {"type": "integer", "minimum": 100, "maximum": 3600000},
                               "capMs": {"type": "integer", "minimum": 1, "maximum": 86400000},
                               "endReason": {"enum": ["PLATEAU", "TIME_CAP", "THERMAL_SOFT", "THERMAL_HARD", "BATTERY_TEMP", "USER_STOP", "BACKGROUNDED", "CHARGER_REMOVED",
                                                      "MEMORY_PRESSURE", "WALL_CAP", "YIELDED", "DEVICE_BUSY", "PROBE_LOST"]},
                               "headroomAtOnsetPermille": {"oneOf": [{"type": "integer", "minimum": 0, "maximum": 10000}, {"type": "null"}]},
                               "windows": {"type": "array", "minItems": 1, "maxItems": 240, "items": win}}}]},
            "energy": {"type": "null"},
        },
    }


def patch_manifest(s):
    s = copy.deepcopy(s)
    d = s["$defs"]
    body = d["body"]
    # P1: audience enum
    body["properties"]["audience"] = {"enum": ["own", "file"]}
    # P2: seq required for own, forbidden for file
    body["required"] = [r for r in body["required"] if r != "seq"] + ["bench"]
    # P4, P5
    body["properties"]["subject"]["properties"]["keyStorage"]["enum"].append("ephemeral")
    body["properties"]["device"]["properties"]["os"]["properties"]["family"]["enum"] += ["windows", "ubuntu-touch"]
    # P6: body.bench
    body["properties"]["bench"] = {"$ref": "#/$defs/bench"}
    d["bench"] = bench_schema()
    # P9: physical bounds
    d["bytes"] = {"type": "integer", "minimum": 0, "maximum": BYTES_MAX}
    d["pctRate"] = {"type": "object", "required": ["p50"], "additionalProperties": False, "properties": {
        k: {"type": "integer", "minimum": 1, "maximum": 10 ** 9} for k in ("p10", "p50", "p90")}}
    d["pctTtft"] = {"type": "object", "required": ["p50"], "additionalProperties": False, "properties": {
        k: {"type": "integer", "minimum": 1, "maximum": 3600000000} for k in ("p10", "p50", "p90")}}
    dev = body["properties"]["device"]["properties"]
    dev["memory"]["properties"]["totalBytes"] = {"$ref": "#/$defs/bytes"}
    dev["accelerators"]["items"]["properties"]["dedicatedBytes"] = {"oneOf": [{"$ref": "#/$defs/bytes"}, {"type": "null"}]}
    r = d["result"]
    r["properties"]["fileBytes"] = {"$ref": "#/$defs/bytes"}
    r["properties"]["memory"]["properties"]["availableBeforeLoadBytes"] = {"$ref": "#/$defs/bytes"}
    r["properties"]["memory"]["properties"]["peakProcessBytes"] = {"$ref": "#/$defs/bytes"}
    r["properties"]["memory"]["properties"]["kvCacheBytes"] = {"oneOf": [{"$ref": "#/$defs/bytes"}, {"type": "null"}]}
    for p in r["properties"]["prefill"]["items"]["properties"], r["properties"]["decode"]["items"]["properties"]:
        p["milliTokPerSec"] = {"$ref": "#/$defs/pctRate"}
    r["properties"]["prefill"]["items"]["properties"]["ttftMicros"] = {"$ref": "#/$defs/pctTtft"}
    su = r["properties"]["sustained"]
    su["properties"]["steadyMilliTokPerSec"] = {"type": "integer", "minimum": 1, "maximum": 10 ** 9}
    su["properties"]["curve"]["items"]["prefixItems"][1] = {"type": "integer", "minimum": 1, "maximum": 10 ** 9}
    # P11 (ERRATA ERR-SCHEMA-1): only the sustain tier has a heat test (benchmark.md 13.4 R1)
    r["properties"]["sustained"] = {"oneOf": [su, {"type": "null"}]}
    r["properties"]["measuredAtMs"] = {"$ref": "#/$defs/epochMs"}
    # P8 (conditions): the always-present part; the audience-specific part is in the root allOf below
    c = r["properties"]["conditions"]
    c["required"] = ["charging", "thermalStart"]
    # P3: presentation is audience-dependent; the members are optional here and required by the root conditionals
    pres = d["presentation"]
    pres["required"] = ["issuedAtMs"]
    pres["properties"]["expiresAtMs"] = {"$ref": "#/$defs/epochMs"}
    # P10: schemaMinor stays 0 (nothing has shipped)
    s["properties"]["schemaMinor"]["$comment"] = "P10: schemaMinor stays 0 in r3; a verifier tolerates unknown members only above the minor it knows"
    own_cond = {
        "if": {"properties": {"body": {"properties": {"audience": {"const": "own"}}}}},
        "then": {
            "properties": {
                "body": {"required": ["seq"], "properties": {"results": {"items": {"properties": {"conditions": {
                    "required": ["charging", "batteryStartPermille", "thermalStart", "socStartMilliC", "screenOn"]}}}}}},
                "presentation": {"required": ["issuedAtMs", "expiresAtMs", "challenge"]},
            }
        },
    }
    file_cond = {
        "if": {"properties": {"body": {"properties": {"audience": {"const": "file"}}}}},
        "then": {
            "properties": {
                "body": {
                    "properties": {
                        "seq": False,
                        "subject": {"properties": {"keyStorage": {"const": "ephemeral"}}},
                        "device": {"properties": {"platformIds": False, "os": {"properties": {"securityPatch": False}}}},
                        "results": {"items": {"properties": {
                            "measuredAtMs": {"type": "integer", "multipleOf": DAY},
                            "conditions": {"properties": {"batteryStartPermille": False, "screenOn": False, "socStartMilliC": False}}}}},
                        "bench": {"properties": {
                            "device": {"properties": {"osBuild": {"type": "null"}, "gpuDriver": {"type": "null"}}},
                            "run": {"properties": {"batteryStartPermille": {"type": "null"}, "screenOn": {"type": "null"},
                                                   "startedAtMs": {"type": "integer", "multipleOf": DAY}, "endedAtMs": {"type": "integer", "multipleOf": DAY}}},
                            "tiers": {"items": {"properties": {"startedAtMs": {"type": "integer", "multipleOf": DAY}}}}}},
                    }
                },
                "presentation": {"additionalProperties": False, "required": ["issuedAtMs"],
                                 "properties": {"issuedAtMs": {"type": "integer", "multipleOf": DAY}}},
            }
        },
    }
    s["allOf"] = [own_cond, file_cond]
    s["title"] = "asom capability manifest payload, major version 1 (r3 conformance schema: DOCUMENTATION AND TEST ORACLE ONLY; the verifier's decoder is normative)"
    forbid_names(s)
    return s


def patch_public(s):
    s = copy.deepcopy(s)
    # PP1: engine.commit and the harness versions may be `custom` when they are not on the release allow-list (design 5.9)
    props = s["properties"]
    props["engine"]["properties"]["commit"] = {"oneOf": [{"type": "string", "pattern": "^[0-9a-f]{7,40}$"}, {"const": "custom"}]}
    for k in ("version", "confVersion"):
        props["harness"]["properties"][k] = {"oneOf": [{"$ref": "#/$defs/semver"}, {"const": "custom"}]}
    # PP2: vendor and model are a catalogue-backed coarse name or `other` (still text)
    s["title"] = "asom public (anonymised) benchmark derivative, major version 1 (r3 conformance schema; DOCUMENTATION AND TEST ORACLE ONLY)"
    return s


def main():
    os.makedirs(DST, exist_ok=True)
    out = {"asom.manifest.1.schema.json": patch_manifest(load("asom.manifest.1.schema.json")),
           "asom.bench-public.1.schema.json": patch_public(load("asom.bench-public.1.schema.json"))}
    for name, doc in out.items():
        with open(os.path.join(DST, name), "w", encoding="utf-8", newline="\n") as f:
            json.dump(doc, f, indent=1, ensure_ascii=False)
            f.write("\n")
        print("wrote", os.path.relpath(os.path.join(DST, name), ROOT))


if __name__ == "__main__":
    main()
