"""M08 (the claim tracker and its adversaries, LAB_SPEC 6.6) for gen_vectors.py.

The worked numbers (M08-001..004) and the adversary table (M08-010..022) are the spec's own, with the expected values written out by hand in the descriptions and
asserted below. The remaining vectors (M08-023 on) are hand-derived and cross-checked against the Python tracker in this file, an independent reading of 6.6.
R3-OVERCLAIM-1: M08-017 does NOT claim the design's 'at most about 2x' padding bound; it pins what the mechanism gives (ratio 2,071 at 3,000 bytes of VISIBLE filler) and the
worst case at the cap (M08-046, M08-047). r4 (LAB_SPEC R4-L-18, R4-L-19): `outBytes` is counted over the normalised answer text (`normalise()` below, an independent reading with
`unicodedata`); M08-083..093 pin the normalisation, M08-094..096 interpolation, M08-097 inheritance with no recorded claim body, M08-098..101 the ASC01 state and penalty cases. The
file is written with ascii_only so that the invisible characters of the normalisation vectors are visible \\uXXXX escapes."""
import json
import os
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ref  # noqa: E402
from common import ok, vec, write  # noqa: E402
from worlds import A, LK, PR  # noqa: E402

M08 = []
_n = [0]
CLAIM = PR(prefill=100000, decode=20000, ttft0=0, steady=20000)
LINK = LK(rtt=10, kbps=100000, warm=True)
BPT = (4000, 8000)


def net_warm(link, b):
    return link["rttMs"] + ref.cdiv(b * 8, link["kbps"])


def base(**kw):
    d = dict(claim=CLAIM, link=LINK, promptTokens=500, promptBytes=5120, maxTokens=1024, bpt=list(BPT), tBodyMs=0, chunks=[], head=None, end=None, acceptedSha=None,
             heldBackend=None, heldCommit=None, claimCommit=None, concurrent=False, peerDigest=None)
    d.update(kw)
    return d


def normalise(text):
    """LAB_SPEC R4-L-19, in the spec's own order: NFC; remove Unicode Cf and control (Cc) characters; collapse every run of space separators (Zs, Zl, Zp) to one
    U+0020; trim. Control characters are removed BEFORE whitespace is collapsed, so a tab or a newline is dropped, not collapsed (ERR-R4B-TR-1)."""
    t = "".join(c for c in unicodedata.normalize("NFC", text) if unicodedata.category(c) not in ("Cf", "Cc"))
    out, pending = [], False
    for c in t:
        if unicodedata.category(c) in ("Zs", "Zl", "Zp"):
            pending = True
        else:
            if pending and out:
                out.append(" ")
            pending = False
            out.append(c)
    return "".join(out)


def chunk_text(c):
    if "parts" in c:
        return "".join(p["text"] * p["times"] for p in c["parts"])
    if "count" in c:
        return "x" * (c["count"] * c["bytesEach"])
    return "x" * c["bytes"]


def evaluate(inp):
    chunks = inp["chunks"]
    last = None
    for c in chunks:
        last = max(last or 0, c["tTo"] if "count" in c else c["t"])
    total = len(normalise("".join(chunk_text(c) for c in chunks)).encode("utf-8"))
    end_t = inp["end"]["t"] if inp["end"] else None
    t_end = max([t for t in (end_t, last) if t is not None])
    elapsed = max(1, t_end - inp["tBodyMs"])
    done = inp["end"] is not None and json.loads(inp["end"]["payload"]).get("terminal") == "done"
    est = ref.out_tok_est(total, inp["bpt"][0])
    pred = ref.predicted(inp["claim"], inp["promptTokens"], net_warm(inp["link"], inp["promptBytes"]), est)
    ratio = ref.ratio_of(pred, elapsed)
    discard = None
    if not done:
        discard = "INCOMPLETE"
    elif total < ref.SHORT:
        discard = "SHORT"
    elif total > inp["maxTokens"] * inp["bpt"][1] // 1000:
        discard = "OVERLONG"
    elif inp["concurrent"]:
        discard = "CONCURRENT"
    elif (inp["acceptedSha"] is not None and inp["acceptedSha"] != A) or (inp["heldBackend"] is not None and inp["heldBackend"] != "metal") or \
            (inp["claimCommit"] is not None and inp["heldCommit"] is not None and inp["heldCommit"] != inp["claimCommit"]):
        discard = "SETTINGS"
    return dict(outBytes=total, outTokEst=est, predictedMs=pred, elapsedMs=elapsed, ratio=ratio, discard=discard)


def m08_eval(id_, desc, inp, want=None):
    got = evaluate(inp)
    if want:
        for k, v in want.items():
            assert got[k] == v, f"{id_}: {k} = {got[k]}, expected {v}"
    M08.append(vec(id_, desc, dict(kind="evaluate", **inp), ok(got)))
    return got


END_OK = '{"attemptId":"a","status":200,"terminal":"done"}'
ONE = [dict(t=20000, bytes=1200)]
EVEN = [dict(tFrom=66, tTo=19800, count=300, bytesEach=4)]

# --- the spec's worked numbers ---------------------------------------------------------------------------------------------------------------------
m08_eval("M08-001", "worked numbers: E1 = 10 + ceilDiv(40,960, 100,000) = 11, E5 = 5,000, outTokEst = 300, E7 = 14,950: predicted = 19,961 ms", base(chunks=ONE, end=dict(t=20500, payload=END_OK)),
         dict(predictedMs=19961, outTokEst=300))
m08_eval("M08-002", "an honest peer finishing at elapsed 20,500: ratio 973", base(chunks=EVEN, end=dict(t=20500, payload=END_OK)), dict(ratio=973, elapsedMs=20500))
m08_eval("M08-003", "a peer whose truth is half its claim: elapsed = 11 + 10,000 + 29,900 = 39,911, ratio 500", base(chunks=[dict(t=39900, bytes=1200)], end=dict(t=39911, payload=END_OK)),
         dict(ratio=500, elapsedMs=39911))
# --- the adversary table (each with a hand-computed expected value) -------------------------------------------------------------------------------
m08_eval("M08-010", "burst at the end: first chunk at tBody + 50, all the rest in one chunk just before tEnd: the same ratio as evenly spaced chunks (973)",
         base(chunks=[dict(t=50, bytes=1), dict(t=20490, bytes=1199)], end=dict(t=20500, payload=END_OK)), dict(ratio=973, outBytes=1200))
m08_eval("M08-011", "split chunks: the same 1,200 bytes as 300 chunks: identical outBytes and ratio", base(chunks=EVEN, end=dict(t=20500, payload=END_OK)), dict(ratio=973, outBytes=1200))
m08_eval("M08-061", "split chunks: the same 1,200 bytes one byte per chunk: identical outBytes and ratio", base(chunks=[dict(tFrom=17, tTo=20000, count=1200, bytesEach=1)], end=dict(t=20500, payload=END_OK)),
         dict(ratio=973, outBytes=1200))
m08_eval("M08-012", "merged chunks: the same text in 2 chunks: identical ratio", base(chunks=[dict(t=10000, bytes=600), dict(t=20000, bytes=600)], end=dict(t=20500, payload=END_OK)), dict(ratio=973, outBytes=1200))
LIE = '{"attemptId":"a","status":200,"terminal":"done","usage":{"completion_tokens":100000,"prompt_tokens":1},"ttftMs":1,"totalMs":2,"decodeMilliTokPerSec":900000000,"outBytes":99999999}'
m08_eval("M08-013", "a lying INFER_END adds usage, ttftMs, totalMs and token counts claiming 10x speed: the members are ignored, the ratio is unchanged (973)",
         base(chunks=ONE, end=dict(t=20500, payload=LIE)), dict(ratio=973, outBytes=1200))
m08_eval("M08-014", "a late INFER_HEAD (5 s after content began): no effect, the head's time is unused", base(chunks=ONE, head=dict(t=25000), end=dict(t=20500, payload=END_OK)), dict(ratio=973))
m08_eval("M08-015", "reported heat: st.tb = 2 on every piggyback has no effect on predicted (claim row only)", base(chunks=ONE, end=dict(t=20500, payload=END_OK), peerDigest=dict(tb=2, qb=0)),
         dict(predictedMs=19961, ratio=973))
m08_eval("M08-016", "reported queue: qb = 2 on every piggyback: no discard, the ratio is unchanged", base(chunks=ONE, end=dict(t=20500, payload=END_OK), peerDigest=dict(tb=0, qb=2)),
         dict(ratio=973, discard=None))
# padding: 1,200 real + 1,800 filler = 3,000 bytes in the honest time (M08-017). R3-OVERCLAIM-1 / LAB_SPEC R4-L-19: the filler is VISIBLE (letters), so the normaliser keeps it. This is
# the stated residual, no longer "at most bptCap / bpt = 2": min(RATIO_CAP / 1000, maxTokens x bptCap / trueOutBytes) = min(5, 1024 x 8 / 1.2 = 6.8) = 5.
m08_eval("M08-017", "padding under the cap with visible filler (1,200 real letters + 1,800 filler letters = 3,000 bytes) in the honest time: normalisation keeps it; outTokEst 750, E7 = 37,450, predicted 42,461, ratio 2,071; "
         "the inflation (2.07x) is inside the stated residual min(RATIO_CAP / 1000, maxTokens x bptCap / trueOutBytes) = min(5, 6.8) = 5, not the r3 bound of about 2x; placement still cannot exceed the claim (effRatio = min(1000, ...))",
         base(chunks=[dict(t=20000, parts=[dict(text="x", times=1200), dict(text="y", times=1800)])], end=dict(t=20500, payload=END_OK)), dict(ratio=2071, predictedMs=42461, outTokEst=750, discard=None, outBytes=3000))
m08_eval("M08-018", "padding over the cap: 8,193 bytes at maxTokens 1,024 (cap 8,192): DISCARD_OVERLONG", base(chunks=[dict(t=20000, bytes=8193)], end=dict(t=20500, payload=END_OK)), dict(discard="OVERLONG"))
m08_eval("M08-062", "padding exactly at the cap (8,192 bytes) is kept: this is the worst case that the tracker cannot see (R3-OVERCLAIM-1)", base(chunks=[dict(t=20000, bytes=8192)], end=dict(t=20500, payload=END_OK)),
         dict(discard=None, outTokEst=2048))
m08_eval("M08-021", "settings switch: INFER_ACCEPT.fileSha256 differs from the claim key: DISCARD_SETTINGS", base(chunks=ONE, end=dict(t=20500, payload=END_OK), acceptedSha="ff" * 32), dict(discard="SETTINGS"))
m08_eval("M08-063", "settings switch: the engine backend this requester holds for the peer differs from the claim row's", base(chunks=ONE, end=dict(t=20500, payload=END_OK), heldBackend="vulkan"), dict(discard="SETTINGS"))
m08_eval("M08-064", "settings switch: the engine commit differs from the claim row's", base(chunks=ONE, end=dict(t=20500, payload=END_OK), heldCommit="bbbbbbb", claimCommit="aaaaaaa"), dict(discard="SETTINGS"))
m08_eval("M08-023", "another attempt of this requester was in flight on that peer: DISCARD_CONCURRENT", base(chunks=ONE, end=dict(t=20500, payload=END_OK), concurrent=True), dict(discard="CONCURRENT"))
m08_eval("M08-024", "the attempt did not end with terminal = done: DISCARD_INCOMPLETE", base(chunks=ONE, end=dict(t=20500, payload='{"terminal":"interrupted"}')), dict(discard="INCOMPLETE"))
m08_eval("M08-025", "127 bytes is SHORT; 128 is kept (SHORT_BYTES = 128)", base(chunks=[dict(t=2000, bytes=127)], end=dict(t=2100, payload=END_OK)), dict(discard="SHORT"))
m08_eval("M08-026", "128 bytes is kept", base(chunks=[dict(t=2000, bytes=128)], end=dict(t=2100, payload=END_OK)), dict(discard=None))
m08_eval("M08-027", "discard order: INCOMPLETE outranks SHORT, OVERLONG, CONCURRENT and SETTINGS", base(chunks=[dict(t=20, bytes=10)], end=dict(t=30, payload='{"terminal":"error"}'), concurrent=True, acceptedSha="ff" * 32),
         dict(discard="INCOMPLETE"))
m08_eval("M08-028", "discard order: OVERLONG outranks CONCURRENT and SETTINGS", base(chunks=[dict(t=2000, bytes=9000)], end=dict(t=2100, payload=END_OK), concurrent=True, acceptedSha="ff" * 32), dict(discard="OVERLONG"))
m08_eval("M08-029", "discard order: CONCURRENT outranks SETTINGS", base(chunks=[dict(t=2000, bytes=1200)], end=dict(t=2100, payload=END_OK), concurrent=True, acceptedSha="ff" * 32), dict(discard="CONCURRENT"))
m08_eval("M08-030", "a very fast peer: the ratio is capped at RATIO_CAP = 5,000", base(chunks=[dict(t=10, bytes=1200)], end=dict(t=20, payload=END_OK)), dict(ratio=5000))
m08_eval("M08-031", "a zero elapsed time is treated as 1 ms (checked arithmetic)", base(chunks=[dict(t=0, bytes=1200)], end=dict(t=0, payload=END_OK)), dict(elapsedMs=1, ratio=5000))
m08_eval("M08-032", "tEnd is the LATER of INFER_END and the last chunk (a chunk after the end frame counts)", base(chunks=[dict(t=30000, bytes=1200)], end=dict(t=20500, payload=END_OK)), dict(elapsedMs=30000))
m08_eval("M08-033", "the claim's throttle onset is honoured by the prediction (onset 5,000 ms: coolMs at the decode rate, the rest at the steady rate)",
         base(claim=PR(prefill=100000, decode=20000, ttft0=0, steady=10000, onset=8000), chunks=ONE, end=dict(t=20500, payload=END_OK)), dict(outTokEst=300))

# --- sequences and states (reducer) --------------------------------------------------------------------------------------------------------------
def summarize(kept, recent, strikes, inherited=False, claim=CLAIM, P=500, disc=700, ceiling=None):
    x = sorted(kept)
    ts = dict(ratios=list(kept), recent=recent, inheritedDiscrepant=inherited)
    state = ref.state_of(ts)
    best = x[(3 * len(x)) // 4] if x else None
    cd = ref.decode_at(claim["decodeAt"], P)
    cl = ceiling
    tracked = dict(
        prefill=ref.tracked_rate(claim["prefillMilliTokPerSec"], ts, cl["prefillMilliTokPerSec"] if cl else None, disc),
        decodeAtP=ref.tracked_rate(cd, ts, cl["decodeMilliTokPerSec"] if cl else None, disc),
        steady=ref.tracked_rate(claim["steadyMilliTokPerSec"], ts, cl["steadyMilliTokPerSec"] if cl else None, disc),
    )
    return dict(n=len(kept), ratios=list(kept), best=best, state=state, strikes=strikes, budgetTripped=ref.budget_tripped(ts), minRatio=ref.min_ratio(ts), tracked=tracked)


def sequence(id_, desc, observations, want=None, disc=700, ceiling=None, claim=CLAIM):
    kept, recent, strikes = [], [], 0
    for o in observations:
        inp = base(claim=claim, chunks=[dict(t=o["elapsed"], bytes=o["bytes"])], end=dict(t=o["elapsed"], payload=END_OK if o.get("done", True) else '{"terminal":"error"}'),
                   concurrent=o.get("concurrent", False))
        ev = evaluate(inp)
        is_kept = ev["discard"] is None
        if is_kept:
            kept = (kept + [ev["ratio"]])[-ref.WIN:]
        if ev["discard"] == "OVERLONG":
            strikes += 1
        recent = (recent + [dict(kept=is_kept, ratio=ev["ratio"], outBytes=ev["outBytes"])])[-20:]
    s = summarize(kept, recent, strikes, claim=claim, disc=disc, ceiling=ceiling)
    if want:
        for k, v in want.items():
            assert s[k] == v, f"{id_}: {k} = {s[k]}, expected {v}"
    inp = dict(kind="sequence", claim=claim, link=LINK, promptTokens=500, promptBytes=5120, maxTokens=1024, bpt=list(BPT), observations=observations, disc=disc, ceiling=ceiling)
    M08.append(vec(id_, desc, inp, ok(s)))


HALF = dict(bytes=1200, elapsed=39911)
HONEST = dict(bytes=1200, elapsed=20500)
sequence("M08-004", "5 kept observations of the half-speed peer: state DISCREPANT, and its placement is min(1000, median 500) = its true speed (prefill 50,000, decode 10,000)", [HALF] * 5,
         dict(state="DISCREPANT", best=500, n=5))
sequence("M08-034", "4 kept observations are UNVERIFIED whatever the ratios (MIN_STATE = 5)", [HALF] * 4, dict(state="UNVERIFIED", n=4))
sequence("M08-035", "n = 0: prior' = claim x 700 / 1000 (prefill 70,000, decode 14,000)", [], dict(state="UNVERIFIED", n=0, tracked=dict(prefill=70000, decodeAtP=14000, steady=14000)))
sequence("M08-036", "n = 1: the blend (2 x prior' + claim x min(1000, x)) / 3", [HONEST], dict(n=1))
sequence("M08-037", "n = 2: the blend with two kept ratios", [HONEST, dict(bytes=1200, elapsed=15000)], dict(n=2))
sequence("M08-038", "n = 3: the lower median of the kept ratios, capped at 1000", [HONEST, HONEST, HALF], dict(n=3))
sequence("M08-039", "an even n takes the lower median x[(n - 1) / 2]: n = 6", [HALF, HALF, HALF, HONEST, HONEST, HONEST], dict(n=6))
sequence("M08-020", "an honest busy peer (queueing on 2 of 5): ratios {400, 450, 900, 950, 960}: best = x[3] = 950 -> CORROBORATED, placement min(1000, x[2] = 900)",
         [dict(bytes=1200, elapsed=49900), dict(bytes=1200, elapsed=44300), dict(bytes=1200, elapsed=22200), dict(bytes=1200, elapsed=21000), dict(bytes=1200, elapsed=20800)],
         dict(state="CORROBORATED", n=5))
sequence("M08-040", "a fast peer: every ratio above 1000 still places at the claim (effRatio = min(1000, median))", [dict(bytes=1200, elapsed=10000)] * 6,
         dict(state="CORROBORATED", tracked=dict(prefill=100000, decodeAtP=20000, steady=20000)))
sequence("M08-041", "best = x[(3n)/4]: the upper quartile of 5 ratios is x[3]", [dict(bytes=1200, elapsed=e) for e in (60000, 45000, 30000, 25000, 20000)], dict(n=5))
sequence("M08-019", "truncation: four answers of < 128 bytes: the discard budget trips at the 4th, state WEAK, tracked rate clamped to the lowest ratio among observations of >= 8 bytes",
         [dict(bytes=20, elapsed=200000), dict(bytes=30, elapsed=100000), dict(bytes=40, elapsed=150000), dict(bytes=10, elapsed=250000)], dict(state="WEAK", budgetTripped=True, n=0))
sequence("M08-042", "truncation with every answer under 8 bytes: no qualifying observation, the tracked rate clamps to 0 (the candidate then fails F8)", [dict(bytes=4, elapsed=1000)] * 4,
         dict(budgetTripped=True, tracked=dict(prefill=0, decodeAtP=0, steady=0)))
sequence("M08-043", "exactly half discarded (2 of 4) does not trip the budget", [dict(bytes=20, elapsed=2000), dict(bytes=20, elapsed=2000), HONEST, HONEST], dict(budgetTripped=False, n=2))
sequence("M08-044", "more than half discarded (3 of 5) trips the budget even with kept ratios", [dict(bytes=20, elapsed=2000)] * 3 + [HONEST] * 2, dict(budgetTripped=True, n=2))
sequence("M08-045", "the window keeps the last WIN = 20 kept ratios (25 observations)", [HALF] * 5 + [HONEST] * 20, dict(n=20))
sequence("M08-046", "R3-OVERCLAIM-1: a peer truly 5x slower than it claims, padding every answer to the cap (8,192 bytes), reads CORROBORATED and is placed at the full claim",
         [dict(bytes=8192, elapsed=20500 + 4 * 19961)] * 5, dict(state="CORROBORATED", tracked=dict(prefill=100000, decodeAtP=20000, steady=20000)))
sequence("M08-047", "the same padding against a peer only 7x slower: WEAK (the residual is bounded, not absent)", [dict(bytes=8192, elapsed=20500 + 6 * 19961)] * 5, dict(state="WEAK"))
sequence("M08-048", "OVERLONG answers add a strike each and are never kept", [dict(bytes=8300, elapsed=20500)] * 3, dict(strikes=3, n=0))
sequence("M08-049", "a concurrent attempt is discarded and does not enter the window", [dict(bytes=1200, elapsed=20500, concurrent=True)] * 2 + [HONEST], dict(n=1))
sequence("M08-050", "state thresholds: best = 800 is CORROBORATED", [dict(bytes=1200, elapsed=24951)] * 5, dict(state="CORROBORATED"))
sequence("M08-051", "state thresholds: best = 799 is WEAK", [dict(bytes=1200, elapsed=24982)] * 5, dict(state="WEAK"))
sequence("M08-052", "state thresholds: best = 600 is WEAK", [dict(bytes=1200, elapsed=33268)] * 5, dict(state="WEAK"))
sequence("M08-053", "state thresholds: best = 599 is DISCREPANT", [dict(bytes=1200, elapsed=33324)] * 5, dict(state="DISCREPANT"))
sequence("M08-054", "the peer-wide 400 permille discount replaces 700 (n = 0: prefill 40,000)", [], dict(tracked=dict(prefill=40000, decodeAtP=8000, steady=8000)), disc=400)
CEIL = dict(prefillMilliTokPerSec=50000, decodeMilliTokPerSec=10000, steadyMilliTokPerSec=10000)
sequence("M08-055", "capRef below the claim binds prior' while n < 3: min(claim, capRef) x 700 / 1000 (prefill 35,000)", [], dict(tracked=dict(prefill=35000, decodeAtP=7000, steady=7000)), ceiling=CEIL)
sequence("M08-056", "capRef does not apply once n >= 3 (the observed median replaces the prior)", [HONEST] * 3, ceiling=CEIL)

# raw states
def state_vec(id_, desc, ratios=(), recent=(), inherited=False, disc=700, want=None, claim=CLAIM):
    ts_recent = list(recent)
    s = summarize(list(ratios), ts_recent, 0, inherited=inherited, disc=disc, claim=claim)
    if want:
        for k, v in want.items():
            assert s[k] == v, f"{id_}: {k} = {s[k]}, expected {v}"
    M08.append(vec(id_, desc, dict(kind="state", claim=claim, promptTokens=500, ratios=list(ratios), recent=ts_recent, inheritedDiscrepant=inherited, disc=disc, ceiling=None), ok(s)))


state_vec("M08-057", "an inherited DISCREPANT state stays DISCREPANT whatever the new ratios say until it clears", [1000] * 6, inherited=True, want=dict(state="DISCREPANT"))
state_vec("M08-058", "ratios above 1000 in the window: the median is capped, the state uses the raw best", [3000, 3000, 3000, 3000, 3000], want=dict(state="CORROBORATED", tracked=dict(prefill=100000, decodeAtP=20000, steady=20000)))
state_vec("M08-059", "a WEAK budget clamp never lifts a lower tracked rate: min(median-based, minRatio-based)", [900, 900, 900],
          recent=[dict(kept=False, ratio=300, outBytes=50)] * 3 + [dict(kept=True, ratio=900, outBytes=1200)] * 3, want=dict(budgetTripped=False))

# claim bodies (24 h, new seq only)
def claim_body(id_, desc, events, want_accepts):
    accepts, seq, at = [], None, None
    for e in events:
        if (seq is not None and e["seq"] <= seq) or (at is not None and e["at"] - at < 86400000):
            accepts.append(False)
        else:
            accepts.append(True)
            seq, at = e["seq"], e["at"]
    assert accepts == want_accepts, f"{id_}: {accepts} vs {want_accepts}"
    M08.append(vec(id_, desc, dict(kind="claimBody", events=events), ok(dict(accepted=accepts))))


claim_body("M08-022", "the peer publishes a new claim seq twice in one day: the second is ignored for routing until 24 h pass", [dict(seq=1, at=1000), dict(seq=2, at=3601000), dict(seq=2, at=86401000)], [True, False, True])
claim_body("M08-060", "a claim body with a seq that is not higher is never accepted (rollback)", [dict(seq=5, at=0), dict(seq=5, at=90000000), dict(seq=4, at=180000000)], [True, False, False])

# --- review fixes (LTQ-05, LTQ-12): appended so that every earlier id keeps its number ---------------------------------------------------------------
DAY = 86400000


def ratio_of_obs(o, claim=CLAIM):
    return evaluate(base(claim=claim, chunks=[dict(t=o["elapsed"], bytes=o["bytes"])], end=dict(t=o["elapsed"], payload=END_OK)))


def inherit(id_, desc, before, after, new_claim_at, want_accepted, want_states, initial_claim=True):
    """A DISCREPANT window, then a new claim seq: DISCREPANT is inherited until 10 new kept observations have best >= CORR. With initial_claim False no claim body was recorded
    before the window (LAB_SPEC R4-L-18: DISCREPANT is inherited then too, and the 24 h gate has no earlier acceptance to wait for)."""
    ratios, recent = [], []
    for o in before:
        ev = ratio_of_obs(o)
        ratios = (ratios + [ev["ratio"]])[-ref.WIN:]
        recent = (recent + [dict(kept=True, ratio=ev["ratio"], outBytes=ev["outBytes"])])[-20:]
    was_bad = ref.state_of(dict(ratios=ratios, recent=recent, inheritedDiscrepant=False)) == "DISCREPANT"
    accepted = new_claim_at >= DAY or not initial_claim
    inherited, ratios = was_bad, []
    states = []
    for o in after:
        ev = ratio_of_obs(o)
        ratios = (ratios + [ev["ratio"]])[-ref.WIN:]
        if inherited and len(ratios) >= 10 and sorted(ratios)[(3 * len(ratios)) // 4] >= ref.CORR:
            inherited = False
        states.append(ref.state_of(dict(ratios=ratios, recent=[], inheritedDiscrepant=inherited)))
    assert accepted == want_accepted, f"{id_}: accepted {accepted}"
    assert states == want_states, f"{id_}: {states} vs {want_states}"
    inp = dict(kind="inherit", claim=CLAIM, link=LINK, promptTokens=500, promptBytes=5120, maxTokens=1024, bpt=list(BPT), before=before, newClaimAtMs=new_claim_at, after=after)
    if not initial_claim:
        inp["initialClaim"] = False
    M08.append(vec(id_, desc, inp, ok(dict(accepted=accepted, states=states))))


GOOD = dict(bytes=1200, elapsed=19000)
inherit("M08-065", "an inherited DISCREPANT state holds through 9 good observations and clears at the 10th (LTQ-12: not at MIN_STATE = 5)", [HALF] * 5, [GOOD] * 10, DAY,
        True, ["DISCREPANT"] * 9 + ["CORROBORATED"])
inherit("M08-066", "a window that was not DISCREPANT is not inherited: the new claim seq starts UNVERIFIED and reaches its state after 5 observations", [HONEST] * 5, [GOOD] * 5, DAY,
        True, ["UNVERIFIED"] * 4 + ["CORROBORATED"])
inherit("M08-067", "an inherited DISCREPANT state does not clear on 10 observations whose best is below CORR", [HALF] * 5, [HALF] * 10, DAY,
        True, ["DISCREPANT"] * 10)


def claim_budget(id_, desc, observations, want_state, want_tracked_prefill):
    """A tripped discard budget survives a new claim seq (only W restarts, LTQ-05)."""
    kept, recent = [], []
    for o in observations:
        ev = ratio_of_obs(o)
        is_kept = ev["discard"] is None
        if is_kept:
            kept = (kept + [ev["ratio"]])[-ref.WIN:]
        recent = (recent + [dict(kept=is_kept, ratio=ev["ratio"], outBytes=ev["outBytes"])])[-20:]
    s = summarize([], recent, 0)
    assert s["state"] == want_state, f"{id_}: {s['state']}"
    assert s["tracked"]["prefill"] == want_tracked_prefill, f"{id_}: {s['tracked']}"
    inp = dict(kind="claimBudget", claim=CLAIM, link=LINK, promptTokens=500, promptBytes=5120, maxTokens=1024, bpt=list(BPT), observations=observations, newClaimAtMs=DAY + 1)
    M08.append(vec(id_, desc, inp, ok(dict(accepted=True, state=s["state"], budgetTripped=s["budgetTripped"], n=0, tracked=s["tracked"]))))


claim_budget("M08-068", "a new claim seq 24 h later restarts W but not the discard budget: four truncated answers still read WEAK and still clamp the rate to the lowest ratio of an answer of >= 8 bytes (40 bytes: predicted 5,461 ms, ratio 27, prefill 2,700)",
             [dict(bytes=40, elapsed=200000)] * 4, "WEAK", 2700)
claim_budget("M08-069", "a new claim seq with every truncated answer under 8 bytes: the clamp is 0 and the candidate fails F8", [dict(bytes=4, elapsed=1000)] * 4, "WEAK", 0)


def penalty(id_, desc, events, probes, want_penalty, want_disc):
    """Two files of one peer DISCREPANT latch a peer-wide penalty of 7 days, doubling on every repeat. Each file keeps its discard record: a tripped budget never lifts DISCREPANT."""
    files = {"A": dict(ratios=[], recent=[]), "B": dict(ratios=[], recent=[])}
    pen = None

    def bad_files():
        return sum(1 for f in files.values() if ref.state_of(dict(ratios=f["ratios"], recent=f["recent"], inheritedDiscrepant=False)) == "DISCREPANT")

    for e in events:
        ev = ratio_of_obs(e)
        f = files[e["file"]]
        is_kept = ev["discard"] is None
        if is_kept:
            f["ratios"] = (f["ratios"] + [ev["ratio"]])[-ref.WIN:]
        f["recent"] = (f["recent"] + [dict(kept=is_kept, ratio=ev["ratio"], outBytes=ev["outBytes"])])[-20:]
        active = pen is not None and pen["untilWallMs"] > e["at"]
        if not active and bad_files() >= 2:
            reps = pen["repeats"] if pen else 0
            pen = dict(untilWallMs=e["at"] + 7 * DAY * (1 << reps), repeats=reps + 1)
    discs = []
    for t in probes:
        discs.append(400 if (pen is not None and pen["untilWallMs"] > t) or bad_files() >= 2 else 700)
    assert pen == want_penalty, f"{id_}: {pen} vs {want_penalty}"
    assert discs == want_disc, f"{id_}: {discs} vs {want_disc}"
    inp = dict(kind="penalty", claim=CLAIM, link=LINK, promptTokens=500, promptBytes=5120, maxTokens=1024, bpt=list(BPT), events=events, probes=probes)
    M08.append(vec(id_, desc, inp, ok(dict(penalty=pen, disc=discs))))


def bad_obs(file, at):
    return dict(file=file, bytes=1200, elapsed=39911, at=at)


FIVE_A = [bad_obs("A", 1000)] * 5
FIVE_B = [bad_obs("B", 2000)] * 5
penalty("M08-070", "one DISCREPANT file of a peer does not latch a penalty: disc stays 700", FIVE_A, [1000, 5000], None, [700, 700])
penalty("M08-071", "two DISCREPANT files latch a 7-day penalty at the observation that makes the second DISCREPANT; disc is 400 until it ends and 400 after it while both stay DISCREPANT",
        FIVE_A + FIVE_B, [2000, 2000 + 7 * DAY - 1, 2000 + 7 * DAY], dict(untilWallMs=2000 + 7 * DAY, repeats=1), [400, 400, 400])
penalty("M08-072", "a second penalty of a peer that is still DISCREPANT lasts 14 days (doubling on repeat)", FIVE_A + FIVE_B + [bad_obs("B", 2000 + 7 * DAY + 5)],
        [2000 + 7 * DAY + 5, 2000 + 21 * DAY + 4, 2000 + 21 * DAY + 5], dict(untilWallMs=2000 + 7 * DAY + 5 + 14 * DAY, repeats=2), [400, 400, 400])
penalty("M08-073", "a third penalty lasts 28 days", FIVE_A + FIVE_B + [bad_obs("B", 2000 + 7 * DAY + 5), bad_obs("B", 2000 + 21 * DAY + 10)], [2000 + 21 * DAY + 10],
        dict(untilWallMs=2000 + 21 * DAY + 10 + 28 * DAY, repeats=3), [400])


def disc_vec(id_, desc, states, penalty_, now, want):
    got = None
    if penalty_ is not None and penalty_["untilWallMs"] > now:
        got = 400
    else:
        bad = sum(1 for st in states if ref.state_of(dict(ratios=st["ratios"], recent=[], inheritedDiscrepant=st["inherited"])) == "DISCREPANT")
        got = 400 if bad >= 2 else 700
    assert got == want, f"{id_}: {got} vs {want}"
    M08.append(vec(id_, desc, dict(kind="disc", states=states, penalty=penalty_, now=now), ok(dict(disc=got))))


INH = lambda f: dict(file=f, ratios=[], inherited=True)
disc_vec("M08-074", "two files DISCREPANT only by inheritance (no observation, so no penalty was latched) still give disc 400", [INH("A"), INH("B")], None, 1000, 400)
disc_vec("M08-075", "one file DISCREPANT by inheritance gives disc 700", [INH("A")], None, 1000, 700)
disc_vec("M08-076", "two files DISCREPANT by ratios (best < 600) give disc 400", [dict(file="A", ratios=[100] * 5, inherited=False), dict(file="B", ratios=[100] * 5, inherited=False)], None, 1000, 400)
disc_vec("M08-077", "a running penalty gives disc 400 whatever the states are", [], dict(untilWallMs=5000, repeats=1), 4999, 400)
disc_vec("M08-078", "a penalty that ended at now gives disc 700 when fewer than two files are DISCREPANT", [INH("A")], dict(untilWallMs=5000, repeats=1), 5000, 700)

# best = x[(3n)/4] at the lengths where nearest-rank differs from the next index down (LTQ-12)
for n_, want_best in ((8, 700), (12, 1000), (16, 1300), (20, 1600)):
    rs = [100 * (k + 1) for k in range(n_)]
    state_vec(f"M08-{79 + (8, 12, 16, 20).index(n_):03d}", f"best at n = {n_} is x[{(3 * n_) // 4}] = {want_best} (nearest rank, not x[{(3 * n_ - 1) // 4}])", rs, want=dict(best=want_best))


# --- r4 builder actions 6 and 7 (REVISION_4.md section 6; LAB_SPEC R4-L-18, R4-L-19) -------------------------------------------------------------
def parts(*ps):
    return [dict(text=t, times=n) for t, n in ps]


ZW = "​"
HONEST_END = dict(t=20500, payload=END_OK)
# (a) trailing-whitespace padding: the honest 1,200 letters, then 1,800 bytes of newline, space and tab. Control characters (newline, tab) are removed, space runs collapse, the end is trimmed.
m08_eval("M08-083", "R4-L-19 (a) trailing-whitespace padding: 1,200 letters then 600 newlines, 600 spaces and 600 tabs (3,000 raw bytes) in the honest time: normalised outBytes 1,200, ratio 973, exactly the unpadded answer (M08-002)",
         base(chunks=[dict(t=20000, parts=parts(("x", 1200), ("\n", 600), (" ", 600), ("\t", 600)))], end=HONEST_END), dict(outBytes=1200, ratio=973, discard=None))
# (b) zero-width padding, interleaved: U+200B, U+FEFF, U+2060 are Cf (3 bytes each)
m08_eval("M08-084", "R4-L-19 (b) zero-width padding: 1,200 letters with 300 U+200B, 100 U+FEFF and 200 U+2060 (3 bytes each, 1,800 bytes of padding) interleaved: all Cf, removed: outBytes 1,200, ratio 973",
         base(chunks=[dict(t=20000, parts=parts(("x", 600), (ZW, 300), ("﻿", 100), ("x", 600), ("⁠", 200)))], end=HONEST_END), dict(outBytes=1200, ratio=973, discard=None))
# (c) a short real answer under a large max_tokens: 200 real bytes, maxTokens 4,096 (cap 32,768)
SHORT_REAL = dict(maxTokens=4096)
m08_eval("M08-085", "R4-L-19 (c) a short real answer under a large max_tokens: 200 real letters then 32,568 spaces (32,768 raw bytes = the cap at max_tokens 4,096): normalised outBytes 200, outTokEst 50, predicted 7,461, "
         "elapsed 7,700: ratio 968, the honest ratio (invisible padding gains nothing)",
         base(chunks=[dict(t=7600, parts=parts(("x", 200), (" ", 32568)))], end=dict(t=7700, payload=END_OK), **SHORT_REAL), dict(outBytes=200, outTokEst=50, predictedMs=7461, elapsedMs=7700, ratio=968, discard=None))
m08_eval("M08-086", "R4-L-19 (c) the residual: the same 200 real letters then 32,568 VISIBLE filler letters (32,768 bytes = the cap) in the same time 7,700: outTokEst 8,192, E7 = 409,550, predicted 414,561, ratio capped at RATIO_CAP 5,000; "
         "the bound min(RATIO_CAP / 1000, 4,096 x 8 / 200) = min(5, 163.8) = 5 is reached; placement still cannot exceed the claim",
         base(chunks=[dict(t=7600, parts=parts(("x", 200), ("y", 32568)))], end=dict(t=7700, payload=END_OK), **SHORT_REAL), dict(outBytes=32768, outTokEst=8192, predictedMs=414561, ratio=5000, discard=None))
m08_eval("M08-087", "R4-L-19: padding cannot rescue a short answer: 100 real letters then 1,000 newlines and 1,000 spaces: normalised outBytes 100 < SHORT_BYTES: DISCARD_SHORT",
         base(chunks=[dict(t=2000, parts=parts(("x", 100), ("\n", 1000), (" ", 1000)))], end=dict(t=2100, payload=END_OK)), dict(outBytes=100, discard="SHORT"))
m08_eval("M08-088", "R4-L-19 NFC: 600 decomposed e + U+0301 (3 bytes each, 1,800 raw bytes) compose to 600 precomposed U+00E9 (2 bytes each): outBytes 1,200, ratio 973",
         base(chunks=[dict(t=20000, parts=parts(("é", 600)))], end=HONEST_END), dict(outBytes=1200, ratio=973, discard=None))
m08_eval("M08-089", "R4-L-19 order: control characters are removed BEFORE whitespace runs collapse. 600 letters, a space, U+0007, a space, 599 letters: the two spaces meet and collapse to one: 600 + 1 + 599 = 1,200",
         base(chunks=[dict(t=20000, parts=parts(("x", 600), (" \u0007 ", 1), ("x", 599)))], end=HONEST_END), dict(outBytes=1200, ratio=973, discard=None))
m08_eval("M08-090", "R4-L-19 (ERR-R4B-TR-1): a newline is a control character and is removed, not collapsed to a space: 600 letters, a newline, 600 letters: outBytes 1,200 (not 1,201)",
         base(chunks=[dict(t=20000, parts=parts(("x", 600), ("\n", 1), ("x", 600)))], end=HONEST_END), dict(outBytes=1200, ratio=973, discard=None))
m08_eval("M08-091", "R4-L-19 whitespace: a run of mixed space separators (U+00A0, U+2003, U+2028, U+0020, U+3000) between two 600-letter halves collapses to ONE U+0020 (1 byte), a leading run and a trailing run (U+2029) are trimmed: outBytes 1,201",
         base(chunks=[dict(t=20000, parts=parts(("  ", 3), ("x", 600), ("    　", 4), ("x", 600), (" ", 2)))], end=HONEST_END), dict(outBytes=1201, discard=None))
m08_eval("M08-092", "R4-L-19 chunk-split invariance: a whitespace run split across chunks, a zero-width character at a chunk edge and a decomposed e + U+0301 split across chunks give the same outBytes as one chunk: "
         "400 letters + 1 + 400 letters + U+00E9 (2) + 397 letters = 1,200",
         base(chunks=[dict(t=5000, parts=parts(("x", 400), (" ", 1))), dict(t=10000, parts=parts((" ", 1), ("x", 400), ("e", 1))), dict(t=15000, parts=parts(("́", 1), ("x", 397), (ZW, 1))), dict(t=20000, parts=parts((" ", 1)))],
              end=HONEST_END), dict(outBytes=1200, ratio=973, discard=None))
m08_eval("M08-093", "R4-L-19 OVERLONG is judged on the normalised length: 8,192 letters (the cap at max_tokens 1,024) plus 5,000 U+200B (15,000 bytes of padding) are kept, outBytes 8,192",
         base(chunks=[dict(t=20000, parts=parts(("x", 8192), (ZW, 5000)))], end=HONEST_END), dict(outBytes=8192, discard=None))

# R4-L-18: the interpolation vectors. The curve (256, 30000) - (1024, 20000) at P = 500 is 26,822 (20,600,000 / 768 = 26,822.9, floored), not 26,823.
CURVE = PR(prefill=100000, decode=20000, ttft0=0, steady=20000, curve=[[256, 30000], [1024, 20000]])
m08_eval("M08-094", "R4-L-18 interpolation: claim decode curve (256, 30000)-(1024, 20000) at P = 500: floor((30000 x 524 + 20000 x 244) / 768) = floor(20,600,000 / 768) = 26,822; E7 = ceilDiv(299 x 10^6, 26,822) = 11,148; "
         "predicted = 11 + 5,000 + 11,148 = 16,159; elapsed 16,600: ratio 973",
         base(claim=CURVE, chunks=[dict(t=16500, bytes=1200)], end=dict(t=16600, payload=END_OK)), dict(predictedMs=16159, elapsedMs=16600, ratio=973))
state_vec("M08-095", "R4-L-18: one tracked ratio scales the claim decode AT P; it does not rescale the curve points first: ratios {900, 900, 900}, claim decode at P = 26,822: tracked decodeAtP = floor(26,822 x 900 / 1000) = 24,139 "
          "(scaling the points first would give 24,140); prefill 90,000, steady 18,000", [900, 900, 900], want=dict(tracked=dict(prefill=90000, decodeAtP=24139, steady=18000)), claim=CURVE)
state_vec("M08-096", "R4-L-18: clamped below the curve: at P = 500 a curve whose first point is at 600 ((600, 30000)-(1024, 20000)) gives the first point, 30,000; n = 0: prior' = min(30,000, no capRef) x 700 / 1000 = 21,000",
          [], want=dict(tracked=dict(prefill=70000, decodeAtP=21000, steady=14000)), claim=PR(prefill=100000, decode=20000, ttft0=0, steady=20000, curve=[[600, 30000], [1024, 20000]]))

# R4-L-18: DISCREPANT is inherited also when no claim body was recorded before the window (the JVM `wasBad` required a recorded claimSeq).
inherit("M08-097", "R4-L-18: a DISCREPANT window with NO claim body recorded before it (no earlier seq, no earlier acceptance), then the first claim body at 1,000 ms: it is accepted (nothing to wait 24 h for) and DISCREPANT is inherited "
        "until 10 good observations: 9 DISCREPANT then CORROBORATED", [HALF] * 5, [GOOD] * 10, 1000, True, ["DISCREPANT"] * 9 + ["CORROBORATED"], initial_claim=False)

# ERR-FX2-ASC01: a tripped discard budget never lifts DISCREPANT.
TRUNC = [dict(kept=True, ratio=500, outBytes=1200)] * 5 + [dict(kept=False, ratio=5, outBytes=50)] * 6
state_vec("M08-098", "ERR-FX2-ASC01: five kept ratios of 500 (best 500 < 600: DISCREPANT) and six truncated answers (6 of 11 discarded: budget tripped): the budget never lifts DISCREPANT to WEAK; the clamp still applies: "
          "minRatio 5, tracked prefill 100,000 x 5 / 1000 = 500, decode 100, steady 100", [500] * 5, recent=TRUNC,
          want=dict(state="DISCREPANT", budgetTripped=True, minRatio=5, tracked=dict(prefill=500, decodeAtP=100, steady=100)))
state_vec("M08-099", "ERR-FX2-ASC01 control: a tripped budget on a CORROBORATED window (best 900) turns it WEAK (the budget demotes)", [900] * 5,
          recent=[dict(kept=True, ratio=900, outBytes=1200)] * 5 + [dict(kept=False, ratio=5, outBytes=50)] * 6, want=dict(state="WEAK", budgetTripped=True))
state_vec("M08-100", "ERR-FX2-ASC01 control: a tripped budget on an UNVERIFIED window (n = 4) is WEAK", [900] * 4,
          recent=[dict(kept=True, ratio=900, outBytes=1200)] * 4 + [dict(kept=False, ratio=5, outBytes=50)] * 6, want=dict(state="WEAK", budgetTripped=True))


def trunc_events(file, at, n_short=6):
    return [dict(file=file, bytes=1200, elapsed=39911, at=at)] * 5 + [dict(file=file, bytes=20, elapsed=1000000, at=at)] * n_short


penalty("M08-101", "ERR-FX2-ASC01: two files of one peer are each DISCREPANT (five ratios of 500) and then each truncates six answers (budget tripped): DISCREPANT is not lifted, so the penalty latches at the 5th kept "
        "observation of the second file (7 days from 2,000), is not extended by the later discards, and disc is 400 before, during and after it (both files are still DISCREPANT)",
        trunc_events("A", 1000) + trunc_events("B", 2000), [2000, 2000 + 7 * DAY - 1, 2000 + 7 * DAY, 2000 + 30 * DAY], dict(untilWallMs=2000 + 7 * DAY, repeats=1), [400, 400, 400, 400])

write("M08-claim-tracker.json", "M08", ["LAB_SPEC.md 6.6", "ASOM_MESH_DESIGN.md 5.7", "docs/design/mesh/REVIEW_ROUND3.md R3-OVERCLAIM-1, R3-CLOSURE-5", "LAB_SPEC.md R4-L-18, R4-L-19"], M08, ascii_only=True)
