"""Reference generator + reference verifier for the asom capability manifest (manifest.md).

DESIGN-SESSION ARTEFACT (2026-09-29). NOT NORMATIVE until owner sign-off.
All keys here are TEST-ONLY. All benchmark numbers are INVENTED for illustration; they are not measurements.

Deterministic: ECDSA nonces follow RFC 6979 (checked against RFC 6979 A.2.5 and against the
`cryptography` library's deterministic ECDSA), so re-running produces byte-identical output.
"""
import base64, copy, hashlib, hmac, json, os, re, sys, datetime

HERE = os.path.dirname(os.path.abspath(__file__))

# ----------------------------------------------------------------------------------------------
# P-256 arithmetic (reference only; not constant time)
# ----------------------------------------------------------------------------------------------
P = 0xffffffff00000001000000000000000000000000ffffffffffffffffffffffff
A = P - 3
B = 0x5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b
N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551
G = (0x6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296,
     0x4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5)

def _add(p, q):
    if p is None: return q
    if q is None: return p
    if p[0] == q[0] and (p[1] + q[1]) % P == 0: return None
    if p == q: l = (3 * p[0] * p[0] + A) * pow(2 * p[1], -1, P) % P
    else: l = (q[1] - p[1]) * pow(q[0] - p[0], -1, P) % P
    x = (l * l - p[0] - q[0]) % P
    return (x, (l * (p[0] - x) - p[1]) % P)

def _mul(k, p):
    r = None
    while k:
        if k & 1: r = _add(r, p)
        p = _add(p, p); k >>= 1
    return r

def on_curve(pt):
    x, y = pt
    return (y * y - (x * x * x + A * x + B)) % P == 0

SPKI_PREFIX = bytes.fromhex('3059301306072a8648ce3d020106082a8648ce3d030107034200')

def spki_of(d):
    Q = _mul(d, G)
    return SPKI_PREFIX + b'\x04' + Q[0].to_bytes(32, 'big') + Q[1].to_bytes(32, 'big')

def point_of_spki(spki):
    if len(spki) != 91 or spki[:26] != SPKI_PREFIX or spki[26] != 4:
        return None
    pt = (int.from_bytes(spki[27:59], 'big'), int.from_bytes(spki[59:91], 'big'))
    return pt if (pt[0] < P and pt[1] < P and on_curve(pt)) else None

def rfc6979_k(x, h1):
    def i2o(v): return v.to_bytes(32, 'big')
    def b2o(b): return i2o(int.from_bytes(b, 'big') % N)
    V = b'\x01' * 32; K = b'\x00' * 32
    K = hmac.new(K, V + b'\x00' + i2o(x) + b2o(h1), hashlib.sha256).digest(); V = hmac.new(K, V, hashlib.sha256).digest()
    K = hmac.new(K, V + b'\x01' + i2o(x) + b2o(h1), hashlib.sha256).digest(); V = hmac.new(K, V, hashlib.sha256).digest()
    while True:
        V = hmac.new(K, V, hashlib.sha256).digest()
        k = int.from_bytes(V, 'big')
        if 1 <= k < N: return k
        K = hmac.new(K, V + b'\x00', hashlib.sha256).digest(); V = hmac.new(K, V, hashlib.sha256).digest()

def sign_raw(d, msg, low_s=True):
    h1 = hashlib.sha256(msg).digest(); e = int.from_bytes(h1, 'big')
    k = rfc6979_k(d, h1)
    r = _mul(k, G)[0] % N
    s = pow(k, -1, N) * (e + r * d) % N
    if low_s and s > N // 2: s = N - s
    return r.to_bytes(32, 'big') + s.to_bytes(32, 'big')

def verify_raw(spki, msg, sig):
    Q = point_of_spki(spki)
    if Q is None or len(sig) != 64: return False
    r = int.from_bytes(sig[:32], 'big'); s = int.from_bytes(sig[32:], 'big')
    if not (0 < r < N and 0 < s < N): return False
    e = int.from_bytes(hashlib.sha256(msg).digest(), 'big')
    w = pow(s, -1, N)
    X = _add(_mul(e * w % N, G), _mul(r * w % N, Q))
    return X is not None and X[0] % N == r

def der_sig(raw):
    def di(b):
        b = b.lstrip(b'\x00') or b'\x00'
        if b[0] & 0x80: b = b'\x00' + b
        return b'\x02' + bytes([len(b)]) + b
    body = di(raw[:32]) + di(raw[32:])
    return b'\x30' + bytes([len(body)]) + body

# RFC 6979 A.2.5 self-test (P-256, SHA-256, message "sample")
_x = 0xC9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721
_k = rfc6979_k(_x, hashlib.sha256(b'sample').digest())
assert _k == 0xA6E3C57DD01ABE90086538398355DD4C3B17AA873382B0F24D6129493D8AAD60, hex(_k)
_sig = sign_raw(_x, b'sample', low_s=False)
assert _sig.hex().upper() == ('EFD48B2AACB6A8FD1140DD9CD45E81D69D2C877B56AAF991C34D0EA84EAF3716'
                              'F7CB1C942D657C41D436C7A1B6E29F65F3E900DBB9AFF4064DC4AB2F843ACDA8'), _sig.hex()

# ----------------------------------------------------------------------------------------------
# base64 helpers (DSSE: producers emit standard base64 with padding; verifiers accept std or url-safe)
# ----------------------------------------------------------------------------------------------
def b64u(b): return base64.urlsafe_b64encode(b).rstrip(b'=').decode()
def b64s(b): return base64.b64encode(b).decode()

class Reject(Exception):
    def __init__(self, code, detail=''):
        super().__init__(code); self.code = code; self.detail = detail

def b64_either(s):
    if not isinstance(s, str) or not re.fullmatch(r'[A-Za-z0-9+/_-]*={0,2}', s): raise Reject('ENCODING', 'alphabet')
    if ('+' in s or '/' in s) and ('-' in s or '_' in s): raise Reject('ENCODING', 'mixed alphabets')
    core = s.rstrip('=')
    if len(s) != len(core) and len(s) % 4 != 0: raise Reject('ENCODING', 'bad padding')
    std = core.replace('-', '+').replace('_', '/')
    try:
        raw = base64.b64decode(std + '=' * (-len(std) % 4), validate=True)
    except Exception:
        raise Reject('ENCODING', 'undecodable')
    if base64.b64encode(raw).decode().rstrip('=') != std: raise Reject('ENCODING', 'non-canonical trailing bits')
    return raw

# ----------------------------------------------------------------------------------------------
# JCS, asom integer profile (RFC 8785 restricted to integers within +-(2^53-1)) + strict parser
# ----------------------------------------------------------------------------------------------
MAXI = 2 ** 53 - 1

def _jcs_str(s):
    out = ['"']
    for ch in s:
        c = ord(ch)
        if 0xD800 <= c <= 0xDFFF: raise Reject('INVALID_UNICODE')
        if ch == '"': out.append('\\"')
        elif ch == '\\': out.append('\\\\')
        elif ch == '\b': out.append('\\b')
        elif ch == '\t': out.append('\\t')
        elif ch == '\n': out.append('\\n')
        elif ch == '\f': out.append('\\f')
        elif ch == '\r': out.append('\\r')
        elif c < 0x20: out.append('\\u%04x' % c)
        else: out.append(ch)
    out.append('"')
    return ''.join(out)

def jcs(v):
    if v is True: return 'true'
    if v is False: return 'false'
    if v is None: return 'null'
    if isinstance(v, int):
        if abs(v) > MAXI: raise Reject('NUMBER_RANGE')
        return str(v)
    if isinstance(v, float): raise Reject('NON_INTEGER_NUMBER')
    if isinstance(v, str): return _jcs_str(v)
    if isinstance(v, list): return '[' + ','.join(jcs(x) for x in v) + ']'
    if isinstance(v, dict):
        ks = sorted(v.keys(), key=lambda k: k.encode('utf-16-be'))
        return '{' + ','.join(_jcs_str(k) + ':' + jcs(v[k]) for k in ks) + '}'
    raise TypeError(type(v))

def jcs_bytes(v): return jcs(v).encode('utf-8')

def _pairs(pairs):
    d = {}
    for k, v in pairs:
        if k in d: raise Reject('DUPLICATE_KEY', k)
        d[k] = v
    return d

def _no_float(s): raise Reject('NON_INTEGER_NUMBER', s)
def _no_const(s): raise Reject('NON_INTEGER_NUMBER', s)

def _int(s):
    if s == '-0': raise Reject('NON_INTEGER_NUMBER', '-0')
    v = int(s)
    if abs(v) > MAXI: raise Reject('NUMBER_RANGE', s)
    return v

def _check_strings(v):
    if isinstance(v, str):
        for ch in v:
            if 0xD800 <= ord(ch) <= 0xDFFF: raise Reject('INVALID_UNICODE')
    elif isinstance(v, list):
        for x in v: _check_strings(x)
    elif isinstance(v, dict):
        for k, x in v.items(): _check_strings(k); _check_strings(x)

def strict_parse(b):
    try:
        text = b.decode('utf-8', errors='strict')
    except UnicodeDecodeError:
        raise Reject('INVALID_UNICODE', 'utf-8')
    if text.startswith('﻿'): raise Reject('MALFORMED_JSON', 'BOM')
    dec = json.JSONDecoder(object_pairs_hook=_pairs, parse_float=_no_float, parse_int=_int, parse_constant=_no_const, strict=True)
    try:
        obj, end = dec.raw_decode(text)
    except Reject:
        raise
    except json.JSONDecodeError as e:
        raise Reject('MALFORMED_JSON', str(e))
    if text[end:].strip(' \t\r\n') != '': raise Reject('TRAILING_DATA')
    _check_strings(obj)
    if _depth(obj) > MAX_DEPTH: raise Reject('MALFORMED_JSON', 'depth')
    return obj, end == len(text)

MAX_DEPTH = 16
def _depth(v):
    if isinstance(v, dict): return 1 + max((_depth(x) for x in v.values()), default=0)
    if isinstance(v, list): return 1 + max((_depth(x) for x in v), default=0)
    return 0

# ----------------------------------------------------------------------------------------------
# DSSE
# ----------------------------------------------------------------------------------------------
PT_V1 = 'application/vnd.asom.manifest.v1+json'
PT_ROLLOVER = 'application/vnd.asom.key-rollover.v1+json'

def pae(ptype, body):
    t = ptype.encode('utf-8')
    return b'DSSEv1 ' + str(len(t)).encode() + b' ' + t + b' ' + str(len(body)).encode() + b' ' + body

def node_id(spki): return b64u(hashlib.sha256(spki).digest())

def node_tag(spki):
    return base64.b32encode(hashlib.sha256(spki).digest()).decode().lower()[:16]

def display_fp(spki):
    t = node_tag(spki).upper()
    return '-'.join(t[i:i + 4] for i in range(0, 16, 4))

def make_container(payload_bytes, d, spki, ptype=PT_V1, sig_override=None, extra_sigs=0, keyid=None,
                   b64=b64s, include_signer=True, evidence=None):
    sig = sig_override if sig_override is not None else sign_raw(d, pae(ptype, payload_bytes))
    sigs = [{'keyid': keyid or node_id(spki), 'sig': b64(sig)}]
    for _ in range(extra_sigs): sigs.append(dict(sigs[0]))
    c = {'asomCapabilityManifest': 1,
         'dsse': {'payloadType': ptype, 'payload': b64(payload_bytes), 'signatures': sigs}}
    if include_signer: c['signer'] = {'spki': b64s(spki)}
    if evidence is not None: c['evidence'] = evidence
    return jcs(c)   # the container itself is emitted in JCS form for reproducibility (not required)

# ----------------------------------------------------------------------------------------------
# TEST-ONLY keys
# ----------------------------------------------------------------------------------------------
D1 = int.from_bytes(hashlib.sha256(b'asom TEST-ONLY manifest key 1').digest(), 'big') % N
D2 = int.from_bytes(hashlib.sha256(b'asom TEST-ONLY manifest key 2').digest(), 'big') % N
SPKI1 = spki_of(D1); SPKI2 = spki_of(D2)
NODE1 = node_id(SPKI1); NODE2 = node_id(SPKI2)

# ----------------------------------------------------------------------------------------------
# Example payload (ILLUSTRATIVE: fictional device, invented numbers)
# ----------------------------------------------------------------------------------------------
def ms(y, mo, d, h=0, mi=0):
    return int(datetime.datetime(y, mo, d, h, mi, tzinfo=datetime.timezone.utc).timestamp() * 1000)

ISSUED = ms(2026, 9, 29, 10, 0)
CHALLENGE = hashlib.sha256(b'asom TEST-ONLY requester challenge 1').digest()
MODEL_SHA_Q4 = hashlib.sha256(b'ILLUSTRATIVE model file example-3b-instruct Q4_K_M').hexdigest()
MODEL_SHA_Q8 = hashlib.sha256(b'ILLUSTRATIVE model file example-3b-instruct Q8_0').hexdigest()

def curve_q4():
    pts = []
    for i in range(21):
        t = i * 30000
        if t < 190000: rate = 18400 - i * 60
        else: rate = max(11200, 18400 - 3000 - (i - 6) * 900)
        temp = 33000 + min(i, 10) * 1500
        pts.append([t, rate, temp, 6100 + (100 if i < 6 else 0)])
    return pts

def base_payload():
    body = {
        'audience': 'own',
        'seq': 17,
        'subject': {'nodeId': NODE1, 'keyAlg': 'ES256', 'keyStorage': 'strongbox'},
        'producer': {
            'app': 'asom-android', 'appVersion': '4.0.0',
            'harness': {'id': 'asom-bench', 'version': '1.0.0', 'methodologyId': 'asom-bench-method/1', 'confVersion': '1.0.0'},
            'engine': {'name': 'llama.cpp', 'commit': '0123abcd', 'buildFlags': ['GGML_OPENCL=ON', 'GGML_VULKAN=ON']},
        },
        'device': {
            'class': 'phone', 'vendor': 'ExampleVendor', 'model': 'Example Phone 1',
            'platformIds': {'brand': 'examplevendor', 'device': 'ex1', 'manufacturer': 'ExampleVendor', 'model': 'EX-1', 'product': 'ex1_global'},
            'os': {'family': 'android', 'version': '16', 'securityPatch': '2026-09-01'},
            'soc': {'vendor': 'ExampleSilicon', 'name': 'ExampleSoC 8',
                    'cpu': {'logicalCores': 8, 'clusters': [{'cores': 2, 'maxKHz': 4320000}, {'cores': 6, 'maxKHz': 3530000}]}},
            'memory': {'totalBytes': 17179869184},
            'accelerators': [
                {'kind': 'gpu', 'vendor': 'ExampleSilicon', 'name': 'ExampleGPU 800', 'apis': ['opencl', 'vulkan'], 'dedicatedBytes': None},
                {'kind': 'npu', 'vendor': 'ExampleSilicon', 'name': 'ExampleNPU', 'apis': [], 'dedicatedBytes': None},
            ],
            'power': {'battery': True, 'batteryDesignMilliWh': 25000},
            'thermal': {'cooling': 'fan', 'stateSource': 'android-thermal-headroom'},
        },
        'results': [
            {
                'modelId': 'example-3b-instruct', 'fileSha256': MODEL_SHA_Q4, 'fileBytes': 2019377152, 'quant': 'Q4_K_M',
                'backend': 'opencl',
                'settings': {'threads': 6, 'gpuLayers': 99, 'ctxTokens': 4096, 'batchTokens': 512},
                'measuredAtMs': ms(2026, 9, 28, 14, 2),
                'runs': {'planned': 5, 'completed': 5, 'discarded': 0},
                'conditions': {'charging': False, 'batteryStartPermille': 870, 'thermalStart': 'nominal', 'socStartMilliC': 33000, 'screenOn': True},
                'prefill': [
                    {'promptTokens': 512, 'milliTokPerSec': {'p10': 204000, 'p50': 212000, 'p90': 216500}, 'ttftMicros': {'p10': 2410000, 'p50': 2480000, 'p90': 2590000}},
                    {'promptTokens': 2048, 'milliTokPerSec': {'p10': 181000, 'p50': 188000, 'p90': 192000}, 'ttftMicros': {'p10': 10700000, 'p50': 10950000, 'p90': 11400000}},
                ],
                'decode': [
                    {'contextTokens': 512, 'genTokens': 128, 'milliTokPerSec': {'p10': 17600, 'p50': 18400, 'p90': 18900}},
                    {'contextTokens': 3584, 'genTokens': 128, 'milliTokPerSec': {'p10': 14500, 'p50': 15100, 'p90': 15600}},
                ],
                'sustained': {'durationMs': 600000, 'intervalMs': 30000, 'steadyMilliTokPerSec': 11200, 'throttleOnsetMs': 190000, 'curve': curve_q4()},
                'memory': {'availableBeforeLoadBytes': 8300000000, 'peakProcessBytes': 2600000000, 'kvCacheBytes': 469762048},
                'power': {'method': 'battery-current', 'avgMilliW': 6100},
                'flags': ['thermal-throttled'],
            },
            {
                'modelId': 'example-3b-instruct', 'fileSha256': MODEL_SHA_Q8, 'fileBytes': 3421225472, 'quant': 'Q8_0',
                'backend': 'cpu',
                'settings': {'threads': 6, 'gpuLayers': 0, 'ctxTokens': 4096, 'batchTokens': 512},
                'measuredAtMs': ms(2026, 9, 28, 14, 40),
                'runs': {'planned': 5, 'completed': 3, 'discarded': 1},
                'conditions': {'charging': True, 'batteryStartPermille': 640, 'thermalStart': 'light', 'socStartMilliC': 38000, 'screenOn': True},
                'prefill': [
                    {'promptTokens': 512, 'milliTokPerSec': {'p50': 61000}, 'ttftMicros': {'p50': 8600000}},
                ],
                'decode': [
                    {'contextTokens': 512, 'genTokens': 128, 'milliTokPerSec': {'p50': 9800}},
                ],
                'sustained': {'durationMs': 300000, 'intervalMs': 60000, 'steadyMilliTokPerSec': 9100, 'throttleOnsetMs': None,
                              'curve': [[0, 9800, 38000, None], [60000, 9500, 41000, None], [120000, 9300, 43000, None],
                                        [180000, 9200, 44000, None], [240000, 9100, 44000, None], [300000, 9100, 45000, None]]},
                'memory': {'availableBeforeLoadBytes': 8100000000, 'peakProcessBytes': 3900000000, 'kvCacheBytes': None},
                'power': {'method': 'unavailable', 'avgMilliW': None},
                'flags': ['charging', 'low-runs'],
            },
        ],
    }
    return {'schema': 'asom.manifest/1', 'schemaMinor': 0, 'body': body,
            'presentation': {'issuedAtMs': ISSUED, 'expiresAtMs': ISSUED + 600000, 'challenge': b64u(CHALLENGE)}}

def body_digest(obj): return b64u(hashlib.sha256(jcs_bytes(obj['body'])).digest())

# ----------------------------------------------------------------------------------------------
# Schema validation (strict for producers; tolerant for consumers)
# ----------------------------------------------------------------------------------------------
try:
    import jsonschema
except ImportError:
    jsonschema = None

SCHEMA = json.load(open(os.path.join(HERE, 'asom.manifest.1.schema.json')))
PUB_SCHEMA = json.load(open(os.path.join(HERE, 'asom.bench-public.1.schema.json')))

def _tolerant(s):
    s = copy.deepcopy(s)
    def walk(n):
        if isinstance(n, dict):
            if n.get('additionalProperties') is False: n['additionalProperties'] = True
            for v in n.values(): walk(v)
        elif isinstance(n, list):
            for v in n: walk(v)
    walk(s); return s

TOLERANT = _tolerant(SCHEMA)

def schema_ok(obj, schema):
    if jsonschema is None: raise SystemExit('jsonschema required')
    v = jsonschema.Draft202012Validator(schema)
    errs = sorted(v.iter_errors(obj), key=lambda e: list(e.path))
    return [f"{list(e.path)}: {e.message[:120]}" for e in errs]

# ----------------------------------------------------------------------------------------------
# Reference verifier (manifest.md section 8). Returns dict or raises Reject.
# ----------------------------------------------------------------------------------------------
MAX_DOC = 524288
TTL_CHALLENGE = 600000
TTL_UNSOLICITED = 30 * 86400000
FUTURE_SKEW = 300000

def consistency(o):
    b = o['body']; pres = o['presentation']
    seen = set()
    for r in b['results']:
        key = (r['fileSha256'], r['backend'])
        if key in seen: return 'duplicate result row'
        seen.add(key)
        if r['runs']['completed'] > r['runs']['planned'] or r['runs']['discarded'] > r['runs']['completed']: return 'runs'
        pcts = [p['milliTokPerSec'] for p in r['prefill']] + [p['ttftMicros'] for p in r['prefill']] + [d['milliTokPerSec'] for d in r['decode']]
        for pc in pcts:
            vals = [pc.get('p10'), pc['p50'], pc.get('p90')]
            if pc.get('p10') is not None and pc['p10'] > pc['p50']: return 'p10>p50'
            if pc.get('p90') is not None and pc['p90'] < pc['p50']: return 'p90<p50'
        for p in r['prefill']:
            if p['promptTokens'] > r['settings']['ctxTokens']: return 'prompt>ctx'
            # TTFT cannot be much shorter than the prompt-processing time implied by the prefill rate
            if p['ttftMicros']['p50'] * p['milliTokPerSec']['p50'] * 10 < p['promptTokens'] * 10**9 * 9: return 'ttft<prefill'
        for d in r['decode']:
            if d['contextTokens'] + d['genTokens'] > r['settings']['ctxTokens']: return 'decode>ctx'
        s = r['sustained']; c = s['curve']
        if c[0][0] != 0: return 'curve start'
        for i in range(1, len(c)):
            if c[i][0] <= c[i - 1][0]: return 'curve order'
        if c[-1][0] > s['durationMs']: return 'curve>duration'
        if s['throttleOnsetMs'] is not None and s['throttleOnsetMs'] > s['durationMs']: return 'onset>duration'
        if s['steadyMilliTokPerSec'] > max(p[1] for p in c): return 'steady>max'
        if r['memory']['peakProcessBytes'] > b['device']['memory']['totalBytes']: return 'peak>total'
        if r['memory']['availableBeforeLoadBytes'] > b['device']['memory']['totalBytes']: return 'avail>total'
        if r['measuredAtMs'] > pres['issuedAtMs']: return 'measured after issued'
        if r['power']['method'] == 'unavailable' and r['power']['avgMilliW'] is not None: return 'power'
    return None

def evaluate_evidence(container, key_spki, body):
    """Tier logic without real platform evidence (vectors cover A0/A1 only; A2 needs captured chains)."""
    ev = container.get('evidence') or []
    hw = body['subject']['keyStorage'] in ('strongbox', 'tee', 'secure-enclave', 'tpm')
    if ev:
        return {'tier': 'A1' if hw else 'A0', 'warnings': ['EVIDENCE_UNVERIFIABLE_IN_REFERENCE']}
    return {'tier': 'A1' if hw else 'A0', 'warnings': []}

TIER_ORDER = {'A0': 0, 'A1': 1, 'A2': 2}

def verify(doc_text, ctx, now_ms):
    """ctx: {mode: pinned|tofu, pinnedSpki?: b64, tofu?: {keyid: b64spki}, expectedChallenge?: b64u,
             rollback?: {"<nodeId>|<audience>": {seq, bodyDigest}}, requiredTier?: A0|A1|A2}"""
    doc = doc_text.encode('utf-8')
    if len(doc) > MAX_DOC: raise Reject('TOO_LARGE')
    container, _ = strict_parse(doc)
    if not isinstance(container, dict) or container.get('asomCapabilityManifest') != 1: raise Reject('CONTAINER_VERSION_UNKNOWN')
    env = container.get('dsse')
    if not isinstance(env, dict) or not isinstance(env.get('payloadType'), str) or not isinstance(env.get('signatures'), list):
        raise Reject('CONTAINER_INVALID')
    pt = env['payloadType']
    if pt != PT_V1:
        raise Reject('SCHEMA_MAJOR_UNKNOWN' if re.fullmatch(r'application/vnd\.asom\.manifest\.v[0-9]+\+json', pt) else 'PAYLOAD_TYPE_UNSUPPORTED')
    if len(env['signatures']) != 1: raise Reject('SIGNATURE_COUNT')
    s0 = env['signatures'][0]
    if not isinstance(s0, dict) or 'sig' not in s0: raise Reject('CONTAINER_INVALID')
    payload = b64_either(env.get('payload'))
    sig = b64_either(s0['sig'])
    if len(sig) != 64: raise Reject('SIGNATURE_ENCODING')
    # key selection
    if ctx['mode'] == 'pinned':
        key = base64.b64decode(ctx['pinnedSpki']); pin_state = 'PINNED'
        if 'keyid' in s0 and s0['keyid'] != node_id(key): raise Reject('KEY_NOT_PINNED')   # hint used only to pick the error code
    else:
        tofu = ctx.get('tofu', {})
        hint = s0.get('keyid')
        presented = b64_either(container['signer']['spki']) if isinstance(container.get('signer'), dict) and 'spki' in container['signer'] else None
        if hint in tofu:
            key = base64.b64decode(tofu[hint]); pin_state = 'TOFU_MATCH'
            if presented is not None and presented != key: raise Reject('KEY_CHANGED')
        elif presented is not None:
            key = presented; pin_state = 'TOFU_NEW'
        else:
            raise Reject('KEY_NOT_PINNED')
    if point_of_spki(key) is None: raise Reject('ALG_UNSUPPORTED')
    if not verify_raw(key, pae(PT_V1, payload), sig): raise Reject('SIGNATURE_INVALID')
    # ---- from here on, ONLY `payload` (the verified bytes) is used ----
    obj, _ = strict_parse(payload)
    if jcs_bytes(obj) != payload: raise Reject('NON_CANONICAL')
    if not isinstance(obj, dict) or not isinstance(obj.get('schema'), str): raise Reject('SCHEMA_INVALID')
    if obj['schema'] != 'asom.manifest/1':
        raise Reject('SCHEMA_MAJOR_UNKNOWN' if re.fullmatch(r'asom\.manifest/[0-9]+', obj['schema']) else 'SCHEMA_INVALID')
    errs = schema_ok(obj, TOLERANT)
    if errs: raise Reject('SCHEMA_INVALID', errs[0])
    body = obj['body']; pres = obj['presentation']
    if body['subject']['nodeId'] != node_id(key): raise Reject('SUBJECT_KEY_MISMATCH')
    if pres['issuedAtMs'] > now_ms + FUTURE_SKEW: raise Reject('NOT_YET_VALID')
    if now_ms >= pres['expiresAtMs']: raise Reject('EXPIRED')
    ttl = pres['expiresAtMs'] - pres['issuedAtMs']
    if ttl <= 0 or ttl > (TTL_CHALLENGE if pres['challenge'] is not None else TTL_UNSOLICITED): raise Reject('TTL_INVALID')
    exp = ctx.get('expectedChallenge')
    if exp is not None and not hmac.compare_digest((pres['challenge'] or '').encode(), exp.encode()): raise Reject('NONCE_MISMATCH')
    why = consistency(obj)
    if why: raise Reject('INCONSISTENT', why)
    digest = body_digest(obj)
    rb = (ctx.get('rollback') or {}).get(body['subject']['nodeId'] + '|' + body['audience'])
    if rb:
        if body['seq'] < rb['seq']: raise Reject('ROLLBACK')
        if body['seq'] == rb['seq'] and digest != rb['bodyDigest']: raise Reject('EQUIVOCATION')
    ev = evaluate_evidence(container, key, body)
    if TIER_ORDER[ev['tier']] < TIER_ORDER[ctx.get('requiredTier', 'A0')]: raise Reject('TIER_INSUFFICIENT')
    return {'pin': pin_state, 'tier': ev['tier'], 'seq': body['seq'], 'bodyDigest': digest,
            'nodeId': body['subject']['nodeId'], 'unknownFields': count_unknown(obj, SCHEMA), 'warnings': ev['warnings'],
            '_obj': obj}

def count_unknown(inst, schema):
    """Count properties not described by the strict schema (rendered as 'N newer items not shown')."""
    root = schema
    def resolve(s):
        while isinstance(s, dict) and '$ref' in s and len(s) <= 2:
            path = s['$ref'].lstrip('#/').split('/'); t = root
            for p in path: t = t[p]
            s = t
        return s
    def walk(i, s):
        s = resolve(s); n = 0
        if isinstance(i, dict) and isinstance(s, dict) and 'properties' in s:
            for k, v in i.items():
                if k in s['properties']: n += walk(v, s['properties'][k])
                else: n += 1
        elif isinstance(i, list) and isinstance(s, dict) and 'items' in s:
            for v in i: n += walk(v, s['items'])
        return n
    return walk(inst, schema)

# ----------------------------------------------------------------------------------------------
# M05 plain-text renderer (reference). Input: verified payload object + verification result.
# ----------------------------------------------------------------------------------------------
BAD = re.compile('[\u0000-\u001f\u007f-\u009f؜‎‏  ‪-‮⁦-⁩﻿]')
def t(s): return BAD.sub('�', s)
def tenths(v, unit):  # integer v in thousandths -> one decimal, half-up
    x = (v + 50) // 100
    return f"{x // 10}.{x % 10}{unit}"
def toks(mtps): return tenths(mtps, ' tokens/s')
def gb(b): x = (b + 50_000_000) // 100_000_000; return f"{x // 10}.{x % 10} GB"
def secs(us):
    if us >= 1_000_000: x = (us + 50_000) // 100_000; return f"{x // 10}.{x % 10} s"
    return f"{(us + 500) // 1000} ms"
def dur(msv):
    s = (msv + 500) // 1000; m, s = divmod(s, 60)
    return f"{m} min {s} s" if m else f"{s} s"
def pct(num, den): return f"{(num * 1000 // den + 5) // 10}%"
def utc(msv): return datetime.datetime.fromtimestamp(msv // 60000 * 60, tz=datetime.timezone.utc).strftime('%Y-%m-%d %H:%M UTC')
def watts(mw): x = (mw + 50) // 100; return f"{x // 10}.{x % 10} W"
KNOWN_CLASS = {'phone', 'tablet', 'handheld', 'laptop', 'desktop', 'server', 'sbc'}
STORAGE = {'strongbox': 'StrongBox secure element', 'tee': 'trusted execution environment', 'secure-enclave': 'Secure Enclave',
           'tpm': 'TPM', 'os-keystore': 'operating-system keystore (software)', 'file': 'file on disk (software)', 'unknown': 'unknown'}
POWER = {'battery-current': 'battery current', 'battery-level': 'battery level change', 'rapl': 'CPU energy counters',
         'pmic': 'power-management chip', 'unavailable': ''}

def render(obj, vr):
    b = obj['body']; p = obj['presentation']; d = b['device']
    cls = d['class'] if d['class'] in KNOWN_CLASS else f"other ({t(d['class'])})"
    L = []
    L.append('ASOM CAPABILITY REPORT')
    L.append(f"Device: {t(d['model'])} by {t(d['vendor'])} ({cls})")
    L.append(f"Report {b['seq']}, signed {utc(p['issuedAtMs'])}, valid until {utc(p['expiresAtMs'])}")
    L.append(f"Signer: node {vr['fingerprint']}")
    L.append('')
    L.append('VERIFICATION (checked by this viewer, not stated by the device)')
    L.append('- Signature: valid. The report has not changed since this key signed it.')
    L.append({'PINNED': '- Signer key: matches the key you paired with.',
              'TOFU_MATCH': '- Signer key: same key as the first report you accepted from this source (not compared in person).',
              'TOFU_NEW': '- Signer key: seen for the first time. Compare the fingerprint above with the device before relying on it.'}[vr['pin']])
    if vr['tier'] == 'A2':
        L.append(f"- Key storage: {STORAGE[b['subject']['keyStorage']]} (attested by the platform).")
    else:
        L.append(f"- Key storage: {STORAGE[b['subject']['keyStorage']]} (self-reported, not attested).")
    L.append('- Freshness: signed for your request.' if vr.get('challengeMatched') else '- Freshness: not tied to a request of yours; it may be an older copy.')
    L.append('- Not proven: that the measurements were honest or typical, that the device model is true,')
    L.append('  or that the benchmark software was unmodified.')
    L.append('')
    L.append('DEVICE')
    L.append(f"- Chip: {t(d['soc']['name'])} by {t(d['soc']['vendor'])}, {d['soc']['cpu']['logicalCores']} CPU cores")
    L.append(f"- Memory: {gb(d['memory']['totalBytes'])}")
    acc = []
    for a in d['accelerators']:
        apis = ', '.join(t(x) for x in a['apis']) if a['apis'] else 'no API used'
        acc.append(f"{t(a['kind']).upper()} {t(a['name'])} ({apis})")
    L.append('- Accelerators: ' + ('; '.join(acc) if acc else 'none reported'))
    osl = f"- OS: {d['os']['family']} {t(d['os']['version'])}"
    if 'securityPatch' in d['os']: osl += f", security patch {d['os']['securityPatch']}"
    L.append(osl)
    L.append(f"- Cooling: {t(d['thermal']['cooling'])}; power: {'battery' if d['power']['battery'] else 'mains only'}")
    pr = b['producer']
    L.append(f"- Benchmark: {t(pr['harness']['id'])} {pr['harness']['version']}, method {pr['harness']['methodologyId']}, "
             f"{t(pr['engine']['name'])} {pr['engine']['commit']}")
    L.append('')
    L.append('RESULTS' if b['results'] else 'RESULTS: none current')
    for i, r in enumerate(b['results'], 1):
        L.append(f"{i}. {t(r['modelId'])} {r['quant']} ({gb(r['fileBytes'])} file) on {t(r['backend'])}, measured {utc(r['measuredAtMs'])}")
        dec = r['decode']
        s = f"   - Writes about {toks(dec[0]['milliTokPerSec']['p50'])} at {dec[0]['contextTokens']} tokens of context"
        for x in dec[1:]: s += f", {toks(x['milliTokPerSec']['p50'])} at {x['contextTokens']}"
        L.append(s + '.')
        pf = r['prefill'][0]
        L.append(f"   - Reads prompts at about {toks(pf['milliTokPerSec']['p50'])}; first token after {secs(pf['ttftMicros']['p50'])} for a {pf['promptTokens']}-token prompt.")
        su = r['sustained']
        if su['throttleOnsetMs'] is None:
            L.append(f"   - Under sustained load ({dur(su['durationMs'])}): no slowdown detected; settles at {toks(su['steadyMilliTokPerSec'])}.")
        else:
            L.append(f"   - Under sustained load ({dur(su['durationMs'])}): slows down after {dur(su['throttleOnsetMs'])}, settles at "
                     f"{toks(su['steadyMilliTokPerSec'])} ({pct(su['steadyMilliTokPerSec'], dec[0]['milliTokPerSec']['p50'])} of the short-context speed).")
        m = r['memory']; left = m['availableBeforeLoadBytes'] - m['peakProcessBytes']
        L.append(f"   - Memory: needs about {gb(m['peakProcessBytes'])}; {gb(m['availableBeforeLoadBytes'])} was free before loading, "
                 + (f"leaving about {gb(left)}." if left >= 0 else f"short by about {gb(-left)}."))
        pw = r['power']; c = r['conditions']
        batt = f", battery {(c['batteryStartPermille'] + 5) // 10}% at start" if c['batteryStartPermille'] is not None else ''
        state = 'charging' if c['charging'] else 'on battery'
        if pw['avgMilliW'] is None:
            L.append(f"   - Power: not measured; {state}{batt}.")
        else:
            L.append(f"   - Power: about {watts(pw['avgMilliW'])} (from {POWER[pw['method']]}); {state}{batt}.")
        runs = r['runs']
        L.append(f"   - Runs: {runs['completed']} of {runs['planned']} completed, {runs['discarded']} discarded."
                 + (' Few runs: treat as rough.' if runs['completed'] - runs['discarded'] < 3 else ''))
    if vr.get('unknownFields'):
        n = vr['unknownFields']
        L.append('')
        L.append(f"This report has {n} item{'s' if n != 1 else ''} from a newer format that this viewer does not show.")
    return '\n'.join(L) + '\n'

# ----------------------------------------------------------------------------------------------
# Public derivative (allow-list builder)
# ----------------------------------------------------------------------------------------------
RAM_CLASSES = [1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 2048]
def q2(x):
    if x < 100: return x
    p = 10 ** (len(str(x)) - 2)
    return (x + p // 2) // p * p
def ram_class(b):
    for c in RAM_CLASSES:
        if b <= c * 1024 ** 3: return c
    return RAM_CLASSES[-1]
def os_major(v):
    m = re.match(r'([0-9]+)', v); return int(m.group(1)) if m else 0

def public_derivative(verified_obj, selected=None):
    b = verified_obj['body']; d = b['device']
    out = {'schema': 'asom.bench-public/1',
           'device': {'class': d['class'], 'vendor': d['vendor'], 'model': d['model'], 'socName': d['soc']['name'],
                      'ramClassGiB': ram_class(d['memory']['totalBytes']), 'osFamily': d['os']['family'],
                      'osMajor': os_major(d['os']['version']), 'cooling': d['thermal']['cooling']},
           'harness': {'version': b['producer']['harness']['version'], 'methodologyId': b['producer']['harness']['methodologyId'],
                       'confVersion': b['producer']['harness']['confVersion']},
           'engine': {'name': b['producer']['engine']['name'], 'commit': b['producer']['engine']['commit']},
           'results': []}
    for i, r in enumerate(b['results']):
        if selected is not None and i not in selected: continue
        c = r['sustained']['curve']; n = len(c)
        idx = sorted(set((k * (n - 1) + 5) // 11 for k in range(12))) if n > 12 else list(range(n))
        pf = r['prefill'][0]; dc = r['decode'][0]
        out['results'].append({
            'modelId': r['modelId'], 'quant': r['quant'], 'fileSha256': r['fileSha256'], 'backend': r['backend'],
            'measuredMonth': datetime.datetime.fromtimestamp(r['measuredAtMs'] // 1000, tz=datetime.timezone.utc).strftime('%Y-%m'),
            'runsCompleted': r['runs']['completed'], 'charging': r['conditions']['charging'],
            'prefillPromptTokens': pf['promptTokens'], 'prefillMilliTokPerSec': q2(pf['milliTokPerSec']['p50']),
            'ttftMillis': q2((pf['ttftMicros']['p50'] + 500) // 1000),
            'decodeContextTokens': dc['contextTokens'], 'decodeMilliTokPerSec': q2(dc['milliTokPerSec']['p50']),
            'steadyMilliTokPerSec': q2(r['sustained']['steadyMilliTokPerSec']),
            'throttleOnsetSec': None if r['sustained']['throttleOnsetMs'] is None else q2((r['sustained']['throttleOnsetMs'] + 500) // 1000),
            'curve': [[q2((c[j][0] + 500) // 1000), q2(c[j][1])] for j in idx],
            'peakProcessMB': q2((r['memory']['peakProcessBytes'] + 500_000) // 1_000_000),
            'powerMethod': r['power']['method'],
            'powerMilliW': None if r['power']['avgMilliW'] is None else q2(r['power']['avgMilliW']),
        })
    return out

FORBIDDEN_PUBLIC = ['nodeId', 'spki', 'seq', 'challenge', 'issuedAtMs', 'expiresAtMs', 'measuredAtMs', 'platformIds',
                    'securityPatch', 'evidence', 'keyStorage', 'audience', 'signer', 'keyid']

# ----------------------------------------------------------------------------------------------
# Build everything
# ----------------------------------------------------------------------------------------------
def main():
    out_dir = HERE
    base = base_payload()
    errs = schema_ok(base, SCHEMA)
    assert not errs, errs
    payload = jcs_bytes(base)
    doc = make_container(payload, D1, SPKI1)
    nowv = ISSUED + 60000
    ctx_pinned = {'mode': 'pinned', 'pinnedSpki': b64s(SPKI1), 'expectedChallenge': b64u(CHALLENGE)}
    vr = verify(doc, ctx_pinned, nowv)
    obj = vr.pop('_obj')
    vr['fingerprint'] = display_fp(SPKI1); vr['challengeMatched'] = True
    text = render(obj, vr)
    pub = public_derivative(obj)
    pub_errs = schema_ok(pub, PUB_SCHEMA)
    assert not pub_errs, pub_errs
    pub_bytes = jcs_bytes(pub)
    for f in FORBIDDEN_PUBLIC: assert ('"' + f + '"') not in pub_bytes.decode(), f
    assert NODE1 not in pub_bytes.decode()

    # example files
    open(os.path.join(out_dir, 'example-container.json'), 'w').write(doc + '\n')
    open(os.path.join(out_dir, 'example-payload.pretty.json'), 'w').write(json.dumps(base, indent=2, ensure_ascii=False) + '\n')
    open(os.path.join(out_dir, 'example-payload.jcs.json'), 'wb').write(payload)
    open(os.path.join(out_dir, 'example-report.txt'), 'w').write(text)
    open(os.path.join(out_dir, 'example-public.jcs.json'), 'wb').write(pub_bytes)
    open(os.path.join(out_dir, 'example-public.pretty.json'), 'w').write(json.dumps(pub, indent=2) + '\n')
    open(os.path.join(out_dir, 'example-pae.bin'), 'wb').write(pae(PT_V1, payload))
    open(os.path.join(out_dir, 'example-sig.der'), 'wb').write(der_sig(b64_either(json.loads(doc)['dsse']['signatures'][0]['sig'])))
    open(os.path.join(out_dir, 'test-key1-spki.der'), 'wb').write(SPKI1)

    # ---------------- vectors ----------------
    V = []
    def add(vid, desc, docx, ctx, now_ms, expect_reject=None, extra=None):
        try:
            res = verify(docx, ctx, now_ms)
            res.pop('_obj')
            got = {'ok': {k: res[k] for k in ('pin', 'tier', 'seq', 'bodyDigest', 'unknownFields')}}
        except Reject as e:
            got = {'reject': e.code}
        if expect_reject is not None:
            assert got == {'reject': expect_reject}, (vid, got)
        else:
            assert 'ok' in got, (vid, got)
        v = {'id': vid, 'origin': 'generated', 'status': 'illustrative', 'description': desc, 'nowMs': now_ms,
             'input': {'document': docx, 'context': ctx}, 'expect': got}
        if extra: v.update(extra)
        V.append(v)

    def resign(o, d=D1, spki=SPKI1, **kw):
        return make_container(jcs_bytes(o), d, spki, **kw)

    add('M02-101', 'Challenge-bound presentation from a pinned mesh peer; accept, tier A1 (StrongBox claimed, no evidence).',
        doc, ctx_pinned, nowv)
    # TOFU / file mode
    o = copy.deepcopy(base); o['body']['audience'] = 'file'; del o['body']['device']['platformIds']; del o['body']['device']['os']['securityPatch']
    o['presentation'] = {'issuedAtMs': ISSUED, 'expiresAtMs': ISSUED + 30 * 86400000, 'challenge': None}
    file_doc = resign(o)
    add('M02-102', 'Unsolicited file-export form (audience file, challenge null, 30-day TTL) verified by a first-time subscriber (TOFU_NEW).',
        file_doc, {'mode': 'tofu', 'tofu': {}}, ISSUED + 86400000)
    add('M02-103', 'Same file verified by a subscriber that already stored this key under its keyid (TOFU_MATCH).',
        file_doc, {'mode': 'tofu', 'tofu': {NODE1: b64s(SPKI1)}}, ISSUED + 86400000)
    add('M02-104', 'Boundary: nowMs == expiresAtMs - 1 accepts.', doc, ctx_pinned, ISSUED + 600000 - 1)
    # high-S
    c = json.loads(doc); sig = b64_either(c['dsse']['signatures'][0]['sig'])
    r_, s_ = sig[:32], int.from_bytes(sig[32:], 'big')
    hi = r_ + (N - s_).to_bytes(32, 'big')
    c['dsse']['signatures'][0]['sig'] = b64s(hi)
    add('M02-105', 'High-S form of the same signature: accept (ECDSA malleability); bodyDigest is unchanged, so signature bytes are never an identifier.',
        jcs(c), ctx_pinned, nowv)
    # url-safe base64
    add('M02-106', 'Envelope using URL-safe unpadded base64 for payload and sig: DSSE requires verifiers to accept either alphabet.',
        make_container(payload, D1, SPKI1, b64=b64u), ctx_pinned, nowv)
    # unknown additive field, minor 1
    o = copy.deepcopy(base); o['schemaMinor'] = 1; o['body']['results'][0]['npuOffloadPermille'] = 0
    add('M02-107', 'schemaMinor 1 with one unknown additive field: accept; unknownFields = 1; the renderer must say one newer item is not shown.',
        resign(o), ctx_pinned, nowv)
    # rollback store present, higher seq -> ok
    add('M02-108', 'Rollback store holds seq 16 for this node/audience: seq 17 accepts.', doc,
        dict(ctx_pinned, rollback={NODE1 + '|own': {'seq': 16, 'bodyDigest': 'x' * 43}}), nowv)
    add('M02-109', 'Rollback store holds seq 17 with the SAME body digest (a re-presentation with a new challenge): accept.', doc,
        dict(ctx_pinned, rollback={NODE1 + '|own': {'seq': 17, 'bodyDigest': body_digest(base)}}), nowv)

    # ---------------- rejects ----------------
    tampered = payload.replace(b'"p50":18400', b'"p50":28400', 1)
    assert tampered != payload
    c = json.loads(doc); c['dsse']['payload'] = b64s(tampered)
    add('M03-101', 'One integer in the payload changed after signing (decode p50 18400 -> 28400).', jcs(c), ctx_pinned, nowv, 'SIGNATURE_INVALID')
    c = json.loads(doc); c['dsse']['signatures'][0]['sig'] = b64s(der_sig(sig))
    add('M03-102', 'DER-encoded signature where 64-octet raw r||s is required.', jcs(c), ctx_pinned, nowv, 'SIGNATURE_ENCODING')
    o2 = copy.deepcopy(base); o2['body']['subject']['nodeId'] = NODE2
    add('M03-103', 'Valid signature by a different key (key 2) presented to a subscriber that pinned key 1.',
        resign(o2, D2, SPKI2), ctx_pinned, nowv, 'KEY_NOT_PINNED')
    spaced = json.dumps(base, indent=1, ensure_ascii=False, sort_keys=True).encode('utf-8')
    add('M03-104', 'Payload validly signed but NOT in canonical (JCS) form (pretty-printed). Rejected to exclude parser-differential ambiguity.',
        make_container(spaced, D1, SPKI1), ctx_pinned, nowv, 'NON_CANONICAL')
    dup = payload.replace(b'{"audience":"own",', b'{"audience":"own","audience":"other",', 1)
    add('M03-105', 'Validly signed payload containing a duplicate member name (audience).',
        make_container(dup, D1, SPKI1), ctx_pinned, nowv, 'DUPLICATE_KEY')
    flt = payload.replace(b'"steadyMilliTokPerSec":11200', b'"steadyMilliTokPerSec":11200.5', 1)
    add('M03-106', 'Validly signed payload containing a non-integer number.', make_container(flt, D1, SPKI1), ctx_pinned, nowv, 'NON_INTEGER_NUMBER')
    add('M03-107', 'nowMs == expiresAtMs: expired.', doc, ctx_pinned, ISSUED + 600000, 'EXPIRED')
    add('M03-108', 'issuedAtMs more than 5 minutes in the future of the verifier clock.', doc, ctx_pinned, ISSUED - 300001, 'NOT_YET_VALID')
    add('M03-109', 'Challenge differs from the one this requester sent (replayed presentation).', doc,
        dict(ctx_pinned, expectedChallenge=b64u(hashlib.sha256(b'another challenge').digest())), nowv, 'NONCE_MISMATCH')
    o3 = copy.deepcopy(base); o3['presentation']['challenge'] = None; o3['presentation']['expiresAtMs'] = ISSUED + 86400000
    add('M03-110', 'Requester expected a challenge but the presentation carries none (an unsolicited copy offered as a fresh answer).',
        resign(o3), ctx_pinned, nowv, 'NONCE_MISMATCH')
    add('M03-111', 'Rollback store holds seq 18: seq 17 is a rollback.', doc,
        dict(ctx_pinned, rollback={NODE1 + '|own': {'seq': 18, 'bodyDigest': 'x' * 43}}), nowv, 'ROLLBACK')
    add('M03-112', 'Rollback store holds seq 17 with a DIFFERENT body digest: the signer issued two bodies under one seq.', doc,
        dict(ctx_pinned, rollback={NODE1 + '|own': {'seq': 17, 'bodyDigest': b64u(b'\x00' * 32)}}), nowv, 'EQUIVOCATION')
    c = json.loads(doc); c['dsse']['payloadType'] = PT_ROLLOVER
    add('M03-113', 'Envelope claims another asom payload type (key-rollover). PAE binds the type, and the verifier refuses non-manifest types before verifying.',
        jcs(c), ctx_pinned, nowv, 'PAYLOAD_TYPE_UNSUPPORTED')
    rol = jcs_bytes({'type': 'x'})
    add('M03-114', 'A different payload type correctly signed by the pinned key cannot be replayed as a manifest (cross-type confusion).',
        make_container(rol, D1, SPKI1, ptype=PT_ROLLOVER), ctx_pinned, nowv, 'PAYLOAD_TYPE_UNSUPPORTED')
    o4 = copy.deepcopy(base); o4['body']['subject']['nodeId'] = NODE2
    add('M03-115', 'Payload names another node as its subject but is signed by key 1.', resign(o4), ctx_pinned, nowv, 'SUBJECT_KEY_MISMATCH')
    o5 = copy.deepcopy(base); o5['presentation']['expiresAtMs'] = ISSUED + 600001
    add('M03-116', 'Challenge-bound presentation with a TTL over 10 minutes.', resign(o5), ctx_pinned, nowv, 'TTL_INVALID')
    add('M03-117', 'Envelope with two signature entries (asom profile requires exactly one).',
        make_container(payload, D1, SPKI1, extra_sigs=1), ctx_pinned, nowv, 'SIGNATURE_COUNT')
    c = json.loads(doc); c['dsse']['payloadType'] = 'application/vnd.asom.manifest.v2+json'
    add('M03-118', 'Unknown major version in the payload type.', jcs(c), ctx_pinned, nowv, 'SCHEMA_MAJOR_UNKNOWN')
    o6 = copy.deepcopy(base); o6['schema'] = 'asom.manifest/2'
    add('M03-119', 'Unknown major version inside the signed payload.', resign(o6), ctx_pinned, nowv, 'SCHEMA_MAJOR_UNKNOWN')
    o7 = copy.deepcopy(base); o7['body']['results'][0]['sustained']['steadyMilliTokPerSec'] = 99999
    add('M03-120', 'Internally inconsistent claim: steady-state throughput above every point of its own curve.', resign(o7), ctx_pinned, nowv, 'INCONSISTENT')
    o8 = copy.deepcopy(base); o8['body']['results'][0]['prefill'][0]['ttftMicros'] = {'p50': 900000}
    add('M03-121', 'Internally inconsistent claim: time to first token shorter than the prompt-processing time implied by its own prefill rate.',
        resign(o8), ctx_pinned, nowv, 'INCONSISTENT')
    add('M03-122', 'TOFU subscriber already stored a different key under this keyid; the file now presents key 2 with the same keyid hint.',
        make_container(jcs_bytes(o2), D2, SPKI2, keyid=NODE1), {'mode': 'tofu', 'tofu': {NODE1: b64s(SPKI1)}}, nowv, 'KEY_CHANGED')
    add('M03-123', 'Trailing data after the container JSON.', doc + ' {}', ctx_pinned, nowv, 'TRAILING_DATA')
    add('M03-124', 'requiredTier A2 but no platform evidence: tier A1 is insufficient.', doc, dict(ctx_pinned, requiredTier='A2'), nowv, 'TIER_INSUFFICIENT')
    c = json.loads(doc); c['dsse']['signatures'][0]['sig'] = c['dsse']['signatures'][0]['sig'][:-3] + 'B=='
    add('M03-125', 'Signature base64 whose final character carries non-zero unused bits (non-canonical base64); rejected before any signature check.',
        jcs(c), ctx_pinned, nowv, 'ENCODING')
    o9 = copy.deepcopy(base); o9['body']['device']['model'] = 'Example Phone 1‮1 enohP'
    add('M03-126', 'Bidi override character (U+202E) in a display string: schema rejects it so plain text cannot be visually spoofed.',
        resign(o9), ctx_pinned, nowv, 'SCHEMA_INVALID')
    add('M03-127', 'TOFU subscriber with no stored key and no signer.spki in the container.',
        make_container(payload, D1, SPKI1, include_signer=False), {'mode': 'tofu', 'tofu': {}}, nowv, 'KEY_NOT_PINNED')
    deep = copy.deepcopy(base); nest = 0
    for _ in range(14): nest = [nest]
    deep['body']['results'][0]['flags'] = nest
    add('M03-128', 'Validly signed payload nested deeper than 16 levels (checked by the strict parser before schema validation).',
        resign(deep), ctx_pinned, nowv, 'MALFORMED_JSON')

    # render + public vectors
    R = [{'id': 'M05-101', 'origin': 'generated', 'status': 'illustrative',
          'description': 'Plain text rendered from the verified payload of M02-101 plus the verification result (PINNED, A1, challenge matched).',
          'input': {'vector': 'M02-101', 'verification': {k: vr[k] for k in ('pin', 'tier', 'fingerprint', 'challengeMatched', 'unknownFields')}},
          'expect': {'ok': {'textUtf8': text, 'sha256': hashlib.sha256(text.encode()).hexdigest()}}}]
    o = copy.deepcopy(base); o['schemaMinor'] = 1; o['body']['results'][0]['npuOffloadPermille'] = 0
    vr7 = verify(resign(o), ctx_pinned, nowv); obj7 = vr7.pop('_obj'); vr7['fingerprint'] = display_fp(SPKI1); vr7['challengeMatched'] = True
    t7 = render(obj7, vr7)
    R.append({'id': 'M05-102', 'origin': 'generated', 'status': 'illustrative',
              'description': 'Rendering of M02-107: identical to M05-101 except the trailing newer-items line.',
              'input': {'vector': 'M02-107', 'verification': {k: vr7[k] for k in ('pin', 'tier', 'fingerprint', 'challengeMatched', 'unknownFields')}},
              'expect': {'ok': {'textUtf8': t7}}})
    PUBV = [{'id': 'M06-101', 'origin': 'generated', 'status': 'illustrative',
             'description': 'Public derivative of the verified M02-101 payload: allow-listed fields only, 2-significant-digit quantisation, month-granular date, RAM class.',
             'input': {'vector': 'M02-101', 'selectedResults': None},
             'expect': {'ok': {'jcsUtf8': pub_bytes.decode(), 'sha256': hashlib.sha256(pub_bytes).hexdigest()}}},
            {'id': 'M06-102', 'origin': 'hand', 'status': 'illustrative',
             'description': 'q2 quantisation table (law: q2(q2(x)) == q2(x); values below 100 unchanged).',
             'input': {'values': [0, 7, 99, 100, 149, 150, 994, 995, 11234, 18400, 212000, 2480000]},
             'expect': {'ok': {'q2': [q2(x) for x in [0, 7, 99, 100, 149, 150, 994, 995, 11234, 18400, 212000, 2480000]]}}}]
    for x in PUBV[1]['input']['values']: assert q2(q2(x)) == q2(x)

    files = {
        'M02-verify-accept.json': {'family': 'M02', 'confVersion': '0.1.0', 'specRefs': ['manifest.md#8'], 'vectors': [v for v in V if v['id'].startswith('M02')]},
        'M03-verify-reject.json': {'family': 'M03', 'confVersion': '0.1.0', 'specRefs': ['manifest.md#8'], 'vectors': [v for v in V if v['id'].startswith('M03')]},
        'M05-render.json': {'family': 'M05', 'confVersion': '0.1.0', 'specRefs': ['manifest.md#13'], 'vectors': R},
        'M06-public-derive.json': {'family': 'M06', 'confVersion': '0.1.0', 'specRefs': ['manifest.md#11'], 'vectors': PUBV},
    }
    for name, content in files.items():
        with open(os.path.join(out_dir, name), 'w') as f:
            json.dump(content, f, indent=1, ensure_ascii=False); f.write('\n')
    keys = {'TEST_ONLY': True, 'note': 'Private scalars are published deliberately: these keys must never be trusted anywhere.',
            'key1': {'d_hex': '%064x' % D1, 'spki_b64': b64s(SPKI1), 'nodeId': NODE1, 'nodeTag': node_tag(SPKI1), 'fingerprint': display_fp(SPKI1)},
            'key2': {'d_hex': '%064x' % D2, 'spki_b64': b64s(SPKI2), 'nodeId': NODE2, 'nodeTag': node_tag(SPKI2), 'fingerprint': display_fp(SPKI2)},
            'challenge1_b64u': b64u(CHALLENGE), 'rfc6979_selftest': 'passed (RFC 6979 A.2.5, P-256/SHA-256, "sample")'}
    with open(os.path.join(out_dir, 'TEST-ONLY-keys.json'), 'w') as f: json.dump(keys, f, indent=1); f.write('\n')
    print('vectors:', len(V), 'accept:', sum(1 for v in V if 'ok' in v['expect']), 'reject:', sum(1 for v in V if 'reject' in v['expect']))
    print('payload bytes:', len(payload), 'container bytes:', len(doc), 'public bytes:', len(pub_bytes))
    print('bodyDigest:', body_digest(base))
    print('nodeId key1:', NODE1, 'fp:', display_fp(SPKI1))

if __name__ == '__main__':
    main()
