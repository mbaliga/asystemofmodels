"""An independent reference of LAB_SPEC 6.3, 6.4, 6.6 and 6.7, written from the spec text (not from the Kotlin code) for `gen_vectors.py`.

Python integers are unbounded, so `sat` reproduces the 2^53 - 1 saturation explicitly. Nothing here reads a clock or a random source.
"""
MAXV = (1 << 53) - 1

CFG = dict(
    peerBiasMs=1000, msPerBatteryPermille=2000, heatPermilleUserActive=500, heatPermille=250, stalePermille=250, expiredPermille=500,
    minDecodeMilliTokPerSec=4000, maxTtftMs=20000, handshakeExtraMs=40,
    loadBytesPerMs=dict(PHONE=500000, TABLET=500000, HANDHELD=1000000, SBC=1000000, LAPTOP=2000000, DESKTOP=2000000),
    pathKbps=dict(LAN=100000, OVERLAY=20000),
    powerMilliW=dict(PHONE=5000, TABLET=7000, HANDHELD=15000, LAPTOP=30000, DESKTOP=150000, SBC=8000),
    queueBucketMs=[0, 10000, 30000],
    quantPenaltyMs={"F16": 0, "BF16": 0, "Q8_0": 0, "Q6_K": 200, "Q5_K_M": 400, "Q4_K_M": 800, "Q4_0": 1000, "Q3_K_M": 2500, "Q2_K": 5000},
    unknownQuantPenaltyMs=1000, msPerRankStep=5000, unrankedRankOffset=10, autoRankFloor=None, cloudDecodePrior=50000,
    maxAttempts=6, maxPeerAttempts=3, bpt=(4000, 8000), outDefault=256, outMax=32768, slack=268435456,
)


def sat(x):
    return max(0, min(x, MAXV))


def cdiv(a, b):
    assert b > 0
    return sat(-(-sat(a) // b))


def fdiv(a, b):
    assert b > 0
    return sat(sat(a) // b)


def mul(a, b):
    return sat(sat(a) * sat(b))


def add(*xs):
    t = 0
    for x in xs:
        t = sat(t + sat(x))
    return t


def decode_at(curve, ctx):
    if ctx <= curve[0][0]:
        return curve[0][1]
    if ctx >= curve[-1][0]:
        return curve[-1][1]
    i = 0
    while curve[i + 1][0] <= ctx:
        i += 1
    (c0, v0), (c1, v1) = curve[i], curve[i + 1]
    return v0 + ((v1 - v0) * (ctx - c0)) // (c1 - c0)


def thermal_decode(m, dec, steady, onset, busy, queue, prefill, hot):
    if m <= 0:
        return 0
    already = add(busy, queue, prefill)
    if hot or (onset is not None and already >= onset):
        return cdiv(mul(m, 1000000), min(dec, steady))
    if onset is None:
        return cdiv(mul(m, 1000000), dec)
    cool = onset - already
    tok_cool = fdiv(mul(cool, dec), 1000000)
    if m <= tok_cool:
        return cdiv(mul(m, 1000000), dec)
    return add(cool, cdiv(mul(m - tok_cool, 1000000), steady))


# ---------------------------------------------------------------------------------------------------------------------------------------------
# tracker (6.6)
# ---------------------------------------------------------------------------------------------------------------------------------------------
WIN, MIN_KEEP, MIN_STATE, CORR, DISC_T, RATIO_CAP, SHORT = 20, 3, 5, 800, 600, 5000, 128


def out_tok_est(out_bytes, bpt):
    return cdiv(mul(out_bytes, 1000), bpt)


def predicted(claim, p_tokens, net_ms, est):
    e5 = add(cdiv(mul(p_tokens, 1000000), claim["prefillMilliTokPerSec"]), claim["ttft0Ms"])
    dec = decode_at(claim["decodeAt"], p_tokens)
    e7 = thermal_decode(est - 1, dec, claim["steadyMilliTokPerSec"], claim["throttleOnsetMs"], 0, 0, e5, False)
    return add(net_ms, e5, e7)


def ratio_of(pred, elapsed):
    return min(RATIO_CAP, fdiv(mul(pred, 1000), max(1, elapsed)))


def state_of(ts):
    if ts is None:
        return "UNVERIFIED"
    if ts.get("inheritedDiscrepant"):
        return "DISCREPANT"
    x = sorted(ts["ratios"])
    n = len(x)
    if n < MIN_STATE:
        base = "UNVERIFIED"
    else:
        best = x[(3 * n) // 4]
        base = "CORROBORATED" if best >= CORR else ("WEAK" if best >= DISC_T else "DISCREPANT")
    if budget_tripped(ts) and base != "DISCREPANT":
        return "WEAK"
    return base


def budget_tripped(ts):
    r = ts.get("recent", [])
    return len(r) >= 4 and 2 * sum(1 for o in r if not o["kept"]) > len(r)


def min_ratio(ts):
    q = [o["ratio"] for o in ts.get("recent", []) if o["outBytes"] >= 8]
    return min(q) if q else 0


def tracked_rate(claim_rate, ts, cap_ref, disc):
    x = sorted(ts["ratios"]) if ts else []
    n = len(x)
    if n >= MIN_KEEP:
        rate = fdiv(mul(claim_rate, min(1000, x[(n - 1) // 2])), 1000)
    else:
        prior = fdiv(mul(min(claim_rate, cap_ref if cap_ref is not None else claim_rate), disc), 1000)
        kept = 0
        for xi in x:
            kept = add(kept, fdiv(mul(claim_rate, min(1000, xi)), 1000))
        rate = fdiv(add(mul(2, prior), kept), 2 + n)
    if ts and budget_tripped(ts):
        rate = min(rate, fdiv(mul(claim_rate, min_ratio(ts)), 1000))
    return min(rate, claim_rate)


# ---------------------------------------------------------------------------------------------------------------------------------------------
# a sovereign plan (6.2, 6.3, 6.4) over the JSON world of gen_vectors.py
# ---------------------------------------------------------------------------------------------------------------------------------------------
BAND = ["ge80", "50-79", "20-49", "lt20"]


def lower(band):
    return band if band is None else BAND[min(BAND.index(band) + 1, 3)]


def freshness(w, n):
    d = n["state"]
    if d is None or n["stateRxMonoMs"] is None or n["stateRegressed"] or n["goawaySeen"]:
        return "EXPIRED"
    age = (w["nowMonoMs"] - n["stateRxMonoMs"]) + min(d["sampledAgeMs"], 60000)
    if not n["sessionOpen"] and age > 30000:
        return "EXPIRED"
    return "FRESH" if age <= 5000 else "WARM" if age <= 30000 else "STALE" if age <= 300000 else "EXPIRED"


def policy_of(q):
    virtual = {"auto", "cheapest", "fastest", "best-reasoning", "local-only"}
    if q["model"] in virtual:
        return q["model"]
    return q["policyHeader"] or "auto"


def dests(w):
    q = w["query"]
    pol = policy_of(q)
    p = {"T", "O", "C"}
    if not w["meshGlobalOn"] or not q["app"]["meshAllowed"]:
        p -= {"O"}
    if q["app"]["cloudBanned"]:
        p -= {"C"}
    if q["app"]["deviceOnly"] or pol == "local-only":
        p &= {"T"}
    if q["fallback"]:
        p &= {"C"}
    return p


def out_tokens(w):
    q = w["query"]
    v = q["maxTokensCap"] if q["maxTokensCap"] is not None else w["appEwmaOut"].get(q["app"]["pkg"], CFG["outDefault"])
    return max(1, min(v, CFG["outMax"]))


def tracker_state(w, node_id, sha, backend):
    for t in w["tracker"]:
        if t["nodeId"] == node_id and t["fileSha256"] == sha and t["backend"] == backend:
            return t["state"]
    return None


def penalty_disc(w, node_id):
    pen = w["penalties"].get(node_id)
    if pen and pen["untilWallMs"] > w["wallNowMs"]:
        return 400
    bad = sum(1 for t in w["tracker"] if t["nodeId"] == node_id and state_of(t["state"]) == "DISCREPANT")
    return 400 if bad >= 2 else 700


def ceiling(cfg_over, model, backend, cls):
    cc = cfg_over.get("classCeilings", {}).get(f"{model}|{backend}|{cls}")
    sg = cfg_over.get("signedReferenceP90", {}).get(f"{model}|{backend}|{cls}")
    if sg:
        sg = {k: (v * 12) // 10 for k, v in sg.items()}
    if sg and cc:
        return {k: min(sg[k], cc[k]) for k in cc}
    return sg or cc


def prior_row(n, f, backend):
    for p in n["priors"]:
        if p["nodeId"] == n["nodeId"] and p["fileSha256"] == f["fileSha256"] and p["backend"] == backend:
            return p["prior"]
    return None


def backend_of(n, f):
    if n["tier"] == "SELF":
        return n["self"]["backend"]
    if n["state"] is not None:
        return n["state"]["backend"]
    bs = {p["backend"] for p in n["priors"] if p["nodeId"] == n["nodeId"] and p["fileSha256"] == f["fileSha256"]}
    return next(iter(bs)) if len(bs) == 1 else None


def resolve(w, n, f, p_tokens):
    backend = backend_of(n, f)
    if backend is None:
        return None, "no-prior"
    claim = prior_row(n, f, backend)
    if claim is None:
        return None, "no-prior"
    if claim["prefillMilliTokPerSec"] <= 0 or claim["steadyMilliTokPerSec"] <= 0 or any(v <= 0 for _, v in claim["decodeAt"]):
        return None, "claim-rate<=0"
    cd = decode_at(claim["decodeAt"], p_tokens)
    if n["tier"] == "SELF":
        return dict(claim=claim, prefill=claim["prefillMilliTokPerSec"], decode=cd, steady=claim["steadyMilliTokPerSec"], state="LOCAL_MEASURED", key=None), None
    ts = tracker_state(w, n["nodeId"], f["fileSha256"], backend)
    if ts and ts.get("memoryDiscrepant"):
        return None, "memory-discrepant"
    cfgo = w["config"]
    cl = ceiling(cfgo, f["modelId"], backend, n["deviceClass"])
    disc = penalty_disc(w, n["nodeId"])
    r = dict(
        claim=claim,
        prefill=tracked_rate(claim["prefillMilliTokPerSec"], ts, cl["prefillMilliTokPerSec"] if cl else None, disc),
        decode=tracked_rate(cd, ts, cl["decodeMilliTokPerSec"] if cl else None, disc),
        steady=tracked_rate(claim["steadyMilliTokPerSec"], ts, cl["steadyMilliTokPerSec"] if cl else None, disc),
        state=state_of(ts), key=(n["nodeId"], f["fileSha256"], backend),
    )
    if min(r["prefill"], r["decode"], r["steady"]) <= 0:
        return None, "tracked-rate<=0"
    return r, None


def fast_fields(w, n):
    """thermalBand, queueBucket, batteryBand after the staleness substitution of LAB_SPEC 6.5 (literal: the thermal rule is a no-op)."""
    d = n["state"]
    fr = freshness(w, n)
    if d is None:
        return dict(fr=fr, fsm="OFF", gov="RUN", tb=1, qb=2, src="unknown", chg=False, band=None, backend=None, held=None)
    tb, qb, band = d["thermalBand"], d["queueBucket"], d["batteryBand"]
    if fr == "STALE":
        if qb >= 1:
            qb = min(2, qb + 1)
        if d["powerSource"] == "battery":
            band = lower(band)
    return dict(fr=fr, fsm=d["fsm"], gov=d["governor"], tb=tb, qb=qb, src=d["powerSource"], chg=d["charging"], band=band, backend=d["backend"], held=set(d["held"]))


def serves(f, q, virtual, cfgo):
    if not virtual:
        return f["modelId"] == q["model"]
    kind_ok = (f["kind"] == "EMBED") if q["op"] == "embeddings" else (f["kind"] == "CHAT")
    floor = cfgo.get("autoRankFloor")
    return kind_ok and (floor is None or (f["catalogueRank"] is not None and f["catalogueRank"] <= floor))


def first_failing(w, n, f, fast, prior_res, P, N):
    q = w["query"]
    peer = n["tier"] == "PEER"
    now = w["nowMonoMs"]
    expired = fast is not None and fast["fr"] == "EXPIRED"
    if peer:
        row = n["peer"]
        if "O" not in P or not row["routeEnabled"]:
            return "F1_ELIGIBILITY", None
        if not row["paired"]:
            return "F2_NOT_PAIRED", None
        if not row["inferGrantedToMe"]:
            return "F3_NO_SCOPE", None
        if fast["held"] is None:
            return "F4_MODEL", "no state: held models unknown"
        if f["fileSha256"] not in fast["held"]:
            return "F4_MODEL", "not held"
    backend = backend_of(n, f)
    crow = prior_row(n, f, backend) if backend else None
    if crow and "numerics-fail" in crow["flags"]:
        return "F4_MODEL", "numerics-fail"
    if f["contextTokens"] is None:
        return "F5_CONTEXT", "model context unknown"
    limit = f["contextTokens"] if n["maxContextTokens"] is None else min(f["contextTokens"], n["maxContextTokens"])
    if q["promptTokens"] + N > limit:
        return "F5_CONTEXT", f"P+N > {limit}"
    if n["tier"] == "SELF":
        s = n["self"]
        if f["fileSha256"] not in s["loaded"] and crow is not None:
            need = add(f["fileBytes"], mul(crow["kvBytesPerToken"], q["promptTokens"] + N), CFG["slack"])
            if s["availBytes"] is None:
                return "F6_MEMORY", "free memory unknown"
            if s["availBytes"] < need:
                return "F6_MEMORY", f"free {s['availBytes']} < {need}"
    if peer:
        lim = n["peer"]["limits"]
        if q["promptBytes"] > lim["maxBodyBytes"]:
            return "F7_BODY", "body"
        if N > lim["maxTokens"]:
            return "F7_BODY", "tokens"
    if prior_res[0] is None:
        return "F8_CLAIM", prior_res[1]
    if peer:
        if n["link"] is None:
            return "F8_CLAIM", "link-stats-missing"
        on_batt = fast["src"] == "battery" and not fast["chg"]
        if on_batt and (n["batteryDesignMilliWh"] is None or n["batteryDesignMilliWh"] <= 0):
            return "F8_CLAIM", "battery-capacity-undefined"
        if not expired and fast["fsm"] != "SERVING":
            return "F9_AVAILABILITY", fast["fsm"]
        if not expired and (fast["tb"] == 2 or fast["gov"] == "HOLD"):
            return "F10_THERMAL", None
        if not expired and fast["src"] == "battery" and not fast["chg"]:
            band = fast["band"]
            if n["peer"]["requireCharging"] or band is None or band in ("20-49", "lt20"):
                return "F11_POWER", band if band is not None else "band unknown"
        if n["link"]["metered"] and not q["app"]["allowMeshOnMetered"]:
            return "F13_METERED", None
        b = n["breaker"]
        if b["coolingUntilMonoMs"] is not None and now < b["coolingUntilMonoMs"]:
            return "F14_BREAKER", None
        if b["declineBackoffUntilMonoMs"] is not None and now < b["declineBackoffUntilMonoMs"]:
            return "F15_DECLINE_BACKOFF", None
    else:
        s = n["self"]
        if s["thermalCode"] >= 3 or s["governor"] == "HOLD":
            return "F10_THERMAL", None
    if q["op"] == "embeddings" and (q["embeddingIdentity"] is None or q["embeddingIdentity"] != f["fileSha256"]):
        return "F16_EMBED_IDENTITY", None
    return None


def estimate(w, n, f, fast, res, N):
    q = w["query"]
    cfgo = w["config"]
    selfn = n["tier"] == "SELF"
    P, B = q["promptTokens"], q["promptBytes"]
    lk = n["link"]
    rtt = 0 if selfn else lk["rttMs"]
    kbps = 0 if selfn else (lk["kbps"] if lk["kbps"] > 0 else CFG["pathKbps"][lk["path"]])
    net = 0 if selfn else add(rtt, cdiv(mul(B, 8), kbps), 0 if lk["sessionWarm"] else add(mul(3, rtt), CFG["handshakeExtraMs"]))
    load_rate = cfgo.get("loadBytesPerMs", {}).get(n["deviceClass"], CFG["loadBytesPerMs"][n["deviceClass"]])
    if selfn:
        load = 0 if f["fileSha256"] in n["self"]["loaded"] else cdiv(f["fileBytes"], load_rate)
    else:
        last = n["lastSameFileMonoMs"].get(f["fileSha256"])
        load = 0 if (last is not None and w["nowMonoMs"] - last <= n["peer"]["limits"]["idleUnloadMs"]) else cdiv(f["fileBytes"], load_rate)
    queue = n["self"]["localQueueMs"] if selfn else max(n["ownReservationsMs"], CFG["queueBucketMs"][fast["qb"]])
    claim = res["claim"]
    prefill = add(cdiv(mul(P, 1000000), res["prefill"]), claim["ttft0Ms"])
    busy = n["self"]["busyForMs"] if selfn else 0
    hot = n["self"]["thermalCode"] >= 2 if selfn else fast["tb"] >= 1
    decode = thermal_decode(N - 1, res["decode"], res["steady"], claim["throttleOnsetMs"], busy, queue, prefill, hot)
    ttft = add(net, load, queue, prefill, 0 if selfn else cdiv(rtt, 2))
    total = add(ttft, decode)
    power = claim["powerMilliW"] if claim["powerMilliW"] is not None else CFG["powerMilliW"][n["deviceClass"]]
    energy = fdiv(mul(power, add(prefill, decode)), 1000)
    on_batt = (n["self"]["onBattery"] and not n["self"]["charging"]) if selfn else (fast["src"] == "battery" and not fast["chg"])
    design = n["self"]["batteryDesignMilliWh"] if selfn else n["batteryDesignMilliWh"]
    used = cdiv(mul(energy, 1000), mul(design, 3600)) if (on_batt and design and design > 0) else 0
    return dict(outTokens=N, netMs=net, loadMs=load, queueMs=queue, preEff=res["prefill"], prefillMs=prefill, decEff=res["decode"], decodeMs=decode,
                ttftMs=ttft, totalMs=total, energyMilliJ=energy, batteryUsedPermille=used)


def score(w, n, f, fast, est, virtual, best_rank):
    q = w["query"]
    selfn = n["tier"] == "SELF"
    s1 = add(est["totalMs"], est["ttftMs"] if q["stream"] else 0)
    if est["batteryUsedPermille"] == 0:
        s2 = 0
    else:
        if selfn:
            b = n["self"]["batteryPermille"]
            mult = 4000 if b is None else 1000 if b >= 500 else 2000 if b >= 200 else 4000
        else:
            band = fast["band"]
            mult = 1000 if band in ("ge80", "50-79") else 2000 if band == "20-49" else 4000
        s2 = fdiv(mul(mul(est["batteryUsedPermille"], CFG["msPerBatteryPermille"]), mult), 1000)
    small = n["deviceClass"] in ("PHONE", "TABLET", "HANDHELD")
    hot = n["self"]["thermalCode"] >= 1 if selfn else fast["tb"] >= 1
    s3 = 0
    if small and hot:
        hp = CFG["heatPermilleUserActive"] if (selfn and n["self"]["userActive"]) else CFG["heatPermille"]
        s3 = fdiv(mul(add(est["prefillMs"], est["decodeMs"]), hp), 1000)
    s4 = 0 if selfn else CFG["peerBiasMs"]
    qp = CFG["quantPenaltyMs"].get(f["quant"], CFG["unknownQuantPenaltyMs"]) if f["quant"] is not None else CFG["unknownQuantPenaltyMs"]
    s5 = qp
    if virtual and best_rank is not None:
        rank = f["catalogueRank"] if f["catalogueRank"] is not None else best_rank + CFG["unrankedRankOffset"]
        s5 = add(s5, mul(rank - best_rank, CFG["msPerRankStep"]))
    fr = None if selfn else fast["fr"]
    s6 = fdiv(mul(est["totalMs"], CFG["stalePermille"]), 1000) if fr == "STALE" else fdiv(mul(est["totalMs"], CFG["expiredPermille"]), 1000) if fr == "EXPIRED" else 0
    return dict(S1=s1, S2=s2, S3=s3, S4=s4, S5=s5, S6=s6, S=add(s1, s2, s3, s4, s5, s6))


def usable(w, n, est):
    return n["tier"] == "SELF" or (est["decEff"] >= CFG["minDecodeMilliTokPerSec"] and est["ttftMs"] <= CFG["maxTtftMs"] and est["totalMs"] <= w["query"]["deadlineMs"])


def sovereign(w):
    """(excluded, survivors) exactly as LAB_SPEC 6.2-6.4 define them, for the JSON world `w` (the cloud tier is not modelled here)."""
    q = w["query"]
    P = dests(w)
    pol = policy_of(q)
    virtual = q["model"] in {"auto", "cheapest", "fastest", "best-reasoning", "local-only"}
    N = out_tokens(w)
    nodes = []
    if "T" in P and w["self"]["self"]["hasEngine"]:
        nodes.append(w["self"])
    if "O" in P:
        nodes += w["peers"]
    nodes.sort(key=lambda n: n["nodeId"])
    excluded, surv = [], []
    for n in nodes:
        fast = fast_fields(w, n) if n["tier"] == "PEER" else None
        seen = set()
        for f in sorted(n["files"], key=lambda f: f["fileSha256"]):
            if f["fileSha256"] in seen or not serves(f, q, virtual, w["config"]):
                continue
            seen.add(f["fileSha256"])
            res = resolve(w, n, f, q["promptTokens"])
            fail = first_failing(w, n, f, fast, res, P, N)
            if fail:
                excluded.append([n["nodeId"], f["fileSha256"], fail[0]])
            else:
                surv.append((n, f, fast, res[0]))
    ranks = [f["catalogueRank"] for _, f, _, _ in surv if f["catalogueRank"] is not None]
    best = min(ranks) if ranks else None
    out = []
    for n, f, fast, res in surv:
        est = estimate(w, n, f, fast, res, N)
        sc = score(w, n, f, fast, est, virtual, best)
        fr = "FRESH" if fast is None else fast["fr"]
        out.append(dict(id=("SELF:" if n["tier"] == "SELF" else "PEER:") + n["nodeId"] + "/" + f["fileSha256"], node=n, file=f, est=est, score=sc,
                        usable=usable(w, n, est), probe=(fr == "EXPIRED") or n["breaker"].get("halfOpen", False), fresh=fr, claim=res["state"], key=res["key"]))
    return excluded, out


# ---------------------------------------------------------------------------------------------------------------------------------------------
# the merge (6.7)
# ---------------------------------------------------------------------------------------------------------------------------------------------
def merge(policy, sov, cloud, never_cloud, caps):
    """sov: dicts with id, tier ('SELF'|'PEER'), nodeId, modelId, sha, terms (S1..S6), usable, probe, claim, rank, key.  cloud: dicts with id, rank, s1."""
    def total(x):
        return sum(x["terms"])

    def tie(x):
        return (total(x), 0 if x["tier"] == "SELF" else 1, x["nodeId"], x["modelId"], x["sha"])

    def by(primary):
        return lambda x: (primary(x), x["probe"], tie(x))

    UN = 2 ** 31 - 1
    if policy == "local-only":
        items = [("S", x) for x in sorted(sov, key=by(lambda x: 0))]
    elif policy == "auto":
        u = [("S", x) for x in sorted([x for x in sov if x["usable"]], key=by(lambda x: 0))]
        n = [("S", x) for x in sorted([x for x in sov if not x["usable"]], key=by(lambda x: 0))]
        c = [("C", x) for x in cloud]
        items = u + n + c if never_cloud else u + c + n
    elif policy == "cheapest":
        items = [("S", x) for x in sorted(sov, key=by(lambda x: x["terms"][1] + x["terms"][2]))] + [("C", x) for x in cloud]
    else:
        if policy == "fastest":
            sv = sorted(sov, key=by(lambda x: x["terms"][0]))

            def beats(s, c):
                return c["s1"] is None or s["terms"][0] < c["s1"]
        else:
            sv = sorted(sov, key=by(lambda x: x["rank"] if x["rank"] is not None else UN))

            def beats(s, c):
                return (s["rank"] if s["rank"] is not None else UN) <= (c["rank"] if c["rank"] is not None else UN)
        items, ci = [], 0
        cl = list(cloud)
        for s in sv:
            while ci < len(cl) and not beats(s, cl[ci]):
                items.append(("C", cl[ci]))
                ci += 1
            items.append(("S", s))
        items += [("C", c) for c in cl[ci:]]
    delta, swapped = [], False
    if items and items[0][0] == "S" and items[0][1]["claim"] == "UNVERIFIED" and items[0][1].get("key"):
        top = items[0][1]
        di = next((i for i, (k, x) in enumerate(items) if i > 0 and k == "S" and x["usable"]), None)
        if di is not None:
            cnt = caps.get(top["key"], (0, 0))
            wouldwin = cnt[0] + 1
            if cnt[1] >= -(-wouldwin // 4):
                items[0], items[di] = items[di], items[0]
                delta = [dict(key=top["key"], wouldWinInc=1, wonInc=0, swapped=True)]
                swapped = True
            else:
                delta = [dict(key=top["key"], wouldWinInc=1, wonInc=1, swapped=False)]
    return [x["id"] for _, x in items], delta, swapped
