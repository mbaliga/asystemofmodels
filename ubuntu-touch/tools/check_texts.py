#!/usr/bin/env python3
"""The UI's error words (qml/js/ErrorText.js) must equal the node's (ErrorMapping.kt UiErrorCode), code for code and word for word,
so the owner reads on screen exactly what the node says. Standard library only. Exit 1 on any difference."""
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
KT = os.path.join(ROOT, "jvm", "ut-host", "src", "main", "kotlin", "xyz", "mdhv", "asom", "ut", "ErrorMapping.kt")
JS = os.path.join(ROOT, "qml", "js", "ErrorText.js")


def main():
    kt = open(KT, encoding="utf-8").read()
    enum_body = kt[kt.index("enum class UiErrorCode"):kt.index("companion object")]
    kotlin = dict(re.findall(r"^\s*([A-Z_]+)\(\"([^\"]*)\"\)[,;]?\s*$", enum_body, re.M))
    js_text = open(JS, encoding="utf-8").read()
    block = js_text[js_text.index("var MESSAGES"):js_text.index("var FALLBACK")]
    js = dict(re.findall(r"^\s*\"([A-Z_]+)\":\s*\"([^\"]*)\",?\s*$", block, re.M))
    bad = []
    if len(kotlin) != 10:
        bad.append(f"expected 10 UiErrorCode values in ErrorMapping.kt, parsed {len(kotlin)}")
    for code in sorted(set(kotlin) | set(js)):
        if code not in js:
            bad.append(f"{code}: in the node, missing from ErrorText.js")
        elif code not in kotlin:
            bad.append(f"{code}: in ErrorText.js, not a node code")
        elif kotlin[code] != js[code]:
            bad.append(f"{code}: node says '{kotlin[code]}', the UI says '{js[code]}'")
    print(f"check_texts: {len(kotlin)} node codes, {len(js)} UI texts")
    for b in bad:
        print("VIOLATION: " + b)
    print("check_texts: " + ("FAILED" if bad else "OK"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
