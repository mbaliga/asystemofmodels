#!/usr/bin/env python3
"""Mutation check of the security-relevant steps of the r3 verifier, the signer, the projections and the bench maths (lab-manifest track).

For each mutant: apply ONE textual source mutation (the `old` text must occur exactly once), rebuild, and ask three independent layers in turn
whether the suite notices:

  L1  `:conformance-runner:run --args='lines M01,...,M06'`: the implementation's own verdict per vector, compared with the verdicts of the
      unmutated build (which the script first compares with what the vector files expect: ok / reject CODE);
  L2  `:conformance-runner:test`: every vector against its expectation, including the step of a reject, the laws and the required ids;
  L3  `:manifest:test :bench-core:test`: the unit, property, matrix and fuzz tests.

A mutant is KILLED by the first layer that fails (a build failure counts as INVALID, not as a kill). A mutant that no layer notices SURVIVED;
each survivor must be argued equivalent or turned into a test. The source file is restored in a `finally` block and its sha256 is checked.

Usage (from the repository root):  python3 lab/manifest/tools/mutants.py [--only ID[,ID...]] [--skip ID[,ID...]] [--log FILE]
Resource limits (the session rules): GRADLE_OPTS=-Xmx1g, --no-daemon --max-workers=2, in-process Kotlin compiler, one build at a time.
"""
import hashlib
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
LAB = os.path.normpath(os.path.join(HERE, "..", ".."))
REPO = os.path.normpath(os.path.join(LAB, ".."))
FAMILIES = "M01,M02,M03,M04,M05,M06"
MAN = "manifest/src/main/kotlin/xyz/mdhv/asom/lab/manifest/"
BEN = "bench-core/src/main/kotlin/xyz/mdhv/asom/lab/bench/"

# (id, what it breaks, file, old, new)
MUTANTS = [
    ("V01-skip-signature", "step 8 never verifies the signature", MAN + "Verifier.kt",
     "if (!Es256.rangeOk(sig) || !Es256.verify(key, Dsse.pae(Dsse.PT_MANIFEST_V1, payload), sig)) return reject(RejectCode.SIGNATURE_INVALID, \"8\")",
     "if (false) return reject(RejectCode.SIGNATURE_INVALID, \"8\")"),
    ("V02-no-range-check", "step 8 drops the explicit 0<r,s<n range check (the JDK verify also refuses out-of-range values)", MAN + "Verifier.kt",
     "if (!Es256.rangeOk(sig) || !Es256.verify(", "if (!Es256.verify("),
    ("V03-mesh-trusts-signer-spki", "MESH takes the key from the untrusted signer.spki instead of the pinned key", MAN + "Verifier.kt",
     "spki = ctx.pinnedSpki ?: return reject(RejectCode.KEY_NOT_PINNED, \"7\", \"no pinned key\")",
     "spki = ((cont[\"signer\"] as? JObject)?.get(\"spki\") as? JString)?.value?.let { (Base64Strict.decodeEither(it) as? B64Result.Ok)?.bytes } ?: ctx.pinnedSpki ?: return reject(RejectCode.KEY_NOT_PINNED, \"7\", \"no pinned key\")"),
    ("V04-mesh-trusts-keyid", "MESH does not compare the keyid hint with the pinned node", MAN + "Verifier.kt",
     "if (keyid != null && keyid != Spki.nodeId(spki)) return reject(RejectCode.KEY_NOT_PINNED, \"7\", \"keyid is not the pinned node\")", ""),
    ("V05-reject-high-s", "the verifier refuses high-S signatures", MAN + "Verifier.kt",
     "if (!Es256.rangeOk(sig) || !Es256.verify(", "if (!Es256.rangeOk(sig) || java.math.BigInteger(1, sig.copyOfRange(32, 64)) > P256.HALF_N || !Es256.verify("),
    ("V06-skip-canonical", "step 10 does not require JCS form", MAN + "Verifier.kt",
     "if (!Jcs.serialize(o).contentEquals(payload)) return reject(RejectCode.NON_CANONICAL, \"10\")", ""),
    ("V07-skip-derivation", "step 15a does not recompute results", MAN + "Verifier.kt",
     "if (!Jcs.serialize(body.resultsJson).contentEquals(Jcs.serialize(xyz.mdhv.asom.lab.bench.ja(expected)))) {", "if (false) {"),
    ("V08-skip-conf-floor", "step 15a ignores the confVersion floor and the known-bad list", MAN + "Verifier.kt",
     "if (Semver.compare(conf, ctx.confFloor) < 0 || conf in ctx.knownBadConf) return reject(", "if (false) return reject("),
    ("V09-expired-off-by-one", "EXPIRED at nowMs > expiresAtMs instead of >=", MAN + "Verifier.kt",
     "if (ctx.nowMs >= exp) return reject(RejectCode.EXPIRED", "if (ctx.nowMs > exp) return reject(RejectCode.EXPIRED"),
    ("V10-skip-test-only-deny", "the TEST_ONLY key deny-list is not applied in production mode", MAN + "Verifier.kt",
     "if (ctx.productionKeys && TestOnlyKeys.isTestOnly(nodeId))", "if (false && TestOnlyKeys.isTestOnly(nodeId))"),
    ("V11-future-skew-ge", "NOT_YET_VALID at issuedAt >= now + skew instead of >", MAN + "Verifier.kt",
     "if (pres.issuedAtMs > Checked.add(ctx.nowMs, FUTURE_SKEW_MS))", "if (pres.issuedAtMs >= Checked.add(ctx.nowMs, FUTURE_SKEW_MS))"),
    ("V12-future-skew-10min", "the future skew is 10 minutes, not 5", MAN + "Verifier.kt",
     "private const val FUTURE_SKEW_MS = 300_000L", "private const val FUTURE_SKEW_MS = 600_000L"),
    ("V13-accept-null-challenge", "a presentation without a challenge passes when the requester expected one", MAN + "Verifier.kt",
     "if (want == null || got == null || !MessageDigest.isEqual(got, want))", "if (want == null || (got != null && !MessageDigest.isEqual(got, want)))"),
    ("V14-rollback-le", "ROLLBACK at seq <= stored instead of <", MAN + "Verifier.kt",
     "if (seq < prev.seq) return reject(RejectCode.ROLLBACK", "if (seq <= prev.seq) return reject(RejectCode.ROLLBACK"),
    ("V15-skip-fingerprint-compare", "step 7b never compares the fingerprint", MAN + "Verifier.kt",
     "if (!MessageDigest.isEqual(want, got)) return reject(RejectCode.FINGERPRINT_MISMATCH, \"7b\")", ""),
    ("V16-accept-manifest-v2-type", "an unknown-major payload type is treated as v1", MAN + "Verifier.kt",
     "if (payloadType != Dsse.PT_MANIFEST_V1) {", "if (payloadType != Dsse.PT_MANIFEST_V1 && !payloadType.contains(\"manifest.v2\")) {"),
    ("V17-skip-subject-match", "step 12 does not compare the subject with the signer", MAN + "Verifier.kt",
     "if (body.subject.nodeId != nodeId) return reject(RejectCode.SUBJECT_KEY_MISMATCH", "if (false) return reject(RejectCode.SUBJECT_KEY_MISMATCH"),
    ("V18-ttl-ge", "TTL_INVALID at ttl >= 10 minutes instead of >", MAN + "Verifier.kt",
     "if (ttl <= 0L || ttl > OWN_MAX_TTL_MS)", "if (ttl <= 0L || ttl >= OWN_MAX_TTL_MS)"),
    ("V19-evidence-limit", "three evidence items pass step 15c", MAN + "Verifier.kt",
     "if (items.size > 2 ||", "if (items.size > 3 ||"),
    ("V20-audience-before-nonce", "step 15b runs before step 14", MAN + "Verifier.kt",
     "        // 14\n",
     "        if (body.audience != (if (ctx.mode == Mode.MESH) Audience.OWN else Audience.FILE)) return reject(RejectCode.AUDIENCE_MISMATCH, \"15b\", obj = obj, spki = spki)\n        // 14\n"),
    ("V21-tier-tee-not-hw", "a TEE key is not counted as hardware-backed", MAN + "Verifier.kt",
     "setOf(\"strongbox\", \"tee\", \"secure-enclave\", \"tpm\")", "setOf(\"strongbox\", \"secure-enclave\", \"tpm\")"),
    ("V22-skip-nonce", "step 14 never compares the challenge", MAN + "Verifier.kt",
     "if (ctx.mode == Mode.MESH) {\n            val want = ctx.expectedChallenge", "if (false) {\n            val want = ctx.expectedChallenge"),
    ("V23-file-storage-any", "a FILE body may claim any key storage", MAN + "Typed.kt",
     "if (file && subject.keyStorage != \"ephemeral\") throw", "if (false) throw"),
    ("V24-consistency-wrapping-mul", "consistency() multiplies without overflow checking", MAN + "Consistency.kt",
     "Checked.mul(Checked.mul(p.ttftMicros.p50, p.milliTokPerSec.p50), 10L)", "(p.ttftMicros.p50 * p.milliTokPerSec.p50 * 10L)"),
    ("V25-strict-spki-prefix", "the strict SPKI form does not compare the fixed prefix", MAN + "Es256.kt",
     "for (i in PREFIX.indices) if (spki[i] != PREFIX[i]) return null", ""),
    ("V26-strict-spki-length", "the strict SPKI form accepts any length of at least 91 bytes", MAN + "Es256.kt",
     "if (spki.size != LENGTH) return null", "if (spki.size < LENGTH) return null"),
    ("V27-strict-spki-oncurve", "the strict SPKI form skips the on-curve check (the JDK key factory may refuse such a point itself)", MAN + "Es256.kt",
     "if (!P256.onCurve(x, y)) return null", ""),
    ("V28-der-nonminimal", "the DER codec accepts a long-form length where the short form suffices", MAN + "Es256.kt",
     "if (n == 1 && v < 0x80) return null", ""),
    ("V29-der-negative", "the DER codec accepts a negative integer", MAN + "Es256.kt",
     "if (body[0].toInt() and 0x80 != 0) return Result.Reject(\"negative integer\")", ""),
    ("V30-sign-no-lows", "the producer stops normalising to low-S", MAN + "Es256.kt",
     "return normaliseLowS(rs)\n    }\n\n    /** If s > n/2", "return rs\n    }\n\n    /** If s > n/2"),
    ("S01-file-keeps-platformids", "the FILE projection keeps platformIds", MAN + "Signer.kt",
     "inputs.device.copy(platformIds = null, os = inputs.device.os.copy(securityPatch = null))", "inputs.device.copy(os = inputs.device.os.copy(securityPatch = null))"),
    ("S02-file-keeps-securitypatch", "the FILE projection keeps securityPatch", MAN + "Signer.kt",
     "inputs.device.copy(platformIds = null, os = inputs.device.os.copy(securityPatch = null))", "inputs.device.copy(platformIds = null)"),
    ("S03-seq-no-clock", "seq ignores the clock (max(stored+1, now/1000) becomes stored+1)", MAN + "Signer.kt",
     "maxOf((stored?.seq ?: 0L) + 1L, nowMs / 1000L)", "(stored?.seq ?: 0L) + 1L"),
    ("S04-seq-always-new", "an unchanged body still gets a new seq", MAN + "Signer.kt",
     "if (stored != null && stored.contentDigest == digest) {", "if (stored != null && stored.contentDigest == digest && stored.seq < 0L) {"),
    ("S05-file-export-key-is-nik-free-check", "the export presentation keeps the exact time (not day-truncated)", MAN + "Signer.kt",
     "jo(\"issuedAtMs\" to ji(nowMs / DAY_MS * DAY_MS))", "jo(\"issuedAtMs\" to ji(nowMs))"),
    ("P01-q2-truncates", "q2 truncates instead of rounding half up", MAN + "PublicDerivative.kt",
     "return (x + p / 2) / p * p", "return x / p * p"),
    ("P02-keep-uncatalogued", "rows whose file is not in the held catalogue are kept", MAN + "PublicDerivative.kt",
     "r.fileSha256 in catalogueSha256 && r.sustained != null", "r.sustained != null"),
    ("P03-commit-always", "the engine commit is emitted even when it is not on the release allow-list", MAN + "PublicDerivative.kt",
     "\"commit\" to js(if (e.commit in allow.engineCommits) e.commit else CUSTOM)", "\"commit\" to js(e.commit)"),
    ("P04-model-always", "the device model is emitted even when it is not on the coarse list", MAN + "PublicDerivative.kt",
     "\"model\" to js(if (d.model in coarse.models) d.model else OTHER)", "\"model\" to js(d.model)"),
    ("B01-lower-median-upper", "the rate median is the upper median", BEN + "Stats.kt",
     "return xs.sorted()[(xs.size - 1) / 2]", "return xs.sorted()[xs.size / 2]"),
    ("B02-exclude-quarter", "up to a quarter of the reps may be excluded (MAX_EXCLUDE_DIV 5 -> 4)", BEN + "Stats.kt",
     "const val MAX_EXCLUDE_DIV: Int = 5", "const val MAX_EXCLUDE_DIV: Int = 4"),
    ("B03-drift-20pc", "THERMAL_DRIFT needs 20% instead of 10%", BEN + "Stats.kt",
     "const val DRIFT_FIRST_LAST_PERMILLE: Long = 100L", "const val DRIFT_FIRST_LAST_PERMILLE: Long = 200L"),
    ("B04-mad-k", "the outlier threshold is 3 scaled MADs of 5000 milli-units", BEN + "Stats.kt",
     "const val OUTLIER_K_MILLI: Long = 4449L", "const val OUTLIER_K_MILLI: Long = 5000L"),
    ("B05-consent-expiry-gt", "a consent token is valid AT its expiry instant", BEN + "Consent.kt",
     "if (nowMs >= expiresAtMs) throw ConsentError(\"token expired\")", "if (nowMs > expiresAtMs) throw ConsentError(\"token expired\")"),
    ("B06-consent-reusable", "a consent token may be used twice", BEN + "Consent.kt",
     "if (consumed) throw ConsentError(\"token already used\")", ""),
    ("B07-fsm-extra-edge", "the governor may go RUNNING -> IDLE", BEN + "Governor.kt",
     "GState.RUNNING to setOf(GState.COOLING, GState.FINALIZING, GState.YIELDED, GState.ABORTING),", "GState.RUNNING to setOf(GState.COOLING, GState.FINALIZING, GState.YIELDED, GState.ABORTING, GState.IDLE),"),
    ("B08-yield-limit-4", "the run finishes after the 4th yield instead of the 3rd", BEN + "Governor.kt",
     "const val YIELD_LIMIT = 3", "const val YIELD_LIMIT = 4"),
    ("B09-android-hard-battery-450", "the Android hard battery-temperature ceiling is 45.0 C", BEN + "Governor.kt",
     "const val ANDROID_HARD_BATTERY_DECIC = 440", "const val ANDROID_HARD_BATTERY_DECIC = 450"),
    ("B10-cleanup-no-close", "the abort path does not close the engine", BEN + "Executor.kt",
     "        runCatching { m?.close() }\n        host.progress.releaseWakeLock()", "        host.progress.releaseWakeLock()"),
    ("B11-cleanup-no-brightness", "the abort path does not restore the brightness", BEN + "Executor.kt",
     "        host.progress.restoreBrightness()\n        host.coexistence.disarm()", "        host.coexistence.disarm()"),
    ("B12-file-keeps-battery", "the FILE bench projection keeps the battery level", BEN + "Project.kt",
     "batteryStartPermille = null, screenOn = null,", "screenOn = null,"),
    ("B13-wrapping-mul", "Checked.mul wraps instead of failing", BEN + "Checked.kt",
     "Math.multiplyExact(a, b)", "a * b"),
    ("B14-role-helper-9000", "the occasional-helper role needs 9 tokens/s instead of 8", BEN + "BenchSet.kt",
     "const val ROLE_HELPER_MTPS: Long = 8_000L", "const val ROLE_HELPER_MTPS: Long = 9_000L"),
]


def sha(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def gradle(tasks, extra=()):
    env = dict(os.environ, GRADLE_OPTS="-Xmx1g")
    cmd = ["./gradlew", "-p", "lab", *tasks, "--no-daemon", "--max-workers=2", "-Pkotlin.compiler.execution.strategy=in-process", "--quiet", *extra]
    p = subprocess.run(cmd, cwd=REPO, env=env, capture_output=True, text=True, timeout=900)
    return p.returncode, p.stdout, p.stderr


def lines_run():
    rc, out, err = gradle([":conformance-runner:run", f"--args=lines {FAMILIES}"])
    lines = [ln for ln in out.split("\n") if ln.startswith("M0")]
    return rc, lines, err


def expected_lines():
    """What the vector files say, for the families whose verdict is a plain ok / reject CODE."""
    out = {}
    conf = os.path.join(LAB, "conformance")
    for rel in ("manifest/M02-verify-accept.json", "manifest/M03-verify-reject.json"):
        with open(os.path.join(conf, rel), encoding="utf-8") as f:
            for v in json.load(f)["vectors"]:
                e = v["expect"]
                out[v["id"]] = "ok" if "ok" in e else "reject " + e["reject"]
    return out


def main(argv):
    only = None
    skip = set()
    log = os.path.join(os.environ.get("TMPDIR", "/tmp"), "mutants.log.jsonl")
    for i, a in enumerate(argv):
        if a == "--only":
            only = set(argv[i + 1].split(","))
        if a == "--skip":
            skip = set(argv[i + 1].split(","))
        if a == "--log":
            log = argv[i + 1]
    t0 = time.time()
    rc, base, err = lines_run()
    if rc != 0 or not base:
        print("baseline lines run failed:", err[-800:])
        return 2
    exp = expected_lines()
    bad = [ln for ln in base if ln.split(" ")[0] in exp and ln.split(" ", 1)[1] != exp[ln.split(" ")[0]]]
    print(f"baseline: {len(base)} lines, {sum(1 for ln in base if ln.split(' ')[0] in exp)} checked against the vector expectations, {len(bad)} differ")
    if bad:
        print("baseline disagrees with the vectors:", bad[:5])
        return 2
    results = []
    for mid, what, rel, old, new in MUTANTS:
        if (only and mid not in only) or mid in skip:
            continue
        path = os.path.join(LAB, rel)
        with open(path, encoding="utf-8") as f:
            src = f.read()
        before = sha(path)
        if src.count(old) != 1:
            results.append({"id": mid, "what": what, "verdict": "INVALID", "why": f"the old text occurs {src.count(old)} times"})
            print(mid, "INVALID (old text count %d)" % src.count(old))
            continue
        verdict, layer, detail = "SURVIVED", "-", ""
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(src.replace(old, new))
            rc, lines, err = lines_run()
            if rc != 0 and not lines:
                if "e: file://" in err or "Compilation error" in err:
                    verdict, layer, detail = "INVALID", "build", err[-300:].replace("\n", " ")
                else:
                    verdict, layer, detail = "KILLED", "L1 (runner crashed)", err[-200:].replace("\n", " ")
            elif lines != base:
                diff = [(a, b) for a, b in zip(base, lines) if a != b][:2]
                verdict, layer, detail = "KILLED", "L1 lines", json.dumps(diff) if diff else f"{len(lines)} lines vs {len(base)}"
            else:
                rc2, out2, err2 = gradle([":conformance-runner:test"])
                if rc2 != 0:
                    verdict, layer, detail = "KILLED", "L2 runner suite", (out2 + err2)[-260:].replace("\n", " ")
                else:
                    rc3, out3, err3 = gradle([":manifest:test", ":bench-core:test"], ["--continue"])
                    if rc3 != 0:
                        verdict, layer, detail = "KILLED", "L3 unit tests", (out3 + err3)[-260:].replace("\n", " ")
        finally:
            with open(path, "w", encoding="utf-8") as f:
                f.write(src)
            assert sha(path) == before, f"restore of {rel} failed"
        results.append({"id": mid, "what": what, "verdict": verdict, "layer": layer, "detail": detail[:300]})
        print(f"{mid:34s} {verdict:9s} {layer}", flush=True)
        with open(log, "a", encoding="utf-8") as f:
            f.write(json.dumps(results[-1]) + "\n")
    killed = [r for r in results if r["verdict"] == "KILLED"]
    surv = [r for r in results if r["verdict"] == "SURVIVED"]
    inv = [r for r in results if r["verdict"] == "INVALID"]
    print(f"\nmutants: {len(results)} run, {len(killed)} killed, {len(surv)} survived, {len(inv)} invalid, {int(time.time() - t0)} s")
    for r in surv:
        print("  SURVIVED", r["id"], "-", r["what"])
    for r in inv:
        print("  INVALID ", r["id"], "-", r.get("why", r.get("detail", "")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
