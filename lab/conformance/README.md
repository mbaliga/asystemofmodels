# lab/conformance: the vector suite (data, not a Gradle project)

Version: see `VERSION` (0.2.0). Spec: `docs/design/mesh/LAB_SPEC.md` section 3. Runner: `lab/conformance-runner`.
Evidence label: **LAB**, `oracle: self`, never device evidence.

## Files

| Path | Family | Notes |
|---|---|---|
| `wire/W00-constants.json` | W00 | frozen constants by reflection; W00-100..104 are `proposed` mesh additions (`proposed-skipped`: each awaits an owner ruling, D12.1 to D12.4, and the v1 contract is frozen; the runner has no checker for them) |
| `wire/W01-echo-headers.json` | W01 | hand vectors: the spec's W01-001..009 plus W01-010..014 |
| `wire/W01-generated.json` | W01 | 200 `proposed`, `generated` cost vectors (ERRATA `ERR-FMT-3`); a non-blocking lane |
| `wire/W01b-one-record.json` | W01b | recorded from the real `AsomServer`; W01b-reach is `proposed` (`proposed-skipped`: it awaits owner ruling D3; `:ledger-model` is built, and `RequestReachAndBytesTest.w01bReachHoldsThroughTheLabTypes` reads this vector and checks it against the real `LabRouteRecord`, ERRATA `ERR-LL-10`) |
| `wire/W02-error-envelopes.json` | W02 | one vector per frozen error code, plus two pins of v1's 400 and missing-model paths |
| `wire/W03-sse.json` | W03 | SSE pass-through bytes and reference-parser events |
| `router/R04-v1-pins.json` | R04 | recorded from the real `Router`, each hand-checked before writing |
| `keys/TEST-ONLY-keys.json` | (data) | published private scalars; **never trusted anywhere**; production verifiers refuse these `nodeId`s (L0.2) |
| `INDEX.json` | (data) | `[{path, sha256, family, status}]` sorted by path; detects an incomplete checkout, authenticates nothing |
| `json/M01-jcs.json` | M01 | 93 hand vectors: the strict parser, JCS and strict base64 (L0.2a) |
| `manifest/M01-der-raw.json`, `M02-verify-accept.json`, `M03-verify-reject.json`, `M05-render.json`, `M06-derivatives.json`, `manifest/schema/` | M01 (der/raw), M02, M03, M05, M06 | 12, 20, 71, 12 and 10 generated vectors; the two JSON schemas (L0.2, L0.3). The M01 family name is shared by `json/` and `manifest/`; they are different vectors |
| `manifest/M03-verify-reject-fx.json` | M03 | 37 generated vectors, M03-179..215 (review fix CV-1 to CV-9); the second file of the M03 family, kept apart because `HostileFramesTest` pins 71 vectors in `M03-verify-reject.json` |
| `bench/M04-derive.json`, `bench/M05-body.json` | M04, M05 | 131 and 30 generated vectors (L0.3) |
| `ledger/L01-destination-sets.json`, `L02-frame-rows.json` | L01, L02 | 56 and 23 hand vectors (L0.4) |
| `policy/W07-live-state.json`, `W07p-presence.json` | W07, W07p | 71 and 97 hand vectors (L0.4) |
| `router/R01-hard-filter.json`, `R02-scoring.json`, `R03-ordering.json`, `R05-failover.json`, `R06-reducers.json`, `M08-claim-tracker.json` | R01, R02, R03, R05, R06, M08 | 91, 50, 33, 44, 68 and 103 vectors (L0.6) |
| `wire/W04-pairing.json`, `W05-fingerprints.json`, `W06-frames.json`, `W07-state-frames.json` | W04, W05, W06, W07 | 444, 185, 284 and 31 vectors (L0.5, proto-trust and proto-wire; the W07 here is the STATE-frame half of the family whose live-state half is under `policy/`) |

Not present at this commit, despite earlier versions of this table: `history/r0/` and `scenarios/` (the simulator's scenarios live in `lab/mesh-sim`, ERRATA ERR-R6-13).
Counts were re-read from the files at head `0e8a3e4` (fix wave 4; `python3` `len(vectors)` per file, compared id by id with `5c4871b` and `2a67003`; `INDEX.json` lists 31 files; every vector is `oracle: self`; all `normative` except the proposed ones named under Pass rules: W00-100..104, W01b-reach and the 200 of `W01-generated.json`).

Every vector file is one JSON object: `{family, confVersion, specRefs, vectors[]}`; each vector has `id`, `origin`
(`hand` | `generated`), `status` (`normative` | `proposed` | `illustrative`), `oracle` (`self` | `independent` | `external`),
`description`, `input`, and `expect` = exactly one of `{"ok": ...}` or `{"reject": "CODE"}`. Additive fields recorded in ERRATA
`ERR-ENV-1`: `expectDetail` and `input.lane` / `input.decision`.

## Pass rules

- A family passes when every **normative** vector passes, at least one normative vector ran, and every required law
  exercised at least one case (the suite prints `law <family>/<name>: <n> cases` and fails on a zero).
- A `proposed` vector runs in a non-blocking lane: its result is reported, never counted as normative, and a proposed
  vector the runner cannot run is `proposed-skipped`. At this commit that is exactly six vectors, W00-100..104 and W01b-reach, and the reason
  is not a missing module (`:ledger-model` and `:mesh-policy` are built): each awaits an unruled owner decision (D12.1 to D12.4, D3) and the v1
  contract is frozen. A family the runner has no checker for is `not-implemented`, never `pass`. W08 (the hostile-node suite) has no vector file,
  so it prints `family W08: not-implemented`; that means "no vectors", not "module not built": its cases run as code in `:mesh-proto`'s tests
  (`W08HandshakeTest`, `HostileFramesTest`, `HostileFramesTls`, `HostileHandshakeSession`; the integration gate prints their counts).
- Every vector must carry an `oracle` tag; they are all `self` (at this commit, in every family).
- Comparison: header names are matched case-insensitively against the implementation (the runner writes the observed
  names in the canonical `X-Asom-*` spelling, so vector files must use that spelling); numbers are integers; the v1 doubles (`costEst`, latency EWMAs) are decimal STRINGS holding the
  shortest round-trip form JDK 21 prints, read with `Double.parseDouble` (LAB_SPEC R6). Message text of errors is never compared.
- Server-driven vectors (W01b, W02, W03) also enforce, on every exchange: no `X-Asom-*` header outside the four frozen echo
  headers; header/row agreement (full for non-stream, commit-time subset for streams, ERRATA `ERR-W01B-1`); and the key-leak
  law (`sk-LAB-SECRET-<provider>` never appears in any response byte, header or ledger row).
- R04 also re-runs every vector on shuffled catalogues (reversed plus four seeded shuffles; `R04-perm` uses 50): the plan must not change.

## The oracle rule (LAB_SPEC 4.10)

Everything here was produced or written in one session, so everything is `"oracle": "self"`. It moves to `independent` only when
an implementation whose author had no access to the generator source produces the same verdict and output, recorded in
`PROGRESS.md`. The Kotlin runner, `RegenerateVectors` and `lab/tools/xcheck.py` agreeing does **not** clear it. The suite fails if a
vector is tagged otherwise, so clearing a tag is a visible, reviewed edit.

## Regenerating

`./gradlew -p lab :conformance-runner:genVectors` rewrites `W01-generated`, `W01b` and `R04` by running the real v1 code (it refuses to write when a
recording contradicts the hand expectation in `RegenerateVectors`), then `python3 lab/tools/regen_index.py`. A change to a generated file
goes with a `VERSION` bump and a reviewed diff. Hand files are edited by hand, then `regen_index.py`.

## Vector changes under `VERSION` 0.2.0 (not yet bumped; HON-2, ERRATA `ERR-FX-VER`)

LAB_SPEC 3.2 says a generated file changes only together with a `VERSION` bump. Between commit `3733200` and head, generated vectors changed
or were added while `VERSION` stayed `0.2.0` (every `confVersion` too), so a consumer cannot tell the two sets apart by version. The bump is
**open**: it needs the generators outside this directory (`lab/mesh-router/tools/common.py`, `lab/mesh-policy/tools/gen_vectors.py`, the
`F.CONF` of `:manifest`'s test sources, `lab/mesh-proto/tools`) and the Swift lane's `supportedConfVersion` to change in the same commit
(the hosted router step regenerates R01 to R06 and M08 and fails on any difference). What changed, by id, compared id by id with the files at `3733200`:

| File | Expectation changed | Added |
|---|---|---|
| `manifest/M02-verify-accept.json` | M02-113, M02-118 (its meaning moved under the CV-3 tier ruling), M02-120 | M02-121, M02-122 |
| `manifest/M03-verify-reject.json` | M03-131 | none (the new M03 ids are in the file below) |
| `manifest/M03-verify-reject-fx.json` (new file) | | M03-179..215 |
| `manifest/M05-render.json` | M05-109 (regenerated from M02-113) | none |
| `ledger/L02-frame-rows.json` | none | L02-022, L02-023 |
| `router/` | none | R01-082..091, R02-047..050, R03-032, R03-033, R06-055..068, M08-065..082 |
| `wire/W04-pairing.json`, `W05-fingerprints.json`, `W06-frames.json`, `W07-state-frames.json` | | new files (wave 3) |

A consumer that pinned the 0.2.0 set as it stood at `3733200` must re-read M02-113, M02-118, M02-120, M03-131 and M05-109: their expectations differ. The second table below adds what changed after `2a67003` (the r4 and r4b waves, up to `5c4871b`), and the third what changed after `5c4871b` (fix wave 3): a consumer that pinned the set at `2a67003` or `5c4871b` must also re-read the ids in those tables.

### Vector changes after `2a67003` (r4 and r4b waves, `VERSION` still 0.2.0; R4H-02, ERRATA `ERR-FX3-HN-2`)

**LAB_SPEC 3.2 ("a generated file changes only together with a `VERSION` bump") is currently violated for the generated files in this table, pending the bump (WAIT-OWNER: REVISION_4 section 6 action 15 and D30 to D32; R4-L-08 restates the rule but its recorded list stops at the earlier five ids).** The `VERSION` was deliberately not bumped by this change. Compared id by id with the files at `2a67003` (`confVersion` is `0.2.0` before and after in every file):

| File (origin of the changed ids) | Expectation changed | Input changed (expectation the same) | Added |
|---|---|---|---|
| `bench/M04-derive.json` (generated) | M04-420, M04-425, M04-443, M04-444 | none | 27: M04-061..066, M04-313..315, M04-326..328, M04-419, M04-426..429, M04-449..458 |
| `wire/W05-fingerprints.json` (hand) | W05-307, W05-308 | none | 19: W05-422..440 |
| `policy/W07-live-state.json` (hand) | W07-075 | none | 2: W07-081, W07-082 |
| `router/M08-claim-tracker.json` (hand) | none | M08-017 | 19: M08-083..101 |
| `wire/W04-pairing.json` (hand for the 47 changed ids; the file also holds 121 generated vectors) | none | 47: W04-013, W04-014, W04-045..048, W04-500..540 | 131: W04-017..024, W04-450..458, W04-460..468, W04-480..499, W04-700..743, W04-760..780, W04-800..819 |

The generated file `bench/M04-derive.json` is the literal LAB_SPEC 3.2 violation; the hand files are listed because the file-level `confVersion` is the only thing a consumer can compare, and a consumer that pinned `2a67003` would otherwise read different expectations under the same version string. No other vector file differs from `2a67003` in any id up to `5c4871b` (L02-022 and L02-023 were already in `2a67003`); the third table covers the ids that changed after `5c4871b`. Two non-vector files do differ: `manifest/schema/asom.bench-public.1.schema.json` (the platform enum gained `windows` and `ubuntu-touch`) and `manifest/schema/asom.manifest.1.schema.json` (two enums gained `BATTERY_LOW` and `POWER_SAVER`); both only widen what the schema accepts (ERRATA `ERR-R4B-MF-4`, `ERR-FX3-HN-2`), they carry no `confVersion`, and builder action 15 should still list them.

### Vector changes after `5c4871b` (fix wave 3, `VERSION` still 0.2.0; A-R4X-3, B-VEC-1, A-F3, ERRATA `ERR-FX4-BD-3`)

Fix wave 3 (commits `ea81378`, `7e982bd`, `e1a4477`; the README edit `03956e6` came after them and did not list them) added 11 ids and changed the input of 2, all under `VERSION` 0.2.0. Compared id by id between `5c4871b` and head `0e8a3e4` (`confVersion` is `0.2.0` before and after in every file; no id was removed and no other file differs in any id):

| File (origin of the changed ids) | Expectation changed | Input changed (expectation the same) | Added |
|---|---|---|---|
| `bench/M04-derive.json` (generated) | none | none | 1: M04-431 |
| `policy/W07-live-state.json` (hand) | none | none | 2: W07-083, W07-084 |
| `router/M08-claim-tracker.json` (hand) | none | none | 7: M08-102..108 |
| `wire/W04-pairing.json` (hand) | none | 2: W04-454, W04-466 (the opening strings; ERR-FX3-PW-3) | 1: W04-459 |

`bench/M04-derive.json` is a generated file: M04-431 is a second literal LAB_SPEC 3.2 violation (a generated file gained an id with no `VERSION` bump), recorded here and pending the same bump. Per-file counts at head: M04 131, W07 71, M08 103, W04 444 (the table of files above). The two ids whose input changed carry the same `expect`, but a consumer that pinned 0.2.0 at `5c4871b` feeds them different bytes.

**Builder action 15 (the `VERSION` bump to 0.3.0, one commit) must cover every id in all three tables**, and must regenerate the generated files above from their generators (`lab/mesh-policy/tools/gen_vectors.py` now reproduces L01, L02, W07 and W07p byte for byte and refuses to write a file that would lose a committed id, ERRATA `ERR-FX3-HN-3`).


## Findings recorded by pinning v1 (for the owner; not vector edits)

1. On streams the response never carries `X-Asom-Cost-*` although the final ledger row does (W01b-002, -003, -007).
2. A failover appends one attempt row per failed candidate before the terminal row; the attempt row names the failed candidate (W01b-004).
3. A stream cut mid-flight is ledgered as status 502 with egress `cloud` and the committed provider (W01b-007).
4. A request body that is not a JSON object is `400 invalid_request_error` with no typed code; a body with no `model` is `404 MODEL_UNKNOWN` (W02-008, -009).
5. A virtual model in `model` wins over `X-Asom-Policy` (R04-027).
