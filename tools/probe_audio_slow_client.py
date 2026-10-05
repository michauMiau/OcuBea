#!/usr/bin/env python3
"""Does a slow client survive the audio ring, or get dropped?

AudioRingBuffer.offer() returns false when the buffer is full and the documented
consequence is that the client is dropped. If that is what happens in practice,
then a browser whose decoder reads in bursts gets its stream cut, and the
symptom is audio that sounds damaged.

This is a measurement, not a reimplementation: it reads the real HTTP endpoint
on the real phone and reports what actually happens over time.
"""
import http.client
import socket
import sys
import time

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.184"
PORT = 8080
PATH = sys.argv[2] if len(sys.argv) > 2 else "/audio.flac"
READ = int(sys.argv[3]) if len(sys.argv) > 3 else 4096
SLEEP = float(sys.argv[4]) if len(sys.argv) > 4 else 0.25
SECONDS = float(sys.argv[5]) if len(sys.argv) > 5 else 20.0


def raw_socket(r):
    """Dig the socket out of http.client's buffering layers.

    Attribute access differs by Python version, and Pyright objects to
    `_sock` not being a declared attribute of the buffer type. Walking the
    known holder names and ignoring the AttributeError is honest here: a probe
    that raises on a version difference tells us nothing about the phone.
    """
    for holder in ("fp",):
        obj = getattr(r, holder, None)
        for path in (("_sock",), ("raw", "_sock"), ("raw", "sock")):
            cur = obj
            for name in path:
                cur = getattr(cur, name, None)
                if cur is None:
                    break
            if isinstance(cur, socket.socket):
                return cur
    return None


def main():
    c = http.client.HTTPConnection(HOST, PORT, timeout=15)
    c.request("GET", PATH)
    r = c.getresponse()
    print(f"  HTTP {r.status} {r.reason}  content-type={r.getheader('Content-Type')}")
    print(f"  reading {READ} B every {SLEEP}s, for {SECONDS}s")
    print()

    sock = raw_socket(r)
    if sock is not None:
        sock.settimeout(5.0)

    t0 = time.time()
    total = 0
    reads = 0
    gaps = []
    stalls = 0
    last = t0
    while time.time() - t0 < SECONDS:
        try:
            b = r.read(READ)
        except Exception as e:
            elapsed = time.time() - t0
            print(f"  [+] read failed after {elapsed:.1f}s: {type(e).__name__}: {e}")
            print(f"      bytes={total} reads={reads}  => STREAM BROKE")
            return 1
        if not b:
            elapsed = time.time() - t0
            print(f"  [+] EOF (clean end of stream) after {elapsed:.1f}s")
            print(f"      bytes={total} reads={reads}  => SERVER ENDED THE STREAM")
            return 1
        now = time.time()
        gap = now - last
        last = now
        if gap > 0.5:
            gaps.append((round(elapsed if (elapsed := now - t0) else 0, 1), round(gap, 2)))
            stalls += 1
        total += len(b)
        reads += 1
        time.sleep(SLEEP)

    elapsed = time.time() - t0
    print(f"  survived {elapsed:.1f}s: {reads} reads, {total} B")
    print(f"  effective rate: {total/elapsed/1024:.1f} KB/s over the wall clock")
    if gaps:
        print(f"  STALLS >0.5s: {stalls}")
        for at, g in gaps[:12]:
            print(f"      at {at:5.1f}s  gap {g:.2f}s")
    else:
        print("  no stall over 0.5s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
