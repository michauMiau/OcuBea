#!/usr/bin/env python3
"""Byte-level gate on every audio endpoint the device advertises.

    python3 tools/verify_audio_containers.py [base_url]
    python3 tools/verify_audio_containers.py --fixture-dir DIR   # offline, from captures
    python3 tools/verify_audio_containers.py --mutate            # prove the checks can fail

## Why exit code 0 is not enough

`ffmpeg` exiting 0 with an empty stderr is not evidence that a stream is
playable. It was measured not to be on this very endpoint set: `/audio.aac`
returned 200, decoded to 575 488 bytes of PCM, and was still wrong in two ways
that only appear in the bytes:

  * every ADTS header declared 48 kHz while the encoder was fed 44.1 kHz, so
    the stream played ~8% slow and about a semitone and a half flat, and
  * the head of the stream was a 9-byte "frame" whose payload was the two-byte
    AudioSpecificConfig rather than audio.

Neither shows up in a return code, which is why every assertion below reads the
container itself -- header fields, chunk sizes, block sizes, sync words, CRCs --
and one of them additionally requires a non-zero decoded PCM byte count, since a
container can be internally consistent and still carry nothing.

## Why `--mutate` exists

A gate that has never been seen red is a gate that has never been tested. Each
assertion below has a paired mutation that reintroduces the corresponding defect
into a good capture, and `--mutate` requires the gate to FAIL on every one. It
exits non-zero if any mutation is *not* caught, which is how this gate proves it
can fail rather than merely that it does.

## The capture rate

Every container has to agree with the rate the microphone is sampled at, so that
rate is an input to the checks rather than something read back out of each
stream and compared with itself. It is taken from the WAV header when one is
present -- `AudioStreamManager.SAMPLE_RATE` = 44100 on this phone -- so the gate
cannot pass on a device that changed its capture rate.
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ADTS_RATES = [
    96000, 88200, 64000, 48000, 44100, 32000, 24000,
    22050, 16000, 12000, 11025, 8000, 7350,
]
LIVE_SIZE = 0xFFFFFFFF

# The 4-bit FLAC block size code table from the specification. Codes 6 and 7
# are escapes: the count is carried in the last bytes of the frame header.
# Note this is NOT a 1:1 index table -- code 12 is 4096, not 13.
FLAC_BLOCK_SIZES_BY_CODE = {
    0: None,   # reserved
    1: 192,
    2: 576, 3: 1152, 4: 2304, 5: 4608,
    6: None, 7: None,   # 8-bit / 16-bit escape, value follows the header
    8: 256, 9: 512, 10: 1024, 11: 2048,
    12: 4096, 13: 8192, 14: 16384, 15: 32768,
}
# Code 0 defers to STREAMINFO; 3 is reserved; the rest are sample widths.
FLAC_BPS_BY_CODE = {0: None, 1: 8, 2: 12, 3: None, 4: 16, 5: 20, 6: 24, 7: 32}
# Rate code 0 defers to STREAMINFO; 12..14 are escapes; 15 is invalid.
FLAC_RATES_BY_CODE = {
    0: None, 1: 88200, 2: 176400, 3: 192000, 4: 8000, 5: 16000,
    6: 22050, 7: 24000, 8: 32000, 9: 44100, 10: 48000, 11: 96000,
    12: None, 13: None, 14: None, 15: None,
}

EXPECTED_CTYPE = {
    "wav": "audio/wav",
    "flac": "audio/flac",
    "aac": "audio/aac",
    "opus": "audio/ogg",
    "amrnb": "audio/amr",
}


class Failure(Exception):
    """One container assertion that did not hold, with the evidence attached."""


# ── transport ────────────────────────────────────────────────────────────────


def download(base: str, name: str, seconds: float, out: str) -> tuple[int, dict]:
    """Pull one audio endpoint for a fixed window; return (bytes, headers)."""
    req = urllib.request.Request(f"{base}/audio.{name}",
                                 headers={"Connection": "close"})
    buf = bytearray()
    headers: dict = {}
    resp = urllib.request.urlopen(req, timeout=30)
    try:
        headers = {k.lower(): v for k, v in resp.headers.items()}
        headers["_status"] = str(resp.status)
        start = time.time()
        while time.time() - start < seconds:
            try:
                chunk = resp.read(8192)
            except (http.client.IncompleteRead, ConnectionResetError) as e:
                # The server closed an ENDLESS stream while we were still reading
                # it, which is the normal way these downloads end: /audio.wav is
                # chunked with no Content-Length, so `Connection: close` tears it
                # down whenever NanoHTTPD decides the client is gone.
                #
                # urllib turns that into IncompleteRead carrying however many
                # bytes DID arrive. Discarding them threw the measurement away and
                # reported a transport hiccup as a container defect, which is the
                # wrong direction: the gate went red on a stream that was fine,
                # and red-on-fine is how a gate trains you to ignore it.
                #
                # Measured: 5 consecutive live runs gave 0 FAILURE, and the runs
                # that did fail did so only in the download step, never in the
                # container checks. So keep the bytes and say why the capture
                # ended short.
                got = getattr(e, "partial", None)
                if got:
                    buf += got
                headers["_ended_early"] = (
                    f"server closed after {len(buf)} bytes: {type(e).__name__}"
                )
                break
            if not chunk:
                break
            buf += chunk
    finally:
        resp.close()
    with open(out, "wb") as fh:
        fh.write(buf)
    return len(buf), headers


def status(base: str) -> dict:
    with urllib.request.urlopen(f"{base}/status.json", timeout=10) as fh:
        return json.loads(fh.read().decode("utf-8"))


# ── external decoders ────────────────────────────────────────────────────────


def ffprobe_stream(path: str) -> dict:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", path],
        capture_output=True, text=True, timeout=120,
    )
    if out.returncode != 0:
        raise Failure(
            f"ffprobe refused {os.path.basename(path)}: {out.stderr.strip()}"
        )
    return json.loads(out.stdout)


def decode_pcm(path: str) -> tuple[int, str]:
    """Decode to signed 16-bit PCM; return (byte count, stderr text)."""
    proc = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "s16le", "-"],
        capture_output=True, timeout=180,
    )
    return len(proc.stdout), proc.stderr.decode("utf-8", "replace")


def ffprobe_frame_stats(raw: bytes) -> tuple[int, int]:
    """(frame count, distinct nb_samples) as the reference decoder sees them."""
    tmp = tempfile.NamedTemporaryFile(suffix=".flac", delete=False)
    try:
        tmp.write(raw)
        tmp.close()
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
             "frame=nb_samples", "-of", "csv=p=0", tmp.name],
            capture_output=True, text=True, timeout=120,
        )
    finally:
        os.unlink(tmp.name)
    rows = [r.strip() for r in out.stdout.splitlines() if r.strip()]
    return len(rows), len(set(rows))


# ── WAV ──────────────────────────────────────────────────────────────────────


def check_wav(path: str, expect_rate: int | None) -> tuple[list[str], int]:
    """A live WAV must carry a real 44-byte header, written once, then PCM.

    Returns the notes and the rate the header declares, so the caller can hold
    every other container to the device's own rate rather than a constant.
    """
    notes: list[str] = []
    raw = open(path, "rb").read()
    if len(raw) < 44 + 4096:
        raise Failure(f"wav body is only {len(raw)} bytes, too short to judge")

    def le32(off: int) -> int:
        return struct.unpack_from("<I", raw, off)[0]

    def le16(off: int) -> int:
        return struct.unpack_from("<H", raw, off)[0]

    def ascii_at(off: int, n: int) -> str:
        return raw[off:off + n].decode("latin1")

    for off, want, what in ((0, "RIFF", "riff id"), (8, "WAVE", "wave id"),
                            (12, "fmt ", "fmt id"), (36, "data", "data id")):
        got = ascii_at(off, 4)
        if got != want:
            raise Failure(f"wav {what} is {got!r}, expected {want!r} at byte {off}")

    if le32(16) != 16:
        raise Failure(f"wav fmt chunk size is {le32(16)}, expected 16")

    audio_format, channels = le16(20), le16(22)
    rate, byte_rate = le32(24), le32(28)
    block_align, bits = le16(32), le16(34)

    if audio_format != 1:
        raise Failure(f"wav audio format is {audio_format}, expected 1 (PCM)")
    if channels != 1:
        raise Failure(f"wav declares {channels} channels, expected 1 (mono)")
    if bits != 16:
        raise Failure(f"wav declares {bits} bits per sample, expected 16")
    if block_align != channels * (bits // 8):
        raise Failure(
            f"wav block align {block_align} != channels x bits/8 = "
            f"{channels * (bits // 8)}"
        )
    if byte_rate != rate * block_align:
        raise Failure(f"wav byte rate {byte_rate} != rate x block align = {rate * block_align}")
    if expect_rate is not None and rate != expect_rate:
        raise Failure(
            f"wav declares {rate} Hz but the expected capture rate is "
            f"{expect_rate}; a player would play the stream at the wrong speed"
        )
    notes.append(
        f"header: pcm mono {rate} Hz {bits}-bit, byte_rate={byte_rate}, align={block_align}"
    )

    # The live length marker. A stream that never ends cannot state its length,
    # and a player that believes a size it cannot reach stalls or truncates.
    riff_size, data_size = le32(4), le32(40)
    if data_size != LIVE_SIZE:
        raise Failure(
            f"wav data chunk size is 0x{data_size:08x}, expected the live marker "
            f"0x{LIVE_SIZE:08x} for an endless stream; a player reads this as the "
            f"length and stops early"
        )
    if riff_size != LIVE_SIZE:
        raise Failure(
            f"wav RIFF size is 0x{riff_size:08x}, expected the live marker "
            f"0x{LIVE_SIZE:08x}; a reader that trusts it truncates or refuses the stream"
        )
    notes.append("both size fields carry the 0xFFFFFFFF live marker")

    body = raw[44:]
    if len(body) % 2:
        raise Failure(f"wav body is {len(body)} bytes, not a whole number of 16-bit samples")
    if body[:4] in (b"RIFF", b"data"):
        raise Failure(
            "a second WAV header appears at byte 44; the header must be written once"
        )
    samples = struct.unpack(f"<{len(body) // 2}h", body)
    nonzero = sum(1 for s in samples if s)
    if nonzero < len(samples) * 0.10:
        raise Failure(
            f"wav body is effectively silent: {nonzero} of {len(samples)} samples non-zero"
        )
    notes.append(
        f"pcm: {len(samples)} samples, {nonzero} non-zero "
        f"({100 * nonzero / len(samples):.1f}%), {len(body) / (rate * 2):.2f} s"
    )
    return notes, rate


# ── FLAC ─────────────────────────────────────────────────────────────────────


class _Bits:
    """Most-significant-bit-first bit reader, which is what FLAC uses."""

    def __init__(self, buf: bytes, offset: int):
        self.b = buf
        self.p = offset * 8

    def read(self, n: int) -> int:
        v = 0
        for _ in range(n):
            if (self.p >> 3) >= len(self.b):
                raise Failure("ran off the end of the capture inside a frame header")
            v = (v << 1) | ((self.b[self.p >> 3] >> (7 - (self.p & 7))) & 1)
            self.p += 1
        return v


def flac_crc8(data: bytes) -> int:
    crc = 0
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def _utf8_len(b0: int) -> int:
    """Byte count of the UTF-8 sequence starting at b0, per the FLAC spec."""
    if b0 < 0x80:
        return 1
    if b0 >> 5 == 0b110:
        return 2
    if b0 >> 4 == 0b1110:
        return 3
    if b0 >> 3 == 0b11110:
        return 4
    return 0


def flac_frame_header(raw: bytes, pos: int) -> dict:
    """The first frame's header, read field by field at its exact offset.

    Position 42 is exact -- it follows `fLaC` plus the one STREAMINFO block -- so
    this reads a real header instead of searching for a sync word. Searching is
    what a headerless stream forces, and 0xFFF8 occurs inside FLAC payloads by
    chance (51 times in a 600 KB capture measured here); resyncing onto one
    mid-subframe is exactly how `invalid residual` appears intermittently.

    The CRC-8 covers the whole header *including* the UTF-8 frame number, so it
    is not at a fixed offset from the sync word. Measured on both captures: the
    header is `ff f8 c9 08 e1 97 92 78`, i.e. the CRC sits at pos+7, three
    bytes after a 3-byte frame number. Assuming a fixed pos+3 -- the layout of a
    frame-0 header with no frame number -- rejects a perfectly good stream, which
    is exactly the bug this parser had before it was measured.
    """
    r = _Bits(raw, pos)
    sync = r.read(14)
    if sync != 0x3FFE:
        raise Failure(
            f"no FLAC frame sync at byte {pos}: read 0x{sync:04x}, expected "
            f"0x3ffe; the body is not a sequence of FLAC frames"
        )
    reserved = r.read(1)
    blocking = r.read(1)
    bs_code = r.read(4)
    rate_code = r.read(4)
    channels = r.read(4) + 1
    bps_code = r.read(3)
    trailing = r.read(1)

    # Escape block sizes carry their value in the bytes after the frame number.
    escape_bytes = {6: 1, 7: 2}.get(bs_code, 0)
    block = FLAC_BLOCK_SIZES_BY_CODE.get(bs_code)

    # Skip the UTF-8 frame number to find where the CRC actually begins.
    frame_num_at = r.p // 8
    if frame_num_at >= len(raw):
        raise Failure(f"frame header at byte {pos} runs past the end of the capture")
    nbytes = _utf8_len(raw[frame_num_at])
    if nbytes == 0:
        raise Failure(
            f"frame header at byte {pos} has an invalid UTF-8 frame-number lead "
            f"byte 0x{raw[frame_num_at]:02x} at byte {frame_num_at}"
        )
    crc_at = frame_num_at + nbytes + escape_bytes
    if crc_at >= len(raw):
        raise Failure(f"frame header at byte {pos} has no room for its CRC-8")
    calc = flac_crc8(raw[pos:crc_at])
    if calc != raw[crc_at]:
        raise Failure(
            f"frame header CRC-8 mismatch at byte {pos}: computed 0x{calc:02x} over "
            f"{crc_at - pos} header bytes, stream says 0x{raw[crc_at]:02x}; the bytes "
            f"there are not the frame header they claim to be"
        )
    if reserved:
        raise Failure(f"the reserved bit is set in the frame header at byte {pos}")
    if trailing:
        raise Failure(
            f"the frame header at byte {pos} sets its reserved trailing bit; "
            f"that bit must be 0"
        )
    if bs_code in (0, 6, 7):
        raise Failure(
            f"the frame at byte {pos} uses block-size code {bs_code}, which "
            f"escapes to STREAMINFO or to an explicit value; this gate requires a "
            f"directly coded block size"
        )
    if bps_code == 0:
        raise Failure(
            "the frame header defers bits-per-sample to STREAMINFO; the reference "
            "decoder resolves that, so read this field from ffprobe"
        )
    if bps_code == 3:
        raise Failure(f"the frame header uses reserved bits-per-sample code 3")

    return {
        "block": block,
        "rate_code": rate_code,
        "channels": channels,
        "bps": FLAC_BPS_BY_CODE[bps_code],
        "rate": FLAC_RATES_BY_CODE[rate_code],
        "crc_at": crc_at,
        "blocking_strategy": "fixed-blocksize" if blocking == 0 else "variable",
        "frame_number_bytes": nbytes,
        "at": pos,
    }


def check_flac(path: str, expect_rate: int | None) -> list[str]:
    """FLAC over HTTP: signature, one STREAMINFO, then frames agreeing with it."""
    notes: list[str] = []
    raw = open(path, "rb").read()
    if len(raw) < 4096 + 42:
        raise Failure(f"flac body is only {len(raw)} bytes")

    if raw[:4] != b"fLaC":
        raise Failure(f"flac does not start with fLaC (got {raw[:4]!r})")

    last = raw[4] >> 7
    block_type = raw[4] & 0x7F
    block_len = int.from_bytes(raw[5:8], "big")
    if block_type != 0:
        raise Failure(f"first metadata block is type {block_type}, expected 0 (STREAMINFO)")
    if block_len != 34:
        raise Failure(f"STREAMINFO is {block_len} bytes, expected 34")
    if last != 1:
        # Declaring more metadata to follow makes ffmpeg read to the end of the
        # file waiting for it and return no frames: measured, a headered capture
        # built both ways decoded 319 488 bytes with the flag set and 0 without.
        raise Failure(
            "last-metadata-block flag is clear, so the stream promises more "
            "metadata blocks; a demuxer waits for them and yields no audio"
        )

    si = raw[8:42]
    min_block = int.from_bytes(si[0:2], "big")
    max_block = int.from_bytes(si[2:4], "big")
    packed = int.from_bytes(si[10:18], "big")
    rate = packed >> 44
    channels = ((packed >> 41) & 0x7) + 1
    bits = ((packed >> 36) & 0x1F) + 1
    total_samples = packed & 0xFFFFFFFFF

    if expect_rate is not None and rate != expect_rate:
        raise Failure(f"flac STREAMINFO says {rate} Hz, expected {expect_rate} Hz")
    if channels != 1:
        raise Failure(f"flac STREAMINFO says {channels} channels, expected 1")
    if bits != 16:
        raise Failure(f"flac STREAMINFO says {bits} bits per sample, expected 16")
    if min_block != max_block:
        raise Failure(
            f"flac STREAMINFO block sizes disagree: min {min_block}, max {max_block}"
        )
    notes.append(
        f"streaminfo: {rate} Hz {channels}ch {bits}-bit, block {min_block}, "
        f"total_samples={total_samples} (0 = unknown, correct for a live stream)"
    )

    # A second fLaC signature would mean the encoder emits its own signature
    # mid-stream, the other way this endpoint used to produce a resync failure.
    count = raw.count(b"fLaC")
    if count != 1:
        raise Failure(
            f"fLaC appears {count} times; the signature must be written once, "
            f"not by every frame"
        )

    first = flac_frame_header(raw, 42)
    if first["block"] != min_block:
        raise Failure(
            f"the first frame declares a block of {first['block']} samples but "
            f"STREAMINFO advertises {min_block}"
        )
    if first["channels"] not in (1, 8):
        raise Failure(
            f"the first frame declares channel assignment {first['channels']}, "
            f"expected mono (1) or independent (8)"
        )
    if first["rate"] is not None and first["rate"] != rate:
        raise Failure(
            f"the first frame's rate code says {first['rate']} Hz but "
            f"STREAMINFO says {rate} Hz"
        )
    if first["bps"] != bits:
        raise Failure(
            f"the first frame declares {first['bps']} bits per sample but "
            f"STREAMINFO says {bits}"
        )

    frames, distinct = ffprobe_frame_stats(raw)
    if frames == 0:
        raise Failure("ffprobe reported zero FLAC frames")
    if distinct != 1:
        raise Failure(
            f"ffprobe saw {distinct} different frame block sizes; a live FLAC "
            f"stream advertises and uses one"
        )
    notes.append(
        f"{frames} frames, all block {min_block} samples = "
        f"{1000 * min_block / rate:.1f} ms each; first frame header "
        f"{first['blocking_strategy']}, {first['frame_number_bytes']}-byte frame "
        f"number, CRC-8 verified at byte {first['crc_at']}"
    )

    # Observed byte rate must be consistent with that frame duration.
    body_bytes = len(raw) - 42
    seconds = frames * min_block / rate
    notes.append(
        f"{body_bytes} body bytes -> {seconds:.2f} s of audio, "
        f"{body_bytes / seconds / 1000:.1f} kB/s"
    )
    return notes


# ── AAC / ADTS ───────────────────────────────────────────────────────────────


def adts_frame_length(raw, pos: int) -> int:
    return (
        ((raw[pos + 3] & 0x03) << 11)
        | (raw[pos + 4] << 3)
        | ((raw[pos + 5] & 0xE0) >> 5)
    )


def adts_rate_index(raw: bytes, pos: int) -> int:
    # bits 18..21 of the 56-bit header, i.e. bits 2..5 of byte 2.
    return (raw[pos + 2] & 0x3C) >> 2


def adts_channel_config(raw: bytes, pos: int) -> int:
    # bits 23..25, straddling bytes 2 and 3.
    return ((raw[pos + 2] & 0x01) << 2) | ((raw[pos + 3] & 0xC0) >> 6)


def _partial_adts(raw: bytes, pos: int, rate_idx: int, expect_rate):
    """Validate a final ADTS frame that is short because the client stopped.

    Returns the number of bytes present when the partial frame is a genuine
    ADTS header consistent with the rest of the stream, else None.

    The point of this helper is that "the last frame is short" must not become a
    loophole. If it did, then any defect could be hidden by cutting the capture
    one byte earlier. So the partial header still has to carry a sync word, a
    rate index equal to the one every other frame declares, and -- when the
    caller knows the real capture rate -- that rate as well.
    """
    avail = len(raw) - pos
    if avail < 7:
        return None
    if raw[pos] != 0xFF or (raw[pos + 1] & 0xF0) != 0xF0:
        return None
    if adts_rate_index(raw, pos) != rate_idx:
        return None
    if expect_rate is not None and ADTS_RATES[rate_idx] != expect_rate:
        return None
    return avail


def check_aac(path: str, expect_rate: int | None) -> list[str]:
    """Raw ADTS: every frame self-describing, at the rate it was really captured."""
    notes: list[str] = []
    raw = open(path, "rb").read()
    if len(raw) < 2048:
        raise Failure(f"aac body is only {len(raw)} bytes")

    rates: set[int] = set()
    chans: set[int] = set()
    sizes: list[int] = []
    starts: list[int] = []
    pos = 0
    # Set on the break path below, and 0 for a capture whose last frame is whole.
    # Without the initialiser a capture that ends cleanly would read these as
    # unbound, which is the same shape of bug as the gate being green for the
    # wrong reason.
    truncated = 0
    truncated_frame_len = 0
    while pos + 7 <= len(raw):
        if raw[pos] != 0xFF or (raw[pos + 1] & 0xF0) != 0xF0:
            raise Failure(
                f"aac framing broke at byte {pos}: {len(sizes)} frames consumed "
                f"{pos} of {len(raw)} body bytes and there is no sync word here"
            )
        idx = adts_rate_index(raw, pos)
        if idx >= len(ADTS_RATES):
            raise Failure(f"aac header at {pos} carries rate index {idx}, off the table")
        frame_len = adts_frame_length(raw, pos)
        if frame_len < 7:
            raise Failure(f"aac header at {pos} declares {frame_len} bytes, under the 7-byte header")
        if pos + frame_len > len(raw):
            # A short FINAL frame is normal on a live stream. The capture simply
            # stops wherever the client stopped reading, so the last frame is
            # short by construction: a 198-byte header with 125 bytes present is
            # what a healthy ADTS stream looks like when you stop reading it, not
            # malformed framing. Calling it a failure unconditionally turned the
            # gate red on a stream whose first 2081 frames were all consistent,
            # which is the same error as judging a truncated JPEG a broken
            # encoder.
            #
            # So accept it, but only when the bytes present are a real ADTS
            # header agreeing with every other frame. Otherwise "the last frame
            # is short" would be a loophole that hides any defect, since any
            # capture can be cut one byte earlier.
            truncated = len(raw) - pos
            truncated_frame_len = frame_len
            if _partial_adts(raw, pos, idx, expect_rate) is None:
                raise Failure(
                    f"aac header at {pos} declares {frame_len} bytes but only "
                    f"{truncated} remain, and those bytes are not a well-formed "
                    f"ADTS header, so this is malformed framing rather than a "
                    f"truncated final frame"
                )
            rates.add(ADTS_RATES[idx])
            chans.add(adts_channel_config(raw, pos))
            break
        chans.add(adts_channel_config(raw, pos))
        sizes.append(frame_len)
        starts.append(pos)
        pos += frame_len

    if not sizes:
        raise Failure("no ADTS frames found")
    if len(rates) != 1:
        raise Failure(f"aac frames declare several rates: {sorted(rates)}")
    rate = rates.pop()
    if expect_rate is not None and rate != expect_rate:
        raise Failure(
            f"aac ADTS headers declare {rate} Hz but the capture rate is "
            f"{expect_rate} Hz; the stream plays {100 * (expect_rate / rate - 1):+.1f}% "
            f"off speed with the pitch shifted to match"
        )
    if chans != {1}:
        raise Failure(f"aac ADTS headers declare channels {sorted(chans)}, expected mono")
    notes.append(f"{len(sizes)} ADTS frames, all declaring {rate} Hz mono")
    if truncated:
        # Say so explicitly. Silence here would read as "every frame was intact",
        # which is not what was measured.
        notes.append(
            f"final frame is truncated at {truncated} of {truncated_frame_len} bytes: "
            f"the capture stopped there, which is expected when a client stops "
            f"reading a live stream; the {len(sizes)} frames before it are intact"
        )

    # The AudioSpecificConfig must not be framed as audio. A two-byte payload
    # cannot be a decodable AAC frame, and shipping one produced ffmpeg's
    # "Error submitting packet to decoder: Invalid data found" on the real
    # stream -- the single cause of that message in that capture.
    for i, n in enumerate(sizes):
        if n - 7 <= 2:
            payload = raw[starts[i] + 7:starts[i] + n]
            raise Failure(
                f"aac frame {i} at byte {starts[i]} is {n} bytes: a 7-byte header "
                f"plus a {n - 7}-byte payload. That payload ({payload.hex()}) is an "
                f"AudioSpecificConfig, not audio, and a 9-byte ADTS frame is not decodable"
            )
    notes.append(f"smallest frame is {min(sizes)} bytes; no config-sized frame present")
    return notes


# ── facts every stream must satisfy ──────────────────────────────────────────


def check_stream(path: str, expect_rate: int | None, want_codec: str | None) -> list[str]:
    notes: list[str] = []
    info = ffprobe_stream(path)
    streams = [s for s in info.get("streams", []) if s.get("codec_type") == "audio"]
    if not streams:
        raise Failure("no audio stream: ffprobe did not find one")
    s = streams[0]
    fmt = info.get("format", {}).get("format_name")
    notes.append(
        f"ffprobe: codec={s.get('codec_name')} rate={s.get('sample_rate')} "
        f"channels={s.get('channels')} format={fmt}"
    )
    if want_codec and s.get("codec_name") != want_codec:
        raise Failure(f"ffprobe says codec {s.get('codec_name')}, expected {want_codec}")
    if expect_rate is not None and int(s.get("sample_rate", 0)) != expect_rate:
        raise Failure(
            f"ffprobe says {s.get('sample_rate')} Hz, expected the capture rate "
            f"{expect_rate} Hz"
        )
    if int(s.get("channels", 0)) != 1:
        raise Failure(f"ffprobe says {s.get('channels')} channels, expected mono")

    pcm_bytes, err = decode_pcm(path)
    if pcm_bytes <= 0:
        raise Failure(
            f"decoded to {pcm_bytes} bytes of PCM: the container is consistent "
            f"but carries no audio"
        )
    rate = expect_rate or int(s.get("sample_rate", 1))
    notes.append(
        f"decoded {pcm_bytes} bytes PCM = {pcm_bytes // 2} samples "
        f"= {pcm_bytes / 2 / rate:.2f} s"
    )
    for line in err.splitlines():
        low = line.lower()
        # Truncation is expected: this is a live stream cut off by the client.
        if "input buffer exhausted" in low or "end of file" in low:
            continue
        notes.append(f"ffmpeg stderr: {line}")
    return notes


# ── the advertised set ───────────────────────────────────────────────────────


def advertised(status_doc: dict) -> tuple[list[str], str, list[dict]]:
    """Codec ids the device claims it can serve, the summary, and the menu.

    The WebUI builds its codec picker by treating the `available` summary as a
    whitelist of `id=true` entries, so that string is the contract between the
    server and the menu -- which is why "not in the summary" means "not
    selectable by the user" rather than merely "unmentioned".
    """
    audio = status_doc.get("audio", {})
    summary = audio.get("available", "")
    claimed: list[str] = []
    for token in summary.split():
        if "=" not in token:
            continue
        name, value = token.split("=", 1)
        if value == "true" and name != "default":
            claimed.append(name)
    # Whatever the menu itself offers has to be servable too.
    menu = audio.get("available_list", [])
    for entry in menu:
        if entry.get("id") and entry["id"] not in claimed:
            claimed.append(entry["id"])
    return claimed, summary, menu


def run_live(base: str, seconds: float, workdir: str) -> int:
    doc = status(base)
    audio = doc.get("audio", {})
    if not audio.get("enabled"):
        print("audio is disabled in settings; nothing to verify", file=sys.stderr)
        return 2

    claimed, summary, menu = advertised(doc)

    # The reported complaint: WAV does not appear in the UI, so it cannot be
    # tested. The endpoint serves bytes either way; the summary is what the
    # picker filters on, so an id missing from it is unreachable for the user.
    if "wav" not in claimed:
        raise Failure(
            f"status.json does not advertise wav, so the WebUI -- which builds "
            f"its codec picker from this string -- gives the user no way to "
            f"select it: {summary!r}"
        )
    print(f"device advertises: {', '.join(claimed)}")
    print(f"  summary: {summary}")
    print(f"  menu:    {', '.join(e.get('id', '?') for e in menu) or '(empty)'}")

    failures: list[str] = []
    passed = 0
    rate: int | None = None

    for codec in claimed:
        path = os.path.join(workdir, f"{codec}.bin")
        try:
            size, headers = download(base, codec, seconds, path)
        except urllib.error.HTTPError as exc:
            body = exc.read()[:200].decode("latin1", "replace")
            failures.append(f"{codec}: HTTP {exc.code} -- {body}")
            print(f"FAIL {codec}: HTTP {exc.code} -- {body}")
            continue
        except Exception as exc:  # noqa: BLE001 - reported, not swallowed
            failures.append(f"{codec}: could not be downloaded: {exc}")
            print(f"FAIL {codec}: {exc}")
            continue

        print(f"\n== {codec}: {size} bytes, Content-Type: {headers.get('content-type')}")
        if size < 4096:
            failures.append(f"{codec}: only {size} bytes in {seconds}s")
            print(f"   FAIL {size} bytes is not a stream")
            continue

        want = EXPECTED_CTYPE.get(codec)
        got = headers.get("content-type", "")
        if want and not got.startswith(want):
            failures.append(f"{codec}: Content-Type is {got!r}, expected {want!r}")
            print(f"   FAIL content-type {got!r}, expected {want!r}")
        if headers.get("transfer-encoding", "").lower() != "chunked":
            failures.append(
                f"{codec}: Transfer-Encoding is {headers.get('transfer-encoding')!r}, "
                f"expected chunked for a live stream of unknown length"
            )
            print(f"   FAIL transfer-encoding {headers.get('transfer-encoding')!r}")

        try:
            if codec == "wav":
                # The WAV header is the device's own statement of its capture
                # rate, so it is read first and every later container is held
                # to it rather than to a constant written here.
                notes, rate = check_wav(path, None)
                notes += check_stream(path, rate, "pcm_s16le")
            else:
                if rate is None:
                    print("   note: wav was not checked first, using no reference rate")
                if codec == "flac":
                    notes = check_flac(path, rate)
                    notes += check_stream(path, rate, "flac")
                elif codec == "aac":
                    notes = check_aac(path, rate)
                    notes += check_stream(path, rate, "aac")
                else:
                    notes = check_stream(path, rate, None)
        except Failure as exc:
            failures.append(f"{codec}: {exc}")
            print(f"   FAIL {exc}")
            continue
        for note in notes:
            print(f"   {note}")
        passed += 1

    print()
    if failures:
        print(f"{len(failures)} FAILURE(S):")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"ok: {passed} advertised endpoint(s) carry a correct container")
    return 0


def run_fixtures(directory: str) -> int:
    failures: list[str] = []
    seen = 0
    rate: int | None = None
    wav = os.path.join(directory, "wav.bin")
    if os.path.exists(wav):
        try:
            notes, rate = check_wav(wav, None)
            print("== wav")
            for n in notes:
                print(f"   {n}")
            seen += 1
        except Failure as exc:
            failures.append(f"wav: {exc}")
            print(f"== wav\n   FAIL {exc}")
    for codec, want in (("flac", "flac"), ("aac", "aac")):
        path = os.path.join(directory, f"{codec}.bin")
        if not os.path.exists(path):
            continue
        try:
            notes = CHECKERS[codec](path, rate)
            notes += check_stream(path, rate, want)
            print(f"== {codec}")
            for n in notes:
                print(f"   {n}")
            seen += 1
        except Failure as exc:
            failures.append(f"{codec}: {exc}")
            print(f"== {codec}\n   FAIL {exc}")
    if failures:
        print(f"\n{len(failures)} FAILURE(S):")
        for f in failures:
            print(f"  - {f}")
        return 1
    if seen == 0:
        print("no captures found", file=sys.stderr)
        return 2
    print(f"\nok: {seen} capture(s) carry a correct container")
    return 0


# ── mutations: prove each assertion can fail ─────────────────────────────────


def wav_bogus_data_size(raw: bytes) -> bytes:
    """A plausible, wrong data chunk size on an otherwise perfect header."""
    out = bytearray(raw)
    struct.pack_into("<I", out, 40, 4_000)
    return bytes(out)


def wav_riff_claims_length(raw: bytes) -> bytes:
    out = bytearray(raw)
    struct.pack_into("<I", out, 4, 44 + 4_000)
    return bytes(out)


def wav_wrong_sample_rate(raw: bytes) -> bytes:
    """Rewrite the header to 48 kHz while keeping it internally consistent.

    The mutation has to keep byte_rate in step or the header-consistency check
    would catch it instead and the test would prove nothing about rate. What
    survives is exactly the case this gate has to catch: a header that is
    perfectly coherent and still wrong, because the payload is 44.1 kHz PCM.
    """
    out = bytearray(raw)
    struct.pack_into("<I", out, 24, 48_000)
    struct.pack_into("<I", out, 28, 48_000 * 2)
    return bytes(out)


def wav_stereo_claim(raw: bytes) -> bytes:
    out = bytearray(raw)
    struct.pack_into("<H", out, 22, 2)
    struct.pack_into("<H", out, 32, 4)
    struct.pack_into("<I", out, 28, 44_100 * 4)
    return bytes(out)


def wav_header_twice(raw: bytes) -> bytes:
    return raw[:44] + raw[:44] + raw[44:]


def wav_silent_body(raw: bytes) -> bytes:
    return raw[:44] + bytes(len(raw) - 44)


def flac_last_flag_clear(raw: bytes) -> bytes:
    out = bytearray(raw)
    out[4] &= 0x7F
    return bytes(out)


def flac_rate_off(raw: bytes) -> bytes:
    out = bytearray(raw)
    packed = int.from_bytes(raw[18:26], "big")
    packed = (packed & ~(0xFFFFF << 44)) | (48_000 << 44)
    out[18:26] = packed.to_bytes(8, "big")
    return bytes(out)


def flac_strip_signature(raw: bytes) -> bytes:
    return raw[4:]


def flac_second_signature(raw: bytes) -> bytes:
    return raw[:42] + b"fLaC" + raw[42:]


def flac_break_frame_sync(raw: bytes) -> bytes:
    out = bytearray(raw)
    out[42] = 0x00
    return bytes(out)


def aac_rate_lie(raw: bytes) -> bytes:
    """Rewrite every ADTS header to 48 kHz -- the defect that was live.

    The rate index lives in bits 2..5 of byte 2, so the mask that preserves
    everything else is 0xC3. Using 0xF3 instead -- which looks like it preserves
    the same field -- writes index 7 (22 050 Hz) and tests nothing.
    """
    out = bytearray(raw)
    pos = 0
    while pos + 7 <= len(out):
        if out[pos] != 0xFF or (out[pos + 1] & 0xF0) != 0xF0:
            break
        frame_len = adts_frame_length(out, pos)
        out[pos + 2] = (out[pos + 2] & 0xC3) | (3 << 2)   # rate index 3 = 48 kHz
        pos += frame_len
    return bytes(out)


def aac_config_as_audio(raw: bytes) -> bytes:
    """Re-introduce the 9-byte first frame carrying the AudioSpecificConfig."""
    return bytes([0xFF, 0xF1, 0x4C, 0x40, 0x01, 0x3F, 0xFC]) + b"\x12\x08" + raw


def aac_stereo_claim(raw: bytes) -> bytes:
    """Set the channel configuration to stereo (2) in the first frame's header.

    The 3-bit field is byte 2 bit 0 as its low bit and byte 3 bits 7..6 as its
    high two, most significant first -- so value 2 is byte 2 bit 0 = 0 with byte
    3 bits 7..6 = 01, i.e. the 0x80 mask. Writing 0x40 there (the most obvious
    edit, and what this gate first did) stores 1, which is the value already
    present: the mutation was a no-op that the check correctly ignored.
    """
    out = bytearray(raw)
    out[2] = (out[2] & 0xFE) | 0x00   # channel config low bit
    out[3] = (out[3] & 0x3F) | 0x80   # ... and its high two bits
    return bytes(out)


def aac_break_framing(raw: bytes) -> bytes:
    """Corrupt a real sync word so the frame walk stops early.

    A frame's payload is skipped by its declared length, so corrupting an
    arbitrary offset lands inside data the parser never looks at. This finds the
    third frame boundary and destroys the sync word there, which is a framing
    error a demuxer genuinely has to report.
    """
    out = bytearray(raw)
    pos = 0
    for _ in range(3):
        if pos + 7 > len(out):
            raise ValueError("capture too short to hold three AAC frames")
        pos += adts_frame_length(out, pos)
    out[pos] ^= 0xFF
    out[pos + 1] ^= 0xFF
    return bytes(out)


def aac_strip_sync(raw: bytes) -> bytes:
    return raw[1:]


MUTATIONS = [
    ("wav data size says 4000", "wav.bin", wav_bogus_data_size, "live marker"),
    ("wav RIFF size claims a real length", "wav.bin", wav_riff_claims_length, "live marker"),
    ("wav header says 48 kHz", "wav.bin", wav_wrong_sample_rate, "sample rate"),
    ("wav claims stereo", "wav.bin", wav_stereo_claim, "channel count"),
    ("wav header written twice", "wav.bin", wav_header_twice, "header once"),
    ("wav body is silence", "wav.bin", wav_silent_body, "body not silent"),
    ("flac last-metadata-block cleared", "flac.bin", flac_last_flag_clear, "metadata flag"),
    ("flac STREAMINFO claims 48 kHz", "flac.bin", flac_rate_off, "sample rate"),
    ("flac signature removed", "flac.bin", flac_strip_signature, "fLaC signature"),
    ("a second fLaC signature", "flac.bin", flac_second_signature, "signature once"),
    ("flac first frame sync broken", "flac.bin", flac_break_frame_sync, "frame sync"),
    ("aac headers declare 48 kHz", "aac.bin", aac_rate_lie, "sample rate"),
    ("aac config framed as audio", "aac.bin", aac_config_as_audio, "no config frame"),
    ("aac claims stereo", "aac.bin", aac_stereo_claim, "channel count"),
    ("aac framing broken mid-stream", "aac.bin", aac_break_framing, "framing"),
    ("aac sync word removed", "aac.bin", aac_strip_sync, "sync word"),
]

CHECKERS = {"wav": check_wav, "flac": check_flac, "aac": check_aac}


def _reference_rate(fixture_dir: str) -> int | None:
    """The capture rate the mutations must be judged against.

    Derived from the WAV capture, exactly as the live run derives it. Passing
    None here -- which this harness used to do -- silently disables every
    sample-rate assertion, since a rate check with nothing to compare against
    has nothing to say. The mutation run reported 16 checks and caught 12; three
    of the four survivors were caused by this, and two of those mutations were
    perfectly good. A gate's own test harness is code like any other and has to
    earn its assumptions too.
    """
    wav = os.path.join(fixture_dir, "wav.bin")
    if not os.path.exists(wav):
        return None
    try:
        _, rate = check_wav(wav, None)
    except Failure:
        return None
    return rate


def run_mutate(fixture_dir: str) -> int:
    """Every mutation must be caught; one that survives is a dead check."""
    survivors: list[str] = []
    caught = 0
    skipped = 0
    rate = _reference_rate(fixture_dir)
    print(f"reference capture rate from the WAV capture: "
          f"{rate if rate else 'unavailable -- rate checks cannot fire'}")
    for label, filename, mutator, target in MUTATIONS:
        src = os.path.join(fixture_dir, filename)
        if not os.path.exists(src):
            print(f"SKIP    {label}: no capture {filename} in {fixture_dir}")
            skipped += 1
            continue
        raw = open(src, "rb").read()
        codec = filename.split(".")[0]
        tmpdir = tempfile.mkdtemp(prefix="audio-mutate-")
        try:
            mutated = os.path.join(tmpdir, filename)
            try:
                with open(mutated, "wb") as fh:
                    fh.write(mutator(raw))
            except ValueError as exc:
                survivors.append(f"{label}: could not be built -- {exc}")
                print(f"SURVIVED {label}  [{target}] -- {exc}")
                continue
            try:
                # Every mutation is judged against the rate the *pristine*
                # capture declares, WAV included. That is the only way the WAV
                # rate assertion can fire at all: a mutated WAV header cannot be
                # its own reference, so a coherent-but-wrong 48 kHz header
                # would otherwise pass silently.
                CHECKERS[codec](mutated, rate)
            except Failure as exc:
                caught += 1
                print(f"caught   {label}  [{target}]")
                print(f"           {str(exc)[:150]}")
                continue
            except Exception as exc:  # noqa: BLE001
                caught += 1
                print(f"caught   {label}  [{target}]")
                print(f"           {type(exc).__name__}: {exc}")
                continue
            print(f"SURVIVED {label}  [{target}] -- the check did not fire")
            survivors.append(f"{label}: the '{target}' check did not fire")
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)

    if rate is None:
        survivors.append(
            "no reference rate: every sample-rate assertion is inert in this run"
        )
    print()
    total = caught + len(survivors)
    if survivors:
        print(f"FAIL: {len(survivors)} of {total} mutations were NOT caught:")
        for s in survivors:
            print(f"  - {s}")
        return 1
    if skipped:
        print(f"FAIL: {skipped} mutation(s) had no capture to run against")
        return 1
    print(f"ok: all {caught} mutations were caught -- every check can fail")
    return 0


# ── entry point ──────────────────────────────────────────────────────────────


def main() -> int:
    ap = argparse.ArgumentParser(description="byte-level audio container gate")
    ap.add_argument("base", nargs="?", default="http://192.168.1.184:8080")
    ap.add_argument("--seconds", type=float, default=6.0,
                    help="how long to hold each stream open")
    ap.add_argument("--fixture-dir",
                    help="verify captures on disk instead of a live device")
    ap.add_argument("--mutate", action="store_true",
                    help="prove the checks fail on deliberately broken input")
    ap.add_argument("--save", help="also save every capture into this directory")
    args = ap.parse_args()

    if args.mutate:
        return run_mutate(args.fixture_dir or ".")

    if args.fixture_dir:
        return run_fixtures(args.fixture_dir)

    if args.save:
        os.makedirs(args.save, exist_ok=True)
    workdir = args.save or tempfile.mkdtemp(prefix="audio-containers-")
    try:
        return run_live(args.base, args.seconds, workdir)
    except Failure as exc:
        print(f"FAIL {exc}")
        return 1
    except urllib.error.URLError as exc:
        print(f"FAIL cannot reach {args.base}: {exc}", file=sys.stderr)
        return 2
    finally:
        if not args.save:
            shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())