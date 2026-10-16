#!/usr/bin/env python3
"""xcheck for the M01 family (LAB_SPEC 4.2, 4.9): a second, hand-written implementation of the strict JSON profile,
the JCS integer profile and strict base64, in Python 3 standard library only. It does not use the `json` module to
parse (that module accepts NaN, big integers and duplicate names unless every hook is set), and it does not import
the design session's generator.

Usage:
  python3 lab/json/tools/xcheck_m01.py [lab/conformance]            check every M01 vector against this implementation
  python3 lab/json/tools/xcheck_m01.py [lab/conformance] --corpus F  also check a corpus written by the Kotlin
                                                                     PropertyTest: lines of `<input hex>\\t<verdict>`

Prints `xcheck M01: <n> agree, <d> disagree` (exit 1 on any disagreement). It was written in the same session as the
Kotlin module, so its agreement shows consistency, never independence: it does NOT clear the `oracle: self` tag
(LAB_SPEC 4.10, R9).
"""
import os
import re
import sys

sys.setrecursionlimit(20000)

MAX_SAFE = 2 ** 53 - 1
MAX_DEPTH = 16
CODES = ["MALFORMED_JSON", "INVALID_UNICODE", "NON_INTEGER_NUMBER", "NUMBER_RANGE", "DUPLICATE_KEY", "TRAILING_DATA"]


class _Syntax(Exception):
    pass


class _Scan:
    def __init__(self, data):
        self.b = data
        self.i = 0
        self.lone = self.nonint = self.range = self.dup = self.deep = False

    def ws(self):
        while self.i < len(self.b) and self.b[self.i] in (0x20, 0x09, 0x0A, 0x0D):
            self.i += 1

    def peek(self):
        if self.i >= len(self.b):
            raise _Syntax("end")
        return self.b[self.i]

    def value(self, level):
        self.ws()
        c = self.peek()
        if c == 0x7B:
            return self.obj(level + 1)
        if c == 0x5B:
            return self.arr(level + 1)
        if c == 0x22:
            return self.string()
        if c == 0x2D or 0x30 <= c <= 0x39:
            return self.number()
        if chr(c).isascii() and chr(c).isalpha():
            return self.word(0)
        raise _Syntax("unexpected byte")

    def enter(self, level):
        if level > MAX_DEPTH:
            self.deep = True

    def obj(self, level):
        self.enter(level)
        self.i += 1
        out = {}
        self.ws()
        if self.peek() == 0x7D:
            self.i += 1
            return out
        while True:
            self.ws()
            if self.peek() != 0x22:
                raise _Syntax("member name")
            k = self.string()
            self.ws()
            if self.peek() != 0x3A:
                raise _Syntax("colon")
            self.i += 1
            v = self.value(level)
            if k in out:
                self.dup = True
            else:
                out[k] = v
            self.ws()
            c = self.peek()
            self.i += 1
            if c == 0x2C:
                continue
            if c == 0x7D:
                return out
            raise _Syntax("object separator")

    def arr(self, level):
        self.enter(level)
        self.i += 1
        out = []
        self.ws()
        if self.peek() == 0x5D:
            self.i += 1
            return out
        while True:
            out.append(self.value(level))
            self.ws()
            c = self.peek()
            self.i += 1
            if c == 0x2C:
                continue
            if c == 0x5D:
                return out
            raise _Syntax("array separator")

    def word(self, skip):
        m = re.compile(rb"[A-Za-z]+").match(self.b, self.i + skip)
        w = m.group(0).decode("ascii")
        if skip == 0 and w in ("true", "false", "null"):
            self.i = m.end()
            return {"true": True, "false": False, "null": None}[w]
        if w in ("NaN", "Infinity"):
            self.nonint = True
            self.i = m.end()
            return 0
        raise _Syntax("word")

    def number(self):
        if self.b[self.i] == 0x2D and self.i + 1 < len(self.b) and chr(self.b[self.i + 1]).isascii() and chr(self.b[self.i + 1]).isalpha():
            return self.word(1)
        m = re.compile(rb"[0-9+\-.eE]*").match(self.b, self.i)
        lex = m.group(0).decode("ascii")
        self.i = m.end()
        if lex == "-0" or not re.fullmatch(r"-?(0|[1-9][0-9]*)", lex):
            self.nonint = True
            return 0
        v = int(lex)
        if abs(v) > MAX_SAFE:
            self.range = True
            return 0
        return v

    def string(self):
        self.i += 1
        parts = []
        while True:
            start = self.i
            while self.i < len(self.b) and self.b[self.i] not in (0x22, 0x5C):
                if self.b[self.i] < 0x20:
                    raise _Syntax("control character")
                self.i += 1
            if self.i >= len(self.b):
                raise _Syntax("unterminated")
            parts.append(self.b[start:self.i].decode("utf-8", errors="replace"))
            if self.b[self.i] == 0x22:
                self.i += 1
                return "".join(parts)
            self.i += 1
            if self.i >= len(self.b):
                raise _Syntax("escape")
            e = self.b[self.i]
            simple = {0x22: '"', 0x5C: "\\", 0x2F: "/", 0x62: "\b", 0x66: "\f", 0x6E: "\n", 0x72: "\r", 0x74: "\t"}
            if e in simple:
                parts.append(simple[e])
                self.i += 1
            elif e == 0x75:
                u = self.hex4(self.i + 1)
                if u is None:
                    raise _Syntax("\\u")
                self.i += 5
                if 0xD800 <= u <= 0xDBFF:
                    nxt = self.hex4(self.i + 2) if self.b[self.i:self.i + 2] == b"\\u" else None
                    if nxt is not None and 0xDC00 <= nxt <= 0xDFFF:
                        parts.append(chr(0x10000 + ((u - 0xD800) << 10) + (nxt - 0xDC00)))
                        self.i += 6
                    else:
                        self.lone = True
                        parts.append("�")
                elif 0xDC00 <= u <= 0xDFFF:
                    self.lone = True
                    parts.append("�")
                else:
                    parts.append(chr(u))
            else:
                raise _Syntax("escape")

    def hex4(self, at):
        h = self.b[at:at + 4]
        if len(h) != 4 or not re.fullmatch(rb"[0-9a-fA-F]{4}", h):
            return None
        return int(h, 16)


def parse(data):
    """(`ok`, value) or (`reject`, CODE): the first row of the LAB_SPEC 4.2 table whose condition holds anywhere in the input."""
    if data[:3] == b"\xef\xbb\xbf":
        return "reject", "MALFORMED_JSON"
    s = _Scan(data)
    try:
        v = s.value(0)
    except (_Syntax, RecursionError):
        return "reject", "MALFORMED_JSON"
    s.ws()
    trailing = s.i < len(data)
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return "reject", "INVALID_UNICODE"
    for flag, code in ((s.lone, "INVALID_UNICODE"), (s.nonint, "NON_INTEGER_NUMBER"), (s.range, "NUMBER_RANGE"), (s.dup, "DUPLICATE_KEY"), (s.deep, "MALFORMED_JSON"), (trailing, "TRAILING_DATA")):
        if flag:
            return "reject", code
    return "ok", v


def _jstr(s):
    out = ['"']
    for ch in s:
        c = ord(ch)
        if ch == '"':
            out.append('\\"')
        elif ch == "\\":
            out.append("\\\\")
        elif c == 8:
            out.append("\\b")
        elif c == 9:
            out.append("\\t")
        elif c == 10:
            out.append("\\n")
        elif c == 12:
            out.append("\\f")
        elif c == 13:
            out.append("\\r")
        elif c < 0x20:
            out.append("\\u%04x" % c)
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def jcs(v):
    if v is True:
        return "true"
    if v is False:
        return "false"
    if v is None:
        return "null"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, str):
        return _jstr(v)
    if isinstance(v, list):
        return "[" + ",".join(jcs(x) for x in v) + "]"
    keys = sorted(v, key=lambda k: k.encode("utf-16-be"))
    return "{" + ",".join(_jstr(k) + ":" + jcs(v[k]) for k in keys) + "}"


def canonicalize(data):
    kind, x = parse(data)
    if kind == "reject":
        return "reject " + x
    return "ok " + jcs(x).encode("utf-8").hex()


_B64_STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"


def b64(text, either):
    """Bytes or None (ENCODING). `either`: standard or URL-safe, padded or not; otherwise `b64url` with no padding."""
    m = re.fullmatch(r"([A-Za-z0-9+/]*|[A-Za-z0-9_-]*)(={0,2})", text)
    if not m:
        return None
    core, pad = m.groups()
    if pad and (not either or len(text) % 4 != 0):
        return None
    if not either and ("+" in core or "/" in core):
        return None
    if len(core) % 4 == 1:
        return None
    vals = [_B64_STD.index(c) if c not in "-_" else (62 if c == "-" else 63) for c in core]
    bits = "".join(format(v, "06b") for v in vals)
    used = len(bits) - len(bits) % 8
    if any(ch != "0" for ch in bits[used:]):
        return None
    return bytes(int(bits[k:k + 8], 2) for k in range(0, used, 8))


def _input_bytes(inp):
    if "inputHex" in inp:
        return bytes.fromhex(inp["inputHex"])
    return inp["inputText"].encode("utf-8")


def verdict_for(vec):
    inp = vec["input"]
    kind = inp["kind"]
    if kind == "canonicalize":
        return canonicalize(_input_bytes(inp))
    if kind in ("base64Either", "base64UrlNoPad"):
        raw = b64(inp["text"], kind == "base64Either")
        return "reject ENCODING" if raw is None else "ok " + raw.hex()
    raise SystemExit("unknown M01 kind " + kind)


def expected_verdict(vec):
    import json
    e = vec["expect"]
    if "reject" in e:
        return "reject " + e["reject"]
    ok = e["ok"]
    if "utf8Hex" in ok:
        assert ok["text"].encode("utf-8").hex() == ok["utf8Hex"], vec["id"] + ": text and utf8Hex disagree"
        return "ok " + ok["utf8Hex"]
    return "ok " + ok["hex"]


def load_m01(root):
    import json
    path = os.path.join(root, "json", "M01-jcs.json")
    if not os.path.isfile(path):
        return None
    with open(path, "rb") as f:
        return json.loads(f.read().decode("utf-8"))


def check_m01(root, corpus=None):
    doc = load_m01(root)
    if doc is None:
        return None
    agree = disagree = 0
    for vec in doc["vectors"]:
        if vec.get("oracle") != "self":
            disagree += 1
            print("  DISAGREE %s: oracle tag is not 'self'" % vec["id"], file=sys.stderr)
        got = verdict_for(vec)
        want = expected_verdict(vec)
        if got == want:
            agree += 1
        else:
            disagree += 1
            print("  DISAGREE %s: python=%s vector=%s" % (vec["id"], got[:120], want[:120]), file=sys.stderr)
    seed = os.path.join(root, "..", "..", "docs", "design", "mesh", "conformance-examples", "seed-vectors.json")
    if os.path.isfile(seed):
        import json
        s = json.load(open(seed, encoding="utf-8"))["M01"]
        by_id = {v["id"]: v for v in doc["vectors"]}
        want = "ok " + s["expect_utf8_hex"]
        got = verdict_for(by_id["M01-001"])
        if got == want and s["input_json_escaped"] == by_id["M01-001"]["input"]["inputText"]:
            agree += 1
        else:
            disagree += 1
            print("  DISAGREE M01-001 vs the design-session seed", file=sys.stderr)
    if corpus:
        n = 0
        for line in open(corpus, encoding="utf-8"):
            line = line.rstrip("\n")
            if not line:
                continue
            hx, want = line.split("\t")
            got = canonicalize(bytes.fromhex(hx))
            n += 1
            if got == want:
                agree += 1
            else:
                disagree += 1
                if disagree < 20:
                    print("  DISAGREE corpus input %s: python=%s kotlin=%s" % (hx[:80], got[:80], want[:80]), file=sys.stderr)
        print("xcheck M01 corpus: %d inputs" % n)
    return agree, disagree


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    corpus = None
    if "--corpus" in argv:
        corpus = argv[argv.index("--corpus") + 1]
        args = [a for a in args if a != corpus]
    root = args[0] if args else "lab/conformance"
    res = check_m01(root, corpus)
    if res is None:
        print("xcheck M01: absent")
        return 0
    print("xcheck M01: %d agree, %d disagree" % res)
    return 1 if res[1] else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
