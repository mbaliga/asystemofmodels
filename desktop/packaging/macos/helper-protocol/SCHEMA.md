# asom-mac-helper protocol v1 (SCHEMA)

Normative for two implementations that must agree byte for byte: the Kotlin client in `desktop/packaging/macos/macplatform`
(`xyz.mdhv.asom.desktop.mac.helper`) and the Swift helper in `desktop/packaging/macos/helper` (`HelperProtocol`). Source:
`docs/design/mesh/platforms/macos.md` 3.5 (the table there is a summary; this file is the normative form). This is an INTERNAL
interface between the node and its own child process. It is not a contract with apps (macos.md 11.1 M-D11) and adds no endpoint,
header or error code to the frozen v1 HTTP contract.

Every choice the design left open is recorded in `desktop/packaging/macos/ERRATA.md` (ids `MAC-PROTO-*`).

## 1. Transport

* The node spawns `Contents/MacOS/asom-mac-helper serve` once and talks over the child's stdin and stdout. There is no socket, no
  XPC service and no listener. Only the parent holds the pipes.
* One JSON object per line, UTF-8, terminated by a single LF (0x0A). The node writes requests to the helper's stdin; the helper
  writes responses and events to its stdout. The helper writes NOTHING to stderr (the node discards it); no request content, key
  blob, path or token ever reaches a log (macos.md 3.3 b).
* A line is at most `MAX_LINE_BYTES = 131072` bytes, not counting the LF. A byte 0x0A or 0x0D inside a line is invalid (CR is
  refused even as insignificant whitespace, so `\r\n` line endings are rejected, never tolerated).
* End of stdin: the helper releases its power assertion and exits 0. A final partial line (no LF before EOF) is discarded unanswered.
* Requests are answered in order, one response per request line, whatever the outcome. Events are interleaved between responses at
  any point; each is a complete line. The optional request `id` lets the node pipeline (for example `sleep.ack` while another
  request is outstanding).
* A helper that dies is a lost probe on the node side (`PROBE_LOST`, macos.md 3.5); the node restarts it at most once per minute.

## 2. JSON profile (both parsers, identical)

Integers only. Rejected, as `MALFORMED_JSON`: bytes that are not well-formed UTF-8 (overlong forms, encoded surrogates, above
U+10FFFF), a leading BOM, floats, fractions, exponents, `-0`, leading zeros, an integer of more than 16 digits or beyond
+-(2^53 - 1), duplicate object member names, a lone or reversed surrogate escape, a `\u` escape with any character that is not
one of `0-9a-fA-F` (ASCII only), a raw control character (below U+0020) inside a string, any escape other than `\" \\ \/ \b \f \n
\r \t \uXXXX`, trailing content after the value, and nesting deeper than 32 (the top-level value is depth 0; a value at depth 33
is refused). Insignificant whitespace is SP and TAB. `null`, `true` and `false` are lower case only.

Canonical output (what both encoders write and what the vectors pin): no whitespace outside strings; members in the order of the
tables below; strings escape `"` as `\"`, `\` as `\\`, every character below U+0020 as `\u00xx` (lower-case hex) and nothing else
(all other characters, including U+007F and U+2028, are written raw as UTF-8); integers in decimal; `null`, `true`, `false`.

Binary values (`blob`, `data`, `spki`, `sig`, `digest`) are STRICT base64: RFC 4648 section 4 standard alphabet, padding
required, length a multiple of four, canonical (re-encoding the decoded bytes gives the same string, so non-zero trailing bits and
the URL-safe alphabet are rejected), no whitespace. Length limits are on the DECODED bytes. Text limits are on UTF-8 BYTES, not
characters. Text rules: "no control character" means none of U+0000 to U+001F and U+007F; "printable ASCII" means U+0020 to U+007E
only; "absolute path" means it starts with `/`, then no control character; the fixed reason is compared byte for byte.

## 3. Decode order and reject codes

Codec-level reject codes are a closed set. When several things are wrong, the FIRST in this order wins, so both lanes give the
same code:

1. `LINE_TOO_LONG` (more than `MAX_LINE_BYTES` bytes);
2. `MALFORMED_JSON` (a CR or LF byte in the line, invalid UTF-8, or any violation of section 2);
3. `NOT_OBJECT` (the top-level value is not an object);
4. the discriminator: `op` (requests), `ev` (events) or `ok` (responses). Absent: `MISSING_FIELD`. Present but the wrong type
   (`op` and `ev` must be strings, `ok` a boolean): `BAD_FIELD`. A string that names no known op or event: `UNKNOWN_OP`;
5. `UNKNOWN_FIELD` (a member that is not in the message's field set, including the optional `id` where it is not allowed);
6. `MISSING_FIELD` (a required member is absent; a nullable member must still be present, with the value `null` allowed);
7. `BAD_FIELD` (a member has the wrong type, is out of range, is not one of the enum values, is not canonical base64, or fails its
   text rule).

Range checks 1 to 7 are all made before any effect. An `id` (requests and responses only) is optional, an integer in
`0 .. 2^53 - 1`, echoed by the responder.

## 4. Requests (node to helper)

Canonical member order: `op`, `id` (when present), then the parameters in the order listed.

| `op` | parameters | notes |
|---|---|---|
| `hello` | `v` integer `0 .. 2^53-1` | the codec accepts any such `v`; the helper answers `UNSUPPORTED_VERSION` unless `v == 1` |
| `se.create` | none | CryptoKit `SecureEnclave.P256.Signing.PrivateKey()`, no access-control flags (the NIK never requires user presence, trust.md 2.5) |
| `se.sign` | `blob` base64 1..4096 bytes; `data` base64 0..65536 bytes | the helper hashes (SHA-256) and signs; raw `r\|\|s` |
| `se.selftest` | `blob` | sign a fixed string, verify with the public key |
| `power.get`, `thermal.get`, `presence.get`, `gpu.get`, `mem.get` | none | |
| `assert.hold` | `reason` text, exactly `asom: lending compute to your paired devices` | the string is what `pmset -g assertions` shows, so it is fixed (MAC-PROTO-4) |
| `assert.release` | none | |
| `sleep.ack` | `token` integer `0 .. 2^53-1` | answers a `sleep.will` event |
| `svc.status`, `svc.register`, `svc.unregister` | `kind` enum `agent`, `daemon` | |
| `backup.exclude` | `path` text 1..1024 bytes, absolute (starts with `/`), no control character | |
| `platform.uuid`, `paths.get` | none | |

## 5. Responses (helper to node)

Canonical member order: `ok`, `id` (when the request carried a usable one), then the fields below. A response is decoded knowing
the op it answers (the node sends one op at a time per id). A helper that cannot parse the request far enough to know its op still
answers with an error response, and echoes `id` when the top-level object parsed and carried a valid `id` (best effort).

Success (`"ok":true`):

| op | fields |
|---|---|
| `hello` | `helper` semver text (`1.2.3` or `1.2.3-pre.1`, at most 32 bytes); `macos` text `27.0` or `27.0.1` (digits and dots, at most 16 bytes); `arch` enum `arm64`, `x86_64`; `se` boolean (`SecureEnclave.isAvailable`); `model` printable ASCII 1..64 bytes (`Mac14,3`) |
| `se.create` | `blob` base64 1..4096 bytes; `spki` base64, exactly 91 bytes (DER SubjectPublicKeyInfo of a P-256 key) |
| `se.sign` | `sig` base64, exactly 64 bytes (`r\|\|s`) |
| `se.selftest` | `verified` boolean (the node treats `false` as a failed self-test) |
| `power.get` | `source` enum `ac`, `battery` (a UPS is `battery`; an unreadable source is the error `UNAVAILABLE`); `charging` boolean; `batteryPermille` integer `0..1000` or `null` (no battery); `lowPower` boolean |
| `thermal.get` | `state` enum `nominal`, `fair`, `serious`, `critical` |
| `presence.get` | `hidIdleMs` integer `>= 0` or `null` (unreadable); `screenLocked` boolean or `null`; `consoleUserIsSelf` boolean or `null`. A `null` is "unknown", and the node treats unknown as PRESENT (MAC-PROTO-2) |
| `gpu.get` | `deviceUtilPermille` integer `0..1000` (an unreadable counter is the error `UNAVAILABLE`) |
| `mem.get` | `physicalBytes` integer `>= 1`; `gpuRecommendedMaxWorkingSetBytes` integer `>= 0` or `null` (no Metal device) |
| `assert.hold`, `assert.release`, `sleep.ack`, `backup.exclude` | none |
| `svc.status`, `svc.register`, `svc.unregister` | `status` enum `notRegistered`, `enabled`, `requiresApproval`, `notFound` |
| `platform.uuid` | `digest` base64, exactly 32 bytes (`SHA-256(IOPlatformUUID)`); the raw UUID never leaves the helper |
| `paths.get` | `userTempDir` absolute text 1..1024 bytes (`NSTemporaryDirectory()`, the per-user directory the CLI also sees as `$TMPDIR`) |

Failure (`"ok":false`), fields `code` and `message`, in that order:

| `code` | when |
|---|---|
| `BAD_REQUEST` | the request line failed any decode step of section 3 (the message is the reject code, for example `BAD_FIELD`), or a `sleep.ack` names no outstanding token |
| `UNKNOWN_OP` | the `op` names nothing (section 3, step 4) |
| `UNSUPPORTED_VERSION` | `hello` with `v` other than 1 |
| `UNAVAILABLE` | the capability does not exist on this Mac (no Secure Enclave, no GPU counter, unreadable power source) |
| `FAILED` | the capability exists and the operation failed (a blob the Enclave refuses, `SMAppService` threw, `setResourceValues` failed) |

`message` is a fixed short reason (text without control characters, at most 200 bytes) and never echoes request content, a key
blob, a path or a token.

Semantics: `assert.hold` while an assertion is already held is `ok` and creates no second assertion (at most one exists);
`assert.release` while none is held is `ok`. The assertion type is `kIOPMAssertionTypePreventUserIdleSystemSleep`, never
`PreventSystemSleep` (macos.md 2.1). It is released when the helper exits for any reason, including the death of the node
(stdin EOF, and IOKit releases it with the process, AM11).

## 6. Events (helper to node, unsolicited)

Canonical member order: `ev`, then the fields.

| `ev` | fields |
|---|---|
| `power` | the same four fields as `power.get`, pushed when the power state changes |
| `thermal` | `state` as `thermal.get`, pushed when it changes |
| `sleep.will` | `token` integer. The node MUST answer `sleep.ack` with the same `token` within 2 seconds; the helper calls `IOAllowPowerChange` after the ack or after 2 seconds, whichever comes first (macos.md 2.1: a Mac whose user closed the lid is not held awake for the 30 s macOS allows) |
| `wake` | none |

## 7. Vectors (`vectors/*.jsonl`)

One JSON object per line, parsed by the same strict profile in both lanes. Text fields:

| field | meaning |
|---|---|
| `id` | unique, `HP-<family>-<nnn>` |
| `kind` | `request`, `response`, `event` or `exchange` |
| `op` | (`response` only) the op the response answers |
| `line` | the wire line, without its LF. Alternative: `lineHex`, the same as hex bytes (used for invalid UTF-8) |
| `pad` | (optional, integer) replace the token `{PAD}` in `line` by this many ASCII `A` characters |
| `zeros` | (optional, integer) replace the token `{ZEROS}` in `line` by the base64 of this many zero bytes |
| `verdict` | `accept` or `reject` |
| `code` | the reject code (section 3), or `-` when accepted |
| `canonical` | for `accept`: the canonical re-encoding of the decoded message (equal to `line` when it was canonical already) |
| `request`, `requestVerdict`, `requestCode`, `response` | (`exchange` only) a raw request line, the codec's verdict and code on it, and the exact response line the fixture machine of section 8 produces |
| `note` | free text, ignored |

Both lanes print one line per vector, `id TAB verdict TAB code TAB canonical` (for an exchange: `id TAB exchange TAB - TAB
<canonical response>`; for a reject the canonical field is `-`), and `scripts/check-protocol-lanes.sh` diffs the two outputs. The
vectors are SELF-ORACLED (the same author wrote both codecs and the expectations); agreement between the lanes is "cross-lane",
not "independent" (R3-CLOSURE-12). Every vector family counts the cases that exercised it and fails when the count is zero.

## 8. The fixture machine (the `exchange` vectors)

The Swift `FixtureBackend` and the Kotlin `FakeHelper` replay the same deterministic machine:

* `hello`: helper `0.1.0`, macos `27.0.1`, arch `arm64`, `se` true, model `Mac14,3`.
* `se.create`: blob = the ASCII bytes `ASOM-FIXTURE-SE-BLOB-V1`; spki = the 26-byte P-256 SPKI prefix, `04`, then the bytes `01`..`40`
  (91 bytes; not a valid curve point, so no lane may use it as a key). `se.sign` and `se.selftest` succeed only for that blob
  (any other blob is `FAILED`); the signature is 32 bytes `11` then 32 bytes `22`.
* `power.get`: `ac`, charging true, 870 permille, low power false. `thermal.get`: `nominal`. `presence.get`: idle 725000 ms, not
  locked, console user is self. `gpu.get`: 137. `mem.get`: 17179869184 and 11453251584.
* `svc.status` agent: `notRegistered`; daemon: `notFound`. `svc.register` agent: `requiresApproval`; daemon: `notFound`.
  `svc.unregister`: `notRegistered`.
* `sleep.ack` succeeds only for token 7 (the fixture's outstanding token); any other token is `BAD_REQUEST`.
* `backup.exclude` succeeds for a path under `/tmp/`, otherwise `FAILED`.
* `platform.uuid`: `SHA-256` of the ASCII text `fixture-platform-uuid`. `paths.get`: `/var/folders/zz/fixture/T/`.

## 9. Versioning

`hello.v` is the protocol version. A new version adds ops or fields only with a new `v`; a v1 helper never guesses a v2 request.
Removing or renaming anything is a new major protocol and a new SCHEMA file.
