#!/usr/bin/env python3
"""Prove tools/check_mjpeg.py can fail, on inputs built to fail it.

check_mjpeg.py is the tool that distinguishes "the camera sent nothing" from "the
consumer wrote nothing". It matters exactly when something is wrong, so a version
that cannot report failure is worse than no tool: it gets trusted on a broken
capture and confirms nothing.

Its main() used to return 0 unconditionally. Measured, on inputs constructed for
the purpose: a 100x garbage buffer, an empty file, and a nonexistent path all
exited 0. The verdict lines it printed were accurate -- "no JPEG SOI anywhere",
"truncated or corrupt" -- which is what makes it dangerous, because a correct
verdict in the output reads as a verdict that was acted on.

This builds the inputs rather than checking in binaries: a real JPEG needs a real
entropy-coded scan, and a checked-in capture is a file nobody can review. Each
frame here is a syntactically valid SOI + APP0 + SOF0 + EOI at a chosen size,
which is enough for the SOF walk and the boundary count that decide the verdict.

Run: python3 tools/verify_mjpeg_gate.py
"""

from __future__ import annotations

import os
import struct
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOL = os.path.join(REPO, "tools", "check_mjpeg.py")


def jpeg(width: int, height: int, payload: bytes = b"\x00" * 40) -> bytes:
    """A JPEG carrying only what check_mjpeg.py reads: SOI, APP0, SOF0, EOI."""
    sof = (
        b"\xff\xc0"
        + struct.pack(">HBHHB", 17, 8, height, width, 1)
        + b"\x01\x11\x00"
    )
    return b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00" + sof + payload + b"\xff\xd9"


CASES = [
    # (label, bytes, expected exit)
    ("garbage, no SOI", b"NOT A JPEG AT ALL" * 100, 1),
    ("empty file", b"", 1),
    ("SOI but no SOF, truncated", b"\xff\xd8\xff\xe0\x00\x10JFIF\x00", 1),
    ("frames, no multipart boundary", jpeg(1280, 720) + jpeg(1280, 720), 1),
    ("frames disagree on size", jpeg(1280, 720) + b"--frame\r\n" + jpeg(640, 480), 1),
    ("one real frame, one boundary", jpeg(1280, 720) + b"--frame\r\n", 0),
    ("several consistent frames", jpeg(1280, 720) * 3 + b"--frame\r\n" * 3, 0),
]


def run(path: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["python3", TOOL, path], capture_output=True, text=True, timeout=120
    )


def main() -> int:
    if not os.path.exists(TOOL):
        print(f"brak narzedzia {TOOL}")
        return 1

    failures: list[str] = []
    tmpdir = tempfile.mkdtemp(prefix="mjpeg-gate-")
    try:
        print("  baseline: brak argumentow musi byc bladem uzycia")
        noargs = subprocess.run(
            ["python3", TOOL], capture_output=True, text=True, timeout=120
        )
        if noargs.returncode == 2:
            print("  ok: bez argumentow -> 2")
        else:
            print(f"  BLAD: bez argumentow -> {noargs.returncode}, oczekiwano 2")
            failures.append("no args")

        missing = run(os.path.join(tmpdir, "does-not-exist.mjpeg"))
        if missing.returncode == 2:
            print("  ok: nieistniejacy plik -> 2")
        else:
            print(f"  BLAD: nieistniejacy plik -> {missing.returncode}, oczekiwano 2")
            failures.append("missing file")

        for label, body, expected in CASES:
            path = os.path.join(tmpdir, label.replace(" ", "_") + ".mjpeg")
            with open(path, "wb") as fh:
                fh.write(body)
            got = run(path)
            if got.returncode != expected:
                verdict = [
                    l.strip()
                    for l in got.stdout.strip().split("\n")
                    if "verdict" in l
                ]
                print(
                    f"  BLAD: '{label}' -> {got.returncode}, oczekiwano {expected}"
                    + (f"  ({verdict[0][:70]})" if verdict else "")
                )
                failures.append(label)
            else:
                print(f"  ok: '{label}' -> {expected}")

        # A good file must not mask a bad one when both are passed. Reporting only
        # the first failure is how one healthy capture hides a broken one.
        good = os.path.join(tmpdir, "good.mjpeg")
        bad = os.path.join(tmpdir, "bad.mjpeg")
        with open(good, "wb") as fh:
            fh.write(jpeg(1280, 720) * 2 + b"--frame\r\n" * 2)
        with open(bad, "wb") as fh:
            fh.write(b"NOT A JPEG" * 100)
        both = subprocess.run(
            ["python3", TOOL, good, bad], capture_output=True, text=True, timeout=120
        )
        if both.returncode == 1:
            print("  ok: dobry plik nie zamaskowal zepsutego")
        else:
            print(f"  BLAD: dobry+zly -> {both.returncode}, oczekiwano 1")
            failures.append("masking")
    finally:
        for name in os.listdir(tmpdir):
            os.unlink(os.path.join(tmpdir, name))
        os.rmdir(tmpdir)

    print()
    if failures:
        print(f"  BRAK: {len(failures)} z {len(CASES) + 3} spraw")
        for f in failures:
            print("   -", f)
        return 1
    print(f"  bramka mjpeg: {len(CASES)} przypadkow, brak argumentow, brak pliku, "
          f"maskowanie - wszystko zlapane")
    return 0


if __name__ == "__main__":
    sys.exit(main())
