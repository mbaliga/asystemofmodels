#!/usr/bin/env python3
"""Runs a command on a real pseudo-terminal (so /dev/tty exists for it), answers its prompt, and returns its exit code.

    pty-run.py <answer-or--> -- <command> [args...]

`-` means: do not answer (the command waits, then the EOF from a closed pty ends it). Prints everything the command wrote
to the terminal, then `exit=<code>`. Used to test install/uninstall paths that must read a confirmation from /dev/tty.
"""
import os
import pty
import select
import sys
import time


def main():
    answer = sys.argv[1]
    cmd = sys.argv[sys.argv.index("--") + 1:]
    pid, fd = pty.fork()
    if pid == 0:
        os.execvp(cmd[0], cmd)
    out = b""
    sent = answer == "-"
    status = None
    deadline = time.time() + 60
    while time.time() < deadline:
        r, _, _ = select.select([fd], [], [], 0.2)
        if r:
            try:
                data = os.read(fd, 4096)
            except OSError:
                break
            if not data:
                break
            out += data
            if not sent and out.rstrip().endswith(b">"):
                os.write(fd, answer.encode() + b"\n")
                sent = True
        else:
            done, st = os.waitpid(pid, os.WNOHANG)
            if done:
                status = st
                break
    if status is None:
        _, status = os.waitpid(pid, 0)
    sys.stdout.write(out.decode(errors="replace").replace("\r\n", "\n"))
    code = os.waitstatus_to_exitcode(status)
    print(f"exit={code}")


if __name__ == "__main__":
    main()
