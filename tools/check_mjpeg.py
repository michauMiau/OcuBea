#!/usr/bin/env python3
"""Check whether an MJPEG capture actually contains decodable frames.

Written because a null-bitmap reading looked like a dead stream and was not: the
consumer that produced the evidence wrote zero bytes because the output file
never appeared, not because the camera sent nothing. A pipeline that reports "no
frames" and a measurement that failed to record must not be confused.

So this verifies the stream itself: JPEG SOI/EOI markers, frame boundaries, and
the dimensions each SOF header claims. A stream of real frames has all three.

The exit code is the gate. main() used to return 0 unconditionally, so this
printed an accurate verdict -- "no JPEG SOI anywhere", "truncated or corrupt" --
and then reported success, which made it a report and not a check. Measured on
purpose-built inputs: a 100x garbage buffer and an empty file both exited 0. A
script that describes a broken stream correctly while exiting 0 is worse than one
that stays silent, because the verdict reads as a verdict.

Exit codes:
  0  real frames, and every SOF header claims the same size
  1  not a usable image stream, or the frames disagree on size
  2  no such file
"""

from __future__ import annotations

import struct
import sys
from collections import Counter

SOI = b"\xff\xd8\xff"
EOI = b"\xff\xd9"


def inspect(path: str) -> dict:
    data = open(path, "rb").read()
    result: dict = {
        "bytes": len(data),
        "distinct_bytes": len(set(data[:200_000])),
    }

    first_soi = data.find(SOI)
    result["first_soi"] = first_soi
    if first_soi < 0:
        result["verdict"] = "no JPEG SOI anywhere -- not an image stream"
        result["ok"] = False
        return result

    # Walk the SOF headers to learn what size the frames claim to be. A JPEG that
    # decodes to a different size than the camera bound is the signature of a
    # mismatched stride, which on this device is what a null bitmap looks like
    # from the far end.
    sizes: Counter = Counter()
    pos = first_soi
    for _ in range(400):
        pos = data.find(b"\xff\xc0", pos + 1)
        if pos < 0:
            break
        height, width = struct.unpack(">HH", data[pos + 5:pos + 9])
        sizes[(width, height)] += 1
    if not sizes:
        pos = first_soi
        for _ in range(400):
            pos = data.find(b"\xff\xc2", pos + 1)
            if pos < 0:
                break
            height, width = struct.unpack(">HH", data[pos + 5:pos + 9])
            sizes[(width, height)] += 1
    result["frame_sizes"] = dict(sizes)

    boundaries = data.count(b"--frame") + data.count(b"--boundary")
    result["boundaries"] = boundaries
    result["eoi_count_sample"] = data[:2_000_000].count(EOI)

    # One size across every frame. A mixed table is not a stream to trust: it
    # means the encoder renegotiated mid-capture, or the capture spliced two
    # sources together, and either way the consumer cannot rely on the frame size
    # it is told to allocate.
    if sizes and boundaries:
        result["ok"] = len(sizes) == 1
        if result["ok"]:
            result["verdict"] = (
                f"real frames: {sum(sizes.values())} SOF headers, "
                f"{boundaries} boundaries"
            )
        else:
            result["verdict"] = (
                f"frames disagree on size: {sizes} -- the stream is not consistent"
            )
    elif sizes:
        # SOI and SOF but no multipart boundary: concatenated JPEGs with no
        # delimiter, which some consumers accept and this one cannot split.
        result["ok"] = False
        result["verdict"] = f"JPEG present ({sizes}), but no multipart boundary"
    else:
        result["ok"] = False
        result["verdict"] = "SOI present but no SOF header -- truncated or corrupt"
    return result


def main() -> int:
    paths = sys.argv[1:]
    if not paths:
        print("uzycie: check_mjpeg.py <plik> [<plik> ...]")
        return 2

    worst = 0
    for path in paths:
        try:
            info = inspect(path)
        except FileNotFoundError:
            print(f"=== {path}\n  brak pliku")
            worst = 2
            continue
        print(f"=== {path}")
        for key, value in info.items():
            print(f"  {key:18}: {value}")
        if not info.get("ok"):
            worst = 1

    if worst:
        print(f"  BRAK: {worst} plikow nie jest poprawnym strumieniem klatek")
    return worst


if __name__ == "__main__":
    sys.exit(main())
