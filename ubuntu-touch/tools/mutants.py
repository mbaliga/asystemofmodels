#!/usr/bin/env python3
"""Mutation checks for the Ubuntu Touch track: each mutant edits ONE production source (exact text, must match exactly once), runs the
whole test task, and restores the file. A mutant is KILLED when the tests fail and SURVIVED when they still pass (a hole in the tests).
A pattern that does not match exactly once, or a mutant that no longer compiles, is reported as such and never counted as killed.

    mutants.py [--kotlin] [--cpp] [--only NAME] [--tests CLASS_PATTERN] [--jdk-home DIR]

Kotlin mutants run `./gradlew -p ubuntu-touch/jvm :ut-host:test`; C++ mutants rebuild the plugin with THIS machine's Qt and run ctest.
Run it alone (it edits the tree). The work tree is restored from a copy taken before each edit, also on Ctrl-C. Standard library only."""
import os
import shutil
import subprocess
import sys

UT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
REPO = os.path.normpath(os.path.join(UT, ".."))
KT = "jvm/ut-host/src/main/kotlin/xyz/mdhv/asom/ut/"
PLUGIN = "plugin/"

# (name, file, old text, new text, what it breaks)
KOTLIN = [
    ("L-UT1-window", KT + "NodeLifecycle.kt", "const val ACTIVE_WINDOW_MS = 10_000L", "const val ACTIVE_WINDOW_MS = 10_001L", "the 10 s window is off by one ms"),
    ("watchdog-gap", KT + "NodeLifecycle.kt", "const val WATCHDOG_GAP_MS = 3_000L", "const val WATCHDOG_GAP_MS = 3_001L", "a gap of exactly 3001 ms is not a resume"),
    ("idle-close", KT + "NodeLifecycle.kt", "now - lastWorkMs >= IDLE_CLOSE_MS", "now - lastWorkMs > IDLE_CLOSE_MS", "the 5 min close is late by a millisecond"),
    ("dial-when-stopped", KT + "NodeLifecycle.kt", "fun mayDial(): Boolean = !stopped && state", "fun mayDial(): Boolean = state", "a stopped node may dial"),
    ("request-without-active-ui", KT + "NodeLifecycle.kt", "if (!uiRecentlyActive(now)) same(refused = UiErrorCode.INTERRUPTED_BY_SUSPEND)", "if (false) same(refused = UiErrorCode.INTERRUPTED_BY_SUSPEND)", "a request is accepted while the UI has not reported active (L-UT1)"),
    ("display-never-released", KT + "NodeLifecycle.kt", "if (bodies.isEmpty() && displayHeld) {", "if (false && displayHeld) {", "the display stays held after the last outcome (L-UT2)"),
    ("freeze-does-not-cancel", KT + "NodeLifecycle.kt", "if (open.isNotEmpty() && closedBy != \"suspend\") effects += LcEffect.CancelAttempts", "if (false) effects += LcEffect.CancelAttempts", "in-flight attempts are not cancelled when the app is backgrounded"),
    ("resume-cancels", KT + "NodeLifecycle.kt", "closedBy != \"suspend\"", "closedBy != \"xx\"", "a resume cancels attempts that are already dead"),
    ("line-cap-off-by-one", KT + "ControlChannel.kt", "if (acc.size().toLong() + chunk > cap) return Line.TooLong", "if (acc.size().toLong() + chunk > cap + 1) return Line.TooLong", "a line one byte over 1 MiB is accepted"),
    ("unknown-fields-allowed", KT + "ControlChannel.kt", "for ((k, _) in o.members) if (k !in allowed) throw BadFrameException(BadFrame.UNKNOWN_FIELD)", "for ((k, _) in o.members) if (false) throw BadFrameException(BadFrame.UNKNOWN_FIELD)", "the frame set is no longer closed"),
    ("int-range-lower-only", KT + "ControlChannel.kt", "if (n < min || n > max) throw BadFrameException(BadFrame.BAD_FIELD)\n            return n", "if (n < min) throw BadFrameException(BadFrame.BAD_FIELD)\n            return n", "integer upper bounds are not enforced"),
    ("writer-cap-removed", KT + "ControlChannel.kt", "if (bytes.size > CtlProtocol.MAX_LINE_BYTES) throw FrameTooLargeException()", "if (false) throw FrameTooLargeException()", "an over-cap frame is written"),
    ("projection-egress-from-served-class", KT + "UiProjection.kt", "val members = COLUMNS.map { it to (full[it] ?: error(\"row has no member $it\")) }", "val members = COLUMNS.map { it to ((if (it == \"egress\") full[\"servedClass\"] else full[it]) ?: error(\"row has no member $it\")) }", "the UI shows the served class where the ledger says reach (Invariant 9)"),
    ("alias-not-sanitised", KT + "UiProjection.kt", "out.appendCodePoint(if (bad) 0xFFFD else cp)", "out.appendCodePoint(cp)", "a peer name with a bidi override reaches the provenance line"),
    ("error-order-no-peer", KT + "ErrorMapping.kt", "if (peers.isEmpty()) return UiErrorCode.NO_PROVIDER_KEY", "if (peers.isEmpty()) return UiErrorCode.MODEL_UNKNOWN", "no paired peer answers MODEL_UNKNOWN"),
    ("error-local-only-after-peers", KT + "ErrorMapping.kt", "if (policy == Policy.LOCAL_ONLY) return UiErrorCode.LOCAL_ENGINE_ABSENT\n", "", "local-only is no longer refused"),
    ("secret-leak-into-frame", KT + "NodeSession.kt", "out.send(NodeFrame.Error(req.rid, error))\n            return", "out.send(NodeFrame.Error(req.messages.first().content, error))\n            return", "the prompt is echoed in an error frame (UTC05)"),
    ("stray-output-reaches-stdout", KT + "Stdio.kt", "System.setOut(sink)\n        System.setErr(sink)\n        return ChannelStreams", "System.setErr(sink)\n        return ChannelStreams", "a library's println can corrupt the channel"),
    ("crash-exit-code", KT + "Main.kt", "Runtime.getRuntime().halt(EXIT_INTERNAL)", "Runtime.getRuntime().halt(0)", "an internal error exits 0"),
    ("tls-trusts-any-key", KT + "SelfTest.kt", "if (!MessageDigest.isEqual(chain[0].publicKey.encoded, spki)) throw CertificateException(\"public key is not the pinned key\")", "", "the pin check is gone: a wrong key completes the handshake"),
    ("home-outside-allowed", KT + "UtPaths.kt", "if (!base.startsWith(home)) throw HostRefusedException(\"$name is outside HOME\")", "", "an XDG base outside HOME is accepted"),
    ("loose-dir-not-tightened", KT + "UtPaths.kt", "Files.setPosixFilePermissions(dir, PRIVATE_DIR)", "Unit", "a group-readable directory is left open"),
    ("ledger-tail-kept", KT + "FileLedger.kt", "if (cut < size) {", "if (false) {", "a torn tail stays in front of the next row (HLU-2)"),
    ("ledger-last-row-unchecked", KT + "FileLedger.kt", "                LedgerFile.checkLastRow(file)\n", "", "a damaged last row does not fail STARTING (HLU-2)"),
    ("ledger-rollback-skipped", KT + "FileLedger.kt", "                truncate(path, committed)\n", "", "a failed append leaves its bytes in the file (HLU-2)"),
    ("ledger-poison-skipped", KT + "FileLedger.kt", "                poisoned.set(t)\n", "", "a rollback that failed does not poison the ledger (HLU-2)"),
    ("ledger-budget-ignored", KT + "FileLedger.kt", "if (budget + size > ROWS_BUDGET) {", "if (false) {", "a rows frame may exceed the frame cap (HLU-2)"),
    ("ledger-limit-ignored", KT + "FileLedger.kt", "if (out.size >= limit) return out", "", "a query reads past the rows it returns (HLU-2)"),
    ("ledger-corrupt-uncaught", KT + "NodeSession.kt", "        } catch (e: CorruptRowException) {\n            return ledgerLost()\n", "        } catch (e: java.io.EOFException) {\n            return ledgerLost()\n", "a corrupt row crashes the node (HLU-2)"),
    ("ledger-toolarge-uncaught", KT + "NodeSession.kt", "        } catch (e: FrameTooLargeException) {\n            ledgerLost()\n", "        } catch (e: java.io.EOFException) {\n            ledgerLost()\n", "an over-size rows frame crashes the node (HLU-2)"),
    ("ledger-loss-keeps-state", KT + "NodeSession.kt", "        settle(lifecycle.apply(LcEvent.LedgerFailed))\n    }\n\n    private fun borrow", "    }\n\n    private fun borrow", "a lost ledger does not stop borrowing (HLU-2)"),
    ("selftest-under-lock", KT + "NodeSession.kt", "selfTestWorker.execute {", "run {", "the self-test holds the session lock (HLU-3)"),
    ("tick-stamped-after-lock", KT + "NodeSession.kt", "lifecycle.apply(LcEvent.Tick, wokeAt)", "lifecycle.apply(LcEvent.Tick)", "the watchdog measures the gap after it got the lock (HLU-3)"),
    ("selftest-not-awaited", KT + "NodeSession.kt", "if (d.frame == UiFrame.Shutdown) awaitSelfTests()", "", "a shutdown overtakes the self-test it follows (HLU-3)"),
    ("state-before-hello", KT + "NodeSession.kt", "if (!greeted) return\n", "", "a frame is written before hello_ack"),
    ("hello-version-unchecked", KT + "NodeSession.kt", "if ((hello as Decoded.Ok<*>).frame != UiFrame.Hello(CtlProtocol.VERSION)) return violate(BadFrame.BAD_FIELD)", "", "any first frame or version starts a session"),
    ("second-hello-allowed", KT + "NodeSession.kt", "is UiFrame.Hello -> return ExitStatus.PROTOCOL_VIOLATION", "is UiFrame.Hello -> return null", "a second hello is accepted"),
]

CPP = [
    ("cap-off-by-one", PLUGIN + "nodeprocess.cpp", "if (nl - start > maxLine)", "if (nl - start > maxLine + 1)", "a line one byte over 1 MiB is accepted"),
    ("manifest-hash-ignored", PLUGIN + "nodeprocess.cpp", "if (QString::fromLatin1(fileSha256(canonical, &ok)) != m.captured(1) || !ok)\n            return QStringLiteral(\"mismatch\");", "fileSha256(canonical, &ok);", "a changed runtime file is not noticed"),
    ("manifest-path-escape", PLUGIN + "nodeprocess.cpp", "|| !canonical.startsWith(canonicalBase + QLatin1Char('/'))", "", "a manifest entry may point outside the runtime (symlink or absolute path)"),
    ("send-allows-newline", PLUGIN + "nodeprocess.cpp", "|| jsonLine.contains(QLatin1Char('\\n')) || jsonLine.contains(QLatin1Char('\\r'))", "", "a line with a break can smuggle a second frame"),
    ("heartbeat-while-inactive", PLUGIN + "lifecycle.cpp", "else\n        m_timer.stop();", "else\n        m_timer.setInterval(1000);", "the node keeps hearing 'active' after the app left the foreground"),
    ("jar-hash-ignored", PLUGIN + "nodeprocess.cpp", "if (!ok || jarHash != want) {", "if (false) {", "a changed jar is started"),
]


def read(p):
    with open(os.path.join(UT, p), encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with open(os.path.join(UT, p), "w", encoding="utf-8", newline="\n") as f:
        f.write(s)


TEST_FILTERS = []


def run_kotlin():
    env = dict(os.environ, GRADLE_OPTS="-Xmx1g")
    env.pop("JAVA_TOOL_OPTIONS", None)
    only_tests = [x for f in TEST_FILTERS for x in ("--tests", f)]
    p = subprocess.run(
        ["./gradlew", "-p", "ubuntu-touch/jvm", ":ut-host:test", *only_tests, "--no-daemon", "--max-workers=2", "-Pkotlin.compiler.execution.strategy=in-process", "-q"],
        cwd=REPO, env=env, capture_output=True, text=True, timeout=1500,
    )
    out = p.stdout + p.stderr
    if p.returncode == 0:
        return "SURVIVED", ""
    if "Compilation error" in out or "e: file://" in out:
        return "BROKEN (does not compile)", ""
    failed = sorted({line.split(" > ")[0].strip() for line in out.splitlines() if " FAILED" in line and " > " in line})
    return "KILLED", ", ".join(failed)[:160]


def run_cpp():
    env = dict(os.environ, QT_QPA_PLATFORM="offscreen")
    build = os.path.join(UT, "build", "mutants")
    r = subprocess.run(["cmake", "-S", UT, "-B", build, "-DASOM_UT_NO_RUNTIME=ON", "-DASOM_UT_BUILD_TESTS=ON", "-DCMAKE_INSTALL_PREFIX=/"], capture_output=True, text=True, env=env)
    if r.returncode != 0:
        return "BROKEN (configure)", ""
    b = subprocess.run(["cmake", "--build", build, "-j", "2"], capture_output=True, text=True, env=env)
    if b.returncode != 0:
        return "BROKEN (does not compile)", ""
    t = subprocess.run(["ctest", "--output-on-failure"], cwd=build, capture_output=True, text=True, env=env, timeout=300)
    if t.returncode == 0:
        return "SURVIVED", ""
    failed = sorted({line.split("::")[1].split("(")[0] for line in t.stdout.splitlines() if line.startswith("FAIL!")})
    return "KILLED", ", ".join(failed)[:160]


def main(argv):
    only = argv[argv.index("--only") + 1] if "--only" in argv else None
    if "--tests" in argv:
        TEST_FILTERS.append(argv[argv.index("--tests") + 1])
    groups = []
    if "--kotlin" in argv or "--cpp" not in argv:
        groups.append(("kotlin", KOTLIN, run_kotlin))
    if "--cpp" in argv or "--kotlin" not in argv:
        groups.append(("cpp", CPP, run_cpp))
    rows = []
    for kind, mutants, runner in groups:
        for name, path, old, new, what in mutants:
            if only and name != only:
                continue
            original = read(path)
            if original.count(old) != 1:
                rows.append((kind, name, "PATTERN NOT FOUND EXACTLY ONCE (%d)" % original.count(old), what, ""))
                print(f"{kind:6} {name:38} PATTERN NOT FOUND EXACTLY ONCE ({original.count(old)})", flush=True)
                continue
            backup = os.path.join(UT, "build", "mutant.orig")
            os.makedirs(os.path.dirname(backup), exist_ok=True)
            shutil.copyfile(os.path.join(UT, path), backup)
            try:
                write(path, original.replace(old, new))
                status, detail = runner()
            finally:
                shutil.copyfile(backup, os.path.join(UT, path))
            rows.append((kind, name, status, what, detail))
            print(f"{kind:6} {name:38} {status:28} {what}  [{detail}]", flush=True)
    bad = [r for r in rows if r[2] != "KILLED"]
    print(f"\nmutants: {len(rows)} run, {sum(1 for r in rows if r[2] == 'KILLED')} killed, {len(bad)} not killed")
    for r in bad:
        print(f"  NOT KILLED: {r[0]} {r[1]}: {r[2]} ({r[3]})")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
