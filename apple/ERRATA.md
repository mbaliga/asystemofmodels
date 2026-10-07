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

## Half I0c (the finish of "Not done in I0b"): readings, blocked items and findings for the lab

Everything below is LAB evidence from this container (Swift 6.1, Linux, swift-crypto; no macOS, no CryptoKit run). Appended; nothing above was edited, and where an earlier line says "not built"
(the signer in E-18, the list "Not done in I0b, on purpose") this section supersedes it. The assignment ran in two sittings: a first builder was stopped by a usage limit with the work
uncommitted, and a second builder (this author) took the tree over, rebuilt and re-ran everything, and wrote this section. Parts that the second builder reviewed and re-ran are the whole
suite and the gates; parts it did not re-derive from the spec line by line are named in `PROGRESS.md` ("not re-verified").

### What was read, and what was not, in I0c

Read: `CLAUDE.md`; `LAB_SPEC.md` 4.6, 4.7, 4.10, 5, 6.6; `benchmark.md` 5.1, 5.2, 11.1 to 11.7; `ASOM_MESH_DESIGN.md` B5, B7, B9 (the lines the specs cite); `REVIEW_ROUND3.md` R3-CLOSURE-5, R3-OVERCLAIM-1; the vector files
`lab/conformance/bench/M04-derive.json`, `lab/conformance/router/M08-claim-tracker.json`, the lab's TEST-ONLY keys file; `lab/ERRATA.md` ERR-BENCH-3, -4 and -10 (as three lines printed by a search for the word "drift", when the drift question of F-4 was checked).
Also seen, as single lines printed by searches: one line of `lab/bench-core/.../Project.kt` (the `thermal-drift` flag, F-1) and the file names of `lab/` sources.

Not read: `lab/mesh-router/**` (the JVM claim tracker), the conformance runner's M08 and M04 adapters, `lab/bench-core/src` beyond the one line above, `lab/ERRATA.md` outside the three entries named. The M08 tracker is therefore
a second implementation for the oracle rule of LAB_SPEC 4.10 in the sense that its author worked from the spec text and the vector files; `AsomRouterCore/ClaimTracker.swift` carries the same statement in its header, written by the first sitting's builder.
The second builder could not verify what the first one opened beyond that header and the code itself, which shows no sign of the Kotlin shape. It is **not** independence in the strong sense: the author read the vectors' expected values (so
the agreement on any clause a vector pinned says nothing about the spec), shares the repository with the lab, and the vectors are `oracle: self`. **No oracle tag was changed.**

### Readings

**E-29. The executor-trace vectors (M04-301 to -345, 26 vectors) are BLOCKED(trace).** Each pins a whole event log and a document hash. Its lines contain numbers that only the lab's `FakeBenchEngine` can produce
(`LOAD T1 coldUs=2388000 warmUs=618760/617520/622480`, the contention and abort times such as `ABORT CHARGER_REMOVED at 165216 ms`, `SUSTAIN end reason=PLATEAU windows=25`) and the per-preset scripts behind them. benchmark.md 5.4
gives the executor as pseudocode and benchmark.md 21 item 4 ("an injectable timing model (`a + b * bytes` per token) and a scripted thermal curve") names the fake without giving a model, a preset, a curve or the log grammar.
A Swift executor that printed these lines would be a transcription of the expected values, so it was not written. The governor's edges (E-37) are built and tested; the executor is not.

**E-30. The quick and ci run plans (M04-401, -403) are BLOCKED(plan); the standard plan (M04-402) is built.** benchmark.md 5.2 gives the standard plan as JSON, member for member; the table of 5.1 describes quick and ci in prose
(tiers, tests, reps, no sustain). No spec text fixes their JCS members (whether `depthReps`, `sustain` or `coolDown` are absent or null, which tier lists they carry), so their `jcsBytes` and `planSha256` cannot be derived without guessing, and a guess
checked against the vector's hash is fitting. `RunPlan.jcsValue` is `nil` for them. The standard plan reproduces M04-402 (948 JCS bytes, `U5x4LJS7...`). A document's recorded `planSha256` is still only checked for shape (E-22).

**E-31. The consent sheet.** The spec gives the wording of one sheet (benchmark.md 11.1: standard, with a download and the 8B opt-in); M04-420 pins its SHA-256 and this lane reproduces it. The wording for any other sheet is **this lane's own**:
the quick/extended/sustained/battery variants, the no-download variant, and the heat-test-again line (11.6 rule 3 gives only the label "Run the heat test again today", not where the sheet says that a heat test already ran, nor its words).
M04-425 pins the hash of that sheet, so it is BLOCKED(consent). `ConsentSheet.wordingIsSpecified` says which inputs have spec wording. The token rules (hash of the shown text, 5 minutes, one run, one plan) are the spec's and are tested at the boundary
(299,999 ms passes, 300,000 ms is refused).

**E-32. Ceilings (benchmark.md 11.3).** The table is implemented per platform with the thresholds as written (Android headroom 950 permille, battery 42.0 and 44.0 C, battery below 200 permille; iOS thermal serious, Low Power Mode, battery below 20%; macOS serious soft,
critical hard, laptop off AC hard; Linux code 3 soft, code 4 hard, battery 45.0 C, Deck GPU busy for 10 s). Where several ceilings hold at once the spec gives no order; this lane returns the hard before the soft one, and among hard ones thermal, then battery,
then the rest (the nine M04 vectors each name one ceiling). The end reason names come from 6.4 where it has them (`THERMAL_SOFT`, `THERMAL_HARD`, `BATTERY_TEMP`, `CHARGER_REMOVED`). Three names are taken from the vectors, not from the spec: `BATTERY_TEMP` for a battery level under 200 permille (M04-443; 6.4 has no battery-level reason), `THERMAL_HARD` for iOS Low Power Mode (M04-444), and `DEVICE_BUSY` for the Deck's GPU rule (M04-447; 6.4 does not list it, 11.4 uses it for the third yield). The memory-pressure, Stop, backgrounded and wall-cap rows of the "all" line are events of the executor, not thresholds, and are not built (E-29); the vectors' `presence` object (`foreground`, `screenOn`, `batterySaver`) and `power.source` for non-laptops are ignored by this lane (only `lowPowerMode` is read), and no vector has `foreground: false`. "Thermal status SEVERE" is read as code 3 of 6.1.

**E-33. Independence (M08).** See "What was read" above. LAB_SPEC 4.10 asks for a second implementation whose author did not read the first; the second builder cannot prove what the first one read, so the statement is: the header says the Kotlin tracker and the M08 adapter were not opened, the second builder did not open them, and the vectors' expected values were read.

**E-34. Interpolation rounding of the decode curve.** 6.6 says "decodeAt ... piecewise linear" (R3-CLOSURE-5 item 10) and fixes no rounding. This lane clamps outside the curve and between two points takes
`floor((lo.rate * (hi.ctx - p) + hi.rate * (p - lo.ctx)) / (hi.ctx - lo.ctx))`. `ClaimTrackerTests.testDecodeCurve` pins the clamps and the interpolation; the floor is the lower rate, the conservative direction for a claim that observation may only lower.

**E-35. A new claim seq (6.6, "restarts W but inherits DISCREPANT").** This lane restarts the window **and** the recent observations of the discard budget, because observations of the old claim are ratios against a different claim row; strikes survive. No vector decides whether the budget restarts
too. Recorded as a reading, and as the less conservative one for the peer: keeping the old budget would be harsher.

**E-36. How the vectors name the claim key.** The M08 inputs carry no claim key; `acceptedSha`, `heldBackend` and `heldCommit` appear as `null` or as a value. The adapter reads `null` as "equal to the claim row's" and a value as "different from it". This is a reading of the vector format, not of the spec.

**E-37. Governor edges (benchmark.md 11.4 with B9).** The 21 edges are the ones M04-430 lists, and the table equals that list. The diagram of 11.4 draws most of them; four are not drawn and are taken from the vector (and from "any ... -> ABORTING"): `PREFLIGHT>ABORTING`, `PREPARING>ABORTING`, `COOLING>FINALIZING` and `YIELDED>FINALIZING` with the spec's "3rd yield". Every other pair is refused (`GovernorTransitionRefused`), a test sweeps all 100 pairs.
`ABORTING>FINALIZING>DONE` is design B9; the diagram's `ABORTED` state is not a state of this lane.

**E-38. The tracker's link estimate is the warm-path one (R3-CLOSURE-5 item 11).** `predicted` uses `rtt + ceilDiv(B * 8, kbps)` with no cold-handshake term, because `tBody` is taken after the handshake and a cold term would raise the ratio in the peer's favour. `LinkEstimate.sessionWarm` is carried and never read there.

**E-39. The padding residual (R3-OVERCLAIM-1) is real and is not fixed here.** `outBytes` counts the bytes of the answer text as received, so padding that the owner cannot see (trailing whitespace, newlines, zero-width characters) counts. The spec's remedy (6.6, M08-017: "at most `bptCap / bpt` = 2") is not what the mechanism gives
(the inflation is bounded by `maxTokens * bptCap / trueBytes` and by `RATIO_CAP`). This lane implements 6.6 and M08-017/-018 as written; the review's suggested normalisation is not in 6.6, so it is not built. `effRatio = min(1000, ...)` still means that a tracked rate never exceeds the claim.

**E-40. The signer (LAB_SPEC 4.7).** `ManifestSigner.signPresentation` follows the pseudocode: `own` needs a 32-byte challenge and the node key, `issuedAtMs = nowMs`, `expiresAtMs = nowMs + 600000`; `file` is signed by the key the caller passes (tests pass the lab's TEST-ONLY per-export keys; production passes `ES256Signer.generateEphemeral()`),
projects the body with `FileProjection`, and sets `issuedAtMs` to the UTC day; the node key is never used for a file. The container comes from `DSSEEnvelope.seal` (low-S, raw r||s, strict 91-byte SPKI, JCS integer profile). The result is verified by `ManifestVerifier` in the same mode before it is returned; a reject
is `MANIFEST_UNAVAILABLE` and nothing is returned. `SelfCheck.productionKeys` is true by default, so a document under a published TEST-ONLY key is refused unless the caller says it is a test. `nextSeq` is `max(stored + 1, nowMs / 1000)`; making `seq` durable before the first signature is the caller's.
The discarding of the per-export key and the "when `JCS(bodyOwn)` changes" test are the caller's too. ES256 signing is random-nonce here (swift-crypto; CryptoKit on Apple): signature bytes are never compared across lanes, only verification is. The test keys are read from `lab/conformance/keys/TEST-ONLY-keys.json` at test time; no private scalar is in `Sources/` (checked by a search for all four `d_hex` values).
The CryptoKit signing path is **UNVERIFIED** (not run here).

**E-41. The cross-lane fixtures (`apple/crosslane`).** 37 documents the Swift signer made (23 accepted by this lane's verifier, 14 rejected for twelve different codes; the rejected ones that the signer refuses to emit are made with `DSSEEnvelope.seal` plus a deliberate edit). The JVM lane's `lines M02,M03` over them (`apple/ci/crosslane.py`) gives, for all 37, the same verdict
as this lane and as the file records. The body is the own-form body of the lab's M02-114 (the accept vector whose `results` both lanes agree on, so F-1 does not touch the fixtures). A regeneration changes every signature byte and no payload byte; the committed files are reviewed artefacts, and a test checks that they still verify, that a regeneration gives the same payloads, and that exactly one committed signature is high-S
(the deliberate twin, M02-902). Only verdicts cross the lines interface: `pin`, `tier` and `bodyDigest` in a fixture's `expect.ok` are this lane's own observation.

### Findings for the lab

The lab was not edited. Each finding names the vector, the spec section and the evidence.

**LF-1. The row flag `thermal-drift` is in the JVM projection and not in benchmark.md 13.3. 37 vectors differ in verdict, 5 more in value.**
Vectors: M02-101, -104 to -113, -115 to -120; M03-111, -112, -124, -132, -133; M05-101 to -106, -108 to -111; M06-101, -103 to -106 (37, verdicts: JVM accepts or gives the later reject, Swift says `DERIVATION_MISMATCH` at step 15a), and the values of M04-042, M06-201 to -204.
Spec: benchmark.md 13.3 lists the flags of a projected row (`flags`, "open enum": `charging`, `thermal-throttled`, `background-load`, `low-runs`, `unstable`, `warm-start`, `numerics-*`, `confidence-<class>`); design B7 names the M04 test flag THERMAL_DRIFT and says it caps confidence at low; nothing says a row carries `thermal-drift`. `lab/ERRATA.md` (ERR-BENCH-3) records the drift rule and not the flag.
Evidence: `asom-conformance check` (spec-literal) reports 42 mismatches; with `ASOM_DIAGNOSTIC_DRIFT_FLAG=1` it reports 0 over 370 vectors; `AsomConformanceTests.testTheOnlyDifferenceInEveryListedVectorIsTheThermalDriftRowFlag` shows for every listed vector that the document's `results` equal the Swift projection once that one flag is removed; the lane diff of the diagnostic lines is empty (370 agree).
**This is not a verifier step-order difference**: the verifier's order is LAB_SPEC 4.6 steps 1 to 19 in both lanes; for the five shadowed vectors (M03-111, -112, -124, -132, -133) the JVM document embeds the same results, so the Swift lane stops at 15a before the step the vector tests. A ruling is the lab owner's: either 13.3 gains `thermal-drift` (and this lane gains one line), or the lab drops the flag and regenerates the documents and hashes that contain it.
Until then the Swift lane stays spec-literal and the 37 are listed in `apple/ci/known-disagreements.txt` with this reason.

**LF-2. The minimum number of kept reps for drift: the lab and this lane read B7 differently, and no vector decides it (F-4).** `lab/ERRATA.md` ERR-BENCH-3 says strictly slowing at every step needs 4 kept reps and first-versus-last needs 3; E-23 of this lane takes 3 and 2. design B7 gives no minimum. A vector that decides it: `tg128@d0`, three reps of 10.0 s, 10.3 s and 10.6 s (drift here, medium in the lab). Neither lane's reading is changed.

**LF-3. M05-211's description says "warn (1.3%)" and the expected text says `pass (1.2% from reference)` (F-6).** The text is consistent with benchmark.md 3.4; the description is off. No verdict difference.

**LF-4. The executor traces, the quick and ci plans, and the run-today consent sheet cannot be reproduced from the spec (E-29 to E-31).** 29 vectors, `oracle: self`, whose expected values come from the lab's fake engine, plan data and sheet wording that no spec section states. Suggested: publish the fake's timing model, presets and log grammar, the JCS of quick and ci, and the run-today sheet in benchmark.md, or mark those vectors as lab-implementation vectors rather than spec vectors.

**LF-5. M04-430 pins four edges that the diagram of benchmark.md 11.4 does not draw (E-37).** `PREFLIGHT>ABORTING`, `PREPARING>ABORTING`, `COOLING>FINALIZING`, `YIELDED>FINALIZING`. The first two follow from "any ... --> ABORTING"; the other two are readings. A one-line amendment of the diagram would settle them.

### Mutation checks (I0c code)

Recorded in `PROGRESS.md` ("Apple lane I0c gate"), with the mutants and what killed them.

## Not done after I0c

- The 26 executor traces, the quick and ci plans and the run-today consent sheet (BLOCKED, E-29 to E-31; `apple/ci/not-implemented.txt`).
- M07 (attestation, deferred), the `OWN` pin state (E-28), A2.
- Anything on macOS: the CryptoKit paths (the verifier and the signer), the iOS simulator job, and every hosted-CI job of `apple-ios.yml` are written and unverified (this container has neither a Mac nor a GitHub Actions run).
## Review fixes (track fix-crypto; findings CV-1 to CV-10)

An independent review of the verifier lanes produced ten findings. The JVM-side readings are in `lab/ERRATA.md` ERR-FX-CV1..10 and ERR-FX-FILES; this section records what changed in this lane and what the lane now does. Where a row below says "supersedes", the earlier row stays in place above and this one wins. Evidence label: LAB, `oracle: self`, NOT DEVICE EVIDENCE.

**ERR-FX-CV1 (Swift mirror of one-record, and a wrap fix it exposed).** `Consistency` ties `producer.harness.confVersion`, `producer.engine.{name,commit,buildFlags}`, `device.totalBytes`, `device.os.{family,version}`, `device.vendor`, `device.model` and `device.soc.name` to their `bench` twins, exactly as the JVM lane (the commit may be an abbreviation: one is a byte prefix of the other; strings compare by UTF-8 code units). `device.class` is not tied (open enum against a closed form). Existing tests that edited a device or confVersion copy alone now edit both copies (`testConfVersionFloorAndKnownBadList`, `testStringLimitsAreInCodePointsAndBidiControlsAreRefused`, `testAPayloadCannotWriteTheVerificationBlock`, `testOutputIsAsciiAndNeverNamesMlperf`), and the old single-copy edit is asserted as `INCONSISTENT`. Changing M02-113 so that the 96-code-point model also sits in `bench.device.model` exposed a REAL difference in `asom.text/1`: design T8 says "at most 72 characters per line", the JVM renderer splits a word longer than a line, and `TextRenderer.wrapDevice` kept it whole (M05-109 differed in line 20). `TextRenderer.wrap(lead:text:cont:)` is now a port of the JVM algorithm and is used for the device line AND the chip line (the chip line was not wrapped at all; the JVM wraps it). The notes still use `wrapNote`, which has no long words to split and was left alone.

**ERR-FX-CV2 (default `productionKeys` is true).** `VerifyContext.init` and `DSSEContext.init` defaulted `productionKeys` to `false`, so an integrator who omitted the argument accepted documents signed by the published TEST-ONLY keys; the JVM lane and `xcheck_manifest.py` default to `true`. Both Swift defaults are now `true` (fail closed). Every test and kit call site that relied on the old default now says `productionKeys: false` explicitly (27 sites: the conformance kit's r0 path and the r3 `verifyContext`, which still reads the vector's own `productionKeys` member, and the XCTest fixtures). `FixCryptoManifestTests.testDefaultContextsRefuseTheTestOnlyKeys` and `FixCryptoTests.testADefaultContextIsAProductionContext` pin it.

**ERR-FX-CV3 (a self-reported tier is a label, never a gate).** Supersedes the step 17/18 code of the first half. `Verified.tier` is still A1 for a hardware claim (display), but step 18 compares `ManifestVerifier.attestedTier` (A0: evidence is unused, A2 is deferred) with `requiredTier`. A1 and A2 are `TIER_INSUFFICIENT`. `VerifierTests.testTierFromKeyStorageAndTheRequiredTier` was edited to the new reading (it asserted `requiredTier` A1 accepted a StrongBox claim). Vectors M03-191, M03-192; M02-118 and M02-120 retagged to `requiredTier` A0.

**ERR-FX-CV4 (FILE presentation is exactly `{issuedAtMs}`).** The FILE branch now reads `presentation` with a `DecodeState(tolerateUnknown: false)` whatever the minor, as the JVM does. An extra member at `schemaMinor` 1 (for example an exact export time) is `SCHEMA_INVALID` (M03-193); without it the document is accepted (M02-121). The own presentation keeps the tolerant state (unit test).

**ERR-FX-CV5 (length folding).** `NodeIdentity.fingerprintMatches` and `ManifestVerifier.constantTimeEqual` folded `a.count ^ b.count` into a `UInt8`, so lengths 256 apart compared equal on the common prefix. Both now return false at once when the lengths differ (lengths are public) and otherwise accumulate the byte differences. M03-194 (26 + 256 characters) and unit tests with 1, 224, 255, 256, 512 and 1024 extra bytes.

**ERR-FX-CV6 (ASCII-only fingerprint normalisation).** Already this lane's reading (E-09); the JVM lane is aligned. M03-195 and M03-196 pin it for both.

**ERR-FX-CV7 (the scan goes on past depth 16). Supersedes the "stops at the 17th level" clause of E-07.** `StrictJSON` records the depth fault and keeps scanning with an iterative `skimDeep()` (an explicit stack of container frames, duplicate names tracked per object, the same string, number and literal rules), so rows 2 to 6 outrank row 7 wherever they sit (ERR-JSON-1). A 200,000-level document and a 200,000-level object chain with a fraction are unit tests (no stack overflow). M03-197..200.

**ERR-FX-CV8 (readings shared with the JVM lane). Supersedes E-07's "a lone `-` is `MALFORMED_JSON`" and records E-27's evidence reading.** The JVM lane moved to this lane's container readings (a non-object container, `signatures[0]` shape at step 3, a non-string FILE `signer.spki`, canonical-decimal majors: E-05 and E-06). This lane moved to the JVM lane's lexer readings (ERR-JSON-3): a letter run is one token (`truex`, `nullnull`, `Infinityx` are `MALFORMED_JSON`), a number run is one lexeme (`[1-2]`, `-`, `[-NaN]`, `[1+]` are `NON_INTEGER_NUMBER`; `-true` is `MALFORMED_JSON`), and an evidence item whose JCS form exceeds 32,768 bytes is `CONTAINER_INVALID` (step 15c). `StrictJSONTests.testRejectTable` row "lone minus" was edited from `MALFORMED_JSON` to `NON_INTEGER_NUMBER` for this reason. Vectors M03-201..214 and M03-208.

**ERR-FX-CV10 (the range half of the on-curve test).** `FixCryptoTests.testANonReducedCoordinateIsRefusedAndTheReducedOneIsNot` uses x = 5 (on the curve) and x + p (`ffffffff00000001000000000000000000000001000000000000000000000004`) and checks `P256Curve.isOnCurve` and `SPKI.validate`; M03-215 is the same through the verifier.

**Lane diff after the fixes.** `apple/ci/known-disagreements.txt` gained five ids, all of them vectors whose deciding step sits after step 15a and which F-1 therefore shadows under the spec-literal projection (M02-121, M02-122, M03-191, M03-192, M03-208; 42 in all). With the F-1 diagnostic switch the diff is empty (333 agree, 0 disagree, 46 not implemented); the XCTest suite has 176 tests (166 before). The new vectors live in `lab/conformance/manifest/M03-verify-reject-fx.json` (lab ERR-FX-FILES).

**Not done, on purpose.** `wrapNote` still keeps a long word whole (no vector reaches it). The CryptoKit path was not run (no macOS here); `P256Curve` and the strict JSON parser are the Swift lane's own code on both platforms, and the new tests use only them. The Windows and hosted-CI runs were not seen.
