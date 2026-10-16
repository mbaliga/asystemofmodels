#!/usr/bin/env python3
"""
Reference sketch of the asom benchmark core's M04 (derive) and M05 (render)
rules, as specified in benchmark.md. ILLUSTRATIVE, not normative: the spec
text in benchmark.md is the authority, and this file exists to prove the spec
is implementable with integer-only arithmetic and that the worked example's
plain text is generated from the same document as its JSON.

Usage: python3 bench_ref.py raw-example-phone.json out-prefix
Writes <prefix>.doc.json (measurement document, pretty), <prefix>.doc.jcs
(canonical bytes), <prefix>.txt (plain-text report).
"""
import hashlib, json, sys, base64

# ---------------------------------------------------------------- bench set 1
# Pinned by content hash (sha256 of the GGUF file, from the Hugging Face API
# LFS oid on 2026-09-29; to be re-verified by download before normative use).
BENCH_SET = {
    "id": "qwen3-dense-1",
    "tiers": {
        "T0": {"modelId": "qwen3-0.6b", "name": "Qwen3-0.6B", "quant": "Q8_0",   "bytes": 639446688,   "params": 751632384},
        "T1": {"modelId": "qwen3-1.7b", "name": "Qwen3-1.7B", "quant": "Q8_0",   "bytes": 1834426016,  "params": 2031739904},
        "T2": {"modelId": "qwen3-4b", "name": "Qwen3-4B",   "quant": "Q4_K_M", "bytes": 2497280256,  "params": 4022468096},
        "T3": {"modelId": "qwen3-8b", "name": "Qwen3-8B",   "quant": "Q4_K_M", "bytes": 5027783488,  "params": 8190735360},
        "T4": {"modelId": "qwen3-14b", "name": "Qwen3-14B",  "quant": "Q4_K_M", "bytes": 9001752960,  "params": 14768307200},
        "T5": {"modelId": "qwen3-32b", "name": "Qwen3-32B",  "quant": "Q4_K_M", "bytes": 19762149024, "params": 32762123264},
    },
    "sha256": {
        "T0": "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031",
        "T1": "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a",
        "T2": "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
        "T3": "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785",
        "T4": "500a8806e85ee9c83f3ae08420295592451379b4f8cf2d0f41c15dffeb6b81f0",
        "T5": "efd971561896866f0e910cce52761ca77b1b138090c7f15fe284676d57d1f689",
    },
}
ORDER = ["T0", "T1", "T2", "T3", "T4", "T5"]

# ------------------------------------------------------- M04 constants (spec §9)
OUTLIER_K_MILLI = 4449          # 3 * 1.4826 scaled MADs, in milli-units (3 * 1483 = 4449)
MAD_ZERO_DEV_PERMILLE = 250     # when MAD == 0, exclude only samples > 25% from the median
MAX_EXCLUDE_DIV = 5             # at most floor(n/5) exclusions
SMOOTH_ONSET_PERMILLE = 900     # onset when smoothed window < 90% of peak, 3 in a row
PEAK_WINDOW_MS = 120000         # peak = max smoothed window starting in the first 2 min
SETTLE_MS = 60000               # plateau measured from onset + 60 s
PLATEAU_WINDOWS = 8
NUMERICS_PASS_PERMILLE = 20     # |nll - ref| <= 2% pass, <= 5% warn, else fail
NUMERICS_WARN_PERMILLE = 50
KV_CTX = 4096                   # "hold" means weights + KV for a 4096-token context
OVERHEAD_BYTES = 314572800      # 300 MiB compute buffers + runtime (assumption, spec §10)
SAFETY_PERMILLE = {"phone": 750, "tablet": 750, "handheld": 800,
                   "laptop": 850, "desktop": 900, "server": 900}
Q4_MILLIBYTES_PER_PARAM = 610   # from the bench set's own Q4_K_M files (0.603..0.621)
# editorial thresholds (asom.text/1), see spec §12.3
COMFORT_DECODE_MTPS, USABLE_DECODE_MTPS = 10000, 4000
COMFORT_TTFT_US, USABLE_TTFT_US = 2000000, 10000000
Q_PROMPT, Q_GEN = 512, 2000   # prompt = the measured pp512@d0/ttft pair


def fdiv(a, b):
    assert a >= 0 and b > 0, (a, b)
    return a // b


def cons_median(xs, higher_is_better):
    s = sorted(xs)
    n = len(s)
    return s[(n - 1) // 2] if higher_is_better else s[n // 2]


def lower_median(xs):
    s = sorted(xs)
    return s[(len(s) - 1) // 2]


def rate_mtps(tokens, micros):
    return fdiv(tokens * 1000000000, micros)


def stat(values, higher_is_better):
    """values: per-sample integers (rates in mtps, or durations in micros).
    Returns the kept rep indices too, so paired spans share a rep's fate."""
    n = len(values)
    med = lower_median(values)
    mad = lower_median([abs(v - med) for v in values])
    if mad > 0:
        out_idx = [i for i, v in enumerate(values) if abs(v - med) * 1000 > OUTLIER_K_MILLI * mad]
    else:
        out_idx = [i for i, v in enumerate(values) if abs(v - med) * 1000 > MAD_ZERO_DEV_PERMILLE * med]
    flags = []
    if len(out_idx) > n // MAX_EXCLUDE_DIV:
        kept_idx = list(range(n))
        flags.append("UNSTABLE")
    else:
        kept_idx = [i for i in range(n) if i not in out_idx]
        if out_idx:
            flags.append("OUTLIER_EXCLUDED")
    kept = [values[i] for i in kept_idx]
    value = cons_median(kept, higher_is_better)
    spread = fdiv((max(kept) - min(kept)) * 1000, value) if value > 0 else 0
    return {"n": n, "kept": len(kept), "keptIdx": kept_idx, "value": value,
            "relSpreadPermille": spread, "flags": flags}


def pct_nearest_rank(xs, p_permille):
    """Nearest-rank percentile over ascending xs, p in permille (spec 13.3)."""
    s = sorted(xs)
    k = -(-p_permille * len(s) // 1000)       # ceil
    return s[max(k, 1) - 1]


def confidence(st, run):
    """Decision table, spec §9.4. Returns high|medium|low|insufficient."""
    kept, n, sp = st["kept"], st["n"], st["relSpreadPermille"]
    cont = max(run["contentionBeforePermille"], run["contentionAfterPermille"])
    if kept < 2:
        return "insufficient"
    if n >= 5 and kept >= 4 and sp <= 50 and cont <= 50 and "UNSTABLE" not in st["flags"]:
        c = "high"
    elif kept >= 3 and sp <= 150 and cont <= 150:
        c = "medium"
    else:
        c = "low"
    caps = []
    if run["startThermal"] != "cool":
        caps.append("medium")
    return min_conf([c] + caps)


CONF_RANK = {"insufficient": 0, "low": 1, "medium": 2, "high": 3}


def min_conf(cs):
    return min(cs, key=lambda c: CONF_RANK[c])


def test_tokens(name):
    # pp512@d0 -> 512 tokens ; tg128@d2048 -> 128 ; ttft128 -> duration only
    if name.startswith("pp") or name.startswith("tg"):
        return int(name[2:].split("@")[0])
    return None


def derive_test(name, raw_samples, run):
    """pp tests at depth 0 carry paired spans {prefill:[...], whole:[...]}: the
    prefill span gives the rate, the whole span (tokenize + prefill + first-token
    argmax) gives time-to-first-token. Outliers are decided on the rate and the
    paired whole-span sample of an excluded rep is excluded with it."""
    toks = test_tokens(name)
    depth = int(name.split("@d")[1]) if "@d" in name else 0
    paired = isinstance(raw_samples, dict)
    micros_list = raw_samples["prefill"] if paired else raw_samples
    vals = [rate_mtps(toks, us) for us in micros_list]
    st = stat(vals, True)
    out = {"test": name, "depth": depth, "unit": "mtps", "samples": list(micros_list),
           "value": st["value"], "kept": st["kept"], "keptIdx": st["keptIdx"],
           "relSpreadPermille": st["relSpreadPermille"],
           "confidence": confidence(st, run), "flags": st["flags"]}
    if paired:
        whole = raw_samples["whole"]
        out["ttftSamples"] = list(whole)
        out["ttftMicros"] = cons_median([whole[i] for i in st["keptIdx"]], False)
    return out


def derive_sustained(s, run):
    w = s["windows"]
    rates = [rate_mtps(tok, us) for (_, tok, us, _) in w]
    n = len(rates)
    sm = [lower_median(rates[max(0, i - 1):min(n, i + 2)]) for i in range(n)]
    peak_idx = max((i for i in range(n) if w[i][0] < PEAK_WINDOW_MS), key=lambda i: (sm[i], -i))
    peak = sm[peak_idx]
    onset_i = None
    for i in range(peak_idx + 1, n - 2):
        if all(sm[j] * 1000 < SMOOTH_ONSET_PERMILLE * peak for j in (i, i + 1, i + 2)):
            onset_i = i
            break
    flags = []
    if onset_i is None:
        tail = sm[-PLATEAU_WINDOWS:]
        onset_ms = None
    else:
        onset_ms = w[onset_i][0]
        settled = [sm[i] for i in range(n) if w[i][0] >= onset_ms + SETTLE_MS]
        if settled:
            tail = settled[-PLATEAU_WINDOWS:]
        else:
            tail = sm[onset_i:]
            flags.append("PLATEAU_NOT_REACHED")
    plateau = lower_median(tail)
    duration = w[-1][0] + s["windowMs"]
    stability = fdiv(plateau * 1000, peak)
    if run["startThermal"] != "cool":
        conf = "low"
    elif s["endReason"] == "PLATEAU" or (s["endReason"] == "TIME_CAP" and duration >= 480000):
        conf = "high"
    elif duration >= 300000:
        conf = "medium"
    else:
        conf = "low"
    thermal_at_onset = w[onset_i][3] if onset_i is not None else None
    return {"test": "sustain", "windowMs": s["windowMs"], "capMs": s["capMs"],
            "windows": [list(x) for x in w],
            "peakMtps": peak, "plateauMtps": plateau, "onsetMs": onset_ms,
            "stabilityPermille": stability, "durationMs": duration,
            "endReason": s["endReason"], "thermalCodeAtOnset": thermal_at_onset,
            "headroomAtOnsetPermille": s.get("headroomAtOnsetPermille") if onset_i is not None else None,
            "confidence": conf, "flags": flags}


def numerics(nll):
    d = abs(nll["milliNatsPerToken"] - nll["refMilliNatsPerToken"])
    dev = fdiv(d * 1000, nll["refMilliNatsPerToken"])
    verdict = "pass" if dev <= NUMERICS_PASS_PERMILLE else ("warn" if dev <= NUMERICS_WARN_PERMILLE else "fail")
    return {"test": "nll1024", "milliNatsPerToken": nll["milliNatsPerToken"],
            "refMilliNatsPerToken": nll["refMilliNatsPerToken"], "deviationPermille": dev, "verdict": verdict}


def res(tier_doc, name):
    for r in tier_doc["results"]:
        if r["test"] == name:
            return r
    return None


def mem_limit(raw):
    m = raw["memory"]
    cands = [m["availAtStartBytes"]]
    for k in ("processLimitBytes", "gpuWorkingSetBytes"):
        if m.get(k) is not None:
            cands.append(m[k])
    return min(cands)


def fits(tier, kv_per_tok, usable):
    b = BENCH_SET["tiers"][tier]["bytes"]
    return b + kv_per_tok * KV_CTX + OVERHEAD_BYTES <= usable


def derive(raw):
    run = raw["run"]
    form = raw["device"]["form"]
    usable = fdiv(mem_limit(raw) * SAFETY_PERMILLE[form], 1000)
    tiers = []
    for t in ORDER:
        if t not in raw["tiers"]:
            continue
        rt = raw["tiers"][t]
        meta = BENCH_SET["tiers"][t]
        tier_run = dict(run, startThermal=rt.get("startThermal", run["startThermal"]))
        doc = {"tier": t, "sha256": BENCH_SET["sha256"][t], "bytes": meta["bytes"], "quant": meta["quant"],
               "startThermal": tier_run["startThermal"],
               "startThermalCode": rt.get("startThermalCode", 0),
               "startedAtMs": rt["startedAtMs"], "nCtx": rt["nCtx"],
               "availBeforeLoadBytes": rt["availBeforeLoadBytes"],
               "peakFootprintBytes": rt["peakFootprintBytes"],
               "kvBytesPerToken": rt["kvBytesPerToken"], "loadColdMicros": rt["loadColdMicros"],
               "loadColdness": rt["loadColdness"],
               "loadWarmMicros": cons_median(rt["loadWarmMicros"], False), "results": []}
        for name in rt["tests"]:
            doc["results"].append(derive_test(name, rt["tests"][name], tier_run))
        doc["numerics"] = numerics(rt["nll"])
        tiers.append(doc)
    sustain = derive_sustained(raw["sustained"], run)
    sustain["tier"] = raw["sustained"]["tier"]

    by = {d["tier"]: d for d in tiers}
    usable_ok = [d["tier"] for d in tiers if d["numerics"]["verdict"] != "fail"]
    # ---- answers
    derived = {"usableMemoryBytes": usable, "safetyPermille": SAFETY_PERMILLE[form]}
    # Q2 largest holdable (estimated) and largest loaded (measured)
    largest_loaded = max(usable_ok, key=ORDER.index)
    ld = by[largest_loaded]
    kv_ratio = fdiv(ld["kvBytesPerToken"] * KV_CTX * 1000, ld["bytes"])
    max_hold = fdiv(max(usable - OVERHEAD_BYTES, 0) * 1000, 1000 + kv_ratio)
    derived["maxHold"] = {"weightBytes": max_hold, "kvRatioPermille": kv_ratio,
                          "approxParamsQ4": fdiv(max_hold * 1000, Q4_MILLIBYTES_PER_PARAM),
                          "largestLoadedTier": largest_loaded, "basis": "estimated"}
    # Q1 7-8B class
    q = {"tier": "T3"}
    if "T3" in by and "T3" in usable_ok:
        dec = res(by["T3"], "tg128@d0")["value"]
        ttft = res(by["T3"], "pp512@d0")["ttftMicros"]
        q["basis"] = "measured"
    elif fits("T3", 147456, usable) and "T2" in by:
        # through-origin scaling from T2 (conservative, spec 12.4)
        t2, b3 = by["T2"], BENCH_SET["tiers"]["T3"]["bytes"]
        dec = fdiv(res(t2, "tg128@d0")["value"] * t2["bytes"], b3)
        ttft = fdiv(res(t2, "pp512@d0")["ttftMicros"] * b3, t2["bytes"])
        q["basis"] = "estimated"
    else:
        dec = ttft = None
        q["basis"] = "cannot-hold" if not fits("T3", 147456, usable) else "not-measured"
    if dec is not None:
        q.update({"decodeMtps": dec, "ttft512Micros": ttft})
        if dec >= COMFORT_DECODE_MTPS and ttft <= COMFORT_TTFT_US:
            q["verdict"] = "comfortable"
        elif dec >= USABLE_DECODE_MTPS and ttft <= USABLE_TTFT_US:
            q["verdict"] = "usable"
        else:
            q["verdict"] = "too-slow"
    else:
        q["verdict"] = q["basis"]
    derived["q7b"] = q
    # Q3 2000-token answer on the headline tier (largest measured, numerics ok)
    h = largest_loaded
    hd = by[h]
    d0 = res(hd, "tg128@d0")["value"]
    d2k = res(hd, "tg128@d2048")
    depth_ratio = fdiv(d2k["value"] * 1000, d0) if d2k else 1000
    ttft = res(hd, "pp512@d0")["ttftMicros"]
    if sustain["tier"] == h:
        eff_peak = fdiv(sustain["peakMtps"] * depth_ratio, 1000)
        eff_plat = fdiv(sustain["plateauMtps"] * depth_ratio, 1000)
        onset_us = sustain["onsetMs"] * 1000 if sustain["onsetMs"] is not None else None
    else:
        eff_peak = eff_plat = fdiv(d0 * depth_ratio, 1000)
        onset_us = None
    if onset_us is None:
        gen = fdiv(Q_GEN * 1000000000, eff_peak)
    else:
        by_onset = fdiv(eff_peak * onset_us, 1000000000)
        if by_onset >= Q_GEN:
            gen = fdiv(Q_GEN * 1000000000, eff_peak)
        else:
            gen = onset_us + fdiv((Q_GEN - by_onset) * 1000000000, eff_plat)
    derived["answer2000"] = {"tier": h, "promptTokens": Q_PROMPT, "genTokens": Q_GEN,
                             "depthRatioPermille": depth_ratio, "micros": ttft + gen,
                             "thermalModel": onset_us is not None, "basis": "estimated"}
    # Q4 throttle
    derived["throttle"] = {"tier": sustain["tier"], "onsetMs": sustain["onsetMs"],
                           "stabilityPermille": sustain["stabilityPermille"],
                           "testedMs": sustain["durationMs"], "basis": "measured"}
    # Q5 role (spec §12.5 decision table)
    stab = sustain["stabilityPermille"]
    def plateau_of(t):
        if t not in by:
            return None
        if sustain["tier"] == t:
            return sustain["plateauMtps"]
        return fdiv(res(by[t], "tg128@d0")["value"] * stab, 1000)
    plat3, plat2 = plateau_of("T3"), plateau_of("T2")
    plat = raw["device"]["platform"]
    if plat in ("ios", "ipados"):
        role = "requester-foreground-helper"
    elif form in ("desktop", "server") and plat3 is not None and plat3 >= COMFORT_DECODE_MTPS:
        role = "strong-provider"
    elif form in ("desktop", "server", "laptop", "handheld") and plat2 is not None and plat2 >= COMFORT_DECODE_MTPS:
        role = "small-model-provider"
    elif form in ("phone", "tablet") and plat2 is not None and plat2 >= 8000:
        role = "occasional-helper"
    else:
        role = "requester"
    derived["role"] = {"code": role, "t2PlateauMtps": plat2, "t3PlateauMtps": plat3}
    # overall confidence = min over headline tier results + sustained
    confs = [r["confidence"] for r in hd["results"]] + [sustain["confidence"]]
    derived["overallConfidence"] = min_conf(confs)
    return tiers, sustain, derived


# ------------------------------------------------------------------- M05 render
def ascii_only(s):
    return "".join(c if 32 <= ord(c) < 127 else "?" for c in s)


def f_rate(mtps):          # floor to 0.1 token/s
    t = mtps // 100
    return f"{t // 10}.{t % 10}"


def f_gb(b):               # capacity: floor to 0.1 GB (10^9)
    t = b // 100000000
    return f"{t // 10}.{t % 10} GB"


def f_gb_file(b):          # file size: round half up to 0.1 GB (10^9)
    t = (b + 50000000) // 100000000
    return f"{t // 10}.{t % 10} GB"


def f_dur(us, estimated):
    s = us // 1000000
    if us < 10000000:
        t = us // 100000
        out = f"{t // 10}.{t % 10} s"
    elif s < 60:
        out = f"{s} s"
    elif s < 600:
        m, r = divmod(s, 60)
        r = (r // 5) * 5 if not estimated else (r // 10) * 10
        out = f"{m} min" if r == 0 else f"{m} min {r} s"
    else:
        out = f"{s // 60} min"
    return ("about " + out) if estimated else out


def f_pct(permille):
    return f"{permille // 10}%"


def f_params(p):
    b = p // 1000000000
    return f"{b}-billion-parameter"


def model_label(t):
    m = BENCH_SET["tiers"][t]
    q = "4-bit" if m["quant"].startswith("Q4") else "8-bit"
    return f"{m['name']} {q}"


VERDICT = {"comfortable": "COMFORTABLE", "usable": "USABLE, NOT COMFORTABLE",
           "too-slow": "TOO SLOW FOR CHAT", "cannot-hold": "CANNOT HOLD IT",
           "not-measured": "NOT MEASURED"}
ROLE = {
    "strong-provider": ("STRONG PROVIDER", "can serve 7-8B models to your other devices."),
    "small-model-provider": ("PROVIDER FOR SMALL MODELS", "can serve 4B-class models to your other devices."),
    "occasional-helper": ("OCCASIONAL HELPER", "can run small models for your\n   other devices while charging; send 7-8B work to a stronger\n   device when one is available."),
    "requester": ("REQUESTER", "best used to send work to your other devices."),
    "requester-foreground-helper": ("REQUESTER (HELPS ONLY WHILE OPEN)", "iPhone and iPad\n   apps cannot serve other devices in the background; an iPad can\n   lend compute while the app is open."),
}
POWER = {"ac": "on charger", "battery": "on battery"}
TEST_WORDS = {"pp512@d0": "reading a 512-token prompt", "pp2048@d0": "reading a 2048-token prompt",
              "tg128@d0": "writing 128 tokens",
              "tg128@d2048": "writing 128 tokens 2k tokens into a chat",
              "tg128@d8192": "writing 128 tokens 8k tokens into a chat"}
CONF_TXT = {"high": "HIGH", "medium": "MEDIUM", "low": "LOW", "insufficient": "INSUFFICIENT"}


def render(doc):
    d, dev, run, der = doc, doc["device"], doc["run"], doc["derived"]
    eng = doc["harness"]["engine"]
    L = []
    a = L.append
    a("ASOM DEVICE REPORT (asom.text/1)")
    a("Generated from this device's benchmark data. (measured) = timed on")
    a("this device. (estimated) = calculated from measured numbers.")
    a("")
    a("DEVICE (as reported by the device itself)")
    a(f"  {ascii_only(dev['maker'])} {ascii_only(dev['model'])} - {dev['platform']} {ascii_only(dev['osVersion'])}")
    a(f"  Chip: {ascii_only(dev['soc'])} | Memory: {f_gb(dev['memTotalBytes'])} total, {f_gb(doc['memory']['availAtStartBytes'])} free at start")
    a(f"  Engine: {eng['name']} {eng['commit'][:8]}, {eng['backend']} backend")
    a(f"  Tested: {run['dayUtc']}, {run['plan']} test, {POWER[run['powerSource']]}, started {run['startThermal']}")
    a(f"  Overall confidence: {CONF_TXT[der['overallConfidence']]}")
    a("")
    a("ANSWERS")
    q = der["q7b"]
    a("1. Can it run a 7-8B model comfortably?")
    if q["basis"] in ("measured", "estimated"):
        a(f"   {VERDICT[q['verdict']]} ({q['basis']} with {model_label('T3')}).")
        est = q["basis"] == "estimated"
        a(f"   It writes about {f_rate(q['decodeMtps'])} tokens/s and starts answering a 512-token")
        a(f"   prompt after {f_dur(q['ttft512Micros'], est)}. We call a model comfortable at 10")
        a("   tokens/s or more and under 2 s to start.")
    else:
        a(f"   {VERDICT[q['verdict']]}.")
    mh = der["maxHold"]
    a("2. What is the largest model it can hold?")
    a(f"   About {f_gb(mh['weightBytes'])} of model file (estimated), roughly a")
    a(f"   {f_params(mh['approxParamsQ4'])} model at 4-bit. Largest actually loaded:")
    a(f"   {model_label(mh['largestLoadedTier'])}, {f_gb_file(BENCH_SET['tiers'][mh['largestLoadedTier']]['bytes'])} file (measured).")
    ans = der["answer2000"]
    a("3. How long will a 2000-token answer take?")
    a(f"   {f_dur(ans['micros'], True).capitalize()} with {model_label(ans['tier'])} for a 512-token")
    a("   prompt, starting cool (estimated from the measured speeds" + (" and" if ans["thermalModel"] else ")."))
    if ans["thermalModel"]:
        a("   heat test).")
    th = der["throttle"]
    a("4. Will it slow down when it gets warm?")
    if th["onsetMs"] is None:
        a(f"   No slowdown seen during {f_dur(th['testedMs'] * 1000, False)} of continuous writing")
        a(f"   ({POWER[run['powerSource']]}, started {run['startThermal']}; measured).")
    else:
        a(f"   Yes. After {f_dur(th['onsetMs'] * 1000, False)} of continuous writing, speed fell to")
        a(f"   {f_pct(th['stabilityPermille'])} of its starting speed and stayed there (measured for")
        a(f"   {f_dur(th['testedMs'] * 1000, False)}, {POWER[run['powerSource']]}, with {model_label(th['tier'])}).")
    rl = ROLE[der["role"]["code"]]
    a("5. What role suits it in a group of your devices?")
    a(f"   {rl[0]}: {rl[1]}")
    a("")
    a("DETAILS (tokens per second, higher is better; start = seconds)")
    a("  model              size     read   write  write@2k  start@512")
    for t in doc["tiers"]:
        name = model_label(t["tier"]).ljust(18)
        def g(nm, kind):
            r = res(t, nm)
            if r is None:
                return "-"
            return f_rate(r["value"]) if kind == "rate" else f_dur(r["ttftMicros"], False).replace(" s", "")
        a(f"  {name} {f_gb_file(t['bytes']).replace(' GB', 'GB').ljust(7)} {g('pp512@d0','rate').rjust(6)}  {g('tg128@d0','rate').rjust(6)}  {g('tg128@d2048','rate').rjust(8)}  {g('pp512@d0','dur').rjust(9)}")
    a("")
    a("NOTES")
    notes = []
    for t in doc["tiers"]:
        for r in t["results"]:
            if "OUTLIER_EXCLUDED" in r["flags"]:
                notes.append(f"{model_label(t['tier'])}, {TEST_WORDS[r['test']]}: {r['n_excluded']} of {len(r['samples'])} timings discarded as outliers.")
            if r["confidence"] in ("low", "insufficient"):
                notes.append(f"{model_label(t['tier'])}, {TEST_WORDS[r['test']]}: {r['confidence']} confidence.")
        nv = t["numerics"]
        notes.append(f"{model_label(t['tier'])} output check: {nv['verdict']} ({nv['deviationPermille'] // 10}.{nv['deviationPermille'] % 10}% from reference).")
    for f in doc["field"]:
        act = res(next(x for x in doc["tiers"] if x["tier"] == f["tier"]), "tg128@d0")["value"]
        if f["decodeMtpsEwma"] < act:
            pct = fdiv((act - f["decodeMtpsEwma"]) * 100, act)
            rel = f"{pct}% below"
        else:
            pct = fdiv((f["decodeMtpsEwma"] - act) * 100, act)
            rel = f"{pct}% above"
        notes.append(f"In everyday use ({f['countClass']} requests, {POWER[f['powerSource']]}), {model_label(f['tier'])} wrote {f_rate(f['decodeMtpsEwma'])} tokens/s, {rel} this test.")
    if doc["energy"] is None:
        notes.append("Battery use: not measured (needs an unplugged battery test).")
    for n_ in notes:
        line = "  -"
        for w_ in n_.split(" "):
            if len(line) + 1 + len(w_) > 72:
                a(line)
                line = "    " + w_
            else:
                line = line + " " + w_
        a(line)
    a("")
    a("WHAT THIS REPORT DOES NOT TELL YOU")
    for s in [
        "- This device tested itself. A signature proves the report was not",
        "  changed after it was made, and which device key made it. It does",
        "  not prove the test ran honestly or that the device is what it says.",
        "- Speeds are for the listed test models. Other models of the same size",
        "  usually behave alike, but not always (mixture-of-experts models",
        "  differ most).",
        "- Heat, battery level, other apps and long conversations change speed.",
        "- Nothing here measures how good the answers are.",
    ]:
        a(s)
    return "\n".join(L) + "\n"


# ------------------------------------------- projection into manifest.md results[]
THERMAL_NAME = {0: "nominal", 1: "light", 2: "moderate", 3: "severe", 4: "critical"}


def project_results(doc):
    """Mapping table of benchmark.md 13.3: the ONLY code path that turns benchmark
    numbers into manifest body.results[] entries (rule B2)."""
    out = []
    run, eng = doc["run"], doc["harness"]["engine"]
    has_battery = doc["device"]["form"] in ("phone", "tablet", "handheld", "laptop")
    charging = run["powerSource"] == "ac" and has_battery
    cont = max(run["contentionBeforePermille"], run["contentionAfterPermille"])
    su = doc["sustain"]
    for t in doc["tiers"]:
        meta = BENCH_SET["tiers"][t["tier"]]
        prefill, decode, worst, flags = [], [], None, set()
        confs = []
        for r in t["results"]:
            toks = test_tokens(r["test"])
            kept_rates = [rate_mtps(toks, r["samples"][i]) for i in r["keptIdx"]]
            pct = {"p10": pct_nearest_rank(kept_rates, 100), "p50": r["value"],
                   "p90": pct_nearest_rank(kept_rates, 900)}
            if r["test"].startswith("pp") and r["depth"] == 0:
                wk = [r["ttftSamples"][i] for i in r["keptIdx"]]
                prefill.append({"promptTokens": toks, "milliTokPerSec": pct,
                                "ttftMicros": {"p10": pct_nearest_rank(wk, 100), "p50": r["ttftMicros"],
                                               "p90": pct_nearest_rank(wk, 900)}})
            elif r["test"].startswith("tg"):
                decode.append({"contextTokens": r["depth"], "genTokens": toks, "milliTokPerSec": pct})
            disc = len(r["samples"]) - r["kept"]
            if worst is None or disc > worst[1]:
                worst = (len(r["samples"]), disc)
            if r["kept"] < 4:
                flags.add("low-runs")
            if "UNSTABLE" in r["flags"]:
                flags.add("unstable")
            confs.append(r["confidence"])
        sustained = None
        if su is not None and su["tier"] == t["tier"]:
            confs.append(su["confidence"])
            sustained = {"durationMs": su["durationMs"], "intervalMs": su["windowMs"],
                         "steadyMilliTokPerSec": su["plateauMtps"], "throttleOnsetMs": su["onsetMs"],
                         "curve": [[w[0], rate_mtps(w[1], w[2]), None, None] for w in su["windows"]]}
            if su["onsetMs"] is not None:
                flags.add("thermal-throttled")
        if charging:
            flags.add("charging")
        if cont > 50:
            flags.add("background-load")
        if t["startThermal"] != "cool":
            flags.add("warm-start")
        nv = t["numerics"]["verdict"]
        if nv != "pass":
            flags.add("numerics-" + nv)
        flags.add("confidence-" + min_conf(confs))
        out.append({
            "modelId": meta["modelId"], "fileSha256": t["sha256"], "fileBytes": t["bytes"],
            "quant": t["quant"], "backend": eng["backend"],
            "settings": {"threads": eng["threads"], "gpuLayers": eng["gpuLayers"],
                         "ctxTokens": t["nCtx"], "batchTokens": 512},
            "measuredAtMs": t["startedAtMs"],
            "runs": {"planned": worst[0], "completed": worst[0], "discarded": worst[1]},
            "conditions": {"charging": charging, "batteryStartPermille": run.get("batteryStartPermille"),
                           "thermalStart": THERMAL_NAME[t["startThermalCode"]], "socStartMilliC": None,
                           "screenOn": run["screenOn"]},
            "prefill": prefill, "decode": decode, "sustained": sustained,
            "memory": {"availableBeforeLoadBytes": t["availBeforeLoadBytes"],
                       "peakProcessBytes": t["peakFootprintBytes"],
                       "kvCacheBytes": t["kvBytesPerToken"] * t["nCtx"]},
            "power": {"method": "unavailable", "avgMilliW": None},
            "flags": sorted(flags),
        })
    return out


# ------------------------------------------------------------------- doc build
def count_class(n):
    return "1-9" if n < 10 else ("10-99" if n < 100 else "100+")


def jcs(o):
    # JCS integer profile (keys are ASCII here, so code-unit order == byte order)
    if isinstance(o, dict):
        return "{" + ",".join(json.dumps(k) + ":" + jcs(o[k]) for k in sorted(o)) + "}"
    if isinstance(o, list):
        return "[" + ",".join(jcs(x) for x in o) + "]"
    if isinstance(o, bool) or o is None:
        return json.dumps(o)
    if isinstance(o, int):
        return str(o)
    if isinstance(o, str):
        return json.dumps(o, ensure_ascii=False, separators=(",", ":"))
    raise TypeError(type(o))


def main(src, prefix):
    raw = json.load(open(src))
    tiers, sustain, derived = derive(raw)
    for t in tiers:
        for r in t["results"]:
            r["n_excluded"] = len(r["samples"]) - r["kept"]
    import datetime
    day = datetime.datetime.fromtimestamp(raw["run"]["startedAtMs"] // 1000, datetime.timezone.utc).strftime("%Y-%m-%d")
    run = {k: raw["run"][k] for k in ("plan", "optInTiers", "startedAtMs", "endedAtMs", "powerSource", "batteryStartPermille",
                                      "startThermal", "screenOn", "contentionBeforePermille",
                                      "contentionAfterPermille", "abort")}
    run["dayUtc"] = day
    doc = {
        "schema": "asom.bench/1",
        "benchProtocol": raw["benchProtocol"],
        "benchSet": raw["benchSet"],
        "harness": raw["harness"],
        "device": raw["device"],
        "memory": raw["memory"],
        "run": run,
        "tiers": tiers,
        "sustain": sustain,
        "energy": None,
        "field": [{"tier": f["tier"], "depthBand": f["depthBand"], "thermalBand": f["thermalBand"],
                   "powerSource": f["powerSource"], "countClass": count_class(f["count"]),
                   "decodeMtpsEwma": f["decodeMtpsEwma"], "prefillMtpsEwma": f["prefillMtpsEwma"],
                   "lastDayUtc": f["lastDayUtc"]} for f in raw["field"]],
        "derived": derived,
    }
    # rule B3: "not measured" is an explicit null in the signed bytes, never an omitted key
    for t in tiers:
        for r in t["results"]:
            r.pop("n_excluded", None)
    text = render_with_counts(doc)
    th = hashlib.sha256(text.encode("ascii")).digest()
    doc["render"] = {"renderer": "asom.text/1", "textSha256": base64.urlsafe_b64encode(th).rstrip(b"=").decode()}
    canon = jcs(doc)
    open(prefix + ".doc.json", "w").write(json.dumps(doc, indent=2) + "\n")
    open(prefix + ".doc.jcs", "w").write(canon)
    open(prefix + ".txt", "w").write(text)
    open(prefix + ".manifest-results.json", "w").write(json.dumps(project_results(doc), indent=2) + "\n")
    print("text sha256 (b64u):", doc["render"]["textSha256"])
    print("doc canonical bytes:", len(canon.encode()))
    print("doc sha256 (hex):", hashlib.sha256(canon.encode()).hexdigest())


def render_with_counts(doc):
    for t in doc["tiers"]:
        for r in t["results"]:
            r["n_excluded"] = len(r["samples"]) - r["kept"]
    out = render(doc)
    for t in doc["tiers"]:
        for r in t["results"]:
            r.pop("n_excluded", None)
    return out


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
