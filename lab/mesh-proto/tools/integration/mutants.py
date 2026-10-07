#!/usr/bin/env python3
"""Mutation checks for the proto-integration track (LAB_SPEC R10): break the implementation on purpose, run the integration suite over real TLS, require a failure, restore.

  python3 lab/mesh-proto/tools/integration/mutants.py                 # every mutant, JDK 21 (the default java)
  python3 lab/mesh-proto/tools/integration/mutants.py --jdk17 3 5 9   # selected mutants (1-based) on JDK 17 (/opt/jdks/jdk-17)
  python3 lab/mesh-proto/tools/integration/mutants.py --list          # names only
  python3 lab/mesh-proto/tools/integration/mutants.py --baseline      # the unmutated suite must pass first

A mutant is a list of exact-string edits (file under lab/mesh-proto/src/main/kotlin/xyz/mdhv/asom/lab/proto, old text, new text, expected number of occurrences). The original bytes of every
touched file are held in memory and restored in a `finally`; a mutant whose text is not found (or found a different number of times) aborts the script, because a mutant that changed
nothing proves nothing. A mutant counts as KILLED only when the build compiled and the integration tests failed; the first failing tests are printed so that a kill by an unrelated
timeout is visible. The suite run is `xyz.mdhv.asom.lab.proto.integration.*` (about a minute).
"""
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", ".."))
SRC = os.path.join(ROOT, "lab", "mesh-proto", "src", "main", "kotlin", "xyz", "mdhv", "asom", "lab", "proto")
SES, LEN, REQ, NODE, WIRE, ROWS, PAIRCH, LIMITS = (
    "session/Session.kt", "session/LenderSide.kt", "session/RequesterSide.kt", "session/Node.kt", "session/Wire.kt", "session/SessionRows.kt", "session/PairingChannel.kt", "session/SessionLimits.kt",
)
IO, MESH, MGR, VERIFY = "tls/TlsEngineIo.kt", "tls/MeshTls.kt", "tls/MeshTlsManagers.kt", "trust/VerifyPeerChain.kt"
LIM, DIAL = "integration/HandshakeLimiter.kt", "integration/DialOutcomes.kt"

MUTANTS = [
    # --- the seven the assignment names (some in two forms) ---
    ("bytes are counted as the payload only by the session layer (9 + payload becomes payload), the tap and the plaintext log unchanged", [
        (WIRE, "internal fun appBytes(f: RawFrame): Long = WireLimits.HEADER_BYTES.toLong() + f.payload.size", "internal fun appBytes(f: RawFrame): Long = f.payload.size.toLong()", 1)]),
    ("the SESSION close row counts the session's own output twice and never its input (the plaintext source swapped)", [
        (ROWS, "(wire.networkNow() - (wire.input.count + wire.output.count) - handshakeRecorded).coerceAtLeast(0)", "(wire.networkNow() - (wire.output.count + wire.output.count) - handshakeRecorded).coerceAtLeast(0)", 1)]),
    ("the network meter of the session layer reads the inbound figure twice (the tap source swapped)", [
        (WIRE, "fun networkNow(): Long = conn.counters().let { it.networkBytesIn + it.networkBytesOut }", "fun networkNow(): Long = conn.counters().let { it.networkBytesIn + it.networkBytesIn }", 1)]),
    ("the per-frame row is appended AFTER the frame is handed to TLS (control frames)", [
        (SES, "        rows.control(rows.frameRow(shape.kind, shape.code, out = appBytes(frame), routeDetail = routeDetail))\n        wire.write(frame)\n",
         "        wire.write(frame)\n        rows.control(rows.frameRow(shape.kind, shape.code, out = appBytes(frame), routeDetail = routeDetail))\n", 1)]),
    ("a control-row failure still sends GOAWAY over the TLS connection", [
        (SES, "        failClosedDone = true\n        phase = Phase.CLOSED\n        wire.kill()\n",
         "        failClosedDone = true\n        phase = Phase.CLOSED\n        runCatching { wire.write(MessageCodec.frame(GoAway(GoAwayReason.SHUTDOWN), 0)) }\n        wire.kill()\n", 1)]),
    ("a revoked or suspended pin is served an offer over TLS (the lender decision sees PAIRED with scope)", [
        (LEN, "registryStatus = status, inferScopeGranted = auth is AuthDecision.Allow,", "registryStatus = PeerStatus.PAIRED, inferScopeGranted = true,", 1)]),
    ("verifyPeerChain accepts a REVOKED pin (a revoked pin served over the TLS handshake)", [
        (VERIFY, '                PeerStatus.REVOKED -> return reject(ChainReject.PIN_REVOKED, "the peer is revoked")\n', "                PeerStatus.REVOKED -> Unit\n", 1)]),
    ("the handshake overhead snapshot of a session is taken too early (zero, before the handshake)", [
        (WIRE, "    val handshakeNetwork: Long = firstCounters.networkBytesIn + firstCounters.networkBytesOut", "    val handshakeNetwork: Long = 0L", 1)]),
    ("the handshake overhead of a DIAL outcome row is taken too early (zero)", [
        (NODE, "            handshake = if (c.measured) c.networkBytesIn + c.networkBytesOut else Overhead.estimatedHandshake()", "            handshake = 0L", 1)]),
    ("the handshake overhead snapshot of a session is taken too late (at the time the row is written)", [
        (WIRE, "    val handshakeNetwork: Long = firstCounters.networkBytesIn + firstCounters.networkBytesOut", "    val handshakeNetwork: Long get() = networkNow()", 1)]),
    ("the idle timer never fires", [
        (SES, "            now - lastStreamActivity >= SessionLimits.IDLE_MS -> goAway(GoAwayReason.IDLE)\n", "", 1)]),
    ("the max-age timer never fires", [
        (SES, "            now - establishedAt >= SessionLimits.MAX_AGE_MS -> goAway(GoAwayReason.MAX_AGE)\n", "", 1)]),
    # --- the other timers and limits ---
    ("an accepted attempt whose body never comes never expires", [
        (LEN, "            if (now - a.acceptedAt >= wait) finish(a, EngineEvent.End(Terminal.ERROR, 408, null))\n", "", 1)]),
    ("the body wait ignores the offer's own deadline", [
        (LEN, "val wait = minOf(a.offer.deadlineMs, SessionLimits.BODY_WAIT_MS)", "val wait = SessionLimits.BODY_WAIT_MS", 1)]),
    ("a fifth concurrent stream is accepted (the cap is 5)", [
        (LIMITS, "const val MAX_STREAMS: Int = 4", "const val MAX_STREAMS: Int = 5", 1)]),
    ("a connection-level frame (stream 0) restarts the idle timer", [
        (SES, "        if (stream != 0L) lastStreamActivity = node.ledger.now()", "        lastStreamActivity = node.ledger.now()", 1)]),
    ("the per-source handshake limit is not applied", [
        (LIM, "        if (window.size >= perSourcePerWindow) return Admission.Refused(Admission.Why.PER_SOURCE_RATE)\n", "", 1)]),
    ("the global in-flight handshake limit is not applied", [
        (LIM, "        if (inFlight >= maxInFlight) return Admission.Refused(Admission.Why.GLOBAL_IN_FLIGHT)\n", "", 1)]),
    ("a chain our verifier refused is reported as a refused socket instead of pin-mismatch", [
        (DIAL, "        MeshTlsRefusal.PEER_CHAIN_REJECTED, MeshTlsRefusal.PEER_CERTIFICATE_MISSING -> PIN_MISMATCH", "        MeshTlsRefusal.PEER_CHAIN_REJECTED, MeshTlsRefusal.PEER_CERTIFICATE_MISSING -> REFUSED", 1)]),
    # --- the fixes this track made, reverted ---
    ("TlsEngineIo counts a produced record as sent before the socket took it (the fix of ERR-PI-1, reverted)", [
        (IO, "            outCount.addAndGet((produced - netOut.remaining()).toLong())", "            outCount.addAndGet(produced.toLong())", 1)]),
    ("TlsEngineIo does not count the start of a record that a reset cut in half (the fix of ERR-PI-1, reverted)", [
        (IO, "            return readCount.get()", "            return inCount.get()", 1)]),
    ("a dialler's PairingChannel does not take the DIAL session id and handshake figure (the seam of ERR-PI-7, reverted)", [
        (PAIRCH, 'SessionRows(ledger, dialSessionId ?: ids.b64(16), null, "unknown", null, wire, dialHandshakeRecorded)', 'SessionRows(ledger, ids.b64(16), null, "unknown", null, wire, 0)', 1)]),
    ("a TLS connection reports its counters as not measured (every row ESTIMATED)", [
        (MESH, "TransportCounters(io.networkBytesIn, io.networkBytesOut, measured = true)", "TransportCounters(io.networkBytesIn, io.networkBytesOut, measured = false)", 1)]),
    # --- the session-layer security mutants, re-run against the TLS suites ---
    ("the trust manager lets a refused chain through (only the post-handshake check is left)", [
        (MGR, "        if (v !is ChainVerdict.Accepted) throw CertificateException()\n", "", 1)]),
    ("authorisation is read once per session, not per frame", [
        (LEN, "    private val served = LinkedHashMap<Long, Served>()\n", "    private val served = LinkedHashMap<Long, Served>()\n    private val authCache = HashMap<String, AuthDecision>()\n", 1),
        (LEN, 'val auth = node.registry.authorize(s.peer, "infer")', 'val auth = authCache.getOrPut("infer") { node.registry.authorize(s.peer, "infer") }', 1),
        (LEN, 'when (val auth = s.node.registry.authorize(s.peer, "infer")) {', 'when (val auth = authCache.getOrPut("infer") { s.node.registry.authorize(s.peer, "infer") }) {', 1),
        (LEN, 'when (val auth = s.node.registry.authorize(s.peer, "state")) {', 'when (val auth = authCache.getOrPut("state") { s.node.registry.authorize(s.peer, "state") }) {', 1),
        (LEN, 'when (val auth = s.node.registry.authorize(s.peer, "manifest")) {', 'when (val auth = authCache.getOrPut("manifest") { s.node.registry.authorize(s.peer, "manifest") }) {', 1)]),
    ("the registry change listener does not close the sessions of a peer that left PAIRED", [
        (NODE, "                val g = change.goaway ?: return@RegistryListener\n                sessionsOf(change.pin).forEach { it.goAwayForRegistry(g) }\n", "", 1)]),
    ("a duplicate attemptId is accepted", [(NODE, "        return before\n", "        return false\n", 1)]),
    ("the extension skip has no EXT_IGNORED row", [
        (SES, '        rows.control(rows.frameRow(MeshKind.CONTROL, "EXT_IGNORED", inn = e.appBytes))\n', "", 1)]),
    ("FC-5: INFER_END is still sent when the lender outcome row failed", [
        (LEN, "            a.run?.cancel()\n            release(a)\n            s.failClosedNow()\n            throw FailClosed()\n        }\n        a.bytesOut = out\n        a.done = true",
         "        }\n        a.bytesOut = out\n        a.done = true", 1)]),
    ("FC-1: a requester intent that failed is ignored and the offer is sent", [
        (REQ, "        node.ledger.appendIntent(intentRow(spec, id))\n        val a = Attempt(stream, id, spec, body, listener)",
         "        try { node.ledger.appendIntent(intentRow(spec, id)) } catch (e: xyz.mdhv.asom.lab.ledger.LedgerUnavailableException) { }\n        val a = Attempt(stream, id, spec, body, listener)", 1)]),
    ("FC-4: a lender intent that failed does not decline (the engine starts anyway)", [
        (LEN, "            s.node.counters.count(Refusal.PEER_UNAVAILABLE)\n            return decline(a, DeclineWire.PEER_UNAVAILABLE, LenderDecisionTable.CONDITION_RETRY_MS)\n", "", 1)]),
    ("the DIAL intent is appended after the connect", [
        (NODE, "        ledger.appendIntent(row(Phase.INTENT, null, 0, null, null, null))\n        val result = dialer.connect(destAddr)\n",
         "        val result = dialer.connect(destAddr)\n        ledger.appendIntent(row(Phase.INTENT, null, 0, null, null, null))\n", 1)]),
]


def gradle(args, jdk17):
    env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS"}
    if jdk17:
        env["JAVA_HOME"] = "/opt/jdks/jdk-17"
    p = subprocess.run(["./gradlew", "-p", "lab"] + args + ["--max-workers=2", "--offline", "--console=plain"], cwd=ROOT, capture_output=True, text=True, env=env)
    return p.returncode, p.stdout + p.stderr


SUITE = [":mesh-proto:test", "--tests", "xyz.mdhv.asom.lab.proto.integration.*"]


def main(argv):
    jdk17 = "--jdk17" in argv
    if "--list" in argv:
        for i, (name, _) in enumerate(MUTANTS):
            print("%2d  %s" % (i + 1, name))
        return 0
    if "--baseline" in argv:
        rc, out = gradle(SUITE, jdk17)
        print("baseline (%s): %s" % ("JDK 17" if jdk17 else "JDK 21", "PASSED" if rc == 0 else "FAILED"))
        if rc != 0:
            print("\n".join(out.splitlines()[-30:]))
        return rc
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
            rc, out = gradle(SUITE, jdk17)
            compiled = "Compilation error" not in out and "e: file:" not in out
            killed = rc != 0 and compiled
            failed = [l.strip() for l in out.splitlines() if " FAILED" in l and "Task" not in l][:2]
            print("mutant %2d %-16s %s%s" % (idx, "KILLED" if killed else ("DID-NOT-COMPILE" if not compiled else "SURVIVED"), name, ("\n            e.g. " + failed[0]) if failed else ""))
            sys.stdout.flush()
            if not killed:
                survivors += 1
        finally:
            for path, original in originals.items():
                open(path, "wb").write(original)
                assert open(path, "rb").read() == original
    print("mutants run: %d (%s), survivors or non-compiling: %d" % (len(todo), "JDK 17" if jdk17 else "JDK 21", survivors))
    return 1 if survivors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
