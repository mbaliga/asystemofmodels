# apple/crosslane: documents the Swift signer made, for the JVM lane to verify

LAB, `oracle: self`, UNSIGNED in the release sense, NOT DEVICE EVIDENCE. Every key is a published TEST-ONLY key (`lab/conformance/keys/TEST-ONLY-keys.json`)
except the per-export key of the one production-path document (M02-943 and M03-915); a production verifier refuses the published ones (`TEST_ONLY_KEY`, M03-904).

A conformance directory of the lab's shape (`VERSION`, `manifest/M02-swift-signed.json`, `manifest/M03-swift-signed.json`, confVersion 0.2.0):
39 vectors, 24 `ok` (M02-901 to M02-943) and 15 `reject` (M03-901 to M03-915). The documents were made by `ManifestSigner.signPresentation`
(own and file audiences, with its self-check) and, for the negative cases that the signer refuses to emit, by `DSSEEnvelope.seal` plus a deliberate edit.
The body is the own-form body of the lab's M02-114 (the one lab accept vector whose `results` both lanes agree on, see `apple/ERRATA.md` F-1).

Regenerate: `ASOM_CONFORMANCE_DIR=lab/conformance swift run --package-path apple asom-conformance generate-crosslane apple/crosslane`.
ES256 here uses a random nonce, so a regeneration changes every signature byte and leaves every payload byte alone, except in M02-943 and M03-915 (one document, made by
the production entry point, whose per-export key is generated inside `signPresentation`, LAB_SPEC R4-L-13: its nodeId, fingerprint and payload are new every time, and a
production-keys FILE verifier checks it under its own fingerprint); a committed fixture is therefore
a reviewed artefact, not a build output. The generator refuses to write a vector that this lane's own verifier contradicts. `swift test` checks that the
committed files verify, that a regeneration gives the same payloads, and that exactly one committed signature is high-S (the deliberate twin M02-902).

Check against the JVM lane (`apple/ci/crosslane.py`): it copies the fixtures into a scratch `lab/conformance`, runs the lab runner's `lines M02,M03`
with `-Dasom.repoRoot` pointing there (the lab is not edited), runs this lane's `lines`, and requires the JVM verdict, the Swift verdict and the
verdict recorded in the file to be the same line for every vector. Only verdicts cross the lines interface; `pin`, `tier` and `bodyDigest` in `expect.ok`
are this lane's own observation.

The other direction (documents the JVM lane signed, verified by this lane) is the lab's own M02 and M03 files: `CrossLaneTests.testJvmSignedDocumentsPassTheDsseLayerHere`
requires this lane's steps 1 to 10 to accept every one of them that the JVM lane took past the signature (58 documents at the time of writing), and the lane diff covers the rest.
