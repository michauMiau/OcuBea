#!/usr/bin/env python3
"""Prove tools/rtsp_verify.py rejects a server that only answers 200.

RTSP is the part of this app an NVR actually consumes, and the failure that
matters here is specific and has already happened once: a server can complete
OPTIONS, DESCRIBE, SETUP and PLAY with 200 on every one, announce a track in its
SDP, and never send a byte of RTP. That reads as a working camera to anything
watching status codes, and shows up as a stream that connects and then shows
nothing.

rtsp_verify.py exists to refuse that, and it needs a phone to run, so CI never
runs it. This stands in a decoy that answers 200 to everything and stays silent,
and requires the probe to fail.

Two things got measured while writing it, both worth keeping:

  - The decoy is rejected, 7 checks failing, exit 1. The checks that pass it are
    the honest ones -- it does speak RTSP 1.0 and does advertise Public -- so
    the verdict comes from the payload checks, not from a connection succeeding.

  - The probe takes host and port as separate arguments, not a URL. Passing
    "rtsp://127.0.0.1:18556/h264.sdp" as one argument makes it read port 8554,
    where nothing was listening, declare RTSP OFF, and exit 0 with 1/1 checks
    passed. That is a green result about a port nobody asked about, so the
    invocation is spelled out here rather than left to whoever runs it next.

Also checked: a socket present while RTSP is off is a failure, not a pass. That
is the entire OFF feature -- a socket that answers and refuses is not the same as
no socket.

Run: python3 tools/verify_rtsp_gate.py
"""

from __future__ import annotations

import os
import signal
import socket
import subprocess
import sys
import tempfile
import textwrap
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOL = os.path.join(REPO, "tools", "rtsp_verify.py")

# Answers 200 to every method, advertises the standard methods, sends no RTP.
DECOY = textwrap.dedent(
    """
    import socket, sys, threading
    port = int(sys.argv[1])
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(8)
    def handle(c):
        try:
            buf = b""
            while b"\\r\\n\\r\\n" not in buf:
                d = c.recv(4096)
                if not d:
                    return
                buf += d
            head = buf.split(b"\\r\\n\\r\\n")[0].decode("utf-8", "replace")
            parts = head.split("\\r\\n")[0].split()
            method = parts[0] if parts else "?"
            cseq = ""
            for h in head.split("\\r\\n")[1:]:
                if h.lower().startswith("cseq:"):
                    cseq = h.split(":", 1)[1].strip()
            c.sendall((
                "RTSP/1.0 200 OK\\r\\n"
                f"CSeq: {cseq}\\r\\n"
                "Public: DESCRIBE, SETUP, PLAY, TEARDOWN, OPTIONS\\r\\n"
                "Content-Length: 0\\r\\n\\r\\n"
            ).encode())
        except Exception:
            pass
        finally:
            try:
                c.close()
            except Exception:
                pass
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()
    """
)


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def serve(port: int) -> subprocess.Popen:
    path = os.path.join(tempfile.gettempdir(), f"rtsp-decoy-{port}.py")
    with open(path, "w") as fh:
        fh.write(DECOY)
    proc = subprocess.Popen(
        ["python3", path, str(port)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    for _ in range(40):
        time.sleep(0.1)
        if proc.poll() is None:
            break
    return proc


def probe(port: int, on: bool) -> subprocess.CompletedProcess:
    cmd = ["python3", TOOL, "127.0.0.1", str(port)]
    if on:
        cmd.append("--on")
    return subprocess.run(cmd, capture_output=True, text=True, timeout=500)


def main() -> int:
    if not os.path.exists(TOOL):
        print(f"brak narzedzia {TOOL}")
        return 1

    failures: list[str] = []
    port_off = free_port()
    port_on = free_port()
    procs = []
    try:
        # 1. Decoy with RTSP considered on: 200 everywhere, no RTP ever.
        procs.append(serve(port_on))
        got = probe(port_on, on=True)
        npass, nfail = got.stdout.count("PASS"), got.stdout.count("FAIL")
        if got.returncode == 1 and nfail > 0:
            print(f"  ok: atrapa '200 na wszystko, zero RTP' -> exit 1 "
                  f"({nfail} FAIL, {npass} PASS)")
            if nfail <= npass:
                print(f"     uwaga: tylko {nfail} z {npass + nfail} odrzucone")
        else:
            print(f"  BLAD: atrapa przeszla -> exit {got.returncode} "
                  f"({npass} PASS, {nfail} FAIL)")
            for line in got.stdout.strip().split("\n")[:6]:
                print("     ", line.strip()[:90])
            failures.append("decoy accepted while on")

        # 2. A socket that exists while RTSP is off is the failure itself.
        procs.append(serve(port_off))
        got = probe(port_off, on=False)
        if got.returncode == 1:
            print("  ok: socket obecny przy RTSP off -> exit 1")
        else:
            print(f"  BLAD: socket obecny przy RTSP off -> exit {got.returncode}, "
                  f"oczekiwano 1")
            failures.append("socket while off accepted")

        # 3. Nothing listening, RTSP off: the real, expected state.
        quiet = free_port()
        got = subprocess.run(
            ["python3", TOOL, "127.0.0.1", str(quiet)],
            capture_output=True,
            text=True,
            timeout=500,
        )
        if got.returncode == 0:
            print("  ok: nic nie slucha przy RTSP off -> exit 0")
        else:
            print(f"  BLAD: pusty port przy RTSP off -> exit {got.returncode}, "
                  f"oczekiwano 0")
            failures.append("quiet port")
    finally:
        for proc in procs:
            proc.send_signal(signal.SIGTERM)
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()

    print()
    if failures:
        print(f"  BRAK: {len(failures)} z 3 spraw")
        for f in failures:
            print("   -", f)
        return 1
    print("  bramka rtsp: atrapa 200/zero-RTP odrzucona, socket przy off odrzucony, "
          "pusty port poprawny")
    return 0


if __name__ == "__main__":
    sys.exit(main())
