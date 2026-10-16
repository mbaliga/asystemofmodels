#!/usr/bin/env python3
"""W06 (frame codec) and W07 (STATE side) vectors: writer and independent checker. Python 3 standard library only.

    python3 lab/mesh-proto/tools/wire/w06_tool.py --write   [conformance-root]   # writes wire/W06-frames.json and wire/W07-state-frames.json
    python3 lab/mesh-proto/tools/wire/w06_tool.py --check   [conformance-root]   # re-derives every expectation from its input and compares
    python3 lab/mesh-proto/tools/wire/w06_tool.py --selftest                    # the spec's four worked encodings, byte for byte

Expectations come from w06_model.py (struct.pack framing, Python's json/ipaddress/base64), which shares no code with the Kotlin lane. The Kotlin
runner (`./gradlew -p lab :conformance-runner:run --args='lines W06'`) must then reproduce every one of them. Vectors are hand-designed (origin: hand);
this tool only computes their hex and normal forms. The tag stays oracle: self (LAB_SPEC 4.10): same session, so agreement is consistency, not independence.
"""
import base64
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import w06_model as m  # noqa: E402

CONF_VERSION = "0.2.0"


# ------------------------------------------------------------------------------------------------------------------------------ inputs
def expand(parts):
    out = bytearray()
    for p in parts:
        if "hex" in p:
            out += bytes.fromhex(p["hex"].replace(" ", ""))
        else:
            out += bytes.fromhex(p["repeat"]) * p["count"]
    return bytes(out)


def parse_type(x):
    if isinstance(x, int):
        return x
    if x in m.BY_NAME:
        return m.BY_NAME[x]
    return int(x, 16) if x.startswith("0x") else int(x)


def ranges(r):
    return r["minV"], r["maxV"]


def common_version(a, b):
    hi, lo = min(a[1], b[1]), max(a[0], b[0])
    return hi if hi >= lo else None


def reject(code, reason=None, **extra):
    d = {}
    if reason is not None:
        d["reason"] = reason
    d.update(extra)
    return {"reject": code}, (d or None)


# ------------------------------------------------------------------------------------------------------------------------------ derive
def derive(kind, inp):
    """-> (expect dict, detail dict or None)"""
    if kind == "frameEncode":
        t, stream, payload = parse_type(inp["type"]), inp["stream"], expand(inp["payload"])
        try:
            fb = m.encode_frame(t, stream, payload)
        except m.Refuse as e:
            return reject(e.code, e.reason)
        return {"ok": m.encode_observation(fb)}, None
    if kind == "messageEncode":
        t, stream = parse_type(inp["type"]), inp["stream"]
        try:
            if "members" in inp:
                normal = m.parse_members(t, inp["members"]) if t != 0x23 else inp["members"]
                if t == 0x06 and normal["code"] == "UNKNOWN":
                    raise m.Refuse("UNKNOWN_NOT_EMITTABLE")
                text = m.jcs(normal)
                fb = m.encode_frame(t, stream, text.encode("utf-8"))
                return {"ok": m.encode_observation(fb, text)}, None
            fb = m.encode_frame(t, stream, expand(inp["payload"]))
            return {"ok": m.encode_observation(fb)}, None
        except m.Refuse as e:
            return reject(e.code, e.reason)
    if kind in ("frameDecode", "messageDecode", "wireStateDecode"):
        r = m.run_decode(expand(inp["bytes"]), inp["receiver"], inp.get("mode", "established"), kind != "frameDecode")
        if r[0] == "ok":
            return {"ok": r[1]}, None
        return {"reject": r[1]}, r[2]
    if kind == "negotiate":
        v = common_version(ranges(inp["local"]), ranges(inp["peer"]))
        if v is None:
            return reject("VERSION_UNSUPPORTED")
        return {"ok": {"v": v}}, None
    if kind == "helloDecision":
        try:
            hello = m.parse_members(0x01, inp["hello"])
        except m.Refuse as e:
            return reject(e.code)
        if hello["nodeId"] != inp["tlsNodeId"]:
            return reject("PROTOCOL_ERROR")
        v = common_version(ranges(inp["local"]), (hello["minV"], hello["maxV"]))
        if v is None:
            return reject("VERSION_UNSUPPORTED")
        return {"ok": {"v": v, "features": sorted(set(hello["features"]) & set(inp["localFeatures"]))}}, None
    if kind == "ackDecision":
        try:
            ack = m.parse_members(0x02, inp["ack"])
        except m.Refuse as e:
            return reject(e.code)
        if ack["nodeId"] != inp["tlsNodeId"]:
            return reject("PROTOCOL_ERROR")
        lo, hi = ranges(inp["local"])
        if not lo <= ack["v"] <= hi:
            return reject("VERSION_UNSUPPORTED")
        return {"ok": {"v": ack["v"], "granted": ack["granted"], "limits": ack["limits"], "features": sorted(set(ack["features"]) & set(inp["localFeatures"]))}}, None
    if kind == "wireStateBuild":
        try:
            normal = m.parse_state(inp["state"])
            text = m.jcs(normal)
            fb = m.encode_frame(0x21, inp["stream"], text.encode("utf-8"))
        except m.Refuse as e:
            return reject(e.code, e.reason)
        return {"ok": m.encode_observation(fb, text)}, None
    if kind == "wireStateProducer":
        events, failure = m.decode(expand(inp["bytes"]), "client", "established")
        if failure:
            return reject(failure[0], failure[1], framesBefore=len(events), consumed=failure[2])
        o = m.strict_json_object(events[0][3])
        r = m.producer_check(o)
        if r is None:
            return {"ok": {"conforms": True}}, None
        return {"reject": r}, None
    raise SystemExit(f"unknown kind {kind}")


# ------------------------------------------------------------------------------------------------------------------------------ vectors
def b64u(b):
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


NODE_C = b64u(bytes(range(32)))
NODE_S = b64u(hashlib.sha256(b"asom lab node S").digest())
ATT1 = "AAAAAAAAAAAAAAAAAAAAAA"
ATT2 = b64u(hashlib.sha256(b"attempt 2").digest()[:16])
NONCE = b64u(hashlib.sha256(b"session nonce").digest()[:16])
CHAL = b64u(hashlib.sha256(b"challenge").digest())
SHA64 = hashlib.sha256(b"model file").hexdigest()
DIGEST43 = b64u(hashlib.sha256(b"manifest body").digest())

HELLO = {
    "endpoints": [{"addr": "192.168.1.20", "port": 11436, "via": "lan"}, {"addr": "fd7a:115c:a1e0::1", "port": 11436, "via": "overlay"}],
    "features": ["infer.offer", "manifest", "state"], "keyTier": "secure-enclave", "maxV": 1, "minV": 1, "name": "Madhav's iPhone", "nodeId": NODE_C,
    "platform": "ios", "proto": "asom-mesh/1", "sessionNonce": NONCE, "sw": "asom-ios/4.0.0", "ts": 1790000000000, "v": 1,
}
ST = {"fsm": "SERVING", "gov": "RUN", "qb": 0, "seq": 4711, "tb": 0}
LIMITS = {"idleUnloadMs": 300000, "maxBodyBytes": 8388608, "maxConcurrent": 1, "maxTokens": 4096, "rpm": 30}
ACK = {
    "endpoints": [{"addr": "192.168.1.40", "port": 11436, "via": "lan"}], "features": ["infer.offer", "manifest", "state"], "granted": ["infer", "manifest", "state"],
    "limits": LIMITS, "nodeId": NODE_S, "st": ST, "ts": 1790000000150, "v": 1,
}
OFFER = {"attemptId": ATT1, "deadlineMs": 60000, "estTokensIn": 1300, "maxTokens": 1024, "model": "qwen3-8b-q4", "op": "chat", "promptBytes": 5120, "stream": True}
STATE = {
    "availability": {"fsm": "SERVING"}, "engine": {"backend": "vulkan", "commit": "4f1c2ab", "confVersion": "1.0.0", "held": [SHA64]},
    "manifest": {"bodyDigest": DIGEST43, "seq": 17}, "power": {"batteryBand": None, "charging": False, "source": "ac"}, "queue": {"bucket": 0},
    "sampledAgeMs": 800, "seq": 4711, "thermal": {"band": 0, "governor": "RUN"}, "v": 1,
}


def without(d, *keys):
    return {k: v for k, v in d.items() if k not in keys}


def with_(d, **kw):
    out = dict(d)
    out.update(kw)
    return out


def jb(o):
    return json.dumps(o, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def spaced(fb):
    return {"hex": f"{fb[:4].hex()} {fb[4:5].hex()} {fb[5:9].hex()} {fb[9:].hex()}".strip()}


def fr(t, stream, payload):
    return m.pack_frame(t, stream, payload)


class Builder:
    def __init__(self, family, spec_refs):
        self.family, self.spec_refs, self.vectors, self.n = family, spec_refs, [], {}

    def add(self, series, kind, desc, inp, id_=None):
        self.n[series] = self.n.get(series, series - 1) + 1
        vid = id_ or f"{self.family}-{self.n[series]:03d}"
        expect, detail = derive(kind, inp)
        v = {"id": vid, "origin": "hand", "status": "normative", "oracle": "self", "description": desc, "input": dict(kind=kind, **inp), "expect": expect}
        if detail is not None:
            v["expectDetail"] = detail
        self.vectors.append(v)
        return v

    def doc(self):
        return {"family": self.family, "confVersion": CONF_VERSION, "specRefs": self.spec_refs, "vectors": self.vectors}


def hexp(b):
    return [{"hex": b.hex()}]


def dec(b, receiver, mode=None, typed=False):
    inp = {"bytes": b if isinstance(b, list) else [spaced(b)], "receiver": receiver}
    if mode:
        inp["mode"] = mode
    return inp


def build_w06():
    b = Builder("W06", ["LAB_SPEC.md 7.1", "LAB_SPEC.md 7.2", "ASOM_MESH_DESIGN.md 4.2 T15", "trust.md 3.3, 15 (frames/codec.json)"])
    add = b.add

    # --- the four worked encodings of LAB_SPEC 7.1 (series 1: ids W06-001..004 exactly) --------------------------------------------------------
    add(1, "frameEncode", "LAB_SPEC 7.1 worked encoding: HELLO {\"v\":1} on stream 0 (the trust.md example), 16 application bytes",
        {"type": "HELLO", "stream": 0, "payload": hexp(b'{"v":1}')})
    add(1, "frameEncode", "LAB_SPEC 7.1 worked encoding: GOAWAY {\"reason\":\"idle\"}, 26 application bytes",
        {"type": "GOAWAY", "stream": 0, "payload": hexp(b'{"reason":"idle"}')})
    add(1, "frameEncode", "LAB_SPEC 7.1 worked encoding: STATE_REQ {\"v\":1} on stream 3, 16 application bytes",
        {"type": "STATE_REQ", "stream": 3, "payload": hexp(b'{"v":1}')})
    add(1, "frameEncode", "LAB_SPEC 7.1 worked encoding: CANCEL on stream 5, 67 bytes in total (63 length + 4)",
        {"type": "CANCEL", "stream": 5, "payload": hexp(jb({"attemptId": ATT1, "reason": "deadline"}))})

    # --- a golden encoding for every frame type (series 10) -------------------------------------------------------------------------------------
    g = 10
    gold = [
        ("HELLO", 0, HELLO, "HELLO with two endpoints (IPv4 lan, IPv6 overlay), all features"),
        ("HELLO", 0, with_(HELLO, name="Dell \U0001F5A5 tower", endpoints=[], features=["state"], keyTier="tpm", platform="windows", sw="asom-desktop/2.5.0+b1"),
         "HELLO with an astral code point in the name and no endpoints"),
        ("HELLO_ACK", 0, ACK, "HELLO_ACK with the st digest and one endpoint"),
        ("HELLO_ACK", 0, without(ACK, "st"), "HELLO_ACK without st (a peer without scope state)"),
        ("GOAWAY", 0, {"reason": "revoked"}, "GOAWAY revoked"),
        ("GOAWAY", 0, {"reason": "max-age"}, "GOAWAY max-age"),
        ("ERROR", 7, {"attemptId": ATT1, "code": "PEER_BUSY", "retryAfterMs": 5000}, "ERROR with attemptId and retryAfterMs, on stream 7"),
        ("ERROR", 0, {"code": "VERSION_UNSUPPORTED"}, "ERROR on stream 0 with only a code"),
        ("INFER_OFFER", 1, OFFER, "INFER_OFFER on stream 1"),
        ("INFER_OFFER", 9, with_(OFFER, op="embeddings", stream=False, attemptId=ATT2), "INFER_OFFER embeddings, non-stream, stream 9"),
        ("INFER_ACCEPT", 1, {"attemptId": ATT1, "fileSha256": SHA64, "servedModel": "qwen3-8b-q4", "st": ST}, "INFER_ACCEPT with st (no queuePos, no estStartMs)"),
        ("INFER_ACCEPT", 1, {"attemptId": ATT1, "fileSha256": SHA64, "servedModel": "qwen3-8b-q4"}, "INFER_ACCEPT without st"),
        ("INFER_DECLINE", 1, {"attemptId": ATT1, "code": "PEER_UNAVAILABLE", "retryAfterMs": 600000, "st": with_(ST, fsm="DRAINING", gov="HOLD", qb=2, tb=2)},
         "INFER_DECLINE at the upper retry bound"),
        ("INFER_DECLINE", 1, {"attemptId": ATT1, "code": "MODEL_NOT_OFFERED", "retryAfterMs": 5000}, "INFER_DECLINE at the lower retry bound"),
        ("INFER_HEAD", 1, {"attemptId": ATT1, "engine": "local", "servedModel": "qwen3-8b-q4", "status": 200}, "INFER_HEAD"),
        ("INFER_END", 1, {"attemptId": ATT1, "st": ST, "status": 200, "terminal": "done"}, "INFER_END done with st (no usage, ttftMs or totalMs)"),
        ("INFER_END", 1, {"attemptId": ATT1, "status": 502, "terminal": "interrupted"}, "INFER_END interrupted"),
        ("CANCEL", 5, {"attemptId": ATT1, "reason": "client-gone"}, "CANCEL client-gone on stream 5"),
        ("STATE_REQ", 3, {"v": 1}, "STATE_REQ on stream 3"),
        ("STATE", 3, STATE, "STATE (asom.state/1) with a manifest and a held model"),
        ("STATE", 3, with_(STATE, manifest=None, engine=with_(STATE["engine"], held=[]), power={"batteryBand": "50-79", "charging": True, "source": "battery"}),
         "STATE with no manifest, no held models and a battery band"),
        ("MANIFEST_REQ", 5, {"challenge": CHAL, "v": 1}, "MANIFEST_REQ on stream 5"),
        ("MANIFEST", 5, {"payload": "e30", "payloadType": "application/vnd.asom.manifest+json", "signatures": [{"keyid": NODE_S, "sig": "AAAA"}]},
         "MANIFEST carries a container object, opaque to the wire layer"),
        ("REVOKE_NOTICE", 0, {"reason": "user", "v": 1}, "REVOKE_NOTICE"),
    ]
    for name, stream, members, desc in gold:
        add(g, "messageEncode", f"golden: {desc}", {"type": name, "stream": stream, "members": members})
    add(g, "messageEncode", "golden: INFER_BODY raw bytes (the OpenAI body after normalisation), stream 1",
        {"type": "INFER_BODY", "stream": 1, "payload": hexp(jb({"messages": [{"content": "hi", "role": "user"}], "model": "qwen3-8b-q4", "stream": True}))})
    add(g, "messageEncode", "golden: INFER_CHUNK raw SSE event bytes, stream 1",
        {"type": "INFER_CHUNK", "stream": 1, "payload": hexp(b'data: {"choices":[{"delta":{"content":"Hi"}}]}\n\n')})
    for k, t in enumerate(["PAIR_HELLO", "PAIR_CHALLENGE", "PAIR_DECISION", "PAIR_COMMIT", "PAIR_COMMIT_ACK"]):
        add(g, "messageEncode", f"golden: {t} frame type number with a raw opaque payload on stream 1 (bodies belong to the pairing track)",
            {"type": t, "stream": 1, "payload": hexp(jb({"v": 1, "n": k}))})

    # --- producer refusals (series 60) -----------------------------------------------------------------------------------------------------------
    s = 60
    add(s, "frameEncode", "a mesh-1 sender never sends an extension type (0x80)", {"type": "0x80", "stream": 0, "payload": hexp(b"{}")})
    add(s, "frameEncode", "a mesh-1 sender never sends an extension type (0xFF)", {"type": "0xFF", "stream": 0, "payload": hexp(b"{}")})
    add(s, "frameEncode", "a retired type (0x03) is unknown and never sent", {"type": "0x03", "stream": 0, "payload": hexp(b"{}")})
    add(s, "frameEncode", "a JSON frame carrying a float is refused by the producer", {"type": "STATE_REQ", "stream": 1, "payload": hexp(b'{"v":1.5}')})
    add(s, "frameEncode", "a JSON frame that is not an object is refused by the producer", {"type": "STATE_REQ", "stream": 1, "payload": hexp(b"[]")})
    add(s, "frameEncode", "JSON payload of exactly 1,048,576 bytes is accepted",
        {"type": "STATE_REQ", "stream": 1, "payload": [{"hex": b'{"v":1}'.hex()}, {"repeat": "20", "count": 1048576 - 7}]})
    add(s, "frameEncode", "JSON payload of 1,048,577 bytes is refused, not truncated",
        {"type": "STATE_REQ", "stream": 1, "payload": [{"hex": b'{"v":1}'.hex()}, {"repeat": "20", "count": 1048577 - 7}]})
    add(s, "frameEncode", "INFER_BODY of exactly 8,388,608 bytes is accepted", {"type": "INFER_BODY", "stream": 1, "payload": [{"repeat": "61", "count": 8388608}]})
    add(s, "frameEncode", "INFER_BODY of 8,388,609 bytes is refused", {"type": "INFER_BODY", "stream": 1, "payload": [{"repeat": "61", "count": 8388609}]})
    add(s, "frameEncode", "HELLO on stream 1 breaks the stream rule", {"type": "HELLO", "stream": 1, "payload": hexp(b"{}")})
    add(s, "frameEncode", "STATE_REQ on an even stream is a parity violation", {"type": "STATE_REQ", "stream": 2, "payload": hexp(b'{"v":1}')})
    add(s, "frameEncode", "STATE_REQ on stream 0 is a parity violation", {"type": "STATE_REQ", "stream": 0, "payload": hexp(b'{"v":1}')})
    add(s, "frameEncode", "PAIR_HELLO outside stream 1", {"type": "PAIR_HELLO", "stream": 3, "payload": hexp(b"{}")})
    add(s, "messageEncode", "a received-only UNKNOWN error code can never be emitted",
        {"type": "ERROR", "stream": 0, "members": {"code": "NOT_A_MESH_CODE"}})
    add(s, "messageEncode", "HELLO with five endpoints is refused (at most 4)",
        {"type": "HELLO", "stream": 0, "members": with_(HELLO, endpoints=[{"addr": f"10.0.0.{i}", "port": 11436, "via": "lan"} for i in range(1, 6)])})
    add(s, "messageEncode", "INFER_DECLINE retryAfterMs 4999 is below the 5000 floor",
        {"type": "INFER_DECLINE", "stream": 1, "members": {"attemptId": ATT1, "code": "PEER_BUSY", "retryAfterMs": 4999}})
    add(s, "messageEncode", "INFER_DECLINE retryAfterMs 600001 is above the 600000 ceiling",
        {"type": "INFER_DECLINE", "stream": 1, "members": {"attemptId": ATT1, "code": "PEER_BUSY", "retryAfterMs": 600001}})
    add(s, "messageEncode", "STATE_REQ on stream 2 is refused", {"type": "STATE_REQ", "stream": 2, "members": {"v": 1}})
    add(s, "messageEncode", "INFER_OFFER on stream 0 is refused", {"type": "INFER_OFFER", "stream": 0, "members": OFFER})
    add(s, "messageEncode", "HELLO_ACK on stream 1 is refused", {"type": "HELLO_ACK", "stream": 1, "members": ACK})

    # --- frame decode: length bounds (series 100) ------------------------------------------------------------------------------------------------
    d = 100
    hello_min = fr(0x01, 0, b'{"v":1}')
    add(d, "frameDecode", "the four worked encodings back to back (a client's bytes, received by a server): 125 application bytes",
        dec([spaced(fr(0x01, 0, b'{"v":1}')), spaced(fr(0x05, 0, b'{"reason":"idle"}')), spaced(fr(0x20, 3, b'{"v":1}')),
             spaced(fr(0x17, 5, jb({"attemptId": ATT1, "reason": "deadline"})))], "server"))
    add(d, "frameDecode", "no bytes at all: no events, no failure", {"bytes": [], "receiver": "server"})
    add(d, "frameDecode", "length 4 is below the minimum of 5: FRAME_TOO_LARGE and close", dec([{"hex": "00000004 01 00000000"}], "server"))
    add(d, "frameDecode", "length 0", dec([{"hex": "00000000 01 00000000"}], "server"))
    add(d, "frameDecode", "length 5 on a JSON type means an empty payload, which is not JSON",
        dec([{"hex": "00000005 01 00000000"}], "server"))
    add(d, "frameDecode", "length 5 on a raw type (INFER_CHUNK) is a valid empty chunk", dec([{"hex": "00000005 15 00000001"}], "client"))
    add(d, "frameDecode", "length 6: a one byte raw payload", dec([{"hex": "00000006 13 00000001 7b"}], "server"))
    add(d, "frameDecode", "length 16,777,221 (the maximum) on a raw type with a 16 MiB payload is accepted: 16,777,225 application bytes",
        dec([{"hex": "01000005 15 00000001"}, {"repeat": "00", "count": 16777216}], "client"))
    add(d, "frameDecode", "length 16,777,222 is above the maximum: FRAME_TOO_LARGE after five bytes", dec([{"hex": "01000006 15 00000001"}], "client"))
    add(d, "frameDecode", "length 0xFFFFFFFF is read as unsigned and refused", dec([{"hex": "ffffffff 15 00000001"}], "client"))
    add(d, "frameDecode", "length 0x80000000 is read as unsigned and refused", dec([{"hex": "80000000 01 00000000"}], "server"))
    add(d, "frameDecode", "length 16,777,221 on a JSON type exceeds the JSON payload limit", dec([{"hex": "01000005 01 00000000"}], "server"))
    add(d, "frameDecode", "an extension at the maximum length is skipped: 16,777,225 application bytes, one EXT_IGNORED event",
        dec([{"hex": "01000005 80 00000000"}, {"repeat": "ee", "count": 16777216}], "server"))
    add(d, "frameDecode", "an extension just above the maximum length is refused like any other frame", dec([{"hex": "01000006 80 00000000"}], "server"))

    # --- stream parity (series 120) --------------------------------------------------------------------------------------------------------------
    d = 150
    add(d, "frameDecode", "HELLO on stream 1: connection-level frames use stream 0", dec(fr(0x01, 1, b'{"v":1}'), "server"))
    add(d, "frameDecode", "STATE_REQ on an even stream 2 (server-opened streams never carry a request)", dec(fr(0x20, 2, b'{"v":1}'), "server"))
    add(d, "frameDecode", "STATE_REQ on stream 0", dec(fr(0x20, 0, b'{"v":1}'), "server"))
    add(d, "frameDecode", "INFER_OFFER on stream 0", dec(fr(0x10, 0, jb(OFFER)), "server"))
    add(d, "frameDecode", "GOAWAY on stream 1", dec(fr(0x05, 1, b'{"reason":"idle"}'), "client"))
    add(d, "frameDecode", "REVOKE_NOTICE on stream 3", dec(fr(0x40, 3, b'{"reason":"user","v":1}'), "server"))
    add(d, "frameDecode", "INFER_CHUNK on even stream 2", dec(fr(0x15, 2, b"data: x\n\n"), "client"))
    add(d, "frameDecode", "INFER_HEAD on stream 0", dec(fr(0x14, 0, jb({"attemptId": ATT1, "engine": "local", "servedModel": "m", "status": 200})), "client"))
    add(d, "frameDecode", "STATE_REQ on stream 4294967295 (the largest odd u32) is accepted", dec(fr(0x20, 0xFFFFFFFF, b'{"v":1}'), "server"))
    add(d, "frameDecode", "STATE_REQ on stream 4294967294 (the largest even u32) is refused", dec(fr(0x20, 0xFFFFFFFE, b'{"v":1}'), "server"))
    add(d, "frameDecode", "ERROR may use any stream: 0, 1, 2 and 4294967295", dec([spaced(fr(0x06, s, b'{"code":"PROTOCOL_ERROR"}')) for s in (0, 1, 2, 0xFFFFFFFF)], "client"))
    add(d, "frameDecode", "a request on a server-opened (even) stream is refused even when other frames before it were fine",
        dec([spaced(fr(0x20, 1, b'{"v":1}')), spaced(fr(0x20, 2, b'{"v":1}'))], "server"))
    add(d, "frameDecode", "HELLO received by a TLS client travels the wrong way (HELLO is client to server)", dec(fr(0x01, 0, b'{"v":1}'), "client"))
    add(d, "frameDecode", "STATE_REQ received by a TLS client", dec(fr(0x20, 1, b'{"v":1}'), "client"))
    add(d, "frameDecode", "HELLO_ACK received by a TLS server", dec(fr(0x02, 0, jb(ACK)), "server"))
    add(d, "frameDecode", "INFER_HEAD received by a TLS server", dec(fr(0x14, 1, b"{}"), "server"))
    add(d, "frameDecode", "PAIR_HELLO on stream 1 of a pairing connection is accepted (opaque)", dec(fr(0x30, 1, b'{"v":1}'), "server", "pairing"))
    add(d, "frameDecode", "a PAIR frame on stream 3 of a pairing connection", dec(fr(0x30, 3, b'{"v":1}'), "server", "pairing"))
    add(d, "frameDecode", "a PAIR frame on stream 0 of a pairing connection", dec(fr(0x31, 0, b'{"v":1}'), "client", "pairing"))
    add(d, "frameDecode", "a PAIR frame on an established connection", dec(fr(0x30, 1, b'{"v":1}'), "server"))
    add(d, "frameDecode", "HELLO on a pairing connection: only PAIR_* (and ERROR) are accepted", dec(fr(0x01, 0, b'{"v":1}'), "server", "pairing"))
    add(d, "frameDecode", "STATE_REQ on a pairing connection", dec(fr(0x20, 1, b'{"v":1}'), "server", "pairing"))
    add(d, "frameDecode", "ERROR on a pairing connection carries the refusal and is accepted", dec(fr(0x06, 1, b'{"code":"PAIRING_REFUSED"}'), "client", "pairing"))
    add(d, "frameDecode", "all five PAIR types on stream 1 of a pairing connection",
        dec([spaced(fr(t, 1, b'{"v":1}')) for t in range(0x30, 0x35)], "server", "pairing"))

    # --- unknown types, retired types, extensions (series 140) -----------------------------------------------------------------------------------
    d = 200
    for t, why in [(0x00, "type 0x00"), (0x03, "retired PING (0x03)"), (0x04, "retired PONG (0x04)"), (0x41, "retired REVOCATION_HINT (0x41)"),
                   (0x42, "retired LOCATOR_HINTS (0x42)"), (0x24, "placement PLACE_REQ (0x24), not scheduled"), (0x25, "placement PLACE (0x25), not scheduled"),
                   (0x07, "unassigned 0x07"), (0x18, "unassigned 0x18"), (0x35, "unassigned 0x35, just past PAIR_COMMIT_ACK"), (0x43, "unassigned 0x43"),
                   (0x7F, "0x7F, the last type below the extension range")]:
        add(d, "frameDecode", f"unknown type below 0x80: {why} is ERROR PROTOCOL_ERROR and close", dec(fr(t, 0, b"{}"), "server"))
    add(d, "frameDecode", "an unknown type with a bad stream and an over-limit payload still fails on the type first",
        dec([{"hex": "00200000 7f 00000002"}], "server"))
    add(d, "frameDecode", "extension type 0x80 is skipped and surfaces as EXT_IGNORED", dec(fr(0x80, 0, b"abc"), "server"))
    add(d, "frameDecode", "extension type 0xFF is skipped and surfaces as EXT_IGNORED", dec(fr(0xFF, 0, b"xyz"), "client"))
    add(d, "frameDecode", "an extension with an empty payload", dec(fr(0x80, 0, b""), "server"))
    add(d, "frameDecode", "an extension is skipped whatever its stream parity and payload bytes", dec(fr(0xA5, 2, b"\xff\xfe not json {"), "server"))
    add(d, "frameDecode", "an extension between two frames: the frames around it are delivered",
        dec([spaced(fr(0x01, 0, b'{"v":1}')), spaced(fr(0x90, 7, b"\x00\x01")), spaced(fr(0x20, 1, b'{"v":1}'))], "server"))
    add(d, "frameDecode", "two extensions in a row", dec([spaced(fr(0x80, 0, b"a")), spaced(fr(0xFF, 4, b"bb"))], "server"))
    add(d, "frameDecode", "an extension is accepted on a pairing connection too", dec(fr(0x81, 0, b"q"), "server", "pairing"))

    # --- truncation (series 160) -----------------------------------------------------------------------------------------------------------------
    d = 250
    full = fr(0x01, 0, b'{"v":1}')
    add(d, "frameDecode", "three bytes of a length prefix: TRUNCATED", dec([{"hex": full[:3].hex()}], "server"))
    add(d, "frameDecode", "five bytes (length and type) of a valid frame: TRUNCATED", dec([{"hex": full[:5].hex()}], "server"))
    add(d, "frameDecode", "eight bytes: the stream id is cut short", dec([{"hex": full[:8].hex()}], "server"))
    add(d, "frameDecode", "the nine byte header alone, payload missing", dec([{"hex": full[:9].hex()}], "server"))
    add(d, "frameDecode", "payload cut one byte short", dec([{"hex": full[:-1].hex()}], "server"))
    add(d, "frameDecode", "two good frames then a third cut inside its payload",
        dec([spaced(full), spaced(fr(0x20, 3, b'{"v":1}')), {"hex": fr(0x20, 5, b'{"v":1}')[:12].hex()}], "server"))
    add(d, "frameDecode", "an extension cut inside its payload", dec([{"hex": fr(0x80, 0, b"abcdef")[:12].hex()}], "server"))
    add(d, "frameDecode", "five bytes with an unknown type: the type fails before the truncation is noticed", dec([{"hex": "0000000c 03"}], "server"))
    add(d, "frameDecode", "five bytes with a below-minimum length", dec([{"hex": "00000003 01"}], "server"))

    # --- strict JSON at the frame layer (series 170) ---------------------------------------------------------------------------------------------
    d = 300
    j = lambda text, **kw: dec(fr(0x20, 1, text if isinstance(text, bytes) else text.encode("utf-8")), "server", **kw)
    add(d, "frameDecode", "a float in a JSON payload", j('{"v":1.5}'))
    add(d, "frameDecode", "an exponent number is not an integer", j('{"v":1e2}'))
    add(d, "frameDecode", "negative zero", j('{"v":-0}'))
    add(d, "frameDecode", "NaN", j('{"v":NaN}'))
    add(d, "frameDecode", "an integer beyond 2^53 - 1", j('{"v":9007199254740992}'))
    add(d, "frameDecode", "2^53 - 1 itself is accepted", j('{"v":9007199254740991}'))
    add(d, "frameDecode", "a duplicate member name", j('{"v":1,"v":1}'))
    add(d, "frameDecode", "text that is not JSON", j("hello"))
    add(d, "frameDecode", "JSON cut short", j('{"v":'))
    add(d, "frameDecode", "a byte order mark", j(b'\xef\xbb\xbf{"v":1}'))
    add(d, "frameDecode", "data after the value", j('{"v":1}x'))
    add(d, "frameDecode", "invalid UTF-8 inside a string", j(b'{"v":"\xff"}'))
    add(d, "frameDecode", "a lone surrogate escape", j('{"v":"\\ud800"}'))
    add(d, "frameDecode", "a surrogate pair escape is fine", j('{"v":"\\ud83d\\ude00"}'))
    add(d, "frameDecode", "nesting depth 17 is refused", j('{"a":' + "[" * 16 + "1" + "]" * 16 + "}"))
    add(d, "frameDecode", "nesting depth 16 is accepted", j('{"a":' + "[" * 15 + "1" + "]" * 15 + "}"))
    add(d, "frameDecode", "a top-level array is not a JSON frame payload", j("[]"))
    add(d, "frameDecode", "a top-level number is not a JSON frame payload", j("5"))
    add(d, "frameDecode", "whitespace and non-canonical member order are accepted (receivers do not require JCS)", j('  { "z" : 1 ,\n "a" : 2 }\n'))
    add(d, "frameDecode", "a raw type carries any bytes, JSON or not", dec(fr(0x15, 1, b"\xff\xfe\x00 not json"), "client"))
    add(d, "frameDecode", "PAIR payload must still be a strict JSON object at the frame layer", dec(fr(0x30, 1, b'{"v":1.5}'), "server", "pairing"))

    # --- size limits (series 190) ----------------------------------------------------------------------------------------------------------------
    d = 350
    add(d, "frameDecode", "JSON payload of exactly 1,048,576 bytes is accepted: 1,048,585 application bytes",
        dec([{"hex": "%08x 20 00000001" % (5 + 1048576)}, {"hex": b'{"v":1}'.hex()}, {"repeat": "20", "count": 1048576 - 7}], "server"))
    add(d, "frameDecode", "JSON payload of 1,048,577 bytes is refused with FRAME_TOO_LARGE after five bytes, never truncated",
        dec([{"hex": "%08x 20 00000001" % (5 + 1048577)}, {"hex": b'{"v":1}'.hex()}, {"repeat": "20", "count": 1048577 - 7}], "server"))
    add(d, "frameDecode", "INFER_BODY of exactly 8,388,608 bytes is accepted: 8,388,617 application bytes",
        dec([{"hex": "%08x 13 00000001" % (5 + 8388608)}, {"repeat": "61", "count": 8388608}], "server"))
    add(d, "frameDecode", "INFER_BODY of 8,388,609 bytes is refused",
        dec([{"hex": "%08x 13 00000001" % (5 + 8388609)}, {"repeat": "61", "count": 8388609}], "server"))
    add(d, "frameDecode", "an INFER_CHUNK larger than the INFER_BODY limit is fine: only the frame maximum applies to chunks",
        dec([{"hex": "%08x 15 00000001" % (5 + 8388609)}, {"repeat": "62", "count": 8388609}], "client"))

    # --- message layer: decode every golden frame back (series 200) ------------------------------------------------------------------------------
    e = 400
    recv = {0x01: "server", 0x02: "client", 0x05: "server", 0x06: "client", 0x10: "server", 0x11: "client", 0x12: "client", 0x13: "server", 0x14: "client",
            0x15: "client", 0x16: "client", 0x17: "server", 0x20: "server", 0x21: "client", 0x22: "server", 0x23: "client", 0x40: "server"}
    for name, stream, members, desc in gold:
        t = m.BY_NAME[name]
        normal = members if t == 0x23 else m.parse_members(t, members)
        add(e, "messageDecode", f"round trip: {desc}", dec(fr(t, stream, m.jcs(normal).encode()), recv[t]))
    add(e, "messageDecode", "round trip: INFER_BODY is opaque raw bytes", dec(fr(0x13, 1, b'{"model":"x","messages":[]}'), "server"))
    add(e, "messageDecode", "round trip: INFER_CHUNK is opaque raw bytes", dec(fr(0x15, 1, b'data: {"x":1}\n\n'), "client"))
    add(e, "messageDecode", "round trip: PAIR_* on a pairing connection are opaque", dec([spaced(fr(0x30, 1, b'{"v":1,"z":2}')), spaced(fr(0x34, 1, b'{"v":1}'))], "client", "pairing"))
    add(e, "messageDecode", "a received HELLO is parsed without requiring JCS: reordered, spaced, with unknown members and unknown features, all dropped",
        dec(fr(0x01, 0, (json.dumps(with_(HELLO, zzz={"a": [1, 2]}, features=["state", "telepathy", "infer.offer"]), indent=1)).encode()), "server"))
    add(e, "messageDecode", "a HELLO_ACK ignores unknown granted scopes and unknown members",
        dec(fr(0x02, 0, jb(with_(ACK, granted=["infer", "revoke-hint", "state"], extra=True))), "client"))
    add(e, "messageDecode", "a HELLO name of exactly 32 code points (astral ones count once)", dec(fr(0x01, 0, jb(with_(HELLO, name="\U0001F5A5" * 32))), "server"))
    add(e, "messageDecode", "an endpoint with an IPv4-mapped IPv6 literal and one with an upper-case IPv6 literal",
        dec(fr(0x01, 0, jb(with_(HELLO, endpoints=[{"addr": "::ffff:10.0.0.1", "port": 1, "via": "lan"}, {"addr": "FD7A:115C::A", "port": 65535, "via": "overlay"}]))), "server"))

    # --- ERROR: no message member (series 230) ---------------------------------------------------------------------------------------------------
    e = 450
    add(e, "messageDecode", "a received ERROR with a message member: the message is ignored and stored nowhere",
        dec(fr(0x06, 0, jb({"code": "PEER_BUSY", "message": "SECRET-PEER-TEXT-4711 <script>alert(1)</script>", "retryAfterMs": 7000})), "client"))
    add(e, "messageDecode", "a received ERROR with an unknown code is stored as UNKNOWN and handled as PROTOCOL_ERROR",
        dec(fr(0x06, 0, jb({"code": "PEER_ON_FIRE", "message": "SECRET-PEER-TEXT-4711"})), "client"))
    add(e, "messageDecode", "a received ERROR with a numeric code", dec(fr(0x06, 0, jb({"code": 7})), "client"))
    add(e, "messageDecode", "a received ERROR with no code", dec(fr(0x06, 0, jb({"message": "x"})), "client"))
    add(e, "messageDecode", "a received ERROR with a float retryAfterMs", dec(fr(0x06, 0, b'{"code":"PEER_BUSY","retryAfterMs":1.5}'), "client"))
    add(e, "messageDecode", "a received ERROR with a malformed attemptId", dec(fr(0x06, 3, jb({"attemptId": "AAAA", "code": "PEER_BUSY"})), "client"))
    add(e, "messageDecode", "a received ERROR with a negative retryAfterMs", dec(fr(0x06, 0, jb({"code": "PEER_BUSY", "retryAfterMs": -1})), "client"))
    add(e, "messageDecode", "each MeshError code is accepted and kept (14 codes)",
        dec([spaced(fr(0x06, 0, jb({"code": c}))) for c in m.MESH_ERRORS], "client"))
    add(e, "messageDecode", "a code in the wrong case is not a MeshError", dec(fr(0x06, 0, jb({"code": "peer_busy"})), "client"))

    # --- schema negatives (series 250) -----------------------------------------------------------------------------------------------------------
    e = 500
    h = lambda **kw: dec(fr(0x01, 0, jb(with_(HELLO, **kw))), "server")
    hd = lambda *keys: dec(fr(0x01, 0, jb(without(HELLO, *keys))), "server")
    add(e, "messageDecode", "HELLO without nodeId", hd("nodeId"))
    add(e, "messageDecode", "HELLO without sessionNonce", hd("sessionNonce"))
    add(e, "messageDecode", "HELLO whose nodeId has 42 characters", h(nodeId=NODE_C[:-1]))
    add(e, "messageDecode", "HELLO whose nodeId has 44 characters", h(nodeId=NODE_C + "A"))
    add(e, "messageDecode", "HELLO whose nodeId has non-zero unused bits", h(nodeId=NODE_C[:-1] + "B"))
    add(e, "messageDecode", "HELLO whose nodeId uses the standard base64 alphabet", h(nodeId="A" * 41 + "+A"))
    add(e, "messageDecode", "HELLO whose nodeId is padded", h(nodeId=NODE_C + "="))
    add(e, "messageDecode", "HELLO whose sessionNonce has 21 characters", h(sessionNonce=NONCE[:-1]))
    add(e, "messageDecode", "HELLO with the wrong proto", h(proto="asom-mesh/2"))
    add(e, "messageDecode", "HELLO with an unknown platform", h(platform="beos"))
    add(e, "messageDecode", "HELLO with an unknown keyTier", h(keyTier="hsm"))
    add(e, "messageDecode", "HELLO with every keyTier and platform value is accepted (seven platforms)",
        dec([spaced(fr(0x01, 0, jb(with_(HELLO, platform=p, keyTier=t)))) for p, t in zip(m.PLATFORMS, (m.KEY_TIERS + ("file",)))], "server"))
    add(e, "messageDecode", "HELLO with five endpoints", h(endpoints=[{"addr": f"10.0.0.{i}", "port": 1, "via": "lan"} for i in range(5)]))
    add(e, "messageDecode", "HELLO with four endpoints is accepted", h(endpoints=[{"addr": f"10.0.0.{i}", "port": 1, "via": "lan"} for i in range(4)]))
    add(e, "messageDecode", "HELLO with a DNS name as an endpoint address", h(endpoints=[{"addr": "example.com", "port": 11436, "via": "lan"}]))
    add(e, "messageDecode", "HELLO with a bracketed IPv6 address", h(endpoints=[{"addr": "[::1]", "port": 11436, "via": "lan"}]))
    add(e, "messageDecode", "HELLO with an IPv6 zone identifier", h(endpoints=[{"addr": "fe80::1%eth0", "port": 11436, "via": "lan"}]))
    add(e, "messageDecode", "HELLO with an IPv4 address with a leading zero", h(endpoints=[{"addr": "010.0.0.1", "port": 11436, "via": "lan"}]))
    add(e, "messageDecode", "HELLO with an IPv4 octet above 255", h(endpoints=[{"addr": "10.0.0.256", "port": 11436, "via": "lan"}]))
    add(e, "messageDecode", "HELLO with port 0", h(endpoints=[{"addr": "10.0.0.1", "port": 0, "via": "lan"}]))
    add(e, "messageDecode", "HELLO with port 65536", h(endpoints=[{"addr": "10.0.0.1", "port": 65536, "via": "lan"}]))
    add(e, "messageDecode", "HELLO with an unknown via", h(endpoints=[{"addr": "10.0.0.1", "port": 1, "via": "wan"}]))
    add(e, "messageDecode", "HELLO endpoint that is not an object", h(endpoints=["10.0.0.1"]))
    add(e, "messageDecode", "HELLO with a 33 code point name", h(name="n" * 33))
    add(e, "messageDecode", "HELLO with an empty name", h(name=""))
    add(e, "messageDecode", "HELLO with a control character in the name", h(name="a\u0007b"))
    add(e, "messageDecode", "HELLO with a negative ts", h(ts=-1))
    add(e, "messageDecode", "HELLO with minV above maxV", h(minV=2, maxV=1))
    add(e, "messageDecode", "HELLO with v outside [minV, maxV]", h(v=2))
    add(e, "messageDecode", "HELLO with minV 0", h(minV=0))
    add(e, "messageDecode", "HELLO with v as a string", h(v="1"))
    add(e, "messageDecode", "HELLO with features that is not an array", h(features="state"))
    add(e, "messageDecode", "HELLO with a non-string feature", h(features=["state", 3]))
    add(e, "messageDecode", "HELLO with a malformed sw", h(sw="no-version"))
    add(e, "messageDecode", "GOAWAY with an unknown reason", dec(fr(0x05, 0, b'{"reason":"overheated"}'), "client"))
    add(e, "messageDecode", "GOAWAY with every reason (six)", dec([spaced(fr(0x05, 0, jb({"reason": r}))) for r in ("revoked", "suspended", "shutdown", "network-change", "idle", "max-age")], "client"))
    o = lambda **kw: dec(fr(0x10, 1, jb(with_(OFFER, **kw))), "server")
    add(e, "messageDecode", "INFER_OFFER with an unknown op", o(op="edit"))
    add(e, "messageDecode", "INFER_OFFER with a model id containing a space", o(model="my model"))
    add(e, "messageDecode", "INFER_OFFER with a 129 character model id", o(model="m" * 129))
    add(e, "messageDecode", "INFER_OFFER with a 128 character model id is accepted", o(model="m" * 128))
    add(e, "messageDecode", "INFER_OFFER with stream as a string", o(stream="true"))
    add(e, "messageDecode", "INFER_OFFER with a negative promptBytes", o(promptBytes=-1))
    add(e, "messageDecode", "INFER_OFFER without maxTokens", dec(fr(0x10, 1, jb(without(OFFER, "maxTokens"))), "server"))
    add(e, "messageDecode", "INFER_OFFER with a 23 character attemptId", o(attemptId=ATT1 + "A"))
    add(e, "messageDecode", "INFER_OFFER with the forbidden members dataClass and retain: ignored, never stored",
        o(dataClass="D1", retain="owner-verbose", extra={"nested": [1]}))
    add(e, "messageDecode", "INFER_ACCEPT with the removed members queuePos and estStartMs: ignored, never stored",
        dec(fr(0x11, 1, jb({"attemptId": ATT1, "fileSha256": SHA64, "servedModel": "qwen3-8b-q4", "queuePos": 2, "estStartMs": 1500})), "client"))
    add(e, "messageDecode", "INFER_ACCEPT with an upper-case fileSha256", dec(fr(0x11, 1, jb({"attemptId": ATT1, "fileSha256": SHA64.upper(), "servedModel": "m"})), "client"))
    add(e, "messageDecode", "INFER_ACCEPT with a 63 digit fileSha256", dec(fr(0x11, 1, jb({"attemptId": ATT1, "fileSha256": SHA64[:-1], "servedModel": "m"})), "client"))
    dc = lambda **kw: dec(fr(0x12, 1, jb(with_({"attemptId": ATT1, "code": "PEER_BUSY", "retryAfterMs": 5000}, **kw))), "client")
    add(e, "messageDecode", "INFER_DECLINE with the retired code PEER_THERMAL", dc(code="PEER_THERMAL"))
    add(e, "messageDecode", "INFER_DECLINE retryAfterMs 4999", dc(retryAfterMs=4999))
    add(e, "messageDecode", "INFER_DECLINE retryAfterMs 600001", dc(retryAfterMs=600001))
    add(e, "messageDecode", "INFER_DECLINE with every allowed code", dec([spaced(fr(0x12, 1, jb({"attemptId": ATT1, "code": c, "retryAfterMs": 5000})))
                                                                      for c in ("PEER_BUSY", "PEER_UNAVAILABLE", "MODEL_NOT_OFFERED", "SCOPE_DENIED", "DUPLICATE_ATTEMPT")], "client"))
    add(e, "messageDecode", "INFER_DECLINE without retryAfterMs", dec(fr(0x12, 1, jb({"attemptId": ATT1, "code": "PEER_BUSY"})), "client"))
    add(e, "messageDecode", "INFER_HEAD with engine remote", dec(fr(0x14, 1, jb({"attemptId": ATT1, "engine": "remote", "servedModel": "m", "status": 200})), "client"))
    add(e, "messageDecode", "INFER_HEAD with status 99", dec(fr(0x14, 1, jb({"attemptId": ATT1, "engine": "local", "servedModel": "m", "status": 99})), "client"))
    add(e, "messageDecode", "INFER_END with the removed members usage, ttftMs and totalMs: ignored, never stored",
        dec(fr(0x16, 1, jb({"attemptId": ATT1, "status": 200, "terminal": "done", "usage": {"promptTokens": 1}, "ttftMs": 5, "totalMs": 90})), "client"))
    add(e, "messageDecode", "INFER_END with the retired terminal thermal", dec(fr(0x16, 1, jb({"attemptId": ATT1, "status": 200, "terminal": "thermal"})), "client"))
    add(e, "messageDecode", "INFER_END with every terminal (five)",
        dec([spaced(fr(0x16, 1, jb({"attemptId": ATT1, "status": 200, "terminal": t}))) for t in ("done", "cancelled", "interrupted", "oom", "error")], "client"))
    add(e, "messageDecode", "CANCEL with an unknown reason", dec(fr(0x17, 1, jb({"attemptId": ATT1, "reason": "bored"})), "server"))
    add(e, "messageDecode", "CANCEL with every reason (four)", dec([spaced(fr(0x17, 1, jb({"attemptId": ATT1, "reason": r}))) for r in ("client-gone", "deadline", "policy-changed", "superseded")], "server"))
    add(e, "messageDecode", "STATE_REQ with v 2", dec(fr(0x20, 1, b'{"v":2}'), "server"))
    add(e, "messageDecode", "STATE_REQ without v", dec(fr(0x20, 1, b"{}"), "server"))
    add(e, "messageDecode", "STATE_REQ with unknown members", dec(fr(0x20, 1, b'{"v":1,"want":"everything"}'), "server"))
    add(e, "messageDecode", "MANIFEST_REQ with a 44 character challenge", dec(fr(0x22, 1, jb({"challenge": CHAL + "A", "v": 1})), "server"))
    add(e, "messageDecode", "MANIFEST_REQ without v", dec(fr(0x22, 1, jb({"challenge": CHAL})), "server"))
    add(e, "messageDecode", "REVOKE_NOTICE with an unknown reason", dec(fr(0x40, 0, b'{"reason":"lost","v":1}'), "server"))
    add(e, "messageDecode", "MANIFEST whose payload is not an object", dec(fr(0x23, 1, b"[]"), "client"))
    ak = lambda **kw: dec(fr(0x02, 0, jb(with_(ACK, **kw))), "client")
    add(e, "messageDecode", "HELLO_ACK with st.qb 3", ak(st=with_(ST, qb=3)))
    add(e, "messageDecode", "HELLO_ACK with st.fsm SLEEPING", ak(st=with_(ST, fsm="SLEEPING")))
    add(e, "messageDecode", "HELLO_ACK with st null", ak(st=None))
    add(e, "messageDecode", "HELLO_ACK with st.seq 0", ak(st=with_(ST, seq=0)))
    add(e, "messageDecode", "HELLO_ACK without limits", dec(fr(0x02, 0, jb(without(ACK, "limits"))), "client"))
    add(e, "messageDecode", "HELLO_ACK with a negative limit", ak(limits=with_(LIMITS, rpm=-1)))
    add(e, "messageDecode", "HELLO_ACK without idleUnloadMs", ak(limits=without(LIMITS, "idleUnloadMs")))
    add(e, "messageDecode", "HELLO_ACK with v 0", ak(v=0))

    # --- the version rule (series 300) -----------------------------------------------------------------------------------------------------------
    n = 650
    for lo, peer, why in [((1, 1), (1, 1), "both speak only 1"), ((1, 3), (2, 5), "the highest common v of [1,3] and [2,5] is 3"),
                          ((2, 4), (1, 3), "[2,4] and [1,3] share 2 and 3: the highest is 3"), ((1, 2), (2, 2), "[1,2] and [2,2] meet at 2"),
                          ((3, 3), (1, 3), "[3,3] and [1,3] meet at 3"), ((1, 255), (255, 255), "the top of the range"),
                          ((1, 1), (1, 255), "an older node and a newer one still speak 1")]:
        add(n, "negotiate", f"version rule: {why}", {"local": {"minV": lo[0], "maxV": lo[1]}, "peer": {"minV": peer[0], "maxV": peer[1]}})
    for lo, peer, why in [((1, 1), (2, 2), "[1,1] and [2,2] share nothing"), ((2, 4), (1, 1), "a node with minV 2 never downgrades to 1"),
                          ((1, 2), (3, 4), "disjoint ranges"), ((5, 9), (1, 4), "ranges that touch only below minV")]:
        add(n, "negotiate", f"version rule: {why}: ERROR VERSION_UNSUPPORTED and close", {"local": {"minV": lo[0], "maxV": lo[1]}, "peer": {"minV": peer[0], "maxV": peer[1]}})
    hello_in = {"local": {"minV": 1, "maxV": 1}, "localFeatures": ["infer.offer", "state"], "hello": HELLO, "tlsNodeId": NODE_C}
    add(n, "helloDecision", "server: a HELLO whose nodeId equals the TLS identity and whose range overlaps is established at v 1, with the feature intersection", hello_in)
    add(n, "helloDecision", "server: a HELLO whose nodeId differs from the TLS identity is PROTOCOL_ERROR", with_(hello_in, tlsNodeId=NODE_S))
    add(n, "helloDecision", "server: no common version is VERSION_UNSUPPORTED", with_(hello_in, hello=with_(HELLO, minV=2, maxV=2, v=2)))
    add(n, "helloDecision", "server: a nodeId mismatch is reported before a version mismatch", with_(hello_in, tlsNodeId=NODE_S, hello=with_(HELLO, minV=2, maxV=2, v=2)))
    add(n, "helloDecision", "server: a HELLO with unknown members and a newer range still negotiates 1",
        with_(hello_in, hello=with_(HELLO, minV=1, maxV=4, v=4, zzz=1)))
    add(n, "helloDecision", "server: a malformed HELLO is PROTOCOL_ERROR", with_(hello_in, hello=without(HELLO, "nodeId")))
    ack_in = {"local": {"minV": 1, "maxV": 1}, "localFeatures": ["manifest", "state"], "ack": ACK, "tlsNodeId": NODE_S}
    add(n, "ackDecision", "client: a HELLO_ACK for the server's TLS identity at v 1 is established, with granted scopes, limits and the feature intersection", ack_in)
    add(n, "ackDecision", "client: a HELLO_ACK for a different nodeId is PROTOCOL_ERROR", with_(ack_in, tlsNodeId=NODE_C))
    add(n, "ackDecision", "client: a HELLO_ACK v outside the client's range is VERSION_UNSUPPORTED", with_(ack_in, ack=with_(ACK, v=2)))
    add(n, "ackDecision", "client: a HELLO_ACK at v 3 is accepted by a client whose range is [2,4]", with_(ack_in, local={"minV": 2, "maxV": 4}, ack=with_(ACK, v=3)))
    return b.doc()


def build_w07():
    b = Builder("W07", ["LAB_SPEC.md 7.2 (asom.state/1, STATE frame)", "LAB_SPEC.md 6.5", "ASOM_MESH_DESIGN.md 7.4 (LP-1)"])
    add = b.add
    s = 300
    add(s, "wireStateBuild", "the lab's STATE builder, spec example, as a frame on stream 3", {"stream": 3, "state": STATE})
    add(s, "wireStateBuild", "producer-strict: a state document carrying every presence field still builds a frame without any of them (the builder has no way to emit one)",
        {"stream": 3, "state": with_(STATE, user={"active": True}, inflight=3, busyForMs=1200, loaded=["qwen3-8b-q4"], estStartS=4, reason="game",
                                     engine=with_(STATE["engine"], loaded=["x"]), queue={"bucket": 1, "depth": 4, "queuePos": 2})})
    add(s, "wireStateBuild", "producer-strict: held models are emitted sorted", {"stream": 1, "state": with_(STATE, engine=with_(STATE["engine"], held=["f" * 64, "0" * 64, SHA64]))})
    add(s, "wireStateBuild", "the builder refuses a document the receiver would refuse (seq 0)", {"stream": 3, "state": with_(STATE, seq=0)})
    add(s, "wireStateBuild", "the builder refuses an unknown enum (fsm SLEEPING)", {"stream": 3, "state": with_(STATE, availability={"fsm": "SLEEPING"})})
    add(s, "wireStateBuild", "the builder clamps nothing silently below the limit and clamps sampledAgeMs to 60000 above it", {"stream": 3, "state": with_(STATE, sampledAgeMs=90000)})
    add(s, "wireStateBuild", "STATE goes on an odd stream: stream 2 is refused", {"stream": 2, "state": STATE})
    s = 320
    st_frame = lambda payload, stream=3: dec(fr(0x21, stream, payload), "client")
    add(s, "wireStateDecode", "a STATE frame in JCS form decodes to the same normal form", st_frame(m.jcs(m.parse_state(STATE)).encode()))
    add(s, "wireStateDecode", "a received STATE with presence fields is accepted and the fields are never stored",
        st_frame(jb(with_(STATE, user={"active": True}, inflight=3, busyForMs=1200, loaded=["x"], estStartS=4, reason="game"))))
    add(s, "wireStateDecode", "a received STATE with unknown members at every level ignores them", st_frame(jb(with_(STATE, zzz=1, power=with_(STATE["power"], x=1), thermal=with_(STATE["thermal"], y=[1])))))
    add(s, "wireStateDecode", "a received STATE with a float seq is refused by the JSON profile", st_frame(jb(STATE).replace(b'"seq":4711', b'"seq":4711.5')))
    add(s, "wireStateDecode", "a received STATE with a float in a nested member", st_frame(jb(STATE).replace(b'"band":0', b'"band":0.0')))
    add(s, "wireStateDecode", "a received STATE with a duplicate member", st_frame(jb(STATE).replace(b'"v":1}', b'"v":1,"v":1}')))
    add(s, "wireStateDecode", "a received STATE with an unknown enum", st_frame(jb(with_(STATE, thermal={"band": 0, "governor": "SPRINT"}))))
    add(s, "wireStateDecode", "a received STATE with v 2", st_frame(jb(with_(STATE, v=2))))
    add(s, "wireStateDecode", "a received STATE with a missing member", st_frame(jb(without(STATE, "queue"))))
    add(s, "wireStateDecode", "a received STATE with band 3", st_frame(jb(with_(STATE, thermal={"band": 3, "governor": "RUN"}))))
    add(s, "wireStateDecode", "a received STATE with sampledAgeMs above 60000 is accepted and clamped", st_frame(jb(with_(STATE, sampledAgeMs=900000))))
    add(s, "wireStateDecode", "a STATE frame on an even stream", st_frame(jb(STATE), 2))
    add(s, "wireStateDecode", "a STATE frame received by a TLS server", dec(fr(0x21, 3, jb(STATE)), "server"))
    add(s, "wireStateDecode", "a STATE_REQ followed by its STATE reply (two directions, two receivers: the reply is read by the client)", st_frame(jb(STATE), 5))
    s = 340
    add(s, "wireStateProducer", "producer check: the lab's own STATE conforms", dec(fr(0x21, 3, m.jcs(m.parse_state(STATE)).encode()), "client"))
    add(s, "wireStateProducer", "producer check: user is a presence field", dec(fr(0x21, 3, jb(with_(STATE, user={"active": True}))), "client"))
    add(s, "wireStateProducer", "producer check: inflight is a presence field", dec(fr(0x21, 3, jb(with_(STATE, inflight=3))), "client"))
    add(s, "wireStateProducer", "producer check: busyForMs is a presence field", dec(fr(0x21, 3, jb(with_(STATE, busyForMs=100))), "client"))
    add(s, "wireStateProducer", "producer check: loaded inside engine is a presence field", dec(fr(0x21, 3, jb(with_(STATE, engine=with_(STATE["engine"], loaded=["m"])))), "client"))
    add(s, "wireStateProducer", "producer check: estStartS is a presence field", dec(fr(0x21, 3, jb(with_(STATE, estStartS=4))), "client"))
    add(s, "wireStateProducer", "producer check: reason is a presence field", dec(fr(0x21, 3, jb(with_(STATE, reason="game"))), "client"))
    add(s, "wireStateProducer", "producer check: queuePos inside queue is a presence field", dec(fr(0x21, 3, jb(with_(STATE, queue={"bucket": 0, "queuePos": 1}))), "client"))
    add(s, "wireStateProducer", "producer check: any other addition is an unknown member", dec(fr(0x21, 3, jb(with_(STATE, zzz=1))), "client"))
    add(s, "wireStateProducer", "producer check: a float never reaches the check, the frame layer refuses it", dec(fr(0x21, 3, jb(STATE).replace(b'"seq":4711', b'"seq":47.5')), "client"))
    return b.doc()


def write_json(path, doc):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(doc, indent=2, ensure_ascii=False) + "\n")


def selftest():
    """LAB_SPEC 7.1: the four worked encodings, byte for byte (the CANCEL row is printed in full there as 67 bytes)."""
    want = [
        ("HELLO", 0, b'{"v":1}', "0000000c0100000000" + "7b2276223a317d", 16),
        ("GOAWAY", 0, b'{"reason":"idle"}', "0000001605" + "00000000" + "7b22726561736f6e223a2269646c65227d", 26),
        ("STATE_REQ", 3, b'{"v":1}', "0000000c2000000003" + "7b2276223a317d", 16),
    ]
    for name, stream, payload, hx, app in want:
        fb = m.encode_frame(m.BY_NAME[name], stream, payload)
        assert fb.hex() == hx and len(fb) == app, (name, fb.hex())
    cancel = m.encode_frame(0x17, 5, jb({"attemptId": ATT1, "reason": "deadline"}))
    assert cancel.hex().startswith("0000003f1700000005" + "7b22617474656d70744964223a22414141") and len(cancel) == 67
    print("selftest: the four worked encodings of LAB_SPEC 7.1 match byte for byte (HELLO 16, GOAWAY 26, STATE_REQ 16, CANCEL 67 bytes in total)")


def main(argv):
    root = next((a for a in argv[1:] if not a.startswith("--")), None) or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "conformance")
    selftest()
    if "--selftest" in argv:
        return 0
    targets = {os.path.join(root, "wire", "W06-frames.json"): build_w06, os.path.join(root, "wire", "W07-state-frames.json"): build_w07}
    if "--write" in argv:
        for path, fn in targets.items():
            doc = fn()
            write_json(path, doc)
            print(f"wrote {os.path.relpath(path)}: {len(doc['vectors'])} vectors")
        return 0
    agree = disagree = 0
    for path in targets:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        fam_agree = 0
        for v in doc["vectors"]:
            inp = dict(v["input"])
            kind = inp.pop("kind")
            expect, detail = derive(kind, inp)
            got_detail = v.get("expectDetail")
            if expect == v["expect"] and detail == got_detail:
                agree += 1
                fam_agree += 1
            else:
                disagree += 1
                print(f"  DISAGREE {v['id']}: python derives {json.dumps(expect)[:200]} {detail} but the file says {json.dumps(v['expect'])[:200]} {got_detail}", file=sys.stderr)
        print(f"xcheck-wire {doc['family']} ({os.path.basename(path)}): {fam_agree} of {len(doc['vectors'])} agree")
    print(f"xcheck-wire total: {agree} agree, {disagree} disagree")
    return 1 if disagree else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
