#!/usr/bin/env python3
"""Check the AAC RTP payload actually carries AU-headers.

The audio gate in verify_audio_truth_gate.py only reads RTSP status codes, so it
stayed green while every AAC frame was undecodable: `a=fmtp:97 mode=AAC-hbr`
promises RFC 3640 AU-headers and the server shipped raw ADTS, which starts
0xFFF1. ffmpeg read the first byte as an AU-headers length of 255 and printed
`Error parsing AU headers` 887 times over three seconds.

This is a two-terminal check on one live session:
  terminal 1 -- a fake client that does DESCRIBE + SETUP + PLAY on the audio
                track and prints what the SDP promises
  terminal 2 -- the same, but it also reads the interleaved payload and checks
                the first bytes against the promise

Exits 0 if the payload matches what the SDP declares, 1 if it does not, 2 if
there is nothing to talk to.
"""
import json
import re
import socket
import sys
import time

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8554
CODEC = sys.argv[3] if len(sys.argv) > 3 else "aac"
BASE = f"rtsp://{HOST}:{PORT}/h264_pcm.sdp"


def session(collect_seconds: float = 4.0):
    """One RTSP session, returns (sdp_text, [(channel, packet)], audio_track)."""
    sock = socket.create_connection((HOST, PORT), timeout=10)
    sock.settimeout(6)
    cseq = 0

    def req(method, url, extra=""):
        nonlocal cseq
        cseq += 1
        sock.sendall(f"{method} {url} RTSP/1.0\r\nCSeq: {cseq}\r\n{extra}\r\n".encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = sock.recv(65536)
            if not chunk:
                raise ConnectionError("serwer zamknal sesje")
            buf += chunk
        head, _, body = buf.partition(b"\r\n\r\n")
        length = re.search(rb"Content-Length:\s*(\d+)", head, re.I)
        want = int(length.group(1)) if length else 0
        while len(body) < want:
            body += sock.recv(65536)
        return head.decode("latin1", "replace"), body.decode("latin1", "replace")

    head, sdp = req("DESCRIBE", BASE, "Accept: application/sdp\r\n")
    if "200" not in head.split("\r\n")[0]:
        raise RuntimeError(f"DESCRIBE odrzuczony: {head.splitlines()[0]}")

    head, _ = req(
        "SETUP", f"{BASE}/trackID=1",
        "Transport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n",
    )
    if "200" not in head.split("\r\n")[0]:
        raise RuntimeError(f"SETUP audio odrzuczony: {head.splitlines()[0]}")
    transport = re.search(r"interleaved=(\d+)-(\d+)", head)
    if not transport:
        raise RuntimeError("brak interleaved= w odpowiedzi SETUP")
    channel = int(transport.group(1))
    session_id = re.search(r"Session:\s*([^;\r\n]+)", head)
    if not session_id:
        raise RuntimeError("brak Session= w odpowiedzi SETUP")

    req("PLAY", f"{BASE}/trackID=1",
        f"Session: {session_id.group(1).strip()}\r\nRange: npt=0.000-\r\n")

    sock.settimeout(0.4)
    wire = bytearray()
    started = time.time()
    while time.time() - started < collect_seconds:
        try:
            wire += sock.recv(65536)
        except socket.timeout:
            pass
        except OSError:
            break
    sock.close()

    packets, i = [], 0
    while i + 4 <= len(wire):
        marker, chan = wire[i], wire[i + 1]
        if marker in (0x24, 0x25):
            length = int.from_bytes(wire[i + 2:i + 4], "big")
            if i + 4 + length > len(wire):
                break
            packets.append((chan, bytes(wire[i + 4:i + 4 + length])))
            i += 4 + length
        else:
            i += 1
    return sdp, packets, channel


def au_header_bytes(sdp: str) -> int:
    """Bytes of AU-headers-section the SDP says precede each AU."""
    fmtp = re.search(r"a=fmtp:\d+([^\\r\\n]*)", sdp)
    if not fmtp:
        return 0
    size = re.search(r"sizelength=(\d+)", fmtp.group(1), re.I)
    if size:
        return int(size.group(1)) // 8
    # No sizeLength stated: RFC 3640's default is 16 bits for a single AU.
    return 2


def main():
    try:
        sdp, packets, channel = session()
    except (OSError, RuntimeError, ConnectionError) as exc:
        print(f"BLAD: nie da sie nalozyc sesji: {exc}")
        return 2

    audio = [p for c, p in packets if c == channel and len(p) >= 12]
    if not audio:
        print(f"BLAD: 0 pakietow RTP na kanale audio {channel} z {len(packets)} ramek")
        return 2

    payloads = []
    for p in audio:
        offset = 12 + (p[0] & 0x0F) * 4
        if p[0] & 0x10:
            offset += 4
        payloads.append(p[offset:])
    payloads = [p for p in payloads if p]

    sizes = {p[1] & 0x7F for p in audio}
    announced = re.search(r"a=rtpmap:\d+ (\S+)", sdp)
    encoding = announced.group(1) if announced else "?"
    header_len = au_header_bytes(sdp)
    failures = []

    if CODEC == "aac":
        if "MPEG4-GENERIC" not in sdp:
            failures.append(
                f"SDP deklaruje {encoding}, a kodek na telefonie to {CODEC} "
                "-- klient otworzy niewlasciwy dekoder")
        if header_len == 0:
            failures.append("brak sizelength w a=fmtp: klient nie wie, ile bajtow naglowka pominac")

        # The payload must not begin with ADTS syncword 0xFFF: under Aac-hbr the
        # first two bytes are a size field, so a packet starting 0xFF means the
        # access unit was shipped raw and the size reads as 255 or 511.
        for payload in payloads:
            if payload[0] == 0xFF:
                failures.append(
                    f"payload zaczyna sie od 0xFF (syncword ADTS): "
                    f"{payload[:8].hex()} -- to surowy AU, nie AU-header")
                break

        # Every announced size must match the bytes that actually follow, and the
        # declared samples must be a frame rather than a byte count.
        for payload in payloads:
            declared = int.from_bytes(payload[:header_len], "big")
            actual = len(payload) - header_len
            if declared != actual:
                failures.append(
                    f"AU-header deklaruje {declared} B, a po naglowku jest {actual} B "
                    f"({payload[:8].hex()})")
                break
            if actual <= 0:
                failures.append(f"AU-header deklaruje {declared} B, czyli zero bajtow audio")
                break

        for p in audio[:1]:
            samples = int.from_bytes(p[8:12], "big")
            if samples == 0:
                failures.append("pole 'samples' naglowka RTP = 0 -- klient nie ma z czego liczyc dlugosci klatki")
                break
            # The L16 rule is bytes/2. For AAC that lands on a number that tracks
            # the payload size (measured 102 for a 204-byte packet) instead of the
            # frame's real length in samples (1024), which is what a player uses
            # to pace playback.
            first_len = len(payloads[0]) - header_len if payloads else 0
            if first_len and samples == first_len // 2:
                failures.append(
                    f"pole 'samples' naglowka RTP = {samples} = bajty/2 "
                    f"({first_len}/2) -- to regula L16, a nie probki kodeka; "
                    "klient odtworzy audio tysiac razy za szybko")
                break
            if samples > 8192:
                failures.append(
                    f"pole 'samples' naglowka RTP = {samples}, co jest bajtami/2 "
                    "a nie probkami kodeka")
                break

    print(f"SDP        : {encoding}, naglowek AU = {header_len} B")
    print(f"pakietow   : {len(audio)} na kanale {channel}, PT={sorted(sizes)}")
    if payloads:
        print(f"payload    : {len(payloads[0])} B, pierwsze bajty {payloads[0][:10].hex()}")
        print(f"           naglowek {payloads[0][:header_len].hex()} "
              f"= {int.from_bytes(payloads[0][:header_len], 'big')} B, "
              f"reszta {len(payloads[0]) - header_len} B")
    if audio:
        s = int.from_bytes(audio[0][8:12], "big")
        print(f"samples    : {s} (pole RTP)")

    for f in failures:
        print(f"BLAD: {f}")
    if failures:
        print("WNIOSEK: SDP obiecuje jedno, a bajty na wire mowia co innego")
        return 1
    print("WNIOSEK: bajty na wire zgadzaja sie z tym, co obiecuje SDP")
    return 0


if __name__ == "__main__":
    sys.exit(main())