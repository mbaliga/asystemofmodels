# asom client SDK — public API contract (§10A)

**Status: PINNED (brief P6). This surface ships inside other people's apps —
changes after publication are breaking. Additions require owner sign-off,
mirroring the §5 freeze.**

Artifacts: `xyz.mdhv.asom:asom-contract` (pure JVM) ·
`xyz.mdhv.asom:asom-client` (`xyz.mdhv.asom.client`) ·
`xyz.mdhv.asom:asom-client-cloud` (`xyz.mdhv.asom.clientcloud`).
Visible dependencies: contract + OkHttp + kotlinx-coroutines. Nothing else.

---

## Manifest: what merges in, what you must add

`:client` and `:client-cloud` each ship a manifest that merges into your app:

```xml
<!-- from :client (and INTERNET also from :client-cloud) -->
<uses-permission android:name="android.permission.INTERNET" />
<queries>
    <package android:name="xyz.mdhv.asom" />
    <intent><action android:name="xyz.mdhv.asom.PAIR" /></intent>
    <provider android:authorities="xyz.mdhv.asom.discovery;xyz.mdhv.asom.models" />
</queries>
```

`<queries>` is load-bearing, not hygiene: on API 30+ an app that cannot see
`xyz.mdhv.asom` gets an empty `queryIntentServices` and an unresolvable
provider authority, so `AsomDiscovery.discover` returns null and
`AsomPairing.pair` returns `NOT_INSTALLED` — **indistinguishable from asom
genuinely not being installed**, on a device where it is installed and running.
INTERNET is required even for the loopback call, because Android gates AF_INET
socket creation on that permission and 127.0.0.1 is not exempt.

Your app must add, for itself:

- **Sibling inventory authorities** (§10A.4) — one entry per package in your
  `suitePackages` list; the SDK cannot know them:
  `<queries><provider android:authorities="com.example.sibling.asom.inventory" /></queries>`
- **`InventoryProvider`**, if you want to contribute to siblings' estimates:
  `<provider android:name="xyz.mdhv.asom.client.InventoryProvider"
   android:authorities="${applicationId}.asom.inventory" android:exported="true" />`
  Publish the inventory from your `Application.onCreate` — a sibling's query can
  start your process without ever reaching an Activity.
- **A network security config** (invariant §1.2). The daemon is plain HTTP on
  loopback, everything else is HTTPS; copy
  `sample-client/src/main/res/xml/network_security_config.xml`. Never set
  `android:usesCleartextTraffic="true"` — that is an app-wide exception for
  *every* host.
- **Backup exclusion for the `:client-cloud` vault.** Its Keystore master key is
  never backed up, so a restored copy of the `asom-client-cloud-vault`
  SharedPreferences can never decrypt. A library cannot set your
  `<application>` backup attributes without a merge conflict, so exclude
  `asom-client-cloud-vault.xml` in your own `dataExtractionRules` /
  `fullBackupContent` (or set `android:allowBackup="false"`, as asom itself
  does). `ClientVault` degrades to "no key stored" — and prompts re-entry —
  rather than failing, but the user still re-types the key.

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

`chunks` is a cold Flow: collecting it issues the request, and it may be
collected more than once. It does its own I/O on `Dispatchers.IO`, so
collecting from a main-thread scope is safe. `headers` is the placeholder
`InferenceResponse(0, "")` until the first chunk has been collected; read it
after collection, not before. Cancelling the collector closes the response and
releases the connection; `cancel()` aborts the in-flight call outright.

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

## The `model` field on every tier (§5.5)

The same body must work whichever implementation is live, so **`CloudOnly`
accepts the virtual selectors too** — your app never branches on the tier.
`:client-cloud` has no catalogue and no router (routing is what asom is *for*),
so it resolves them with a fixed, deliberately dumb rule:

| `model` | `RemoteAsom` | `CloudOnly` |
|---|---|---|
| concrete id | routed by policy | first keyed provider that serves it |
| `auto` `cheapest` `fastest` `best-reasoning` | routed per §7 | **first keyed provider that declares models, and its first declared model** — declaration order is your preference order; the outgoing body's `model` is rewritten to that concrete id |
| `local-only` | `501 LOCAL_ENGINE_ABSENT` | `501 LOCAL_ENGINE_ABSENT` |

If no keyed provider declares any model, a virtual selector returns
`404 MODEL_UNKNOWN` — it is never forwarded upstream verbatim. With no key at
all the answer is `503 NO_PROVIDER_KEY`, as before.

`CloudProvider.baseUrl` must be `https://` (invariant §1.2); anything else
throws `IllegalArgumentException` at construction.

## Timeouts

Both default OkHttp clients use connect 10 s / write 30 s / **read 180 s**,
matching what the daemon itself allows its upstream provider. Do not hand in a
bare `OkHttpClient()`: its 10 s read timeout is shorter than a routine
completion takes, so the call fails client-side while the user is still billed
for it and the ledger records an egress the app never saw.

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

The bind is pinned to the `xyz.mdhv.asom` package: the action is public, so
without the pin any installed app could publish a matching service and a
look-alike consent sheet. Signing certificates are deliberately *not* checked —
§10A.4 records that first-party cert matching is unreliable across
Play/F-Droid/direct-APK channels; trust still rests on the daemon's
`Binder.getCallingUid()` check (§5.7).

`pair()` always returns. "Decide later" and a back-press finish the sheet
without any decision reaching the callback, so the SDK also treats your activity
coming back to the foreground as an answer and re-reads the daemon's
`getStatus()` — yielding `PENDING`. Call `pair()` again later, or `fetchToken()`
to pick up a token approved out of band.

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
