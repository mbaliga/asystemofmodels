# P5 device checklist — Android shell (RedMagic)

All items `NEEDS-DEVICE-VALIDATION` until the owner confirms on hardware
(brief §12). Sideload the CI `asom-debug-apk` artifact or build locally per
`docs/DECK_SETUP.md`.

## Service & server

- [ ] Install APK, open asom → Status tab shows `○ daemon stopped`
- [ ] Tap **Start** → notification "asom · serving on 127.0.0.1:11435" appears; status flips to `● daemon running`
- [ ] From `adb shell` (or Termux):
      `curl -s http://127.0.0.1:11435/admin/health -H "Authorization: Bearer <token from Status tab>"`
      → `{"status":"ok",...,"hasLocalEngine":false}`
- [ ] No token → 401 `NOT_PAIRED`
- [ ] Confirm the port is NOT reachable from another device on the LAN
      (`curl http://<phone-ip>:11435/admin/health` from the Deck must fail) — invariant §1.2
- [ ] Tap **Stop** → notification gone, curl fails (connection refused)
- [ ] Battery: leave running 30 min idle; verify no crash / kill (specialUse FGS honored)

## Vault (Keystore — §8)

- [ ] Keys tab: save a real provider key (e.g. OpenRouter) → badge flips to `● key stored`
- [ ] Kill + relaunch app → badge still `● key stored` (Room + Keystore persistence)
- [ ] Reboot device → key still present, chat still routable (StrongBox/TEE key survives)
- [ ] Delete key → `○ no key`; routed request now fails `NO_PROVIDER_KEY`

## End-to-end routing (real cloud, BYOK)

- [ ] With a stored key, from adb shell:
      `curl -s http://127.0.0.1:11435/v1/chat/completions -H "Authorization: Bearer <token>" -d '{"model":"cheapest","messages":[{"role":"user","content":"hi"}]}'`
      → real completion; response carries `X-Asom-Served-By` / `X-Asom-Egress: cloud` / cost headers
- [ ] `stream:true` variant streams SSE chunks
- [ ] Ledger tab shows one row per request (incl. the failed ones above) with
      caller / served-by / egress / tokens / cost matching the response headers (§1.9)

## Colorblind check (§1.6 — owner-verified by definition)

- [ ] Every state indicator (daemon on/off, key present/absent, egress) is
      readable via shape + label alone; violet/cyan hues are distinguishable
