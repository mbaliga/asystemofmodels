"""Illustrative seed vectors for the asom mesh conformance suite.
Computed in the design session with Python 3.11 + cryptography 41. NOT normative.
The P-256 key below is a TEST-ONLY key generated for these examples."""
import json, base64, hashlib
from decimal import Decimal, ROUND_HALF_UP
import secrets, subprocess, tempfile, os
# --- minimal pure-Python P-256 (illustration only; NOT constant-time) ---
P = 0xffffffff00000001000000000000000000000000ffffffffffffffffffffffff
A = P - 3
B = 0x5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b
G = (0x6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296,
     0x4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5)
def inv(x, m): return pow(x, -1, m)
def add(p, q):
    if p is None: return q
    if q is None: return p
    if p[0] == q[0] and (p[1] + q[1]) % P == 0: return None
    if p == q: l = (3*p[0]*p[0] + A) * inv(2*p[1], P) % P
    else: l = (q[1]-p[1]) * inv(q[0]-p[0], P) % P
    x = (l*l - p[0] - q[0]) % P
    return (x, (l*(p[0]-x) - p[1]) % P)
def mul(k, p):
    r = None
    while k:
        if k & 1: r = add(r, p)
        p = add(p, p); k >>= 1
    return r
def der_int(i):
    b = i.to_bytes((i.bit_length()+8)//8, 'big')  # always leaves room for sign bit
    b = b.lstrip(b'\x00') or b'\x00'
    if b[0] & 0x80: b = b'\x00' + b
    return b'\x02' + bytes([len(b)]) + b
def der_sig(r, s):
    body = der_int(r) + der_int(s); return b'\x30' + bytes([len(body)]) + body

def b64u(b): return base64.urlsafe_b64encode(b).rstrip(b'=').decode()
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551

class NonInteger(Exception): pass
def jcs(v):
    """RFC 8785 restricted to the asom integer-only profile."""
    if isinstance(v, bool): return 'true' if v else 'false'
    if v is None: return 'null'
    if isinstance(v, int):
        if abs(v) > 2**53 - 1: raise NonInteger('outside I-JSON exact range')
        return str(v)
    if isinstance(v, float): raise NonInteger('non-integer number')
    if isinstance(v, str): return json.dumps(v, ensure_ascii=False)
    if isinstance(v, list): return '[' + ','.join(jcs(x) for x in v) + ']'
    if isinstance(v, dict):
        ks = sorted(v.keys(), key=lambda k: k.encode('utf-16-be'))
        return '{' + ','.join(json.dumps(k, ensure_ascii=False) + ':' + jcs(v[k]) for k in ks) + '}'
    raise TypeError(type(v))

out = {}
# ---- M01 canonicalization
m01_in = {"b":1,"a":2,"é":3,"\U0001F600":4,"Ａ":5,"url":"https://x/y","ctl":"\u0001\n","nfd":"é"}
canon = jcs(m01_in)
codepoint_order = '{' + ','.join(json.dumps(k, ensure_ascii=False)+':'+jcs(m01_in[k]) for k in sorted(m01_in)) + '}'
out['M01'] = {
  "id":"M01-001","origin":"hand","status":"illustrative",
  "description":"UTF-16 code-unit key order (U+1F600 sorts BEFORE U+FF21), '/' not escaped, control chars as lowercase \\u00XX, no Unicode normalization",
  "input_json_escaped": json.dumps(m01_in, ensure_ascii=True),
  "expect_utf8_hex": canon.encode().hex(),
  "expect_text": canon,
  "trap_codepoint_order_WRONG": codepoint_order,
}
# ---- M02/M03 signatures
d = 0x5EEDA50C00000000000000000000000000000000000000000000000000000001  # TEST-ONLY private scalar
Q = mul(d, G)
spki = bytes.fromhex('3059301306072a8648ce3d020106082a8648ce3d030107034200') + b'\x04' + Q[0].to_bytes(32,'big') + Q[1].to_bytes(32,'big')
payload = {"schema":"asom.manifest/0-example","nodeId":"n_test","decodeMilliTokPerSec":12345,
           "issuedAtMs":1759104000000,"expiresAtMs":1759708800000}
pbytes = jcs(payload).encode()
h = int.from_bytes(hashlib.sha256(pbytes).digest(), 'big')
def sign(msg_h, priv):
    while True:
        k = secrets.randbelow(N-1) + 1
        r = mul(k, G)[0] % N
        if r == 0: continue
        s = inv(k, N) * (msg_h + r*priv) % N
        if s: return r, s
def verify(msg_h, r, s, pubpt):
    if not (0 < r < N and 0 < s < N): return False
    w = inv(s, N); X = add(mul(msg_h*w % N, G), mul(r*w % N, pubpt))
    return X is not None and X[0] % N == r
tries = 0
while True:
    tries += 1
    r, s = sign(h, d)
    if r < 2**248: break
raw = r.to_bytes(32,'big') + s.to_bytes(32,'big')
s_high = max(s, N - s)
raw_high = r.to_bytes(32,'big') + s_high.to_bytes(32,'big')
der = der_sig(r, s)
# cross-check with OpenSSL CLI
td = tempfile.mkdtemp()
open(os.path.join(td,'pub.der'),'wb').write(spki)
open(os.path.join(td,'msg'),'wb').write(pbytes)
open(os.path.join(td,'sig.der'),'wb').write(der)
ossl = subprocess.run(['openssl','dgst','-sha256','-verify',os.path.join(td,'pub.der'),'-keyform','DER','-signature',os.path.join(td,'sig.der'),os.path.join(td,'msg')],capture_output=True,text=True)
tampered = dict(payload); tampered["decodeMilliTokPerSec"] = 12346
th = int.from_bytes(hashlib.sha256(jcs(tampered).encode()).digest(),'big')
d2 = secrets.randbelow(N-1)+1
r2, s2 = sign(h, d2)
out['KEY'] = {"TEST_ONLY":True,"curve":"P-256","jwk":{"kty":"EC","crv":"P-256","x":b64u(Q[0].to_bytes(32,'big')),"y":b64u(Q[1].to_bytes(32,'big'))},
              "spki_der_b64":base64.b64encode(spki).decode(),
              "W05_spki_sha256_b64url": b64u(hashlib.sha256(spki).digest()),
              "openssl_verify_of_M02_001_der": (ossl.stdout+ossl.stderr).strip()}
out['M02'] = [
 {"id":"M02-001","description":"ES256 raw r||s over JCS bytes; r has a leading zero octet (must be kept: fixed 32-octet width)",
  "signed_text":pbytes.decode(),"sig_raw_b64url":b64u(raw),"r_leading_zero_octets": 32 - (r.bit_length()+7)//8,
  "expect":"accept","selfcheck":verify(h,r,s,Q),"tries_to_find":tries},
 {"id":"M02-002","description":"same signature with S replaced by its high form (n - s where needed): ECDSA verification accepts it; implementations MUST accept and MUST NOT use signature bytes as an identifier",
  "sig_raw_b64url":b64u(raw_high),"expect":"accept","selfcheck":verify(h,r,s_high,Q)},
]
out['M03'] = [
 {"id":"M03-001","description":"one integer changed after signing","signed_text":jcs(tampered),"sig_raw_b64url":b64u(raw),"expect":"reject:SIGNATURE_INVALID","selfcheck_verify_result":verify(th,r,s,Q)},
 {"id":"M03-002","description":"DER-encoded signature supplied where the envelope requires 64-octet raw r||s","sig_b64url":b64u(der),"len":len(der),"expect":"reject:SIGNATURE_ENCODING"},
 {"id":"M03-003","description":"valid signature by a different key than the pinned node key","sig_raw_b64url":b64u(r2.to_bytes(32,'big')+s2.to_bytes(32,'big')),"expect":"reject:KEY_NOT_PINNED","selfcheck_verifies_under_other_key":verify(h,r2,s2,mul(d2,G))},
 {"id":"M03-004","description":"non-integer number in signed object","input_text":'{"decodeMilliTokPerSec":12.5}',"expect":"reject:NON_INTEGER_NUMBER"},
 {"id":"M03-005","description":"duplicate member name","input_text":'{"a":1,"a":1}',"expect":"reject:DUPLICATE_KEY"},
]
# ---- W01 USD rendering (Java BigDecimal.valueOf(d).setScale(8,HALF_UP).stripTrailingZeros().toPlainString())
def java_usd(d):
    q = Decimal(repr(d)).quantize(Decimal('1e-8'), rounding=ROUND_HALF_UP)
    s = format(q.normalize(), 'f')
    return '0' if Decimal(s) == 0 else s
rows = []
for d in [0.00012, 1.25e-9, 1.25e-7, 2.5e-8, 123.456789125, 0.1+0.2]:
    rows.append({"costEst_double_repr":repr(d),"expect":java_usd(d),
                 "trap_exact_binary_then_half_up":format(Decimal(d).quantize(Decimal('1e-8'),rounding=ROUND_HALF_UP).normalize(),'f'),
                 "trap_half_even_on_decimal":format(Decimal(repr(d)).quantize(Decimal('1e-8')).normalize(),'f')})
out['W01_usd'] = rows
# ---- W03 SSE reframing
parts = [b'data: ', b'{"a":1}\n\n', b': keep-alive\n\ndata: {"b"', b':2}\r\n\r\ndata: [DONE]\n\n']
out['W03'] = {"id":"W03-001","description":"events split across reads, comment line, CRLF terminator, [DONE]",
  "chunks_b64":[base64.b64encode(p).decode() for p in parts],"expect":{"events":['{"a":1}','{"b":2}'],"done":True}}
print(json.dumps(out, indent=1, ensure_ascii=False))
json.dump(out, open('seed-vectors.json','w'), indent=1, ensure_ascii=False)
