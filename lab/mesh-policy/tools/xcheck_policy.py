#!/usr/bin/env python3
"""A second implementation (Python 3, standard library only) of the pure-policy families L01, W07 and W07p, used by lab/tools/xcheck.py.

It re-derives every vector's expected value from the spec text (LAB_SPEC 6.2, 6.3, 6.5, 7.2; design 3.2, 7.4, 8.5, 8.6) and compares it with the committed
vector. It was written in the SAME session as the Kotlin code and the vectors, so its agreement shows consistency, never independence: it does NOT clear the
`oracle: self` tag (LAB_SPEC 4.10, R9). L02 is not covered here (its expected rows are typed by hand in gen_vectors.py and checked against the real
Kotlin ledger classes by the runner).
"""
import json
import os
import re

MAX_SAFE = 9007199254740991


# ------------------------------------------------------------------------------------------------------------------------------ L01
def destination_set(i):
    fb = i["fallback"]
    fallback_mode = fb is not None
    restricted = i["deviceOnly"] or i["localOnly"]
    t = not fallback_mode
    o = (not fallback_mode) and i["meshGlobal"] and i["appMesh"] and not restricted
    c = (not restricted) and (not i["cloudBan"]) and ((not fallback_mode) or len(fb) > 0)
    p = [d for d, on in (("T", t), ("O", o), ("C", c)) if on]
    named = {("T",): "device-only", ("T", "O"): "own-devices", ("T", "O", "C"): "own-and-cloud", ("T", "C"): "cloud-no-peers"}.get(tuple(p))
    return {"P": p, "class": named, "cloudRestrictedTo": list(fb) if (fallback_mode and c) else None}


def l01(v):
    i = v["input"]
    k = i["kind"]
    if k == "destinationSet":
        return {"ok": destination_set(i)}
    if k == "appDefault":
        return {"ok": {"meshAllowed": bool(i["userTicked"]) if i["userTicked"] is not None else False}}
    if k == "eligibility":
        if "O" not in i["P"] or not i["routeEnabled"]:
            return {"reject": "F1_ELIGIBILITY"}
        if i["status"] != "PAIRED":
            return {"reject": "F2_NOT_PAIRED"}
        if not i["inferGranted"]:
            return {"reject": "F3_NO_SCOPE"}
        return {"ok": {"eligible": True}}
    if k == "quiescence":
        if i["role"] == "LENDER":
            return {"ok": {"mayInitiate": i["userOp"]}}
        return {"ok": {"mayInitiate": i["pending"] or i["screen"] or i["userOp"] or i["finishing"]}}
    if k == "inbound":
        return {"ok": {"mayAccept": i["fsm"] == "SERVING" or i["pairingWindow"] or i["peersTab"]}}
    if k == "sessionClose":
        return {"ok": {"close": (i["openStreams"] == 0 and i["idleMs"] >= 300000) or i["ageMs"] >= 1800000}}
    raise ValueError(k)


# ------------------------------------------------------------------------------------------------------------------------------ W07
def jcs(o):
    return json.dumps(o, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


BANDS = ["ge80", "50-79", "20-49", "lt20"]
BACKENDS = ["cpu", "vulkan", "metal", "opencl", "cuda", "hexagon"]
PRESENCE_NAMES = {
    "user", "active", "inflight", "busyForMs", "loaded", "estStartS", "estStartMs", "reason", "localActive", "depth", "queuePos", "ttftMs", "totalMs", "usage", "screen",
    "idle", "foreground", "console", "login", "keyguard", "batteryPermille", "availBytes", "memory", "headroomPermille", "forecastPermille",
}
MEMBERS = {
    "": {"availability", "engine", "manifest", "power", "queue", "sampledAgeMs", "seq", "thermal", "v"},
    "availability": {"fsm"}, "engine": {"backend", "commit", "confVersion", "held"}, "manifest": {"bodyDigest", "seq"}, "power": {"batteryBand", "charging", "source"},
    "queue": {"bucket"}, "thermal": {"band", "governor"},
}


class Reject(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def strict_json(text):
    def pairs(ps):
        names = [k for k, _ in ps]
        if len(names) != len(set(names)):
            raise Reject("DUPLICATE_KEY")
        return dict(ps)

    def bad_float(s):
        raise Reject("NON_INTEGER_NUMBER")

    def integer(s):
        if s == "-0":
            raise Reject("NON_INTEGER_NUMBER")
        n = int(s)
        if abs(n) > MAX_SAFE:
            raise Reject("NUMBER_RANGE")
        return n

    def const(s):
        raise Reject("NON_INTEGER_NUMBER")

    try:
        return json.loads(text, object_pairs_hook=pairs, parse_float=bad_float, parse_int=integer, parse_constant=const)
    except Reject:
        raise
    except ValueError:
        raise Reject("MALFORMED_JSON")


def build_state(view, seq, age):
    q = min(2, view["localQueued"] + view["peerQueued"])
    man = None if (view["manifestSeq"] is None or view["manifestDigest"] is None) else {"bodyDigest": view["manifestDigest"], "seq": view["manifestSeq"]}
    doc = {
        "availability": {"fsm": view["fsm"]},
        "engine": {"backend": view["backend"], "commit": view["commit"], "confVersion": view["confVersion"], "held": sorted(view["held"])},
        "manifest": man,
        "power": {"batteryBand": view["batteryBand"], "charging": view["charging"], "source": view["powerSource"]},
        "queue": {"bucket": q}, "sampledAgeMs": age, "seq": seq, "thermal": {"band": view["thermalBand"], "governor": view["governor"]}, "v": 1,
    }
    st = {"fsm": view["fsm"], "gov": view["governor"], "qb": q, "seq": seq, "tb": view["thermalBand"]}
    return doc, st


def parse_state(text):
    d = strict_json(text)
    if not isinstance(d, dict):
        raise Reject("WRONG_TYPE")

    def need(o, k, typ=None):
        if k not in o:
            raise Reject("MISSING_MEMBER")
        v = o[k]
        if typ is bool and not isinstance(v, bool):
            raise Reject("WRONG_TYPE")
        if typ is int and (isinstance(v, bool) or not isinstance(v, int)):
            raise Reject("WRONG_TYPE")
        if typ is str and not isinstance(v, str):
            raise Reject("WRONG_TYPE")
        if typ is dict and not isinstance(v, dict):
            raise Reject("WRONG_TYPE")
        return v

    if need(d, "v", int) != 1:
        raise Reject("BAD_VERSION")
    seq = need(d, "seq", int)
    if seq < 1:
        raise Reject("OUT_OF_RANGE")
    age = need(d, "sampledAgeMs", int)
    if age < 0:
        raise Reject("OUT_OF_RANGE")
    fsm = need(need(d, "availability", dict), "fsm", str)
    if fsm not in ("OFF", "ARMED", "SERVING", "DRAINING"):
        raise Reject("UNKNOWN_ENUM")
    power = need(d, "power", dict)
    src = need(power, "source", str)
    if src not in ("ac", "battery", "unknown"):
        raise Reject("UNKNOWN_ENUM")
    charging = need(power, "charging", bool)
    if "batteryBand" not in power:
        raise Reject("MISSING_MEMBER")
    band = power["batteryBand"]
    if band is not None:
        if not isinstance(band, str):
            raise Reject("WRONG_TYPE")
        if band not in BANDS:
            raise Reject("UNKNOWN_ENUM")
    th = need(d, "thermal", dict)
    tb = need(th, "band", int)
    if tb not in (0, 1, 2):
        raise Reject("OUT_OF_RANGE")
    gov = need(th, "governor", str)
    if gov not in ("RUN", "QUEUE", "HOLD"):
        raise Reject("UNKNOWN_ENUM")
    eng = need(d, "engine", dict)
    backend = need(eng, "backend", str)
    if backend not in BACKENDS:
        raise Reject("UNKNOWN_ENUM")
    commit = need(eng, "commit", str)
    if not re.fullmatch(r"[0-9a-f]{7,40}", commit):
        raise Reject("OUT_OF_RANGE")
    conf = need(eng, "confVersion", str)
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", conf):
        raise Reject("OUT_OF_RANGE")
    if "held" not in eng:
        raise Reject("MISSING_MEMBER")
    held = eng["held"]
    if not isinstance(held, list):
        raise Reject("WRONG_TYPE")
    if len(held) > 64:
        raise Reject("OUT_OF_RANGE")
    if any(not isinstance(h, str) for h in held):
        raise Reject("WRONG_TYPE")
    if any(not re.fullmatch(r"[0-9a-f]{64}", h) for h in held):
        raise Reject("OUT_OF_RANGE")
    qb = need(need(d, "queue", dict), "bucket", int)
    if qb not in (0, 1, 2):
        raise Reject("OUT_OF_RANGE")
    if "manifest" not in d:
        raise Reject("MISSING_MEMBER")
    m = d["manifest"]
    man = None
    if m is not None:
        if not isinstance(m, dict):
            raise Reject("WRONG_TYPE")
        ms = need(m, "seq", int)
        if ms < 1:
            raise Reject("OUT_OF_RANGE")
        dg = need(m, "bodyDigest", str)
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", dg):
            raise Reject("OUT_OF_RANGE")
        man = {"bodyDigest": dg, "seq": ms}
    return {
        "availability": {"fsm": fsm}, "engine": {"backend": backend, "commit": commit, "confVersion": conf, "held": sorted(held)}, "manifest": man,
        "power": {"batteryBand": band, "charging": charging, "source": src}, "queue": {"bucket": qb}, "sampledAgeMs": min(age, 60000), "seq": seq,
        "thermal": {"band": tb, "governor": gov}, "v": 1,
    }


def producer_check(text):
    d = strict_json(text)
    if not isinstance(d, dict):
        raise Reject("WRONG_TYPE")
    unknown = False

    def walk(o, path):
        nonlocal unknown
        allowed = MEMBERS.get(path)
        if allowed is None:
            return
        for name, value in o.items():
            if name not in allowed:
                if name in PRESENCE_NAMES:
                    raise Reject("PRESENCE_FIELD")
                unknown = True
            elif isinstance(value, dict):
                walk(value, name)

    walk(d, "")
    if unknown:
        raise Reject("UNKNOWN_MEMBER")


def staleness(i):
    age = (i["nowMonoMs"] - i["rxMonoMs"]) + min(i["sampledAgeMs"], 60000)
    if i["goaway"] or (i["lastSeq"] is not None and i["seq"] < i["lastSeq"]) or (i["sessionClosed"] and age > 30000):
        c = "EXPIRED"
    elif age <= 5000:
        c = "FRESH"
    elif age <= 30000:
        c = "WARM"
    elif age <= 300000:
        c = "STALE"
    else:
        c = "EXPIRED"
    return c, age


def fast_fields(c, s):
    if c in ("FRESH", "WARM"):
        return {"thermalBand": s["thermalBand"], "queueBucket": s["queueBucket"], "batteryBand": s["batteryBand"]}
    if c == "EXPIRED":
        return None
    band = s["batteryBand"]
    if s["powerSource"] == "battery" and band is not None:
        band = BANDS[min(BANDS.index(band) + 1, 3)]
    return {"thermalBand": s["thermalBand"], "queueBucket": min(2, s["queueBucket"] + 1) if s["queueBucket"] >= 1 else s["queueBucket"], "batteryBand": band}


def w07(v):
    i = v["input"]
    k = i["kind"]
    try:
        if k == "stateBuild":
            doc, st = build_state(i["view"], i["seq"], i["sampledAgeMs"])
            return {"ok": {"jcs": jcs(doc), "st": jcs(st)}}
        if k == "stateParse":
            return {"ok": {"normal": jcs(parse_state(i["text"]))}}
        if k == "producerCheck":
            producer_check(i["text"])
            return {"ok": {"conforms": True}}
        if k == "staleness":
            c, age = staleness(i)
            r = {"class": c, "ageMs": age, "probeOnly": c == "EXPIRED"}
            if "state" in i:
                r["fast"] = fast_fields(c, i["state"])
            return {"ok": r}
        if k == "skew":
            c, _ = staleness(i)
            return {"ok": {"class": c}}
    except Reject as e:
        return {"reject": e.code}
    raise ValueError(k)


# ------------------------------------------------------------------------------------------------------------------------------ W07p
PRESENCE = {"SCREEN_INTERACTIVE", "INPUT_ACTIVITY", "KEYGUARD_DISMISSED", "FOREGROUND_APP", "HEAVY_FOREGROUND_PROCESS", "CONSOLE_USER", "LOGIN_STATE", "OTHER_PROCESS_CONTENTION"}
CONDITION = {"POWER_SOURCE", "CHARGING", "BATTERY_LEVEL", "BATTERY_TEMPERATURE", "THERMAL_BAND", "MEMORY", "PATH", "SLEEP_IMMINENT"}
PF_CONSENT = {"LEND_SCREEN_FRONTMOST", "INPUT_INSIDE_LEND_SCREEN"}
PF_PRESENCE = {"LEND_SCREEN_LEFT_FOREGROUND", "SCENE_RESIGN_ACTIVE", "TERMINAL_FOCUS_LOST", "SCREEN_OFF", "INPUT_OUTSIDE_LEND_SCREEN"}


def classify(name, pf):
    if name in PF_CONSENT or name in PF_PRESENCE:
        if not pf:
            return None
        return "consent" if name in PF_CONSENT else "presence"
    if name == "SCREEN_INTERACTIVE" and pf:
        return "consent"
    if name in PRESENCE:
        return "presence"
    if name in CONDITION:
        return "condition"
    raise ValueError(name)


def fsm_trace(i):
    pf, grace, hold = i["pf"], i["graceMs"], i.get("holdDownMs", 600000)
    fsm, last_p, cond, need, drain_start, inflight = "OFF", None, False, False, None, 0
    out = []
    for e in i["events"]:
        now, ev = e["t"], e["e"]
        if ev == "enable":
            if fsm == "OFF":
                fsm, need = "ARMED", pf
        elif ev == "disable":
            fsm, drain_start = "OFF", None
        elif ev == "start":
            need = False
        elif ev == "inflight":
            inflight = e["n"]
        elif ev == "input":
            k = classify(e["input"], pf)
            if k == "presence":
                last_p = now
                need = need or pf
                if fsm == "SERVING":
                    fsm, drain_start = "DRAINING", now
            elif k == "condition":
                cond = e["ok"]
                if not cond and fsm == "SERVING":
                    fsm, drain_start = "DRAINING", now
        if fsm == "DRAINING" and now > drain_start and (inflight == 0 or now - drain_start >= grace):
            fsm, drain_start = "ARMED", None
        if fsm == "ARMED" and cond and not need and (last_p is None or now >= last_p + hold):
            fsm = "SERVING"
        out.append(fsm)
    return out


def clamp(ms):
    return max(5000, min(600000, ms))


def decide(o, s):
    lim = s.get("limits") or {"maxBodyBytes": 8388608, "maxTokens": 4096, "maxConcurrent": 1, "rpm": 30}
    aid = o["attemptId"]

    def dec(row, code, retry):
        return {"row": row, "reply": "decline", "code": code, "retryAfterMs": retry, "wire": jcs({"attemptId": aid, "code": code, "retryAfterMs": retry})}

    def err(row, code, close):
        return {"row": row, "reply": "error", "code": code, "close": close, "wire": jcs({"attemptId": aid, "code": code})}

    if s["registryStatus"] != "PAIRED":
        return err("1", "PEER_NOT_PAIRED", True)
    if not s["inferScopeGranted"]:
        return dec("2", "SCOPE_DENIED", 30000)
    if s["attemptSeenWithin24h"]:
        return dec("3", "DUPLICATE_ATTEMPT", 5000)
    if not s["modelAllowedAndLoadable"]:
        return dec("4", "MODEL_NOT_OFFERED", 30000)
    if o["promptBytes"] > lim["maxBodyBytes"] or o["maxTokens"] > lim["maxTokens"]:
        return err("5", "FRAME_TOO_LARGE", False)
    if s["presenceActive"]:
        return dec("6", "PEER_UNAVAILABLE", clamp(s["presenceHoldRemainingMs"]))
    if not s["servingConditionsOk"]:
        return dec("6", "PEER_UNAVAILABLE", 30000)
    if s["predictedThermalHold"]:
        return dec("6a", "PEER_UNAVAILABLE", 30000)
    if s["estStartMs"] > o["deadlineMs"]:
        return dec("6b", "PEER_BUSY", 5000)
    if s["inflight"] >= lim["maxConcurrent"] or s["requestsThisMinute"] >= lim["rpm"]:
        return dec("7", "PEER_BUSY", 5000)
    return {"row": "8", "reply": "accept", "retain": "none", "wire": jcs({"attemptId": aid, "fileSha256": "a" * 64, "servedModel": o["model"]})}


def flatten(o, path=""):
    if isinstance(o, dict):
        r = {}
        for k, v in o.items():
            r.update(flatten(v, k if not path else path + "." + k))
        return r
    return {path: jcs(o)}


def wire_diff(i):
    view = i["view"]
    other = dict(view)
    vary = i["vary"]
    if "presence" in vary:
        other["presence"] = vary["presence"]
    for k in ("fsm", "localQueued", "governor"):
        if k in vary:
            other[k] = vary[k]
    a, _ = build_state(view, 7, 800)
    b, _ = build_state(other, 7, 800)
    fa, fb = flatten(a), flatten(b)
    return sorted(k for k in set(fa) | set(fb) if fa.get(k) != fb.get(k))


def w07p(v):
    i = v["input"]
    k = i["kind"]
    if k == "fsmTrace":
        return {"ok": {"fsm": fsm_trace(i)}}
    if k == "classify":
        c = classify(i["input"], i["pf"])
        return {"reject": "PF_ONLY_INPUT"} if c is None else {"ok": c}
    if k == "lenderDecision":
        return {"ok": decide(i["offer"], i["situation"])}
    if k == "wireDiff":
        return {"ok": {"changed": wire_diff(i)}}
    raise ValueError(k)


FAMILIES = {"L01": l01, "W07": w07, "W07p": w07p}


def check(root, family):
    """Returns (agree, disagree) for one family, printing each disagreement."""
    agree = disagree = 0
    for sub in ("ledger", "policy"):
        d = os.path.join(root, sub)
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            if not name.endswith(".json"):
                continue
            doc = json.load(open(os.path.join(d, name), encoding="utf-8"))
            if doc.get("family") != family:
                continue
            for v in doc["vectors"]:
                got = FAMILIES[family](v)
                if got == v["expect"]:
                    agree += 1
                else:
                    disagree += 1
                    print(f"  DISAGREE {v['id']}: python {json.dumps(got)[:300]} vs vector {json.dumps(v['expect'])[:300]}")
    return agree, disagree


if __name__ == "__main__":
    import sys

    root = sys.argv[1] if len(sys.argv) > 1 else "lab/conformance"
    bad = 0
    for fam in FAMILIES:
        a, d = check(root, fam)
        print(f"xcheck {fam}: {a} agree, {d} disagree")
        bad += d
    sys.exit(1 if bad else 0)
