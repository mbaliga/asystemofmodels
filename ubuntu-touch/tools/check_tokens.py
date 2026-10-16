#!/usr/bin/env python3
"""Invariants 6 and 7 at the token seam (static). Standard library only.

  * qml/tokens/Tokens.qml defines the semantic pair exactly: violet #8E7BFF and cyan #35E0FF;
  * every colour token is either the pair or a low-saturation neutral: none has a red or green hue at saturation > 0.25;
  * no colour literal (#rgb, #rrggbb, #aarrggbb, Qt.rgba, Qt.hsla, a CSS colour name) appears in any other QML or JS file, so a
    page can only name a token;
  * the shape tokens are pairwise different (colour is never the only signal).
Prints one line per check; exits non-zero on a violation."""
import colorsys
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
QML = os.path.join(ROOT, "qml")
TOKENS = os.path.join(QML, "tokens", "Tokens.qml")
NAMED = {"red", "green", "blue", "yellow", "orange", "purple", "pink", "lime", "olive", "teal", "cyan", "magenta", "maroon", "navy",
         "gold", "coral", "crimson", "tomato", "salmon", "khaki", "brown", "aqua", "fuchsia", "silver", "gray", "grey", "white", "black",
         "darkred", "darkgreen", "lightgreen", "lightblue", "violet", "indigo"}


def hex_to_rgb(h):
    h = h.lstrip("#")
    if len(h) == 8:
        h = h[2:]
    return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))


def main():
    bad = []
    text = open(TOKENS, encoding="utf-8").read()
    colours = dict(re.findall(r"readonly property color (\w+): \"(#[0-9A-Fa-f]{6,8})\"", text))
    if colours.get("violet", "").lower() != "#8e7bff":
        bad.append(f"Tokens.violet is {colours.get('violet')}, must be #8E7BFF")
    if colours.get("cyan", "").lower() != "#35e0ff":
        bad.append(f"Tokens.cyan is {colours.get('cyan')}, must be #35E0FF")
    print(f"tokens: {len(colours)} colour tokens ({', '.join(sorted(colours))}); semantic pair checked")
    for name, value in colours.items():
        r, g, b = hex_to_rgb(value)
        h, s, v = colorsys.rgb_to_hsv(r, g, b)
        hue = h * 360
        if s > 0.25 and (hue < 30 or hue >= 330 or 75 <= hue < 165):
            bad.append(f"Tokens.{name} {value} is a saturated red or green (hue {hue:.0f}, saturation {s:.2f})")
    print("tokens: no colour token has a red or green hue at saturation > 0.25")
    # the check must have bite: prove the predicate flags a red and a green
    for name, value in (("red", "#E02020"), ("green", "#20C040")):
        r, g, b = hex_to_rgb(value)
        h, s, v = colorsys.rgb_to_hsv(r, g, b)
        hue = h * 360
        assert s > 0.25 and (hue < 30 or hue >= 330 or 75 <= hue < 165), f"the red/green predicate missed {name}"
    shapes = dict(re.findall(r"readonly property string (shape\w+): \"([\w-]+)\"", text))
    distinct = {k: v for k, v in shapes.items() if k != "shapeNone"}
    if len(set(distinct.values())) != len(distinct):
        bad.append(f"two meanings share a shape: {distinct}")
    print(f"tokens: {len(distinct)} meanings, {len(set(distinct.values()))} distinct shapes")

    scanned = 0
    for base, dirs, names in os.walk(QML):
        for n in names:
            path = os.path.join(base, n)
            if path == TOKENS or not n.endswith((".qml", ".js")):
                continue
            scanned += 1
            for i, line in enumerate(open(path, encoding="utf-8").read().splitlines(), 1):
                code = re.sub(r"//.*$", "", line)
                if re.search(r"[\"']#[0-9A-Fa-f]{3,8}[\"']", code):
                    bad.append(f"{os.path.relpath(path, ROOT)}:{i}: a colour literal outside Tokens.qml")
                if re.search(r"Qt\.(rgba|hsla|hsva|lighter|darker|tint)\(", code):
                    bad.append(f"{os.path.relpath(path, ROOT)}:{i}: colour arithmetic outside Tokens.qml")
                for m in re.finditer(r"\bcolor:\s*\"([A-Za-z]+)\"", code):
                    if m.group(1).lower() in NAMED:
                        bad.append(f"{os.path.relpath(path, ROOT)}:{i}: named colour '{m.group(1)}' outside Tokens.qml")
    if scanned < 8:
        bad.append(f"only {scanned} QML/JS files scanned: the literal check would be vacuous")
    print(f"tokens: {scanned} QML/JS files scanned for colour literals outside Tokens.qml")
    for b in bad:
        print("VIOLATION: " + b)
    print("check_tokens: " + ("FAILED" if bad else "OK"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
