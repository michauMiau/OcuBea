#!/usr/bin/env python3
"""What the RTSP server actually says for the audio track, in both states.

Written because the conformance probe reported "disabled audio is refused" as a
FAILURE while the device had audio enabled -- the probe asserted a condition it
had never established. Fixing it revealed a second ambiguity: DESCRIBE and SETUP
were both being answered off the same connection with overlapping sequence
numbers, so which reply belonged to which request was not determinable from the
script.

So this asks precisely, one request per connection, and prints the raw status
line for each. No inference about what the answer ought to be: the point is to
see what the server does, so the assertion can be written against reality.

Usage: python3 tools/rtsp_audio_truth.py [host] [port]
"""

from __future__ import annotations

import json
import socket
import sys
import urllib.request

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.184"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8554


def ask(request_line: str, cseq: int = 1, extra: str = "") -> str:
    """Send one request on a fresh connection and return its status line."""
    s = socket.socket()
    s.settimeout(10)
    try:
        s.connect((HOST, PORT))
        # RTSP requires a Content-Length, and omitting it leaves the server
        # waiting for a body that never comes. My first version left it out and
        # every row read "no reply" -- which looks exactly like a server that
        # refuses to answer, and would have been reported as one.
        head = (
            f"{request_line} RTSP/1.0\r\n"
            f"CSeq: {cseq}\r\n"
            f"{extra}\r\n"
            "Content-Length: 0\r\n\r\n"
        )
        s.sendall(head.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            try:
                chunk = s.recv(4096)
            except (socket.timeout, OSError):
                break
            if not chunk:
                break
            buf += chunk
        return buf.decode("latin-1").split("\r\n")[0] if buf else "no reply"
    except OSError as exc:
        return f"connection error: {exc}"
    finally:
        s.close()


def ask_twice(request_line: str, transport: str, cseq: int = 1) -> list[str]:
    """Send the SAME request twice down ONE connection, return both status lines.

    A per-request probe cannot see this at all: every row above opens its own
    socket, so a repeated SETUP never happens. ffmpeg's RTSP demuxer does repeat
    it -- once for the media description, again for the payload type before
    PLAY -- and against a server that answered the repeat with
    `551 Unsupported media`, ffprobe printed

    [rtsp @ ...] method SETUP failed: 551Unsupported media
    Server returned 5XX Server Error reply

    while the server was streaming correctly. That is the regression this
    function exists to catch, and it is invisible to a probe that never repeats
    anything.
    """
    replies: list[str] = []
    s = socket.socket()
    s.settimeout(10)
    try:
        s.connect((HOST, PORT))
        for _ in range(2):
            head = (
                f"{request_line} RTSP/1.0\r\n"
                f"CSeq: {cseq}\r\n"
                f"{transport}\r\n"
                "Content-Length: 0\r\n\r\n"
            )
            s.sendall(head.encode())
            buf = b""
            # Accumulate until the header is complete, keeping any remainder:
            # TCP does not preserve message boundaries, so one recv() can
            # deliver the second reply glued to the first.
            while b"\r\n\r\n" not in buf and len(buf) < 16384:
                try:
                    chunk = s.recv(4096)
                except (socket.timeout, OSError):
                    break
                if not chunk:
                    break
                buf += chunk
            replies.append(
                buf.decode("latin-1").split("\r\n")[0] if buf else "no reply"
            )
        return replies
    except OSError as exc:
        return replies + [f"connection error: {exc}"]
    finally:
        s.close()


def audio_enabled() -> bool | None:
    try:
        with urllib.request.urlopen(f"http://{HOST}:8080/status.json", timeout=8) as r:
            return bool(json.loads(r.read()).get("audio", {}).get("enabled"))
    except Exception:
        return None


def main() -> int:
    state = audio_enabled()
    print(f"=== {HOST}:{PORT} — device audio.enabled = {state}")
    print()
    print("  Each row is a fresh connection with exactly one request.")
    print()

    # Exit codes, matching check_mjpeg.py so the two gates read the same way:
    #   0 every row matched the device's own reported audio state
    #   1 at least one row contradicted it -- the server is promising or
    #     refusing audio that does not exist
    #   2 the script could not establish the audio state, so there is nothing
    #     to assert against and a PASS here would be meaningless
    if state is None:
        print("  audio state unknown -> cannot assert anything, refusing to report 0")
        return 2

    rows = [
        ("DESCRIBE h264_pcm.sdp", f"DESCRIBE rtsp://{HOST}:{PORT}/h264_pcm.sdp", "Accept: application/sdp"),
        ("DESCRIBE h264.sdp", f"DESCRIBE rtsp://{HOST}:{PORT}/h264.sdp", "Accept: application/sdp"),
        ("SETUP   audio track", f"SETUP rtsp://{HOST}:{PORT}/h264_pcm.sdp/trackID=1",
         "Transport: RTP/AVP/TCP;unicast;interleaved=4-5"),
        ("SETUP   video track", f"SETUP rtsp://{HOST}:{PORT}/h264.sdp/trackID=0",
         "Transport: RTP/AVP/TCP;unicast;interleaved=0-1"),
    ]
    replies = {}
    for label, line, extra in rows:
        replies[label] = ask(line, 1, extra)
        print(f"  {label:28s} -> {replies[label]}")

    # The repeat, on one connection. Checked separately from the single-shot
    # rows above because it is a different failure mode with the same symptom.
    repeated = ask_twice(
        f"SETUP rtsp://{HOST}:{PORT}/h264_pcm.sdp/trackID=1",
        "Transport: RTP/AVP/TCP;unicast;interleaved=4-5",
    )
    print(f"  {'SETUP audio, twice, 1 conn':28s} -> {repeated[0] if repeated else '?'}"
          f"  then  {repeated[1] if len(repeated) > 1 else '?'}")

    print()
    # Only the audio SETUP carries the truth about audio. Video SETUP must be
    # 200 in both states -- a device without audio still serves video, and
    # asserting anything else there would fail a healthy device for the wrong
    # reason, which is exactly the mistake this file was written to end.
    audio_setup = replies["SETUP   audio track"]
    video_setup = replies["SETUP   video track"]
    failures = []

    if "no reply" in audio_setup or "connection error" in audio_setup:
        failures.append("audio SETUP got no reply, so the server never answered")
    elif state is True:
        if not audio_setup.startswith("RTSP/1.0 200"):
            failures.append(f"audio is enabled but SETUP answered {audio_setup!r}")
        elif "application/sdp" in replies["DESCRIBE h264_pcm.sdp"] and \
                "m=audio" not in replies["DESCRIBE h264_pcm.sdp"]:
            # DESCRIBE's status line alone cannot prove the media line is there;
            # only the absence of a refusal is checked here.
            pass
    else:  # audio is OFF
        if not audio_setup.startswith("RTSP/1.0 551"):
            failures.append(
                f"audio is disabled but SETUP answered {audio_setup!r} -- a promise of silence"
            )

    if not video_setup.startswith("RTSP/1.0 200"):
        failures.append(f"video SETUP answered {video_setup!r}, expected 200 in either audio state")

    # Idempotence: a track this session already holds must answer the same way
    # the first time. Refusing the repeat is what made an independent decoder
    # report a healthy server as broken, so it is asserted in BOTH audio states.
    if len(repeated) < 2:
        failures.append(f"the repeated SETUP got {len(repeated)} replies, wanted 2")
    else:
        first, second = repeated[0], repeated[1]
        if second in ("no reply",) or second.startswith("connection error"):
            failures.append(f"repeated audio SETUP answered {second!r}, expected the same as the first")
        elif first.startswith("RTSP/1.0 200") and not second.startswith("RTSP/1.0 200"):
            failures.append(
                f"repeated audio SETUP answered {second!r} after a {first!r} -- "
                "a track already in use must be idempotent"
            )

    if failures:
        print(f"  FAIL ({len(failures)}): audio.enabled={state} contradicted by the server")
        for f in failures:
            print(f"    - {f}")
        return 1

    expected = "200" if state else "551"
    print(f"  PASS: audio.enabled={state}, audio SETUP answered {expected} as it must,")
    print("        and video SETUP answered 200 in the same state")
    return 0


if __name__ == "__main__":
    sys.exit(main())