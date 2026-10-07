#!/usr/bin/env python3
"""Mutation checks for the trust layer (LAB_SPEC R10, assignment proto-trust): break the implementation on purpose, run the suite, require a failure, restore.

  python3 lab/mesh-proto/tools/trust/mutants.py            # every mutant against :mesh-proto:test
  python3 lab/mesh-proto/tools/trust/mutants.py 1 4 7      # selected mutants (1-based)
  python3 lab/mesh-proto/tools/trust/mutants.py --runner 1 # also run :conformance-runner:test for the selected mutants

Each mutant is an exact-string replacement in one source file. The file is restored byte for byte in a `finally`, and a final `git diff --quiet` of
the two source packages is NOT used (the files are untracked in a fresh worktree): the original bytes are held in memory and compared after the run.
A mutant that does not apply (the text is not found) fails the script: a mutant that changed nothing would prove nothing.
"""
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", ".."))
SRC = os.path.join(ROOT, "lab", "mesh-proto", "src", "main", "kotlin", "xyz", "mdhv", "asom", "lab", "proto")
V = "trust/VerifyPeerChain.kt"
REG = "trust/PeerRegistry.kt"
PIN = "trust/Pin.kt"
PC = "pairing/PairCrypto.kt"
FSM = "pairing/PairingFsm.kt"

MUTANTS = [
    ("a CA leaf is accepted (the LEAF_IS_CA check removed)", V,
     'if (lBc.ca || KeyUsageBits.KEY_CERT_SIGN in lKu) return bad(ChainReject.LEAF_IS_CA, "the leaf is a CA or may sign certificates")', ""),
    ("a P-384 key is accepted (the strict P-256 key check falls back to any JDK EC key)", V,
     "private fun strictKey(spki: ByteArray): PublicKey? = Spki.strict(spki)",
     "private fun strictKey(spki: ByteArray): PublicKey? = Spki.strict(spki) ?: runCatching { java.security.KeyFactory.getInstance(\"EC\").generatePublic(java.security.spec.X509EncodedKeySpec(spki)) }.getOrNull()"),
    ("a SHA-1 signature is accepted (algorithm check and verifier both allow ecdsa-with-SHA1)", V,
     "if (!c.signatureAlgorithm.contentEquals(ecdsaSha256) || !c.tbsSignatureAlgorithm.contentEquals(ecdsaSha256)) {",
     "val sha1 = Der.sequence(Der.oid(Oids.ECDSA_SHA1)); if (!(c.signatureAlgorithm.contentEquals(ecdsaSha256) || c.signatureAlgorithm.contentEquals(sha1)) || !c.tbsSignatureAlgorithm.contentEquals(c.signatureAlgorithm)) {"),
    ("the AKI is not compared with the node SKI", V,
     'if (!MessageDigestEq.equal(lAki, nSki)) return bad(ChainReject.AKI_MISMATCH, "the leaf authorityKeyIdentifier is not the node subjectKeyIdentifier")', ""),
    ("the leaf validity is not checked", V,
     'if (t < leaf.notBefore - SKEW_SEC) return bad(ChainReject.CLOCK_SKEW, "the leaf is not yet valid")\n        if (t > leaf.notAfter + SKEW_SEC) return bad(ChainReject.CLOCK_SKEW, "the leaf has expired")', ""),
    ("a pin is compared with a prefix (length check removed, shorter value prefix-compared)", PIN,
     "fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = a.size == LENGTH && b.size == LENGTH && MessageDigest.isEqual(a, b)",
     "fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean { val n = minOf(a.size, b.size); return n > 0 && MessageDigest.isEqual(a.copyOf(n), b.copyOf(n)) }"),
    ("the SAS truncation is off by one (bytes 1..4 instead of 0..3)", PC,
     "val u = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)",
     "val u = ((h[1].toLong() and 0xFF) shl 24) or ((h[2].toLong() and 0xFF) shl 16) or ((h[3].toLong() and 0xFF) shl 8) or (h[4].toLong() and 0xFF)"),
    ("the proof is computed over a swapped nonce (nonce_D) when D checks it", FSM,
     "if (!PairCrypto.proofValid(s.secret, cfg.ownPin, e.pinS, e.nonceS, e.proof)) {",
     "if (!PairCrypto.proofValid(s.secret, cfg.ownPin, e.pinS, e.freshNonceD, e.proof)) {"),
    ("a revoked peer is still authorised (only SUSPENDED is denied)", REG,
     "        if (row.status != PeerStatus.PAIRED) return AuthDecision.Deny(DenyReason.NOT_PAIRED)\n        val granted",
     "        if (row.status == PeerStatus.SUSPENDED) return AuthDecision.Deny(DenyReason.NOT_PAIRED)\n        val granted"),
    ("a suspended peer is authorised for a scope it was not granted", REG,
     "        if (row.status != PeerStatus.PAIRED) return AuthDecision.Deny(DenyReason.NOT_PAIRED)\n        val granted",
     "        if (row.status == PeerStatus.REVOKED) return AuthDecision.Deny(DenyReason.NOT_PAIRED)\n        if (row.status == PeerStatus.SUSPENDED) return AuthDecision.Allow\n        val granted"),
    ("the node self-signature is not verified (only the leaf signature is)", V,
     "if (!signatureOk(node, nodeKey) || !signatureOk(leaf, nodeKey))", "if (!signatureOk(leaf, nodeKey))"),
    ("the node certificate may be any CA depth (the pathLen check removed)", V,
     'if (nBc.pathLen != 0L) return bad(ChainReject.PATHLEN_VIOLATION, "the node certificate must carry pathLen 0")', ""),
    ("the leaf EKU is not checked", V,
     'if (Oids.SERVER_AUTH !in lEku || Oids.CLIENT_AUTH !in lEku) return bad(ChainReject.LEAF_EKU, "the leaf must allow both serverAuth and clientAuth")', ""),
    ("a unknown critical extension is ignored", V,
     'critical(c)?.let { return bad(ChainReject.UNKNOWN_CRITICAL_EXTENSION, "a critical extension is not understood") }', ""),
    ("any chain length is accepted when the first two certificates are right", V,
     'if (chain.size != 2) return bad(ChainReject.CHAIN_LENGTH', 'if (chain.size < 2) return bad(ChainReject.CHAIN_LENGTH'),
    ("a ceremony commits without the LOCAL approval", REG,
     "if (!c.localApproved) return refuse(RegistryRefusal.LOCAL_APPROVAL_REQUIRED)", ""),
    ("a registry row with an unknown status value reads as PAIRED", REG,
     "StatusCodes.decode(r.row.status)?.let { Loaded.Row(r.row, it) } ?: Loaded.Corrupt(r.row.status)", "Loaded.Row(r.row, StatusCodes.decode(r.row.status) ?: PeerStatus.PAIRED)"),
    ("a REVOKED pin with a valid proof reaches the challenge (L7)", FSM,
     "is StatusLookup.Known -> if (st.status == PeerStatus.REVOKED) {", "is StatusLookup.Known -> if (st.status == PeerStatus.REVOKED && false) {"),
    ("a second valid hello is accepted after the window was consumed (L6)", FSM,
     "        is DEvent.HelloReceived -> same(s, PairEffect.Refuse(Refusal.PAIRING_WINDOW_CLOSED))\n        is DEvent.RemoteDecision, is DEvent.AckReceived -> abort(AbortReason.PROTOCOL, Refusal.PROTOCOL_ERROR)",
     "        is DEvent.HelloReceived -> DStep(DState.Consumed(e.pinS, e.nonceS, e.freshNonceD, e.nowMs), listOf(PairEffect.SendChallenge))\n        is DEvent.RemoteDecision, is DEvent.AckReceived -> abort(AbortReason.PROTOCOL, Refusal.PROTOCOL_ERROR)"),
    ("the registry forgets a row that is not REVOKED", REG,
     "if (row.status != PeerStatus.REVOKED) return refuse(RegistryRefusal.NOT_REVOKED)", ""),
    ("a SUSPENDED row is restored by a network status claim", REG,
     "is NetworkEvent.RevocationHint, is NetworkEvent.StatusClaim -> refuse(RegistryRefusal.NETWORK_TRANSITION_RETIRED)",
     "is NetworkEvent.RevocationHint -> refuse(RegistryRefusal.NETWORK_TRANSITION_RETIRED)\n        is NetworkEvent.StatusClaim -> { if (e.claimed == PeerStatus.PAIRED) restore(e.pin, 0); RegistryResult.Updated }"),
]


def gradle(args):
    env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS"}
    p = subprocess.run(["./gradlew", "-p", "lab"] + args + ["--max-workers=2", "--offline", "--rerun"], cwd=ROOT, capture_output=True, text=True, env=env)
    return p.returncode, p.stdout + p.stderr


def main(argv):
    runner = "--runner" in argv
    picks = [int(a) for a in argv[1:] if a.isdigit()]
    todo = [(i + 1, m) for i, m in enumerate(MUTANTS) if not picks or (i + 1) in picks]
    failures = 0
    for idx, (name, rel, old, new) in todo:
        path = os.path.join(SRC, rel)
        original = open(path, "rb").read()
        text = original.decode("utf-8")
        if text.count(old) != 1:
            print("mutant %d: the text to replace occurs %d times in %s: %s" % (idx, text.count(old), rel, name))
            return 2
        try:
            open(path, "wb").write(text.replace(old, new).encode("utf-8"))
            rc, out = gradle([":mesh-proto:test"])
            killed = rc != 0
            how = "mesh-proto"
            if runner and killed:
                rc2, out2 = gradle([":conformance-runner:test"])
                how += " + runner(%s)" % ("fails" if rc2 != 0 else "PASSES")
                killed = killed and rc2 != 0
            compiled = "Compilation error" not in out and "e: file:" not in out
            failed_tests = [l.strip() for l in out.splitlines() if " FAILED" in l and ">" in l][:3]
            print("mutant %2d %-9s %s\n          %s%s" % (idx, "KILLED" if killed and compiled else ("NOT-KILLED" if not killed else "DID-NOT-COMPILE"), name, how, ("  e.g. " + failed_tests[0]) if failed_tests else ""))
            if not (killed and compiled):
                failures += 1
        finally:
            open(path, "wb").write(original)
            assert open(path, "rb").read() == original
    print("mutants run: %d, survivors or non-compiling: %d" % (len(todo), failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
