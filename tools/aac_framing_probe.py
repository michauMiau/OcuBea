#!/usr/bin/env python3
"""Which RTP/AAC-hbr framing does ffmpeg accept, tested against the real frames.

OcuBea's AAC track is measured correct on the wire: one whole ADTS frame per
packet, marker set, timestamps +1024, and the AU size field equal to the bytes
that follow. Concatenated without RTP the same access units decode as
`aac 48000 Hz mono`. ffmpeg still answers `Error parsing AU headers`, so the
framing is the remaining variable.

This replays the real frames from a local socket, four ways:

  hdr16    2-byte AU-headers-section, sizelength=16   (RFC 3640, one AU per packet)
  hdr8     1-byte AU-headers-section, sizelength=8    (one AU, < 256 B)
  bare     no AU-headers at all, sizelength=0
  corrupt  a 2-byte section whose size is one too large -- must be rejected

The verdict is the number of bytes ffmpeg writes, never the absence of stderr:
`-f null` returns 0 and prints nothing when it decoded nothing at all, which made
an earlier version of this probe call all four layouts a success, the corrupt one
included.

Run: python3 tools/aac_framing_probe.py [host] [port]
Exits 0 if exactly one layout decodes and the corrupt control is rejected.
"""
import os
import re
import socket
import subprocess
import sys
import threading
import time

BASE_PORT = 18554
ADTS_HEADER_BYTES = 7


def real_access_units(host, port, seconds=3.0):
    """Whole ADTS frames OcuBea is sending right now."""
    base = f"rtsp://{host}:{port}/h264_pcm.sdp"
    sock = socket.create_connection((host, port), timeout=10)
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
        return head.decode("latin1", "replace")

    head = req("SETUP", f"{base}/trackID=1",
           "Transport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n")
    if "200" not in head.splitlines()[0]:
        raise RuntimeError(f"SETUP audio odrzucony: {head.splitlines()[0]}")
    # The channel the server actually assigned, not an assumed one: audio is on
    # an odd offset from the video channel and this probe asked for 2-3. Reading
    # the Transport header is the only way to be sure.
    transport = re.search(r"interleaved=(\d+)-(\d+)", head)
    if not transport:
        raise RuntimeError("brak interleaved= w odpowiedzi SETUP")
    audio_channel = int(transport.group(1))
    session = re.search(r"Session:\s*([^;\r\n]+)", head).group(1).strip()
    req("PLAY", base, f"Session: {session}\r\nRange: npt=0.000-\r\n")

    sock.settimeout(0.4)
    wire = bytearray()
    started = time.time()
    while time.time() - started < seconds:
        try:
            wire += sock.recv(65536)
        except socket.timeout:
            pass
        except OSError:
            break
    sock.close()

    units = []
    i = 0
    while i + 4 <= len(wire):
        if wire[i] in (0x24, 0x25):
            length = int.from_bytes(wire[i + 2:i + 4], "big")
            if i + 4 + length > len(wire):
                break
            if wire[i + 1] == audio_channel:
                pkt = bytes(wire[i + 4:i + 4 + length])
                off = 12 + (pkt[0] & 0x0F) * 4
                if pkt[0] & 0x10:
                    off += 4
                payload = pkt[off:]
                # Strip the AU-headers-section and keep the whole ADTS frame.
                # Offsets are relative to the ADTS start, which is payload[2]:
                # the AU-headers-section is 2 bytes wide with sizelength=16.
                # frame_length is 13 bits of bytes 3..5 of that ADTS header,
                # i.e. payload[5], payload[6], payload[7].
                if len(payload) > 9 and payload[2] == 0xFF and (payload[3] & 0xF0) == 0xF0:
                    frame = ((payload[5] & 0x03) << 11) | \
                        ((payload[6] & 0xFF) << 3) | ((payload[7] & 0xFF) >> 5)
                    # The AU must be whole: payload is exactly the 2-byte
                    # AU-headers-section plus the frame it declares.
                    if 7 <= frame and len(payload) == frame + 2:
                        units.append(payload[2:2 + frame])
            i += 4 + length
        else:
            i += 1
    return units


def sdp_for(sizelength, config):
    # Content-Base is what lets ffmpeg resolve trackID=0 against the request URI;
    # a=control:* gives the session a base control. Without both it refuses the
    # SDP with `Invalid data found` and every layout measures as 0 B.
    return (
        "v=0\r\n"
        "o=- 0 0 IN IP4 127.0.0.1\r\n"
        "s=aac probe\r\n"
        "c=IN IP4 127.0.0.1\r\n"
        "t=0 0\r\n"
        "a=control:*\r\n"
        "m=audio 0 RTP/AVP 97\r\n"
        "a=rtpmap:97 MPEG4-GENERIC/48000/1\r\n"
        f"a=fmtp:97 mode=AAC-hbr;profile-level-id=1;sizelength={sizelength};"
        f"indexlength=0;indexdeltalength=0{config}\r\n"
        "a=control:trackID=0\r\n"
    )


class Server(threading.Thread):
    def __init__(self, units, sizelength, config, with_header, corrupt, strip_adts=False):
        super().__init__(daemon=True)
        self.units = units
        self.sizelength = sizelength
        self.config = config
        self.with_header = with_header
        self.corrupt = corrupt
        self.strip_adts = strip_adts
        self.port = BASE_PORT + len(units) % 97 + sizelength
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", self.port))
        self.sock.listen(1)

    def run(self):
        try:
            conn, _ = self.sock.accept()
        except OSError:
            return
        conn.settimeout(6)
        cseq = 0
        try:
            while True:
                data = conn.recv(65536)
                if not data:
                    return
                lines = data.decode("latin1", "replace").split("\r\n")
                for line in lines:
                    if not line:
                        continue
                    verb = line.split(" ")[0]
                    if not verb.isupper():
                        continue
                    # ffmpeg sends SETUP and PLAY back to back in one write, so
                    # splitting on CRLF and answering each line in turn is what
                    # keeps the session open: the old loop returned after PLAY
                    # streamed, and ffmpeg read `End of file` before it had seen
                    # the interleaved data it had already requested.
                    if verb == "PLAY":
                        cseq += 1
                        conn.sendall(
                            f"RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\n"
                            f"Session: {self.port}\r\nRange: npt=0.000-\r\n\r\n".encode())
                        self.stream(conn)
                        return
                    cseq += 1
                    if verb == "OPTIONS":
                        conn.sendall(f"RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\n"
                                     f"Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n\r\n".encode())
                    elif verb == "DESCRIBE":
                        sdp = sdp_for(self.sizelength, self.config)
                        conn.sendall(
                            f"RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\n"
                            f"Content-Type: application/sdp\r\n"
                            f"Content-Base: rtsp://127.0.0.1:{self.port}/x\r\n"
                            f"Content-Length: {len(sdp)}\r\n\r\n{sdp}".encode())
                    elif verb == "SETUP":
                        conn.sendall(
                            f"RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\n"
                            f"Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n"
                            f"Session: {self.port};timeout=60\r\n\r\n".encode())
                    elif verb in ("TEARDOWN", "GET_PARAMETER"):
                        conn.sendall(f"RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\n\r\n".encode())
        except OSError:
            pass
        finally:
            self.sock.close()

    def stream(self, conn):
        seq, ts = 1000, 0
        for unit in self.units:
            # Under MPEG4-GENERIC/AAC-hbr the access unit is raw AAC. ADTS is a
            # file-framing wrapper for .aac files and is not part of the RTP
            # payload: a decoder that trusts AudioSpecificConfig from `config=`
            # reads the first bytes as audio and the whole frame is noise. So the
            # "strip" variants below remove the 7-byte ADTS header.
            payload = unit[ADTS_HEADER_BYTES:] if self.strip_adts else unit
            if self.with_header:
                size = len(payload) + 1 if self.corrupt else len(payload)
                body = (size.to_bytes(2, "big") if self.sizelength == 16
                        else bytes([size & 0xFF])) + payload
            else:
                body = payload
            hdr = bytearray(12)
            hdr[0] = 0x80
            hdr[1] = 0x80 | 97
            hdr[2] = (seq >> 8) & 0xFF
            hdr[3] = seq & 0xFF
            hdr[4] = (ts >> 24) & 0xFF
            hdr[5] = (ts >> 16) & 0xFF
            hdr[6] = (ts >> 8) & 0xFF
            hdr[7] = ts & 0xFF
            hdr[10], hdr[11] = 0x04, 0x00
            pkt = bytes(hdr) + body
            conn.sendall(b"\x24\x00" + len(pkt).to_bytes(2, "big") + pkt)
            seq += 1
            ts += 1024


def attempt(name, units, sizelength, config, with_header, corrupt=False, strip_adts=False):
    server = Server(units, sizelength, config, with_header, corrupt, strip_adts)
    server.start()
    out = f"/tmp/aac_framing_{name}.aac"
    if os.path.exists(out):
        os.remove(out)
    result = subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-rtsp_transport", "tcp",
         "-i", f"rtsp://127.0.0.1:{server.port}/x", "-t", "4",
         "-map", "0:a:0", "-c:a", "copy", out],
        capture_output=True, text=True, timeout=40)
    written = os.path.getsize(out) if os.path.exists(out) else 0
    errors = [l for l in result.stderr.splitlines()
              if "AU headers" in l or "larger than packet" in l or "Invalid data" in l]
    ok = written > len(units) * 80 and not errors
    label = "DEKODUJE" if ok else "odrzucone"
    print(f"  {name:11s} sizelen={sizelength:2d} config={'tak' if config else 'nie'} "
          f"hdr={'tak ' if with_header else 'nie'} adts={'zost' if not strip_adts else 'ZERO'}"
          f" -> {written:6d} B  {label}"
          + (f"  {errors[0][:44]}" if errors else ""))
    return ok


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.29"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8554
    try:
        units = real_access_units(host, port)
    except (OSError, RuntimeError, ConnectionError) as exc:
        print(f"BLAD: nie da sie pobrac prawdziwych ramek z OcuBea: {exc}")
        return 2
    if len(units) < 10:
        print(f"BLAD: tylko {len(units)} ramek z telefonu, za malo by osaczyc wariant")
        return 2
    print(f"  ramek z telefonu: {len(units)}, "
          f"{min(len(u) for u in units)}-{max(len(u) for u in units)} B\n")

    results = {}
    cfg = ";config=1188"
    # The decisive question: does the access unit go out with its ADTS header or
    # without. Under MPEG4-GENERIC the header is not part of the payload.
    for name, sl, hdr, strip in (
        ("hdr16_adts", 16, True, False),
        ("hdr16_zero", 16, True, True),
        ("bare_zero", 0, False, True),
        ("hdr8_zero", 8, True, True),
        ("hdr16_nc", 16, True, True),
    ):
        results[name] = attempt(name, units, sl, cfg, hdr, strip_adts=strip)

    print("\n  kontrola (musi byc odrzucone):")
    bad_ok = attempt("corrupt", units, 16, cfg, True, corrupt=True, strip_adts=True)
    if bad_ok:
        print("\n  BLAD: proba przyjmuje celowo zly rozmiar AU -- niczego nie rozroznia")
        return 1

    winners = [k for k, v in results.items() if v]
    print()
    if not winners:
        print("  ZADEN wariant nie dekoduje -- wada jest poza tymi czterema ukladami")
        return 1
    if len(winners) > 1:
        print(f"  dekoduje: {', '.join(winners)} -- proba ich nie rozroznia")
        return 1
    print(f"  ffmpeg akceptuje wylacznie: {winners[0]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())