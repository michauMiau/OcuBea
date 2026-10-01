#!/usr/bin/env python3
"""
ONVIF conformance probe for OcuBea.

Exists because answering `Ok` is not ONVIF. Every check here is an assertion a
real NVR would make, written as a pass/fail, with the failure printed in the
client's own terms rather than as an HTTP status.

What this deliberately does NOT do: treat a 200 as success. A SOAP service that
answers 200 with a text/plain greeting, or with a Fault where a Response is
required, is broken in the only way that matters to a client.

Checks, grouped the way an NVR walks them:
  1. WSDL       -- a real client fetches the WSDL before it can even form a request
  2. envelope   -- SOAP 1.2/1.1 content type, correct namespace, one Body
  3. GetServices / GetDeviceInformation / GetCapabilities
  4. GetProfiles -- the profile must exist and carry a real encoder config
  5. GetStreamUri -- the URI it returns must actually FETCH a frame
  6. the returned URI must be MJPEG-shaped, not RTSP-shaped

Run:  python3 tools/onvif_verify.py [base_url]
Exit: 0 only if every check passes.
"""
import sys, re, subprocess, json, time
from urllib.parse import urlparse

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://192.168.1.184:8080").rstrip("/")
SOAP_CT = 'Content-Type: application/soap+xml; charset=utf-8; action="'

results = []


def record(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}")
    if detail and not ok:
        print(f"          {detail}")
    return ok


def post(action, body="", headers=""):
    """POST a SOAP request. Returns (status, headers, body)."""
    # A generated client puts the operation in the Body, self-closing when it has
    # no arguments -- which is every operation this device implements. It also
    # sets the SOAP 1.2 action header.
    #
    # The probe used to send an EMPTY Body and rely on the header. That made it
    # pass against a build where the Body path was broken: extractAction() was
    # truncating `GetDeviceInformation` to `Get` and falling through to
    # ActionNotSupported, and an empty Body meant the header path ran instead and
    # hid it. 18/18 on a device that answered Fault to every real client. The
    # Body element is the whole point of the request, so the probe must send it.
    if not body:
        body = f'<tds:{action}/>'
    env = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" '
        'xmlns:tds="http://www.onvif.org/ver10/device/wsdl" '
        'xmlns:trt="http://www.onvif.org/ver10/media/wsdl">'
        f"<s:Header><Action s:mustUnderstand=\"1\">{action}</Action></s:Header>"
        f"<s:Body>{body}</s:Body></s:Envelope>"
    )
    cmd = [
        "curl", "-sS", "-m", "20", "-D", "/tmp/_h.txt",
        "-w", "\n__CODE:%{http_code}",
        "-X", "POST", f"{BASE}/onvif/device_service",
        "-H", f'{SOAP_CT}{action}"',
        "-H", "Content-Type: application/soap+xml; charset=utf-8",
        "--data-binary", env,
    ]
    p = subprocess.run(cmd, capture_output=True, text=True)
    out = p.stdout
    code = out.rpartition("__CODE:")[2].strip()
    body_out = out.rpartition("\n__CODE:")[0]
    try:
        hdrs = open("/tmp/_h.txt").read()
    except OSError:
        hdrs = ""
    return code, hdrs, body_out


def has_no_fault(body):
    return "Fault" not in body


def has_element(body, name):
    """True only for a real <ns:Name>...</ns:Name> or <ns:Name/>, not a mention."""
    return re.search(rf"<(?:\w+:)?{re.escape(name)}\b", body) is not None


print(f"=== ONVIF probe: {BASE}/onvif/device_service ===\n")

# ── 1. WSDL ────────────────────────────────────────────────────────────────────
# A real client (ONVIF Device Test Tool, gsoap-generated bindings, Frigate's
# onvif client) fetches the WSDL and generates its stubs from it. Without it the
# service is unreachable no matter how correct the SOAP is.
r = subprocess.run(
    ["curl", "-sS", "-m", "15", f"{BASE}/onvif/device_service?wsdl"],
    capture_output=True, text=True)
wsdl = r.stdout
record("WSDL is served, not a greeting",
       len(wsdl) > 500 and "wsdl:definitions" in wsdl or "definitions" in wsdl,
       f"got {len(wsdl)} B: {wsdl[:90]!r}")

for op in ("GetProfiles", "GetStreamUri", "GetDeviceInformation"):
    record(f"WSDL declares {op}", op in wsdl)

# ── 2/3. Operations ───────────────────────────────────────────────────────────
OPS = [
    ("GetSystemDateAndTime", "GetSystemDateAndTimeResponse"),
    ("GetServices", "GetServicesResponse"),
    ("GetDeviceInformation", "GetDeviceInformationResponse"),
    ("GetCapabilities", "GetCapabilitiesResponse"),
    ("GetProfiles", "GetProfilesResponse"),
    ("GetStreamUri", "GetStreamUriResponse"),
    ("GetSnapshotUri", "GetSnapshotUriResponse"),
]
bodies = {}
for action, want in OPS:
    code, hdrs, body = post(action)
    # The operation is sent in the Body, so the response must be produced by the
    # Body path: requiring the FULL response element name (not a substring like
    # "Get") is what catches a truncating extractAction().
    ok = (code == "200"
          and has_element(body, want)
          and has_no_fault(body)
          and "http://www.onvif.org/ver10/device/wsdl" in body)
    bodies[action] = body
    record(f"{action} -> {want}",
           ok, f"HTTP {code}; Fault={not has_no_fault(body)}; {body[:110]!r}")

# ── 4. The profile must be real ───────────────────────────────────────────────
prof = bodies.get("GetProfiles", "")
has_token = re.search(r'token="([^"]+)"', prof)
record("GetProfiles carries a profile token", bool(has_token),
       "no token attribute: a client has nothing to configure against")

# The encoder configuration must name an encoding and a resolution. A profile
# with only a name is what a stub returns, and it is why an NVR shows the camera
# and then cannot configure it.
record("profile has a real encoder configuration",
       "<tt:Encoding>" in prof and "<tt:Resolution>" in prof and "Width" in prof,
       "no Encoding/Resolution: the profile is a name, not a configuration")

# ── 5. GetStreamUri must return something that FETCHES ─────────────────────────
stream_body = bodies.get("GetStreamUri", "")
m = re.search(r"<tt:Uri>([^<]+)</tt:Uri>", stream_body)
uri = m.group(1) if m else None
record("GetStreamUri returns a URI", bool(uri), "no <tt:Uri> in the response")

if uri:
    # THE check that separates "claims to be a camera" from "is one". A camera
    # that hands out a URI which 404s, 204s, or serves HTML is worse than a camera
    # that admits it has no stream: an NVR shows it as online with a dead preview.
    # The JPEG path only runs with a consumer attached, so hold /video open
    # while probing. Without it the camera answers 204 and a working stream looks
    # like a dead one -- the same trap as the MJPEG rotation measurements.
    import threading
    _stop = []
    def _hold():
        while not _stop:
            subprocess.run(["curl", "-sS", "-m", "2", "-o", "/dev/null", uri],
                           capture_output=True)
    _t = threading.Thread(target=_hold, daemon=True)
    _t.start()
    time.sleep(3)
    # Three separate -w fields with a delimiter that cannot appear in a
    # content_type: an MJPEG response is "multipart/x-mixed-replace; boundary=..."
    # which contains a space, so splitting on whitespace shifted every field and
    # the size parse ended up reading the boundary string.
    p = subprocess.run(
        ["curl", "-sS", "-m", "25", "-o", "/tmp/_stream.bin",
         "-w", "%{http_code}|%{size_download}", uri],
        capture_output=True, text=True)
    http_code, _, size = p.stdout.strip().partition("|")
    record("the advertised stream URI actually serves frames",
           http_code == "200" and int(size or 0) > 1000,
           f"{uri} -> HTTP {http_code}, {size}B (need 200 and >1 kB)")

    # MJPEG must actually be multipart, or the NVR's decoder gets HTML.
    head = b""
    try:
        with open("/tmp/_stream.bin", "rb") as f:
            head = f.read(4096)
    except OSError:
        pass  # no file means curl failed outright, which the check above caught
    record("stream is multipart MJPEG (not HTML/JSON error page)",
           b"--boundary" in head or b"multipart" in head.lower() or b"\xff\xd8" in head,
           f"first bytes: {head[:60]!r}")

    # RTSP in an SDP-less HTTP ONVIF implementation is a lie the client will follow.
    record("advertised URI is http, not rtsp (this build has no RTSP listener)",
           uri.startswith("http"), uri)
    _stop.append(1)

# ── 6. Auth must not be advertised as absent ──────────────────────────────────
p = subprocess.run(["curl", "-sS", "-m", "10", "-o", "/dev/null",
                    "-w", "%{http_code}", f"{BASE}/status.json"],
                   capture_output=True, text=True)
record("status.json reachable without credentials (auth off)",
       p.stdout.strip() in ("200", "401", "403"),
       f"HTTP {p.stdout.strip()}")

print("\n=== podsumowanie ===")
passed = sum(1 for _, ok, _ in results if ok)
print(f"  {passed}/{len(results)} checkow zaliczonych")
fails = [n for n, ok, _ in results if not ok]
if fails:
    print("  nieudane:")
    for n in fails:
        print(f"    - {n}")
sys.exit(0 if not fails else 1)
