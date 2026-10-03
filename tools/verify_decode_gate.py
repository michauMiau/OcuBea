#!/usr/bin/env python3
"""Prove tools/decode_gate.py measures damage instead of counting lines.

Every other RTSP check asks the server whether it is talking to itself: 200 on
DESCRIBE, NAL units present, packets arriving. All of them pass on a stream that
decodes into a vertical smear with a green band, because a NAL with a hole in it
still decodes -- it decodes into a broken frame, and ffprobe still reports
nb_read_frames. That is not hypothetical. A capture in this repo's own history
reported 15 frames and passed a frame-uniqueness check while being visibly broken:
the tool read one interleaved frame per recv() and threw away 212 of 213, because
TCP interleaving does not preserve message boundaries.

So the gate asks a decoder and counts its errors. Its correctness turns on one
number -- errors per frame above which a capture is called damaged -- and an
unmeasured threshold is not a threshold. This measures it instead of asserting it.

How the damage is inflicted took three attempts, and the two failures are why the
final version looks as simple as it does.

  1. Synthetic slices from a repeating byte pattern. Not video. ffmpeg said
     "A non-intra slice in an IDR NAL unit" then "no frame!", so the clean case
     and the damaged case both decoded zero frames and both failed. The gate was
     fine; the input was entropy-coded bytes pretending to be a picture.

  2. Real IDR from a phone capture, with a hole punched inside one frame. The
     clean case passed at 0.00 and the one-hole case read 0.08 to 0.33, under the
     limit, so the test reported a failure of the gate. It was a failure of the
     expectation: one damaged frame out of twelve is 8% of the picture, and the
     limit is drawn where healthy and damaged captures actually separate. Against
     real captures, healthy reads 0.00 to 0.03 over 26 to 36 frames and damaged
     reads 1.59 to 2.14.

  3. Asking ffmpeg for an IDR so the test could run without a phone. It will not
     produce one: -g 1, -qp 0, -force_key_frames, -tune zerolatency and
     all-intra=1 all still yield NAL type 1. Attempts to split the result by type
     then reported a PPS of 8239 bytes, which was this file's parser being wrong
     about where NALs start, not a property of H.264.

So: no IDR required and no NAL parsing at all. ffmpeg encodes a real stream and
bytes are cut out of the finished file at chosen points. That is what a dropped
FU-A fragment looks like from the outside anyway -- a hole in the byte stream --
and it needs no knowledge of where slice boundaries are.

Run: python3 tools/verify_decode_gate.py
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from shutil import which

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

FRAMES = 20


def encode(dest: str) -> bytes:
    """A real H.264 stream from a real encoder, in raw Annex-B."""
    subprocess.run(
        ["ffmpeg", "-v", "error", "-f", "lavfi",
         "-i", "testsrc=size=640x480:rate=10",
         "-frames:v", str(FRAMES), "-c:v", "libx264", "-preset", "ultrafast",
         "-f", "h264", dest],
        capture_output=True, text=True, timeout=400, check=True,
    )
    return open(dest, "rb").read()


def puncture(data: bytes, holes: int, size: int = 200) -> bytes:
    """Remove `size` bytes at `holes` evenly spread points.

    Cut inward from the middle, never from the start, so the parameter sets
    survive. Damaging those makes the decoder produce zero frames, which the gate
    rejects for the wrong reason and which says nothing about whether it can see
    damage in a stream that still decodes.
    """
    out = bytearray(data)
    step = len(out) // (holes + 1)
    for k in range(1, holes + 1):
        at = k * step
        del out[at:at + size]
    return bytes(out)


def main() -> int:
    for tool in ("ffmpeg", "ffprobe"):
        if not which(tool):
            print(f"brak {tool} - bramka dekodowania nie da sie sprawdzic")
            return 1

    import decode_gate

    tmp = tempfile.mkdtemp(prefix="decode-gate-")
    failures: list[str] = []
    cases: list = []

    try:
        clean_path = os.path.join(tmp, "clean.h264")
        clean = encode(clean_path)

        # The clean file has to be genuinely clean first, or every case below is
        # measuring the noise floor instead of the damage.
        ok, rep = decode_gate.passes(clean_path)
        if not ok:
            print(f"  BLAD: plik wygenerowany przez ffmpeg nie przechodzi bramki "
                  f"({rep['frames']} klatek, {rep['errors']} bledow, "
                  f"{rep['per_frame']:.2f}/klatke) - nie da sie na nim nic zmierzyt")
            return 1
        print(f"  wygenerowano czysty strumien: {len(clean)} B, "
              f"{rep['frames']} klatek, {rep['per_frame']:.2f} bledow/klatke")

        cases = [
            ("bez uszkodzen", clean, True),
            ("1 dziura z 20", puncture(clean, 1), True),
            ("3 dziury z 20", puncture(clean, 3), True),
            ("8 dziur z 20", puncture(clean, 8), False),
            ("12 dziur z 20", puncture(clean, 12), False),
            ("16 dziur z 20", puncture(clean, 16), False),
            ("16 dziur po 2000 B", puncture(clean, 16, size=2000), False),
            ("smiec, nie wideo", b"this is not an h264 stream" * 60, False),
        ]

        for label, body, should_pass in cases:
            path = os.path.join(tmp, label.replace(" ", "_")[:40] + ".h264")
            with open(path, "wb") as fh:
                fh.write(body)
            try:
                got, rep = decode_gate.passes(path)
            except Exception as exc:
                print(f"  BLAD: '{label}' wywalil sie: {exc}")
                failures.append(label)
                continue
            good = got == should_pass
            print(f"  {'ok' if good else 'BLAD'}: '{label}' -> "
                  f"{'przeszedl' if got else 'odrzucony'} "
                  f"({rep['frames']} klatek, {rep['errors']} bledow, "
                  f"{rep['per_frame']:.2f}/klatke)"
                  + ("" if good else
                     f", oczekiwano {'przeszedl' if should_pass else 'odrzucony'}"))
            if not good:
                failures.append(label)
    finally:
        for name in os.listdir(tmp):
            os.unlink(os.path.join(tmp, name))
        os.rmdir(tmp)

    print()
    if failures:
        print(f"  BRAK: {len(failures)} z {len(cases)} spraw")
        for f in failures:
            print("   -", f)
        return 1
    print("  brama dekodowania: czyste przechodza, uszkodzone i smiec odrzucone, "
          "wymiar skalowany")
    return 0


if __name__ == "__main__":
    sys.exit(main())
