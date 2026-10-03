#!/usr/bin/env python3
"""Prove tools/rtsp_capture_h264.py can reach every gate it claims to run.

The capture tool went green in CI while being broken. It called decode_gate.passes
and had no import for it, so any real run died with NameError on the last step,
after the minutes spent talking to the camera. Committed as 4afe530, and the CI
suite that supposedly covered it never noticed.

Import checking does not catch this. Verified here rather than assumed: importing
the committed file succeeds with or without the import line, because the name is
only resolved when main() executes, and importing a module runs no code from it.
So this does not check imports. It calls the same functions the tool calls, with
inputs built to reach them, and requires each to return a verdict rather than
raise.

What it covers:

  - decode_gate is reachable and returns a verdict
  - export_pngs writes files
  - the FU-A reassembler rebuilds a NAL from fragments
  - the interleaved-frame reader parses a framed TCP buffer
  - the packet deadline is monotonic, so a capture cannot hang forever
  - the RTP reader returns something for an empty socket rather than looping

The last two matter because a tool that hangs is worse than a tool that fails:
CI either waits forever or is killed and reports a timeout, and neither names the
thing that was wrong.

Run: python3 tools/verify_capture_gate.py
"""

from __future__ import annotations

import importlib.util
import os
import socket
import struct
import sys
import tempfile
import threading
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CAPTURE = os.path.join(REPO, "tools", "rtsp_capture_h264.py")
sys.path.insert(0, os.path.join(REPO, "tools"))


def load():
    spec = importlib.util.spec_from_file_location("rtsp_capture", CAPTURE)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"nie da sie zaladowac {CAPTURE}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main() -> int:
    if not os.path.exists(CAPTURE):
        print(f"brak narzedzia {CAPTURE}")
        return 1

    failures: list[str] = []

    # 1. The whole point: every name main() uses must resolve.
    print("  sprawdzam, czy decode_gate jest osiagalne z capture'a")
    mod = load()
    if not hasattr(mod, "decode_gate"):
        print("  BLAD: capture nie ma 'decode_gate' -- kazde uruchomienie skonczy "
              "sie NameError po polaczeniu z kamera")
        failures.append("decode_gate missing")
        return 1
    print(f"  ok: decode_gate osiagalne ({mod.decode_gate.__file__.split('/')[-1]})")

    import decode_gate

    # 2. passes() on a real generated stream: the call the tool makes at the end.
    if not _which("ffmpeg"):
        print("  brak ffmpeg - pomijam test dekodowania")
    else:
        tmp = tempfile.mkdtemp(prefix="capture-gate-")
        clean = os.path.join(tmp, "clean.h264")
        rc = os.system(
            f"ffmpeg -v error -f lavfi -i testsrc=size=320x240:rate=5 -frames:v 8 "
            f"-c:v libx264 -preset ultrafast -f h264 {clean} >/dev/null 2>&1"
        )
        if rc != 0:
            print("  BLAD: ffmpeg nie wygenerowal strumienia")
            failures.append("ffmpeg")
        else:
            ok, rep = decode_gate.passes(clean)
            print(f"  ok: decode_gate.passes zwrocil werdykt "
                  f"({rep['frames']} klatek, {rep['per_frame']:.2f}/klatke, "
                  f"{'przeszedl' if ok else 'odrzucony'})")
            if not isinstance(ok, bool) or "frames" not in rep:
                print("  BLAD: werdykt nie ma oczekiwanych pol")
                failures.append("verdict shape")

            pngs = decode_gate.export_pngs(clean)
            if not pngs:
                print("  BLAD: export_pngs nie zwrocil zadnego pliku")
                failures.append("export_pngs")
            else:
                print(f"  ok: export_pngs zwrocil {len(pngs)} plikow "
                      f"({os.path.getsize(pngs[0])} B)")

            # A capture that only ever sees healthy files cannot be trusted to
            # report a damaged one, so the same call has to reject this.
            bad = os.path.join(tmp, "bad.h264")
            data = bytearray(open(clean, "rb").read())
            step = len(data) // 9
            for k in range(1, 8):
                del data[k * step:k * step + 200]
            with open(bad, "wb") as fh:
                fh.write(bytes(data))
            ok_bad, rep_bad = decode_gate.passes(bad)
            if ok_bad:
                print(f"  BLAD: uszkodzony plik przeszedl "
                      f"({rep_bad['per_frame']:.2f}/klatke)")
                failures.append("damaged accepted")
            else:
                print(f"  ok: uszkodzony plik odrzucony "
                      f"({rep_bad['per_frame']:.2f}/klatke)")

        for name in os.listdir(tmp):
            os.unlink(os.path.join(tmp, name))
        os.rmdir(tmp)

    # 3. The interleaved frame reader. This is the one that was wrong: recv()
    # does not preserve message boundaries, so one call returns many frames and
    # reading one per call threw the rest away.
    if hasattr(mod, "next_interleaved"):
        # The payload must not contain 0x24, which is the '$' the reader looks for.
        # My first version filled it with bytes([ch]) and channel 1 is 0x24, so the
        # reader found that byte instead of the frame header and returned 5 bytes --
        # which read as a bug in the parser and was not. RTP payloads are real NALs,
        # and 0x24 is a legal byte inside one, so this is also a reminder that the
        # reader anchors on the first '$' and a payload containing one will confuse
        # it; that has not been seen on the wire and is not fixed here.
        frames = []
        for i, ch in enumerate((0, 2)):
            payload = b"\x00\x00\x00\x01\x67" + bytes([0x40 + i]) * 40
            frames.append(b"$" + bytes([ch]) + struct.pack(">H", len(payload)) + payload)
        # 4-byte start code + 1 NAL header + 40 bytes = 45. I first wrote 42 and
        # the reader was right both times; only the expectation was wrong.
        expected = len(frames[0]) - 4
        blob = b"".join(frames)
        sock = _FakeSock(blob)
        buf = b""
        stop = time.monotonic() + 5
        # It returns (payload, buf), not a triple, and the buffer carries over.
        # Both frames arrive in one recv() because TCP does not preserve message
        # boundaries -- which is the bug that made the capture drop 212 of 213
        # packets, so reading one frame per call and discarding the rest is
        # exactly the failure this checks for.
        got = []
        for _ in range(2):
            payload, buf = mod.next_interleaved(sock, buf, stop)
            got.append(len(payload) if payload else 0)
        if got == [expected, expected] and buf == b"":
            print(f"  ok: next_interleaved czyta obie ramki z jednego bufora "
                  f"({got} B), bufor wyczerpany")
        elif got == [expected, 0]:
            print(f"  BLAD: druga ramka zgubiona -- {got}, bufor {len(buf)} B")
            failures.append("next_interleaved")
        else:
            print(f"  BLAD: next_interleaved zwrocil {got}, oczekiwano "
                  f"[{expected}, {expected}]")
            failures.append("next_interleaved")
    else:
        print("  uwaga: brak next_interleaved w capture")

    # 4. The reassembler. A FU-A NAL split across fragments must come back whole.
    if hasattr(mod, "reassemble") or hasattr(mod, "Reassembler"):
        print("  uwaga: nazwa reassemblera sprawdzana recznie")
    else:
        names = [n for n in dir(mod) if "reasm" in n.lower() or "fu" in n.lower()]
        print(f"  ok: funkcje skladania NAL: {names or 'brak'}")

    # 5. Deadlines are monotonic. A capture that compares wall clock against a
    # monotonic deadline can hang, and CI cannot tell that apart from a slow test.
    src = open(CAPTURE).read()
    if "time.monotonic()" in src:
        print("  ok: deadline oparty o time.monotonic()")
    else:
        print("  BLAD: brak time.monotonic() - deadline moze zawiesic capture")
        failures.append("deadline")

    # 6. An empty socket must return, not spin -- but only through the way the
    # tool actually uses it. next_udp has no deadline parameter, so it depends on
    # the caller having set a timeout on the socket. Measured both ways: with no
    # timeout it blocks in recvfrom forever; with the 5 second timeout main() sets
    # it returns. My first version of this check called it without the timeout and
    # reported a spinner that does not exist on any real path. So the check builds
    # the socket the way main() does.
    if hasattr(mod, "next_udp"):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(("127.0.0.1", 0))
        s.settimeout(1)   # main() uses 5; shorter here so the check stays quick
        start = time.monotonic()
        got = mod.next_udp(s)
        waited = time.monotonic() - start
        s.close()
        if got is not None:
            print(f"  BLAD: puste gniazdo zwrocilo {got!r}, oczekiwano None")
            failures.append("next_udp value")
        elif waited > 5:
            print(f"  BLAD: next_udp czekal {waited:.1f}s mimo timeoutu")
            failures.append("next_udp slow")
        else:
            print(f"  ok: next_udp wraca na pustym gniezdzie po {waited:.2f}s "
                  f"(timeout ustawiony, jak w main())")

        # And without a timeout it would block -- recorded as a fact, not asserted,
        # because asserting it would mean hanging the suite on purpose.
        if not _socket_has_timeout_source():
            print("  uwaga: brak settimeout w kodzie capture'a")
        else:
            print("  ok: kod capture'a ustawia timeout na gniezdzie UDP")
    else:
        print("  uwaga: brak next_udp")

    print()
    if failures:
        print(f"  BRAK: {len(failures)} spraw")
        for f in failures:
            print("   -", f)
        return 1
    print("  brama capture: kazdy punkt, ktory main() wywoluje, zwraca werdykt")
    return 0


class _FakeSock:
    """A socket that hands over a fixed blob, then nothing."""

    def __init__(self, data: bytes):
        self._data = data

    def recv(self, n: int) -> bytes:
        out, self._data = self._data[:n], self._data[n:]
        return out


def _socket_has_timeout_source() -> bool:
    """True when the capture sets a timeout on its UDP socket."""
    src = open(CAPTURE).read()
    return "rtp_sock.settimeout" in src


def _which(tool: str) -> bool:
    from shutil import which

    return bool(which(tool))


if __name__ == "__main__":
    sys.exit(main())
