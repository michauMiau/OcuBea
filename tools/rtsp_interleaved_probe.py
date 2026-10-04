#!/usr/bin/env python3
"""Real RTSP probe over interleaved TCP.

Reads the SDP, does SETUP+PLAY on the video track and reassembles the RTP into
an Annex-B file, so ffmpeg can be pointed at what the server actually sent.

Exit codes: 0 stream reassembled, 1 no RTP at all, 2 RTSP error.
"""
import base64
import re
import socket
import sys
import time
from collections import Counter

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.184"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8554
SECONDS = int(sys.argv[3]) if len(sys.argv) > 3 else 8
OUT = sys.argv[4] if len(sys.argv) > 4 else "/tmp/rtsp_out.h264"
TRACK = sys.argv[5] if len(sys.argv) > 5 else "trackID=0"

BASE = f"rtsp://{HOST}:{PORT}/h264_pcm.sdp"


class Rtsp:
    def __init__(self, host, port):
        self.s = socket.create_connection((host, port), timeout=10)
        self.s.settimeout(6)
        self.cseq = 0

    def request(self, method, url, extra=""):
        self.cseq += 1
        head = f"{method} {url} RTSP/1.0\r\nCSeq: {self.cseq}\r\n{extra}\r\n"
        self.s.sendall(head.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.s.recv(65536)
            if not chunk:
                break
            buf += chunk
        head, _, rest = buf.partition(b"\r\n\r\n")
        m = re.search(rb"Content-Length:\s*(\d+)", head, re.I)
        want = int(m.group(1)) if m else 0
        body = rest
        while len(body) < want:
            chunk = self.s.recv(65536)
            if not chunk:
                break
            body += chunk
        return head.decode("latin1", "replace"), body


def main():
    r = Rtsp(HOST, PORT)
    desc_head, sdp = r.request("DESCRIBE", BASE, "Accept: application/sdp\r\n")
    if "200" not in desc_head.splitlines()[0]:
        print(f"  DESCRIBE odrzucone: {desc_head.splitlines()[0]}")
        return 2

    sets = re.search(rb"sprop-parameter-sets=([^\r\n]+)", sdp)
    if sets:
        parts = sets.group(1).decode().split(",")
        sps, pps = base64.b64decode(parts[0]), base64.b64decode(parts[1])
        print(f"  SDP: SPS {len(sps)}B PPS {len(pps)}B")
    else:
        print("  SDP: brak sprop-parameter-sets")

    track = f"{BASE}/{TRACK}"
    head, _ = r.request(
        "SETUP", track,
        "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n",
    )
    print(f"  SETUP: {head.splitlines()[0]}")
    for line in head.splitlines():
        if line.lower().startswith(("transport", "session")):
            print(f"    {line.strip()}")
    if "200" not in head.splitlines()[0]:
        return 2

    m = re.search(r"Session:\s*([^;\r\n]+)", head)
    session = m.group(1).strip() if m else "1"
    head, _ = r.request("PLAY", track, f"Session: {session}\r\nRange: npt=0.000-\r\n")
    print(f"  PLAY:  {head.splitlines()[0]}")
    if "200" not in head.splitlines()[0]:
        return 2

    wire = bytearray()
    r.s.settimeout(0.4)
    deadline = time.time() + SECONDS
    while time.time() < deadline:
        try:
            wire += r.s.recv(65536)
        except socket.timeout:
            continue
        except OSError:
            break

    frames = []
    i = 0
    while i + 4 <= len(wire):
        if wire[i] == 0x24:
            length = int.from_bytes(wire[i + 2:i + 4], "big")
            if i + 4 + length > len(wire):
                break
            frames.append((wire[i + 1], bytes(wire[i + 4:i + 4 + length])))
            i += 4 + length
        elif wire[i] == 0x25:
            length = int.from_bytes(wire[i + 2:i + 4], "big")
            if i + 4 + length > len(wire):
                break
            i += 4 + length
        else:
            i += 1

    channels = sorted({c for c, _ in frames})
    print(f"  interleaved {len(wire)} B -> {len(frames)} ramek, kanal(y) {channels}")
    if not frames:
        print("  ZADEN RTP po PLAY - klient nie dostanie nic")
        return 1

    kinds = Counter()
    bits = bytearray()
    pending = None
    for channel, pkt in frames:
        if channel != 0 or len(pkt) < 12 or (pkt[0] >> 6) != 2:
            continue
        cc = pkt[0] & 0x0F
        off = 12 + cc * 4
        if pkt[0] & 0x10:
            off += 4
        pay = pkt[off:]
        if not pay:
            continue
        t = pay[0] & 0x1F
        if t == 28:
            fu = pay[1] & 0x1F
            indicator = pay[0] & 0xE0
            start = pay[1] & 0x80
            end = pay[1] & 0x40
            kinds[f"FU-A/{fu}" + ("S" if start else "") + ("E" if end else "")] += 1
            if start:
                # A decoder rebuilds the NAL byte from the indicator's NRI and
                # the FU header's type. Writing a placeholder instead would
                # shift every following byte by one and invent nal_unit_type 0.
                pending = indicator | fu
                bits += b"\x00\x00\x00\x01" + bytes([pending])
                bits += pay[2:]
            elif pending is not None:
                bits += pay[2:]
            else:
                continue
            if end:
                pending = None
        elif t == 24:
            kinds["STAP-A"] += 1
            j = 1
            while j + 2 <= len(pay):
                size = int.from_bytes(pay[j:j + 2], "big")
                j += 2
                if size == 0:
                    break
                bits += b"\x00\x00\x00\x01" + pay[j:j + size]
                j += size
        else:
            kinds[f"NAL {t}"] += 1
            bits += b"\x00\x00\x00\x01" + pay

    print(f"  NAL: {dict(kinds)}")
    with open(OUT, "wb") as fh:
        fh.write(bytes(bits))
    print(f"  Annex-B -> {OUT} ({len(bits)} B)")
    r.s.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())