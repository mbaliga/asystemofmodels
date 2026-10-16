#!/usr/bin/env python3
"""Prints the JVM flags of runtime/jvm.options, one per line, with ${NAME} expanded from the environment exactly as the plugin does
(NodeProcess::expandOptions: '#' comments, no shell interpretation, an unset XDG_CACHE_HOME falls back to $HOME/.cache). Used by the
arm64 smoke and the AppArmor approximation, which start the JVM without the plugin. Standard library only."""
import os
import re
import sys


def expand(text, env):
    out = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue

        def sub(m):
            name = m.group(1)
            value = env.get(name, "")
            if not value and name == "XDG_CACHE_HOME" and env.get("HOME"):
                value = env["HOME"] + "/.cache"
            return value

        out.append(re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}", sub, line))
    return out


if __name__ == "__main__":
    path = sys.argv[1]
    for flag in expand(open(path, encoding="utf-8").read(), dict(os.environ)):
        print(flag)
