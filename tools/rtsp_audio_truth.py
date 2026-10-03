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
    print(f"  DESCRIBE h264_pcm.sdp        -> {ask(f'DESCRIBE rtsp://{HOST}:{PORT}/h264_pcm.sdp', 1, 'Accept: application/sdp')}")
    print(f"  DESCRIBE h264.sdp            -> {ask(f'DESCRIBE rtsp://{HOST}:{PORT}/h264.sdp', 1, 'Accept: application/sdp')}")
    print(f"  SETUP   audio track          -> {ask(f'SETUP rtsp://{HOST}:{PORT}/h264_pcm.sdp/trackID=1', 1, 'Transport: RTP/AVP/TCP;unicast;interleaved=4-5')}")
    print(f"  SETUP   video track          -> {ask(f'SETUP rtsp://{HOST}:{PORT}/h264.sdp/trackID=0', 1, 'Transport: RTP/AVP/TCP;unicast;interleaved=0-1')}")
    print()
    if state is True:
        print("  audio is ON  -> audio SETUP should be 200, and DESCRIBE should advertise it")
    elif state is False:
        print("  audio is OFF -> audio SETUP should be 551, not a promise of silence")
    else:
        print("  audio state unknown -> read /status.json before trusting either column")
    return 0


if __name__ == "__main__":
    sys.exit(main())