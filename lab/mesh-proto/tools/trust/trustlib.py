#!/usr/bin/env python3
"""A second implementation of the trust layer (trust.md 2.3, 2.4, 3.2, 4.2, 4.5) in Python 3, standard library only.

Everything here is hand-written from the spec text: a DER writer and a DER reader, P-256 arithmetic with RFC 6979 deterministic ECDSA, the two
certificate templates, a strict SPKI import, the pin and fingerprint derivations, the peer-chain verifier, the QR grammar, and the proof, SAS and
transcript functions. It is used by gen_vectors.py (to write the W04 and W05 vector files) and by xcheck_trust.py (to re-evaluate every vector).

It was written in the SAME session as the Kotlin code (lab/mesh-proto). Its agreement with the Kotlin code shows that two hand-written readings of
one spec agree; it NEVER clears the `oracle: self` tag (LAB_SPEC 4.10, R9).
"""
import base64
import hashlib
import hmac
import re
from datetime import datetime, timezone

# ------------------------------------------------------------------------------------------------------------------------------------ base64url


def b64u(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode("ascii")


def unb64u(s):
    """Strict base64url, no padding, canonical trailing bits. Returns bytes or None."""
    if not re.fullmatch(r"[A-Za-z0-9_-]*", s) or len(s) % 4 == 1:
        return None
    try:
        raw = base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))
    except Exception:
        return None
    return raw if b64u(raw) == s else None


# ------------------------------------------------------------------------------------------------------------------------------------ P-256 and ECDSA
P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
A = P - 3
B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
GX = 0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296
GY = 0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5
G = (GX, GY)


def _inv(x, m):
    return pow(x, -1, m)


def _add(p1, p2):
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    (x1, y1), (x2, y2) = p1, p2
    if x1 == x2:
        if (y1 + y2) % P == 0:
            return None
        lam = (3 * x1 * x1 + A) * _inv(2 * y1, P) % P
    else:
        lam = (y2 - y1) * _inv(x2 - x1, P) % P
    x3 = (lam * lam - x1 - x2) % P
    return (x3, (lam * (x1 - x3) - y1) % P)


def mul(k, pt=G):
    r, a = None, pt
    while k:
        if k & 1:
            r = _add(r, a)
        a = _add(a, a)
        k >>= 1
    return r


def on_curve(x, y):
    return 0 <= x < P and 0 <= y < P and (y * y - (x * x * x + A * x + B)) % P == 0


def spki_of(d):
    x, y = mul(d)
    return SPKI_PREFIX + b"\x04" + x.to_bytes(32, "big") + y.to_bytes(32, "big")


SPKI_PREFIX = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200")


def _rfc6979_k(d, h1):
    x = d.to_bytes(32, "big")
    hh = (int.from_bytes(h1, "big") % N).to_bytes(32, "big")
    v, k = b"\x01" * 32, b"\x00" * 32
    k = hmac.new(k, v + b"\x00" + x + hh, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    k = hmac.new(k, v + b"\x01" + x + hh, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    while True:
        v = hmac.new(k, v, hashlib.sha256).digest()
        c = int.from_bytes(v, "big")
        if 0 < c < N:
            return c
        k = hmac.new(k, v + b"\x00", hashlib.sha256).digest()
        v = hmac.new(k, v, hashlib.sha256).digest()


def ecdsa_sign(d, msg, digest="sha256"):
    """Deterministic (RFC 6979 nonce from SHA-256) ECDSA over P-256, low-S, returns raw r||s. [digest] picks the message hash only."""
    h = hashlib.new(digest, msg).digest()
    e = int.from_bytes(h[:32], "big") if len(h) > 32 else int.from_bytes(h, "big")
    k = _rfc6979_k(d, hashlib.sha256(msg).digest())
    r = mul(k)[0] % N
    s = _inv(k, N) * (e + r * d) % N
    if s > N // 2:
        s = N - s
    return r.to_bytes(32, "big") + s.to_bytes(32, "big")


def ecdsa_verify(spki, msg, raw):
    if len(raw) != 64:
        return False
    r, s = int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big")
    if not (0 < r < N and 0 < s < N):
        return False
    q = (int.from_bytes(spki[27:59], "big"), int.from_bytes(spki[59:91], "big"))
    e = int.from_bytes(hashlib.sha256(msg).digest(), "big")
    w = _inv(s, N)
    pt = _add(mul(e * w % N), mul(r * w % N, q))
    return pt is not None and pt[0] % N == r


def strict_spki(spki):
    """The 91-byte P-256 SPKI of LAB_SPEC 4.5: the fixed prefix, 04, x and y, with the point on the curve."""
    if len(spki) != 91 or spki[:26] != SPKI_PREFIX or spki[26] != 4:
        return False
    return on_curve(int.from_bytes(spki[27:59], "big"), int.from_bytes(spki[59:91], "big"))


# ------------------------------------------------------------------------------------------------------------------------------------ identifiers


def pin_of(spki):
    return hashlib.sha256(spki).digest()


def node_tag(pin):
    return base64.b32encode(pin).decode("ascii").lower().rstrip("=")[:16]


def display_fp(pin):
    t = node_tag(pin).upper()
    return "-".join(t[i:i + 4] for i in range(0, 16, 4))


# ------------------------------------------------------------------------------------------------------------------------------------ DER writer


def length(n):
    if n < 0x80:
        return bytes([n])
    if n < 0x100:
        return bytes([0x81, n])
    return bytes([0x82, n >> 8, n & 0xFF])


def tlv(tag, body):
    return bytes([tag]) + length(len(body)) + body


def seq(*parts):
    return tlv(0x30, b"".join(parts))


def setof(*parts):
    return tlv(0x31, b"".join(parts))


def explicit(n, body):
    return tlv(0xA0 | n, body)


def oid(dotted):
    arcs = [int(x) for x in dotted.split(".")]
    out = bytearray()

    def b128(v):
        groups = [v & 0x7F]
        v >>= 7
        while v:
            groups.append(0x80 | (v & 0x7F))
            v >>= 7
        out.extend(reversed(groups))

    b128(arcs[0] * 40 + arcs[1])
    for a in arcs[2:]:
        b128(a)
    return tlv(0x06, bytes(out))


def integer_unsigned(b):
    b = b.lstrip(b"\x00") or b"\x00"
    if b[0] & 0x80:
        b = b"\x00" + b
    return tlv(0x02, b)


def integer_small(v):
    return tlv(0x02, bytes([v]))


def bit_string(b, unused=0):
    return tlv(0x03, bytes([unused]) + b)


def octets(b):
    return tlv(0x04, b)


def utf8(s):
    return tlv(0x0C, s.encode("utf-8"))


def boolean(v):
    return tlv(0x01, b"\xff" if v else b"\x00")


def _t(epoch):
    return datetime.fromtimestamp(epoch, tz=timezone.utc)


def time_el(epoch):
    t = _t(epoch)
    if 1950 <= t.year <= 2049:
        return tlv(0x17, t.strftime("%y%m%d%H%M%SZ").encode())
    return gen_time(epoch)


def gen_time(epoch):
    t = _t(epoch)
    return tlv(0x18, ("%04d" % t.year + t.strftime("%m%d%H%M%SZ")).encode())


# ------------------------------------------------------------------------------------------------------------------------------------ templates
OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
OID_ECDSA_SHA1 = "1.2.840.10045.4.1"
OID_ECDSA_SHA384 = "1.2.840.10045.4.3.3"
OID_CN = "2.5.4.3"
OID_SKI = "2.5.29.14"
OID_KU = "2.5.29.15"
OID_BC = "2.5.29.19"
OID_AKI = "2.5.29.35"
OID_EKU = "2.5.29.37"
OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
OID_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2"
ALG_SHA256 = seq(oid(OID_ECDSA_SHA256))
NODE_NOT_AFTER = 253402300799
LEAF_LIFETIME = 14 * 24 * 3600
BACKDATE = 3600


def ski_of(spki):
    """RFC 7093 method 1 with SHA-256: the leftmost 160 bits of SHA-256 over the 65-byte point."""
    return hashlib.sha256(spki[26:91]).digest()[:20]


def name_der(prefix, spki):
    return seq(setof(seq(oid(OID_CN), utf8(prefix + " " + node_tag(pin_of(spki))))))


def ext(oid_s, critical, value):
    return seq(oid(oid_s), boolean(True), octets(value)) if critical else seq(oid(oid_s), octets(value))


def build_tbs(serial, issuer, not_before_el, not_after_el, subject, spki, exts, version=2, inner_alg=ALG_SHA256, extras=b""):
    return seq(
        explicit(0, integer_small(version)),
        integer_unsigned(serial),
        inner_alg,
        issuer,
        seq(not_before_el, not_after_el),
        subject,
        spki,
        extras,
        explicit(3, seq(*exts)),
    )


def node_tbs(nik_spki, serial, created):
    n = name_der("asom-node", nik_spki)
    return build_tbs(
        serial, n, time_el(created - BACKDATE), gen_time(NODE_NOT_AFTER), n, nik_spki,
        [
            ext(OID_BC, True, seq(boolean(True), integer_small(0))),
            ext(OID_KU, True, bit_string(b"\x04", 2)),
            ext(OID_SKI, False, octets(ski_of(nik_spki))),
        ],
    )


def leaf_exts(nik_spki):
    return [
        ext(OID_BC, True, seq()),
        ext(OID_KU, True, bit_string(b"\x80", 7)),
        ext(OID_EKU, False, seq(oid(OID_SERVER_AUTH), oid(OID_CLIENT_AUTH))),
        ext(OID_AKI, False, seq(tlv(0x80, ski_of(nik_spki)))),
    ]


def leaf_tbs(nik_spki, leaf_spki, serial, now):
    return build_tbs(
        serial, name_der("asom-node", nik_spki), time_el(now - BACKDATE), time_el(now + LEAF_LIFETIME),
        name_der("asom-session", nik_spki), leaf_spki, leaf_exts(nik_spki),
    )


def sig_der(raw):
    def i(b):
        b = b.lstrip(b"\x00") or b"\x00"
        if b[0] & 0x80:
            b = b"\x00" + b
        return tlv(0x02, b)

    return seq(i(raw[:32]), i(raw[32:]))


def assemble(tbs, raw, alg=ALG_SHA256):
    return seq(tbs, alg, bit_string(sig_der(raw)))


def sign_cert(tbs, d, alg=ALG_SHA256, digest="sha256"):
    return assemble(tbs, ecdsa_sign(d, tbs, digest), alg)


# ------------------------------------------------------------------------------------------------------------------------------------ DER reader


class DerError(Exception):
    pass


def read_tlv(b, off, limit):
    if off >= limit:
        raise DerError("start")
    tag = b[off]
    if tag & 0x1F == 0x1F:
        raise DerError("high tag")
    p = off + 1
    if p >= limit:
        raise DerError("length")
    first = b[p]
    p += 1
    if first < 0x80:
        n = first
    else:
        k = first & 0x7F
        if k == 0 or k > 3 or p + k > limit:
            raise DerError("length form")
        n = int.from_bytes(b[p:p + k], "big")
        p += k
        if n < 0x80 or (k >= 2 and n < 0x100) or (k == 3 and n < 0x10000):
            raise DerError("non-minimal length")
    if n > (1 << 20) or p + n > limit:
        raise DerError("overrun")
    return tag, off, p, p + n


def children(b, el):
    _, _, cs, ce = el
    out, p = [], cs
    while p < ce:
        c = read_tlv(b, p, ce)
        out.append(c)
        p = c[3]
    return out


def single(b):
    el = read_tlv(b, 0, len(b))
    if el[3] != len(b):
        raise DerError("trailing")
    return el


def whole(b, el):
    return b[el[1]:el[3]]


def content(b, el):
    return b[el[2]:el[3]]


def expect(el, tag):
    if el[0] != tag:
        raise DerError("tag")
    return el


def oid_str(b, el):
    expect(el, 0x06)
    c = content(b, el)
    if not c:
        raise DerError("empty oid")
    arcs, v, fresh = [], 0, True
    for x in c:
        if fresh and x == 0x80:
            raise DerError("oid arc")
        fresh = False
        v = (v << 7) | (x & 0x7F)
        if not x & 0x80:
            arcs.append(v)
            v, fresh = 0, True
    if not fresh:
        raise DerError("oid trunc")
    f = arcs[0]
    head = [0, f] if f < 40 else [1, f - 40] if f < 80 else [2, f - 80]
    return ".".join(str(a) for a in head + arcs[1:])


def bool_val(b, el):
    expect(el, 0x01)
    c = content(b, el)
    if len(c) != 1 or c[0] not in (0, 0xFF):
        raise DerError("boolean")
    return c[0] == 0xFF


def small_int(b, el):
    expect(el, 0x02)
    c = content(b, el)
    if not c or len(c) > 8 or c[0] & 0x80 or (len(c) > 1 and c[0] == 0 and not c[1] & 0x80):
        raise DerError("integer")
    return int.from_bytes(c, "big")


def time_val(b, el):
    s = content(b, el).decode("ascii", "replace")
    if el[0] == 0x17:
        if not re.fullmatch(r"\d{12}Z", s):
            raise DerError("utctime")
        yy = int(s[:2])
        year = 1900 + yy if yy >= 50 else 2000 + yy
        rest = s[2:12]
    elif el[0] == 0x18:
        if not re.fullmatch(r"\d{14}Z", s):
            raise DerError("gentime")
        year = int(s[:4])
        if year < 2050:
            raise DerError("gentime before 2050")
        rest = s[4:14]
    else:
        raise DerError("time tag")
    try:
        return int(datetime(year, int(rest[0:2]), int(rest[2:4]), int(rest[4:6]), int(rest[6:8]), int(rest[8:10]), tzinfo=timezone.utc).timestamp())
    except ValueError:
        raise DerError("date")


class Cert:
    pass


def parse_cert(der):
    c = Cert()
    c.der = der
    top = children(der, expect(single(der), 0x30))
    if len(top) != 3:
        raise DerError("top")
    tbs, outer_alg, sigel = expect(top[0], 0x30), expect(top[1], 0x30), expect(top[2], 0x03)
    sig = content(der, sigel)
    if not sig or sig[0] != 0:
        raise DerError("sig unused bits")
    c.tbs, c.outer_alg, c.sig = whole(der, tbs), whole(der, outer_alg), sig[1:]
    f = children(der, tbs)
    if len(f) != 8:
        raise DerError("tbs fields")
    ver = children(der, expect(f[0], 0xA0))
    if len(ver) != 1 or small_int(der, ver[0]) != 2:
        raise DerError("version")
    ser = expect(f[1], 0x02)
    sc = content(der, ser)
    if not sc or len(sc) > 21 or sc[0] & 0x80 or (len(sc) > 1 and sc[0] == 0 and not sc[1] & 0x80):
        raise DerError("serial")
    c.inner_alg = whole(der, expect(f[2], 0x30))
    c.issuer = whole(der, expect(f[3], 0x30))
    val = children(der, expect(f[4], 0x30))
    if len(val) != 2:
        raise DerError("validity")
    c.not_before, c.not_after = time_val(der, val[0]), time_val(der, val[1])
    c.subject = whole(der, expect(f[5], 0x30))
    c.spki = whole(der, expect(f[6], 0x30))
    wrap = children(der, expect(f[7], 0xA3))
    if len(wrap) != 1:
        raise DerError("ext wrap")
    c.exts, c.dup = [], False
    for e in children(der, expect(wrap[0], 0x30)):
        parts = children(der, expect(e, 0x30))
        if len(parts) not in (2, 3):
            raise DerError("ext")
        o = oid_str(der, parts[0])
        crit = False
        if len(parts) == 3:
            if not bool_val(der, parts[1]):
                raise DerError("explicit default")
            crit = True
        val_b = content(der, expect(parts[-1], 0x04))
        if any(x[0] == o for x in c.exts):
            c.dup = True
        c.exts.append((o, crit, val_b))
    if not c.exts:
        raise DerError("no exts")
    return c


def ext_of(c, o):
    for x in c.exts:
        if x[0] == o:
            return x
    return None


def parse_bc(v):
    ch = children(v, expect(single(v), 0x30))
    i, ca = 0, False
    if i < len(ch) and ch[i][0] == 0x01:
        if not bool_val(v, ch[i]):
            raise DerError("explicit default cA")
        ca = True
        i += 1
    path = None
    if i < len(ch) and ch[i][0] == 0x02:
        path = small_int(v, ch[i])
        i += 1
    if i != len(ch):
        raise DerError("bc extra")
    return ca, path


def parse_ku(v):
    c = content(v, expect(single(v), 0x03))
    if len(c) < 2:
        raise DerError("ku empty")
    unused, data = c[0], c[1:]
    if unused > 7 or len(data) > 2 or data[-1] == 0:
        raise DerError("ku shape")
    last = data[-1]
    if last & ((1 << unused) - 1) or unused != (last & -last).bit_length() - 1:
        raise DerError("ku minimal")
    return {k for k in range(len(data) * 8 - unused) if data[k // 8] & (0x80 >> (k % 8))}


def parse_eku(v):
    ch = children(v, expect(single(v), 0x30))
    if not ch:
        raise DerError("eku empty")
    return [oid_str(v, x) for x in ch]


def parse_ski(v):
    el = expect(single(v), 0x04)
    if el[3] == el[2]:
        raise DerError("ski empty")
    return content(v, el)


def parse_aki(v):
    ch = children(v, expect(single(v), 0x30))
    if len(ch) != 1 or ch[0][0] != 0x80 or ch[0][3] == ch[0][2]:
        raise DerError("aki")
    return content(v, ch[0])


# ------------------------------------------------------------------------------------------------------------------------------------ verifyPeerChain
KNOWN = {OID_BC, OID_KU, OID_EKU, OID_SKI, OID_AKI}
SKEW = 2 * 3600
# the test-only node ids (lab/conformance/keys/TEST-ONLY-keys.json) are loaded by the caller
TEST_ONLY_NODE_IDS = set()


def verify_chain(chain, mode, now, registry, window_open, production_keys=True, server=False):
    """Returns ('ok', pin, effective_mode) or ('reject', CODE). [registry]: callable pin_bytes -> ('absent',) | ('known', 'PAIRED'|...) | ('corrupt',) | ('unreadable',).
    [mode]: ('paired', pin) | ('pairing', pin) | ('established',) | ('pairing_server',); ignored when [server]."""
    try:
        r = _analyse(chain, now, production_keys)
        if r[0] == "reject":
            return r
        pin = r[1]
        st = registry(pin)
        if server:
            mode = ("pairing_server",) if st == ("absent",) and window_open else ("established",)
        if mode[0] in ("paired", "pairing") and not (len(pin) == len(mode[1]) and hmac.compare_digest(pin, mode[1])):
            return ("reject", "PIN_MISMATCH")
        if mode[0] == "pairing_server" and not window_open:
            return ("reject", "PAIRING_WINDOW_CLOSED")
        needs_paired = mode[0] in ("paired", "established")
        if st[0] in ("unreadable", "corrupt"):
            return ("reject", "REGISTRY_UNREADABLE")
        if st[0] == "absent":
            if needs_paired:
                return ("reject", "PIN_UNKNOWN")
        else:
            if st[1] == "REVOKED":
                return ("reject", "PIN_REVOKED")
            if st[1] == "SUSPENDED" and needs_paired:
                return ("reject", "PIN_SUSPENDED")
        return ("ok", pin, mode)
    except Exception:
        return ("reject", "INTERNAL")


def _analyse(chain, now, production_keys):
    if len(chain) != 2:
        return ("reject", "CHAIN_LENGTH")
    try:
        leaf, node = parse_cert(chain[0]), parse_cert(chain[1])
    except DerError:
        return ("reject", "CERT_MALFORMED")
    if not (strict_spki(node.spki) and strict_spki(leaf.spki)):
        return ("reject", "KEY_UNSUPPORTED")
    for c in (node, leaf):
        if c.outer_alg != ALG_SHA256 or c.inner_alg != ALG_SHA256:
            return ("reject", "SIG_ALG_UNSUPPORTED")
    for c in (node, leaf):
        if c.dup:
            return ("reject", "EXTENSION_INVALID")
        if any(crit and o not in KNOWN for o, crit, _ in c.exts):
            return ("reject", "UNKNOWN_CRITICAL_EXTENSION")
    n_bc, n_ku, n_ski = ext_of(node, OID_BC), ext_of(node, OID_KU), ext_of(node, OID_SKI)
    l_bc, l_ku, l_eku, l_aki = ext_of(leaf, OID_BC), ext_of(leaf, OID_KU), ext_of(leaf, OID_EKU), ext_of(leaf, OID_AKI)
    if None in (n_bc, n_ku, n_ski, l_bc, l_ku, l_eku, l_aki):
        return ("reject", "EXTENSION_MISSING")
    if not (n_bc[1] and n_ku[1] and l_bc[1] and l_ku[1]):
        return ("reject", "EXTENSION_INVALID")
    try:
        nca, npath = parse_bc(n_bc[2])
        nku = parse_ku(n_ku[2])
        nski = parse_ski(n_ski[2])
        lca, lpath = parse_bc(l_bc[2])
        lku = parse_ku(l_ku[2])
        leku = parse_eku(l_eku[2])
        laki = parse_aki(l_aki[2])
    except DerError:
        return ("reject", "EXTENSION_INVALID")
    if not nca or nku != {5}:
        return ("reject", "NODE_NOT_CA")
    if npath != 0:
        return ("reject", "PATHLEN_VIOLATION")
    if lca or 5 in lku:
        return ("reject", "LEAF_IS_CA")
    if lpath is not None:
        return ("reject", "EXTENSION_INVALID")
    if 0 not in lku:
        return ("reject", "LEAF_KEY_USAGE")
    if OID_SERVER_AUTH not in leku or OID_CLIENT_AUTH not in leku:
        return ("reject", "LEAF_EKU")
    if leaf.issuer != node.subject or node.issuer != node.subject:
        return ("reject", "ISSUER_MISMATCH")
    if laki != nski:
        return ("reject", "AKI_MISMATCH")
    for c in (node, leaf):
        raw = _sig_raw(c.sig)
        if raw is None or not ecdsa_verify(node.spki, c.tbs, raw):
            return ("reject", "BAD_SIGNATURE")
    if now < leaf.not_before - SKEW or now > leaf.not_after + SKEW:
        return ("reject", "CLOCK_SKEW")
    pin = pin_of(node.spki)
    if production_keys and b64u(pin) in TEST_ONLY_NODE_IDS:
        return ("reject", "TEST_ONLY_KEY")
    return ("ok", pin)


def _sig_raw(der):
    """Strict DER ECDSA-Sig-Value to raw r||s, or None."""
    try:
        ch = children(der, expect(single(der), 0x30))
        if len(ch) != 2:
            return None
        parts = []
        for el in ch:
            expect(el, 0x02)
            c = content(der, el)
            if not c or c[0] & 0x80 or (len(c) > 1 and c[0] == 0 and not c[1] & 0x80):
                return None
            v = int.from_bytes(c, "big")
            if v.bit_length() > 256:
                return None
            parts.append(v.to_bytes(32, "big"))
        return parts[0] + parts[1]
    except DerError:
        return None


# ------------------------------------------------------------------------------------------------------------------------------------ pairing (trust.md 4.5)


def proof(secret, pin_d, pin_s, nonce_s):
    return hmac.new(secret, b"asom-pair-v1/proof\x00" + pin_d + pin_s + nonce_s, hashlib.sha256).digest()


def sas(pin_d, pin_s, nonce_s, nonce_d):
    h = hashlib.sha256(b"asom-pair-v1/sas\x00" + pin_d + pin_s + nonce_s + nonce_d).digest()
    n = int.from_bytes(h[:4], "big") % 1000000
    return "%06d" % n


def sas_display(pin_d, pin_s, nonce_s, nonce_d):
    s = sas(pin_d, pin_s, nonce_s, nonce_d)
    return s[:3] + " " + s[3:]


def transcript(pin_d, pin_s, nonce_s, nonce_d):
    return hashlib.sha256(b"asom-pair-v1/transcript\x00" + pin_d + pin_s + nonce_s + nonce_d).digest()


# ------------------------------------------------------------------------------------------------------------------------------------ QR grammar (trust.md 4.2)
UNRESERVED = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")


def pct_encode(s):
    return "".join(chr(b) if chr(b) in UNRESERVED else "%%%02X" % b for b in s.encode("utf-8"))


def pct_decode(s):
    out = bytearray()
    i = 0
    while i < len(s):
        ch = s[i]
        if ch == "%":
            if i + 2 >= len(s) or not re.fullmatch(r"[0-9A-Fa-f]{2}", s[i + 1:i + 3]):
                return None
            out.append(int(s[i + 1:i + 3], 16))
            i += 3
        elif ch in UNRESERVED:
            out.append(ord(ch))
            i += 1
        else:
            return None
    try:
        text = bytes(out).decode("utf-8")
    except UnicodeDecodeError:
        return None
    for c in text:
        cp = ord(c)
        if cp < 0x20 or 0x7F <= cp <= 0x9F or cp in (0x2028, 0x2029, 0xFFFD) or 0x202A <= cp <= 0x202E or 0x2066 <= cp <= 0x2069:
            return None
    return text


def _v4(s):
    m = re.fullmatch(r"(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})", s)
    if not m:
        return None
    parts = m.groups()
    if any(len(p) > 1 and p[0] == "0" for p in parts) or any(int(p) > 255 for p in parts):
        return None
    return [int(p) for p in parts]


def _v6(s):
    s = s.lower()
    if not re.fullmatch(r"[0-9a-f:]+", s) or s.count("::") > 1 or ":::" in s:
        return None
    if "::" in s:
        head, tail = s.split("::")
        h = head.split(":") if head else []
        t = tail.split(":") if tail else []
        if len(h) + len(t) > 7:
            return None
        groups = h + ["0"] * (8 - len(h) - len(t)) + t
    else:
        groups = s.split(":")
        if len(groups) != 8:
            return None
    if any(not g or len(g) > 4 for g in groups):
        return None
    return [int(g, 16) for g in groups]


def _v6_text(g):
    best, blen, i = -1, 0, 0
    while i < 8:
        if g[i] == 0:
            j = i
            while j < 8 and g[j] == 0:
                j += 1
            if j - i > blen:
                best, blen = i, j - i
            i = j
        else:
            i += 1
    if blen < 2:
        return ":".join("%x" % x for x in g)
    return ":".join("%x" % x for x in g[:best]) + "::" + ":".join("%x" % x for x in g[best + blen:])


def _port(s):
    if not re.fullmatch(r"[0-9]{1,5}", s) or (len(s) > 1 and s[0] == "0"):
        return None
    p = int(s)
    return p if 1 <= p <= 65535 else None


def parse_endpoint(item):
    if item.startswith("["):
        m = re.fullmatch(r"\[([^\]]*)\]:(.*)", item)
        if not m:
            return None
        g, p = _v6(m.group(1)), _port(m.group(2))
        return None if g is None or p is None else (_v6_text(g), p, True)
    if ":" not in item:
        return None
    host, _, port = item.rpartition(":")
    p = _port(port)
    if p is None or _v4(host) is None:
        return None
    return (host, p, False)


def looks_dns(item):
    host = item.rpartition(":")[0] or item
    return bool(host) and not host.startswith("[") and any(c.isalpha() for c in host) and re.fullmatch(r"[A-Za-z0-9.-]+", host) is not None


def eligible(ep, loopback_ok=False):
    addr, _, v6 = ep
    if not v6:
        o = _v4(addr)
        if o[0] == 127:
            return loopback_ok
        return o[0] == 10 or (o[0] == 172 and 16 <= o[1] <= 31) or (o[0] == 192 and o[1] == 168) or (o[0] == 169 and o[1] == 254) or (o[0] == 100 and 64 <= o[1] <= 127)
    g = _v6(addr)
    if all(x == 0 for x in g):
        return False
    if all(x == 0 for x in g[:7]) and g[7] == 1:
        return loopback_ok
    return (g[0] & 0xFE00) == 0xFC00 or (g[0] & 0xFFC0) == 0xFE80


def qr_parse(text, now, loopback_ok=False):
    """Returns ('ok', {k, a, s, x, n}) or ('reject', CODE)."""
    if len(text) > 2048:
        return ("reject", "TOO_LONG")
    if not text.startswith("asom-pair:"):
        return ("reject", "SCHEME")
    if not text.startswith("asom-pair:1?"):
        return ("reject", "VERSION")
    query = text[len("asom-pair:1?"):]
    if not query:
        return ("reject", "MISSING_FIELD")
    fields = {}
    for part in query.split("&"):
        eq = part.find("=")
        if eq <= 0:
            return ("reject", "SYNTAX")
        key = part[:eq]
        if key not in ("k", "a", "s", "x", "n"):
            return ("reject", "UNKNOWN_FIELD")
        if key in fields:
            return ("reject", "DUPLICATE_FIELD")
        fields[key] = part[eq + 1:]
    for key in "kasxn":
        if key not in fields:
            return ("reject", "MISSING_FIELD")
    pin = unb64u(fields["k"]) if len(fields["k"]) == 43 else None
    if pin is None or len(pin) != 32:
        return ("reject", "BAD_PIN")
    secret = unb64u(fields["s"]) if len(fields["s"]) == 43 else None
    if secret is None or len(secret) != 32:
        return ("reject", "BAD_SECRET")
    xt = fields["x"]
    if not re.fullmatch(r"[0-9]{1,12}", xt) or (len(xt) > 1 and xt[0] == "0"):
        return ("reject", "BAD_EXPIRY")
    x = int(xt)
    if now >= x + 60:
        return ("reject", "EXPIRED")
    if x > now + 120 + 60:
        return ("reject", "EXPIRY_TOO_FAR")
    if not fields["a"]:
        return ("reject", "ENDPOINT_COUNT")
    items = fields["a"].split(",")
    if len(items) > 4:
        return ("reject", "ENDPOINT_COUNT")
    eps = []
    for item in items:
        ep = parse_endpoint(item)
        if ep is None:
            return ("reject", "DNS_NAME" if looks_dns(item) else "ENDPOINT_SYNTAX")
        if not eligible(ep, loopback_ok):
            return ("reject", "ADDRESS_NOT_ELIGIBLE")
        eps.append(ep)
    name = pct_decode(fields["n"])
    if name is None:
        return ("reject", "BAD_NAME")
    cps = len(name)
    if cps == 0:
        return ("reject", "BAD_NAME")
    if cps > 32:
        return ("reject", "NAME_TOO_LONG")
    return ("ok", {"k": b64u(pin), "a": [("[%s]:%d" % (a, p)) if v6 else "%s:%d" % (a, p) for a, p, v6 in eps], "s": b64u(secret), "x": x, "n": name})


def qr_encode(k, a, s, x, n):
    return "asom-pair:1?k=%s&a=%s&s=%s&x=%d&n=%s" % (k, ",".join(a), s, x, pct_encode(n))
