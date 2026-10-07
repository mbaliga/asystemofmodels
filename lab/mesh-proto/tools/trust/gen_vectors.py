#!/usr/bin/env python3
"""Writes lab/conformance/wire/W05-fingerprints.json and W04-pairing.json (LAB_SPEC 3.1, 7.3, 7.4; trust.md 15).

Run: python3 lab/mesh-proto/tools/trust/gen_vectors.py   (then python3 lab/tools/regen_index.py)

Every certificate is forged here by trustlib.py (a hand-rolled DER writer and RFC 6979 ECDSA, no Kotlin involved), and the expected verdict of every
forged certificate is TYPED BY HAND at the call site: it is the defect that was injected, not the output of any verifier. The positive derivations
(pins, tags, templates, proofs, SAS, transcripts, URIs) come from trustlib. The finite-state-machine and registry traces are typed by hand from
trust.md 4.6 and 4.7. All vectors are `oracle: self` (LAB_SPEC 4.10, R9): same author, same session as the Kotlin implementation.
"""
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trustlib as T  # noqa: E402

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "conformance")
KEYS = json.load(open(os.path.join(ROOT, "keys", "TEST-ONLY-keys.json"), encoding="utf-8"))
CONF_VERSION = open(os.path.join(ROOT, "VERSION"), encoding="utf-8").read().strip()

D = {k: int(KEYS[k]["d_hex"], 16) for k in ("key1", "key2", "key3", "key4")}
SPKI = {k: T.spki_of(D[k]) for k in D}
for k in D:
    import base64
    assert SPKI[k] == base64.b64decode(KEYS[k]["spki_b64"]), k
    assert T.b64u(T.pin_of(SPKI[k])) == KEYS[k]["nodeId"], k
    assert T.node_tag(T.pin_of(SPKI[k])) == KEYS[k]["nodeTag"], k
T.TEST_ONLY_NODE_IDS.update(KEYS[k]["nodeId"] for k in D)

NOW = 1790000000
CREATED = NOW - 86400
SN_NODE1 = bytes.fromhex("0102030405060708090a0b0c0d0e0f10")
SN_NODE2 = bytes.fromhex("1112131415161718191a1b1c1d1e1f20")
SN_LEAF1 = bytes.fromhex("2122232425262728292a2b2c2d2e2f30")
SN_LEAF2 = bytes.fromhex("3132333435363738393a3b3c3d3e3f40")

P384_SPKI = bytes.fromhex(
    "3076301006072a8648ce3d020106052b8104002203620004635e9eeee38d10a64509fd454ec217df31a49ecae88e310af6438d53a2ba2b8edb1114da8e6aa1e93dd1aea526dbaaf43d256b741ee4189db7f6132d0880d9266af98963f5fa9806327026b6a13ce8efdfa55d8da4b95426f35e639e910fa064"
)
RSA_SPKI = bytes.fromhex(
    "30820122300d06092a864886f70d01010105000382010f003082010a0282010100d0aee82d3970863d060f8e99778e26f0a9e9124865017bc32d532a0cb7f182a4c827980095d3d81850114d47f491fed9681a54faf3e875f84a72e11c882d734c2625bc70d7a6cf74028dba84b00fff3fd24350ca38c845ebd2ff26d5ecd6bba6c1eb43d3445fde25932b43741154746b7ad054c19a54fa6ad2e016684b85e3232ab100cb10d3e73ceb1149f3cee0f31823ad7ec96f46a571f8ee2af968d7729d2fdba1985f714703c97a1dcb8bb86ff3bcc1d8f30911c045583566a4950606d0959ef38ff020e098fb4e00ada5b5acd4d33194d837099cb352bfddef7b603a24ed2bb00c35b4ef0c769f4ee6f0d1f04f92f40470764a41235f4b403993d570b10203010001"
)


def envelope(family, spec_refs, vectors):
    return {"family": family, "confVersion": CONF_VERSION, "specRefs": spec_refs, "vectors": vectors}


class Collector:
    def __init__(self, family):
        self.family = family
        self.items = []
        self.ids = set()

    def add(self, num, description, inp, expect, origin="hand", status="normative"):
        vid = "%s-%03d" % (self.family, num)
        assert vid not in self.ids, vid
        self.ids.add(vid)
        assert (("ok" in expect) != ("reject" in expect)) and len(expect) == 1
        self.items.append({"id": vid, "origin": origin, "status": status, "oracle": "self", "description": description, "input": inp, "expect": expect})


def write(path, doc):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(doc, indent=2, ensure_ascii=False) + "\n")


H = lambda b: b.hex()  # noqa: E731
NODEID = {k: KEYS[k]["nodeId"] for k in D}

# ------------------------------------------------------------------------------------------------------------------------------------ cert forge


def e_bc(ca, path=None, critical=True):
    parts = []
    if ca:
        parts.append(T.boolean(True))
    if path is not None:
        parts.append(T.integer_small(path))
    return T.ext(T.OID_BC, critical, T.seq(*parts))


def e_ku(bits, critical=True):
    n = max(bits) + 1
    nbytes = (n + 7) // 8
    data = bytearray(nbytes)
    for b in bits:
        data[b // 8] |= 0x80 >> (b % 8)
    return T.ext(T.OID_KU, critical, T.bit_string(bytes(data), nbytes * 8 - n))


def e_ski(b, critical=False):
    return T.ext(T.OID_SKI, critical, T.octets(b))


def e_aki(b, critical=False):
    return T.ext(T.OID_AKI, critical, T.seq(T.tlv(0x80, b)))


def e_eku(oids, critical=False):
    return T.ext(T.OID_EKU, critical, T.seq(*[T.oid(o) for o in oids]))


def node_exts(spki):
    return [e_bc(True, 0), e_ku({5}), e_ski(T.ski_of(spki))]


def leaf_exts(nik_spki):
    return [e_bc(False), e_ku({0}), e_eku([T.OID_SERVER_AUTH, T.OID_CLIENT_AUTH]), e_aki(T.ski_of(nik_spki))]


ALG_SHA1 = T.seq(T.oid(T.OID_ECDSA_SHA1))
ALG_SHA384 = T.seq(T.oid(T.OID_ECDSA_SHA384))
ALG_NULL_PARAMS = T.seq(T.oid(T.OID_ECDSA_SHA256), T.tlv(0x05, b""))


def forge_node(nik, *, created=CREATED, serial=SN_NODE1, exts=None, spki=None, subject=None, issuer=None, signer=None, alg=T.ALG_SHA256, inner=None, digest="sha256",
               version=2, not_before=None, not_after=None, flip_sig=False, flip_tbs=None):
    d, nik_spki = nik
    n = T.name_der("asom-node", nik_spki)
    tbs = T.build_tbs(
        serial, issuer or n, not_before or T.time_el(created - T.BACKDATE), not_after or T.gen_time(T.NODE_NOT_AFTER), subject or n, spki or nik_spki,
        node_exts(nik_spki) if exts is None else exts, version=version, inner_alg=inner or alg,
    )
    return finish(tbs, signer if signer is not None else d, alg, digest, flip_sig, flip_tbs)


def forge_leaf(nik, leaf_spki, *, now=NOW, serial=SN_LEAF1, exts=None, subject=None, issuer=None, signer=None, alg=T.ALG_SHA256, inner=None, digest="sha256",
               version=2, not_before=None, not_after=None, flip_sig=False, flip_tbs=None):
    d, nik_spki = nik
    tbs = T.build_tbs(
        serial, issuer or T.name_der("asom-node", nik_spki), not_before or T.time_el(now - T.BACKDATE), not_after or T.time_el(now + T.LEAF_LIFETIME),
        subject or T.name_der("asom-session", nik_spki), leaf_spki, leaf_exts(nik_spki) if exts is None else exts, version=version, inner_alg=inner or alg,
    )
    return finish(tbs, signer if signer is not None else d, alg, digest, flip_sig, flip_tbs)


def finish(tbs, signer_d, alg, digest, flip_sig, flip_tbs):
    raw = T.ecdsa_sign(signer_d, tbs, digest)
    if flip_sig:
        raw = raw[:10] + bytes([raw[10] ^ 0x01]) + raw[11:]
    if flip_tbs is not None:
        tbs = tbs[:flip_tbs] + bytes([tbs[flip_tbs] ^ 0x01]) + tbs[flip_tbs + 1:]
    return T.assemble(tbs, raw, alg)


NIK1, NIK2 = (D["key1"], SPKI["key1"]), (D["key2"], SPKI["key2"])
LEAF_KEY1, LEAF_KEY2 = SPKI["key3"], SPKI["key4"]
NODE1 = forge_node(NIK1)
LEAF1 = forge_leaf(NIK1, LEAF_KEY1)
NODE2 = forge_node(NIK2, serial=SN_NODE2)
LEAF2 = forge_leaf(NIK2, LEAF_KEY2, serial=SN_LEAF2)
PIN1, PIN2 = NODEID["key1"], NODEID["key2"]

# ------------------------------------------------------------------------------------------------------------------------------------ W05
w05 = Collector("W05")

# 001..: pins, node tags, fingerprints (trust.md 2.3)
n = 1
for k in ("key1", "key2", "key3", "key4"):
    pin = T.pin_of(SPKI[k])
    w05.add(n, "pin, nodeId, nodeTag and display fingerprint of the TEST-ONLY %s SPKI" % k,
            {"kind": "pinDerive", "spkiHex": H(SPKI[k])},
            {"ok": {"pinHex": H(pin), "nodeId": T.b64u(pin), "nodeTag": T.node_tag(pin), "display": T.display_fp(pin)}})
    n += 1
for label in ("asom-vector/spki/D", "asom-vector/spki/S"):
    import hashlib
    pin = hashlib.sha256(label.encode()).digest()
    w05.add(n, "nodeTag and display fingerprint of the illustrative pin SHA-256(%r) (trust.md 4.2)" % label, {"kind": "pinTag", "pinHex": H(pin)},
            {"ok": {"nodeId": T.b64u(pin), "nodeTag": T.node_tag(pin), "display": T.display_fp(pin)}})
    n += 1
for pin, label in ((bytes(32), "all-zero"), (bytes([255]) * 32, "all-ones"), (bytes(range(32)), "00..1f")):
    w05.add(n, "nodeTag of the %s pin (base32 edge values)" % label, {"kind": "pinTag", "pinHex": H(pin)},
            {"ok": {"nodeId": T.b64u(pin), "nodeTag": T.node_tag(pin), "display": T.display_fp(pin)}})
    n += 1

# 100..: strict SPKI import (LAB_SPEC 4.5) and pin comparison
n = 100
good = SPKI["key1"]
bad_spki = [
    ("a trailing byte (92 bytes)", good + b"\x00"),
    ("one byte short (90 bytes)", good[:-1]),
    ("empty input", b""),
    ("a compressed point (33-byte key, 59-byte SPKI)", bytes.fromhex("3039301306072a8648ce3d020106082a8648ce3d030107032200") + bytes([2 + (good[-1] & 1)]) + good[27:59]),
    ("the uncompressed marker replaced by 05", good[:26] + b"\x05" + good[27:]),
    ("a wrong prefix (id-ecPublicKey with prime192v1)", bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030101034200") + good[26:]),
    ("a P-384 SPKI (120 bytes)", P384_SPKI),
    ("an RSA-2048 SPKI", RSA_SPKI),
    ("a point that is not on the curve (y flipped in its last bit)", good[:-1] + bytes([good[-1] ^ 1])),
    ("x = p (not below the field prime)", good[:27] + T.P.to_bytes(32, "big") + good[59:]),
]
for desc, spki in bad_spki:
    assert not T.strict_spki(spki), desc
    w05.add(n, "SPKI import refused: " + desc, {"kind": "spkiImport", "spkiHex": H(spki), "productionKeys": False}, {"reject": "ALG_UNSUPPORTED"})
    n += 1
w05.add(n, "SPKI import accepted: the TEST-ONLY key1 in conformance mode", {"kind": "spkiImport", "spkiHex": H(good), "productionKeys": False}, {"ok": {"pinHex": H(T.pin_of(good))}})
n += 1
w05.add(n, "SPKI import refused: the TEST-ONLY key1 in production mode (M03-141 semantics)", {"kind": "spkiImport", "spkiHex": H(good), "productionKeys": True}, {"reject": "TEST_ONLY_KEY"})
n += 1
w05.add(n, "SPKI import accepted: a key that is not a TEST-ONLY key, in production mode (a fixed scalar of this generator)",
        {"kind": "spkiImport", "spkiHex": H(T.spki_of(0x1234567890ABCDEF)), "productionKeys": True}, {"ok": {"pinHex": H(T.pin_of(T.spki_of(0x1234567890ABCDEF)))}})
n += 1

p1 = T.pin_of(SPKI["key1"])
p2 = T.pin_of(SPKI["key2"])
flip_last = p1[:-1] + bytes([p1[-1] ^ 1])
flip_first = bytes([p1[0] ^ 0x80]) + p1[1:]
cmp_cases = [
    ("equal pins", p1, p1, True),
    ("different pins", p1, p2, False),
    ("pins differing in the last bit only", p1, flip_last, False),
    ("pins differing in the first bit only", p1, flip_first, False),
    ("a 31-byte prefix of the pin is not the pin", p1, p1[:31], False),
    ("the pin is not a 31-byte prefix (swapped arguments)", p1[:31], p1, False),
    ("a 16-byte prefix is not the pin", p1, p1[:16], False),
    ("the pin followed by one extra byte", p1, p1 + b"\x00", False),
    ("an empty value is not a pin", p1, b"", False),
    ("two empty values are not equal pins (a pin is exactly 32 bytes)", b"", b"", False),
    ("two equal 31-byte values are not pins either", p1[:31], p1[:31], False),
    ("two all-zero pins", bytes(32), bytes(32), True),
    ("the all-zero pin and a pin differing in the last byte", bytes(32), bytes(31) + b"\x01", False),
]
for desc, a, b, eq in cmp_cases:
    w05.add(n, "pin comparison: " + desc, {"kind": "pinCompare", "aHex": H(a), "bHex": H(b)}, {"ok": {"equal": eq}})
    n += 1

# 200..: certificate templates (trust.md 2.4): golden TBS and certificate bytes for the TEST-ONLY keys
n = 200


def template_vector(num, desc, role, nik, leaf_key, serial, t):
    d, nik_spki = nik
    if role == "node":
        tbs = T.node_tbs(nik_spki, serial, t)
    else:
        tbs = T.leaf_tbs(nik_spki, SPKI[leaf_key], serial, t)
    raw = T.ecdsa_sign(d, tbs)
    cert = T.assemble(tbs, raw)
    inp = {"kind": "template", "role": role, "nikKey": "key1" if nik is NIK1 else "key2", "serialHex": H(serial), "epochSec": t, "sigRawHex": H(raw)}
    if role == "leaf":
        inp["leafKey"] = leaf_key
    w05.add(num, desc, inp, {"ok": {"tbsHex": H(tbs), "certHex": H(cert), "skiHex": H(T.ski_of(nik_spki))}})


template_vector(n, "node certificate of key1 (CA:TRUE pathLen 0, keyCertSign, SKI; notAfter 99991231235959Z), created at 1790000000 - 86400", "node", NIK1, None, SN_NODE1, CREATED); n += 1
template_vector(n, "node certificate of key2", "node", NIK2, None, SN_NODE2, CREATED); n += 1
template_vector(n, "node certificate with a serial whose first byte is 00 (minimal INTEGER drops it)", "node", NIK1, None, bytes.fromhex("00112233445566778899aabbccddeeff"), CREATED); n += 1
template_vector(n, "node certificate with the largest serial (7f ff ...)", "node", NIK2, None, bytes.fromhex("7fffffffffffffffffffffffffffffff"), CREATED); n += 1
template_vector(n, "node certificate created in 2050 (notBefore as GeneralizedTime)", "node", NIK1, None, SN_NODE1, 2524608000 + 3600); n += 1
template_vector(n, "session leaf of key1's node with leaf key3 (14 days, digitalSignature, serverAuth+clientAuth, AKI = node SKI)", "leaf", NIK1, "key3", SN_LEAF1, NOW); n += 1
template_vector(n, "session leaf of key2's node with leaf key4", "leaf", NIK2, "key4", SN_LEAF2, NOW); n += 1
template_vector(n, "session leaf minted in 2049 (notAfter in 2050 is a GeneralizedTime, notBefore a UTCTime)", "leaf", NIK1, "key3", SN_LEAF1, 2524608000 - 7 * 86400); n += 1
template_vector(n, "session leaf minted exactly at 2050-01-01T01:00:00Z (both bounds in their RFC 5280 forms)", "leaf", NIK1, "key4", SN_LEAF2, 2524608000 + 3600); n += 1

# 300..: verifyPeerChain (certs/verify-chain.json of trust.md 15)
n = 300


def mode_json(kind, pin=None):
    return {"kind": kind} if pin is None else {"kind": kind, "pin": pin}


def chain_vec(num, desc, chain, mode, expect, now=NOW, registry=None, default="ABSENT", window=True, production=False):
    inp = {
        "kind": "chain", "chainHex": [H(c) for c in chain], "mode": mode, "nowEpochSec": now,
        "registry": registry if registry is not None else {}, "registryDefault": default, "windowOpen": window, "productionKeys": production,
    }
    w05.add(num, desc, inp, expect)


def ok_expect(pin, mode):
    return {"ok": {"pin": pin, "mode": mode}}


PAIRED1 = {PIN1: "PAIRED"}
CH1 = [LEAF1, NODE1]
CH2 = [LEAF2, NODE2]

# valid chains in every mode
chain_vec(n, "valid [leaf, node]: EXPECT_PAIRED(pin1), pin1 PAIRED", CH1, mode_json("expectPaired", PIN1), ok_expect(PIN1, "expectPaired"), registry=PAIRED1); n += 1
chain_vec(n, "valid [leaf, node] of the second node: EXPECT_PAIRED(pin2), pin2 PAIRED", CH2, mode_json("expectPaired", PIN2), ok_expect(PIN2, "expectPaired"), registry={PIN2: "PAIRED"}); n += 1
chain_vec(n, "valid: ESTABLISHED_SERVER, pin1 PAIRED", CH1, mode_json("established"), ok_expect(PIN1, "established"), registry=PAIRED1); n += 1
chain_vec(n, "valid: EXPECT_PAIRING(pin1 from the QR), pin1 unknown", CH1, mode_json("expectPairing", PIN1), ok_expect(PIN1, "expectPairing")); n += 1
chain_vec(n, "valid: PAIRING_SERVER, window open, pin1 unknown", CH1, mode_json("pairingServer"), ok_expect(PIN1, "pairingServer")); n += 1
chain_vec(n, "valid: server mode selection picks ESTABLISHED_SERVER for a PAIRED pin even with a window open", CH1, mode_json("server"), ok_expect(PIN1, "established"), registry=PAIRED1); n += 1
chain_vec(n, "valid: server mode selection picks PAIRING_SERVER for an unknown pin with a window open", CH1, mode_json("server"), ok_expect(PIN1, "pairingServer")); n += 1
chain_vec(n, "valid, per the letter of trust.md 3.2: EXPECT_PAIRING accepts a SUSPENDED pin (it is not REVOKED); the registry refuses to re-pair it (L4)", CH1,
          mode_json("expectPairing", PIN1), ok_expect(PIN1, "expectPairing"), registry={PIN1: "SUSPENDED"}); n += 1
chain_vec(n, "valid, per the letter: PAIRING_SERVER accepts a PAIRED pin that is not REVOKED; the registry refuses the commit (PAIR_EXISTS)", CH1,
          mode_json("pairingServer"), ok_expect(PIN1, "pairingServer"), registry=PAIRED1); n += 1
chain_vec(n, "production mode refuses the TEST-ONLY node key", CH1, mode_json("expectPaired", PIN1), {"reject": "TEST_ONLY_KEY"}, registry=PAIRED1, production=True); n += 1

# clock: the leaf window is [notBefore - 2h, notAfter + 2h]
nb, na = NOW - 3600, NOW + T.LEAF_LIFETIME
chain_vec(n, "leaf accepted exactly at notBefore - 2h (the skew bound is inclusive)", CH1, mode_json("established"), ok_expect(PIN1, "established"), now=nb - 7200, registry=PAIRED1); n += 1
chain_vec(n, "leaf not yet valid beyond the 2 h skew (notBefore - 2h - 1 s)", CH1, mode_json("established"), {"reject": "CLOCK_SKEW"}, now=nb - 7201, registry=PAIRED1); n += 1
chain_vec(n, "leaf accepted at notAfter + 2h", CH1, mode_json("established"), ok_expect(PIN1, "established"), now=na + 7200, registry=PAIRED1); n += 1
chain_vec(n, "leaf expired beyond the 2 h skew (notAfter + 2h + 1 s)", CH1, mode_json("established"), {"reject": "CLOCK_SKEW"}, now=na + 7201, registry=PAIRED1); n += 1
chain_vec(n, "leaf expired a year ago", CH1, mode_json("established"), {"reject": "CLOCK_SKEW"}, now=na + 365 * 86400, registry=PAIRED1); n += 1
chain_vec(n, "clock far in the past (epoch 0): the leaf is not yet valid", CH1, mode_json("established"), {"reject": "CLOCK_SKEW"}, now=0, registry=PAIRED1); n += 1

# registry and pin states
chain_vec(n, "pin not in the registry: ESTABLISHED_SERVER, window closed", CH1, mode_json("established"), {"reject": "PIN_UNKNOWN"}, window=False); n += 1
chain_vec(n, "pin not in the registry: ESTABLISHED_SERVER even with a window open (the explicit mode does not look at the window)", CH1, mode_json("established"), {"reject": "PIN_UNKNOWN"}); n += 1
chain_vec(n, "pin not in the registry: EXPECT_PAIRED", CH1, mode_json("expectPaired", PIN1), {"reject": "PIN_UNKNOWN"}); n += 1
chain_vec(n, "server mode selection with an unknown pin and the window closed: ESTABLISHED_SERVER, so PIN_UNKNOWN", CH1, mode_json("server"), {"reject": "PIN_UNKNOWN"}, window=False); n += 1
chain_vec(n, "pin SUSPENDED: ESTABLISHED_SERVER", CH1, mode_json("established"), {"reject": "PIN_SUSPENDED"}, registry={PIN1: "SUSPENDED"}); n += 1
chain_vec(n, "pin SUSPENDED: EXPECT_PAIRED", CH1, mode_json("expectPaired", PIN1), {"reject": "PIN_SUSPENDED"}, registry={PIN1: "SUSPENDED"}); n += 1
chain_vec(n, "pin SUSPENDED: server mode selection (not unknown, so ESTABLISHED_SERVER)", CH1, mode_json("server"), {"reject": "PIN_SUSPENDED"}, registry={PIN1: "SUSPENDED"}); n += 1
chain_vec(n, "pin REVOKED: PAIRING_SERVER", CH1, mode_json("pairingServer"), {"reject": "PIN_REVOKED"}, registry={PIN1: "REVOKED"}); n += 1
chain_vec(n, "pin REVOKED: EXPECT_PAIRING", CH1, mode_json("expectPairing", PIN1), {"reject": "PIN_REVOKED"}, registry={PIN1: "REVOKED"}); n += 1
chain_vec(n, "pin REVOKED: ESTABLISHED_SERVER", CH1, mode_json("established"), {"reject": "PIN_REVOKED"}, registry={PIN1: "REVOKED"}); n += 1
chain_vec(n, "pin REVOKED: EXPECT_PAIRED", CH1, mode_json("expectPaired", PIN1), {"reject": "PIN_REVOKED"}, registry={PIN1: "REVOKED"}); n += 1
chain_vec(n, "pin REVOKED: server mode selection", CH1, mode_json("server"), {"reject": "PIN_REVOKED"}, registry={PIN1: "REVOKED"}); n += 1
chain_vec(n, "EXPECT_PAIRED with another pin: the chain is valid but its node key is not the dialed one", CH1, mode_json("expectPaired", PIN2), {"reject": "PIN_MISMATCH"}, registry={PIN1: "PAIRED", PIN2: "PAIRED"}); n += 1
chain_vec(n, "EXPECT_PAIRING with another pin from the QR", CH1, mode_json("expectPairing", PIN2), {"reject": "PIN_MISMATCH"}); n += 1
chain_vec(n, "PAIRING_SERVER with the window closed", CH1, mode_json("pairingServer"), {"reject": "PAIRING_WINDOW_CLOSED"}, window=False); n += 1
chain_vec(n, "registry row with an unrecognised status value: ESTABLISHED_SERVER denies (L5)", CH1, mode_json("established"), {"reject": "REGISTRY_UNREADABLE"}, registry={PIN1: "CORRUPT"}); n += 1
chain_vec(n, "registry row with an unrecognised status value: EXPECT_PAIRING denies (L5)", CH1, mode_json("expectPairing", PIN1), {"reject": "REGISTRY_UNREADABLE"}, registry={PIN1: "CORRUPT"}); n += 1
chain_vec(n, "registry unreadable: EXPECT_PAIRED fails closed", CH1, mode_json("expectPaired", PIN1), {"reject": "REGISTRY_UNREADABLE"}, default="UNREADABLE"); n += 1
chain_vec(n, "registry unreadable: PAIRING_SERVER fails closed", CH1, mode_json("pairingServer"), {"reject": "REGISTRY_UNREADABLE"}, default="UNREADABLE"); n += 1
chain_vec(n, "registry unreadable: server mode selection fails closed (never PAIRING_SERVER)", CH1, mode_json("server"), {"reject": "REGISTRY_UNREADABLE"}, default="UNREADABLE"); n += 1

# chain shape
PAIRED_EST = dict(mode=mode_json("established"), registry=PAIRED1)
chain_vec(n, "an empty chain", [], **PAIRED_EST, expect={"reject": "CHAIN_LENGTH"}); n += 1
chain_vec(n, "a 1-certificate chain: the leaf only", [LEAF1], **PAIRED_EST, expect={"reject": "CHAIN_LENGTH"}); n += 1
chain_vec(n, "a 1-certificate chain: the node certificate only", [NODE1], **PAIRED_EST, expect={"reject": "CHAIN_LENGTH"}); n += 1
chain_vec(n, "a 3-certificate chain [leaf, node, node]", [LEAF1, NODE1, NODE1], **PAIRED_EST, expect={"reject": "CHAIN_LENGTH"}); n += 1
chain_vec(n, "a 3-certificate chain [leaf, node, other node]", [LEAF1, NODE1, NODE2], **PAIRED_EST, expect={"reject": "CHAIN_LENGTH"}); n += 1
chain_vec(n, "order swapped [node, leaf]: the node certificate sits at the leaf position and lacks the leaf extensions (extension presence is checked before the CA profile)", [NODE1, LEAF1], **PAIRED_EST, expect={"reject": "EXTENSION_MISSING"}); n += 1
chain_vec(n, "[leaf, leaf]: the node position holds a certificate without a subjectKeyIdentifier", [LEAF1, LEAF1], **PAIRED_EST, expect={"reject": "EXTENSION_MISSING"}); n += 1
chain_vec(n, "[node, node]: a node certificate at the leaf position lacks the leaf extensions", [NODE1, NODE1], **PAIRED_EST, expect={"reject": "EXTENSION_MISSING"}); n += 1
chain_vec(n, "leaf from node 2 with node 1's certificate (issuer name matches nothing)", [LEAF2, NODE1], **PAIRED_EST, expect={"reject": "ISSUER_MISMATCH"}); n += 1

# malformed DER
chain_vec(n, "garbage bytes as the leaf", [b"\x00\x01\x02\x03", NODE1], **PAIRED_EST, expect={"reject": "CERT_MALFORMED"}); n += 1
chain_vec(n, "an empty leaf", [b"", NODE1], **PAIRED_EST, expect={"reject": "CERT_MALFORMED"}); n += 1
chain_vec(n, "a truncated leaf (last 5 bytes missing)", [LEAF1[:-5], NODE1], **PAIRED_EST, expect={"reject": "CERT_MALFORMED"}); n += 1
chain_vec(n, "a truncated node certificate", [LEAF1, NODE1[:-1]], **PAIRED_EST, expect={"reject": "CERT_MALFORMED"}); n += 1
chain_vec(n, "a leaf with a trailing byte", [LEAF1 + b"\x00", NODE1], **PAIRED_EST, expect={"reject": "CERT_MALFORMED"}); n += 1
assert LEAF1[1] == 0x82
chain_vec(n, "a leaf whose outer SEQUENCE length is non-minimal (83 00 hh ll instead of 82 hh ll)", [LEAF1[:1] + b"\x83\x00" + LEAF1[2:], NODE1], **PAIRED_EST, expect={"reject": "CERT_MALFORMED"}); n += 1
chain_vec(n, "version 1 (v2 certificate) instead of v3", [forge_leaf(NIK1, LEAF_KEY1, version=1), NODE1], **PAIRED_EST, expect={"reject": "CERT_MALFORMED"}); n += 1
chain_vec(n, "version 0 (v1 certificate) instead of v3", [forge_leaf(NIK1, LEAF_KEY1, version=0), NODE1], **PAIRED_EST, expect={"reject": "CERT_MALFORMED"}); n += 1
chain_vec(n, "a leaf validity written as GeneralizedTime before 2050 (RFC 5280 requires UTCTime)", [forge_leaf(NIK1, LEAF_KEY1, not_before=T.gen_time(NOW - 3600)), NODE1], **PAIRED_EST, expect={"reject": "CERT_MALFORMED"}); n += 1

# keys
chain_vec(n, "a P-384 key in the leaf", [forge_leaf(NIK1, P384_SPKI), NODE1], **PAIRED_EST, expect={"reject": "KEY_UNSUPPORTED"}); n += 1
chain_vec(n, "an RSA-2048 key in the leaf", [forge_leaf(NIK1, RSA_SPKI), NODE1], **PAIRED_EST, expect={"reject": "KEY_UNSUPPORTED"}); n += 1
chain_vec(n, "a P-384 key as the node key (certificate otherwise well-formed)", [LEAF1, forge_node(NIK1, spki=P384_SPKI)], **PAIRED_EST, expect={"reject": "KEY_UNSUPPORTED"}); n += 1
chain_vec(n, "an RSA-2048 key as the node key", [LEAF1, forge_node(NIK1, spki=RSA_SPKI)], **PAIRED_EST, expect={"reject": "KEY_UNSUPPORTED"}); n += 1
off_curve = SPKI["key3"][:-1] + bytes([SPKI["key3"][-1] ^ 1])
chain_vec(n, "a leaf key that is a P-256 point NOT on the curve", [forge_leaf(NIK1, off_curve), NODE1], **PAIRED_EST, expect={"reject": "KEY_UNSUPPORTED"}); n += 1
compressed = bytes.fromhex("3039301306072a8648ce3d020106082a8648ce3d030107032200") + bytes([2]) + SPKI["key3"][27:59]
chain_vec(n, "a leaf key in compressed form", [forge_leaf(NIK1, compressed), NODE1], **PAIRED_EST, expect={"reject": "KEY_UNSUPPORTED"}); n += 1

# signature algorithms
sha1_leaf = forge_leaf(NIK1, LEAF_KEY1, alg=ALG_SHA1, digest="sha1")
sha1_node = forge_node(NIK1, alg=ALG_SHA1, digest="sha1")
chain_vec(n, "a SHA-1 (ecdsa-with-SHA1) signature on the leaf, genuinely signed with SHA-1", [sha1_leaf, NODE1], **PAIRED_EST, expect={"reject": "SIG_ALG_UNSUPPORTED"}); n += 1
chain_vec(n, "a SHA-1 signature on the node certificate", [LEAF1, sha1_node], **PAIRED_EST, expect={"reject": "SIG_ALG_UNSUPPORTED"}); n += 1
chain_vec(n, "SHA-1 on both certificates", [sha1_leaf, sha1_node], **PAIRED_EST, expect={"reject": "SIG_ALG_UNSUPPORTED"}); n += 1
chain_vec(n, "an ecdsa-with-SHA384 signature on the leaf (not ecdsa_secp256r1_sha256)", [forge_leaf(NIK1, LEAF_KEY1, alg=ALG_SHA384, digest="sha384"), NODE1], **PAIRED_EST, expect={"reject": "SIG_ALG_UNSUPPORTED"}); n += 1
chain_vec(n, "an ecdsa-with-SHA384 signature on the node certificate", [LEAF1, forge_node(NIK1, alg=ALG_SHA384, digest="sha384")], **PAIRED_EST, expect={"reject": "SIG_ALG_UNSUPPORTED"}); n += 1
chain_vec(n, "outer algorithm ecdsa-with-SHA256 but the signed algorithm field says SHA-1", [forge_leaf(NIK1, LEAF_KEY1, inner=ALG_SHA1), NODE1], **PAIRED_EST, expect={"reject": "SIG_ALG_UNSUPPORTED"}); n += 1
chain_vec(n, "the algorithm identifier carries NULL parameters (RFC 5758 requires them absent)", [forge_leaf(NIK1, LEAF_KEY1, alg=ALG_NULL_PARAMS), NODE1], **PAIRED_EST, expect={"reject": "SIG_ALG_UNSUPPORTED"}); n += 1

# signatures
chain_vec(n, "the leaf signed by another node's key (key2) but naming node 1 as issuer", [forge_leaf(NIK1, LEAF_KEY1, signer=D["key2"]), NODE1], **PAIRED_EST, expect={"reject": "BAD_SIGNATURE"}); n += 1
chain_vec(n, "the leaf signed by its own session key instead of the node key", [forge_leaf(NIK1, LEAF_KEY1, signer=D["key3"]), NODE1], **PAIRED_EST, expect={"reject": "BAD_SIGNATURE"}); n += 1
chain_vec(n, "the node certificate signed by a different key (not self-signed)", [LEAF1, forge_node(NIK1, signer=D["key2"])], **PAIRED_EST, expect={"reject": "BAD_SIGNATURE"}); n += 1
chain_vec(n, "a flipped bit in the leaf signature", [forge_leaf(NIK1, LEAF_KEY1, flip_sig=True), NODE1], **PAIRED_EST, expect={"reject": "BAD_SIGNATURE"}); n += 1
chain_vec(n, "a flipped bit in the node signature", [LEAF1, forge_node(NIK1, flip_sig=True)], **PAIRED_EST, expect={"reject": "BAD_SIGNATURE"}); n += 1
leaf_tbs_plain = T.leaf_tbs(SPKI["key1"], LEAF_KEY1, SN_LEAF1, NOW)
subject_offset = leaf_tbs_plain.index(T.name_der("asom-session", SPKI["key1"])) + 20
chain_vec(n, "a signed byte of the leaf (a character of its subject name) changed after signing", [forge_leaf(NIK1, LEAF_KEY1, flip_tbs=subject_offset), NODE1], **PAIRED_EST, expect={"reject": "BAD_SIGNATURE"}); n += 1
high_s_leaf_raw = None


def high_s_leaf():
    tbs = T.leaf_tbs(SPKI["key1"], LEAF_KEY1, SN_LEAF1, NOW)
    raw = T.ecdsa_sign(D["key1"], tbs)
    s = int.from_bytes(raw[32:], "big")
    hi = raw[:32] + (T.N - s).to_bytes(32, "big")
    assert T.ecdsa_verify(SPKI["key1"], tbs, hi)
    return T.assemble(tbs, hi)


chain_vec(n, "a high-S leaf signature is accepted (low-S is a producer rule; the consumer accepts both, like M02-105)", [high_s_leaf(), NODE1], **PAIRED_EST, expect=ok_expect(PIN1, "established")); n += 1
zero_pad = T.seq(T.tlv(0x02, b"\x00\x00" + T.ecdsa_sign(D["key1"], b"x")[:32].lstrip(b"\x00")), T.tlv(0x02, b"\x01"))
nonmin_leaf_tbs = T.leaf_tbs(SPKI["key1"], LEAF_KEY1, SN_LEAF1, NOW)
nonmin_leaf = T.seq(nonmin_leaf_tbs, T.ALG_SHA256, T.bit_string(zero_pad))
chain_vec(n, "a signature whose DER INTEGERs are not minimal", [nonmin_leaf, NODE1], **PAIRED_EST, expect={"reject": "BAD_SIGNATURE"}); n += 1
chain_vec(n, "a signature BIT STRING that is not a SEQUENCE of two integers", [T.seq(nonmin_leaf_tbs, T.ALG_SHA256, T.bit_string(b"\x30\x00")), NODE1], **PAIRED_EST, expect={"reject": "BAD_SIGNATURE"}); n += 1

# profile: CA bits, key usage, path length
chain_vec(n, "a CA leaf: basicConstraints CA:TRUE on the leaf", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(True, 0), e_ku({0}), e_eku([T.OID_SERVER_AUTH, T.OID_CLIENT_AUTH]), e_aki(T.ski_of(SPKI["key1"]))]), NODE1], **PAIRED_EST, expect={"reject": "LEAF_IS_CA"}); n += 1
chain_vec(n, "a CA leaf: CA:TRUE without a path length", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(True), e_ku({0}), e_eku([T.OID_SERVER_AUTH, T.OID_CLIENT_AUTH]), e_aki(T.ski_of(SPKI["key1"]))]), NODE1], **PAIRED_EST, expect={"reject": "LEAF_IS_CA"}); n += 1
chain_vec(n, "a leaf that may sign certificates (keyUsage digitalSignature + keyCertSign)", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0, 5}), e_eku([T.OID_SERVER_AUTH, T.OID_CLIENT_AUTH]), e_aki(T.ski_of(SPKI["key1"]))]), NODE1], **PAIRED_EST, expect={"reject": "LEAF_IS_CA"}); n += 1
chain_vec(n, "a leaf with a path length although CA is false", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False, 0), e_ku({0}), e_eku([T.OID_SERVER_AUTH, T.OID_CLIENT_AUTH]), e_aki(T.ski_of(SPKI["key1"]))]), NODE1], **PAIRED_EST, expect={"reject": "EXTENSION_INVALID"}); n += 1
chain_vec(n, "a leaf basicConstraints with the DEFAULT FALSE written out (not DER)", [forge_leaf(NIK1, LEAF_KEY1, exts=[T.ext(T.OID_BC, True, T.seq(T.boolean(False))), e_ku({0}), e_eku([T.OID_SERVER_AUTH, T.OID_CLIENT_AUTH]), e_aki(T.ski_of(SPKI["key1"]))]), NODE1], **PAIRED_EST, expect={"reject": "EXTENSION_INVALID"}); n += 1
chain_vec(n, "a leaf keyUsage without digitalSignature (keyEncipherment only)", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({2}), e_eku([T.OID_SERVER_AUTH, T.OID_CLIENT_AUTH]), e_aki(T.ski_of(SPKI["key1"]))]), NODE1], **PAIRED_EST, expect={"reject": "LEAF_KEY_USAGE"}); n += 1
chain_vec(n, "a node certificate without the CA bit (basicConstraints SEQUENCE {})", [LEAF1, forge_node(NIK1, exts=[e_bc(False), e_ku({5}), e_ski(T.ski_of(SPKI["key1"]))])], **PAIRED_EST, expect={"reject": "NODE_NOT_CA"}); n += 1
chain_vec(n, "a node certificate whose keyUsage lacks keyCertSign (digitalSignature)", [LEAF1, forge_node(NIK1, exts=[e_bc(True, 0), e_ku({0}), e_ski(T.ski_of(SPKI["key1"]))])], **PAIRED_EST, expect={"reject": "NODE_NOT_CA"}); n += 1
chain_vec(n, "a node certificate whose keyUsage is keyCertSign + digitalSignature (not exactly keyCertSign)", [LEAF1, forge_node(NIK1, exts=[e_bc(True, 0), e_ku({0, 5}), e_ski(T.ski_of(SPKI["key1"]))])], **PAIRED_EST, expect={"reject": "NODE_NOT_CA"}); n += 1
chain_vec(n, "a node certificate with pathLen 1", [LEAF1, forge_node(NIK1, exts=[e_bc(True, 1), e_ku({5}), e_ski(T.ski_of(SPKI["key1"]))])], **PAIRED_EST, expect={"reject": "PATHLEN_VIOLATION"}); n += 1
chain_vec(n, "a node certificate with pathLen 5", [LEAF1, forge_node(NIK1, exts=[e_bc(True, 5), e_ku({5}), e_ski(T.ski_of(SPKI["key1"]))])], **PAIRED_EST, expect={"reject": "PATHLEN_VIOLATION"}); n += 1
chain_vec(n, "a node certificate without a path length (unbounded CA)", [LEAF1, forge_node(NIK1, exts=[e_bc(True), e_ku({5}), e_ski(T.ski_of(SPKI["key1"]))])], **PAIRED_EST, expect={"reject": "PATHLEN_VIOLATION"}); n += 1
chain_vec(n, "node basicConstraints not critical", [LEAF1, forge_node(NIK1, exts=[e_bc(True, 0, critical=False), e_ku({5}), e_ski(T.ski_of(SPKI["key1"]))])], **PAIRED_EST, expect={"reject": "EXTENSION_INVALID"}); n += 1
chain_vec(n, "node keyUsage not critical", [LEAF1, forge_node(NIK1, exts=[e_bc(True, 0), e_ku({5}, critical=False), e_ski(T.ski_of(SPKI["key1"]))])], **PAIRED_EST, expect={"reject": "EXTENSION_INVALID"}); n += 1
chain_vec(n, "leaf basicConstraints not critical", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False, critical=False), e_ku({0}), e_eku([T.OID_SERVER_AUTH, T.OID_CLIENT_AUTH]), e_aki(T.ski_of(SPKI["key1"]))]), NODE1], **PAIRED_EST, expect={"reject": "EXTENSION_INVALID"}); n += 1
chain_vec(n, "leaf keyUsage not critical", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0}, critical=False), e_eku([T.OID_SERVER_AUTH, T.OID_CLIENT_AUTH]), e_aki(T.ski_of(SPKI["key1"]))]), NODE1], **PAIRED_EST, expect={"reject": "EXTENSION_INVALID"}); n += 1

# missing extensions
sk1 = T.ski_of(SPKI["key1"])
EKU_BOTH = [T.OID_SERVER_AUTH, T.OID_CLIENT_AUTH]
chain_vec(n, "the node certificate has no basicConstraints", [LEAF1, forge_node(NIK1, exts=[e_ku({5}), e_ski(sk1)])], **PAIRED_EST, expect={"reject": "EXTENSION_MISSING"}); n += 1
chain_vec(n, "the node certificate has no keyUsage", [LEAF1, forge_node(NIK1, exts=[e_bc(True, 0), e_ski(sk1)])], **PAIRED_EST, expect={"reject": "EXTENSION_MISSING"}); n += 1
chain_vec(n, "the node certificate has no subjectKeyIdentifier", [LEAF1, forge_node(NIK1, exts=[e_bc(True, 0), e_ku({5})])], **PAIRED_EST, expect={"reject": "EXTENSION_MISSING"}); n += 1
chain_vec(n, "the leaf has no basicConstraints", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_ku({0}), e_eku(EKU_BOTH), e_aki(sk1)]), NODE1], **PAIRED_EST, expect={"reject": "EXTENSION_MISSING"}); n += 1
chain_vec(n, "the leaf has no keyUsage", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_eku(EKU_BOTH), e_aki(sk1)]), NODE1], **PAIRED_EST, expect={"reject": "EXTENSION_MISSING"}); n += 1
chain_vec(n, "the leaf has no extKeyUsage", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0}), e_aki(sk1)]), NODE1], **PAIRED_EST, expect={"reject": "EXTENSION_MISSING"}); n += 1
chain_vec(n, "the leaf has no authorityKeyIdentifier", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0}), e_eku(EKU_BOTH)]), NODE1], **PAIRED_EST, expect={"reject": "EXTENSION_MISSING"}); n += 1

# AKI
chain_vec(n, "the leaf authorityKeyIdentifier is not the node subjectKeyIdentifier (20 other bytes)", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0}), e_eku(EKU_BOTH), e_aki(bytes(range(20)))]), NODE1], **PAIRED_EST, expect={"reject": "AKI_MISMATCH"}); n += 1
chain_vec(n, "the leaf authorityKeyIdentifier is the leaf's own key identifier", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0}), e_eku(EKU_BOTH), e_aki(T.ski_of(LEAF_KEY1))]), NODE1], **PAIRED_EST, expect={"reject": "AKI_MISMATCH"}); n += 1
chain_vec(n, "the leaf authorityKeyIdentifier is the right bytes truncated by one", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0}), e_eku(EKU_BOTH), e_aki(sk1[:-1])]), NODE1], **PAIRED_EST, expect={"reject": "AKI_MISMATCH"}); n += 1
chain_vec(n, "the leaf authorityKeyIdentifier is one bit off", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0}), e_eku(EKU_BOTH), e_aki(sk1[:-1] + bytes([sk1[-1] ^ 1]))]), NODE1], **PAIRED_EST, expect={"reject": "AKI_MISMATCH"}); n += 1
chain_vec(n, "the node subjectKeyIdentifier differs from the one the leaf names (node carries another SKI)", [LEAF1, forge_node(NIK1, exts=[e_bc(True, 0), e_ku({5}), e_ski(bytes(range(20)))])], **PAIRED_EST, expect={"reject": "AKI_MISMATCH"}); n += 1

# EKU
chain_vec(n, "extKeyUsage serverAuth only", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0}), e_eku([T.OID_SERVER_AUTH]), e_aki(sk1)]), NODE1], **PAIRED_EST, expect={"reject": "LEAF_EKU"}); n += 1
chain_vec(n, "extKeyUsage clientAuth only", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0}), e_eku([T.OID_CLIENT_AUTH]), e_aki(sk1)]), NODE1], **PAIRED_EST, expect={"reject": "LEAF_EKU"}); n += 1
chain_vec(n, "extKeyUsage codeSigning only", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0}), e_eku(["1.3.6.1.5.5.7.3.3"]), e_aki(sk1)]), NODE1], **PAIRED_EST, expect={"reject": "LEAF_EKU"}); n += 1
chain_vec(n, "extKeyUsage anyExtendedKeyUsage is not a substitute for the two purposes", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0}), e_eku(["2.5.29.37.0"]), e_aki(sk1)]), NODE1], **PAIRED_EST, expect={"reject": "LEAF_EKU"}); n += 1
chain_vec(n, "an empty extKeyUsage SEQUENCE", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0}), e_eku([]), e_aki(sk1)]), NODE1], **PAIRED_EST, expect={"reject": "EXTENSION_INVALID"}); n += 1
chain_vec(n, "extKeyUsage lists a third purpose besides serverAuth and clientAuth: accepted (the two required purposes are present)", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), e_ku({0}), e_eku(EKU_BOTH + ["1.3.6.1.5.5.7.3.8"]), e_aki(sk1)]), NODE1], **PAIRED_EST, expect=ok_expect(PIN1, "established")); n += 1

# unknown / duplicate extensions
chain_vec(n, "an unknown CRITICAL extension on the leaf", [forge_leaf(NIK1, LEAF_KEY1, exts=leaf_exts(SPKI["key1"]) + [T.ext("1.2.3.4.5", True, T.seq())]), NODE1], **PAIRED_EST, expect={"reject": "UNKNOWN_CRITICAL_EXTENSION"}); n += 1
chain_vec(n, "an unknown CRITICAL extension on the node certificate", [LEAF1, forge_node(NIK1, exts=node_exts(SPKI["key1"]) + [T.ext("1.2.3.4.5", True, T.seq())])], **PAIRED_EST, expect={"reject": "UNKNOWN_CRITICAL_EXTENSION"}); n += 1
chain_vec(n, "a critical nameConstraints extension on the node certificate (not understood)", [LEAF1, forge_node(NIK1, exts=node_exts(SPKI["key1"]) + [T.ext("2.5.29.30", True, T.seq())])], **PAIRED_EST, expect={"reject": "UNKNOWN_CRITICAL_EXTENSION"}); n += 1
chain_vec(n, "a critical subjectAltName on the leaf (names are not identity; not understood)", [forge_leaf(NIK1, LEAF_KEY1, exts=leaf_exts(SPKI["key1"]) + [T.ext("2.5.29.17", True, T.seq())]), NODE1], **PAIRED_EST, expect={"reject": "UNKNOWN_CRITICAL_EXTENSION"}); n += 1
chain_vec(n, "an unknown NON-critical extension on the leaf is ignored (RFC 5280)", [forge_leaf(NIK1, LEAF_KEY1, exts=leaf_exts(SPKI["key1"]) + [T.ext("1.2.3.4.5", False, T.seq())]), NODE1], **PAIRED_EST, expect=ok_expect(PIN1, "established")); n += 1
chain_vec(n, "a duplicate extension (two keyUsage) on the leaf", [forge_leaf(NIK1, LEAF_KEY1, exts=leaf_exts(SPKI["key1"]) + [e_ku({0})]), NODE1], **PAIRED_EST, expect={"reject": "EXTENSION_INVALID"}); n += 1
chain_vec(n, "keyUsage with a trailing zero bit (not a minimal named bit list)", [forge_leaf(NIK1, LEAF_KEY1, exts=[e_bc(False), T.ext(T.OID_KU, True, T.bit_string(b"\x80\x00", 0)), e_eku(EKU_BOTH), e_aki(sk1)]), NODE1], **PAIRED_EST, expect={"reject": "EXTENSION_INVALID"}); n += 1

# names
chain_vec(n, "the leaf issuer is not the node subject (another node's name)", [forge_leaf(NIK1, LEAF_KEY1, issuer=T.name_der("asom-node", SPKI["key2"])), NODE1], **PAIRED_EST, expect={"reject": "ISSUER_MISMATCH"}); n += 1
chain_vec(n, "the leaf issuer differs from the node subject in one character", [forge_leaf(NIK1, LEAF_KEY1, issuer=T.seq(T.setof(T.seq(T.oid(T.OID_CN), T.utf8("asom-node " + T.node_tag(T.pin_of(SPKI["key1"]))[:-1] + "x"))))), NODE1], **PAIRED_EST, expect={"reject": "ISSUER_MISMATCH"}); n += 1
chain_vec(n, "the node certificate is not self-issued (issuer is another name)", [LEAF1, forge_node(NIK1, issuer=T.name_der("asom-node", SPKI["key2"]))], **PAIRED_EST, expect={"reject": "ISSUER_MISMATCH"}); n += 1
chain_vec(n, "the leaf is issued by a name the node does not carry but signs correctly (names are bytes, not meaning)", [forge_leaf(NIK1, LEAF_KEY1, issuer=T.seq(T.setof(T.seq(T.oid(T.OID_CN), T.tlv(0x13, b"asom-node " + T.node_tag(T.pin_of(SPKI["key1"])).encode()))))), NODE1], **PAIRED_EST, expect={"reject": "ISSUER_MISMATCH"}); n += 1

# the second node's chain, mixed with the first node's leaf key
chain_vec(n, "a replayed leaf: node 2's chain with node 1's leaf certificate", [LEAF1, NODE2], **PAIRED_EST, expect={"reject": "ISSUER_MISMATCH"}); n += 1

write(os.path.join(ROOT, "wire", "W05-fingerprints.json"), envelope(
    "W05", ["trust.md 2.3", "trust.md 2.4", "trust.md 3.2", "trust.md 15 (certs/node-leaf-templates.json, certs/verify-chain.json)", "LAB_SPEC.md 4.5", "LAB_SPEC.md 7.4"], w05.items))
print("W05:", len(w05.items), "vectors")

# ------------------------------------------------------------------------------------------------------------------------------------ W04
w04 = Collector("W04")
PIN_D = hashlib.sha256(b"asom-vector/spki/D").digest()
PIN_S = hashlib.sha256(b"asom-vector/spki/S").digest()
SECRET = bytes(range(32))
NONCE_S = b"\xa5" * 32
NONCE_D = b"\x5a" * 32
EX_URI = "asom-pair:1?k=jZcpQhuRMg6yNp81ynXIGjfGCcZeStuotMWTOtRFPgs&a=192.168.1.40:11436,100.101.7.9:11436&s=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&x=1790000120&n=Dell%20tower"
assert len(EX_URI) == 170 and T.b64u(PIN_D) == "jZcpQhuRMg6yNp81ynXIGjfGCcZeStuotMWTOtRFPgs" and T.b64u(SECRET) == "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
WORKED_PROOF = "becQGXmCCTwCLN4BK4hj0qa3AW-AT9DgDikj7RA_baM"
assert T.b64u(T.proof(SECRET, PIN_D, PIN_S, NONCE_S)) == WORKED_PROOF
assert T.sas_display(PIN_D, PIN_S, NONCE_S, NONCE_D) == "865 412"
assert T.transcript(PIN_D, PIN_S, NONCE_S, NONCE_D).hex() == "a7cfd74766ca9d6eab6393ad20c52a1e103e394678101b503f1e48c6f473ffb1"
assert T.b64u(T.proof(SECRET, PIN_S, PIN_D, NONCE_S)) == "r3iMlWpfvBBdchUUFGHUoCSNIi283ymNyEqeqNjoAC8"

QNOW = 1790000000
K_B64 = T.b64u(PIN_D)
S_B64 = T.b64u(SECRET)


def uri(k=K_B64, a="192.168.1.40:11436", s=S_B64, x=QNOW + 120, n="Dell%20tower", order="kasxn", drop=(), extra=()):
    parts = {"k": "k=" + k, "a": "a=" + a, "s": "s=" + s, "x": "x=" + str(x), "n": "n=" + n}
    items = [parts[c] for c in order if c not in drop] + list(extra)
    return "asom-pair:1?" + "&".join(items)


def qr_ok(num, desc, text, now=QNOW, policy="mesh"):
    r = T.qr_parse(text, now, loopback_ok=(policy == "loopbackForTests"))
    assert r[0] == "ok", (desc, r)
    w04.add(num, desc, {"kind": "qrParse", "uri": text, "nowSec": now, "policy": policy}, {"ok": r[1]})


def qr_bad(num, desc, text, code, now=QNOW, policy="mesh"):
    r = T.qr_parse(text, now, loopback_ok=(policy == "loopbackForTests"))
    assert r == ("reject", code), (desc, r, code)
    w04.add(num, desc, {"kind": "qrParse", "uri": text, "nowSec": now, "policy": policy}, {"reject": code})


n = 1
qr_ok(n, "the worked example of trust.md 4.2 (170 characters)", EX_URI); n += 1
qr_ok(n, "parameters in another order are valid (n k s a x)", uri(order="nkasx")); n += 1
qr_ok(n, "an IPv6 ULA endpoint in brackets", uri(a="[fd12:3456:789a::1]:11436")); n += 1
qr_ok(n, "an IPv6 link-local endpoint (no zone identifier)", uri(a="[fe80::1234:5678]:1")); n += 1
qr_ok(n, "an IPv6 literal in upper case is accepted and normalised to RFC 5952 lower case", uri(a="[FD12:0:0:0:0:0:0:1]:11436")); n += 1
qr_ok(n, "four endpoints (the maximum)", uri(a="10.0.0.1:1,172.16.0.2:2,192.168.0.3:3,100.64.0.4:65535")); n += 1
qr_ok(n, "the overlay range edges 100.64.0.0 and 100.127.255.255", uri(a="100.64.0.0:9,100.127.255.255:9")); n += 1
qr_ok(n, "172.16.0.0 and 172.31.255.255 (RFC 1918 edges) and link-local 169.254.0.1", uri(a="172.16.0.0:9,172.31.255.255:9,169.254.0.1:9")); n += 1
qr_ok(n, "a UTF-8 name with a non-ASCII letter, an apostrophe and a space", uri(n=T.pct_encode("Madhav's Pixel Ü"))); n += 1
qr_ok(n, "a name of exactly 32 code points", uri(n=T.pct_encode("A" * 32))); n += 1
qr_ok(n, "a name of 32 code points that are 4-byte emoji (128 bytes)", uri(n=T.pct_encode("\U0001F600" * 32))); n += 1
qr_ok(n, "lower-case hex in a percent escape is accepted", uri(n="a%2fb")); n += 1
qr_ok(n, "now = x + 59 s (inside the 60 s grace)", EX_URI, now=QNOW + 120 + 59); n += 1
qr_ok(n, "x = now + 180 s (the largest accepted lead)", uri(x=QNOW + 180)); n += 1
qr_ok(n, "loopback endpoints are accepted only under the test-only policy", uri(a="127.0.0.1:11436,[::1]:11436"), policy="loopbackForTests"); n += 1
qr_ok(n, "port 65535 and port 1", uri(a="192.168.1.1:65535,192.168.1.2:1")); n += 1

n = 30
qr_bad(n, "missing k", uri(drop="k"), "MISSING_FIELD"); n += 1
qr_bad(n, "missing a", uri(drop="a"), "MISSING_FIELD"); n += 1
qr_bad(n, "missing s", uri(drop="s"), "MISSING_FIELD"); n += 1
qr_bad(n, "missing x", uri(drop="x"), "MISSING_FIELD"); n += 1
qr_bad(n, "missing n", uri(drop="n"), "MISSING_FIELD"); n += 1
qr_bad(n, "no parameters at all", "asom-pair:1?", "MISSING_FIELD"); n += 1
qr_bad(n, "a repeated parameter", uri(extra=("k=" + K_B64,)), "DUPLICATE_FIELD"); n += 1
qr_bad(n, "an unknown parameter", uri(extra=("z=1",)), "UNKNOWN_FIELD"); n += 1
qr_bad(n, "a parameter without '='", uri(extra=("flag",)), "SYNTAX"); n += 1
qr_bad(n, "a parameter with an empty name", uri(extra=("=1",)), "SYNTAX"); n += 1
qr_bad(n, "wrong version 2", EX_URI.replace("asom-pair:1?", "asom-pair:2?"), "VERSION"); n += 1
qr_bad(n, "wrong version 0", EX_URI.replace("asom-pair:1?", "asom-pair:0?"), "VERSION"); n += 1
qr_bad(n, "no version", EX_URI.replace("asom-pair:1?", "asom-pair:?"), "VERSION"); n += 1
qr_bad(n, "another scheme", EX_URI.replace("asom-pair:", "https:"), "SCHEME"); n += 1
qr_bad(n, "an upper-case scheme", EX_URI.replace("asom-pair:", "ASOM-PAIR:"), "SCHEME"); n += 1
qr_bad(n, "expired: now = x + 60 s", EX_URI, "EXPIRED", now=QNOW + 120 + 60); n += 1
qr_bad(n, "expired long ago", EX_URI, "EXPIRED", now=QNOW + 100000); n += 1
qr_bad(n, "x beyond now + 180 s", uri(x=QNOW + 181), "EXPIRY_TOO_FAR"); n += 1
qr_bad(n, "x absurdly far ahead", uri(x=999999999999), "EXPIRY_TOO_FAR"); n += 1
qr_bad(n, "x with a leading zero", uri(x="01790000120"), "BAD_EXPIRY"); n += 1
qr_bad(n, "x negative", uri(x="-1"), "BAD_EXPIRY"); n += 1
qr_bad(n, "x hexadecimal", uri(x="0x6aa4c9f8"), "BAD_EXPIRY"); n += 1
qr_bad(n, "x empty", uri(x=""), "BAD_EXPIRY"); n += 1
qr_bad(n, "x with a fraction", uri(x="1790000120.5"), "BAD_EXPIRY"); n += 1
qr_bad(n, "x with 13 digits", uri(x="1790000120000"), "BAD_EXPIRY"); n += 1

n = 60
for desc, a, code in [
    ("a public IPv4 address", "8.8.8.8:11436", "ADDRESS_NOT_ELIGIBLE"),
    ("another public IPv4 address among private ones", "192.168.1.40:11436,93.184.216.34:11436", "ADDRESS_NOT_ELIGIBLE"),
    ("a global-unicast IPv6 address", "[2001:db8::1]:11436", "ADDRESS_NOT_ELIGIBLE"),
    ("the IPv4 wildcard", "0.0.0.0:11436", "ADDRESS_NOT_ELIGIBLE"),
    ("the IPv6 wildcard", "[::]:11436", "ADDRESS_NOT_ELIGIBLE"),
    ("IPv4 loopback", "127.0.0.1:11436", "ADDRESS_NOT_ELIGIBLE"),
    ("IPv6 loopback", "[::1]:11436", "ADDRESS_NOT_ELIGIBLE"),
    ("172.15.255.255 (just below RFC 1918 172.16/12)", "172.15.255.255:1", "ADDRESS_NOT_ELIGIBLE"),
    ("172.32.0.0 (just above 172.16/12)", "172.32.0.0:1", "ADDRESS_NOT_ELIGIBLE"),
    ("100.63.255.255 (just below the overlay range)", "100.63.255.255:1", "ADDRESS_NOT_ELIGIBLE"),
    ("100.128.0.0 (just above the overlay range)", "100.128.0.0:1", "ADDRESS_NOT_ELIGIBLE"),
    ("a multicast address", "224.0.0.251:5353", "ADDRESS_NOT_ELIGIBLE"),
    ("the limited broadcast address", "255.255.255.255:1", "ADDRESS_NOT_ELIGIBLE"),
    ("an IPv6 multicast address", "[ff02::fb]:5353", "ADDRESS_NOT_ELIGIBLE"),
    ("a DNS name", "dell.local:11436", "DNS_NAME"),
    ("a DNS name with a registered domain", "example.com:443", "DNS_NAME"),
    ("localhost", "localhost:11436", "DNS_NAME"),
    ("a bare host with no port", "192.168.1.40", "ENDPOINT_SYNTAX"),
    ("an IPv4 address with three octets", "192.168.1:1", "ENDPOINT_SYNTAX"),
    ("an IPv4 address with a leading zero in an octet", "192.168.01.1:1", "ENDPOINT_SYNTAX"),
    ("an IPv4 octet above 255", "192.168.1.256:1", "ENDPOINT_SYNTAX"),
    ("port 0", "192.168.1.1:0", "ENDPOINT_SYNTAX"),
    ("port 65536", "192.168.1.1:65536", "ENDPOINT_SYNTAX"),
    ("a port with a leading zero", "192.168.1.1:01", "ENDPOINT_SYNTAX"),
    ("an empty port", "192.168.1.1:", "ENDPOINT_SYNTAX"),
    ("an IPv6 literal without brackets", "fd00::1:11436", "ENDPOINT_SYNTAX"),
    ("an IPv6 literal with a zone identifier", "[fe80::1%25en0]:1", "ENDPOINT_SYNTAX"),
    ("an IPv6 literal with two '::'", "[fd00::1::2]:1", "ENDPOINT_SYNTAX"),
    ("an IPv6 literal with a dotted IPv4 tail", "[fd00::1.2.3.4]:1", "ENDPOINT_SYNTAX"),
    ("an IPv6 literal with nine groups", "[fd00:1:2:3:4:5:6:7:8]:1", "ENDPOINT_SYNTAX"),
    ("an empty endpoint list", "", "ENDPOINT_COUNT"),
    ("five endpoints", "10.0.0.1:1,10.0.0.2:2,10.0.0.3:3,10.0.0.4:4,10.0.0.5:5", "ENDPOINT_COUNT"),
    ("an empty endpoint between commas", "10.0.0.1:1,,10.0.0.2:2", "ENDPOINT_SYNTAX"),
]:
    qr_bad(n, desc, uri(a=a), code); n += 1

n = 100
good_k = K_B64
qr_bad(n, "k one character short", uri(k=good_k[:-1]), "BAD_PIN"); n += 1
qr_bad(n, "k one character long", uri(k=good_k + "A"), "BAD_PIN"); n += 1
qr_bad(n, "k with padding '='", uri(k=good_k[:-1] + "="), "BAD_PIN"); n += 1
qr_bad(n, "k in the standard alphabet ('+' for '-')", uri(k=good_k.replace("-", "+") if "-" in good_k else good_k[:-1] + "+"), "BAD_PIN"); n += 1
qr_bad(n, "k with non-zero trailing bits (last character changed)", uri(k=good_k[:-1] + ("B" if good_k[-1] != "B" else "C")), "BAD_PIN"); n += 1
qr_bad(n, "k empty", uri(k=""), "BAD_PIN"); n += 1
qr_bad(n, "s one character short", uri(s=S_B64[:-1]), "BAD_SECRET"); n += 1
qr_bad(n, "s not base64url", uri(s="!" * 43), "BAD_SECRET"); n += 1
qr_bad(n, "s with non-zero trailing bits", uri(s=S_B64[:-1] + "B"), "BAD_SECRET"); n += 1
for desc, nm, code in [
    ("n empty", "", "BAD_NAME"),
    ("n with a raw space", "Dell tower", "BAD_NAME"),
    ("n with '+' for a space", "Dell+tower", "BAD_NAME"),
    ("n with a raw apostrophe (not unreserved)", "Madhav's", "BAD_NAME"),
    ("n with a raw non-ASCII character", "DellÜ", "BAD_NAME"),
    ("n with a bad hex digit", "%ZZ", "BAD_NAME"),
    ("n with a truncated escape", "abc%4", "BAD_NAME"),
    ("n with a trailing '%'", "abc%", "BAD_NAME"),
    ("n with invalid UTF-8 (C3 28)", "%C3%28", "BAD_NAME"),
    ("n with an overlong UTF-8 encoding of '/' (C0 AF)", "%C0%AF", "BAD_NAME"),
    ("n with a UTF-8 encoded surrogate (ED A0 80)", "%ED%A0%80", "BAD_NAME"),
    ("n with a NUL", "a%00b", "BAD_NAME"),
    ("n with a newline", "a%0Ab", "BAD_NAME"),
    ("n with a DEL character", "a%7Fb", "BAD_NAME"),
    ("n with a C1 control (U+0085)", "a%C2%85b", "BAD_NAME"),
    ("n with a right-to-left override (U+202E)", "a%E2%80%AEb", "BAD_NAME"),
    ("n with U+2028 LINE SEPARATOR", "a%E2%80%A8b", "BAD_NAME"),
    ("n with the replacement character U+FFFD", "a%EF%BF%BDb", "BAD_NAME"),
    ("n of 33 ASCII code points", "A" * 33, "NAME_TOO_LONG"),
    ("n of 33 code points that are emoji", T.pct_encode("\U0001F600" * 33), "NAME_TOO_LONG"),
]:
    qr_bad(n, desc, uri(n=nm), code); n += 1
qr_bad(n, "a payload longer than 2048 characters", uri(n="A" * 2100), "TOO_LONG"); n += 1

# encoder vectors: the canonical writer (k a s x n order, upper-case percent escapes)
n = 150
for desc, fields in [
    ("encode the worked example", {"k": K_B64, "a": ["192.168.1.40:11436", "100.101.7.9:11436"], "s": S_B64, "x": 1790000120, "n": "Dell tower"}),
    ("encode an IPv6 endpoint and a UTF-8 name", {"k": K_B64, "a": ["[fd12:3456:789a::1]:11436"], "s": S_B64, "x": 1790000100, "n": "Madhav's Pixel Ü"}),
    ("encode a 32-code-point emoji name and four endpoints", {"k": K_B64, "a": ["10.0.0.1:1", "172.16.0.2:2", "192.168.0.3:3", "100.64.0.4:65535"], "s": S_B64, "x": 1790000119, "n": "\U0001F600" * 32}),
    ("encode a name that needs every escape class (space, quote, percent, slash, ampersand, equals, hash)", {"k": K_B64, "a": ["10.0.0.1:1"], "s": S_B64, "x": 1790000050, "n": "a b'c%d/e&f=g#h"}),
]:
    w04.add(n, desc, {"kind": "qrEncode", **fields, "nowSec": QNOW}, {"ok": {"uri": T.qr_encode(fields["k"], fields["a"], fields["s"], fields["x"], fields["n"])}}); n += 1

# 200..: proof, SAS, transcript (trust.md 4.5)
n = 200
w04.add(n, "the worked vector of trust.md 4.5: proof, SAS and transcript", {"kind": "proofSas", "pinD": T.b64u(PIN_D), "pinS": T.b64u(PIN_S), "secret": T.b64u(SECRET), "nonceS": T.b64u(NONCE_S), "nonceD": T.b64u(NONCE_D)},
        {"ok": {"proof": WORKED_PROOF, "sas": "865 412", "transcriptHex": "a7cfd74766ca9d6eab6393ad20c52a1e103e394678101b503f1e48c6f473ffb1"}}); n += 1


def pv(num, desc, presented, kind="proofVerify", **over):
    inp = {"kind": kind, "pinD": T.b64u(PIN_D), "pinS": T.b64u(PIN_S), "secret": T.b64u(SECRET), "nonceS": T.b64u(NONCE_S), "nonceD": T.b64u(NONCE_D), "presented": presented, "negative": over.pop("negative", "")}
    inp.update(over)
    return inp


valid_expect = {"ok": {"valid": True}}
w04.add(n, "the worked proof is valid", pv(n, "", WORKED_PROOF), valid_expect); n += 1
w04.add(n, "pin-swap negative: the proof computed with pin_D and pin_S exchanged (trust.md 4.5) is rejected", pv(n, "", "r3iMlWpfvBBdchUUFGHUoCSNIi283ymNyEqeqNjoAC8", negative="pin-swap"), {"reject": "PAIRING_PROOF_INVALID"}); n += 1
nonce_swapped = T.b64u(T.proof(SECRET, PIN_D, PIN_S, NONCE_D))
w04.add(n, "nonce-swap negative: the proof computed over nonce_D instead of nonce_S is rejected", pv(n, "", nonce_swapped, negative="nonce-swap"), {"reject": "PAIRING_PROOF_INVALID"}); n += 1
wrong_secret = T.b64u(T.proof(bytes(range(1, 33)), PIN_D, PIN_S, NONCE_S))
w04.add(n, "a proof under another secret is rejected", pv(n, "", wrong_secret, negative="wrong-secret"), {"reject": "PAIRING_PROOF_INVALID"}); n += 1
w04.add(n, "a relay: S2 presents S1's proof (the proof binds pin_S)", pv(n, "", WORKED_PROOF, pinS=T.b64u(hashlib.sha256(b"asom-vector/spki/S2").digest()), negative="relay"), {"reject": "PAIRING_PROOF_INVALID"}); n += 1
w04.add(n, "a proof of 31 bytes (a prefix of the right proof) is rejected", pv(n, "", T.b64u(T.proof(SECRET, PIN_D, PIN_S, NONCE_S)[:31]), negative="short"), {"reject": "PAIRING_PROOF_INVALID"}); n += 1
w04.add(n, "an all-zero proof is rejected", pv(n, "", T.b64u(bytes(32)), negative="zero"), {"reject": "PAIRING_PROOF_INVALID"}); n += 1
w04.add(n, "a proof that is not base64url is rejected", pv(n, "", "!!!", negative="encoding"), {"reject": "PAIRING_PROOF_INVALID"}); n += 1
w04.add(n, "a proof with the right bytes but standard-alphabet characters is rejected", pv(n, "", T.b64u(T.proof(SECRET, PIN_D, PIN_S, NONCE_S)).replace("_", "/").replace("-", "+") + "=", negative="encoding"), {"reject": "PAIRING_PROOF_INVALID"}); n += 1
w04.add(n, "a SAS is not a proof: the six digits as a proof are rejected", pv(n, "", "865412", negative="sas"), {"reject": "PAIRING_PROOF_INVALID"}); n += 1

# SAS zero padding and ordering: search for a case whose SAS begins with 0 and one whose groups are 000 xxx
rng = random.Random(20260930)
found_zero = None
for _ in range(100000):
    nd, ns = rng.randbytes(32), rng.randbytes(32)
    if T.sas(PIN_D, PIN_S, ns, nd).startswith("00"):
        found_zero = (ns, nd)
        break
ns, nd = found_zero
w04.add(n, "a SAS with two leading zeros is zero-padded to 6 digits and grouped 3+3", {"kind": "proofSas", "pinD": T.b64u(PIN_D), "pinS": T.b64u(PIN_S), "secret": T.b64u(SECRET), "nonceS": T.b64u(ns), "nonceD": T.b64u(nd)},
        {"ok": {"proof": T.b64u(T.proof(SECRET, PIN_D, PIN_S, ns)), "sas": T.sas_display(PIN_D, PIN_S, ns, nd), "transcriptHex": T.transcript(PIN_D, PIN_S, ns, nd).hex()}}, origin="generated"); n += 1
w04.add(n, "the SAS and transcript are NOT symmetric in the pins: exchanging pin_D and pin_S changes both", {"kind": "proofSas", "pinD": T.b64u(PIN_S), "pinS": T.b64u(PIN_D), "secret": T.b64u(SECRET), "nonceS": T.b64u(NONCE_S), "nonceD": T.b64u(NONCE_D)},
        {"ok": {"proof": T.b64u(T.proof(SECRET, PIN_S, PIN_D, NONCE_S)), "sas": T.sas_display(PIN_S, PIN_D, NONCE_S, NONCE_D), "transcriptHex": T.transcript(PIN_S, PIN_D, NONCE_S, NONCE_D).hex()}}); n += 1
w04.add(n, "the SAS and transcript are NOT symmetric in the nonces: exchanging nonce_S and nonce_D changes both", {"kind": "proofSas", "pinD": T.b64u(PIN_D), "pinS": T.b64u(PIN_S), "secret": T.b64u(SECRET), "nonceS": T.b64u(NONCE_D), "nonceD": T.b64u(NONCE_S)},
        {"ok": {"proof": T.b64u(T.proof(SECRET, PIN_D, PIN_S, NONCE_D)), "sas": T.sas_display(PIN_D, PIN_S, NONCE_D, NONCE_S), "transcriptHex": T.transcript(PIN_D, PIN_S, NONCE_D, NONCE_S).hex()}}); n += 1

# 20 seeded random cases, each with its pin-swap and nonce-swap negative
rng = random.Random(4)
for i in range(20):
    pd, ps, sec, nsr, ndr = (rng.randbytes(32) for _ in range(5))
    base = {"pinD": T.b64u(pd), "pinS": T.b64u(ps), "secret": T.b64u(sec), "nonceS": T.b64u(nsr), "nonceD": T.b64u(ndr)}
    w04.add(300 + i, "seeded random case %d (random.Random(4)): proof, SAS and transcript" % (i + 1), {"kind": "proofSas", **base},
            {"ok": {"proof": T.b64u(T.proof(sec, pd, ps, nsr)), "sas": T.sas_display(pd, ps, nsr, ndr), "transcriptHex": T.transcript(pd, ps, nsr, ndr).hex()}}, origin="generated")
    w04.add(320 + i, "seeded random case %d: the pin-swap proof is rejected" % (i + 1),
            {"kind": "proofVerify", **base, "presented": T.b64u(T.proof(sec, ps, pd, nsr)), "negative": "pin-swap"}, {"reject": "PAIRING_PROOF_INVALID"}, origin="generated")
    w04.add(340 + i, "seeded random case %d: the nonce-swap proof is rejected" % (i + 1),
            {"kind": "proofVerify", **base, "presented": T.b64u(T.proof(sec, pd, ps, ndr)), "negative": "nonce-swap"}, {"reject": "PAIRING_PROOF_INVALID"}, origin="generated")
    w04.add(360 + i, "seeded random case %d: the proof itself is valid" % (i + 1),
            {"kind": "proofVerify", **base, "presented": T.b64u(T.proof(sec, pd, ps, nsr)), "negative": ""}, {"ok": {"valid": True}}, origin="generated")

# 400..: pairing messages (trust.md 4.4 with the r3 changes of LAB_SPEC 7.3)
n = 400
NS_B64, ND_B64 = T.b64u(NONCE_S), T.b64u(NONCE_D)
PR_B64 = WORKED_PROOF


def jcs(o):
    return json.dumps(o, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def hello_obj(**over):
    o = {"v": 1, "nonceS": NS_B64, "proof": PR_B64, "name": "Madhav's iPhone", "platform": "ios", "keyTier": "secure-enclave", "endpoints": []}
    o.update(over)
    return {k: v for k, v in o.items() if v is not ...}


def msg_ok(num, desc, typ, obj_or_text, normal=None):
    text = obj_or_text if isinstance(obj_or_text, str) else json.dumps(obj_or_text, ensure_ascii=False)
    w04.add(num, desc, {"kind": "message", "type": typ, "payload": text}, {"ok": {"normal": normal if normal is not None else jcs(json.loads(text))}})


def msg_bad(num, desc, typ, obj_or_text, code):
    text = obj_or_text if isinstance(obj_or_text, str) else json.dumps(obj_or_text, ensure_ascii=False)
    w04.add(num, desc, {"kind": "message", "type": typ, "payload": text}, {"reject": code})


msg_ok(n, "PAIR_HELLO: the trust.md 4.4 shape with no endpoints", "PAIR_HELLO", hello_obj()); n += 1
msg_ok(n, "PAIR_HELLO with one lan endpoint and one overlay endpoint", "PAIR_HELLO", hello_obj(endpoints=[{"addr": "192.168.1.9", "port": 11436, "via": "lan"}, {"addr": "fd7a::1", "port": 1, "via": "overlay"}])); n += 1
msg_ok(n, "PAIR_HELLO platform windows (the r3 enum of LAB_SPEC 7.2, not the older five)", "PAIR_HELLO", hello_obj(platform="windows")); n += 1
msg_ok(n, "PAIR_HELLO platform ubuntu-touch", "PAIR_HELLO", hello_obj(platform="ubuntu-touch")); n += 1
msg_ok(n, "PAIR_HELLO keyTier file", "PAIR_HELLO", hello_obj(keyTier="file")); n += 1
msg_ok(n, "PAIR_HELLO: an unknown member is ignored and not kept", "PAIR_HELLO", hello_obj(extra="x", locSeed=NS_B64), normal=jcs(hello_obj())); n += 1
msg_ok(n, "PAIR_HELLO: a name of 32 code points", "PAIR_HELLO", hello_obj(name="x" * 32)); n += 1
msg_ok(n, "PAIR_HELLO: a name of 32 emoji code points", "PAIR_HELLO", hello_obj(name="\U0001F600" * 32)); n += 1
msg_bad(n, "PAIR_HELLO: a name of 33 code points", "PAIR_HELLO", hello_obj(name="x" * 33), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: an empty name", "PAIR_HELLO", hello_obj(name=""), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: a name with a control character", "PAIR_HELLO", hello_obj(name="a\u0007b"), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: a platform outside the enum", "PAIR_HELLO", hello_obj(platform="freebsd"), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: a platform in the wrong case", "PAIR_HELLO", hello_obj(platform="iOS"), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: a keyTier outside the enum", "PAIR_HELLO", hello_obj(keyTier="hsm"), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: nonceS of 31 bytes", "PAIR_HELLO", hello_obj(nonceS=T.b64u(bytes(31))), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: nonceS with padding", "PAIR_HELLO", hello_obj(nonceS=NS_B64[:-1] + "="), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: proof missing", "PAIR_HELLO", hello_obj(proof=...), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: endpoints missing", "PAIR_HELLO", hello_obj(endpoints=...), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: five endpoints", "PAIR_HELLO", hello_obj(endpoints=[{"addr": "10.0.0.%d" % i, "port": 1, "via": "lan"} for i in range(5)]), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: an endpoint with via wan", "PAIR_HELLO", hello_obj(endpoints=[{"addr": "10.0.0.1", "port": 1, "via": "wan"}]), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: an endpoint that names a DNS host", "PAIR_HELLO", hello_obj(endpoints=[{"addr": "dell.local", "port": 1, "via": "lan"}]), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: an endpoint with port 0", "PAIR_HELLO", hello_obj(endpoints=[{"addr": "10.0.0.1", "port": 0, "via": "lan"}]), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: v is 2", "PAIR_HELLO", hello_obj(v=2), "VERSION_UNSUPPORTED"); n += 1
msg_bad(n, "PAIR_HELLO: v is missing", "PAIR_HELLO", hello_obj(v=...), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: v is the string \"1\"", "PAIR_HELLO", hello_obj(v="1"), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: v is 1.0 (a float is not in the JSON profile)", "PAIR_HELLO", json.dumps(hello_obj()).replace('"v": 1', '"v": 1.0'), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: a duplicate member", "PAIR_HELLO", '{"v":1,"v":1,"nonceS":"%s","proof":"%s","name":"a","platform":"ios","keyTier":"file","endpoints":[]}' % (NS_B64, PR_B64), "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: not an object", "PAIR_HELLO", "[1]", "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: not JSON", "PAIR_HELLO", "hello", "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_HELLO: an empty payload", "PAIR_HELLO", "", "PROTOCOL_ERROR"); n += 1
chal = {"v": 1, "nonceD": ND_B64, "name": "Dell tower", "platform": "linux", "keyTier": "file"}
msg_ok(n, "PAIR_CHALLENGE: the trust.md 4.4 shape", "PAIR_CHALLENGE", chal); n += 1
msg_ok(n, "PAIR_CHALLENGE: an unknown member is ignored", "PAIR_CHALLENGE", {**chal, "queuePos": 1}, normal=jcs(chal)); n += 1
msg_bad(n, "PAIR_CHALLENGE: nonceD missing", "PAIR_CHALLENGE", {k: v for k, v in chal.items() if k != "nonceD"}, "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_CHALLENGE: a 33-code-point name", "PAIR_CHALLENGE", {**chal, "name": "y" * 33}, "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_CHALLENGE: platform android-tv", "PAIR_CHALLENGE", {**chal, "platform": "android-tv"}, "PROTOCOL_ERROR"); n += 1
msg_ok(n, "PAIR_DECISION approve true", "PAIR_DECISION", {"v": 1, "approve": True}); n += 1
msg_ok(n, "PAIR_DECISION approve false", "PAIR_DECISION", {"v": 1, "approve": False}); n += 1
msg_bad(n, "PAIR_DECISION: approve is the string true", "PAIR_DECISION", {"v": 1, "approve": "true"}, "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_DECISION: approve is 1", "PAIR_DECISION", {"v": 1, "approve": 1}, "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_DECISION: approve missing", "PAIR_DECISION", {"v": 1}, "PROTOCOL_ERROR"); n += 1
tr = T.b64u(T.transcript(PIN_D, PIN_S, NONCE_S, NONCE_D))
msg_ok(n, "PAIR_COMMIT: v and transcript only (r3: no locSeed)", "PAIR_COMMIT", {"v": 1, "transcript": tr}); n += 1
msg_ok(n, "PAIR_COMMIT: a received locSeed is ignored like any unknown member and never kept", "PAIR_COMMIT", {"v": 1, "transcript": tr, "locSeed": NS_B64}, normal=jcs({"v": 1, "transcript": tr})); n += 1
msg_bad(n, "PAIR_COMMIT: transcript missing", "PAIR_COMMIT", {"v": 1, "locSeed": NS_B64}, "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_COMMIT: transcript of 31 bytes", "PAIR_COMMIT", {"v": 1, "transcript": T.b64u(bytes(31))}, "PROTOCOL_ERROR"); n += 1
msg_bad(n, "PAIR_COMMIT: transcript given as hex", "PAIR_COMMIT", {"v": 1, "transcript": T.transcript(PIN_D, PIN_S, NONCE_S, NONCE_D).hex()}, "PROTOCOL_ERROR"); n += 1
msg_ok(n, "PAIR_COMMIT_ACK: v and transcript", "PAIR_COMMIT_ACK", {"v": 1, "transcript": tr}); n += 1
msg_bad(n, "PAIR_COMMIT_ACK: v is 2", "PAIR_COMMIT_ACK", {"v": 2, "transcript": tr}, "VERSION_UNSUPPORTED"); n += 1

# 500..: the pairing state machines of trust.md 4.6, as traces of "<state>:<effects>" typed by hand
n = 500
WORKED = {"pinD": T.b64u(PIN_D), "pinS": T.b64u(PIN_S)}
ID_ = lambda b: T.b64u(b)  # noqa: E731
BADPROOF = T.b64u(bytes(32))


def d_open(t=0):
    return {"e": "UserOpenWindow", "secretHex": SECRET.hex(), "nowMs": t}


def d_hello(t, proof=None, status="ABSENT", pin_s=None, nonce_s=None, nonce_d=NONCE_D):
    ps = pin_s if pin_s is not None else PIN_S
    ns = nonce_s if nonce_s is not None else NONCE_S
    pr = proof if proof is not None else T.proof(SECRET, PIN_D, ps, ns)
    return {"e": "HelloReceived", "pinS": ID_(ps), "nonceSHex": ns.hex(), "proofHex": pr.hex(), "status": status, "nonceDHex": nonce_d.hex(), "nowMs": t}


TRANSCRIPT = T.transcript(PIN_D, PIN_S, NONCE_S, NONCE_D)
T8 = TRANSCRIPT.hex()[:8]
SEND_COMMIT = "SendCommit(%s)" % T8


def fsm(num, desc, machine, script, trace, **extra):
    inp = {"kind": "fsm", "machine": machine, "pinOwn": T.b64u(PIN_D if machine == "D" else PIN_S), "script": script}
    inp.update(extra)
    w04.add(num, desc, inp, {"ok": {"trace": trace}})


fsm(n, "D happy path: window, valid hello, challenge, both approve, row, commit, acknowledgement (the worked vector: SAS 865 412)", "D",
    [d_open(1000), d_hello(5000), {"e": "ChallengeSent"}, {"e": "LocalDecision", "approve": True}, {"e": "RemoteDecision", "approve": True}, {"e": "RowDurable", "nowMs": 6000},
     {"e": "AckReceived", "transcriptHex": TRANSCRIPT.hex()}],
    ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "AWAIT_NONE:ShowConsent(865 412)", "AWAIT_LOCAL:SendDecision(true)", "COMMITTING:WritePairedRow", "AWAIT_ACK:" + SEND_COMMIT, "CLOSED:Done,Closed(done)"]); n += 1
fsm(n, "D happy path with the remote approval first", "D",
    [d_open(0), d_hello(10), {"e": "ChallengeSent"}, {"e": "RemoteDecision", "approve": True}, {"e": "LocalDecision", "approve": True}, {"e": "RowDurable", "nowMs": 20}],
    ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "AWAIT_NONE:ShowConsent(865 412)", "AWAIT_REMOTE:", "COMMITTING:SendDecision(true),WritePairedRow", "AWAIT_ACK:" + SEND_COMMIT]); n += 1
fsm(n, "D: three invalid proofs close the window (3 tries); a fourth hello meets a closed window", "D",
    [d_open(0), d_hello(1, proof=bytes(32)), d_hello(2, proof=bytes(32)), d_hello(3, proof=bytes(32)), d_hello(4)],
    ["OPEN:WindowOpened", "OPEN:Refuse(PAIRING_PROOF_INVALID)", "OPEN:Refuse(PAIRING_PROOF_INVALID)", "CLOSED:Abort(TRIES_EXHAUSTED,PAIRING_PROOF_INVALID)", "CLOSED:Refuse(PAIRING_WINDOW_CLOSED)"]); n += 1
fsm(n, "D: two invalid proofs then a valid one still pairs (tries do not carry over a valid proof)", "D",
    [d_open(0), d_hello(1, proof=bytes(32)), d_hello(2, proof=bytes(32)), d_hello(3)],
    ["OPEN:WindowOpened", "OPEN:Refuse(PAIRING_PROOF_INVALID)", "OPEN:Refuse(PAIRING_PROOF_INVALID)", "CONSUMED:SendChallenge"]); n += 1
fsm(n, "D (L6): a window accepts at most one valid proof; a second valid hello is refused", "D",
    [d_open(0), d_hello(1), d_hello(2, pin_s=hashlib.sha256(b"asom-vector/spki/S2").digest()), d_hello(3)],
    ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "CONSUMED:Refuse(PAIRING_WINDOW_CLOSED)", "CONSUMED:Refuse(PAIRING_WINDOW_CLOSED)"]); n += 1
fsm(n, "D (L7): a REVOKED pin is refused with a valid proof; the window closes and the user is warned", "D",
    [d_open(0), d_hello(1, status="REVOKED")],
    ["OPEN:WindowOpened", "CLOSED:Warn(revoked-device-tried-to-pair),Abort(REVOKED_PEER,PAIRING_REFUSED)"]); n += 1
fsm(n, "D (L7): a REVOKED pin with an INVALID proof is refused for the same reason (the status is checked first)", "D",
    [d_open(0), d_hello(1, proof=bytes(32), status="REVOKED")],
    ["OPEN:WindowOpened", "CLOSED:Warn(revoked-device-tried-to-pair),Abort(REVOKED_PEER,PAIRING_REFUSED)"]); n += 1
fsm(n, "D: a PAIRED pin cannot pair again", "D", [d_open(0), d_hello(1, status="PAIRED")], ["OPEN:WindowOpened", "CLOSED:Abort(PEER_EXISTS,PAIRING_REFUSED)"]); n += 1
fsm(n, "D (L4): a SUSPENDED pin cannot be moved to PAIRED by a pairing ceremony", "D", [d_open(0), d_hello(1, status="SUSPENDED")], ["OPEN:WindowOpened", "CLOSED:Abort(PEER_EXISTS,PAIRING_REFUSED)"]); n += 1
fsm(n, "D (L5): a corrupt registry status fails closed", "D", [d_open(0), d_hello(1, status="CORRUPT")], ["OPEN:WindowOpened", "CLOSED:Abort(REGISTRY_UNREADABLE,PAIRING_REFUSED)"]); n += 1
fsm(n, "D: an unreadable registry fails closed", "D", [d_open(0), d_hello(1, status="UNREADABLE")], ["OPEN:WindowOpened", "CLOSED:Abort(REGISTRY_UNREADABLE,PAIRING_REFUSED)"]); n += 1
fsm(n, "D: the window expires after 120 s (Tick at 119999 ms keeps it, at 120000 ms closes it)", "D",
    [d_open(0), {"e": "Tick", "nowMs": 119999}, {"e": "Tick", "nowMs": 120000}], ["OPEN:WindowOpened", "OPEN:", "CLOSED:Closed(window-expired)"]); n += 1
fsm(n, "D: a hello at the expiry instant is refused as a closed window", "D", [d_open(0), d_hello(120000)],
    ["OPEN:WindowOpened", "CLOSED:Refuse(PAIRING_WINDOW_CLOSED),Closed(window-expired)"]); n += 1
fsm(n, "D: the user cancels the open window", "D", [d_open(0), {"e": "UserCancel"}], ["OPEN:WindowOpened", "CLOSED:Closed(window-cancelled)"]); n += 1
fsm(n, "D: the local user declines", "D", [d_open(0), d_hello(1), {"e": "ChallengeSent"}, {"e": "LocalDecision", "approve": False}],
    ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "AWAIT_NONE:ShowConsent(865 412)", "CLOSED:SendDecision(false),Abort(DECLINED_LOCAL,PAIRING_REFUSED)"]); n += 1
fsm(n, "D: the remote user declines", "D", [d_open(0), d_hello(1), {"e": "ChallengeSent"}, {"e": "RemoteDecision", "approve": False}],
    ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "AWAIT_NONE:ShowConsent(865 412)", "CLOSED:Abort(DECLINED_REMOTE,PAIRING_REFUSED)"]); n += 1
fsm(n, "D (L1): the peer's approval alone never writes a row; a lost connection ends the ceremony with no row", "D",
    [d_open(0), d_hello(1), {"e": "ChallengeSent"}, {"e": "RemoteDecision", "approve": True}, {"e": "ConnectionLost"}],
    ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "AWAIT_NONE:ShowConsent(865 412)", "AWAIT_REMOTE:", "CLOSED:Abort(CONNECTION_LOST,-)"]); n += 1
fsm(n, "D: the decisions time out 120 s after the hello was consumed", "D",
    [d_open(0), d_hello(5000), {"e": "ChallengeSent"}, {"e": "Tick", "nowMs": 124999}, {"e": "Tick", "nowMs": 125000}],
    ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "AWAIT_NONE:ShowConsent(865 412)", "AWAIT_NONE:", "CLOSED:Abort(TIMEOUT,PAIRING_REFUSED)"]); n += 1
fsm(n, "D: the acknowledgement times out after 10 s; the row stays PAIRED but unconfirmed", "D",
    [d_open(0), d_hello(1), {"e": "ChallengeSent"}, {"e": "LocalDecision", "approve": True}, {"e": "RemoteDecision", "approve": True}, {"e": "RowDurable", "nowMs": 6000}, {"e": "Tick", "nowMs": 15999}, {"e": "Tick", "nowMs": 16000}],
    ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "AWAIT_NONE:ShowConsent(865 412)", "AWAIT_LOCAL:SendDecision(true)", "COMMITTING:WritePairedRow", "AWAIT_ACK:" + SEND_COMMIT, "AWAIT_ACK:",
     "CLOSED:MarkUnconfirmed(ack-timeout),Warn(may-not-have-finished),Closed(ack-timeout)"]); n += 1
fsm(n, "D: an acknowledgement with a different transcript leaves the row unconfirmed and warns", "D",
    [d_open(0), d_hello(1), {"e": "ChallengeSent"}, {"e": "LocalDecision", "approve": True}, {"e": "RemoteDecision", "approve": True}, {"e": "RowDurable", "nowMs": 6000}, {"e": "AckReceived", "transcriptHex": bytes(32).hex()}],
    ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "AWAIT_NONE:ShowConsent(865 412)", "AWAIT_LOCAL:SendDecision(true)", "COMMITTING:WritePairedRow", "AWAIT_ACK:" + SEND_COMMIT,
     "CLOSED:MarkUnconfirmed(transcript-mismatch),Warn(transcript-mismatch),Closed(transcript-mismatch)"]); n += 1
fsm(n, "D: the connection is lost while waiting for the acknowledgement", "D",
    [d_open(0), d_hello(1), {"e": "ChallengeSent"}, {"e": "LocalDecision", "approve": True}, {"e": "RemoteDecision", "approve": True}, {"e": "RowDurable", "nowMs": 6000}, {"e": "ConnectionLost"}],
    ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "AWAIT_NONE:ShowConsent(865 412)", "AWAIT_LOCAL:SendDecision(true)", "COMMITTING:WritePairedRow", "AWAIT_ACK:" + SEND_COMMIT,
     "CLOSED:MarkUnconfirmed(connection-lost),Warn(may-not-have-finished),Closed(connection-lost)"]); n += 1
fsm(n, "D: a failed row write aborts the ceremony and sends no commit", "D",
    [d_open(0), d_hello(1), {"e": "ChallengeSent"}, {"e": "LocalDecision", "approve": True}, {"e": "RemoteDecision", "approve": True}, {"e": "RowWriteFailed"}],
    ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "AWAIT_NONE:ShowConsent(865 412)", "AWAIT_LOCAL:SendDecision(true)", "COMMITTING:WritePairedRow", "CLOSED:Abort(WRITE_FAILED,PAIRING_REFUSED)"]); n += 1
fsm(n, "D: a second unknown connection raises a warning and changes nothing (trust.md 4.6)", "D",
    [d_open(0), d_hello(1), {"e": "ChallengeSent"}, {"e": "SecondUnknownConnection"}],
    ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "AWAIT_NONE:ShowConsent(865 412)", "AWAIT_NONE:Warn(second-connection)"]); n += 1
fsm(n, "D: an acknowledgement before any commit is a protocol error (the ceremony aborts)", "D",
    [d_open(0), d_hello(1), {"e": "ChallengeSent"}, {"e": "AckReceived", "transcriptHex": TRANSCRIPT.hex()}],
    ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "AWAIT_NONE:ShowConsent(865 412)", "CLOSED:Abort(PROTOCOL,PROTOCOL_ERROR)"]); n += 1

# S side
QR_PAYLOAD = uri(a="192.168.1.40:11436,100.101.7.9:11436", x=QNOW + 120)
ENDPOINTS = ["192.168.1.40:11436", "100.101.7.9:11436"]


def s_scan(text=QR_PAYLOAD, now=QNOW):
    return {"e": "Scanned", "uri": text, "nowSec": now, "policy": "mesh"}


S_HELLO = "SendHello(%s)" % T.proof(SECRET, PIN_D, PIN_S, NONCE_S).hex()[:8]


def sfsm(num, desc, script, trace):
    w04.add(num, desc, {"kind": "fsm", "machine": "S", "pinOwn": T.b64u(PIN_S), "script": script}, {"ok": {"trace": trace}})


def s_dial(pin, t=100, nonce=NONCE_S):
    return {"e": "DialResult", "presentedPin": ID_(pin) if pin is not None else None, "nonceSHex": nonce.hex(), "nowMs": t}


S_BEGIN = [s_scan(), {"e": "UserConfirmConnect", "yes": True, "nowMs": 0}, s_dial(PIN_D)]
S_BEGIN_TRACE = ["CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)", "DIALING:Dial(192.168.1.40:11436)", "SENT_HELLO:" + S_HELLO]
S_TO_AWAIT = S_BEGIN + [{"e": "ChallengeReceived", "nonceDHex": NONCE_D.hex(), "nowMs": 200}]
S_TO_AWAIT_TRACE = S_BEGIN_TRACE + ["AWAIT_NONE:ShowConsent(865 412)"]
sfsm(n, "S happy path: scan, confirm, dial, hello, challenge, both approve, commit, row, acknowledgement", S_TO_AWAIT + [
    {"e": "RemoteDecision", "approve": True}, {"e": "LocalDecision", "approve": True}, {"e": "CommitReceived", "transcriptHex": TRANSCRIPT.hex()}, {"e": "RowDurable"}],
     S_TO_AWAIT_TRACE + ["AWAIT_REMOTE:", "AWAIT_COMMIT:SendDecision(true)", "WRITING:WritePairedRow", "DONE:SendAck(%s),Done" % T8]); n += 1
sfsm(n, "S: local approval first, then the remote one", S_TO_AWAIT + [{"e": "LocalDecision", "approve": True}, {"e": "RemoteDecision", "approve": True}],
     S_TO_AWAIT_TRACE + ["AWAIT_LOCAL:SendDecision(true)", "AWAIT_COMMIT:"]); n += 1
sfsm(n, "S: the first endpoint is unreachable, the second answers", [s_scan(), {"e": "UserConfirmConnect", "yes": True, "nowMs": 0}, s_dial(None), s_dial(PIN_D)],
     ["CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)", "DIALING:Dial(192.168.1.40:11436)", "DIALING:Dial(100.101.7.9:11436)", "SENT_HELLO:" + S_HELLO]); n += 1
sfsm(n, "S: the first endpoint presents a different pin (a pin mismatch moves on), the second is right", [s_scan(), {"e": "UserConfirmConnect", "yes": True, "nowMs": 0}, s_dial(PIN_S), s_dial(PIN_D)],
     ["CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)", "DIALING:Dial(192.168.1.40:11436)", "DIALING:Dial(100.101.7.9:11436)", "SENT_HELLO:" + S_HELLO]); n += 1
sfsm(n, "S: every endpoint fails", [s_scan(), {"e": "UserConfirmConnect", "yes": True, "nowMs": 0}, s_dial(None), s_dial(PIN_S)],
     ["CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)", "DIALING:Dial(192.168.1.40:11436)", "DIALING:Dial(100.101.7.9:11436)", "FAILED:Abort(CONNECTION_LOST,-)"]); n += 1
sfsm(n, "S: an expired URI is rejected at the scan and S stays idle", [s_scan(now=QNOW + 120 + 60)], ["IDLE:ScanRejected(EXPIRED)"]); n += 1
sfsm(n, "S: a public address in the URI is rejected at the scan", [s_scan(uri(a="8.8.8.8:1"))], ["IDLE:ScanRejected(ADDRESS_NOT_ELIGIBLE)"]); n += 1
sfsm(n, "S: the user declines the connection", [s_scan(), {"e": "UserConfirmConnect", "yes": False, "nowMs": 0}], ["CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)", "IDLE:"]); n += 1
sfsm(n, "S: D answers PAIRING_WINDOW_CLOSED to the hello", S_BEGIN + [{"e": "ErrorReceived", "code": "PAIRING_WINDOW_CLOSED"}], S_BEGIN_TRACE + ["FAILED:Abort(DECLINED_REMOTE,-)"]); n += 1
sfsm(n, "S: the local user declines", S_TO_AWAIT + [{"e": "LocalDecision", "approve": False}], S_TO_AWAIT_TRACE + ["FAILED:SendDecision(false),Abort(DECLINED_LOCAL,-)"]); n += 1
sfsm(n, "S: the remote user declines", S_TO_AWAIT + [{"e": "RemoteDecision", "approve": False}], S_TO_AWAIT_TRACE + ["FAILED:Abort(DECLINED_REMOTE,-)"]); n += 1
sfsm(n, "S: no decision within 120 s of the challenge", S_TO_AWAIT + [{"e": "Tick", "nowMs": 120199}, {"e": "Tick", "nowMs": 120200}], S_TO_AWAIT_TRACE + ["AWAIT_NONE:", "FAILED:Abort(TIMEOUT,-)"]); n += 1
sfsm(n, "S: a commit whose transcript differs from the one S computed writes no row", S_TO_AWAIT + [
    {"e": "RemoteDecision", "approve": True}, {"e": "LocalDecision", "approve": True}, {"e": "CommitReceived", "transcriptHex": bytes(32).hex()}],
     S_TO_AWAIT_TRACE + ["AWAIT_REMOTE:", "AWAIT_COMMIT:SendDecision(true)", "FAILED:Abort(TRANSCRIPT_MISMATCH,-)"]); n += 1
sfsm(n, "S (L1): a commit before the local approval is a protocol error and writes no row", S_TO_AWAIT + [{"e": "CommitReceived", "transcriptHex": TRANSCRIPT.hex()}],
     S_TO_AWAIT_TRACE + ["FAILED:Abort(PROTOCOL,-)"]); n += 1
sfsm(n, "S: a commit that arrives after only the remote approval (no local approval yet) is a protocol error", S_TO_AWAIT + [{"e": "RemoteDecision", "approve": True}, {"e": "CommitReceived", "transcriptHex": TRANSCRIPT.hex()}],
     S_TO_AWAIT_TRACE + ["AWAIT_REMOTE:", "FAILED:Abort(PROTOCOL,-)"]); n += 1
sfsm(n, "S: the row write fails after a matching commit", S_TO_AWAIT + [
    {"e": "RemoteDecision", "approve": True}, {"e": "LocalDecision", "approve": True}, {"e": "CommitReceived", "transcriptHex": TRANSCRIPT.hex()}, {"e": "RowWriteFailed"}],
     S_TO_AWAIT_TRACE + ["AWAIT_REMOTE:", "AWAIT_COMMIT:SendDecision(true)", "WRITING:WritePairedRow", "FAILED:Abort(WRITE_FAILED,-)"]); n += 1
sfsm(n, "S: the connection is lost while waiting for the decisions", S_TO_AWAIT + [{"e": "ConnectionLost"}], S_TO_AWAIT_TRACE + ["FAILED:Abort(CONNECTION_LOST,-)"]); n += 1

# 600..: the peer registry (trust.md 4.7), as traces typed by hand
n = 600
RK1, RK2 = NODEID["key1"], NODEID["key2"]


def rop(op, **kw):
    return {"op": op, **kw}


def commit(pin=RK1, scopes=("infer", "state"), local=True, remote=True, clazz="own", t=100):
    return rop("commit", pin=pin, scopes=list(scopes), local=local, remote=remote, clazz=clazz, nowMs=t)


def reg(num, desc, ops, trace):
    w04.add(num, desc, {"kind": "registry", "ops": ops}, {"ok": {"trace": trace}})


reg(n, "L1/L2: a ceremony with both approvals writes PAIRED; authorize allows a granted scope only", [
    commit(), rop("status", pin=RK1), rop("authorize", pin=RK1, scope="infer"), rop("authorize", pin=RK1, scope="state"), rop("authorize", pin=RK1, scope="manifest"), rop("authorize", pin=RK1, scope="revoke-hint"),
    rop("granted", pin=RK1), rop("session", pin=RK1)],
    ["commit:Changed(none>PAIRED,none)", "status:PAIRED", "authorize:Allow", "authorize:Allow", "authorize:Deny(SCOPE_NOT_GRANTED)", "authorize:Deny(UNKNOWN_SCOPE)", "granted:infer,state", "session:Keep"]); n += 1
reg(n, "L1: no row without the LOCAL user's approval", [commit(local=False), rop("status", pin=RK1), rop("authorize", pin=RK1, scope="infer")],
    ["commit:Refused(LOCAL_APPROVAL_REQUIRED)", "status:ABSENT", "authorize:Deny(NO_ROW)"]); n += 1
reg(n, "L1: no row without the peer's approval either", [commit(remote=False), rop("status", pin=RK1)], ["commit:Refused(REMOTE_APPROVAL_REQUIRED)", "status:ABSENT"]); n += 1
reg(n, "L1: no network message creates or raises a row (hint, forged status claim, pairing chatter)", [
    rop("net", event="hint", frm=RK2, pin=RK1), rop("net", event="statusClaim", pin=RK1, status="PAIRED"), rop("net", event="pairMessage", pin=RK1), rop("status", pin=RK1)],
    ["net:Refused(NETWORK_TRANSITION_RETIRED)", "net:Refused(NETWORK_TRANSITION_RETIRED)", "net:Updated", "status:ABSENT"]); n += 1
reg(n, "L1/L4: a network claim cannot raise a SUSPENDED row to PAIRED; a hint cannot lower a PAIRED one in mesh-1", [
    commit(), commit(pin=RK2), rop("pause", pin=RK1, nowMs=200), rop("net", event="statusClaim", pin=RK1, status="PAIRED"), rop("status", pin=RK1),
    rop("net", event="hint", frm=RK2, pin=RK2), rop("status", pin=RK2)],
    ["commit:Changed(none>PAIRED,none)", "commit:Changed(none>PAIRED,none)", "pause:Changed(PAIRED>SUSPENDED,suspended)", "net:Refused(NETWORK_TRANSITION_RETIRED)", "status:SUSPENDED",
     "net:Refused(NETWORK_TRANSITION_RETIRED)", "status:PAIRED"]); n += 1
reg(n, "L4: Pause then Restore (local only); authorize and the session decision follow the row on every read", [
    commit(), rop("authorize", pin=RK1, scope="infer"), rop("pause", pin=RK1, nowMs=200), rop("authorize", pin=RK1, scope="infer"), rop("session", pin=RK1), rop("granted", pin=RK1),
    rop("restore", pin=RK1, nowMs=300), rop("authorize", pin=RK1, scope="infer"), rop("session", pin=RK1)],
    ["commit:Changed(none>PAIRED,none)", "authorize:Allow", "pause:Changed(PAIRED>SUSPENDED,suspended)", "authorize:Deny(NOT_PAIRED)", "session:Goaway(suspended)", "granted:",
     "restore:Changed(SUSPENDED>PAIRED,none)", "authorize:Allow", "session:Keep"]); n += 1
reg(n, "L3: a REVOKED row stays REVOKED under network events, cannot be restored or re-paired, and only Forget removes it", [
    commit(), rop("revoke", pin=RK1, nowMs=200), rop("session", pin=RK1), rop("authorize", pin=RK1, scope="infer"), rop("net", event="statusClaim", pin=RK1, status="PAIRED"), rop("status", pin=RK1),
    rop("restore", pin=RK1, nowMs=300), rop("pause", pin=RK1, nowMs=300), commit(), rop("status", pin=RK1), rop("forget", pin=RK1), rop("status", pin=RK1), commit(t=400), rop("status", pin=RK1)],
    ["commit:Changed(none>PAIRED,none)", "revoke:Changed(PAIRED>REVOKED,revoked)", "session:Goaway(revoked)", "authorize:Deny(NOT_PAIRED)", "net:Refused(NETWORK_TRANSITION_RETIRED)", "status:REVOKED",
     "restore:Refused(NOT_SUSPENDED)", "pause:Refused(NOT_PAIRED)", "commit:Refused(REVOKED_CANNOT_REPAIR)", "status:REVOKED", "forget:Changed(REVOKED>none,none)", "status:ABSENT",
     "commit:Changed(none>PAIRED,none)", "status:PAIRED"]); n += 1
reg(n, "SUSPENDED to REVOKED needs no GOAWAY (the sessions were closed when the row left PAIRED)", [
    commit(), rop("pause", pin=RK1, nowMs=200), rop("revoke", pin=RK1, nowMs=300), rop("session", pin=RK1)],
    ["commit:Changed(none>PAIRED,none)", "pause:Changed(PAIRED>SUSPENDED,suspended)", "revoke:Changed(SUSPENDED>REVOKED,none)", "session:Goaway(revoked)"]); n += 1
reg(n, "Forget removes only a REVOKED row", [commit(), rop("forget", pin=RK1), rop("pause", pin=RK1, nowMs=1), rop("forget", pin=RK1), rop("status", pin=RK1), rop("forget", pin=RK2)],
    ["commit:Changed(none>PAIRED,none)", "forget:Refused(NOT_REVOKED)", "pause:Changed(PAIRED>SUSPENDED,suspended)", "forget:Refused(NOT_REVOKED)", "status:SUSPENDED", "forget:Refused(NOT_FOUND)"]); n += 1
reg(n, "An existing pin cannot be paired again", [commit(), commit(), rop("pause", pin=RK1, nowMs=1), commit()],
    ["commit:Changed(none>PAIRED,none)", "commit:Refused(PAIR_EXISTS)", "pause:Changed(PAIRED>SUSPENDED,suspended)", "commit:Refused(PAIR_EXISTS)"]); n += 1
reg(n, "Per-direction controls are independent: inbound scopes, outbound route, and neither touches the status", [
    commit(scopes=("infer",)), rop("authorizeOut", pin=RK1), rop("setRoute", pin=RK1, enabled=True, ceiling="D1"), rop("authorizeOut", pin=RK1),
    rop("setScopes", pin=RK1, scopes=["state", "manifest"]), rop("authorize", pin=RK1, scope="infer"), rop("authorize", pin=RK1, scope="state"), rop("authorizeOut", pin=RK1),
    rop("setRoute", pin=RK1, enabled=False, ceiling="D2"), rop("authorize", pin=RK1, scope="manifest"), rop("authorizeOut", pin=RK1), rop("setRoute", pin=RK1, enabled=True, ceiling="D2"), rop("authorizeOut", pin=RK1), rop("status", pin=RK1)],
    ["commit:Changed(none>PAIRED,none)", "authorizeOut:Deny(ROUTING_DISABLED)", "setRoute:Updated", "authorizeOut:Allow(D1)", "setScopes:Updated", "authorize:Deny(SCOPE_NOT_GRANTED)", "authorize:Allow",
     "authorizeOut:Allow(D1)", "setRoute:Updated", "authorize:Allow", "authorizeOut:Deny(ROUTING_DISABLED)", "setRoute:Updated", "authorizeOut:Allow(D2)", "status:PAIRED"]); n += 1
reg(n, "A paused peer is not routed to either, and a revoked row accepts no control changes", [
    commit(), rop("setRoute", pin=RK1, enabled=True, ceiling="D1"), rop("pause", pin=RK1, nowMs=1), rop("authorizeOut", pin=RK1), rop("revoke", pin=RK1, nowMs=2), rop("setRoute", pin=RK1, enabled=True, ceiling="D1"),
    rop("setScopes", pin=RK1, scopes=["infer"])],
    ["commit:Changed(none>PAIRED,none)", "setRoute:Updated", "pause:Changed(PAIRED>SUSPENDED,suspended)", "authorizeOut:Deny(NOT_PAIRED)", "revoke:Changed(SUSPENDED>REVOKED,none)", "setRoute:Refused(REVOKED_ROW)", "setScopes:Refused(REVOKED_ROW)"]); n += 1
reg(n, "mesh-1 pairs the own class only (class other is out of scope, LAB_SPEC 7.3)", [commit(clazz="other"), rop("status", pin=RK1)], ["commit:Refused(CLASS_OTHER_NOT_IN_MESH1)", "status:ABSENT"]); n += 1
reg(n, "L5: a status value the code does not know denies everything and closes sessions", [
    rop("plant", pin=RK1, status=9, scopesJson='["infer"]'), rop("status", pin=RK1), rop("authorize", pin=RK1, scope="infer"), rop("authorizeOut", pin=RK1), rop("session", pin=RK1), rop("granted", pin=RK1),
    rop("pause", pin=RK1, nowMs=1), rop("revoke", pin=RK1, nowMs=1), rop("restore", pin=RK1, nowMs=1), rop("forget", pin=RK1), commit()],
    ["plant:ok", "status:CORRUPT", "authorize:Deny(UNKNOWN_STATUS)", "authorizeOut:Deny(UNKNOWN_STATUS)", "session:Goaway(suspended)", "granted:", "pause:Refused(CORRUPT_ROW)", "revoke:Refused(CORRUPT_ROW)",
     "restore:Refused(CORRUPT_ROW)", "forget:Refused(CORRUPT_ROW)", "commit:Refused(CORRUPT_ROW)"]); n += 1
reg(n, "L5: status 0, 1, 5 and -1 are all unknown", [
    rop("plant", pin=RK1, status=0, scopesJson='["infer"]'), rop("authorize", pin=RK1, scope="infer"), rop("plant", pin=RK1, status=1, scopesJson='["infer"]'), rop("authorize", pin=RK1, scope="infer"),
    rop("plant", pin=RK1, status=5, scopesJson='["infer"]'), rop("authorize", pin=RK1, scope="infer"), rop("plant", pin=RK1, status=-1, scopesJson='["infer"]'), rop("authorize", pin=RK1, scope="infer"),
    rop("plant", pin=RK1, status=2, scopesJson='["infer"]'), rop("authorize", pin=RK1, scope="infer")],
    ["plant:ok", "authorize:Deny(UNKNOWN_STATUS)", "plant:ok", "authorize:Deny(UNKNOWN_STATUS)", "plant:ok", "authorize:Deny(UNKNOWN_STATUS)", "plant:ok", "authorize:Deny(UNKNOWN_STATUS)", "plant:ok", "authorize:Allow"]); n += 1
reg(n, "L5: unreadable scopes deny even for a PAIRED row (duplicate, unknown name, wrong type, trailing text)", [
    rop("plant", pin=RK1, status=2, scopesJson='["infer","infer"]'), rop("authorize", pin=RK1, scope="infer"), rop("plant", pin=RK1, status=2, scopesJson='["infer","admin"]'), rop("authorize", pin=RK1, scope="infer"),
    rop("plant", pin=RK1, status=2, scopesJson='{"infer":true}'), rop("authorize", pin=RK1, scope="infer"), rop("plant", pin=RK1, status=2, scopesJson='["infer"] x'), rop("authorize", pin=RK1, scope="infer"),
    rop("plant", pin=RK1, status=2, scopesJson=''), rop("authorize", pin=RK1, scope="infer"), rop("granted", pin=RK1)],
    ["plant:ok", "authorize:Deny(UNREADABLE_SCOPES)", "plant:ok", "authorize:Deny(UNREADABLE_SCOPES)", "plant:ok", "authorize:Deny(UNREADABLE_SCOPES)", "plant:ok", "authorize:Deny(UNREADABLE_SCOPES)",
     "plant:ok", "authorize:Deny(UNREADABLE_SCOPES)", "granted:"]); n += 1
reg(n, "Fail closed: an unreadable store denies; authorize re-reads on every call, so a recovered store allows again", [
    commit(), rop("authorize", pin=RK1, scope="infer"), rop("fault", reads=True, writes=False), rop("authorize", pin=RK1, scope="infer"), rop("authorizeOut", pin=RK1), rop("status", pin=RK1), rop("session", pin=RK1),
    commit(pin=RK2), rop("pause", pin=RK1, nowMs=5), rop("fault", reads=False, writes=False), rop("authorize", pin=RK1, scope="infer"), rop("status", pin=RK1)],
    ["commit:Changed(none>PAIRED,none)", "authorize:Allow", "fault:ok", "authorize:Deny(UNREADABLE)", "authorizeOut:Deny(UNREADABLE)", "status:UNREADABLE", "session:Goaway(suspended)", "commit:Refused(STORE_UNREADABLE)",
     "pause:Refused(STORE_UNREADABLE)", "fault:ok", "authorize:Allow", "status:PAIRED"]); n += 1
reg(n, "Fail closed: a failed durable write changes nothing and the old row still authorises", [
    commit(), rop("fault", reads=False, writes=True), commit(pin=RK2), rop("pause", pin=RK1, nowMs=5), rop("authorize", pin=RK1, scope="infer"), rop("status", pin=RK1), rop("status", pin=RK2),
    rop("revoke", pin=RK1, nowMs=6), rop("setRoute", pin=RK1, enabled=True, ceiling="D1"), rop("fault", reads=False, writes=False), rop("status", pin=RK1)],
    ["commit:Changed(none>PAIRED,none)", "fault:ok", "commit:Refused(STORE_WRITE_FAILED)", "pause:Refused(STORE_WRITE_FAILED)", "authorize:Allow", "status:PAIRED", "status:ABSENT",
     "revoke:Refused(STORE_WRITE_FAILED)", "setRoute:Refused(STORE_WRITE_FAILED)", "fault:ok", "status:PAIRED"]); n += 1
reg(n, "GOAWAY reasons: PAIRED to SUSPENDED says suspended, PAIRED to REVOKED says revoked, an absent row says revoked", [
    commit(), commit(pin=RK2), rop("pause", pin=RK1, nowMs=1), rop("revoke", pin=RK2, nowMs=1), rop("session", pin=RK1), rop("session", pin=RK2), rop("forget", pin=RK2), rop("session", pin=RK2)],
    ["commit:Changed(none>PAIRED,none)", "commit:Changed(none>PAIRED,none)", "pause:Changed(PAIRED>SUSPENDED,suspended)", "revoke:Changed(PAIRED>REVOKED,revoked)", "session:Goaway(suspended)", "session:Goaway(revoked)",
     "forget:Changed(REVOKED>none,none)", "session:Goaway(revoked)"]); n += 1
reg(n, "A revoke notice from a peer is only noted: it changes no row in either direction", [commit(), rop("net", event="revokeNotice", pin=RK1), rop("status", pin=RK1), rop("authorize", pin=RK1, scope="infer")],
    ["commit:Changed(none>PAIRED,none)", "net:Updated", "status:PAIRED", "authorize:Allow"]); n += 1
reg(n, "Two peers are independent rows", [commit(), commit(pin=RK2, scopes=("manifest",)), rop("revoke", pin=RK1, nowMs=9), rop("authorize", pin=RK2, scope="manifest"), rop("authorize", pin=RK2, scope="infer"), rop("authorize", pin=RK1, scope="infer")],
    ["commit:Changed(none>PAIRED,none)", "commit:Changed(none>PAIRED,none)", "revoke:Changed(PAIRED>REVOKED,revoked)", "authorize:Allow", "authorize:Deny(SCOPE_NOT_GRANTED)", "authorize:Deny(NOT_PAIRED)"]); n += 1
reg(n, "granted lists scopes in the fixed order infer, manifest, state whatever the order they were given", [commit(scopes=("state", "manifest", "infer")), rop("granted", pin=RK1), rop("setScopes", pin=RK1, scopes=[]), rop("granted", pin=RK1), rop("authorize", pin=RK1, scope="infer")],
    ["commit:Changed(none>PAIRED,none)", "granted:infer,manifest,state", "setScopes:Updated", "granted:", "authorize:Deny(SCOPE_NOT_GRANTED)"]); n += 1

write(os.path.join(ROOT, "wire", "W04-pairing.json"), envelope(
    "W04", ["trust.md 4.2", "trust.md 4.4", "trust.md 4.5", "trust.md 4.6", "trust.md 4.7", "trust.md 15 (pairing/proof-sas.json, pairing/qr-uri.json, laws L1-L8)", "LAB_SPEC.md 7.3"], w04.items))
print("W04:", len(w04.items), "vectors")
