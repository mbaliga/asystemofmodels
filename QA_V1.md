# QA_V1 — asom v1 manual QA script

Brief P8 gate. This is the consolidated device script covering everything
built across P0–P8; it supersedes the individual `docs/DEVICE_CHECKLIST_P*.md`
files (kept for phase-by-phase history). Target device: RedMagic (owner's).

**Status of this document:** build/test items below were executed in this
session with real command output (pasted). All UI/device-only items are
`NEEDS-DEVICE-VALIDATION` — they require the owner's hardware and are listed
here, unexecuted, per brief §12 (never fabricate a result).

---

## 0. What was actually executed this session (real output)

```
$ ANDROID_HOME=/opt/android-sdk gradle jvmTest \
    :vault:testDebugUnitTest :ledger:testDebugUnitTest \
    :client:testDebugUnitTest :storage:testDebugUnitTest \
    :app:assembleDebug :sample-client:assembleDebug

BUILD SUCCESSFUL in 30s
```

Per-module test counts observed via JUnit XML across the session:
`core:contract` 13 · `core:catalogue` 13 · `core:inference-api` 2 ·
`core:routing` 38 (incl. 6 seeded property-law tests; JaCoCo branch coverage
97.8–100% on Router/CooldownRegistry) · `server` 33 (23 integration + 6
Anthropic driver + 4 OpenAI-compat driver) · `vault` (DataKeyVault +
Redaction) · `ledger` (RecordMapping) · `storage` 3 (SHA-256 verify) ·
`client` 6 (NudgePolicy). Both `:app` and `:sample-client` debug APKs
assemble. Local Android SDK was installed in-session at `/opt/android-sdk`
specifically to reproduce and fix a P5 CI compile failure (see PROGRESS.md).

CI (`.github/workflows/ci.yml`) reruns all of the above plus JVM tests on a
bare JDK (no Android SDK) on every push — see PROGRESS.md for run links.

---

## 1. Fresh install & first run

- [ ] Install `asom-debug-apk`; launch → Status tab shows `○ daemon stopped`, endpoint `http://127.0.0.1:11435`, local engine `absent`
- [ ] **Start on boot** toggle defaults OFF (brief invariant: nothing runs unless the user starts it)
- [ ] Tap **Start** → notification "asom · idle — serving on 127.0.0.1:11435"; Status flips to `● daemon running`
- [ ] Reboot device with the toggle still OFF → asom does NOT auto-start
- [ ] Enable **Start on boot**, reboot → asom auto-starts; notification present without opening the app

## 2. Watched-object core (§0/§1.9)

- [ ] `curl http://127.0.0.1:11435/admin/health -H "Authorization: Bearer <Status tab token>"` → `{"status":"ok",...,"hasLocalEngine":false}`
- [ ] No token → `401 NOT_PAIRED`
- [ ] Send a chat completion with a stored key → response carries `X-Asom-Served-By` / `X-Asom-Egress: cloud` / `X-Asom-Cost-Est`+`Basis`; Ledger tab row for the SAME request shows identical provider/model/egress/cost — confirm by comparing header values to the ledger row's displayed fields side by side (invariant §1.9; automated equivalent already asserted in `AsomServerIntegrationTest`)
- [ ] During the request, notification updates to "serving a request…" then a streaming request shows "streaming via `<provider>`…", then returns to "idle" (§11 P8 live state)
- [ ] `local-only` model → typed `501 LOCAL_ENGINE_ABSENT`, ledger shows `egress: local`, `bytesOut: 0`

## 3. Vault (§8)

- [ ] Keys tab: save a real provider key → `● key stored`; kill+relaunch app → still stored; reboot device → still stored and routable (Keystore survives)
- [ ] Delete key → `○ no key`; routed request now fails `NO_PROVIDER_KEY`
- [ ] No key bytes appear in `adb logcat` during a save/route cycle (redaction law; unit-tested in `RedactionTest`, spot-check logcat manually too)

## 4. Pairing + Hotspot (§5.7)

- [ ] Install `asom-sample-client-apk`; tap **Pair** → asom's consent sheet shows the sample's verified label/package/cert fingerprint (from the binder, not extras)
- [ ] "Decide later" → sample stays `PENDING`, no token minted
- [ ] Approve → sample `PAIRED`; asom Hotspot tab lists it `● paired`
- [ ] Sample streams a chat completion through asom; ledger row's `callerPkg` matches the sample's package
- [ ] Hotspot tab **Revoke** → next sample request fails `401 TOKEN_REVOKED` immediately (no restart needed)
- [ ] **Remove** clears the row entirely; re-pairing works

## 5. §10A fallback tiers — cross-cutting gate

- [ ] Sample paired + asom running → resolved tier `REMOTE_ASOM`; streaming works
- [ ] Save a CloudOnly key in the sample (its own vault)
- [ ] **Uninstall asom** while the sample is open → next **Status** tap shows `asom not installed`, tier `CLOUD_ONLY`; **Send** still streams via the sample's own key — no crash
- [ ] Reinstall + re-pair asom → tier returns to `REMOTE_ASOM` without touching the sample
- [ ] With the (default empty) suite package list, nudge decision is always `None` — confirm no nudge ever appears for this single sample app

## 6. Storage (§10/§5.8)

- [ ] Models tab: download `llama-3.3-70b` on wifi → progress updates; completes to `● downloaded`; one `egress: download` ledger row
- [ ] Kill app mid-download, reopen → resumes (WorkManager persistence)
- [ ] Pin → Evict disabled; unpin → Evict removes the file, storage total drops
- [ ] Sample client: **Open model fd (§5.8)** → reports a real `size=` matching the file; works only while paired (revoke → fails)
- [ ] `adb shell find /data/data/xyz.mdhv.asom -name '*.gguf'` → exactly one copy on disk even with two apps referencing it

## 7. Verbose mode + TTL (§9, §11 P8)

- [ ] Status tab: enable **Verbose ledger mode** → a persistent-notification-worthy state is active (WorkManager periodic purge scheduled; confirm via `adb shell dumpsys jobscheduler | grep asom` or WorkManager's own diagnostics)
- [ ] Send a request with verbose mode on → a `verbose_log` row exists (inspect via a temporary debug query or Room Inspector) containing the request/response bodies
- [ ] Wait past 24h (or temporarily shrink `VerbosePurgeWorker.TTL_MS` in a debug build) → the row is purged by the next hourly worker run
- [ ] Disable verbose mode → no new verbose rows written; existing rows still purge on schedule

## 8. Quick-settings tile (§11 P8)

- [ ] Add the "asom" tile to the quick-settings shade
- [ ] Tap while stopped → daemon starts, tile flips to active/"serving"
- [ ] Tap while running → daemon stops, tile flips to inactive/"stopped"

## 9. Colorblind check (§1.6 — owner-verified by definition)

- [ ] Every state indicator across Status/Hotspot/Models/Keys/Ledger is readable via shape + label alone with color removed mentally; violet/cyan are the only semantic hues used, never red/green

## 10. Distribution sanity

- [ ] Sideloaded APK installs cleanly with no GMS/Play Services prompts (invariant §1.8)
- [ ] App requests no permissions beyond those declared in the manifest (INTERNET, FOREGROUND_SERVICE[_SPECIAL_USE], POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED)

---

## Sign-off

This script has not yet been executed on hardware. Per brief §12, all of
§1–§10 above remain `NEEDS-DEVICE-VALIDATION` until the owner runs them on
the RedMagic and confirms. Real-key cloud smoke (P4) is a separate
`NEEDS-OWNER-VALIDATION` item logged in `PROGRESS.md`.
