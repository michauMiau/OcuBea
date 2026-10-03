#!/usr/bin/env python3
"""Check whether an MJPEG capture actually contains decodable frames.

Written because a null-bitmap reading looked like a dead stream and was not: the
consumer that produced the evidence wrote zero bytes because the output file
never appeared, not because the camera sent nothing. A pipeline that reports "no
frames" and a measurement that failed to record must not be confused.

So this verifies the stream itself: JPEG SOI/EOI markers, frame boundaries, and
the dimensions each SOF header claims. A stream of real frames has all three.
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

    if sizes and boundaries:
        result["verdict"] = f"real frames: {sum(sizes.values())} SOF headers, {boundaries} boundaries"
    elif sizes:
        result["verdict"] = f"JPEG present ({sizes}), but no multipart boundary"
    else:
        result["verdict"] = "SOI present but no SOF header -- truncated or corrupt"
    return result


def main() -> int:
    for path in sys.argv[1:]:
        info = inspect(path)
        print(f"=== {path}")
        for key, value in info.items():
            print(f"  {key:18}: {value}")
    return 0


if __name__ == "__main__":
    sys.exit(main())