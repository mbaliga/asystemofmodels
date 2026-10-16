#!/usr/bin/env python3
"""Plain-text law for the QML of the click (finding HLU-1; ERRATA ERR-FX-UT-1). Standard library only.

Qt's default `textFormat` is `Text.AutoText`: when the first line of a string looks like HTML the item is rendered as StyledText, and
StyledText loads `<img src>` URLs from the UI process, over the network, with no ledger row. A model's answer, a peer's alias and the
node's row fields are text the app does not control, so no item may ever leave the default. The rule is therefore uniform and does not
try to judge which bindings are "safe":

  * every `Label`, `Text` and `TextEdit` object in qml/ sets `textFormat: Text.PlainText` itself (as its own property, not in a child);
  * `textFormat` appears nowhere else in qml/ (no assignment from JS, no other value);
  * `Text.AutoText`, `Text.RichText`, `Text.StyledText` and `Text.MarkdownText` appear nowhere in qml/.

Non-vacuity: the check fails when it finds no Label or Text object at all. `--selftest` runs the negative controls (each must be
reported) and the positive controls (each must pass) on synthetic sources. Exit 1 on any violation."""
import os
import re
import sys
import tempfile

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
QML = os.path.join(ROOT, "qml")
TEXT_TYPES = ("Label", "Text", "TextEdit")
OBJECT = re.compile(r"(?<![\w.])(?:[A-Za-z_]\w*\.)*(" + "|".join(TEXT_TYPES) + r")\s*\{")
FORBIDDEN_FORMAT = re.compile(r"\bText\.(AutoText|RichText|StyledText|MarkdownText)\b")
TEXTFORMAT = re.compile(r"\btextFormat\b")
OWN_PROPERTY = re.compile(r"(?:^|[;{\s])textFormat\s*:\s*([^\n;}]*)")


def mask(src):
    """Same length as `src`; comments and the inside of string literals become spaces (newlines are kept)."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if two == "//":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
        elif two == "/*":
            end = src.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in src[i:end]))
            i = end
        elif c in "\"'`":
            quote = c
            out.append(quote)
            i += 1
            while i < n and src[i] != quote:
                if src[i] == "\\" and i + 1 < n:
                    out.append("  ")
                    i += 2
                    continue
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            if i < n:
                out.append(quote)
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def own_body(masked, open_brace):
    """The text of the object that starts at `open_brace`, with every nested {...} removed; None when the braces never close."""
    depth = 0
    own = []
    for i in range(open_brace, len(masked)):
        c = masked[i]
        if c == "{":
            depth += 1
            if depth == 1:
                continue
        elif c == "}":
            depth -= 1
            if depth == 0:
                return "".join(own)
        if depth == 1:
            own.append(c)
    return None


def line_of(src, pos):
    return src.count("\n", 0, pos) + 1


def check_source(name, src):
    """Returns (violations, number of Label/Text objects seen)."""
    bad = []
    masked = mask(src)
    objects = 0
    for m in OBJECT.finditer(masked):
        objects += 1
        body = own_body(masked, m.end() - 1)
        line = line_of(src, m.start())
        if body is None:
            bad.append(f"{name}:{line}: {m.group(1)} block never closes")
            continue
        values = [v.strip() for v in OWN_PROPERTY.findall(body)]
        if values != ["Text.PlainText"]:
            seen = "no textFormat" if not values else "textFormat " + ", ".join(values)
            bad.append(f"{name}:{line}: {m.group(1)} must set textFormat: Text.PlainText itself ({seen})")
    for m in TEXTFORMAT.finditer(masked):
        rest = masked[m.start():].split("\n", 1)[0]
        if not re.match(r"textFormat\s*:\s*Text\.PlainText\s*;?\s*(\}.*)?$", rest):
            bad.append(f"{name}:{line_of(src, m.start())}: textFormat may only be declared as `textFormat: Text.PlainText`: {rest.strip()}")
    for m in FORBIDDEN_FORMAT.finditer(masked):
        bad.append(f"{name}:{line_of(src, m.start())}: Text.{m.group(1)} is forbidden: the UI shows only plain text")
    return bad, objects


def check_tree(qml_dir=QML):
    bad = []
    objects = 0
    files = 0
    for root, _, names in os.walk(qml_dir):
        for n in sorted(names):
            if n.endswith((".qml", ".js")):
                files += 1
                path = os.path.join(root, n)
                with open(path, encoding="utf-8") as f:
                    b, k = check_source(os.path.relpath(path, ROOT).replace(os.sep, "/"), f.read())
                bad += b
                objects += k
    if objects == 0:
        bad.append("no Label, Text or TextEdit object found in qml/: the check would be vacuous")
    return bad, objects, files


GOOD = "Label {\n    text: x\n    textFormat: Text.PlainText\n}\n"
SELFTEST = [
    # (name, source, exact number of violations expected)
    ("label without textFormat", "Label {\n    text: page.model.answer.text\n}\n", 1),
    ("text without textFormat", "Item { Text { text: a } }\n", 1),
    ("textedit without textFormat", "TextEdit { text: a }\n", 1),
    ("delegate label without textFormat", "Repeater { delegate: Label { text: modelData } }\n", 1),
    ("qualified label without textFormat", "Item { Controls.Label { text: a } }\n", 1),
    ("AutoText set explicitly", "Label { textFormat: Text.AutoText; text: a }\n", 3),
    ("RichText", "Label { textFormat: Text.RichText }\n", 3),
    ("StyledText", "Text { textFormat: Text.StyledText }\n", 3),
    ("MarkdownText", "Text { textFormat: Text.MarkdownText }\n", 3),
    ("PlainText only in a comment", "Label {\n    // textFormat: Text.PlainText\n    text: a\n}\n", 1),
    ("PlainText only in a string", "Label {\n    text: \"textFormat: Text.PlainText\"\n}\n", 1),
    ("PlainText only in a child object", "Label {\n    Item { textFormat: Text.PlainText }\n}\n", 1),
    ("textFormat assigned from JS", GOOD + "Item { Component.onCompleted: label.textFormat = Text.PlainText }\n", 1),
    ("textFormat bound to an expression", "Label { textFormat: cond ? Text.PlainText : Text.RichText }\n", 3),
    ("one good and one bad label", GOOD + "Label { text: b }\n", 1),
    ("block never closes", "Label { text: a\n", 1),
    ("one-line form", "Label { text: a }\n", 1),
]
SELFTEST_OK = [
    ("plain label", GOOD),
    ("one-line plain label", "Label { text: a; textFormat: Text.PlainText }\n"),
    ("plain text in a delegate", "Repeater { delegate: Label {\n  textFormat: Text.PlainText\n  text: modelData\n} }\n"),
    ("a property called text is not an object", "Item { property string Text: \"x\"; text: Text.WordWrap }\n"),
    ("braces and URLs inside strings do not confuse the scan", "Label {\n  text: \"} // { http://x\"\n  textFormat: Text.PlainText\n}\n"),
    ("wrapMode uses Text.WordWrap", "Label {\n  wrapMode: Text.WordWrap\n  textFormat: Text.PlainText\n}\n"),
]


def selftest():
    failures = []
    for name, src, want in SELFTEST:
        got, objects = check_source("selftest", src)
        if len(got) != want:
            failures.append(f"negative control '{name}': expected {want} violation(s), got {len(got)}: {got}")
        print(f"selftest: negative control '{name}': {len(got)} violation(s) {'(detected)' if got else '(MISSED)'}")
    for name, src in SELFTEST_OK:
        got, objects = check_source("selftest", src)
        if got:
            failures.append(f"positive control rejected: {name}: {got}")
        print(f"selftest: positive control '{name}': {len(got)} violation(s), {objects} object(s)")
    with tempfile.TemporaryDirectory() as d:
        b, objects, files = check_tree(d)
        if not b or objects != 0:
            failures.append("an empty tree must fail as vacuous")
        print(f"selftest: negative control 'empty tree is vacuous': {len(b)} violation(s)")
    return failures


def main():
    args = sys.argv[1:]
    if args == ["--selftest"]:
        failures = selftest()
        for f in failures:
            print("VIOLATION: " + f)
        print("check_qml_text selftest: " + ("FAILED" if failures else f"OK ({len(SELFTEST)} negative, {len(SELFTEST_OK) + 1} positive controls)"))
        return 1 if failures else 0
    if args:
        print("usage: check_qml_text.py [--selftest]", file=sys.stderr)
        return 2
    bad, objects, files = check_tree()
    print(f"check_qml_text: {objects} Label/Text/TextEdit objects in {files} QML/JS files")
    for b in bad:
        print("VIOLATION: " + b)
    print("check_qml_text: " + ("FAILED" if bad else "OK"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
