#!/usr/bin/env python3
"""Capture the OcuBea RTSP H264 track and prove a third-party player can decode it.

Why this file exists
-------------------
A protocol probe can pass on a stream nobody can play. This one does not: it pulls
the RTP off the wire, reassembles it back into Annex-B, and hands the result to
ffmpeg. If ffmpeg reads no frames, this exits non-zero. Protocol conformance and
playability are different questions, and only the second one matters to a user.

Transport: TCP interleaved, not UDP
-----------------------------------
RFC 2326 10.12 lets SETUP ask for "RTP/AVP/TCP;unicast;interleaved=0-1", which
carries RTP inside the RTSP connection as $ <channel> <length:16> frames. UDP is
available behind --udp for comparison, but it is not the default, because a UDP
capture of this stream loses FU-A fragments and the loss looks exactly like a
broken encoder: the top of a frame decodes and the remainder is a vertical smear,
while ffmpeg reports "out of range intra chroma pred mode" and "error while
decoding MB 63 20". Those messages are what a NAL with a hole in it produces. The
hole was in the transport. Judging the encoder on a UDP capture measures the
network, not the code.

Reassembly boundaries come from the FU-A flags
---------------------------------------------
Not from scanning for start codes. RTP payloads carry no start codes -- RFC 6184
strips them -- so after reassembly nothing in the data records where one NAL
stopped and the next began. Two earlier versions tried anyway: one wrote a start
code per access unit, so parameter sets and picture ran together; the next wrote
one in front of every byte. Both produced files ffmpeg rejected. The S and E bits
are the only surviving record of the boundaries.
"""

import argparse
import glob
import os
import socket
import subprocess
import sys
import time


def parse_status(blob: bytes):
    for line in blob.split(b"\r\n"):
        if line.startswith(b"RTSP/"):
            parts = line.split(None, 2)
            return int(parts[1]), parts[2].decode("latin-1")
    return 0, "no status line"


def read_headers(sock, seconds=20.0):
    """Read one RTSP response, which ends at the blank line."""
    sock.settimeout(seconds)
    buf = b""
    stop = time.monotonic() + seconds
    while b"\r\n\r\n" not in buf and time.monotonic() < stop:
        try:
            chunk = sock.recv(4096)
        except (socket.timeout, OSError):
            break
        if not chunk:
            break
        buf += chunk
    return buf


def read_exact(sock, n, stop):
    got = bytearray()
    while len(got) < n and time.monotonic() < stop:
        try:
            chunk = sock.recv(n - len(got))
        except (socket.timeout, OSError):
            break
        if not chunk:
            break
        got += chunk
    return bytes(got)


def next_interleaved(sock, buf, stop):
    """Pull one RTP packet out of the $ framing, keeping leftovers in buf.

    Returns (payload, buf). The buffer is carried across calls because a single
    recv() routinely returns a partial frame plus the start of the next one, and
    re-reading from the socket instead would drop whatever was already taken.
    """
    # Find the next '$'. Anything before it is an RTSP response or junk.
    while True:
        at = buf.find(b"$")
        if at >= 0:
            buf = buf[at:]
            break
        if time.monotonic() >= stop:
            return None, buf
        try:
            chunk = sock.recv(4096)
        except (socket.timeout, OSError):
            return None, buf
        if not chunk:
            return None, buf
        buf += chunk

    while len(buf) < 4:
        if time.monotonic() >= stop:
            return None, buf
        try:
            chunk = sock.recv(4096)
        except (socket.timeout, OSError):
            return None, buf
        if not chunk:
            return None, buf
        buf += chunk

    length = int.from_bytes(buf[2:4], "big")
    buf = buf[4:]
    while len(buf) < length:
        if time.monotonic() >= stop:
            return None, buf
        buf += read_exact(sock, length - len(buf), stop)
        if len(buf) < length:
            return None, buf
    return buf[:length], buf[length:]


def next_udp(rtp_sock):
    try:
        pkt, _ = rtp_sock.recvfrom(65536)
    except (socket.timeout, OSError):
        return None
    if len(pkt) < 4:
        return None
    length = int.from_bytes(pkt[2:4], "big")
    return pkt[4:4 + length]


def main() -> int:
    ap = argparse.ArgumentParser(description="capture RTSP H264 and prove it decodes")
    ap.add_argument("host")
    ap.add_argument("port", type=int, nargs="?", default=8554)
    ap.add_argument("out", nargs="?", default="/tmp/ocubea.h264")
    ap.add_argument("seconds", type=float, nargs="?", default=4.0,
                    help="how long to record; bounded by time AND by packet count")
    ap.add_argument("--udp", action="store_true",
                    help="use UDP instead of TCP interleaved (loses fragments; for comparison only)")
    ap.add_argument("--packets", type=int, default=4000,
                    help="packet ceiling, a second bound in case the stream stalls")
    args = ap.parse_args()

    host, port, out = args.host, args.port, args.out
    base = f"rtsp://{host}:{port}/h264.sdp"

    s = socket.socket()
    s.settimeout(20)
    s.connect((host, port))

    if args.udp:
        rtp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rtp_sock.bind(("0.0.0.0", 0))
        rtp_sock.settimeout(5)
        rtp_port = rtp_sock.getsockname()[1]
        transport = f"RTP/AVP;unicast;client_port={rtp_port}-{rtp_port + 1}"
    else:
        rtp_sock = None
        transport = "RTP/AVP/TCP;unicast;interleaved=0-1"

    plan = [
        ("DESCRIBE", base, 1, ("Accept: application/sdp",)),
        ("SETUP", base + "/trackID=0", 2, (f"Transport: {transport}",)),
        ("PLAY", base, 3, ("Range: npt=0.000-",)),
    ]
    for method, uri, n, extra in plan:
        lines = [f"{method} {uri} RTSP/1.0", f"CSeq: {n}", "User-Agent: ocubea-capture"]
        lines.extend(extra)
        # Content-Length: 0 tells the server not to wait for a request body.
        # Without it the server sits in recv() and the probe reports "no reply"
        # for a request that was actually answered.
        lines.append("Content-Length: 0")
        s.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("latin-1"))
        code, reason = parse_status(read_headers(s))
        print(f"  {method:9s} -> {code} {reason}")

    # Two independent bounds. A packet count alone is not a bound on time: a fast
    # stream reaches it in seconds, a stalled one blocks in recv() for the full
    # socket timeout per packet and the script then runs for minutes.
    stop_at = time.monotonic() + args.seconds
    buf = b""

    units: list[tuple[int, bytes]] = []
    current_type = -1
    current = bytearray()
    packets = fu_fragments = single_packets = markers = short_packets = 0

    def flush():
        nonlocal current, current_type
        if current and current_type >= 0:
            units.append((current_type, bytes(current)))
        current = bytearray()
        current_type = -1

    while packets < args.packets and time.monotonic() < stop_at:
        if args.udp:
            payload = next_udp(rtp_sock)
        else:
            payload, buf = next_interleaved(s, buf, stop_at)
        if not payload:
            continue
        # 12-byte RTP header: V/P/X/CC, M/PT, seq, timestamp, ssrc
        if len(payload) < 13:
            short_packets += 1
            continue
        if payload[1] & 0x7F != 96:      # dynamic PT for H264 in this SDP
            continue
        packets += 1
        if (payload[1] >> 7) & 1:
            markers += 1
        media = payload[12:]
        indicator = media[0]

        if indicator & 0x1F == 28:        # FU-A
            fu_fragments += 1
            fu_header = media[1]
            if (fu_header >> 7) & 1:      # S: a new NAL starts here
                flush()
                current_type = fu_header & 0x1F
            current += media[2:]
            if (fu_header >> 6) & 1:      # E: this NAL ends here
                flush()
        else:                             # a whole NAL in one packet
            single_packets += 1
            flush()
            current_type = indicator & 0x1F
            current += media[1:]
            flush()
    flush()
    s.close()
    if rtp_sock:
        rtp_sock.close()

    print()
    print(f"  transport     : {'UDP (porownanie, gubi fragmenty)' if args.udp else 'TCP interleaved'}")
    extra = f", {short_packets} za krotkie" if short_packets else ""
    print(f"  pakietow      : {packets} ({fu_fragments} FU-A, {single_packets} pojedynczych){extra}")
    print(f"  marker bitow  : {markers}")

    if not units:
        print("  brak NAL units -- serwer nic nie wyslal")
        return 1

    with open(out, "wb") as f:
        for _t, body in units:
            f.write(b"\x00\x00\x00\x01" + body)

    seen = sorted({t for t, _ in units})
    names = {1: "non-IDR", 5: "IDR", 6: "SEI", 7: "SPS", 8: "PPS"}
    label = lambda t: f"{t}({names.get(t, '?')})"
    print(f"  zapisano {out}: {len(units)} NAL, {sum(len(b) for _, b in units)} B")
    print(f"  typy NAL      : {[label(t) for t in seen]}")
    print(f"  SPS w-band    : {'tak' if 7 in seen else 'NIE'}"
          f"   PPS w-band: {'tak' if 8 in seen else 'NIE'}")
    # Reported per type because a 1400-byte "SPS" means the parameter sets and the
    # picture have merged into one unit, which is what an early capture showed:
    # 29 huge SPS and no PPS at all.
    for t in seen:
        bodies = [b for tt, b in units if tt == t]
        sizes = sorted({len(b) for b in bodies})
        print(f"    typ {t:2d} {label(t):9s}: {len(bodies)} szt, "
              f"rozmiary {sizes[:4]}{'...' if len(sizes) > 4 else ''}")

    print()
    # Everything from here on is the decode gate, and it lives in
    # tools/decode_gate.py so it can be run against a saved file without a phone.
    # It used to be inline, which meant the only way to check whether this gate
    # could tell a broken capture from a good one was to produce a broken capture.
    #
    # What it asks, and why: every other check here asks the server whether it is
    # talking to itself -- 200 on DESCRIBE, NAL units present, packets arriving --
    # and all of those pass on a stream that decodes into a vertical smear with a
    # green band. A NAL with a hole in it still decodes, so nb_read_frames is
    # green on it: this tool once read one interleaved frame per recv() and threw
    # 212 of 213 away, and ffprobe still reported 15 frames. Frame uniqueness passed
    # too, because two of three exported frames were identical and the third
    # differed.
    #
    # The decoder's own error output is what cannot be faked. Measured across
    # seventeen captures: healthy files 0-1 errors over 26-36 frames, damaged ones
    # 24-47 over 13-29. The line is drawn at 0.5 errors per frame, where the two
    # populations actually separate -- not at zero, which no file reaches.
    ok, rep = decode_gate.passes(out)
    print(f"  codec            : {rep['codec']}")
    print(f"  nb_read_frames   : {rep['frames']}")
    print(f"  bledy dekodowania: {rep['errors']}")
    print(f"  na klatke        : {rep['per_frame']:.2f}")
    for sample in rep["samples"]:
        print("  ! " + sample)
    pngs = decode_gate.export_pngs(out)
    print(f"  klatki wyeksportowane: {len(pngs)}  "
          f"{[f'{os.path.basename(f)} {os.path.getsize(f)} B' for f in pngs]}")

    if ok:
        print("  brama dekodowania: OK")
        return 0
    print(f"  BRAK: {rep.get('reason', 'brama dekodowania nie przeszla')}")
    return 1


if __name__ == "__main__":
    sys.exit(main())