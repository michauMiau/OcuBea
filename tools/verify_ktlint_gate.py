#!/usr/bin/env python3
"""Prove the ktlint gate can fail, and prove it fails when ktlint itself breaks.

The gate in tools/ktlint_gate.py re-parses ktlint output instead of trusting an
exit code. That is the kind of thing that works on the day it is written and
silently passes forever after, because a parser that stops matching turns every
finding into "no findings". So two things are checked here, on real files in the
repository, and both were watched going red before either was fixed:

  1. Each defect rule, introduced into a real source file, is reported by the
     gate -- and reported clean again once the file is restored.
  2. A ktlint invocation that crashes makes the gate exit 2, not 0. A gate that
     reports "no defects" when the tool underneath it failed to start is worse
     than no gate at all, because it looks like a pass.

The harness refuses to run against a dirty tree: restoring these files is what
makes the mutations safe, and a file edited from underneath the harness is how
work gets deleted while the log says everything was verified.
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GATE = os.path.join(REPO, "tools", "ktlint_gate.py")
KTJAR = "/tmp/ktlint.jar"
KTLINT_URL = "https://github.com/pinterest/ktlint/releases/download/1.5.0/ktlint"


def ensure_ktlint():
    """Fetches ktlint if the jar is gone, and refuses to run without it.

    /tmp is a scratch directory and gets pruned, so the jar disappears between
    sessions. Without this the gate still ran: every mutation "passed", and the
    summary read BRAK: 6 z 7 spraw -- six checks that could not have failed,
    reported in the same voice as real coverage. ktlint_gate.py does fail
    loudly on a broken invocation, but only for the run it makes itself; the
    mutation harness interpreted the missing jar as six clean files.

    So the jar is a precondition, not a nicety: fetch it, or exit non-zero
    before touching the target.
    """
    if os.path.exists(KTJAR):
        return True
    print(f"  brak {KTJAR}, pobieram ktlint 1.5.0...")
    try:
        urllib.request.urlretrieve(KTLINT_URL, KTJAR)
    except Exception as e:
        print(f"BLAD: nie da sie pobrac ktlint: {e}")
        print("      bramka ktlint jest bezczynna, a nie zielona")
        return False
    return os.path.exists(KTJAR)

# Chosen because it has every feature the six mutations need: six imports, so
# two can be swapped; a bare $identifier, so the string-template rule can be
# triggered; and blank lines, so a third can be introduced.
#
# The first two files tried were wrong in instructive ways. One was
# single-imported and held only ${System.nanoTime()}, so the reorder and
# string-template mutations changed nothing at all -- the harness reported them as
# tests that could never go red, which is what it is for. The other had no bare
# $identifier either, and ${x.y} is explicitly not flagged, so "pad the braces"
# could not have worked on it either.
TARGET = "app/src/test/java/com/ocubea/camera/AdaptiveResolutionGovernorTest.kt"

MUTATIONS = [
    ("unused import",
     "no-unused-imports",
     lambda s: s.replace("import org.junit.Test",
                         "import org.junit.Test\nimport java.util.zip.ZipFile", 1)),
    ("duplicated import",
     "import-ordering",
     lambda s: s.replace("import org.junit.Test",
                         "import org.junit.Test\nimport org.junit.Test", 1)),
    ("imports out of order",
     "import-ordering",
     "swap_first_two_imports"),
    ("no final newline",
     "final-newline",
     lambda s: s.rstrip("\n")),
    ("consecutive blank lines",
     "no-consecutive-blank-lines",
     lambda s: s.replace("\n\n", "\n\n\n", 1)),
    ("redundant braces in a template",
     "string-template",
     "pad_template"),
]


def apply_edit(body, edit):
    if edit == "swap_first_two_imports":
        lines = body.split("\n")
        idx = [i for i, l in enumerate(lines) if l.startswith("import ")]
        if len(idx) >= 2:
            lines[idx[0]], lines[idx[1]] = lines[idx[1]], lines[idx[0]]
            return "\n".join(lines)
        return body
    if edit == "pad_template":
        # $x -> ${x}, and that is the rule backwards twice over. The first attempt
        # assumed double braces ($x -> ${{x}}) were the violation; the second
        # assumed padded braces (${x} -> ${ x }). Both passed the gate, so both
        # were mutations that could never have turned it red. Checked against five
        # variants: $x passes, ${x} IS flagged, $x.y passes, ${x.y} passes,
        # ${f()} passes. The rule flags braces around a bare identifier, because
        # $x alone already means the same thing.
        m = re.search(r"(?<![\w{])\$(\w+)\b", body)
        if not m:
            return body
        return body[:m.start()] + "${" + m.group(1) + "}" + body[m.end():]
    return edit(body)


def run_gate(pattern=None, jar=KTJAR):
    cmd = ["python3", GATE, "--jar", jar, "--cwd", REPO, pattern or TARGET]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=600)


def main():
    if not ensure_ktlint():
        return 1
    if shutil.which("java") is None:
        print("brak java - nie da sie zweryfikowac bramy")
        return 1

    src = os.path.join(REPO, TARGET)
    if not os.path.exists(src):
        print("brak pliku testowego " + TARGET)
        return 1

    dirty = subprocess.run(["git", "status", "--porcelain", TARGET],
                           capture_output=True, text=True, cwd=REPO)
    if dirty.stdout.strip():
        print("  brak: " + TARGET + " ma niezacommitowane zmiany. Bramka nie "
              "moze testowac na pliku, ktory kto juz edytuje - przywracanie "
              "mogloby skasowac prace.")
        return 1

    backup = tempfile.NamedTemporaryFile(delete=False, suffix=".kt")
    shutil.copyfile(src, backup.name)
    backup.close()
    original = open(backup.name).read()

    def restore():
        shutil.copyfile(backup.name, src)

    failures = []
    try:
        base = run_gate()
        if base.returncode != 0:
            print("  brak: plik testowy nie jest czysty przed mutacja")
            print(base.stdout, base.stderr)
            return 1
        print("  baseline: czysty")

        for label, rule, edit in MUTATIONS:
            body = apply_edit(original, edit)
            if body == original:
                print("  brak: mutacja '" + label + "' niczego nie zmienila - "
                      "test nie bylb w stanie zaczerwienic")
                failures.append(label)
                continue

            open(src, "w").write(body)
            got = run_gate()
            restore()

            # The gate prints [rule-id] next to each finding, so this asserts
            # which rule fired. Matching on prose alone meant a mutation tripping
            # a different rule than it claims still read as a pass.
            if got.returncode == 0:
                print("  BLAD: mutacja '" + label + "' (" + rule + ") - brama przeszla")
                failures.append(label)
            elif "[" + rule + "]" not in got.stdout:
                print("  BLAD: mutacja '" + label + "' zglosiona jako inna regula "
                      "(oczekiwano " + rule + ")")
                print("   ", got.stdout.strip().replace("\n", "\n    ")[:400])
                failures.append(label)
            else:
                print("  ok: '" + label + "' -> zlapane (" + rule + ")")

        # ktlint itself failing must not read as a clean tree. java prints
        # "Invalid or corrupt jarfile" and exits 1, so without that marker the gate
        # parses an empty result and reports no defects.
        r = subprocess.run(["python3", GATE, "--jar", "/etc/hostname", TARGET],
                           capture_output=True, text=True, timeout=300, cwd=REPO)
        if r.returncode == 0:
            print("  BLAD: ktlint sie wywalil, a brama zwrocila 0 - taki wynik "
                  "wygladalby jak sukces")
            failures.append("ktlint crash = exit 0")
        elif r.returncode == 2:
            print("  ok: ktlint sie wywalil -> brama zwraca 2, nie 'brak bledu'")
        else:
            print("  uwaga: ktlint bledu daje kod " + str(r.returncode) + ", oczekiwano 2")
            failures.append("ktlint crash = wrong code")

    finally:
        restore()

    if open(src).read() != original:
        print("  BLAD: " + TARGET + " nie wrócil do stanu wyjsciowego")
        failures.append("restore")
    else:
        print("  restore: plik identyczny z wyjsciowym")

    os.unlink(backup.name)
    print()
    if failures:
        print("  BRAK: " + str(len(failures)) + " z " + str(len(MUTATIONS) + 1) + " spraw")
        for f in failures:
            print("   -", f)
        return 1
    print("  brama ktlint: " + str(len(MUTATIONS)) + " mutacji zlapanych, restore OK, crash wykryty")
    return 0


if __name__ == "__main__":
    sys.exit(main())
