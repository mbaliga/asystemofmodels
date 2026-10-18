"""R06 (reducers: integer EWMA, link and app EWMAs, cap counters, freshness classes, the pure breaker) for gen_vectors.py.

hand: EWMA and link arithmetic, the freshness table, the cap pattern (wins at would-win counts 1, 5, 9, ...).
pin:  the breaker vectors write out the v1 CooldownRegistry curve (30 s doubling to 15 min, the streak surviving the deadline, a success resetting it); the Kotlin
      law test `PureBreakerPinTest` compares the pure breaker with the REAL CooldownRegistry over random event sequences, so a divergence from v1 fails there."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import ok, rej, vec, write  # noqa: E402

R06 = []
_n = [0]


def r06(desc, inp, expect):
    _n[0] += 1
    R06.append(vec(f"R06-{_n[0]:03d}", desc, inp, expect))


def ewma(samples):
    out, e = [], None
    for x in samples:
        e = x if e is None else (7 * e + x) // 8
        out.append(e)
    return out


for name, samples in (
    ("a single sample is the value", [100]),
    ("(7e + x) / 8 with floor division", [100, 200, 200, 200]),
    ("a falling series", [1000, 0, 0, 0, 0]),
    ("floor keeps small samples from moving a small value", [7, 8, 8, 8]),
):
    r06(f"EWMA: {name}", dict(kind="ewma", samples=samples), ok(dict(values=ewma(samples))))
assert ewma([100, 200, 200, 200]) == [100, 112, 123, 132]


def link(start, events):
    rtt, kbps, n = start["rttMs"], start["kbps"], start["samples"]
    for e in events:
        if e["kind"] == "rtt":
            rtt = e["ms"] if n == 0 else (7 * rtt + e["ms"]) // 8
            n += 1
        else:
            if e["bytes"] >= 262144 and e["ms"] > 0:
                x = e["bytes"] * 8 // e["ms"]
                kbps = x if kbps <= 0 else (7 * kbps + x) // 8
    return dict(rttMs=rtt, kbps=kbps, samples=n)


L0 = dict(rttMs=0, kbps=0, samples=0)
for desc, start, events in (
    ("the first RTT sample is the value, the next follow the EWMA", L0, [dict(kind="rtt", ms=10), dict(kind="rtt", ms=18)]),
    ("a transfer under 256 KiB says nothing about bandwidth", dict(rttMs=10, kbps=0, samples=1), [dict(kind="transfer", bytes=262143, ms=100)]),
    ("a transfer of exactly 256 KiB counts: bits per millisecond are kbit/s", dict(rttMs=10, kbps=0, samples=1), [dict(kind="transfer", bytes=262144, ms=100)]),
    ("the second bandwidth sample is smoothed", dict(rttMs=10, kbps=20971, samples=1), [dict(kind="transfer", bytes=1048576, ms=50)]),
    ("a transfer with no elapsed time is ignored", dict(rttMs=10, kbps=500, samples=1), [dict(kind="transfer", bytes=1048576, ms=0)]),
):
    r06(f"link: {desc}", dict(kind="link", start=start, events=events), ok(link(start, events)))
assert link(L0, [dict(kind="rtt", ms=10), dict(kind="rtt", ms=18)])["rttMs"] == 11
assert link(dict(rttMs=10, kbps=0, samples=1), [dict(kind="transfer", bytes=262144, ms=100)])["kbps"] == 20971


def app_ewma(start, events):
    m = dict(start)
    for e in events:
        x = max(1, e["tokens"])
        m[e["app"]] = x if e["app"] not in m else (7 * m[e["app"]] + x) // 8
    return m


for desc, start, events in (
    ("the first completion of an app is the value", {}, [dict(app="app.a", tokens=300)]),
    ("later completions are smoothed", {"app.a": 300}, [dict(app="app.a", tokens=100), dict(app="app.a", tokens=100)]),
    ("a zero-token completion counts as 1 and apps are independent", {"app.a": 300}, [dict(app="app.b", tokens=0)]),
):
    r06(f"per-app output length: {desc}", dict(kind="appEwma", start=start, events=events), ok(dict(values=app_ewma(start, events))))
assert app_ewma({"app.a": 300}, [dict(app="app.a", tokens=100), dict(app="app.a", tokens=100)])["app.a"] == 253


def cap_end(start, deltas):
    ww, won = start["wouldWin"], start["won"]
    for d in deltas:
        ww += d["wouldWinInc"]
        won += d["wonInc"]
    return dict(wouldWin=ww, won=won)


r06("cap counters: a committed delta adds to both counters", dict(kind="cap", start=dict(wouldWin=3, won=1), deltas=[dict(wouldWinInc=1, wonInc=1), dict(wouldWinInc=1, wonInc=0)]),
    ok(cap_end(dict(wouldWin=3, won=1), [dict(wouldWinInc=1, wonInc=1), dict(wouldWinInc=1, wonInc=0)])))
r06("cap counters: nothing is committed for a plan that did not execute", dict(kind="cap", start=dict(wouldWin=3, won=1), deltas=[]), ok(dict(wouldWin=3, won=1)))
r06("cap run: an UNVERIFIED key wins at would-win counts 1, 5 and 9 of 12 (ceilDiv(12, 4) = 3 wins)", dict(kind="capRun", k=12),
    ok(dict(wins=[True, False, False, False, True, False, False, False, True, False, False, False], wouldWin=12, won=3)))
r06("cap run: 4 would-win plans: exactly 1 win", dict(kind="capRun", k=4), ok(dict(wins=[True, False, False, False], wouldWin=4, won=1)))
r06("cap run: 5 would-win plans: 2 wins (ceilDiv(5, 4))", dict(kind="capRun", k=5), ok(dict(wins=[True, False, False, False, True], wouldWin=5, won=2)))

# freshness classes on the requester's monotonic clock (LAB_SPEC 6.5)
S = lambda seq, age, rx: dict(kind="state", seq=seq, sampledAgeMs=age, rxMonoMs=rx)
OPEN = dict(kind="session")
for desc, events, now, cls, regressed in (
    ("age 5,000 is FRESH", [OPEN, S(5, 0, 1000)], 6000, "FRESH", False),
    ("age 5,001 is WARM", [OPEN, S(5, 0, 1000)], 6001, "WARM", False),
    ("age 30,000 is WARM", [OPEN, S(5, 0, 1000)], 31000, "WARM", False),
    ("age 30,001 is STALE", [OPEN, S(5, 0, 1000)], 31001, "STALE", False),
    ("age 300,000 is STALE", [OPEN, S(5, 0, 1000)], 301000, "STALE", False),
    ("age 300,001 is EXPIRED", [OPEN, S(5, 0, 1000)], 301001, "EXPIRED", False),
    ("sampledAgeMs adds to the age: 4,000 + 2,000 = 6,000 is WARM", [OPEN, S(5, 2000, 1000)], 5000, "WARM", False),
    ("sampledAgeMs is capped at 60,000: a claimed age of 999,999 counts 60,000 (STALE)", [OPEN, S(5, 999999, 1000)], 1000, "STALE", False),
    ("a seq that goes backwards is EXPIRED", [OPEN, S(9, 0, 1000), S(4, 0, 2000)], 2000, "EXPIRED", True),
    ("an equal seq is a repeat: ignored, the state does not get fresher", [OPEN, S(5, 0, 1000), S(5, 0, 5000)], 6001, "WARM", False),
    ("a higher seq refreshes", [OPEN, S(5, 0, 1000), S(6, 0, 5000)], 6001, "FRESH", False),
    ("a GOAWAY since the state makes it EXPIRED", [OPEN, S(5, 0, 1000), dict(kind="goaway")], 1500, "EXPIRED", False),
    ("a state after the GOAWAY clears it", [OPEN, S(5, 0, 1000), dict(kind="goaway"), S(6, 0, 1200)], 1500, "FRESH", False),
    ("a closed session with a state older than 30,000 is EXPIRED", [OPEN, S(5, 0, 1000), dict(kind="sessionClose")], 31001, "EXPIRED", False),
    ("a closed session with a state of 30,000 or less keeps its class", [OPEN, S(5, 0, 1000), dict(kind="sessionClose")], 31000, "WARM", False),
    ("seq is per session: a new session forgets the highest seq", [OPEN, S(100, 0, 1000), OPEN, S(1, 0, 1500)], 2000, "FRESH", False),
    ("no state at all is EXPIRED", [OPEN], 1000, "EXPIRED", False),
    ("a piggybacked digest refreshes the fast fields and the receive time", [OPEN, S(5, 0, 1000), dict(kind="piggyback", seq=6, rxMonoMs=9000, tb=2, qb=2, gov="QUEUE", fsm="DRAINING")], 10000, "FRESH", False),
    ("a digest with no base state is ignored", [OPEN, dict(kind="piggyback", seq=1, rxMonoMs=9000, tb=0, qb=0, gov="RUN", fsm="SERVING")], 10000, "EXPIRED", False),
):
    hs = max([e.get("seq", 0) for e in events if e["kind"] in ("state", "piggyback")] or [0])
    r06(f"freshness: {desc}", dict(kind="freshness", events=events, now=now), ok(dict(cls=cls, regressed=regressed)))

r06("freshness: the fast fields after a piggyback are the digest's", dict(kind="freshness", events=[OPEN, S(5, 0, 1000), dict(kind="piggyback", seq=6, rxMonoMs=9000, tb=2, qb=2, gov="QUEUE", fsm="DRAINING")], now=10000, fields=True),
    ok(dict(cls="FRESH", regressed=False, fsm="DRAINING", thermalBand=2, queueBucket=2, governor="QUEUE", seq=6)))

# st digest parsing
GOOD = '{"seq":6,"fsm":"SERVING","tb":1,"gov":"RUN","qb":2}'
r06("st digest: a well-formed digest", dict(kind="stParse", text=GOOD), ok(dict(seq=6, fsm="SERVING", tb=1, gov="RUN", qb=2)))
r06("st digest: unknown members are ignored", dict(kind="stParse", text='{"seq":6,"fsm":"SERVING","tb":1,"gov":"RUN","qb":2,"usage":{"x":1}}'), ok(dict(seq=6, fsm="SERVING", tb=1, gov="RUN", qb=2)))
r06("st digest: a band outside 0..2 is refused", dict(kind="stParse", text='{"seq":6,"fsm":"SERVING","tb":3,"gov":"RUN","qb":2}'), rej("REFUSED"))
r06("st digest: an unknown fsm is refused", dict(kind="stParse", text='{"seq":6,"fsm":"BUSY","tb":1,"gov":"RUN","qb":2}'), rej("REFUSED"))
r06("st digest: a missing member is refused", dict(kind="stParse", text='{"seq":6,"fsm":"SERVING","tb":1,"gov":"RUN"}'), rej("REFUSED"))
r06("st digest: seq 0 is refused", dict(kind="stParse", text='{"seq":0,"fsm":"SERVING","tb":1,"gov":"RUN","qb":2}'), rej("REFUSED"))
r06("st digest: a fraction is refused (integers only)", dict(kind="stParse", text='{"seq":6,"fsm":"SERVING","tb":1.0,"gov":"RUN","qb":2}'), rej("REFUSED"))

# decline back-off
for now, retry, until in ((1000, 12000, 13000), (1000, 100, 6000), (1000, 9999999, 601000)):
    r06(f"decline back-off: retryAfterMs {retry} at {now} clamps to 5,000..600,000", dict(kind="declineBackoff", now=now, retryAfterMs=retry), ok(dict(until=until)))


# the pure breaker: the v1 CooldownRegistry curve
def breaker(curve, events, probes):
    base, cap = (30000, 900000) if curve == "provider" else (30000, 30000)
    fails, until = 0, 0
    for e in events:
        if e["kind"] == "fail":
            fails += 1
            backoff = cap if fails - 1 >= 30 else min(cap, base << (fails - 1))
            until = e["at"] + backoff
        else:
            fails, until = 0, 0
    return dict(failures=fails, coolingUntil=until, probes=[dict(at=t, cooling=until > t, halfOpen=(fails > 0 and until <= t)) for t in probes])


F_ = lambda at: dict(kind="fail", at=at)
SUC = dict(kind="success", at=0)
for desc, curve, events, probes in (
    ("one failure cools for 30 s", "provider", [F_(0)], [0, 29999, 30000]),
    ("a second failure after the deadline cools for 60 s (the streak survives the deadline)", "provider", [F_(0), F_(40000)], [99999, 100000]),
    ("failures double to the 15-minute cap: 30, 60, 120, 240, 480, 900, 900 s", "provider", [F_(0), F_(0), F_(0), F_(0), F_(0), F_(0), F_(0)], [899999, 900000]),
    ("a success closes the breaker and resets the streak", "provider", [F_(0), F_(0), SUC, F_(1000)], [30999, 31000]),
    ("the peer transport curve stays at 30 s however many failures", "peer", [F_(0), F_(0), F_(0), F_(0)], [29999, 30000]),
    ("half-open: after the deadline a failed peer is back for an offer-only probe", "peer", [F_(0)], [29999, 30000, 60000]),
    ("no failure, no breaker", "provider", [], [0]),
):
    r06(f"breaker (pinned to CooldownRegistry): {desc}", dict(kind="breaker", curve=curve, events=events, probes=probes), ok(breaker(curve, events, probes)))
assert breaker("provider", [F_(0)] * 7, [900000])["coolingUntil"] == 900000

# review fixes (LTQ-02, LTQ-06, LTQ-13): appended so that every earlier id keeps its number
PB = lambda seq, rx, fsm="SERVING": dict(kind="piggyback", seq=seq, rxMonoMs=rx, tb=0, qb=0, gov="RUN", fsm=fsm)
CLOSE = dict(kind="sessionClose")
GO = dict(kind="goaway")
for desc, events, now, cls, regressed in (
    ("a GOAWAY survives the re-dial: with no new state the pre-GOAWAY state is still EXPIRED (LTQ-02)", [OPEN, S(5, 0, 1000), GO, CLOSE, OPEN], 2000, "EXPIRED", False),
    ("a full state after the re-dial clears the GOAWAY (LTQ-02)", [OPEN, S(5, 0, 1000), GO, CLOSE, OPEN, S(1, 0, 1500)], 1600, "FRESH", False),
    ("a digest after the re-dial clears the GOAWAY for the digest fields (LTQ-02)", [OPEN, S(5, 0, 1000), GO, CLOSE, OPEN, PB(1, 1500)], 1600, "FRESH", False),
    ("a regressed seq survives the re-dial until a state arrives (LTQ-02)", [OPEN, S(9, 0, 1000), S(4, 0, 2000), CLOSE, OPEN], 2100, "EXPIRED", True),
    ("a digest whose seq is below the highest seen marks the state regressed and EXPIRED (LTQ-13)", [OPEN, S(9, 0, 1000), PB(4, 1100)], 1200, "EXPIRED", True),
    ("a digest at the highest seq and then above it clears the regression (LTQ-13)", [OPEN, S(9, 0, 1000), PB(4, 1100), PB(10, 1300)], 1400, "FRESH", False),
):
    r06(f"freshness: {desc}", dict(kind="freshness", events=events, now=now), ok(dict(cls=cls, regressed=regressed)))


def power(desc, events, now, cls, power_cls):
    r06(f"freshness of the power fields: {desc}", dict(kind="freshness", events=events, now=now, power=True), ok(dict(cls=cls, regressed=False, powerCls=power_cls)))


power("with no digest the power fields age like the state", [OPEN, S(5, 0, 1000)], 6001, "WARM", "WARM")
power("a digest refreshes the digest fields only: ten minutes after the full state the power fields are EXPIRED (LTQ-06)", [OPEN, S(5, 0, 0), PB(6, 600000)], 600100, "FRESH", "EXPIRED")
power("100 s after the full state the power fields are STALE under a fresh digest", [OPEN, S(5, 0, 0), PB(6, 100000)], 100100, "FRESH", "STALE")
power("10 s after the full state the power fields are WARM under a fresh digest", [OPEN, S(5, 0, 0), PB(6, 10000)], 10100, "FRESH", "WARM")
power("the sampled age of the full state counts for the power fields (40,000 + 1,000 is STALE)", [OPEN, S(5, 40000, 0), PB(6, 1000)], 1000, "FRESH", "STALE")
power("a full state at the seq of a known digest refreshes the power fields and leaves the digest fields alone", [OPEN, S(5, 0, 0), PB(6, 100000), S(6, 0, 100200)], 100300, "FRESH", "FRESH")
power("a GOAWAY, a re-dial and a digest: the digest fields are fresh, the power fields still date from before the GOAWAY (LTQ-02, LTQ-06)", [OPEN, S(5, 0, 1000), GO, CLOSE, OPEN, PB(1, 1500)], 1600, "FRESH", "EXPIRED")
power("a full state after the GOAWAY and the re-dial refreshes both", [OPEN, S(5, 0, 1000), GO, CLOSE, OPEN, S(1, 0, 1500)], 1600, "FRESH", "FRESH")

write("R06-reducers.json", "R06", ["LAB_SPEC.md 6.5", "LAB_SPEC.md 6.7 (cap)", "docs/design/mesh/router.md 3.3, 3.5, 5.5, 8", "core/routing CooldownRegistry (frozen v1)"], R06)
