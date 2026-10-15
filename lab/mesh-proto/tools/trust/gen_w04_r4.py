"""The W04 vectors of design revision 4 (trust.md R4-T-10; REVISION_4.md section 6, builder action 1). Imported by gen_vectors.py, which passes its own names.

The vectors here run the R3 machines (the default since R4-T-10: a vector without a "profile" member) and the no-expiry QR reading (a vector without an "expiry"
member). Every trace is typed by hand from trust.md 4.3 to 4.6 as amended by R4-T-10; the commitment, the proof over the commitment, the SAS of the opened nonce and
the transcript come from trustlib.py (hashlib and hmac, no Kotlin involved). All vectors are `oracle: self` (LAB_SPEC 4.10, R9).
"""
import hashlib
import json
import random


def add(g):
    T = g["T"]
    w04 = g["w04"]
    PIN_D, PIN_S = g["PIN_D"], g["PIN_S"]
    SECRET, NONCE_S, NONCE_D = g["SECRET"], g["NONCE_S"], g["NONCE_D"]
    TRANSCRIPT, T8 = g["TRANSCRIPT"], g["T8"]
    SEND_COMMIT = g["SEND_COMMIT"]
    ID_ = g["ID_"]
    d_open = g["d_open"]
    s_scan, s_dial = g["s_scan"], g["s_dial"]
    QNOW = g["QNOW"]
    uri = g["uri"]
    EX_URI = g["EX_URI"]

    C_S = T.commit_nonce(NONCE_S)
    assert C_S.hex() == "7aeb90fc067d60d0d8d5524a07ad60ad56e46927424cd4890afa4b11c560b4bf" and T.b64u(C_S) == "euuQ_AZ9YNDY1VJKB61grVbkaSdCTNSJCvpLEcVgtL8"
    assert T.sas_display(PIN_D, PIN_S, NONCE_S, NONCE_D) == "865 412"
    WIRE_SAS = T.sas_display(PIN_D, PIN_S, C_S, NONCE_D)
    assert WIRE_SAS != "865 412"
    S2 = hashlib.sha256(b"asom-vector/spki/S2").digest()
    CODE = "865 412"

    # ------------------------------------------------------------------------------------------------------------------------------ D
    def h3(t, status="ABSENT", pin_s=None, proof=None, conn=None, nonce_d=NONCE_D, nonce_s=NONCE_S):
        ps = pin_s if pin_s is not None else PIN_S
        c = T.commit_nonce(nonce_s)
        pr = proof if proof is not None else T.proof(SECRET, PIN_D, ps, c)
        e = {"e": "HelloReceived", "pinS": ID_(ps), "nonceSHex": c.hex(), "proofHex": pr.hex(), "status": status, "nonceDHex": nonce_d.hex(), "nowMs": t}
        if conn is not None:
            e["connId"] = conn
        return e

    def remote(approve, reveal=NONCE_S, conn=None):
        e = {"e": "RemoteDecision", "approve": approve}
        if reveal is not None:
            e["revealHex"] = reveal.hex()
        if conn is not None:
            e["connId"] = conn
        return e

    def local(approve, code=None):
        e = {"e": "LocalDecision", "approve": approve}
        if code is not None:
            e["typedCode"] = code
        return e

    def ack(transcript=None, conn=None):
        e = {"e": "AckReceived", "transcriptHex": (transcript if transcript is not None else TRANSCRIPT).hex()}
        if conn is not None:
            e["connId"] = conn
        return e

    CH = {"e": "ChallengeSent"}
    PT = ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "AWAIT_NONE:PromptTypedCode"]
    COMMIT_ROW = "COMMITTING:SendDecision(true),WritePairedRow"

    def fsm3(num, desc, script, trace, profile=None):
        inp = {"kind": "fsm", "machine": "D", "pinOwn": T.b64u(PIN_D), "script": script}
        if profile is not None:
            inp["profile"] = profile
        w04.add(num, desc, inp, {"ok": {"trace": trace}})

    def p0(conn=None, t_open=1000, t_hello=5000):
        return [d_open(t_open), h3(t_hello, conn=conn), CH]

    n = 700
    fsm3(n, "D (R3) happy path: the opening arrives first, then the code typed from S's screen; D shows no code; the row, the commit, the acknowledgement", p0() + [
        remote(True), local(True, CODE), {"e": "RowDurable", "nowMs": 6000}, ack()],
        PT + ["AWAIT_REMOTE:", COMMIT_ROW, "AWAIT_ACK:" + SEND_COMMIT, "CLOSED:Done,Closed(done)"], profile="r3"); n += 1
    fsm3(n, "D (R3): the person types the code before S's opening has arrived; it is kept and judged when the opening comes", p0() + [
        local(True, "865412"), remote(True), {"e": "RowDurable", "nowMs": 6000}],
        PT + ["AWAIT_NONE:", COMMIT_ROW, "AWAIT_ACK:" + SEND_COMMIT]); n += 1
    fsm3(n, "D (R3): the typed code may contain a no-break space and spaces (ignored)", p0() + [remote(True), local(True, " 865 412 ")],
        PT + ["AWAIT_REMOTE:", COMMIT_ROW]); n += 1
    fsm3(n, "D (R3): a wrong code is counted and warned, a right one then pairs", p0() + [remote(True), local(True, "000 000"), local(True, CODE)],
        PT + ["AWAIT_REMOTE:", "AWAIT_REMOTE:Warn(wrong-code)", COMMIT_ROW]); n += 1
    fsm3(n, "D (R3): the third wrong code declines and aborts (TRIES_EXHAUSTED); no row", p0() + [remote(True), local(True, "000 000"), local(True, "111 111"), local(True, "222 222")],
        PT + ["AWAIT_REMOTE:", "AWAIT_REMOTE:Warn(wrong-code)", "AWAIT_REMOTE:Warn(wrong-code)", "CLOSED:SendDecision(false),Abort(TRIES_EXHAUSTED,PAIRING_REFUSED)"]); n += 1
    fsm3(n, "D (R3): an approval without a typed code is ignored with a warning", p0() + [remote(True), local(True)],
        PT + ["AWAIT_REMOTE:", "AWAIT_REMOTE:Warn(typed-code-required)"]); n += 1
    fsm3(n, "D (R3): an approval without a typed code before the opening is ignored with a warning too", p0() + [local(True)],
        PT + ["AWAIT_NONE:Warn(typed-code-required)"]); n += 1
    fsm3(n, "D (R3): the code predicted from the wire (the SAS over the commitment) is the wrong code, so a relay that grinds nonce_D on what S sent gains nothing", p0() + [
        remote(True), local(True, WIRE_SAS)],
        PT + ["AWAIT_REMOTE:", "AWAIT_REMOTE:Warn(wrong-code)"]); n += 1
    fsm3(n, "D (R3): an opening that does not open the commitment ends the ceremony (PROTOCOL_ERROR), also when the right code was typed first", p0() + [
        local(True, CODE), remote(True, reveal=b"\x01" * 32)],
        PT + ["AWAIT_NONE:", "CLOSED:Abort(PROTOCOL,PROTOCOL_ERROR)"]); n += 1
    fsm3(n, "D (R3): an approval that carries no opening ends the ceremony", p0() + [remote(True, reveal=None)], PT + ["CLOSED:Abort(PROTOCOL,PROTOCOL_ERROR)"]); n += 1
    fsm3(n, "D (R3): an opening of 16 bytes ends the ceremony", p0() + [remote(True, reveal=NONCE_S[:16])], PT + ["CLOSED:Abort(PROTOCOL,PROTOCOL_ERROR)"]); n += 1
    fsm3(n, "D (R3): the commitment itself offered as the opening ends the ceremony", p0() + [remote(True, reveal=C_S)], PT + ["CLOSED:Abort(PROTOCOL,PROTOCOL_ERROR)"]); n += 1
    fsm3(n, "D (R3): S declines (no opening is sent with a decline)", p0() + [remote(False, reveal=None)], PT + ["CLOSED:Abort(DECLINED_REMOTE,PAIRING_REFUSED)"]); n += 1
    fsm3(n, "D (R3): the local user declines", p0() + [local(False)], PT + ["CLOSED:SendDecision(false),Abort(DECLINED_LOCAL,PAIRING_REFUSED)"]); n += 1
    fsm3(n, "D (R3): the local user cancels", p0() + [{"e": "UserCancel"}], PT + ["CLOSED:SendDecision(false),Abort(DECLINED_LOCAL,PAIRING_REFUSED)"]); n += 1
    fsm3(n, "D (R3) (L1): the peer's approval and opening alone never write a row; a lost connection ends the ceremony with no row", p0() + [remote(True), {"e": "ConnectionLost"}],
        PT + ["AWAIT_REMOTE:", "CLOSED:Abort(CONNECTION_LOST,-)"]); n += 1
    fsm3(n, "D (R3) (L1): the typed code alone never writes a row", p0() + [local(True, CODE), {"e": "ConnectionLost"}], PT + ["AWAIT_NONE:", "CLOSED:Abort(CONNECTION_LOST,-)"]); n += 1
    fsm3(n, "D (R3): the decisions time out 120 s after the hello was consumed", p0() + [{"e": "Tick", "nowMs": 124999}, {"e": "Tick", "nowMs": 125000}],
        PT + ["AWAIT_NONE:", "CLOSED:Abort(TIMEOUT,PAIRING_REFUSED)"]); n += 1
    fsm3(n, "D (R3): a second opening after the first was accepted changes nothing", p0() + [remote(True), remote(True), local(True, CODE)],
        PT + ["AWAIT_REMOTE:", "AWAIT_REMOTE:", COMMIT_ROW]); n += 1

    # connection binding (R4-T-10): the decision and the acknowledgement count only on the connection of the hello
    fsm3(n, "D (R3): a decision from another connection is refused and changes nothing; the bound connection then proceeds to the acknowledgement", p0(conn=7) + [
        remote(True, conn=8), remote(True, conn=7), local(True, CODE), {"e": "RowDurable", "nowMs": 6000}, ack(conn=9), ack(conn=7)],
        PT + ["AWAIT_NONE:Refuse(PROTOCOL_ERROR)", "AWAIT_REMOTE:", COMMIT_ROW, "AWAIT_ACK:" + SEND_COMMIT, "AWAIT_ACK:Refuse(PROTOCOL_ERROR)", "CLOSED:Done,Closed(done)"]); n += 1
    fsm3(n, "D (R3): a decline from another connection does not abort the ceremony", p0(conn=7) + [remote(False, reveal=None, conn=8)],
        PT + ["AWAIT_NONE:Refuse(PROTOCOL_ERROR)"]); n += 1
    fsm3(n, "D (R3): a decision from another connection while COMMITTING and while waiting for the acknowledgement is refused", p0(conn=7) + [
        remote(True, conn=7), local(True, CODE), remote(True, conn=8), {"e": "RowDurable", "nowMs": 6000}, remote(False, reveal=None, conn=8), ack(conn=7)],
        PT + ["AWAIT_REMOTE:", COMMIT_ROW, "COMMITTING:Refuse(PROTOCOL_ERROR)", "AWAIT_ACK:" + SEND_COMMIT, "AWAIT_ACK:Refuse(PROTOCOL_ERROR)", "CLOSED:Done,Closed(done)"]); n += 1
    fsm3(n, "D (R3): an acknowledgement from another connection before the commit is refused and does not end the ceremony", p0(conn=7) + [ack(conn=8), remote(True, conn=7)],
        PT + ["AWAIT_NONE:Refuse(PROTOCOL_ERROR)", "AWAIT_REMOTE:"]); n += 1
    fsm3(n, "D (R3): a decision from another connection in the CONSUMED state is refused, one from the bound connection is a protocol error", [d_open(0), h3(1, conn=7), remote(True, conn=8), remote(True, conn=7)],
        ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "CONSUMED:Refuse(PROTOCOL_ERROR)", "CLOSED:Abort(PROTOCOL,PROTOCOL_ERROR)"]); n += 1

    # the ceremony's tail under R3
    tail = p0() + [remote(True), local(True, CODE)]
    tail_trace = PT + ["AWAIT_REMOTE:", COMMIT_ROW]
    fsm3(n, "D (R3): the acknowledgement times out after 10 s; the row stays PAIRED but unconfirmed", tail + [{"e": "RowDurable", "nowMs": 6000}, {"e": "Tick", "nowMs": 15999}, {"e": "Tick", "nowMs": 16000}],
        tail_trace + ["AWAIT_ACK:" + SEND_COMMIT, "AWAIT_ACK:", "CLOSED:MarkUnconfirmed(ack-timeout),Warn(may-not-have-finished),Closed(ack-timeout)"]); n += 1
    fsm3(n, "D (R3): an acknowledgement with a different transcript leaves the row unconfirmed and warns", tail + [{"e": "RowDurable", "nowMs": 6000}, ack(bytes(32))],
        tail_trace + ["AWAIT_ACK:" + SEND_COMMIT, "CLOSED:MarkUnconfirmed(transcript-mismatch),Warn(transcript-mismatch),Closed(transcript-mismatch)"]); n += 1
    fsm3(n, "D (R3): the connection is lost while waiting for the acknowledgement", tail + [{"e": "RowDurable", "nowMs": 6000}, {"e": "ConnectionLost"}],
        tail_trace + ["AWAIT_ACK:" + SEND_COMMIT, "CLOSED:MarkUnconfirmed(connection-lost),Warn(may-not-have-finished),Closed(connection-lost)"]); n += 1
    fsm3(n, "D (R3): a failed row write aborts the ceremony and sends no commit", tail + [{"e": "RowWriteFailed"}], tail_trace + ["CLOSED:Abort(WRITE_FAILED,PAIRING_REFUSED)"]); n += 1
    fsm3(n, "D (R3): an acknowledgement before any commit is a protocol error and leaves the row unconfirmed", tail + [ack()],
        tail_trace + ["CLOSED:MarkUnconfirmed(acknowledgement-before-commit),Abort(PROTOCOL,PROTOCOL_ERROR)"]); n += 1
    fsm3(n, "D (R3): a second unknown connection raises a warning and changes nothing", p0() + [{"e": "SecondUnknownConnection"}], PT + ["AWAIT_NONE:Warn(second-connection)"]); n += 1
    fsm3(n, "D (R3): an acknowledgement in the challenge state is a protocol error", p0() + [ack()], PT + ["CLOSED:Abort(PROTOCOL,PROTOCOL_ERROR)"]); n += 1

    # the part of the ceremony before the challenge is the same under both profiles; the hello carries the commitment and the proof binds it
    fsm3(n, "D (R3): a hello whose proof binds the raw nonce instead of the commitment is refused", [d_open(0), h3(1, proof=T.proof(SECRET, PIN_D, PIN_S, NONCE_S))],
        ["OPEN:WindowOpened", "OPEN:Refuse(PAIRING_PROOF_INVALID)"]); n += 1
    fsm3(n, "D (R3): three invalid proofs close the window; a fourth hello meets a closed window", [d_open(0), h3(1, proof=bytes(32)), h3(2, proof=bytes(32)), h3(3, proof=bytes(32)), h3(4)],
        ["OPEN:WindowOpened", "OPEN:Refuse(PAIRING_PROOF_INVALID)", "OPEN:Refuse(PAIRING_PROOF_INVALID)", "CLOSED:Abort(TRIES_EXHAUSTED,PAIRING_PROOF_INVALID)", "CLOSED:Refuse(PAIRING_WINDOW_CLOSED)"]); n += 1
    fsm3(n, "D (R3): two invalid proofs then a valid one still pairs", [d_open(0), h3(1, proof=bytes(32)), h3(2, proof=bytes(32)), h3(3)],
        ["OPEN:WindowOpened", "OPEN:Refuse(PAIRING_PROOF_INVALID)", "OPEN:Refuse(PAIRING_PROOF_INVALID)", "CONSUMED:SendChallenge"]); n += 1
    fsm3(n, "D (R3) (L6): a window accepts at most one valid proof; a second valid hello is refused", [d_open(0), h3(1), h3(2, pin_s=S2), h3(3)],
        ["OPEN:WindowOpened", "CONSUMED:SendChallenge", "CONSUMED:Refuse(PAIRING_WINDOW_CLOSED)", "CONSUMED:Refuse(PAIRING_WINDOW_CLOSED)"]); n += 1
    fsm3(n, "D (R3) (L7): a REVOKED pin is refused with a valid proof; the window closes and the user is warned", [d_open(0), h3(1, status="REVOKED")],
        ["OPEN:WindowOpened", "CLOSED:Warn(revoked-device-tried-to-pair),Abort(REVOKED_PEER,PAIRING_REFUSED)"]); n += 1
    fsm3(n, "D (R3) (L7): a REVOKED pin with an INVALID proof is refused for the same reason (the status is checked first)", [d_open(0), h3(1, proof=bytes(32), status="REVOKED")],
        ["OPEN:WindowOpened", "CLOSED:Warn(revoked-device-tried-to-pair),Abort(REVOKED_PEER,PAIRING_REFUSED)"]); n += 1
    fsm3(n, "D (R3): a PAIRED pin cannot pair again", [d_open(0), h3(1, status="PAIRED")], ["OPEN:WindowOpened", "CLOSED:Abort(PEER_EXISTS,PAIRING_REFUSED)"]); n += 1
    fsm3(n, "D (R3) (L4): a SUSPENDED pin cannot be moved to PAIRED by a pairing ceremony", [d_open(0), h3(1, status="SUSPENDED")], ["OPEN:WindowOpened", "CLOSED:Abort(PEER_EXISTS,PAIRING_REFUSED)"]); n += 1
    fsm3(n, "D (R3) (L5): a corrupt registry status fails closed", [d_open(0), h3(1, status="CORRUPT")], ["OPEN:WindowOpened", "CLOSED:Abort(REGISTRY_UNREADABLE,PAIRING_REFUSED)"]); n += 1
    fsm3(n, "D (R3): an unreadable registry fails closed", [d_open(0), h3(1, status="UNREADABLE")], ["OPEN:WindowOpened", "CLOSED:Abort(REGISTRY_UNREADABLE,PAIRING_REFUSED)"]); n += 1
    fsm3(n, "D (R3): the window expires after 120 s", [d_open(0), {"e": "Tick", "nowMs": 119999}, {"e": "Tick", "nowMs": 120000}], ["OPEN:WindowOpened", "OPEN:", "CLOSED:Closed(window-expired)"]); n += 1
    fsm3(n, "D (R3): a hello at the expiry instant is refused as a closed window", [d_open(0), h3(120000)], ["OPEN:WindowOpened", "CLOSED:Refuse(PAIRING_WINDOW_CLOSED),Closed(window-expired)"]); n += 1
    fsm3(n, "D (R3): the user cancels the open window", [d_open(0), {"e": "UserCancel"}], ["OPEN:WindowOpened", "CLOSED:Closed(window-cancelled)"]); n += 1
    assert n < 760, n

    # ------------------------------------------------------------------------------------------------------------------------------ S
    S3_HELLO = "SendHello(%s)" % T.proof(SECRET, PIN_D, PIN_S, C_S).hex()[:8]
    assert S3_HELLO != g["S_HELLO"]

    def sfsm3(num, desc, script, trace, profile=None):
        inp = {"kind": "fsm", "machine": "S", "pinOwn": T.b64u(PIN_S), "script": script}
        if profile is not None:
            inp["profile"] = profile
        w04.add(num, desc, inp, {"ok": {"trace": trace}})

    BEGIN = [s_scan(), {"e": "UserConfirmConnect", "yes": True, "nowMs": 0}, s_dial(PIN_D)]
    BEGIN_T = ["CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)", "DIALING:Dial(192.168.1.40:11436)", "SENT_HELLO:" + S3_HELLO]
    AWAIT = BEGIN + [{"e": "ChallengeReceived", "nonceDHex": NONCE_D.hex(), "nowMs": 200}]
    AWAIT_T = BEGIN_T + ["AWAIT_NONE:ShowConsent(865 412)"]

    n = 760
    sfsm3(n, "S (R3) happy path: the hello carries the commitment, S opens the nonce with its approval, then the commit, the row and the acknowledgement", AWAIT + [
        {"e": "RemoteDecision", "approve": True}, {"e": "LocalDecision", "approve": True}, {"e": "CommitReceived", "transcriptHex": TRANSCRIPT.hex()}, {"e": "RowDurable"}],
        AWAIT_T + ["AWAIT_REMOTE:", "AWAIT_COMMIT:SendDecision(true,reveal)", "WRITING:WritePairedRow", "DONE:SendAck(%s),Done" % T8], profile="r3"); n += 1
    sfsm3(n, "S (R3): local approval first (the opening leaves with it), then the remote one", AWAIT + [{"e": "LocalDecision", "approve": True}, {"e": "RemoteDecision", "approve": True}],
        AWAIT_T + ["AWAIT_LOCAL:SendDecision(true,reveal)", "AWAIT_COMMIT:"]); n += 1
    sfsm3(n, "S (R3): D answers PAIRING_WINDOW_CLOSED to the hello: the window expired on the other device, not a decline", BEGIN + [{"e": "ErrorReceived", "code": "PAIRING_WINDOW_CLOSED"}],
        BEGIN_T + ["FAILED:Abort(WINDOW_EXPIRED,-)"]); n += 1
    sfsm3(n, "S (R3): any other error code in answer to the hello is D declining", BEGIN + [{"e": "ErrorReceived", "code": "PAIRING_PROOF_INVALID"}], BEGIN_T + ["FAILED:Abort(DECLINED_REMOTE,-)"]); n += 1
    sfsm3(n, "S (R3): the local user declines and no opening is sent", AWAIT + [{"e": "LocalDecision", "approve": False}], AWAIT_T + ["FAILED:SendDecision(false),Abort(DECLINED_LOCAL,-)"]); n += 1
    sfsm3(n, "S (R3): the user cancels while the decisions are open and no opening is sent", AWAIT + [{"e": "UserCancel"}], AWAIT_T + ["FAILED:SendDecision(false),Abort(DECLINED_LOCAL,-)"]); n += 1
    sfsm3(n, "S (R3): the remote user declines", AWAIT + [{"e": "RemoteDecision", "approve": False}], AWAIT_T + ["FAILED:Abort(DECLINED_REMOTE,-)"]); n += 1
    sfsm3(n, "S (R3): no decision within 120 s of the challenge", AWAIT + [{"e": "Tick", "nowMs": 120199}, {"e": "Tick", "nowMs": 120200}], AWAIT_T + ["AWAIT_NONE:", "FAILED:Abort(TIMEOUT,-)"]); n += 1
    sfsm3(n, "S (R3): there is no timeout while the hello is unanswered beyond the dial budget", BEGIN + [{"e": "Tick", "nowMs": 10_000_000}], BEGIN_T + ["SENT_HELLO:"]); n += 1
    sfsm3(n, "S (R3): a commit whose transcript differs from the one S computed from the opened nonce writes no row", AWAIT + [
        {"e": "RemoteDecision", "approve": True}, {"e": "LocalDecision", "approve": True}, {"e": "CommitReceived", "transcriptHex": bytes(32).hex()}],
        AWAIT_T + ["AWAIT_REMOTE:", "AWAIT_COMMIT:SendDecision(true,reveal)", "FAILED:Abort(TRANSCRIPT_MISMATCH,-)"]); n += 1
    sfsm3(n, "S (R3) (L1): a commit before the local approval is a protocol error and writes no row", AWAIT + [{"e": "CommitReceived", "transcriptHex": TRANSCRIPT.hex()}],
        AWAIT_T + ["FAILED:Abort(PROTOCOL,-)"]); n += 1
    sfsm3(n, "S (R3): the row write fails after a matching commit", AWAIT + [
        {"e": "RemoteDecision", "approve": True}, {"e": "LocalDecision", "approve": True}, {"e": "CommitReceived", "transcriptHex": TRANSCRIPT.hex()}, {"e": "RowWriteFailed"}],
        AWAIT_T + ["AWAIT_REMOTE:", "AWAIT_COMMIT:SendDecision(true,reveal)", "WRITING:WritePairedRow", "FAILED:Abort(WRITE_FAILED,-)"]); n += 1
    sfsm3(n, "S (R3): the connection is lost while waiting for the decisions", AWAIT + [{"e": "ConnectionLost"}], AWAIT_T + ["FAILED:Abort(CONNECTION_LOST,-)"]); n += 1
    sfsm3(n, "S (R3): the first endpoint is unreachable, the second answers (the hello is built at the successful dial)", [s_scan(), {"e": "UserConfirmConnect", "yes": True, "nowMs": 0}, s_dial(None), s_dial(PIN_D)],
        ["CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)", "DIALING:Dial(192.168.1.40:11436)", "DIALING:Dial(100.101.7.9:11436)", "SENT_HELLO:" + S3_HELLO]); n += 1
    sfsm3(n, "S (R3): the first endpoint presents a different pin, the second is right", [s_scan(), {"e": "UserConfirmConnect", "yes": True, "nowMs": 0}, s_dial(PIN_S), s_dial(PIN_D)],
        ["CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)", "DIALING:Dial(192.168.1.40:11436)", "DIALING:Dial(100.101.7.9:11436)", "SENT_HELLO:" + S3_HELLO]); n += 1
    sfsm3(n, "S (R3): every endpoint fails", [s_scan(), {"e": "UserConfirmConnect", "yes": True, "nowMs": 0}, s_dial(None), s_dial(PIN_S)],
        ["CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)", "DIALING:Dial(192.168.1.40:11436)", "DIALING:Dial(100.101.7.9:11436)", "FAILED:Abort(CONNECTION_LOST,-)"]); n += 1
    sfsm3(n, "S (R3): the user declines the connection", [s_scan(), {"e": "UserConfirmConnect", "yes": False, "nowMs": 0}], ["CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)", "IDLE:"]); n += 1

    # the scanner does not judge the QR expiry (R4-T-10, design T8)
    sfsm3(n, "S (R3): a URI past x + 60 s on the scanner's clock is accepted: D's PAIRING_WINDOW_CLOSED is authoritative", [s_scan(now=QNOW + 120 + 60)], ["CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)"]); n += 1
    sfsm3(n, "S (R3): a URI whose x is a day ahead of the scanner's clock is accepted", [s_scan(now=QNOW - 100000)], ["CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)"]); n += 1
    sfsm3(n, "S (R3): a past-expiry URI with a public address is still refused at the scan", [s_scan(uri(a="8.8.8.8:1"), now=QNOW + 100000)], ["IDLE:ScanRejected(ADDRESS_NOT_ELIGIBLE)"]); n += 1
    sfsm3(n, "S (R3): a past-expiry URI, the window long closed on D, ends at D's answer", [s_scan(now=QNOW + 100000), {"e": "UserConfirmConnect", "yes": True, "nowMs": 0}, s_dial(PIN_D), {"e": "ErrorReceived", "code": "PAIRING_WINDOW_CLOSED"}],
        ["CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)", "DIALING:Dial(192.168.1.40:11436)", "SENT_HELLO:" + S3_HELLO, "FAILED:Abort(WINDOW_EXPIRED,-)"]); n += 1
    assert n < 800, n

    # ------------------------------------------------------------------------------------------------------------------------------ the commitment function
    def cvec(num, desc, nonce, revealed, commitment=None, origin="hand"):
        inp = {"kind": "commit", "nonceS": T.b64u(nonce), "revealed": revealed}
        if commitment is not None:
            inp["commitment"] = T.b64u(commitment)
        c = T.commit_nonce(nonce)
        wire = commitment if commitment is not None else c
        rv = T.unb64u(revealed) or b""
        w04.add(num, desc, inp, {"ok": {"commitment": T.b64u(c), "opens": T.commit_opens(wire, rv)}}, origin=origin)

    n = 460
    cvec(n, "R4-T-10: C = SHA-256(\"asom-pair-v1/commit\" 0x00 nonce_S) for the worked nonce (0xa5 x 32); its own nonce opens it", NONCE_S, T.b64u(NONCE_S)); n += 1
    cvec(n, "another nonce does not open the commitment", NONCE_S, T.b64u(NONCE_D)); n += 1
    cvec(n, "the commitment offered as its own opening does not open it", NONCE_S, T.b64u(C_S)); n += 1
    cvec(n, "an opening of 31 bytes does not open it", NONCE_S, T.b64u(NONCE_S[:31])); n += 1
    cvec(n, "an opening of 33 bytes does not open it", NONCE_S, T.b64u(NONCE_S + b"\x00")); n += 1
    cvec(n, "an empty opening does not open it", NONCE_S, ""); n += 1
    cvec(n, "an opening that is not canonical base64url (non-zero trailing bits) does not open it", NONCE_S, T.b64u(NONCE_S)[:-1] + "B"); n += 1
    cvec(n, "an opening with padding does not open it", NONCE_S, T.b64u(NONCE_S) + "="); n += 1
    cvec(n, "a commitment of another nonce on the wire is not opened by this nonce", NONCE_S, T.b64u(NONCE_S), commitment=T.commit_nonce(NONCE_D)); n += 1
    assert n < 470, n
    for i in range(20):
        rng = random.Random(7000 + i)
        ns, other = rng.randbytes(32), rng.randbytes(32)
        cvec(480 + i, "seeded random case %d (random.Random(7000 + i)): the nonce opens its commitment" % (i + 1), ns, T.b64u(ns), origin="generated")
        cvec(800 + i, "seeded random case %d: another nonce does not open the commitment" % (i + 1), ns, T.b64u(other), origin="generated")

    # ------------------------------------------------------------------------------------------------------------------------------ the PAIR_DECISION reveal member
    NS_REVEAL = T.b64u(NONCE_S)

    def mdec(num, desc, obj, ok=True, normal=None, code="PROTOCOL_ERROR"):
        text = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
        inp = {"kind": "message", "type": "PAIR_DECISION", "payload": text}
        if ok:
            norm = normal if normal is not None else json.dumps(json.loads(text), sort_keys=True, separators=(",", ":"), ensure_ascii=False)
            w04.add(num, desc, inp, {"ok": {"normal": norm}})
        else:
            w04.add(num, desc, inp, {"reject": code})

    n = 450
    mdec(n, "PAIR_DECISION approve true with the 32-byte opening of nonce_S (R4-T-10)", {"v": 1, "approve": True, "reveal": NS_REVEAL}); n += 1
    mdec(n, "PAIR_DECISION approve false carrying an opening is well formed (D aborts on the decline whatever it carries)", {"v": 1, "approve": False, "reveal": NS_REVEAL}); n += 1
    mdec(n, "PAIR_DECISION: an opening of 31 bytes is refused", {"v": 1, "approve": True, "reveal": T.b64u(NONCE_S[:31])}, ok=False); n += 1
    mdec(n, "PAIR_DECISION: an opening of 33 bytes is refused", {"v": 1, "approve": True, "reveal": T.b64u(NONCE_S + b"\x01")}, ok=False); n += 1
    mdec(n, "PAIR_DECISION: an opening with padding is refused", {"v": 1, "approve": True, "reveal": NS_REVEAL[:-1] + "="}, ok=False); n += 1
    mdec(n, "PAIR_DECISION: an opening given as hex is refused", {"v": 1, "approve": True, "reveal": NONCE_S.hex()}, ok=False); n += 1
    mdec(n, "PAIR_DECISION: an opening that is a number is refused", {"v": 1, "approve": True, "reveal": 5}, ok=False); n += 1
    mdec(n, "PAIR_DECISION: an opening that is null is refused", '{"v":1,"approve":true,"reveal":null}', ok=False); n += 1
    mdec(n, "PAIR_DECISION: an unknown member next to the opening is ignored and not kept", {"v": 1, "approve": True, "reveal": NS_REVEAL, "note": "x"},
         normal='{"approve":true,"reveal":"%s","v":1}' % NS_REVEAL); n += 1
    assert n < 460, n
