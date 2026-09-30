# apple/ERRATA.md: spec defects and ambiguities met by the Swift lane, half I0a

`docs/design/mesh/REVIEW_ROUND3.md` lists 39 known spec defects. Where one touches this half, the conservative reading was taken
and is recorded here. Where the spec is silent or ambiguous and no review finding covers it, the same rule applied and the entry
says so. Nothing below was decided silently. IDs are local to this file.

## Review-round-3 defects that touch this half

**E-01. R3-CONFORMANCE-2 (the `git diff --exit-code` isolation check cannot fail).**
PLATFORM_PLAN P2 asks every non-Android workflow to run `git diff --exit-code -- core server gradle ...` after checkout, which is always
clean. `apple-ios.yml` instead runs job `root-unchanged` with `fetch-depth: 0`: on a pull request it diffs `origin/<base>...HEAD`, on a
push `<before>..HEAD`, over the same protected paths. When no base exists (a new branch, `workflow_dispatch`) it prints a notice and
does not run. Not done: the reviewer's negative-control branch that touches `settings.gradle.kts` and must fail, because it needs a hosted run.
Still not covered: a root edit merged in a separate change.

**E-02. R3-OVERCLAIM-7 and R3-CLOSURE-12 (independence of the Swift lane is unenforceable).**
This lane is **not** independent and does not claim to be. It was written in a full checkout that contains the design spikes,
`manifest-vectors/` (including `gen_manifest_vectors.py`, whose source was not opened, and `VerifyDsse.java`, which was) and
`conformance-examples/`. The spike Swift (`spikes/macos-spike/swift-es256`, `spikes/ios-spike/AsomKitSpike`) was read. What it shares with
them: the `#if canImport(CryptoKit)` idiom, the 26-byte SPKI prefix constant (a fixed DER string from the spec), and the idea of testing
against the JCA column. The code itself was rewritten. The vectors keep `oracle: self`; nothing here may be recorded in `PROGRESS.md`
as independent agreement. At best it is cross-lane.

**E-03. R3-CLOSURE-4 (L0.2 needs derive() from L0.3).**
Half I0a is the L0.2a-shaped scope the reviewer proposed: JSON, JCS, DSSE/ES256, key formats, and verifier steps 1 to 10. Steps 11 to 19
(typed decoder, `derive(bench)`, consistency, rollback, tier) are not built, so no accept vector can be given a full `ok`.

**E-04. R3-CLOSURE-1 and R3-CLOSURE-2 (FILE audience under-specified; siblings not self-contained).**
Only the DSSE-level part of FILE mode is implemented (key from `signer.spki`, fingerprint compare, `PINNED_BY_FINGERPRINT`, `SIGNER_UNVERIFIED`).
The projection, the subject and the export self-check are half I0b. This half read `manifest.md` section 8.3 for the code meanings.

## Conservative readings taken where the spec is silent (steps 1 to 10)

**E-05. Container shape (step 3, and the shape of `signatures[0]`).** A top-level value that is not an object is `CONTAINER_INVALID`.
A missing or different `asomCapabilityManifest` (2, `"1"`, `true`, absent) is `CONTAINER_VERSION_UNKNOWN`. A `dsse` that is not an object,
or a `payloadType`, `payload` or `signatures` of the wrong type, is `CONTAINER_INVALID`. If `signatures[0]` exists but is not an object, has no
string `sig`, or has a non-string `keyid`, that is `CONTAINER_INVALID`, raised **before** steps 4 and 5 (the spec does not say where such a defect goes).
`FILE`: a `signer.spki` that is not a string is `CONTAINER_INVALID`; one that is not strict base64 is `ENCODING`.

**E-06. Newer payload-type major (step 4).** `application/vnd.asom.manifest.v<N>+json` with N a canonical decimal (no leading zero, not `1`, any length)
is `SCHEMA_MAJOR_UNKNOWN`. `v0`, `v01`, `v`, `v1x`, `v-2` and every other type are `PAYLOAD_TYPE_UNSUPPORTED`. Comparison is by UTF-8 bytes.

**E-07. Error priority of the strict parser (section 4.2 says "in this order of checks").** A single pass cannot check a whole document for
every class in table order, so: structural errors (not one JSON value; a BOM; a stray byte outside a string; a raw control character; a bad
escape) stop the parse at once and win; the other classes are recorded while parsing goes on and the lowest table row wins
(`INVALID_UNICODE` 2, `NON_INTEGER_NUMBER` 4, `NUMBER_RANGE` 5, `DUPLICATE_KEY` 6, depth 7, `TRAILING_DATA` 8). Consequences: invalid UTF-8 **inside a
string** is `INVALID_UNICODE`, but a non-ASCII byte **outside** a string is `MALFORMED_JSON` (it is not part of a JSON value); a document that
nests deeper than 16 stops at the 17th level, so a structural defect after that point is never seen. Number lexemes: `NaN`, `Infinity`, `-Infinity`,
`01`, `1.`, `1e3`, `-0` are `NON_INTEGER_NUMBER`; `+1`, `.5` and a lone `-` are `MALFORMED_JSON`; 17 or more digits, or a value above 2^53-1, is `NUMBER_RANGE`.
Whitespace before and after the value is allowed.

**E-08. Deviations from the PLATFORM_PLAN file tree.** Strict base64 is in `AsomJSON` (the assignment places it there; the plan lists it under `AsomDSSE`).
The extra target `AsomConformanceKit` holds the logic behind the CLI so that tests can call it. `AsomKit-Package` is the scheme SwiftPM
generates for a package named `AsomKit` with library products; that could not be checked here (no Xcode). swift-crypto is `3.15.1 ..< 5.0.0`; it resolved to 4.5.2 here;
the spikes agreed on 3.15.1 and 4.5.2, and this lane was also run against 3.15.1 (see `PROGRESS.md`).

**E-09. Unicode.** Swift `String ==` is canonical equivalence (`"e\u{301}" == "\u{e9}"`, and the Kelvin sign equals `K`). Every comparison the profile defines
(member names, duplicate detection, `keyid`, payload type) is by UTF-16 code units or UTF-8 bytes. Fingerprint normalisation uppercases **ASCII only**: Unicode
uppercasing could turn a non-ASCII character (dotless i) into a base32 letter and accept an input that is not the fingerprint.

**E-10. Order of checks in step 7.** `keyid != nodeId(K)` is tested before the SPKI shape, exactly as written, so a document with a wrong `keyid` and a
compressed key is `KEY_NOT_PINNED`, not `ALG_UNSUPPORTED`. A test pins this so that a reorder is noticed.

**E-11. DER codec.** The codec accepts `r = 0` or `s = 0` (`3006020100020100`): range is checked at verify (`SIGNATURE_INVALID`). It rejects a long-form length even where the
value would fit, because 70 bytes is the maximum and short form is therefore the only minimal one. Every DER defect is `SIGNATURE_ENCODING`.

**E-12. `r`/`s` range check.** `ES256.verify` checks `0 < r < n` and `0 < s < n` itself, then calls the library. The library also refuses them, and the code is the same, so removing
our check is an equivalent mutant (mutation M20 survived, see `PROGRESS.md`). It stays as the spec's explicit step, not as a tested behaviour.

## Facts about the vectors that were found, not guessed

**E-13. The JVM column.** `manifest-vectors/crosscheck.out` holds the signature-layer verdict of `VerifyDsse.java` (`<id> sigValidUnderKey1=<bool>`), **not**
`<id> ok|reject` lines. There is no JVM lines file until the lab's runner exists, so the diff in the gate is signature layer against signature layer. Two lines differ, both explained:
`M03-123` (trailing data after the container: the regex-based JCA check never parses, so it reports `true`; this lane's strict parse refuses first; the spike saw the same
difference) and `M03-125` (a signature whose last base64 character has non-zero unused bits **and** different data bits: a lenient decoder (Python's, checked; and, judging by its `false`, the JCA one) accepts it
and gets a different `s`, so the JCA column says `false`; this lane refuses it as `ENCODING` before any signature check). The other 35 lines are identical.

**E-14. r0 versus r3.** The r0 accept documents carry `derived`/`render` members and TOFU contexts that r3 forbids or retires, so they can only ever pass the DSSE layer here,
never a full verify. M02-102, M02-103 and M03-122 are retired by LAB_SPEC 4.9; M03-127 is run as FILE mode with no `signer.spki`, per its r3 relabelling.
The CLI refuses `confVersion` other than `0.1.0` and any context `mode` it has no mapping for, instead of guessing an r3 mapping.

**E-15. `lines` prints 14 of 37 vectors and no `ok` line.** This is deliberate (see README). A vector that passes steps 1 to 10 is not known to be `ok`.

## Not done, on purpose

- No `AsomManifest`, no `AsomBenchCore`, no M05/M06, no lane-diff or jvm-lines job (their dependency, the lab's `lines` mode, does not exist).
- No constant-time claim: `U256` and the fingerprint comparison handle public data only, and the optimiser is not constrained.
- No iOS code, no Secure Enclave, no Network.framework.

## E-15 (orchestrator, 2026-09-30): root-unchanged job replaced

The E-01 job diffed the protected paths against the PR base branch. On the v1 review PR that base is `main`, so every v1
change counted and the job failed on its first hosted run. It now runs `lab/tools/isolation.py` (pinned to
`lab/LAB_BASE_SHA`). Lesson: a check written without a hosted run has not been shown to pass.
