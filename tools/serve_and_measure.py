#!/usr/bin/env python3
"""Serve app/src/main/assets/index.html locally and measure the tiles.

The phone carries the last installed APK, so --device reads a build that is one
edit behind after every src/ change. That is correct behaviour for the gate and
useless for iterating on CSS. Serve the repo bundle over loopback instead: the
same bytes Gradle will package, no install step, so a CSS change can be measured
within a minute instead of after a full build and adb install.

The page calls the phone for /status.json and the stream, so those requests will
fail against loopback. That is fine and is the point: layout, tile geometry and
styling do not depend on a live stream, and tools/shoot_web_ui.py already skips
checks for elements that are not visible.
"""
from __future__ import annotations

import contextlib
import functools
import http.server
import json
import pathlib
import socket
import sys
import threading

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
PORT = 8099


# Endpoints the page polls. Answering them keeps the page's own code paths
# quiet, so a real pageerror stands out instead of hiding under a wall of
# 404s from the stub.
STUB = {
        "/status.json": {
            "available": "aac=true opus=false amrnb=false flac=true wav=true default=aac",
            "mode": "mjpeg", "fps": 0, "viewers": 1, "frames": 0, "dropped": 0,
            "bitrate": 0, "uptime": 0, "width": 1280, "height": 720,
        },
        "/recordings": {"items": []},
        "/clips": {"items": []},
        "/settings": {},
        "/api/status": {"ok": True},
        # These three were 404ing from the stub and each one printed a console
        # error, which the gate then counted. A measurement harness that adds
        # noise to the thing it measures is its own defect: stub the endpoint
        # rather than let an unrelated 404 become a FAIL line.
        "/hls/profile": {"ok": True, "codec": "h264"},
        "/sensors.json": {"sensors": []},
        "/clips/recording": {"recording": False},
    }

class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format, *args):  # noqa: A002,D102 - silence logging
        pass

    def end_headers(self):
        # The page is rendered once per run; a cached CSS would hide the change
        # being measured, which is the exact failure this repo keeps hitting.
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def do_GET(self):  # noqa: N802
        path = self.path.split("?")[0].rstrip("/") or "/"
        if path in STUB:
            body = json.dumps(STUB[path]).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path.startswith("/api/"):
            body = b"{}"
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path.startswith("/favicon") or path.startswith("/video") \
                or path.startswith("/audio") or path.startswith("/snapshot"):
            self.send_response(204)
            self.end_headers()
            return
        return super().do_GET()


@contextlib.contextmanager
def server():
    handler = functools.partial(Quiet, directory=str(ASSETS))
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), handler)
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    try:
        yield f"http://127.0.0.1:{PORT}/"
    finally:
        httpd.shutdown()


def main() -> int:
    scripts = sys.argv[1:] or [
        "measure_tile_layout.py",
        "measure_radios_tiles.py",
    ]
    with server() as url:
        print(f"  serwuje {ASSETS} na {url}\n")
        rc = 0
        for s in scripts:
            print(f"=== {s} ===")
            import subprocess
            p = subprocess.run(
                [sys.executable, str(ROOT / "tools" / s), url],
                cwd=str(ROOT),
            )
            rc |= p.returncode
            print()
    return rc


if __name__ == "__main__":
    sys.exit(main())
