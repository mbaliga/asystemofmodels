# asom — Play Console answer sheet

> Only the **deltas** from `Personal-Tracker/store/HOUSE_DEFAULTS.md`.

| | |
|---|---|
| applicationId | `xyz.mdhv.asom` |
| Version at time of writing | `0.1.0` (versionCode `1`) |
| Category | **Tools** |
| Tags | ai, llm, api, byok, router, privacy, developer tools |
| Contact email | `asom@asystemofcells.com` |
| Website | `https://asystemofcells.com/asom` |
| Privacy policy | `https://asystemofcells.com/asom/privacy` |

## Deltas from the house defaults

### The listing must not overclaim v1
Brief invariant: v1 has **no local engine**; `local-only` returns
`501 LOCAL_ENGINE_ABSENT`. The description says so explicitly, in its own section.
Do not let a future edit imply on-device inference before it exists.

### Data safety
**No data collected. No data shared.**

| Question | Answer |
|---|---|
| Collect or share any user data? | **No** |
| Encrypted in transit? | Yes |
| Deletion? | Users can delete data in the app |

The subtlety, worth writing down because this app is *about* egress: asom makes
provider API calls **on the user's behalf, with the user's own keys, to providers
the user configured**. That is a user-directed transfer to a third party, not
collection by this app: nothing goes to us, and there is no server of ours. The
app's own ledger is the disclosure mechanism, and it is stronger than the Data
safety form requires. The privacy policy says all of this plainly.

Three invariants keep that answer true, and each is a build-failing rule in
`CLAUDE.md`: no automatic egress; the server binds `127.0.0.1` only, never
`0.0.0.0`; and every network event writes a ledger row.

### Permissions
| Permission | Why | Play form? |
|---|---|---|
| `INTERNET` | Provider API calls, the catalogue fetch, and model downloads. | No |
| `FOREGROUND_SERVICE` | Keep the local API server alive while other apps use it. | No |
| `FOREGROUND_SERVICE_SPECIAL_USE` | The declared type for that server. | **Yes, needs work — see below.** |
| `POST_NOTIFICATIONS` | The foreground-service notification. | No |
| `RECEIVE_BOOT_COMPLETED` | Restore the server after a reboot, if the user enabled that. | No |

**`FOREGROUND_SERVICE_SPECIAL_USE` is the one thing that will bounce this
submission.** Since Android 14 it requires a manifest property naming the subtype,
and Play reviews the justification by hand:

```xml
<service android:name=".server.AsomServerService"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="local_inference_api_server" />
</service>
```

The justification to give Play: the app runs a loopback-only API server that other
locally-installed apps connect to; it must stay alive while those apps are in the
foreground, and none of the predefined foreground-service types describes a local
server. **Consider `dataSync` first** — if the routing work is bounded and
user-initiated it may fit, and `dataSync` sails through review while `specialUse`
does not. Decide this before submitting, not in response to a rejection.

### App access
- **All functionality available without special access.** Correct: the app needs
  the user's own provider keys, but it has no login of ours and nothing is gated
  behind an account. Review can open every screen. Give them nothing.
- **Do not put a real API key in a review note or a screenshot.**

### Content rating
- Category `Utility, Productivity, Communication, or Other`.
- **"Unrestricted internet browsing"** → No.
- Everything else No. Expected **Everyone**.

### Accessibility claim in the listing
The red/green invariant (owner is colourblind; semantic pair is violet `#8E7BFF`
and cyan `#35E0FF`, always with shape or label redundancy) is real and is stated in
the listing. Keep it true in the screenshots as well as the code.

## F-Droid
- ✅ Licence present. Declare **`NonFreeNet`** (the app's purpose is calling
  third-party provider APIs).
- The pure-JVM core builds on a bare JDK with no Android SDK, which makes the
  F-Droid build story unusually clean.

## Pre-submit checklist

- [ ] Resolve the foreground-service type: `dataSync` if it fits, otherwise
      `specialUse` with the subtype property and a written justification.
- [ ] Confirm `CATALOGUE_URL` points at something real, not the `OWNER-FILL`
      placeholder or the committed fixture.
- [ ] Screenshots: dashboard, Keys tab with **every key blurred**, the egress
      ledger, a paired-app row.
- [ ] Icon 512x512, feature graphic 1024x500.
