# P6 device checklist — pairing + client + §10A fallback (RedMagic)

All items `NEEDS-DEVICE-VALIDATION` until the owner confirms (brief §12).
Install BOTH CI artifacts: `asom-debug-apk` and `asom-sample-client-apk`.

## Pairing (§5.7)

- [ ] asom running; open sample client → **Status** shows `asom: port=11435 …` and `resolved tier: CLOUD_ONLY` (not yet paired)
- [ ] Tap **Pair** → asom's consent sheet opens showing the sample client's
      verified label + package + cert fingerprint (identity from the binder, not extras)
- [ ] Dismiss without deciding ("Decide later") → sample shows `PENDING`; no token minted
- [ ] Re-pair → **Approve** → sample shows `PAIRED`; asom Hotspot tab lists the app as `● paired`
- [ ] **Status** now shows `resolved tier: REMOTE_ASOM`
- [ ] Send (stream) → streamed completion; `served-by`/`egress` echo headers displayed; asom Ledger tab shows the row with callerPkg = sample package ✓ §1.9
- [ ] asom Hotspot tab → **Revoke** → next send fails `401 TOKEN_REVOKED`; sample `status()` = REVOKED
- [ ] Remove + re-pair works

## §10A fallback tiers — THE gate item

- [ ] With asom paired and running: tier = REMOTE_ASOM, streaming works
- [ ] Save a CloudOnly key (app's own vault) in the sample
- [ ] **Uninstall asom mid-session** → tap **Status** → `asom not installed`, `resolved tier: CLOUD_ONLY`; **Send** streams via the app's own key — no crash, no data loss
- [ ] Reinstall asom + pair → tier returns to REMOTE_ASOM without reinstalling the sample

## §10A.5 nudge conditions

- [ ] Suite list empty (default) → nudge decision always `None` (single-app users never nagged)
- [ ] (After owner fills suite packages) with a sibling app installed and no asom → `SuggestInstall` with reclaimable bytes + labels; dismiss → silent for 30 days; cap 3 lifetime
- [ ] asom installed + local CloudOnly key present → `SuggestHandoff`; the handoff is a human re-entry in asom's Keys tab (no programmatic transfer — §10A.3)

## Discovery (§5.1)

- [ ] With asom uninstalled: sample "Status" reports not installed (provider query returns null, no crash)
- [ ] With asom installed but daemon stopped: discovery still answers (provider is static); chat fails with connection refused — sample surfaces the error string
