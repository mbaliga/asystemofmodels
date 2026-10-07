# apple/: the Swift lane, halves I0a, I0b and I0c

**Status: LAB, UNSIGNED, ships nothing.** No listener, no network access at runtime, no key material outside tests.
This is `PLATFORM_PLAN.md` section 6, step I0 (= MC0 = L0.7). Half I0a built the pure Swift targets for JSON, DSSE and ES256. Half I0b added the r3
verifier and the benchmark core: `AsomBenchCore` (M04 derivation, the projection, the `asom.text/1` renderer) and `AsomManifest` (the typed decoder,
`consistency()`, the verifier of `LAB_SPEC.md` 4.6 in the normative step order, the FILE and public projections, the `asom.manifest-text/1` renderer), and the
`lines` and `check` modes of the CLI over the lab's r3 vectors (`lab/conformance`, confVersion 0.2.0). **Half I0c** adds the M04 protocol pieces that the spec fixes
(the standard run plan, the consent token, the governor state machine, the runtime ceilings, the bench-set pins), `signPresentation` (the signer) with a cross-lane
fixture directory the JVM lane verifies, the M08 claim tracker (`AsomRouterCore`, a second implementation), and negative controls for the lane diff itself.

## The boundary

- **ES256 only.** ECDSA P-256 with SHA-256, one signature, 64-octet raw r||s. No Ed25519, no RSA, no ML-DSA.
  Producers here normalise to low-S (C2); the verifier accepts high-S. Signing is random-nonce (swift-crypto, CryptoKit): signature bytes are never compared across lanes, only verification is.
- **A second implementation of the `LAB_SPEC.md` section 4, 5 and 6.6 profile, written from the spec.** It is **never linked into the Mac node** (the Mac node is
  the JVM lane) and shares no code with the JVM lab. **It is not independent evidence** (see "What the evidence is" and `ERRATA.md` E-16, E-17 and E-33).
- **No `JSONSerialization` on any signed byte.** `AsomJSON` is a hand-written tokenizer. `AsomJSON`, `AsomDSSE`, `AsomBenchCore`, `AsomManifest` and `AsomRouterCore` do not import Foundation;
  only the conformance kit, the CLI and the tests do.
- **CryptoKit on Apple, swift-crypto on Linux**, from one source: `#if canImport(CryptoKit) import CryptoKit #else import Crypto #endif`.
  swift-crypto is a dependency only under `.when(platforms: [.linux])`, so an Apple build carries no third-party crypto.

## Targets

| Target | What it holds |
|---|---|
| `AsomJSON` | `StrictJSON` (LAB_SPEC 4.2 rejects: duplicates, fractions, exponents, `-0`, `NaN`, beyond +-(2^53-1), lone surrogates, invalid UTF-8, BOM, depth over 16, trailing data), `JCS` (RFC 8785 integer profile), `Base64Strict` (`b64either`, `b64url`), `RejectCode` |
| `AsomDSSE` | `PAE`, `SPKI`, `ES256` and `ES256Signer` (low-S, strict 91-byte SPKI), `SignatureCodec`, `NodeIdentity` (pin, node id and tag, fingerprints, the TEST-ONLY deny-list of four keys, SHA-256), `DSSEEnvelope` (verifier steps 1 to 10 and the container producer) |
| `AsomBenchCore` | `Checked` (overflow-checked 64-bit arithmetic), `SchemaReader`, `BenchSet` (the compiled-in Q1 pins; L1 loads only under a D18 ruling flag and has none), `BenchDocument` (the typed `asom.bench/1` decoder), `Stats` and `SustainDerivation` (M04), `Derivation`, `Projection`, `TextRenderer` (`asom.text/1`), **`RunPlan`** (the standard plan, benchmark.md 5.2), **`ConsentSheet` and `ConsentGate`** (11.1), **`Governor`** (11.4 with B9), **`Ceilings`** (11.3) |
| `AsomManifest` | `ManifestDecoder` (step 11), `Consistency` (manifest.md 8.4), `ManifestVerifier` (steps 1 to 19), `FileProjection`, `PublicDerivative`, `ManifestText`, **`ManifestSigner`** (`signPresentation`, the self-check, `nextSeq`) |
| `AsomRouterCore` | **`ClaimTracker`**, `AttemptEvaluator`, `ClaimBodyGate`, `CapRef` (LAB_SPEC 6.6; M08) |
| `AsomConformanceKit` | The logic behind the CLI, so that tests can call it (`Conformance` for the r0 set, `R3` for the r3 set, `CrossLane` for the fixtures). Not a product |
| `asom-conformance` | The CLI (`Sources/asom-conformance/main.swift`) |

## Running it

```sh
export PATH=/opt/swift/usr/bin:$PATH             # this container; elsewhere a Swift 6.1 toolchain
swift build --package-path apple
swift test  --package-path apple
swift run --package-path apple asom-conformance lines M01,M02,M03,M04,M05,M06,M08      # this lane's own verdicts, sorted, one per vector it produces
swift run --package-path apple asom-conformance check                                   # every verdict and value against the vector's own expectation
ASOM_DIAGNOSTIC_DRIFT_FLAG=1 swift run --package-path apple asom-conformance check      # diagnostic reading of finding LF-1 (see below)
swift run --package-path apple asom-conformance show M05-101                            # the observed value of one vector
ASOM_CONFORMANCE_DIR=lab/conformance swift run --package-path apple asom-conformance generate-crosslane apple/crosslane   # regenerates the signer fixtures (new signatures)
python3 apple/ci/lane_diff.py jvm.lines swift.lines --known apple/ci/known-disagreements.txt --not-implemented apple/ci/not-implemented.txt
python3 apple/ci/test_lane_diff.py ; python3 apple/ci/test_crosslane.py                  # negative controls of the two diff tools
python3 apple/ci/crosslane.py all --jvm-runner lab/conformance-runner/build/install/conformance-runner/bin/conformance-runner --swift-cli apple/.build/debug/asom-conformance
```

The vector directory is `ASOM_CONFORMANCE_DIR`, else `lab/conformance`, else `docs/design/mesh/manifest-vectors`. A directory whose `VERSION` is `0.2.0` is the r3 set;
anything else is the r0 set of half I0a (M02 and M03, DSSE layer only, `check` and `sigcheck`). The CLI refuses a confVersion it does not understand instead of guessing.

## What is covered (r3, `lab/conformance`)

| Family | Vectors | This lane |
|---|---|---|
| M01 (JCS, strict JSON, base64, DER codec) | 105 | all |
| M02 (verify, accept) | 20 | all |
| M03 (verify, reject) | 108 | all |
| M04 (derive, plan, consent, governor, ceilings, pins, traces) | 94 | **65**: `test` (22), `sustain` (9), `percentile` (2), `doc` (15), `plan` (1: standard), `consent` (5), `fsm` (1), `ceilings` (9), `pins` (1). **Not** 29, each BLOCKED in `apple/ci/not-implemented.txt` with its reason: the 26 executor traces (the fake engine, its presets and the event grammar are not in the spec), the quick and ci plans (their JCS form is not in the spec), the run-today consent sheet (its wording is not in the spec). `ERRATA.md` E-29 to E-31 |
| M05 (render) | 42 | all: the manifest text (12) and the `asom.text/1` body (30) |
| M06 (derivatives) | 10 | all: `q2`, the public derivative, the FILE projection |
| M08 (claim tracker) | 59 | all: `evaluate` (28), `sequence` (26), `state` (3), `claimBody` (2) |

`lines` printed 370 lines at the time of writing (the 294 of half I0b, plus 17 M04 and 59 M08 vectors; the real count is in the gate entry of `PROGRESS.md`). The JVM runner prints 399 for M01 to M06 and M08; the other 29 are the BLOCKED M04 vectors above.

## The lane diff

`apple/ci/lane_diff.py` compares the two lines files **per vector** and names each difference with both verdicts. Two explicit lists sit beside it:
`known-disagreements.txt` (37 vectors, finding LF-1, each line with its reason) and `not-implemented.txt` (29 vectors, each BLOCKED with its reason). It fails on a disagreement that is not listed, on a listed one that now agrees,
on a vector one lane printed and the other did not, on a `not-implemented` entry that the Swift lane now prints or that the JVM lane does not print, on an entry with no reason, and on a duplicate. `apple/ci/test_lane_diff.py` runs the real script against each of those failures.

**LF-1 in one paragraph.** The JVM lane's projection of `results[]` adds a row flag `thermal-drift` when an M04 test drifted (design B7). benchmark.md 13.3 lists the flags of a projected row and has no such flag.
The Swift lane follows the list, so it says `DERIVATION_MISMATCH` at step 15a for every document that carries the flag. **This is not a verifier step-order difference**: the verifier runs steps 1 to 19 in the order of LAB_SPEC 4.6, and
`testTheOnlyDifferenceInEveryListedVectorIsTheThermalDriftRowFlag` shows that for each of the 37 listed vectors the document's own `results` equal the Swift projection once that one flag is removed. Which reading is right is the lab owner's call;
the diagnostic switch exists only to show what remains once it is set aside, and the gate diff never uses it. The lab was not edited. Details, and every other reading taken where the spec is silent, are in `ERRATA.md`.

## The signer and the cross-lane fixtures

`ManifestSigner.signPresentation` (LAB_SPEC 4.7) builds `{schema, schemaMinor, body, presentation}`, seals it (`DSSEEnvelope.seal`: JCS payload, PAE, low-S raw r||s, `signer.spki`), and **verifies the result with `ManifestVerifier` before returning it**; a document this lane would reject is never handed out
(`MANIFEST_UNAVAILABLE`). `own` documents are signed by the node key and need a 32-byte challenge; `file` documents are projected (`FileProjection`) and signed by the per-export key the caller passes in, never by the node key.
`apple/crosslane` holds 37 documents it made (23 accepted, 14 rejected, 12 reject codes), and `apple/ci/crosslane.py` has the JVM lane's `lines` mode verify them on a scratch repository root (the lab is not edited) and requires the JVM verdict, the Swift verdict and the verdict the file records to be the same line.
The other direction is the lab's own M02 and M03 files: the JVM lane signed them and `CrossLaneTests.testJvmSignedDocumentsPassTheDsseLayerHere` requires this lane's steps 1 to 10 to accept every one of them that the JVM lane took past the signature.

## What the evidence is, and is not

- **Evidence labels:** `LAB`, `CI (hosted VM) evidence`, `SIMULATOR`. Every artefact is UNSIGNED. **NOT DEVICE EVIDENCE.**
- **Independence is not claimed.** The vectors stay `oracle: self`. The author of half I0b did not read `lab/manifest/src`, `lab/bench-core/src`, `lab/json/src`, the generators or the runner's family adapters,
  but did read all of `lab/ERRATA.md` (which states the JVM lane's readings), the vectors' inputs and expected outputs before writing the code, and `bench_ref.py` for the formatters. Eight clauses were then set from the
  vectors (`ERRATA.md` E-17). The authors of half I0c opened no `lab/*/src` file and neither the M08 adapter nor the Kotlin tracker (one line of `lab/bench-core/.../Project.kt` and three lines of `lab/ERRATA.md` were printed by searches for "thermal-drift" and "drift"), but read the M08 vectors' expected outputs first and shares the repository (`ERRATA.md` "What was read, and what was not"). At most this is cross-lane agreement, and the agreement on the clauses a vector pinned is not evidence about the spec.
  **No oracle tag was changed.**
- **What was actually run:** `swift build`, `swift test` (XCTest, Linux, swift-crypto, no JDK), the conformance CLI in `lines` and `check` mode, the JVM runner's `lines` mode through Gradle, `lane_diff.py`, the two negative-control scripts, `crosslane.py`,
  a YAML parse of the workflow (actionlint is not installed in this container), and a mutation harness over the new code (`PROGRESS.md`). **Not run:** anything on macOS, CryptoKit, Xcode, the iOS simulator, `xcodebuild`, the `swift:6.1-noble` container, or GitHub Actions. Those jobs are written and YAML-parsed only (actionlint, which checked the I0b version of the workflow, is not installed in this container); the CryptoKit path of the signer is UNVERIFIED.
  vectors (`ERRATA.md` E-17). At most this is cross-lane agreement, and the agreement on those clauses is not evidence about the spec.
- **What was actually run:** `swift build`, `swift test` (XCTest, Linux, swift-crypto, no JDK), the conformance CLI in `lines` and `check` mode, the JVM runner's `lines` mode through Gradle, `lane_diff.py`,
  actionlint on the workflow. **Not run (as written for I0b):** anything on macOS, CryptoKit, Xcode, the iOS simulator, `xcodebuild`, the `swift:6.1-noble` container, or GitHub Actions. Those jobs were written and syntax-checked only. **Superseded 2026-10-07:** at head `37332005` the hosted check runs `apple-swift-lane (macos-latest, CryptoKit)`, `apple-swift-lane (ubuntu-latest, swift:6.1-noble, swift-crypto)`, `ios-package-sim (iPhone 17 simulator, package targets only)`, `jvm-lines` and `lane-diff` all concluded success (URLs: PROGRESS entry "Corrections to the record, 2026-10-07 (fix-docs)"; conclusions and step names were read, not the logs). That is `CI (hosted VM) evidence` and still not device evidence: nothing here has run on an iPhone, an iPad or a Mac in hand.
- **Does not prove:** anything about UIKit, SwiftUI, Network.framework, the Secure Enclave, App Attest, file protection, or any iPhone or iPad. It does not prove that CryptoKit behaves like swift-crypto on every input;
  the workflow's macOS job is the first place that is tested, and the two Swift builds' lines files are diffed against each other there.
- The signer signs. It is not a policy: nothing here decides who may see a manifest or whether a benchmark is honest, and the claim tracker detects claims that observation does not bear out, not honesty (and it has the padding residual of `ERRATA.md` E-39).

`ERRATA.md` records every place a spec defect or ambiguity forced a choice, every disagreement with the JVM lane, and the findings for the lab (LF-1 to LF-5).
