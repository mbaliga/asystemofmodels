"""R01 (hard filters, plan level) for gen_vectors.py. Expected values: hand (exclusion lists, survivors, error codes), cross-checked against ref.py."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ref  # noqa: E402
from common import ok, rej, s8, vec, write  # noqa: E402
from worlds import *  # noqa: E402,F401,F403


# =============================================================================================================================================
# R01: hard filters, plan level. expect.ok = {excluded: [[node, sha8, code]...] sorted, survivors: [ids sorted]} or a reject code.
# =============================================================================================================================================
def base_peer(**kw):
    kw.setdefault("last_same", {A: NOW - 1000})
    return PEER("peer-a", **kw)


def base_world(**kw):
    peers = kw.pop("peers", None)
    return WORLD(kw.pop("self_", None), peers if peers is not None else [base_peer()], **kw)


def E(node, code, sha=A):
    return [node, s8(sha), code]


def SV(tier, node, sha=A):
    return f"{tier}:{node}/{s8(sha)}"


SELF_A = SV("SELF", "self-node")
PEER_A = SV("PEER", "peer-a")
R01 = []
_r01_n = [0]


def r01(desc, w, excluded=None, survivors=None, reject=None, status="normative"):
    _r01_n[0] += 1
    id_ = f"R01-{_r01_n[0]:03d}"
    ex, surv = ref.sovereign(w)
    ex_ref = sorted([[n, s8(sha), c] for n, sha, c in ex], key=lambda e: (e[0], e[1]))
    ids_ref = sorted(x["id"].split("/")[0] + "/" + s8(x["file"]["fileSha256"]) for x in surv)
    if reject is not None:
        assert not surv, f"{id_}: the reference found survivors {ids_ref}"
        exp = rej(reject)
        if excluded is not None:
            assert sorted(excluded, key=lambda e: (e[0], e[1])) == ex_ref, f"{id_}: hand {excluded} vs ref {ex_ref}"
        R01.append(vec(id_, desc, w, exp, status, detail={"excluded": ex_ref}))
        return
    else:
        exs = sorted(excluded or [], key=lambda e: (e[0], e[1]))
        assert exs == ex_ref, f"{id_}: hand exclusions {exs} vs ref {ex_ref}"
        sv = sorted(survivors if survivors is not None else ids_ref)
        assert sv == ids_ref, f"{id_}: hand survivors {sv} vs ref {ids_ref}"
        exp = ok({"excluded": exs, "survivors": sv})
    R01.append(vec(id_, desc, w, exp, status))


def mod(w, fn):
    w = dup(w)
    fn(w)
    return w


def peer_(w, i=0):
    return w["peers"][i]


r01("healthy baseline: this device and one own peer, no row fails", base_world(), [], [SELF_A, PEER_A])
r01("F1: the peer row denies (routeEnabled false)", mod(base_world(), lambda w: peer_(w)["peer"].update(routeEnabled=False)), [E("peer-a", "F1_ELIGIBILITY")], [SELF_A])
r01("F1: app mesh off removes O from P, so the peer is not in the universe (nothing to record)", base_world(q=Q(app=dict(APP, meshAllowed=False))), [], [SELF_A])
r01("F1: global 'use my devices' off removes O", base_world(mesh=False), [], [SELF_A])
r01("device-only app: P is {T}", base_world(q=Q(app=dict(APP, deviceOnly=True))), [], [SELF_A])
r01("local-only: P is {T}", base_world(q=Q(model="local-only")), [], [SELF_A])
r01("F2: the peer is not paired", mod(base_world(), lambda w: peer_(w)["peer"].update(paired=False)), [E("peer-a", "F2_NOT_PAIRED")], [SELF_A])
r01("F3: the peer did not grant infer", mod(base_world(), lambda w: peer_(w)["peer"].update(inferGrantedToMe=False)), [E("peer-a", "F3_NO_SCOPE")], [SELF_A])
r01("first failing row: F1 outranks F2 and F3", mod(base_world(), lambda w: peer_(w)["peer"].update(routeEnabled=False, paired=False, inferGrantedToMe=False)),
    [E("peer-a", "F1_ELIGIBILITY")], [SELF_A])
r01("first failing row: F2 outranks F3", mod(base_world(), lambda w: peer_(w)["peer"].update(paired=False, inferGrantedToMe=False)), [E("peer-a", "F2_NOT_PAIRED")], [SELF_A])
r01("F4: the file is not in the peer's held list", mod(base_world(), lambda w: peer_(w)["state"].update(held=[B])), [E("peer-a", "F4_MODEL")], [SELF_A])
r01("F4: numerics-fail on the peer's (file, backend)", mod(base_world(), lambda w: peer_(w)["priors"][0]["prior"].update(flags=["numerics-fail"])), [E("peer-a", "F4_MODEL")], [SELF_A])
r01("F4: numerics-fail on this device", mod(base_world(), lambda w: w["self"]["priors"][0]["prior"].update(flags=["numerics-fail"])), [E("self-node", "F4_MODEL")], [PEER_A])
r01("F4: a peer never heard from has no held list (conservative)", base_world(peers=[base_peer(state=None)]), [E("peer-a", "F4_MODEL")], [SELF_A])
r01("F4 outranks F8: not held and no prior", mod(base_world(peers=[base_peer(prior=None)]), lambda w: peer_(w)["state"].update(held=[B])), [E("peer-a", "F4_MODEL")], [SELF_A])
r01("F5: P + N = 800 exceeds a model context of 799 (both nodes)", mod(base_world(), lambda w: [f.update(contextTokens=799) for n in [w["self"], peer_(w)] for f in n["files"]]),
    [E("peer-a", "F5_CONTEXT"), E("self-node", "F5_CONTEXT")], [], reject="CONTEXT_OVERFLOW")
r01("F5 boundary: P + N = 800 fits a context of 800", mod(base_world(), lambda w: [f.update(contextTokens=800) for n in [w["self"], peer_(w)] for f in n["files"]]), [], [SELF_A, PEER_A])
r01("F5: the node's own maximum context (700) is below P + N", base_world(peers=[base_peer(max_ctx=700)]), [E("peer-a", "F5_CONTEXT")], [SELF_A])
r01("F5: an unknown model context cannot be shown to fit (conservative; ERRATA)", mod(base_world(), lambda w: [f.update(contextTokens=None) for f in peer_(w)["files"]]),
    [E("peer-a", "F5_CONTEXT")], [SELF_A])
NEED = 5027784832 + 100000 * 800 + 268435456
r01(f"F6: free memory {NEED - 1} is one byte below fileBytes + kv x (P + N) + 256 MiB = {NEED} (this device only; the file is not loaded)",
    mod(base_world(), lambda w: w["self"]["self"].update(loaded=[], availBytes=NEED - 1)), [E("self-node", "F6_MEMORY")], [PEER_A])
r01("F6 boundary: free memory equal to the need passes", mod(base_world(), lambda w: w["self"]["self"].update(loaded=[], availBytes=NEED)), [], [SELF_A, PEER_A])
r01("F6 is skipped for a loaded file (free memory 0)", mod(base_world(), lambda w: w["self"]["self"].update(loaded=[A], availBytes=0)), [], [SELF_A, PEER_A])
r01("F6: unknown free memory on an unloaded file (conservative)", mod(base_world(), lambda w: w["self"]["self"].update(loaded=[], availBytes=None)), [E("self-node", "F6_MEMORY")], [PEER_A])
r01("F7: the body (2,000 bytes) is over the peer's limit of 1,999", mod(base_world(), lambda w: peer_(w)["peer"]["limits"].update(maxBodyBytes=1999)), [E("peer-a", "F7_BODY")], [SELF_A])
r01("F7 boundary: a body equal to the limit passes", mod(base_world(), lambda w: peer_(w)["peer"]["limits"].update(maxBodyBytes=2000)), [], [SELF_A, PEER_A])
r01("F7: N = 300 is over the peer's maxTokens of 299", mod(base_world(), lambda w: peer_(w)["peer"]["limits"].update(maxTokens=299)), [E("peer-a", "F7_BODY")], [SELF_A])
r01("F8: no prior and no class default", base_world(peers=[base_peer(prior=None)]), [E("peer-a", "F8_CLAIM")], [SELF_A])
r01("F8: a prefill rate of 0 is unusable", base_world(peers=[base_peer(prior=PR(prefill=0))]), [E("peer-a", "F8_CLAIM")], [SELF_A])
r01("F8: the tracker marks the memory claim DISCREPANT", mod(base_world(), lambda w: w["tracker"].append(TRK("peer-a", A, "metal", TS(memory=True)))), [E("peer-a", "F8_CLAIM")], [SELF_A])
r01("F8: no link statistics for the peer (conservative; ERRATA)", base_world(peers=[base_peer(link=None)]), [E("peer-a", "F8_CLAIM")], [SELF_A])
r01("F8: a peer on battery with no known design capacity has no S2 (conservative; ERRATA R3-CLOSURE-5 (3))",
    base_world(peers=[base_peer(state=ST(src="battery", band="ge80"))]), [E("peer-a", "F8_CLAIM")], [SELF_A])
r01("a speed-DISCREPANT claim does not exclude: it is placed at its observed speed", mod(base_world(), lambda w: w["tracker"].append(TRK("peer-a", A, "metal", TS(ratios=[300] * 5)))),
    [], [SELF_A, PEER_A])
for label, fsm in (("DRAINING", "DRAINING"), ("OFF", "OFF"), ("ARMED", "ARMED")):
    r01(f"F9: the peer's availability is {label}", base_world(peers=[base_peer(state=ST(fsm=fsm))]), [E("peer-a", "F9_AVAILABILITY")], [SELF_A])
r01("F9 is skipped when EXPIRED: the offer itself is the probe", base_world(peers=[base_peer(state=ST(fsm="DRAINING"), rx=NOW - 400000)]), [], [SELF_A, PEER_A])
r01("F9 still applies to a STALE state (age 100,000)", base_world(peers=[base_peer(state=ST(fsm="DRAINING"), rx=NOW - 100000)]), [E("peer-a", "F9_AVAILABILITY")], [SELF_A])
r01("F10: peer thermal band 2", base_world(peers=[base_peer(state=ST(tb=2))]), [E("peer-a", "F10_THERMAL")], [SELF_A])
r01("F10: peer governor HOLD", base_world(peers=[base_peer(state=ST(gov="HOLD"))]), [E("peer-a", "F10_THERMAL")], [SELF_A])
r01("F10: peer governor QUEUE is not excluded", base_world(peers=[base_peer(state=ST(gov="QUEUE"))]), [], [SELF_A, PEER_A])
r01("F10 is skipped when EXPIRED", base_world(peers=[base_peer(state=ST(tb=2), rx=NOW - 400000)]), [], [SELF_A, PEER_A])
r01("F10: this device at thermal code 3", mod(base_world(), lambda w: w["self"]["self"].update(thermalCode=3)), [E("self-node", "F10_THERMAL")], [PEER_A])
r01("F10: this device at thermal code 2 is not excluded", mod(base_world(), lambda w: w["self"]["self"].update(thermalCode=2)), [], [SELF_A, PEER_A])
r01("F10: this device governor HOLD", mod(base_world(), lambda w: w["self"]["self"].update(governor="HOLD")), [E("self-node", "F10_THERMAL")], [PEER_A])
r01("F10: this device governor QUEUE is not excluded (the v2 matrix decides)", mod(base_world(), lambda w: w["self"]["self"].update(governor="QUEUE")), [], [SELF_A, PEER_A])
for band in ("20-49", "lt20"):
    r01(f"F11: on battery, not charging, band {band}", base_world(peers=[base_peer(state=ST(src="battery", band=band), design=40000)]), [E("peer-a", "F11_POWER")], [SELF_A])
r01("F11: on battery with requireCharging", base_world(peers=[base_peer(state=ST(src="battery", band="ge80"), design=40000, row=ROW(charge=True))]), [E("peer-a", "F11_POWER")], [SELF_A])
r01("F11: on battery, band ge80, no requireCharging passes", base_world(peers=[base_peer(state=ST(src="battery", band="ge80"), design=40000)]), [], [SELF_A, PEER_A])
r01("F11: on battery while charging passes", base_world(peers=[base_peer(state=ST(src="battery", chg=True, band="lt20"), design=40000)]), [], [SELF_A, PEER_A])
r01("F11: an unknown band on battery is excluded (conservative)", base_world(peers=[base_peer(state=ST(src="battery", band=None), design=40000)]), [E("peer-a", "F11_POWER")], [SELF_A])
r01("F11: a STALE 50-79 band is substituted one lower (20-49) and excluded", base_world(peers=[base_peer(state=ST(src="battery", band="50-79"), design=40000, rx=NOW - 100000)]),
    [E("peer-a", "F11_POWER")], [SELF_A])
r01("F11 is skipped when EXPIRED", base_world(peers=[base_peer(state=ST(src="battery", band="lt20"), design=40000, rx=NOW - 400000)]), [], [SELF_A, PEER_A])
r01("F13: a metered path the app does not allow", base_world(peers=[base_peer(link=LK(metered=True))]), [E("peer-a", "F13_METERED")], [SELF_A])
r01("F13: allowed when the app allows mesh on metered paths", base_world(peers=[base_peer(link=LK(metered=True))], q=Q(app=dict(APP, allowMeshOnMetered=True))), [], [SELF_A, PEER_A])
r01("F14: cooling until now + 1", base_world(peers=[base_peer(breaker=dict(coolingUntilMonoMs=NOW + 1, declineBackoffUntilMonoMs=None, halfOpen=False))]), [E("peer-a", "F14_BREAKER")], [SELF_A])
r01("F14 boundary: cooling until now is over", base_world(peers=[base_peer(breaker=dict(coolingUntilMonoMs=NOW, declineBackoffUntilMonoMs=None, halfOpen=False))]), [], [SELF_A, PEER_A])
r01("F15: decline back-off until now + 1", base_world(peers=[base_peer(breaker=dict(coolingUntilMonoMs=None, declineBackoffUntilMonoMs=NOW + 1, halfOpen=False))]), [E("peer-a", "F15_DECLINE_BACKOFF")], [SELF_A])
EMB_Q = Q(op="embeddings", model="auto", embedding=B)
emb_self = SELF(files=[F(sha=A, kind="EMBED")], situation=SS(loaded=(A,)))
emb_peer_a = PEER("peer-a", files=[F(sha=A, kind="EMBED")], last_same={A: NOW - 1000})
emb_peer_b = PEER("peer-b", files=[F(sha=B, kind="EMBED")], state=ST(held=(B,)), last_same={B: NOW - 1000})
r01("F16: an embeddings request pinned to file B excludes the other files", WORLD(emb_self, [emb_peer_a, emb_peer_b], q=EMB_Q),
    [E("self-node", "F16_EMBED_IDENTITY"), E("peer-a", "F16_EMBED_IDENTITY")], [SV("PEER", "peer-b", B)])
r01("F16: an embeddings request with no identity excludes every file (conservative; ERRATA)", WORLD(emb_self, [emb_peer_a, emb_peer_b], q=Q(op="embeddings", embedding=None)),
    [E("self-node", "F16_EMBED_IDENTITY"), E("peer-a", "F16_EMBED_IDENTITY"), E("peer-b", "F16_EMBED_IDENTITY", B)], [], reject="NO_PROVIDER_KEY")
r01("F16: the identity file passes", WORLD(emb_self, [emb_peer_a], q=Q(op="embeddings", embedding=A)), [], [SELF_A, PEER_A])
r01("first failing row: F9, F10 and F14 all fail, F9 is recorded", base_world(peers=[base_peer(state=ST(fsm="DRAINING", tb=2), breaker=dict(coolingUntilMonoMs=NOW + 5, declineBackoffUntilMonoMs=None, halfOpen=False))]),
    [E("peer-a", "F9_AVAILABILITY")], [SELF_A])
r01("first failing row: F5 outranks F7", mod(base_world(), lambda w: (peer_(w)["files"][0].update(contextTokens=700), peer_(w)["peer"]["limits"].update(maxBodyBytes=10))), [E("peer-a", "F5_CONTEXT")], [SELF_A])
r01("a virtual selector with no chat file on the peer records nothing for it", base_world(peers=[base_peer(files=[F(sha=B, kind="EMBED")], state=ST(held=(B,)))]), [], [SELF_A])
r01("a concrete model id matches files by modelId only", base_world(q=Q(model="qwen3-8b"), peers=[base_peer(files=[F(model="other-model")])]), [], [SELF_A])
r01("the auto rank floor keeps only files ranked at or above it (rank 2 kept, rank 3 and unranked dropped without a record)",
    WORLD(SELF(files=[F(rank=2)]), [base_peer(files=[F(rank=3)])], config=dict(autoRankFloor=2)), [], [SELF_A])

# errors: the plan is empty, and the most specific true cause among the existing codes is returned
no_engine = SELF(situation=SS(engine=False))
r01("error: no engine, no peers, no key: the cloud tier's own v1 code", WORLD(no_engine, []), [], [], reject="NO_PROVIDER_KEY")
r01("error: a concrete model nobody holds and the catalogue does not know: MODEL_UNKNOWN", WORLD(no_engine, [], q=Q(model="no-such-model")), [], [], reject="MODEL_UNKNOWN")
r01("error: local-only without an engine: LOCAL_ENGINE_ABSENT", WORLD(no_engine, [], q=Q(model="local-only")), [], [], reject="LOCAL_ENGINE_ABSENT")
r01("error: local-only, engine present but too hot: THERMAL_HOLD", WORLD(SELF(situation=SS(thermal=3)), [], q=Q(model="local-only")), [E("self-node", "F10_THERMAL")], [], reject="THERMAL_HOLD")
r01("error: local-only, engine present but out of memory: MODEL_OOM", WORLD(SELF(situation=SS(loaded=(), avail=1000)), [], q=Q(model="local-only")), [E("self-node", "F6_MEMORY")], [], reject="MODEL_OOM")
r01("error: local-only, the prompt does not fit: CONTEXT_OVERFLOW", WORLD(SELF(files=[F(ctx=100)]), [], q=Q(model="local-only")), [E("self-node", "F5_CONTEXT")], [], reject="CONTEXT_OVERFLOW")
r01("error: every sovereign candidate is cooling or backing off: ALL_PROVIDERS_COOLING",
    WORLD(no_engine, [PEER("peer-a", last_same={A: NOW - 1000}, breaker=dict(coolingUntilMonoMs=NOW + 5, declineBackoffUntilMonoMs=None, halfOpen=False)),
                      PEER("peer-b", last_same={A: NOW - 1000}, breaker=dict(coolingUntilMonoMs=None, declineBackoffUntilMonoMs=NOW + 5, halfOpen=False))]),
    [E("peer-a", "F14_BREAKER"), E("peer-b", "F15_DECLINE_BACKOFF")], [], reject="ALL_PROVIDERS_COOLING")
r01("error: every cloud provider is cooling (the cloud tier's v1 code)", WORLD(no_engine, [], q=Q(model="llama-3.3-70b"), cloud=CLOUD(keys=["openrouter", "groq", "trainy-ai"],
    cooling={"openrouter": WALL + 30000, "groq": WALL + 30000, "trainy-ai": WALL + 30000})), [], [], reject="ALL_PROVIDERS_COOLING")
r01("error: a device-only app with no engine: LOCAL_ENGINE_ABSENT", WORLD(no_engine, [], q=Q(app=dict(APP, deviceOnly=True))), [], [], reject="LOCAL_ENGINE_ABSENT")
r01("error: a cloud-banned app with the mesh off and no engine: LOCAL_ENGINE_ABSENT", WORLD(no_engine, [], q=Q(app=dict(APP, cloudBanned=True)), mesh=False), [], [], reject="LOCAL_ENGINE_ABSENT")
r01("error: X-Asom-Fallback for a cloud-banned app leaves no destination: NO_PROVIDER_KEY", WORLD(SELF(), [], q=Q(fallback=["openrouter"], app=dict(APP, cloudBanned=True))), [], [], reject="NO_PROVIDER_KEY")
r01("error: local-only together with X-Asom-Fallback leaves no destination (v1 pin R04-027 order): LOCAL_ENGINE_ABSENT", WORLD(SELF(), [], q=Q(model="local-only", fallback=["openrouter"])),
    [], [], reject="LOCAL_ENGINE_ABSENT")
r01("error: peers excluded by availability and the cloud has no key: the cloud tier's NO_PROVIDER_KEY", WORLD(no_engine, [PEER("peer-a", state=ST(fsm="DRAINING"))]), [E("peer-a", "F9_AVAILABILITY")], [], reject="NO_PROVIDER_KEY")
r01("error: a concrete model that only a peer holds, for an app the mesh is off for, no engine, cloud banned: LOCAL_ENGINE_ABSENT",
    WORLD(no_engine, [base_peer()], q=Q(model="qwen3-8b", app=dict(APP, meshAllowed=False, cloudBanned=True))), [], [], reject="LOCAL_ENGINE_ABSENT")
r01("error: the engine is present but does not hold the concrete model and the cloud is banned: MODEL_UNKNOWN", WORLD(SELF(files=[F(model="other")]), [], q=Q(model="qwen3-8b", app=dict(APP, cloudBanned=True))),
    [], [], reject="MODEL_UNKNOWN")

# review fixes (LTQ-01, LTQ-06, LTQ-15): appended so that every earlier id keeps its number
def two_backend_peer(state_backend, **kw):
    pr = PR()
    p = base_peer(state=ST(backend=state_backend), **kw)
    p["priors"] = [dict(nodeId="peer-a", fileSha256=A, backend=b, prior=dup(pr)) for b in ("metal", "vulkan")]
    return p


MEM = TRK("peer-a", A, "metal", TS(memory=True))
r01("F8: a terminal=oom memory mark on backend metal excludes the file (control: the live state reports metal)", base_world(peers=[two_backend_peer("metal")], tracker=[MEM]),
    [E("peer-a", "F8_CLAIM")], [SELF_A])
r01("F8: the same memory mark after the peer's live state switches to backend vulkan (the tracker key is (peer, file); the backend only picks the claim row, LTQ-01)",
    base_world(peers=[two_backend_peer("vulkan")], tracker=[MEM]), [E("peer-a", "F8_CLAIM")], [SELF_A])
r01("F8: a claim whose decode curve descends in context is excluded with F8, not an error (LTQ-15)", base_world(peers=[base_peer(prior=PR(curve=[[1024, 10000], [512, 12000]]))]),
    [E("peer-a", "F8_CLAIM")], [SELF_A])
r01("F8: a claim whose decode curve repeats a context is excluded with F8 (LTQ-15)", base_world(peers=[base_peer(prior=PR(curve=[[512, 10000], [512, 12000]]))]),
    [E("peer-a", "F8_CLAIM")], [SELF_A])
r01("F8: a claim whose decode curve has five points is excluded with F8 (LTQ-15)", base_world(peers=[base_peer(prior=PR(curve=[[256 * i, 10000] for i in range(1, 6)]))]),
    [E("peer-a", "F8_CLAIM")], [SELF_A])
r01("F8: a four-point ascending decode curve is accepted", base_world(peers=[base_peer(prior=PR(curve=[[256, 14000], [512, 12000], [1024, 10000], [4096, 8000]]))]), [], [SELF_A, PEER_A])
r01("F11: a fresh digest does not refresh the power fields: a STALE full state with band 50-79 reads 20-49 and is excluded (LTQ-06)",
    base_world(peers=[base_peer(state=ST(src="battery", band="50-79"), design=40000, power_freshness="STALE")]), [E("peer-a", "F11_POWER")], [SELF_A])
r01("F11 is skipped when the power fields are EXPIRED although the digest fields are fresh: the offer is the probe (LTQ-06)",
    base_world(peers=[base_peer(state=ST(src="battery", band="lt20"), design=40000, power_freshness="EXPIRED")]), [], [SELF_A, PEER_A])
r01("F9 still reads the digest fields when only the power fields are EXPIRED (LTQ-06)", base_world(peers=[base_peer(state=ST(fsm="DRAINING"), power_freshness="EXPIRED")]),
    [E("peer-a", "F9_AVAILABILITY")], [SELF_A])
r01("a power freshness better than the digest's is ignored: the digest is STALE, so the 50-79 band still reads one lower (LTQ-06)",
    base_world(peers=[base_peer(state=ST(src="battery", band="50-79"), design=40000, rx=NOW - 100000, power_freshness="FRESH")]), [E("peer-a", "F11_POWER")], [SELF_A])

write("R01-hard-filter.json", "R01", ["LAB_SPEC.md 6.3", "LAB_SPEC.md 6.2", "LAB_SPEC.md 6.7 (errors)", "docs/design/mesh/REVIEW_ROUND3.md R3-CLOSURE-5"], R01)
