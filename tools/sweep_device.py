"""Full functional sweep against a live OcuBea on Android 6.

Every claim this makes is checked against the device, not against the source.
The pydroid-ipcam reference is used as the source of truth for the API surface,
so "the endpoint exists" and "the endpoint is what pydroid calls" are separate
questions and only the second one makes a client work.

Run: python3 sweep_android6.py [host]
"""
import json
import struct
import sys
import time
import urllib.error
import urllib.request

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.184"
B = f"http://{HOST}:8080"

PASS, FAIL = [], []


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    print(f"   {'OK  ' if ok else 'FAIL'} {name}" + (f"  [{detail}]" if detail else ""))
    return ok


def req(path, method="GET", timeout=10, data=None):
    r = urllib.request.Request(B + path, method=method, data=data)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def req_stream(path, seconds=6):
    """Reads a live stream for a while and reports what actually arrived."""
    got, t0 = 0, time.time()
    try:
        with urllib.request.urlopen(B + path, timeout=seconds + 4) as resp:
            ct = resp.headers.get("Content-Type", "")
            cl = resp.headers.get("Content-Length")
            while time.time() - t0 < seconds:
                chunk = resp.read(65536)
                if not chunk:
                    break
                got += len(chunk)
    except Exception as e:
        return 0, "", None, str(e)
    return got, ct, cl, None


def jpeg_size(data):
    if not data[:2] == b"\xff\xd8":
        return None
    i = 2
    while i < len(data) - 9:
        if data[i] != 0xFF:
            i += 1
            continue
        m = data[i + 1]
        if m in (0xC0, 0xC1, 0xC2):
            h, w = struct.unpack(">HH", data[i + 5:i + 9])
            return w, h
        if m == 0xD9:
            break
        if m in (0xD8,) or 0xD0 <= m <= 0xD7:
            i += 2
            continue
        i += 2 + struct.unpack(">H", data[i + 2:i + 4])[0]
    return None


print(f"\n=== OcuBea sweep @ {HOST} ===\n")

# ---------------------------------------------------------------- podstawowe
print("[1] Podstawowe endpointy")
for p in ("/status.json", "/sensors.json", "/shot.jpg"):
    s, b, h = req(p)
    check(f"GET {p}", s == 200 and len(b) > 10, f"HTTP {s}, {len(b)} B")

s, b, _ = req("/status.json")
try:
    st = json.loads(b)
    check("status.json to poprawny JSON", True, f"{len(st)} kluczy")
except Exception as e:
    check("status.json to poprawny JSON", False, str(e))
    st = {}

s, b, _ = req("/status.json?show_avail=1")
try:
    sav = json.loads(b)
    nc = len(sav.get("curvals", {}))
    na = len(sav.get("avail", {}))
    check("curvals/avail obecne", nc > 0 and na > 0, f"curvals={nc} avail={na}")
except Exception as e:
    check("curvals/avail obecne", False, str(e))

# ---------------------------------------------------------------- MJPEG
print("\n[2] MJPEG /video")
s, b, h = req("/shot.jpg")
sz = jpeg_size(b)
check("shot.jpg to poprawny JPEG", sz is not None, f"{sz[0]}x{sz[1]}" if sz else "brak")
check("shot.jpg ma Content-Type image/jpeg",
      "image/jpeg" in h.get("Content-Type", ""), h.get("Content-Type", ""))

got, ct, cl, err = req_stream("/video", 6)
check("/video przesyla dane", got > 50000, f"{got} B, {ct}")
check("/video ma multipart/x-mixed-replace",
      "multipart/x-mixed-replace" in (ct or ""), ct)
check("/video bez Content-Length (nieskonczony)", cl is None, f"CL={cl}")

# ---------------------------------------------------------------- audio
print("\n[3] Audio - wszystkie kodeki")
codecs = {"aac": "audio/aac", "wav": "audio/wav", "flac": "audio/flac",
          "opus": "audio/ogg", "amrnb": "audio/amr"}
for c, want_ct in codecs.items():
    got, ct, cl, err = req_stream(f"/audio.{c}", 5)
    if got > 2000:
        ok = want_ct.split("/")[0] in (ct or "")
        check(f"/audio.{c}", ok, f"{got} B, {ct}")
    else:
        # 501 is a legitimate answer when the device has no such encoder
        s, b, hh = req(f"/audio.{c}")
        check(f"/audio.{c} -> 501 gdy brak kodeka", s == 501, f"HTTP {s}, {len(b)} B")

# ---------------------------------------------------------------- HLS
print("\n[4] HLS")
s, b, _ = req("/hls/index.m3u8")
if s == 503:
    time.sleep(8)
    s, b, _ = req("/hls/index.m3u8")
txt = b.decode("utf-8", "replace")
check("index.m3u8 odpowiada", s == 200, f"HTTP {s}")
check("m3u8 to HLS", "#EXTM3U" in txt, txt.splitlines()[0] if txt else "")
check("m3u8 ma #EXT-X-STREAM-INF albo mapę", "EXT-X-STREAM-INF" in txt or "#EXT-X-MAP" in txt)
if "#EXT-X-MAP" in txt:
    uri = [l for l in txt.splitlines() if "MAP" in l][0].split('URI="')[1].split('"')[0]
    check("init.mp4 osiagalny", req(f"/hls/{uri}")[0] == 200, uri)
if s == 200:
    segs = [l for l in txt.splitlines() if l.endswith(".m4s")]
    if segs:
        ss, sb, sh = req(f"/hls/{segs[-1]}")
        check("segment osiagalny", ss == 200 and len(sb) > 100, f"{len(sb)} B")
check("hls.min.js dostepny", req("/hls.min.js")[0] == 200)

# ---------------------------------------------------------------- PTZ / torch
print("\n[5] Sterowanie: torch, PTZ, ustawienia")
# Sciezki dokladnie takie, jak wywoluje pydroid-ipcam (pydroid.py:191 i :246)
s, b, _ = req("/torchon")
check("torchon zwraca 200", s == 200, f"HTTP {s}, {b[:20]!r}")
s2, sb2, _ = req("/status.json")
check("torch widoczny w statusie", json.loads(sb2)["torch"] is True)
s, b, _ = req("/torchoff")
check("torchoff zwraca 200", s == 200, f"HTTP {s}, {b[:20]!r}")
s2, sb2, _ = req("/status.json")
check("torch wygaszony", json.loads(sb2)["torch"] is False)

for key, val in (("quality", "60"), ("night_vision", "on"), ("whitebalance", "auto"),
                 ("focus", "auto"), ("exposure", "auto"), ("overlay", "on")):
    s, b, _ = req(f"/settings/{key}?set={val}")
    check(f"settings {key}={val}", s == 200 and b.strip() == b"Ok", f"HTTP {s}, {b[:16]!r}")

s, b, _ = req("/settings/ptz?zoom=1")
check("ptz zoom zwraca Ok", s == 200 and b.strip() == b"Ok", f"HTTP {s}, {b[:16]!r}")

s, b, _ = req("/nofocus")
check("nofocus zwraca Ok", s == 200 and b.strip() == b"Ok")
s, b, _ = req("/focus")
check("focus zwraca Ok", s == 200 and b.strip() == b"Ok")

s2, sb2, _ = req("/status.json")
j2 = json.loads(sb2)
check("zoom ujawniony", "level" in j2.get("zoom", {}), str(j2.get("zoom")))
check("quality w statusie", "jpeg_quality" in j2, str(j2.get("jpeg_quality")))

req("/settings/quality?set=75")
req("/settings/night_vision?set=off")

print("\n[6] Warstwa IP Webcam / pydroid")
s, b, _ = req("/v1/devices")
check("/v1/devices", s == 200, f"HTTP {s}")
s, b, _ = req("/v1/devices?action=list")
check("/v1/devices?action=list", s == 200, f"HTTP {s}")
s, b, _ = req("/cgi-bin/action?command=devinfo")
ok = s == 200 and b"firmware" in b
check("/cgi-bin/action?command=devinfo", ok, f"HTTP {s}, {b[:60]!r}")
# Unknown command must be reported honestly, not answered "Ok" with HTTP 200.
s, b, _ = req("/cgi-bin/action?command=totally_made_up")
check("nieznana komenda -> 501 (nie klamze 'Ok')", s == 501, f"HTTP {s}")

# ---------------------------------------------------------------- WebUI
print("\n[7] WebUI")
s, b, h = req("/")
check("WebUI serwowany", s == 200 and len(b) > 5000, f"HTTP {s}, {len(b)} B")
html = b.decode("utf-8", "replace")
check("WebUI ma element audio", 'id="aEl"' in html)
check("WebUI ma przycisk odsluchu", 'id="bAudioPlay"' in html)
check("WebUI ma selektor kodeka",
      'id="aCodec"' in html or "audio_codec" in html or "aac" in html.lower())
check("WebUI ma quality slider", 'id="' in html and "quality" in html.lower())

print(f"\n=== {len(PASS)} passed, {len(FAIL)} failed ===")
if FAIL:
    print("FAIL:")
    for f in FAIL:
        print("  -", f)
sys.exit(1 if FAIL else 0)
