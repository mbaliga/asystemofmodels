# asom client SDK — public API contract (§10A)

**Status: PINNED (brief P6). This surface ships inside other people's apps —
changes after publication are breaking. Additions require owner sign-off,
mirroring the §5 freeze.**

Artifacts: `xyz.mdhv.asom:asom-contract` (pure JVM) ·
`xyz.mdhv.asom:asom-client` (`xyz.mdhv.asom.client`) ·
`xyz.mdhv.asom:asom-client-cloud` (`xyz.mdhv.asom.clientcloud`).
Visible dependencies: contract + OkHttp + kotlinx-coroutines. Nothing else.

---

## The one interface (§10A.1)

Every consuming app codes against `InferenceClient` — the app's own logic
never knows which implementation is live.

```kotlin
// xyz.mdhv.asom.contract.client (in :core:contract — pure JVM)
interface InferenceClient {
    suspend fun chat(requestJson: String, options: RequestOptions = RequestOptions()): InferenceResponse
    fun chatStream(requestJson: String, options: RequestOptions = RequestOptions()): InferenceStream
    suspend fun embeddings(requestJson: String): InferenceResponse
    suspend fun models(): InferenceResponse
}

data class RequestOptions(          // the §5.3 headers, typed
    val policy: Policy? = null,     // X-Asom-Policy
    val fallback: List<String> = emptyList(), // X-Asom-Fallback
    val noTrain: Boolean = false,   // X-Asom-No-Train
)

class InferenceResponse(
    val status: Int,
    val bodyJson: String,
    val servedBy: String?,  // X-Asom-Served-By ("provider/model")
    val egress: String?,    // X-Asom-Egress ("local" | "cloud")
    val costEst: String?,   // X-Asom-Cost-Est (USD, only when derivable)
    val costBasis: String?, // X-Asom-Cost-Basis ("usage" | "heuristic")
)

interface InferenceStream {
    val headers: InferenceResponse  // status + echo headers, body empty
    val chunks: Flow<String>        // SSE data payloads, excluding [DONE]
    fun cancel()
}
```

Three implementations:

| Impl | Module | Ships in v1 | What it is |
|---|---|---|---|
| `RemoteAsom` | `:client` | ✔ | routes through the asom daemon on 127.0.0.1 after AIDL pairing — no keys, no models, no engine in the app |
| `CloudOnly` | `:client-cloud` | ✔ | the app does cloud-BYOK itself: HTTPS + its own Keystore vault |
| `Embedded` | `asom-standalone` | v2 (seam only) | in-app engine for opt-in on-device-identity apps |

## Fallback tiers (§10A.2)

```kotlin
class FallbackResolver(
    context: Context,
    embedded: InferenceClient? = null,   // v2 seam; null in v1
    cloudOnly: InferenceClient? = null,  // from :client-cloud, if bundled
) {
    enum class Tier { REMOTE_ASOM, EMBEDDED, CLOUD_ONLY, NONE }
    data class Resolved(val tier: Tier, val client: InferenceClient?)

    fun remoteAsom(): RemoteAsom
    fun resolve(): Resolved   // re-evaluated per call — asom install/removal
                              // mid-life degrades/upgrades gracefully
}
```

Priority: **RemoteAsom → Embedded (if bundled) → CloudOnly**. Removing asom
must degrade to the next tier with no data loss and no crash.

## Discovery (§5.1)

```kotlin
object AsomDiscovery {
    fun discover(context: Context): AsomEndpoint?  // null = asom not installed
    fun defaultEndpoint(): AsomEndpoint            // documented port fallback
}
data class AsomEndpoint(val port: Int, val version: String, val capabilities: Capabilities)
```

## Pairing (§5.7)

```kotlin
class AsomPairing(context: Context) {
    suspend fun status(): PairingStatus
    suspend fun pair(activity: Activity): PairingStatus  // full consent flow
    fun token(): String?    // locally persisted bearer token
    suspend fun fetchToken(): String?  // one-shot recovery of undelivered token
    fun forget()            // local only; revocation is daemon-side
}
enum class PairingStatus { NOT_INSTALLED, NOT_PAIRED, PENDING, PAIRED, REVOKED }
```

Flow (landmine handled): the SDK binds AIDL (action `xyz.mdhv.asom.PAIR`),
registers the request (identity = daemon-side `Binder.getCallingUid()`,
never extras), then launches asom's consent activity from the CLIENT's
foreground context. The daemon stores only the token's SHA-256; the raw
token is delivered exactly once and persisted app-side. Losing local storage
means re-pairing.

## Keys never transfer (§10A.3)

- `RemoteAsom` apps hold **no keys**.
- `CloudOnly`/`Embedded` apps hold keys in **their own vault**
  (`ClientVault` in `:client-cloud`), entered by the user in that app.
- asom never emits or ingests a key. Late adoption is a **human re-entry
  handoff**: the app deep-links the user to asom's Keys tab, the user
  re-types the key there, the app drops its local copy and switches tiers.
  No programmatic key exchange, ever.

```kotlin
// xyz.mdhv.asom.clientcloud
class CloudOnly(context, providers: List<CloudProvider>, client: OkHttpClient = ...) : InferenceClient {
    val vault: ClientVault  // storeKey/getKey/hasKey/deleteKey/providersWithKeys
}
data class CloudProvider(val id: String, val baseUrl: String, val models: List<String> = emptyList())
```

## Sibling detection (§10A.4)

```kotlin
class InventoryProvider : ContentProvider   // register at <pkg>.asom.inventory
object InventoryRegistry { fun set(value: AppInventory) }
data class AppInventory(val modelIds: List<String>, val bytesHeld: Long, val appLabel: String)

object SuiteInventory {
    fun query(context: Context, suitePackages: List<String>): Summary
    // Summary: suiteAppCount, reclaimableBytes, appLabels
}
```

`suitePackages` is editable config (OWNER-FILL: the first-party suite list;
brief §14.7). Unknown/empty list disables detection and the multi-app nudge.
Not a security boundary — §5.7 AIDL cert verification is the only trust gate.

## Nudges (§10A.5 — must not become spam)

```kotlin
class NudgePolicy(dismissCooldownMs = 30 days, lifetimeCap = 3) {
    fun decide(signals: Signals, state: State, nowMs: Long): Decision
    fun onShown(state: State): State
    fun onDismissed(state: State, nowMs: Long): State
}
// Decision: None | SuggestInstall(count, reclaimableBytes, labels) | SuggestHandoff(bytes)
```

Laws (unit-tested): single-app users are never nagged; the install pitch
appears only when ≥2 suite apps make the duplication real, always with the
quantified value; handoff offer only when asom is present AND the app holds
a local key/model; dismiss = 30-day cooldown; lifetime cap 3 prompts.

## Errors

HTTP errors surface as `InferenceResponse` with the OpenAI envelope and
typed `code` (§5.6). The SDK throws only on transport failures (IOException).
`RemoteAsom` without pairing returns a synthesized `401 NOT_PAIRED`.

## Model file access (§5.8 — for llama.cpp-embedding apps)

Not wrapped in v1: open `content://xyz.mdhv.asom.models/models/{modelId}`
with `ContentResolver.openFileDescriptor(uri, "r")` and load via
`/proc/self/fd/<fd>`. Requires an active pairing. Even standalone (v2) apps
must prefer this fd over downloading a second copy when asom is present
(§10A.2).

## AIDL wire contract (internal, stable)

`xyz.mdhv.asom.ipc.IAsomPairing`: `requestPairing(IAsomPairingCallback)`,
`getToken()` (raw token exactly once post-approval), `getStatus()`; ints in
`xyz.mdhv.asom.contract.PairingStatusCode`.
