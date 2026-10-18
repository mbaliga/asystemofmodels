# images/

Sizes and check commands: `Personal-Tracker/store/ASSET_SPECS.md`.

## Needed, none present yet

- `icon.png` 512x512 no alpha · `featureGraphic.png` 1024x500 no alpha
- `phoneScreenshots/` — 2 to 8, 1080x1920, no alpha.

## Shoot these

1. The dashboard.
2. The **egress ledger**. This is the product's actual argument; give it a slot.
3. A paired-app row, showing verified caller identity.
4. The Keys tab.

**Blur every API key.** A key visible in a store screenshot is public forever, and
this is the one app in the house whose screenshots are guaranteed to contain one.
Check the Keys tab frame twice, including any partially-masked prefix.

Colour must not carry meaning on its own in any frame: the semantic pair is violet
and cyan, always with a shape or label alongside.

```sh
adb exec-out screencap -p > shot.png
magick shot.png -background black -alpha remove -alpha off phoneScreenshots/01.png
```
