#!/usr/bin/env python3
"""xcheck for the manifest families (LAB_SPEC 4.6-4.9, 4.11): a second, hand-written implementation in Python 3 standard
library only (plus the `openssl` command when it is on PATH), written to be read from the LAB_SPEC text and NOT from the Kotlin.

  M01der  the DER <-> raw signature codec and low-S normalisation (M01-2xx vectors in conformance/manifest/M01-der-raw.json)
  M02     accept vectors: the container / DSSE / ES256 layer (steps 1-10), the sigLayer facts, the key-id and fingerprint rules
  M03     reject vectors: the first failing step and code among steps 1-10 (a vector whose expected step is 11 or later must
          pass steps 1-10 here), and every `sigLayer.verifies` fact, checked with a pure-Python P-256 verifier AND, for
          every vector whose signature is 64 octets, with `openssl dgst -sha256 -verify`
  M05     the verification header of the manifest renderings (signer line, date line, device line), ASCII/LF/72-column laws,
          the text sha256, and that the file-form renderings share the export's tail
  M06     q2, the public derivative (allow-list re-derivation from the verified payload) and the FILE projection (structural)

It shares parse(), jcs() and b64() with lab/json/tools/xcheck_m01.py (the :json track's own Python implementation).
What it cannot do: steps 11-19 (the typed decoder, consistency(), derivation, roles, rollback) are Kotlin-only; the JSON-Schema
oracle (schema_oracle.py) and the derive reference (bench_ref.py, through xcheck_m04.py) cover parts of them. It was written
in the same session as the Kotlin module, so its agreement shows consistency, never independence, and does NOT clear the
`oracle: self` tag (LAB_SPEC 4.10, R9).
"""
import base64
import datetime
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
LAB = os.path.normpath(os.path.join(HERE, "..", ".."))

_spec = importlib.util.spec_from_file_location("xcheck_m01", os.path.join(LAB, "json", "tools", "xcheck_m01.py"))
M01 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(M01)

P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
A = P - 3
B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
G = (0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
     0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5)
SPKI_PREFIX = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200")
MAX_CONTAINER = 524288
MAX_PAYLOAD = 262144
PT = "application/vnd.asom.manifest.v1+json"


# ------------------------------------------------------------------ P-256, pure Python
def _add(p1, p2):
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    x1, y1 = p1
    x2, y2 = p2
    if x1 == x2:
        if (y1 + y2) % P == 0:
            return None
        lam = (3 * x1 * x1 + A) * pow(2 * y1, -1, P) % P
    else:
        lam = (y2 - y1) * pow(x2 - x1, -1, P) % P
    x3 = (lam * lam - x1 - x2) % P
    return (x3, (lam * (x1 - x3) - y1) % P)


def _mul(k, pt):
    r = None
    while k:
        if k & 1:
            r = _add(r, pt)
        pt = _add(pt, pt)
        k >>= 1
    return r


def on_curve(x, y):
    return 0 <= x < P and 0 <= y < P and (y * y - (x * x * x + A * x + B)) % P == 0


def strict_spki(spki):
    """The (x, y) of a strict 91-byte SPKI, or None (LAB_SPEC 4.5)."""
    if len(spki) != 91 or spki[:26] != SPKI_PREFIX or spki[26] != 4:
        return None
    x = int.from_bytes(spki[27:59], "big")
    y = int.from_bytes(spki[59:91], "big")
    return (x, y) if on_curve(x, y) else None


def ecdsa_verify(pub, msg, raw):
    r = int.from_bytes(raw[:32], "big")
    s = int.from_bytes(raw[32:], "big")
    if not (1 <= r < N and 1 <= s < N):
        return False
    z = int.from_bytes(hashlib.sha256(msg).digest(), "big")
    w = pow(s, -1, N)
    pt = _add(_mul(z * w % N, G), _mul(r * w % N, pub))
    return pt is not None and pt[0] % N == r


def pae(ptype, payload):
    t = ptype.encode("utf-8")
    return b"DSSEv1 " + str(len(t)).encode() + b" " + t + b" " + str(len(payload)).encode() + b" " + payload


def b64u(b):
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def node_id(spki):
    return b64u(hashlib.sha256(spki).digest())


def b32(b):
    return base64.b32encode(b).decode().rstrip("=")


def export_fp_plain(spki):
    return b32(hashlib.sha256(spki).digest()[:16])


def export_fp(spki):
    f = export_fp_plain(spki)
    return "-".join([f[0:5], f[5:10], f[10:14], f[14:18], f[18:22], f[22:26]])


def display_fp(spki):
    t = b32(hashlib.sha256(spki).digest())[:16]
    return "-".join(t[i:i + 4] for i in range(0, 16, 4))


def load_json(path):
    with open(path, "rb") as f:
        return json.loads(f.read().decode("utf-8"))


def test_only_ids(root):
    path = os.path.join(root, "keys", "TEST-ONLY-keys.json")
    d = load_json(path)
    ids = set()
    for k, v in d.items():
        if isinstance(v, dict) and "spki_b64" in v:
            ids.add(node_id(base64.b64decode(v["spki_b64"])))
    return ids


# ------------------------------------------------------------------ steps 1-10, independent
class Steps:
    """Returns (code, step) for the first failing step among 1-10, else None; also `.spki`, `.sig`, `.payload`."""

    def __init__(self, deny):
        self.deny = deny

    def run(self, doc, ctx):
        self.spki = self.sig = self.payload = None
        self.ptype = PT
        if len(doc) > MAX_CONTAINER:
            return ("TOO_LARGE", "1")
        kind, c = M01.parse(doc)
        if kind == "reject":
            return (c, "2")
        if not isinstance(c, dict) or type(c.get("asomCapabilityManifest")) is not int or c["asomCapabilityManifest"] != 1:
            return ("CONTAINER_VERSION_UNKNOWN", "3")
        d = c.get("dsse")
        if not isinstance(d, dict) or not isinstance(d.get("payloadType"), str) or not isinstance(d.get("payload"), str) or not isinstance(d.get("signatures"), list):
            return ("CONTAINER_INVALID", "3")
        ptype = d["payloadType"]
        if ptype != PT:
            import re
            m = re.fullmatch(r"application/vnd\.asom\.manifest\.v([0-9]+)\+json", ptype)
            return ("SCHEMA_MAJOR_UNKNOWN", "4") if m and int(m.group(1)) > 1 else ("PAYLOAD_TYPE_UNSUPPORTED", "4")
        sigs = d["signatures"]
        if len(sigs) != 1:
            return ("SIGNATURE_COUNT", "5")
        s0 = sigs[0]
        if not isinstance(s0, dict) or not isinstance(s0.get("sig"), str):
            return ("CONTAINER_INVALID", "5")
        if "keyid" in s0 and not isinstance(s0["keyid"], str):
            return ("CONTAINER_INVALID", "5")
        keyid = s0.get("keyid")
        payload = M01.b64(d["payload"], True)
        if payload is None:
            return ("ENCODING", "6")
        sig = M01.b64(s0["sig"], True)
        if sig is None:
            return ("ENCODING", "6")
        if len(sig) != 64:
            return ("SIGNATURE_ENCODING", "6")
        if len(payload) > MAX_PAYLOAD:
            return ("TOO_LARGE", "6")
        self.sig, self.payload = sig, payload
        mode = ctx["mode"]
        if mode == "MESH":
            if "pinnedSpkiB64" not in ctx or ctx["pinnedSpkiB64"] is None:
                return ("KEY_NOT_PINNED", "7")
            spki = base64.b64decode(ctx["pinnedSpkiB64"])
        else:
            sg = c.get("signer")
            if not isinstance(sg, dict) or not isinstance(sg.get("spki"), str):
                return ("KEY_NOT_PINNED", "7")
            spki = M01.b64(sg["spki"], True)
            if spki is None:
                return ("ENCODING", "7")
        if keyid is not None and keyid != node_id(spki):
            return ("KEY_NOT_PINNED", "7")
        pub = strict_spki(spki)
        if pub is None:
            return ("ALG_UNSUPPORTED", "7")
        self.spki, self.pub = spki, pub
        if ctx.get("productionKeys", True) and node_id(spki) in self.deny:
            return ("TEST_ONLY_KEY", "7")
        if mode == "FILE" and ctx.get("comparedFingerprint") is not None:
            want = export_fp_plain(spki)
            got = "".join(ch for ch in ctx["comparedFingerprint"].upper() if ch not in " -")
            if want != got:
                return ("FINGERPRINT_MISMATCH", "7b")
        if not ecdsa_verify(pub, pae(PT, payload), sig):
            return ("SIGNATURE_INVALID", "8")
        kind, o = M01.parse(payload)
        if kind == "reject":
            return (o, "9")
        if M01.jcs(o).encode("utf-8") != payload:
            return ("NON_CANONICAL", "10")
        self.obj = o
        return None


def step_no(s):
    digits = "".join(ch for ch in s if ch.isdigit())
    return int(digits)


def pem(spki):
    b = base64.b64encode(spki).decode()
    return "-----BEGIN PUBLIC KEY-----\n" + "\n".join(b[i:i + 64] for i in range(0, len(b), 64)) + "\n-----END PUBLIC KEY-----\n"


def der_int(v):
    b = v.to_bytes((v.bit_length() + 7) // 8 or 1, "big")
    if b[0] & 0x80:
        b = b"\x00" + b
    return b"\x02" + bytes([len(b)]) + b


def raw_to_der_python(raw):
    body = der_int(int.from_bytes(raw[:32], "big")) + der_int(int.from_bytes(raw[32:], "big"))
    return b"\x30" + bytes([len(body)]) + body


def openssl_verify(spki, msg, raw, tmp):
    with open(os.path.join(tmp, "k.pem"), "w") as f:
        f.write(pem(spki))
    with open(os.path.join(tmp, "s.der"), "wb") as f:
        f.write(raw_to_der_python(raw))
    with open(os.path.join(tmp, "m.bin"), "wb") as f:
        f.write(msg)
    r = subprocess.run(["openssl", "dgst", "-sha256", "-verify", os.path.join(tmp, "k.pem"), "-signature", os.path.join(tmp, "s.der"), os.path.join(tmp, "m.bin")],
                       capture_output=True, text=True)
    if r.returncode == 0 and "Verified OK" in r.stdout:
        return True
    if "Verification failure" in r.stdout or "Verification failure" in r.stderr:
        return False
    return None  # openssl could not decide (bad input): not counted


def doc_bytes(inp):
    if "documentHex" in inp:
        return bytes.fromhex(inp["documentHex"])
    if "documentFill" in inp:
        return bytes([inp["documentFill"]["byte"]]) * inp["documentFill"]["count"]
    return inp["document"].encode("utf-8")


def lenient_sig_layer(document):
    """(payloadType, payload, sig) from a container whose typed layer is refused before step 8 but whose octets still decode strictly."""
    try:
        c = json.loads(document.decode("utf-8"))
        d = c["dsse"]
        payload = M01.b64(d["payload"], True)
        sig = M01.b64(d["signatures"][0]["sig"], True)
        if payload is None or sig is None or len(sig) != 64 or not isinstance(d["payloadType"], str):
            return None
        return d["payloadType"], payload, sig
    except Exception:
        return None


class Counter:
    def __init__(self, name):
        self.name, self.agree, self.dis, self.skipped = name, 0, 0, 0

    def ok(self, cond, msg):
        if cond:
            self.agree += 1
        else:
            self.dis += 1
            print("  DISAGREE " + msg, file=sys.stderr)


# ------------------------------------------------------------------ M02 / M03
def check_verify(root, family, cnt, openssl_state):
    doc = load_json(os.path.join(root, "manifest", "M02-verify-accept.json" if family == "M02" else "M03-verify-reject.json"))
    steps = Steps(test_only_ids(root))
    tmp = tempfile.mkdtemp(prefix="xcheck-manifest-")
    try:
        for v in doc["vectors"]:
            inp = v["input"]
            document = doc_bytes(inp)
            res = steps.run(document, inp["context"])
            e = v["expect"]
            if "reject" in e:
                want = (e["reject"], v["expectDetail"]["step"])
                if step_no(want[1]) <= 10:
                    cnt.ok(res == want, f"{v['id']}: expected {want}, xcheck got {res}")
                else:
                    cnt.ok(res is None, f"{v['id']}: expected a step {want[1]} reject, so steps 1-10 must pass; xcheck got {res}")
            else:
                cnt.ok(res is None, f"{v['id']}: an accept vector must pass steps 1-10; xcheck got {res}")
                if res is None:
                    bd = b64u(hashlib.sha256(M01.jcs(steps.obj["body"]).encode("utf-8")).digest())
                    cnt.ok(bd == e["ok"]["bodyDigest"], f"{v['id']}: bodyDigest {e['ok']['bodyDigest']} vs {bd}")
                    seq = steps.obj["body"].get("seq")
                    cnt.ok(seq == e["ok"]["seq"], f"{v['id']}: seq {e['ok']['seq']} vs {seq}")
            sl = v.get("sigLayer")
            if sl is not None:
                spki = base64.b64decode(sl["spkiB64"])
                pub = strict_spki(spki)
                if steps.sig is None:
                    lenient = lenient_sig_layer(document)
                    if lenient is not None:
                        steps.ptype, steps.payload, steps.sig = lenient
                if steps.sig is not None and pub is not None:
                    py = ecdsa_verify(pub, pae(getattr(steps, "ptype", PT), steps.payload), steps.sig)
                    cnt.ok(py == sl["verifies"], f"{v['id']}: sigLayer.verifies={sl['verifies']} but the pure-Python verifier says {py}")
                    if openssl_state["on"]:
                        ox = openssl_verify(spki, pae(getattr(steps, "ptype", PT), steps.payload), steps.sig, tmp)
                        if ox is not None:
                            openssl_state["n"] += 1
                            cnt.ok(ox == sl["verifies"], f"{v['id']}: sigLayer.verifies={sl['verifies']} but openssl says {ox}")
                elif pub is not None:
                    cnt.skipped += 1  # the signature text itself is malformed (M03-125 style): no octets to verify
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ------------------------------------------------------------------ M01 DER codec
def der_to_raw(der):
    if len(der) < 8 or der[0] != 0x30:
        return None
    if der[1] >= 0x80 or der[1] != len(der) - 2:
        return None
    i = 2
    parts = []
    for _ in range(2):
        if i + 2 > len(der) or der[i] != 0x02:
            return None
        ln = der[i + 1]
        if ln == 0 or ln >= 0x80 or i + 2 + ln > len(der):
            return None
        v = der[i + 2:i + 2 + ln]
        if v[0] & 0x80:
            return None
        if len(v) > 1 and v[0] == 0 and not (v[1] & 0x80):
            return None
        v = v.lstrip(b"\x00") if len(v) > 1 else v
        if len(v) > 32:
            return None
        parts.append(v.rjust(32, b"\x00"))
        i += 2 + ln
    if i != len(der):
        return None
    return parts[0] + parts[1]


def check_der(root):
    path = os.path.join(root, "manifest", "M01-der-raw.json")
    if not os.path.isfile(path):
        return None
    cnt = Counter("M01der")
    for v in load_json(path)["vectors"]:
        inp, e = v["input"], v["expect"]
        k = inp["kind"]
        if k == "derToRaw":
            r = der_to_raw(bytes.fromhex(inp["derHex"]))
            got = {"ok": {"rawHex": r.hex()}} if r is not None else {"reject": "SIGNATURE_ENCODING"}
        elif k == "rawToDer":
            raw = bytes.fromhex(inp["rawHex"])
            got = {"reject": "SIGNATURE_ENCODING"} if len(raw) != 64 else {"ok": {"derHex": raw_to_der_python(raw).hex()}}
        elif k == "normaliseLowS":
            raw = bytes.fromhex(inp["rawHex"])
            if len(raw) != 64:
                got = {"reject": "SIGNATURE_ENCODING"}
            else:
                s = int.from_bytes(raw[32:], "big")
                if s > N // 2:
                    s = N - s
                got = {"ok": {"rawHex": (raw[:32] + s.to_bytes(32, "big")).hex()}}
        else:
            cnt.ok(False, f"{v['id']}: unknown kind {k}")
            continue
        cnt.ok(got == e, f"{v['id']}: expected {e}, xcheck derived {got}")
        # round trip: any accepted DER re-encodes to itself, so the codec has no second spelling
        if k == "derToRaw" and "ok" in got:
            cnt.ok(raw_to_der_python(bytes.fromhex(got["ok"]["rawHex"])).hex() == inp["derHex"], f"{v['id']}: DER does not round-trip")
    return cnt


# ------------------------------------------------------------------ M05
def asciify(s):
    return "".join(ch if ord(ch) < 128 else "?" for ch in s)


def utc_minute(ms):
    return datetime.datetime.fromtimestamp(ms // 1000, datetime.timezone.utc).strftime("%Y-%m-%d %H:%M")


def utc_day(ms):
    return datetime.datetime.fromtimestamp(ms // 1000, datetime.timezone.utc).strftime("%Y-%m-%d")


def check_m05(root):
    path = os.path.join(root, "manifest", "M05-render.json")
    if not os.path.isfile(path):
        return None
    cnt = Counter("M05")
    steps = Steps(test_only_ids(root))
    texts = {}
    for v in load_json(path)["vectors"]:
        text = v["expect"]["ok"]["text"]
        texts[v["id"]] = text
        cnt.ok(hashlib.sha256(text.encode("utf-8")).hexdigest() == v["expect"]["ok"]["sha256"], f"{v['id']}: sha256 of text")
        cnt.ok(text.isascii() and "\r" not in text and text.endswith("\n") and "\n\n\n" not in text, f"{v['id']}: ASCII/LF/no triple newline")
        tail = text[text.index("ASOM DEVICE REPORT (asom.text/1)"):]
        body_only = tail.split("\n\nThis report has ")[0]  # the trailing newer-items line belongs to the viewer, not to the body
        cnt.ok(all(len(ln) <= 72 for ln in body_only.split("\n")), f"{v['id']}: a line of the asom.text/1 body is over 72 columns")
        cnt.ok("MLPerf" not in text and "mlperf" not in text.lower(), f"{v['id']}: MLPerf wording present while the note is disabled")
        inp = v["input"]
        if inp["kind"] == "export":
            cnt.ok(text.startswith("ASOM DEVICE REPORT (asom.text/1)"), f"{v['id']}: an export starts with the body")
            continue
        res = steps.run(doc_bytes(inp), inp["context"])
        if res is not None and step_no(res[1]) <= 10:
            cnt.ok(False, f"{v['id']}: the rendered document fails step {res}")
            continue
        o = steps.obj
        body, pres = o["body"], o["presentation"]
        lines = text.split("\n")
        cnt.ok(lines[0] == "ASOM CAPABILITY REPORT", f"{v['id']}: first line")
        dev = body["device"]
        cnt.ok(lines[1] == asciify(f"Device: {dev['model']} by {dev['vendor']} ({dev['class']})"), f"{v['id']}: device line {lines[1]!r}")
        if inp["context"]["mode"] == "MESH":
            want = f"Report {body['seq']}, signed {utc_minute(pres['issuedAtMs'])} UTC, valid until {utc_minute(pres['expiresAtMs'])} UTC"
            cnt.ok(lines[2] == want, f"{v['id']}: date line {lines[2]!r} vs {want!r}")
            cnt.ok(lines[3] == "Signer: node " + display_fp(steps.spki), f"{v['id']}: signer line {lines[3]!r}")
        else:
            cnt.ok(lines[2] == f"Report exported {utc_day(pres['issuedAtMs'])} (day only)", f"{v['id']}: date line {lines[2]!r}")
            cnt.ok(lines[3] == "Signer: key " + export_fp(steps.spki), f"{v['id']}: signer line {lines[3]!r}")
        i = text.index("ASOM DEVICE REPORT (asom.text/1)")
        cnt.ok(text[:i].count("\n- ") == (6 if "\n- REJECTED: " in text[:i] else 5) and "VERIFICATION (checked by this viewer, not stated by the device)" in text[:i], f"{v['id']}: five verification bullets")
        inp_id = v["id"]
        texts[inp_id + ":tail"] = text[i:]
    # the file-form renderings share one body with the export of the same document (M05-103/104/108 vs M05-105)
    if all(k in texts for k in ("M05-103:tail", "M05-104:tail", "M05-108:tail", "M05-105")):
        for k in ("M05-103:tail", "M05-104:tail", "M05-108:tail"):
            cnt.ok(texts[k] == texts["M05-105"], f"{k}: the body differs from the export M05-105")
    if "M05-101:tail" in texts and "M05-106:tail" in texts:
        a, b = texts["M05-101:tail"], texts["M05-106:tail"]
        cnt.ok(a != b and len(a.split("\n")) <= len(b.split("\n")) + 2, "M05-106: mesh available changes question 5 only")
    return cnt


# ------------------------------------------------------------------ M06
def q2(x):
    if x < 100:
        return x
    p = 1
    t = x
    while t >= 100:
        t //= 10
        p *= 10
    return (x + p // 2) // p * p


RAM = [1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 2048]


def ram_class(nbytes):
    for c in RAM:
        if nbytes <= c * 2 ** 30:
            return c
    return RAM[-1]


def public_derivative(obj, catalogue, allow, coarse):
    b = obj["body"]
    d = b["device"]
    rows = []
    for r in b["results"]:
        if r["fileSha256"] not in catalogue or r["sustained"] is None:
            continue
        su = r["sustained"]
        c = su["curve"]
        n = len(c)
        idx = sorted(set((k * (n - 1) + 5) // 11 for k in range(12))) if n > 12 else list(range(n))
        pf, dc = r["prefill"][0], r["decode"][0]
        month = datetime.datetime.fromtimestamp(r["measuredAtMs"] // 1000, datetime.timezone.utc).strftime("%Y-%m")
        rows.append({
            "modelId": r["modelId"], "quant": r["quant"], "fileSha256": r["fileSha256"], "backend": r["backend"], "measuredMonth": month,
            "runsCompleted": r["runs"]["completed"], "charging": r["conditions"]["charging"],
            "prefillPromptTokens": pf["promptTokens"], "prefillMilliTokPerSec": q2(pf["milliTokPerSec"]["p50"]),
            "ttftMillis": q2((pf["ttftMicros"]["p50"] + 500) // 1000), "decodeContextTokens": dc["contextTokens"],
            "decodeMilliTokPerSec": q2(dc["milliTokPerSec"]["p50"]), "steadyMilliTokPerSec": q2(su["steadyMilliTokPerSec"]),
            "throttleOnsetSec": None if su["throttleOnsetMs"] is None else q2((su["throttleOnsetMs"] + 500) // 1000),
            "curve": [[q2((c[j][0] + 500) // 1000), q2(c[j][1])] for j in idx],
            "peakProcessMB": q2((r["memory"]["peakProcessBytes"] + 500000) // 1000000), "powerMethod": r["power"]["method"],
            "powerMilliW": None if r["power"]["avgMilliW"] is None else q2(r["power"]["avgMilliW"]),
        })
    if not rows:
        return None
    h, e = b["producer"]["harness"], b["producer"]["engine"]
    m = __import__("re").match(r"[0-9]+", d["os"]["version"])
    return {
        "schema": "asom.bench-public/1",
        "device": {"class": d["class"], "vendor": d["vendor"] if d["vendor"] in coarse["vendors"] else "other",
                   "model": d["model"] if d["model"] in coarse["models"] else "other", "socName": d["soc"]["name"],
                   "ramClassGiB": ram_class(d["memory"]["totalBytes"]), "osFamily": d["os"]["family"],
                   "osMajor": min(int(m.group(0)), 1000) if m else 0, "cooling": d["thermal"]["cooling"]},
        "harness": {"version": h["version"] if h["version"] in allow["harnessVersions"] else "custom", "methodologyId": h["methodologyId"],
                    "confVersion": h["confVersion"] if h["confVersion"] in allow["harnessVersions"] else "custom"},
        "engine": {"name": e["name"], "commit": e["commit"] if e["commit"] in allow["engineCommits"] else "custom"},
        "results": rows,
    }


FORBIDDEN = ["nodeId", "spki", "seq", "challenge", "issuedAtMs", "expiresAtMs", "measuredAtMs", "platformIds", "securityPatch", "evidence",
             "keyStorage", "audience", "signer", "keyid", "osBuild", "gpuDriver", "fingerprint", "settings", "threads", "gpuLayers", "batchTokens"]


def names(o, out):
    if isinstance(o, dict):
        for k, v in o.items():
            out.add(k)
            names(v, out)
    elif isinstance(o, list):
        for x in o:
            names(x, out)
    return out


def day_floor(ms):
    return ms // 86400000 * 86400000


def check_m06(root):
    path = os.path.join(root, "manifest", "M06-derivatives.json")
    if not os.path.isfile(path):
        return None
    cnt = Counter("M06")
    steps = Steps(test_only_ids(root))
    for v in load_json(path)["vectors"]:
        inp, e = v["input"], v["expect"]["ok"]
        kind = inp["kind"]
        if kind == "q2":
            cnt.ok([q2(x) for x in inp["values"]] == e["q2"], f"{v['id']}: q2 table")
            cnt.ok(all(q2(q2(x)) == q2(x) for x in inp["values"]), f"{v['id']}: q2 not idempotent")
        elif kind == "public":
            res = steps.run(doc_bytes(inp), inp["context"])
            cnt.ok(res is None, f"{v['id']}: the source document fails steps 1-10: {res}")
            if res is not None:
                continue
            out = public_derivative(steps.obj, set(inp["catalogue"]), inp["allowList"], inp["coarse"])
            if out is None:
                cnt.ok(e.get("none") is True, f"{v['id']}: xcheck derives no public document but the vector has one")
                continue
            jcs = M01.jcs(out)
            cnt.ok(e.get("jcsUtf8") == jcs, f"{v['id']}: public derivative differs from the Python re-derivation")
            cnt.ok(e.get("sha256") == hashlib.sha256(jcs.encode("utf-8")).hexdigest(), f"{v['id']}: sha256")
            found = names(out, set()) & set(FORBIDDEN)
            cnt.ok(not found, f"{v['id']}: forbidden member names in the public output: {found}")
        elif kind == "fileProjection":
            own = inp["ownObj"]
            f = json.loads(e["jcsUtf8"])
            cnt.ok(hashlib.sha256(e["jcsUtf8"].encode("utf-8")).hexdigest() == e["sha256"], f"{v['id']}: sha256")
            cnt.ok(M01.jcs(f) == e["jcsUtf8"], f"{v['id']}: the file body is not canonical")
            cnt.ok(f["audience"] == "file" and "seq" not in f, f"{v['id']}: audience file, no seq")
            cnt.ok(f["subject"] == {"nodeId": inp["exportNodeId"], "keyAlg": "ES256", "keyStorage": "ephemeral"}, f"{v['id']}: subject is the per-export key")
            cnt.ok(f["subject"]["nodeId"] != own["body"]["subject"]["nodeId"], f"{v['id']}: the export subject must not be the NIK id")
            od = json.loads(json.dumps(own["body"]["device"]))
            od["os"].pop("securityPatch", None)
            od.pop("platformIds", None)
            cnt.ok(f["device"] == od, f"{v['id']}: device is the own device minus securityPatch and platformIds")
            fr = f["bench"]["run"]
            orn = own["body"]["bench"]["run"]
            cnt.ok(fr["startedAtMs"] == day_floor(orn["startedAtMs"]) and fr["endedAtMs"] == day_floor(orn["endedAtMs"]), f"{v['id']}: run times are day-granular")
            cnt.ok(fr["batteryStartPermille"] is None and fr["screenOn"] is None, f"{v['id']}: no battery level or screen state")
            cnt.ok(f["bench"]["device"]["osBuild"] is None and f["bench"]["device"]["gpuDriver"] is None, f"{v['id']}: no OS build or GPU driver")
            cnt.ok(len(f["results"]) == len(own["body"]["results"]), f"{v['id']}: same number of results")
            leaked = names(f, set()) & {"platformIds", "securityPatch", "seq", "screenOn_", "socStartMilliC_"}
            cnt.ok(not leaked, f"{v['id']}: leaked {leaked}")
            for fr_, or_ in zip(f["results"], own["body"]["results"]):
                cnt.ok(fr_["measuredAtMs"] == day_floor(or_["measuredAtMs"]), f"{v['id']}: measuredAtMs is day-granular")
                cnt.ok("batteryStartPermille" not in fr_["conditions"] and "screenOn" not in fr_["conditions"] and "socStartMilliC" not in fr_["conditions"], f"{v['id']}: conditions carry no battery, screen or SoC temperature")
        else:
            cnt.ok(False, f"{v['id']}: unknown kind {kind}")
    return cnt


def run_all(root, want):
    state = {"on": shutil.which("openssl") is not None, "n": 0}
    out = {}
    if "M01der" in want:
        out["M01der"] = check_der(root)
    for fam in ("M02", "M03"):
        if fam in want:
            c = Counter(fam)
            check_verify(root, fam, c, state)
            out[fam] = c
    if "M05" in want:
        out["M05"] = check_m05(root)
    if "M06" in want:
        out["M06"] = check_m06(root)
    return out, state


def main(argv):
    root = argv[1] if len(argv) > 1 else os.path.join(LAB, "conformance")
    want = argv[2].split(",") if len(argv) > 2 else ["M01der", "M02", "M03", "M05", "M06"]
    out, state = run_all(root, want)
    bad = 0
    for fam in want:
        c = out.get(fam)
        if c is None or (c.agree == 0 and c.dis == 0):
            print(f"xcheck {fam}: absent")
        else:
            print(f"xcheck {fam}: {c.agree} agree, {c.dis} disagree")
            bad += c.dis
    print(f"xcheck manifest: pure-Python P-256 verify; openssl {'cross-checked ' + str(state['n']) + ' signatures' if state['on'] else 'ABSENT, external check skipped'}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
