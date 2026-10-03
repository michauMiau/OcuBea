#!/usr/bin/env python3
"""Mutation gate for the ramp guard in AdaptiveResolutionGovernor.

Background for why this exists at all
-------------------------------------
The first version of this harness reported three "caught" mutations for a guard
that was in fact unprotected. The cause was Gradle's up-to-date checking: the
mutated source changed, but `:app:testDebugUnitTest` was served from cache and
never recompiled, so the suite ran against the *original* production code and
came back green. A mutation harness that cannot tell "the test caught this" from
"the build did not run" is worse than no harness, because it manufactures
confidence.

So this harness enforces three things:

1. `--rerun-tasks` on every run, so the suite genuinely recompiles.
2. A compile error is reported as INCONCLUSIVE, never as a caught mutation. A
   mutation that does not compile has not been tested at all.
3. The mutated tree is restored from a byte-for-byte backup in a `finally`
   block, and the SHA-256 of the restored file is compared with the original.
   A harness that dies mid-run must not leave mutated production code behind --
   which is exactly what happened once already.

Usage:  python3 tools/mutate_ramp_guard.py
Exit:   0 when the baseline is green and every mutation is caught.
"""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "app/src/main/java/com/ocubea/camera/CameraManager.kt"
BACKUP = Path("/tmp/ramp_guard_backup.kt")
GRADLE_ENV = {**os.environ, "JAVA_HOME": "/usr/lib/jvm/java-17-openjdk-amd64"}

GUARD = "        if (windowStartMs > 0L && !isSpanCredible(nowMs, windowStartMs)) return Decision.NONE"
SPAN = "        return span in steadyWindowMs..maxSpanMs"
SPAN_DEFAULT = "    private var steadyWindowMs: Long = 1_000L"  # first occurrence; the doc comment above it is prose

# Each mutation removes or inverts one specific behaviour of the ramp guard. They
# are written as (old, new) so that a mutation which does not apply raises
# instead of silently testing nothing.
MUTATIONS: dict[str, tuple[str, str]] = {
    # The measured 2026-10-02 failure: with no guard at all, a 400 ms window
    # holding 7 fps against a request of 15 degrades 1280x720 -> 960x540.
    "guard removed entirely": (GUARD, ""),
    # The guard exists but never fires.
    "guard present but dead (if false)": (GUARD, "        if (false) return Decision.NONE"),
    # The span comparison inverted: every full window reads as a ramp, so the
    # ladder can never degrade and becomes decorative.
    "span check inverted (every window reads as a ramp)":
        (SPAN, "        return span !in steadyWindowMs..maxSpanMs"),
    # The 2026-10-03 production defect: accept ANY span, including the ~1.79e12
    # produced by mixing elapsedRealtime with currentTimeMillis.
    "implausible spans accepted (the clock-mismatch defect)":
        (SPAN, "        return span >= steadyWindowMs"),
    # Every window counts as steady, which is the original defect.
    "steadyWindowMs = 0": (SPAN_DEFAULT, "    private var steadyWindowMs: Long = 0L"),
    "maxSpanMs removed (clock mismatch accepted again)":
        ("        return span in steadyWindowMs..maxSpanMs",
         "        return span >= steadyWindowMs"),
    # Every window counts as a ramp, which is a blanket refusal.
    "steadyWindowMs = 60s": (SPAN_DEFAULT, "    private var steadyWindowMs: Long = 60_000L"),
}


class Sentinel:
    """Detects edits to the file under test made while this harness runs.

    Learned the hard way. This harness kept a `finally` restore that compared the
    SHA-256 against its own backup -- correct for safety, wrong for trust. Run
    alongside edits to the same file, it reverted those edits and still printed
    "source restored, SHA-256 verified against the original", because the file
    matched the snapshot it had taken. The gate reported 5/5 caught mutations
    against a source tree that no longer contained the code under review.

    So the harness now records the SHA it started from and re-checks it before
    every restore. If the file moved underneath, that is reported as a failure
    and the run is abandoned rather than clobbering the new work.
    """

    def __init__(self, path: Path, start_sha: str) -> None:
        self.path = path
        self.start_sha = start_sha
        self.harness_sha: str | None = None
        self.violations: list[str] = []

    def note_harness_write(self, text: str) -> None:
        """Record a write this harness performed, so it is not later mistaken
        for an outside edit."""
        self.harness_sha = hashlib.sha256(text.encode()).hexdigest()

    def started(self) -> bool:
        """True only when the file is byte-identical to the startup snapshot.

        A mutation this harness applied is also a different SHA, so SHA equality
        alone cannot distinguish "the harness mutated it" from "someone else
        edited it". The three states are tracked separately: the startup
        snapshot, the last thing the harness wrote, and anything else. Only the
        third is an outside edit -- and only that must block the restore.
        """
        current = hashlib.sha256(self.path.read_bytes()).hexdigest()
        if current == self.start_sha:
            return True
        if self.harness_sha is not None and current == self.harness_sha:
            return True
        self.violations.append(
            f"file is neither the startup snapshot ({self.start_sha[:12]}) nor "
            f"the last harness write ({str(self.harness_sha)[:12]}): {current[:12]}"
        )
        return False == self.start_sha


def run_suite() -> tuple[bool, list[str], bool]:
    """Run the governor tests. Returns (passed, failed_test_names, compile_error)."""
    result = subprocess.run(
        [
            str(ROOT / "gradlew"),
            ":app:testDebugUnitTest",
            # Mandatory. Without it Gradle serves the task UP-TO-DATE and the
            # mutation is never exercised.
            "--rerun-tasks",
            "--console=plain",
            "--tests",
            "*AdaptiveResolutionGovernorTest*",
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        env=GRADLE_ENV,
    )
    output = result.stdout + result.stderr
    compile_error = bool(re.search(r"^e: ", output, re.M))
    failed = [
        line.split("> ")[-1].split(" FAILED")[0].strip()
        for line in output.split("\n")
        if "AdaptiveResolutionGovernorTest >" in line and "FAILED" in line
    ]
    return ("FAILED" not in output), failed, compile_error


REQUIRED = [
    # (name, substring). The harness refuses to start unless the code it is about
    # to mutate is actually present. Added after it backed up a tree with the
    # guard already removed and then "restored" that broken state over the fix.
    ("the span guard call", "if (windowStartMs > 0L && !isSpanCredible(nowMs, windowStartMs)) return Decision.NONE"),
    ("isSpanCredible", "fun isSpanCredible(nowMs: Long, windowStartMs: Long): Boolean"),
    ("the steady window floor", "private var steadyWindowMs: Long = 1_000L"),
    ("the implausible-span ceiling", "private val maxSpanMs: Long = 3_600_000L"),
]


def main() -> int:
    if len(sys.argv) > 1:
        print(__doc__)
        print("\nThis harness takes no arguments; it was run as:")
        print("    python3 tools/mutate_ramp_guard.py")
        return 0

    preflight = TARGET.read_text()
    missing = [name for name, needle in REQUIRED if needle not in preflight]
    if missing:
        print("REFUSING TO RUN -- the code under test is not present:")
        for name in missing:
            print(f"  missing: {name}")
        print("\nRunning anyway would back up a broken tree and then restore it,")
        print("overwriting whatever fix is missing. Fix the code first.")
        return 2

    original = TARGET.read_text()
    original_sha = hashlib.sha256(original.encode()).hexdigest()
    shutil.copy2(TARGET, BACKUP)
    guard = Sentinel(TARGET, original_sha)

    problems: list[str] = []
    try:
        passed, failed, compile_error = run_suite()
        if not passed or compile_error:
            print(f"baseline is not green (passed={passed} compile_error={compile_error})")
            print("  failing:", failed or "(could not parse -- see the gradle output)")
            print("\nA red baseline means every mutation below would be meaningless.")
            return 2
        print(f"baseline: green, {len(MUTATIONS)} mutations to try\n")

        for label, (old, new) in MUTATIONS.items():
            if original.count(old) != 1:
                print(f"{label}: SKIPPED -- anchor matches {original.count(old)} times, need 1")
                problems.append(f"{label}: anchor did not match exactly once")
                continue

            mutated = original.replace(old, new, 1)
            TARGET.write_text(mutated)
            guard.note_harness_write(mutated)
            passed, failed, compile_error = run_suite()

            if compile_error:
                verdict = "INCONCLUSIVE (did not compile -- not a caught mutation)"
                problems.append(f"{label}: mutation did not compile")
            elif passed:
                verdict = "*** NOT CAUGHT ***"
                problems.append(f"{label}: survived")
            else:
                verdict = f"caught by {len(failed)} test(s)"

            print(f"{label}\n  {verdict}")
            for name in failed:
                print(f"    -> {name}")
    finally:
        # If the file was edited from outside while this ran, do NOT restore: the
        # backup predates those edits and clobbering them is the exact failure this
        # class exists to catch.
        if not guard.started():
            # Deliberately no restore, and deliberately no return: a return here
            # would skip the SHA check below, which is the one thing that proves
            # the tree is clean. Flag it and fall through instead.
            print("\nABORTED: the file under test was edited from outside this "
                  "harness while it ran. Restoring would revert that work, so "
                  "nothing was restored. Re-run the harness on a quiet tree.")
            problems.append("file under test was edited from outside during the run")
        elif BACKUP.exists():
            shutil.copy2(BACKUP, TARGET)
        restored = hashlib.sha256(TARGET.read_bytes()).hexdigest()
        restore_ok = guard.started() and restored == original_sha
        if not restore_ok and guard.started():
            problems.append("restore did not reproduce the original file")
        print(
            "\nsource restored, SHA-256 verified against the original"
            if restore_ok
            else "\nsource NOT restored -- the tree is not in its starting state"
        )

    if problems:
        if any("edited from outside" in p for p in problems):
            return 4
        print("\nNOT ALL MUTATIONS CAUGHT:")
        for problem in problems:
            print(" -", problem)
        return 1
    print(f"all {len(MUTATIONS)} mutations caught")
    return 0


if __name__ == "__main__":
    sys.exit(main())