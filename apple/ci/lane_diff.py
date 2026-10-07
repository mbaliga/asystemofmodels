#!/usr/bin/env python3
"""Per-vector lane diff of the JVM runner's `lines` output against the Swift lane's (LAB_SPEC.md 3.3, PLATFORM_PLAN.md 6).

    lane_diff.py JVM.lines SWIFT.lines [--known FILE] [--not-implemented FILE]

Every input line is `<id> ok` or `<id> reject <CODE>`. The output names each vector whose verdicts differ, with both
verdicts. The exit status is non-zero when:
  * a vector disagrees and is not listed in --known (a new, unexplained disagreement);
  * a vector listed in --known now agrees (the list is stale, and stale lists hide regressions);
  * the JVM lane printed a vector the Swift lane did not, and it is not listed in --not-implemented;
  * the Swift lane printed a vector the JVM lane did not;
  * a vector listed in --not-implemented that the Swift lane printed, or that the JVM lane does not print at all (a stale or
    mistyped entry);
  * an entry of either list with no reason after the id, or listed twice.
The --known and --not-implemented files hold one `<id> <reason>` per line; `#` starts a comment.
"""
import sys


def read_lines(path):
    verdicts = {}
    for raw in open(path, encoding="utf-8"):
        raw = raw.rstrip("\n")
        if not raw:
            continue
        vid, _, verdict = raw.partition(" ")
        if vid in verdicts:
            sys.exit("%s: duplicate vector id %s" % (path, vid))
        verdicts[vid] = verdict
    return verdicts


def read_list(path, problems):
    ids = {}
    if path is None:
        return ids
    for raw in open(path, encoding="utf-8"):
        line = raw.split("#", 1)[0].strip()
        if line:
            vid, _, reason = line.partition(" ")
            if vid in ids:
                problems.append("%s: listed twice in %s" % (vid, path))
            if not reason.strip():
                problems.append("%s: no reason given in %s" % (vid, path))
            ids[vid] = reason.strip()
    return ids


def main(argv):
    args = argv[1:]
    if len(args) < 2:
        sys.exit(__doc__)
    jvm_path, swift_path = args[0], args[1]
    known_path = not_impl_path = None
    rest = args[2:]
    while rest:
        flag = rest.pop(0)
        if flag == "--known":
            known_path = rest.pop(0)
        elif flag == "--not-implemented":
            not_impl_path = rest.pop(0)
        else:
            sys.exit("unknown argument " + flag)
    jvm, swift = read_lines(jvm_path), read_lines(swift_path)
    problems = []
    known, not_impl = read_list(known_path, problems), read_list(not_impl_path, problems)

    agree = 0
    disagree = []
    for vid in sorted(jvm):
        if vid not in swift:
            if vid not in not_impl:
                problems.append("%s: printed by the JVM lane only and not listed as not implemented" % vid)
            continue
        if jvm[vid] == swift[vid]:
            agree += 1
            if vid in known:
                problems.append("%s: listed as a known disagreement but the lanes agree (stale list)" % vid)
        else:
            disagree.append(vid)
    for vid in sorted(swift):
        if vid not in jvm:
            problems.append("%s: printed by the Swift lane only" % vid)
    for vid in sorted(not_impl):
        if vid in swift:
            problems.append("%s: listed as not implemented but the Swift lane printed it" % vid)
        elif vid not in jvm:
            problems.append("%s: listed as not implemented but the JVM lane does not print it either (stale or mistyped entry)" % vid)
    for vid in sorted(known):
        if vid not in jvm or vid not in swift:
            problems.append("%s: listed as a known disagreement but a lane did not print it" % vid)

    print("%-9s %-34s %-34s %s" % ("vector", "jvm", "swift", "status"))
    for vid in disagree:
        status = "KNOWN " + known[vid] if vid in known else "UNEXPLAINED"
        print("%-9s %-34s %-34s %s" % (vid, jvm[vid], swift[vid], status))
        if vid not in known:
            problems.append("%s: disagreement not listed in the known list" % vid)
    only_jvm = sorted(v for v in jvm if v not in swift)
    print()
    print("vectors: jvm=%d swift=%d agree=%d disagree=%d (known %d) jvm-only=%d (not implemented %d)" % (
        len(jvm), len(swift), agree, len(disagree), sum(1 for v in disagree if v in known), len(only_jvm),
        sum(1 for v in only_jvm if v in not_impl)))
    for p in problems:
        print("PROBLEM: " + p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
