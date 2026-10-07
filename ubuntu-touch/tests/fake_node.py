#!/usr/bin/env python3
"""A scripted node for the QML tests: speaks asom-ut-ctl/1 on stdin/stdout with canned peers and answers, no JVM and no network.
It mirrors `--fake-ui` of the JVM node (FakeNode.kt); tools/check_fake_nodes.py runs one script against both and fails on any
difference, so the QML tests exercise the same wire the real node speaks. LAB / test double: it proves nothing about a device.
Standard library only."""
import json
import sys

NODE_TAG = "fake0fake0fake0fa"
COLUMNS = ["ts", "callerPkg", "requestedModel", "servedProvider", "servedModel", "egress", "bytesOut", "tokensIn", "tokensOut",
           "costEst", "costBasis", "latencyMs", "status", "requestId", "attemptId", "phase", "attemptIndex", "reach", "terminal",
           "servedClass", "peerNode", "peerAlias", "peerPath", "meshKind", "bytesIn", "meshCode", "destAddr", "addrSource",
           "routeReason", "routeDetail", "droppedFields", "overheadBytes", "overheadBasis", "sessionId"]
PROJECTED = ["ts", "requestId", "requestedModel", "servedProvider", "servedModel", "egress", "reach", "servedClass", "peerNode",
             "peerAlias", "peerPath", "routeReason", "status", "latencyMs", "tokensIn", "tokensOut", "terminal", "meshCode"]
POLICIES = {"auto", "cheapest", "fastest", "best-reasoning", "local-only"}
PEERS = [("fake-deck", {"fake", "fake-interrupt"}, False), ("fake-cooling-peer", {"fake-cooling"}, True)]
MAX_LINE = 1 << 20


def out(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def row(rid, model, ts):
    r = {c: None for c in COLUMNS}
    r.update({"ts": ts, "callerPkg": "self-ui:xyz.mdhv.asom.ut", "requestedModel": model, "servedProvider": "peer", "servedModel": "fake",
              "egress": "peer", "bytesOut": 0, "tokensIn": 3, "tokensOut": 4, "costBasis": "none", "latencyMs": 12, "status": 200,
              "requestId": rid, "reach": "peer", "terminal": True, "servedClass": "peer", "peerNode": NODE_TAG, "peerAlias": "fake-deck",
              "peerPath": "lan"})
    return r


def record(rid, model):
    r = row(rid, model, 2000)
    rec = {k: r[k] for k in PROJECTED}
    rec["provenance"] = "served by peer:fake-deck/fake · via lan"
    return rec


def error_for(model):
    if model == "local-only":
        return "LOCAL_ENGINE_ABSENT"
    policy = model in POLICIES
    holders = [p for p in PEERS if p[1]] if policy else [p for p in PEERS if model in p[1]]
    if not holders:
        return "NO_PROVIDER_KEY" if policy else "MODEL_UNKNOWN"
    if all(p[2] for p in holders):
        return "ALL_PROVIDERS_COOLING"
    return None


def main():
    ui_active = False
    state = "idle"
    last = None

    def emit_state():
        nonlocal last
        if last != state:
            last = state
            out({"t": "state", "node": state, "sessions": 0, "lending": "off"})

    def set_state(s):
        nonlocal state
        state = s
        emit_state()

    def fail(reason):
        sys.stderr.write("asom-ut: protocol violation " + reason + "\n")
        sys.stderr.flush()
        sys.exit(65)

    def read_line():
        line = sys.stdin.buffer.readline(MAX_LINE + 2)
        if not line:
            return None
        if not line.endswith(b"\n"):
            fail("TOO_LONG")
        return line[:-1]

    first = read_line()
    if first is None:
        sys.exit(0)
    try:
        hello = json.loads(first.decode("utf-8"))
    except ValueError:
        fail("NOT_JSON")
    if hello != {"t": "hello", "v": 1}:
        fail("BAD_FIELD")
    out({"t": "hello_ack", "nodeTag": NODE_TAG, "keyStorage": "file"})
    emit_state()
    while True:
        raw = read_line()
        if raw is None:
            return
        try:
            f = json.loads(raw.decode("utf-8"))
        except ValueError:
            fail("NOT_JSON")
        t = f.get("t") if isinstance(f, dict) else None
        if t == "lifecycle":
            s = f.get("state")
            if s == "active":
                ui_active = True
                if state == "interrupted":
                    set_state("idle")
            else:
                ui_active = False
                if state in ("idle", "active"):
                    set_state("interrupted")
        elif t == "peers":
            if f.get("op") == "open":
                if not ui_active or state == "interrupted":
                    out({"t": "error", "rid": None, "code": "INTERRUPTED_BY_SUSPEND"})
                else:
                    if state == "idle":
                        set_state("active")
                    out({"t": "peers", "list": [{"alias": p[0]} for p in PEERS]})
        elif t == "borrow":
            rid, model = f["rid"], f["model"]
            code = error_for(model)
            if code is not None:
                out({"t": "error", "rid": rid, "code": code})
            elif not ui_active or state == "interrupted":
                out({"t": "error", "rid": rid, "code": "INTERRUPTED_BY_SUSPEND"})
            else:
                if state == "idle":
                    set_state("active")
                out({"t": "chunk", "rid": rid, "delta": "Hello "})
                if model == "fake-interrupt":
                    out({"t": "error", "rid": rid, "code": "MESH_STREAM_INTERRUPTED"})
                else:
                    out({"t": "chunk", "rid": rid, "delta": "from the fake node."})
                    out({"t": "end", "rid": rid, "record": record(rid, model)})
        elif t in ("pair", "revoke", "export"):
            out({"t": "error", "rid": None, "code": "UNSUPPORTED_BY_DRIVER"})
        elif t == "ledger":
            out({"t": "rows", "rows": [row("fake-1", "fake", 1000)][: f.get("limit", 1)]})
        elif t == "selftest":
            out({"t": "selftest", "result": {"selftest": "ok", "profile": "fake-ui"}})
        elif t == "shutdown":
            return
        elif t == "cancel":
            pass
        else:
            fail("UNKNOWN_TYPE")


if __name__ == "__main__":
    main()
