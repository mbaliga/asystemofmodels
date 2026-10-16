#!/usr/bin/env bash
# Regenerates vectors and re-runs the independent checks. Output -> crosscheck.out
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; cd "$HERE"
PY="${PY:-../../venv/bin/python}"            # needs jsonschema + cryptography>=43
WORK="$(mktemp -d)"
{
echo "# Cross-check transcript (design session 2026-09-29). Real output of the commands shown."
echo '$ python gen_manifest_vectors.py'; "$PY" gen_manifest_vectors.py
"$PY" - "$WORK" <<'EOF'
import json, sys, os
for f in ['M02-verify-accept.json','M03-verify-reject.json']:
    for v in json.load(open(f))['vectors']:
        open(os.path.join(sys.argv[1], v['id'] + '.json'), 'w').write(v['input']['document'])
EOF
echo; echo '$ openssl dgst -sha256 -verify <(openssl pkey -pubin -inform DER -in test-key1-spki.der) -signature example-sig.der example-pae.bin'
openssl dgst -sha256 -verify <(openssl pkey -pubin -inform DER -in test-key1-spki.der) -signature example-sig.der example-pae.bin; openssl version
echo; echo '$ python <cryptography cross-check: RFC 6979 nonce equality + verify all accept vectors>'
"$PY" - <<'EOF'
import json, base64, importlib.util, cryptography
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature, decode_dss_signature
spec=importlib.util.spec_from_file_location('g','gen_manifest_vectors.py'); g=importlib.util.module_from_spec(spec); spec.loader.exec_module(g)
priv = ec.derive_private_key(g.D1, ec.SECP256R1())
pae = open('example-pae.bin','rb').read()
r,s = decode_dss_signature(priv.sign(pae, ec.ECDSA(hashes.SHA256(), deterministic_signing=True)))
print('cryptography', cryptography.__version__, 'RFC 6979 nonce/signature equal to reference:', g.sign_raw(g.D1, pae, low_s=False) == r.to_bytes(32,'big')+s.to_bytes(32,'big'))
ok=0; acc=json.load(open('M02-verify-accept.json'))['vectors']
for v in acc:
    c=json.loads(v['input']['document'])
    dec=lambda x: base64.b64decode(x.replace('-','+').replace('_','/')+'='*(-len(x)%4))
    P=dec(c['dsse']['payload']); S=dec(c['dsse']['signatures'][0]['sig']); pt=c['dsse']['payloadType'].encode()
    key=serialization.load_der_public_key(base64.b64decode(v['input']['context'].get('pinnedSpki') or c['signer']['spki']))
    key.verify(encode_dss_signature(int.from_bytes(S[:32],'big'),int.from_bytes(S[32:],'big')), b'DSSEv1 %d %s %d %s'%(len(pt),pt,len(P),P), ec.ECDSA(hashes.SHA256())); ok+=1
print('accept vectors verified by cryptography:', ok, '/', len(acc))
EOF
echo; echo '$ java -cp . VerifyDsse <per-vector documents>   (independent JCA SHA256withECDSAinP1363Format over an independently built PAE)'
javac -d "$WORK" VerifyDsse.java 2>/dev/null && java -cp "$WORK" VerifyDsse "$WORK" 2>&1 | grep -v JAVA_TOOL
} > crosscheck.out 2>&1
rm -rf "$WORK" __pycache__
