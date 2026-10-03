#!/usr/bin/env python3
"""Prove the restore guard in mutate_ramp_guard.py actually restores.

Written because the guard was wrong twice and both times it looked correct in the
log. First it reported "source restored, SHA-256 verified against the original"
while silently reverting work done after the harness started. Then, after that
was fixed, it refused to restore a legitimate mutation and left
`steadyWindowMs = 60_000L` on disk: SHA equality could not tell "the harness
mutated the file" from "someone edited it".

So this exercises the real Sentinel class from the real module, against a
throwaway file, in all three states:

  1. untouched            -> restore is allowed
  2. mutated by harness   -> restore is allowed (the harness made this change)
  3. edited from outside  -> restore is REFUSED, or clobbering real work

Case 3 is the one that matters: a restore that runs there deletes someone's
uncommitted work while printing that everything is fine.

Usage: python3 tools/verify_restore_guard.py
"""

from __future__ import annotations

import hashlib
import importlib.util
import shutil
import sys
from pathlib import Path

MODULE = Path(__file__).resolve().parent / "mutate_ramp_guard.py"


def load_sentinel_class() -> type:
    """Import the harness without running its main().

    Loaded by path rather than by name so this works regardless of sys.path, and
    the module has a __main__ guard, so importing it is side-effect free.
    """
    spec = importlib.util.spec_from_file_location("mutate_ramp_guard", MODULE)
    assert spec and spec.loader, "cannot load the harness module"
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.Sentinel  # type: ignore[no-any-return]


def main() -> int:
    sentinel_class = load_sentinel_class()
    work = Path("/tmp/restore_guard_probe.kt")
    failures: list[str] = []

    def fresh(content: str = "val steadyWindowMs: Long = 1_000L\n") -> object:
        work.write_text(content)
        start = hashlib.sha256(work.read_bytes()).hexdigest()
        return sentinel_class(work, start)

    # The type checker cannot see attributes on the dynamically loaded class, so
    # the probe is typed loosely on purpose. Every use below is still checked at
    # runtime by the assertions, which is what actually matters here.

    # 1. Untouched: the restore must be allowed.
    guard = fresh()
    if not guard.started():
        failures.append("untouched file was treated as an outside edit")
    print(f"1. untouched            -> restore {'ALLOWED' if guard.started() else 'REFUSED'}  (want ALLOWED)")

    # 2. Mutated by the harness: still our own change, restore must be allowed.
    guard = fresh()
    mutated = "val steadyWindowMs: Long = 60_000L\n"
    work.write_text(mutated)
    guard.note_harness_write(mutated)
    allowed = guard.started()
    if not allowed:
        failures.append("a mutation the harness itself applied was treated as an outside edit")
    print(f"2. mutated by harness   -> restore {'ALLOWED' if allowed else 'REFUSED'}  (want ALLOWED)")

    # 3. Edited from outside: the restore must be refused, or it deletes real work.
    guard = fresh()
    work.write_text("// someone else's uncommitted edit\n")
    allowed = guard.started()
    if allowed:
        failures.append("an outside edit was treated as restorable -- the restore would clobber real work")
    print(f"3. edited from outside  -> restore {'ALLOWED' if allowed else 'REFUSED'}  (want REFUSED)")

    # 4. Harness mutation, then an outside edit on top: still refused. This is the
    #    sequence that actually happened -- the last mutation was in place, a
    #    second edit landed, and the guard could not tell them apart.
    guard = fresh()
    work.write_text(mutated)
    guard.note_harness_write(mutated)
    work.write_text(mutated + "// and then a real edit\n")
    allowed = guard.started()
    if allowed:
        failures.append("harness mutation plus an outside edit was treated as restorable")
    print(f"4. mutation + outside   -> restore {'ALLOWED' if allowed else 'REFUSED'}  (want REFUSED)")

    if work.exists():
        work.unlink()

    print()
    if failures:
        print("FAILED:")
        for failure in failures:
            print(" -", failure)
        return 1
    print("all four restore cases behave correctly")
    return 0


if __name__ == "__main__":
    sys.exit(main())
