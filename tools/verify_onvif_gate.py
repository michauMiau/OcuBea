#!/usr/bin/env python3
"""Prove tools/onvif_verify.py fails on a service that is not ONVIF.

onvif_verify.py is the only check that answers "would a real NVR accept this
camera", and an NVR is unforgiving: it fetches the WSDL, generates stubs from it,
and calls operations by name. A device that returns 200 with something that
merely mentions the right words is not a camera, it is a decoy, and the failure
shows up in the NVR's UI as "no response from device" long after the app looks
healthy.

Nothing runs this in CI because it needs a phone. That is a real gap: 14 checks
exist to catch a fake ONVIF, and the only way to find out they work is a device
that has stopped being one. So this stands in two servers -- a fake one that
faults everything, and one that echoes the right words while being useless -- and
requires the probe to reject both.

The second one is the point. A first attempt here only checked that the probe
fails when the server faults, which is easy: anything fails that. The echo server
answers every request with a well-formed envelope containing the operation names,
the profile token, the encoder configuration and a stream URI, all the strings a
lazy implementation greps for. If the probe passed it, it would be worthless --
and it does not: exit 1, with 7 of 14 checks failing.

Run: python3 tools/verify_onvif_gate.py
"""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import tempfile
import textwrap
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOL = os.path.join(REPO, "tools", "onvif_verify.py")

FAULTING = textwrap.dedent(
    """
    import http.server, sys
    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def _send(self, code, body, ct="application/soap+xml; charset=utf-8"):
            b = body.encode()
            self.send_response(code)
            self.send_header("Content-Type", ct)
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
        def do_GET(self):
            if "wsdl" in self.path:
                self._send(200, "<!-- minimal wsdl -->", "text/xml")
            else:
                self._send(404, "nope", "text/plain")
        def do_POST(self):
            n = int(self.headers.get("Content-Length") or 0)
            self.rfile.read(n)
            self._send(500, '<s:Envelope><s:Body><s:Fault>'
                            '<s:Code><s:Value>s:Receiver</s:Value></s:Code>'
                            '</s:Fault></s:Body></s:Envelope>')
    http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
    """
)

ECHO = textwrap.dedent(
    """
    import http.server, sys, json
    # A decoy: well-formed envelopes carrying every string a grep-based probe
    # looks for, and nothing an NVR could actually use.
    WSDL = ("<definitions><message><name>GetProfiles</name></message>"
            "<message><name>GetStreamUri</name></message>"
            "<message><name>GetDeviceInformation</name></message></definitions>")
    RESP = {
        "GetSystemDateAndTime": "GetSystemDateAndTimeResponse",
        "GetServices": "GetServicesResponse",
        "GetDeviceInformation": "GetDeviceInformationResponse",
        "GetCapabilities": "GetCapabilitiesResponse",
        "GetProfiles": "GetProfilesResponse",
        "GetStreamUri": "GetStreamUriResponse",
        "GetSnapshotUri": "GetSnapshotUriResponse",
    }
    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def _s(self, code, body, ct="application/soap+xml; charset=utf-8"):
            b = body.encode()
            self.send_response(code)
            self.send_header("Content-Type", ct)
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
        def do_GET(self):
            if "wsdl" in self.path:
                self._s(200, WSDL, "text/xml")
            elif "status.json" in self.path:
                self._s(200, json.dumps({"auth_enabled": False}), "application/json")
            else:
                self._s(404, "x", "text/plain")
        def do_POST(self):
            n = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(n).decode("utf-8", "replace")
            op = next((k for k in RESP if k in raw), "")
            r = RESP.get(op, "")
            body = ('<s:Envelope xmlns:s="http://www.onvif.org/ver20/schema">'
                    '<s:Body><' + r + '>'
                    '<Profiles token="prof1"><Profile token="prof1">'
                    '<VideoEncoderConfiguration><Encoding>H264</Encoding>'
                    '<Resolution><Width>1280</Width><Height>720</Height></Resolution>'
                    '</VideoEncoderConfiguration></Profile></Profiles>'
                    '<StreamUri>rtsp://127.0.0.1:8554/x.sdp</StreamUri>'
                    '<Uri>rtsp://127.0.0.1:8554/x.sdp</Uri>'
                    '</' + r + '></s:Body></s:Envelope>')
            self._s(200, body)
    http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
    """
)


def serve(source: str, port: int) -> subprocess.Popen:
    path = os.path.join(tempfile.gettempdir(), f"onvif-fake-{port}.py")
    with open(path, "w") as fh:
        fh.write(source)
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


def probe(port: int) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["python3", TOOL, f"http://127.0.0.1:{port}"],
        capture_output=True,
        text=True,
        timeout=400,
    )


def main() -> int:
    if not os.path.exists(TOOL):
        print(f"brak narzedzia {TOOL}")
        return 1

    failures: list[str] = []
    # Two ports, chosen from the ephemeral range and checked for collisions by
    # letting the bind fail loudly rather than silently probing the wrong server.
    port_a, port_b = 18211, 18212
    servers = []
    try:
        for label, src, port in (
            ("serwer faultujacy kazda operacje", FAULTING, port_a),
            ("serwer-echo: poprawne slowa, bezuzyteczna usluga", ECHO, port_b),
        ):
            servers.append((label, serve(src, port)))
            got = probe(port)
            passed = got.stdout.count("PASS")
            failed = got.stdout.count("FAIL")
            if got.returncode == 1 and failed > 0:
                print(f"  ok: '{label}' -> exit 1, {failed} FAIL z {passed + failed} "
                      f"spraw")
                if failed <= passed:
                    # A probe that rejects almost nothing has stopped reading the
                    # response, and exit 1 alone would not show it.
                    print(f"  uwaga: tylko {failed} z {passed + failed} odrzucone")
            elif got.returncode == 0:
                print(f"  BLAD: '{label}' przeszedl - probe nieodroznia atrapy "
                      f"od ONVIF")
                failures.append(label)
            else:
                print(f"  BLAD: '{label}' -> exit {got.returncode}, oczekiwano 1")
                failures.append(label)

        # Nothing listening: must not look like a pass either.
        quiet = subprocess.run(
            ["python3", TOOL, "http://127.0.0.1:18213"],
            capture_output=True,
            text=True,
            timeout=400,
        )
        if quiet.returncode == 1:
            print("  ok: nic nie slucha -> exit 1")
        else:
            print(f"  BLAD: nic nie slucha -> exit {quiet.returncode}, oczekiwano 1")
            failures.append("nothing listening")
    finally:
        for _, proc in servers:
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
    print("  bramka onvif: atrapa odrzucona, echo-atrapa odrzucona, cisza odrzucona")
    return 0


if __name__ == "__main__":
    sys.exit(main())
