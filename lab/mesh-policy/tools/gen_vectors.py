#!/usr/bin/env python3
"""Writes the hand vectors of the lab-ledger-policy track: lab/conformance/ledger/L01, L02 and lab/conformance/policy/W07, W07p.

Every EXPECTED value below is typed by hand from LAB_SPEC 6.2, 6.3, 6.5, 7.2, 7.5-7.7 and design 8.4-8.6 (arithmetic such as `9 + payload` is written
out). Nothing here calls the Kotlin code, so a vector that disagrees with the implementation is a real disagreement to be investigated, never a recording.
Python 3 standard library only. Run from anywhere; it rewrites the four files, then run `python3 lab/tools/regen_index.py`.
"""
import json
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "conformance")
CONF = "0.2.0"


def vec(id_, desc, inp, expect, status="normative"):
    return {"id": id_, "origin": "hand", "status": status, "oracle": "self", "description": desc, "input": inp, "expect": expect}


def ok(v):
    return {"ok": v}


def rej(code):
    return {"reject": code}


def write(sub, name, family, refs, vectors):
    path = os.path.join(ROOT, sub, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    doc = {"family": family, "confVersion": CONF, "specRefs": refs, "vectors": vectors}
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"{sub}/{name}: {len(vectors)} vectors")


def jcs(o):
    return json.dumps(o, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


# ----------------------------------------------------------------------------------------------------------------------------------------------
# L01: destination sets, eligibility, quiescence
# ----------------------------------------------------------------------------------------------------------------------------------------------
def ds(g, m, ban, dev, loc, fb, nt=False):
    return {"kind": "destinationSet", "meshGlobal": g, "appMesh": m, "cloudBan": ban, "deviceOnly": dev, "localOnly": loc, "fallback": fb, "noTrain": nt}


def dsx(p, cls, restricted=None):
    return ok({"P": p, "class": cls, "cloudRestrictedTo": restricted})


L01 = [
    vec("L01-001", "contract 5.2 row 1: mesh global off (the default), auto: T and C", ds(False, True, False, False, False, None), dsx(["T", "C"], "cloud-no-peers")),
    vec("L01-002", "contract 5.2 row 2: on, app mesh own, no cloud ban, auto: T, O and C", ds(True, True, False, False, False, None), dsx(["T", "O", "C"], "own-and-cloud")),
    vec("L01-003", "contract 5.2 row 3: on, own, cloud ban: T and O", ds(True, True, True, False, False, None), dsx(["T", "O"], "own-devices")),
    vec("L01-004", "contract 5.2 row 4: on, app mesh off: T and C", ds(True, False, False, False, False, None), dsx(["T", "C"], "cloud-no-peers")),
    vec("L01-005", "contract 5.2 row 5: on, own, local-only: T", ds(True, True, False, False, True, None), dsx(["T"], "device-only")),
    vec("L01-006", "contract 5.2 row 7: on, own, auto plus X-Asom-Fallback: C restricted to the list", ds(True, True, False, False, False, ["openrouter", "groq"]), dsx(["C"], None, ["openrouter", "groq"])),
    vec("L01-007", "an app marked device-only: T", ds(True, True, False, True, False, None), dsx(["T"], "device-only")),
    vec("L01-008", "X-Asom-No-Train has no effect on P (it filters inside C exactly as v1)", ds(True, True, False, False, False, None, True), dsx(["T", "O", "C"], "own-and-cloud")),
    vec("L01-009", "cloud ban plus fallback: the fallback needs C and the app bans it: empty", ds(True, True, True, False, False, ["groq"]), dsx([], None)),
    vec("L01-010", "local-only plus fallback: nothing in common: empty", ds(True, True, False, False, True, ["groq"]), dsx([], None)),
    vec("L01-011", "ERR-LP-1: a fallback header naming no provider leaves no destination", ds(True, True, False, False, False, []), dsx([], None)),
    vec("L01-012", "mesh off everywhere plus fallback: C restricted to the list", ds(False, False, False, False, False, ["groq"]), dsx(["C"], None, ["groq"])),
    vec("L01-013", "mesh global off plus local-only: T", ds(False, True, False, False, True, None), dsx(["T"], "device-only")),
    vec("L01-014", "cloud ban plus device-only: T", ds(True, True, True, True, False, None), dsx(["T"], "device-only")),
    vec("L01-015", "app mesh off plus cloud ban: T only", ds(True, False, True, False, False, None), dsx(["T"], "device-only")),
    vec("L01-016", "global off, app off, cloud ban: T only", ds(False, False, True, False, False, None), dsx(["T"], "device-only")),
    vec("L01-017", "every restriction at once: T", ds(False, False, True, True, True, None), dsx(["T"], "device-only")),
    vec("L01-018", "device-only plus fallback: empty", ds(True, True, False, True, False, ["groq"]), dsx([], None)),
    vec("L01-019", "own-devices request that is also local-only and cloud-banned: T (the most restrictive rule wins)", ds(True, True, True, False, True, None), dsx(["T"], "device-only")),
    vec("L01-020", "fallback with no-train: still C restricted to the list", ds(True, True, False, False, False, ["groq"], True), dsx(["C"], None, ["groq"])),
    vec("L01-021", "global off plus cloud ban: T only", ds(False, True, True, False, False, None), dsx(["T"], "device-only")),
]


def dflt(paired, banned, ticked):
    return {"kind": "appDefault", "pairedBeforeSwitch": paired, "cloudBanned": banned, "userTicked": ticked}


L01 += [
    vec("L01-100", "r3 default (design 8.5): an existing app appears with an unticked box: mesh off", dflt(True, False, None), ok({"meshAllowed": False})),
    vec("L01-101", "r3 default: an app paired after the switch defaults to off", dflt(False, False, None), ok({"meshAllowed": False})),
    vec("L01-102", "r3 default: a cloud-banned app stays off", dflt(True, True, None), ok({"meshAllowed": False})),
    vec("L01-103", "a cloud-banned app the user ticked individually: on", dflt(True, True, True), ok({"meshAllowed": True})),
    vec("L01-104", "an app the user explicitly left unticked: off", dflt(True, False, False), ok({"meshAllowed": False})),
]


def elig(p, status, route, infer):
    return {"kind": "eligibility", "P": p, "status": status, "routeEnabled": route, "inferGranted": infer}


L01 += [
    vec("L01-200", "F1..F3 all pass: eligible", elig(["T", "O", "C"], "PAIRED", True, True), ok({"eligible": True})),
    vec("L01-201", "O not in P: F1_ELIGIBILITY", elig(["T", "C"], "PAIRED", True, True), rej("F1_ELIGIBILITY")),
    vec("L01-202", "the peer row denies routing: F1_ELIGIBILITY", elig(["T", "O", "C"], "PAIRED", False, True), rej("F1_ELIGIBILITY")),
    vec("L01-203", "SUSPENDED is not PAIRED: F2_NOT_PAIRED", elig(["T", "O", "C"], "SUSPENDED", True, True), rej("F2_NOT_PAIRED")),
    vec("L01-204", "REVOKED is not PAIRED: F2_NOT_PAIRED", elig(["T", "O", "C"], "REVOKED", True, True), rej("F2_NOT_PAIRED")),
    vec("L01-205", "the peer did not grant infer: F3_NO_SCOPE", elig(["T", "O", "C"], "PAIRED", True, False), rej("F3_NO_SCOPE")),
    vec("L01-206", "F1 outranks F2 and F3 (the first failing row is recorded)", elig(["T"], "REVOKED", False, False), rej("F1_ELIGIBILITY")),
    vec("L01-207", "F2 outranks F3", elig(["T", "O", "C"], "REVOKED", True, False), rej("F2_NOT_PAIRED")),
]


def q(role, pending, screen, userop, finishing):
    return {"kind": "quiescence", "role": role, "pending": pending, "screen": screen, "userOp": userop, "finishing": finishing}


def may(b):
    return ok({"mayInitiate": b})


L01 += [
    vec("L01-300", "requester, none of the four conditions: initiates nothing (a phone with mesh on and no apps sends nothing)", q("REQUESTER", False, False, False, False), may(False)),
    vec("L01-301", "requester, a local request whose P contains O is pending: may initiate", q("REQUESTER", True, False, False, False), may(True)),
    vec("L01-302", "requester, a peer-status screen is open: may initiate", q("REQUESTER", False, True, False, False), may(True)),
    vec("L01-303", "requester, the user started a peer operation: may initiate", q("REQUESTER", False, False, True, False), may(True)),
    vec("L01-304", "requester, an in-flight attempt is finishing: may initiate", q("REQUESTER", False, False, False, True), may(True)),
    vec("L01-305", "lender, a pending local request alone: initiates none", q("LENDER", True, False, False, False), may(False)),
    vec("L01-306", "lender, a peer-status screen alone: initiates none", q("LENDER", False, True, False, False), may(False)),
    vec("L01-307", "lender, an attempt finishing alone: initiates none", q("LENDER", False, False, False, True), may(False)),
    vec("L01-308", "lender under rule 3 (user revoking a peer, sharing a report): may initiate", q("LENDER", False, False, True, False), may(True)),
    vec("L01-309", "lender with rules 1, 2 and 4 but not 3: still none", q("LENDER", True, True, False, True), may(False)),
    vec("L01-310", "requester with all four: may initiate", q("REQUESTER", True, True, True, True), may(True)),
]


def inb(fsm, window, tab):
    return {"kind": "inbound", "fsm": fsm, "pairingWindow": window, "peersTab": tab}


def acc(b):
    return ok({"mayAccept": b})


L01 += [
    vec("L01-320", "a lender accepts inbound while SERVING", inb("SERVING", False, False), acc(True)),
    vec("L01-321", "ARMED with nothing open: no inbound", inb("ARMED", False, False), acc(False)),
    vec("L01-322", "ARMED with a pairing window open: accepts", inb("ARMED", True, False), acc(True)),
    vec("L01-323", "DRAINING with the Peers tab open: accepts", inb("DRAINING", False, True), acc(True)),
    vec("L01-324", "OFF: no listener", inb("OFF", False, False), acc(False)),
    vec("L01-325", "DRAINING with nothing open: the listener is closed", inb("DRAINING", False, False), acc(False)),
]


def sc(idle, age, streams):
    return {"kind": "sessionClose", "idleMs": idle, "ageMs": age, "openStreams": streams}


def clo(b):
    return ok({"close": b})


L01 += [
    vec("L01-340", "idle 299,999 ms with no open stream: stays open", sc(299_999, 10, 0), clo(False)),
    vec("L01-341", "idle 300,000 ms with no open stream: closes (5 minutes)", sc(300_000, 10, 0), clo(True)),
    vec("L01-342", "an open stream keeps the session past 5 idle minutes", sc(400_000, 10, 1), clo(False)),
    vec("L01-343", "age 1,800,000 ms closes even with a stream (30 minutes)", sc(0, 1_800_000, 1), clo(True)),
    vec("L01-344", "age 1,799,999 ms with a stream stays open", sc(0, 1_799_999, 1), clo(False)),
]

# ----------------------------------------------------------------------------------------------------------------------------------------------
# W07: live state
# ----------------------------------------------------------------------------------------------------------------------------------------------
HEX_A = "a" * 64
HEX_B = "b" * 64
DIG = "A" * 43
NO_PRESENCE = {"screenInteractive": False, "inputIdleMs": 0, "keyguardDismissed": False, "foregroundApp": None, "heavyForegroundProcess": False, "consoleUser": None, "loginState": None, "otherProcessContentionPermille": 0}
BUSY_PRESENCE = {
    "screenInteractive": True, "inputIdleMs": 4_242_424_242, "keyguardDismissed": True, "foregroundApp": "com.sentinel.game", "heavyForegroundProcess": True,
    "consoleUser": "sentinel-user-4711", "loginState": "locked", "otherProcessContentionPermille": 987,
}


def view(**kw):
    v = {
        "fsm": "SERVING", "powerSource": "ac", "charging": False, "batteryBand": None, "batteryPercentExact": 37, "thermalBand": 0, "governor": "RUN", "backend": "vulkan",
        "commit": "4f1c2ab", "confVersion": "1.0.0", "held": [HEX_A], "localQueued": 0, "peerQueued": 0, "loadedModels": ["qwen3-8b-q4"], "freeMemoryBytes": 21474836480,
        "manifestSeq": 17, "manifestDigest": DIG, "presence": BUSY_PRESENCE,
    }
    v.update(kw)
    return v


EXAMPLE = {
    "availability": {"fsm": "SERVING"},
    "engine": {"backend": "vulkan", "commit": "4f1c2ab", "confVersion": "1.0.0", "held": [HEX_A]},
    "manifest": {"bodyDigest": DIG, "seq": 17},
    "power": {"batteryBand": None, "charging": False, "source": "ac"},
    "queue": {"bucket": 0},
    "sampledAgeMs": 800,
    "seq": 4711,
    "thermal": {"band": 0, "governor": "RUN"},
    "v": 1,
}
EXAMPLE_TEXT = jcs(EXAMPLE)


def build(v, seq, age):
    return {"kind": "stateBuild", "view": v, "seq": seq, "sampledAgeMs": age}


def built(text, st):
    return ok({"jcs": text, "st": st})


W07 = []
W07.append(vec("W07-001", "LAB_SPEC 7.2's asom.state/1 example, built from a lender view that carries busy presence inputs: none of them is on the wire", build(view(), 4711, 800), built(EXAMPLE_TEXT, '{"fsm":"SERVING","gov":"RUN","qb":0,"seq":4711,"tb":0}')))
W07.append(vec("W07-002", "the same view with every presence input different: byte-identical STATE and st (LP-1)", build(view(presence=NO_PRESENCE), 4711, 800), built(EXAMPLE_TEXT, '{"fsm":"SERVING","gov":"RUN","qb":0,"seq":4711,"tb":0}')))
W07.append(vec(
    "W07-003", "a battery lender that is draining: band 20-49, queue bucket 1, governor QUEUE, no manifest",
    build(view(fsm="DRAINING", powerSource="battery", batteryBand="20-49", thermalBand=1, governor="QUEUE", localQueued=1, manifestSeq=None, manifestDigest=None, held=[]), 1, 0),
    built(
        '{"availability":{"fsm":"DRAINING"},"engine":{"backend":"vulkan","commit":"4f1c2ab","confVersion":"1.0.0","held":[]},"manifest":null,'
        '"power":{"batteryBand":"20-49","charging":false,"source":"battery"},"queue":{"bucket":1},"sampledAgeMs":0,"seq":1,"thermal":{"band":1,"governor":"QUEUE"},"v":1}',
        '{"fsm":"DRAINING","gov":"QUEUE","qb":1,"seq":1,"tb":1}',
    ),
))
W07.append(vec(
    "W07-004", "the queue bucket is min(2, local + peer): 1 + 4 gives 2; the exact battery percentage, loaded models and free memory are absent",
    build(view(localQueued=1, peerQueued=4, batteryPercentExact=37, held=[HEX_B, HEX_A]), 9, 60_000),
    built(
        jcs({**EXAMPLE, "queue": {"bucket": 2}, "sampledAgeMs": 60_000, "seq": 9, "engine": {"backend": "vulkan", "commit": "4f1c2ab", "confVersion": "1.0.0", "held": [HEX_A, HEX_B]}}),
        '{"fsm":"SERVING","gov":"RUN","qb":2,"seq":9,"tb":0}',
    ),
))
W07.append(vec(
    "W07-005", "an idle AC lender with thermal band 2 and governor HOLD",
    build(view(thermalBand=2, governor="HOLD", fsm="ARMED", held=[], manifestSeq=None, manifestDigest=None), 12, 5),
    built(
        '{"availability":{"fsm":"ARMED"},"engine":{"backend":"vulkan","commit":"4f1c2ab","confVersion":"1.0.0","held":[]},"manifest":null,'
        '"power":{"batteryBand":null,"charging":false,"source":"ac"},"queue":{"bucket":0},"sampledAgeMs":5,"seq":12,"thermal":{"band":2,"governor":"HOLD"},"v":1}',
        '{"fsm":"ARMED","gov":"HOLD","qb":0,"seq":12,"tb":2}',
    ),
))


def parse(text, unknown=False):
    return {"kind": "stateParse", "text": text, "hasUnknownMembers": unknown}


def mod(**kw):
    d = json.loads(EXAMPLE_TEXT)
    for k, v in kw.items():
        d[k] = v
    return jcs(d)


W07.append(vec("W07-010", "the spec's example parses and its normal form is itself", parse(EXAMPLE_TEXT), ok({"normal": EXAMPLE_TEXT})))
W07.append(vec(
    "W07-011", "a receiver ignores unknown members (user, inflight, loaded, queue.estStartS) and never stores them",
    parse(EXAMPLE_TEXT.replace('{"availability"', '{"user":{"active":true},"inflight":3,"availability"').replace('"bucket":0', '"bucket":0,"estStartS":4'), True), ok({"normal": EXAMPLE_TEXT}),
))
W07.append(vec("W07-012", "a fraction is refused by the JSON profile", parse(EXAMPLE_TEXT.replace('"seq":4711', '"seq":4711.5')), rej("NON_INTEGER_NUMBER")))
W07.append(vec("W07-013", "an exponent is refused", parse(EXAMPLE_TEXT.replace('"seq":4711', '"seq":1e3')), rej("NON_INTEGER_NUMBER")))
W07.append(vec("W07-014", "negative zero is refused", parse(EXAMPLE_TEXT.replace('"seq":4711', '"seq":-0')), rej("NON_INTEGER_NUMBER")))
W07.append(vec("W07-015", "a duplicate member name is refused", parse(EXAMPLE_TEXT.replace('"v":1', '"v":1,"v":1')), rej("DUPLICATE_KEY")))
W07.append(vec("W07-016", "truncated JSON", parse("{"), rej("MALFORMED_JSON")))
W07.append(vec("W07-017", "a top-level array is the wrong type", parse("[]"), rej("WRONG_TYPE")))
W07.append(vec("W07-018", "version 2 is not understood", parse(EXAMPLE_TEXT.replace('"v":1', '"v":2')), rej("BAD_VERSION")))
W07.append(vec("W07-019", "a missing required member (seq)", parse(EXAMPLE_TEXT.replace('"seq":4711,', "")), rej("MISSING_MEMBER")))
W07.append(vec("W07-020", "an fsm outside the closed enum (ERR-LP-3: refused, not mapped to unknown)", parse(EXAMPLE_TEXT.replace("SERVING", "SLEEPING")), rej("UNKNOWN_ENUM")))
W07.append(vec("W07-021", "thermal band 3 is out of range (0..2)", parse(EXAMPLE_TEXT.replace('"band":0', '"band":3')), rej("OUT_OF_RANGE")))
W07.append(vec("W07-022", "queue bucket 3 is out of range (0..2)", parse(EXAMPLE_TEXT.replace('"bucket":0', '"bucket":3')), rej("OUT_OF_RANGE")))
W07.append(vec("W07-023", "a negative sampledAgeMs", parse(EXAMPLE_TEXT.replace('"sampledAgeMs":800', '"sampledAgeMs":-1')), rej("OUT_OF_RANGE")))
W07.append(vec("W07-024", "sampledAgeMs above 60,000 is accepted; the normal form clamps it", parse(EXAMPLE_TEXT.replace('"sampledAgeMs":800', '"sampledAgeMs":90000')), ok({"normal": EXAMPLE_TEXT.replace('"sampledAgeMs":800', '"sampledAgeMs":60000')})))
W07.append(vec("W07-025", "a backend outside the closed enum", parse(EXAMPLE_TEXT.replace('"vulkan"', '"webgpu"')), rej("UNKNOWN_ENUM")))
W07.append(vec("W07-026", "a power source outside ac|battery|unknown", parse(EXAMPLE_TEXT.replace('"source":"ac"', '"source":"solar"')), rej("UNKNOWN_ENUM")))
W07.append(vec("W07-027", "charging must be a boolean", parse(EXAMPLE_TEXT.replace('"charging":false', '"charging":0')), rej("WRONG_TYPE")))
W07.append(vec("W07-028", "a battery band outside the closed set", parse(EXAMPLE_TEXT.replace('"batteryBand":null', '"batteryBand":"ge90"')), rej("UNKNOWN_ENUM")))
W07.append(vec("W07-029", "manifest is required (null is allowed, absent is not)", parse(EXAMPLE_TEXT.replace(',"manifest":{"bodyDigest":"%s","seq":17}' % DIG, "").replace('"manifest":{"bodyDigest":"%s","seq":17},' % DIG, "")), rej("MISSING_MEMBER")))
W07.append(vec("W07-030", "manifest null is allowed", parse(mod(manifest=None)), ok({"normal": mod(manifest=None)})))
W07.append(vec("W07-031", "an integer beyond 2^53 - 1 is refused by the JSON profile", parse(EXAMPLE_TEXT.replace('"seq":4711', '"seq":9007199254740992')), rej("NUMBER_RANGE")))
W07.append(vec("W07-032", "a held entry that is not 64 hex characters", parse(EXAMPLE_TEXT.replace(HEX_A, "abc")), rej("OUT_OF_RANGE")))
W07.append(vec(
    "W07-033", "held in any order parses; the normal form sorts it",
    parse(jcs({**EXAMPLE, "engine": {**EXAMPLE["engine"], "held": [HEX_B, HEX_A]}})),
    ok({"normal": jcs({**EXAMPLE, "engine": {**EXAMPLE["engine"], "held": [HEX_A, HEX_B]}})}),
))
W07.append(vec("W07-034", "a body digest that is not 43 base64url characters", parse(EXAMPLE_TEXT.replace(DIG, "short")), rej("OUT_OF_RANGE")))
W07.append(vec("W07-035", "seq 0 is out of range (the first state has seq 1)", parse(EXAMPLE_TEXT.replace('"seq":4711', '"seq":0')), rej("OUT_OF_RANGE")))


def prod(text):
    return {"kind": "producerCheck", "text": text}


W07.append(vec("W07-040", "producer-strict: the example conforms", prod(EXAMPLE_TEXT), ok({"conforms": True})))
W07.append(vec("W07-041", "producer-strict: a top-level `user` member is a presence field", prod(EXAMPLE_TEXT.replace('{"availability"', '{"user":{"active":false},"availability"')), rej("PRESENCE_FIELD")))
W07.append(vec("W07-042", "producer-strict: `inflight`", prod(EXAMPLE_TEXT.replace('"v":1', '"v":1,"inflight":0')), rej("PRESENCE_FIELD")))
W07.append(vec("W07-043", "producer-strict: `busyForMs` in thermal", prod(EXAMPLE_TEXT.replace('"governor":"RUN"', '"busyForMs":0,"governor":"RUN"')), rej("PRESENCE_FIELD")))
W07.append(vec("W07-044", "producer-strict: `loaded` in engine", prod(EXAMPLE_TEXT.replace('"held":', '"loaded":[],"held":')), rej("PRESENCE_FIELD")))
W07.append(vec("W07-045", "producer-strict: `estStartS` in queue", prod(EXAMPLE_TEXT.replace('"bucket":0', '"bucket":0,"estStartS":4')), rej("PRESENCE_FIELD")))
W07.append(vec("W07-046", "producer-strict: `reason` in availability", prod(EXAMPLE_TEXT.replace('"fsm":"SERVING"', '"fsm":"SERVING","reason":"sleeping"')), rej("PRESENCE_FIELD")))
W07.append(vec("W07-047", "producer-strict: an unknown non-presence member", prod(EXAMPLE_TEXT.replace('"v":1', '"v":1,"zzz":1')), rej("UNKNOWN_MEMBER")))
W07.append(vec("W07-048", "producer-strict: an unknown nested member", prod(EXAMPLE_TEXT.replace('"charging":false', '"charging":false,"zzz":true')), rej("UNKNOWN_MEMBER")))
W07.append(vec("W07-049", "producer-strict: a float is refused by the JSON profile first", prod(EXAMPLE_TEXT.replace('"seq":4711', '"seq":47.5')), rej("NON_INTEGER_NUMBER")))
W07.append(vec("W07-050", "producer-strict: `queuePos` (removed from INFER_ACCEPT and never a STATE member)", prod(EXAMPLE_TEXT.replace('"bucket":0', '"bucket":0,"queuePos":1')), rej("PRESENCE_FIELD")))


def stale(now, rx, sampled, closed=False, seq=5, last=None, goaway=False, state=None, extra=None):
    d = {"kind": "staleness", "nowMonoMs": now, "rxMonoMs": rx, "sampledAgeMs": sampled, "sessionClosed": closed, "seq": seq, "lastSeq": last, "goaway": goaway}
    if state is not None:
        d["state"] = state
    if extra:
        d.update(extra)
    return d


def cls(c, age, probe=False, fast="absent"):
    r = {"class": c, "ageMs": age, "probeOnly": probe}
    if fast != "absent":
        r["fast"] = fast
    return ok(r)


R = 10_000  # rxMonoMs
W07 += [
    vec("W07-060", "age 0: FRESH", stale(R, R, 0), cls("FRESH", 0)),
    vec("W07-061", "age 5,000: still FRESH", stale(R + 5_000, R, 0), cls("FRESH", 5_000)),
    vec("W07-062", "age 5,001: WARM", stale(R + 5_001, R, 0), cls("WARM", 5_001)),
    vec("W07-063", "age 30,000: still WARM", stale(R + 30_000, R, 0), cls("WARM", 30_000)),
    vec("W07-064", "age 30,001: STALE", stale(R + 30_001, R, 0), cls("STALE", 30_001)),
    vec("W07-065", "age 300,000: still STALE", stale(R + 300_000, R, 0), cls("STALE", 300_000)),
    vec("W07-066", "age 300,001: EXPIRED, probe-only", stale(R + 300_001, R, 0), cls("EXPIRED", 300_001, True)),
    vec("W07-067", "the sender's sampled age adds to the requester's own: 4,000 + 1,000 = 5,000 is FRESH", stale(R + 4_000, R, 1_000), cls("FRESH", 5_000)),
    vec("W07-068", "4,001 + 1,000 = 5,001 is WARM", stale(R + 4_001, R, 1_000), cls("WARM", 5_001)),
    vec("W07-069", "sampled age is capped at 60,000: a huge value alone gives STALE, never EXPIRED", stale(R, R, 10_000_000), cls("STALE", 60_000)),
    vec("W07-070", "a closed session's state that is exactly 30,000 old is WARM", stale(R + 30_000, R, 0, closed=True), cls("WARM", 30_000)),
    vec("W07-071", "a closed session's state older than 30,000 is EXPIRED", stale(R + 30_001, R, 0, closed=True), cls("EXPIRED", 30_001, True)),
    vec("W07-072", "seq went backwards: EXPIRED", stale(R, R, 0, seq=4, last=5), cls("EXPIRED", 0, True)),
    vec("W07-073", "an equal seq is a repeat, not a reset (ERR-LP-4)", stale(R, R, 0, seq=5, last=5), cls("FRESH", 0)),
    vec("W07-074", "a GOAWAY since the state: EXPIRED", stale(R, R, 0, goaway=True), cls("EXPIRED", 0, True)),
    vec(
        "W07-075", "STALE: queue min(2, 1 + 1) = 2, battery band one lower (ge80 to 50-79) on battery, thermal band unchanged (ERR-LP-5)",
        stale(R + 40_000, R, 0, state={"thermalBand": 1, "queueBucket": 1, "batteryBand": "ge80", "powerSource": "battery"}),
        cls("STALE", 40_000, False, {"thermalBand": 1, "queueBucket": 2, "batteryBand": "50-79"}),
    ),
    vec(
        "W07-076", "STALE on AC with quiet fields: nothing changes", stale(R + 40_000, R, 0, state={"thermalBand": 0, "queueBucket": 0, "batteryBand": "ge80", "powerSource": "ac"}),
        cls("STALE", 40_000, False, {"thermalBand": 0, "queueBucket": 0, "batteryBand": "ge80"}),
    ),
    vec(
        "W07-077", "WARM passes the received values", stale(R + 10_000, R, 0, state={"thermalBand": 2, "queueBucket": 1, "batteryBand": "20-49", "powerSource": "battery"}),
        cls("WARM", 10_000, False, {"thermalBand": 2, "queueBucket": 1, "batteryBand": "20-49"}),
    ),
    vec(
        "W07-078", "EXPIRED yields no fast fields: probe-only", stale(R + 400_000, R, 0, state={"thermalBand": 0, "queueBucket": 0, "batteryBand": None, "powerSource": "ac"}),
        cls("EXPIRED", 400_000, True, None),
    ),
    vec(
        "W07-079", "STALE, battery band lt20 stays lt20", stale(R + 40_000, R, 0, state={"thermalBand": 0, "queueBucket": 0, "batteryBand": "lt20", "powerSource": "battery"}),
        cls("STALE", 40_000, False, {"thermalBand": 0, "queueBucket": 0, "batteryBand": "lt20"}),
    ),
    vec(
        "W07-080", "STALE, unknown battery band stays unknown", stale(R + 40_000, R, 0, state={"thermalBand": 0, "queueBucket": 0, "batteryBand": None, "powerSource": "battery"}),
        cls("STALE", 40_000, False, {"thermalBand": 0, "queueBucket": 0, "batteryBand": None}),
    ),
]

SKEW = [-600_000, -1, 0, 1, 600_000]
for n, (desc, now, samp, expect) in enumerate(
    [
        ("FRESH", R + 1_000, 0, "FRESH"), ("WARM", R + 20_000, 0, "WARM"), ("STALE", R + 100_000, 0, "STALE"), ("EXPIRED", R + 400_000, 0, "EXPIRED"),
    ],
    start=90,
):
    W07.append(vec(f"W07-{n:03d}", f"a peer clock that is 10 minutes fast or slow (and 1 ms either way) changes no class: {desc}", {**stale(now, R, samp), "kind": "skew", "peerSkewMs": SKEW}, ok({"class": expect})))

# ----------------------------------------------------------------------------------------------------------------------------------------------
# W07p: presence laws, lender decision, wire projection
# ----------------------------------------------------------------------------------------------------------------------------------------------
def ev(t, e, **kw):
    d = {"t": t, "e": e}
    d.update(kw)
    return d


def cond(t, ok_, name="THERMAL_BAND"):
    return ev(t, "input", input=name, ok=ok_)


def pres(t, name):
    return ev(t, "input", input=name)


def trace(pf, grace, events, states):
    return {"kind": "fsmTrace", "pf": pf, "graceMs": grace, "events": events}, ok({"fsm": states})


def fsm_vec(id_, desc, pf, grace, events, states):
    i, e = trace(pf, grace, events, states)
    return vec(id_, desc, i, e)


W07P = [
    fsm_vec("W07p-001", "LP-2: a presence event while SERVING is DRAINING in the same step", False, 30_000, [ev(0, "enable"), cond(0, True), pres(1_000, "SCREEN_INTERACTIVE")], ["ARMED", "SERVING", "DRAINING"]),
    fsm_vec(
        "W07p-002", "LP-2: a second presence event at t + 599,999 restarts the hold-down: not SERVING at t' + 599,999, SERVING at the first evaluation >= t' + 600,000",
        False, 30_000,
        [ev(0, "enable"), cond(0, True), pres(1_000, "INPUT_ACTIVITY"), ev(1_001, "tick"), pres(600_999, "INPUT_ACTIVITY"), ev(1_200_998, "tick"), ev(1_200_999, "tick")],
        ["ARMED", "SERVING", "DRAINING", "ARMED", "ARMED", "ARMED", "SERVING"],
    ),
    fsm_vec(
        "W07p-003", "LP-2: not SERVING at t + 599,999, SERVING at t + 600,000", False, 30_000,
        [ev(0, "enable"), cond(0, True), pres(5_000, "KEYGUARD_DISMISSED"), ev(5_001, "tick"), ev(604_999, "tick"), ev(605_000, "tick")],
        ["ARMED", "SERVING", "DRAINING", "ARMED", "ARMED", "SERVING"],
    ),
    fsm_vec(
        "W07p-004", "LP-2: a thermal drain returns as soon as the band drops (no hold-down for a condition)", False, 30_000,
        [ev(0, "enable"), cond(0, True), cond(10_000, False), ev(10_001, "tick"), cond(20_000, True)], ["ARMED", "SERVING", "DRAINING", "ARMED", "SERVING"],
    ),
    fsm_vec(
        "W07p-005", "design 3.2 rule 2: a sleep-imminent drain is not a presence drain: no hold-down", False, 30_000,
        [ev(0, "enable"), cond(0, True), cond(5_000, False, "SLEEP_IMMINENT"), ev(5_001, "tick"), cond(6_000, True, "SLEEP_IMMINENT")], ["ARMED", "SERVING", "DRAINING", "ARMED", "SERVING"],
    ),
    fsm_vec(
        "W07p-006", "grace: in-flight work keeps the drain going until the grace expires", False, 2_000,
        [ev(0, "enable"), cond(0, True), ev(100, "inflight", n=1), pres(1_000, "INPUT_ACTIVITY"), ev(2_999, "tick"), ev(3_000, "tick")],
        ["ARMED", "SERVING", "SERVING", "DRAINING", "DRAINING", "ARMED"],
    ),
    fsm_vec(
        "W07p-007", "the drain completes as soon as the in-flight work finishes", False, 30_000,
        [ev(0, "enable"), cond(0, True), ev(100, "inflight", n=1), pres(1_000, "INPUT_ACTIVITY"), ev(1_500, "inflight", n=0)], ["ARMED", "SERVING", "SERVING", "DRAINING", "ARMED"],
    ),
    fsm_vec("W07p-008", "user_disable from SERVING: OFF at once", False, 30_000, [ev(0, "enable"), cond(0, True), ev(500, "disable")], ["ARMED", "SERVING", "OFF"]),
    fsm_vec(
        "W07p-009", "conditions fail while ARMED: stays ARMED; a game (heavy foreground process) while SERVING drains", False, 2_000,
        [ev(0, "enable"), cond(0, False), cond(1_000, True), pres(2_000, "HEAVY_FOREGROUND_PROCESS")], ["ARMED", "ARMED", "SERVING", "DRAINING"],
    ),
    fsm_vec(
        "W07p-010", "PF: frontmost lend screen + screen on + a touch inside it: consent, stays SERVING", True, 2_000,
        [ev(0, "enable"), ev(0, "start"), cond(0, True), pres(1_000, "LEND_SCREEN_FRONTMOST"), pres(2_000, "SCREEN_INTERACTIVE"), pres(3_000, "INPUT_INSIDE_LEND_SCREEN")],
        ["ARMED", "ARMED", "SERVING", "SERVING", "SERVING", "SERVING"],
    ),
    fsm_vec(
        "W07p-011", "PF: a touch outside the lend screen: DRAINING, then no SERVING without a new explicit Start lending (even past the hold-down)", True, 2_000,
        [ev(0, "enable"), ev(0, "start"), cond(0, True), pres(4_000, "INPUT_OUTSIDE_LEND_SCREEN"), ev(4_001, "tick"), ev(700_000, "tick"), ev(700_001, "start")],
        ["ARMED", "ARMED", "SERVING", "DRAINING", "ARMED", "ARMED", "SERVING"],
    ),
    fsm_vec(
        "W07p-012", "PF: the lend screen leaving the foreground is presence", True, 2_000,
        [ev(0, "enable"), ev(0, "start"), cond(0, True), pres(1_000, "LEND_SCREEN_LEFT_FOREGROUND")], ["ARMED", "ARMED", "SERVING", "DRAINING"],
    ),
    fsm_vec(
        "W07p-013", "PF: the scene resigning active is presence", True, 2_000,
        [ev(0, "enable"), ev(0, "start"), cond(0, True), pres(1_000, "SCENE_RESIGN_ACTIVE")], ["ARMED", "ARMED", "SERVING", "DRAINING"],
    ),
    fsm_vec(
        "W07p-014", "PF: the terminal losing focus is presence (the mechanism that reports it is unspecified, ERR-LP-2)", True, 2_000,
        [ev(0, "enable"), ev(0, "start"), cond(0, True), pres(1_000, "TERMINAL_FOCUS_LOST")], ["ARMED", "ARMED", "SERVING", "DRAINING"],
    ),
    fsm_vec(
        "W07p-015", "PF: the screen turning off is presence", True, 2_000,
        [ev(0, "enable"), ev(0, "start"), cond(0, True), pres(1_000, "SCREEN_OFF")], ["ARMED", "ARMED", "SERVING", "DRAINING"],
    ),
    fsm_vec(
        "W07p-016", "PF: Start lending inside the hold-down does not bring it back early; it returns at the first evaluation >= t + 600,000", True, 2_000,
        [ev(0, "enable"), ev(0, "start"), cond(0, True), pres(1_000, "LEND_SCREEN_LEFT_FOREGROUND"), ev(1_001, "tick"), ev(2_000, "start"), ev(601_000, "tick")],
        ["ARMED", "ARMED", "SERVING", "DRAINING", "ARMED", "ARMED", "SERVING"],
    ),
    fsm_vec(
        "W07p-017", "PF: contention from other processes stays presence (conservative)", True, 2_000,
        [ev(0, "enable"), ev(0, "start"), cond(0, True), pres(1_000, "OTHER_PROCESS_CONTENTION")], ["ARMED", "ARMED", "SERVING", "DRAINING"],
    ),
    fsm_vec(
        "W07p-018", "not PF: the screen turning interactive IS presence (contrast with W07p-010)", False, 2_000,
        [ev(0, "enable"), cond(0, True), pres(1_000, "SCREEN_INTERACTIVE")], ["ARMED", "SERVING", "DRAINING"],
    ),
    fsm_vec(
        "W07p-019", "a presence event while ARMED extends the hold-down even though nothing was serving", False, 30_000,
        [ev(0, "enable"), cond(0, False), pres(1_000, "CONSOLE_USER"), cond(2_000, True), ev(600_999, "tick"), ev(601_000, "tick")],
        ["ARMED", "ARMED", "ARMED", "ARMED", "ARMED", "SERVING"],
    ),
]

PRESENCE = ["SCREEN_INTERACTIVE", "INPUT_ACTIVITY", "KEYGUARD_DISMISSED", "FOREGROUND_APP", "HEAVY_FOREGROUND_PROCESS", "CONSOLE_USER", "LOGIN_STATE", "OTHER_PROCESS_CONTENTION"]
CONDITION = ["POWER_SOURCE", "CHARGING", "BATTERY_LEVEL", "BATTERY_TEMPERATURE", "THERMAL_BAND", "MEMORY", "PATH", "SLEEP_IMMINENT"]
PF_CONSENT = ["LEND_SCREEN_FRONTMOST", "INPUT_INSIDE_LEND_SCREEN"]
PF_PRESENCE = ["LEND_SCREEN_LEFT_FOREGROUND", "SCENE_RESIGN_ACTIVE", "TERMINAL_FOCUS_LOST", "SCREEN_OFF", "INPUT_OUTSIDE_LEND_SCREEN"]
n = 100
for name in PRESENCE:
    pf_kind = "consent" if name == "SCREEN_INTERACTIVE" else "presence"
    W07P.append(vec(f"W07p-{n}", f"LP-0: {name} on a node that is not PF is presence", {"kind": "classify", "input": name, "pf": False}, ok("presence")))
    n += 1
    W07P.append(vec(f"W07p-{n}", f"LP-0: {name} on a PF node is {pf_kind}", {"kind": "classify", "input": name, "pf": True}, ok(pf_kind)))
    n += 1
for name in CONDITION:
    for pf in (False, True):
        W07P.append(vec(f"W07p-{n}", f"LP-0: {name} is a condition (pf={str(pf).lower()})", {"kind": "classify", "input": name, "pf": pf}, ok("condition")))
        n += 1
for name in PF_CONSENT:
    W07P.append(vec(f"W07p-{n}", f"LP-0 PF exception: {name} is consent", {"kind": "classify", "input": name, "pf": True}, ok("consent")))
    n += 1
    W07P.append(vec(f"W07p-{n}", f"{name} does not exist on a node that is not PF", {"kind": "classify", "input": name, "pf": False}, rej("PF_ONLY_INPUT")))
    n += 1
for name in PF_PRESENCE:
    W07P.append(vec(f"W07p-{n}", f"LP-0 PF exception: {name} is presence", {"kind": "classify", "input": name, "pf": True}, ok("presence")))
    n += 1
    W07P.append(vec(f"W07p-{n}", f"{name} does not exist on a node that is not PF", {"kind": "classify", "input": name, "pf": False}, rej("PF_ONLY_INPUT")))
    n += 1

OFFER = {"attemptId": "AAAAAAAAAAAAAAAAAAAAAA", "model": "qwen3-8b", "op": "chat", "promptBytes": 1_200, "maxTokens": 512, "deadlineMs": 60_000, "stream": True}
SIT = {
    "registryStatus": "PAIRED", "inferScopeGranted": True, "attemptSeenWithin24h": False, "modelAllowedAndLoadable": True, "servingConditionsOk": True, "presenceActive": False,
    "presenceHoldRemainingMs": 0, "predictedThermalHold": False, "estStartMs": 0, "inflight": 0, "requestsThisMinute": 0,
}
AID = '"attemptId":"AAAAAAAAAAAAAAAAAAAAAA"'


def dec(id_, desc, sit=None, offer=None, expect=None):
    s = dict(SIT)
    s.update(sit or {})
    o = dict(OFFER)
    o.update(offer or {})
    return vec(id_, desc, {"kind": "lenderDecision", "offer": o, "situation": s}, ok(expect))


def decline(row, code, retry):
    return {"row": row, "reply": "decline", "code": code, "retryAfterMs": retry, "wire": "{" + AID + f',"code":"{code}","retryAfterMs":{retry}' + "}"}


def error(row, code, close):
    return {"row": row, "reply": "error", "code": code, "close": close, "wire": "{" + AID + f',"code":"{code}"' + "}"}


W07P += [
    dec("W07p-200", "row 8: every row passes: INFER_ACCEPT, retain none, no queuePos and no estStartMs", expect={"row": "8", "reply": "accept", "retain": "none", "wire": "{" + AID + ',"fileSha256":"' + "a" * 64 + '","servedModel":"qwen3-8b"}'}),
    dec("W07p-201", "row 1: not PAIRED: ERROR PEER_NOT_PAIRED and close", sit={"registryStatus": "REVOKED"}, expect=error("1", "PEER_NOT_PAIRED", True)),
    dec("W07p-202", "row 1: SUSPENDED is not PAIRED either", sit={"registryStatus": "SUSPENDED"}, expect=error("1", "PEER_NOT_PAIRED", True)),
    dec("W07p-203", "row 2: no infer scope: SCOPE_DENIED", sit={"inferScopeGranted": False}, expect=decline("2", "SCOPE_DENIED", 30_000)),
    dec("W07p-204", "row 3: an attemptId seen in the last 24 h: DUPLICATE_ATTEMPT", sit={"attemptSeenWithin24h": True}, expect=decline("3", "DUPLICATE_ATTEMPT", 5_000)),
    dec("W07p-205", "row 4: the model is not allowed or loadable: MODEL_NOT_OFFERED", sit={"modelAllowedAndLoadable": False}, expect=decline("4", "MODEL_NOT_OFFERED", 30_000)),
    dec("W07p-206", "row 5: promptBytes above maxBodyBytes: ERROR FRAME_TOO_LARGE (ERR-LP-6), no close", offer={"promptBytes": 8_388_609}, expect=error("5", "FRAME_TOO_LARGE", False)),
    dec("W07p-207", "row 5: maxTokens above the limit", offer={"maxTokens": 4_097}, expect=error("5", "FRAME_TOO_LARGE", False)),
    dec("W07p-208", "row 5: promptBytes exactly at the limit passes", offer={"promptBytes": 8_388_608}, expect={"row": "8", "reply": "accept", "retain": "none", "wire": "{" + AID + ',"fileSha256":"' + "a" * 64 + '","servedModel":"qwen3-8b"}'}),
    dec("W07p-209", "row 6: serving conditions fail (charging, battery, thermal): PEER_UNAVAILABLE", sit={"servingConditionsOk": False}, expect=decline("6", "PEER_UNAVAILABLE", 30_000)),
    dec("W07p-210", "row 6, LP-1: a presence cause is PEER_UNAVAILABLE with the remaining hold-down as retryAfterMs", sit={"presenceActive": True, "presenceHoldRemainingMs": 123_456}, expect=decline("6", "PEER_UNAVAILABLE", 123_456)),
    dec("W07p-211", "row 6: a presence hold-down below 5,000 ms is raised to 5,000", sit={"presenceActive": True, "presenceHoldRemainingMs": 10}, expect=decline("6", "PEER_UNAVAILABLE", 5_000)),
    dec("W07p-212", "row 6: a hold-down above 600,000 ms is lowered to 600,000", sit={"presenceActive": True, "presenceHoldRemainingMs": 10_000_000}, expect=decline("6", "PEER_UNAVAILABLE", 600_000)),
    dec("W07p-213", "row 6a: a predicted thermal hold: PEER_UNAVAILABLE", sit={"predictedThermalHold": True}, expect=decline("6a", "PEER_UNAVAILABLE", 30_000)),
    dec("W07p-214", "row 6b: estStartMs 60,001 > deadlineMs 60,000: PEER_BUSY (the estimate is never sent)", sit={"estStartMs": 60_001}, expect=decline("6b", "PEER_BUSY", 5_000)),
    dec("W07p-215", "row 6b: estStartMs equal to the deadline is accepted", sit={"estStartMs": 60_000}, expect={"row": "8", "reply": "accept", "retain": "none", "wire": "{" + AID + ',"fileSha256":"' + "a" * 64 + '","servedModel":"qwen3-8b"}'}),
    dec("W07p-216", "row 7: one request already in flight (maxConcurrent 1): PEER_BUSY", sit={"inflight": 1}, expect=decline("7", "PEER_BUSY", 5_000)),
    dec("W07p-217", "row 7: 30 requests this minute (rpm 30): PEER_BUSY", sit={"requestsThisMinute": 30}, expect=decline("7", "PEER_BUSY", 5_000)),
    dec("W07p-218", "row 7: 29 requests this minute passes", sit={"requestsThisMinute": 29}, expect={"row": "8", "reply": "accept", "retain": "none", "wire": "{" + AID + ',"fileSha256":"' + "a" * 64 + '","servedModel":"qwen3-8b"}'}),
    dec("W07p-219", "order: not PAIRED outranks a missing scope", sit={"registryStatus": "REVOKED", "inferScopeGranted": False}, expect=error("1", "PEER_NOT_PAIRED", True)),
    dec("W07p-220", "order: a missing scope outranks a duplicate", sit={"inferScopeGranted": False, "attemptSeenWithin24h": True}, expect=decline("2", "SCOPE_DENIED", 30_000)),
    dec("W07p-221", "order: a too-large offer outranks a presence cause", sit={"presenceActive": True, "presenceHoldRemainingMs": 100_000}, offer={"promptBytes": 9_000_000}, expect=error("5", "FRAME_TOO_LARGE", False)),
    dec("W07p-222", "order: presence and a failing condition are one row (6); the presence retryAfterMs wins", sit={"presenceActive": True, "presenceHoldRemainingMs": 200_000, "servingConditionsOk": False}, expect=decline("6", "PEER_UNAVAILABLE", 200_000)),
    dec("W07p-223", "order: row 6 outranks 6a, 6b and 7", sit={"servingConditionsOk": False, "predictedThermalHold": True, "estStartMs": 90_000, "inflight": 1}, expect=decline("6", "PEER_UNAVAILABLE", 30_000)),
    dec("W07p-224", "order: 6a outranks 6b", sit={"predictedThermalHold": True, "estStartMs": 90_000}, expect=decline("6a", "PEER_UNAVAILABLE", 30_000)),
    dec("W07p-225", "order: 6b outranks 7", sit={"estStartMs": 90_000, "inflight": 1}, expect=decline("6b", "PEER_BUSY", 5_000)),
]


def wd(vary, changed, desc, id_, base=None):
    return vec(id_, desc, {"kind": "wireDiff", "view": base or view(presence=NO_PRESENCE), "vary": vary}, ok({"changed": changed}))


W07P += [
    wd({"presence": BUSY_PRESENCE}, [], "LP-1: only presence inputs change: no wire field changes", "W07p-300"),
    wd({"presence": {**BUSY_PRESENCE, "screenInteractive": False, "foregroundApp": "org.example.terminal"}}, [], "LP-1: another presence change: still nothing on the wire", "W07p-301"),
    wd({"presence": BUSY_PRESENCE, "fsm": "DRAINING"}, ["availability.fsm"], "LP-1 (ii): a presence-driven change of availability.fsm is allowed", "W07p-302"),
    wd({"presence": BUSY_PRESENCE, "localQueued": 2}, ["queue.bucket"], "LP-1 (iii): the queue bucket moves with the lender's own local use", "W07p-303"),
    wd({"presence": BUSY_PRESENCE, "fsm": "ARMED", "localQueued": 1}, ["availability.fsm", "queue.bucket"], "LP-1: fsm and the queue bucket together, and nothing else", "W07p-304"),
    wd({"governor": "HOLD"}, ["thermal.governor"], "control: a CONDITION change (the thermal governor) does show on the wire", "W07p-305"),
]

# ----------------------------------------------------------------------------------------------------------------------------------------------
# L02: which frame or event produces which rows (LAB_SPEC 7.6). Rows are abbreviated: k = meshKind, p = phase, c = meshCode, o = bytesOut, i = bytesIn,
# st = status, s = the node's session group (1-based, by first appearance), a = attemptId (attempt rows), ob = overheadBasis. Application bytes = 9 + payload.
# ----------------------------------------------------------------------------------------------------------------------------------------------
def r(k, p, c, o, i, st, s, a=None, ob=None, **extra):
    d = {"k": k, "p": p, "c": c, "o": o, "i": i, "st": st, "s": s}
    if a:
        d["a"] = a
    if ob:
        d["ob"] = ob
    d.update(extra)
    return d


def dial(**kw):
    return {"op": "dial", **kw}


def fr(frm, kind, payload, **kw):
    return {"op": "frame", "from": frm, "kind": kind, "payload": payload, **kw}


def close(by):
    return {"op": "close", "by": by}


def fin(att, status, code, tokens=0):
    return {"op": "finish", "attempt": att, "status": status, "meshCode": code, "tokensOut": tokens}


HS = [dial(), fr("A", "HELLO", 300, stream="0"), fr("B", "HELLO_ACK", 200, stream="0")]
DIAL_I = r("dial", "intent", None, 0, None, 0, 1)
DIAL_O = r("dial", "outcome", "connected", 0, 0, 200, 1, ob="measured")
H_A = [r("control", None, "HELLO", 309, 0, 200, 1), r("control", None, "HELLO_ACK", 0, 209, 200, 1)]
H_B = [r("control", None, "HELLO", 0, 309, 200, 1), r("control", None, "HELLO_ACK", 209, 0, 200, 1)]
CLOSE = r("session", None, "close", 0, 0, 200, 1, ob="measured")
OPEN_B = r("session", None, "established", 0, 0, 200, 1, ob="measured")
INFER = ["infer-sent", "infer-served"]

L02 = []


def l02(id_, desc, steps, expect, **extra):
    L02.append(vec(id_, desc, {"seed": 1, "steps": steps, **extra}, ok(expect)))


l02(
    "L02-001", "a dialed session: DIAL intent before the SYN, DIAL outcome with the handshake overhead, HELLO and HELLO_ACK one CONTROL row per frame on each node, SESSION open on the listener (after HELLO), SESSION close with overhead",
    HS + [close("A")],
    {"A": [DIAL_I, DIAL_O] + H_A + [CLOSE], "B": [OPEN_B] + H_B + [CLOSE], "abort": None, "groups": {"A": 1, "B": 1}},
)
l02(
    "L02-002", "STATE_REQ and STATE: one CONTROL row per frame on both nodes",
    HS + [fr("A", "STATE_REQ", 7, stream="3"), fr("B", "STATE", 250, stream="3"), close("A")],
    {
        "A": H_A + [r("control", None, "STATE_REQ", 16, 0, 200, 1), r("control", None, "STATE", 0, 259, 200, 1)],
        "B": H_B + [r("control", None, "STATE_REQ", 0, 16, 200, 1), r("control", None, "STATE", 259, 0, 200, 1)],
    },
    show=["A", "B"], onlyKinds=["control"],
)
l02(
    "L02-003", "MANIFEST_REQ is a CONTROL row; MANIFEST is MANIFEST_SENT on the sender and MANIFEST_RECEIVED (the verdict in meshCode) on the receiver",
    HS + [fr("A", "MANIFEST_REQ", 60, stream="5"), fr("B", "MANIFEST", 4000, stream="5", code="verified"), close("A")],
    {
        "A": H_A + [r("control", None, "MANIFEST_REQ", 69, 0, 200, 1), r("manifest-received", None, "verified", 0, 4009, 200, 1)],
        "B": H_B + [r("control", None, "MANIFEST_REQ", 0, 69, 200, 1), r("manifest-sent", None, "MANIFEST", 4009, 0, 200, 1)],
    },
    show=["A", "B"], onlyKinds=["control", "manifest-sent", "manifest-received"],
)
l02(
    "L02-004", "a connection-level ERROR (ERROR:<code>) and GOAWAY (GOAWAY:<reason>) are CONTROL rows on both nodes",
    HS + [fr("B", "ERROR", 30, stream="0", code="SCOPE_DENIED"), fr("B", "GOAWAY", 17, stream="0", code="idle"), close("A")],
    {
        "A": H_A + [r("control", None, "ERROR:SCOPE_DENIED", 0, 39, 200, 1), r("control", None, "GOAWAY:idle", 0, 26, 200, 1)],
        "B": H_B + [r("control", None, "ERROR:SCOPE_DENIED", 39, 0, 200, 1), r("control", None, "GOAWAY:idle", 26, 0, 200, 1)],
    },
    show=["A", "B"], onlyKinds=["control"],
)
l02(
    "L02-005", "REVOKE_NOTICE is a REVOCATION row on the sender and on the receiver, in both directions",
    HS + [fr("A", "REVOKE_NOTICE", 21, stream="0"), fr("B", "REVOKE_NOTICE", 21, stream="0"), close("A")],
    {
        "A": [r("revocation", None, "REVOKE_NOTICE", 30, 0, 200, 1), r("revocation", None, "REVOKE_NOTICE", 0, 30, 200, 1)],
        "B": [r("revocation", None, "REVOKE_NOTICE", 0, 30, 200, 1), r("revocation", None, "REVOKE_NOTICE", 30, 0, 200, 1)],
    },
    show=["A", "B"], onlyKinds=["revocation"],
)
l02(
    "L02-006", "a pairing connection: SESSION open (pairing) on the listener, one PAIRING row per frame (all five kinds) on each node, both nodes group their rows into one session",
    [
        dial(mode="pairing"), fr("B", "PAIR_HELLO", 150), fr("A", "PAIR_CHALLENGE", 60), fr("A", "PAIR_DECISION", 40), fr("B", "PAIR_DECISION", 40),
        fr("A", "PAIR_COMMIT", 200), fr("B", "PAIR_COMMIT_ACK", 30), close("B"),
    ],
    {
        "A": [
            DIAL_I, DIAL_O, r("pairing", None, "PAIR_HELLO", 0, 159, 200, 1), r("pairing", None, "PAIR_CHALLENGE", 69, 0, 200, 1), r("pairing", None, "PAIR_DECISION", 49, 0, 200, 1),
            r("pairing", None, "PAIR_DECISION", 0, 49, 200, 1), r("pairing", None, "PAIR_COMMIT", 209, 0, 200, 1), r("pairing", None, "PAIR_COMMIT_ACK", 0, 39, 200, 1), CLOSE,
        ],
        "B": [
            r("session", None, "pairing", 0, 0, 200, 1, ob="measured"), r("pairing", None, "PAIR_HELLO", 159, 0, 200, 1), r("pairing", None, "PAIR_CHALLENGE", 0, 69, 200, 1),
            r("pairing", None, "PAIR_DECISION", 0, 49, 200, 1), r("pairing", None, "PAIR_DECISION", 49, 0, 200, 1), r("pairing", None, "PAIR_COMMIT", 0, 209, 200, 1),
            r("pairing", None, "PAIR_COMMIT_ACK", 39, 0, 200, 1), CLOSE,
        ],
        "abort": None, "groups": {"A": 1, "B": 1},
    },
)
l02(
    "L02-007", "an extension frame (type >= 0x80) from a non-conforming peer: CONTROL EXT_IGNORED on the receiver; the sender has no ledger here",
    [dial(hostile=True, source="user"), fr("B", "EXTENSION", 40, stream="0"), close("A")],
    {"A": [DIAL_I, DIAL_O, r("control", None, "EXT_IGNORED", 0, 49, 200, 1), CLOSE], "B": []},
    show=["A", "B"],
)
OUTCOMES = [("refused", "qr"), ("timeout", "hello"), ("pin-mismatch", "user"), ("not-tls", "qr"), ("local-network-denied", "qr"), ("firewall-blocked", "user")]
a_rows = []
for n_, (oc, src) in enumerate(OUTCOMES, start=1):
    a_rows += [r("dial", "intent", None, 0, None, 0, n_), r("dial", "outcome", oc, 0, 0, 599, n_)]
l02(
    "L02-008", "every failed dial outcome has a DIAL intent (before the SYN) and a DIAL outcome (599); the other node has nothing: nothing was authenticated",
    [dial(outcome=oc, source=src) for oc, src in OUTCOMES],
    {"A": a_rows, "B": [], "abort": None, "groups": {"A": 6, "B": 0}},
)
l02(
    "L02-009", "inbound connections refused before authentication: ONE INBOUND_REFUSED row per window, with no address and no session",
    [{"op": "refused", "count": 3}],
    {"A": [], "B": [r("inbound-refused", None, "refused:3", 0, 0, 403, None)]},
    show=["A", "B"],
)
l02(
    "L02-010", "an attempt declined at the offer: the requester has intent and outcome (503 + code, offer and decline bytes summed); the lender has an OUTCOME ONLY",
    HS + [fr("A", "INFER_OFFER", 120, attempt="a1", request="r1"), fr("B", "INFER_DECLINE", 90, attempt="a1", code="PEER_BUSY"), fin("a1", 503, "PEER_BUSY"), close("A")],
    {
        "A": [r("infer-sent", "intent", None, 0, None, 0, 1, a="a1"), r("infer-sent", "outcome", "PEER_BUSY", 129, 99, 503, 1, a="a1")],
        "B": [r("infer-served", "outcome", "PEER_BUSY", 99, 129, 503, 1, a="a1")],
    },
    show=["A", "B"], onlyKinds=INFER,
)
SERVED_END = {"model": "qwen3-8b", "status": 200, "terminal": "done", "tokensIn": 1300}
l02(
    "L02-011", "a served, streamed attempt: requester intent before the offer and outcome after the last frame; lender intent after the body arrives and outcome before INFER_END (whose bytes it already counts); bytes summed per direction",
    HS + [
        fr("A", "INFER_OFFER", 120, attempt="a1", request="r1"), fr("B", "INFER_ACCEPT", 100, attempt="a1"), fr("A", "INFER_BODY", 5000, attempt="a1"),
        fr("B", "INFER_HEAD", 70, attempt="a1"), fr("B", "INFER_CHUNK", 900, attempt="a1"), fr("B", "INFER_CHUNK", 800, attempt="a1"),
        fr("B", "INFER_END", 60, attempt="a1", served=SERVED_END), fin("a1", 200, None, 400), close("A"),
    ],
    {
        "A": [r("infer-sent", "intent", None, 0, None, 0, 1, a="a1"), r("infer-sent", "outcome", None, 5138, 1975, 200, 1, a="a1")],
        "B": [r("infer-served", "intent", None, 0, None, 0, 1, a="a1"), r("infer-served", "outcome", None, 1975, 5138, 200, 1, a="a1")],
    },
    show=["A", "B"], onlyKinds=INFER,
)
CANCELLED_END = {"model": "qwen3-8b", "status": 499, "terminal": "cancelled", "tokensIn": 0}
l02(
    "L02-012", "a cancelled attempt: CANCEL is counted in both outcome rows; the lender's code is CANCELLED",
    HS + [
        fr("A", "INFER_OFFER", 120, attempt="a1", request="r1"), fr("B", "INFER_ACCEPT", 100, attempt="a1"), fr("A", "INFER_BODY", 3000, attempt="a1"),
        fr("B", "INFER_HEAD", 70, attempt="a1"), fr("A", "CANCEL", 58, attempt="a1"), fr("B", "INFER_END", 60, attempt="a1", served=CANCELLED_END), fin("a1", 499, "CANCELLED"), close("A"),
    ],
    {
        "A": [r("infer-sent", "intent", None, 0, None, 0, 1, a="a1"), r("infer-sent", "outcome", "CANCELLED", 3205, 257, 499, 1, a="a1")],
        "B": [r("infer-served", "intent", None, 0, None, 0, 1, a="a1"), r("infer-served", "outcome", "CANCELLED", 257, 3205, 499, 1, a="a1")],
    },
    show=["A", "B"], onlyKinds=INFER,
)
SERVED2 = {"model": "qwen3-8b", "status": 200, "terminal": "done", "tokensIn": 40}
l02(
    "L02-013", "R3-CLOSURE-6: one session carrying two attempts: every row of both attempts (and the DIAL and SESSION rows) names the same session, so the rows group per session",
    HS + [
        fr("A", "INFER_OFFER", 100, attempt="a1", request="r1"), fr("B", "INFER_DECLINE", 90, attempt="a1", code="PEER_BUSY"), fin("a1", 503, "PEER_BUSY"),
        fr("A", "INFER_OFFER", 100, attempt="a2", request="r2"), fr("B", "INFER_ACCEPT", 80, attempt="a2"), fr("A", "INFER_BODY", 1000, attempt="a2"),
        fr("B", "INFER_HEAD", 50, attempt="a2"), fr("B", "INFER_CHUNK", 300, attempt="a2"), fr("B", "INFER_END", 40, attempt="a2", served=SERVED2), fin("a2", 200, None, 60), close("A"),
    ],
    {
        "A": [
            r("infer-sent", "intent", None, 0, None, 0, 1, a="a1"), r("infer-sent", "outcome", "PEER_BUSY", 109, 99, 503, 1, a="a1"),
            r("infer-sent", "intent", None, 0, None, 0, 1, a="a2"), r("infer-sent", "outcome", None, 1118, 506, 200, 1, a="a2"),
        ],
        "B": [
            r("infer-served", "outcome", "PEER_BUSY", 99, 109, 503, 1, a="a1"),
            r("infer-served", "intent", None, 0, None, 0, 1, a="a2"), r("infer-served", "outcome", None, 506, 1118, 200, 1, a="a2"),
        ],
        "groups": {"A": 1, "B": 1},
    },
    show=["A", "B", "groups"], onlyKinds=INFER,
)
l02(
    "L02-014", "a stack that cannot measure: every overhead figure is labelled ESTIMATED",
    HS + [close("A")],
    {
        "A": [DIAL_I, r("dial", "outcome", "connected", 0, 0, 200, 1, ob="estimated")] + H_A + [r("session", None, "close", 0, 0, 200, 1, ob="estimated")],
        "B": [r("session", None, "established", 0, 0, 200, 1, ob="estimated")] + H_B + [r("session", None, "close", 0, 0, 200, 1, ob="estimated")],
    },
    show=["A", "B"], measured=False,
)
l02(
    "L02-015", "FC-1: the requester's intent append fails: no byte of the attempt is sent, LEDGER_UNAVAILABLE, no further candidate; the session is closed cleanly afterwards",
    HS + [fr("A", "INFER_OFFER", 120, attempt="a1", request="r1"), close("A")],
    {"A": [DIAL_I, DIAL_O] + H_A + [CLOSE], "B": [OPEN_B] + H_B + [CLOSE], "abort": "LEDGER_UNAVAILABLE", "groups": {"A": 1, "B": 1}},
    fail={"node": "A", "at": 4, "mode": "BEFORE_WRITE", "sticky": False},
)
DECLINE_BYTES = 9 + len(jcs({"attemptId": "a1", "code": "PEER_UNAVAILABLE", "retryAfterMs": 30000}))
l02(
    "L02-016", "FC-4: the lender's intent append fails: it declines PEER_UNAVAILABLE, its own outcome row is the next write, the engine never starts (no lender intent row)",
    HS + [
        fr("A", "INFER_OFFER", 120, attempt="a1", request="r1"), fr("B", "INFER_ACCEPT", 100, attempt="a1"), fr("A", "INFER_BODY", 500, attempt="a1"),
        fin("a1", 503, "PEER_UNAVAILABLE"), close("A"),
    ],
    {
        "A": [r("infer-sent", "intent", None, 0, None, 0, 1, a="a1"), r("infer-sent", "outcome", "PEER_UNAVAILABLE", 129 + 509, 109 + DECLINE_BYTES, 503, 1, a="a1")],
        "B": [r("infer-served", "outcome", "PEER_UNAVAILABLE", 109 + DECLINE_BYTES, 129 + 509, 503, 1, a="a1")],
    },
    show=["A", "B"], onlyKinds=INFER, fail={"node": "B", "at": 3, "mode": "BEFORE_WRITE", "sticky": False},
)
l02(
    "L02-017", "FC-5: the lender's outcome append fails: no INFER_END is sent, the session is closed (close:ledger-failure) and the requester records the loss",
    HS + [
        fr("A", "INFER_OFFER", 120, attempt="a1", request="r1"), fr("B", "INFER_ACCEPT", 100, attempt="a1"), fr("A", "INFER_BODY", 500, attempt="a1"),
        fr("B", "INFER_HEAD", 70, attempt="a1"), fr("B", "INFER_END", 60, attempt="a1", served=SERVED_END), fin("a1", 502, "PEER_LOST"), close("A"),
    ],
    {
        "A": [DIAL_I, DIAL_O] + H_A + [r("infer-sent", "intent", None, 0, None, 0, 1, a="a1"), r("infer-sent", "outcome", "PEER_LOST", 638, 188, 502, 1, a="a1"), CLOSE],
        "B": [OPEN_B] + H_B + [r("infer-served", "intent", None, 0, None, 0, 1, a="a1"), r("session", None, "close:ledger-failure", 0, 0, 503, 1, ob="measured")],
        "abort": None,
    },
    show=["A", "B", "abort"], fail={"node": "B", "at": 4, "mode": "BEFORE_WRITE", "sticky": False},
)


def terminal(egress, served):
    return r("terminal", None, None, 0, None, 200, None, egress=egress, servedClass=served, header=egress)


l02(
    "L02-018", "W01b-reach, L-L5 and L-L5b: the terminal row's egress is the reach (the furthest class that received CONTENT, local < peer < cloud), servedClass is the serving attempt's class, and the header equals the row; a declined offer does not raise reach",
    HS + [
        {"op": "request", "id": "r1", "model": "qwen3-8b", "attempts": ["peer-served:interrupted:0:40", "local-ok"]},
        {"op": "request", "id": "r2", "model": "qwen3-8b", "attempts": ["peer-decline:PEER_BUSY", "local-ok"]},
        {"op": "request", "id": "r3", "model": "qwen3-8b", "attempts": ["cloud-fail", "local-ok"]},
        {"op": "request", "id": "r4", "model": "qwen3-8b", "attempts": ["peer-served:done:1:40"]},
        {"op": "request", "id": "r5", "model": "qwen3-8b", "attempts": ["cloud-ok"]},
        close("A"),
    ],
    {
        "A": [terminal("peer", "local"), terminal("local", "local"), terminal("cloud", "local"), terminal("peer", "peer"), terminal("cloud", "cloud")],
        "B": [],
    },
    show=["A", "B"], onlyKinds=["terminal"],
)
l02(
    "L02-019", "application bytes are 9 + payload: the worked encodings of LAB_SPEC 7.1 (HELLO with 7 payload bytes is 16, GOAWAY idle with 17 is 26, STATE_REQ with 7 is 16)",
    [dial(), fr("A", "HELLO", 7, stream="0"), fr("B", "HELLO_ACK", 200, stream="0"), fr("A", "STATE_REQ", 7, stream="3"), fr("A", "GOAWAY", 17, stream="0", code="idle"), close("A")],
    {
        "A": [r("control", None, "HELLO", 16, 0, 200, 1), r("control", None, "HELLO_ACK", 0, 209, 200, 1), r("control", None, "STATE_REQ", 16, 0, 200, 1), r("control", None, "GOAWAY:idle", 26, 0, 200, 1)],
        "B": [r("control", None, "HELLO", 0, 16, 200, 1), r("control", None, "HELLO_ACK", 209, 0, 200, 1), r("control", None, "STATE_REQ", 0, 16, 200, 1), r("control", None, "GOAWAY:idle", 0, 26, 200, 1)],
    },
    show=["A", "B"], onlyKinds=["control"],
)
l02(
    "L02-020", "two connections one after the other: two sessions, and each node's rows group into two",
    HS + [close("A")] + HS + [close("A")],
    {
        "A": H_A + [r("control", None, "HELLO", 309, 0, 200, 2), r("control", None, "HELLO_ACK", 0, 209, 200, 2)],
        "B": H_B + [r("control", None, "HELLO", 0, 309, 200, 2), r("control", None, "HELLO_ACK", 209, 0, 200, 2)],
        "groups": {"A": 2, "B": 2},
    },
    show=["A", "B", "groups"], onlyKinds=["control"],
)
l02(
    "L02-021", "an ERROR that carries an attemptId belongs to the attempt (no CONTROL row): the lender's outcome row carries its code, both outcome rows count its bytes",
    HS + [fr("A", "INFER_OFFER", 120, attempt="a1", request="r1"), fr("B", "ERROR", 45, attempt="a1", code="FRAME_TOO_LARGE"), fin("a1", 503, "FRAME_TOO_LARGE"), close("A")],
    {
        "A": H_A + [r("infer-sent", "intent", None, 0, None, 0, 1, a="a1"), r("infer-sent", "outcome", "FRAME_TOO_LARGE", 129, 54, 503, 1, a="a1")],
        "B": H_B + [r("infer-served", "outcome", "FRAME_TOO_LARGE", 54, 129, 503, 1, a="a1")],
    },
    show=["A", "B"], onlyKinds=["control", "infer-sent", "infer-served"],
)

write(
    "ledger", "L01-destination-sets.json", "L01",
    ["LAB_SPEC.md 6.2", "LAB_SPEC.md 6.3 (F1-F3)", "docs/design/mesh/contract.md 5.2", "ASOM_MESH_DESIGN.md 8.5, 8.6"],
    L01,
)
write(
    "policy", "W07-live-state.json", "W07",
    ["LAB_SPEC.md 6.5", "LAB_SPEC.md 7.2 (asom.state/1)", "docs/design/mesh/router.md 3.2, 3.5, Appendix A"],
    W07,
)
write(
    "policy", "W07p-presence.json", "W07p",
    ["LAB_SPEC.md 6.5 (LP-0..LP-2)", "LAB_SPEC.md 7.2 (lender decision table)", "ASOM_MESH_DESIGN.md 3.2, 7.4"],
    W07P,
)
write(
    "ledger", "L02-frame-rows.json", "L02",
    ["LAB_SPEC.md 7.5", "LAB_SPEC.md 7.6", "LAB_SPEC.md 7.7", "docs/design/mesh/REVIEW_ROUND3.md R3-CLOSURE-6", "ASOM_MESH_DESIGN.md 8.4"],
    L02,
)
