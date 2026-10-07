# Trust, identity, pairing and transport between nodes

**Revision 4 (2026-10-07):** §16 amends this section; where they differ, §16 wins (`REVISION_4.md`).

**Section of:** the ASOM multi-device ("mesh") design session, 2026-09-29.
**Status:** DESIGN PROPOSAL. Nothing here is approved. It is a candidate for turning roadmap §7 (v4, "The Literal Hotspot", DIRECTION-GRADE) into CONTRACT-GRADE text for its trust layer. v4's entry criteria stand (v3 shipped, the design session held, a tailnet in place). v1 device validation is not complete, so none of this may be executed yet. Every contract addition and every invariant change below needs owner sign-off (§13, §14).
**Sibling sections this one relies on:** `platforms.md` (per-OS hosting and the Swift/JVM code strategy), `manifest.md` (signed capability manifest and attestation tiers), `benchmark.md`, `router.md` (placement, live-state payloads, failover), `contract.md` (overall delta registry and phasing). This section owns: who a node is, how two nodes come to trust each other, how they talk, who may ask for what, how trust is withdrawn, what happens to prompt content, and the cross-node ledger linkage.

Tags used below: **[Vn]** is a fact verified this session (sources in §16.1). **[An]** is an assumption I did not verify (§16.2). Anything tagged [An] and load-bearing is also listed as a build-time spike.

---

## 0. The design in eight rules

These rules govern every mechanism below. An implementation that breaks one of them is wrong, whatever else it does.

| # | Rule | v1 analog |
|---|---|---|
| R1 | **A node's identity is a key, never an address, a name or a discovery record.** Authentication and authorization take exactly two inputs: the pinned key fingerprint and the local registry row. | Identity came from `Binder.getCallingUid()`, never from intent extras |
| R2 | **Only an explicit `PAIRED` row authorizes**, and it is re-read on every request. Any other state, a missing row, or an unreadable row denies. | `check()` allow-lists `PAIRED` (audit fix C-pairing-1) |
| R3 | **No network message can create, restore or raise trust.** Only a local human action on the device concerned can move a peer into `PAIRED`. A network message can at most *lower* trust. | A revoked app cannot un-revoke itself over AIDL |
| R4 | **Consent comes before contact.** A node accepts a pairing only inside a window the user opened on that node, and the user confirms on *both* devices. | Consent sheet shows the verified identity |
| R5 | **Content leaves only after the peer has agreed to serve.** An offer carries metadata only. The prompt body is sent after an `ACCEPT`. | (new) |
| R6 | **A peer serves with its local engine only.** It never sends a peer's request on to the cloud, never forwards it to another peer, and never uses its own BYOK keys for someone else. | Keys never transfer (§10A.3). Roadmap §11: no relaying |
| R7 | **Peers get no data-read capability.** The mesh has no ledger read, no key access, no model-file transfer, no verbose bodies and no app identities. A compromised peer can do little beyond what it is sent. | The fd provider needs an active pairing |
| R8 | **Every attempt that reaches a wire has a row on both nodes,** and the requester's row is durable *before* content leaves. A stated egress class must be true. | Audit: failover attempts were missing rows |

---

## 1. Threat model

| Adversary | Capabilities assumed | Primary defence |
|---|---|---|
| LAN attacker (café Wi-Fi, a compromised IoT device at home) | Sees and injects packets on the LAN, spoofs ARP, DHCP and mDNS, runs its own TLS servers | Pinned mutual TLS (R1). Listener limited to private addresses and enabled networks (§12). Addresses are never trusted |
| Holder of a photographed or screenshotted pairing QR | Knows the one-time secret for up to 120 s. Is on the same LAN or tailnet | Single-use window, SAS comparison, consent on both devices (§4.8) |
| Author of a malicious QR (poster, message) | Chooses the key, addresses and name in the QR | Scanner confirms before connecting. Scanner refuses public addresses. Consent on both devices (§4.8) |
| Compromised or malicious **paired** peer | Holds a valid pinned key and whatever scopes were granted | Minimal scopes (R7). Offer/accept (R5). Data classes (§10). Per-attempt ledger. Revocation (§8). Its capabilities are listed in §9 |
| Stolen device, still running asom | Holds its node key and leaf in memory | Revocation on each peer, sped up by revocation hints (§8.3). The residual exposure is stated in §9.3 |
| Malicious local app on a node | Can connect to any port on 127.0.0.1 | The peer protocol never appears on loopback. The app-facing API never accepts mesh identities (§3.5) |
| Overlay operator (Tailscale Inc. or a self-hosted Headscale) | Controls the overlay's routing and coordination | Overlay is an underlay only. asom mTLS runs inside it. A malicious coordination server can cause denial of service but cannot impersonate a peer (§6.5) |

Out of scope: an attacker with root or kernel control of a node (they can use its keys and read its memory; no mechanism here survives that), side channels in the inference engine beyond the cache rule in §10.6, and traffic analysis by the overlay's relays.

---

## 2. Node identity

### 2.1 Key hierarchy: two levels, deliberately

```
Node Identity Key (NIK)          ECDSA P-256, generated on-device, hardware-backed where possible,
  |                              purpose = SIGN only, never exported, never rotated in band
  |  self-signs
  v
Node certificate                 the PINNED object. pin = SHA-256(DER SubjectPublicKeyInfo of NIK)
  |  signs (weekly, and on start)
  v
Session leaf key + leaf cert     ECDSA P-256, used by the TLS stack for CertificateVerify,
                                 validity 14 days, private key in memory or the OS keystore
```

**Why two levels and not "the hardware key is the TLS key".**
1. **Apple.** Whether a Secure Enclave key can act as a TLS client identity is *unverified*. Apple DTS confirmed a bug that blocked it in 2016 and, as of 2021, still had not confirmed the fix [V9]. Designing for a single level would put iOS pairing on an unverified path. With two levels the Secure Enclave only has to do `SecKeyCreateSignature` over a 32-byte digest once a week, and it verifiably supports P-256 signing [V4].
2. **StrongBox and TPM** are slow and support few concurrent operations [V5][A8]. A TLS handshake calls the key once per connection. A weekly leaf signature does not load them at all.
3. **Every tier needs just one capability: "sign a digest with P-256".** StrongBox [V5], the TEE, the Secure Enclave [V4], TPM 2.0 [V12], PKCS#11 and a plain file key all have it. That keeps the per-platform integration small and the Swift and JVM implementations symmetric, which matters because of the KMP ban (see `platforms.md`).
4. Leaf keys can be rotated freely without re-pairing. Only the NIK is pinned.

**What the two levels cost.** A software leaf key stolen from process memory impersonates the node until the leaf expires (at most 14 days + skew) or until the NIK is revoked on the peer, whichever comes first. Against an attacker who can read process memory this adds very little, because that attacker can also ask the NIK to sign new leaves for as long as they keep access.

### 2.2 Algorithm: ECDSA P-256 with SHA-256, and nothing else in v4

| Candidate | StrongBox | Android TEE | Secure Enclave | TPM 2.0 | JSSE / Conscrypt / Network.framework TLS | Verdict |
|---|---|---|---|---|---|---|
| **ECDSA P-256** | yes [V5] | yes | **only classical curve** [V4] | mandated by the PC Client profile [V12] | universal [A9] | **Chosen** |
| Ed25519 | **no** [V5] | Android 13+ (KeyMint v2) [V6] | **no** [V4] | not generally available [V12] | uneven | Rejected: cannot be hardware-backed on iPhone, StrongBox or most TPMs |
| RSA-2048 | yes | yes | no | yes | yes | Rejected: no Secure Enclave support; large |
| ML-DSA-65 | no | no | iOS 26+ [V4] | no | no TLS support | Parked for PQ migration (§2.8) |

### 2.3 Identifiers

| Name | Definition | Where used |
|---|---|---|
| `pin` | `SHA-256(DER(SubjectPublicKeyInfo(NIK)))`, 32 bytes | **The only authentication input.** Registry primary key. Carried as `k` in the QR |
| `nodeId` | `base64url(pin)` without padding (43 chars) | Wire messages |
| `nodeTag` | first 16 chars of lowercase RFC 4648 base32 of `pin` (80 bits) | Ledger rows, UI, `X-Asom-Node`. **Never used for authorization** |
| display fingerprint | `nodeTag` uppercased and grouped in fours, e.g. `RWLS-SQQ3-SEZA-5MRW` | Consent sheets, Peers tab |

### 2.4 Certificate profiles (exact)

Minimal self-signed X.509v3, generated from fixed templates. Parsing uses the platform's X.509 parser. Generation uses a small deterministic DER template encoder shipped with golden vectors. The JDK has no public certificate-builder API [A10], and adding BouncyCastle for two fixed templates is not worth the dependency.

**Node certificate**

| Field | Value |
|---|---|
| version | v3 |
| serialNumber | 16 random bytes, high bit cleared |
| signature | `ecdsa-with-SHA256` (1.2.840.10045.4.3.2) |
| issuer = subject | `CN=asom-node <nodeTag>` |
| validity | notBefore = creation − 1 h; notAfter = `99991231235959Z` (RFC 5280 "no well-defined expiration") |
| SPKI | id-ecPublicKey, prime256v1, uncompressed point |
| extensions | basicConstraints `CA:TRUE, pathLen:0` (critical); keyUsage `keyCertSign` (critical); subjectKeyIdentifier |

**Session leaf certificate**

| Field | Value |
|---|---|
| issuer | node certificate subject |
| subject | `CN=asom-session <nodeTag>` |
| validity | notBefore = now − 1 h; notAfter = now + 14 d. Re-minted on every start and after 7 days |
| SPKI | P-256 leaf public key |
| extensions | basicConstraints `CA:FALSE` (critical); keyUsage `digitalSignature` (critical); extKeyUsage `serverAuth, clientAuth`; authorityKeyIdentifier = node SKI |

No names, no SANs, no custom OIDs. Hostname verification is never performed, because addresses are not identity (R1). The certificate chain sent in TLS is exactly `[leaf, node]`.

### 2.5 Key storage tiers and their honest limits

| Tier | Where | Platforms | Protects against | Does **NOT** protect against |
|---|---|---|---|---|
| **T3 Secure element** | Android StrongBox (`setIsStrongBoxBacked(true)`, P-256 SIGN) | Android devices that have StrongBox | Key extraction even under physical or side-channel attack. Cloning | Malware or root on the device *using* the key while it runs. A stolen phone that is still running (§9.3). Attesting that asom itself is unmodified |
| **T2 Isolated hardware** | Android TEE Keystore / Apple Secure Enclave (`SecureEnclave.P256.Signing`, `…ThisDeviceOnly`) / Linux TPM 2.0 via PKCS#11 (P-256) | Android without StrongBox; iPhone and iPad; Macs with a Secure Enclave; Linux with a usable TPM | Key extraction by software. Cloning through backups (the key does not migrate) | Same as T3. **Mac JVM caveat:** a JVM process probably cannot reach the Secure Enclave without a signed native helper holding keychain entitlements [A3]; asom-desktop on macOS therefore starts at T1 or T0 until that is spiked. **Steam Deck caveat:** SteamOS appears to blacklist the TPM kernel module by default [V13, low confidence], so a Deck node starts at T0 unless the owner re-enables the module |
| **T1 OS keystore, software** | macOS login keychain; Linux Secret Service (GNOME Keyring or KWallet), encrypted under the login password | Mac and Linux without T2 | Offline theft of the disk while logged out | **Any process running as the same user can usually read it.** Malware running as the user can clone the identity silently |
| **T0 File key** | PKCS#8 at `$XDG_STATE_HOME/asom/node-key.p8`, mode 0600, optionally passphrase-encrypted | Headless Linux, Deck, CI | Other unprivileged users | Root, backups and disk images. **A cloned file key is invisible to peers.** Two machines holding one identity look like one node |

**Rules that cut across tiers**
- The tier is recorded as `keyTier` in `HELLO` (§5.2) and shown in the Peers tab. It is **self-reported** unless `manifest.md`'s attestation verifies it. The UI must say "hardware-backed (self-reported)" or "hardware-backed (attested)" and never plain "hardware-backed".
- **Pairing does not require any tier or any attestation.** A T0 Linux box must be able to pair. The tier informs the user's choice. It is not a gate. (A per-peer "require ≥ T2 attested" option can be added later; it is not proposed now.)
- **NIK never requires user authentication** (`setUserAuthenticationRequired`, `kSecAccessControlUserPresence`). The daemon signs leaves in the background, so requiring auth would break unattended operation. The consequence: a locked but running stolen device keeps its identity (§9.3).
- The NIK is generated at first mesh enable, never before, so a v1-style single-device user never has a node key at all.

### 2.6 Leaf lifecycle

```
on daemon start, and every 24 h:
  if leaf is null or leaf.notAfter - now < 7 d:
      leafKey  = generate P-256 (Android: TEE Keystore key usable by the KeyManager;
                                 JVM: in-memory only, never written to disk;
                                 Apple: software key in the keychain, ThisDeviceOnly  [A2])
      leafCert = template(leafKey.pub, now-1h, now+14d) signed by NIK
      install into the TLS server and client contexts; existing sessions continue
```

### 2.7 What node identity does NOT guarantee

- It does not prove that the software behind the key is asom, or unmodified asom.
- It does not prove who operates the node. "Own device" is a human declaration made at pairing (§10.1).
- It does not prove that the node is the physical device it names itself as. Names and platforms are self-reported.
- At T0 and T1 it does not prove the key exists on only one machine.
- Hardware backing prevents extraction, not misuse by code running on the device.

### 2.8 Post-quantum note

Traffic recorded on a LAN today could be decrypted later by a quantum-capable adversary ("harvest now, decrypt later"), unless the TLS key exchange is hybrid. The TLS profile (§3.2) therefore uses the platform's default key-exchange groups and will pick up hybrid ML-KEM groups where each stack offers them. Whether JSSE, Conscrypt and Network.framework offer them today is **not verified** here. NIK signatures are an authentication-time risk only, so they are not urgent. The pin format carries an algorithm tag (`alg` in the registry, `p256` only in v4) to leave room for an ML-DSA NIK later [V4].

---

## 3. Transport

### 3.1 Options considered

| Option | Hardware-backable identity everywhere? | Native, vetted stack on JVM, Android and iOS? | Operator dependency | Verdict |
|---|---|---|---|---|
| **TLS 1.3, mutual, pinned self-signed certs (two-level, §2.1)** | Yes: P-256 NIK in T2/T3, signing leaves | Yes: JSSE, Conscrypt, Network.framework [A9]. ATS does not apply to Network.framework [V3] | None | **Chosen** |
| Noise (XX/IK) | **No.** Noise's specified DH functions are 25519 and 448; Secure Enclave, StrongBox and TPM offer P-256 only [V4][V5][V12]. A P-256 Noise variant would be non-standard crypto | No first-party implementation on Apple or Android. Third-party code would sit in the crypto-critical path | None | Rejected |
| WireGuard / Tailscale **as the trust anchor** | No: Curve25519 keys | Needs a VPN Network Extension on iOS, and probably holds the device's single VPN slot [A12] | Tailscale Inc. or a self-hosted Headscale coordination server [V10] | Rejected **as the trust anchor**. Tailnet membership is not asom pairing: every tailnet device, shared node or ACL mistake would reach the listener. **Kept as an optional underlay** (§6.5) |
| TLS-PSK from the pairing secret | PSK is a symmetric secret in app storage, so no | Uneven support | None | Rejected: stored secrets instead of keys; PSK on iOS is awkward |
| TLS 1.3 with raw public keys (RFC 7250) | Yes | JSSE and Network.framework do not support it [A9] | None | Rejected on support |
| HTTP bearer tokens over TLS, as the roadmap's "per-device tokens" | Not key-bound | Yes | None | Rejected: a leaked token can be replayed from anywhere on the network. Keys bind identity to a device |

**Decision.** asom-mesh uses TLS 1.3 with mandatory mutual authentication. Each side pins the other's node certificate. A small length-prefixed frame protocol (§3.3) runs on top. The overlay, when present, is a reachability layer underneath and never a trust anchor. The protocol is identical on LAN and on the overlay.

**Why frames and not HTTP on the peer channel.**
(a) **iOS.** URLSession under ATS cannot be *loosened* to accept a self-signed certificate, only tightened [V3]. Pinned self-signed trust on iOS therefore needs Network.framework, which has no HTTP client. `NWProtocolFramer` handles length-prefixed frames directly.
(b) **JVM server.** Ktor CIO's server-side TLS is not established: the HTTPS issue is still open, and CIO's own TLS lacks TLS 1.3 [V11]. The brief pins CIO, and Netty would need an owner exception. A plain JSSE `SSLServerSocket` (or `SSLEngine`) with a frame codec avoids both, and it runs the same way on Android through Conscrypt.
(c) A six-message protocol is a much smaller attack surface to expose on a LAN than a general HTTP server.

The OpenAI request body still passes through **verbatim** inside `INFER_BODY`, as v1 §5.9 requires, and response chunks come back as the same bytes the engine produced.

### 3.2 TLS profile (normative)

| Parameter | Value | Why |
|---|---|---|
| Versions | TLS 1.3 **only** | No downgrade surface |
| Client authentication | **Required** (`needClientAuth=true`; `sec_protocol_options_set_peer_authentication_required`) | Mutual pinning |
| Signature schemes | `ecdsa_secp256r1_sha256` only | Matches the certificates |
| Cipher suites and groups | Platform TLS 1.3 defaults; hybrid ML-KEM groups where offered (§2.8) | Don't hand-tune |
| 0-RTT early data | **Disabled** on every implementation | Early data is replayable |
| Session resumption | **Never redeemable** (r4, R4-T-01: a fresh TLS context per dial and per accepted connection; a JSSE listener may still send tickets that cannot be redeemed; r3 text: "Disabled on the peer listener (no tickets issued, no client session cache)") | JSSE is believed not to call the TrustManager on resumption [A1], which would skip the registry check. The per-frame check (§5.3) covers this anyway |
| SNI | Not sent (connections go to IP literals) | Nothing identifying in the ClientHello |
| ALPN | `asom-mesh/1`, required. Server fails the handshake (`no_application_protocol`) otherwise | Prevents cross-protocol confusion with other TLS services |
| Trust evaluation | The custom verifier below replaces platform PKIX. No system trust store, no hostname check | R1 |
| Handshake timeout | 5 s | Bounds unauthenticated work |

**Peer-chain verifier.** The same function is used on both sides, and it is the most security-critical code in the mesh. It has its own vectors (§15).

```
enum Mode { EXPECT_PAIRED(pin), EXPECT_PAIRING(pinFromQr), ESTABLISHED_SERVER, PAIRING_SERVER }

fun verifyPeerChain(chain: List<X509>, mode: Mode, now: Instant): Pin {
  require(chain.size == 2)                               // exactly [leaf, node]; reject 1 or 3+
  val (leaf, node) = chain
  require(node.publicKey.isEcP256() && leaf.publicKey.isEcP256())
  require(node.sigAlg == ECDSA_SHA256 && leaf.sigAlg == ECDSA_SHA256)
  require(node.isCA(pathLen = 0) && node.keyUsage == {keyCertSign})
  require(!leaf.isCA() && leaf.keyUsage.contains(digitalSignature))
  require(leaf.issuerDN == node.subjectDN)
  require(verifySig(node, by = node.publicKey))          // self-signature
  require(verifySig(leaf, by = node.publicKey))          // leaf signed by the NIK
  require(now in (leaf.notBefore - 2h) .. (leaf.notAfter + 2h)) else CLOCK_SKEW
  val pin = sha256(node.subjectPublicKeyInfo.encoded)
  when (mode) {
    EXPECT_PAIRED(p)    -> require(constantTimeEq(pin, p) && registry.statusOf(pin) == PAIRED)    // normal dialing
    EXPECT_PAIRING(k)   -> require(constantTimeEq(pin, k) && registry.statusOf(pin) != REVOKED)   // S dialing D from a QR
    ESTABLISHED_SERVER  -> require(registry.statusOf(pin) == PAIRED)   // allow-list (R2)
    PAIRING_SERVER      -> require(pairingWindow.isOpen() && registry.statusOf(pin) != REVOKED)
  }
  return pin
}
// Server mode selection: ESTABLISHED_SERVER, unless the pin is unknown AND a pairing window is open,
// in which case PAIRING_SERVER. A connection admitted in pairing mode accepts only PAIR_* frames (§4).
// Any exception or unknown status -> handshake failure with alert certificate_unknown
// (one alert for every refusal, so a refused node does not learn its status).
```

The TLS stack itself checks the CertificateVerify signature with `chain[0]`'s key, which proves possession of the leaf key. The verifier supplies the chain-to-pin logic. **The client verifies the server chain before it sends its own certificate** (TLS 1.3 message order), so a client that dials a wrong or hostile address aborts without revealing its identity.

### 3.3 The `asom-mesh/1` frame protocol

```
frame   = length:u32be  type:u8  stream:u32be  payload:(length - 5 bytes)
length  = number of bytes after the length field; 5 <= length <= 16 MiB + 5 (else FRAME_TOO_LARGE, close)
stream  = 0 for connection-level frames; odd = opened by the TLS client; even = opened by the TLS server
payload = UTF-8 JSON object, except INFER_BODY and INFER_CHUNK, which are raw bytes
types 0x80..0xFF are ignorable extensions (skip if unknown); an unknown type below 0x80 -> PROTOCOL_ERROR, close
```

Worked example (computed): `HELLO` with payload `{"v":1}` on stream 0 encodes as `0000000c 01 00000000 7b2276223a317d`.

| Type | Name | Dir | Stream | Payload (JSON unless noted) |
|---|---|---|---|---|
| 0x01 | `HELLO` | C→S | 0 | §5.2 |
| 0x02 | `HELLO_ACK` | S→C | 0 | §5.2 |
| 0x03 / 0x04 | `PING` / `PONG` | both | 0 | `{"n":<u64>}` |
| 0x05 | `GOAWAY` | both | 0 | `{"reason":"revoked\|suspended\|shutdown\|network-change\|idle"}` |
| 0x06 | `ERROR` | both | any | `{"code":"<MeshError>","message":"<authored text only>","retryAfterMs":<int?>}` |
| 0x10 | `INFER_OFFER` | C→S | new odd | §10.5, **no content** |
| 0x11 | `INFER_ACCEPT` | S→C | same | `{"attemptId","servedModel","queuePos","estStartMs"}` |
| 0x12 | `INFER_DECLINE` | S→C | same | `{"attemptId","code","retryAfterMs"}` |
| 0x13 | `INFER_BODY` | C→S | same | raw bytes: the OpenAI JSON body, byte-exact |
| 0x14 | `INFER_HEAD` | S→C | same | `{"attemptId","status","servedModel","engine":"local"}` |
| 0x15 | `INFER_CHUNK` | S→C | same | raw bytes: SSE event bytes, or the complete JSON body when not streaming |
| 0x16 | `INFER_END` | S→C | same | `{"attemptId","status","usage":{"promptTokens","completionTokens"},"ttftMs","totalMs","terminal":"done\|cancelled\|thermal\|oom\|error"}` |
| 0x17 | `CANCEL` | C→S | same | `{"attemptId","reason"}` |
| 0x20 / 0x21 | `STATE_REQ` / `STATE` | C→S / S→C | new odd | defined in `router.md`; needs scope `state` |
| 0x22 / 0x23 | `MANIFEST_REQ` / `MANIFEST` | C→S / S→C | new odd | `{"challenge":<b64u 32B>}` / defined in `manifest.md`; needs scope `manifest` |
| 0x30–0x34 | `PAIR_*` | §4.4 | 1 | pairing connections only |
| 0x40 | `REVOKE_NOTICE` | both | 0 | `{"v":1,"reason":"user"}` (courtesy; §8.3) |
| 0x41 | `REVOCATION_HINT` | C→S | new odd | `{"v":1,"target":"<nodeId>","reason":"lost\|stolen\|replaced\|other","ts"}`; needs scope `revoke-hint` |
| 0x42 | `LOCATOR_HINTS` | both | 0 | `{"peers":[{"nodeId","endpoints":[…],"observedAt"}]}` (§6.2) |

**Mesh error codes.** These travel on the peer channel only and are separate from the frozen §5.6 enum: `PEER_NOT_PAIRED, SCOPE_DENIED, MODEL_NOT_OFFERED, PEER_BUSY, PEER_THERMAL, PEER_BATTERY, PEER_USER_ACTIVE, FRAME_TOO_LARGE, PROTOCOL_ERROR, VERSION_UNSUPPORTED, CLOCK_SKEW, DUPLICATE_ATTEMPT, PAIRING_WINDOW_CLOSED, PAIRING_PROOF_INVALID, PAIRING_REFUSED`. Every `message` is text written by asom. Exception strings are never echoed, which carries forward the v1 audit's key-leak lesson.

**Limits (defaults, per peer; r4: superseded by R4-T-08):** 4 concurrent streams; `INFER_BODY` ≤ 8 MiB; 8 unauthenticated connections in flight per listener; 10 handshakes per minute per source address; idle close after 5 min without a stream; `PING` only while a stream is open (battery).

### 3.4 Checked against iOS and Apple platforms

| Concern | Finding | Consequence |
|---|---|---|
| Self-signed pinned TLS | ATS does not apply to Network.framework. Under ATS, URLSession can tighten trust but not loosen it [V3] | iOS and macOS use `NWConnection`/`NWListener` with `sec_protocol_options_set_verify_block` running `verifyPeerChain` |
| TLS client identity | Secure Enclave key as TLS identity is unverified [V9] | Two-level keys (§2.1). The leaf is a software key in the keychain. Building a `sec_identity` from it is spike **S-A2** |
| Outbound LAN connections | Need the Local Network privilege. Traffic over VPN interfaces is exempt (local network = broadcast-capable interface: Wi-Fi or Ethernet, not cellular or VPN) [V1] | A requester-only iPhone that reaches peers only over the tailnet never needs the Local Network prompt. LAN peers need `NSLocalNetworkUsageDescription` |
| Accepting incoming TCP | Does **not** need the Local Network privilege on Apple platforms [V1] | A foreground iOS provider can accept without the prompt, but mDNS advertising would need it |
| Background | Apple DTS advises closing listeners when the app becomes eligible for suspension; a suspended app cannot serve [V8]. In the background, a local-network operation with an undetermined privilege is silently denied [V1] | iOS is a **requester, or a foreground-only provider**. The listener closes on `willResignActive`. In-flight streams get `GOAWAY{"reason":"shutdown"}` and the requester fails over (`router.md`) |
| macOS asom-desktop | Local Network privacy exists since macOS 15. launchd *daemons*, root and Terminal/SSH tools are allowed automatically; launchd *agents* are not [V2] | Recommend a per-user launchd **agent** (NIK stays out of a root process) with `AssociatedBundleIdentifiers` and an Apple-issued signature, and accept one prompt. Running as a root daemon avoids the prompt but makes the node a higher-value target |
| Bonjour | Register, browse and resolve need the Local Network privilege and `NSBonjourServices`. The multicast entitlement is needed only for arbitrary or all service types [V1] | Relevant only if mDNS is approved (§6.3) |

### 3.5 Separation from the v1 app-facing API

- The app-facing API stays on `127.0.0.1:11435` and is unchanged. It **never accepts a mesh identity**. The peer listener **never accepts a bearer token** and never serves `/v1/*`.
- The peer protocol is never bound to loopback. A local app therefore cannot pose as a peer by connecting to a local port. (This rules out one tempting design: terminating TLS in-process and forwarding plaintext to a loopback port.)
- Distinct code paths, distinct listeners and a structural test (§15, law L11).

### 3.6 What the transport does NOT guarantee

- Confidentiality stops at the peer process. **The serving peer necessarily sees the plaintext prompt and output.** No consumer-device mechanism is used here to prevent that.
- It does not hide from a LAN or overlay observer *that* two nodes talk, when, or how much (sizes and timing).
- It does not protect against a compromised endpoint.
- It does not resist harvest-now-decrypt-later unless hybrid key exchange is in use (§2.8, unverified).
- Correctness rests on `verifyPeerChain` being the only trust path. A single "accept all" trust manager anywhere defeats all of it (risk R-1).

---

## 4. Pairing (QR, fingerprint-pinned, consent on both devices)

### 4.1 Roles and preconditions

- **D (displayer):** shows the QR and must be able to *listen*. Opening a pairing window is a user action. If the user has not switched the peer listener on, it is started for the window only, on the current LAN or overlay address, and stops when the window closes (§12.2 wording).
- **S (scanner):** scans or pastes, and connects **outbound only**. An iPhone or any requester-only node can always be S. It never needs a listener to pair.
- Both nodes have generated a NIK (§2.5) and have the mesh feature enabled.

### 4.2 QR payload grammar (exact)

```
asom-pair-uri = "asom-pair:1?" param *( "&" param )
param  = "k=" b64u(pin_D)               ; required, 43 chars
       / "a=" endpoint *( "," endpoint ) ; required, 1..4 endpoints, in preference order
       / "s=" b64u(secret)              ; required, 32 random bytes, 43 chars
       / "x=" unix-seconds              ; required, window expiry (<= open + 120 s)
       / "n=" pct-encoded UTF-8         ; required, D's display name, <= 32 chars
endpoint = ipv4 ":" port / "[" ipv6 "]" ":" port     ; literals only, never DNS names
```

Worked example (computed; the pins are **illustrative**: SHA-256 of fixed labels, not real SPKIs):

```
asom-pair:1?k=jZcpQhuRMg6yNp81ynXIGjfGCcZeStuotMWTOtRFPgs&a=192.168.1.40:11436,100.101.7.9:11436&s=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&x=1790000120&n=Dell%20tower
```

That is 170 characters, well inside a low-error-correction QR. D displays it with `FLAG_SECURE` on Android (blocks screenshots and recording). On iOS it blurs when `UIScreen.isCaptured` is true. The same string can be copied as text for headless or no-camera pairing (§4.9).

### 4.3 Message flow

```
 User@D          D (listener)                                     S (scanner)            User@S
   | "Add device"  |                                                   |                     |
   |-------------->| open window W{secret, x=now+120s, tries=0}        |                     |
   |               | ensure listener on eligible addrs (§12)           |                     |
   |   QR shown    |                                                   |                     |
   |               |                         scan/paste --------------->| parse + validate    |
   |               |                                                   | refuse public addrs |
   |               |                                                   |-- "Connect to       |
   |               |                                                   |   'Dell tower'?" -->|
   |               |                                                   |<------- confirm ----|
   |               |<====== TLS 1.3 (S verifies chain, pin == k) ======|                     |
   |               |  D: unknown pin + window open -> PAIRING mode      |                     |
   |               |<----------------- PAIR_HELLO ---------------------| {pin_S implied by TLS, nonce_S, proof, name_S}
   |               | check window, proof, pin_S not REVOKED;            |                     |
   |               | window -> CONSUMED (single use)                    |                     |
   |               |------------------ PAIR_CHALLENGE ---------------->| {nonce_D, name_D}   |
   |               |  both compute SAS = f(pin_D,pin_S,nonce_S,nonce_D) |                     |
   | consent sheet |                                                   | consent sheet       |
   | "Pair with    |                                                   | "Pair with 'Dell    |
   |  'Madhav's    |                                                   |  tower'? Code       |
   |  iPhone'?     |                                                   |  865 412 must match |
   |  Code 865 412"|                                                   |  the code on Dell"  |
   |-- approve --->|                                                   |<---- approve -------|
   | + class/scopes|------------------ PAIR_DECISION ------------------>|                     |
   |               |<----------------- PAIR_DECISION ------------------|                     |
   |               |  both approved -> D writes PAIRED row             |                     |
   |               |------------------ PAIR_COMMIT ------------------->| S writes PAIRED row |
   |               |<----------------- PAIR_COMMIT_ACK ----------------|                     |
   |               | close connection; window CLOSED; listener reverts  |                     |
```

### 4.4 Pairing messages

All on stream 1 of a pairing-mode connection. Any other frame type on that connection is a `PROTOCOL_ERROR` and the connection closes.

```jsonc
// 0x30 PAIR_HELLO   S -> D
{ "v": 1,
  "nonceS": "<b64u 32B>",
  "proof": "<b64u 32B>",                 // §4.5
  "name": "Madhav's iPhone",             // self-reported, <= 32 chars, user-editable before sending
  "platform": "ios",                     // self-reported: android|ios|ipados|macos|linux
  "keyTier": "secure-enclave",           // self-reported (§2.5)
  "endpoints": [] }                      // S's own listener endpoints, if S has one
// 0x31 PAIR_CHALLENGE   D -> S
{ "v": 1, "nonceD": "<b64u 32B>", "name": "Dell tower", "platform": "linux", "keyTier": "file" }
// 0x32 PAIR_DECISION   both directions, sent when the local user decides
{ "v": 1, "approve": true }              // or false -> PAIRING_REFUSED, both sides abort
// 0x33 PAIR_COMMIT   D -> S, sent only after D has a local approve AND S's approve
{ "v": 1, "transcript": "<b64u 32B>",    // §4.5, both sides compare; mismatch -> abort, no row
  "locSeed": "<b64u 32B>" }              // random; only used if mDNS locate is approved (§6.3)
// 0x34 PAIR_COMMIT_ACK   S -> D
{ "v": 1, "transcript": "<b64u 32B>" }
```

The class and scopes each user picks are **local** and are not sent in `PAIR_DECISION`. Each side's registry is its own policy. The peer learns what it was granted from `HELLO_ACK` on its first session (§5.2).

### 4.5 Proof, SAS and transcript (exact)

```
pin_D, pin_S : 32-byte SHA-256 of each NIK SPKI; pin_S is taken from the TLS-presented chain, never from a message
proof      = HMAC-SHA256(key = secret, msg = "asom-pair-v1/proof" || 0x00 || pin_D || pin_S || nonce_S)
SAS        = uint32_be( SHA-256("asom-pair-v1/sas" || 0x00 || pin_D || pin_S || nonce_S || nonce_D)[0..4] ) mod 1_000_000
             displayed as two groups of 3 digits, zero-padded
transcript = SHA-256("asom-pair-v1/transcript" || 0x00 || pin_D || pin_S || nonce_S || nonce_D)
```

**Worked vector** (computed this session; illustrative pins as in §4.2):

| Input | Value |
|---|---|
| `pin_D` | `jZcpQhuRMg6yNp81ynXIGjfGCcZeStuotMWTOtRFPgs` (= SHA-256("asom-vector/spki/D")) |
| `pin_S` | `AyXFqjFWNe-eItKRiUlURLOfmG2TQ2Q00gp5wPPnSH0` (= SHA-256("asom-vector/spki/S")) |
| `secret` | bytes 0x00..0x1f → `AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8` |
| `nonce_S` / `nonce_D` | 32 × 0xA5 / 32 × 0x5A |
| **proof** | `becQGXmCCTwCLN4BK4hj0qa3AW-AT9DgDikj7RA_baM` |
| proof with the pins swapped (must be rejected) | `r3iMlWpfvBBdchUUFGHUoCSNIi283ymNyEqeqNjoAC8` |
| **SAS** | `865 412` |
| **transcript** | `a7cfd74766ca9d6eab6393ad20c52a1e103e394678101b503f1e48c6f473ffb1` |

**Why this binding is enough without a TLS exporter.** JSSE has no public keying-material-exporter API as far as I know, so exporter-based channel binding would not be portable. It is not needed. S pinned D's key from the QR, so S's TLS session ends at the real D. A relay cannot present `pin_S`'s certificate without S's key, so it cannot reuse S's proof, which contains `pin_S`. D checks `pin_S` against the certificate TLS actually presented. **r4 (R4-T-10): the r3 sentence "Ordering stops SAS grinding: S commits `pin_S` and `nonce_S` before D reveals `nonce_D`, so an attacker cannot search for a key that produces a chosen code" is deleted.** It was false for an attacker in D's role. SAS grinding is stopped by commit-before-reveal: `PAIR_HELLO` carries a commitment to `nonce_S`, which S opens only with its approval, and D's approval is a typed code (design T7).

### 4.6 State machines

**Pairing window on D**

```
CLOSED --(user: Add device)--> OPEN{secret, expiry, tries=0}
OPEN --(valid PAIR_HELLO: proof ok, pin_S not REVOKED)--> CONSUMED{pin_S, nonce_S}      [single use]
OPEN --(invalid proof)--> OPEN{tries+1};  tries == 3 --> CLOSED
OPEN --(pin_S is REVOKED)--> CLOSED  + UI: "a revoked device tried to pair; Forget it first to allow this"
OPEN --(expiry | user cancel)--> CLOSED
CONSUMED --(send PAIR_CHALLENGE)--> AWAIT_DECISIONS (timeout 120 s from CONSUMED)
AWAIT_DECISIONS --(any approve=false | timeout | connection lost)--> CLOSED (no row written)
AWAIT_DECISIONS --(local approve AND remote approve)--> COMMITTING
COMMITTING --(write PAIRED row durably, then send PAIR_COMMIT)--> AWAIT_ACK
AWAIT_ACK --(ACK with matching transcript)--> CLOSED (done)
AWAIT_ACK --(timeout 10 s)--> CLOSED; row stays PAIRED with confirmedByPeer=false (UI: "Dell may not have finished pairing")
```

A second connection with an unknown pin while the window is `CONSUMED` is refused at the TLS layer. It also raises a UI warning that someone else holds the QR (§4.8).

**Pairing on S**

```
IDLE --(scan/paste; syntax ok; now < x+60s; all endpoints eligible (§6.6))--> CONFIRM_CONNECT
CONFIRM_CONNECT --(user confirms)--> DIALING(endpoint_i)
DIALING --(TLS ok, pin == k)--> SENT_HELLO ;  --(pin mismatch | fail)--> DIALING(next) ; none left --> FAILED
SENT_HELLO --(PAIR_CHALLENGE)--> AWAIT_DECISIONS (show SAS)
SENT_HELLO --(ERROR PAIRING_*)--> FAILED ("window closed or already used")
AWAIT_DECISIONS --(local approve AND remote approve AND PAIR_COMMIT transcript matches)--> write PAIRED row, send ACK --> DONE
AWAIT_DECISIONS --(any deny | timeout | mismatch)--> FAILED (no row)
```

### 4.7 The peer registry

Persisted locally, one row per pin. Separate from the v1 `pairings` table, which is for apps. The v1 AIDL status codes are left alone.

```
peers(
  pin            BLOB(32) PRIMARY KEY,   -- authentication input (R1)
  alg            TEXT  = 'p256',
  nodeTag        TEXT,
  label          TEXT,                   -- initially the peer's self-reported name, marked "(self-reported)"; user-editable
  platform       TEXT,                   -- self-reported
  class          TEXT  CHECK in ('own','other'),
  status         INT   CHECK in (2 /*PAIRED*/, 3 /*REVOKED*/, 4 /*SUSPENDED*/),
  inboundScopes  TEXT,                   -- JSON array (§7.1)
  routeEnabled   INT,                    -- may I send work to this peer? (§7.1)
  routeCeiling   TEXT  CHECK in ('D1','D2'),
  limits         TEXT,                   -- JSON (§7.2)
  endpoints      TEXT,                   -- JSON [{addr,port,via:'lan'|'overlay',source:'qr'|'hello'|'hint'|'mdns'|'user',lastOkAt}]
  locKey         BLOB(32) NULL,          -- §6.3, only if mDNS locate is approved
  pairedAt       INT,  pairTranscript BLOB(32),  confirmedByPeer INT,
  statusChangedAt INT, statusReason TEXT, -- 'user' | 'hint:<nodeTag>' | 'user-restore'
  lastSeenAt     INT,  keyTierClaim TEXT
)
```

**Registry state machine.** Only the transitions listed here exist. Each is marked as local (a user action on this device) or network (a message).

| From → To | Trigger | Local or network |
|---|---|---|
| (absent) → PAIRED | Completed ceremony (§4.6) with the **local** user's approval | local (gated) |
| PAIRED → SUSPENDED | User taps "Pause", or an accepted `REVOCATION_HINT` from an own-class peer holding `revoke-hint` (§8.3) | local, or network (**lowering only**) |
| SUSPENDED → PAIRED | User taps "Restore" | **local only** |
| PAIRED / SUSPENDED → REVOKED | User taps "Revoke" | **local only** |
| REVOKED → (absent) | User taps "Forget" | **local only** |
| any → PAIRED by any message | — | **does not exist (R3)** |
| REVOKED → anything by pairing | — | **refused.** A revoked pin cannot re-pair until it is Forgotten (mirrors v1's REVOKED-blocks-requestPairing fix) |

`authorize(pin, scope) := row != null && row.status == PAIRED && scope in row.inboundScopes`. Everything else, including a status value the code does not recognise, denies.

### 4.8 Attacks on pairing

| Attack | Outcome |
|---|---|
| Attacker photographs the QR and races S | At most one valid proof per window. If the attacker wins, the real S gets `PAIRING_WINDOW_CLOSED` and shows no code. D's consent sheet shows the attacker's name and a code that nothing on S matches. D's UI also warns "another device already used this code" whenever a second unknown-pin connection arrives. **Residual risk:** the user approves on D without looking at S. The design turns a silent attack into one that needs a human mistake; it cannot eliminate it. |
| Malicious QR (poster or message) with the attacker's key | S refuses non-private addresses (§6.6), so the attacker must already be on the user's LAN or tailnet. S asks before dialing and then asks for approval with a SAS. The attacker's server can auto-approve on its side. **Residual risk:** a user who approves on S pairs with an attacker node on their LAN. It gets only the scopes the user grants, and routing to it is off until enabled (§7.1). |
| MITM between S and D | S pins `k`, so a MITM cannot complete TLS as D. A MITM cannot present `pin_S`. |
| Replay of an old `PAIR_HELLO` | The window's secret is single-use, and `nonce_S` plus the pin binding make the proof specific to its window. |
| Brute-forcing the secret | 256-bit secret, 3 tries per window, 120 s window. Not feasible. |
| Revoked device re-pairs with its old key | Refused (§4.6). |
| Revoked device generates a new key and re-pairs | This is a *new identity* and needs a full ceremony with the owner's consent on both devices. D's consent sheet warns: "The name 'Deck' matches a device you revoked on 2026-10-04." That warning is only a hint, because names are self-reported. |

### 4.9 Variants

- **No camera or headless (Linux CLI):** `asom mesh pair-show` prints the URI and an ANSI QR. `asom mesh pair-join '<uri>'` joins, and the SAS confirmation is typed as `y`. The URI must travel over a channel the owner controls (their own terminal or SSH). It carries a live secret for 120 s.
- **Address refresh without re-pairing:** when an already-PAIRED S scans a new QR from D and `k` matches an existing PAIRED row, S only updates D's endpoints. It sends nothing and changes no trust state.

### 4.10 What pairing does NOT guarantee

- It does not guarantee that the other device is owned by the same person. `class` is a declaration each user makes at pairing.
- It does not guarantee that the other node runs genuine asom (§2.7).
- It does not guarantee against a user who approves without comparing codes.
- A half-completed pairing (`confirmedByPeer=false`) is harmless, but it is shown to the user rather than hidden.

---

## 5. Session establishment

### 5.1 State machines

**Client (dialing a PAIRED peer)**

```
IDLE --(router needs peer P)--> LOCATING  (candidate endpoints, §6.1)
LOCATING --(candidates)--> DIALING(e_i)   (§6.6 eligibility check before connect AND after connect: local address must be on an eligible interface)
DIALING --(TCP ok)--> TLS(e_i)            (EXPECT_PAIRED(pin_P))
TLS --(pin mismatch)--> DIALING(e_i+1)    (mark e_i "locator-mismatch", skip it for 10 min)
TLS --(ok)--> HELLO_SENT --(HELLO_ACK within 5 s)--> ESTABLISHED
HELLO_SENT --(ERROR VERSION_UNSUPPORTED | timeout)--> CLOSED
all candidates failed --> UNREACHABLE    (backoff 30 s -> 15 min, same curve as the v1 cooldown)
ESTABLISHED --(GOAWAY | idle 5 min | network change | local status of P != PAIRED)--> CLOSED
```

**Server (peer listener)**

```
LISTENING --(accept; under per-source and global limits)--> TLS (mode chosen per §3.2)
TLS --(verifier ok, ESTABLISHED_SERVER)--> AWAIT_HELLO (5 s) --(HELLO ok)--> ESTABLISHED
TLS --(verifier ok, PAIRING_SERVER)--> PAIRING (only PAIR_* frames; §4.6)
TLS --(verifier fails)--> closed with alert certificate_unknown
ESTABLISHED --(each frame)--> authorize(pin, scope) re-read from the registry (§5.3)
ESTABLISHED --(registry: pin left PAIRED)--> send GOAWAY{revoked|suspended}, cancel in-flight streams, close
```

### 5.2 HELLO and HELLO_ACK

```jsonc
// 0x01 HELLO (C -> S)
{ "v": 1, "minV": 1, "maxV": 1, "proto": "asom-mesh/1",
  "nodeId": "<b64u pin>",            // must equal the TLS-presented pin, else PROTOCOL_ERROR
  "ts": 1790000000000,               // for CLOCK_SKEW diagnostics only; never a security input
  "sessionNonce": "<b64u 16B>",      // session id for the control-plane ledger row (§11.3)
  "name": "Madhav's iPhone",         // sent only to own-class peers; "asom node" otherwise
  "platform": "ios", "sw": "asom-ios/4.0.0",
  "keyTier": "secure-enclave",       // self-reported (§2.5)
  "endpoints": [],                   // this node's listener endpoints, if any (feeds §6.2)
  "features": ["infer.offer", "state", "manifest", "locator-hints"] }
// 0x02 HELLO_ACK (S -> C)
{ "v": 1, "nodeId": "<b64u pin>", "ts": 1790000000150,
  "granted": ["infer", "state", "manifest"],      // the inbound scopes S grants C (§7.1)
  "limits": { "maxConcurrent": 1, "maxBodyBytes": 8388608, "maxTokens": 4096, "rpm": 30 },
  "endpoints": [ { "addr": "192.168.1.40", "port": 11436, "via": "lan" } ],
  "features": ["infer.offer", "state", "manifest"] }
```

Version rule: pick the highest `v` in both ranges. If there is none, send `VERSION_UNSUPPORTED` and close. There is no downgrade below a node's configured `minV`.

### 5.3 Authorization is checked per frame, not per handshake

At every `INFER_OFFER`, `INFER_BODY`, `STATE_REQ`, `MANIFEST_REQ`, `REVOCATION_HINT` and `LOCATOR_HINTS`, the server re-reads `authorize(pin, scope)` from the registry. A registry change listener also pushes `GOAWAY` and closes the peer's sessions the moment its status leaves `PAIRED`. Neither mechanism alone is enough: the first covers races, the second covers long streams. This is the mesh version of v1's "server checks per request", and it is also what makes resumption-bypass bugs harmless [A1].

### 5.4 Replay and freshness

| Artefact | Replay defence |
|---|---|
| TLS records within a session | TLS 1.3 sequence numbers and AEAD |
| TLS handshakes | Fresh ephemeral key exchange. No 0-RTT. No resumption |
| Pairing proof | Single-use window, 120 s, `nonce_S`, pin binding (§4.5) |
| `INFER_*` | `attemptId` (random 128-bit) must be unique per peer. The server keeps a 24 h LRU and answers a repeat with `DUPLICATE_ATTEMPT` (guards double execution and double ledger rows) |
| `REVOCATION_HINT`, `LOCATOR_HINTS` | Authenticated only by the session they arrive on and **never forwarded**, so a hint cannot be replayed from outside a session. `ts` older than 24 h is ignored |
| Manifests | Challenge nonce and expiry, defined in `manifest.md` |

### 5.5 What session establishment does NOT guarantee

It does not guarantee that a peer answering from a *new* address is the same *physical* device as before; it is the same *key*. It does not guarantee liveness beyond the last `PONG`. It does not guarantee clock correctness: a badly wrong clock fails closed (`CLOCK_SKEW`) rather than open.

---

## 6. Locating peers (the roadmap's "no mDNS, static registry", re-examined)

### 6.1 Locator sources, in order

1. **Overlay endpoint** from the registry (tailnet address). Stable, so it does not churn.
2. **Last-known LAN endpoints**, most recently successful first.
3. **Authenticated locator hints** (§6.2).
4. **mDNS locate** (§6.3), **only if the owner approves it and the user switches it on**.
5. **Manual:** the user edits an address or re-scans D's QR (address refresh, §4.9).

Every candidate goes through `verifyPeerChain(EXPECT_PAIRED(pin))`. A wrong address costs one failed handshake and nothing else.

### 6.2 Authenticated locator hints (new; no multicast; recommended for v4.0)

- Within an established session, `HELLO` and `HELLO_ACK` carry the sender's own current listener endpoints. They are authenticated because they arrive inside mutual TLS. The receiver updates that peer's `endpoints` with `source:'hello'`.
- An **own-class** peer may also send `LOCATOR_HINTS` about *other own-class* peers it has recently reached. The receiver merges these as `source:'hint'` for rows it already has. A hint can never create a row.
- **Trust argument:** a hint is an address, and addresses are not trusted (R1). A lying hint costs one failed handshake. That is why accepting hints from any own-class PAIRED peer is safe.
- **Privacy:** hints about third nodes are never sent to other-class peers.
- This fixes most churn with no multicast at all, as long as one node is stable (for example the Dell) or reachable over the overlay.

### 6.3 mDNS/Bonjour used only to locate already-paired peers: evaluation

**The claim.** Suppose an adversary controls every mDNS response on the LAN. Compared with the no-mDNS design, (a) the set of peers a node authenticates is the same, and (b) nothing more is disclosed before authentication, except that the listener is advertising.

**Argument.**
1. Authentication and authorization take only `(pin, registry row)` (R1, R2). An mDNS answer supplies at most an `(addr, port)` candidate, which is never an input to either.
2. The requester is the TLS client and verifies the server chain *before* sending its own certificate or any frame (§3.2). Dialing a hostile address reveals a ClientHello with no SNI, the ALPN `asom-mesh/1` and an ephemeral key share. None of these identify the requester, though they do reveal that it runs asom.
3. Candidates are dialed only if their advertisement carries a tag that matches a paired peer. Tags are `trunc64(HMAC(locKey, "asom-loc-v1/tag" || 0x00 || u64be(epochHour)))`. `locKey = HMAC(locSeed, "asom-loc-v1/key" || 0x00 || pin_D || pin_S)`, and `locSeed` travels only inside the pairing TLS channel (§4.4), so a photographed QR does not reveal it. Without `locKey`, a tag is indistinguishable from random. Discovered instances without a matching tag are **never shown, never dialed, never counted**. That is the line between "locating paired peers" and the open discovery the stop-line forbids (roadmap §11).
4. Denial of service (suppressed or poisoned answers) falls back to sources 1–3 and 5. An attacker who controls mDNS on the LAN can already drop packets there, so nothing is lost.

Worked tag vector (computed): with `locSeed` = 32 × 0x3C and the §4.5 illustrative pins, `locKey = 20dd298e…3338f46e`. At `epochHour = 497222`, `tag = 200e8ba86caf4664`.

**What mDNS adds anyway (the real costs).**
- **Presence disclosure.** The constant service type `_asom-mesh._tcp` tells anyone on the LAN that an asom listener is present. The instance name is random per network join (never the device name, which is Bonjour's default and must be overridden). The TXT record is padded to exactly 8 tags so the peer count stays hidden, and tags rotate hourly, so a non-peer cannot link an instance across networks or epochs.
- **Background multicast.** The network events are periodic, which means battery use and ledger rows (§11.3, one `locate` row per hour).
- **Permissions.** On iOS, Bonjour needs the Local Network privilege and `NSBonjourServices`, but not the multicast entitlement for a fixed service type [V1]. Any LAN peer connection already triggers the same privilege, so for a LAN user the extra permission cost is roughly one Info.plist entry. On Android, targetSdk 37+ needs `ACCESS_LOCAL_NETWORK` for any LAN socket and for NsdManager alike [V7], so again no extra cost. NsdManager is part of the framework, not GMS, so invariant 8 holds. (The brief pins targetSdk 35 today, so the permission is not yet required.)
- **Browse on iOS happens in the foreground only.**

**When it becomes unsafe.** Any code path that uses an mDNS record to *pre-select trust*, such as showing "nearby devices to pair" or auto-pairing, turns this into open discovery. Law L12 (§15) forbids that structurally.

**Verdict.** The refinement is **safe on the trust argument above**. It does not by itself breach the stop-line, whose text is "no peer discovery beyond explicitly paired, owner-controlled devices". But it reverses an explicit roadmap line ("no mDNS"), so it is **an owner decision (OD-3)**. **Recommendation:** ship v4.0 with sources 1–3 and 5, which need no multicast. Approve mDNS locate as an **off-by-default** option for a later v4 step. It is the only zero-infrastructure fix for the case that remains: a two-device LAN mesh with no stable node and no overlay, whose provider changed address.

### 6.4 DHCP churn: how much remains after §6.2

This depends on [A4] (home routers keep stable leases for devices whose per-network MAC is stable). The remaining failure is one peer that changed address while no other node was reachable. The UI says so plainly ("Deck not found at 192.168.1.23; re-scan its code or turn on network locating") and never fails silently.

### 6.5 The overlay case (Tailscale-class)

- The registry records the peer's overlay endpoint, which arrives in the QR's `a=` or in `HELLO`. Overlay addresses are stable, so churn goes away and off-LAN reachability comes for free.
- **Trust is unchanged.** The overlay authenticates *devices on the tailnet*. asom still pins keys. A compromised or hostile coordination server (Tailscale Inc. or Headscale [V10]) can route an overlay address to an attacker node. The pin check fails, so the result is denial of service, never impersonation.
- **Sovereignty cost, stated honestly:** the coordination server learns which devices exist and when they connect. Relays (DERP) see encrypted metadata. Recommend Headscale for owners who want no third-party operator [V10].
- mDNS is assumed not to cross the tailnet [A5]. It is not needed there.
- **The listener must never be published through overlay features that expose ports to the internet** (for example Tailscale Funnel or Serve). asom cannot detect that configuration. mTLS pinning still holds, but the "never a public interface" invariant would be broken by configuration, so the docs must warn against it (risk R-9).

### 6.6 Address eligibility (outbound dialing and listener binding)

```
eligible(addr, iface) =
     addr in 10/8, 172.16/12, 192.168/16, 169.254/16, fc00::/7, fe80::/10(scoped)   and iface.kind in {wifi, ethernet}
  or addr in the overlay range                                                       and iface is the user-selected overlay (VPN/tun) interface
never eligible: IPv6 global unicast, public IPv4, any address on a cellular interface,
                wildcard (0.0.0.0, ::), loopback (the peer protocol never runs on loopback, §3.5)
```

The **interface** check matters because carrier-grade NAT uses the same `100.64.0.0/10` block as Tailscale [A6]. An address in that block reached over cellular is *not* the overlay. Dialing checks the socket's local address after connecting and before TLS. On Android the socket is bound to the selected `Network`.

---

## 7. Authorization: roles and scopes, per peer, asymmetric

### 7.1 Two independent directions per peer row

**Inbound scopes** (what this peer may ask of **me**):

| Scope | Grants | Default for own class | Default for other class (if OD-2 approves) |
|---|---|---|---|
| `infer` | `INFER_*`, served by **my local engine only** (R6) | on if I have a local engine and the user enabled "offer compute" | off |
| `state` | `STATE_REQ`: my live situation (router payload) | on | off (coarse `accepting:true/false` only, via `INFER_DECLINE`) |
| `manifest` | `MANIFEST_REQ`: my signed capability manifest | on | on |
| `revoke-hint` | `REVOCATION_HINT`: may *suspend* other peers on me (§8.3) | on | **never grantable** |

**Outbound controls** (what **I** do toward this peer):

| Control | Meaning | Default |
|---|---|---|
| `routeEnabled` | My router may place my apps' requests on this peer | own: on once the user enables "use my devices"; other: off |
| `routeCeiling` | Highest data class I will send it (§10.2) | own: `D1`; other: `D2` |
| `shareLocatorHints` | Send it `LOCATOR_HINTS` about my other own peers | own: on; other: **never** |

**Roles** follow from the scopes rather than being declared. A peer is a *provider to me* if `routeEnabled` is on here and it granted me `infer`. I am a *provider to it* if I granted it `infer`. An iPhone that grants nothing and enables routing is a pure requester, and it binds no listener (§12.3).

### 7.2 Serving conditions and limits (inbound `infer`)

```jsonc
"limits": {
  "allowedModels": ["*"],            // or explicit ids; "*" = whatever is loaded or loadable
  "maxConcurrent": 1,                // the engine is single-flight (roadmap v2 P3)
  "maxBodyBytes": 8388608, "maxTokens": 4096, "maxContext": 8192, "rpm": 30,
  "servingConditions": {
    "requireCharging": true,         // default true on phones and tablets, false on desktops
    "minBatteryPct": 50,
    "thermal": "RUN_ONLY",           // v2 P4 governor state must be RUN
    "notWhileUserActive": true,      // phone in use -> PEER_USER_ACTIVE
    "localFirst": true               // my own apps always pre-empt peer work in the v2 queue
  }
}
```

### 7.3 Decision tables

**Server side, on `INFER_OFFER` from pin P.** Evaluated in order; the first failing row decides.

| # | Check | Fail result |
|---|---|---|
| 1 | `registry[P].status == PAIRED` | `PEER_NOT_PAIRED` + close |
| 2 | `infer ∈ inboundScopes` | `SCOPE_DENIED` |
| 3 | `attemptId` not seen from P in the last 24 h | `DUPLICATE_ATTEMPT` |
| 4 | `offer.model` allowed and loadable | `MODEL_NOT_OFFERED` |
| 5 | `offer.promptBytes ≤ maxBodyBytes`, `maxTokens ≤` limit | `FRAME_TOO_LARGE` |
| 6 | serving conditions (charging, battery, thermal, user activity) | `PEER_BATTERY` / `PEER_THERMAL` / `PEER_USER_ACTIVE` + `retryAfterMs` |
| 7 | concurrency and rpm | `PEER_BUSY` + `retryAfterMs` |
| 8 | `class == other` ⇒ `retain` forced to `none` (may only be downgraded, never upgraded) | — |
| ✓ | `INFER_ACCEPT`; write the server ledger row (§11.2); wait for `INFER_BODY`; execute on the **local engine only** | — |

**Requester side, eligibility of peer P for a request from app A** (pure function; `router.md` calls it before scoring):

```kotlin
fun meshEligibility(req: RouteQuery, app: AppPolicy, peer: PeerRow, crossOwnerEnabled: Boolean): Eligibility {
    if (req.policy == Policy.LOCAL_ONLY) return Deny("local-only means this device only")      // frozen v1 meaning kept
    if (app.mesh == MeshPolicy.OFF) return Deny("app not allowed on other devices")
    if (peer.status != PeerStatus.PAIRED) return Deny("peer not paired")
    if (!peer.routeEnabled) return Deny("routing to peer disabled")
    val dc = classify(req, app)                                   // §10.2
    if (dc == D0) return Deny("device-only data")
    when (peer.clazz) {
        OWN   -> if (dc > peer.routeCeiling) return Deny("above peer ceiling")
        OTHER -> if (!crossOwnerEnabled || app.mesh != MeshPolicy.OWN_AND_OTHER ||
                     dc != D2 || peer.routeCeiling != D2) return Deny("other-owner not permitted")
    }
    val redact = peer.clazz == OTHER || app.redactForOwnDevices          // v3 egress firewall
    val retain = if (peer.clazz == OWN && app.allowPeerVerbose) Retain.OWNER_VERBOSE else Retain.NONE
    return Allow(redact = redact, retain = retain, dataClass = dc)
}
```

### 7.4 Never available over the mesh, in any version of this design

BYOK keys or key presence, the ledger or verbose bodies, model files, app identities (package names), the pairing registry, the app-facing API, cloud routing on a peer's behalf, and forwarding to a third node. Adding any of these is a new design session, not a scope flag.

---

## 8. Revocation, suspension, key loss and re-pairing

### 8.1 Revoke (local, immediate)

```
user taps Revoke on peer P:
  1. registry[P].status = REVOKED, statusReason='user'     (durable before step 2)
  2. registry listener -> for each session with P: send GOAWAY{"reason":"revoked"}, cancel streams, close
  3. in-flight attempts from P: engine cancelled; server ledger rows finalised with status 'revoked-mid-stream'
  4. in-flight attempts TO P: requester treats them as a peer failure (router failover); rows finalised
  5. if P is reachable: send REVOKE_NOTICE (courtesy only; nothing relies on its delivery)
  6. if routing hints are enabled: offer "Tell my other devices" -> REVOCATION_HINT to each own peer (§8.3)
From now on P's TLS handshakes fail at verifyPeerChain (not PAIRED), and P's pairing attempts are refused (REVOKED).
```

### 8.2 Suspend and restore

"Pause" is local, reversible and does not authorize: SUSPENDED → PAIRED happens only through "Restore" on this device. It exists so that revocation hints (§8.3) can fail closed without being destructive.

### 8.3 Revocation hints (propagation without central authority)

With no CA and no server, revocation is per node: a stolen phone has to be revoked on every other node. Hints make that fast while keeping R3.

- An own-class peer R that holds `revoke-hint` on me sends `REVOCATION_HINT{target: X}`.
- If `X` is PAIRED on me, I move it to **SUSPENDED** (not REVOKED), close its sessions, and notify: "Dell says your iPhone was revoked (stolen). Revoke it here too, or Restore?"
- A hint can only lower trust. It is never forwarded (one hop). It cannot target me. The target must already be in my registry.
- **Abuse case:** a compromised own peer can suspend my other peers. That is a denial of service which the user sees and can undo with one tap per peer. It is the fail-safe direction, and preferable to the stolen-phone case where nothing propagates.
- `REVOKE_NOTICE` from P tells me that P revoked *me*. I stop routing to P and show it. It **does not** change P's status on my side, because a notice cannot be used to change trust in either direction without a local decision.

### 8.4 Key loss and replacement

- **Key loss** (factory reset, app data cleared, iPhone restored to a new device where `ThisDeviceOnly` keys do not migrate) means **a new identity**. Every peer sees an unknown pin. Re-pair through the full ceremony. The consent sheet warns when the new device's name matches an existing or revoked row and offers "Revoke the old 'Deck' too".
- **No in-band NIK rotation in v4.** A "rotate to key K2, signed by K1" message would let whoever stole K1 move trust to their own key, and that would *survive* the owner later revoking K1 on peers that had already accepted the rotation. Re-pairing costs one QR scan and has no such hole. Leaf rotation (§2.6) needs no peer interaction.
- A peer's node certificate never expires. Revocation is the only way to invalidate it, exactly as with v1 tokens ("tokens never expire; revocation is the only invalidation").

### 8.5 Fail-closed laws

Laws L1–L7 in §15. The key ones: no sequence of network inputs reaches PAIRED without a local approval event; REVOKED is absorbing except for a local Forget; an unknown status denies.

---

## 9. What a compromised peer can and cannot do

### 9.1 A compromised peer acting as a provider for me (I send it work)

| It **can** | It **cannot** |
|---|---|
| Read every prompt I routed to it, and retain or exfiltrate it, whatever `retain` says | Obtain my BYOK keys: they are never on the mesh (R7, invariant 4) |
| Return wrong, low-quality or adversarial outputs, including prompt-injection payloads aimed at my app | Spend my cloud keys: it never receives them, and requests are local-engine only (R6) |
| Claim a different `servedModel` than it actually ran | Impersonate another peer: keys are distinct and pinned |
| Lie in its manifest or state to attract traffic (`manifest.md`, `router.md` cross-check claims against observed performance) | Un-revoke itself or raise its own scopes (R3) |
| Learn my usage pattern: when I'm active, request sizes, models chosen | Learn which app on my device sent a request: app identity is not on the wire (§11.2) |
| Waste my time with slow, stalling or hung responses | See prompts in data classes it is not eligible for (§10). With offer/accept, it never receives a body it declined |
| As own class: suspend my other peers with hints (reversible DoS, §8.3) and learn my other own peers' addresses (hints) | Pair new devices to me: pairing needs a window opened locally on me |

### 9.2 A malicious requester (a peer I granted `infer`)

It can use my compute, battery and thermal budget within its limits (§7.2), probe which models I hold, send adversarial inputs to my tokenizer and engine, and try cache-timing probes against other requesters' prompts. The last is defeated by §10.6's partitioning. It **cannot** reach my app-facing API, my keys, my ledger, my files or my cloud providers. It cannot see other peers' prompts or outputs, and it cannot make me forward work anywhere.

### 9.3 A stolen device that is still running

Its NIK does not require user authentication (§2.5), so a locked but running phone keeps its identity. It can keep **requesting** from my providers, which spends their compute and is bounded by limits, and it keeps receiving whatever the router sends it *as a provider*. Its exposure ends only when each peer revokes it, which hints speed up (§8.3). The 14-day leaf lifetime does **not** bound this, because the NIK can mint new leaves. The design limits the blast radius (R7) rather than preventing access. **What a stolen node can reach is a design choice. How long it can reach it depends on the user noticing.**

---

## 10. The privacy problem: prompt content on another device

### 10.1 Peer classes

| Class | Meaning | Who decides | Status |
|---|---|---|---|
| `own` | "This is my device." | Each user, at pairing, on their own device | Default and only class in v4.0 |
| `other` | "Someone else's device (household, friend)." | Each user, at pairing | **Designed but disabled pending OD-2.** The roadmap stop-line says "owner-controlled devices" |

The class is a **declaration**. It cannot be verified cryptographically. A user can label a friend's phone `own`. The stop-line is enforced by what the product offers (no discovery, no incentives, no relaying), not by any ability to detect ownership.

### 10.2 Data classes

| Class | Meaning | Derived when |
|---|---|---|
| `D0` device-only | Must not leave this device (no peer; cloud eligibility is unchanged and governed as before) | `X-Asom-Policy: local-only`; app `mesh = off`; app marked "sensitive"; or the v3 detector finds secret-class entities (keys, credentials) and the app's rule is "secrets stay on device" |
| `D1` own-devices | May go to own-class peers | App `mesh = own` (the default once mesh is enabled) |
| `D2` shareable | May also go to other-class peers | App `mesh = own+other` and OD-2 approved |

`classify(req, app)` = the **most restrictive** class that any applicable rule yields.

### 10.3 Per-app policy against peer class (requester side)

Per-app `mesh` is a new column in the v2.5 per-app policy table (Hotspot tab). Global default: **off** until the user switches on "Use my devices". After that, new apps default to `own`, and apps with the v2.5 **cloud ban** stay `off` until the user sets them individually, because a cloud-banned app is most likely one whose owner wants it kept on the device.

| App `mesh` policy | own-class peer | other-class peer | Redaction (v3 firewall) | `retain` sent |
|---|---|---|---|---|
| `off` | never | never | — | — |
| `own` | allowed (D1) | never | off (per-app toggle "redact for my devices too") | `none`, or `owner-verbose` if the app allows it |
| `own+other` | allowed | allowed only for D2, only if OD-2 approved | **always** for other class | always `none` for other class |
| any + request `local-only` | never | never | — | — |

`X-Asom-No-Train` has no mesh effect: peers run local engines, which do not train. The frozen meaning of `local-only`, this device only, is **kept**. An app that wants "never cloud, but my Dell is fine" needs the optional new policy value `own-devices` (contract delta C-6, owner sign-off).

### 10.4 Sensitive-prompt handling

1. Before an offer is made, the requester applies the **v3 egress firewall** exactly as it does for cloud-bound bodies: reversible placeholders, with re-substitution on the response. It always runs for other-class peers, and for own-class peers when the app opts in.
2. If the detector finds a *secret-class* entity (API-key or credential patterns) and the app's rule says so, the request becomes `D0`. It then never goes to a peer, whatever the policy.
3. The number of redactions is reported through the existing `X-Asom-Redacted` header (v3).

**This does not guarantee** that sensitive content is caught. Regex-grade detection misses a lot, and NER is optional (roadmap v3). Classification limits exposure. It is not a guarantee.

### 10.5 Offer, then accept, then body (R5)

```jsonc
// 0x10 INFER_OFFER (C -> S): metadata only, no content
{ "attemptId": "<b64u 16B>",           // random per attempt (§11.3)
  "op": "chat",                        // chat | completions | embeddings
  "model": "llama-3.1-8b-instruct",    // concrete id; virtual selectors are resolved by the requester
  "stream": true,
  "promptBytes": 5120, "estTokensIn": 1300, "maxTokens": 1024,
  "dataClass": "D1", "retain": "none", // none | owner-verbose (own class only)
  "deadlineMs": 60000 }
```

The body (`INFER_BODY`) is sent only after `INFER_ACCEPT`. A peer that is busy, hot, on battery or lacking the model never receives the prompt. Cost: one LAN round trip, typically a few milliseconds (router accounts for it).

### 10.6 What the serving peer may retain or log (normative for conforming nodes)

| Item | `retain: none` | `retain: owner-verbose` (own class only) |
|---|---|---|
| Metadata ledger row (§11) | **required** | **required** |
| Request and response bodies | **never persisted**. Verbose capture is suppressed for this attempt even when the node's verbose mode is on | may be captured by the serving node's own verbose mode (24 h TTL, persistent notification, as in v1 §9) |
| Diagnostics and logs | no content, same redaction law as v1 | no content |
| KV / prefix cache | **partitioned per requester pin** and discarded at the end of the attempt | partitioned per requester pin; may persist in memory |
| Passive benchmark samples (roadmap v2 P6) | performance metrics only (tok/s, TTFT, throttle); no content, no requester id | same |
| Opt-in public benchmark upload (v2 P7) | must never include anything derived from peer-served requests that identifies the peer or requester | same |

The cache partition rule exists because a shared prefix cache lets one requester measure time to first token and infer whether another requester's prompt shared a prefix [A11]. Partitioning by requester pin (my own local apps form one partition) removes that cross-requester channel.

### 10.7 What leaks regardless of policy

Even with everything applied, a serving peer learns: that the requester is active, when, and roughly how much; which model it wanted; the request and response sizes; and (own class only) the requester's device name. An overlay or LAN observer learns that the two nodes talk, when, and how much.

### 10.8 What this policy does NOT guarantee

- `retain`, cache partitioning and "local engine only" are followed only by **conforming, uncompromised** peers. The requester cannot check any of them. **The only guarantee the requester can enforce is what it chooses to send, to whom.** That choice is local and shows up in its own ledger, attempt by attempt.
- Redaction is only as good as the v3 detector.
- `own` is a declaration (§10.1).

---

## 11. Ledger: every remote request on both nodes, linkable without content

### 11.1 New egress class `lan` and row fields (additive, nullable)

Proposed additions to `RouteRecord` and `route_log`. Every field is nullable or defaulted, so existing rows and v1 serialisation are unchanged:

| Field | Requester row ("sent") | Serving row ("served") |
|---|---|---|
| `egress` | `lan` | `lan` |
| `callerPkg` | the local app's verified package (as v1) | `peer:<nodeTag of requester>`. **The requester's app identity is never transmitted** |
| `peerNode` | `nodeTag` of the serving peer | `nodeTag` of the requester |
| `meshRole` | `sent` | `served` |
| `meshKind` | `infer` / `control` / `pairing` / `revocation` / `locate` | same |
| `meshAttemptId` | random 128-bit id, base32 (one per attempt) | same value |
| `servedProvider` / `servedModel` | `peer:<nodeTag>` / the model **as claimed** by the peer's `INFER_HEAD` | `local` / the model it actually loaded |
| `bytesOut` | request body bytes sent to the peer (0 if declined) | response bytes sent back |
| `bytesIn` (new) | response bytes received | request body bytes received |
| `tokensIn/Out` | from `INFER_END.usage` (the peer's claim) | counted locally |
| `costEst` / `costBasis` | null / `none` (no monetary cost; energy is `router.md`'s concern) | null / `none` |
| `status` | final status. **`0 = IN_FLIGHT`** while pending (write-ahead) | same |

### 11.2 Write-ahead sequence for one inference attempt

```
Requester A                                             Peer B
1. offer decided (router)
2. A: ledger.append(row{status=IN_FLIGHT, meshKind=infer, attemptId, peerNode=B, bytesOut=0})  -- durable
3. INFER_OFFER ------------------------------------------>
                                                        4. B: decision table §7.3
   <------------------------------------- INFER_DECLINE    B: ledger.append(row{status=<code>, bytesIn=0})  (metadata only; no content was sent)
   A: update row(status=<code>, bytesOut=0)  -> router tries the next candidate (new attemptId, new row)
   <-------------------------------------- INFER_ACCEPT     B: ledger.append(row{status=IN_FLIGHT, meshRole=served})  -- durable BEFORE body is read into the engine
5. A: update row(bytesOut=len(body))  -- durable BEFORE the body leaves
6. INFER_BODY ------------------------------------------->
   <----------------- INFER_HEAD / INFER_CHUNK* / INFER_END   B: update row(final status, tokens, bytesOut)
7. A: update row(final status, tokens, latency); echo headers came from this same record (§11.4)
Crash anywhere: a row left at IN_FLIGHT is shown as "outcome unknown - content may have been delivered".
```

This needs an **update-by-`meshAttemptId`** path in the ledger (the v1 ledger is insert-only). The alternative, two rows per attempt, would double-count in the UI and in exports. It goes on the contract list (C-4).

### 11.3 Non-inference network events

| Event | Row |
|---|---|
| Control-plane session (`HELLO`, `STATE`, `MANIFEST`, `LOCATOR_HINTS`, `PING`) | One `meshKind=control` row per session, keyed by `sessionNonce`. Written IN_FLIGHT at `ESTABLISHED`, updated hourly and at close with frame counts and bytes in and out. **Not one row per heartbeat** (owner decision OD-1 covers whether per-session granularity satisfies "every network event writes a ledger row") |
| Pairing ceremony | One `meshKind=pairing` row on each side (outcome: paired / refused / window-closed / proof-invalid). A refused attempt from an unknown pin records `peerNode = nodeTag(pin)` |
| `REVOKE_NOTICE`, `REVOCATION_HINT` sent or received | One `meshKind=revocation` row each |
| Failed dials (TLS pin mismatch, timeout) | Recorded inside the attempt or session row that caused them (`status = PEER_UNREACHABLE`, `bytesOut = 0`). No separate rows |
| mDNS (only if approved) | One `meshKind=locate` row per hour with query and answer counts |

### 11.4 Echo headers stay truthful (invariant 9)

On the requester, `X-Asom-Served-By: peer:<nodeTag>/<model>`, `X-Asom-Egress: lan` and `X-Asom-Node: <nodeTag>` are built from **the same `RouteRecord`** as A's ledger row. For streams they come from the commit-time view at `INFER_HEAD`, and the row is finalised afterwards, exactly as v1 does. Each node's headers and rows agree with each other. The two nodes' rows are linked only by `meshAttemptId`.

**Honesty note that must appear in the UI copy:** `egress: lan` on the requester means "the content left this device and went to paired peer X". What X did with it afterwards (including whether it really ran the model it named) is X's **claim**, recorded in X's own ledger. A conforming peer never sends a request on (R6), but that cannot be verified from the requester.

### 11.5 Linkage without content

- `meshAttemptId` comes from a CSPRNG, one per attempt. It is **never derived from content, time or app identity**.
- The requester's logical-request grouping id (which ties failover attempts together) is **kept local and never sent**. Two peers that each received one attempt of the same request therefore cannot link them.
- **Rejected:** putting a hash of the prompt in either row. Short prompts are guessable, so a hash can be dictionary-attacked back to content from an exported ledger. The same goes for any keyed hash whose key is kept.
- **Export:** each node exports only its own rows (v1 share sheet, no automatic egress). Joining two exports on `meshAttemptId` rebuilds the cross-device story ("app X on the iPhone → Dell, 1,300 tokens in, served, 2.1 s") with no content in either file.

---

## 12. The invariant amendment

### 12.1 What the roadmap draft says, and why it needs precision

Roadmap §7 draft: *"an additional listener MAY bind a private-overlay interface (Tailscale-class tailnet) or mTLS-secured LAN, OFF by default, per-device tokens required, every remote request ledgered on both nodes. Never a public interface."*

| Gap in the draft | Why it matters | Fix in the proposed text |
|---|---|---|
| "per-device tokens" | Bearer tokens on a network can be replayed from anywhere once leaked. This design uses pinned keys | "mutual TLS against per-node keys pinned at an in-person pairing" |
| "mTLS-secured LAN" does not say which addresses | IPv6 LAN addresses are often globally routable (no NAT). Café Wi-Fi is RFC 1918 but socially public | Enumerated private and link-local ranges only; no IPv6 global unicast; user-enabled networks only |
| Wildcard binds are not excluded | Binding `::` or `0.0.0.0` exposes every interface, cellular included | "enumerated addresses only; never a wildcard; never cellular" |
| Overlay identified by address | `100.64/10` is also carrier CGNAT [A6] | "a user-selected overlay interface" |
| Which API is exposed is not stated | The v1 API must never be reachable from another device | "serves only the peer protocol, never the app-facing API" |
| Pairing needs a listener | D must listen during the window | "a pairing window may open it for at most 120 seconds" |

### 12.2 Proposed text: Amendment 2 (v4), full

> **Invariant 2 (as amended at v4).** The app-facing API server binds `127.0.0.1` only: never `0.0.0.0`, `::`, or any other address, and it is never reachable from another device. In addition, and only while the user has switched it on (OFF by default; a pairing window the user opens may switch it on for at most 120 seconds), a separate **peer listener** MAY bind specific addresses on (a) a private-overlay interface (Tailscale-class tailnet) the user selected, and/or (b) a Wi-Fi or Ethernet interface on a network the user enabled it for, using only private or link-local addresses (IPv4 `10/8`, `172.16/12`, `192.168/16`, `169.254/16`; IPv6 `fc00::/7`, `fe80::/10`). It never binds a wildcard, a publicly routable address, or a cellular interface. The peer listener serves only the peer protocol (never the app-facing API), speaks only TLS 1.3 with mutual authentication against per-node keys pinned at an in-person pairing, and authorizes only peers whose registry state is PAIRED, re-checked on every request. Every request a peer sends or serves is ledgered on both nodes. No cleartext beyond localhost. Never a public interface.

**Companion clauses.** Each changes the *text* of another invariant. Roadmap §7 anticipated all three in substance (it names the `lan` egress class, QR device pairing and capability exchange), but §13 lists only Invariant 2 as amended. **Whether they count as part of the second amendment or as a third is OD-1.** Here they are drafted so that the owner can approve one combined text:

> **Invariant 3, clause (d).** Peer traffic (egress class `lan`): the peer protocol to a PAIRED peer at a private, link-local or user-selected-overlay address. This covers requests this node sends, responses it serves, and the enumerated control-plane messages (capability manifest, live state, pairing, revocation, locator). It never goes to a public address and is never relayed onward. Each inference attempt is ledgered on both nodes before its content leaves; control-plane traffic is ledgered per session.

> **Invariant 5 (extended).** App pairing on a device remains AIDL-verified via `Binder.getCallingUid()`, and no HTTP registration endpoint exists. Node pairing between devices is key-based and exists only inside a pairing window the user opened on the listening node: a single-use secret of at most 120 seconds shown as a QR code, bound to both nodes' keys, and confirmed by the user on both devices. No endpoint accepts unsolicited registration, and no network message can create, restore or raise a pairing.

> **Invariant 1 (clarification).** Peer control-plane messages (capability manifest, live state) are not usage or benchmark uploads. They go only to PAIRED peers that the user granted the corresponding scope, contain only enumerated schema fields (no content, keys, per-app usage or ledger rows), are shown in the Peers tab as the exact last payload sent, and are ledgered. They never go to any other destination.

### 12.3 Do outbound-only nodes, macOS/iOS or multi-interface listeners need more?

| Case | Invariant 2 | Other invariants | Platform permission (not an invariant) |
|---|---|---|---|
| **Outbound-only node** (iOS requester, or any node that grants no inbound scope) | **Untouched:** binds nothing beyond loopback | Needs Invariant 3(d): peer requests are a new egress class even when outbound only | iOS: Local Network privilege for LAN peers, none needed over the overlay [V1]. Android 17+/target 37: `ACCESS_LOCAL_NETWORK` [V7] |
| **iOS foreground provider** | Covered by "MAY", listener closed in the background [V8] | — | iOS: accepting TCP needs no Local Network privilege [V1]. Android 17: accepting **does** need it [V7] |
| **macOS asom-desktop** | Covered | — | Per-user launchd agent, which gets the Local Network prompt once. A root daemon avoids the prompt but raises risk [V2] |
| **Multi-interface host** (Dell with Ethernet, Wi-Fi and a tailnet) | Covered by "specific addresses", "never a wildcard" and the interface rules | — | Re-evaluate on every network change: close all peer sockets, rebind eligible addresses only |

**Conclusion.** The combined text in §12.2 is sufficient. Nothing in this section needs a fourth change. I am not assuming the companion clauses are approved (OD-1).

---

## 13. Proposed contract deltas (all additive; every item needs owner sign-off)

| ID | Delta | Notes |
|---|---|---|
| C-1 | **Peer protocol `asom-mesh/1`**: framing, message types and mesh error codes (§3.3, §4.4, §5.2), on a new peer listener, default TCP **11436** | A new surface. It does not touch §5.2's localhost API |
| C-2 | Ledger egress class **`lan`** (`Egress.LAN`), and the value `lan` for `X-Asom-Egress` on the requester | The v1 enum comment "Exhaustive — no additions (invariant)" means this is the Invariant 3 change (§12.2) |
| C-3 | Response header **`X-Asom-Node: <nodeTag>`** | Provisional in roadmap §7 |
| C-4 | `RouteRecord` / `route_log` additive nullable fields `peerNode, meshRole, meshKind, meshAttemptId, bytesIn`; status `0 = IN_FLIGHT`; ledger **update-by-attemptId** | Write-ahead (§11.2) |
| C-5 | `X-Asom-Served-By` value form `peer:<nodeTag>/<model>` | A new value inside an existing header |
| C-6 | *(optional)* virtual policy **`own-devices`** (never cloud; this device or own-class peers) plus error `NO_ELIGIBLE_NODE` | Without it, apps can express only "this device" (`local-only`) or "anything" |
| C-7 | Per-app policy table column `mesh: off \| own \| own+other` (v2.5 table) | Dashboard only, not API |

Not proposed: any new `/v1/*` or `/admin/*` endpoint, any HTTP pairing endpoint, any change to the v1 AIDL pairing codes.

---

## 14. Owner decisions

| ID | Question | Options | Recommendation | Why only the owner |
|---|---|---|---|---|
| **OD-1** | Do the companion clauses to Invariants 3, 5 and 1 (§12.2) count as part of the sanctioned second amendment, or as a third? And is per-session ledgering of control traffic an acceptable reading of "every network event writes a ledger row"? | (a) one combined Amendment 2 text; (b) Amendment 2 limited to Invariant 2, with the others escalated as a third amendment; (c) drop the mesh features that need them | **(a)**, explicitly signed off. Roadmap §7 already anticipates each substance (`lan` class, QR device pairing, capability exchange); only the invariant text lags behind | Roadmap §13: a third amendment requires owner escalation. Only the owner can rule what counts |
| **OD-2** | Allow pairing with **other people's** devices (class `other`)? | (a) own devices only in v4 (class `other` designed but disabled); (b) allow `other` with the stricter defaults in §7 and §10; (c) never | **(a)** | The stop-line wording is "owner-controlled devices". Cross-owner compute sharing edges toward the Layer-3 direction the owner excluded |
| **OD-3** | Replace the roadmap's "no mDNS" with **mDNS locate-only** (§6.3)? | (a) keep no-mDNS (static registry + overlay + authenticated locator hints); (b) approve locate-only mDNS, off by default, in a later v4 step; (c) approve and default on | **(b)** | It reverses an explicit roadmap line, and it trades presence disclosure on the LAN for convenience |

Engineering choices that need no owner ruling but must be verified at build time: spikes S-A1 (JSSE resumption), S-A2 (iOS leaf identity), S-A3 (Mac JVM and the Secure Enclave), S-A9 (custom ALPN and TLS-1.3-only settings on all three stacks).

---

## 15. Conformance vectors and laws (trust layer)

Vectors live next to the other mesh vectors that `platforms.md` defines. Every implementation (JVM and Swift) must pass them unchanged.

| Vector file | Contents |
|---|---|
| `pairing/proof-sas.json` | The §4.5 worked vector, plus 20 random ones; a pin-swap negative case; a nonce-swap negative case |
| `pairing/qr-uri.json` | Valid URIs, and invalid ones: missing field, expired, public address, DNS name instead of literal, 5 endpoints, oversized name, wrong version |
| `certs/node-leaf-templates.json` | Deterministic DER for fixed test keys (encoder golden output) |
| `certs/verify-chain.json` | Valid `[leaf,node]`; negatives: 1-cert and 3-cert chains, leaf signed by another node, CA leaf, node without CA bit, P-384 key, RSA key, SHA-1 signature, expired leaf beyond skew, leaf not yet valid, pin not in registry, pin SUSPENDED, pin REVOKED in pairing mode, issuer DN mismatch |
| `frames/codec.json` | The §3.3 example; length boundaries (5, 16 MiB + 5, +1); unknown type < 0x80 and ≥ 0x80; truncated frames |
| `authz/decision-table.json` | Every row of §7.3 on both sides, and `meshEligibility` across the full cross product of policy × class × data class × `crossOwnerEnabled` |
| `locator/tags.json` | The §6.3 tag vector plus epoch ±1 |
| `locator/addr-eligibility.json` | (address, interface kind) pairs, each marked eligible or not: RFC 1918, link-local, ULA, overlay-on-tun, `100.64/10` on cellular (not eligible), IPv6 GUA, public IPv4, wildcard, loopback |

**Laws** (property tests in the style of the v1 routing laws; the oracles are written independently of the implementation, which was the audit's lesson):

- **L1** No sequence of network-originated events reaches `PAIRED` for any pin without a local approval event in the same ceremony.
- **L2** `authorize(pin, s)` ⇒ `status == PAIRED ∧ s ∈ inboundScopes`.
- **L3** Once `REVOKED`, a row stays `REVOKED` under any network events. Only a local `Forget` removes it.
- **L4** `SUSPENDED → PAIRED` only on a local `Restore`.
- **L5** An unknown or corrupt status value denies.
- **L6** A pairing window accepts at most one valid proof.
- **L7** A `REVOKED` pin is refused in pairing even with a valid proof.
- **L8** `verifyPeerChain` accepts exactly the valid vectors and rejects every negative one.
- **L9** `local-only` ⇒ no mesh; app `off` ⇒ no mesh; class `other` ⇒ `D2 ∧ redact ∧ retain == none`.
- **L10** Every attempt that sent `INFER_BODY` has a durable requester row written before the send. Every attempt that accepted has a durable server row written before the engine starts.
- **L11** The peer listener rejects any bearer token. The app-facing listener rejects any mesh frame. Neither code path is reachable from the other.
- **L12** No UI or router code path consumes an mDNS instance that lacks a matching tag.
- **L13** `eligible(addr, iface)` is false for every public, cellular, wildcard and loopback case in `locator/addr-eligibility.json`.

---

## 16. Verified facts and assumptions

### 16.1 Verified this session

| ID | Fact | Source | Confidence |
|---|---|---|---|
| V1 | A "local network" is on a broadcast-capable interface (Wi-Fi, Ethernet), **not cellular or VPN**. Outgoing TCP to a local address needs the Local Network privilege; **listening for and accepting incoming TCP does not**. Bonjour register, browse and resolve need it. On iOS, multicast and broadcast (and arbitrary or all Bonjour types) need the multicast entitlement. A background iOS local-network operation with an undetermined privilege is denied silently. Local Network privacy exists on iOS 14+ and macOS 15+ | Apple TN3179 "Understanding local network privacy" (revision 2026-02-17), developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy | high |
| V2 | On macOS, `launchd` daemons, root processes and Terminal/SSH tools are allowed automatically; `launchd` agents are not | Apple TN3179, macOS considerations | high |
| V3 | ATS does not apply to Network framework. Under ATS, URLSession trust can be tightened (pinning) but not loosened (self-signed). iOS 17+ ATS no longer allows IP-address connections by default | Apple, "Preventing Insecure Network Connections" and the `NSAllowsLocalNetworking` reference | high |
| V4 | CryptoKit `SecureEnclave` offers P256 (signing, key agreement), MLKEM768/1024 and MLDSA65/87, and **no Curve25519** | Apple CryptoKit `SecureEnclave` documentation (topics list) | high |
| V5 | StrongBox supports RSA 2048, AES-128/256, **ECDSA/ECDH P-256**, HMAC-SHA256 and 3DES, and is "slower, more resource-constrained, and supports fewer concurrent operations" | developer.android.com/privacy-and-security/keystore | high |
| V6 | Android 13's KeyMint v2 added Curve25519 (Ed25519 and X25519) support | source.android.com keystore documentation (via search summary; not fetched directly) | medium |
| V7 | Android 17 enforces `ACCESS_LOCAL_NETWORK` (NEARBY_DEVICES group) for targetSdk 37+. It covers outgoing **and accepting** TCP, UDP, mDNS and NsdManager. Apps targeting ≤36 are implicitly granted it via INTERNET | developer.android.com/privacy-and-security/local-network-permission | high |
| V8 | Apple DTS advises closing listeners when the app becomes eligible for suspension; a suspended app cannot serve | Apple Developer Forums (Quinn), threads 757385, 91625 | medium-high |
| V9 | Using a Secure Enclave key as a Secure Transport client identity was blocked by a bug (2016). As of 2021 DTS "suspects" but has not confirmed a fix | Apple Developer Forums thread 45824 | high (that it is unconfirmed) |
| V10 | The official Tailscale iOS and Android apps support a custom control server (Headscale) | Tailscale docs "custom control server"; headscale docs | medium-high |
| V11 | Ktor CIO does not support HTTP/2. The CIO-server HTTPS issue (#886) is still open. CIO's TLS lacks TLS 1.3 (KTOR-6737) | ktor.io server-engines docs; github.com/ktorio/ktor/issues/886; JetBrains YouTrack KTOR-6737 | medium |
| V12 | The TPM PC Client profile mandates ECC NIST P-256. Ed25519 is not generally available in TPMs | TCG PC Client PTP spec (search summary); tpm2-pkcs11 issue 785 | medium |
| V13 | SteamOS blacklists the `tpm` kernel module by default (the Deck's fTPM is otherwise present) | jiankun.lu blog, 2022-11-14 | low (dated) |

### 16.2 Assumptions (not verified; load-bearing ones become spikes)

| ID | Assumption | Load-bearing? |
|---|---|---|
| A1 | JSSE skips the TrustManager on TLS session resumption | Mitigated anyway (resumption disabled plus per-frame check). Spike S-A1 |
| A2 | On iOS and macOS, a software P-256 leaf key and certificate can be turned into a `sec_identity` for `NWConnection` client authentication | **Yes.** Spike S-A2 |
| A3 | A JVM process on macOS cannot use Secure Enclave keys without a signed native helper holding keychain entitlements | Tier only. Spike S-A3 |
| A4 | Home routers mostly keep stable DHCP leases for devices whose per-network MAC is stable | Sizes OD-3 only |
| A5 | Tailscale does not carry mDNS across the tailnet | No |
| A6 | Carrier-grade NAT uses `100.64.0.0/10` (RFC 6598), the same block as Tailscale | Motivates the interface rule; the rule is safe even if this is wrong |
| A7 | Tailscale's IPv6 range sits inside `fc00::/7` (ULA) | Covered by the ULA rule either way |
| A8 | StrongBox and TPM ECDSA signing take tens to hundreds of milliseconds | No (weekly leaf signing) |
| A9 | JSSE, Conscrypt and Network.framework all support TLS-1.3-only settings, required client auth, custom ALPN, a custom verifier and disabling 0-RTT and resumption | **Yes.** Spike S-A9 |
| A10 | The JDK has no public X.509 certificate-builder API | Decides the template-encoder choice |
| A11 | Shared prefix/KV caching across requesters creates a timing side channel (published prompt-cache audit research) | Motivates §10.6 |
| A12 | iOS allows only one VPN active at a time, so Tailscale occupies it | Context only |

---

## 17. Pieces that could be built now (pure JVM, no contract change, no Android)

These could be built only if the owner authorises **design-spike work ahead of v4's entry criteria**. CLAUDE.md forbids starting later versions, so none of this should land in the shipped modules without that sign-off. A scratch module outside `settings.gradle.kts`'s shipped set would keep the frozen build untouched.

1. `pairing`: QR URI codec, proof, SAS and transcript functions, and the window and scanner state machines, with the §4.5 vectors and laws L1, L6, L7.
2. `certs`: the two DER templates and `verifyPeerChain` against JDK `java.security` (P-256, ECDSA), with the full negative vector set (L8).
3. `frames`: the `asom-mesh/1` codec and its limits (vectors).
4. `registry`: the peer registry FSM as a pure state machine behind a DAO interface (same pattern as v1's `PairingRegistry`), with property tests L1–L5.
5. `policy`: `meshEligibility`, `classify` and the §7.3 server decision table as pure functions, with exhaustive cross-product tests (L9). The `router.md` simulator can call these directly.
6. `locator`: `eligible(addr, iface)` and tag derivation (L13, the tag vectors).
7. A JVM loopback-only **test harness** (two in-process nodes over JSSE on 127.0.0.1 inside tests only) that exercises the full pairing, session and revocation choreography. It is a test fixture, never a listener in any shipped artifact.

---

## 16. Revision 4 amendments (normative; 2026-10-07)

**Status.** Design revision 4 (`REVISION_4.md`) folds into this section the readings that the lab's trust, TLS, session and pairing tracks recorded in `lab/ERRATA.md`. **Where this section and §1–§15 differ, this section wins**; `ASOM_MESH_DESIGN.md` still wins over both. Every numeric limit marked **PROVISIONAL** is a judgement, not a measurement, and must be reviewed with device data before M1. "Builder action" marks a change that the lab code or the vectors do not yet carry. Nothing here is device evidence.

**R4-T-01 (§3.2 "Session resumption"; R3-OVERCLAIM-6, R3-CLOSURE-7, ERR-PL-1).** Old: "Disabled on the peer listener (no tickets issued, no client session cache)". New: "Never redeemable. The dialler builds a fresh TLS context for every dial (empty client cache, so no PSK is offered). The listener builds a fresh TLS context for every accepted connection; a JSSE listener may still send TLS 1.3 tickets (JDK 17 and 21 have no scoped switch), but no later connection can redeem them because the context that issued them is gone. No JVM-wide system property is set (design C12)." Measured on JDK 17.0.12 and 21.0.10 (LAB): a hostile client that replayed a ticket got a full handshake with the verifier invoked once. Conscrypt and Network.framework: UNVERIFIED (S-A9 matrix). Consequence: design §4.4's "a modified client holding a PSK defeats T9 against any provider that tolerates resumption (JSSE 17)" no longer applies to an asom JVM listener built this way.

**R4-T-02 (§3.2 ALPN; ERR-PL-5).** Old: "Server fails the handshake (`no_application_protocol`) otherwise". New: "ALPN `asom-mesh/1` is required on both sides and enforced three ways: the TLS parameters; neither side presents its certificate unless the handshake's negotiated protocol is `asom-mesh/1`; and a check after the handshake. A ClientHello without ALPN is refused (the alert may be `handshake_failure` rather than `no_application_protocol`)."

**R4-T-03 (§3.2 client authentication; ERR-PL-13).** New: a dialler refuses a listener that never requested its certificate (`CLIENT_AUTH_NOT_REQUESTED`).

**R4-T-04 (§3.2 "one alert for every refusal"; ERR-PL-7, ERR-PT-5, ERR-FX2-TT-3).** Every verifier refusal is raised as a certificate exception with no message and no cause, so both JDKs send `certificate_unknown`; the evidence for uniformity is what the refused peer observes on its socket (W08 law `alert-uniform`), never a constant in the verdict type. An empty client certificate may be `bad_certificate` (JDK 17) or `certificate_required` (JDK 21).

**R4-T-05 (§3.2 handshake timeout; ERR-PL-9).** The 5 s is one deadline for the whole handshake (a peer that drips its ClientHello also times out). After a failure the socket is half-closed and drained for at most 200 ms so the peer can read the alert.

**R4-T-06 (§3.2 `verifyPeerChain`; design T6; ERR-PT-1, ERR-PT-2, ERR-PT-3, ERR-PT-4, ERR-PT-5).**
- The verifier checks, in this order: chain length 2; both certificates parse; both keys strict P-256 (design T1); both algorithm identifiers exactly `ecdsa-with-SHA256` with absent parameters; no duplicate and no unknown critical extension; presence of node BC, KU, SKI and leaf BC, KU, EKU, AKI; BC and KU critical; node CA with keyUsage exactly {keyCertSign} and pathLen **present** and 0; leaf not a CA, no keyCertSign, no pathLen, digitalSignature present, EKU holds serverAuth and clientAuth; issuer bytes equal to the node subject bytes and the node self-issued; AKI equal to the node SKI; both signatures; the leaf clock; then the pin and the registry. Unknown non-critical extensions are ignored.
- Templates (§2.4): SKI = leftmost 160 bits of SHA-256 over the 65-byte public point; leaf serial 16 bytes, high bit clear, not all zero; names are `UTF8String` `CN=asom-node <nodeTag>` and `CN=asom-session <nodeTag>`; only BC and KU are critical; RFC 5280 time encodings; node notAfter `99991231235959Z`.
- **Leaf lifetime cap (new, builder action):** a leaf whose `notAfter − notBefore` exceeds 14 days + 1 hour is refused (`CLOCK_SKEW` is not used for it; a new refusal reason `LEAF_LIFETIME`, alert `certificate_unknown`). Old: the 14-day bound was a producer rule only. Needs one W05 vector.
- Clock window bounds are inclusive: accepted at `notBefore − 7,200 s` and at `notAfter + 7,200 s`, refused one second beyond (W05-310..313).
- **Pairing modes follow design T6.** `EXPECT_PAIRING` and `PAIRING_SERVER` admit a pin only if its registry row is absent. Old: "`statusOf(pin) != REVOKED`". The lab's verifier still applies the old letter (W05-307, W05-308 pin it) and refuses SUSPENDED and PAIRED one layer down (`PEER_EXISTS`); the outcome is the same. Design T6's "or PAIRED, for address refresh" path is **not built in mesh-1**: endpoints of a PAIRED peer change only through its authenticated `HELLO` or the user (T12). **Builder action:** move the refusal into the verifier modes and regenerate W05-307/308.

**R4-T-07 (§3.2 message order; ERR-PL-6).** In TLS 1.3 a dialler's handshake completes before the listener has judged the dialler's certificate, so "dial returned" is not acceptance; a refused dialler learns it as a `certificate_unknown` alert on its first read.

**R4-T-08 (§3.3 limits and §5.1 timers; design §4.3, T9; ERR-PI-3, ERR-PI-4, ERR-PI-5, ERR-PS-6, ERR-PS-7, ERR-PS-14, ERR-PS-18, ERR-FX2-TT-1, ERR-FX2-PS-1…7, ERR-FX2-PS-10).** Old: "Limits (defaults, per peer): 4 concurrent streams; `INFER_BODY` ≤ 8 MiB; 8 unauthenticated connections in flight per listener; 10 handshakes per minute per source address; idle close after 5 min without a stream; `PING` only while a stream is open." New (PROVISIONAL where marked):

| Limit | Rule |
|---|---|
| Concurrent served streams | 4 per **peer (pin)**, counted across all of that peer's sessions; a fifth offer the decision table would accept is declined `PEER_BUSY` |
| Unauthenticated connections | 8 in flight per listener; **4 per source** (PROVISIONAL); the source key is the IPv4 address, an IPv4-mapped IPv6 address as its IPv4 form, any other IPv6 address as its /64 (zone and case ignored); a ticket older than 10 s (PROVISIONAL, twice the handshake timeout) is reclaimed |
| Handshakes per source | 10 per sliding 60 s window; a refused connection does not count towards the window; a refused connection is closed before any TLS byte and counted in the one `INBOUND_REFUSED` row |
| Idle close | 300,000 ms with no open stream and no frame of a stream (stream ≠ 0) accepted by a handler in either direction; connection-level frames, extension frames and frames a handler ignores (a tolerated late `CANCEL` or late body) do not restart it |
| Maximum session age | **30 min** (design §4.3 and T9: forces a fresh handshake and the keyguard-bound leaf). A session with an open stream says `GOAWAY max-age` at the first moment it has none. ERR-PI-3's PROVISIONAL 24 h is superseded. **Builder action** |
| Body wait (lender) | `min(offer.deadlineMs, 30,000 ms)` after the accept (PROVISIONAL); on expiry the lender writes the single outcome row (status 408, `terminal` error) durable before `INFER_END` and frees the slot; the first `INFER_BODY` that arrives after an expiry is ignored, a second is `BODY_WITHOUT_OFFER` |
| Request wait (requester) | a `STATE_REQ` or `MANIFEST_REQ` unanswered for 30,000 ms (PROVISIONAL) fails as lost; one late reply is ignored |
| Attempt deadline (requester backstop) | an attempt with no frame either way for its own `deadlineMs` is cancelled with `CANCEL deadline`; a cancelled attempt that does not end within 30,000 ms (PROVISIONAL) closes the session without `GOAWAY` and every attempt on it ends as a lost peer (599 `PEER_UNREACHABLE`). The router's own deadline may be shorter |
| Late `CANCEL` | a finished attempt tolerates one late `CANCEL` (no reply, no row, bytes residual on the session close row); a second, or one for an attempt never opened, is `PROTOCOL_ERROR` and close |
| Write stall | every application write after the handshake completes within 30 s (PROVISIONAL) or the socket is killed (`TRANSPORT_IO`); `close()` waits at most 500 ms for the write lock and never blocks on a stalled peer |
| Registry listeners | a registry change never writes to a socket on the thread that made the change; it hands `GOAWAY` to the session's driver and returns. **Builder action** (`integration/Node.kt`, `session/`) |
| Extension frames before `HELLO` | held (size only), up to 16 (PROVISIONAL); the 17th is `FRAME_BEFORE_HELLO`; rowed after the open row under the session's one id |
| `attemptId` replay LRU | recorded at first sight of an offer whatever its outcome; 65,536 per node, oldest out (PROVISIONAL); 24 h |
| `HELLO.sessionNonce` | must not equal any of the last 65,536 session ids this node dialled, minted or accepted (PROVISIONAL); a repeat is `PROTOCOL_ERROR` and close |
| `HELLO.ts` / `HELLO_ACK.ts` | more than 2 h from the local clock is `ERROR CLOCK_SKEW` and close, checked after the node id and the version; within 2 h it changes nothing (resolves §5.2 "never a security input" against §5.5 "fails closed": it fails closed beyond 2 h) |

`PING`/`PONG` are retired in mesh-1 (design §4.3). Limits other than the above are unchanged. Nothing in the lab's `main` calls the handshake limiter (no listener exists, LAB_SPEC R5).

**R4-T-09 (§3.3 frame table).** The frame table of §3.3 and the error list are the r0 set; the mesh-1 set is `LAB_SPEC.md` §7.2 as amended by its §10 (R4-L-21). `HELLO_ACK.limits` has five members (`idleUnloadMs` added); `INFER_END` carries no `usage`, `ttftMs`, `totalMs`; `ERROR` carries no `message`.

**R4-T-10 (§4.3–§4.6 pairing; design T7, T8; R3 profile; ERR-FX2-PW-1…7, ERR-PT-6, ERR-PT-7, ERR-PT-8).** Commit before reveal, typed code on D, S does not gate on expiry:
- **Commitment.** S puts `C = SHA-256("asom-pair-v1/commit" ‖ 0x00 ‖ nonce_S)` (32 bytes, base64url) in the existing `PAIR_HELLO.nonceS` member; `proof` binds `C` in place of `nonce_S` (the function is unchanged). D answers `nonce_D` in `PAIR_CHALLENGE`. S opens `nonce_S` only with its own approval, in a new optional member `reveal` of `PAIR_DECISION` (`{"v":1,"approve":true,"reveal":"<b64u 32B>"}`); a decline sends no opening. D checks `SHA-256(label ‖ 0x00 ‖ reveal) == C` in constant time before it judges any code; a missing, short or different opening is `PROTOCOL_ERROR` and the ceremony ends. SAS and transcript use the opened `nonce_S` (functions unchanged). **Old §4.5 sentence deleted:** "Ordering stops SAS grinding: S commits `pin_S` and `nonce_S` before D reveals `nonce_D`, so an attacker cannot search for a key that produces a chosen code." It was false for an attacker in D's role (a relay holding the photographed QR secret read `nonce_S` and searched `nonce_D`); reproduced in the lab (`PairRelayAttackTest`).
- **Typed code on D (design T7).** D shows no code. D's approval carries the six digits the user typed; it approves only when they equal the SAS of the opened nonce (spaces and no-break spaces ignored, exactly six ASCII digits, constant-time compare). A wrong code is counted; the third wrong code declines and aborts (`TRIES_EXHAUSTED`). A code typed before the opening arrives is kept and judged then. S shows the code and keeps compare-and-approve. The §4.3 diagram's D-side sheet "Code 865 412" is replaced by "Type the code shown on <S>".
- **S does not gate on QR expiry (design T8).** A scanner keeps every syntax check of `x` and drops the two clock comparisons; D's `PAIRING_WINDOW_CLOSED` is authoritative and S shows "window expired on the other device".
- **Directions follow the TLS role.** S (the scanner) is the TLS client and sends `PAIR_HELLO`, its `PAIR_DECISION` and `PAIR_COMMIT_ACK`; D (the listener) sends `PAIR_CHALLENGE`, its `PAIR_DECISION` and `PAIR_COMMIT`. A wrong-direction, reflected or cold frame is `PROTOCOL_ERROR`, one `ERROR` row and close; a node refuses to send a frame its side may not send.
- **Connection binding.** D's pairing-window probe for the TLS layer admits a pairing connection only while the window is OPEN and unexpired; decisions and acknowledgements from any connection other than the hello's are refused.
- **Budget.** A pairing connection admits at most 16 inbound frames (R4-L-25).
- **Messages.** `platform` is the seven-value enum of `LAB_SPEC.md` §7.2 and `keyTier` the six-value enum; `name` is 1–32 code points and refuses control characters, U+2028, U+2029, bidi controls and U+FFFD; `v` must be the integer 1; unknown members are ignored and never kept; a received `locSeed` is dropped.
- **QR grammar readings (§4.2):** any parameter order; a missing, repeated or unknown parameter is refused; `k`, `s` exactly 43 canonical characters; `x` plain decimal, at most 12 digits, no leading zero; `n` percent-decoded strictly (`+` is not a space); endpoints are IP literals (IPv4 without leading zeros, bracketed IPv6 without zone); address classes per §6.6 without the interface test (the dialler checks the socket after connecting); `100.64/10` is the overlay range; loopback only under an explicit test-only policy.
- **State machines (§4.6)** are total functions: added events `RowDurable`, `RowWriteFailed`, `ConnectionLost`, `Tick`, `SecondUnknownConnection`; a hello outside OPEN is `PAIRING_WINDOW_CLOSED`; a REVOKED pin closes the window before the proof is checked; a pin already PAIRED or SUSPENDED is `PAIRING_REFUSED`; `approve:false` always aborts; an acknowledgement that mismatches or times out leaves the row PAIRED and unconfirmed; S's decision wait is 120 s from the challenge; S has no timeout in `SENT_HELLO` beyond the dial budget.
- **Worked vector (§4.5)** is unchanged (the three functions are unchanged; under R3 the `nonce_S` input of `proof` is `C`).
- **Builder action (ERR-FX2-PW-7):** the lab implements all of this as `PairProfile.R3`, `QrExpiry.NONE` and `PairingChannel` orientation `FROM_TLS_ROLE`, but the **defaults still reproduce the r0 behaviour** because 41 frozen W04 `fsm` vectors, 104 `qrParse` vectors and `PairingFsmExhaustiveTest` pin it, and the session harnesses (`PairWorld`, `TlsPairing`, `RandomRuns`) call the TLS server "S". To make r4 the default: add the W04 vectors listed in ERR-FX2-PW-7 and the laws `pairing-typed-code`, `pairing-commit-reveal`, `qr-expiry-none`; move the r0 vectors and the exhaustive test to an explicit `R0_COMPAT` profile or delete them; re-orient the harnesses; flip the three defaults.

**R4-T-11 (§4.7 registry, §8.3, §15; ERR-PT-9, ERR-FX2-TT-2).** mesh-1 has **no network-originated registry transition**: a hint or a status claim changes nothing; `revoke-hint` is not a scope (inbound scopes are `infer`, `manifest`, `state`); class `other` is out of mesh-1. Local transitions only: commit (needs local and peer approval), pause, restore, revoke (from PAIRED or SUSPENDED), forget (from REVOKED only). Every mutation is serialised (one lock across read, write and listener calls); reads take no lock and re-read the store. A change is durable before any listener hears of it.

**R4-T-12 (§5.2, §5.3, §7.3; ERR-PS-4, ERR-PS-5, ERR-LP-6, ERR-PW-7).** `HELLO_ACK.limits` requires all five members. Authorisation denials map to the decision table: "no scope" and "scopes unreadable" are row 2 (`SCOPE_DENIED`, the session stays); absent, unreadable, corrupt or not PAIRED is row 1 (`ERROR PEER_NOT_PAIRED` and close, with the `attemptId` for an offer or a body). A frame over its class limit inside an attempt is answered by an attempt-scoped `ERROR {"attemptId","code":"FRAME_TOO_LARGE"}`. A connection-level `ERROR` closes the session, except `SCOPE_DENIED` or `MANIFEST_UNAVAILABLE` answering a pending `STATE_REQ` or `MANIFEST_REQ`, which fails only that request.

**R4-T-13 (§15 laws L10, L11; ERR-PT-10).** L10 (a durable requester row before `INFER_BODY`) is covered by `LAB_SPEC.md` §7.7 L-L13/L-L14 over the per-frame writer; L11 (each listener rejects the other's traffic) needs both listeners and stays **unexercised** until a listener exists (D-v2/M1).
