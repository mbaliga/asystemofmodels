#!/usr/bin/env python3
"""Second reader for the record tap (lab/mesh-proto, track proto-tls). Standard library only.

`TapCrossCheckDumpTest` writes, per JDK, the ClientHello and ServerHello messages the Kotlin record tap read (hand-built ones and real JSSE ones),
together with the tap's own reading of each one. This script parses the same bytes with its own code (RFC 8446 sections 4.1.2 and 4.1.3, written
from the RFC with `struct`, sharing nothing with RecordTap.kt) and compares: session id, cipher suites, extension types in order, supported_versions,
signature_algorithms, supported_groups, ALPN names, SNI names, the ServerHello's selected version, pre_shared_key, early_data and HelloRetryRequest.

It reads every `*.json` under the directory given (default: lab/mesh-proto/build/tls-tap-xcheck) and prints one line per file and a total:

    tap-xcheck jdk17.json: 29 hellos, 0 disagree
    tap-xcheck: 58 hellos, 0 disagree, kinds: client 40 server 18, psk 7, early_data 1, sni 4

It exits 1 on any disagreement, on a file that holds no hello, or when the corpus has no pre_shared_key hello, no early_data hello or no ServerHello
(a cross-check that never saw one proves nothing). Same author as the Kotlin tap, so the oracle is still `self` (LAB_SPEC 4.10).
"""
import glob
import json
import os
import struct
import sys

HRR_RANDOM = bytes.fromhex("cf21ad74e59a6111be1d8c021e65b891c2a211167abb8c5e079e09e2c8a8339c")


def u16s(b):
    if len(b) % 2:
        raise ValueError("odd length list")
    return [x for (x,) in struct.iter_unpack(">H", b)]


def parse(raw, client):
    """Returns the same fields as the tap, from the handshake message bytes (type, u24 length, body)."""
    if raw[0] != (1 if client else 2):
        raise ValueError("wrong handshake type")
    n = int.from_bytes(raw[1:4], "big")
    if n != len(raw) - 4:
        raise ValueError("length mismatch")
    b = raw[4:]
    pos = 2 + 32  # legacy_version, random
    random = b[2:34]
    sid_len = b[pos]
    pos += 1
    sid = b[pos:pos + sid_len]
    pos += sid_len
    suites = []
    if client:
        (cs_len,) = struct.unpack_from(">H", b, pos)
        pos += 2
        suites = u16s(b[pos:pos + cs_len])
        pos += cs_len
        pos += 1 + b[pos]  # compression methods
    else:
        suites = [struct.unpack_from(">H", b, pos)[0]]
        pos += 3  # cipher suite, compression method
    (ext_len,) = struct.unpack_from(">H", b, pos)
    pos += 2
    end = pos + ext_len
    if end != len(b):
        raise ValueError("extensions do not end the message")
    out = {"sessionId": sid.hex(), "suites": suites, "extensions": [], "versions": [], "sigs": [], "groups": [], "alpn": [], "sni": [],
           "selected": None, "psk": False, "early": False, "hrr": (not client) and random == HRR_RANDOM}
    while pos < end:
        t, l = struct.unpack_from(">HH", b, pos)
        d = b[pos + 4:pos + 4 + l]
        pos += 4 + l
        out["extensions"].append(t)
        if t == 43:
            if client:
                out["versions"] = u16s(d[1:1 + d[0]])
            else:
                out["selected"] = struct.unpack(">H", d)[0]
        elif t == 13:
            out["sigs"] = u16s(d[2:2 + struct.unpack(">H", d[:2])[0]])
        elif t == 10:
            out["groups"] = u16s(d[2:2 + struct.unpack(">H", d[:2])[0]])
        elif t == 16:
            q = 2
            while q < len(d):
                out["alpn"].append(d[q + 1:q + 1 + d[q]].decode("latin-1"))
                q += 1 + d[q]
        elif t == 0 and len(d) >= 5:
            q = 2
            while q + 3 <= len(d):
                (nl,) = struct.unpack_from(">H", d, q + 1)
                out["sni"].append(d[q + 3:q + 3 + nl].decode("latin-1"))
                q += 3 + nl
        elif t == 41:
            out["psk"] = True
        elif t == 42:
            out["early"] = True
    return out


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "build", "tls-tap-xcheck")
    files = sorted(glob.glob(os.path.join(base, "*.json")))
    if not files:
        print("tap-xcheck: no dump found in %s (run :mesh-proto:test first)" % base)
        return 1
    total = bad = psk = early = sni = servers = clients = 0
    for f in files:
        items = json.load(open(f))
        n = d = 0
        for it in items:
            n += 1
            raw = bytes.fromhex(it["hex"])
            try:
                mine = parse(raw, it["client"])
            except Exception as e:  # a parse failure is a disagreement, not a crash
                print("  DISAGREE %s %s: my parser failed: %s" % (os.path.basename(f), it["source"], e))
                d += 1
                continue
            theirs = it["tap"]
            diffs = [k for k in mine if mine[k] != theirs[k]]
            if diffs:
                d += 1
                print("  DISAGREE %s %s on %s: python %s, tap %s" % (os.path.basename(f), it["source"], diffs, {k: mine[k] for k in diffs}, {k: theirs[k] for k in diffs}))
            psk += mine["psk"] and it["client"]
            early += mine["early"]
            sni += bool(mine["sni"])
            servers += not it["client"]
            clients += it["client"]
        print("tap-xcheck %s: %d hellos, %d disagree" % (os.path.basename(f), n, d))
        total += n
        bad += d
        if n == 0:
            bad += 1
    print("tap-xcheck: %d hellos, %d disagree, kinds: client %d server %d, psk %d, early_data %d, sni %d" % (total, bad, clients, servers, psk, early, sni))
    if psk == 0 or early == 0 or servers == 0:
        print("tap-xcheck: VACUOUS corpus (needs a pre_shared_key hello, an early_data hello and a ServerHello)")
        return 1
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
