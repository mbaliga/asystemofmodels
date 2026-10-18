"""An independent Python model of the asom-mesh/1 frame layer (LAB_SPEC 7.1, 7.2), standard library only.

It was written from the spec text with struct.pack and Python's own json, ipaddress and base64 modules, and shares no code with the Kotlin lane
(lab/mesh-proto/src/main/kotlin/xyz/mdhv/asom/lab/proto/wire). It is used by w06_tool.py to compute vector expectations and to re-check the files.
Same-session, so it never clears the `oracle: self` tag (LAB_SPEC 4.10, R9).

Model of failure order: a decoder runs its checks as soon as the prefix that decides them has arrived: length bounds, unknown type, connection mode,
direction and payload limit (5 bytes), the stream rule (9 bytes), the payload's JSON profile (whole frame). Vectors written for this model are
single-fault, so the order among independent JSON faults is not exercised.
"""
import base64
import hashlib
import ipaddress
import json
import re
import struct

MIN_LEN = 5
MAX_LEN = 16_777_221
JSON_MAX = 1_048_576
BODY_MAX = 8_388_608
MAX_SAFE = 2 ** 53 - 1

# type -> (name, direction, stream rule, payload class)
TYPES = {
    0x01: ("HELLO", "c2s", "zero", "json"),
    0x02: ("HELLO_ACK", "s2c", "zero", "json"),
    0x05: ("GOAWAY", "both", "zero", "json"),
    0x06: ("ERROR", "both", "any", "json"),
    0x10: ("INFER_OFFER", "c2s", "odd", "json"),
    0x11: ("INFER_ACCEPT", "s2c", "odd", "json"),
    0x12: ("INFER_DECLINE", "s2c", "odd", "json"),
    0x13: ("INFER_BODY", "c2s", "odd", "body"),
    0x14: ("INFER_HEAD", "s2c", "odd", "json"),
    0x15: ("INFER_CHUNK", "s2c", "odd", "chunk"),
    0x16: ("INFER_END", "s2c", "odd", "json"),
    0x17: ("CANCEL", "c2s", "odd", "json"),
    0x20: ("STATE_REQ", "c2s", "odd", "json"),
    0x21: ("STATE", "s2c", "odd", "json"),
    0x22: ("MANIFEST_REQ", "c2s", "odd", "json"),
    0x23: ("MANIFEST", "s2c", "odd", "json"),
    0x30: ("PAIR_HELLO", "both", "pair", "json"),
    0x31: ("PAIR_CHALLENGE", "both", "pair", "json"),
    0x32: ("PAIR_DECISION", "both", "pair", "json"),
    0x33: ("PAIR_COMMIT", "both", "pair", "json"),
    0x34: ("PAIR_COMMIT_ACK", "both", "pair", "json"),
    0x40: ("REVOKE_NOTICE", "both", "zero", "json"),
}
BY_NAME = {v[0]: k for k, v in TYPES.items()}


class Refuse(Exception):
    def __init__(self, reason, code="PROTOCOL_ERROR"):
        super().__init__(reason)
        self.reason = reason
        self.code = code


# ------------------------------------------------------------------------------------------------------------------------------ framing
def pack_frame(t, stream, payload):
    return struct.pack(">IBI", 5 + len(payload), t, stream) + payload


def stream_ok(rule, s):
    return {"zero": s == 0, "odd": s % 2 == 1, "any": True, "pair": s == 1}[rule]


def payload_limit(cls):
    return {"json": JSON_MAX, "body": BODY_MAX, "chunk": MAX_LEN - MIN_LEN}[cls]


def encode_frame(t, stream, payload):
    """The producer: refuses what a mesh-1 sender must never emit."""
    if t >= 0x80:
        raise Refuse("EXTENSION_NOT_SENT")
    if t not in TYPES:
        raise Refuse("UNKNOWN_TYPE")
    name, _, rule, cls = TYPES[t]
    if not stream_ok(rule, stream):
        raise Refuse("STREAM_RULE")
    if len(payload) > payload_limit(cls):
        raise Refuse("PAYLOAD_LIMIT", "FRAME_TOO_LARGE")
    if cls == "json":
        strict_json_object(payload)
    return pack_frame(t, stream, payload)


def decode(data, receiver, mode="established"):
    """-> (events, failure). An event is ('FRAME', t, stream, payload) or ('EXT', t, stream, appBytes);
    failure is None or (code, reason, consumed)."""
    events = []
    pos = 0
    n = len(data)
    while pos < n:
        avail = n - pos
        if avail < 5:
            return events, ("PROTOCOL_ERROR", "TRUNCATED", avail)
        (length,) = struct.unpack_from(">I", data, pos)
        t = data[pos + 4]
        if length < MIN_LEN:
            return events, ("FRAME_TOO_LARGE", "LENGTH_BELOW_MIN", 5)
        if length > MAX_LEN:
            return events, ("FRAME_TOO_LARGE", "LENGTH_ABOVE_MAX", 5)
        plen = length - 5
        ext = t >= 0x80
        if not ext:
            if t not in TYPES:
                return events, ("PROTOCOL_ERROR", "UNKNOWN_TYPE", 5)
            name, direction, rule, cls = TYPES[t]
            is_pair = 0x30 <= t <= 0x34
            if mode == "established" and is_pair:
                return events, ("PROTOCOL_ERROR", "MODE_REJECTS_TYPE", 5)
            if mode == "pairing" and not (is_pair or t == 0x06):
                return events, ("PROTOCOL_ERROR", "MODE_REJECTS_TYPE", 5)
            if (direction == "c2s" and receiver != "server") or (direction == "s2c" and receiver != "client"):
                return events, ("PROTOCOL_ERROR", "WRONG_DIRECTION", 5)
            if plen > payload_limit(cls):
                return events, ("FRAME_TOO_LARGE", "PAYLOAD_LIMIT", 5)
        if avail < 9:
            return events, ("PROTOCOL_ERROR", "TRUNCATED", avail)
        (stream,) = struct.unpack_from(">I", data, pos + 5)
        if not ext and not stream_ok(rule, stream):
            return events, ("PROTOCOL_ERROR", "STREAM_RULE", 9)
        if avail < 9 + plen:
            return events, ("PROTOCOL_ERROR", "TRUNCATED", avail)
        payload = data[pos + 9: pos + 9 + plen]
        if ext:
            events.append(("EXT", t, stream, 9 + plen))
        else:
            if cls == "json":
                try:
                    strict_json_object(payload)
                except Refuse as e:
                    return events, ("PROTOCOL_ERROR", e.reason, 9 + plen)
            events.append(("FRAME", t, stream, payload))
        pos += 9 + plen
    return events, None


# ------------------------------------------------------------------------------------------------------------------------------ strict JSON
def _no_float(_s):
    raise Refuse("NON_INTEGER_NUMBER")


def _int(s):
    if s == "-0":
        raise Refuse("NON_INTEGER_NUMBER")
    v = int(s)
    if abs(v) > MAX_SAFE:
        raise Refuse("NUMBER_RANGE")
    return v


def _pairs(pairs):
    seen = set()
    for k, _ in pairs:
        if k in seen:
            raise Refuse("DUPLICATE_KEY")
        seen.add(k)
    return dict(pairs)


def _has_lone_surrogate(o):
    if isinstance(o, str):
        return any(0xD800 <= ord(c) <= 0xDFFF for c in o)
    if isinstance(o, dict):
        return any(_has_lone_surrogate(k) or _has_lone_surrogate(v) for k, v in o.items())
    if isinstance(o, list):
        return any(_has_lone_surrogate(v) for v in o)
    return False


def _depth(o):
    if isinstance(o, dict):
        return 1 + max((_depth(v) for v in o.values()), default=0)
    if isinstance(o, list):
        return 1 + max((_depth(v) for v in o), default=0)
    return 0


def strict_json(b):
    if b[:3] == b"\xef\xbb\xbf":
        raise Refuse("MALFORMED_JSON")
    text = b.decode("utf-8", errors="replace")
    bad_utf8 = False
    try:
        b.decode("utf-8")
    except UnicodeDecodeError:
        bad_utf8 = True
    dec = json.JSONDecoder(object_pairs_hook=_pairs, parse_float=_no_float, parse_int=_int, parse_constant=_no_float, strict=True)
    try:
        obj = dec.decode(text)
    except json.JSONDecodeError as e:
        raise Refuse("TRAILING_DATA" if e.msg == "Extra data" else "MALFORMED_JSON")
    except RecursionError:
        raise Refuse("MALFORMED_JSON")
    if bad_utf8 or _has_lone_surrogate(obj):
        raise Refuse("INVALID_UNICODE")
    if _depth(obj) > 16:
        raise Refuse("MALFORMED_JSON")
    return obj


def strict_json_object(b):
    o = strict_json(b)
    if not isinstance(o, dict):
        raise Refuse("NOT_AN_OBJECT")
    return o


def _utf16_key(s):
    return s.encode("utf-16-be")


def jcs(o):
    def canon(x):
        if isinstance(x, dict):
            return {k: canon(x[k]) for k in sorted(x, key=_utf16_key)}
        if isinstance(x, list):
            return [canon(v) for v in x]
        return x

    return json.dumps(canon(o), ensure_ascii=False, separators=(",", ":"))


# ------------------------------------------------------------------------------------------------------------------------------ field rules
def b64url_bytes(s, nbytes):
    nchars = {32: 43, 16: 22}[nbytes]
    if not isinstance(s, str) or len(s) != nchars or not re.fullmatch(r"[A-Za-z0-9_-]+", s):
        return False
    try:
        raw = base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))
    except Exception:
        return False
    return len(raw) == nbytes and base64.urlsafe_b64encode(raw).decode().rstrip("=") == s


def is_node_id(s):
    return b64url_bytes(s, 32)


def is_attempt_id(s):
    return b64url_bytes(s, 16)


def is_ip_literal(s):
    if "%" in s:
        return False
    try:
        ipaddress.ip_address(s)
        return True
    except ValueError:
        return False


MODEL_ID = re.compile(r"[A-Za-z0-9._:-]{1,128}\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
SOFTWARE = re.compile(r"[A-Za-z0-9._-]{1,32}/[A-Za-z0-9._+-]{1,32}\Z")


def is_name(s):
    if not 1 <= len(s) <= 32:
        return False
    return not any(ord(c) < 0x20 or 0x7F <= ord(c) <= 0x9F for c in s)


def count(v):
    return 0 <= v <= MAX_SAFE


def bad(reason):
    raise Refuse(reason)


class Rd:
    def __init__(self, o):
        self.o = o

    def _get(self, k):
        if k not in self.o:
            bad("MISSING_MEMBER")
        return self.o[k]

    def str(self, k):
        v = self._get(k)
        if not isinstance(v, str):
            bad("WRONG_TYPE")
        return v

    def str_opt(self, k):
        if k not in self.o:
            return None
        return self.str(k)

    def int(self, k):
        v = self._get(k)
        if isinstance(v, bool) or not isinstance(v, int):
            bad("WRONG_TYPE")
        return v

    def int_opt(self, k):
        if k not in self.o:
            return None
        return self.int(k)

    def bool(self, k):
        v = self._get(k)
        if not isinstance(v, bool):
            bad("WRONG_TYPE")
        return v

    def obj(self, k):
        v = self._get(k)
        if not isinstance(v, dict):
            bad("WRONG_TYPE")
        return Rd(v)

    def enum(self, k, allowed):
        v = self.str(k)
        if v not in allowed:
            bad("UNKNOWN_ENUM")
        return v

    def array(self, k):
        v = self._get(k)
        if not isinstance(v, list):
            bad("WRONG_TYPE")
        return v

    def known(self, k, allowed):
        out = set()
        for e in self.array(k):
            if not isinstance(e, str):
                bad("WRONG_TYPE")
            if e in allowed:
                out.add(e)
        return sorted(out)

    def endpoints(self, k):
        items = self.array(k)
        if len(items) > 4:
            bad("OUT_OF_RANGE")
        out = []
        for e in items:
            if not isinstance(e, dict):
                bad("WRONG_TYPE")
            r = Rd(e)
            addr = r.str("addr")
            port = r.int("port")
            via = r.enum("via", ("lan", "overlay"))
            if not 1 <= port <= 65535:
                bad("OUT_OF_RANGE")
            if not is_ip_literal(addr):
                bad("OUT_OF_RANGE")
            out.append({"addr": addr, "port": port, "via": via})
        return out

    def st(self):
        if "st" not in self.o:
            return None
        v = self.o["st"]
        if not isinstance(v, dict):
            bad("WRONG_TYPE")
        r = Rd(v)
        fsm = r.enum("fsm", ("OFF", "ARMED", "SERVING", "DRAINING"))
        gov = r.enum("gov", ("RUN", "QUEUE", "HOLD"))
        qb, seq, tb = r.int("qb"), r.int("seq"), r.int("tb")
        if qb not in (0, 1, 2) or tb not in (0, 1, 2) or seq < 1 or seq > MAX_SAFE:
            bad("OUT_OF_RANGE")
        return {"fsm": fsm, "gov": gov, "qb": qb, "seq": seq, "tb": tb}

    def version(self):
        if self.int("v") != 1:
            bad("BAD_VERSION")


FEATURES = ("infer.offer", "manifest", "state")
SCOPES = ("infer", "manifest", "state")
KEY_TIERS = ("strongbox", "tee", "secure-enclave", "tpm", "os-keystore", "file")
PLATFORMS = ("android", "ios", "ipados", "macos", "linux", "windows", "ubuntu-touch")
MESH_ERRORS = (
    "PEER_NOT_PAIRED", "SCOPE_DENIED", "PROTOCOL_ERROR", "VERSION_UNSUPPORTED", "FRAME_TOO_LARGE", "DUPLICATE_ATTEMPT", "CLOCK_SKEW", "MODEL_NOT_OFFERED",
    "PEER_BUSY", "PEER_UNAVAILABLE", "MANIFEST_UNAVAILABLE", "PAIRING_WINDOW_CLOSED", "PAIRING_PROOF_INVALID", "PAIRING_REFUSED",
)


def with_st(d, st):
    if st is not None:
        d["st"] = st
    return d


def parse_members(t, o, allow_unknown_code=True):
    """-> the normal form (a dict) of the typed message, from already strict-parsed members."""
    r = Rd(o)
    if t == 0x01:
        if r.str("proto") != "asom-mesh/1":
            bad("UNKNOWN_ENUM")
        eps = r.endpoints("endpoints")
        feats = r.known("features", FEATURES)
        tier = r.enum("keyTier", KEY_TIERS)
        min_v, max_v = r.int("minV"), r.int("maxV")
        name, node = r.str("name"), r.str("nodeId")
        plat = r.enum("platform", PLATFORMS)
        nonce, sw, ts, v = r.str("sessionNonce"), r.str("sw"), r.int("ts"), r.int("v")
        if not (1 <= min_v <= 255 and min_v <= max_v <= 255):
            bad("OUT_OF_RANGE")
        if not min_v <= v <= max_v:
            bad("BAD_VERSION")
        if not (is_name(name) and is_node_id(node) and b64url_bytes(nonce, 16) and SOFTWARE.match(sw) and count(ts)):
            bad("OUT_OF_RANGE")
        return {"endpoints": eps, "features": feats, "keyTier": tier, "maxV": max_v, "minV": min_v, "name": name, "nodeId": node, "platform": plat,
                "proto": "asom-mesh/1", "sessionNonce": nonce, "sw": sw, "ts": ts, "v": v}
    if t == 0x02:
        eps = r.endpoints("endpoints")
        feats = r.known("features", FEATURES)
        granted = r.known("granted", SCOPES)
        lim = r.obj("limits")
        limits = {k: lim.int(k) for k in ("idleUnloadMs", "maxBodyBytes", "maxConcurrent", "maxTokens", "rpm")}
        node = r.str("nodeId")
        st = r.st()
        ts, v = r.int("ts"), r.int("v")
        if not 1 <= v <= 255 or len(eps) > 4:
            bad("OUT_OF_RANGE")
        if not all(count(x) for x in limits.values()) or not is_node_id(node) or not count(ts):
            bad("OUT_OF_RANGE")
        return with_st({"endpoints": eps, "features": feats, "granted": granted, "limits": limits, "nodeId": node, "ts": ts, "v": v}, st)
    if t == 0x05:
        return {"reason": r.enum("reason", ("revoked", "suspended", "shutdown", "network-change", "idle", "max-age"))}
    if t == 0x06:
        code = r.str("code")
        stored = code if code in MESH_ERRORS else "UNKNOWN"
        att, retry = r.str_opt("attemptId"), r.int_opt("retryAfterMs")
        if (att is not None and not is_attempt_id(att)) or (retry is not None and not count(retry)):
            bad("OUT_OF_RANGE")
        d = {"code": stored}
        if att is not None:
            d["attemptId"] = att
        if retry is not None:
            d["retryAfterMs"] = retry
        return d
    if t == 0x10:
        att = r.str("attemptId")
        dl, est, mt = r.int("deadlineMs"), r.int("estTokensIn"), r.int("maxTokens")
        model = r.str("model")
        op = r.enum("op", ("chat", "completions", "embeddings"))
        pb, stream = r.int("promptBytes"), r.bool("stream")
        if not is_attempt_id(att) or not MODEL_ID.match(model) or not all(count(x) for x in (dl, est, mt, pb)):
            bad("OUT_OF_RANGE")
        return {"attemptId": att, "deadlineMs": dl, "estTokensIn": est, "maxTokens": mt, "model": model, "op": op, "promptBytes": pb, "stream": stream}
    if t == 0x11:
        att, sha, served = r.str("attemptId"), r.str("fileSha256"), r.str("servedModel")
        st = r.st()
        if not is_attempt_id(att) or not SHA256.match(sha) or not MODEL_ID.match(served):
            bad("OUT_OF_RANGE")
        return with_st({"attemptId": att, "fileSha256": sha, "servedModel": served}, st)
    if t == 0x12:
        att = r.str("attemptId")
        code = r.enum("code", ("PEER_BUSY", "PEER_UNAVAILABLE", "MODEL_NOT_OFFERED", "SCOPE_DENIED", "DUPLICATE_ATTEMPT"))
        retry = r.int("retryAfterMs")
        st = r.st()
        if not is_attempt_id(att) or not 5000 <= retry <= 600000:
            bad("OUT_OF_RANGE")
        return with_st({"attemptId": att, "code": code, "retryAfterMs": retry}, st)
    if t == 0x14:
        if r.str("engine") != "local":
            bad("UNKNOWN_ENUM")
        att, served, status = r.str("attemptId"), r.str("servedModel"), r.int("status")
        if not is_attempt_id(att) or not MODEL_ID.match(served) or not 100 <= status <= 599:
            bad("OUT_OF_RANGE")
        return {"attemptId": att, "engine": "local", "servedModel": served, "status": status}
    if t == 0x16:
        att, status = r.str("attemptId"), r.int("status")
        terminal = r.enum("terminal", ("done", "cancelled", "interrupted", "oom", "error"))
        st = r.st()
        if not is_attempt_id(att) or not 100 <= status <= 599:
            bad("OUT_OF_RANGE")
        return with_st({"attemptId": att, "status": status, "terminal": terminal}, st)
    if t == 0x17:
        att = r.str("attemptId")
        reason = r.enum("reason", ("client-gone", "deadline", "policy-changed", "superseded"))
        if not is_attempt_id(att):
            bad("OUT_OF_RANGE")
        return {"attemptId": att, "reason": reason}
    if t == 0x20:
        r.version()
        return {"v": 1}
    if t == 0x21:
        return parse_state(o)
    if t == 0x22:
        ch = r.str("challenge")
        r.version()
        if not b64url_bytes(ch, 32):
            bad("OUT_OF_RANGE")
        return {"challenge": ch, "v": 1}
    if t == 0x40:
        if r.str("reason") != "user":
            bad("UNKNOWN_ENUM")
        r.version()
        return {"reason": "user", "v": 1}
    bad("UNKNOWN_TYPE")


# ------------------------------------------------------------------------------------------------------------------------------ asom.state/1
BACKENDS = ("cpu", "vulkan", "metal", "opencl", "cuda", "hexagon")
BATTERY_BANDS = ("ge80", "50-79", "20-49", "lt20")
STATE_MEMBERS = {
    "": {"availability", "engine", "manifest", "power", "queue", "sampledAgeMs", "seq", "thermal", "v"},
    "availability": {"fsm"},
    "engine": {"backend", "commit", "confVersion", "held"},
    "manifest": {"bodyDigest", "seq"},
    "power": {"batteryBand", "charging", "source"},
    "queue": {"bucket"},
    "thermal": {"band", "governor"},
}
PRESENCE_NAMES = {
    "user", "active", "inflight", "busyForMs", "loaded", "estStartS", "estStartMs", "reason", "localActive", "depth", "queuePos", "ttftMs", "totalMs", "usage",
    "screen", "idle", "foreground", "console", "login", "keyguard", "batteryPermille", "availBytes", "memory", "headroomPermille", "forecastPermille",
}


def parse_state(o):
    r = Rd(o)
    if r.int("v") != 1:
        bad("BAD_VERSION")
    seq = r.int("seq")
    if seq < 1:
        bad("OUT_OF_RANGE")
    age = r.int("sampledAgeMs")
    if age < 0:
        bad("OUT_OF_RANGE")
    fsm = r.obj("availability").enum("fsm", ("OFF", "ARMED", "SERVING", "DRAINING"))
    power = r.obj("power")
    source = power.enum("source", ("ac", "battery", "unknown"))
    charging = power.bool("charging")
    if "batteryBand" not in power.o:
        bad("MISSING_MEMBER")
    bb = power.o["batteryBand"]
    if bb is None:
        band = None
    elif isinstance(bb, str):
        if bb not in BATTERY_BANDS:
            bad("UNKNOWN_ENUM")
        band = bb
    else:
        bad("WRONG_TYPE")
    th = r.obj("thermal")
    tb = th.int("band")
    if tb not in (0, 1, 2):
        bad("OUT_OF_RANGE")
    gov = th.enum("governor", ("RUN", "QUEUE", "HOLD"))
    eng = r.obj("engine")
    backend = eng.enum("backend", BACKENDS)
    commit = eng.str("commit")
    if not re.fullmatch(r"[0-9a-f]{7,40}", commit):
        bad("OUT_OF_RANGE")
    conf = eng.str("confVersion")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", conf):
        bad("OUT_OF_RANGE")
    held = eng.array("held")
    if len(held) > 64:
        bad("OUT_OF_RANGE")
    if any(not isinstance(h, str) for h in held):
        bad("WRONG_TYPE")
    if any(not re.fullmatch(r"[0-9a-f]{64}", h) for h in held):
        bad("OUT_OF_RANGE")
    qb = r.obj("queue").int("bucket")
    if qb not in (0, 1, 2):
        bad("OUT_OF_RANGE")
    if "manifest" not in r.o:
        bad("MISSING_MEMBER")
    m = r.o["manifest"]
    if m is None:
        manifest = None
    elif isinstance(m, dict):
        mr = Rd(m)
        mseq = mr.int("seq")
        if mseq < 1:
            bad("OUT_OF_RANGE")
        dig = mr.str("bodyDigest")
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", dig):
            bad("OUT_OF_RANGE")
        manifest = {"bodyDigest": dig, "seq": mseq}
    else:
        bad("WRONG_TYPE")
    return {
        "availability": {"fsm": fsm},
        "engine": {"backend": backend, "commit": commit, "confVersion": conf, "held": sorted(held)},
        "manifest": manifest,
        "power": {"batteryBand": band, "charging": charging, "source": source},
        "queue": {"bucket": qb},
        "sampledAgeMs": min(age, 60000),
        "seq": seq,
        "thermal": {"band": tb, "governor": gov},
        "v": 1,
    }


def producer_check(o):
    """-> None, 'PRESENCE_FIELD' or 'UNKNOWN_MEMBER' (the lab's own STATE producer must emit nothing else)."""
    unknown = [False]

    def walk(obj, path):
        allowed = STATE_MEMBERS.get(path)
        if allowed is None:
            return None
        for name, value in obj.items():
            if name not in allowed:
                if name in PRESENCE_NAMES:
                    return "PRESENCE_FIELD"
                unknown[0] = True
            elif isinstance(value, dict):
                r = walk(value, name)
                if r:
                    return r
        return None

    r = walk(o, "")
    if r:
        return r
    return "UNKNOWN_MEMBER" if unknown[0] else None


# ------------------------------------------------------------------------------------------------------------------------------ messages
def message_normal(t, payload):
    """-> ('json', normalDict) or ('opaque', None), refusing with Refuse. Raw types and PAIR_* are opaque; MANIFEST is its own JSON container."""
    if t in (0x13, 0x15) or 0x30 <= t <= 0x34:
        return "opaque", None
    o = strict_json_object(payload)
    if t == 0x23:
        return "json", o
    return "json", parse_members(t, o)


def sha(b):
    return hashlib.sha256(b).hexdigest()


def frame_event(t, stream, payload):
    return {"kind": "FRAME", "type": TYPES[t][0], "stream": stream, "appBytes": 9 + len(payload), "payloadLen": len(payload), "payloadSha256": sha(payload)}


def message_event(t, stream, payload):
    kind, normal = message_normal(t, payload)
    e = {"kind": "MESSAGE", "type": TYPES[t][0], "stream": stream, "appBytes": 9 + len(payload)}
    if kind == "json":
        e["normal"] = jcs(normal)
    else:
        e["payloadLen"] = len(payload)
        e["payloadSha256"] = sha(payload)
    return e


def ext_event(t, stream, app):
    return {"kind": "EXT_IGNORED", "type": t, "stream": stream, "appBytes": app}


def run_decode(data, receiver, mode, typed):
    """-> ('ok', {'events': [...]}) or ('reject', code, detail)."""
    events, failure = decode(data, receiver, mode)
    out = []
    for ev in events:
        if ev[0] == "EXT":
            out.append(ext_event(ev[1], ev[2], ev[3]))
            continue
        _, t, stream, payload = ev
        if not typed:
            out.append(frame_event(t, stream, payload))
            continue
        try:
            out.append(message_event(t, stream, payload))
        except Refuse as e:
            return ("reject", e.code, {"reason": e.reason, "framesBefore": len(out), "consumed": 9 + len(payload)})
    if failure:
        code, reason, consumed = failure
        return ("reject", code, {"reason": reason, "framesBefore": len(out), "consumed": consumed})
    return ("ok", {"events": out})


def encode_observation(frame_bytes, payload_json=None):
    o = {"appBytes": len(frame_bytes), "frameSha256": sha(frame_bytes)}
    if len(frame_bytes) <= 1024:
        o["hex"] = frame_bytes.hex()
    if payload_json is not None:
        o["payload"] = payload_json
    return o
