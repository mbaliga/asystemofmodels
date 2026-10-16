# The signed capability manifest: schema, canonicalisation, signing, verification, attestation, tamper-evidence and subscribers

**Section of:** the ASOM multi-device ("mesh") design session, 2026-09-29.
**Status:** DESIGN PROPOSAL. Nothing here is approved, frozen or executable. It turns requirement 3 (a benchmark report as a manifest JSON that any subscriber can verify) into implementable text for the roadmap §7 (v4) design session and for the v2 P6/P7 benchmark work. v2 and v4 entry criteria stand; v1 device validation is still open (`PROGRESS.md`), so none of this may be built into shipped modules without the owner's authorisation (see `platforms.md` OD4). Every contract addition (§17) and every invariant question (§18, §19) needs owner sign-off.
**Siblings:** `trust.md` (node identity key NIK, pairing, the `asom-mesh/1` frames incl. `MANIFEST_REQ`/`MANIFEST`, the `lan` ledger class), `platforms.md` (per-OS roles, the integer-only rule C1, ES256 rule C2, the conformance suite and its M-families), `benchmark.md` (what is measured and how: M04 derivation, the measurement document), `router.md` (placement; live-state `STATE`), `contract.md` (the combined delta registry).
**This section owns:** the manifest's envelope and payload schema, canonical bytes, the signature, which key signs, the verification algorithm and its typed rejects, attestation tiers and what each proves, freshness/rollback/poisoning defences including the claim-versus-observed cross-check, the private/public split, the subscriber channels, and the one-source rule for plain text.

**Artefacts produced in this session** (all under `mesh/manifest-vectors/`, all keys TEST-ONLY, all benchmark numbers INVENTED):

| File | What it is |
|---|---|
| `asom.manifest.1.schema.json` | Payload JSON Schema (draft 2020-12), strict form (Appendix A) |
| `asom.bench-public.1.schema.json` | Public anonymised derivative schema (Appendix B) |
| `gen_manifest_vectors.py` | Reference generator **and** reference verifier/renderer/public builder (deterministic: RFC 6979 nonces) |
| `VerifyDsse.java` | Independent JCA check of the DSSE/ES256 layer |
| `example-container.json`, `example-payload.pretty.json`, `example-payload.jcs.json`, `example-report.txt`, `example-public.*.json` | The complete example (§15) |
| `M02-verify-accept.json`, `M03-verify-reject.json`, `M05-render.json`, `M06-public-derive.json`, `TEST-ONLY-keys.json` | Vectors in the `platforms.md` §8.3 envelope (§16) |
| `run_crosscheck.sh`, `crosscheck.out` | Re-runs everything; real output: generator self-checks, OpenSSL verify, `cryptography` verify, JCA verify |

Tags: **[MVn]** verified this session (sources in §23.1); **[MAn]** assumption (§23.2). Facts verified by sibling sections are cited with their own tags (`platforms.md` [V24], `trust.md` [V5]).

---

## 0. The design in twelve rules

| # | Rule | v1 analog |
|---|---|---|
| M1 | **A manifest is a claim, never a fact.** Routers use it as a prior and replace it with what they observe (§11.5). No manifest field can raise trust, grant a scope or change a data-class ceiling (`trust.md` R3). | The ledger records what happened, not what a provider promised |
| M2 | **One record, many renderings.** The signed payload bytes are the single source. Plain text, the live-state digest and the public derivative are pure functions of those verified bytes (plus, for text, the viewer's verification result). Nothing is rendered from the pre-signing object. | Invariant 9: echo headers and ledger row from one `RouteRecord` |
| M3 | **Sign exact bytes; require those bytes to be canonical.** DSSE over the payload bytes; the verifier also rejects payload bytes that are not the JCS form of what they parse to. | — |
| M4 | **ES256 only** (ECDSA P-256, SHA-256, 64-octet r‖s), because it is the only algorithm hardware-backed on StrongBox, Secure Enclave and TPM alike. | `platforms.md` C2, `trust.md` §2.2 |
| M5 | **The mesh manifest is signed by the node identity key (NIK)** that the peer already pinned at pairing. Sending it to a paired peer reveals no identifier the peer did not already hold. | Identity from a verified source, never from a claim |
| M6 | **A key taken from the document itself proves nothing against a deliberate forger.** Tamper-evidence needs a pinned key (pairing or an in-person fingerprint comparison) or platform attestation. TOFU is labelled as such. | — |
| M7 | **Attestation tiers inform; they never gate own-device pairing** and never turn into trust by themselves. A1 ("hardware key, self-reported") is treated exactly like A0 for every security decision. | `trust.md` §2.5: tier is not a gate |
| M8 | **Freshness is two things:** a requester challenge proves the *signing key* was used after the request; `measuredAtMs` and the observed-performance check are the only defence against *stale measurements*. Neither is mistaken for the other. | — |
| M9 | **Private and public are different objects.** The private manifest (NIK-signed, device-identified) goes only to paired peers or to a file the user chose to export. The public derivative carries no key, no signature, no identifier, and states that it cannot be verified. | Roadmap v2 P7: anonymous by construction |
| M10 | **No new egress, no new listener, no new HTTP endpoint is required** for requirement 3. Mesh peers pull over `asom-mesh/1`; everyone else gets a user-exported file. A localhost endpoint is optional and recommended deferred (OD-M5). | Frozen §5; roadmap §13 |
| M11 | **Every verification failure is typed** and shown; nothing fails silently and nothing is shown as "verified" without saying what was verified. | Typed errors §5.6; the audit's "stated class must be true" |
| M12 | **Never in a manifest:** BYOK keys or key presence, cloud providers, app identities or package names, ledger rows or usage counts, prompt or response content, the user's device name. Structural test (§4.6). | Invariant 4; `trust.md` §7.4 |

---

## 1. Scope, layering and the overlap with `benchmark.md`

```
            (benchmark.md)                         (this section)                                   (consumers)
 raw samples ──M04 derive──▶ measurement block ─┐
                                                ├─▶ ManifestBody (THE record) ──project(audience)──▶ payload object
 device facts, producer, NIK subject ───────────┘                                                     │ JCS
                                                                                                       ▼
                                                            payload bytes P ──ES256(PAE(type,P))──▶ DSSE envelope ─▶ container JSON
                                                                 │ (only after verify(P) succeeds)
                  ┌──────────────────────────────┬───────────────┴─────────────────┬───────────────────────────────┐
                  ▼                              ▼                                 ▼                               ▼
     plain text = M05(parse(P), vr)    bodyDigest = SHA-256(JCS(body))   ClaimView for the router (§11.5)   public = PublicDerivative(parse(P))
```

**Overlap to resolve before any freeze (coordination item, not an owner decision).** `benchmark.md` (written in parallel) defines a measurement document `asom.bench/1` with its own renderer `asom.text/1` (seen in `mesh/bench-examples/` at 02:47 today). This section's payload schema (§4, Appendix A) defines a self-contained `body.results` block so that the envelope, verifier and vectors here are complete and testable today. **Both must not ship.** Recommendation: when `benchmark.md` freezes, replace `body.results` and `body.device`'s measurement-adjacent fields by `body.bench` = the `asom.bench/1` document verbatim, and keep from this section only what the envelope, the verifier and the router need that the bench document does not carry: `audience`, `seq`, `subject` (NIK, storage claim), `device.platformIds` (for attested-model comparison, §9.3), and the §11.5 fields the router reads (decode rate by context, steady-state rate, throttle onset, prefill rate and TTFT, memory before load and peak). Everything else in this section — canonicalisation, envelope, keys, verification, attestation, freshness, poisoning defence, the private/public split, subscribers and the text header/verification block — is independent of which measurement block wins. The plain-text report is then `M05 header + verification block (this section) + asom.text/1 body (benchmark.md)`.

---

## 2. Threat model (manifest-specific; `trust.md` §1 covers transport and pairing)

| Adversary | Wants | Can | Primary defence |
|---|---|---|---|
| A **malicious or compromised paired peer** | Attract traffic (to see prompts or just to be chosen), or repel it (free-ride), or crash the requester | Sign anything with its own NIK; choose `seq`; re-sign old measurements; claim any number | M1: claims are priors, clamped and replaced by observation (§11.5); internal-consistency rejects (§8.4); size and depth limits; data classes decided by policy, never by manifest (`trust.md` §7.3) |
| A **stolen-leaf impersonator** (`trust.md` §2.1: a software TLS leaf key read from memory) | Pose as the node and serve a flattering manifest | Complete mTLS as the node until the leaf expires; replay any manifest it captured | Challenge-bound presentation: a fresh NIK signature is required, and the NIK is hardware-bound where possible (§11.2) |
| A **file tamperer** (sync folder, messenger, email) | Change numbers in an exported report | Edit bytes; swap in an older report; strip or add evidence | Signature under a pinned key (M6); rollback store (§11.3); evidence bound to the signing key (§9) |
| A **forger with no access to any device** | Produce a convincing report for a device class (e.g. to mislead a third-party app) | Generate keys; run a modified harness; copy public attestation chains | Without a pin it succeeds at A0/A1: that is stated in every rendering. A2 requires a key inside genuine vendor hardware (§9) |
| The **producing device's own user** | A flattering report (cooled device, best of N runs, patched harness) | Everything their device can do | Recorded conditions and run counts (claims); A2 on Android bounds harness modification to rooted devices; observation (§11.5). Honestly: not preventable (§10) |
| A **malicious app on the subscriber's device** | Read a stable device identifier | Read world-readable files and unauthenticated providers | Manifests are never exposed on the discovery provider or any unauthenticated surface (§13.4) |
| The **public receiver / anyone scraping it** | Link uploads to an install or person | See every uploaded payload and transport metadata | No key, no id, no exact timestamps, quantised numbers (§12). Residual linkability stated |
| A **poisoner of the public dataset** | Skew the community table | Script uploads | Nothing cryptographic can stop it without an operator identity service (§12.4); robust aggregation and editorial cross-check are receiver policy |

Out of scope: root/kernel compromise of the producing device after key generation (it can use the key and fake everything the harness reports), vendor PKI compromise (Google or Apple roots), and physical side channels on secure elements.

---

## 3. Object model

### 3.1 The one record

`ManifestBody` is a Kotlin data class in a new pure-JVM `:core:mesh` (`platforms.md` §7.1) with exactly the fields of `body` in Appendix A. It is built once per benchmark completion (or model add/remove, or OS/engine change) from `benchmark.md`'s derived results. The node persists `(seq, JCS(body_own), bodyDigest_own)` durably **before** the first signature over a new body.

### 3.2 Projections by audience (the body a subscriber sees)

| Audience | Who | Differences from the full body | Evidence (§9) |
|---|---|---|---|
| `own` | Paired peer of class own (`trust.md` §10.1) | none | included |
| `other` | Paired peer of class other (**only if `trust.md` OD-2 permits other-owner peers**) | drop `device.platformIds`, `device.os.securityPatch` | omitted unless the user switched it on for that peer |
| `file` | User export (§13.3) | drop `device.platformIds`, `device.os.securityPatch` | optional (user toggle on the export screen, default off) |

`seq` is shared by all projections of one body; `bodyDigest` is per projection. Subscribers key their rollback store by `(nodeId, audience)` (§11.3).

### 3.3 Presentation

A **presentation** is one signature over `{schema, schemaMinor, body, presentation}` where `presentation = {issuedAtMs, expiresAtMs, challenge}`. Re-signing is cheap; re-measuring is not. A node therefore signs a new presentation for every `MANIFEST_REQ` (with the requester's challenge) and one unsolicited presentation per export (challenge `null`).

**`expiresAtMs` bounds when a presentation may be accepted, not how long an accepted body may be used.** A subscriber that verified a presentation keeps the verified body until its content goes stale (§11.4) or the peer's live-state digest changes (§13.2).

---

## 4. Payload schema (`asom.manifest/1`)

The full JSON Schema is Appendix A (and `manifest-vectors/asom.manifest.1.schema.json`); it validates the example in §15 (checked with `jsonschema` 4.26.0 in `gen_manifest_vectors.py`).

### 4.1 Field map

Units are in the field name (C1: integers only). "Privacy" states who may see the field.

| Path | Type / unit | Meaning and source | Privacy |
|---|---|---|---|
| `schema` | const `asom.manifest/1` | Major version | all |
| `schemaMinor` | int ≥ 0 | Additive revision (§4.3) | all |
| `body.audience` | `own`\|`other`\|`file` | Projection (§3.2) | all |
| `body.seq` | int ≥ 1 | Content version per signing key; increments when the full body changes | all |
| `body.subject.nodeId` | b64url(SHA-256(SPKI)) 43 chars | The signing key's pin, identical to `trust.md` §2.3 `nodeId` | own, other, file |
| `body.subject.keyAlg` | const `ES256` | Closed enum; a new algorithm is a new major | all |
| `body.subject.keyStorage` | `strongbox`\|`tee`\|`secure-enclave`\|`tpm`\|`os-keystore`\|`file`\|`unknown` | **Self-reported.** Maps to `trust.md` T3/T2/T2/T2/T1/T0 | all |
| `body.producer.app`, `.appVersion` | id, semver | e.g. `asom-android`, `asom-desktop`, `asom-bench-ios` | all |
| `body.producer.harness.{id,version,methodologyId,confVersion}` | ids, semver | Harness identity, the `benchmark.md` method, the conformance version it passed (`platforms.md` §8.7) | all |
| `body.producer.engine.{name,commit,buildFlags}` | id, hex 7–40, text[] | Engine and pinned commit (`platforms.md` C11) | all |
| `body.device.class` | open id | `phone`, `tablet`, `handheld`, `laptop`, `desktop`, `server`, `sbc` | all |
| `body.device.vendor`, `.model` | text | Marketing names, self-reported | all |
| `body.device.platformIds.{brand,device,manufacturer,model,product}` | text | Raw platform ids (Android `Build.*`), self-reported, used **only** to compare with attested values (§9.3) | own |
| `body.device.os.{family,version,securityPatch}` | enum, text, date | OS; patch level only for `own` | patch: own |
| `body.device.soc.{vendor,name,cpu.logicalCores,cpu.clusters[{cores,maxKHz}]}` | text, ints | SoC and CPU topology | all |
| `body.device.memory.totalBytes` | bytes | Physical RAM as reported by the OS | all |
| `body.device.accelerators[{kind,vendor,name,apis[],dedicatedBytes}]` | open id, text, ids, bytes\|null | GPU/NPU/DSP present, which APIs the engine can use, dedicated VRAM | all |
| `body.device.power.{battery,batteryDesignMilliWh}` | bool, int\|null | Has a battery; design capacity where the OS exposes it | all |
| `body.device.thermal.{cooling,stateSource}` | open ids | Cooling class (`passive`, `fan`, `liquid`, `unknown`) and which OS signal the governor reads | all |
| `body.results[]` | ≤ 64 | One per `(fileSha256, backend)`; rows with different engine commit or backend are never merged (C11) | all |
| `…modelId, fileSha256, fileBytes, quant, backend` | ids, hex, bytes | Model file identity (catalogue sha256) and backend | all |
| `…settings.{threads,gpuLayers,ctxTokens,batchTokens}` | ints | Engine settings used | all |
| `…measuredAtMs` | epoch ms | End of the measurement run | all |
| `…runs.{planned,completed,discarded}` | ints | Cherry-picking visibility (a claim) | all |
| `…conditions.{charging,batteryStartPermille,thermalStart,socStartMilliC,screenOn}` | bool, ‰, open id, m°C, bool (nullable) | Conditions at run start | all |
| `…prefill[{promptTokens, milliTokPerSec{p10,p50,p90}, ttftMicros{…}}]` | 1–4 points | Prompt processing rate and time to first token at given prompt lengths | all |
| `…decode[{contextTokens, genTokens, milliTokPerSec{…}}]` | 1–4 points | Generation rate at given context depths | all |
| `…sustained.{durationMs,intervalMs,steadyMilliTokPerSec,throttleOnsetMs,curve[[tMs,milliTokPerSec,socMilliC,powerMilliW]]}` | ints, tuples ≤ 240 | Throughput-versus-time (throttle curve); onset and steady state per `benchmark.md` M04 | all |
| `…memory.{availableBeforeLoadBytes,peakProcessBytes,kvCacheBytes}` | bytes | Headroom = available − peak (derived by consumers, not stored) | all |
| `…power.{method,avgMilliW}` | enum, mW\|null | `battery-current`, `battery-level`, `rapl`, `pmic`, `unavailable` — only where obtainable (`benchmark.md` owns how; [MA8]) | all |
| `…flags[]` | open ids | `low-runs`, `thermal-throttled`, `charging`, `background-load`, `gpu-shared`, `low-memory` | all |
| `presentation.{issuedAtMs,expiresAtMs}` | epoch ms | Signing time and presentation expiry (§11.1) | all |
| `presentation.challenge` | b64url 32 bytes \| null | The requester's nonce, or null for unsolicited/file presentations | all |

Derived quantities (headroom, steady/peak ratio, energy per token, answers such as "can it run 8B comfortably") are **not stored**: consumers and the renderer compute them. Storing derivations beside their inputs creates two sources that can disagree.

### 4.2 Integer and string rules

- Every number is an integer in the unit named by its suffix (`MilliTokPerSec` = tokens/s × 1000, `Micros`, `Ms`, `Bytes`, `MilliC`, `Permille`, `MilliW`, `KHz`), within ±(2⁵³−1) (`platforms.md` C1). Floats, exponents and `-0` are rejected at parse time.
- Timestamps are epoch milliseconds UTC, 2020-01-01 ≤ t < 2100-01-01.
- Display strings (`text`): 1–96 UTF-16 code units, and none of U+0000–U+001F, U+007F–U+009F, U+061C, U+200E, U+200F, U+2028, U+2029, U+202A–U+202E, U+2066–U+2069, U+FEFF. This blocks terminal-escape and bidi-override spoofing of the plain text (vector M03-126). The renderer also replaces any such character with U+FFFD (defence in depth).
- Identifiers (`id`): `^[a-z0-9][a-z0-9._+-]{0,63}$`.

### 4.3 Additive-evolution rules

| Change | Allowed in | Rule |
|---|---|---|
| Add an **optional** property anywhere | minor (`schemaMinor + 1`) | Old consumers ignore it after verification; the renderer states "N items from a newer format are not shown" (vector M02-107) |
| Add a value to an **open** enum (`class`, `kind`, `backend`, `cooling`, `stateSource`, `thermalStart`, `flags`) | minor | Consumers render unknown values as `other (<value>)` and treat them as "unknown" in logic |
| Add a value to a **closed** enum (`audience`, `keyAlg`, `keyStorage`, `os.family`, `power.method`) | **major** | A consumer cannot safely guess the meaning |
| Make optional → required, remove, rename, change a unit or meaning | **major** | Units never change; a new unit is a new field name |
| Change canonicalisation, envelope or algorithm | **major** of the container or payload type | — |

- **Producers validate strictly** (Appendix A as written: `additionalProperties: false`) against the schema of the minor they emit.
- **Consumers validate tolerantly**: the same schema with every `additionalProperties: false` relaxed to `true` (the reference verifier generates this mechanically). Unknown properties are **signed** (they are inside the verified bytes), so ignoring them is safe; they are never rendered as facts.
- Major versions are carried twice, in the DSSE `payloadType` (`…manifest.v1+json`) and in `schema`; both must agree (vectors M03-118, M03-119).
- Deprecation: a field may be marked deprecated in a minor but is still emitted until the next major.

### 4.4 Size limits

Container ≤ 512 KiB; decoded payload ≤ 256 KiB; JSON nesting depth ≤ 16; `results` ≤ 64; `curve` ≤ 240 points; arrays elsewhere as in Appendix A. The example payload is 4,228 bytes; its container 6,057 bytes (without evidence; an Android attestation chain adds roughly 3–8 KB — [MA14]).

### 4.5 Where each field comes from, per platform

Owned by `benchmark.md` and `platforms.md`; this section only fixes that each producer fills `null` (or omits an optional field) rather than inventing a value, and records `power.method = unavailable` when it cannot measure. An unknowable field is never estimated inside the manifest.

### 4.6 Fields that must never appear (structural law)

The manifest builder takes only `ManifestBody`, whose type has no field for: BYOK keys or their presence, cloud provider ids, package names or app labels, ledger rows or counts, prompts or responses, the user-set device name, IP addresses, IMEI/serial/MEID. Law **LM-1**: a reflection test over `ManifestBody` and a string search over every generated vector fail the build if any such field name or value appears (mirrors the v2 P7 gate "structurally incapable of emitting a key/content/fingerprint field").

---

## 5. Canonicalisation

### 5.1 Decision

| Option | For | Against | Verdict |
|---|---|---|---|
| **Sign exact bytes (DSSE) AND require the bytes to be RFC 8785 JCS, asom integer profile** | Verifier never has to reproduce bytes to check a signature; canonical check removes parser-differential attacks (duplicate keys, number forms) and gives a stable digest for live-state change detection and golden vectors | Verifier still needs a JCS serialiser (small with integers only) | **Chosen** |
| Sign exact bytes, no canonical requirement | Simplest | `{"p50":5,"p50":500}` parses differently in different JSON libraries; the digest of "the same content" varies; vectors cannot be byte-exact | Rejected |
| Deterministic CBOR + COSE | Compact, a well-defined deterministic encoding | The requirement is a *manifest JSON*; every subscriber needs a CBOR library; Swift and JVM both lack one in the platform | Rejected |
| JSON with an embedded signature over the JCS form of the object minus the signature (JSF style) | Human-readable file | **Every verifier must canonicalise correctly to verify at all**; one serialiser bug means valid reports fail or, worse, different bytes verify | Rejected |

### 5.2 The asom JCS integer profile (normative; vectors `platforms.md` M01, this section M03-104/105/106)

RFC 8785 [MV12] with integer-only numbers:
1. No whitespace between tokens.
2. Object members sorted recursively by their names as arrays of **UTF-16 code units**, compared as unsigned integers; array order unchanged.
3. Strings: `"` → `\"`, `\` → `\\`; U+0008/0009/000A/000C/000D → `\b \t \n \f \r`; other U+0000–U+001F → `\u00xx` lowercase hex; everything else literal (including `/` and non-ASCII); no Unicode normalisation.
4. Numbers: integers within ±(2⁵³−1) in shortest decimal form (which is what ECMAScript's number serialisation yields for such integers). Anything else is not in the profile.
5. Literals `true`, `false`, `null`.

### 5.3 Strict parser (before and after verification)

Reject: invalid UTF-8 or a BOM (`INVALID_UNICODE`/`MALFORMED_JSON`), lone surrogates (`INVALID_UNICODE`), duplicate member names at any depth (`DUPLICATE_KEY`), fractions, exponents, `-0`, `NaN`/`Infinity` (`NON_INTEGER_NUMBER`), integers beyond ±(2⁵³−1) (`NUMBER_RANGE`), depth > 16 (`MALFORMED_JSON`), non-whitespace after the value (`TRAILING_DATA`). Then `JCS(parsed) == payloadBytes` or `NON_CANONICAL`. RFC 8785 §5 requires exactly this order for signatures: parse and check I-JSON, check data, verify [MV12] — here the signature is checked first on the raw bytes (DSSE), which is safe because no parsed value is used before it passes.

---

## 6. Signature envelope

### 6.1 Decision

| Option | For | Against | Verdict |
|---|---|---|---|
| **DSSE** (signs `PAE(payloadType, payload)`) with a fixed asom profile | No `alg` field to confuse; the payload type is inside the signed bytes (domain separation between asom signed types); spec says verifiers must not re-parse the envelope after verification [MV13]; ~20 lines in any language, no library; its ECDSA test vector already uses raw r‖s [MV13], matching C2 | Smaller ecosystem than JOSE; base64 alphabet laxity (verifiers must accept either) | **Chosen** |
| JWS flattened JSON (RFC 7515) with a pinned profile (`alg` must be ES256, `typ` fixed, no `jku`/`x5u`/`jwk`/`crit`) | Large library ecosystem; ES256 is standard r‖s (`platforms.md` [V24]); `x5c` header fits Android chains | The protected header is attacker-chosen input that every third-party verifier must police (`alg` confusion is a known failure class); generic JOSE libraries do not enforce the asom profile, so "any app" would get weaker checks by default | Close second; rejected |
| COSE_Sign1 | Standard for attestation formats | CBOR; see §5.1 | Rejected |
| Detached `.sig` file next to a plain JSON file | Readable JSON | Two files that separate in transit; the classic "verify one, use the other" bug | Rejected |

### 6.2 asom DSSE profile (normative)

- `PAE(type, body) = "DSSEv1" SP LEN(type) SP type SP LEN(body) SP body`, LEN = decimal byte length without leading zeros [MV13].
- `payloadType` registry (closed; others → `PAYLOAD_TYPE_UNSUPPORTED`):

| payloadType | Use |
|---|---|
| `application/vnd.asom.manifest.v1+json` | This manifest |
| `application/vnd.asom.key-rollover.v1+json` | Reserved (§7.5) |
| `application/vnd.asom.appattest-bind.v1` | Used only as App Attest client data (§9.4), never as an envelope |

- Exactly **one** signature entry (`SIGNATURE_COUNT` otherwise). `sig` decodes to exactly 64 octets r‖s, each 32 octets big-endian, leading zeros kept (`SIGNATURE_ENCODING` otherwise). Producers emit **low-S** (s ≤ n/2); verifiers **accept high-S** (vector M02-105) and never use signature bytes as an identifier.
- Base64: producers emit standard alphabet with padding. Verifiers accept standard or URL-safe [MV13], padded or unpadded, but reject mixed alphabets, bad padding and non-zero unused trailing bits (`ENCODING`, vector M03-125) so that one byte string has at most two accepted spellings.
- `keyid` = the signer's `nodeId`. It is an **unauthenticated hint** [MV13]; it only selects a TOFU entry or the error code (`KEY_NOT_PINNED`).

### 6.3 Container (the "manifest JSON" file and the `MANIFEST` frame payload)

```jsonc
{
  "asomCapabilityManifest": 1,                       // container version; anything else -> CONTAINER_VERSION_UNKNOWN
  "dsse": {
    "payloadType": "application/vnd.asom.manifest.v1+json",
    "payload": "<base64 of the JCS payload bytes>",
    "signatures": [ { "keyid": "<nodeId>", "sig": "<base64 of 64-octet r||s>" } ]
  },
  "signer": { "spki": "<base64 DER SubjectPublicKeyInfo>" },   // unauthenticated; used only in TOFU/file mode
  "evidence": [ /* optional platform attestation items, section 9; each independently verifiable and bound to the signer key */ ]
}
```

The container is not signed. Only `dsse.*` feeds signature verification; `signer.spki` is a candidate key in TOFU mode only; each `evidence` item is bound to the signing key by key equality or by a nonce derived from it (§9). Unknown container members are ignored. Producers emit the container in JCS form for reproducibility; verifiers do not require it. File extension `.asom-manifest.json`, media type `application/vnd.asom.manifest-container+json`.

### 6.4 Domain separation from other NIK signatures

The NIK also signs X.509 leaf certificates (`trust.md` §2.1). A DER `TBSCertificate` starts with `0x30`; every DSSE signing input starts with the ASCII `DSSEv1 `. The two can never collide. **Any other object the NIK signs in future (for example a signed live state in `router.md`) must use DSSE with its own registered payloadType**, so a signature over one type can never verify as another (vectors M03-113, M03-114).

---

## 7. Algorithm, keys and signing

### 7.1 Algorithm

| Candidate | Android StrongBox | Android TEE | Apple Secure Enclave | TPM 2.0 | Verdict |
|---|---|---|---|---|---|
| **ECDSA P-256 / SHA-256 (ES256)** | yes [MV6] | yes | yes; the only classical curve [MV8] | mandated by the PC Client profile (`trust.md` [V12], medium) | **Chosen** |
| Ed25519 | not listed [MV6] | KeyMint v2 / Android 13+ [MV7, medium] | **no** [MV8] | not generally available (`trust.md` [V12]) | Rejected: cannot be hardware-backed on iPhone, StrongBox or most TPMs |
| RSA-2048 | yes [MV6] | yes | no [MV8] | yes | Rejected: no Secure Enclave; large signatures |
| ML-DSA-65 | no | KeyMint lists ML-DSA keys on current Android [MV5] | iOS 26+ (`MLDSA65/87`) [MV8] | no | **Parked** for a post-quantum major (§7.6) |

### 7.2 Which key signs

| Producer | Signing key | Why |
|---|---|---|
| asom node (Android daemon, asom-desktop, a future iOS foreground provider) | **the NIK** (`trust.md` §2.1) | Peers already pinned it at pairing (M5); one key to attest; the manifest reveals no new identifier to a peer |
| Standalone benchmark app that is a separate install (iOS `AsomBench`, an Android standalone shell if it has its own package) | its own **report key** with the NIK's generation profile | It is not a node; it never presents to the mesh. Subscribers see it as a different source (different `nodeId`) |

Coordination with `trust.md`: (a) the NIK must be generated **with the attestation parameters of §7.3** or Android A2 is impossible for it later (attestation is fixed at key generation, [MV5]); (b) the NIK is generated at first mesh enable **or** at the first user action that produces a signed report, whichever comes first (`trust.md` §2.5 says mesh enable only).

### 7.3 Key generation parameters

**Android** (`KeyGenParameterSpec`, alias `asom.nik.v1`):

```kotlin
fun nikSpec(strongBox: Boolean, deviceProps: Boolean) =
    KeyGenParameterSpec.Builder("asom.nik.v1", KeyProperties.PURPOSE_SIGN)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setAttestationChallenge("asom-nik/1".toByteArray(Charsets.US_ASCII))  // <=128 bytes [MV5]; a domain tag, not freshness
        .apply { if (deviceProps) setDevicePropertiesAttestationIncluded(true) } // API 31+; brand/device/manufacturer/model/product [MV5]
        .setIsStrongBoxBacked(strongBox)
        .setUserAuthenticationRequired(false)                                   // daemon signs unattended (trust.md §2.5)
        .build()
// Attempt order: (SB, props) -> (SB) -> (TEE, props) -> (TEE). StrongBoxUnavailableException / ProviderException move to the next.
// Record which attempt succeeded; keyStorage = "strongbox" | "tee". Store getCertificateChain("asom.nik.v1") as evidence.
```

**iOS / iPadOS:** `SecureEnclave.P256.Signing.PrivateKey` with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, `.privateKeyUsage`, no user presence; `keyStorage = secure-enclave`. Keychain item non-synchronisable; manifests excluded from backup (`platforms.md` C7).
**Desktop:** per `trust.md` §2.5 (T3–T0). `keyStorage` records the tier actually used; a JVM on macOS without the signed native helper is `os-keystore` or `file` (`trust.md` [A3]).

### 7.4 Signing procedure (producer)

```text
signPresentation(bodyOwn, audience, challenge?, now):
  body   = project(bodyOwn, audience)                                   // section 3.2
  obj    = { schema: "asom.manifest/1", schemaMinor: CURRENT_MINOR, body,
             presentation: { issuedAtMs: now,
                             expiresAtMs: now + (challenge != null ? 600_000 : 30 * 86_400_000),
                             challenge: challenge?.b64url } }
  validateStrict(obj)                                                   // Appendix A as written
  P      = JCS(obj)                                                     // bytes, section 5.2
  der    = platformSign(key, "SHA256withECDSA", PAE(PT_V1, P))          // Keystore / JCA / SecKeyCreateSignature return DER
  (r, s) = derToInts(der);  if s > n/2: s = n - s                       // low-S
  sig    = r.to32() || s.to32()                                         // fixed width, leading zeros kept
  c      = container(P, sig, keyid = nodeId, signer.spki, evidence(audience))
  verifyManifest(c, ctx = { mode: PINNED(ownSpki), expectedChallenge: challenge }, now)   // self-check through the CONSUMER path
         -> on any reject: do not send; local diagnostics; typed error to requester (MANIFEST_UNAVAILABLE)
  lastPresentationSent[audienceTarget] = c                              // exact bytes for the "last sent" viewer (section 13.2)
  return c
```

`seq` rule: when `JCS(bodyOwn)` differs from the stored one, `seq = stored.seq + 1`, and `(seq, JCS(bodyOwn), digest)` is written durably (Android: Room in a transaction; desktop: `FileChannel.force(true)`) before the first signature over it. A crash between persist and sign loses nothing; a crash before persist re-derives the same body and increments once.

### 7.5 Rotation, loss and compromise

| Event | Effect on manifests | Subscriber action |
|---|---|---|
| NIK lost (reinstall, data wipe, device reset [MA5]) | New key, new `nodeId`; old manifests unverifiable against the new pin | Mesh: re-pair (`trust.md` §8.4). File/TOFU subscribers see a new source; a changed key under an old `keyid` is `KEY_CHANGED` (vector M03-122), never silently re-pinned |
| NIK suspected compromised | Revoke on every peer (`trust.md` §8); manifests from that key rejected (`KEY_NOT_PINNED` once the row is gone) | — |
| Planned algorithm migration (post-quantum) | New major; reserved rollover statement: DSSE `…key-rollover.v1+json` payload `{oldNodeId, newNodeId, atMs}` signed by the old key **and** co-signed by the new key | Accepted only with a local user confirmation. Not in the first release |
| Leaf rotation (`trust.md` §2.6) | none: manifests are signed by the NIK, not the leaf | — |

### 7.6 Post-quantum note

A future `asom.manifest/2` may use ML-DSA-65 where StrongBox/TEE/Secure Enclave support it [MV5][MV8]. The algorithm is derived from the SPKI, never from a header, so migration is a new payloadType plus a new closed-enum value, not a negotiation.

---

## 8. Verification

### 8.1 Contexts

| Context | Key source | Challenge | Required tier (default) | Rollback store |
|---|---|---|---|---|
| **MESH** (requester verifying a `MANIFEST` frame) | The SPKI of **the session's authenticated peer** (the node certificate pinned in `trust.md`), never looked up by `keyid` | the one this requester sent (required) | A0 | per `(peer nodeId, audience)` |
| **FILE, pinned** (the user typed or scanned the 16-character fingerprint) | that SPKI | none | A0 (subscriber policy) | per subscriber |
| **FILE, TOFU** (first sight) | `container.signer.spki`, then remembered under its `nodeId` | none | A0; UI must show the compare-fingerprint prompt | per subscriber |
| **Third-party library** | any of the above, configured by the integrating app | configurable | configurable | configurable |

### 8.2 Algorithm (normative order; the reference implementation is `verify()` in `gen_manifest_vectors.py`)

```text
verifyManifest(docBytes, ctx, nowMs) -> Verified | Reject(code)
 1  if |docBytes| > 512 KiB                                       -> TOO_LARGE
 2  C = strictParse(docBytes)                                     -> MALFORMED_JSON | DUPLICATE_KEY | NON_INTEGER_NUMBER | NUMBER_RANGE | INVALID_UNICODE | TRAILING_DATA
 3  C.asomCapabilityManifest == 1                                 else CONTAINER_VERSION_UNKNOWN
    C.dsse is an object with string payloadType and array signatures  else CONTAINER_INVALID
 4  C.dsse.payloadType == "application/vnd.asom.manifest.v1+json" else SCHEMA_MAJOR_UNKNOWN (other manifest.vN) | PAYLOAD_TYPE_UNSUPPORTED
 5  |C.dsse.signatures| == 1                                       else SIGNATURE_COUNT
 6  P = b64either(C.dsse.payload); S = b64either(sig)             -> ENCODING
    |S| == 64                                                      else SIGNATURE_ENCODING
 7  key selection:
      MESH / pinned:  K = ctx.pinnedSpki;  if keyid present and keyid != nodeId(K) -> KEY_NOT_PINNED
      TOFU: if ctx.tofu[keyid] exists: K = it (TOFU_MATCH); if C.signer.spki present and != K -> KEY_CHANGED
            else if C.signer.spki present: K = it (TOFU_NEW)
            else -> KEY_NOT_PINNED
    K is an uncompressed P-256 SPKI with the point on the curve    else ALG_UNSUPPORTED
 8  ES256.verify(K, PAE(PT_V1, P), S) with 0<r<n, 0<s<n            else SIGNATURE_INVALID
    ---- from here on only P (the verified bytes) is read; C.dsse.payload is never re-read ----
 9  O = strictParse(P)                                             -> (as step 2)
10  JCS(O) == P                                                    else NON_CANONICAL
11  O.schema == "asom.manifest/1"                                  else SCHEMA_MAJOR_UNKNOWN | SCHEMA_INVALID
    tolerantSchema.validate(O)                                     else SCHEMA_INVALID
12  O.body.subject.nodeId == b64url(SHA-256(K))                    else SUBJECT_KEY_MISMATCH
13  O.presentation.issuedAtMs <= nowMs + 300_000                   else NOT_YET_VALID
    nowMs < O.presentation.expiresAtMs                             else EXPIRED
    0 < expires - issued <= (challenge != null ? 600_000 : 2_592_000_000)   else TTL_INVALID
14  if ctx.expectedChallenge != null:
        constantTimeEq(O.presentation.challenge, ctx.expectedChallenge)     else NONCE_MISMATCH   (null counts as mismatch)
15  consistency(O)  (section 8.4)                                  else INCONSISTENT
16  D = b64url(SHA-256(JCS(O.body)));  prev = rollback[(nodeId, audience)]
    if prev: O.body.seq < prev.seq                                  -> ROLLBACK
             O.body.seq == prev.seq and D != prev.bodyDigest        -> EQUIVOCATION
17  E = evaluateEvidence(C.evidence, K, O.body, ctx.attestationPolicy, nowMs)   // never rejects by itself (section 9)
18  tier(E) >= ctx.requiredTier                                     else TIER_INSUFFICIENT
19  commit: rollback[(nodeId, audience)] = (max seq, D); TOFU_NEW -> tofu[nodeId] = K (only after the user confirmed, in UI contexts)
    return Verified{ payloadBytes: P, obj: O, bodyDigest: D, pin, tier, evidence: E, unknownFields: count, warnings }
```

Human viewers may display a report rejected only at steps 13–16 (time, challenge, consistency, rollback) with the reject code shown in the verification block. Reports failing steps 1–12 are never rendered as content.

### 8.3 Typed rejects

| Code | Meaning | Can the signature still be valid? (measured by `VerifyDsse.java`) |
|---|---|---|
| `TOO_LARGE`, `MALFORMED_JSON`, `TRAILING_DATA` | Too big, not one JSON value, or nested deeper than 16 | yes (M03-123, M03-128) |
| `CONTAINER_VERSION_UNKNOWN`, `CONTAINER_INVALID` | Unknown container or missing DSSE fields | — |
| `PAYLOAD_TYPE_UNSUPPORTED` | Not a manifest payload type | yes, under its own type (M03-114) |
| `SCHEMA_MAJOR_UNKNOWN` | Newer major in payloadType or `schema` | yes (M03-119) |
| `SIGNATURE_COUNT`, `ENCODING`, `SIGNATURE_ENCODING` | Envelope profile violations | yes (M03-117) / no |
| `KEY_NOT_PINNED`, `KEY_CHANGED`, `ALG_UNSUPPORTED` | Wrong, changed or non-P-256 key | — |
| `SIGNATURE_INVALID` | Bytes changed after signing, or signed by another key | no (M03-101) |
| `NON_CANONICAL`, `DUPLICATE_KEY`, `NON_INTEGER_NUMBER`, `NUMBER_RANGE`, `INVALID_UNICODE` | Signed bytes that are ambiguous or outside the profile | **yes** (M03-104, -105, -106) |
| `SCHEMA_INVALID` | Payload violates the schema (incl. bidi characters) | **yes** (M03-126) |
| `SUBJECT_KEY_MISMATCH` | Payload names another node than its signer | **yes** (M03-115) |
| `NOT_YET_VALID`, `EXPIRED`, `TTL_INVALID` | Presentation time window | **yes** (M03-107, -108, -116) |
| `NONCE_MISMATCH` | Not an answer to this requester's challenge | **yes** (M03-109, -110) |
| `INCONSISTENT` | Claims contradict each other (§8.4) | **yes** (M03-120, -121) |
| `ROLLBACK`, `EQUIVOCATION` | Older than, or conflicting with, what this subscriber accepted | **yes** (M03-111, -112) |
| `TIER_INSUFFICIENT` | Subscriber policy requires a higher attestation tier | **yes** (M03-124) |

The right-hand column is the practical lesson for third-party implementers: **"the signature verifies" is necessary and nowhere near sufficient.** 21 of the 28 reject vectors carry a cryptographically valid signature (by test key 1, under the document's own payload type).

### 8.4 Internal consistency (rejects, not warnings)

A signer can claim anything, but it cannot claim self-contradictory things and still be used. `consistency(O)` fails when, for any result: two rows share `(fileSha256, backend)`; `runs.completed > planned` or `discarded > completed`; any `p10 > p50` or `p90 < p50`; `prefill.promptTokens > settings.ctxTokens` or `decode.contextTokens + genTokens > ctxTokens`; **TTFT is shorter than 90% of the prompt time implied by the same point's prefill rate** (`ttftMicros.p50 × milliTokPerSec.p50 × 10 < promptTokens × 10⁹ × 9`, vector M03-121); the curve does not start at `t = 0`, is not strictly increasing, or runs past `durationMs`; `throttleOnsetMs > durationMs`; **steady-state rate exceeds every curve point** (M03-120); `peakProcessBytes` or `availableBeforeLoadBytes` exceeds `device.memory.totalBytes`; `measuredAtMs > issuedAtMs`; `power.method = unavailable` with a non-null `avgMilliW`.

---

## 9. Attestation tiers

### 9.1 Two independent axes

**Pin (who):** `PINNED` (the key matches one pinned by a human ceremony — mesh pairing or a compared fingerprint), `TOFU_MATCH`, `TOFU_NEW`, or `OWN` (a device viewing its own report).
**Tier (what hardware):**

| Tier | Definition (as determined by the **verifier**) | What it proves | What it does NOT prove |
|---|---|---|---|
| **A0 self-signed** | Valid signature; `keyStorage` is `os-keystore`, `file` or `unknown` | The bytes are unchanged since the key signed them. With a pin: that the pinned device's key signed them | Anything about where the key lives, what software used it, or whether the content is true. Without a pin: nothing against a forger (anyone can make a key) |
| **A1 hardware-bound key, self-reported** | As A0, and `keyStorage` claims `strongbox`, `tee`, `secure-enclave` or `tpm`, with no verifiable evidence | For the verifier: exactly what A0 proves. The claim is displayed as "(self-reported)" | That the key is in hardware. **A1 is treated exactly as A0 for every decision.** It exists so that honest producers get key-extraction resistance and the UI can say what was claimed |
| **A2 platform-attested** | Evidence verified offline to a bundled vendor root and bound to the signing key (§9.3–§9.5) | The signing key was generated inside genuine vendor hardware of the attested security level, with the attested key properties. Android adds: the app package and signing certificate (as reported by the OS), the verified-boot state and patch level **at key generation**, and optionally the device's brand/model | That the device was uncompromised **after** key generation; that the benchmark ran honestly or in typical conditions; that the harness was unmodified at run time; that the vendor PKI was not compromised or a leaked factory key was not used (revocation, §9.6) |

A2 carries sub-flags that consumers read: `storage` (`strongbox`/`tee`/`secure-enclave`/`tpm`), `boot` (`verified`, `known-custom-os`, `unverified`), `app` (`asom-allowlisted`, `unknown`), `modelAttested` (bool), `revocation` (`checked:<snapshot age>`, `unchecked`), `root` (which vendor root).

### 9.2 What is genuinely available offline, per platform

| Platform | Best tier | Evidence | Offline verification? | Egress needed? | Recommendation |
|---|---|---|---|---|---|
| **Android** (daemon or standalone app) | **A2** | Key Attestation chain of the NIK itself (leaf = NIK certificate) | **Yes**: signatures and parsing need only bundled roots [MV1][MV16]. **Revocation needs the status list**, which Google serves online [MV2] (§9.6) | None at generation by asom (the OS provisions RKP keys itself [MA7]); none at verification if the status list arrives through the catalogue | **Build first.** No GMS: Key Attestation is a Keystore feature; Play Integrity (a GMS API [MA6]) is rejected by Invariant 8 |
| **iOS / iPadOS** | A1 now; A2 only via App Attest | App Attest attestation binding the Secure Enclave NIK (§9.4) | **Yes** for attestation and assertions (chain to the Apple App Attestation root, nonce, RP ID, counter) [MV10]. The receipt/fraud metric needs a server-to-server call to Apple (not used) | **Yes: `attestKey` contacts Apple's App Attest server** [MV9]. That is an egress class v1 does not permit | OD-M3: recommend **no App Attest**; iOS stays A1. A CryptoKit Secure Enclave key has no other third-party attestation for non-managed devices [MA2] |
| **macOS** | A1 (with the SE helper, `trust.md` S-A3) or A0 | none | — | — | App Attest `isSupported` is documented as false on Macs, although the server-validation page now also describes a macOS-specific step [MV11]: treat as unavailable until verified on hardware; and a JVM node would need an entitled native app anyway |
| **Linux (TPM 2.0)** | A1 now; A2 later | EK certificate chain + credential activation + `TPM2_Certify(NIK by AK)` + optional quote (§9.5) | Partly: verifying an EK chain is offline **if** the certificate is in TPM NV; the protocol is **interactive** (the requester acts as the privacy CA) | **Yes for many fTPMs**: Intel PTT before 11th gen and AMD fTPM EK certificates are downloaded from vendor servers [MV14] | Defer; not proposed for v4.0 (§9.5) |
| **Steam Deck** | A0 (A1 if the TPM module is re-enabled) | none | — | — | `trust.md` [V13] (low confidence): SteamOS disables the TPM module by default |

**Honest summary:** in the first release, **Android is the only platform where a subscriber can verify anything about the hardware behind a manifest.** Every other platform is A0/A1, i.e. "signed by this key, which you pinned or did not".

### 9.3 Android A2 evidence and verification (offline)

Evidence item: `{"type": "android-key-attestation/1", "chain": ["<base64 DER leaf>", …, "<base64 DER root>"]}` (from `KeyStore.getCertificateChain`).

```text
evaluateAndroid(ev, K, body, policy, nowMs) -> EvidenceResult | EvidenceFail(code)
  certs = ev.chain.map(parseX509);  2 <= |certs| <= 8                         else EV_CHAIN_SHAPE
  certs[0].SPKI == K                                                           else EV_KEY_MISMATCH     // binds the evidence to THIS signer
  for i in 0 .. |certs|-2:
      certs[i].issuer == certs[i+1].subject and sigValid(certs[i], certs[i+1].publicKey)   // RSA-2048, ECDSA P-256, ECDSA P-384
                                                                               else EV_CHAIN_BROKEN
  certs.last.SPKI in policy.googleRoots                                        else EV_UNTRUSTED_ROOT
      // bundle BOTH: RSA root "SERIALNUMBER=f92009e853b6b045" (2022-2042) and ECDSA P-384 "Key Attestation CA 1",
      // which signs chains from 2026-02-01 [MV1]. Do not enforce validity periods on the RSA-rooted chain (Google's guidance [MV1]).
  if policy.statusSnapshot != null:
      any cert serial (lowercase hex) with status REVOKED or SUSPENDED          -> EV_REVOKED           [MV2]
  ext = first certificate, from the leaf up, carrying OID 1.3.6.1.4.1.11129.2.1.17  [MV4]
      that certificate must be certs[0]                                        else EV_EXTENSION_POSITION   // only the first occurrence is trustworthy [MV3]
  kd = parseKeyDescription(ext)                                                // attestationVersion 1..4, 100..500 accepted [MV4]
  kd.attestationSecurityLevel in {TrustedEnvironment(1), StrongBox(2)} and == kd.keyMintSecurityLevel   else EV_SOFTWARE
  kd.attestationChallenge == ASCII("asom-nik/1")                               else EV_CHALLENGE
  hw = kd.hardwareEnforced
  hw.algorithm == EC and hw.ecCurve == P_256 and SIGN in hw.purpose and SHA_2_256 in hw.digest and hw.origin == GENERATED
                                                                               else EV_KEY_PARAMS
  storage = (level == StrongBox) ? "strongbox" : "tee"
  body.subject.keyStorage == storage                                           else EV_STORAGE_CLAIM_MISMATCH
  rot  = hw.rootOfTrust                                                        // verifiedBootKey, deviceLocked, verifiedBootState, verifiedBootHash [MV4]
  boot = rot.verifiedBootState == Verified and rot.deviceLocked                       ? "verified"
       : rot.verifiedBootState == SelfSigned and rot.deviceLocked
             and rot.verifiedBootKey in policy.knownCustomOsBootKeys                  ? "known-custom-os"
       : "unverified"
  aid  = kd.softwareEnforced.attestationApplicationId                          // supplied by the OS keystore, not the TEE [MA1]
  app  = aid.packageNames ∩ policy.asomPackages != ∅ and aid.signatureDigests ⊆ policy.asomSigningCerts ? "asom-allowlisted" : "unknown"
  ids  = (hw.attestationIdBrand, …Device, …Manufacturer, …Model, …Product)      // tags 710, 711, 716, 717, 712 [MV4][MV5]
  modelAttested = ids all present and equal to body.device.platformIds (byte-exact)
  return A2{ storage, boot, app, modelAttested, osVersion: hw.osVersion, osPatchLevel: hw.osPatchLevel,
             revocation: policy.statusSnapshot ? "checked:" + (nowMs - snapshot.fetchedAtMs) : "unchecked", root }
```

`EvidenceFail` never rejects the manifest: the tier falls back to A1/A0 and the verification block says "attestation failed: <code>". A policy that requires A2 then rejects with `TIER_INSUFFICIENT`. Parsing needs a DER reader; the JDK has no public ASN.1 API [MA10], so either a small audited DER reader (with its own vectors) or Google's Apache-2.0 `android/keyattestation` Kotlin library [MV16] if a spike shows it runs on a plain JVM.

**What Android A2 does and does not add, precisely.**
- **Key in hardware:** proven (TEE or StrongBox security level, generated not imported).
- **App identity:** the package name and signing-certificate digests are reported by the OS keystore [MA1]. With `boot = verified` they mean "this key was created by an APK signed with the asom release key on a locked, verified OS". With `boot = unverified` they mean nothing.
- **Device model:** proven only when the device-properties attestation was accepted at key generation and the five values match `platformIds` (`modelAttested`). Many devices will not support it (`ProviderException`, [MV5]).
- **Harness unmodified:** not proven. At best (verified boot + allow-listed signing certificate) the installed binary was owner-signed at key generation time; a later root exploit can alter it at run time.
- **All of it is point-in-time at NIK generation.** A later compromise does not show. (A fresh attestation per challenge would require generating a new attested key per request; rejected for cost and because the NIK binding would then need a second signature chain.)

### 9.4 Apple App Attest binding (only if OD-M3 approves; not recommended now)

```text
at NIK creation (iOS):
  nik     = SecureEnclave.P256.Signing.PrivateKey(...)                 // the manifest signer
  aaKey   = DCAppAttestService.shared.generateKey()                    // a separate SE key only usable via App Attest [MV9]
  cd      = PAE("application/vnd.asom.appattest-bind.v1", nikSpkiDer)
  attObj  = attestKey(aaKey, clientDataHash: SHA256(cd))              // NETWORK: Apple App Attest service [MV9]
evidence: {"type":"apple-app-attest/1","keyId":b64(aaKey),"attestation":b64(attObj),"assertion":b64|null}
per challenge (optional): assertion = generateAssertion(aaKey, clientDataHash: SHA256(PAE(PT_V1, P)))      // on-device [MA3]
verify (offline) [MV10]: x5c chain to the bundled Apple App Attestation root; nonce = SHA256(authData || SHA256(cd)) where cd is
  recomputed FROM THE SIGNER'S SPKI (this is the binding) equals credCert extension 1.2.840.113635.100.8.2; SHA256(credCert key) == keyId;
  rpIdHash == SHA256("<TeamID>.<bundleId>"); counter == 0; aaguid == "appattest" + 7 zero bytes; credentialId == keyId;
  validation-category and bundle-version extensions as documented. Assertion: ECDSA over SHA256(authData || SHA256(PAE(PT_V1,P)))
  with the credCert key; rpIdHash; counter strictly greater than the last one this verifier saw for keyId.
```

It would prove: a genuine Apple device's Secure Enclave holds a key created by an instance of the asom app with that App ID, and that instance bound this NIK. It would **not** prove that the NIK itself is in the Secure Enclave (the app says so), the device model, the absence of a jailbreak [MA13], or honest measurement. App Attest keys do not survive reinstall, device migration or backup restore [MV9].

### 9.5 TPM (deferred; sketch only)

Credential activation needs a verifier that knows the EK: `R→P` challenge; `P→R` EK certificate + EK public + AK name; `R` verifies the EK chain to a bundled manufacturer root and runs `MakeCredential`; `P` runs `ActivateCredential`, `TPM2_Certify(NIK, AK, qualifyingData = challenge)` and optionally `TPM2_Quote`; `R` checks the secret, the certified NIK name and attributes (`fixedTPM`, `fixedParent`, `sensitiveDataOrigin`). It needs two new frame pairs and, on common fTPMs, a vendor EK-certificate download [MV14]. PCR values have no reference baseline on a general-purpose Linux box; a quote would only support "changed since pairing" alerts, which kernel updates trigger constantly. Not proposed for v4.0.

### 9.6 Roots, revocation and the sovereignty cost

- **Roots:** bundled in the app and the desktop node (a mirror of Google's published roots [MV16]); updated by app release and optionally by the catalogue (MC-5).
- **Revocation:** Google says each chain certificate must be checked against its status list at `https://android.googleapis.com/attestation/status` (JSON; statuses `REVOKED`/`SUSPENDED`; `Cache-Control` sets refresh) [MV2]. Fetching it directly from a device is an egress class v1 does not have. OD-M4 recommends mirroring it into the catalogue, which is already a permitted egress (Invariant 3b). Until then every A2 result says `revocation: unchecked`. Google also states that leaks affect only factory-provisioned keys, not RKP-certified ones, and that devices launching with Android 16 use RKP only [MV2].
- **Sovereignty:** A2 makes Google (or Apple) the anchor of a claim. That fits "the cloud is a watched object" only if it is visible and optional: A2 is informational everywhere in an own-device mesh; a user setting "Ignore vendor attestation" makes every A2 display as A1 (OD-M7).

### 9.7 Which tier each consumer should require, for which decision

| Decision | Minimum pin | Minimum tier | Notes |
|---|---|---|---|
| Show the report to a human | any | A0 | The verification block (§14) always states pin and tier; `TOFU_NEW` shows a compare-fingerprint prompt |
| Mesh router **prior** before any observation | PINNED | A0 | Clamped and discounted (§11.5); A2 with `boot=verified`, `app=asom-allowlisted` gets a larger prior weight, never an exemption from observation |
| Mesh router after ≥ 3 observations of a model | PINNED | A0 | The manifest matters only for models not yet observed |
| Decide which data classes a peer may receive, or its trust class | — | **never from a manifest** | Registry and user only (`trust.md` R3, §7.3). No tier changes this |
| UI label "hardware-backed key (attested)" | any | A2 | Otherwise "(self-reported)" |
| UI label "genuine asom app on a verified OS" | any | A2 + `boot=verified` (or known custom OS) + `app=asom-allowlisted` + `revocation` checked within 30 days | Android only |
| UI label "device model attested" | any | A2 + `modelAttested` | Android only |
| Other-owner peer (only if `trust.md` OD-2 allows) | PINNED | A2 as above for **any** use of claims; otherwise observation only | Cross-owner claims are the most attractive poisoning target |
| A third-party app deciding whether to offload work to a device, from a file | fingerprint-compared (PINNED) **or** A2 + `app=asom-allowlisted` | — | `TOFU_NEW` alone: display only |
| Public community table | n/a | n/a | Unsigned by design (§12); robust statistics only |
| Governor thresholds on the device itself | — | — | Use the local calibration store (roadmap v2 P6), not a manifest |

---

## 10. The honest tamper-evidence analysis

| Question a subscriber might ask | Mechanism | Answer when everything verifies | What it does NOT establish |
|---|---|---|---|
| Were these bytes changed after signing? | ES256 over `PAE(type, P)` | No | Whether they were true when signed |
| Which key signed them? | Same | Key K | Which **device** holds K — unless K is PINNED (pairing, fingerprint compared in person) or A2-attested. `TOFU_NEW`: nothing; anyone can make a key |
| Was the key used after my request? | Challenge inside `P` | Yes | That the measurements are new; the signer can re-sign old measurements |
| Is the key inside secure hardware? | A2 evidence | Yes (Android; iOS only via App Attest) | That only asom uses it; that the device stayed uncompromised after key generation |
| Is the claimed device model true? | Android device-properties attestation | Yes when `modelAttested` | On iOS, macOS, Linux: never, by any mechanism here |
| Was the harness unmodified? | Android A2 + verified boot + allow-listed signing certificate | The installed binary was owner-signed at key generation time | Run-time integrity; modified inputs; on every other platform nothing |
| Did the benchmark run honestly and typically? | **No cryptographic mechanism** | — | Cooling tricks, best-of-N (runs counts are claims), background load. Only observation by the requester (§11.5) and requester-run probes (§11.6) address it, and only approximately |
| Is this the latest report? | `seq` + rollback store | Not older than what this subscriber accepted before | Anything at first contact; the signer chooses `seq`, so it defends only against third-party replay |
| Did the public contribution come from a real device? | none | Unknown, stated as such | Everything (§12.4) |

---

## 11. Freshness and abuse

### 11.1 Time fields

| Field | Meaning | Who relies on it |
|---|---|---|
| `results[].measuredAtMs` | When the measurement ended (claimed) | Content staleness (§11.4) |
| `presentation.issuedAtMs` | When this presentation was signed (claimed, bounded by the challenge in the mesh) | `NOT_YET_VALID` with 5 min future skew |
| `presentation.expiresAtMs` | Last instant this presentation may be accepted | `EXPIRED`; TTL ≤ 10 min with a challenge, ≤ 30 days without |

Clock skew: freshness in the mesh rests on the challenge, not on clocks, so a wrong clock cannot make a replay acceptable. A badly skewed producer clock produces `NOT_YET_VALID` or `EXPIRED` (fails closed), which the Peers tab surfaces together with `trust.md`'s `CLOCK_SKEW` diagnostics.

### 11.2 What the requester challenge adds on an mTLS channel that is already authenticated

1. **NIK liveness.** A stolen TLS leaf (`trust.md` §2.1) lets an attacker complete mTLS as the node for up to 14 days, and replay any manifest it captured. A challenge-bound presentation needs a **fresh NIK signature**, which a leaf thief cannot produce when the NIK is hardware-bound. The requester then sees `NONCE_MISMATCH` from a peer that passes TLS — a strong compromise signal, raised in the Peers tab.
2. **Per-request binding for audit:** the stored presentation shows what the peer claimed when this requester asked, at that time.
3. It does **not** stop the genuine node from re-presenting stale or invented measurements.

The requester binds the signer to the channel: in MESH context the key is the session peer's pinned key (§8.1), so a peer cannot relay another node's manifest.

### 11.3 Rollback and equivocation

Store per subscriber: `(nodeId, audience) → (maxSeq, bodyDigest at maxSeq)`, persisted. `seq < maxSeq` → `ROLLBACK`; `seq == maxSeq` with another digest → `EQUIVOCATION` (the signer issued two different bodies under one `seq`: a bug or an attack; flagged on the peer). Limits: the signer controls `seq`, so this protects only against replays by third parties (file swaps, stale caches), and nothing at first contact.

### 11.4 Content staleness (the requester's rules)

A verified result row is **stale** (used for display only, excluded from routing priors) when any of: `nowMs − measuredAtMs > 30 days`; its `backend` or `engine.commit` differs from the peer's current live state (`router.md` `STATE`); the peer's OS major version differs; the peer's live state reports a new `manifest.bodyDigest` not yet fetched.

### 11.5 Treating a manifest as a claim: the claim-versus-observed tracker

This is the only defence against a signer that lies about performance. It is a pure function over integers, kept per requester, fed by verified manifests and by the requester's own measurements of completed peer requests. The router (`router.md`) consumes only its output `ClaimView`.

```text
key k = (peerNodeId, fileSha256, backend)

Claim from the verified result row r:
  decodeAt(ctx)  = piecewise-linear over r.decode[].(contextTokens -> milliTokPerSec.p50), clamped at the end points
  steady         = r.sustained.steadyMilliTokPerSec
  prefill        = r.prefill[0].milliTokPerSec.p50
  ttft0Us        = max(0, r.prefill[0].ttftMicros.p50 - r.prefill[0].promptTokens * 1e9 / prefill)

Observation from one completed attempt (requester's monotonic clock; completionTokens >= 32 only):
  decodeObs  = (completionTokens - 1) * 1e9 / (tLastChunkUs - tFirstChunkUs)            // milliTok/s
  ttftObsUs  = tFirstChunkUs - tBodySentUs
  hot        = peerSTATE.thermal >= moderate or peerSTATE.busyForMs >= r.sustained.throttleOnsetMs
  expDecode  = hot ? min(decodeAt(promptTokens), steady) : decodeAt(promptTokens)
  ratio      = min(5000, decodeObs * 1000 / expDecode)                                   // permille
  expTtftUs  = ttft0Us + promptTokens * 1e9 / prefill + rttUs                            // rttUs from PING/PONG
  ttftRatio  = min(5000, expTtftUs * 1000 / ttftObsUs)

Window W(k) = last 20 observations; n = |W|; med = lower median of ratios
State(k):   n < 3                   -> UNVERIFIED
            med >= 800              -> CORROBORATED
            600 <= med < 800        -> WEAK
            med < 600               -> DISCREPANT      (leave only after 10 new observations with med >= 800, or a new
                                                        manifest with a higher seq, which restarts W(k) against the new claim)

prior(k)    = min(decodeAt(512), capRef) * disc / 1000
  capRef    = editorial reference p90 for (model, backend, device class) * 12/10 when the catalogue has one (roadmap v2 P6), else the claim
  disc      = 900 if A2 with boot=verified and app=asom-allowlisted, else 700; 400 if ANY key of this peer is DISCREPANT
  w0        = 5 if that A2 condition holds, else 2
Effective(k):
  UNVERIFIED, CORROBORATED -> (w0 * prior + sum(decodeObs in W)) / (w0 + n)
  WEAK, DISCREPANT         -> median(decodeObs in W)
ClaimView(k) = { effectiveMilliTokPerSec, state, n, claimed: decodeAt(512), tier, pin }
```

Also fed back: a peer `MODEL_OOM`/`INFER_END terminal=oom` for model m marks its memory claim for m `DISCREPANT` (the router stops placing m there until a manifest with a higher `seq`); repeated `PEER_THERMAL` before `throttleOnsetMs` marks the sustained claim `WEAK`. All arithmetic is 64-bit signed with floor division, specified so that `router.md`'s R-family vectors can pin it (M08, §16.3).

**Recommendations to `router.md`** (owned there): an `UNVERIFIED` key may win at most 1 in 4 of the placements it would win on score until `n ≥ 3` (bounded exploration); a `DISCREPANT` peer is shown in the Peers tab as "claims more than it delivers (observed X% of claimed)" — the watched object applies to peers too.

**Why this bounds manifest poisoning.** To attract traffic a malicious peer must claim more than it delivers; after 3 observations its claims no longer affect placement for that model, and its other claims are discounted to 40%. Traffic attraction is further bounded by data classes, which no claim can change. A peer that *does* deliver the claimed speed is not poisoning placement — though it may still mishandle data (`trust.md` §9.1), which performance observation cannot detect.

### 11.6 Requester-run probes (the only measurement not made by the claimant)

A probe is an ordinary `INFER_OFFER`/`INFER_BODY` with a synthetic prompt: 512 tokens of words drawn from a fixed public word list with a PRNG seeded by a fresh 32-byte nonce (so the peer cannot pre-compute or cache it), `max_tokens = 128`, `temperature = 0`. The requester measures TTFT and decode rate itself and feeds one observation. It is ledgered on both nodes like any request, with caller `asom:probe`. It contains no user data. OD-M6: recommend **user-initiated only** ("Check this device's claims" in the Peers tab). Limit: a peer can deliver a probe honestly and still mishandle real prompts; probes measure speed, not conduct.

### 11.7 Other abuse cases

| Abuse | Defence |
|---|---|
| Oversized or deeply nested documents to exhaust the verifier | 512 KiB / 256 KiB / depth 16 / array limits, checked before schema validation |
| Bidi or control characters to spoof the plain text | Schema pattern (M03-126) + renderer replacement |
| Claiming models it does not hold, to be offered work | Harmless to data: `INFER_OFFER` carries no content (`trust.md` R5); `MODEL_NOT_OFFERED` counts as a failed observation |
| Manifest spam (many `seq` bumps) | The requester pulls; it never accepts pushes. Pull rate ≤ 1 per 10 min per peer unless the live-state digest changed |
| Signing cost as denial of service on StrongBox | Server-side limit ≤ 6 `MANIFEST_REQ` per peer per hour, 1 concurrent → `PEER_BUSY` (signing latency [MA4]) |

---

## 12. Private versus public: the tension and its resolution

### 12.1 The tension

Roadmap v2 P7: the contributed payload is anonymous by construction — no install ID, no fingerprint beyond coarse device class — and the receiver must not keep IPs. A verifiable manifest wants a stable device key. **A stable public key in an uploaded payload is an install fingerprint**, so the two cannot be the same object.

### 12.2 Resolution

| | Private manifest | Public derivative |
|---|---|---|
| Schema | `asom.manifest/1` | `asom.bench-public/1` (Appendix B) |
| Signed | yes, by the NIK (or report key) | **no** |
| Contains | full body per audience | allow-listed subset, quantised |
| Goes to | paired peers on request (§13.2); a file the user exported (§13.3) | the owner-operated receiver, only by the v2 P7 view-first tap |
| Never goes to | any receiver, any unpaired party automatically | any peer (it is useless to them) |

`PublicDerivative.from(verifiedPrivatePayload, selectedResults)` is a pure allow-list function (reference: `public_derivative()`):

| Public field | Source | Transformation |
|---|---|---|
| `device.class, vendor, model, socName, osFamily, cooling` | body | copied (coarse by nature) |
| `device.ramClassGiB` | `memory.totalBytes` | smallest of 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 2048 GiB ≥ total |
| `device.osMajor` | `os.version` | leading integer only |
| `harness.version, methodologyId, confVersion`; `engine.name, commit` | producer | copied |
| per result: `modelId, quant, fileSha256, backend, charging, prefillPromptTokens, decodeContextTokens, runsCompleted, powerMethod` | result | copied (public catalogue values or small enums) |
| `measuredMonth` | `measuredAtMs` | `YYYY-MM` UTC |
| `prefillMilliTokPerSec, decodeMilliTokPerSec, steadyMilliTokPerSec, ttftMillis, throttleOnsetSec, peakProcessMB, powerMilliW` | result | `q2` (2 significant digits, half-up; < 100 unchanged) |
| `curve` | sustained curve | ≤ 12 evenly spaced points `[q2(seconds), q2(rate)]` |

Never emitted (law **LM-2**, asserted in the generator): `nodeId`, SPKI, `seq`, `challenge`, exact timestamps, `platformIds`, `securityPatch`, OS build, GPU driver versions, `keyStorage`, `audience`, evidence, `signer`, `keyid`, settings (threads, layers, batch), temperatures.

### 12.3 What the public derivative can and cannot guarantee

- **Can:** (a) *transport* integrity and receiver authenticity by HTTPS; (b) *transparency of what was sent*: the viewer shows the exact bytes and their SHA-256 before the tap; the receiver's reply echoes that hash, so the user can see the receiver stored exactly those bytes; (c) *derivability*: on the device, the viewer shows the private report beside the public bytes, so the user can see the public one is a projection of their signed report.
- **Cannot:** show that it came from a real device, a real asom install, or an honest run. Anyone can post any JSON. Every published aggregate must say "unverified community submissions". Robust aggregation (medians, trimming, comparison with the owner's editorial reference table) is receiver policy, not a guarantee.
- **Residual linkability, stated:** the combination of device model, set of models, month and quantised numbers may still be unique among uploads, so two uploads from one install can sometimes be linked by content, and a receiver seeing transport metadata sees more. Mitigations: the user selects which results to include; quantisation; no exact times. This is "anonymous by construction" in the roadmap's sense (no identifier), not "unlinkable against a determined receiver".

### 12.4 Rejected alternatives for a verifiable-yet-anonymous upload

| Alternative | Why rejected |
|---|---|
| Sign with a fresh per-upload key | Proves nothing about origin (anyone can make a key); only a checksum with extra steps |
| Attach Android attestation of a fresh per-upload key | RKP attestation keys are per-app and rotate [MV15], so uploads within a rotation window are linkable; chains also reveal patch level and the verified-boot key. That is a fingerprint beyond coarse class |
| Group signatures or anonymous credentials (BBS+, Privacy Pass style) | Need an issuer that checks membership — an operator identity service — and are not hardware-backed; contradicts "no operator backend" |
| A salted commitment to the private digest, revealed later by choice | Harmless to privacy, but gives the receiver nothing verifiable unless the user later de-anonymises. Not worth the scrutiny in v2 P7; parked |

---

## 13. The subscriber model

### 13.1 Channels

| # | Subscriber | Channel | New contract surface | Freshness |
|---|---|---|---|---|
| S1 | Paired asom node (requester) | Pull over `asom-mesh/1`: `MANIFEST_REQ` / `MANIFEST` (0x22/0x23, already in `trust.md` C-1); change hint in live state | Frame **payload** definitions (MC-1), live-state field (MC-3), one mesh error (MC-1) | Challenge, per request |
| S2 | Any app or device, anywhere | A **file** the user exported (`.asom-manifest.json` + `.asom-report.txt`) through the share sheet, Storage Access Framework, or `asom manifest export --out` on desktop; imported by opening it | File format (MC-2); **no endpoint** | None (≤ 30-day presentation); rollback store per subscriber |
| S3 | The device's own user | The standalone app or the asom dashboard renders its own report | none | n/a |
| S4 | Apps on the same device (optional) | `GET /admin/manifest` on 127.0.0.1, bearer-gated | **New endpoint** (MC-4) | Optional challenge query |

**Is there an approach with no new endpoint?** Yes: S1 + S2 + S3 satisfy requirement 3 without touching §5.2. S1 needs the mesh protocol that v4 introduces anyway; S2 needs only a file format. S4 is recommended **deferred** (OD-M5) until a consuming app actually needs machine-readable capability data from the local daemon.

### 13.2 S1: mesh pull, exactly

```text
Requester R, provider P, session ESTABLISHED (trust.md section 5), R holds scope "manifest" on P.
When: (a) session established and R has no unexpired verified body for P, or
      (b) P's STATE.manifest.bodyDigest != cached digest (router.md live state), or
      (c) the user opens P in the Peers tab.   Never more than once per 10 min per peer unless (b).
R: c = CSPRNG(32); pending[stream] = c; send MANIFEST_REQ {"challenge": b64url(c)} on a new odd stream; timeout 10 s
P: authorize(pin_R, "manifest") per frame (trust.md 5.3)                    else ERROR SCOPE_DENIED
   rate: <= 6 per peer per hour, 1 in flight                              else ERROR PEER_BUSY {retryAfterMs}
   no body yet (no benchmark run)                                          -> ERROR MANIFEST_UNAVAILABLE
   container = signPresentation(bodyOwn, audience = class(R), challenge = c, now)     (section 7.4)
   send MANIFEST (0x23): payload = container UTF-8 bytes (<= 512 KiB)
R: verifyManifest(container, {mode: MESH, pinnedSpki: sessionPeerSpki, expectedChallenge: c, requiredTier: policy}, now)
   accept -> cache {P bytes, bodyDigest, seq, tier, pin, receivedAtMs}; update rollback; ClaimTracker.onManifest
   reject -> local diagnostics; Peers tab "report rejected: <CODE>"; ClaimTracker uses observation only for P
Ledger: control-plane traffic, recorded per session as trust.md section 11.3 specifies (egress class lan, pending trust.md OD-1).
Peers tab: "Last report sent to <peer>" shows the exact container bytes and their plain-text rendering (trust.md 12.2, Invariant 1 clarification).
```

Push was rejected: an unsolicited `MANIFEST` has no challenge, adds network events the peer did not ask for, and gives a malicious peer a spam channel. The live-state digest turns "push on change" into "pull on hint" at the cost of one field.

### 13.3 S2: files

- **Export screen (view-first):** shows the plain text, the exact container bytes, and two choices: *Full signed report* (warning: "contains this device's key fingerprint XXXX-…; anyone holding the file can link it to this device") or *Anonymous summary* (the public derivative, unsigned, with the text "cannot be verified by anyone"). Evidence inclusion is a separate toggle, default off. The export is an explicit foreground action whose exact payload is shown first — OD-M2 decides whether that sits inside Invariant 1's existing principle.
- **Import:** any subscriber runs §8.2 in FILE mode. The asom apps offer "Compare fingerprint" (type or scan the 16-character code shown on the producing device) to move from `TOFU_NEW` to `PINNED`.
- Files are excluded from cloud backup by the producer (C7); what the recipient does with an exported file is outside asom's control, and the export screen says so.

### 13.4 Rejected subscriber channels

| Channel | Why rejected |
|---|---|
| Put the manifest (or its digest) in the discovery `ContentProvider` (§5.1) | That provider needs no permission, so any app on the device could read a stable device key fingerprint: an unauthenticated device identifier |
| Broadcast / mDNS TXT carrying capability data | Open discovery beyond paired devices (roadmap §11 stop-line); presence and capability disclosure to a whole LAN |
| Unsolicited `MANIFEST` push frames | §13.2 |
| A public "manifest URL" hosted by asom | A public listener (Invariant 2; roadmap §11) |
| A world-readable file in shared storage | Same unauthenticated-identifier problem as the discovery provider |

---

## 14. Plain text and JSON from one source

### 14.1 Laws

- **LM-3** Every consumer-facing rendering is a pure function of the verified payload bytes `P` and, for text, the viewer's `VerificationResult`: `text = M05(parse(P), vr)`, `digest = SHA-256(JCS(parse(P).body))`, `public = PublicDerivative(parse(P))`.
- **LM-4** The producer renders its own report only after signing and self-verifying, from the signed bytes (`sign → verify → parse → render`), never from the in-memory `ManifestBody`. A signing or encoding bug therefore shows up on the producer's own screen.
- **LM-5** `M05(parse(JCS(o)), vr) == M05(o, vr)` for every valid `o` (property test), and M05 output is byte-exact across implementations (UTF-8, LF, no locale, no time zone other than UTC).
- **LM-6** The verification block is computed by the viewer and never taken from the payload. A producer cannot sign the words "verified" or "attested" into its own report.

### 14.2 Renderer `asom.manifest-text/1` (header and verification block; the result block moves to `asom.text/1` if `benchmark.md`'s document is adopted, §1)

| Quantity | Rule (integer arithmetic only) | Example |
|---|---|---|
| tokens/s from `milliTokPerSec` v | `x = (v + 50) / 100` → `x/10 "." x%10` | 18400 → `18.4 tokens/s` |
| bytes | decimal GB: `x = (b + 5·10⁷) / 10⁸` → one decimal | 2019377152 → `2.0 GB` |
| µs | ≥ 1 s: one decimal seconds; else integer ms (half-up) | 2480000 → `2.5 s` |
| ms duration | `s = (ms + 500) / 1000` → `"<m> min <s> s"` or `"<s> s"` | 190000 → `3 min 10 s` |
| ratio | `(num · 1000 / den + 5) / 10` `%` | 11200/18400 → `61%` |
| mW | `x = (mw + 50) / 100` → one decimal `W` | 6100 → `6.1 W` |
| epoch ms | truncate to the minute, `YYYY-MM-DD HH:MM UTC` | — |
| unknown open-enum value | `other (<value>)` | — |
| unknown fields | final line "This report has N item(s) from a newer format that this viewer does not show." | M05-102 |
| `pin` | `PINNED`: "matches the key you paired with"; `TOFU_MATCH`: "same key as the first report you accepted from this source (not compared in person)"; `TOFU_NEW`: "seen for the first time. Compare the fingerprint above…"; `OWN`: "this device's own key" | — |
| tier | A2: "(attested by the platform)" plus the sub-flags in words; A0/A1: "(self-reported, not attested)" | — |
| always | "Not proven: that the measurements were honest or typical, that the device model is true, or that the benchmark software was unmodified." — reduced only by the specific A2 sub-flags that prove part of it (model attested; owner-signed app on verified OS) | — |

Colour is never used for meaning (Invariant 6): graphical front-ends use the violet/cyan pair plus a shape and the same words (for example ◆ attested / ◇ self-reported).

### 14.3 The example rendering

Rendered by the reference renderer from the verified bytes of vector M02-101 (PINNED, A1, challenge matched); byte-exact expectation in `M05-render.json`:

```text
ASOM CAPABILITY REPORT
Device: Example Phone 1 by ExampleVendor (phone)
Report 17, signed 2026-09-29 10:00 UTC, valid until 2026-09-29 10:10 UTC
Signer: node XWWD-3XQW-7TEB-MU27

VERIFICATION (checked by this viewer, not stated by the device)
- Signature: valid. The report has not changed since this key signed it.
- Signer key: matches the key you paired with.
- Key storage: StrongBox secure element (self-reported, not attested).
- Freshness: signed for your request.
- Not proven: that the measurements were honest or typical, that the device model is true,
  or that the benchmark software was unmodified.

DEVICE
- Chip: ExampleSoC 8 by ExampleSilicon, 8 CPU cores
- Memory: 17.2 GB
- Accelerators: GPU ExampleGPU 800 (opencl, vulkan); NPU ExampleNPU (no API used)
- OS: android 16, security patch 2026-09-01
- Cooling: fan; power: battery
- Benchmark: asom-bench 1.0.0, method asom-bench-method/1, llama.cpp 0123abcd

RESULTS
1. example-3b-instruct Q4_K_M (2.0 GB file) on opencl, measured 2026-09-28 14:02 UTC
   - Writes about 18.4 tokens/s at 512 tokens of context, 15.1 tokens/s at 3584.
   - Reads prompts at about 212.0 tokens/s; first token after 2.5 s for a 512-token prompt.
   - Under sustained load (10 min 0 s): slows down after 3 min 10 s, settles at 11.2 tokens/s (61% of the short-context speed).
   - Memory: needs about 2.6 GB; 8.3 GB was free before loading, leaving about 5.7 GB.
   - Power: about 6.1 W (from battery current); on battery, battery 87% at start.
   - Runs: 5 of 5 completed, 0 discarded.
2. example-3b-instruct Q8_0 (3.4 GB file) on cpu, measured 2026-09-28 14:40 UTC
   - Writes about 9.8 tokens/s at 512 tokens of context.
   - Reads prompts at about 61.0 tokens/s; first token after 8.6 s for a 512-token prompt.
   - Under sustained load (5 min 0 s): no slowdown detected; settles at 9.1 tokens/s.
   - Memory: needs about 3.9 GB; 8.1 GB was free before loading, leaving about 4.2 GB.
   - Power: not measured; charging, battery 64% at start.
   - Runs: 3 of 5 completed, 1 discarded. Few runs: treat as rough.
```

---

## 15. Complete example

**All numbers are invented for illustration; the device, SoC and model are fictional; the key is TEST-ONLY** (private scalar published in `TEST-ONLY-keys.json`). Signer `nodeId` `vaw93hb8yBZTX2LebYgzri1pfOnBlRIILoBLiyK_eN4`, display fingerprint `XWWD-3XQW-7TEB-MU27`, challenge `nvsK-QRFTzOSUx0XtUEgqKOWIsUY8j2dL4ikBHOh5pw`, `bodyDigest` `q9byxb_XHtIh4tLfwivvuPTg5yq7e2E7H7eCxoz3QwA`.

### 15.1 Payload (pretty-printed for reading; the signed bytes are its JCS form, `example-payload.jcs.json`, 4,228 bytes)

```json
{
  "schema": "asom.manifest/1",
  "schemaMinor": 0,
  "body": {
    "audience": "own",
    "seq": 17,
    "subject": {
      "nodeId": "vaw93hb8yBZTX2LebYgzri1pfOnBlRIILoBLiyK_eN4",
      "keyAlg": "ES256",
      "keyStorage": "strongbox"
    },
    "producer": {
      "app": "asom-android",
      "appVersion": "4.0.0",
      "harness": {
        "id": "asom-bench",
        "version": "1.0.0",
        "methodologyId": "asom-bench-method/1",
        "confVersion": "1.0.0"
      },
      "engine": {
        "name": "llama.cpp",
        "commit": "0123abcd",
        "buildFlags": [
          "GGML_OPENCL=ON",
          "GGML_VULKAN=ON"
        ]
      }
    },
    "device": {
      "class": "phone",
      "vendor": "ExampleVendor",
      "model": "Example Phone 1",
      "platformIds": {
        "brand": "examplevendor",
        "device": "ex1",
        "manufacturer": "ExampleVendor",
        "model": "EX-1",
        "product": "ex1_global"
      },
      "os": {
        "family": "android",
        "version": "16",
        "securityPatch": "2026-09-01"
      },
      "soc": {
        "vendor": "ExampleSilicon",
        "name": "ExampleSoC 8",
        "cpu": {
          "logicalCores": 8,
          "clusters": [
            {
              "cores": 2,
              "maxKHz": 4320000
            },
            {
              "cores": 6,
              "maxKHz": 3530000
            }
          ]
        }
      },
      "memory": {
        "totalBytes": 17179869184
      },
      "accelerators": [
        {
          "kind": "gpu",
          "vendor": "ExampleSilicon",
          "name": "ExampleGPU 800",
          "apis": [
            "opencl",
            "vulkan"
          ],
          "dedicatedBytes": null
        },
        {
          "kind": "npu",
          "vendor": "ExampleSilicon",
          "name": "ExampleNPU",
          "apis": [],
          "dedicatedBytes": null
        }
      ],
      "power": {
        "battery": true,
        "batteryDesignMilliWh": 25000
      },
      "thermal": {
        "cooling": "fan",
        "stateSource": "android-thermal-headroom"
      }
    },
    "results": [
      {
        "modelId": "example-3b-instruct",
        "fileSha256": "6bc3e8ccea8ef082f0fcc23f91d3f8d7b5414e6d3e3af010641a8fb71729c810",
        "fileBytes": 2019377152,
        "quant": "Q4_K_M",
        "backend": "opencl",
        "settings": {
          "threads": 6,
          "gpuLayers": 99,
          "ctxTokens": 4096,
          "batchTokens": 512
        },
        "measuredAtMs": 1790604120000,
        "runs": {
          "planned": 5,
          "completed": 5,
          "discarded": 0
        },
        "conditions": {
          "charging": false,
          "batteryStartPermille": 870,
          "thermalStart": "nominal",
          "socStartMilliC": 33000,
          "screenOn": true
        },
        "prefill": [
          {
            "promptTokens": 512,
            "milliTokPerSec": {
              "p10": 204000,
              "p50": 212000,
              "p90": 216500
            },
            "ttftMicros": {
              "p10": 2410000,
              "p50": 2480000,
              "p90": 2590000
            }
          },
          {
            "promptTokens": 2048,
            "milliTokPerSec": {
              "p10": 181000,
              "p50": 188000,
              "p90": 192000
            },
            "ttftMicros": {
              "p10": 10700000,
              "p50": 10950000,
              "p90": 11400000
            }
          }
        ],
        "decode": [
          {
            "contextTokens": 512,
            "genTokens": 128,
            "milliTokPerSec": {
              "p10": 17600,
              "p50": 18400,
              "p90": 18900
            }
          },
          {
            "contextTokens": 3584,
            "genTokens": 128,
            "milliTokPerSec": {
              "p10": 14500,
              "p50": 15100,
              "p90": 15600
            }
          }
        ],
        "sustained": {
          "durationMs": 600000,
          "intervalMs": 30000,
          "steadyMilliTokPerSec": 11200,
          "throttleOnsetMs": 190000,
          "curve": [
            [
              0,
              18400,
              33000,
              6200
            ],
            [
              30000,
              18340,
              34500,
              6200
            ],
            [
              60000,
              18280,
              36000,
              6200
            ],
            [
              90000,
              18220,
              37500,
              6200
            ],
            [
              120000,
              18160,
              39000,
              6200
            ],
            [
              150000,
              18100,
              40500,
              6200
            ],
            [
              180000,
              18040,
              42000,
              6100
            ],
            [
              210000,
              14500,
              43500,
              6100
            ],
            [
              240000,
              13600,
              45000,
              6100
            ],
            [
              270000,
              12700,
              46500,
              6100
            ],
            [
              300000,
              11800,
              48000,
              6100
            ],
            [
              330000,
              11200,
              48000,
              6100
            ],
            [
              360000,
              11200,
              48000,
              6100
            ],
            [
              390000,
              11200,
              48000,
              6100
            ],
            [
              420000,
              11200,
              48000,
              6100
            ],
            [
              450000,
              11200,
              48000,
              6100
            ],
            [
              480000,
              11200,
              48000,
              6100
            ],
            [
              510000,
              11200,
              48000,
              6100
            ],
            [
              540000,
              11200,
              48000,
              6100
            ],
            [
              570000,
              11200,
              48000,
              6100
            ],
            [
              600000,
              11200,
              48000,
              6100
            ]
          ]
        },
        "memory": {
          "availableBeforeLoadBytes": 8300000000,
          "peakProcessBytes": 2600000000,
          "kvCacheBytes": 469762048
        },
        "power": {
          "method": "battery-current",
          "avgMilliW": 6100
        },
        "flags": [
          "thermal-throttled"
        ]
      },
      {
        "modelId": "example-3b-instruct",
        "fileSha256": "3c7d5df352a994683cff6ed77a911a87741ecaf096b4fed0a23366f6f4cf5bf3",
        "fileBytes": 3421225472,
        "quant": "Q8_0",
        "backend": "cpu",
        "settings": {
          "threads": 6,
          "gpuLayers": 0,
          "ctxTokens": 4096,
          "batchTokens": 512
        },
        "measuredAtMs": 1790606400000,
        "runs": {
          "planned": 5,
          "completed": 3,
          "discarded": 1
        },
        "conditions": {
          "charging": true,
          "batteryStartPermille": 640,
          "thermalStart": "light",
          "socStartMilliC": 38000,
          "screenOn": true
        },
        "prefill": [
          {
            "promptTokens": 512,
            "milliTokPerSec": {
              "p50": 61000
            },
            "ttftMicros": {
              "p50": 8600000
            }
          }
        ],
        "decode": [
          {
            "contextTokens": 512,
            "genTokens": 128,
            "milliTokPerSec": {
              "p50": 9800
            }
          }
        ],
        "sustained": {
          "durationMs": 300000,
          "intervalMs": 60000,
          "steadyMilliTokPerSec": 9100,
          "throttleOnsetMs": null,
          "curve": [
            [
              0,
              9800,
              38000,
              null
            ],
            [
              60000,
              9500,
              41000,
              null
            ],
            [
              120000,
              9300,
              43000,
              null
            ],
            [
              180000,
              9200,
              44000,
              null
            ],
            [
              240000,
              9100,
              44000,
              null
            ],
            [
              300000,
              9100,
              45000,
              null
            ]
          ]
        },
        "memory": {
          "availableBeforeLoadBytes": 8100000000,
          "peakProcessBytes": 3900000000,
          "kvCacheBytes": null
        },
        "power": {
          "method": "unavailable",
          "avgMilliW": null
        },
        "flags": [
          "charging",
          "low-runs"
        ]
      }
    ]
  },
  "presentation": {
    "issuedAtMs": 1790676000000,
    "expiresAtMs": 1790676600000,
    "challenge": "nvsK-QRFTzOSUx0XtUEgqKOWIsUY8j2dL4ikBHOh5pw"
  }
}
```

### 15.2 Container, exactly as sent (the `MANIFEST` frame payload / the `.asom-manifest.json` file)

```json
{"asomCapabilityManifest":1,"dsse":{"payload":"eyJib2R5Ijp7ImF1ZGllbmNlIjoib3duIiwiZGV2aWNlIjp7ImFjY2VsZXJhdG9ycyI6W3siYXBpcyI6WyJvcGVuY2wiLCJ2dWxrYW4iXSwiZGVkaWNhdGVkQnl0ZXMiOm51bGwsImtpbmQiOiJncHUiLCJuYW1lIjoiRXhhbXBsZUdQVSA4MDAiLCJ2ZW5kb3IiOiJFeGFtcGxlU2lsaWNvbiJ9LHsiYXBpcyI6W10sImRlZGljYXRlZEJ5dGVzIjpudWxsLCJraW5kIjoibnB1IiwibmFtZSI6IkV4YW1wbGVOUFUiLCJ2ZW5kb3IiOiJFeGFtcGxlU2lsaWNvbiJ9XSwiY2xhc3MiOiJwaG9uZSIsIm1lbW9yeSI6eyJ0b3RhbEJ5dGVzIjoxNzE3OTg2OTE4NH0sIm1vZGVsIjoiRXhhbXBsZSBQaG9uZSAxIiwib3MiOnsiZmFtaWx5IjoiYW5kcm9pZCIsInNlY3VyaXR5UGF0Y2giOiIyMDI2LTA5LTAxIiwidmVyc2lvbiI6IjE2In0sInBsYXRmb3JtSWRzIjp7ImJyYW5kIjoiZXhhbXBsZXZlbmRvciIsImRldmljZSI6ImV4MSIsIm1hbnVmYWN0dXJlciI6IkV4YW1wbGVWZW5kb3IiLCJtb2RlbCI6IkVYLTEiLCJwcm9kdWN0IjoiZXgxX2dsb2JhbCJ9LCJwb3dlciI6eyJiYXR0ZXJ5Ijp0cnVlLCJiYXR0ZXJ5RGVzaWduTWlsbGlXaCI6MjUwMDB9LCJzb2MiOnsiY3B1Ijp7ImNsdXN0ZXJzIjpbeyJjb3JlcyI6MiwibWF4S0h6Ijo0MzIwMDAwfSx7ImNvcmVzIjo2LCJtYXhLSHoiOjM1MzAwMDB9XSwibG9naWNhbENvcmVzIjo4fSwibmFtZSI6IkV4YW1wbGVTb0MgOCIsInZlbmRvciI6IkV4YW1wbGVTaWxpY29uIn0sInRoZXJtYWwiOnsiY29vbGluZyI6ImZhbiIsInN0YXRlU291cmNlIjoiYW5kcm9pZC10aGVybWFsLWhlYWRyb29tIn0sInZlbmRvciI6IkV4YW1wbGVWZW5kb3IifSwicHJvZHVjZXIiOnsiYXBwIjoiYXNvbS1hbmRyb2lkIiwiYXBwVmVyc2lvbiI6IjQuMC4wIiwiZW5naW5lIjp7ImJ1aWxkRmxhZ3MiOlsiR0dNTF9PUEVOQ0w9T04iLCJHR01MX1ZVTEtBTj1PTiJdLCJjb21taXQiOiIwMTIzYWJjZCIsIm5hbWUiOiJsbGFtYS5jcHAifSwiaGFybmVzcyI6eyJjb25mVmVyc2lvbiI6IjEuMC4wIiwiaWQiOiJhc29tLWJlbmNoIiwibWV0aG9kb2xvZ3lJZCI6ImFzb20tYmVuY2gtbWV0aG9kLzEiLCJ2ZXJzaW9uIjoiMS4wLjAifX0sInJlc3VsdHMiOlt7ImJhY2tlbmQiOiJvcGVuY2wiLCJjb25kaXRpb25zIjp7ImJhdHRlcnlTdGFydFBlcm1pbGxlIjo4NzAsImNoYXJnaW5nIjpmYWxzZSwic2NyZWVuT24iOnRydWUsInNvY1N0YXJ0TWlsbGlDIjozMzAwMCwidGhlcm1hbFN0YXJ0Ijoibm9taW5hbCJ9LCJkZWNvZGUiOlt7ImNvbnRleHRUb2tlbnMiOjUxMiwiZ2VuVG9rZW5zIjoxMjgsIm1pbGxpVG9rUGVyU2VjIjp7InAxMCI6MTc2MDAsInA1MCI6MTg0MDAsInA5MCI6MTg5MDB9fSx7ImNvbnRleHRUb2tlbnMiOjM1ODQsImdlblRva2VucyI6MTI4LCJtaWxsaVRva1BlclNlYyI6eyJwMTAiOjE0NTAwLCJwNTAiOjE1MTAwLCJwOTAiOjE1NjAwfX1dLCJmaWxlQnl0ZXMiOjIwMTkzNzcxNTIsImZpbGVTaGEyNTYiOiI2YmMzZThjY2VhOGVmMDgyZjBmY2MyM2Y5MWQzZjhkN2I1NDE0ZTZkM2UzYWYwMTA2NDFhOGZiNzE3MjljODEwIiwiZmxhZ3MiOlsidGhlcm1hbC10aHJvdHRsZWQiXSwibWVhc3VyZWRBdE1zIjoxNzkwNjA0MTIwMDAwLCJtZW1vcnkiOnsiYXZhaWxhYmxlQmVmb3JlTG9hZEJ5dGVzIjo4MzAwMDAwMDAwLCJrdkNhY2hlQnl0ZXMiOjQ2OTc2MjA0OCwicGVha1Byb2Nlc3NCeXRlcyI6MjYwMDAwMDAwMH0sIm1vZGVsSWQiOiJleGFtcGxlLTNiLWluc3RydWN0IiwicG93ZXIiOnsiYXZnTWlsbGlXIjo2MTAwLCJtZXRob2QiOiJiYXR0ZXJ5LWN1cnJlbnQifSwicHJlZmlsbCI6W3sibWlsbGlUb2tQZXJTZWMiOnsicDEwIjoyMDQwMDAsInA1MCI6MjEyMDAwLCJwOTAiOjIxNjUwMH0sInByb21wdFRva2VucyI6NTEyLCJ0dGZ0TWljcm9zIjp7InAxMCI6MjQxMDAwMCwicDUwIjoyNDgwMDAwLCJwOTAiOjI1OTAwMDB9fSx7Im1pbGxpVG9rUGVyU2VjIjp7InAxMCI6MTgxMDAwLCJwNTAiOjE4ODAwMCwicDkwIjoxOTIwMDB9LCJwcm9tcHRUb2tlbnMiOjIwNDgsInR0ZnRNaWNyb3MiOnsicDEwIjoxMDcwMDAwMCwicDUwIjoxMDk1MDAwMCwicDkwIjoxMTQwMDAwMH19XSwicXVhbnQiOiJRNF9LX00iLCJydW5zIjp7ImNvbXBsZXRlZCI6NSwiZGlzY2FyZGVkIjowLCJwbGFubmVkIjo1fSwic2V0dGluZ3MiOnsiYmF0Y2hUb2tlbnMiOjUxMiwiY3R4VG9rZW5zIjo0MDk2LCJncHVMYXllcnMiOjk5LCJ0aHJlYWRzIjo2fSwic3VzdGFpbmVkIjp7ImN1cnZlIjpbWzAsMTg0MDAsMzMwMDAsNjIwMF0sWzMwMDAwLDE4MzQwLDM0NTAwLDYyMDBdLFs2MDAwMCwxODI4MCwzNjAwMCw2MjAwXSxbOTAwMDAsMTgyMjAsMzc1MDAsNjIwMF0sWzEyMDAwMCwxODE2MCwzOTAwMCw2MjAwXSxbMTUwMDAwLDE4MTAwLDQwNTAwLDYyMDBdLFsxODAwMDAsMTgwNDAsNDIwMDAsNjEwMF0sWzIxMDAwMCwxNDUwMCw0MzUwMCw2MTAwXSxbMjQwMDAwLDEzNjAwLDQ1MDAwLDYxMDBdLFsyNzAwMDAsMTI3MDAsNDY1MDAsNjEwMF0sWzMwMDAwMCwxMTgwMCw0ODAwMCw2MTAwXSxbMzMwMDAwLDExMjAwLDQ4MDAwLDYxMDBdLFszNjAwMDAsMTEyMDAsNDgwMDAsNjEwMF0sWzM5MDAwMCwxMTIwMCw0ODAwMCw2MTAwXSxbNDIwMDAwLDExMjAwLDQ4MDAwLDYxMDBdLFs0NTAwMDAsMTEyMDAsNDgwMDAsNjEwMF0sWzQ4MDAwMCwxMTIwMCw0ODAwMCw2MTAwXSxbNTEwMDAwLDExMjAwLDQ4MDAwLDYxMDBdLFs1NDAwMDAsMTEyMDAsNDgwMDAsNjEwMF0sWzU3MDAwMCwxMTIwMCw0ODAwMCw2MTAwXSxbNjAwMDAwLDExMjAwLDQ4MDAwLDYxMDBdXSwiZHVyYXRpb25NcyI6NjAwMDAwLCJpbnRlcnZhbE1zIjozMDAwMCwic3RlYWR5TWlsbGlUb2tQZXJTZWMiOjExMjAwLCJ0aHJvdHRsZU9uc2V0TXMiOjE5MDAwMH19LHsiYmFja2VuZCI6ImNwdSIsImNvbmRpdGlvbnMiOnsiYmF0dGVyeVN0YXJ0UGVybWlsbGUiOjY0MCwiY2hhcmdpbmciOnRydWUsInNjcmVlbk9uIjp0cnVlLCJzb2NTdGFydE1pbGxpQyI6MzgwMDAsInRoZXJtYWxTdGFydCI6ImxpZ2h0In0sImRlY29kZSI6W3siY29udGV4dFRva2VucyI6NTEyLCJnZW5Ub2tlbnMiOjEyOCwibWlsbGlUb2tQZXJTZWMiOnsicDUwIjo5ODAwfX1dLCJmaWxlQnl0ZXMiOjM0MjEyMjU0NzIsImZpbGVTaGEyNTYiOiIzYzdkNWRmMzUyYTk5NDY4M2NmZjZlZDc3YTkxMWE4Nzc0MWVjYWYwOTZiNGZlZDBhMjMzNjZmNmY0Y2Y1YmYzIiwiZmxhZ3MiOlsiY2hhcmdpbmciLCJsb3ctcnVucyJdLCJtZWFzdXJlZEF0TXMiOjE3OTA2MDY0MDAwMDAsIm1lbW9yeSI6eyJhdmFpbGFibGVCZWZvcmVMb2FkQnl0ZXMiOjgxMDAwMDAwMDAsImt2Q2FjaGVCeXRlcyI6bnVsbCwicGVha1Byb2Nlc3NCeXRlcyI6MzkwMDAwMDAwMH0sIm1vZGVsSWQiOiJleGFtcGxlLTNiLWluc3RydWN0IiwicG93ZXIiOnsiYXZnTWlsbGlXIjpudWxsLCJtZXRob2QiOiJ1bmF2YWlsYWJsZSJ9LCJwcmVmaWxsIjpbeyJtaWxsaVRva1BlclNlYyI6eyJwNTAiOjYxMDAwfSwicHJvbXB0VG9rZW5zIjo1MTIsInR0ZnRNaWNyb3MiOnsicDUwIjo4NjAwMDAwfX1dLCJxdWFudCI6IlE4XzAiLCJydW5zIjp7ImNvbXBsZXRlZCI6MywiZGlzY2FyZGVkIjoxLCJwbGFubmVkIjo1fSwic2V0dGluZ3MiOnsiYmF0Y2hUb2tlbnMiOjUxMiwiY3R4VG9rZW5zIjo0MDk2LCJncHVMYXllcnMiOjAsInRocmVhZHMiOjZ9LCJzdXN0YWluZWQiOnsiY3VydmUiOltbMCw5ODAwLDM4MDAwLG51bGxdLFs2MDAwMCw5NTAwLDQxMDAwLG51bGxdLFsxMjAwMDAsOTMwMCw0MzAwMCxudWxsXSxbMTgwMDAwLDkyMDAsNDQwMDAsbnVsbF0sWzI0MDAwMCw5MTAwLDQ0MDAwLG51bGxdLFszMDAwMDAsOTEwMCw0NTAwMCxudWxsXV0sImR1cmF0aW9uTXMiOjMwMDAwMCwiaW50ZXJ2YWxNcyI6NjAwMDAsInN0ZWFkeU1pbGxpVG9rUGVyU2VjIjo5MTAwLCJ0aHJvdHRsZU9uc2V0TXMiOm51bGx9fV0sInNlcSI6MTcsInN1YmplY3QiOnsia2V5QWxnIjoiRVMyNTYiLCJrZXlTdG9yYWdlIjoic3Ryb25nYm94Iiwibm9kZUlkIjoidmF3OTNoYjh5QlpUWDJMZWJZZ3pyaTFwZk9uQmxSSUlMb0JMaXlLX2VONCJ9fSwicHJlc2VudGF0aW9uIjp7ImNoYWxsZW5nZSI6Im52c0stUVJGVHpPU1V4MFh0VUVncUtPV0lzVVk4ajJkTDRpa0JIT2g1cHciLCJleHBpcmVzQXRNcyI6MTc5MDY3NjYwMDAwMCwiaXNzdWVkQXRNcyI6MTc5MDY3NjAwMDAwMH0sInNjaGVtYSI6ImFzb20ubWFuaWZlc3QvMSIsInNjaGVtYU1pbm9yIjowfQ==","payloadType":"application/vnd.asom.manifest.v1+json","signatures":[{"keyid":"vaw93hb8yBZTX2LebYgzri1pfOnBlRIILoBLiyK_eN4","sig":"1cu+UasO/G4TnL3axnFdU8EeNLZ8fXotsF6eSa4iE1g6R4O4s4Pad3n+L8oYklCzkVabTVR5fHE4qUn5vhm5qg=="}]},"signer":{"spki":"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE4qjYZv8IPpVldevJ/GAe+oYlG/YpAPoqkmMxjeyqofErSay/5ls13fJbnWLTZ7Oj51JprjQj628DEDqwZM1yTw=="}}
```

Independent checks run in this session (`crosscheck.out`): the DER form of this signature over `PAE(type, P)` verifies with OpenSSL 3.0.13 ("Verified OK"); `cryptography` 50.0.1 verifies it and reproduces the same RFC 6979 nonce; Java 21's `SHA256withECDSAinP1363Format` over an independently built PAE verifies it.

### 15.3 Public derivative of the same report (unsigned; `example-public.jcs.json`, 1,496 bytes)

```json
{
  "schema": "asom.bench-public/1",
  "device": {
    "class": "phone",
    "vendor": "ExampleVendor",
    "model": "Example Phone 1",
    "socName": "ExampleSoC 8",
    "ramClassGiB": 16,
    "osFamily": "android",
    "osMajor": 16,
    "cooling": "fan"
  },
  "harness": {
    "version": "1.0.0",
    "methodologyId": "asom-bench-method/1",
    "confVersion": "1.0.0"
  },
  "engine": {
    "name": "llama.cpp",
    "commit": "0123abcd"
  },
  "results": [
    {
      "modelId": "example-3b-instruct",
      "quant": "Q4_K_M",
      "fileSha256": "6bc3e8ccea8ef082f0fcc23f91d3f8d7b5414e6d3e3af010641a8fb71729c810",
      "backend": "opencl",
      "measuredMonth": "2026-09",
      "runsCompleted": 5,
      "charging": false,
      "prefillPromptTokens": 512,
      "prefillMilliTokPerSec": 210000,
      "ttftMillis": 2500,
      "decodeContextTokens": 512,
      "decodeMilliTokPerSec": 18000,
      "steadyMilliTokPerSec": 11000,
      "throttleOnsetSec": 190,
      "curve": [
        [
          0,
          18000
        ],
        [
          60,
          18000
        ],
        [
          120,
          18000
        ],
        [
          150,
          18000
        ],
        [
          210,
          15000
        ],
        [
          270,
          13000
        ],
        [
          330,
          11000
        ],
        [
          390,
          11000
        ],
        [
          450,
          11000
        ],
        [
          480,
          11000
        ],
        [
          540,
          11000
        ],
        [
          600,
          11000
        ]
      ],
      "peakProcessMB": 2600,
      "powerMethod": "battery-current",
      "powerMilliW": 6100
    },
    {
      "modelId": "example-3b-instruct",
      "quant": "Q8_0",
      "fileSha256": "3c7d5df352a994683cff6ed77a911a87741ecaf096b4fed0a23366f6f4cf5bf3",
      "backend": "cpu",
      "measuredMonth": "2026-09",
      "runsCompleted": 3,
      "charging": true,
      "prefillPromptTokens": 512,
      "prefillMilliTokPerSec": 61000,
      "ttftMillis": 8600,
      "decodeContextTokens": 512,
      "decodeMilliTokPerSec": 9800,
      "steadyMilliTokPerSec": 9100,
      "throttleOnsetSec": null,
      "curve": [
        [
          0,
          9800
        ],
        [
          60,
          9500
        ],
        [
          120,
          9300
        ],
        [
          180,
          9200
        ],
        [
          240,
          9100
        ],
        [
          300,
          9100
        ]
      ],
      "peakProcessMB": 3900,
      "powerMethod": "unavailable",
      "powerMilliW": null
    }
  ]
}
```

---

## 16. Verification test vectors to ship

### 16.1 Files and envelope

Vectors use the `platforms.md` §8.3 envelope. IDs `M02-1xx`, `M03-1xx`, `M05-1xx` avoid the seed IDs already in `platforms.md` §8.9; `M06` (public derivation) is a new family, and `M07` (evidence) and `M08` (claim tracker) are specified below but not generated, so `platforms.md`'s family pattern needs `M0[1-8]` (coordination item). Every vector here is `status: illustrative` until the owner signs off the schema. The generator is deterministic (re-run produced byte-identical files, checked).

### 16.2 Generated vectors (37 verify vectors + 2 render + 2 public)

| ID | Case | Expected | sig valid |
|---|---|---|---|
| M02-101 | Challenge-bound presentation from a pinned mesh peer; accept, tier A1 (StrongBox claimed, no evidence). | accept: pin=PINNED, tier=A1, seq=17 | true |
| M02-102 | Unsolicited file-export form (audience file, challenge null, 30-day TTL) verified by a first-time subscriber (TOFU_NEW). | accept: pin=TOFU_NEW, tier=A1, seq=17 | true |
| M02-103 | Same file verified by a subscriber that already stored this key under its keyid (TOFU_MATCH). | accept: pin=TOFU_MATCH, tier=A1, seq=17 | true |
| M02-104 | Boundary: nowMs == expiresAtMs - 1 accepts. | accept: pin=PINNED, tier=A1, seq=17 | true |
| M02-105 | High-S form of the same signature: accept (ECDSA malleability); bodyDigest is unchanged, so signature bytes are never an identifier. | accept: pin=PINNED, tier=A1, seq=17 | true |
| M02-106 | Envelope using URL-safe unpadded base64 for payload and sig: DSSE requires verifiers to accept either alphabet. | accept: pin=PINNED, tier=A1, seq=17 | true |
| M02-107 | schemaMinor 1 with one unknown additive field: accept; unknownFields = 1; the renderer must say one newer item is not shown. | accept: pin=PINNED, tier=A1, seq=17, unknownFields=1 | true |
| M02-108 | Rollback store holds seq 16 for this node/audience: seq 17 accepts. | accept: pin=PINNED, tier=A1, seq=17 | true |
| M02-109 | Rollback store holds seq 17 with the SAME body digest (a re-presentation with a new challenge): accept. | accept: pin=PINNED, tier=A1, seq=17 | true |
| M03-101 | One integer in the payload changed after signing (decode p50 18400 -> 28400). | reject `SIGNATURE_INVALID` | false |
| M03-102 | DER-encoded signature where 64-octet raw r//s is required. | reject `SIGNATURE_ENCODING` | false |
| M03-103 | Valid signature by a different key (key 2) presented to a subscriber that pinned key 1. | reject `KEY_NOT_PINNED` | false |
| M03-104 | Payload validly signed but NOT in canonical (JCS) form (pretty-printed). Rejected to exclude parser-differential ambiguity. | reject `NON_CANONICAL` | true |
| M03-105 | Validly signed payload containing a duplicate member name (audience). | reject `DUPLICATE_KEY` | true |
| M03-106 | Validly signed payload containing a non-integer number. | reject `NON_INTEGER_NUMBER` | true |
| M03-107 | nowMs == expiresAtMs: expired. | reject `EXPIRED` | true |
| M03-108 | issuedAtMs more than 5 minutes in the future of the verifier clock. | reject `NOT_YET_VALID` | true |
| M03-109 | Challenge differs from the one this requester sent (replayed presentation). | reject `NONCE_MISMATCH` | true |
| M03-110 | Requester expected a challenge but the presentation carries none (an unsolicited copy offered as a fresh answer). | reject `NONCE_MISMATCH` | true |
| M03-111 | Rollback store holds seq 18: seq 17 is a rollback. | reject `ROLLBACK` | true |
| M03-112 | Rollback store holds seq 17 with a DIFFERENT body digest: the signer issued two bodies under one seq. | reject `EQUIVOCATION` | true |
| M03-113 | Envelope claims another asom payload type (key-rollover). PAE binds the type, and the verifier refuses non-manifest types before verifying. | reject `PAYLOAD_TYPE_UNSUPPORTED` | false |
| M03-114 | A different payload type correctly signed by the pinned key cannot be replayed as a manifest (cross-type confusion). | reject `PAYLOAD_TYPE_UNSUPPORTED` | true |
| M03-115 | Payload names another node as its subject but is signed by key 1. | reject `SUBJECT_KEY_MISMATCH` | true |
| M03-116 | Challenge-bound presentation with a TTL over 10 minutes. | reject `TTL_INVALID` | true |
| M03-117 | Envelope with two signature entries (asom profile requires exactly one). | reject `SIGNATURE_COUNT` | true |
| M03-118 | Unknown major version in the payload type. | reject `SCHEMA_MAJOR_UNKNOWN` | false |
| M03-119 | Unknown major version inside the signed payload. | reject `SCHEMA_MAJOR_UNKNOWN` | true |
| M03-120 | Internally inconsistent claim: steady-state throughput above every point of its own curve. | reject `INCONSISTENT` | true |
| M03-121 | Internally inconsistent claim: time to first token shorter than the prompt-processing time implied by its own prefill rate. | reject `INCONSISTENT` | true |
| M03-122 | TOFU subscriber already stored a different key under this keyid; the file now presents key 2 with the same keyid hint. | reject `KEY_CHANGED` | false |
| M03-123 | Trailing data after the container JSON. | reject `TRAILING_DATA` | true |
| M03-124 | requiredTier A2 but no platform evidence: tier A1 is insufficient. | reject `TIER_INSUFFICIENT` | true |
| M03-125 | Signature base64 whose final character carries non-zero unused bits (non-canonical base64); rejected before any signature check. | reject `ENCODING` | false |
| M03-126 | Bidi override character (U+202E) in a display string: schema rejects it so plain text cannot be visually spoofed. | reject `SCHEMA_INVALID` | true |
| M03-127 | TOFU subscriber with no stored key and no signer.spki in the container. | reject `KEY_NOT_PINNED` | true |
| M03-128 | Validly signed payload nested deeper than 16 levels (checked by the strict parser before schema validation). | reject `MALFORMED_JSON` | true |
| M05-101 | Plain text rendered from the verified payload of M02-101 plus the verification result (PINNED, A1, challenge matched). | exact bytes (see file) | n/a |
| M05-102 | Rendering of M02-107: identical to M05-101 except the trailing newer-items line. | exact bytes (see file) | n/a |
| M06-101 | Public derivative of the verified M02-101 payload: allow-listed fields only, 2-significant-digit quantisation, month-granular date, RAM class. | exact bytes (see file) | n/a |
| M06-102 | q2 quantisation table (law: q2(q2(x)) == q2(x); values below 100 unchanged). | exact bytes (see file) | n/a |

`sig valid` is the independent JCA result under test key 1 over the PAE built from the document's own `payloadType` (from `crosscheck.out`).

### 16.3 Vectors still to create (cannot be fabricated in a design session)

| Family | Cases | Source of truth |
|---|---|---|
| **M07-A Android evidence** | Accept: a real TEE chain and a real StrongBox chain from the owner's devices, one chain rooted at the RSA root and one at the P-384 "Key Attestation CA 1" (post-2026-02-01) [MV1]. Reject: leaf SPKI ≠ signer (`EV_KEY_MISMATCH`), extension appended in a non-leaf certificate (`EV_EXTENSION_POSITION`), `Software` security level (`EV_SOFTWARE`), wrong challenge, `keyStorage` claim `strongbox` with a TEE chain, a chain whose intermediate serial is in a status snapshot (`EV_REVOKED`), unknown root, broken signature link | Chains captured on the owner's RedMagic and one other device (**NEEDS-DEVICE-VALIDATION**); Google's own sample chains from `android/keyattestation` test data where the licence allows |
| **M07-B App Attest** (only if OD-M3 approves) | Accept attestation + assertion; reject wrong RP ID, counter reuse, binding to another SPKI, development `aaguid` in a production policy | Captured on owner hardware |
| **M08 claim tracker** | Every state transition of §11.5 with exact integer outputs; the `hot` branch; the peer-wide 400‰ discount; the 5000‰ cap; lower median for even n | Hand-written oracles independent of the implementation (the audit's lesson that oracles must not be the implementation's own key functions) |
| **M04 derive** | Owned by `benchmark.md` | — |

### 16.4 Property laws (in the style of the v1 routing laws)

- **LV-1** For any valid payload `o` and key `d`: `verify(sign(o, d))` accepts under PINNED(d) at any `now` in `[issuedAt − 5 min, expiresAt)`.
- **LV-2** Flipping any single bit of the decoded payload yields `SIGNATURE_INVALID` (or an earlier reject); never an accept.
- **LV-3** Any re-serialisation of a valid payload that is not byte-identical (whitespace, key order, escapes such as `\/` or `é`) yields `NON_CANONICAL` even when validly signed.
- **LV-4** `verify` never reads `dsse.payload` after step 8 (structural test: the verified bytes are the only input to steps 9–19).
- **LV-5** Accepting any vector commits at most a rollback/TOFU update; rejecting commits nothing.
- **LV-6** `bodyDigest` is invariant under a new presentation of the same body (vectors M02-101, -105, -109).
- **LM-1..LM-6** as stated in §4.6, §12.2 and §14.1.

---

## 17. Proposed contract deltas (all additive; every item needs owner sign-off)

| ID | Delta | Surface | Notes |
|---|---|---|---|
| **MC-1** | Payload of `MANIFEST_REQ` (0x22) `{"challenge": b64url 32 bytes}` and `MANIFEST` (0x23) = the §6.3 container; mesh error `MANIFEST_UNAVAILABLE` | `asom-mesh/1` (`trust.md` C-1) | Frames already proposed by `trust.md`; this fixes their content |
| **MC-2** | Payload type `application/vnd.asom.manifest.v1+json`, schema `asom.manifest/1`, container v1, file extensions `.asom-manifest.json` / `.asom-report.txt`, renderer `asom.manifest-text/1` | Public file format consumed by third parties | A public artefact other apps code against: treat as contract, with the M-family vectors as its executable form |
| **MC-3** | Live-state field `manifest: {"seq": int, "bodyDigest": b64url}` in `STATE` | `router.md` W07 | Replaces push with pull-on-hint |
| **MC-4** *(optional; recommend defer)* | `GET /admin/manifest[?challenge=]` on 127.0.0.1, bearer-gated like `/admin/*`; `Accept: text/plain` returns `asom.manifest-text/1` of the same payload | Frozen §5.2 | Only if a consuming app needs it (OD-M5) |
| **MC-5** | Catalogue additive block `attestation.android: {roots[], statusSnapshot: {fetchedAtMs, entries{}}, asomSigningCerts[], knownCustomOsBootKeys[]}` | Owner's catalogue repo (brief §6, roadmap §9) | Delivered over the existing catalogue egress (Invariant 3b). Needs an owner CI job mirroring Google's status list |
| **MC-6** | Public derivative schema `asom.bench-public/1` | v2 P7 payload | The roadmap planned the upload; this is its schema |
| — | **Not proposed:** any new `/v1/*` endpoint, any response header, any new ledger egress class, any discovery-provider column, any AIDL method | — | Manifest traffic rides the `lan` class of `trust.md` C-2 |

---

## 18. Owner decisions

| ID | Question | Options | Recommendation | Why only the owner |
|---|---|---|---|---|
| **OD-M1** | Invariant 1 says "no background or silent transmission of usage **or benchmark data** — ever". A node answering a paired peer's `MANIFEST_REQ` without a tap each time sends benchmark data. Is that permitted? | (a) Covered by `trust.md` OD-1's combined text (Invariant 1 clarification): only to PAIRED peers the user granted `manifest`, enumerated fields, "last report sent" viewer, ledgered; plus, at pairing, the consent sheet shows the current report that will be shared. (b) Only on an explicit per-share tap (view-first each time); peers use unsolicited presentations (≤ 30 days, no challenge); the design supports this unchanged. (c) Escalate as a third amendment | **(a)**, with (b) as the fallback if the owner reads (a) as a third amendment | Only the owner can rule whether this is inside the v4 amendment or a third one (roadmap §13) |
| **OD-M2** | Is a user-initiated, view-first **file export** of the report (share sheet / SAF / CLI file) within Invariant 1's principle, like the v1 ledger export, or a third amendment? | (a) Within the principle (no network egress by asom; shows the exact payload first); (b) third amendment; (c) no export: display and mesh only (then "any app" can subscribe only via S4 or the mesh) | **(a)**; full signed report and anonymous summary both offered, with the fingerprint warning | Roadmap §13 counts exports; the owner decides whether this one counts |
| **OD-M3** | Use Apple App Attest for iOS A2? `attestKey` contacts Apple's server [MV9] | (a) No; iOS stays A1 (Secure Enclave key, self-reported). (b) Yes, one-time per install, as a new ledgered egress class "platform-attest" — a third amendment | **(a)** | New egress class to a vendor; a third amendment by the roadmap's rule |
| **OD-M4** | Android attestation revocation status | (a) No check; every A2 says "revocation unchecked". (b) Mirror Google's status list into the catalogue (MC-5; owner CI job). (c) Devices fetch it from googleapis.com directly (new egress class; third amendment) | **(b)**, with (a) until it exists | Catalogue schema and repo are the owner's; (c) would be a third amendment |
| **OD-M5** | Local on-device subscribers: add `GET /admin/manifest`? | (a) Defer until a consumer needs it. (b) Add now (bearer-gated, localhost). (c) Discovery-provider column (rejected: unauthenticated identifier) | **(a)** | New endpoint in the frozen §5 |
| **OD-M6** | Requester-run probes (§11.6) | (a) User-initiated only. (b) Automatic background probes, ledgered. (c) None | **(a)** | (b) is automatic network activity with synthetic content: arguably within Invariant 1's letter, clearly against its spirit |
| **OD-M7** | Ship vendor roots (Google; Apple only if OD-M3 = b) as attestation anchors | (a) Ship; A2 informational in own-device meshes; a user setting "Ignore vendor attestation". (b) Do not implement A2 | **(a)** | Makes a vendor PKI an anchor of a displayed claim: a sovereignty trade-off |

Constraints respected rather than escalated: **no KMP** (the Swift side ports the small verifier/renderer surface against these vectors, `platforms.md` Option 1); **no starting later versions**: every build item in §21 waits for `platforms.md` OD4.

---

## 19. Invariant impacts

| Invariant | Status | Detail |
|---|---|---|
| 1 No automatic egress | needs-amendment (OD-M1, OD-M2) | Serving manifests to peers and exporting files are benchmark data leaving the device. Designed as view-first and scope-gated; whether that is inside Amendment 2's companion text or a third amendment is the owner's call. The public upload is Amendment 1 as written, with the §12 builder |
| 2 Bind 127.0.0.1 only | honored | The manifest adds no listener. It rides `trust.md`'s peer listener (Amendment 2). MC-4, if ever approved, is on the existing localhost server |
| 3 Egress classes | honored | No new class: mesh traffic is `lan` (`trust.md` C-2); revocation via catalogue (3b). The two options that would add one (App Attest, direct status fetch) are recommended against (OD-M3, OD-M4) |
| 4 BYOK keys | honored | No key or key-presence field exists (LM-1) |
| 5 Pairing identity | honored | A manifest can never create or raise a pairing; in MESH context the key is the session's pinned peer key |
| 6 Red/green never carry meaning | honored | Tiers and pin states are words plus shapes; violet/cyan only |
| 7 Placeholder UI | honored | Plain text is a contract (M05); visual design stays Hyle's |
| 8 No GMS | honored | Key Attestation verified offline against bundled roots; Play Integrity rejected |
| 9 One record | extended | The signed payload bytes are the one record for text, digest, router input and public derivative (LM-3..LM-6) |
| Roadmap §13 "exactly two amendments" | third-amendment risk surfaced | OD-M1, OD-M2 (possible), OD-M3 option (b), OD-M4 option (c) |
| CLAUDE.md "no KMP", "do not start later versions" | honored | Swift port against vectors; build items gated on OD4 |

---

## 20. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| Users or third-party apps read "signed" as "true" or "verified device" | critical | Every rendering carries the viewer-computed verification block and the "Not proven" line (LM-6); `TOFU_NEW` prompts a fingerprint comparison; A1 = A0 in all logic |
| Android A2 accepted from a leaked factory attestation key because revocation is unchecked | high | OD-M4 (b); display `revocation: unchecked`; RKP chains are not affected by leaks per Google [MV2]; never gate own-device decisions on A2 |
| Parser or canonicaliser divergence between Kotlin and Swift (accepting tampered or rejecting valid reports) | high | Canonical-bytes requirement; M01–M03 vectors on every implementation; an independent checker (`platforms.md` `xcheck.py`) |
| Manifest poisoning attracts traffic to a hostile peer | high | §11.5 tracker; bounded exploration; data classes independent of claims |
| Stable key fingerprint leaks through exported files | medium | Export warning; anonymous summary offered; not on any unauthenticated surface (§13.4) |
| Overlap with `benchmark.md` produces two measurement schemas | medium | §1 coordination item; resolve before any freeze |
| StrongBox signing latency makes `MANIFEST_REQ` slow [MA4] | medium | Rate limits; cached body; presentation signing only |
| NIK generated without attestation parameters, making Android A2 permanently impossible for that node | medium | §7.2 coordination with `trust.md` before any NIK code lands |
| Google root rotation (P-384 from 2026-02-01) breaks verifiers lacking P-384 [MV1] | medium | Bundle both roots; M07-A vectors for both |
| Clock skew causes spurious `EXPIRED`/`NOT_YET_VALID` on file imports | low | 5 min future skew; human viewers may show time-rejected reports with the code |
| Rollback store loss lets an old file be re-accepted | low | Persisted store; it is a third-party-replay defence only, as stated |

---

## 21. What could be built now (pure JVM, no contract change, no Android; only with `platforms.md` OD4 authorisation)

1. `:core:mesh` JCS integer-profile serialiser and strict parser (§5), passing `platforms.md` M01 and this section's M03-104/105/106/123/125/126.
2. DSSE PAE, base64 profile, ES256 raw↔DER codec with low-S normalisation, and `verifyManifest` with all typed rejects (§8), passing M02/M03 here.
3. `ManifestBody` DTOs, the strict and tolerant schema validators (Appendix A), the audience projection (§3.2), `consistency()` (§8.4).
4. `PublicDerivative.from` and `q2` with LM-2 (§12), passing M06.
5. The `asom.manifest-text/1` renderer (§14), passing M05 byte-exactly on JDK 17 and 21.
6. `ClaimTracker` as a pure function with hand-written M08 oracles (§11.5).
7. A DER reader and `evaluateAndroid` (§9.3) against Google's published sample chains, pending device-captured chains.
8. Porting `gen_manifest_vectors.py` into `conformance/tools/` as the independent (non-JVM) checker.

---

## 22. What this section does NOT guarantee (summary)

- A valid signature proves **integrity since signing and which key signed**. It never proves that the measurements are honest, typical, recent, or from the device model named.
- A pin proves the key is the one a human paired or compared. It does not prove what software used the key.
- A2 on Android proves hardware key residency and (with verified boot) an owner-signed binary **at key generation**; nothing about later compromise, and nothing on iOS, macOS or Linux in the first release.
- The challenge proves the signing key was used after the request, not that the measurements are new.
- Rollback protection defends against third parties replaying old documents, not against a lying signer.
- The claim tracker bounds how far a lying peer can skew placement; it does not detect a peer that performs as claimed but mishandles data.
- The public derivative is unverifiable by design and only "anonymous" in the sense of carrying no identifier.
- Test vectors prove agreement on enumerated cases, not correctness everywhere; the Android evidence vectors do not exist yet.

---

## 23. Verified facts and assumptions

### 23.1 Verified this session

| ID | Fact | Source | Confidence |
|---|---|---|---|
| MV1 | Google publishes an RSA-2048 attestation root (`SERIALNUMBER=f92009e853b6b045`, 2022-03-20 to 2042-03-15) and a new ECDSA P-384 root ("Key Attestation CA 1", 2025-07-17 to 2035-07-15) that "will begin signing attestation certificate chains on February 1, 2026". Google recommends trusting chains to the RSA root regardless of certificate validity periods | developer.android.com/privacy-and-security/security-key-attestation | high |
| MV2 | The revocation status list is at `https://android.googleapis.com/attestation/status` (JSON; entries keyed by lowercase hex serial; status `REVOKED`/`SUSPENDED`; reasons incl. `KEY_COMPROMISE`; refresh per `Cache-Control`); "it's critical that the status of each certificate … be checked". Leaked keys are revoked "typically within several days"; leaks "don't apply to attestation keys certified by … RKP"; devices launching with Android 16 support only RKP | same page | high |
| MV3 | "Only the first occurrence of the extension in the chain can be trusted"; later occurrences may be attacker-added | same page | high |
| MV4 | Extension OID 1.3.6.1.4.1.11129.2.1.17; attestation versions 1–4 (Keymaster) and 100–500 (KeyMint 1.0–5.0); SecurityLevel Software(0)/TrustedEnvironment(1)/StrongBox(2); RootOfTrust {verifiedBootKey, deviceLocked, verifiedBootState, verifiedBootHash}; VerifiedBootState Verified/SelfSigned/Unverified/Failed; ID tags attestationIdBrand 710 … attestationIdModel 717; attestationApplicationId tag 709 | source.android.com/docs/security/features/keystore/attestation | high |
| MV5 | `setDevicePropertiesAttestationIncluded` (API 31) adds brand, device, manufacturer, model, product to the attestation; `generateKey` throws `ProviderException` if unsupported; the reference lists no permission requirement. `setAttestationChallenge` (API 24): up to 128 bytes. The same reference documents ML-DSA key handling | developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder (fetched HTML) | high (stated); medium (absence of a permission) |
| MV6 | StrongBox supports RSA 2048, AES 128/256, ECDSA/ECDH P-256, HMAC-SHA256, 3DES; it is "slower, more resource-constrained, and supports fewer concurrent operations" | developer.android.com/privacy-and-security/keystore | high |
| MV7 | KeyMint v2 (Android 13) added Curve25519 signing and key agreement | Search summary of source.android.com keystore docs (not fetched directly) | medium |
| MV8 | CryptoKit `SecureEnclave` offers `P256`, `MLKEM768`, `MLKEM1024`, `MLDSA65`, `MLDSA87`; no Curve25519 | developer.apple.com CryptoKit `SecureEnclave` (documentation JSON) | high |
| MV9 | App Attest: `attestKey` "accesses a remote Apple server" (`serverUnavailable` on connectivity failure); the key lives in the Secure Enclave; keys "don't survive app reinstallation, device migration, or restoration of a device from a backup" | developer.apple.com "Establishing your app's integrity" and `attestKey` reference | high |
| MV10 | App Attest attestation format (`apple-appattest`, x5c credCert + intermediate, receipt) and server validation steps: nonce in OID 1.2.840.113635.100.8.2, RP ID = SHA-256 of App ID, counter 0, aaguid `appattest`/`appattestdevelop`, credentialId, validation-category and bundle-version extensions; assertion verification with counter monotonicity; receipt used in a server-to-server fraud-metric call | developer.apple.com "Validating apps that connect to your server" | high |
| MV11 | `isSupported` is documented as false on Mac (including Catalyst and iOS apps on Apple silicon), while the validation page includes a macOS-only `aclBlob` step: the documentation is internally inconsistent | developer.apple.com `isSupported` and validation pages | high (that both statements exist) |
| MV12 | RFC 8785: control characters as lowercase `\uhhhh` except `\b \t \n \f \r`; only `\` and `"` otherwise escaped; `/` not escaped; properties sorted by UTF-16 code units; no whitespace; duplicate names and lone surrogates must be rejected; §5 requires parse/check/verify for signature use | rfc-editor.org/rfc/rfc8785 | high |
| MV13 | DSSE: PAE definition; "Either standard or URL-safe base64 encodings are allowed … verifiers MUST accept either"; KEYID is an unauthenticated hint that "MUST NOT be used for security decisions"; implementations "MUST NOT re-parse the envelope after verification"; the ECDSA test vector uses raw r‖s | github.com/secure-systems-lab/dsse protocol.md | high |
| MV14 | Intel PTT EK certificates were provisioned online (ekop.intel.com) before 11th-gen Core and come from an on-die CA from 11th gen; AMD fTPM EK certificates are fetched from ftpm.amd.com; `tpm2_getekcertificate` reads NV indices or downloads from the manufacturer | Intel community threads; Microsoft Q&A; tpm2-tools man page | medium |
| MV15 | RKP gives each application a different attestation key, and the keys rotate regularly | Android Developers Blog 2022-03 "Upgrading Android Attestation: Remote Provisioning" (via search summary) | medium |
| MV16 | `android/keyattestation` is an Apache-2.0 Kotlin verifier; its `roots.json` mirrors `https://android.googleapis.com/attestation/root`; the revocation list can be supplied by the caller | github.com/android/keyattestation README | medium |
| MV17 | Computed: the RFC 6979 implementation reproduces RFC 6979 A.2.5 (P-256/SHA-256, "sample") and matches `cryptography` 50.0.1's deterministic ECDSA; the example signature verifies under OpenSSL 3.0.13 and Java 21 JCA; all 9 accept vectors verify under `cryptography`; the generator is byte-deterministic | `manifest-vectors/crosscheck.out` | high (for what was run) |

Relied on from siblings: RFC 7518 ES256 = 64-octet r‖s (`platforms.md` [V24]); TPM PC Client mandates P-256 (`trust.md` [V12], medium); SteamOS TPM module (`trust.md` [V13], low).

### 23.2 Assumptions (not verified)

| ID | Assumption | Load-bearing? |
|---|---|---|
| MA1 | `attestationApplicationId` is in the software-enforced list (supplied by the Android keystore service, not the TEE), so it is only as trustworthy as the OS | Yes: it is why §9.3 ties app identity to `boot = verified` |
| MA2 | A CryptoKit Secure Enclave key has no public third-party attestation on non-managed devices other than App Attest | Yes for iOS = A1 |
| MA3 | `generateAssertion` runs on-device without contacting Apple | Only if OD-M3 (b) |
| MA4 | StrongBox P-256 signing takes on the order of 100 ms | Sizes the rate limit |
| MA5 | Android Keystore keys are deleted on uninstall or app-data clear | Rotation table |
| MA6 | Play Integrity requires Google Play services | Rejection reason only (it is excluded either way) |
| MA7 | Generating an attested key needs no app-initiated network access; on a device with an empty RKP pool it may fail, and the producer falls back to A1 | Yes for Invariant 3 on Android A2; device test |
| MA8 | Power data: Android battery current is readable by apps; iOS exposes only coarse battery level; Linux RAPL energy counters need elevated privileges; macOS needs privileged APIs | `benchmark.md` owns; nullable fields cover it |
| MA10 | The JDK has no public ASN.1/DER parsing API | Library choice in §9.3 |
| MA11 | Whether `android/keyattestation` runs on a plain JVM (desktop node) | Spike |
| MA12 | Custom OSes (e.g. GrapheneOS) publish verified-boot key hashes usable in `knownCustomOsBootKeys` | Policy option only |
| MA13 | App Attest does not definitively detect a jailbroken device | Honesty note only |
| MA14 | An Android attestation chain is 3–8 KB base64 | Size budget |

---

## Appendix A — `asom.manifest/1` payload JSON Schema (draft 2020-12, strict form)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "asom:schema/asom.manifest/1",
  "title": "asom capability manifest payload, major version 1 (DESIGN PROPOSAL, not frozen)",
  "description": "The signed payload inside a DSSE envelope with payloadType application/vnd.asom.manifest.v1+json. All numbers are integers in the unit named by the field suffix (C1). This is the STRICT (producer/conformance) form: additionalProperties false everywhere. Consumers apply the tolerant rule in manifest.md 4.3 (unknown properties are ignored, never rendered as facts).",
  "type": "object",
  "required": ["schema", "schemaMinor", "body", "presentation"],
  "additionalProperties": false,
  "properties": {
    "schema": { "const": "asom.manifest/1" },
    "schemaMinor": { "type": "integer", "minimum": 0, "maximum": 1000 },
    "body": { "$ref": "#/$defs/body" },
    "presentation": { "$ref": "#/$defs/presentation" }
  },
  "$defs": {
    "u53": { "type": "integer", "minimum": 0, "maximum": 9007199254740991 },
    "i53": { "type": "integer", "minimum": -9007199254740991, "maximum": 9007199254740991 },
    "epochMs": { "type": "integer", "minimum": 1577836800000, "maximum": 4102444800000 },
    "text": {
      "type": "string", "minLength": 1, "maxLength": 96,
      "pattern": "^[^\\u0000-\\u001F\\u007F-\\u009F\\u061C\\u200E\\u200F\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]+$"
    },
    "id": { "type": "string", "pattern": "^[a-z0-9][a-z0-9._+-]{0,63}$" },
    "methodId": { "type": "string", "pattern": "^[a-z0-9][a-z0-9.-]{0,47}/[0-9]{1,4}$" },
    "semver": { "type": "string", "pattern": "^(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})$" },
    "b64u32": { "type": "string", "pattern": "^[A-Za-z0-9_-]{43}$" },
    "sha256hex": { "type": "string", "pattern": "^[0-9a-f]{64}$" },
    "date": { "type": "string", "pattern": "^20[2-9][0-9]-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$" },
    "pct": {
      "type": "object", "required": ["p50"], "additionalProperties": false,
      "properties": { "p10": { "$ref": "#/$defs/u53" }, "p50": { "$ref": "#/$defs/u53" }, "p90": { "$ref": "#/$defs/u53" } }
    },
    "nullableU53": { "oneOf": [ { "$ref": "#/$defs/u53" }, { "type": "null" } ] },
    "nullableI53": { "oneOf": [ { "$ref": "#/$defs/i53" }, { "type": "null" } ] },

    "presentation": {
      "type": "object",
      "required": ["issuedAtMs", "expiresAtMs", "challenge"],
      "additionalProperties": false,
      "properties": {
        "issuedAtMs": { "$ref": "#/$defs/epochMs" },
        "expiresAtMs": { "$ref": "#/$defs/epochMs" },
        "challenge": { "oneOf": [ { "$ref": "#/$defs/b64u32" }, { "type": "null" } ] }
      }
    },

    "body": {
      "type": "object",
      "required": ["audience", "seq", "subject", "producer", "device", "results"],
      "additionalProperties": false,
      "properties": {
        "audience": { "enum": ["own", "other", "file"] },
        "seq": { "type": "integer", "minimum": 1, "maximum": 9007199254740991 },
        "subject": {
          "type": "object", "required": ["nodeId", "keyAlg", "keyStorage"], "additionalProperties": false,
          "properties": {
            "nodeId": { "$ref": "#/$defs/b64u32" },
            "keyAlg": { "const": "ES256" },
            "keyStorage": { "enum": ["strongbox", "tee", "secure-enclave", "tpm", "os-keystore", "file", "unknown"] }
          }
        },
        "producer": {
          "type": "object", "required": ["app", "appVersion", "harness", "engine"], "additionalProperties": false,
          "properties": {
            "app": { "$ref": "#/$defs/id" },
            "appVersion": { "$ref": "#/$defs/semver" },
            "harness": {
              "type": "object", "required": ["id", "version", "methodologyId", "confVersion"], "additionalProperties": false,
              "properties": {
                "id": { "$ref": "#/$defs/id" },
                "version": { "$ref": "#/$defs/semver" },
                "methodologyId": { "$ref": "#/$defs/methodId" },
                "confVersion": { "$ref": "#/$defs/semver" }
              }
            },
            "engine": {
              "type": "object", "required": ["name", "commit", "buildFlags"], "additionalProperties": false,
              "properties": {
                "name": { "$ref": "#/$defs/id" },
                "commit": { "type": "string", "pattern": "^[0-9a-f]{7,40}$" },
                "buildFlags": { "type": "array", "maxItems": 16, "uniqueItems": true, "items": { "$ref": "#/$defs/text" } }
              }
            }
          }
        },
        "device": {
          "type": "object",
          "required": ["class", "vendor", "model", "os", "soc", "memory", "accelerators", "power", "thermal"],
          "additionalProperties": false,
          "properties": {
            "class": { "$ref": "#/$defs/id", "description": "OPEN enum. Known: phone, tablet, handheld, laptop, desktop, server, sbc. Unknown values render as 'other (<value>)'." },
            "vendor": { "$ref": "#/$defs/text" },
            "model": { "$ref": "#/$defs/text" },
            "platformIds": {
              "description": "Raw platform identifiers, self-reported, used ONLY to compare with attested values (Android Build.BRAND/DEVICE/MANUFACTURER/MODEL/PRODUCT). Omitted for audience other and file.",
              "type": "object", "additionalProperties": false,
              "properties": {
                "brand": { "$ref": "#/$defs/text" }, "device": { "$ref": "#/$defs/text" },
                "manufacturer": { "$ref": "#/$defs/text" }, "model": { "$ref": "#/$defs/text" },
                "product": { "$ref": "#/$defs/text" }
              }
            },
            "os": {
              "type": "object", "required": ["family", "version"], "additionalProperties": false,
              "properties": {
                "family": { "enum": ["android", "ios", "ipados", "macos", "linux"] },
                "version": { "$ref": "#/$defs/text" },
                "securityPatch": { "$ref": "#/$defs/date", "description": "Omitted for audience other and file." }
              }
            },
            "soc": {
              "type": "object", "required": ["vendor", "name", "cpu"], "additionalProperties": false,
              "properties": {
                "vendor": { "$ref": "#/$defs/text" },
                "name": { "$ref": "#/$defs/text" },
                "cpu": {
                  "type": "object", "required": ["logicalCores", "clusters"], "additionalProperties": false,
                  "properties": {
                    "logicalCores": { "type": "integer", "minimum": 1, "maximum": 1024 },
                    "clusters": {
                      "type": "array", "maxItems": 8,
                      "items": {
                        "type": "object", "required": ["cores", "maxKHz"], "additionalProperties": false,
                        "properties": { "cores": { "type": "integer", "minimum": 1, "maximum": 1024 }, "maxKHz": { "$ref": "#/$defs/u53" } }
                      }
                    }
                  }
                }
              }
            },
            "memory": {
              "type": "object", "required": ["totalBytes"], "additionalProperties": false,
              "properties": { "totalBytes": { "$ref": "#/$defs/u53" } }
            },
            "accelerators": {
              "type": "array", "maxItems": 8,
              "items": {
                "type": "object", "required": ["kind", "vendor", "name", "apis", "dedicatedBytes"], "additionalProperties": false,
                "properties": {
                  "kind": { "$ref": "#/$defs/id", "description": "OPEN enum. Known: gpu, npu, dsp." },
                  "vendor": { "$ref": "#/$defs/text" },
                  "name": { "$ref": "#/$defs/text" },
                  "apis": { "type": "array", "maxItems": 8, "uniqueItems": true, "items": { "$ref": "#/$defs/id" } },
                  "dedicatedBytes": { "$ref": "#/$defs/nullableU53" }
                }
              }
            },
            "power": {
              "type": "object", "required": ["battery"], "additionalProperties": false,
              "properties": { "battery": { "type": "boolean" }, "batteryDesignMilliWh": { "$ref": "#/$defs/nullableU53" } }
            },
            "thermal": {
              "type": "object", "required": ["cooling", "stateSource"], "additionalProperties": false,
              "properties": {
                "cooling": { "$ref": "#/$defs/id", "description": "OPEN enum. Known: passive, fan, liquid, unknown." },
                "stateSource": { "$ref": "#/$defs/id", "description": "OPEN enum. Known: android-thermal-headroom, apple-thermal-state, linux-thermal-zone, none." }
              }
            }
          }
        },
        "results": {
          "type": "array", "maxItems": 64,
          "items": { "$ref": "#/$defs/result" }
        }
      }
    },

    "result": {
      "type": "object",
      "required": ["modelId", "fileSha256", "fileBytes", "quant", "backend", "settings", "measuredAtMs", "runs", "conditions", "prefill", "decode", "sustained", "memory", "power", "flags"],
      "additionalProperties": false,
      "properties": {
        "modelId": { "$ref": "#/$defs/id" },
        "fileSha256": { "$ref": "#/$defs/sha256hex" },
        "fileBytes": { "$ref": "#/$defs/u53" },
        "quant": { "type": "string", "pattern": "^[A-Za-z0-9_.-]{1,24}$" },
        "backend": { "$ref": "#/$defs/id", "description": "OPEN enum. Known: cpu, vulkan, opencl, metal, cuda, hip, hexagon. Rows with different backend or engine commit are never merged (platforms.md C11)." },
        "settings": {
          "type": "object", "required": ["threads", "gpuLayers", "ctxTokens", "batchTokens"], "additionalProperties": false,
          "properties": {
            "threads": { "type": "integer", "minimum": 0, "maximum": 1024 },
            "gpuLayers": { "type": "integer", "minimum": 0, "maximum": 100000 },
            "ctxTokens": { "type": "integer", "minimum": 1, "maximum": 10000000 },
            "batchTokens": { "type": "integer", "minimum": 1, "maximum": 1000000 }
          }
        },
        "measuredAtMs": { "$ref": "#/$defs/epochMs" },
        "runs": {
          "type": "object", "required": ["planned", "completed", "discarded"], "additionalProperties": false,
          "properties": {
            "planned": { "type": "integer", "minimum": 1, "maximum": 1000 },
            "completed": { "type": "integer", "minimum": 0, "maximum": 1000 },
            "discarded": { "type": "integer", "minimum": 0, "maximum": 1000 }
          }
        },
        "conditions": {
          "type": "object", "required": ["charging", "batteryStartPermille", "thermalStart", "socStartMilliC", "screenOn"], "additionalProperties": false,
          "properties": {
            "charging": { "type": "boolean" },
            "batteryStartPermille": { "oneOf": [ { "type": "integer", "minimum": 0, "maximum": 1000 }, { "type": "null" } ] },
            "thermalStart": { "$ref": "#/$defs/id", "description": "OPEN enum. Known: nominal, light, moderate, severe, critical, unknown." },
            "socStartMilliC": { "$ref": "#/$defs/nullableI53" },
            "screenOn": { "oneOf": [ { "type": "boolean" }, { "type": "null" } ] }
          }
        },
        "prefill": {
          "type": "array", "minItems": 1, "maxItems": 4,
          "items": {
            "type": "object", "required": ["promptTokens", "milliTokPerSec", "ttftMicros"], "additionalProperties": false,
            "properties": {
              "promptTokens": { "type": "integer", "minimum": 1, "maximum": 10000000 },
              "milliTokPerSec": { "$ref": "#/$defs/pct" },
              "ttftMicros": { "$ref": "#/$defs/pct" }
            }
          }
        },
        "decode": {
          "type": "array", "minItems": 1, "maxItems": 4,
          "items": {
            "type": "object", "required": ["contextTokens", "genTokens", "milliTokPerSec"], "additionalProperties": false,
            "properties": {
              "contextTokens": { "type": "integer", "minimum": 0, "maximum": 10000000 },
              "genTokens": { "type": "integer", "minimum": 1, "maximum": 1000000 },
              "milliTokPerSec": { "$ref": "#/$defs/pct" }
            }
          }
        },
        "sustained": {
          "type": "object", "required": ["durationMs", "intervalMs", "steadyMilliTokPerSec", "throttleOnsetMs", "curve"], "additionalProperties": false,
          "properties": {
            "durationMs": { "type": "integer", "minimum": 1000, "maximum": 86400000 },
            "intervalMs": { "type": "integer", "minimum": 100, "maximum": 3600000 },
            "steadyMilliTokPerSec": { "$ref": "#/$defs/u53" },
            "throttleOnsetMs": { "$ref": "#/$defs/nullableU53" },
            "curve": {
              "description": "Tuples [tMs, decodeMilliTokPerSec, socMilliC|null, powerMilliW|null], tMs strictly increasing from 0.",
              "type": "array", "minItems": 2, "maxItems": 240,
              "items": {
                "type": "array", "minItems": 4, "maxItems": 4,
                "prefixItems": [
                  { "$ref": "#/$defs/u53" }, { "$ref": "#/$defs/u53" },
                  { "$ref": "#/$defs/nullableI53" }, { "$ref": "#/$defs/nullableU53" }
                ]
              }
            }
          }
        },
        "memory": {
          "type": "object", "required": ["availableBeforeLoadBytes", "peakProcessBytes"], "additionalProperties": false,
          "properties": {
            "availableBeforeLoadBytes": { "$ref": "#/$defs/u53" },
            "peakProcessBytes": { "$ref": "#/$defs/u53" },
            "kvCacheBytes": { "$ref": "#/$defs/nullableU53" }
          }
        },
        "power": {
          "type": "object", "required": ["method", "avgMilliW"], "additionalProperties": false,
          "properties": {
            "method": { "enum": ["battery-current", "battery-level", "rapl", "pmic", "unavailable"] },
            "avgMilliW": { "$ref": "#/$defs/nullableU53" }
          }
        },
        "flags": {
          "type": "array", "maxItems": 16, "uniqueItems": true,
          "items": { "$ref": "#/$defs/id", "description": "OPEN enum. Known: low-runs, thermal-throttled, charging, background-load, gpu-shared, low-memory." }
        }
      }
    }
  }
}
```

## Appendix B — `asom.bench-public/1` public derivative JSON Schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "asom:schema/asom.bench-public/1",
  "title": "asom public (anonymised) benchmark derivative, major version 1 (DESIGN PROPOSAL; the v2 P7 upload payload)",
  "description": "UNSIGNED by construction. Built only by PublicDerivative.from(verified private payload) through an allow-list. Contains no key, no node id, no seq, no challenge, no evidence, no exact timestamps, no OS patch level, no platform ids. Numbers are quantised to 2 significant digits (manifest.md 11.2).",
  "type": "object",
  "required": ["schema", "device", "harness", "engine", "results"],
  "additionalProperties": false,
  "properties": {
    "schema": { "const": "asom.bench-public/1" },
    "device": {
      "type": "object",
      "required": ["class", "vendor", "model", "socName", "ramClassGiB", "osFamily", "osMajor", "cooling"],
      "additionalProperties": false,
      "properties": {
        "class": { "$ref": "#/$defs/id" },
        "vendor": { "$ref": "#/$defs/text" },
        "model": { "$ref": "#/$defs/text" },
        "socName": { "$ref": "#/$defs/text" },
        "ramClassGiB": { "enum": [1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 2048] },
        "osFamily": { "enum": ["android", "ios", "ipados", "macos", "linux"] },
        "osMajor": { "type": "integer", "minimum": 0, "maximum": 1000 },
        "cooling": { "$ref": "#/$defs/id" }
      }
    },
    "harness": {
      "type": "object", "required": ["version", "methodologyId", "confVersion"], "additionalProperties": false,
      "properties": {
        "version": { "$ref": "#/$defs/semver" },
        "methodologyId": { "type": "string", "pattern": "^[a-z0-9][a-z0-9.-]{0,47}/[0-9]{1,4}$" },
        "confVersion": { "$ref": "#/$defs/semver" }
      }
    },
    "engine": {
      "type": "object", "required": ["name", "commit"], "additionalProperties": false,
      "properties": { "name": { "$ref": "#/$defs/id" }, "commit": { "type": "string", "pattern": "^[0-9a-f]{7,40}$" } }
    },
    "results": {
      "type": "array", "minItems": 1, "maxItems": 16,
      "items": {
        "type": "object",
        "required": ["modelId", "quant", "fileSha256", "backend", "measuredMonth", "runsCompleted", "charging",
                     "prefillPromptTokens", "prefillMilliTokPerSec", "ttftMillis", "decodeContextTokens", "decodeMilliTokPerSec",
                     "steadyMilliTokPerSec", "throttleOnsetSec", "curve", "peakProcessMB", "powerMethod", "powerMilliW"],
        "additionalProperties": false,
        "properties": {
          "modelId": { "$ref": "#/$defs/id" },
          "quant": { "type": "string", "pattern": "^[A-Za-z0-9_.-]{1,24}$" },
          "fileSha256": { "type": "string", "pattern": "^[0-9a-f]{64}$" },
          "backend": { "$ref": "#/$defs/id" },
          "measuredMonth": { "type": "string", "pattern": "^20[2-9][0-9]-(0[1-9]|1[0-2])$" },
          "runsCompleted": { "type": "integer", "minimum": 0, "maximum": 1000 },
          "charging": { "type": "boolean" },
          "prefillPromptTokens": { "type": "integer", "minimum": 1 },
          "prefillMilliTokPerSec": { "$ref": "#/$defs/q2" },
          "ttftMillis": { "$ref": "#/$defs/q2" },
          "decodeContextTokens": { "type": "integer", "minimum": 0 },
          "decodeMilliTokPerSec": { "$ref": "#/$defs/q2" },
          "steadyMilliTokPerSec": { "$ref": "#/$defs/q2" },
          "throttleOnsetSec": { "oneOf": [ { "$ref": "#/$defs/q2" }, { "type": "null" } ] },
          "curve": {
            "type": "array", "minItems": 2, "maxItems": 12,
            "items": { "type": "array", "minItems": 2, "maxItems": 2, "prefixItems": [ { "$ref": "#/$defs/q2" }, { "$ref": "#/$defs/q2" } ] }
          },
          "peakProcessMB": { "$ref": "#/$defs/q2" },
          "powerMethod": { "enum": ["battery-current", "battery-level", "rapl", "pmic", "unavailable"] },
          "powerMilliW": { "oneOf": [ { "$ref": "#/$defs/q2" }, { "type": "null" } ] }
        }
      }
    }
  },
  "$defs": {
    "q2": { "type": "integer", "minimum": 0, "maximum": 9007199254740991, "description": "Non-negative integer quantised to at most 2 significant decimal digits (values < 100 unchanged). Law: q2(q2(x)) == q2(x)." },
    "id": { "type": "string", "pattern": "^[a-z0-9][a-z0-9._+-]{0,63}$" },
    "semver": { "type": "string", "pattern": "^(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})$" },
    "text": {
      "type": "string", "minLength": 1, "maxLength": 96,
      "pattern": "^[^\\u0000-\\u001F\\u007F-\\u009F\\u061C\\u200E\\u200F\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]+$"
    }
  }
}
```
