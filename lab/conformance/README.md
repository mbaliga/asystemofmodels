# lab/conformance: the vector suite (data, not a Gradle project)

Version: see `VERSION` (0.2.0). Spec: `docs/design/mesh/LAB_SPEC.md` section 3. Runner: `lab/conformance-runner`.
Evidence label: **LAB**, `oracle: self`, never device evidence.

## Files

| Path | Family | Notes |
|---|---|---|
| `wire/W00-constants.json` | W00 | frozen constants by reflection; W00-100..104 are `proposed` mesh additions (skipped until the lab types exist) |
| `wire/W01-echo-headers.json` | W01 | hand vectors: the spec's W01-001..009 plus W01-010..014 |
| `wire/W01-generated.json` | W01 | 200 `proposed`, `generated` cost vectors (ERRATA `ERR-FMT-3`); a non-blocking lane |
| `wire/W01b-one-record.json` | W01b | recorded from the real `AsomServer`; W01b-reach is `proposed` (needs `:ledger-model`) |
| `wire/W02-error-envelopes.json` | W02 | one vector per frozen error code, plus two pins of v1's 400 and missing-model paths |
| `wire/W03-sse.json` | W03 | SSE pass-through bytes and reference-parser events |
| `router/R04-v1-pins.json` | R04 | recorded from the real `Router`, each hand-checked before writing |
| `keys/TEST-ONLY-keys.json` | (data) | published private scalars; **never trusted anywhere**; production verifiers refuse these `nodeId`s (L0.2) |
| `INDEX.json` | (data) | `[{path, sha256, family, status}]` sorted by path; detects an incomplete checkout, authenticates nothing |
| `history/r0/` | | reserved for the r0 seed files (L0.2, never run) |
| `manifest/`, `ledger/`, `scenarios/` | | reserved for later work items |

Every vector file is one JSON object: `{family, confVersion, specRefs, vectors[]}`; each vector has `id`, `origin`
(`hand` | `generated`), `status` (`normative` | `proposed` | `illustrative`), `oracle` (`self` | `independent` | `external`),
`description`, `input`, and `expect` = exactly one of `{"ok": ...}` or `{"reject": "CODE"}`. Additive fields recorded in ERRATA
`ERR-ENV-1`: `expectDetail` and `input.lane` / `input.decision`.

## Pass rules

- A family passes when every **normative** vector passes, at least one normative vector ran, and every required law
  exercised at least one case (the suite prints `law <family>/<name>: <n> cases` and fails on a zero).
- A `proposed` vector runs in a non-blocking lane: its result is reported, never counted as normative, and a proposed
  vector whose module is not built is `proposed-skipped`. A family whose module is not built is `not-implemented`, never `pass`.
- Every vector must carry an `oracle` tag; at L0.1 they are all `self`.
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

## Findings recorded by pinning v1 (for the owner; not vector edits)

1. On streams the response never carries `X-Asom-Cost-*` although the final ledger row does (W01b-002, -003, -007).
2. A failover appends one attempt row per failed candidate before the terminal row; the attempt row names the failed candidate (W01b-004).
3. A stream cut mid-flight is ledgered as status 502 with egress `cloud` and the committed provider (W01b-007).
4. A request body that is not a JSON object is `400 invalid_request_error` with no typed code; a body with no `model` is `404 MODEL_UNKNOWN` (W02-008, -009).
5. A virtual model in `model` wins over `X-Asom-Policy` (R04-027).
