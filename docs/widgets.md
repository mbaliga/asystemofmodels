# Home-screen widgets

asom ships six standard Android App Widgets (`AppWidgetProvider` + `RemoteViews`), so any launcher can
host them: Pixel Launcher, One UI, Nova, Orrery Home. Each one is resizable and has four layouts (a
one-line tiny layout, small, medium, large) drawn from asom's real data: the egress ledger, the daemon's live state, the pairing
store and the model-download store.

**Status: built and JVM-tested; nothing here has run on a device.** Every rendering, resize, tap and
refresh behaviour is `NEEDS-DEVICE-VALIDATION` (see the owner-verify list at the end; brief §12).

Scope: the owner lifted the "`app/` and the other Android modules stay untouched until v1 device
validation" rule **for widgets only** (2026-11-04). Outside `app/` the only change is two read-only
queries on `LedgerDao` in `:ledger` (`since`, `recentRoutes`; no schema change, no migration). Nothing
in `:core:*` or `:server` changed, so the pure-JVM build and `./gradlew jvmTest` are untouched.

## How the invariants hold on the home screen

| Invariant | What the widgets do |
|---|---|
| §1.1 / §1.3 no automatic egress | No network in any widget, no new permission, no new dependency (no Glance, Compose or WorkManager in the widget path), no telemetry. Widgets read the same local Room tables and in-memory state the dashboard reads. Nothing is cached on disk, so there is no widget snapshot to leak into a backup (`allowBackup` is already false). |
| §1.4 keys never shown | The widget code never touches the vault, the dev bearer token or pairing token hashes. The pairing data is reduced to `(status, approvedAt)` before it reaches the widget layer (`PairingRow`). |
| §1.6 red/green carry no meaning | Only violet `#8E7BFF` and cyan `#35E0FF` carry meaning, always with a glyph and a word (`●`/`○`/`◐`/`◑`/`✕`/`▲`/`■`/`⚠`/`⦿` plus text). `WidgetInvariantsTest` fails if any coloured line lacks a glyph or words; `WidgetResourcesTest` checks every widget colour is a Hyle token, never a banned red/green hue, and reads at 4.5:1 or better on the widget surface. |
| §1.7 placeholder-functional | Four plain templates and one one-line layout against Hyle tokens; no visual design. Surface and ink are the dashboard's own `HyleSeam` roles (`Surface`, `OnSurface`); the dim ink (Hyle `material.stone`) and the hairline (Hyle `border.strong`) have no seam role and are pinned to their Hyle tokens. `WidgetResourcesTest` checks all of them. |
| §1.9 one `RouteRecord` | The route widgets turn each ledger row back into a `RouteRecord` (`toRecord()`) and print served-by, egress and cost from `RouteRecord.toEchoHeaders()`, the same function that wrote the HTTP headers. The widget cannot disagree with the API or the Ledger tab. |
| v1 has no local engine | A `local` ledger row means nothing was served and nothing left the device. Widgets say exactly that ("■ local — nothing served, nothing left the device") and never "on-device" or "native" (`WidgetInvariantsTest` scans every face built from the real strings). |

Dark only: Hyle is a dark-only system, and the §1.6 pair fails text contrast on a light ground (cyan on
white is about 1.6:1), so `values/colors.xml` and `values-night/colors.xml` are identical by design.
Secondary text uses Hyle `material.stone` (#9B958C) because Hyle's `text.secondary` (42% ink) is below
4.5:1 at 12sp.

## The six widgets

Sizes: **T** is one short row (a 1-row landscape cell), **S** about 2x1 to 4x1 in portrait, **M** a 2- or
3-row landscape cell or the Orrery hub's short preset, **L** about 3x2 and up in portrait (exact
breakpoints below). **T** is one line for every widget: S's value (or headline, or newest row) and its
stamp, with the title in whatever width is left.

| Widget | Template | S | M adds | L adds | Tap |
|---|---|---|---|---|---|
| **Left the device today** | stat | `▲ 12 rows` and today's date (the date is the stamp at every size); `⚠ 12 rows (incomplete)` when a ledger write failed | estimated cost: `≈ 0.0042 USD estimated` when every cloud row is priced, `≈ 0.0004 USD estimated (1 of 10 cloud rows priced)` when only some are, "No cost basis — not estimated" when none are; breakdown `▲ cloud 10 · ■ download 2`; the incomplete-ledger warning leads when it applies | as room allows: local rows ("■ 1 local row — nothing served, nothing left"); bytes sent and tokens; footer "Counted from asom's ledger; resets at midnight" | Ledger tab |
| **asom daemon** | stat + one pad | `● running` / `○ stopped` / `✕ didn't start` and the time it was drawn | "as of" time; the verbose-ledger notice and the ledger-incomplete warning first when they apply, then `serving on 127.0.0.1:11435` (or "not listening · port 11435") and the activity (idle / serving a request… / streaming via *provider*…); **Start** or **Stop** button | the rest of those lines; footer "v1 has no local engine — every served route is cloud" | Status tab; the button starts or stops the daemon |
| **Last route** | card | `▲ openrouter/llama-3.3-70b` (or `▲ groq/… · HTTP 429`, `■ local · not served · HTTP 501`) and its time | `▲ cloud` or `■ local — …` headline; served by / sent to / not served; `HTTP 200 · 14:05` | cost from the echo header with its basis; tokens, latency, bytes sent; for local rows, what was asked for; footer "Metadata only — no prompts, replies or keys" | Ledger tab |
| **Recent routes** | list | the newest route and its time | three routes, each with its time; as-of time | five routes; same footer | Ledger tab |
| **AI hotspot** | stat | `● 3 apps paired`, or `◐ 1 waiting` when a request waits | as-of time; "◐ 1 app waiting for your approval"; "✕ 1 revoked" | last approval time; footer "App names stay inside asom" | the consent sheet when a request waits, otherwise the Hotspot tab |
| **Model downloads** | progress | `◐ 42%` with the model id, a bar and the time it was drawn (`◑ verifying`, or an indeterminate bar when the total is unknown); or `● 2 models stored` | as-of time; `16.8 GB of 40.0 GB` | "+1 more downloading", models stored, failed count, model storage on disk | Models tab |

Data sources (all through `ServiceLocator`, read off the main thread in `WidgetData`):

| Widget | Reads |
|---|---|
| Left the device today | `LedgerDao.since(localMidnight)` → `RouteLogEntity.toRecord()` → `EgressDay`; `ServiceLocator.ledgerDegraded` |
| asom daemon | `AsomService.running`, `.activity`, `.startError`; `ServiceLocator.verboseMode`, `.ledgerDegraded`; `Asom.BIND_HOST` / `DEFAULT_PORT` |
| Last route / Recent routes | `LedgerDao.recentRoutes(1)` / `recentRoutes(5)` (egress `cloud` or `local` only) → `RouteView` |
| AI hotspot | `PairingDao.all()` reduced to `PairingRow(status, approvedAt)` → `HotspotCounts` |
| Model downloads | `ModelDownloadManager.observeAll().first()` (rows without their error text), the catalogue's downloadable models, `totalBytesOnDisk()` → `DownloadsSummary` |

"Left the device" counts the ledger rows whose egress is `cloud`, `catalogue` or `download`, the same
field the Ledger tab shows. Most are one network event each (§1.3): every provider attempt (a failover
attempt is its own `cloud` row, because the body reached that provider), every catalogue fetch, every
download. One kind of row is not: when every candidate failed, the request's terminal row is marked
`cloud` (the body did leave) but names no provider and carries no bytes, since the bytes are on the
attempt rows. A request that tried two providers and failed is therefore three rows here, as it is in
the ledger. Cost sums only rows with an estimate **and** a basis, exactly the rows whose echo headers
carried `X-Asom-Cost-Est` (§5.4); with none it says so instead of printing zero, and when only some cloud
rows have one, the coverage is part of the cost sentence, so no layout can show a partial sum as the
day's cost. Only completed cloud rows carry a cost; attempt and terminal rows never do.

## Sizes

`SizeClass` (pure, `widget/model/WidgetModel.kt`) holds one set of dp breakpoints used two ways. Each
height is where that size's layout still fits at a 1.3x font scale with one line per text slot;
`WidgetResourcesTest` adds up every layout's slot heights (a text line counted as 1.2x its sp size) and
fails if one does not fit its breakpoint. On Android's cell table a 1-row cell is about 102dp tall in
portrait and 51dp in landscape, a 2-row cell about 220dp and 117dp.

| Class | Breakpoint (w x h) | Typical cells |
|---|---|---|
| TINY | 110 x 40 dp | 1-row landscape (~51dp); anything narrower than 110dp |
| SMALL | 110 x 72 dp | 2x1 to 4x1 portrait (~102dp); 2x2 portrait (narrow); 2-row landscape (~117dp) |
| MEDIUM | 180 x 120 dp | 3-row landscape (~184dp); Orrery hub "Small" (120dp) |
| LARGE | 180 x 200 dp | 3x2, 4x2 and up portrait (~220dp); Orrery hub "Medium" and "Large" |

- API 31+: `RemoteViews(Map<SizeF, RemoteViews>)` with those four keys; the launcher picks the layout
  on every resize without waking asom.
- API 29-30: the provider reads the four size options and sends a portrait layout (narrowest width,
  tallest height) and a landscape one (widest width, shortest height) as one
  `RemoteViews(landscape, portrait)`, so a rotating launcher shows the right one without a redraw; it
  redraws in `onAppWidgetOptionsChanged`. A host that has not reported a size gets the widget's default
  (`WidgetKind.defaultSize`: what its default cells measure in portrait).
- Each provider's legacy `minWidth`/`minHeight` (70n − 30 dp) and its `targetCellWidth`/`Height` name the
  same default cells (2x1 for the hotspot, 4x2 for recent routes, 3x2 for the rest); a test keeps them in
  step.
- Compact sizes (TINY, SMALL) have no room for a 48dp control, so the daemon widget has no button there;
  the whole widget is one tap. Every text slot is one line (two in large layouts) and ellipsizes. Above
  1.3x, the lowest lines of a layout are cut first, which is why each widget orders its lines by
  importance (warnings before routine lines).

Provider XML: `updatePeriodMillis="0"`, `resizeMode="horizontal|vertical"`, `widgetCategory="home_screen"`,
`minResizeWidth/Height` 110 x 40 dp (the TINY layout), `targetCellWidth/Height`, `previewLayout` (API 31+,
sample content) and `previewImage` (vector, API 29-30), `initialLayout` (a loading layout), `description`.

## When widgets redraw

Pushed, not polled (`WidgetRefresh`):

- Every write the widgets show happens in asom's one process (the ledger sink, the pairing service, the
  download worker, the daemon service). While any widget is placed, asom observes the same flows the
  dashboard observes: `LedgerDao.count()`, `PairingDao.observeAll()`, `ModelDownloadManager.observeAll()`,
  the daemon's state flows and `ledgerDegraded`. A change redraws only the widgets that show it
  (`Source.kinds`). Bursts (a streaming request, download progress every second) are conflated to at
  most one redraw per source every 2 s, and the last state is always drawn. An error in an observed flow
  (a Room re-query hitting `SQLITE_BUSY`, say) re-subscribes after 2, 4, 8, 16, 32 s, then every minute
  (`Backoff`), rather than ending that observer for the life of the process.
- The observers start from `Application.onCreate` when a widget is placed, and from any provider
  `onUpdate`. Their first emission redraws, so a widget left stale by a dead process is corrected the
  next time asom's process starts.
- Midnight: one inexact, non-waking `AlarmManager.setWindow(RTC, midnight + 1 s, 10 min, …)` to the
  non-exported `WidgetTickReceiver` redraws every widget (no exact-alarm permission; unlike `set`, the
  window keeps API 29-30 from deferring it by hours). Doze or an OEM battery manager may still hold it
  while the phone sleeps; the egress widget prints its date at every size, so a late redraw can't pass
  yesterday's total off as today's. Every render re-arms it.
- `TIME_SET`, `TIMEZONE_CHANGED` and `LOCALE_CHANGED` (all delivered to manifest receivers) redraw times
  and words through each provider's own `onReceive`.
- Medium and large layouts print an "as of" time (the Last route card prints the route's own time
  instead, the egress widget its date), so a missed redraw is visible. Where data lives only in memory or
  can freeze when asom's process dies (the daemon's state, a running download), compact layouts print
  the time too.
- Each broadcast (`onUpdate`, options changes, clock broadcasts, the midnight tick) is finished at 8 s
  even if a blocking read inside it has not returned (`finishWithin`: the receiver waits on a cancellable
  `join()`, not on the read); the redraw still lands when the read completes.

Known limit: the daemon's state lives in memory in asom's process. If the system kills that process while
the daemon runs, there is no process left to redraw, and the daemon widget keeps "● running" until
asom's process starts again (it is then redrawn as stopped). Every size prints the time it was drawn
(the time alone at the compact sizes, "as of" with it above), so the old drawing shows its age. A
running download frozen the same way shows its time too.

## Taps

All taps are explicit, immutable `PendingIntent`s to asom's own components, with request codes unique
per widget id and target (`RequestCodes`). No URL schemes.

- Open: `MainActivity` with `xyz.mdhv.asom.widget.extra.DESTINATION` = `status` / `hotspot` / `models` /
  `ledger`. MainActivity checks it against the closed `Destination` set and selects that tab (also on
  `onNewIntent`); anything else is ignored. The Keys tab is deliberately not a destination.
- Consent: a waiting pairing request opens `PairingActivity`, which shows only the binder-verified
  pending identity (§5.7) and reads nothing from the intent. The widget never approves anything.
- Start/Stop: `WidgetActionActivity` (not exported, translucent, finishes at once) checks the action
  against `DaemonAction` and calls `AsomService.start`/`stop`. It is an activity because Android 12+
  refuses foreground-service starts from the background. A tap is the user starting the daemon, the
  same act as the dashboard's Start button or the quick-settings tile; boot-start stays default OFF.

## States

| State | What shows |
|---|---|
| first run / loading | `initialLayout`: "asom — loading…" until the first render |
| empty | "Nothing has left the device today" (`■ 0 rows`); "No routes yet" + "Start the daemon and send a request."; "Apps join via the AI-hotspot pairing flow."; "Nothing downloaded yet"; "No downloadable models in the catalogue" |
| error | "✕ Couldn't read asom's data — open asom to check", in the widget's own template, keeping its title and tap |
| stale | "as of" time on medium and large; the daemon's time, and a running download's, at every size; the egress widget's date at every size; route rows carry their own times |
| withheld | caller packages never shown on route widgets; paired-app names, packages and certificate hashes never reach the hotspot widget (counts only, footer "App names stay inside asom"); download error text dropped |
| incomplete ledger | "⚠ A ledger row could not be written — the ledger is incomplete" leads the egress widget's lines and the daemon widget's lines from medium up; a compact egress widget marks the value itself ("⚠ 12 rows (incomplete)") |
| partial cost | "≈ 0.0004 USD estimated (1 of 10 cloud rows priced)": the coverage is inside the sentence, never a separate line a smaller layout could drop |

TalkBack: the whole-widget target carries a words-only summary (glyphs stripped) ending in where the tap
goes; the Start/Stop button has its own description ("Start the asom daemon").

## Code

| Path | What |
|---|---|
| `app/src/main/kotlin/xyz/mdhv/asom/widget/model/` | Pure Kotlin, no `android.*`: size classes, faces (`StatFace`, `CardFace`, `ListFace`, `ProgressFace`), the copy seam (`WidgetCopy`, `Txt`, `Cnt`), formatting, and one presenter per widget |
| `app/src/main/kotlin/xyz/mdhv/asom/widget/` | Android: providers, `WidgetRenderer` (RemoteViews), `WidgetData` (reads), `WidgetRefresh` (observers, alarm), `WidgetLinks` (taps), `ResourceCopy`, `WidgetActionActivity` |
| `app/src/main/res/layout/widget_{stat,card,list,progress}_{small,medium,large}.xml` | The four templates at three sizes; `widget_tiny.xml` (the shared one-line layout); `widget_loading.xml`; `widget_preview_*.xml` |
| `app/src/main/res/xml/widget_info_*.xml`, `res/drawable/widget_*` | Provider declarations, backgrounds, picker previews |
| `app/src/main/res/values*/` | `widget_*` strings and plurals, colours (Hyle tokens), dimens (launcher radius on API 31+), styles |
| `ledger/.../LedgerDb.kt` | `LedgerDao.since(fromTs)` and `LedgerDao.recentRoutes(limit)`, read-only |

Tests (`app/src/test/kotlin/xyz/mdhv/asom/widget/`, run by `:app:testDebugUnitTest`): `WidgetModelTest`,
`WidgetFormatTest`, `EgressTodayTest`, `DaemonWidgetTest`, `RoutesTest`, `HotspotWidgetTest`,
`DownloadsWidgetTest`, `WidgetInvariantsTest` (every presenter over a state x size matrix with the real
`strings.xml`; every sentence and plural exercised; the one-line summary included), `WidgetResourcesTest`
(layout slots per size, every layout's height at 1.3x against its breakpoint, RemoteViews-safe views,
text sizes, provider XML with legacy and target cells in step, manifest export flags, colours against the
`HyleSeam` roles and Hyle tokens, contrast).
The two new DAO queries are checked by Room's compiler at build time; they are not executed in a JVM
test (this repo has no Robolectric).

## Owner-verify list (all `NEEDS-DEVICE-VALIDATION`)

- [ ] All six widgets appear in the picker on the owner's phone (RedMagic) with their previews, and in Orrery Home / BOS_launcher
- [ ] Each widget resizes through tiny (1-row landscape), small, medium and large on the stock launcher and on Orrery Home (equator tiles and hub presets); the layout switches at the expected sizes and nothing is cut off at 1.3x; at 2x the lowest lines go first and the value stays readable
- [ ] Left the device today: send a routed request → the count and cost change within a few seconds; matches the Ledger tab's rows for today; at midnight it resets (and the date in the corner changes)
- [ ] Daemon: Start and Stop from the widget work on Android 12+ (the translucent activity does not flash visibly or end up in Recents); the widget, the QS tile and the Status tab agree; streaming shows "streaming via *provider*…" and returns to idle
- [ ] Last route / Recent routes: served-by, egress and cost match the response's `X-Asom-*` headers for the same request; a `local-only` request shows "■ local — nothing served, nothing left the device"
- [ ] AI hotspot: a pending request (pair from `:sample-client`, then "Decide later") shows "◐ 1 waiting"; tapping opens the consent sheet; no app name appears anywhere on the widget
- [ ] Model downloads: progress advances during a download, "◑ verifying" at the end, then "● 1 model stored"
- [ ] Taps land on the right tab, including when the dashboard is already open on another tab
- [ ] TalkBack reads the summaries and the Start/Stop button's description
- [ ] After an OEM battery manager or a force-stop, the widget shows the time it was last drawn (also on a 2x1 daemon widget) and corrects itself when asom next runs
- [ ] The midnight redraw arrives within about 10 minutes of midnight while the phone is awake
- [ ] API 29-30 path (if a device is available): layout chosen from the reported size; redraw on resize; on a launcher that rotates, the landscape layout appears without a redraw
