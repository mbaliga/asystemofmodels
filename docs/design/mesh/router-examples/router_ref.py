#!/usr/bin/env python3
"""
Reference arithmetic for router.md (asom mesh design session, 2026-09-29).

Two parts:
  A. mode_math(): the numbers behind router.md section 1 (what "distributing work"
     really means). Every input is either VERIFIED (source named in router.md section 19)
     or an ASSUMPTION (labelled here and in router.md). Floats are fine here: this part is
     explanatory arithmetic, not a conformance vector.
  B. estimate()/score(): the normative integer estimator and scorer of router.md
     sections 4-5 (C1: integers only, floor division unless ceil_div). The worked example
     in router.md section 5.7 is printed by this script; the R02 illustrative vectors are
     emitted as JSON.

All device numbers in part B are ASSUMPTIONS or taken from the INVENTED example manifest
(bench-examples/, manifest-vectors/); they are not measurements.
Run: python3 router_ref.py  (no dependencies)
"""
import json

# --------------------------------------------------------------------------------------
# Part A: mode arithmetic
# --------------------------------------------------------------------------------------

def mode_math():
    out = []
    p = out.append
    # Qwen3-8B config (VERIFIED: huggingface.co/Qwen/Qwen3-8B config.json)
    layers, kv_heads, head_dim = 36, 8, 128
    kv_f16 = 2 * layers * kv_heads * head_dim * 2            # K and V, 2 bytes/elem
    kv_q8 = 2 * layers * kv_heads * head_dim * 34 // 32      # q8_0: 34 bytes per 32 elems
    p(f"[KV] Qwen3-8B KV bytes/token f16={kv_f16} ({kv_f16/1024:.0f} KiB), q8_0={kv_q8}")
    for prompt in (512, 4096):
        for name, per in (("f16", kv_f16), ("q8_0", kv_q8)):
            b = prompt * per
            # Wi-Fi throughput 320-610 Mbps (VERIFIED: prima.cpp testbed, arXiv 2504.08791)
            t_lo = b * 8 / 610e6
            t_hi = b * 8 / 320e6
            p(f"[KV] prompt={prompt} {name}: {b/2**20:.0f} MiB -> {t_lo:.1f}-{t_hi:.1f} s over 610-320 Mbps")
    # Prefill speed reference (VERIFIED: llama.cpp discussion 4167, LLaMA 7B Q4_0, M4 Pro pp512=439.78)
    for prompt in (512, 4096):
        p(f"[KV] M4 Pro-class prefill of {prompt} tok at ~440 tok/s ~ {prompt/440:.1f} s (7B Q4_0 figure used as proxy for 8B: ASSUMPTION)")

    # Pipeline split, decode: one hidden-state vector per token per boundary
    hidden = 4096
    act = hidden * 2
    p(f"[PP] decode activation per boundary per token = {act} bytes (f16) -> bandwidth irrelevant; latency dominates")
    # Wi-Fi RTT 7.5-30 ms average, 4.9-54 ms range (VERIFIED: exo issue 2295, 2026-09-02)
    for base_tps in (50.0, 20.0, 5.0):
        base_ms = 1000 / base_tps
        for rtt in (7.5, 30.0):
            # 2-stage ring: at least one full round trip per generated token (sampling at the head)
            tps = 1000 / (base_ms + rtt)
            p(f"[PP] single-node {base_tps:.0f} tok/s ({base_ms:.0f} ms/tok) + {rtt} ms RTT/token -> {tps:.1f} tok/s ({(tps/base_tps-1)*100:+.0f}%)")

    # Cross-device speculative decoding bound
    # E[tokens per round] with acceptance alpha and window k (standard SD analysis)
    def e_tokens(alpha, k):
        return (1 - alpha ** (k + 1)) / (1 - alpha)
    p("[SD] expected tokens/round E(alpha,k) = (1-alpha^(k+1))/(1-alpha)")
    for alpha in (0.6, 0.8):
        for k in (3, 5):
            p(f"[SD]   alpha={alpha} k={k}: E={e_tokens(alpha, k):.2f}")
    # Case 1: phone drafts, desktop verifies (the typical personal mesh direction)
    # ASSUMPTIONS: phone 1.7B draft ~20 tok/s (50 ms/tok); desktop target 8B ~50 tok/s (20 ms/step);
    # verify of k+1 tokens ~ 1.2 target steps.
    t_draft, t_step, rtt = 50.0, 20.0, 10.0
    for k in (3, 5):
        e = e_tokens(0.7, k)
        round_ms = k * t_draft + rtt + 1.2 * t_step
        p(f"[SD] phone-drafts/desktop-verifies k={k} alpha=0.7: {e/round_ms*1000:.1f} tok/s vs desktop alone {1000/t_step:.0f} tok/s")
    # Case 2: capacity-bound target on slow node S (e.g. 70B at ~1.5 tok/s -> ~670 ms/step, prima.cpp-like),
    # faster drafter D (5 ms/draft token) vs in-node draft on S (15 ms/draft token). ASSUMPTIONS.
    t_step_s, t_dD, t_dS = 670.0, 5.0, 15.0
    for k in (3, 5):
        e = e_tokens(0.7, k)
        cross = e / (k * t_dD + 10.0 + 1.2 * t_step_s) * 1000
        innode = e / (k * t_dS + 1.2 * t_step_s) * 1000
        p(f"[SD] capacity-bound S, k={k} alpha=0.7: cross-device {cross:.2f} tok/s vs in-node {innode:.2f} tok/s vs plain {1000/t_step_s:.2f} tok/s")

    # Whole-request placement example (the section-1 benefit estimate)
    # ASSUMPTIONS: phone 8B decode 5 tok/s (roadmap section 1 planning assumption), phone prefill 30 tok/s;
    # M4 Pro-class 8B: decode 45 tok/s, prefill 400 tok/s (7B Q4_0 figures 50.74/439.78 VERIFIED, 8B ~10% slower ASSUMED);
    # Wi-Fi RTT 10 ms; prompt 500 tokens / 2 KB; answer 300 tokens.
    P, N = 500, 300
    phone_ttft = P / 30
    phone_total = phone_ttft + (N - 1) / 5
    mac_ttft = 0.010 + 0.010 + P / 400 + 0.005
    mac_total = mac_ttft + (N - 1) / 45
    p(f"[WR] phone local: TTFT {phone_ttft:.1f} s, total {phone_total:.1f} s")
    p(f"[WR] Mac peer:    TTFT {mac_ttft:.2f} s, total {mac_total:.1f} s  (speed-up TTFT x{phone_ttft/mac_ttft:.0f}, total x{phone_total/mac_total:.1f})")
    # Phone battery saved: ASSUMPTION 5 W during inference, 15.4 Wh battery (4000 mAh x 3.85 V)
    j = 5.0 * phone_total
    p(f"[WR] phone energy avoided ~{j:.0f} J = {j/(15.4*3600)*100:.1f}% of a 15.4 Wh battery (ASSUMED 5 W draw)")

    # Fan-out of independent work (embeddings): throughput adds, bounded by the fastest node
    rates = [1000.0, 400.0, 250.0]    # ASSUMED embeddings/s for three heterogeneous nodes
    p(f"[FO] fan-out ceiling = sum/fastest = {sum(rates)/max(rates):.2f}x for rates {rates} (ASSUMED)")
    return out


# --------------------------------------------------------------------------------------
# Part B: the normative integer estimator and scorer (router.md sections 4-5)
# --------------------------------------------------------------------------------------

def ceil_div(a, b):
    assert b > 0
    return -((-a) // b)

THERMAL = {"nominal": 0, "light": 1, "moderate": 2, "severe": 3, "critical": 4}
QUANT_PENALTY_MS = {"F16": 0, "BF16": 0, "Q8_0": 0, "Q6_K": 200, "Q5_K_M": 400, "Q4_K_M": 800,
                    "Q4_0": 1000, "Q3_K_M": 2500, "Q2_K": 5000}
CFG = {
    "peerBiasMs": 1000,
    "msPerBatteryPermille": 2000,
    "heatPermille": 250, "heatPermilleUserActive": 500,
    "stalePermille": 250, "expiredPermille": 500,
    "handshakeExtraMs": 40,
    "minDecodeMilliTokPerSec": 4000, "maxTtftMs": 20000,
}

def decode_ms(n_after_first, dec, steady, onset_ms, busy_ms, queue_ms, prefill_ms, band):
    """Thermal-aware decode time for the tokens after the first (router.md 4.2, E7)."""
    if n_after_first <= 0:
        return 0
    already = busy_ms + queue_ms + prefill_ms
    hot = THERMAL[band] >= THERMAL["moderate"] or (onset_ms is not None and already >= onset_ms)
    if hot:
        return ceil_div(n_after_first * 1_000_000, min(dec, steady))
    if onset_ms is None:                       # no onset observed: rate holds
        return ceil_div(n_after_first * 1_000_000, dec)
    cool_ms = onset_ms - already
    tok_cool = cool_ms * dec // 1_000_000
    if n_after_first <= tok_cool:
        return ceil_div(n_after_first * 1_000_000, dec)
    return cool_ms + ceil_div((n_after_first - tok_cool) * 1_000_000, steady)

def estimate(c, q):
    """c: candidate dict, q: query dict. Returns the E1..E10 terms (router.md 4.2)."""
    is_self = c["tier"] == "SELF"
    rtt = 0 if is_self else c["rttMs"]
    net = 0 if is_self else rtt + ceil_div(q["promptBytes"] * 8, c["kbps"]) + (0 if c["sessionWarm"] else 2 * rtt + CFG["handshakeExtraMs"])
    queue = c["queueMs"]
    load = 0 if c["loaded"] else ceil_div(c["fileBytes"], c["loadBytesPerMs"])
    prefill = ceil_div(q["promptTokens"] * 1_000_000, c["prefillMilliTokPerSec"]) + c["ttft0Ms"]
    first = 0 if is_self else ceil_div(rtt, 2)
    ttft = net + queue + load + prefill + first
    dec = c["decodeMilliTokPerSec"]
    dms = decode_ms(q["outTokens"] - 1, dec, c["steadyMilliTokPerSec"], c["throttleOnsetMs"],
                    c["busyForMs"], queue, prefill, c["thermalBand"])
    total = ttft + dms
    active = prefill + dms
    energy_mj = c["powerMilliW"] * active // 1000
    if c["onBattery"] and not c["charging"]:
        cap_mj = c["batteryDesignMilliWh"] * 3600
        used_permille = ceil_div(energy_mj * 1000, cap_mj)
        b = c["batteryPermille"]
        mult = 1000 if b >= 500 else (2000 if b >= 200 else 4000)
        battery_cost = used_permille * CFG["msPerBatteryPermille"] * mult // 1000
    else:
        used_permille = 0
        battery_cost = 0
    hot_or_forecast = THERMAL[c["thermalBand"]] >= THERMAL["light"] or c["forecastHeadroomPermille"] >= 750
    if c["deviceClass"] in ("phone", "tablet", "handheld") and hot_or_forecast:
        hp = CFG["heatPermilleUserActive"] if c["userActive"] else CFG["heatPermille"]
        heat = active * hp // 1000
    else:
        heat = 0
    return {"netMs": net, "queueMs": queue, "loadMs": load, "prefillMs": prefill, "firstTokMs": first,
            "ttftMs": ttft, "decodeMs": dms, "totalMs": total, "energyMilliJ": energy_mj,
            "batteryUsedPermille": used_permille, "batteryCostMs": battery_cost, "heatCostMs": heat,
            "decodeEffMilliTokPerSec": dec}

def score(c, q, e):
    t = e["totalMs"] + (e["ttftMs"] if q["stream"] else 0)
    bias = 0 if c["tier"] == "SELF" else CFG["peerBiasMs"]
    quality = QUANT_PENALTY_MS.get(c["quant"], 1000)
    unc = {"FRESH": 0, "WARM": 0, "STALE": e["totalMs"] * CFG["stalePermille"] // 1000,
           "EXPIRED": e["totalMs"] * CFG["expiredPermille"] // 1000}[c["freshness"]]
    terms = {"S1_timeMs": t, "S2_batteryMs": e["batteryCostMs"], "S3_heatMs": e["heatCostMs"],
             "S4_localityBiasMs": bias, "S5_qualityMs": quality, "S6_uncertaintyMs": unc}
    total = sum(terms.values())
    usable = (e["decodeEffMilliTokPerSec"] >= CFG["minDecodeMilliTokPerSec"]
              and e["ttftMs"] <= CFG["maxTtftMs"] and e["totalMs"] <= q["deadlineMs"])
    return terms, total, usable

def worked_example():
    # Query: streaming chat, 500 prompt tokens (~2,000 bytes), 300 expected output tokens.
    q = {"promptTokens": 500, "promptBytes": 2000, "outTokens": 300, "stream": True, "deadlineMs": 120000}
    base = {"fileBytes": 5_027_784_832, "quant": "Q4_K_M", "ttft0Ms": 0}
    cands = [
        dict(base, name="self (phone, battery 60%, light thermal)", tier="SELF", nodeId="self",
             rttMs=0, kbps=1, sessionWarm=True, queueMs=0, loaded=True, loadBytesPerMs=500_000,
             prefillMilliTokPerSec=30_000, decodeMilliTokPerSec=5_000, steadyMilliTokPerSec=3_300,
             throttleOnsetMs=195_000, busyForMs=0, thermalBand="light", forecastHeadroomPermille=600,
             powerMilliW=5_000, onBattery=True, charging=False, batteryDesignMilliWh=15_400,
             batteryPermille=600, deviceClass="phone", userActive=True, freshness="FRESH"),
        dict(base, name="peer Mac (AC, idle, model loaded)", tier="PEER", nodeId="RWLS-SQQ3-SEZA-5MRW",
             rttMs=10, kbps=300_000, sessionWarm=True, queueMs=0, loaded=True, loadBytesPerMs=2_000_000,
             prefillMilliTokPerSec=400_000, decodeMilliTokPerSec=45_000, steadyMilliTokPerSec=45_000,
             throttleOnsetMs=None, busyForMs=0, thermalBand="nominal", forecastHeadroomPermille=200,
             powerMilliW=40_000, onBattery=False, charging=False, batteryDesignMilliWh=0,
             batteryPermille=1000, deviceClass="desktop", userActive=False, freshness="WARM"),
        dict(base, name="peer Deck (AC, busy with one queued job, file not loaded, state STALE)", tier="PEER",
             nodeId="K7QD-2MXA-PL4E-9TNB", rttMs=12, kbps=200_000, sessionWarm=False, queueMs=9_000,
             loaded=False, loadBytesPerMs=1_000_000, prefillMilliTokPerSec=120_000,
             decodeMilliTokPerSec=14_000, steadyMilliTokPerSec=12_000, throttleOnsetMs=600_000,
             busyForMs=30_000, thermalBand="nominal", forecastHeadroomPermille=300, powerMilliW=15_000,
             onBattery=False, charging=True, batteryDesignMilliWh=40_000, batteryPermille=800,
             deviceClass="handheld", userActive=False, freshness="STALE"),
    ]
    rows = []
    for c in cands:
        e = estimate(c, q)
        terms, total, usable = score(c, q, e)
        rows.append((total, {"SELF": 0, "PEER": 1}[c["tier"]], c["nodeId"], c, e, terms, usable))
    rows.sort(key=lambda r: (r[0], r[1], r[2]))
    lines = []
    vectors = []
    for rank, (total, _, nid, c, e, terms, usable) in enumerate(rows, 1):
        lines.append(f"#{rank} {c['name']}: score={total} usable={usable}")
        lines.append("    estimate: " + json.dumps(e, sort_keys=True))
        lines.append("    terms:    " + json.dumps(terms, sort_keys=True))
        vectors.append({"id": f"R02-9{rank:02d}", "origin": "generated", "status": "illustrative",
                        "description": f"router.md 5.7 worked example candidate: {c['name']}",
                        "input": {"query": q, "candidate": {k: v for k, v in c.items() if k != 'name'}},
                        "expect": {"ok": {"estimate": e, "terms": terms, "score": total, "usable": usable}}})
    # dominant-term explanation (router.md 10.2)
    w, r = rows[0], rows[1]
    diffs = [(r[5][k] - w[5][k], k) for k in sorted(w[5])]
    best = max(diffs, key=lambda d: (d[0], -sorted(w[5]).index(d[1])))
    lines.append(f"winner={w[3]['nodeId']} runner-up={r[3]['nodeId']} dominant term={best[1]} (+{best[0]} ms for runner-up)")
    return lines, vectors

if __name__ == "__main__":
    for line in mode_math():
        print(line)
    print()
    lines, vectors = worked_example()
    for line in lines:
        print(line)
    with open(__file__.replace("router_ref.py", "R02-worked-example.json"), "w") as f:
        json.dump({"family": "R02", "confVersion": "0.0.0", "specRefs": ["router.md#5.7"], "vectors": vectors},
                  f, indent=1, sort_keys=True)
