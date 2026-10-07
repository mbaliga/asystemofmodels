#!/usr/bin/env python3
"""xcheck for the trust families W04 and W05 (LAB_SPEC 3.10): re-evaluates the committed vectors with trustlib.py, a second implementation written
from the spec text, and compares every verdict with the vector's `expect`. Also runs OpenSSL (if installed) over the certificate vectors.

  python3 lab/mesh-proto/tools/trust/xcheck_trust.py lab/conformance [W04] [W05]

Called by lab/tools/xcheck.py. Python 3 standard library only; `openssl` is optional and its absence is reported, never hidden.

Same author, same session as the Kotlin code: agreement shows two readings of one spec agree. It does NOT clear `oracle: self` (LAB_SPEC 4.10, R9).
Not covered here (Kotlin-only, typed by hand in gen_vectors.py): the W04 `fsm` and `registry` vectors.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trustlib as T  # noqa: E402


class Counter:
    def __init__(self):
        self.agree = 0
        self.dis = 0
        self.skipped = 0


def load(root, name):
    with open(os.path.join(root, "wire", name), "rb") as f:
        return json.loads(f.read().decode("utf-8"))


def _load_test_only(root):
    keys = json.load(open(os.path.join(root, "keys", "TEST-ONLY-keys.json"), encoding="utf-8"))
    for k in ("key1", "key2", "key3", "key4"):
        T.TEST_ONLY_NODE_IDS.add(keys[k]["nodeId"])
    return keys


def _expect_text(e):
    return ("ok", json.dumps(e["ok"], sort_keys=True)) if "ok" in e else ("reject", e["reject"])


# ------------------------------------------------------------------------------------------------------------------------------------ W05


def registry_fn(inp):
    table, default = inp["registry"], inp["registryDefault"]

    def look(pin):
        s = table.get(T.b64u(pin), default)
        return {"ABSENT": ("absent",), "UNREADABLE": ("unreadable",), "CORRUPT": ("corrupt",)}.get(s) or ("known", s)

    return look


def eval_w05(v, keys):
    i = v["input"]
    k = i["kind"]
    if k == "pinDerive":
        spki = bytes.fromhex(i["spkiHex"])
        if not T.strict_spki(spki):
            return ("reject", "ALG_UNSUPPORTED")
        pin = T.pin_of(spki)
        return ("ok", json.dumps({"pinHex": pin.hex(), "nodeId": T.b64u(pin), "nodeTag": T.node_tag(pin), "display": T.display_fp(pin)}, sort_keys=True))
    if k == "pinTag":
        pin = bytes.fromhex(i["pinHex"])
        return ("ok", json.dumps({"nodeId": T.b64u(pin), "nodeTag": T.node_tag(pin), "display": T.display_fp(pin)}, sort_keys=True))
    if k == "spkiImport":
        spki = bytes.fromhex(i["spkiHex"])
        if not T.strict_spki(spki):
            return ("reject", "ALG_UNSUPPORTED")
        pin = T.pin_of(spki)
        if i["productionKeys"] and T.b64u(pin) in T.TEST_ONLY_NODE_IDS:
            return ("reject", "TEST_ONLY_KEY")
        return ("ok", json.dumps({"pinHex": pin.hex()}, sort_keys=True))
    if k == "pinCompare":
        a, b = bytes.fromhex(i["aHex"]), bytes.fromhex(i["bHex"])
        return ("ok", json.dumps({"equal": len(a) == 32 and len(b) == 32 and a == b}, sort_keys=True))
    if k == "template":
        nik = keys[i["nikKey"]]
        d = int(nik["d_hex"], 16)
        spki = T.spki_of(d)
        serial = bytes.fromhex(i["serialHex"])
        if i["role"] == "node":
            tbs = T.node_tbs(spki, serial, i["epochSec"])
        else:
            tbs = T.leaf_tbs(spki, T.spki_of(int(keys[i["leafKey"]]["d_hex"], 16)), serial, i["epochSec"])
        raw = T.ecdsa_sign(d, tbs)
        if raw.hex() != i["sigRawHex"]:
            return ("reject", "SIGNATURE_DIFFERS")
        return ("ok", json.dumps({"tbsHex": tbs.hex(), "certHex": T.assemble(tbs, raw).hex(), "skiHex": T.ski_of(spki).hex()}, sort_keys=True))
    if k == "chain":
        chain = [bytes.fromhex(h) for h in i["chainHex"]]
        m = i["mode"]
        kind = m["kind"]
        mode = {"expectPaired": ("paired", T.unb64u(m.get("pin", ""))), "expectPairing": ("pairing", T.unb64u(m.get("pin", ""))), "established": ("established",), "pairingServer": ("pairing_server",), "server": ("established",)}[kind]
        r = T.verify_chain(chain, mode, i["nowEpochSec"], registry_fn(i), i["windowOpen"], i["productionKeys"], server=(kind == "server"))
        if r[0] == "reject":
            return ("reject", r[1])
        names = {"paired": "expectPaired", "pairing": "expectPairing", "established": "established", "pairing_server": "pairingServer"}
        return ("ok", json.dumps({"pin": T.b64u(r[1]), "mode": names[r[2][0]]}, sort_keys=True))
    raise ValueError(k)


# ------------------------------------------------------------------------------------------------------------------------------------ openssl


def openssl_available():
    return shutil.which("openssl") is not None


def _run(args, inp=None):
    p = subprocess.run(["openssl"] + args, input=inp, capture_output=True)
    return p.returncode, (p.stdout + p.stderr).decode("utf-8", "replace")


def pem(der):
    import base64
    b = base64.b64encode(der).decode()
    return "-----BEGIN CERTIFICATE-----\n" + "\n".join(b[i:i + 64] for i in range(0, len(b), 64)) + "\n-----END CERTIFICATE-----\n"


def openssl_template_check(v, tmp):
    """OpenSSL parses the golden certificate and agrees on the profile and the signature."""
    cert = bytes.fromhex(v["expect"]["ok"]["certHex"])
    role = v["input"]["role"]
    path = os.path.join(tmp, "c.der")
    open(path, "wb").write(cert)
    rc, text = _run(["x509", "-inform", "DER", "-in", path, "-noout", "-text"])
    if rc != 0:
        return False, "openssl x509 failed: " + text[:200]
    want = ["Signature Algorithm: ecdsa-with-SHA256", "Public Key Algorithm: id-ecPublicKey", "ASN1 OID: prime256v1", "Version: 3 (0x2)"]
    if role == "node":
        want += ["CA:TRUE, pathlen:0", "Certificate Sign", "Subject Key Identifier", "Not After : Dec 31 23:59:59 9999 GMT", "Issuer: CN = asom-node ", "Subject: CN = asom-node "]
    else:
        want += ["CA:FALSE", "Digital Signature", "TLS Web Server Authentication, TLS Web Client Authentication", "Authority Key Identifier", "Subject: CN = asom-session "]
    for w in want:
        if w not in text:
            return False, "openssl text lacks %r" % w
    if role == "node":
        pp = os.path.join(tmp, "n.pem")
        open(pp, "w").write(pem(cert))
        rc, out = _run(["verify", "-no-CApath", "-CAfile", pp, "-attime", str(v["input"]["epochSec"]), pp])
        if rc != 0 or "OK" not in out:
            return False, "openssl verify (self-signed) failed: " + out[:200]
    return True, ""


def openssl_chain_check(v, tmp):
    """For chains OpenSSL can judge on its own terms: the valid ones (it must accept) and signature failures (it must reject)."""
    i = v["input"]
    chain = [bytes.fromhex(h) for h in i["chainHex"]]
    if len(chain) != 2:
        return None
    exp = _expect_text(v["expect"])
    lf, nd = os.path.join(tmp, "leaf.pem"), os.path.join(tmp, "node.pem")
    try:
        open(lf, "w").write(pem(chain[0]))
        open(nd, "w").write(pem(chain[1]))
    except Exception:
        return None
    now = i["nowEpochSec"]
    try:
        leaf = T.parse_cert(chain[0])
        inside = leaf.not_before <= now <= leaf.not_after
    except T.DerError:
        inside = False
    args = ["verify", "-no-CApath", "-check_ss_sig", "-CAfile", nd, "-attime", str(now), lf]
    if exp[0] == "ok" and inside:
        rc, out = _run(args)
        return (rc == 0 and "OK" in out, out[:200])
    if exp == ("reject", "BAD_SIGNATURE") and inside:
        rc, out = _run(args)
        return (rc != 0, out[:200])
    return None


# ------------------------------------------------------------------------------------------------------------------------------------ W04


def _strict_json(text):
    def pairs(p):
        d = {}
        for k, val in p:
            if k in d:
                raise ValueError("duplicate member")
            d[k] = val
        return d

    def bad(_):
        raise ValueError("float or constant")

    return json.loads(text, object_pairs_hook=pairs, parse_float=bad, parse_constant=bad)


def _jcs(o):
    return json.dumps(o, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


PLATFORMS = ["android", "ios", "ipados", "macos", "linux", "windows", "ubuntu-touch"]
TIERS = ["strongbox", "tee", "secure-enclave", "tpm", "os-keystore", "file"]


def _b32(o, key):
    s = o.get(key)
    if not isinstance(s, str) or len(s) != 43:
        return None
    r = T.unb64u(s)
    return r if r is not None and len(r) == 32 else None


def _name_ok(o):
    n = o.get("name")
    if not isinstance(n, str) or not 1 <= len(n) <= 32:
        return None
    return n if T.pct_decode(T.pct_encode(n)) is not None else None


def eval_message(typ, text):
    try:
        o = _strict_json(text)
    except Exception:
        return ("reject", "PROTOCOL_ERROR")
    if not isinstance(o, dict):
        return ("reject", "PROTOCOL_ERROR")
    v = o.get("v")
    if not isinstance(v, int) or isinstance(v, bool):
        return ("reject", "PROTOCOL_ERROR")
    if v != 1:
        return ("reject", "VERSION_UNSUPPORTED")
    bad = ("reject", "PROTOCOL_ERROR")
    if typ == "PAIR_HELLO":
        if _b32(o, "nonceS") is None or _b32(o, "proof") is None or _name_ok(o) is None or o.get("platform") not in PLATFORMS or o.get("keyTier") not in TIERS:
            return bad
        eps = o.get("endpoints")
        if not isinstance(eps, list) or len(eps) > 4:
            return bad
        out = []
        for e in eps:
            if not isinstance(e, dict):
                return bad
            a, p, via = e.get("addr"), e.get("port"), e.get("via")
            if not isinstance(a, str) or not (T._v4(a) is not None or T._v6(a) is not None or False):
                return bad
            if T._v6(a) is not None and a != a.lower():
                return bad
            if not isinstance(p, int) or isinstance(p, bool) or not 1 <= p <= 65535 or via not in ("lan", "overlay"):
                return bad
            out.append({"addr": a, "port": p, "via": via})
        norm = {"v": 1, "nonceS": o["nonceS"], "proof": o["proof"], "name": o["name"], "platform": o["platform"], "keyTier": o["keyTier"], "endpoints": out}
    elif typ == "PAIR_CHALLENGE":
        if _b32(o, "nonceD") is None or _name_ok(o) is None or o.get("platform") not in PLATFORMS or o.get("keyTier") not in TIERS:
            return bad
        norm = {"v": 1, "nonceD": o["nonceD"], "name": o["name"], "platform": o["platform"], "keyTier": o["keyTier"]}
    elif typ == "PAIR_DECISION":
        if not isinstance(o.get("approve"), bool):
            return bad
        norm = {"v": 1, "approve": o["approve"]}
    elif typ in ("PAIR_COMMIT", "PAIR_COMMIT_ACK"):
        if _b32(o, "transcript") is None:
            return bad
        norm = {"v": 1, "transcript": o["transcript"]}
    else:
        raise ValueError(typ)
    return ("ok", json.dumps({"normal": _jcs(norm)}, sort_keys=True))


def eval_w04(v):
    i = v["input"]
    k = i["kind"]
    if k == "qrParse":
        r = T.qr_parse(i["uri"], i["nowSec"], loopback_ok=(i["policy"] == "loopbackForTests"))
        return ("ok", json.dumps(r[1], sort_keys=True)) if r[0] == "ok" else ("reject", r[1])
    if k == "qrEncode":
        return ("ok", json.dumps({"uri": T.qr_encode(i["k"], i["a"], i["s"], i["x"], i["n"])}, sort_keys=True))
    if k == "proofSas":
        pd, ps, sec, ns, nd = (T.unb64u(i[x]) for x in ("pinD", "pinS", "secret", "nonceS", "nonceD"))
        return ("ok", json.dumps({"proof": T.b64u(T.proof(sec, pd, ps, ns)), "sas": T.sas_display(pd, ps, ns, nd), "transcriptHex": T.transcript(pd, ps, ns, nd).hex()}, sort_keys=True))
    if k == "proofVerify":
        pd, ps, sec, ns = (T.unb64u(i[x]) for x in ("pinD", "pinS", "secret", "nonceS"))
        presented = T.unb64u(i["presented"]) if len(i["presented"]) == 43 else None
        good = presented is not None and len(presented) == 32 and presented == T.proof(sec, pd, ps, ns)
        return ("ok", json.dumps({"valid": True}, sort_keys=True)) if good else ("reject", "PAIRING_PROOF_INVALID")
    if k == "message":
        return eval_message(i["type"], i["payload"])
    return None


def main(argv):
    root = argv[1] if len(argv) > 1 and not argv[1].startswith("W") else "lab/conformance"
    fams = [a for a in argv[1:] if a in ("W04", "W05")] or ["W04", "W05"]
    keys = _load_test_only(root)
    res = {}
    use_openssl = openssl_available()
    ossl = {"template": [0, 0], "chain": [0, 0]}
    tmp = tempfile.mkdtemp(prefix="asom-xcheck-trust-")
    try:
        for fam in fams:
            c = Counter()
            doc = load(root, "W05-fingerprints.json" if fam == "W05" else "W04-pairing.json")
            for v in doc["vectors"]:
                got = eval_w05(v, keys) if fam == "W05" else eval_w04(v)
                if got is None:
                    c.skipped += 1
                    continue
                want = _expect_text(v["expect"])
                if got == want:
                    c.agree += 1
                else:
                    c.dis += 1
                    print("  DISAGREE %s: expected %s, python gives %s" % (v["id"], str(want)[:160], str(got)[:160]), file=sys.stderr)
                if fam == "W05" and use_openssl:
                    i = v["input"]
                    if i["kind"] == "template":
                        ok, why = openssl_template_check(v, tmp)
                        ossl["template"][0 if ok else 1] += 1
                        if not ok:
                            print("  OPENSSL %s: %s" % (v["id"], why), file=sys.stderr)
                    elif i["kind"] == "chain":
                        r = openssl_chain_check(v, tmp)
                        if r is not None:
                            ossl["chain"][0 if r[0] else 1] += 1
                            if not r[0]:
                                print("  OPENSSL %s: %s" % (v["id"], r[1]), file=sys.stderr)
            res[fam] = c
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    bad = 0
    for fam, c in res.items():
        extra = " (%d fsm/registry vectors are Kotlin-only)" % c.skipped if c.skipped else ""
        print("xcheck %s: %d agree, %d disagree%s" % (fam, c.agree, c.dis, extra))
        bad += c.dis
    if "W05" in res:
        if use_openssl:
            _, ver = _run(["version"])
            print("xcheck W05 openssl (%s): templates %d agree, %d disagree; chains %d agree, %d disagree" % (ver.strip(), ossl["template"][0], ossl["template"][1], ossl["chain"][0], ossl["chain"][1]))
            bad += ossl["template"][1] + ossl["chain"][1]
        else:
            print("xcheck W05 openssl: not installed, no OpenSSL evidence")
    return 1 if bad else 0


def check(root, family):
    """Entry point for lab/tools/xcheck.py: (agree, disagree) of one family. OpenSSL disagreements count as disagreements."""
    keys = _load_test_only(root)
    c = Counter()
    doc = load(root, "W05-fingerprints.json" if family == "W05" else "W04-pairing.json")
    for v in doc["vectors"]:
        got = eval_w05(v, keys) if family == "W05" else eval_w04(v)
        if got is None:
            continue
        if got == _expect_text(v["expect"]):
            c.agree += 1
        else:
            c.dis += 1
            print("  DISAGREE %s: expected %s, python gives %s" % (v["id"], str(_expect_text(v["expect"]))[:160], str(got)[:160]), file=sys.stderr)
    if family == "W05" and openssl_available():
        tmp = tempfile.mkdtemp(prefix="asom-xcheck-trust-")
        try:
            for v in doc["vectors"]:
                i = v["input"]
                r = None
                if i["kind"] == "template":
                    r = openssl_template_check(v, tmp)
                elif i["kind"] == "chain":
                    r = openssl_chain_check(v, tmp)
                if r is not None:
                    if r[0]:
                        c.agree += 1
                    else:
                        c.dis += 1
                        print("  OPENSSL %s: %s" % (v["id"], r[1]), file=sys.stderr)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    return c.agree, c.dis


if __name__ == "__main__":
    sys.exit(main(sys.argv))
