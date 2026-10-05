"""Decide the RFC 3640 AAC-hbr layout by exhaustive replay, not by one guess.

Three independent choices the sender makes, and ffmpeg's reaction to each:

  config=    with config= in SDP the AAC decoder is built from extradata and
             the AU-headers-section is expected to be absent; without it the
             sender must carry the AU sizes itself.
  AU headers 2-byte 16-bit size prefix per packet, or nothing.
  ADTS       RFC 3640 carries raw AAC access units. ADTS is a file/stream
             framing header, not part of the AU, so shipping it inside the RTP
             payload puts seven junk bytes in front of every frame.

The verdict is bytes written by ffmpeg, never the absence of stderr: `-f null`
returns 0 while extracting nothing, which is how a probe over a broken stream
looks identical to a probe over a good one.
"""

import os
import re
import socket
import subprocess
import sys
import threading
import time

sys.path.insert(0, "/root/OcuBea/tools")

import aac_framing_probe as P

ADTS_HEADER = 7


def build_sdp(with_config):
    fmtp = ("mode=AAC-hbr;profile-level-id=1;sizelength=16;"
            "indexlength=0;indexdeltalength=0")
    if with_config:
        fmtp += ";config=1188"
    return (
        "v=0\r\n"
        "o=- 0 0 IN IP4 127.0.0.1\r\n"
        "s=aac layout probe\r\n"
        "c=IN IP4 127.0.0.1\r\n"
        "t=0 0\r\n"
        "a=control:*\r\n"
        "m=audio 0 RTP/AVP 97\r\n"
        "a=rtpmap:97 MPEG4-GENERIC/48000/1\r\n"
        f"a=fmtp:97 {fmtp}\r\n"
        "a=control:trackID=0\r\n"
    )


def serve_once(port, sdp, packets, hold=3.0):
    """One RTSP session: answer OPTIONS/DESCRIBE/SETUP/PLAY, then push packets."""
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(1)

    def run():
        try:
            conn, _ = srv.accept()
        except OSError:
            return
        conn.settimeout(6)
        seq = 1000
        ts = 0
        buf = b""
        try:
            while True:
                data = conn.recv(65536)
                if not data:
                    return
                buf += data
                while b"\r\n\r\n" in buf:
                    head, _, buf = buf.partition(b"\r\n\r\n")
                    verb = head.decode("latin1", "replace").split("\r\n")[0].split(" ")[0]
                    seq += 1
                    if verb == "DESCRIBE":
                        conn.sendall(
                            f"RTSP/1.0 200 OK\r\nCSeq: {seq}\r\n"
                            f"Content-Type: application/sdp\r\n"
                            f"Content-Base: rtsp://127.0.0.1:{port}/x\r\n"
                            f"Content-Length: {len(sdp)}\r\n\r\n{sdp}".encode())
                    elif verb == "SETUP":
                        conn.sendall(
                            f"RTSP/1.0 200 OK\r\nCSeq: {seq}\r\n"
                            "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n"
                            f"Session: {port};timeout=60\r\n\r\n".encode())
                    elif verb == "PLAY":
                        conn.sendall(
                            f"RTSP/1.0 200 OK\r\nCSeq: {seq}\r\n"
                            f"Session: {port}\r\nRange: npt=0.000-\r\n\r\n".encode())
                        for body in packets:
                            hdr = bytearray(12)
                            hdr[0] = 0x80
                            hdr[1] = 0x80 | 97
                            hdr[2] = (seq >> 8) & 0xFF
                            hdr[3] = seq & 0xFF
                            hdr[4] = (ts >> 24) & 0xFF
                            hdr[5] = (ts >> 16) & 0xFF
                            hdr[6] = (ts >> 8) & 0xFF
                            hdr[7] = ts & 0xFF
                            hdr[10] = 0x04
                            pkt = bytes(hdr) + body
                            conn.sendall(
                                b"\x24\x00" + len(pkt).to_bytes(2, "big") + pkt)
                            seq += 1
                            ts += 1024
                        time.sleep(hold)
                        return
                    else:
                        conn.sendall(
                            f"RTSP/1.0 200 OK\r\nCSeq: {seq}\r\n\r\n".encode())
        except OSError:
            pass
        finally:
            srv.close()

    threading.Thread(target=run, daemon=True).start()


def measure(name, port, sdp, packets, floor):
    serve_once(port, sdp, packets)
    time.sleep(0.2)
    out = f"/tmp/layout_{name}.aac"
    if os.path.exists(out):
        os.remove(out)
    proc = subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-rtsp_transport", "tcp",
         "-i", f"rtsp://127.0.0.1:{port}/x", "-t", "4",
         "-map", "0:a:0", "-c:a", "copy", out],
        capture_output=True, text=True, timeout=60)
    written = os.path.getsize(out) if os.path.exists(out) else 0
    complaints = [l for l in proc.stderr.splitlines()
                  if "AU header" in l or "packet size" in l or "Invalid" in l]
    ok = written >= floor and not complaints
    detail = complaints[0].split("] ")[-1][:44] if complaints else ""
    print(f"  {name:34s} {written:7d} B  "
          f"{'*** DEKODUJE ***' if ok else 'odrzucone'}  {detail}")
    return ok


def main():
    units = P.real_access_units("192.168.1.29", 8554)
    if not units:
        print("  brak jednostek z telefonu - probe nie ma czego odtworzyc")
        return 1
    floor = len(units) * 80
    print(f"  {len(units)} jednostek, {sum(len(u) for u in units)} B, "
          f"próg = {floor} B\n")

    port = 19100
    results = {}
    for with_config in (True, False):
        sdp = build_sdp(with_config)
        for with_headers in (True, False):
            for strip_adts in (True, False):
                name = (f"config={int(with_config)}"
                        f"_hdr={int(with_headers)}"
                        f"_raw={int(strip_adts)}")
                packets = []
                for unit in units:
                    au = unit[ADTS_HEADER:] if strip_adts else unit
                    body = (len(au).to_bytes(2, "big") + au) if with_headers else au
                    packets.append(body)
                port += 1
                results[name] = measure(name, port, sdp, packets, floor)

    winners = [n for n, ok in results.items() if ok]
    print()
    if len(winners) == 1:
        print(f"  >>> LAYOUT: {winners[0]}")
    elif winners:
        print(f"  >>> ffmpeg akceptuje: {', '.join(winners)}")
    else:
        print("  >>> zaden layout nie przeszedl - patrz raport")
    return 0


if __name__ == "__main__":
    sys.exit(main())
