#!/usr/bin/env python3
"""Decide whether a captured H.264 file is actually decodable, by decoding it.

This is the only check in the repository that asks the question worth asking. Every
other RTSP check asks the server whether it is talking to itself: 200 on DESCRIBE,
200 on SETUP, 200 on PLAY, NAL units present, packets arriving. All of those pass
on a stream that produces a vertical smear with a green band.

The reason is specific and measured here rather than argued. A NAL with a hole in
it still decodes. The capture tool once read one interleaved frame per recv() and
discarded the rest, and TCP interleaving does not preserve message boundaries --
one recv() returned 213 complete frames, so 212 packets were dropped. Dropped FU-A
fragments leave holes, a NAL with a hole decodes into a smear, and ffprobe still
reported nb_read_frames=15. Two of three exported frames were byte-identical and
the third differed, so a uniqueness check passed on it too. nb_read_frames and
frame uniqueness were both green on a visibly broken picture.

What cannot be faked is the decoder's own error output. Each damaged macroblock
row produces "error while decoding MB" and "out of range intra chroma pred mode".
Measured across seventeen captures: healthy files report 0 to 1 error over 26 to
36 frames, damaged ones report 24 to 47 over 13 to 29. The populations do not
touch.

So the gate is errors per frame, not errors, and the line is drawn at 0.5 -- where
the two populations actually separate, rather than at zero, which no file reaches
because even a clean capture carries a stray warning.

A frame that is legitimately dark still passes. Blackness is the room, not the
codec, and a uniqueness check used to fail on exactly that: 1 of 3 unique PNGs from
a correct stream of a dark, static scene.

Usage:
    python3 tools/decode_gate.py capture.h264 [...]
"""

from __future__ import annotations

import glob
import subprocess
import sys

# ffmpeg's wording for a damaged macroblock, plus the parameter-set failures that
# produce a stream which decodes nothing at all. Matched by substring because the
# exact phrasing moves between ffmpeg versions and pinning it would make this fail
# on a toolchain upgrade rather than on a broken capture.
DECODER_ERROR_MARKERS = (
    "error while decoding",
    "out of range",
    "invalid level",
    "decode_slice_header",
    "non-existing PPS",
    "reference overflow",
    "missing picture",
    "no frame",
)

# Where the two populations separate. Healthy: 0.00-0.03. Damaged: 1.59-2.14.
# Anything at 298+ is a file that decoded no frames at all, so it fails on both
# counts and the threshold is not what decides it.
ERRORS_PER_FRAME_LIMIT = 0.5


def decode_report(path: str) -> dict:
    """Decode the file and report what the decoder said. Never raises on a bad
    file -- a corrupt capture is the input this exists for."""
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
         "-show_entries", "stream=codec_name,width,height,nb_read_frames",
         "-of", "default=nw=1", path],
        capture_output=True, text=True, timeout=300,
    )
    frames = 0
    codec = "?"
    for line in probe.stdout.split("\n"):
        if line.startswith("nb_read_frames="):
            try:
                frames = int(line.split("=", 1)[1])
            except ValueError:
                frames = 0
        elif line.startswith("codec_name="):
            codec = line.split("=", 1)[1]

    verify = subprocess.run(
        ["ffmpeg", "-v", "warning", "-i", path, "-f", "null", "-"],
        capture_output=True, text=True, timeout=300,
    )
    errors = [
        line for line in verify.stderr.split("\n")
        if any(k in line for k in DECODER_ERROR_MARKERS)
    ]
    per_frame = len(errors) / max(1, frames)
    return {
        "codec": codec,
        "frames": frames,
        "errors": len(errors),
        "per_frame": per_frame,
        "samples": [e.strip()[:110] for e in errors[:3]],
    }


def passes(path: str) -> tuple[bool, dict]:
    """True only if the file decodes to frames with a plausible error rate."""
    rep = decode_report(path)
    if rep["frames"] <= 0:
        rep["reason"] = "no frames decoded at all"
        return False, rep
    if rep["per_frame"] > ERRORS_PER_FRAME_LIMIT:
        rep["reason"] = (
            f"{rep['errors']} decoder errors over {rep['frames']} frames "
            f"= {rep['per_frame']:.2f} per frame, limit "
            f"{ERRORS_PER_FRAME_LIMIT}"
        )
        return False, rep
    return True, rep


def export_pngs(path: str, count: int = 3) -> list[str]:
    """Export frames for a person to look at. Kept out of the verdict on purpose:
    a dark room produces near-identical frames from a correct stream."""
    stem = path.rsplit(".", 1)[0]
    subprocess.run(
        ["ffmpeg", "-hide_banner", "-v", "error", "-i", path,
         "-frames:v", str(count), "-y", f"{stem}_frame%d.png"],
        capture_output=True, text=True, timeout=300,
    )
    return sorted(glob.glob(stem + "_frame*.png"))


def main() -> int:
    paths = sys.argv[1:]
    if not paths:
        print("uzycie: decode_gate.py <plik.h264> [...]")
        return 2

    worst = 0
    for path in paths:
        ok, rep = passes(path)
        print(f"=== {path}")
        print(f"  codec            : {rep['codec']}")
        print(f"  klatki           : {rep['frames']}")
        print(f"  bledy dekodowania: {rep['errors']}")
        print(f"  na klatke        : {rep['per_frame']:.2f}")
        for sample in rep["samples"]:
            print(f"  ! {sample}")
        if ok:
            pngs = export_pngs(path)
            print(f"  brama dekodowania: OK  ({len(pngs)} PNG do ogladnięcia)")
        else:
            print(f"  BRAK: {rep.get('reason', 'nie przeszla')}")
            worst = 1
    return worst


if __name__ == "__main__":
    sys.exit(main())
