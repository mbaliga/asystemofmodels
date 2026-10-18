#!/usr/bin/env python3
"""Records REAL D-Bus wire bytes into recorded.txt (linux.md 10.2: "recorded PrepareForSleep messages, LE and BE").

Regenerating is manual and rare; the committed recorded.txt is the vector file the Kotlin tests read. This script is an
INDEPENDENT implementation (Python `struct`, no code shared with the Kotlin client): it starts a private dbus-daemon,
plays a fake logind (owning org.freedesktop.login1) in little-endian and in big-endian, plays a spoofer, and writes down
exactly the bytes the daemon delivered to a subscriber. The daemon is the arbiter of what is well formed.

  python3 record_vectors.py > recorded.txt

Needs /usr/bin/dbus-daemon. Standard library only.
"""
import os
import select
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time

LOGIND = "org.freedesktop.login1"
PATH = "/org/freedesktop/login1"
IFACE = "org.freedesktop.login1.Manager"
MATCH = ("type='signal',sender='org.freedesktop.login1',interface='org.freedesktop.login1.Manager',"
         "member='PrepareForSleep',path='/org/freedesktop/login1'")

CONFIG = """<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<busconfig>
  <listen>unix:path=%s</listen>
  <auth>EXTERNAL</auth>
  <policy context="default">
    <allow send_destination="*" eavesdrop="true"/>
    <allow eavesdrop="true"/>
    <allow own="*"/>
    <allow user="*"/>
  </policy>
</busconfig>
"""


class M:
    def __init__(self, e, base=0):
        self.e, self.b, self.base = e, bytearray(), base

    def align(self, n):
        self.b += b"\0" * ((-(self.base + len(self.b))) % n)

    def u8(self, v):
        self.b.append(v)

    def u32(self, v):
        self.align(4)
        self.b += struct.pack(self.e + "I", v)

    def s(self, v):
        d = v.encode()
        self.u32(len(d))
        self.b += d + b"\0"

    def g(self, v):
        d = v.encode()
        self.b.append(len(d))
        self.b += d + b"\0"


def message(e, mtype, serial, fields, body=b"", flags=0):
    fm = M(e, base=16)
    for code, sig, val in fields:
        fm.align(8)
        fm.u8(code)
        fm.g(sig)
        if sig in ("o", "s"):
            fm.s(val)
        elif sig == "g":
            fm.g(val)
        elif sig == "u":
            fm.u32(val)
        else:
            raise ValueError(sig)
    hdr = struct.pack(e + "BBBBIII", ord("l" if e == "<" else "B"), mtype, flags, 1, len(body), serial, len(fm.b))
    out = hdr + bytes(fm.b)
    out += b"\0" * ((-len(out)) % 8)
    return out + body


def call(e, serial, member, arg=None, iface="org.freedesktop.DBus"):
    fields = [(1, "o", "/org/freedesktop/DBus"), (2, "s", iface), (3, "s", member), (6, "s", "org.freedesktop.DBus")]
    body = b""
    if arg is not None:
        fields.append((8, "g", "su" if isinstance(arg, tuple) else "s"))
        m = M(e)
        if isinstance(arg, tuple):
            m.s(arg[0])
            m.u32(arg[1])
        else:
            m.s(arg)
        body = bytes(m.b)
    return message(e, 1, serial, fields, body)


def signal(e, serial, value, destination=None):
    fields = [(1, "o", PATH), (2, "s", IFACE), (3, "s", "PrepareForSleep")]
    if destination:
        fields.append((6, "s", destination))
    fields.append((8, "g", "b"))
    return message(e, 4, serial, fields, struct.pack(e + "I", 1 if value else 0))


def read_exact(sock, n, timeout):
    buf = b""
    end = time.time() + timeout
    while len(buf) < n:
        left = end - time.time()
        if left <= 0 or not select.select([sock], [], [], left)[0]:
            raise TimeoutError("timed out")
        d = sock.recv(n - len(buf))
        if not d:
            raise EOFError
        buf += d
    return buf


def read_message(sock, timeout=3.0):
    head = read_exact(sock, 16, timeout)
    e = "<" if head[0:1] == b"l" else ">"
    body_len, _serial, fields_len = struct.unpack(e + "III", head[4:8] + head[8:12] + head[12:16])
    total = 16 + fields_len
    total += (-total) % 8
    total += body_len
    return head + read_exact(sock, total - 16, timeout)


def classify(msg):
    """(type, member or None, reply_serial or None) with a throwaway parse used only to label the recording."""
    e = "<" if msg[0:1] == b"l" else ">"
    mtype = msg[1]
    fields_len = struct.unpack(e + "I", msg[12:16])[0]
    pos, end, member, reply = 16, 16 + fields_len, None, None
    while pos < end:
        pos += (-pos) % 8
        code = msg[pos]
        siglen = msg[pos + 1]
        sig = msg[pos + 2:pos + 2 + siglen].decode()
        pos += 2 + siglen + 1
        if sig in ("s", "o"):
            pos += (-pos) % 4
            n = struct.unpack(e + "I", msg[pos:pos + 4])[0]
            val = msg[pos + 4:pos + 4 + n].decode()
            pos += 4 + n + 1
            if code == 3:
                member = val
        elif sig == "u":
            pos += (-pos) % 4
            val = struct.unpack(e + "I", msg[pos:pos + 4])[0]
            pos += 4
            if code == 5:
                reply = val
        elif sig == "g":
            pos += 1 + msg[pos] + 1
    return mtype, member, reply


class Client:
    def __init__(self, path, e="<"):
        self.e, self.serial = e, 0
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect(path)
        self.sock.sendall(b"\0AUTH EXTERNAL " + str(os.getuid()).encode().hex().encode() + b"\r\n")
        line = b""
        while not line.endswith(b"\r\n"):
            line += self.sock.recv(1)
        assert line.startswith(b"OK "), line
        self.sock.sendall(b"BEGIN\r\n")
        self.received = []  # every message the daemon sent us after BEGIN, in order
        self.unique = None

    def rpc(self, member, arg=None):
        self.serial += 1
        self.sock.sendall(call(self.e, self.serial, member, arg))
        while True:
            m = read_message(self.sock)
            self.received.append(m)
            t, _mem, reply = classify(m)
            if t in (2, 3) and reply == self.serial:
                return m

    def hello(self):
        m = self.rpc("Hello")
        e = "<" if m[0:1] == b"l" else ">"
        start = (16 + struct.unpack(e + "I", m[12:16])[0] + 7) // 8 * 8
        body = m[start:]
        n = struct.unpack(e + "I", body[0:4])[0]
        self.unique = body[4:4 + n].decode()
        return m

    def emit(self, value, destination=None):
        self.serial += 1
        self.sock.sendall(signal(self.e, self.serial, value, destination))

    def drain(self, timeout=1.0):
        got = []
        while True:
            try:
                m = read_message(self.sock, timeout)
            except TimeoutError:
                return got
            self.received.append(m)
            got.append(m)


def main():
    if not shutil.which("dbus-daemon"):
        sys.exit("dbus-daemon not found")
    work = tempfile.mkdtemp(prefix="asom-dbus-rec-")
    bus = os.path.join(work, "bus")
    cfg = os.path.join(work, "bus.conf")
    open(cfg, "w").write(CONFIG % bus)
    daemon = subprocess.Popen(["dbus-daemon", "--config-file=" + cfg, "--nofork", "--print-address=1"], stdout=subprocess.PIPE)
    daemon.stdout.readline()
    version = subprocess.run(["dbus-daemon", "--version"], capture_output=True, text=True).stdout.splitlines()[0]
    rows = []

    def add(name, kind, expect, msg):
        rows.append((name, kind, expect, msg.hex()))

    try:
        a = Client(bus)
        hello = a.hello()
        add("hello-reply-le", "method-return", "reply=1;unique=" + a.unique, hello)
        add_match = a.rpc("AddMatch", MATCH)
        add("addmatch-reply-le", "method-return", "reply=2", add_match)
        add("call-hello-le", "outbound-call", "serial=1;member=Hello", call("<", 1, "Hello"))
        add("call-addmatch-le", "outbound-call", "serial=2;member=AddMatch;arg=" + MATCH, call("<", 2, "AddMatch", MATCH))
        name_acquired = [m for m in a.received if classify(m)[1] == "NameAcquired"]
        assert name_acquired, "expected a NameAcquired signal unicast to the subscriber"
        add("nameacquired-unicast-le", "signal-ignored", "unicast-from-bus", name_acquired[0])

        for tag, e in (("le", "<"), ("be", ">")):
            logind = Client(bus, e)
            logind.hello()
            logind.serial += 1
            logind.sock.sendall(call(e, logind.serial, "RequestName", (LOGIND, 0)))
            logind.drain(0.5)
            before = len(a.received)
            logind.emit(True)
            got = a.drain(0.5)
            assert len(got) == 1, "subscriber should get the true signal (%s)" % tag
            add("prepare-for-sleep-true-" + tag, "signal-accepted", "true;endian=" + tag, got[0])
            logind.emit(False)
            got = a.drain(0.5)
            assert len(got) == 1
            add("prepare-for-sleep-false-" + tag, "signal-accepted", "false;endian=" + tag, got[0])
            logind.sock.close()
            time.sleep(0.3)
            a.drain(0.5)  # anything else the bus sent (nothing is subscribed)

        spoof = Client(bus)
        spoof.hello()
        spoof.emit(True)
        delivered = a.drain(1.0)
        assert delivered == [], "the bus delivered a broadcast from a non-logind name to a sender-filtered match"
        spoof.emit(True, destination=a.unique)
        got = a.drain(1.0)
        assert len(got) == 1, "the bus should deliver a unicast signal addressed to us"
        add("forged-unicast-prepare-for-sleep-le", "signal-ignored", "unicast-forgery;endian=le", got[0])
    finally:
        daemon.terminate()
        daemon.wait()
        shutil.rmtree(work, ignore_errors=True)

    print("# REAL wire bytes recorded from " + version + " by record_vectors.py (independent Python implementation).")
    print("# Each line: id ; kind ; expectation ; hex of one complete message as the daemon delivered it to the subscriber")
    print("# (kind outbound-call: the bytes the Python reference encoder produces for a call the Kotlin client must produce identically).")
    print("# The forged-unicast row proves why the client drops unicast signals: the bus delivers them regardless of the match rule.")
    for r in rows:
        print(" ; ".join(r))


if __name__ == "__main__":
    main()
