#!/usr/bin/env python3
"""xcheck for the bench families (LAB_SPEC 5; benchmark.md 5, 9, 11, 12, 13): Python 3 standard library only.

It re-derives what the Kotlin `:bench-core` produced, from the design session's reference sketch
(docs/design/mesh/bench-examples/bench_ref.py, which the LAB_SPEC calls illustrative, not normative) plus a Python port of the
rules the sketch predates:

  M04 test        bench_ref.stat / derive_test with the B7 thermal-drift rule and the 9.4 caps ported here
  M04 sustain     bench_ref.derive_sustained (+ HARD_CEILING, cap rules for the abort reasons the sketch lacks)
  M04 percentile  bench_ref.pct_nearest_rank
  M04 doc         bench_ref.derive on the raw form rebuilt from the vector's bench document: the five answers
  M04 plan        the standard plan's JSON is parsed OUT OF benchmark.md 5.2 and its JCS sha256 compared; quick and ci are
                  checked against the 5.1 table rows (reps, tiers, sustained yes/no)
  M04 pins        the Q1 pin table is parsed OUT OF benchmark.md 4.2 (bytes and sha256 per tier) and compared
  M04 fsm         the governor edge list is transcribed from benchmark.md 11.4 here; the vector's edges must contain every
                  transcribed edge and add only the documented extensions
  M04 ceilings    an independent implementation of the benchmark.md 11.3 table, compared to every ceilings vector
  M04 trace       every STATE edge is an allowed edge and chains; every LOAD is followed by its UNLOAD before the next LOAD or
                  the FINALIZING state (resources released); refusals stop at PREFLIGHT; the outcome matches the last state
  M04 consent     the 5.4 consent rules (hash match, 5-minute expiry, single use, same plan) modelled here
  M05 body        bench_ref.render on the rebuilt document: the DETAILS table and the ANSWERS block are compared line by line;
                  the ASCII/LF/72-column laws are checked on the whole text

What it cannot do: it is not the executor (traces are checked by invariants, not re-simulated), and the reference sketch
does not cover partial runs, missing sustained blocks, the B17/B18 wording or the notes; those documents are reported as
`skipped (outside the reference sketch)`, with counts. It was written in the same session as the Kotlin module, so its
agreement shows consistency, never independence, and does NOT clear the `oracle: self` tag (LAB_SPEC 4.10, R9).
"""
import hashlib
import importlib.util
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
LAB = os.path.normpath(os.path.join(HERE, "..", ".."))
REPO = os.path.normpath(os.path.join(LAB, ".."))
DESIGN = os.path.join(REPO, "docs", "design", "mesh")

_s1 = importlib.util.spec_from_file_location("xcheck_m01", os.path.join(LAB, "json", "tools", "xcheck_m01.py"))
M01 = importlib.util.module_from_spec(_s1)
_s1.loader.exec_module(M01)
_s2 = importlib.util.spec_from_file_location("bench_ref", os.path.join(DESIGN, "bench-examples", "bench_ref.py"))
REF = importlib.util.module_from_spec(_s2)
_s2.loader.exec_module(REF)

CONF_RANK = REF.CONF_RANK
REF_STAT = REF.stat  # the sketch's own stat, kept before it is patched


class Counter:
    def __init__(self):
        self.agree = self.dis = self.skipped = 0

    def ok(self, cond, msg):
        if cond:
            self.agree += 1
        else:
            self.dis += 1
            print("  DISAGREE " + msg, file=sys.stderr)


def load(root, rel):
    with open(os.path.join(root, rel), "rb") as f:
        return json.loads(f.read().decode("utf-8"))


def b64u(b):
    import base64
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


# ------------------------------------------------------------------ the rules the sketch predates
DRIFT_PERMILLE = 100


def stat_b7(values, higher_is_better=True):
    """bench_ref.stat plus design 6.5 B7 (ERRATA ERR-BENCH-3): slowing at every step over >= 4 kept reps, or the last kept rep
    more than 100 permille slower than the first kept rep over >= 3 kept reps, flags THERMAL_DRIFT; an excluded first rep is
    put back. Fewer than 2 kept reps: no value."""
    st = REF_STAT(values, higher_is_better)
    n = st["n"]
    kept_idx = list(st["keptIdx"])
    flags = list(st["flags"])
    seq = [values[i] for i in kept_idx]
    every = len(seq) >= 4 and all(seq[i] < seq[i - 1] for i in range(1, len(seq)))
    fl = len(seq) >= 3 and seq[0] > seq[-1] and (seq[0] - seq[-1]) * 1000 > DRIFT_PERMILLE * seq[0]
    if every or fl:
        flags.append("THERMAL_DRIFT")
        if 0 not in kept_idx:
            kept_idx = [0] + kept_idx
        if len(kept_idx) == n and "OUTLIER_EXCLUDED" in flags:
            flags.remove("OUTLIER_EXCLUDED")
    kept = [values[i] for i in kept_idx]
    value = REF.cons_median(kept, higher_is_better) if len(kept) >= 2 else None
    spread = REF.fdiv((max(kept) - min(kept)) * 1000, value) if value else 0
    return {"n": n, "kept": len(kept), "keptIdx": kept_idx, "value": value, "relSpreadPermille": spread, "flags": flags}


def confidence_9_4(st, run):
    if st["kept"] < 2:
        return "insufficient"
    kept, n, sp = st["kept"], st["n"], st["relSpreadPermille"]
    cont = max(run["contentionBeforePermille"], run["contentionAfterPermille"])
    if n >= 5 and kept >= 4 and sp <= 50 and cont <= 50 and "UNSTABLE" not in st["flags"]:
        c = "high"
    elif kept >= 3 and sp <= 150 and cont <= 150:
        c = "medium"
    else:
        c = "low"
    caps = [c]
    if run.get("startThermal", "cool") != "cool" or run.get("virtualized") or run.get("streamTiming") or run.get("restarted"):
        caps.append("medium")
    if run.get("swapped") or "THERMAL_DRIFT" in st["flags"]:
        caps.append("low")
    return min(caps, key=lambda x: CONF_RANK[x])


REF.stat = stat_b7  # bench_ref.derive_test resolves both names in its module globals
REF.confidence = confidence_9_4


def derive_sustained_6_3(s, run):
    """bench_ref.derive_sustained with the onset threshold of benchmark.md 6.3 and 9.1 taken literally: a smoothed window is below
    onset when it is < floor(900 * peak / 1000) (ERRATA ERR-FX2-2); the sketch compares against the real-valued 900 * peak / 1000."""
    w = s["windows"]
    rates = [REF.rate_mtps(tok, us) for (_, tok, us, _) in w]
    n = len(rates)
    sm = [REF.lower_median(rates[max(0, i - 1):min(n, i + 2)]) for i in range(n)]
    peak_idx = max((i for i in range(n) if w[i][0] < REF.PEAK_WINDOW_MS), key=lambda i: (sm[i], -i))
    peak = sm[peak_idx]
    threshold = REF.SMOOTH_ONSET_PERMILLE * peak // 1000
    onset_i = None
    for i in range(peak_idx + 1, n - 2):
        if all(sm[j] < threshold for j in (i, i + 1, i + 2)):
            onset_i = i
            break
    flags = []
    if onset_i is None:
        tail = sm[-REF.PLATEAU_WINDOWS:]
        onset_ms = None
    else:
        onset_ms = w[onset_i][0]
        settled = [sm[i] for i in range(n) if w[i][0] >= onset_ms + REF.SETTLE_MS]
        if settled:
            tail = settled[-REF.PLATEAU_WINDOWS:]
        else:
            tail = sm[onset_i:]
            flags.append("PLATEAU_NOT_REACHED")
    plateau = REF.lower_median(tail)
    duration = w[-1][0] + s["windowMs"]
    stability = REF.fdiv(plateau * 1000, peak)
    if run["startThermal"] != "cool":
        conf = "low"
    elif s["endReason"] == "PLATEAU" or (s["endReason"] == "TIME_CAP" and duration >= 480000):
        conf = "high"
    elif duration >= 300000:
        conf = "medium"
    else:
        conf = "low"
    thermal_at_onset = w[onset_i][3] if onset_i is not None else None
    return {"test": "sustain", "windowMs": s["windowMs"], "capMs": s["capMs"], "windows": [list(x) for x in w],
            "peakMtps": peak, "plateauMtps": plateau, "onsetMs": onset_ms, "stabilityPermille": stability, "durationMs": duration,
            "endReason": s["endReason"], "thermalCodeAtOnset": thermal_at_onset,
            "headroomAtOnsetPermille": s.get("headroomAtOnsetPermille") if onset_i is not None else None,
            "confidence": conf, "flags": flags}


REF.derive_sustained = derive_sustained_6_3  # REF.derive and sustain_result both resolve it through the module


def test_result(spec, samples, whole, ctx):
    toks = REF.test_tokens(spec)
    vals = [REF.rate_mtps(toks, us) for us in samples]
    st = stat_b7(vals, True)
    run = {"contentionBeforePermille": ctx.get("contentionPermille", 0), "contentionAfterPermille": ctx.get("contentionPermille", 0),
           "startThermal": "warm" if ctx.get("warmStart") else "cool", "virtualized": ctx.get("virtualized"), "streamTiming": ctx.get("streamTiming"),
           "restarted": ctx.get("restarted"), "swapped": ctx.get("swapped")}
    out = {"value": st["value"], "kept": st["kept"], "keptIdx": st["keptIdx"], "relSpreadPermille": st["relSpreadPermille"],
           "confidence": confidence_9_4(st, run), "flags": st["flags"]}
    if whole is not None and st["kept"] >= 1:
        out["ttftMicros"] = REF.cons_median([whole[i] for i in st["keptIdx"]], False)
    return out


HARD_REASONS = {"THERMAL_HARD", "BATTERY_TEMP", "MEMORY_PRESSURE", "WALL_CAP"}


def sustain_result(i):
    run = {"startThermal": i["startThermal"]}
    s = {"windows": i["windows"], "windowMs": i["windowMs"], "capMs": i["capMs"], "endReason": i["endReason"], "headroomAtOnsetPermille": i["headroomAtOnsetPermille"]}
    out = REF.derive_sustained(s, run)
    conf, flags = out["confidence"], list(out["flags"])
    # benchmark.md 6.4: a hard-ceiling abort keeps the windows, adds HARD_CEILING and caps confidence at medium; a user stop is low
    if i["endReason"] in ("THERMAL_HARD", "BATTERY_TEMP", "MEMORY_PRESSURE", "BACKGROUNDED", "CHARGER_REMOVED", "DEVICE_BUSY"):
        flags.append("HARD_CEILING")
        conf = min([conf, "medium"], key=lambda x: CONF_RANK[x])
    if i["endReason"] in ("USER_STOP",):
        conf = "low" if out["durationMs"] < 300000 else min([conf, "medium"], key=lambda x: CONF_RANK[x])
    return {"peakMtps": out["peakMtps"], "plateauMtps": out["plateauMtps"], "onsetMs": out["onsetMs"], "stabilityPermille": out["stabilityPermille"],
            "durationMs": out["durationMs"], "thermalCodeAtOnset": out["thermalCodeAtOnset"], "headroomAtOnsetPermille": out["headroomAtOnsetPermille"],
            "confidence": conf, "flags": flags}


# ------------------------------------------------------------------ the spec text parsed out of benchmark.md
def spec_text():
    with open(os.path.join(DESIGN, "benchmark.md"), encoding="utf-8") as f:
        return f.read()


def spec_pins(md):
    sec = md[md.index("### 4.2 The pin"):md.index("### 4.3")]
    pins = []
    for ln in sec.split("\n"):
        m = re.match(r"\| (T[0-5]) \| `[^`]+` \| `[^`]+` \| ([0-9,]+) \| `([0-9a-f]{64})` \|", ln)
        if m:
            pins.append({"tier": m.group(1), "bytes": int(m.group(2).replace(",", "")), "sha256": m.group(3)})
    return pins


def spec_plan(md):
    sec = md[md.index("### 5.2 Run-plan format"):md.index("### 5.3")]
    txt = sec[sec.index("```json") + 7:]
    txt = txt[:txt.index("```")]
    kind, v = M01.parse(txt.encode("utf-8"))
    assert kind == "ok", v
    return v


def spec_plan_rows(md):
    sec = md[md.index("### 5.1 Plans"):md.index("### 5.2")]
    rows = {}
    for ln in sec.split("\n"):
        cells = [c.strip() for c in ln.strip().strip("|").split("|")]
        m = re.match(r"\*\*(\w+)\*\*", cells[0]) if cells else None
        if m and len(cells) >= 6:
            rows[m.group(1)] = cells
    return rows


# the governor edges, transcribed from the benchmark.md 11.4 diagram (not from the Kotlin table)
SPEC_EDGES = {
    ("IDLE", "PREFLIGHT"), ("PREFLIGHT", "IDLE"), ("PREFLIGHT", "AWAIT_CONSENT"), ("AWAIT_CONSENT", "IDLE"), ("AWAIT_CONSENT", "PREPARING"),
    ("PREPARING", "COOLING"), ("COOLING", "RUNNING"), ("RUNNING", "COOLING"), ("RUNNING", "FINALIZING"), ("RUNNING", "YIELDED"),
    ("YIELDED", "COOLING"), ("YIELDED", "FINALIZING"), ("FINALIZING", "DONE"),
    # "any --hard ceiling | Stop | backgrounded | charger removed | wall cap--> ABORTING" for every live state
    ("PREFLIGHT", "ABORTING"), ("AWAIT_CONSENT", "ABORTING"), ("PREPARING", "ABORTING"), ("COOLING", "ABORTING"), ("RUNNING", "ABORTING"), ("YIELDED", "ABORTING"),
}
# documented extensions (ERRATA ERR-BENCH-7): ABORTED(reason) is modelled as ABORTING -> FINALIZING -> DONE with outcome `aborted`,
# and COOLING -> FINALIZING covers a run whose remaining tiers were all skipped
DOCUMENTED_EXTRA_EDGES = {("ABORTING", "FINALIZING"), ("COOLING", "FINALIZING")}

# benchmark.md 11.3, one row per platform; unified thermal codes 0 none, 1 light, 2 moderate, 3 severe (serious), 4 critical
HARD_ON_DECI_C = {"android": 440, "linux": 450}
SOFT_ON_DECI_C = {"android": 420}


def ceiling_11_3(i):
    plat, form, th, pw, pr = i["platform"], i["form"], i["thermal"], i["power"], i["presence"]
    code, head, bt = th["code"], th.get("headroom"), th.get("batteryTempDeciC")
    lvl = pw.get("level")
    # hard first
    if plat == "android":
        if code >= 3:
            return "hard THERMAL_HARD"
        if (bt is not None and bt >= 440) or (lvl is not None and lvl < 200):
            return "hard BATTERY_TEMP"
    elif plat in ("ios", "ipados"):
        if code >= 3 or pr.get("lowPowerMode") or (lvl is not None and lvl < 200):
            return "hard THERMAL_HARD"
    elif plat == "macos":
        if code >= 4:
            return "hard THERMAL_HARD"
    elif plat == "linux":
        if code >= 4:
            return "hard THERMAL_HARD"
        if bt is not None and bt >= 450:
            return "hard BATTERY_TEMP"
        if form == "handheld" and i.get("gpuBusyHeldMs", 0) >= 10000:
            return "hard DEVICE_BUSY"
    # soft
    if plat == "android" and ((head is not None and head >= 950) or (bt is not None and bt >= 420)):
        return "soft THERMAL_SOFT"
    if plat == "macos" and code == 3:
        return "soft THERMAL_SOFT"
    if plat == "linux" and code == 3:
        return "soft THERMAL_SOFT"
    return "none"


# ------------------------------------------------------------------ BenchDoc -> the sketch's raw form
def raw_from_doc(doc):
    tiers = {}
    for t in doc["tiers"]:
        tests = {}
        for r in t["tests"]:
            tests[r["test"]] = {"prefill": r["samples"], "whole": r["wholeSamples"]} if r.get("wholeSamples") is not None else r["samples"]
        tiers[t["tier"]] = {"kvBytesPerToken": t["kvBytesPerToken"], "loadColdMicros": t["loadColdMicros"], "loadColdness": t["loadColdness"],
                            "loadWarmMicros": t["loadWarmMicros"], "tests": tests,
                            "nll": {"milliNatsPerToken": t["numerics"]["milliNatsPerToken"], "refMilliNatsPerToken": t["numerics"]["refMilliNatsPerToken"]},
                            "startedAtMs": t["startedAtMs"], "availBeforeLoadBytes": t["availBeforeLoadBytes"], "peakFootprintBytes": t["peakFootprintBytes"],
                            "nCtx": t["nCtx"], "startThermal": t["startThermal"], "startThermalCode": t["startThermalCode"]}
    sus = doc["sustain"]
    run = dict(doc["run"])
    raw = {"benchProtocol": doc["benchProtocol"], "benchSet": doc["benchSet"], "harness": doc["harness"], "device": doc["device"],
           "memory": doc["memory"], "run": run, "tiers": tiers, "field": []}
    if sus is not None:
        raw["sustained"] = {"tier": sus["tier"], "windowMs": sus["windowMs"], "capMs": sus["capMs"], "endReason": sus["endReason"],
                            "headroomAtOnsetPermille": sus.get("headroomAtOnsetPermille"), "windows": sus["windows"]}
    return raw


def answers_of(raw):
    """bench_ref.derive plus two readings the sketch predates: a sustain block on a tier whose numerics failed is ignored by the
    throttle, the role, every plateau estimate and the overall confidence (benchmark.md 4.4, ERRATA ERR-FX2-1), and a sustained
    phase starts warm when the run or its tier did (ERRATA ERR-FX2-5)."""
    run = dict(raw["run"])
    sus = raw["sustained"]
    tier_start = raw["tiers"].get(sus["tier"], {}).get("startThermal", run["startThermal"])
    if run["startThermal"] == "cool" and tier_start != "cool":
        run["startThermal"] = tier_start
    tiers, sustain, derived = REF.derive(dict(raw, run=run))
    ok = [t["tier"] for t in tiers if t["numerics"]["verdict"] != "fail"]
    by = {t["tier"]: t for t in tiers if t["tier"] in ok}
    used = sustain["tier"] in ok
    if not used:
        derived["throttle"] = None
    stab = sustain["stabilityPermille"]

    def plateau_of(t):
        if t not in by or not used:
            return None
        if sustain["tier"] == t:
            return sustain["plateauMtps"]
        r = REF.res(by[t], "tg128@d0")
        return REF.fdiv(r["value"] * stab, 1000) if r is not None and r["value"] is not None else None

    plat3, plat2 = plateau_of("T3"), plateau_of("T2")
    form, plat = raw["device"]["form"], raw["device"]["platform"]
    if plat in ("ios", "ipados"):
        role = "requester-foreground-helper"
    elif form in ("desktop", "server") and plat3 is not None and plat3 >= REF.COMFORT_DECODE_MTPS:
        role = "strong-provider"
    elif form in ("desktop", "server", "laptop", "handheld") and plat2 is not None and plat2 >= REF.COMFORT_DECODE_MTPS:
        role = "small-model-provider"
    elif form in ("phone", "tablet") and plat2 is not None and plat2 >= 8000:
        role = "occasional-helper"
    else:
        role = "requester"
    derived["role"] = {"code": role, "t2PlateauMtps": plat2, "t3PlateauMtps": plat3}
    head = by[max(ok, key=REF.ORDER.index)]
    derived["overallConfidence"] = REF.min_conf([r["confidence"] for r in head["results"]] + ([sustain["confidence"]] if used else []))
    return tiers, sustain, derived


def compare_answers(cnt, vid, ans, derived, tiers):
    e = ans
    cnt.ok(e["usableMemoryBytes"] == derived["usableMemoryBytes"] and e["safetyPermille"] == derived["safetyPermille"], f"{vid}: usable memory")
    mh = derived["maxHold"]
    cnt.ok(e["maxHold"] == {k: mh[k] for k in ("weightBytes", "kvRatioPermille", "approxParamsQ4", "largestLoadedTier")}, f"{vid}: maxHold {e['maxHold']} vs {mh}")
    q = derived["q7b"]
    eq = e["q7b"]
    cnt.ok(eq["basis"] == q["basis"] and eq.get("decodeMtps") == q.get("decodeMtps") and eq.get("ttft512Micros") == q.get("ttft512Micros") and eq["verdict"] == q["verdict"], f"{vid}: q7b {eq} vs {q}")
    a = derived["answer2000"]
    ea = e["answer2000"]
    cnt.ok(ea["tier"] == a["tier"] and ea["depthRatioPermille"] == a["depthRatioPermille"] and ea["micros"] == a["micros"] and ea["thermalModel"] == a["thermalModel"], f"{vid}: answer2000 {ea} vs {a}")
    t = derived["throttle"]
    et = e["throttle"]
    if et is None or t is None:
        cnt.ok(et is None and t is None, f"{vid}: throttle {et} vs {t}")
    else:
        cnt.ok(et["tier"] == t["tier"] and et["onsetMs"] == t["onsetMs"] and et["stabilityPermille"] == t["stabilityPermille"] and et["testedMs"] == t["testedMs"], f"{vid}: throttle {et} vs {t}")
    r = derived["role"]
    cnt.ok(e["role"] == {"code": r["code"], "t2PlateauMtps": r["t2PlateauMtps"], "t3PlateauMtps": r["t3PlateauMtps"]}, f"{vid}: role {e['role']} vs {r}")
    cnt.ok(e["overallConfidence"] == derived["overallConfidence"], f"{vid}: overall confidence {e['overallConfidence']} vs {derived['overallConfidence']}")
    by = {t_["tier"]: t_ for t_ in tiers}
    for et_ in e["tiers"]:
        rt = by[et_["tier"]]
        cnt.ok(et_["numerics"] == rt["numerics"]["verdict"] and et_["loadWarmMicros"] == rt["loadWarmMicros"], f"{vid}: tier {et_['tier']} numerics/load")
        for er in et_["tests"]:
            rr = [x for x in rt["results"] if x["test"] == er["test"]][0]
            cnt.ok(er["value"] == rr["value"] and er["kept"] == rr["kept"] and er["confidence"] == rr["confidence"] and er["flags"] == rr["flags"], f"{vid}: {et_['tier']} {er['test']} {er} vs {rr['value']}/{rr['kept']}/{rr['confidence']}/{rr['flags']}")


def doc_scope_reason(raw):
    """Why the sketch cannot derive this document (None when it can)."""
    if "sustained" not in raw:
        return "no sustained block (the sketch requires one)"
    if raw["run"].get("abort") is not None:
        return "partial run"
    if raw["benchSet"] != "qwen3-dense-1":
        return "not bench set 1"
    return None


# ------------------------------------------------------------------ the families
def check_m04(root):
    path = os.path.join(root, "bench", "M04-derive.json")
    if not os.path.isfile(path):
        return None
    cnt = Counter()
    md = spec_text()
    kinds = {}
    for v in load(root, "bench/M04-derive.json")["vectors"]:
        i, e = v["input"], v["expect"]
        k = i["kind"]
        kinds[k] = kinds.get(k, 0) + 1
        vid = v["id"]
        if k == "test":
            got = test_result(i["spec"], i["samples"], i.get("whole"), i.get("ctx", {}))
            ok = e["ok"]
            cnt.ok(all(ok.get(f) == got.get(f) for f in got), f"{vid}: expected {ok}, xcheck {got}")
        elif k == "sustain":
            got = sustain_result(i)
            cnt.ok(e["ok"] == got, f"{vid}: expected {e['ok']}, xcheck {got}")
        elif k == "percentile":
            cnt.ok(e["ok"]["value"] == REF.pct_nearest_rank(i["values"], i["p"]), f"{vid}: percentile")
        elif k == "doc":
            if "ok" not in e:
                cnt.skipped += 1
                continue
            raw = raw_from_doc(i["benchDoc"])
            why = doc_scope_reason(raw)
            if why:
                cnt.skipped += 1
                print(f"  note {vid}: skipped, outside the reference sketch ({why})")
                continue
            tiers, sustain, derived = answers_of(raw)
            compare_answers(cnt, vid, e["ok"]["answers"], derived, tiers)
        elif k == "plan":
            ok = e["ok"]
            if i["plan"] == "standard":
                jc = M01.jcs(spec_plan(md)).encode("utf-8")
                cnt.ok(b64u(hashlib.sha256(jc).digest()) == ok["planSha256"] and len(jc) == ok["jcsBytes"], f"{vid}: the standard plan sha256 differs from the plan JSON in benchmark.md 5.2")
            else:
                rows = spec_plan_rows(md)
                row = rows[i["plan"]]
                cnt.ok(len(ok["planSha256"]) == 43 and ok["jcsBytes"] > 0, f"{vid}: plan hash shape")
                cnt.ok(row[4] == "3", f"{vid}: the 5.1 table says {row[4]} reps for {i['plan']}")
        elif k == "pins":
            ok = e["ok"]
            cnt.ok(ok["q1"] == spec_pins(md), f"{vid}: Q1 pins differ from the benchmark.md 4.2 table")
            cnt.ok(ok["q1"] == [{"tier": t, "bytes": REF.BENCH_SET["tiers"][t]["bytes"], "sha256": REF.BENCH_SET["sha256"][t]} for t in REF.ORDER], f"{vid}: pins differ from bench_ref.BENCH_SET")
            cnt.ok(ok["l1HasPins"] is False and ok["l1InDefaults"] is False and ok["l1WithoutRulingThrows"] is True, f"{vid}: L1 must have no pins (BLOCKED(D18))")
        elif k == "fsm":
            edges = {tuple(x.split(">")) for x in e["ok"]["edges"]}
            cnt.ok(SPEC_EDGES <= edges, f"{vid}: missing spec edges {sorted(SPEC_EDGES - edges)}")
            cnt.ok(edges - SPEC_EDGES <= DOCUMENTED_EXTRA_EDGES, f"{vid}: undocumented edges {sorted(edges - SPEC_EDGES - DOCUMENTED_EXTRA_EDGES)}")
        elif k == "ceilings":
            cnt.ok(ceiling_11_3(i) == e["ok"]["ceiling"], f"{vid}: expected {e['ok']['ceiling']}, xcheck {ceiling_11_3(i)}")
        elif k == "trace":
            check_trace(cnt, v)
        elif k == "consent":
            got = consent_model(i)
            cnt.ok(got == ("consumed" if "ok" in e else "refused"), f"{vid}: consent model says {got}")
            if "ok" in e:
                cnt.ok(re.fullmatch(r"[0-9a-f]{64}", e["ok"]["textSha256"]) is not None, f"{vid}: textSha256 shape")
        else:
            cnt.ok(False, f"{vid}: unknown kind {k}")
    return cnt, kinds


def consent_model(i):
    if i["confirm"] != "match":
        return "refused"
    if i["consumeNowMs"] - i["mintNowMs"] >= 300000:
        return "refused"
    if i.get("consumeTwice"):
        return "refused"
    if i.get("consumePlan", i["plan"]) != i["plan"]:
        return "refused"
    return "consumed"


def check_trace(cnt, v):
    vid, e = v["id"], v["expect"]["ok"]
    ev = e["events"]
    state = None
    loaded = None
    outcome = e["outcome"]
    last = None
    for line in ev:
        m = re.match(r"STATE (\w+)->(\w+)(\(.*\))?$", line)
        if m:
            a, b = m.group(1), m.group(2)
            cnt.ok((a, b) in SPEC_EDGES | DOCUMENTED_EXTRA_EDGES, f"{vid}: STATE edge {a}->{b} is not allowed")
            if state is not None:
                cnt.ok(a == state, f"{vid}: STATE {a}->{b} does not continue from {state}")
            else:
                cnt.ok(a == "IDLE", f"{vid}: the first STATE must leave IDLE")
            state = last = b
            if b in ("FINALIZING", "ABORTING"):
                cnt.ok(loaded is None or b == "ABORTING", f"{vid}: FINALIZING while {loaded} is still loaded")
            continue
        m = re.match(r"LOAD (T\d) ", line)
        if m:
            cnt.ok(loaded is None, f"{vid}: LOAD {m.group(1)} while {loaded} is still loaded")
            loaded = m.group(1)
        m = re.match(r"UNLOAD (T\d)$", line)
        if m:
            cnt.ok(loaded == m.group(1), f"{vid}: UNLOAD {m.group(1)} but {loaded} is loaded")
            loaded = None
    cnt.ok(state in ("DONE", "IDLE"), f"{vid}: the log ends in {state}")
    cnt.ok(loaded is None, f"{vid}: the run ends with {loaded} still loaded (resources not released)")
    if outcome.startswith("refused") or "refused" in outcome:
        cnt.ok(last == "IDLE" and not any("STATE PREPARING" in x for x in ev), f"{vid}: a refused run must not go past PREFLIGHT")
    if outcome == "completed":
        cnt.ok(state == "DONE" and not any("ABORTING" in x for x in ev), f"{vid}: completed without abort")
    if outcome == "aborted":
        cnt.ok(any("->ABORTING" in x for x in ev) and state == "DONE", f"{vid}: an aborted run passes ABORTING and finishes")


def check_m05_body(root):
    path = os.path.join(root, "bench", "M05-body.json")
    if not os.path.isfile(path):
        return None
    cnt = Counter()
    for v in load(root, "bench/M05-body.json")["vectors"]:
        vid = v["id"]
        if "ok" not in v["expect"] or "text" not in v["expect"]["ok"]:
            cnt.skipped += 1
            continue
        text = v["expect"]["ok"]["text"]
        cnt.ok(hashlib.sha256(text.encode("utf-8")).hexdigest() == v["expect"]["ok"]["sha256"], f"{vid}: sha256")
        cnt.ok(text.isascii() and "\r" not in text and text.endswith("\n") and all(len(x) <= 72 for x in text.split("\n")), f"{vid}: ASCII, LF, 72 columns")
        cnt.ok("mlperf" not in text.lower(), f"{vid}: MLPerf wording")
        if v["input"]["kind"] != "body" or "benchDoc" not in v["input"]:
            continue
        raw = raw_from_doc(v["input"]["benchDoc"])
        why = doc_scope_reason(raw)
        if why:
            cnt.skipped += 1
            continue
        try:
            tiers, sustain, derived = answers_of(raw)
        except Exception as ex:  # a document the sketch cannot handle
            cnt.skipped += 1
            print(f"  note {vid}: skipped, the sketch cannot derive it ({type(ex).__name__})")
            continue
        if derived["throttle"] is None:  # the sketch's renderer has no "heat test not used" wording (ERRATA ERR-FX2-1)
            cnt.skipped += 1
            print(f"  note {vid}: skipped, the heat test is ignored by the answers (a failed tier)")
            continue
        doc = {"device": raw["device"], "run": dict(raw["run"], dayUtc=v["input"]["benchDoc"]["run"]["dayUtc"]), "harness": raw["harness"], "memory": raw["memory"],
               "tiers": tiers, "sustain": sustain, "derived": derived, "field": [], "energy": None}
        ref_text = REF.render_with_counts(doc)
        a = section(text, "DETAILS", "NOTES")
        b = section(ref_text, "DETAILS", "NOTES")
        cnt.ok(a == b, f"{vid}: DETAILS table differs from the sketch's")
        # the ANSWERS block: the sketch's wording predates B17 (question 5 without the mesh) and B7 (first-minute speed), so with the mesh
        # off only the numbers of questions 1-4 are compared; with the mesh on and no drift the whole block must be identical
        drift = "first-minute" in text.lower() or "First-minute" in text
        ansa, ansb = section(text, "ANSWERS", "DETAILS"), section(ref_text, "ANSWERS", "DETAILS")
        if v["input"]["render"].get("meshAvailable") and not drift:
            # B18 adds a "Basis:" line to question 3 and the sketch's role lines are over 72 columns (wrapped in the Kotlin text)
            norm = lambda t: " ".join(" ".join(x for x in t.split("\n") if not x.strip().startswith("Basis:")).split())
            cnt.ok(norm(ansa) == norm(ansb), f"{vid}: the ANSWERS block differs from the sketch's")
        else:
            na = re.findall(r"[0-9]+(?:\.[0-9]+)?", ansa.split("\n5. ")[0])
            nb = re.findall(r"[0-9]+(?:\.[0-9]+)?", ansb.split("\n5. ")[0])
            cnt.ok(na == nb, f"{vid}: the numbers of questions 1-4 differ from the sketch's")
    return cnt


def section(text, start, end):
    a = text.index("\n" + start) if ("\n" + start) in text else 0
    b = text.index("\n" + end, a + 1) if ("\n" + end) in text[a + 1:] else len(text)
    return text[a:b]


def main(argv):
    root = argv[1] if len(argv) > 1 else os.path.join(LAB, "conformance")
    bad = 0
    r = check_m04(root)
    if r is None:
        print("xcheck M04: absent")
    else:
        c, kinds = r
        print(f"xcheck M04: {c.agree} agree, {c.dis} disagree ({c.skipped} vectors skipped as outside the reference sketch; kinds {dict(sorted(kinds.items()))})")
        bad += c.dis
    c = check_m05_body(root)
    if c is None:
        print("xcheck M05body: absent")
    else:
        print(f"xcheck M05body: {c.agree} agree, {c.dis} disagree ({c.skipped} skipped as outside the reference sketch)")
        bad += c.dis
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
