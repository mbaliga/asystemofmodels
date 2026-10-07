"""R03 (merge per policy and the cap) for gen_vectors.py. The orders are hand-typed and asserted equal to ref.merge (an independent Python reading of LAB_SPEC 6.7)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ref  # noqa: E402
from common import ok, vec, write  # noqa: E402

R03 = []
_n = [0]
SHA = {"a": "a1" * 32, "b": "b2" * 32, "c": "c3" * 32}


def sv(tier, node, sha="a", terms=(0, 0, 0, 0, 0, 0), usable=True, probe=False, claim=None, rank=None, model="m", key=None, backend="metal"):
    claim = claim or ("LOCAL_MEASURED" if tier == "SELF" else "CORROBORATED")
    k = None
    if key or (tier == "PEER" and claim == "UNVERIFIED"):
        k = dict(nodeId=node, fileSha256=SHA[sha], backend=backend)
    return dict(id=f"{tier}:{node}/{SHA[sha][:8]}", tier=tier, nodeId=node, modelId=model, sha=SHA[sha], terms=list(terms), usable=usable, probe=probe, claim=claim, rank=rank, key=k)


def cl(provider, model="m", rank=None, s1=None):
    return dict(id=f"cloud:{provider}/{model}", provider=provider, model=model, rank=rank, s1=s1)


def kk(x):
    return (x["nodeId"], x["sha"], x["key"]["backend"])


def r03(desc, policy, sov, cloud=(), caps=(), never=False, order=None, swapped=None, delta=None):
    _n[0] += 1
    id_ = f"R03-{_n[0]:03d}"
    capmap = {(c["nodeId"], c["fileSha256"], c["backend"]): (c["wouldWin"], c["won"]) for c in caps}
    ref_keyed = [dict(x, key=(kk(x) if x["key"] else None)) for x in sov]
    got, d, sw = ref.merge(policy, ref_keyed, list(cloud), never, capmap)
    if order is not None:
        assert got == order, f"{id_}: hand {order} vs ref {got}"
    if swapped is not None:
        assert sw == swapped, f"{id_}: swapped hand {swapped} vs ref {sw}"
    dl = [dict(nodeId=e["key"][0], fileSha256=e["key"][1], backend=e["key"][2], wouldWinInc=e["wouldWinInc"], wonInc=e["wonInc"], swapped=e["swapped"]) for e in d]
    if delta is not None:
        assert dl == delta, f"{id_}: delta hand {delta} vs ref {dl}"
    inp = dict(policy=policy, neverCloud=never, sovereign=sov, cloud=list(cloud), caps=list(caps))
    R03.append(vec(id_, desc, inp, ok({"order": got, "capDelta": dl, "cappedSwap": sw})))


def cap(node, sha, ww, won, backend="metal"):
    return dict(nodeId=node, fileSha256=SHA[sha], backend=backend, wouldWin=ww, won=won)


P1, P2, P3 = "PEER:p1/a1a1a1a1", "PEER:p2/a1a1a1a1", "PEER:p3/a1a1a1a1"
S0 = "SELF:self/a1a1a1a1"
CO, CG, CA = "cloud:openrouter/m", "cloud:groq/m", "cloud:anthropic/m"

# auto
r03("auto: usable sovereign by S, then the v1 cloud order, then unusable sovereign by S", "auto",
    [sv("PEER", "p1", terms=(100, 0, 0, 0, 0, 0)), sv("PEER", "p2", terms=(50, 0, 0, 0, 0, 0)), sv("PEER", "p3", terms=(10, 0, 0, 0, 0, 0), usable=False)],
    [cl("openrouter"), cl("groq")], order=[P2, P1, CO, CG, P3])
r03("auto: an unusable sovereign candidate goes after every cloud entry even when its score is best", "auto",
    [sv("PEER", "p1", terms=(1, 0, 0, 0, 0, 0), usable=False)], [cl("openrouter")], order=[CO, P1])
r03("auto with the user switch neverCloudWhenDevicesCanAnswer: unusable sovereign moves ahead of the cloud", "auto",
    [sv("PEER", "p1", terms=(100, 0, 0, 0, 0, 0)), sv("PEER", "p3", terms=(10, 0, 0, 0, 0, 0), usable=False)], [cl("openrouter")], never=True, order=[P1, P3, CO])
r03("auto: probe-only entries are last within their block (counterexample to RL9: p1 dominates p2 but is probe-only)", "auto",
    [sv("PEER", "p1", terms=(10, 0, 0, 0, 0, 0), probe=True), sv("PEER", "p2", terms=(90, 0, 0, 0, 0, 0))], order=[P2, P1])
r03("auto: probe-only entries are last within the unusable block too", "auto",
    [sv("PEER", "p1", terms=(10, 0, 0, 0, 0, 0), probe=True, usable=False), sv("PEER", "p2", terms=(90, 0, 0, 0, 0, 0), usable=False)], [cl("openrouter")], order=[CO, P2, P1])
r03("tie-break: equal S, this device (SELF) before a peer", "auto", [sv("PEER", "p1", terms=(70, 0, 0, 0, 0, 0)), sv("SELF", "self", terms=(70, 0, 0, 0, 0, 0))], order=[S0, P1])
r03("tie-break: equal S and tier, by nodeId", "auto", [sv("PEER", "p2", terms=(70, 0, 0, 0, 0, 0)), sv("PEER", "p1", terms=(70, 0, 0, 0, 0, 0))], order=[P1, P2])
r03("tie-break: equal S, tier and node, by modelId then sha", "auto",
    [sv("PEER", "p1", sha="b", model="zeta"), sv("PEER", "p1", sha="c", model="alpha"), sv("PEER", "p1", sha="a", model="alpha")],
    order=["PEER:p1/a1a1a1a1", "PEER:p1/c3c3c3c3", "PEER:p1/b2b2b2b2"])
r03("auto: this device counts as usable, so it is in the first block whatever the gate says", "auto",
    [sv("SELF", "self", terms=(500, 0, 0, 0, 0, 0)), sv("PEER", "p1", terms=(100, 0, 0, 0, 0, 0))], [cl("openrouter")], order=[P1, S0, CO])
# local-only
r03("local-only: this device only, by S", "local-only", [sv("SELF", "self", sha="a", terms=(90, 0, 0, 0, 0, 0)), sv("SELF", "self", sha="b", terms=(30, 0, 0, 0, 0, 0))],
    order=["SELF:self/b2b2b2b2", S0])
# cheapest
r03("cheapest: sovereign by (S2 + S3), then S; the cloud after every sovereign entry", "cheapest",
    [sv("PEER", "p1", terms=(10, 500, 0, 0, 0, 0)), sv("PEER", "p2", terms=(999, 0, 100, 0, 0, 0)), sv("PEER", "p3", terms=(50, 0, 0, 0, 0, 0))],
    [cl("openrouter"), cl("groq")], order=[P3, P2, P1, CO, CG])
r03("cheapest: equal S2 + S3 falls back to S", "cheapest", [sv("PEER", "p1", terms=(200, 0, 0, 0, 0, 0)), sv("PEER", "p2", terms=(100, 0, 0, 0, 0, 0))], order=[P2, P1])
r03("cheapest: probe-only after non-probe with the same (S2 + S3)", "cheapest",
    [sv("PEER", "p1", terms=(100, 0, 0, 0, 0, 0), probe=True), sv("PEER", "p2", terms=(900, 0, 0, 0, 0, 0))], order=[P2, P1])
r03("cheapest: an unusable sovereign candidate still precedes priced cloud", "cheapest", [sv("PEER", "p1", terms=(900, 0, 0, 0, 0, 0), usable=False)], [cl("openrouter")], order=[P1, CO])
# fastest
r03("fastest: each sovereign entry goes before the first cloud entry whose estimate it beats; the cloud order never changes", "fastest",
    [sv("PEER", "p1", terms=(40, 0, 0, 0, 0, 0)), sv("PEER", "p2", terms=(150, 0, 0, 0, 0, 0))], [cl("openrouter", s1=50), cl("groq", s1=100), cl("anthropic", s1=200)],
    order=[P1, CO, CG, P2, CA])
r03("fastest: an unmeasured cloud entry (no estimate) is beaten by any sovereign entry", "fastest",
    [sv("PEER", "p1", terms=(9000, 0, 0, 0, 0, 0))], [cl("openrouter", s1=50), cl("groq", s1=None)], order=[CO, P1, CG])
r03("fastest: a tie goes to the cloud (an entry must beat, not tie)", "fastest", [sv("PEER", "p1", terms=(50, 0, 0, 0, 0, 0))], [cl("openrouter", s1=50)], order=[CO, P1])
r03("fastest: sovereign entries in non-decreasing S1 (probe-only after equal S1 only: RL10 outranks the partition)", "fastest",
    [sv("PEER", "p1", terms=(30, 0, 0, 0, 0, 0), probe=True), sv("PEER", "p2", terms=(60, 0, 0, 0, 0, 0)), sv("PEER", "p3", terms=(30, 5, 0, 0, 0, 0))], order=[P3, P1, P2])
# best-reasoning
r03("best-reasoning: by catalogue rank, sovereign first on equal rank, the cloud order unchanged", "best-reasoning",
    [sv("PEER", "p1", rank=3, terms=(10, 0, 0, 0, 0, 0)), sv("PEER", "p2", rank=2, terms=(99, 0, 0, 0, 0, 0))],
    [cl("anthropic", rank=1), cl("openrouter", rank=2), cl("groq", rank=3)], order=[CA, P2, CO, P1, CG])
r03("best-reasoning: unranked entries last, sovereign before cloud among them", "best-reasoning",
    [sv("PEER", "p1", rank=None, terms=(10, 0, 0, 0, 0, 0))], [cl("openrouter", rank=1), cl("groq", rank=None)], order=[CO, P1, CG])
r03("best-reasoning: equal rank orders sovereign by (probe-only, S)", "best-reasoning",
    [sv("PEER", "p1", rank=2, terms=(10, 0, 0, 0, 0, 0), probe=True), sv("PEER", "p2", rank=2, terms=(50, 0, 0, 0, 0, 0)), sv("PEER", "p3", rank=1, terms=(900, 0, 0, 0, 0, 0))],
    order=[P3, P2, P1])
# the cap
un = lambda node, terms=(10, 0, 0, 0, 0, 0), **kw: sv("PEER", node, claim="UNVERIFIED", terms=terms, **kw)
r03("cap: the top UNVERIFIED candidate wins its first would-win placement", "auto", [un("p1"), sv("PEER", "p2", terms=(20, 0, 0, 0, 0, 0))],
    order=[P1, P2], swapped=False, delta=[dict(nodeId="p1", fileSha256=SHA["a"], backend="metal", wouldWinInc=1, wonInc=1, swapped=False)])
r03("cap: wouldWin 1, won 1: ceilDiv(2, 4) = 1 <= won, so the next usable sovereign candidate takes the placement (counterexample to RL9 and RL10: the cap swaps)", "auto",
    [un("p1"), sv("PEER", "p2", terms=(20, 0, 0, 0, 0, 0))], caps=[cap("p1", "a", 1, 1)], order=[P2, P1], swapped=True,
    delta=[dict(nodeId="p1", fileSha256=SHA["a"], backend="metal", wouldWinInc=1, wonInc=0, swapped=True)])
r03("cap: wins are allowed at would-win counts 1, 5, 9: wouldWin 4, won 1 -> 5th would-win, ceilDiv(5, 4) = 2 > 1, it wins", "auto",
    [un("p1"), sv("PEER", "p2", terms=(20, 0, 0, 0, 0, 0))], caps=[cap("p1", "a", 4, 1)], order=[P1, P2], swapped=False)
r03("cap: wouldWin 2, won 1 -> 3rd, ceilDiv(3, 4) = 1 <= 1, swap", "auto", [un("p1"), sv("PEER", "p2", terms=(20, 0, 0, 0, 0, 0))], caps=[cap("p1", "a", 2, 1)], order=[P2, P1], swapped=True)
r03("cap: only when another USABLE sovereign candidate exists (the other is unusable)", "auto", [un("p1"), sv("PEER", "p2", terms=(20, 0, 0, 0, 0, 0), usable=False)], [cl("openrouter")],
    caps=[cap("p1", "a", 9, 9)], order=[P1, CO, "PEER:p2/a1a1a1a1"], swapped=False, delta=[])
r03("cap: the cap never moves work to the cloud (no other sovereign candidate)", "auto", [un("p1")], [cl("openrouter")], caps=[cap("p1", "a", 9, 9)], order=[P1, CO], swapped=False, delta=[])
r03("cap: a CORROBORATED top candidate is not capped", "auto", [sv("PEER", "p1", terms=(10, 0, 0, 0, 0, 0)), sv("PEER", "p2", terms=(20, 0, 0, 0, 0, 0))], caps=[cap("p1", "a", 9, 9)],
    order=[P1, P2], swapped=False, delta=[])
r03("cap: this device (LOCAL_MEASURED) is never capped", "auto", [sv("SELF", "self", terms=(10, 0, 0, 0, 0, 0)), sv("PEER", "p2", terms=(20, 0, 0, 0, 0, 0))], order=[S0, P2], swapped=False, delta=[])
r03("cap: a cloud entry ahead of the sovereign candidate means there is no would-win placement to cap (fastest)", "fastest",
    [un("p1", terms=(90, 0, 0, 0, 0, 0)), sv("PEER", "p2", terms=(95, 0, 0, 0, 0, 0))], [cl("openrouter", s1=10)], caps=[cap("p1", "a", 9, 9)], order=[CO, P1, P2], swapped=False, delta=[])
r03("cap: the swap takes the best OTHER usable sovereign candidate (the first usable one in order)", "auto",
    [un("p1"), sv("PEER", "p2", terms=(30, 0, 0, 0, 0, 0), usable=False), sv("PEER", "p3", terms=(40, 0, 0, 0, 0, 0))], caps=[cap("p1", "a", 1, 1)],
    order=[P3, P1, "PEER:p2/a1a1a1a1"], swapped=True)

# review fix LTQ-01: appended so that every earlier id keeps its number. The cap counters are per (peer, file): the backend of the live state does not restart them.
r03("cap: the counters of backend metal still count after the peer's live state switches to vulkan (wouldWin 3, won 1 -> 4th, ceilDiv(4, 4) = 1 <= 1, swap) (LTQ-01)", "auto",
    [un("p1", backend="vulkan"), sv("PEER", "p2", terms=(20, 0, 0, 0, 0, 0))], caps=[cap("p1", "a", 3, 1, backend="metal")], order=[P2, P1], swapped=True,
    delta=[dict(nodeId="p1", fileSha256=SHA["a"], backend="vulkan", wouldWinInc=1, wonInc=0, swapped=True)])
r03("cap: counters on two backends of one file add up (metal 2/1 and vulkan 1/0 -> wouldWin 3, won 1 -> swap) (LTQ-01)", "auto",
    [un("p1", backend="vulkan"), sv("PEER", "p2", terms=(20, 0, 0, 0, 0, 0))], caps=[cap("p1", "a", 2, 1, backend="metal"), cap("p1", "a", 1, 0, backend="vulkan")], order=[P2, P1], swapped=True,
    delta=[dict(nodeId="p1", fileSha256=SHA["a"], backend="vulkan", wouldWinInc=1, wonInc=0, swapped=True)])

write("R03-ordering.json", "R03", ["LAB_SPEC.md 6.7 (merge per policy, cap)", "LAB_SPEC.md 6.8 (RL9, RL10, RL11, RL13 exceptions)", "docs/design/mesh/router.md 5.4, 5.5"], R03)
