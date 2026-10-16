#!/usr/bin/env python3
"""Checks a click's AppArmor manifest (the built `asom.apparmor`, or the template `asom.apparmor.in`) against the pinned policy
lists (policy-groups.json, from click-reviewers-tools 9f5abad):

  * the file is valid JSON with no unresolved @PLACEHOLDER@ (Clickable replaces them in the install dir);
  * only the keys template, policy_groups and policy_version (no read_path, write_path or abstractions: nothing that widens the
    profile beyond a policy group);
  * the template is `ubuntu-sdk`: never `unconfined`, never a reserved template;
  * every policy group is a COMMON group (automated store review) of the file's policy version; none is reserved, unknown or repeated;
  * the group set is EXACTLY the expected four (networking, keep-display-on, camera, content_exchange_source): a fifth group, even a
    common one, is a change that needs the design's sign-off;
  * the policy version equals --policy (default 2404.1, the one ubuntu-touch-24.04-1.x maps to).

Usage: check-groups.py <file> [--policy 2404.1] [--substitute]   (--substitute fills @APPARMOR_POLICY@ so the .in file can be checked)
       check-groups.py --selftest                                 (negative controls: each broken variant must fail)
Exit 0 when the file passes. Standard library only."""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ALLOWED_KEYS = {"template", "policy_groups", "policy_version"}
REQUIRED_KEYS = {"policy_groups", "policy_version"}  # click-review warns on an explicit "ubuntu-sdk" template: absent means that default


def load_lists():
    with open(os.path.join(HERE, "policy-groups.json"), encoding="utf-8") as f:
        return json.load(f)


def check_text(text, lists, policy="2404.1", substitute=False):
    """Returns a list of problems; empty means the manifest passes."""
    problems = []
    if substitute:
        text = text.replace("@APPARMOR_POLICY@", policy)
    left = re.findall(r"@[A-Z_]+@", text)
    if left:
        return [f"unresolved placeholder(s) {sorted(set(left))}: check the file Clickable built, or pass --substitute"]
    try:
        doc = json.loads(text)
    except ValueError as e:
        return [f"not valid JSON: {e}"]
    if not isinstance(doc, dict):
        return ["the manifest is not a JSON object"]
    extra = set(doc) - ALLOWED_KEYS
    if extra:
        problems.append(f"keys {sorted(extra)} are not allowed (only {sorted(ALLOWED_KEYS)}): they widen the profile beyond a policy group")
    missing = REQUIRED_KEYS - set(doc)
    if missing:
        problems.append(f"missing keys {sorted(missing)}")
    version = str(doc.get("policy_version"))
    if str(policy) != version:
        problems.append(f"policy_version {doc.get('policy_version')!r} is not {policy}")
    known = lists["policies"].get(version)
    if known is None:
        problems.append(f"policy version {version} is not in the pinned lists")
        return problems
    template = doc.get("template", lists["expected"]["template"])
    if template == "unconfined" or template in known["templates"]["reserved"]:
        problems.append(f"template {template!r} is reserved (manual review, red-flagged): never unconfined")
    elif template != lists["expected"]["template"]:
        problems.append(f"template is {template!r}, must be {lists['expected']['template']!r}")
    groups = doc.get("policy_groups")
    if not isinstance(groups, list) or not all(isinstance(g, str) for g in groups):
        problems.append("policy_groups is not a list of strings")
        return problems
    if len(groups) != len(set(groups)):
        problems.append(f"a policy group is repeated: {groups}")
    for g in groups:
        if g in known["policy_groups"]["reserved"]:
            problems.append(f"group {g!r} is RESERVED (manual review, not automated)")
        elif g not in known["policy_groups"]["common"]:
            problems.append(f"group {g!r} is not a known common group of policy {version}")
    if sorted(set(groups)) != sorted(lists["expected"]["policy_groups"]):
        problems.append(f"the group set {sorted(set(groups))} is not exactly the expected {lists['expected']['policy_groups']}")
    return problems


GOOD = {"template": "ubuntu-sdk", "policy_groups": ["networking", "keep-display-on", "camera", "content_exchange_source"], "policy_version": 2404.1}


def selftest():
    lists = load_lists()
    cases = [
        ("the expected manifest", GOOD, True),
        ("groups in another order", dict(GOOD, policy_groups=["camera", "networking", "content_exchange_source", "keep-display-on"]), True),
        ("no template key (the click-review default, ubuntu-sdk)", {k: v for k, v in GOOD.items() if k != "template"}, True),
        ("template unconfined", dict(GOOD, template="unconfined"), False),
        ("template default", dict(GOOD, template="default"), False),
        ("a reserved group (bluetooth)", dict(GOOD, policy_groups=GOOD["policy_groups"] + ["bluetooth"]), False),
        ("a reserved group in place of a common one", dict(GOOD, policy_groups=["networking", "keep-display-on", "camera", "history"]), False),
        ("an unknown group", dict(GOOD, policy_groups=GOOD["policy_groups"] + ["root_access"]), False),
        ("a fifth COMMON group (location)", dict(GOOD, policy_groups=GOOD["policy_groups"] + ["location"]), False),
        ("a missing group", dict(GOOD, policy_groups=["networking", "keep-display-on", "camera"]), False),
        ("a repeated group", dict(GOOD, policy_groups=GOOD["policy_groups"] + ["camera"]), False),
        ("an extra key read_path", dict(GOOD, read_path=["/home"]), False),
        ("an extra key abstractions", dict(GOOD, abstractions=["base"]), False),
        ("policy 2404.2 where 2404.1 is required", dict(GOOD, policy_version=2404.2), False),
        ("an unknown policy version", dict(GOOD, policy_version=9999), False),
        ("no policy_version", {k: v for k, v in GOOD.items() if k != "policy_version"}, False),
        ("policy_groups not a list", dict(GOOD, policy_groups="networking"), False),
    ]
    wrong = 0
    for name, doc, want_ok in cases:
        problems = check_text(json.dumps(doc), lists)
        ok = not problems
        status = "ok" if ok == want_ok else "WRONG"
        if ok != want_ok:
            wrong += 1
        print(f"  selftest {name}: expected {'pass' if want_ok else 'fail'}, got {'pass' if ok else 'fail'} -> {status}")
    unresolved = check_text('{"template": "ubuntu-sdk", "policy_groups": [], "policy_version": @APPARMOR_POLICY@}', lists)
    print(f"  selftest an unresolved placeholder: expected fail, got {'fail' if unresolved else 'pass'} -> {'ok' if unresolved else 'WRONG'}")
    wrong += 0 if unresolved else 1
    garbage = check_text("not json", lists)
    print(f"  selftest not JSON: expected fail, got {'fail' if garbage else 'pass'} -> {'ok' if garbage else 'WRONG'}")
    wrong += 0 if garbage else 1
    print(f"selftest {'FAILED' if wrong else 'OK'}: {len(cases) + 2} cases, {wrong} wrong")
    return 1 if wrong else 0


def main(argv):
    if "--selftest" in argv:
        return selftest()
    args = [a for a in argv[1:] if not a.startswith("--")]
    policy = "2404.1"
    if "--policy" in argv:
        policy = argv[argv.index("--policy") + 1]
        args = [a for a in args if a != policy]
    if len(args) != 1:
        print(__doc__)
        return 2
    text = open(args[0], encoding="utf-8").read()
    problems = check_text(text, load_lists(), policy=policy, substitute="--substitute" in argv)
    if problems:
        for p in problems:
            print(f"check-groups: {args[0]}: {p}")
        print("check-groups: FAILED")
        return 1
    print(f"check-groups: {args[0]}: template ubuntu-sdk (explicit or default), policy {policy}, groups {sorted(json.loads(text.replace('@APPARMOR_POLICY@', policy))['policy_groups'])}, all common, none reserved: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
