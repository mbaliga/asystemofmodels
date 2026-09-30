"""Writes lab/mesh-sim/scenarios/*.json. Integers only (LAB_SPEC 6.9). Every scenario carries the label 'SIMULATED — NOT DEVICE EVIDENCE'.

Truth numbers are invented lab values (they are NOT measurements of any real device); the claims a node publishes are truth x claimScalePermille / 1000.
"""
import json
import os

LABEL = "SIMULATED — NOT DEVICE EVIDENCE"
A = "a1" * 32
FILE = dict(modelId="qwen3-8b", fileSha256=A, fileBytes=5027784832, quant="Q4_K_M", rank=None, ctx=32768)


def truth(prefill, decode, ttft0, steady, onset=None):
    return {A: dict(prefillMilliTokPerSec=prefill, decodeMilliTokPerSec=decode, ttft0Ms=ttft0, steadyMilliTokPerSec=steady, throttleOnsetMs=onset)}


def node(id, tier, cls, backend, tr, scale=1000, source="ac", battery=None, design=None):
    return dict(id=id, tier=tier, **{"class": cls}, backend=backend, files=[dict(FILE)], truth=tr, claimScalePermille=scale,
                power=dict(source=source, batteryPermille=battery, designMilliWh=design), presence=[])


def link(a, b, rtt=8, kbps=200000, path="LAN", metered=False):
    return dict(a=a, b=b, path=path, rttMedianMs=rtt, rttSigmaPermille=200, kbps=kbps, metered=metered, dropsPerHour=0)


def app(pkg, policy="auto", per_hour=20, prompt=300, out=80, mesh=True, device_only=False, stream=1000, cloud_banned=False):
    return dict(pkg=pkg, meshAllowed=mesh, cloudBanned=cloud_banned, deviceOnly=device_only, policy=policy, arrivalsPerHour=per_hour, promptTokensMedian=prompt,
                outTokensMedian=out, sigmaPermille=300, streamPermille=stream, allowMeshOnMetered=False, neverCloudWhenDevicesCanAnswer=False, maxTokensCap=None)


def sc(id, seed, dur, nodes, links, apps, faults, expect, cloud=()):
    return dict(scenario=id, seed=seed, durationMs=dur, label=LABEL, catalogue="fixtures/catalogue.v1.json", nodes=nodes, links=links, cloud=list(cloud), apps=apps,
                faults=faults, expect=expect)


def fault(at, kind, node=None, phase=None, dur=None, val=None):
    return dict(atMs=at, kind=kind, node=node, phase=phase, durationMs=dur, valuePermille=val)


HOUR = 3600000


def phone():
    return node("phone", "SELF", "PHONE", "cpu", truth(30000, 5000, 200, 4000, 180000), source="battery", battery=900, design=19000)


MAC = lambda: node("mac", "PEER", "DESKTOP", "metal", truth(800000, 35000, 150, 35000))
EXP0 = dict(lawViolations=0, minPlacementPermille={}, minBatteryReductionPermille=None, maxShareOverHindsightPermille={})

out = {}
out["SC01"] = sc("SC01", 1, 72 * HOUR, [phone(), MAC()], [link("phone", "mac")], [app("app.chat", per_hour=15, out=80)], [],
                 dict(EXP0, minPlacementPermille={"mac": 950}, minBatteryReductionPermille=800))
out["SC04"] = sc("SC04", 4, 40 * 60000,
                 [phone(), node("dell", "PEER", "LAPTOP", "cpu", truth(300000, 20000, 200, 20000))],
                 [link("phone", "dell", rtt=12)], [app("app.chat", per_hour=60, out=120)],
                 [fault(15 * 60000, "peer-vanish", node="dell", phase="mid-stream", dur=15 * 60000)], EXP0)
out["SC06"] = sc("SC06", 6, 4 * HOUR,
                 [phone(), MAC(), node("liar", "PEER", "DESKTOP", "cpu", truth(300000, 20000, 200, 20000), scale=2000)],
                 [link("phone", "mac"), link("phone", "liar", rtt=10)], [app("app.chat", per_hour=90, out=60)], [],
                 dict(EXP0, maxShareOverHindsightPermille={"liar": 100}))
out["SC09"] = sc("SC09", 9, 2 * HOUR, [phone(), MAC()], [link("phone", "mac")],
                 [app("app.open", per_hour=20), app("app.private", per_hour=20, device_only=True), app("app.local", policy="local-only", per_hour=20)], [], EXP0)

# ---- test-only scenarios (src/test/resources/scenarios): one per fault kind, plus the ones the simulator laws need ----
MIN = 60000
CLOUD = [dict(provider="openrouter", ttftMedianMs=800, decodeMilliTokPerSec=50000, errorPermille={"429": 20, "5xx": 20})]


def fbase(id, seed, faults, dur=40 * MIN, apps=None):
    return sc(id, seed, dur,
              [phone(), MAC(), node("dell", "PEER", "LAPTOP", "cpu", truth(300000, 20000, 200, 20000))],
              [link("phone", "mac"), link("phone", "dell", rtt=12)],
              apps or [app("app.a", per_hour=60, out=80), dict(app("app.b", per_hour=60, prompt=500, out=100), streamPermille=500)], faults, EXP0, cloud=CLOUD)


tests = {}
FAULTS = {
    "peer-vanish": [fault(10 * MIN, "peer-vanish", node="mac", dur=3 * MIN)],
    "peer-vanish-before-head": [fault(10 * MIN, "peer-vanish", node="mac", phase="before-head", dur=3 * MIN), fault(20 * MIN, "peer-vanish", node="dell", phase="before-head", dur=3 * MIN)],
    "peer-vanish-mid-stream": [fault(10 * MIN, "peer-vanish", node="mac", phase="mid-stream", dur=3 * MIN), fault(20 * MIN, "peer-vanish", node="dell", phase="mid-stream", dur=3 * MIN)],
    "session-drop": [fault(10 * MIN, "session-drop", node="mac"), fault(20 * MIN, "session-drop", node="dell")],
    "network-change": [fault(15 * MIN, "network-change", val=3000)],
    "thermal-spike": [fault(10 * MIN, "thermal-spike", node="mac", dur=10 * MIN), fault(15 * MIN, "thermal-spike", dur=10 * MIN)],
    "charger-unplug": [fault(10 * MIN, "charger-unplug", node="mac", dur=10 * MIN), fault(12 * MIN, "charger-unplug")],
    "battery-floor": [fault(10 * MIN, "battery-floor", node="dell", val=150), fault(20 * MIN, "battery-floor", val=150)],
    "presence": [fault(10 * MIN, "presence", node="mac", dur=2 * MIN)],
    "claim-lie": [fault(5 * MIN, "claim-lie", node="dell", val=2000)],
    "claim-stale": [fault(5 * MIN, "claim-stale", node="dell")],
    "decline-storm": [fault(10 * MIN, "decline-storm", node="mac", dur=5 * MIN)],
    "state-delay": [fault(10 * MIN, "state-delay", node="mac", dur=5 * MIN, val=3000)],
    "state-drop": [fault(10 * MIN, "state-drop", node="mac", dur=5 * MIN)],
    "clock-skew": [fault(0, "clock-skew", node="mac", val=600000), fault(0, "clock-skew", node="dell", val=-600000)],
    "duplicate-attempt": [fault(10 * MIN, "duplicate-attempt"), fault(20 * MIN, "duplicate-attempt")],
    "overlay-only-high-rtt": [fault(10 * MIN, "overlay-only-high-rtt", node="mac", val=200)],
    "metered-underlay": [fault(10 * MIN, "metered-underlay", node="mac")],
    "ledger-full": [fault(15 * MIN, "ledger-full", dur=2 * MIN)],
}
for i, (k, fl) in enumerate(sorted(FAULTS.items())):
    tests["F-" + k] = fbase("F-" + k, 100 + i, fl)
tests["F-none"] = fbase("F-none", 99, [])
tests["T-RL18"] = fbase("T-RL18", 118, [fault(t * MIN, "peer-vanish", node=n, dur=2 * MIN) for t, n in ((5, "mac"), (12, "dell"), (18, "mac"), (25, "dell"))] + [fault(9 * MIN, "decline-storm", node="mac", dur=3 * MIN)])
tests["T-RL19"] = fbase("T-RL19", 119, [], dur=20 * MIN, apps=[app("app.a", per_hour=900, out=200), app("app.b", per_hour=900, out=200), app("app.c", per_hour=900, out=200)])
tests["T-RL4"] = fbase("T-RL4", 104, [], dur=30 * MIN, apps=[app("app.a", per_hour=120, out=60)])

# R6-FINDING-COLD: an HONEST lender that is used less often than its idle-unload time (300 s) is cold at every observation; the tracker's `predicted` holds no load time (LAB_SPEC 6.6).
tests["T-COLD"] = sc("T-COLD", 77, 24 * HOUR, [phone(), MAC()], [link("phone", "mac")], [app("app.chat", per_hour=6, out=80)], [], EXP0)

# T-ERR: the cloud tier fails often (429 and 5xx), so that the error path of a request (RL15b) is exercised many times; run as B0 (v1 cloud only) and as B3.
ERRCLOUD = [dict(provider="openrouter", ttftMedianMs=800, decodeMilliTokPerSec=50000, errorPermille={"429": 250, "5xx": 250})]
tests["T-ERR"] = sc("T-ERR", 55, 40 * MIN, [phone(), MAC()], [link("phone", "mac")], [app("app.a", per_hour=120, out=80)], [], EXP0, cloud=ERRCLOUD)

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
for sub, table in (("scenarios", out), (os.path.join("src", "test", "resources", "scenarios"), tests)):
    d = os.path.join(root, sub)
    os.makedirs(d, exist_ok=True)
    for k, v in table.items():
        with open(os.path.join(d, k + ".json"), "w", encoding="utf-8") as f:
            f.write(json.dumps(v, indent=2, ensure_ascii=False) + "\n")
print("ok", sorted(out), len(tests))
