# apple/: the Swift lane, half I0a

**Status: LAB, UNSIGNED, ships nothing.** No listener, no network access at runtime, no key material outside tests.
This is half I0a of `PLATFORM_PLAN.md` section 6 (step I0 = MC0 = L0.7): the pure Swift targets for JSON, DSSE and ES256,
a conformance CLI, and the CI workflow `.github/workflows/apple-ios.yml`. Half I0b (`AsomManifest`, the r3 verifier;
`AsomBenchCore`) waits for the lab's r3 vectors and is not started.

## The boundary

- **ES256 only.** ECDSA P-256 with SHA-256, one signature, 64-octet raw r||s. No Ed25519, no RSA, no ML-DSA.
  Producers here normalise to low-S (C2); the verifier accepts high-S.
- **An independent Swift implementation, written from the spec prose** (`docs/design/mesh/LAB_SPEC.md` section 4).
  It is **never linked into the Mac node** (the Mac node is the JVM lane) and shares no code with the JVM lab.
- **No `JSONSerialization` on any signed byte.** `AsomJSON` is a hand-written tokenizer. `AsomJSON` and `AsomDSSE`
  do not import Foundation; only the conformance kit and the tests do.
- **CryptoKit on Apple, swift-crypto on Linux**, from one source: `#if canImport(CryptoKit) import CryptoKit #else import Crypto #endif`.
  swift-crypto is a dependency only under `.when(platforms: [.linux])`, so an Apple build carries no third-party crypto.

## Targets

| Target | What it holds |
|---|---|
| `AsomJSON` | `StrictJSON` (LAB_SPEC 4.2 rejects: duplicates, fractions, exponents, `-0`, `NaN`, beyond +-(2^53-1), lone surrogates, invalid UTF-8, BOM, depth over 16, trailing data), `JCS` (RFC 8785 integer profile, keys ordered by UTF-16 code units), `Base64Strict` (`b64either`, canonical), `RejectCode` |
| `AsomDSSE` | `PAE`, `SPKI` (strict 91 bytes, curve equation checked with our own 256-bit arithmetic), `ES256` (verify, `ES256Signer` with low-S and per-export keys), `SignatureCodec` (DER to raw and back, low-S), `NodeIdentity` (pin, nodeId, nodeTag, display and export fingerprints, TEST-ONLY deny-list), `DSSEEnvelope` (verifier steps 1 to 10 of LAB_SPEC 4.6, and the container producer) |
| `AsomConformanceKit` | The logic behind the CLI, so that tests can call it. Not a product. Not in the plan's file tree (see `ERRATA.md` E-08) |
| `asom-conformance` | The CLI (`Sources/asom-conformance/main.swift`) |

`AsomManifest` (typed decoder, steps 11 to 19, projections, the M05 renderer) and `AsomBenchCore` are **not built**.

## Running it

```sh
export PATH=/opt/swift/usr/bin:$PATH             # this container; elsewhere a Swift 6.1 toolchain
swift build --package-path apple
swift test  --package-path apple
ASOM_CONFORMANCE_DIR=docs/design/mesh/manifest-vectors swift run --package-path apple asom-conformance lines M02,M03
swift run --package-path apple asom-conformance check M02,M03      # every verdict against the vector's own expectation
swift run --package-path apple asom-conformance sigcheck           # signature layer, format of manifest-vectors/crosscheck.out
```

The vector directory is `ASOM_CONFORMANCE_DIR`, else `lab/conformance` if it exists, else
`docs/design/mesh/manifest-vectors`. `lab/conformance` does not exist yet. The unit tests use `ASOM_CONFORMANCE_DIR` if set and
otherwise the r0 directory, so they do not break when the lab's r3 set (confVersion 0.2.0) lands; the CLI refuses a
confVersion it does not understand instead of guessing.

## Which vectors are covered

The vectors that exist today are the r0 generation (`confVersion 0.1.0`, `oracle` self, status `illustrative`) in
`docs/design/mesh/manifest-vectors/`. **They are not the r3 set**: the lab regenerates M02, M03, M05 and M06 under 0.2.0. Nothing here
claims to be the r3 set and no vector was invented.

| Family | Vectors | What this lane does with them |
|---|---|---|
| M02 (9) | 101, 104 to 109 | steps 1 to 10 pass. **Not printed as `ok`**: `ok` needs steps 11 to 18 (half I0b) |
| M02 | 102, 103 | retired by LAB_SPEC 4.9 (TOFU). Skipped, reported as `retired-r3` |
| M03 (28) | 101 to 106, 113, 114, 117, 118, 123, 125, 127, 128 (14) | **decided at steps 1 to 10 and printed**: `SIGNATURE_INVALID`, `SIGNATURE_ENCODING`, `KEY_NOT_PINNED` (103 and 127), `NON_CANONICAL`, `DUPLICATE_KEY`, `NON_INTEGER_NUMBER`, `PAYLOAD_TYPE_UNSUPPORTED` (113, 114), `SIGNATURE_COUNT`, `SCHEMA_MAJOR_UNKNOWN` (118), `TRAILING_DATA`, `ENCODING`, `MALFORMED_JSON` |
| M03 | 107 to 112, 115, 116, 119 to 121, 124, 126 (13) | steps 1 to 10 pass; the expected reject is raised at step 11 or later (half I0b). Not printed |
| M03 | 122 | retired (TOFU `KEY_CHANGED`) |
| M03-127 | | run as FILE mode with no `signer.spki`, per its r3 relabelling |
| M01, M05, M06 | none | reported `not-implemented`; the r0 directory has no M01 file. M01-001 (an r0 seed from `conformance-examples/seed-vectors.json`) is exercised by the unit tests only |

`lines` therefore prints **14 lines** and no `ok` line. A whole-file `diff` against the JVM runner's lines will differ by construction
until `AsomManifest` exists; the lane-diff must compare the ids present (see the TODO in the workflow).

## What the evidence is, and is not

- **Evidence labels:** `LAB`, `CI (hosted VM) evidence`, `SIMULATOR`. Every artefact is UNSIGNED. **NOT DEVICE EVIDENCE.**
- **Independence is not claimed.** The vectors stay `oracle: self`. The author of this half read the design spikes
  (`spikes/macos-spike`, `spikes/ios-spike`, `manifest-vectors/VerifyDsse.java`) and the r0 vector files, worked in a full checkout,
  and did not use a sparse checkout, so the condition of LAB_SPEC 4.10 (no access to generator source) is not met and
  R3-OVERCLAIM-7 says it cannot be clearly met by self-declaration. At most this is cross-lane agreement.
- **What was actually run:** `swift build`, `swift test` (XCTest, Linux, swift-crypto, JDK-free), the conformance CLI, actionlint on the
  workflow. **Not run:** anything on macOS, CryptoKit, Xcode, the iOS simulator, `xcodebuild`, the `swift:6.1-noble` container, or
  GitHub Actions. Those jobs are written and syntax-checked only.
- **Does not prove:** anything about UIKit, SwiftUI, Network.framework, the Secure Enclave, App Attest, file protection, or any iPhone or
  iPad. It does not prove that CryptoKit behaves like swift-crypto on every input; the workflow's macOS job is the first place that is tested.
- The DSSE layer is **not** the manifest verifier: it does not decode the schema, check time, challenge, consistency, rollback or tier.

`ERRATA.md` records every place a spec defect or ambiguity forced a choice.
