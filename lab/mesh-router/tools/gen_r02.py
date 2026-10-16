"""R02 (estimator and score, plan level) for gen_vectors.py. Expected values: ref (ref.py), with the spec's own worked numbers asserted (R02-r3-001)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ref  # noqa: E402
from common import ok, s8, vec, write  # noqa: E402
from worlds import *  # noqa: E402,F401,F403

R02 = []
_n = [0]
FIELDS = ["outTokens", "netMs", "loadMs", "queueMs", "preEff", "prefillMs", "decEff", "decodeMs", "ttftMs", "totalMs", "energyMilliJ", "batteryUsedPermille"]


def r02(desc, w, anchor=None, status="normative", expect_excluded=None):
    _n[0] += 1
    id_ = f"R02-{_n[0]:03d}"
    ex, surv = ref.sovereign(w)
    if expect_excluded is None:
        assert not ex, f"{id_}: unexpected exclusions {ex}"
    attempts = {}
    for x in surv:
        key = x["id"].split("/")[0] + "/" + s8(x["file"]["fileSha256"])
        sc = x["score"]
        attempts[key] = dict(estimate={k: x["est"][k] for k in FIELDS}, terms=sc, usable=x["usable"])
    if anchor:
        for key, want in anchor.items():
            got = dict(attempts[key]["estimate"], **attempts[key]["terms"])
            for k, v in want.items():
                assert got[k] == v, f"{id_} {key}: {k} = {got[k]}, the spec says {v}"
    R02.append(vec(id_, desc, w, ok({"attempts": attempts}), status, origin="generated"))
    return attempts


def only_peer(peer, q=None, **kw):
    return WORLD(SELF(situation=SS(engine=False)), [peer], q=q or Q(), **kw)


def only_self(self_, q=None, **kw):
    return WORLD(self_, [], q=q or Q(), **kw)


# R02-r3-001 (LAB_SPEC 6.4): query P = 500, B = 2,000, N = 300 (cap 300), stream. Every number below is the spec's own.
deck = PEER("deck-node", "HANDHELD", prior=PR(prefill=60000, decode=12000, ttft0=300, steady=12000), last_same={A: NOW - 100})
phone = SELF(situation=SS(permille=600, thermal=1, active=True, design=19000))
r02("R02-r3-001: the Deck (warm session, tracker n = 0 so prior' = claim x 700/1000) against the phone (battery 600 permille, thermal 1, user active); the spec's own numbers",
    WORLD(phone, [deck]),
    anchor={
        "PEER:deck-node/a1a1a1a1": dict(netMs=11, prefillMs=12205, decodeMs=35596, ttftMs=12221, totalMs=47817, S1=60038, S2=0, S3=0, S4=1000, S5=800, S=61838),
        "SELF:self-node/a1a1a1a1": dict(netMs=0, prefillMs=16867, decodeMs=59800, ttftMs=16867, totalMs=76667, S1=93534, S2=12000, S3=38333, S4=0, S5=800, S=144667,
                                        energyMilliJ=383335, batteryUsedPermille=6),
    })
r02("cold session and a cold model: E1 adds 3 x rtt + 40, E2 adds ceilDiv(fileBytes, 1,000,000)", only_peer(PEER("deck-node", "HANDHELD", link=LK(warm=False), last_same={A: NOW - 400000})))
r02("STALE state: queue bucket 1 becomes 2 (30,000 ms) and S6 = 25% of the total", only_peer(PEER("mac", state=ST(qb=1), rx=NOW - 100000, last_same={A: NOW - 100})))
r02("EXPIRED state: last-known fast fields are used as they are and S6 = 50% of the total", only_peer(PEER("mac", state=ST(qb=1, tb=1), rx=NOW - 400000, last_same={A: NOW - 100})))
r02("zero-ish: P = 1, no body bytes, N = 1 (no decode time)", only_peer(PEER("mac", last_same={A: NOW - 100}), q=Q(prompt=1, bytes=0, cap=1)))
r02("N from the per-app output EWMA when no cap is ruled", only_peer(PEER("mac", last_same={A: NOW - 100}), q=Q(cap=None), app_ewma={"app.a": 77}))
r02("N defaults to 256 with neither a cap nor an EWMA", only_peer(PEER("mac", last_same={A: NOW - 100}), q=Q(cap=None)))
r02("N is clamped to 32,768", only_peer(PEER("mac", last_same={A: NOW - 100}, files=[F(ctx=100000)], row=ROW(limits=dict(LIMITS, maxTokens=100000))), q=Q(cap=None), app_ewma={"app.a": 99999}))
for permille in (500, 499, 200, 199):
    r02(f"S2: this device on battery at {permille} permille (multiplier 1000 at >= 500, 2000 at 200..499, 4000 below 200)",
        only_self(SELF(situation=SS(permille=permille, design=19000)), q=Q(prompt=2000, bytes=8000, cap=600)))
r02("S2 is 0 on mains or while charging", only_self(SELF(situation=SS(permille=100, chg=True, design=19000))))
r02("S2: an EXPIRED peer on battery at band lt20 has multiplier 4000 (F11 is skipped when EXPIRED)",
    only_peer(PEER("mac", cls="LAPTOP", state=ST(src="battery", band="lt20"), rx=NOW - 400000, design=50000, last_same={A: NOW - 100})))
r02("S2: a peer on battery at band 50-79 has multiplier 1000", only_peer(PEER("mac", cls="LAPTOP", state=ST(src="battery", band="50-79"), design=50000, last_same={A: NOW - 100})))
r02("S3: this device, user active, thermal code 1: heat 500 permille", only_self(SELF(situation=SS(thermal=1, active=True))))
r02("S3: this device, not user active, thermal code 1: heat 250 permille", only_self(SELF(situation=SS(thermal=1, active=False))))
r02("S3: this device, thermal code 0: no heat", only_self(SELF(situation=SS(thermal=0, active=True))))
r02("S3: a warm handheld peer at band 1: heat 250 permille (peers are always 250)", only_peer(PEER("deck", "HANDHELD", state=ST(tb=1), last_same={A: NOW - 100})))
r02("S3: a desktop is never charged for heat", only_peer(PEER("mac", "DESKTOP", state=ST(tb=1), last_same={A: NOW - 100})))
r02("E7: thermal code 2 on this device takes the hot branch min(dec, steady)", only_self(SELF(situation=SS(thermal=2))))
r02("E7: throttle onset crossed while decoding: coolMs at the decode rate, the rest at the steady rate",
    only_self(SELF(prior=PR(prefill=30000, decode=5000, ttft0=200, steady=2500, onset=20000, power=5000), situation=SS(thermal=0, busy=0)), q=Q(cap=600)))
r02("E7: already past the onset (busy time counts) takes the hot branch", only_self(SELF(prior=PR(prefill=30000, decode=5000, ttft0=200, steady=2500, onset=20000, power=5000), situation=SS(busy=30000))))
r02("E6: the claim decode is the curve at P (between two points, floor division)", only_peer(PEER("mac", prior=PR(curve=[[128, 30000], [1024, 21000]], steady=15000), last_same={A: NOW - 100}), q=Q(prompt=500, bytes=2000)))
r02("E6: P above the last curve point is clamped to it", only_peer(PEER("mac", prior=PR(curve=[[128, 30000], [1024, 21000]], steady=15000), last_same={A: NOW - 100}), q=Q(prompt=5000, bytes=20000)))
r02("E6: P below the first curve point is clamped to it", only_peer(PEER("mac", prior=PR(curve=[[512, 30000], [1024, 21000]], steady=15000), last_same={A: NOW - 100}), q=Q(prompt=100, bytes=400)))
r02("tracker n = 3: the lower median of the kept ratios scales every rate (min(1000, median))", WORLD(SELF(situation=SS(engine=False)), [PEER("mac", last_same={A: NOW - 100})],
    tracker=[TRK("mac", A, "metal", TS(ratios=[900, 500, 700]))]))
r02("tracker n = 2: the blend (2 x prior' + sum(claim x min(1000, x))) / (2 + n)", WORLD(SELF(situation=SS(engine=False)), [PEER("mac", last_same={A: NOW - 100})],
    tracker=[TRK("mac", A, "metal", TS(ratios=[900, 1400]))]))
r02("capRef: the class ceiling (below the claim) is the binding term of prior' while n < 3", WORLD(SELF(situation=SS(engine=False)), [PEER("mac", last_same={A: NOW - 100})],
    config=dict(classCeilings={"qwen3-8b|metal|DESKTOP": dict(prefillMilliTokPerSec=30000, decodeMilliTokPerSec=6000, steadyMilliTokPerSec=6000)})))
r02("capRef: a signed reference p90 x 12/10 is the binding term", WORLD(SELF(situation=SS(engine=False)), [PEER("mac", last_same={A: NOW - 100})],
    config=dict(signedReferenceP90={"qwen3-8b|metal|DESKTOP": dict(prefillMilliTokPerSec=20000, decodeMilliTokPerSec=5000, steadyMilliTokPerSec=5000)})))
r02("the peer-wide discount: two DISCREPANT keys of one peer make disc 400 instead of 700",
    WORLD(SELF(situation=SS(engine=False)), [PEER("mac", files=[F(sha=A), F(sha=B)], state=ST(held=(A, B)), last_same={A: NOW - 100, B: NOW - 100})],
          tracker=[TRK("mac", A, "metal", TS(ratios=[100] * 5)), TRK("mac", B, "metal", TS(ratios=[100] * 5))]),
    expect_excluded=None)
r02("S5, virtual selector: rank difference x 5,000, and an unranked file counts as best + 10",
    WORLD(SELF(situation=SS(engine=False)), [PEER("mac", files=[F(sha=A, rank=1), F(sha=B, rank=3), F(sha=C, rank=None)], state=ST(held=(A, B, C)),
                                                 last_same={A: NOW - 100, B: NOW - 100, C: NOW - 100})]))
r02("S5, concrete model: no rank term", WORLD(SELF(situation=SS(engine=False)), [PEER("mac", files=[F(sha=A, rank=1), F(sha=B, rank=3)], state=ST(held=(A, B)),
                                                                                      last_same={A: NOW - 100, B: NOW - 100})], q=Q(model="qwen3-8b")))
quants = [("F16", A), ("Q8_0", B), ("Q6_K", C), ("Q5_K_M", "d4" * 32), ("Q4_0", "e5" * 32), ("Q3_K_M", "f6" * 32), ("Q2_K", "07" * 32), ("IQ1_S", "18" * 32), (None, "29" * 32)]
r02("S5: the quantisation penalty table, an unknown quantisation and a missing one (1,000)",
    WORLD(SELF(situation=SS(engine=False)), [PEER("mac", files=[F(sha=s, quant=qn) for qn, s in quants], state=ST(held=tuple(s for _, s in quants)),
                                                 last_same={s: NOW - 100 for _, s in quants})], q=Q(model="qwen3-8b"), config=dict(maxAttempts=20, maxPeerAttempts=20)))
r02("E2: this device, file not loaded: ceilDiv(fileBytes, 500,000) for a phone", only_self(SELF(situation=SS(loaded=()))))
r02("E3: own reservations above the queue bucket win the max", only_peer(PEER("mac", state=ST(qb=1), reservations=15000, last_same={A: NOW - 100})))
r02("E3: queue bucket 2 is 30,000 ms", only_peer(PEER("mac", state=ST(qb=2), last_same={A: NOW - 100})))
r02("E3: this device uses its own local queue", only_self(SELF(situation=SS(queue=4500))))
r02("E1: an unmeasured bandwidth uses the path default (OVERLAY 20,000 kbit/s)", only_peer(PEER("mac", link=LK(kbps=0, path="OVERLAY"), last_same={A: NOW - 100})))
r02("E11: a peer's power class default (DESKTOP 150,000 mW) when the claim has no power", only_peer(PEER("mac", last_same={A: NOW - 100})))
r02("usable gate: decEff 3,999 is below 4,000 (peers only)", only_peer(PEER("mac", prior=PR(decode=5714, steady=5714), last_same={A: NOW - 100})))
r02("usable gate: decEff 4,000 passes", only_peer(PEER("mac", prior=PR(decode=5715, steady=5715), last_same={A: NOW - 100})))
r02("usable gate: ttft over 20,000 ms fails", only_peer(PEER("mac", prior=PR(prefill=20000, decode=40000, steady=40000), last_same={A: NOW - 100})))
r02("usable gate: total over the deadline fails", only_peer(PEER("mac", last_same={A: NOW - 100}), q=Q(deadline=10000)))
r02("this device is not subject to the usability gate (D9)", only_self(SELF(prior=PR(prefill=30000, decode=1000, ttft0=200, steady=900, onset=None, power=5000)), q=Q(deadline=5000)))
r02("saturation: a load time beyond 2^53 - 1 saturates and the sum saturates too",
    WORLD(SELF(situation=SS(engine=False)), [PEER("mac", files=[F(bytes=(1 << 60))], last_same={}, prior=PR())], config=dict(loadBytesPerMs={"DESKTOP": 1})))

# review fixes (LTQ-01, LTQ-06): appended so that every earlier id keeps its number
def two_backend_mac(state_backend):
    p = PEER("mac", state=ST(backend=state_backend), last_same={A: NOW - 100})
    p["priors"] = [dict(nodeId="mac", fileSha256=A, backend=b, prior=PR()) for b in ("metal", "vulkan")]
    return p


DISC6 = [TRK("mac", A, "metal", TS(ratios=[400] * 6))]
metal_run = r02("tracker n = 6 of ratio 400 (DISCREPANT), live state on the claim's own backend: every rate is scaled by the lower median 400 (prefill 24,000, decode 4,800)",
                WORLD(SELF(situation=SS(engine=False)), [two_backend_mac("metal")], tracker=DISC6),
                anchor={"PEER:mac/a1a1a1a1": dict(preEff=24000, decEff=4800)})
vulkan_run = r02("the same tracker after the peer's live state switches to backend vulkan: the tracker is keyed (peer, file), so the placement rate is the same (LTQ-01)",
                 WORLD(SELF(situation=SS(engine=False)), [two_backend_mac("vulkan")], tracker=DISC6),
                 anchor={"PEER:mac/a1a1a1a1": dict(preEff=24000, decEff=4800)})
assert metal_run == vulkan_run
r02("S6 charges the worse of the digest class and the power class: a FRESH digest with STALE power fields is 25% of the total, and the band 'ge80' reads 50-79 (LTQ-06)",
    only_peer(PEER("mac", cls="LAPTOP", state=ST(src="battery", band="ge80"), design=50000, power_freshness="STALE", last_same={A: NOW - 100})))
r02("S6 with EXPIRED power fields and a FRESH digest is 50% of the total (LTQ-06)",
    only_peer(PEER("mac", cls="LAPTOP", state=ST(src="battery", band="ge80"), design=50000, power_freshness="EXPIRED", last_same={A: NOW - 100})))

write("R02-scoring.json", "R02", ["LAB_SPEC.md 6.4 (E0-E12, S1-S6)", "LAB_SPEC.md 6.4 worked example R02-r3-001", "docs/design/mesh/REVIEW_ROUND3.md R3-CLOSURE-5"], R02)
