# asystemofmodels (asom)

A **sovereign model-routing daemon for Android**. One app owns the model files, the BYOK cloud keys, the routing decision, and an egress ledger; it exposes an OpenAI-compatible HTTP API on `127.0.0.1:11435`. Every other app on the device is a thin client — no model files, no inference engine, no keys. Apps join via an "AI hotspot" pairing flow, exactly like devices joining a Wi-Fi hotspot.

**Status: v1 under construction.** See `ASOM_BUILD_BRIEF.md` for the frozen spec and `PROGRESS.md` for phase gates.

## Ethos

Sovereign · local-first · open-source · BYOK · **no telemetry** · no operator backend · one-time/free. The cloud is always a *watched object*: you can always see which model ran, on-device vs cloud, and what left the device.

## What v1 ships

- Deterministic cloud-BYOK routing (`cheapest | fastest | best-reasoning | auto | local-only`)
- OpenAI-compatible server (`/v1/chat/completions` with SSE, `/v1/completions`, `/v1/embeddings`, `/v1/models`)
- AIDL-verified pairing ("AI hotspot") + bearer tokens
- Android-Keystore key vault
- Shared model download/storage — install once, served read-only to paired apps via fd
- Egress ledger + per-response echo headers (`X-Asom-Served-By`, `X-Asom-Egress`, `X-Asom-Cost-Est`)
- Dashboard app, publishable `:client` SDK, `:sample-client` proof app

v1 does **not** ship a local generation engine — `local-only` returns a typed `501 LOCAL_ENGINE_ABSENT` and capabilities report `hasLocalEngine:false`. That's Phase 2.

## Building

Pure-JVM modules (`:core:*`, `:server`) build and test on a bare JDK 17+ — no Android SDK required:

```sh
./gradlew jvmTest        # all pure-JVM tests
./gradlew :server:run    # desktop-runnable server (from P3)
```

The full Android build needs an Android SDK (`ANDROID_HOME` or `local.properties`); without one, Android modules are automatically excluded so the JVM side keeps working:

```sh
./gradlew :app:assembleDebug
```

CI builds every push: JVM tests on a bare JDK, plus a debug APK artifact. Steam Deck setup notes: `docs/DECK_SETUP.md`.

## For client apps

Model files are served read-only via `content://xyz.mdhv.asom.models/models/{modelId}` to paired apps. Apps embedding llama.cpp can load the returned fd via the `/proc/self/fd/<n>` path; the kernel page cache dedups read-only mappings across processes, so N apps share one copy of the weights in RAM.

The `:client` SDK (discovery, pairing, streaming chat) is documented in `docs/CLIENT_API.md` (lands in P6).

## License

Apache-2.0. See `LICENSE`.
