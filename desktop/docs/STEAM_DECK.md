# asom on the Steam Deck (SteamOS)

**Status: scaffold. Nothing here has been run on a Deck.** Every Deck statement below is a rule the code enforces in tests
against SYNTHETIC fixture trees, or an open question. Deck behaviour stays `NEEDS-DEVICE-VALIDATION` (checklist ids DV-D1
to DV-D11 in `docs/design/mesh/PLATFORM_PLAN.md` section 3). Authority: `platforms/linux.md` sections 3.3 and 3.4 and
`desktop/ERRATA.md` (ERR-DECK-1, ERR-DECK-2, ERR-FSM-4).

## How it runs

- USER mode only (plus FOREGROUND for development). SYSTEM mode is refused on SteamOS: no root-managed install survives a
  SteamOS update. The node runs from a `$HOME` tarball as a user unit (`asom-user.service`), which is **not** sandboxed:
  every game runs as the same user and could read the node's key, ledger and models.
- Off by default. Nothing enables or starts the unit. Start at login is `systemctl --user enable asom`; start at boot
  needs `loginctl enable-linger deck`. Whether linger and the user manager survive Game Mode and Desktop Mode switches and
  SteamOS updates is assumed, not verified (DV-D2, DV-D3).

## Rules the host enforces

| Rule | Where | State |
|---|---|---|
| Never a block lock. A delay lock only. | `SteamOsPolicy.blockLockAllowed` is a constant `false`; the Inhibitor refuses a block lock before spawning anything; no configuration changes it | tested (LAB); the reason (a fake sleep in Game Mode) rests on an inference that a delay lock avoids it, settled only by DV-D4 |
| Never on battery. An unknown power source counts as battery. | `SteamOsPolicy` | tested against SYNTHETIC trees |
| Lends only docked, on AC, with no game running | `SteamOsPolicy` | **cannot be read yet**: there is no specified way to see that the Deck is docked, that a game runs or that it is in Game Mode. Unknown counts as the unsafe answer, so a real Deck stays ARMED and does not lend until a mechanism is ruled (ERR-DECK-1) |
| 2 s drain grace | `graceMsDeck` | tested; a light game is caught only by sustained contention (400 permille for 10 s), so "drains within 2 s of a game start" is NOT claimed |
| Game Mode lending needs an explicit opt-in (`gameModeLending`) | `SteamOsPolicy` | tested; off by default |

## What you will not see

**While in Game Mode, this Deck can lend compute without showing anything on its screen.** That is only possible after you
opt in with `gameModeLending`, and it is the price of the opt-in. Lending in Game Mode is visible on the requesting device
and through `asom status` over SSH or in Desktop Mode.

## Sleep

The node cannot keep a Deck awake. If a docked Deck should keep lending, set Steam's plugged-in sleep to "Disabled". The
node holds only a delay lock, so pressing the power button still suspends the Deck; the node is told through logind's
`PrepareForSleep` and drains within the 5 s delay-lock budget. Whether SteamOS's own sleep path honours a delay lock and
sends the signal (assumptions LA01 and LF05) is exactly what DV-D4 must show on a real Deck.

## Not built or not known

- The PF role (`asom lend --foreground`, which should stop when its terminal loses focus) has no mechanism on Linux for observing focus loss (ERR-FSM-4); it drains only on contention.
- GPU counters on SteamOS (`gpu_busy_percent`, `drm-engine-gfx`) are unverified (DV-D5).
- The Vulkan heap budget for an 8B model, firewall state, Tailscale behaviour and `swap.max = 0` under the user manager
  are unverified (DV-D6, DV-D8, DV-D10, DV-D11).
