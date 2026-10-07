#!/usr/bin/env python3
"""Mutation checks for the session layer (LAB_SPEC R10, assignment proto-session): break the implementation on purpose, run the session suite, require a failure, restore.

  python3 lab/mesh-proto/tools/session/mutants.py            # every mutant against the session tests of :mesh-proto
  python3 lab/mesh-proto/tools/session/mutants.py 1 4 7      # selected mutants (1-based)
  python3 lab/mesh-proto/tools/session/mutants.py --list     # names only

A mutant is a list of exact-string edits (file, old text, new text, expected number of occurrences). The original bytes of every touched file are held in memory and
restored in a `finally`; a mutant whose text is not found (or found a different number of times) aborts the script, because a mutant that changed nothing proves
nothing. A mutant counts as KILLED only when the build compiled and the session tests failed.
"""
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", ".."))
SRC = os.path.join(ROOT, "lab", "mesh-proto", "src", "main", "kotlin", "xyz", "mdhv", "asom", "lab", "proto", "session")
SES = "Session.kt"
LEN = "LenderSide.kt"
REQ = "RequesterSide.kt"
NODE = "Node.kt"
WIRE = "Wire.kt"
ROWS = "SessionRows.kt"

MUTANTS = [
    ("the per-frame row is appended AFTER the frame is sent (control frames)", [
        (SES, "        rows.control(rows.frameRow(shape.kind, shape.code, out = appBytes(frame), routeDetail = routeDetail))\n        wire.write(frame)\n",
         "        wire.write(frame)\n        rows.control(rows.frameRow(shape.kind, shape.code, out = appBytes(frame), routeDetail = routeDetail))\n", 1)]),
    ("the lender decline outcome row is appended AFTER the INFER_DECLINE is sent", [
        (LEN, "        s.rows.control(outcomeRow(a, 503, code.wire, out, null))\n        a.bytesOut = out\n        release(a)\n        s.writeAttemptFrame(frame)\n",
         "        s.writeAttemptFrame(frame)\n        s.rows.control(outcomeRow(a, 503, code.wire, out, null))\n        a.bytesOut = out\n        release(a)\n", 1)]),
    ("the requester intent row is appended AFTER the INFER_OFFER is sent", [
        (REQ, "        node.ledger.appendIntent(intentRow(spec, id))\n        val a = Attempt(stream, id, spec, body, listener)\n        a.startTs = node.ledger.now()\n        a.bytesOut += appBytes(frame)\n        attempts[stream] = a\n        s.writeAttemptFrame(frame)\n",
         "        val a = Attempt(stream, id, spec, body, listener)\n        a.startTs = node.ledger.now()\n        a.bytesOut += appBytes(frame)\n        attempts[stream] = a\n        s.writeAttemptFrame(frame)\n        node.ledger.appendIntent(intentRow(spec, id))\n", 1)]),
    ("the lender outcome row is appended AFTER INFER_END is sent", [
        (LEN, "        try {\n            s.rows.attempt(row)\n        } catch (e: LedgerWriteException) {\n            a.run?.cancel()\n            release(a)\n            s.failClosedNow()\n            throw FailClosed()\n        }\n        a.bytesOut = out\n        a.done = true\n        release(a)\n        s.writeAttemptFrame(end)\n",
         "        s.writeAttemptFrame(end)\n        try {\n            s.rows.attempt(row)\n        } catch (e: LedgerWriteException) {\n            a.run?.cancel()\n            release(a)\n            s.failClosedNow()\n            throw FailClosed()\n        }\n        a.bytesOut = out\n        a.done = true\n        release(a)\n", 1)]),
    ("a control-row failure still sends GOAWAY", [
        (SES, "        failClosedDone = true\n        phase = Phase.CLOSED\n        wire.kill()\n",
         "        failClosedDone = true\n        phase = Phase.CLOSED\n        runCatching { wire.write(MessageCodec.frame(GoAway(GoAwayReason.SHUTDOWN), 0)) }\n        wire.kill()\n", 1)]),
    ("a control-row failure does not close the connection", [
        (SES, "        failClosedDone = true\n        phase = Phase.CLOSED\n        wire.kill()\n", "        failClosedDone = true\n        phase = Phase.CLOSED\n", 1)]),
    ("FC-4 does not decline PEER_UNAVAILABLE (the engine starts anyway)", [
        (LEN, "            s.node.counters.count(Refusal.PEER_UNAVAILABLE)\n            return decline(a, DeclineWire.PEER_UNAVAILABLE, LenderDecisionTable.CONDITION_RETRY_MS)\n", "", 1)]),
    ("FC-5 still sends INFER_END when the outcome row failed", [
        (LEN, "            a.run?.cancel()\n            release(a)\n            s.failClosedNow()\n            throw FailClosed()\n        }\n        a.bytesOut = out\n        a.done = true",
         "        }\n        a.bytesOut = out\n        a.done = true", 1)]),
    ("FC-1: a requester intent that failed is ignored and the offer is sent", [
        (REQ, "        node.ledger.appendIntent(intentRow(spec, id))\n        val a = Attempt(stream, id, spec, body, listener)",
         "        try { node.ledger.appendIntent(intentRow(spec, id)) } catch (e: xyz.mdhv.asom.lab.ledger.LedgerUnavailableException) { }\n        val a = Attempt(stream, id, spec, body, listener)", 1)]),
    ("authorisation is read once per session, not per frame", [
        (LEN, "    private val served = LinkedHashMap<Long, Served>()\n",
         "    private val served = LinkedHashMap<Long, Served>()\n    private val authCache = HashMap<String, AuthDecision>()\n", 1),
        (LEN, "val auth = node.registry.authorize(s.peer, \"infer\")", "val auth = authCache.getOrPut(\"infer\") { node.registry.authorize(s.peer, \"infer\") }", 1),
        (LEN, "when (val auth = s.node.registry.authorize(s.peer, \"infer\")) {", "when (val auth = authCache.getOrPut(\"infer\") { s.node.registry.authorize(s.peer, \"infer\") }) {", 1),
        (LEN, "when (val auth = s.node.registry.authorize(s.peer, \"state\")) {", "when (val auth = authCache.getOrPut(\"state\") { s.node.registry.authorize(s.peer, \"state\") }) {", 1),
        (LEN, "when (val auth = s.node.registry.authorize(s.peer, \"manifest\")) {", "when (val auth = authCache.getOrPut(\"manifest\") { s.node.registry.authorize(s.peer, \"manifest\") }) {", 1)]),
    ("a revoked or suspended peer is served (the decision sees PAIRED with scope)", [
        (LEN, "registryStatus = status, inferScopeGranted = auth is AuthDecision.Allow,", "registryStatus = PeerStatus.PAIRED, inferScopeGranted = true,", 1)]),
    ("a duplicate attemptId is accepted", [(NODE, "        return before\n", "        return false\n", 1)]),
    ("HELLO nodeId is not compared with the authenticated pin", [
        (SES, "Handshake.onHello(c.versions, c.features, h, peer.nodeId)", "Handshake.onHello(c.versions, c.features, h, h.nodeId)", 1)]),
    ("HELLO_ACK nodeId is not compared with the authenticated pin", [
        (SES, "Handshake.onAck(c.versions, c.features, ack, peer.nodeId)", "Handshake.onAck(c.versions, c.features, ack, ack.nodeId)", 1)]),
    ("a received MANIFEST that fails verification is accepted", [
        (REQ, "                is Rejected -> Verdict.Bad(r.code, r.step)\n",
         "                is Rejected -> Verdict.Ok(Verified(ByteArray(0), r.obj!!, \"\", xyz.mdhv.asom.lab.manifest.PinState.Pinned, xyz.mdhv.asom.lab.manifest.Tier.A0, 0, ByteArray(0), null))\n", 1)]),
    ("a received MANIFEST is verified against whatever key the host names, even one that does not hash to the pin", [
        (REQ, "val safe = if (ctx.pinnedSpki != null && !hashesTo(ctx.pinnedSpki!!, s.peer)) withoutPinnedKey(ctx) else ctx", "val safe = ctx", 1)]),
    ("the extension skip has no EXT_IGNORED row", [
        (SES, "        rows.control(rows.frameRow(MeshKind.CONTROL, \"EXT_IGNORED\", inn = e.appBytes))\n", "", 1)]),
    ("bytes are counted as the payload only (not 9 + payload)", [
        (WIRE, "internal fun appBytes(f: RawFrame): Long = WireLimits.HEADER_BYTES.toLong() + f.payload.size", "internal fun appBytes(f: RawFrame): Long = f.payload.size.toLong()", 1)]),
    ("a STATE builder emits a presence field", [
        (LEN, "                val frame = MessageCodec.frame(StateMsg(doc), f.stream)\n",
         "                val frame = xyz.mdhv.asom.lab.proto.wire.RawFrame(xyz.mdhv.asom.lab.proto.wire.FrameTypes.STATE, f.stream, String(payload, Charsets.UTF_8).replaceFirst(\"{\", \"{\\\"inflight\\\":1,\").toByteArray(Charsets.UTF_8))\n", 1)]),
    ("st is sent to a peer that does not hold scope state", [
        (SES, "        if (node.registry.authorize(peer, \"state\") !is AuthDecision.Allow) return null\n", "", 1)]),
    ("the registry change listener does not close the sessions of a peer that left PAIRED", [
        (NODE, "                val g = change.goaway ?: return@RegistryListener\n                sessionsOf(change.pin).forEach { it.goAwayForRegistry(g) }\n", "", 1)]),
    ("the DIAL intent is appended after the connect", [
        (NODE, "        ledger.appendIntent(row(Phase.INTENT, null, 0, null, null, null))\n        val result = dialer.connect(destAddr)\n",
         "        val result = dialer.connect(destAddr)\n        ledger.appendIntent(row(Phase.INTENT, null, 0, null, null, null))\n", 1)]),
    ("a HELLO from a clock far away is accepted (no CLOCK_SKEW)", [
        (SES, "                if (abs(h.ts - node.ledger.now()) > c.clockSkewMs) return fail(Refusal.CLOCK_SKEW)\n", "", 1)]),
    ("a reused stream id is accepted", [(SES, "        if (usedStreams.add(stream)) return true\n", "        if (true) return true\n", 1)]),
    ("the overhead of a MEASURED session is taken from the writer's estimate instead of the transport meter", [
        (ROWS, "        val overhead = if (wire.measured) {\n            (wire.networkNow()", "        val overhead = if (false) {\n            (wire.networkNow()", 1)]),
    ("a CANCEL for any attempt on any stream is accepted silently", [
        (LEN, "        if (a == null || a.offer.attemptId != c.attemptId) return s.fail(Refusal.CANCEL_UNKNOWN_ATTEMPT)\n", "        if (a == null) return\n", 1)]),
    ("an INFER_BODY without an accepted offer is ignored instead of refused", [
        (LEN, "        if (a == null || a.state != Stage.ACCEPTED) return s.fail(Refusal.BODY_WITHOUT_OFFER)\n", "        if (a == null || a.state != Stage.ACCEPTED) return\n", 1)]),
    ("the SESSION close row does not carry the uncovered input bytes", [
        (ROWS, "bytesIn = (wire.input.count - coveredIn).coerceAtLeast(0), meshCode = code,", "bytesIn = 0, meshCode = code,", 1)]),
]


def gradle(args):
    env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS"}
    p = subprocess.run(["./gradlew", "-p", "lab"] + args + ["--max-workers=2", "--offline"], cwd=ROOT, capture_output=True, text=True, env=env)
    return p.returncode, p.stdout + p.stderr


def main(argv):
    if "--list" in argv:
        for i, (name, _) in enumerate(MUTANTS):
            print("%2d  %s" % (i + 1, name))
        return 0
    picks = [int(a) for a in argv[1:] if a.isdigit()]
    todo = [(i + 1, m) for i, m in enumerate(MUTANTS) if not picks or (i + 1) in picks]
    survivors = 0
    for idx, (name, edits) in todo:
        originals = {}
        try:
            for rel, old, new, count in edits:
                path = os.path.join(SRC, rel)
                if path not in originals:
                    originals[path] = open(path, "rb").read()
                text = open(path, "rb").read().decode("utf-8")
                if text.count(old) != count:
                    print("mutant %d: the text to replace occurs %d times (expected %d) in %s: %s" % (idx, text.count(old), count, rel, name))
                    return 2
                open(path, "wb").write(text.replace(old, new).encode("utf-8"))
            rc, out = gradle([":mesh-proto:test", "--tests", "*session*"])
            compiled = "Compilation error" not in out and "e: file:" not in out
            killed = rc != 0 and compiled
            failed = [l.strip() for l in out.splitlines() if " FAILED" in l][:2]
            print("mutant %2d %-16s %s%s" % (idx, "KILLED" if killed else ("DID-NOT-COMPILE" if not compiled else "SURVIVED"), name, ("\n            e.g. " + failed[0]) if failed else ""))
            sys.stdout.flush()
            if not killed:
                survivors += 1
        finally:
            for path, original in originals.items():
                open(path, "wb").write(original)
                assert open(path, "rb").read() == original
    print("mutants run: %d, survivors or non-compiling: %d" % (len(todo), survivors))
    return 1 if survivors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
