# P7 device checklist — storage (RedMagic)

All items `NEEDS-DEVICE-VALIDATION` until the owner confirms (brief §12).

## Downloads (§10)

- [ ] Models tab lists every catalogue model with `files[]` (v1 fixture: `llama-3.3-70b`)
- [ ] Tap **Download** on wifi → progress updates (bytes downloaded / total) via WorkManager
- [ ] Toggle off wifi mid-download (or start on cellular with wifi-only default) → download pauses/queues, resumes when wifi returns (WorkManager constraint)
- [ ] Kill the app mid-download, reopen → download resumes (WorkManager persistence), does not restart from zero on a resumable connection
- [ ] On completion: status flips to `● downloaded`; ledger has one `egress: download` row for the model
- [ ] Corrupt the downloaded file on disk (adb shell) → re-download detects/repairs (or: intentionally point sha256 wrong in a debug build and confirm `sha256 mismatch` failure path deletes the partial file)

## Pin / evict

- [ ] Pin a downloaded model → **Evict** button disabled
- [ ] Unpin → **Evict** removes the file; storage total drops; status returns to `○ not downloaded`
- [ ] Total storage figure on the Models tab matches `du -sh` on `filesDir/models/` via adb

## fd sharing (§5.8) — THE gate item: a SECOND app reads a model fd

- [ ] Pair `:sample-client` (see P6 checklist)
- [ ] With `llama-3.3-70b` downloaded in asom, tap **Open model fd (§5.8)** in the sample client → shows a real `size=` matching the file on disk
- [ ] Revoke the sample client's pairing → fd open now fails (returns null / SecurityException path), confirming the UID-pairing check gates access
- [ ] Without pairing at all (fresh install, never paired) → fd open fails
- [ ] Confirm via `adb shell` that only ONE copy of the weights exists on disk (`find /data/data/xyz.mdhv.asom -name '*.gguf'` — single hit) even though two apps reference it — the kernel page cache dedup claim from §5.8 is a runtime property, not something to assert in-app, but the single-copy-on-disk part is directly checkable
