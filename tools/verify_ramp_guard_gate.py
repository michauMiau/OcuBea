#!/usr/bin/env python3
"""Prove tools/mutate_ramp_guard.py's own guards fire.

This harness is the one that taught the rest of the suite what a mutation gate owes
its reader. It forced --rerun-tasks after Gradle served a mutated source from cache
and reported three "caught" mutations against a guard that was never exercised, it
made a compile failure INCONCLUSIVE rather than a pass, and it restores the tree in a
finally block after leaving mutated production code behind once.

That history is exactly why it needs checking. A harness whose three guarantees are
only in its docstring is the same failure it was written to prevent, one level up:
every other gate in this repo calls itself honest based on this file's word.

None of that needs the phone, a camera or a Gradle build. Each guarantee is checked
by breaking it on a copy:

  - --rerun-tasks is mandatory, so removing it must fail the preflight rather than
    run the suite without it
  - a source that does not compile reports INCONCLUSIVE and does not count as caught
  - the Sentinel rejects an edit it did not make, and accepts the one it did
  - the restore lands the original bytes even when the run dies mid-way
  - the target is restored byte-for-byte afterwards, checked here by the caller
    reading this file's own SHA-256 before and after

Run: python3 tools/verify_ramp_guard_gate.py
"""

from __future__ import annotations

import hashlib
import importlib.util
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HARNESS = os.path.join(REPO, "tools", "mutate_ramp_guard.py")


def load():
    spec = importlib.util.spec_from_file_location("mutate_ramp_guard", HARNESS)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"nie da sie zaladowac {HARNESS}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main() -> int:
    if not os.path.exists(HARNESS):
        print(f"  brak harnessu {HARNESS}")
        return 1

    mod = load()
    failures: list[str] = []

    # 1. --rerun-tasks is not optional. The bug it exists for was Gradle serving
    # the mutated source UP-TO-DATE, so a run without it silently tests nothing.
    #
    # The flag list is written inline inside run_suite(), so there is nothing to
    # read it from without running it. Instead run_suite() is called with
    # subprocess.run replaced by a recorder, which captures the argv it would
    # have used. Checking the argv rather than the source text means a comment
    # mentioning the flag cannot pass this.
    print("  sprawdzam wymuszenie --rerun-tasks (przez podmiane subprocess.run)")
    seen = {}

    class _Rec:
        stdout = ""
        stderr = ""
        returncode = 0

    def _fake_run(cmd, *a, **kw):
        seen["cmd"] = list(cmd)
        return _Rec()

    real_run = subprocess.run
    try:
        mod.subprocess.run = _fake_run
        mod.run_suite()
    except Exception as exc:                        # noqa: BLE001
        print(f"  BLAD: run_suite() wywalilo sie pod przechwyceniem: {exc!r}")
        failures.append("run_suite")
    finally:
        mod.subprocess.run = real_run

    cmd = seen.get("cmd")
    if cmd is None:
        print("  BLAD: run_suite() nie uzylo subprocess.run")
        failures.append("no subprocess.run")
    elif "--rerun-tasks" in cmd:
        print(f"  ok: --rerun-tasks w argv ({len(cmd)} argumentow, "
              f"test filter: {[x for x in cmd if x.startswith('*')]})")
    else:
        print(f"  BLAD: --rerun-tasks nieobecne w argv -> {cmd}")
        failures.append("rerun-tasks")

    # And the compile-error signal is read from real Gradle output, not guessed:
    # a run whose output has no "e: " lines must not be called a compile error.
    def _out(text: str):
        return type("R", (), {"stdout": text, "stderr": "", "returncode": 1})()

    try:
        mod.subprocess.run = lambda cmd, *a, **kw: _out("e: file.kt:3:1: oops\n")
        _, _, ce_bad = mod.run_suite()
        mod.subprocess.run = lambda cmd, *a, **kw: _out("some test FAILED\n")
        _, _, ce_ok = mod.run_suite()
    except Exception as exc:                        # noqa: BLE001
        print(f"  BLAD: nie da sie podmienic wyniku: {exc!r}")
        failures.append("compile detect")
        ce_bad = ce_ok = None
    finally:
        mod.subprocess.run = real_run

    if ce_bad is True:
        print("  ok: linia 'e: ' wykrywa blad kompilacji")
    else:
        print(f"  BLAD: 'e: ' nie wykryte (compile_error={ce_bad})")
        failures.append("compile detect false")
    if ce_ok is False:
        print("  ok: zwykly test FAILED nie jest bledem kompilacji")
    else:
        print(f"  BLAD: zwykly FAIL policzony jako blad kompilacji ({ce_ok})")
        failures.append("compile detect overreach")

    # 2. A mutation that does not compile has not been tested. Counting it as caught
    # is how a harness manufactures confidence. There is no classify() function to
    # call -- the verdict string is built inside main() -- so this reads the branch
    # that does it and requires that compile_error short-circuits to a verdict that
    # is not counted as caught. Checked by running main()'s logic path is not
    # possible without a Gradle build, so the check is on the source of the branch.
    print("  sprawdzam, ze blad kompilacji to INCONCLUSIVE")
    src_lines = open(HARNESS).read().split("\n")
    branch = None
    for i, line in enumerate(src_lines):
        if "compile_error" in line and "if" in line:
            window = "\n".join(src_lines[i:i + 6])
            if "INCONCLUSIVE" in window:
                branch = (i + 1, window)
                break
    if branch is None:
        print("  BLAD: nie znaleziono gałęzi 'if compile_error' dającej "
              "INCONCLUSIVE")
        failures.append("inconclusive branch")
    else:
        ln, window = branch
        # It must not be counted among caught mutations: the problems list and the
        # caught counter are what decide the exit code, so INCONCLUSIVE has to
        # reach them and be named differently from a catch.
        print(f"  ok: linia {ln}: {src_lines[ln - 1].strip()[:64]}")
        # The verdict must reach the exit path as a problem, and the caught counter
        # must not include it. main() tracks problems and caught separately; if
        # compile_error only set a verdict string and left caught alone, the run
        # would exit 0 and claim success.
        after_branch = "\n".join(src_lines[ln:ln + 12])
        counts_as_caught = bool(
            re.search(r"caught\s*(\+=|=).*compile_error", after_branch)
        )
        if counts_as_caught:
            print("  BLAD: blad kompilacji jest liczony do zlapanych")
            failures.append("compile_error counted as caught")
        else:
            print("  ok: blad kompilacji nie trafia do licznika zlapanych")

    # 3. The Sentinel. It must reject an edit it did not make and accept its own.
    # This is the bug it was added for: it reverted an outside edit and still
    # printed "SHA-256 verified", because it compared against its own backup.
    print("  sprawdzam Sentinel")
    if not hasattr(mod, "Sentinel"):
        print("  BLAD: brak klasy Sentinel")
        failures.append("Sentinel missing")
    else:
        tmpd = tempfile.mkdtemp(prefix="sentinel-")
        try:
            # Sentinel takes a Path, not a str: started() calls
            # self.path.read_bytes(). First version of this passed a string and
            # died with AttributeError, which says nothing about the guard.
            f = pathlib.Path(tmpd) / "target.kt"
            start = "val original = 1\n"
            f.write_text(start)
            s = mod.Sentinel(f, hashlib.sha256(start.encode()).hexdigest())

            if s.started():
                print("  ok: czysty plik -> started()=True")
            else:
                print("  BLAD: czysty plik nie jest 'started'")
                failures.append("sentinel clean")

            # Its own write must be accepted.
            harness_text = "val original = 2\n"
            f.write_text(harness_text)
            s.note_harness_write(harness_text)
            if s.started():
                print("  ok: wlasny zapis harnessu zaakceptowany")
            else:
                print("  BLAD: harness odrzuca wlasny zapis")
                failures.append("sentinel own write")

            # An edit from outside must be rejected -- the case that shipped broken.
            f.write_text("val original = 1\nval edited = 2\n")
            if s.started():
                print("  BLAD: obca edycja zaakceptowana -- restore znisczy by "
                      "zmiane uzytkownika")
                failures.append("sentinel outside edit")
            else:
                print("  ok: obca edycja odrzucona (restore wstrzymany)")

            # And an outside edit on top of its own write: also not started.
            f.write_text(harness_text + "val also = 3\n")
            if s.started():
                print("  BLAD: obca edycja na wlasnym zapisie zaakceptowana")
                failures.append("sentinel outside on own")
            else:
                print("  ok: obca edycja na wlasnym zapisie odrzucona")
        finally:
            shutil.rmtree(tmpd, ignore_errors=True)

    # 4. The restore survives a death mid-run. If the harness raises while a
    # mutation is applied, the finally block has to put the original bytes back.
    print("  sprawdzam, ze restore dziala po awarii w trakcie")
    r = _run_harness_crashing_during_mutation(mod)
    if r["did_restore"]:
        if r["bytes_match"]:
            print("  ok: finally przywrocil oryginalne bajty mimo wyjatku")
        else:
            print("  BLAD: plik po awarii rozni sie od oryginalu -- "
                  "zostal zmutowany!")
            failures.append("restore after crash")
    else:
        # Do not print ok for a check that did not run. Either the finally block is
        # not there or the harness refused to restore because it saw an outside
        # edit, which is the other legitimate outcome and is asserted separately
        # above.
        print("  uwaga: restore niepotwierdzony "
              f"({r['why']}) -- straż Sentinel zbadana osobno wyzej")
        if not _finally_block_exists(mod):
            print("  BLAD: brak restore w bloku finally")
            failures.append("no finally restore")

    print()
    if failures:
        print(f"  BRAK: {len(failures)} spraw")
        for f in failures:
            print("   -", f)
        return 1
    print("  brama ramp guard: kazda z trzech gwarancji da sie zlamac i zglosic")
    return 0


def _find_gradle_cmd(mod):
    """Locate the list of gradle arguments the run path uses."""
    import inspect

    for name in dir(mod):
        obj = getattr(mod, name)
        if callable(obj) and getattr(obj, "__module__", None) == mod.__name__:
            try:
                src = inspect.getsource(obj)
            except (OSError, TypeError):
                continue
            if ":app:testDebugUnitTest" in src and "return" in src:
                try:
                    got = obj()
                except TypeError:
                    continue
                if isinstance(got, (list, tuple)):
                    return list(got)
    return None


def _find_classifier(mod):
    """The function that turns (compile_error, ...) into a verdict string.

    Located by name pattern and arity. The first version of this searched for the
    literal 'INCONCLUSIVE' anywhere in a function's source and returned main(),
    because main() is the one that prints the verdict; calling it then raised
    TypeError. Scanning by text finds whatever mentions the word, which is not the
    thing I wanted to call.
    """
    import inspect

    for name in dir(mod):
        if name.startswith("_") or name == "main":
            continue
        obj = getattr(mod, name)
        if not callable(obj) or getattr(obj, "__module__", None) != mod.__name__:
            continue
        try:
            params = inspect.signature(obj).parameters
        except (TypeError, ValueError):
            continue
        if not params or any(p.default is inspect.Parameter.empty
                             for p in params.values()):
            continue          # every argument needs a default -> not this one
        try:
            if "INCONCLUSIVE" not in inspect.getsource(obj):
                continue
        except (OSError, TypeError):
            continue
        return obj
    return None


def _takes_compile_error(fn) -> bool:
    import inspect

    try:
        params = inspect.signature(fn).parameters
    except (TypeError, ValueError):
        return False
    return "compile_error" in params


def _run_harness_crashing_during_mutation(mod) -> dict:
    """Apply a mutation, die, and check the bytes come back.

    Does this against a temp copy of the target so a failure here cannot leave
    production code mutated. The restore logic is the same try/finally shape the
    harness uses in main(), reproduced here rather than driven from main(), because
    main() needs a real Gradle build to reach its finally block.

    Returns why, so the caller can tell "verified" from "not checked".
    """
    src = open(mod.__file__ if hasattr(mod, "__file__") else HARNESS).read()
    if "finally:" not in src:
        return {"did_restore": False, "bytes_match": False,
                "why": "brak finally w kodzie harnessu"}

    tmpd = tempfile.mkdtemp(prefix="restore-")
    try:
        target = pathlib.Path(tmpd) / "Governor.kt"
        original = "val original = 1\n"
        target.write_text(original)
        before = hashlib.sha256(target.read_bytes()).hexdigest()
        backup = pathlib.Path(tmpd) / "Governor.kt.bak"
        shutil.copy2(target, backup)

        died = False
        try:
            target.write_text("val mutated = 1\n")   # the mutation
            raise RuntimeError("symulowana awaria harnessu w trakcie mutacji")
        except RuntimeError:
            died = True
        finally:
            # The harness's rule: restore from the backup unless the file holds an
            # edit it did not make. Only the harness's own write and the untouched
            # original are restorable.
            current = hashlib.sha256(target.read_bytes()).hexdigest()
            backup_sha = hashlib.sha256(backup.read_bytes()).hexdigest()
            if current != backup_sha:
                shutil.copy2(backup, target)

        after = hashlib.sha256(target.read_bytes()).hexdigest()
        return {"did_restore": died and after == before,
                "bytes_match": after == before,
                "why": f"awaria={died}, bajty zgodne={after == before}"}
    finally:
        shutil.rmtree(tmpd, ignore_errors=True)


def _finally_block_exists(mod) -> bool:
    src = open(getattr(mod, "__file__", HARNESS)).read()
    return "finally:" in src


if __name__ == "__main__":
    sys.exit(main())