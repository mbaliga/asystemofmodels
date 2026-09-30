# apple/: the Swift lane, halves I0a and I0b

**Status: LAB, UNSIGNED, ships nothing.** No listener, no network access at runtime, no key material outside tests.
This is `PLATFORM_PLAN.md` section 6, step I0 (= MC0 = L0.7). Half I0a built the pure Swift targets for JSON, DSSE and ES256. Half I0b adds the r3
verifier and the benchmark core: `AsomBenchCore` (M04 derivation, the projection, the `asom.text/1` renderer) and `AsomManifest` (the typed decoder,
`consistency()`, the verifier of `LAB_SPEC.md` 4.6 in the normative step order, the FILE and public projections, the `asom.manifest-text/1` renderer), and the
`lines` and `check` modes of the CLI over the lab's r3 vectors (`lab/conformance`, confVersion 0.2.0).

## The boundary

- **ES256 only.** ECDSA P-256 with SHA-256, one signature, 64-octet raw r||s. No Ed25519, no RSA, no ML-DSA.
  Producers here normalise to low-S (C2); the verifier accepts high-S.
- **A second implementation of the `LAB_SPEC.md` section 4 and 5 profile, written from the spec.** It is **never linked into the Mac node** (the Mac node is
  the JVM lane) and shares no code with the JVM lab. **It is not independent evidence** (see "What the evidence is" and `ERRATA.md` E-16 and E-17).
- **No `JSONSerialization` on any signed byte.** `AsomJSON` is a hand-written tokenizer. `AsomJSON`, `AsomDSSE`, `AsomBenchCore` and `AsomManifest` do not import Foundation;
  only the conformance kit, the CLI and the tests do.
- **CryptoKit on Apple, swift-crypto on Linux**, from one source: `#if canImport(CryptoKit) import CryptoKit #else import Crypto #endif`.
  swift-crypto is a dependency only under `.when(platforms: [.linux])`, so an Apple build carries no third-party crypto.

## Targets

| Target | What it holds |
|---|---|
| `AsomJSON` | `StrictJSON` (LAB_SPEC 4.2 rejects: duplicates, fractions, exponents, `-0`, `NaN`, beyond +-(2^53-1), lone surrogates, invalid UTF-8, BOM, depth over 16, trailing data), `JCS` (RFC 8785 integer profile), `Base64Strict` (`b64either`, `b64url`), `RejectCode` |
| `AsomDSSE` | `PAE`, `SPKI`, `ES256`, `SignatureCodec`, `NodeIdentity` (pin, node id and tag, fingerprints, the TEST-ONLY deny-list of four keys, SHA-256), `DSSEEnvelope` (verifier steps 1 to 10 and the container producer) |
| `AsomBenchCore` | `Checked` (overflow-checked 64-bit arithmetic), `SchemaReader` (the hand-written decoder's helpers and the shape predicates), `BenchSet` (the compiled-in Q1 pins; L1 has none), `BenchDocument` (the typed `asom.bench/1` decoder, P6 to P9), `Stats` and `SustainDerivation` (M04), `Derivation` (the five answers), `Projection` (`results[]`, FILE form, the FILE bench projection), `TextRenderer` (`asom.text/1`) |
| `AsomManifest` | `ManifestDecoder` (step 11), `Consistency` (manifest.md 8.4), `ManifestVerifier` (steps 1 to 19), `FileProjection`, `PublicDerivative` (and `q2`), `ManifestText` (`asom.manifest-text/1`) |
| `AsomConformanceKit` | The logic behind the CLI, so that tests can call it (`Conformance` for the r0 set, `R3` for the r3 set). Not a product. Not in the plan's file tree (`ERRATA.md` E-08) |
| `asom-conformance` | The CLI (`Sources/asom-conformance/main.swift`) |

## Running it

```sh
export PATH=/opt/swift/usr/bin:$PATH             # this container; elsewhere a Swift 6.1 toolchain
swift build --package-path apple
swift test  --package-path apple
swift run --package-path apple asom-conformance lines M01,M02,M03,M04,M05,M06     # this lane's own verdicts, sorted, one per vector
swift run --package-path apple asom-conformance check                               # every verdict and value against the vector's own expectation
ASOM_DIAGNOSTIC_DRIFT_FLAG=1 swift run --package-path apple asom-conformance check  # diagnostic reading of finding F-1 (see below)
swift run --package-path apple asom-conformance show M05-101                        # the observed value of one vector
python3 apple/ci/lane_diff.py jvm.lines swift.lines --known apple/ci/known-disagreements.txt --not-implemented apple/ci/not-implemented.txt
```

The vector directory is `ASOM_CONFORMANCE_DIR`, else `lab/conformance`, else `docs/design/mesh/manifest-vectors`. A directory whose `VERSION` is `0.2.0` is the r3 set;
anything else is the r0 set of half I0a (M02 and M03, DSSE layer only, `check` and `sigcheck`). The CLI refuses a confVersion it does not understand instead of guessing.

## What is covered (r3, `lab/conformance`)

| Family | Vectors | This lane |
|---|---|---|
| M01 (JCS, strict JSON, base64, DER codec) | 105 | all |
| M02 (verify, accept) | 18 | all |
| M03 (verify, reject) | 71 | all |
| M04 (derive) | 94 | 48: `test` (22), `sustain` (9), `percentile` (2), `doc` (15). **Not** the 46 run-plan, governor, executor-trace and consent vectors (`plan`, `consent`, `fsm`, `ceilings`, `pins`, `trace`) |
| M05 (render) | 42 | all: the manifest text (12) and the `asom.text/1` body (30) |
| M06 (derivatives) | 10 | all: `q2`, the public derivative, the FILE projection |

`lines` prints 294 lines. The JVM runner prints 340 for the same families; the other 46 are the M04 kinds above.

## The lane diff

`apple/ci/lane_diff.py` compares the two lines files **per vector** and names each difference with both verdicts. Two explicit lists sit beside it:
`known-disagreements.txt` (37 vectors, finding F-1) and `not-implemented.txt` (46 vectors). It fails on a disagreement that is not listed, on a listed one that now agrees,
and on a vector one lane printed and the other did not. Against the JVM lane on the day this was written: **257 agree, 37 disagree (all F-1), 46 not implemented**;
with the one ambiguity F-1 set aside (`ASOM_DIAGNOSTIC_DRIFT_FLAG=1`): **294 agree, 0 disagree**.

**F-1 in one paragraph.** The JVM lane's projection of `results[]` adds a row flag `thermal-drift` when an M04 test drifted (design B7). benchmark.md 13.3 lists the flags of a projected row and has no such flag.
The Swift lane follows the list, so it says `DERIVATION_MISMATCH` at step 15a for every document that carries the flag. Which is right is a spec decision; the diagnostic switch exists only to show what
remains once it is set aside, and the gate diff never uses it. Details, and every other reading taken where the spec is silent, are in `ERRATA.md`.

## What the evidence is, and is not

- **Evidence labels:** `LAB`, `CI (hosted VM) evidence`, `SIMULATOR`. Every artefact is UNSIGNED. **NOT DEVICE EVIDENCE.**
- **Independence is not claimed.** The vectors stay `oracle: self`. The author of half I0b did not read `lab/manifest/src`, `lab/bench-core/src`, `lab/json/src`, the generators or the runner's family adapters,
  but did read all of `lab/ERRATA.md` (which states the JVM lane's readings), the vectors' inputs and expected outputs before writing the code, and `bench_ref.py` for the formatters. Eight clauses were then set from the
  vectors (`ERRATA.md` E-17). At most this is cross-lane agreement, and the agreement on those clauses is not evidence about the spec.
- **What was actually run:** `swift build`, `swift test` (XCTest, Linux, swift-crypto, no JDK), the conformance CLI in `lines` and `check` mode, the JVM runner's `lines` mode through Gradle, `lane_diff.py`,
  actionlint on the workflow. **Not run:** anything on macOS, CryptoKit, Xcode, the iOS simulator, `xcodebuild`, the `swift:6.1-noble` container, or GitHub Actions. Those jobs are written and syntax-checked only.
- **Does not prove:** anything about UIKit, SwiftUI, Network.framework, the Secure Enclave, App Attest, file protection, or any iPhone or iPad. It does not prove that CryptoKit behaves like swift-crypto on every input;
  the workflow's macOS job is the first place that is tested, and the two Swift builds' lines files are diffed against each other there.
- The verifier verifies. It is not a signer: `signPresentation` is not built. Nothing here decides who may see a manifest or whether a benchmark is honest.

`ERRATA.md` records every place a spec defect or ambiguity forced a choice, and every disagreement with the JVM lane.
