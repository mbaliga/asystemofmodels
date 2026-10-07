#!/usr/bin/env python3
"""Writes helper-protocol/vectors/*.jsonl (protocol v1, see ../SCHEMA.md). Standard library only; deterministic.

The expectations below are written by hand as literals. This script is NOT a third codec: it never parses or canonicalises a
wire line, it only serialises the vector records themselves. The Kotlin and Swift lanes are what check the expectations.
Run from anywhere:  python3 desktop/packaging/macos/helper-protocol/tools/gen_vectors.py
"""
import base64
import hashlib
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "vectors"))
REASON = "asom: lending compute to your paired devices"
MAX_LINE = 131072

FIX_BLOB = b"ASOM-FIXTURE-SE-BLOB-V1"
FIX_SPKI = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200") + b"\x04" + bytes(range(1, 65))
assert len(FIX_SPKI) == 91
FIX_SIG = bytes([0x11] * 32 + [0x22] * 32)
FIX_DIGEST = hashlib.sha256(b"fixture-platform-uuid").digest()


def b64(b):
    return base64.b64encode(b).decode("ascii")


class Family:
    def __init__(self, prefix, filename):
        self.prefix, self.filename, self.rows = prefix, filename, []

    def add(self, kind, line=None, verdict="accept", code="-", canonical=None, note="", lineHex=None, **extra):
        n = len(self.rows) + 1
        v = {"id": f"HP-{self.prefix}-{n:03d}", "kind": kind}
        if lineHex is not None:
            v["lineHex"] = lineHex
        else:
            v["line"] = line
        v.update(extra)
        v["verdict"] = verdict
        v["code"] = code
        if verdict == "accept":
            v["canonical"] = line if canonical is None else canonical
        if note:
            v["note"] = note
        self.rows.append(v)

    def write(self):
        path = os.path.join(OUT, self.filename)
        with open(path, "w", encoding="ascii", newline="\n") as f:
            for r in self.rows:
                f.write(json.dumps(r, ensure_ascii=True, separators=(",", ":")) + "\n")
        return path, len(self.rows)


families = []


def fam(prefix, filename):
    f = Family(prefix, filename)
    families.append(f)
    return f


# ---------------------------------------------------------------------------------------------------------------- requests
R = fam("REQ", "requests-accept.jsonl")
ra = lambda line, canonical=None, note="", **x: R.add("request", line, "accept", "-", canonical, note, **x)

ra('{"op":"hello","v":1}')
ra('{"v":1,"op":"hello"}', '{"op":"hello","v":1}', "member order is not significant on input")
ra('{ "op" : "hello" ,\t"v" : 1 }', '{"op":"hello","v":1}', "insignificant whitespace is SP and TAB")
ra('{"op":"hello","v":1,"id":0}', '{"op":"hello","id":0,"v":1}', "id sits directly after op in the canonical form")
ra('{"id":9007199254740991,"v":2,"op":"hello"}', '{"op":"hello","id":9007199254740991,"v":2}', "the codec accepts any v; the helper answers UNSUPPORTED_VERSION")
ra('{"op":"hello","v":0}')
ra('{"op":"hello","v":9007199254740991}')
ra('{"op":"se.create"}')
ra('{"op":"se.create","id":5}')
ra('{"op":"se.sign","blob":"AAEC","data":"aGVsbG8="}')
ra('{"data":"","blob":"AAEC","op":"se.sign"}', '{"op":"se.sign","blob":"AAEC","data":""}', "empty data is a valid zero-length message")
ra('{"op":"se.sign","blob":"AAEC","data":"{ZEROS}"}', note="data of exactly 65536 bytes", zeros=65536)
ra('{"op":"se.sign","blob":"{ZEROS}","data":"AA=="}', note="blob of exactly 4096 bytes", zeros=4096)
ra('{"op":"se.selftest","blob":"AAEC"}')
for op in ("power.get", "thermal.get", "presence.get", "gpu.get", "mem.get", "assert.release", "platform.uuid", "paths.get"):
    ra('{"op":"%s"}' % op)
ra('{"op":"assert.hold","reason":"%s"}' % REASON)
ra('{"reason":"%s","id":3,"op":"assert.hold"}' % REASON, '{"op":"assert.hold","id":3,"reason":"%s"}' % REASON)
ra('{"op":"sleep.ack","token":0}')
ra('{"op":"sleep.ack","token":9007199254740991,"id":8}', '{"op":"sleep.ack","id":8,"token":9007199254740991}')
ra('{"op":"svc.status","kind":"agent"}')
ra('{"op":"svc.register","kind":"daemon"}')
ra('{"op":"svc.unregister","kind":"agent","id":1}', '{"op":"svc.unregister","id":1,"kind":"agent"}')
ra('{"op":"backup.exclude","path":"/Users/a b/Library/Group Containers/TEAM.xyz.mdhv.asom"}')
ra('{"op":"backup.exclude","path":"/tmp/\\u00e9"}', '{"op":"backup.exclude","path":"/tmp/\u00e9"}', "escaped BMP character; canonical writes it raw")
ra('{"op":"backup.exclude","path":"\\/tmp\\/x"}', '{"op":"backup.exclude","path":"/tmp/x"}', "escaped solidus")
ra('{"op":"backup.exclude","path":"/tmp/\\ud83d\\ude00"}', '{"op":"backup.exclude","path":"/tmp/\U0001F600"}', "surrogate pair escape; canonical writes the astral character raw")
ra('{"op":"backup.exclude","path":"/tmp/\U0001F600"}', note="a raw four-byte UTF-8 character")
ra('{"op":"backup.exclude","path":"/%s"}' % ("A" * 1023), note="path of exactly 1024 bytes")
ra('{"op":"backup.exclude","path":"/%s%s"}' % ("\u00e9" * 511, "A"), note="path of exactly 1024 UTF-8 bytes made of 2-byte characters")
ra('{"op":"backup.exclude","path":"/tmp/a\\"b\\\\c"}', note="escaped quote and backslash stay escaped in the canonical form")

# ---------------------------------------------------------------------------------------------------------- request rejects
X = fam("REQX", "requests-reject.jsonl")
rx = lambda code, line=None, note="", lineHex=None, **x: X.add("request", line, "reject", code, None, note, lineHex=lineHex, **x)

# MALFORMED_JSON
rx("MALFORMED_JSON", "", "empty line")
rx("MALFORMED_JSON", "not json")
rx("MALFORMED_JSON", '{"op":"hello","v":1', "unterminated object")
rx("MALFORMED_JSON", '{"op":"hello","v":1}}', "trailing content")
rx("MALFORMED_JSON", '{"op":"hello","v":1} x', "trailing content after whitespace")
rx("MALFORMED_JSON", '{"op":"hello","v":1.0}', "fraction")
rx("MALFORMED_JSON", '{"op":"hello","v":1e0}', "exponent")
rx("MALFORMED_JSON", '{"op":"hello","v":01}', "leading zero")
rx("MALFORMED_JSON", '{"op":"hello","v":-0}', "negative zero")
rx("MALFORMED_JSON", '{"op":"hello","v":-}', "bare minus")
rx("MALFORMED_JSON", '{"op":"hello","v":9007199254740992}', "2^53 is out of the integer profile")
rx("MALFORMED_JSON", '{"op":"hello","v":12345678901234567}', "17 digits")
rx("MALFORMED_JSON", '{"op":"hello","v":-9007199254740992}', "below -(2^53-1)")
rx("MALFORMED_JSON", '{"op":"hello","op":"hello","v":1}', "duplicate member name")
rx("MALFORMED_JSON", '{"op":"hello","v":1,"v":1}', "duplicate member name, same value")
rx("MALFORMED_JSON", '{"op":"se.create","x":"\\ud800"}', "lone high surrogate escape (also an unknown field: the JSON layer wins)")
rx("MALFORMED_JSON", '{"op":"se.create","x":"\\udc00"}', "lone low surrogate escape")
rx("MALFORMED_JSON", '{"op":"se.create","x":"\\ude00\\ud83d"}', "reversed surrogate pair")
rx("MALFORMED_JSON", '{"op":"se.create","x":"\\ud83dA"}', "high surrogate not followed by an escape")
rx("MALFORMED_JSON", '{"op":"se.create","x":"\\u\u0660\u0660\u0664\u0661"}', "non-ASCII digits in a \\u escape (Character.digit would accept them)")
rx("MALFORMED_JSON", '{"op":"se.create","x":"\\u00g0"}', "non-hex digit in a \\u escape")
rx("MALFORMED_JSON", '{"op":"se.create","x":"\\u00"}', "short \\u escape")
rx("MALFORMED_JSON", '{"op":"backup.exclude","path":"/a\\xb"}', "unknown escape")
rx("MALFORMED_JSON", '{"op":"backup.exclude","path":"/a\tb"}', "raw TAB inside a string")
rx("MALFORMED_JSON", '{"op":"backup.exclude","path":"/a\u0001b"}', "raw control character inside a string")
rx("MALFORMED_JSON", '{"op":"hello","v":1}\r', "CR at the end of a line (CRLF is refused)")
rx("MALFORMED_JSON", '{"op":"hello",\r"v":1}', "CR between tokens")
rx("MALFORMED_JSON", '{"op":"hello",\n"v":1}', "LF inside a line cannot occur on the wire")
rx("MALFORMED_JSON", '\ufeff{"op":"hello","v":1}', "leading BOM")
rx("MALFORMED_JSON", "{'op':'hello','v':1}", "single quotes")
rx("MALFORMED_JSON", '{"op":"hello","v":TRUE}', "upper-case literal")
rx("MALFORMED_JSON", '{"op":"hello","v":1,}', "trailing comma")
rx("MALFORMED_JSON", '{"op":"hello" "v":1}', "missing comma")
rx("MALFORMED_JSON", '{op:"hello","v":1}', "unquoted name")
rx("MALFORMED_JSON", '{"op":"se.create","x":' + "[" * 33 + "]" * 33 + "}", "nesting of 33 levels")
rx("MALFORMED_JSON", "", "invalid UTF-8: a lone 0xff byte in a string", lineHex=b'{"op":"hello","v":1,"x":"\xff"}'.hex())
rx("MALFORMED_JSON", "", "invalid UTF-8: overlong encoding of '/'", lineHex=b'{"op":"hello","v":1,"x":"\xc0\xaf"}'.hex())
rx("MALFORMED_JSON", "", "invalid UTF-8: an encoded surrogate", lineHex=b'{"op":"hello","v":1,"x":"\xed\xa0\x80"}'.hex())
rx("MALFORMED_JSON", "", "invalid UTF-8: truncated sequence", lineHex=b'{"op":"hello","v":1,"x":"\xe2\x82"}'.hex())
rx("MALFORMED_JSON", "", "invalid UTF-8: above U+10FFFF", lineHex=b'{"op":"hello","v":1,"x":"\xf4\x90\x80\x80"}'.hex())
rx("MALFORMED_JSON", "", "invalid UTF-8: a continuation byte with no lead", lineHex=b'{"op":"hello","v":1,"x":"\x80"}'.hex())
# LINE_TOO_LONG (boundary: 131072 passes the framing step and fails later, 131073 is refused there)
tmpl_len = len('{"op":"backup.exclude","path":"/{PAD}"}') - len("{PAD}")
rx("BAD_FIELD", '{"op":"backup.exclude","path":"/{PAD}"}', "a line of exactly 131072 bytes passes framing; the path is then over 1024 bytes", pad=MAX_LINE - tmpl_len)
rx("LINE_TOO_LONG", '{"op":"backup.exclude","path":"/{PAD}"}', "a line of 131073 bytes", pad=MAX_LINE - tmpl_len + 1)
# NOT_OBJECT
for v in ("[]", '"hello"', "1", "null", "true", '[{"op":"hello","v":1}]'):
    rx("NOT_OBJECT", v)
# discriminator
rx("MISSING_FIELD", "{}", "no op")
rx("MISSING_FIELD", '{"v":1}', "no op, only a parameter")
rx("BAD_FIELD", '{"op":1}', "op is not a string")
rx("BAD_FIELD", '{"op":null}')
rx("BAD_FIELD", '{"op":["hello"],"v":1}')
rx("UNKNOWN_OP", '{"op":"nope"}')
rx("UNKNOWN_OP", '{"op":"HELLO","v":1}', "op names are case sensitive")
rx("UNKNOWN_OP", '{"op":""}')
rx("UNKNOWN_OP", '{"op":"se.create ","x":1}', "unknown op is decided before unknown fields")
rx("UNKNOWN_OP", '{"op":"shutdown"}', "there is no shutdown op: EOF on stdin is the shutdown")
# UNKNOWN_FIELD (before MISSING_FIELD and BAD_FIELD)
rx("UNKNOWN_FIELD", '{"op":"hello","v":1,"x":1}')
rx("UNKNOWN_FIELD", '{"op":"se.create","blob":"AAEC"}')
rx("UNKNOWN_FIELD", '{"op":"power.get","id":1,"extra":true}')
rx("UNKNOWN_FIELD", '{"op":"hello","x":1}', "unknown field wins over the missing v")
rx("UNKNOWN_FIELD", '{"op":"hello","v":"1","x":1}', "unknown field wins over the bad v")
rx("UNKNOWN_FIELD", '{"op":"se.create","x":' + "[" * 32 + "]" * 32 + "}", "32 levels of nesting is legal JSON; the field is unknown")
rx("UNKNOWN_FIELD", '{"op":"hello","v":1,"ok":true}', "a response discriminator is not a request field")
rx("UNKNOWN_FIELD", '{"op":"hello","v":1,"ev":"wake"}')
rx("UNKNOWN_FIELD", '{"op":"se.create","\u00e9":1,"e\u0301":2}', "two names that are canonically equivalent but differ in bytes are NOT duplicates (Swift String equality would say they are)")
# MISSING_FIELD (before BAD_FIELD)
rx("MISSING_FIELD", '{"op":"hello"}')
rx("MISSING_FIELD", '{"op":"se.sign","blob":"AAEC"}')
rx("MISSING_FIELD", '{"op":"se.sign","data":"AA=="}')
rx("MISSING_FIELD", '{"op":"se.selftest"}')
rx("MISSING_FIELD", '{"op":"sleep.ack"}')
rx("MISSING_FIELD", '{"op":"svc.status"}')
rx("MISSING_FIELD", '{"op":"backup.exclude"}')
rx("MISSING_FIELD", '{"op":"assert.hold"}')
rx("MISSING_FIELD", '{"op":"se.sign","blob":"!!!!"}', "missing data wins over the bad blob")
# BAD_FIELD
rx("BAD_FIELD", '{"op":"hello","v":"1"}')
rx("BAD_FIELD", '{"op":"hello","v":null}')
rx("BAD_FIELD", '{"op":"hello","v":true}')
rx("BAD_FIELD", '{"op":"hello","v":-1}')
rx("BAD_FIELD", '{"op":"hello","v":[1]}')
rx("BAD_FIELD", '{"op":"hello","v":1,"id":-1}')
rx("BAD_FIELD", '{"op":"hello","v":1,"id":"1"}')
rx("BAD_FIELD", '{"op":"hello","v":1,"id":true}')
rx("BAD_FIELD", '{"op":"hello","v":1,"id":null}')
rx("BAD_FIELD", '{"op":"se.sign","blob":"!!!!","data":"AA=="}', "not base64")
rx("BAD_FIELD", '{"op":"se.sign","blob":"AAE","data":"AA=="}', "missing padding")
rx("BAD_FIELD", '{"op":"se.sign","blob":"AAF=","data":"AA=="}', "non-zero trailing bits are not canonical")
rx("BAD_FIELD", '{"op":"se.sign","blob":"AAEC","data":"AA"}', "unpadded data")
rx("BAD_FIELD", '{"op":"se.sign","blob":"AAEC","data":"AA==="}', "too much padding")
rx("BAD_FIELD", '{"op":"se.sign","blob":"AAEC","data":"A=A="}', "padding in the middle")
rx("BAD_FIELD", '{"op":"se.sign","blob":"AAEC","data":"aGVs bG8="}', "whitespace inside base64")
rx("BAD_FIELD", '{"op":"se.sign","blob":"AAEC","data":"aG\\nVsbG8="}', "escaped newline inside base64")
rx("BAD_FIELD", '{"op":"se.sign","blob":"-_-_","data":"AA=="}', "URL-safe alphabet")
rx("BAD_FIELD", '{"op":"se.sign","blob":"AAEC","data":1}', "data is not a string")
rx("BAD_FIELD", '{"op":"se.sign","blob":"","data":"AA=="}', "empty blob (at least one byte)")
rx("BAD_FIELD", '{"op":"se.sign","blob":"{ZEROS}","data":"AA=="}', "blob of 4097 bytes", zeros=4097)
rx("BAD_FIELD", '{"op":"se.sign","blob":"AAEC","data":"{ZEROS}"}', "data of 65537 bytes", zeros=65537)
rx("BAD_FIELD", '{"op":"se.selftest","blob":""}')
rx("BAD_FIELD", '{"op":"se.selftest","blob":"{ZEROS}"}', "blob of 4097 bytes", zeros=4097)
rx("BAD_FIELD", '{"op":"se.selftest","blob":null}')
rx("BAD_FIELD", '{"op":"assert.hold","reason":"other"}', "the reason is fixed")
rx("BAD_FIELD", '{"op":"assert.hold","reason":""}')
rx("BAD_FIELD", '{"op":"assert.hold","reason":"%s "}' % REASON, "one trailing space")
rx("BAD_FIELD", '{"op":"assert.hold","reason":1}')
rx("BAD_FIELD", '{"op":"assert.hold","reason":null}')
rx("BAD_FIELD", '{"op":"sleep.ack","token":-1}')
rx("BAD_FIELD", '{"op":"sleep.ack","token":"7"}')
rx("BAD_FIELD", '{"op":"sleep.ack","token":null}')
rx("MALFORMED_JSON", '{"op":"sleep.ack","token":1.5}', "a float is a JSON-profile violation")
rx("BAD_FIELD", '{"op":"svc.status","kind":"Agent"}', "enum values are case sensitive")
rx("BAD_FIELD", '{"op":"svc.register","kind":"user"}')
rx("BAD_FIELD", '{"op":"svc.unregister","kind":1}')
rx("BAD_FIELD", '{"op":"svc.status","kind":null}')
rx("BAD_FIELD", '{"op":"backup.exclude","path":""}')
rx("BAD_FIELD", '{"op":"backup.exclude","path":"relative/path"}')
rx("BAD_FIELD", '{"op":"backup.exclude","path":"~/x"}')
rx("BAD_FIELD", '{"op":"backup.exclude","path":"/a\\u0000b"}', "NUL")
rx("BAD_FIELD", '{"op":"backup.exclude","path":"/a\\u0001b"}', "escaped control character")
rx("BAD_FIELD", '{"op":"backup.exclude","path":"/a\\nb"}', "escaped newline")
rx("BAD_FIELD", '{"op":"backup.exclude","path":"/a\\u007fb"}', "DEL (U+007F) is a control character of the text rule")
rx("BAD_FIELD", '{"op":"backup.exclude","path":"/%s"}' % ("A" * 1024), "path of 1025 bytes")
rx("BAD_FIELD", '{"op":"backup.exclude","path":"/%s"}' % ("\u00e9" * 512), "path of 1025 UTF-8 bytes but only 513 characters: limits are on bytes")
rx("BAD_FIELD", '{"op":"backup.exclude","path":7}')

# ------------------------------------------------------------------------------------------------------------------ responses
S = fam("RSP", "responses-accept.jsonl")
sa = lambda op, line, canonical=None, note="": S.add("response", line, "accept", "-", canonical, note, op=op)

sa("hello", '{"ok":true,"helper":"0.1.0","macos":"27.0.1","arch":"arm64","se":false,"model":"Mac14,3"}')
sa("hello", '{"ok":true,"id":1,"helper":"1.2.3-pre.1","macos":"15.0","arch":"x86_64","se":true,"model":"MacBookPro18,3"}')
sa("hello", '{"model":"Mac14,3","se":true,"arch":"arm64","macos":"27.0.1","helper":"0.1.0","id":2,"ok":true}',
   '{"ok":true,"id":2,"helper":"0.1.0","macos":"27.0.1","arch":"arm64","se":true,"model":"Mac14,3"}', "member order is not significant on input")
sa("se.create", '{"ok":true,"blob":"%s","spki":"%s"}' % (b64(FIX_BLOB), b64(FIX_SPKI)))
sa("se.create", '{"ok":true,"id":3,"blob":"AAEC","spki":"%s"}' % b64(FIX_SPKI))
sa("se.sign", '{"ok":true,"sig":"%s"}' % b64(FIX_SIG))
sa("se.selftest", '{"ok":true,"verified":true}')
sa("se.selftest", '{"ok":true,"verified":false}', note="verified false is a well-formed answer; the node treats it as a failed self-test")
sa("power.get", '{"ok":true,"source":"ac","charging":true,"batteryPermille":870,"lowPower":false}')
sa("power.get", '{"ok":true,"source":"battery","charging":false,"batteryPermille":0,"lowPower":true}')
sa("power.get", '{"ok":true,"source":"ac","charging":false,"batteryPermille":null,"lowPower":false}', note="a desktop Mac: no battery")
sa("power.get", '{"ok":true,"source":"battery","charging":false,"batteryPermille":1000,"lowPower":false}')
for st in ("nominal", "fair", "serious", "critical"):
    sa("thermal.get", '{"ok":true,"state":"%s"}' % st)
sa("presence.get", '{"ok":true,"hidIdleMs":725000,"screenLocked":false,"consoleUserIsSelf":true}')
sa("presence.get", '{"ok":true,"hidIdleMs":null,"screenLocked":null,"consoleUserIsSelf":null}', note="every input unknown")
sa("presence.get", '{"ok":true,"hidIdleMs":0,"screenLocked":true,"consoleUserIsSelf":false}')
sa("gpu.get", '{"ok":true,"deviceUtilPermille":137}')
sa("gpu.get", '{"ok":true,"deviceUtilPermille":1000}')
sa("mem.get", '{"ok":true,"physicalBytes":17179869184,"gpuRecommendedMaxWorkingSetBytes":11453251584}')
sa("mem.get", '{"ok":true,"physicalBytes":1,"gpuRecommendedMaxWorkingSetBytes":null}')
for op in ("assert.hold", "assert.release", "sleep.ack", "backup.exclude"):
    sa(op, '{"ok":true}')
sa("assert.hold", '{"ok":true,"id":9}')
for op in ("svc.status", "svc.register", "svc.unregister"):
    for st in ("notRegistered", "enabled", "requiresApproval", "notFound"):
        sa(op, '{"ok":true,"status":"%s"}' % st)
sa("platform.uuid", '{"ok":true,"digest":"%s"}' % b64(FIX_DIGEST))
sa("paths.get", '{"ok":true,"userTempDir":"/var/folders/zz/fixture/T/"}')
sa("paths.get", '{"ok":true,"userTempDir":"/var/folders/z\\u00e9/T/"}', '{"ok":true,"userTempDir":"/var/folders/z\u00e9/T/"}')
for op in ("hello", "se.create", "se.sign", "power.get", "svc.register", "paths.get"):
    for code in ("BAD_REQUEST", "UNKNOWN_OP", "UNSUPPORTED_VERSION", "UNAVAILABLE", "FAILED"):
        sa(op, '{"ok":false,"code":"%s","message":"fixed reason"}' % code)
sa("se.sign", '{"ok":false,"id":4,"code":"FAILED","message":"key blob not usable"}')
sa("se.sign", '{"message":"","code":"FAILED","ok":false}', '{"ok":false,"code":"FAILED","message":""}', "an empty message is legal")
sa("gpu.get", '{"ok":false,"code":"UNAVAILABLE","message":"no GPU counter"}')
sa("hello", '{"ok":false,"code":"BAD_REQUEST","message":"%s"}' % ("m" * 200), note="a message of exactly 200 bytes")

SX = fam("RSPX", "responses-reject.jsonl")
sx = lambda op, code, line, note="", lineHex=None, **x: SX.add("response", line, "reject", code, None, note, lineHex=lineHex, op=op, **x)

sx("hello", "MALFORMED_JSON", "")
sx("hello", "MALFORMED_JSON", '{"ok":true,')
sx("hello", "MALFORMED_JSON", '{"ok":true,"ok":true}', "duplicate")
sx("power.get", "MALFORMED_JSON", '{"ok":true,"source":"ac","charging":true,"batteryPermille":870.0,"lowPower":false}', "a float")
sx("hello", "MALFORMED_JSON", '{"ok":true}\r')
sx("hello", "NOT_OBJECT", "[]")
sx("hello", "NOT_OBJECT", "true")
sx("hello", "MISSING_FIELD", "{}", "no ok")
sx("hello", "MISSING_FIELD", '{"helper":"0.1.0"}', "no ok, so the field set is unknown")
sx("hello", "BAD_FIELD", '{"ok":"true"}')
sx("hello", "BAD_FIELD", '{"ok":1}')
sx("hello", "BAD_FIELD", '{"ok":null}')
sx("hello", "UNKNOWN_FIELD", '{"ok":true,"helper":"0.1.0","macos":"27.0.1","arch":"arm64","se":true,"model":"Mac14,3","extra":1}')
sx("assert.hold", "UNKNOWN_FIELD", '{"ok":true,"code":"FAILED"}', "an ok response carries no code")
sx("se.create", "UNKNOWN_FIELD", '{"ok":false,"code":"FAILED","message":"x","blob":"AAEC"}', "an error carries no result field")
sx("se.create", "UNKNOWN_FIELD", '{"ok":true,"blob":"AAEC","spki":"%s","ev":"wake"}' % b64(FIX_SPKI))
sx("hello", "MISSING_FIELD", '{"ok":true,"helper":"0.1.0","macos":"27.0.1","arch":"arm64","se":true}', "model missing")
sx("power.get", "MISSING_FIELD", '{"ok":true,"source":"ac","charging":true,"lowPower":false}', "a nullable field must still be present")
sx("presence.get", "MISSING_FIELD", '{"ok":true,"hidIdleMs":1,"screenLocked":false}')
sx("se.sign", "MISSING_FIELD", '{"ok":true}')
sx("svc.status", "MISSING_FIELD", '{"ok":true}')
sx("hello", "MISSING_FIELD", '{"ok":false,"code":"FAILED"}', "an error needs its message")
sx("hello", "MISSING_FIELD", '{"ok":false,"message":"x"}', "an error needs its code")
sx("hello", "BAD_FIELD", '{"ok":false,"code":"NOPE","message":"x"}', "unknown error code")
sx("hello", "BAD_FIELD", '{"ok":false,"code":"failed","message":"x"}')
sx("hello", "BAD_FIELD", '{"ok":false,"code":"FAILED","message":5}')
sx("hello", "BAD_FIELD", '{"ok":false,"code":"FAILED","message":null}')
sx("hello", "BAD_FIELD", '{"ok":false,"code":"FAILED","message":"a\\u0001b"}', "control character in a message")
sx("hello", "BAD_FIELD", '{"ok":false,"code":"FAILED","message":"%s"}' % ("m" * 201), "message of 201 bytes")
sx("hello", "BAD_FIELD", '{"ok":false,"code":"FAILED","message":"%s"}' % ("\u00e9" * 101), "message of 202 bytes made of 101 characters: limits are on bytes")
sx("hello", "BAD_FIELD", '{"ok":true,"id":-1,"helper":"0.1.0","macos":"27.0.1","arch":"arm64","se":true,"model":"Mac14,3"}')
sx("hello", "BAD_FIELD", '{"ok":true,"helper":"0.1","macos":"27.0.1","arch":"arm64","se":true,"model":"Mac14,3"}', "not a semver")
sx("hello", "BAD_FIELD", '{"ok":true,"helper":"1.2.3-","macos":"27.0.1","arch":"arm64","se":true,"model":"Mac14,3"}')
sx("hello", "BAD_FIELD", '{"ok":true,"helper":"1.2.3","macos":"27","arch":"arm64","se":true,"model":"Mac14,3"}')
sx("hello", "BAD_FIELD", '{"ok":true,"helper":"1.2.3","macos":"27.0.1.4","arch":"arm64","se":true,"model":"Mac14,3"}')
sx("hello", "BAD_FIELD", '{"ok":true,"helper":"1.2.3","macos":"27.0.1","arch":"ppc","se":true,"model":"Mac14,3"}')
sx("hello", "BAD_FIELD", '{"ok":true,"helper":"1.2.3","macos":"27.0.1","arch":"arm64","se":"yes","model":"Mac14,3"}')
sx("hello", "BAD_FIELD", '{"ok":true,"helper":"1.2.3","macos":"27.0.1","arch":"arm64","se":null,"model":"Mac14,3"}')
sx("hello", "BAD_FIELD", '{"ok":true,"helper":"1.2.3","macos":"27.0.1","arch":"arm64","se":true,"model":""}')
sx("hello", "BAD_FIELD", '{"ok":true,"helper":"1.2.3","macos":"27.0.1","arch":"arm64","se":true,"model":"Mac\\u00e914"}', "model is printable ASCII")
sx("se.create", "BAD_FIELD", '{"ok":true,"blob":"AAEC","spki":"%s"}' % b64(FIX_SPKI[:90]), "spki of 90 bytes")
sx("se.create", "BAD_FIELD", '{"ok":true,"blob":"AAEC","spki":"%s"}' % b64(FIX_SPKI + b"\x00"), "spki of 92 bytes")
sx("se.create", "BAD_FIELD", '{"ok":true,"blob":"","spki":"%s"}' % b64(FIX_SPKI), "empty blob")
sx("se.create", "BAD_FIELD", '{"ok":true,"blob":"AAE","spki":"%s"}' % b64(FIX_SPKI), "unpadded blob")
sx("se.sign", "BAD_FIELD", '{"ok":true,"sig":"%s"}' % b64(FIX_SIG[:63]), "sig of 63 bytes")
sx("se.sign", "BAD_FIELD", '{"ok":true,"sig":"%s"}' % b64(FIX_SIG + b"\x00"), "sig of 65 bytes (DER-ish sizes are refused)")
sx("se.selftest", "BAD_FIELD", '{"ok":true,"verified":"true"}')
sx("se.selftest", "BAD_FIELD", '{"ok":true,"verified":null}')
sx("power.get", "BAD_FIELD", '{"ok":true,"source":"UPS","charging":true,"batteryPermille":870,"lowPower":false}')
sx("power.get", "BAD_FIELD", '{"ok":true,"source":"ac","charging":true,"batteryPermille":1001,"lowPower":false}')
sx("power.get", "BAD_FIELD", '{"ok":true,"source":"ac","charging":true,"batteryPermille":-1,"lowPower":false}')
sx("power.get", "BAD_FIELD", '{"ok":true,"source":"ac","charging":"true","batteryPermille":870,"lowPower":false}')
sx("power.get", "BAD_FIELD", '{"ok":true,"source":"ac","charging":true,"batteryPermille":"870","lowPower":false}')
sx("power.get", "BAD_FIELD", '{"ok":true,"source":"ac","charging":true,"batteryPermille":870,"lowPower":null}')
sx("thermal.get", "BAD_FIELD", '{"ok":true,"state":"hot"}')
sx("thermal.get", "BAD_FIELD", '{"ok":true,"state":"Nominal"}')
sx("thermal.get", "BAD_FIELD", '{"ok":true,"state":null}')
sx("presence.get", "BAD_FIELD", '{"ok":true,"hidIdleMs":-1,"screenLocked":false,"consoleUserIsSelf":true}')
sx("presence.get", "BAD_FIELD", '{"ok":true,"hidIdleMs":"1","screenLocked":false,"consoleUserIsSelf":true}')
sx("presence.get", "BAD_FIELD", '{"ok":true,"hidIdleMs":1,"screenLocked":"no","consoleUserIsSelf":true}')
sx("gpu.get", "BAD_FIELD", '{"ok":true,"deviceUtilPermille":1001}')
sx("gpu.get", "BAD_FIELD", '{"ok":true,"deviceUtilPermille":-1}')
sx("gpu.get", "BAD_FIELD", '{"ok":true,"deviceUtilPermille":null}')
sx("mem.get", "BAD_FIELD", '{"ok":true,"physicalBytes":0,"gpuRecommendedMaxWorkingSetBytes":1}', "physical memory is at least 1")
sx("mem.get", "BAD_FIELD", '{"ok":true,"physicalBytes":1,"gpuRecommendedMaxWorkingSetBytes":-1}')
sx("mem.get", "BAD_FIELD", '{"ok":true,"physicalBytes":null,"gpuRecommendedMaxWorkingSetBytes":null}')
sx("svc.status", "BAD_FIELD", '{"ok":true,"status":"registered"}')
sx("svc.register", "BAD_FIELD", '{"ok":true,"status":null}')
sx("platform.uuid", "BAD_FIELD", '{"ok":true,"digest":"%s"}' % b64(FIX_DIGEST[:31]), "digest of 31 bytes")
sx("platform.uuid", "BAD_FIELD", '{"ok":true,"digest":"%s"}' % b64(FIX_DIGEST + b"\x00"), "digest of 33 bytes")
sx("paths.get", "BAD_FIELD", '{"ok":true,"userTempDir":"relative"}')
sx("paths.get", "BAD_FIELD", '{"ok":true,"userTempDir":""}')
sx("paths.get", "BAD_FIELD", '{"ok":true,"userTempDir":"/a\\u0000b"}')
sx("paths.get", "BAD_FIELD", '{"ok":true,"userTempDir":"/%s"}' % ("A" * 1024), "1025 bytes")
sx("assert.hold", "UNKNOWN_FIELD", '{"ok":true,"held":true}')
sx("hello", "MALFORMED_JSON", "", "invalid UTF-8 in a message", lineHex=b'{"ok":false,"code":"FAILED","message":"\xff"}'.hex())

# ----------------------------------------------------------------------------------------------------------------------- events
E = fam("EV", "events-accept.jsonl")
ea = lambda line, canonical=None, note="": E.add("event", line, "accept", "-", canonical, note)
ea('{"ev":"power","source":"ac","charging":true,"batteryPermille":870,"lowPower":false}')
ea('{"ev":"power","source":"battery","charging":false,"batteryPermille":null,"lowPower":true}')
ea('{"lowPower":false,"batteryPermille":5,"charging":false,"source":"battery","ev":"power"}',
   '{"ev":"power","source":"battery","charging":false,"batteryPermille":5,"lowPower":false}', "member order is not significant on input")
for st in ("nominal", "fair", "serious", "critical"):
    ea('{"ev":"thermal","state":"%s"}' % st)
ea('{"ev":"sleep.will","token":7}')
ea('{"ev":"sleep.will","token":0}')
ea('{"ev":"sleep.will","token":9007199254740991}')
ea('{"ev":"wake"}')
ea('{ "ev" : "wake" }', '{"ev":"wake"}')

EX = fam("EVX", "events-reject.jsonl")
ex = lambda code, line, note="", lineHex=None: EX.add("event", line, "reject", code, None, note, lineHex=lineHex)
ex("MALFORMED_JSON", "")
ex("MALFORMED_JSON", '{"ev":"wake"')
ex("MALFORMED_JSON", '{"ev":"wake","ev":"wake"}')
ex("MALFORMED_JSON", '{"ev":"sleep.will","token":7.0}')
ex("MALFORMED_JSON", '{"ev":"wake"}\r')
ex("NOT_OBJECT", "[]")
ex("MISSING_FIELD", "{}")
ex("MISSING_FIELD", '{"token":7}', "no ev")
ex("BAD_FIELD", '{"ev":1}')
ex("BAD_FIELD", '{"ev":null}')
ex("UNKNOWN_OP", '{"ev":"sleep.did"}', "unknown event name")
ex("UNKNOWN_OP", '{"ev":"Wake"}')
ex("UNKNOWN_OP", '{"ev":""}')
ex("UNKNOWN_FIELD", '{"ev":"wake","token":1}')
ex("UNKNOWN_FIELD", '{"ev":"sleep.will","token":7,"id":1}', "events carry no id")
ex("UNKNOWN_FIELD", '{"ev":"thermal","state":"fair","ok":true}', "an event is not a response")
ex("UNKNOWN_FIELD", '{"ev":"power","source":"ac","charging":true,"batteryPermille":870,"lowPower":false,"x":1}')
ex("MISSING_FIELD", '{"ev":"sleep.will"}')
ex("MISSING_FIELD", '{"ev":"thermal"}')
ex("MISSING_FIELD", '{"ev":"power","source":"ac","charging":true,"lowPower":false}')
ex("BAD_FIELD", '{"ev":"sleep.will","token":-1}')
ex("BAD_FIELD", '{"ev":"sleep.will","token":"7"}')
ex("BAD_FIELD", '{"ev":"sleep.will","token":null}')
ex("BAD_FIELD", '{"ev":"thermal","state":"hot"}')
ex("BAD_FIELD", '{"ev":"power","source":"grid","charging":true,"batteryPermille":870,"lowPower":false}')
ex("BAD_FIELD", '{"ev":"power","source":"ac","charging":true,"batteryPermille":1001,"lowPower":false}')
ex("BAD_FIELD", '{"ev":"power","source":"ac","charging":1,"batteryPermille":870,"lowPower":false}')
ex("MALFORMED_JSON", "", "invalid UTF-8", lineHex=b'{"ev":"wa\xffke"}'.hex())

# --------------------------------------------------------------------------------------------------------------- exchanges
XCH = fam("XCH", "exchanges.jsonl")


def xch(request, response, req_verdict="accept", req_code="-", note="", **extra):
    n = len(XCH.rows) + 1
    v = {"id": f"HP-XCH-{n:03d}", "kind": "exchange", "request": request}
    v.update(extra)
    v.update({"requestVerdict": req_verdict, "requestCode": req_code, "response": response, "verdict": "accept", "code": "-"})
    if note:
        v["note"] = note
    XCH.rows.append(v)


ok_hello = lambda i="": '{"ok":true%s,"helper":"0.1.0","macos":"27.0.1","arch":"arm64","se":true,"model":"Mac14,3"}' % i
xch('{"op":"hello","v":1}', ok_hello())
xch('{"op":"hello","id":1,"v":1}', ok_hello(',"id":1'))
xch('{"op":"hello","id":2,"v":2}', '{"ok":false,"id":2,"code":"UNSUPPORTED_VERSION","message":"protocol version 1 only"}')
xch('{"op":"hello","v":0}', '{"ok":false,"code":"UNSUPPORTED_VERSION","message":"protocol version 1 only"}')
xch('{"op":"se.create","id":3}', '{"ok":true,"id":3,"blob":"%s","spki":"%s"}' % (b64(FIX_BLOB), b64(FIX_SPKI)))
xch('{"op":"se.sign","id":4,"blob":"%s","data":"aGVsbG8="}' % b64(FIX_BLOB), '{"ok":true,"id":4,"sig":"%s"}' % b64(FIX_SIG))
xch('{"op":"se.sign","blob":"%s","data":""}' % b64(FIX_BLOB), '{"ok":true,"sig":"%s"}' % b64(FIX_SIG))
xch('{"op":"se.sign","blob":"AAEC","data":"aGVsbG8="}', '{"ok":false,"code":"FAILED","message":"key blob not usable"}')
xch('{"op":"se.selftest","blob":"%s"}' % b64(FIX_BLOB), '{"ok":true,"verified":true}')
xch('{"op":"se.selftest","id":5,"blob":"AAEC"}', '{"ok":false,"id":5,"code":"FAILED","message":"key blob not usable"}')
xch('{"op":"power.get"}', '{"ok":true,"source":"ac","charging":true,"batteryPermille":870,"lowPower":false}')
xch('{"op":"thermal.get"}', '{"ok":true,"state":"nominal"}')
xch('{"op":"presence.get","id":6}', '{"ok":true,"id":6,"hidIdleMs":725000,"screenLocked":false,"consoleUserIsSelf":true}')
xch('{"op":"gpu.get"}', '{"ok":true,"deviceUtilPermille":137}')
xch('{"op":"mem.get"}', '{"ok":true,"physicalBytes":17179869184,"gpuRecommendedMaxWorkingSetBytes":11453251584}')
xch('{"op":"assert.hold","reason":"%s"}' % REASON, '{"ok":true}')
xch('{"op":"assert.hold","id":7,"reason":"%s"}' % REASON, '{"ok":true,"id":7}', note="a second hold is ok and creates no second assertion")
xch('{"op":"assert.release"}', '{"ok":true}')
xch('{"op":"assert.release","id":8}', '{"ok":true,"id":8}', note="release while nothing is held is ok")
xch('{"op":"sleep.ack","token":7}', '{"ok":true}')
xch('{"op":"sleep.ack","id":9,"token":8}', '{"ok":false,"id":9,"code":"BAD_REQUEST","message":"unknown token"}')
xch('{"op":"svc.status","kind":"agent"}', '{"ok":true,"status":"notRegistered"}')
xch('{"op":"svc.status","kind":"daemon"}', '{"ok":true,"status":"notFound"}')
xch('{"op":"svc.register","kind":"agent"}', '{"ok":true,"status":"requiresApproval"}')
xch('{"op":"svc.register","kind":"daemon"}', '{"ok":true,"status":"notFound"}')
xch('{"op":"svc.unregister","kind":"agent"}', '{"ok":true,"status":"notRegistered"}')
xch('{"op":"svc.unregister","id":10,"kind":"daemon"}', '{"ok":true,"id":10,"status":"notRegistered"}')
xch('{"op":"backup.exclude","path":"/tmp/asom"}', '{"ok":true}')
xch('{"op":"backup.exclude","path":"/Users/x/asom"}', '{"ok":false,"code":"FAILED","message":"cannot exclude"}')
xch('{"op":"platform.uuid"}', '{"ok":true,"digest":"%s"}' % b64(FIX_DIGEST))
xch('{"op":"paths.get"}', '{"ok":true,"userTempDir":"/var/folders/zz/fixture/T/"}')
# the same requests without an id: what the typed client sends (the Kotlin FakeHelper replays these exact lines)
xch('{"op":"se.create"}', '{"ok":true,"blob":"%s","spki":"%s"}' % (b64(FIX_BLOB), b64(FIX_SPKI)))
xch('{"op":"se.selftest","blob":"AAEC"}', '{"ok":false,"code":"FAILED","message":"key blob not usable"}')
xch('{"op":"presence.get"}', '{"ok":true,"hidIdleMs":725000,"screenLocked":false,"consoleUserIsSelf":true}')
xch('{"op":"sleep.ack","token":8}', '{"ok":false,"code":"BAD_REQUEST","message":"unknown token"}')
xch('{"op":"svc.unregister","kind":"daemon"}', '{"ok":true,"status":"notRegistered"}')
xch('{"op":"hello","v":2}', '{"ok":false,"code":"UNSUPPORTED_VERSION","message":"protocol version 1 only"}')
xch('{"op":"nope","id":11}', '{"ok":false,"id":11,"code":"UNKNOWN_OP","message":"UNKNOWN_OP"}', "reject", "UNKNOWN_OP", "the id is echoed when the object parsed")
xch('{"op":"hello","id":12,"v":"1"}', '{"ok":false,"id":12,"code":"BAD_REQUEST","message":"BAD_FIELD"}', "reject", "BAD_FIELD")
xch('{"op":"hello","id":13}', '{"ok":false,"id":13,"code":"BAD_REQUEST","message":"MISSING_FIELD"}', "reject", "MISSING_FIELD")
xch('{"op":"hello","v":1,"x":1,"id":14}', '{"ok":false,"id":14,"code":"BAD_REQUEST","message":"UNKNOWN_FIELD"}', "reject", "UNKNOWN_FIELD")
xch('{"op":"hello","v":1,"id":-1}', '{"ok":false,"code":"BAD_REQUEST","message":"BAD_FIELD"}', "reject", "BAD_FIELD", "an invalid id is not echoed")
xch('{"op":"hello","v":1,"id":"1"}', '{"ok":false,"code":"BAD_REQUEST","message":"BAD_FIELD"}', "reject", "BAD_FIELD", "an id of the wrong type is not echoed")
xch('not json', '{"ok":false,"code":"BAD_REQUEST","message":"MALFORMED_JSON"}', "reject", "MALFORMED_JSON")
xch('', '{"ok":false,"code":"BAD_REQUEST","message":"MALFORMED_JSON"}', "reject", "MALFORMED_JSON", "an empty line is answered too")
xch('[1]', '{"ok":false,"code":"BAD_REQUEST","message":"NOT_OBJECT"}', "reject", "NOT_OBJECT")
xch('{"op":"backup.exclude","id":15,"path":"relative"}', '{"ok":false,"id":15,"code":"BAD_REQUEST","message":"BAD_FIELD"}', "reject", "BAD_FIELD")
xch('{"op":"se.sign","id":16,"blob":"{ZEROS}","data":""}', '{"ok":false,"id":16,"code":"BAD_REQUEST","message":"BAD_FIELD"}', "reject", "BAD_FIELD", "blob of 4097 bytes", zeros=4097)
xch('{"op":"backup.exclude","path":"/{PAD}"}', '{"ok":false,"code":"BAD_REQUEST","message":"LINE_TOO_LONG"}', "reject", "LINE_TOO_LONG", "a line of 131073 bytes", pad=MAX_LINE - tmpl_len + 1)

# --------------------------------------------------------------------------------------------------------------------- write
if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for f in families:
        path, n = f.write()
        total += n
        print(f"{os.path.relpath(path)}: {n} vectors")
    print(f"total: {total} vectors")
