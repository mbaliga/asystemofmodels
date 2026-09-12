# asystemofmodels — v1 Build Brief ("asom")

**Status:** FROZEN for cold execution · **Date:** 2026-07-04
**Repo:** `asystemofcells/asystemofmodels` (public, Apache-2.0)
**Naming:** canonical name `asystemofmodels`; everyday name **asom** (WINE-pattern nickname — use after first mention). Package anchor `xyz.mdhv.asom`. Header prefix `X-Asom-*`. Default port **11435**.

---

## 0. What this is (cold-start context — assume no chat history)

asom is a **sovereign model-routing daemon for Android**. One app owns the model files, the BYOK cloud keys, the routing decision, and an egress ledger; it exposes an OpenAI-compatible HTTP API on `127.0.0.1:11435`. Every other app on the device is a thin client with **no model files, no inference engine, no keys**. Apps join via an "AI hotspot" pairing flow (AIDL-verified consent), exactly like devices joining a Wi-Fi hotspot.

**v1 ships:** deterministic cloud-BYOK routing, shared model download/storage (install-once, served read-only to paired apps), Keystore vault, pairing, egress ledger + per-response echo headers, dashboard app, publishable client SDK.
**v1 does NOT ship:** a local generation engine (stubbed with typed errors), semantic routing, NL routing, loop workflows, web-origin callers. These are Phase 2, separate brief.

**ASOM is optional for consuming apps.** Each app declares a fallback tier (§10A): the *default* tier does cloud-BYOK on its own when ASOM is absent, and routes through ASOM only when present; a small *opt-in* set embeds the full engine (v2). ASOM is never a hard dependency, and a single-app user is never nagged to install it. Keys and models are the reason to *want* ASOM (enter once, download once), never a gate.

Ethos (frames every decision): sovereign, local-first, open-source, BYOK, **no telemetry**, no operator backend, one-time/free. The cloud is always a *watched object*: the user can always see which model ran, on-device vs cloud, and what left the device.

**Reader contract:** this brief is self-contained. Where `OWNER-FILL` appears, request the value from the owner or proceed with the committed fixture — never invent it.

---

## 1. Invariants — violating any of these fails the build

1. **No automatic egress.** No analytics, no crash-reporting SaaS, no telemetry SDKs, and no background or silent transmission of usage or benchmark data — ever. The *only* data that leaves the device leaves by an explicit foreground user action that shows the exact payload first: in v1 that is user-triggered ledger export via the Android share sheet (§9). v1 has **no upload path at all**. (v2 introduces exactly one more such action — opt-in, view-first benchmark contribution — under the same rule; see roadmap §13. It does not exist in v1.)
2. Server binds `127.0.0.1` **only**. Never `0.0.0.0`. No cleartext exceptions beyond localhost in network security config.
3. Permitted network egress classes, exhaustively: (a) provider API calls using the user's own keys, (b) catalogue.json fetch, (c) model-file downloads from catalogue URLs. **Every** network event writes a ledger row.
4. BYOK keys: Android-Keystore-wrapped (§8), entered **only** in the dashboard Keys tab, never accepted or returned by any API, never in logs or the ledger.
5. Pairing identity is **AIDL-verified** via `Binder.getCallingUid()`. No HTTP registration endpoint exists in v1 (the legacy `POST /admin/register` design is deleted).
6. **Red/green never carry meaning** (owner is red-green colorblind — hard constraint). Semantic hue pair: violet `#8E7BFF` / cyan `#35E0FF` (Hyle `accent.violet` / `provenance.cloud`, vendored into the token seam), always with shape/label redundancy.
7. UI is placeholder-functional Compose/Material3, wired against a **token-contract seam** (the Hyle design system supplies real visuals later; do not attempt visual design).
8. No GMS/Firebase/Play-services dependencies.
9. Per-response echo headers and the ledger row are built from the **same `RouteRecord` struct** — the API answer and the dashboard can never disagree.

---

## 2. v1 scope

**IN:** deterministic router · OpenAI-compat server (SSE streaming) · AIDL pairing + bearer tokens · Keystore vault · catalogue consumption · model download/verify/store + fd-sharing to paired apps · metadata ledger + opt-in verbose mode · dashboard (status / Hotspot / models / keys / ledger tabs) · `:client` SDK · `:sample-client` proof app.

**OUT (fail loudly, never silently):** local generation engine — `local-only` returns `501 LOCAL_ENGINE_ABSENT` and capabilities report `hasLocalEngine:false` · semantic router tiers · `X-Asom-Route-NL` · `loop:<name>` DAGs · web/PWA callers · on-device embeddings.

---

## 3. Environment & build reality

- **Dev host:** Steam Deck (SteamOS). Work inside a distrobox/podman Ubuntu box with JDK 17, git, `gh`. Local Android SDK is **optional** — document setup in `docs/DECK_SETUP.md` but never require it.
- **Guaranteed build path: GitHub Actions** (public repo → free hosted runners). CI runs all JVM tests and assembles a debug APK artifact on every push. There is **no self-hosted runner and no homelab** — do not reference one.
- **Pure-JVM-first law:** `:core:*` and `:server` must build and test on a bare JDK (`./gradlew :server:test`) with no Android SDK present. This is what makes the project developable on the Deck.
- Pins: Kotlin 2.1.x · Gradle 8.10+ wrapper · AGP 8.7+ · Ktor 3.x (**CIO** engine — not Netty) · Room 2.6+ · kotlinx-serialization · OkHttp (SSE) · androidx.work. `compileSdk 35 / targetSdk 35 / minSdk 29`.
- Foreground service uses FGS type `specialUse` with the manifest property declaration (sideload-first distribution makes this safe; Play justification is a later owner concern).

---

## 4. Modules & dependency law

| Module | Type | May depend on |
|---|---|---|
| `:core:contract` | pure JVM | — (DTOs, header names, error codes, `RouteRecord`, **`InferenceClient` interface** §10A) |
| `:core:catalogue` | pure JVM | contract |
| `:core:routing` | pure JVM | contract, catalogue |
| `:core:inference-api` | pure JVM | contract (engine interface + `NoopEngine` stub) |
| `:server` | pure JVM (Ktor) | all `:core:*` |
| `:vault` | Android lib | contract |
| `:pairing` | Android lib | contract |
| `:storage` | Android lib | contract, catalogue |
| `:ledger` | Android lib | contract |
| `:app` | Android app | everything above |
| `:client` | Android lib (publishable) | contract only (`RemoteAsom` transport, discovery, pairing, `FallbackResolver`, `NudgePolicy`, `InventoryProvider` — §10A) |
| `:client-cloud` | Android lib (publishable) | contract only (`CloudOnly` impl: HTTPS + the app's *own* Keystore BYOK vault — §10A) |
| `:sample-client` | Android app | client, client-cloud |

**Law:** no `android.*` import in JVM modules — enforced by module type. `:client` and `:client-cloud` must stay dependency-minimal (they ship inside other people's apps). **`asom-standalone`** (the embeddable engine + downloader + vault providing the `Embedded` impl) is **v2** — it needs the engine and is not built here; §10A defines the seam it will slot into.

---

## 5. Public contract — FROZEN (no new endpoints or headers without owner sign-off)

### 5.1 Discovery
`ContentProvider`, authority **`xyz.mdhv.asom.discovery`**, no permission required (discovery only, no secrets). Single row: `port:Int, version:String, capabilities:String(JSON)`. Capabilities JSON: `{ endpoints[], virtualModels[], hasLocalEngine:false, catalogueVersion }`. Clients fall back to documented default port 11435.

### 5.2 HTTP API — base `http://127.0.0.1:11435`
- `POST /v1/chat/completions` — OpenAI schema; **SSE** when `stream:true`.
- `POST /v1/completions` — legacy shim over chat.
- `POST /v1/embeddings` — cloud-routed in v1.
- `GET /v1/models` — concrete models (key present) + virtual models, each tagged via `owned_by`/metadata so clients can distinguish.
- `GET /admin/health`, `GET /admin/catalogue` (merged catalogue + live cooldown state). Bearer-gated, localhost-only.
- **Deleted vs legacy design:** `POST /admin/register`, `POST /admin/providers/{id}/key`. Do not implement.

### 5.3 Request headers (all optional except auth)
`Authorization: Bearer <token>` (required on `/v1/*` and `/admin/*`) · `X-Asom-Policy: cheapest|fastest|best-reasoning|local-only` · `X-Asom-Fallback: provider_a,provider_b` · `X-Asom-No-Train: true` (exclude providers with `trainsOnData=true`).

### 5.4 Response headers (every `/v1/*` response)
`X-Asom-Served-By: <provider>/<model>` · `X-Asom-Egress: local|cloud` · `X-Asom-Cost-Est: <USD>` **only when derivable**, with `X-Asom-Cost-Basis: usage|heuristic`. Never estimate without a basis.

### 5.5 `model` field semantics
Concrete id (`"llama-3.3-70b"`) **or** virtual policy (`auto | cheapest | fastest | best-reasoning | local-only`).

### 5.6 Errors
OpenAI error envelope + typed `code`: `NOT_PAIRED, TOKEN_REVOKED, NO_PROVIDER_KEY, MODEL_UNKNOWN, ALL_PROVIDERS_COOLING, LOCAL_ENGINE_ABSENT, UNSUPPORTED_BY_DRIVER`.

### 5.7 Pairing (AIDL — the "Hotspot")
Exported bound service, action **`xyz.mdhv.asom.PAIR`**. Conceptual interface: `requestPairing()`, `getToken()`, `getStatus()` with an async callback. Flow: client binds → daemon reads `Binder.getCallingUid()` → resolves package name + **signing-cert SHA-256** via PackageManager → consent sheet shows the *verified* identity (app label, package, cert fingerprint) → on approval mint a random 256-bit token bound to `(package, certHash)` → deliver via callback. Store only the token's SHA-256 hash (Room); compare constant-time. Revocation from the Hotspot tab invalidates immediately (server + providers check per request). Tokens never expire; revocation is the only invalidation.

**Consent-UI launch path (landmine):** a background-bound service cannot launch activities on modern Android (background-activity-launch restrictions). Sequence therefore is: SDK binds AIDL → `requestPairing()` records a pending request keyed by the *verified* (uid, package, certHash) → SDK launches asom's exported `PairingActivity` from the **client app's foreground context** via `startActivityForResult` → the activity displays the pending verified identity for consent. Identity always comes from the AIDL bind's `getCallingUid()` — never from activity extras or `callingPackage`. If the user dismisses without deciding, `getStatus()` returns `PENDING` and no token is minted.

### 5.8 Model file sharing
`ContentProvider`, authority **`xyz.mdhv.asom.models`**, `openFile` mode `"r"` only, URIs `content://xyz.mdhv.asom.models/models/{modelId}`. Caller UID must map to an active pairing. Consumer note (document in README): llama.cpp-embedding apps can load the returned fd via the `/proc/self/fd/<n>` path; the kernel page cache dedups read-only mappings across processes.

### 5.9 Body handling & streaming (v1)
The router parses only `model` and `stream` (and reads `messages` for heuristic token counts). All other body fields **pass through verbatim** to `openai-compat` providers — the daemon never gatekeeps provider capabilities. The Anthropic driver translates the text-chat subset (`messages`/`system`, `max_tokens`, `temperature`, `top_p`, `stop`, `stream`); fields it cannot translate (e.g. `tools`) return `501 UNSUPPORTED_BY_DRIVER`. Streaming: `openai-compat` streams are **byte-level pass-through** (echo headers are committed before the body starts); only the Anthropic driver re-maps events to `chat.completion.chunk` shape. For usage-based cost on streams, inject `stream_options: {"include_usage": true}` when absent; if the provider still omits usage, fall back to `costBasis: heuristic`.

---

## 6. Catalogue (consumed, not owned)

Source of truth lives in the owner's news-app repo. `CATALOGUE_URL = <OWNER-FILL>`. Until provided, drive everything from the committed fixture `fixtures/catalogue.v1.json`, authored to this schema:

```json
{
  "version": 1,
  "updatedAt": "2026-07-04T00:00:00Z",
  "providers": [{
    "id": "openrouter",
    "displayName": "OpenRouter",
    "kind": "openai-compat",            
    "baseUrl": "https://openrouter.ai/api/v1",
    "auth": { "type": "bearer" },
    "trainsOnData": false,
    "programmaticAllowed": true,
    "rate": { "rpm": 60, "rpd": 10000 },
    "pricing": { "llama-3.3-70b": { "inPerMTok": 0.10, "outPerMTok": 0.30 } },
    "models": ["llama-3.3-70b"]
  }],
  "models": [{
    "id": "llama-3.3-70b",
    "family": "llama",
    "kind": "chat",
    "ctx": 131072,
    "files": [{ "url": "https://…", "sha256": "…", "bytes": 40000000000, "quant": "Q4_K_M" }]
  }]
}
```

`kind` drives driver selection: `openai-compat` (generic, baseUrl-parameterized — covers OpenRouter/Groq/Together/Mistral/DeepSeek/Gemini-compat) and `anthropic` (native driver). Refresh: on demand + 24 h cache with ETag. Catalogue fetches are ledger rows (`egress: catalogue`). The fixture must contain **≥3 providers** — at least one with `trainsOnData:true` and at least two serving a common model at different prices — so §7's filter and ordering tests are exercisable.

---

## 7. Routing spec (deterministic, v1)

For each request: **resolve** the model selector → **filter** candidate providers (user key stored ∧ serves the model ∧ `programmaticAllowed` ∧ passes `No-Train` filter if set) → **order** by policy: `cheapest` = pricing ascending; `fastest` = latency EWMA ascending (persisted per provider); `best-reasoning` = catalogue rank field; `auto` = cheapest within the fastest latency band → **attempt** in order with a per-provider circuit breaker: on 429/5xx/timeout, put provider in cooldown (exponential backoff 30 s → 15 min cap) and fall to next → all exhausted = `ALL_PROVIDERS_COOLING`. `X-Asom-Fallback` overrides ordering; `X-Asom-Policy` overrides the default policy. Every request produces one `RouteRecord` → echo headers + ledger row. No ML, no embeddings, no NL parsing in v1.

---

## 8. Vault

AES-256-GCM master key in Android Keystore (**StrongBox when available**, TEE fallback) wraps a random data key; ciphertext + nonce in Room. One active key per provider. Write path exists only in the dashboard Keys tab. Redaction law: any logging path renders key material as `[REDACTED]`; include a unit test asserting no key bytes appear in captured logs.

---

## 9. Ledger

Room table `route_log`: `ts, callerPkg, requestedModel, servedProvider, servedModel, egress(local|cloud|catalogue|download), bytesOut, tokensIn, tokensOut, costEst?, costBasis, latencyMs, status`. Default is **metadata-only**. Opt-in **verbose mode** stores request/response bodies in a separate table with a **24 h TTL purge job** and a persistent notification while active. Export: user-triggered JSON share only — the ledger itself never leaves the device automatically.

---

## 10. Storage & sharing

WorkManager downloads (resumable, wifi-only toggle) from catalogue `files[].url` → SHA-256 verify → `filesDir/models/{modelId}/`. Dashboard models tab: download, progress, pin, evict, per-model and total storage stats. Served read-only via §5.8. Download events are ledger rows (`egress: download`).

---

## 10A. Consuming-app integration & fallback (SDK-side)

This governs how *other* apps use ASOM. The `:client*` modules and `:sample-client` are built in this repo and must demonstrate all of it; other repos consume these modules.

### 10A.1 The one interface
Every consuming app codes against a single `InferenceClient` (in `:core:contract`) exposing an OpenAI-compatible surface (`chat`, `stream`, `embed`, `models`). The app's own logic never knows which implementation is live. Three implementations exist:

- **`RemoteAsom`** (`:client`) — talks to the ASOM daemon on `127.0.0.1:11435` over HTTP/SSE after AIDL pairing. No keys, no models, no engine in the consuming app.
- **`CloudOnly`** (`:client-cloud`) — the app does cloud-BYOK itself: HTTPS to providers using keys entered into **the app's own** Keystore vault. No engine, no local models. Lightweight. **Available from v1.**
- **`Embedded`** (`asom-standalone`, **v2**) — the app runs local generation itself via the embedded engine. Heavy. Only opt-in, on-device-identity apps include it.

### 10A.2 Fallback tiers (declared per app, at build time)
- **Default tier** = `RemoteAsom` when ASOM is present and paired, else `CloudOnly`. Local inference simply requires ASOM. This keeps the app light and makes the dedup guarantee airtight: on-device models live in exactly one place (ASOM) or nowhere — never *N* copies.
- **Standalone tier** (opt-in; e.g. FoneBru) = additionally includes `Embedded`, so the app runs local models with **no ASOM present**. Even here the resolver prefers ASOM's shared model files when ASOM *is* installed (reads the `content://xyz.mdhv.asom.models` fd from §5.8 rather than downloading a second copy), shrinking duplicate-model storage to the one rare tail: a standalone app **and** a user who declined ASOM. Acceptable.

`FallbackResolver` (in `:client`) picks the impl in priority order **RemoteAsom → Embedded (if bundled) → CloudOnly**, re-evaluating when ASOM is installed/removed mid-life. Removal of ASOM must degrade gracefully to the app's next available tier with no data loss and no crash — this is the whole point of "optional."

### 10A.3 Keys never transfer (reinforces Invariant 4)
Keys are **human-entered into whichever surface will hold them**, and never move between surfaces in either direction:
- `RemoteAsom` apps hold **no keys** — they route through ASOM, whose vault the user filled in ASOM's Keys tab.
- `CloudOnly` / `Embedded` apps hold keys in **their own** vault, entered by the user in that app.
- ASOM never emits a key to an app, and never ingests a key from an app (§1.4). **Late adoption is a handoff, not a push:** when an app detects ASOM installed but lacking a key the app has, it shows "ASOM can manage this key for all your apps — open ASOM to add it," the user re-enters it *in ASOM*, and the app then drops its local copy and switches to `RemoteAsom`. A secret only ever crosses a human-verified boundary. No `authOnce`, no double-auth, no programmatic key exchange — the vault write-path integrity is the product's spine.

### 10A.4 Sibling detection (backend-free, channel-robust)
So the suite can recognize itself without a server and without coupling to signing keys (first-party apps span Play's per-app signing, F-Droid, and direct APKs — cert-matching is *not* reliable across those; package names are), each first-party app **optionally** exposes an `InventoryProvider` (shipped in `:client`): a read-only `ContentProvider`, authority `<pkg>.asom.inventory`, reporting `{ modelIds[], bytesHeld, appLabel }`. Model inventory is not sensitive, so no permission is needed. A sibling sums these to compute both **how many suite apps are installed** and **how much storage would be reclaimed** by deduping to ASOM. Detection uses a `<queries>` list of known suite packages (configurable; defaults to the owner's suite). Apps that don't register the provider simply don't contribute to the estimate — graceful. This is **not** a security boundary; ASOM's AIDL cert-verification (§5.7) remains the only trust gate.

### 10A.5 Adoption nudges (must not become spam)
A nudge may fire **only when the user is in a state ASOM would concretely improve**, and always shows the quantified value:
- **Single app, no ASOM** → fully functional on `CloudOnly`; **no nagging**. At most one dismissible, low-key mention that centralized routing exists. Never a launch modal, never a wall.
- **≥2 suite apps detected** (via §10A.4) → "You have {appLabels} — install ASOM to store models and keys once. You'd reclaim **{reclaimableBytes}** across **{n}** apps." The pitch appears only when the duplication it solves is real.
- **ASOM present, app holding a local key/model** → offer the §10A.3 handoff + switch to shared model files, showing the storage reclaimed.

`NudgePolicy` (in `:client`) enforces: dismissible; dismiss = long cooldown; a hard lifetime cap on prompts per app; suppressed entirely for single-app users. UI is the consuming app's own (Hyle later); `:client` provides only the *signals* and the policy gate.

### 10A.6 Design intent for consuming apps (Hyle-realized, not built here)
In a consuming app's inference/keys surface, ASOM should read as **a fixed, provenance-glowing header** — the canonical, always-visible home for keys and routing: when ASOM is present, "Keys & models managed by ASOM" with the warm-radium/cyan provenance treatment; when absent, the install affordance. This is a Hyle concern surfaced later through the token seam; **no UI is designed in this repo** (Invariant 7). Noted here so the seam reserves the slot.

---

## 11. Phases & gates (log every gate to `PROGRESS.md`)

- **P0 — Bootstrap.** Module skeleton per §4, version catalog, `LICENSE` (Apache-2.0), README stub, `CLAUDE.md` at root distilling §1 invariants, §3 build commands, §4 dependency law, and the gate list (future sessions read it first), CI (`.github/workflows/ci.yml`: JVM tests + `assembleDebug` artifact, Java 17, Gradle cache). *Gate:* CI green on skeleton.
- **P1 — Contract + catalogue.** DTOs, parser, fixture, golden tests. *Gate:* `:core:*` tests pass.
- **P2 — Routing core.** Router + cooldown FSM + property tests (ordering laws, filter laws, breaker behavior). *Gate:* `./gradlew :core:routing:test`; ≥90% branch coverage on policy/cooldown logic.
- **P3 — Server, desktop-runnable.** Ktor CIO, all §5.2 endpoints, SSE, auth middleware (in-memory tokens), `FakeProvider` drivers; JVM integration tests against `127.0.0.1`. *Gate:* `./gradlew :server:run` on desktop + committed curl transcript test.
- **P4 — Real drivers.** `OpenAICompatDriver(baseUrl)` + `AnthropicDriver`; upstream-SSE → OpenAI-SSE normalization; usage→cost mapping. *Gate:* mock-server JVM tests green; real-key smoke = `NEEDS-OWNER-VALIDATION` (owner curls from Deck).
- **P5 — Android shell.** `:app` foreground service (`specialUse` + property) hosting the server; real `:vault`; ledger persistence; minimal dashboard (status, keys, ledger). *Gate:* CI APK artifact; on-device checklist → `NEEDS-DEVICE-VALIDATION` (RedMagic).
- **P6 — Pairing + client + fallback (§10A).** AIDL service + consent sheet + token store; `:client` SDK (discovery, pair, streaming chat, `FallbackResolver`, `InventoryProvider`, `NudgePolicy`); `:client-cloud` (`CloudOnly` impl with its own Keystore vault); pin the `InferenceClient` + SDK surface in `docs/CLIENT_API.md` *before* implementing and treat it as contract, since it ships inside other apps. `:sample-client` must prove the full tier behavior: paired streaming via `RemoteAsom`, **automatic fallback to `CloudOnly` when ASOM is absent/removed**, and a nudge firing only under the §10A.5 conditions. *Gate:* device checklist including uninstalling ASOM mid-session and observing graceful `CloudOnly` fallback.
- **P7 — Storage.** Downloads, fd provider, pin/evict. *Gate:* device checklist including a second app reading a model fd via `:sample-client`.
- **P8 — Watched-object polish.** Echo headers asserted end-to-end; Hotspot tab (list/revoke); verbose mode + TTL; quick-settings tile; boot-start toggle (**default OFF** — nothing runs unless the user starts it); notification surfaces live state (idle/streaming/provider). *Gate:* execute and commit `QA_V1.md` manual script.
- **Phase 2 (separate brief, do not start):** `:inference` engine (llama.cpp JNI), semantic tiers, `Route-NL`, loops.

---

## 12. Testing & anti-overclaim discipline

Never mark a gate passed without pasting real command output into `PROGRESS.md`. No fabricated logs or test results. If blocked, write `BLOCKED(<reason>)` and stop. Device-only items are `NEEDS-DEVICE-VALIDATION` and remain open until the owner confirms — do not claim them done.

---

## 13. Do-not list

No telemetry libraries · no Firebase/GMS · no `0.0.0.0` bind · no endpoints/headers beyond §5 · no package renames · no engine code in v1 (`NoopEngine` only) · no KMP · no UI ambition beyond functional Compose/Material3 against the token seam · no extra egress classes · no chakravyuha-specific hooks (see §15).

---

## 14. Owner tasks (the parts only Madhav can do)

1. **(P0)** Create the repo `asystemofcells/asystemofmodels` (public, Apache-2.0) — or authenticate `gh` in the session and delegate: `gh repo create asystemofcells/asystemofmodels --public --license apache-2.0`.
2. **(by P1, ongoing)** Mirror §6 schema into the news-app catalogue: add `pricing`, `rate`, and model `files[]` (URL/sha256/bytes) entries; paste the raw `CATALOGUE_URL` into this brief's `OWNER-FILL`.
3. **(P4)** Provide at least two provider keys and run the real-key smoke curls from the Deck; mark the P4 validation item.
4. **(P5+)** RedMagic sideload validation per each device checklist (adb from Deck distrobox or Termux).
5. **(P8)** Decide distribution order (sideload → F-Droid first; Play later requires the `specialUse` justification); create and safeguard a release keystore when distributing.
6. **(anytime)** Confirm Apache-2.0; publish the Hyle token contract or explicitly bless the placeholder seam.
7. **(P6)** Provide the first-party suite package list for §10A.4 sibling detection (e.g. `xyz.mdhv.fonebru`, `com.clackpad.ime`, …) — ships as editable config, not hardcoded; unknown/empty list just disables the nudge.

---

## 15. Relationship boundary — chakravyuha

asystemofmodels is a **standalone product**. chakravyuha (the larger sovereignty tool that works with Aarso) MAY consume it strictly as a client: the localhost API, the `:client` SDK, and — if later needed — a read-only ledger endpoint added through the normal public-contract process. asom never imports, references, or special-cases chakravyuha; features it wants arrive as ordinary public API proposals. **One-way dependency, no shared private code.** This is the line that prevents forked work.

---

## 16. First commands (after owner task 1)

Clone the repo, commit this brief at the root as `ASOM_BUILD_BRIEF.md`, then execute P0. Work phase by phase; never skip a gate.
