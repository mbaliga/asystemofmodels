# Device checklist: iPhone and iPad

**PLAN, nothing run, no app exists.** No asom iOS or iPadOS app has been built, signed or installed. Nothing here was run on an iPhone, an iPad or a Simulator.

Source: `docs/design/mesh/platforms/ios.md` section 10.5 (the ID, device and expected columns are copied from it; the steps column is this track's expansion of the spec's Step column), PLATFORM_PLAN section 6, step I5.

**Every item below is `NEEDS-DEVICE-VALIDATION` until the owner pastes real output.** Simulator and hosted-runner results never close an item of this list. The "expected" column is what a success looks like, not a result. The Device column of each row says which device it needs.

How to record an item: paste the command or screenshot description, the full output and the date into the "Your output" cell, then change its Status cell. Leave a failing or surprising result in place; a fix that hides it is worse than the result.

The `apple/ci/check_device_checklist.py` script keeps this file in step with `ios.md`: every DV-I id appears exactly once, every row carries a label, and the word for a success verdict is absent until you write one yourself in a pasted output.

## Checklist

| ID | Device | Steps | Expected | Your output | Status |
|---|---|---|---|---|---|
| DV-I1 | iPhone, iPad | Run an M3-only session (no pairing, no peer). Then open Settings > Privacy & Security > Local Network and look for asom. | no "Local Network" entry for asom | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I2 | iPhone, iPad | Run the `quick` plan with the Q1 T1 model on Metal. Open the report, then the ledger view. | report renders; `backend=metal`; ledger has only `download` rows (plus `catalogue` if fetched) | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I3 | iPhone | Put the iPhone on the charger and run the `standard` plan to its end. Note the thermal state at each stop in the ledger. | soft stop at `fair` + 120 s or hard stop at `serious`, logged; partial result finalised | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I4 | iPad | Export a signed report on the iPad. On the Deck run the JVM verifier on the file, typing the fingerprint shown on the iPad. | `PINNED_BY_FINGERPRINT(typed)` | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I5 | iPhone | Start a Metal decode. Swipe to the Home Screen mid-decode. Reopen, rerun, and repeat 20 times in all. Count crashes. | **no crash** (IA01); run aborted with `backgrounded`; each time a partial row | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I6 | iPad Pro 16 GB | On the 16 GB iPad Pro run the 8B Q4_K_M decode and prefill for a 10-minute sustain. Record tokens per second at start and end. | numbers recorded; they replace IA17 (never quoted as speed-ups before) | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I7 | each device | On each device load increasingly large tiers until `MODEL_OOM`. Log `os_proc_available_memory()` before and after each load. Repeat with and without the `increased-memory-limit` entitlement. | the largest tier that fits; with and without `increased-memory-limit` | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I8 | iPhone | Install two owner apps from the same Team that share one NIK, and a third app from another Team. Try to use the NIK from all three. | shared use works; foreign access fails | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I9 | iPhone + Deck | Pair the iPhone with the Deck by QR over the LAN. Watch for the Local Network prompt at the first dial. Deny it, dial again, then re-grant in Settings and dial again. | `DIAL` outcome `local-network-denied`, then success after re-grant | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I10 | iPhone + Dell | Pair the iPhone with the Dell and stream over Tailscale (Headscale if D8(b) is chosen). Read both ledgers. | no Local Network prompt; `peerPath=overlay` on both ledgers | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I11 | iPhone | Start a stream, then leave the host app (Home Screen). Read the app, then both ledgers. | stream finishes within the grace, or `CANCEL` + `interrupted` rows on both sides; typed error in the app | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I12 | iPhone | Switch the home lender off and send a request. Read the echo header and the ledger. | no silent cloud call; fallback only if pre-set policy allows it, and the echo says so | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I13 | iPad + Android | Put the iPad on charger, open the lend screen, and borrow from an Android phone over the LAN. Read both ledgers. | served; both ledgers joined on `attemptId` | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I14 | iPad | During a served stream, pull down Control Center on the iPad. Wait 10 minutes and check whether SERVING is published again. | DRAINING at once; current attempt ends per §3.2; SERVING not re-published for 10 min | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I15 | iPad | During a served stream, lock the iPad with the side button. Unlock and read the lender ledger. | listener closed; `GOAWAY`; no crash; lender rows present | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I16 | iPad | Run a sustained served stream until the thermal state reaches `serious`. Read the requester's error and the lender ledger. | drains; `PEER_UNAVAILABLE` to the requester | _paste output here_ | NEEDS-DEVICE-VALIDATION |
| DV-I17 | iPad | With the Tailscale tunnel up, capture packets at the gateway during a session. Record whether log.tailscale.com is contacted. | log.tailscale.com contacted or not: recorded for the D8 disclosure | _paste output here_ | NEEDS-DEVICE-VALIDATION |

## OWNER_STEPS

Everything below needs the owner's Apple account or hardware and has not been done. None of it is claimed to work.

1. Join the Apple Developer Program and record the Team ID (D22). Nothing on this list can start before it.
2. Decide the open items this list depends on: D14 part A, D15, D24, and the bundle id (iOS-D2; the owner's `com.asoc.<appname>` form needs a NAMES.md row first, OQ-25).
3. Get an Apple-silicon Mac with Xcode. Generate the project (`xcodegen`) once the app exists; no `project.yml` exists yet.
4. Sign and install development builds on the iPhone and iPad (iOS-D5 option a). CI builds unsigned only (iOS-D6).
5. Run the rows above, paste real output, and change each Status only after the output is pasted.
