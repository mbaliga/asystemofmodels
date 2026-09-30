# apple/ERRATA.md: spec defects and ambiguities met by the Swift lane, halves I0a and I0b

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

---

# Half I0b (`AsomBenchCore`, `AsomManifest`, r3 `lines` and `check`)

IDs: `E-16` onwards are readings taken where the spec is silent or ambiguous; `F-1` onwards are findings from the lane diff against
the JVM lane (the disagreement table is in F-1). Everything below is LAB evidence. The lab was not edited.

## What was read, and what was not (E-02 restated for I0b; R3-OVERCLAIM-7, R3-CLOSURE-12)

**E-16. Independence is not claimed for I0b either.** The vectors stay `oracle: self`; agreement of this lane with them is cross-lane
evidence, and `PROGRESS.md` says so.

Not read: `lab/manifest/src`, `lab/bench-core/src`, `lab/json/src`, `lab/*/tools`, `RegenerateVectors.kt`, the conformance runner's family
adapters (`ManifestFamilies.kt`, `BenchFamilies.kt`, `M01.kt`, `PureFamilies.kt`), and `gen_manifest_vectors.py`. The JVM implementation and
the vector generators were not opened.

Read, in full or in the parts named: `CLAUDE.md`; `OWNER_DIRECTIVES_2026-09-30.md`; `LAB_SPEC.md` 3.1 to 3.3, 4, 5; `manifest.md` 4, 8, 12, 14;
`benchmark.md` 2.2, 2.6, 3.4, 4, 5, 6, 8, 9, 11.3, 11.4, 12, 13; `ASOM_MESH_DESIGN.md` 5.2, 5.8 to 5.10, 6.5 to 6.8; `REVIEW_ROUND3.md`;
`PLATFORM_PLAN.md` 6; **all of `lab/ERRATA.md`** (the assignment allows the ERR entries; they state the JVM lane's readings of ERR-JSON-1 to 10,
ERR-CLOSURE-1, ERR-MAN-1 and 2, ERR-BENCH-1 to 10, so those readings were known to this author); `lab/conformance/` (vector files, README,
INDEX, the two JSON schemas, which LAB_SPEC 4.1 calls a test oracle); the conformance runner's `Main.kt`, `Family.kt` and `Suite.kt` (the lines
mode); and `docs/design/mesh/bench-examples/bench_ref.py`, **lines 226 to 240 (`mem_limit`) and 360 to 538 (the formatters and `render`)**, because
LAB_SPEC 5 and benchmark.md 12.6 cite `bench_ref.py` for the formatting functions. The reference's `derive`, `stat`, `derive_sustained` and
`project_results` were not opened before this lane's versions were written.

**E-17. The vectors were read before the code was written, and some clauses were then set from them.** The author read the vector files'
inputs and expected outputs during exploration (that is how the input kinds, the `answers` object of the M04 `doc` vectors and the M05 texts were
learned). Where the prose could not decide something, the vector's value was taken. They are listed so that nobody counts agreement on these
points as evidence about the spec:

1. The wording of four M05 body clauses the spec does not give: the virtual-machine banner (`  VIRTUAL MACHINE: results are capped at MEDIUM confidence.`, placed
   after the confidence line), the heat-test notes (`Heat test: the slowed-down speed had not settled when the test ended.` and `Heat test: stopped early at a safety limit.`), the
   abort note (`The run stopped early (<reason>); only the finished tests are shown.`) and the drift note (`speed drifted during the test.`).
2. The hand-wrapping of the role lines, in particular `PROVIDER FOR SMALL MODELS: ... to your` / `other devices.` (the reference's line exceeds 72 columns).
3. That question 3 says only "(estimated from the measured speeds)." when the heat test showed no onset (M05-208); the reference says "and heat test" whenever a thermal model is used.
4. The `Key storage:` words for `strongbox`, `os-keystore` and `ephemeral` (M05-101, -111, -103). The words for `tee`, `secure-enclave`, `tpm`, `file` and `unknown` are this lane's own and no vector constrains them.
5. The plural rule of the newer-format line ("1 item", "2 items"; the spec writes `item(s)`), and `REJECTED: <CODE>. Do not rely on this report.` with `Freshness: not confirmed.` for a report shown after a reject at steps 13 to 16 (the spec fixes the display rule, not the words).
6. The selection of the twelve curve points of the public derivative: index `(2*j*(n-1) + 11) / 22` for `j = 0..11` (M06-101). The spec says only "at most 12 evenly spaced points".
7. That an M04 `doc` vector with `audience: file` projects the OWN-form document with the FILE row shape and does **not** truncate the times first (M04-041). The
   spec's FILE projection (M06-201 to -204) is the one that truncates.
8. The deny-list also holds key3 and key4 of `lab/conformance/keys/TEST-ONLY-keys.json` (found by M03-167; data, not a reading).

## Readings where the spec is silent

**E-18. R3-CLOSURE-1 (the FILE projection is not defined), conservative reading.** `FileProjection.body` (LAB_SPEC 4.7 and design 5.8 read literally):
`audience` becomes `file`; `seq` is dropped; `subject` becomes the caller's per-export node id with `keyAlg ES256` and `keyStorage ephemeral`;
`device.platformIds` and `device.os.securityPatch` are dropped; `bench.device.osBuild` and `gpuDriver` become `null`; `bench.run.batteryStartPermille` and `screenOn`
become `null`; `bench.run.startedAtMs`, `endedAtMs` and every `bench.tiers[].startedAtMs` are truncated to the UTC day; `results` is recomputed as
`project(derive(bench_file), FILE)`, so `measuredAtMs` is the truncated tier start and a row's `conditions` holds only `charging` and `thermalStart`. Unknown members are kept.
The result reproduces M06-201 to -204 byte for byte (under the diagnostic reading of F-1). `signPresentation` (the signer) is **not** built: the assignment asks for the verifier, the projections and the renderer.

**E-19. R3-CLOSURE-2 and R3-CLOSURE-4.** The siblings are not self-contained; the parts read are listed in E-16. L0.2b (the full verifier with 15a) is implemented in one go with `derive`, as the lab did (ERR-CLOSURE-4), so no accept vector is decided by a stub.

**E-20. Decoded payload limit.** `manifest.md` 4.4 says "decoded payload at most 256 KiB" and LAB_SPEC 4.6 gives no step for it. It is checked at step 6, right after both base64 decodes, as `TOO_LARGE` (a change to `AsomDSSE/Envelope.swift`). A test signs a 300 KiB payload to show the check runs before the signature.

**E-21. Unknown members (P10).** At `schemaMinor` 0 (the minor this decoder knows) an unknown member anywhere in the payload is `SCHEMA_INVALID`; above it, unknown members are tolerated and counted in `unknownFields`
(M02-107). The five names of P7 (`derived`, `render`, `textSha256`, `field`, `custom`) are invalid at any depth of `body` at any minor. The standalone bench document is judged at minor 0.

**E-22. Typed decoder strictness where the spec and the schema are silent** (each is a `SCHEMA_INVALID`): a `presentation.challenge` must be 32 bytes of strict base64url, not only 43 characters of the alphabet; `bench.tiers` must be unique and in tier order (T0 to T5); a
prefill test at a depth other than 0 is refused (benchmark.md 13.4 R5: it cannot be carried); a prefill test needs its whole spans, one per rep, and a decode test must have none; the sustain tier must be one of the measured tiers; `energy` must be `null`; a tier's `sha256` must equal the compiled-in pin
(its `bytes` and `quant` are not compared: the projection takes them from the pin); `planSha256` is checked for shape only (no compiled-in plan hash is compared); a timestamp must be below 2100-01-01 (the schema's inclusive bound is one millisecond wider).

**E-23. M04 readings the spec does not settle, and how far the vectors pin them** (a sweep changed one reading at a time and counted vector mismatches):
- *Drift* (design B7). Drift is judged on the kept reps in time order, after the outlier rule: "strictly slowing at every step" needs at least **3** kept reps, "first minus last over 100 permille of the first" at least **2**. The vectors accept every pair from (2,2) to (4,4) and refuse only (5,x) and (6,6). **They do not tell 3 from 4.** A 3-rep test whose reps slow at every step is drift here; a reading that needs 4 kept reps says no drift. Quick-plan documents have 3 reps, so two lanes can differ on real data. See F-4 for a vector that would decide it.
- *Contention* for the caps of 9.4 is `max(contentionBeforePermille, contentionAfterPermille)`. No vector distinguishes max, before and after.
- The sustained phase's start class is the sustain **tier's** `startThermal` (the document has no field of its own); `run.startThermal` gives the same vector results.
- "Hard ceiling" (`HARD_CEILING` flag, cap at medium) is `THERMAL_HARD` and `BATTERY_TEMP`, the two ceilings named in an end reason; the other safety aborts are not. The vectors say `USER_STOP` is not one (M04-033); nothing distinguishes `BATTERY_TEMP`.
- `headroomAtOnsetPermille` is `null` when there is no onset. No vector has a headroom without an onset.
- The swap cap is 256 MiB (benchmark.md 8): no document vector has swap growth, so only the direct M04-019 exercises the flag. The kv ratio of Q2 uses the document's `kvBytesPerToken` and `bytes`; the pin's give the same numbers in every vector.
- Pinned by vectors, so not a free choice: `charging` needs a form with a battery (M04-047, M06-203); `low-runs` is true when **any** test of the tier kept fewer than 4 (M02 vectors); `runs` come from the test with the most discards (M06-203); the numerics flag; the sorted flag order.

**E-24. The projection when a tier cannot be carried.** A tier that has no prefill or no decode test, more than 4 of either, or a test with no value (kept fewer than 2 reps) has no row in `results`; nothing is invented. The manifest schema needs at least one point of each with a `p50` of at least 1.

**E-25. The projection's row flags.** The list of benchmark.md 13.3 is taken as closed: `charging`, `thermal-throttled` (the sustain tier with an onset), `background-load`, `low-runs`, `unstable`, `warm-start`, `numerics-warn|fail|not-run`, `confidence-<class>` (the minimum of the tier's tests and, for the sustain tier, the sustained result). See F-1 for the flag that this excludes.

**E-26. Public derivative.** The first prefill point and the first decode point of a row are used; `ttftMillis = q2(ttft.p50 / 1000)` (floor to milliseconds first); `peakProcessMB` uses 10^6 bytes (3,120,000,000 bytes is 3,120 MB, so 3,100 after q2; a MiB reading would give 3,000: M06-101); `throttleOnsetSec = q2(onsetMs / 1000)` or `null`; `ramClassGiB` uses GiB of 2^30 bytes; `osMajor` is the leading digits of `os.version`.
The `asom.bench-public/1` schema in `lab/conformance/manifest/schema` lists `osFamily` as `android|ios|ipados|macos|linux` while the manifest schema also allows `windows` and `ubuntu-touch`. A Windows or Ubuntu Touch manifest therefore produces a derivative that its own schema would refuse; this lane emits the value and does not invent a mapping. Recorded for the owner.

**E-27. Evidence (step 15c).** `evidence` may be absent or an array of at most 2 objects; anything else is `CONTAINER_INVALID`. No evidence type is read (A2 is deferred), so the DER size and depth limits have nothing to guard yet.

**E-28. The `OWN` pin state is not built** (a device's view of its own report). Only `PINNED`, `SIGNER_UNVERIFIED` and `PINNED_BY_FINGERPRINT(typed|qr)` exist, the three states the vectors use.

## Findings from the lane diff

`./gradlew -p lab :conformance-runner:run --args="lines M01,M02,M03,M04,M05,M06" --quiet` printed 340 verdict lines; the Swift lane prints 294 (the other 46 are the M04 run-plan,
governor, executor-trace and consent vectors, which this half does not implement). Per vector:

| family | JVM lines | agree | disagree | Swift: not implemented |
|---|---|---|---|---|
| M01 | 105 | 105 | 0 | 0 |
| M02 | 18 | 1 | 17 | 0 |
| M03 | 71 | 66 | 5 | 0 |
| M04 | 94 | 48 | 0 | 46 |
| M05 | 42 | 32 | 10 | 0 |
| M06 | 10 | 5 | 5 | 0 |
| total | 340 | 257 | 37 | 46 |

**F-1. The `thermal-drift` row flag (spec ambiguity; the JVM lane's reading is not in `lab/ERRATA.md`). 37 vectors, one cause.**
Design B7 says M04 flags `THERMAL_DRIFT` and caps confidence at low. benchmark.md 13.3 lists the flags a projected row carries and does not
list a drift flag; B7 did not amend that list. The Swift lane follows the list literally: a drifting test lowers the row's `confidence-<class>` and
adds no other flag. The JVM lane also emits `thermal-drift` (M02-101's T2 row has `["charging","confidence-low","thermal-drift","thermal-throttled"]`;
`grep -i thermal-drift lab/ERRATA.md` finds nothing). The document is signed, so `JCS(results)` differs and step 15a says `DERIVATION_MISMATCH`, one step
before the step that decides the vector. Which reading is right is a spec decision. The Swift lane did not copy the JVM's; with the JVM's flag emitted the
diff is empty (see below). Vectors, verbatim from `apple/ci/lane_diff.py`:

```
vector    jvm                                swift                              status
M02-101   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-104   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-105   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-106   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-107   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-108   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-109   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-110   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-111   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-112   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-113   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-115   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-116   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-117   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-118   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-119   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M02-120   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M03-111   reject ROLLBACK                    reject DERIVATION_MISMATCH         KNOWN F-1
M03-112   reject EQUIVOCATION                reject DERIVATION_MISMATCH         KNOWN F-1
M03-124   reject TIER_INSUFFICIENT           reject DERIVATION_MISMATCH         KNOWN F-1
M03-132   reject AUDIENCE_MISMATCH           reject DERIVATION_MISMATCH         KNOWN F-1
M03-133   reject CONTAINER_INVALID           reject DERIVATION_MISMATCH         KNOWN F-1
M05-101   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M05-102   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M05-103   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M05-104   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M05-105   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M05-106   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M05-108   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M05-109   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M05-110   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M05-111   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M06-101   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M06-103   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M06-104   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M06-105   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1
M06-106   ok                                 reject DERIVATION_MISMATCH         KNOWN F-1

vectors: jvm=340 swift=294 agree=257 disagree=37 (known 37) jvm-only=46 (not implemented 46)
```

Five more vectors have the same verdict in both lanes and a different **value** for the same reason (their `results` hash or the file body's flags): M04-042, M06-201, M06-202, M06-203, M06-204 (`asom-conformance check`, spec-literal: 42 mismatches; 37 + 5).
Five of the 37 (M03-111, -112, -124, -132, -133) are shadowed: their document embeds the same results, so under the spec-literal reading they never reach the step they test. The Swift unit tests cover those steps with a re-projected base document.

The diagnostic run (`ASOM_DIAGNOSTIC_DRIFT_FLAG=1`, which makes the projection emit `thermal-drift` and nothing else) prints 294 lines and the per-vector diff is empty: 294 agree, 0 disagree, 46 not implemented. `asom-conformance check` under it reports `checked 294, mismatches 0`. The workflow runs both diffs; the second must be empty.

**F-2. M01-149 (Swift bug, fixed).** `{} <0xFF>`: the lab says `INVALID_UNICODE` (row 2 checks the bytes of the whole input and outranks row 8), this lane said `TRAILING_DATA`. E-07 (half I0a) had read row 2 as "inside a string". The spec's table order and its wording ("invalid UTF-8 (overlong, surrogate code points encoded in UTF-8, truncated sequences)" with no position) support the lab's reading; `StrictJSON` now checks the bytes after the value too. The trailing data is still never parsed (M01-150). Pinned by six rows in `StrictJSONTests`. E-07 of half I0a is amended accordingly.

**F-3. M03-167 (Swift data bug, fixed).** In production mode a FILE document signed by key3 must be `TEST_ONLY_KEY`. The deny-list held key1 and key2, the keys of the r0 file; the lab's per-export keys key3 and key4 are TEST-ONLY too (LAB_SPEC 4.5: "these nodeIds"). The list now holds all four, and a test compares it with the lab's keys file.

**F-4. Undetermined: the minimum number of kept reps for drift (E-23).** No vector decides between "3 kept reps" (this lane) and "4 kept reps" for the strictly-slowing rule, or between 2 and 3 for the first-minus-last rule.
A vector that would: `tg128@d0`, three reps of 10.0 s, 10.3 s and 10.6 s (rates 12800, 12427, 12075; slowing at every step, 57 permille in total). Here it is drift (confidence low); under the other reading it is medium (`StatsTests.testThreeRepsSlowingAtEveryStepAreDriftInThisLane` pins this lane's answer). Needs a design ruling and a vector; not edited here.

**F-5. `lab/ERRATA.md` does not record the `thermal-drift` flag (F-1).** The JVM lane emits it (and `lab/bench-core/tools/xcheck_m04.py` mentions it); none of the ERR-BENCH entries does. Listed so that the lab track can add it.

**F-6. M05-211's description says "warn (1.3%)" but the expected text shows `pass (1.2% from reference)` for that tier.** The expected text is consistent with benchmark.md 3.4 (20 permille passes); only the description is off. Not a verdict difference.

## Not done in I0b, on purpose

- The M04 run-plan, governor, consent and executor-trace vectors (`plan`, `consent`, `fsm`, `ceilings`, `pins`, `trace`: 46 vectors). The assignment for I0b is derive, projection and the renderer. They are listed in `apple/ci/not-implemented.txt`, and the runner prints their count on stderr; a kind that silently vanished would show as a `jvm-only` problem in the diff.
- `signPresentation` (the signer), the claim tracker (M08), M07 (attestation, deferred), the `OWN` pin, A2.
- Anything on macOS: the CryptoKit path and the iOS simulator job are written and unverified (this container has neither).
