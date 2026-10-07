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
| `bench/M04-derive.json`, `bench/M05-body.json` | M04, M05 | 94 and 30 generated vectors (L0.3) |
| `ledger/L01-destination-sets.json`, `L02-frame-rows.json` | L01, L02 | 56 and 23 hand vectors (L0.4) |
| `policy/W07-live-state.json`, `W07p-presence.json` | W07, W07p | 67 and 97 hand vectors (L0.4) |
| `router/R01-hard-filter.json`, `R02-scoring.json`, `R03-ordering.json`, `R05-failover.json`, `R06-reducers.json`, `M08-claim-tracker.json` | R01, R02, R03, R05, R06, M08 | 91, 50, 33, 44, 68 and 77 vectors (L0.6) |
| `wire/W04-pairing.json`, `W05-fingerprints.json`, `W06-frames.json`, `W07-state-frames.json` | W04, W05, W06, W07 | 312, 166, 284 and 31 vectors (L0.5, proto-trust and proto-wire; the W07 here is the STATE-frame half of the family whose live-state half is under `policy/`) |

Not present at this commit, despite earlier versions of this table: `history/r0/` and `scenarios/` (the simulator's scenarios live in `lab/mesh-sim`, ERRATA ERR-R6-13).
Counts are read from the files at head `3a91da05` (`INDEX.json` lists 31 files; every vector is `oracle: self`; all `normative` except the proposed ones named under Pass rules: W00-100..104, W01b-reach and the 200 of `W01-generated.json`).

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

A consumer that pinned the earlier 0.2.0 set must re-read M02-113, M02-118, M02-120, M03-131 and M05-109: their expectations differ.

## Findings recorded by pinning v1 (for the owner; not vector edits)

1. On streams the response never carries `X-Asom-Cost-*` although the final ledger row does (W01b-002, -003, -007).
2. A failover appends one attempt row per failed candidate before the terminal row; the attempt row names the failed candidate (W01b-004).
3. A stream cut mid-flight is ledgered as status 502 with egress `cloud` and the committed provider (W01b-007).
4. A request body that is not a JSON object is `400 invalid_request_error` with no typed code; a body with no `model` is `404 MODEL_UNKNOWN` (W02-008, -009).
5. A virtual model in `model` wins over `X-Asom-Policy` (R04-027).
