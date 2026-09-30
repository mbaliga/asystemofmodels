"""R05 (failover: attempt state and event -> action, row status, breaker change) for gen_vectors.py. Every expected value is hand-typed from router.md 8.2 with the
r3 codes (PEER_UNAVAILABLE replaces PEER_THERMAL, PEER_BATTERY and PEER_USER_ACTIVE; terminal = interrupted replaces thermal). The rows the spec leaves open are
named in the descriptions and in lab/ERRATA.md."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import ok, rej, vec, write  # noqa: E402

R05 = []
_n = [0]


def D(action, status, breaker="NONE", backoff=None, tracker="NONE", cancel=False, sse=None, new_id=False, refresh=False, content=False):
    return dict(action=action, cancelFrame=cancel, rowStatus=status, breaker=breaker, backoffMs=backoff, tracker=tracker, sseReason=sse, newAttemptId=new_id,
                refreshRegistry=refresh, contentLeft=content)


def r05(desc, phase, event, decision=None, stream=False, committed=False):
    _n[0] += 1
    inp = dict(kind="decide", phase=phase, stream=stream, headersCommitted=committed, event=event)
    R05.append(vec(f"R05-{_n[0]:03d}", desc, inp, ok(decision) if decision is not None else rej("ILLEGAL_TRANSITION")))


def reeval(desc, accepted, runner, worse):
    _n[0] += 1
    R05.append(vec(f"R05-{_n[0]:03d}", desc, dict(kind="reeval", acceptedScore=accepted, runnerUpScore=runner), ok(dict(worse=worse))))


def ev(kind, **kw):
    return dict(kind=kind, **kw)


# row 1: dial or TLS failure (no content, no bytes)
r05("row 1: a dial or TLS pin failure: next candidate, breaker +1 on the peer transport curve, PEER_UNREACHABLE", "PLANNED", ev("DialFailed"), D("NEXT", "PEER_UNREACHABLE", "TRANSPORT_FAILURE"))
r05("row 1: a connection lost while offering (no content yet) is PEER_UNREACHABLE too", "OFFERING", ev("ConnectionLost"), D("NEXT", "PEER_UNREACHABLE", "TRANSPORT_FAILURE"))
r05("row 1: a connection lost after the accept and before the body is PEER_UNREACHABLE too", "ACCEPTED", ev("ConnectionLost"), D("NEXT", "PEER_UNREACHABLE", "TRANSPORT_FAILURE"))
# row 2: declines
r05("row 2: PEER_BUSY with retryAfterMs 12,000: next, back-off 12,000 ms, no breaker", "OFFERING", ev("Decline", code="PEER_BUSY", retryAfterMs=12000), D("NEXT", "PEER_BUSY", backoff=12000))
r05("row 2: retryAfterMs below the 5,000 minimum is raised to 5,000", "OFFERING", ev("Decline", code="PEER_BUSY", retryAfterMs=100), D("NEXT", "PEER_BUSY", backoff=5000))
r05("row 2: retryAfterMs above 600,000 is capped (the requester never trusts a longer stay-away)", "OFFERING", ev("Decline", code="PEER_BUSY", retryAfterMs=9999999), D("NEXT", "PEER_BUSY", backoff=600000))
r05("row 2 (r3): PEER_UNAVAILABLE stands for a thermal, battery or presence cause alike: next, back-off retryAfterMs", "OFFERING", ev("Decline", code="PEER_UNAVAILABLE", retryAfterMs=600000),
    D("NEXT", "PEER_UNAVAILABLE", backoff=600000))
# row 3
r05("row 3: MODEL_NOT_OFFERED feeds the claim tracker as a failed observation", "OFFERING", ev("Decline", code="MODEL_NOT_OFFERED", retryAfterMs=30000),
    D("NEXT", "MODEL_NOT_OFFERED", backoff=30000, tracker="FAILED_OBSERVATION"))
# row 4
r05("row 4: SCOPE_DENIED: next, refresh the registry view, exclude until the session is re-established", "OFFERING", ev("Decline", code="SCOPE_DENIED", retryAfterMs=30000),
    D("NEXT", "SCOPE_DENIED", "EXCLUDE_UNTIL_SESSION_REESTABLISHED", refresh=True))
r05("row 4: ERROR PEER_NOT_PAIRED: the same", "OFFERING", ev("ErrorFrame", code="PEER_NOT_PAIRED"), D("NEXT", "PEER_NOT_PAIRED", "EXCLUDE_UNTIL_SESSION_REESTABLISHED", refresh=True))
# row 5
r05("row 5: an offer timeout: CANCEL, next, breaker +1", "OFFERING", ev("OfferTimeout"), D("NEXT", "OFFER_TIMEOUT", "TRANSPORT_FAILURE", cancel=True))
# row 6
r05("row 6: accepted, then re-evaluated worse than the next candidate: CANCEL before the body, next, no breaker", "ACCEPTED", ev("AcceptedWorse"), D("NEXT", "CANCELLED_BEFORE_BODY", cancel=True))
# row 7
r05("row 7: the body was sent and no INFER_HEAD came in time: CANCEL, retry allowed, breaker +1, content left", "BODY_SENT", ev("HeadTimeout"),
    D("NEXT", "PEER_LOST_PRE_HEAD", "TRANSPORT_FAILURE", cancel=True, content=True))
r05("row 7: GOAWAY or a reset after the body and before the head: the same", "BODY_SENT", ev("ConnectionLost"), D("NEXT", "PEER_LOST_PRE_HEAD", "TRANSPORT_FAILURE", cancel=True, content=True))
# row 8
r05("row 8: non-stream, terminal interrupted (r3: replaces thermal): next, back-off 60,000 ms", "RECEIVING", ev("Terminal", terminal="interrupted"), D("NEXT", "PEER_INTERRUPTED", backoff=60000, content=True))
r05("row 8: non-stream, terminal oom: next, the tracker marks the memory claim DISCREPANT", "RECEIVING", ev("Terminal", terminal="oom"), D("NEXT", "PEER_OOM", tracker="MEMORY_DISCREPANT", content=True))
r05("row 8: non-stream, terminal error: next, breaker +1 on the v1 curve", "RECEIVING", ev("Terminal", terminal="error"), D("NEXT", "PEER_ERROR", "FAILURE", content=True))
r05("row 8: non-stream, terminal cancelled by the lender: next, breaker +1", "RECEIVING", ev("Terminal", terminal="cancelled"), D("NEXT", "PEER_CANCELLED", "FAILURE", content=True))
r05("row 8: a stream whose INFER_HEAD has not arrived is retried like a non-stream (headers not committed)", "BODY_SENT", ev("Terminal", terminal="error"), D("NEXT", "PEER_ERROR", "FAILURE", content=True), stream=True)
r05("row 8: non-stream, connection lost during the answer: next, breaker +1 (status PEER_LOST: the spec names none for a loss without a terminal)", "RECEIVING", ev("ConnectionLost"),
    D("NEXT", "PEER_LOST", "TRANSPORT_FAILURE", content=True))
# row 9
r05("row 9: stream, loss after INFER_HEAD: no retry; the SSE error event MESH_STREAM_INTERRUPTED reason peer-lost, no [DONE]; breaker +1", "RECEIVING", ev("ConnectionLost"),
    D("FAIL_IN_BAND", "PEER_LOST_MID_STREAM", "TRANSPORT_FAILURE", sse="peer-lost", content=True), stream=True, committed=True)
# row 10
r05("row 10 (r3): stream, terminal interrupted after the head: fail in band with the one reason peer-lost, back-off 60,000 ms", "RECEIVING", ev("Terminal", terminal="interrupted"),
    D("FAIL_IN_BAND", "PEER_INTERRUPTED_MID_STREAM", backoff=60000, sse="peer-lost", content=True), stream=True, committed=True)
r05("row 10: stream, terminal oom after the head: fail in band, memory claim DISCREPANT", "RECEIVING", ev("Terminal", terminal="oom"),
    D("FAIL_IN_BAND", "PEER_OOM_MID_STREAM", tracker="MEMORY_DISCREPANT", sse="peer-lost", content=True), stream=True, committed=True)
r05("row 10: stream, terminal error after the head: fail in band, breaker +1", "RECEIVING", ev("Terminal", terminal="error"),
    D("FAIL_IN_BAND", "PEER_ERROR_MID_STREAM", "FAILURE", sse="peer-lost", content=True), stream=True, committed=True)
# the good path
r05("terminal done: return the answer", "RECEIVING", ev("Terminal", terminal="done"), D("RETURN", "ok", content=True))
r05("terminal done before any chunk (an empty answer): return", "BODY_SENT", ev("Terminal", terminal="done"), D("RETURN", "ok", content=True))
# row 11
r05("row 11: this device's governor HOLD mid-stream is the v2 behaviour (no peer effect)", "RECEIVING", ev("SelfGovernorHold"), D("V2_LOCAL", "V2"), stream=True, committed=True)
# row 12
r05("row 12: the deadline passed while offering: stop", "OFFERING", ev("DeadlinePassed"), D("STOP", "DEADLINE"))
r05("row 12: the deadline passed while the peer holds the body: stop", "BODY_SENT", ev("DeadlinePassed"), D("STOP", "DEADLINE", content=True))
# row 13
r05("row 13: DUPLICATE_ATTEMPT is a bug signal: never retried with the same attemptId; next candidate with a new id", "OFFERING", ev("Decline", code="DUPLICATE_ATTEMPT", retryAfterMs=5000),
    D("NEXT", "DUPLICATE_ATTEMPT", new_id=True))
# client disconnect
r05("a client disconnect while offering: CANCEL, both rows CANCELLED, no breaker", "OFFERING", ev("ClientDisconnect"), D("CLIENT_CANCELLED", "CANCELLED", cancel=True))
r05("a client disconnect while the peer answers: CANCEL, content left", "RECEIVING", ev("ClientDisconnect"), D("CLIENT_CANCELLED", "CANCELLED", cancel=True, content=True), stream=True, committed=True)
# illegal combinations
r05("illegal: a decline can only arrive while offering", "RECEIVING", ev("Decline", code="PEER_BUSY", retryAfterMs=5000))
r05("illegal: a dial failure can only happen before the offer", "OFFERING", ev("DialFailed"))
r05("illegal: an offer timeout can only happen while offering", "ACCEPTED", ev("OfferTimeout"))
r05("illegal: a worse accept-time re-evaluation can only follow an accept", "OFFERING", ev("AcceptedWorse"))
r05("illegal: a head timeout needs a body that was sent", "OFFERING", ev("HeadTimeout"))
r05("illegal: an INFER_END terminal needs the body to have been sent", "OFFERING", ev("Terminal", terminal="done"))
r05("illegal: nothing to cancel before the attempt starts", "PLANNED", ev("ClientDisconnect"))
r05("illegal: a lost connection needs an attempt in flight", "PLANNED", ev("ConnectionLost"))
# accept-time re-evaluation (router.md 8.4)
reeval("re-evaluation: 6,000 against a runner-up of 5,000 is inside max(1,000 ms, 10%): keep", 6000, 5000, False)
reeval("re-evaluation: 6,001 against 5,000 is worse by more than 1,000 ms: cancel", 6001, 5000, True)
reeval("re-evaluation: at 20,000 the tolerance is 10% = 2,000: 22,000 keeps", 22000, 20000, False)
reeval("re-evaluation: 22,001 against 20,000 cancels", 22001, 20000, True)

write("R05-failover.json", "R05", ["docs/design/mesh/router.md 8.1, 8.2, 8.4", "LAB_SPEC.md 6.10 (R05)", "LAB_SPEC.md 6.7 (errors, MESH_STREAM_INTERRUPTED)"], R05)
