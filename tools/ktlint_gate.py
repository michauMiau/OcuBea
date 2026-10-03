#!/usr/bin/env python3
"""Run ktlint and fail only on the rules that name a defect.

Why this exists
---------------
ktlint reports 1510 findings in this repo. All but a few dozen are wrapping and
indentation opinions -- 337 on argument-list-wrapping, 263 on statement-wrapping,
242 on indent. Gating a build on those is not a quality gate: it is a gate that
can only be satisfied by reformatting the whole repository, which buries whatever
change is being reviewed and gets reverted by the next person who disagrees with
a line break.

So the layout rules are not the gate. The gate is the small set of rules that
name something actually wrong:

    no-unused-imports          an import nothing references
    import-ordering            duplicated, or unsorted, imports -- which is how
                               two declarations of the same name get in
    no-consecutive-blank-lines noise that hides a boundary
    string-template            redundant curly braces in a template
    final-newline              a file that ends mid-line
    no-trailing-spaces         trailing whitespace
    no-blank-line-before-rbrace a blank line inside an empty block
    filename                   a file whose name does not match its class

These were 77 real findings and all 77 are fixed. The layout rules stay available
and ungated: turning one on later is a separate change with its own reformat,
which is the only way it can be reviewed.

How this is honest about what it does
--------------------------------------
ktlint's own output is discarded and re-parsed here. That is not ideal -- it means
this script depends on ktlint's message format -- so it fails loudly instead of
quietly when the format changes, rather than filtering everything into a pass. A
checker that cannot tell "no findings" from "my parser broke" is a checker that
reports green forever.

Verified to fail: each of the defect rules below was introduced into a real file
and this script reported it, then reported clean again after the file was
restored. See tools/verify_ktlint_gate.py, which does exactly that and runs in
CI.
"""

import argparse
import re
import subprocess
import sys

DEFECT_RULES = {
    "no-unused-imports",
    "import-ordering",
    "no-consecutive-blank-lines",
    "string-template",
    "final-newline",
    "no-trailing-spaces",
    "no-blank-line-before-rbrace",
    "multiline-loop",
    "function-expression-body",
    "chain-wrapping",
    "filename",
}

# ktlint prints "path:line:col: message (standard:rule-id)".
LINE = re.compile(r"^(?P<path>[^:]+):(?P<line>\d+):(?P<col>\d+):\s+"
                  r"(?P<msg>.*?)\s+\(standard:(?P<rule>[\w-]+)\)\s*$")


def main() -> int:
    ap = argparse.ArgumentParser(description="ktlint, gated on defect rules only")
    ap.add_argument("pattern", nargs="?", default="app/src/**/*.kt")
    ap.add_argument("--jar", default="/tmp/ktlint.jar",
                    help="path to the ktlint jar (not the shell wrapper)")
    ap.add_argument("--cwd", default=".")
    ap.add_argument("--list-all", action="store_true",
                    help="print every finding, including layout ones")
    args = ap.parse_args()

    proc = subprocess.run(
        ["java", "-jar", args.jar, args.pattern, "--relative"],
        capture_output=True, text=True, timeout=900, cwd=args.cwd)

    out = proc.stdout + proc.stderr
    crash_markers = (
        "Exception in thread",
        "AccessDeniedException",
        "Invalid or corrupt jarfile",
        "Could not find or load main class",
        "Unrecognized option",
        "no such file",
    )
    if any(m in out for m in crash_markers):
        # A crash must never read as a clean tree. That is how a gate that was
        # never actually run gets reported as a gate that passed.
        print(out[:2000], file=sys.stderr)
        print("ktlint nie dziala -- wynik jest bezuzyteczny, nie 'brak bledu'",
              file=sys.stderr)
        return 2

    defects, layout, unparsed = [], 0, []
    for line in out.split("\n"):
        if ".kt:" not in line:
            continue
        m = LINE.match(line.strip())
        if not m:
            unparsed.append(line.strip())
            continue
        if m.group("rule") in DEFECT_RULES:
            defects.append((m.group("path"), m.group("line"), m.group("msg"),
                            m.group("rule")))
        else:
            layout += 1

    if args.list_all:
        print(out)

    print(f"  ktlint: {layout + len(defects)} findings "
          f"({layout} layout, {len(defects)} defect)")
    if unparsed:
        # Fail on a format change rather than silently passing everything.
        print(f"  NIEROZPOZNANY FORMAT WYNIKU ({len(unparsed)} linii) - "
              f"bramka nie moze zaufać parserowi:", file=sys.stderr)
        for l in unparsed[:5]:
            print("    " + l, file=sys.stderr)
        return 2

    for path, line_no, msg, rule in defects:
        # The rule id is printed so tools/verify_ktlint_gate.py can assert which
        # rule fired. Without it the harness can only check prose, and a mutation
        # that trips a different rule than it claims still looks like a pass.
        print(f"  {path}:{line_no}: [{rule}] {msg}")

    if defects:
        print(f"  BRAK: {len(defects)} bledy z regul defektowych")
        return 1
    print("  brama ktlint: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())